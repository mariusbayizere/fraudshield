"""Parse the Markdown extraction of the SRS into tables keyed by section marker.

The extraction (docs/srs/FraudShield_SRS_v1_0.md) is produced from the .docx with
tables rendered as pipe tables and ``<br>`` for in-cell paragraph breaks.
"""

from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class SrsTable:
    header: tuple[str, ...]
    rows: tuple[tuple[str, ...], ...]


class SrsParseError(ValueError):
    """Raised when an expected SRS section or table is missing."""


# FR-01: 7, FR-02: 10, FR-03: 8, FR-04: 12, FR-05: 7, FR-06: 7, FR-07: 9
EXPECTED_FUNCTIONAL_REQUIREMENTS = 60

_SEPARATOR = re.compile(r"^\|(?:-+\|)+$")


def _cells(line: str) -> tuple[str, ...]:
    inner = line.strip()[1:-1]
    parts = re.split(r"(?<!\\)\|", inner)
    return tuple(p.strip().replace("\\|", "|").replace(" <br> ", " / ") for p in parts)


def tables_in(lines: list[str]) -> list[SrsTable]:
    tables: list[SrsTable] = []
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        if line.startswith("|") and i + 1 < len(lines) and _SEPARATOR.match(lines[i + 1].strip()):
            header = _cells(line)
            rows: list[tuple[str, ...]] = []
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(_cells(lines[i]))
                i += 1
            tables.append(SrsTable(header=header, rows=tuple(rows)))
            continue
        i += 1
    return tables


def first_table_after(text: str, marker_regex: str) -> SrsTable:
    """Return the first table that follows the line matching ``marker_regex``."""
    lines = text.splitlines()
    pattern = re.compile(marker_regex)
    for index, line in enumerate(lines):
        if pattern.match(line.strip()):
            found = tables_in(lines[index + 1 :])
            if not found:
                raise SrsParseError(f"no table after marker {marker_regex!r}")
            return found[0]
    raise SrsParseError(f"marker not found: {marker_regex!r}")


def functional_requirement_rows(text: str) -> list[tuple[str, ...]]:
    rows = [
        row
        for table in tables_in(text.splitlines())
        if table.header[:1] == ("ID",)
        for row in table.rows
        if re.fullmatch(r"FR-\d{2}-\d{2}", row[0])
    ]
    if len(rows) != EXPECTED_FUNCTIONAL_REQUIREMENTS:
        expected = EXPECTED_FUNCTIONAL_REQUIREMENTS
        raise SrsParseError(f"expected {expected} functional requirements, found {len(rows)}")
    return rows
