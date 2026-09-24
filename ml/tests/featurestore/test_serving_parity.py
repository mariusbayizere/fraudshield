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
    edges, edge_outcomes = _window_edges(len(out))
    out.extend(edges)
    outcomes.update(edge_outcomes)
    out.sort(key=lambda tx: (tx.timestamp, tx.transaction_id))
    return out, outcomes


#: Every trailing window the store reads, as a span before a scored transaction.
EDGE_SPANS = (
    timedelta(seconds=60),
    timedelta(hours=1),
    timedelta(hours=24),
    timedelta(days=7),
    timedelta(days=30),
    timedelta(days=90),
)


def _edge_row(
    tid: str,
    account: str,
    at: datetime,
    counterparty: str,
    *,
    device: str | None = "D0",
    agent: str | None = None,
    amount: float = 2_500.0,
) -> Transaction:
    """One row of the edge corpus, on whichever keys the window under test reads."""
    return Transaction(
        transaction_id=tid,
        account_id=account,
        timestamp=at,
        amount_rwf=amount,
        latitude=-1.95,
        longitude=30.06,
        account_country="RW",
        counterparty_country="RW",
        counterparty_id=counterparty,
        amount_minor=int(amount),
        currency="RWF",
        channel="AGENT_BANKING" if agent else "MOBILE_MONEY",
        device_fingerprint=device,
        agent_id=agent,
        merchant_category_code="6011" if agent else "5411",
    )


#: Three senders, so a set-valued window (unique senders, accounts per device, unique customers)
#: cannot have its edge row masked by the neighbour a microsecond inside it.
EDGE_SENDERS = ("A9", "A10", "A11")


def _window_edges(start_index: int) -> tuple[list[Transaction], dict[str, Outcome]]:
    """History that lands exactly on each window edge, and a microsecond either side.

    The batch path takes `start < row.timestamp < scored` and the store takes `s > t - span`: an
    inclusive/exclusive flip at either end changes a count only when a row sits exactly on the
    edge, which a random gap set never produces (review finding 7).

    The store holds several copies of that arithmetic, each on a different Redis key, and a row
    only reaches the copy whose key it is written to (re-review N4, V3). So the corpus is built
    per *key*, not per window:

    - **the account's own key** — A7's rows on every span in `EDGE_SPANS`, which carry the
      velocity counts and the 90 d amount statistics;
    - **the counterparty key** — rows to `C0`, the counterparty the scored row uses: labelled
      ones on the 90 d edge for `counterparty_confirmed_fraud_90d`, and one sender per position
      on the 24 h edge for `counterparty_unique_senders_24h`;
    - **the account's key, filtered by counterparty** — `tx_count_to_counterparty_30d` reads
      A7's own rows *to C0*, which the spans above never produce;
    - **the geo-cell key** — labelled rows in the scored cell on the 30 d edge, without which
      `geo_cell_fraud_rate_30d` counts nothing at all;
    - **the device key** — three accounts on the scored row's device at the 7 d edge
      (`accounts_per_device_7d` is a set of accounts);
    - **the agent key** — a second scored row through an agent, with three customers at the 1 h
      edge (`agent_unique_customers_1h`).

    `mean_hourly_count_30d` reads a half-open pair, `(t - 30d, t - 1h]`. Flipping *both* of its
    bounds at once is self-cancelling when exactly one row sits on each edge, so the 1 h edge
    carries a second row and the two errors can no longer balance.

    One pair resists a single-flip test, and that is a property of the code rather than a gap in
    the corpus: `ACCOUNT_HORIZON == W_90D`, so the fetch bound `({t - ACCOUNT_HORIZON}` and
    `_amounts`' own `s > t - W_90D` are redundant with each other. Flipping either alone is an
    equivalent mutant — a row at exactly `t - 90d` is still excluded by the other — while
    flipping **both** does fail this suite (checked). Nothing to fix; worth knowing before
    reading a surviving mutant there as missing coverage.
    """
    anchor = START + timedelta(days=200)
    account = "A7"
    micro = timedelta(microseconds=1)
    rows: list[Transaction] = []
    outcomes: dict[str, Outcome] = {}

    for i, span in enumerate(EDGE_SPANS):
        for j, at in enumerate(_around(anchor - span, micro)):
            rows.append(
                _edge_row(f"edge{i}{j}", account, at, f"C{i % 6}", amount=1_000.0 * (i + 1))
            )
    # Three rows on the 1 h edge against two on the 30 d edge (`edge40` and `cp30d0`): the pair
    # of bounds must not be able to cancel, however the two errors are combined.
    rows.append(_edge_row("edge1hbis", account, anchor - EDGE_SPANS[1], "C1"))
    rows.append(_edge_row("edge1hter", account, anchor - EDGE_SPANS[1], "C1"))
    rows.append(_edge_row("edgescored", account, anchor, "C0", amount=5_000.0))

    #: (id prefix, sender, counterparty, edge, device, verdict before the scored row)
    groups: list[tuple[str, tuple[str, str, str], datetime, str | None, bool]] = [
        ("cell", (account, "A9", "C3"), anchor - timedelta(days=30), "D0", True),
        ("cpfraud", (account, "A9", "C0"), anchor - timedelta(days=90), "D0", True),
        ("cp30d", (account, account, "C0"), anchor - timedelta(days=30), "D0", False),
    ]
    for prefix, (_, sender, counterparty), edge, device, fraud in groups:
        for j, at in enumerate(_around(edge, micro)):
            tid = f"{prefix}{j}"
            rows.append(_edge_row(tid, sender, at, counterparty, device=device))
            if fraud:
                # The verdict is in before the scored row, so it counts at scoring time (E.2).
                outcomes[tid] = Outcome(tid, is_fraud=True, available_at=at + timedelta(hours=1))

    # Set-valued windows: one sender per position, or the neighbour hides the edge.
    for j, (sender, at) in enumerate(
        zip(EDGE_SENDERS, _around(anchor - timedelta(hours=24), micro), strict=True)
    ):
        # A different device: on D0 these rows would put every sender inside the device's 7 d
        # window anyway, and mask the device edge below.
        rows.append(_edge_row(f"sender{j}", sender, at, "C0", device="D8"))
    for j, (sender, at) in enumerate(
        zip(EDGE_SENDERS, _around(anchor - timedelta(days=7), micro), strict=True)
    ):
        rows.append(_edge_row(f"device{j}", sender, at, "C2"))

    # The agent key needs a scored row that went through an agent.
    agent_anchor = anchor + timedelta(days=1)
    for j, (sender, at) in enumerate(
        zip(EDGE_SENDERS, _around(agent_anchor - timedelta(hours=1), micro), strict=True)
    ):
        rows.append(_edge_row(f"agent{j}", sender, at, "C2", agent="G9"))
    rows.append(_edge_row("edgeagentscored", account, agent_anchor, "C0", agent="G9"))
    return rows, outcomes


