"""Leave-one-fraud-type-out (E.5 item 5; PB-66's job 3).

For each of the generator's eight fraud scenarios, every row of the scenario, frauds the observed
label missed included, is removed from every fitted set (training, validation and calibration, and
the target encoding with them), and the model is refitted on what is left. That model scores the
whole test period, and the held-out scenario's test frauds are read at 0.60 and at the 1% FPR
budget's threshold (the gate's rule), beside the production model, fitted with every scenario, read
the same way. The difference is what never having seen the scenario costs.

**Volume is decided before any test row is scored.** The production model is fitted first and
timed (fitting scores nothing). If nine fits at that pace would not fit `budget_minutes`, every fit
— production included, so the comparison stays like for like — uses 30,000 training rows spread in
time, as PB-67's learning curve did. The report states which volume ran and why
(`docs/research/preregistration_pb66_full_volume.md`, amendment of 2026-10-19).
"""

from __future__ import annotations

import math
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fraudshield_ml.training import gate, gate_run, model, pb66, smoke

#: The generator's eight scenarios (`fraudshield_dataset.generator.fraud.SCENARIOS`).
SCENARIOS = (
    "sim_swap",
    "account_takeover",
    "agent_fraud",
    "velocity",
    "card_not_present",
    "mule_account",
    "merchant_fraud",
    "synthetic_identity",
)
#: The variant labelled mule_account that is one transfer to a known payee, not a burst (ADR 0028).
REVERSAL_VARIANT = "reversal_scam_social_engineering"
FALLBACK_TRAIN_ROWS = 30_000
#: Production plus one refit per scenario.
FITS = 1 + len(SCENARIOS)


@dataclass(frozen=True)
class Read:
    """One scenario's test frauds read off one model's scores."""

    frauds: int
    at_flag: int
    at_budget: int

    def recall(self, caught: int) -> tuple[float, tuple[float, float]]:
        point = caught / self.frauds if self.frauds else math.nan
        return point, pb66.wilson(caught, self.frauds)


def budget_threshold(scores: Any, labels: Any, fpr: float = gate.FPR_BUDGET) -> float:
    """The score that `fpr` of legitimate test rows reach; flagged means strictly above it."""
    np = _np()
    negatives = np.sort(scores[~labels])[::-1]
    return float(negatives[min(len(negatives) - 1, max(0, int(len(negatives) * fpr) - 1))])


def read(scores: Any, labels: Any, members: Any) -> Read:
    """How many of the `members` frauds each operating point catches."""
    threshold = budget_threshold(scores, labels)
    frauds = labels & members
    return Read(
        frauds=int(frauds.sum()),
        at_flag=int((frauds & (scores >= gate.FLAG)).sum()),
        at_budget=int((frauds & (scores > threshold)).sum()),
    )


def _np() -> Any:
    import numpy as np  # noqa: PLC0415 - heavy, and only these paths need it

    return np


def _facts(cache: Path) -> tuple[list[str], list[str]]:
    import pyarrow.parquet as pq  # noqa: PLC0415 - heavy, and only this path needs it

    wanted = (smoke.CACHE_FRAUD_TYPE, smoke.CACHE_VARIANT)
    missing = [k for k in wanted if k not in pq.read_schema(cache).names]
    if missing:
        raise ValueError(
            f"{cache} has no {missing}: leave-one-fraud-type-out needs a cache written with the "
            "fraud-type column (fs-features evaluate on this commit or later)"
        )
    table = pq.read_table(cache, columns=list(wanted))
    kinds = [str(v) for v in table.column(smoke.CACHE_FRAUD_TYPE).to_pylist()]
    variants = [str(v) for v in table.column(smoke.CACHE_VARIANT).to_pylist()]
    return kinds, variants


@dataclass
class Result:
    train_rows_used: int
    volume: str
    seconds_per_fit: float
    production: dict[str, Read]
    held_out: dict[str, Read]
    #: mule_account without the reversal variant, which no model here catches (ADR 0028)
    production_mule_base: Read
    held_out_mule_base: Read
    dropped: dict[str, int]


