"""FR-02-04 and D-05 (4): exact per-model contributions, combined in margin space."""

from __future__ import annotations

import math

import numpy as np
import pytest

from fraudshield_ml.training import explain, model

pytestmark = pytest.mark.req("FR-02-04", "D-05")

NAMES = ("signal", "b", "c", "anti", "e", "f")


@pytest.fixture(scope="module")
def fitted() -> tuple[model.Ensemble, list[list[float]]]:
    rng = np.random.default_rng(11)
    labels = [bool(v) for v in rng.random(3000) < 0.05]
    matrix = []
    for y in labels:
        row = rng.normal(0.0, 1.0, 6)
        if y:
            row[0] += 2.0
            row[3] -= 1.0
        matrix.append([float(v) for v in row])
    split = model.RowSplit(tuple(range(1800)), tuple(range(1800, 2400)), tuple(range(2400, 3000)))
    return model.fit_ensemble(matrix, labels, split, seed=1), matrix


def test_each_models_contributions_sum_to_its_own_margin_within_tolerance(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    """FR-02-04's additivity test, per model, in margin space where it is exact."""
    ensemble, matrix = fitted
    explained = explain.explain(ensemble, matrix[:300], NAMES)
    assert max(e.additivity_error for e in explained) <= explain.ADDITIVITY_TOLERANCE


def test_the_combined_contributions_sum_to_the_combined_margin_not_the_probability(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    """D-05 (4): 0.55·φ_xgb + 0.45·φ_lgb sums to the weighted margin minus the weighted base."""
    ensemble, matrix = fitted
    for e in explain.explain(ensemble, matrix[:300], NAMES):
        assert sum(e.shap) + e.base == pytest.approx(e.margin, abs=explain.ADDITIVITY_TOLERANCE)


def test_the_top_five_are_ranked_by_magnitude_and_signed(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    ensemble, matrix = fitted
    for e in explain.explain(ensemble, matrix[:200], NAMES):
        assert len(e.top) == explain.TOP
        magnitudes = [abs(c.shap) for c in e.top]
        assert magnitudes == sorted(magnitudes, reverse=True)
        assert min(magnitudes) >= sorted(abs(v) for v in e.shap)[-explain.TOP]
        for c in e.top:
            assert (c.shap > 0) == (c.direction == "increases_risk")
            assert c.template_key.startswith(f"shap.{c.feature}.")


def test_the_planted_signal_is_what_the_explanations_name_first(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    """A check that the attribution lands on the right column, not only that it adds up."""
    ensemble, matrix = fitted
    firsts = [e.top[0].feature for e in explain.explain(ensemble, matrix[:500], NAMES)]
    assert max(set(firsts), key=firsts.count) == "signal"


def _explanation(error: float, top: int = explain.TOP) -> explain.Explanation:
    contribution = explain.Contribution("a", 1.0, 0.1, "increases_risk", "shap.a.up")
    return explain.Explanation(0.0, 0.1, (0.1,), (contribution,) * top, error)


@pytest.mark.req("ML-GATE-10")
def test_coverage_counts_only_flagged_rows_and_only_complete_explanations() -> None:
    good, bad, short = _explanation(0.0), _explanation(0.01), _explanation(0.0, top=3)
    scores = [0.9, 0.7, 0.65, 0.1]
    share, rows = explain.coverage(scores, [good, bad, short, bad])
    assert rows == 3, "the LOW row is not in ML-GATE-10's population"
    assert share == pytest.approx(1 / 3)
    share, rows = explain.coverage([0.1, 0.2], [good, good])
    assert rows == 0
    assert math.isnan(share), "coverage of no flagged rows is undefined, not 100%"


def test_a_column_count_mismatch_is_refused(
    fitted: tuple[model.Ensemble, list[list[float]]],
) -> None:
    ensemble, matrix = fitted
    with pytest.raises(ValueError, match="plus a bias column"):
        explain.explain(ensemble, matrix[:5], NAMES[:5])
