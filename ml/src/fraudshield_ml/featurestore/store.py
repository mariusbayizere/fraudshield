"""The Redis feature store (FR-02-09): arrivals written after each transaction, read as of the next.

**Why it stores arrivals rather than counters.** Every windowed feature is defined relative to the
*scored* transaction's timestamp: `tx_count_1h` is the rows in `(t - 1h, t)`. A counter updated
after the previous transaction knows nothing about which of its rows have since left the window, so
it is stale by the time it is read. The store therefore keeps each entity's arrivals in a sorted
set keyed by timestamp and aggregates at read time, for exactly the scored `t`.

**What a read costs.** `context_for` issues every command it needs in **one pipelined round trip**
(the budget C.2 gives the Redis read) and assembles the scoring contract's `AccountContext` from
the replies. `observe` is the asynchronous write after the transaction completes; its duration is
the FR-02-09 update latency and is exported as a Prometheus histogram.

**Layout** (every key under the configured prefix, default `fs:fv1:`; members are JSON arrays so
no token can collide with a separator):

    a:{account}:tx      ZSET  ts -> [txid, amount_rwf, counterparty, cp_country, device, lat, lon]
    a:{account}:first   ZSET  "first" -> earliest ts seen or restored (ZADD LT)
    a:{account}:last    ZSET  one member, the latest transaction: [lat, lon] (kept past 90 d)
    a:{account}:cps     SET   every counterparty paid         (durable)
    a:{account}:ctry    SET   every counterparty country       (durable)
    a:{account}:dev     SET   every device fingerprint used    (durable)
    a:{account}:swap    ZSET  SIM swap times
    a:{account}:tier    ZSET  effective_at -> [tier, effective_at]
    a:{account}:prof    HASH  opened_at (micros)
    c:{counterparty}:tx ZSET  ts -> [txid, account, label, available_at]
    d:{device}:tx       ZSET  ts -> [txid, account]
    d:{device}:first    ZSET  "first" -> earliest sighting (ZADD LT)
    g:{agent}:tx        ZSET  ts -> [txid, account, is_cash_out]
    g:{agent}:stand     ZSET  effective_at -> [balance, limit, lat, lon, effective_at]
    h:{cell}:tx         ZSET  ts -> [txid, label, available_at]
    t:{txid}            HASH  where the transaction's labelled members live, for label updates

`label` is -1 until an outcome arrives, then 0 or 1, and `available_at` is when the label became
visible; reads count a label only when `available_at < t` (`label_basis=AVAILABLE_AT_LAG`).

**TTL.** FR-02-09 fixes 30 days, refreshed on every write, so an active entity keeps its state and
an idle one expires. Windows reach 90 days and the durable facts are unbounded, so an expired or
never-populated entity is not "new": `context_for` asks the configured `Fallback` (the database
tables M6 owns, `account_velocity_cache` and the per-account durable table PB-37 records as absent)
and, with none, reports the state as unknown or — only when the store is declared `authoritative`,
i.e. it has seen every transaction since go-live — as genuinely new.
"""

from __future__ import annotations

import json
import math
import re
import time
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Protocol

from prometheus_client import CollectorRegistry, Counter, Histogram

from fraudshield_ml.features.primitives import h3_cell, haversine_km
from fraudshield_ml.features.registry import REGISTRY, smoothing_for
from fraudshield_ml.features.types import AgentStanding, Transaction
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.serving.generated import scoring_pb2 as pb

TTL = timedelta(days=30)
MICROS = 1_000_000
SECONDS_PER_DAY = 86_400
UNLABELLED = -1

_WINDOW = re.compile(r"^(\d+)([smhd])$")
_UNIT = {"s": 1, "m": 60, "h": 3600, "d": 86400}


def _window_micros(name: str) -> int:
    """A feature's declared window in microseconds, parsed from the registry's own string."""
    match = _WINDOW.match(REGISTRY[name].window or "")
    if match is None:
        raise ValueError(f"{name} declares no single trailing window")
    return int(match.group(1)) * _UNIT[match.group(2)] * MICROS


