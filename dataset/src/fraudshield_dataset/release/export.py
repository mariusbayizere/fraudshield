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

from fraudshield_dataset.paths import DATASET_ROOT, PROVENANCE_MD, REALISM_REPORT_MD

TABLES = ("transactions", "labels", "account_events")
LICENCE = DATASET_ROOT / "release" / "LICENSE-CC-BY-4.0.txt"
DOCUMENTS = (PROVENANCE_MD, REALISM_REPORT_MD)
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


def export(source: Path, output: Path, *, csv_tables: bool = True) -> ExportResult:
    """Write a verifiable release of ``source`` into ``output``.

    The Parquet partitions are copied as they are, CSV is produced on the way out, and the licence
    and the generated documents travel with the data: a release that arrives without its parameter
    provenance and realism report cannot be judged by whoever receives it.
    """
    manifest_path = source / "manifest.json"
    if not manifest_path.exists():
        raise FileNotFoundError(f"{source} has no manifest.json; generate the dataset first")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    output.mkdir(parents=True, exist_ok=True)
    rows: dict[str, int] = {}
    for table in TABLES:
        rows[table] = _copy_parquet(source, table, output / "parquet" / table)
        if csv_tables:
            written = _write_csv(source, table, output / "csv" / f"{table}.csv")
            if written != rows[table]:
                raise ValueError(f"{table}: exported {written} CSV rows for {rows[table]} rows")
    shutil.copyfile(LICENCE, output / LICENCE.name)
    for document in DOCUMENTS:
        if document.exists():
            shutil.copyfile(document, output / document.name)
    release = {
        "dataset": manifest["dataset"],
        "seed": manifest["seed"],
        "rows": manifest["rows"],
        "rows_by_table": rows,
        "rows_by_month": manifest["rows_by_month"],
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
