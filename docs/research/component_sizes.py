"""Component-size distribution per relational key, for E1's density threshold.

E1 requires component folding to be chosen against a **measured** distribution rather than against
an author's sense of whether a graph "feels" sparse, and sets the threshold at a largest component
exceeding 10% of accounts. This script produces the measurement.

For each relational key it builds the bipartite account-to-entity graph, unions accounts that share
an entity, and reports the size distribution of the resulting connected components as fractions of
all accounts.

**The geo cell is approximated by a lat/lon grid**, not computed with H3. H3 resolution 6 averages
about 36 km^2; a 0.05 degree grid is about 30 km^2 at these latitudes. The question here is "how
many accounts share a cell of roughly this size", which does not depend on the tiling being
hexagonal or on any cell's identity. Taking a first-ever runtime dependency to make this input exact
would get the precision budget backwards, since the 10% threshold it feeds is itself a judgement.
Any claim about *specific* cells requires real H3; see the lab notebook.

Run:
    python docs/research/component_sizes.py <dataset-dir> \
        --output docs/research/component_sizes.json
"""

from __future__ import annotations

import argparse
import json
import subprocess
from collections import Counter
from pathlib import Path
from typing import Any

import pyarrow.parquet as pq

#: Degrees per grid cell. 0.05 deg is ~5.5 km x 5.5 km ~= 30 km^2 near the equator, against H3
#: resolution 6's ~36 km^2 average. Cell area shrinks with cos(latitude); across the simulated
#: countries (about 4 S to 4 N) that is under 0.3%.
GRID_DEGREES = 0.05

RELATIONAL_KEYS = ("counterparty", "device", "agent", "geo_cell")

#: E1: component folding is invalid above this share. A judgement, not a derived constant.
THRESHOLD = 0.10


class UnionFind:
    def __init__(self) -> None:
        self.parent: dict[str, str] = {}

    def find(self, x: str) -> str:
        root = x
        while self.parent.setdefault(root, root) != root:
            root = self.parent[root]
        while self.parent[x] != root:  # path compression, iterative
            self.parent[x], x = root, self.parent[x]
        return root

    def union(self, a: str, b: str) -> None:
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def _cell(lat: float, lon: float) -> str:
    return f"{int(lat // GRID_DEGREES)}:{int(lon // GRID_DEGREES)}"


def measure(dataset: Path) -> dict[str, Any]:
    files = sorted((dataset / "transactions").rglob("*.parquet"))
    if not files:
        raise SystemExit(f"no transaction parquet under {dataset}")

    unions = {key: UnionFind() for key in RELATIONAL_KEYS}
    accounts: set[str] = set()
    rows = 0

    for path in files:
        table = pq.read_table(
            path,
            columns=[
                "account_id",
                "counterparty_id",
                "device_fingerprint",
                "agent_id",
                "latitude",
                "longitude",
            ],
        )
        cols = {name: table.column(name).to_pylist() for name in table.column_names}
        rows += len(cols["account_id"])
        for i, account in enumerate(cols["account_id"]):
            accounts.add(account)
            for key, column in (
                ("counterparty", "counterparty_id"),
                ("device", "device_fingerprint"),
                ("agent", "agent_id"),
            ):
                entity = cols[column][i]
                if entity:  # null device for USSD, null agent outside AGENT_BANKING
                    unions[key].union(account, f"{key}:{entity}")
            lat, lon = cols["latitude"][i], cols["longitude"][i]
            if lat is not None and lon is not None:
                unions["geo_cell"].union(account, f"cell:{_cell(lat, lon)}")

    total = len(accounts)
    result: dict[str, Any] = {
        "rows": rows,
        "accounts": total,
        "grid_degrees": GRID_DEGREES,
        "threshold": THRESHOLD,
        "keys": {},
    }
    for key in RELATIONAL_KEYS:
        uf = unions[key]
        sizes = Counter(uf.find(a) for a in accounts)
        ordered = sorted(sizes.values(), reverse=True)
        largest = ordered[0] / total
        result["keys"][key] = {
            "components": len(ordered),
            "largest_accounts": ordered[0],
            "largest_share": round(largest, 6),
            "median_size": ordered[len(ordered) // 2],
            "p99_size": ordered[max(0, int(len(ordered) * 0.01))],
            "component_folding_valid": bool(largest <= THRESHOLD),
        }
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    result = measure(args.dataset)
    # E2 and the citable-run rule: the tree the measurement was taken on is part of the result.
    result["tree"] = subprocess.run(
        ["/usr/bin/git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True
    ).stdout.strip()
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    print(f"rows {result['rows']:,}  accounts {result['accounts']:,}  tree {result['tree'][:7]}")
    for key, stats in result["keys"].items():
        verdict = "OK" if stats["component_folding_valid"] else "DEGENERATE"
        print(
            f"  {key:14s} components {stats['components']:>7,}  "
            f"largest {stats['largest_share']:>8.4%}  {verdict}"
        )


if __name__ == "__main__":
    main()
