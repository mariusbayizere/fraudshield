"""Round-two evidence for the paper, as fixed in `round2_plan.md` before any of it ran.

Three subcommands, each run in the environment of the code that produced the published figures,
each refusing a draw other than d8083dbc, each logging its test-period reads through the project's
own `access.record`, and each writing one stamped text file (and per-row scores beside it):

    gate       at m4-complete: the gate model's reversal split and calibration, baselines and
               ablations as paired account-clustered AUC differences, the single-feature floor on
               the gate's test rows, leave-one-country-out, and the reconciliation of the test
               period's confirmed frauds from the dataset's own labels;
    curve      at 088cfd2: the four learning-curve points with account-clustered intervals;
    selection  at 088cfd2: 30,000 training rows at several corpus depths, with each training
               row's history.

Every interval resamples whole accounts, stratified by whether an account has any confirmed fraud
in the rows concerned (1,000 resamples, seed 1), as `clustered_intervals.py` does; a difference
between two models is evaluated on the same resample (paired).
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import importlib.util
import math
import os
import re
import statistics
import subprocess
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
import pyarrow.parquet as pq

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import clustered_intervals as ci  # noqa: E402 - the shared resampling, from this folder

from fraudshield_ml.cli import REGISTRY  # noqa: E402
from fraudshield_ml.training import battery, gate, gate_run, model, smoke  # noqa: E402

ROOT = HERE.parents[3]
ACCESS = ROOT / "ml/src/fraudshield_ml/training/access.py"
SEED = 1
RESAMPLES = ci.RESAMPLES
REVERSAL = "reversal_scam_social_engineering"
NOVELTY = "counterparty_is_new_for_account"
#: Where the published learning-curve files are read, so a reproduction is checked against them
#: rather than against values typed here (the paper's audit reads them at the same commit).
CURVE_REF = "3dacca6"
#: The gate cache's corpus depth (paper-reproduce.yml), a fixed point of the selection test.
GATE_CORPUS = 400_000
MISMATCHES: list[str] = []
#: Printed beside every clustered recall (plan, section 1, item 1).
CLUSTER_NOTE = (
    "  clustered recall holds each threshold at its full-sample value, so it leaves out the",
    "  threshold's own uncertainty; a resample holding none of a subgroup's rows is dropped",
)


def wilson(k: int, n: int) -> tuple[float, float]:
    """`battery.VariantResult.recall_interval`'s Wilson 95% interval, for a bare count."""
    if n == 0:
        return math.nan, math.nan
    z, p = 1.96, k / n
    denom = 1 + z**2 / n
    centre = p + z**2 / (2 * n)
    spread = z * math.sqrt(p * (1 - p) / n + z**2 / (4 * n**2))
    return max(0.0, (centre - spread) / denom), min(1.0, (centre + spread) / denom)


def fpr_threshold(scores: Any, labels: Any) -> float:
    """The 1% budget's threshold, by `gate.recall_at_fpr`'s rule (rows strictly above it flag)."""
    negatives = np.sort(scores[~labels])[::-1]
    return float(negatives[min(len(negatives) - 1, max(0, int(len(negatives) * 0.01) - 1))])


def fmt(pair: tuple[float, float]) -> str:
    return f"[{pair[0]:.3f}, {pair[1]:.3f}]"


def access_module() -> Any:
    spec = importlib.util.spec_from_file_location("tree_access", ACCESS)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {ACCESS}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def cache_column(cache: Path, column: str) -> list[str]:
    return [str(v) for v in pq.read_table(cache, columns=[column]).column(0).to_pylist()]


def test_rows(cache: Path) -> int:
    return cache_column(cache, smoke.CACHE_SEGMENT).count("test")


def accounts_of(cache: Path) -> list[str]:
    return [
        str(v) for v in pq.read_table(cache, columns=[smoke.CACHE_ACCOUNT]).column(0).to_pylist()
    ]


# ---- gate -----------------------------------------------------------------------------------


@dataclass
class Gate:
    """The refitted gate model and the test rows everything in the `gate` job is measured on."""

    data: Any
    extras: dict[str, list[str]]
    test: list[int]
    labels: Any
    accounts: list[str]
    channels: list[str]
    draws: list[Any]
    scored: Any
    ensemble: Any


def fit_gate(cache: Path) -> tuple[Gate, list[str]]:
    vectors, extras = smoke.cache_read_any(cache)
    data = gate_run.load(vectors, extras)
    test = list(data.test)
    labels = np.asarray([data.labels[i] for i in test], dtype=bool)
    accounts = [extras[smoke.CACHE_ACCOUNT][i] for i in test]
    channels = [data.channels[i] for i in test]
    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=SEED)
    scored = fitted.score([data.matrix[i] for i in test])
    covered = gate_run._covered(fitted, data, scored.ensemble)
    point = gate.metrics(gate.rows_of(scored.ensemble, list(labels), channels, covered))
    for key, published in ci.PUBLISHED_GATE.items():
        if round(point[key], 3) != published:
            MISMATCHES.append(f"gate {key}: {point[key]:.4f} against {published}")
    g = Gate(
        data=data,
        extras=extras,
        test=test,
        labels=labels,
        accounts=accounts,
        channels=channels,
        draws=ci.cluster_draws(accounts, list(labels), np.random.default_rng(SEED)),
        scored=scored,
        ensemble=np.asarray(scored.ensemble, dtype=float),
    )
    return g, [
        f"GATE MODEL (D-05 ensemble, {len(data.split.train)} training rows, seed {SEED})",
        f"  test rows {len(labels)}, confirmed fraud {int(labels.sum())}, accounts "
        f"{len(set(accounts))}; AUC {point['ML-GATE-01']:.3f}, recall at 1% FPR "
        f"{point['ML-GATE-02']:.3f}, recall at 0.60 {point['ML-GATE-03']:.3f}",
    ]


