"""Closed-form relationships between base rate, FPR, precision, recall and F1.

Used by ``evaluate.py`` to report the corrected gate metrics:

* D-01 — precision at a fixed FPR is bounded above by the base rate, so the SRS gate
  "precision at 1% FPR >= 0.72" is unattainable at a 0.87% fraud rate; the ceiling is
  reported next to the measured value.
* D-02 — recall, F1 and FNR are measured at the flag threshold; the precision and FPR that
  those targets imply are reported as a consistency check.
"""

from __future__ import annotations

from dataclasses import dataclass


class OperatingPointError(ValueError):
    """Raised for rates outside their mathematical domain."""


def _require_open_unit(name: str, value: float) -> None:
    if not 0.0 < value < 1.0:
        raise OperatingPointError(f"{name} must be in (0, 1), got {value}")


def _require_closed_unit(name: str, value: float) -> None:
    if not 0.0 <= value <= 1.0:
        raise OperatingPointError(f"{name} must be in [0, 1], got {value}")


def precision_ceiling_at_fpr(base_rate: float, fpr: float) -> float:
    """Highest precision achievable at false positive rate ``fpr``.

    Per unit of traffic, true positives are at most ``base_rate`` (recall = 1) and false
    positives are exactly ``fpr * (1 - base_rate)``, so
    ``precision <= base_rate / (base_rate + fpr * (1 - base_rate))``.
    """
    _require_open_unit("base_rate", base_rate)
    _require_closed_unit("fpr", fpr)
    return base_rate / (base_rate + fpr * (1.0 - base_rate))


@dataclass(frozen=True)
class ImpliedOperatingPoint:
    precision: float
    false_positive_rate: float


def implied_operating_point(f1: float, recall: float, base_rate: float) -> ImpliedOperatingPoint:
    """Precision and FPR implied by an F1 score and recall at a given base rate.

    From ``F1 = 2PR / (P + R)``: ``P = F1 * R / (2R - F1)``, defined when ``2R > F1`` and
    ``P <= 1``. Then ``FPR = R * base_rate * (1 - P) / (P * (1 - base_rate))``.
    """
    _require_open_unit("base_rate", base_rate)
    _require_closed_unit("f1", f1)
    _require_closed_unit("recall", recall)
    if f1 == 0.0 or 2.0 * recall <= f1:
        raise OperatingPointError(f"no precision satisfies f1={f1} with recall={recall}")
    precision = f1 * recall / (2.0 * recall - f1)
    if precision > 1.0:
        raise OperatingPointError(f"f1={f1} is unreachable with recall={recall}")
    fpr = recall * base_rate * (1.0 - precision) / (precision * (1.0 - base_rate))
    return ImpliedOperatingPoint(precision=precision, false_positive_rate=fpr)
