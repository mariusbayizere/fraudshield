"""Package M4's evaluated model into a servable bundle (`fs-model build`).

**This module trains nothing of its own.** It runs the functions M4's gate run uses
(`training.gate_run.load`, `training.model.fit_ensemble`, `training.anomaly.fit_anomaly`,
`training.onnx_export.export`) on the gate's cache and seed, and packages what they return. The gate
figures therefore describe the served model, not a sibling of it.

An earlier version of this module fitted its own ensemble, with the same weights and calibration but
without M4's imbalance weighting or early stopping. The rebase onto `m4-complete` exposed the
duplication (`docs/parallel/M5_updates.md`, section 10), and it was deleted.

**Parity is checked before anything is written.** The bundle's served path (ONNX Runtime, isotonic
knots) scores the whole test period. The build refuses to write unless every raw probability is
within 1e-5 of the native boosters (E.4's criterion) and no calibrated score crosses a risk tier
that `Ensemble.score` did not. The calibrated gap itself is reported, not bounded at 1e-5: the
isotonic map is identical on both paths but steep enough in places to amplify a 4e-7 raw
difference to ~2e-4, on 37 of 101,909 rows of the gate model's test period.
"""

from __future__ import annotations

import hashlib
import subprocess
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from fraudshield_ml.features.registry import REGISTRY, Dtype
from fraudshield_ml.metrics.single_feature import auc, separation
from fraudshield_ml.models import forest, isotonic
from fraudshield_ml.models.bundle import PARITY, Bundle, Encoding, feature_registry_version
from fraudshield_ml.serving.thresholds import DEFAULT
from fraudshield_ml.training import anomaly, battery, gate_run, model, onnx_export, smoke
from fraudshield_ml.training.ensemble import auc_interval

#: `encode_categoricals`' default, repeated so the frozen serving table uses the same shrinkage.
PRIOR_WEIGHT = 50.0


class ParityError(RuntimeError):
    """The packaged model does not score as the fitted model does; nothing was written."""


