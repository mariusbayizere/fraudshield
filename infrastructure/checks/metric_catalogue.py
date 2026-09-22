"""Fail when an alert, recording rule, dashboard or canary analysis names an unknown metric.

The services that emit FraudShield's metrics are built in later milestones, while the alerts and
dashboards that read them exist now (M9). Without a guard the two drift silently: a rule that
queries a misspelt metric or label is valid PromQL, returns no data and never fires. This check
ties every query in ``infrastructure/`` to the one catalogue the build prompt defines, Part E.10.

Rules enforced for every PromQL expression found under the scanned directories:

* A metric whose name starts with ``fs_`` must be one of the E.10 metrics, or the ``_bucket``,
  ``_sum`` or ``_count`` series of an E.10 metric measured in ``_seconds`` (histogram series).
* Its label matchers may use only the labels E.10 gives that metric, the target labels Prometheus
  attaches at scrape time (``SCRAPE_LABELS``) and ``le`` on ``_bucket`` series.
* Literal label values for labels whose values a contract defines (channel, tier, decision,
  Kafka topic and consumer group) must be values of that contract.
* A name containing ``:`` is a recording rule and must be defined by a ``record:`` under
  ``infrastructure/prometheus/rules``.
* Any other name must be an exporter metric listed in ``EXTERNAL_METRICS`` with its source.
  E.10 lists only what FraudShield itself emits; probes and scrape health come from Prometheus
  and the blackbox exporter.

Usage: ``uv run python infrastructure/checks/metric_catalogue.py`` from the repository root.
Exit status 0 when every query is known, 1 otherwise.
"""

from __future__ import annotations

import json
import re
import sys
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
BUILD_PROMPT = ROOT / "docs/prompts/FraudShield_Master_Build_Prompt.md"
OPENAPI = ROOT / "contracts/openapi/fraudshield-api.yaml"
KAFKA_TOPICS = ROOT / "contracts/kafka/topics.yaml"
RULES_DIR = ROOT / "infrastructure/prometheus/rules"
SCANNED_DIRS = (
    ROOT / "infrastructure/prometheus",
    ROOT / "infrastructure/grafana",
    ROOT / "infrastructure/argo-rollouts",
)
# Keys whose string values are PromQL: rule and unit-test expressions, Argo Rollouts analysis
# queries, Grafana panel targets and promtool input series selectors.
PROMQL_KEYS = frozenset({"expr", "query", "series"})
HISTOGRAM_SUFFIXES = ("_bucket", "_sum", "_count")

# Target labels attached by the scrape configuration (infrastructure/prometheus/prometheus.yml)
# and the Argo Rollouts pod-template hash used to separate canary from stable pods.
SCRAPE_LABELS = frozenset(
    {"job", "instance", "namespace", "pod", "container", "app", "rollouts_pod_template_hash"}
)
EXTERNAL_METRICS = {
    "up": "Prometheus scrape health (every target)",
    "probe_success": "blackbox_exporter (OPS-OBS-05 uptime probes)",
    "ALERTS": "Prometheus built-in series of pending and firing alerts",
}
# E.10 metric label -> (OpenAPI schema whose enum lists its values).
OPENAPI_ENUM_LABELS = {
    ("fs_ingest_requests_total", "channel"): "Channel",
    ("fs_decisions_total", "channel"): "Channel",
    ("fs_decisions_total", "tier"): "RiskTier",
    ("fs_decisions_total", "decision"): "Decision",
    ("fs_alert_queue_depth", "tier"): "AlertTier",
    ("fs_alert_timeout_release_total", "channel"): "Channel",
}
BOOLEAN_LABELS = {("fs_decisions_total", "fallback")}
KEYWORDS = frozenset(
    {
        "by", "without", "on", "ignoring", "group_left", "group_right", "bool", "and", "or",
        "unless", "offset", "atan2", "sum", "min", "max", "avg", "group", "stddev", "stdvar",
        "count", "count_values", "bottomk", "topk", "quantile", "limitk", "limit_ratio", "inf",
        "nan", "start", "end",
    }
)  # fmt: skip
GROUPING_KEYWORDS = frozenset({"by", "without", "on", "ignoring", "group_left", "group_right"})
TOKEN = re.compile(
    r"""
    (?P<string>"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`[^`]*`)
    |(?P<range>\[[^\]]*\])
    |(?P<var>\$\{[^}]*\}|\$\w+)
    |(?P<number>(?<![\w:])[-+]?(?:0x[0-9a-fA-F]+|\d[\w.]*))
    |(?P<ident>[a-zA-Z_:][\w:]*)
    |(?P<punct>[{}(),])
    |(?P<op>=~|!~|!=|==|<=|>=|[-+*/%^<>=@])
    |(?P<space>\s+)
    """,
    re.VERBOSE,
)
TEMPLATE_QUERY = re.compile(
    r"^\s*label_values\(\s*(?:(?P<selector>.+),\s*)?(?P<label>\w+)\s*\)\s*$"
)
SIMPLE_ALTERNATION = re.compile(r"^[A-Za-z0-9_.-]+(?:\|[A-Za-z0-9_.-]+)*$")


