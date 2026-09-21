"""Rendering for the M4 battery, kept apart from the arithmetic that produces it."""

from __future__ import annotations

import math
from collections.abc import Sequence

from fraudshield_ml.training.battery import Bin, Scored


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


def reliability_table(bins: Sequence[Bin], ece: float, before: float, after: float) -> list[str]:
    lines = [
        "",
        "CALIBRATION — Platt scaling fitted on D-07's calibration period, which is neither",
        "fitted on nor scored. AUC is unchanged by construction: a monotone map cannot reorder.",
        f"  Brier before {before:.5f}  ->  after {after:.5f}",
        f"  expected calibration error {ece:.4f}",
        "",
        "  predicted       rows   predicted   observed",
    ]
    for b in bins:
        lines.append(
            f"  [{b.lower:.1f}, {b.upper:.1f})  {b.rows:>8d}    {b.predicted:.3f}      "
            f"{b.observed:.3f}"
        )
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
