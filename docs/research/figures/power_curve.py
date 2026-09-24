"""Power curve for the shortcut detector: fire rate against dataset size.

The evidence behind "our synthetic benchmark does not leak, and here is the scale at which we can
prove it". Four series share one x axis:

* **clean** — the false-alarm rate, which should sit at the gate's nominal 5%;
* **plant 0.6** — a leak agreeing with the label 60% of the time, the subtle case worth defending;
* **plant 1.0** — a perfect oracle, which only tests that the detector is alive;
* **plant 0.5** — a marker carrying no information, the negative control. Without it, "the gate
  fired" cannot be distinguished from "the gate fires whenever the data is touched", and the other
  three series cannot be interpreted.

Reads the committed sweep output and emits SVG directly: no plotting dependency, deterministic
output, and a figure that diffs as text rather than as an opaque binary.

    $ uv run python docs/research/figures/power_curve.py
"""

from __future__ import annotations

import json
import math
from collections import defaultdict
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[3]
RECORDS = REPO / "docs" / "reviews" / "M2" / "shortcut_diagnosis.jsonl"
FIGURE = Path(__file__).with_suffix(".svg")
SCENARIO_MINIMUM_ROWS = 170_000
POWER_SATURATION_ROWS = 60_000
W, H = 900, 520
LEFT, RIGHT, TOP, BOTTOM = 78, 300, 54, 66


def wilson(successes: int, trials: int, z: float = 1.96) -> tuple[float, float]:
    """Interval for a proportion from few trials, where the normal approximation misleads."""
    if trials == 0:
        return (0.0, 1.0)
    p = successes / trials
    denom = 1 + z * z / trials
    centre = (p + z * z / (2 * trials)) / denom
    half = z * math.sqrt(p * (1 - p) / trials + z * z / (4 * trials * trials)) / denom
    return (max(0.0, centre - half), min(1.0, centre + half))


# JSON records: the sweep's own schema, checked by its tests rather than here.
Record = dict[str, Any]


def series(
    records: list[Record], strength: float | None
) -> list[tuple[int, float, float, float, int]]:
    by_scale: dict[int, list[Record]] = defaultdict(list)
    for record in records:
        if record.get("variant") != "refused" and record.get("strength") == strength:
            by_scale[int(record["rows"])].append(record)
    points = []
    for scale in sorted(by_scale):
        runs = by_scale[scale]
        fired = sum(not run["passed"] for run in runs)
        low, high = wilson(fired, len(runs))
        points.append((scale, fired / len(runs), low, high, len(runs)))
    return points