def recall_block(g: Gate, name: str, mask: Any, thresholds: dict[str, float]) -> list[str]:
    """Caught, recall, Wilson and clustered interval for the fraud rows `mask` selects."""
    selected = mask & g.labels
    rows = np.flatnonzero(selected)
    n = len(rows)
    lines = [f"  {name}: {n} confirmed-fraud rows on {len({g.accounts[i] for i in rows})} accounts"]
    for label, threshold in thresholds.items():
        at_flag = label.startswith("flag")
        flagged = g.ensemble >= threshold if at_flag else g.ensemble > threshold
        k = int(flagged[rows].sum())
        values = [float(flagged[d[selected[d]]].mean()) for d in g.draws if selected[d].any()]
        dropped = len(g.draws) - len(values)
        clustered = ci.interval(values) if values else (math.nan, math.nan)
        recall = k / n if n else math.nan
        lines.append(
            f"    {label:<14} caught {k}/{n}  recall {recall:.3f}  Wilson {fmt(wilson(k, n))}  "
            f"clustered {fmt(clustered)} ({dropped} of {len(g.draws)} resamples held none of "
            "these rows and were dropped)"
        )
    return lines


def reversal_section(g: Gate) -> tuple[list[str], Any, Any]:
    """Item 1: the reversal variant, and its split by the row's own counterparty novelty."""
    column = g.data.names.index(NOVELTY)
    novelty = np.asarray([g.data.matrix[i][column] for i in g.test], dtype=float)
    variants = np.asarray([g.extras[smoke.CACHE_VARIANT][i] for i in g.test], dtype=object)
    thresholds = {"1% FPR budget": fpr_threshold(g.ensemble, g.labels), "flag 0.60": gate.FLAG}
    reversal = variants == REVERSAL
    lines = [
        "",
        "REVERSAL VARIANT, production ensemble (1% FPR threshold "
        f"{thresholds['1% FPR budget']:.4f}: rows strictly above it; flag: at or above 0.60)",
        *CLUSTER_NOTE,
        *recall_block(g, "all reversal rows", reversal, thresholds),
        *recall_block(g, f"{NOVELTY} = 1", reversal & (novelty == 1), thresholds),
        *recall_block(g, f"{NOVELTY} = 0", reversal & (novelty == 0), thresholds),
        f"  reversal fraud rows with {NOVELTY} undefined: "
        f"{int((reversal & g.labels & np.isnan(novelty)).sum())}",
    ]
    return lines, novelty, variants


def calibration_section(g: Gate) -> list[str]:
    """Item 2: calibration over all rows and in the decision region."""
    scores, labels = list(g.ensemble), list(g.labels)
    region = battery.decision_region(scores, labels)
    whole = battery.expected_calibration_error(battery.reliability(scores, labels))
    return [
        "",
        "CALIBRATION of the production ensemble (battery.reliability, battery.decision_region:",
        "the definitions behind the diagnostic model's published decision-region figure)",
        f"  all test rows: {len(labels)}, expected calibration error {whole:.4f}",
        f"  decision region (score >= {region.threshold:.2f}): rows {region.rows}, fraud "
        f"{region.fraud}, expected calibration error {region.ece:.4f}, Brier {region.brier:.5f}",
        "    predicted       rows   predicted   observed",
        *(
            f"    [{b.lower:.1f}, {b.upper:.1f})  {b.rows:>8}    {b.predicted:.3f}"
            f"      {b.observed:.3f}"
            for b in region.bins
        ),
    ]


def paired(g: Gate, scores: Any) -> tuple[float, float, tuple[float, float]]:
    """AUC, its difference from the ensemble's, and the paired clustered interval of that."""
    s, y, e = np.asarray(scores, dtype=float), g.labels, g.ensemble
    value = gate.auc(s, y)
    deltas = [gate.auc(s[d], y[d]) - gate.auc(e[d], y[d]) for d in g.draws]
    return value, value - gate.auc(e, y), ci.interval(deltas)


