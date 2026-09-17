"""Fail if terms from the unrelated health-system project appear in the repository (D-47).

Only the verbatim SRS sources and the build prompt are exempt, because they must be
kept exactly as supplied by the author.
"""

from __future__ import annotations

import sys
from pathlib import Path

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.scope_terms import banned_terms_in
from fraudshield_tools.traceability import tracked_files

EXEMPT_PREFIXES: tuple[str, ...] = (
    "docs/srs/",
    "docs/prompts/",
    "tools/src/fraudshield_tools/scope_terms.py",
    "tools/src/fraudshield_tools/traceability_seed.py",
)
BINARY_SUFFIXES = {
    ".docx",
    ".png",
    ".jpg",
    ".jpeg",
    ".gif",
    ".ico",
    ".woff2",
    ".pdf",
    ".jar",
    ".pmtiles",
}


def violations(root: Path, files: list[Path]) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for path in files:
        rel = path.relative_to(root).as_posix()
        if rel.startswith(EXEMPT_PREFIXES) or path.suffix.lower() in BINARY_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        terms = banned_terms_in(f"{rel}\n{text}")
        if terms:
            found[rel] = terms
    return found


def main() -> int:
    found = violations(REPO_ROOT, tracked_files(REPO_ROOT))
    for rel, terms in sorted(found.items()):
        print(f"ERROR {rel}: out-of-scope terms {terms} (D-47)", file=sys.stderr)
    print(f"scope-guard: {len(found)} files with out-of-scope terms")
    return 1 if found else 0


if __name__ == "__main__":
    raise SystemExit(main())
