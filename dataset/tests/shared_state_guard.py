"""The pure logic behind E15's session guard, separated so it can itself be tested.

A guard with no test is the thing E13 exists to refuse: the session fixture passes on every clean
run whether or not it can detect anything, and the one run where it matters is the run where nobody
is watching. Keeping the comparison pure lets a test mutate a tree in ``tmp_path`` and assert the
guard reports it — without mutating anything the guard protects.
"""

from __future__ import annotations

import hashlib
from pathlib import Path


def tree_digest(root: Path, relative_to: Path) -> dict[str, str]:
    """A digest per file, so a report can name the file rather than only the tree."""
    if not root.exists():
        return {}
    return {
        str(path.relative_to(relative_to)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def describe_changes(before: dict[str, str], after: dict[str, str]) -> list[str]:
    """Every difference, labelled, in a stable order.

    Creations are reported as well as modifications and deletions: a test that writes a *new* file
    into the parameter tree has changed what ``load_parameters`` returns just as surely as one that
    edits an existing file, and the country packs made that a live possibility — the loader globs
    ``countries/*.yaml``, so an extra pack is an extra country.
    """
    return [
        *(f"modified: {p}" for p in sorted(before.keys() & after.keys()) if before[p] != after[p]),
        *(f"deleted:  {p}" for p in sorted(before.keys() - after.keys())),
        *(f"created:  {p}" for p in sorted(after.keys() - before.keys())),
    ]