def _around(edge: datetime, micro: timedelta) -> tuple[datetime, datetime, datetime]:
    """Exactly on the edge, a microsecond inside it, a microsecond outside it."""
    return edge, edge + micro, edge - micro


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


def _agree(name: str, batch: float | str, served: float | str, *, whole_days: bool = False) -> bool:
    if REGISTRY[name].dtype is Dtype.CATEGORICAL:
        return batch == served
    b, s = float(batch), float(served)
    if whole_days and name in serving.WHOLE_DAY_FEATURES and not math.isnan(b):
        b = float(math.floor(b)) if b >= 0 else math.nan
    if math.isnan(b) or math.isnan(s):
        return math.isnan(b) and math.isnan(s)
    return abs(b - s) <= TOLERANCE + TOLERANCE * abs(b)


def _seed(store: FeatureStore, extra: dict[str, object]) -> None:
    """The reference data both paths read, written into the store before the replay."""
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


def replay(
    redis: object | None = None, *, exact: bool = True
) -> tuple[list[dict[str, float | str]], list[dict[str, float | str]]]:
    """The prefix replay, against fakeredis by default or any Redis client passed in.

    `exact` is the scorer reading the store itself (ADR 0033), ages at full precision; without it,
    the contract's `AccountContext` alone, whose ages are whole days.
    """
    rows, outcomes = corpus()
    extra = reference_data()
    store = FeatureStore(
        redis if redis is not None else fakeredis.FakeRedis(decode_responses=True),
        REFERENCE,
        authoritative=True,
        metrics=StoreMetrics.create(CollectorRegistry()),
    )
    _seed(store, extra)

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
        if exact:
            read = store.read(domain)
            served_rows.append(
                serving.compute(domain, read.context, [], REFERENCE, read.exact_ages)
            )
        else:
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
def test_the_contract_only_path_agrees_at_whole_day_resolution() -> None:
    """Without ADR 0033 the API sends AccountContext, whose four ages are whole days: everything
    else agrees exactly, and those four agree once the batch value is floored."""
    batch_rows, served_rows = replay(exact=False)
    bad = [
        (i, name)
        for i, (b, s) in enumerate(zip(batch_rows, served_rows, strict=True))
        for name in REGISTRY
        if not _agree(name, b[name], s[name], whole_days=True)
    ]
    assert not bad, bad[:8]


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
