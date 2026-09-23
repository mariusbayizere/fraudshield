"""Acceptance test for the database fallback carried to M6 (M5 review finding M5-3).

FR-02-09: "stale key handled by DB fallback"; C.4: "Redis down -> DB fallback for velocity". The
criterion carried to M6 is exact:

    **With the account's Redis state expired, a read served through the fallback yields the same
    account context, and so the same 44 features, as a read from Redis that still holds it.**

The test replays a corpus twice: store A keeps every key, and store B loses the scored account's
keys before each read, as a 30-day TTL does to an idle account. B is answered by the fallback under
test. Every read must match A's field for field, with the full-precision ages, and B must say it
was degraded.

`ReplayFallback` is the reference. M6's PostgreSQL reader (`featurestore.postgres`) is the second
parameter: it runs against TimescaleDB in Docker, with the schema built from the repository's own
bootstrap and Flyway migrations, and reads as the `fs_scorer` role the deployment gives the scorer.
The corpus uses short names ("A0", "t0001"); the database holds tokens and UUIDs, so the harness
maps them on the way in and back on the way out, and compares in the corpus's terms. Coordinates
are rounded to six decimals, the precision the API accepts and the database stores (`numeric(9,
6)`).
"""

from __future__ import annotations

import random
import shutil
import socket
import subprocess
import time
import uuid
from collections import defaultdict
from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import fakeredis
import pg8000.dbapi
import pg8000.native
import pytest
from prometheus_client import CollectorRegistry

from fraudshield_ml.features.types import CountryFacts, Transaction
from fraudshield_ml.featurestore.fallback import ReplayFallback
from fraudshield_ml.featurestore.postgres import FallbackUnavailableError, PostgresFallback
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.featurestore.store import FeatureStore, StoreMetrics

START = datetime(2025, 1, 5, tzinfo=UTC)
REFERENCE = Reference(
    countries={
        "RW": CountryFacts("RW", "AF", frozenset({"EAC"}), 2),
        "KE": CountryFacts("KE", "AF", frozenset({"EAC"}), 3),
    },
    country_of_currency={"RWF": "RW", "KES": "KE"},
    minor_units={"RWF": 0, "KES": 2},
    denominations={"RWF": (1000,)},
)

#: The image and schema the deployment runs (docker-compose.yml, backend/persistence).
TIMESCALE_IMAGE = "timescale/timescaledb:2.30.0-pg16"
DB = Path(__file__).resolve().parents[3] / "backend/persistence/src/main/resources/db"
INSTITUTION = "7d3c1f0e-5b2a-4c9d-8e7f-6a5b4c3d2e1f"


def _docker() -> str:
    return shutil.which("docker") or "docker"


class _Database:
    """A TimescaleDB container with the FraudShield schema, for the life of the test module."""

    def __init__(self) -> None:
        self.port = _free_port()
        self.password = uuid.uuid4().hex
        self.scorer_password = uuid.uuid4().hex
        self.container = subprocess.run(  # noqa: S603
            [
                _docker(),
                "run",
                "-d",
                "--rm",
                "-e",
                f"POSTGRES_PASSWORD={self.password}",
                "-e",
                "POSTGRES_DB=fraudshield_db",
                "-v",
                f"{DB}:/db:ro",
                "-p",
                f"127.0.0.1:{self.port}:5432",
                TIMESCALE_IMAGE,
            ],
            capture_output=True,
            text=True,
            check=True,
            timeout=600,
        ).stdout.strip()
        try:
            self._wait()
            self._psql("postgres", "-f", "/db/bootstrap/bootstrap.sql")
            self._psql("postgres", "-c", f"ALTER ROLE fs_scorer PASSWORD '{self.scorer_password}'")
            migrations = sorted(
                (DB / "migration").glob("V*__*.sql"), key=lambda f: int(f.name[1:].split("__")[0])
            )
            for migration in migrations:
                self._psql("fs_migrator", "-f", f"/db/migration/{migration.name}")
            self.admin = self.connect("postgres", self.password)
            self.admin.autocommit = True
            self.admin.run(
                "INSERT INTO fraudshield.institutions (id, code, name, country)"
                " VALUES (CAST(:i AS uuid), 'fallback-test', 'Fallback Test', 'RW')",
                i=INSTITUTION,
            )
            self.admin.run(
                "SELECT set_config('fraudshield.institution_id', :i, false)", i=INSTITUTION
            )
        except BaseException:
            self.close()
            raise

    def _wait(self) -> None:
        deadline = time.monotonic() + 120
        while True:
            ready = subprocess.run(  # noqa: S603
                [
                    _docker(),
                    "exec",
                    self.container,
                    "pg_isready",
                    "-U",
                    "postgres",
                    "-h",
                    "127.0.0.1",
                ],
                capture_output=True,
                check=False,
            )
            if ready.returncode == 0:
                # The image restarts the server once after its init scripts; wait that out too.
                time.sleep(2)
                if (
                    subprocess.run(  # noqa: S603
                        [
                            _docker(),
                            "exec",
                            self.container,
                            "pg_isready",
                            "-U",
                            "postgres",
                            "-h",
                            "127.0.0.1",
                        ],
                        capture_output=True,
                        check=False,
                    ).returncode
                    == 0
                ):
                    return
            if time.monotonic() > deadline:
                raise TimeoutError("TimescaleDB did not start")
            time.sleep(0.5)

    def _psql(self, user: str, *args: str) -> None:
        subprocess.run(  # noqa: S603
            [
                _docker(),
                "exec",
                self.container,
                "psql",
                "-v",
                "ON_ERROR_STOP=1",
                "-q",
                "-U",
                user,
                "-d",
                "fraudshield_db",
                *args,
            ],
            capture_output=True,
            text=True,
            check=True,
            timeout=300,
        )

    def connect(self, user: str, password: str) -> Any:
        return pg8000.native.Connection(
            user, host="127.0.0.1", port=self.port, database="fraudshield_db", password=password
        )

    def scorer(self) -> Any:
        return pg8000.dbapi.connect(
            user="fs_scorer",
            host="127.0.0.1",
            port=self.port,
            database="fraudshield_db",
            password=self.scorer_password,
        )

    def close(self) -> None:
        subprocess.run([_docker(), "stop", self.container], capture_output=True, check=False)  # noqa: S603


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


