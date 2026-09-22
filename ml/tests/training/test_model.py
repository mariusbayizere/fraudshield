"""D-05's production model: two early-stopped boosters, one combination, one isotonic calibrator."""

from __future__ import annotations

from itertools import pairwise

import numpy as np
import pytest

from fraudshield_ml.training import model
from fraudshield_ml.training.ensemble import ensemble_score

pytestmark = pytest.mark.req("FR-02-03", "D-05")


def _data(n: int = 3000, seed: int = 3) -> tuple[list[list[float]], list[bool]]:
    """Noisy, imbalanced, learnable: 5% positives shifted on two of six columns."""
    rng = np.random.default_rng(seed)
    labels = [bool(v) for v in rng.random(n) < 0.05]
    matrix = []
    for y in labels:
        row = rng.normal(0.0, 1.0, 6)
        if y:
            row[0] += 1.5
            row[3] -= 1.0
        matrix.append([float(v) for v in row])
    return matrix, labels


SPLIT = model.RowSplit(tuple(range(0, 1800)), tuple(range(1800, 2400)), tuple(range(2400, 3000)))


@pytest.fixture(scope="module")
def fitted() -> tuple[model.Ensemble, list[list[float]], list[bool]]:
    matrix, labels = _data()
    return (
        model.fit_ensemble(matrix, labels, SPLIT, seed=1),
        matrix,
        labels,
    )


@pytest.mark.parametrize(
    ("train", "validation", "calibration", "pair"),
    [
        ([0, 1, 2], [3, 4], [2, 5], "train and calibration"),
        ([0, 1, 2], [3, 4], [4, 5], "validation and calibration"),
        ([0, 1, 2], [2, 4], [5, 6], "train and validation"),
    ],
)
def test_overlapping_row_sets_are_refused(
    train: list[int], validation: list[int], calibration: list[int], pair: str
) -> None:
    """A calibrator fitted on rows the boosters saw produces plausible output, so refuse early."""
    with pytest.raises(model.OverlappingRowsError, match=pair):
        model.RowSplit(tuple(train), tuple(validation), tuple(calibration))


def test_an_empty_split_or_a_single_class_is_refused() -> None:
    matrix, _ = _data(10)
    with pytest.raises(ValueError, match="no calibration rows"):
        model.RowSplit((0, 1), (2,), ())
    with pytest.raises(ValueError, match="single class"):
        model.fit_ensemble(matrix, [False] * 10, model.RowSplit((0, 1, 2), (3,), (4,)), seed=1)


@pytest.mark.req("ML-GATE-11", "D-07")
def test_the_calibrator_learns_from_the_calibration_rows_and_no_others(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Every isotonic fit sees exactly the calibration period's labels, in its order."""
    seen: list[list[bool]] = []
    real = model._isotonic

    def record(scores: list[float], labels: list[bool]) -> object:
        seen.append(list(labels))
        return real(scores, labels)

    monkeypatch.setattr(model, "_isotonic", record)
    matrix, labels = _data()
    model.fit_ensemble(matrix, labels, SPLIT, seed=1)
    expected = [labels[i] for i in SPLIT.calibration]
    assert seen == [expected, expected, expected], (
        "a calibrator saw rows outside the calibration period"
    )


@pytest.mark.req("ML-GATE-11")
def test_isotonic_calibration_corrects_the_level_the_imbalance_weight_inflated(
    fitted: tuple[model.Ensemble, list[list[float]], list[bool]],
) -> None:
    """Imbalance weighting pushes every raw probability up; the calibrator brings the mean back.

    On its own fitting rows, isotonic regression's fitted values average exactly to the labels, so
    the calibrated mean over the calibration period is its base rate — while the weighted raw
    score sits far above it.
    """
    ensemble, matrix, labels = fitted
    calibration = SPLIT.calibration
    scored = ensemble.score([matrix[i] for i in calibration])
    base = sum(labels[i] for i in calibration) / len(calibration)
    assert abs(sum(scored.raw_combined) / len(calibration) - base) > 0.01, (
        "precondition: the raw level is off, so there is something to calibrate"
    )
    assert sum(scored.ensemble) / len(calibration) == pytest.approx(base, abs=1e-9)
    for column in (scored.ensemble, scored.xgboost, scored.lightgbm):
        assert all(0.0 <= v <= 1.0 for v in column)


def test_the_calibrated_score_is_a_monotone_map_of_the_raw_combination(
    fitted: tuple[model.Ensemble, list[list[float]], list[bool]],
) -> None:
    """Calibration may tie scores but never reorder them, so ranking metrics are unchanged by it."""
    ensemble, matrix, _ = fitted
    scored = ensemble.score(matrix[:500])
    pairs = sorted(zip(scored.raw_combined, scored.ensemble, strict=True))
    assert all(a[1] <= b[1] for a, b in pairwise(pairs))
    p_xgb, p_lgb = ensemble.raw(matrix[:500])
    assert scored.raw_combined == ensemble_score(p_xgb, p_lgb)


def test_early_stopping_stops_and_the_imbalance_weight_is_negatives_per_positive(
    fitted: tuple[model.Ensemble, list[list[float]], list[bool]],
) -> None:
    ensemble, _, labels = fitted
    train = SPLIT.train
    positives = sum(labels[i] for i in train)
    assert ensemble.scale_pos_weight == pytest.approx((len(train) - positives) / positives)
    assert 1 <= ensemble.xgboost_rounds < model.MAX_ROUNDS
    assert 1 <= ensemble.lightgbm_rounds < model.MAX_ROUNDS
