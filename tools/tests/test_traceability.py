from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from fraudshield_tools.traceability import (
    TaggedTest,
    check,
    discover_tagged_tests,
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
    assert any("does not exist" in e for e in report.errors)


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


def test_discovers_tags_in_all_three_languages(tmp_path: Path) -> None:
    java = tmp_path / "backend/common/src/test/java/MoneyTest.java"
    java.parent.mkdir(parents=True)
    java.write_text('@Tag("D-43")\nclass MoneyTest {}\n')
    py = tmp_path / "ml/tests/test_metrics.py"
    py.parent.mkdir(parents=True)
    py.write_text('@pytest.mark.req("D-01", "ML-GATE-02")\ndef test_x(): ...\n')
    ts = tmp_path / "frontend/src/contrast.test.ts"
    ts.parent.mkdir(parents=True)
    ts.write_text(
        "it('[D-33, UX-DASH-01] white on amber fails', () => {});\n"
        "it.each([\n  ['a', 1],\n])('[FR-04-05] renders %s', () => {});\n"
        "it('no tag here [D-01]', () => {});\n"
    )
    not_a_test = tmp_path / "backend/common/src/main/java/Money.java"
    not_a_test.parent.mkdir(parents=True)
    not_a_test.write_text('@Tag("D-99")\n')

    found = discover_tagged_tests(tmp_path, [java, py, ts, not_a_test])

    assert {(t.requirement_id, t.location) for t in found} == {
        ("D-43", "backend/common/src/test/java/MoneyTest.java:1"),
        ("D-01", "ml/tests/test_metrics.py:1"),
        ("ML-GATE-02", "ml/tests/test_metrics.py:1"),
        ("D-33", "frontend/src/contrast.test.ts:1"),
        ("UX-DASH-01", "frontend/src/contrast.test.ts:1"),
        ("FR-04-05", "frontend/src/contrast.test.ts:4"),
    }


def test_matrix_lists_tests_and_status_counts() -> None:
    matrix = render_matrix([_row(defects=["D-12"])], [TaggedTest("FR-01-03", "t.py:9")])
    assert "Status counts: NOT_STARTED 1." in matrix
    expected = "| FR-01-03 | M | M6 | NOT_STARTED | Idempotent ingestion | — | t.py:9 | — | D-12 |"
    assert expected in matrix
