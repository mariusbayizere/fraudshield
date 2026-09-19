"""The seven remaining velocity features, hand-computed on both paths (Part E.2, E13).

Four trailing counts, two trailing sums and one trailing distinct count. They share one contract
in the registry — `_TRAILING_ACCOUNT_AGGREGATE` — so they share one fixture here, and the fixture
is built to sit **on** every window edge rather than comfortably inside it: a row exactly at
`t - W` is outside the window, and a suite whose rows never land there cannot tell an inclusive
bound from an exclusive one (parity mutation 2).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.online import window_of as online_window_of
from fraudshield_ml.features.types import Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)

COUNTS = ("tx_count_60s", "tx_count_1h", "tx_count_24h", "tx_count_7d")

#: Seconds before `T`, and the counterparty each row paid. The four values 60, 3600, 86400 and
#: 604800 are the window edges themselves: each is excluded from its own window and included in
#: every longer one, so one fixture exercises all four bounds in both directions.
HISTORY: tuple[tuple[int, str], ...] = (
    (30, "M1"),
    (60, "M1"),
    (90, "M2"),
    (1_800, "M3"),
    (3_600, "M3"),
    (7_200, "M3"),
    (43_200, "M4"),
    (86_400, "M5"),
    (259_200, "M6"),
    (604_800, "M7"),
    (864_000, "M8"),
)


def tx(
    seconds_before: int,
    *,
    amount: float,
    counterparty: str,
    account: str = "A",
) -> Transaction:
    return Transaction(
        transaction_id=f"{account}-{seconds_before}",
        account_id=account,
        timestamp=T - timedelta(seconds=seconds_before),
        amount_rwf=amount,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        counterparty_id=counterparty,
    )


#: Amounts ascend in thousands with the row order, so every expected sum below is a small exact
#: integer and can be checked by hand rather than by re-running the code that produced it.
HISTORY_ROWS = [
    tx(seconds, amount=1000.0 * (i + 1), counterparty=counterparty)
    for i, (seconds, counterparty) in enumerate(HISTORY)
]

SCORED = Transaction(
    transaction_id="scored",
    account_id="A",
    timestamp=T,
    amount_rwf=50_000.0,
    latitude=KIGALI[0],
    longitude=KIGALI[1],
    counterparty_id="M9",
)


def warm(rows: list[Transaction] | None = None) -> OnlineFeatures:
    features = OnlineFeatures()
    for row in rows if rows is not None else HISTORY_ROWS:
        features.observe(row)
    return features


# --- the windows themselves ----------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_both_paths_read_the_same_window_from_the_registry() -> None:
    """Two parsers, one declared string, hand-computed expectations for each.

    Each path parses the registry's `window` for itself rather than sharing a helper: the window
    rule is exactly what parity mutations 2 and 3 exist to catch, so a shared parser would put it
    inside the surface parity cannot see. That makes agreement between the parsers something to
    assert rather than assume.
    """
    expected = {
        "tx_count_60s": timedelta(seconds=60),
        "tx_count_1h": timedelta(hours=1),
        "tx_count_24h": timedelta(hours=24),
        "tx_count_7d": timedelta(days=7),
        "amount_sum_24h": timedelta(hours=24),
        "amount_sum_7d": timedelta(days=7),
        "unique_counterparties_24h": timedelta(hours=24),
    }
    for name, span in expected.items():
        assert batch.window_of(name) == span, name
        assert online_window_of(name) == span, name


@pytest.mark.req("FR-02-02")
def test_a_feature_without_one_trailing_window_is_refused_by_both_parsers() -> None:
    """`velocity_ratio_1h_vs_30d` declares `1h/30d` and `amount_log1p` declares none.

    Returning a default — the longer of the pair, or zero — would give a caller a window that no
    feature declared, which is worse than refusing: the number produced would look like a feature.
    """
    for name in ("velocity_ratio_1h_vs_30d", "amount_log1p", "seconds_since_last_tx"):
        with pytest.raises(ValueError, match="window"):
            batch.window_of(name)
        with pytest.raises(ValueError, match="window"):
            online_window_of(name)


@pytest.mark.req("FR-02-02")
def test_the_fixture_sits_on_every_window_edge() -> None:
    """E12: the precondition the boundary assertions below depend on.

    Without a row exactly at each edge, every count test would pass under both an inclusive and an
    exclusive bound, which is the vacuous pass E12 was written for — and is the fixture defect that
    made M2's first fold-grouping test prove nothing.
    """
    offsets = {seconds for seconds, _ in HISTORY}
    for name in COUNTS:
        edge = int(batch.window_of(name).total_seconds())
        assert edge in offsets, f"no row sits exactly on {name}'s edge of {edge} s"
    assert 864_000 in offsets, "no row sits outside the longest window"


# --- counts ---------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_four_counts_against_hand_worked_expectations() -> None:
    """Counted by hand from `HISTORY`, with the edge row excluded from its own window each time.

    - 60 s: rows strictly inside (T-60s, T) -> {30} = **1**; the row at 60 s is on the edge.
    - 1 h: {30, 60, 90, 1800} = **4**; the row at 3600 s is on the edge.
    - 24 h: the four above plus {3600, 7200, 43200} = **7**; 86400 is on the edge.
    - 7 d: the seven above plus {86400, 259200} = **9**; 604800 is on the edge and 864000 is
      outside it.
    """
    expected = {"tx_count_60s": 1, "tx_count_1h": 4, "tx_count_24h": 7, "tx_count_7d": 9}
    features = warm()
    for name, count in expected.items():
        assert batch.tx_count(HISTORY_ROWS, SCORED, name) == count, name
        assert features.tx_count(SCORED, name) == count, name


@pytest.mark.req("FR-02-02")
def test_a_row_moved_across_an_edge_changes_the_count_on_both_paths() -> None:
    """The bound is exclusive, asserted by moving the edge row one second inside it.

    This is the assertion the fixture's edge rows exist for: it fails under `<=` where the code has
    `<`, on both paths, at each of the four windows.
    """
    for name in COUNTS:
        edge = int(batch.window_of(name).total_seconds())
        outside = [tx(edge, amount=1000.0, counterparty="M1")]
        inside = [tx(edge - 1, amount=1000.0, counterparty="M1")]
        assert batch.tx_count(outside, SCORED, name) == 0, f"{name}: the edge row was counted"
        assert batch.tx_count(inside, SCORED, name) == 1, f"{name}: the row inside was not"
        assert warm(outside).tx_count(SCORED, name) == 0, name
        assert warm(inside).tx_count(SCORED, name) == 1, name


@pytest.mark.req("FR-02-02")
def test_an_account_with_no_history_counts_zero_rather_than_nothing() -> None:
    """`nan_rule`: an account that has counted nothing has counted 0, which is not unknown.

    A NaN here would be indistinguishable from a genuinely quiet account, and quiet is the state
    the velocity features exist to contrast with.
    """
    for name in COUNTS:
        assert batch.tx_count([], SCORED, name) == 0
        assert OnlineFeatures().tx_count(SCORED, name) == 0


@pytest.mark.req("FR-02-02", "ML-GATE-01")
def test_another_accounts_rows_never_enter_this_accounts_velocity() -> None:
    """`history_key=ACCOUNT`, asserted rather than left to the caller.

    A mixed history produces a number that looks like a velocity and is an aggregate over
    strangers — plausible at every magnitude and silent. The precondition asserts the other
    account's rows really do fall inside the window, or this would pass over a fixture where the
    filter never had anything to remove.
    """
    intruder = [tx(s, amount=1000.0, counterparty="X", account="B") for s in (10, 20, 30)]
    assert all(SCORED.timestamp - timedelta(hours=24) < r.timestamp for r in intruder), (
        "precondition: the other account's rows are inside the window being counted"
    )
    assert batch.tx_count(HISTORY_ROWS + intruder, SCORED, "tx_count_24h") == 7
    assert warm(HISTORY_ROWS + intruder).tx_count(SCORED, "tx_count_24h") == 7


# --- sums -----------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_two_sums_against_hand_worked_expectations() -> None:
    """Amounts ascend in thousands with the row order, so the sums are exact integers.

    24 h: rows 1..7 -> 1000+2000+3000+4000+5000+6000+7000 = **28,000**.
    7 d: those plus rows 8 and 9 -> 28,000 + 8,000 + 9,000 = **45,000**.
    """
    features = warm()
    for name, total in (("amount_sum_24h", 28_000.0), ("amount_sum_7d", 45_000.0)):
        assert batch.amount_sum(HISTORY_ROWS, SCORED, name) == total, name
        assert features.amount_sum(SCORED, name) == total, name


@pytest.mark.req("FR-02-02")
def test_the_scored_transactions_own_amount_is_never_in_its_window() -> None:
    """`self_inclusion=EXCLUDED`. The scored amount is 50,000 — five times the whole 7 d window —
    so its inclusion could not hide inside a rounding argument."""
    assert SCORED.amount_rwf > batch.amount_sum(HISTORY_ROWS, SCORED, "amount_sum_7d"), (
        "precondition: including the scored amount would change the sum unmistakably"
    )
    assert batch.amount_sum([*HISTORY_ROWS, SCORED], SCORED, "amount_sum_7d") == 45_000.0


@pytest.mark.req("FR-02-02")
def test_an_empty_window_sums_to_zero_on_both_paths() -> None:
    assert batch.amount_sum([], SCORED, "amount_sum_24h") == 0.0
    assert OnlineFeatures().amount_sum(SCORED, "amount_sum_24h") == 0.0


# --- distinct counterparties ------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_unique_counterparties_counts_the_set_not_the_rows() -> None:
    """The seven rows inside 24 h pay M1, M1, M2, M3, M3, M3, M4 — **four** distinct.

    Seven rows and four counterparties, so a count of rows and a count of the set are different
    numbers in this fixture; if they were equal the test would pass under either implementation.
    """
    inside = [c for seconds, c in HISTORY if seconds < 86_400]
    assert len(inside) == 7, "precondition: the window holds seven rows"
    assert len(set(inside)) == 4, "precondition: a counterparty repeats inside the window"

    assert batch.unique_counterparties_24h(HISTORY_ROWS, SCORED) == 4
    assert warm().unique_counterparties_24h(SCORED) == 4


@pytest.mark.req("FR-02-02")
def test_a_row_without_a_counterparty_is_refused_on_both_paths() -> None:
    """Rows with no counterparty must not collapse into one unknown counterparty.

    They would make unrelated payments look like an established relationship — a *low* distinct
    count, which is the direction that reads as normal.
    """
    anonymous = Transaction(
        transaction_id="anon",
        account_id="A",
        timestamp=T - timedelta(hours=1),
        amount_rwf=1000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
    )
    assert anonymous.counterparty_id is None, "precondition: the row carries no counterparty"
    with pytest.raises(ValueError, match="counterparty"):
        batch.unique_counterparties_24h([anonymous], SCORED)
    with pytest.raises(ValueError, match="counterparty"):
        warm([anonymous]).unique_counterparties_24h(SCORED)
