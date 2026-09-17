from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

from fraudshield_tools.traceability import (
    TaggedTest,
    check,
    discover_tagged_tests,
    main,
    render_matrix,
)


def _row(**overrides: Any) -> dict[str, Any]:
    row: dict[str, Any] = {
        "id": "FR-01-03",
        "title": "Idempotent ingestion",
        "priority": "M",
        "milestone": "M6",
        "status": "NOT_STARTED",
        "verification": "test",
        "implementation": [],
        "evidence": [],
        "deviations": [],
        "defects": [],
    }
    row.update(overrides)
    return row


def test_clean_rows_pass(tmp_path: Path) -> None:
    report = check([_row()], {"completed": []}, [], tmp_path)
    assert report.errors == []


def test_unknown_tag_fails(tmp_path: Path) -> None:
    report = check([_row()], {}, [TaggedTest("FR-99-99", "x.py:1")], tmp_path)
    assert any("FR-99-99" in e for e in report.errors)


def test_done_without_evidence_or_test_fails(tmp_path: Path) -> None:
    report = check([_row(status="DONE")], {}, [], tmp_path)
    assert any("requires evidence" in e for e in report.errors)
    assert any("no tagged test" in e for e in report.errors)


def test_done_with_evidence_and_test_passes(tmp_path: Path) -> None:
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs/proof.json").write_text("{}")
    row = _row(status="DONE", evidence=["docs/proof.json (idempotency run)"])
    report = check([row], {}, [TaggedTest("FR-01-03", "t.py:3")], tmp_path)
    assert report.errors == []


def test_missing_evidence_file_fails(tmp_path: Path) -> None:
    row = _row(status="DONE", evidence=["docs/benchmarks/missing.json"])
    report = check([row], {}, [TaggedTest("FR-01-03", "t.py:3")], tmp_path)
    assert any("evidence path does not exist" in e for e in report.errors)


def test_closed_milestone_requires_final_status_and_test(tmp_path: Path) -> None:
    report = check([_row(milestone="M0")], {"completed": ["M0"]}, [], tmp_path)
    assert any("closed M0 has status NOT_STARTED" in e for e in report.errors)
    assert any("lacks a tagged test" in e for e in report.errors)


def test_open_milestone_is_not_enforced(tmp_path: Path) -> None:
    report = check([_row(milestone="M6")], {"completed": ["M0"]}, [], tmp_path)
    assert report.errors == []


def test_should_rows_in_closed_milestone_are_not_forced(tmp_path: Path) -> None:
    report = check([_row(milestone="M0", priority="S")], {"completed": ["M0"]}, [], tmp_path)
    assert report.errors == []


@pytest.mark.parametrize(
    ("status", "missing_field"),
    [
        ("DONE_WITH_DEVIATION", "requires an ADR"),
        ("VERIFIED_AT_REDUCED_SCALE", "requires reduced_scale"),
        ("BLOCKED", "requires blocked_reason"),
        ("REQUIRES_EXTERNAL_PARTY", "requires notes"),
    ],
)
def test_status_specific_fields(tmp_path: Path, status: str, missing_field: str) -> None:
    row = _row(status=status, evidence=["measured"], verification="external")
    report = check([row], {}, [], tmp_path)
    assert any(missing_field in e for e in report.errors)


def test_duplicate_and_malformed_ids_fail(tmp_path: Path) -> None:
    report = check([_row(), _row(), _row(id="FR-1-3")], {}, [], tmp_path)
    assert any("duplicate IDs" in e for e in report.errors)
    assert any("malformed ID" in e for e in report.errors)


def test_discovery_uses_only_test_files(tmp_path: Path) -> None:
    java = tmp_path / "backend/common/src/test/java/MoneyTest.java"
    java.parent.mkdir(parents=True)
    java.write_text('class MoneyTest {\n  @Tag("D-43")\n  @Test\n  void x() {}\n}\n')
    main_source = tmp_path / "backend/common/src/main/java/Money.java"
    main_source.parent.mkdir(parents=True)
    main_source.write_text('@Tag("D-99")\n')

    found = discover_tagged_tests(tmp_path, [java, main_source])

    assert [(t.requirement_id, t.location) for t in found] == [
        ("D-43", "backend/common/src/test/java/MoneyTest.java:2")
    ]


def test_matrix_lists_tests_and_status_counts() -> None:
    matrix = render_matrix([_row(defects=["D-12"])], [TaggedTest("FR-01-03", "t.py:9")])
    assert "Status counts: NOT_STARTED 1." in matrix
    expected = "| FR-01-03 | M | M6 | NOT_STARTED | Idempotent ingestion | — | t.py:9 | — | D-12 |"
    assert expected in matrix


