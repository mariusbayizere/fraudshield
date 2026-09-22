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
    service = server.ScoringService(ModelHolder(Scorer(bundles[0], kit.reference)), None, writer)
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
                context = features.context_for(tx)  # what the API reads before calling Score
                counts.append(context.tx_count_1h)
                reply = score(
                    pb.ScoreRequest(transaction=to_proto(tx), context=context), timeout=10
                )
                assert reply.result.feature_vector["tx_count_1h"].number == context.tx_count_1h
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
