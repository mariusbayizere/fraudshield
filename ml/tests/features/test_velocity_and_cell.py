"""Hand-computed expectations for the two implemented features, on both paths (Part E.2, E13).

Parity proves the paths agree; these prove they are both *right*. Neither substitutes for the
other, and the shared primitives are covered only by tests like these.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.types import Outcome, Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)
NAIROBI = (-1.2921, 36.8219)


#: ADR 0025's rule for real-valued features: relative, with an absolute floor.
def assert_parity(batch_value: float, online_value: float) -> None:
    assert abs(batch_value - online_value) <= 1e-12 + 1e-12 * abs(batch_value), (
        f"batch {batch_value!r} vs online {online_value!r} exceeds ADR 0025's tolerance"
    )


def tx(
    offset: timedelta,
    *,
    tid: str = "",
    account: str = "A",
    at: tuple[float, float] = KIGALI,
) -> Transaction:
    return Transaction(
        transaction_id=tid or f"t{offset.total_seconds():.0f}",
        account_id=account,
        timestamp=T + offset,
        amount_rwf=1000.0,
        latitude=at[0],
        longitude=at[1],
    )


SCORED = tx(timedelta(0), tid="scored")


# --- velocity_ratio_1h_vs_30d ---------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_zero_history_returns_exactly_one_on_both_paths() -> None:
    """The settled convention, hand-computed: (0 + 1) / (0 + 1) = 1.0, exactly.

    Not approximately: both terms are smoothed by the same alpha with prior 1.0, so the ratio is
    exactly representable. 0.0 would read as suspiciously quiet and NaN would discard the row.

    A genuinely new account's first-seen **is** the scored transaction — but the online path is
    told that, having consulted the durable store, rather than inferring it. The inference is
    indistinguishable from a post-flush cache, which is why it is the caller's job.
    """
    assert batch.velocity_ratio_1h_vs_30d([], SCORED, first_seen_at=SCORED.timestamp) == 1.0

    online = OnlineFeatures()
    online.restore_first_seen("A", SCORED.timestamp)
    assert online.velocity_ratio_1h_vs_30d(SCORED) == 1.0


@pytest.mark.req("FR-02-02", "D-04")
def test_the_online_path_fails_closed_without_a_durable_first_seen() -> None:
    """PB-37. No durable first-seen means no denominator, so NaN rather than a plausible number.

    Until the per-account table exists there is nothing to restore from, and the damaging case is
    not a cold start: it is arrivals restored from the database while first-seen is not, which
    divides thirty days of rows by whatever span the cache holds and collapses the ratio across the
    entire account base during a recovery. A missing feature is honest; that number is not.
    """
    online = OnlineFeatures()
    for i in range(5):
        online.observe(tx(timedelta(hours=-10 + i), tid=f"h{i}"))
    assert math.isnan(online.velocity_ratio_1h_vs_30d(SCORED)), (
        "the feature must be NaN when the durable first-seen is unavailable"
    )

    # And it must recover exactly once the durable value is supplied, or NaN would be permanent.
    online.restore_first_seen("A", T - timedelta(hours=25))
    recovered = online.velocity_ratio_1h_vs_30d(SCORED)
    assert not math.isnan(recovered), "restoring the durable value must restore the feature"
    assert recovered > 0.0


@pytest.mark.req("FR-02-02")
def test_observing_transactions_never_invents_a_first_seen() -> None:
    """The control for the test above: `observe` must not quietly fix the fail-closed case.

    If `observe` set first-seen from the first arrival, the NaN test would pass only until the
    first transaction arrived, and every post-flush account would silently get a wrong denominator
    instead of a NaN — which is the defect, not the fix.
    """
    online = OnlineFeatures()
    online.observe(tx(timedelta(hours=-5), tid="first"))
    assert math.isnan(online.velocity_ratio_1h_vs_30d(SCORED)), (
        "observe() inferred a first-seen from an arrival, which is the rolling window's edge "
        "rather than the account's start"
    )


@pytest.mark.req("FR-02-02")
def test_the_ratio_against_a_hand_computed_baseline() -> None:
    """first_seen 25 h ago, 24 transactions in the long window, 5 in the trailing hour.

    observed = 25 h, capped at 30 d -> 25 h. baseline_hours = 25 - 1 = 24 (the 1 h numerator is
    removed from its own denominator, `nesting=SHORT_EXCLUDED`).
    long_mean = 24 / 24 = 1.0 per hour.
    ratio = (5 + 1) / (1.0 + 1) = 6 / 2 = **3.0**.
    """
    first_seen = T - timedelta(hours=25)
    long_rows = [tx(timedelta(hours=-(24 - i))) for i in range(24)]  # -24h .. -1h inclusive
    short_rows = [tx(timedelta(minutes=-50 + 10 * i), tid=f"s{i}") for i in range(5)]
    history = long_rows + short_rows

    assert len(long_rows) == 24, "precondition: 24 long-window rows"
    assert len(short_rows) == 5, "precondition: 5 rows in the trailing hour"
    assert all(r.timestamp <= T - timedelta(hours=1) for r in long_rows), (
        "precondition: no long-window row strays into the trailing hour, which would double-count"
    )
    for row in short_rows:
        assert T - timedelta(hours=1) < row.timestamp < T, (
            "precondition: short rows are in the hour"
        )

    expected = 3.0
    got_batch = batch.velocity_ratio_1h_vs_30d(history, SCORED, first_seen_at=first_seen)
    assert got_batch == pytest.approx(expected, abs=1e-12)

    online = OnlineFeatures()
    online.restore_first_seen("A", first_seen)
    for row in sorted(history, key=lambda r: r.timestamp):
        online.observe(row)
    assert_parity(got_batch, online.velocity_ratio_1h_vs_30d(SCORED))


@pytest.mark.req("FR-02-02")
def test_a_transaction_exactly_on_the_hour_boundary_counts_as_long_not_short() -> None:
    """The bound both paths must agree on: `(t-30d, t-1h]` and `(t-1h, t)` (parity mutation 2).

    One transaction, exactly at t - 1 h, first_seen 25 h ago so baseline_hours = 24.
    Counted long:  ratio = (0 + 1) / (1/24 + 1) = 24/25 = **0.96**.
    Counted short: ratio = (1 + 1) / (0 + 1)    = **2.0**.
    The two readings differ by more than a tolerance could hide, which is what makes this test able
    to fail.
    """
    first_seen = T - timedelta(hours=25)
    boundary = [tx(timedelta(hours=-1), tid="boundary")]
    assert boundary[0].timestamp == T - timedelta(hours=1), "precondition: exactly on the boundary"

    expected = 24.0 / 25.0
    got_batch = batch.velocity_ratio_1h_vs_30d(boundary, SCORED, first_seen_at=first_seen)
    assert got_batch == pytest.approx(expected, abs=1e-12)
    assert got_batch != pytest.approx(2.0), "the boundary row was counted in the short window"

    online = OnlineFeatures()
    online.restore_first_seen("A", first_seen)
    online.observe(boundary[0])
    assert_parity(got_batch, online.velocity_ratio_1h_vs_30d(SCORED))


@pytest.mark.req("FR-02-02")
def test_observed_capped_history_does_not_divide_a_new_account_by_thirty_days() -> None:
    """A three-day-old account with 6 prior transactions, none in the trailing hour.

    ASSUMED_FULL would give 6 / 719 = 0.00834/h and a ratio of (0+1)/(1.00834) = 0.99173.
    OBSERVED_CAPPED gives 6 / 71 = 0.084507/h and a ratio of (0+1)/(1.084507) = **0.92208**.
    The wrong one makes a new account look quieter, which is the direction that makes it look safe.
    """
    first_seen = T - timedelta(hours=72)
    # The founding transaction IS the first-seen moment: a fixture where first_seen precedes every
    # row in the history describes an account that cannot exist, and the two paths would disagree
    # for that reason rather than for a real one.
    history = [tx(timedelta(hours=-72), tid="founding")] + [
        tx(timedelta(hours=-60) + timedelta(hours=10 * i)) for i in range(5)
    ]
    assert history[0].timestamp == first_seen, "precondition: the history starts at first-seen"
    assert len(history) == 6, "precondition: six prior transactions"
    assert all(r.timestamp <= T - timedelta(hours=1) for r in history), (
        "precondition: no fixture row falls in the trailing hour"
    )

    expected = 1.0 / (6.0 / 71.0 + 1.0)
    got_batch = batch.velocity_ratio_1h_vs_30d(history, SCORED, first_seen_at=first_seen)
    assert got_batch == pytest.approx(expected, rel=1e-12)
    assert got_batch == pytest.approx(0.922078, abs=1e-6)

    online = OnlineFeatures()
    online.restore_first_seen("A", first_seen)
    for row in history:
        online.observe(row)
    assert_parity(got_batch, online.velocity_ratio_1h_vs_30d(SCORED))


# --- geo_cell_fraud_rate_30d ----------------------------------------------------------------


def _cell_corpus(
    n: int, frauds: int, *, hidden: int = 0
) -> tuple[list[Transaction], dict[str, Outcome]]:
    """`hidden` labels (taken from the frauds) have not arrived by scoring time.

    A row whose label is unavailable is excluded from the rate **entirely**, numerator and
    denominator both: its outcome is unknown, so it carries no information. Counting it as
    legitimate would bias every cell's rate downward by the size of the investigation backlog.
    """
    corpus = [
        tx(timedelta(days=-20) + timedelta(hours=i), tid=f"c{i}", account=f"A{i}") for i in range(n)
    ]
    outcomes = {}
    for i, row in enumerate(corpus):
        outcomes[row.transaction_id] = Outcome(
            transaction_id=row.transaction_id,
            is_fraud=i < frauds,
            available_at=T + timedelta(days=1) if i < hidden else row.timestamp + timedelta(days=1),
        )
    return corpus, outcomes


@pytest.mark.req("FR-02-02")
def test_the_cell_rate_against_a_hand_computed_value() -> None:
    """10 prior transactions in the cell, 2 confirmed fraud, alpha = 50, prior = 0.01.

    rate = (2 + 50 * 0.01) / (10 + 50) = 2.5 / 60 = **0.0416666...**
    """
    corpus, outcomes = _cell_corpus(10, 2)
    assert len(corpus) == 10, "precondition: the cell has the stated history"

    expected = 2.5 / 60.0
    got_batch = batch.geo_cell_fraud_rate_30d(corpus, outcomes, SCORED, prior=0.01)
    assert got_batch == pytest.approx(expected, rel=1e-12)

    online = OnlineFeatures()
    for row in corpus:
        online.observe(row)
    for outcome in outcomes.values():
        online.observe_outcome(outcome)
    assert_parity(got_batch, online.geo_cell_fraud_rate_30d(SCORED, prior=0.01))


@pytest.mark.req("FR-02-02")
def test_a_label_that_had_not_arrived_is_not_counted() -> None:
    """The leakage guard, hand-computed. One of the two frauds is not yet available.

    That row drops out of both numerator and denominator, because its outcome is unknown:
    rate = (1 + 0.5) / (9 + 50) = 1.5 / 59 = **0.02542...**, against 2.5/60 with the leak.
    A batch path filtering on confirmation rather than availability would read the larger value.
    """
    corpus, outcomes = _cell_corpus(10, 2, hidden=1)
    hidden = [o for o in outcomes.values() if o.available_at >= T]
    assert hidden, "precondition: at least one label is not yet available at scoring time"

    expected = 1.5 / 59.0
    got_batch = batch.geo_cell_fraud_rate_30d(corpus, outcomes, SCORED, prior=0.01)
    assert got_batch == pytest.approx(expected, rel=1e-12)
    assert got_batch != pytest.approx(2.5 / 60.0), "an unavailable label leaked into the rate"

    online = OnlineFeatures()
    for row in corpus:
        online.observe(row)
    for outcome in outcomes.values():
        online.observe_outcome(outcome)
    assert_parity(got_batch, online.geo_cell_fraud_rate_30d(SCORED, prior=0.01))


@pytest.mark.req("FR-02-02")
def test_an_unseen_cell_returns_the_prior_not_certainty() -> None:
    """Zero evidence returns the fitted base rate, which is why `prior` is a declared field.

    Shrinking a fraud rate toward 1.0 - the ratio's prior - would make every unseen cell read as
    certain fraud. (0 + 50*0.01) / (0 + 50) = **0.01**.
    """
    elsewhere = tx(timedelta(0), tid="far", at=NAIROBI)
    assert batch.geo_cell_fraud_rate_30d([], {}, elsewhere, prior=0.01) == pytest.approx(0.01)
    assert OnlineFeatures().geo_cell_fraud_rate_30d(elsewhere, prior=0.01) == pytest.approx(0.01)
