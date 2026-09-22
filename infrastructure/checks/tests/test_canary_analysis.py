"""The canary analysis queries (infrastructure/argo-rollouts/analysis-template.yaml), evaluated.

The queries are read from the AnalysisTemplate itself, their Argo arguments substituted, and run by
the pinned promtool against synthetic series in which the stable version is failing badly. That
proves the analysis measures the canary pods only, and that the success conditions fail closed.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[3]
TEMPLATE = ROOT / "infrastructure/argo-rollouts/analysis-template.yaml"
PROMTOOL = ROOT / "infrastructure/bin/promtool"
NAMESPACE, CANARY, STABLE = "fraudshield", "canary1", "stable1"


def metrics() -> dict[str, dict[str, Any]]:
    template = yaml.safe_load(TEMPLATE.read_text())
    return {metric["name"]: metric for metric in template["spec"]["metrics"]}


def query(name: str) -> str:
    text = metrics()[name]["provider"]["prometheus"]["query"]
    text = text.replace("{{args.namespace}}", NAMESPACE).replace("{{args.canary-hash}}", CANARY)
    return " ".join(text.split())


def threshold(name: str) -> float:
    """The limit in 'len(result) == 1 && result[0] <= X'; the test asserts that exact shape."""
    condition = metrics()[name]["successCondition"]
    match = re.fullmatch(r"len\(result\) == 1 && result\[0\] <= ([0-9.]+)", condition)
    assert match, f"unexpected successCondition shape: {condition}"
    return float(match.group(1))


def series(metric: str, pod_hash: str, per_minute: float, **labels: str) -> dict[str, str]:
    all_labels = {"namespace": NAMESPACE, "rollouts_pod_template_hash": pod_hash, **labels}
    selector = ", ".join(f'{key}="{value}"' for key, value in all_labels.items())
    return {"series": f"{metric}{{{selector}}}", "values": f"0+{per_minute}x20"}


def evaluate(expression: str, inputs: list[dict[str, str]], tmp_path: Path) -> list[float]:
    """Instant value(s) of an expression at 15 minutes, via promtool."""
    test = {
        "evaluation_interval": "1m",
        "tests": [
            {
                "interval": "1m",
                "input_series": inputs,
                "promql_expr_test": [{"expr": expression, "eval_time": "15m", "exp_samples": []}],
            }
        ],
    }
    path = tmp_path / "canary_test.yml"
    path.write_text(yaml.safe_dump(test))
    result = subprocess.run(  # noqa: S603 - pinned tool, generated test file
        [str(PROMTOOL), "test", "rules", str(path)], capture_output=True, text=True, check=False
    )
    # Expecting no samples makes promtool print what it got; parse those values.
    return [float(value) for value in re.findall(r"\} (\S+)\n", result.stdout + result.stderr)]


def requests(pod_hash: str, per_minute: float, errors_per_minute: float) -> list[dict[str, str]]:
    found = [series("fs_ingest_requests_total", pod_hash, per_minute, status="200", channel="USSD")]
    if errors_per_minute:
        found.append(
            series(
                "fs_ingest_requests_total",
                pod_hash,
                errors_per_minute,
                status="503",
                channel="USSD",
            )
        )
    return found


@pytest.mark.parametrize(
    ("canary_errors", "passes"),
    [(0.0, True), (4.0, True), (6.0, False)],
    ids=["no 5xx series", "0.4% errors", "0.6% errors"],
)
def test_error_rate_judges_the_canary_only(
    canary_errors: float, passes: bool, tmp_path: Path
) -> None:
    inputs = requests(CANARY, 1000 - canary_errors, canary_errors)
    inputs += requests(STABLE, 500, 500)  # a broken stable version must not fail the canary
    values = evaluate(query("error-rate"), inputs, tmp_path)
    assert len(values) == 1
    assert values[0] == pytest.approx(canary_errors / 1000)
    assert (values[0] <= threshold("error-rate")) is passes


def test_error_rate_fails_closed_without_canary_traffic(tmp_path: Path) -> None:
    values = evaluate(query("error-rate"), requests(STABLE, 1000, 0), tmp_path)
    assert values == []  # len(result) == 1 is false, so the measurement fails


def latency(pod_hash: str, below_80ms_per_minute: float) -> list[dict[str, str]]:
    metric = "fs_decision_latency_seconds_bucket"
    return [
        series(metric, pod_hash, 900, le="0.04"),
        series(metric, pod_hash, below_80ms_per_minute, le="0.08"),
        series(metric, pod_hash, 1000, le="0.1"),
        series(metric, pod_hash, 1000, le="+Inf"),
    ]


@pytest.mark.parametrize(
    ("below_80ms", "passes"),
    [(995, True), (985, False)],
    ids=["p99 77.9 ms", "p99 86.7 ms"],
)
def test_p99_judges_the_canary_only(below_80ms: float, passes: bool, tmp_path: Path) -> None:
    inputs = latency(CANARY, below_80ms) + latency(STABLE, 900)
    values = evaluate(query("p99-decision-latency"), inputs, tmp_path)
    assert len(values) == 1
    assert (values[0] <= threshold("p99-decision-latency")) is passes


def test_thresholds_are_the_srs_values() -> None:
    assert threshold("error-rate") == 0.005
    assert threshold("p99-decision-latency") == 0.080
