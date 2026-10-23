"""D-07's temporal split, read from the published `split.json` (PB-49).

**Why this reads a file rather than importing the planner.** `fraudshield_ml` does not import
`fraudshield_dataset`: the feature pipeline consumes the published interchange format so that it
cannot read values a release does not carry, and would otherwise work here and fail on a release.
The boundaries are published exactly so this side does not have to reconstruct them — and
reconstructing them is not a small job, because they are planned from the *target* row count,
which the realised counts do not determine (PB-48).

**The segments are not a partition, and that is the thing to get right.** `calibration` is the
tail of `validation`, not a fifth disjoint block, and `embargo` belongs to no fitting set at all.
So a row has one *primary* segment — train, validation, embargo or test — and separately may or
may not fall in the calibration period. A `segment_of` that returned one of five names would make
the calibration rows vanish from validation, and a model would be tuned on a period it had also
calibrated on without anything saying so.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from pathlib import Path
from typing import Any


class Segment(Enum):
    """The four disjoint periods. Calibration is asked for separately, because it overlaps."""

    TRAIN = "train"
    VALIDATION = "validation"
    EMBARGO = "embargo"
    TEST = "test"


#: Segments a model may be fitted on. The embargo is excluded by construction — it exists to keep
#: an incident that straddles the boundary from having rows on both sides — and the test period is
#: excluded because it is the answer.
FITTABLE = {Segment.TRAIN, Segment.VALIDATION}


class SplitUnavailableError(RuntimeError):
    """The dataset does not publish its split, which is not something to work around."""


@dataclass(frozen=True)
class Boundaries:
    """The four D-07 boundaries in epoch microseconds, as published."""

    validation_start: int
    calibration_start: int
    embargo_start: int
    test_start: int
    end: int

    def segment_of(self, timestamp: datetime) -> Segment:
        """Which disjoint period a transaction falls in. Never raises: a row after `end` is test.

        Comparison is on microseconds rather than on datetimes, because the boundaries are
        published as integers and converting them back would introduce a rounding question where
        there is not one.
        """
        micros = int(timestamp.timestamp() * 1_000_000)
        if micros < self.validation_start:
            return Segment.TRAIN
        if micros < self.embargo_start:
            return Segment.VALIDATION
        if micros < self.test_start:
            return Segment.EMBARGO
        return Segment.TEST

    def in_calibration(self, timestamp: datetime) -> bool:
        """Whether a row is in the calibration period, which is the tail of validation."""
        micros = int(timestamp.timestamp() * 1_000_000)
        return self.calibration_start <= micros < self.embargo_start

    def describe(self) -> str:
        def when(micros: int) -> str:
            return datetime.fromtimestamp(micros / 1_000_000, tz=UTC).strftime("%Y-%m-%d %H:%M")

        return (
            f"train < {when(self.validation_start)} <= validation "
            f"(calibration from {when(self.calibration_start)}) "
            f"< {when(self.embargo_start)} <= embargo < {when(self.test_start)} <= test"
        )


def load(path: Path) -> Boundaries:
    """The boundaries a dataset published, or a refusal naming what has to happen.

    Every failure here is a refusal rather than a default. A default boundary would be a split
    nobody chose, computed on data nobody checked, producing a number that looks like every other
    number this project reports.
    """
    if not path.exists():
        raise SplitUnavailableError(
            f"{path} does not exist. Publish it with: fs-dataset split <dataset> --output {path}. "
            "A dataset generated before the split block existed cannot produce one and must be "
            "regenerated (PB-48)"
        )
    try:
        block: Any = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as broken:
        raise SplitUnavailableError(f"{path} is not valid JSON: {broken}") from broken
    if not isinstance(block, dict):
        raise SplitUnavailableError(f"{path} does not hold a split block")
    micros = block.get("boundaries_micros")
    if not isinstance(micros, dict):
        raise SplitUnavailableError(
            f"{path} carries no boundaries_micros; it is not a split published by fs-dataset"
        )
    try:
        boundaries = Boundaries(
            validation_start=int(micros["validation_start"]),
            calibration_start=int(micros["calibration_start"]),
            embargo_start=int(micros["embargo_start"]),
            test_start=int(micros["test_start"]),
            end=int(micros["end"]),
        )
    except (KeyError, TypeError, ValueError) as broken:
        raise SplitUnavailableError(f"{path}: boundaries_micros is malformed: {broken}") from broken
    ordered = [
        boundaries.validation_start,
        boundaries.calibration_start,
        boundaries.embargo_start,
        boundaries.test_start,
        boundaries.end,
    ]
    if ordered != sorted(ordered):
        raise SplitUnavailableError(
            f"{path}: the boundaries are out of order ({ordered}), so every row's segment would "
            "depend on which comparison ran first"
        )
    return boundaries
