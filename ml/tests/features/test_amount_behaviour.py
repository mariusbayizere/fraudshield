"""The five amount-behaviour features, hand-computed on both paths (Part E.2, E13).

Two read the scored transaction alone, two read a 90 d window with a minimum-history cliff, and
one reads operational configuration **as of the transaction** (ADR 0026). The last is the only
feature so far whose correctness depends on a fixture property rather than on a value: the two
paths agree for every transaction newer than the last configuration change, so a fixture without
one passes with the bug present.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.types import (
    LimitDimension,
    OperationalLimit,
    Transaction,
)

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)

#: Denominations in minor units, as a country pack supplies them. Invented codes: the table is
#: keyed by currency and the feature never names one, so the test must not either.
DENOMINATIONS: dict[str, tuple[int, ...]] = {"AAA": (1_000, 5_000), "BBB": (25, 100)}


def tx(
    hours_before: float = 0.0,
    *,
    amount: float,
    tid: str = "scored",
    account: str = "A",
    minor: int | None = None,
    currency: str | None = None,
    channel: str | None = None,
) -> Transaction:
    return Transaction(
        transaction_id=tid,
        account_id=account,
        timestamp=T - timedelta(hours=hours_before),
        amount_rwf=amount,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        counterparty_id="M1",
        amount_minor=minor,
        currency=currency,
        channel=channel,
    )


def warm(rows: list[Transaction]) -> OnlineFeatures:
    features = OnlineFeatures()
    for row in rows:
        features.observe(row)
    return features


# --- amount_log1p -----------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_amount_log1p_against_hand_computed_values() -> None:
    """log1p(0) is exactly 0 and log1p(e - 1) is exactly 1; 10,000 RWF gives 9.2104...

    The zero case is the one worth pinning. `log(1 + x)` evaluated in float64 loses every
    significant digit as x approaches zero, and a near-zero amount — a balance check, a failed
    top-up — is exactly where an unusual amount is most visible.
    """
    assert batch.amount_log1p(tx(amount=0.0)) == 0.0
    assert OnlineFeatures().amount_log1p(tx(amount=0.0)) == 0.0
    assert batch.amount_log1p(tx(amount=math.e - 1.0)) == pytest.approx(1.0, abs=1e-15)
    assert batch.amount_log1p(tx(amount=10_000.0)) == pytest.approx(9.210440366976517, abs=1e-12)
    assert OnlineFeatures().amount_log1p(tx(amount=10_000.0)) == batch.amount_log1p(
        tx(amount=10_000.0)
    )


@pytest.mark.req("FR-02-02")
def test_log1p_is_not_the_same_as_log_of_one_plus_x_near_zero() -> None:
    """The precondition for the choice above, asserted so it is a measurement not an assertion."""
    tiny = 1e-17
    assert math.log(1.0 + tiny) == 0.0, "precondition: log(1+x) has collapsed at this magnitude"
    assert batch.amount_log1p(tx(amount=tiny)) > 0.0


# --- amount_zscore_90d ------------------------------------------------------------------------

#: Nine prior amounts: median 5,000, absolute deviations {4000, 3000, 2000, 1000, 0, 1000, 2000,
#: 3000, 4000} whose median is 2,000. So the scale is 1.4826 * 2000 = 2,965.2.
_Z_AMOUNTS = [1_000.0, 2_000.0, 3_000.0, 4_000.0, 5_000.0, 6_000.0, 7_000.0, 8_000.0, 9_000.0]
_Z_HISTORY = [
    tx(hours_before=24 * (i + 1), amount=amount, tid=f"z{i}") for i, amount in enumerate(_Z_AMOUNTS)
]


@pytest.mark.req("FR-02-02")
def test_the_robust_z_score_against_a_hand_worked_example() -> None:
    """median 5,000; MAD 2,000; scale 1.4826 x 2,000 = 2,965.2.

    A scored amount of 20,000 gives (20,000 - 5,000) / 2,965.2 = **5.0586...**, and one of 5,000
    gives exactly 0. The fixture's deviations are symmetric, so the median and the mean coincide —
    which means this test alone cannot tell them apart, and the next one exists for that.
    """
    scored = tx(amount=20_000.0)
    expected = 15_000.0 / (1.4826 * 2_000.0)
    assert batch.amount_zscore_90d(_Z_HISTORY, scored) == pytest.approx(expected, rel=1e-12)
    assert warm(_Z_HISTORY).amount_zscore_90d(scored) == pytest.approx(expected, rel=1e-12)
    assert batch.amount_zscore_90d(_Z_HISTORY, tx(amount=5_000.0)) == 0.0


@pytest.mark.req("FR-02-02")
def test_the_centre_is_the_median_and_not_the_mean() -> None:
    """One transaction abroad must not drag the centre, which is the whole point of "robust".

    The fixture adds a single 1,000,000 outlier: the mean moves by ~99,500 and the median by 500.
    Without this case the feature could be implemented with a mean and every other test would
    still pass.
    """
    history = [*_Z_HISTORY, tx(hours_before=240, amount=1_000_000.0, tid="outlier")]
    amounts = [row.amount_rwf for row in history]
    mean = sum(amounts) / len(amounts)
    assert mean > 100_000.0, "precondition: the outlier moves a mean a long way"

    scored = tx(amount=20_000.0)
    # Ten values now: the median is the mean of 5,000 and 6,000 = 5,500.
    centre = 5_500.0
    deviations = sorted(abs(a - centre) for a in amounts)
    spread = (deviations[4] + deviations[5]) / 2.0
    expected = (20_000.0 - centre) / (1.4826 * spread)
    assert batch.amount_zscore_90d(history, scored) == pytest.approx(expected, rel=1e-12)
    assert warm(history).amount_zscore_90d(scored) == pytest.approx(expected, rel=1e-12)


@pytest.mark.req("FR-02-02", "D-04")
def test_thin_history_is_nan_and_not_zero_on_both_paths() -> None:
    """`minimum_history=5` with `below_threshold_value=None`.

    Zero is the *most normal possible* z-score, so returning it for a thin-history account scores
    a new account as perfectly typical — the direction that makes a new account look safe. The
    boundary is asserted in both directions so an off-by-one in the threshold fails.
    """
    scored = tx(amount=20_000.0)
    for count in range(5):
        history = _Z_HISTORY[:count]
        assert math.isnan(batch.amount_zscore_90d(history, scored)), count
        assert math.isnan(warm(history).amount_zscore_90d(scored)), count
    assert not math.isnan(batch.amount_zscore_90d(_Z_HISTORY[:5], scored)), (
        "five observations is the declared minimum, so it must produce an estimate"
    )
    assert not math.isnan(warm(_Z_HISTORY[:5]).amount_zscore_90d(scored))


@pytest.mark.req("FR-02-02", "D-04")
def test_a_zero_mad_is_nan_rather_than_a_division_by_zero() -> None:
    """An account whose last 90 days are all one amount has no scale to divide by.

    A separate cliff from the minimum history and not foldable into it: there are eight
    observations here, comfortably over the threshold of five.
    """
    flat = [tx(hours_before=24 * (i + 1), amount=7_000.0, tid=f"f{i}") for i in range(8)]
    assert len(flat) > 5, "precondition: the minimum-history cliff is not what is being tested"
    assert len({row.amount_rwf for row in flat}) == 1, "precondition: the amounts are identical"
    scored = tx(amount=20_000.0)
    assert math.isnan(batch.amount_zscore_90d(flat, scored))
    assert math.isnan(warm(flat).amount_zscore_90d(scored))


@pytest.mark.req("FR-02-02")
def test_the_scored_amount_is_excluded_from_its_own_reference_set() -> None:
    """`self_inclusion=EXCLUDED`. A 1,000,000 scored amount inside its own median and MAD would
    pull both toward itself and shrink its own z-score, which is the direction that hides it."""
    scored = tx(amount=1_000_000.0)
    with_self = batch.amount_zscore_90d([*_Z_HISTORY, scored], scored)
    without = batch.amount_zscore_90d(_Z_HISTORY, scored)
    assert without == pytest.approx(995_000.0 / (1.4826 * 2_000.0), rel=1e-12)
    assert with_self == without, "the scored row entered its own window"


# --- amount_to_max_90d_ratio -------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_ratio_to_the_ninety_day_maximum() -> None:
    """The largest prior amount is 9,000, so 18,000 gives exactly 2.0 and 4,500 exactly 0.5."""
    assert batch.amount_to_max_90d_ratio(_Z_HISTORY, tx(amount=18_000.0)) == 2.0
    assert warm(_Z_HISTORY).amount_to_max_90d_ratio(tx(amount=18_000.0)) == 2.0
    assert batch.amount_to_max_90d_ratio(_Z_HISTORY, tx(amount=4_500.0)) == 0.5


@pytest.mark.req("FR-02-02", "D-04")
def test_one_prior_observation_is_enough_for_a_maximum() -> None:
    """`minimum_history=1` here against 5 for the z-score, which is the reason the field is
    declared per feature: a maximum is defined at one point and a scale estimate is not."""
    scored = tx(amount=18_000.0)
    assert math.isnan(batch.amount_to_max_90d_ratio([], scored))
    assert math.isnan(OnlineFeatures().amount_to_max_90d_ratio(scored))
    one = [tx(hours_before=24, amount=9_000.0, tid="one")]
    assert batch.amount_to_max_90d_ratio(one, scored) == 2.0
    assert warm(one).amount_to_max_90d_ratio(scored) == 2.0


# --- round_sum_flag ----------------------------------------------------------------------------


@pytest.mark.req("TEST-01", "FR-02-02", "ML-DATA-07")
def test_round_sums_are_exact_multiples_in_minor_units() -> None:
    """15,000 is a multiple of both 1,000 and 5,000; 12,345 of neither; 2,000 of 1,000 only.

    Integer minor units, so "exact" is exact. In binary floating point 0.1 + 0.2 is not 0.3, and a
    flag that answered "almost a multiple" would be a different feature from the one declared.
    """
    for minor, expected in ((15_000, True), (12_345, False), (2_000, True), (1, False)):
        scored = tx(amount=1.0, minor=minor, currency="AAA")
        assert batch.round_sum_flag(scored, DENOMINATIONS) is expected, minor
        assert OnlineFeatures().round_sum_flag(scored, DENOMINATIONS) is expected, minor


@pytest.mark.req("TEST-01", "FR-02-02", "ML-DATA-07")
def test_the_denominations_are_the_transactions_own_currencys() -> None:
    """The same integer is round in one currency and not in another, which is the property that
    makes a per-currency table necessary rather than tidy.

    100 is a multiple of BBB's 25 and 100, and of neither of AAA's 1,000 and 5,000.
    """
    assert batch.round_sum_flag(tx(amount=1.0, minor=100, currency="BBB"), DENOMINATIONS) is True
    assert batch.round_sum_flag(tx(amount=1.0, minor=100, currency="AAA"), DENOMINATIONS) is False


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_currency_without_denominations_is_refused_on_both_paths() -> None:
    """An empty table would make every amount un-round, which is an answer rather than a refusal.

    "No amount in this currency is round" and "nobody told us what round means here" are different
    statements, and only one of them is true.
    """
    scored = tx(amount=1.0, minor=1_000, currency="CCC")
    with pytest.raises(KeyError, match="CCC"):
        batch.round_sum_flag(scored, DENOMINATIONS)
    with pytest.raises(KeyError, match="CCC"):
        OnlineFeatures().round_sum_flag(scored, DENOMINATIONS)
    with pytest.raises((KeyError, ValueError), match="denominations"):
        batch.round_sum_flag(tx(amount=1.0, minor=1_000, currency="AAA"), {"AAA": ()})


@pytest.mark.req("FR-02-02")
def test_a_row_without_a_native_amount_is_refused() -> None:
    """Falling back to the base-currency amount would test a converted number for roundness, and
    roundness is a property of what the payer typed."""
    scored = tx(amount=15_000.0)
    assert scored.amount_minor is None, "precondition: the row carries no native amount"
    with pytest.raises(ValueError, match="amount_minor"):
        batch.round_sum_flag(scored, DENOMINATIONS)
    with pytest.raises(ValueError, match="amount_minor"):
        OnlineFeatures().round_sum_flag(scored, DENOMINATIONS)


# --- just_below_limit_flag (ADR 0026) -----------------------------------------------------------

_OLD_LIMIT = OperationalLimit(
    dimension=LimitDimension.CHANNEL,
    applies_to="MOBILE_MONEY",
    amount_rwf=500_000.0,
    effective_at=T - timedelta(days=365),
)
_NEW_LIMIT = OperationalLimit(
    dimension=LimitDimension.CHANNEL,
    applies_to="MOBILE_MONEY",
    amount_rwf=1_000_000.0,
    effective_at=T - timedelta(days=30),
)
_TIER_LIMIT = OperationalLimit(
    dimension=LimitDimension.KYC_TIER,
    applies_to="2",
    amount_rwf=200_000.0,
    effective_at=T - timedelta(days=365),
)
_LIMITS = (_OLD_LIMIT, _NEW_LIMIT, _TIER_LIMIT)


@pytest.mark.req("FR-02-02")
def test_the_band_is_five_percent_below_and_open_at_the_limit() -> None:
    """475,000 is exactly 5% below 500,000 and fires; 500,000 is the limit itself and does not.

    At or above the limit the transaction is not below it. The structuring signal is an amount
    placed deliberately *under* a threshold, and a transaction at the threshold was not structured
    around it.
    """
    old = [_OLD_LIMIT]
    cases = ((474_999.0, False), (475_000.0, True), (499_999.0, True), (500_000.0, False))
    for amount, expected in cases:
        scored = tx(amount=amount, channel="MOBILE_MONEY")
        assert batch.just_below_limit_flag(scored, old, kyc_tier=None) is expected, amount
        assert OnlineFeatures().just_below_limit_flag(scored, old, kyc_tier=None) is expected, (
            amount
        )


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_limit_in_force_is_the_one_at_the_transaction_not_the_latest() -> None:
    """ADR 0026's whole content, and parity mutation 11 stated as a value test.

    A transaction from 200 days ago was subject to the 500,000 limit; the 1,000,000 limit took
    effect 30 days ago. An amount of 480,000 is inside the old band and nowhere near the new one,
    so a path joining current configuration reads **False** where the truth is **True**.
    """
    old_transaction = tx(hours_before=24 * 200, amount=480_000.0, channel="MOBILE_MONEY")
    assert _NEW_LIMIT.effective_at > old_transaction.timestamp, (
        "precondition: the fixture spans a configuration change, or both paths agree while the "
        "batch one is wrong and the test is blind (E12, ADR 0026)"
    )
    assert _OLD_LIMIT.effective_at <= old_transaction.timestamp

    assert batch.just_below_limit_flag(old_transaction, _LIMITS, kyc_tier=None) is True
    assert OnlineFeatures().just_below_limit_flag(old_transaction, _LIMITS, kyc_tier=None) is True

    # The same amount today is not near the limit now in force, which is what makes the two
    # readings distinguishable rather than a matter of interpretation.
    today = tx(amount=480_000.0, channel="MOBILE_MONEY")
    assert batch.just_below_limit_flag(today, _LIMITS, kyc_tier=None) is False


@pytest.mark.req("FR-02-02")
def test_a_limit_that_had_not_taken_effect_is_absent_rather_than_zero() -> None:
    """Before a limit existed there was nothing to structure under.

    Treating an unborn limit as the earliest one known would make the flag true for transactions
    that predate the rule — a feature reporting a behaviour that was not possible.
    """
    before_anything = tx(hours_before=24 * 400, amount=480_000.0, channel="MOBILE_MONEY")
    assert all(limit.effective_at > before_anything.timestamp for limit in _LIMITS), (
        "precondition: no limit had taken effect yet"
    )
    assert batch.just_below_limit_flag(before_anything, _LIMITS, kyc_tier=None) is False
    assert OnlineFeatures().just_below_limit_flag(before_anything, _LIMITS, kyc_tier=None) is False


@pytest.mark.req("FR-02-02")
def test_a_kyc_tier_limit_fires_only_for_that_tier() -> None:
    """The tier is supplied by the caller because the tier itself is read as-of (ADR 0026).

    195,000 is inside the 5% band below the tier-2 limit of 200,000 and outside every channel
    band, so the two dimensions are distinguishable in this fixture.
    """
    scored = tx(amount=195_000.0, channel="CARD")
    assert batch.just_below_limit_flag(scored, _LIMITS, kyc_tier=2) is True
    assert OnlineFeatures().just_below_limit_flag(scored, _LIMITS, kyc_tier=2) is True
    assert batch.just_below_limit_flag(scored, _LIMITS, kyc_tier=1) is False
    assert batch.just_below_limit_flag(scored, _LIMITS, kyc_tier=None) is False


@pytest.mark.req("FR-02-02")
def test_a_channel_limit_does_not_apply_to_another_channel() -> None:
    """The same amount, a different channel: 480,000 fires under MOBILE_MONEY's old limit and not
    under CARD, which has none."""
    old_time = 24 * 200
    on_channel = tx(hours_before=old_time, amount=480_000.0, channel="MOBILE_MONEY")
    off_channel = tx(hours_before=old_time, amount=480_000.0, channel="CARD")
    assert batch.just_below_limit_flag(on_channel, _LIMITS, kyc_tier=None) is True
    assert batch.just_below_limit_flag(off_channel, _LIMITS, kyc_tier=None) is False


@pytest.mark.req("FR-02-02", "D-03")
def test_an_active_rule_threshold_is_refused_rather_than_silently_skipped() -> None:
    """Part E.2's third clause, which M1's schema cannot answer as-of (ADR 0026).

    A caller supplying one gets an error naming the gap. Skipping it would compute the flag over
    the two dimensions that work and present the result as the whole feature, which is the shape
    of every guard-with-two-doors in this project's notebook.
    """
    rule = OperationalLimit(
        dimension=LimitDimension.ACTIVE_RULE_THRESHOLD,
        applies_to="velocity-burst",
        amount_rwf=300_000.0,
        effective_at=T - timedelta(days=100),
    )
    scored = tx(amount=290_000.0, channel="MOBILE_MONEY")
    with pytest.raises(ValueError, match="active rule threshold"):
        batch.just_below_limit_flag(scored, [rule], kyc_tier=None)
    with pytest.raises(ValueError, match="active rule threshold"):
        OnlineFeatures().just_below_limit_flag(scored, [rule], kyc_tier=None)


@pytest.mark.req("FR-02-02")
def test_a_limit_must_be_positive_and_time_zoned() -> None:
    """Refused at construction, so a malformed limit cannot reach the as-of comparison."""
    with pytest.raises(ValueError, match="timezone-aware"):
        OperationalLimit(
            dimension=LimitDimension.CHANNEL,
            applies_to="CARD",
            amount_rwf=1.0,
            effective_at=datetime(2025, 1, 1),  # noqa: DTZ001 - the defect under test
        )
    with pytest.raises(ValueError, match="not a limit"):
        OperationalLimit(
            dimension=LimitDimension.CHANNEL,
            applies_to="CARD",
            amount_rwf=0.0,
            effective_at=T,
        )
