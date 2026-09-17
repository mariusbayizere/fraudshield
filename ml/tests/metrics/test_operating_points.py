from __future__ import annotations

import math

import pytest

from fraudshield_ml.metrics.operating_points import (
    OperatingPointError,
    implied_operating_point,
    precision_ceiling_at_fpr,
)

SRS_BASE_RATE = 0.0087


@pytest.mark.req("D-01", "ML-GATE-02")
def test_precision_ceiling_at_one_percent_fpr_matches_hand_calculation() -> None:
    # 0.0087 / (0.0087 + 0.01 * 0.9913) = 0.0087 / 0.018613
    assert precision_ceiling_at_fpr(SRS_BASE_RATE, 0.01) == pytest.approx(0.467415, abs=1e-6)


@pytest.mark.req("D-01", "ML-GATE-02")
def test_srs_precision_gate_is_unattainable_at_srs_base_rate() -> None:
    assert precision_ceiling_at_fpr(SRS_BASE_RATE, 0.01) < 0.720


@pytest.mark.req("D-01")
def test_ceiling_is_one_at_zero_fpr_and_base_rate_at_full_fpr() -> None:
    assert precision_ceiling_at_fpr(0.3, 0.0) == 1.0
    assert precision_ceiling_at_fpr(0.3, 1.0) == pytest.approx(0.3)


@pytest.mark.req("D-02", "ML-GATE-03", "ML-GATE-04")
def test_implied_operating_point_for_srs_recall_and_f1_targets() -> None:
    point = implied_operating_point(f1=0.80, recall=0.88, base_rate=SRS_BASE_RATE)
    # P = 0.704 / 0.96; FPR = 0.88 * 0.0087 * (1 - P) / (P * 0.9913)
    assert point.precision == pytest.approx(0.733333, abs=1e-6)
    assert point.false_positive_rate == pytest.approx(0.002809, abs=1e-6)


@pytest.mark.req("D-02")
def test_implied_point_round_trips_through_f1_definition() -> None:
    point = implied_operating_point(f1=0.62, recall=0.9, base_rate=0.05)
    f1 = 2 * point.precision * 0.9 / (point.precision + 0.9)
    assert math.isclose(f1, 0.62, rel_tol=1e-12)


@pytest.mark.parametrize(
    ("f1", "recall"),
    [(0.0, 0.5), (0.9, 0.4), (0.95, 0.5)],
)
def test_unreachable_f1_recall_pairs_are_rejected(f1: float, recall: float) -> None:
    with pytest.raises(OperatingPointError):
        implied_operating_point(f1=f1, recall=recall, base_rate=0.01)


@pytest.mark.parametrize(
    ("base_rate", "fpr"), [(0.0, 0.01), (1.0, 0.01), (0.01, -0.1), (0.01, 1.5)]
)
def test_out_of_domain_rates_are_rejected(base_rate: float, fpr: float) -> None:
    with pytest.raises(OperatingPointError):
        precision_ceiling_at_fpr(base_rate, fpr)


@pytest.mark.req("D-02")
def test_precision_of_exactly_one_is_accepted_with_zero_fpr() -> None:
    # F1 = 2/3 with recall 1/2 requires P = (2/3 * 1/2) / (1 - 2/3) = 1 exactly.
    point = implied_operating_point(f1=2 / 3, recall=0.5, base_rate=0.01)
    assert point.precision == pytest.approx(1.0, abs=1e-12)
    assert point.false_positive_rate == pytest.approx(0.0, abs=1e-12)


@pytest.mark.req("D-02")
def test_precision_just_above_one_is_rejected() -> None:
    # F1 = 0.7 with recall 0.5 would need P = 0.35 / 0.3 = 1.1667.
    with pytest.raises(OperatingPointError, match="unreachable"):
        implied_operating_point(f1=0.7, recall=0.5, base_rate=0.01)
