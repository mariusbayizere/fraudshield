"""The published temporal split (D-07, PB-48).

The point of publishing the split is that a consumer's evaluation is computed on the same rows the
gates were, so the test that matters is the one comparing the two paths a reader can take: the
sidecar `split.json` a consumer downloads, and the table the realism report prints. They come from
one definition, and if they ever stop agreeing this fails.

The rest defend the two ways the block could be wrong while still looking right: the boundaries
could be reconstructed rather than recorded, landing close but not equal; and the label partitions
could be read out of order, producing counts that are plausible and mislabelled.
"""

from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from typing import Any

import pyarrow.parquet as pq
import pytest

from fraudshield_dataset import cli
from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import load_parameters
from fraudshield_dataset.realism.checks import run_checks
from fraudshield_dataset.realism.report import render as render_report
from fraudshield_dataset.release.export import export
from fraudshield_dataset.release.split import (
    SEGMENT_LABELS,
    SplitUnavailableError,
    measured_block,
    read_timestamps_and_labels,
)

pytestmark = pytest.mark.req("D-07")

SEED = 20260917
ROWS = 60_000


@pytest.fixture(scope="module")
def dataset(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("split-dataset")
    generate(
        build_config(load_parameters(), seed=SEED, total_rows=ROWS),
        output,
        allow_missing_scenarios=True,
    )
    return output


@pytest.fixture(scope="module")
def block(dataset: Path) -> dict[str, Any]:
    return measured_block(dataset)


def _report_table(dataset: Path) -> dict[str, tuple[int, float]]:
    """The split table the realism report prints, parsed back out of the rendered markdown.

    Parsed from the text rather than taken from `measures`, so the comparison happens at the point
    a reader actually reads: two numbers that agree in a dict and disagree on the page would still
    mislead whoever quotes the report.
    """
    config = build_config(load_parameters(), seed=SEED, total_rows=ROWS)
    results, measures = run_checks(dataset, config, full=False)
    text = render_report(results, measures, config, False)
    rows = {}
    for line in text.splitlines():
        match = re.match(r"\|\s*([^|]+?)\s*\|\s*([\d,]+)\s*\|\s*([\d.]+)%\s*\|", line)
        if match and match.group(1) in SEGMENT_LABELS.values():
            rows[match.group(1)] = (int(match.group(2).replace(",", "")), float(match.group(3)))
    return rows


@pytest.mark.req("D-07", "ML-DATA-08")
def test_the_published_split_reproduces_the_realism_report(
    dataset: Path, block: dict[str, Any]
) -> None:
    """PB-48's acceptance: the sidecar and the report state the same segments.

    Preconditions first, because a split whose segments were empty or whose fraud rates were all
    zero would let any two implementations agree. Every segment must hold rows, and the test
    period must hold fraud — the quantity every M4 metric is computed over.
    """
    segments = block["segments"]
    assert isinstance(segments, dict)
    assert all(segments[key]["rows"] > 0 for key in SEGMENT_LABELS), (
        "precondition: every segment must hold rows, or agreement is vacuous"
    )
    assert segments["test"]["true_fraud_rate"] > 0, (
        "precondition: the test period must hold fraud, or the rates agree at zero"
    )

    printed = _report_table(dataset)
    assert set(printed) == set(SEGMENT_LABELS.values()), (
        "the report printed a different segment set"
    )
    for key, label in SEGMENT_LABELS.items():
        rows, rate = printed[label]
        assert segments[key]["rows"] == rows
        assert segments[key]["true_fraud_rate"] * 100 == pytest.approx(rate, abs=0.0005)


@pytest.mark.req("D-07", "ML-DATA-08")
def test_the_boundaries_are_the_planner_s_own_and_not_recomputed(dataset: Path) -> None:
    """The recorded block must equal the plan the generator ran under, to the microsecond.

    Equality is the assertion, not closeness: a boundary reconstructed from the realised row count
    instead of the target lands within hours of the right answer, which is exactly the failure
    that would never be noticed.
    """
    manifest = json.loads((dataset / "manifest.json").read_text(encoding="utf-8"))
    planned = build_config(load_parameters(), seed=SEED, total_rows=ROWS).split
    recorded = manifest["split"]["boundaries_micros"]
    assert recorded["validation_start"] == planned.validation_start
    assert recorded["calibration_start"] == planned.calibration_start
    assert recorded["embargo_start"] == planned.embargo_start
    assert recorded["test_start"] == planned.test_start
    assert recorded["end"] == planned.end
    # The realised rows differ from the target, so a block derived from them would differ here.
    assert manifest["rows"] != ROWS, "precondition: realised rows must differ from the target"


@pytest.mark.req("D-07", "ML-DATA-08")
def test_the_boundaries_are_ordered_and_the_embargo_is_the_stated_width(
    block: dict[str, Any],
) -> None:
    """The embargo is not decoration: it is the days standing between a validation row and a test
    row of the same incident, and its width is a parameter a consumer must be able to check.
    """
    micros = block["boundaries_micros"]
    assert isinstance(micros, dict)
    order = ["validation_start", "calibration_start", "embargo_start", "test_start", "end"]
    values = [micros[name] for name in order]
    assert values == sorted(values), f"the boundaries are out of order: {micros}"
    days = (micros["test_start"] - micros["embargo_start"]) / 86_400_000_000
    assert days == pytest.approx(float(block["embargo_days"]))


@pytest.mark.req("D-07", "ML-DATA-08")
def test_calibration_sits_inside_validation_and_the_embargo_sits_outside_both(
    block: dict[str, Any],
) -> None:
    """The segments overlap on purpose, and a consumer summing them would be wrong.

    Asserted here so the published shape is the documented one: calibration is the tail of
    validation, so the five counts exceed the dataset rather than partitioning it.
    """
    segments = block["segments"]
    assert isinstance(segments, dict)
    assert segments["calibration"]["rows"] < segments["validation"]["rows"]
    total = sum(int(segments[key]["rows"]) for key in SEGMENT_LABELS)
    assert total > int(block["rows_measured"])


@pytest.mark.req("D-07", "ML-DATA-08")
def test_a_dataset_generated_before_the_block_refuses_rather_than_reconstructing(
    dataset: Path, tmp_path: Path
) -> None:
    """The mutation: strip the block, as every dataset generated before PB-48 has it stripped.

    A reconstruction would be the wrong answer delivered confidently; the refusal names what has
    to happen instead.
    """
    older = tmp_path / "older"
    shutil.copytree(dataset, older)
    manifest = json.loads((older / "manifest.json").read_text(encoding="utf-8"))
    del manifest["split"]
    (older / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    with pytest.raises(SplitUnavailableError, match="regenerate"):
        measured_block(older)


@pytest.mark.req("D-07", "ML-DATA-08")
def test_label_partitions_out_of_transaction_order_are_refused(
    dataset: Path, tmp_path: Path
) -> None:
    """The mutation that motivates the identifier check: reverse one label partition.

    Positional alignment is what makes reading three columns cheap, and reversing a month leaves
    every count in range while attaching each label to the wrong row. Without the check this
    passes silently, which is why it is a check and not a comment.
    """
    shuffled = tmp_path / "shuffled"
    shutil.copytree(dataset, shuffled)
    path = next(iter(sorted((shuffled / "labels").glob("month=*/*.parquet"))))
    table = pq.read_table(path)
    pq.write_table(table.take(list(reversed(range(table.num_rows)))), path)
    with pytest.raises(SplitUnavailableError, match="transaction order"):
        read_timestamps_and_labels(shuffled)


@pytest.mark.req("D-07", "ML-DATA-08")
def test_the_release_carries_the_split_so_it_is_self_describing(
    dataset: Path, tmp_path: Path, block: dict[str, Any]
) -> None:
    """A consumer holding only the release must be able to say which rows are training rows."""
    release = tmp_path / "release"
    export(dataset, release, csv_tables=False, report=tmp_path / "absent.md")
    published = json.loads((release / "release.json").read_text(encoding="utf-8"))["split"]
    assert published == json.loads(json.dumps(block))


@pytest.mark.req("D-07", "ML-DATA-08")
def test_cli_split_writes_the_sidecar(dataset: Path, tmp_path: Path) -> None:
    output = tmp_path / "split.json"
    assert cli.main(["split", str(dataset), "--output", str(output)]) == 0
    written = json.loads(output.read_text(encoding="utf-8"))
    assert set(written["segments"]) == set(SEGMENT_LABELS)
    assert written["boundaries_utc"]["test_start"].endswith("Z")


@pytest.mark.req("D-07", "ML-DATA-08")
def test_cli_split_refuses_a_directory_that_is_not_a_dataset(tmp_path: Path) -> None:
    assert cli.main(["split", str(tmp_path), "--output", str(tmp_path / "split.json")]) == 2


@pytest.mark.req("D-07", "ML-DATA-08")
def test_export_refuses_a_dataset_that_cannot_carry_its_split(
    dataset: Path, tmp_path: Path
) -> None:
    """A release without its split is not self-describing, so it is not written.

    The mutation is the same one: strip the block, as every pre-PB-48 dataset has it stripped. The
    refusal must be a message and an exit code — a traceback names no action.
    """
    older = tmp_path / "older"
    shutil.copytree(dataset, older)
    manifest = json.loads((older / "manifest.json").read_text(encoding="utf-8"))
    del manifest["split"]
    (older / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    output = tmp_path / "release"
    # A report that does not exist, so PB-41's fingerprint refusal cannot fire first and mask
    # this one. The suite caught exactly that: the first version of this test passed a committed
    # report describing another dataset and was refused two checks earlier than it claimed.
    absent = tmp_path / "no-such-report.md"
    command = ["export", str(older), "--output", str(output), "--report", str(absent)]
    assert cli.main(command) == 2
    assert not (output / "release.json").exists()
    assert not (output / "parquet").exists(), (
        "the refusal must come before the copying, or it leaves a directory that looks like a "
        "release and is not one"
    )