class _Names:
    """Corpus names to schema values and back: tokens for accounts, counterparties and devices,
    UUIDs for transaction ids."""

    def __init__(self) -> None:
        self.back: dict[str, str] = {}

    def token(self, name: str | None) -> str | None:
        if name is None:
            return None
        token = "tok_" + name + "0" * (24 - len(name))
        self.back[token] = name
        return token

    def txid(self, name: str) -> str:
        value = str(uuid.uuid5(uuid.NAMESPACE_URL, "fallback-test/" + name))
        self.back[value] = name
        return value

    def name(self, value: str) -> str:
        return self.back[value]

    def optional(self, value: str | None) -> str | None:
        return None if value is None else self.back[value]


class _Appending(defaultdict[str, Any]):
    """`fallback.sim_swaps[account].append(at)`, written to the database."""

    def __init__(self, write: Callable[[str, Any], None]) -> None:
        super().__init__()
        self._write = write

    def __missing__(self, account: str) -> Any:
        write = self._write

        class _List(list[Any]):
            def append(self, value: Any) -> None:
                write(account, value)
                super().append(value)

        self[account] = _List()
        return self[account]


class _Opened(dict[str, datetime]):
    """`fallback.opened_at[account] = at`, written to the database."""

    def __init__(self, write: Callable[[str, datetime], None]) -> None:
        super().__init__()
        self._write = write

    def __setitem__(self, account: str, at: datetime) -> None:
        self._write(account, at)
        super().__setitem__(account, at)


