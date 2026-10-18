"""The test-set access log (I.2): every command that scores D-07's test rows records that it did.

The M4 review found the test period scored by at least eleven committed runs across three draws,
with nothing recording the looks (M4-8). No model was tuned on them, but "the test set was not
reused" is a claim, and a claim needs a record. This is the record: one JSON line per scoring
run, appended, committed beside the evidence it belongs to. Runs made before it existed are
back-filled from the committed evidence and marked as such.

The log is the repository's `docs/benchmarks/test_set_access.jsonl` unless `FS_TEST_ACCESS_LOG`
names another file, which the test suite does so that no test ever writes to the committed log.
"""

from __future__ import annotations

import json
import os
from datetime import UTC, datetime
from pathlib import Path

ENVIRONMENT = "FS_TEST_ACCESS_LOG"
#: ml/src/fraudshield_ml/training/access.py -> the repository root is four levels up.
DEFAULT = Path(__file__).resolve().parents[4] / "docs" / "benchmarks" / "test_set_access.jsonl"


def log_path() -> Path:
    configured = os.environ.get(ENVIRONMENT)
    return Path(configured) if configured else DEFAULT


def record(command: str, source: Path, test_rows: int, *, purpose: str) -> Path:
    """Append one line saying which command scored how many test rows from where, and why."""
    path = log_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    entry = {
        "utc": datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "command": command,
        "source": str(source),
        "test_rows": test_rows,
        "purpose": purpose,
    }
    with path.open("a", encoding="utf-8") as log:
        log.write(json.dumps(entry, sort_keys=True) + "\n")
    return path
