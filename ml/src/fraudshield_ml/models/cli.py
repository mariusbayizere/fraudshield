"""`fs-model`: build a servable bundle, and manage it in the model registry."""

from __future__ import annotations

import argparse
import sys
import tempfile
from collections.abc import Sequence
from pathlib import Path

from fraudshield_ml.models import build
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving.registry import PREVIOUS, PRODUCTION, SHADOW, MlflowRegistry


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-model", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    make = commands.add_parser("build", help="train D-05's ensemble and D-06's forest from a cache")
    make.add_argument("--cache", type=Path, required=True, help="feature cache (fs-features)")
    make.add_argument("--out", type=Path, required=True, help="bundle directory to write")
    make.add_argument("--seed", type=int, default=1)
    make.add_argument("--trees", type=int, default=build.Complexity().trees)
    make.add_argument("--depth", type=int, default=build.Complexity().depth)

    publish = commands.add_parser("publish", help="upload a bundle and register it as a version")
    publish.add_argument("--bundle", type=Path, required=True)
    publish.add_argument("--mlflow", required=True, help="MLflow tracking URL")
    publish.add_argument("--name", default="fraudshield-ensemble")
    publish.add_argument("--alias", choices=[PRODUCTION, SHADOW], help="point this alias at it")

    alias = commands.add_parser(
        "alias", help="move an alias: promote, roll back, or switch shadow mode on (D-50)"
    )
    alias.add_argument("--mlflow", required=True)
    alias.add_argument("--name", default="fraudshield-ensemble")
    alias.add_argument("alias", choices=[PRODUCTION, SHADOW, PREVIOUS])
    alias.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "fs-models")
    alias.add_argument("version")

    args = parser.parse_args(argv)
    if args.command == "build":
        root = Path(__file__).resolve().parents[4]
        report = build.build_from_cache(
            args.cache,
            args.out,
            seed=args.seed,
            root=root,
            complexity=build.Complexity(trees=args.trees, depth=args.depth),
        )
        print("\n".join(report.lines()))
        print(f"bundle written to {args.out}")
    elif args.command == "publish":
        Bundle.load(args.bundle)  # refuse to publish what a scorer would refuse to load
        version = MlflowRegistry(args.mlflow).publish(args.name, args.bundle, alias=args.alias)
        print(f"{args.name} v{version}" + (f" is now @{args.alias}" if args.alias else ""))
    elif args.command == "alias":
        MlflowRegistry(args.mlflow).promote(args.name, args.alias, args.version, args.cache)
        print(f"{args.name} @{args.alias} -> v{args.version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
