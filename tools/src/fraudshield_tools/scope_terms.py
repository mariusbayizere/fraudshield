"""Terms from the unrelated health-system project that must not appear in FraudShield (D-47).

Section 05B of the SRS was copied from another project and mentions its domain.
The build prompt (A.3 rule 9, D-47) forbids importing any of that naming.
"""

from __future__ import annotations

import re

BANNED_TERMS: tuple[str, ...] = (
    "kinyamed",
    "healthguard",
    "patient",
    "triage",
    "clinic",
    "clinical",
    "hospital",
    "health centre",
    "health center",
    "symptom",
    "doctor",
    "consultation",
)

# Letter boundaries (not \b) so identifiers such as snake_case and kebab-case names match;
# camelCase is split first so PascalCase component names match too.
BANNED_PATTERN = re.compile(
    r"(?<![a-z])(" + "|".join(re.escape(term) for term in BANNED_TERMS) + r")s?(?![a-z])",
    re.IGNORECASE,
)
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z])(?=[A-Z])")


def banned_terms_in(text: str) -> list[str]:
    words = _CAMEL_BOUNDARY.sub(" ", text)
    return sorted({match.group(1).lower() for match in BANNED_PATTERN.finditer(words)})
