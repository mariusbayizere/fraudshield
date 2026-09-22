"""Generate the FraudShield Grafana dashboards (SRS 8.2, build prompt E.10) as provisioned JSON.

The JSON files in ``dashboards/`` are generated from this module and committed, so Grafana loads
plain files (provisioning) and reviewers diff real dashboards. ``--check`` fails when a committed
file differs from what this module produces, so hand edits in Grafana cannot drift silently
(provisioned dashboards are also read-only in the UI: provisioning/dashboards/fraudshield.yml).

Every query reads a metric from build prompt Part E.10 or a recording rule in
``infrastructure/prometheus/rules``; ``infrastructure/checks/metric_catalogue.py`` enforces it.

Usage (repository root):
    uv run python infrastructure/grafana/generate_dashboards.py          # write
    uv run python infrastructure/grafana/generate_dashboards.py --check  # verify
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

OUT = Path(__file__).resolve().parent / "dashboards"
PROMETHEUS = {"type": "prometheus", "uid": "prometheus"}
SCHEMA_VERSION = 41
PANEL_WIDTH, PANEL_HEIGHT, GRID_WIDTH = 12, 8, 24
RATE = "$__rate_interval"
CHANNEL = 'channel=~"$channel"'


@dataclass
class Panel:
    title: str
    targets: list[tuple[str, str]]  # (PromQL, legend)
    unit: str = "short"
    kind: str = "timeseries"
    description: str = ""
    thresholds: list[tuple[float, str]] = field(default_factory=list)  # (value, colour)
    width: int = PANEL_WIDTH

    def render(self, panel_id: int, x: int, y: int) -> dict[str, Any]:
        steps: list[dict[str, Any]] = [{"color": "green", "value": None}]
        steps += [{"color": colour, "value": value} for value, colour in self.thresholds]
        custom = {"thresholdsStyle": {"mode": "line"}} if self.kind == "timeseries" else {}
        return {
            "id": panel_id,
            "type": self.kind,
            "title": self.title,
            "description": self.description,
            "datasource": PROMETHEUS,
            "gridPos": {"x": x, "y": y, "w": self.width, "h": PANEL_HEIGHT},
            "fieldConfig": {
                "defaults": {
                    "unit": self.unit,
                    "thresholds": {"mode": "absolute", "steps": steps},
                    "custom": custom,
                },
                "overrides": [],
            },
            "options": {"legend": {"displayMode": "table", "placement": "bottom"}}
            if self.kind == "timeseries"
            else {},
            "targets": [
                {
                    "refId": chr(ord("A") + index),
                    "datasource": PROMETHEUS,
                    "expr": expr,
                    "legendFormat": legend,
                    "range": True,
                }
                for index, (expr, legend) in enumerate(self.targets)
            ],
        }


@dataclass
class Dashboard:
    uid: str
    title: str
    description: str
    panels: list[Panel]
    channel_variable: bool = False

    def render(self) -> dict[str, Any]:
        rendered, x, y = [], 0, 0
        for panel_id, panel in enumerate(self.panels, start=1):
            if x + panel.width > GRID_WIDTH:
                x, y = 0, y + PANEL_HEIGHT
            rendered.append(panel.render(panel_id, x, y))
            x += panel.width
        variables = (
            [
                {
                    "name": "channel",
                    "label": "Channel",
                    "type": "query",
                    "datasource": PROMETHEUS,
                    "query": "label_values(fs_decisions_total, channel)",
                    "includeAll": True,
                    "multi": True,
                    "allValue": ".*",
                    "current": {"text": "All", "value": "$__all"},
                    "refresh": 2,
                }
            ]
            if self.channel_variable
            else []
        )
        return {
            "uid": self.uid,
            "title": self.title,
            "description": self.description,
            "tags": ["fraudshield"],
            "editable": False,
            "graphTooltip": 1,
            "refresh": "30s",
            "time": {"from": "now-6h", "to": "now"},
            "timezone": "utc",
            "schemaVersion": SCHEMA_VERSION,
            "version": 1,
            "templating": {"list": variables},
            "annotations": {"list": []},
            "links": [],
            "panels": rendered,
        }


def quantile(q: str, metric: str, by: str = "") -> str:
    group = f"le, {by}" if by else "le"
    return f"histogram_quantile({q}, sum by ({group}) (rate({metric}_bucket[{RATE}])))"


DECISION_PATH = Dashboard(
    uid="fs-decision-path",
    title="FraudShield / Decision path",
    description="Synchronous ingest-to-decision path (C.2): traffic, latency by stage, fallback.",
    channel_variable=True,
    panels=[
        Panel(
            "Transactions per second",
            [("fraudshield:ingest_requests:rate5m", "all"),
             ("fraudshield:ingest_requests:rate5m offset 1w", "same time last week")],
            unit="reqps",
            description="SRS 8.2 TPS. FraudShieldTransactionRateDrop fires below 80% of last week.",
        ),
        Panel(
            "Ingest responses by status",
            [(f"sum by (status) (rate(fs_ingest_requests_total{{{CHANNEL}}}[{RATE}]))",
              "{{status}}")],
            unit="reqps",
        ),
        Panel(
            "Decision latency (server side)",
            [("fraudshield:decision_latency_seconds:p50_5m", "p50"),
             ("fraudshield:decision_latency_seconds:p95_5m", "p95"),
             ("fraudshield:decision_latency_seconds:p99_5m", "p99")],
            unit="s",
            thresholds=[(0.040, "orange"), (0.080, "red")],
            description="Target p95 <= 40 ms flagged; alert and canary rollback at p99 > 80 ms.",
        ),
        Panel(
            "Scoring latency p95 by stage",
            [(quantile("0.95", "fs_scoring_latency_seconds", "stage"), "{{stage}}")],
            unit="s",
        ),
        Panel(
            "Feature fetch latency p95 by source",
            [(quantile("0.95", "fs_feature_latency_seconds", "source"), "{{source}}")],
            unit="s",
        ),
        Panel(
            "Decisions by tier and outcome",
            [(f"sum by (tier, decision) (rate(fs_decisions_total{{{CHANNEL}}}[{RATE}]))",
              "{{tier}} {{decision}}")],
            unit="reqps",
        ),
        Panel(
            "Rule-based fallback share",
            [("fraudshield:fallback_decisions:ratio_5m", "fallback")],
            unit="percentunit",
            thresholds=[(0.0001, "red")],
            description="C.4: share of decisions made while the scorer circuit was open.",
        ),
        Panel(
            "Redis hit ratio and DB pool in use",
            [("avg(fs_redis_hit_ratio)", "redis hit ratio"),
             ("sum(fs_db_pool_in_use)", "db connections in use")],
            description="A falling hit ratio means velocity features come from the DB fallback.",
        ),
    ],
)  # fmt: skip

KAFKA_SPOOL = Dashboard(
    uid="fs-kafka-spool",
    title="FraudShield / Kafka and spool",
    description="Asynchronous path: consumer lag per topic and group, local spool (D-15).",
    panels=[
        Panel(
            "Consumer lag by topic and group",
            [("sum by (topic, group) (fs_kafka_consumer_lag)", "{{topic}} / {{group}}")],
            thresholds=[(5000, "red")],
            description="SRS 8.2: alert above 5,000 messages.",
            width=GRID_WIDTH,
        ),
        Panel(
            "Spool depth by pod",
            [("sum by (pod) (fs_spool_depth)", "{{pod}}")],
            description="Decisions not yet acknowledged by Kafka. Must drain to zero.",
        ),
        Panel(
            "Scrape health",
            [('up{job=~"fraudshield-.*"}', "{{job}} {{pod}}")],
            description="1 = Prometheus can scrape the pod.",
        ),
    ],
)  # fmt: skip

RISK_OVERVIEW = Dashboard(
    uid="fs-risk-overview",
    title="FraudShield / Risk overview",
    description="Risk-officer view of SRS 8.2 fraud signals and the D-10 timeout-release rate.",
    channel_variable=True,
    panels=[
        Panel(
            "Auto-blocks per second vs 3x 7-day baseline",
            [("fraudshield:auto_block:rate15m", "auto-blocks/s (15 min)"),
             ("3 * fraudshield:auto_block:rate15m:avg_7d", "alert level (3x baseline)")],
            unit="reqps",
        ),
        Panel(
            "HIGH-tier share by channel with 3-sigma band",
            [(f"fraudshield:high_tier:ratio_1h{{{CHANNEL}}}", "{{channel}}"),
             (f"fraudshield:high_tier:ratio_1h:avg_30d{{{CHANNEL}}} + 3 * "
              f"fraudshield:high_tier:ratio_1h:stddev_30d{{{CHANNEL}}}", "{{channel}} +3 sigma"),
             (f"fraudshield:high_tier:ratio_1h:avg_30d{{{CHANNEL}}} - 3 * "
              f"fraudshield:high_tier:ratio_1h:stddev_30d{{{CHANNEL}}}", "{{channel}} -3 sigma")],
            unit="percentunit",
            description="Live fraud rate is the model-flagged share; labels arrive later.",
        ),
        Panel(
            "MEDIUM holds released by timeout (D-10)",
            [(f"fraudshield:timeout_release:ratio_15m{{{CHANNEL}}}", "{{channel}}"),
             ("fraudshield:config:timeout_release_ratio_max", "alert level")],
            unit="percentunit",
        ),
        Panel(
            "False positive rate (labelled)",
            [("max(fs_false_positive_rate)", "false positive rate")],
            unit="percentunit",
            thresholds=[(0.05, "red")],
            description="SRS 8.2: ticket above 5%.",
        ),
        Panel(
            "Decisions per second by channel",
            [(f"sum by (channel) (rate(fs_decisions_total{{{CHANNEL}}}[{RATE}]))", "{{channel}}")],
            unit="reqps",
            width=GRID_WIDTH,
        ),
    ],
)  # fmt: skip

ANALYST_OPERATIONS = Dashboard(
    uid="fs-analyst-operations",
    title="FraudShield / Analyst operations",
    description="Alert queues and analyst review time (SRS 8.2 review time distribution).",
    panels=[
        Panel(
            "Alert queue depth by tier",
            [("sum by (tier) (fs_alert_queue_depth)", "{{tier}}")],
        ),
        Panel(
            "Review duration p50 / p90",
            [(quantile("0.5", "fs_review_duration_seconds"), "p50"),
             (quantile("0.9", "fs_review_duration_seconds"), "p90")],
            unit="s",
        ),
        Panel(
            "Review duration distribution",
            [(f"sum by (le) (increase(fs_review_duration_seconds_bucket[{RATE}]))", "{{le}}")],
            kind="heatmap",
        ),
        Panel(
            "Timeout releases per minute by channel",
            [(f"60 * sum by (channel) (rate(fs_alert_timeout_release_total[{RATE}]))",
              "{{channel}}")],
        ),
    ],
)  # fmt: skip

MODEL_HEALTH = Dashboard(
    uid="fs-model-health",
    title="FraudShield / Model health",
    description="Model in use per alias and feature drift (SRS 8.2 ML drift monitoring).",
    panels=[
        Panel(
            "Model versions by alias",
            [("fs_model_version_info", "{{alias}} {{version}}")],
            kind="table",
            description="One series per MLflow alias (@production, @shadow, @previous_production).",
        ),
        Panel(
            "Live AUC-ROC (production) and alert level",
            [('max(fs_model_auc_roc{alias="production"})', "live AUC-ROC"),
             ("fraudshield:model_auc_roc:production_covered", "counted (coverage >= 30%)"),
             ("fraudshield:model_auc_roc:baseline_30d - 0.03", "alert level (baseline - 0.03)")],
            description="ADR 0091: only hours with label coverage of at least 30% count.",
        ),
        Panel(
            "Label coverage by alias",
            [("max by (alias) (fs_model_label_coverage)", "{{alias}}")],
            unit="percentunit",
            thresholds=[(0.30, "green")],
            description="Below 30% (D-11) live AUC is not judged and the AUC alert is silent.",
        ),
        Panel(
            "Top 10 features by PSI",
            [("topk(10, max by (feature) (fs_feature_psi))", "{{feature}}")],
            thresholds=[(0.2, "red")],
            description="SRS 8.2: PSI above 0.2 triggers model review.",
        ),
    ],
)  # fmt: skip

DASHBOARDS = (DECISION_PATH, KAFKA_SPOOL, RISK_OVERVIEW, ANALYST_OPERATIONS, MODEL_HEALTH)


def rendered_files() -> dict[Path, str]:
    return {
        OUT / f"{board.uid}.json": json.dumps(board.render(), indent=2, sort_keys=True) + "\n"
        for board in DASHBOARDS
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="fail if committed files differ")
    args = parser.parse_args(argv)
    files = rendered_files()
    if args.check:
        stale = sorted(
            path.name
            for path, text in files.items()
            if not path.exists() or path.read_text() != text
        )
        extra = sorted(p.name for p in OUT.glob("*.json") if p not in files)
        for name in stale:
            print(f"dashboards/{name} is out of date; run generate_dashboards.py", file=sys.stderr)
        for name in extra:
            print(f"dashboards/{name} is not generated by generate_dashboards.py", file=sys.stderr)
        print(f"dashboards: {len(files)} checked")
        return 1 if stale or extra else 0
    OUT.mkdir(exist_ok=True)
    for path, text in files.items():
        path.write_text(text)
    print(f"dashboards: wrote {len(files)} files to {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
