"""Fail when an alert rule breaks the labelling and runbook conventions of FraudShield alerts.

Build prompt E.10 requires a runbook in ``docs/runbooks/`` for each page-worthy alert. This check
makes that a property of the repository rather than a promise, over the Prometheus and Loki rules:

* every alert has ``severity`` in ``SEVERITIES`` and ``team`` in ``TEAMS`` (the values
  Alertmanager routes on) and ``summary`` and ``description`` annotations;
* every ``severity: page`` alert has ``runbook_url`` equal to ``RUNBOOK_BASE/<alert>.md``, and
  that file exists and is listed in ``docs/runbooks/README.md``;
* every runbook file belongs to a page alert, so a renamed or deleted alert cannot leave a stale
  runbook behind;
* alert names are unique across all rule files.

Usage: ``uv run python infrastructure/checks/rule_conventions.py`` from the repository root.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

ROOT = Path(__file__).resolve().parents[2]
RULE_DIRS = (ROOT / "infrastructure/prometheus/rules", ROOT / "infrastructure/loki/rules")
RUNBOOKS = ROOT / "docs/runbooks"
RUNBOOK_BASE = "https://github.com/mariusbayizere/fraudshield/blob/main/docs/runbooks/"
SEVERITIES = frozenset({"page", "ticket"})
TEAMS = frozenset({"sre", "risk", "ml"})
REQUIRED_ANNOTATIONS = ("summary", "description")


@dataclass(frozen=True)
class Alert:
    name: str
    source: str
    labels: dict[str, str]
    annotations: dict[str, str]


def alerts_in(document: dict[str, Any], source: str) -> list[Alert]:
    return [
        Alert(
            name=rule["alert"],
            source=source,
            labels={str(k): str(v) for k, v in (rule.get("labels") or {}).items()},
            annotations={str(k): str(v) for k, v in (rule.get("annotations") or {}).items()},
        )
        for group in document.get("groups", [])
        for rule in group.get("rules", [])
        if "alert" in rule
    ]


def load_alerts(rule_dirs: tuple[Path, ...] = RULE_DIRS) -> list[Alert]:
    found: list[Alert] = []
    for directory in rule_dirs:
        for path in sorted(directory.rglob("*.y*ml")):
            document = yaml.safe_load(path.read_text()) or {}
            found.extend(alerts_in(document, str(path.relative_to(ROOT))))
    return found


def alert_problems(alert: Alert) -> list[str]:
    where = f"{alert.source}: {alert.name}"
    found: list[str] = []
    if alert.labels.get("severity") not in SEVERITIES:
        found.append(f"{where}: severity must be one of {sorted(SEVERITIES)}")
    if alert.labels.get("team") not in TEAMS:
        found.append(f"{where}: team must be one of {sorted(TEAMS)}")
    found.extend(
        f"{where}: missing annotation '{key}'"
        for key in REQUIRED_ANNOTATIONS
        if not alert.annotations.get(key, "").strip()
    )
    if alert.labels.get("severity") == "page":
        expected = f"{RUNBOOK_BASE}{alert.name}.md"
        if alert.annotations.get("runbook_url") != expected:
            found.append(f"{where}: page alerts need runbook_url {expected}")
    return found


def runbook_problems(alerts: list[Alert], runbooks: Path = RUNBOOKS) -> list[str]:
    paged = {alert.name for alert in alerts if alert.labels.get("severity") == "page"}
    files = {path.stem for path in runbooks.glob("*.md") if path.name != "README.md"}
    index_path = runbooks / "README.md"
    index = index_path.read_text() if index_path.exists() else ""
    found = [
        f"docs/runbooks/{name}.md is missing for page alert {name}"
        for name in sorted(paged - files)
    ]
    found += [f"docs/runbooks/{name}.md belongs to no page alert" for name in sorted(files - paged)]
    found += [
        f"docs/runbooks/README.md does not link {name}.md"
        for name in sorted(paged & files)
        if f"({name}.md)" not in index
    ]
    return found


def duplicate_problems(alerts: list[Alert]) -> list[str]:
    seen: dict[str, str] = {}
    found: list[str] = []
    for alert in alerts:
        if alert.name in seen:
            found.append(f"alert {alert.name} defined in {seen[alert.name]} and {alert.source}")
        seen.setdefault(alert.name, alert.source)
    return found


def main() -> int:
    alerts = load_alerts()
    problems = [problem for alert in alerts for problem in alert_problems(alert)]
    problems += runbook_problems(alerts) + duplicate_problems(alerts)
    for problem in problems:
        print(problem, file=sys.stderr)
    pages = sum(alert.labels.get("severity") == "page" for alert in alerts)
    print(f"rule-conventions: {len(alerts)} alerts, {pages} page-worthy, each with a runbook")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
