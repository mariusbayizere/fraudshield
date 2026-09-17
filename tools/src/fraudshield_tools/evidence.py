"""Verification of traceability evidence references (ADR 0004; M0 re-review finding R-1).

Evidence is checked for substance, not only form:

* a commit SHA must be an ancestor of ``HEAD`` (an unrelated or future commit proves nothing);
* a GitHub Actions run URL must belong to this repository, and the run must exist, have
  concluded ``success``, and have been triggered for a commit in ``HEAD``'s history. That needs
  the GitHub API: CI provides a token, so an unverifiable run URL is an error in CI and a
  visible warning on a developer machine without a token.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

REPOSITORY = "mariusbayizere/fraudshield"
COMMIT_SHA = re.compile(r"^[0-9a-f]{7,40}$")
CI_RUN_URL = re.compile(rf"^https://github\.com/{re.escape(REPOSITORY)}/actions/runs/(\d+)$")
API_TIMEOUT_SECONDS = 15


@dataclass(frozen=True)
class RunVerdict:
    """``verified`` is None when the run could not be checked (no API access)."""

    verified: bool | None
    detail: str


class EvidenceVerifier(Protocol):
    in_ci: bool

    def commit_in_history(self, sha: str) -> bool: ...

    def ci_run(self, run_id: int) -> RunVerdict: ...


class GitEvidenceVerifier:
    def __init__(self, root: Path, token: str | None = None, in_ci: bool | None = None) -> None:
        self.root = root
        self.token = token if token is not None else os.environ.get("GITHUB_TOKEN")
        self.in_ci = in_ci if in_ci is not None else os.environ.get("GITHUB_ACTIONS") == "true"

    def commit_in_history(self, sha: str) -> bool:
        if not COMMIT_SHA.match(sha):
            return False
        result = subprocess.run(  # noqa: S603 - fixed git command; sha matched against COMMIT_SHA
            ["git", "merge-base", "--is-ancestor", sha, "HEAD"],  # noqa: S607
            cwd=self.root,
            capture_output=True,
            check=False,
        )
        return result.returncode == 0

    def ci_run(self, run_id: int) -> RunVerdict:
        if not self.token:
            return RunVerdict(None, "no GITHUB_TOKEN available to query the Actions API")
        request = urllib.request.Request(
            f"https://api.github.com/repos/{REPOSITORY}/actions/runs/{run_id}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=API_TIMEOUT_SECONDS) as response:  # noqa: S310
                run = json.load(response)
        except urllib.error.HTTPError as error:
            return RunVerdict(False, f"Actions API returned HTTP {error.code}")
        except (urllib.error.URLError, TimeoutError) as error:
            return RunVerdict(None, f"Actions API unreachable: {error}")
        if run.get("conclusion") != "success":
            return RunVerdict(False, f"run concluded {run.get('conclusion')!r}")
        head_sha = str(run.get("head_sha", ""))
        if not self.commit_in_history(head_sha):
            return RunVerdict(False, f"run commit {head_sha[:12]} is not in HEAD's history")
        return RunVerdict(True, "success")


# --- row references ----------------------------------------------------------------------

PATH_LIKE = re.compile(r"^[\w.\-]+(/[\w.\-]+)+$")
ADR_PATH = re.compile(r"^docs/adr/\d{4}-[a-z0-9-]+\.md$")
HARDWARE_RECORD = Path("docs/benchmarks/hardware.md")
BENCHMARK_EVIDENCE_DIR = "docs/benchmarks/"

# Rows whose acceptance criterion is a latency, throughput or render-time number (ADR 0010).
# Their gate evidence must be a measurement file from a dedicated machine recorded in
# docs/benchmarks/hardware.md — never a shared CI runner.
BENCHMARK_ROWS = frozenset(
    {
        *(f"NFR-PERF-{n:02d}" for n in (1, 2, 3, 4, 5, 6, 7, 8, 10)),
        "FR-01-01",
        "FR-01-06",
        "FR-02-02",
        "FR-02-07",
        "FR-02-09",
        "FR-03-01",
        "FR-03-03",
        "ML-GATE-12",
        "NFR-REL-05",
        "TEST-09",
        "TEST-10",
    }
)
EVIDENCED_STATUSES = frozenset({"DONE", "DONE_WITH_DEVIATION", "VERIFIED_AT_REDUCED_SCALE"})


@dataclass
class ReferenceFindings:
    errors: list[str]
    warnings: list[str]


def _first_token(entry: object) -> str:
    return str(entry).strip().split(" ", 1)[0]


def recorded_machines(root: Path) -> set[str]:
    """Machine IDs are the level-2 headings of the hardware record, e.g. ``## dev-laptop-01``."""
    record = root / HARDWARE_RECORD
    if not record.exists():
        return set()
    return {
        match.group(1)
        for match in re.finditer(r"^## ([a-z0-9][a-z0-9-]*)\b", record.read_text("utf-8"), re.M)
    }


