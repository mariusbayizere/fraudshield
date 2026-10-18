"""The temporal split, published as data (D-07, PB-48).

`plan_split` computes four boundaries from the calibrated volume, and until now none of them
reached the published output: a consumer holding the Parquet could not say which rows were
training rows. Reconstructing them is not a small job — the boundaries are defined on *planned*
volume with a seasonal factor per month, not on the realised rows — and two consumers
reconstructing them would disagree without either noticing. So the generator records them, and
everything downstream reads what it recorded.

**Who computes what.** The boundaries come from the planner, once, at generation time, and are
written into `manifest.json`. This module measures the segments those boundaries cut — row counts
and fraud rates — and never re-derives a boundary. A dataset generated before the block existed
refuses here rather than being reconstructed, because a reconstructed boundary that is almost
right is worse than an absent one.

**The segments overlap on purpose.** `calibration` is the tail of `validation`, not a fifth
disjoint block: D-07 calibrates on the last part of the validation period. `embargo` is excluded
from every fitting set — it is the seven days standing between a validation row and a test row of
the same incident. So the row counts do not sum to the dataset.
"""

from __future__ import annotations

import datetime as dt
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import numpy as np
import pyarrow.parquet as pq
from numpy.typing import NDArray

from fraudshield_dataset.generator.config import SimulationConfig, SplitPlan

#: Bumped when the block's shape changes, so a consumer reading an older release can tell.
SPLIT_BLOCK_VERSION = 1

#: Machine key -> the label the realism report has always printed. Both are published: the key is
#: what a consumer selects on, the label is what the report's table says, and
#: `test_the_published_split_reproduces_the_realism_report` holds the two together.
SEGMENT_LABELS = {
    "train": "train",
    "validation": "validation",
    "calibration": "calibration (last part of validation)",
    "embargo": "embargo (excluded)",
    "test": "test",
}

_MICROS_PER_DAY = 86_400_000_000


class SplitUnavailableError(RuntimeError):
    """A dataset that does not publish its split, which is not something to work around."""


