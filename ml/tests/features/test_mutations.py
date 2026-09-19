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

Categorical rows are asserted differently and deliberately so: ADR 0025 allows a categorical **no
tolerance at all**, so the detection criterion is inequality rather than a gap exceeding a bound.
Reusing `assert_detected` for them would silently reintroduce a tolerance where the ADR removed
one.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import numpy as np
import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.registry import categories_for, smoothing_for
from fraudshield_ml.features.types import (
    CountryFacts,
    LimitDimension,
    OperationalLimit,
    Outcome,
    Transaction,
)

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


def assert_detected_exactly(correct: str, mutated: str, mutation: str) -> None:
    """For a categorical, where ADR 0025 permits no tolerance: any difference at all is detected,
    and the thing worth asserting is that the mutation actually produced one."""
    assert correct != mutated, (
        f"mutation {mutation!r} left the category unchanged at {correct!r}. Exact equality cannot "
        "catch a divergence that does not occur, so this is the specification for a fixture that "
        "would make it occur, not evidence that the check works."
    )


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


# --- categorical rows: corridor_class ------------------------------------------------------------

#: Two packs sharing a bloc, and the same pair after one of them leaves it. Bloc membership is a
#: SOURCED pack fact with an accessed date, and memberships do change — Somalia joined the EAC in
#: 2024, Tanzania left COMESA — so "the packs the other path read" is not a hypothetical.
_PACKS_TODAY = {
    "RW": CountryFacts("RW", "AF", frozenset({"EAC", "COMESA"}), 2),
    "TZ": CountryFacts("TZ", "AF", frozenset({"EAC", "SADC"}), 3),
}
_PACKS_STALE = {
    "RW": CountryFacts("RW", "AF", frozenset({"COMESA"}), 2),
    "TZ": CountryFacts("TZ", "AF", frozenset({"SADC"}), 3),
}


def _corridor_tx(sender: str, recipient: str) -> Transaction:
    return Transaction(
        transaction_id="scored",
        account_id="A",
        timestamp=T,
        amount_rwf=1000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        account_country=sender,
        counterparty_country=recipient,
    )


@pytest.mark.req("FR-02-02", "D-03")
def test_mutation_6_a_category_computed_from_a_different_population_is_detected() -> None:
    """Row 6, in the form the suite can actually run today.

    The row's stated cause — "a category encoded from a different fold" — needs M4's target
    encoder, which does not exist; what it names structurally is a category whose value depends on
    **which reference population the path consulted**, and that is reachable now. One path reads
    the current packs and the other a revision in which the pair's shared bloc is absent, which is
    a real event rather than an invented one: memberships carry an accessed date precisely because
    they change.

    Detected by exact categorical equality, with no tolerance to absorb it.
    """
    scored = _corridor_tx("RW", "TZ")
    correct = batch.corridor_class(scored, _PACKS_TODAY)
    mutated = OnlineFeatures().corridor_class(scored, _PACKS_STALE)

    domestic, intra_bloc, cross_bloc, _ = categories_for("corridor_class")
    assert correct == intra_bloc, "precondition: the pair shares a bloc under the current packs"
    assert mutated == cross_bloc, "precondition: and shares none under the stale ones"
    assert correct != domestic
    assert_detected_exactly(correct, mutated, "category computed from a different pack revision")


@pytest.mark.req("FR-02-02", "D-03")
def test_mutation_13_testing_the_shared_bloc_before_the_same_country_is_detected() -> None:
    """Row 13, added when `corridor_class` was implemented (E14: recorded as it was tried).

    A country shares every one of its blocs with itself, so asking "do they share a bloc?" before
    "is it the same country?" reclassifies **every domestic transaction** as INTRA_BLOC. It is
    worth its own row because of where it lands: domestic rows are the overwhelming majority, so
    the mutation makes the feature nearly constant — and a nearly constant feature reads as a
    feature with no signal, which is a conclusion someone might accept rather than investigate.

    Detected by exact categorical equality on a domestic pair.
    """
    scored = _corridor_tx("RW", "RW")
    domestic, intra_bloc, _, _ = categories_for("corridor_class")

    sender = _PACKS_TODAY["RW"]
    assert sender.blocs, "precondition: the sender belongs to a bloc, or the mutation is inert"

    correct = batch.corridor_class(scored, _PACKS_TODAY)
    # The two tests swapped: the bloc intersection is non-empty for a country and itself.
    mutated = intra_bloc if sender.blocs & sender.blocs else domestic

    assert correct == domestic
    assert_detected_exactly(
        correct, mutated, "shared-bloc test placed before the same-country test"
    )


