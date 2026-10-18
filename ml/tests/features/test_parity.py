"""Prefix replay and the cold-cache case (parity Decisions 2, 3 and 6).

Handing both paths a completed history proves they agree on a situation the online path never
encounters, and cannot detect a batch window that reaches forward in time — both paths would see
the same future rows. So the online path is fed arrivals one at a time and snapshotted, and the
batch path is computed on **the prefix ending at that transaction only**.
"""

from __future__ import annotations

import itertools
import math
import random
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.primitives import h3_cell, haversine_km
from fraudshield_ml.features.types import (
    IdentityEvidence,
    LimitDimension,
    OperationalLimit,
    Outcome,
    Transaction,
)

START = datetime(2025, 1, 1, tzinfo=UTC)
TOLERANCE_ABS = 1e-12
TOLERANCE_REL = 1e-12


def _agree(batch_value: float, online_value: float) -> bool:
    return abs(batch_value - online_value) <= TOLERANCE_ABS + TOLERANCE_REL * abs(batch_value)


def _history(n: int = 60, seed: int = 20260919) -> list[Transaction]:
    """An account's arrivals, deliberately uneven so windows open and close mid-replay."""
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    rows: list[Transaction] = []
    when = START
    for i in range(n):
        when += timedelta(minutes=rng.choice([3, 17, 90, 400, 1500, 5000]))
        rows.append(
            Transaction(
                transaction_id=f"t{i}",
                account_id="A",
                timestamp=when,
                amount_rwf=float(rng.randrange(1000, 500000)),
                latitude=-1.9441 + rng.uniform(-0.2, 0.2),
                longitude=30.0619 + rng.uniform(-0.2, 0.2),
            )
        )
    return rows


@pytest.mark.req("FR-02-02")
def test_prefix_replay_agrees_at_every_step() -> None:
    """The core parity assertion, at every k rather than once at the end."""
    rows = _history()
    first_seen = rows[0].timestamp

    spans_a_window_boundary = any(
        (b.timestamp - a.timestamp) > timedelta(hours=1) for a, b in itertools.pairwise(rows)
    )
    assert spans_a_window_boundary, (
        "precondition: the replay must cross the 1 h boundary, or the short window never empties "
        "and the test cannot detect a bound error (E12)"
    )

    online = OnlineFeatures()
    online.restore_first_seen("A", first_seen)
    checked = 0
    for k, scored in enumerate(rows):
        online_value = online.velocity_ratio_1h_vs_30d(scored)
        batch_value = batch.velocity_ratio_1h_vs_30d(rows[:k], scored, first_seen_at=first_seen)
        assert _agree(batch_value, online_value), (
            f"prefix {k} ({scored.transaction_id}): "
            f"batch {batch_value!r} vs online {online_value!r}"
        )
        online.observe(scored)
        checked += 1
    assert checked == len(rows)


@pytest.mark.req("FR-02-02")
def test_the_replay_is_not_trivially_constant() -> None:
    """A replay where every value is 1.0 would pass the test above while proving nothing.

    This is the same failure E12 was written for: the assertion holds over a fixture that never
    exercises the thing being asserted.
    """
    rows = _history()
    online = OnlineFeatures()
    online.restore_first_seen("A", rows[0].timestamp)
    values = []
    for scored in rows:
        values.append(online.velocity_ratio_1h_vs_30d(scored))
        online.observe(scored)
    assert len(set(values)) > 5, f"the replay produced only {len(set(values))} distinct values"
    assert max(values) > 1.5, "no burst in the fixture: the numerator never dominates"


