"""The store's behaviour outside the happy path: expiry, fallback, unknown state, labels, TTL."""

from __future__ import annotations

import json
import math
from datetime import UTC, datetime, timedelta
from pathlib import Path

import fakeredis
import pytest
from prometheus_client import CollectorRegistry

from fraudshield_ml import cli
from fraudshield_ml.features.types import Transaction
from fraudshield_ml.featurestore.reference import Reference, ReferenceDataError
from fraudshield_ml.featurestore.store import (
    TTL,
    Durable,
    FeatureStore,
    StoreMetrics,
    from_micros,
    micros,
)
from fraudshield_ml.serving import features as serving

T0 = datetime(2025, 6, 1, 12, tzinfo=UTC)
PACKS = {
    "RW": {
        "alpha2": "RW",
        "blocs": ["EAC"],
        "continent": "AF",
        "currency": "RWF",
        "currency_minor_units": 0,
        "round_denominations": [500, 1000],
        "utc_offset_hours": 2,
    },
    "KE": {
        "alpha2": "KE",
        "blocs": ["EAC"],
        "continent": "AF",
        "currency": "KES",
        "currency_minor_units": 2,
        "round_denominations": [5000],
        "utc_offset_hours": 3,
    },
}


def reference(tmp_path: Path) -> Reference:
    path = tmp_path / "packs.json"
    path.write_text(json.dumps(PACKS))
    return Reference.from_packs(path)


def tx(i: int, at: datetime, *, account: str = "A", device: str | None = "D1") -> Transaction:
    return Transaction(
        transaction_id=f"t{i}",
        account_id=account,
        timestamp=at,
        amount_rwf=1000.0 + i,
        latitude=-1.95,
        longitude=30.06,
        account_country="RW",
        counterparty_country="RW",
        counterparty_id="C1",
        amount_minor=1000 + i,
        currency="RWF",
        channel="MOBILE_MONEY",
        device_fingerprint=device,
        merchant_category_code="5411",
    )


def store(tmp_path: Path, **kwargs: object) -> FeatureStore:
    return FeatureStore(
        fakeredis.FakeRedis(decode_responses=True),
        reference(tmp_path),
        metrics=StoreMetrics.create(CollectorRegistry()),
        **kwargs,  # type: ignore[arg-type]
    )


def test_the_readers_agree_with_the_pipeline_s(tmp_path: Path) -> None:
    ref = reference(tmp_path)
    path = tmp_path / "packs.json"
    assert dict(ref.countries) == cli.load_packs(path)
    assert dict(ref.minor_units) == cli.minor_units_by_currency(path)
    assert dict(ref.denominations) == cli.denominations_by_currency(path)
    assert dict(ref.country_of_currency) == cli.country_by_currency(path)


def test_a_shared_currency_and_an_unknown_one_are_refused(tmp_path: Path) -> None:
    ambiguous = {**PACKS, "BI": {**PACKS["RW"], "alpha2": "BI"}}
    path = tmp_path / "ambiguous.json"
    path.write_text(json.dumps(ambiguous))
    with pytest.raises(ReferenceDataError, match="share"):
        Reference.from_packs(path)
    with pytest.raises(ReferenceDataError, match="no country pack"):
        reference(tmp_path).account_country("XOF")


@pytest.mark.req("FR-02-09")
def test_absent_state_is_unknown_unless_the_store_is_authoritative(tmp_path: Path) -> None:
    """A flushed or expired account must not read as a new one (PB-37's failure, in Redis)."""
    cautious = store(tmp_path)
    ctx = cautious.context_for(tx(1, T0))
    assert math.isnan(ctx.mean_hourly_count_30d), "unknown first-seen gives no ratio"
    assert cautious.metrics.unknown_state.labels("account")._value.get() == 1

    trusting = store(tmp_path, authoritative=True)
    ctx = trusting.context_for(tx(1, T0))
    assert ctx.mean_hourly_count_30d == 0.0, "a genuinely first transaction is its own baseline"


class RecordingFallback:
    def __init__(self, durable: Durable | None, device_first: datetime | None = None) -> None:
        self.durable = durable
        self.device_first = device_first
        self.asked: list[str] = []

    def account(self, account_id: str) -> Durable | None:
        self.asked.append(account_id)
        return self.durable

    def device_first_seen(self, device: str) -> datetime | None:
        self.asked.append(device)
        return self.device_first


