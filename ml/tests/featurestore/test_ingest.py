"""Labels reach the store through `fs.labels`, and the features that read outcomes change."""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import fakeredis
import pytest
from prometheus_client import CollectorRegistry

from fraudshield_contracts import events
from fraudshield_ml.features.types import CountryFacts, Transaction
from fraudshield_ml.featurestore.ingest import EventError, apply_label, apply_labels
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.featurestore.store import LABEL_LATENCY, FeatureStore, StoreMetrics

T0 = datetime(2025, 8, 1, 9, tzinfo=UTC)
REFERENCE = Reference(
    countries={"RW": CountryFacts("RW", "AF", frozenset({"EAC"}), 2)},
    country_of_currency={"RWF": "RW"},
    minor_units={"RWF": 0},
    denominations={"RWF": (1000,)},
    cell_rate_prior=0.02,
)


def store() -> FeatureStore:
    return FeatureStore(
        fakeredis.FakeRedis(decode_responses=True),
        REFERENCE,
        authoritative=True,
        metrics=StoreMetrics.create(CollectorRegistry()),
    )


def transaction(i: int, at: datetime, counterparty: str = "C1") -> Transaction:
    return Transaction(
        transaction_id=f"3f8e0c5a-6b1d-4f5e-9a2b-{i:012d}",
        account_id=f"A{i % 3}",
        timestamp=at,
        amount_rwf=5_000.0,
        latitude=-1.95,
        longitude=30.06,
        account_country="RW",
        counterparty_country="RW",
        counterparty_id=counterparty,
        amount_minor=5_000,
        currency="RWF",
        channel="MOBILE_MONEY",
        device_fingerprint="D1",
        merchant_category_code="5411",
    )


def label_event(
    transaction_id: str, label: str, available_at: datetime, **payload: object
) -> dict[str, Any]:
    return {
        "event_id": "537d91d6-f3ca-5f97-9352-7aa86c5db363",
        "event_type": "label.recorded",
        "schema_version": 1,
        "occurred_at": available_at.isoformat().replace("+00:00", "Z"),
        "institution_id": "5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
        "producer": "fraudshield-api",
        "payload": {
            "label_id": "1f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11",
            "transaction_id": transaction_id,
            "label": label,
            "source": "ANALYST",
            "label_available_at": available_at.isoformat().replace("+00:00", "Z"),
            "transaction_timestamp": T0.isoformat().replace("+00:00", "Z"),
            **payload,
        },
    }


def topic() -> Any:
    return next(t for t in events.topics() if t.name == "fs.labels")


@pytest.mark.req("FR-02-09")
def test_a_label_event_reaches_the_features_that_read_outcomes() -> None:
    """Without this, `counterparty_confirmed_fraud_90d` is 0 and the cell rate is the prior for
    every transaction in production, though the model was trained on real values (ADR 0034)."""
    s = store()
    earlier = transaction(1, T0)
    s.observe(earlier)
    scored = transaction(2, T0 + timedelta(days=1))

    before = s.context_for(scored)
    assert before.counterparty_confirmed_fraud_90d == 0
    assert before.geo_cell_fraud_rate_30d == pytest.approx(REFERENCE.cell_rate_prior)

    event = label_event(earlier.transaction_id, "FRAUD", T0 + timedelta(hours=2))
    assert events.validate_event(topic(), event) == [], "the fixture is a valid fs.labels event"
    assert apply_label(s, event)

    after = s.context_for(scored)
    assert after.counterparty_confirmed_fraud_90d == 1
    assert after.geo_cell_fraud_rate_30d > before.geo_cell_fraud_rate_30d


@pytest.mark.req("FR-02-09")
def test_a_label_is_counted_only_once_it_is_available(tmp_path: Path) -> None:
    """E.2's leakage rule: a label available after the scored transaction is not in its features."""
    s = store()
    earlier = transaction(1, T0)
    s.observe(earlier)
    apply_label(s, label_event(earlier.transaction_id, "FRAUD", T0 + timedelta(days=5)))
    soon = s.context_for(transaction(2, T0 + timedelta(days=1)))
    later = s.context_for(transaction(3, T0 + timedelta(days=6)))
    assert soon.counterparty_confirmed_fraud_90d == 0, "the verdict had not arrived yet"
    assert later.counterparty_confirmed_fraud_90d == 1


def test_a_superseding_verdict_replaces_the_earlier_one() -> None:
    s = store()
    earlier = transaction(1, T0)
    s.observe(earlier)
    apply_label(s, label_event(earlier.transaction_id, "FRAUD", T0 + timedelta(hours=1)))
    apply_label(
        s,
        label_event(
            earlier.transaction_id,
            "LEGITIMATE",
            T0 + timedelta(hours=2),
            supersedes_label_id="1f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11",
        ),
    )
    context = s.context_for(transaction(2, T0 + timedelta(days=1)))
    assert context.counterparty_confirmed_fraud_90d == 0


