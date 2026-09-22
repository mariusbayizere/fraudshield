"""`fs-model`: build a servable bundle, and manage it in the model registry."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from collections.abc import Sequence
from pathlib import Path

from fraudshield_ml.models import build
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving.registry import (
    PREVIOUS,
    PRODUCTION,
    SHADOW,
    MlflowRegistry,
    PromotionRefused,
)
from fraudshield_ml.serving.shadow import GateDecision


def _gate(path: Path | None) -> GateDecision | None:
    """A shadow comparator's decision, as JSON. Its own fields, nothing inferred."""
    if path is None:
        return None
    report = json.loads(path.read_text())
    return GateDecision(
        promote=bool(report["promote"]),
        reasons=tuple(report.get("reasons", ())),
        scored=int(report["scored"]),
        label_coverage=float(report["label_coverage"]),
        auc_delta=report.get("auc_delta"),
        psi=report.get("psi"),
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-model", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    make = commands.add_parser(
        "build", help="package M4's evaluated ensemble and forest from the gate's cache and seed"
    )
    make.add_argument("--cache", type=Path, required=True, help="feature cache (fs-features)")
    make.add_argument("--out", type=Path, required=True, help="bundle directory to write")
    make.add_argument("--seed", type=int, default=1)

    publish = commands.add_parser("publish", help="upload a bundle and register it as a version")
    publish.add_argument("--bundle", type=Path, required=True)
    publish.add_argument("--mlflow", required=True, help="MLflow tracking URL")
    publish.add_argument("--name", default="fraudshield-ensemble")
    publish.add_argument("--alias", choices=[PRODUCTION, SHADOW], help="point this alias at it")
    publish.add_argument("--gate", type=Path, help="the shadow comparator's decision, JSON (D-11)")
    publish.add_argument("--override", help="promote without a shadow gate, and why (recorded)")

    alias = commands.add_parser(
        "alias",
        help="move an alias: promote, roll back, or switch shadow mode on (D-50). Rolling "
        "production back to the version previous_production holds needs no --gate: it has served",
    )
    alias.add_argument("--mlflow", required=True)
    alias.add_argument("--name", default="fraudshield-ensemble")
    alias.add_argument("alias", choices=[PRODUCTION, SHADOW, PREVIOUS])
    alias.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "fs-models")
    alias.add_argument("--gate", type=Path, help="the shadow comparator's decision, JSON (D-11)")
    alias.add_argument("--override", help="promote without a shadow gate, and why (recorded)")
    alias.add_argument("version")

    args = parser.parse_args(argv)
    try:
        return _run(args)
    except PromotionRefused as refusal:
        print(f"refused: {refusal}", file=sys.stderr)
        return 1


def _run(args: argparse.Namespace) -> int:
    if args.command == "build":
        root = Path(__file__).resolve().parents[4]
        report = build.build_from_cache(args.cache, args.out, seed=args.seed, root=root)
        print("\n".join(report.lines()))
        print(f"bundle written to {args.out}")
    elif args.command == "publish":
        Bundle.load(args.bundle)  # refuse to publish what a scorer would refuse to load
        version = MlflowRegistry(args.mlflow).publish(
            args.name,
            args.bundle,
            alias=args.alias,
            gate=_gate(args.gate),
            override=args.override,
        )
        print(f"{args.name} v{version}" + (f" is now @{args.alias}" if args.alias else ""))
    elif args.command == "alias":
        MlflowRegistry(args.mlflow).promote(
            args.name,
            args.alias,
            args.version,
            args.cache,
            gate=_gate(args.gate),
            override=args.override,
        )
        print(f"{args.name} @{args.alias} -> v{args.version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
