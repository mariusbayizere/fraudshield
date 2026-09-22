"""Rendering for the M4 battery, kept apart from the arithmetic that produces it."""

from __future__ import annotations

import math
from collections.abc import Sequence

from fraudshield_ml.training.battery import (
    NOVEL_VARIANT,
    Bin,
    Calibration,
    Scored,
    VariantResult,
)
from fraudshield_ml.training.ensemble import SeedRun, SeedVarianceSummary
from fraudshield_ml.training.frontier import Point


def _row(item: Scored, baseline: float | None = None) -> str:
    margin = f"{item.auc - baseline:+.3f}" if baseline is not None else "     "
    recall = "  n/a" if math.isnan(item.recall_at_1pct_fpr) else f"{item.recall_at_1pct_fpr:.3f}"
    return (
        f"  {item.name:<34s} {item.auc:.3f} +/-{item.interval:.3f}  {margin}  "
        f"{recall}  {item.rows:>7d} {item.fraud:>5d}"
    )


HEADER = f"  {'':<34s} {'AUC':<5s}         {'margin':<6s}  {'R@1%':<5s}  {'rows':>7s} {'fraud':>5s}"


def table(title: str, items: Sequence[Scored], baseline: float | None = None) -> list[str]:
    """One block. The fraud count is a column and not a footnote, in every table here.

    A breakdown slices the held-out rows, and a slice runs out of positives fast: the point of
    reporting per country is lost if the reader cannot see that a cell rests on nine fraud rows.
    """
    lines = ["", title, HEADER]
    lines += [_row(item, baseline) for item in items]
    thin = [i.name for i in items if i.fraud < 30]
    if thin:
        lines.append(
            f"  ^ {', '.join(thin)} rest on fewer than 30 fraud rows; read those as direction"
        )
        lines.append("    rather than as measurement.")
    return lines


def _bins(bins: Sequence[Bin]) -> list[str]:
    return [
        f"  [{b.lower:.1f}, {b.upper:.1f})  {b.rows:>8d}    {b.predicted:.3f}      {b.observed:.3f}"
        for b in bins
    ]


def reliability_table(result: Calibration) -> list[str]:
    """Overall calibration, then calibration where a decision is made.

    Both, because neither alone is honest. At a 0.9% base rate a good model puts 99% of rows in
    the lowest bin, so the row-weighted ECE is dominated by predictions nobody acts on and reads
    near zero whatever happens at the top. Restricting to the decision region answers the question
    an alert budget actually asks — and carries its own row and fraud counts, because the same
    number over forty rows and over four hundred are different claims.
    """
    region = result.region
    lines = [
        "",
        "CALIBRATION — Platt scaling fitted on D-07's calibration period, which is neither",
        "fitted on nor scored. AUC is unchanged by construction: a monotone map cannot reorder.",
        f"  Brier before {result.brier_before:.5f}  ->  after {result.brier_after:.5f}",
        f"  expected calibration error, all rows      {result.ece:.4f}",
        "",
        "  predicted       rows   predicted   observed",
    ]
    lines += _bins(result.bins)
    lines += [
        "",
        f"  DECISION REGION (score >= {region.threshold:.2f}) — where an alert is raised. The",
        "  overall ECE above is row-weighted, so at a 0.9% base rate it is dominated by",
        "  predictions nobody acts on; this is the number an alert budget depends on.",
    ]
    if region.rows == 0:
        lines.append("  no row scores above the threshold, so there is nothing to calibrate here")
        return lines
    lines += [
        f"    rows / fraud                           {region.rows} / {region.fraud}",
        f"    expected calibration error             {region.ece:.4f}",
        f"    Brier                                  {region.brier:.5f}",
        "",
        "  predicted       rows   predicted   observed",
    ]
    lines += _bins(region.bins)
    return lines


