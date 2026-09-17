from __future__ import annotations

import ast
import hashlib
import json
from pathlib import Path

import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.dataset as ds
import pytest

from fraudshield_dataset import cli
from fraudshield_dataset.generator import config as config_module
from fraudshield_dataset.generator.config import (
    CHANNELS,
    build_config,
    month_labels,
    segment_channel_shares,
)
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.generator.schema import ACCOUNT_EVENTS, LABELS, TRANSACTIONS
from fraudshield_dataset.params import ParameterError, ParameterSet, load_parameters

GENERATOR_SOURCES = Path(config_module.__file__).parent


def _files(root: Path) -> dict[str, str]:
    return {
        str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*.parquet"))
    }


@pytest.fixture(scope="module")
def small(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("small")
    generate(build_config(load_parameters(), seed=11, total_rows=6000), output, chunk_size=8)
    return output


@pytest.mark.req("D-08", "ML-DATA-01")
def test_same_seed_is_byte_identical_across_chunk_sizes(small: Path, tmp_path: Path) -> None:
    for chunk_size in (1, 64):
        other = tmp_path / f"chunk-{chunk_size}"
        generate(
            build_config(load_parameters(), seed=11, total_rows=6000), other, chunk_size=chunk_size
        )
        assert _files(other) == _files(small)
        assert (other / "manifest.json").read_bytes() == (small / "manifest.json").read_bytes()


@pytest.mark.req("D-08")
def test_a_different_seed_gives_different_data(small: Path, tmp_path: Path) -> None:
    generate(build_config(load_parameters(), seed=12, total_rows=6000), tmp_path, chunk_size=8)
    assert _files(tmp_path) != _files(small)


@pytest.mark.req("ML-DATA-06", "RES-01")
def test_output_layout_schema_and_manifest(small: Path) -> None:
    manifest = json.loads((small / "manifest.json").read_text())
    run = json.loads((small / "run.json").read_text())
    assert len(manifest["rows_by_month"]) == 24
    assert manifest["rows"] == sum(manifest["rows_by_month"].values())
    assert sum(manifest["rows_by_day"].values()) == manifest["rows"]
    assert set(manifest["sha256"]) == set(_files(small))
    assert all(manifest["sha256"][k] == v for k, v in _files(small).items())
    assert 0 < run["peak_rss_bytes"] < 2 * 2**30
    for table, schema in (
        ("transactions", TRANSACTIONS),
        ("labels", LABELS),
        ("account_events", ACCOUNT_EVENTS),
    ):
        dataset = ds.dataset(small / table, format="parquet", partitioning="hive")
        assert dataset.schema.remove(dataset.schema.get_field_index("month")) == schema


@pytest.mark.req("D-08", "FR-01-02")
def test_rows_follow_the_ingestion_contract_formats(small: Path) -> None:
    table = ds.dataset(small / "transactions", format="parquet", partitioning="hive").to_table()
    assert pc.all(
        pc.match_substring_regex(table["account_id"], r"^tok_[A-Za-z0-9]{24,64}$")
    ).as_py()
    assert pc.all(
        pc.match_substring_regex(table["counterparty_id"], r"^tok_[A-Za-z0-9]{24,64}$")
    ).as_py()
    assert pc.all(pc.match_substring_regex(table["merchant_category_code"], r"^[0-9]{4}$")).as_py()
    assert pc.all(pc.greater(table["amount"], pa.scalar(0, pa.decimal128(18, 4)))).as_py()
    assert set(pc.unique(table["channel"]).to_pylist()) == set(CHANNELS)
    ussd = table.filter(pc.equal(table["channel"], "USSD"))
    assert ussd["device_fingerprint"].null_count == ussd.num_rows
    agent = table.filter(pc.equal(table["channel"], "AGENT_BANKING"))
    assert agent["agent_id"].null_count == 0
    other = table.filter(pc.not_equal(table["channel"], "AGENT_BANKING"))
    assert other["agent_id"].null_count == other.num_rows
    assert len(set(table["transaction_id"].to_pylist())) == table.num_rows


@pytest.mark.req("ML-DATA-03", "ML-DATA-05", "ML-DATA-01")
def test_small_run_is_close_to_the_calibrated_targets(small: Path) -> None:
    parameters = load_parameters()
    table = ds.dataset(small / "transactions", format="parquet", partitioning="hive").to_table(
        columns=["channel"]
    )
    rows = table.num_rows
    # About a dozen customers a month at this size: volume and mixes are loose; the ±0.5 pp gate
    # is measured on the full dataset.
    assert abs(rows - 6000) / 6000 < 0.3
    shares = {
        d["values"]: d["counts"] / rows for d in pc.value_counts(table["channel"]).to_pylist()
    }
    for channel, target in parameters.mapping("channels.channel_share").items():
        assert abs(shares[channel] - target) < 0.06, channel


def test_channel_shares_by_segment_reproduce_the_srs_mix() -> None:
    parameters = load_parameters()
    shares = segment_channel_shares(parameters)
    segments = parameters.mapping("population.segment_share")
    target = parameters.mapping("channels.channel_share")
    for channel in CHANNELS:
        mixture = sum(segments[s] * shares[s][channel] for s in segments)
        assert mixture == pytest.approx(target[channel])


def test_inconsistent_rural_shares_are_rejected() -> None:
    parameters = load_parameters()
    changed = dict(parameters.parameters)
    rural = parameters.get("behaviour.rural_ussd_channel_share")
    changed[rural.key] = type(rural)(
        **{**rural.__dict__, "value": {"USSD": 0.1, "AGENT_BANKING": 0.1, "MOBILE_MONEY": 0.8}}
    )
    segment = parameters.get("population.segment_share")
    changed[segment.key] = type(segment)(
        **{
            **segment.__dict__,
            "value": {
                "urban_salaried": 0.1,
                "informal_trader": 0.1,
                "rural_ussd": 0.7,
                "student": 0.1,
            },
        }
    )
    with pytest.raises(ParameterError, match="rural USSD channel shares"):
        segment_channel_shares(ParameterSet(changed, parameters.descriptions))


def test_month_labels_cross_year_boundaries() -> None:
    assert month_labels("2024-11", 3) == ("2024-11", "2024-12", "2025-01")


def test_chunk_size_must_be_positive(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="chunk_size"):
        generate(build_config(load_parameters(), seed=1, total_rows=100), tmp_path, chunk_size=0)


def test_cli_generate(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert cli.main(["generate", "--output", str(tmp_path), "--rows", "300", "--seed", "3"]) == 0
    assert "generated" in capsys.readouterr().out


# Behavioural numbers must come from the parameter files (owner direction 2026-09-17). Every
# literal allowed here is structural, grouped by why it is not a modelling choice.
_STRUCTURAL_NUMBERS = {
    # counting, indexing and small structural floors (pool minimums for tiny development runs)
    -1, 0, 1, 2, 3, 4, 5, 8, 10, 12, 32, 62,
    # calendar and clock units
    7, 24, 28, 60, 1970, 3600, 86_399, 86_400, 1_000_000, 3_600_000_000, 86_400_000_000,
    # value ranges and precision of the contract and schema: coordinates, DECIMAL(18,4), UUID bytes
    6, 16, 18, 90, 180,
    # dataset definition (ADR 0022): shard count; Parquet row-group size; numeric tolerance
    64, 65_536, 1024, 1e-12,
}  # fmt: skip


def test_generator_code_has_no_unexplained_numeric_literals() -> None:
    found = []
    for path in sorted(GENERATOR_SOURCES.glob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if (
                isinstance(node, ast.Constant)
                and isinstance(node.value, int | float)
                and not isinstance(node.value, bool)
                and node.value not in _STRUCTURAL_NUMBERS
            ):
                found.append(f"{path.name}:{node.lineno}: {node.value}")
    assert found == []