def baselines_section(g: Gate) -> list[str]:
    """Item 3a: the baselines of `gate_run.run`, fitted as it fits them."""
    from fraudshield_ml.training import anomaly, baselines  # noqa: PLC0415

    data, test = g.data, g.test
    amount = [row[data.names.index("amount_log1p")] for row in data.matrix]
    rule = baselines.rule_engine(amount, data.mcc, data.labels, data.split.train)
    forest = anomaly.fit_anomaly(data.matrix, data.split.train, seed=SEED)
    train = list(data.split.train)
    candidates = [
        ("status-quo rule engine", [rule[i] for i in test]),
        ("logistic regression", baselines.logistic_regression(
            data.matrix, data.labels, train, test, seed=SEED)),
        ("random forest", baselines.random_forest(
            data.matrix, data.labels, train, test, seed=SEED)),
        ("XGBoost alone", g.scored.xgboost),
        ("LightGBM alone", g.scored.lightgbm),
        ("Isolation Forest alone", forest.score([data.matrix[i] for i in test])),
    ]  # fmt: skip
    lines = [
        "",
        "BASELINES: AUC, and the baseline's AUC minus the ensemble's with a paired",
        "account-clustered interval (in place of DeLong p-values, which assume independent rows)",
    ]
    for name, scores in candidates:
        value, delta, pair = paired(g, scores)
        lines.append(
            f"  {name:<26} AUC {value:.3f}  difference {delta:+.4f}  clustered {fmt(pair)}"
        )
    return lines


def ablations_section(g: Gate) -> list[str]:
    """Item 3b: the ablations of `gate_run._ablations`, refitted as it refits them."""
    from fraudshield_ml.training import anomaly  # noqa: PLC0415

    data = g.data
    group = {n: REGISTRY[n].group.name for n in data.names}
    removals = [
        ("without agent features", lambda n: group[n] == "AGENT"),
        ("without the corridor feature", lambda n: group[n] == "CORRIDOR"),
        ("without round-sum awareness", lambda n: n == "round_sum_flag"),
        ("without month-end awareness", lambda n: n == "is_month_end_window"),
        ("card-style features only", lambda n: group[n] not in gate_run.CARD_STYLE_GROUPS),
    ]
    matrices = []
    for name, drop in removals:
        kept = [j for j, n in enumerate(data.names) if not drop(n)]
        matrices.append((name, [[row[j] for j in kept] for row in data.matrix]))
    columns = [j for j, n in enumerate(data.names) if n in gate_run.DEVICE_FEATURES]
    medians = anomaly.training_medians(data.matrix, data.split.train)
    imputed = [
        [medians[j] if j in columns and math.isnan(v) else v for j, v in enumerate(row)]
        for row in data.matrix
    ]
    matrices.append(("without USSD-aware device handling", imputed))
    lines = [
        "",
        "ABLATIONS: refitted on the same split; AUC minus the full model's, paired interval",
    ]
    for name, matrix in matrices:
        refit = model.fit_ensemble(matrix, data.labels, data.split, seed=SEED)
        value, delta, pair = paired(g, refit.score([matrix[i] for i in g.test]).ensemble)
        lines.append(
            f"  {name:<36} {len(matrix[0])} feat  AUC {value:.3f}  difference {delta:+.4f}  "
            f"clustered {fmt(pair)}"
        )
    return lines


def floor_section(g: Gate) -> list[str]:
    """Item 4: every trainable feature alone on the gate's test rows."""
    from fraudshield_ml.metrics.single_feature import auc_standard_error  # noqa: PLC0415

    floors, skipped = [], []
    positives = int(g.labels.sum())
    for j, name in enumerate(g.data.names):
        column = np.asarray([g.data.matrix[i][j] for i in g.test], dtype=float)
        if np.isnan(column).any():
            skipped.append(name)
            continue
        if gate.auc(column, g.labels) < 0.5:
            column = -column
        value = gate.auc(column, g.labels)
        hanley = 1.96 * auc_standard_error(value, positives, len(g.labels) - positives)
        clustered = ci.interval([gate.auc(column[d], g.labels[d]) for d in g.draws])
        floors.append((value, name, hanley, clustered))
    return [
        "",
        "SINGLE-FEATURE FLOOR on the gate's test rows: max(AUC, 1-AUC), orientation fixed by",
        "the point estimate, Hanley-McNeil and account-clustered 95% intervals",
        *(
            f"  {name:<38} {value:.3f}  +/-{hanley:.3f}  clustered {fmt(clustered)}"
            for value, name, hanley, clustered in sorted(floors, reverse=True)
        ),
        "  not defined on every test row, so not ranked (as gate_run._best): " + ", ".join(skipped),
    ]


def loco_section(cache: Path) -> tuple[list[str], int]:
    """Item 5: leave-one-country-out on the diagnostic battery, `_battery_loco`'s design."""
    bench, all_accounts = ci.bench_from(cache)
    full = np.asarray(bench.fit(bench.train, bench.test), dtype=float)
    position = {row: k for k, row in enumerate(bench.test)}
    lines = [
        "",
        "LEAVE-ONE-COUNTRY-OUT (diagnostic battery): AUC in-sample and with the country unseen in",
        "training, each with an account-clustered interval over that country's test rows",
    ]
    for country in sorted({bench.country[i] for i in bench.test}):
        inner = [i for i in bench.test if bench.country[i] == country]
        held = np.asarray(bench.labels_of(inner), dtype=bool)
        if not held.any():
            continue
        inner_accounts = [all_accounts[i] for i in inner]
        local = ci.cluster_draws(inner_accounts, list(held), np.random.default_rng(SEED))
        seen = full[[position[i] for i in inner]]
        unseen = np.asarray(
            bench.fit([i for i in bench.train if bench.country[i] != country], inner), dtype=float
        )

        def clustered(s: Any, h: Any = held, draws: list[Any] = local) -> str:
            return fmt(ci.interval([gate.auc(s[d], h[d]) for d in draws]))

        lines.append(
            f"  {country}  rows {len(inner)}, fraud {int(held.sum())}, accounts "
            f"{len(set(inner_accounts))}: in-sample {gate.auc(seen, held):.3f} {clustered(seen)}, "
            f"unseen {gate.auc(unseen, held):.3f} {clustered(unseen)}"
        )
    return lines, len(bench.test)


