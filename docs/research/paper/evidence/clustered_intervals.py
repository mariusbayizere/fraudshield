"""Account-clustered intervals for the paper's model results, and why a group loses to its member.

Every interval the paper reported was a bootstrap or a closed form that treats test rows as
independent. Fraud on this benchmark is staged as multi-row incidents on one account, so rows of one
incident are correlated and those intervals are too narrow by an unknown amount. This script refits
the declared gate model and the diagnostic battery exactly as `m4-complete` does, from the same
feature caches and seeds, keeps every per-row score (PB-142), and resamples **accounts** rather than
rows, so all rows of an account, and so of every incident on it, enter or leave a resample together.

It also measures why the velocity group alone (0.860 in the battery) scores below one of its own
members, `velocity_ratio_1h_vs_30d`, alone (0.879): the diagnostic booster's test AUC across its
boosting rounds, with and without class weighting, against that feature's own AUC on the same rows.

Run with the `m4-complete` worktree's environment, so the models are the ones the paper reports:
    cd <m4-complete worktree> && uv run --package fraudshield-ml python \\
        <this repository>/docs/research/paper/evidence/clustered_intervals.py \\
        --gate-cache features_gate.parquet --battery-cache features_full.parquet \\
        --fingerprint <draw fingerprint> --scores-out <dir> > clustered_intervals_d8083dbc.txt

Reads test-period rows, so it records the read in the test-set access log through the project's
own `record` function, loaded from this repository's tree.
"""

from __future__ import annotations

import argparse
import gzip
import importlib.util
import math
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np
import xgboost as xgb

from fraudshield_ml.cli import REGISTRY, Battery
from fraudshield_ml.training import battery, gate, gate_run, model, smoke

ROOT = Path(__file__).resolve().parents[4]
ACCESS = ROOT / "ml/src/fraudshield_ml/training/access.py"
EXPECTED_DRAW = "d8083dbc742c20437bf3d060614f88849059eb8cf12bd0d3bbb092b518e6be32"
RESAMPLES = 1000
#: The gate's seed: `fs-features gate --seeds 1 2 3 4 5` fits and bootstraps at the first.
SEED = 1
#: The battery's seed: `fs-features battery` was run without --seed, so its default (cli.py).
BATTERY_SEED = 20260917
#: What m4-complete's evidence files print, to three places, so a reproduction is checked rather
#: than assumed (docs/benchmarks/m4_gate_d8083dbc_v3.txt, m4_battery_d8083dbc_e1.txt).
PUBLISHED_GATE = {"ML-GATE-01": 0.970, "ML-GATE-02": 0.871, "ML-GATE-03": 0.736}
PUBLISHED_BATTERY_AUC = 0.972
PUBLISHED_GROUP_ALONE = {"velocity": 0.860, "counterparty": 0.925, "temporal": 0.856}
#: Headline figures that did not reproduce; any makes the run exit 1 after writing its evidence.
MISMATCHES: list[str] = []
GATE_KEYS = (
    ("ML-GATE-01", "AUC-ROC"),
    ("ML-GATE-02", "recall at 1% FPR"),
    ("ML-GATE-03", "recall at 0.60"),
    ("ML-GATE-04", "F1 at 0.60"),
    ("ML-GATE-06", "FNR at 0.60"),
    ("ML-GATE-07", "MOBILE_MONEY AUC"),
    ("ML-GATE-08", "USSD AUC"),
    ("ML-GATE-09", "AGENT_BANKING AUC"),
)


