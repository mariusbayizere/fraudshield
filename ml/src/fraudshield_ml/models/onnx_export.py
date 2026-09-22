"""ONNX exports of a bundle's boosters and their parity (E.4; ADR 0032).

E.4 lists ONNX exports among the model artefacts, with a parity test: maximum absolute probability
difference below 1e-5 on 100,000 rows. The result differs by model:

* **XGBoost passes.** XGBoost evaluates splits in float32 itself, so the converter's float32 input
  loses nothing (5.4e-7 on the 101,909 bench1m test rows).
* **LightGBM cannot pass through ONNX.** Its split thresholds are doubles and `onnxmltools`'
  LightGBM converter accepts float32 input only, so a value between a threshold and its float32
  rounding takes the other branch (7,102 of 101,909 test rows over 1e-5, worst 0.20). The test
  pins the converter's refusal of double input, so a converter that gains it is noticed.

Serving therefore uses Treelite, which is exact against both boosters; ONNX stays an export for
interoperability. Its imports are development dependencies: nothing in serving imports this module.
"""

from __future__ import annotations

from typing import Any

import numpy as np
import onnxruntime as ort  # type: ignore[import-untyped]
from onnxmltools import convert_lightgbm, convert_xgboost  # type: ignore[import-untyped]
from onnxmltools.convert.common import data_types  # type: ignore[import-untyped]

from fraudshield_ml.models.bundle import Bundle

PARITY = 1e-5
OPSET = 15


def _float_input(features: int) -> Any:
    return [("input", data_types.FloatTensorType([None, features]))]


def export_xgboost(bundle: Bundle) -> bytes:
    model = convert_xgboost(
        bundle.xgboost, initial_types=_float_input(len(bundle.features)), target_opset=OPSET
    )
    return bytes(model.SerializeToString())


def export_lightgbm(bundle: Bundle, *, double: bool = False) -> bytes:
    """Float32 input, the only kind the converter accepts; `double=True` shows it refusing."""

    initial = (
        [("input", data_types.DoubleTensorType([None, len(bundle.features)]))]
        if double
        else _float_input(len(bundle.features))
    )
    model = convert_lightgbm(
        bundle.lightgbm, initial_types=initial, target_opset=OPSET, zipmap=False
    )
    return bytes(model.SerializeToString())


def probabilities(onnx_model: bytes, rows: Any) -> Any:

    options = ort.SessionOptions()
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    session = ort.InferenceSession(onnx_model, options, providers=["CPUExecutionProvider"])
    outputs = session.run(None, {"input": np.asarray(rows, dtype=np.float32)})
    return np.asarray(outputs[1])[:, 1]


def parity(bundle: Bundle, rows: Any) -> dict[str, float]:
    """Maximum absolute probability difference between each export and its native booster."""
    array = np.asarray(rows, dtype=np.float64)
    native_xgb = bundle.xgboost.inplace_predict(array, missing=np.nan)
    native_lgb = bundle.lightgbm.predict(array)
    return {
        "xgboost": float(np.max(np.abs(probabilities(export_xgboost(bundle), array) - native_xgb))),
        "lightgbm": float(
            np.max(np.abs(probabilities(export_lightgbm(bundle), array) - native_lgb))
        ),
    }
