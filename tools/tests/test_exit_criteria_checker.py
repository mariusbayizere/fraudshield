"""The exit-criteria checker (PB-45).

The table it guards was, for a day, the only record in this repository asserting a milestone's
completeness, and two of its rows said **Met** while citing an evidence run that had not happened.
These tests are mostly about the ways the checker must refuse, because refusing is its whole job.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml

from fraudshield_tools import REPO_ROOT, exit_criteria

pytestmark = pytest.mark.req("D-47")

REGISTER = REPO_ROOT / "docs" / "traceability" / "m3_exit_criteria.yaml"
DOCUMENT = REPO_ROOT / "docs" / "traceability" / "m3_exit_criteria.md"


def _register(**overrides: object) -> dict[str, object]:
    criterion: dict[str, object] = {
        "id": "E99",
        "title": "an example criterion",
        "status": "met",
        "evidence": {"kind": "test", "ref": "test_the_committed_register_resolves"},
    }
    criterion.update(overrides)
    return {"milestone": "M3", "criteria": [criterion]}


@pytest.mark.req("D-47")
def test_the_committed_register_resolves() -> None:
    """The real M3 register, against the real repository. If this fails the table is lying."""
    report = exit_criteria.check(exit_criteria.load(REGISTER), REPO_ROOT)
    assert report.ok, report.errors
    assert report.checked == 15, f"expected 15 criteria, checked {report.checked}"


@pytest.mark.req("D-47")
def test_the_committed_table_is_not_stale() -> None:
    """The document's generated block must equal what the register renders.

    This is the check that would have caught the original failure: the table said Met, and nothing
    compared it to anything.
    """
    register = exit_criteria.load(REGISTER)
    table = exit_criteria.render_table(register)
    text = DOCUMENT.read_text(encoding="utf-8")
    start = text.find(exit_criteria.START_MARKER)
    end = text.find(exit_criteria.END_MARKER)
    assert start != -1, "the document has no generated-table markers"
    assert table == text[start : end + len(exit_criteria.END_MARKER)], (
        "the table is stale; run: uv run fs-exit-criteria --render"
    )


@pytest.mark.req("D-47")
def test_a_named_test_that_does_not_exist_is_refused() -> None:
    """The mutation this tool exists for: claim evidence that is not there."""
    report = exit_criteria.check(
        _register(evidence={"kind": "test", "ref": "test_this_was_never_written"}), REPO_ROOT
    )
    assert not report.ok
    assert any("test_this_was_never_written" in e for e in report.errors)


@pytest.mark.req("D-47")
def test_a_judgement_criterion_may_never_be_marked_met() -> None:
    """The load-bearing rule.

    A checker that marked judgement criteria met would be worse than none: it would lend a
    machine's authority to a claim no machine made, and a reader could not tell which cells were
    measured. The control asserts the same criterion is accepted when honestly labelled.
    """
    judged = {"kind": "judgement", "asserted_by": "author", "note": "a rule applied throughout"}
    refused = exit_criteria.check(_register(status="met", evidence=judged), REPO_ROOT)
    assert not refused.ok
    assert any("may only be 'author_asserted'" in e for e in refused.errors)

    accepted = exit_criteria.check(_register(status="author_asserted", evidence=judged), REPO_ROOT)
    assert accepted.ok, accepted.errors


@pytest.mark.req("D-47")
def test_a_judgement_must_name_who_judged_and_what() -> None:
    """ "Author asserted" with nothing after it is a label, not a record."""
    for missing in ({"asserted_by": ""}, {"note": ""}):
        evidence = {"kind": "judgement", "asserted_by": "author", "note": "something"} | missing
        report = exit_criteria.check(
            _register(status="author_asserted", evidence=evidence), REPO_ROOT
        )
        assert not report.ok, missing


@pytest.mark.req("D-47")
def test_a_mechanised_criterion_may_not_hide_behind_author_asserted() -> None:
    """The other direction: if evidence can be resolved, the status must follow it.

    Without this, any inconvenient row could be relabelled a judgement and escape the check.
    """
    report = exit_criteria.check(
        _register(
            status="author_asserted",
            evidence={"kind": "test", "ref": "test_the_committed_register_resolves"},
        ),
        REPO_ROOT,
    )
    assert not report.ok
    assert any("mechanically checkable" in e for e in report.errors)


@pytest.mark.req("D-47")
def test_an_artefact_must_name_the_commit_it_was_produced_at(tmp_path: Path) -> None:
    """A figure is quoted with the tree that produced it, not only with its scale (E2).

    Caught a real instance on its first run: the M3 milestone review did not name the commit its
    findings were fixed at.
    """
    artefact = tmp_path / "run.txt"
    artefact.write_text("produced at commit abcdef1\n", encoding="utf-8")
    rel = "run.txt"

    nameless = exit_criteria.check(_register(evidence={"kind": "artifact", "ref": rel}), tmp_path)
    assert not nameless.ok
    assert any("names no commit" in e for e in nameless.errors)

    wrong = exit_criteria.check(
        _register(evidence={"kind": "artifact", "ref": rel, "commit": "9999999"}), tmp_path
    )
    assert not wrong.ok
    assert any("does not name commit" in e for e in wrong.errors)

    right = exit_criteria.check(
        _register(evidence={"kind": "artifact", "ref": rel, "commit": "abcdef1"}), tmp_path
    )
    assert right.ok, right.errors


@pytest.mark.req("D-47")
def test_a_gate_must_be_wired_into_the_governance_target() -> None:
    """A gate nobody runs establishes nothing.

    Checked by wiring rather than by execution: running it here would double what CI already does
    and, since this checker is itself part of `governance`, would recurse.
    """
    wired = exit_criteria.check(
        _register(evidence={"kind": "gate", "ref": "uv run fs-traceability check"}), REPO_ROOT
    )
    assert wired.ok, wired.errors

    unwired = exit_criteria.check(
        _register(evidence={"kind": "gate", "ref": "uv run fs-invented-gate"}), REPO_ROOT
    )
    assert not unwired.ok
    assert any("governance target" in e for e in unwired.errors)


@pytest.mark.req("D-47")
def test_an_empty_register_is_refused_rather_than_passing_over_nothing() -> None:
    """ADR 0009's generalisation: a check that has only run over an empty scope is untested."""
    report = exit_criteria.check({"milestone": "M3", "criteria": []}, REPO_ROOT)
    assert not report.ok
    assert any("passes over nothing" in e for e in report.errors)


