"""The online feature path: incremental state, one transaction at a time.

Independent of `fraudshield_ml.features.batch` — neither imports the other, enforced by a test that
walks the import graph. Both import `registry` (declarative) and `types` (data).

The online path never sees a history; it sees arrivals. That asymmetry is the whole point of prefix
replay: handing both paths a completed history proves they agree on a situation this one never
encounters, and cannot detect a batch window that reaches forward in time.

**Usage is `compute` then `observe`, in that order.** `compute` reads state accumulated from
transactions that arrived *before* the one being scored, which is what `self_inclusion=EXCLUDED`
means operationally. Calling `observe` first would fold the scored transaction into its own window.
"""

from __future__ import annotations

import math
import re
from collections import deque
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from fraudshield_ml.features.primitives import h3_cell, haversine_km
from fraudshield_ml.features.registry import (
    REGISTRY,
    HistoryKey,
    categories_for,
    smoothing_for,
)
from fraudshield_ml.features.types import (
    AgentStanding,
    CountryFacts,
    IdentityEvidence,
    LimitDimension,
    OperationalLimit,
    Outcome,
    TierAssignment,
    Transaction,
)

_SHORT = timedelta(hours=1)
_LONG = timedelta(days=30)
_CELL = timedelta(days=30)

_WINDOW = re.compile(r"^(\d+)([smhd])$")
_UNIT_SECONDS = {"s": 1, "m": 60, "h": 3600, "d": 86400}


def window_of(name: str) -> timedelta:
    """The declared window of a single-window feature, read from the registry.

    Written against the same declared string the batch path reads and with a different parser, on
    purpose. A shared parser would put the window rule inside the surface parity cannot see, and
    the window rule is what parity mutations 2 and 3 exist to catch.
    """
    window = REGISTRY[name].window
    match = _WINDOW.match(window or "")
    if match is None:
        raise ValueError(
            f"{name} declares window={window!r}, which is not a single trailing span. A feature "
            "with no window, an unbounded one or a nested pair has no one interval to accumulate"
        )
    return timedelta(seconds=int(match.group(1)) * _UNIT_SECONDS[match.group(2)])


def _horizon(key: HistoryKey) -> timedelta:
    """How far back arrivals keyed by `key` are kept: the longest window any such feature reads.

    Derived from the registry rather than written as a constant, so that registering a feature with
    a longer window cannot leave the store quietly evicting rows that feature needs — a defect that
    would show up as a feature reading low rather than as an error. Features whose window is
    unbounded are **not** covered: they need durable state rather than a longer rolling window,
    which is exactly what `history_requirement=DURABLE` says.
    """
    spans = [
        window_of(name)
        for name, spec in REGISTRY.items()
        if spec.contract is not None
        and spec.contract.history_key is key
        and _WINDOW.match(spec.window or "")
    ]
    if not spans:
        raise ValueError(
            f"no windowed feature is keyed by {key.value}, so there is nothing to keep"
        )
    return max(spans)


_ACCOUNT_HORIZON = _horizon(HistoryKey.ACCOUNT)
_COUNTERPARTY_HORIZON = _horizon(HistoryKey.COUNTERPARTY)
_DEVICE_HORIZON = _horizon(HistoryKey.DEVICE)
_AGENT_HORIZON = _horizon(HistoryKey.AGENT)

#: The MAD-to-sigma constant, so the robust z-score is on the standard-deviation scale under
#: normality. The same number as the batch path's, because it is a property of the estimator
#: rather than a choice either path gets to make.
_MAD_TO_SIGMA = 1.4826

#: `just_below_limit_flag` fires in `[limit * (1 - _LIMIT_BAND), limit)`.
_LIMIT_BAND = 0.05

#: The speed above which the journey did not happen. Above commercial cruising speed, so a value
#: at the cap is a proxy for a shared account, a credential used elsewhere or a spoofed location,
#: never for travel. It saturates so that nothing can split inside the impossible range.
_MAX_IMPLIED_SPEED_KMH = 1000.0

#: Part E.2's "account age under 30 d", a threshold rather than a ramp because the specification
#: names a cliff and smoothing it would hide a second modelling decision inside a heuristic.
_NEW_ACCOUNT_DAYS = 30.0

#: The composite's internal "recent" window, and the share of its 30 d window that a
#: constant-rate account puts in it. The ramp term measures departure from that share, so an even
#: account scores 0 and the term means "faster than its own baseline" rather than "recent".
_RAMP_SHORT_WINDOW = timedelta(days=7)
_EVEN_RATE_SHARE = _RAMP_SHORT_WINDOW / timedelta(days=30)


def _minimum_observations(name: str) -> int:
    """The declared minimum-history threshold, from the registry rather than a literal here."""
    contract = REGISTRY[name].contract
    minimum = contract.minimum_history if contract is not None else None
    if minimum is None:
        raise ValueError(f"{name} declares no minimum_history, so there is no threshold to apply")
    return minimum.minimum_observations


def _median(values: Sequence[float]) -> float:
    """The median, averaging the two middle values for an even count.

    Defined here and again in the batch path rather than shared. "Median" has two defensible
    readings for an even sample — the lower middle, or the mean of the two — and a shared helper
    would make both paths agree on whichever it chose, which is precisely the fork the parity
    suite exists to surface. Hand-computed tests pin both, including an even-count case.
    """
    ordered = sorted(values)
    half, remainder = divmod(len(ordered), 2)
    if remainder:
        return ordered[half]
    return (ordered[half - 1] + ordered[half]) / 2.0


@dataclass(frozen=True)
class Arrival:
    """What the online store keeps per transaction, and nothing more.

    Not a `Transaction`: the store holds what the windowed features read, so that what is kept is
    a decision rather than a side effect of the record's shape. Everything here is evicted with the
    rolling window; anything a feature needs after that has to be `DURABLE` and live elsewhere.
    """

    at: datetime
    amount_rwf: float
    counterparty_id: str | None
    latitude: float
    longitude: float
    device_fingerprint: str | None = None