def gate_command(args: argparse.Namespace) -> int:
    reads: list[tuple[str, Path, int, str]] = []
    purpose = "paper evidence, round two"
    try:
        # Each read is logged before the work that makes it, from the cache's own test-row count.
        reads.append(("round2.py gate", args.gate_cache, test_rows(args.gate_cache), purpose))
        g, lines = fit_gate(args.gate_cache)
        emit(lines)
        block, novelty, variants = reversal_section(g)
        emit(block)
        with gzip.open(args.out / "round2_gate_scores_d8083dbc.csv.gz", "wt") as file:
            file.write(f"test_row,account_id,label,variant,channel,{NOVELTY},ensemble\n")
            for k, i in enumerate(g.test):
                file.write(
                    f"{i},{g.accounts[k]},{int(g.labels[k])},{variants[k]},{g.channels[k]},"
                    f"{novelty[k]!r},{g.ensemble[k]!r}\n"
                )
        emit(calibration_section(g))
        emit(baselines_section(g))
        emit(ablations_section(g))
        emit(floor_section(g))
        reads.append(
            ("round2.py gate (battery)", args.battery_cache, test_rows(args.battery_cache), purpose)
        )
        block, _ = loco_section(args.battery_cache)
        emit(block)
        reads.append(("round2.py reconcile", args.dataset, test_rows(args.gate_cache), purpose))
        emit(["", *reconcile(args.dataset, args.split, g.extras, g.test)])
    finally:
        log_reads(reads)
    return finish("gate")


def reconcile(
    dataset: Path, split: Path, extras: dict[str, list[str]], test: list[int]
) -> list[str]:
    """Confirmed frauds of the test period by variant, fraud type, observed and true label."""
    from fraudshield_ml.training import split as split_module  # noqa: PLC0415

    boundaries = split_module.load(split)
    times: dict[str, Any] = {}
    for part in sorted((dataset / "transactions").glob("month=*/part-*.parquet")):
        table = pq.read_table(part, columns=["transaction_id", "transaction_timestamp"])
        ids = table.column("transaction_id").to_pylist()
        stamps = table.column("transaction_timestamp").to_pylist()
        for tid, ts in zip(ids, stamps, strict=True):
            if boundaries.segment_of(ts) is split_module.Segment.TEST:
                times[str(tid)] = ts
    counts: Counter[tuple[str, str, bool, bool]] = Counter()
    for part in sorted((dataset / "labels").glob("month=*/part-*.parquet")):
        table = pq.read_table(
            part,
            columns=[
                "transaction_id",
                "is_fraud_observed",
                "is_fraud_true",
                "fraud_type",
                "scenario_variant",
            ],
        )
        names = ("transaction_id", "is_fraud_observed", "is_fraud_true", "fraud_type")
        for tid, observed, true, kind, variant in zip(
            *(table.column(c).to_pylist() for c in (*names, "scenario_variant")), strict=True
        ):
            if str(tid) in times and (observed or true):
                counts[(variant or "base", kind or "none", bool(observed), bool(true))] += 1
    observed = sum(n for (_, _, o, _), n in counts.items() if o)
    cache_fraud = sum(1 for i in test if extras[smoke.CACHE_LABEL][i] == "True")
    if observed != cache_fraud:
        MISMATCHES.append(
            f"reconciliation: {observed} observed frauds, the gate cache holds {cache_fraud}"
        )
    lines = [
        "RECONCILIATION of the test period's frauds (dataset labels, test segment by split.json)",
        f"  test rows {len(times)}; observed (confirmed) fraud {observed}; the gate cache's test "
        f"rows hold {cache_fraud}",
        "  variant                            fraud type                observed  true   rows",
    ]
    for (variant, kind, o, t), n in sorted(counts.items()):
        lines.append(f"  {variant:<34} {kind:<24}  {o!s:<8}  {t!s:<5}  {n}")
    by_variant = Counter()
    for (variant, _, o, _), n in counts.items():
        if o:
            by_variant[variant] += n
    lines.append(
        "  observed fraud by variant: "
        + ", ".join(f"{v} {n}" for v, n in sorted(by_variant.items()))
    )
    cache_variants = Counter(
        extras[smoke.CACHE_VARIANT][i] or "(empty)"
        for i in test
        if extras[smoke.CACHE_LABEL][i] == "True"
    )
    lines.append(
        "  the gate cache's variant column on its fraud rows: "
        + ", ".join(f"{v} {n}" for v, n in sorted(cache_variants.items()))
    )
    return lines


