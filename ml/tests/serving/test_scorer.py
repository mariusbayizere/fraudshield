"""The scorer's result: FR-02-01's nine fields, FR-02-04's SHAP rule, FR-02-05 and FR-02-06."""

from __future__ import annotations

import re
from types import SimpleNamespace

import fakeredis
import pytest

from fraudshield_ml.features.registry import REGISTRY
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.serving.scorer import ModelHolder, Scorer, template_key
from fraudshield_ml.serving.thresholds import KEY, Thresholds, ThresholdStore

#: The OpenAPI contract's pattern for ShapContribution.template_key.
TEMPLATE = re.compile(r"^feature\.[a-z0-9_]+\.[a-z_]+$")
Stage = pb.StageTiming.Stage


@pytest.fixture(scope="module")
def scorer(bundles: tuple[Bundle, Bundle], kit: SimpleNamespace) -> Scorer:
    return Scorer(bundles[0], kit.reference)


@pytest.mark.req("FR-02-01", "TEST-10")
def test_every_result_carries_the_nine_fields(scorer: Scorer, kit: SimpleNamespace) -> None:
    for burst in (False, True):
        result = scorer.score(kit.request(1, burst=burst)).result
        for name in ("ensemble_score", "xgboost_score", "lightgbm_score", "anomaly_score"):
            value = getattr(result, name)
            assert isinstance(value, float)
            assert 0.0 <= value <= 1.0, name
        assert result.model_risk_tier != pb.RISK_TIER_UNSPECIFIED
        assert set(result.feature_vector) == set(REGISTRY), "exactly the 44 registered names"
        assert result.model_version == scorer.model_version != ""
        assert result.feature_registry_version.startswith("fr-")
        assert result.scoring_duration_ms >= 1
        assert result.transaction_id == kit.request(1).transaction.transaction_id
        assert result.scoring_result_id
        assert -1.0 <= result.anomaly_raw <= 0.0, "the raw forest score (D-06)"


@pytest.mark.req("FR-02-01", "D-04")
def test_structural_missingness_is_the_missing_marker_not_a_number(
    scorer: Scorer, kit: SimpleNamespace
) -> None:
    ussd = kit.transaction(2, channel="USSD", device_fingerprint=None)
    result = scorer.score(kit.request(tx=ussd)).result
    for name in (
        "device_is_new_for_account",
        "accounts_per_device_7d",
        "device_changes_24h",
        "device_age_days",
    ):
        assert result.feature_vector[name].WhichOneof("value") == "missing", name
    assert result.feature_vector["channel"].category == "USSD"
    assert result.feature_vector["amount_log1p"].WhichOneof("value") == "number"


@pytest.mark.req("FR-02-04", "D-05")
def test_shap_is_computed_at_and_above_the_threshold_only(
    scorer: Scorer, kit: SimpleNamespace
) -> None:
    flagged = scorer.score(kit.request(3, burst=True)).result
    quiet = scorer.score(kit.request(4, burst=False)).result
    assert flagged.ensemble_score >= 0.60, "precondition: the burst request is flagged"
    assert quiet.ensemble_score < 0.60, "precondition: the quiet request is not"

    assert len(flagged.shap_all) == 44
    assert len(flagged.shap_top5) == 5
    magnitudes = [abs(c.shap) for c in flagged.shap_all]
    assert magnitudes == sorted(magnitudes, reverse=True)
    assert list(flagged.shap_top5) == list(flagged.shap_all[:5])
    total = flagged.shap_base_value + sum(c.shap for c in flagged.shap_all)
    assert total == pytest.approx(flagged.final_margin, abs=0.001)
    for c in flagged.shap_all:
        assert TEMPLATE.match(c.template_key)
        assert c.increases_risk == (c.shap > 0)
    assert any(s.stage == Stage.STAGE_SHAP for s in flagged.stage_timings)

    assert not quiet.shap_all
    assert not quiet.shap_top5
    assert not any(s.stage == Stage.STAGE_SHAP for s in quiet.stage_timings)


def test_every_stage_is_timed(scorer: Scorer, kit: SimpleNamespace) -> None:
    result = scorer.score(kit.request(5)).result
    stages = {s.stage for s in result.stage_timings}
    assert stages == {
        Stage.STAGE_FEATURES,
        Stage.STAGE_ENSEMBLE,
        Stage.STAGE_CALIBRATION,
        Stage.STAGE_ISOLATION_FOREST,
    }
    assert all(s.milliseconds >= 0 for s in result.stage_timings)