@dataclass
class AccountState:
    """Per-account online state.

    `first_seen_at` is the `DURABLE` field. It is held separately from `arrivals` precisely because
    it is not recoverable from them: once an account is older than the rolling window, the window
    has forgotten when it started, and a cache flush would silently switch the feature from
    OBSERVED_CAPPED to a shorter apparent history. The cold-cache parity case is the only test that
    exercises this.
    """

    first_seen_at: datetime | None = None
    #: The previous transaction, which `seconds_since_last_tx` needs with **no window bound** and
    #: the geographic pair needs a location from. Held explicitly rather than read off the end of
    #: `arrivals`, because an account dormant longer than the rolling horizon has had its last
    #: arrival evicted while the gap it measures is precisely what matters — the feature would
    #: read NaN for the dormant accounts it exists to notice.
    #:
    #: Unlike `first_seen_at`, `observe()` **does** set this: the last arrival genuinely is the
    #: last transaction, which is an observation rather than the inference that made DURABLE
    #: decorative for first-seen. What it cannot survive is a flush, which is why
    #: `restore_last_transaction` exists and why the feature is NaN until it is called.
    last_transaction: Arrival | None = None
    #: Every counterparty country this account has ever sent to. `DURABLE` and unbounded: the set
    #: is not recoverable from a rolling window, and a flush that lost it would make every
    #: corridor look new on every established account simultaneously — the same shape as PB-37's
    #: first-seen, in a different field.
    countries: set[str] = field(default_factory=set)
    #: Every counterparty this account has ever paid. `DURABLE` and unbounded, for the same reason
    #: as `countries`: a flush that lost it would report every established payee as new, across
    #: the whole account base, at the moment the system is recovering.
    counterparties: set[str] = field(default_factory=set)
    #: Every device fingerprint this account has ever used. The fifth durable field, unbounded for
    #: the same reason as the other two sets: a flush would report every familiar handset as new.
    devices: set[str] = field(default_factory=set)
    arrivals: deque[Arrival] = field(default_factory=deque)

    def evict_before(self, cutoff: datetime) -> None:
        while self.arrivals and self.arrivals[0].at <= cutoff:
            self.arrivals.popleft()


@dataclass
class CounterpartyState:
    """Per-counterparty online state: who sent, when, and which transaction it was.

    Keyed by the **counterparty**, so E1's account-grouped folds do not isolate the features that
    read it — a ring moving money from ten victims into one mule produces ten rows reading this
    one object. Each such feature declares `cross_account_control` in the registry, and the store
    being separate from `AccountState` is what makes that visible in the code rather than only in
    the contract.
    """

    arrivals: deque[tuple[datetime, str, str]] = field(default_factory=deque)

    def evict_before(self, cutoff: datetime) -> None:
        while self.arrivals and self.arrivals[0][0] <= cutoff:
            self.arrivals.popleft()


@dataclass
class DeviceState:
    """Per-device online state: which accounts used it and when, plus its durable first sighting.

    `first_seen_at` is held separately and is **not** set by `observe`, on exactly PB-37's
    reasoning: after a flush the earliest arrival is the rolling window's edge rather than the
    device's first sighting, so inferring it would report every device in the estate as a few days
    old at the moment the store came back — which is the direction that reads as suspicious, for
    the entire estate at once.
    """

    first_seen_at: datetime | None = None
    arrivals: deque[tuple[datetime, str]] = field(default_factory=deque)

    def evict_before(self, cutoff: datetime) -> None:
        while self.arrivals and self.arrivals[0][0] <= cutoff:
            self.arrivals.popleft()


@dataclass
class AgentState:
    """Per-agent online state: who transacted, when, and under which category code.

    Keyed by the AGENT, so customers of one agent read one object and E1's account grouping does
    not isolate it. The category code is kept because `agent_cashout_count_1h` counts cash
    disbursements rather than every transaction — a distinction the current dataset does not make,
    which is precisely why the store must be able to.
    """

    arrivals: deque[tuple[datetime, str, str | None]] = field(default_factory=deque)

    def evict_before(self, cutoff: datetime) -> None:
        while self.arrivals and self.arrivals[0][0] <= cutoff:
            self.arrivals.popleft()


@dataclass
class CellState:
    """Per-cell online state: arrivals and the labels that have landed for them."""

    arrivals: deque[tuple[datetime, str]] = field(default_factory=deque)

    def evict_before(self, cutoff: datetime) -> None:
        while self.arrivals and self.arrivals[0][0] <= cutoff:
            self.arrivals.popleft()


