"""M4's fitted forest and isotonic calibrators, exported to arrays, score exactly as fitted."""

from __future__ import annotations

import json
import math
import random

import numpy as np
import pytest
from sklearn.isotonic import IsotonicRegression

from fraudshield_ml.models import forest, isotonic
from fraudshield_ml.training import anomaly


def _matrix(rows: int, seed: int) -> list[list[float]]:
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    out = []
    for _ in range(rows):
        row = [rng.gauss(0, 1), rng.expovariate(1.0), float(rng.randint(0, 3)), rng.random()]
        if rng.random() < 0.2:
            row[1] = math.nan
        out.append(row)
    # A column missing everywhere: M4 imputes it to a constant rather than failing.
    return [[*r, math.nan] for r in out]


@pytest.fixture(scope="module")
def fitted() -> tuple[anomaly.AnomalyModel, forest.Forest]:
    train = _matrix(2000, 1)
    model = anomaly.fit_anomaly(train, list(range(len(train))), seed=7)
    return model, forest.export(model)


@pytest.mark.req("FR-02-05", "D-06")
def test_the_export_scores_exactly_as_m4_s_model_does(
    fitted: tuple[anomaly.AnomalyModel, forest.Forest],
) -> None:
    model, exported = fitted
    probe = [*_matrix(300, 2), [40.0, 90.0, 3.0, 1.0, math.nan]]
    expected = model.raw(probe)
    got = [exported.raw(row) for row in probe]
    assert np.max(np.abs(np.asarray(got) - np.asarray(expected))) < 1e-12
    assert [exported.percentile(r) for r in got] == model.score(probe)
    walked = [exported.raw_reference(row) for row in probe]
    # Same leaves; only the summation order differs (numpy is pairwise), so ulps, not exactness.
    assert np.max(np.abs(np.asarray(got) - np.asarray(walked))) < 1e-12


@pytest.mark.req("FR-02-05", "D-06")
def test_the_anomaly_score_is_a_percentile_of_the_training_reference(
    fitted: tuple[anomaly.AnomalyModel, forest.Forest],
) -> None:
    _, exported = fitted
    ordinary = exported.percentile(exported.raw([0.0, 1.0, 1.0, 0.5, math.nan]))
    extreme = exported.percentile(exported.raw([40.0, 90.0, 3.0, 1.0, math.nan]))
    assert 0.0 <= ordinary < 0.9
    assert extreme == 1.0
    assert all(-1.0 <= -v <= 0.0 for v in exported.reference), "raw lies in [-1, 0] (D-06)"


def test_the_forest_survives_a_json_round_trip(
    fitted: tuple[anomaly.AnomalyModel, forest.Forest],
) -> None:
    _, exported = fitted
    again = forest.Forest.from_json(json.loads(json.dumps(exported.to_json())))
    row = [1.5, math.nan, 2.0, 0.1, math.nan]
    assert again.raw(row) == exported.raw(row)


def test_average_path_length_edge_cases() -> None:
    assert forest.average_path_length(1) == 0.0
    assert forest.average_path_length(2) == 1.0
    assert forest.average_path_length(256) == pytest.approx(10.2448, abs=1e-3)


@pytest.mark.req("FR-02-03", "D-05")
def test_the_isotonic_knots_predict_exactly_as_the_fitted_calibrator_does() -> None:
    rng = random.Random(5)  # noqa: S311 - test data, not secrets
    scores = [rng.random() for _ in range(3000)]
    labels = [1.0 if rng.random() < s**3 else 0.0 for s in scores]
    # M4's calibrator settings (training.model._isotonic).
    model = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip").fit(scores, labels)
    knots = isotonic.from_sklearn(model)
    probe = [-0.5, 0.0, *[rng.random() for _ in range(500)], 1.0, 1.7]
    assert knots.many(probe) == pytest.approx(list(model.predict(probe)), abs=1e-15)
    assert knots(0.3) == pytest.approx(float(model.predict([0.3])[0]), abs=1e-15)
    assert isotonic.Isotonic.from_json(json.loads(json.dumps(knots.to_json()))) == knots


def test_a_calibrator_that_does_not_clip_is_refused() -> None:
    model = IsotonicRegression(out_of_bounds="nan").fit([0.1, 0.9], [0.0, 1.0])
    with pytest.raises(ValueError, match="clips"):
        isotonic.from_sklearn(model)
