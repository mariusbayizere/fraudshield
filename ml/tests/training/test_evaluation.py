"""The evaluation report's arithmetic and its refusals (PB-46, PB-55)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.training.evaluation import (
    TRIVIAL_FEATURES,
    Baseline,
    Evaluation,
    SegmentCounts,
    spread,
    summarise,
)
from fraudshield_ml.training.split import Boundaries

pytestmark = pytest.mark.req("D-08")

START = datetime(2025, 1, 1, tzinfo=UTC)


def _boundaries() -> Boundaries:
    def at(days: float) -> int:
        return int((START + timedelta(days=days)).timestamp() * 1_000_000)

    return Boundaries(at(600), at(632), at(658), at(665), at(729))


@pytest.mark.req("D-08")
def test_a_partial_feature_is_not_offered_as_the_baseline() -> None:
    """The defect the first real evaluation shipped with, as a test (PB-55).

    `auc` drops NaN scores with their labels, which is right — a structurally missing value is not
    a low value. But it means a feature defined on a tenth of the rows is scored on a tenth of
    them while the model is scored on all of them. The first run reported a margin of -0.002
    against `days_since_sim_swap` at 1.000, a feature defined only on accounts that had a SIM
    swap, and a margin between two numbers over different populations is not a margin.

    So `partial` is a property of the measurement and the reporting rests on it.
    """
    whole = Baseline(feature="amount_log1p", separation=0.77, rows=8000, fraud=55, covered=1.0)
    part = Baseline(feature="days_since_sim_swap", separation=1.0, rows=800, fraud=40, covered=0.1)
    assert not whole.partial
    assert part.partial
    assert part.separation > whole.separation, (
        "precondition: the partial feature must look stronger, or there is nothing to resist"
    )


@pytest.mark.req("D-08")
def test_the_report_refuses_to_subtract_a_partial_feature_from_the_model() -> None:
    """The margin is over the complete-coverage baseline, and the partial one is printed apart."""
    report = summarise(
        Evaluation(
            features=38,
            train=SegmentCounts("train", 12000, 111),
            test=SegmentCounts("test", 8000, 55),
            corpus_rows=400000,
            train_covers_days=9.0,
            model_auc=0.998,
            model_auc_error=0.004,
            recall_at_1pct_fpr=0.945,
            baseline=Baseline("amount_log1p", 0.774, 8000, 55, 1.0),
            strongest=Baseline("days_since_sim_swap", 1.0, 800, 40, 0.1),
            trivial=Baseline("amount_log1p", 0.774, 8000, 55, 1.0),
            boundaries=_boundaries(),
        )
    )
    assert "MARGIN OVER THE FEATURE      +0.224" in report
    assert "THE STRONGEST FEATURE IS NOT THE BASELINE" in report
    assert "defined on                10.0% of held-out rows" in report


def test_a_velocity_feature_is_never_called_trivial() -> None:
    """`seconds_since_last_tx` is the benchmark's second-strongest single feature.

    Calling it trivial is how a benchmark's hardest baseline gets reported as its easiest, and the
    trivial set is named by hand precisely because no property of a registry entry decides what a
    fraud team could do without a model.
    """
    assert "seconds_since_last_tx" not in TRIVIAL_FEATURES
    assert "velocity_ratio_1h_vs_30d" not in TRIVIAL_FEATURES
    assert TRIVIAL_FEATURES, "precondition: the trivial set is not empty"


def test_the_sample_spreads_across_the_period_rather_than_taking_its_tail() -> None:
    """Why the first two runs both trained on about a week whatever the corpus reached.

    Taking the last N rows of the train pool trains on the days immediately before the boundary
    however deep the corpus goes: the sample size decided the window, not the corpus. A stride
    over a pool already in timestamp order is a stratified sample over time and needs no seed.
    """
    pool = list(range(1000))
    sample = spread(pool, 10)
    assert sample == sorted(sample), "the sample must stay in timestamp order"
    assert len(sample) == 10
    assert sample[0] < 100, "the sample must start near the beginning of the period"
    assert sample[-1] > 800, "and reach near its end, which a tail sample would not"
    assert spread(pool, 5000) == pool, "asking for more than exists takes everything"