@dataclass(frozen=True)
class Selector:
    """One metric selector found in an expression: name plus (label, operator, value) matchers."""

    name: str
    matchers: tuple[tuple[str, str, str], ...] = ()


@dataclass
class Catalogue:
    """Everything a query may legitimately name."""

    metrics: dict[str, frozenset[str]]
    recording_rules: frozenset[str]
    label_values: dict[tuple[str, str], frozenset[str]] = field(default_factory=dict)


# --- catalogue sources ------------------------------------------------------------------------


def e10_metrics(prompt_text: str) -> dict[str, frozenset[str]]:
    """Metric name -> label names, parsed from the backticked metrics in build prompt E.10."""
    section = re.search(r"^### E\.10 Observability\n(.*?)(?=^### )", prompt_text, re.M | re.S)
    if section is None:
        raise ValueError("build prompt has no '### E.10 Observability' section")
    found: dict[str, frozenset[str]] = {}
    for name, labels in re.findall(r"`(fs_[a-z0-9_]+)(?:\{([a-z_,\s]*)\})?`", section.group(1)):
        found[name] = frozenset(label.strip() for label in labels.split(",") if label.strip())
    if not found:
        raise ValueError("E.10 lists no fs_ metrics; the parser or the section changed")
    return found


def openapi_enum(openapi: dict[str, Any], schema: str) -> frozenset[str]:
    values = openapi["components"]["schemas"][schema]["enum"]
    return frozenset(str(value) for value in values)


def kafka_values(topics_doc: dict[str, Any]) -> tuple[frozenset[str], frozenset[str]]:
    """(topic names including DLQs, consumer group names) from the Kafka topic catalogue."""
    suffix = topics_doc["defaults"]["dlq_suffix"]
    topics = [topic["name"] for topic in topics_doc["topics"]]
    groups = {group for topic in topics_doc["topics"] for group in topic.get("consumers", [])}
    return frozenset(topics + [name + suffix for name in topics]), frozenset(groups)


def recording_rule_names(rules_dir: Path) -> frozenset[str]:
    names: set[str] = set()
    for path in sorted(rules_dir.glob("*.y*ml")):
        document = yaml.safe_load(path.read_text()) or {}
        for group in document.get("groups", []):
            names.update(rule["record"] for rule in group.get("rules", []) if "record" in rule)
    return frozenset(names)


def load_catalogue() -> Catalogue:
    openapi = yaml.safe_load(OPENAPI.read_text())
    topics, groups = kafka_values(yaml.safe_load(KAFKA_TOPICS.read_text()))
    label_values = {
        key: openapi_enum(openapi, schema) for key, schema in OPENAPI_ENUM_LABELS.items()
    }
    label_values |= {key: frozenset({"true", "false"}) for key in BOOLEAN_LABELS}
    label_values[("fs_kafka_consumer_lag", "topic")] = topics
    label_values[("fs_kafka_consumer_lag", "group")] = groups
    return Catalogue(
        metrics=e10_metrics(BUILD_PROMPT.read_text()),
        recording_rules=recording_rule_names(RULES_DIR),
        label_values=label_values,
    )


