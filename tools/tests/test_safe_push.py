"""tools/bin/safe-push pushes only after the governance checks have passed on the commit pushed.

A push once followed a check that had not run (M8, 2026-10-19), the fourth variant of a check that
reported and did not gate. These tests pin that a failing or missing check, a dirty tree, a
detached HEAD, an unleased force, or HEAD or the tree moving during the checks leaves the remote
unchanged; that the commits checked are the ones the remote lacks, read from the remote itself;
and that a clean run pushes. `uv` is a stub that records each check, fails the one whose
arguments equal SAFE_PUSH_TEST_FAIL, and runs SAFE_PUSH_TEST_DO during the one equal to
SAFE_PUSH_TEST_ON.
"""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools" / "bin" / "safe-push"

STUB_UV = """#!/bin/sh
shift 3  # run --no-sync --quiet
echo "$*" >> "$SAFE_PUSH_TEST_LOG"
if [ "$*" = "${SAFE_PUSH_TEST_ON:-}" ]; then sh -c "$SAFE_PUSH_TEST_DO"; fi
[ "$*" != "${SAFE_PUSH_TEST_FAIL:-}" ]
"""

CHECKS = [
    "fs-traceability check",
    "fs-traceability-seed --check",
    "fs-defect-register --check",
    "fs-scope-guard",
    "fs-readme-status",
    "fs-compose-budget",
    "fs-contract-baselines --against origin/main",
    "fs-migration-guard --against origin/main",
]


