"""Rank generator parameters by how much they move the dataset's headline properties.

Which parameters deserve the effort of finding a real source is a question about influence, and
influence here is measurable rather than a matter of opinion: perturb one parameter, regenerate a
small dataset with the same seed, and see what moves.

Method (recorded in ``docs/research/parameter_influence.md``):

1. A baseline dataset is generated at ``--rows`` with the repository parameters.
2. For every parameter, the value is perturbed by ``--step`` (10% by default): numbers are scaled,
   integers move by at least one, lists and mappings have every entry scaled, and booleans and
   strings are left alone (they are reported as not perturbable rather than as uninfluential).
3. The dataset is regenerated with that one change and compared with the baseline over the
   headline properties: row count, amounts, channel mix, country mix, segment mix, fraud rate,
   the customer, agent and merchant populations, and the monthly and weekday distributions.
4. The influence score is the largest change any property shows, in units of the tolerance that
   property is held to (0.5 pp for the mixes, 1% relative for counts and amounts), so that scores
   from different kinds of property are comparable.

A parameter that the generator refuses to run with (the calibration guards) scores as ``refused``
and is ranked at the top: a value that cannot move without breaking the run is as influential as
the run allows.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import sys
import tempfile
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import pyarrow.compute as pc
import pyarrow.dataset as ds

from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import ParameterError, ParameterSet, load_parameters

# A property's tolerance: how much change counts as one unit of influence.
SHARE_TOLERANCE = 0.005  # 0.5 percentage points, the SRS 7.1 mix tolerance
RELATIVE_TOLERANCE = 0.01  # 1% for counts, amounts and populations
DEFAULT_STEP = 0.10
DEFAULT_ROWS = 20_000


@dataclass(frozen=True)
class Trial:
    """How the ranking is run: dataset size, seed, and how far each parameter is moved."""

    rows: int = DEFAULT_ROWS
    seed: int = 20260917
    step: float = DEFAULT_STEP


@dataclass(frozen=True)
class Influence:
    key: str
    score: float
    worst_property: str
    detail: str
    perturbed: bool = True


@dataclass
class Profile:
    """Headline properties of one generated dataset."""

    shares: dict[str, float] = field(default_factory=dict)
    counts: dict[str, float] = field(default_factory=dict)

    def compare(self, other: Profile) -> tuple[float, str, str]:
        worst, score, detail = "", 0.0, "no change"
        for name, value in self.shares.items():
            change = abs(other.shares.get(name, 0.0) - value)
            if change / SHARE_TOLERANCE > score:
                worst, score = name, change / SHARE_TOLERANCE
                detail = f"{value:.4f} to {other.shares.get(name, 0.0):.4f} ({change * 100:.2f} pp)"
        for name, value in self.counts.items():
            other_value = other.counts.get(name, 0.0)
            change = abs(other_value - value) / value if value else float(other_value > 0)
            if change / RELATIVE_TOLERANCE > score:
                worst, score = name, change / RELATIVE_TOLERANCE
                detail = f"{value:,.2f} to {other_value:,.2f} ({change * 100:.2f}%)"
        return score, worst, detail


def profile(root: Path) -> Profile:
    """Measure the headline properties the sourcing pass cares about."""
    transactions = ds.dataset(root / "transactions", format="parquet", partitioning="hive")
    table = transactions.to_table(
        columns=["amount_rwf", "channel", "currency", "account_id", "counterparty_id", "agent_id"]
    )
    labels = ds.dataset(root / "labels", format="parquet", partitioning="hive").to_table(
        columns=["is_fraud_true"]
    )
    rows = table.num_rows
    result = Profile()
    result.counts["rows"] = float(rows)
    amounts = pc.cast(table["amount_rwf"], "float64").to_numpy()
    result.counts["mean_amount_rwf"] = float(np.mean(amounts)) if rows else 0.0
    result.counts["median_amount_rwf"] = float(np.median(amounts)) if rows else 0.0
    result.counts["accounts"] = float(pc.count_distinct(table["account_id"]).as_py())
    result.counts["counterparties"] = float(pc.count_distinct(table["counterparty_id"]).as_py())
    result.counts["agents"] = float(pc.count_distinct(table["agent_id"]).as_py() or 0)
    for column, prefix in (("channel", "channel"), ("currency", "currency")):
        for entry in pc.value_counts(table[column]).to_pylist():
            result.shares[f"{prefix}:{entry['values']}"] = entry["counts"] / rows
    result.shares["fraud_rate"] = float(
        pc.sum(pc.cast(labels["is_fraud_true"], "int64")).as_py() or 0
    ) / max(rows, 1)
    months = sorted(p.name for p in (root / "transactions").glob("month=*"))
    for month in months:
        monthly = ds.dataset(root / "transactions" / month, format="parquet").count_rows()
        result.shares[f"month:{month}"] = monthly / rows
    return result


def _scaled(value: Any, step: float) -> Any:
    """Perturb a parameter value, or return None when it has no numeric content."""
    if isinstance(value, bool | str):
        return None
    if isinstance(value, int):
        return value + max(1, round(abs(value) * step))
    if isinstance(value, float):
        return value * (1.0 + step)
    if isinstance(value, list):
        scaled = [_scaled(item, step) for item in value]
        return None if any(item is None for item in scaled) else scaled
    if isinstance(value, dict):
        scaled_map = {k: _scaled(v, step) for k, v in value.items()}
        return None if any(v is None for v in scaled_map.values()) else scaled_map
    return None


def _with_value(parameters: ParameterSet, key: str, value: Any) -> ParameterSet:
    changed = dict(parameters.parameters)
    original = changed[key]
    changed[key] = type(original)(**{**original.__dict__, "value": value})
    return ParameterSet(changed, parameters.descriptions)


def _generate(parameters: ParameterSet, seed: int, rows: int, output: Path) -> Profile:
    generate(build_config(parameters, seed=seed, total_rows=rows), output, chunk_size=64)
    return profile(output)


def influences(
    parameters: ParameterSet,
    trial: Trial,
    workspace: Path,
    keys: Sequence[str] | None = None,
) -> list[Influence]:
    """Rank ``keys`` (every parameter by default) by what perturbing each one moves."""
    baseline = _generate(parameters, trial.seed, trial.rows, workspace / "baseline")
    chosen = set(keys) if keys is not None else None
    results: list[Influence] = []
    for parameter in sorted(parameters, key=lambda p: p.key):
        if chosen is not None and parameter.key not in chosen:
            continue
        value = _scaled(parameter.value, trial.step)
        if value is None:
            results.append(
                Influence(parameter.key, math.inf, "", "not numeric; not perturbable", False)
            )
            continue
        target = workspace / "trial"
        shutil.rmtree(target, ignore_errors=True)
        try:
            measured = _generate(
                _with_value(parameters, parameter.key, value), trial.seed, trial.rows, target
            )
        except (ParameterError, ValueError, KeyError) as error:
            results.append(
                Influence(parameter.key, math.inf, "run refused", str(error)[:160], True)
            )
            continue
        score, worst, detail = baseline.compare(measured)
        results.append(Influence(parameter.key, score, worst, detail))
        print(f"{parameter.key:55s} {score:9.2f} {worst}", file=sys.stderr, flush=True)
    return sorted(results, key=lambda r: (-r.score, r.key))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument("--rows", type=int, default=DEFAULT_ROWS)
    parser.add_argument("--seed", type=int, default=20260917)
    parser.add_argument("--step", type=float, default=DEFAULT_STEP)
    parser.add_argument("--output", type=Path, required=True, help="JSON ranking to write")
    parser.add_argument(
        "--only", action="append", help="rank just this parameter (repeatable); default is all"
    )
    args = parser.parse_args(argv)
    parameters = load_parameters()
    with tempfile.TemporaryDirectory(prefix="fs-sensitivity-") as workspace:
        trial = Trial(rows=args.rows, seed=args.seed, step=args.step)
        ranking = influences(parameters, trial, Path(workspace), args.only)
    # The ranking describes one parameter set, so record its digest: a later reader can then tell
    # whether this file still describes the parameters in the repository.
    digest = hashlib.sha256(
        json.dumps(
            {
                parameter.key: parameter.value
                for parameter in sorted(parameters, key=lambda x: x.key)
            },
            sort_keys=True,
            default=str,
        ).encode()
    ).hexdigest()
    args.output.write_text(
        json.dumps(
            {
                "rows": args.rows,
                "seed": args.seed,
                "step": args.step,
                "parameter_values_sha256": digest,
                "parameter_count": len(parameters),
                "ranked": len(ranking),
                "share_tolerance": SHARE_TOLERANCE,
                "relative_tolerance": RELATIVE_TOLERANCE,
                "parameters": [
                    {
                        "key": r.key,
                        "score": None if math.isinf(r.score) else round(r.score, 3),
                        "worst_property": r.worst_property,
                        "detail": r.detail,
                        "perturbed": r.perturbed,
                    }
                    for r in ranking
                ],
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    print(f"ranked {len(ranking)} parameters into {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
