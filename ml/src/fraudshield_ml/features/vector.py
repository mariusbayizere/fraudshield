"""The 44-slot feature vector, assembled from the batch path (PB-44, ML-DATA-07).

**Not a third feature path.** This module computes nothing: it calls `batch` and collects the
results under the registry's names. It imports `batch` and never `online`, so the independence the
parity suite depends on is untouched — and a test asserts that neither feature path imports this
one, because a feature path importing its own caller would make the parity test circular.

Its job is to answer a question no single feature can: **which features does the benchmark
actually feed?** A feature whose inputs do not exist returns NaN for every row, D-04's native
missing handling covers that, and nothing raises. The registry's `computable` field declares the
answer and `computability()` checks the declaration against the data.

**What a caller must supply, and why the list is the point.** Everything the features read that is
not a transaction column arrives through `FeatureContext`. Assembling one is how a caller finds
out what it does not have — which is the same discovery this module exists to make loud.
"""

from __future__ import annotations

import math
from bisect import bisect_left
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime

from fraudshield_ml.features import batch
from fraudshield_ml.features.primitives import h3_cell
from fraudshield_ml.features.registry import REGISTRY, Computability
from fraudshield_ml.features.types import (
    AgentStanding,
    CountryFacts,
    IdentityEvidence,
    OperationalLimit,
    Outcome,
    TierAssignment,
    Transaction,
)

#: A feature's value. `str` for the two categoricals, `float` for everything else — including the
#: flags and counts, so that a structural NaN has somewhere to live. A `bool` cannot be NaN, and
#: the D-04 contract needs exactly that.
FeatureValue = float | str


@dataclass(frozen=True)
class FeatureContext:
    """Everything the 44 features read that is not a transaction column.

    Each field has a defined empty value, and an empty one is not an error: it is the state that
    makes the features reading it return NaN for every row, which is precisely what
    `computability()` is built to detect. A context that refused to be empty would hide the gap
    this module exists to report.
    """

    countries: Mapping[str, CountryFacts]
    outcomes: Mapping[str, Outcome] = field(default_factory=dict)
    #: SIM swaps per account, from `account_events`.
    sim_swaps: Mapping[str, Sequence[datetime]] = field(default_factory=dict)
    #: Account opening dates. Empty until M6's per-account table exists (PB-37).
    opened_at: Mapping[str, datetime] = field(default_factory=dict)
    #: KYC tier assignments per account, with `effective_at`. Empty until the same table exists.
    tiers: Mapping[str, Sequence[TierAssignment]] = field(default_factory=dict)
    #: Agent standing over time. Empty until an agent table exists.
    agent_standing: Mapping[str, Sequence[AgentStanding]] = field(default_factory=dict)
    #: Channel and KYC-tier limits with `effective_at` (ADR 0026).
    limits: Sequence[OperationalLimit] = ()
    #: Common denominations in minor units, keyed by currency, from the packs (ADR 0023).
    denominations: Mapping[str, Sequence[int]] = field(default_factory=dict)
    #: Merchant category codes that mean a cash disbursement.
    cash_out_codes: frozenset[str] = frozenset()
    #: Each account's **true** first transaction, and each device's first sighting, from a source
    #: that can see all of history. Absent means "this cannot be established", and the features
    #: that need it are NaN — they are not inferred from the corpus.
    #:
    #: This is the batch counterpart of `restore_first_seen` (PB-37). Taking an account's earliest
    #: row *in the supplied corpus* is the same inference the online path is forbidden to make: a
    #: truncated corpus's earliest row is the window's edge, not the account's beginning, and
    #: `velocity_ratio_1h_vs_30d` divides by observed history, so the error inflates the ratio for
    #: exactly the accounts that look newest. Every run this repository has performed passed a
    #: truncated corpus, so the inference was always wrong and never visibly so.
    first_seen: Mapping[str, datetime] = field(default_factory=dict)
    device_first_seen: Mapping[str, datetime] = field(default_factory=dict)
    #: What each account had already used **before the corpus begins**: counterparties, corridor
    #: countries, devices. Same reason as the timestamps above and the same failure if omitted —
    #: the three novelty flags declare unbounded history, so computing "never before" from a
    #: truncated corpus reports every long-standing payee, corridor and handset as new, and does
    #: so most for the accounts with the longest histories. Empty asserts the corpus is complete.
    counterparties_before: Mapping[str, frozenset[str]] = field(default_factory=dict)
    countries_before: Mapping[str, frozenset[str]] = field(default_factory=dict)
    devices_before: Mapping[str, frozenset[str]] = field(default_factory=dict)
    #: The fitted training-fold base rate the cell rate shrinks toward, and the declared tier range.
    cell_rate_prior: float = 0.0
    kyc_tier_range: tuple[int, int] = (1, 3)


