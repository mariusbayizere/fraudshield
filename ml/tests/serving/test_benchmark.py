"""The benchmark harness itself, at toy scale: it must measure what it says it measures."""

from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving import benchmark
from fraudshield_ml.serving.scorer import Scorer

PACKS = {
    "RW": {
        "alpha2": "RW",
        "blocs": ["EAC"],
        "continent": "AF",
        "currency": "RWF",
        "currency_minor_units": 0,
        "round_denominations": [500, 1000],
        "utc_offset_hours": 2,
    },
}


def test_percentile_is_nearest_rank() -> None:
    values = [float(v) for v in range(1, 101)]
    assert benchmark.percentile(values, 0.5) == 50.0
    assert benchmark.percentile(values, 0.99) == 99.0
    assert benchmark.percentile([7.0], 0.95) == 7.0


@pytest.mark.req("TEST-10", "FR-02-07")
def test_the_serve_benchmark_drives_real_worker_processes(
    bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    packs = tmp_path / "packs.json"
    packs.write_text(json.dumps(PACKS))
    out = tmp_path / "serve.json"
    assert (
        benchmark.main(
            [
                "serve",
                "--bundle",
                str(bundle_dirs[0]),
                "--packs",
                str(packs),
                "--synthetic",
                "--requests",
                "120",
                "--warmup",
                "20",
                "--concurrency",
                "16",
                "--workers",
                "1",
                "--out",
                str(out),
            ]
        )
        == 0
    )
    report = json.loads(out.read_text())
    serve = report["serve"]
    assert serve["requests"] == 120
    assert serve["errors"] == 0
    assert serve["client_ms"]["p50"] <= serve["client_ms"]["p99"]
    assert 0.0 <= serve["shap_share"] <= 1.0
    assert "NOT GATE EVIDENCE" in report["machine"]["caveat"]
    assert report["machine"]["commit"]


@pytest.mark.req("TEST-10")
def test_the_memory_benchmark_scores_without_reloading(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    scorer = Scorer(bundles[0], kit.reference)
    requests = [(kit.request(i), kit.read(burst=i % 9 == 0)) for i in range(50)]
    report = benchmark.run_memory(scorer, requests, scorings=400, warmup=50)
    assert report.model_loaded_once
    assert report.scorings == 400
    assert report.growth_mb < benchmark.MEMORY_GATE_MB
    assert report.passed
    assert benchmark.rss_mb() > 0
