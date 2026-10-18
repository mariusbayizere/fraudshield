"""E.5's baselines: fitted on training rows only, and never on a test label."""

from __future__ import annotations

import math

import numpy as np
import pytest

from fraudshield_ml.training import baselines, gate

pytestmark = pytest.mark.req("TEST-14")


def test_the_rule_engine_counts_rules_and_fits_its_threshold_on_training_legitimate_rows() -> None:
    amounts = [1.0] * 99 + [5.0] + [100.0, 1.0, 1.0]
    labels = [False] * 100 + [True, False, True]
    mcc = ["5411"] * 100 + ["5411", "4829", "6011"]
    train = list(range(100))
    scores = baselines.rule_engine(amounts, mcc, labels, train)
    # The 99th percentile of the training legitimate amounts sits between 1.0 and 5.0, so the
    # 100.0 in the scored rows fires the amount rule and cannot have moved the threshold itself.
    assert scores[100:] == [1.0, 1.0, 1.0]
    assert baselines.rule_engine(amounts, None, labels, train)[100:] == [1.0, 0.0, 0.0], (
        "without an MCC column only the amount rule is scored"
    )


def _learnable(n: int = 1500) -> tuple[list[list[float]], list[bool]]:
    rng = np.random.default_rng(4)
    labels = [bool(v) for v in rng.random(n) < 0.1]
    matrix = []
    for y in labels:
        row = [float(v) for v in rng.normal(0.0, 1.0, 4)]
        row[1] += 2.0 if y else 0.0
        row[2] = math.nan if rng.random() < 0.3 else row[2]
        matrix.append(row)
    return matrix, labels


@pytest.mark.parametrize("fit", [baselines.logistic_regression, baselines.random_forest])
def test_a_baseline_learns_the_signal_and_never_reads_a_test_label(fit: object) -> None:
    matrix, labels = _learnable()
    train, test = list(range(1000)), list(range(1000, 1500))
    scores = fit(matrix, labels, train, test, seed=1)  # type: ignore[operator]
    held = np.array([labels[i] for i in test])
    assert gate.auc(np.array(scores), held) > 0.8
    assert all(0.0 <= s <= 1.0 for s in scores)
    flipped = labels[:1000] + [not y for y in labels[1000:]]
    assert fit(matrix, flipped, train, test, seed=1) == scores, "a test label reached the fit"  # type: ignore[operator]
