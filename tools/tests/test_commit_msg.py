from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from fraudshield_tools.commit_msg import main, problems

GOOD = """feat(decision): hold MEDIUM transactions with 30 s review deadline

Implements the HOLD outcome and deadline scheduler. Final decision is
delivered via signed webhook and fs.decisions.final (D-14).
https://github.com/mariusbayizere/fraudshield/actions/runs/35176401589/jobs/105058963189

Refs: FR-03-02, D-14
"""


def test_good_message_passes() -> None:
    assert problems(GOOD) == []


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("update stuff", "subject must be"),
        ("feature: add thing", "subject must be"),
        ("feat: WIP", "placeholder"),
        ("fix(api): handle nulls.", "full stop"),
        (
            "feat(frontend): add strict TypeScript toolchain and WCAG contrast measure",
            "73 characters",
        ),
        ("feat: add x\nbody on second line", "second line must be blank"),
        ("feat: add x\n\n" + "y" * 73, "line 3 is 73 characters"),
        ("feat: add x\n\nCo-Authored-By: Someone <a@b.c>", "co-author"),
        ("feat: add x\n\n   co-authored-by: Someone <a@b.c>", "co-author"),
        ("feat: add x\n\nGenerated-by: Claude Code", "co-author"),
        ("feat: add x\n\nSigned-off-by: Claude <noreply@anthropic.com>", "co-author"),
        ("feat: updated things", "placeholder"),
        # GOV-7: a placeholder stays a placeholder when words follow it.
        ("feat: updated things across modules", "placeholder"),
        ("chore: clean up misc files in the tree", "placeholder"),
        ("feat: add x\n\nAssisted-by: Claude Code", "co-author"),
        ("feat: add x\n\nCo-developed-by: GitHub Copilot", "co-author"),
        ("feat: add x\n\nMade with Claude", "co-author"),
        ("feat: add x\n\n  pair-programmed-by: an AI assistant", "co-author"),
        ("chore: minor", "placeholder"),
        ("", "empty"),
    ],
)
def test_rule_violations(message: str, expected: str) -> None:
    assert any(expected in p for p in problems(message)), problems(message)


def test_owner_sign_off_and_real_summaries_are_allowed() -> None:
    assert problems("fix(api): update threshold cache on config change") == []
    assert (
        problems("docs: add testing guide\n\nSigned-off-by: Marius Bayizere <m@example.org>") == []
    )


def test_attribution_trailers_naming_a_person_remain_allowed() -> None:
    """GOV-7 rejects trailers that name a tool; crediting a person is the owner's to do."""
    assert problems("feat(api): add the rate limiter\n\nAssisted-by: a colleague") == []
    assert problems("feat(api): add the rate limiter\n\nCo-developed-by: a teammate") == []


def test_comment_lines_and_indented_code_are_ignored() -> None:
    message = "docs: explain setup\n\n    " + "x" * 90 + "\n# git comment " + "z" * 90 + "\n"
    assert problems(message) == []


def test_cli_file_and_range(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    message_file = tmp_path / "COMMIT_EDITMSG"
    message_file.write_text(GOOD)
    assert main([str(message_file)]) == 0
    message_file.write_text("oops")
    assert main([str(message_file)]) == 1

    def run(*args: str) -> None:
        subprocess.run(["git", *args], cwd=tmp_path, check=True, capture_output=True)  # noqa: S603, S607

    run("init", "-q")
    run("config", "user.email", "t@example.invalid")
    run("config", "user.name", "Test")
    run("commit", "-q", "--allow-empty", "-m", "chore: start")
    run("commit", "-q", "--allow-empty", "-m", "bad message")
    monkeypatch.chdir(tmp_path)
    assert main(["--rev-range", "HEAD~1..HEAD"]) == 1
    assert main(["--rev-range", "HEAD~1"]) == 0