# --- PromQL scanning --------------------------------------------------------------------------


def tokens(expression: str) -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    position = 0
    while position < len(expression):
        match = TOKEN.match(expression, position)
        if match is None:
            raise ValueError(f"cannot tokenise at {expression[position : position + 20]!r}")
        kind = match.lastgroup or ""
        if kind != "space":
            found.append((kind, match.group()))
        position = match.end()
    _check_balanced(found)
    return found


def _check_balanced(stream: list[tuple[str, str]]) -> None:
    """promtool parses rule files, but dashboards and Argo queries only pass through here."""
    closing = {")": "(", "}": "{"}
    stack: list[str] = []
    for _, text in stream:
        if text in ("(", "{"):
            stack.append(text)
        elif text in closing and (not stack or stack.pop() != closing[text]):
            raise ValueError(f"unbalanced '{text}'")
    if stack:
        raise ValueError(f"unclosed '{stack[-1]}'")


def _unquote(literal: str) -> str:
    if literal.startswith("`"):
        return literal[1:-1]
    return bytes(literal[1:-1], "utf-8").decode("unicode_escape")


def _matchers(stream: list[tuple[str, str]], start: int) -> tuple[list[tuple[str, str, str]], int]:
    """Parse ``{label op "value", ...}`` beginning at the ``{`` at ``start``; return end index."""
    matchers: list[tuple[str, str, str]] = []
    index = start + 1
    while stream[index][1] != "}":
        if stream[index][1] == ",":
            index += 1
            continue
        label, operator, value = stream[index], stream[index + 1], stream[index + 2]
        if label[0] not in ("ident", "string") or value[0] not in ("string", "var"):
            raise ValueError(f"malformed label matcher near {label[1]!r}")
        matchers.append((label[1].strip("\"'`"), operator[1], _unquote(value[1])))
        index += 3
    return matchers, index + 1


def _skip_parenthesised(stream: list[tuple[str, str]], start: int) -> int:
    depth, index = 0, start
    while True:
        depth += {"(": 1, ")": -1}.get(stream[index][1], 0)
        index += 1
        if depth == 0:
            return index


def selectors(expression: str) -> Iterator[Selector]:
    """Every metric selector in a PromQL expression or a Grafana ``label_values`` query."""
    template = TEMPLATE_QUERY.match(expression)
    if template is not None:
        if template.group("selector"):
            yield from selectors(template.group("selector"))
        return
    stream = [*tokens(expression), ("end", "")]
    index = 0
    while index < len(stream) - 1:
        kind, text = stream[index]
        following = stream[index + 1][1]
        if kind == "ident" and text in GROUPING_KEYWORDS and following == "(":
            index = _skip_parenthesised(stream, index + 1)
        elif kind == "ident" and (text in KEYWORDS or following == "("):
            index += 1
        elif kind == "ident":
            matchers: list[tuple[str, str, str]] = []
            index += 1
            if following == "{":
                matchers, index = _matchers(stream, index)
            yield Selector(text, tuple(matchers))
        elif text == "{":
            matchers, index = _matchers(stream, index)
            names = [value for label, op, value in matchers if label == "__name__" and op == "="]
            rest = tuple(m for m in matchers if m[0] != "__name__")
            yield Selector(names[0] if names else "", rest)
        else:
            index += 1


def problems(expression: str, catalogue: Catalogue) -> list[str]:
    try:
        found = list(selectors(expression))
    except (ValueError, IndexError) as error:
        return [f"unparseable PromQL ({error})"]
    return [problem for selector in found for problem in _selector_problems(selector, catalogue)]


def _base_metric(name: str, catalogue: Catalogue) -> tuple[str, bool] | None:
    """(E.10 metric, is_bucket) for an E.10 metric or one of its histogram series."""
    if name in catalogue.metrics:
        return name, False
    for suffix in HISTOGRAM_SUFFIXES:
        base = name.removesuffix(suffix)
        if base != name and base.endswith("_seconds") and base in catalogue.metrics:
            return base, suffix == "_bucket"
    return None


