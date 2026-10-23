"""Reference data the store and the scorer read: country packs, denominations and fixed priors.

Read from the published `packs.json`, the same interchange file the feature pipeline reads. The
readers repeat `fraudshield_ml.cli`'s rather than importing it, because that module imports
pyarrow at load and every scoring process would carry it for four small JSON lookups;
`test_the_readers_agree_with_the_pipeline_s` holds the two equal on the same file.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

from fraudshield_ml.features.types import CountryFacts

#: The merchant category code for a cash disbursement, which the agent cash-out count reads.
CASH_DISBURSEMENT_MCC = "6011"
#: The cell rate's shrinkage prior, fitted on training rows; the value the M4 cache was built with.
CELL_RATE_PRIOR = 0.0087


class ReferenceDataError(ValueError):
    """Reference data that cannot place a transaction: an unknown or ambiguous currency."""


@dataclass(frozen=True)
class Reference:
    countries: Mapping[str, CountryFacts]
    #: ISO 4217 code -> the country whose pack declares it. Unique by construction (see below).
    country_of_currency: Mapping[str, str]
    minor_units: Mapping[str, int]
    denominations: Mapping[str, tuple[int, ...]]
    cash_out_codes: frozenset[str] = frozenset({CASH_DISBURSEMENT_MCC})
    cell_rate_prior: float = CELL_RATE_PRIOR
    kyc_tier_range: tuple[int, int] = (1, 3)
    #: Anything else a deployment records beside the packs, for the manifest.
    notes: Mapping[str, str] = field(default_factory=dict)

    @staticmethod
    def from_packs(path: Path) -> Reference:
        facts = json.loads(path.read_text(encoding="utf-8"))
        countries: dict[str, CountryFacts] = {}
        country_of: dict[str, str] = {}
        minor: dict[str, int] = {}
        steps: dict[str, tuple[int, ...]] = {}
        for code, entry in sorted(facts.items()):
            countries[code] = CountryFacts(
                alpha2=str(entry["alpha2"]),
                continent=str(entry["continent"]),
                blocs=frozenset(str(b) for b in entry["blocs"]),
                utc_offset_hours=int(entry["utc_offset_hours"]),
            )
            currency = str(entry["currency"])
            if currency in country_of:
                # Training recovered the sender's country from the currency (cli.country_by_
                # currency); two packs sharing one makes that ambiguous, and the scorer must
                # refuse where the pipeline refused.
                raise ReferenceDataError(
                    f"{country_of[currency]} and {code} share {currency!r}, so a transaction's "
                    "country cannot be recovered from its currency"
                )
            country_of[currency] = code
            minor[currency] = int(entry["currency_minor_units"])
            steps[currency] = tuple(int(v) for v in entry["round_denominations"])
        return Reference(
            countries=countries,
            country_of_currency=country_of,
            minor_units=minor,
            denominations=steps,
        )

    def account_country(self, currency: str) -> str:
        try:
            return self.country_of_currency[currency]
        except KeyError:
            raise ReferenceDataError(
                f"no country pack declares {currency!r}, so the transaction's country, local "
                "time and corridor are unknown"
            ) from None
