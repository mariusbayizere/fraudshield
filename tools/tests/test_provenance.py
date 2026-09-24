"""The stamp that says which tree actually ran (PB-53).

PB-52 is the case every test here is built around: an artefact naming a commit whose code does not
produce it, because the run was made on a working tree carrying uncommitted changes. The stamp
exists to make that state visible at the moment it happens rather than two days later, so the
tests are about the difference between "names a commit" and "was produced at one".
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from fraudshield_tools import exit_criteria, provenance
from fraudshield_tools.evidence_run import main as evidence_main

pytestmark = pytest.mark.req("D-47")


def _repo(root: Path) -> Path:
    """A real git repository with one commit, because the stamp is defined by git's own answers."""
    subprocess.run(["git", "init", "-q", str(root)], check=True)  # noqa: S603, S607
    for key, value in (("user.email", "t@example.com"), ("user.name", "Test")):
        subprocess.run(  # noqa: S603
            ["git", "-C", str(root), "config", key, value],  # noqa: S607
            check=True,
        )
    (root / "tracked.txt").write_text("one\n", encoding="utf-8")
    subprocess.run(["git", "-C", str(root), "add", "-A"], check=True)  # noqa: S603, S607
    subprocess.run(  # noqa: S603
        ["git", "-C", str(root), "commit", "-qm", "first"],  # noqa: S607
        check=True,
    )
    return root


def test_a_clean_tree_reads_clean_and_a_modified_one_does_not(tmp_path: Path) -> None:
    """The precondition first: the tree must really be clean, or the rest is vacuous."""
    root = _repo(tmp_path / "repo")
    assert provenance.tree_state(root) == provenance.CLEAN
    assert provenance.stamp(root).clean

    (root / "tracked.txt").write_text("two\n", encoding="utf-8")
    dirty = provenance.tree_state(root)
    assert dirty != provenance.CLEAN
    assert not provenance.stamp(root).clean


def test_an_untracked_file_counts_as_dirty(tmp_path: Path) -> None:
    """The archetypal accident: a new module imported and not yet added.

    A guard that diffed tracked content would call this clean, and it is precisely the case where
    the interpreter runs code no commit contains.
    """
    root = _repo(tmp_path / "repo")
    assert provenance.tree_state(root) == provenance.CLEAN, "precondition: clean before the file"
    (root / "brand_new.py").write_text("x = 1\n", encoding="utf-8")
    assert provenance.tree_state(root) != provenance.CLEAN


def test_two_different_modifications_give_two_different_states(tmp_path: Path) -> None:
    """The state has to be a function of the modification, not a flag.

    A constant "dirty" marker would make two different uncommitted trees indistinguishable, and
    the question PB-52 could not answer — *which* uncommitted change — would stay unanswerable
    even with the guard in place.
    """
    root = _repo(tmp_path / "repo")
    (root / "tracked.txt").write_text("two\n", encoding="utf-8")
    first = provenance.tree_state(root)
    (root / "another.txt").write_text("three\n", encoding="utf-8")
    second = provenance.tree_state(root)
    assert first != second


def test_require_clean_refuses_a_dirty_tree_and_the_escape_stamps_it(tmp_path: Path) -> None:
    """Refusal is the default; the escape does not suppress the record, it writes one."""
    root = _repo(tmp_path / "repo")
    assert provenance.require_clean(root).clean, "precondition: a clean tree is not refused"

    (root / "tracked.txt").write_text("two\n", encoding="utf-8")
    with pytest.raises(provenance.DirtyTreeError, match="belongs to no commit"):
        provenance.require_clean(root)

    stamped = provenance.require_clean(root, allow_dirty=True)
    assert not stamped.clean
    assert "NOT CITABLE" in stamped.header()


def test_a_header_round_trips_through_parse() -> None:
    """The header is a contract between the runner that writes it and the checker that reads it."""
    original = provenance.Stamp(commit="a" * 40, tree_state=provenance.CLEAN)
    parsed = provenance.parse(original.header() + "\nsome measured output\n")
    assert parsed == original
    assert provenance.parse("no stamp here\n") is None


