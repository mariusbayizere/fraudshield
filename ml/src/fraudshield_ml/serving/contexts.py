"""Where the scorer gets an account's context (ADR 0033): the feature store, or a fixed set.

Since ADR 0033 the API sends the transaction and the scorer reads the account's context itself.
Production reads the Redis feature store (`featurestore.store.FeatureStore.read`). `StaticContexts`
serves reads computed ahead of time, and exists for one purpose: benchmarking the scorer on a
machine with no Redis. A benchmark run against it measures scoring and excludes the store read,
and its report says so.
"""

from __future__ import annotations

import base64
import json
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Protocol

from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.store import ContextRead
from fraudshield_ml.serving.generated import scoring_pb2 as pb


class ContextSource(Protocol):
    def read(self, tx: Transaction) -> ContextRead: ...


class StaticContexts:
    """Reads keyed by transaction id, loaded from a JSON-lines file. Benchmark use only."""

    def __init__(self, reads: Mapping[str, ContextRead]) -> None:
        self._reads = dict(reads)

    def read(self, tx: Transaction) -> ContextRead:
        try:
            return self._reads[tx.transaction_id]
        except KeyError:
            raise LookupError(f"no precomputed context for {tx.transaction_id}") from None

    @staticmethod
    def write(path: Path, reads: Iterable[tuple[str, ContextRead]]) -> Path:
        with path.open("w", encoding="utf-8") as out:
            for transaction_id, read in reads:
                record = {
                    "transaction_id": transaction_id,
                    "context": base64.b64encode(read.context.SerializeToString()).decode(),
                    "exact_ages": read.exact_ages,
                    "degraded": read.degraded,
                }
                out.write(json.dumps(record) + "\n")
        return path

    @staticmethod
    def load(path: Path) -> StaticContexts:
        reads: dict[str, ContextRead] = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            record = json.loads(line)
            reads[record["transaction_id"]] = ContextRead(
                context=pb.AccountContext.FromString(base64.b64decode(record["context"])),
                exact_ages={k: float(v) for k, v in record["exact_ages"].items()},
                degraded=bool(record["degraded"]),
            )
        return StaticContexts(reads)
