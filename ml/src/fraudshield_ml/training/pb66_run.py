"""PB-66's run: the gate's seed-1 model on a full-volume cache, then `pb66`'s five analyses.

The model is fitted exactly as `gate_run.run` fits its reported model (`model.fit_ensemble` on the
cache's D-07 split, the same seed), so every figure here belongs to the model the gate reports. The
best single feature on main's evidence rides beside every ranking metric (PB-46, PB-66); metrics
read at 0.60 or 0.85 have no single-feature counterpart, since a lone feature has no calibrated
probability.
"""

from __future__ import annotations

import json
import math
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fraudshield_ml.training import gate, gate_run, model, pb66, smoke

#: The strongest single feature on the declared gate's rows (`m4_evaluate_gate_d8083dbc.txt`).
BASELINE_FEATURE = "velocity_ratio_1h_vs_30d"

#: § 2: windowed features look back up to 90 days, so a training row nearer the draw's start has
#: truncated history (PB-76).
TRUNCATION_DAYS = 90

#: Printed and written ahead of the fairness table, never after it (owner, 2026-10-19).
FAIRNESS_PREAMBLE = (
    "Fraud mechanisms in this benchmark are country-invariant by construction: no fraud parameter "
    "is keyed by country (test_no_fraud_parameter_is_keyed_by_country). What this report measures "
    "is therefore the generator's base rates and amount mixes per group, passed through one model, "
    "not differential treatment of groups by the model."
)
UNAVAILABLE = (
    "urban/rural: not available (the exported tables carry no urban/rural or customer-segment "
    "column, and no proxy is joined)",
    "KYC tier: not available (not in the exported tables)",
)


@dataclass(frozen=True)
class TestRows:
    """The test rows' scores and the per-row facts the analyses group by."""

    scores: Any
    baseline: Any
    labels: Any
    timestamps_us: Any
    amounts: Any
    countries: list[str]
    channels: list[str]
    transaction_rows: tuple[int, ...]
    #: -1 when the feature ranks fraud low and was negated, as the gate's `_best` orients it
    baseline_orientation: int
    #: test rows whose baseline value is missing (ranked lowest)
    baseline_missing: int
    #: § 2: training rows within 90 days of the first, whose windowed history is truncated
    truncated_training_rows: int
    training_rows: int


def _columns(cache: Path) -> dict[str, list[object]]:
    """PB-66's per-row columns for every cached row. Refuses a cache written without them."""
    import pyarrow.parquet as pq  # noqa: PLC0415 - heavy, and only this path needs it

    wanted = (smoke.CACHE_TIMESTAMP, smoke.CACHE_AMOUNT, smoke.CACHE_COUNTRY, smoke.CACHE_CHANNEL)
    present = pq.read_schema(cache).names
    missing = [k for k in wanted if k not in present]
    if missing:
        raise ValueError(
            f"{cache} has no {missing}: PB-66 needs a cache written with the timestamp and amount "
            "columns (fs-features evaluate on this commit or later)"
        )
    table = pq.read_table(cache, columns=list(wanted))
    return {k: table.column(k).to_pylist() for k in wanted}


def score(cache: Path, seed: int) -> TestRows:
    """The gate's seed-1 model, fitted as `gate_run.run` fits it, and its test rows' scores."""
    import numpy as np  # noqa: PLC0415 - heavy, and only this path needs it

    data = gate_run.load_cache(cache)
    # Everything that can refuse runs before the fit, so no test row is scored and then dropped
    # without an access line.
    columns = _columns(cache)
    feature = data.names.index(BASELINE_FEATURE)
    stamps = [int(str(v)) for v in columns[smoke.CACHE_TIMESTAMP]]
    train_stamps = [stamps[i] for i in data.split.train]
    horizon = min(train_stamps) + TRUNCATION_DAYS * pb66.MICROS_PER_DAY
    test = data.test

    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    scores = np.asarray(fitted.score([data.matrix[i] for i in test]).ensemble, dtype=float)
    labels = np.asarray([data.labels[i] for i in test], dtype=bool)
    raw = np.asarray([data.matrix[i][feature] for i in test], dtype=float)
    missing = int(np.isnan(raw).sum())
    # Oriented as the gate's `_best` orients a single feature (by its AUC's direction); a missing
    # value ranks lowest rather than poisoning the sort, and the report counts them.
    orientation = -1 if gate.auc(np.nan_to_num(raw, nan=0.0), labels) < 0.5 else 1
    baseline = np.nan_to_num(orientation * raw, nan=-np.inf)
    return TestRows(
        scores=scores,
        baseline=baseline,
        labels=labels,
        timestamps_us=np.asarray([stamps[i] for i in test], dtype=np.int64),
        amounts=np.asarray([float(str(columns[smoke.CACHE_AMOUNT][i])) for i in test]),
        countries=[str(columns[smoke.CACHE_COUNTRY][i]) for i in test],
        channels=[str(columns[smoke.CACHE_CHANNEL][i]) for i in test],
        transaction_rows=tuple(test),
        baseline_orientation=orientation,
        baseline_missing=missing,
        truncated_training_rows=sum(1 for t in train_stamps if t < horizon),
        training_rows=len(train_stamps),
    )