def git(cwd: Path, *args: str) -> str:
    return subprocess.run(  # noqa: S603 - git with fixed arguments
        ["git", *args],  # noqa: S607 - git from PATH, as the script uses it
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def commit(work: Path, text: str, message: str) -> str:
    (work / "a.txt").write_text(text)
    git(work, "commit", "-q", "-am", message)
    return git(work, "rev-parse", "HEAD")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    remote = tmp_path / "remote.git"
    work = tmp_path / "work"
    for args in (["--bare", str(remote)], [str(work)]):
        subprocess.run(["git", "init", "-q", "-b", "main", *args], check=True)  # noqa: S603, S607
    git(work, "config", "user.email", "test@example.test")
    git(work, "config", "user.name", "Test")
    git(work, "remote", "add", "origin", str(remote))
    (work / "a.txt").write_text("a\n")
    git(work, "add", "a.txt")
    git(work, "commit", "-q", "-m", "docs: start")
    git(work, "push", "-q", "origin", "main")
    git(work, "switch", "-q", "-c", "feature")
    commit(work, "b\n", "docs: change a")
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    (bin_dir / "uv").write_text(STUB_UV)
    (bin_dir / "uv").chmod(0o755)
    return work


def push(
    work: Path, *args: str, fail: str = "", on: str = "", do: str = "", uv: bool = True
) -> tuple[subprocess.CompletedProcess[str], list[str]]:
    log = work.parent / "checks.log"
    log.unlink(missing_ok=True)
    path = os.environ["PATH"]
    env = {
        **os.environ,
        "PATH": f"{work.parent / 'bin'}:{path}" if uv else "/usr/bin:/bin",
        "SAFE_PUSH_TEST_LOG": str(log),
        "SAFE_PUSH_TEST_FAIL": fail,
        "SAFE_PUSH_TEST_ON": on,
        "SAFE_PUSH_TEST_DO": do,
    }
    result = subprocess.run(  # noqa: S603 - the script under test
        [str(SCRIPT), *args], cwd=work, env=env, capture_output=True, text=True, check=False
    )
    checks = log.read_text().splitlines() if log.exists() else []
    return result, checks


def remote_head(work: Path, branch: str = "feature") -> str | None:
    out = git(work, "ls-remote", "origin", f"refs/heads/{branch}")
    return out.split()[0] if out else None


def test_pushes_the_checked_commit_after_every_check_passes(repo: Path) -> None:
    head = git(repo, "rev-parse", "HEAD")
    result, checks = push(repo)
    assert result.returncode == 0, result.stderr
    assert remote_head(repo) == head
    assert checks == [f"fs-commit-msg --rev-range origin/main..{head}", *CHECKS]


@pytest.mark.parametrize("failing", ["fs-commit-msg", *CHECKS])
def test_any_one_failing_check_refuses_the_push(repo: Path, failing: str) -> None:
    head = git(repo, "rev-parse", "HEAD")
    if failing == "fs-commit-msg":
        failing = f"fs-commit-msg --rev-range origin/main..{head}"
    result, checks = push(repo, fail=failing)
    assert result.returncode == 1
    assert f"FAILED: {failing}" in result.stderr
    assert "REFUSED" in result.stderr
    assert remote_head(repo) is None
    # Every check still runs, so one refusal reports every problem at once.
    assert len(checks) == 1 + len(CHECKS)


def test_without_uv_no_check_passes_and_nothing_is_pushed(repo: Path) -> None:
    result, checks = push(repo, uv=False)
    assert result.returncode == 1
    assert "REFUSED" in result.stderr
    assert checks == []
    assert remote_head(repo) is None


def test_checks_only_the_commits_the_remote_does_not_have(repo: Path) -> None:
    pushed = git(repo, "rev-parse", "HEAD")
    assert push(repo)[0].returncode == 0
    head = commit(repo, "c\n", "docs: change a again")
    result, checks = push(repo)
    assert result.returncode == 0, result.stderr
    assert checks[0] == f"fs-commit-msg --rev-range {pushed}..{head}"


def test_a_deleted_remote_branch_is_checked_from_main_not_from_a_stale_tracking_ref(
    repo: Path,
) -> None:
    assert push(repo)[0].returncode == 0
    git(repo, "push", "-q", "origin", ":feature")
    # Without --prune, refs/remotes/origin/feature would still name the pushed commit and hide it.
    head = git(repo, "rev-parse", "HEAD")
    result, checks = push(repo)
    assert result.returncode == 0, result.stderr
    assert checks[0] == f"fs-commit-msg --rev-range origin/main..{head}"


def test_a_dirty_tree_is_refused_before_any_check(repo: Path) -> None:
    (repo / "untracked.txt").write_text("x\n")
    result, checks = push(repo)
    assert result.returncode == 1
    assert "uncommitted or untracked" in result.stderr
    assert checks == []
    assert remote_head(repo) is None


def test_a_tree_changed_during_the_checks_is_refused(repo: Path) -> None:
    result, checks = push(repo, on="fs-readme-status", do="echo x > untracked.txt")
    assert result.returncode == 1
    assert "changed while the checks ran" in result.stderr
    assert len(checks) == 1 + len(CHECKS)
    assert remote_head(repo) is None


def test_a_commit_made_during_the_checks_is_refused(repo: Path) -> None:
    result, _ = push(
        repo, on="fs-readme-status", do="echo late > a.txt && git commit -q -am 'docs: late'"
    )
    assert result.returncode == 1
    assert "HEAD moved" in result.stderr
    assert remote_head(repo) is None


def test_an_unreachable_remote_is_refused(repo: Path) -> None:
    git(repo, "remote", "set-url", "origin", str(repo.parent / "missing.git"))
    result, checks = push(repo)
    assert result.returncode == 1
    assert "could not fetch" in result.stderr
    assert checks == []


def test_a_detached_head_and_main_are_refused(repo: Path) -> None:
    git(repo, "switch", "-q", "--detach")
    result, checks = push(repo)
    assert result.returncode == 1
    assert "detached" in result.stderr
    git(repo, "switch", "-q", "main")
    result, checks = push(repo)
    assert result.returncode == 1
    assert "main" in result.stderr
    assert checks == []


@pytest.mark.parametrize(
    "args",
    [
        ("--force",),
        ("-f",),
        ("--force-with-lease",),
        ("--force-with-lease=feature:origin/feature",),
        ("--force-with-lease=feature:abc",),
        ("--no-verify",),
        ("origin", "main"),
    ],
)
def test_anything_but_a_lease_on_a_sha_is_refused(repo: Path, args: tuple[str, ...]) -> None:
    result, checks = push(repo, *args)
    assert result.returncode == 2
    assert checks == []
    assert remote_head(repo) is None


def test_a_rewritten_branch_needs_a_lease_on_the_sha_it_replaces(repo: Path) -> None:
    assert push(repo)[0].returncode == 0
    pushed = git(repo, "rev-parse", "HEAD")
    git(repo, "commit", "-q", "--amend", "-m", "docs: change a, reworded")
    result, _ = push(repo)
    assert result.returncode == 1
    assert "not an ancestor" in result.stderr
    assert remote_head(repo) == pushed

    wrong = push(repo, "--force-with-lease=feature:" + "0" * 40)[0]
    assert wrong.returncode == 1
    assert remote_head(repo) == pushed

    other = push(repo, f"--force-with-lease=main:{pushed}")[0]
    assert other.returncode == 1
    assert remote_head(repo) == pushed

    head = git(repo, "rev-parse", "HEAD")
    result, checks = push(repo, f"--force-with-lease=refs/heads/feature:{pushed[:12]}")
    assert result.returncode == 0, result.stderr
    assert remote_head(repo) == head
    assert checks[0] == f"fs-commit-msg --rev-range origin/main..{head}"
