"""Country packs: every country-specific fact, and nothing country-specific outside them.

M2 hard-coded five East African countries in several places — a `COUNTRIES` tuple, a currency
minor-unit table, country-keyed blocks in `currencies.yaml` — so a reader could reasonably ask
whether the work generalises or whether East Africa was baked into it (ADR 0023).

A pack is `dataset/generator/params/countries/<alpha-2>.yaml`, carrying the same provenance rules as
every other parameter. Adding a country is adding a file; if it ever requires touching code, ADR
0023 has been violated and the Country Z test says so.

**A pack holds facts about a country. It does not hold simulation choices.** Which countries a run
simulates, and in what proportion, is `geography.country_share` — a calibrated SRS target, not a
property of anywhere. Keeping the two apart is what lets the validated core stay five countries
while packs exist that are never simulated (ADR 0023 section 3).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fraudshield_dataset.params import ParameterError, ParameterSet

PACK_CATEGORY = "countries"


@dataclass(frozen=True)
class CountryPack:
    """One country's facts, as read from its pack."""

    alpha2: str
    currency: str
    minor_units: int
    utc_offset_hours: int
    centre: tuple[float, float]
    fx_rate_to_base: float
    continent: str
    blocs: frozenset[str]
    #: Common denominations in MINOR units (PB-44). The generator does not read them -- its own
    #: round-sum rule rounds to two significant figures -- but `round_sum_flag` cannot be computed
    #: without them, and a pack is where a currency fact belongs (ADR 0023) rather than a table in
    #: the feature code.
    round_denominations: tuple[int, ...]

    def shares_a_bloc_with(self, other: CountryPack) -> bool:
        return bool(self.blocs & other.blocs)


def _pack_codes(parameters: ParameterSet) -> list[str]:
    prefix = f"{PACK_CATEGORY}."
    return sorted({c.removeprefix(prefix) for c in parameters.categories() if c.startswith(prefix)})


def _centre(value: Any, alpha2: str) -> tuple[float, float]:
    if (
        not isinstance(value, list)
        or len(value) != 2
        or not all(isinstance(v, int | float) and not isinstance(v, bool) for v in value)
    ):
        raise ParameterError(f"countries.{alpha2}.centre: expected [latitude, longitude]")
    return (float(value[0]), float(value[1]))


def _blocs(value: Any, alpha2: str) -> frozenset[str]:
    if not isinstance(value, list) or not all(isinstance(v, str) and v for v in value):
        raise ParameterError(f"countries.{alpha2}.blocs: expected a list of bloc names")
    return frozenset(str(v) for v in value)


def _denominations(value: Any, alpha2: str) -> tuple[int, ...]:
    """Positive integers in minor units, ascending and distinct.

    Checked here rather than trusted, because every way this list can be wrong is silent:
    a zero would make every amount round (`amount % 0` raises, and a guard that skipped it would
    make the step vanish), a negative one is meaningless, a duplicate changes nothing but says the
    pack was edited without reading it, and a float would make an exact integer remainder
    inexact -- which is the one property `round_sum_flag` is built on.
    """
    if not isinstance(value, list) or not value:
        raise ParameterError(f"countries.{alpha2}.round_denominations: expected a non-empty list")
    if not all(isinstance(v, int) and not isinstance(v, bool) and v > 0 for v in value):
        raise ParameterError(
            f"countries.{alpha2}.round_denominations: every denomination must be a positive "
            f"integer in minor units, got {value!r}"
        )
    if sorted(value) != list(value) or len(set(value)) != len(value):
        raise ParameterError(
            f"countries.{alpha2}.round_denominations: must be ascending and distinct, got {value!r}"
        )
    return tuple(int(v) for v in value)


def load_packs(parameters: ParameterSet) -> dict[str, CountryPack]:
    """Every pack present, whether or not a run simulates it."""
    packs: dict[str, CountryPack] = {}
    for alpha2 in _pack_codes(parameters):
        prefix = f"{PACK_CATEGORY}.{alpha2}"
        packs[alpha2] = CountryPack(
            alpha2=alpha2,
            currency=str(parameters.value(f"{prefix}.currency")),
            minor_units=parameters.integer(f"{prefix}.currency_minor_units"),
            utc_offset_hours=parameters.integer(f"{prefix}.utc_offset_hours"),
            centre=_centre(parameters.value(f"{prefix}.centre"), alpha2),
            fx_rate_to_base=parameters.number(f"{prefix}.fx_rate_to_base"),
            continent=str(parameters.value(f"{prefix}.continent")),
            blocs=_blocs(parameters.value(f"{prefix}.blocs"), alpha2),
            round_denominations=_denominations(
                parameters.value(f"{prefix}.round_denominations"), alpha2
            ),
        )
    if not packs:
        raise ParameterError("no country packs found in generator/params/countries/")
    return packs


def simulated(parameters: ParameterSet) -> dict[str, CountryPack]:
    """The packs a run actually simulates, from ``geography.country_share``.

    A share naming a country with no pack is refused here rather than failing later with a missing
    currency or offset, because the message is much clearer at this point.
    """
    packs = load_packs(parameters)
    wanted = sorted(parameters.mapping("geography.country_share"))
    missing = [code for code in wanted if code not in packs]
    if missing:
        raise ParameterError(
            f"geography.country_share names {', '.join(missing)} with no country pack; add "
            f"generator/params/countries/<code>.yaml, which is all adding a country should take"
        )
    return {code: packs[code] for code in wanted}


def rates_by_currency(packs: dict[str, CountryPack]) -> dict[str, float]:
    """Conversion to the base currency, keyed by currency.

    Lives in the packs rather than a central table because a country cannot be generated without a
    rate: with the rate outside, adding a country meant editing a shared file, which the Country Z
    test caught as a violation of ADR 0023's own acceptance criterion.
    """
    rates: dict[str, float] = {}
    for pack in packs.values():
        existing = rates.setdefault(pack.currency, pack.fx_rate_to_base)
        if existing != pack.fx_rate_to_base:
            raise ParameterError(
                f"{pack.currency}: packs disagree on the rate to base "
                f"({existing} and {pack.fx_rate_to_base} from {pack.alpha2})"
            )
    return rates


def minor_units_by_currency(packs: dict[str, CountryPack]) -> dict[str, int]:
    """Minor units keyed by currency, which several countries may share (XOF, for instance).

    A pack repeats its currency's minor units rather than pointing at a shared table, so it reads
    on its own; this refuses the disagreement that repetition makes possible.
    """
    units: dict[str, int] = {}
    for pack in packs.values():
        existing = units.setdefault(pack.currency, pack.minor_units)
        if existing != pack.minor_units:
            raise ParameterError(
                f"{pack.currency}: packs disagree on minor units "
                f"({existing} and {pack.minor_units} from {pack.alpha2})"
            )
    return units