# ---- curve and selection (088cfd2) -------------------------------------------------------------


def fit_point(cache: Path, train_rows: int | None) -> tuple[list[str], dict[str, float]]:
    """Five seeds at one size; seed 1 with row-bootstrap and account-clustered intervals."""
    data = gate_run.load_cache(cache, train_rows=train_rows)
    test = list(data.test)
    labels = [data.labels[i] for i in test]
    accounts_all = accounts_of(cache)
    accounts = [accounts_all[i] for i in test]
    channels = [data.channels[i] for i in test]
    rows_test = data.matrix[test]
    per_seed: dict[str, list[float]] = {}
    first: Any = None
    for seed in (1, 2, 3, 4, 5):
        fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=seed)
        scores = fitted.score(rows_test).ensemble
        rows = gate.rows_of(scores, labels, channels, [True] * len(labels))
        values = gate.metrics(rows)
        for key in ("ML-GATE-01", "ML-GATE-02", "ML-GATE-03"):
            per_seed.setdefault(key, []).append(values[key])
        if seed == 1:
            first = rows
    by_rows = gate.bootstrap(first, resamples=RESAMPLES, seed=SEED)
    draws = ci.cluster_draws(accounts, labels, np.random.default_rng(SEED))
    clustered: dict[str, list[float]] = {}
    for d in draws:
        for key, value in gate.metrics(first.take(d)).items():
            clustered.setdefault(key, []).append(value)
    lines = [f"  training rows {len(data.split.train)}, test rows {len(test)}, fraud {sum(labels)}"]
    for key, name in (
        ("ML-GATE-03", "recall at 0.60"),
        ("ML-GATE-02", "recall at 1% FPR"),
        ("ML-GATE-01", "AUC"),
    ):
        values = per_seed[key]
        lines.append(
            f"    {name:<16} seed 1 {values[0]:.3f}  rows {fmt(by_rows[key])}  clustered "
            f"{fmt(ci.interval(clustered[key]))}  five seeds {statistics.mean(values):.4f} "
            f"+/- {statistics.stdev(values):.4f}"
        )
    return lines, {"recall": per_seed["ML-GATE-03"][0], "test": len(test)}


def published_curve(size: int) -> float:
    """Seed-1 recall at 0.60 of one learning-curve point, from its evidence file at CURVE_REF."""
    name = f"docs/benchmarks/m4_learning_curve_{size // 1000}k_d8083dbc.txt"
    text = ci.git("show", f"{CURVE_REF}:{name}")
    found = re.search(r"ML-GATE-03\s+Recall at 0\.60\s+([\d.]+)", text)
    if found is None:
        raise ValueError(f"{name} at {CURVE_REF} has no ML-GATE-03 line")
    return float(found.group(1))


def curve_command(args: argparse.Namespace) -> int:
    emit(["LEARNING CURVE, rebuilt as recorded, with account-clustered intervals at seed 1"])
    reads: list[tuple[str, Path, int, str]] = []
    try:
        for cache, size in (
            (args.small, 30_000),
            (args.small, 60_000),
            (args.small, 120_000),
            (args.large, 240_000),
        ):
            purpose = f"paper evidence, learning curve at {size}"
            reads.append(("round2.py curve", cache, test_rows(cache), purpose))
            block, summary = fit_point(cache, size)
            emit([f"  {size} training rows, cache {cache.name}", *block])
            published = published_curve(size)
            if round(summary["recall"], 3) != published:
                MISMATCHES.append(f"curve {size}: {summary['recall']:.4f} against {published}")
    finally:
        log_reads(reads)
    return finish("curve")


