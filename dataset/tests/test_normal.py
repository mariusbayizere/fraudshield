from __future__ import annotations

import math

import pytest

from fraudshield_dataset.normal import inverse_cdf

pytestmark = pytest.mark.req("ML-DATA-05")

# Published standard-normal quantiles.
KNOWN = {
    0.5: 0.0,
    0.975: 1.959963984540054,
    0.995: 2.5758293035489004,
    0.0001: -3.719016485455709,
    0.9999: 3.719016485455709,
    0.001: -3.090232306167813,
    0.25: -0.6744897501960817,
}


@pytest.mark.parametrize(("p", "z"), sorted(KNOWN.items()))
def test_matches_published_quantiles(p: float, z: float) -> None:
    assert inverse_cdf(p) == pytest.approx(z, abs=1e-12)


def test_round_trips_through_the_cdf() -> None:
    for i in range(1, 1000):
        p = i / 1000
        z = inverse_cdf(p)
        assert 0.5 * math.erfc(-z / math.sqrt(2)) == pytest.approx(p, abs=1e-12)


def test_rejects_probabilities_outside_the_open_unit_interval() -> None:
    for p in (0.0, 1.0, -0.1, 1.5):
        with pytest.raises(ValueError, match="out of range"):
            inverse_cdf(p)
