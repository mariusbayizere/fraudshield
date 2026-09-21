"""A pipeline smoke test: dataset to features to a model to a number.

**This is a pipeline check, not a result, and no figure it prints may be quoted as one.** It exists
to give early signal that data-to-model works end to end before M4's machinery is built — one
model, one split, no tuning, no calibration, no confidence intervals, and a holdout chosen for
speed rather than for the evaluation design D-07 specifies.

**What it must always print, and why.** On this benchmark a single feature,
`velocity_ratio_1h_vs_30d`, reaches `max(AUC, 1-AUC)` of 0.894
(`docs/benchmarks/single_feature_baseline.md`). A model AUC reported without that floor beside it
invites a reader to compare against 0.5, which is wrong by an order of magnitude in the quantity
that matters. So this prints the margin over the baseline, and it prints it even when the margin is
negative — especially then.

**What it deliberately does not do:**

* it does not use D-07's temporal split with its embargo — it takes a time-ordered holdout of the
  scored sample, which is *a* temporal split and not *the* one, so its numbers are not comparable
  with anything M4 will report;
* it fits no calibration, so nothing here speaks to ML-GATE-11;
* it tunes nothing, so a low number is not evidence that the features are weak;
* it trains on the **36 computable** features and says so, never on "44".
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq

from fraudshield_ml.features.registry import REGISTRY, Computability, Dtype
from fraudshield_ml.features.vector import FeatureValue
from fraudshield_ml.metrics.single_feature import (
    auc,
    auc_standard_error,
    hash_fold,
    separation,
)

#: The single-feature floor this benchmark sets, from `docs/benchmarks/single_feature_baseline.md`
#: at commit 2c80ef6. Repeated here so the smoke test cannot print a model number without it, and
#: `test_the_floor_matches_the_published_baseline` pins the two together.
SINGLE_FEATURE_FLOOR = 0.894
FLOOR_FEATURE = "velocity_ratio_1h_vs_30d"

#: Folds for the out-of-fold encoding, grouped by account (E1). Fixed rather than a parameter:
#: every fold count this project uses is five, and a caller free to pass one would be free to pass
#: a count that puts an account's own rows back in its own estimate.
ENCODING_FOLDS = 5


def trainable_features() -> tuple[str, ...]:
    """The features a model may be given: those the benchmark can feed and that vary.

    Derived from the registry rather than listed, so a feature whose computability changes joins or
    leaves the training set the day its declaration does — and the count reported beside every
    metric follows automatically.
    """
    return tuple(
        sorted(
            name for name, spec in REGISTRY.items() if spec.computable is Computability.COMPUTABLE
        )
    )


@dataclass(frozen=True)
class SmokeRun:
    """Everything that changes what the smoke run measures, in one place.

    A settings object rather than six parameters, because the cache key is derived from three of
    them and a caller passing them separately could pass one set to the key and another to the
    computation.
    """

    dataset: Path
    packs: Path
    corpus_rows: int
    sample_rows: int
    seed: int
    cache: Path | None = None

    @property
    def key(self) -> dict[str, str]:
        return cache_key(str(self.dataset), self.corpus_rows, self.sample_rows)


@dataclass(frozen=True)
class SmokeResult:
    """What the smoke run measured, with everything needed to read it honestly."""

    features: int
    train_rows: int
    test_rows: int
    train_fraud: int
    test_fraud: int
    model_auc: float
    model_auc_error: float
    recall_at_1pct_fpr: float
    baseline_auc: float
    baseline_feature: str

    @property
    def margin(self) -> float:
        """The number that means something: how much the model adds over one threshold."""
        return self.model_auc - self.baseline_auc


def encode_categoricals(
    rows: Sequence[dict[str, FeatureValue]],
    labels: Sequence[bool],
    accounts: Sequence[str],
    train: Sequence[int],
    *,
    prior_weight: float = 50.0,
) -> dict[str, list[float]]:
    """Target-encode the two categoricals, fitted on the training rows only.

    Two different encodings, deliberately. **Training rows** get an out-of-fold estimate with folds
    grouped by whole accounts (E1), so a row's own incident cannot inform its score. **Test rows**
    get the estimate fitted on all training rows, which is what serving would do. Using the
    out-of-fold estimate for test rows would leak the test labels; using the full-fit estimate for
    training rows would leak each row's own.

    The out-of-fold estimate is computed by subtracting each fold's totals from the whole, rather
    than by rescanning the kept rows for every row — the same arithmetic in one pass instead of
    `len(train)` passes. `test_the_fast_encoding_matches_the_obvious_one` holds the two together,
    because the fast form is the one that is easy to get subtly wrong.
    """
    categorical = [n for n, s in REGISTRY.items() if s.dtype is Dtype.CATEGORICAL]
    training = set(train)
    folds = ENCODING_FOLDS
    fold_of = {i: hash_fold(accounts[i], folds) for i in train}
    # Positives and rows per fold, so each fold's base rate is the whole minus that fold.
    fold_positives = [0] * folds
    fold_rows = [0] * folds
    for i in train:
        fold_positives[fold_of[i]] += int(labels[i])
        fold_rows[fold_of[i]] += 1
    total_positives, total_rows = sum(fold_positives), sum(fold_rows)

    encoded: dict[str, list[float]] = {}
    for name in categorical:
        values = [str(row[name]) for row in rows]
        base = total_positives / max(total_rows, 1)
        totals: dict[str, list[float]] = {}
        # Per category: [fraud, seen] overall, then the same split by fold.
        by_fold: list[dict[str, list[float]]] = [{} for _ in range(folds)]
        for i in train:
            cell = totals.setdefault(values[i], [0.0, 0.0])
            cell[0] += float(labels[i])
            cell[1] += 1.0
            cell = by_fold[fold_of[i]].setdefault(values[i], [0.0, 0.0])
            cell[0] += float(labels[i])
            cell[1] += 1.0

        column = [0.0] * len(rows)
        for i in range(len(rows)):
            if i not in training:
                fraud, seen = totals.get(values[i], [0.0, 0.0])
                column[i] = (fraud + prior_weight * base) / (seen + prior_weight)
                continue
            # Out-of-fold for a training row: its own account's fold is held out.
            fold = fold_of[i]
            kept_rows = total_rows - fold_rows[fold]
            fold_base = (total_positives - fold_positives[fold]) / max(kept_rows, 1)
            whole = totals[values[i]]
            held = by_fold[fold].get(values[i], [0.0, 0.0])
            fraud, seen = whole[0] - held[0], whole[1] - held[1]
            column[i] = (fraud + prior_weight * fold_base) / (seen + prior_weight)
        encoded[name] = column
    return encoded


def recall_at_fpr(scores: Sequence[float], labels: Sequence[bool], fpr: float) -> float:
    """Recall at a false-positive rate, by choosing the threshold on the negatives.

    The operating point the decision engine cares about (ML-GATE-03 reports recall; ML-GATE-02
    precision at 1% FPR). Reported here as a second number so the smoke test says something about
    the low-FPR regime, where AUC is least informative.
    """
    negatives = sorted((s for s, y in zip(scores, labels, strict=True) if not y), reverse=True)
    positives = [s for s, y in zip(scores, labels, strict=True) if y]
    if not negatives or not positives:
        return math.nan
    index = min(len(negatives) - 1, max(0, int(len(negatives) * fpr) - 1))
    threshold = negatives[index]
    return sum(1 for s in positives if s >= threshold) / len(positives)


def summarise(result: SmokeResult) -> str:
    """The report. The floor is printed before the model's number, not after it."""
    margin = result.margin
    lines = [
        "PIPELINE SMOKE TEST — not a result. One model, one split, no tuning, no calibration,",
        "and a time-ordered holdout that is NOT D-07's evaluation split. Nothing here is",
        "comparable with anything M4 will report.",
        "",
        f"  features trained on          {result.features} (the computable ones, never 44)",
        f"  train rows / fraud           {result.train_rows} / {result.train_fraud}",
        f"  test rows / fraud            {result.test_rows} / {result.test_fraud}",
        "",
        f"  single-feature floor         {result.baseline_auc:.3f}  ({result.baseline_feature})",
        f"  model AUC                    {result.model_auc:.3f} "
        f"+/-{1.96 * result.model_auc_error:.3f}",
        f"  MARGIN OVER THE FLOOR        {margin:+.3f}",
        f"  recall at 1% FPR             {result.recall_at_1pct_fpr:.3f}",
        "",
    ]
    if margin <= 0:
        lines.append(
            "  The model does not beat one threshold on one feature. For a smoke test that is a"
        )
        lines.append(
            "  pipeline signal, not a verdict on the features — but it is the number to watch."
        )
    elif margin < 1.96 * result.model_auc_error:
        lines.append(
            "  The margin is inside the model's own interval, so this run does not establish that"
        )
        lines.append("  the model beats the floor at all.")
    return "\n".join(lines)


