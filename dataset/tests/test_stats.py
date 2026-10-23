from __future__ import annotations

import math

import numpy as np
import pytest

from fraudshield_dataset.realism.stats import (
    CV_TREE_NULL_INFLATION,
    ShallowTree,
    auc,
    cross_validated_auc,
    null_auc_stderr,
    separation,
    wilson_interval,
)

pytestmark = pytest.mark.req("D-08")


def test_auc_matches_hand_computed_cases() -> None:
    labels = np.array([False, False, True, True])
    assert auc(np.array([0.1, 0.4, 0.35, 0.8]), labels) == pytest.approx(0.75)
    assert auc(np.array([1.0, 2.0, 3.0, 4.0]), labels) == 1.0
    assert auc(np.array([4.0, 3.0, 2.0, 1.0]), labels) == 0.0
    assert auc(np.array([1.0, 1.0, 1.0, 1.0]), labels) == 0.5  # ties count half
    assert auc(np.array([1.0, 2.0]), np.array([True, True])) == 0.5  # undefined
    assert separation(np.array([4.0, 3.0, 2.0, 1.0]), labels) == 1.0


def test_tree_finds_a_planted_split_and_cross_validation_reports_it() -> None:
    rng = np.random.default_rng(0)
    x = rng.normal(size=(4000, 3))
    y = (x[:, 1] > 0.8) & (rng.random(4000) < 0.9)
    tree = ShallowTree(max_depth=2).fit(x, y)
    assert auc(tree.predict(x), y) > 0.9
    assert cross_validated_auc(x, y, folds=3, seed=1) > 0.9


def test_tree_on_noise_scores_near_one_half() -> None:
    rng = np.random.default_rng(1)
    x = rng.normal(size=(6000, 4))
    y = rng.random(6000) < 0.05
    assert abs(cross_validated_auc(x, y, folds=4, seed=2) - 0.5) < 0.05


def test_tree_edge_cases() -> None:
    with pytest.raises(RuntimeError, match="not fitted"):
        ShallowTree().predict(np.zeros((1, 1)))
    pure = ShallowTree().fit(np.zeros((200, 1)), np.zeros(200, dtype=bool))
    assert pure.predict(np.zeros((2, 1))).tolist() == [0.0, 0.0]
    constant = ShallowTree(min_leaf=10).fit(np.zeros((200, 1)), np.arange(200) % 2 == 0)
    assert constant.predict(np.zeros((1, 1)))[0] == pytest.approx(0.5)


def test_grouped_folds_keep_each_group_on_one_side() -> None:
    rng = np.random.default_rng(4)
    # Clustered labels: each group's rows share a feature value and a label.
    groups = np.repeat(np.arange(400), 5)
    x = np.repeat(rng.random(400), 5).reshape(-1, 1)
    y = np.repeat(rng.random(400) < 0.2, 5)
    ungrouped = cross_validated_auc(x, y, folds=5, seed=1)
    grouped = cross_validated_auc(x, y, folds=5, seed=1, groups=groups)
    assert ungrouped > 0.6  # clusters leak across folds
    assert abs(grouped - 0.5) < 0.06


@pytest.mark.parametrize(
    ("successes", "total", "low", "high"),
    [
        # Published worked examples for the Wilson score interval.
        (0, 10, 0.0, 0.2775),
        (10, 10, 0.7225, 1.0),
        (5, 10, 0.2366, 0.7634),
        (100, 1000, 0.0829, 0.1202),
    ],
)
def test_wilson_interval_matches_published_values(
    successes: int, total: int, low: float, high: float
) -> None:
    measured = wilson_interval(successes, total)
    # Published values are quoted to four decimals, so the tolerance is half a unit in the last.
    assert measured[0] == pytest.approx(low, abs=5e-5)
    assert measured[1] == pytest.approx(high, abs=5e-5)


def test_wilson_interval_stays_inside_zero_and_one_and_handles_no_data() -> None:
    assert wilson_interval(0, 0) == (0.0, 0.0)
    for successes in range(0, 21):
        low, high = wilson_interval(successes, 20)
        assert 0.0 <= low <= successes / 20 <= high <= 1.0


def test_null_auc_stderr_matches_the_mann_whitney_null() -> None:
    # sqrt((n1 + n0 + 1) / (12 n1 n0)) for the rank-sum statistic under exchangeable labels.
    assert null_auc_stderr(50, 50) == pytest.approx(math.sqrt(101 / (12 * 2500)), rel=1e-12)
    assert null_auc_stderr(1, 1) == pytest.approx(math.sqrt(3 / 12), rel=1e-12)
    assert null_auc_stderr(0, 10) == 0.0
    assert null_auc_stderr(10, 0) == 0.0
    # Noise falls as the smaller class grows.
    assert null_auc_stderr(10, 1000) > null_auc_stderr(100, 1000)


@pytest.mark.req("D-08")
def test_the_tree_null_band_is_conservative_for_the_statistic_it_judges() -> None:
    """The analytic standard error is for a single score vector, not a fitted tree.

    The identifier and shortcut gates size their bands with it, multiplied by
    ``CV_TREE_NULL_INFLATION``. This measures the true null spread of the statistic those gates
    actually compute and fails if the constant is not conservative (M2 principal review, MAJOR 1.3).
    """
    rng = np.random.default_rng(20260918)
    values, positives, trials = 240, 95, 150
    deviations = []
    for trial in range(trials):
        features = rng.integers(48, 122, size=(values, 8)).astype(np.float64)
        labels = np.zeros(values, dtype=bool)
        labels[rng.choice(values, positives, replace=False)] = True
        deviations.append(cross_validated_auc(features, labels, folds=5, seed=trial) - 0.5)

    measured = float(np.std(deviations, ddof=1))
    analytic = null_auc_stderr(positives, values - positives)
    inflation = measured / analytic

    assert inflation < CV_TREE_NULL_INFLATION, (
        f"the cross-validated tree's null SD is {inflation:.2f} analytic standard errors; "
        f"CV_TREE_NULL_INFLATION is {CV_TREE_NULL_INFLATION} and must stay above it"
    )
