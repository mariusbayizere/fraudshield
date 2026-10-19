"""PB-66's evaluations on one set of scored test rows.

The design is `docs/research/preregistration_pb66_full_volume.md`, section 3.

Each analysis is a function of arrays, so each can be tested on rows small enough to reason about:

* PR-AUC beside ROC (§ 3.1): average precision, as scikit-learn defines it, and the curve.
* Precision at a fixed alert budget (§ 3.2): each full UTC day's top k scores are that day's alerts.
  The budgets are **assumed**, not required (the SRS states none), and the precision ceiling — that
  day's frauds over k — is reported beside every precision, since a budget above the day's fraud
  count caps precision whatever the model does.
* Performance by month of the test period (§ 3.3, C-5): three buckets, as thin as they are.
* The Drummond-Holte cost curve (§ 3.4): normalised expected cost, minimised over thresholds, at
  each probability-cost value, beside the trivial classifier's min(PC, 1 - PC). Parameter-free:
  no invented cost enters it. Every value lies in [0, 1] by construction, and `cost_curve` checks
  that it does, because an earlier frontier in this project once produced a negative cost.
* The fairness report (§ 3.5): false-positive rates and blocked amounts per group. Fraud
  mechanisms are country-invariant by construction, so what this measures is the generator's base
  rates and amount mixes per group through one model, not differential treatment by the model.

Intervals are 95% percentile bootstraps: stratified by class for row-level metrics, as the gate's
are, and clustered by day where the metric is a per-day one.
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from fraudshield_ml.training import gate

#: § 3.2's assumed budgets, alerts a day for the draw (it has no institution column). 10 is below
#: the draw's ~15 frauds a day, so it is the one that binds (owner, 2026-10-19).
BUDGETS = (10, 50, 100, 200)
#: § 3.4's probability-cost grid.
COST_GRID = 101
RESAMPLES = gate.RESAMPLES
MICROS_PER_DAY = 86_400_000_000
EPOCH = datetime(1970, 1, 1, tzinfo=UTC)


def _np() -> Any:
    import numpy as np  # noqa: PLC0415 - heavy, and only these paths need it

    return np


def interval(draws: Sequence[float]) -> tuple[float, float]:
    """The 95% percentile interval of bootstrap draws, ignoring undefined ones."""
    np = _np()
    defined = np.array([d for d in draws if not math.isnan(d)], dtype=float)
    if not len(defined):
        return (math.nan, math.nan)
    return (float(np.percentile(defined, 2.5)), float(np.percentile(defined, 97.5)))


def stratified(labels: Any, rng: Any) -> Any:
    """One class-stratified resample of row positions, as `gate.bootstrap` draws them."""
    np = _np()
    # A class with no rows (a group without fraud, say) contributes none rather than failing.
    parts = [
        rows[rng.integers(0, len(rows), len(rows))]
        for rows in (np.flatnonzero(labels), np.flatnonzero(~labels))
        if len(rows)
    ]
    return np.concatenate(parts)


# --- § 3.1 PR-AUC ---------------------------------------------------------------------------------


def pr_curve(scores: Any, labels: Any) -> tuple[Any, Any]:
    """Precision and recall at each distinct score, highest first; tied scores flag together."""
    np = _np()
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels, dtype=bool)
    order = np.argsort(-scores, kind="stable")
    ranked, hits = scores[order], labels[order]
    true_positives = np.cumsum(hits)
    flagged = np.arange(1, len(ranked) + 1)
    # The last position of each run of equal scores: a threshold flags the whole run or none of it.
    last = np.flatnonzero(np.r_[ranked[1:] != ranked[:-1], True])
    positives = max(int(hits.sum()), 1)
    return true_positives[last] / flagged[last], true_positives[last] / positives


def average_precision(scores: Any, labels: Any) -> float:
    """Sum over thresholds of (recall gained) x (precision there): scikit-learn's definition."""
    np = _np()
    labels = np.asarray(labels, dtype=bool)
    if not labels.any():
        return math.nan
    precision, recall = pr_curve(scores, labels)
    gained = np.diff(np.r_[0.0, recall])
    return float((gained * precision).sum())


# --- § 3.2 Alert budgets --------------------------------------------------------------------------


@dataclass(frozen=True)
class Days:
    """Per full day: the rows, the frauds, and what each budget's alerts caught."""

    days: tuple[int, ...]
    rows: Any
    frauds: Any
    #: budget -> per-day (alerts, frauds caught, alerts decided by a tie at the cut). Caught is
    #: fractional where a block of tied scores straddles the cut: see `_top_k`.
    caught: dict[int, tuple[Any, Any, Any]]
    excluded: tuple[int, ...]
    #: rows on the excluded days, so the report can say what the full-day rule set aside
    excluded_rows: int


