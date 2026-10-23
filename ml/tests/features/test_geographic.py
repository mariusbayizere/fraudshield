"""The four location-derived geographic features, hand-computed on both paths (Part E.2, E13).

`geo_cell_fraud_rate_30d` is the fifth and is covered with the label-derived features, where its
cross-account exposure is the subject. These four read only where the account has been.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.primitives import haversine_km
from fraudshield_ml.features.types import Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)
NAIROBI = (-1.2921, 36.8219)
#: 754.9 km apart on a sphere of radius 6371.0088 km, pinned by the shared-primitive test.
KIGALI_TO_NAIROBI_KM = 754.9


def at(
    hours_before: float,
    where: tuple[float, float],
    *,
    tid: str = "t",
    account: str = "A",
) -> Transaction:
    return Transaction(
        transaction_id=tid,
        account_id=account,
        timestamp=T - timedelta(hours=hours_before),
        amount_rwf=1_000.0,
        latitude=where[0],
        longitude=where[1],
        counterparty_id="M1",
    )


def warm(rows: list[Transaction]) -> OnlineFeatures:
    features = OnlineFeatures()
    for row in rows:
        features.observe(row)
    return features


SCORED = at(0.0, NAIROBI, tid="scored")


# --- distance_from_last_tx_km --------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_distance_from_the_previous_transaction_on_both_paths() -> None:
    """Kigali an hour ago, Nairobi now: 754.9 km, through the shared haversine primitive."""
    history = [at(1.0, KIGALI, tid="previous")]
    assert batch.distance_from_last_tx_km(history, SCORED) == pytest.approx(
        KIGALI_TO_NAIROBI_KM, abs=0.1
    )
    assert warm(history).distance_from_last_tx_km(SCORED) == pytest.approx(
        KIGALI_TO_NAIROBI_KM, abs=0.1
    )


@pytest.mark.req("FR-02-02")
def test_the_previous_transaction_is_the_most_recent_not_the_first() -> None:
    """Two priors, the older far away and the newer at the same place: the distance is 0, not 754.9.

    A path that took the earliest prior would read 754.9 here, and would be indistinguishable from
    a correct one on any fixture with a single prior transaction.
    """
    history = [at(10.0, KIGALI, tid="old"), at(1.0, NAIROBI, tid="recent")]
    assert batch.distance_from_last_tx_km(history, SCORED) == 0.0
    assert warm(history).distance_from_last_tx_km(SCORED) == 0.0


@pytest.mark.req("FR-02-02", "D-04")
def test_a_first_transaction_has_no_distance_and_is_nan() -> None:
    """0.0 would say "the same place", which is a claim about a journey that did not happen."""
    assert math.isnan(batch.distance_from_last_tx_km([], SCORED))
    assert math.isnan(OnlineFeatures().distance_from_last_tx_km(SCORED))


# --- implied_speed_kmh ---------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_implied_speed_against_hand_worked_values() -> None:
    """754.9 km in 1 h is 754.9 km/h, below the cap; in 2 h it is 377.45.

    754.9 km/h is a plausible aircraft speed, which is what makes it the right value to pin: the
    feature must distinguish "flew" from "impossible", and a cap applied too low would collapse
    both into the same number.
    """
    one_hour = [at(1.0, KIGALI, tid="p1")]
    assert batch.implied_speed_kmh(one_hour, SCORED) == pytest.approx(754.9, abs=0.1)
    assert warm(one_hour).implied_speed_kmh(SCORED) == pytest.approx(754.9, abs=0.1)

    two_hours = [at(2.0, KIGALI, tid="p2")]
    assert batch.implied_speed_kmh(two_hours, SCORED) == pytest.approx(377.45, abs=0.05)
    assert warm(two_hours).implied_speed_kmh(SCORED) == pytest.approx(377.45, abs=0.05)


@pytest.mark.req("FR-02-02")
def test_an_impossible_speed_is_capped_rather_than_reported() -> None:
    """754.9 km in 15 minutes is 3,019.6 km/h, which the cap reports as 1,000.

    The precondition asserts the uncapped value really is above the cap, so this cannot pass over a
    fixture that never reaches it.
    """
    quarter_hour = [at(0.25, KIGALI, tid="fast")]
    uncapped = KIGALI_TO_NAIROBI_KM / 0.25
    assert uncapped > batch.MAX_IMPLIED_SPEED_KMH, "precondition: the fixture exceeds the cap"
    assert batch.implied_speed_kmh(quarter_hour, SCORED) == batch.MAX_IMPLIED_SPEED_KMH
    assert warm(quarter_hour).implied_speed_kmh(SCORED) == batch.MAX_IMPLIED_SPEED_KMH


@pytest.mark.req("FR-02-02", "D-04")
def test_a_simultaneous_predecessor_makes_all_three_features_nan() -> None:
    """Owner decision 2026-09-19: strictly-earlier wins, and this is the silence it buys.

    A row stamped the same instant as the scored one is **not** a predecessor. That is the
    convention every window here uses and the only one the online path can implement — at scoring
    time a transaction stamped the same instant may not have arrived — so making the geographic
    group the exception would put the two paths' divergence precisely on simultaneous
    transactions, which is itself a fraud pattern.

    The cost is that when the *only* prior transaction shares the timestamp, three features are
    NaN rather than reporting a journey. That is the honest output: a capped speed computed from a
    zero gap is a number with no journey behind it. It is a silence, so it is asserted here and
    named in `registry.SIMULTANEOUS_PREDECESSOR_NOTE` and in the datasheet, rather than left to be
    discovered.
    """
    simultaneous = Transaction(
        transaction_id="same-instant",
        account_id="A",
        timestamp=SCORED.timestamp,
        amount_rwf=1.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        counterparty_id="M1",
    )
    assert simultaneous.timestamp == SCORED.timestamp, "precondition: the stamps are identical"
    assert simultaneous.transaction_id != SCORED.transaction_id

    history = [simultaneous]
    assert math.isnan(batch.distance_from_last_tx_km(history, SCORED))
    assert math.isnan(batch.implied_speed_kmh(history, SCORED))
    assert math.isnan(batch.seconds_since_last_tx(history, SCORED))

    features = warm(history)
    assert math.isnan(features.distance_from_last_tx_km(SCORED))
    assert math.isnan(features.implied_speed_kmh(SCORED))
    assert math.isnan(features.seconds_since_last_tx(SCORED))


@pytest.mark.req("FR-02-02")
def test_one_microsecond_earlier_is_a_predecessor_and_saturates() -> None:
    """The boundary on the other side: a row one microsecond earlier **is** previous.

    754.9 km in a microsecond is about 2.7e12 km/h, so the cap is what the feature reports. This
    is the case the old zero-elapsed guard was reaching for, and it needs no guard: the gap is
    positive, the division is defined, and the cap does the work.
    """
    a_moment_before = [at(1.0 / 3_600_000_000.0, KIGALI, tid="micro")]
    gap = batch.seconds_since_last_tx(a_moment_before, SCORED)
    assert gap > 0.0, "precondition: the row is strictly earlier, so it is a predecessor"
    assert gap < 1e-3, "precondition: and the gap is tiny, so the uncapped speed is absurd"
    assert batch.implied_speed_kmh(a_moment_before, SCORED) == batch.MAX_IMPLIED_SPEED_KMH
    assert warm(a_moment_before).implied_speed_kmh(SCORED) == batch.MAX_IMPLIED_SPEED_KMH


@pytest.mark.req("FR-02-02")
def test_what_the_cap_means_when_it_binds() -> None:
    """At the cap the feature is saturated, not measured, and the tests say which is which.

    1000 km/h is above commercial cruising speed, so a value at the cap says one person cannot
    have been in both places — a proxy for a shared account, a credential used elsewhere or a
    spoofed location. It saturates rather than reporting 3,000 or 2.7e12 so that nothing can split
    *inside* the impossible range and learn a distinction with no meaning.

    Asserted as a property of the function: every gap short enough to be impossible gives the same
    number, and gaps long enough to be a flight give different ones.
    """
    impossible = [
        batch.implied_speed_kmh([at(hours, KIGALI, tid=f"i{hours}")], SCORED)
        for hours in (0.01, 0.1, 0.5, 0.7)
    ]
    assert set(impossible) == {batch.MAX_IMPLIED_SPEED_KMH}, (
        f"the cap did not saturate: {impossible}"
    )
    plausible = [
        batch.implied_speed_kmh([at(hours, KIGALI, tid=f"p{hours}")], SCORED)
        for hours in (1.0, 2.0, 8.0)
    ]
    assert len(set(plausible)) == 3, "below the cap the feature must still be a measurement"
    assert all(value < batch.MAX_IMPLIED_SPEED_KMH for value in plausible)


@pytest.mark.req("FR-02-02", "D-04")
def test_the_speed_is_nan_without_a_previous_transaction() -> None:
    assert math.isnan(batch.implied_speed_kmh([], SCORED))
    assert math.isnan(OnlineFeatures().implied_speed_kmh(SCORED))


@pytest.mark.req("FR-02-02")
def test_the_speed_is_not_composed_from_the_other_two_features() -> None:
    """Composing the distance and the gap would inherit two NaN conventions and lose the cap.

    At a microsecond gap the composition is about 2.7e12 km/h while the feature reports 1,000 —
    the saturation is a property of this feature and not of its parts, so a path that divided one
    feature by another would agree everywhere except where the cap does its work.
    """
    a_moment_before = [at(1.0 / 3_600_000_000.0, KIGALI, tid="micro")]
    distance = batch.distance_from_last_tx_km(a_moment_before, SCORED)
    gap = batch.seconds_since_last_tx(a_moment_before, SCORED)
    composed = distance / (gap / 3_600.0)
    assert composed > 1e9, "precondition: the composition is far above the cap"
    assert batch.implied_speed_kmh(a_moment_before, SCORED) == batch.MAX_IMPLIED_SPEED_KMH
    assert composed != batch.implied_speed_kmh(a_moment_before, SCORED)


# --- is_new_country_for_account -------------------------------------------------------------------


def sending_to(country: str, *, hours_before: float = 0.0, tid: str = "t") -> Transaction:
    return Transaction(
        transaction_id=tid,
        account_id="A",
        timestamp=T - timedelta(hours=hours_before),
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        counterparty_id="M1",
        counterparty_country=country,
    )


@pytest.mark.req("FR-02-02", "D-03")
def test_a_country_is_new_until_the_account_has_sent_to_it() -> None:
    """The counterparty's country (owner decision 2026-09-19), over unbounded history.

    Invented codes: the feature compares two values that came out of the data and names no
    country, so a test that used real ones would suggest otherwise.
    """
    history = [
        sending_to("AA", hours_before=100.0, tid="h1"),
        sending_to("BB", hours_before=50.0, tid="h2"),
    ]
    assert batch.is_new_country_for_account(history, sending_to("CC")) is True
    assert batch.is_new_country_for_account(history, sending_to("AA")) is False
    assert batch.is_new_country_for_account(history, sending_to("BB")) is False

    features = warm(history)
    assert features.is_new_country_for_account(sending_to("CC")) is True
    assert features.is_new_country_for_account(sending_to("AA")) is False


@pytest.mark.req("FR-02-02")
def test_an_accounts_first_transaction_is_always_to_a_new_country() -> None:
    """`nan_rule`: never NaN. No history means a new country by definition, which is True."""
    assert batch.is_new_country_for_account([], sending_to("AA")) is True
    assert OnlineFeatures().is_new_country_for_account(sending_to("AA")) is True


@pytest.mark.req("FR-02-02")
def test_the_history_is_unbounded_and_reaches_past_the_rolling_window() -> None:
    """A corridor used once two years ago is not new.

    A windowed version would report every dormant corridor as new again, on exactly the accounts
    whose behaviour has not changed. 800 days is well past the 90 d account horizon, so the
    online path can only answer this from durable state.
    """
    long_ago = [sending_to("AA", hours_before=800 * 24.0, tid="ancient")]
    scored = sending_to("AA")
    assert batch.is_new_country_for_account(long_ago, scored) is False
    features = warm(long_ago)
    assert not features._in_window(scored, "amount_zscore_90d"), (
        "precondition: the arrival has left the rolling window, so the answer is durable state"
    )
    assert features.is_new_country_for_account(scored) is False


@pytest.mark.req("FR-02-02", "D-04")
def test_a_flush_makes_every_corridor_look_new_until_restored() -> None:
    """`history_requirement=DURABLE`. This is PB-37's failure shape in a different field.

    After a flush the set of countries is empty, so the feature fires on the entire established
    account base at once — during a recovery, when a surge of novelty signals is least likely to
    be read as a cache problem. Asserted so that `restore_countries` is not decorative.
    """
    history = [sending_to("AA", hours_before=100.0, tid="h1")]
    features = warm(history)
    assert features.is_new_country_for_account(sending_to("AA")) is False

    features.flush_cache()
    assert features.is_new_country_for_account(sending_to("AA")) is True, (
        "a flush must lose the country set; if it does not, the value is being rebuilt from "
        "something the cache should not have kept"
    )

    features.restore_countries("A", {"AA"})
    assert features.is_new_country_for_account(sending_to("AA")) is False


@pytest.mark.req("FR-02-02")
def test_a_row_without_a_counterparty_country_is_refused() -> None:
    """Rows without one would share a destination, so the second would read as familiar."""
    scored = at(0.0, KIGALI, tid="no-country")
    assert scored.counterparty_country is None, "precondition: the row carries no country"
    with pytest.raises(ValueError, match="counterparty_country"):
        batch.is_new_country_for_account([], scored)
    with pytest.raises(ValueError, match="counterparty_country"):
        OnlineFeatures().is_new_country_for_account(scored)


# --- distance_from_home_centroid_km ---------------------------------------------------------------


#: Four transactions near Kigali and one in Nairobi. The component-wise median stays in Kigali; a
#: mean would be dragged about 135 km east, into the country between them.
_HOME_ROWS = [
    at(24.0, (-1.9441, 30.0619), tid="h1"),
    at(48.0, (-1.9450, 30.0625), tid="h2"),
    at(72.0, (-1.9430, 30.0610), tid="h3"),
    at(96.0, (-1.9460, 30.0630), tid="h4"),
    at(120.0, NAIROBI, tid="away"),
]


@pytest.mark.req("FR-02-02")
def test_the_home_centroid_is_the_component_wise_median() -> None:
    """Five prior locations: the median latitude and longitude are the middle values of each.

    Sorted latitudes are -1.9460, -1.9450, -1.9441, -1.9430, -1.2921, so the median is -1.9441;
    sorted longitudes are 30.0610, 30.0619, 30.0625, 30.0630, 36.8219, so the median is 30.0625.
    A transaction at that exact point is 0.05 km from home rather than 135 km.
    """
    scored = at(0.0, (-1.9441, 30.0625), tid="scored")
    assert batch.distance_from_home_centroid_km(_HOME_ROWS, scored) == pytest.approx(0.0, abs=1e-9)
    assert warm(_HOME_ROWS).distance_from_home_centroid_km(scored) == pytest.approx(0.0, abs=1e-9)


@pytest.mark.req("FR-02-02")
def test_one_trip_abroad_does_not_move_home() -> None:
    """The property "median, not mean" exists for, measured rather than described.

    The mean longitude of the fixture is about 31.42 — roughly 150 km east of Kigali — so a mean
    centroid would report every subsequent transaction at home as that far away. The precondition
    asserts the mean really is displaced, or this would pass under either implementation.
    """
    mean_lat = sum(row.latitude for row in _HOME_ROWS) / len(_HOME_ROWS)
    mean_lon = sum(row.longitude for row in _HOME_ROWS) / len(_HOME_ROWS)
    displacement = haversine_km(mean_lat, mean_lon, -1.9441, 30.0619)
    assert displacement > 100.0, (
        f"precondition: the mean centroid is only {displacement:.1f} km from Kigali, so this "
        "fixture cannot tell a median from a mean"
    )

    home = at(0.0, KIGALI, tid="home")
    assert batch.distance_from_home_centroid_km(_HOME_ROWS, home) < 1.0
    assert warm(_HOME_ROWS).distance_from_home_centroid_km(home) < 1.0


@pytest.mark.req("FR-02-02")
def test_a_transaction_far_from_home_reports_the_distance() -> None:
    """The feature must move: a centroid test that only ever sees rows at home proves nothing."""
    away = at(0.0, NAIROBI, tid="away-now")
    distance = batch.distance_from_home_centroid_km(_HOME_ROWS, away)
    assert distance == pytest.approx(754.9, abs=1.0)
    assert warm(_HOME_ROWS).distance_from_home_centroid_km(away) == pytest.approx(
        distance, rel=1e-12
    )


@pytest.mark.req("FR-02-02", "D-04")
def test_the_centroid_is_nan_with_no_prior_locations() -> None:
    """`minimum_history=1`: there is no home yet, which 0.0 would misreport as "at home"."""
    assert math.isnan(batch.distance_from_home_centroid_km([], SCORED))
    assert math.isnan(OnlineFeatures().distance_from_home_centroid_km(SCORED))


@pytest.mark.req("FR-02-02")
def test_the_centroid_window_is_ninety_days_and_the_edge_is_excluded() -> None:
    """A location exactly 90 days back is outside the window, one an hour inside it is not.

    Same open interval as every other trailing window in the registry, asserted here because this
    is the only 90 d feature whose value changes visibly when a single row enters or leaves.
    """
    edge = at(90 * 24.0, NAIROBI, tid="edge")
    inside = at(90 * 24.0 - 1.0, NAIROBI, tid="inside")
    scored = at(0.0, KIGALI, tid="scored")
    assert math.isnan(batch.distance_from_home_centroid_km([edge], scored)), (
        "the row exactly on the 90 d edge was counted"
    )
    assert batch.distance_from_home_centroid_km([inside], scored) == pytest.approx(754.9, abs=1.0)
    assert math.isnan(warm([edge]).distance_from_home_centroid_km(scored))


# --- the durable state these share with the temporal group ------------------------------------


@pytest.mark.req("FR-02-02", "D-04")
def test_a_flush_loses_the_previous_location_until_it_is_restored() -> None:
    """`history_requirement=DURABLE` for the pair that reads the previous transaction.

    Both features go NaN across a flush rather than measuring from the first arrival afterwards,
    which would report a short hop for an account that had crossed a border — in the direction
    that reads as normal. Restoring the durable record restores both, which is what makes
    `restore_last_transaction` carry a location and not only a time.
    """
    previous = at(3.0, KIGALI, tid="previous")
    features = warm([previous])
    warm_distance = features.distance_from_last_tx_km(SCORED)
    warm_speed = features.implied_speed_kmh(SCORED)
    assert warm_distance > 700.0, "precondition: the warm value is a real distance"

    features.flush_cache()
    assert math.isnan(features.distance_from_last_tx_km(SCORED))
    assert math.isnan(features.implied_speed_kmh(SCORED))

    features.restore_last_transaction(
        "A", previous.timestamp, previous.latitude, previous.longitude
    )
    assert features.distance_from_last_tx_km(SCORED) == warm_distance
    assert features.implied_speed_kmh(SCORED) == warm_speed