@dataclass(frozen=True)
class Report:
    rows: Mapping[str, int]
    xgboost_rounds: int
    lightgbm_rounds: int
    ensemble_auc: float
    #: Half-width of the ensemble AUC's 95% interval (Hanley-McNeil).
    ensemble_auc_interval: float
    floor: float
    ece_ensemble: float
    recall_at_1pct_fpr: float
    #: Largest |served - native| raw probability over the test period: E.4's parity quantity.
    parity: float
    #: Largest |served - fitted| calibrated score, and how many test rows exceed the bound there.
    calibrated_gap: float = 0.0
    calibrated_rows_over_bound: int = 0

    def lines(self) -> list[str]:
        return [
            f"rows: train {self.rows['train']}, validation {self.rows['validation']}, "
            f"calibration {self.rows['calibration']}, test {self.rows['test']}",
            f"rounds: XGBoost {self.xgboost_rounds}, LightGBM {self.lightgbm_rounds}",
            f"test AUC: ensemble {self.ensemble_auc:.4f} ±{self.ensemble_auc_interval:.4f}",
            f"single-feature floor ({smoke.FLOOR_FEATURE}, same rows): {self.floor:.4f}; "
            f"margin {self.ensemble_auc - self.floor:+.4f}",
            f"ECE (10 equal-width bins): {self.ece_ensemble:.5f}",
            f"recall at 1% FPR: {self.recall_at_1pct_fpr:.4f}",
            f"E.4 parity, ONNX against native, over the test period: {self.parity:.2e} "
            f"(bound {PARITY})",
            f"calibrated gap: {self.calibrated_gap:.2e}, "
            f"{self.calibrated_rows_over_bound} rows over {PARITY}, no risk-tier change",
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
    extras: dict[str, list[str]],
    *,
    seed: int,
    provenance: Mapping[str, Any] | None = None,
) -> tuple[Bundle, Report]:
    """Fit M4's model on the cache exactly as the gate run does, then package and verify it."""
    data = gate_run.load(list(vectors), extras)
    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    fitted_forest = anomaly.fit_anomaly(data.matrix, data.split.train, seed=seed)
    onnx_xgb, onnx_lgb = onnx_export.export(fitted, len(data.names))

    categorical = [n for n in data.names if REGISTRY[n].dtype is Dtype.CATEGORICAL]
    encodings = {
        n: full_fit_encoding([str(v[n]) for v in vectors], data.labels, data.split.train)
        for n in categorical
    }
    xgboost = fitted.xgboost[: fitted.xgboost_rounds]
    calibration = {
        "ensemble": isotonic.from_sklearn(fitted.calibrator),
        "xgboost": isotonic.from_sklearn(fitted.xgboost_calibrator),
        "lightgbm": isotonic.from_sklearn(fitted.lightgbm_calibrator),
    }
    content = hashlib.sha256()
    content.update(xgboost.save_raw("json"))
    content.update(fitted.lightgbm.model_to_string().encode())
    for cal in calibration.values():
        content.update(repr(cal).encode())
    bundle = Bundle(
        model_version=f"ensemble-{content.hexdigest()[:12]}",
        registry_version=feature_registry_version(),
        features=tuple(data.names),
        encodings=encodings,
        xgboost=xgboost,
        lightgbm=fitted.lightgbm,
        xgboost_onnx=bytes(onnx_xgb.SerializeToString()),
        lightgbm_onnx=bytes(onnx_lgb.SerializeToString()),
        calibration=calibration,
        forest=forest.export(fitted_forest),
    )

    # Parity over the whole test period: the served path against the fitted model.
    test_rows = [data.matrix[i] for i in data.test]
    test_labels = [data.labels[i] for i in data.test]
    expected = fitted.score(test_rows)
    served_xgb, served_lgb = bundle.onnx_raw(test_rows)
    native_xgb, native_lgb = bundle.native_raw(test_rows)
    served = [
        bundle.calibrate(float(x), float(y)).ensemble_score
        for x, y in zip(served_xgb, served_lgb, strict=True)
    ]
    calibrated_gap = max(abs(a - b) for a, b in zip(served, expected.ensemble, strict=True))
    raw_worst = float(
        max(np.max(np.abs(served_xgb - native_xgb)), np.max(np.abs(served_lgb - native_lgb)))
    )
    thresholds = DEFAULT
    tier_changes = sum(
        1
        for a, b in zip(served, expected.ensemble, strict=True)
        if thresholds.tier(a, 0.0) != thresholds.tier(b, 0.0)
    )
    # E.4's criterion is on the boosters' probabilities. The isotonic map is the same function on
    # both paths but has near-vertical segments (a slope of ~1e5 on the gate model), so a 4e-7
    # raw difference can move a calibrated score by ~2e-4; that is reported, and the build
    # requires that it moves no transaction across a risk tier.
    if raw_worst >= PARITY or tier_changes:
        raise ParityError(
            f"served raw probabilities differ from the native boosters by {raw_worst:.2e} "
            f"(bound {PARITY}) and {tier_changes} test transactions change risk tier; "
            f"calibrated gap {calibrated_gap:.2e}"
        )

    positives = sum(test_labels)
    ensemble_auc = auc(served, test_labels)
    floor_column = [float(vectors[i][smoke.FLOOR_FEATURE]) for i in data.test]
    report = Report(
        rows={
            "train": len(data.split.train),
            "validation": len(data.split.validation),
            "calibration": len(data.split.calibration),
            "test": len(data.test),
        },
        xgboost_rounds=fitted.xgboost_rounds,
        lightgbm_rounds=fitted.lightgbm_rounds,
        ensemble_auc=ensemble_auc,
        ensemble_auc_interval=auc_interval(ensemble_auc, positives, len(test_labels) - positives),
        floor=separation(floor_column, test_labels),
        ece_ensemble=battery.expected_calibration_error(battery.reliability(served, test_labels)),
        recall_at_1pct_fpr=smoke.recall_at_fpr(served, test_labels, 0.01),
        parity=raw_worst,
        calibrated_gap=calibrated_gap,
        calibrated_rows_over_bound=sum(
            1 for a, b in zip(served, expected.ensemble, strict=True) if abs(a - b) >= PARITY
        ),
    )
    bundle.provenance = {
        **(provenance or {}),
        "trained_by": "fraudshield_ml.training.model.fit_ensemble (M4)",
        "seed": seed,
        "xgboost_rounds": fitted.xgboost_rounds,
        "lightgbm_rounds": fitted.lightgbm_rounds,
        "scale_pos_weight": fitted.scale_pos_weight,
        "held_out": {
            "rows": report.rows,
            "ensemble_auc": report.ensemble_auc,
            "ensemble_auc_interval": report.ensemble_auc_interval,
            "single_feature_floor": report.floor,
            "floor_feature": smoke.FLOOR_FEATURE,
            "ece_equal_width_10": report.ece_ensemble,
            "recall_at_1pct_fpr": report.recall_at_1pct_fpr,
            "parity_onnx_vs_native": report.parity,
            "calibrated_gap": report.calibrated_gap,
            "calibrated_rows_over_parity_bound": report.calibrated_rows_over_bound,
        },
    }
    return bundle, report


def build_from_cache(cache: Path, out: Path, *, seed: int, root: Path) -> Report:
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
    )
    bundle.save(out)
    return report
