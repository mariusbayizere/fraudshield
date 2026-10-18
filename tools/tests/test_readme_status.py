from __future__ import annotations

from pathlib import Path

import pytest

from fraudshield_tools import readme_status


def test_the_repository_readme_agrees_with_the_register() -> None:
    """The live check, against the real files: this is what CI runs."""
    assert readme_status.main([]) == 0


def test_the_check_fails_when_the_readme_names_a_different_milestone(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The case that actually happened: the README said M0 while M2 was under way.

    It sat that way for two whole milestones because nothing looked — every other
    record-accuracy guard here watches a generated artefact, and the README is written by hand
    (M2 milestone review).
    """
    monkeypatch.setattr(readme_status, "readme_milestone", lambda: "M0")
    monkeypatch.setattr(readme_status, "current_milestone", lambda: "M2")

    assert readme_status.main([]) == 1


def test_a_readme_without_a_status_line_is_an_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """Deleting the status line must fail rather than silently pass the check."""

    def missing() -> str:
        raise ValueError("README.md has no status line")

    monkeypatch.setattr(readme_status, "readme_milestone", missing)

    assert readme_status.main([]) == 1


def test_a_status_line_naming_m8b_is_read_whole(monkeypatch: pytest.MonkeyPatch) -> None:
    """M8B must not be read as M8, or the check would compare the wrong milestone."""
    found = readme_status.STATUS.search("> **Project status: M8B — the analyst API.**")
    assert found is not None
    assert found.group(1) == "M8B"


@pytest.mark.parametrize(("current", "accepted"), [("M8B", True), ("M7", True), ("M9B", False)])
def test_current_must_be_a_known_milestone(
    current: str, accepted: bool, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The register's "current" is checked against the milestone list, M8B included."""
    register = tmp_path / "milestones.yaml"
    register.write_text(f"current: {current}\ncompleted: []\n", encoding="utf-8")
    monkeypatch.setattr(readme_status, "MILESTONES", register)
    if accepted:
        assert readme_status.current_milestone() == current
    else:
        with pytest.raises(ValueError, match="must be a milestone"):
            readme_status.current_milestone()
