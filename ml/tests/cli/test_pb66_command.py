"""`fs-features pb66` against a cache written to disk, as the full-volume run will read it.

The cache is written with `smoke.cache_write`, carrying PB-66's timestamp and amount columns, and
the command reads it through `gate_run.load_cache`, the gate's own reader. Test rows span the three
months of D-07's test period from a partial first day, so the month buckets and the full-day rule
both have something to do.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

import numpy as np
import pytest

from fraudshield_ml import cli
from fraudshield_ml.features.registry import REGISTRY, Dtype
from fraudshield_ml.training import gate_run, lofto, pb66_run, smoke

SEGMENTS = {"train": 500, "validation": 250, "calibration": 250, "test": 600}
TEST_START = int(datetime(2025, 10, 28, 22, 2, tzinfo=UTC).timestamp()) * 1_000_000
TEST_SPAN_US = 64 * 86_400 * 1_000_000


def _value(name: str, fraud: bool, rng: np.random.Generator) -> object:
    spec = REGISTRY[name]
    shift = 1.0 if fraud and name == pb66_run.BASELINE_FEATURE else 0.0
    if fraud and name == "counterparty_is_new_for_account":
        shift = 1.0
    if spec.dtype is Dtype.CATEGORICAL:
        categories = spec.categories or ("A", "B")
        return str(categories[int(rng.integers(0, len(categories)))])
    if spec.dtype is Dtype.BOOL:
        return bool(rng.random() < (0.75 if shift else 0.3))
    if spec.dtype in (Dtype.INT64, Dtype.ORDINAL):
        return int(max(0, round(rng.normal(3 + 2 * shift, 2))))
    return float(rng.normal(shift, 1.0))


def _write(path: Path, *, timestamps: bool = True) -> None:
    rng = np.random.default_rng(13)
    names = smoke.trainable_features()
    rows: list[dict[str, object]] = []
    extras: dict[str, list[object]] = {k: [] for k in smoke.CACHE_EXTRAS}
    if timestamps:
        extras[smoke.CACHE_TIMESTAMP] = []
        extras[smoke.CACHE_AMOUNT] = []
        extras[smoke.CACHE_FRAUD_TYPE] = []
        extras[smoke.CACHE_TRUE_LABEL] = []
    for segment, count in SEGMENTS.items():
        for i in range(count):
            fraud = i % 5 == 0
            rows.append({n: _value(n, fraud, rng) for n in names})
            extras[smoke.CACHE_LABEL].append(str(fraud))
            extras[smoke.CACHE_ACCOUNT].append(f"acct-{int(rng.integers(0, 150))}")
            extras[smoke.CACHE_SEGMENT].append(segment)
            extras[smoke.CACHE_COUNTRY].append(("RW", "KE", "UG")[i % 3])
            extras[smoke.CACHE_CHANNEL].append(("MOBILE_MONEY", "USSD", "CARD")[(i // 3) % 3])
            extras[smoke.CACHE_VARIANT].append("base" if fraud else "")
            if timestamps:
                offset = (
                    TEST_SPAN_US * i // count if segment == "test" else -86_400_000_000 * (i + 30)
                )
                extras[smoke.CACHE_TIMESTAMP].append(str(TEST_START + offset))
                extras[smoke.CACHE_AMOUNT].append(repr(float(rng.lognormal(9, 1))))
                kind = lofto.SCENARIOS[(i // 5) % len(lofto.SCENARIOS)] if fraud else ""
                extras[smoke.CACHE_FRAUD_TYPE].append(kind)
                extras[smoke.CACHE_TRUE_LABEL].append(str(fraud))
    smoke.cache_write(path, {"dataset": "fixture"}, rows, extras)  # type: ignore[arg-type]


@pytest.fixture(scope="module")
def cache(tmp_path_factory: pytest.TempPathFactory) -> Path:
    path = tmp_path_factory.mktemp("pb66") / "features.parquet"
    _write(path)
    return path


def test_pb66_reports_every_preregistered_section_and_logs_the_access(
    cache: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str], private_access_log: Path
) -> None:
    before = private_access_log.read_text() if private_access_log.exists() else ""
    out = tmp_path / "out"
    scores = tmp_path / "scores.csv"
    code = cli.main(
        [
            "pb66",
            str(cache),
            "--out",
            str(out),
            "--resamples",
            "20",
            "--scores-out",
            str(scores),
            "--purpose",
            "PB-66 fixture run",
        ]
    )
    assert code == 0
    printed = capsys.readouterr().out
    for heading in (
        "3.1 PR-AUC",
        "3.2 Precision at an ASSUMED",
        "3.3 By month",
        "3.4 Drummond",
        "3.5 Fairness",
    ):
        assert heading in printed
    # The preamble comes before the first fairness row, never after it (owner, 2026-10-19).
    assert printed.index(pb66_run.FAIRNESS_PREAMBLE) < printed.index("by country")
    assert "urban/rural: not available" in printed
    for month in ("2025-10", "2025-11", "2025-12"):
        assert month in printed

    metrics = json.loads((out / "metrics.json").read_text())
    assert metrics["budgets"]["assumed"] == [10, 50, 100, 200]
    assert len(metrics["budgets"]["days_excluded"]) == 2
    assert metrics["budgets"]["first_day"] == "2025-10-29"
    assert metrics["training_history"]["training_rows"] == SEGMENTS["train"]
    # Train rows sit 30 to 529 days before the test start, a day apart: those within 90 days of
    # the earliest are the 90 oldest.
    assert metrics["training_history"]["within_90_days_of_the_first"] == 90
    assert "NaN" not in (out / "metrics.json").read_text(), "metrics.json must be strict JSON"
    assert sorted(metrics["fairness"]["country"]) == ["KE", "RW", "UG"]
    assert [m["month"] for m in metrics["months"]["model"]] == ["2025-10", "2025-11", "2025-12"]
    assert sum(m["rows"] for m in metrics["months"]["model"]) == SEGMENTS["test"]
    costs = metrics["cost_curve"]["model"]["normalised_expected_cost"]
    assert min(costs) >= 0.0
    assert max(costs) <= 1.0
    for figure in ("pr.svg", "cost_curve.svg"):
        assert (out / "figures" / figure).read_text().startswith("<svg")
    lines = scores.read_text().splitlines()
    assert len(lines) == SEGMENTS["test"] + 1
    assert lines[0].split(",") == list(pb66_run.SCORE_COLUMNS)
    first = dict(zip(pb66_run.SCORE_COLUMNS, lines[1].split(","), strict=True))
    assert first["account_id"].startswith("acct-")
    assert first["fraud_type"] in {*lofto.SCENARIOS, ""}
    assert first["counterparty_is_new_for_account"] in {"0.0", "1.0"}
    assert sorted(metrics["reliability_by_channel"]) == ["CARD", "MOBILE_MONEY", "USSD"]
    ten = metrics["budgets"]["model"]["10"]
    assert ten["alerts_per_1000"] > 0
    assert ten["fraud_rwf_caught"] > 0
    assert (out / "figures" / "reliability_by_channel.svg").exists()

    after = private_access_log.read_text()
    added = [json.loads(line) for line in after[len(before) :].splitlines()]
    assert [(a["command"], a["purpose"], a["test_rows"]) for a in added] == [
        ("pb66", "PB-66 fixture run", SEGMENTS["test"])
    ]


def test_a_cache_without_pb66_columns_is_refused_before_any_access(
    tmp_path: Path, capsys: pytest.CaptureFixture[str], private_access_log: Path
) -> None:
    old = tmp_path / "old.parquet"
    _write(old, timestamps=False)
    before = private_access_log.read_text() if private_access_log.exists() else ""
    code = cli.main(["pb66", str(old), "--out", str(tmp_path / "out"), "--resamples", "5"])
    assert code == 2
    assert "_timestamp_us" in capsys.readouterr().err
    after = private_access_log.read_text() if private_access_log.exists() else ""
    assert after == before


def test_pb66_refuses_a_missing_cache(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    code = cli.main(["pb66", str(tmp_path / "none.parquet"), "--out", str(tmp_path / "out")])
    assert code == 2
    assert "no usable feature matrix" in capsys.readouterr().err


def test_pb66_scores_the_gates_own_seed_1_model(
    cache: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str], private_access_log: Path
) -> None:
    """The workflow's identity check, in miniature: pb66's seed-1 figures are the gate's."""
    before = private_access_log.read_text() if private_access_log.exists() else ""
    gate_out = tmp_path / "gate"
    code = cli.main(
        [
            "gate",
            str(cache),
            "--out",
            str(gate_out),
            "--seeds",
            "1",
            "--resamples",
            "5",
            "--metrics-only",
            "--purpose",
            "PB-66 fixture gate",
        ]
    )
    assert code == 0
    pb66_out = tmp_path / "pb66"
    assert cli.main(["pb66", str(cache), "--out", str(pb66_out), "--resamples", "5"]) == 0
    capsys.readouterr()
    gate_json = json.loads((gate_out / "metrics.json").read_text())
    gate_metrics = {g["id"]: g["value"] for g in gate_json["gate"]}
    identity = json.loads((pb66_out / "metrics.json").read_text())["model_identity"]
    assert identity["auc"] == pytest.approx(gate_metrics["ML-GATE-01"], abs=1e-12)
    assert identity["recall_at_flag"] == pytest.approx(gate_metrics["ML-GATE-03"], abs=1e-12)
    added = [
        json.loads(line) for line in private_access_log.read_text()[len(before) :].splitlines()
    ]
    assert [a["purpose"] for a in added] == ["PB-66 fixture gate", "PB-66 full-volume evaluation"]


