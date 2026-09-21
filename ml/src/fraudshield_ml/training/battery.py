"""M4's experiments, all refitting on one cached feature matrix.

Part E.5 asks for more than a headline: calibration, explanations, baselines, ablations, and
breakdowns by country and channel. They share a shape — fit something on the train rows, score
the test rows, report it beside the same baselines the headline is reported against — so they
share this module, and they all read a feature matrix computed once (`fs-features evaluate
--cache`). The feature pass is the only expensive part of M4; a battery that recomputed it per
experiment would make the cheap work look expensive.

**No new dependency.** SHAP comes from XGBoost's own `pred_contribs`, which is exact TreeSHAP for
trees, rather than from the `shap` package; calibration is Platt scaling fitted with plain
gradient descent. Both are small enough to read, and a dependency added for one number is a
dependency the whole project then carries.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

from fraudshield_ml.metrics.single_feature import auc, auc_standard_error
from fraudshield_ml.training.smoke import recall_at_fpr


@dataclass(frozen=True)
class Scored:
    """A model's scores on a set of rows, with the counts that say what they can support."""

    name: str
    auc: float
    error: float
    recall_at_1pct_fpr: float
    rows: int
    fraud: int

    @property
    def interval(self) -> float:
        return 1.96 * self.error


def score(name: str, scores: Sequence[float], labels: Sequence[bool]) -> Scored:
    """One row of any table here. The fraud count travels with the figure, always.

    Every breakdown below slices the held-out rows, and a slice of a slice runs out of positives
    quickly: the whole point of reporting per country and per channel is lost if the reader cannot
    see that one cell rests on nine fraud rows.
    """
    value = auc(scores, labels)
    positives = sum(1 for y in labels if y)
    return Scored(
        name=name,
        auc=value,
        error=auc_standard_error(value, positives, len(labels) - positives),
        recall_at_1pct_fpr=recall_at_fpr(scores, labels, 0.01),
        rows=len(labels),
        fraud=positives,
    )


# --- calibration ----------------------------------------------------------------------------


def _logit(p: float) -> float:
    p = min(max(p, 1e-9), 1 - 1e-9)
    return math.log(p / (1 - p))


def fit_platt(
    scores: Sequence[float], labels: Sequence[bool], *, steps: int = 2000
) -> tuple[float, float]:
    """Platt scaling: `sigmoid(a * logit(p) + b)`, fitted by gradient descent on log loss.

    Fitted on D-07's **calibration** period, which is the tail of validation and is neither fitted
    on nor scored. Two parameters rather than isotonic regression: with a few hundred fraud rows
    an isotonic fit has a step per positive and calibrates itself onto the noise.
    """
    x = [_logit(p) for p in scores]
    y = [1.0 if label else 0.0 for label in labels]
    a, b = 1.0, 0.0
    rate = 0.1
    for _ in range(steps):
        ga = gb = 0.0
        for xi, yi in zip(x, y, strict=True):
            predicted = 1.0 / (1.0 + math.exp(-(a * xi + b)))
            error = predicted - yi
            ga += error * xi
            gb += error
        a -= rate * ga / len(x)
        b -= rate * gb / len(x)
    return a, b


def apply_platt(scores: Sequence[float], fit: tuple[float, float]) -> list[float]:
    a, b = fit
    return [1.0 / (1.0 + math.exp(-(a * _logit(p) + b))) for p in scores]


def brier(scores: Sequence[float], labels: Sequence[bool]) -> float:
    """Mean squared error of the probabilities.

    Falls when calibration improves, which AUC cannot see: AUC is a property of the ordering and
    a model can rank perfectly while predicting 0.9 for a 1% event.
    """
    return sum((p - (1.0 if y else 0.0)) ** 2 for p, y in zip(scores, labels, strict=True)) / len(
        scores
    )


@dataclass(frozen=True)
class Bin:
    lower: float
    upper: float
    rows: int
    predicted: float
    observed: float


def reliability(scores: Sequence[float], labels: Sequence[bool], bins: int = 10) -> list[Bin]:
    """Predicted against observed, in equal-width probability bins.

    Equal-width rather than equal-count, because the question a reliability table answers is "when
    the model says 0.9, how often is it right?" — and equal-count bins at a 1% base rate put
    almost every row in one bin and answer nothing.
    """
    buckets: list[list[tuple[float, bool]]] = [[] for _ in range(bins)]
    for p, y in zip(scores, labels, strict=True):
        buckets[min(int(p * bins), bins - 1)].append((p, y))
    table = []
    for i, bucket in enumerate(buckets):
        if not bucket:
            continue
        table.append(
            Bin(
                lower=i / bins,
                upper=(i + 1) / bins,
                rows=len(bucket),
                predicted=sum(p for p, _ in bucket) / len(bucket),
                observed=sum(1 for _, y in bucket if y) / len(bucket),
            )
        )
    return table


def expected_calibration_error(table: Sequence[Bin]) -> float:
    """Row-weighted mean gap between predicted and observed. ML-GATE-11's quantity."""
    total = sum(b.rows for b in table)
    return sum(b.rows * abs(b.predicted - b.observed) for b in table) / total if total else math.nan


# --- explanations ---------------------------------------------------------------------------


def mean_absolute_shap(
    contributions: Sequence[Sequence[float]], names: Sequence[str]
) -> list[tuple[str, float]]:
    """Mean |SHAP| per feature, descending — the standard global importance summary.

    The last column of XGBoost's `pred_contribs` is the bias term and is dropped here, which is
    also the check that the matrix has the shape this function assumes.
    """
    if not contributions:
        return []
    width = len(contributions[0])
    if width != len(names) + 1:
        raise ValueError(
            f"expected {len(names)} features plus a bias column, got {width}. A silent mismatch "
            "would attribute every feature's importance to its neighbour"
        )
    totals = [0.0] * len(names)
    for row in contributions:
        for i in range(len(names)):
            totals[i] += abs(row[i])
    return sorted(
        ((names[i], totals[i] / len(contributions)) for i in range(len(names))),
        key=lambda pair: pair[1],
        reverse=True,
    )


def additivity_error(contributions: Sequence[Sequence[float]], margins: Sequence[float]) -> float:
    """Largest gap between a row's summed contributions and the model's own margin for it.

    SHAP's defining property is that the parts sum to the whole. A value that violates it is not a
    slightly wrong explanation, it is a different quantity wearing the name — so this is measured
    rather than assumed, and it is the M4 gate's own check.
    """
    worst = 0.0
    for row, margin in zip(contributions, margins, strict=True):
        worst = max(worst, abs(sum(row) - margin))
    return worst
