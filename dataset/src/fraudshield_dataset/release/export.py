"""Assemble a release from a generated dataset (ML-DATA-08, ADR 0022 section 3).

The generator writes partitioned Parquet only. CSV exists here and nowhere else: it is written a
month at a time through one open writer per table, so a release of any size costs the memory of a
single month, and the columnar files stay the working format.

Everything that leaves the repository is listed in ``SHA256SUMS`` and described in
``release.json``: the files, their row counts, the seed and the generator's manifest checksums. A
consumer can verify the release with ``sha256sum -c SHA256SUMS`` and nothing else.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
from pyarrow import csv

from fraudshield_dataset.fingerprint import dataset_fingerprint
from fraudshield_dataset.paths import DATASET_ROOT, PROVENANCE_MD, REALISM_REPORT_MD
from fraudshield_dataset.release.split import measured_block, read_block

TABLES = ("transactions", "labels", "account_events")
LICENCE = DATASET_ROOT / "release" / "LICENSE-CC-BY-4.0.txt"
_CHECKSUM_BLOCK = 2**20


@dataclass(frozen=True)
class ExportResult:
    output: Path
    rows: dict[str, int]
    files: list[str]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while block := handle.read(_CHECKSUM_BLOCK):
            digest.update(block)
    return digest.hexdigest()


def _months(source: Path, table: str) -> Iterator[Path]:
    for partition in sorted((source / table).glob("month=*")):
        yield partition / "part-0000.parquet"


def _write_csv(source: Path, table: str, target: Path) -> int:
    """Stream one table to CSV, a month per batch, and return the rows written."""
    rows = 0
    writer: csv.CSVWriter | None = None
    try:
        for path in _months(source, table):
            month = pq.read_table(path)
            if writer is None:
                target.parent.mkdir(parents=True, exist_ok=True)
                writer = csv.CSVWriter(target, month.schema)
            writer.write_table(month)
            rows += month.num_rows
            del month
    finally:
        if writer is not None:
            writer.close()
    return rows


def _copy_parquet(source: Path, table: str, target: Path) -> int:
    rows = 0
    for path in _months(source, table):
        destination = target / path.parent.name / path.name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        rows += pq.read_metadata(path).num_rows
    return rows


def export(
    source: Path, output: Path, *, csv_tables: bool = True, report: Path | None = None
) -> ExportResult:
    """Write a verifiable release of ``source`` into ``output``.

    The Parquet partitions are copied as they are, CSV is produced on the way out, and the licence
    and the generated documents travel with the data: a release that arrives without its parameter
    provenance and realism report cannot be judged by whoever receives it.

    ``report`` is the realism report to bundle, defaulting to the one committed in the repository.
    It is a parameter because the report that describes a release is the one generated **from the
    dataset being released**, which for a release-size run is produced by that run and never lives
    here — the committed report describes whatever run last regenerated it. Whichever is used must
    carry this dataset's fingerprint, or the export refuses (PB-41).
    """
    manifest_path = source / "manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError(f"{source} has no manifest.json; generate the dataset first")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    # Before anything is copied. A release that cannot carry its split is not self-describing
    # (PB-48), and discovering that after writing a gigabyte of Parquet leaves a half-built
    # directory that looks like a release and is not one. The block is read again below with its
    # measured counts; reading the manifest twice is free next to copying the data.
    read_block(source)
    output.mkdir(parents=True, exist_ok=True)
    rows: dict[str, int] = {}
    for table in TABLES:
        rows[table] = _copy_parquet(source, table, output / "parquet" / table)
        if csv_tables:
            written = _write_csv(source, table, output / "csv" / f"{table}.csv")
            if written != rows[table]:
                raise ValueError(f"{table}: exported {written} CSV rows for {rows[table]} rows")
    shutil.copyfile(LICENCE, output / LICENCE.name)
    fingerprint = dataset_fingerprint(source)
    realism = report or REALISM_REPORT_MD
    if PROVENANCE_MD.exists():
        shutil.copyfile(PROVENANCE_MD, output / PROVENANCE_MD.name)
    if realism.exists():
        _refuse_a_report_about_other_data(realism, fingerprint)
        # Named for what it is in the release, not for where it came from: a report generated by a
        # CI run into an artifact directory must still arrive as `realism_report.md`.
        shutil.copyfile(realism, output / REALISM_REPORT_MD.name)
    release = {
        "dataset": manifest["dataset"],
        "seed": manifest["seed"],
        "rows": manifest["rows"],
        "rows_by_table": rows,
        "rows_by_month": manifest["rows_by_month"],
        # The partition key is the simulation month in local time, not the UTC month of the
        # timestamp, and a consumer filtering by timestamp must know it (PB-26). Stated here as
        # well as in the datasheet, because a machine reading release.json will not read prose.
        "partitioning": {
            "key": "month",
            "meaning": "simulation month in local time, not the UTC month of transaction_timestamp",
            "drift": "a row's UTC timestamp may precede its partition start, never follow its end",
            "max_backward_drift_hours": 3,
            "consumer_rule": "to select a UTC month M, read partitions M and M+1 and filter on "
            "transaction_timestamp; pruning on month= alone is unsound",
        },
        # The fingerprint of the rows in THIS release (PB-41), not of the parameters that were
        # given to the generator. A consumer comparing two releases, or checking that the bundled
        # realism report describes what arrived with it, has one value to compare and does not
        # need to re-run the generator to find out.
        "dataset_fingerprint_sha256": fingerprint,
        # The temporal split, so a release is self-describing (PB-48). Without it a consumer
        # holding the Parquet cannot say which rows are training rows, and any evaluation they
        # compute is incomparable with the gates in a way nothing would announce. The boundaries
        # are the generator's, read from the manifest; the counts are measured here.
        "split": measured_block(source),
        "formats": ["parquet", "csv"] if csv_tables else ["parquet"],
        "licence": {"name": "CC BY 4.0", "file": LICENCE.name, "sha256": _sha256(LICENCE)},
        "source_manifest_sha256": _sha256(manifest_path),
    }
    (output / "release.json").write_text(json.dumps(release, indent=2, sort_keys=True) + "\n")
    shutil.copyfile(manifest_path, output / "manifest.json")
    files = sorted(
        str(path.relative_to(output))
        for path in output.rglob("*")
        if path.is_file() and path.name != "SHA256SUMS"
    )
    (output / "SHA256SUMS").write_text(
        "".join(f"{_sha256(output / name)}  {name}\n" for name in files), encoding="utf-8"
    )
    return ExportResult(output, rows, files)


def _refuse_a_report_about_other_data(document: Path, fingerprint: str) -> None:
    """Refuse to bundle a realism report that does not describe the data being exported (PB-41).

    This is the point where the fingerprint earns its keep. A release ships the data and the report
    together, and a consumer reasonably assumes the second describes the first — there is nothing
    in the bundle to suggest otherwise, and the figures look like measurements because they are,
    of a different dataset. PB-39 was that state, shipped: the committed report described a
    superseded parameter set for two commits.

    Refusing is deliberate rather than warning or omitting the file. A warning is read by whoever
    runs the export and by nobody downstream; silently dropping the report produces a release that
    cannot be judged at all, which the module docstring names as the thing the documents are here
    to prevent. Regenerating the report for the dataset being released is cheap and is the only
    correct outcome, so the error says exactly that.

    `params_provenance.md` describes the parameters rather than the rows and is covered by its own
    staleness check, so it is copied without this check rather than made to carry a fingerprint.
    """
    text = document.read_text(encoding="utf-8")
    if fingerprint in text:
        return
    raise ValueError(
        f"{document} does not describe this dataset: it carries no dataset fingerprint matching "
        f"{fingerprint}. A release bundles the data and the report together and a consumer has no "
        "way to tell they disagree, so the report is regenerated for the dataset being released: "
        "uv run fs-dataset report <dataset> --rows <rows>"
    )


def verify(release: Path) -> list[str]:
    """Return the files in ``release`` whose checksum no longer matches ``SHA256SUMS``."""
    listed = (release / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    wrong = []
    for line in listed:
        digest, name = line.split("  ", 1)
        path = release / name
        if not path.exists() or _sha256(path) != digest:
            wrong.append(name)
    return wrong


def schema_of(table: str, source: Path) -> pa.Schema:
    """Schema of an exported table, for tests and for the datasheet."""
    return pq.read_schema(next(_months(source, table)))
