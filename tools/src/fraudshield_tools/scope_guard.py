"""Fail if vocabulary from the unrelated health-system project appears in the repository (D-47).

Exemptions, all reviewed rather than silent (ADR 0008):

* ``CONTENT_ALLOWLIST`` — files or directories whose content must quote the vocabulary:
  verbatim source documents, the guard's own term list, the seeder's substitution table, the
  D-47 decision record, and review records. File *paths* are checked everywhere.
* The generated traceability YAML and matrix are scanned in full (including progress fields
  such as ``notes``), except the D-47 row's verbatim title.
* A line pragma ``scope-guard: allow D-47`` exempts that line, only in Markdown under ``docs/``.

Content is never rephrased merely to avoid the guard.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.repo import tracked_files
from fraudshield_tools.scope_terms import banned_terms_in

# Files whose *content* must quote the vocabulary. File paths are always checked.
CONTENT_ALLOWLIST: dict[str, str] = {
    "docs/srs/": "verbatim SRS source, its extraction, and the register generated from Part B",
    "docs/prompts/": "verbatim build specification supplied by the author",
    "docs/adr/0008-out-of-scope-content-guard.md": "decision record for D-47 itself",
    "docs/reviews/": "review records quote findings verbatim and are never edited to pass a lint",
    "tools/src/fraudshield_tools/scope_terms.py": "the list of banned terms",
    "tools/src/fraudshield_tools/traceability_seed.py": "D-47 substitution table",
}
# Generated traceability files are scanned in full except the D-47 row's verbatim title, the
# only text the seeder allows to carry these terms.
TRACEABILITY_YAML = "docs/traceability/requirements.yaml"
TRACEABILITY_MATRIX = "docs/traceability/requirements_matrix.md"
DEFECT_ROW_WITH_TERMS = "D-47"
LINE_PRAGMA = "scope-guard: allow D-47"
PRAGMA_SCOPE = re.compile(r"^docs/.+\.md$")
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


def _content_allowlisted(rel: str) -> bool:
    return any(
        rel == entry or (entry.endswith("/") and rel.startswith(entry))
        for entry in CONTENT_ALLOWLIST
    )


def _defect_title(text: str) -> str | None:
    document = yaml.safe_load(text) or {}
    for row in document.get("requirements", []):
        if row.get("id") == DEFECT_ROW_WITH_TERMS:
            return str(row.get("title", ""))
    return None


def _scannable_content(rel: str, text: str, d47_title: str | None) -> str:
    if _content_allowlisted(rel):
        return ""
    if rel in {TRACEABILITY_YAML, TRACEABILITY_MATRIX} and d47_title:
        d47_line_prefixes = (f"title: {d47_title}", f"| {DEFECT_ROW_WITH_TERMS} |")
        lines = [
            line.replace(d47_title, "") if line.strip().startswith(d47_line_prefixes) else line
            for line in text.splitlines()
        ]
        return "\n".join(lines)
    if PRAGMA_SCOPE.match(rel):
        return "\n".join(line for line in text.splitlines() if LINE_PRAGMA not in line)
    return text


def violations(root: Path, files: list[Path]) -> dict[str, list[str]]:
    yaml_path = root / TRACEABILITY_YAML
    d47_title = _defect_title(yaml_path.read_text("utf-8")) if yaml_path.exists() else None
    found: dict[str, list[str]] = {}
    for path in files:
        rel = path.relative_to(root).as_posix()
        content = ""
        if path.suffix.lower() not in BINARY_SUFFIXES:
            try:
                content = _scannable_content(rel, path.read_text(encoding="utf-8"), d47_title)
            except UnicodeDecodeError:
                content = ""
        terms = banned_terms_in(f"{rel}\n{content}")
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
