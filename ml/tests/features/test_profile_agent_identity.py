"""Account profile, agent and the synthetic-identity composite (Part E.2, E13, ADR 0026, D-04).

Three groups that read state the transaction stream does not carry. Two of the account-profile
features and three of the agent features are read **as of the transaction**, which is the axis no
window-contract field catches because there is no window to get wrong.

The four agent features are NaN together outside `AGENT_BANKING` — the second structural-NaN set
D-04 fixes at four, and it is asserted as a set for the same reason the device one is.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.registry import REGISTRY, Group
from fraudshield_ml.features.types import (
    AgentStanding,
    IdentityEvidence,
    TierAssignment,
    Transaction,
)

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)
NAIROBI = (-1.2921, 36.8219)
CASH_OUT = frozenset({"6011"})


def tx(
    hours_before: float = 0.0,
    *,
    account: str = "A",
    agent: str | None = None,
    mcc: str | None = None,
    where: tuple[float, float] = KIGALI,
    tid: str | None = None,
) -> Transaction:
    return Transaction(
        transaction_id=tid or f"{account}-{hours_before:g}",
        account_id=account,
        timestamp=T - timedelta(hours=hours_before),
        amount_rwf=1_000.0,
        latitude=where[0],
        longitude=where[1],
        counterparty_id="M1",
        channel="AGENT_BANKING" if agent else "MOBILE_MONEY",
        agent_id=agent,
        merchant_category_code=mcc,
    )


def warm(rows: list[Transaction]) -> OnlineFeatures:
    features = OnlineFeatures()
    for row in rows:
        features.observe(row)
    return features


SCORED = tx(tid="scored")
AT_AGENT = tx(agent="AG1", mcc="6011", tid="scored-at-agent")


# --- account_age_days and counterparty ages ---------------------------------------------------


@pytest.mark.req("FR-02-02", "D-04")
def test_the_account_age_is_supplied_and_nan_when_unknown() -> None:
    """PB-37: M1 has no per-account table, so today there is nowhere the opening date lives.

    NaN rather than an age measured from the earliest transaction in the data: an account may be
    opened long before it transacts, and the substitute is bounded below by the dataset's start,
    so the whole population would read as no older than the benchmark.
    """
    assert batch.account_age_days(SCORED, T - timedelta(days=400)) == 400.0
    assert OnlineFeatures().account_age_days(SCORED, T - timedelta(days=400)) == 400.0
    assert math.isnan(batch.account_age_days(SCORED, None))
    assert math.isnan(OnlineFeatures().account_age_days(SCORED, None))


# --- kyc_tier as of the transaction (ADR 0026) --------------------------------------------------

_TIERS = (
    TierAssignment(tier=1, effective_at=T - timedelta(days=400)),
    TierAssignment(tier=3, effective_at=T - timedelta(days=10)),
)


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_tier_is_the_one_in_force_not_the_current_one() -> None:
    """The upgrade to tier 3 happened 10 days ago; a transaction 200 days old was tier 1.

    This is the feature where ADR 0026 bites hardest, and the reason is not anachronism. Tier
    upgrades are frequently triggered by investigation, so the current tier of an investigated
    account is a **consequence of the fraud being scored** — reading it here is a label arriving
    through a profile column.

    The precondition asserts the fixture spans the change, or both readings coincide and the test
    is blind (E12).
    """
    old = tx(hours_before=200 * 24.0, tid="old")
    assert _TIERS[1].effective_at > old.timestamp, (
        "precondition: the fixture must span a tier change, or the as-of rule is untested"
    )
    assert batch.kyc_tier(old, _TIERS) == 1.0
    assert OnlineFeatures().kyc_tier(old, _TIERS) == 1.0
    assert batch.kyc_tier(SCORED, _TIERS) == 3.0
    assert OnlineFeatures().kyc_tier(SCORED, _TIERS) == 3.0


@pytest.mark.req("FR-02-02", "D-04")
def test_a_tier_that_had_not_taken_effect_is_nan_not_invented() -> None:
    """An invented tier is an ordinal the model reads as a real one, which is worse than missing."""
    ancient = tx(hours_before=500 * 24.0, tid="ancient")
    assert all(a.effective_at > ancient.timestamp for a in _TIERS), (
        "precondition: no assignment had taken effect"
    )
    assert math.isnan(batch.kyc_tier(ancient, _TIERS))
    assert math.isnan(OnlineFeatures().kyc_tier(ancient, _TIERS))


# --- days_since_sim_swap ------------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-04")
def test_the_gap_since_the_most_recent_swap_before_the_transaction() -> None:
    """Swaps 90 and 3 days back give 3; a swap dated after the transaction is ignored.

    The future swap is the case that matters: an MNO feed delivered in bulk carries swaps that had
    not happened yet, which is `label_available_at`'s problem in a substrate where nobody thinks
    to look for it.
    """
    swaps = [T - timedelta(days=90), T - timedelta(days=3), T + timedelta(days=5)]
    assert swaps[2] > SCORED.timestamp, "precondition: one swap is in the transaction's future"
    assert batch.days_since_sim_swap(SCORED, swaps) == 3.0
    assert OnlineFeatures().days_since_sim_swap(SCORED, swaps) == 3.0


@pytest.mark.req("FR-02-02", "D-04")
def test_no_swap_and_no_signal_are_both_nan() -> None:
    """Deliberately conflated. Both are the genuine absence of a date, and the alternative — a
    large number for "no swap ever" — would read as "a swap long ago", the safe end of a signal
    whose dangerous end is "a swap an hour ago". Distinguishing them needs a second feature."""
    assert math.isnan(batch.days_since_sim_swap(SCORED, []))
    assert math.isnan(batch.days_since_sim_swap(SCORED, [T + timedelta(days=1)]))
    assert math.isnan(OnlineFeatures().days_since_sim_swap(SCORED, []))


# --- dormancy_reactivation_flag ------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_dormancy_needs_both_silence_and_a_prior_life() -> None:
    """Silent 61 days having transacted before is True; silent but brand new is False.

    The second condition is what makes the feature `DURABLE`: a 60-day window can see that an
    account has been quiet and cannot tell a dormant account from a new one.
    """
    dormant = [tx(hours_before=61 * 24.0, tid="long-ago")]
    assert batch.dormancy_reactivation_flag(dormant, SCORED) is True
    assert warm(dormant).dormancy_reactivation_flag(SCORED) is True

    assert batch.dormancy_reactivation_flag([], SCORED) is False, (
        "a new account is not a reactivation; False, and definitely not NaN"
    )
    assert OnlineFeatures().dormancy_reactivation_flag(SCORED) is False

    active = [tx(hours_before=61 * 24.0, tid="old"), tx(hours_before=5.0, tid="recent")]
    assert batch.dormancy_reactivation_flag(active, SCORED) is False
    assert warm(active).dormancy_reactivation_flag(SCORED) is False


@pytest.mark.req("FR-02-02")
def test_the_dormancy_boundary_is_the_declared_sixty_days() -> None:
    """Exactly 60 days back counts as silence, 59 does not — the same open interval as every
    other window here."""
    on_the_edge = [tx(hours_before=60 * 24.0, tid="edge")]
    just_inside = [tx(hours_before=60 * 24.0 - 1.0, tid="inside")]
    assert batch.dormancy_reactivation_flag(on_the_edge, SCORED) is True
    assert batch.dormancy_reactivation_flag(just_inside, SCORED) is False
    assert warm(on_the_edge).dormancy_reactivation_flag(SCORED) is True
    assert warm(just_inside).dormancy_reactivation_flag(SCORED) is False


# --- the agent structural-NaN set ----------------------------------------------------------------

_STANDING = (
    AgentStanding(
        float_balance_rwf=200_000.0,
        float_limit_rwf=1_000_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        effective_at=T - timedelta(days=400),
    ),
    AgentStanding(
        float_balance_rwf=900_000.0,
        float_limit_rwf=1_000_000.0,
        latitude=NAIROBI[0],
        longitude=NAIROBI[1],
        effective_at=T - timedelta(days=5),
    ),
)


def _agent_values(rows: list[Transaction], scored: Transaction) -> dict[str, float]:
    features = warm(rows)
    return {
        "batch.float": batch.agent_float_utilisation_ratio(scored, _STANDING),
        "batch.cashouts": batch.agent_cashout_count_1h(rows, scored, CASH_OUT),
        "batch.customers": batch.agent_unique_customers_1h(rows, scored),
        "batch.distance": batch.agent_distance_from_registered_km(scored, _STANDING),
        "online.float": features.agent_float_utilisation_ratio(scored, _STANDING),
        "online.cashouts": features.agent_cashout_count_1h(scored, CASH_OUT),
        "online.customers": features.agent_unique_customers_1h(scored),
        "online.distance": features.agent_distance_from_registered_km(scored, _STANDING),
    }


@pytest.mark.req("FR-02-02", "D-04")
def test_exactly_four_agent_features_are_nan_together_off_an_agent() -> None:
    """The second structural-NaN set, asserted as a set on both paths, with its control.

    Without the control the assertion would hold over an implementation that returned NaN
    unconditionally — which is how a contract about *which* features go missing becomes a test
    that nothing works.
    """
    rows = [tx(hours_before=0.5, agent="AG1", mcc="6011", account="B")]
    off_agent = _agent_values(rows, SCORED)
    assert all(math.isnan(v) for v in off_agent.values()), (
        f"not every agent feature is NaN off an agent: {off_agent}"
    )
    on_agent = _agent_values(rows, AT_AGENT)
    assert not any(math.isnan(v) for v in on_agent.values()), (
        f"an agent feature is NaN at an agent: {on_agent}"
    )


@pytest.mark.req("FR-02-02", "D-04")
def test_the_registry_names_the_same_four_agent_features() -> None:
    group = {name for name, spec in REGISTRY.items() if spec.group is Group.AGENT}
    assert set(batch.AGENT_FEATURES) == group
    assert len(group) == 4, "D-04 fixes the agent structural NaN count at four"


# --- the agent features' values ------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_float_ratio_and_premises_are_read_as_of_the_transaction() -> None:
    """The agent relocated and drew its float down 5 days ago.

    A transaction 30 days old was at an agent with 20% utilisation, in Kigali. Reading today's
    standing gives 90% and Nairobi — and an agent that relocated *after* an incident would
    otherwise appear to have been at its new premises all along, so this feature would report a
    short distance for exactly the transactions that were far from where the agent actually was.
    """
    old = tx(hours_before=30 * 24.0, agent="AG1", mcc="6011", tid="old-at-agent")
    assert _STANDING[1].effective_at > old.timestamp, (
        "precondition: the fixture spans a change of standing (E12, ADR 0026)"
    )
    assert batch.agent_float_utilisation_ratio(old, _STANDING) == pytest.approx(0.2)
    assert batch.agent_distance_from_registered_km(old, _STANDING) == pytest.approx(0.0, abs=0.01)

    assert batch.agent_float_utilisation_ratio(AT_AGENT, _STANDING) == pytest.approx(0.9)
    assert batch.agent_distance_from_registered_km(AT_AGENT, _STANDING) == pytest.approx(
        754.9, abs=1.0
    )
    features = OnlineFeatures()
    assert features.agent_float_utilisation_ratio(old, _STANDING) == pytest.approx(0.2)
    assert features.agent_distance_from_registered_km(old, _STANDING) == pytest.approx(
        0.0, abs=0.01
    )


@pytest.mark.req("FR-02-02")
def test_the_cashout_count_counts_only_cash_disbursements() -> None:
    """Three cash-outs and one non-cash transaction at the same agent in the hour: the count is 3.

    On the current dataset every agent transaction carries the cash code, so this fixture contains
    something the benchmark never produces. That is deliberate: a feature tested only against data
    that cannot distinguish its two cases proves nothing about the distinction.
    """
    rows = [
        tx(hours_before=0.2, agent="AG1", mcc="6011", account="B"),
        tx(hours_before=0.4, agent="AG1", mcc="6011", account="C"),
        tx(hours_before=0.6, agent="AG1", mcc="6011", account="D"),
        tx(hours_before=0.5, agent="AG1", mcc="5411", account="E", tid="not-cash"),
        tx(hours_before=2.0, agent="AG1", mcc="6011", account="F", tid="too-old"),
        tx(hours_before=0.3, agent="AG2", mcc="6011", account="G", tid="other-agent"),
    ]
    assert batch.agent_cashout_count_1h(rows, AT_AGENT, CASH_OUT) == 3.0
    assert warm(rows).agent_cashout_count_1h(AT_AGENT, CASH_OUT) == 3.0


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_customer_count_is_distinct_accounts_including_the_scored_one() -> None:
    """Four other accounts in the hour plus the scored one is 5, and one account transacting twice
    counts once — six rows, five accounts, so the two readings differ in this fixture."""
    rows = [
        tx(hours_before=0.2, agent="AG1", mcc="6011", account="B"),
        tx(hours_before=0.3, agent="AG1", mcc="6011", account="B", tid="b-again"),
        tx(hours_before=0.4, agent="AG1", mcc="6011", account="C"),
        tx(hours_before=0.5, agent="AG1", mcc="5411", account="D"),
        tx(hours_before=0.6, agent="AG1", mcc="6011", account="E"),
        tx(hours_before=3.0, agent="AG1", mcc="6011", account="F", tid="too-old"),
    ]
    assert len(rows) == 6, "precondition: six rows"
    assert batch.agent_unique_customers_1h(rows, AT_AGENT) == 5.0
    assert warm(rows).agent_unique_customers_1h(AT_AGENT) == 5.0


@pytest.mark.req("FR-02-02", "D-04")
def test_an_agent_with_no_recorded_standing_is_nan() -> None:
    """Before the agent had a standing there is no utilisation and no address.

    Substituting the earliest on record would apply a float limit that did not exist to a
    transaction that predates it — ADR 0026's defect with the arrow pointing backwards.
    """
    ancient = tx(hours_before=500 * 24.0, agent="AG1", mcc="6011", tid="ancient")
    assert math.isnan(batch.agent_float_utilisation_ratio(ancient, _STANDING))
    assert math.isnan(batch.agent_distance_from_registered_km(ancient, _STANDING))


@pytest.mark.req("FR-02-02")
def test_a_standing_with_a_non_positive_limit_is_refused_at_construction() -> None:
    """A utilisation ratio against a zero limit is not a fraction of anything."""
    with pytest.raises(ValueError, match="not a fraction"):
        AgentStanding(
            float_balance_rwf=1.0,
            float_limit_rwf=0.0,
            latitude=0.0,
            longitude=0.0,
            effective_at=T,
        )


# --- synthetic_identity_score --------------------------------------------------------------------

TIER_RANGE = (1, 3)


def evidence(
    *,
    tier: float = 3.0,
    opened_at: datetime | None = None,
    accounts_on_device: float = 1.0,
) -> IdentityEvidence:
    return IdentityEvidence(
        kyc_tier=tier,
        kyc_tier_range=TIER_RANGE,
        opened_at=opened_at if opened_at is not None else T - timedelta(days=900),
        accounts_on_device=accounts_on_device,
    )


@pytest.mark.req("FR-02-02")
def test_the_composite_is_zero_when_no_term_fires() -> None:
    """Highest tier, old account, unshared device, no history: every term 0, so the score is 0.

    **A score of 0 means "no evidence", not "checked and clean"** — the two are indistinguishable
    in the output, which is the cost of the rule that a missing input contributes zero.
    """
    score = batch.synthetic_identity_score([], SCORED, evidence())
    assert score == 0.0
    assert OnlineFeatures().synthetic_identity_score(SCORED, evidence()) == 0.0


@pytest.mark.req("FR-02-02")
def test_each_term_contributes_exactly_a_quarter_at_its_maximum() -> None:
    """The form is an unweighted mean of four terms, so one term at its maximum is exactly 0.25.

    Asserted term by term, which is what makes the composite readable: a score of 0.5 is two terms
    at their maximum, or four at half, and nothing else.
    """
    lowest_tier = evidence(tier=1.0)
    assert batch.synthetic_identity_score([], SCORED, lowest_tier) == 0.25

    brand_new = evidence(opened_at=T - timedelta(days=10))
    assert batch.synthetic_identity_score([], SCORED, brand_new) == 0.25

    shared = evidence(accounts_on_device=3.0)
    assert batch.synthetic_identity_score([], SCORED, shared) == 0.25

    both = IdentityEvidence(
        kyc_tier=1.0,
        kyc_tier_range=TIER_RANGE,
        opened_at=T - timedelta(days=10),
        accounts_on_device=3.0,
    )
    assert batch.synthetic_identity_score([], SCORED, both) == 0.75
    assert OnlineFeatures().synthetic_identity_score(SCORED, both) == 0.75


@pytest.mark.req("FR-02-02")
def test_the_ramp_term_is_zero_for_a_constant_rate_account() -> None:
    """An evenly-transacting account scores 0 on the ramp term, not 0.23.

    That is what makes the term mean "faster than its own baseline" rather than "recent", and it
    is why the raw share is rescaled rather than used directly: a term that read 0.23 for ordinary
    behaviour would add a constant to every account's score and the composite would stop being
    readable as "how many terms fired".

    One transaction a day for thirty days. Both windows are open at the far end, so the rows at
    exactly 7 d and 30 d are excluded and the counts are **6 of 29**, a share of 0.207 against the
    even-rate 0.233 — just below, so the term clips to 0. The arithmetic is spelled out because
    the near-miss is the interesting part: the zero point sits where a constant rate lands, and a
    fixture a day either side of it still reads 0.
    """
    even = [tx(hours_before=24.0 * (i + 1), tid=f"d{i}") for i in range(30)]
    week = SCORED.timestamp - timedelta(days=7)
    month = SCORED.timestamp - timedelta(days=30)
    recent = sum(1 for row in even if week < row.timestamp < SCORED.timestamp)
    over_month = sum(1 for row in even if month < row.timestamp < SCORED.timestamp)
    assert (recent, over_month) == (6, 29), (
        f"precondition: the open windows hold 6 of 29, got {recent} of {over_month}"
    )
    assert recent / over_month < 7 / 30, "precondition: an even account sits below the zero point"

    assert batch.synthetic_identity_score(even, SCORED, evidence()) == 0.0
    assert warm(even).synthetic_identity_score(SCORED, evidence()) == 0.0


@pytest.mark.req("FR-02-02")
def test_the_ramp_term_is_between_zero_and_one_for_a_partial_ramp() -> None:
    """A control for the two ends: the term must not be a flag wearing a float's type.

    Fifteen transactions in the last week and fifteen spread over the preceding three, which is a
    share well above the even rate and well below 1. If the term only ever read 0 or 1 the two
    tests either side of this one would pass over a threshold rather than a ramp.
    """
    ramping = [tx(hours_before=float(i) * 8.0 + 1.0, tid=f"r{i}") for i in range(15)]
    ramping += [tx(hours_before=24.0 * (8 + i), tid=f"o{i}") for i in range(15)]
    score = batch.synthetic_identity_score(ramping, SCORED, evidence())
    assert 0.0 < score < 0.25, f"the ramp term saturated or did not fire: score {score}"
    assert warm(ramping).synthetic_identity_score(SCORED, evidence()) == pytest.approx(score)


@pytest.mark.req("FR-02-02")
def test_an_account_whose_whole_month_is_in_the_last_week_ramps_to_one() -> None:
    """All twenty transactions inside seven days: the ramp term is 1, so the score is 0.25."""
    burst = [tx(hours_before=float(i) + 1.0, tid=f"b{i}") for i in range(20)]
    assert all(row.timestamp > SCORED.timestamp - timedelta(days=7) for row in burst)
    assert batch.synthetic_identity_score(burst, SCORED, evidence()) == pytest.approx(0.25)
    assert warm(burst).synthetic_identity_score(SCORED, evidence()) == pytest.approx(0.25)


@pytest.mark.req("FR-02-02", "D-04")
def test_a_missing_input_contributes_zero_rather_than_propagating_nan() -> None:
    """The registry fixes this for the device term and it is applied to every term.

    If a null fingerprint propagated NaN, every USSD transaction would lose this feature too and
    D-04's structural-NaN count would be five rather than four.
    """
    absent = IdentityEvidence(
        kyc_tier=float("nan"),
        kyc_tier_range=TIER_RANGE,
        opened_at=None,
        accounts_on_device=float("nan"),
    )
    score = batch.synthetic_identity_score([], SCORED, absent)
    assert score == 0.0
    assert not math.isnan(score)
    assert OnlineFeatures().synthetic_identity_score(SCORED, absent) == 0.0


@pytest.mark.req("FR-02-02")
def test_the_score_stays_inside_the_unit_interval() -> None:
    """In [0, 1] by construction rather than by clipping: four terms each in [0, 1], averaged.

    Swept over tiers beyond the declared range and a device shared by ten accounts, so a term that
    could exceed 1 would show up here rather than as an out-of-range score in training.
    """
    burst = [tx(hours_before=float(i) + 1.0, tid=f"s{i}") for i in range(20)]
    for tier in (0.0, 1.0, 2.0, 3.0, 4.0):
        for shared in (0.0, 1.0, 2.0, 10.0):
            score = batch.synthetic_identity_score(
                burst,
                SCORED,
                IdentityEvidence(
                    kyc_tier=tier,
                    kyc_tier_range=TIER_RANGE,
                    opened_at=T - timedelta(days=1),
                    accounts_on_device=shared,
                ),
            )
            assert 0.0 <= score <= 1.0, (tier, shared, score)


@pytest.mark.req("FR-02-02")
def test_a_degenerate_tier_range_is_refused_on_both_paths() -> None:
    """The range comes from the country pack, so one with no span is a pack error rather than an
    account with no tier — and "low tier" would have no meaning to compute."""
    broken = IdentityEvidence(
        kyc_tier=1.0, kyc_tier_range=(2, 2), opened_at=None, accounts_on_device=1.0
    )
    with pytest.raises(ValueError, match="no span"):
        batch.synthetic_identity_score([], SCORED, broken)
    with pytest.raises(ValueError, match="no span"):
        OnlineFeatures().synthetic_identity_score(SCORED, broken)