#: One model, no tuning. Fixed here rather than exposed, because a smoke test with knobs is a
#: tuning run wearing a smoke test's disclaimer.
BOOSTING_ROUNDS = 200
MODEL_PARAMETERS = {
    "objective": "binary:logistic",
    "eval_metric": "auc",
    "max_depth": 5,
    "eta": 0.1,
    "subsample": 0.9,
    "colsample_bytree": 0.9,
    "nthread": 1,
}


def fit_and_score(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    test: Sequence[int],
    *,
    seed: int = 0,
) -> list[float]:
    """Train one booster on the training rows and score the held-out ones.

    The booster API rather than `XGBClassifier`, which imports scikit-learn: this package does not
    depend on it and should not acquire it for one estimator. The first attempt at this run
    discovered that after eleven minutes of feature computation, which is why the model half is a
    function with a test of its own rather than a few lines inside the command.

    NaN travels through as a value the trees split on (D-04) rather than being imputed: the device
    and agent features are NaN together for whole channels, and that pattern is signal.
    """
    import xgboost as xgb  # noqa: PLC0415 - heavy, and only this path needs it

    training = xgb.DMatrix(
        [list(matrix[i]) for i in train],
        label=[float(labels[i]) for i in train],
        missing=float("nan"),
    )
    held_out = xgb.DMatrix([list(matrix[i]) for i in test], missing=float("nan"))
    booster = xgb.train({**MODEL_PARAMETERS, "seed": seed}, training, BOOSTING_ROUNDS)
    return [float(p) for p in booster.predict(held_out)]