def selection_command(args: argparse.Namespace) -> int:
    from fraudshield_ml.cli import read_transactions  # noqa: PLC0415
    from fraudshield_ml.training import split as split_module  # noqa: PLC0415
    from fraudshield_ml.training.evaluation import spread  # noqa: PLC0415

    boundaries = split_module.load(args.split)
    emit([
        "TRAINING-ROW SELECTION: 30,000 training rows at each corpus depth; the same test rows,",
        "model and seeds; history = days from the corpus's first transaction to the training row",
    ])  # fmt: skip
    reads: list[tuple[str, Path, int, str]] = []
    reference_test: list[tuple[str, str]] | None = None
    try:
        for pair in args.caches:
            depth_text, path = pair.split("=", 1)
            depth, cache = int(depth_text), Path(path)
            if not cache.exists():
                reason = Path(f"{path}.reason")
                why = reason.read_text().strip() if reason.exists() else "no cache was built"
                emit([f"  corpus {depth}: NOT RUN. {why}"])
                continue
            segments = cache_column(cache, smoke.CACHE_SEGMENT)
            accounts = cache_column(cache, smoke.CACHE_ACCOUNT)
            labels = cache_column(cache, smoke.CACHE_LABEL)
            test = [
                (a, y) for a, y, s in zip(accounts, labels, segments, strict=True) if s == "test"
            ]
            if reference_test is None:
                reference_test = test
            elif test != reference_test:
                MISMATCHES.append(
                    f"selection at {depth}: the test rows differ from the first depth's"
                )
            rows = read_transactions(args.dataset, args.packs, limit=depth)
            train_pool = [
                i
                for i, r in enumerate(rows)
                if boundaries.segment_of(r.timestamp) is split_module.Segment.TRAIN
            ]
            chosen = spread(train_pool, 30_000)
            cached_train = [a for a, s in zip(accounts, segments, strict=True) if s == "train"]
            if [rows[i].account_id for i in chosen] != cached_train:
                MISMATCHES.append(
                    f"selection at {depth}: recomputed training rows differ from the cache's"
                )
            start = rows[0].timestamp
            days = sorted((rows[i].timestamp - start).total_seconds() / 86_400 for i in chosen)
            # The depth is a requested limit; a depth past the draw reads the whole draw, so the
            # rows actually read are logged too (M11: "corpus 2000000" had to be inferred).
            corpus_read = len(rows)
            del rows
            q = statistics.quantiles(days, n=4)
            short = sum(d < 30 for d in days) / len(days)
            medium = sum(d < 90 for d in days) / len(days)
            reads.append(
                (
                    "round2.py selection",
                    cache,
                    len(test),
                    f"paper evidence, selection at corpus {depth}",
                )
            )
            block, summary = fit_point(cache, None)
            emit([
                f"  corpus {depth}: history days min {days[0]:.1f}, quartiles {q[0]:.1f} / "
                f"{q[1]:.1f} / {q[2]:.1f}, max {days[-1]:.1f}; under 30 days {short:.3f}, "
                f"under 90 days {medium:.3f}; corpus rows read {corpus_read}",
                *block,
            ])  # fmt: skip
            # 400,000 rows of corpus and 30,000 training rows is the gate's own design, so it must
            # give the gate's published seed-1 recall at 0.60, or the caches differ from the gate's.
            gate_recall = ci.PUBLISHED_GATE["ML-GATE-03"]
            if depth == GATE_CORPUS and round(summary["recall"], 3) != gate_recall:
                MISMATCHES.append(
                    f"selection at {depth}: {summary['recall']:.4f} against {gate_recall}"
                )
    finally:
        log_reads(reads)
    return finish("selection")


def committed_number(path: str, pattern: str) -> int:
    """An integer read from a committed evidence file in this checkout, not typed here."""
    found = re.search(pattern, (HERE / path).read_text())
    if found is None:
        raise ValueError(f"{path} has no match for {pattern!r}")
    return int(found.group(1))


def gate_reconciliation() -> Counter[tuple[str, str, bool, bool]]:
    """The gate job's reconciliation table, read from its committed evidence file."""
    text = (HERE / "round2_gate_d8083dbc.txt").read_text()
    block = text.split("RECONCILIATION of the test period's frauds", 1)[1]
    counts: Counter[tuple[str, str, bool, bool]] = Counter()
    for line in block.splitlines():
        found = re.match(r"^  (\S+)\s+(\S+)\s+(True|False)\s+(True|False)\s+(\d+)$", line)
        if found:
            variant, kind, observed, true, n = found.groups()
            counts[(variant, kind, observed == "True", true == "True")] = int(n)
    if not counts:
        raise ValueError("round2_gate_d8083dbc.txt holds no reconciliation rows")
    return counts


# ---- full volume (PB-66's per-row scores) -------------------------------------------------------


def pb66_auc(metrics: Path) -> float:
    """The seed-1 model's AUC as PB-66 published it."""
    import json  # noqa: PLC0415

    return float(json.loads(metrics.read_text())["model_identity"]["auc"])


def cross_checks(  # noqa: PLR0913, PLR0917 - the quantities the plan's cross-checks compare
    args: argparse.Namespace,
    rows: list[dict[str, str]],
    labels: Any,
    truth: list[bool],
    reversal: Any,
    scores: Any,
    counts: Counter[tuple[str, str, bool, bool]],
) -> list[str]:
    """Plan section 4's cross-checks, each able to fail the run; references read, not typed."""
    intervals, labelled = "clustered_intervals_d8083dbc.txt", "reversal_labels_d8083dbc.txt"
    gate_counts = gate_reconciliation()
    # Label noise marks some legitimate rows as fraud; they keep a legitimate row's empty type,
    # so the check is on rows that are truly fraud, which must all carry one.
    untyped = sum(1 for r, t in zip(rows, truth, strict=True) if t and not r["fraud_type"])
    differing = sum(1 for k in set(counts) | set(gate_counts) if counts[k] != gate_counts[k])
    fraud = committed_number(intervals, r"test rows \d+, fraud (\d+)")
    checks = [
        ("test rows", len(rows), committed_number(intervals, r"test rows (\d+), fraud")),
        ("observed fraud", int(labels.sum()), fraud),
        ("reversal rows, observed fraud", int((reversal & labels).sum()),
         committed_number(labelled, r"fraud by observed label: (\d+)")),
        ("reversal rows, true fraud", int((reversal & np.asarray(truth)).sum()),
         committed_number(labelled, r"fraud by true label: (\d+)")),
        ("true-fraud rows without a fraud type", untyped, 0),
        ("cells differing from the gate job's reconciliation", differing, 0),
        ("AUC differing from PB-66's metrics.json by more than 1e-9",
         int(abs(gate.auc(scores, labels) - pb66_auc(args.metrics)) > 1e-9), 0),
    ]  # fmt: skip
    lines = ["", "CROSS-CHECKS (plan section 4)"]
    for name, got, want in checks:
        state = "MATCH" if got == want else "DIFFERS"
        if state == "DIFFERS":
            MISMATCHES.append(f"full volume: {name} {got}, expected {want}")
        lines.append(f"  {name}: {got}, expected {want}  {state}")
    return lines


