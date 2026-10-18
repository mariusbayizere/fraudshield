"""Output tables of FraudShield-EAC-Transactions (ADR 0022).

``transactions`` follows the ingestion contract (``TransactionIngestRequest``) plus ``amount_rwf``.
Labels live in ``labels`` so behavioural columns and ground truth are never mixed by accident.
Fraud and legitimate rows are built through the same :class:`Rows` buffer, so they share types,
null patterns and value formats by construction.
"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import pyarrow as pa

TRANSACTIONS = pa.schema(
    [
        pa.field("transaction_id", pa.string(), nullable=False),
        pa.field("account_id", pa.string(), nullable=False),
        pa.field("counterparty_id", pa.string(), nullable=False),
        pa.field("amount", pa.decimal128(18, 4), nullable=False),
        pa.field("currency", pa.string(), nullable=False),
        pa.field("amount_rwf", pa.decimal128(18, 4), nullable=False),
        pa.field("channel", pa.string(), nullable=False),
        pa.field("merchant_category_code", pa.string(), nullable=False),
        pa.field("latitude", pa.float64(), nullable=False),
        pa.field("longitude", pa.float64(), nullable=False),
        pa.field("device_fingerprint", pa.string(), nullable=True),
        pa.field("agent_id", pa.string(), nullable=True),
        pa.field("counterparty_country", pa.string(), nullable=False),
        pa.field("transaction_timestamp", pa.timestamp("us", tz="UTC"), nullable=False),
    ]
)

LABELS = pa.schema(
    [
        pa.field("transaction_id", pa.string(), nullable=False),
        pa.field("is_fraud_observed", pa.bool_(), nullable=False),
        pa.field("is_fraud_true", pa.bool_(), nullable=False),
        pa.field("fraud_type", pa.string(), nullable=True),
        pa.field("scenario_variant", pa.string(), nullable=True),
        pa.field("label_available_at", pa.timestamp("us", tz="UTC"), nullable=False),
    ]
)

ACCOUNT_EVENTS = pa.schema(
    [
        pa.field("account_id", pa.string(), nullable=False),
        pa.field("event_type", pa.string(), nullable=False),
        pa.field("event_timestamp", pa.timestamp("us", tz="UTC"), nullable=False),
    ]
)


@dataclass
class Rows:
    """Column buffer for one simulated batch; every generator appends through :meth:`add`."""

    columns: dict[str, list[object]] = field(
        default_factory=lambda: {
            name: []
            for name in (
                *TRANSACTIONS.names,
                "is_fraud_true",
                "fraud_type",
                "scenario_variant",
            )
        }
    )

    def add(self, **values: object) -> None:
        for name, column in self.columns.items():
            column.append(values.get(name))

    def extend(self, other: Rows) -> None:
        for name, column in self.columns.items():
            column.extend(other.columns[name])

    def __len__(self) -> int:
        return len(self.columns["transaction_id"])


def micros(values: np.ndarray) -> list[int]:
    return [int(v) for v in values]