@dataclass(frozen=True)
class CorpusIndex:
    """The corpus grouped by each `history_key`, in timestamp order.

    **Why this exists, and why it changes no value.** Every feature already filters the history it
    is given by its own key — `_within` drops rows of other accounts, the counterparty features
    drop other counterparties, the cell rate recomputes an H3 cell per row. Handing each one a
    slice that already shares its key is therefore exactly equivalent and turns a scan of the whole
    corpus into a scan of the rows that could have mattered.

    It is not an optimisation for its own sake. Without it a scored row costs a pass over the whole
    corpus for each of about twenty features, so a corpus large enough for the window features to
    be non-empty — this dataset has 5,920 accounts, so a ten-thousand-row slice gives under two
    rows per account — is unaffordable, and the measurement would be of a fixture rather than of
    the benchmark. The choice is between indexing and measuring something that is not the dataset.

    Each list is sorted by timestamp, so the rows strictly before a scored transaction are a
    prefix, found by bisection rather than by filtering.
    """

    by_account: dict[str, list[Transaction]]
    by_counterparty: dict[str, list[Transaction]]
    by_device: dict[str, list[Transaction]]
    by_agent: dict[str, list[Transaction]]
    by_cell: dict[str, list[Transaction]]
    #: The cell each row belongs to, computed once. `h3_cell` is the most expensive primitive here
    #: and the cell rate would otherwise call it for every row of every scored transaction.
    cell_of: dict[str, str]

    @staticmethod
    def build(corpus: Sequence[Transaction]) -> CorpusIndex:
        by_account: dict[str, list[Transaction]] = {}
        by_counterparty: dict[str, list[Transaction]] = {}
        by_device: dict[str, list[Transaction]] = {}
        by_agent: dict[str, list[Transaction]] = {}
        by_cell: dict[str, list[Transaction]] = {}
        cell_of: dict[str, str] = {}
        for row in sorted(corpus, key=lambda r: r.timestamp):
            by_account.setdefault(row.account_id, []).append(row)
            if row.counterparty_id is not None:
                by_counterparty.setdefault(row.counterparty_id, []).append(row)
            if row.device_fingerprint is not None:
                by_device.setdefault(row.device_fingerprint, []).append(row)
            if row.agent_id is not None:
                by_agent.setdefault(row.agent_id, []).append(row)
            cell = h3_cell(row.latitude, row.longitude)
            cell_of[row.transaction_id] = cell
            by_cell.setdefault(cell, []).append(row)
        return CorpusIndex(
            by_account=by_account,
            by_counterparty=by_counterparty,
            by_device=by_device,
            by_agent=by_agent,
            by_cell=by_cell,
            cell_of=cell_of,
        )

    def before(
        self, group: dict[str, list[Transaction]], key: str | None, scored: Transaction
    ) -> list[Transaction]:
        """The rows of `key` strictly earlier than `scored`.

        Strictly earlier, by bisecting on the timestamp: a row stamped the same instant is not a
        predecessor, which is the convention every window in the registry uses.
        """
        if key is None:
            return []
        rows = group.get(key, [])
        cut = bisect_left([row.timestamp for row in rows], scored.timestamp)
        return rows[:cut]


