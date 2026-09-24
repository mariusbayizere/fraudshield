"""C-6: does the ensemble reduce seed variance, and by how much (D-09, D-05)?

The SRS claims "ensemble reduces variance 12% vs single model" with no citation. D-09's
resolution is to replace it with a measurement FraudShield produces: fit XGBoost alone, LightGBM
alone, and the 0.55·p_xgb + 0.45·p_lgb combination D-05 specifies, over several seeds, on the same
train/test split, and report how the AUC's standard deviation across seeds compares.

**What this measures, and what it does not.** It measures *seed variance* — how much a model's
figure moves when nothing about the data or the configuration changes except the random state the
optimiser starts from. It does not fit D-05's isotonic calibration or Optuna's hyperparameter
search: those change what the *level* of a metric is, not how much it moves across seeds, and
adding them would spend the run's cost on a question this experiment does not ask. If a reader
wants the calibrated ensemble's ECE, that is `docs/benchmarks/m4_battery.md`'s job, on one seed.

**Five seeds, fixed and declared, not drawn.** The SRS asks for five; a random draw of five would
make the figure depend on which five, and a reader could not reproduce it without also knowing the
draw's own seed.
"""

from __future__ import annotations

import math
import statistics
from collections.abc import Sequence
from dataclasses import dataclass

from fraudshield_ml.metrics.single_feature import auc, auc_standard_error

#: Five seeds, fixed rather than drawn, so a reader who runs this again gets the same five.
SEEDS: tuple[int, ...] = (1, 2, 3, 4, 5)

#: D-05's combination weights: 0.55 for XGBoost's raw probability, 0.45 for LightGBM's. Fixed by
#: the SRS, not fitted — this experiment is about seed variance, not about finding better weights.
XGBOOST_WEIGHT = 0.55
LIGHTGBM_WEIGHT = 0.45

XGBOOST_PARAMETERS = {
    "objective": "binary:logistic",
    "eval_metric": "auc",
    "max_depth": 5,
    "eta": 0.1,
    "subsample": 0.9,
    "colsample_bytree": 0.9,
    "nthread": 1,
}
#: num_leaves 31 is LightGBM's own default and is the leaf-count a depth-5 balanced tree would
#: reach (2**5 - 1), so the two models are given comparable capacity rather than compared at
#: mismatched complexity by accident.
LIGHTGBM_PARAMETERS = {
    "objective": "binary",
    "metric": "auc",
    "num_leaves": 31,
    "learning_rate": 0.1,
    "feature_fraction": 0.9,
    "bagging_fraction": 0.9,
    "bagging_freq": 1,
    "num_threads": 1,
    "verbosity": -1,
}
BOOSTING_ROUNDS = 200


def fit_xgboost(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    test: Sequence[int],
    seed: int,
) -> list[float]:
    import xgboost as xgb  # noqa: PLC0415 - heavy, and only this path needs it

    training = xgb.DMatrix(
        [list(matrix[i]) for i in train],
        label=[float(labels[i]) for i in train],
        missing=float("nan"),
    )
    held_out = xgb.DMatrix([list(matrix[i]) for i in test], missing=float("nan"))
    booster = xgb.train({**XGBOOST_PARAMETERS, "seed": seed}, training, BOOSTING_ROUNDS)
    return [float(p) for p in booster.predict(held_out)]


