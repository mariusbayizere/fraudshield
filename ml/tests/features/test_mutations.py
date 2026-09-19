"""The parity mutation table, executed (E14, parity Decision 5).

A test that cannot fail proves nothing. Each case here applies a deliberate divergence and asserts
that **ADR 0025's tolerance would catch it** — that the correct and mutated values disagree by more
than `1e-12 + 1e-12 * |correct|`. A mutation that slips inside the tolerance is not a curiosity: it
is the specification for a case the suite is missing.

Results are recorded in `docs/ml/training_serving_parity.md`, beside the design they test, filled
in as each is implemented rather than assembled at the end.

Rows 9 (bucket-aligned fallback), 1, 5 and 8 are **not runnable yet** — they need the DB fallback
path, an amount-sum feature and a structural-NaN feature, none of which exists. They stay `pending`
rather than being marked passed by omission.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.registry import smoothing_for
from fraudshield_ml.features.types import Outcome, Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)


def _tx(offset: timedelta, *, tid: str, account: str = "A") -> Transaction:
    return Transaction(
        transaction_id=tid,
        account_id=account,
        timestamp=T + offset,
        amount_rwf=1000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
    )


SCORED = _tx(timedelta(0), tid="scored")


def assert_detected(correct: float, mutated: float, mutation: str) -> None:
    """The mutation must move the value beyond ADR 0025's tolerance."""
    gap = abs(correct - mutated)
    allowed = 1e-12 + 1e-12 * abs(correct)
    assert gap > allowed, (
        f"mutation {mutation!r} moved the value by {gap!r}, inside the tolerance {allowed!r}. "
        "The parity suite would not catch it, so this is the specification for a missing case."
    )


@pytest.mark.req("FR-02-02")
def test_mutation_2_window_bound_off_by_one_is_detected() -> None:
    """A row exactly on `t - 1h` moved from the long window into the short one.

    Correct: (0 + 1) / (1/24 + 1) = 24/25 = 0.96. Mutated: (1 + 1) / (0 + 1) = 2.0.
    """
    first_seen = T - timedelta(hours=25)
    boundary = _tx(timedelta(hours=-1), tid="boundary")
    alpha = smoothing_for("velocity_ratio_1h_vs_30d").alpha

    correct = batch.velocity_ratio_1h_vs_30d([boundary], SCORED, first_seen_at=first_seen)

    # `<` where the implementation has `<=`: the boundary row becomes a short-window row.
    short_count = sum(1 for x in [boundary] if T - timedelta(hours=1) <= x.timestamp < T)
    long_count = sum(1 for x in [boundary] if x.timestamp < T - timedelta(hours=1))
    long_mean = long_count / 24.0
    mutated = (short_count + alpha) / (long_mean + alpha)

    assert correct == pytest.approx(24 / 25, abs=1e-12)
    assert mutated == pytest.approx(2.0, abs=1e-12)
    assert_detected(correct, mutated, "window bound < instead of <=")


@pytest.mark.req("FR-02-02")
def test_mutation_3_silent_window_drift_is_detected() -> None:
    """A 30 d window computed over 31 d, so rows just outside it are counted.

    The fixture places rows in the band `(t-31d, t-30d)`, which the correct window excludes.
    """
    first_seen = T - timedelta(days=40)
    outside = [_tx(timedelta(days=-31) + timedelta(hours=i), tid=f"o{i}") for i in range(12)]
    inside = [_tx(timedelta(days=-10) + timedelta(hours=i), tid=f"i{i}") for i in range(12)]
    assert all(r.timestamp < T - timedelta(days=30) for r in outside), (
        "precondition: the drifted rows are genuinely outside the correct 30 d window"
    )

    alpha = smoothing_for("velocity_ratio_1h_vs_30d").alpha
    correct = batch.velocity_ratio_1h_vs_30d(outside + inside, SCORED, first_seen_at=first_seen)

    baseline_hours = 30 * 24 - 1
    drifted_long = sum(
        1
        for x in outside + inside
        if T - timedelta(days=31) < x.timestamp <= T - timedelta(hours=1)
    )
    mutated = (0 + alpha) / (drifted_long / baseline_hours + alpha)

    assert_detected(correct, mutated, "30 d window computed over 31 d")


@pytest.mark.req("FR-02-02")
def test_mutation_7_using_a_label_before_it_was_available_is_detected() -> None:
    """The batch path filtering on confirmation rather than availability.

    Correct: the unavailable row drops out entirely, (1 + 0.5) / (9 + 50) = 1.5/59.
    Mutated: it is counted as confirmed fraud, (2 + 0.5) / (10 + 50) = 2.5/60.
    """
    corpus = [
        _tx(timedelta(days=-20) + timedelta(hours=i), tid=f"c{i}", account=f"A{i}")
        for i in range(10)
    ]
    available = {
        row.transaction_id: Outcome(
            transaction_id=row.transaction_id,
            is_fraud=i < 2,
            available_at=T + timedelta(days=1) if i == 0 else row.timestamp + timedelta(days=1),
        )
        for i, row in enumerate(corpus)
    }
    ignoring_availability = {
        tid: Outcome(transaction_id=tid, is_fraud=o.is_fraud, available_at=corpus[0].timestamp)
        for tid, o in available.items()
    }
    assert any(o.available_at >= T for o in available.values()), (
        "precondition: at least one label had not arrived by scoring time"
    )

    correct = batch.geo_cell_fraud_rate_30d(corpus, available, SCORED, prior=0.01)
    mutated = batch.geo_cell_fraud_rate_30d(corpus, ignoring_availability, SCORED, prior=0.01)

    assert correct == pytest.approx(1.5 / 59, rel=1e-12)
    assert mutated == pytest.approx(2.5 / 60, rel=1e-12)
    assert_detected(correct, mutated, "label used before label_available_at")


