"""PB-66's analyses on rows small enough to check by hand.

Each pre-registered measure (`docs/research/preregistration_pb66_full_volume.md`, section 3) has a
case whose answer is worked out here, not read back from the code under test.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime

import numpy as np
import pytest
from sklearn.metrics import average_precision_score

from fraudshield_ml.training import gate, pb66

DAY = pb66.MICROS_PER_DAY


def _micros(text: str) -> int:
    moment = datetime.fromisoformat(text).replace(tzinfo=UTC)
    return int(moment.timestamp()) * 1_000_000


# --- 3.1 ------------------------------------------------------------------------------------------


def test_average_precision_is_scikit_learns_including_ties() -> None:
    rng = np.random.default_rng(3)
    labels = rng.random(2_000) < 0.05
    # Rounded to two places, so many scores tie, which is where step definitions differ.
    scores = np.round(np.where(labels, rng.normal(0.6, 0.2, 2_000), rng.normal(0.4, 0.2, 2_000)), 2)
    assert pb66.average_precision(scores, labels) == pytest.approx(
        average_precision_score(labels, scores), abs=1e-12
    )


def test_tied_scores_are_flagged_together_on_the_pr_curve() -> None:
    precision, recall = pb66.pr_curve([0.9, 0.5, 0.5, 0.1], [True, True, False, False])
    # Three distinct thresholds: 0.9 flags one row, 0.5 flags three, 0.1 flags all four.
    assert precision.tolist() == pytest.approx([1.0, 2 / 3, 0.5])
    assert recall.tolist() == pytest.approx([0.5, 1.0, 1.0])


def test_average_precision_without_fraud_is_undefined() -> None:
    assert math.isnan(pb66.average_precision([0.1, 0.2], [False, False]))


# --- 3.2 ------------------------------------------------------------------------------------------


def _three_days() -> tuple[list[float], list[bool], list[int]]:
    """A partial first day, two full days, a partial last day."""
    stamps, scores, labels = [], [], []
    start = _micros("2025-10-28T22:00:00")
    for row in range(3):  # partial first day: three rows, all fraud, all top-scored
        stamps.append(start + row)
        scores.append(0.99)
        labels.append(True)
    for day in (1, 2):
        base = _micros("2025-10-28T00:00:00") + day * DAY
        for row in range(10):
            stamps.append(base + row * 1_000_000)
            # Day 1: frauds score highest. Day 2: the two frauds score lowest of all.
            fraud = row < 2
            scores.append((0.9 - row * 0.01) if day == 1 else (0.1 if fraud else 0.8 - row * 0.01))
            labels.append(fraud)
    stamps.append(_micros("2025-10-31T03:00:00"))
    scores.append(0.95)
    labels.append(True)
    return scores, labels, stamps


def test_partial_first_and_last_days_are_excluded_and_counted() -> None:
    _, _, stamps = _three_days()
    _, kept = pb66.full_days(stamps)
    assert len(kept) == 2
    days = pb66.daily_budget(*_three_days(), budgets=(3,))
    assert len(days.days) == 2
    assert len(days.excluded) == 2
    assert days.rows.tolist() == [10, 10]
    assert days.frauds.tolist() == [2, 2]


def test_a_budget_reports_precision_recall_and_the_ceiling_beside_it() -> None:
    days = pb66.daily_budget(*_three_days(), budgets=(3,))
    report = pb66.budget_report(days, resamples=50, seed=1)[3]
    # Day 1's top three hold both frauds; day 2's hold none. Six alerts, two caught, four frauds.
    assert report["precision"] == pytest.approx(2 / 6)
    assert report["recall"] == pytest.approx(2 / 4)
    # Each day holds two frauds, so at most two of its three alerts can be right.
    assert report["ceiling"] == pytest.approx(4 / 6)
    assert report["share_of_ceiling"] == pytest.approx(0.5)
    assert report["alert_rate"] == pytest.approx(6 / 20)
    low, high = report["intervals"]["precision"]  # type: ignore[index]
    assert 0.0 <= low <= high <= 2 / 3


def test_a_tie_straddling_the_cut_shares_the_remaining_slots() -> None:
    # Top score 0.9 (fraud); then four rows tied at 0.5, one of them fraud; budget 3.
    scores = np.array([0.9, 0.5, 0.5, 0.5, 0.5, 0.1])
    labels = np.array([True, False, True, False, False, False])
    alerts, caught, tied = pb66._top_k(scores, labels, 3)
    # Two slots for a block of four holding one fraud: half a fraud expected, wherever it sits.
    assert (alerts, caught, tied) == (3, 1.5, 2)
    # The same rows in another order give the same answer: no time-of-day tie-break.
    order = np.array([5, 4, 3, 2, 1, 0])
    assert pb66._top_k(scores[order], labels[order], 3) == (3, 1.5, 2)
    # A block that fits wholly under the cut decides nothing.
    assert pb66._top_k(scores, labels, 5) == (5, 2.0, 0)


def test_budget_intervals_resample_whole_days() -> None:
    days = pb66.daily_budget(*_three_days(), budgets=(3,))
    report = pb66.budget_report(days, resamples=400, seed=2)[3]
    # Two days, precision 2/3 and 0: a resample of whole days can only give 0, 1/3 or 2/3, so the
    # interval's ends are exactly the extremes. A row-level bootstrap would not land on them.
    assert report["intervals"]["precision"] == pytest.approx((0.0, 2 / 3))  # type: ignore[index]


def test_a_budget_larger_than_a_day_alerts_on_every_row() -> None:
    days = pb66.daily_budget(*_three_days(), budgets=(50,))
    assert days.caught[50][0].tolist() == [10, 10]


# --- 3.3 ------------------------------------------------------------------------------------------


def test_rows_are_bucketed_by_utc_month() -> None:
    stamps = [_micros("2025-10-31T23:59:59"), _micros("2025-11-01T00:00:00")]
    assert pb66.month_of(stamps) == ["2025-10", "2025-11"]


def test_each_month_reports_its_own_counts_metrics_and_intervals() -> None:
    rng = np.random.default_rng(5)
    months = ["2025-11"] * 600 + ["2025-12"] * 600
    labels = rng.random(1_200) < 0.2
    scores = np.clip(np.where(labels, 0.75, 0.3) + rng.normal(0, 0.15, 1_200), 0, 1)
    buckets = pb66.by_month(scores, labels, months, resamples=40, seed=2)
    assert [b.month for b in buckets] == ["2025-11", "2025-12"]
    assert [b.rows for b in buckets] == [600, 600]
    assert sum(b.frauds for b in buckets) == int(labels.sum())
    first = buckets[0]
    flag = gate.at_threshold(scores[:600], labels[:600], gate.FLAG)
    assert first.point["recall_at_flag"] == pytest.approx(flag.recall)
    assert first.point["auc"] == pytest.approx(gate.auc(scores[:600], labels[:600]))
    low, high = first.intervals["recall_at_flag"]
    assert low <= first.point["recall_at_flag"] <= high
    point, (d_low, d_high) = pb66.difference(buckets[1], buckets[0])
    assert point == pytest.approx(
        buckets[1].point["recall_at_flag"] - first.point["recall_at_flag"]
    )
    assert d_low <= point <= d_high
    # The difference's interval is wider than either month's own spread would suggest alone.
    assert d_high - d_low > (high - low) / 2


def test_a_month_without_fraud_is_reported_without_intervals() -> None:
    buckets = pb66.by_month([0.1, 0.2], [False, False], ["2025-10", "2025-10"], resamples=5, seed=1)
    assert buckets[0].frauds == 0
    assert math.isnan(buckets[0].point["auc"])
    assert all(math.isnan(v) for pair in buckets[0].intervals.values() for v in pair)
    # Its difference with another month has a point but no interval, rather than failing.
    other = pb66.by_month([0.9, 0.1], [True, False], ["2025-11"] * 2, resamples=5, seed=1)[0]
    point, bounds = pb66.difference(other, buckets[0])
    assert math.isnan(point)
    assert all(math.isnan(v) for v in bounds)


def test_a_day_number_reads_as_its_utc_date() -> None:
    assert pb66.day_of(_micros("2025-10-29T00:00:00") // DAY) == "2025-10-29"


# --- 3.4 ------------------------------------------------------------------------------------------


def test_a_perfect_ranking_costs_nothing_at_every_operating_point() -> None:
    curve = pb66.cost_curve([0.9, 0.8, 0.2, 0.1], [True, True, False, False])
    assert max(curve.model) == pytest.approx(0.0)
    assert curve.worse_than_trivial() == []


def test_an_uninformative_ranking_is_the_trivial_classifier() -> None:
    curve = pb66.cost_curve([0.5] * 4, [True, False, True, False])
    assert curve.model == pytest.approx(curve.trivial)
    # Equal to trivial is "not below" it, and the report says so.
    assert len(curve.worse_than_trivial()) == len([p for p in curve.pc if 0.01 <= p <= 0.99])


def test_the_cost_curve_never_leaves_zero_to_one_or_rises_above_trivial() -> None:
    rng = np.random.default_rng(11)
    labels = rng.random(3_000) < 0.01
    scores = rng.random(3_000)
    curve = pb66.cost_curve(scores, labels)
    assert min(curve.model) >= 0.0
    assert max(curve.model) <= 1.0
    assert all(m <= t + 1e-12 for m, t in zip(curve.model, curve.trivial, strict=True))
    assert curve.at(0.5) == curve.model[50]


def test_a_cost_curve_needs_both_classes() -> None:
    with pytest.raises(ValueError, match="both classes"):
        pb66.cost_curve([0.1, 0.2], [False, False])


# --- 3.5 ------------------------------------------------------------------------------------------


def test_fairness_counts_false_positives_and_blocked_amounts_per_group() -> None:
    scores = [0.9, 0.7, 0.2, 0.95, 0.1, 0.1]
    labels = [False, False, False, True, False, False]
    amounts = [100.0, 50.0, 10.0, 1_000.0, 5.0, 5.0]
    groups = ["RW", "RW", "RW", "KE", "KE", "KE"]
    report = pb66.fairness(scores, labels, amounts, groups, resamples=20, seed=3)
    rw, ke = report["RW"], report["KE"]
    assert (rw["rows"], rw["legitimate"], rw["frauds"]) == (3, 3, 0)
    assert rw["fpr_at_flag"] == pytest.approx(2 / 3)
    assert rw["fpr_at_block"] == pytest.approx(1 / 3)
    # The only blocked legitimate row is worth 100 of RW's 160.
    assert rw["legit_blocked_amount"] == pytest.approx(100.0)
    assert rw["legit_blocked_share"] == pytest.approx(100 / 160)
    assert ke["blocked_amount"] == pytest.approx(1_000.0)
    assert ke["legit_blocked_amount"] == 0.0
    assert ke["false_positives_at_flag"] == 0.0
    # A group without fraud still gets intervals: its legitimate rows are resampled alone.
    low, high = rw["intervals"]["fpr_at_flag"]  # type: ignore[index]
    assert 0.0 <= low <= high <= 1.0


def test_a_group_without_false_positives_still_has_room_above_zero() -> None:
    # Zero false positives in 500 legitimate rows: a bootstrap would say [0, 0], so any non-zero
    # overall rate would look "different". Wilson's upper bound stays above zero.
    report = pb66.fairness(
        [0.1] * 500, [False] * 500, [1.0] * 500, ["RW"] * 500, resamples=5, seed=1
    )
    low, high = report["RW"]["intervals"]["fpr_at_flag"]  # type: ignore[index]
    assert low == 0.0
    assert 0.005 < high < 0.01


def test_wilson_matches_its_textbook_values() -> None:
    low, high = pb66.wilson(10, 100)
    assert (low, high) == pytest.approx((0.0552, 0.1744), abs=1e-4)
    assert all(math.isnan(v) for v in pb66.wilson(0, 0))


def test_a_group_with_only_fraud_has_no_false_positive_rate() -> None:
    report = pb66.fairness([0.9], [True], [10.0], ["UG"], resamples=5, seed=1)
    assert math.isnan(report["UG"]["fpr_at_flag"])  # type: ignore[arg-type]
    assert math.isnan(report["UG"]["legit_blocked_share"])  # type: ignore[arg-type]


def test_intervals_of_undefined_draws_are_undefined() -> None:
    assert all(math.isnan(v) for v in pb66.interval([math.nan, math.nan]))