def check_row_references(
    row: dict[str, object], root: Path, verifier: EvidenceVerifier
) -> ReferenceFindings:
    findings = ReferenceFindings(errors=[], warnings=[])
    where = f"{row.get('id')}:"
    evidence = [str(e) for e in _as_list(row.get("evidence"))]
    for entry in evidence:
        _check_evidence_entry(entry, where, root, verifier, findings)
    for deviation in _as_list(row.get("deviations")):
        token = _first_token(deviation)
        if not ADR_PATH.match(token) or not (root / token).is_file():
            findings.errors.append(f"{where} deviation {deviation!r} is not an existing ADR file")
    for implementation in _as_list(row.get("implementation")):
        path = _first_token(implementation).split("#", 1)[0].split(":", 1)[0]
        if not PATH_LIKE.match(path) or not (root / path).exists():
            findings.errors.append(f"{where} implementation path does not exist: {path}")
    if row.get("id") in BENCHMARK_ROWS and row.get("status") in EVIDENCED_STATUSES:
        _check_benchmark_row(row, evidence, where, root, findings)
    return findings


def _as_list(value: object) -> list[object]:
    return list(value) if isinstance(value, list) else []


def _check_evidence_entry(
    entry: str, where: str, root: Path, verifier: EvidenceVerifier, findings: ReferenceFindings
) -> None:
    token = _first_token(entry)
    run = CI_RUN_URL.match(token)
    if run:
        verdict = verifier.ci_run(int(run.group(1)))
        if verdict.verified is False:
            findings.errors.append(
                f"{where} CI run {run.group(1)} is not valid evidence: {verdict.detail}"
            )
        elif verdict.verified is None:
            message = f"{where} CI run {run.group(1)} not verified ({verdict.detail})"
            (findings.errors if verifier.in_ci else findings.warnings).append(message)
        return
    if token.startswith("https://"):
        findings.errors.append(f"{where} evidence URL {token!r} is not a run of {REPOSITORY}")
        return
    if COMMIT_SHA.match(token):
        if not verifier.commit_in_history(token):
            findings.errors.append(f"{where} evidence commit {token} is not an ancestor of HEAD")
        return
    path = token.split("#", 1)[0].split(":", 1)[0]
    if PATH_LIKE.match(path):
        if not (root / path).exists():
            findings.errors.append(f"{where} evidence path does not exist: {path}")
        return
    findings.errors.append(
        f"{where} evidence {entry!r} must start with a repository path, "
        "a commit SHA in HEAD's history or a CI run URL of this repository"
    )


def _check_benchmark_row(
    row: dict[str, object], evidence: list[str], where: str, root: Path, findings: ReferenceFindings
) -> None:
    measurement_files = [e for e in evidence if _first_token(e).startswith(BENCHMARK_EVIDENCE_DIR)]
    if not measurement_files:
        findings.errors.append(
            f"{where} benchmark row needs a measurement file under {BENCHMARK_EVIDENCE_DIR} "
            "(CI runner timings are not gate evidence, ADR 0010)"
        )
    if row.get("status") == "VERIFIED_AT_REDUCED_SCALE":
        machines = recorded_machines(root)
        stated = str(row.get("reduced_scale") or "")
        if not any(re.search(rf"\bmachine: {re.escape(m)}\b", stated) for m in machines):
            findings.errors.append(
                f"{where} reduced_scale must state 'machine: <id>' for a machine recorded in "
                f"{HARDWARE_RECORD} (recorded: {sorted(machines)})"
            )
