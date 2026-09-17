"""``fs-dataset``: generator, reports and checks for FraudShield-EAC-Transactions (ADR 0022)."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence

from fraudshield_dataset.params import ParameterError, load_parameters
from fraudshield_dataset.paths import PROVENANCE_MD
from fraudshield_dataset.provenance_report import render


def _provenance(check: bool) -> int:
    try:
        rendered = render(load_parameters())
    except ParameterError as error:
        print(f"fs-dataset provenance: {error}", file=sys.stderr)
        return 2
    if check:
        current = PROVENANCE_MD.read_text(encoding="utf-8") if PROVENANCE_MD.exists() else ""
        if current != rendered:
            print(
                "dataset/params_provenance.md is stale; run: uv run fs-dataset provenance",
                file=sys.stderr,
            )
            return 1
        print("parameter provenance up to date")
        return 0
    PROVENANCE_MD.write_text(rendered, encoding="utf-8")
    print(f"wrote {PROVENANCE_MD.name}")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-dataset", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    provenance = commands.add_parser("provenance", help="render dataset/params_provenance.md")
    provenance.add_argument("--check", action="store_true", help="fail if the file is stale")
    args = parser.parse_args(argv)
    return _provenance(args.check)


if __name__ == "__main__":
    sys.exit(main())