def main() -> None:
    records = [
        json.loads(line) for line in RECORDS.read_text(encoding="utf-8").splitlines() if line
    ]
    scales = sorted({int(r["rows"]) for r in records})
    lo, hi = math.log10(min(scales) * 0.85), math.log10(max(scales) * 1.18)

    def x_of(rows: float) -> float:
        return LEFT + (math.log10(rows) - lo) / (hi - lo) * (W - LEFT - RIGHT)

    def y_of(rate: float) -> float:
        return H - BOTTOM - rate * (H - TOP - BOTTOM)

    styles = [
        (0.6, "plant 0.6 - leak agreeing with the label 60% of the time", "#1f6f4a", "none"),
        (1.0, "plant 1.0 - perfect oracle (liveness only)", "#7a7a7a", "2,3"),
        (None, "clean - false alarms (nominal 5%)", "#1b4f9c", "none"),
        (0.5, "plant 0.5 - negative control, no information", "#b3541e", "6,4"),
    ]
    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" '
        f'font-family="DejaVu Sans, Helvetica, sans-serif">',
        f'<rect width="{W}" height="{H}" fill="#ffffff"/>',
        f'<text x="{LEFT}" y="26" font-size="15" font-weight="600">Shortcut detector: power, '
        f"false alarms and negative control</text>",
        f'<text x="{LEFT}" y="44" font-size="10.5" fill="#555">FraudShield-EAC synthetic '
        f"benchmark; each point is a proportion of runs with a 95% Wilson interval</text>",
    ]
    for rate in (0.0, 0.25, 0.5, 0.75, 1.0):
        y = y_of(rate)
        out.append(
            f'<line x1="{LEFT}" y1="{y:.1f}" x2="{W - RIGHT}" y2="{y:.1f}" '
            f'stroke="#e6e6e6" stroke-width="1"/>'
        )
        out.append(
            f'<text x="{LEFT - 9}" y="{y + 3.5:.1f}" font-size="10" text-anchor="end" '
            f'fill="#444">{rate:.0%}</text>'
        )
    y5 = y_of(0.05)
    out.append(
        f'<line x1="{LEFT}" y1="{y5:.1f}" x2="{W - RIGHT}" y2="{y5:.1f}" stroke="#1b4f9c" '
        f'stroke-width="0.9" stroke-dasharray="4,3" opacity="0.6"/>'
    )
    for rows, label, colour in (
        (POWER_SATURATION_ROWS, "power saturates ~60,000", "#1f6f4a"),
        (SCENARIO_MINIMUM_ROWS, "all 8 scenarios ~170,000", "#111111"),
    ):
        x = x_of(rows)
        out.append(
            f'<line x1="{x:.1f}" y1="{TOP}" x2="{x:.1f}" y2="{H - BOTTOM}" stroke="{colour}" '
            f'stroke-width="1" stroke-dasharray="7,4" opacity="0.55"/>'
        )
        out.append(
            f'<text x="{x - 5:.1f}" y="{TOP + 8}" font-size="9.5" fill="{colour}" '
            f'text-anchor="end" transform="rotate(-90 {x - 5:.1f} {TOP + 8})">{label}</text>'
        )
    for scale in scales:
        x = x_of(scale)
        out.append(
            f'<line x1="{x:.1f}" y1="{H - BOTTOM}" x2="{x:.1f}" y2="{H - BOTTOM + 5}" '
            f'stroke="#444" stroke-width="1"/>'
        )
        out.append(
            f'<text x="{x:.1f}" y="{H - BOTTOM + 18}" font-size="9.5" text-anchor="middle" '
            f'fill="#444">{scale // 1000}K</text>'
        )
    out.append(
        f'<line x1="{LEFT}" y1="{H - BOTTOM}" x2="{W - RIGHT}" y2="{H - BOTTOM}" '
        f'stroke="#444" stroke-width="1.2"/>'
    )
    out.append(
        f'<line x1="{LEFT}" y1="{TOP}" x2="{LEFT}" y2="{H - BOTTOM}" stroke="#444" '
        f'stroke-width="1.2"/>'
    )
    out.append(
        f'<text x="{(LEFT + W - RIGHT) / 2:.0f}" y="{H - 24}" font-size="11" '
        f'text-anchor="middle">dataset size (rows, log scale)</text>'
    )
    out.append(
        f'<text x="20" y="{(TOP + H - BOTTOM) / 2:.0f}" font-size="11" text-anchor="middle" '
        f'transform="rotate(-90 20 {(TOP + H - BOTTOM) / 2:.0f})">runs where the gate '
        f"fired</text>"
    )

    legend_y = TOP + 6
    for strength, label, colour, dash in styles:
        points = series(records, strength)
        if not points:
            continue
        dash_attr = "" if dash == "none" else f' stroke-dasharray="{dash}"'
        path = " ".join(
            f"{'M' if i == 0 else 'L'}{x_of(s):.1f},{y_of(r):.1f}"
            for i, (s, r, _, _, _) in enumerate(points)
        )
        out.append(f'<path d="{path}" fill="none" stroke="{colour}" stroke-width="2"{dash_attr}/>')
        for scale, rate, low, high, trials in points:
            x = x_of(scale)
            out.append(
                f'<line x1="{x:.1f}" y1="{y_of(low):.1f}" x2="{x:.1f}" '
                f'y2="{y_of(high):.1f}" stroke="{colour}" stroke-width="1.1" opacity="0.75"/>'
            )
            out.append(f'<circle cx="{x:.1f}" cy="{y_of(rate):.1f}" r="3.4" fill="{colour}"/>')
            out.append(f"<title>{scale:,} rows: {rate:.0%} of {trials} runs</title>")
        out.append(
            f'<line x1="{W - RIGHT + 14}" y1="{legend_y}" x2="{W - RIGHT + 44}" '
            f'y2="{legend_y}" stroke="{colour}" stroke-width="2"{dash_attr}/>'
        )
        out.append(f'<circle cx="{W - RIGHT + 29}" cy="{legend_y}" r="3.4" fill="{colour}"/>')
        out.append(f'<text x="{W - RIGHT + 52}" y="{legend_y + 3.5}" font-size="10">{label}</text>')
        legend_y += 21
    out.append("</svg>")
    FIGURE.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"wrote {FIGURE.relative_to(REPO)}")


if __name__ == "__main__":
    main()
