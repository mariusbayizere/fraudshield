"""ONNX exports of D-05's boosters, and the parity test E.4 requires of them.

E.4: native model files for SHAP, ONNX exports for serving, and a parity test with a maximum
absolute probability difference below 1e-5 on 100,000 rows. The export is what M5's scoring
service runs, so "the ONNX model agrees with the model that was evaluated" is what makes the gate's
figures describe production at all.

**Each booster is truncated to its early-stopped round count before export.** Early stopping keeps
the trees trained past the best iteration, and `Ensemble.raw` scores with only the first
`*_rounds` of them; exporting the whole booster would ship a different model from the one
evaluated, and the parity test is exactly what would catch it.

ONNX computes in float32. XGBoost does too; LightGBM keeps double thresholds, so a float32 input
can in principle fall on the other side of a split. Parity is therefore measured, not assumed.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

from fraudshield_ml.training.ensemble import ensemble_score
from fraudshield_ml.training.model import Ensemble

#: E.4's parity bound on the maximum absolute probability difference.
PARITY_TOLERANCE = 1e-5
INPUT = "input"


@dataclass(frozen=True)
class Parity:
    """Largest absolute probability difference between ONNX and native, per model and combined."""

    xgboost: float
    lightgbm: float
    combined: float
    rows: int

    @property
    def passed(self) -> bool:
        return max(self.xgboost, self.lightgbm, self.combined) < PARITY_TOLERANCE


def export(ensemble: Ensemble, width: int) -> tuple[Any, Any]:
    """Both boosters as ONNX models, truncated to the rounds the ensemble scores with."""
    import lightgbm as lgb  # noqa: PLC0415 - heavy, and only this path needs it
    import onnxmltools  # noqa: PLC0415 - heavy, and only this path needs it
    from onnxmltools.convert.common.data_types import FloatTensorType  # noqa: PLC0415

    signature = [(INPUT, FloatTensorType([None, width]))]
    xgboost = onnxmltools.convert_xgboost(
        ensemble.xgboost[: ensemble.xgboost_rounds], initial_types=signature
    )
    truncated = lgb.Booster(
        model_str=ensemble.lightgbm.model_to_string(num_iteration=ensemble.lightgbm_rounds)
    )
    lightgbm = onnxmltools.convert_lightgbm(truncated, initial_types=signature, zipmap=False)
    return xgboost, lightgbm


def probabilities(onnx_model: Any, rows: Sequence[Sequence[float]]) -> list[float]:
    """The positive-class probability from an exported booster, via onnxruntime on the CPU."""
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it
    import onnxruntime as ort  # noqa: PLC0415 - heavy, and only this path needs it

    session = ort.InferenceSession(
        onnx_model.SerializeToString(), providers=["CPUExecutionProvider"]
    )
    _, scores = session.run(None, {INPUT: np.asarray(rows, dtype=np.float32)})
    return [float(p) for p in scores[:, 1]]


def parity(ensemble: Ensemble, rows: Sequence[Sequence[float]]) -> Parity:
    """E.4's parity test on `rows`: native against ONNX, for each booster and their combination."""
    xgboost, lightgbm = export(ensemble, len(rows[0]))
    native_x, native_l = ensemble.raw(rows)
    onnx_x, onnx_l = probabilities(xgboost, rows), probabilities(lightgbm, rows)

    def worst(a: Sequence[float], b: Sequence[float]) -> float:
        return max(abs(p - q) for p, q in zip(a, b, strict=True))

    return Parity(
        xgboost=worst(native_x, onnx_x),
        lightgbm=worst(native_l, onnx_l),
        combined=worst(ensemble_score(native_x, native_l), ensemble_score(onnx_x, onnx_l)),
        rows=len(rows),
    )