@pytest.mark.req("FR-02-02")
def test_a_cache_flush_without_restoring_durable_state_changes_the_feature() -> None:
    """Decision 6's cold-cache case, and the only test that exercises `DURABLE`.

    `velocity_ratio_1h_vs_30d` declares `history_basis=OBSERVED_CAPPED`, which divides by history
    actually observed and therefore needs a first-seen timestamp. A flush destroys it. If the
    online path silently re-derives first-seen from its next arrival, the denominator shrinks and
    the ratio inflates for every established account at once — invisible on every warm-path test.

    This asserts the damage is real, so that `restore_first_seen` is not decorative.
    """
    rows = _history()
    first_seen = rows[0].timestamp
    scored = rows[-1]

    warm = OnlineFeatures()
    warm.restore_first_seen("A", first_seen)
    for row in rows[:-1]:
        warm.observe(row)
    warm_value = warm.velocity_ratio_1h_vs_30d(scored)

    cold = OnlineFeatures()
    cold.restore_first_seen("A", first_seen)
    for row in rows[:-1]:
        cold.observe(row)
    cold.flush_cache()
    for row in rows[-4:-1]:  # only recent arrivals return after a flush
        cold.observe(row)
    unrestored = cold.velocity_ratio_1h_vs_30d(scored)

    assert not _agree(warm_value, unrestored), (
        "a flush that loses the durable first-seen must change the feature; if it does not, "
        "either the fixture has too little history or OBSERVED_CAPPED is not being applied"
    )

    cold.restore_first_seen("A", first_seen)
    restored = cold.velocity_ratio_1h_vs_30d(scored)
    assert restored != unrestored, "restoring the durable field must undo the flush's effect"


@pytest.mark.req("FR-02-02")
def test_the_shared_primitives_against_hand_computed_values() -> None:
    """The parity test cannot see into these, so they are covered only here.

    Kigali (-1.9441, 30.0619) to Nairobi (-1.2921, 36.8219) is 754.9 km great-circle on a sphere
    of radius 6371.0088 km. A point and itself is exactly 0.
    """
    assert haversine_km(-1.9441, 30.0619, -1.2921, 36.8219) == pytest.approx(754.9, abs=0.1)
    assert haversine_km(-1.9441, 30.0619, -1.9441, 30.0619) == 0.0
    assert haversine_km(0.0, 0.0, 0.0, 1.0) == pytest.approx(111.195, abs=0.01)

    # H3 resolution 6 averages ~36 km^2, so two points ~100 m apart share a cell and Kigali and
    # Nairobi do not.
    assert h3_cell(-1.9441, 30.0619) == h3_cell(-1.9450, 30.0625)
    assert h3_cell(-1.9441, 30.0619) != h3_cell(-1.2921, 36.8219)


#: Three tight clusters, so several accounts transact inside the same H3 resolution-6 cell.
#: Jitter is +/-0.004 deg (~450 m) against a cell edge of ~3.2 km, which makes sharing the rule
#: rather than an accident of rounding.
_CLUSTERS = ((-1.9441, 30.0619), (-1.9500, 30.0700), (-1.2921, 36.8219))


def _multi_account_history(
    accounts: int = 6, per_account: int = 12, seed: int = 20260919
) -> list[Transaction]:
    """Arrivals from several accounts, interleaved in time and sharing cells.

    A single-account replay cannot exercise a cross-account aggregate at all: every row it sees
    belongs to the same account, so a cell rate keyed by the cell is indistinguishable from one
    keyed by the account. `geo_cell_fraud_rate_30d` is the feature whose leakage note was already
    wrong once for exactly this reason, so its fixture has to contain the thing that went wrong.
    """
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    rows: list[Transaction] = []
    for a in range(accounts):
        when = START + timedelta(hours=rng.randrange(0, 48))
        centre = _CLUSTERS[a % len(_CLUSTERS)]
        for i in range(per_account):
            when += timedelta(minutes=rng.choice([45, 200, 900, 3000]))
            rows.append(
                Transaction(
                    transaction_id=f"a{a}t{i}",
                    account_id=f"A{a}",
                    timestamp=when,
                    amount_rwf=float(rng.randrange(1000, 500000)),
                    latitude=centre[0] + rng.uniform(-0.004, 0.004),
                    longitude=centre[1] + rng.uniform(-0.004, 0.004),
                )
            )
    rows.sort(key=lambda r: r.timestamp)
    return rows