W_60S = _window_micros("tx_count_60s")
W_1H = _window_micros("tx_count_1h")
W_24H = _window_micros("tx_count_24h")
W_7D = _window_micros("tx_count_7d")
W_30D = _window_micros("tx_count_to_counterparty_30d")
W_90D = _window_micros("amount_zscore_90d")
W_DORMANT = _window_micros("dormancy_reactivation_flag")
W_CELL = _window_micros("geo_cell_fraud_rate_30d")
W_CP_FRAUD = _window_micros("counterparty_confirmed_fraud_90d")
W_CP_SENDERS = _window_micros("counterparty_unique_senders_24h")
W_DEVICE = _window_micros("accounts_per_device_7d")
W_AGENT = _window_micros("agent_unique_customers_1h")
W_RAMP_MONTH = _window_micros("synthetic_identity_score")
#: The composite's internal "recent" sub-window (batch.RAMP_SHORT_WINDOW).
W_RAMP_RECENT = 7 * SECONDS_PER_DAY * MICROS
#: The longest window any account-keyed read needs: everything older is trimmed on write.
ACCOUNT_HORIZON = max(W_90D, W_30D, W_7D, W_RAMP_MONTH)
#: How long a verdict may take before its absence means a missing producer rather than the normal
#: wait. E.3's label delay is log-normal with a 72-hour median and log-sigma 1.2
#: (`dataset/generator/params/labels.yaml`), so ~95% of verdicts have arrived by three weeks.
#: Under it, an unlabelled row is a label in flight; over it, nobody is writing them (ADR 0034).
LABEL_LATENCY = 21 * SECONDS_PER_DAY * MICROS


def micros(moment: datetime) -> int:
    if moment.tzinfo is None:
        raise ValueError("timestamps must be timezone-aware UTC")
    delta = moment - datetime(1970, 1, 1, tzinfo=UTC)
    return (delta.days * SECONDS_PER_DAY + delta.seconds) * MICROS + delta.microseconds


def from_micros(value: int) -> datetime:
    return datetime(1970, 1, 1, tzinfo=UTC) + timedelta(microseconds=value)


def _median(values: Sequence[float]) -> float:
    """Median, averaging the two middle values for an even count, as both paths define it."""
    ordered = sorted(values)
    half, odd = divmod(len(ordered), 2)
    return ordered[half] if odd else (ordered[half - 1] + ordered[half]) / 2.0


def _whole_days(delta_micros: int) -> int | None:
    """Whole days for the contract's uint32 day fields; negative (a data error) has no encoding."""
    return None if delta_micros < 0 else delta_micros // (SECONDS_PER_DAY * MICROS)


@dataclass(frozen=True)
class Durable:
    """Everything an account's Redis keys held, as the database knows it before a given time.

    The fallback must return all of it, because the acceptance test for the carried DB fallback
    (`ml/tests/featurestore/test_db_fallback.py`, owned by M6) requires the features read through
    it to be **identical** to the Redis path's: the windowed transactions as well as the durable
    facts, the SIM swaps, the tier history and the opening date.
    """

    first_seen: datetime | None = None
    last_at: datetime | None = None
    last_location: tuple[float, float] | None = None
    counterparties: frozenset[str] = frozenset()
    countries: frozenset[str] = frozenset()
    devices: frozenset[str] = frozenset()
    #: The account's own transactions strictly before the scored time (at least the 90-day
    #: horizon); the store filters to its windows.
    transactions: tuple[Transaction, ...] = ()
    sim_swaps: tuple[datetime, ...] = ()
    tiers: tuple[tuple[int, datetime], ...] = ()
    opened_at: datetime | None = None


class Fallback(Protocol):
    """The database side of C.4's "Redis down or key expired -> DB fallback".

    Carried to M6, which owns the tables (`account_velocity_cache`, the transactions hypertable and
    PB-37's per-account durable table). `featurestore.fallback.ReplayFallback` is the reference
    implementation the acceptance test holds a PostgreSQL one to.
    """

    def account(self, account_id: str, before: datetime) -> Durable | None:
        """The account's state strictly before `before`, or None if it has never been seen."""

    def device_first_seen(self, device: str, before: datetime) -> datetime | None: ...