def full_days(timestamps_us: Any) -> tuple[Any, tuple[int, ...]]:
    """Each row's UTC day, and the days kept: every day present except the first and the last.

    The test period starts at 22:02 on its first day and ends part-way through its last (D-07's
    boundary and the end of the draw), so both are partial; § 3.2 counts full days only. The
    report prints the first and last day kept, so a period that happened to start or end at
    midnight would show it.
    """
    np = _np()
    day = np.asarray(timestamps_us, dtype=np.int64) // MICROS_PER_DAY
    present = sorted({int(d) for d in np.unique(day)})
    kept = tuple(present[1:-1])
    return day, kept


def _top_k(scores: Any, labels: Any, k: int) -> tuple[int, float, int]:
    """Alerts, frauds caught and tie-decided alerts when the top k scores of a day are alerted.

    Calibrated scores tie heavily (isotonic regression maps ranges of raw scores to one value),
    so the k-th alert often falls inside a block of equal scores. Choosing which tied rows to
    alert by row order would choose by time of day, since the cache is in time order. Instead
    every row above the block is alerted and the block's remaining slots are shared evenly: its
    frauds count in proportion, which is the expectation over a random choice within the block.
    """
    np = _np()
    if k >= len(scores):
        return len(scores), float(labels.sum()), 0
    kth = np.sort(scores)[::-1][k - 1]
    above = scores > kth
    block = scores == kth
    slots = k - int(above.sum())
    share = slots / int(block.sum())
    caught = float(labels[above].sum()) + share * float(labels[block].sum())
    return k, caught, slots if share < 1 else 0


def daily_budget(
    scores: Any, labels: Any, timestamps_us: Any, budgets: Sequence[int] = BUDGETS
) -> Days:
    """Each full day's top-k scores are its alerts; ties at the cut share the remaining slots."""
    np = _np()
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels, dtype=bool)
    day, kept = full_days(timestamps_us)
    present = sorted({int(d) for d in np.unique(day)})
    rows, frauds = [], []
    caught: dict[int, tuple[list[int], list[float], list[int]]] = {k: ([], [], []) for k in budgets}
    for d in kept:
        index = np.flatnonzero(day == d)
        rows.append(len(index))
        frauds.append(int(labels[index].sum()))
        for k in budgets:
            alerts, hits, tied = _top_k(scores[index], labels[index], k)
            caught[k][0].append(alerts)
            caught[k][1].append(hits)
            caught[k][2].append(tied)
    excluded = tuple(d for d in present if d not in kept)
    return Days(
        days=kept,
        rows=np.asarray(rows),
        frauds=np.asarray(frauds),
        caught={
            k: (np.asarray(a), np.asarray(h), np.asarray(t)) for k, (a, h, t) in caught.items()
        },
        excluded=excluded,
        excluded_rows=int(np.isin(day, excluded).sum()),
    )


def _budget_point(days: Days, k: int, pick: Any) -> dict[str, float]:
    """One budget's pooled figures over the days in `pick` (repeats allowed, for the bootstrap)."""
    np = _np()
    alerts, hits, _ = days.caught[k]
    alerts, hits = alerts[pick], hits[pick]
    frauds, rows = days.frauds[pick], days.rows[pick]
    total_alerts = int(alerts.sum())
    return {
        "precision": float(hits.sum() / total_alerts) if total_alerts else math.nan,
        "recall": float(hits.sum() / frauds.sum()) if frauds.sum() else math.nan,
        "ceiling": float(np.minimum(frauds, k).sum() / total_alerts) if total_alerts else math.nan,
        "alert_rate": float(total_alerts / rows.sum()) if rows.sum() else math.nan,
    }


def budget_report(
    days: Days, *, resamples: int = RESAMPLES, seed: int
) -> dict[int, dict[str, object]]:
    """Each budget's precision, recall, ceiling and alert rate, with day-clustered intervals."""
    np = _np()
    rng = np.random.default_rng(seed)
    everything = np.arange(len(days.days))
    picks = [rng.integers(0, len(everything), len(everything)) for _ in range(resamples)]
    report: dict[int, dict[str, object]] = {}
    for k in days.caught:
        point = _budget_point(days, k, everything)
        draws = [_budget_point(days, k, pick) for pick in picks]
        report[k] = {
            **point,
            "tie_decided_alerts": int(days.caught[k][2].sum()),
            "share_of_ceiling": point["precision"] / point["ceiling"]
            if point["ceiling"]
            else math.nan,
            "intervals": {m: interval([d[m] for d in draws]) for m in point},
        }
    return report


