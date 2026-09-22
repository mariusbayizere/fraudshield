"""Build a servable bundle from the cached feature matrix (`fs-model build`).

**What this is, and what it is not.** It trains the model D-05 specifies — XGBoost and LightGBM
combined 0.55/0.45, one isotonic calibration on the combined score, per-model calibrators for
display — plus D-06's Isolation Forest, on the same cache and split the M4 battery reads, with the
same fixed hyperparameters C-6 used. It is **not** E.4's latency-constrained hyperparameter search,
five-seed selection or early stopping: those decide *which* configuration to ship and belong to the
training pipeline. This decides nothing; it packages one configuration so the scoring service has
the specified model to serve, and it reports the held-out numbers that model reaches beside the
single-feature floor (ML-GATE-13's reporting rule), so nobody mistakes the package for a result.

**Splits (D-07).** Train rows fit the boosters, the target encodings and the forest. Calibration
rows — the chronologically later tail of validation — fit the three isotonic calibrators and
nothing else. Test rows are scored once, for the report, and fit nothing.
"""

from __future__ import annotations

import hashlib
import math
import subprocess
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from fraudshield_ml.features.registry import REGISTRY, Dtype
from fraudshield_ml.metrics.single_feature import auc, separation
from fraudshield_ml.models import forest, isotonic
from fraudshield_ml.models.bundle import (
    LIGHTGBM_WEIGHT,
    XGBOOST_WEIGHT,
    Bundle,
    Encoding,
    feature_registry_version,
)
from fraudshield_ml.serving.features import WHOLE_DAY_FEATURES
from fraudshield_ml.training import battery, smoke
from fraudshield_ml.training.ensemble import (
    BOOSTING_ROUNDS,
    LIGHTGBM_PARAMETERS,
    XGBOOST_PARAMETERS,
    auc_interval,
)

#: `encode_categoricals`' default, repeated so the frozen serving table uses the same shrinkage.
PRIOR_WEIGHT = 50.0


@dataclass(frozen=True)
class Complexity:
    """Tree count and depth for both boosters, the quantity D-16 constrains.

    LightGBM gets `num_leaves = 2**depth - 1` so the two models have matched capacity, the rule
    C-6 used. The default is the configuration C-6 and the first bundles used; the served one is
    chosen from the frontier (ADR 0032).
    """

    trees: int = BOOSTING_ROUNDS
    depth: int = int(XGBOOST_PARAMETERS["max_depth"])  # type: ignore[call-overload]

    def xgboost(self, seed: int) -> dict[str, Any]:
        return {**XGBOOST_PARAMETERS, "max_depth": self.depth, "seed": seed}

    def lightgbm(self, seed: int) -> dict[str, Any]:
        return {
            **LIGHTGBM_PARAMETERS,
            "num_leaves": 2**self.depth - 1,
            "max_depth": self.depth,
            "seed": seed,
        }


@dataclass(frozen=True)
class Report:
    rows: Mapping[str, int]
    ensemble_auc: float
    #: Half-width of the ensemble AUC's 95% interval (Hanley-McNeil), so a configuration can be
    #: judged "within the interval of the best" from the report alone (D-16, ADR 0032).
    ensemble_auc_interval: float
    xgboost_auc: float
    lightgbm_auc: float
    floor: float
    ece_ensemble: float
    ece_raw: float
    recall_at_1pct_fpr: float

    def lines(self) -> list[str]:
        return [
            f"rows: train {self.rows['train']}, calibration {self.rows['calibration']}, "
            f"test {self.rows['test']}",
            f"test AUC: ensemble {self.ensemble_auc:.4f} ±{self.ensemble_auc_interval:.4f}, "
            f"XGBoost {self.xgboost_auc:.4f}, "
            f"LightGBM {self.lightgbm_auc:.4f}",
            f"single-feature floor ({smoke.FLOOR_FEATURE}, same rows): {self.floor:.4f}; "
            f"margin {self.ensemble_auc - self.floor:+.4f}",
            f"ECE (10 equal-width bins): calibrated {self.ece_ensemble:.5f}, "
            f"uncalibrated combination {self.ece_raw:.5f}",
            f"recall at 1% FPR (calibrated ensemble): {self.recall_at_1pct_fpr:.4f}",
        ]