def compute(
    corpus: Sequence[Transaction],
    index: int,
    context: FeatureContext,
    corpus_index: CorpusIndex | None = None,
) -> dict[str, FeatureValue]:
    """Every feature for `corpus[index]`, read from the rows strictly before it.

    `corpus` is the whole population in timestamp order and `index` the row being scored, so the
    prefix `corpus[:index]` is both this account's history and the cross-account corpus the
    counterparty-, device-, agent- and cell-keyed features read. Passing a prefix rather than the
    whole corpus is not an optimisation: it is what stops a batch window reaching forward in time,
    and it is the same discipline the parity suite's prefix replay enforces.
    """
    scored = corpus[index]
    account = scored.account_id
    values: dict[str, FeatureValue] = {}

    # Without an index the history is the whole prefix, which is correct and quadratic. With one
    # each feature gets the rows that share its key, which is the same rows after its own filter.
    slices = _histories(corpus, index, scored, corpus_index)
    history = account_history = slices["account"]
    counterparty_history = slices["counterparty"]
    cell_history = slices["cell"]

    first_seen = context.first_seen.get(account)
    values["velocity_ratio_1h_vs_30d"] = (
        batch.velocity_ratio_1h_vs_30d(history, scored, first_seen_at=first_seen)
        if first_seen is not None
        else math.nan
    )
    for name in ("tx_count_60s", "tx_count_1h", "tx_count_24h", "tx_count_7d"):
        values[name] = float(batch.tx_count(history, scored, name))
    for name in ("amount_sum_24h", "amount_sum_7d"):
        values[name] = batch.amount_sum(history, scored, name)
    values["unique_counterparties_24h"] = float(batch.unique_counterparties_24h(history, scored))

    values["amount_log1p"] = batch.amount_log1p(scored)
    values["amount_zscore_90d"] = batch.amount_zscore_90d(history, scored)
    values["amount_to_max_90d_ratio"] = batch.amount_to_max_90d_ratio(history, scored)
    values["round_sum_flag"] = _optional(
        lambda: float(batch.round_sum_flag(scored, context.denominations))
    )
    values["just_below_limit_flag"] = float(
        batch.just_below_limit_flag(scored, context.limits, kyc_tier=_tier_ordinal(scored, context))
    )

    for name, call in (
        ("local_hour_sin", batch.local_hour_sin),
        ("local_hour_cos", batch.local_hour_cos),
    ):
        values[name] = call(scored, context.countries)
    values["local_day_of_week"] = float(batch.local_day_of_week(scored, context.countries))
    values["is_local_night"] = float(batch.is_local_night(scored, context.countries))
    values["is_month_end_window"] = float(batch.is_month_end_window(scored, context.countries))
    values["seconds_since_last_tx"] = batch.seconds_since_last_tx(history, scored)

    values["distance_from_last_tx_km"] = batch.distance_from_last_tx_km(history, scored)
    values["implied_speed_kmh"] = batch.implied_speed_kmh(history, scored)
    values["distance_from_home_centroid_km"] = batch.distance_from_home_centroid_km(history, scored)
    values["is_new_country_for_account"] = float(
        batch.is_new_country_for_account(
            history, scored, known_before=context.countries_before.get(account, frozenset())
        )
    )
    # `context.outcomes` is passed, not copied. It used to be `dict(context.outcomes)` here and
    # again below, which rebuilt a mapping of **every outcome in the corpus** twice for every
    # scored row: 40.5 ms per row at a 200,000-row corpus, measured, against a total of about 40 —
    # so the copying *was* the feature pass. The two callees never mutate it and now say `Mapping`.
    values["geo_cell_fraud_rate_30d"] = batch.geo_cell_fraud_rate_30d(
        cell_history, context.outcomes, scored, prior=context.cell_rate_prior
    )

    values["counterparty_is_new_for_account"] = float(
        batch.counterparty_is_new_for_account(
            account_history,
            scored,
            known_before=context.counterparties_before.get(account, frozenset()),
        )
    )
    values["counterparty_account_age_days"] = batch.counterparty_account_age_days(
        scored, context.opened_at.get(_counterparty(scored))
    )
    values["counterparty_unique_senders_24h"] = float(
        batch.counterparty_unique_senders_24h(counterparty_history, scored)
    )
    values["counterparty_confirmed_fraud_90d"] = float(
        batch.counterparty_confirmed_fraud_90d(counterparty_history, context.outcomes, scored)
    )
    values["tx_count_to_counterparty_30d"] = float(
        batch.tx_count_to_counterparty_30d(history, scored)
    )

    values.update(
        _device_and_agent(scored, account_history, slices["device"], slices["agent"], context)
    )

    values["account_age_days"] = batch.account_age_days(scored, context.opened_at.get(account))
    values["kyc_tier"] = batch.kyc_tier(scored, context.tiers.get(account, ()))
    values["days_since_sim_swap"] = batch.days_since_sim_swap(
        scored, context.sim_swaps.get(account, ())
    )
    values["dormancy_reactivation_flag"] = float(batch.dormancy_reactivation_flag(history, scored))

    values["corridor_class"] = batch.corridor_class(scored, context.countries)
    values["synthetic_identity_score"] = batch.synthetic_identity_score(
        history,
        scored,
        IdentityEvidence(
            kyc_tier=values["kyc_tier"] if isinstance(values["kyc_tier"], float) else math.nan,
            kyc_tier_range=context.kyc_tier_range,
            opened_at=context.opened_at.get(account),
            accounts_on_device=(
                values["accounts_per_device_7d"]
                if isinstance(values["accounts_per_device_7d"], float)
                else math.nan
            ),
        ),
    )

    missing = set(REGISTRY) - set(values)
    if missing:
        raise ValueError(
            f"the vector is missing {sorted(missing)}. A slot left out here is a feature that "
            "silently stops being trained on, which no other test would notice"
        )
    return values


