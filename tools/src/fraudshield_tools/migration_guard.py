"""Merged Flyway migrations are immutable (backlog PB-20, ADR 0017).

Flyway records a checksum for every applied migration; editing one that is already on `main`
breaks every database migrated from it (validation fails, or the change is silently not applied).
This guard lists the migration files at the merge base with the published branch and fails if any
of them is modified, renamed or deleted in the working tree. New migrations are allowed.

On a push to `main` itself the merge base is HEAD, so the guard protects branches before they
merge, like `fs-contract-baselines`.

Usage: ``fs-migration-guard --against origin/main``.
"""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path

from fraudshield_tools import REPO_ROOT

MIGRATIONS = "backend/persistence/src/main/resources/db/migration"


class GuardError(RuntimeError):
    """The published migrations could not be determined."""


def _git(root: Path, *args: str) -> str:
    result = subprocess.run(  # noqa: S603 - fixed git subcommands
        ["git", "-C", str(root), *args],  # noqa: S607
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise GuardError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout


def published_migrations(root: Path, against: str) -> dict[str, str]:
    """Git blob id of every migration file at the merge base with `against`."""
    try:
        _git(root, "rev-parse", "--verify", "--quiet", f"{against}^{{commit}}")
    except GuardError as error:
        raise GuardError(f"ref {against!r} not found; fetch it first") from error
    base = _git(root, "merge-base", "HEAD", against).strip()
    listing = _git(root, "ls-tree", "-r", base, "--", MIGRATIONS)
    blobs: dict[str, str] = {}
    for line in listing.splitlines():
        meta, path = line.split("\t", 1)
        blobs[path] = meta.split()[2]
    return blobs


def blob_id(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha1(b"blob %d\0" % len(data) + data, usedforsecurity=False).hexdigest()


def changed_migrations(root: Path, against: str) -> list[str]:
    problems: list[str] = []
    for path, blob in sorted(published_migrations(root, against).items()):
        current = root / path
        if not current.is_file():
            problems.append(f"{path}: deleted or renamed after it was merged")
        elif blob_id(current) != blob:
            problems.append(f"{path}: modified after it was merged; add a new migration instead")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument("--against", default="origin/main")
    args = parser.parse_args(argv)
    try:
        problems = changed_migrations(REPO_ROOT, args.against)
        published = len(published_migrations(REPO_ROOT, args.against))
    except GuardError as error:
        print(f"migration-guard: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"ERROR {problem}", file=sys.stderr)
    print(f"migration-guard: {published} merged migrations, {len(problems)} changed")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