@pytest.mark.req("FR-02-02")
def test_the_cell_rate_agrees_across_paths_under_multi_account_prefix_replay() -> None:
    """Prefix replay for the label-derived, cell-keyed feature, across accounts.

    The batch path can see the future and the whole corpus; the online path can see neither. Both
    must read the same value at every prefix, with labels gated on `available_at`.
    """
    rows = _multi_account_history()
    outcomes = {
        row.transaction_id: Outcome(
            transaction_id=row.transaction_id,
            is_fraud=(i % 7 == 0),
            available_at=row.timestamp + timedelta(days=3),
        )
        for i, row in enumerate(rows)
    }

    # E12: the preconditions this test depends on, asserted before the assertion they exist for.
    accounts_by_cell: dict[str, set[str]] = {}
    for row in rows:
        accounts_by_cell.setdefault(h3_cell(row.latitude, row.longitude), set()).add(row.account_id)
    shared = {cell: who for cell, who in accounts_by_cell.items() if len(who) > 1}
    assert shared, (
        "precondition: no H3 cell in the fixture is used by more than one account, so this replay "
        "would not exercise a cross-account aggregate at all"
    )
    assert max(len(who) for who in shared.values()) >= 2
    assert len({r.account_id for r in rows}) >= 3, "precondition: several accounts"
    assert any(o.is_fraud for o in outcomes.values()), "precondition: the fixture contains fraud"

    online = OnlineFeatures()
    for outcome in outcomes.values():
        online.observe_outcome(outcome)

    seen_nonzero = False
    for k, scored in enumerate(rows):
        online_value = online.geo_cell_fraud_rate_30d(scored, prior=0.0087)
        batch_value = batch.geo_cell_fraud_rate_30d(rows[:k], outcomes, scored, prior=0.0087)
        assert _agree(batch_value, online_value), (
            f"prefix {k} ({scored.transaction_id}, {scored.account_id}): "
            f"batch {batch_value!r} vs online {online_value!r}"
        )
        if batch_value > 0.0087:
            seen_nonzero = True
        online.observe(scored)

    assert seen_nonzero, (
        "every prefix returned the prior, so no confirmed fraud ever entered a cell rate and the "
        "replay proved nothing about the aggregate (E12/E13)"
    )


@pytest.mark.req("FR-02-02")
def test_the_cell_rate_reads_other_accounts_rows_which_is_why_it_needs_a_control() -> None:
    """The property that makes `history_key=GEO_CELL` necessary, asserted rather than assumed.

    If the cell rate were in fact account-scoped, E1's account-grouped folds would isolate it and
    `cross_account_control` would be unnecessary. This shows it is not: restricting the corpus to
    the scored account's own rows changes the value.
    """
    rows = _multi_account_history()
    outcomes = {
        row.transaction_id: Outcome(
            transaction_id=row.transaction_id,
            is_fraud=(i % 5 == 0),
            available_at=row.timestamp + timedelta(days=1),
        )
        for i, row in enumerate(rows)
    }
    scored = rows[-1]
    prior_rows = rows[:-1]
    own = [r for r in prior_rows if r.account_id == scored.account_id]
    assert own, "precondition: the scored account has prior rows of its own"
    assert len(own) < len(prior_rows), "precondition: other accounts also have rows"

    across = batch.geo_cell_fraud_rate_30d(prior_rows, outcomes, scored, prior=0.0087)
    own_only = batch.geo_cell_fraud_rate_30d(own, outcomes, scored, prior=0.0087)
    assert across != own_only, (
        "the cell rate is unchanged by other accounts' rows, so either the fixture's cells are not "
        "shared or the aggregate is not cell-keyed; in both cases history_key=GEO_CELL is wrong"
    )


#: A replay for the trailing account aggregates. Separate from `_history` because those features
#: need amounts that vary over orders of magnitude and counterparties that repeat: a replay where
#: every amount is equal cannot distinguish a sum from a count times a constant, and one where
#: every counterparty is distinct cannot distinguish a set from a row count.
def _velocity_history(n: int = 80, seed: int = 20260919) -> list[Transaction]:
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    rows: list[Transaction] = []
    when = START
    for i in range(n):
        when += timedelta(seconds=rng.choice([20, 45, 900, 5_000, 40_000, 200_000, 900_000]))
        rows.append(
            Transaction(
                transaction_id=f"v{i}",
                account_id="A",
                timestamp=when,
                amount_rwf=float(rng.choice([250, 1_500, 48_000, 1_200_000, 9_800_000])),
                latitude=-1.9441,
                longitude=30.0619,
                counterparty_id=f"M{rng.randrange(6)}",
            )
        )
    return rows


