"""C-6's replacement measurement: XGBoost alone, LightGBM alone, the D-05 ensemble (D-09)."""

from __future__ import annotations

import math
import random

import pytest

from fraudshield_ml.training.ensemble import (
    LIGHTGBM_WEIGHT,
    SEEDS,
    XGBOOST_WEIGHT,
    SeedVarianceSummary,
    ensemble_score,
    fit_lightgbm,
    fit_xgboost,
    run_seeds,
    summarise,
)

pytestmark = pytest.mark.req("D-09")


def _fixture() -> tuple[list[list[float]], list[bool], list[int], list[int]]:
    """A noisy, informative, two-class matrix. Deliberately not perfectly separated.

    Perfectly separated scores give a Hanley-McNeil variance of exactly zero at any seed, which
    is the trap three other tests in this project fell into today: an interval, or here a
    standard deviation, compares 0.0 with 0.0 and passes whatever the code does. This fixture's
    signal is weak enough that different seeds' trees actually disagree with each other.
    """
    rng = random.Random(20260917)  # noqa: S311 - test fixture, not cryptography
    rows, labels = [], []
    for i in range(500):
        is_fraud = i % 15 == 0
        rows.append([rng.gauss(0.3 if is_fraud else 0.0, 1.0), rng.gauss(0.0, 1.0)])
        labels.append(is_fraud)
    train, test = list(range(350)), list(range(350, 500))
    assert sum(labels[i] for i in train) >= 5, "precondition: enough fraud to train on"
    assert sum(labels[i] for i in test) >= 3, "precondition: enough fraud to score"
    return rows, labels, train, test


def test_xgboost_and_lightgbm_both_fit_and_score_on_the_same_shape() -> None:
    """The mechanism, first: both boosters take the same matrix and return one score per row."""
    matrix, labels, train, test = _fixture()
    xgb_scores = fit_xgboost(matrix, labels, train, test, seed=1)
    lgb_scores = fit_lightgbm(matrix, labels, train, test, seed=1)
    assert len(xgb_scores) == len(test)
    assert len(lgb_scores) == len(test)
    assert all(0.0 <= s <= 1.0 for s in xgb_scores), "binary:logistic must return probabilities"
    assert all(0.0 <= s <= 1.0 for s in lgb_scores), "binary must return probabilities"


@pytest.mark.req("TEST-02")
def test_the_ensemble_is_the_d05_weighted_combination_and_nothing_else() -> None:
    """D-05: 0.55*p_xgb + 0.45*p_lgb on raw probabilities. Not fitted, not calibrated here."""
    assert pytest.approx(1.0) == XGBOOST_WEIGHT + LIGHTGBM_WEIGHT
    combined = ensemble_score([1.0, 0.0, 0.4], [0.0, 1.0, 0.6])
    assert combined == pytest.approx([0.55, 0.45, 0.49])


def test_a_structural_nan_column_passes_through_both_models() -> None:
    """D-04's principle applied to the second model: NaN is a value the trees split on."""
    matrix = [[float(i % 2), math.nan] for i in range(300)]
    labels = [i % 2 == 1 for i in range(300)]
    train, test = list(range(200)), list(range(200, 300))
    xgb_scores = fit_xgboost(matrix, labels, train, test, seed=1)
    lgb_scores = fit_lightgbm(matrix, labels, train, test, seed=1)
    assert all(not math.isnan(s) for s in xgb_scores)
    assert all(not math.isnan(s) for s in lgb_scores)


def test_run_seeds_holds_the_rows_fixed_and_only_the_seed_moves() -> None:
    """Sampling variance and seed variance are different questions; this isolates the second."""
    matrix, labels, train, test = _fixture()
    runs = run_seeds(matrix, labels, train, test, seeds=(1, 2, 3))
    assert [r.seed for r in runs] == [1, 2, 3]
    for r in runs:
        for value in (r.xgboost_auc, r.lightgbm_auc, r.ensemble_auc):
            assert 0.0 <= value <= 1.0

    # Not every seed need move a booster's AUC, but the whole point of running several is that
    # at least one pair of seeds must disagree, or "variance across seeds" was never exercised.
    xgb_values = {round(r.xgboost_auc, 6) for r in runs}
    lgb_values = {round(r.lightgbm_auc, 6) for r in runs}
    assert len(xgb_values) > 1 or len(lgb_values) > 1, (
        "precondition: at least one model's AUC must actually move across seeds, or this fixture "
        "cannot demonstrate seed variance at all"
    )


def test_summarise_reports_mean_and_stdev_and_needs_at_least_two_seeds() -> None:
    summary = summarise("xgboost", [0.90, 0.92, 0.88])
    assert summary.mean == pytest.approx(0.90, abs=1e-6)
    assert summary.stdev > 0
    with pytest.raises(ValueError, match="not enough"):
        summarise("xgboost", [0.90])


def test_summarise_refuses_an_undefined_auc_rather_than_crashing_on_it() -> None:
    """statistics.stdev on a NaN fails with 'float has no attribute numerator', which points
    nowhere near the actual cause: a seed whose held-out set had one class. Caught here instead.
    """
    with pytest.raises(ValueError, match="undefined AUC"):
        summarise("xgboost", [0.9, math.nan, 0.85])


def test_reduction_from_is_positive_when_the_ensemble_moves_less_across_seeds() -> None:
    """C-6's own framing: a percentage the standard deviation fell, sign meaningful either way."""
    steadier = SeedVarianceSummary("ensemble", mean=0.9, stdev=0.01, runs=(0.89, 0.90, 0.91))
    noisier = SeedVarianceSummary("xgboost", mean=0.9, stdev=0.04, runs=(0.86, 0.90, 0.94))
    assert steadier.reduction_from(noisier) == pytest.approx(75.0)
    assert noisier.reduction_from(steadier) == pytest.approx(-300.0), (
        "a configuration that moves MORE across seeds must report a negative reduction, not be "
        "clipped to zero — the whole point of C-6 is that the claim can come out either way"
    )


def test_reduction_from_a_zero_variance_baseline_is_nan_not_a_division_error() -> None:
    zero = SeedVarianceSummary("perfect", mean=1.0, stdev=0.0, runs=(1.0, 1.0, 1.0))
    other = SeedVarianceSummary("ensemble", mean=0.9, stdev=0.02, runs=(0.88, 0.90, 0.92))
    assert math.isnan(other.reduction_from(zero))


def test_the_five_seeds_are_fixed_not_drawn() -> None:
    """The SRS asks for five; a reader must reproduce which five without a second seed."""
    assert SEEDS == (1, 2, 3, 4, 5)
    assert len(set(SEEDS)) == 5