def full_volume_command(args: argparse.Namespace) -> int:
    """Plan section 4: items 1, 2 and 6 on PB-66's full-volume seed-1 scores, read, not refitted."""
    import csv  # noqa: PLC0415
    import hashlib  # noqa: PLC0415

    digest = hashlib.sha256(args.scores.read_bytes()).hexdigest()
    expected = args.sha256.read_text().split()[0]
    if digest != expected:
        MISMATCHES.append(f"full volume: scores digest {digest}, PB-66 recorded {expected}")
        return finish("full-volume")
    with args.scores.open(newline="", encoding="utf-8") as file:
        rows = list(csv.DictReader(file))
    reads = [
        (
            "round2.py full-volume",
            args.scores,
            len(rows),
            "paper evidence, round two (PB-66 scores)",
        )
    ]
    try:
        labels = np.asarray([r["label_observed"] == "1" for r in rows], dtype=bool)
        scores = np.asarray([float(r["score"]) for r in rows], dtype=float)
        accounts = [r["account_id"] for r in rows]
        if not all(accounts):
            MISMATCHES.append(
                "full volume: the scores carry no account_id, so nothing can be clustered"
            )
            return finish("full-volume")
        fraud_rows = [r for r, y in zip(rows, labels, strict=True) if y]
        if not all(r["label_true"] for r in rows):
            MISMATCHES.append("full volume: label_true is blank on some rows (an older cache)")
        for column in ("variant", "fraud_type"):
            if not any(r[column] for r in fraud_rows):
                MISMATCHES.append(
                    f"full volume: {column} is blank on every fraud row (an older cache)"
                )
        if MISMATCHES:
            return finish("full-volume")
        g = Gate(
            data=None,
            extras={},
            test=list(range(len(rows))),
            labels=labels,
            accounts=accounts,
            channels=[r["channel"] for r in rows],
            draws=ci.cluster_draws(accounts, list(labels), np.random.default_rng(SEED)),
            scored=None,
            ensemble=scores,
        )
        novelty = np.asarray(
            [
                float(r[NOVELTY]) if r[NOVELTY] not in {"", "nan", "None"} else math.nan
                for r in rows
            ],
            dtype=float,
        )
        variants = np.asarray([r["variant"] for r in rows], dtype=object)
        thresholds = {"1% FPR budget": fpr_threshold(scores, labels), "flag 0.60": gate.FLAG}
        reversal = variants == REVERSAL
        emit([
            f"FULL-VOLUME MODEL (PB-66's seed-1 scores, sha256 {digest})",
            "  the draw's identity rests on PB-66's run, which regenerated and fingerprinted it;",
            "  this job reads that run's scores and has no dataset of its own to fingerprint",
            f"  test rows {len(rows)}, confirmed fraud {int(labels.sum())}, accounts "
            f"{len(set(accounts))}; AUC {gate.auc(scores, labels):.3f}",
            "",
            "REVERSAL VARIANT, full-volume model (1% FPR threshold "
            f"{thresholds['1% FPR budget']:.4f}: rows strictly above it; flag: at or above 0.60)",
            *CLUSTER_NOTE,
            *recall_block(g, "all reversal rows", reversal, thresholds),
            *recall_block(g, f"{NOVELTY} = 1", reversal & (novelty == 1), thresholds),
            *recall_block(g, f"{NOVELTY} = 0", reversal & (novelty == 0), thresholds),
            f"  reversal fraud rows with {NOVELTY} undefined: "
            f"{int((reversal & labels & np.isnan(novelty)).sum())}",
        ])  # fmt: skip
        emit(calibration_section(g))
        truth = [r["label_true"] in {"1", "True", "true"} for r in rows]
        counts: Counter[tuple[str, str, bool, bool]] = Counter(
            (r["variant"] or "base", r["fraud_type"] or "none", bool(y), t)
            for r, y, t in zip(rows, labels, truth, strict=True)
            if y or t
        )
        table = [
            f"  {v:<34} {k:<24}  {o!s:<8}  {t!s:<5}  {n}"
            for (v, k, o, t), n in sorted(counts.items())
        ]
        emit(
            [
                "",
                "RECONCILIATION from PB-66's columns (variant, fraud type, observed, true)",
                *table,
            ]
        )
        emit(cross_checks(args, rows, labels, truth, reversal, scores, counts))
    finally:
        log_reads(reads)
    return finish("full-volume")


# ---- output ------------------------------------------------------------------------------------


