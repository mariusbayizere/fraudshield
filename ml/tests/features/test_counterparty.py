"""The five counterparty features on both paths, hand-computed (Part E.2, E13).

Two of them are keyed by the **counterparty** rather than the account, so E1's account-grouped
folds do not isolate them: a ring moving money from ten victims into one mule produces ten rows
whose feature values all come from the same counterparty-keyed object. Their fixtures therefore
contain several accounts paying one counterparty, because a single-account fixture cannot tell a
counterparty-keyed aggregate from an account-keyed one — the defect that made
`geo_cell_fraud_rate_30d`'s first leakage note wrong.
"""

from __future__ import annotations

import math
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.types import Outcome, Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)


def tx(
    hours_before: float,
    *,
    counterparty: str,
    account: str = "A",
    tid: str | None = None,
) -> Transaction:
    return Transaction(
        transaction_id=tid or f"{account}-{counterparty}-{hours_before:g}",
        account_id=account,
        timestamp=T - timedelta(hours=hours_before),
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        counterparty_id=counterparty,
    )


def warm(rows: list[Transaction], outcomes: dict[str, Outcome] | None = None) -> OnlineFeatures:
    features = OnlineFeatures()
    for outcome in (outcomes or {}).values():
        features.observe_outcome(outcome)
    for row in rows:
        features.observe(row)
    return features


SCORED = tx(0.0, counterparty="MULE", tid="scored")


# --- counterparty_is_new_for_account ------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_a_counterparty_is_new_until_this_account_has_paid_it() -> None:
    """Another account's payment to the same counterparty does not make it familiar.

    That is the distinction between this feature and `counterparty_unique_senders_24h`: this one
    is keyed by the account and asks about *this* relationship. The fixture contains the other
    account's row so the two readings are distinguishable here.
    """
    history = [
        tx(100.0, counterparty="SHOP", account="A"),
        tx(50.0, counterparty="MULE", account="B"),
    ]
    assert batch.counterparty_is_new_for_account(history, SCORED) is True
    assert warm(history).counterparty_is_new_for_account(SCORED) is True

    after_paying = [*history, tx(10.0, counterparty="MULE", account="A")]
    assert batch.counterparty_is_new_for_account(after_paying, SCORED) is False
    assert warm(after_paying).counterparty_is_new_for_account(SCORED) is False


@pytest.mark.req("FR-02-02")
def test_the_relationship_history_is_unbounded() -> None:
    """A payee used once three years ago is not new.

    1,000 days is far past the 90 d account horizon, so the online path can only answer this from
    durable state — which is what makes the flush case below meaningful rather than decorative.
    """
    ancient = [tx(1_000 * 24.0, counterparty="MULE", account="A", tid="ancient")]
    assert batch.counterparty_is_new_for_account(ancient, SCORED) is False
    assert warm(ancient).counterparty_is_new_for_account(SCORED) is False


@pytest.mark.req("FR-02-02", "D-04")
def test_a_flush_reports_every_payee_as_new_until_restored() -> None:
    """`history_requirement=DURABLE`, and PB-37's failure shape in a fourth field.

    After a flush the set is empty, so the feature fires on every established relationship at
    once — during a recovery, when a surge of novelty signals is least likely to be read as a
    cache problem.
    """
    history = [tx(100.0, counterparty="MULE", account="A", tid="paid-before")]
    features = warm(history)
    assert features.counterparty_is_new_for_account(SCORED) is False

    features.flush_cache()
    assert features.counterparty_is_new_for_account(SCORED) is True

    features.restore_counterparties("A", {"MULE"})
    assert features.counterparty_is_new_for_account(SCORED) is False


# --- counterparty_unique_senders_24h -------------------------------------------------------------

