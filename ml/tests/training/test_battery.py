"""M4's battery: calibration, SHAP and the counts that say what a cell can support (Part E.5)."""

from __future__ import annotations

import math

import pytest

from fraudshield_ml.training.battery import (
    NOVEL_VARIANT,
    VariantResult,
    additivity_error,
    apply_platt,
    brier,
    by_variant,
    decision_region,
    expected_calibration_error,
    fit_platt,
    mean_absolute_shap,
    reliability,
    score,
)
from fraudshield_ml.training.report import table
from fraudshield_ml.training.smoke import recall_at_fpr

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


@pytest.mark.req("ML-GATE-03")
def test_recall_at_a_false_positive_rate_does_not_charge_nothing_for_ties() -> None:
    """The defect an M4 ablation printed: recall 0.904 at "1% FPR" with AUC 0.551 (PB-58).

    Arithmetically impossible, and the cause is ties. The agent features are NaN for every
    non-agent row, so a model given only them scores almost the whole population identically.
    Taking the 99th percentile of negatives and counting rows *at or above* it then admits every
    tied negative for free, and the realised false-positive rate is near 1.0 rather than 0.01.

    A degenerate model must report a recall near zero, because that is the operating point a rule
    engine could actually run.
    """
    tied = [0.5] * 1000
    labels = [i < 50 for i in range(1000)]
    assert recall_at_fpr(tied, labels, 0.01) == 0.0, (
        "a model that scores every row identically catches nothing within any FPR budget"
    )

    # A model that genuinely separates still reports what it earns: ten negatives above the
    # threshold out of a thousand is the 1% budget, and the positives above it are the recall.
    scores = [0.9] * 40 + [0.5] * 10 + [0.1] * 950
    labels = [True] * 40 + [False] * 10 + [False] * 950
    assert recall_at_fpr(scores, labels, 0.02) == pytest.approx(1.0)


def test_the_overall_ece_hides_what_the_decision_region_shows() -> None:
    """Why both are reported, with a model that is right where nobody looks and wrong where
    everybody does.

    At a 0.9% base rate a good model puts almost every row in the lowest bin, so the row-weighted
    ECE is an average over predictions nobody acts on. Here the low-score mass is perfectly
    calibrated and the alert region claims 0.9 while observing 0.5 — a model that would waste half
    its analyst time. The overall ECE must stay small and the decision-region ECE must be large,
    or one of the two numbers is not doing its job.
    """
    scores = [0.001] * 9900 + [0.9] * 100
    labels = [False] * 9900 + [True] * 50 + [False] * 50

    overall = expected_calibration_error(reliability(scores, labels))
    region = decision_region(scores, labels)

    assert overall < 0.01, "precondition: the bulk is well calibrated, so the average looks fine"
    assert region.rows == 100
    assert region.fraud == 50
    assert region.ece > 0.35, "the decision region is badly calibrated and must say so"
    assert region.ece > overall * 30


def test_the_decision_region_carries_its_own_counts() -> None:
    """A region metric over forty rows and over four hundred are different claims, and the
    restriction is what makes that easy to forget.
    """
    scores = [0.1] * 1000 + [0.8] * 40
    labels = [False] * 1000 + [True] * 30 + [False] * 10
    region = decision_region(scores, labels)
    assert (region.rows, region.fraud) == (40, 30)
    assert region.threshold == pytest.approx(0.60)


def test_an_empty_decision_region_is_not_a_calibration_of_zero() -> None:
    """A model that never scores above the threshold has no decision region, which is a fact
    about it and not an error — and reporting 0.0 would read as perfectly calibrated.
    """
    region = decision_region([0.01] * 100, [False] * 100)
    assert region.rows == 0
    assert math.isnan(region.ece)
    assert math.isnan(region.brier)