@pytest.mark.req("D-47")
def test_the_rendered_table_marks_judgement_rows_as_author_asserted() -> None:
    """A reader scanning the table must see at a glance which rows a machine stands behind."""
    table = exit_criteria.render_table(exit_criteria.load(REGISTER))
    assert "**author-asserted** — author" in table
    assert "**NOT MET**" in table, "E3's failure must be visible in the table, not softened"
    judgement_rows = [line for line in table.splitlines() if "author-asserted" in line]
    assert len(judgement_rows) == 3, f"expected E2, E12 and E13, got {len(judgement_rows)}"


@pytest.mark.req("D-47")
def test_rendering_is_idempotent(tmp_path: Path) -> None:
    """Rendering twice must not move the file, or `--check` would fail after a `--render`."""
    document = tmp_path / "criteria.md"
    document.write_text(
        f"prose\n\n{exit_criteria.START_MARKER}\n{exit_criteria.END_MARKER}\n\nmore prose\n",
        encoding="utf-8",
    )
    table = exit_criteria.render_table(exit_criteria.load(REGISTER))
    assert exit_criteria.apply_table(document, table) is True
    assert exit_criteria.apply_table(document, table) is False
    assert document.read_text(encoding="utf-8").startswith("prose")
    assert document.read_text(encoding="utf-8").endswith("more prose\n")


@pytest.mark.req("D-47")
def test_every_criterion_in_the_register_has_a_title_and_a_known_status() -> None:
    """Shape errors fail loudly rather than rendering an empty cell."""
    register = yaml.safe_load(REGISTER.read_text(encoding="utf-8"))
    for criterion in register["criteria"]:
        assert criterion["title"].strip()
        assert criterion["status"] in exit_criteria.STATUSES
        assert criterion["evidence"]["kind"] in exit_criteria.KINDS