def git(*args: str) -> str:
    return subprocess.run(  # noqa: S603 - fixed git command
        ["git", *args],  # noqa: S607
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def cluster_draws(accounts: list[str], labels: list[bool], rng: Any) -> list[Any]:
    """Row index arrays for `RESAMPLES` resamples of whole accounts, drawn with replacement.

    Stratified like the row bootstrap it is compared with (gate.bootstrap draws fraud and
    legitimate rows separately): accounts with any fraud row and accounts with none are resampled
    separately, so the difference between the two intervals is the clustering and nothing else.
    """
    by_account: dict[str, list[int]] = {}
    for i, account in enumerate(accounts):
        by_account.setdefault(account, []).append(i)
    fraud = [np.asarray(r) for r in by_account.values() if any(labels[i] for i in r)]
    clean = [np.asarray(r) for r in by_account.values() if not any(labels[i] for i in r)]
    draws = []
    for _ in range(RESAMPLES):
        picked = [fraud[c] for c in rng.integers(0, len(fraud), len(fraud))]
        picked += [clean[c] for c in rng.integers(0, len(clean), len(clean))]
        draws.append(np.concatenate(picked))
    return draws


def interval(values: list[float]) -> tuple[float, float]:
    defined = np.array([v for v in values if not math.isnan(v)])
    return float(np.percentile(defined, 2.5)), float(np.percentile(defined, 97.5))


def auc_of(scores: Any, labels: Any) -> float:
    return gate.auc(np.asarray(scores, dtype=float), np.asarray(labels, dtype=bool))


def gate_section(cache: Path, out: Path, lines: list[str]) -> int:
    vectors, extras = smoke.cache_read_any(cache)
    data = gate_run.load(vectors, extras)
    accounts = [extras[smoke.CACHE_ACCOUNT][i] for i in data.test]
    labels = [data.labels[i] for i in data.test]
    channels = [data.channels[i] for i in data.test]
    fitted = model.fit_ensemble(data.matrix, data.labels, data.split, seed=SEED)
    scored = fitted.score([data.matrix[i] for i in data.test])
    covered = gate_run._covered(fitted, data, scored.ensemble)
    rows = gate.rows_of(scored.ensemble, labels, channels, covered)
    point = gate.metrics(rows)
    by_rows = gate.bootstrap(rows, resamples=RESAMPLES, seed=SEED)
    draws: dict[str, list[float]] = {}
    for index in cluster_draws(accounts, labels, np.random.default_rng(SEED)):
        for key, value in gate.metrics(rows.take(index)).items():
            draws.setdefault(key, []).append(value)
    with gzip.open(out / "gate_scores_d8083dbc.csv.gz", "wt") as file:
        file.write(
            "test_row,account_id,label,channel,ensemble_calibrated,"
            "xgboost_calibrated,lightgbm_calibrated\n"
        )
        for k, i in enumerate(data.test):
            file.write(
                f"{i},{accounts[k]},{int(labels[k])},{channels[k]},{scored.ensemble[k]!r},"
                f"{scored.xgboost[k]!r},{scored.lightgbm[k]!r}\n"
            )
    frauds = [a for a, y in zip(accounts, labels, strict=True) if y]
    lines += [
        "GATE MODEL (D-05 ensemble, seed 1), refitted from the gate cache",
        f"  test rows {len(labels)}, fraud {sum(labels)}, accounts {len(set(accounts))}, "
        f"accounts with fraud {len(set(frauds))}",
        "  reproduction against m4_gate_d8083dbc_v3.txt (three places):",
    ]
    for key, published in PUBLISHED_GATE.items():
        state = "MATCH" if round(point[key], 3) == published else "DIFFERS"
        if state == "DIFFERS":
            MISMATCHES.append(f"gate {key}")
        lines.append(f"    {key}: published {published:.3f}, reproduced {point[key]:.3f}  {state}")
    lines += ["", "  metric                    value    row bootstrap      account-clustered"]
    for key, name in GATE_KEYS:
        low, high = by_rows[key]
        c_low, c_high = interval(draws[key])
        lines.append(
            f"  {key} {name:<20} {point[key]:.3f}  [{low:.3f}, {high:.3f}]   "
            f"[{c_low:.3f}, {c_high:.3f}]"
        )
    return len(labels)


def bench_from(cache: Path) -> tuple[Battery, list[str]]:
    vectors, extras = smoke.cache_read_any(cache)
    names = smoke.trainable_features()
    labels = [v == "True" for v in extras[smoke.CACHE_LABEL]]
    segment = extras[smoke.CACHE_SEGMENT]
    by = {n: [i for i, s in enumerate(segment) if s == n] for n in ("train", "test", "calibration")}
    encoded = smoke.encode_categoricals(vectors, labels, extras[smoke.CACHE_ACCOUNT], by["train"])
    bench = Battery(
        names=names,
        matrix=[
            [encoded[n][i] if n in encoded else float(vectors[i][n]) for n in names]
            for i in range(len(vectors))
        ],
        labels=labels,
        country=extras[smoke.CACHE_COUNTRY],
        channel=extras[smoke.CACHE_CHANNEL],
        variant=extras[smoke.CACHE_VARIANT],
        train=by["train"],
        test=by["test"],
        calibration=by["calibration"],
        seed=BATTERY_SEED,
    )
    return bench, extras[smoke.CACHE_ACCOUNT]


def battery_section(cache: Path, out: Path, lines: list[str]) -> int:
    bench, all_accounts = bench_from(cache)
    held = bench.labels_of(bench.test)
    accounts = [all_accounts[i] for i in bench.test]
    draws = cluster_draws(accounts, held, np.random.default_rng(SEED))
    held_arr = np.asarray(held, dtype=bool)

    def clustered_auc(scores: list[float]) -> tuple[float, float]:
        s = np.asarray(scores, dtype=float)
        return interval([gate.auc(s[d], held_arr[d]) for d in draws])

    full = bench.fit(bench.train, bench.test)
    headline = auc_of(full, held)
    state = "MATCH" if round(headline, 3) == PUBLISHED_BATTERY_AUC else "DIFFERS"
    if state == "DIFFERS":
        MISMATCHES.append("battery full model")
    lines += [
        "",
        "DIAGNOSTIC BATTERY (one untuned XGBoost, seed 1), refitted from the battery cache",
        f"  full model: published {PUBLISHED_BATTERY_AUC:.3f}, reproduced {headline:.3f}  {state}",
        "  group alone          AUC     row interval (Hanley-McNeil)   account-clustered",
    ]
    groups: dict[str, list[int]] = {}
    for i, name in enumerate(bench.names):
        groups.setdefault(REGISTRY[name].group.name.lower(), []).append(i)
    alone_scores: dict[str, list[float]] = {}
    for group, members in sorted(groups.items()):
        reduced = [[row[i] for i in members] for row in bench.matrix]
        scores = smoke.fit_and_score(
            reduced, bench.labels, bench.train, bench.test, seed=BATTERY_SEED
        )
        alone_scores[group] = scores
        result = battery.score(group, scores, held)
        c_low, c_high = clustered_auc(scores)
        mark = ""
        if group in PUBLISHED_GROUP_ALONE:
            same = round(result.auc, 3) == PUBLISHED_GROUP_ALONE[group]
            if not same:
                MISMATCHES.append(f"battery {group} alone")
            mark = "  MATCH" if same else f"  DIFFERS (published {PUBLISHED_GROUP_ALONE[group]})"
        lines.append(
            f"  {group:<18} {result.auc:.3f}   ±{result.interval:.3f}"
            f"                     [{c_low:.3f}, {c_high:.3f}]{mark}"
        )
    variants = [bench.variant[i] for i in bench.test]
    results, threshold = battery.by_variant(full, held, variants)
    lines += [
        "",
        f"  variants at the 1% FPR threshold {threshold:.4f} (Wilson vs account-clustered)",
    ]
    full_arr = np.asarray(full, dtype=float)
    variant_arr = np.asarray(variants, dtype=object)
    for result in results:
        low, high = result.recall_interval
        mask = held_arr & (variant_arr == result.variant)
        rows_of_kind = np.flatnonzero(mask)
        kind_accounts = len({accounts[i] for i in rows_of_kind})
        recalls = []
        for d in draws:
            picked = d[mask[d]]
            if len(picked):
                recalls.append(float((full_arr[picked] > threshold).mean()))
        c_low, c_high = interval(recalls)
        lines.append(
            f"    {result.variant or 'base':<34} {result.detected}/{result.fraud} on "
            f"{kind_accounts} accounts  Wilson [{low:.3f}, {high:.3f}]  "
            f"clustered [{c_low:.3f}, {c_high:.3f}]"
        )
    with gzip.open(out / "battery_scores_d8083dbc.csv.gz", "wt") as file:
        file.write(
            "test_row,account_id,label,variant,country,channel,full,"
            + ",".join(f"alone_{g}" for g in sorted(alone_scores))
            + "\n"
        )
        for k, i in enumerate(bench.test):
            alone = ",".join(repr(alone_scores[g][k]) for g in sorted(alone_scores))
            file.write(
                f"{i},{accounts[k]},{int(held[k])},{variants[k]},{bench.country[i]},"
                f"{bench.channel[i]},{full[k]!r},{alone}\n"
            )
    velocity_section(bench, groups["velocity"], held, alone_scores["velocity"], lines)
    return len(held)


def velocity_section(
    bench: Battery,
    members: list[int],
    held: list[bool],
    battery_scores: list[float],
    lines: list[str],
) -> None:
    """Why the velocity group alone can score below one of its members alone."""
    ratio = bench.names.index("velocity_ratio_1h_vs_30d")
    feature_auc = auc_of([bench.matrix[i][ratio] for i in bench.test], held)
    feature_auc = max(feature_auc, 1 - feature_auc)
    train = xgb.DMatrix(
        [[bench.matrix[i][j] for j in members] for i in bench.train],
        label=[float(bench.labels[i]) for i in bench.train],
        missing=float("nan"),
    )
    test = xgb.DMatrix(
        [[bench.matrix[i][j] for j in members] for i in bench.test], missing=float("nan")
    )
    positives = sum(bench.labels[i] for i in bench.train)
    weight = (len(bench.train) - positives) / positives
    lines += [
        "",
        "VELOCITY GROUP ALONE AGAINST ITS STRONGEST MEMBER (same test rows)",
        f"  velocity_ratio_1h_vs_30d alone, raw ranking: {feature_auc:.3f}",
        f"  training rows {len(bench.train)}, fraud {positives}",
        "  booster test AUC by boosting round (the battery stops at round "
        f"{smoke.BOOSTING_ROUNDS}):",
        "    rounds   as the battery fits it   with scale_pos_weight",
    ]
    plain = xgb.train(
        {**smoke.MODEL_PARAMETERS, "seed": BATTERY_SEED}, train, smoke.BOOSTING_ROUNDS
    )
    weighted = xgb.train(
        {**smoke.MODEL_PARAMETERS, "seed": BATTERY_SEED, "scale_pos_weight": weight},
        train,
        smoke.BOOSTING_ROUNDS,
    )
    final = auc_of(plain.predict(test), held)
    reference = auc_of(battery_scores, held)
    same = abs(final - reference) < 1e-12
    if not same:
        MISMATCHES.append("velocity booster against the battery's velocity-alone model")
    lines.append(
        f"  consistency: this booster at round {smoke.BOOSTING_ROUNDS} gives {final:.4f}, the "
        f"battery's velocity-alone model {reference:.4f}  {'MATCH' if same else 'DIFFERS'}"
    )
    for rounds in (1, 5, 10, 25, 50, 100, smoke.BOOSTING_ROUNDS):
        a = auc_of(plain.predict(test, iteration_range=(0, rounds)), held)
        b = auc_of(weighted.predict(test, iteration_range=(0, rounds)), held)
        lines.append(f"    {rounds:>6}   {a:.3f}                    {b:.3f}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gate-cache", type=Path, required=True)
    parser.add_argument("--battery-cache", type=Path, required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--scores-out", type=Path, required=True)
    args = parser.parse_args(argv[1:])
    if args.fingerprint != EXPECTED_DRAW:
        print(f"refused: draw {args.fingerprint[:8]} is not {EXPECTED_DRAW[:8]}", file=sys.stderr)
        return 1
    state = "clean" if not git("status", "--porcelain").strip() else "DIRTY"
    args.scores_out.mkdir(parents=True, exist_ok=True)
    lines: list[str] = []
    gate_rows = gate_section(args.gate_cache, args.scores_out, lines)
    battery_rows = battery_section(args.battery_cache, args.scores_out, lines)
    spec = importlib.util.spec_from_file_location("tree_access", ACCESS)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {ACCESS}")
    access = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(access)
    for command, cache, rows in (
        ("clustered_intervals.py (gate)", args.gate_cache, gate_rows),
        ("clustered_intervals.py (battery)", args.battery_cache, battery_rows),
    ):
        access.record(command, cache, rows, purpose="paper evidence: account-clustered intervals")
    print(f"# evidence-commit: {git('rev-parse', 'HEAD').strip()}")
    print(f"# evidence-tree-state: {state}")
    print(
        "# command: python docs/research/paper/evidence/clustered_intervals.py "
        f"--gate-cache {args.gate_cache} --battery-cache {args.battery_cache}"
    )
    print(f"# exit: {1 if MISMATCHES else 0}")
    print(f"# models and features: fraudshield-ml at m4-complete; draw {args.fingerprint}")
    print(
        f"# resamples: {RESAMPLES} of whole accounts, seed {SEED}; per-row scores in "
        f"{args.scores_out}"
    )
    print()
    print("\n".join(lines))
    if MISMATCHES:
        print(f"\nDID NOT REPRODUCE: {', '.join(MISMATCHES)}")
        print(f"refused: did not reproduce {MISMATCHES}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