def _device_and_agent(
    scored: Transaction,
    account_history: Sequence[Transaction],
    device_history: Sequence[Transaction],
    agent_history: Sequence[Transaction],
    context: FeatureContext,
) -> dict[str, FeatureValue]:
    """The two structural-NaN groups, together because they go missing together.

    Split out of `compute` so the four device features and the four agent ones read as the two sets
    D-04 describes rather than as eight lines among forty: the contract is about which features are
    absent at the same time, and the code should show the grouping the contract names.
    """
    standing = context.agent_standing.get(scored.agent_id or "", ())
    return {
        "channel": batch.channel(scored),
        "device_is_new_for_account": batch.device_is_new_for_account(
            account_history,
            scored,
            known_before=context.devices_before.get(scored.account_id, frozenset()),
        ),
        "accounts_per_device_7d": batch.accounts_per_device_7d(device_history, scored),
        "device_changes_24h": batch.device_changes_24h(account_history, scored),
        "device_age_days": batch.device_age_days(
            scored,
            context.device_first_seen.get(scored.device_fingerprint or ""),
        ),
        "agent_float_utilisation_ratio": batch.agent_float_utilisation_ratio(scored, standing),
        "agent_cashout_count_1h": batch.agent_cashout_count_1h(
            agent_history, scored, context.cash_out_codes
        ),
        "agent_unique_customers_1h": batch.agent_unique_customers_1h(agent_history, scored),
        "agent_distance_from_registered_km": batch.agent_distance_from_registered_km(
            scored, standing
        ),
    }


def _histories(
    corpus: Sequence[Transaction],
    index: int,
    scored: Transaction,
    corpus_index: CorpusIndex | None,
) -> dict[str, list[Transaction]]:
    """The rows each key's features may read, strictly before the scored transaction.

    Without an index every feature gets the whole prefix, which is correct and quadratic. With one
    each gets the rows sharing its key — the same rows that survive the feature's own filter, so
    the values are identical and `test_the_index_changes_no_value` asserts it.
    """
    if corpus_index is None:
        prefix = list(corpus[:index])
        return dict.fromkeys(("account", "counterparty", "device", "agent", "cell"), prefix)
    return {
        "account": corpus_index.before(corpus_index.by_account, scored.account_id, scored),
        "counterparty": corpus_index.before(
            corpus_index.by_counterparty, scored.counterparty_id, scored
        ),
        "device": corpus_index.before(corpus_index.by_device, scored.device_fingerprint, scored),
        "agent": corpus_index.before(corpus_index.by_agent, scored.agent_id, scored),
        "cell": corpus_index.before(
            corpus_index.by_cell, corpus_index.cell_of.get(scored.transaction_id), scored
        ),
    }


