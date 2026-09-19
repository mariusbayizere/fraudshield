"""Pure data records shared by both feature paths.

Both `batch` and `online` import this, for the same reason both import `registry`: it **computes
nothing**. A record with no behaviour cannot carry a bug for the parity test to be blind to, which
is the property parity Decision 4 actually requires — the rule is not "share nothing", it is "share
nothing that could be wrong in the same way twice".
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime


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
