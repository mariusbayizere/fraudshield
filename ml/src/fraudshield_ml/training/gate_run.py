"""The M4 gate evaluation (E.5): one declared run of D-05's model against the gate thresholds.

Reads a cache holding D-07's four row sets — train, validation (outside its calibration tail),
calibration, test — fits the ensemble once on the first three, and scores the fourth. Everything
reported is on the test rows, and nothing here is tuned: the thresholds are D-02's, the model's
configuration is fixed, and a metric that misses its threshold is reported as missed (D.3: record
the measured value and the analysis, do not tune on test).

What is printed beside every gate figure is what PB-46's option 3 requires: the single-feature
floor, because on this benchmark one velocity feature nearly separates fraud alone.
"""

from __future__ import annotations

import math
import statistics
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from fraudshield_ml.features.registry import REGISTRY
from fraudshield_ml.features.vector import FeatureValue
from fraudshield_ml.metrics.single_feature import auc_standard_error
from fraudshield_ml.training import anomaly, baselines, explain, gate, model, smoke

#: E.5.4's EAC-specific ablations. Each removes one mechanism the SRS argues a Western card model
#: lacks. "USSD-aware device handling" is D-04's NaN-as-signal treatment of the four
#: device-fingerprint features; removing it means imputing them, as a model without it would.
DEVICE_FEATURES = (
    "device_age_days",
    "device_changes_24h",
    "device_is_new_for_account",
    "accounts_per_device_7d",
)
#: The groups a card-fraud model would carry. ASSUMED as a definition, pending the owner's C-4
#: decision (PB-60): counterparty, agent, corridor and synthetic-identity features are specific
#: to person-to-person mobile money and are left out.
CARD_STYLE_GROUPS = frozenset(
    {
        "AMOUNT_BEHAVIOUR",
        "VELOCITY",
        "TEMPORAL",
        "GEOGRAPHIC",
        "DEVICE_AND_CHANNEL",
        "ACCOUNT_PROFILE",
    }
)


@dataclass(frozen=True)
class Loaded:
    """A cache decoded into what the gate needs, with D-07's four row sets."""

    names: tuple[str, ...]
    matrix: list[list[float]]
    labels: list[bool]
    channels: list[str]
    mcc: list[str] | None
    split: model.RowSplit
    test: tuple[int, ...]


@dataclass(frozen=True)
class Scored:
    """A model's scores on the test rows, compared with the ensemble's."""

    name: str
    auc: float
    interval: float
    recall_at_1pct_fpr: float
    delong_p: float


@dataclass(frozen=True)
class Ablation:
    name: str
    features: int
    auc: float
    delta: float
    delong_p: float
    recall_at_1pct_fpr: float
    ece: float


@dataclass(frozen=True)
class GateResult:
    spec: gate.Spec
    value: float
    interval: tuple[float, float]

    @property
    def passed(self) -> bool:
        return self.spec.passes(self.value)


@dataclass
class Report:
    counts: dict[str, tuple[int, int]]
    xgboost_rounds: int
    lightgbm_rounds: int
    scale_pos_weight: float
    seed: int
    resamples: int
    gates: list[GateResult]
    companions: dict[str, tuple[float, tuple[float, float]]]
    floor: tuple[str, float]
    baselines: list[Scored]
    ablations: list[Ablation] = field(default_factory=list)
    seeds: dict[str, list[float]] = field(default_factory=dict)
    reliability: list[tuple[float, float, int]] = field(default_factory=list)
    roc: list[tuple[float, float]] = field(default_factory=list)
    mcc_available: bool = True


def load(vectors: Sequence[dict[str, FeatureValue]], extras: dict[str, list[str]]) -> Loaded:
    """Decode a cache. Refuses one without all four of D-07's row sets."""
    names = smoke.trainable_features()
    labels = [v == "True" for v in extras[smoke.CACHE_LABEL]]
    segment = extras[smoke.CACHE_SEGMENT]
    by = {
        s: tuple(i for i, v in enumerate(segment) if v == s)
        for s in ("train", "validation", "calibration", "test")
    }
    missing = [s for s, rows in by.items() if not rows]
    if missing:
        raise ValueError(
            f"the cache holds no {', '.join(missing)} rows. The gate needs all four of D-07's "
            "sets; write one with fs-features evaluate --calibration-rows N --validation-rows N"
        )
    encoded = smoke.encode_categoricals(
        vectors,
        labels,
        extras[smoke.CACHE_ACCOUNT],
        by["train"],
    )
    matrix = [
        [encoded[n][i] if n in encoded else float(vectors[i][n]) for n in names]
        for i in range(len(vectors))
    ]
    return Loaded(
        names=names,
        matrix=matrix,
        labels=labels,
        channels=extras[smoke.CACHE_CHANNEL],
        mcc=extras.get(smoke.CACHE_MCC),
        split=model.RowSplit(by["train"], by["validation"], by["calibration"]),
        test=by["test"],
    )