# --- amount-sum rows: the tolerance's two sides ------------------------------------------------

#: A heavy user's seven-day window: one large transfer and fifty-nine small ones. The mix of
#: magnitudes is what makes the order of accumulation matter at all — a window of equal amounts
#: reassociates exactly and would make row 1 pass for the wrong reason.
_HEAVY_AMOUNTS = [1.0e7] + [x * 0.1 + 1.0 / 3.0 for x in range(1, 60)]


def _heavy_window() -> list[Transaction]:
    return [
        Transaction(
            transaction_id=f"s{i}",
            account_id="A",
            timestamp=T - timedelta(hours=i + 1),
            amount_rwf=amount,
            latitude=KIGALI[0],
            longitude=KIGALI[1],
            counterparty_id="M1",
        )
        for i, amount in enumerate(_HEAVY_AMOUNTS)
    ]


@pytest.mark.req("FR-02-02")
def test_mutation_1_an_honest_reassociation_must_pass() -> None:
    """**The control, and the one row that must NOT be detected.**

    Without it the suite cannot distinguish "the tolerance catches bugs" from "the tolerance
    catches everything, including correct arithmetic" — and a tolerance that fails on correct code
    gets loosened under pressure until it catches nothing. Same role as M2's 0.5-strength plant in
    the power curve: a case that *should not* fire.

    **What running it revealed, recorded because it changes what the row means.** Both paths
    accumulate with the built-in `sum()`, and since CPython 3.12 `sum()` applies Neumaier
    compensated summation to floats. Both are therefore correctly rounded, agree bit-for-bit for
    the same multiset **whatever order they visit it in**, and the reassociation this row exists to
    permit does not currently occur between them at all. The first version of this test asserted
    that two orders differed and failed, because they do not.

    The row is not therefore vacuous, and must not be deleted as such. The comparison is against a
    **naive running total**, which is what the production online path will be: a Redis-backed store
    keeps an incrementally updated sum rather than re-adding a window on every request, and an
    incremental total cannot be compensated because it never sees the window twice. So the
    difference the tolerance must admit is real, it is simply not visible between today's two
    implementations — and it will appear at M6 without any change to these features.

    Asserted in both directions, so the row does not quietly become a test of one order.
    """
    rows = _heavy_window()
    compensated = batch.amount_sum(rows, SCORED, "amount_sum_7d")

    running_forward = 0.0
    for row in rows:
        running_forward += row.amount_rwf
    running_reverse = 0.0
    for row in reversed(rows):
        running_reverse += row.amount_rwf

    assert len({compensated, running_forward, running_reverse}) > 1, (
        "precondition: a naive running total must differ from the compensated sum in at least one "
        "order, or this control passes over arithmetic that could not have failed (E12)"
    )
    allowed = 1e-12 + 1e-12 * abs(compensated)
    for name, value in (("forward", running_forward), ("reverse", running_reverse)):
        gap = abs(compensated - value)
        assert gap <= allowed, (
            f"a naive running total accumulated {name} moved the sum by {gap!r}, outside the "
            f"tolerance {allowed!r}. ADR 0025's rule would fail on a correct online store, which "
            "is how a tolerance gets loosened until it catches nothing"
        )
    # The margin is not an accident of this fixture: at 1e7 the tolerance is ~1e-5 while one ulp is
    # ~1.9e-9, so a running total has three orders of magnitude of room before it binds.
    assert max(abs(compensated - running_forward), abs(compensated - running_reverse)) > 0.0
    assert allowed > 1e-6