def run(
    cache: Path,
    *,
    seed: int,
    budget_minutes: float,
    clock: Callable[[], float] = time.monotonic,
    on_volume: Callable[[str, int], None] = lambda _volume, _rows: None,
) -> Result:
    """Every fit and read. `on_volume` gets the volume and test row count before any is scored."""
    np = _np()
    kinds, variants = _facts(cache)

    started = clock()
    data = gate_run.load_cache(cache)
    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    seconds = clock() - started
    train_rows: int | None = None
    if FITS * seconds > budget_minutes * 60:
        train_rows = FALLBACK_TRAIN_ROWS
        volume = (
            f"{FALLBACK_TRAIN_ROWS} training rows: one full-volume fit took "
            f"{seconds / 60:.1f} min, so {FITS} would exceed the {budget_minutes:.0f}-minute budget"
        )
        data = gate_run.load_cache(cache, train_rows=train_rows)
        fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    else:
        volume = (
            f"full volume ({len(data.split.train)} training rows): one fit took "
            f"{seconds / 60:.1f} min, {FITS} fit the {budget_minutes:.0f}-minute budget"
        )
    on_volume(volume, len(data.test))

    test = list(data.test)
    labels = np.asarray([data.labels[i] for i in test], dtype=bool)
    test_kinds = np.asarray([kinds[i] for i in test], dtype=object)
    base_mule = np.asarray(
        [kinds[i] == "mule_account" and variants[i] != REVERSAL_VARIANT for i in test], dtype=bool
    )

    def scored(fit: model.Ensemble, rows: gate_run.Loaded) -> Any:
        return np.asarray(fit.score([rows.matrix[i] for i in test]).ensemble, dtype=float)

    production_scores = scored(fitted, data)
    production = {k: read(production_scores, labels, test_kinds == k) for k in SCENARIOS}
    production_mule_base = read(production_scores, labels, base_mule)

    held_out: dict[str, Read] = {}
    dropped: dict[str, int] = {}
    held_out_mule_base = production_mule_base
    in_test = set(test)
    for kind in SCENARIOS:
        # Every row of the scenario, including frauds the observed label missed (label noise):
        # left in, they would teach the model the scenario as legitimate.
        drop = [i for i, k in enumerate(kinds) if k == kind and i not in in_test]
        dropped[kind] = len(drop)
        rows = gate_run.load_cache(cache, train_rows=train_rows, drop=drop)
        fit = model.fit_ensemble(rows.matrix, rows.labels, rows.split, seed=seed)
        scores = scored(fit, rows)
        held_out[kind] = read(scores, labels, test_kinds == kind)
        if kind == "mule_account":
            held_out_mule_base = read(scores, labels, base_mule)
    return Result(
        train_rows_used=len(data.split.train),
        volume=volume,
        seconds_per_fit=seconds,
        production=production,
        held_out=held_out,
        production_mule_base=production_mule_base,
        held_out_mule_base=held_out_mule_base,
        dropped=dropped,
    )


def _pairs(result: Result) -> list[tuple[str, Read, Read]]:
    pairs = [(k, result.production[k], result.held_out[k]) for k in SCENARIOS]
    pairs.append(
        ("mule_account without reversal", result.production_mule_base, result.held_out_mule_base)
    )
    return pairs


def as_rows(result: Result) -> list[dict[str, object]]:
    """One row per scenario: production and held-out recall at both points, with Wilson CIs."""
    rows: list[dict[str, object]] = []
    for name, before, after in _pairs(result):
        row: dict[str, object] = {"scenario": name, "test_frauds": before.frauds}
        for point, b, a in (
            ("flag", before.at_flag, after.at_flag),
            ("budget", before.at_budget, after.at_budget),
        ):
            row[f"production_recall_at_{point}"], row[f"production_{point}_wilson"] = before.recall(
                b
            )
            row[f"held_out_recall_at_{point}"], row[f"held_out_{point}_wilson"] = after.recall(a)
            row[f"drop_at_{point}"] = before.recall(b)[0] - after.recall(a)[0]
        rows.append(row)
    return rows


def _f(v: float) -> str:
    return "n/a" if math.isnan(v) else f"{v:.3f}"


def summary(result: Result) -> list[str]:
    """The text report: the volume and why, then each scenario's recall before and after."""
    lines = [
        "LEAVE-ONE-FRAUD-TYPE-OUT — each scenario's rows removed from every fitted set",
        f"  volume: {result.volume}",
        "  recall of the held-out scenario's test frauds, Wilson 95% intervals:",
        "  scenario                        frauds  at 1% FPR: production -> held out (drop)"
        "      at 0.60: production -> held out (drop)",
    ]
    for name, before, after in _pairs(result):
        (pb, (pb_lo, pb_hi)), (ab, (ab_lo, ab_hi)) = (
            before.recall(before.at_budget),
            after.recall(after.at_budget),
        )
        pf, af = before.recall(before.at_flag)[0], after.recall(after.at_flag)[0]
        lines.append(
            f"  {name:31} {before.frauds:>6}  {_f(pb)} [{_f(pb_lo)}, {_f(pb_hi)}] -> "
            f"{_f(ab)} [{_f(ab_lo)}, {_f(ab_hi)}] ({_f(pb - ab)})   "
            f"{_f(pf)} -> {_f(af)} ({_f(pf - af)})"
        )
    lines.append(
        "  rows removed from the fitted sets: "
        + ", ".join(f"{k} {n}" for k, n in result.dropped.items())
    )
    return lines