def test_a_dropped_row_leaves_every_fitted_set_but_never_the_test(cache: Path) -> None:
    whole = gate_run.load_cache(cache)
    drop = [
        whole.split.train[0],
        whole.split.validation[0],
        whole.split.calibration[0],
        whole.test[0],
    ]
    held = gate_run.load_cache(cache, drop=drop)
    assert len(held.split.train) == len(whole.split.train) - 1
    assert len(held.split.validation) == len(whole.split.validation) - 1
    assert len(held.split.calibration) == len(whole.split.calibration) - 1
    assert held.test == whole.test


def test_lofto_refits_without_each_scenario_and_logs_before_scoring(
    cache: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str], private_access_log: Path
) -> None:
    before = private_access_log.read_text() if private_access_log.exists() else ""
    out = tmp_path / "lofto"
    code = cli.main(["lofto", str(cache), "--out", str(out), "--budget-minutes", "100000"])
    assert code == 0
    printed = capsys.readouterr().out
    assert "volume: full volume (500 training rows)" in printed
    payload = json.loads((out / "lofto.json").read_text())
    names = [r["scenario"] for r in payload["scenarios"]]
    assert names == [*lofto.SCENARIOS, "mule_account without reversal"]
    for row in payload["scenarios"][: len(lofto.SCENARIOS)]:
        assert row["test_frauds"] > 0
        low, high = row["held_out_budget_wilson"]
        assert 0.0 <= low <= row["held_out_recall_at_budget"] <= high <= 1.0
    # Every scenario's non-test rows were removed for its refit: frauds of that type only.
    assert all(n > 0 for n in payload["rows_removed"].values())
    added = [
        json.loads(line) for line in private_access_log.read_text()[len(before) :].splitlines()
    ]
    assert [a["command"] for a in added] == ["lofto"]
    assert added[0]["test_rows"] == SEGMENTS["test"]
    assert "full volume" in added[0]["purpose"]


def test_lofto_falls_back_when_nine_fits_would_not_fit(
    cache: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(lofto, "FALLBACK_TRAIN_ROWS", 200)
    ticks = iter([0.0, 3600.0])  # the first fit "takes" an hour
    told: list[tuple[str, int]] = []
    result = lofto.run(
        cache,
        seed=1,
        budget_minutes=60,
        clock=lambda: next(ticks),
        on_volume=lambda volume, rows: told.append((volume, rows)),
    )
    assert result.train_rows_used == 200
    assert told[0][0].startswith("200 training rows: one full-volume fit took 60.0 min")
    assert told[0][1] == SEGMENTS["test"]
    assert set(result.held_out) == set(lofto.SCENARIOS)


def test_lofto_refuses_a_cache_without_fraud_types(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    old = tmp_path / "old.parquet"
    _write(old, timestamps=False)
    assert cli.main(["lofto", str(old), "--out", str(tmp_path / "out")]) == 2
    assert "_fraud_type" in capsys.readouterr().err
    assert cli.main(["lofto", str(tmp_path / "none.parquet"), "--out", str(tmp_path / "o")]) == 2
