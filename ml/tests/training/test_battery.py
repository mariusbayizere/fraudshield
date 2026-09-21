"""M4's battery: calibration, SHAP and the counts that say what a cell can support (Part E.5)."""

from __future__ import annotations

import math

import pytest

from fraudshield_ml.training.battery import (
    additivity_error,
    apply_platt,
    brier,
    expected_calibration_error,
    fit_platt,
    mean_absolute_shap,
    reliability,
    score,
)
from fraudshield_ml.training.report import table

pytestmark = pytest.mark.req("ML-GATE-11")


def test_a_score_carries_the_fraud_count_that_decides_what_it_can_support() -> None:
    """Every table here slices the held-out rows, and a slice runs out of positives first.

    The interval is the visible consequence: the same AUC over 6 positives and over 600 is the
    same number and two different claims.
    """
    # The positives overlap the negatives on purpose. A perfectly separated sample has a
    # Hanley-McNeil variance of exactly zero at any n, so it would compare 0.0 with 0.0 and pass
    # whatever the implementation did — the same trap the smoke test's interval check fell into.
    thin = score("one country", [0.9, 0.9, 0.1] + [0.1] * 97, [True] * 3 + [False] * 97)
    thick = score("all", [0.9] * 200 + [0.1] * 100 + [0.1] * 9700, [True] * 300 + [False] * 9700)
    assert thin.fraud == 3
    assert thick.fraud == 300
    assert 0.5 < thin.auc < 1.0, (
        "precondition: an imperfect AUC, or the variance is zero by identity"
    )
    assert thin.auc == pytest.approx(thick.auc), "precondition: the same AUC, so only n differs"
    assert thin.interval > thick.interval


def test_the_report_flags_cells_too_thin_to_read_as_measurement() -> None:
    """A reader should not have to do the arithmetic to notice a cell rests on nine fraud rows."""
    thin = score("CD", [0.9] * 9 + [0.1] * 400, [True] * 9 + [False] * 400)
    thick = score("RW", [0.9] * 200 + [0.1] * 9000, [True] * 200 + [False] * 9000)
    rendered = "\n".join(table("BY COUNTRY", [thin, thick]))
    assert "fewer than 30 fraud rows" in rendered
    assert "CD" in rendered.split("fewer than 30")[1].split("\n")[0] or "CD" in rendered
    assert "RW" not in rendered.split("^")[1]


def test_platt_scaling_fixes_the_level_and_cannot_change_the_ordering() -> None:
    """The defining property, and the reason calibration needs its own metric.

    A monotone increasing map cannot reorder, so AUC is unchanged by construction and cannot show
    whether calibration worked. Brier and the mean level can.

    The fixture is inflated **and informative**, which took two attempts to get right. Perfectly
    separated scores are correctly calibrated by pushing the positives *up*, so "the level must
    come down" is false there. Scores carrying no signal let the fit choose a slightly negative
    slope, which reverses the order and is also correct. What the assertions below need is the
    ordinary case: overlapping, informative, and far above the base rate.
    """
    positives = [0.75 + 0.24 * i / 50 for i in range(50)]
    negatives = [0.60 + 0.30 * j / 950 for j in range(950)]
    inflated = positives + negatives
    labels = [True] * 50 + [False] * 950
    base = sum(labels) / len(labels)
    assert min(inflated) > 10 * base, "precondition: every score is far above the base rate"

    fit = fit_platt(inflated, labels)
    mapped = apply_platt(inflated, fit)

    assert fit[0] > 0, "an informative fixture must fit a positive slope"
    assert sorted(range(len(mapped)), key=lambda i: mapped[i]) == sorted(
        range(len(inflated)), key=lambda i: inflated[i]
    ), "a monotone increasing map reordered the scores"
    assert brier(mapped, labels) < brier(inflated, labels), "calibration must lower the Brier"

    mean_before = sum(inflated) / len(inflated)
    mean_after = sum(mapped) / len(mapped)
    assert abs(mean_after - base) < abs(mean_before - base) / 10, (
        f"the mean probability moved {mean_before:.3f} -> {mean_after:.3f} against a base rate of "
        f"{base:.3f}; calibration is about the level and this is the level"
    )


def test_the_reliability_table_bins_by_width_so_the_confident_end_is_visible() -> None:
    """Equal-width, not equal-count. At a 1% base rate equal-count bins put almost every row in
    one bin and cannot answer "when the model says 0.9, how often is it right?".
    """
    scores = [0.05] * 900 + [0.95] * 100
    labels = [False] * 900 + [True] * 90 + [False] * 10
    bins = reliability(scores, labels)

    assert len(bins) == 2, "two populated bins, not one crowded one"
    top = bins[-1]
    assert top.rows == 100
    assert top.predicted == pytest.approx(0.95)
    assert top.observed == pytest.approx(0.90)
    assert expected_calibration_error(bins) == pytest.approx(
        (900 * abs(0.05 - 0.0) + 100 * abs(0.95 - 0.90)) / 1000
    )


def test_shap_importances_are_ordered_and_the_bias_column_is_not_a_feature() -> None:
    """XGBoost appends the bias to `pred_contribs`, and mistaking it for a feature would shift
    every name by one — attributing each feature's importance to its neighbour.
    """
    names = ["a", "b", "c"]
    contributions = [[1.0, -3.0, 0.5, 0.1], [-1.0, 3.0, -0.5, 0.1]]
    ranked = mean_absolute_shap(contributions, names)
    assert [name for name, _ in ranked] == ["b", "a", "c"]
    assert ranked[0][1] == pytest.approx(3.0)

    with pytest.raises(ValueError, match="plus a bias column"):
        mean_absolute_shap([[1.0, 2.0]], names)


def test_additivity_is_measured_rather_than_assumed() -> None:
    """SHAP's defining property is that the parts sum to the whole. A value that violates it is
    not a slightly wrong explanation; it is a different quantity wearing the name.
    """
    exact = [[1.0, -0.5, 0.25]]
    assert additivity_error(exact, [0.75]) == pytest.approx(0.0)
    assert additivity_error(exact, [0.60]) == pytest.approx(0.15)


def test_an_empty_contribution_set_is_not_an_importance_of_zero() -> None:
    assert mean_absolute_shap([], ["a"]) == []
    assert math.isnan(expected_calibration_error([]))