def evaluate(scores: Sequence[float], labels: Sequence[bool]) -> tuple[float, float, float]:
    """Model AUC, its standard error, and recall at 1% FPR."""
    value = auc(scores, labels)
    positives = sum(1 for y in labels if y)
    error = auc_standard_error(value, positives, len(labels) - positives)
    return value, error, recall_at_fpr(scores, labels, 0.01)


def floor_from(scores: Sequence[float], labels: Sequence[bool]) -> float:
    """The baseline measured on the same rows, rather than quoted from the published run.

    Quoting 0.894 against a model measured on a different sample would compare two numbers from two
    populations. The published figure is the one to cite; this one is the one to subtract.
    """
    return separation(scores, labels)


#: Columns the cache adds beside the features. Prefixed so they cannot collide with a feature name.
CACHE_LABEL = "_is_fraud"
CACHE_ACCOUNT = "_account_id"
#: Per-row facts that are not features but that the M4 battery needs: which D-07 period the row
#: belongs to, and the country and channel a breakdown groups by. They ride in the cache because
#: recomputing them would mean re-reading the corpus, which is the thing the cache exists to avoid.
CACHE_SEGMENT = "_segment"
CACHE_COUNTRY = "_country"
CACHE_CHANNEL = "_channel"
CACHE_EXTRAS = (CACHE_LABEL, CACHE_ACCOUNT, CACHE_SEGMENT, CACHE_COUNTRY, CACHE_CHANNEL)


def cache_key(dataset: str, corpus_rows: int, sample_rows: int) -> dict[str, str]:
    """What a cached feature matrix must agree with before it may be reused.

    The feature pass is the expensive half of this command by an order of magnitude, and a run
    that fails after it — as the first one did, on a missing model dependency — should not pay for
    it twice. But a cache keyed on nothing is a way to report one run's numbers under another
    run's settings, so the key carries everything that changes the matrix: which dataset, how much
    history, how many scored rows, and which features at which revision of the registry.
    """
    return {
        "dataset": dataset,
        "corpus_rows": str(corpus_rows),
        "sample_rows": str(sample_rows),
        "features": ",".join(trainable_features()),
    }


def cache_write(
    path: Path,
    key: dict[str, str],
    rows: Sequence[dict[str, FeatureValue]],
    extras: Mapping[str, Sequence[object]],
) -> None:
    """The feature matrix plus the per-row facts a breakdown needs, under a key that names them."""
    missing = set(CACHE_EXTRAS) - set(extras)
    if missing:
        raise ValueError(f"the cache needs {sorted(missing)} alongside the features")
    names = trainable_features()
    columns: dict[str, list[object]] = {name: [row[name] for row in rows] for name in names}
    table = pa.table(
        {**columns, **{k: list(extras[k]) for k in CACHE_EXTRAS}},
        metadata={k.encode(): v.encode() for k, v in key.items()},
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(table, path)


def cache_read_any(
    path: Path,
) -> tuple[list[dict[str, FeatureValue]], dict[str, list[str]]] | None:
    """The cached matrix whatever it was computed for, for a reader that is not re-running it.

    `cache_read` refuses a key mismatch because a *run* must not report one configuration's
    numbers under another's. A reader of the cache has no configuration of its own to mismatch:
    it reports what the matrix says, and the matrix carries its own segment labels.
    """
    return _read(path, key=None)


def cache_read(
    path: Path, key: dict[str, str]
) -> tuple[list[dict[str, FeatureValue]], dict[str, list[str]]] | None:
    """The cached matrix and its per-row facts, or None if it was computed under other settings."""
    return _read(path, key=key)


def _read(
    path: Path, key: dict[str, str] | None
) -> tuple[list[dict[str, FeatureValue]], dict[str, list[str]]] | None:
    if not path.exists():
        return None
    table = pq.read_table(path)
    stored = {k.decode(): v.decode() for k, v in (table.schema.metadata or {}).items()}
    if key is not None and any(stored.get(field) != value for field, value in key.items()):
        return None
    if any(name not in table.schema.names for name in CACHE_EXTRAS):
        return None
    names = trainable_features()
    if any(name not in table.schema.names for name in names):
        return None
    columns = {name: table.column(name).to_pylist() for name in names}
    rows = [{name: columns[name][i] for name in names} for i in range(table.num_rows)]
    extras = {k: [str(v) for v in table.column(k).to_pylist()] for k in CACHE_EXTRAS}
    return rows, extras