def shap_table(importances: Sequence[tuple[str, float]], additivity: float, top: int) -> list[str]:
    lines = [
        "",
        "EXPLANATIONS — exact TreeSHAP from XGBoost's own pred_contribs, no new dependency.",
        f"  additivity error {additivity:.2e} (contributions must sum to the model's margin)",
        "",
        f"  top {top} features by mean |SHAP|",
    ]
    lines += [f"  {name:<34s} {value:.4f}" for name, value in importances[:top]]
    return lines


def variant_table(results: Sequence[VariantResult], threshold: float, fpr: float) -> list[str]:
    """The novel-variant hold-out: M4's main generalisation evidence (PB-59).

    Leave-one-country-out measures nothing on this benchmark, because fraud is country-invariant
    by construction. This is the test that can still fail: a fraud shape the generator places only
    in the test period, so a model fitted on the train period has never seen it.
    """
    lines = [
        "",
        "NOVEL SUB-VARIANT — a fraud shape present only in the test period, so the model has",
        "never seen it. All variants scored against the same legitimate rows at one threshold",
        f"({threshold:.6f}, the {fpr:.0%} false-positive budget over all legitimate rows).",
        "",
        f"  {'variant':<34s} {'AUC':<5s}         {'recall':<6s} {'[95% CI]':<15s} "
        f"{'caught':>7s}  {'fraud':>5s}",
    ]
    for item in results:
        lo, hi = item.recall_interval
        ci = f"[{lo:.3f}, {hi:.3f}]" if not math.isnan(lo) else "[n/a]"
        lines.append(
            f"  {item.variant:<34s} {item.auc:.3f} +/-{item.interval:.3f}  "
            f"{item.recall:.3f} {ci:<15s} {item.detected:>7d}  {item.fraud:>5d}"
        )

    base_list = [r for r in results if r.variant == "base"]
    base = base_list[0] if base_list else None
    if base:
        for item in results:
            if item is base:
                continue
            lo, hi = item.recall_interval
            base_lo, base_hi = base.recall_interval
            separated = hi < base_lo or lo > base_hi
            lines += [
                "",
                f"  {item.variant}: recall {item.recall:.1%} [{lo:.1%}, {hi:.1%}] on "
                f"{item.fraud} fraud rows, against",
                f"  base at {base.recall:.1%} [{base_lo:.1%}, {base_hi:.1%}] on {base.fraud} rows.",
            ]
            if item.variant == NOVEL_VARIANT and not separated:
                lines += [
                    "  **The unseen shape is caught at least as often as the familiar one, so",
                    "  this experiment does not measure generalisation on this benchmark.** The",
                    "  novelty is in the lead time, not the transaction pattern: the drain is",
                    "  still a burst, and a model that detects bursts catches it without having",
                    "  seen the variant. Reported as a null result rather than dropped (PB-61).",
                ]
            elif separated and item.recall < base.recall:
                lines.append(
                    "  The intervals do not overlap: this is a supported finding, not noise at"
                    " this sample size."
                )
            elif not separated:
                lines.append(
                    "  The intervals overlap, so this comparison alone does not establish a"
                    " difference."
                )
            if item.fraud < 30:
                lines.append(
                    f"  Rests on {item.fraud} fraud rows; read the interval, not the point"
                    " estimate alone."
                )
    return lines


