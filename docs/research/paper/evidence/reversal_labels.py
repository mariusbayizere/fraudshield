"""Why the battery scores fewer reversal-scam rows than the generator wrote.

The datasheet counts the pre-registered reversal-scam variant's rows; the battery scores fewer of
them as fraud. This reads the draw itself and counts, for each held-out sub-variant, its rows in the
test period by true label and by the observed label the evaluation trains and scores on. It
recomputes the draw's fingerprint with the generator's own function at the milestone tag, so the
counts are tied to the draw the paper evaluates, and writes an evidence file whose header records
the commit and whether the working tree was clean.

Read-only: it opens the dataset's Parquet files and changes nothing.

Usage (from the repository root, with a clean tree):
    uv run --no-project --with 'pyarrow==25.0.1' python \
        docs/research/paper/evidence/reversal_labels.py DATASET_DIR \
        > docs/research/paper/evidence/reversal_labels_d8083dbc.txt
"""

from __future__ import annotations

import datetime as dt
import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

import pyarrow.compute as pc
import pyarrow.dataset as ds

ROOT = Path(__file__).resolve().parents[4]
TAG = "m4-complete"
FINGERPRINT = "dataset/src/fraudshield_dataset/fingerprint.py"
VARIANTS = ("reversal_scam_social_engineering", "novel_esim_delayed_drain")


def git(*args: str) -> str:
    return subprocess.run(  # noqa: S603 - fixed git command
        ["git", *args],  # noqa: S607
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def fingerprint(dataset: Path) -> str:
    """The draw's fingerprint, computed by the generator's function as it stands at the tag."""
    with tempfile.TemporaryDirectory() as scratch:
        module = Path(scratch) / "fingerprint.py"
        module.write_text(git("show", f"{TAG}:{FINGERPRINT}"))
        spec = importlib.util.spec_from_file_location("tag_fingerprint", module)
        if spec is None or spec.loader is None:
            raise ImportError(f"cannot load {FINGERPRINT} at {TAG}")
        loaded = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(loaded)
        return str(loaded.dataset_fingerprint(dataset))


def main(argv: list[str]) -> int:
    dataset = Path(argv[1])
    manifest = json.loads((dataset / "manifest.json").read_text())
    micros = manifest["split"]["boundaries_micros"]["test_start"]
    test_start = dt.datetime.fromtimestamp(micros / 1e6, dt.UTC)
    labels = ds.dataset(dataset / "labels", format="parquet", partitioning="hive").to_table()
    transactions = ds.dataset(dataset / "transactions", format="parquet", partitioning="hive")

    state = "clean" if not git("status", "--porcelain").strip() else "DIRTY"
    print(f"# evidence-commit: {git('rev-parse', 'HEAD').strip()}")
    print(f"# evidence-tree-state: {state}")
    print(f"# command: python docs/research/paper/evidence/reversal_labels.py {dataset}")
    print("# exit: 0")
    print()
    print("HELD-OUT SUB-VARIANTS BY LABEL — rows the generator wrote, and rows the evaluation")
    print("scores as fraud (it reads is_fraud_observed, the label a model would be given).")
    print(f"  dataset fingerprint: {fingerprint(dataset)} (function at {TAG})")
    print(f"  rows: {manifest['rows']}   test period from {test_start.isoformat()}")
    for variant in VARIANTS:
        rows = labels.filter(pc.equal(labels["scenario_variant"], variant)).to_pylist()
        ids = [r["transaction_id"] for r in rows]
        stamps = transactions.to_table(
            columns=["transaction_id", "transaction_timestamp"],
            filter=pc.field("transaction_id").isin(ids),
        )
        when = dict(
            zip(
                stamps["transaction_id"].to_pylist(),
                stamps["transaction_timestamp"].to_pylist(),
                strict=True,
            )
        )
        in_test = [r for r in rows if when[r["transaction_id"]] >= test_start]
        missed = [r for r in in_test if r["is_fraud_true"] and not r["is_fraud_observed"]]
        print(f"  {variant}")
        print(f"    rows written: {len(rows)}")
        print(f"    in the test period: {len(in_test)}")
        print(f"    fraud by true label: {sum(r['is_fraud_true'] for r in in_test)}")
        print(f"    fraud by observed label: {sum(r['is_fraud_observed'] for r in in_test)}")
        print(f"    true fraud labelled legitimate (missed-fraud noise): {len(missed)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
