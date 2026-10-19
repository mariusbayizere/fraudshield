"""Fail if a paper section states a result without going through numbers.tex.

Every number the paper states must pass the claims register gate (MEASURED with a commit and
run, or SOURCED with a checkable citation). numbers.tex is where each number is recorded with its
provenance; sections refer to it through macros. This check finds numbers typed directly into a
section, which would bypass that record:

* decimals (``0.970``), percentages (``9.1\\%``) and comma-grouped integers (``170{,}000``);
* bare integers (``51``, ``15``, ``40~ms``), and numbers written as words (``eight``,
  ``thirteenth``, ``fivefold``), because the owner's rule is that every number in the paper is an
  audited macro or is not there. ``one``, ``first`` and ``second`` are exempt: they are used as
  articles and ordinals of sequence far more often than as counts, and a count of one is written
  through a macro where it is a result.

Identifiers that merely look numeric are allowed: requirement and defect IDs (``D-15``,
``ML-GATE-01``, ``PB-61``), ADR numbers, SRS and build-prompt section references (``SRS 8.2``,
``Part E.13``), milestone names (``M4``), and anything inside ``\\texttt{}`` (commit hashes, file
names) or a citation's locator (``\\cite[Section~5.1]{key}``). Integers written as words
Calendar dates, citation page and figure locators, layout dimensions, standard and library names
(``ISO 4217``, ``SHA-256``, ``H3``, ``float64``) and the asymptotic bound ``O(TLD^2)`` are not
numbers the paper claims and are stripped first. ``main.tex`` is checked from ``\\begin{document}``.

Usage: ``python3 docs/research/paper/check_numbers.py`` (exit 1 on any finding).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
#: Generated files are checked by their generator's --check instead: their numbers are quotations.
SECTIONS = [
    HERE / "main.tex",
    *sorted(p for p in (HERE / "sections").glob("*.tex") if not p.name.startswith("generated-")),
]

STRIP = [
    re.compile(r"(?<!\\)%.*$", re.M),  # comments
    re.compile(r"\\(?:texttt|url|href|cite|ref|label|input|notyet)(?:\[[^\]]*\])?\{[^}]*\}"),
    re.compile(r"\\n[A-Z][A-Za-z]*"),  # number macros from numbers.tex
    re.compile(r"\b(?:SRS|Part|section|Section|Sections|E|B|C|D|A|H|I)\s?[A-J]?\.?\d+(?:\.\d+)*"),
    re.compile(r"\b[A-Z]{1,4}(?:-[A-Z]{1,6})*-\d+[a-z]?\b"),  # D-15, ML-GATE-01, PB-61, C-6
    re.compile(r"\bADR[\s~]+\d{4}\b"),
    re.compile(r"\b\d{4}-\d{2}-\d{2}\b"),  # ISO dates, before years are stripped
    re.compile(r"\bM\d{1,2}\b"),
    re.compile(r"\d+(?:\.\d+)?\\(?:linewidth|textwidth|columnwidth)"),  # layout, not results
    re.compile(r"\b(?:CC BY|Apache-)\s?\d\.\d\b"),  # licence versions
    re.compile(r"\d+(?:\.\d+)?\s*(?:cm|pt|mm|em|ex)\b"),  # layout dimensions
    re.compile(r"\\(?:begin|end)\{[^}]*\}(?:\{[^}]*\})?"),  # environments and column specs
    re.compile(
        r"\b\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|"
        r"October|November|December)\s+\d{4}\b"
    ),  # dates
    re.compile(r"\b(?:19|20)\d{2}(?:--(?:19|20)?\d{2,4})?\b"),  # years and year ranges
    re.compile(r"\b(?:pages?|Figure|Table|Tables|Annex)\s+[A-Z]?\d+(?:\s*(?:,|and)\s*[A-Z]?\d+)*"),
    re.compile(r"\b(?:ISO\s+4217|SHA-256|H3|float64)\b"),  # standard and library names
    re.compile(r"O\([^)]*\)"),  # asymptotic bound
    re.compile(r"-?\d+\\fbox\w+|\\\\\[\d+pt\]|\\addlinespace\[[^\]]*\]"),  # box and row spacing
    re.compile(r"\bseed[ -~]+\d+\b|\bseed-\d+\b", re.I),  # a run's label, not a count
    re.compile(r"\$?\[0,\s*1\]\$?"),  # the unit interval, as notation
    re.compile(r"\\multicolumn\{\d+\}"),
    re.compile(r"1\s*-\s*\\mathrm\{AUC\}"),  # notation in max(AUC, 1 - AUC)
    re.compile(r"\bhalf-width\b"),  # the name of an interval statistic
]
NUMBER_WORDS = (
    r"two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|"
    r"sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|"
    r"hundreds?|thousands?|millions?|billions?|dozens?|twice|half|halves|quarter|"
    r"third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|"
    r"(?:two|three|four|five|six|seven|eight|nine|ten)fold|tens"
)
FORBIDDEN = re.compile(
    r"\d+\.\d+|\d+\s*\\%|\d{1,3}\{,\}\d{3}|\d{1,3},\d{3}|(?<![\w\\])\d+(?!\w)"
    rf"|\b(?:{NUMBER_WORDS})\b",
    re.I,
)


def findings(path: Path) -> list[str]:
    found: list[str] = []
    lines = path.read_text().splitlines()
    start = next((i for i, line in enumerate(lines) if "\\begin{document}" in line), -1) + 1
    for number, line in enumerate(lines, start=1):
        if number <= start:
            continue
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
