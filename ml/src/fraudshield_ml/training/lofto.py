"""Leave-one-fraud-type-out (E.5 item 5; PB-66's job 3).

For each of the generator's eight fraud scenarios, every row of the scenario, frauds the observed
label missed included, is removed from every fitted set (training, validation and calibration, and
the target encoding with them), and the model is refitted on what is left. That model scores the
whole test period, and the held-out scenario's test frauds are read at 0.60 and at the 1% FPR
budget's threshold (the gate's rule), beside the production model, fitted with every scenario, read
the same way. The difference is what never fitting on the scenario costs. Not what never having
seen it costs: the remaining rows' features were computed over a history that still contains the
scenario's rows.

**Volume is decided before any test row is scored.** The production model is fitted first and
timed, cache load included (fitting scores nothing). If nine at that pace would not fit
`budget_minutes`, every fit — production included, so the comparison stays like for like — uses
30,000 training rows spread in time (a refit: 30,000 less its scenario's rows), as PB-67's learning
curve did. The report states which volume ran and why
(`docs/research/preregistration_pb66_full_volume.md`, amendment of 2026-10-19).
"""

from __future__ import annotations

import math
import time
from collections.abc import Callable
from dataclasses import dataclass, field
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
    """Everything read so far. `complete` is False while refits remain, so a partial copy can be
    written after each one: a run cut short keeps what it measured (its access is already logged).
    """

    volume: str
    seconds_per_fit: float
    production: dict[str, Read]
    #: every test fraud, read off the production model: comparable with job 2's seed-1 model
    production_overall: Read
    #: mule_account without the reversal variant, which no model here catches (ADR 0028)
    production_mule_base: Read
    held_out: dict[str, Read] = field(default_factory=dict)
    held_out_mule_base: Read | None = None
    #: non-test rows removed for each scenario's refit, and each fit's training rows: at the
    #: 30,000-row fallback a refit has 30,000 less the scenario's rows (spread, then dropped, so
    #: its legitimate rows are production's)
    dropped: dict[str, int] = field(default_factory=dict)
    training_rows: dict[str, int] = field(default_factory=dict)
    complete: bool = False


def run(  # noqa: PLR0913 - every argument is a setting of the run or one of its two reporters
    cache: Path,
    *,
    seed: int,
    budget_minutes: float,
    clock: Callable[[], float] = time.monotonic,
    on_volume: Callable[[str, int], None] = lambda _volume, _rows: None,
    on_progress: Callable[[Result], None] = lambda _result: None,
) -> Result:
    """Every fit and read.

    `on_volume` gets the volume and the test row count before any test row is scored;
    `on_progress` gets the result after the production fit and after each refit.
    """
    np = _np()
    kinds, variants = _facts(cache)

    started = clock()
    data = gate_run.load_cache(cache)
    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    # Load and fit together: every refit reloads the cache, so this is what nine of them cost.
    seconds = clock() - started
    train_rows: int | None = None
    if FITS * seconds > budget_minutes * 60:
        train_rows = FALLBACK_TRAIN_ROWS
        volume = (
            f"{FALLBACK_TRAIN_ROWS} training rows (each refit: {FALLBACK_TRAIN_ROWS} less its "
            f"scenario's rows): one full-volume load-and-fit took {seconds / 60:.1f} min, so "
            f"{FITS} would exceed the {budget_minutes:.0f}-minute budget"
        )
        del data, fitted
        data = gate_run.load_cache(cache, train_rows=train_rows)
        fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
    else:
        volume = (
            f"full volume ({len(data.split.train)} training rows): one load-and-fit took "
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
    result = Result(
        volume=volume,
        seconds_per_fit=seconds,
        production={k: read(production_scores, labels, test_kinds == k) for k in SCENARIOS},
        production_overall=read(production_scores, labels, np.ones(len(test), dtype=bool)),
        production_mule_base=read(production_scores, labels, base_mule),
        training_rows={"production": len(data.split.train)},
    )
    # Only the production reads are needed from here: the matrix goes before the refits load
    # theirs, so two never sit in memory at once.
    del data, fitted, production_scores
    on_progress(result)

    in_test = set(test)
    for kind in SCENARIOS:
        # Every row of the scenario, including frauds the observed label missed (label noise):
        # left in, they would teach the model the scenario as legitimate.
        drop = [i for i, k in enumerate(kinds) if k == kind and i not in in_test]
        rows = gate_run.load_cache(cache, train_rows=train_rows, drop=drop)
        fit = model.fit_ensemble(rows.matrix, rows.labels, rows.split, seed=seed)
        scores = scored(fit, rows)
        result.dropped[kind] = len(drop)
        result.training_rows[kind] = len(rows.split.train)
        result.held_out[kind] = read(scores, labels, test_kinds == kind)
        if kind == "mule_account":
            result.held_out_mule_base = read(scores, labels, base_mule)
        del rows, fit, scores
        result.complete = kind == SCENARIOS[-1]
        on_progress(result)
    return result


def _pairs(result: Result) -> list[tuple[str, Read, Read]]:
    """Each scenario refitted so far, with its production read; partial while refits remain."""
    pairs = [
        (k, result.production[k], result.held_out[k]) for k in SCENARIOS if k in result.held_out
    ]
    if result.held_out_mule_base is not None:
        pairs.append(
            (
                "mule_account without reversal",
                result.production_mule_base,
                result.held_out_mule_base,
            )
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


def as_payload(result: Result) -> dict[str, object]:
    """What `lofto.json` holds, complete or not."""
    overall, (low, high) = result.production_overall.recall(result.production_overall.at_budget)
    return {
        "complete": result.complete,
        "volume": result.volume,
        "seconds_per_load_and_fit": result.seconds_per_fit,
        "production_recall_at_budget_all_frauds": overall,
        "production_recall_at_budget_all_frauds_wilson": [low, high],
        "rows_removed": result.dropped,
        "training_rows": result.training_rows,
        "scenarios": as_rows(result),
    }


def _f(v: float) -> str:
    return "n/a" if math.isnan(v) else f"{v:.3f}"


def summary(result: Result) -> list[str]:
    """The text report: the volume and why, then each scenario's recall before and after."""
    lines = [
        "LEAVE-ONE-FRAUD-TYPE-OUT — each scenario's rows removed from every fitted set",
        f"  volume: {result.volume}",
        "  production recall at the 1% FPR budget, all test frauds: "
        f"{_f(result.production_overall.recall(result.production_overall.at_budget)[0])}",
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
    lines.append(
        "  training rows per fit: " + ", ".join(f"{k} {n}" for k, n in result.training_rows.items())
    )
    if not result.complete:
        lines.append("  INCOMPLETE: not every scenario was refitted")
    return lines
