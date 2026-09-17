from __future__ import annotations

import shutil
from collections.abc import Callable
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
import pytest

from fraudshield_dataset import cli
from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.fraud import NOVEL_VARIANT
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import load_parameters
from fraudshield_dataset.realism.checks import CheckResult, run_checks

pytestmark = pytest.mark.req("D-08")

SEED = 20260917
CLEAN_ROWS = 200_000
PLANT_ROWS = 40_000


def _results(root: Path, rows: int) -> dict[str, CheckResult]:
    config = build_config(load_parameters(), seed=SEED, total_rows=rows)
    results, _ = run_checks(root, config, full=False)
    return {r.name: r for r in results}


@pytest.fixture(scope="module")
def clean(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("clean")
    generate(build_config(load_parameters(), seed=SEED, total_rows=CLEAN_ROWS), output)
    return output


@pytest.fixture(scope="module")
def plant_base(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("plant-base")
    generate(build_config(load_parameters(), seed=SEED, total_rows=PLANT_ROWS), output)
    return output


def _plant(
    base: Path, target: Path, change: Callable[[pa.Table, pa.Table], tuple[pa.Table, pa.Table]]
) -> Path:
    """Copy the dataset and rewrite every month through ``change(transactions, labels)``."""
    shutil.copytree(base, target)
    for path in sorted((target / "transactions").glob("month=*/part-0000.parquet")):
        labels_path = target / "labels" / path.parent.name / "part-0000.parquet"
        transactions, labels = change(pq.read_table(path), pq.read_table(labels_path))
        pq.write_table(transactions, path)
        pq.write_table(labels, labels_path)
    return target


def _replace(table: pa.Table, name: str, values: pa.Array) -> pa.Table:
    return table.set_column(table.schema.get_field_index(name), table.schema.field(name), values)


@pytest.mark.req("D-08", "ML-DATA-04")
def test_a_generated_dataset_passes_every_gate_check(clean: Path) -> None:
    results = _results(clean, CLEAN_ROWS)
    failed = {name: r.value for name, r in results.items() if r.gate and not r.passed}
    assert failed == {}
    assert results["trivial rule baseline"].gate is False


def test_single_feature_leak_is_caught(plant_base: Path, tmp_path: Path) -> None:
    def inflate(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_observed"]
        scaled = pc.if_else(fraud, pc.multiply(t["amount_rwf"], 1000), t["amount_rwf"])
        return _replace(t, "amount_rwf", scaled.cast(t.schema.field("amount_rwf").type)), labels

    results = _results(_plant(plant_base, tmp_path / "leak", inflate), PLANT_ROWS)
    assert not results["single-feature AUC"].passed
    assert "amount_rwf" in results["single-feature AUC"].value


def test_identifier_ordering_leak_is_caught_by_the_shortcut_detector(
    plant_base: Path, tmp_path: Path
) -> None:
    def mark_ids(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_observed"].to_pylist()
        ids = [
            ("ff" + i[2:]) if f else i
            for i, f in zip(t["transaction_id"].to_pylist(), fraud, strict=True)
        ]
        return _replace(t, "transaction_id", pa.array(ids)), _replace(
            labels, "transaction_id", pa.array(ids)
        )

    results = _results(_plant(plant_base, tmp_path / "leak", mark_ids), PLANT_ROWS)
    assert not results["shortcut detector"].passed


def test_token_construction_leak_is_caught(plant_base: Path, tmp_path: Path) -> None:
    def mark_devices(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_observed"].to_pylist()
        devices = [
            ("tok_Z" + d[5:]) if (d is not None and f) else d
            for d, f in zip(t["device_fingerprint"].to_pylist(), fraud, strict=True)
        ]
        return _replace(t, "device_fingerprint", pa.array(devices, pa.string())), labels

    results = _results(_plant(plant_base, tmp_path / "leak", mark_devices), PLANT_ROWS)
    assert not results["identifier construction"].passed


def test_fraud_only_null_pattern_is_caught(plant_base: Path, tmp_path: Path) -> None:
    def agent_on_ussd(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_true"].to_pylist()
        channels = t["channel"].to_pylist()
        agents = [
            "tok_" + "A" * 32 if (f and c == "USSD") else a
            for a, f, c in zip(t["agent_id"].to_pylist(), fraud, channels, strict=True)
        ]
        return _replace(t, "agent_id", pa.array(agents, pa.string())), labels

    results = _results(_plant(plant_base, tmp_path / "leak", agent_on_ussd), PLANT_ROWS)
    assert not results["null signatures per channel"].passed


def test_novel_variant_outside_the_test_period_is_caught(plant_base: Path, tmp_path: Path) -> None:
    moved = {"done": False}

    def early_novelty(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        variants = labels["scenario_variant"].to_pylist()
        truth = labels["is_fraud_true"].to_pylist()
        if not moved["done"] and any(truth):
            first = truth.index(True)
            variants[first] = NOVEL_VARIANT
            moved["done"] = True
        return t, _replace(labels, "scenario_variant", pa.array(variants, pa.string()))

    results = _results(_plant(plant_base, tmp_path / "leak", early_novelty), PLANT_ROWS)
    assert not results["novel sub-variant placement"].passed


def test_cli_check_exit_codes(plant_base: Path, capsys: pytest.CaptureFixture[str]) -> None:
    arguments = ["check", str(plant_base), "--seed", str(SEED), "--rows", str(PLANT_ROWS)]
    assert cli.main(arguments) in (0, 1)
    output = capsys.readouterr().out
    assert "single-feature AUC" in output
    assert cli.main([*arguments, "--full"]) == 1  # 40K rows cannot meet the 5M size gate


def test_cli_report_writes_every_section(plant_base: Path, tmp_path: Path) -> None:
    output = tmp_path / "realism_report.md"
    arguments = ["report", str(plant_base), "--seed", str(SEED), "--rows", str(PLANT_ROWS)]
    assert cli.main([*arguments, "--output", str(output)]) in (0, 1)
    text = output.read_text(encoding="utf-8")
    for heading in (
        "## Run",
        "## Checks",
        "## Temporal split (D-07)",
        "## Distributions against targets",
        "## Monthly fraud rate against the calibrated schedule (ML-DATA-02)",
        "## Single-feature AUC",
        "## Shortcut and identifier checks",
        "## Labels",
        "## Novel sub-variant placement (D-08)",
        "## Fraud scenarios over time",
        "## Parameter provenance",
    ):
        assert heading in text, heading
    assert "FraudShield-EAC synthetic benchmark" in text


def test_duplicate_transaction_ids_are_caught(plant_base: Path, tmp_path: Path) -> None:
    def repeat_first_id(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        ids = t["transaction_id"].to_pylist()
        ids[1] = ids[0]  # the same payment listed twice
        return _replace(t, "transaction_id", pa.array(ids)), _replace(
            labels, "transaction_id", pa.array(ids)
        )

    results = _results(_plant(plant_base, tmp_path / "duplicate", repeat_first_id), PLANT_ROWS)
    assert not results["identifier uniqueness"].passed
    assert results["identifier uniqueness"].value.startswith("24 duplicate")