def test_a_batch_counts_applied_expired_and_rejected() -> None:
    s = store()
    kept = transaction(1, T0)
    s.observe(kept)
    counts = apply_labels(
        s,
        [
            label_event(kept.transaction_id, "FRAUD", T0 + timedelta(hours=1)),
            label_event("3f8e0c5a-6b1d-4f5e-9a2b-999999999999", "FRAUD", T0),
            label_event(kept.transaction_id, "UNSURE", T0),
        ],
    )
    assert counts == {"applied": 1, "expired": 1, "rejected": 1}


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda e: e.__setitem__("event_type", "transaction.scored"), "event_type"),
        (lambda e: e.__setitem__("payload", []), "payload object"),
        (lambda e: e["payload"].pop("label_available_at"), "missing"),
        (lambda e: e["payload"].__setitem__("label_available_at", "yesterday"), "ISO-8601"),
    ],
)
def test_a_malformed_event_is_refused(mutate: object, message: str) -> None:
    event = json.loads(json.dumps(label_event("t1", "FRAUD", T0)))
    mutate(event)  # type: ignore[operator]
    with pytest.raises(EventError, match=message):
        apply_label(store(), event)


@pytest.mark.req("FR-02-09")
def test_the_store_counts_features_left_constant_for_want_of_a_producer() -> None:
    """ADR 0034: serving these as constants is silent otherwise, and costs 162 flagged frauds on
    the gate model. The counter is what M9's page fires on, so it must mean "no producer" and
    nothing else (re-review N5)."""
    s = store()

    def states() -> dict[str, float]:
        return {
            state: s.metrics.missing_producer.labels(state)._value.get()
            for state in ("outcomes", "sim_swaps", "kyc_tier", "account_opened_at")
        }

    earlier = transaction(1, T0)  # account A1, counterparty C1
    s.observe(earlier)

    s.context_for(transaction(2, T0 + timedelta(days=1)))  # A2: the store has never seen it
    assert states() == {"outcomes": 0, "sim_swaps": 1, "kyc_tier": 0, "account_opened_at": 0}, (
        "a first-ever transaction has no tier or opening date for any producer to have written"
    )

    s.context_for(transaction(4, T0 + timedelta(days=1)))  # A1, known, a day after its first row
    assert states() == {"outcomes": 0, "sim_swaps": 2, "kyc_tier": 1, "account_opened_at": 1}, (
        "a verdict a day old is a label in flight, not a producer that is missing"
    )

    scored = transaction(7, T0 + timedelta(days=30))  # A1, C1, past the label-delay horizon
    s.context_for(scored)
    assert states() == {"outcomes": 1, "sim_swaps": 3, "kyc_tier": 2, "account_opened_at": 2}

    apply_label(s, label_event(earlier.transaction_id, "FRAUD", T0 + timedelta(hours=1)))
    s.record_sim_swap(scored.account_id, T0 - timedelta(days=2))
    s.set_kyc_tier(scored.account_id, 2, T0 - timedelta(days=30))
    s.set_opened_at(scored.account_id, T0 - timedelta(days=90))
    s.context_for(transaction(10, T0 + timedelta(days=31)))  # the same account again
    assert states() == {"outcomes": 1, "sim_swaps": 3, "kyc_tier": 2, "account_opened_at": 2}, (
        "with every producer writing, the page falls silent — which is how carry 1 and carry 2 "
        "are seen to have closed"
    )


@pytest.mark.req("FR-02-09")
def test_a_new_account_paying_an_old_counterparty_still_counts_as_a_missing_producer() -> None:
    """The counterparty arm reads the counterparty's key, not the account's, so a degraded read
    of the account must not hide it — that shape is a routine one, and it is the arm by which
    carry 1 is seen to have closed (re-review V4)."""
    s = FeatureStore(
        fakeredis.FakeRedis(decode_responses=True),
        REFERENCE,
        authoritative=False,  # the production shape: an absent account is DEGRADED_MODE
        metrics=StoreMetrics.create(CollectorRegistry()),
    )
    s.observe(transaction(1, T0, counterparty="C9"))  # A1 -> C9, never adjudicated

    stranger = transaction(2, T0 + timedelta(days=40), counterparty="C9")  # A2, unknown to Redis
    read = s.read(stranger)
    assert read.degraded, "the scored account's own state is absent"
    assert read.context.counterparty_confirmed_fraud_90d == 0, "served as a constant"
    assert s.metrics.missing_producer.labels("outcomes")._value.get() == 1
    assert s.metrics.missing_producer.labels("kyc_tier")._value.get() == 0, (
        "the account's own state is the fallback's business, not a producer's"
    )


@pytest.mark.req("FR-02-09")
def test_the_label_horizon_is_where_a_verdict_stops_being_in_flight() -> None:
    """`LABEL_LATENCY` decides when a silent counterparty becomes a page. Without this the
    constant was free to move anywhere in [1 d, 30 d) unnoticed (re-review V4)."""
    horizon = timedelta(microseconds=LABEL_LATENCY)
    for gap, expected in ((horizon, 0), (horizon + timedelta(microseconds=1), 1)):
        s = store()
        s.observe(transaction(1, T0, counterparty="C7"))
        s.context_for(transaction(4, T0 + gap, counterparty="C7"))
        assert s.metrics.missing_producer.labels("outcomes")._value.get() == expected, (
            f"a counterparty row {gap} old: counted={expected}"
        )
    assert horizon == timedelta(days=21), "E.3's label delay: 72 h median, log-sigma 1.2, p95 ~21 d"
