"""Tests for the alert labelling and runbook check (infrastructure/checks/rule_conventions.py)."""

from __future__ import annotations

from pathlib import Path

import pytest
import rule_conventions as rc


def page(name: str, **annotations: str) -> rc.Alert:
    defaults = {
        "summary": "s",
        "description": "d",
        "runbook_url": f"{rc.RUNBOOK_BASE}{name}.md",
    }
    return rc.Alert(name, "rules.yml", {"severity": "page", "team": "sre"}, defaults | annotations)


def test_reads_alerts_but_not_recording_rules() -> None:
    document = {
        "groups": [
            {"rules": [{"record": "a:b:c", "expr": "1"}, {"alert": "X", "expr": "1", "labels": {}}]}
        ]
    }
    assert [alert.name for alert in rc.alerts_in(document, "f.yml")] == ["X"]


def test_well_formed_page_alert_passes() -> None:
    assert rc.alert_problems(page("FraudShieldX")) == []


def ticket(severity: str, team: str, **annotations: str) -> rc.Alert:
    return rc.Alert("A", "rules.yml", {"severity": severity, "team": team}, annotations)


@pytest.mark.parametrize(
    ("alert", "fragment"),
    [
        (ticket("critical", "sre", summary="s", description="d"), "severity"),
        (ticket("ticket", "ops", summary="s", description="d"), "team"),
        (ticket("ticket", "ml", summary="s"), "description"),
        (page("A", runbook_url="https://example.org/a"), "runbook_url"),
    ],
)
def test_convention_breaks_are_reported(alert: rc.Alert, fragment: str) -> None:
    assert any(fragment in problem for problem in rc.alert_problems(alert))


def test_runbooks_must_match_page_alerts_both_ways(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("[A](A.md)")
    (tmp_path / "A.md").write_text("# A")
    (tmp_path / "Stale.md").write_text("# gone")
    problems = rc.runbook_problems([page("A"), page("Missing")], tmp_path)
    assert problems == [
        "docs/runbooks/Missing.md is missing for page alert Missing",
        "docs/runbooks/Stale.md belongs to no page alert",
    ]


def test_runbook_must_be_indexed(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("no links")
    (tmp_path / "A.md").write_text("# A")
    assert rc.runbook_problems([page("A")], tmp_path) == [
        "docs/runbooks/README.md does not link A.md"
    ]


def test_duplicate_alert_names_are_reported() -> None:
    assert rc.duplicate_problems([page("A"), page("A")]) == [
        "alert A defined in rules.yml and rules.yml"
    ]


def test_repository_rules_follow_the_conventions(capsys: pytest.CaptureFixture[str]) -> None:
    assert rc.main() == 0, capsys.readouterr().err
