from __future__ import annotations

import copy

import pytest

from fraudshield_contracts import baseline_guard
from fraudshield_contracts.events import schemas


def test_published_baselines_are_read_from_git_history() -> None:
    kafka = baseline_guard.published_kafka_baseline("HEAD")
    assert set(kafka) <= set(schemas())
    committed = baseline_guard._git(
        "ls-tree", "--name-only", f"HEAD:{baseline_guard.KAFKA_SCHEMAS}"
    )
    assert len(kafka) == len([n for n in committed.split() if n.endswith(".schema.json")])


def test_guard_fails_closed_when_baselines_cannot_be_read(monkeypatch: pytest.MonkeyPatch) -> None:
    """Review N-04: schemas published without a readable baseline must not pass as "0 published"."""
    monkeypatch.setattr(baseline_guard, "merge_base", lambda against: "0" * 40)
    monkeypatch.setattr(baseline_guard, "published_kafka_baseline", lambda base: {})
    monkeypatch.setattr(
        baseline_guard, "_has", lambda base, path: path == baseline_guard.KAFKA_SCHEMAS
    )
    with pytest.raises(baseline_guard.GuardError, match="no baseline could be read"):
        baseline_guard.check("origin/main")
    assert baseline_guard.main(["--against", "origin/main"]) == 2


def test_guard_explains_a_missing_ref(capsys: pytest.CaptureFixture[str]) -> None:
    assert baseline_guard.main(["--against", "refs/heads/does-not-exist-anywhere"]) == 2
    assert "not found; fetch it first" in capsys.readouterr().err


def test_guard_uses_the_published_baseline_not_the_edited_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Review NF-E04: a schema narrowed together with its baseline must still fail."""
    published = copy.deepcopy(dict(schemas()))
    published["urn:fraudshield:kafka:decision-final"]["properties"]["decided_by"]["enum"].append(
        "RETIRED_DECIDER"
    )
    monkeypatch.setattr(baseline_guard, "merge_base", lambda against: "0" * 40)
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
