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
    CountryFacts,
    LimitDimension,
    OperationalLimit,
    Outcome,
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

    short_count = sum(1 for x in history if short_start < x.timestamp < t)
    long_count = sum(1 for x in history if long_start < x.timestamp <= short_start)

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
    window = window_of(name)
    start = scored.timestamp - window
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
