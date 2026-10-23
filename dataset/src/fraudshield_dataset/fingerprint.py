"""A fingerprint over the dataset's **output rows** (PB-41).

`parameter_digest` answers "did the parameters change?". The question a shipped report needs
answered is **"is this report still about this dataset?"**, and those are not the same question.
PB-29 proved it: moving every country fact into packs changed **no parameter value** — the pack FX
rates are byte-identical to the table they replaced — and re-drew the entire benchmark anyway,
because `countries.simulated()` sorts and country iteration went from declaration order to
alphabetical. Every downstream draw shifted. The digest could not see it, **by construction**, and
caught the stale report only incidentally, because that refactor also moved the parameter
*structure*. Had the sort been introduced on its own, in a commit touching only `countries.py`, the
guard would have passed while every figure in the report silently became wrong.

That is the sixth appearance of the guard-with-two-doors shape in this project's notebook: a guard
that checks the input it was written for and not the output it exists to protect. The fix is not a
better parameter digest; it is a second digest over the thing the report describes.

**The parameter digest is kept alongside it.** They answer different questions and neither replaces
the other: the fingerprint detects *that* a report went stale, the digest localises *why*.

## What is hashed, and why it is a sample

Every row would be exact and would cost a full pass plus a total ordering of 5,000,000 rows, which
the 2 GiB memory budget does not have room for. Instead: the `SAMPLE_ROWS` rows of each table that
sort first under a fixed canonical ordering, plus **each table's row count**.

The sample is by *value*, never by position, so it is independent of partition layout, chunk size
and the order the generator happened to emit rows in — the same property E4 requires of the dataset
itself, and the reason a "first N rows of the first partition" sample was rejected: it would change
when the chunk size changed, which is a legitimate difference the dataset gate explicitly allows.

Transaction identifiers are UUIDs derived by hashing, so ordering by them is effectively random
with respect to content and the sample is not a slice of one corner of the data. The row counts are
in the payload because a draw that added rows without disturbing the sampled ones would otherwise
be invisible — a sample answers "are these the same rows?" and the count answers "are these all of
them?".

`SAMPLE_ROWS` is a judgement, not a derived constant: it is large enough that no plausible change
to a draw leaves all 1,024 sampled rows of all three tables untouched, and small enough to hold in
memory while streaming. A reader is entitled to argue with the number; what is not negotiable is
that the fingerprint is computed from output rows rather than from inputs.
"""

from __future__ import annotations

import hashlib
import heapq
import json
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pyarrow.parquet as pq

#: Bumped when the payload's shape changes, so that two fingerprints are never compared across
#: definitions and silently found to differ for the wrong reason. Version 2 hashes **every**
#: column of each sampled row; version 1 hashed three per table and could not see a change
#: confined to any of the others.
FINGERPRINT_VERSION = 2

SAMPLE_ROWS = 1024

#: The column each table's sample is **ordered** by. It decides *which* rows are sampled and
#: nothing else: every column of those rows is hashed.
#:
#: **This used to be a list of three columns per table, and that was the defect.** PB-40 gave the
#: generator a device-sharing mechanism, which rewrote `device_fingerprint` for hundreds of
#: accounts and changed nothing else — and the fingerprint did not move, because
#: `device_fingerprint` was not one of the three. A guard that reads three columns of fourteen
#: cannot answer "is this report still about this dataset?", which is the only question it exists
#: to answer. That is the seventh instance of the guard-with-two-doors shape in the notebook, and
#: the first inside a guard written to fix the sixth.
#:
#: Hashing every column costs nothing that matters: the sample is 1,024 rows per table either way,
#: and the extra columns are read from Parquet only for the partitions being scanned.
ORDERING_COLUMN: dict[str, str] = {
    "account_events": "account_id",
    "labels": "transaction_id",
    "transactions": "transaction_id",
}


class FingerprintError(RuntimeError):
    """A dataset that cannot be fingerprinted, which is never a reason to ship one without."""


def _rows(path: Path, ordering: str) -> tuple[list[str], Iterator[tuple[str, ...]]]:
    """One partition's rows, every column, canonicalised to strings and ordered-column first.

    Strings rather than native values so the ordering and the payload do not depend on how Arrow
    happens to map a decimal or a timestamp into Python, which is a detail of the reader version
    rather than of the data.

    The ordering column is placed first so that `nsmallest` sorts by it, and the remaining columns
    ride along in the tuple. A row's whole content therefore reaches the payload, which is the
    difference between version 2 and version 1.
    """
    block = pq.read_table(path)
    names = [ordering, *sorted(n for n in block.schema.names if n != ordering)]
    values = [block.column(name).to_pylist() for name in names]

    def stream() -> Iterator[tuple[str, ...]]:
        for row in zip(*values, strict=True):
            yield tuple("" if value is None else str(value) for value in row)

    return names, stream()


def _partitions(root: Path, table: str) -> list[Path]:
    paths = sorted((root / table).glob("month=*/part-*.parquet"))
    if not paths:
        raise FingerprintError(
            f"{root / table} holds no partitions, so there is nothing to fingerprint. A dataset "
            "missing a table is not a dataset with an empty table: shipping it would produce a "
            "fingerprint that looks valid and covers less than it claims"
        )
    return paths


def table_fingerprint(root: Path, table: str) -> dict[str, Any]:
    """One table's contribution: its row count and its canonical sample, every column."""
    ordering = ORDERING_COLUMN[table]
    rows = 0
    names: list[str] = []
    sample: list[tuple[str, ...]] = []
    for path in _partitions(root, table):
        block = pq.read_metadata(path)
        rows += block.num_rows
        names, incoming = _rows(path, ordering)
        # `nsmallest` keeps a bounded heap over the iterator, so a month is streamed rather than
        # materialised as tuples all at once.
        sample = heapq.nsmallest(SAMPLE_ROWS, _chain(sample, incoming))
    if rows == 0:
        raise FingerprintError(
            f"{table} has partitions but no rows; a fingerprint over nothing would be a constant "
            "that matches every empty dataset"
        )
    # The column names travel with the sample: a table that gained or lost a column would
    # otherwise produce a payload of the same shape over different content.
    return {"rows": rows, "columns": names, "sample": [list(row) for row in sample]}


def _chain(
    kept: list[tuple[str, ...]], incoming: Iterator[tuple[str, ...]]
) -> Iterator[tuple[str, ...]]:
    yield from kept
    yield from incoming


def dataset_fingerprint(root: Path) -> str:
    """SHA-256 over the sampled output rows of every table, with their counts.

    Deterministic from the data alone: no seed, no configuration and no wall-clock value enters it,
    so two runs that produced the same rows produce the same fingerprint however they were
    partitioned.
    """
    payload = {
        "version": FINGERPRINT_VERSION,
        "sample_rows": SAMPLE_ROWS,
        "tables": {table: table_fingerprint(root, table) for table in sorted(ORDERING_COLUMN)},
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
