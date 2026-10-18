"""The six temporal features on both paths, hand-computed (Part E.2, E13, D-43).

Five are functions of the transaction's **local** civil time, which is derived from the country
pack's UTC offset and never stored beside the UTC timestamp — storing both is parity mutation 4,
two fields that must agree maintained by two paths. The sixth reads the previous transaction with
no window at all, which is the first feature whose state a rolling cache cannot rebuild.

Every fixture below is chosen so that **the local answer and the UTC answer differ**. A transaction
at midday in a +2 country is midday in both, and a suite built from such rows would pass with the
offset ignored entirely.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.types import CountryFacts, Transaction

#: Two real offsets and one invented negative one, so a sign error is visible. +2 and +3 are the
#: packs' own values; QQ exists only to prove nothing assumes a positive offset.
PACKS: dict[str, CountryFacts] = {
    "RW": CountryFacts("RW", "AF", frozenset({"EAC"}), 2),
    "KE": CountryFacts("KE", "AF", frozenset({"EAC"}), 3),
    "QQ": CountryFacts("QQ", "QQ", frozenset({"QBLOC"}), -5),
}


def at(moment: datetime, *, country: str = "RW", account: str = "A", tid: str = "t") -> Transaction:
    return Transaction(
        transaction_id=tid,
        account_id=account,
        timestamp=moment,
        amount_rwf=1_000.0,
        latitude=-1.9441,
        longitude=30.0619,
        counterparty_id="M1",
        account_country=country,
    )


def online() -> OnlineFeatures:
    return OnlineFeatures()


# --- local time -------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-43")
def test_local_time_comes_from_the_pack_and_differs_from_utc() -> None:
    """22:30 UTC is 00:30 the next day in +2 and 17:30 the previous evening in -5.

    All three answers are a different **day**, not merely a different hour, so a path that ignored
    the offset would be wrong about the date as well as the time — and both errors are visible in
    one assertion.
    """
    moment = datetime(2025, 6, 1, 22, 30, tzinfo=UTC)
    assert batch.local_time(at(moment, country="RW"), PACKS).day == 2
    assert batch.local_time(at(moment, country="RW"), PACKS).hour == 0
    assert batch.local_time(at(moment, country="QQ"), PACKS).day == 1
    assert batch.local_time(at(moment, country="QQ"), PACKS).hour == 17
    assert moment.hour == 22, "precondition: the UTC hour differs from both local hours"


@pytest.mark.req("FR-02-02", "D-43")
def test_both_paths_read_the_same_local_hour() -> None:
    """The offset is applied once, by each path, from the same pack field."""
    for hour in range(24):
        moment = datetime(2025, 6, 1, hour, 15, tzinfo=UTC)
        for country in PACKS:
            scored = at(moment, country=country)
            assert batch.local_hour(scored, PACKS) == online().local_hour(scored, PACKS)


@pytest.mark.req("FR-02-02")
def test_a_transaction_without_a_country_is_refused_rather_than_read_as_utc() -> None:
    """UTC is itself a plausible-looking local time, so a fallback would shift every hour-of-day
    feature by the offset and do it silently."""
    scored = Transaction(
        transaction_id="t",
        account_id="A",
        timestamp=datetime(2025, 6, 1, 12, tzinfo=UTC),
        amount_rwf=1.0,
        latitude=0.0,
        longitude=0.0,
    )
    assert scored.account_country is None, "precondition: the row carries no country"
    with pytest.raises(ValueError, match="account_country"):
        batch.local_hour(scored, PACKS)
    with pytest.raises(ValueError, match="account_country"):
        online().local_hour(scored, PACKS)


# --- the sin/cos pair --------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_hour_pair_against_hand_computed_values() -> None:
    """Local 00:00 gives (0, 1); 06:00 gives (1, 0); 12:00 gives (0, -1); 18:00 gives (-1, 0).

    22:00 UTC in +2 is local midnight, which is the case the encoding exists for: its sin and cos
    sit next to 23:00's rather than at the opposite end of a 0-23 line.
    """
    midnight = at(datetime(2025, 6, 1, 22, tzinfo=UTC))
    assert batch.local_hour(midnight, PACKS) == 0
    assert batch.local_hour_sin(midnight, PACKS) == pytest.approx(0.0, abs=1e-15)
    assert batch.local_hour_cos(midnight, PACKS) == pytest.approx(1.0, abs=1e-15)

    for utc_hour, expected in ((4, (1.0, 0.0)), (10, (0.0, -1.0)), (16, (-1.0, 0.0))):
        scored = at(datetime(2025, 6, 1, utc_hour, tzinfo=UTC))
        assert batch.local_hour_sin(scored, PACKS) == pytest.approx(expected[0], abs=1e-15)
        assert batch.local_hour_cos(scored, PACKS) == pytest.approx(expected[1], abs=1e-15)
        assert online().local_hour_sin(scored, PACKS) == batch.local_hour_sin(scored, PACKS)
        assert online().local_hour_cos(scored, PACKS) == batch.local_hour_cos(scored, PACKS)


@pytest.mark.req("FR-02-02")
def test_23_and_00_are_adjacent_which_is_the_point_of_the_encoding() -> None:
    """The property the pair exists for, asserted rather than described.

    On the raw 0-23 scale, 23 and 0 are as far apart as any two hours. On the circle they are one
    step apart — the same distance as 11 and 12 — and this asserts that the gap between 23 and 0 is
    smaller than the gap between 0 and 12.
    """

    def point(hour_utc: int) -> tuple[float, float]:
        scored = at(datetime(2025, 6, 1, hour_utc, tzinfo=UTC))
        return batch.local_hour_sin(scored, PACKS), batch.local_hour_cos(scored, PACKS)

    def gap(a: tuple[float, float], b: tuple[float, float]) -> float:
        return math.hypot(a[0] - b[0], a[1] - b[1])

    local_23, local_00, local_12 = point(21), point(22), point(10)
    assert gap(local_23, local_00) < gap(local_00, local_12)
    assert gap(local_23, local_00) == pytest.approx(gap(local_00, point(23)), abs=1e-12)


@pytest.mark.req("FR-02-02")
def test_the_pair_is_on_the_unit_circle_for_every_hour() -> None:
    """Both must be computed from one hour value; computed from two they would leave the circle."""
    for hour in range(24):
        scored = at(datetime(2025, 6, 1, hour, tzinfo=UTC))
        s = batch.local_hour_sin(scored, PACKS)
        c = batch.local_hour_cos(scored, PACKS)
        assert s * s + c * c == pytest.approx(1.0, abs=1e-15)


# --- day of week, night, month end ---------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-43")
def test_the_local_day_of_week_can_differ_from_the_utc_one() -> None:
    """2025-06-01 is a Sunday. At 23:30 UTC it is already Monday in +2, so weekday goes 6 -> 0.

    A week-day feature computed in UTC would place a Monday-morning salary run in the weekend, and
    the salary cycle is one of the behaviours the dataset models explicitly.
    """
    moment = datetime(2025, 6, 1, 23, 30, tzinfo=UTC)
    assert moment.weekday() == 6, "precondition: the UTC day is Sunday"
    scored = at(moment)
    assert batch.local_day_of_week(scored, PACKS) == 0
    assert online().local_day_of_week(scored, PACKS) == 0


@pytest.mark.req("FR-02-02")
def test_local_night_is_midnight_to_four_fifty_nine_inclusive() -> None:
    """Both ends asserted: 04:59 is night and 05:00 is not, which an off-by-one would swap."""
    cases = ((22, 0, True), (2, 59, True), (2, 30, True), (3, 0, False), (9, 0, False))
    for utc_hour, minute, expected in cases:
        scored = at(datetime(2025, 6, 1, utc_hour, minute, tzinfo=UTC))
        assert batch.is_local_night(scored, PACKS) is expected, (utc_hour, minute)
        assert online().is_local_night(scored, PACKS) is expected, (utc_hour, minute)
    # Local 04:59 and 05:00, an hour apart on either side of the boundary.
    assert batch.local_hour(at(datetime(2025, 6, 1, 2, 59, tzinfo=UTC)), PACKS) == 4
    assert batch.local_hour(at(datetime(2025, 6, 1, 3, 0, tzinfo=UTC)), PACKS) == 5


@pytest.mark.req("FR-02-02")
def test_the_month_end_window_is_the_last_three_and_first_two_local_days() -> None:
    """June has 30 days, so 28, 29 and 30 June and 1 and 2 July are in; 27 June and 3 July are not.

    Times are chosen at 10:00 UTC (local noon) so the local day equals the UTC day and the day
    arithmetic is what is being tested rather than the offset.
    """
    inside = ((6, 28), (6, 29), (6, 30), (7, 1), (7, 2))
    outside = ((6, 27), (7, 3), (6, 15))
    for month, day in inside:
        scored = at(datetime(2025, month, day, 10, tzinfo=UTC))
        assert batch.is_month_end_window(scored, PACKS) is True, (month, day)
        assert online().is_month_end_window(scored, PACKS) is True, (month, day)
    for month, day in outside:
        scored = at(datetime(2025, month, day, 10, tzinfo=UTC))
        assert batch.is_month_end_window(scored, PACKS) is False, (month, day)
        assert online().is_month_end_window(scored, PACKS) is False, (month, day)


@pytest.mark.req("FR-02-02")
def test_month_length_is_the_real_one_including_a_leap_february() -> None:
    """The window is "the last three days", not "from the 29th": February 26 is inside it in 2025
    and outside it in 2024, and 2024-02-29 exists.

    A table of month lengths gets this wrong one year in four, which is why the length is derived
    by stepping to the first of the next month and back a day.
    """
    assert batch.is_month_end_window(at(datetime(2025, 2, 26, 10, tzinfo=UTC)), PACKS) is True
    assert batch.is_month_end_window(at(datetime(2024, 2, 26, 10, tzinfo=UTC)), PACKS) is False
    assert batch.is_month_end_window(at(datetime(2024, 2, 27, 10, tzinfo=UTC)), PACKS) is True
    assert batch.is_month_end_window(at(datetime(2024, 2, 29, 10, tzinfo=UTC)), PACKS) is True
    # December, where the next month is in the following year.
    assert batch.is_month_end_window(at(datetime(2025, 12, 29, 10, tzinfo=UTC)), PACKS) is True
    assert batch.is_month_end_window(at(datetime(2025, 12, 20, 10, tzinfo=UTC)), PACKS) is False


@pytest.mark.req("FR-02-02", "D-43")
def test_the_month_end_window_follows_the_local_month_boundary() -> None:
    """2025-06-30 22:30 UTC is already 1 July locally in +2 — still inside the window, but for the
    other reason, and PB-26 pinned that the partition key is this same local month.

    The case that distinguishes the two readings is the far edge: 2025-07-03 22:30 UTC is 4 July
    locally, outside the window, while the UTC day 3 is also outside — so the test uses 2 July
    22:30 UTC, local 3 July, which is **outside** locally and **inside** in UTC.
    """
    crossing = at(datetime(2025, 7, 2, 22, 30, tzinfo=UTC))
    assert crossing.timestamp.day == 2, "precondition: the UTC day is inside the window"
    assert batch.local_time(crossing, PACKS).day == 3, "precondition: the local day is outside it"
    assert batch.is_month_end_window(crossing, PACKS) is False
    assert online().is_month_end_window(crossing, PACKS) is False


# --- seconds_since_last_tx ------------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-04")
def test_the_gap_since_the_previous_transaction_on_both_paths() -> None:
    """Two prior transactions 90 and 3,600 seconds back: the gap is the **most recent**, 90."""
    scored_at = datetime(2025, 6, 1, 12, tzinfo=UTC)
    history = [
        at(scored_at - timedelta(seconds=3_600), tid="old"),
        at(scored_at - timedelta(seconds=90), tid="recent"),
    ]
    scored = at(scored_at, tid="scored")
    assert batch.seconds_since_last_tx(history, scored) == 90.0

    features = online()
    for row in history:
        features.observe(row)
    assert features.seconds_since_last_tx(scored) == 90.0


@pytest.mark.req("FR-02-02", "D-04")
def test_a_first_transaction_has_no_gap_and_is_nan() -> None:
    """`minimum_history=1`, `below_threshold_value=None`. Zero would mean "no time has passed",
    which is the opposite of what a first transaction means."""
    scored = at(datetime(2025, 6, 1, 12, tzinfo=UTC))
    assert math.isnan(batch.seconds_since_last_tx([], scored))
    assert math.isnan(online().seconds_since_last_tx(scored))


@pytest.mark.req("FR-02-02")
def test_the_gap_is_unbounded_and_reaches_past_the_rolling_horizon() -> None:
    """`window=unbounded`. A transaction 200 days back still counts, and 200 days is beyond the
    90 d horizon the account store evicts at — which is the whole reason the previous transaction
    is held as durable state rather than read off the end of the rolling window.

    Without this case the feature would look correct while returning NaN for exactly the dormant
    accounts it exists to notice.
    """
    scored_at = datetime(2025, 6, 1, 12, tzinfo=UTC)
    long_ago = at(scored_at - timedelta(days=200), tid="dormant")
    scored = at(scored_at, tid="scored")

    features = online()
    features.observe(long_ago)
    expected = 200 * 86_400.0
    assert batch.seconds_since_last_tx([long_ago], scored) == expected
    assert features.seconds_since_last_tx(scored) == expected
    assert not features._in_window(scored, "amount_zscore_90d"), (
        "precondition: the arrival has been evicted from the 90 d window, so the value above "
        "cannot have come from it"
    )


@pytest.mark.req("FR-02-02", "D-04")
def test_a_flush_loses_the_gap_until_the_durable_value_is_restored() -> None:
    """`history_requirement=DURABLE`, and the only test that exercises it for this feature.

    The feature declares no window, so there is no span after which the cache is whole again: a
    flush loses the gap for every account until each transacts twice more. NaN rather than a gap
    measured from the first arrival after the flush, which would report a few minutes for an
    account that had been dormant for months — in the direction that reads as normal.
    """
    scored_at = datetime(2025, 6, 1, 12, tzinfo=UTC)
    previous = at(scored_at - timedelta(days=45), tid="previous")
    scored = at(scored_at, tid="scored")

    features = online()
    features.observe(previous)
    warm_value = features.seconds_since_last_tx(scored)
    assert warm_value == 45 * 86_400.0

    features.flush_cache()
    assert math.isnan(features.seconds_since_last_tx(scored)), (
        "a flush must lose the gap; if it does not, the value is being rebuilt from something the "
        "cache should not have kept"
    )

    features.restore_last_transaction(
        "A", previous.timestamp, previous.latitude, previous.longitude
    )
    assert features.seconds_since_last_tx(scored) == warm_value


@pytest.mark.req("FR-02-02")
def test_an_out_of_order_arrival_does_not_produce_a_negative_gap() -> None:
    """A replay that delivers a later transaction first must not move "the last transaction"
    forward past the row being scored: the gap would come out negative, which is not a value this
    feature has. NaN says so rather than a number that cannot be true."""
    scored_at = datetime(2025, 6, 1, 12, tzinfo=UTC)
    later = at(scored_at + timedelta(hours=1), tid="later")
    scored = at(scored_at, tid="scored")

    features = online()
    features.observe(later)
    assert math.isnan(features.seconds_since_last_tx(scored))
    assert math.isnan(batch.seconds_since_last_tx([later], scored))
