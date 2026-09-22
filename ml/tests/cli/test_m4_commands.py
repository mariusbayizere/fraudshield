"""`fs-features battery`, `frontier` and `seed-variance` against a cache written to disk.

The same principle as `test_fs_features.py`: the cache is written with `smoke.cache_write`, the
function `fs-features evaluate --cache` uses, and each command reads it back through its own
reader. A fixture that handed the runners an in-memory matrix would skip the part most likely to
break, which is the cache's columns and segment labels agreeing with what the runners expect.

The fixture is small and noisy on purpose. Perfectly separable rows give a Hanley-McNeil variance
of exactly zero and seed-to-seed AUCs that never move, so every interval and every seed-variance
comparison would compare zero with zero and pass whatever the code did.
"""

from __future__ import annotations

import json
import math
import re
from pathlib import Path

import numpy as np
import pytest

from fraudshield_ml import cli
from fraudshield_ml.features.registry import REGISTRY, Dtype
from fraudshield_ml.training import access, gate, smoke
from fraudshield_ml.training import battery as battery_module
from fraudshield_ml.training.battery import NOVEL_VARIANT

REVERSAL = "reversal_scam_social_engineering"
COUNTRIES = ("AA", "BB", "CC")
CHANNELS = ("MOBILE_MONEY", "USSD", "CARD", "AGENT_BANKING")
#: Features that carry the fraud signal, one from each of four groups, so an ablation that removes
#: any one group still leaves signal behind — the redundancy shape PB-60 measured.
SIGNAL = (
    "velocity_ratio_1h_vs_30d",
    "counterparty_is_new_for_account",
    "seconds_since_last_tx",
    "device_age_days",
)


def _value(name: str, fraud: bool, carries_signal: bool, rng: np.random.Generator) -> object:
    spec = REGISTRY[name]
    shift = 1.2 if fraud and carries_signal and name in SIGNAL else 0.0
    if name in {"seconds_since_last_tx", "device_age_days"}:
        shift = -shift
    if spec.dtype is Dtype.CATEGORICAL:
        categories = spec.categories or ("A", "B")
        return str(categories[int(rng.integers(0, len(categories)))])
    if spec.dtype is Dtype.BOOL:
        return bool(rng.random() < (0.75 if shift else 0.3))
    if spec.dtype in (Dtype.INT64, Dtype.ORDINAL):
        return int(max(0, round(rng.normal(3 + 2 * shift, 2))))
    return float(rng.normal(shift, 1.0))


