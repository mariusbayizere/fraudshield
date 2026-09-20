"""Pure data records shared by both feature paths.

Both `batch` and `online` import this, for the same reason both import `registry`: it **computes
nothing**. A record with no behaviour cannot carry a bug for the parity test to be blind to, which
is the property parity Decision 4 actually requires — the rule is not "share nothing", it is "share
nothing that could be wrong in the same way twice".
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum


@dataclass(frozen=True)
class Transaction:
    """One transaction, as both paths see it.

    ``timestamp`` is timezone-aware UTC. Local time is derived from the country pack's offset
    (D-43) and is deliberately not stored here: storing both invites the two paths to disagree
    about which is authoritative, which is parity mutation 4.
    """

    transaction_id: str
    account_id: str
    timestamp: datetime
    amount_rwf: float
    latitude: float
    longitude: float
    #: The sending account's country and the recipient's, as ISO 3166-1 alpha-2 codes. Both are
    #: request-time inputs: ``corridor_class`` declares ``source=REQUEST``, and the recipient's
    #: country is a column of the dataset's ``transactions`` table while the sender's is an
    #: attribute of the account. ``None`` means the caller did not supply it, which is refused by
    #: the features that need it rather than filled in with a default — a guessed country would
    #: silently reclassify a corridor.
    account_country: str | None = None
    counterparty_country: str | None = None
    #: The counterparty's token, as the dataset's ``transactions`` table carries it. ``None`` means
    #: the caller did not supply it; the counterparty features refuse rather than treating every
    #: such row as the same unknown counterparty, which would make them agree with each other and
    #: manufacture a relationship that does not exist.
    counterparty_id: str | None = None
    #: The amount in the transaction's own currency, **in minor units, as an integer**.
    #: `round_sum_flag` asks whether an amount is an exact multiple of a denomination, and "exact"
    #: is not a property a binary float has: 0.1 + 0.2 is not 0.3, and a flag that answered
    #: "almost" would be a different feature. Minor units are what the pack's
    #: `currency_minor_units` exists to define, so the conversion is the caller's, once, at
    #: ingestion, rather than the feature's on every row.
    amount_minor: int | None = None
    #: ISO 4217 code of `amount_minor`'s currency. No default: a default would name a currency in
    #: code, which ADR 0023 forbids, and would silently price a transaction in the wrong one.
    currency: str | None = None
    #: The channel, one of the values `registry.categories_for("channel")` declares.
    channel: str | None = None
    #: The device fingerprint, or ``None`` when the channel has no device — which every USSD
    #: transaction does. ``None`` here is **data**, not a missing input: it is the structural NaN
    #: D-04 fixes at exactly four device features, so it must not be confused with a caller
    #: forgetting to supply a value. That is why the device features return NaN for it rather than
    #: raising, while a missing counterparty or currency raises.
    device_fingerprint: str | None = None
    #: The agent's token, or ``None`` when the transaction was not at an agent. Like a null device
    #: fingerprint this is **data**: D-04 fixes exactly four agent features as NaN together
    #: outside ``AGENT_BANKING``, so the agent features return NaN for it rather than raising.
    agent_id: str | None = None
    #: The merchant category code. ``agent_cashout_count_1h`` needs it to tell a cash disbursement
    #: from any other agent transaction, and the codes are reference data the caller supplies
    #: rather than literals here.
    merchant_category_code: str | None = None

    def __post_init__(self) -> None:
        if self.timestamp.tzinfo is None:
            raise ValueError(
                f"{self.transaction_id}: timestamp must be timezone-aware; a naive timestamp is "
                "the ambiguity D-43 and PB-26 both exist to remove"
            )


@dataclass(frozen=True)
class Outcome:
    """A confirmed label and the moment it became usable.

    ``available_at`` is **not** ``confirmed_at``. Part E.2 allows a feature to see a label only
    once it was available, and a batch path filtering on confirmation time would import the
    investigation delay — the asymmetry ``label_basis`` declares in the registry.
    """

    transaction_id: str
    is_fraud: bool
    available_at: datetime


@dataclass(frozen=True)
class CountryFacts:
    """The country-pack facts the feature paths read, as data and nothing else.

    Supplied by the caller, exactly as ``rates_to_rwf`` is supplied to ``fx_to_rwf`` and the
    fitted ``prior`` to the cell rate. The feature path does not read YAML and holds no table of
    countries: ADR 0023 requires that adding a country is adding a pack file, so a country the
    feature code knows about by name would be that decision violated. ``test_corridor`` proves it
    behaviourally with an invented country and an invented bloc, rather than by lint.

    Deliberately **no** ``shares_a_bloc_with`` method, though it would be one line. Both paths
    import this module, so a predicate here would be shared logic that the parity test is blind to
    by construction (Decision 4) — and "do these two countries share a bloc" is precisely the
    question ``corridor_class`` exists to answer. Each path intersects the sets for itself.
    """

    alpha2: str
    #: Continent code from the pack. ``corridor_class`` compares two countries' codes for equality
    #: and never tests one against a literal, so no continent is named in feature code either.
    continent: str
    blocs: frozenset[str]
    #: Hours ahead of UTC, from the pack's ``utc_offset_hours``. Local time is **derived** rather
    #: than stored on the transaction (D-43): storing both invites the two paths to disagree about
    #: which is authoritative, which is parity mutation 4. No default, because a default is a
    #: timezone named in code and would silently move every temporal feature by a few hours.
    utc_offset_hours: int


class LimitDimension(Enum):
    """What an operational limit is keyed by (ADR 0026).

    ``ACTIVE_RULE_THRESHOLD`` is listed and **is not implementable**, which is why it is listed.
    Part E.2 includes "active rule threshold" in `just_below_limit_flag`, and ADR 0026 checked M1's
    schema and found it cannot answer it as-of: `alert_rule_versions` carries only `created_at` —
    when the rule was *written*, not when it became *effective* — and `alert_rules.state` is a
    mutable column with no transition history, so "was this rule enabled at T?" is unanswerable.
    A rule disabled after an incident is exactly the case that matters.

    Leaving the value out of the enum would make the omission invisible; including it and refusing
    it makes a caller who supplies one get an error naming the schema gap, rather than a flag
    quietly computed over the two dimensions that do work.
    """

    CHANNEL = "channel"
    KYC_TIER = "kyc_tier"
    ACTIVE_RULE_THRESHOLD = "active_rule_threshold"


@dataclass(frozen=True)
class OperationalLimit:
    """A configured limit and the moment it took effect.

    ``effective_at`` is the field ADR 0026 is about. The batch path joins a configuration table and
    naturally sees **today's** limits; the online path sees what was live. Training on today's
    threshold against an eight-month-old transaction encodes a limit that did not exist when it
    happened — future information arriving through configuration rather than through a window,
    which no window-contract field catches because there is no window to get wrong.

    The limit is held in the base currency, like every other amount the features compare, so that
    a cross-currency transaction is tested against the limit it was actually subject to rather than
    against a number that happens to share its magnitude.
    """

    dimension: LimitDimension
    #: The channel name, or the KYC tier as its ordinal in string form.
    applies_to: str
    amount_rwf: float
    effective_at: datetime

    def __post_init__(self) -> None:
        if self.effective_at.tzinfo is None:
            raise ValueError(
                f"{self.dimension.value}/{self.applies_to}: effective_at must be timezone-aware; "
                "an as-of comparison against a naive timestamp is the ambiguity ADR 0026 removes"
            )
        if self.amount_rwf <= 0.0:
            raise ValueError(
                f"{self.dimension.value}/{self.applies_to}: a limit of {self.amount_rwf} is not a "
                "limit; a band below it would cover every amount or none"
            )


@dataclass(frozen=True)
class TierAssignment:
    """A KYC tier and the moment it took effect (ADR 0026).

    A separate record from ``OperationalLimit`` although both are as-of lookups, because they are
    as-of over different things: a limit is institution-wide configuration, a tier is an attribute
    of one account. Merging them would mean one of the two carried a field the other never uses,
    and the join a caller has to get right is different in each case.

    The asymmetry ADR 0026 is about bites hardest here. Tier upgrades are frequently triggered by
    investigation, so the current tier of an account that was investigated **is a consequence of
    the fraud being scored**. Reading it for an eight-month-old transaction is not merely
    anachronistic, it is the label arriving through a profile column.
    """

    tier: int
    effective_at: datetime

    def __post_init__(self) -> None:
        if self.effective_at.tzinfo is None:
            raise ValueError(
                f"tier {self.tier}: effective_at must be timezone-aware; an as-of comparison "
                "against a naive timestamp is the ambiguity ADR 0026 removes"
            )


@dataclass(frozen=True)
class AgentStanding:
    """An agent's float, limit and registered premises as they stood at a moment (ADR 0026).

    Every field here is mutable operational data, which is why the whole record carries an
    ``effective_at`` rather than being a static attribute like an account's opening date. The
    registered location is the one that catches people out: an agent that relocated after an
    incident would otherwise appear to have been at its new premises all along, and
    ``agent_distance_from_registered_km`` would report a short distance for exactly the
    transactions that were far from where the agent actually was.
    """

    float_balance_rwf: float
    float_limit_rwf: float
    latitude: float
    longitude: float
    effective_at: datetime

    def __post_init__(self) -> None:
        if self.effective_at.tzinfo is None:
            raise ValueError("AgentStanding.effective_at must be timezone-aware (ADR 0026)")
        if self.float_limit_rwf <= 0.0:
            raise ValueError(
                f"float_limit_rwf is {self.float_limit_rwf}; a utilisation ratio against a "
                "non-positive limit is not a fraction of anything"
            )


@dataclass(frozen=True)
class IdentityEvidence:
    """The non-transactional inputs `synthetic_identity_score` reads, as one record.

    Grouped rather than passed as four arguments because they are one thing: what is known about
    this account that is not in its transaction history. Each is allowed to be absent, and an
    absent one contributes **zero** to the composite rather than NaN — the composite accumulates
    evidence, so missing evidence adds none. The consequence is worth stating where a caller will
    see it: a score of 0 means "no evidence", not "checked and clean".

    ``accounts_on_device`` is the value of ``accounts_per_device_7d``, passed in rather than
    recomputed. The registry already records that this composite "inherits accounts_per_device_7d's
    exposure at reduced weight", so they are the same cross-account read and computing it twice
    would give two places for it to be wrong.
    """

    #: The KYC tier in force at the transaction, or NaN when it is unknown.
    kyc_tier: float
    #: The lowest and highest tiers the country pack declares (ADR 0023: never a literal in code).
    kyc_tier_range: tuple[int, int]
    #: The account's opening date, or None when the institution has no record of it (PB-37).
    opened_at: datetime | None
    #: ``accounts_per_device_7d`` for this transaction, NaN for a null fingerprint.
    accounts_on_device: float