# --- § 3.3 Months ---------------------------------------------------------------------------------


def month_of(timestamps_us: Any) -> list[str]:
    """Each row's UTC calendar month, as YYYY-MM, from exact integer microseconds."""
    return [(EPOCH + timedelta(microseconds=int(t))).strftime("%Y-%m") for t in timestamps_us]


def day_of(day: int) -> str:
    """A day number from `full_days` as a UTC date."""
    return (EPOCH + timedelta(days=day)).strftime("%Y-%m-%d")


def _bucket_metrics(scores: Any, labels: Any) -> dict[str, float]:
    flag = gate.at_threshold(scores, labels, gate.FLAG)
    return {
        "auc": gate.auc(scores, labels) if labels.any() and (~labels).any() else math.nan,
        "average_precision": average_precision(scores, labels),
        "recall_at_flag": flag.recall,
        "precision_at_flag": flag.precision,
        "fpr_at_flag": flag.fpr,
    }


@dataclass
class Bucket:
    month: str
    rows: int
    frauds: int
    point: dict[str, float]
    intervals: dict[str, tuple[float, float]]
    #: the bootstrap draws of recall at 0.60, kept for the difference between two months
    recall_draws: list[float] = field(default_factory=list)


def by_month(
    scores: Any, labels: Any, months: Sequence[str], *, resamples: int = RESAMPLES, seed: int
) -> list[Bucket]:
    np = _np()
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels, dtype=bool)
    months_array = np.asarray(months, dtype=object)
    buckets = []
    for offset, month in enumerate(sorted(set(months))):
        index = np.flatnonzero(months_array == month)
        s, y = scores[index], labels[index]
        rng = np.random.default_rng(seed + offset)
        draws = (
            [_bucket_metrics(s[i], y[i]) for i in (stratified(y, rng) for _ in range(resamples))]
            if y.any() and (~y).any()
            else []
        )
        buckets.append(
            Bucket(
                month=month,
                rows=len(index),
                frauds=int(y.sum()),
                point=_bucket_metrics(s, y),
                intervals={m: interval([d[m] for d in draws]) for m in _bucket_metrics(s, y)},
                recall_draws=[d["recall_at_flag"] for d in draws],
            )
        )
    return buckets


def difference(later: Bucket, earlier: Bucket) -> tuple[float, tuple[float, float]]:
    """Later month's recall at 0.60 minus the earlier's, with an interval from paired draws.

    The two buckets are resampled independently, so pairing draw i with draw i is a valid draw of
    the difference.
    """
    point = later.point["recall_at_flag"] - earlier.point["recall_at_flag"]
    if not later.recall_draws or not earlier.recall_draws:
        # A bucket with one class has no draws; the difference then has no interval either.
        return point, (math.nan, math.nan)
    draws = [a - b for a, b in zip(later.recall_draws, earlier.recall_draws, strict=True)]
    return point, interval(draws)


# --- § 3.4 Cost curve -----------------------------------------------------------------------------


@dataclass(frozen=True)
class CostCurve:
    pc: tuple[float, ...]
    model: tuple[float, ...]
    trivial: tuple[float, ...]

    def worse_than_trivial(self, low: float = 0.01, high: float = 0.99) -> list[float]:
        """The probability-cost values in [low, high] where the model is not below trivial."""
        return [
            p
            for p, m, t in zip(self.pc, self.model, self.trivial, strict=True)
            if low <= p <= high and m >= t
        ]

    def at(self, pc: float) -> float:
        return self.model[min(range(len(self.pc)), key=lambda i: abs(self.pc[i] - pc))]