def test_every_variant_is_scored_against_the_same_negatives_and_one_threshold() -> None:
    """The novel-variant hold-out only means something if both halves are held fixed (PB-59).

    Give each variant its own threshold and a shape looks detectable because its own negatives
    were easy; give each its own negatives and two different problems are being compared. Here
    the novel shape scores lower than the base shape against identical legitimate rows, and the
    recall gap is the cost of never having seen it.
    """
    # Five legitimate rows sit above the novel shape and none above the base shape, so the two
    # differ in AUC while both stay above the 1% threshold. Recall and AUC pull in opposite
    # directions here on purpose: at a 1% budget a shape must beat the 99th percentile of
    # legitimate rows to be caught at all, so "detected" and "perfectly ranked" are close
    # together and the fixture has to thread between them.
    scores = [0.95] * 40 + [0.80] * 10 + [0.85] * 5 + [0.30] * 945
    labels = [True] * 50 + [False] * 950
    variants = ["base"] * 40 + [NOVEL_VARIANT] * 10 + [""] * 950

    results, threshold = by_variant(scores, labels, variants, fpr=0.01)
    assert threshold < 0.80, "precondition: both shapes must be above the alert threshold"

    by_name = {r.variant: r for r in results}
    assert set(by_name) == {"base", NOVEL_VARIANT}
    assert by_name["base"].fraud == 40
    assert by_name[NOVEL_VARIANT].fraud == 10
    assert by_name["base"].recall == pytest.approx(1.0)
    assert by_name[NOVEL_VARIANT].recall == pytest.approx(1.0)
    assert by_name["base"].auc == pytest.approx(1.0)
    assert by_name[NOVEL_VARIANT].auc == pytest.approx(1 - 5 / 950)
    assert by_name["base"].auc > by_name[NOVEL_VARIANT].auc, (
        "the base shape outranks the novel one against the same negatives"
    )
    assert results[0].variant == NOVEL_VARIANT, "the novel variant is reported first"


def test_an_undetected_novel_variant_reports_zero_recall_not_a_missing_row() -> None:
    """The failure this test exists to catch must be visible, not absent.

    A model that never scores the unseen shape above the alert threshold has a recall of zero on
    it. Omitting the row — because there is nothing to report — would read as "not measured".
    """
    scores = [0.95] * 40 + [0.05] * 10 + [0.30] * 950
    labels = [True] * 50 + [False] * 950
    variants = ["base"] * 40 + [NOVEL_VARIANT] * 10 + [""] * 950

    results, _ = by_variant(scores, labels, variants, fpr=0.01)
    novel = next(r for r in results if r.variant == NOVEL_VARIANT)
    assert novel.fraud == 10
    assert novel.detected == 0
    assert novel.recall == 0.0
    assert novel.auc < 0.5, "scored below the legitimate rows, which is worse than chance"


def test_the_recall_interval_is_wide_at_the_sample_size_a_thin_variant_actually_has() -> None:
    """PB-61's reversal-scam variant: 4 of 66 caught. The point estimate alone (6.1%) reads as
    settled; the interval is what says the true rate could plausibly be anywhere up to 14.6%.
    """
    result = VariantResult(
        variant="reversal_scam_social_engineering", fraud=66, detected=4, auc=0.656, error=0.037
    )
    lo, hi = result.recall_interval
    assert lo == pytest.approx(0.0238, abs=1e-4)
    assert hi == pytest.approx(0.1457, abs=1e-4)
    assert lo < result.recall < hi


def test_the_recall_interval_does_not_exceed_one_at_perfect_recall() -> None:
    """The normal approximation would overshoot 1.0 here; Wilson must not."""
    result = VariantResult(variant="base", fraud=10, detected=10, auc=1.0, error=0.0)
    lo, hi = result.recall_interval
    assert hi == pytest.approx(1.0)
    assert 0.0 < lo < 1.0


def test_the_recall_interval_is_undefined_without_any_fraud_rows() -> None:
    result = VariantResult(variant="empty", fraud=0, detected=0, auc=math.nan, error=math.nan)
    lo, hi = result.recall_interval
    assert math.isnan(lo)
    assert math.isnan(hi)