@pytest.mark.req("FR-02-09")
def test_an_expired_key_is_answered_by_the_fallback(tmp_path: Path) -> None:
    fallback = RecordingFallback(
        Durable(
            first_seen=T0 - timedelta(days=200),
            last_at=T0 - timedelta(days=70),
            last_location=(-1.5, 30.5),
            counterparties=frozenset({"C1"}),
            countries=frozenset({"RW"}),
            devices=frozenset({"D1"}),
        ),
        device_first=T0 - timedelta(days=10, hours=6),
    )
    s = store(tmp_path, fallback=fallback)
    scored = tx(1, T0)
    ctx = s.context_for(scored)
    assert fallback.asked == ["A", "D1"]
    assert not ctx.counterparty_new_for_account
    assert not ctx.device.device_new_for_account
    assert ctx.device.device_age_days == 10
    assert "RW" in ctx.countries_seen
    features = serving.compute(scored, ctx, [], s.reference)
    assert features["dormancy_reactivation_flag"] == 1.0, "70 days silent, then back"
    assert features["seconds_since_last_tx"] == pytest.approx(70 * 86_400)
    assert s.metrics.fallbacks.labels("account")._value.get() == 1


def test_a_fallback_that_has_never_seen_the_account_means_new(tmp_path: Path) -> None:
    s = store(tmp_path, fallback=RecordingFallback(None))
    ctx = s.context_for(tx(1, T0))
    assert ctx.mean_hourly_count_30d == 0.0
    assert ctx.counterparty_new_for_account


@pytest.mark.req("FR-02-09")
def test_every_written_key_carries_the_thirty_day_ttl(tmp_path: Path) -> None:
    s = store(tmp_path, authoritative=True)
    s.observe(tx(1, T0))
    keys = s.redis.keys(f"{s.prefix}*")
    assert keys
    for key in keys:
        ttl = s.redis.ttl(key)
        if ":where" in key:
            assert ttl >= TTL.total_seconds(), "label locations outlive the 90-day window"
        else:
            assert 0 < ttl <= TTL.total_seconds()


@pytest.mark.req("FR-02-09")
def test_the_update_latency_is_measured(tmp_path: Path) -> None:
    s = store(tmp_path, authoritative=True)
    for i in range(5):
        s.observe(tx(i, T0 + timedelta(minutes=i)))
    samples = {
        sample.name: sample.value
        for metric in s.metrics.update_seconds.collect()
        for sample in metric.samples
    }
    assert samples["fs_feature_store_update_seconds_count"] == 5


def test_a_label_for_an_expired_transaction_is_reported_not_invented(tmp_path: Path) -> None:
    s = store(tmp_path, authoritative=True)
    assert s.observe_outcome("never-seen", True, T0) is False


def test_the_window_trims_rows_older_than_the_horizon(tmp_path: Path) -> None:
    s = store(tmp_path, authoritative=True)
    s.observe(tx(1, T0))
    s.observe(tx(2, T0 + timedelta(days=120)))
    assert s.redis.zcard(f"{s.prefix}a:A:tx") == 1
    ctx = s.context_for(tx(3, T0 + timedelta(days=121)))
    assert ctx.history_count_90d == 1
    assert ctx.last_transaction_at.ToDatetime(tzinfo=UTC) == T0 + timedelta(days=120)


def test_the_last_transaction_survives_past_the_ninety_day_window(tmp_path: Path) -> None:
    s = store(tmp_path, authoritative=True)
    s.observe(tx(1, T0))
    ctx = s.context_for(tx(2, T0 + timedelta(days=100)))
    assert ctx.history_count_90d == 0
    assert ctx.last_transaction_at.ToDatetime(tzinfo=UTC) == T0
    assert ctx.days_since_previous_activity == 100


def test_restored_durable_state_is_read(tmp_path: Path) -> None:
    s = store(tmp_path)
    s.restore_first_seen("A", T0 - timedelta(days=3))
    s.restore_device_first_seen("D9", T0 - timedelta(days=2))
    s.restore_known("A", counterparties=["C1"], countries=["KE"], devices=["D9"])
    ctx = s.context_for(tx(1, T0, device="D9"))
    assert not math.isnan(ctx.mean_hourly_count_30d)
    assert not ctx.counterparty_new_for_account
    assert list(ctx.countries_seen) == ["KE"]
    assert ctx.device.device_age_days == 2
    assert not ctx.device.device_new_for_account


def test_no_device_means_no_device_context(tmp_path: Path) -> None:
    s = store(tmp_path, authoritative=True)
    ctx = s.context_for(tx(1, T0, device=None))
    assert not ctx.HasField("device")


def test_micros_round_trip_and_refuse_naive_times() -> None:
    moment = datetime(2031, 2, 3, 4, 5, 6, 789012, tzinfo=UTC)
    assert from_micros(micros(moment)) == moment
    with pytest.raises(ValueError, match="timezone-aware"):
        micros(datetime(2031, 2, 3))  # noqa: DTZ001 - the naive time is the point
