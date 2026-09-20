"""The batch feature path: whole histories, filtered.

Independent of `fraudshield_ml.features.online` — neither imports the other, and
`test_the_two_paths_do_not_import_each_other` walks the import graph to prove it. Both import
`registry` (declarative) and `types` (data), neither of which computes anything.

This path reads an account's history as a sequence and selects windows by filtering. The online
path accumulates the same quantities incrementally. They are written to look different on purpose:
a parity test is worth exactly the size of the surface the two do not share.

**Window bounds, which both paths must agree on** (parity mutation 2 exists to catch a divergence):
for a transaction scored at time `t`, the history considered is the half-open interval
`(t - 30d, t)`, partitioned exactly as `(t - 30d, t - 1h]` and `(t - 1h, t)`. The scored
transaction is never in its own window (`self_inclusion=EXCLUDED`), and a transaction landing
exactly on `t - 1h` belongs to the long window, so the partition has no gap and no overlap.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from datetime import datetime, timedelta

from fraudshield_ml.features.primitives import h3_cell, haversine_km
from fraudshield_ml.features.registry import REGISTRY, categories_for, smoothing_for
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

SHORT_WINDOW = timedelta(hours=1)
LONG_WINDOW = timedelta(days=30)
CELL_WINDOW = timedelta(days=30)

#: Unpacked from the registry's declared order, which `test_registry` pins so that a reorder is a
#: failing test rather than a silent swap of two classes' meanings.
_DOMESTIC, _INTRA_BLOC, _CROSS_BLOC, _INTERCONTINENTAL = categories_for("corridor_class")


def velocity_ratio_1h_vs_30d(
    history: Sequence[Transaction],
    scored: Transaction,
    first_seen_at: datetime,
) -> float:
    """Trailing-hour count over the mean hourly count of the prior 30 days, both smoothed.

    `first_seen_at` is the durable field (`history_requirement=DURABLE`): the denominator divides
    by history **actually observed**, capped at 30 days (`history_basis=OBSERVED_CAPPED`), so a
    three-day-old account is not scored as though it had been quiet for 27 days.

    The 1 h numerator is removed from the 30 d denominator (`nesting=SHORT_EXCLUDED`), so the
    baseline does not move with the burst it is a baseline for.

    Equal smoothing on both terms with prior 1.0 makes a zero-history account return exactly 1.0 —
    "this account looks like its own baseline" — rather than 0.0 or NaN.
    """
    alpha = smoothing_for("velocity_ratio_1h_vs_30d").alpha

    t = scored.timestamp
    short_start, long_start = t - SHORT_WINDOW, t - LONG_WINDOW

    # Filtered here rather than trusted to the caller, as every other account-keyed feature does.
    # It was not, until `test_the_corpus_index_changes_no_value` fed this the whole corpus prefix
    # and the ratio counted strangers' transactions as this account's burst — a number that is
    # plausible at every magnitude, that no single-account test could produce, and that the online
    # path cannot make because its state is keyed by account.
    own = [x for x in history if x.account_id == scored.account_id]
    short_count = sum(1 for x in own if short_start < x.timestamp < t)
    long_count = sum(1 for x in own if long_start < x.timestamp <= short_start)

    observed = min(LONG_WINDOW, t - first_seen_at)
    baseline_hours = observed.total_seconds() / 3600.0 - SHORT_WINDOW.total_seconds() / 3600.0
    long_mean = long_count / baseline_hours if baseline_hours > 0 else 0.0

    return (short_count + alpha) / (long_mean + alpha)


def geo_cell_fraud_rate_30d(
    corpus: Sequence[Transaction],
    outcomes: dict[str, Outcome],
    scored: Transaction,
    prior: float,
) -> float:
    """Confirmed-fraud proportion of the scored transaction's H3 cell over the prior 30 days.

    Keyed by the **cell**, not the account (`history_key=GEO_CELL`), so E1's account-grouped folds
    do not isolate it: `corpus` must therefore already be restricted to training-fold rows, and
    `prior` fitted on the same rows. Passing the whole corpus is the mutation that would expose the
    leak, by raising the single-feature AUC above its clean value.

    Only outcomes whose `available_at` precedes the scored timestamp are counted
    (`label_basis=AVAILABLE_AT_LAG`) — never `confirmed_at`, which would import the investigation
    delay straight into the feature.
    """
    alpha = smoothing_for("geo_cell_fraud_rate_30d").alpha

    t = scored.timestamp
    start = t - CELL_WINDOW
    cell = h3_cell(scored.latitude, scored.longitude)

    total = 0
    fraud = 0
    for row in corpus:
        if row.transaction_id == scored.transaction_id:
            continue  # self_inclusion=EXCLUDED
        if not (start < row.timestamp < t):
            continue
        if h3_cell(row.latitude, row.longitude) != cell:
            continue
        outcome = outcomes.get(row.transaction_id)
        if outcome is None or outcome.available_at >= t:
            continue  # the label had not arrived; the online path could not have seen it either
        total += 1
        fraud += int(outcome.is_fraud)

    return (fraud + alpha * prior) / (total + alpha)


def corridor_class(scored: Transaction, countries: Mapping[str, CountryFacts]) -> str:
    """The corridor the transaction crosses, from the two countries' pack facts (PB-30, ADR 0023).

    Part E.2 specifies `DOMESTIC | EAC_CROSS_BORDER | NON_EAC_CROSS_BORDER`. ADR 0023 replaces
    that with the four classes the registry declares, so that EAC is a configured set of
    memberships rather than a branch in code, and a new country is a new pack file. **No country,
    currency, bloc or continent is named here** — every decision is an equality or a set
    intersection between two packs, which is what makes `test_an_invented_country_and_bloc_need_no
    _code_change` possible at all.

    The order of the tests is the definition, and the first two are not interchangeable: a country
    shares every one of its blocs with itself, so a domestic corridor would otherwise classify as
    `INTRA_BLOC` and the domestic case — the overwhelming majority of rows — would disappear.

    **`CROSS_BLOC_AFRICA` is implemented as "same continent, no shared bloc"**, with no continent
    named in code. For the validated core and every pack that exists the two readings coincide,
    since all are African; for a hypothetical pair of non-African countries the class name would
    be a misnomer while the rule stayed right. That is recorded in PB-42 rather than fixed by
    testing the continent against a literal, which would reintroduce exactly the hard-coding ADR
    0023 removed.

    The sender's country is read from the transaction record rather than joined from an account
    profile, which is what keeps `reference_data_basis=NOT_REFERENCE_DATA` true: a join against a
    mutable profile would read today's country for an eight-month-old transaction, which is
    ADR 0026's defect arriving through a different table.
    """
    sender = _country(scored.account_country, "account_country", scored, countries)
    recipient = _country(scored.counterparty_country, "counterparty_country", scored, countries)

    if sender.alpha2 == recipient.alpha2:
        return _DOMESTIC
    if sender.blocs & recipient.blocs:
        return _INTRA_BLOC
    if sender.continent == recipient.continent:
        return _CROSS_BLOC
    return _INTERCONTINENTAL


def _country(
    code: str | None,
    field: str,
    scored: Transaction,
    countries: Mapping[str, CountryFacts],
) -> CountryFacts:
    """The pack for a code, refusing rather than defaulting.

    A missing country cannot be defaulted: every substitute — the sender's own country, a
    configured home country — classifies the corridor as something, and the something would be
    wrong in the direction that makes a cross-border transfer look domestic.
    """
    if code is None:
        raise ValueError(
            f"{scored.transaction_id}: corridor_class needs {field}, which the request did not "
            "carry. There is no default: every substitute names a corridor the transaction did "
            "not cross"
        )
    if code not in countries:
        raise KeyError(
            f"{scored.transaction_id}: no country pack for {code!r} ({field}). Adding a country "
            "is adding dataset/generator/params/countries/<code>.yaml (ADR 0023), never a case "
            "in feature code"
        )
    return countries[code]


# ---------------------------------------------------------------------------------------------
# Velocity: four trailing counts, two trailing sums, one trailing distinct count.
#
# All seven share `_TRAILING_ACCOUNT_AGGREGATE` in the registry, so they share one window rule
# here: for a transaction scored at `t` with window `W`, the rows counted are those of the SAME
# ACCOUNT in the open interval `(t - W, t)`. Both ends are exclusive. The upper end is
# `self_inclusion=EXCLUDED`; the lower end is the convention `velocity_ratio_1h_vs_30d` already
# fixed, where the long window is `(t - 30d, t - 1h]` so that the two windows partition without a
# gap or an overlap. A row landing exactly on `t - W` is therefore outside the window, which is
# parity mutation 2's boundary and is tested at each of the four edges.
# ---------------------------------------------------------------------------------------------

_WINDOW_UNITS = {"s": "seconds", "m": "minutes", "h": "hours", "d": "days"}


def window_of(name: str) -> timedelta:
    """The declared window of a single-window feature, parsed from the registry's string.

    Parsed rather than repeated as a constant so that the window a feature *computes* is the window
    the registry *declares*, and the two cannot drift. The online path parses the same declared
    string with its own parser: sharing one would put the window rule — the thing parity mutations
    2 and 3 exist to catch — inside the surface the parity test cannot see.
    """
    spec = REGISTRY[name]
    window = spec.window
    if window is None or window == "unbounded" or "/" in window:
        raise ValueError(
            f"{name} declares window={window!r}, which is not a single trailing span; a feature "
            "with no window, an unbounded one or a pair of them has no single interval to read"
        )
    unit = _WINDOW_UNITS.get(window[-1])
    if unit is None:
        raise ValueError(f"{name}: cannot read a window from {window!r}")
    return timedelta(**{unit: int(window[:-1])})


def _within(history: Sequence[Transaction], scored: Transaction, name: str) -> list[Transaction]:
    """This account's rows inside the feature's trailing window.

    The account filter is applied here rather than trusted to the caller. Every one of these
    features declares `history_key=ACCOUNT`, and a caller that handed over a mixed history would
    get a number that looks like a velocity and is an aggregate over strangers — silent, and
    plausible at every magnitude.
    """
    return _within_span(history, scored, window_of(name))


def _within_span(
    history: Sequence[Transaction], scored: Transaction, span: timedelta
) -> list[Transaction]:
    """This account's rows inside an explicit trailing span.

    Separate from `_within` because one caller has no feature name to look up: the composite's
    internal 7 d sub-window is part of *its* documented form and is not a declared window of any
    feature, so naming another feature's window to get the number would tie the composite to a
    feature it has nothing to do with.
    """
    start = scored.timestamp - span
    return [
        row
        for row in history
        if row.account_id == scored.account_id and start < row.timestamp < scored.timestamp
    ]


def tx_count(history: Sequence[Transaction], scored: Transaction, name: str) -> int:
    """`tx_count_60s`, `tx_count_1h`, `tx_count_24h` or `tx_count_7d`.

    One function for four features because the registry gives them one contract. Writing four
    bodies would make a later divergence between them look like ordinary variation rather than the
    decision it would be — the same reasoning the registry applies to their shared contract object.
    """
    return len(_within(history, scored, name))


def amount_sum(history: Sequence[Transaction], scored: Transaction, name: str) -> float:
    """`amount_sum_24h` or `amount_sum_7d`, in the base currency.

    Each row carries its own `amount_rwf`, converted at the rate for **its own** transaction date.
    Summing a pre-converted column is not an optimisation, it is the leakage boundary: a window
    converted at the scored transaction's rate would price an eight-month-old transfer at a rate
    published after it happened, which is ADR 0026's defect reaching a different column.

    Real-valued, so ADR 0025's relative tolerance applies rather than exact equality; the two paths
    accumulate in different orders by construction.
    """
    return sum(row.amount_rwf for row in _within(history, scored, name))


def unique_counterparties_24h(history: Sequence[Transaction], scored: Transaction) -> int:
    """Distinct counterparties this account paid in the trailing 24 h.

    An exact set, never a sketch: ADR 0025 requires exact equality for a count, and a HyperLogLog's
    error is invisible at the volumes where the feature is uninteresting and decisive at the burst
    volumes where it is the signal.
    """
    rows = _within(history, scored, "unique_counterparties_24h")
    return len({_counterparty_of(row) for row in rows})


def _counterparty_of(row: Transaction) -> str:
    if row.counterparty_id is None:
        raise ValueError(
            f"{row.transaction_id}: counterparty_id is missing. Counting rows without one as a "
            "single unknown counterparty would make unrelated payments look like a relationship"
        )
    return row.counterparty_id


# ---------------------------------------------------------------------------------------------
# Amount behaviour (5). Two read the scored transaction alone; two read a 90 d window with a
# minimum-history cliff; one reads operational configuration as of the transaction (ADR 0026).
# ---------------------------------------------------------------------------------------------

#: Part E.2: "robust: median/MAD". 1.4826 makes the MAD a consistent estimator of the standard
#: deviation under normality, so the z-score is on the familiar scale rather than on the MAD's.
MAD_TO_SIGMA = 1.4826

#: `just_below_limit_flag` is true in the band `[limit * (1 - BAND), limit)`.
LIMIT_BAND = 0.05


def amount_log1p(scored: Transaction) -> float:
    """log1p of the scored amount in the base currency.

    `log1p` rather than `log(1 + x)`: the two agree at payment magnitudes and differ near zero,
    where `log(1 + x)` loses every significant digit. An amount can be near zero — a one-unit
    balance check or a failed top-up — and a feature that is imprecise exactly for the smallest
    amounts is imprecise exactly where an unusual amount is most visible.
    """
    return math.log1p(scored.amount_rwf)


def _prior_amounts(history: Sequence[Transaction], scored: Transaction, name: str) -> list[float]:
    return [row.amount_rwf for row in _within(history, scored, name)]


def amount_zscore_90d(history: Sequence[Transaction], scored: Transaction) -> float:
    """Robust z-score of the scored amount against this account's prior 90 days.

    `(amount - median) / (1.4826 * MAD)`, with the scored transaction excluded from its own
    reference set. Two separate cliffs, both declared in the registry and neither foldable into
    the other:

    * **fewer than 5 prior observations** — `minimum_history`. A MAD over four points is not
      imprecise, it is meaningless.
    * **a MAD of exactly zero** — an account whose last 90 days are all one amount has no scale to
      divide by. Not a smoothing case: there is no scale to shrink toward.

    Both emit NaN, which is `below_threshold_value=None`. Emitting 0.0 is the dangerous option and
    is why the registry makes it a declared value rather than a default: zero is the *most normal
    possible* z-score, so a thin-history account would be scored as perfectly typical rather than
    as unknown — the direction that makes a new account look safe.
    """
    amounts = _prior_amounts(history, scored, "amount_zscore_90d")
    if len(amounts) < _minimum_observations("amount_zscore_90d"):
        return float("nan")

    centre = _median(amounts)
    deviation = _median([abs(value - centre) for value in amounts])
    if deviation == 0.0:
        return float("nan")
    return (scored.amount_rwf - centre) / (MAD_TO_SIGMA * deviation)


def amount_to_max_90d_ratio(history: Sequence[Transaction], scored: Transaction) -> float:
    """The scored amount over the largest in this account's prior 90 days.

    One prior observation is enough for a maximum, which is why this feature's
    `minimum_history` is 1 where the z-score's is 5: a maximum is defined at one point and a scale
    estimate is not. The two cliffs are declared per feature for exactly this reason.
    """
    amounts = _prior_amounts(history, scored, "amount_to_max_90d_ratio")
    if not amounts:
        return float("nan")
    largest = max(amounts)
    if largest <= 0.0:
        return float("nan")
    return scored.amount_rwf / largest


def _minimum_observations(name: str) -> int:
    """The declared minimum-history threshold, refusing a feature that has none.

    Read from the registry rather than repeated as a literal: the threshold is a contract field,
    and a copy of it here would be the one place a change to the contract did not reach.
    """
    contract = REGISTRY[name].contract
    minimum = contract.minimum_history if contract is not None else None
    if minimum is None:
        raise ValueError(f"{name} declares no minimum_history, so it has no threshold to apply")
    return minimum.minimum_observations


def _median(values: Sequence[float]) -> float:
    """The median, with the even case averaging the two middle values.

    Written here rather than taken from `statistics` so that the online path can define it for
    itself: "median" has two defensible readings for an even count — the lower middle, or their
    mean — and a shared helper would make the two paths agree on whichever one it chose. That is
    the fork the parity suite is meant to surface, so it must not be resolved by a shared import.
    """
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def round_sum_flag(scored: Transaction, denominations: Mapping[str, Sequence[int]]) -> bool:
    """True when the amount is an exact multiple of one of its currency's common denominations.

    Both the amount and the denominations are **integers in minor units**, so "exact multiple" is
    exact. A float modulo would make the flag true or false depending on a representation error,
    and a flag computed to within a rounding error is a different feature from the one declared.

    The denomination table is passed in, keyed by currency, and comes from the country packs —
    never from a table here (ADR 0023). Not a fraud signal on its own: most salary and rent
    payments are round, which is what makes it a *look-alike* feature rather than a rule.
    """
    if scored.amount_minor is None or scored.currency is None:
        raise ValueError(
            f"{scored.transaction_id}: round_sum_flag needs amount_minor and currency. Falling "
            "back to the base-currency amount would test a converted number for roundness, and "
            "roundness is a property of what the payer typed"
        )
    if scored.currency not in denominations:
        raise KeyError(
            f"{scored.transaction_id}: no denominations for {scored.currency!r}; they come from "
            "the country packs, so a missing entry means a pack is missing rather than that no "
            "amount in that currency is round"
        )
    steps = denominations[scored.currency]
    if not steps:
        raise ValueError(
            f"{scored.transaction_id}: {scored.currency!r} declares no denominations, which would "
            "make every amount un-round rather than saying the question was not answered"
        )
    return any(step > 0 and scored.amount_minor % step == 0 for step in steps)


def just_below_limit_flag(
    scored: Transaction,
    limits: Sequence[OperationalLimit],
    *,
    kyc_tier: int | None,
) -> bool:
    """True when the amount sits in the 5% band below a limit **in force at the transaction**.

    ADR 0026: the limits are resolved as of `scored.timestamp`, never as of today. The batch path
    is the one that gets this wrong by default — it joins a configuration table and sees the
    current row — so the as-of resolution is the whole content of this function, and the parity
    fixture is required to span a configuration change or the two paths agree while both are
    wrong.

    The band is `[limit * 0.95, limit)`: at or above the limit the transaction is not below it,
    and the structuring signal is an amount placed deliberately under a threshold.

    **Part E.2's "active rule threshold" clause is not implemented**, and a caller supplying one
    gets an error rather than a flag computed over the two dimensions that do work. M1's schema
    cannot answer it as-of (ADR 0026), so the honest output is a refusal naming the gap.
    """
    applicable = _applicable_limits(scored, limits, kyc_tier)
    in_force = _in_force_at(applicable, scored.timestamp)
    return any(
        limit * (1.0 - LIMIT_BAND) <= scored.amount_rwf < limit for limit in in_force.values()
    )


def _applicable_limits(
    scored: Transaction, limits: Sequence[OperationalLimit], kyc_tier: int | None
) -> list[OperationalLimit]:
    applicable = []
    for limit in limits:
        if limit.dimension is LimitDimension.ACTIVE_RULE_THRESHOLD:
            raise ValueError(
                "just_below_limit_flag cannot read an active rule threshold as of the "
                "transaction: alert_rule_versions carries only created_at, and alert_rules.state "
                "has no transition history, so 'was this rule in force at T?' is unanswerable "
                "(ADR 0026). The clause ships unimplemented rather than silently reading today's "
                "rules"
            )
        matches_channel = (
            limit.dimension is LimitDimension.CHANNEL and limit.applies_to == scored.channel
        )
        matches_tier = (
            limit.dimension is LimitDimension.KYC_TIER
            and kyc_tier is not None
            and limit.applies_to == str(kyc_tier)
        )
        if matches_channel or matches_tier:
            applicable.append(limit)
    return applicable


def _in_force_at(limits: Sequence[OperationalLimit], at: datetime) -> dict[tuple[str, str], float]:
    """The latest version of each limit that had taken effect by `at`.

    A limit with no version effective by then is **absent**, not zero and not the earliest one
    known: before a limit existed there was nothing to structure under, and inventing one would
    make the flag true for transactions that predate the rule.
    """
    latest: dict[tuple[str, str], OperationalLimit] = {}
    for limit in limits:
        if limit.effective_at > at:
            continue
        key = (limit.dimension.value, limit.applies_to)
        current = latest.get(key)
        if current is None or limit.effective_at > current.effective_at:
            latest[key] = limit
    return {key: limit.amount_rwf for key, limit in latest.items()}


# ---------------------------------------------------------------------------------------------
# Temporal (6). Five read the scored transaction's local time; one reads the previous transaction
# with no window at all.
#
# Local time is **derived** from the country pack's UTC offset (D-43), never stored alongside the
# UTC timestamp. Storing both is parity mutation 4: two fields that must agree, maintained by two
# paths, one of which will eventually be written from the other's assumption.
# ---------------------------------------------------------------------------------------------

_HOURS_IN_DAY = 24
NIGHT_HOURS = range(0, 5)
"""Local 00:00-04:59 inclusive, as `is_local_night` declares."""

MONTH_END_DAYS = 3
MONTH_START_DAYS = 2
"""The salary period: the last 3 and first 2 local days of a month."""


def local_time(scored: Transaction, countries: Mapping[str, CountryFacts]) -> datetime:
    """The transaction's local civil time, from the pack of the country it happened in.

    The country is the **account's**, not the counterparty's: local time is a property of where the
    person transacted, and a cross-border transfer does not happen in the recipient's afternoon.

    A fixed offset is exact for every pack that exists — none of the five observes daylight saving,
    which each pack's provenance records — so this is arithmetic rather than a zone lookup. A pack
    for a country that does observe it would need a zone rather than an offset, and that is a pack
    schema change, not a change here.
    """
    if scored.account_country is None:
        raise ValueError(
            f"{scored.transaction_id}: the temporal features need account_country to find the "
            "local time. Falling back to UTC would move every hour-of-day feature by the offset "
            "and would do it silently, since UTC is itself a plausible-looking local time"
        )
    if scored.account_country not in countries:
        raise KeyError(
            f"{scored.transaction_id}: no country pack for {scored.account_country!r}; the UTC "
            "offset comes from the pack (ADR 0023)"
        )
    offset = countries[scored.account_country].utc_offset_hours
    return scored.timestamp + timedelta(hours=offset)


def local_hour(scored: Transaction, countries: Mapping[str, CountryFacts]) -> int:
    """The integer local hour, 0-23, which the registry declares for the sin/cos pair."""
    return local_time(scored, countries).hour


def local_hour_sin(scored: Transaction, countries: Mapping[str, CountryFacts]) -> float:
    return math.sin(2.0 * math.pi * local_hour(scored, countries) / _HOURS_IN_DAY)


def local_hour_cos(scored: Transaction, countries: Mapping[str, CountryFacts]) -> float:
    return math.cos(2.0 * math.pi * local_hour(scored, countries) / _HOURS_IN_DAY)


def local_day_of_week(scored: Transaction, countries: Mapping[str, CountryFacts]) -> int:
    """Monday=0, from local time rather than UTC.

    The distinction is not cosmetic: a transaction at 23:30 UTC on a Sunday is Monday morning in
    every pack with a positive offset, and a week-day feature computed in UTC would place a
    Monday-morning salary run in the weekend.
    """
    return local_time(scored, countries).weekday()


def is_local_night(scored: Transaction, countries: Mapping[str, CountryFacts]) -> bool:
    """Local 00:00-04:59 inclusive."""
    return local_hour(scored, countries) in NIGHT_HOURS


def is_month_end_window(scored: Transaction, countries: Mapping[str, CountryFacts]) -> bool:
    """The last 3 and first 2 local days of a month — the salary period.

    Month length is the **local** calendar month, so February and a 31-day month differ and the
    boundary is the same local-month boundary PB-26 pinned for the dataset's partition key. A
    window defined on the UTC month would disagree with the partition it is read from, for rows
    near a boundary, in the direction that is hardest to notice.
    """
    local = local_time(scored, countries)
    if local.day <= MONTH_START_DAYS:
        return True
    return local.day > _days_in_month(local) - MONTH_END_DAYS


def _days_in_month(moment: datetime) -> int:
    """Days in the local calendar month containing `moment`.

    By construction rather than by table: step to the first of the next month and back one day, so
    a leap February needs no case of its own and cannot be got wrong for one year in four.
    """
    if moment.month == 12:
        following = moment.replace(year=moment.year + 1, month=1, day=1)
    else:
        following = moment.replace(month=moment.month + 1, day=1)
    return (following - timedelta(days=1)).day


def seconds_since_last_tx(history: Sequence[Transaction], scored: Transaction) -> float:
    """Seconds since this account's previous transaction, with no window bound.

    `window=unbounded`: the previous transaction counts however old it is, which is what makes
    dormancy visible at all. NaN when there is no previous transaction — `minimum_history=1` with
    `below_threshold_value=None` — because a first transaction has no gap, and 0 would mean the
    opposite of what it says.
    """
    previous = [
        row.timestamp
        for row in history
        if row.account_id == scored.account_id and row.timestamp < scored.timestamp
    ]
    if not previous:
        return float("nan")
    return (scored.timestamp - max(previous)).total_seconds()


# ---------------------------------------------------------------------------------------------
# Geographic (4 of 5; `geo_cell_fraud_rate_30d` is above, with the label-derived features).
# ---------------------------------------------------------------------------------------------

MAX_IMPLIED_SPEED_KMH = 1000.0
"""The speed above which the journey did not happen.