def _optional(call: Callable[[], float]) -> float:
    """Run a feature that refuses when its reference data is absent, reporting NaN instead.

    Only for features whose refusal **is** the absent-data case: `round_sum_flag` raises when no
    denominations exist for the currency, and at the vector level that is the same fact as a NaN.
    Used deliberately narrowly — swallowing a refusal anywhere else would convert a bug into a
    missing value, which is the direction that hides things.
    """
    try:
        return float(call())
    except (KeyError, ValueError):
        return math.nan


def _counterparty(scored: Transaction) -> str:
    return scored.counterparty_id or ""


def _tier_ordinal(scored: Transaction, context: FeatureContext) -> int | None:
    tier = batch.kyc_tier(scored, context.tiers.get(scored.account_id, ()))
    return None if math.isnan(tier) else int(tier)


# `_first_seen` and `_device_first_seen` used to live here, each returning the earliest row in the
# supplied corpus and falling back to the scored transaction's own timestamp. Both were removed
# after the M3 milestone review (M3-2): that is exactly the inference `observe()` is forbidden to
# make, and the docstring defending it described a complete-dataset usage that no run in this
# repository has ever performed. The values now come from `FeatureContext`, supplied by a caller
# that can see all of history, and are NaN when nobody can establish them.


@dataclass(frozen=True)
class ComputabilityResult:
    """What a computability run found, per feature and in total."""

    rows: int
    nan_rate: dict[str, float]
    #: Distinct values each feature took over the sample, counting NaN as one value. A feature
    #: that took one value carries nothing, whether that value is a number or NaN.
    distinct: dict[str, int]
    #: Every feature whose declared state disagrees with the one the data produced, as
    #: `(name, declared, observed)`. One list rather than four: the three states are decided by a
    #: single measured number, so a mismatch is a mismatch whichever way it points, and
    #: enumerating the cases invites leaving one out.
    mismatched: tuple[tuple[str, Computability, Computability], ...]

    @property
    def ok(self) -> bool:
        return not self.mismatched


def observed_state(distinct_values: int) -> Computability:
    """The state the data says a feature is in, from one number.

    The three states are a **partition** on the count of distinct non-NaN values, which is what
    makes the check exhaustive rather than a list of cases somebody thought of:

    * **0** — it never produced a number, so its inputs are absent (``NO_SOURCE_DATA``);
    * **1** — it always produced the same one: present, complete, carrying nothing (``CONSTANT``);
    * **2 or more** — it varies (``COMPUTABLE``).

    **Missingness is deliberately not variation.** A feature that is NaN on every USSD row and `1`
    on all the others is constant: what separates those rows is the channel, `channel` already
    carries it, and counting the NaN as a second value would credit this feature with that one's
    signal. Counting it that way is exactly what hid `accounts_per_device_7d`, which is
    identically 1 wherever it is defined (PB-40) and read as two distinct values.
    """
    if distinct_values == 0:
        return Computability.NO_SOURCE_DATA
    if distinct_values == 1:
        return Computability.CONSTANT
    return Computability.COMPUTABLE