def test_evidence_run_refuses_a_dirty_tree_and_writes_nothing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The wrapper is where the rule binds, so the refusal is tested through it."""
    root = _repo(tmp_path / "repo")
    monkeypatch.setattr("fraudshield_tools.evidence_run.REPO_ROOT", root)
    output = tmp_path / "out" / "artefact.txt"

    assert evidence_main(["--output", str(output), "--", "echo", "hello"]) == 0
    assert "hello" in output.read_text(encoding="utf-8")
    assert provenance.parse(output.read_text(encoding="utf-8")) is not None

    (root / "tracked.txt").write_text("two\n", encoding="utf-8")
    output.unlink()
    assert evidence_main(["--output", str(output), "--", "echo", "hello"]) == 2
    assert not output.exists(), "a refused run must leave no artefact to be quoted"

    assert evidence_main(["--allow-dirty", "--output", str(output), "--", "echo", "hi"]) == 0
    assert "NOT CITABLE" in output.read_text(encoding="utf-8")


def test_a_failing_command_still_leaves_its_artefact(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A failed evidence run is evidence. One that left no file would be indistinguishable from a
    run never attempted, which is the ambiguity the exit-criteria checker exists to remove.
    """
    root = _repo(tmp_path / "repo")
    monkeypatch.setattr("fraudshield_tools.evidence_run.REPO_ROOT", root)
    output = tmp_path / "artefact.txt"
    assert evidence_main(["--output", str(output), "--", "false"]) == 1
    assert "# exit: 1" in output.read_text(encoding="utf-8")


def _criterion(**evidence: object) -> dict[str, object]:
    return {
        "milestone": "MX",
        "criteria": [
            {
                "id": "EX",
                "title": "an artefact criterion",
                "status": "met",
                "evidence": {"kind": "artifact", **evidence},
            }
        ],
    }


def test_the_checker_reads_the_stamp_rather_than_the_hand_written_hash(tmp_path: Path) -> None:
    """The gap PB-52 fell through, closed (PB-53).

    All three artefacts below *name* the same commit, so the pre-existing check passes on each.
    They differ only in what the stamp says actually ran — which is the distinction between a
    claim and its evidence, and the one that was missing.
    """
    commit = "abcdef1234567890abcdef1234567890abcdef12"
    short = commit[:7]

    unstamped = tmp_path / "old.txt"
    unstamped.write_text(f"produced at commit {short}\n", encoding="utf-8")
    report = exit_criteria.check(_criterion(ref="old.txt", commit=short), tmp_path)
    assert report.ok, report.errors
    assert any("carries no fs-evidence stamp" in w for w in report.warnings), (
        "an artefact predating the guard must warn, not fail: failing them all would get the "
        "threshold lowered until nothing fails"
    )

    clean = tmp_path / "clean.txt"
    clean.write_text(
        provenance.Stamp(commit=commit, tree_state=provenance.CLEAN).header() + f"{short}\n",
        encoding="utf-8",
    )
    report = exit_criteria.check(_criterion(ref="clean.txt", commit=short), tmp_path)
    assert report.ok, report.errors
    assert not report.warnings

    dirty = tmp_path / "dirty.txt"
    dirty.write_text(
        provenance.Stamp(commit=commit, tree_state="0123456789abcdef").header() + f"{short}\n",
        encoding="utf-8",
    )
    report = exit_criteria.check(_criterion(ref="dirty.txt", commit=short), tmp_path)
    assert not report.ok
    assert any("modified working tree" in e for e in report.errors)


def test_a_stamp_naming_another_commit_is_refused(tmp_path: Path) -> None:
    """The artefact says one thing in prose and another in its stamp. The stamp is what ran."""
    artefact = tmp_path / "moved.txt"
    artefact.write_text(
        provenance.Stamp(commit="b" * 40, tree_state=provenance.CLEAN).header() + "aaaaaaa\n",
        encoding="utf-8",
    )
    report = exit_criteria.check(_criterion(ref="moved.txt", commit="aaaaaaa"), tmp_path)
    assert not report.ok
    assert any("is stamped" in e for e in report.errors)