def full_fit_encoding(
    values: Sequence[str], labels: Sequence[bool], train: Sequence[int]
) -> Encoding:
    """The estimate `encode_categoricals` gives every non-training row, as a lookup table."""
    positives = sum(1 for i in train if labels[i])
    base = positives / max(len(train), 1)
    totals: dict[str, list[float]] = {}
    for i in train:
        cell = totals.setdefault(values[i], [0.0, 0.0])
        cell[0] += float(labels[i])
        cell[1] += 1.0
    table = {
        category: (fraud + PRIOR_WEIGHT * base) / (seen + PRIOR_WEIGHT)
        for category, (fraud, seen) in totals.items()
    }
    return Encoding(table=table, prior=base)


def fit_boosters(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    seed: int,
    complexity: Complexity = Complexity(),  # noqa: B008 - frozen, immutable default
) -> tuple[Any, Any]:
    import lightgbm as lgb  # noqa: PLC0415 - heavy
    import xgboost as xgb  # noqa: PLC0415 - heavy

    x = np.asarray([matrix[i] for i in train], dtype=np.float64)
    y = np.asarray([float(labels[i]) for i in train])
    booster = xgb.train(
        complexity.xgboost(seed),
        xgb.DMatrix(x, label=y, missing=math.nan),
        complexity.trees,
    )
    light = lgb.train(complexity.lightgbm(seed), lgb.Dataset(x, label=y), complexity.trees)
    return booster, light


def serving_view(values: Mapping[str, float | str]) -> dict[str, float | str]:
    """A feature row as the scoring contract can deliver it.

    `AccountContext` carries the four ages in whole days (`uint32`), so the model is trained on
    whole days: floor for a non-negative age, NaN for a negative one, which is a data error the
    contract has no encoding for. Training on the fractional value would teach thresholds serving
    can never reproduce.
    """
    out = dict(values)
    for name in WHOLE_DAY_FEATURES:
        if name in out:
            value = float(out[name])
            if not math.isnan(value):
                out[name] = float(math.floor(value)) if value >= 0 else math.nan
    return out


