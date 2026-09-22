"""Serving parity: Redis store + serving features agree with the batch path the model trained on.

Prefix replay, as M3's parity suite does it: each transaction is scored from the store **before**
it is observed, and the batch vector is computed on the corpus prefix ending at it. The corpus is
built so every store read is exercised — shared counterparties, devices, agents and H3 cells,
labels that arrive later, SIM swaps, tier changes and agent standing changes mid-replay.
"""

from __future__ import annotations

import math
import random
import socket
import subprocess
import time
from datetime import UTC, datetime, timedelta

import fakeredis
import pytest
import redis
from prometheus_client import CollectorRegistry

from fraudshield_ml.features.registry import REGISTRY, Dtype
from fraudshield_ml.features.types import (
    AgentStanding,
    CountryFacts,
    Outcome,
    TierAssignment,
    Transaction,
)
from fraudshield_ml.features.vector import CorpusIndex, FeatureContext
from fraudshield_ml.features.vector import compute as batch_vector
from fraudshield_ml.featurestore import store as store_module
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.featurestore.store import FeatureStore, StoreMetrics
from fraudshield_ml.serving import features as serving
from fraudshield_ml.serving.convert import to_proto

START = datetime(2025, 3, 1, tzinfo=UTC)
TOLERANCE = 1e-9

REFERENCE = Reference(
    countries={
        "RW": CountryFacts("RW", "AF", frozenset({"EAC", "COMESA"}), 2),
        "KE": CountryFacts("KE", "AF", frozenset({"EAC", "COMESA"}), 3),
        "CD": CountryFacts("CD", "AF", frozenset({"SADC"}), 2),
        "GB": CountryFacts("GB", "EU", frozenset({"XX"}), 0),
    },
    country_of_currency={"RWF": "RW", "KES": "KE", "CDF": "CD", "GBP": "GB"},
    minor_units={"RWF": 0, "KES": 2, "CDF": 2, "GBP": 2},
    denominations={"RWF": (500, 1000, 5000), "KES": (5000, 10000), "CDF": (100000,), "GBP": (500,)},
    cash_out_codes=frozenset({"6011"}),
    cell_rate_prior=0.02,
)

ACCOUNTS = [f"A{i}" for i in range(9)]
HOME = {a: ("RWF", "KES")[i % 2] for i, a in enumerate(ACCOUNTS)}
COUNTERPARTIES = [f"C{i}" for i in range(6)]
DEVICES = [f"D{i}" for i in range(5)]
AGENTS = ["G0", "G1"]
CHANNELS = ["MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER"]
GAPS = [20, 45, 300, 1_800, 5_000, 30_000, 90_000, 400_000, 1_300_000, 5_400_000]


def corpus(rows: int = 260, seed: int = 20260922) -> tuple[list[Transaction], dict[str, Outcome]]:
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    out: list[Transaction] = []
    outcomes: dict[str, Outcome] = {}
    when = START
    for i in range(rows):
        # Interleaved accounts with mostly short gaps, so windows hold several accounts at once.
        when += timedelta(seconds=rng.choice(GAPS[:6]) if rng.random() < 0.93 else rng.choice(GAPS))
        account = rng.choice(ACCOUNTS)
        channel = rng.choice(CHANNELS)
        currency = HOME[account]
        units = REFERENCE.minor_units[currency]
        major = rng.choice([500, 1000, 1234, 5000, 20000, 77777, 250000])
        device = None if channel in ("USSD", "AGENT_BANKING") else rng.choice(DEVICES)
        agent = rng.choice(AGENTS) if channel == "AGENT_BANKING" else None
        tx = Transaction(
            transaction_id=f"t{i:04d}",
            account_id=account,
            timestamp=when,
            amount_rwf=float(major) * (1.0 if currency == "RWF" else 9.5),
            latitude=-1.95 + 0.4 * rng.choice([0, 0, 1, 2]) + rng.uniform(-0.01, 0.01),
            longitude=30.06 + 0.4 * rng.choice([0, 1]) + rng.uniform(-0.01, 0.01),
            account_country=REFERENCE.country_of_currency[currency],
            counterparty_country=rng.choice(["RW", "RW", "KE", "CD", "GB"]),
            counterparty_id=rng.choice(COUNTERPARTIES),
            amount_minor=major * 10**units,
            currency=currency,
            channel=channel,
            device_fingerprint=device,
            agent_id=agent,
            merchant_category_code=rng.choice(["6011", "5411"]) if agent else "5411",
        )
        out.append(tx)
        if rng.random() < 0.35:
            outcomes[tx.transaction_id] = Outcome(
                transaction_id=tx.transaction_id,
                is_fraud=rng.random() < 0.4,
                available_at=when + timedelta(seconds=rng.choice([60, 3_600, 200_000])),
            )
    return out, outcomes


