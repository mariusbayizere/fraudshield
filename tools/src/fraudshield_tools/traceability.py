"""Traceability check and matrix renderer (build prompt D.2).

``fs-traceability check``  validates docs/traceability/requirements.yaml against the
tests tagged in code and the milestone state, and fails CI on any violation.
``fs-traceability render`` regenerates docs/traceability/requirements_matrix.md.

Enforcement rules (ADR 0004):
  1. IDs are unique and well formed; enums are valid.
  2. Every requirement ID used as a test tag exists in the YAML.
  3. Rows with a completed status carry evidence; each status has its required fields.
  4. Completed rows verified by ``test`` have at least one tagged test.
  5. For every milestone listed as completed in milestones.yaml, every Must row assigned
     to it (or earlier) is in a final status, and Must rows verified by ``test`` have
     at least one tagged test.
  6. Evidence and deviation paths that look like repository files exist.
  7. The rendered matrix is up to date.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from fraudshield_tools import REPO_ROOT

REQUIREMENTS_YAML = Path("docs/traceability/requirements.yaml")
MILESTONES_YAML = Path("docs/traceability/milestones.yaml")
MATRIX_MD = Path("docs/traceability/requirements_matrix.md")

ID_PATTERN = re.compile(
    r"^(FR-\d{2}-\d{2}|NFR-(PERF|SEC|REL)-\d{2}|UX-(REG|DASH)-\d{2}"
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
MILESTONES = [f"M{n}" for n in range(13)]

JAVA_TAG = re.compile(r'@Tag\(\s*"([^"]+)"\s*\)')
PY_REQ = re.compile(r"@pytest\.mark\.req\(([^)]*)\)")
# Matches it('[ID] …'), test.skip("[ID] …") and the table form it.each([...])('[ID] …').
TS_TITLE = re.compile(
    r"""(?:\b(?:it|test|describe)(?:\.\w+)?\(|\]\)\()\s*['"`]\[([A-Z0-9 ,\-]+)\]"""
)
QUOTED = re.compile(r"""['"]([^'"]+)['"]""")
PATH_LIKE = re.compile(r"^[\w.\-]+(/[\w.\-]+)+$")


@dataclass(frozen=True)
class TaggedTest:
    requirement_id: str
    location: str


@dataclass
class CheckReport:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def tracked_files(root: Path) -> list[Path]:
    """Files tracked or staged in git plus untracked, non-ignored files."""
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],  # noqa: S607
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return [root / line for line in result.stdout.splitlines() if (root / line).is_file()]


def _is_test_file(rel: str) -> bool:
    name = rel.rsplit("/", 1)[-1]
    if rel.endswith(".java"):
        return "/src/test/" in rel
    if rel.endswith(".py"):
        return name.startswith("test_") or name.endswith("_test.py")
    return bool(re.search(r"\.(test|spec)\.(ts|tsx)$", name))


def discover_tagged_tests(root: Path, files: list[Path] | None = None) -> list[TaggedTest]:
    found: list[TaggedTest] = []
    for path in files if files is not None else tracked_files(root):
        rel = path.relative_to(root).as_posix()
        if not _is_test_file(rel):
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            ids: list[str] = []
            if rel.endswith(".java"):
                ids = JAVA_TAG.findall(line)
            elif rel.endswith(".py"):
                ids = [q for args in PY_REQ.findall(line) for q in QUOTED.findall(args)]
            else:
                groups = TS_TITLE.findall(line)
                ids = [part.strip() for group in groups for part in group.split(",")]
            found.extend(TaggedTest(req_id, f"{rel}:{lineno}") for req_id in ids)
    return found


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

    for row in rows:
        _check_row(row, tests_by_id, closed_upto, root, report)
    return report


def _check_row(
    row: dict[str, Any],
    tests_by_id: dict[str, list[str]],
    closed_upto: int,
    root: Path,
    report: CheckReport,
) -> None:
    req_id = str(row.get("id"))
    where = f"{req_id}:"
    if not ID_PATTERN.match(req_id):
        report.errors.append(f"{where} malformed ID")
    enums = (("priority", PRIORITIES), ("status", STATUSES), ("verification", VERIFICATIONS))
    for key, allowed in enums:
        if row.get(key) not in allowed:
            report.errors.append(f"{where} invalid {key} {row.get(key)!r}")
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
    _check_paths(row, root, report)


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


def _check_paths(row: dict[str, Any], root: Path, report: CheckReport) -> None:
    references = [
        *(row.get("evidence") or []),
        *(row.get("deviations") or []),
        *(row.get("implementation") or []),
    ]
    for ref in references:
        candidate = str(ref).split(" ", 1)[0].split("#", 1)[0].split(":", 1)[0]
        if PATH_LIKE.match(candidate) and not (root / candidate).exists():
            report.errors.append(f"{row.get('id')}: referenced path does not exist: {candidate}")


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
    for message in report.errors:
        print(f"ERROR {message}", file=sys.stderr)
    print(
        f"traceability-check: {len(rows)} rows, {len(tests)} tagged tests, "
        f"{len(report.errors)} errors"
    )
    return 1 if report.errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
