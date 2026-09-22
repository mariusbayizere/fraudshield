"""`fs-model`: build a servable bundle, and manage it in the model registry."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from fraudshield_ml.models import build


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-model", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    make = commands.add_parser("build", help="train D-05's ensemble and D-06's forest from a cache")
    make.add_argument("--cache", type=Path, required=True, help="feature cache (fs-features)")
    make.add_argument("--out", type=Path, required=True, help="bundle directory to write")
    make.add_argument("--seed", type=int, default=1)

    args = parser.parse_args(argv)
    if args.command == "build":
        root = Path(__file__).resolve().parents[4]
        report = build.build_from_cache(args.cache, args.out, seed=args.seed, root=root)
        print("\n".join(report.lines()))
        print(f"bundle written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
