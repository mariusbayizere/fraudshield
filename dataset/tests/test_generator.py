from __future__ import annotations

import ast
import hashlib
import json
import re
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.dataset as ds
import pyarrow.parquet as pq
import pytest

from fraudshield_dataset import cli
from fraudshield_dataset.generator import config as config_module
from fraudshield_dataset.generator import pipeline as pipeline_module
from fraudshield_dataset.generator.config import (
    CHANNELS,
    SEGMENTS,
    SimulationConfig,
    build_config,
    check_urban_share,
    month_labels,
    seasonal_factor,
    segment_channel_shares,
)
from fraudshield_dataset.generator.fraud import NOVEL_VARIANT, SCENARIOS, FraudModel
from fraudshield_dataset.generator.legit import LegitimateBehaviour
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.generator.population import Population
from fraudshield_dataset.generator.schema import ACCOUNT_EVENTS, LABELS, TRANSACTIONS
from fraudshield_dataset.params import ParameterError, ParameterSet, ScaleError, load_parameters

GENERATOR_SOURCES = Path(config_module.__file__).parent


def _files(root: Path) -> dict[str, str]:
    return {
        str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*.parquet"))
    }


@pytest.fixture(scope="module")
def small(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("small")
    generate(
        build_config(load_parameters(), seed=11, total_rows=6000),
        output,
        chunk_size=8,
        allow_missing_scenarios=True,
    )
    return output


@pytest.mark.req("D-08", "ML-DATA-01")
def test_same_seed_is_byte_identical_across_chunk_sizes(small: Path, tmp_path: Path) -> None:
    for chunk_size in (1, 64):
        other = tmp_path / f"chunk-{chunk_size}"
        generate(
            build_config(load_parameters(), seed=11, total_rows=6000),
            other,
            chunk_size=chunk_size,
            allow_missing_scenarios=True,
        )
        assert _files(other) == _files(small)
        assert (other / "manifest.json").read_bytes() == (small / "manifest.json").read_bytes()


@pytest.mark.req("D-08")
def test_a_different_seed_gives_different_data(small: Path, tmp_path: Path) -> None:
    generate(
        build_config(load_parameters(), seed=12, total_rows=6000),
        tmp_path,
        chunk_size=8,
        allow_missing_scenarios=True,
    )
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


def test_segment_channel_preferences_keep_their_ordering() -> None:
    """Fitting must not turn a rural profile into an urban one (ML-DATA-03)."""
    parameters = load_parameters()
    shares = segment_channel_shares(parameters)

    assert shares["rural_ussd"]["USSD"] > shares["urban_salaried"]["USSD"]
    assert shares["rural_ussd"]["AGENT_BANKING"] > shares["urban_salaried"]["AGENT_BANKING"]
    assert shares["urban_salaried"]["CARD"] > shares["rural_ussd"]["CARD"]
    assert shares["urban_salaried"]["ONLINE"] > shares["informal_trader"]["ONLINE"]
    for segment, mix in shares.items():
        assert sum(mix.values()) == pytest.approx(1.0), segment


def test_a_segment_missing_a_channel_weight_is_rejected() -> None:
    parameters = load_parameters()
    weights = {segment: {"MOBILE_MONEY": 1.0} for segment in SEGMENTS}
    changed = _with(parameters, behaviour__segment_channel_preference=weights)
    with pytest.raises(ParameterError, match="weight for every channel"):
        segment_channel_shares(changed)


def test_a_zero_channel_weight_is_rejected() -> None:
    parameters = load_parameters()
    weights = {
        segment: {channel: (0.0 if channel == "CARD" else 1.0) for channel in CHANNELS}
        for segment in SEGMENTS
    }
    changed = _with(parameters, behaviour__segment_channel_preference=weights)
    with pytest.raises(ParameterError, match="positive weight"):
        segment_channel_shares(changed)


@pytest.mark.req("ML-DATA-05")
def test_segments_must_match_the_sourced_urban_share() -> None:
    """The urban share of customers is sourced; the segment split may not contradict it."""
    changed = _with(
        load_parameters(),
        population__segment_share={
            "urban_salaried": 0.50,
            "informal_trader": 0.20,
            "rural_ussd": 0.20,
            "student": 0.10,
        },
    )
    with pytest.raises(ParameterError, match="urban_share_of_customers"):
        check_urban_share(changed)


def test_month_labels_cross_year_boundaries() -> None:
    assert month_labels("2024-11", 3) == ("2024-11", "2024-12", "2025-01")


def test_chunk_size_must_be_positive(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="chunk_size"):
        generate(build_config(load_parameters(), seed=1, total_rows=100), tmp_path, chunk_size=0)


def test_cli_generate(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    arguments = ["generate", "--output", str(tmp_path), "--rows", "300", "--seed", "3"]
    assert cli.main([*arguments, "--allow-missing-scenarios"]) == 0
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
    # solver mechanics: iteration cap for the channel-mix fitting, and the tolerance for the check
    # that the assumed segment split still matches the sourced urban share (0.5 pp, as ML-DATA-03)
    200, 0.005,
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


def _with(parameters: ParameterSet, **overrides: object) -> ParameterSet:
    changed = dict(parameters.parameters)
    for key, value in overrides.items():
        name = key.replace("__", ".")
        changed[name] = type(changed[name])(**{**changed[name].__dict__, "value": value})
    return ParameterSet(changed, parameters.descriptions)


# Every test-period SIM swap uses the novel sub-variant, so its placement can be checked at a size
# that runs in seconds; the realism report checks presence with the real parameters at full size.
_MEDIUM = {"fraud__novelty_share_of_sim_swap": 1.0}


@pytest.fixture(scope="module")
def medium(tmp_path_factory: pytest.TempPathFactory) -> Path:
    output = tmp_path_factory.mktemp("medium")
    parameters = _with(load_parameters(), **_MEDIUM)
    generate(
        build_config(parameters, seed=5, total_rows=60_000),
        output,
        chunk_size=16,
        allow_missing_scenarios=True,
    )
    return output


@pytest.mark.req("ML-DATA-04", "D-08")
def test_all_eight_scenarios_occur_and_the_novel_variant_only_in_the_test_period(
    medium: Path,
) -> None:
    config = build_config(_with(load_parameters(), **_MEDIUM), seed=5, total_rows=60_000)
    labels = ds.dataset(medium / "labels", format="parquet", partitioning="hive").to_table()
    transactions = ds.dataset(medium / "transactions", format="parquet", partitioning="hive")
    timestamps = transactions.to_table(columns=["transaction_timestamp"])["transaction_timestamp"]
    fraud = labels.filter(labels["is_fraud_true"])
    assert set(pc.unique(fraud["fraud_type"]).to_pylist()) == set(SCENARIOS)
    variants = labels["scenario_variant"].to_pylist()
    novel = [
        t
        for t, v in zip(timestamps.cast(pa.int64()).to_pylist(), variants, strict=True)
        if v == NOVEL_VARIANT
    ]
    assert novel, "the novel sub-variant must appear in the test period"
    assert min(novel) >= config.split.test_start
    legitimate = labels.filter(pc.invert(labels["is_fraud_true"]))
    assert legitimate["fraud_type"].null_count == legitimate.num_rows
    rate = pc.mean(labels["is_fraud_true"].cast(pa.float64())).as_py()
    assert 0.004 < rate < 0.015


@pytest.mark.req("D-08")
def test_fraud_enabling_events_are_recorded_like_legitimate_events(medium: Path) -> None:
    """The events fraud plants must be indistinguishable from legitimate ones by construction.

    This test used to assert only that the two event types exist, which the M2 principal review
    found vacuous: the property in its name was false, and the microsecond part of the timestamp
    plus the day of the month identified a victim's account with near-perfect precision.
    """
    events = ds.dataset(medium / "account_events", format="parquet", partitioning="hive").to_table()
    transactions = ds.dataset(
        medium / "transactions", format="parquet", partitioning="hive"
    ).to_table(columns=["account_id"])
    labels = ds.dataset(medium / "labels", format="parquet", partitioning="hive").to_table(
        columns=["is_fraud_true"]
    )
    kinds = set(pc.unique(events["event_type"]).to_pylist())
    assert kinds == {"SIM_SWAP", "DEVICE_CHANGE"}

    victims = {
        account
        for account, fraud in zip(
            transactions["account_id"].to_pylist(),
            labels["is_fraud_true"].to_pylist(),
            strict=True,
        )
        if fraud
    }
    on_victim = np.array([a in victims for a in events["account_id"].to_pylist()])
    micros = events["event_timestamp"].cast(pa.int64()).to_numpy()
    sub_second = micros % 1_000_000
    day = pc.day(events["event_timestamp"]).to_numpy()
    base = float(on_victim.mean())

    # Sub-second precision and the day of the month must say nothing about whose account it is.
    assert (sub_second != 0).mean() > 0.9, "legitimate events are quantised to whole seconds again"
    assert float(on_victim[sub_second != 0].mean()) < base + 0.15
    assert (day >= 28).any(), "no event falls in the last days of a month"
    assert float(on_victim[day >= 28].mean()) < base + 0.25


def test_calibration_refuses_a_share_its_role_holders_cannot_stage() -> None:
    """A share beyond what the role can stage at this size is refused rather than quietly dropped.

    The refusal now says which of the three causes it is and, when more rows would fix it, how
    many. This case is reachable by scale, so it is a ScaleError quoting a minimum rather than a
    flat parameter error (M2 milestone review, MAJOR M-4).
    """
    parameters = load_parameters()
    shares = dict.fromkeys(SCENARIOS, 0.0)
    shares["synthetic_identity"] = 0.9
    shares["velocity"] = 0.1
    config = build_config(
        _with(
            parameters,
            fraud__scenario_share=shares,
            fraud__synthetic_identity_fraction=0.01,
        ),
        seed=1,
        total_rows=600_000,
    )
    population = Population(config)
    with pytest.raises(ScaleError, match=r"synthetic_identity needs \d+ customer-months"):
        FraudModel(config, population, LegitimateBehaviour(config, population))


def test_fraud_rate_ramp_meets_both_targets() -> None:
    parameters = load_parameters()
    config = build_config(parameters, seed=1)
    weights = [
        v * seasonal_factor(parameters, m)
        for m, v in zip(config.months, config.monthly_volume, strict=True)
    ]
    overall = sum(r * w for r, w in zip(config.fraud_rate_by_month, weights, strict=True)) / sum(
        weights
    )
    assert overall == pytest.approx(parameters.number("fraud.fraud_rate_overall"), rel=1e-6)
    assert config.fraud_rate_by_month[-1] > config.fraud_rate_by_month[0]
    split = config.split
    assert split.validation_start < split.calibration_start < split.embargo_start
    assert split.test_start - split.embargo_start == 7 * 86_400_000_000


def _test_period_rate(parameters: ParameterSet, config: SimulationConfig) -> float:
    """Volume-weighted fraud rate over the part of each month inside the test period."""
    weights = []
    for month, planned in zip(config.months, config.monthly_volume, strict=True):
        low, high = config_module.month_bounds(month)
        scaled = planned * seasonal_factor(parameters, month)
        weights.append(scaled * max(0, high - max(low, config.split.test_start)) / (high - low))
    return sum(r * w for r, w in zip(config.fraud_rate_by_month, weights, strict=True)) / sum(
        weights
    )


@pytest.mark.req("ML-DATA-02", "D-07")
def test_the_intensity_schedule_meets_the_test_period_target_by_construction() -> None:
    parameters = load_parameters()
    config = build_config(parameters, seed=1)
    assert _test_period_rate(parameters, config) == pytest.approx(
        parameters.number("fraud.fraud_rate_test"),
        abs=parameters.number("fraud.test_rate_tolerance"),
    )


@pytest.mark.req("ML-DATA-02")
def test_a_flat_intensity_schedule_is_refused_because_it_misses_the_test_target() -> None:
    parameters = load_parameters()
    months = len(parameters.numbers("fraud.monthly_intensity"))
    flat = _with(parameters, fraud__monthly_intensity=[1.0] * months)
    with pytest.raises(ParameterError, match="test-period rate"):
        build_config(flat, seed=1)


@pytest.mark.req("ML-DATA-02")
def test_a_schedule_of_the_wrong_length_is_refused() -> None:
    short = _with(load_parameters(), fraud__monthly_intensity=[1.0, 1.0])
    with pytest.raises(ParameterError, match="monthly_intensity has 2 values"):
        build_config(short, seed=1)


@pytest.mark.req("ML-DATA-02", "ML-DATA-04")
def test_every_month_allocates_its_whole_fraud_target_across_feasible_scenarios() -> None:
    config = build_config(load_parameters(), seed=3, total_rows=60_000)
    population = Population(config)
    model = FraudModel(config, population, LegitimateBehaviour(config, population))
    for month in range(len(config.months)):
        allocation = model.allocation(month)
        assert sum(allocation.values()) == model.monthly_target(month)
        assert set(allocation) == set(SCENARIOS)
        for scenario, rows in allocation.items():
            low, high = model.rows_range[scenario]
            planned = model.plan(scenario, month)
            assert sum(planned.values()) == rows
            assert all(low <= count <= high for count in planned.values())
            assert len(planned) == model.incidents(scenario, month)
            assert len(planned) <= model.eligible_count(scenario, month)
    # No customer has reached its bust-out month at the start, so that scenario cannot run yet and
    # its share is reallocated: the month still hits its target. Which later months can run it
    # depends on when customers joined, so the property is that it runs over the simulation, not
    # that it runs in any particular month.
    assert model.allocation(0)["synthetic_identity"] == 0
    staged = [model.allocation(m)["synthetic_identity"] for m in range(len(config.months))]
    assert sum(staged) > 0
    assert sum(1 for rows in staged if rows) >= 3


@pytest.mark.req("ML-DATA-05")
def test_country_and_segment_quotas_are_exact_for_every_population_prefix() -> None:
    config = build_config(load_parameters(), seed=7, total_rows=200_000)
    population = Population(config)
    shares = config.parameters.mapping("geography.country_share")
    counts = dict.fromkeys(shares, 0)
    for index in range(config.customers_total):
        counts[population.country_of(index)] += 1
        size = index + 1
        # Sequential apportionment keeps every prefix within one customer of its exact quota,
        # so the country mix holds at development scale and not only in expectation.
        for country, share in shares.items():
            assert abs(counts[country] - share * size) < 1.0


@pytest.mark.req("D-08", "ML-DATA-01")
def test_byte_identity_holds_across_a_row_group_boundary(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Determinism was only ever tested on months small enough to fit one Parquet row group.

    A release month holds several, and the layout is what `combine_chunks()` is there to keep
    chunk-independent, so the row-group boundary is forced here rather than by a larger run
    (M2 principal review, area 2 "could not verify").
    """
    monkeypatch.setattr(pipeline_module, "ROW_GROUP_SIZE", 64)
    monkeypatch.setitem(pipeline_module._WRITE_OPTIONS, "row_group_size", 64)
    one, many = tmp_path / "chunk-1", tmp_path / "chunk-64"
    for output, chunk in ((one, 1), (many, 64)):
        generate(
            build_config(load_parameters(), seed=11, total_rows=6000),
            output,
            chunk_size=chunk,
            allow_missing_scenarios=True,
        )

    groups = pq.ParquetFile(
        next((one / "transactions").glob("month=*/part-0000.parquet"))
    ).num_row_groups
    assert groups > 1, "the boundary this test exists for was not reached"
    assert _files(one) == _files(many)


@pytest.mark.req("D-08", "ML-DATA-04")
def test_a_run_too_small_for_a_scenario_fails_with_the_minimum_size() -> None:
    """A dataset missing a fraud scenario must fail loudly, not be produced quietly.

    Below about 170,000 rows the mule pool is usually empty, and the capacity guard used to skip
    the scenario and carry on, so every development run produced a dataset with seven of the eight
    scenarios and nothing said so (M2 milestone review, MAJOR M-4).
    """
    config = build_config(load_parameters(), seed=20260917, total_rows=10_000)
    population = Population(config)

    with pytest.raises(ScaleError) as raised:
        FraudModel(config, population, LegitimateBehaviour(config, population))

    message = str(raised.value)
    assert "mule_account" in message
    assert "parameters are not at fault" in message
    # The minimum must be larger than the size that just failed, or it sends the reader in a circle.
    quoted = int(re.search(r"Generate at ([\d,]+) rows", message).group(1).replace(",", ""))
    assert quoted > 10_000


@pytest.mark.req("ML-DATA-04")
def test_the_minimum_size_is_a_property_of_the_parameters_not_the_run() -> None:
    """The same minimum must come back whatever size it was computed from.

    It is derived by extrapolating this run's role rate, so if it moved with the run it would be
    measuring the run rather than the parameters.
    """
    quoted = []
    for rows in (10_000, 30_000, 100_000):
        config = build_config(load_parameters(), seed=20260923, total_rows=rows)
        population = Population(config)
        with pytest.raises(ScaleError) as raised:
            FraudModel(config, population, LegitimateBehaviour(config, population))
        quoted.append(
            int(
                re.search(r"Generate at ([\d,]+) rows", str(raised.value)).group(1).replace(",", "")
            )
        )

    assert max(quoted) - min(quoted) < 0.05 * min(quoted), quoted