@pytest.mark.req("FR-02-05", "D-06")
def test_a_synthetic_outlier_reaches_review_even_below_the_ensemble_threshold(
    scorer: Scorer, kit: SimpleNamespace
) -> None:
    """FR-02-05's acceptance scenario: extreme amount, new location, new device at once."""
    outlier = kit.transaction(
        6,
        amount_rwf=900_000_000.0,
        amount_minor=900_000_000,
        latitude=-11.7,
        longitude=27.5,
        device_fingerprint="tok_new",
    )
    ctx = kit.context(countries_seen=[])
    ctx.device.device_new_for_account = True
    ctx.device.device_changes_24h = 4
    ctx.last_location.CopyFrom(pb.GeoPoint(latitude=-1.95, longitude=30.06))
    result = scorer.score(kit.request(tx=outlier, ctx=ctx)).result
    assert result.anomaly_score > 0.7
    assert result.ensemble_score < 0.60, "precondition: the ensemble alone would not flag it"
    assert result.model_risk_tier == pb.RISK_TIER_MEDIUM, "routed to analyst review"


def test_the_tier_rule() -> None:
    t = Thresholds()
    assert t.tier(0.9, 0.0) == pb.RISK_TIER_HIGH
    assert t.tier(0.7, 0.0) == pb.RISK_TIER_MEDIUM
    assert t.tier(0.1, 0.71) == pb.RISK_TIER_MEDIUM
    assert t.tier(0.1, 0.7) == pb.RISK_TIER_LOW, "strictly greater than the review threshold"
    with pytest.raises(ValueError, match="medium < high"):
        Thresholds(medium=0.9, high=0.8)
    with pytest.raises(ValueError, match="anomaly_review"):
        Thresholds(anomaly_review=0.0)


@pytest.mark.req("FR-02-06")
def test_a_threshold_change_takes_effect_within_the_refresh_without_a_reload(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    redis = fakeredis.FakeRedis(decode_responses=True)
    now = [0.0]
    quiet = Thresholds(anomaly_review=0.999)
    store = ThresholdStore(redis, default=quiet, refresh=10.0, clock=lambda: now[0])
    scorer = Scorer(bundles[0], kit.reference, store)
    request = kit.request(7, burst=False)
    before = scorer.score(request).result
    assert before.model_risk_tier == pb.RISK_TIER_LOW, "precondition: a quiet transaction"
    assert before.anomaly_score > 0.01, "precondition: room to lower the review threshold under it"
    lowered = before.anomaly_score / 2

    redis.hset(KEY, mapping={"medium": "0.6", "high": "0.85", "anomaly_review": str(lowered)})
    now[0] = 5.0
    assert scorer.score(request).result.model_risk_tier == pb.RISK_TIER_LOW, "not yet"
    now[0] = 10.5
    after = scorer.score(request).result
    assert after.model_risk_tier == pb.RISK_TIER_MEDIUM, "the lowered threshold now applies"
    assert after.model_version == before.model_version, "the model was not reloaded"


def test_the_threshold_store_survives_a_bad_value_and_an_outage(kit: SimpleNamespace) -> None:
    redis = fakeredis.FakeRedis(decode_responses=True)
    now = [0.0]
    store = ThresholdStore(redis, refresh=1.0, clock=lambda: now[0])
    redis.hset(KEY, mapping={"medium": "0.9", "high": "0.5"})
    assert store.current() == Thresholds(), "an invalid set is ignored"

    class Down:
        def hgetall(self, _key: str) -> dict[str, str]:
            raise ConnectionError("redis down")

    outage = ThresholdStore(Down(), refresh=1.0, clock=lambda: now[0])
    assert outage.current() == Thresholds()
    with pytest.raises(ValueError, match="60 seconds"):
        ThresholdStore(redis, refresh=61.0)


def test_the_holder_swaps_atomically(bundles: tuple[Bundle, Bundle], kit: SimpleNamespace) -> None:
    a, b = (Scorer(x, kit.reference) for x in bundles)
    holder = ModelHolder(a)
    assert holder.swap_production(b) is a
    assert holder.production is b
    assert holder.swaps == 1
    assert holder.swap_shadow(a) is None
    assert holder.shadow is a


def test_template_keys_follow_the_contract_pattern() -> None:
    assert template_key("tx_count_1h", True) == "feature.tx_count_1h.increases_risk"
    assert TEMPLATE.match(template_key("channel", False))