#: Four accounts paying one mule inside 24 h, one of them twice, plus a fifth payment that is
#: older than the window and a payment to a different counterparty.
_MULE_ROWS = [
    tx(2.0, counterparty="MULE", account="V1"),
    tx(3.0, counterparty="MULE", account="V2"),
    tx(4.0, counterparty="MULE", account="V3"),
    tx(5.0, counterparty="MULE", account="V3", tid="v3-again"),
    tx(6.0, counterparty="MULE", account="V4"),
    tx(30.0, counterparty="MULE", account="V5", tid="too-old"),
    tx(1.0, counterparty="SHOP", account="V6", tid="other-payee"),
]


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_mule_signal_counts_distinct_senders_inside_the_window() -> None:
    """V1, V2, V3 (twice) and V4 inside 24 h is **four** distinct senders.

    V5 is 30 h back and outside; the payment to SHOP is a different counterparty. Six rows, four
    senders, so a count of rows and a count of the set are different numbers here — if they were
    equal the test would pass under either implementation.
    """
    inside = [
        r
        for r in _MULE_ROWS
        if r.counterparty_id == "MULE" and r.timestamp > T - timedelta(hours=24)
    ]
    assert len(inside) == 5, "precondition: five rows inside the window"
    assert len({r.account_id for r in inside}) == 4, "precondition: one sender appears twice"

    assert batch.counterparty_unique_senders_24h(_MULE_ROWS, SCORED) == 4
    assert warm(_MULE_ROWS).counterparty_unique_senders_24h(SCORED) == 4


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_the_sender_count_reads_other_accounts_rows_which_is_why_it_needs_a_control() -> None:
    """The property that makes `history_key=COUNTERPARTY` necessary, asserted not assumed.

    If the feature were in fact account-scoped, E1's account-grouped folds would isolate it and
    `cross_account_control` would be describing a control that does nothing. Restricting the
    corpus to the scored account's own rows changes the value, so it is not.
    """
    scored = tx(0.0, counterparty="MULE", account="V1", tid="scored-by-v1")
    own = [r for r in _MULE_ROWS if r.account_id == "V1"]
    assert own, "precondition: the scored account has rows of its own"
    assert len(own) < len(_MULE_ROWS), "precondition: other accounts have rows too"

    across = batch.counterparty_unique_senders_24h(_MULE_ROWS, scored)
    own_only = batch.counterparty_unique_senders_24h(own, scored)
    assert across > own_only, (
        "other accounts' rows did not change the count, so either the fixture is not shared or "
        "the aggregate is not counterparty-keyed"
    )


@pytest.mark.req("FR-02-02")
def test_an_unseen_counterparty_has_no_senders_rather_than_an_error() -> None:
    """Zero, not NaN: nobody has sent to it, which is a count and not an unknown."""
    fresh = tx(0.0, counterparty="BRAND-NEW", tid="fresh")
    assert batch.counterparty_unique_senders_24h([], fresh) == 0
    assert OnlineFeatures().counterparty_unique_senders_24h(fresh) == 0


# --- counterparty_confirmed_fraud_90d ------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_confirmed_fraud_counts_only_labels_that_had_arrived() -> None:
    """Three fraudulent payments to the mule: two labelled before the scored transaction, one
    after. The feature reads **2**, not 3.

    The third row is the one that matters. Its label exists in the corpus and had not arrived when
    the transaction being scored happened, so counting it imports the investigation delay — which
    the online path structurally cannot do and the batch path will unless stopped.
    """
    rows = [
        tx(48.0, counterparty="MULE", account="V1", tid="f1"),
        tx(72.0, counterparty="MULE", account="V2", tid="f2"),
        tx(96.0, counterparty="MULE", account="V3", tid="f3-late-label"),
        tx(24.0, counterparty="MULE", account="V4", tid="clean"),
    ]
    outcomes = {
        "f1": Outcome("f1", True, T - timedelta(hours=1)),
        "f2": Outcome("f2", True, T - timedelta(hours=2)),
        "f3-late-label": Outcome("f3-late-label", True, T + timedelta(days=5)),
        "clean": Outcome("clean", False, T - timedelta(hours=3)),
    }
    late = outcomes["f3-late-label"]
    assert late.available_at > SCORED.timestamp, (
        "precondition: one confirmed fraud's label had not arrived, or the lag is untested (E12)"
    )

    assert batch.counterparty_confirmed_fraud_90d(rows, outcomes, SCORED) == 2
    assert warm(rows, outcomes).counterparty_confirmed_fraud_90d(SCORED) == 2