@pytest.mark.req("FR-02-02")
def test_mutation_8_a_sum_accumulated_in_float32_is_detected() -> None:
    """Precision loss masquerading as reassociation, which is exactly why row 1 is not enough.

    float32 carries about seven significant decimal digits. At a heavy user's seven-day total the
    error is in the ones, not in the last bits — visibly a different number — yet a suite holding
    only row 1's reasoning ("the paths sum in different orders, so small differences are fine")
    would have no rule that separates the two. ADR 0025's relative tolerance does: it is three
    orders of magnitude stricter than the specification's 1e-9 at this magnitude, and the float32
    error is eight orders of magnitude larger than the tolerance.
    """
    rows = _heavy_window()
    correct = batch.amount_sum(rows, SCORED, "amount_sum_7d")

    # Accumulated at single precision, the way a store or a feature column typed float32 would.
    narrowed = np.float32(0.0)
    for row in rows:
        narrowed = np.float32(narrowed + np.float32(row.amount_rwf))
    mutated = float(narrowed)

    assert correct > 1e6, "precondition: the sum is large enough for float32 to lose digits"
    assert_detected(correct, mutated, "window sum accumulated in float32")
    # Recorded rather than merely detected: the gap is in the ones, not in the last bits.
    assert abs(correct - mutated) > 1.0


# --- configuration drift: the row that needs a fixture property, not a fixture value ------------


@pytest.mark.req("FR-02-02")
def test_mutation_11_joining_current_thresholds_instead_of_as_of_ones_is_detected() -> None:
    """ADR 0026's defect, applied to the batch path and caught by the required fixture property.

    The mutation is what a batch implementation does *by default*: join the configuration table
    and take the row that is current. For a transaction from 200 days ago that reads today's
    1,000,000 limit instead of the 500,000 limit it was actually subject to, and an amount of
    480,000 moves from inside the band to outside it.

    **The fixture property is the test.** The two paths agree for every transaction newer than the
    last configuration change, so a fixture set without one passes with the bug present — the
    suite would not be weak, it would be blind. The precondition asserting `changes > 0` is
    therefore asserted before the comparison, and is why ADR 0026 made it a property of the
    fixture rather than a case in a list.
    """
    limits = [
        OperationalLimit(
            dimension=LimitDimension.CHANNEL,
            applies_to="MOBILE_MONEY",
            amount_rwf=500_000.0,
            effective_at=T - timedelta(days=365),
        ),
        OperationalLimit(
            dimension=LimitDimension.CHANNEL,
            applies_to="MOBILE_MONEY",
            amount_rwf=1_000_000.0,
            effective_at=T - timedelta(days=30),
        ),
    ]
    scored = Transaction(
        transaction_id="old",
        account_id="A",
        timestamp=T - timedelta(days=200),
        amount_rwf=480_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        channel="MOBILE_MONEY",
    )

    changes = sum(1 for limit in limits if limit.effective_at > scored.timestamp)
    assert changes > 0, (
        "precondition (E12, ADR 0026): the fixture must span a configuration change that is in "
        "the future relative to the scored transaction, or both readings coincide and this "
        "mutation cannot be detected at all"
    )

    correct = batch.just_below_limit_flag(scored, limits, kyc_tier=None)
    # The mutation: take the latest version of each limit, ignoring effective_at entirely.
    current = max(limits, key=lambda limit: limit.effective_at)
    mutated = current.amount_rwf * 0.95 <= scored.amount_rwf < current.amount_rwf

    assert correct is True, "the transaction is inside the band that was in force at the time"
    assert mutated is False, "and outside the band in force today"
    assert correct != mutated, (
        "joining current thresholds gave the same answer as resolving them as of the transaction, "
        "so this fixture does not span a change that matters"
    )