class OnlineFeatures:
    """Accumulates the state the online path serves from."""

    def __init__(self) -> None:
        self._accounts: dict[str, AccountState] = {}
        self._counterparties: dict[str, CounterpartyState] = {}
        self._devices: dict[str, DeviceState] = {}
        self._agents: dict[str, AgentState] = {}
        self._cells: dict[str, CellState] = {}
        self._outcomes: dict[str, Outcome] = {}
        #: A memo of corridor classifications, keyed by the ordered country pair. Serving resolves
        #: this on every request and the answer depends on nothing but two packs, so it is the one
        #: thing in this class that is genuinely cacheable. It is also the one new way this feature
        #: can be wrong online and not in batch, which is why it is here rather than in a comment:
        #: a memo keyed on one country instead of the pair returns the previous corridor's class,
        #: and every hand-computed test would still pass because each computes a pair once.
        self._corridors: dict[tuple[str, str], str] = {}

    # ---- state ------------------------------------------------------------------------------

    def observe(self, transaction: Transaction) -> None:
        """Fold a transaction into state. Call **after** `compute` for that transaction."""
        account = self._accounts.setdefault(transaction.account_id, AccountState())
        # Deliberately does NOT set first_seen_at. Taking the first arrival as the account's start
        # is the invention that makes `DURABLE` decorative: after a flush the earliest arrival is
        # the rolling window's edge, not the account's beginning. The caller supplies it through
        # `restore_first_seen`, from the durable store or - for a genuinely new account - from the
        # transaction itself, having consulted that store. Until PB-37's table exists there is no
        # store, so this feature fails closed.
        arrival = Arrival(
            at=transaction.timestamp,
            amount_rwf=transaction.amount_rwf,
            counterparty_id=transaction.counterparty_id,
            latitude=transaction.latitude,
            longitude=transaction.longitude,
            device_fingerprint=transaction.device_fingerprint,
        )
        account.arrivals.append(arrival)
        # Monotone rather than assigned: a replay that delivers an out-of-order arrival must not
        # move "the last transaction" backwards, which would turn the next gap negative.
        if account.last_transaction is None or arrival.at >= account.last_transaction.at:
            account.last_transaction = arrival
        if transaction.counterparty_country is not None:
            account.countries.add(transaction.counterparty_country)
        # Evicted at the longest window any account-keyed feature reads, not at any one feature's:
        # a store that forgot what the 90 d features need would leave them silently short.
        account.evict_before(transaction.timestamp - _ACCOUNT_HORIZON)

        if transaction.counterparty_id is not None:
            account.counterparties.add(transaction.counterparty_id)
            counterparty = self._counterparties.setdefault(
                transaction.counterparty_id, CounterpartyState()
            )
            counterparty.arrivals.append(
                (transaction.timestamp, transaction.account_id, transaction.transaction_id)
            )
            counterparty.evict_before(transaction.timestamp - _COUNTERPARTY_HORIZON)

        if transaction.device_fingerprint is not None:
            account.devices.add(transaction.device_fingerprint)
            device = self._devices.setdefault(transaction.device_fingerprint, DeviceState())
            device.arrivals.append((transaction.timestamp, transaction.account_id))
            device.evict_before(transaction.timestamp - _DEVICE_HORIZON)

        if transaction.agent_id is not None:
            agent = self._agents.setdefault(transaction.agent_id, AgentState())
            agent.arrivals.append(
                (
                    transaction.timestamp,
                    transaction.account_id,
                    transaction.merchant_category_code,
                )
            )
            agent.evict_before(transaction.timestamp - _AGENT_HORIZON)

        cell = self._cells.setdefault(
            h3_cell(transaction.latitude, transaction.longitude), CellState()
        )
        cell.arrivals.append((transaction.timestamp, transaction.transaction_id))
        cell.evict_before(transaction.timestamp - _CELL)

    def restore_first_seen(self, account_id: str, first_seen_at: datetime) -> None:
        """Load an account's durable first-seen timestamp from persistent storage.

        **Required after a cache flush, and on any replay that does not start at an account's very
        first transaction.** Without it the online path infers first-seen from its earliest
        *arrival*, which is the rolling window's edge rather than the account's start — silently
        switching `velocity_ratio_1h_vs_30d` from OBSERVED_CAPPED to a shorter apparent history and
        inflating the ratio for every established account at once.

        That this method must exist is the operational content of `history_requirement=DURABLE`:
        the value cannot be reconstructed from anything the cache holds, so something outside the
        cache has to supply it (PB-37 records that M1 has no table for it yet).
        """
        state = self._accounts.setdefault(account_id, AccountState())
        state.first_seen_at = first_seen_at

    def restore_last_transaction(
        self, account_id: str, at: datetime, latitude: float, longitude: float
    ) -> None:
        """Load the account's previous transaction from durable storage after a flush.

        The counterpart of `restore_first_seen`, and needed for the same reason with a different
        mechanism. `seconds_since_last_tx` declares no window at all, so there is no span after
        which the rolling cache is whole again: a flush loses the gap for every account until each
        transacts twice more, and the accounts it loses it for longest are the dormant ones the
        feature exists to notice.

        Cheaper to satisfy than first-seen, and worth saying so: the previous transaction is a
        recent row of `transactions`, so the DB fallback can answer it exactly, which is why the
        feature is `fallback_behaviour=EXACT` while the device features are not.
        """
        state = self._accounts.setdefault(account_id, AccountState())
        state.last_transaction = Arrival(
            at=at, amount_rwf=0.0, counterparty_id=None, latitude=latitude, longitude=longitude
        )

    def restore_countries(self, account_id: str, countries: set[str]) -> None:
        """Load the account's lifetime set of counterparty countries after a flush.

        The third durable field, alongside the first-seen timestamp and the previous transaction.
        Without it `is_new_country_for_account` returns True for every corridor after a flush,
        which fires a novelty signal across the whole established account base during a recovery —
        PB-37's failure shape in a different field, and worth the same treatment.
        """
        state = self._accounts.setdefault(account_id, AccountState())
        state.countries = set(countries)

    def restore_counterparties(self, account_id: str, counterparties: set[str]) -> None:
        """Load the account's lifetime set of counterparties after a flush.

        The fourth durable field. Same shape as `restore_countries`, and worth its own method
        rather than a combined "restore everything": each durable field is a separate schema
        commitment, and a caller that can supply one and not another should fail on the one it
        cannot rather than silently supply an empty set for it.
        """
        state = self._accounts.setdefault(account_id, AccountState())
        state.counterparties = set(counterparties)

    def restore_devices(self, account_id: str, devices: set[str]) -> None:
        """Load the account's lifetime set of device fingerprints after a flush."""
        state = self._accounts.setdefault(account_id, AccountState())
        state.devices = set(devices)

    def restore_device_first_seen(self, fingerprint: str, first_seen_at: datetime) -> None:
        """Load a device's first sighting anywhere in the institution, from durable storage.

        Required after a flush and on any replay that does not start at the device's very first
        appearance. `observe` will not infer it: the inference is the one that made `DURABLE`
        decorative for the account's first-seen, and it fails the same way here — the earliest
        arrival is the window's edge, not the device's beginning.
        """
        state = self._devices.setdefault(fingerprint, DeviceState())
        state.first_seen_at = first_seen_at

    def observe_outcome(self, outcome: Outcome) -> None:
        """Record a label. It becomes visible only from its `available_at`, not on arrival here."""
        self._outcomes[outcome.transaction_id] = outcome

    def flush_cache(self) -> None:
        """Drop everything a cache holds, keeping nothing.

        The cold-cache parity case calls this mid-replay. A `DURABLE` field that does not survive
        it is a contract violation, and this is the only place that becomes visible.
        """
        self._accounts.clear()
        self._counterparties.clear()
        self._devices.clear()
        self._agents.clear()
        self._cells.clear()
        # Cleared too, though it is a memo of version-controlled pack data rather than history:
        # a cache that survives a flush because someone judged it safe is a claim, and the cheaper
        # thing is to drop it and assert the value does not move.
        self._corridors.clear()

    # ---- features ---------------------------------------------------------------------------

    def velocity_ratio_1h_vs_30d(self, scored: Transaction) -> float:
        """See `registry`'s contract. Computed from arrivals, not from a filtered history."""
        alpha = smoothing_for("velocity_ratio_1h_vs_30d").alpha

        state = self._accounts.get(scored.account_id)
        if state is None or state.first_seen_at is None:
            # FAIL CLOSED (PB-37). `history_basis=OBSERVED_CAPPED` divides by history actually
            # observed, so without a durable first-seen there is no denominator - only a guess.
            # A guess here is not a small error: restoring arrivals from the database while the
            # per-account first-seen is missing divides thirty days of rows by whatever span the
            # cache happens to hold, collapsing the ratio on every established account at once,
            # during a recovery, when the system is already degraded.
            #
            # NaN instead. The models' native missing handling (D-04) covers it, and a missing
            # feature during recovery is honest where a plausible wrong number is training/serving
            # skew arriving exactly when nobody is positioned to notice.
            return float("nan")

        t = scored.timestamp
        short_edge = t - _SHORT
        long_edge = t - _LONG

        short_count = 0
        long_count = 0
        for arrival in state.arrivals:
            if arrival.at >= t:
                continue
            if arrival.at > short_edge:
                short_count += 1
            elif arrival.at > long_edge:
                long_count += 1

        observed_seconds = (t - state.first_seen_at).total_seconds()
        capped = min(observed_seconds, _LONG.total_seconds())
        baseline_hours = (capped - _SHORT.total_seconds()) / 3600.0
        long_mean = long_count / baseline_hours if baseline_hours > 0 else 0.0

        return (short_count + alpha) / (long_mean + alpha)

    def _in_window(self, scored: Transaction, name: str) -> list[Arrival]:
        """This account's arrivals inside the feature's trailing window.

        The interval is open at both ends: `(t - W, t)`. The upper end is
        `self_inclusion=EXCLUDED` — `compute` runs before `observe`, so the scored transaction is
        not in the store yet, and the explicit test is the belt to that brace for a caller who
        replays out of order. The lower end matches the partition
        `velocity_ratio_1h_vs_30d` already fixed, so a row exactly on `t - W` is outside.
        """
        state = self._accounts.get(scored.account_id)
        if state is None:
            return []
        return self._in_span(scored, window_of(name))

    def _in_span(self, scored: Transaction, span: timedelta) -> list[Arrival]:
        """The same open interval against an explicit span, for the composite's internal window.

        The composite's 7 d "recent" window is part of its own documented form rather than a
        declared window of any feature, so it has no name to look up.
        """
        state = self._accounts.get(scored.account_id)
        if state is None:
            return []
        start = scored.timestamp - span
        return [a for a in state.arrivals if start < a.at < scored.timestamp]

    def tx_count(self, scored: Transaction, name: str) -> int:
        """`tx_count_60s`, `tx_count_1h`, `tx_count_24h` or `tx_count_7d`. See `registry`."""
        return len(self._in_window(scored, name))

    def amount_sum(self, scored: Transaction, name: str) -> float:
        """`amount_sum_24h` or `amount_sum_7d`, from arrivals rather than a filtered history.

        Each arrival carries the amount already converted at its own transaction date, so the
        window cannot be re-priced at the scored transaction's rate. Accumulated in arrival order,
        which is not the order the batch path uses — the reassociation ADR 0025's relative
        tolerance exists to permit, and parity mutation 1 is the control proving the tolerance
        still admits it.
        """
        return sum(a.amount_rwf for a in self._in_window(scored, name))

    def unique_counterparties_24h(self, scored: Transaction) -> int:
        """Distinct counterparties in the trailing 24 h. An exact set, never a sketch."""
        seen: set[str] = set()
        for arrival in self._in_window(scored, "unique_counterparties_24h"):
            if arrival.counterparty_id is None:
                raise ValueError(
                    "an arrival carries no counterparty_id; counting those together would make "
                    "unrelated payments look like one relationship"
                )
            seen.add(arrival.counterparty_id)
        return len(seen)

    # ---- amount behaviour --------------------------------------------------------------------

    def amount_log1p(self, scored: Transaction) -> float:
        """log1p of the scored amount. Reads the request, so it needs no state at all."""
        return math.log1p(scored.amount_rwf)

    def amount_zscore_90d(self, scored: Transaction) -> float:
        """Robust z-score against the account's prior 90 days of amounts.

        Recomputed from the retained arrivals rather than from a running median, deliberately: a
        streaming median is an approximation, and ADR 0025's tolerance is three orders of
        magnitude tighter than an approximate quantile. A real store would keep the window and
        sort it, which is what this does.
        """
        amounts = [a.amount_rwf for a in self._in_window(scored, "amount_zscore_90d")]
        if len(amounts) < _minimum_observations("amount_zscore_90d"):
            return float("nan")
        centre = _median(amounts)
        spread = _median([abs(value - centre) for value in amounts])
        if spread == 0.0:
            # No scale to divide by. NaN rather than 0.0: zero is the most normal possible
            # z-score, so an account with one repeated amount would read as perfectly typical.
            return float("nan")
        return (scored.amount_rwf - centre) / (_MAD_TO_SIGMA * spread)

    def amount_to_max_90d_ratio(self, scored: Transaction) -> float:
        """The scored amount over the largest in the prior 90 days, NaN with no prior amount."""
        amounts = [a.amount_rwf for a in self._in_window(scored, "amount_to_max_90d_ratio")]
        if not amounts:
            return float("nan")
        largest = max(amounts)
        return scored.amount_rwf / largest if largest > 0.0 else float("nan")

    def round_sum_flag(
        self, scored: Transaction, denominations: Mapping[str, Sequence[int]]
    ) -> bool:
        """Exact multiple of a denomination, in integer minor units. See `registry`."""
        amount, currency = scored.amount_minor, scored.currency
        if amount is None or currency is None:
            raise ValueError(
                f"{scored.transaction_id}: round_sum_flag needs amount_minor and currency; "
                "roundness is a property of the amount the payer entered, not of its conversion"
            )
        steps = denominations.get(currency)
        if not steps:
            raise KeyError(
                f"{scored.transaction_id}: {currency!r} has no denominations. They come from the "
                "country packs (ADR 0023), so this is a deployment serving a currency it has no "
                "pack for, not a currency in which nothing is round"
            )
        return any(step > 0 and amount % step == 0 for step in steps)

    def just_below_limit_flag(
        self,
        scored: Transaction,
        limits: Sequence[OperationalLimit],
        *,
        kyc_tier: int | None,
    ) -> bool:
        """The 5% band below a limit in force at the transaction (ADR 0026).

        The online path reads what is live, which is the *same* rule stated from the other side:
        at scoring time "as of the transaction" and "as of now" coincide. They diverge on replay
        and in training, which is why the batch path carries the same resolution rather than
        trusting that the two agree — and why the parity fixture must span a configuration change
        or both paths agree while the batch one is wrong.
        """
        in_force: dict[tuple[str, str], OperationalLimit] = {}
        for limit in limits:
            if limit.dimension is LimitDimension.ACTIVE_RULE_THRESHOLD:
                raise ValueError(
                    "just_below_limit_flag cannot resolve an active rule threshold as of the "
                    "transaction: M1 records when a rule version was created, not when it became "
                    "effective, and keeps no history of enablement (ADR 0026). The clause is "
                    "unimplemented rather than quietly computed against today's rules"
                )
            if limit.effective_at > scored.timestamp:
                continue
            if limit.dimension is LimitDimension.CHANNEL:
                if limit.applies_to != scored.channel:
                    continue
            elif kyc_tier is None or limit.applies_to != str(kyc_tier):
                continue
            key = (limit.dimension.value, limit.applies_to)
            seen = in_force.get(key)
            if seen is None or limit.effective_at > seen.effective_at:
                in_force[key] = limit

        floor = 1.0 - _LIMIT_BAND
        return any(
            limit.amount_rwf * floor <= scored.amount_rwf < limit.amount_rwf
            for limit in in_force.values()
        )

    # ---- temporal ----------------------------------------------------------------------------

    def _local(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> datetime:
        """Local civil time, derived from the pack rather than carried on the transaction (D-43).

        Written against the same pack field the batch path reads, with its own arithmetic: holding
        a stored local time beside the UTC one is parity mutation 4, and deriving it twice is what
        makes that mutation detectable.
        """
        country = scored.account_country
        if country is None:
            raise ValueError(
                f"{scored.transaction_id}: the temporal features need account_country. UTC is "
                "itself a plausible-looking local time, so a fallback would shift every "
                "hour-of-day feature silently"
            )
        pack = countries.get(country)
        if pack is None:
            raise KeyError(
                f"{scored.transaction_id}: {country!r} has no country pack, so no UTC offset"
            )
        return scored.timestamp + timedelta(hours=pack.utc_offset_hours)

    def local_hour(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> int:
        """The integer local hour 0-23, as the registry declares for the sin/cos pair."""
        return self._local(scored, countries).hour

    def local_hour_sin(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> float:
        radians = 2.0 * math.pi * self.local_hour(scored, countries) / 24.0
        return math.sin(radians)

    def local_hour_cos(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> float:
        radians = 2.0 * math.pi * self.local_hour(scored, countries) / 24.0
        return math.cos(radians)

    def local_day_of_week(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> int:
        """Monday=0, from local time: 23:30 UTC on a Sunday is Monday in every positive offset."""
        return self._local(scored, countries).weekday()

    def is_local_night(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> bool:
        return 0 <= self.local_hour(scored, countries) <= 4

    def is_month_end_window(
        self, scored: Transaction, countries: Mapping[str, CountryFacts]
    ) -> bool:
        """The last 3 and first 2 days of the **local** calendar month."""
        local = self._local(scored, countries)
        if local.day <= 2:
            return True
        next_month = (
            local.replace(year=local.year + 1, month=1, day=1)
            if local.month == 12
            else local.replace(month=local.month + 1, day=1)
        )
        return local.day > (next_month - timedelta(days=1)).day - 3

    def seconds_since_last_tx(self, scored: Transaction) -> float:
        """Seconds since this account's previous transaction, NaN when there is none.

        Reads the durable `last_transaction` rather than the rolling window: the feature declares
        no window, so an account quiet for longer than the horizon has no arrival left and the gap
        it measures is exactly the one worth having.
        """
        state = self._accounts.get(scored.account_id)
        if state is None or state.last_transaction is None:
            return float("nan")
        if state.last_transaction.at >= scored.timestamp:
            # The scored transaction has already been observed, or a replay is out of order.
            # A negative or zero gap is not a value this feature has; NaN says so.
            return float("nan")
        return (scored.timestamp - state.last_transaction.at).total_seconds()

    # ---- geographic ----------------------------------------------------------------------------

    def distance_from_last_tx_km(self, scored: Transaction) -> float:
        """Great-circle distance from the account's previous transaction, NaN when there is none.

        Reads the durable `last_transaction`, so it survives eviction and does not survive a flush:
        the same `DURABLE` state `seconds_since_last_tx` reads, which is why the flush case covers
        both at once and why the arrival record carries a location at all.
        """
        previous = self._previous(scored)
        if previous is None:
            return float("nan")
        return haversine_km(
            previous.latitude, previous.longitude, scored.latitude, scored.longitude
        )

    def implied_speed_kmh(self, scored: Transaction) -> float:
        """Distance over elapsed hours, capped; the cap also covers a zero elapsed time."""
        previous = self._previous(scored)
        if previous is None:
            return float("nan")
        distance = haversine_km(
            previous.latitude, previous.longitude, scored.latitude, scored.longitude
        )
        # `_previous` returns only a strictly earlier arrival, so the gap cannot be zero. The
        # guard that used to sit here was unreachable; see registry.SIMULTANEOUS_PREDECESSOR_NOTE
        # for what happens instead when the only prior transaction shares the timestamp.
        hours = (scored.timestamp - previous.at).total_seconds() / 3600.0
        return min(distance / hours, _MAX_IMPLIED_SPEED_KMH)

    def distance_from_home_centroid_km(self, scored: Transaction) -> float:
        """Distance from the component-wise median of the prior 90 days of locations."""
        window = self._in_window(scored, "distance_from_home_centroid_km")
        if not window:
            return float("nan")
        centre_lat = _median([a.latitude for a in window])
        centre_lon = _median([a.longitude for a in window])
        return haversine_km(centre_lat, centre_lon, scored.latitude, scored.longitude)

    def _previous(self, scored: Transaction) -> Arrival | None:
        """The durable previous transaction, or None when it is unknown or not earlier."""
        state = self._accounts.get(scored.account_id)
        if state is None or state.last_transaction is None:
            return None
        if state.last_transaction.at >= scored.timestamp:
            return None
        return state.last_transaction

    def is_new_country_for_account(self, scored: Transaction) -> bool:
        """True when this account has never sent to the counterparty's country before.

        Unbounded, so the set of countries is `DURABLE` state a rolling window cannot rebuild: a
        flush would make every corridor look new and fire this on the entire established account
        base at once. Held in its own set for that reason, and dropped by `flush_cache` so the loss
        is visible rather than papered over by whatever the window happens to still hold.
        """
        country = scored.counterparty_country
        if country is None:
            raise ValueError(
                f"{scored.transaction_id}: is_new_country_for_account needs counterparty_country; "
                "rows without one would share a destination and the second would read as familiar"
            )
        state = self._accounts.get(scored.account_id)
        if state is None:
            return True
        return country not in state.countries

    # ---- counterparty --------------------------------------------------------------------------

    def counterparty_is_new_for_account(self, scored: Transaction) -> bool:
        """True when this account has never paid this counterparty. Unbounded, so `DURABLE`."""
        counterparty = _counterparty_of(scored)
        state = self._accounts.get(scored.account_id)
        if state is None:
            return True
        return counterparty not in state.counterparties

    def counterparty_unique_senders_24h(self, scored: Transaction) -> int:
        """Distinct accounts that sent to this counterparty in the trailing 24 h — the mule signal.

        `history_key=COUNTERPARTY`: this reads other accounts' rows by construction, which is the
        whole content of the feature. A legitimate recipient rarely acquires many unrelated senders
        at once; a mule does, and the senders are exactly the accounts a per-account view cannot
        see. That is also why the feature needs `cross_account_control` in the registry — E1's
        account-grouped folds do not isolate a leak that travels through the counterparty.
        """
        counterparty = _counterparty_of(scored)
        state = self._counterparties.get(counterparty)
        if state is None:
            return 0
        start = scored.timestamp - window_of("counterparty_unique_senders_24h")
        return len({account for at, account, _ in state.arrivals if start < at < scored.timestamp})

    def counterparty_confirmed_fraud_90d(self, scored: Transaction) -> int:
        """Confirmed-fraud transactions involving this counterparty in the prior 90 d.

        Gated on `label_available_at`, never on confirmation: counting a label that had not
        arrived imports the investigation delay straight into the feature, and the online path
        structurally cannot do it — which is why the batch path has to be stopped from doing it.
        """
        counterparty = _counterparty_of(scored)
        state = self._counterparties.get(counterparty)
        if state is None:
            return 0
        start = scored.timestamp - window_of("counterparty_confirmed_fraud_90d")
        confirmed = 0
        for at, _, transaction_id in state.arrivals:
            if not (start < at < scored.timestamp) or transaction_id == scored.transaction_id:
                continue
            outcome = self._outcomes.get(transaction_id)
            if outcome is None or outcome.available_at >= scored.timestamp:
                continue
            confirmed += int(outcome.is_fraud)
        return confirmed

    def tx_count_to_counterparty_30d(self, scored: Transaction) -> int:
        """Transactions from this account to this counterparty in the trailing 30 d.

        Keyed by the ACCOUNT, not the counterparty: it is a statement about this pair's
        relationship, so it reads the account's own arrivals and filters them. Low for a first
        transfer to a new payee, which is the established-relationship signal.
        """
        counterparty = _counterparty_of(scored)
        window = self._in_window(scored, "tx_count_to_counterparty_30d")
        return sum(1 for arrival in window if arrival.counterparty_id == counterparty)

    def counterparty_account_age_days(
        self, scored: Transaction, opened_at: datetime | None
    ) -> float:
        """Days since the counterparty account was opened, NaN when that is unknown.

        Supplied rather than accumulated: this is a static attribute of the counterparty, and the
        store holds arrivals. Deriving it from the earliest arrival the cache has seen would bound
        every counterparty's age by the cache's own age and report the entire population as young
        — a plausible number that moves with how recently the store was flushed.
        """
        if opened_at is None:
            return float("nan")
        return (scored.timestamp - opened_at).total_seconds() / 86_400.0

    # ---- device and channel ----------------------------------------------------------------

    def channel(self, scored: Transaction) -> str:
        """The transaction's channel, checked against the registry's declared values."""
        value = scored.channel
        if value is None:
            raise ValueError(
                f"{scored.transaction_id}: channel is mandatory and constrained at ingestion"
            )
        if value not in categories_for("channel"):
            raise ValueError(
                f"{scored.transaction_id}: channel {value!r} is undeclared; it would reach the "
                "model as an unseen level rather than as an error"
            )
        return value

    def device_is_new_for_account(self, scored: Transaction) -> float:
        """1.0 for an unfamiliar device, 0.0 for a familiar one, NaN when there is no device."""
        device = scored.device_fingerprint
        if device is None:
            return float("nan")
        state = self._accounts.get(scored.account_id)
        if state is None:
            return 1.0
        return 0.0 if device in state.devices else 1.0

    def accounts_per_device_7d(self, scored: Transaction) -> float:
        """Distinct accounts on this device in the trailing 7 d, NaN when there is no device.

        `fallback_behaviour=NAN_UNDER_FALLBACK`: cross-account device state lives only in the
        online store, so the DB fallback has nothing to read and the feature is declared absent
        rather than approximated. Identically 1 on the current dataset (PB-40).
        """
        device = scored.device_fingerprint
        if device is None:
            return float("nan")
        state = self._devices.get(device)
        accounts = {scored.account_id}
        if state is not None:
            start = scored.timestamp - window_of("accounts_per_device_7d")
            accounts |= {a for at, a in state.arrivals if start < at < scored.timestamp}
        return float(len(accounts))

    def device_changes_24h(self, scored: Transaction) -> float:
        """Distinct devices on this account in 24 h minus one, NaN when there is no device."""
        device = scored.device_fingerprint
        if device is None:
            return float("nan")
        devices = {
            arrival.device_fingerprint
            for arrival in self._in_window(scored, "device_changes_24h")
            if arrival.device_fingerprint is not None
        }
        devices.add(device)
        return float(len(devices) - 1)

    def device_age_days(self, scored: Transaction) -> float:
        """Days since this device was first seen anywhere, NaN without a durable first sighting.

        Fails closed on the same terms as the account's first-seen (PB-37): without the durable
        value there is no age, only a guess bounded by however long the cache has been warm.
        """
        device = scored.device_fingerprint
        if device is None:
            return float("nan")
        state = self._devices.get(device)
        if state is None or state.first_seen_at is None:
            return float("nan")
        return (scored.timestamp - state.first_seen_at).total_seconds() / 86_400.0

    # ---- account profile -----------------------------------------------------------------------

    def account_age_days(self, scored: Transaction, opened_at: datetime | None) -> float:
        """Days since the account was opened, NaN when that is unknown.

        Supplied, not accumulated, and distinct from the first-seen-in-data timestamp: an account
        may be opened long before it transacts, so the store's earliest arrival is a different
        quantity that happens to have similar units.
        """
        if opened_at is None:
            return float("nan")
        return (scored.timestamp - opened_at).total_seconds() / 86_400.0

    def kyc_tier(self, scored: Transaction, assignments: Sequence[TierAssignment]) -> float:
        """The tier in force at the transaction, NaN when none had taken effect (ADR 0026).

        At scoring time "as of the transaction" and "as of now" coincide, which is exactly why the
        batch path carries the same resolution rather than trusting the two to agree: they diverge
        on replay and in training, and nowhere else.
        """
        in_force = [a for a in assignments if a.effective_at <= scored.timestamp]
        if not in_force:
            return float("nan")
        return float(max(in_force, key=lambda a: a.effective_at).tier)

    def days_since_sim_swap(self, scored: Transaction, swaps: Sequence[datetime]) -> float:
        """Days since the most recent swap strictly before the transaction, NaN when there is none.

        NaN covers both "no MNO signal" and "signal, no swap". Both are the absence of a date, and
        a large sentinel would read as "a swap long ago" — the safe end of a signal whose
        dangerous end is "a swap an hour ago".
        """
        earlier = [when for when in swaps if when < scored.timestamp]
        if not earlier:
            return float("nan")
        return (scored.timestamp - max(earlier)).total_seconds() / 86_400.0

    def dormancy_reactivation_flag(self, scored: Transaction) -> bool:
        """Silent for 60 d having transacted before, from the durable previous transaction.

        Reads `last_transaction` rather than the rolling window, and that is the whole feature: a
        60-day window can see that an account has been quiet and cannot tell a dormant account
        from a new one. After a flush the durable record is gone, every established account looks
        new, and the flag reads False across the estate — the quiet direction, during a recovery.
        """
        previous = self._previous(scored)
        if previous is None:
            return False
        return previous.at <= scored.timestamp - window_of("dormancy_reactivation_flag")

    # ---- agent ----------------------------------------------------------------------------------

    def agent_float_utilisation_ratio(
        self, scored: Transaction, standing: Sequence[AgentStanding]
    ) -> float:
        """Float drawn down over the limit, both as of the transaction, NaN off an agent."""
        if scored.agent_id is None:
            return float("nan")
        current = _standing_at(standing, scored.timestamp)
        if current is None:
            return float("nan")
        return current.float_balance_rwf / current.float_limit_rwf

    def agent_cashout_count_1h(self, scored: Transaction, cash_out_codes: frozenset[str]) -> float:
        """Cash disbursements at this agent in the trailing 1 h, NaN off an agent."""
        agent = scored.agent_id
        if agent is None:
            return float("nan")
        state = self._agents.get(agent)
        if state is None:
            return 0.0
        start = scored.timestamp - window_of("agent_cashout_count_1h")
        return float(
            sum(
                1
                for at, _, code in state.arrivals
                if code in cash_out_codes and start < at < scored.timestamp
            )
        )

    def agent_unique_customers_1h(self, scored: Transaction) -> float:
        """Distinct accounts at this agent in the trailing 1 h, including the scored one."""
        agent = scored.agent_id
        if agent is None:
            return float("nan")
        accounts = {scored.account_id}
        state = self._agents.get(agent)
        if state is not None:
            start = scored.timestamp - window_of("agent_unique_customers_1h")
            accounts |= {a for at, a, _ in state.arrivals if start < at < scored.timestamp}
        return float(len(accounts))

    def agent_distance_from_registered_km(
        self, scored: Transaction, standing: Sequence[AgentStanding]
    ) -> float:
        """Distance from the agent's registered premises as of the transaction, NaN off an agent."""
        if scored.agent_id is None:
            return float("nan")
        current = _standing_at(standing, scored.timestamp)
        if current is None:
            return float("nan")
        return haversine_km(current.latitude, current.longitude, scored.latitude, scored.longitude)

    # ---- synthetic identity ------------------------------------------------------------------

    def synthetic_identity_score(self, scored: Transaction, evidence: IdentityEvidence) -> float:
        """The four-term composite in [0, 1]. See `batch` and the registry for the form.

        Written from the arrivals the store holds rather than from a filtered history, and with
        its own term functions: the composite is four independent judgements averaged, so four
        places where the two paths could disagree, and sharing the terms would leave parity
        checking only the mean.
        """
        lowest, highest = evidence.kyc_tier_range
        if highest <= lowest:
            raise ValueError(
                f"kyc_tier_range {evidence.kyc_tier_range} has no span, so 'low tier' has no "
                "meaning; the range comes from the country pack"
            )

        tier = evidence.kyc_tier
        low_tier = (
            0.0 if math.isnan(tier) else min(1.0, max(0.0, (highest - tier) / (highest - lowest)))
        )

        if evidence.opened_at is None:
            new_account = 0.0
        else:
            age_days = (scored.timestamp - evidence.opened_at).total_seconds() / 86_400.0
            new_account = 1.0 if age_days < _NEW_ACCOUNT_DAYS else 0.0

        shared = evidence.accounts_on_device
        device_sharing = 0.0 if math.isnan(shared) else min(1.0, max(0.0, (shared - 1.0) / 2.0))

        recent = len(self._in_span(scored, _RAMP_SHORT_WINDOW))
        month = len(self._in_span(scored, window_of("synthetic_identity_score")))
        if month == 0:
            ramp = 0.0
        else:
            share = recent / month
            ramp = min(1.0, max(0.0, (share - _EVEN_RATE_SHARE) / (1.0 - _EVEN_RATE_SHARE)))

        return (low_tier + new_account + device_sharing + ramp) / 4.0

    def geo_cell_fraud_rate_30d(self, scored: Transaction, prior: float) -> float:
        """See `registry`'s contract. Labels are gated on `available_at`, never on confirmation."""
        alpha = smoothing_for("geo_cell_fraud_rate_30d").alpha

        state = self._cells.get(h3_cell(scored.latitude, scored.longitude))
        if state is None:
            return (0 + alpha * prior) / (0 + alpha)

        t = scored.timestamp
        edge = t - _CELL
        total = 0
        fraud = 0
        for arrival, transaction_id in state.arrivals:
            if transaction_id == scored.transaction_id or not (edge < arrival < t):
                continue
            outcome = self._outcomes.get(transaction_id)
            if outcome is None or outcome.available_at >= t:
                continue
            total += 1
            fraud += int(outcome.is_fraud)

        return (fraud + alpha * prior) / (total + alpha)

    def corridor_class(self, scored: Transaction, countries: Mapping[str, CountryFacts]) -> str:
        """See `registry`'s contract and ADR 0023. Classified per request, memoised by pair.

        **Stated plainly: parity is close to tautological for this feature.** It reads the scored
        transaction and two static pack records, with no history and no state, so the two paths
        cannot disagree about a window, a bound or an arrival order — the disagreements the parity
        design exists to surface. The evidence that this feature is *right* is the hand-computed
        table in `test_corridor.py`, not the parity replay; saying so here is cheaper than letting
        a reader infer more assurance from a green parity run than it contains.

        What parity does still cover is the memo below, which batch has no equivalent of.
        """
        domestic, intra_bloc, cross_bloc, intercontinental = categories_for("corridor_class")

        sender_code = _required(scored.account_country, "account_country", scored)
        recipient_code = _required(scored.counterparty_country, "counterparty_country", scored)

        cached = self._corridors.get((sender_code, recipient_code))
        if cached is not None:
            return cached

        sender = _pack(countries, sender_code, scored)
        recipient = _pack(countries, recipient_code, scored)

        if sender_code == recipient_code:
            corridor = domestic
        elif sender.blocs.isdisjoint(recipient.blocs):
            corridor = cross_bloc if sender.continent == recipient.continent else intercontinental
        else:
            corridor = intra_bloc

        self._corridors[(sender_code, recipient_code)] = corridor
        return corridor


def _required(code: str | None, field_name: str, scored: Transaction) -> str:
    """The country code, or a refusal. Serving must not invent one."""
    if code is None:
        raise ValueError(
            f"{scored.transaction_id}: corridor_class needs {field_name}; the scoring request did "
            "not carry it and there is no value that could stand in for it"
        )
    return code


def _pack(countries: Mapping[str, CountryFacts], code: str, scored: Transaction) -> CountryFacts:
    if code not in countries:
        raise KeyError(
            f"{scored.transaction_id}: {code!r} has no country pack. Under ADR 0023 a country is "
            "a pack file, so a missing one is a deployment carrying packs it does not serve"
        )
    return countries[code]


def _counterparty_of(scored: Transaction) -> str:
    """The scored transaction's counterparty, or a refusal.

    Rows without one must not share an identity: they would look like repeated payments to a
    single payee, so the second would read as an established relationship and the mule count would
    lump unrelated recipients together.
    """
    if scored.counterparty_id is None:
        raise ValueError(
            f"{scored.transaction_id}: the counterparty features need counterparty_id; rows "
            "without one would share an identity and read as an established relationship"
        )
    return scored.counterparty_id


def _standing_at(standing: Sequence[AgentStanding], at: datetime) -> AgentStanding | None:
    """The agent's latest standing effective by `at`, or None when it had none.

    None rather than the earliest on record: applying a float limit that did not yet exist to a
    transaction that predates it is ADR 0026's defect with the arrow pointing backwards.
    """
    in_force = [record for record in standing if record.effective_at <= at]
    if not in_force:
        return None
    return max(in_force, key=lambda record: record.effective_at)
