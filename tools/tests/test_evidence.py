from __future__ import annotations

import io
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import pytest

from fraudshield_tools.evidence import (
    GitEvidenceVerifier,
    RunVerdict,
    check_row_references,
    recorded_machines,
)


class Verifier:
    def __init__(self, verdict: RunVerdict, in_ci: bool) -> None:
        self.verdict = verdict
        self.in_ci = in_ci

    def commit_in_history(self, sha: str) -> bool:
        return sha == "abc1234"

    def ci_run(self, run_id: int) -> RunVerdict:
        return self.verdict


RUN = "https://github.com/mariusbayizere/fraudshield/actions/runs/1"
FAKE_TOKEN = "test-" + "placeholder"


def _refs(row: dict[str, Any], root: Path, verdict: RunVerdict, in_ci: bool = False) -> Any:
    return check_row_references(row, root, Verifier(verdict, in_ci))


@pytest.mark.parametrize(
    ("verdict", "in_ci", "errors", "warnings"),
    [
        (RunVerdict(True, "success"), True, 0, 0),
        (RunVerdict(False, "run concluded 'failure'"), False, 1, 0),
        (RunVerdict(None, "no token"), False, 0, 1),
        (RunVerdict(None, "no token"), True, 1, 0),
    ],
)
def test_ci_run_evidence_is_verified_not_just_parsed(
    tmp_path: Path, verdict: RunVerdict, in_ci: bool, errors: int, warnings: int
) -> None:
    findings = _refs({"id": "FR-01-03", "evidence": [RUN]}, tmp_path, verdict, in_ci)
    assert (len(findings.errors), len(findings.warnings)) == (errors, warnings)


@pytest.mark.parametrize(
    "url",
    [
        "https://github.com/someone-else/fraudshield/actions/runs/35179479949",
        "https://github.com/mariusbayizere/fraudshield-fork/actions/runs/1",
        "https://example.com/proof",
    ],
)
def test_urls_outside_this_repository_are_rejected(tmp_path: Path, url: str) -> None:
    findings = _refs({"id": "FR-01-03", "evidence": [url]}, tmp_path, RunVerdict(True, "ok"))
    assert any("is not a run of mariusbayizere/fraudshield" in e for e in findings.errors)


def test_commit_must_be_in_history(tmp_path: Path) -> None:
    ok = _refs({"id": "D-47", "evidence": ["abc1234 closing"]}, tmp_path, RunVerdict(True, ""))
    bad = _refs({"id": "D-47", "evidence": ["89d6859 unrelated"]}, tmp_path, RunVerdict(True, ""))
    assert ok.errors == []
    assert bad.errors == ["D-47: evidence commit 89d6859 is not an ancestor of HEAD"]


MEASUREMENT = "docs/benchmarks/2026-09-20-FR-02-07.json"


def _hardware(tmp_path: Path) -> None:
    (tmp_path / "docs/benchmarks").mkdir(parents=True)
    (tmp_path / "docs/benchmarks/hardware.md").write_text(
        "# Hardware\n\n## dev-laptop-01 (recorded)\n\n## bench-cs-4c16g\n"
    )
    (tmp_path / "docs/benchmarks/2026-09-20-FR-02-07.json").write_text("{}")


def test_benchmark_rows_need_measurement_file_and_recorded_machine(tmp_path: Path) -> None:
    _hardware(tmp_path)
    assert recorded_machines(tmp_path) == {"dev-laptop-01", "bench-cs-4c16g"}
    base = {"id": "FR-02-07", "status": "VERIFIED_AT_REDUCED_SCALE"}

    ci_only = _refs(
        {**base, "evidence": [RUN], "reduced_scale": "machine: dev-laptop-01"},
        tmp_path,
        RunVerdict(True, ""),
    )
    no_machine = _refs(
        {
            **base,
            "evidence": ["docs/benchmarks/2026-09-20-FR-02-07.json"],
            "reduced_scale": "2 cores",
        },
        tmp_path,
        RunVerdict(True, ""),
    )
    unknown_machine = _refs(
        {
            **base,
            "evidence": ["docs/benchmarks/2026-09-20-FR-02-07.json"],
            "reduced_scale": "machine: github-runner",
        },
        tmp_path,
        RunVerdict(True, ""),
    )
    good = _refs(
        {
            **base,
            "evidence": ["docs/benchmarks/2026-09-20-FR-02-07.json"],
            "reduced_scale": "p99 31 ms at 200 concurrent; machine: dev-laptop-01",
        },
        tmp_path,
        RunVerdict(True, ""),
    )

    assert any("needs a measurement file" in e for e in ci_only.errors)
    assert any("reduced_scale must state" in e for e in no_machine.errors)
    assert any("reduced_scale must state" in e for e in unknown_machine.errors)
    assert good.errors == []


