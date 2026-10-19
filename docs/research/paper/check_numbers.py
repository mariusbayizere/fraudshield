"""Fail if the paper states a number without going through numbers.tex.

The owner's rule is that every number in the paper is an audited macro or is not there.
numbers.tex records each number with its provenance and audit_numbers.py checks it; sections refer
to it through macros. This check finds numbers typed directly into the text, which would bypass
that record: decimals (``0.970``), percentages (``9.1\\%``), grouped and bare integers
(``170{,}000``, ``51``), integers next to letters (``99th``, ``p95``, ``40ms``, ``5x``), and numbers
written as words (``eight``, ``thirteenth``, ``fivefold``, ``double``).

``one``, ``first``, ``second``, ``once``, ``single`` and ``pair`` are exempt: in this paper they
are articles, ordinals of sequence or adjectives (``a single feature``) far more often than counts,
and a count that is a result is written through a macro. Anything else that merely looks numeric is
stripped before the search, each by a narrow pattern: requirement, defect and backlog identifiers
(``D-15``, ``ML-GATE-01``, ``PB-61``), ADR numbers, exit criteria (``E1``), milestones (``M4``),
``\\texttt{}`` (commit hashes, file names), citations and their locators, cross-references,
calendar dates, the years named in the cited sources' titles, the report pages, annex tables
and tables a source cites, layout
dimensions inside braces, standard, library and metric names (``ISO 4217``, ``SHA-256``,
``H3``, ``float64``, ``F1``), the bound ``O(TLD^2)``, the label of a run (``seed 1``), the
unit interval and the name of one lab-notebook entry. ``main.tex`` is checked from
``\\begin{document}``.

The two files named in GENERATED are skipped because their generators check them: Appendix B's
table (``appendix_features.py --check``) and Appendix D's index (``audit_numbers.py --check``).

Usage: ``python3 docs/research/paper/check_numbers.py`` (exit 1 on any finding).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
#: Written by a generator whose --check compares them with their source, so skipped by name only.
GENERATED = ("generated-feature-table.tex", "generated-evidence-index.tex")
SECTIONS = [
    HERE / "main.tex",
    *sorted(p for p in (HERE / "sections").glob("*.tex") if p.name not in GENERATED),
]

STRIP = [
    re.compile(r"(?<!\\)%.*$", re.M),  # comments
    re.compile(r"\\(?:texttt|url|href|cite|ref|label|input)(?:\[[^\]]*\])?\{(?:\\.|[^{}\\])*\}"),
    re.compile(r"\\notyet\{M\d{1,2}\}"),  # a milestone, the only thing \notyet takes
    re.compile(r"\\deferred\{M\d{1,2}\}"),  # likewise for \deferred
    re.compile(r"\\n[A-Z][A-Za-z]*"),  # number macros from numbers.tex
    re.compile(r"\b[A-Z]{1,4}(?:-[A-Z]{1,6})*-\d+[a-z]?\b"),  # D-15, ML-GATE-01, PB-61, C-6
    re.compile(r"\bADR[\s~]+\d{4}\b"),
    re.compile(r"\bE\d{1,2}\b"),  # exit criteria
    re.compile(r"\bM\d{1,2}\b"),  # milestones
    # a pre-specification: two commits and their dates, checked by audit_numbers.py
    re.compile(r"\\prespec(?:\{[0-9a-f]{7}\}\{\d{4}-\d{2}-\d{2}\}){2}"),
    re.compile(r"\\notprespec(?:\{[0-9a-f]{7}\}){2}"),
    re.compile(r"\\rebasedprespec(?:\{[0-9a-f]{7}\}){2}\{\d{4}-\d{2}-\d{2}\}"),
    re.compile(r"\b\d{4}-\d{2}-\d{2}\b"),  # ISO dates, before years are stripped
    re.compile(
        r"\b\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|"
        r"October|November|December)\s+\d{4}\b"
    ),  # dates
    re.compile(r"\b(?:2022 census|2024--2025 calendar|Rwanda 2024)\b"),  # the cited sources' years
    re.compile(r"\b(?:Tables?|Annex)\s+[A-Z]\d+(?:\s*(?:,|and)\s*[A-Z]\d+)*"),  # H1, G2
    re.compile(r"\bH\d(?=\s*\((?:printed\s+)?page)"),  # an annex table before its page
    re.compile(r"\b(?:Table|Figure)\s+\d+(?=\s*\((?:printed\s+)?page)"),  # a source's own
    re.compile(r"\(?(?:printed\s+)?pages?\s+\d+(?:\s+of the PDF)?\)?"),  # a source's pages
    re.compile(r"(?<=[{\[])\d+(?:\.\d+)?\s*(?:cm|pt|mm|em|ex)(?=[}\]])"),  # layout dimensions
    re.compile(r"\d+(?:\.\d+)?\\(?:linewidth|textwidth|columnwidth)"),  # layout widths
    re.compile(r"-?\d+\\fbox\w+|\\addlinespace\[[^\]]*\]"),  # box and row spacing
    re.compile(r"\\begin\{(?:tabular|longtable)\}\{[^}]*(?:\{[^}]*\}[^}]*)*\}"),  # column specs
    re.compile(r"\\(?:begin|end)\{[^}]*\}"),  # environment names
    re.compile(r"\\multicolumn\{\d+\}"),
    re.compile(r"\b(?:CC BY|Apache-)\s?\d\.\d\b"),  # licence versions
    re.compile(r"\b(?:ISO\s+4217|SHA-256|H3|float64|F1)\b"),  # standard, library, metric names
    re.compile(r"\$O\(TLD\^2\)\$"),  # the TreeSHAP cost bound, as notation
    re.compile(r"\bseed[ ~-]\d\b"),  # a run's label, not a count
    re.compile(r"\$?\[0,\s*1\]\$?"),  # the unit interval, as notation
    re.compile(r"1\s*-\s*\\mathrm\{AUC\}"),  # notation in max(AUC, 1 - AUC)
    re.compile(r"\bhalf-width\b"),  # the name of an interval statistic
    re.compile(r"``a guard with two doors''"),  # the title of a lab-notebook entry
]
NUMBER_WORDS = (
    r"zero|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|"
    r"sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|"
    r"hundreds?|thousands?|millions?|billions?|dozens?|twice|thrice|half|halves|quarters?|"
    r"double|triple|quadruple|tens|"
    r"third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|thirteenth|"
    r"fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth|"
    r"fortieth|fiftieth|sixtieth|seventieth|eightieth|ninetieth|hundredths?|thousandths?|"
    r"millionths?|billionths?|trillionths?|trillions?|twelfths?|"
    r"(?:doubl|tripl|quadrupl|quintupl|sextupl)(?:e|es|ed|ing)|halved|halving|"
    r"(?:third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)s|"
    r"\w+fold"
)
FORBIDDEN = re.compile(
    r"\d+\.\d+|\d+\s*\\%|\d{1,3}\{,\}\d{3}|\d{1,3},\d{3}|(?<![\\\d])\d+"
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