def computability(
    corpus: Sequence[Transaction], context: FeatureContext, *, sample: Sequence[int]
) -> ComputabilityResult:
    """Check every feature's declared `computable` against what it does on real rows (PB-44).

    Two directions, and the second matters as much as the first:

    * a feature declared **COMPUTABLE** that is NaN for 100% of rows is **dead** — trained on,
      counted in "44 features", and carrying nothing;
    * a feature declared **NO_SOURCE_DATA** that is **not** NaN for every row has been wired up
      while the register still says it is dead. That is the direction that fires when someone
      fixes the gap, and without it the register would drift into pessimism — which is no more
      true than optimism, and is the kind of staleness a reader trusts.

    **100%, not a threshold.** A feature that is NaN for 99.9% of rows is a legitimate structural
    NaN — four device features are NaN on every USSD transaction, four agent features on every
    non-agent one — and choosing a cut-off would mean deciding how dead is dead. The only
    unambiguous reading is "never produced a number", and that is what is checked.

    **The scan's size is reported and an empty one is refused**, per ADR 0009's generalisation: a
    check that has only ever run over an empty scope is untested, and this one would pass over an
    empty sample with every feature trivially neither dead nor revived.
    """
    if not sample:
        raise ValueError(
            "computability() was given an empty sample, so every feature would be neither dead "
            "nor revived and the check would pass having examined nothing (ADR 0009)"
        )

    corpus_index = CorpusIndex.build(corpus)
    produced_a_number = dict.fromkeys(REGISTRY, 0)
    seen: dict[str, set[str]] = {name: set() for name in REGISTRY}
    for index in sample:
        for name, value in compute(corpus, index, context, corpus_index).items():
            if isinstance(value, str) or not math.isnan(value):
                produced_a_number[name] += 1
                # Compared as text, because `nan != nan` would make a set of floats report a
                # feature as having as many values as it has rows.
                seen[name].add(value if isinstance(value, str) else repr(value))

    rows = len(sample)
    nan_rate = {name: 1.0 - produced / rows for name, produced in produced_a_number.items()}
    distinct = {name: len(values) for name, values in seen.items()}
    mismatched = tuple(
        (name, spec.computable, observed_state(distinct[name]))
        for name, spec in sorted(REGISTRY.items())
        if spec.computable is not observed_state(distinct[name])
    )
    return ComputabilityResult(
        rows=rows, nan_rate=nan_rate, distinct=distinct, mismatched=mismatched
    )


#: What each mismatch means, so the error says why it matters rather than only that it happened.
#: A table because every pair is reachable and each has a different consequence — and because a
#: default message would be the one a reader saw most often.
_MISMATCH: dict[tuple[Computability, Computability], str] = {
    (Computability.COMPUTABLE, Computability.NO_SOURCE_DATA): (
        "trained on and produced nothing; D-04's missing handling hides it"
    ),
    (Computability.COMPUTABLE, Computability.CONSTANT): (
        "present, complete, well-typed and carrying one value, so every completeness count "
        "reports it as working"
    ),
    (Computability.NO_SOURCE_DATA, Computability.CONSTANT): (
        "its inputs arrived but give no variation; the gap moved rather than closed"
    ),
    (Computability.NO_SOURCE_DATA, Computability.COMPUTABLE): (
        "somebody wired the data and the register still says it is dead"
    ),
    (Computability.CONSTANT, Computability.COMPUTABLE): (
        "the degeneracy has cleared and the declaration has not"
    ),
    (Computability.CONSTANT, Computability.NO_SOURCE_DATA): (
        "it is not constant, it is absent; the inputs went away"
    ),
}


def _why(declared: Computability, observed: Computability) -> str:
    return _MISMATCH[(declared, observed)]


def describe(result: ComputabilityResult) -> str:
    """A report that says what was checked as well as what it found.

    "Passed" and "had nothing to check" must be distinguishable, which is why the row count leads
    and is not buried under a verdict.
    """
    lines = [f"computability: {len(REGISTRY)} features over {result.rows} scored rows"]
    for name in sorted(result.nan_rate):
        state = REGISTRY[name].computable
        marker = "" if state.value == "computable" else f"  [declared {state.name}]"
        lines.append(
            f"  {name:38s} NaN {result.nan_rate[name]:6.1%}"
            f"  {result.distinct[name]:>5d} distinct{marker}"
        )
    for name, declared, observed in result.mismatched:
        lines.append(
            f"ERROR {name}: declared {declared.name}, observed {observed.name} "
            f"({result.distinct[name]} distinct non-NaN values) - {_why(declared, observed)}"
        )
    if result.ok:
        lines.append("OK every feature's computability declaration matches the data")
    return "\n".join(lines)