@pytest.mark.req("FR-02-02")
def test_the_trailing_aggregates_agree_at_every_prefix() -> None:
    """Prefix replay for the seven trailing account aggregates, counts exactly and sums by ADR 0025.

    The counts and the distinct count are compared with `==` rather than `_agree`: ADR 0025 allows
    a count no tolerance, and reusing the float comparison here would quietly grant one.
    """
    rows = _velocity_history()
    online = OnlineFeatures()

    spans = [b.timestamp - a.timestamp for a, b in itertools.pairwise(rows)]
    assert any(s < timedelta(seconds=60) for s in spans), (
        "precondition: the replay must put two rows inside 60 s, or tx_count_60s is 0 throughout "
        "and proves nothing (E12/E13)"
    )
    assert any(s > timedelta(days=7) for s in spans), (
        "precondition: the replay must leave a gap longer than 7 d, or no window ever empties"
    )

    seen_counts: set[int] = set()
    seen_sums: set[float] = set()
    seen_parties: set[int] = set()
    for k, scored in enumerate(rows):
        prefix = rows[:k]
        for name in ("tx_count_60s", "tx_count_1h", "tx_count_24h", "tx_count_7d"):
            expected = batch.tx_count(prefix, scored, name)
            assert online.tx_count(scored, name) == expected, f"prefix {k}, {name}"
            if name == "tx_count_24h":
                seen_counts.add(expected)
        for name in ("amount_sum_24h", "amount_sum_7d"):
            expected_sum = batch.amount_sum(prefix, scored, name)
            assert _agree(expected_sum, online.amount_sum(scored, name)), f"prefix {k}, {name}"
            if name == "amount_sum_7d":
                seen_sums.add(expected_sum)
        parties = batch.unique_counterparties_24h(prefix, scored)
        assert online.unique_counterparties_24h(scored) == parties, f"prefix {k}, counterparties"
        seen_parties.add(parties)
        online.observe(scored)

    # E13: the replay must have exercised each feature non-trivially, not merely agreed on zeros.
    assert len(seen_counts) > 3, f"tx_count_24h took only {sorted(seen_counts)} across the replay"
    assert max(seen_sums) > 1e6, "no large sum in the replay, so the tolerance was never stretched"
    assert len(seen_parties) > 2, "the distinct-counterparty count barely moved"


def _agree_including_nan(batch_value: float, online_value: float) -> bool:
    """ADR 0025 rule 3: NaN positions must match exactly, never absorbed by a tolerance.

    A NaN on one path against a number on the other is a contract violation rather than a
    numerical difference, so the two cases are separated here rather than run through `_agree`,
    where `nan != nan` would make every comparison fail for the wrong reason.
    """
    if math.isnan(batch_value) or math.isnan(online_value):
        return math.isnan(batch_value) and math.isnan(online_value)
    return _agree(batch_value, online_value)


#: A configuration change inside the replay, which ADR 0026 makes a **required fixture property**
#: rather than a case in a list: the two paths agree for every transaction newer than the last
#: change, so a fixture without one passes with the as-of bug present.
_LIMIT_CHANGE_AT = START + timedelta(days=40)
_REPLAY_LIMITS = (
    OperationalLimit(
        dimension=LimitDimension.CHANNEL,
        applies_to="MOBILE_MONEY",
        amount_rwf=60_000.0,
        effective_at=START - timedelta(days=1),
    ),
    OperationalLimit(
        dimension=LimitDimension.CHANNEL,
        applies_to="MOBILE_MONEY",
        amount_rwf=1_300_000.0,
        effective_at=_LIMIT_CHANGE_AT,
    ),
)