def cost_curve(scores: Any, labels: Any, points: int = COST_GRID) -> CostCurve:
    """Drummond and Holte's lower envelope: min over thresholds of FNR*PC + FPR*(1 - PC).

    Every threshold between distinct scores is tried, plus "flag everything" and "flag nothing",
    so the trivial classifiers are in the envelope and the curve can never rise above them.
    """
    np = _np()
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels, dtype=bool)
    positives, negatives = int(labels.sum()), int((~labels).sum())
    if not positives or not negatives:
        raise ValueError("a cost curve needs both classes")
    order = np.argsort(-scores, kind="stable")
    ranked, hits = scores[order], labels[order]
    last = np.flatnonzero(np.r_[ranked[1:] != ranked[:-1], True])
    tp = np.r_[0, np.cumsum(hits)[last]]
    fp = np.r_[0, np.cumsum(~hits)[last]]
    fnr = 1.0 - tp / positives
    fpr = fp / negatives
    pc = np.linspace(0.0, 1.0, points)
    model = np.min(np.outer(pc, fnr) + np.outer(1.0 - pc, fpr), axis=1)
    trivial = np.minimum(pc, 1.0 - pc)
    if model.min() < -1e-12 or model.max() > 1.0 + 1e-12:
        raise AssertionError(f"normalised expected cost left [0, 1]: {model.min()}, {model.max()}")
    return CostCurve(tuple(map(float, pc)), tuple(map(float, model)), tuple(map(float, trivial)))


# --- § 3.5 Fairness -------------------------------------------------------------------------------


def _group_point(scores: Any, labels: Any, amounts: Any) -> dict[str, float]:
    """One group's false positives at 0.60 and 0.85 and its blocked amounts."""
    np = _np()
    legit = ~labels
    blocked = scores >= gate.BLOCK
    legit_amount = float(amounts[legit].sum())
    wrongly = float(amounts[legit & blocked].sum())
    return {
        "fpr_at_flag": float((scores[legit] >= gate.FLAG).mean()) if legit.any() else math.nan,
        "fpr_at_block": float(blocked[legit].mean()) if legit.any() else math.nan,
        "legit_blocked_amount": wrongly,
        "legit_blocked_share": wrongly / legit_amount if legit_amount else math.nan,
        "blocked_amount": float(amounts[blocked].sum()),
        "false_positives_at_flag": float(int((scores[legit] >= gate.FLAG).sum())),
        "false_positives_at_block": float(int(np.count_nonzero(blocked & legit))),
    }


def wilson(successes: int, trials: int, z: float = 1.959963984540054) -> tuple[float, float]:
    """The Wilson score interval for a proportion.

    Used for per-group false-positive rates rather than a percentile bootstrap: with a handful of
    false positives, often none, a bootstrap gives [0, 0] for a group with no false positive, and
    § 3.5's rule would then call every such group "different" from the overall rate.
    """
    if not trials:
        return (math.nan, math.nan)
    p = successes / trials
    denominator = 1 + z * z / trials
    centre = (p + z * z / (2 * trials)) / denominator
    half = z * math.sqrt(p * (1 - p) / trials + z * z / (4 * trials * trials)) / denominator
    # Exact at the edges: computed, 0 of n gives a lower bound of about 1e-19 rather than 0.
    low = 0.0 if successes == 0 else max(0.0, centre - half)
    high = 1.0 if successes == trials else min(1.0, centre + half)
    return (low, high)


def fairness(  # noqa: PLR0913 - the rows' four facts and the bootstrap's two settings
    scores: Any,
    labels: Any,
    amounts: Any,
    groups: Sequence[str],
    *,
    resamples: int = RESAMPLES,
    seed: int,
) -> dict[str, dict[str, object]]:
    """Per group: counts, FPR at 0.60 and 0.85, and blocked amounts.

    FPRs carry Wilson intervals (see `wilson`); amounts carry stratified bootstrap intervals.
    """
    np = _np()
    scores = np.asarray(scores, dtype=float)
    labels = np.asarray(labels, dtype=bool)
    amounts = np.asarray(amounts, dtype=float)
    groups_array = np.asarray(groups, dtype=object)
    report: dict[str, dict[str, object]] = {}
    for offset, name in enumerate(sorted(set(groups))):
        index = np.flatnonzero(groups_array == name)
        s, y, a = scores[index], labels[index], amounts[index]
        rng = np.random.default_rng(seed + offset)
        draws = [
            _group_point(s[i], y[i], a[i]) for i in (stratified(y, rng) for _ in range(resamples))
        ]
        point = _group_point(s, y, a)
        legitimate = int((~y).sum())
        intervals = {m: interval([d[m] for d in draws]) for m in point}
        intervals["fpr_at_flag"] = wilson(int(point["false_positives_at_flag"]), legitimate)
        intervals["fpr_at_block"] = wilson(int(point["false_positives_at_block"]), legitimate)
        report[name] = {
            "rows": len(index),
            "legitimate": legitimate,
            "frauds": int(y.sum()),
            **point,
            "intervals": intervals,
        }
    return report
