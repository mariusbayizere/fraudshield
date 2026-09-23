"""The PostgreSQL fallback's behaviour when the database cannot answer (C.4, ADR 0062 point 6).

Equality with the Redis path is `test_db_fallback.py`'s job, against a real database. These tests
hold the reader to the rule that matters when that database is unhealthy: a failure is never read
as "never seen", and a failed connection is not reused.
"""

from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import pytest

from fraudshield_ml.featurestore.postgres import FallbackUnavailableError, PostgresFallback

AT = datetime(2026, 9, 1, tzinfo=UTC)
ACCOUNT = "tok_" + "A" * 24


class _Cursor:
    def __init__(self, connection: _Connection) -> None:
        self.connection = connection
        self.rows: list[tuple[Any, ...]] = []

    def execute(self, sql: str, parameters: tuple[Any, ...] = ()) -> None:
        self.connection.statements.append(sql)
        if self.connection.fail_on and self.connection.fail_on in sql:
            raise OSError("connection reset")
        for prefix, rows in self.connection.answers.items():
            if prefix in sql:
                self.rows = list(rows)
                return
        self.rows = []

    def fetchone(self) -> tuple[Any, ...] | None:
        return self.rows[0] if self.rows else None

    def fetchall(self) -> list[tuple[Any, ...]]:
        return self.rows


class _Connection:
    def __init__(self, answers: dict[str, list[tuple[Any, ...]]], fail_on: str = "") -> None:
        self.answers = answers
        self.fail_on = fail_on
        self.statements: list[str] = []
        self.closed = False

    def cursor(self) -> _Cursor:
        return _Cursor(self)

    def rollback(self) -> None:
        pass

    def close(self) -> None:
        self.closed = True


NEVER_SEEN: dict[str, list[tuple[Any, ...]]] = {
    "feature_fallback_account": [(None, None, None, None, [], [], [], None)],
}

SEEN: dict[str, list[tuple[Any, ...]]] = {
    "feature_fallback_account": [
        (AT, AT, Decimal("-1.950000"), Decimal("30.060000"), ["tok_C"], ["RW "], ["tok_D"], None)
    ],
    "feature_fallback_transactions": [
        (
            "3f1c9b6e-0d2a-4b8c-9e7f-1a2b3c4d5e6f",
            AT,
            Decimal("1500.0000"),
            "KES",
            Decimal("15000.0000"),
            Decimal("-1.950000"),
            Decimal("30.060000"),
            "RW",
            "tok_C",
            "KE",
            "CARD",
            "tok_D",
            None,
            "5411",
        ),
        (
            "4f1c9b6e-0d2a-4b8c-9e7f-1a2b3c4d5e6f",
            AT,
            Decimal("10.5050"),
            "KES",
            Decimal("1300.0000"),
            Decimal("-1.950000"),
            Decimal("30.060000"),
            None,
            "tok_C",
            None,
            "USSD",
            None,
            None,
            None,
        ),
    ],
    "feature_fallback_sim_swaps": [(AT,)],
    "feature_fallback_kyc_tiers": [(2, AT)],
    "feature_fallback_device_first_seen": [(AT,)],
}


def test_a_database_that_has_never_seen_the_account_answers_none() -> None:
    connection = _Connection(NEVER_SEEN)
    reader = PostgresFallback(lambda: connection, {"KES": 2})
    assert reader.account(ACCOUNT, AT) is None
    assert any("statement_timeout = 100" in s for s in connection.statements)
    assert any("default_transaction_read_only" in s for s in connection.statements)


@pytest.mark.req("FR-02-09")
def test_rows_become_the_durable_state_the_store_rebuilds_from() -> None:
    reader = PostgresFallback(lambda: _Connection(SEEN), {"KES": 2})
    durable = reader.account(ACCOUNT, AT)
    assert durable is not None
    assert durable.last_location == (-1.95, 30.06)
    assert durable.countries == frozenset({"RW"})
    assert durable.sim_swaps == (AT,)
    assert durable.tiers == ((2, AT),)
    first, second = durable.transactions
    assert first.amount_minor == 150_000
    assert first.currency == "KES"
    assert first.account_country == "RW"
    assert first.device_fingerprint == "tok_D"
    assert second.amount_minor is None, "10.505 KES is not a whole number of cents"
    assert second.account_country is None
    assert second.merchant_category_code is None
    assert reader.device_first_seen("tok_D", AT) == AT


def test_a_currency_without_minor_units_leaves_the_minor_amount_unknown() -> None:
    durable = PostgresFallback(lambda: _Connection(SEEN), {}).account(ACCOUNT, AT)
    assert durable is not None
    assert all(t.amount_minor is None for t in durable.transactions)


@pytest.mark.req("FR-02-09")
def test_a_failure_is_unavailable_never_never_seen_and_the_connection_is_replaced() -> None:
    connections: list[_Connection] = []

    def connect() -> _Connection:
        connections.append(_Connection(SEEN, fail_on="feature_fallback_transactions"))
        return connections[-1]

    reader = PostgresFallback(connect, {"KES": 2})
    with pytest.raises(FallbackUnavailableError):
        reader.account(ACCOUNT, AT)
    assert connections[0].closed, "a connection that failed is not reused"
    with pytest.raises(FallbackUnavailableError):
        reader.account(ACCOUNT, AT)
    assert len(connections) == 2
    reader.close()
    assert connections[1].closed


def test_an_unreachable_database_is_unavailable() -> None:
    def refuse() -> _Connection:
        raise ConnectionRefusedError("no route")

    reader = PostgresFallback(refuse, {})
    with pytest.raises(FallbackUnavailableError):
        reader.device_first_seen("tok_D", AT)


def test_a_function_that_returns_no_row_is_a_failure() -> None:
    reader = PostgresFallback(lambda: _Connection({}), {})
    with pytest.raises(FallbackUnavailableError):
        reader.account(ACCOUNT, AT)


def test_closing_a_connection_that_fails_to_close_still_forgets_it() -> None:
    class _Stubborn(_Connection):
        def close(self) -> None:
            raise OSError("already gone")

    connection = _Stubborn(NEVER_SEEN)
    reader = PostgresFallback(lambda: connection, {}, statement_timeout_ms=250)
    reader.account(ACCOUNT, AT)
    assert any("statement_timeout = 250" in s for s in connection.statements)
    reader.close()
    reader.close()