# --- temporal rows -------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-43")
def test_mutation_4_local_time_applied_in_one_path_only_is_detected() -> None:
    """D-43's timezone handling diverging: one path reads local time, the other UTC.

    The fixture is 22:00 UTC, which is **local midnight** in a +2 pack. The hour pair therefore
    moves from (0, 1) to sin/cos of 22, `is_local_night` flips from True to False, and
    `local_day_of_week` changes day. All three are asserted, because the three features fail
    differently: two by a tolerance, one by exact equality and one by an ordinal.

    A fixture at local noon would show none of it — noon is noon in every positive offset — which
    is why every temporal fixture in this suite is chosen so the local and UTC answers differ.
    """
    packs = {"RW": CountryFacts("RW", "AF", frozenset({"EAC"}), 2)}
    scored = Transaction(
        transaction_id="midnight",
        account_id="A",
        timestamp=T.replace(hour=22, minute=0),
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        account_country="RW",
    )
    assert batch.local_hour(scored, packs) == 0, "precondition: local midnight"
    assert scored.timestamp.hour == 22, "precondition: and a different UTC hour"

    correct_sin = batch.local_hour_sin(scored, packs)
    correct_cos = batch.local_hour_cos(scored, packs)
    # The mutation: the offset is never applied, so the UTC hour is used as the local one.
    mutated_sin = math.sin(2.0 * math.pi * scored.timestamp.hour / 24.0)
    mutated_cos = math.cos(2.0 * math.pi * scored.timestamp.hour / 24.0)

    assert_detected(correct_sin, mutated_sin, "local time applied in one path only (sin)")
    assert_detected(correct_cos, mutated_cos, "local time applied in one path only (cos)")

    assert batch.is_local_night(scored, packs) is True
    assert (0 <= scored.timestamp.hour <= 4) is False, (
        "the same transaction is night locally and not night in UTC, which exact equality catches"
    )
    assert batch.local_day_of_week(scored, packs) != scored.timestamp.weekday()


@pytest.mark.req("FR-02-02")
def test_mutation_14_the_speed_cap_applied_on_one_path_only_is_detected() -> None:
    """Row 14, added when the zero-elapsed clause was removed (owner direction 2026-09-19).

    The cap is the whole of what `implied_speed_kmh` says in the impossible range: at the cap the
    feature reports "one person cannot have been in both places", and uncapped it reports a number
    that looks like a measurement. A path that dropped the cap would agree with the other for
    every plausible journey and diverge only where the feature carries its signal — the
    guard-with-two-doors shape, arriving as a difference between paths rather than as a missing
    check.

    The precondition asserts the fixture actually saturates, so this cannot pass over a journey
    that was merely fast.
    """
    previous = Transaction(
        transaction_id="previous",
        account_id="A",
        timestamp=T - timedelta(minutes=6),
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
    )
    scored = Transaction(
        transaction_id="scored",
        account_id="A",
        timestamp=T,
        amount_rwf=1_000.0,
        latitude=-1.2921,
        longitude=36.8219,
    )

    capped = batch.implied_speed_kmh([previous], scored)
    distance = batch.distance_from_last_tx_km([previous], scored)
    uncapped = distance / (6.0 / 60.0)

    assert capped == batch.MAX_IMPLIED_SPEED_KMH, "precondition: the fixture saturates the cap"
    assert uncapped > batch.MAX_IMPLIED_SPEED_KMH * 5, (
        "precondition: and does so by a wide margin, not by a rounding"
    )
    assert_detected(capped, uncapped, "implied-speed cap applied on one path only")


@pytest.mark.req("FR-02-02")
def test_the_cap_does_not_fire_for_a_journey_that_is_merely_fast() -> None:
    """The control for the row above, and the reason the cap sits at 1,000 rather than lower.

    754.9 km in an hour is an aircraft, not an impossibility. If the cap fired here the feature
    would report the same number for a flight and for a spoofed location, and the mutation above
    would pass for the wrong reason — a detector that fires at everything looks identical to one
    that works.
    """
    previous = Transaction(
        transaction_id="previous",
        account_id="A",
        timestamp=T - timedelta(hours=1),
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
    )
    scored = Transaction(
        transaction_id="scored",
        account_id="A",
        timestamp=T,
        amount_rwf=1_000.0,
        latitude=-1.2921,
        longitude=36.8219,
    )
    speed = batch.implied_speed_kmh([previous], scored)
    assert speed < batch.MAX_IMPLIED_SPEED_KMH
    assert speed == pytest.approx(754.9, abs=0.1)