def _ranked(rows: TestRows, scores: Any, *, resamples: int, seed: int) -> dict[str, object]:
    """The ranking metrics for one set of scores: AUC, PR-AUC and their intervals."""
    import numpy as np  # noqa: PLC0415

    rng = np.random.default_rng(seed)
    draws = []
    for _ in range(resamples):
        i = pb66.stratified(rows.labels, rng)
        draws.append(
            (gate.auc(scores[i], rows.labels[i]), pb66.average_precision(scores[i], rows.labels[i]))
        )
    return {
        "auc": gate.auc(scores, rows.labels),
        "auc_interval": pb66.interval([d[0] for d in draws]),
        "average_precision": pb66.average_precision(scores, rows.labels),
        "average_precision_interval": pb66.interval([d[1] for d in draws]),
    }


def analyse(rows: TestRows, *, resamples: int, seed: int) -> dict[str, object]:
    """Every § 3 analysis on the model's scores, with the single feature beside the ranking ones."""
    months = pb66.month_of(rows.timestamps_us)
    buckets = pb66.by_month(rows.scores, rows.labels, months, resamples=resamples, seed=seed)
    by_name = {b.month: b for b in buckets}
    # Only the pre-registered difference: December minus November, the two full months.
    differences = (
        {"2025-12-minus-2025-11": pb66.difference(by_name["2025-12"], by_name["2025-11"])}
        if {"2025-11", "2025-12"} <= set(by_name)
        else {}
    )
    model_days = pb66.daily_budget(rows.scores, rows.labels, rows.timestamps_us)
    baseline_days = pb66.daily_budget(rows.baseline, rows.labels, rows.timestamps_us)
    model_cost = pb66.cost_curve(rows.scores, rows.labels)
    baseline_cost = pb66.cost_curve(rows.baseline, rows.labels)
    baseline_months = pb66.by_month(
        rows.baseline, rows.labels, months, resamples=resamples, seed=seed
    )
    flag = gate.at_threshold(rows.scores, rows.labels, gate.FLAG)
    return {
        # The workflow checks these against the gate's seed-1 metrics.json: the same model.
        "model_identity": {
            "auc": gate.auc(rows.scores, rows.labels),
            "recall_at_flag": flag.recall,
        },
        "training_history": {
            "training_rows": rows.training_rows,
            "within_90_days_of_the_first": rows.truncated_training_rows,
            "share": rows.truncated_training_rows / rows.training_rows,
        },
        "baseline_feature": {
            "name": BASELINE_FEATURE,
            "orientation": rows.baseline_orientation,
            "missing_on_test_rows": rows.baseline_missing,
        },
        "pr_auc": {
            "model": _ranked(rows, rows.scores, resamples=resamples, seed=seed),
            "baseline": _ranked(rows, rows.baseline, resamples=resamples, seed=seed),
        },
        "budgets": {
            "assumed": list(pb66.BUDGETS),
            "days_used": len(model_days.days),
            "first_day": pb66.day_of(model_days.days[0]) if model_days.days else None,
            "last_day": pb66.day_of(model_days.days[-1]) if model_days.days else None,
            "days_excluded": [pb66.day_of(d) for d in model_days.excluded],
            "rows_excluded": model_days.excluded_rows,
            "model": pb66.budget_report(model_days, resamples=resamples, seed=seed),
            "baseline": pb66.budget_report(baseline_days, resamples=resamples, seed=seed),
        },
        "months": {
            "model": [_bucket(b) for b in buckets],
            "baseline": [
                {
                    "month": b.month,
                    "auc": b.point["auc"],
                    "average_precision": b.point["average_precision"],
                }
                for b in baseline_months
            ],
            "differences_in_recall_at_flag": {
                k: {"point": p, "interval": i} for k, (p, i) in differences.items()
            },
        },
        "cost_curve": {
            "model": _cost(model_cost),
            "baseline": _cost(baseline_cost),
        },
        "fairness": {
            "preamble": FAIRNESS_PREAMBLE,
            "unavailable": list(UNAVAILABLE),
            "country": pb66.fairness(
                rows.scores,
                rows.labels,
                rows.amounts,
                rows.countries,
                resamples=resamples,
                seed=seed,
            ),
            "channel": pb66.fairness(
                rows.scores,
                rows.labels,
                rows.amounts,
                rows.channels,
                resamples=resamples,
                seed=seed,
            ),
            "overall": pb66.fairness(
                rows.scores,
                rows.labels,
                rows.amounts,
                ["all"] * len(rows.labels),
                resamples=resamples,
                seed=seed,
            )["all"],
        },
    }


