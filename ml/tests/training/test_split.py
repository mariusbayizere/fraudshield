"""Reading D-07's published split (PB-49).

The property worth testing is not that four comparisons work. It is that **calibration overlaps
validation** and the reader says so: a `segment_of` returning one of five names would quietly
remove the calibration rows from validation, and a model would be tuned on a period it had also
calibrated on with nothing to show for it. Everything else here is refusal behaviour, because
every way this file can be wrong produces a split nobody chose rather than an error.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest

from fraudshield_ml.training.split import (
    FITTABLE,
    Boundaries,
    Segment,
    SplitUnavailableError,
    load,
)

pytestmark = pytest.mark.req("D-07")

START = datetime(2025, 1, 1, tzinfo=UTC)


def _micros(offset_days: float) -> int:
    return int((START + timedelta(days=offset_days)).timestamp() * 1_000_000)


def _boundaries() -> Boundaries:
    """Spaced like the real ones: a long train period, a short validation, a seven-day embargo."""
    return Boundaries(
        validation_start=_micros(600),
        calibration_start=_micros(632),
        embargo_start=_micros(658),
        test_start=_micros(665),
        end=_micros(729),
    )


def _at(offset_days: float) -> datetime:
    return START + timedelta(days=offset_days)


def test_each_period_claims_the_rows_inside_it_and_the_boundaries_are_half_open() -> None:
    """A boundary belongs to the period it opens, so no row is in two and none in neither."""
    b = _boundaries()
    assert b.segment_of(_at(599.9)) is Segment.TRAIN
    assert b.segment_of(_at(600)) is Segment.VALIDATION, "the boundary opens the period"
    assert b.segment_of(_at(657.9)) is Segment.VALIDATION
    assert b.segment_of(_at(658)) is Segment.EMBARGO
    assert b.segment_of(_at(664.9)) is Segment.EMBARGO
    assert b.segment_of(_at(665)) is Segment.TEST
    assert b.segment_of(_at(1000)) is Segment.TEST, "a row past the end is still test, not an error"


def test_calibration_overlaps_validation_rather_than_replacing_it() -> None:
    """The distinction this module exists to preserve.

    A calibration row is a validation row. If `segment_of` returned CALIBRATION for it, the
    validation set would silently lose its last 26 days and nothing downstream would say so.
    """
    b = _boundaries()
    inside = _at(640)
    assert b.segment_of(inside) is Segment.VALIDATION
    assert b.in_calibration(inside)

    earlier = _at(610)
    assert b.segment_of(earlier) is Segment.VALIDATION
    assert not b.in_calibration(earlier), "precondition: validation must extend before calibration"

    assert not b.in_calibration(_at(660)), "the embargo is not part of calibration"
    assert not b.in_calibration(_at(670)), "nor is the test period"


def test_the_embargo_is_not_fittable_and_neither_is_the_test_period() -> None:
    """The embargo exists to keep an incident straddling the boundary off both sides.

    Asserted against the exported set rather than inside a training loop, so a caller that fits on
    `FITTABLE` cannot reach the embargo by construction instead of by remembering.
    """
    assert Segment.EMBARGO not in FITTABLE
    assert Segment.TEST not in FITTABLE
    assert {Segment.TRAIN, Segment.VALIDATION} == FITTABLE


def test_a_published_block_round_trips(tmp_path: Path) -> None:
    """The format is `fs-dataset split`'s output, so the shape is a contract between packages."""
    b = _boundaries()
    path = tmp_path / "split.json"
    path.write_text(
        json.dumps(
            {
                "version": 1,
                "boundaries_micros": {
                    "validation_start": b.validation_start,
                    "calibration_start": b.calibration_start,
                    "embargo_start": b.embargo_start,
                    "test_start": b.test_start,
                    "end": b.end,
                },
                "segments": {},
            }
        ),
        encoding="utf-8",
    )
    assert load(path) == b
    assert "calibration from" in load(path).describe()


@pytest.mark.parametrize(
    ("name", "content", "message"),
    [
        ("missing", None, "does not exist"),
        ("broken.json", "{not json", "not valid JSON"),
        ("list.json", "[]", "does not hold a split block"),
        ("empty.json", "{}", "carries no boundaries_micros"),
        ("partial.json", '{"boundaries_micros": {"validation_start": 1}}', "malformed"),
    ],
)
def test_every_unusable_split_is_refused_rather_than_defaulted(
    tmp_path: Path, name: str, content: str | None, message: str
) -> None:
    """A default boundary would be a split nobody chose, producing a number that looks like every
    other number this project reports. Each failure names what to do instead.
    """
    path = tmp_path / name
    if content is not None:
        path.write_text(content, encoding="utf-8")
    with pytest.raises(SplitUnavailableError, match=message):
        load(path)


def test_boundaries_out_of_order_are_refused(tmp_path: Path) -> None:
    """Not a hypothetical: the order decides every row's segment, and a swapped pair would put the
    test period before the embargo without any row failing to be classified.
    """
    b = _boundaries()
    path = tmp_path / "split.json"
    path.write_text(
        json.dumps(
            {
                "boundaries_micros": {
                    "validation_start": b.embargo_start,
                    "calibration_start": b.calibration_start,
                    "embargo_start": b.validation_start,
                    "test_start": b.test_start,
                    "end": b.end,
                }
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(SplitUnavailableError, match="out of order"):
        load(path)
