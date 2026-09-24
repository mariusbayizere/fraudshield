"""Merged Flyway migrations are immutable (backlog PB-20, ADR 0017).

Flyway records a checksum for every applied migration; editing one that is already on `main`
breaks every database migrated from it (validation fails, or the change is silently not applied).
This guard lists the migration files at the merge base with the published branch and fails if any
of them is modified, renamed or deleted in the working tree. New migrations are allowed, but only
above the highest merged version: Flyway applies migrations in version order, so a new V5 added
after V11 is merged would never run on a database that is already at V11 (PB-24).

On a push to `main` itself the merge base is HEAD, so the guard protects branches before they
merge, like `fs-contract-baselines`.

Usage: ``fs-migration-guard --against origin/main``.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

from fraudshield_tools import REPO_ROOT

MIGRATIONS = "backend/persistence/src/main/resources/db/migration"
VERSIONED = re.compile(r"^V(?P<version>\d+(?:[._]\d+)*)__")


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


def blob_id(root: Path, path: str) -> str:
    """Blob id git itself would record for the working-tree file, filters included.

    Hashing the bytes on disk disagreed with git wherever a filter applies (line endings, for
    example), so an untouched migration could be reported as modified (PB-24).
    """
    return _git(root, "hash-object", "--path", path, str(root / path)).strip()


def _version(name: str) -> tuple[int, ...] | None:
    match = VERSIONED.match(name)
    if not match:
        return None
    return tuple(int(part) for part in re.split(r"[._]", match.group("version")))


def out_of_order_migrations(root: Path, against: str) -> list[str]:
    """New migrations must sort above every merged one, or Flyway will skip them."""
    published = published_migrations(root, against)
    merged = [v for v in (_version(Path(p).name) for p in published) if v is not None]
    if not merged:
        return []
    highest = max(merged)
    problems = []
    directory = root / MIGRATIONS
    for path in sorted(directory.glob("V*.sql")) if directory.is_dir() else []:
        relative = path.relative_to(root).as_posix()
        version = _version(path.name)
        if relative in published or version is None:
            continue
        if version <= highest:
            readable = ".".join(str(part) for part in highest)
            problems.append(
                f"{relative}: sorts at or below the highest merged migration (V{readable}); "
                "Flyway would skip it on a database already at that version"
            )
    return problems


def changed_migrations(root: Path, against: str) -> list[str]:
    problems: list[str] = []
    for path, blob in sorted(published_migrations(root, against).items()):
        if not (root / path).is_file():
            problems.append(f"{path}: deleted or renamed after it was merged")
        elif blob_id(root, path) != blob:
            problems.append(f"{path}: modified after it was merged; add a new migration instead")
    return problems + out_of_order_migrations(root, against)


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