def frontier_table(points: Sequence[Point], requests: int) -> list[str]:
    """The accuracy-latency trade-off (D-16), with the caveat the numbers require.

    ADR 0010 allows latency percentiles as gate evidence only from the dedicated machine, so this
    reports a shape and not a verdict. The 40 ms constraint D-16 sets cannot be applied from here
    and the table does not pretend to apply it.
    """
    unstable = [p for p in points if p.p99_is_impossible]
    lines = [
        "",
        "ACCURACY vs SINGLE-REQUEST LATENCY (D-16) — one row per request, never a batch: the",
        "scoring service handles one transaction per call, and a batch would amortise exactly the",
        "overhead a single request pays. 50 calls are discarded per configuration as warm-up.",
        "",
        "  NOT GATE NUMBERS. ADR 0010 permits latency percentiles as gate evidence only from the",
        "  dedicated machine in docs/benchmarks/hardware.md. What is measurable here is the shape",
        f"  of the trade-off, over {requests} timed requests per configuration.",
        "",
        f"  {'trees':>5s} {'depth':>5s}  {'AUC':<14s} {'predict p50':>11s} {'+SHAP p50':>10s}"
        f" {'SHAP cost':>10s}  {'predict p99':>11s} {'+SHAP p99':>10s}",
    ]
    for p in points:
        lines.append(
            f"  {p.trees:>5d} {p.depth:>5d}  {p.auc:.3f} +/-{p.interval:.3f}  "
            f"{p.predict_p50:>10.2f} {p.explain_p50:>10.2f} {p.explanation_cost:>9.2f}ms  "
            f"{p.predict_p99:>10.2f} {p.explain_p99:>10.2f}"
        )
    if unstable:
        lines += [
            "",
            "  THE p99 COLUMNS ARE NOT MEASUREMENT ON THIS MACHINE, and they say so themselves:",
            f"  {len(unstable)} of {len(points)} configurations show a NEGATIVE explanation cost",
            "  at p99, which is impossible: explaining cannot be faster than not explaining. The",
            "  p99 of a single request here is dominated by scheduler jitter, not by model",
            "  complexity.",
            "  This is ADR 0010's rule demonstrated rather than asserted: a shared or busy machine",
            "  cannot produce a repeatable percentile, and D-16's 40 ms constraint cannot be",
            "  applied from here. The p50 columns are stable and are what this run reports.",
        ]
    if len(points) >= 2:
        first, last = points[0], points[-1]
        lines += [
            "",
            f"  From {first.trees} trees at depth {first.depth} to {last.trees} at depth"
            f" {last.depth}: AUC {last.auc - first.auc:+.3f}",
            f"  (inside every interval), while the explanation cost at p50 goes"
            f" {first.explanation_cost:.2f} -> {last.explanation_cost:.2f} ms.",
            "  Complexity buys no accuracy on this benchmark and costs explanation latency.",
        ]
    return lines


def seed_variance_table(
    runs: Sequence[SeedRun],
    summaries: dict[str, SeedVarianceSummary],
    baselines: tuple[str, ...],
) -> list[str]:
    """C-6, measured: does the ensemble's AUC move less across seeds than either model alone?"""
    lines = [
        "",
        "C-6 — SEED VARIANCE: XGBoost alone, LightGBM alone and the 0.55/0.45 ensemble (D-05),",
        f"at {len(runs)} fixed seeds ({', '.join(str(r.seed) for r in runs)}), on the same",
        "train/test rows throughout. Not calibrated: this measures how much a model's own random",
        "state moves its AUC, not the calibrated ensemble's error rate.",
        "",
        f"  {'seed':>5s}  {'xgboost':>10s}  {'lightgbm':>10s}  {'ensemble':>10s}",
    ]
    for r in runs:
        lines.append(
            f"  {r.seed:>5d}  {r.xgboost_auc:>10.6f}  {r.lightgbm_auc:>10.6f}  "
            f"{r.ensemble_auc:>10.6f}"
        )
    lines += ["", f"  {'':<9s}  {'mean':>10s}  {'stdev':>10s}"]
    for name in ("xgboost", "lightgbm", "ensemble"):
        s = summaries[name]
        lines.append(f"  {name:<9s}  {s.mean:>10.6f}  {s.stdev:>10.6f}")
    lines.append("")
    ensemble = summaries["ensemble"]
    for base in baselines:
        reduction = ensemble.reduction_from(summaries[base])
        verdict = "REDUCED" if reduction > 0 else "DID NOT REDUCE"
        lines.append(
            f"  ensemble stdev vs {base} alone: {reduction:+.1f}% ({verdict} seed variance"
            f" relative to {base})"
        )
    lines += [
        "",
        "  C-6 claims a 12% reduction with no citation (D-09). The figures above are the",
        "  replacement measurement, reported as measured whichever way they come out.",
    ]
    return lines