class _PostgresUnderTest:
    """M6's reader, written to as the API's persistence path writes (PostgresSink) and read back in
    the corpus's names. Exposes the same writing surface as `ReplayFallback`."""

    def __init__(self, db: _Database) -> None:
        self.db = db
        self.names = _Names()
        self.reader = PostgresFallback(
            db.scorer, dict(REFERENCE.minor_units), statement_timeout_ms=10_000
        )
        self.sim_swaps = _Appending(self._swap)
        self.tiers = _Appending(self._tier)
        self.opened_at = _Opened(self._opened)

    def _swap(self, account: str, at: datetime) -> None:
        self.db.admin.run(
            "INSERT INTO fraudshield.account_sim_swaps (institution_id, account_token, swapped_at)"
            " VALUES (CAST(:i AS uuid), :a, :t)",
            i=INSTITUTION,
            a=self.names.token(account),
            t=at,
        )

    def _tier(self, account: str, entry: tuple[int, datetime]) -> None:
        self.db.admin.run(
            "INSERT INTO fraudshield.account_kyc_tiers (institution_id, account_token, tier,"
            " effective_at) VALUES (CAST(:i AS uuid), :a, :k, :t)",
            i=INSTITUTION,
            a=self.names.token(account),
            k=entry[0],
            t=entry[1],
        )

    def _opened(self, account: str, at: datetime) -> None:
        self.db.admin.run(
            "INSERT INTO fraudshield.account_profiles (institution_id, account_token, opened_at)"
            " VALUES (CAST(:i AS uuid), :a, :t) ON CONFLICT (institution_id, account_token)"
            " DO UPDATE SET opened_at = EXCLUDED.opened_at"
            " WHERE account_profiles.opened_at IS NULL",
            i=INSTITUTION,
            a=self.names.token(account),
            t=at,
        )

    def record(self, tx: Transaction) -> None:
        minor_units = REFERENCE.minor_units[tx.currency or "RWF"]
        self.db.admin.run(
            "INSERT INTO fraudshield.transactions (institution_id, transaction_id, account_token,"
            " counterparty_token, amount, currency, amount_rwf, channel, merchant_category_code,"
            " latitude, longitude, device_token, agent_token, counterparty_country,"
            " transaction_timestamp, received_at) VALUES (CAST(:i AS uuid), CAST(:x AS uuid), :a,"
            " :c, :amount, :cur, :rwf, :ch, :mcc, :lat, :lon, :d, :g, :cc, :t, :t)",
            i=INSTITUTION,
            x=self.names.txid(tx.transaction_id),
            a=self.names.token(tx.account_id),
            c=self.names.token(tx.counterparty_id),
            amount=str((tx.amount_minor or 0) / 10**minor_units),
            cur=tx.currency,
            rwf=repr(tx.amount_rwf),
            ch=tx.channel,
            mcc=tx.merchant_category_code,
            lat=repr(tx.latitude),
            lon=repr(tx.longitude),
            d=self.names.token(tx.device_fingerprint),
            g=self.names.token(tx.agent_id),
            cc=tx.counterparty_country,
            t=tx.timestamp,
        )
        # As PostgresSink does: first appearance only ever moves earlier.
        self.db.admin.run(
            "INSERT INTO fraudshield.account_profiles"
            " (institution_id, account_token, first_seen_at)"
            " VALUES (CAST(:i AS uuid), :a, :t) ON CONFLICT (institution_id, account_token)"
            " DO UPDATE SET first_seen_at = EXCLUDED.first_seen_at"
            " WHERE account_profiles.first_seen_at IS NULL"
            " OR EXCLUDED.first_seen_at < account_profiles.first_seen_at",
            i=INSTITUTION,
            a=self.names.token(tx.account_id),
            t=tx.timestamp,
        )

    def account(self, account_id: str, before: datetime) -> Any:
        durable = self.reader.account(str(self.names.token(account_id)), before)
        if durable is None:
            return None
        n = self.names
        return replace(
            durable,
            counterparties=frozenset(n.name(c) for c in durable.counterparties),
            devices=frozenset(n.name(d) for d in durable.devices),
            transactions=tuple(
                replace(
                    t,
                    transaction_id=n.name(t.transaction_id),
                    account_id=account_id,
                    counterparty_id=n.optional(t.counterparty_id),
                    device_fingerprint=n.optional(t.device_fingerprint),
                    agent_id=n.optional(t.agent_id),
                )
                for t in durable.transactions
            ),
        )

    def device_first_seen(self, device: str, before: datetime) -> datetime | None:
        return self.reader.device_first_seen(str(self.names.token(device)), before)


_DATABASE: _Database | None = None


def _postgres() -> _PostgresUnderTest:
    global _DATABASE  # noqa: PLW0603 - one container for the module, started on first use
    if _DATABASE is None:
        _DATABASE = _Database()
    return _PostgresUnderTest(_DATABASE)


@pytest.fixture(scope="module", autouse=True)
def _stop_database() -> Any:
    yield
    global _DATABASE  # noqa: PLW0603
    if _DATABASE is not None:
        _DATABASE.close()
        _DATABASE = None


#: Each fallback implementation under test.
FALLBACKS = [
    pytest.param(ReplayFallback, id="reference"),
    pytest.param(_postgres, id="m6-postgresql", marks=pytest.mark.requires_docker),
]


def corpus(rows: int = 220, seed: int = 20260922) -> list[Transaction]:
    """Six accounts, some idle past the 30-day TTL but inside the 90-day window, so a lost key
    really does take rows the features still read."""
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    out: list[Transaction] = []
    when = START
    for i in range(rows):
        when += timedelta(
            seconds=rng.choice([30, 600, 7_200, 90_000])
            if rng.random() < 0.9
            else rng.choice([35 * 86_400, 50 * 86_400])
        )
        account = f"A{rng.randrange(6)}"
        device = None if rng.random() < 0.2 else f"D{rng.randrange(4)}"
        out.append(
            Transaction(
                transaction_id=f"t{i:04d}",
                account_id=account,
                timestamp=when,
                amount_rwf=float(rng.choice([900, 1500, 4200, 30_000, 120_000])),
                latitude=round(-1.95 + 0.3 * rng.randrange(3), 6),
                longitude=round(30.06 + 0.3 * rng.randrange(2), 6),
                account_country="RW",
                counterparty_country=rng.choice(["RW", "RW", "KE"]),
                counterparty_id=f"C{rng.randrange(5)}",
                amount_minor=1000,
                currency="RWF",
                channel="MOBILE_MONEY" if device else "USSD",
                device_fingerprint=device,
                merchant_category_code="5411",
            )
        )
    return out


