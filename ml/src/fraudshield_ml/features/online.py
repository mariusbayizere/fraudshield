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

from collections import deque
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime, timedelta

from fraudshield_ml.features.primitives import h3_cell
from fraudshield_ml.features.registry import categories_for, smoothing_for
from fraudshield_ml.features.types import CountryFacts, Outcome, Transaction

_SHORT = timedelta(hours=1)
_LONG = timedelta(days=30)
_CELL = timedelta(days=30)


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
    arrivals: deque[datetime] = field(default_factory=deque)

    def evict_before(self, cutoff: datetime) -> None:
        while self.arrivals and self.arrivals[0] <= cutoff:
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
        account.arrivals.append(transaction.timestamp)
        account.evict_before(transaction.timestamp - _LONG)

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

    def observe_outcome(self, outcome: Outcome) -> None:
        """Record a label. It becomes visible only from its `available_at`, not on arrival here."""
        self._outcomes[outcome.transaction_id] = outcome

    def flush_cache(self) -> None:
        """Drop everything a cache holds, keeping nothing.

        The cold-cache parity case calls this mid-replay. A `DURABLE` field that does not survive
        it is a contract violation, and this is the only place that becomes visible.
        """
        self._accounts.clear()
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
            if arrival >= t:
                continue
            if arrival > short_edge:
                short_count += 1
            elif arrival > long_edge:
                long_count += 1

        observed_seconds = (t - state.first_seen_at).total_seconds()
        capped = min(observed_seconds, _LONG.total_seconds())
        baseline_hours = (capped - _SHORT.total_seconds()) / 3600.0
        long_mean = long_count / baseline_hours if baseline_hours > 0 else 0.0

        return (short_count + alpha) / (long_mean + alpha)

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
