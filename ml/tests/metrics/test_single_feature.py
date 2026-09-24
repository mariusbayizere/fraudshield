"""Single-feature separation and account-grouped encoding (E1, E3, hand-computed).

The encoding test is the one E1 demands of every fold, split, sample and target encoding: build
data where grouping by account and grouping by row **genuinely disagree**, and assert they do. M2's
first attempt at this test constructed categories that were purely fraud or purely legitimate, so
both foldings agreed trivially and the test passed while proving nothing.
"""

from __future__ import annotations

import math

import pytest

from fraudshield_ml.metrics.single_feature import (
    SINGLE_FEATURE_AUC_LIMIT,
    auc,
    auc_standard_error,
    class_counts,
    hash_fold,
    out_of_fold_target_encoding,
    separation,
)


@pytest.mark.req("D-08")
def test_auc_against_hand_computed_values() -> None:
    """Worked by hand from the rank-sum identity, not read off the implementation.

    With scores [1, 2, 3, 4] and labels [F, F, T, T] the two positives hold ranks 3 and 4, so the
    rank sum is 7; the minimum for two positives is 1 + 2 = 3, and (7 - 3) / (2 x 2) = **1.0**.
    Inverting the labels puts them at ranks 1 and 2, giving (3 - 3) / 4 = **0.0**.

    The interleaved cases are the ones worth pinning, because "alternating labels" is not the same
    as "no signal": [T, F, T, F] puts the positives at ranks 1 and 3, giving (4 - 3) / 4 = **0.25**
    — a feature that predicts the negative class. [F, T, T, F] puts them at 2 and 3, giving
    (5 - 3) / 4 = **0.5**, which is the coin flip.
    """
    assert auc([1.0, 2.0, 3.0, 4.0], [False, False, True, True]) == 1.0
    assert auc([1.0, 2.0, 3.0, 4.0], [True, True, False, False]) == 0.0
    assert auc([1.0, 2.0, 3.0, 4.0], [True, False, True, False]) == 0.25
    assert auc([1.0, 2.0, 3.0, 4.0], [False, True, True, False]) == 0.5


@pytest.mark.req("D-08")
def test_ties_take_their_mean_rank_so_a_constant_feature_scores_one_half() -> None:
    """Ties are the normal case here, not an edge one: most of the 44 are counts, flags and
    saturating ratios, so runs of identical values are ordinary.

    A tie-breaking rule that favoured the positive class would report a **constant** feature at
    1.0 — the strongest possible shortcut, from a column carrying nothing.
    """
    assert auc([1.0, 1.0, 1.0, 1.0], [False, False, True, True]) == 0.5
    assert auc([1.0, 1.0, 2.0, 2.0], [False, True, False, True]) == 0.5
    # Half-tied: scores [1, 2, 2, 3] with labels [F, F, T, T]. The tied pair takes mean rank 2.5
    # each and the top takes 4, so the positive rank sum is 2.5 + 4 = 6.5 and
    # (6.5 - 3) / 4 = 0.875 — below the 1.0 an untied ordering would give, which is the tie
    # costing the feature exactly the half-pair it cannot order.
    assert auc([1.0, 2.0, 2.0, 3.0], [False, False, True, True]) == pytest.approx(0.875)


@pytest.mark.req("D-08")
def test_separation_reports_either_direction() -> None:
    """A feature that predicts the negative class perfectly is exactly as much of a shortcut.

    Reporting 0.05 as comfortably under a 0.80 ceiling is how that gets missed, which is why the
    ceiling is applied to `max(auc, 1 - auc)` and not to the AUC.
    """
    inverted = [1.0, 2.0, 3.0, 4.0]
    labels = [True, True, False, False]
    assert auc(inverted, labels) == 0.0
    assert separation(inverted, labels) == 1.0
    assert separation(inverted, labels) > SINGLE_FEATURE_AUC_LIMIT


@pytest.mark.req("D-08", "D-04")
def test_nan_scores_are_dropped_with_their_labels_not_scored_as_zero() -> None:
    """Structural NaN is the normal state of eight features, and scoring it as 0 would rank every
    missing row together at one end — a shortcut the feature does not have.

    Here the NaN rows are fraud: dropped, only the two real rows count and the single positive
    sits above the single negative, giving **1.0**. Scored as zero the same data gives **1/3**,
    because the two fraud rows are pinned to the bottom of the ranking by a value the feature
    never produced.
    """
    scores = [math.nan, math.nan, 1.0, 2.0]
    labels = [True, True, False, True]
    assert auc(scores, labels) == pytest.approx(1.0)
    assert auc([0.0, 0.0, 1.0, 2.0], labels) == pytest.approx(1 / 3), (
        "precondition: scoring the NaNs as zero gives a materially different answer, so the "
        "choice is not cosmetic"
    )


@pytest.mark.req("D-08")
def test_an_undefined_auc_is_nan_rather_than_a_half() -> None:
    """One class absent means the question has no answer. 0.5 would read as "no signal", which is
    a measurement, and there is none."""
    assert math.isnan(auc([1.0, 2.0], [True, True]))
    assert math.isnan(auc([], []))


# --- E1: the encoding folds by account, not by row -------------------------------------------