@pytest.mark.req("FR-02-02")
def test_confirmed_fraud_is_bounded_by_the_ninety_day_window() -> None:
    """A fraud 91 days back is outside; one 89 days back is inside."""
    outside = tx(91 * 24.0, counterparty="MULE", account="V1", tid="old-fraud")
    inside = tx(89 * 24.0, counterparty="MULE", account="V2", tid="recent-fraud")
    outcomes = {
        "old-fraud": Outcome("old-fraud", True, T - timedelta(days=80)),
        "recent-fraud": Outcome("recent-fraud", True, T - timedelta(days=80)),
    }
    assert batch.counterparty_confirmed_fraud_90d([outside], outcomes, SCORED) == 0
    assert batch.counterparty_confirmed_fraud_90d([inside], outcomes, SCORED) == 1
    assert warm([outside], outcomes).counterparty_confirmed_fraud_90d(SCORED) == 0
    assert warm([inside], outcomes).counterparty_confirmed_fraud_90d(SCORED) == 1


# --- tx_count_to_counterparty_30d -----------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_relationship_count_is_this_pair_only() -> None:
    """Three payments from A to MULE inside 30 d is 3; A's payments to SHOP and B's to MULE are not.

    Keyed by the ACCOUNT even though a counterparty appears in it, so account-grouped folds do
    isolate it — the fixture contains both the other payee and the other payer, so a path that got
    the key wrong reads 4 or 5 rather than 3.
    """
    rows = [
        tx(10.0, counterparty="MULE", account="A", tid="a1"),
        tx(100.0, counterparty="MULE", account="A", tid="a2"),
        tx(500.0, counterparty="MULE", account="A", tid="a3"),
        tx(20.0, counterparty="SHOP", account="A", tid="other-payee"),
        tx(20.0, counterparty="MULE", account="B", tid="other-payer"),
        tx(31 * 24.0, counterparty="MULE", account="A", tid="too-old"),
    ]
    assert batch.tx_count_to_counterparty_30d(rows, SCORED) == 3
    assert warm(rows).tx_count_to_counterparty_30d(SCORED) == 3


@pytest.mark.req("FR-02-02")
def test_a_first_transfer_to_a_new_payee_counts_zero() -> None:
    """The established-relationship signal at its low end: 0, not NaN."""
    assert batch.tx_count_to_counterparty_30d([], SCORED) == 0
    assert OnlineFeatures().tx_count_to_counterparty_30d(SCORED) == 0


# --- counterparty_account_age_days ---


@pytest.mark.req("FR-02-02", "D-04")
def test_the_counterparty_age_is_supplied_and_nan_when_unknown() -> None:
    """30 days exactly, and NaN when the opening date is not known.

    NaN rather than a substitute: the natural one — the counterparty's first transaction seen in
    the data — is bounded below by the dataset's own start, so every counterparty would look at
    most as old as the benchmark and the whole population would read as young.
    """
    opened = T - timedelta(days=30)
    assert batch.counterparty_account_age_days(SCORED, opened) == 30.0
    assert OnlineFeatures().counterparty_account_age_days(SCORED, opened) == 30.0
    assert math.isnan(batch.counterparty_account_age_days(SCORED, None))
    assert math.isnan(OnlineFeatures().counterparty_account_age_days(SCORED, None))


@pytest.mark.req("FR-02-02")
def test_a_transaction_before_the_opening_date_is_negative_not_clamped() -> None:
    """A data error must not be presented as a brand-new account, which is the value the fraud
    signal points at. It reports the contradiction instead."""
    future_opening = T + timedelta(days=5)
    assert batch.counterparty_account_age_days(SCORED, future_opening) == -5.0


# --- refusals ------------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_a_row_without_a_counterparty_is_refused_by_every_counterparty_feature() -> None:
    """Rows without one would share an identity, so the second would read as established and the
    mule count would lump unrelated recipients together."""
    anonymous = Transaction(
        transaction_id="anon",
        account_id="A",
        timestamp=T,
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
    )
    assert anonymous.counterparty_id is None, "precondition: the row carries no counterparty"
    features = OnlineFeatures()
    calls: tuple[Callable[[], object], ...] = (
        lambda: batch.counterparty_is_new_for_account([], anonymous),
        lambda: batch.counterparty_unique_senders_24h([], anonymous),
        lambda: batch.counterparty_confirmed_fraud_90d([], {}, anonymous),
        lambda: batch.tx_count_to_counterparty_30d([], anonymous),
        lambda: features.counterparty_is_new_for_account(anonymous),
        lambda: features.counterparty_unique_senders_24h(anonymous),
        lambda: features.counterparty_confirmed_fraud_90d(anonymous),
        lambda: features.tx_count_to_counterparty_30d(anonymous),
    )
    for call in calls:
        with pytest.raises(ValueError, match="counterparty_id"):
            call()