@pytest.mark.parametrize(
    "evidence",
    ["tested", "should work", "ADR 9999 (nonexistent)", "docs/missing.json (run)"],
)
def test_evidence_must_reference_path_commit_or_ci_run(tmp_path: Path, evidence: str) -> None:
    row = _row(status="DONE", evidence=[evidence])
    report = check([row], {}, [TaggedTest("FR-01-03", "t.py:3")], tmp_path, lambda _sha: False)
    assert report.errors, f"evidence {evidence!r} was accepted"


def test_evidence_accepts_existing_commit_and_ci_run_url(tmp_path: Path) -> None:
    row = _row(
        status="DONE",
        evidence=[
            "3f82d6a make ci green at this commit",
            "https://github.com/mariusbayizere/fraudshield/actions/runs/35175017636",
        ],
    )
    report = check(
        [row], {}, [TaggedTest("FR-01-03", "t.py:3")], tmp_path, lambda sha: sha == "3f82d6a"
    )
    assert report.errors == []


def test_unknown_commit_in_evidence_fails(tmp_path: Path) -> None:
    row = _row(status="DONE", evidence=["deadbee"])
    report = check([row], {}, [TaggedTest("FR-01-03", "t.py:3")], tmp_path, lambda _sha: False)
    assert any("not in the repository" in e for e in report.errors)


def test_deviation_must_be_existing_adr_file(tmp_path: Path) -> None:
    (tmp_path / "docs/adr").mkdir(parents=True)
    (tmp_path / "docs/adr/0003-toolchain.md").write_text("# ADR")
    (tmp_path / "docs/proof.json").write_text("{}")
    base = {"status": "DONE_WITH_DEVIATION", "evidence": ["docs/proof.json"]}
    tests = [TaggedTest("FR-01-03", "t.py:3")]

    bad = check([_row(**base, deviations=["ADR 9999 (nonexistent)"])], {}, tests, tmp_path)
    missing = check([_row(**base, deviations=["docs/adr/0009-missing.md"])], {}, tests, tmp_path)
    good = check([_row(**base, deviations=["docs/adr/0003-toolchain.md"])], {}, tests, tmp_path)

    assert any("not an existing ADR" in e for e in bad.errors)
    assert any("not an existing ADR" in e for e in missing.errors)
    assert good.errors == []


def test_non_test_verification_only_for_reviewed_overrides(tmp_path: Path) -> None:
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs/note.md").write_text("x")
    sneaky = _row(status="DONE", verification="inspection", evidence=["docs/note.md"])
    allowed = _row(id="D-48", status="DONE", verification="inspection", evidence=["docs/note.md"])
    assert any("not permitted" in e for e in check([sneaky], {}, [], tmp_path).errors)
    assert check([allowed], {}, [], tmp_path).errors == []


def test_implementation_paths_must_exist(tmp_path: Path) -> None:
    report = check([_row(implementation=["backend/nope/Missing.java"])], {}, [], tmp_path)
    assert any("implementation path does not exist" in e for e in report.errors)


def _git_repo_with_traceability(tmp_path: Path, rows: list[dict[str, Any]]) -> None:
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)  # noqa: S603, S607
    (tmp_path / "docs/traceability").mkdir(parents=True)
    (tmp_path / "docs/traceability/requirements.yaml").write_text(
        yaml.safe_dump({"requirements": rows})
    )
    (tmp_path / "docs/traceability/milestones.yaml").write_text("completed: []\n")
    test_file = tmp_path / "tools/tests/test_x.py"
    test_file.parent.mkdir(parents=True)
    test_file.write_text('import pytest\n\n@pytest.mark.req("FR-01-03")\ndef test_x(): ...\n')


def test_main_check_fails_on_stale_matrix_and_passes_after_render(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    _git_repo_with_traceability(tmp_path, [_row()])

    assert main(["check", "--root", str(tmp_path)]) == 1
    assert "is stale" in capsys.readouterr().err

    assert main(["render", "--root", str(tmp_path)]) == 0
    assert main(["check", "--root", str(tmp_path)]) == 0
    matrix = (tmp_path / "docs/traceability/requirements_matrix.md").read_text()
    assert "tools/tests/test_x.py:3" in matrix

    (tmp_path / "tools/tests/test_x.py").write_text("def test_x(): ...\n")
    assert main(["check", "--root", str(tmp_path)]) == 1