def _store(fallback: object | None, *, authoritative: bool) -> FeatureStore:
    return FeatureStore(
        fakeredis.FakeRedis(decode_responses=True),
        REFERENCE,
        fallback=fallback,  # type: ignore[arg-type]
        authoritative=authoritative,
        metrics=StoreMetrics.create(CollectorRegistry()),
    )


def _expire_account(store: FeatureStore, account: str, device: str | None = None) -> None:
    for key in store.redis.keys(f"{store.prefix}a:{account}:*"):
        store.redis.delete(key)
    if device is not None:
        # The device's first sighting expires too, so the fallback is asked about devices the
        # database has seen, not only ones it has not (the independent review, 2026-09-23).
        store.redis.delete(f"{store.prefix}d:{device}:first")


@pytest.mark.req("FR-02-09")
@pytest.mark.parametrize("make", FALLBACKS)
def test_a_read_through_the_fallback_equals_the_redis_read(
    make: Callable[[], Any] | None,
) -> None:
    assert make is not None
    fallback = make()
    redis_path = _store(None, authoritative=True)
    fallback_path = _store(fallback, authoritative=False)

    swaps = {"A1": START + timedelta(days=9), "A4": START + timedelta(days=40)}
    tiers = {"A0": [(1, START - timedelta(days=5)), (3, START + timedelta(days=60))]}
    opened = {"A2": START - timedelta(days=12, hours=3), "A3": START - timedelta(days=400)}
    for account, at in swaps.items():
        redis_path.record_sim_swap(account, at)
        fallback.sim_swaps[account].append(at)
    for account, history in tiers.items():
        for tier, at in history:
            redis_path.set_kyc_tier(account, tier, at)
            fallback.tiers[account].append((tier, at))
    for account, at in opened.items():
        redis_path.set_opened_at(account, at)
        fallback.opened_at[account] = at

    compared = expired_rows = 0
    for tx in corpus():
        _expire_account(fallback_path, tx.account_id, tx.device_fingerprint)
        expected = redis_path.read(tx)
        got = fallback_path.read(tx)
        assert got.context == expected.context, f"{tx.transaction_id}: contexts differ"
        assert got.exact_ages == expected.exact_ages, f"{tx.transaction_id}: ages differ"
        assert got.degraded, "a read answered by the fallback is DEGRADED_MODE (C.4)"
        assert not expected.degraded
        compared += 1
        expired_rows += expected.context.history_count_90d
        redis_path.observe(tx)
        fallback_path.observe(tx)
        fallback.record(tx)
    assert compared == 220
    assert expired_rows > 0, "the fallback must have rebuilt real window rows, not empty ones"


def test_an_account_the_database_has_never_seen_is_new_not_unknown() -> None:
    store = _store(ReplayFallback(), authoritative=False)
    read = store.read(corpus(1)[0])
    assert read.context.mean_hourly_count_30d == 0.0, "first transaction: its own baseline"
    assert read.degraded


@pytest.mark.requires_docker
@pytest.mark.req("FR-02-09")
def test_the_database_session_is_bounded_and_read_only() -> None:
    """The reader's SETs must outlive the transaction the driver opened for them."""
    db = _postgres().db
    reader = PostgresFallback(db.scorer, dict(REFERENCE.minor_units), statement_timeout_ms=200)
    assert reader.account("tok_" + "N" * 24, START) is None
    connection = reader._connection
    assert connection is not None
    cursor = connection.cursor()
    cursor.execute("SHOW statement_timeout")
    assert cursor.fetchone()[0] == "200ms"
    cursor.execute("SHOW transaction_read_only")
    assert cursor.fetchone()[0] == "on"
    connection.rollback()
    with pytest.raises(pg8000.dbapi.DatabaseError):
        cursor.execute("SELECT pg_sleep(1)")
    reader.close()


@pytest.mark.requires_docker
@pytest.mark.req("FR-02-09")
def test_at_the_production_bounds_a_timed_out_statement_does_not_refuse_the_next_read() -> None:
    """The deployment's defaults (100 ms statements, 200 ms socket) against a real server: a
    statement PostgreSQL cancels fails its own read, and the next read is served."""
    db = _postgres().db
    reader = PostgresFallback(db.scorer, dict(REFERENCE.minor_units))
    assert reader.account("tok_" + "Q" * 24, START) is None
    kept = reader._connection
    with pytest.raises(FallbackUnavailableError):
        reader._query(lambda cur: cur.execute("SELECT pg_sleep(0.3)"))
    assert reader.account("tok_" + "Q" * 24, START) is None, "served, not refused"
    assert reader._connection is kept
    reader.close()
