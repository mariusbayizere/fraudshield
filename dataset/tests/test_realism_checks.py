from __future__ import annotations

import json
import shutil
from collections.abc import Callable
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.dataset as ds
import pyarrow.parquet as pq
import pytest

from fraudshield_dataset import cli
from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.fraud import NOVEL_VARIANT
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import load_parameters
from fraudshield_dataset.paths import REALISM_REPORT_MD
from fraudshield_dataset.realism.checks import (
    CheckResult,
    check_digest,
    parameter_digest,
    run_checks,
)

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


def test_quantised_account_events_are_caught(plant_base: Path, tmp_path: Path) -> None:
    """The BLOCKER the M2 principal review found: only planted events had sub-second times."""
    target = tmp_path / "quantised"
    shutil.copytree(plant_base, target)
    transactions = ds.dataset(
        target / "transactions", format="parquet", partitioning="hive"
    ).to_table(columns=["account_id"])
    labels = ds.dataset(target / "labels", format="parquet", partitioning="hive").to_table(
        columns=["is_fraud_observed"]
    )
    fraud_accounts = {
        account
        for account, flag in zip(
            transactions["account_id"].to_pylist(),
            labels["is_fraud_observed"].to_pylist(),
            strict=True,
        )
        if flag
    }
    for path in sorted((target / "account_events").glob("month=*/part-0000.parquet")):
        events = pq.read_table(path)
        micros = events["event_timestamp"].cast(pa.int64()).to_numpy().copy()
        for index, account in enumerate(events["account_id"].to_pylist()):
            if account not in fraud_accounts:  # what the generator used to do to every event
                micros[index] -= micros[index] % 1_000_000
        stamped = pa.array(micros, pa.int64()).cast(pa.timestamp("us", tz="UTC"))
        pq.write_table(
            events.set_column(
                events.schema.get_field_index("event_timestamp"),
                events.schema.field("event_timestamp"),
                stamped,
            ),
            path,
        )

    results = _results(target, PLANT_ROWS)
    assert not results["event construction"].passed


