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
    """Row-weighted mean gap between predicted and observed. ML-GATE-11's quantity.

    **Row-weighted is what makes it nearly useless on its own here.** At a 0.9% base rate a good
    model puts 99% of rows in the lowest bin, predicting ~0.001 and observing ~0.001, so the
    weighted mean is dominated by rows nobody will ever act on and reads 0.0005 whatever the
    model does at the top. Report it with `decision_region` below, which is where the number
    matters: an alert is raised or not raised on the confident end.
    """
    total = sum(b.rows for b in table)
    return sum(b.rows * abs(b.predicted - b.observed) for b in table) / total if total else math.nan


#: Where a decision is actually made. Scores below this never reach an analyst under any alert
#: budget this project contemplates, so calibration there is arithmetic rather than a property
#: anyone relies on.
DECISION_THRESHOLD = 0.60


@dataclass(frozen=True)
class Region:
    """Calibration over one slice of the score range, with the counts it rests on."""

    threshold: float
    bins: list[Bin]
    ece: float
    brier: float
    rows: int
    fraud: int


@dataclass(frozen=True)
class Calibration:
    """What the calibration section reports: the whole range, and the part decisions use."""

    bins: list[Bin]
    ece: float
    brier_before: float
    brier_after: float
    region: Region


def decision_region(
    scores: Sequence[float], labels: Sequence[bool], threshold: float = DECISION_THRESHOLD
) -> Region:
    """Reliability, ECE and Brier restricted to scores at or above `threshold`.

    Returns the bins, the ECE over them, the Brier score over them, and the rows and fraud the
    region holds — because a decision-region metric over forty rows is a different claim from the
    same number over four hundred, and the restriction is what makes that easy to forget.
    """
    kept = [(p, y) for p, y in zip(scores, labels, strict=True) if p >= threshold]
    if not kept:
        return Region(threshold, [], math.nan, math.nan, 0, 0)
    region_scores = [p for p, _ in kept]
    region_labels = [y for _, y in kept]
    table = reliability(region_scores, region_labels)
    return Region(
        threshold=threshold,
        bins=table,
        ece=expected_calibration_error(table),
        brier=brier(region_scores, region_labels),
        rows=len(kept),
        fraud=sum(1 for y in region_labels if y),
    )


def calibrate(raw: Sequence[float], mapped: Sequence[float], labels: Sequence[bool]) -> Calibration:
    """Everything the calibration section needs, measured once."""
    bins = reliability(mapped, labels)
    return Calibration(
        bins=bins,
        ece=expected_calibration_error(bins),
        brier_before=brier(raw, labels),
        brier_after=brier(mapped, labels),
        region=decision_region(mapped, labels),
    )


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


# --- generalisation to an unseen fraud shape -------------------------------------------------

#: The sub-variant the generator places only in the test period (D-08). A model trained on the
#: train period has, by construction, never seen it.
NOVEL_VARIANT = "novel_esim_delayed_drain"


@dataclass(frozen=True)
class VariantResult:
    """One fraud shape's detectability, against the same legitimate rows and the same threshold."""

    variant: str
    fraud: int
    detected: int
    auc: float
    error: float

    @property
    def recall(self) -> float:
        return self.detected / self.fraud if self.fraud else math.nan

    @property
    def interval(self) -> float:
        return 1.96 * self.error

    @property
    def recall_interval(self) -> tuple[float, float]:
        """Wilson 95% interval on `recall`, because it is a proportion and `detected` is often
        small enough that the point estimate alone invites exactly the over-reading this project
        has already paid for once today: a rate quoted without the count behind it reads as more
        settled than it is. Wilson rather than the normal approximation because it stays inside
        [0, 1] and does not degenerate at `recall` near 0 or 1, both of which happen here.
        """
        if self.fraud == 0:
            return math.nan, math.nan
        z = 1.96
        n, k = self.fraud, self.detected
        p = k / n
        denom = 1 + z**2 / n
        centre = p + z**2 / (2 * n)
        spread = z * math.sqrt(p * (1 - p) / n + z**2 / (4 * n**2))
        # Exact Wilson bounds lie in [0, 1]; at k = 0 or k = n the subtraction lands a rounding
        # error outside, which prints as -0.000.
        return max(0.0, (centre - spread) / denom), min(1.0, (centre + spread) / denom)


def by_variant(
    scores: Sequence[float],
    labels: Sequence[bool],
    variants: Sequence[str],
    fpr: float = 0.01,
) -> tuple[list[VariantResult], float]:
    """Each fraud shape scored against **the same** legitimate rows at **one** threshold.

    This is the comparison the novel-variant hold-out exists to make, and it only means anything
    if both halves are held fixed. Giving each variant its own threshold would let a shape look
    detectable because its own negatives happened to be easy; giving each its own negatives would
    compare two different problems. So the threshold is chosen once, from all legitimate rows, at
    the budget an alert queue would actually run.

    Returns the per-variant results and the threshold, because a recall without the operating
    point that produced it is not reproducible.
    """
    legitimate = sorted((s for s, y in zip(scores, labels, strict=True) if not y), reverse=True)
    if not legitimate:
        return [], math.nan
    index = min(len(legitimate) - 1, max(0, int(len(legitimate) * fpr) - 1))
    threshold = legitimate[index]

    kinds = sorted(
        {v for v, y in zip(variants, labels, strict=True) if y and v},
        key=lambda v: (v != NOVEL_VARIANT, v),
    )
    results = []
    for kind in kinds:
        positives = [s for s, y, v in zip(scores, labels, variants, strict=True) if y and v == kind]
        if not positives:
            continue
        paired = positives + [s for s, y in zip(scores, labels, strict=True) if not y]
        paired_labels = [True] * len(positives) + [False] * (len(paired) - len(positives))
        value = auc(paired, paired_labels)
        results.append(
            VariantResult(
                variant=kind,
                fraud=len(positives),
                detected=sum(1 for s in positives if s > threshold),
                auc=value,
                error=auc_standard_error(value, len(positives), len(paired) - len(positives)),
            )
        )
    return results, threshold
