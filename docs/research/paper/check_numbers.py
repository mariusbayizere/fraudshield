"""Fail if a paper section states a result without going through numbers.tex.

Every number the paper states must pass the claims register gate (MEASURED with a commit and
run, or SOURCED with a checkable citation). numbers.tex is where each number is recorded with its
provenance; sections refer to it through macros. This check finds numbers typed directly into a
section, which would bypass that record:

* decimals (``0.970``), percentages (``9.1\\%``) and comma-grouped integers (``170{,}000``).

Identifiers that merely look numeric are allowed: requirement and defect IDs (``D-15``,
``ML-GATE-01``, ``PB-61``), ADR numbers, SRS and build-prompt section references (``SRS 8.2``,
``Part E.13``), milestone names (``M4``), and anything inside ``\\texttt{}`` (commit hashes, file
names) or a citation's locator (``\\cite[Section~5.1]{key}``). Integers written as words
in prose are the author's responsibility.

Usage: ``python3 docs/research/paper/check_numbers.py`` (exit 1 on any finding).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SECTIONS = sorted((HERE / "sections").glob("*.tex"))

STRIP = [
    re.compile(r"(?<!\\)%.*$", re.M),  # comments
    re.compile(r"\\(?:texttt|url|href|cite|ref|label|input|notyet)(?:\[[^\]]*\])?\{[^}]*\}"),
    re.compile(r"\\n[A-Z][A-Za-z]*"),  # number macros from numbers.tex
    re.compile(r"\b(?:SRS|Part|section|Section|Sections|E|B|C|D|A|H|I)\s?[A-J]?\.?\d+(?:\.\d+)*"),
    re.compile(r"\b[A-Z]{1,4}(?:-[A-Z]{1,6})*-\d+[a-z]?\b"),  # D-15, ML-GATE-01, PB-61, C-6
    re.compile(r"\bADR\s+\d{4}\b"),
    re.compile(r"\bM\d{1,2}\b"),
    re.compile(r"\d+(?:\.\d+)?\\(?:linewidth|textwidth|columnwidth)"),  # layout, not results
    re.compile(r"\b(?:CC BY|Apache-)\s?\d\.\d\b"),  # licence versions
]
FORBIDDEN = re.compile(r"\d+\.\d+|\d+\s*\\%|\d{1,3}\{,\}\d{3}|\d{1,3},\d{3}")


def findings(path: Path) -> list[str]:
    found: list[str] = []
    for number, line in enumerate(path.read_text().splitlines(), start=1):
        text = line
        for pattern in STRIP:
            text = pattern.sub(" ", text)
        found.extend(
            f"{path.relative_to(HERE)}:{number}: '{match.group()}' is not from numbers.tex"
            for match in FORBIDDEN.finditer(text)
        )
    return found


def main() -> int:
    problems = [problem for path in SECTIONS for problem in findings(path)]
    for problem in problems:
        print(problem, file=sys.stderr)
    print(f"check-numbers: {len(SECTIONS)} sections, {len(problems)} direct number(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