def _selector_problems(selector: Selector, catalogue: Catalogue) -> list[str]:
    name = selector.name
    if not name:
        return ["selector without a literal metric name"]
    if ":" in name:
        known = name in catalogue.recording_rules
        return [] if known else [f"recording rule '{name}' is not defined in {RULES_DIR.name}/"]
    if not name.startswith("fs_"):
        return [] if name in EXTERNAL_METRICS else [f"'{name}' is neither in E.10 nor allowlisted"]
    resolved = _base_metric(name, catalogue)
    if resolved is None:
        return [f"metric '{name}' is not defined in build prompt Part E.10"]
    base, is_bucket = resolved
    allowed = catalogue.metrics[base] | SCRAPE_LABELS | ({"le"} if is_bucket else set())
    found: list[str] = []
    for label, operator, value in selector.matchers:
        if label not in allowed:
            found.append(f"label '{label}' is not an E.10 label of '{base}'")
            continue
        found.extend(_value_problems(base, label, operator, value, catalogue))
    return found


def _value_problems(
    metric: str, label: str, operator: str, value: str, catalogue: Catalogue
) -> list[str]:
    permitted = catalogue.label_values.get((metric, label))
    if permitted is None or "$" in value or "{{" in value:
        return []
    if operator in ("=~", "!~"):
        if not SIMPLE_ALTERNATION.match(value):
            return []
        candidates = value.split("|")
    else:
        candidates = [value]
    return [
        f"'{candidate}' is not a contract value of {metric}{{{label}}}"
        for candidate in candidates
        if candidate not in permitted
    ]


# --- file discovery ---------------------------------------------------------------------------


def _walk(node: Any, key: str | None = None) -> Iterator[str]:
    if isinstance(node, dict):
        for child_key, child in node.items():
            yield from _walk(child, str(child_key))
    elif isinstance(node, list):
        for child in node:
            yield from _walk(child, key)
    elif isinstance(node, str) and key in PROMQL_KEYS and node.strip():
        yield node


def expressions(path: Path) -> Iterator[str]:
    """PromQL strings in one YAML (possibly multi-document) or Grafana JSON file."""
    if path.suffix == ".json":
        yield from _walk(json.loads(path.read_text()))
        return
    for document in yaml.safe_load_all(path.read_text()):
        yield from _walk(document)


def scanned_files(directories: tuple[Path, ...] = SCANNED_DIRS) -> list[Path]:
    patterns = ("*.yml", "*.yaml", "*.json")
    return sorted(
        path
        for directory in directories
        if directory.exists()
        for pattern in patterns
        for path in directory.rglob(pattern)
    )


def main() -> int:
    catalogue = load_catalogue()
    failures: list[str] = []
    read: set[str] = set()
    count = 0
    for path in scanned_files():
        relative = path.relative_to(ROOT)
        for expression in expressions(path):
            count += 1
            failures.extend(
                f"{relative}: {problem}\n    in: {expression.strip()}"
                for problem in problems(expression, catalogue)
            )
            read.update(_read_metrics(expression, catalogue))
    for failure in failures:
        print(failure, file=sys.stderr)
    known = len(catalogue.metrics)
    print(f"metric-catalogue: {count} expressions checked against {known} E.10 metrics")
    unread = sorted(set(catalogue.metrics) - read)
    if unread:
        print(f"metric-catalogue: E.10 metrics no rule or dashboard reads yet: {', '.join(unread)}")
    if failures:
        print(f"metric-catalogue: {len(failures)} problem(s)", file=sys.stderr)
        return 1
    return 0


def _read_metrics(expression: str, catalogue: Catalogue) -> set[str]:
    """E.10 metrics an expression reads (unparseable expressions are reported by problems())."""
    try:
        found = list(selectors(expression))
    except (ValueError, IndexError):
        return set()
    return {resolved[0] for s in found if (resolved := _base_metric(s.name, catalogue))}


if __name__ == "__main__":
    sys.exit(main())
