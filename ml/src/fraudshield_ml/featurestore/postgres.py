"""The feature store's database fallback over M6's tables (C.4, FR-02-09; PB-69 carried from M5-3).

When an account's Redis keys have expired or Redis cannot answer, `FeatureStore` asks its
`Fallback` for everything those keys held. This is that fallback for a deployment: it reads the
transactions hypertable, `account_profiles` (PB-37) and the SIM-swap and KYC-tier histories (V67),
and returns what `featurestore.fallback.ReplayFallback`, the reference, returns for the same
history. `ml/tests/featurestore/test_db_fallback.py` holds it to that, field for field.

It reads as `fs_scorer`, which may call the `feature_fallback_*` functions V67 defines and
nothing else (ADR 0062 point 6). The Fallback protocol knows an account by its token alone, as the
Redis keys do, so those functions answer for a token rather than for an institution.

A fallback that cannot answer raises `FallbackUnavailableError`. It never returns ``None`` for a
failure, because ``None`` means "never seen", and a known account read as new would score its
history away. The store does not catch the error, so the scorer answers UNAVAILABLE and the API
decides on its rule-based fallback (C.4).
"""

from __future__ import annotations

import contextlib
import threading
from collections.abc import Callable
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any, Protocol
from urllib.parse import urlsplit

from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.store import ACCOUNT_HORIZON, Durable

#: How long one fallback statement may take. The fallback is on the scorer's synchronous path, so
#: a stalled database must fail the read rather than hold it (the Java hot path's reads have the
#: same kind of bound, ADR 0062).
DEFAULT_STATEMENT_TIMEOUT_MS = 100


class FallbackUnavailableError(RuntimeError):
    """The database could not answer; the account's state is unknown, not empty."""


class Connection(Protocol):
    """The part of a DB-API connection the reader uses."""

    def cursor(self) -> Any: ...

    def commit(self) -> None: ...

    def rollback(self) -> None: ...

    def close(self) -> None: ...