def test_a_marker_in_any_transaction_id_byte_is_caught(plant_base: Path, tmp_path: Path) -> None:
    """Only two of sixteen bytes were read, so a marker in byte 5 passed every check."""

    def mark_byte_five(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_observed"].to_pylist()
        ids = [
            (i[:10] + "ff" + i[12:]) if f else i
            for i, f in zip(t["transaction_id"].to_pylist(), fraud, strict=True)
        ]
        return _replace(t, "transaction_id", pa.array(ids)), _replace(
            labels, "transaction_id", pa.array(ids)
        )

    results = _results(_plant(plant_base, tmp_path / "byte5", mark_byte_five), PLANT_ROWS)
    assert not results["shortcut detector"].passed


def test_a_marker_in_any_token_character_is_caught(plant_base: Path, tmp_path: Path) -> None:
    """Three of thirty-two token characters were read; a marker in the middle passed."""

    def mark_middle(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_observed"].to_pylist()
        victims = {a for a, f in zip(t["account_id"].to_pylist(), fraud, strict=True) if f}
        accounts = [
            (a[:14] + "Z" + a[15:]) if a in victims else a for a in t["account_id"].to_pylist()
        ]
        return _replace(t, "account_id", pa.array(accounts)), labels

    results = _results(_plant(plant_base, tmp_path / "token-middle", mark_middle), PLANT_ROWS)
    assert not results["identifier construction"].passed


def test_a_label_available_before_its_transaction_is_caught(
    plant_base: Path, tmp_path: Path
) -> None:
    """When a label arrives tells a model nothing about the label; nothing checked that."""

    def fast_labels(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        fraud = labels["is_fraud_observed"].to_pylist()
        stamps = t["transaction_timestamp"].cast(pa.int64()).to_numpy()
        available = [
            int(s) + (1 if f else 86_400_000_000) for s, f in zip(stamps, fraud, strict=True)
        ]
        return t, _replace(
            labels,
            "label_available_at",
            pa.array(available, pa.int64()).cast(pa.timestamp("us", tz="UTC")),
        )

    results = _results(_plant(plant_base, tmp_path / "fast-labels", fast_labels), PLANT_ROWS)
    assert not results["shortcut detector"].passed


def test_a_malformed_value_is_caught(plant_base: Path, tmp_path: Path) -> None:
    """The value-format gate had no test that it reacts."""

    def break_mcc(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        codes = t["merchant_category_code"].to_pylist()
        codes[0] = "ABCD"
        return _replace(t, "merchant_category_code", pa.array(codes, pa.string())), labels

    results = _results(_plant(plant_base, tmp_path / "bad-mcc", break_mcc), PLANT_ROWS)
    assert not results["value formats"].passed


def test_fraud_concentrated_in_late_months_is_caught_by_file_order(
    plant_base: Path, tmp_path: Path
) -> None:
    """The file-order gate had no test that it reacts."""
    state = {"file": 0}

    def drop_early_fraud(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        state["file"] += 1
        if state["file"] > 12:
            return t, labels
        observed = [False] * labels.num_rows  # no fraud at all in the first half of the year
        return t, _replace(labels, "is_fraud_observed", pa.array(observed, pa.bool_()))

    results = _results(_plant(plant_base, tmp_path / "late-fraud", drop_early_fraud), PLANT_ROWS)
    assert not results["file order"].passed


@pytest.mark.req("ML-DATA-08")
def test_the_committed_report_describes_the_current_parameters() -> None:
    """The report shipped with a release must not describe a superseded parameter set.

    It did, for two commits: its footer said no parameter was sourced while twelve were
    (M2 principal review, MAJOR 4.1). The digest makes that a failure here instead.
    """
    digest = parameter_digest(load_parameters())
    text = REALISM_REPORT_MD.read_text(encoding="utf-8")

    assert digest in text, (
        "dataset/realism_report.md was generated from different parameter values; regenerate it "
        "with: uv run fs-dataset report <dataset> --rows <rows>"
    )


@pytest.mark.req("ML-DATA-08")
def test_the_committed_report_describes_the_current_check_set(clean: Path) -> None:
    """A report that omits a check the code performs must fail, not pass quietly.

    The parameter digest above closed one door and left another open: a report generated before a
    check existed still matched the parameters and passed, while describing a run that never
    performed that check. Adding the two reported event channels produced exactly that state — no
    parameter moved, so nothing failed (M2 delta re-check).
    """
    config = build_config(load_parameters(), seed=SEED, total_rows=CLEAN_ROWS)
    results, _ = run_checks(clean, config, full=False)
    text = REALISM_REPORT_MD.read_text(encoding="utf-8")

    assert check_digest(results) in text, (
        "dataset/realism_report.md describes a different set of checks than the code performs; "
        "regenerate it with: uv run fs-dataset report <dataset> --rows <rows>"
    )


def test_the_check_set_guard_fails_when_a_check_is_added() -> None:
    """The guard has to react to a new check, or it is the old guard with extra steps.

    Adding a check is the case that slipped through: it changes no parameter, so the parameter
    digest is identical and only a digest over the check set can notice.
    """
    before = [
        CheckResult("single-feature AUC", True, True, "max 0.711", "every feature <= 0.8 (D-08)"),
        CheckResult("shortcut detector", True, True, "AUC 0.510", "within its null band"),
    ]
    added = CheckResult("event delay (reported)", True, False, "AUC 0.713", "reported, not gated")

    assert check_digest([*before, added]) != check_digest(before)
    # Order must not matter: the same set rendered in a different order is the same check set.
    assert check_digest(before[::-1]) == check_digest(before)
    # Semantics count, not just names: rewording what a check requires invalidates a stale report.
    reworded = [CheckResult(before[0].name, True, True, before[0].value, "a different rule")]
    assert check_digest(reworded) != check_digest([before[0]])
    # A measured value legitimately differs run to run and must NOT invalidate the report.
    revalued = [CheckResult(before[0].name, True, True, "max 0.727", before[0].requirement)]
    assert check_digest(revalued) == check_digest([before[0]])


def _full_results(root: Path, rows: int) -> dict[str, CheckResult]:
    config = build_config(load_parameters(), seed=SEED, total_rows=rows)
    results, _ = run_checks(root, config, full=True)
    return {r.name: r for r in results}


@pytest.mark.req("ML-DATA-01", "ML-DATA-02", "ML-DATA-03", "ML-DATA-05")
def test_the_release_gates_react_when_their_property_is_broken(
    plant_base: Path, tmp_path: Path
) -> None:
    """The --full gates had no negative test, so nothing showed they could fail (MAJOR 4.3).

    The size gate is exercised by the dataset being small; the others are broken deliberately.
    """
    assert not _full_results(plant_base, PLANT_ROWS)["size"].passed

    def no_label_noise(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        return t, _replace(labels, "is_fraud_observed", labels["is_fraud_true"])

    def no_fraud(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        clean = pa.array([False] * labels.num_rows, pa.bool_())
        return t, _replace(_replace(labels, "is_fraud_true", clean), "is_fraud_observed", clean)

    def one_channel(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        single = pa.array(["MOBILE_MONEY"] * t.num_rows, pa.string())
        return _replace(t, "channel", single), labels

    def one_country(t: pa.Table, labels: pa.Table) -> tuple[pa.Table, pa.Table]:
        single = pa.array(["RWF"] * t.num_rows, pa.string())
        return _replace(t, "currency", single), labels

    for name, change, check in (
        ("noiseless", no_label_noise, "label noise"),
        ("fraudless", no_fraud, "fraud rate"),
        ("one-channel", one_channel, "channel mix"),
        ("one-country", one_country, "country mix"),
    ):
        results = _full_results(_plant(plant_base, tmp_path / name, change), PLANT_ROWS)
        assert not results[check].passed, check


@pytest.mark.req("ML-DATA-04")
def test_a_memory_overrun_and_a_missing_scenario_are_caught(
    plant_base: Path, tmp_path: Path
) -> None:
    """The last two gates without a negative test (MAJOR 4.3).

    The scenario count is a release gate: a small run legitimately lacks scenarios, so it is
    checked in full mode, where this dataset is too small to hold all eight.
    """
    assert not _full_results(plant_base, PLANT_ROWS)["fraud scenarios"].passed

    hungry = tmp_path / "hungry"
    shutil.copytree(plant_base, hungry)
    run = json.loads((hungry / "run.json").read_text(encoding="utf-8"))
    run["peak_rss_bytes"] = 3 * 2**30  # over the 2 GiB budget
    (hungry / "run.json").write_text(json.dumps(run, indent=2, sort_keys=True) + "\n")

    assert not _results(hungry, PLANT_ROWS)["generator peak memory"].passed