def _bucket(b: pb66.Bucket) -> dict[str, object]:
    return {
        "month": b.month,
        "rows": b.rows,
        "frauds": b.frauds,
        **b.point,
        "intervals": b.intervals,
    }


def _cost(curve: pb66.CostCurve) -> dict[str, object]:
    return {
        "pc": list(curve.pc),
        "normalised_expected_cost": list(curve.model),
        "trivial": list(curve.trivial),
        "at_pc_0_5": curve.at(0.5),
        "not_below_trivial_between_0_01_and_0_99": curve.worse_than_trivial(),
    }


def _f(value: object, digits: int = 3) -> str:
    return (
        "n/a"
        if not isinstance(value, float | int) or math.isnan(float(value))
        else f"{value:.{digits}f}"
    )


def _ci(interval: Sequence[object], digits: int = 3) -> str:
    low, high = interval
    return f"[{_f(low, digits)}, {_f(high, digits)}]"


def summary(result: dict[str, Any], *, seed: int, resamples: int) -> list[str]:
    """The plain-text report, in the pre-registration's order, fairness preamble first."""
    pr, budgets, months = result["pr_auc"], result["budgets"], result["months"]
    lines = [
        "PB-66 — seed-1 calibrated ensemble on D-07's test period "
        f"({resamples} bootstrap resamples, seed {seed})",
        f"  baseline beside ranking metrics: {BASELINE_FEATURE} (PB-46), orientation "
        f"{result['baseline_feature']['orientation']:+d}, "
        f"{result['baseline_feature']['missing_on_test_rows']} test rows missing it",
        "",
        "2. The model (the gate's seed 1; the workflow compares these with the gate's own)",
        f"  AUC-ROC {_f(result['model_identity']['auc'], 6)}   recall at 0.60 "
        f"{_f(result['model_identity']['recall_at_flag'], 6)}",
        f"  training rows within 90 days of the first (truncated history, PB-76): "
        f"{result['training_history']['within_90_days_of_the_first']} of "
        f"{result['training_history']['training_rows']} "
        f"({_f(result['training_history']['share'], 4)})",
        "",
        "3.1 PR-AUC beside ROC",
    ]
    for who in ("model", "baseline"):
        r = pr[who]
        lines.append(
            f"  {who:8}  AUC-ROC {_f(r['auc'])} {_ci(r['auc_interval'])}   "
            f"PR-AUC {_f(r['average_precision'])} {_ci(r['average_precision_interval'])}"
        )
    lines += [
        "",
        "3.2 Precision at an ASSUMED alert budget (the SRS states none; owner, 2026-10-19)",
        f"  {budgets['days_used']} full UTC days, {budgets['first_day']} to {budgets['last_day']}; "
        f"excluded as partial: {', '.join(budgets['days_excluded'])} "
        f"({budgets['rows_excluded']} rows)",
        "  alerts at a tie are shared across the tied block (see pb66._top_k)",
        "  budget  who       precision [95% CI, by day]   ceiling  share   recall [95% CI]  "
        " alert rate  tie-decided alerts",
    ]
    for k in budgets["assumed"]:
        for who in ("model", "baseline"):
            b = budgets[who][k]
            lines.append(
                f"  {k:>6}  {who:8}  {_f(b['precision'])} {_ci(b['intervals']['precision'])}  "
                f"{_f(b['ceiling'])}   {_f(b['share_of_ceiling'], 2)}   {_f(b['recall'])} "
                f"{_ci(b['intervals']['recall'])}  {_f(b['alert_rate'], 4)}      "
                f"{b['tie_decided_alerts']}"
            )
    lines += [
        "",
        "3.3 By month of the test period (C-5) — reported as thin as it is",
        "  month    rows    frauds  AUC [95% CI]          PR-AUC   recall@0.60 [95% CI]  "
        "precision@0.60  FPR@0.60",
    ]
    for m in months["model"]:
        i = m["intervals"]
        lines.append(
            f"  {m['month']}  {m['rows']:>6}  {m['frauds']:>6}  {_f(m['auc'])} {_ci(i['auc'])}  "
            f"{_f(m['average_precision'])}    "
            f"{_f(m['recall_at_flag'])} {_ci(i['recall_at_flag'])}  "
            f"{_f(m['precision_at_flag'])}           {_f(m['fpr_at_flag'], 4)}"
        )
        lines.append(
            f"           intervals: PR-AUC {_ci(i['average_precision'])}  precision@0.60 "
            f"{_ci(i['precision_at_flag'])}  FPR@0.60 {_ci(i['fpr_at_flag'], 4)}"
        )
    for m in months["baseline"]:
        lines.append(
            f"  {m['month']}  baseline AUC {_f(m['auc'])}  PR-AUC {_f(m['average_precision'])}"
        )
    for key, d in months["differences_in_recall_at_flag"].items():
        lines.append(f"  recall@0.60 {key}: {_f(d['point'])} {_ci(d['interval'])}")
    cost = result["cost_curve"]
    lines += [
        "",
        "3.4 Drummond-Holte cost curve (normalised expected cost; no assumed costs)",
        f"  model at PC=0.5: {_f(cost['model']['at_pc_0_5'], 4)}; baseline: "
        f"{_f(cost['baseline']['at_pc_0_5'], 4)}",
        "  PC in [0.01, 0.99] where the model is not below the trivial classifier: "
        f"{cost['model']['not_below_trivial_between_0_01_and_0_99'] or 'none'}",
        "",
        "3.5 Fairness",
        f"  {FAIRNESS_PREAMBLE}",
        *(f"  {u}" for u in UNAVAILABLE),
    ]
    fairness = result["fairness"]
    header = (
        "  group          rows   legit  frauds  FPR@0.60 [95% Wilson]      "
        "FPR@0.85 [95% Wilson]      legit blocked RWF (share)   all blocked RWF"
    )
    for kind in ("country", "channel"):
        lines += ["", f"  by {kind}", header]
        for name, g in {**fairness[kind], "all": fairness["overall"]}.items():
            lines.append(
                f"  {name:12} {g['rows']:>6} {g['legitimate']:>7} {g['frauds']:>6}  "
                f"{_f(g['fpr_at_flag'], 4)} {_ci(g['intervals']['fpr_at_flag'], 4)}  "
                f"{_f(g['fpr_at_block'], 4)} {_ci(g['intervals']['fpr_at_block'], 4)}  "
                f"{_f(g['legit_blocked_amount'], 0)} ({_f(g['legit_blocked_share'], 5)})   "
                f"{_f(g['blocked_amount'], 0)}"
            )
    return lines


