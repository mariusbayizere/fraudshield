"""``fs-evidence``: run a command and stamp its output with the tree that actually ran (PB-53).

**Why a wrapper rather than a flag on each command.** The rule belongs to every evidence run in
this repository — the generator, the realism report, the feature measurements, anything whose
output is later quoted. Putting it in each producer would mean `fraudshield_dataset` and
`fraudshield_ml` depending on the governance package, which is the coupling those packages are
kept free of on purpose: the feature pipeline consumes the published interchange format and
imports no producer. A wrapper keeps the rule in one place and the packages independent.

    uv run fs-evidence --output docs/benchmarks/x.txt -- uv run fs-features computability ...

Every artefact also names the machine it ran on — CPU, cores, memory and OS — because runs now
happen on more than one: accuracy results are admissible from any of them, and ADR 0010 restricts
only latency, but a reader must be able to tell which machine produced which number.

It refuses on a dirty tree, because an artefact produced from uncommitted code belongs to no
commit and nothing downstream can tell. ``--allow-dirty`` runs anyway and stamps the artefact
**NOT CITABLE**, so a development run is possible and is never mistaken for evidence.
"""

from __future__ import annotations

import argparse
import os
import platform
import subprocess
import sys
from collections.abc import Sequence
from pathlib import Path

from fraudshield_tools import REPO_ROOT
from fraudshield_tools.provenance import DirtyTreeError, require_clean

MACHINE_FIELD = "# evidence-machine:"


def _read(path: str) -> str:
    try:
        return Path(path).read_text(encoding="utf-8")
    except OSError:
        return ""


def machine() -> str:
    """CPU model, logical cores, total memory and OS, read from the machine rather than typed."""
    cpu = next(
        (
            line.split(":", 1)[1].strip()
            for line in _read("/proc/cpuinfo").splitlines()
            if line.startswith("model name")
        ),
        platform.processor() or platform.machine() or "unknown CPU",
    )
    total = next(
        (
            f"{int(line.split()[1]) / 1024 / 1024:.1f} GiB"
            for line in _read("/proc/meminfo").splitlines()
            if line.startswith("MemTotal:")
        ),
        "unknown memory",
    )
    return f"{cpu}; {os.cpu_count()} logical cores; {total}; {platform.platform()}"


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-evidence", description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="artefact to write")
    parser.add_argument(
        "--allow-dirty",
        action="store_true",
        help="run on a modified tree and stamp the artefact NOT CITABLE",
    )
    parser.add_argument("command", nargs=argparse.REMAINDER, help="-- then the command to run")
    args = parser.parse_args(argv)
    command = [a for a in args.command if a != "--"]
    if not command:
        print("fs-evidence: no command given after --", file=sys.stderr)
        return 2
    try:
        stamp = require_clean(REPO_ROOT, allow_dirty=args.allow_dirty)
    except DirtyTreeError as dirty:
        print(f"fs-evidence: {dirty}", file=sys.stderr)
        return 2

    finished = subprocess.run(  # noqa: S603 - argv comes from the operator's own command line
        command, cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )
    sys.stdout.write(finished.stdout)
    sys.stderr.write(finished.stderr)
    # The artefact is written whatever the command's exit code: a failed evidence run is itself
    # evidence, and one that left no file would be indistinguishable from one never attempted.
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        stamp.header()
        + f"{MACHINE_FIELD} {machine()}\n"
        + f"# command: {' '.join(command)}\n"
        + f"# exit: {finished.returncode}\n\n"
        + finished.stdout,
        encoding="utf-8",
    )
    print(f"fs-evidence: wrote {args.output} ({'clean' if stamp.clean else 'NOT CITABLE'})")
    return finished.returncode


if __name__ == "__main__":
    raise SystemExit(main())