def reference_data() -> dict[str, object]:
    return {
        "sim_swaps": {
            "A1": [START + timedelta(days=2, hours=5)],
            "A4": [START + timedelta(hours=7)],
        },
        "opened_at": {
            "A0": START - timedelta(days=400, hours=3),
            "A2": START - timedelta(days=12, hours=20),
            "C1": START - timedelta(days=3, hours=1),
        },
        "tiers": {
            "A0": [
                TierAssignment(1, START - timedelta(days=30)),
                TierAssignment(3, START + timedelta(days=3)),
            ],
            "A3": [TierAssignment(2, START)],
        },
        "standing": {
            "G0": [
                AgentStanding(
                    float_balance_rwf=400_000.0,
                    float_limit_rwf=1_000_000.0,
                    latitude=-1.95,
                    longitude=30.06,
                    effective_at=START - timedelta(days=1),
                ),
                AgentStanding(
                    float_balance_rwf=900_000.0,
                    float_limit_rwf=1_000_000.0,
                    latitude=-1.55,
                    longitude=30.46,
                    effective_at=START + timedelta(days=4),
                ),
            ],
        },
    }


def _agree(name: str, batch: float | str, served: float | str) -> bool:
    if REGISTRY[name].dtype is Dtype.CATEGORICAL:
        return batch == served
    b, s = float(batch), float(served)
    if name in serving.WHOLE_DAY_FEATURES and not math.isnan(b):
        b = float(math.floor(b)) if b >= 0 else math.nan
    if math.isnan(b) or math.isnan(s):
        return math.isnan(b) and math.isnan(s)
    return abs(b - s) <= TOLERANCE + TOLERANCE * abs(b)


def replay(
    redis: object | None = None,
) -> tuple[list[dict[str, float | str]], list[dict[str, float | str]]]:
    """The prefix replay, against fakeredis by default or any Redis client passed in."""
    rows, outcomes = corpus()
    extra = reference_data()
    store = FeatureStore(
        redis if redis is not None else fakeredis.FakeRedis(decode_responses=True),
        REFERENCE,
        authoritative=True,
        metrics=StoreMetrics.create(CollectorRegistry()),
    )
    for account, swaps in extra["sim_swaps"].items():  # type: ignore[attr-defined]
        for at in swaps:
            store.record_sim_swap(account, at)
    for entity, at in extra["opened_at"].items():  # type: ignore[attr-defined]
        store.set_opened_at(entity, at)
    for account, tiers in extra["tiers"].items():  # type: ignore[attr-defined]
        for tier in tiers:
            store.set_kyc_tier(account, tier.tier, tier.effective_at)
    for agent, standing in extra["standing"].items():  # type: ignore[attr-defined]
        for record in standing:
            store.set_agent_standing(agent, record)

    first_seen: dict[str, datetime] = {}
    device_first: dict[str, datetime] = {}
    for tx in rows:
        first_seen.setdefault(tx.account_id, tx.timestamp)
        if tx.device_fingerprint:
            device_first.setdefault(tx.device_fingerprint, tx.timestamp)
    context = FeatureContext(
        countries=REFERENCE.countries,
        outcomes=outcomes,
        sim_swaps=extra["sim_swaps"],  # type: ignore[arg-type]
        opened_at=extra["opened_at"],  # type: ignore[arg-type]
        tiers=extra["tiers"],  # type: ignore[arg-type]
        agent_standing=extra["standing"],  # type: ignore[arg-type]
        denominations=REFERENCE.denominations,
        cash_out_codes=REFERENCE.cash_out_codes,
        first_seen=first_seen,
        device_first_seen=device_first,
        cell_rate_prior=REFERENCE.cell_rate_prior,
        kyc_tier_range=REFERENCE.kyc_tier_range,
    )
    index = CorpusIndex.build(rows)
    batch_rows, served_rows = [], []
    for i, tx in enumerate(rows):
        proto = to_proto(tx)
        domain = serving.domain_transaction(proto, REFERENCE)
        assert domain == tx, "the contract round trip must reproduce the training record"
        served_rows.append(serving.compute(domain, store.context_for(domain), [], REFERENCE))
        batch_rows.append(batch_vector(rows, i, context, index))
        store.observe(tx)
        if tx.transaction_id in outcomes:
            o = outcomes[tx.transaction_id]
            assert store.observe_outcome(o.transaction_id, o.is_fraud, o.available_at)
    return batch_rows, served_rows


