"""The M4 gate metrics, as D-01 and D-02 correct them, with the uncertainty E.5 asks for.

ML-GATE-01 … ML-GATE-11 are measured here; ML-GATE-12 (latency) and ML-GATE-13 (shadow delta)
need the scoring service and belong to M5.

**Operating points (D-02).** The flag threshold is 0.60 (MEDIUM and above) and the block threshold
0.85, on the calibrated `ensemble_score`. Tiers are half-open (E.6): a score *at* 0.60 is flagged.
Recall, F1 and FNR are measured at the flag threshold and FPR at the block threshold.

**D-01.** "Precision at 1% FPR ≥ 0.72" is impossible at this base rate, so the gate metric is recall
at 1% FPR; precision there is still reported beside its theoretical ceiling for the test set's
own base rate, which no model can exceed.

**Uncertainty.** Every metric carries a 95% percentile interval from a stratified bootstrap:
positives and negatives are resampled separately, so each resample keeps the test set's base rate
and a rare-class metric is never computed on a resample that happens to hold few positives.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass
from typing import Any

FLAG = 0.60
BLOCK = 0.85
FPR_BUDGET = 0.01
ECE_BINS = 15
RESAMPLES = 1000
CHANNELS = {"ML-GATE-07": "MOBILE_MONEY", "ML-GATE-08": "USSD", "ML-GATE-09": "AGENT_BANKING"}


@dataclass(frozen=True)
class Spec:
    id: str
    name: str
    threshold: float
    #: True if the metric must be at least the threshold; False if it must be below it.
    at_least: bool

    def passes(self, value: float) -> bool:
        if math.isnan(value):
            return False
        return value >= self.threshold if self.at_least else value < self.threshold


SPECS = (
    Spec("ML-GATE-01", "AUC-ROC", 0.940, True),
    Spec("ML-GATE-02", "Recall at 1% FPR (D-01)", 0.720, True),
    Spec("ML-GATE-03", "Recall at 0.60", 0.880, True),
    Spec("ML-GATE-04", "F1 at 0.60", 0.800, True),
    Spec("ML-GATE-05", "FPR at 0.85", 0.015, False),
    Spec("ML-GATE-06", "FNR at 0.60", 0.120, False),
    Spec("ML-GATE-07", "MOBILE_MONEY AUC-ROC", 0.920, True),
    Spec("ML-GATE-08", "USSD AUC-ROC", 0.900, True),
    Spec("ML-GATE-09", "AGENT_BANKING AUC-ROC", 0.910, True),
    Spec("ML-GATE-10", "SHAP coverage, HIGH and MEDIUM", 1.000, True),
    Spec("ML-GATE-11", "ECE, 15 equal-mass bins", 0.050, False),
)


def _np() -> Any:
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    return np


def auc(scores: Any, labels: Any) -> float:
    """Mann-Whitney AUC with mid-ranks, so tied scores count half."""
    from scipy.stats import rankdata  # noqa: PLC0415 - heavy, and only this path needs it

    positives = int(labels.sum())
    negatives = len(labels) - positives
    if positives == 0 or negatives == 0:
        return math.nan
    ranks = rankdata(scores)
    return float((ranks[labels].sum() - positives * (positives + 1) / 2) / (positives * negatives))


def recall_at_fpr(scores: Any, labels: Any, fpr: float = FPR_BUDGET) -> float:
    """Positives strictly above the threshold that `fpr` of negatives reach (smoke.py's rule)."""
    np = _np()
    negatives = np.sort(scores[~labels])[::-1]
    positives = scores[labels]
    if len(negatives) == 0 or len(positives) == 0:
        return math.nan
    index = min(len(negatives) - 1, max(0, int(len(negatives) * fpr) - 1))
    return float((positives > negatives[index]).mean())


def at_fpr_budget(scores: Any, labels: Any, fpr: float = FPR_BUDGET) -> tuple[float, float]:
    """Precision and the **realised** false-positive rate at the `fpr` budget's threshold.

    Rows strictly above the threshold are flagged (`recall_at_fpr`'s rule), so ties on it are not
    charged. A calibrated score has many — isotonic regression is piecewise constant — and the
    realised rate can fall well below the budget. Precision is only comparable with D-01's ceiling
    at the rate it was actually measured at, so both are returned.
    """
    np = _np()
    negatives = np.sort(scores[~labels])[::-1]
    if len(negatives) == 0:
        return math.nan, math.nan
    threshold = negatives[min(len(negatives) - 1, max(0, int(len(negatives) * fpr) - 1))]
    flagged = scores > threshold
    realised = float((flagged & ~labels).sum() / len(negatives))
    precision = float(labels[flagged].mean()) if flagged.any() else math.nan
    return precision, realised


def precision_ceiling(base_rate: float, fpr: float = FPR_BUDGET) -> float:
    """D-01: the best precision any model can reach at `fpr`, with perfect recall."""
    return base_rate / (base_rate + fpr * (1.0 - base_rate))


@dataclass(frozen=True)
class OperatingPoint:
    threshold: float
    precision: float
    recall: float
    f1: float
    fpr: float
    fnr: float


def at_threshold(scores: Any, labels: Any, threshold: float) -> OperatingPoint:
    """Everything at one threshold, flagging scores at or above it (E.6's half-open tiers)."""
    flagged = scores >= threshold
    tp = int((flagged & labels).sum())
    fp = int((flagged & ~labels).sum())
    positives = int(labels.sum())
    negatives = len(labels) - positives
    precision = tp / (tp + fp) if tp + fp else math.nan
    recall = tp / positives if positives else math.nan
    f1 = (
        2 * precision * recall / (precision + recall)
        if not (math.isnan(precision) or math.isnan(recall)) and precision + recall
        else math.nan
    )
    return OperatingPoint(
        threshold=threshold,
        precision=precision,
        recall=recall,
        f1=f1,
        fpr=fp / negatives if negatives else math.nan,
        fnr=1.0 - recall if not math.isnan(recall) else math.nan,
    )


def ece_equal_mass(scores: Any, labels: Any, bins: int = ECE_BINS) -> float:
    """ECE over bins holding equal numbers of rows (E.5: the gate's definition)."""
    np = _np()
    order = np.argsort(scores, kind="stable")
    total = 0.0
    for chunk in np.array_split(order, bins):
        if len(chunk):
            total += len(chunk) * abs(float(scores[chunk].mean()) - float(labels[chunk].mean()))
    return total / len(scores)


def ece_equal_width(scores: Any, labels: Any, bins: int = ECE_BINS) -> float:
    """ECE over equal-width probability bins, reported beside the gate's equal-mass figure."""
    np = _np()
    index = np.minimum((scores * bins).astype(int), bins - 1)
    total = 0.0
    for b in range(bins):
        mask = index == b
        if mask.any():
            total += int(mask.sum()) * abs(float(scores[mask].mean()) - float(labels[mask].mean()))
    return total / len(scores)


def brier(scores: Any, labels: Any) -> float:
    return float(((scores - labels.astype(float)) ** 2).mean())


@dataclass(frozen=True)
class Rows:
    """The scored test rows as arrays: score, label, channel, and whether it is explained."""

    scores: Any
    labels: Any
    channels: Any
    covered: Any

    def take(self, index: Any) -> Rows:
        return Rows(
            self.scores[index], self.labels[index], self.channels[index], self.covered[index]
        )


def rows_of(
    scores: Sequence[float],
    labels: Sequence[bool],
    channels: Sequence[str],
    covered: Sequence[bool],
) -> Rows:
    np = _np()
    return Rows(
        np.asarray(scores, dtype=float),
        np.asarray(labels, dtype=bool),
        np.asarray(channels, dtype=object),
        np.asarray(covered, dtype=bool),
    )


def metrics(rows: Rows) -> dict[str, float]:
    """Every gate metric and its companions, on one set of rows."""
    scores, labels, channels, covered = rows.scores, rows.labels, rows.channels, rows.covered
    flag = at_threshold(scores, labels, FLAG)
    block = at_threshold(scores, labels, BLOCK)
    flagged = scores >= FLAG
    budget_precision, realised = at_fpr_budget(scores, labels)
    values = {
        "ML-GATE-01": auc(scores, labels),
        "ML-GATE-02": recall_at_fpr(scores, labels),
        "ML-GATE-03": flag.recall,
        "ML-GATE-04": flag.f1,
        "ML-GATE-05": block.fpr,
        "ML-GATE-06": flag.fnr,
        "ML-GATE-10": float(covered[flagged].mean()) if flagged.any() else math.nan,
        "ML-GATE-11": ece_equal_mass(scores, labels),
        "precision_at_1pct_fpr": budget_precision,
        "realised_fpr_at_1pct_budget": realised,
        "precision_ceiling_at_1pct_fpr": precision_ceiling(float(labels.mean())),
        "precision_ceiling_at_realised_fpr": precision_ceiling(float(labels.mean()), realised),
        "precision_at_flag": flag.precision,
        "precision_at_block": block.precision,
        "recall_at_block": block.recall,
        "f1_at_block": block.f1,
        "fpr_at_flag": flag.fpr,
        "ece_equal_width": ece_equal_width(scores, labels),
        "brier": brier(scores, labels),
    }
    for gate, channel in CHANNELS.items():
        mask = channels == channel
        values[gate] = auc(scores[mask], labels[mask]) if mask.any() else math.nan
    return values


def bootstrap(
    rows: Rows, *, resamples: int = RESAMPLES, seed: int
) -> dict[str, tuple[float, float]]:
    """95% percentile intervals from a bootstrap stratified by class."""
    np = _np()
    rng = np.random.default_rng(seed)
    positive = np.flatnonzero(rows.labels)
    negative = np.flatnonzero(~rows.labels)
    draws: dict[str, list[float]] = {}
    for _ in range(resamples):
        index = np.concatenate(
            [
                positive[rng.integers(0, len(positive), len(positive))],
                negative[rng.integers(0, len(negative), len(negative))],
            ]
        )
        for key, value in metrics(rows.take(index)).items():
            draws.setdefault(key, []).append(value)
    intervals = {}
    for key, values in draws.items():
        defined = np.array([v for v in values if not math.isnan(v)])
        intervals[key] = (
            (float(np.percentile(defined, 2.5)), float(np.percentile(defined, 97.5)))
            if len(defined)
            else (math.nan, math.nan)
        )
    return intervals


def delong(labels: Any, first: Any, second: Any) -> tuple[float, float, float]:
    """DeLong's test for two correlated AUCs on the same rows: (auc1, auc2, two-sided p).

    The fast form (Sun and Xu, 2014): each AUC's structural components from mid-ranks, then the
    variance of their difference from the components' covariance across the two classifiers.
    """
    from scipy.stats import norm, rankdata  # noqa: PLC0415 - heavy, and only this path needs it

    np = _np()
    pos = np.flatnonzero(labels)
    neg = np.flatnonzero(~labels)
    m, n = len(pos), len(neg)
    v01, v10, aucs = [], [], []
    for scores in (first, second):
        tx = rankdata(scores[pos])
        ty = rankdata(scores[neg])
        tz = rankdata(np.concatenate([scores[pos], scores[neg]]))
        aucs.append(float(tz[:m].sum() / (m * n) - (m + 1) / (2 * n)))
        v01.append((tz[:m] - tx) / n)
        v10.append(1.0 - (tz[m:] - ty) / m)
    sx = np.cov(np.array(v01))
    sy = np.cov(np.array(v10))
    variance = (sx[0, 0] + sx[1, 1] - 2 * sx[0, 1]) / m + (sy[0, 0] + sy[1, 1] - 2 * sy[0, 1]) / n
    if variance <= 0:
        return aucs[0], aucs[1], 1.0 if aucs[0] == aucs[1] else 0.0
    z = (aucs[0] - aucs[1]) / math.sqrt(variance)
    return aucs[0], aucs[1], float(2 * norm.sf(abs(z)))
