"""The accuracy-latency frontier, including exact TreeSHAP (D-16).

D-16's resolution makes tree complexity a **constrained hyperparameter**: the search rejects any
configuration whose single-request p99, ensemble plus exact TreeSHAP on flagged rows, exceeds
40 ms. It then asks for the trade-off curve, and calls it a strong research figure.

**Why this is the most defensible thing M4 measures.** The benchmark is easy: one velocity feature
reaches 0.89, four disjoint feature groups each reach 0.85 alone (PB-60), and removing a whole
country from training costs nothing (PB-59). Every accuracy claim is therefore weakened by the
dataset. A latency curve is not: the cost of explaining a decision is a property of the model and
the SHAP algorithm, not of how separable the data happens to be. A tree twice as deep costs what
it costs whether the fraud is easy or hard to find.

**The absolute numbers here are not gate numbers.** ADR 0010 is explicit that latency percentiles
may only be quoted as gate evidence from the dedicated machine in `docs/benchmarks/hardware.md`,
because a shared or busy machine's percentiles are not repeatable. What this produces is the
*shape* of the trade-off — how accuracy and cost move together — measured on one machine and
labelled with it. The 40 ms constraint cannot be applied from here.
"""

from __future__ import annotations

import time
from collections.abc import Sequence
from dataclasses import dataclass

from fraudshield_ml.metrics.single_feature import auc, auc_standard_error


@dataclass(frozen=True)
class Point:
    """One tree configuration: what it costs and what it buys."""

    trees: int
    depth: int
    auc: float
    error: float
    #: Milliseconds for a **single** request, which is the quantity D-16 constrains. Batch
    #: throughput would flatter every configuration equally and answer a different question.
    predict_p50: float
    predict_p99: float
    #: The same, with exact TreeSHAP. The gap between the two is the price of an explanation.
    explain_p50: float
    explain_p99: float

    @property
    def interval(self) -> float:
        return 1.96 * self.error

    @property
    def explanation_cost(self) -> float:
        """Milliseconds TreeSHAP adds at p99 — the number D-16's constraint is really about."""
        return self.explain_p99 - self.predict_p99


def percentile(values: Sequence[float], fraction: float) -> float:
    """Nearest-rank percentile. No interpolation: a latency percentile is an observed request."""
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round(fraction * len(ordered)) - 1))
    return ordered[index]


def time_single_requests(
    call: object, rows: Sequence[Sequence[float]], repeats: int
) -> list[float]:
    """Milliseconds per single-row call, one row at a time.

    One row at a time on purpose. Scoring a batch amortises the per-call overhead that dominates a
    single request, and the scoring service handles one transaction per request — so a batch
    measurement would report a number no request will ever experience.
    """
    timings = []
    for _ in range(repeats):
        for row in rows:
            start = time.perf_counter()
            call([row])  # type: ignore[operator]
            timings.append((time.perf_counter() - start) * 1000.0)
    return timings


@dataclass(frozen=True)
class Timings:
    """Single-request milliseconds for one configuration, with and without the explanation."""

    predict: Sequence[float]
    explain: Sequence[float]


def summarise(
    trees: int,
    depth: int,
    scores: Sequence[float],
    labels: Sequence[bool],
    timings: Timings,
) -> Point:
    value = auc(scores, labels)
    positives = sum(1 for y in labels if y)
    return Point(
        trees=trees,
        depth=depth,
        auc=value,
        error=auc_standard_error(value, positives, len(labels) - positives),
        predict_p50=percentile(timings.predict, 0.50),
        predict_p99=percentile(timings.predict, 0.99),
        explain_p50=percentile(timings.explain, 0.50),
        explain_p99=percentile(timings.explain, 0.99),
    )
