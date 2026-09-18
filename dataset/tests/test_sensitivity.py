from __future__ import annotations

import json
import math
from pathlib import Path

import pytest

from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import load_parameters
from fraudshield_dataset.sensitivity import (
    RELATIVE_TOLERANCE,
    SHARE_TOLERANCE,
    Profile,
    Trial,
    _scaled,
    influences,
    main,
    profile,
)

SEED = 20260917
ROWS = 6_000


def test_scaling_a_value_leaves_text_and_flags_alone() -> None:
    assert _scaled(10.0, 0.1) == pytest.approx(11.0)
    assert _scaled(3, 0.1) == 4  # an integer must move by at least one
    assert _scaled([1.0, 2.0], 0.5) == [1.5, 3.0]
    assert _scaled({"a": 2.0}, 0.5) == {"a": 3.0}
    assert _scaled("2024-01", 0.1) is None
    assert _scaled(True, 0.1) is None
    assert _scaled({"RW": "RWF"}, 0.1) is None


def test_the_score_is_in_units_of_each_property_tolerance() -> None:
    baseline = Profile(shares={"channel:CARD": 0.10}, counts={"rows": 1000.0})
    moved_share = Profile(shares={"channel:CARD": 0.10 + SHARE_TOLERANCE}, counts={"rows": 1000.0})
    moved_count = Profile(
        shares={"channel:CARD": 0.10}, counts={"rows": 1000.0 * (1 + RELATIVE_TOLERANCE)}
    )

    assert baseline.compare(moved_share)[0] == pytest.approx(1.0)
    assert baseline.compare(moved_count)[0] == pytest.approx(1.0)
    assert baseline.compare(baseline) == (0.0, "", "no change")


def test_the_profile_measures_the_headline_properties(tmp_path: Path) -> None:
    output = tmp_path / "ds"
    generate(build_config(load_parameters(), seed=SEED, total_rows=ROWS), output)

    measured = profile(output)

    assert measured.counts["rows"] > 0
    assert measured.counts["accounts"] > 0
    assert measured.counts["mean_amount_rwf"] > measured.counts["median_amount_rwf"]
    assert sum(v for k, v in measured.shares.items() if k.startswith("channel:")) == pytest.approx(
        1.0
    )
    assert sum(v for k, v in measured.shares.items() if k.startswith("month:")) == pytest.approx(
        1.0
    )
    assert 0.0 < measured.shares["fraud_rate"] < 0.05


@pytest.mark.req("ML-DATA-01")
def test_ranking_orders_an_influential_parameter_above_an_inert_one(tmp_path: Path) -> None:
    """A parameter that moves the data must outrank one that cannot."""
    keys = (
        "population.mean_transactions_per_active_customer_month",  # changes every row count
        "labels.label_delay_log_sigma",  # touches only when a label becomes available
        "volume.start_month",  # a string: not perturbable
    )
    ranking = influences(load_parameters(), Trial(rows=ROWS, seed=SEED), tmp_path, keys)
    by_key = {r.key: r for r in ranking}

    assert math.isinf(by_key["volume.start_month"].score)
    assert by_key["volume.start_month"].perturbed is False
    assert (
        by_key["population.mean_transactions_per_active_customer_month"].score
        > by_key["labels.label_delay_log_sigma"].score
    )
    assert ranking[0].key == "volume.start_month"  # refusals and non-numerics rank first


def test_cli_writes_a_ranking_with_the_parameter_digest(tmp_path: Path) -> None:
    output = tmp_path / "ranking.json"
    assert main(["--rows", str(ROWS), "--seed", str(SEED), "--output", str(output)]) == 0

    written = json.loads(output.read_text(encoding="utf-8"))
    assert written["rows"] == ROWS
    assert written["ranked"] == written["parameter_count"]
    assert len(written["parameter_values_sha256"]) == 64
    assert written["parameter_count"] == len(written["parameters"])
    assert written["ranked"] == len(written["parameters"])
    # Every entry carries either a score or a reason it has none.
    for entry in written["parameters"]:
        assert entry["score"] is not None or entry["detail"]
