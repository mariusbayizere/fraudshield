from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from fraudshield_tools import migration_guard
from fraudshield_tools.migration_guard import MIGRATIONS, GuardError, changed_migrations

pytestmark = pytest.mark.req("D-31")


def _git(root: Path, *args: str) -> None:
    subprocess.run(  # noqa: S603 - fixed git commands on a temporary repository
        ["git", "-C", str(root), "-c", "user.name=t", "-c", "user.email=t@example.com", *args],  # noqa: S607
        check=True,
        capture_output=True,
    )


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    _git(tmp_path, "init", "-q", "-b", "main")
    migrations = tmp_path / MIGRATIONS
    migrations.mkdir(parents=True)
    (migrations / "V1__first.sql").write_text("CREATE TABLE a (id int);\n")
    (migrations / "V2__second.sql").write_text("CREATE TABLE b (id int);\n")
    _git(tmp_path, "add", ".")
    _git(tmp_path, "commit", "-q", "-m", "merged")
    _git(tmp_path, "switch", "-q", "-c", "feature")
    return tmp_path


def test_new_migrations_are_allowed(repo: Path) -> None:
    (repo / MIGRATIONS / "V3__third.sql").write_text("CREATE TABLE c (id int);\n")
    assert changed_migrations(repo, "main") == []


def test_edited_or_deleted_merged_migrations_are_rejected(repo: Path) -> None:
    (repo / MIGRATIONS / "V1__first.sql").write_text("CREATE TABLE a (id bigint);\n")
    (repo / MIGRATIONS / "V2__second.sql").unlink()
    problems = changed_migrations(repo, "main")
    assert len(problems) == 2
    assert "V1__first.sql: modified" in problems[0]
    assert "V2__second.sql: deleted" in problems[1]


def test_committed_edits_on_a_branch_are_rejected(repo: Path) -> None:
    (repo / MIGRATIONS / "V2__second.sql").write_text("-- rewritten\n")
    _git(repo, "commit", "-q", "-am", "edit merged migration")
    assert changed_migrations(repo, "main") == [
        f"{MIGRATIONS}/V2__second.sql: modified after it was merged; add a new migration instead"
    ]


def test_unknown_ref_is_an_error(repo: Path) -> None:
    with pytest.raises(GuardError, match="not found"):
        changed_migrations(repo, "origin/nowhere")


def test_main_reports_the_repository_state(capsys: pytest.CaptureFixture[str]) -> None:
    assert migration_guard.main(["--against", "HEAD"]) == 0
    assert "changed" in capsys.readouterr().out
    assert migration_guard.main(["--against", "no-such-ref"]) == 2