@pytest.mark.req("FR-02-02")
def test_mutation_10_losing_the_durable_first_seen_is_detected() -> None:
    """`account_first_seen_at` lost on flush, so OBSERVED_CAPPED divides by a shorter history.

    **The damaging case is not the one it first looks like.** Two flush scenarios exist and they
    behave oppositely:

    * *Everything lost* — arrivals and first-seen both re-derived from what comes back. The
      numerator's row count and the denominator's span shrink together, the ratio barely moves, and
      the mutation is nearly invisible.
    * *Arrivals restored, first-seen not* — the transactions come back from the database while the
      per-account first-seen does not, because **there is no table for it** (PB-37). A full 30 days
      of rows are then divided by a two-day apparent history: the baseline inflates and the ratio
      collapses, on every established account at once.

    The second is exactly what M1's schema produces today, which is what makes PB-37 a correctness
    item rather than a convenience. Asserted as *detected*, not as a direction: the direction
    depends on which scenario occurs, and claiming one would be a guess dressed as a test.
    """
    true_first_seen = T - timedelta(days=30)
    # 6-hourly across the whole 30 days, so a flush genuinely leaves recent arrivals.
    history = [_tx(timedelta(days=-30) + timedelta(hours=6 * i), tid=f"h{i}") for i in range(119)]
    assert history[0].timestamp == true_first_seen, "precondition: history starts at first-seen"

    correct = batch.velocity_ratio_1h_vs_30d(history, SCORED, first_seen_at=true_first_seen)

    survivors = [r for r in history if r.timestamp > T - timedelta(days=2)]
    assert survivors, "precondition: a flush leaves some recent arrivals"
    assert len(survivors) < len(history), "precondition: a flush also loses some"

    # Arrivals restored from the database, first-seen re-derived from the surviving window.
    rederived = batch.velocity_ratio_1h_vs_30d(
        history, SCORED, first_seen_at=survivors[0].timestamp
    )
    assert rederived < correct, (
        "a full 30 days of rows over a two-day apparent history must inflate the baseline and "
        "collapse the ratio; if it does not, OBSERVED_CAPPED is not being applied"
    )
    assert_detected(correct, rederived, "durable first-seen lost while arrivals are restored")

    # The other scenario, recorded because it is the one that would lull a reviewer.
    both_lost = batch.velocity_ratio_1h_vs_30d(
        survivors, SCORED, first_seen_at=survivors[0].timestamp
    )
    assert abs(both_lost - correct) < abs(rederived - correct), (
        "losing everything together should move the value LESS than losing first-seen alone; if "
        "this ordering ever reverses, the reasoning in this docstring is wrong"
    )


@pytest.mark.req("FR-02-02")
def test_mutation_12_cell_rate_over_all_rows_rather_than_training_folds_is_detected() -> None:
    """A non-account-keyed aggregate computed over the whole corpus instead of training rows.

    This is the leak `cross_account_control` names. Its observable signature on a model is a rise
    in single-feature AUC; here, one step earlier, it is that the feature's value changes at all —
    which is the necessary condition for that rise and is checkable without fitting anything.
    """
    training = [
        _tx(timedelta(days=-20) + timedelta(hours=i), tid=f"tr{i}", account=f"TR{i}")
        for i in range(8)
    ]
    held_out = [
        _tx(timedelta(days=-15) + timedelta(hours=i), tid=f"va{i}", account=f"VA{i}")
        for i in range(8)
    ]
    outcomes = {
        row.transaction_id: Outcome(
            transaction_id=row.transaction_id,
            is_fraud=row.transaction_id.startswith("va"),
            available_at=row.timestamp + timedelta(days=1),
        )
        for row in training + held_out
    }
    assert {r.account_id for r in training}.isdisjoint({r.account_id for r in held_out}), (
        "precondition: the folds share no account, so any difference is cross-account leakage"
    )

    correct = batch.geo_cell_fraud_rate_30d(training, outcomes, SCORED, prior=0.0087)
    mutated = batch.geo_cell_fraud_rate_30d(training + held_out, outcomes, SCORED, prior=0.0087)

    assert mutated > correct, (
        "the held-out fold's fraud must raise the rate, or the fixture is flat"
    )
    assert_detected(correct, mutated, "cell rate computed over all rows, not training folds")