class PostgresFallback:
    """`Fallback` over PostgreSQL, as `fs_scorer`.

    `connect` opens a DB-API connection (pg8000's, in the scorer). One connection is kept and
    reused under a lock, and replaced after any failure.
    """

    def __init__(
        self,
        connect: Callable[[], Connection],
        minor_units: dict[str, int],
        *,
        statement_timeout_ms: int = DEFAULT_STATEMENT_TIMEOUT_MS,
    ) -> None:
        self._connect = connect
        self._minor_units = dict(minor_units)
        self._timeout_ms = int(statement_timeout_ms)
        self._lock = threading.Lock()
        self._connection: Connection | None = None

    def account(self, account_id: str, before: datetime) -> Durable | None:
        """The account's state strictly before `before`; None if the database has never seen it."""
        since = before - timedelta(microseconds=ACCOUNT_HORIZON)
        summary, transactions, swaps, tiers = self._query(
            lambda cur: (
                _one(cur, "SELECT * FROM feature_fallback_account(%s, %s)", (account_id, before)),
                _all(
                    cur,
                    "SELECT * FROM feature_fallback_transactions(%s, %s, %s)",
                    (account_id, since, before),
                ),
                _all(cur, "SELECT * FROM feature_fallback_sim_swaps(%s)", (account_id,)),
                _all(cur, "SELECT * FROM feature_fallback_kyc_tiers(%s)", (account_id,)),
            )
        )
        first_seen, last_at, last_lat, last_lon, counterparties, countries, devices, opened = (
            summary
        )
        if first_seen is None and not (swaps or tiers or opened):
            return None
        return Durable(
            first_seen=first_seen,
            last_at=last_at,
            last_location=(float(last_lat), float(last_lon)) if last_at is not None else None,
            counterparties=frozenset(counterparties or ()),
            countries=frozenset(c.strip() for c in countries or ()),
            devices=frozenset(devices or ()),
            transactions=tuple(self._transaction(account_id, row) for row in transactions),
            sim_swaps=tuple(row[0] for row in swaps),
            tiers=tuple((int(row[0]), row[1]) for row in tiers),
            opened_at=opened,
        )

    def device_first_seen(self, device: str, before: datetime) -> datetime | None:
        """When the device first appeared, on any account, strictly before `before`."""
        row = self._query(
            lambda cur: _one(
                cur, "SELECT feature_fallback_device_first_seen(%s, %s)", (device, before)
            )
        )
        found: datetime | None = row[0]
        return found

    def close(self) -> None:
        with self._lock:
            self._drop()

    def _query(self, work: Callable[[Any], Any]) -> Any:
        with self._lock:
            try:
                if self._connection is None:
                    self._connection = self._open()
                cursor = self._connection.cursor()
                try:
                    return work(cursor)
                finally:
                    # Read-only work: end the transaction so no snapshot is held between reads.
                    self._connection.rollback()
            except Exception as error:
                self._drop()
                raise FallbackUnavailableError(
                    "the feature store's database fallback failed"
                ) from error

    def _open(self) -> Connection:
        connection = self._connect()
        cursor = connection.cursor()
        cursor.execute(f"SET statement_timeout = {self._timeout_ms}")
        cursor.execute("SET default_transaction_read_only = on")
        # A DB-API driver opens a transaction for these SETs; committing keeps them for the
        # session. A rollback here would undo both (the independent review, 2026-09-23).
        connection.commit()
        return connection

    def _drop(self) -> None:
        if self._connection is not None:
            # The connection is being discarded; a failure to close it changes nothing.
            with contextlib.suppress(Exception):
                self._connection.close()
            self._connection = None

    def _transaction(self, account_id: str, row: tuple[Any, ...]) -> Transaction:
        (
            transaction_id,
            timestamp,
            amount,
            currency,
            amount_rwf,
            latitude,
            longitude,
            account_country,
            counterparty,
            counterparty_country,
            channel,
            device,
            agent,
            mcc,
        ) = row
        currency = currency.strip()
        return Transaction(
            transaction_id=str(transaction_id),
            account_id=account_id,
            timestamp=timestamp,
            amount_rwf=float(amount_rwf),
            latitude=float(latitude),
            longitude=float(longitude),
            account_country=account_country.strip() if account_country else None,
            counterparty_country=counterparty_country.strip() if counterparty_country else None,
            counterparty_id=counterparty,
            amount_minor=_minor(amount, self._minor_units.get(currency)),
            currency=currency,
            channel=channel,
            device_fingerprint=device,
            agent_id=agent,
            merchant_category_code=mcc.strip() if mcc else None,
        )


def connector(url: str, password: str, *, timeout_s: float = 2.0) -> Callable[[], Connection]:
    """Opens pg8000 connections for `url` (postgresql://user@host:port/database).

    The password comes from the environment, never from the URL or the command line. The socket
    timeout bounds connecting and every read, so an unreachable database fails the read rather
    than holding the fallback's lock.
    """
    parts = urlsplit(url)
    if parts.scheme not in ("postgresql", "postgres") or not parts.hostname or not parts.path:
        raise ValueError("the feature store database is postgresql://user@host:port/database")
    if parts.password:
        raise ValueError("the feature store database password comes from the environment")
    options = {
        "user": parts.username or "fs_scorer",
        "host": parts.hostname,
        "port": parts.port or 5432,
        "database": parts.path.lstrip("/"),
        "password": password,
        "timeout": timeout_s,
    }

    def connect() -> Connection:
        import pg8000.dbapi  # noqa: PLC0415 - only a deployment with a database needs the driver

        connection: Connection = pg8000.dbapi.connect(**options)
        return connection

    return connect


def _minor(amount: Decimal, units: int | None) -> int | None:
    """The amount in minor units, or None where it is not a whole number of them."""
    if units is None:
        return None
    scaled = Decimal(amount).scaleb(units)
    return int(scaled) if scaled == scaled.to_integral_value() else None


def _one(cursor: Any, sql: str, parameters: tuple[Any, ...]) -> tuple[Any, ...]:
    cursor.execute(sql, parameters)
    row = cursor.fetchone()
    if row is None:
        raise FallbackUnavailableError(f"no row from {sql.split('(', 1)[0]}")
    return tuple(row)


def _all(cursor: Any, sql: str, parameters: tuple[Any, ...]) -> list[tuple[Any, ...]]:
    cursor.execute(sql, parameters)
    return [tuple(row) for row in cursor.fetchall()]
