"""Fail if vocabulary from the unrelated health-system project appears in the repository (D-47).

Two exemption mechanisms, both reviewed rather than silent (ADR 0008):

* ``ALLOWLIST`` — files or directories that must quote the vocabulary: verbatim source
  documents, the generated register and traceability files, the guard's own term list, the
  seeder's substitution table, the D-47 decision record, and review records.
* A line pragma ``scope-guard: allow D-47`` on the same line, for a sentence that discusses
  the defect itself (for example in a walkthrough). The pragma must name D-47.

Content is never rephrased merely to avoid the guard.
"""

from __future__ import annotations

import sys
from pathlib import Path

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.repo import tracked_files
from fraudshield_tools.scope_terms import banned_terms_in

ALLOWLIST: dict[str, str] = {
    "docs/srs/": "verbatim SRS source, its extraction, and the register generated from Part B",
    "docs/prompts/": "verbatim build specification supplied by the author",
    "docs/traceability/requirements.yaml": (
        "generated; the seeder asserts only the D-47 row carries these terms and "
        "fs-traceability-seed --check proves the file equals the generated output"
    ),
    "docs/traceability/requirements_matrix.md": "generated from requirements.yaml",
    "docs/adr/0008-out-of-scope-content-guard.md": "decision record for D-47 itself",
    "docs/reviews/": "review records quote findings verbatim and are never edited to pass a lint",
    "tools/src/fraudshield_tools/scope_terms.py": "the list of banned terms",
    "tools/src/fraudshield_tools/traceability_seed.py": "D-47 substitution table",
}
LINE_PRAGMA = "scope-guard: allow D-47"
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


def _allowlisted(rel: str) -> bool:
    return any(
        rel == entry or (entry.endswith("/") and rel.startswith(entry)) for entry in ALLOWLIST
    )


def violations(root: Path, files: list[Path]) -> dict[str, list[str]]:
    found: dict[str, list[str]] = {}
    for path in files:
        rel = path.relative_to(root).as_posix()
        if _allowlisted(rel) or path.suffix.lower() in BINARY_SUFFIXES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        checked = "\n".join(line for line in text.splitlines() if LINE_PRAGMA not in line)
        terms = banned_terms_in(f"{rel}\n{checked}")
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