def machine() -> str:
    """CPU, cores and memory of the machine running this, read from it rather than typed."""
    try:
        cpu = next(
            line.split(":", 1)[1].strip()
            for line in Path("/proc/cpuinfo").read_text().splitlines()
            if line.startswith("model name")
        )
        memory = next(
            line.split()[1] for line in Path("/proc/meminfo").read_text().splitlines()
            if line.startswith("MemTotal")
        )  # fmt: skip
        return f"{cpu}; {os.cpu_count()} logical cores; {int(memory) / 1024**2:.1f} GiB"
    except (OSError, StopIteration):
        return "unknown"


def inputs(args: argparse.Namespace) -> list[str]:
    """``# evidence-input:`` lines in the format ``fs-evidence --input`` writes (keep the two
    identical): every file argument that exists, by SHA-256 and size, so a result names the cache
    it read by content (M11). Directory arguments, the dataset among them, are not hashed."""
    paths = [v for v in vars(args).values() if isinstance(v, Path)]
    paths += [Path(pair.split("=", 1)[1]) for pair in getattr(args, "caches", None) or []]
    lines = []
    for path in paths:
        if path.is_file():
            digest = hashlib.sha256()
            with path.open("rb") as handle:
                for block in iter(lambda: handle.read(1 << 20), b""):
                    digest.update(block)
            size = path.stat().st_size
            lines.append(f"# evidence-input: {digest.hexdigest()} {size} bytes {path}")
    return lines


def header(args: argparse.Namespace) -> None:
    """The stamp, printed before any work, so a run that dies still names what it was."""
    state = "clean" if not ci.git("status", "--porcelain").strip() else "DIRTY"
    code = subprocess.run(
        ["git", "rev-parse", "HEAD"],  # noqa: S607
        capture_output=True, text=True, check=False,
    ).stdout.strip()  # fmt: skip
    emit([
        f"# evidence-commit: {ci.git('rev-parse', 'HEAD').strip()}",
        f"# evidence-tree-state: {state}",
        f"# evidence-machine: {machine()}",
        *inputs(args),
        f"# command: python docs/research/paper/evidence/round2.py {' '.join(sys.argv[1:])}",
        f"# models and features: the code at {code or '(not a git checkout)'}",
        f"# draw: {args.fingerprint}; plan: docs/research/paper/evidence/round2_plan.md",
        f"# resamples: {RESAMPLES} of whole accounts, stratified by fraud, resampling seed {SEED}",
        "# exit: see RESULT at the end; a file without one is from a run that did not finish",
        "",
    ])  # fmt: skip


def emit(lines: list[str]) -> None:
    """Print a finished section at once, so a later failure cannot lose it."""
    print("\n".join(lines), flush=True)


def finish(name: str) -> int:
    if MISMATCHES:
        emit([f"\nDID NOT REPRODUCE: {'; '.join(MISMATCHES)}", "RESULT: exit 1"])
        print(f"refused: {name} did not reproduce {MISMATCHES}", file=sys.stderr)
        return 1
    emit(["", "RESULT: exit 0"])
    return 0


def log_reads(reads: list[tuple[str, Path, int, str]]) -> None:
    """Record every test-period read that happened, whether or not the run finished."""
    access = access_module()
    for command, source, rows, purpose in reads:
        access.record(command, source, rows, purpose=purpose)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fingerprint", required=True)
    commands = parser.add_subparsers(dest="command", required=True)
    g = commands.add_parser("gate")
    g.add_argument("--gate-cache", type=Path, required=True)
    g.add_argument("--battery-cache", type=Path, required=True)
    g.add_argument("--dataset", type=Path, required=True)
    g.add_argument("--split", type=Path, required=True)
    g.add_argument("--out", type=Path, required=True)
    c = commands.add_parser("curve")
    c.add_argument("--small", type=Path, required=True, help="the 340,000-row-corpus cache")
    c.add_argument("--large", type=Path, required=True, help="the 520,000-row-corpus cache")
    f = commands.add_parser("full-volume")
    f.add_argument("--scores", type=Path, required=True, help="PB-66's test_scores_seed1.csv")
    f.add_argument("--sha256", type=Path, required=True, help="PB-66's test_scores_seed1.sha256")
    f.add_argument("--metrics", type=Path, required=True, help="PB-66's pb66 metrics.json")
    s = commands.add_parser("selection")
    s.add_argument("--dataset", type=Path, required=True)
    s.add_argument("--packs", type=Path, required=True)
    s.add_argument("--split", type=Path, required=True)
    s.add_argument("caches", nargs="+", help="depth=cache path, one per corpus depth")
    args = parser.parse_args(argv[1:])
    if args.fingerprint != ci.EXPECTED_DRAW:
        print(
            f"refused: draw {args.fingerprint[:8]} is not {ci.EXPECTED_DRAW[:8]}", file=sys.stderr
        )
        return 1
    header(args)
    if args.command == "gate":
        args.out.mkdir(parents=True, exist_ok=True)
        return gate_command(args)
    if args.command == "curve":
        return curve_command(args)
    if args.command == "full-volume":
        return full_volume_command(args)
    return selection_command(args)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
