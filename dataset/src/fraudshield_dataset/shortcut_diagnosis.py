"""Diagnose the shortcut detector across scales, on clean and deliberately leaky data.

Owner direction (M2 milestone review): do not adjust the band again. Establish whether a clean
30,159-row dataset failing its own anti-leakage gate is a small-sample artefact or a real finding
about the generator, and locate the scale at which the detector is usable.

Three questions, and the second is the one that decides whether the detector is worth having:

1. **False alarm.** On clean data, does the AUC sit at 0.5 and how often does it fall outside its
   own band? A persistent displacement from 0.5 would mean the generator really does leak.
2. **Power, and at what subtlety.** With a shortcut deliberately planted, does the gate fire, and
   how weak a leak can it still see? A detector that cannot catch a planted leak at a given scale
   is useless there whatever its band, so a clean pass at that scale would mean nothing.
3. **Where power saturates**, which is the minimum scale worth quoting.

Plant strengths: ``1.0`` is a perfect oracle and tests only liveness; ``0.6`` is the subtle case
worth defending; ``0.5`` carries no information at all and is a negative control on the plant
itself, where the detector should be as quiet as it is on clean data. Without that control, "the
gate fired" cannot be distinguished from "the gate fires at anything that touches the data".

Scales below :data:`SCENARIO_MINIMUM_ROWS` are kept deliberately, but they measure something
different: the dataset a development run *actually* produces, which is missing at least one fraud
scenario (M2 milestone review, MAJOR M-4). Those runs waive the scenario check and each record
carries ``scenarios_not_staged`` from that run's own manifest, so the deficiency travels with the
datum rather than living in prose.

Two failures shaped the implementation. Results were once written to a session scratch directory and
lost with the session, so output goes to a path inside the repository. And a single long-lived
process accumulated memory across tiers until the host reaped it, so each unit of work runs in its
own subprocess and the sweep resumes from the JSONL it has already written.

Run::

    $ uv run python -m fraudshield_dataset.shortcut_diagnosis \\
          --out docs/reviews/M2/shortcut_diagnosis.jsonl --work /tmp/diag
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import ParameterError, load_parameters
from fraudshield_dataset.realism.checks import run_checks

# The smallest run at which the shipped parameters stage all eight scenarios (MAJOR M-4).
SCENARIO_MINIMUM_ROWS = 170_000
# Cheapest first, so a run cut short still holds the tiers below it.
SCALES = (10_000, 30_000, 60_000, 100_000, 200_000, 300_000, 500_000, 1_000_000)
BASE_SEEDS = (20260917, 20260918, 20260919, 20260920, 20260921)
# Five seeds put a false-alarm *rate* inside an interval far too wide to act on: 1 of 5 spans about
# 5% to 45%. The small-n end is where the failure occurs and where a rate has to be quoted, so it
# gets fifteen. Larger scales keep five, enough for the saturation trend they are there to show.
EXTRA_SEEDS = tuple(20260922 + index for index in range(10))
DENSE_SCALES = frozenset({10_000, 30_000, 60_000})
# Repeated seeds at one size above the minimum, to replace a three-point series that was never
# evidence of stability and whose points came from structurally different datasets.
STABILITY_ROWS = 300_000
STABILITY_SEEDS = tuple(20260917 + index for index in range(10))
PLANT_STRENGTHS = (1.0, 0.6, 0.5)
# The negative control earns its place where false alarms actually occur. Above this the detector is
# demonstrably quiet on clean data, so the control adds little and costs the most reap-prone time.
CONTROL_MAX_ROWS = 100_000


def seeds_for(rows: int) -> tuple[int, ...]:
    if rows == STABILITY_ROWS:
        return tuple(dict.fromkeys(BASE_SEEDS + STABILITY_SEEDS))
    return BASE_SEEDS + EXTRA_SEEDS if rows in DENSE_SCALES else BASE_SEEDS


def strengths_for(rows: int) -> tuple[float, ...]:
    return tuple(s for s in PLANT_STRENGTHS if s != 0.5 or rows <= CONTROL_MAX_ROWS)


def plant_id_prefix_leak(source: Path, target: Path, strength: float, seed: int) -> Path:
    """Copy the dataset and correlate the first byte of ``transaction_id`` with the label.

    ``strength`` is the probability that a row's marker tells the truth about its label; a row that
    does not tell the truth gets the other marker, so 0.5 carries no information at all.
    """
    shutil.copytree(source, target, dirs_exist_ok=True)
    rng = np.random.default_rng(seed)
    for month in sorted((target / "transactions").glob("month=*")):
        path = month / "part-0000.parquet"
        labels = pq.read_table(target / "labels" / month.name / "part-0000.parquet")
        transactions = pq.read_table(path)
        observed = labels["is_fraud_observed"].to_pylist()
        ids = transactions["transaction_id"].to_pylist()
        truthful = rng.random(len(ids)) < strength
        planted = [
            ("ff" if (flag == tell) else "00") + value[2:]
            for value, flag, tell in zip(ids, observed, truthful, strict=True)
        ]
        index = transactions.schema.get_field_index("transaction_id")
        pq.write_table(
            transactions.set_column(index, "transaction_id", pa.array(planted, pa.string())), path
        )
    return target


def measure(root: Path, rows: int, seed: int) -> dict[str, object]:
    config = build_config(load_parameters(), seed=seed, total_rows=rows)
    results, measures = run_checks(root, config, full=False)
    check = next(r for r in results if r.name == "shortcut detector")
    single = measures["single_feature_auc"]
    worst = max(single, key=lambda name: single[name])
    return {
        "auc": float(measures["shortcut_detector_auc"]),
        "band": float(measures["shortcut_detector_band"]),
        "passed": bool(check.passed),
        "detector_rows": check.value,
        # Carried for the stability question: the same runs answer both.
        "single_feature_max": float(single[worst]),
        "single_feature_worst": worst,
    }


def _not_staged(root: Path) -> list[str]:
    manifest = json.loads((root / "manifest.json").read_text(encoding="utf-8"))
    staged = manifest.get("scenarios_not_staged", [])
    return list(staged) if isinstance(staged, list) else []


def run_unit(rows: int, seed: int, out_path: Path, workdir: Path) -> None:
    """Measure one ``(rows, seed)`` pair and append its records.

    One subprocess runs exactly one of these, which is what bounds the sweep's memory.
    """
    below = rows < SCENARIO_MINIMUM_ROWS
    clean = workdir / f"clean-{rows}-{seed}"
    records: list[dict[str, object]] = []
    try:
        if not clean.exists():
            generate(
                build_config(load_parameters(), seed=seed, total_rows=rows),
                clean,
                allow_missing_scenarios=below,
            )
    except ParameterError as error:
        refusal = _record((rows, seed), "refused", None, (below, []), error=str(error))
        _append(out_path, [refusal])
        shutil.rmtree(clean, ignore_errors=True)
        return

    missing = _not_staged(clean)
    started = time.time()
    context = (below, missing)
    records.append(_record((rows, seed), "clean", None, context, **measure(clean, rows, seed)))
    for strength in strengths_for(rows):
        leaky = workdir / f"leaky-{rows}-{seed}-{strength}"
        plant_id_prefix_leak(clean, leaky, strength, seed)
        records.append(
            _record((rows, seed), "leaky", strength, context, **measure(leaky, rows, seed))
        )
        shutil.rmtree(leaky, ignore_errors=True)
    records[-1]["elapsed_s"] = round(time.time() - started, 1)
    _append(out_path, records)
    shutil.rmtree(clean, ignore_errors=True)


def _record(
    unit: tuple[int, int],
    variant: str,
    strength: float | None,
    context: tuple[bool, list[str]],
    **rest: object,
) -> dict[str, object]:
    """One JSONL record. Every field that qualifies the number travels with the number.

    ``context`` carries whether the run was below the scenario minimum and which scenarios it
    could not stage, so a sub-minimum datum is self-labelling rather than needing prose beside it.
    """
    rows, seed = unit
    below_minimum, not_staged = context
    return {
        "rows": rows,
        "seed": seed,
        "variant": variant,
        "strength": strength,
        "below_scenario_minimum": below_minimum,
        "scenarios_not_staged": not_staged,
        **rest,
    }


def _append(out_path: Path, records: list[dict[str, object]]) -> None:
    with out_path.open("a", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, sort_keys=True) + "\n")
            handle.flush()
            print(record, flush=True)


def done_pairs(out_path: Path) -> set[tuple[int, int]]:
    """``(rows, seed)`` pairs already measured, so an interrupted sweep resumes rather than repeats.

    A refusal settles its pair permanently: it is deterministic for that pair, so retrying it on
    every restart would never terminate.
    """
    done: set[tuple[int, int]] = set()
    if not out_path.exists():
        return done
    for line in out_path.read_text(encoding="utf-8").splitlines():
        try:
            record = json.loads(line)
            if record["variant"] in ("clean", "refused"):
                done.add((record["rows"], record["seed"]))
        except (json.JSONDecodeError, KeyError):
            continue  # a line truncated by a kill mid-write
    return done


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--unit", type=int, nargs=2, metavar=("ROWS", "SEED"))
    args = parser.parse_args(argv)
    args.work.mkdir(parents=True, exist_ok=True)

    if args.unit:
        run_unit(args.unit[0], args.unit[1], args.out, args.work)
        return 0

    done = done_pairs(args.out)
    for rows in SCALES:
        for seed in seeds_for(rows):
            if (rows, seed) in done:
                continue
            # One subprocess per unit. A single process accumulated memory across tiers until the
            # host reaped it; this bounds the sweep's footprint to one unit's peak.
            subprocess.run(  # noqa: S603 - fixed argv, no shell
                [
                    sys.executable,
                    "-m",
                    "fraudshield_dataset.shortcut_diagnosis",
                    "--out",
                    str(args.out),
                    "--work",
                    str(args.work),
                    "--unit",
                    str(rows),
                    str(seed),
                ],
                check=False,
            )
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
