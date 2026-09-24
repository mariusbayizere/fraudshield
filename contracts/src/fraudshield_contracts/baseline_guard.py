"""Check contracts against the baselines already published on main (ADR 0012, review NF-E04).

The contract tests compare schemas with the committed baselines, but a commit that edits a schema
and its baseline together would pass them. This guard reads the baselines as they were at the merge
base with the published branch and checks the current Kafka schemas against those, and runs
`buf breaking` on the proto against the proto at the merge base, so only a change that is compatible
with what consumers already run can merge.

On a push to main itself the merge base is HEAD, so the check compares HEAD with itself: the guard
protects changes before they merge (branches and pull requests), not commits already on main.

Usage: ``fs-contract-baselines --against origin/main`` (CI, full history) or ``--against main``.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from typing import Any

from fraudshield_contracts import CONTRACTS_ROOT
from fraudshield_contracts.compatibility import breaking_changes_between
from fraudshield_contracts.events import schemas

KAFKA_BASELINE = "contracts/kafka/baseline"
KAFKA_SCHEMAS = "contracts/kafka/schemas"
PROTO_ROOT = "contracts/proto"
PROTO_FILE = f"{PROTO_ROOT}/fraudshield/scoring/v1/scoring.proto"
BUF = CONTRACTS_ROOT.parent / "tools" / "bin" / "buf"


def _git(*args: str) -> str:
    result = subprocess.run(  # noqa: S603 - fixed git subcommands; ref validated by git itself
        ["git", "-C", str(CONTRACTS_ROOT.parent), *args],  # noqa: S607
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def published_kafka_baseline(base: str) -> dict[str, Any]:
    listing = (
        _git("ls-tree", "--name-only", f"{base}:{KAFKA_BASELINE}")
        if _has(base, KAFKA_BASELINE)
        else ""
    )
    found: dict[str, Any] = {}
    for name in listing.split():
        if name.endswith(".schema.json"):
            schema = json.loads(_git("show", f"{base}:{KAFKA_BASELINE}/{name}"))
            found[schema["$id"]] = schema
    return found


def buf_breaking(against: str) -> list[str]:
    """`buf breaking` for the proto against a git ref, one finding per line."""
    result = subprocess.run(  # noqa: S603 - pinned launcher with fixed arguments
        [str(BUF), "breaking", PROTO_ROOT, "--against", f".git#ref={against},subdir={PROTO_ROOT}"],
        cwd=CONTRACTS_ROOT.parent,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode not in (0, 100):
        raise RuntimeError(f"buf breaking failed: {result.stderr.strip() or result.stdout.strip()}")
    return [line for line in result.stdout.splitlines() if line.strip()]


def _has(base: str, path: str) -> bool:
    try:
        _git("cat-file", "-e", f"{base}:{path}")
    except subprocess.CalledProcessError:
        return False
    return True


class GuardError(RuntimeError):
    """The guard could not establish what is published, so it cannot pass."""


def merge_base(against: str) -> str:
    try:
        _git("rev-parse", "--verify", "--quiet", f"{against}^{{commit}}")
    except subprocess.CalledProcessError as error:
        raise GuardError(
            f"ref {against!r} not found; fetch it first (for example `git fetch origin main`)"
        ) from error
    try:
        return _git("merge-base", "HEAD", against).strip()
    except subprocess.CalledProcessError as error:
        raise GuardError(f"HEAD and {against!r} have no common history") from error


def check(against: str) -> list[str]:
    base = merge_base(against)
    problems: list[str] = []
    published = published_kafka_baseline(base)
    if not published and _has(base, KAFKA_SCHEMAS):
        raise GuardError(
            f"Kafka schemas exist at {base[:12]} but no baseline could be read "
            f"from {KAFKA_BASELINE}"
        )
    for schema_id, found in breaking_changes_between(published, dict(schemas())).items():
        problems.extend(f"kafka {schema_id}: {change}" for change in found)
    proto_published = _has(base, PROTO_FILE)
    if proto_published:
        problems.extend(f"proto: {finding}" for finding in buf_breaking(base))
    print(
        f"contract-baselines: merge base {base[:12]} with {against}; "
        f"{len(published)} published Kafka schemas, proto "
        f"{'present' if proto_published else 'not yet published'}; "
        f"{len(problems)} breaking changes"
    )
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0] if __doc__ else None)
    parser.add_argument(
        "--against", default="origin/main", help="published branch (default origin/main)"
    )
    args = parser.parse_args(argv)
    try:
        problems = check(args.against)
    except subprocess.CalledProcessError as error:
        print(f"contract-baselines: git failed: {error.stderr.strip()}", file=sys.stderr)
        return 2
    except RuntimeError as error:
        print(f"contract-baselines: {error}", file=sys.stderr)
        return 2
    for problem in problems:
        print(f"ERROR {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
