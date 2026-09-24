"""The reference database fallback: what M6's PostgreSQL reader must return (M5-3, carried).

When an account's Redis keys have expired (30 days idle) or Redis is down, the store asks a
`Fallback` for everything those keys held. This class answers from an in-memory record of the
transactions and reference events, exactly as M6's tables would hold them: the transactions
hypertable, `account_velocity_cache`, and the per-account durable table (PB-37).

It is the specification the acceptance test (`ml/tests/featurestore/test_db_fallback.py`) is
written against. M6 plugs a PostgreSQL implementation into the same test, and it passes only if the
features read through it are identical to the Redis path's.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime

from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.store import Durable


@dataclass
class ReplayFallback:
    transactions: dict[str, list[Transaction]] = field(default_factory=lambda: defaultdict(list))
    device_sightings: dict[str, list[datetime]] = field(default_factory=lambda: defaultdict(list))
    sim_swaps: dict[str, list[datetime]] = field(default_factory=lambda: defaultdict(list))
    tiers: dict[str, list[tuple[int, datetime]]] = field(default_factory=lambda: defaultdict(list))
    opened_at: dict[str, datetime] = field(default_factory=dict)

    def record(self, tx: Transaction) -> None:
        """A completed transaction, as the persistence consumer writes it to the database."""
        self.transactions[tx.account_id].append(tx)
        if tx.device_fingerprint is not None:
            self.device_sightings[tx.device_fingerprint].append(tx.timestamp)

    def account(self, account_id: str, before: datetime) -> Durable | None:
        earlier = sorted(
            (tx for tx in self.transactions.get(account_id, ()) if tx.timestamp < before),
            key=lambda tx: tx.timestamp,
        )
        swaps = tuple(self.sim_swaps.get(account_id, ()))
        tiers = tuple(self.tiers.get(account_id, ()))
        opened = self.opened_at.get(account_id)
        if not earlier and not (swaps or tiers or opened):
            return None
        last = earlier[-1] if earlier else None
        return Durable(
            first_seen=earlier[0].timestamp if earlier else None,
            last_at=last.timestamp if last else None,
            last_location=(last.latitude, last.longitude) if last else None,
            counterparties=frozenset(t.counterparty_id for t in earlier if t.counterparty_id),
            countries=frozenset(t.counterparty_country for t in earlier if t.counterparty_country),
            devices=frozenset(t.device_fingerprint for t in earlier if t.device_fingerprint),
            transactions=tuple(earlier),
            sim_swaps=swaps,
            tiers=tiers,
            opened_at=opened,
        )

    def device_first_seen(self, device: str, before: datetime) -> datetime | None:
        seen = [at for at in self.device_sightings.get(device, ()) if at < before]
        return min(seen) if seen else None
