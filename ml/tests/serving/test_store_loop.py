"""The scoring loop the API drives: context from the store, score, write back (FR-02-09)."""

from __future__ import annotations

import threading
from datetime import timedelta
from types import SimpleNamespace
from typing import Any

import fakeredis
import grpc  # type: ignore[import-untyped]
import pytest
from prometheus_client import CollectorRegistry

from fraudshield_ml.featurestore.store import FeatureStore, StoreMetrics
from fraudshield_ml.featurestore.writer import StoreWriter
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving import server
from fraudshield_ml.serving.convert import to_proto
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.serving.scorer import ModelHolder, Scorer


def store(kit: SimpleNamespace) -> FeatureStore:
    return FeatureStore(
        fakeredis.FakeRedis(decode_responses=True),
        kit.reference,
        authoritative=True,
        metrics=StoreMetrics.create(CollectorRegistry()),
    )


@pytest.mark.req("FR-02-09")
def test_each_scored_transaction_reaches_the_next_one_s_context(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    features = store(kit)
    writer = StoreWriter(features, registry=CollectorRegistry())
    service = server.ScoringService(
        ModelHolder(Scorer(bundles[0], kit.reference)), None, writer, features
    )
    grpc_server, _, port = server.build_server(service, "127.0.0.1:0", tls=None)
    grpc_server.start()
    counts = []
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            score = channel.unary_unary(
                f"/{server.SERVICE}/Score",
                request_serializer=pb.ScoreRequest.SerializeToString,
                response_deserializer=pb.ScoreResponse.FromString,
            )
            for i in range(5):
                tx = kit.transaction(i, timestamp=kit.t0 + timedelta(minutes=i))
                # ADR 0033: the API sends the transaction; the scorer reads the store itself.
                reply = score(pb.ScoreRequest(transaction=to_proto(tx)), timeout=10)
                counts.append(reply.result.account_context.tx_count_1h)
                assert reply.result.feature_vector["tx_count_1h"].number == counts[-1]
                assert not reply.result.feature_store_degraded
                writer.drain()
    finally:
        grpc_server.stop(0)
    assert counts == [0, 1, 2, 3, 4], "each transaction sees every earlier one in its hour"
    samples = {
        s.name: s.value for m in features.metrics.update_seconds.collect() for s in m.samples
    }
    assert samples["fs_feature_store_update_seconds_count"] == 5


def test_a_retried_score_writes_nothing_new(kit: SimpleNamespace) -> None:
    features = store(kit)
    tx = kit.transaction(1)
    features.observe(tx)
    features.observe(tx)
    later = kit.transaction(2, timestamp=kit.t0 + timedelta(minutes=5))
    assert features.context_for(later).tx_count_1h == 1


def test_a_store_outage_is_counted_and_never_raised(kit: SimpleNamespace) -> None:
    class Down:
        def pipeline(self, **_: Any) -> Any:
            raise ConnectionError("redis down")

    broken = FeatureStore(Down(), kit.reference, metrics=StoreMetrics.create(CollectorRegistry()))
    writer = StoreWriter(broken, registry=CollectorRegistry())
    assert writer.offer(kit.transaction(1))
    writer.drain()
    assert writer.failed._value.get() == 1


def test_a_full_queue_drops_the_write(kit: SimpleNamespace) -> None:
    gate = threading.Event()

    class Slow:
        def pipeline(self, **_: Any) -> Any:
            gate.wait(5)
            raise ConnectionError("slow")

    writer = StoreWriter(
        FeatureStore(Slow(), kit.reference, metrics=StoreMetrics.create(CollectorRegistry())),
        queue_size=1,
        registry=CollectorRegistry(),
    )
    accepted = [writer.offer(kit.transaction(i)) for i in range(6)]
    gate.set()
    writer.drain()
    assert accepted.count(False) >= 4
    assert writer.dropped._value.get() == accepted.count(False)


def _serve(service: server.ScoringService) -> tuple[Any, Any]:
    grpc_server, _, port = server.build_server(service, "127.0.0.1:0", tls=None)
    grpc_server.start()
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    score = channel.unary_unary(
        f"/{server.SERVICE}/Score",
        request_serializer=pb.ScoreRequest.SerializeToString,
        response_deserializer=pb.ScoreResponse.FromString,
    )
    return grpc_server, score


@pytest.mark.req("FR-02-09", "FR-02-01")
def test_the_scorer_reads_the_context_itself_when_the_api_sends_none(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    """ADR 0033's shape, on today's contract: the request carries the transaction only."""
    features = store(kit)
    earlier = [kit.transaction(i, timestamp=kit.t0 + timedelta(minutes=i)) for i in range(3)]
    for tx in earlier:
        features.observe(tx)
    service = server.ScoringService(
        ModelHolder(Scorer(bundles[0], kit.reference)), None, None, features
    )
    grpc_server, score = _serve(service)
    try:
        scored = kit.transaction(9, timestamp=kit.t0 + timedelta(minutes=10))
        reply = score(pb.ScoreRequest(transaction=to_proto(scored)), timeout=10)
    finally:
        grpc_server.stop(0)
    assert reply.result.feature_vector["tx_count_1h"].number == 3.0


def test_no_feature_store_is_unavailable_rather_than_scored_as_new(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    grpc_server, score = _serve(
        server.ScoringService(ModelHolder(Scorer(bundles[0], kit.reference)))
    )
    try:
        with pytest.raises(grpc.RpcError) as caught:
            score(pb.ScoreRequest(transaction=to_proto(kit.transaction(1))), timeout=10)
        assert caught.value.code() == grpc.StatusCode.UNAVAILABLE
        assert "no feature store" in caught.value.details()
    finally:
        grpc_server.stop(0)


def test_an_unreadable_store_is_unavailable_so_the_api_falls_back(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    class Down:
        def pipeline(self, **_: Any) -> Any:
            raise ConnectionError("redis down")

    broken = FeatureStore(Down(), kit.reference, metrics=StoreMetrics.create(CollectorRegistry()))
    grpc_server, score = _serve(
        server.ScoringService(ModelHolder(Scorer(bundles[0], kit.reference)), None, None, broken)
    )
    try:
        with pytest.raises(grpc.RpcError) as caught:
            score(pb.ScoreRequest(transaction=to_proto(kit.transaction(1))), timeout=10)
        assert caught.value.code() == grpc.StatusCode.UNAVAILABLE
        bad = to_proto(kit.transaction(2))
        bad.amount.currency = "XOF"
        with pytest.raises(grpc.RpcError) as caught:
            score(pb.ScoreRequest(transaction=bad), timeout=10)
        assert caught.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    finally:
        grpc_server.stop(0)