#: Denominations in minor units, as a pack supplies them; an invented currency, since the feature
#: paths must never name one.
_REPLAY_DENOMINATIONS = {"AAA": (100, 1_000)}


def _amount_history(n: int = 70, seed: int = 20260919) -> list[Transaction]:
    """Arrivals carrying everything the amount features read.

    Amounts span four orders of magnitude so the z-score has a scale to find and the ratio to the
    maximum moves; some are exact multiples of a denomination and some are not.
    """
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    rows: list[Transaction] = []
    when = START
    for i in range(n):
        when += timedelta(hours=rng.choice([2, 9, 40, 170, 700]))
        minor = rng.choice([57_400, 60_000, 1_000, 12_345, 250_000, 999])
        rows.append(
            Transaction(
                transaction_id=f"m{i}",
                account_id="A",
                timestamp=when,
                amount_rwf=float(minor),
                latitude=-1.9441,
                longitude=30.0619,
                counterparty_id=f"M{rng.randrange(4)}",
                amount_minor=minor,
                currency="AAA",
                channel="MOBILE_MONEY",
            )
        )
    return rows


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_amount_features_agree_at_every_prefix_across_a_configuration_change() -> None:
    """Prefix replay for the five amount features, with ADR 0026's required fixture property.

    The preconditions come first and there are three, because this replay can be vacuous in three
    separate ways: no configuration change (the as-of bug becomes invisible), no thin-history
    prefix (the NaN contract is never exercised), and no round amount (the flag is constant).
    """
    rows = _amount_history()
    changes = sum(
        1 for limit in _REPLAY_LIMITS if rows[0].timestamp < limit.effective_at < rows[-1].timestamp
    )
    assert changes > 0, (
        "precondition (ADR 0026): the replayed history must span at least one configuration "
        "change, or the two paths agree for every row and the suite is blind to as-of drift"
    )
    assert sum(1 for r in rows if r.timestamp < _LIMIT_CHANGE_AT) > 5, (
        "precondition: and enough rows fall on the earlier side of it to matter"
    )
    assert any(r.amount_minor is not None and r.amount_minor % 1_000 == 0 for r in rows), (
        "precondition: some amount is an exact multiple of a denomination"
    )

    online = OnlineFeatures()
    flags: set[bool] = set()
    nan_prefixes = 0
    for k, scored in enumerate(rows):
        prefix = rows[:k]
        pairs = (
            (batch.amount_log1p(scored), online.amount_log1p(scored)),
            (
                batch.amount_zscore_90d(prefix, scored),
                online.amount_zscore_90d(scored),
            ),
            (
                batch.amount_to_max_90d_ratio(prefix, scored),
                online.amount_to_max_90d_ratio(scored),
            ),
        )
        for batch_value, online_value in pairs:
            assert _agree_including_nan(batch_value, online_value), (
                f"prefix {k} ({scored.transaction_id}): {batch_value!r} vs {online_value!r}"
            )
        if math.isnan(batch.amount_zscore_90d(prefix, scored)):
            nan_prefixes += 1

        assert batch.round_sum_flag(scored, _REPLAY_DENOMINATIONS) == online.round_sum_flag(
            scored, _REPLAY_DENOMINATIONS
        ), f"prefix {k}: round_sum_flag"
        below = batch.just_below_limit_flag(scored, _REPLAY_LIMITS, kyc_tier=None)
        assert below == online.just_below_limit_flag(scored, _REPLAY_LIMITS, kyc_tier=None), (
            f"prefix {k}: just_below_limit_flag"
        )
        flags.add(below)
        online.observe(scored)

    assert nan_prefixes > 0, (
        "no prefix was below the z-score's five-observation threshold, so the NaN contract was "
        "never exercised (E13)"
    )
    assert flags == {True, False}, (
        "just_below_limit_flag was constant across the replay, so parity on it proved nothing"
    )


