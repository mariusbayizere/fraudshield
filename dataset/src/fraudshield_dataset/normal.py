"""Inverse standard-normal CDF (probit), used to place values on a distribution's quantiles.

NumPy has no probit and SciPy is not a dependency (ADR 0022 section 7), so this is Peter Acklam's
rational approximation with one step of Halley refinement, accurate to about 1e-15 over the open
unit interval. The constants are the published coefficients of that approximation, not modelling
parameters.
"""

from __future__ import annotations

import math

# Acklam's coefficients (low region, central region) and the split points of his approximation.
_A = (
    -3.969683028665376e01,
    2.209460984245205e02,
    -2.759285104469687e02,
    1.383577518672690e02,
    -3.066479806614716e01,
    2.506628277459239e00,
)
_B = (
    -5.447609879822406e01,
    1.615858368580409e02,
    -1.556989798598866e02,
    6.680131188771972e01,
    -1.328068155288572e01,
)
_C = (
    -7.784894002430293e-03,
    -3.223964580411365e-01,
    -2.400758277161838e00,
    -2.549732539343734e00,
    4.374664141464968e00,
    2.938163982698783e00,
)
_D = (7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e00, 3.754408661907416e00)
_LOW, _HIGH = 0.02425, 1 - 0.02425


def inverse_cdf(p: float) -> float:
    """The value ``z`` with ``P(Z <= z) = p`` for a standard normal ``Z`` (0 < p < 1)."""
    if not 0.0 < p < 1.0:
        raise ValueError(f"probability out of range: {p}")
    if p < _LOW:
        q = math.sqrt(-2 * math.log(p))
        z = (((((_C[0] * q + _C[1]) * q + _C[2]) * q + _C[3]) * q + _C[4]) * q + _C[5]) / (
            (((_D[0] * q + _D[1]) * q + _D[2]) * q + _D[3]) * q + 1
        )
    elif p <= _HIGH:
        q = p - 0.5
        r = q * q
        z = (
            (((((_A[0] * r + _A[1]) * r + _A[2]) * r + _A[3]) * r + _A[4]) * r + _A[5])
            * q
            / (((((_B[0] * r + _B[1]) * r + _B[2]) * r + _B[3]) * r + _B[4]) * r + 1)
        )
    else:
        q = math.sqrt(-2 * math.log(1 - p))
        z = -(((((_C[0] * q + _C[1]) * q + _C[2]) * q + _C[3]) * q + _C[4]) * q + _C[5]) / (
            (((_D[0] * q + _D[1]) * q + _D[2]) * q + _D[3]) * q + 1
        )
    # One Halley step on the residual of the CDF removes the approximation's remaining error.
    error = 0.5 * math.erfc(-z / math.sqrt(2)) - p
    density = math.exp(-z * z / 2) / math.sqrt(2 * math.pi)
    return z - error / density / (1 + z * error / (2 * density))
