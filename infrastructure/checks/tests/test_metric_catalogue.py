"""Tests for the E.10 metric-catalogue guard (infrastructure/checks/metric_catalogue.py)."""

from __future__ import annotations

import json
from pathlib import Path

import metric_catalogue as mc
import pytest

CATALOGUE = mc.Catalogue(
    metrics={
        "fs_decisions_total": frozenset({"tier", "decision", "channel", "fallback"}),
        "fs_decision_latency_seconds": frozenset(),
        "fs_spool_depth": frozenset(),
        "fs_kafka_consumer_lag": frozenset({"topic", "group"}),
    },
    recording_rules=frozenset({"fraudshield:decision_latency_seconds:p99_5m"}),
    label_values={
        ("fs_decisions_total", "channel"): frozenset({"USSD", "CARD"}),
        ("fs_decisions_total", "tier"): frozenset({"HIGH", "MEDIUM", "LOW"}),
    },
)


def names(expression: str) -> list[str]:
    return [selector.name for selector in mc.selectors(expression)]


class TestE10Source:
    def test_parses_all_seventeen_metrics_with_their_labels(self) -> None:
        metrics = mc.e10_metrics(mc.BUILD_PROMPT.read_text())
        assert len(metrics) == 17  # 15 in the build prompt plus two added by ADR 0091
        assert metrics["fs_model_version_info"] == {"alias", "version"}
        assert metrics["fs_model_label_coverage"] == {"alias"}
        assert metrics["fs_decisions_total"] == {"tier", "decision", "channel", "fallback"}
        assert metrics["fs_kafka_consumer_lag"] == {"topic", "group"}
        assert metrics["fs_decision_latency_seconds"] == frozenset()

    def test_missing_section_is_an_error_not_an_empty_catalogue(self) -> None:
        with pytest.raises(ValueError, match=r"E\.10"):
            mc.e10_metrics("# no such section\n### E.11 Next\n")

    def test_contract_label_values_come_from_the_contracts(self) -> None:
        catalogue = mc.load_catalogue()
        assert "MOBILE_MONEY" in catalogue.label_values[("fs_decisions_total", "channel")]
        assert catalogue.label_values[("fs_alert_queue_depth", "tier")] == {
            "HIGH",
            "MEDIUM",
            "ANOMALY",
        }
        topics = catalogue.label_values[("fs_kafka_consumer_lag", "topic")]
        assert {"fs.decisions.final", "fs.decisions.final.dlq"} <= topics


class TestSelectorExtraction:
    def test_functions_aggregations_and_grouping_labels_are_not_metrics(self) -> None:
        expression = (
            "histogram_quantile(0.99, sum by (le, channel) "
            "(rate(fs_decision_latency_seconds_bucket[5m])))"
        )
        assert names(expression) == ["fs_decision_latency_seconds_bucket"]

    def test_binary_operators_offsets_and_subqueries(self) -> None:
        expression = (
            "sum(rate(fs_decisions_total[10m])) < 0.8 * sum(rate(fs_decisions_total[10m] "
            "offset 1w)) and on() max_over_time(fs_spool_depth[1h:1m]) > 0"
        )
        assert names(expression) == ["fs_decisions_total", "fs_decisions_total", "fs_spool_depth"]

    def test_matchers_are_captured_with_operator_and_value(self) -> None:
        (selector,) = mc.selectors('fs_decisions_total{tier="HIGH", channel=~"USSD|CARD"}')
        assert selector.matchers == (("tier", "=", "HIGH"), ("channel", "=~", "USSD|CARD"))

    def test_name_matcher_selector(self) -> None:
        (selector,) = mc.selectors('{__name__="fs_spool_depth", pod="api-0"}')
        assert selector == mc.Selector("fs_spool_depth", (("pod", "=", "api-0"),))

    def test_grafana_variables_and_label_values_queries(self) -> None:
        assert names('sum(rate(fs_decisions_total{channel=~"$channel"}[$__rate_interval]))') == [
            "fs_decisions_total"
        ]
        assert names("label_values(fs_decisions_total, channel)") == ["fs_decisions_total"]
        assert names("label_values(channel)") == []

    def test_label_names_in_without_and_ignoring_are_skipped(self) -> None:
        expression = "sum without (pod) (fs_spool_depth) / ignoring (job) group_left fs_spool_depth"
        assert names(expression) == ["fs_spool_depth", "fs_spool_depth"]


class TestProblems:
    @pytest.mark.parametrize(
        "expression",
        [
            'sum(rate(fs_decisions_total{tier="HIGH", channel="USSD"}[5m]))',
            'fs_decision_latency_seconds_bucket{le="0.08", namespace="fraudshield"}',
            "fraudshield:decision_latency_seconds:p99_5m > 0.08",
            'probe_success{job="blackbox"} == 0',
            'fs_decisions_total{channel=~"$channel"}',
            'fs_decisions_total{tier=~"HIGH|MEDIUM"}',
            'fs_decisions_total{tier=~"H.*"}',
        ],
    )
    def test_known_queries_pass(self, expression: str) -> None:
        assert mc.problems(expression, CATALOGUE) == []

    @pytest.mark.parametrize(
        ("expression", "fragment"),
        [
            ("rate(fs_decision_total[5m])", "not defined in build prompt Part E.10"),
            ('fs_decisions_total{risk_tier="HIGH"}', "label 'risk_tier'"),
            ('fs_decisions_total{channel="MPESA"}', "'MPESA' is not a contract value"),
            ('fs_decisions_total{tier=~"HIGH|CRITICAL"}', "'CRITICAL'"),
            ('fs_spool_depth{le="1"}', "label 'le'"),
            ("fs_spool_depth_bucket", "not defined"),
            ("fraudshield:tps:rate5m", "recording rule"),
            ("node_cpu_seconds_total", "neither in E.10 nor allowlisted"),
            ('{job="api"}', "without a literal metric name"),
            ("sum(fs_spool_depth", "unparseable"),
        ],
    )
    def test_drift_is_reported(self, expression: str, fragment: str) -> None:
        found = mc.problems(expression, CATALOGUE)
        assert any(fragment in problem for problem in found), found


class TestFileDiscovery:
    def test_finds_expressions_in_rules_tests_rollouts_and_dashboards(self, tmp_path: Path) -> None:
        rules = tmp_path / "rules.yml"
        rules.write_text(
            "groups:\n- name: g\n  rules:\n  - alert: A\n    expr: fs_spool_depth > 0\n"
            "---\nspec:\n  metrics:\n  - provider:\n      prometheus:\n        query: up\n"
        )
        dashboard = tmp_path / "board.json"
        dashboard.write_text(json.dumps({"panels": [{"targets": [{"expr": "fs_x"}]}]}))
        assert list(mc.expressions(rules)) == ["fs_spool_depth > 0", "up"]
        assert list(mc.expressions(dashboard)) == ["fs_x"]


def test_repository_queries_all_resolve(capsys: pytest.CaptureFixture[str]) -> None:
    """The check itself, over the committed rules, dashboards and analyses."""
    assert mc.main() == 0, capsys.readouterr().err
