from __future__ import annotations

import copy

import pytest

from fraudshield_contracts import baseline_guard
from fraudshield_contracts.events import schemas


def test_published_baselines_are_read_from_git_history() -> None:
    kafka = baseline_guard.published_kafka_baseline("HEAD")
    assert set(kafka) <= set(schemas())
    assert baseline_guard.published_kafka_baseline("HEAD~0") == kafka


def test_guard_uses_the_published_baseline_not_the_edited_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Review NF-E04: a schema narrowed together with its baseline must still fail."""
    published = copy.deepcopy(dict(schemas()))
    published["urn:fraudshield:kafka:decision-final"]["properties"]["decided_by"]["enum"].append(
        "RETIRED_DECIDER"
    )
    monkeypatch.setattr(
        baseline_guard, "_git", lambda *args: "0" * 40 if args[0] == "merge-base" else ""
    )
    monkeypatch.setattr(baseline_guard, "published_kafka_baseline", lambda base: published)
    monkeypatch.setattr(
        baseline_guard, "_has", lambda base, path: path == baseline_guard.PROTO_FILE
    )
    monkeypatch.setattr(
        baseline_guard, "buf_breaking", lambda base: ["scoring.proto:1:1:RPC deleted."]
    )
    problems = baseline_guard.check("origin/main")
    assert any("decided_by: enum values removed" in p for p in problems)
    assert "proto: scoring.proto:1:1:RPC deleted." in problems
    assert baseline_guard.main(["--against", "origin/main"]) == 1


def test_guard_reports_git_failures() -> None:
    assert baseline_guard.main(["--against", "refs/heads/does-not-exist-anywhere"]) == 2
