"""The feature paths' transaction record, written as the scoring contract's message.

The inverse of `features.domain_transaction`; the parity replay asserts the round trip reproduces
the record exactly, so a field dropped or rounded at the contract boundary fails a test.
"""

from __future__ import annotations

from decimal import Decimal

from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.serving.features import CHANNELS
from fraudshield_ml.serving.generated import scoring_pb2 as pb

_CHANNEL_CODES = {name: code for code, name in CHANNELS.items()}


def to_proto(
    tx: Transaction, *, institution: str = "inst_test", reference: Reference | None = None
) -> pb.Transaction:
    if tx.amount_minor is None or tx.currency is None or tx.channel is None:
        raise ValueError(f"{tx.transaction_id}: amount_minor, currency and channel are required")
    units = reference.minor_units[tx.currency] if reference is not None else _units(tx)
    message = pb.Transaction(
        transaction_id=tx.transaction_id,
        institution_id=institution,
        account_token=tx.account_id,
        counterparty_token=tx.counterparty_id or "",
        amount=pb.Money(amount=str(Decimal(tx.amount_minor).scaleb(-units)), currency=tx.currency),
        amount_rwf=repr(tx.amount_rwf),
        channel=_CHANNEL_CODES[tx.channel],
        merchant_category_code=tx.merchant_category_code or "",
        location=pb.GeoPoint(latitude=tx.latitude, longitude=tx.longitude),
    )
    if tx.device_fingerprint is not None:
        message.device_token = tx.device_fingerprint
    if tx.agent_id is not None:
        message.agent_token = tx.agent_id
    if tx.counterparty_country is not None:
        message.counterparty_country = tx.counterparty_country
    message.transaction_timestamp.FromDatetime(tx.timestamp)
    message.received_at.FromDatetime(tx.timestamp)
    return message


#: Minor units for the currencies the tests use when no reference is passed.
_TEST_UNITS = {"RWF": 0, "UGX": 0, "KES": 2, "TZS": 2, "CDF": 2, "GBP": 2}


def _units(tx: Transaction) -> int:
    return _TEST_UNITS[tx.currency or ""]
