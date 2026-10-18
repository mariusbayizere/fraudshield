"""The primitives both paths may share, enumerated in ``registry.SHARED_PRIMITIVES``.

The parity test **cannot see into these**: if both paths call the same haversine, a wrong haversine
agrees with itself and parity passes. Each therefore carries unit tests with hand-computed
expectations, which is the only thing covering this surface (parity Decision 4).

A shared window-aggregation helper is deliberately **not** permitted here. Window aggregation is
precisely what the parity test exists to check, and sharing it would make the test prove that a
function equals itself.
"""

from __future__ import annotations

import math

# h3 ships no py.typed marker; the one call site is wrapped in `h3_cell`, which is typed, so the
# untyped surface is a single function rather than the whole feature path.
import h3  # type: ignore[import-untyped]

#: Part E.2 specifies the geographic cell as H3 resolution 6 (~36 km^2 average).
H3_RESOLUTION = 6

EARTH_RADIUS_KM = 6371.0088
"""IUGG mean earth radius. Fixed here so both paths cannot differ by choosing 6371 vs 6378."""


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance in kilometres.

    Haversine rather than the spherical law of cosines: the latter loses precision for small
    distances through `acos` of a number near 1, and most consecutive transactions on an account
    are close together, which is exactly where `distance_from_last_tx_km` needs to be right.
    """
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlambda = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlambda / 2) ** 2
    return 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(a))


def h3_cell(latitude: float, longitude: float) -> str:
    """The H3 resolution-6 cell containing a point.

    Isolated here so the H3 dependency has exactly one call site. The density measurement in
    `docs/research/component_sizes.py` deliberately uses a lat/lon grid instead and says why; this
    is the feature path, where the cell's identity matters and an approximation would not do.
    """
    return str(h3.latlng_to_cell(latitude, longitude, H3_RESOLUTION))


def fx_to_rwf(amount: float, currency: str, rates_to_rwf: dict[str, float]) -> float:
    """Convert an amount to RWF at a supplied rate.

    The rate table is **passed in, never looked up here**, and the caller is responsible for
    supplying the rate for the transaction's own date. That is a leakage boundary, not an
    ergonomics choice: a primitive that fetched "the current rate" would convert an eight-month-old
    transaction at a rate published after it happened — `reference_data_basis=AS_OF_EVENT` in a
    different substrate (ADR 0026).

    Rates live in the country packs (ADR 0023), never hard-coded here: RWF is the base only because
    the validated core is East African, and a pack-driven table is what lets that change.
    """
    if currency not in rates_to_rwf:
        raise KeyError(
            f"no rate to RWF for {currency!r}; rates come from the country packs, and a currency "
            "without one means a pack is missing rather than that a default should be used"
        )
    return amount * rates_to_rwf[currency]