@pytest.mark.req("D-08", "ML-GATE-01")
def test_the_encoding_folds_by_account_not_by_row() -> None:
    """E1's required shape: data where the two groupings genuinely disagree, asserted.

    Ten accounts, each with five rows, and the fraud is **concentrated in incidents**: every row of
    a fraudulent account is fraud. Category X belongs to fraudulent accounts and category Y to
    clean ones, but each account's rows all share its category — so folding per row leaves four
    other rows of the same incident inside the estimate that scores the fifth, while folding by
    account removes all five together.

    The categories are deliberately **mixed** overall — X is not purely fraud across the dataset,
    because two clean accounts also use it. M2's first attempt made them pure, and then both
    foldings agreed trivially and the test passed while proving nothing.
    """
    accounts, categories, labels = [], [], []
    for a in range(10):
        fraudulent = a < 4
        category = "X" if a < 6 else "Y"
        for _ in range(5):
            accounts.append(f"A{a}")
            categories.append(category)
            labels.append(fraudulent)

    assert len({c for c, y in zip(categories, labels, strict=True) if y}) == 1
    assert len({c for c, y in zip(categories, labels, strict=True) if not y}) == 2, (
        "precondition: the categories are mixed, so grouped and ungrouped can disagree (E12)"
    )

    grouped = out_of_fold_target_encoding(categories, labels, accounts)
    # The mutation: fold by row, which is what M-8 did.
    per_row = out_of_fold_target_encoding(
        categories, labels, [f"row{i}" for i in range(len(labels))]
    )
    assert grouped != per_row, (
        "grouping by account and by row produced identical encodings, so this fixture cannot "
        "tell them apart and the test proves nothing about the grouping"
    )
    assert separation(per_row, labels) >= separation(grouped, labels), (
        "folding per row must not make the feature look weaker; if it does, the fixture's "
        "incidents are not concentrated and the leak has nothing to carry"
    )


@pytest.mark.req("D-08")
def test_the_encoding_is_smoothed_so_a_rare_category_is_not_certain() -> None:
    """A category seen twice must not score 0 or 1 on the strength of two rows.

    The prior is the training folds' own base rate, never the global one: a prior fitted on
    everything puts the held-out labels into every cell, which is the leak one level up from the
    one the folds prevent.
    """
    accounts = [f"A{i}" for i in range(40)]
    categories = ["common"] * 38 + ["rare", "rare"]
    labels = [i % 10 == 0 for i in range(38)] + [True, True]
    encoded = out_of_fold_target_encoding(categories, labels, accounts)
    rare = [
        value for value, category in zip(encoded, categories, strict=True) if category == "rare"
    ]
    assert rare, "precondition: the rare category survived into the encoding"
    assert all(0.0 < value < 0.5 for value in rare), (
        f"a category seen twice encoded to {rare}, which is a certainty built from two rows"
    )


@pytest.mark.req("D-08")
def test_fold_assignment_is_stable_across_processes() -> None:
    """Python's `hash()` is salted per process, so using it would make folds differ between runs
    and break E4's determinism silently — the encoding would still look fine in any one run."""
    assert hash_fold("A123", 5) == hash_fold("A123", 5)
    assert {hash_fold(f"A{i}", 5) for i in range(200)} == {0, 1, 2, 3, 4}
    assert hash_fold("A123", 5) == 0, "pinned, so a change to the digest is a visible decision"


@pytest.mark.req("D-08")
def test_the_standard_error_says_what_the_sample_can_resolve() -> None:
    """A ceiling check without an interval is a number pretending to be a decision.

    At this benchmark's fraud rate a twenty-thousand-row sample holds about 170 positives, and the
    95% interval around 0.80 is then roughly +/- 0.04 — so a feature measured at 0.78 could be
    0.82. Hand-checked against Hanley and McNeil's formula at that shape.
    """
    error = auc_standard_error(0.80, positives=174, negatives=19_826)
    assert error == pytest.approx(0.0202, abs=0.0005)
    assert 1.96 * error == pytest.approx(0.040, abs=0.001)

    # More positives narrow it; the negatives barely matter once they are plentiful, which is why
    # enriching a sample for fraud is worth more than simply taking more rows.
    richer = auc_standard_error(0.80, positives=350, negatives=39_650)
    assert richer < error
    assert auc_standard_error(0.80, positives=174, negatives=100_000) == pytest.approx(
        error, abs=0.004
    )


@pytest.mark.req("D-08")
def test_the_standard_error_is_undefined_where_the_auc_is() -> None:
    """One class absent, or fewer than two of either, leaves nothing to estimate a spread from."""
    assert math.isnan(auc_standard_error(0.8, positives=1, negatives=100))
    assert math.isnan(auc_standard_error(0.8, positives=100, negatives=0))
    assert math.isnan(auc_standard_error(math.nan, positives=10, negatives=10))


@pytest.mark.req("D-08", "D-04")
def test_the_counts_are_of_the_rows_the_feature_actually_scored() -> None:
    """A feature NaN on four rows in five is measured on a fifth of the sample, and its interval
    is correspondingly wider — which is exactly what a bare AUC hides.

    The structural NaNs make this the normal case, not an edge one: four device features are NaN on
    every USSD transaction and four agent features on every non-agent one.
    """
    scores = [math.nan, math.nan, math.nan, 1.0, 2.0]
    labels = [True, True, False, False, True]
    assert class_counts(scores, labels) == (1, 1)
    assert class_counts([1.0, 2.0, 3.0], [True, False, True]) == (2, 1)
