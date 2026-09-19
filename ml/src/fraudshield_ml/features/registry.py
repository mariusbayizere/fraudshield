"""The declarative feature contract. This module computes nothing.

Part E.2 requires every feature to carry "name, group, dtype, definition, window, source
(Redis/DB/request), NaN rule, leakage note, plain-English template key". Those nine describe
*what* a feature is.

Six further fields describe *how its window is read*, and they exist because writing the first
windowed feature exposed six questions Part E.2 does not answer — and which two independent
implementations would each answer plausibly, differently, and silently. A ratio of a 1 h count to a
30 d mean has to decide whether the scored transaction counts toward its own window, whether the
1 h numerator is also inside the 30 d denominator, what a zero-history account returns, whether the
denominator divides by 30 days or by the history actually observed, whether that requires state
surviving a cache flush, and what happens when the online store is down.

Every one of those is a fork where the batch path and the online path can disagree while both look
correct in review. The parity suite would catch the disagreement, but only after both were written;
the registry makes each answer a **declared field that validation refuses to leave blank**, so the
fork is resolved once, in one place, before either path is written.

The registry is the only module both paths import (parity Decision 4). It holds no arithmetic, so
importing it cannot make the two paths share a bug.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class SelfInclusion(Enum):
    """Whether the transaction being scored counts toward its own window.

    The online path sees the transaction arrive and must decide whether to fold it into the
    aggregate before or after scoring; the batch path, reading a completed history, has no natural
    moment at which to make the same choice. Left undeclared, this is an off-by-one that appears
    only on an account's first transaction and at every window boundary.
    """

    EXCLUDED = "excluded"
    INCLUDED = "included"


class Nesting(Enum):
    """For a ratio of nested windows, whether the short window is inside the long denominator.

    ``velocity_ratio_1h_vs_30d`` compares the last hour against a 30-day baseline. If the hour is
    also in the baseline, the baseline moves with the thing it is a baseline for, damping exactly
    the burst the feature exists to detect.
    """

    SHORT_EXCLUDED = "short_excluded"
    SHORT_INCLUDED = "short_included"
    NOT_NESTED = "not_nested"


class PriorSource(Enum):
    """A smoothing prior that is fitted rather than declared.

    ``GLOBAL_TRAIN_RATE`` is the base fraud rate of the **training folds only**, account-grouped
    per E1. It cannot be a literal in this file: it depends on the dataset and on its size, which
    E2 forbids quoting without a scale, and computing it over all rows would leak the validation
    fold's labels into every cell's prior.
    """

    GLOBAL_TRAIN_RATE = "global_train_rate"


class SmoothingPlacement(Enum):
    """Where the smoothing constant is applied in a ratio.

    ``BOTH_TERMS`` is what makes a zero-history account return exactly 1.0 — "this account looks
    like its own baseline" — rather than 0.0 (which reads as suspiciously quiet) or NaN (which
    discards the row). Applying it to the numerator alone biases every ratio upward; to the
    denominator alone, downward.
    """

    BOTH_TERMS = "both_terms"
    NUMERATOR_ONLY = "numerator_only"
    DENOMINATOR_ONLY = "denominator_only"


class HistoryBasis(Enum):
    """What the denominator of a rate or mean divides by.

    ``ASSUMED_FULL`` divides by the nominal window regardless of how much history exists, so a
    three-day-old account is scored as though it had been quiet for 27 days — indistinguishable
    from dormancy, and wrong in the direction that makes new accounts look safe.

    ``OBSERVED_CAPPED`` divides by the history actually observed, capped at the window. It requires
    knowing when the account was first seen, which is why it forces ``HistoryRequirement.DURABLE``.
    """

    OBSERVED_CAPPED = "observed_capped"
    ASSUMED_FULL = "assumed_full"
    NOT_TIME_NORMALISED = "not_time_normalised"
    """The denominator is a count of observed events, not an elapsed span.

    A cell's fraud *proportion* divides confirmed fraud by transactions seen, so there is no
    "divide by 30 days" choice to make. Declaring this explicitly is not the same as leaving the
    field blank: it is the assertion that the question was asked and does not apply."""


class HistoryRequirement(Enum):
    """Whether the feature's state must survive a cache flush.

    ``CACHE_SUFFICIENT`` state can be rebuilt from the rolling window itself: a 24-hour count is
    whole again 24 hours after a flush. ``DURABLE`` state cannot — an account's first-seen
    timestamp is not recoverable from a 30-day window once the account is older than 30 days, so a
    flush would silently switch the feature from OBSERVED_CAPPED to a shorter apparent history and
    inflate every rate it feeds.

    A DURABLE field is a schema commitment, not a cache-warming problem.
    """

    DURABLE = "durable"
    CACHE_SUFFICIENT = "cache_sufficient"


class FallbackBehaviour(Enum):
    """What the feature emits when the online store is down and the DB fallback serves.

    M1 built ``account_activity_hourly``, a continuous aggregate whose own comment names it the
    "database fallback for velocity features when Redis is down (C.4)". It buckets to the hour, so
    sub-hour windows are not computable from it and a trailing hour is not the same number as a
    bucket-aligned hour.

    ``NAN_UNDER_FALLBACK`` declares the feature absent on the degraded path, which the models'
    native missing handling (D-04) already covers. ``EXACT`` asserts the fallback reproduces the
    online value under ADR 0025's rule.

    **``EXACT`` is reachable for every trailing window, by a hybrid read**, which is not obvious and
    was missed on the first pass: take the complete hourly buckets from the aggregate and read raw
    ``transactions`` for the partial head and tail hours only. The raw portion spans at most two
    hours regardless of whether the window is 24 h or 30 d, so the degraded path stays cheap while
    the value stays exact. Summing whole buckets alone is *not* exact for any trailing window — at
    10:30 a bucket sum covers 10:00-10:30 where the trailing hour covers 09:30-10:30, and the error
    does not shrink to nothing for long windows, it merely becomes easy to excuse.

    ``NAN_UNDER_FALLBACK`` is therefore reserved for features whose inputs are **not in the database
    at all**, chiefly the cross-account device state that lives only in the online store.

    The value that is not available is "approximately right": a bucket-aligned substitute passes
    every warm-path test and diverges only during an incident.
    """

    EXACT = "exact"
    NAN_UNDER_FALLBACK = "nan_under_fallback"


@dataclass(frozen=True)
class Smoothing:
    """A smoothing constant, where it is applied, and what it shrinks toward.

    ``prior`` is the value a zero-evidence observation returns. A *ratio* smooths toward 1.0 ("this
    account looks like its own baseline"); a *rate* smooths toward the global base rate ("an unseen
    cell is an average cell"). Both are Laplace-shaped and they are not interchangeable: shrinking
    a fraud rate toward 1.0 would make every unseen H3 cell certain fraud.

    The field was added when the second feature was registered. The first needed only alpha and
    placement, and a schema fitted to one example had silently encoded "shrink toward 1.0" as an
    assumption of the arithmetic rather than a declared value.
    """

    alpha: float
    placement: SmoothingPlacement
    prior: float | PriorSource

    def __post_init__(self) -> None:
        if self.alpha <= 0.0:
            raise ValueError(f"smoothing alpha must be positive, got {self.alpha}")


class LabelBasis(Enum):
    """Which labels a feature's window may see.

    Part E.2: "Label-derived features use only labels whose ``label_available_at`` < transaction
    time." The rule is unambiguous and the fork is still real, because the two paths are asymmetric
    by construction: the online path *cannot* see a label that has not arrived, and the batch path
    *can*, and will, unless something stops it. A batch implementation that filters on
    ``confirmed_at`` rather than ``label_available_at`` looks correct in review and leaks the
    investigation delay straight into the feature.

    Declaring it makes the asymmetry a registry fact that the parity suite's prefix replay tests,
    rather than a sentence in a leakage note that only the batch author reads.
    """

    NOT_LABEL_DERIVED = "not_label_derived"
    AVAILABLE_AT_LAG = "available_at_lag"


class HistoryKey(Enum):
    """What entity the feature's window aggregates over. **The field with teeth.**

    M3 exit criterion E1 requires every fold, split, sample and target encoding to group by
    **account**, because fraud arrives as incidents sharing an account and a row is not an
    independent unit. That rule assumes the thing a feature aggregates over *is* the account. For
    most features it is. For some it is not, and for those **account-grouped folds do not isolate
    anything**.

    Concretely: ``counterparty_unique_senders_24h`` counts distinct senders to a mule. A fraud ring
    moving money from ten victim accounts into one mule produces ten rows whose feature values are
    all computed from the same counterparty-keyed aggregate. Put victim A in train and victim B in
    validation and B's transactions are inside the feature A was trained on — cross-account leakage
    that account-grouped folds are blind to by construction, because the leak does not travel
    through the account.

    This is M2's M-8 defect at one level up. M-8 folded per row when the unit was the incident; this
    folds per account when the unit is the counterparty. Same shape, found the same way, by asking
    what the grouping is actually grouping.

    **Any feature whose key is not ``ACCOUNT`` must state how it is fold-safe**, and the only
    generally correct answer is that the aggregate is computed from training-fold rows only, with
    the folds themselves grouped by account.
    """

    ACCOUNT = "account"
    COUNTERPARTY = "counterparty"
    DEVICE = "device"
    GEO_CELL = "geo_cell"
    AGENT = "agent"
    MERCHANT = "merchant"


@dataclass(frozen=True)
class MinimumHistory:
    """The history below which a feature refuses to produce an estimate, and what it emits instead.

    Part E.2 specifies ``amount_zscore_90d`` as "robust: median/MAD; NaN->0 with < 5 history". That
    sentence contains three separate forks and settles none of them: whether 5 counts transactions
    or days, whether the scored transaction is one of the 5, and whether the output below the
    threshold is ``0.0`` or NaN — the arrow is read as "NaN becomes 0" by one implementer and "emit
    NaN, or 0" by another.

    It is a distinct mechanism from smoothing and cannot be folded into it. Smoothing degrades
    gracefully toward a prior and is defined at zero evidence; a minimum-history rule is a hard
    cliff that refuses an estimate the statistic cannot support — a MAD over four points is not
    imprecise, it is meaningless.

    ``below_threshold_value`` of ``None`` means NaN. Emitting ``0.0`` from a z-score is the more
    dangerous of the two options and so must be stated rather than defaulted: zero is the *most
    normal possible value*, so a thin-history account is scored as perfectly typical rather than
    as unknown, which is the direction that makes a new account look safe.
    """

    minimum_observations: int
    below_threshold_value: float | None

    def __post_init__(self) -> None:
        if self.minimum_observations < 1:
            raise ValueError(
                f"minimum_observations must be at least 1, got {self.minimum_observations}"
            )


@dataclass(frozen=True)
class WindowContract:
    """The answers a windowed feature must give before either path is written.

    Six fields when the first feature was registered; ``label_basis`` made seven when the second
    needed it; ``minimum_history`` made eight when the amount group arrived. The count is not the
    point — refusing to leave any of them blank is.

    ``minimum_history`` is ``None`` for a feature that has no such cliff, which is a declared answer
    and not a blank: a count is defined at one observation and a ratio is defined at zero through
    its smoothing, so most features genuinely have no threshold.

    ``history_key`` made nine, arriving from the counterparty group and immediately finding a defect
    in a feature registered two hours earlier. See ``HistoryKey``.
    """

    self_inclusion: SelfInclusion
    nesting: Nesting
    smoothing: Smoothing | None
    history_basis: HistoryBasis
    history_requirement: HistoryRequirement
    fallback_behaviour: FallbackBehaviour
    label_basis: LabelBasis
    minimum_history: MinimumHistory | None
    history_key: HistoryKey
    cross_account_control: str | None = None

    def __post_init__(self) -> None:
        # A non-account key means E1's account-grouped folds do not isolate this feature, so the
        # feature must say what does. Refused rather than defaulted: there is no safe default, and
        # a blank here is precisely the omission that made the first geo_cell leakage note wrong.
        if self.history_key is not HistoryKey.ACCOUNT:
            control = (self.cross_account_control or "").strip()
            if not control:
                raise ValueError(
                    f"history_key={self.history_key.value} aggregates across accounts, so E1's "
                    "account-grouped folds do not isolate it; cross_account_control must state "
                    "what does. Leaving it blank is how a leak that travels through the "
                    f"{self.history_key.value} rather than through the account goes unnoticed"
                )
            # A control that only asserts safety is the failure mode that produced this field: the
            # first geo_cell leakage note asserted a protection that did not exist, and a reader
            # auditing it would have been reassured. Every control therefore names the mutation
            # that would detect the leak if the control failed, so it ships with its own
            # falsification test rather than with a claim.
            if "mutation" not in control.lower():
                raise ValueError(
                    f"history_key={self.history_key.value}: cross_account_control must name the "
                    "mutation that would DETECT this leak if the control failed. A control that "
                    "only asserts safety is exactly the claim-with-nothing-behind-it this field "
                    "exists to prevent"
                )
        elif self.cross_account_control is not None:
            raise ValueError(
                "history_key=account is isolated by E1's folds already; a cross_account_control "
                "here would describe a control that is not doing anything"
            )
        if (
            self.history_basis is HistoryBasis.OBSERVED_CAPPED
            and self.history_requirement is not HistoryRequirement.DURABLE
        ):
            raise ValueError(
                "history_basis=OBSERVED_CAPPED divides by observed history, which needs a "
                "first-seen timestamp that a cache flush would destroy; it requires "
                "history_requirement=DURABLE"
            )


class Group(Enum):
    """Part E.2's nine feature groups, with the catalogue's count for each."""

    VELOCITY = "velocity"
    AMOUNT_BEHAVIOUR = "amount_behaviour"
    TEMPORAL = "temporal"
    GEOGRAPHIC = "geographic"
    COUNTERPARTY = "counterparty"
    DEVICE_AND_CHANNEL = "device_and_channel"
    ACCOUNT_PROFILE = "account_profile"
    AGENT = "agent"
    CORRIDOR = "corridor"
    SYNTHETIC_IDENTITY = "synthetic_identity"


class Source(Enum):
    """Where the online path reads the feature's inputs (Part E.2)."""

    REQUEST = "request"
    REDIS = "redis"
    DB = "db"


class Dtype(Enum):
    FLOAT64 = "float64"
    INT64 = "int64"
    BOOL = "bool"
    CATEGORICAL = "categorical"
    ORDINAL = "ordinal"


#: Which features are NaN when an account's only prior transaction shares the scored timestamp
#: exactly, and why that is a documented limit rather than a defect (owner direction 2026-09-19).
#:
#: A predecessor is a row **strictly earlier** than the scored one. That is the convention every
#: windowed feature here uses, and it is the only one the online path *can* implement: at scoring
#: time a transaction stamped the same instant may not have arrived yet, so a rule that counted it
#: would make the two paths disagree precisely on simultaneous transactions. Since a burst of
#: drains inside one second is itself a fraud pattern, putting the divergence there would be the
#: worst available place for it.
#:
#: The consequence, stated rather than left to be discovered: when the ONLY prior transaction
#: shares the scored timestamp exactly, these three features are NaN —
#: ``seconds_since_last_tx``, ``distance_from_last_tx_km`` and ``implied_speed_kmh``. That is the
#: honest output (a capped speed derived from a zero gap would be a number with no journey behind
#: it), and it is a silence, so it is named here and in the datasheet rather than inferred.
#:
#: **Detecting simultaneous bursts is the velocity group's job, not the geographic group's**, and
#: whether the velocity group can actually do it is a measured question rather than an assumption:
#: ``tx_count_60s`` uses the same strictly-earlier bound, so it too cannot see a row stamped the
#: same microsecond. What it does see is any row stamped even one microsecond earlier. The
#: measurement therefore has to be of the *data*: how often an account's consecutive transactions
#: carry byte-identical timestamps.
#:
#: **Measured on 201,243 rows at seed 20260917 (tree ``65c8351``): zero collisions.** No two
#: transactions on an account are ever stamped the same instant, so the silence above costs
#: nothing on this benchmark and the strictly-earlier bound excludes nothing from
#: ``tx_count_60s`` either. In the same run 6 consecutive same-account pairs are under a second
#: apart and 261 are under a minute, so the velocity group does see the bursts this dataset has —
#: about 0.13% of rows. Rare rather than dead, which is what a burst indicator should be, but a
#: claim resting on it is a claim about a few hundred rows at this scale (E2). Recorded in the
#: datasheet with the tree.
SIMULTANEOUS_PREDECESSOR_NOTE = (
    "seconds_since_last_tx, distance_from_last_tx_km and implied_speed_kmh are NaN when the only "
    "prior transaction shares the scored timestamp exactly, because a predecessor is strictly "
    "earlier on both paths. Simultaneous-burst detection belongs to the velocity group."
)

#: Primitives both paths may share, per parity Decision 4. The parity test cannot see into these,
#: so each carries its own unit tests with hand-computed expectations. A shared window-aggregation
#: helper is deliberately **not** permitted: it is precisely the surface the parity test exists to
#: cover, and sharing it would make the test prove that a function equals itself.
SHARED_PRIMITIVES = frozenset({"haversine_km", "fx_to_rwf", "h3_cell"})


class ReferenceDataBasis(Enum):
    """When mutable operational configuration is read (ADR 0026).

    Deliberately **not** part of ``WindowContract``: ``just_below_limit_flag`` declares no window,
    so a field on the window contract could not reach the feature that motivated it. Configuration
    drift is a separate axis from window reading, and putting it on the wrong object would have
    left the original hole open.

    The asymmetry is the same one ``label_basis`` addresses in another substrate: the online path
    structurally cannot see a future configuration value, the batch path can and will unless
    stopped. Training on today's threshold against an eight-month-old transaction encodes a limit
    that did not exist when the transaction happened.
    """

    AS_OF_EVENT = "as_of_event"
    CURRENT = "current"
    NOT_REFERENCE_DATA = "not_reference_data"


@dataclass(frozen=True)
class FeatureSpec:
    """One feature's declared contract. Nothing here computes anything."""

    name: str
    group: Group
    dtype: Dtype
    definition: str
    source: Source
    nan_rule: str
    leakage_note: str
    #: Part E.2 calls this the "plain-English template key"; it is named ``template_id`` here
    #: because ``*_key`` assignments with high-entropy values trip gitleaks' generic-api-key rule,
    #: and the repository's gitleaks discipline forbids allowlisting by pattern. Renaming removes
    #: the false positive at source rather than teaching the scanner to ignore a class of finding.
    template_id: str
    reference_data_basis: ReferenceDataBasis
    window: str | None = None
    contract: WindowContract | None = None
    tolerance_note: str | None = None
    #: The permitted values of a categorical feature, in a fixed order. Declared rather than left
    #: to the two paths because ADR 0025 requires EXACT equality for a categorical: there is no
    #: tolerance to absorb a disagreement, so the two paths must not be free to spell a class
    #: differently, and a class one path can emit and the other cannot is a contract violation
    #: rather than a numerical difference. The order is fixed so a later encoder's codes are
    #: reproducible; the registry still computes nothing, and assigns no codes.
    categories: tuple[str, ...] | None = None
    #: Set when the feature is *computable but carries no signal on the current dataset*, naming
    #: the backlog item and what would clear it. Unlike the ten contract fields this has a default,
    #: and deliberately: it records an observed property of the data, not a fork two independent
    #: implementations could resolve differently. ML-DATA-07's completeness check cannot catch this
    #: class — a degenerate feature *is* computable — so it has to be declared.
    degeneracy: str | None = None

    def __post_init__(self) -> None:
        for field_name in ("definition", "nan_rule", "leakage_note", "template_id"):
            if not getattr(self, field_name).strip():
                raise ValueError(f"{self.name}: {field_name} is blank")

        # The validation the six fields exist for: a windowed feature may not ship with any of them
        # unanswered. Blank is refused rather than defaulted, because every default here is a
        # defensible choice that the other path might defend differently.
        if self.window is not None and self.contract is None:
            raise ValueError(
                f"{self.name}: window={self.window!r} but no WindowContract. Every windowed "
                "feature must declare self_inclusion, nesting, smoothing, history_basis, "
                "history_requirement and fallback_behaviour before either path is written"
            )
        if self.window is None and self.contract is not None:
            raise ValueError(
                f"{self.name}: carries a WindowContract but declares no window; the contract's "
                "fields have no meaning without one"
            )

        # A categorical with undeclared values is the same class of silence the ten contract
        # fields exist to close, in the one place where no tolerance can cover the disagreement.
        if self.dtype is Dtype.CATEGORICAL and not self.categories:
            raise ValueError(
                f"{self.name}: dtype is categorical, so its permitted values must be declared. "
                "ADR 0025 requires exact equality for a categorical, so the two paths cannot be "
                "left free to name the classes for themselves"
            )
        if self.dtype is not Dtype.CATEGORICAL and self.categories is not None:
            raise ValueError(
                f"{self.name}: declares categories but its dtype is {self.dtype.value}; the "
                "field would describe permitted values nothing reads"
            )
        if self.categories is not None:
            duplicates = [c for c in self.categories if self.categories.count(c) > 1]
            if duplicates:
                raise ValueError(f"{self.name}: duplicate category {duplicates[0]!r}")
            blank = [c for c in self.categories if not c.strip()]
            if blank:
                raise ValueError(f"{self.name}: a category is blank")


def contract_for(name: str) -> WindowContract:
    """The declared window contract, or a refusal naming the feature.

    A lookup, not a computation: it exists so the feature paths do not each carry an ``assert`` to
    narrow `WindowContract | None`. ``assert`` is stripped under ``python -O``, so a guard written
    that way is absent in exactly the deployment where it would matter.
    """
    spec = REGISTRY[name]
    if spec.contract is None:
        raise ValueError(f"{name} declares no window contract, so it has no window semantics")
    return spec.contract


def categories_for(name: str) -> tuple[str, ...]:
    """The declared permitted values of a categorical feature, or a refusal. A lookup.

    Both paths read this, for the same reason both read ``smoothing_for``: it is a declared
    answer, not a computation, so reading it cannot make the two paths share a bug. What each path
    must decide for itself is **which** of these values a transaction belongs to, and that is the
    surface the parity test covers.
    """
    spec = REGISTRY[name]
    if spec.categories is None:
        raise ValueError(f"{name} is not categorical, so it has no permitted values")
    return spec.categories


def smoothing_for(name: str) -> Smoothing:
    """The declared smoothing, or a refusal. Also a lookup."""
    smoothing = contract_for(name).smoothing
    if smoothing is None:
        raise ValueError(f"{name} declares no smoothing, so it has no zero-evidence value")
    return smoothing


def validate(specs: dict[str, FeatureSpec]) -> None:
    """Refuse a registry whose keys disagree with the specs they hold."""
    for key, spec in specs.items():
        if key != spec.name:
            raise ValueError(f"registry key {key!r} does not match spec name {spec.name!r}")


_VELOCITY_RATIO = FeatureSpec(
    name="velocity_ratio_1h_vs_30d",
    group=Group.VELOCITY,
    dtype=Dtype.FLOAT64,
    definition=(
        "Transactions in the trailing 1 h, divided by the mean hourly transaction count over the "
        "prior 30 d excluding that hour, both terms Laplace-smoothed by alpha=1.0 so an account "
        "with no history returns exactly 1.0."
    ),
    source=Source.REDIS,
    nan_rule=(
        "Warm path: never NaN. Zero history returns 1.0 by construction, and the DB fallback "
        "serves the same value by hybrid read. "
        "FAILS CLOSED (PB-37): NaN whenever the durable first-seen is unavailable for the account. "
        "history_basis=OBSERVED_CAPPED divides by history actually observed, so without that "
        "timestamp there is no denominator, only a guess - and the guess is not a small error. "
        "Restoring arrivals from the database while the per-account first-seen is missing, which "
        "is what M1's schema produces today, divides thirty days of rows by whatever span the "
        "cache happens to hold and collapses the ratio across the entire account base, during a "
        "recovery, when the system is already degraded. The models' native missing handling "
        "(D-04) covers a NaN; nothing covers a plausible wrong number."
    ),
    leakage_note=(
        "Both windows are strictly backward-looking and exclude the scored transaction, so no "
        "future row enters. Carries no label, so it is not subject to label_available_at. "
        "DURABLE: history_basis is OBSERVED_CAPPED, so the denominator divides by the history "
        "actually observed, which needs a first-seen timestamp. A cache flush would destroy it and "
        "the feature would silently divide by a shorter apparent history — inflating the ratio for "
        "every established account at once, in the direction that reads as a burst. The cold-cache "
        "parity case is the only test that would catch it."
    ),
    template_id="velocity.ratio_1h_vs_30d",
    reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    window="1h/30d",
    contract=WindowContract(
        self_inclusion=SelfInclusion.EXCLUDED,
        nesting=Nesting.SHORT_EXCLUDED,
        smoothing=Smoothing(alpha=1.0, placement=SmoothingPlacement.BOTH_TERMS, prior=1.0),
        history_basis=HistoryBasis.OBSERVED_CAPPED,
        history_requirement=HistoryRequirement.DURABLE,
        fallback_behaviour=FallbackBehaviour.EXACT,
        label_basis=LabelBasis.NOT_LABEL_DERIVED,
        minimum_history=None,
        history_key=HistoryKey.ACCOUNT,
    ),
)

REGISTRY: dict[str, FeatureSpec] = {_VELOCITY_RATIO.name: _VELOCITY_RATIO}

validate(REGISTRY)


_GEO_CELL_FRAUD_RATE = FeatureSpec(
    name="geo_cell_fraud_rate_30d",
    group=Group.GEOGRAPHIC,
    dtype=Dtype.FLOAT64,
    definition=(
        "Confirmed-fraud proportion of the transaction's H3 resolution-6 cell over the prior 30 d, "
        "counting only labels whose label_available_at precedes the transaction timestamp, "
        "shrunk toward the training-fold base rate with a pseudo-count of 50."
    ),
    source=Source.DB,
    nan_rule=(
        "Never NaN: a cell with no prior transactions returns the training-fold base rate through "
        "the prior. A NaN here would be indistinguishable from a genuinely unseen cell."
    ),
    leakage_note=(
        "The feature reads labels, so it is the one most able to leak, and its first leakage note "
        "was wrong. Guards: the window excludes the scored transaction; only labels with "
        "label_available_at < transaction timestamp are counted, never confirmed_at, which would "
        "import the investigation delay; and BOTH the prior AND every cell estimate are computed "
        "from training-fold rows only, with folds grouped by account. The original note claimed "
        "account-grouped folds meant no validation label reached a cell estimate. That is false: "
        "history_key is GEO_CELL, so a cell aggregates across accounts, and account-grouped folds "
        "are blind to a leak that travels through the cell rather than through the account. "
        "Subject to the D-08 single-feature AUC ceiling of 0.80, measured at a stated scale (E2)."
    ),
    template_id="geographic.cell_fraud_rate_30d",
    reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    window="30d",
    contract=WindowContract(
        self_inclusion=SelfInclusion.EXCLUDED,
        nesting=Nesting.NOT_NESTED,
        smoothing=Smoothing(
            alpha=50.0,
            placement=SmoothingPlacement.BOTH_TERMS,
            prior=PriorSource.GLOBAL_TRAIN_RATE,
        ),
        history_basis=HistoryBasis.NOT_TIME_NORMALISED,
        history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
        fallback_behaviour=FallbackBehaviour.EXACT,
        label_basis=LabelBasis.AVAILABLE_AT_LAG,
        minimum_history=None,
        history_key=HistoryKey.GEO_CELL,
        cross_account_control=(
            "Every cell estimate and the fitted prior are computed from training-fold rows only, "
            "with folds grouped by account per E1. A validation-fold account's transactions never "
            "enter any cell rate a training row sees. Tested by the mutation that computes cell "
            "rates over all rows and asserts the single-feature AUC rises, which is the only "
            "observable signature of this leak."
        ),
    ),
)

REGISTRY[_GEO_CELL_FRAUD_RATE.name] = _GEO_CELL_FRAUD_RATE

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Velocity (8). Seven trailing counts and sums share one contract shape exactly, so they share one
# named contract: writing it out eight times would make a future divergence between them look like
# ordinary variation instead of the decision it would be.
# ---------------------------------------------------------------------------------------------

_TRAILING_ACCOUNT_AGGREGATE = WindowContract(
    self_inclusion=SelfInclusion.EXCLUDED,
    nesting=Nesting.NOT_NESTED,
    smoothing=None,
    history_basis=HistoryBasis.NOT_TIME_NORMALISED,
    history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
    fallback_behaviour=FallbackBehaviour.EXACT,
    label_basis=LabelBasis.NOT_LABEL_DERIVED,
    minimum_history=None,
    history_key=HistoryKey.ACCOUNT,
)

_BACKWARD_ONLY = (
    "The window is strictly backward-looking and excludes the scored transaction, so no future row "
    "enters. Carries no label, so label_available_at does not apply."
)
_ZERO_HISTORY_IS_ZERO = (
    "Never NaN: an account with no history has counted nothing, which is 0, not unknown. NaN here "
    "would be indistinguishable from a genuinely quiet account."
)


def _count(name: str, window: str, definition: str) -> FeatureSpec:
    return FeatureSpec(
        name=name,
        group=Group.VELOCITY,
        dtype=Dtype.INT64,
        definition=definition,
        source=Source.REDIS,
        nan_rule=_ZERO_HISTORY_IS_ZERO,
        leakage_note=_BACKWARD_ONLY,
        template_id=f"velocity.{name}",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window=window,
        contract=_TRAILING_ACCOUNT_AGGREGATE,
    )


for _spec_ in (
    _count(
        "tx_count_60s",
        "60s",
        "Transactions on this account in the trailing 60 s before the scored transaction.",
    ),
    _count(
        "tx_count_1h",
        "1h",
        "Transactions on this account in the trailing 1 h before the scored transaction.",
    ),
    _count(
        "tx_count_24h",
        "24h",
        "Transactions on this account in the trailing 24 h before the scored transaction.",
    ),
    _count(
        "tx_count_7d",
        "7d",
        "Transactions on this account in the trailing 7 d before the scored transaction.",
    ),
):
    REGISTRY[_spec_.name] = _spec_


_AMOUNT_SUM_TOLERANCE = (
    "Real-valued sum: ADR 0025's relative rule applies, not exact equality. Two independent "
    "implementations accumulate in different orders, and at a heavy user's 7 d total the "
    "specification's original 1e-9 would have been below float64's resolution."
)

for _spec_ in (
    FeatureSpec(
        name="amount_sum_24h",
        group=Group.VELOCITY,
        dtype=Dtype.FLOAT64,
        definition=(
            "Sum of transaction amounts on this account in the trailing 24 h, each converted to "
            "RWF at the FX rate for its own transaction date, not the scored transaction's date."
        ),
        source=Source.REDIS,
        nan_rule=_ZERO_HISTORY_IS_ZERO,
        leakage_note=(
            _BACKWARD_ONLY + " The per-transaction-date FX rate is the leakage-relevant choice: "
            "converting the whole window at today's rate would import a rate published after most "
            "of those transactions occurred."
        ),
        template_id="velocity.amount_sum_24h",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="24h",
        contract=_TRAILING_ACCOUNT_AGGREGATE,
        tolerance_note=_AMOUNT_SUM_TOLERANCE,
    ),
    FeatureSpec(
        name="amount_sum_7d",
        group=Group.VELOCITY,
        dtype=Dtype.FLOAT64,
        definition=(
            "Sum of transaction amounts on this account in the trailing 7 d, each converted to RWF "
            "at the FX rate for its own transaction date."
        ),
        source=Source.REDIS,
        nan_rule=_ZERO_HISTORY_IS_ZERO,
        leakage_note=_BACKWARD_ONLY,
        template_id="velocity.amount_sum_7d",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="7d",
        contract=_TRAILING_ACCOUNT_AGGREGATE,
        tolerance_note=_AMOUNT_SUM_TOLERANCE,
    ),
    FeatureSpec(
        name="unique_counterparties_24h",
        group=Group.VELOCITY,
        dtype=Dtype.INT64,
        definition=(
            "Distinct counterparty tokens this account transacted with in the trailing 24 h."
        ),
        source=Source.REDIS,
        nan_rule=_ZERO_HISTORY_IS_ZERO,
        leakage_note=_BACKWARD_ONLY,
        template_id="velocity.unique_counterparties_24h",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="24h",
        contract=_TRAILING_ACCOUNT_AGGREGATE,
        tolerance_note=(
            "Exact set cardinality, never a probabilistic sketch. ADR 0025 requires exact equality "
            "for counts, and a HyperLogLog would make that unreachable by construction: its ~0.8% "
            "standard error is invisible at the daily volumes where the feature is uninteresting "
            "and decisive at the burst volumes where it is the signal. A 24 h per-account set is "
            "small enough that the sketch buys nothing worth this."
        ),
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Amount behaviour (5).
# ---------------------------------------------------------------------------------------------

for _spec_ in (
    FeatureSpec(
        name="amount_log1p",
        group=Group.AMOUNT_BEHAVIOUR,
        dtype=Dtype.FLOAT64,
        definition=(
            "log1p of the scored transaction's amount in RWF, converted at the FX rate for its own "
            "transaction date."
        ),
        source=Source.REQUEST,
        nan_rule=(
            "Never NaN: the amount is mandatory on the request and log1p is defined at 0. A "
            "negative amount is a contract violation refused at ingestion, not a NaN here."
        ),
        leakage_note=(
            "Reads only the scored transaction, so there is no window and no history to leak "
            "through. The FX rate is the transaction's own date, which is knowable at scoring time."
        ),
        template_id="amount.log1p",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="amount_zscore_90d",
        group=Group.AMOUNT_BEHAVIOUR,
        dtype=Dtype.FLOAT64,
        definition=(
            "Robust z-score of the scored amount against this account's prior 90 d of amounts: "
            "(amount - median) / (1.4826 * MAD), the constant making MAD a consistent estimator of "
            "the standard deviation under normality. Zero MAD yields the below-threshold value, "
            "since an account whose last 90 d are all one amount has no scale to divide by."
        ),
        source=Source.REDIS,
        nan_rule=(
            "Emits NaN with fewer than 5 prior transactions. DEVIATION from Part E.2's 'NaN->0 "
            "with < 5 history', taken by owner decision 2026-09-19 and recorded, not silent. "
            "Reason: 0.0 is the most normal possible z-score, so emitting it claims a thin-history "
            "account is exactly average — a confident statement about an account we know almost "
            "nothing about, and new accounts are disproportionately fraud-relevant. That is the "
            "same bias argument that settled history_basis as OBSERVED_CAPPED: both wrong answers "
            "point the same way, making a new account look ordinary. NaN lets the gradient-boosted "
            "models' native missing handling learn what absent history implies. E.2's "
            "median-imputation clause applies to the Isolation Forest and the baselines only "
            "(D-04), which is precisely this case."
        ),
        leakage_note=(
            _BACKWARD_ONLY + " The median and MAD are computed over the prior window only; "
            "including the scored amount would shrink its own z-score toward zero, most strongly "
            "for exactly the outliers the feature exists to find."
        ),
        template_id="amount.zscore_90d",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="90d",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=MinimumHistory(minimum_observations=5, below_threshold_value=None),
            history_key=HistoryKey.ACCOUNT,
        ),
        tolerance_note=(
            "A median is order-independent and exact; the division is real-valued, so ADR 0025's "
            "relative rule applies. The fallback reads raw amounts rather than the hourly "
            "aggregate, which carries only sums and counts and cannot yield a median at all."
        ),
    ),
    FeatureSpec(
        name="amount_to_max_90d_ratio",
        group=Group.AMOUNT_BEHAVIOUR,
        dtype=Dtype.FLOAT64,
        definition=(
            "Scored amount divided by the largest amount on this account in the prior 90 d, both "
            "in RWF at their own transaction dates."
        ),
        source=Source.REDIS,
        nan_rule=(
            "NaN with no prior transaction, because the denominator does not exist. Part E.2 does "
            "not state this case; NaN is chosen over 1.0 because 1.0 means 'exactly the account's "
            "previous maximum', a strong and specific claim that no-history does not support."
        ),
        leakage_note=_BACKWARD_ONLY,
        template_id="amount.to_max_90d_ratio",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="90d",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=MinimumHistory(minimum_observations=1, below_threshold_value=None),
            history_key=HistoryKey.ACCOUNT,
        ),
    ),
    FeatureSpec(
        name="round_sum_flag",
        group=Group.AMOUNT_BEHAVIOUR,
        dtype=Dtype.BOOL,
        definition=(
            "True when the amount is an exact multiple of one of the transaction currency's common "
            "denominations, read from the country pack rather than hard-coded (ADR 0023). Not a "
            "fraud signal alone: most salary and rent payments are round."
        ),
        source=Source.REQUEST,
        nan_rule=(
            "Never NaN: every simulated currency has denominations in its pack, and a pack without "
            "them fails parameter validation before any feature runs."
        ),
        leakage_note=(
            "Reads the scored transaction and a static pack fact. No window, no history. The pack "
            "value is a property of a currency, not a calibrated simulation choice, so it does not "
            "vary with the dataset the model was trained on."
        ),
        template_id="amount.round_sum_flag",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="just_below_limit_flag",
        group=Group.AMOUNT_BEHAVIOUR,
        dtype=Dtype.BOOL,
        definition=(
            "True when the amount falls within 5% below any channel limit or KYC-tier limit in "
            "force AT THE TRANSACTION'S TIMESTAMP (ADR 0026) — the structuring signal. Part E.2's "
            "'active rule threshold' clause is NOT included: see the leakage note."
        ),
        source=Source.DB,
        nan_rule=(
            "Never NaN: with no applicable limit the answer is False, not unknown. An "
            "account whose "
            "tier carries no limit cannot be structuring against one."
        ),
        leakage_note=(
            "RESOLVED BY ADR 0026, PARTIALLY IMPLEMENTABLE. Limits are mutable operational "
            "configuration, so both paths read them as of the transaction timestamp, never as of "
            "today; training on today's threshold against an eight-month-old transaction would "
            "encode a limit that did not exist then. That is future information arriving through "
            "configuration rather than through a window, which is why no window-contract field "
            "catches it and why reference_data_basis exists. The paths diverge only for "
            "transactions older than the last configuration change, so the parity suite detects "
            "this class ONLY if its fixtures contain one — now a required fixture property with an "
            "E12 precondition asserting changes > 0. "
            "NOT IMPLEMENTABLE TODAY: the 'active rule threshold' clause. alert_rule_versions "
            "carries created_at (when the row was written), not effective_at (when the rule became "
            "live), and alert_rules.state and current_version are mutable columns with no "
            "transition history, so 'was this rule enabled at T?' is unanswerable — and a rule "
            "disabled after an incident is exactly the case that matters. Channel and KYC-tier "
            "limits are as-of queryable and are included; the rule clause waits on the M6 "
            "migration ADR 0026 records."
        ),
        template_id="amount.just_below_limit_flag",
        reference_data_basis=ReferenceDataBasis.AS_OF_EVENT,
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Temporal (6). Five read only the scored transaction; seconds_since_last_tx reads history with no
# window bound, which the window field expresses as "unbounded" rather than as absent — the
# contract fields all apply to it, and None would wrongly say "no history at all".
# ---------------------------------------------------------------------------------------------

_LOCAL_TIME_LEAKAGE = (
    "Reads the scored transaction's timestamp and the country pack's UTC offset (D-43). No "
    "history, no window. The path-divergence risk is not leakage but timezone handling: both paths "
    "must derive local time identically, which parity mutation 4 exists to prove."
)
_LOCAL_TIME_NAN = (
    "Never NaN: every pack carries a UTC offset, and a transaction without a timestamp is refused "
    "at ingestion."
)

_UNBOUNDED_FLUSH = (
    " DURABLE: the previous transaction is not recoverable from any rolling window once the gap "
    "exceeds it, so a cache flush would turn a dormant account's genuine long gap into a "
    "no-history NaN, and every reactivation would look like a first transaction."
)

_UNBOUNDED_ACCOUNT_HISTORY = WindowContract(
    self_inclusion=SelfInclusion.EXCLUDED,
    nesting=Nesting.NOT_NESTED,
    smoothing=None,
    history_basis=HistoryBasis.NOT_TIME_NORMALISED,
    history_requirement=HistoryRequirement.DURABLE,
    fallback_behaviour=FallbackBehaviour.EXACT,
    label_basis=LabelBasis.NOT_LABEL_DERIVED,
    minimum_history=MinimumHistory(minimum_observations=1, below_threshold_value=None),
    history_key=HistoryKey.ACCOUNT,
)

for _spec_ in (
    FeatureSpec(
        name="local_hour_sin",
        group=Group.TEMPORAL,
        dtype=Dtype.FLOAT64,
        definition=(
            "sin(2*pi*local_hour/24), so 23:00 and 00:00 are adjacent rather than extremes. "
            "local_hour is the INTEGER local hour 0-23, not a fractional hour: Part E.2's own "
            "examples are clock hours, is_local_night and local_day_of_week are hour and day "
            "quantities, and a fractional hour is a second encoding of a quantity the group "
            "already encodes. Stated here because two implementations would each pick one "
            "plausibly, differently and silently, which is what this registry exists to stop."
        ),
        source=Source.REQUEST,
        nan_rule=_LOCAL_TIME_NAN,
        leakage_note=_LOCAL_TIME_LEAKAGE,
        template_id="temporal.local_hour_sin",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="local_hour_cos",
        group=Group.TEMPORAL,
        dtype=Dtype.FLOAT64,
        definition=(
            "cos(2*pi*local_hour/24), the quadrature partner that makes the pair injective. "
            "Reads the same INTEGER local hour 0-23 as local_hour_sin; the pair must be computed "
            "from one value, or the two together encode a point off the unit circle."
        ),
        source=Source.REQUEST,
        nan_rule=_LOCAL_TIME_NAN,
        leakage_note=_LOCAL_TIME_LEAKAGE,
        template_id="temporal.local_hour_cos",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="local_day_of_week",
        group=Group.TEMPORAL,
        dtype=Dtype.ORDINAL,
        definition="Local day of week, Monday=0, from local time not UTC.",
        source=Source.REQUEST,
        nan_rule=_LOCAL_TIME_NAN,
        leakage_note=_LOCAL_TIME_LEAKAGE,
        template_id="temporal.local_day_of_week",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="is_local_night",
        group=Group.TEMPORAL,
        dtype=Dtype.BOOL,
        definition="True for local times 00:00-04:59 inclusive.",
        source=Source.REQUEST,
        nan_rule=_LOCAL_TIME_NAN,
        leakage_note=_LOCAL_TIME_LEAKAGE,
        template_id="temporal.is_local_night",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="is_month_end_window",
        group=Group.TEMPORAL,
        dtype=Dtype.BOOL,
        definition=(
            "True on the last 3 and first 2 local days of a month, the salary period. Month length "
            "is the local calendar month, so February and 31-day months differ, and the boundary "
            "is the same local-month boundary PB-26 pinned for the partition key."
        ),
        source=Source.REQUEST,
        nan_rule=_LOCAL_TIME_NAN,
        leakage_note=_LOCAL_TIME_LEAKAGE,
        template_id="temporal.is_month_end_window",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="seconds_since_last_tx",
        group=Group.TEMPORAL,
        dtype=Dtype.FLOAT64,
        definition=(
            "Seconds between this account's previous transaction and the scored one, with no "
            "window bound: the previous transaction counts however old it is."
        ),
        source=Source.REDIS,
        nan_rule=(
            "NaN with no prior transaction, per Part E.2. NaN rather than a large sentinel, which "
            "the models would read as an ordinary long gap."
        ),
        leakage_note=(
            _BACKWARD_ONLY + " Unbounded history makes this DURABLE: the previous timestamp is not "
            "recoverable from any rolling window once the gap exceeds it, so a cache flush would "
            "turn a dormant account's genuine 200-day gap into a NaN."
        ),
        template_id="temporal.seconds_since_last_tx",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=_UNBOUNDED_ACCOUNT_HISTORY,
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Geographic (5; geo_cell_fraud_rate_30d registered above).
# ---------------------------------------------------------------------------------------------

_NINETY_DAY_ACCOUNT_LOCATION = WindowContract(
    self_inclusion=SelfInclusion.EXCLUDED,
    nesting=Nesting.NOT_NESTED,
    smoothing=None,
    history_basis=HistoryBasis.NOT_TIME_NORMALISED,
    history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
    fallback_behaviour=FallbackBehaviour.EXACT,
    label_basis=LabelBasis.NOT_LABEL_DERIVED,
    minimum_history=MinimumHistory(minimum_observations=1, below_threshold_value=None),
    history_key=HistoryKey.ACCOUNT,
)

for _spec_ in (
    FeatureSpec(
        name="distance_from_last_tx_km",
        group=Group.GEOGRAPHIC,
        dtype=Dtype.FLOAT64,
        definition=(
            "Great-circle distance from this account's previous transaction location to the scored "
            "one, via the shared haversine primitive."
        ),
        source=Source.REDIS,
        nan_rule="NaN with no prior transaction: there is no origin to measure from.",
        leakage_note=(
            _BACKWARD_ONLY + " Uses haversine_km, a SHARED_PRIMITIVE the parity test cannot see "
            "into, so it carries hand-computed unit tests of its own." + _UNBOUNDED_FLUSH
        ),
        template_id="geographic.distance_from_last_tx_km",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=_UNBOUNDED_ACCOUNT_HISTORY,
    ),
    FeatureSpec(
        name="implied_speed_kmh",
        group=Group.GEOGRAPHIC,
        dtype=Dtype.FLOAT64,
        definition=(
            "distance_from_last_tx_km divided by the elapsed hours since the previous transaction, "
            "capped at 1000 km/h. "
            "WHAT THE CAP MEANS WHEN IT BINDS: not 'fast'. 1000 km/h is above commercial cruising "
            "speed, so a value at the cap says the two locations cannot both have been visited by "
            "one person in that time — a proxy for a shared account, a stolen credential used "
            "elsewhere, or a spoofed location, never for travel. It is a saturating indicator and "
            "the model should read it as a category, which is why it saturates rather than "
            "reporting 3,000 or 40,000 and letting a tree split inside the impossible range. "
            "CORRECTED 2026-09-19 (owner decision): an earlier wording said the cap also defined "
            "a zero-elapsed-time case. It does not, and the case cannot arise. A predecessor is a "
            "row STRICTLY earlier than the scored one — the convention every windowed feature "
            "here uses, and the only one the online path can implement, since at scoring time a "
            "transaction sharing the timestamp may not have arrived — so the elapsed time is "
            "always positive and the cap now applies only to fast-but-positive gaps. See "
            "SIMULTANEOUS_PREDECESSOR_NOTE."
        ),
        source=Source.REDIS,
        nan_rule="NaN with no prior transaction, inherited from both of its inputs.",
        leakage_note=(
            _BACKWARD_ONLY + " The cap is a constant, not a fitted quantity, so it introduces no "
            "dependence on the training data." + _UNBOUNDED_FLUSH
        ),
        template_id="geographic.implied_speed_kmh",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=_UNBOUNDED_ACCOUNT_HISTORY,
        tolerance_note=(
            "Both paths must cap at the same point in the arithmetic. Capping before versus after "
            "the division gives different values for a near-zero denominator, and that is the case "
            "the feature is for."
        ),
    ),
    FeatureSpec(
        name="distance_from_home_centroid_km",
        group=Group.GEOGRAPHIC,
        dtype=Dtype.FLOAT64,
        definition=(
            "Great-circle distance from the component-wise median of this account's prior 90 d of "
            "transaction locations to the scored one. Median, not mean: a single transaction "
            "abroad would drag a mean centroid into the sea between two countries."
        ),
        source=Source.REDIS,
        nan_rule="NaN with no prior transaction in the window: no centroid exists.",
        leakage_note=_BACKWARD_ONLY,
        template_id="geographic.distance_from_home_centroid_km",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="90d",
        contract=_NINETY_DAY_ACCOUNT_LOCATION,
        tolerance_note=(
            "The component-wise median of latitudes and longitudes is not a spherical median and "
            "is meaningless across the antimeridian. Acceptable here because every pack is African "
            "and no simulated corridor crosses it; recorded so the assumption fails loudly rather "
            "than silently if a Pacific pack is ever added."
        ),
    ),
    FeatureSpec(
        name="is_new_country_for_account",
        group=Group.GEOGRAPHIC,
        dtype=Dtype.BOOL,
        definition=(
            "True when this account has never before sent to the scored transaction's COUNTERPARTY "
            "COUNTRY, over unbounded history. Country comes from the pack, never hard-coded "
            "(ADR 0023). "
            "RESOLVED 2026-09-19 (owner decision). Part E.2 says 'the scored transaction's "
            "country', which reads most naturally as where the transaction happened — and that is "
            "not computable here: no column records it, transaction locations are continuous "
            "coordinates, and resolving them would put a geocoder inside the feature path. The "
            "account's own country is computable and never changes in this dataset, so that "
            "reading would have shipped a third declared degeneracy. The counterparty's country is "
            "recorded per row, varies, and makes this the destination-novelty signal that sits "
            "beside corridor_class."
        ),
        source=Source.REDIS,
        nan_rule=(
            "Never NaN: an account with no history is in a new country by definition, which is "
            "True, not unknown."
        ),
        leakage_note=(
            _BACKWARD_ONLY + " Unbounded history makes this DURABLE: the set of countries an "
            "account has transacted in is not recoverable from a rolling window, so a cache flush "
            "would make every country look new and fire this on established accounts."
        ),
        template_id="geographic.is_new_country_for_account",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.DURABLE,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.ACCOUNT,
        ),
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Counterparty (5). Two of these are keyed by the counterparty, and between them they show that
# E1's account grouping is not sufficient for this group — see the cross_account_control text.
# ---------------------------------------------------------------------------------------------

_COMPONENT_FOLDING = (
    "Folds group by connected component of the account-counterparty graph, not by account alone. "
    "Accounts sharing a counterparty are one unit, because the aggregate they read is one object; "
    "grouping by account puts the same mule's sender set on both sides of the split. Detected by "
    "the mutation that folds by account only and asserts the validation AUC rises above its "
    "component-folded value — a rise is the optimism this control removes."
)

for _spec_ in (
    FeatureSpec(
        name="counterparty_is_new_for_account",
        group=Group.COUNTERPARTY,
        dtype=Dtype.BOOL,
        definition=(
            "True when this account has never transacted with this counterparty before, over "
            "unbounded history."
        ),
        source=Source.REDIS,
        nan_rule="Never NaN: no history means the counterparty is new, which is True.",
        leakage_note=(
            _BACKWARD_ONLY + " Keyed by the ACCOUNT despite naming a counterparty: the question is "
            "about this account's own history, so E1's grouping isolates it. Contrast "
            "counterparty_unique_senders_24h, which asks about the counterparty and does not. "
            "DURABLE: the set of counterparties an account has ever paid is not recoverable from "
            "a rolling window, so a cache flush would mark every established payee as new and "
            "fire this feature across the whole population at once."
        ),
        template_id="counterparty.is_new_for_account",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.DURABLE,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.ACCOUNT,
        ),
    ),
    FeatureSpec(
        name="counterparty_account_age_days",
        group=Group.COUNTERPARTY,
        dtype=Dtype.FLOAT64,
        definition=(
            "Days between the counterparty account's opening date and the scored transaction. A "
            "static attribute of the counterparty, not an aggregate over transactions."
        ),
        source=Source.DB,
        nan_rule=(
            "NaN when the counterparty is external to the institution and its opening date is "
            "unknown, which is a genuine absence rather than a zero-age account."
        ),
        leakage_note=(
            "Reads an immutable date, no labels and no aggregate, so nothing travels between "
            "accounts through it and no fold grouping is at stake. It declares no window because "
            "an opening date is a point fact, not a history."
        ),
        template_id="counterparty.account_age_days",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="counterparty_unique_senders_24h",
        group=Group.COUNTERPARTY,
        dtype=Dtype.INT64,
        definition=(
            "Distinct accounts that sent to this counterparty in the trailing 24 h — the mule "
            "signal, since a legitimate recipient rarely acquires many unrelated senders at once."
        ),
        source=Source.REDIS,
        nan_rule="Never NaN: no senders in the window is 0, not unknown.",
        leakage_note=(
            "Keyed by the COUNTERPARTY, so E1's account-grouped folds do not isolate it. A ring "
            "moving money from ten victims into one mule produces ten rows reading one aggregate; "
            "split those accounts across folds and validation rows sit inside the feature training "
            "rows saw. Carries no label, so this is optimism in the validation estimate rather "
            "than label leakage — but the estimate is what every model decision is made on."
        ),
        template_id="counterparty.unique_senders_24h",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="24h",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.COUNTERPARTY,
            cross_account_control=_COMPONENT_FOLDING,
        ),
        tolerance_note=(
            "Exact set cardinality, never a sketch, for the reason given on "
            "unique_counterparties_24h: a mule's sender count is decisive at exactly the burst "
            "volumes where a HyperLogLog's error stops being negligible."
        ),
    ),
    FeatureSpec(
        name="counterparty_confirmed_fraud_90d",
        group=Group.COUNTERPARTY,
        dtype=Dtype.INT64,
        definition=(
            "Confirmed-fraud transactions involving this counterparty in the prior 90 d, counting "
            "only labels whose label_available_at precedes the scored transaction."
        ),
        source=Source.DB,
        nan_rule="Never NaN: no confirmed fraud in the window is 0, not unknown.",
        leakage_note=(
            "The most dangerous feature in the catalogue: label-derived AND counterparty-keyed, so "
            "it carries both failure modes at once. Labels are filtered on label_available_at, "
            "never confirmed_at. Being keyed by the counterparty, a validation account's confirmed "
            "fraud would otherwise enter the count a training row reads — the geo_cell defect in "
            "another location, and here it is labels crossing the fold, not merely optimism. "
            "Subject to the D-08 single-feature AUC ceiling at a stated scale (E2)."
        ),
        template_id="counterparty.confirmed_fraud_90d",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="90d",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.AVAILABLE_AT_LAG,
            minimum_history=None,
            history_key=HistoryKey.COUNTERPARTY,
            cross_account_control=(
                "Counts are computed from training-fold rows only, with folds grouped by connected "
                "component of the account-counterparty graph. Detected by the mutation that counts "
                "over all rows and asserts the single-feature AUC rises above its clean value, "
                "which is this leak's only observable signature."
            ),
        ),
    ),
    FeatureSpec(
        name="tx_count_to_counterparty_30d",
        group=Group.COUNTERPARTY,
        dtype=Dtype.INT64,
        definition=(
            "Transactions from this account to this counterparty in the trailing 30 d — an "
            "established-relationship signal, low for a first transfer to a new payee."
        ),
        source=Source.REDIS,
        nan_rule="Never NaN: no prior transactions to this counterparty is 0, not unknown.",
        leakage_note=(
            _BACKWARD_ONLY + " Keyed by the ACCOUNT: the aggregate is scoped to this account's "
            "transactions to one counterparty, so it contains no other account's rows and E1's "
            "grouping isolates it. The (account, counterparty) pair is narrower than the account, "
            "never wider, which is what makes account grouping sufficient here."
        ),
        template_id="counterparty.tx_count_to_counterparty_30d",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="30d",
        contract=_TRAILING_ACCOUNT_AGGREGATE,
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Device and channel (5). Four are NaN for a null fingerprint (D-04), which USSD always has.
# ---------------------------------------------------------------------------------------------

_NULL_FINGERPRINT_NAN = (
    "NaN for a null device fingerprint, which every USSD transaction has. Structural NaN per D-04: "
    "exactly four device features are NaN together, and the parity suite asserts NaN POSITIONS "
    "match rather than absorbing them into a tolerance."
)

for _spec_ in (
    FeatureSpec(
        name="channel",
        group=Group.DEVICE_AND_CHANNEL,
        dtype=Dtype.CATEGORICAL,
        definition=(
            "The transaction's channel, passed to native categorical handling rather than "
            "one-hot encoded: MOBILE_MONEY, CARD, AGENT_BANKING, USSD, ONLINE, BANK_TRANSFER."
        ),
        source=Source.REQUEST,
        nan_rule="Never NaN: the channel is mandatory and constrained at ingestion.",
        leakage_note=(
            "Reads the scored transaction only. Categorical, so ADR 0025 requires EXACT equality "
            "across paths — a category encoded from a different fold is parity mutation 6, which "
            "is M2's encoding defect reproduced in serving."
        ),
        template_id="device.channel",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        categories=(
            "MOBILE_MONEY",
            "CARD",
            "AGENT_BANKING",
            "USSD",
            "ONLINE",
            "BANK_TRANSFER",
        ),
    ),
    FeatureSpec(
        name="device_is_new_for_account",
        group=Group.DEVICE_AND_CHANNEL,
        dtype=Dtype.BOOL,
        definition="True when this account has not used this device fingerprint before.",
        source=Source.REDIS,
        nan_rule=_NULL_FINGERPRINT_NAN,
        leakage_note=(
            _BACKWARD_ONLY + " Keyed by the ACCOUNT: it asks about this account's own devices, so "
            "E1's grouping isolates it, unlike accounts_per_device_7d below. DURABLE: the set of "
            "devices an account has used is not recoverable from a rolling window, so a cache "
            "flush would mark every familiar handset as new — firing the feature on the entire "
            "population simultaneously, which is indistinguishable from a mass device-swap attack."
        ),
        template_id="device.is_new_for_account",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.DURABLE,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.ACCOUNT,
        ),
    ),
    FeatureSpec(
        name="accounts_per_device_7d",
        group=Group.DEVICE_AND_CHANNEL,
        dtype=Dtype.INT64,
        definition=(
            "Distinct accounts that transacted from this device fingerprint in the trailing 7 d — "
            "the device-sharing signal behind synthetic-identity rings."
        ),
        source=Source.REDIS,
        nan_rule=_NULL_FINGERPRINT_NAN,
        leakage_note=(
            "Keyed by the DEVICE, and cross-account aggregation IS the signal: a device shared by "
            "many accounts is the entire point, so there is no account-scoped version of this "
            "feature to retreat to. Restricting the count to training-fold rows would not leak, "
            "but would make the training-time value systematically lower than the serving-time "
            "value by a factor of the fold ratio — replacing leakage with training/serving skew, "
            "which is the defect the parity suite exists to prevent. Component folding resolves "
            "it: see cross_account_control."
        ),
        template_id="device.accounts_per_device_7d",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="7d",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.NAN_UNDER_FALLBACK,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.DEVICE,
            cross_account_control=(
                "Folds group by connected component of the account-device graph, so every account "
                "sharing a device lands in one fold and the count over all rows within that fold "
                "is both complete and leak-free — no training-fold restriction is needed, so no "
                "skew is introduced. Detected by the mutation that folds by account only and "
                "asserts the validation AUC rises above its component-folded value."
            ),
        ),
        tolerance_note=(
            "NAN_UNDER_FALLBACK, and the only feature so far that earns it: cross-account device "
            "state exists solely in the online store. M1 built account_activity_hourly and "
            "merchant_activity_15m and no device aggregate at all, so the database genuinely "
            "cannot answer this question — as opposed to answering it inconveniently."
        ),
        degeneracy=(
            "DEGENERATE ON THE CURRENT DATASET (PB-40). Measured at tree d85385f: 5,484 distinct "
            "device fingerprints across 5,920 accounts, and zero used by more than one account "
            "(max accounts per device: 1). The feature is therefore identically 1 and has no "
            "variance. The generator has no device-sharing mechanism; this is a data gap, not a "
            "feature defect, and the feature ships computing correctly over data that does not "
            "exercise it. Cleared when the generator shares devices between accounts, scheduled "
            "before M4 training so the feature is non-degenerate when the model using it is fitted."
        ),
    ),
    FeatureSpec(
        name="device_changes_24h",
        group=Group.DEVICE_AND_CHANNEL,
        dtype=Dtype.INT64,
        definition=(
            "Distinct device fingerprints this account transacted from in the trailing 24 h, minus "
            "one, so a single consistent device scores 0."
        ),
        source=Source.REDIS,
        nan_rule=_NULL_FINGERPRINT_NAN,
        leakage_note=_BACKWARD_ONLY + " Keyed by the ACCOUNT.",
        template_id="device.device_changes_24h",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="24h",
        contract=_TRAILING_ACCOUNT_AGGREGATE,
    ),
    FeatureSpec(
        name="device_age_days",
        group=Group.DEVICE_AND_CHANNEL,
        dtype=Dtype.FLOAT64,
        definition=(
            "Days since this device fingerprint was first seen anywhere in the institution, not "
            "merely on this account: a device first seen an hour ago is the signal, and scoping it "
            "to the account would make every device new on its first use there."
        ),
        source=Source.REDIS,
        nan_rule=_NULL_FINGERPRINT_NAN,
        leakage_note=(
            "Keyed by the DEVICE for the reason in the definition, so E1's grouping does not "
            "isolate it. Carries no label, so the exposure is validation optimism rather than "
            "label leakage. DURABLE: a device's first-seen date is not recoverable from a rolling "
            "window, and a flush would reset every device to newborn — firing this feature on the "
            "entire population at once."
        ),
        template_id="device.device_age_days",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="unbounded",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.DURABLE,
            fallback_behaviour=FallbackBehaviour.NAN_UNDER_FALLBACK,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.DEVICE,
            cross_account_control=(
                "Folds group by connected component of the account-device graph, as for "
                "accounts_per_device_7d. Detected by the same mutation: fold by account only and "
                "assert the validation AUC rises."
            ),
        ),
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)


# ---------------------------------------------------------------------------------------------
# Account profile (4).
# ---------------------------------------------------------------------------------------------

_AGENT_NAN = (
    "NaN outside AGENT_BANKING. Structural NaN per D-04: exactly four agent features are NaN "
    "together, and the parity suite asserts NaN positions match."
)

for _spec_ in (
    FeatureSpec(
        name="account_age_days",
        group=Group.ACCOUNT_PROFILE,
        dtype=Dtype.FLOAT64,
        definition="Days between the account's opening date and the scored transaction.",
        source=Source.DB,
        nan_rule="Never NaN for an institution account: the opening date is mandatory.",
        leakage_note=(
            "An immutable date, no aggregate, no labels. Blocked in practice by PB-37: M1 has no "
            "per-account table, so there is nowhere the opening date lives. Distinct from the "
            "first-seen timestamp velocity_ratio_1h_vs_30d needs — an account may be opened long "
            "before its first transaction — and the M6 migration must carry both."
        ),
        template_id="profile.account_age_days",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
    ),
    FeatureSpec(
        name="kyc_tier",
        group=Group.ACCOUNT_PROFILE,
        dtype=Dtype.ORDINAL,
        definition=(
            "The account's KYC tier IN FORCE AT THE TRANSACTION'S TIMESTAMP (ADR 0026), as an "
            "ordinal. Tiers are upgraded over time, so today's tier is not the one the transaction "
            "was subject to — and the upgrade often follows the very activity being scored."
        ),
        source=Source.DB,
        nan_rule="Never NaN: every account holds a tier from opening.",
        leakage_note=(
            "The second feature reading mutable state as-of the event, and the one that shows "
            "reference_data_basis is not a one-feature field. Reading the current tier for an "
            "eight-month-old transaction imports an upgrade that had not happened — and since "
            "tier upgrades are frequently triggered by investigation, that upgrade can be a direct "
            "consequence of the fraud being scored. Ordinal, so ADR 0025 requires exact equality."
        ),
        template_id="profile.kyc_tier",
        reference_data_basis=ReferenceDataBasis.AS_OF_EVENT,
    ),
    FeatureSpec(
        name="days_since_sim_swap",
        group=Group.ACCOUNT_PROFILE,
        dtype=Dtype.FLOAT64,
        definition=(
            "Days since the most recent SIM swap on the account's registered MSISDN, from the MNO "
            "signal, counting only swaps before the scored transaction."
        ),
        source=Source.DB,
        nan_rule=(
            "NaN when the MNO signal is unavailable, per Part E.2, and ALSO NaN when it is "
            "available and reports no swap — both are genuine absences of a date. Distinguishing "
            "them would need a second feature; conflating them into a large number would let the "
            "models read 'no swap ever' as 'a swap long ago'."
        ),
        leakage_note=(
            _BACKWARD_ONLY + " The swap must be filtered to those occurring before the scored "
            "transaction: an MNO feed delivered in bulk would otherwise include swaps that had not "
            "happened yet, which is label_available_at's problem in a non-label substrate."
        ),
        template_id="profile.days_since_sim_swap",
        reference_data_basis=ReferenceDataBasis.AS_OF_EVENT,
    ),
    FeatureSpec(
        name="dormancy_reactivation_flag",
        group=Group.ACCOUNT_PROFILE,
        dtype=Dtype.BOOL,
        definition=(
            "True when this account had no transaction in the 60 d before the scored one, having "
            "had at least one before that — reactivation after dormancy, not a new account."
        ),
        source=Source.REDIS,
        nan_rule=(
            "Never NaN. An account with no history at all is False, not True: it is new, not "
            "reactivated, and the minimum_history of one prior transaction is what separates them."
        ),
        leakage_note=(
            _BACKWARD_ONLY
            + " DURABLE because of the 'had at least one before that' clause: a 60 d "
            "window cannot tell a dormant account from a new one, so a cache flush would turn "
            "every genuine reactivation into a False."
        ),
        template_id="profile.dormancy_reactivation_flag",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="60d",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.DURABLE,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=MinimumHistory(minimum_observations=1, below_threshold_value=0.0),
            history_key=HistoryKey.ACCOUNT,
        ),
    ),
    # -----------------------------------------------------------------------------------------
    # Agent-specific (4). Two are keyed by the AGENT, which is a third cross-account key.
    # -----------------------------------------------------------------------------------------
    FeatureSpec(
        name="agent_float_utilisation_ratio",
        group=Group.AGENT,
        dtype=Dtype.FLOAT64,
        definition=(
            "The agent's float drawn down as a fraction of its float limit, both as they stood at "
            "the transaction's timestamp (ADR 0026)."
        ),
        source=Source.DB,
        nan_rule=_AGENT_NAN,
        leakage_note=(
            "Float balance and limit are both mutable, so AS_OF_EVENT: reading today's limit for "
            "an old transaction imports a limit change that may itself have followed the incident."
        ),
        template_id="agent.float_utilisation_ratio",
        reference_data_basis=ReferenceDataBasis.AS_OF_EVENT,
    ),
    FeatureSpec(
        name="agent_cashout_count_1h",
        group=Group.AGENT,
        dtype=Dtype.INT64,
        definition="Cash-out transactions at this agent in the trailing 1 h.",
        source=Source.REDIS,
        nan_rule=_AGENT_NAN,
        leakage_note=(
            "Keyed by the AGENT, so E1's account grouping does not isolate it: customers of one "
            "agent read one aggregate."
        ),
        template_id="agent.cashout_count_1h",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="1h",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.AGENT,
            cross_account_control=(
                "Folds group by connected component of the account-agent graph. Detected by the "
                "mutation that folds by account only and asserts the validation AUC rises."
            ),
        ),
    ),
    FeatureSpec(
        name="agent_unique_customers_1h",
        group=Group.AGENT,
        dtype=Dtype.INT64,
        definition="Distinct accounts transacting at this agent in the trailing 1 h.",
        source=Source.REDIS,
        nan_rule=_AGENT_NAN,
        leakage_note=(
            "Keyed by the AGENT, and like accounts_per_device_7d the cross-account count IS the "
            "signal, so component folding rather than a training-fold restriction is the control."
        ),
        template_id="agent.unique_customers_1h",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="1h",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
            fallback_behaviour=FallbackBehaviour.EXACT,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.AGENT,
            cross_account_control=(
                "Folds group by connected component of the account-agent graph. Detected by the "
                "mutation that folds by account only and asserts the validation AUC rises."
            ),
        ),
        tolerance_note="Exact set cardinality, never a sketch, as for the other distinct counts.",
    ),
    FeatureSpec(
        name="agent_distance_from_registered_km",
        group=Group.AGENT,
        dtype=Dtype.FLOAT64,
        definition=(
            "Great-circle distance from the agent's registered premises to the transaction "
            "location, via the shared haversine primitive — an agent transacting far from its "
            "registered site."
        ),
        source=Source.DB,
        nan_rule=_AGENT_NAN,
        leakage_note=(
            "The registered location is mutable operational data, read as of the transaction: an "
            "agent that relocated after an incident would otherwise appear to have been at its new "
            "premises all along."
        ),
        template_id="agent.distance_from_registered_km",
        reference_data_basis=ReferenceDataBasis.AS_OF_EVENT,
    ),
    # -----------------------------------------------------------------------------------------
    # Corridor (1) and synthetic identity (1).
    # -----------------------------------------------------------------------------------------
    FeatureSpec(
        name="corridor_class",
        group=Group.CORRIDOR,
        dtype=Dtype.CATEGORICAL,
        definition=(
            "DOMESTIC | INTRA_BLOC | CROSS_BLOC_AFRICA | INTERCONTINENTAL, from the sender's and "
            "recipient's country packs and their bloc memberships (EAC, ECOWAS, SADC, COMESA, "
            "CEMAC, AMU). A country may hold several memberships; INTRA_BLOC means the pair shares "
            "at least one."
        ),
        source=Source.REQUEST,
        nan_rule=(
            "Never NaN: both countries are known at scoring time and every pack declares its "
            "blocs, a pack without them failing parameter validation before any feature runs."
        ),
        leakage_note=(
            "DEVIATION from Part E.2, recorded (ADR 0023, and FR-02-02's register row). E.2 "
            "specifies DOMESTIC | EAC_CROSS_BORDER | NON_EAC_CROSS_BORDER; this generalises to "
            "blocs so that no EAC special case survives in feature code and a new country is a "
            "new pack file rather than a code change. The feature keeps its slot and the count "
            "stays 44. Reads static pack facts and the scored transaction only — no history, no "
            "labels. Bloc memberships are SOURCED with an as-of date; a membership change is a "
            "pack edit, which is why this is NOT_REFERENCE_DATA rather than AS_OF_EVENT: packs are "
            "version-controlled parameters, not mutable operational configuration."
        ),
        template_id="corridor.corridor_class",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        categories=("DOMESTIC", "INTRA_BLOC", "CROSS_BLOC_AFRICA", "INTERCONTINENTAL"),
        degeneracy=(
            "TWO OF FOUR CLASSES ARE UNREACHABLE ON THE CURRENT DATASET (PB-43). Every simulated "
            "country is in the EAC and on the same continent, and behaviour.remittance_corridors "
            "sends every cross-border transfer to another simulated country, so a generated row "
            "is DOMESTIC or INTRA_BLOC and never CROSS_BLOC_AFRICA or INTERCONTINENTAL. The "
            "feature is correct and its four-way rule is tested; what the dataset cannot do is "
            "exercise half of it, so a model fitted on this benchmark learns nothing about the "
            "two absent classes and an encoder fitted on it has no cell for them. Unlike PB-40 "
            "this is not a generator defect: ADR 0023's owner direction is explicitly that the "
            "simulated country set is NOT to be broadened. Cleared only by a deliberate decision "
            "to simulate a corridor leaving the validated core, which is a dataset-draw change "
            "and an owner's call."
        ),
    ),
    FeatureSpec(
        name="synthetic_identity_score",
        group=Group.SYNTHETIC_IDENTITY,
        dtype=Dtype.FLOAT64,
        definition=(
            "A deterministic, documented composite in [0, 1] of low KYC tier, account age under "
            "30 d, shared device and phone attributes across accounts, and rapid volume ramp. "
            "Computed WITHOUT labels, per Part E.2 — it is a hand-specified heuristic, not a "
            "sub-model, so it carries no fitted quantity and no fold dependence of its own."
        ),
        source=Source.REDIS,
        nan_rule=(
            "Never NaN: each component has a defined value for every account, with the "
            "device-sharing component contributing zero for a null fingerprint rather than "
            "propagating NaN — otherwise every USSD transaction would lose this feature too, and "
            "D-04 fixes the NaN count at four device features, not five."
        ),
        leakage_note=(
            "Keyed by the ACCOUNT overall, but its device-sharing component reads cross-account "
            "device state, so it inherits accounts_per_device_7d's exposure at reduced weight. "
            "Declared ACCOUNT with that stated here rather than DEVICE, because the composite is "
            "an account-level score; the component-folded grouping the device features require "
            "covers it, and this note exists so that a reader does not conclude the composite is "
            "free of cross-account content. Carries no labels, so the D-08 ceiling applies as an "
            "AUC check rather than as a leakage concern. DURABLE: the account-age and "
            "device-sharing components both need state a cache flush would destroy, and because "
            "this is a composite the damage is silent — the score stays in [0, 1] and merely "
            "means something different, with no NaN to signal it."
        ),
        template_id="synthetic.identity_score",
        reference_data_basis=ReferenceDataBasis.NOT_REFERENCE_DATA,
        window="30d",
        contract=WindowContract(
            self_inclusion=SelfInclusion.EXCLUDED,
            nesting=Nesting.NOT_NESTED,
            smoothing=None,
            history_basis=HistoryBasis.NOT_TIME_NORMALISED,
            history_requirement=HistoryRequirement.DURABLE,
            fallback_behaviour=FallbackBehaviour.NAN_UNDER_FALLBACK,
            label_basis=LabelBasis.NOT_LABEL_DERIVED,
            minimum_history=None,
            history_key=HistoryKey.ACCOUNT,
        ),
        degeneracy=(
            "PARTIALLY DEGENERATE ON THE CURRENT DATASET (PB-40). Part E.2 defines this composite "
            "over four terms, one being 'shared device and phone attributes across accounts'. "
            "No device in the dataset is shared, so that term contributes a constant and the score "
            "is effectively a composite of three terms, not four. The score remains in [0, 1] and "
            "emits no NaN, so nothing downstream signals the loss — which is why it is declared "
            "here. Cleared with accounts_per_device_7d, before M4 training."
        ),
    ),
):
    REGISTRY[_spec_.name] = _spec_

validate(REGISTRY)
