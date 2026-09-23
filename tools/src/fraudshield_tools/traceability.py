"""Traceability check and matrix renderer (build prompt D.2).

``fs-traceability check``  validates docs/traceability/requirements.yaml against the
tests tagged in code and the milestone state, and fails CI on any violation.
``fs-traceability render`` regenerates docs/traceability/requirements_matrix.md.

Enforcement rules (ADR 0004). SRS-derived fields are verified separately by
``fs-traceability-seed --check``.
  1. IDs are unique and well formed; enums are valid.
  2. Every requirement ID used as a test tag exists in the YAML (tags are discovered by
     :mod:`fraudshield_tools.test_tags`, which ignores comments and disabled tests).
  3. A verification method other than ``test`` is allowed only for the rows listed in
     ``VERIFICATION_OVERRIDES`` (a reviewed decision, not a per-row escape hatch).
  4. Rows with a completed status carry evidence, verified by
     :mod:`fraudshield_tools.evidence`: an existing repository path, a commit that is an
     ancestor of HEAD, or a successful GitHub Actions run of this repository for a commit in
     HEAD's history. Benchmark rows need a measurement file from a recorded machine (ADR 0010).
  5. ``DONE_WITH_DEVIATION`` rows name at least one existing ``docs/adr/NNNN-*.md``; every
     deviation entry must be such a file. Other statuses have their required fields.
  6. Completed rows verified by ``test`` have at least one tagged test.
  7. For every milestone listed as completed in milestones.yaml, every Must row assigned
     to it (or earlier) is in a final status, and Must rows verified by ``test`` have
     at least one tagged test.
  8. Implementation paths exist.
  9. The rendered matrix is up to date.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.evidence import (
    EvidenceVerifier,
    GitEvidenceVerifier,
    check_row_references,
)
from fraudshield_tools.repo import tracked_files
from fraudshield_tools.test_tags import TaggedTest, discover
from fraudshield_tools.traceability_seed import VERIFICATION_OVERRIDES

__all__ = ["CheckReport", "TaggedTest", "check", "discover_tagged_tests", "main", "render_matrix"]

REQUIREMENTS_YAML = Path("docs/traceability/requirements.yaml")
MILESTONES_YAML = Path("docs/traceability/milestones.yaml")
MATRIX_MD = Path("docs/traceability/requirements_matrix.md")

ID_PATTERN = re.compile(
    r"^(FR-\d{2}-\d{2}|NFR-(PERF|SEC|REL)-\d{2}|UX-(REG|DASH)-\d{2}"
    # UX-ROLE-* and DEV-MATRIX-* come from the parts of SRS v5.0 the owner adopted
    # (docs/srs/v5_decisions.md): the per-role frontend specification and the device matrix.
    r"|UX-ROLE-(AN|SN|RO|AD)-\d{2}|DEV-MATRIX-\d{2}"
    r"|MOB-(PWA|TOUCH|NET|PERF|COMP|DEV)-\d{2}|ML-(DATA|GATE)-\d{2}|OPS-(CI|OBS)-\d{2}"
    r"|TEST-\d{2}|RES-\d{2}|D-\d{2})$"
)
PRIORITIES = {"M", "S", "C", "W"}
STATUSES = {
    "NOT_STARTED",
    "IN_PROGRESS",
    "DONE",
    "DONE_WITH_DEVIATION",
    "VERIFIED_AT_REDUCED_SCALE",
    "REQUIRES_EXTERNAL_PARTY",
    "BLOCKED",
}
EVIDENCED_STATUSES = {"DONE", "DONE_WITH_DEVIATION", "VERIFIED_AT_REDUCED_SCALE"}
FINAL_STATUSES = EVIDENCED_STATUSES | {"REQUIRES_EXTERNAL_PARTY"}
VERIFICATIONS = {"test", "inspection", "manual", "external"}
# M13 is the reporting milestone added after M8 for SRS v5.0 FR-08 (build prompt D.3).
MILESTONES = [f"M{n}" for n in range(14)]


@dataclass
class CheckReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def discover_tagged_tests(root: Path, files: list[Path] | None = None) -> list[TaggedTest]:
    return discover(root, files if files is not None else tracked_files(root))


def load_yaml(path: Path) -> dict[str, Any]:
    loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
    return loaded if isinstance(loaded, dict) else {}


def _milestone_index(milestone: str) -> int:
    return MILESTONES.index(milestone)


def check(
    rows: list[dict[str, Any]],
    milestones: dict[str, Any],
    tests: list[TaggedTest],
    root: Path,
    verifier: EvidenceVerifier | None = None,
) -> CheckReport:
    report = CheckReport()
    ids = [str(row.get("id")) for row in rows]
    duplicates = sorted({i for i in ids if ids.count(i) > 1})
    if duplicates:
        report.errors.append(f"duplicate IDs: {duplicates}")
    known = set(ids)

    tests_by_id: dict[str, list[str]] = defaultdict(list)
    for tagged in tests:
        if tagged.requirement_id not in known:
            report.errors.append(f"{tagged.location}: unknown tag {tagged.requirement_id!r}")
        tests_by_id[tagged.requirement_id].append(tagged.location)

    completed = milestones.get("completed") or []
    bad_milestones = [m for m in completed if m not in MILESTONES]
    if bad_milestones:
        report.errors.append(f"milestones.yaml: unknown milestones {bad_milestones}")
    closed_upto = max((_milestone_index(m) for m in completed if m in MILESTONES), default=-1)

    context = _RowContext(tests_by_id, closed_upto, root, verifier or GitEvidenceVerifier(root))
    for row in rows:
        _check_row(row, context, report)
    return report


@dataclass(frozen=True)
class _RowContext:
    tests_by_id: dict[str, list[str]]
    closed_upto: int
    root: Path
    verifier: EvidenceVerifier


def _check_row(row: dict[str, Any], context: _RowContext, report: CheckReport) -> None:
    tests_by_id, closed_upto = context.tests_by_id, context.closed_upto
    req_id = str(row.get("id"))
    where = f"{req_id}:"
    if not ID_PATTERN.match(req_id):
        report.errors.append(f"{where} malformed ID")
    enums = (("priority", PRIORITIES), ("status", STATUSES), ("verification", VERIFICATIONS))
    for key, allowed in enums:
        if row.get(key) not in allowed:
            report.errors.append(f"{where} invalid {key} {row.get(key)!r}")
    expected_verification = VERIFICATION_OVERRIDES.get(req_id, "test")
    if (
        row.get("verification") in VERIFICATIONS
        and row.get("verification") != expected_verification
    ):
        report.errors.append(
            f"{where} verification {row.get('verification')!r} is not permitted; "
            f"expected {expected_verification!r} (change VERIFICATION_OVERRIDES via review)"
        )
    milestone = row.get("milestone")
    if milestone not in MILESTONES:
        report.errors.append(f"{where} invalid milestone {milestone!r}")
        return

    status = row.get("status")
    has_test = bool(tests_by_id.get(req_id))
    _check_status_fields(row, has_test, report)
    if _milestone_index(milestone) <= closed_upto and row.get("priority") == "M":
        if status not in FINAL_STATUSES:
            report.errors.append(f"{where} Must row in closed {milestone} has status {status}")
        if row.get("verification") == "test" and not has_test:
            report.errors.append(f"{where} Must row in closed {milestone} lacks a tagged test")
    references = check_row_references(row, context.root, context.verifier)
    report.errors.extend(references.errors)
    report.warnings.extend(references.warnings)


def _check_status_fields(row: dict[str, Any], has_test: bool, report: CheckReport) -> None:
    where = f"{row.get('id')}:"
    status = row.get("status")
    required = {
        "DONE_WITH_DEVIATION": ("deviations", "requires an ADR in deviations"),
        "VERIFIED_AT_REDUCED_SCALE": ("reduced_scale", "requires reduced_scale numbers"),
        "BLOCKED": ("blocked_reason", "requires blocked_reason"),
        "REQUIRES_EXTERNAL_PARTY": ("notes", "requires notes naming the party"),
    }
    if status in EVIDENCED_STATUSES:
        if not row.get("evidence"):
            report.errors.append(f"{where} status {status} requires evidence")
        if row.get("verification") == "test" and not has_test:
            report.errors.append(f"{where} status {status} but no tagged test found")
    if status in required and not row.get(required[status][0]):
        report.errors.append(f"{where} {status} {required[status][1]}")


def _cell(values: list[str]) -> str:
    return "<br>".join(v.replace("|", "\\|") for v in values) if values else "—"


def render_matrix(rows: list[dict[str, Any]], tests: list[TaggedTest]) -> str:
    tests_by_id: dict[str, list[str]] = defaultdict(list)
    for tagged in tests:
        tests_by_id[tagged.requirement_id].append(tagged.location)
    counts: dict[str, int] = defaultdict(int)
    for row in rows:
        counts[str(row["status"])] += 1
    lines = [
        "<!-- GENERATED FILE — do not edit. Source: docs/traceability/requirements.yaml",
        "     and test tags in code. Regenerate with: uv run fs-traceability render -->",
        "",
        "# FraudShield Requirements Traceability Matrix",
        "",
        f"Rows: {len(rows)}. Status counts: "
        + ", ".join(f"{status} {counts[status]}" for status in sorted(counts))
        + ".",
        "",
        "| ID | Priority | Milestone | Status | Title | Implementation | Tests | Evidence "
        "| Deviations |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for row in rows:
        req_id = str(row["id"])
        deviations = [*(row.get("deviations") or []), *row.get("defects", [])]
        lines.append(
            f"| {req_id} | {row['priority']} | {row['milestone']} | {row['status']} "
            f"| {str(row['title']).replace('|', '/')} | {_cell(row.get('implementation') or [])} "
            f"| {_cell(sorted(tests_by_id.get(req_id, [])))} | {_cell(row.get('evidence') or [])} "
            f"| {_cell(deviations)} |"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="FraudShield traceability tooling")
    parser.add_argument("command", choices=["check", "render"])
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args(argv)
    root: Path = args.root

    rows = load_yaml(root / REQUIREMENTS_YAML).get("requirements", [])
    tests = discover_tagged_tests(root)
    matrix = render_matrix(rows, tests)

    if args.command == "render":
        (root / MATRIX_MD).write_text(matrix, encoding="utf-8")
        print(f"wrote {MATRIX_MD} ({len(rows)} rows, {len(tests)} tagged tests)")
        return 0

    report = check(rows, load_yaml(root / MILESTONES_YAML), tests, root)
    matrix_path = root / MATRIX_MD
    if not matrix_path.exists() or matrix_path.read_text(encoding="utf-8") != matrix:
        report.errors.append(f"{MATRIX_MD} is stale; run: uv run fs-traceability render")
    for message in report.warnings:
        print(f"WARNING {message}", file=sys.stderr)
    for message in report.errors:
        print(f"ERROR {message}", file=sys.stderr)
    print(
        f"traceability-check: {len(rows)} rows, {len(tests)} tagged tests, "
        f"{len(report.errors)} errors, {len(report.warnings)} warnings"
    )
    return 1 if report.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
