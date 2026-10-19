"""Whether the reversal-scam rows really carry no counterparty novelty.

The pre-registered reversal-scam variant (ADR 0028) is meant to remove counterparty novelty: its
transfer goes to a counterparty on the victim's own payee list. The generator's test checks list
membership, not the feature, and a listed payee may never have been paid. This reads the draw and
computes `counterparty_is_new_for_account` for every row of the variant exactly as the feature
registry defines it and as the batch path computes it with complete history: true when no earlier
transaction on the same account went to the same counterparty. The whole draw is the history, so
nothing is truncated.

It recomputes the draw's fingerprint with the generator's function at the milestone tag and refuses
to report on any draw but the evaluated one. It reads test-period rows, so it records the read in
the test-set access log through the project's own `record` function, loaded from the tree.

Read-only on the dataset: it opens the Parquet files and changes nothing.

Usage (from the repository root, with a clean tree):
    uv run --no-project --with 'pyarrow==25.0.1' python \\
        docs/research/paper/evidence/reversal_novelty.py DATASET_DIR \\
        > docs/research/paper/evidence/reversal_novelty_d8083dbc.txt
"""

from __future__ import annotations

import datetime as dt
import importlib.util
import json
import subprocess
import sys
import tempfile
from collections import Counter
from pathlib import Path
from types import ModuleType

import pyarrow.compute as pc
import pyarrow.dataset as ds

ROOT = Path(__file__).resolve().parents[4]
TAG = "m4-complete"
FINGERPRINT = "dataset/src/fraudshield_dataset/fingerprint.py"
ACCESS = ROOT / "ml/src/fraudshield_ml/training/access.py"
EXPECTED = "d8083dbc742c20437bf3d060614f88849059eb8cf12bd0d3bbb092b518e6be32"
VARIANT = "reversal_scam_social_engineering"


def git(*args: str) -> str:
    return subprocess.run(  # noqa: S603 - fixed git command
        ["git", *args],  # noqa: S607
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def load(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fingerprint(dataset: Path) -> str:
    """The draw's fingerprint, computed by the generator's function as it stands at the tag."""
    with tempfile.TemporaryDirectory() as scratch:
        module = Path(scratch) / "fingerprint.py"
        module.write_text(git("show", f"{TAG}:{FINGERPRINT}"))
        return str(load("tag_fingerprint", module).dataset_fingerprint(dataset))


def main(argv: list[str]) -> int:
    dataset = Path(argv[1])
    # Read before the access log is appended below, which is the one write this run makes.
    state = "clean" if not git("status", "--porcelain").strip() else "DIRTY"
    found = fingerprint(dataset)
    if found != EXPECTED:
        print(f"refused: draw {found[:8]} is not the evaluated {EXPECTED[:8]}", file=sys.stderr)
        return 1
    manifest = json.loads((dataset / "manifest.json").read_text())
    micros = manifest["split"]["boundaries_micros"]["test_start"]
    test_start = dt.datetime.fromtimestamp(micros / 1e6, dt.UTC)
    labels = ds.dataset(dataset / "labels", format="parquet", partitioning="hive").to_table()
    transactions = ds.dataset(dataset / "transactions", format="parquet", partitioning="hive")

    variant = labels.filter(pc.equal(labels["scenario_variant"], VARIANT)).to_pylist()
    observed = {r["transaction_id"]: r["is_fraud_observed"] for r in variant}
    scored = transactions.to_table(
        columns=["transaction_id", "account_id", "counterparty_id", "transaction_timestamp"],
        filter=pc.field("transaction_id").isin(list(observed)),
    ).to_pylist()
    accounts = sorted({r["account_id"] for r in scored})
    history = transactions.to_table(
        columns=["account_id", "counterparty_id", "transaction_timestamp"],
        filter=pc.field("account_id").isin(accounts),
    ).to_pylist()

    def is_new(row: dict[str, object]) -> bool:
        return not any(
            h["account_id"] == row["account_id"]
            and h["transaction_timestamp"] < row["transaction_timestamp"]
            and h["counterparty_id"] == row["counterparty_id"]
            for h in history
        )

    def earlier_payments(row: dict[str, object]) -> int:
        return sum(
            h["account_id"] == row["account_id"]
            and h["transaction_timestamp"] < row["transaction_timestamp"]
            and h["counterparty_id"] == row["counterparty_id"]
            for h in history
        )

    in_test = [r for r in scored if r["transaction_timestamp"] >= test_start]
    new_all = [r for r in in_test if is_new(r)]
    scored_fraud = [r for r in in_test if observed[r["transaction_id"]]]
    new_scored = [r for r in scored_fraud if is_new(r)]
    prior = Counter(min(earlier_payments(r), 3) for r in scored_fraud)
    test_rows_read = sum(h["transaction_timestamp"] >= test_start for h in history)

    access = load("tree_access", ACCESS)
    access.record(
        "reversal_novelty.py",
        dataset,
        test_rows_read,
        purpose=(
            "paper evidence: counterparty_is_new_for_account on the reversal-scam rows "
            f"(reads the variant's rows and every row of their {len(accounts)} accounts)"
        ),
    )

    print(f"# evidence-commit: {git('rev-parse', 'HEAD').strip()}")
    print(f"# evidence-tree-state: {state}")
    print(f"# command: python docs/research/paper/evidence/reversal_novelty.py {dataset}")
    print("# exit: 0")
    print()
    print("REVERSAL-SCAM ROWS: counterparty_is_new_for_account with complete history")
    print("  (true when no earlier transaction on the same account went to the same counterparty)")
    print(f"  dataset fingerprint: {found} (function at {TAG})")
    print(f"  rows: {manifest['rows']}   test period from {test_start.isoformat()}")
    print(f"  accounts read in full: {len(accounts)}   test-period rows read: {test_rows_read}")
    print(f"  variant rows in the test period: {len(in_test)}")
    print(f"    counterparty_is_new_for_account true: {len(new_all)}")
    print(f"    counterparty_is_new_for_account false: {len(in_test) - len(new_all)}")
    print(f"  of which scored as fraud (observed label): {len(scored_fraud)}")
    print(f"    counterparty_is_new_for_account true: {len(new_scored)}")
    print(f"    counterparty_is_new_for_account false: {len(scored_fraud) - len(new_scored)}")
    print("  earlier payments to the same counterparty, scored rows (3 means 3 or more):")
    for count in range(4):
        print(f"    {count}: {prior.get(count, 0)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