def git_revision(root: Path) -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],  # noqa: S607 - git from PATH, as every tool here
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def build(
    vectors: Sequence[dict[str, float | str]],
    extras: Mapping[str, Sequence[str]],
    *,
    seed: int,
    provenance: Mapping[str, Any] | None = None,
    complexity: Complexity = Complexity(),  # noqa: B008 - frozen, immutable default
) -> tuple[Bundle, Report]:
    names = smoke.trainable_features()
    vectors = [serving_view(v) for v in vectors]
    labels = [v == "True" for v in extras[smoke.CACHE_LABEL]]
    segment = extras[smoke.CACHE_SEGMENT]
    by = {s: [i for i, g in enumerate(segment) if g == s] for s in ("train", "calibration", "test")}
    for part, rows in by.items():
        if not rows:
            raise ValueError(f"the cache has no {part} rows; a bundle needs all three splits")

    categorical = [n for n in names if REGISTRY[n].dtype is Dtype.CATEGORICAL]
    encoded = smoke.encode_categoricals(vectors, labels, extras[smoke.CACHE_ACCOUNT], by["train"])
    encodings = {
        n: full_fit_encoding([str(v[n]) for v in vectors], labels, by["train"]) for n in categorical
    }
    matrix = [
        [encoded[n][i] if n in encoded else float(vectors[i][n]) for n in names]
        for i in range(len(vectors))
    ]

    booster, light = fit_boosters(matrix, labels, by["train"], seed, complexity)

    def raw_scores(rows: Sequence[int]) -> tuple[list[float], list[float], list[float]]:
        x = np.asarray([matrix[i] for i in rows], dtype=np.float64)
        px = [float(p) for p in booster.inplace_predict(x, missing=math.nan)]
        pl = [float(p) for p in light.predict(x)]
        combined = [XGBOOST_WEIGHT * a + LIGHTGBM_WEIGHT * b for a, b in zip(px, pl, strict=True)]
        return px, pl, combined

    cal_labels = [labels[i] for i in by["calibration"]]
    cx, cl, cc = raw_scores(by["calibration"])
    calibration = {
        "ensemble": isotonic.fit(cc, cal_labels)[0],
        "xgboost": isotonic.fit(cx, cal_labels)[0],
        "lightgbm": isotonic.fit(cl, cal_labels)[0],
    }
    fitted_forest, _ = forest.fit([matrix[i] for i in by["train"]], seed=seed)

    test_labels = [labels[i] for i in by["test"]]
    tx, tl, tc = raw_scores(by["test"])
    calibrated = calibration["ensemble"].many(tc)
    floor_column = [float(vectors[i][smoke.FLOOR_FEATURE]) for i in by["test"]]
    ensemble_auc = auc(calibrated, test_labels)
    positives = sum(test_labels)
    report = Report(
        rows={k: len(v) for k, v in by.items()},
        ensemble_auc=ensemble_auc,
        ensemble_auc_interval=auc_interval(ensemble_auc, positives, len(test_labels) - positives),
        xgboost_auc=auc(tx, test_labels),
        lightgbm_auc=auc(tl, test_labels),
        floor=separation(floor_column, test_labels),
        ece_ensemble=battery.expected_calibration_error(
            battery.reliability(calibrated, test_labels)
        ),
        ece_raw=battery.expected_calibration_error(battery.reliability(tc, test_labels)),
        recall_at_1pct_fpr=smoke.recall_at_fpr(calibrated, test_labels, 0.01),
    )

    registry = feature_registry_version()
    content = hashlib.sha256()
    content.update(booster.save_raw("json"))
    content.update(light.model_to_string().encode())
    for cal in calibration.values():
        content.update(repr(cal).encode())
    bundle = Bundle(
        model_version=f"ensemble-{content.hexdigest()[:12]}",
        registry_version=registry,
        features=names,
        encodings=encodings,
        xgboost=booster,
        lightgbm=light,
        calibration=calibration,
        forest=fitted_forest,
        provenance={
            **(provenance or {}),
            "seed": seed,
            "whole_day_features": list(WHOLE_DAY_FEATURES),
            "trees": complexity.trees,
            "depth": complexity.depth,
            "xgboost_parameters": complexity.xgboost(seed),
            "lightgbm_parameters": complexity.lightgbm(seed),
            "held_out": {
                "rows": report.rows,
                "ensemble_auc": report.ensemble_auc,
                "ensemble_auc_interval": report.ensemble_auc_interval,
                "single_feature_floor": report.floor,
                "floor_feature": smoke.FLOOR_FEATURE,
                "ece_equal_width_10": report.ece_ensemble,
                "recall_at_1pct_fpr": report.recall_at_1pct_fpr,
            },
        },
    )
    return bundle, report


def build_from_cache(
    cache: Path,
    out: Path,
    *,
    seed: int,
    root: Path,
    complexity: Complexity = Complexity(),  # noqa: B008 - frozen, immutable default
) -> Report:
    import pyarrow.parquet as pq  # noqa: PLC0415

    loaded = smoke.cache_read_any(cache)
    if loaded is None:
        raise ValueError(f"{cache} holds no usable feature matrix")
    vectors, extras = loaded
    metadata = pq.read_schema(cache).metadata or {}
    bundle, report = build(
        vectors,
        extras,
        seed=seed,
        provenance={
            "git_revision": git_revision(root),
            "cache": str(cache),
            "cache_sha256": hashlib.sha256(cache.read_bytes()).hexdigest(),
            "cache_key": {k.decode(): v.decode() for k, v in metadata.items()},
        },
        complexity=complexity,
    )
    bundle.save(out)
    return report