@pytest.fixture(scope="module")
def replayed() -> tuple[list[dict[str, float | str]], list[dict[str, float | str]]]:
    return replay()


@pytest.mark.req("FR-02-09", "FR-02-02", "D-04")
def test_every_feature_agrees_with_the_batch_path_at_every_prefix(
    replayed: tuple[list[dict[str, float | str]], list[dict[str, float | str]]],
) -> None:
    batch_rows, served_rows = replayed
    disagreements = [
        (i, name, b[name], s[name])
        for i, (b, s) in enumerate(zip(batch_rows, served_rows, strict=True))
        for name in REGISTRY
        if not _agree(name, b[name], s[name])
    ]
    assert not disagreements, f"{len(disagreements)} disagreements, first: {disagreements[:8]}"


@pytest.mark.req("FR-02-09")
def test_the_replay_exercises_every_store_read(
    replayed: tuple[list[dict[str, float | str]], list[dict[str, float | str]]],
) -> None:
    """A parity test over constant columns proves nothing, so each feature must vary."""
    _, served_rows = replayed
    constant_by_design = {
        # No configured limits are sent in this replay; the flag is covered by its own test.
        "just_below_limit_flag",
    }
    constant = sorted(
        name
        for name in REGISTRY
        if name not in constant_by_design and len({str(row[name]) for row in served_rows}) < 2
    )
    assert not constant, f"features constant across the replay: {constant}"


def _disagreements() -> int:
    batch_rows, served_rows = replay()
    return sum(
        1
        for b, s in zip(batch_rows, served_rows, strict=True)
        for name in REGISTRY
        if not _agree(name, b[name], s[name])
    )


@pytest.mark.req("FR-02-09")
@pytest.mark.parametrize(
    ("target", "attribute", "value"),
    [
        (serving, "MAD_TO_SIGMA", 1.5),
        ("store", "W_1H", 3_600_000_000 + 1_000_000),
        ("store", "W_CELL", 29 * 86_400_000_000),
    ],
    ids=["z-score scale", "1h window one second wide", "cell window a day short"],
)
def test_the_replay_detects_a_divergence(
    monkeypatch: pytest.MonkeyPatch, target: object, attribute: str, value: float
) -> None:
    """Mutation checks: the parity assertion is worth only the divergences it can see."""
    monkeypatch.setattr(store_module if target == "store" else target, attribute, value)
    assert _disagreements() > 0


@pytest.mark.req("FR-02-09")
def test_the_replay_detects_labels_that_never_arrive(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(FeatureStore, "observe_outcome", lambda *_: True)
    assert _disagreements() > 0


# ------------------------------------------------------------------ against the real server


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.mark.requires_docker
@pytest.mark.req("FR-02-09")
def test_the_replay_agrees_on_the_redis_the_deployment_runs() -> None:
    """fakeredis stands in everywhere else; this checks ZADD LT and pipelining on Redis 7.2.16,
    the image docker-compose.yml pins. Skipped without Docker, run in CI (ADR 0010)."""
    port = _free_port()
    container = subprocess.run(  # noqa: S603
        ["docker", "run", "-d", "--rm", "-p", f"127.0.0.1:{port}:6379", "redis:7.2.16"],  # noqa: S607
        capture_output=True,
        text=True,
        check=True,
        timeout=600,
    ).stdout.strip()
    try:
        client = redis.Redis(port=port, decode_responses=True)
        deadline = time.monotonic() + 60
        while True:
            try:
                client.ping()
                break
            except redis.ConnectionError:
                if time.monotonic() > deadline:
                    raise
                time.sleep(0.5)
        batch_rows, served_rows = replay(client)
        bad = [
            (i, n)
            for i, (b, s) in enumerate(zip(batch_rows, served_rows, strict=True))
            for n in REGISTRY
            if not _agree(n, b[n], s[n])
        ]
        assert not bad, bad[:8]
    finally:
        subprocess.run(["docker", "stop", container], capture_output=True, check=False)  # noqa: S603, S607
