"""The sweep harness's own logic, which decides what gets measured and what is resumed."""

from __future__ import annotations

import json
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from fraudshield_dataset import shortcut_diagnosis as diag

pytestmark = pytest.mark.req("D-08")


def test_dense_seeds_only_where_a_rate_has_to_be_quoted() -> None:
    """Fifteen seeds where false alarms occur, five where only a trend is needed.

    One failure in five spans roughly 5% to 45%, which is too wide to act on, and the small-n end
    is where the detector's false-alarm rate actually has to be stated.
    """
    assert len(diag.seeds_for(10_000)) == 15
    assert len(diag.seeds_for(30_000)) == 15
    assert len(diag.seeds_for(200_000)) == 5
    # The stability question needs its own ten at one size above the scenario minimum.
    assert len(diag.seeds_for(diag.STABILITY_ROWS)) >= 10
    assert len(set(diag.seeds_for(diag.STABILITY_ROWS))) == len(diag.seeds_for(diag.STABILITY_ROWS))


def test_the_negative_control_runs_only_where_false_alarms_happen() -> None:
    """0.5 carries no information; it earns its cost at small n and not at release scale."""
    assert 0.5 in diag.strengths_for(10_000)
    assert 0.5 in diag.strengths_for(diag.CONTROL_MAX_ROWS)
    assert 0.5 not in diag.strengths_for(300_000)
    # The liveness and subtle plants run at every scale.
    for rows in (10_000, 1_000_000):
        assert 1.0 in diag.strengths_for(rows)
        assert 0.6 in diag.strengths_for(rows)


def test_a_resumed_sweep_skips_what_it_has_already_measured(tmp_path: Path) -> None:
    """Two reapings and a lost scratch directory: resuming has to be exact, not approximate."""
    out = tmp_path / "records.jsonl"
    out.write_text(
        "\n".join(
            json.dumps(r)
            for r in (
                {"rows": 10_000, "seed": 1, "variant": "clean", "strength": None},
                {"rows": 10_000, "seed": 1, "variant": "leaky", "strength": 1.0},
                {"rows": 30_000, "seed": 2, "variant": "refused", "strength": None},
            )
        )
        + "\n",
        encoding="utf-8",
    )

    done = diag.done_pairs(out)

    assert (10_000, 1) in done
    # A refusal is deterministic for its pair, so it settles it rather than being retried forever.
    assert (30_000, 2) in done
    assert (60_000, 1) not in done


def test_a_line_truncated_by_a_kill_does_not_stop_the_resume(tmp_path: Path) -> None:
    """A sweep killed mid-write leaves a partial line; it must not poison the whole file."""
    out = tmp_path / "records.jsonl"
    good = json.dumps({"rows": 10_000, "seed": 1, "variant": "clean", "strength": None})
    out.write_text(f"{good}\n{{'rows': 30000, 'seed'\n", encoding="utf-8")

    assert diag.done_pairs(out) == {(10_000, 1)}


def test_done_pairs_of_a_sweep_that_never_ran_is_empty(tmp_path: Path) -> None:
    assert diag.done_pairs(tmp_path / "absent.jsonl") == set()


def _dataset(root: Path, labels: list[bool]) -> Path:
    """The two tables and the one column the plant touches, at the paths the harness expects."""
    ids = [f"{index:032x}" for index in range(len(labels))]
    (root / "transactions" / "month=2024-01").mkdir(parents=True)
    (root / "labels" / "month=2024-01").mkdir(parents=True)
    pq.write_table(
        pa.table({"transaction_id": ids}),
        root / "transactions" / "month=2024-01" / "part-0000.parquet",
    )
    pq.write_table(
        pa.table({"is_fraud_observed": labels}),
        root / "labels" / "month=2024-01" / "part-0000.parquet",
    )
    return root


def _planted_prefixes(root: Path) -> list[str]:
    table = pq.read_table(root / "transactions" / "month=2024-01" / "part-0000.parquet")
    return [value[:2] for value in table["transaction_id"].to_pylist()]


def test_a_full_strength_plant_marks_every_row_by_its_label(tmp_path: Path) -> None:
    labels = [True, False] * 50
    source = _dataset(tmp_path / "clean", labels)

    diag.plant_id_prefix_leak(source, tmp_path / "leaky", strength=1.0, seed=1)

    prefixes = _planted_prefixes(tmp_path / "leaky")
    assert all((prefix == "ff") == label for prefix, label in zip(prefixes, labels, strict=True))


def test_the_negative_control_plants_no_information(tmp_path: Path) -> None:
    """At 0.5 the marker must agree with the label about half the time, which is no signal at all.

    Without this the sweep cannot tell "the gate caught my leak" from "the gate fires whenever the
    data is touched", and the whole power curve would be uninterpretable.
    """
    labels = [index % 2 == 0 for index in range(400)]
    source = _dataset(tmp_path / "clean", labels)

    diag.plant_id_prefix_leak(source, tmp_path / "control", strength=0.5, seed=7)

    prefixes = _planted_prefixes(tmp_path / "control")
    agreeing = sum(
        (prefix == "ff") == label for prefix, label in zip(prefixes, labels, strict=True)
    )
    assert 0.4 < agreeing / len(labels) < 0.6


def test_the_plant_leaves_the_rest_of_the_identifier_alone(tmp_path: Path) -> None:
    """Only the first byte carries the plant; a wider change would confound the detector."""
    labels = [True, False, True, False]
    source = _dataset(tmp_path / "clean", labels)
    before = pq.read_table(source / "transactions" / "month=2024-01" / "part-0000.parquet")[
        "transaction_id"
    ].to_pylist()

    diag.plant_id_prefix_leak(source, tmp_path / "leaky", strength=1.0, seed=3)

    after = pq.read_table(
        tmp_path / "leaky" / "transactions" / "month=2024-01" / "part-0000.parquet"
    )["transaction_id"].to_pylist()
    assert [value[2:] for value in after] == [value[2:] for value in before]