def _write_cache(
    path: Path,
    *,
    segments: dict[str, int],
    variants: bool = True,
    seed: int = 7,
    mcc: bool = False,
) -> dict[str, int]:
    """Rows in each named segment, 20% fraud. Returns the fraud count per segment."""
    rng = np.random.default_rng(seed)
    names = smoke.trainable_features()
    rows: list[dict[str, object]] = []
    extras: dict[str, list[object]] = {k: [] for k in smoke.CACHE_EXTRAS}
    if mcc:
        extras[smoke.CACHE_MCC] = []
    fraud_in: dict[str, int] = {}
    for segment, count in segments.items():
        fraud_in[segment] = 0
        for i in range(count):
            fraud = i % 5 == 0
            variant = ""
            if fraud and variants and segment == "test":
                # Mostly the base shape, with a novel shape that keeps the signal and a
                # reversal-scam shape that carries none of it — the PB-61 contrast in miniature.
                variant = (NOVEL_VARIANT, REVERSAL, "base", "base")[(i // 5) % 4]
            elif fraud and variants:
                variant = "base"
            carries_signal = variant != REVERSAL
            fraud_in[segment] += fraud
            rows.append({n: _value(n, fraud, carries_signal, rng) for n in names})
            extras[smoke.CACHE_LABEL].append(str(fraud))
            extras[smoke.CACHE_ACCOUNT].append(f"acct-{int(rng.integers(0, 150))}")
            extras[smoke.CACHE_SEGMENT].append(segment)
            extras[smoke.CACHE_COUNTRY].append(COUNTRIES[i % len(COUNTRIES)])
            extras[smoke.CACHE_CHANNEL].append(CHANNELS[(i // 3) % len(CHANNELS)])
            extras[smoke.CACHE_VARIANT].append(variant)
            if mcc:
                extras[smoke.CACHE_MCC].append("4829" if i % 4 == 0 else "5411")
    smoke.cache_write(path, {"dataset": "fixture"}, rows, extras)  # type: ignore[arg-type]
    return fraud_in


@pytest.fixture(scope="module")
def cache(tmp_path_factory: pytest.TempPathFactory) -> tuple[Path, dict[str, int]]:
    path = tmp_path_factory.mktemp("cache") / "features.parquet"
    fraud = _write_cache(path, segments={"train": 400, "test": 400, "calibration": 150})
    return path, fraud


@pytest.fixture(scope="module")
def battery_output(
    cache: tuple[Path, dict[str, int]], tmp_path_factory: pytest.TempPathFactory
) -> str:
    """One battery run shared by the assertions below: it refits two dozen models."""
    import contextlib  # noqa: PLC0415
    import io  # noqa: PLC0415

    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        assert cli.main(["battery", str(cache[0]), "--top", "5"]) == 0
    return buffer.getvalue()


@pytest.mark.req("ML-GATE-11", "ML-GATE-10", "ML-GATE-03")
def test_the_battery_reports_every_section_off_one_cache(
    battery_output: str, cache: tuple[Path, dict[str, int]]
) -> None:
    """Every section present, in order, with the row counts the cache actually holds."""
    sections = [
        "HEADLINE",
        "CALIBRATION",
        "DECISION REGION",
        "EXPLANATIONS",
        "ABLATIONS, ONE GROUP REMOVED",
        "ABLATIONS, ONE GROUP ONLY",
        "BY COUNTRY",
        "BY CHANNEL",
        "NOVEL SUB-VARIANT",
        "LEAVE-ONE-COUNTRY-OUT",
    ]
    positions = [battery_output.find(s) for s in sections]
    assert all(p >= 0 for p in positions), dict(zip(sections, positions, strict=True))
    assert positions == sorted(positions), "the sections are out of their documented order"
    assert "rows train/test/calibration 400/400/150" in battery_output
    headline = next(line for line in battery_output.splitlines() if line.startswith("  full model"))
    assert headline.split()[-1] == str(cache[1]["test"]), "the headline's fraud count is wrong"


@pytest.mark.req("ML-GATE-03")
def test_the_battery_separates_a_shape_that_lacks_the_signal(battery_output: str) -> None:
    """The fixture's reversal-scam rows carry none of the signal; the report must say so.

    Its interval should sit wholly below the base shape's, and the report must print the verdict
    the interval supports rather than leave the reader to compare two point estimates.
    """
    reversal = next(
        line for line in battery_output.splitlines() if line.startswith(f"  {REVERSAL} ")
    )
    base = next(line for line in battery_output.splitlines() if line.startswith("  base "))
    assert float(reversal.split()[3]) < float(base.split()[3])
    assert re.search(r"\[0\.\d{3}, [01]\.\d{3}\]", reversal), "the row carries no recall interval"
    assert f"{REVERSAL}: recall" in battery_output
    assert "The intervals do not overlap" in battery_output


def test_the_battery_says_what_it_skipped(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A section with nothing to measure is reported as skipped, never silently dropped."""
    path = tmp_path / "bare.parquet"
    _write_cache(path, segments={"train": 200, "test": 200}, variants=False)
    assert cli.main(["battery", str(path), "--top", "3"]) == 0
    out = capsys.readouterr().out
    assert "CALIBRATION — skipped: the cache holds no calibration-period rows." in out
    assert "NOVEL SUB-VARIANT — skipped: the held-out rows carry no labelled variants." in out


@pytest.mark.parametrize("command", ["battery", "frontier", "seed-variance"])
def test_every_m4_command_refuses_a_missing_cache(
    command: str, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    assert cli.main([command, str(tmp_path / "absent.parquet")]) == 2
    assert "holds no usable feature matrix" in capsys.readouterr().err


@pytest.mark.parametrize("command", ["battery", "seed-variance"])
def test_a_cache_without_test_rows_is_refused_not_scored(
    command: str, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Scoring nothing would print an AUC of nan, which reads like a result."""
    path = tmp_path / "train_only.parquet"
    _write_cache(path, segments={"train": 100})
    assert cli.main([command, str(path)]) == 2
    assert "0 test rows" in capsys.readouterr().err


@pytest.mark.req("D-05")
def test_seed_variance_reports_all_three_models_at_the_seeds_asked(
    cache: tuple[Path, dict[str, int]], capsys: pytest.CaptureFixture[str]
) -> None:
    """One row per seed, a mean and stdev per model, and a verdict against each baseline."""
    assert cli.main(["seed-variance", str(cache[0]), "--seeds", "1", "2", "3"]) == 0
    out = capsys.readouterr().out
    assert "at 3 fixed seeds (1, 2, 3)" in out
    seed_rows = [
        row
        for row in (line.split() for line in out.splitlines())
        if len(row) == 4 and row[0] in {"1", "2", "3"}
    ]
    assert [row[0] for row in seed_rows[:3]] == ["1", "2", "3"]
    aucs = [float(v) for row in seed_rows[:3] for v in row[1:]]
    assert all(0.5 < v <= 1.0 for v in aucs), aucs
    for name in ("xgboost", "lightgbm", "ensemble"):
        stdev = next(line for line in out.splitlines() if line.strip().startswith(name)).split()[-1]
        assert not math.isnan(float(stdev))
    assert out.count("ensemble stdev vs") == 2
    assert "REDUCED" in out, "each baseline comparison must state a verdict"


@pytest.mark.req("ML-GATE-12")
def test_the_frontier_walks_the_whole_grid_and_disowns_its_milliseconds(
    cache: tuple[Path, dict[str, int]], capsys: pytest.CaptureFixture[str]
) -> None:
    """Every grid point is reported, and the output itself says these are not gate numbers."""
    assert cli.main(["frontier", str(cache[0]), "--requests", "5", "--repeats", "1"]) == 0
    out = capsys.readouterr().out
    for trees, depth in cli.FRONTIER_GRID:
        assert f"{trees} trees, depth {depth}: done" in out
    assert "NOT GATE NUMBERS" in out
    assert "over 5 timed requests per configuration" in out
    table = [
        line.split() for line in out.splitlines() if line.strip()[:1].isdigit() and "+/-" in line
    ]
    assert [(int(r[0]), int(r[1])) for r in table] == list(cli.FRONTIER_GRID)


@pytest.mark.req("ML-GATE-11", "D-07")
def test_the_calibrator_is_fitted_on_the_calibration_period_and_never_on_test(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """D-07 sets calibration rows aside so the calibrator never sees a scored row.

    Fitting it on the test rows instead passed the whole suite until this test (M4 review, M4-6):
    the output is plausible either way, so only the rows it was given can tell.
    """
    scored: list[list[int]] = []
    platt_labels: list[list[bool]] = []

    def record_fit(self: cli.Battery, train: list[int], test: list[int]) -> list[float]:
        scored.append(list(test))
        return [0.5] * len(test)

    def record_platt(scores: list[float], labels: list[bool]) -> tuple[float, float]:
        platt_labels.append(list(labels))
        return (1.0, 0.0)

    monkeypatch.setattr(cli.Battery, "fit", record_fit)
    monkeypatch.setattr(battery_module, "fit_platt", record_platt)
    bench = cli.Battery(
        names=("a",),
        matrix=[[0.0]] * 6,
        labels=[False, True, True, False, False, True],
        country=[""] * 6,
        channel=[""] * 6,
        variant=[""] * 6,
        train=[0, 1],
        test=[2, 3],
        calibration=[4, 5],
        seed=1,
    )
    cli._battery_calibration(bench, [0.5, 0.5])

    assert scored == [[4, 5]], (
        "the calibrator was fitted on scores for rows outside the calibration period"
    )
    assert not set(scored[0]) & set(bench.test)
    assert platt_labels == [[False, True]], (
        "the calibrator saw labels that are not the calibration period's"
    )


GATE_SEGMENTS = {"train": 500, "validation": 250, "calibration": 250, "test": 500}


@pytest.fixture(scope="module")
def gate_cache(tmp_path_factory: pytest.TempPathFactory) -> Path:
    path = tmp_path_factory.mktemp("gate") / "features.parquet"
    _write_cache(path, segments=GATE_SEGMENTS, mcc=True)
    return path


@pytest.mark.req("TEST-14", "ML-GATE-01", "ML-GATE-10", "ML-GATE-11")
def test_the_gate_reports_every_metric_and_writes_its_artefacts(
    gate_cache: Path,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    private_access_log: Path,
) -> None:
    """All eleven M4 gate metrics, the floor, baselines, ablations, seeds, and E.5's outputs."""
    before = private_access_log.read_text().count('"gate"') if private_access_log.exists() else 0
    out = tmp_path / "gate"
    code = cli.main(
        [
            "gate",
            str(gate_cache),
            "--out",
            str(out),
            "--seeds",
            "1",
            "2",
            "--resamples",
            "30",
            "--ablate",
        ]
    )
    assert code == 0, "without --enforce a failing metric is reported, not an error (D.3)"
    printed = capsys.readouterr().out
    for spec in gate.SPECS:
        assert spec.id in printed
    for heading in ("floor: strongest single feature", "BASELINES", "ABLATIONS", "SEEDS"):
        assert heading in printed
    for baseline in ("status-quo rule engine", "logistic regression", "random forest"):
        assert baseline in printed
    assert "card-style features only" in printed

    metrics = json.loads((out / "metrics.json").read_text())
    assert [g["id"] for g in metrics["gate"]] == [s.id for s in gate.SPECS]
    for g in metrics["gate"]:
        assert isinstance(g["pass"], bool)
        assert len(g["ci95"]) == 2
    assert metrics["rows"]["test"]["rows"] == GATE_SEGMENTS["test"]
    assert len(metrics["seeds"]["ML-GATE-01"]) == 2
    for table in ("gate", "baselines", "ablations"):
        assert (out / "tables" / f"{table}.tex").read_text().startswith(r"\begin{tabular}")
    for figure in ("reliability", "roc"):
        assert (out / "figures" / f"{figure}.svg").read_text().startswith("<svg")
    assert private_access_log.read_text().count('"gate"') == before + 1, "the run was not logged"


def test_enforce_exits_one_when_a_gate_metric_misses_its_threshold(
    gate_cache: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The CI gate (E.5 item 9) fails on a missed threshold only when asked to."""
    impossible = (gate.Spec("ML-GATE-01", "AUC-ROC", 2.0, True), *gate.SPECS[1:])
    monkeypatch.setattr(gate, "SPECS", impossible)
    args = ["gate", str(gate_cache), "--out", str(tmp_path), "--seeds", "1", "--resamples", "5"]
    assert cli.main(args) == 0
    assert cli.main([*args, "--enforce"]) == 1


def test_the_gate_refuses_a_cache_without_all_four_row_sets(
    cache: tuple[Path, dict[str, int]], tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """A cache from before D-05 has no validation rows to stop on; refuse and say how to fix it."""
    assert cli.main(["gate", str(cache[0]), "--out", str(tmp_path)]) == 2
    err = capsys.readouterr().err
    assert "no validation rows" in err
    assert "--validation-rows" in err


def test_no_test_writes_to_the_committed_access_log() -> None:
    assert access.log_path() != access.DEFAULT
