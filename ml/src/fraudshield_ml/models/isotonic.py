"""Isotonic calibration (D-05), stored as its knots and applied without scikit-learn.

A fitted `IsotonicRegression` with `out_of_bounds="clip"` predicts by linear interpolation between
its thresholds, clamped at the ends, which is `numpy.interp` exactly. So the calibrator persists as
two lists of numbers, and the test holds the two predictions equal.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

import numpy as np


@dataclass(frozen=True)
class Isotonic:
    x: tuple[float, ...]
    y: tuple[float, ...]

    def __call__(self, score: float) -> float:
        return float(np.interp(score, self.x, self.y))

    def many(self, scores: Sequence[float]) -> list[float]:
        return [float(v) for v in np.interp(np.asarray(scores, dtype=float), self.x, self.y)]

    def to_json(self) -> dict[str, Any]:
        return {"x": list(self.x), "y": list(self.y)}

    @staticmethod
    def from_json(data: dict[str, Any]) -> Isotonic:
        return Isotonic(tuple(float(v) for v in data["x"]), tuple(float(v) for v in data["y"]))


def from_sklearn(model: Any) -> Isotonic:
    """A fitted `IsotonicRegression` (M4's calibrators, `training.model._isotonic`) as knots."""
    if getattr(model, "out_of_bounds", None) != "clip":
        raise ValueError("only a calibrator that clips out-of-range scores is reproduced by interp")
    return Isotonic(
        tuple(float(v) for v in model.X_thresholds_),
        tuple(float(v) for v in model.y_thresholds_),
    )
