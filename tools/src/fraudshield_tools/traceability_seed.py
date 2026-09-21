"""Seed or refresh docs/traceability/requirements.yaml from the SRS and defect register.

Rows are derived from the SRS tables listed in build prompt D.2. Re-running is safe:
SRS-derived fields (title, section, srs_text, priority, milestone, defects) are
refreshed, while progress fields (status, implementation, evidence, deviations,
verification, notes) are preserved for existing IDs.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.defect_register import DEFECT_HEADING, PROMPT_PATH, extract_part_b
from fraudshield_tools.scope_terms import banned_terms_in
from fraudshield_tools.srs_tables import SrsTable, first_table_after, functional_requirement_rows

SRS_MD = REPO_ROOT / "docs/srs/FraudShield_SRS_v1_0.md"
REQUIREMENTS_YAML = REPO_ROOT / "docs/traceability/requirements.yaml"

# D-47: the SRS 05B tables were copied from an unrelated health-system project.
# Replace those fragments with the FraudShield equivalents defined in D-47.
D47_SUBSTITUTIONS: tuple[tuple[str, str], ...] = (
    ("CRITICAL patient alerts (KinyaMed) and HIGH fraud alerts (FraudShield)", "HIGH fraud alerts"),
    ("patient card (KinyaMed) or alert card (FraudShield)", "alert card"),
    ("Queue (KinyaMed) and alert feed (FraudShield)", "Alert feed"),
    ("CRITICAL triage, CONFIRM FRAUD", "CONFIRM FRAUD"),
    (
        "KinyaMed: complete triage via 3-exchange SMS; FraudShield: auto-block SMS to customer; "
        "both fully functional",
        "Auto-block SMS to customer fully functional",
    ),
    ("triage submission, analyst decision", "analyst decision"),
    ("navigator.vibrate(200) on critical urgency result; ", ""),
    (
        "symptom submission / fraud alert; queue position; SMS confirmation",
        "fraud alert; SMS confirmation",
    ),
    ("triage/alert result", "alert result"),
    (
        "Doctor queue view on mobile; patient detail full-screen; analyst alert card",
        "Alert feed on mobile; alert detail full-screen; alert card",
    ),
    ("consultation notes", "investigation notes"),
    ("Standard clinic or bank office PC", "Bank operations workstation"),
    ("hospital and bank office PC target", "bank operations workstation target"),
    ("reception desk and clinic waiting screen target", "bank operations wall display target"),
    ("27-inch clinic reception or bank trading desk display", "27-inch bank operations display"),
    ("rural health centre", "rural agent location"),
    ("Clinical / Financial Justification", "Financial Justification"),
)


@dataclass(frozen=True)
class SectionSpec:
    prefix: str
    marker: str
    section: str
    expected_rows: int


SECTIONS: tuple[SectionSpec, ...] = (
    SectionSpec("NFR-PERF", r"^4\.1\s+Performance", "4.1 Performance", 10),
    SectionSpec("NFR-SEC", r"^4\.2\s+Security", "4.2 Security", 10),
    SectionSpec("NFR-REL", r"^4\.3\s+Reliability", "4.3 Reliability", 6),
    SectionSpec("UX-REG", r"^5\.3\s+Registration", "5.3 Registration form", 10),
    SectionSpec("UX-DASH", r"^5\.4\s+Analyst", "5.4 Analyst dashboard components", 9),
    SectionSpec("MOB-PWA", r"^A\.3\s+", "05B A.3 PWA", 9),
    SectionSpec("MOB-TOUCH", r"^A\.4\s+", "05B A.4 Touch interaction", 8),
    SectionSpec("MOB-NET", r"^A\.5\s+", "05B A.5 Network resilience", 5),
    SectionSpec("MOB-PERF", r"^A\.6\s+", "05B A.6 Mobile performance", 8),
    SectionSpec("MOB-COMP", r"^A\.7\s+", "05B A.7 Mobile component behaviour", 9),
    SectionSpec("MOB-DEV", r"^A\.8\s+", "05B A.8 Cross-device testing matrix", 7),
    SectionSpec("ML-DATA", r"^7\.1\s+", "7.1 Dataset", 8),
    SectionSpec("ML-GATE", r"^7\.2\s+", "7.2 Model deployment gate", 13),
    SectionSpec("OPS-CI", r"^8\.1\s+", "8.1 CI/CD pipeline", 8),
    SectionSpec("OPS-OBS", r"^8\.2\s+", "8.2 Observability", 6),
    SectionSpec("TEST", r"^09\s+Testing", "9 Testing requirements", 14),
    SectionSpec("RES", r"^10\s+Research", "10 Research artifacts", 7),
)

FR_SECTION = {
    "01": "3.1 FR-01 Transaction ingestion API",
    "02": "3.2 FR-02 ML fraud scoring engine",
    "03": "3.3 FR-03 Risk decision and auto-block engine",
    "04": "3.4 FR-04 Analyst dashboard",
    "05": "3.5 FR-05 Risk officer panel",
    "06": "3.6 FR-06 Admin panel",
    "07": "3.7 FR-07 Authentication and authorisation",
}

# Defect → affected requirement rows (build prompt Part B). Rendered on both sides.
DEFECT_LINKS: dict[str, tuple[str, ...]] = {
    "D-01": ("ML-GATE-02",),
    "D-02": ("ML-GATE-03", "ML-GATE-04", "ML-GATE-05", "ML-GATE-06"),
    "D-03": ("FR-02-02",),
    "D-04": ("FR-02-02", "FR-01-04", "ML-DATA-07", "TEST-01"),
    "D-05": ("FR-02-03", "FR-02-04", "ML-GATE-11", "TEST-02"),
    "D-06": ("FR-02-05", "TEST-02"),
    "D-07": ("ML-DATA-01", "ML-DATA-06", "ML-GATE-01", "OPS-CI-05"),
    "D-08": ("ML-DATA-04", "RES-01"),
    "D-09": ("RES-01", "RES-02"),
    "D-10": ("FR-02-05", "FR-03-02", "FR-04-02"),
    "D-11": ("FR-02-08", "ML-GATE-13", "TEST-08"),
    "D-12": ("FR-01-03", "FR-02-01"),
    "D-13": ("FR-01-01", "FR-03-01", "NFR-PERF-02"),
    "D-14": ("FR-03-02",),
    "D-15": ("NFR-REL-02",),
    "D-16": ("FR-02-07", "ML-GATE-12", "TEST-10"),
    "D-17": ("TEST-03",),
    "D-18": ("FR-03-02", "FR-03-07", "TEST-03"),
    "D-19": ("NFR-SEC-08", "FR-06-07"),
    "D-20": ("NFR-SEC-01",),
    "D-21": ("NFR-SEC-09",),
    "D-22": ("FR-05-06",),
    "D-23": ("FR-07-03", "TEST-07"),
    "D-24": ("FR-07-01", "FR-07-02", "UX-REG-06"),
    "D-25": ("FR-03-04", "FR-03-05"),
    "D-26": ("FR-07-06",),
    "D-27": ("FR-06-02", "FR-07-04", "FR-07-09"),
    "D-28": ("NFR-SEC-10",),
    "D-29": ("MOB-PWA-05", "MOB-NET-04"),
    "D-30": ("FR-03-08",),
    "D-31": ("FR-06-07", "FR-07-08", "FR-05-06"),
    "D-32": ("FR-06-06", "NFR-SEC-05", "OPS-OBS-04"),
    "D-33": ("UX-DASH-01", "UX-DASH-03"),
    "D-34": ("UX-DASH-02", "TEST-12"),
    "D-35": ("FR-04-02", "UX-DASH-02"),
    "D-36": ("MOB-COMP-01", "MOB-COMP-02"),
    "D-37": ("MOB-TOUCH-01",),
    "D-38": ("UX-REG-04",),
    "D-39": ("MOB-PERF-06",),
    "D-40": ("MOB-PWA-09", "MOB-DEV-05"),
    "D-41": ("MOB-PWA-05", "MOB-PWA-08"),
    "D-42": ("MOB-DEV-07", "MOB-NET-05"),
    "D-43": ("FR-03-04",),
    "D-44": ("FR-04-04", "UX-DASH-06"),
    "D-45": ("FR-04-05", "UX-DASH-05", "MOB-COMP-08"),
    "D-46": ("FR-05-02",),
    "D-47": ("MOB-PWA-08", "MOB-TOUCH-03", "MOB-TOUCH-04", "MOB-TOUCH-08", "MOB-DEV-01"),
    "D-48": (),
    "D-49": ("NFR-REL-04",),
    "D-50": ("FR-06-03",),
    "D-51": ("TEST-07", "NFR-REL-06"),
}

DEFECT_MILESTONES: dict[str, str] = {
    **dict.fromkeys(("D-47", "D-48"), "M0"),
    **dict.fromkeys(("D-30", "D-31"), "M1"),
    # Re-planned out of M1 by ADR 0021 (M1 milestone review MAJOR-1): the M1 part is delivered and
    # recorded in each row's notes; the remainder needs a later component.
    "D-20": "M6",
    "D-32": "M7",
    "D-49": "M9",
    **dict.fromkeys(("D-07", "D-08"), "M2"),
    **dict.fromkeys(("D-03", "D-04"), "M3"),
    **dict.fromkeys(("D-01", "D-02", "D-05", "D-06", "D-09"), "M4"),
    **dict.fromkeys(("D-11", "D-16", "D-50"), "M5"),
    **dict.fromkeys(("D-10", "D-12", "D-13", "D-14", "D-15", "D-17", "D-18", "D-25", "D-51"), "M6"),
    **dict.fromkeys(("D-19", "D-23", "D-24", "D-26", "D-27"), "M7"),
    **dict.fromkeys(
        (
            "D-22",
            "D-29",
            "D-33",
            "D-34",
            "D-35",
            "D-36",
            "D-37",
            "D-38",
            "D-39",
            "D-40",
            "D-41",
            "D-42",
            "D-43",
            "D-44",
            "D-45",
            "D-46",
        ),
        "M8",
    ),
    **dict.fromkeys(("D-21", "D-28"), "M9"),
}

FR_MILESTONE_OVERRIDES: dict[str, str] = {
    "FR-01-07": "M1",
    "FR-02-02": "M3",
    # ADR 0027: the build prompt's M5 gate reads "FR-02-01, 02-04 ... 02-10 tests pass", which
    # includes FR-02-09, while the register filed the Redis feature store under M3. There is no
    # feature store, no Redis and no Prometheus; M5 is the milestone that builds them.
    "FR-02-09": "M5",
    "FR-02-03": "M4",
    "FR-02-04": "M4",
    "FR-06-01": "M7",
    "FR-06-02": "M7",
    "FR-06-06": "M7",
    "FR-06-07": "M7",
    "FR-06-05": "M9",
}

FR_GROUP_MILESTONE = {
    "01": "M6",
    "02": "M5",
    "03": "M6",
    "04": "M8",
    "05": "M8",
    "06": "M8",
    "07": "M7",
}

SECTION_MILESTONE = {
    "NFR-PERF": "M10",
    "NFR-SEC": "M9",
    "NFR-REL": "M6",
    "UX-REG": "M8",
    "UX-DASH": "M8",
    "MOB-PWA": "M8",
    "MOB-TOUCH": "M8",
    "MOB-NET": "M8",
    "MOB-PERF": "M8",
    "MOB-COMP": "M8",
    "MOB-DEV": "M10",
    "ML-DATA": "M2",
    "ML-GATE": "M4",
    "OPS-CI": "M9",
    "OPS-OBS": "M9",
    "TEST": "M10",
    "RES": "M11",
}

ROW_MILESTONE_OVERRIDES = {
    # ADR 0024: the SRS files "all 44 features computable" under the dataset milestone, but the
    # build prompt's own definitions place feature engineering in M3 ("M3 -- Feature engineering
    # and feature store (Part E.2)", gate: "all 44 feature unit tests pass for all 6 channels")
    # while M2's gate names rows, distribution targets, leakage and the datasheet and says nothing
    # about features. A requirement filed under the wrong milestone, of the same family as D-01.
    # ADR 0027: a completeness requirement cannot be judged before the data it counts exists.
    # Six of the 44 are missing for 100% of records; kyc_tier and the two agent features need
    # tables M6 builds, and the two account-age features need the per-account durable table
    # PB-37 records as absent. M6 is the last of the enabling milestones.
    "ML-DATA-07": "M6",
    "NFR-SEC-05": "M1",
    "NFR-SEC-06": "M6",
    "NFR-SEC-03": "M7",  # ADR 0021: vault and tokenisation M6, analyst inspector test M7
    "ML-GATE-12": "M5",
    "ML-GATE-13": "M5",
    # ADR 0027: three of TEST-01's four named scenarios are covered; the fourth needs
    # round_sum_flag, whose source-data gap the registry schedules for M4.
    "TEST-01": "M4",
    "TEST-02": "M4",
    "TEST-03": "M6",
    "TEST-04": "M7",
    "TEST-05": "M6",
    "TEST-06": "M8",
    "TEST-07": "M7",
    "TEST-08": "M5",
    "TEST-10": "M5",
    "TEST-11": "M8",
    "TEST-12": "M8",
    "TEST-13": "M9",
    "TEST-14": "M4",
    "RES-01": "M2",
    "RES-02": "M2",
}

# Rows verified by inspection or by an external party rather than an automated test
# (ADR 0004). Everything else defaults to verification "test".
VERIFICATION_OVERRIDES = {
    "D-48": "inspection",
    "NFR-SEC-09": "external",
    "NFR-SEC-10": "external",
    "NFR-PERF-09": "external",
    "MOB-DEV-07": "manual",
    "RES-07": "inspection",
}

# Non-FR priorities (ADR 0004): SRS tables 4.x, 7.x, 8.x and 9 gate deployment and are M.
# 05B rows follow the priority of the functional requirement they serve.
PRIORITY_OVERRIDES = {
    "UX-DASH-07": "S",  # MEDIUM timer badge serves FR-03-02 (S)
    "UX-DASH-09": "S",  # behavioural fingerprint serves FR-04-07 (S)
    "MOB-PWA-05": "S",  # background sync; Chromium-only (D-29, D-41)
    "MOB-PWA-08": "S",  # push is never the only alert path (D-41)
    "MOB-TOUCH-03": "S",
    "MOB-TOUCH-04": "S",
    "MOB-TOUCH-08": "S",
}

PROGRESS_FIELDS = (
    "status",
    "implementation",
    "evidence",
    "deviations",
    "verification",
    "notes",
    "blocked_reason",
    "reduced_scale",
)


class SeedError(ValueError):
    """Raised when the SRS extraction does not match expectations."""


def apply_d47(text: str) -> str:
    for old, new in D47_SUBSTITUTIONS:
        text = text.replace(old, new)
    return text


def _clean(text: str) -> str:
    return re.sub(r"\s+", " ", apply_d47(text)).strip()


def _fr_milestone(req_id: str) -> str:
    return FR_MILESTONE_OVERRIDES.get(req_id, FR_GROUP_MILESTONE[req_id[3:5]])


def _row_text(table: SrsTable, row: tuple[str, ...]) -> str:
    pairs = [f"{h}: {c}" for h, c in zip(table.header[1:], row[1:], strict=False) if c]
    return _clean("; ".join(pairs))


def _defects_for(req_id: str) -> list[str]:
    return sorted(d for d, targets in DEFECT_LINKS.items() if req_id in targets)


def build_rows(srs_text: str, prompt_text: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for fr in functional_requirement_rows(srs_text):
        req_id = fr[0]
        rows.append(
            {
                "id": req_id,
                "section": FR_SECTION[req_id[3:5]],
                "title": _clean(fr[1]),
                "priority": fr[2],
                "milestone": _fr_milestone(req_id),
                "srs_text": _clean(f"Acceptance criterion: {fr[3]}"),
                "defects": _defects_for(req_id),
            }
        )
    for spec in SECTIONS:
        table = first_table_after(srs_text, spec.marker)
        if len(table.rows) != spec.expected_rows:
            found = len(table.rows)
            raise SeedError(f"{spec.section}: expected {spec.expected_rows} rows, got {found}")
        for n, row in enumerate(table.rows, start=1):
            req_id = f"{spec.prefix}-{n:02d}"
            rows.append(
                {
                    "id": req_id,
                    "section": spec.section,
                    "title": _clean(row[0]),
                    "priority": PRIORITY_OVERRIDES.get(req_id, "M"),
                    "milestone": ROW_MILESTONE_OVERRIDES.get(
                        req_id, SECTION_MILESTONE[spec.prefix]
                    ),
                    "srs_text": _row_text(table, row),
                    "defects": _defects_for(req_id),
                }
            )
    part_b = extract_part_b(prompt_text)
    for match in DEFECT_HEADING.finditer(part_b):
        defect_id = match.group(1)
        rows.append(
            {
                "id": defect_id,
                "section": "Build prompt Part B defect register",
                "title": _defect_title(match.group(2)),
                "priority": "M",
                "milestone": DEFECT_MILESTONES.get(defect_id, "M4"),
                "srs_text": "See docs/srs/defect_register.md",
                "defects": [],
                "affects": list(DEFECT_LINKS[defect_id]),
            }
        )
    _assert_no_banned_terms(rows)
    return rows


def _defect_title(raw: str) -> str:
    return re.sub(r"\*\*|`", "", raw).strip()


# D-47's title quotes the out-of-scope vocabulary it forbids; it is the only row allowed to.
ROWS_ALLOWED_OUT_OF_SCOPE_TERMS = frozenset({"D-47"})


def _assert_no_banned_terms(rows: list[dict[str, Any]]) -> None:
    leaks = {
        row["id"]: banned_terms_in(f"{row['title']} {row['srs_text']}")
        for row in rows
        if row["id"] not in ROWS_ALLOWED_OUT_OF_SCOPE_TERMS
        and banned_terms_in(f"{row['title']} {row['srs_text']}")
    }
    if leaks:
        raise SeedError(f"D-47 substitutions incomplete: {leaks}")


def merge(fresh: list[dict[str, Any]], existing: list[dict[str, Any]]) -> list[dict[str, Any]]:
    previous = {row["id"]: row for row in existing}
    merged: list[dict[str, Any]] = []
    for row in fresh:
        old = previous.get(row["id"], {})
        progress = {field: old[field] for field in PROGRESS_FIELDS if field in old}
        merged.append(
            {
                **row,
                "status": progress.pop("status", "NOT_STARTED"),
                "verification": progress.pop(
                    "verification", VERIFICATION_OVERRIDES.get(row["id"], "test")
                ),
                "implementation": progress.pop("implementation", []),
                "evidence": progress.pop("evidence", []),
                "deviations": progress.pop("deviations", []),
                **progress,
            }
        )
    return merged


def load_rows(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    document = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    rows: list[dict[str, Any]] = document.get("requirements", [])
    return rows


YAML_HEADER = """# FraudShield requirements traceability (build prompt D.2).
# SRS-derived fields are refreshed by: uv run python -m fraudshield_tools.traceability_seed
# Progress fields (status, implementation, evidence, deviations, verification, notes)
# are edited by hand as work lands. Tests are NOT listed here: they are discovered
# from tags in code (JUnit @Tag, pytest.mark.req, "[ID]" title prefix) by fs-traceability.
"""


def render_yaml(rows: list[dict[str, Any]]) -> str:
    body = yaml.safe_dump({"requirements": rows}, sort_keys=False, allow_unicode=True, width=100)
    return YAML_HEADER + body


def dump(rows: list[dict[str, Any]], path: Path) -> None:
    path.write_text(render_yaml(rows), encoding="utf-8")


def main(argv: list[str] | None = None, root: Path = REPO_ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="fail if SRS-derived fields, the row set or the YAML layout differ from a fresh seed",
    )
    args = parser.parse_args(argv)
    srs = (root / SRS_MD.relative_to(REPO_ROOT)).read_text(encoding="utf-8")
    prompt = (root / PROMPT_PATH.relative_to(REPO_ROOT)).read_text(encoding="utf-8")
    target = root / REQUIREMENTS_YAML.relative_to(REPO_ROOT)
    expected = render_yaml(merge(build_rows(srs, prompt), load_rows(target)))
    if args.check:
        current = target.read_text(encoding="utf-8") if target.exists() else ""
        if current != expected:
            print(
                f"{REQUIREMENTS_YAML.relative_to(REPO_ROOT)} differs from the SRS-derived seed "
                "(edited title/section/priority/milestone/defects, added or removed rows, or "
                "unseeded layout); fix the SRS source or seeder mappings under review, then run: "
                "uv run fs-traceability-seed",
                file=sys.stderr,
            )
            return 1
        print("traceability seed up to date")
        return 0
    target.write_text(expected, encoding="utf-8")
    print(f"seeded {expected.count(chr(10) + '- id: ')} requirement rows")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