def _arrays(scores: Sequence[float], labels: Sequence[bool]) -> tuple[Any, Any]:
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    return np.asarray(scores, dtype=float), np.asarray(labels, dtype=bool)


def _scored(
    name: str, scores: Sequence[float], ensemble: Sequence[float], labels: Sequence[bool]
) -> Scored:
    s, y = _arrays(scores, labels)
    e, _ = _arrays(ensemble, labels)
    value, _, p = gate.delong(y, s, e)
    positives = int(y.sum())
    return Scored(
        name=name,
        auc=value,
        interval=1.96 * auc_standard_error(value, positives, len(y) - positives),
        recall_at_1pct_fpr=gate.recall_at_fpr(s, y),
        delong_p=p,
    )


def _covered(ens: model.Ensemble, data: Loaded, scores: Sequence[float]) -> list[bool]:
    """Explain every flagged test row; a LOW row needs no explanation and is not counted."""
    flagged = [k for k, s in enumerate(scores) if s >= explain.EXPLAINED_FROM]
    covered = [False] * len(scores)
    if flagged:
        rows = [data.matrix[data.test[k]] for k in flagged]
        for k, e in zip(flagged, explain.explain(ens, rows, data.names), strict=True):
            covered[k] = e.complete
    return covered


def _floor(data: Loaded, labels: Sequence[bool]) -> tuple[str, float]:
    """The strongest single feature defined on every test row, measured on the same rows."""
    best = ("", math.nan)
    for j, name in enumerate(data.names):
        column = [data.matrix[i][j] for i in data.test]
        if any(math.isnan(v) for v in column):
            continue
        value = smoke.floor_from(column, labels)
        if math.isnan(best[1]) or value > best[1]:
            best = (name, value)
    return best


def _ablations(
    data: Loaded, seed: int, full: Sequence[float], labels: Sequence[bool]
) -> list[Ablation]:
    group = {n: REGISTRY[n].group.name for n in data.names}
    removals: list[tuple[str, Callable[[str], bool]]] = [
        ("without agent features", lambda n: group[n] == "AGENT"),
        ("without the corridor feature", lambda n: group[n] == "CORRIDOR"),
        ("without round-sum awareness", lambda n: n == "round_sum_flag"),
        ("without month-end awareness", lambda n: n == "is_month_end_window"),
        ("card-style features only", lambda n: group[n] not in CARD_STYLE_GROUPS),
    ]
    held = _arrays(full, labels)
    results = []
    for name, drop in removals:
        kept = [j for j, n in enumerate(data.names) if not drop(n)]
        matrix = [[row[j] for j in kept] for row in data.matrix]
        results.append(_ablate(name, matrix, data, seed, held))
    # USSD-unaware: the device features imputed with training medians, so their missingness on
    # USSD rows stops being a value the trees can split on.
    columns = [j for j, n in enumerate(data.names) if n in DEVICE_FEATURES]
    medians = anomaly.training_medians(data.matrix, data.split.train)
    matrix = [
        [medians[j] if j in columns and math.isnan(v) else v for j, v in enumerate(row)]
        for row in data.matrix
    ]
    results.append(_ablate("without USSD-aware device handling", matrix, data, seed, held))
    return results


def _ablate(
    name: str,
    matrix: list[list[float]],
    data: Loaded,
    seed: int,
    held: tuple[Any, Any],
) -> Ablation:
    full, y = held
    fitted = model.fit_ensemble(matrix, data.labels, data.split, seed=seed)
    scores, _ = _arrays(fitted.score([matrix[i] for i in data.test]).ensemble, list(y))
    value, full_auc, p = gate.delong(y, scores, full)
    return Ablation(
        name=name,
        features=len(matrix[0]),
        auc=value,
        delta=value - full_auc,
        delong_p=p,
        recall_at_1pct_fpr=gate.recall_at_fpr(scores, y),
        ece=gate.ece_equal_mass(scores, y),
    )