def _journey_history(n: int = 60, seed: int = 20260920) -> list[Transaction]:
    """Arrivals that move between three cities and four destination countries.

    A replay that never leaves one place makes every distance 0 and every country familiar after
    the first row, so the geographic features would agree on constants.
    """
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    places = ((-1.9441, 30.0619), (-1.2921, 36.8219), (0.3476, 32.5825))
    destinations = ("AA", "BB", "CC", "DD")
    rows: list[Transaction] = []
    when = START
    for i in range(n):
        when += timedelta(minutes=rng.choice([7, 55, 300, 2_000, 30_000]))
        place = places[rng.randrange(len(places))]
        rows.append(
            Transaction(
                transaction_id=f"g{i}",
                account_id="A",
                timestamp=when,
                amount_rwf=float(rng.randrange(1_000, 900_000)),
                latitude=place[0] + rng.uniform(-0.01, 0.01),
                longitude=place[1] + rng.uniform(-0.01, 0.01),
                counterparty_id=f"M{rng.randrange(5)}",
                counterparty_country=destinations[rng.randrange(len(destinations))],
            )
        )
    return rows


@pytest.mark.req("FR-02-02")
def test_the_geographic_and_gap_features_agree_at_every_prefix() -> None:
    """Prefix replay for the four features that read the previous transaction or the 90 d window.

    Three preconditions, because this replay can be vacuous in three ways: never moving (every
    distance 0), never saturating the speed cap (the cap untested), and never revisiting a country
    (the novelty flag constant True).
    """
    rows = _journey_history()
    online = OnlineFeatures()
    online.restore_first_seen("A", rows[0].timestamp)

    distances: set[float] = set()
    speeds: set[float] = set()
    novelty: set[bool] = set()
    nan_gaps = 0
    for k, scored in enumerate(rows):
        prefix = rows[:k]
        for batch_value, online_value in (
            (
                batch.seconds_since_last_tx(prefix, scored),
                online.seconds_since_last_tx(scored),
            ),
            (
                batch.distance_from_last_tx_km(prefix, scored),
                online.distance_from_last_tx_km(scored),
            ),
            (batch.implied_speed_kmh(prefix, scored), online.implied_speed_kmh(scored)),
            (
                batch.distance_from_home_centroid_km(prefix, scored),
                online.distance_from_home_centroid_km(scored),
            ),
        ):
            assert _agree_including_nan(batch_value, online_value), (
                f"prefix {k} ({scored.transaction_id}): {batch_value!r} vs {online_value!r}"
            )
        new_country = batch.is_new_country_for_account(prefix, scored)
        assert new_country == online.is_new_country_for_account(scored), f"prefix {k}: country"

        distance = batch.distance_from_last_tx_km(prefix, scored)
        if not math.isnan(distance):
            distances.add(round(distance, 3))
            speeds.add(batch.implied_speed_kmh(prefix, scored))
        else:
            nan_gaps += 1
        novelty.add(new_country)
        online.observe(scored)

    assert nan_gaps == 1, f"only the first row should have no predecessor, got {nan_gaps}"
    assert len(distances) > 5, "the replay barely moved, so the distances proved nothing"
    assert max(speeds) == batch.MAX_IMPLIED_SPEED_KMH, (
        "no prefix saturated the speed cap, so the cap is untested by this replay"
    )
    assert novelty == {True, False}, "every country was new, so the novelty flag was constant"


def _mixed_history(n: int = 70, seed: int = 20260921) -> list[Transaction]:
    """Arrivals from several accounts through agents, devices and counterparties.

    One replay for the four remaining groups, because they share a store and a bug in eviction or
    in the arrival record would show up in all of them at once. Several accounts, because three of
    the features are keyed by something other than the account and a single-account replay cannot
    exercise a cross-account aggregate at all.
    """
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    rows: list[Transaction] = []
    when = START
    for i in range(n):
        when += timedelta(minutes=rng.choice([4, 25, 200, 1_500, 20_000]))
        at_agent = rng.random() < 0.4
        on_ussd = not at_agent and rng.random() < 0.25
        rows.append(
            Transaction(
                transaction_id=f"x{i}",
                account_id=f"A{rng.randrange(4)}",
                timestamp=when,
                amount_rwf=float(rng.randrange(1_000, 400_000)),
                latitude=-1.9441,
                longitude=30.0619,
                counterparty_id=f"C{rng.randrange(5)}",
                counterparty_country=("AA", "BB", "CC")[rng.randrange(3)],
                channel="AGENT_BANKING" if at_agent else ("USSD" if on_ussd else "MOBILE_MONEY"),
                device_fingerprint=None if on_ussd else f"D{rng.randrange(3)}",
                agent_id=f"AG{rng.randrange(2)}" if at_agent else None,
                merchant_category_code="6011" if at_agent else "5411",
            )
        )
    return rows


