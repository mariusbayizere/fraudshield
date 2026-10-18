"""Acceptance test for the database fallback carried to M6 (M5 review finding M5-3).

FR-02-09: "stale key handled by DB fallback"; C.4: "Redis down -> DB fallback for velocity". The
criterion carried to M6 is exact:

    **With the account's Redis state expired, a read served through the fallback yields the same
    account context, and so the same 44 features, as a read from Redis that still holds it.**

The test replays a corpus twice: store A keeps every key, and store B loses the scored account's
keys before each read, as a 30-day TTL does to an idle account. B is answered by the fallback under
test. Every read must match A's field for field, with the full-precision ages, and B must say it
was degraded.

`ReplayFallback` is the reference and runs now. M6 adds its PostgreSQL reader as a parameter
(`FALLBACKS`); until then that parameter is skipped with the reason named, and the traceability row
proposed in `docs/parallel/M5_updates.md` stays open.
"""

from __future__ import annotations

import random
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

import fakeredis
import pytest
from prometheus_client import CollectorRegistry

from fraudshield_ml.features.types import CountryFacts, Transaction
from fraudshield_ml.featurestore.fallback import ReplayFallback
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

#: Each fallback implementation under test: a factory, or a skip naming who delivers it.
FALLBACKS = [
    pytest.param(ReplayFallback, id="reference"),
    pytest.param(
        None,
        id="m6-postgresql",
        marks=pytest.mark.skip(
            reason="M6 delivers the PostgreSQL Fallback over account_velocity_cache, the "
            "transactions hypertable and PB-37's durable table; it must pass this test (M5-3)"
        ),
    ),
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
                latitude=-1.95 + 0.3 * rng.randrange(3),
                longitude=30.06 + 0.3 * rng.randrange(2),
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


def _expire_account(store: FeatureStore, account: str) -> None:
    for key in store.redis.keys(f"{store.prefix}a:{account}:*"):
        store.redis.delete(key)


@pytest.mark.req("FR-02-09")
@pytest.mark.parametrize("make", FALLBACKS)
def test_a_read_through_the_fallback_equals_the_redis_read(
    make: Callable[[], ReplayFallback] | None,
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
        _expire_account(fallback_path, tx.account_id)
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
