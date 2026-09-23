"""Generate docs/srs/defect_register.md from Part B of the master build prompt.

The register is generated rather than hand-copied so it can never drift from the
binding resolutions it mirrors. Run with ``--check`` in CI to detect drift.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from fraudshield_tools import REPO_ROOT

PROMPT_PATH = REPO_ROOT / "docs/prompts/FraudShield_Master_Build_Prompt.md"
REGISTER_PATH = REPO_ROOT / "docs/srs/defect_register.md"

PART_B_START = "## PART B — SRS DEFECT REGISTER AND BINDING RESOLUTIONS"
PART_C_START = "## PART C —"
DEFECT_HEADING = re.compile(r"^\*\*(D-\d{2}) — (.+?)\*\*", re.MULTILINE)
EXPECTED_DEFECT_COUNT = 54

HEADER = """<!-- GENERATED FILE — do not edit.
     Source: docs/prompts/FraudShield_Master_Build_Prompt.md Part B.
     Regenerate with: uv run fs-defect-register -->

# FraudShield SRS Defect Register

Binding resolutions for contradictions, impossibilities and risks found in
`docs/srs/FraudShield_SRS_v1_0.docx`. Precedence (build prompt A.4): these
resolutions override SRS acceptance criteria and requirement text. Every
affected ADR, test and traceability row references the defect ID.

Status of each resolution is tracked in `docs/traceability/requirements.yaml`
(rows `D-01` … `D-51`), not in this file.

"""


class DefectRegisterError(ValueError):
    """Raised when Part B cannot be extracted faithfully."""


def extract_part_b(prompt_text: str) -> str:
    start = prompt_text.find(PART_B_START)
    end = prompt_text.find(PART_C_START)
    if start < 0 or end < 0 or end <= start:
        raise DefectRegisterError("Part B boundaries not found in build prompt")
    body = prompt_text[start + len(PART_B_START) : end].strip()
    return body.removesuffix("---").strip()


def defect_ids(part_b: str) -> list[str]:
    return [match.group(1) for match in DEFECT_HEADING.finditer(part_b)]


def render(prompt_text: str) -> str:
    part_b = extract_part_b(prompt_text)
    ids = defect_ids(part_b)
    expected = [f"D-{n:02d}" for n in range(1, EXPECTED_DEFECT_COUNT + 1)]
    if ids != expected:
        missing = sorted(set(expected) - set(ids))
        raise DefectRegisterError(f"Defect IDs not contiguous D-01..D-51; missing={missing}")
    return HEADER + part_b + "\n"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the register is stale")
    args = parser.parse_args(argv)
    content = render(PROMPT_PATH.read_text(encoding="utf-8"))
    if args.check:
        current = REGISTER_PATH.read_text(encoding="utf-8") if REGISTER_PATH.exists() else ""
        if current != content:
            print(f"{REGISTER_PATH} is stale; run: uv run fs-defect-register", file=sys.stderr)
            return 1
        print(f"defect register up to date ({EXPECTED_DEFECT_COUNT} defects)")
        return 0
    Path(REGISTER_PATH).write_text(content, encoding="utf-8")
    print(f"wrote {REGISTER_PATH.relative_to(REPO_ROOT)} ({EXPECTED_DEFECT_COUNT} defects)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