def test_non_benchmark_rows_are_not_subject_to_machine_rules(tmp_path: Path) -> None:
    findings = _refs(
        {"id": "D-47", "status": "DONE", "evidence": [RUN]}, tmp_path, RunVerdict(True, "")
    )
    assert findings.errors == []


def test_git_verifier_checks_ancestry(tmp_path: Path) -> None:
    def git(*args: str) -> str:
        return subprocess.run(  # noqa: S603
            ["git", *args],  # noqa: S607
            cwd=tmp_path,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    git("init", "-q")
    git("config", "user.email", "t@example.invalid")
    git("config", "user.name", "Test")
    git("commit", "-q", "--allow-empty", "-m", "first")
    first = git("rev-parse", "HEAD")
    git("checkout", "-q", "-b", "side")
    git("commit", "-q", "--allow-empty", "-m", "side")
    side = git("rev-parse", "HEAD")
    git("checkout", "-q", "-")
    verifier = GitEvidenceVerifier(tmp_path, token="", in_ci=False)
    assert verifier.commit_in_history(first[:7])
    assert not verifier.commit_in_history(side)
    assert not verifier.commit_in_history("not-a-sha")
    assert verifier.ci_run(1).verified is None


def test_git_verifier_queries_actions_api(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    verifier = GitEvidenceVerifier(tmp_path, token=FAKE_TOKEN, in_ci=True)
    monkeypatch.setattr(verifier, "commit_in_history", lambda sha: sha == "good")
    responses: dict[int, Any] = {
        1: {"conclusion": "success", "head_sha": "good"},
        2: {"conclusion": "failure", "head_sha": "good"},
        3: {"conclusion": "success", "head_sha": "elsewhere"},
        4: {"conclusion": "success", "head_sha": "good"},
        5: {"conclusion": "success", "head_sha": "good"},
    }
    # GOV-10: a run concludes success even when a path filter skipped one of its jobs.
    jobs: dict[int, Any] = {
        1: {"jobs": [{"name": "python", "conclusion": "success"}]},
        4: {
            "jobs": [
                {"name": "changes", "conclusion": "success"},
                {"name": "stack", "conclusion": "skipped"},
            ]
        },
        5: {"jobs": []},
    }

    def fake_urlopen(request: Any, timeout: int) -> io.BytesIO:
        assert request.headers["Authorization"] == f"Bearer {FAKE_TOKEN}"
        url = request.full_url.split("?", 1)[0]
        if url.endswith("/jobs"):
            return io.BytesIO(json.dumps(jobs[int(url.rsplit("/", 2)[1])]).encode())
        run_id = int(url.rsplit("/", 1)[1])
        if run_id == 404:
            raise urllib.error.HTTPError(request.full_url, 404, "Not Found", {}, None)  # type: ignore[arg-type]
        if run_id == 500:
            raise urllib.error.URLError("offline")
        return io.BytesIO(json.dumps(responses[run_id]).encode())

    monkeypatch.setattr(urllib.request, "urlopen", fake_urlopen)
    assert verifier.ci_run(1) == RunVerdict(True, "success, 1 job(s) succeeded")
    assert verifier.ci_run(2).verified is False
    assert verifier.ci_run(3).verified is False
    assert verifier.ci_run(404) == RunVerdict(False, "Actions API returned HTTP 404")
    assert verifier.ci_run(500).verified is None
    skipped = verifier.ci_run(4)
    assert skipped.verified is False
    assert "stack skipped" in skipped.detail
    assert verifier.ci_run(5) == RunVerdict(False, "run reports no jobs")