@pytest.mark.req("FR-02-02", "D-04")
def test_the_counterparty_device_agent_and_identity_features_agree_at_every_prefix() -> None:
    """Prefix replay for the last four groups, with NaN positions compared as positions.

    Counts and flags are compared exactly, reals under ADR 0025's relative rule, and the
    structurally-missing features by whether they are missing — which is a different question from
    whether two numbers are close, and is why `_agree_including_nan` exists.
    """
    rows = _mixed_history()
    cash_out = frozenset({"6011"})
    identity = IdentityEvidence(
        kyc_tier=2.0,
        kyc_tier_range=(1, 3),
        opened_at=START - timedelta(days=400),
        accounts_on_device=1.0,
    )

    # The three ways this replay could be vacuous, asserted before anything is compared (E12).
    assert any(row.device_fingerprint is None for row in rows), (
        "precondition: some row has no device, or the device NaN contract is never exercised"
    )
    assert any(row.agent_id is not None for row in rows), (
        "precondition: some row is at an agent, or the agent features are NaN throughout"
    )
    assert len({row.account_id for row in rows}) >= 3, (
        "precondition: several accounts, or the counterparty- and agent-keyed aggregates are "
        "indistinguishable from account-keyed ones"
    )

    online = OnlineFeatures()
    nan_devices = 0
    agent_values: set[float] = set()
    for k, scored in enumerate(rows):
        prefix = rows[:k]
        pairs = [
            (
                batch.accounts_per_device_7d(prefix, scored),
                online.accounts_per_device_7d(scored),
            ),
            (batch.device_changes_24h(prefix, scored), online.device_changes_24h(scored)),
            (
                batch.device_is_new_for_account(prefix, scored),
                online.device_is_new_for_account(scored),
            ),
            (
                batch.agent_cashout_count_1h(prefix, scored, cash_out),
                online.agent_cashout_count_1h(scored, cash_out),
            ),
            (
                batch.agent_unique_customers_1h(prefix, scored),
                online.agent_unique_customers_1h(scored),
            ),
            (
                batch.synthetic_identity_score(prefix, scored, identity),
                online.synthetic_identity_score(scored, identity),
            ),
        ]
        for batch_value, online_value in pairs:
            assert _agree_including_nan(batch_value, online_value), (
                f"prefix {k} ({scored.transaction_id}): {batch_value!r} vs {online_value!r}"
            )
        assert batch.counterparty_is_new_for_account(
            prefix, scored
        ) == online.counterparty_is_new_for_account(scored), f"prefix {k}: counterparty novelty"
        assert batch.counterparty_unique_senders_24h(
            prefix, scored
        ) == online.counterparty_unique_senders_24h(scored), f"prefix {k}: senders"
        assert batch.tx_count_to_counterparty_30d(
            prefix, scored
        ) == online.tx_count_to_counterparty_30d(scored), f"prefix {k}: pair count"
        assert batch.dormancy_reactivation_flag(
            prefix, scored
        ) == online.dormancy_reactivation_flag(scored), f"prefix {k}: dormancy"

        if math.isnan(batch.device_changes_24h(prefix, scored)):
            nan_devices += 1
        agent_values.add(batch.agent_unique_customers_1h(prefix, scored))
        online.observe(scored)

    assert nan_devices > 3, f"only {nan_devices} prefixes exercised the device NaN contract"
    assert len({v for v in agent_values if not math.isnan(v)}) > 1, (
        "the agent customer count was constant where it was defined, so parity proved nothing"
    )
