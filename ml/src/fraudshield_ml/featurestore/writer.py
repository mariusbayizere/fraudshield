"""Feeding the store from the scorer, off the hot path (FR-02-09).

**Why the scorer writes, not a Kafka consumer.** The frozen `fs.transactions.scored` payload carries
no `counterparty_country` (and makes the location optional), so a consumer of that topic could not
maintain the account's corridor set or its location history, and `is_new_country_for_account` and
the geographic features would drift from what the model was trained on. The scorer holds the full
contract transaction, so each worker hands every scored transaction to this writer, which folds it
into Redis on a background thread. The contract gap is recorded in `docs/parallel/M5_updates.md`.

**Semantics.** Writing is idempotent — every structure is keyed by transaction id, so a retried
score writes nothing new — and order-independent, because every read is windowed by timestamp.
Transactions decided by the rule fallback while ML was down reach the store when the replay job
re-scores them after recovery (C.4). A full queue drops the write and counts it, like shadow
scoring: FR-02-09 measures the update, and a production decision must never wait for it.
"""

from __future__ import annotations

import logging
import queue
import threading
from typing import Any

from prometheus_client import CollectorRegistry, Counter

from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.store import FeatureStore

LOG = logging.getLogger(__name__)
QUEUE_SIZE = 20_000


class StoreWriter:
    def __init__(
        self,
        store: FeatureStore,
        *,
        queue_size: int = QUEUE_SIZE,
        registry: CollectorRegistry | None = None,
    ) -> None:
        kwargs: dict[str, Any] = {"registry": registry} if registry is not None else {}
        self.store = store
        self.dropped = Counter(
            "fs_feature_store_writes_dropped_total",
            "Store updates dropped because the writer's queue was full",
            **kwargs,
        )
        self.failed = Counter(
            "fs_feature_store_writes_failed_total", "Store updates that raised", **kwargs
        )
        self._queue: queue.Queue[Transaction] = queue.Queue(maxsize=queue_size)
        threading.Thread(target=self._run, name="store-writer", daemon=True).start()

    def offer(self, transaction: Transaction) -> bool:
        try:
            self._queue.put_nowait(transaction)
        except queue.Full:
            self.dropped.inc()
            return False
        return True

    def drain(self) -> None:
        self._queue.join()

    def _run(self) -> None:
        while True:
            transaction = self._queue.get()
            try:
                self.store.observe(transaction)
            except Exception:
                self.failed.inc()
                LOG.warning(
                    "feature store update failed for %s", transaction.transaction_id, exc_info=True
                )
            finally:
                self._queue.task_done()