@dataclass
class StoreMetrics:
    update_seconds: Histogram
    fallbacks: Counter
    unknown_state: Counter
    #: ADR 0034: reads whose features fell back to a constant because no producer writes the
    #: state they read. Silent otherwise, and worth 162 unflagged frauds on the gate model.
    missing_producer: Counter

    @staticmethod
    def create(registry: CollectorRegistry | None = None) -> StoreMetrics:
        kwargs: dict[str, Any] = {"registry": registry} if registry is not None else {}
        return StoreMetrics(
            update_seconds=Histogram(
                "fs_feature_store_update_seconds",
                "Time to write one completed transaction into the feature store (FR-02-09)",
                buckets=(0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0),
                **kwargs,
            ),
            fallbacks=Counter(
                "fs_feature_store_fallbacks_total",
                "Reads that found no Redis state for an entity and asked the fallback source",
                ["entity"],
                **kwargs,
            ),
            missing_producer=Counter(
                "fs_feature_store_missing_producer_reads_total",
                "Reads where a feature fell back to a constant because nothing writes the state "
                "it reads (ADR 0034): outcomes, SIM swaps, KYC tier, account opening",
                ["state"],
                **kwargs,
            ),
            unknown_state=Counter(
                "fs_feature_store_unknown_state_total",
                "Reads whose durable state was unknown: no Redis key, no fallback answer, and "
                "the store not authoritative (DEGRADED_MODE, C.4)",
                ["entity"],
                **kwargs,
            ),
        )


@dataclass(frozen=True)
class ContextRead:
    """What one store read returns: the contract's message and the ages it cannot carry."""

    context: pb.AccountContext
    #: The four `WHOLE_DAY_FEATURES` at full precision, where the store knows them.
    exact_ages: dict[str, float]
    #: C.4's DEGRADED_MODE: the account's durable state was unknown, or came from the fallback
    #: rather than Redis. Returned to the API as `ScoringResult.feature_store_degraded`.
    degraded: bool = False


@dataclass
class _Snapshot:
    """The replies to one `context_for` round trip, parsed."""

    rows: list[tuple[list[Any], int]]
    first: Any
    last: tuple[list[Any], int] | None
    cps: set[str] = field(default_factory=set)
    countries: set[str] = field(default_factory=set)
    devices: set[str] = field(default_factory=set)
    swap: list[str] = field(default_factory=list)
    tier: list[str] = field(default_factory=list)
    opened: str | None = None
    cell_rows: list[list[Any]] = field(default_factory=list)
    cp_rows: list[tuple[list[Any], int]] = field(default_factory=list)
    cp_opened: str | None = None
    device_rows: list[list[Any]] = field(default_factory=list)
    device_first: Any = None
    agent_rows: list[list[Any]] = field(default_factory=list)
    standing: list[str] = field(default_factory=list)


