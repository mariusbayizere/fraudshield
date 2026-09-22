"""The gate evaluation's outputs (E.5 item 9): a summary, `metrics.json`, LaTeX tables, figures.

Figures are written as SVG by hand, as `docs/research/figures/power_curve.py` does, rather than
through a plotting dependency: two line charts do not justify one.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

from fraudshield_ml.training.gate_run import Report, seed_summary


def _f(value: float, digits: int = 3) -> str:
    return "n/a" if math.isnan(value) else f"{value:.{digits}f}"


def _ci(interval: tuple[float, float], digits: int = 3) -> str:
    return f"[{_f(interval[0], digits)}, {_f(interval[1], digits)}]"


def summary(report: Report) -> list[str]:
    """The printed gate summary, with the single-feature floor beside the headline on purpose."""
    passed = [g for g in report.gates if g.passed]
    failed = [g for g in report.gates if not g.passed]
    counts = "  ".join(f"{k} {n:,}/{f:,}" for k, (n, f) in report.counts.items())
    lines = [
        "M4 GATE — D-05's calibrated ensemble on D-07's test period, thresholds from D-01/D-02.",
        f"  floor: strongest single feature {report.floor[0]} at {_f(report.floor[1])} on the "
        "same rows (PB-46)",
        f"  rows/fraud  {counts}",
        f"  XGBoost {report.xgboost_rounds} rounds, LightGBM {report.lightgbm_rounds}, "
        f"scale_pos_weight {report.scale_pos_weight:.1f}, seed {report.seed}, "
        f"{report.resamples} stratified bootstrap resamples",
        "",
        f"  {'gate':<11s} {'metric':<32s} {'value':>7s}  {'95% CI':<16s} "
        f"{'threshold':>11s}  result",
    ]
    for g in report.gates:
        rule = f"{'>=' if g.spec.at_least else '<'} {g.spec.threshold:g}"
        lines.append(
            f"  {g.spec.id:<11s} {g.spec.name:<32s} {_f(g.value):>7s}  {_ci(g.interval):<16s} "
            f"{rule:>11s}  {'PASS' if g.passed else 'FAIL'}"
        )
    c = report.companions
    lines += [
        "",
        f"  at the 1% FPR budget's threshold the realised FPR is "
        f"{_f(c['realised_fpr_at_1pct_budget'][0], 4)} (ties are not charged); precision there "
        f"{_f(c['precision_at_1pct_fpr'][0])} {_ci(c['precision_at_1pct_fpr'][1])}",
        f"  against D-01's ceiling of {_f(c['precision_ceiling_at_realised_fpr'][0])} at that "
        f"rate and {_f(c['precision_ceiling_at_1pct_fpr'][0])} at exactly 1% FPR",
        f"  at 0.60: precision {_f(c['precision_at_flag'][0])}, FPR {_f(c['fpr_at_flag'][0], 4)}"
        f"   at 0.85: precision {_f(c['precision_at_block'][0])}, recall "
        f"{_f(c['recall_at_block'][0])}, F1 {_f(c['f1_at_block'][0])}   (D-02)",
        f"  ECE equal-width {_f(c['ece_equal_width'][0], 4)}, Brier {_f(c['brier'][0], 5)}",
        (
            f"  ONNX parity over {report.parity.rows:,} test rows: max |ONNX - native| XGBoost "
            f"{report.parity.xgboost:.2e}, LightGBM {report.parity.lightgbm:.2e}, combined "
            f"{report.parity.combined:.2e} -> {'PASS' if report.parity.passed else 'FAIL'}"
            " (E.4, < 1e-5)"
            if report.parity
            else "  ONNX parity: not measured"
        ),
        "",
        f"  {len(passed)} of {len(report.gates)} gate metrics pass"
        + (f"; FAILED: {', '.join(g.spec.id for g in failed)}" if failed else ""),
    ]
    lines += [
        "",
        "BASELINES — AUC with a Hanley-McNeil 95% interval; DeLong p against the ensemble.",
    ]
    if not report.mcc_available:
        lines.append(
            "  (the cache carries no MCC column, so the rule engine scores the amount rule alone)"
        )
    for b in report.baselines:
        lines.append(
            f"  {b.name:<28s} {_f(b.auc)} +/-{_f(b.interval)}  R@1%FPR {_f(b.recall_at_1pct_fpr)}"
            f"  p {b.delong_p:.2g}"
        )
    if report.ablations:
        lines += [
            "",
            "ABLATIONS (E.5.4) — refitted on the same split; delta and DeLong p vs the full model.",
        ]
        for a in report.ablations:
            lines.append(
                f"  {a.name:<38s} {a.features:>2d} feat  AUC {_f(a.auc)} ({a.delta:+.4f}, p "
                f"{a.delong_p:.2g})  R@1%FPR {_f(a.recall_at_1pct_fpr)}  ECE {_f(a.ece, 4)}"
            )
    if len(next(iter(report.seeds.values()), [])) > 1:
        lines += [
            "",
            f"SEEDS — mean and SD over {len(next(iter(report.seeds.values())))} refits (E.5.2).",
        ]
        for key, values in report.seeds.items():
            mean, sd = seed_summary(values)
            lines.append(f"  {key:<11s} {_f(mean, 4)} +/- {_f(sd, 4)}")
    return lines


#: The packages whose versions decide the gate's numbers. The full lock is uv.lock at the commit
#: the evidence stamp names; these are repeated here so the file answers the question alone.
PACKAGES = ("xgboost", "lightgbm", "scikit-learn", "onnxruntime", "onnxmltools", "numpy", "scipy")


def environment() -> dict[str, object]:
    """What E.4 asks a run to log beside its metrics: the environment and the hardware."""
    import os  # noqa: PLC0415 - only this path needs it
    import platform  # noqa: PLC0415
    from importlib.metadata import PackageNotFoundError, version  # noqa: PLC0415

    def installed(name: str) -> str | None:
        try:
            return version(name)
        except PackageNotFoundError:
            return None

    return {
        "python": platform.python_version(),
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpus": os.cpu_count(),
        "packages": {name: installed(name) for name in PACKAGES},
    }


def as_json(report: Report) -> dict[str, object]:
    def clean(v: float) -> float | None:
        return None if math.isnan(v) else v

    return {
        "environment": environment(),
        "rows": {k: {"rows": n, "fraud": f} for k, (n, f) in report.counts.items()},
        "model": {
            "xgboost_rounds": report.xgboost_rounds,
            "lightgbm_rounds": report.lightgbm_rounds,
            "scale_pos_weight": report.scale_pos_weight,
            "seed": report.seed,
            "bootstrap_resamples": report.resamples,
        },
        "floor": {"feature": report.floor[0], "separation": clean(report.floor[1])},
        "gate": [
            {
                "id": g.spec.id,
                "metric": g.spec.name,
                "threshold": g.spec.threshold,
                "direction": ">=" if g.spec.at_least else "<",
                "value": clean(g.value),
                "ci95": [clean(g.interval[0]), clean(g.interval[1])],
                "pass": g.passed,
            }
            for g in report.gates
        ],
        "companions": {
            k: {"value": clean(v), "ci95": [clean(i[0]), clean(i[1])]}
            for k, (v, i) in report.companions.items()
        },
        "baselines": [
            {
                "name": b.name,
                "auc": clean(b.auc),
                "auc_interval": clean(b.interval),
                "recall_at_1pct_fpr": clean(b.recall_at_1pct_fpr),
                "delong_p_vs_ensemble": clean(b.delong_p),
            }
            for b in report.baselines
        ],
        "ablations": [
            {
                "name": a.name,
                "features": a.features,
                "auc": clean(a.auc),
                "delta": clean(a.delta),
                "delong_p": clean(a.delong_p),
                "recall_at_1pct_fpr": clean(a.recall_at_1pct_fpr),
                "ece": clean(a.ece),
            }
            for a in report.ablations
        ],
        "seeds": {k: [clean(v) for v in values] for k, values in report.seeds.items()},
        "onnx_parity": (
            {
                "rows": report.parity.rows,
                "max_abs_diff": {
                    "xgboost": report.parity.xgboost,
                    "lightgbm": report.parity.lightgbm,
                    "combined": report.parity.combined,
                },
                "pass": report.parity.passed,
            }
            if report.parity
            else None
        ),
        "passed": all(g.passed for g in report.gates),
    }


def _tex(value: float, digits: int = 3) -> str:
    return "---" if math.isnan(value) else f"{value:.{digits}f}"


def gate_table(report: Report) -> str:
    rows = [
        f"{g.spec.id} & {g.spec.name.replace('%', r'\%')} & {_tex(g.value)} & "
        f"[{_tex(g.interval[0])}, {_tex(g.interval[1])}] & "
        f"{'$\\geq$' if g.spec.at_least else '$<$'} {g.spec.threshold:g} & "
        f"{'pass' if g.passed else r'\textbf{fail}'} \\\\"
        for g in report.gates
    ]
    return "\n".join(
        [
            r"\begin{tabular}{llrlrl}",
            r"\toprule",
            r"Gate & Metric & Value & 95\% CI & Threshold & Result \\",
            r"\midrule",
            *rows,
            r"\bottomrule",
            r"\end{tabular}",
            "",
        ]
    )


def baseline_table(report: Report) -> str:
    rows = [
        f"{b.name} & {_tex(b.auc)} $\\pm$ {_tex(b.interval)} & {_tex(b.recall_at_1pct_fpr)} & "
        f"{b.delong_p:.2g} \\\\"
        for b in report.baselines
    ]
    return "\n".join(
        [
            r"\begin{tabular}{lrrr}",
            r"\toprule",
            r"Model & AUC & Recall at 1\% FPR & DeLong $p$ \\",
            r"\midrule",
            *rows,
            r"\bottomrule",
            r"\end{tabular}",
            "",
        ]
    )


def ablation_table(report: Report) -> str:
    rows = [
        f"{a.name} & {a.features} & {_tex(a.auc)} & {a.delta:+.4f} & {a.delong_p:.2g} & "
        f"{_tex(a.recall_at_1pct_fpr)} \\\\"
        for a in report.ablations
    ]
    return "\n".join(
        [
            r"\begin{tabular}{lrrrrr}",
            r"\toprule",
            r"Ablation & Features & AUC & $\Delta$AUC & DeLong $p$ & Recall at 1\% FPR \\",
            r"\midrule",
            *rows,
            r"\bottomrule",
            r"\end{tabular}",
            "",
        ]
    )


SIZE, PAD = 360, 48
BLUE, GREY = "#1f5fa8", "#999"


def _point(x: float, y: float) -> tuple[float, float]:
    span = SIZE - 2 * PAD
    return PAD + x * span, SIZE - PAD - y * span


def _svg(title: str, labels: tuple[str, str], series: list[tuple[float, float]]) -> str:
    """A square line chart on [0, 1] x [0, 1] with the y = x diagonal for reference."""
    xlabel, ylabel = labels
    span = SIZE - 2 * PAD
    points = [_point(x, y) for x, y in series]
    path = " ".join(f"{px:.1f},{py:.1f}" for px, py in points)
    (x0, y0), (x1, y1) = _point(0, 0), _point(1, 1)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIZE}" height="{SIZE}" '
        f'viewBox="0 0 {SIZE} {SIZE}" font-family="sans-serif" font-size="11">',
        f"<title>{title}</title>",
        f'<rect x="{PAD}" y="{PAD}" width="{span}" height="{span}" fill="none" stroke="#444"/>',
        f'<text x="{SIZE / 2}" y="{PAD - 16}" text-anchor="middle" font-size="13">{title}</text>',
        f'<text x="{SIZE / 2}" y="{SIZE - 12}" text-anchor="middle">{xlabel}</text>',
        f'<text x="14" y="{SIZE / 2}" text-anchor="middle" '
        f'transform="rotate(-90 14 {SIZE / 2})">{ylabel}</text>',
    ]
    for tick in (0.0, 0.5, 1.0):
        tx, ty = _point(tick, tick)
        parts.append(
            f'<text x="{tx:.1f}" y="{SIZE - PAD + 14}" text-anchor="middle">{tick:g}</text>'
        )
        parts.append(f'<text x="{PAD - 6}" y="{ty + 4:.1f}" text-anchor="end">{tick:g}</text>')
    parts.append(
        f'<polyline points="{x0:.1f},{y0:.1f} {x1:.1f},{y1:.1f}" fill="none" '
        f'stroke="{GREY}" stroke-dasharray="4 3"/>'
    )
    parts.append(f'<polyline points="{path}" fill="none" stroke="{BLUE}" stroke-width="2"/>')
    if len(points) <= 20:
        parts += [
            f'<circle cx="{px:.1f}" cy="{py:.1f}" r="2.5" fill="{BLUE}"/>' for px, py in points
        ]
    parts.append("</svg>")
    return "\n".join(parts) + "\n"


def write(report: Report, out: Path) -> list[Path]:
    """Write every artefact under `out` and return their paths."""
    tables, figures = out / "tables", out / "figures"
    tables.mkdir(parents=True, exist_ok=True)
    figures.mkdir(parents=True, exist_ok=True)
    written = {
        out / "metrics.json": json.dumps(as_json(report), indent=2, sort_keys=True) + "\n",
        tables / "gate.tex": gate_table(report),
        tables / "baselines.tex": baseline_table(report),
        figures / "reliability.svg": _svg(
            "Reliability, 15 equal-mass bins",
            ("mean predicted probability", "observed fraud rate"),
            [(p, o) for p, o, _ in report.reliability],
        ),
        figures / "roc.svg": _svg(
            "ROC, calibrated ensemble", ("false positive rate", "true positive rate"), report.roc
        ),
    }
    if report.ablations:
        written[tables / "ablations.tex"] = ablation_table(report)
    for path, text in written.items():
        path.write_text(text, encoding="utf-8")
    return sorted(written)