def fit_lightgbm(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    test: Sequence[int],
    seed: int,
) -> list[float]:
    """LightGBM's native booster API, not the scikit-learn wrapper, for the reason `smoke.py`
    gives for XGBoost: this package does not depend on scikit-learn and should not acquire it for
    one estimator. NaN passes through as LightGBM's own native missing-value handling (D-04's
    principle applied to the second model): a structurally missing value is a value the trees
    split on, not something to impute.
    """
    import lightgbm as lgb  # noqa: PLC0415 - heavy, and only this path needs it
    import numpy as np  # noqa: PLC0415 - only this path needs it

    # LightGBM's Dataset construction wants an array, not a list of lists (it raised "Data list
    # can only be of ndarray or Sequence" on the same nested-list shape XGBoost's DMatrix accepts
    # directly) — converted here rather than changing the matrix's shape everywhere it is built.
    training = lgb.Dataset(
        np.array([matrix[i] for i in train], dtype=float),
        label=np.array([float(labels[i]) for i in train]),
    )
    booster = lgb.train(
        {**LIGHTGBM_PARAMETERS, "seed": seed}, training, num_boost_round=BOOSTING_ROUNDS
    )
    predicted = booster.predict(np.array([matrix[i] for i in test], dtype=float))
    return [float(p) for p in predicted]


def ensemble_score(xgb_scores: Sequence[float], lgb_scores: Sequence[float]) -> list[float]:
    """D-05's combination: 0.55·p_xgb + 0.45·p_lgb, on raw probabilities, before calibration."""
    return [
        XGBOOST_WEIGHT * x + LIGHTGBM_WEIGHT * y
        for x, y in zip(xgb_scores, lgb_scores, strict=True)
    ]


@dataclass(frozen=True)
class SeedRun:
    """One seed's AUC for each of the three configurations, on the same held-out rows."""

    seed: int
    xgboost_auc: float
    lightgbm_auc: float
    ensemble_auc: float


@dataclass(frozen=True)
class SeedVarianceSummary:
    """Mean and standard deviation across seeds, for one configuration."""

    name: str
    mean: float
    stdev: float
    runs: tuple[float, ...]

    def reduction_from(self, baseline: SeedVarianceSummary) -> float:
        """Percentage the standard deviation fell relative to `baseline` — C-6's own framing.

        Negative means this configuration's AUC moved *more* across seeds than the baseline did,
        which is the opposite of what C-6 claims and is reported exactly as measured either way.
        """
        if baseline.stdev == 0:
            return math.nan
        return (baseline.stdev - self.stdev) / baseline.stdev * 100.0


def summarise(name: str, values: Sequence[float]) -> SeedVarianceSummary:
    if len(values) < 2:
        raise ValueError(
            f"{name}: {len(values)} seed(s) is not enough to measure variance across seeds"
        )
    broken = [v for v in values if math.isnan(v)]
    if broken:
        raise ValueError(
            f"{name}: {len(broken)} of {len(values)} seeds produced an undefined AUC (no fraud "
            "or no legitimate rows in the held-out set for that seed's fit) — this is a fact "
            "about the sample, not something to average past. statistics.stdev fails on NaN with "
            "an unrelated-looking error ('float has no attribute numerator'), which is why this "
            "is checked here rather than left to surface there"
        )
    return SeedVarianceSummary(
        name=name, mean=statistics.mean(values), stdev=statistics.stdev(values), runs=tuple(values)
    )


def run_seeds(
    matrix: Sequence[Sequence[float]],
    labels: Sequence[bool],
    train: Sequence[int],
    test: Sequence[int],
    seeds: Sequence[int] = SEEDS,
) -> list[SeedRun]:
    """Fit all three configurations at every seed, on the same train/test rows throughout.

    The train/test rows are held fixed across seeds on purpose: this experiment isolates the
    variance a *model's own random state* contributes, and letting the sample vary too would mix
    that in with sampling variance and answer a different question.
    """
    held = [labels[i] for i in test]
    runs = []
    for seed in seeds:
        xgb_scores = fit_xgboost(matrix, labels, train, test, seed)
        lgb_scores = fit_lightgbm(matrix, labels, train, test, seed)
        combined = ensemble_score(xgb_scores, lgb_scores)
        runs.append(
            SeedRun(
                seed=seed,
                xgboost_auc=auc(xgb_scores, held),
                lightgbm_auc=auc(lgb_scores, held),
                ensemble_auc=auc(combined, held),
            )
        )
    return runs


def auc_interval(value: float, positives: int, negatives: int) -> float:
    return 1.96 * auc_standard_error(value, positives, negatives)
