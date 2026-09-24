"""Applying events to the feature store: labels today, account reference state when it exists.

`StoreWriter` folds in each scored transaction, but two trained features read state that no
transaction carries:

* `counterparty_confirmed_fraud_90d` and `geo_cell_fraud_rate_30d` read **outcomes**, which arrive
  on `fs.labels` (`contracts/kafka/schemas/label.schema.json`). `apply_label` is the function a
  consumer of that topic calls; M6/M9 own the consumer deployment, M5 owns what it does.
* `days_since_sim_swap`, `kyc_tier`, `account_age_days` and the agent features read account
  reference state, and **no topic carries it** (`contracts/kafka/topics.yaml`). That gap is
  ADR 0034 and the store's setters are the seam for whoever fills it.

Labels respect E.2's leakage rule without any work here: the store records `available_at` with the
label and every read counts only labels available before the scored transaction.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable, Mapping
from datetime import datetime
from typing import Any

from fraudshield_ml.featurestore.store import FeatureStore

EVENT_TYPE = "label.recorded"
FRAUD = "FRAUD"
LEGITIMATE = "LEGITIMATE"
LOG = logging.getLogger(__name__)


class EventError(ValueError):
    """An event this cannot apply: wrong type, or a field the payload must carry is missing."""


def _timestamp(value: str, field: str) -> datetime:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        raise EventError(f"{field}={value!r} is not an ISO-8601 instant") from None


def apply_label(store: FeatureStore, event: Mapping[str, Any]) -> bool:
    """Apply one `fs.labels` event. False when the transaction is no longer in the store.

    A superseding label (`supersedes_label_id`) needs no special case: the store keys an outcome by
    transaction, so applying the newer event replaces the older verdict.
    """
    if event.get("event_type") != EVENT_TYPE:
        raise EventError(f"event_type {event.get('event_type')!r}, expected {EVENT_TYPE!r}")
    payload = event.get("payload")
    if not isinstance(payload, Mapping):
        raise EventError("the event carries no payload object")
    missing = {"transaction_id", "label", "label_available_at"} - set(payload)
    if missing:
        raise EventError(f"the payload is missing {sorted(missing)}")
    label = payload["label"]
    if label not in (FRAUD, LEGITIMATE):
        raise EventError(f"label {label!r} is neither {FRAUD} nor {LEGITIMATE}")
    return store.observe_outcome(
        str(payload["transaction_id"]),
        label == FRAUD,
        _timestamp(str(payload["label_available_at"]), "label_available_at"),
    )


def apply_labels(store: FeatureStore, events: Iterable[Mapping[str, Any]]) -> dict[str, int]:
    """Apply a batch, counting what happened. A malformed event is counted, not raised: one bad
    message must not stop a consumer, and the count is what an alert is built on."""
    counts = {"applied": 0, "expired": 0, "rejected": 0}
    for event in events:
        try:
            counts["applied" if apply_label(store, event) else "expired"] += 1
        except EventError as error:
            counts["rejected"] += 1
            LOG.warning("label event rejected: %s", error)
    return counts
