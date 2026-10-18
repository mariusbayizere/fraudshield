"""D-06: the Isolation Forest's two scores, and where its imputation comes from."""

from __future__ import annotations

import math

import numpy as np
import pytest

from fraudshield_ml.training import anomaly

pytestmark = pytest.mark.req("D-06")


def _matrix(n: int = 1000, seed: int = 5) -> list[list[float]]:
    rng = np.random.default_rng(seed)
    return [[float(v) for v in rng.normal(0.0, 1.0, 4)] for _ in range(n)]


@pytest.fixture(scope="module")
def fitted() -> tuple[anomaly.AnomalyModel, list[list[float]]]:
    matrix = _matrix()
    return anomaly.fit_anomaly(matrix, list(range(800)), seed=1), matrix


@pytest.mark.req("TEST-02")
def test_the_raw_score_is_scikit_learns_and_lies_in_minus_one_to_zero(
    fitted: tuple[anomaly.AnomalyModel, list[list[float]]],
) -> None:
    """The SRS's [-1, 1] unit test applies to the raw score, which is where it holds."""
    model, matrix = fitted
    raw = model.raw(matrix)
    assert all(-1.0 <= v <= 0.0 for v in raw)
    assert raw == pytest.approx([float(v) for v in model.forest.score_samples(matrix)])


def test_the_anomaly_score_is_a_percentile_against_the_training_rows(
    fitted: tuple[anomaly.AnomalyModel, list[list[float]]],
) -> None:
    """On the training rows themselves the score is uniform on (0, 1]; an outlier reads 1.0."""
    model, matrix = fitted
    on_train = model.score(matrix[:800])
    assert all(0.0 < v <= 1.0 for v in on_train)
    assert sum(on_train) / len(on_train) == pytest.approx(0.5, abs=0.01)
    outlier, typical = model.score([[40.0, -40.0, 40.0, -40.0], [0.0, 0.0, 0.0, 0.0]])
    assert outlier == 1.0
    assert typical < 0.5, "the centre of the training distribution is not anomalous"


def test_the_review_threshold_flags_about_the_top_percent_of_training_rows(
    fitted: tuple[anomaly.AnomalyModel, list[list[float]]],
) -> None:
    """D-06's production default is the 0.995 percentile, so ~0.5% of the reference exceeds it."""
    model, matrix = fitted
    above = sum(1 for v in model.score(matrix[:800]) if v > 0.995)
    assert above == pytest.approx(0.005 * 800, abs=1)


def test_imputation_uses_training_medians_and_never_the_scored_rows() -> None:
    """A median over scored rows would carry their distribution into the model: a quiet leak."""
    matrix = [[1.0, math.nan], [3.0, 10.0], [5.0, 20.0], [1000.0, math.nan], [2000.0, 5000.0]]
    model = anomaly.fit_anomaly(matrix, [0, 1, 2], seed=1)
    assert model.medians == (3.0, 15.0)
    assert model.impute([[math.nan, math.nan]]) == [[3.0, 15.0]]


def test_no_training_rows_is_refused() -> None:
    with pytest.raises(ValueError, match="no training rows"):
        anomaly.fit_anomaly(_matrix(10), [], seed=1)
