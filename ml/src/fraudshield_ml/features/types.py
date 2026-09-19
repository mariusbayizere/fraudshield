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
