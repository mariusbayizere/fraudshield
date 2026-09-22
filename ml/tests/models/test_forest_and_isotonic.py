"""The exported Isolation Forest and isotonic calibrator score exactly as scikit-learn does."""

from __future__ import annotations

import json
import math
import random

import numpy as np
import pytest

from fraudshield_ml.models import forest, isotonic


def _matrix(rows: int, seed: int) -> list[list[float]]:
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    out = []
    for _ in range(rows):
        row = [rng.gauss(0, 1), rng.expovariate(1.0), float(rng.randint(0, 3)), rng.random()]
        if rng.random() < 0.2:
            row[1] = math.nan
        out.append(row)
    # A column missing everywhere: imputes to a constant rather than failing.
    return [[*r, math.nan] for r in out]


@pytest.mark.req("FR-02-05", "D-06")
def test_the_export_scores_exactly_as_scikit_learn_does() -> None:
    train = _matrix(2000, 1)
    exported, model = forest.fit(train, seed=7)
    probe = [*_matrix(300, 2), [40.0, 90.0, 3.0, 1.0, math.nan]]
    expected = model.score_samples(np.where(np.isnan(probe), exported.medians, probe))
    got = [exported.raw(row) for row in probe]
    assert np.max(np.abs(np.asarray(got) - expected)) < 1e-12


@pytest.mark.req("FR-02-05", "D-06")
def test_the_anomaly_score_is_a_percentile_of_the_training_reference() -> None:
    exported, _ = forest.fit(_matrix(2000, 3), seed=1)
    ordinary = exported.percentile(exported.raw([0.0, 1.0, 1.0, 0.5, math.nan]))
    extreme = exported.percentile(exported.raw([40.0, 90.0, 3.0, 1.0, math.nan]))
    assert 0.0 <= ordinary < 0.9
    assert extreme == 1.0
    raws = [-v for v in exported.reference]
    assert all(-1.0 <= r <= 0.0 for r in raws), "the raw score lies in [-1, 0] (D-06)"


def test_the_forest_survives_a_json_round_trip() -> None:
    exported, _ = forest.fit(_matrix(500, 4), seed=2)
    again = forest.Forest.from_json(json.loads(json.dumps(exported.to_json())))
    row = [1.5, math.nan, 2.0, 0.1, math.nan]
    assert again.raw(row) == exported.raw(row)


def test_average_path_length_edge_cases() -> None:
    assert forest.average_path_length(1) == 0.0
    assert forest.average_path_length(2) == 1.0
    assert forest.average_path_length(256) == pytest.approx(10.2448, abs=1e-3)


@pytest.mark.req("FR-02-03", "D-05")
def test_the_isotonic_knots_predict_exactly_as_scikit_learn_does() -> None:
    rng = random.Random(5)  # noqa: S311 - test data, not secrets
    scores = [rng.random() for _ in range(3000)]
    labels = [rng.random() < s**3 for s in scores]
    fitted, model = isotonic.fit(scores, labels)
    probe = [-0.5, 0.0, *[rng.random() for _ in range(500)], 1.0, 1.7]
    assert fitted.many(probe) == pytest.approx(list(model.predict(probe)), abs=1e-15)
    assert fitted(0.3) == pytest.approx(float(model.predict([0.3])[0]), abs=1e-15)
    again = isotonic.Isotonic.from_json(json.loads(json.dumps(fitted.to_json())))
    assert again == fitted