def _iso(micros: int) -> str:
    return (
        dt.datetime.fromtimestamp(micros / 1_000_000, tz=dt.UTC)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def planned_block(config: SimulationConfig) -> dict[str, Any]:
    """The boundaries, as the generator planned them. Written into `manifest.json` at generation.

    Both representations travel: microseconds because that is what the Parquet column holds and
    what a comparison must use, ISO-8601 because a human reading `release.json` should not have to
    convert epoch microseconds to decide whether a date is in the test period.
    """
    plan = config.split
    return {
        "version": SPLIT_BLOCK_VERSION,
        "design": "D-07",
        "timestamp_column": "transaction_timestamp",
        "embargo_days": config.parameters.integer("split.embargo_days"),
        "total_rows_target": config.total_rows,
        "boundaries_micros": {
            "validation_start": plan.validation_start,
            "calibration_start": plan.calibration_start,
            "embargo_start": plan.embargo_start,
            "test_start": plan.test_start,
            "end": plan.end,
        },
        "boundaries_utc": {
            name: _iso(value)
            for name, value in (
                ("validation_start", plan.validation_start),
                ("calibration_start", plan.calibration_start),
                ("embargo_start", plan.embargo_start),
                ("test_start", plan.test_start),
                ("end", plan.end),
            )
        },
        "rules": {
            "train": "transaction_timestamp < validation_start",
            "validation": "validation_start <= transaction_timestamp < embargo_start",
            "calibration": "calibration_start <= transaction_timestamp < embargo_start"
            " (the tail of validation, not a separate block)",
            "embargo": "embargo_start <= transaction_timestamp < test_start; excluded from every"
            " fitting set",
            "test": "transaction_timestamp >= test_start",
        },
    }


def plan_from(block: dict[str, Any]) -> SplitPlan:
    """The published boundaries, back as a plan. No arithmetic: it reads what was recorded."""
    micros = block["boundaries_micros"]
    return SplitPlan(
        validation_start=int(micros["validation_start"]),
        calibration_start=int(micros["calibration_start"]),
        embargo_start=int(micros["embargo_start"]),
        test_start=int(micros["test_start"]),
        end=int(micros["end"]),
    )


def read_block(root: Path) -> dict[str, Any]:
    """The split block of a generated dataset, or a refusal naming what has to happen.

    It refuses rather than reconstructing, because a dataset generated before PB-48 was drawn
    under boundaries this code can only guess at: the target row count the planner scaled by is
    not recorded anywhere else, and guessing it wrong moves every boundary by hours.
    """
    manifest = root / "manifest.json"
    if not manifest.exists():
        raise SplitUnavailableError(f"{root} has no manifest.json; generate the dataset first")
    block = json.loads(manifest.read_text(encoding="utf-8")).get("split")
    if block is None:
        raise SplitUnavailableError(
            f"{root} was generated before the split was published (PB-48) and its boundaries "
            "cannot be recovered from the rows; regenerate it with a current fs-dataset generate"
        )
    return dict(block)


def masks(timestamps: NDArray[np.int64], plan: SplitPlan) -> dict[str, NDArray[np.bool_]]:
    """The five segments, by machine key. The one definition of who belongs to which set."""
    return {
        "train": timestamps < plan.validation_start,
        "validation": (timestamps >= plan.validation_start) & (timestamps < plan.embargo_start),
        "calibration": (timestamps >= plan.calibration_start) & (timestamps < plan.embargo_start),
        "embargo": (timestamps >= plan.embargo_start) & (timestamps < plan.test_start),
        "test": timestamps >= plan.test_start,
    }


def segment_counts(
    timestamps: NDArray[np.int64],
    true: NDArray[np.bool_],
    observed: NDArray[np.bool_],
    plan: SplitPlan,
) -> dict[str, dict[str, float]]:
    """Rows, both fraud rates and the span, per segment. Measured, never planned."""
    out: dict[str, dict[str, float]] = {}
    for name, mask in masks(timestamps, plan).items():
        count = int(mask.sum())
        out[name] = {
            "rows": count,
            "true_fraud_rate": float(true[mask].mean()) if count else 0.0,
            "observed_fraud_rate": float(observed[mask].mean()) if count else 0.0,
            "span_days": round(
                float(timestamps[mask].max() - timestamps[mask].min()) / _MICROS_PER_DAY, 2
            )
            if count
            else 0.0,
        }
    return out


def _partitions(root: Path, table: str) -> Iterator[Path]:
    yield from sorted(
        (root / table).glob("month=*/*.parquet"), key=lambda p: (p.parent.name, p.name)
    )


def read_timestamps_and_labels(
    root: Path,
) -> tuple[NDArray[np.int64], NDArray[np.bool_], NDArray[np.bool_]]:
    """Three columns of the whole dataset, and nothing else.

    The realism checks load every column they need for every check; measuring a split needs a
    timestamp and two flags, so this reads those and pays a fraction of the memory. The two tables
    are written in the same order within a partition, which this asserts rather than assumes —
    a silent misalignment would put a row's timestamp against another row's label and the counts
    would still look plausible.
    """
    times: list[NDArray[np.int64]] = []
    true: list[NDArray[np.bool_]] = []
    observed: list[NDArray[np.bool_]] = []
    transactions = list(_partitions(root, "transactions"))
    labels = list(_partitions(root, "labels"))
    if not transactions:
        raise SplitUnavailableError(f"{root} holds no transactions/month=*/*.parquet partitions")
    if [p.parent.name for p in transactions] != [p.parent.name for p in labels]:
        raise SplitUnavailableError(
            f"{root} has {len(transactions)} transaction partitions and {len(labels)} label "
            "partitions; they must be one to one"
        )
    for tx_path, label_path in zip(transactions, labels, strict=True):
        tx = pq.read_table(tx_path, columns=["transaction_id", "transaction_timestamp"])
        label = pq.read_table(
            label_path, columns=["transaction_id", "is_fraud_true", "is_fraud_observed"]
        )
        if not tx.column("transaction_id").equals(label.column("transaction_id")):
            raise SplitUnavailableError(
                f"{tx_path.parent.name}: the label partition is not in transaction order, so "
                "positional alignment would mislabel rows"
            )
        times.append(tx.column("transaction_timestamp").cast("int64").to_numpy())
        true.append(label.column("is_fraud_true").to_numpy(zero_copy_only=False))
        observed.append(label.column("is_fraud_observed").to_numpy(zero_copy_only=False))
    return np.concatenate(times), np.concatenate(true), np.concatenate(observed)


def measured_block(root: Path) -> dict[str, Any]:
    """The published split block: the recorded boundaries plus the segments they actually cut.

    This is what `fs-dataset split` writes and what `release.json` carries, from one function, so
    a release and its sidecar cannot disagree.
    """
    block = read_block(root)
    timestamps, true, observed = read_timestamps_and_labels(root)
    counts = segment_counts(timestamps, true, observed, plan_from(block))
    return {
        **block,
        "rows_measured": int(timestamps.size),
        "segments": {key: {"label": SEGMENT_LABELS[key], **counts[key]} for key in SEGMENT_LABELS},
    }


def write(root: Path, output: Path) -> dict[str, Any]:
    block = measured_block(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(block, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return block