def _reliability(rows: gate.Rows) -> list[tuple[float, float, int]]:
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    order = np.argsort(rows.scores, kind="stable")
    return [
        (float(rows.scores[c].mean()), float(rows.labels[c].mean()), len(c))
        for c in np.array_split(order, gate.ECE_BINS)
        if len(c)
    ]


def _roc(rows: gate.Rows, points: int = 200) -> list[tuple[float, float]]:
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    order = np.argsort(-rows.scores, kind="stable")
    labels = rows.labels[order]
    tpr = np.concatenate([[0.0], np.cumsum(labels) / max(int(labels.sum()), 1)])
    fpr = np.concatenate([[0.0], np.cumsum(~labels) / max(int((~labels).sum()), 1)])
    keep = np.unique(np.linspace(0, len(tpr) - 1, points).astype(int))
    return [(float(fpr[k]), float(tpr[k])) for k in keep]


def run(data: Loaded, *, seed: int, seeds: Sequence[int], resamples: int, ablate: bool) -> Report:
    labels = [data.labels[i] for i in data.test]
    channels = [data.channels[i] for i in data.test]
    test_rows = [data.matrix[i] for i in data.test]

    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    scored = fitted.score(test_rows)
    rows = gate.rows_of(scored.ensemble, labels, channels, _covered(fitted, data, scored.ensemble))
    point = gate.metrics(rows)
    intervals = gate.bootstrap(rows, resamples=resamples, seed=seed)

    amount = [row[data.names.index("amount_log1p")] for row in data.matrix]
    rule = baselines.rule_engine(amount, data.mcc, data.labels, data.split.train)
    forest = anomaly.fit_anomaly(data.matrix, data.split.train, seed=seed)
    train = list(data.split.train)
    candidates = [
        ("status-quo rule engine", [rule[i] for i in data.test]),
        (
            "logistic regression",
            baselines.logistic_regression(
                data.matrix, data.labels, train, list(data.test), seed=seed
            ),
        ),
        (
            "random forest",
            baselines.random_forest(data.matrix, data.labels, train, list(data.test), seed=seed),
        ),
        ("XGBoost alone", scored.xgboost),
        ("LightGBM alone", scored.lightgbm),
        ("Isolation Forest alone", forest.score(test_rows)),
    ]
    counts = {
        name: (len(index), sum(1 for i in index if data.labels[i]))
        for name, index in (
            ("train", data.split.train),
            ("validation", data.split.validation),
            ("calibration", data.split.calibration),
            ("test", data.test),
        )
    }
    report = Report(
        counts=counts,
        xgboost_rounds=fitted.xgboost_rounds,
        lightgbm_rounds=fitted.lightgbm_rounds,
        scale_pos_weight=fitted.scale_pos_weight,
        seed=seed,
        resamples=resamples,
        gates=[GateResult(s, point[s.id], intervals[s.id]) for s in gate.SPECS],
        companions={k: (v, intervals[k]) for k, v in point.items() if not k.startswith("ML-GATE-")},
        floor=_floor(data, labels),
        baselines=[_scored(n, s, scored.ensemble, labels) for n, s in candidates],
        reliability=_reliability(rows),
        roc=_roc(rows),
        mcc_available=data.mcc is not None,
    )
    if ablate:
        report.ablations = _ablations(data, seed, scored.ensemble, labels)
    for other in seeds:
        refit = (
            fitted
            if other == seed
            else model.fit_ensemble(data.matrix, data.labels, data.split, seed=other)
        )
        again = refit.score(test_rows).ensemble
        values = gate.metrics(gate.rows_of(again, labels, channels, _covered(refit, data, again)))
        for key in (s.id for s in gate.SPECS):
            report.seeds.setdefault(key, []).append(values[key])
    return report


def seed_summary(values: Sequence[float]) -> tuple[float, float]:
    defined = [v for v in values if not math.isnan(v)]
    if len(defined) < 2:
        return (defined[0] if defined else math.nan), math.nan
    return statistics.fmean(defined), statistics.stdev(defined)