def _svg(
    title: str, labels: tuple[str, str], series: list[tuple[str, list[tuple[float, float]]]]
) -> str:
    """Line chart on [0, 1] x [0, 1] with one polyline per series and a legend; no diagonal."""
    from fraudshield_ml.training.gate_report import PAD, SIZE, _point  # noqa: PLC0415

    colours = ("#1f5fa8", "#c0392b", "#999")
    span = SIZE - 2 * PAD
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" '
        f'viewBox="0 0 {SIZE} {SIZE}" font-family="sans-serif" font-size="11">',
        f"<title>{title}</title>",
        f'<rect x="{PAD}" y="{PAD}" width="{span}" height="{span}" fill="none" stroke="#444"/>',
        f'<text x="{SIZE / 2}" y="{PAD - 16}" text-anchor="middle" font-size="13">{title}</text>',
        f'<text x="{SIZE / 2}" y="{SIZE - 12}" text-anchor="middle">{labels[0]}</text>',
        f'<text x="14" y="{SIZE / 2}" text-anchor="middle" '
        f'transform="rotate(-90 14 {SIZE / 2})">{labels[1]}</text>',
    ]
    for n, (name, points) in enumerate(series):
        colour = colours[n % len(colours)]
        path = " ".join(f"{px:.1f},{py:.1f}" for px, py in (_point(x, y) for x, y in points))
        parts.append(f'<polyline points="{path}" fill="none" stroke="{colour}" stroke-width="2"/>')
        parts.append(f'<text x="{PAD + 6}" y="{PAD + 14 + 13 * n}" fill="{colour}">{name}</text>')
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def _thin(points: list[tuple[float, float]], keep: int = 400) -> list[tuple[float, float]]:
    step = max(1, len(points) // keep)
    return points[::step] + points[-1:]


def write(result: dict[str, Any], rows: TestRows, out: Path, scores_out: Path | None) -> list[Path]:
    """metrics.json, report.txt, the PR and cost figures, and optionally the per-row scores."""
    figures = out / "figures"
    figures.mkdir(parents=True, exist_ok=True)
    precision, recall = pb66.pr_curve(rows.scores, rows.labels)
    b_precision, b_recall = pb66.pr_curve(rows.baseline, rows.labels)
    cost = result["cost_curve"]
    written = {
        out / "metrics.json": json.dumps(_strict(result), indent=2, sort_keys=True) + "\n",
        figures / "pr.svg": _svg(
            "Precision-recall, calibrated ensemble",
            ("recall", "precision"),
            [
                ("ensemble", _thin(list(zip(recall.tolist(), precision.tolist(), strict=True)))),
                (
                    BASELINE_FEATURE,
                    _thin(list(zip(b_recall.tolist(), b_precision.tolist(), strict=True))),
                ),
            ],
        ),
        figures / "cost_curve.svg": _svg(
            "Cost curve (Drummond-Holte)",
            ("probability cost PC(+)", "normalised expected cost"),
            [
                (
                    "ensemble",
                    list(
                        zip(
                            cost["model"]["pc"],
                            cost["model"]["normalised_expected_cost"],
                            strict=True,
                        )
                    ),
                ),
                (
                    BASELINE_FEATURE,
                    list(
                        zip(
                            cost["baseline"]["pc"],
                            cost["baseline"]["normalised_expected_cost"],
                            strict=True,
                        )
                    ),
                ),
                ("trivial", list(zip(cost["model"]["pc"], cost["model"]["trivial"], strict=True))),
            ],
        ),
    }
    if scores_out is not None:
        scores_out.parent.mkdir(parents=True, exist_ok=True)
        lines = ["cache_row,score,baseline,label,timestamp_us,amount_rwf,country,channel"]
        lines += [
            f"{r},{s!r},{b!r},{int(y)},{t},{a!r},{c},{ch}"
            for r, s, b, y, t, a, c, ch in zip(
                rows.transaction_rows,
                rows.scores.tolist(),
                rows.baseline.tolist(),
                rows.labels.tolist(),
                rows.timestamps_us.tolist(),
                rows.amounts.tolist(),
                rows.countries,
                rows.channels,
                strict=True,
            )
        ]
        written[scores_out] = "\n".join(lines) + "\n"
    for path, text in written.items():
        path.write_text(text, encoding="utf-8")
    return list(written)


def _strict(value: object) -> object:
    """The result as strict JSON: NaN becomes null, tuples lists, numpy scalars Python ones."""
    if isinstance(value, dict):
        return {str(k): _strict(v) for k, v in value.items()}
    if isinstance(value, list | tuple):
        return [_strict(v) for v in value]
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, float) and math.isnan(value):
        return None
    return value