**What it means when it binds.** 1000 km/h is above commercial cruising speed, so a value at the
cap is not "fast": it says one person cannot have been in both places, which makes it a proxy for a
shared account, a credential used elsewhere, or a spoofed location. The feature saturates rather
than reporting 3,000 or 40,000 so that a tree cannot split *inside* the impossible range and learn
a distinction that has no meaning.

**There is no zero-elapsed-time case to guard** (owner decision 2026-09-19, correcting the
registry's earlier wording). A predecessor is a row strictly earlier than the scored one — the
convention every window here uses, and the only one the online path can implement, since a
transaction stamped the same instant may not have arrived when the scored one is served. The
elapsed time is therefore always positive. A `hours <= 0` guard would be unreachable, which is the
shape this project has already removed twice: an `assert` that `python -O` strips, and a licence
check that only ever ran over an empty scope. An unreachable guard reads as protection and is not.

See `registry.SIMULTANEOUS_PREDECESSOR_NOTE` for what goes NaN instead, and why that silence is
documented rather than closed.
"""


def _previous(history: Sequence[Transaction], scored: Transaction) -> Transaction | None:
    """This account's most recent transaction strictly before the scored one, or None.

    Unbounded: the previous transaction counts however old it is, which is what makes a dormant
    account's first movement visible.
    """
    earlier = [
        row
        for row in history
        if row.account_id == scored.account_id and row.timestamp < scored.timestamp
    ]
    if not earlier:
        return None
    return max(earlier, key=lambda row: row.timestamp)


def distance_from_last_tx_km(history: Sequence[Transaction], scored: Transaction) -> float:
    """Great-circle distance from the account's previous transaction, NaN when there is none.

    A first transaction has no distance; 0.0 would say "the same place", which is a statement about
    a journey that did not happen.
    """
    previous = _previous(history, scored)
    if previous is None:
        return float("nan")
    return haversine_km(previous.latitude, previous.longitude, scored.latitude, scored.longitude)


def implied_speed_kmh(history: Sequence[Transaction], scored: Transaction) -> float:
    """Distance from the previous transaction over the elapsed hours, capped.

    Not `distance_from_last_tx_km / (seconds_since_last_tx / 3600)` computed from the two features:
    composing them would make this path depend on two other features' NaN conventions, and a NaN
    divided by a NaN is a different contract from the one declared. It is computed from the same
    previous transaction, once.
    """
    previous = _previous(history, scored)
    if previous is None:
        return float("nan")
    distance = haversine_km(
        previous.latitude, previous.longitude, scored.latitude, scored.longitude
    )
    # Strictly positive: `_previous` requires a strictly earlier row, so there is no zero to guard.
    hours = (scored.timestamp - previous.timestamp).total_seconds() / 3600.0
    return min(distance / hours, MAX_IMPLIED_SPEED_KMH)


def distance_from_home_centroid_km(history: Sequence[Transaction], scored: Transaction) -> float:
    """Distance from the component-wise median of the account's prior 90 days of locations.

    Median, not mean, and component-wise: a single transaction abroad drags a mean centroid into
    the sea between two countries, and every subsequent transaction at home then reads as far from
    "home". The median of each coordinate is unmoved by one outlier in either.
    """
    rows = _within(history, scored, "distance_from_home_centroid_km")
    if not rows:
        return float("nan")
    centre_lat = _median([row.latitude for row in rows])
    centre_lon = _median([row.longitude for row in rows])
    return haversine_km(centre_lat, centre_lon, scored.latitude, scored.longitude)


def is_new_country_for_account(history: Sequence[Transaction], scored: Transaction) -> bool:
    """True when this account has never sent to the counterparty's country before.

    **Unbounded history**: a corridor used once two years ago is not new. That is the feature's
    content — "first time to this country" is a claim about an account's whole life, and a windowed
    version would report every dormant corridor as new again, on exactly the accounts whose
    behaviour has not changed.

    The country is the counterparty's, by owner decision (2026-09-19). Part E.2's "the scored
    transaction's country" reads most naturally as where the transaction happened, and nothing
    records that: locations are continuous coordinates and resolving them would put a geocoder in
    the feature path. The registry entry carries the full reasoning, including why the account's
    own country was rejected.

    No country is named here: the comparison is between two values that came out of the data, so a
    new country is a new pack and no code (ADR 0023).
    """
    country = scored.counterparty_country
    if country is None:
        raise ValueError(
            f"{scored.transaction_id}: is_new_country_for_account needs counterparty_country. "
            "Treating a missing one as a country of its own would make every such row share a "
            "destination, so the second would read as familiar"
        )
    seen = {
        row.counterparty_country
        for row in history
        if row.account_id == scored.account_id and row.timestamp < scored.timestamp
    }
    return country not in seen


# ---------------------------------------------------------------------------------------------
# Counterparty (5). Two are keyed by the COUNTERPARTY rather than the account, so E1's
# account-grouped folds do not isolate them and the registry makes each declare what does.
# ---------------------------------------------------------------------------------------------


def counterparty_of(scored: Transaction) -> str:
    """The scored transaction's counterparty, or a refusal naming why there is no default."""
    if scored.counterparty_id is None:
        raise ValueError(
            f"{scored.transaction_id}: the counterparty features need counterparty_id. Rows "
            "without one would share an identity, so the second would read as an established "
            "relationship and unrelated recipients would be counted as one mule"
        )
    return scored.counterparty_id


def counterparty_is_new_for_account(history: Sequence[Transaction], scored: Transaction) -> bool:
    """True when this account has never transacted with this counterparty, over all history.

    Unbounded on purpose: a payee used once three years ago is not new, and a windowed version
    would fire on every dormant relationship — on exactly the accounts whose behaviour has not
    changed.
    """
    counterparty = counterparty_of(scored)
    return not any(
        row.account_id == scored.account_id
        and row.timestamp < scored.timestamp
        and row.counterparty_id == counterparty
        for row in history
    )


def counterparty_unique_senders_24h(corpus: Sequence[Transaction], scored: Transaction) -> int:
    """Distinct accounts that sent to this counterparty in the trailing 24 h.

    `corpus`, not `history`: this is keyed by the counterparty and reads **other accounts' rows**,
    which is the feature. It must therefore be given training-fold rows only when it is computed
    for training, exactly as `geo_cell_fraud_rate_30d` must — the registry's
    `cross_account_control` names the mutation that would show the leak if that were forgotten.
    """
    counterparty = counterparty_of(scored)
    start = scored.timestamp - window_of("counterparty_unique_senders_24h")
    return len(
        {
            row.account_id
            for row in corpus
            if row.counterparty_id == counterparty and start < row.timestamp < scored.timestamp
        }
    )


def counterparty_confirmed_fraud_90d(
    corpus: Sequence[Transaction], outcomes: dict[str, Outcome], scored: Transaction
) -> int:
    """Confirmed-fraud transactions involving this counterparty in the prior 90 d.

    Counted only where `available_at` precedes the scored timestamp. Filtering on a confirmation
    time instead would import the investigation delay into the feature — the asymmetry that exists
    because the batch path *can* see a label the online path cannot, and will unless stopped.
    """
    counterparty = counterparty_of(scored)
    start = scored.timestamp - window_of("counterparty_confirmed_fraud_90d")
    confirmed = 0
    for row in corpus:
        if row.transaction_id == scored.transaction_id or row.counterparty_id != counterparty:
            continue
        if not (start < row.timestamp < scored.timestamp):
            continue
        outcome = outcomes.get(row.transaction_id)
        if outcome is None or outcome.available_at >= scored.timestamp:
            continue
        confirmed += int(outcome.is_fraud)
    return confirmed


def tx_count_to_counterparty_30d(history: Sequence[Transaction], scored: Transaction) -> int:
    """Transactions from this account to this counterparty in the trailing 30 d.

    Keyed by the ACCOUNT even though a counterparty appears in it: the quantity is a property of
    the pair, computed from the account's own rows, so account-grouped folds do isolate it and it
    needs no cross-account control. The registry says so, and this is what that declaration means
    in code.
    """
    counterparty = counterparty_of(scored)
    rows = _within(history, scored, "tx_count_to_counterparty_30d")
    return sum(1 for row in rows if row.counterparty_id == counterparty)


SECONDS_PER_DAY = 86_400.0


def counterparty_account_age_days(scored: Transaction, opened_at: datetime | None) -> float:
    """Days between the counterparty account's opening date and the scored transaction.

    A **static attribute of the counterparty**, not an aggregate over transactions, so it is
    supplied rather than derived: nothing in an account's own transaction history says when its
    payee's account was opened. A young recipient account is the mule signal; an old one is the
    ordinary case.

    NaN when the opening date is unknown, which is the honest output and not a small matter: the
    natural substitute — the counterparty's first transaction seen in the data — is bounded below
    by the dataset's own start, so every counterparty would look at most as old as the benchmark
    and the feature would read "young" for the entire population.

    Negative when the transaction predates the opening date, and deliberately not clamped: that is
    a data error, and reporting 0 would present it as a brand-new account, which is the value the
    fraud signal points at.
    """
    if opened_at is None:
        return float("nan")
    return (scored.timestamp - opened_at).total_seconds() / SECONDS_PER_DAY


# ---------------------------------------------------------------------------------------------
# Device and channel (5). Four of them are NaN together for a null fingerprint — the structural
# NaN D-04 fixes at exactly four, and parity mutation 5 is the case where one of them returns 0.0
# instead. `channel` is the fifth and is never NaN.
#
# A null fingerprint is **data**, not a missing input. Every USSD transaction has one, because
# USSD has no device to fingerprint. So these four return NaN for it rather than raising, while a
# missing counterparty or currency raises: one is a fact about the transaction, the other is a
# caller who did not supply what the feature needs.
# ---------------------------------------------------------------------------------------------

DEVICE_FEATURES = (
    "device_is_new_for_account",
    "accounts_per_device_7d",
    "device_changes_24h",
    "device_age_days",
)
"""The four that are NaN together. Named so the parity suite can assert the positions match as a
set rather than one feature at a time — a contract about *which* features go missing together is
not tested by four separate assertions that each happen to hold."""


def channel(scored: Transaction) -> str:
    """The transaction's channel, checked against the registry's declared values.

    Checked rather than passed through: ADR 0025 allows a categorical no tolerance, so an
    undeclared value is not a slightly-wrong number, it is a category no encoder has a cell for —
    and it would arrive at the model as an unseen level rather than as an error.
    """
    value = scored.channel
    if value is None:
        raise ValueError(
            f"{scored.transaction_id}: channel is mandatory and constrained at ingestion, so a "
            "missing one is a broken request rather than a feature to compute"
        )
    permitted = categories_for("channel")
    if value not in permitted:
        raise ValueError(
            f"{scored.transaction_id}: channel {value!r} is not one of {list(permitted)}. An "
            "undeclared category reaches the model as an unseen level, which is silent"
        )
    return value


def device_is_new_for_account(history: Sequence[Transaction], scored: Transaction) -> float:
    """1.0 when this account has not used this device before, 0.0 when it has, NaN with no device.

    Returned as a float rather than a bool so the structural NaN has somewhere to live: a
    three-valued feature cannot be a Python `bool`, and encoding "no device" as `False` is exactly
    parity mutation 5 — the D-04 contract collapsing to a number.
    """
    device = scored.device_fingerprint
    if device is None:
        return float("nan")
    seen = any(
        row.account_id == scored.account_id
        and row.timestamp < scored.timestamp
        and row.device_fingerprint == device
        for row in history
    )
    return 0.0 if seen else 1.0


def accounts_per_device_7d(corpus: Sequence[Transaction], scored: Transaction) -> float:
    """Distinct accounts that transacted from this device in the trailing 7 d, NaN with no device.

    `history_key=DEVICE`, so it reads other accounts' rows and `corpus` must be training-fold rows
    when computed for training. **Identically 1 on the current dataset** (PB-40): no fingerprint is
    shared between accounts, so the feature is computable, constant, and declared degenerate in the
    registry. It is implemented correctly anyway — the generator fix is scheduled before M4
    training, and a feature written to match a degenerate dataset would then be the defect.
    """
    device = scored.device_fingerprint
    if device is None:
        return float("nan")
    start = scored.timestamp - window_of("accounts_per_device_7d")
    return float(
        len(
            {
                row.account_id
                for row in corpus
                if row.device_fingerprint == device and start < row.timestamp < scored.timestamp
            }
            | {scored.account_id}
        )
    )


def device_changes_24h(history: Sequence[Transaction], scored: Transaction) -> float:
    """Distinct devices this account used in the trailing 24 h, minus one, NaN with no device.

    Minus one so a single consistent device scores 0 — the feature is "how many times did the
    device change", not "how many devices were there". The scored transaction's own device counts
    toward the set even though the window excludes the scored row: a switch is only visible if the
    device being switched *to* is in the comparison, and excluding it would make the first
    transaction from a new device look like no change at all.
    """
    device = scored.device_fingerprint
    if device is None:
        return float("nan")
    rows = _within(history, scored, "device_changes_24h")
    devices = {row.device_fingerprint for row in rows if row.device_fingerprint is not None}
    devices.add(device)
    return float(len(devices) - 1)


def device_age_days(scored: Transaction, first_seen_at: datetime | None) -> float:
    """Days since this device was first seen **anywhere in the institution**, not on this account.

    A device first seen an hour ago is the signal; scoping it to the account would make every
    device new on its first use there, which is what `device_is_new_for_account` already says.

    `DURABLE`, and supplied rather than derived, on the same reasoning as PB-37's account
    first-seen: after a cache flush the earliest arrival is the rolling window's edge, not the
    device's first sighting, so a path that inferred it would report every device in the estate as
    days old at the moment the store came back. NaN when it is unknown.
    """
    if scored.device_fingerprint is None or first_seen_at is None:
        return float("nan")
    return (scored.timestamp - first_seen_at).total_seconds() / SECONDS_PER_DAY


# ---------------------------------------------------------------------------------------------
# Account profile (4). Three read state the dataset does not carry and one reads the account's own
# arrivals; two of the three are read **as of the transaction** (ADR 0026).
# ---------------------------------------------------------------------------------------------


def account_age_days(scored: Transaction, opened_at: datetime | None) -> float:
    """Days between the account's opening date and the scored transaction.

    Distinct from the first-seen-in-data timestamp `velocity_ratio_1h_vs_30d` needs: an account may
    be opened long before it transacts, so one does not substitute for the other and PB-37's
    migration must carry both.

    NaN when the opening date is unknown. The registry's nan_rule says "never NaN for an
    institution account", which is a statement about a system that has the column — M1 has no
    per-account table at all (PB-37), so today the honest output is NaN rather than an age
    measured from whatever the data happens to start at.
    """
    if opened_at is None:
        return float("nan")
    return (scored.timestamp - opened_at).total_seconds() / SECONDS_PER_DAY


def kyc_tier(scored: Transaction, assignments: Sequence[TierAssignment]) -> float:
    """The tier in force at the transaction's timestamp, never the current one (ADR 0026).

    This is the feature that shows `reference_data_basis` is not a one-feature field. Tier upgrades
    are frequently triggered by investigation, so the *current* tier of an investigated account is
    a consequence of the fraud being scored: reading it here is a label arriving through a profile
    column, not merely an anachronism.

    NaN when no assignment had taken effect. The registry says every account holds a tier from
    opening, so that is a data gap rather than a state — and an invented tier would be an ordinal
    the model reads as a real one. Ordinal, so ADR 0025 requires exact equality across paths.
    """
    in_force = [a for a in assignments if a.effective_at <= scored.timestamp]
    if not in_force:
        return float("nan")
    return float(max(in_force, key=lambda a: a.effective_at).tier)


def days_since_sim_swap(scored: Transaction, swaps: Sequence[datetime]) -> float:
    """Days since the most recent SIM swap strictly before the scored transaction.

    NaN both when the MNO signal is unavailable and when it is available and reports no swap. They
    are different facts and conflating them is still right: both are the genuine absence of a date,
    and the alternative — a large number for "no swap ever" — would let the models read it as "a
    swap long ago", which is the safe end of a signal whose dangerous end is "a swap an hour ago".
    Distinguishing the two would need a second feature, not a sentinel in this one.

    Swaps at or after the scored timestamp are excluded. An MNO feed delivered in bulk carries
    swaps that had not happened yet, which is `label_available_at`'s problem in a substrate where
    nobody thinks to look for it.
    """
    earlier = [when for when in swaps if when < scored.timestamp]
    if not earlier:
        return float("nan")
    return (scored.timestamp - max(earlier)).total_seconds() / SECONDS_PER_DAY


def dormancy_reactivation_flag(history: Sequence[Transaction], scored: Transaction) -> bool:
    """True when the account was silent for 60 d having transacted before that.

    Two conditions, and the second is what makes the feature `DURABLE`: a 60-day window can see
    that an account has been quiet, and cannot tell a dormant account from a new one. Only
    unbounded knowledge of whether it *ever* transacted separates them, and that is exactly what a
    cache flush destroys — after which every established account looks new and the flag reads
    False across the estate, which is the quiet direction.

    An account with no history at all is False, not NaN: it is new, and new is a definite state.
    That is `minimum_history=1` with `below_threshold_value=0.0`.
    """
    earlier = [
        row
        for row in history
        if row.account_id == scored.account_id and row.timestamp < scored.timestamp
    ]
    if not earlier:
        return False
    window_start = scored.timestamp - window_of("dormancy_reactivation_flag")
    return max(row.timestamp for row in earlier) <= window_start


# ---------------------------------------------------------------------------------------------
# Agent (4). NaN together outside AGENT_BANKING — the second structural-NaN set D-04 fixes at
# four, alongside the device group's. Two of them are keyed by the AGENT, so E1's account-grouped
# folds do not isolate them.
# ---------------------------------------------------------------------------------------------

AGENT_FEATURES = (
    "agent_float_utilisation_ratio",
    "agent_cashout_count_1h",
    "agent_unique_customers_1h",
    "agent_distance_from_registered_km",
)
"""The four that are NaN together outside `AGENT_BANKING`, named for the same reason as
`DEVICE_FEATURES`: the contract is about which features go missing at once."""


def _agent_of(scored: Transaction) -> str | None:
    """The agent token, or None when this is not an agent transaction.

    None is data rather than a missing input — most transactions are not at an agent — so the
    agent features return NaN for it, exactly as the device features do for a null fingerprint.
    """
    return scored.agent_id


def agent_float_utilisation_ratio(scored: Transaction, standing: Sequence[AgentStanding]) -> float:
    """The agent's float drawn down as a fraction of its limit, both as of the transaction.

    Both numbers are mutable, so both are read as-of (ADR 0026): today's limit applied to an old
    transaction imports a limit change that may itself have followed the incident being scored.
    """
    if _agent_of(scored) is None:
        return float("nan")
    current = _standing_at(standing, scored.timestamp)
    if current is None:
        return float("nan")
    return current.float_balance_rwf / current.float_limit_rwf


def agent_cashout_count_1h(
    corpus: Sequence[Transaction], scored: Transaction, cash_out_codes: frozenset[str]
) -> float:
    """Cash-out transactions at this agent in the trailing 1 h.

    `cash_out_codes` is supplied rather than written here for the same reason the FX table and the
    denominations are: a merchant category code is reference data, and a literal here would be one
    more thing to change when a deployment's coding scheme differs.

    **On the current dataset every agent transaction is a cash disbursement**, because the
    generator gives them all one code and models no cash-in. So this feature and
    `agent_unique_customers_1h` count the same rows here, differing only in that one counts rows
    and the other counts accounts. That is a property of the benchmark rather than of the feature,
    and it is why the fixture below contains a non-cash-out agent transaction that the dataset
    never produces.
    """
    agent = _agent_of(scored)
    if agent is None:
        return float("nan")
    start = scored.timestamp - window_of("agent_cashout_count_1h")
    return float(
        sum(
            1
            for row in corpus
            if row.agent_id == agent
            and row.merchant_category_code in cash_out_codes
            and start < row.timestamp < scored.timestamp
        )
    )


def agent_unique_customers_1h(corpus: Sequence[Transaction], scored: Transaction) -> float:
    """Distinct accounts transacting at this agent in the trailing 1 h.

    Keyed by the AGENT, and like `accounts_per_device_7d` the cross-account count **is** the
    signal — an agent serving many unrelated customers in an hour is the thing being measured, so
    a training-fold restriction would change the feature's value rather than protect it. The
    registry names component folding as the control and the mutation that would expose its
    failure.
    """
    agent = _agent_of(scored)
    if agent is None:
        return float("nan")
    start = scored.timestamp - window_of("agent_unique_customers_1h")
    return float(
        len(
            {
                row.account_id
                for row in corpus
                if row.agent_id == agent and start < row.timestamp < scored.timestamp
            }
            | {scored.account_id}
        )
    )


def agent_distance_from_registered_km(
    scored: Transaction, standing: Sequence[AgentStanding]
) -> float:
    """Distance from the agent's registered premises to where the transaction happened.

    The premises are read as-of: an agent that relocated after an incident would otherwise appear
    to have been at its new address all along, and this feature would report a short distance for
    exactly the transactions that were far from where the agent actually was.
    """
    if _agent_of(scored) is None:
        return float("nan")
    current = _standing_at(standing, scored.timestamp)
    if current is None:
        return float("nan")
    return haversine_km(current.latitude, current.longitude, scored.latitude, scored.longitude)


def _standing_at(standing: Sequence[AgentStanding], at: datetime) -> AgentStanding | None:
    """The agent's most recent standing that had taken effect by `at`, or None.

    None rather than the earliest known: before the agent had a recorded standing there is no
    utilisation and no registered address, and substituting the first one on record would apply a
    float limit that did not exist to a transaction that predates it.
    """
    in_force = [record for record in standing if record.effective_at <= at]
    if not in_force:
        return None
    return max(in_force, key=lambda record: record.effective_at)


# ---------------------------------------------------------------------------------------------
# Synthetic identity (1). Part E.2 names four terms and delegates the form: "a deterministic,
# documented composite in [0, 1]". Defining it is therefore the work rather than a deviation, and
# the definition is written down here and in the registry rather than being inferable from the
# arithmetic.
# ---------------------------------------------------------------------------------------------

RAMP_SHORT_WINDOW = timedelta(days=7)
"""The composite's internal "recent" window, measured against its declared 30 d window.

Not a declared window of any feature, and deliberately not borrowed from one: it is part of the
composite's own documented form, which Part E.2 delegates, and naming `amount_sum_7d`'s window to
obtain the number would tie this feature to one it has nothing to do with."""

#: The share of the 30-day window that the trailing 7 days occupy for an account transacting at a
#: constant rate. The ramp term measures departure from this, so an even account scores 0 rather
#: than 0.233 and the term means "faster than its own baseline" rather than "recent".
_EVEN_RATE_SHARE = RAMP_SHORT_WINDOW / timedelta(days=30)

NEW_ACCOUNT_DAYS = 30.0
"""Part E.2's "account age under 30 d". A threshold, not a ramp: the specification names a cliff,
and smoothing it would be a second modelling decision hidden inside a feature that is supposed to
be a hand-specified heuristic."""

SYNTHETIC_IDENTITY_TERMS = 4
"""Equal weights, deliberately. The registry records that this composite "carries no fitted
quantity and no fold dependence of its own" — any other weighting would be a fitted quantity
unless it were argued from evidence, and there is none to argue from. Equal weights are the
assumption that is visible as an assumption."""


def synthetic_identity_score(
    history: Sequence[Transaction], scored: Transaction, evidence: IdentityEvidence
) -> float:
    """A documented composite in [0, 1] of Part E.2's four synthetic-identity terms.

    **The form**: the unweighted mean of four terms, each independently in [0, 1]. The score is
    therefore in [0, 1] by construction rather than by clipping, and every term can be read off
    the output — a score of 0.25 is exactly one term at its maximum, or four at a quarter.

    **The terms**:

    1. *Low KYC tier.* Linear in the tier's position within the declared range, 1 at the lowest
       tier and 0 at the highest. The range is supplied, because KYC tiers are a country-pack fact
       (ADR 0023) and a literal here would be one more country-specific value in code.
    2. *A new account.* 1 below `NEW_ACCOUNT_DAYS`, 0 at or above it.
    3. *Device sharing.* 0 for one account on the device, rising to 1 at three or more. Three
       because two accounts on a handset is a shared family phone and is common; a ring is
       several.
    4. *A rapid volume ramp.* The share of the trailing 30 days' transactions that fall in the
       trailing 7, rescaled so that a constant-rate account scores 0 and an account whose entire
       recent history is in the last week scores 1.

    **Missing evidence contributes zero, never NaN.** The registry fixes this for the device term —
    a null fingerprint gives 0, not a propagated NaN, because otherwise every USSD transaction
    would lose this feature too and D-04's structural-NaN count would be five rather than four.
    The same rule is applied to every term, which is the only coherent reading: the composite
    accumulates evidence, so absent evidence adds none. The consequence is worth stating plainly —
    **a score of 0 means "no evidence" and not "checked and clean"**, and the two are
    indistinguishable in the output.

    **Two of the four terms are impaired on the current dataset.** No device is shared (PB-40), so
    term 3 is constant; and Part E.2's "phone attributes" have no column at all, so the term is
    device-only rather than device-and-phone. The registry declares the degeneracy.
    """
    terms = (
        _low_kyc_term(evidence.kyc_tier, evidence.kyc_tier_range),
        _new_account_term(scored, evidence.opened_at),
        _device_sharing_term(evidence.accounts_on_device),
        _volume_ramp_term(history, scored),
    )
    return sum(terms) / SYNTHETIC_IDENTITY_TERMS


def _low_kyc_term(tier: float, tier_range: tuple[int, int]) -> float:
    lowest, highest = tier_range
    if highest <= lowest:
        raise ValueError(
            f"kyc_tier_range {tier_range} has no span, so 'low tier' has no meaning; the range "
            "comes from the country pack and a degenerate one is a pack error"
        )
    if math.isnan(tier):
        return 0.0
    position = (highest - tier) / (highest - lowest)
    return min(1.0, max(0.0, position))


def _new_account_term(scored: Transaction, opened_at: datetime | None) -> float:
    if opened_at is None:
        return 0.0
    age = (scored.timestamp - opened_at).total_seconds() / SECONDS_PER_DAY
    return 1.0 if age < NEW_ACCOUNT_DAYS else 0.0


def _device_sharing_term(accounts_on_device: float) -> float:
    """0 for an unshared device, 1 at three accounts or more, linear between.

    Three rather than two: a handset used by two accounts is a shared family phone and is common
    enough that treating it as evidence of a ring would make the term fire on ordinary behaviour.
    """
    if math.isnan(accounts_on_device):
        return 0.0
    return min(1.0, max(0.0, (accounts_on_device - 1.0) / 2.0))


def _volume_ramp_term(history: Sequence[Transaction], scored: Transaction) -> float:
    """How much of the trailing 30 days' activity falls in the trailing 7, rescaled.

    A constant-rate account puts 7/30 of its month in the last week, so that is the zero point; an
    account whose entire month is in the last week scores 1. Rescaling rather than reporting the
    raw share is what makes the term mean "ramping" instead of "recent", and what lets it be
    averaged with three terms that already mean "suspicious".
    """
    recent = len(_within_span(history, scored, RAMP_SHORT_WINDOW))
    month = len(_within_span(history, scored, window_of("synthetic_identity_score")))
    if month == 0:
        return 0.0
    share = recent / month
    return min(1.0, max(0.0, (share - _EVEN_RATE_SHARE) / (1.0 - _EVEN_RATE_SHARE)))