@dataclass
class FeatureStore:
    redis: Any
    reference: Reference
    prefix: str = "fs:fv1:"
    ttl: timedelta = TTL
    fallback: Fallback | None = None
    #: True only when the store has observed every transaction since go-live, so that absent
    #: state means "new" rather than "unknown". Tests and replays set it; production must not.
    authoritative: bool = False
    metrics: StoreMetrics = field(default_factory=StoreMetrics.create)

    # ------------------------------------------------------------------------------ keys

    def _k(self, kind: str, entity: str, suffix: str) -> str:
        return f"{self.prefix}{kind}:{entity}:{suffix}"

    # ------------------------------------------------------------------------------ writes

    def observe(self, tx: Transaction) -> None:
        """Fold one **completed** transaction into the store. FR-02-09's update, timed."""
        started = time.perf_counter()
        ts = micros(tx.timestamp)
        ttl = int(self.ttl.total_seconds())
        account = tx.account_id
        pipe = self.redis.pipeline(transaction=True)
        touched: list[str] = []

        def zadd(key: str, member: list[Any], score: int, *, trim: int | None = None) -> None:
            pipe.zadd(key, {json.dumps(member, separators=(",", ":")): score})
            if trim is not None:
                pipe.zremrangebyscore(key, "-inf", f"({ts - trim}")
            touched.append(key)

        zadd(self._k("a", account, "tx"), _member(tx), ts, trim=ACCOUNT_HORIZON)
        first = self._k("a", account, "first")
        pipe.zadd(first, {"first": ts}, lt=True)
        last = self._k("a", account, "last")
        pipe.zadd(last, {json.dumps([tx.latitude, tx.longitude]): ts})
        pipe.zremrangebyrank(last, 0, -2)
        touched += [first, last]
        for suffix, value in (
            ("cps", tx.counterparty_id),
            ("ctry", tx.counterparty_country),
            ("dev", tx.device_fingerprint),
        ):
            if value is not None:
                key = self._k("a", account, suffix)
                pipe.sadd(key, value)
                touched.append(key)

        cell = h3_cell(tx.latitude, tx.longitude)
        cell_member = [tx.transaction_id, UNLABELLED, None]
        zadd(self._k("h", cell, "tx"), cell_member, ts, trim=W_CELL)
        where: dict[str, str] = {"ts": str(ts), "cell": cell, "cell_m": json.dumps(cell_member)}
        if tx.counterparty_id is not None:
            cp_member = [tx.transaction_id, account, UNLABELLED, None]
            zadd(self._k("c", tx.counterparty_id, "tx"), cp_member, ts, trim=W_CP_FRAUD)
            where |= {"cp": tx.counterparty_id, "cp_m": json.dumps(cp_member)}
        if tx.device_fingerprint is not None:
            zadd(
                self._k("d", tx.device_fingerprint, "tx"),
                [tx.transaction_id, account],
                ts,
                trim=W_DEVICE,
            )
            device_first = self._k("d", tx.device_fingerprint, "first")
            pipe.zadd(device_first, {"first": ts}, lt=True)
            touched.append(device_first)
        if tx.agent_id is not None:
            cash_out = tx.merchant_category_code in self.reference.cash_out_codes
            zadd(
                self._k("g", tx.agent_id, "tx"),
                [tx.transaction_id, account, cash_out],
                ts,
                trim=W_AGENT,
            )
        # Reference keys (profile, tiers, SIM swaps, agent standing) are written once and never
        # again, so without this they expire under an account that transacts daily (review
        # finding 2). EXPIRE on a key that does not exist is a no-op.
        touched += [self._k("a", account, suffix) for suffix in ("prof", "tier", "swap")]
        if tx.agent_id is not None:
            touched.append(self._k("g", tx.agent_id, "stand"))
        txn = self._k("t", tx.transaction_id, "where")
        pipe.hset(txn, mapping=where)
        # The label can arrive up to 90 days later and still matter to counterparty reads.
        pipe.expire(txn, max(ttl, W_CP_FRAUD // MICROS))
        for key in touched:
            pipe.expire(key, ttl)
        pipe.execute()
        self.metrics.update_seconds.observe(time.perf_counter() - started)

    def observe_outcome(self, transaction_id: str, is_fraud: bool, available_at: datetime) -> bool:
        """Attach a label to an observed transaction. False when the transaction has expired."""
        where = self.redis.hgetall(self._k("t", transaction_id, "where"))
        if not where:
            return False
        ts = int(where["ts"])
        label = int(is_fraud)
        at = micros(available_at)
        pipe = self.redis.pipeline(transaction=True)
        cell_key = self._k("h", where["cell"], "tx")
        new_cell = json.dumps([transaction_id, label, at], separators=(",", ":"))
        pipe.zrem(cell_key, _compact(where["cell_m"]))
        pipe.zadd(cell_key, {new_cell: ts})
        update = {"cell_m": new_cell}
        if "cp" in where:
            cp_key = self._k("c", where["cp"], "tx")
            old = json.loads(where["cp_m"])
            new_cp = json.dumps([transaction_id, old[1], label, at], separators=(",", ":"))
            pipe.zrem(cp_key, _compact(where["cp_m"]))
            pipe.zadd(cp_key, {new_cp: ts})
            update["cp_m"] = new_cp
        pipe.hset(self._k("t", transaction_id, "where"), mapping=update)
        pipe.execute()
        return True

    def restore_first_seen(self, account_id: str, first_seen_at: datetime) -> None:
        """PB-37's durable first-seen, from a source that can see all of history."""
        key = self._k("a", account_id, "first")
        self.redis.zadd(key, {"first": micros(first_seen_at)}, lt=True)
        self.redis.expire(key, int(self.ttl.total_seconds()))

    def restore_device_first_seen(self, device: str, first_seen_at: datetime) -> None:
        key = self._k("d", device, "first")
        self.redis.zadd(key, {"first": micros(first_seen_at)}, lt=True)
        self.redis.expire(key, int(self.ttl.total_seconds()))

    def restore_known(
        self,
        account_id: str,
        *,
        counterparties: Iterable[str] = (),
        countries: Iterable[str] = (),
        devices: Iterable[str] = (),
    ) -> None:
        """What the account used before the store began: the batch path's `*_before` sets."""
        ttl = int(self.ttl.total_seconds())
        for suffix, values in (("cps", counterparties), ("ctry", countries), ("dev", devices)):
            members = list(values)
            if members:
                key = self._k("a", account_id, suffix)
                self.redis.sadd(key, *members)
                self.redis.expire(key, ttl)

    def record_sim_swap(self, account_id: str, at: datetime) -> None:
        key = self._k("a", account_id, "swap")
        self.redis.zadd(key, {str(micros(at)): micros(at)})
        self.redis.expire(key, int(self.ttl.total_seconds()))

    def set_opened_at(self, account_id: str, opened_at: datetime) -> None:
        key = self._k("a", account_id, "prof")
        self.redis.hset(key, "opened_at", str(micros(opened_at)))
        self.redis.expire(key, int(self.ttl.total_seconds()))

    def set_kyc_tier(self, account_id: str, tier: int, effective_at: datetime) -> None:
        key = self._k("a", account_id, "tier")
        at = micros(effective_at)
        self.redis.zadd(key, {json.dumps([tier, at]): at})
        self.redis.expire(key, int(self.ttl.total_seconds()))

    def set_agent_standing(self, agent_id: str, standing: AgentStanding) -> None:
        key = self._k("g", agent_id, "stand")
        at = micros(standing.effective_at)
        member = [
            standing.float_balance_rwf,
            standing.float_limit_rwf,
            standing.latitude,
            standing.longitude,
            at,
        ]
        self.redis.zadd(key, {json.dumps(member): at})
        self.redis.expire(key, int(self.ttl.total_seconds()))

    # ------------------------------------------------------------------------------ reads

    def context_for(self, tx: Transaction) -> pb.AccountContext:
        """The contract's `AccountContext` for `tx`, as of `tx.timestamp`, in one round trip."""
        return self.read(tx).context

    def read(self, tx: Transaction) -> ContextRead:
        """The context plus the four ages at full precision, for a scorer that reads the store
        itself (ADR 0033): the contract's `uint32` day fields cannot carry the fraction the model
        was trained on, and flooring it moved 10 of 101,909 gate-model test transactions across a
        risk tier."""
        t = micros(tx.timestamp)
        snap = self._read(tx, t)
        degraded = snap.first is None
        first_seen, known = self._resolve_account(tx.account_id, snap, t)
        exact: dict[str, float] = {}

        ctx = pb.AccountContext()
        self._velocity(ctx, snap.rows, t, first_seen if known else None)
        self._amounts(ctx, snap.rows, t)
        self._previous(ctx, snap.rows, snap.last, t)
        ctx.countries_seen.extend(sorted(snap.countries))
        if snap.opened is not None:
            _set_days(ctx, "account_age_days", t - int(snap.opened))
            exact["account_age_days"] = _days(t - int(snap.opened))
        if snap.tier:
            ctx.kyc_tier = int(json.loads(snap.tier[0])[0])
        if snap.swap:
            _set_days(ctx, "days_since_sim_swap", t - int(snap.swap[0]))
            exact["days_since_sim_swap"] = _days(t - int(snap.swap[0]))
        self._counterparty(ctx, tx, snap, t)
        if snap.cp_opened is not None:
            exact["counterparty_account_age_days"] = _days(t - int(snap.cp_opened))
        if tx.device_fingerprint is not None:
            first_device = self._device(ctx, tx, snap, t)
            if first_device is not None:
                exact["device_age_days"] = _days(t - first_device)
        if tx.agent_id is not None:
            self._agent(ctx, tx, snap)
        self._cell(ctx, tx, snap.cell_rows, t)
        self._count_missing_producers(snap, ctx, t, degraded=degraded and not self.authoritative)
        month = sum(1 for _, s in snap.rows if s > t - W_RAMP_MONTH)
        if month:
            recent = sum(1 for _, s in snap.rows if s > t - W_RAMP_RECENT)
            ctx.volume_ramp_ratio_7d = recent / month
        # Absent Redis state is degraded unless the store claims completeness (a replay or test).
        return ContextRead(
            context=ctx, exact_ages=exact, degraded=degraded and not self.authoritative
        )

    def _count_missing_producers(
        self, snap: _Snapshot, ctx: pb.AccountContext, t: int, *, degraded: bool
    ) -> None:
        """Count the features that fell back to a constant for want of a producer (ADR 0034).

        Only where the state could have applied, so the counter means "no producer" and nothing
        else — it is what M9's page fires on, and a page that also fires on ordinary operation is
        a page that gets silenced (re-review N5):

        - **outcomes**: the counterparty must have a row old enough that a verdict would have
          arrived by now (`LABEL_LATENCY`). A counterparty seen only this morning, with the labels
          consumer deployed and healthy, is a label in flight, not a missing producer.
        - **reference state**: the account must already be known to the store. A first-ever
          transaction has no tier or opening date for any producer to have written, so its absence
          says nothing.
        - A degraded read is the *account's* own Redis state being absent (`snap.first is None`),
          which is the fallback's shortfall and not a producer's, so the three account-state arms
          stand down for it. The counterparty arm does not: it reads a different key, and a new
          account paying an established counterparty is an ordinary shape whose outcome features
          are exactly as constant as anyone else's (re-review V4).
        """
        if (
            snap.cp_rows
            and any(s < t - LABEL_LATENCY for _, s in snap.cp_rows)
            and not any(m[2] != UNLABELLED for m, _ in snap.cp_rows)
        ):
            self.metrics.missing_producer.labels("outcomes").inc()
        if degraded:
            return
        if not ctx.HasField("days_since_sim_swap"):
            self.metrics.missing_producer.labels("sim_swaps").inc()
        known = bool(snap.rows) or snap.first is not None
        if known and not ctx.HasField("kyc_tier"):
            self.metrics.missing_producer.labels("kyc_tier").inc()
        if known and not ctx.HasField("account_age_days"):
            self.metrics.missing_producer.labels("account_opened_at").inc()

    def _read(self, tx: Transaction, t: int) -> _Snapshot:
        """Every command `context_for` needs, pipelined into a single round trip."""
        before = f"({t}"
        account, cp, device, agent = (
            tx.account_id,
            tx.counterparty_id,
            tx.device_fingerprint,
            tx.agent_id,
        )
        pipe = self.redis.pipeline(transaction=False)
        a = self._k
        pipe.zrangebyscore(
            a("a", account, "tx"), f"({t - ACCOUNT_HORIZON}", before, withscores=True
        )
        pipe.zscore(a("a", account, "first"), "first")
        pipe.zrange(a("a", account, "last"), 0, -1, withscores=True)
        pipe.smembers(a("a", account, "cps"))
        pipe.smembers(a("a", account, "ctry"))
        pipe.smembers(a("a", account, "dev"))
        pipe.zrevrangebyscore(a("a", account, "swap"), before, "-inf", start=0, num=1)
        pipe.zrevrangebyscore(a("a", account, "tier"), t, "-inf", start=0, num=1)
        pipe.hget(a("a", account, "prof"), "opened_at")
        pipe.zrangebyscore(
            a("h", h3_cell(tx.latitude, tx.longitude), "tx"), f"({t - W_CELL}", before
        )
        if cp is not None:
            pipe.zrangebyscore(a("c", cp, "tx"), f"({t - W_CP_FRAUD}", before, withscores=True)
            pipe.hget(a("a", cp, "prof"), "opened_at")
        if device is not None:
            pipe.zrangebyscore(a("d", device, "tx"), f"({t - W_DEVICE}", before)
            pipe.zscore(a("d", device, "first"), "first")
        if agent is not None:
            pipe.zrangebyscore(a("g", agent, "tx"), f"({t - W_AGENT}", before)
            pipe.zrevrangebyscore(a("g", agent, "stand"), t, "-inf", start=0, num=1)
        replies = iter(pipe.execute())

        snap = _Snapshot(
            rows=[(json.loads(m), int(s)) for m, s in next(replies)],
            first=next(replies),
            last=None,
        )
        last = next(replies)
        snap.last = (json.loads(last[0][0]), int(last[0][1])) if last else None
        snap.cps, snap.countries, snap.devices = (
            set(next(replies)),
            set(next(replies)),
            set(next(replies)),
        )
        snap.swap, snap.tier, snap.opened = next(replies), next(replies), next(replies)
        snap.cell_rows = [json.loads(m) for m in next(replies)]
        if cp is not None:
            snap.cp_rows = [(json.loads(m), int(s)) for m, s in next(replies)]
            snap.cp_opened = next(replies)
        if device is not None:
            snap.device_rows = [json.loads(m) for m in next(replies)]
            snap.device_first = next(replies)
        if agent is not None:
            snap.agent_rows = [json.loads(m) for m in next(replies)]
            snap.standing = next(replies)
        return snap

    def _resolve_account(self, account: str, snap: _Snapshot, t: int) -> tuple[int | None, bool]:
        """First-seen and whether the account's durable state is known at all.

        Redis first; on a miss, the configured fallback, whose answer is authoritative either way
        (a record, or "never seen"). Only with no fallback and no claim to completeness is the
        state genuinely unknown, and then it is counted rather than guessed.
        """
        if snap.first is not None:
            return int(snap.first), True
        durable = self._fallback_account(account, from_micros(t))
        if durable is None and not (self.fallback is not None or self.authoritative):
            self.metrics.unknown_state.labels("account").inc()
            return None, False
        first_seen: int | None = None
        if durable is not None:
            first_seen = micros(durable.first_seen) if durable.first_seen else None
            if durable.last_at is not None and durable.last_location is not None:
                snap.last = (list(durable.last_location), micros(durable.last_at))
            snap.cps |= durable.counterparties
            snap.countries |= durable.countries
            snap.devices |= durable.devices
            self._restore_windows(snap, durable, t)
        # No earlier transaction anywhere: this one is the first, as the batch path's true
        # first-seen would say.
        return (first_seen if first_seen is not None else t), True

    @staticmethod
    def _restore_windows(snap: _Snapshot, durable: Durable, t: int) -> None:
        """Rebuild the account's expired keys from the fallback, as Redis would have read them."""
        rows = [
            (_member(row), micros(row.timestamp))
            for row in durable.transactions
            if t - ACCOUNT_HORIZON < micros(row.timestamp) < t
        ]
        # Redis orders equal scores by member bytes; the same order keeps sums identical.
        rows.sort(key=lambda r: (r[1], json.dumps(r[0], separators=(",", ":"))))
        snap.rows = [(json.loads(json.dumps(m, separators=(",", ":"))), s) for m, s in rows]
        swaps = sorted(micros(at) for at in durable.sim_swaps if micros(at) < t)
        if swaps and not snap.swap:
            snap.swap = [str(swaps[-1])]
        tiers = sorted((micros(at), tier) for tier, at in durable.tiers if micros(at) <= t)
        if tiers and not snap.tier:
            snap.tier = [json.dumps([tiers[-1][1], tiers[-1][0]])]
        if durable.opened_at is not None and snap.opened is None:
            snap.opened = str(micros(durable.opened_at))

    @staticmethod
    def _counterparty(ctx: pb.AccountContext, tx: Transaction, snap: _Snapshot, t: int) -> None:
        cp = tx.counterparty_id
        ctx.counterparty_new_for_account = cp is not None and cp not in snap.cps
        if snap.cp_opened is not None:
            _set_days(ctx, "counterparty_account_age_days", t - int(snap.cp_opened))
        ctx.counterparty_unique_senders_24h = len(
            {m[1] for m, s in snap.cp_rows if s > t - W_CP_SENDERS}
        )
        ctx.counterparty_confirmed_fraud_90d = sum(
            1
            for m, _ in snap.cp_rows
            if m[0] != tx.transaction_id and m[2] == 1 and m[3] is not None and m[3] < t
        )
        ctx.tx_count_to_counterparty_30d = sum(
            1 for m, s in snap.rows if s > t - W_30D and cp is not None and m[2] == cp
        )

    def _device(
        self, ctx: pb.AccountContext, tx: Transaction, snap: _Snapshot, t: int
    ) -> int | None:
        device = tx.device_fingerprint
        assert device is not None  # noqa: S101 - the caller checked
        first = int(snap.device_first) if snap.device_first is not None else None
        if first is None and self.fallback is not None:
            self.metrics.fallbacks.labels("device").inc()
            found = self.fallback.device_first_seen(device, from_micros(t))
            first = micros(found) if found is not None else None
        if first is None and (self.authoritative or self.fallback is not None):
            first = t
        day = {m[4] for m, s in snap.rows if s > t - W_24H and m[4] is not None}
        ctx.device.CopyFrom(
            pb.DeviceContext(
                device_new_for_account=device not in snap.devices,
                accounts_per_device_7d=len({r[1] for r in snap.device_rows} | {tx.account_id}),
                device_changes_24h=len(day | {device}) - 1,
            )
        )
        if first is not None:
            _set_days(ctx.device, "device_age_days", t - first)
        ctx.accounts_sharing_device_or_phone = ctx.device.accounts_per_device_7d
        return first

    @staticmethod
    def _agent(ctx: pb.AccountContext, tx: Transaction, snap: _Snapshot) -> None:
        ctx.agent.CopyFrom(
            pb.AgentContext(
                cashout_count_1h=sum(1 for r in snap.agent_rows if r[2]),
                unique_customers_1h=len({r[1] for r in snap.agent_rows} | {tx.account_id}),
            )
        )
        if snap.standing:
            balance, limit, lat, lon, _ = json.loads(snap.standing[0])
            ctx.agent.float_utilisation_ratio = balance / limit
            ctx.agent.distance_from_registered_km = haversine_km(
                lat, lon, tx.latitude, tx.longitude
            )

    def _cell(
        self, ctx: pb.AccountContext, tx: Transaction, cell_rows: list[list[Any]], t: int
    ) -> None:
        alpha = smoothing_for("geo_cell_fraud_rate_30d").alpha
        labelled = [
            r
            for r in cell_rows
            if r[0] != tx.transaction_id and r[1] != UNLABELLED and r[2] is not None and r[2] < t
        ]
        fraud = sum(1 for r in labelled if r[1] == 1)
        ctx.geo_cell_fraud_rate_30d = (fraud + alpha * self.reference.cell_rate_prior) / (
            len(labelled) + alpha
        )

    def _fallback_account(self, account: str, before: datetime) -> Durable | None:
        if self.fallback is None:
            return None
        self.metrics.fallbacks.labels("account").inc()
        return self.fallback.account(account, before)

    @staticmethod
    def _velocity(
        ctx: pb.AccountContext, rows: list[tuple[list[Any], int]], t: int, first_seen: int | None
    ) -> None:
        def within(span: int) -> list[list[Any]]:
            return [m for m, s in rows if s > t - span]

        ctx.tx_count_60s = len(within(W_60S))
        ctx.tx_count_1h = len(within(W_1H))
        day = within(W_24H)
        week = within(W_7D)
        ctx.tx_count_24h = len(day)
        ctx.tx_count_7d = len(week)
        ctx.amount_sum_24h_rwf = repr(float(sum(m[1] for m in day)))
        ctx.amount_sum_7d_rwf = repr(float(sum(m[1] for m in week)))
        ctx.unique_counterparties_24h = len({m[2] for m in day})
        if first_seen is None:
            # Unknown history: the ratio cannot be formed (batch returns NaN without first-seen).
            ctx.mean_hourly_count_30d = math.nan
            return
        # The long window is (t - 30d, t - 1h], disjoint from the 1 h numerator (SHORT_EXCLUDED).
        long_count = sum(1 for _, s in rows if t - W_30D < s <= t - W_1H)
        observed = min(W_30D, t - first_seen)
        baseline_hours = observed / (3600 * MICROS) - W_1H / (3600 * MICROS)
        ctx.mean_hourly_count_30d = long_count / baseline_hours if baseline_hours > 0 else 0.0

    @staticmethod
    def _amounts(ctx: pb.AccountContext, rows: list[tuple[list[Any], int]], t: int) -> None:
        amounts = [float(m[1]) for m, s in rows if s > t - W_90D]
        ctx.history_count_90d = len(amounts)
        if not amounts:
            return
        centre = _median(amounts)
        ctx.amount_median_90d_rwf = repr(centre)
        ctx.amount_mad_90d_rwf = repr(_median([abs(v - centre) for v in amounts]))
        ctx.amount_max_90d_rwf = repr(max(amounts))
        located = [(float(m[5]), float(m[6])) for m, s in rows if s > t - W_90D]
        ctx.home_centroid_90d.CopyFrom(
            pb.GeoPoint(
                latitude=_median([p[0] for p in located]),
                longitude=_median([p[1] for p in located]),
            )
        )

    @staticmethod
    def _previous(
        ctx: pb.AccountContext,
        rows: list[tuple[list[Any], int]],
        last_seen: tuple[list[Any], int] | None,
        t: int,
    ) -> None:
        if rows:
            member, at = rows[-1]
            location = (float(member[5]), float(member[6]))
        elif last_seen is not None and last_seen[1] < t:
            location, at = (float(last_seen[0][0]), float(last_seen[0][1])), last_seen[1]
        else:
            return
        ctx.last_location.CopyFrom(pb.GeoPoint(latitude=location[0], longitude=location[1]))
        ctx.last_transaction_at.FromMicroseconds(at)
        _set_days(ctx, "days_since_previous_activity", t - at)


def _member(tx: Transaction) -> list[Any]:
    """An account-window row, identical whether written by `observe` or rebuilt from a fallback."""
    return [
        tx.transaction_id,
        tx.amount_rwf,
        tx.counterparty_id,
        tx.counterparty_country,
        tx.device_fingerprint,
        tx.latitude,
        tx.longitude,
    ]


def _days(delta_micros: int) -> float:
    """Fractional days, as the batch path computes them (seconds / 86400), sign kept."""
    return (delta_micros / MICROS) / SECONDS_PER_DAY


def _set_days(message: Any, name: str, delta_micros: int) -> None:
    days = _whole_days(delta_micros)
    if days is not None:
        setattr(message, name, days)


def _compact(member: str) -> str:
    """Members are written compact; normalise one read back from the location hash."""
    return json.dumps(json.loads(member), separators=(",", ":"))
