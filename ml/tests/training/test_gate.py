"""The gate metrics, each checked against an independent reference (E.5, D-01, D-02)."""

from __future__ import annotations

import math

import numpy as np
import pytest
from scipy.stats import norm

from fraudshield_ml.metrics.single_feature import auc as reference_auc
from fraudshield_ml.training import battery, gate
from fraudshield_ml.training.smoke import recall_at_fpr as reference_recall_at_fpr

pytestmark = pytest.mark.req("TEST-14")


def _sample(n: int = 4000, seed: int = 2) -> tuple[np.ndarray, np.ndarray]:
    rng = np.random.default_rng(seed)
    labels = rng.random(n) < 0.05
    # Rounded to two decimals so that ties are common, which is where AUC and FPR code goes wrong.
    scores = np.round(np.clip(rng.normal(0.3, 0.2, n) + 0.35 * labels, 0.0, 1.0), 2)
    return scores, labels


@pytest.mark.req("ML-GATE-01")
def test_auc_matches_the_reference_implementation_under_ties() -> None:
    scores, labels = _sample()
    assert gate.auc(scores, labels) == pytest.approx(
        reference_auc(list(scores), list(labels)), abs=1e-12
    )


@pytest.mark.req("ML-GATE-02", "D-01")
def test_recall_at_one_percent_fpr_matches_the_tie_aware_reference() -> None:
    scores, labels = _sample()
    assert gate.recall_at_fpr(scores, labels) == pytest.approx(
        reference_recall_at_fpr(list(scores), list(labels), 0.01), abs=1e-12
    )


@pytest.mark.req("D-01")
def test_the_precision_ceiling_is_d01s_own_worked_figure() -> None:
    """D-01: at a 0.87% base rate the ceiling at 1% FPR is about 0.467."""
    assert gate.precision_ceiling(0.0087) == pytest.approx(0.467, abs=0.001)


@pytest.mark.req("ML-GATE-03", "ML-GATE-04", "ML-GATE-05", "ML-GATE-06", "D-02")
def test_operating_points_are_half_open_and_consistent() -> None:
    """A score exactly at 0.60 is flagged (E.6), and FNR is 1 - recall at the same threshold."""
    scores = np.array([0.60, 0.59, 0.85, 0.84, 0.10, 0.90])
    labels = np.array([True, True, False, False, False, True])
    flag = gate.at_threshold(scores, labels, gate.FLAG)
    assert flag.recall == pytest.approx(2 / 3), "the positive at exactly 0.60 must be flagged"
    assert flag.fnr == pytest.approx(1 - flag.recall)
    assert flag.precision == pytest.approx(2 / 4)
    assert flag.f1 == pytest.approx(2 * 0.5 * (2 / 3) / (0.5 + 2 / 3))
    block = gate.at_threshold(scores, labels, gate.BLOCK)
    assert block.fpr == pytest.approx(1 / 3), "the negative at exactly 0.85 is blocked"


@pytest.mark.req("ML-GATE-11")
def test_the_calibration_measures_match_the_batterys_where_they_overlap() -> None:
    scores, labels = _sample()
    bins = battery.reliability(list(scores), list(labels), bins=gate.ECE_BINS)
    assert gate.ece_equal_width(scores, labels) == pytest.approx(
        battery.expected_calibration_error(bins), abs=1e-12
    )
    assert gate.brier(scores, labels) == pytest.approx(
        battery.brier(list(scores), list(labels)), abs=1e-12
    )


@pytest.mark.req("ML-GATE-11")
def test_equal_mass_ece_uses_fifteen_bins_of_equal_count() -> None:
    """Perfectly calibrated within each equal-count bin gives zero; a constant offset gives it."""
    scores = np.repeat(np.linspace(0.02, 0.98, 15), 100)
    labels = np.zeros(1500, dtype=bool)
    for b, p in enumerate(np.linspace(0.02, 0.98, 15)):
        labels[b * 100 : b * 100 + round(p * 100)] = True
    assert gate.ece_equal_mass(scores, labels) == pytest.approx(0.0, abs=0.005)
    assert gate.ece_equal_mass(np.clip(scores + 0.01, 0, 1), labels) == pytest.approx(
        0.01, abs=0.006
    )


def _slow_delong(labels: np.ndarray, first: np.ndarray, second: np.ndarray) -> float:
    """DeLong's variance from its definition, O(m·n) pairs, as the reference for the fast form."""
    pos, neg = np.flatnonzero(labels), np.flatnonzero(~labels)

    def components(s: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        psi = (s[pos][:, None] > s[neg][None, :]) + 0.5 * (s[pos][:, None] == s[neg][None, :])
        return psi.mean(axis=1), psi.mean(axis=0)

    (v10a, v01a), (v10b, v01b) = components(first), components(second)
    s10 = np.cov(np.vstack([v10a, v10b]))
    s01 = np.cov(np.vstack([v01a, v01b]))
    var = (s10[0, 0] + s10[1, 1] - 2 * s10[0, 1]) / len(pos) + (
        s01[0, 0] + s01[1, 1] - 2 * s01[0, 1]
    ) / len(neg)
    z = (v10a.mean() - v10b.mean()) / math.sqrt(var)
    return float(2 * norm.sf(abs(z)))


def test_delong_matches_its_definition() -> None:
    scores, labels = _sample(600)
    rng = np.random.default_rng(9)
    other = np.round(scores + rng.normal(0, 0.15, len(scores)), 2)
    auc1, auc2, p = gate.delong(labels, scores, other)
    assert auc1 == pytest.approx(gate.auc(scores, labels), abs=1e-12)
    assert auc2 == pytest.approx(gate.auc(other, labels), abs=1e-12)
    assert p == pytest.approx(_slow_delong(labels, scores, other), abs=1e-9)
    assert gate.delong(labels, scores, scores)[2] == 1.0, "a classifier does not differ from itself"


def test_the_bootstrap_keeps_the_base_rate_and_brackets_the_point_estimate() -> None:
    scores, labels = _sample()
    channels = np.array(["MOBILE_MONEY", "USSD", "AGENT_BANKING", "CARD"] * 1000, dtype=object)
    rows = gate.Rows(scores, labels, channels, np.ones(len(scores), dtype=bool))
    point = gate.metrics(rows)
    intervals = gate.bootstrap(rows, resamples=200, seed=1)
    for key in ("ML-GATE-01", "ML-GATE-02", "ML-GATE-07", "ML-GATE-11"):
        lo, hi = intervals[key]
        assert lo <= point[key] <= hi, key
        assert hi > lo, f"{key}: a zero-width interval means the resampling did nothing"
    lo, hi = intervals["precision_ceiling_at_1pct_fpr"]
    assert lo == pytest.approx(hi), (
        "stratified resamples keep the base rate, so the ceiling is fixed"
    )


def test_a_gate_spec_fails_on_nan_and_respects_direction() -> None:
    at_least = gate.Spec("x", "x", 0.9, True)
    below = gate.Spec("y", "y", 0.05, False)
    assert at_least.passes(0.9)
    assert not at_least.passes(0.89)
    assert below.passes(0.049)
    assert not below.passes(0.05)
    assert not at_least.passes(math.nan), "an unmeasured metric is not a passed one"
