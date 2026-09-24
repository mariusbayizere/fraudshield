"""``fs-dataset``: generator, reports and checks for FraudShield-EAC-Transactions (ADR 0022)."""

from __future__ import annotations

import argparse
import json
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

from fraudshield_dataset.generator.config import build_config
from fraudshield_dataset.generator.countries import load_packs
from fraudshield_dataset.generator.pipeline import generate
from fraudshield_dataset.params import ParameterError, load_parameters
from fraudshield_dataset.paths import PROVENANCE_MD, REALISM_REPORT_MD
from fraudshield_dataset.provenance_report import render
from fraudshield_dataset.realism.checks import run_checks
from fraudshield_dataset.realism.report import render as render_report
from fraudshield_dataset.release.export import export
from fraudshield_dataset.release.split import SplitUnavailableError
from fraudshield_dataset.release.split import write as write_split


def _packs(output: Path) -> int:
    """Publish the pack facts a consumer of the dataset needs, as data rather than as code.

    The feature pipeline reads the country a transaction belongs to, its UTC offset and its bloc
    memberships, and it must not import this package to get them: ADR 0023 puts every
    country-specific value in a pack, and a second reader of those YAML files would be a second
    place for the schema to drift. Publishing them alongside the dataset is the same arrangement
    as publishing Parquet — the consumer reads the output format, never the producer's code.

    Only the facts a consumer needs, not the provenance: a citation is a claim about where a
    number came from, and it belongs with the parameters rather than in a machine-read sidecar.
    """
    packs = load_packs(load_parameters())
    facts = {
        code: {
            "alpha2": pack.alpha2,
            "continent": pack.continent,
            "blocs": sorted(pack.blocs),
            "utc_offset_hours": pack.utc_offset_hours,
            "currency": pack.currency,
            "currency_minor_units": pack.minor_units,
            # In minor units, so a consumer never has to know the currency's scale to use them.
            "round_denominations": list(pack.round_denominations),
        }
        for code, pack in sorted(packs.items())
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(facts, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {output} ({len(facts)} country packs)")
    return 0


def _split(dataset: Path, output: Path) -> int:
    """Publish the temporal split a consumer needs to reproduce any evaluation (D-07, PB-48).

    The boundaries are read from the dataset's own manifest, not recomputed: `plan_split` is their
    one home, and a second implementation here would be a second answer to the question "which
    rows are training rows?". The segment counts and fraud rates are measured from the rows.
    """
    try:
        block = write_split(dataset, output)
    except SplitUnavailableError as error:
        print(f"fs-dataset split: {error}", file=sys.stderr)
        return 2
    segments = block["segments"]
    print(f"wrote {output}")
    for key, segment in segments.items():
        print(f"  {key:<12} {segment['rows']:>10,} rows  {segment['true_fraud_rate']:.3%} fraud")
    return 0


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


def _parser() -> argparse.ArgumentParser:
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
    packs_command = commands.add_parser(
        "packs", help="write the country packs' facts as JSON, for consumers of the dataset"
    )
    packs_command.add_argument("--output", type=Path, required=True)
    split_command = commands.add_parser(
        "split", help="publish the temporal split boundaries and the segments they cut"
    )
    split_command.add_argument("dataset", type=Path)
    split_command.add_argument("--output", type=Path, required=True)
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
    return parser


def _export(args: argparse.Namespace) -> int:
    """Assemble a release, or refuse with a reason a reader can act on.

    A release that cannot carry its split is not self-describing (PB-48), so export refuses rather
    than shipping one without it. The refusal is a message and an exit code, not a traceback:
    "regenerate the dataset" is an instruction, and a stack trace is not.
    """
    try:
        release = export(args.dataset, args.output, csv_tables=not args.no_csv, report=args.report)
    except SplitUnavailableError as error:
        print(f"fs-dataset export: {error}", file=sys.stderr)
        return 2
    rows = release.rows["transactions"]
    print(f"exported {rows} transaction rows and {len(release.files)} files to {args.output}")
    return 0


def _report(args: argparse.Namespace) -> int:
    config = build_config(load_parameters(), seed=args.seed, total_rows=args.rows)
    results, measures = run_checks(args.dataset, config, full=args.full)
    args.output.write_text(render_report(results, measures, config, args.full), encoding="utf-8")
    print(f"wrote {args.output}")
    return 0 if all(r.passed or not r.gate for r in results) else 1


def _check(args: argparse.Namespace) -> int:
    config = build_config(load_parameters(), seed=args.seed, total_rows=args.rows)
    results, _ = run_checks(args.dataset, config, full=args.full)
    for r in results:
        status = "PASS" if r.passed else ("FAIL" if r.gate else "INFO")
        print(f"{status:4} {r.name}: {r.value} [{r.requirement}]")
    return 0 if all(r.passed or not r.gate for r in results) else 1


def _generate(args: argparse.Namespace) -> int:
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


#: One entry per subcommand. A dispatch table rather than a chain of returns, so adding the eighth
#: command does not make this function too long to read.
COMMANDS: dict[str, Callable[[argparse.Namespace], int]] = {
    "packs": lambda args: _packs(args.output),
    "split": lambda args: _split(args.dataset, args.output),
    "export": _export,
    "report": _report,
    "check": _check,
    "generate": _generate,
    "provenance": lambda args: _provenance(args.check),
}


def main(argv: Sequence[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    return COMMANDS[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
