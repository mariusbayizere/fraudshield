"""The PostgreSQL fallback's behaviour when the database cannot answer (C.4, ADR 0062 point 6).

Equality with the Redis path is `test_db_fallback.py`'s job, against a real database. These tests
hold the reader to the rule that matters when that database is unhealthy: a failure is never read
as "never seen", and a failed connection is not reused.
"""

from __future__ import annotations

import threading
import time
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

import pg8000.dbapi
import pytest

from fraudshield_ml.featurestore.postgres import (
    FallbackUnavailableError,
    PostgresFallback,
    connector,
)

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

    def commit(self) -> None:
        self.statements.append("COMMIT")

    def rollback(self) -> None:
        self.statements.append("ROLLBACK")

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
    settings_end = connection.statements.index("SET default_transaction_read_only = on") + 1
    assert connection.statements[settings_end] == "COMMIT", "a rollback would undo the SETs"


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

    now = [0.0]
    reader = PostgresFallback(connect, {"KES": 2}, clock=lambda: now[0])
    with pytest.raises(FallbackUnavailableError):
        reader.account(ACCOUNT, AT)
    assert connections[0].closed, "a connection that failed is not reused"
    now[0] += 6.0  # past the cool-down
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


def test_the_connector_takes_the_password_from_the_caller_and_bounds_the_socket(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seen: dict[str, Any] = {}
    monkeypatch.setattr(pg8000.dbapi, "connect", lambda **kw: seen.update(kw) or _Connection({}))
    connector("postgresql://fs_scorer@db.internal:6543/fraudshield_db", "s3cret", timeout_s=1.5)()
    assert seen == {
        "user": "fs_scorer",
        "host": "db.internal",
        "port": 6543,
        "database": "fraudshield_db",
        "password": "s3cret",
        "timeout": 1.5,
    }
    for bad in ("mysql://x@h/d", "postgresql://fs_scorer:pw@h/d", "postgresql:///d"):
        with pytest.raises(ValueError, match="feature store database"):
            connector(bad, "p")


@pytest.mark.req("FR-02-09")
def test_a_blackholed_database_fails_reads_fast_and_is_not_retried_during_the_cool_down() -> None:
    """The delta review's D1: four concurrent reads against an unanswering database finished at
    2, 4, 6 and 8 s. Now one attempt is made, the others give up within the lock wait, and reads
    in the cool-down fail at once without connecting."""
    attempts: list[float] = []
    now = [0.0]

    def blackhole() -> _Connection:
        attempts.append(time.monotonic())
        time.sleep(0.3)  # the socket timeout expiring
        raise TimeoutError("connect timed out")

    reader = PostgresFallback(blackhole, {}, clock=lambda: now[0])
    failures: list[BaseException] = []

    def read() -> None:
        try:
            reader.account(ACCOUNT, AT)
        except BaseException as error:
            failures.append(error)

    started = time.monotonic()
    threads = [threading.Thread(target=read) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert time.monotonic() - started < 1.0
    assert len(failures) == 4
    assert all(isinstance(f, FallbackUnavailableError) for f in failures)
    assert len(attempts) == 1, "one connection attempt, not one per read"
    with pytest.raises(FallbackUnavailableError, match="failed recently"):
        reader.device_first_seen("tok_D", AT)
    assert len(attempts) == 1, "no attempt during the cool-down"
    now[0] += 6.0
    with pytest.raises(FallbackUnavailableError):
        reader.device_first_seen("tok_D", AT)
    assert len(attempts) == 2, "after the cool-down the database is tried again"
