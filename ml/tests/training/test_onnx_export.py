"""E.4's ONNX parity: the exported boosters agree with the evaluated ones to within 1e-5."""

from __future__ import annotations

import numpy as np
import onnxmltools
import pytest
from onnxmltools.convert.common.data_types import FloatTensorType

from fraudshield_ml.training import model, onnx_export

pytestmark = pytest.mark.req("FR-02-03", "D-05")


@pytest.fixture(scope="module")
def fitted() -> tuple[model.Ensemble, list[list[float]]]:
    rng = np.random.default_rng(3)
    labels = rng.random(4000) < 0.05
    matrix = rng.normal(0.0, 1.0, (4000, 6))
    matrix[labels, 0] += 1.5
    matrix[rng.random((4000, 6)) < 0.1] = np.nan
    split = model.RowSplit(tuple(range(2400)), tuple(range(2400, 3200)), tuple(range(3200, 4000)))
    return model.fit_ensemble(matrix.tolist(), list(labels), split, seed=1), matrix.tolist()


def test_the_exports_agree_with_the_native_boosters_including_on_missing_values(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    """A tenth of the values are NaN, so the missing-value branches are exercised too (D-04)."""
    ensemble, matrix = fitted
    result = onnx_export.parity(ensemble, matrix)
    assert result.rows == len(matrix)
    assert result.passed, result
    assert max(result.xgboost, result.lightgbm, result.combined) < onnx_export.PARITY_TOLERANCE


def test_exporting_the_whole_booster_instead_of_the_early_stopped_one_breaks_parity(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    """Early stopping leaves extra trees behind; the parity test must notice if they ship."""
    ensemble, matrix = fitted
    assert ensemble.xgboost.num_boosted_rounds() > ensemble.xgboost_rounds, (
        "precondition: early stopping left trees past the best iteration"
    )
    whole = onnxmltools.convert_xgboost(
        ensemble.xgboost, initial_types=[(onnx_export.INPUT, FloatTensorType([None, 6]))]
    )
    native, _ = ensemble.raw(matrix)
    shipped = onnx_export.probabilities(whole, matrix)
    assert max(abs(a - b) for a, b in zip(native, shipped, strict=True)) > 1e-3
