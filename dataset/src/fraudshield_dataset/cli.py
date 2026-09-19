"""``fs-dataset``: generator, reports and checks for FraudShield-EAC-Transactions (ADR 0022)."""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import ParameterError, load_parameters
from fraudshield_dataset.paths import PROVENANCE_MD, REALISM_REPORT_MD
from fraudshield_dataset.provenance_report import render
from fraudshield_dataset.realism.checks import run_checks
from fraudshield_dataset.realism.report import render as render_report
from fraudshield_dataset.release.export import export


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
    generator = commands.add_parser(
        "generate", help="simulate the dataset into partitioned Parquet"
    )
    generator.add_argument("--output", type=Path, required=True)
    generator.add_argument("--seed", type=int, default=20260917)
    generator.add_argument(
        "--rows", type=int, help="target rows (default: volume.total_rows_target)"
    )
    generator.add_argument("--chunk-size", type=int, default=8, help="shards simulated together")
    generator.add_argument(
        "--allow-missing-scenarios",
        action="store_true",
        help="generate even if the run is too small to stage every fraud scenario; the ones that "
        "could not be staged are recorded in manifest.json",
    )
    check = commands.add_parser("check", help="run the anti-leakage and realism checks")
    check.add_argument("dataset", type=Path)
    check.add_argument("--seed", type=int, default=20260917)
    check.add_argument("--rows", type=int, help="target rows the dataset was generated with")
    check.add_argument("--full", action="store_true", help="gate size and distribution targets")
    report = commands.add_parser("report", help="run the checks and write the realism report")
    report.add_argument("dataset", type=Path)
    report.add_argument("--seed", type=int, default=20260917)
    report.add_argument("--rows", type=int)
    report.add_argument("--full", action="store_true")
    report.add_argument("--output", type=Path, default=REALISM_REPORT_MD)
    export_command = commands.add_parser("export", help="assemble a verifiable release")
    export_command.add_argument("dataset", type=Path)
    export_command.add_argument("--output", type=Path, required=True)
    export_command.add_argument(
        "--no-csv", action="store_true", help="export Parquet only (CSV is written here alone)"
    )
    export_command.add_argument(
        "--report",
        type=Path,
        default=None,
        help="the realism report to bundle, defaulting to the committed one; it must carry this "
        "dataset's fingerprint or the export refuses (PB-41)",
    )
    args = parser.parse_args(argv)
    if args.command == "export":
        release = export(args.dataset, args.output, csv_tables=not args.no_csv, report=args.report)
        rows = release.rows["transactions"]
        print(f"exported {rows} transaction rows and {len(release.files)} files to {args.output}")
        return 0
    if args.command == "report":
        config = build_config(load_parameters(), seed=args.seed, total_rows=args.rows)
        results, measures = run_checks(args.dataset, config, full=args.full)
        args.output.write_text(
            render_report(results, measures, config, args.full), encoding="utf-8"
        )
        print(f"wrote {args.output}")
        return 0 if all(r.passed or not r.gate for r in results) else 1
    if args.command == "check":
        config = build_config(load_parameters(), seed=args.seed, total_rows=args.rows)
        results, _ = run_checks(args.dataset, config, full=args.full)
        for r in results:
            status = "PASS" if r.passed else ("FAIL" if r.gate else "INFO")
            print(f"{status:4} {r.name}: {r.value} [{r.requirement}]")
        return 0 if all(r.passed or not r.gate for r in results) else 1
    if args.command == "generate":
        config = build_config(load_parameters(), seed=args.seed, total_rows=args.rows)
        result = generate(
            config,
            args.output,
            chunk_size=args.chunk_size,
            allow_missing_scenarios=args.allow_missing_scenarios,
        )
        print(
            f"generated {result.rows} rows in {len(result.rows_by_month)} months; "
            f"peak RSS {result.peak_rss_bytes / 2**20:.0f} MiB"
        )
        return 0
    return _provenance(args.check)


if __name__ == "__main__":
    sys.exit(main())
