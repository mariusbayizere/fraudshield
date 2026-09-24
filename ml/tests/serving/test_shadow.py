"""Shadow scoring off the hot path (FR-02-08, TEST-08) and D-11's promotion gate (ML-GATE-13)."""

from __future__ import annotations

import json
import random
import threading
import time
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from prometheus_client import CollectorRegistry

from fraudshield_contracts import events
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.serving.scorer import ModelHolder, Scorer
from fraudshield_ml.serving.shadow import (
    Comparison,
    JsonLinesSink,
    ProducerSink,
    ShadowMetrics,
    ShadowRunner,
    envelope,
    promotion_gate,
    psi,
)

NOW = datetime(2026, 9, 22, 12, tzinfo=UTC)


def topic() -> Any:
    return next(t for t in events.topics() if t.name == "fs.ml.shadow")


class ListSink:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict[str, Any]]] = []

    def emit(self, key: str, event: dict[str, Any]) -> None:
        self.events.append((key, event))


def runner(holder: ModelHolder, sink: Any, **kwargs: Any) -> ShadowRunner:
    return ShadowRunner(
        lambda: holder.shadow,
        sink,
        metrics=ShadowMetrics.create(CollectorRegistry()),
        clock=lambda: NOW,
        **kwargs,
    )


@pytest.mark.req("FR-02-08", "TEST-08")
def test_the_event_is_valid_against_the_frozen_schema() -> None:
    event = envelope(
        transaction_id="3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11",
        institution_id="5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b",
        production_version="ensemble-aaaaaaaaaaaa",
        production_score=0.91,
        shadow_version="ensemble-bbbbbbbbbbbb",
        shadow_score=0.88,
        shadow_tier=pb.RISK_TIER_HIGH,
        scored_at=NOW,
        traceparent="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
    )
    assert events.validate_event(topic(), event) == []
    assert event["payload"]["shadow_risk_tier"] == "HIGH"


@pytest.mark.req("FR-02-08", "TEST-08")
def test_every_transaction_is_shadow_scored_and_logged_with_both_scores(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace, tmp_path: Path
) -> None:
    production, shadow = (Scorer(b, kit.reference) for b in bundles)
    holder = ModelHolder(production, shadow)
    log = tmp_path / "shadow.jsonl"
    shadowing = runner(holder, JsonLinesSink(log))
    results = []
    for i in range(12):
        request, read = kit.request(i), kit.read(burst=i % 3 == 0)
        result = production.score(request, read).result
        results.append((request, read, result))
        assert shadowing.offer(request, result, read)
    shadowing.drain()

    lines = [json.loads(line) for line in log.read_text().splitlines()]
    assert len(lines) == 12
    differed = 0
    for line, (request, read, result) in zip(lines, results, strict=True):
        payload = line["value"]["payload"]
        assert line["key"] == result.transaction_id
        assert payload["production_model_version"] == production.model_version
        assert payload["production_ensemble_score"] == result.ensemble_score
        assert payload["shadow_model_version"] == shadow.model_version
        # The shadow score is the shadow model's own, not a copy of production's: it is what the
        # comparator and D-11's gate are computed from (review finding 6).
        expected = shadow.score(request, read).result
        assert payload["shadow_ensemble_score"] == expected.ensemble_score
        differed += payload["shadow_ensemble_score"] != payload["production_ensemble_score"]
        assert events.validate_event(topic(), line["value"]) == []
    assert differed, "two different models scoring the same rows must not agree exactly"
    assert len(shadowing.comparison.production) == 12
    assert shadowing.metrics.scored._value.get() == 12


def test_nothing_is_queued_when_shadow_mode_is_off(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    production = Scorer(bundles[0], kit.reference)
    sink = ListSink()
    shadowing = runner(ModelHolder(production), sink)
    request = kit.request(1)
    read = kit.read()
    assert not shadowing.offer(request, production.score(request, read).result, read)
    shadowing.drain()
    assert sink.events == []


@pytest.mark.req("FR-02-08")
def test_a_full_queue_drops_rather_than_blocking_production(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    release = threading.Event()

    class Stuck:
        model_version = "stuck"

        def score(self, request: pb.ScoreRequest, read: Any) -> Any:
            release.wait(5)
            raise RuntimeError("never mind")

    production = Scorer(bundles[0], kit.reference)
    holder = ModelHolder(production, Stuck())  # type: ignore[arg-type]
    shadowing = runner(holder, ListSink(), queue_size=2)
    request = kit.request(1)
    read = kit.read()
    result = production.score(request, read).result
    started = time.perf_counter()
    accepted = [shadowing.offer(request, result, read) for _ in range(10)]
    elapsed = time.perf_counter() - started
    release.set()
    shadowing.drain()
    assert elapsed < 0.1, "offer never waits"
    assert accepted.count(True) <= 3, "one in flight plus a queue of two"
    assert shadowing.metrics.dropped._value.get() == accepted.count(False)
    assert shadowing.metrics.failed._value.get() == accepted.count(True), "failures are counted"


def test_a_new_shadow_version_starts_a_new_comparison(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    production, other = (Scorer(b, kit.reference) for b in bundles)
    holder = ModelHolder(production, other)
    shadowing = runner(holder, ListSink())
    request = kit.request(1)
    read = kit.read()
    result = production.score(request, read).result
    shadowing.offer(request, result, read)
    shadowing.drain()
    holder.swap_shadow(production)
    shadowing.offer(request, result, read)
    shadowing.drain()
    assert shadowing.comparison.shadow_version == production.model_version
    assert len(shadowing.comparison.production) == 1


def test_the_producer_sink_writes_the_topic_keyed_by_transaction() -> None:
    sent: list[tuple[str, bytes, bytes]] = []

    class Producer:
        def produce(self, topic: str, *, key: bytes, value: bytes) -> None:
            sent.append((topic, key, value))

    ProducerSink(Producer()).emit("tx1", {"a": 1})
    assert sent == [("fs.ml.shadow", b"tx1", b'{"a":1}')]


# ------------------------------------------------------------------ D-11 promotion gate


def comparison(
    n: int, *, shift: float = 0.0, started: datetime = NOW - timedelta(hours=30), seed: int = 1
) -> tuple[Comparison, dict[str, bool]]:
    rng = random.Random(seed)  # noqa: S311 - test data, not secrets
    c = Comparison(started=started)
    labels: dict[str, bool] = {}
    for i in range(n):
        fraud = rng.random() < 0.05
        p = min(1.0, max(0.0, (0.7 if fraud else 0.1) + rng.gauss(0, 0.15)))
        s = min(1.0, max(0.0, p + shift + rng.gauss(0, 0.02)))
        c.add(f"t{i}", p, s)
        if rng.random() < 0.5:
            labels[f"t{i}"] = fraud
    return c, labels


@pytest.mark.req("ML-GATE-13", "D-11")
def test_a_shadow_model_that_meets_every_clause_is_promotable() -> None:
    c, labels = comparison(50_000)
    decision = promotion_gate(c, labels, NOW)
    assert decision.promote, decision.reasons
    assert decision.label_coverage == pytest.approx(0.5, abs=0.01)
    assert decision.auc_delta is not None
    assert decision.auc_delta >= -0.010


@pytest.mark.req("ML-GATE-13", "D-11")
def test_below_thirty_percent_coverage_the_gate_says_insufficient_labels() -> None:
    c, labels = comparison(50_000)
    few = dict(list(labels.items())[: int(0.2 * 50_000)])
    decision = promotion_gate(c, few, NOW)
    assert not decision.promote
    assert any(r.startswith("Insufficient labels") for r in decision.reasons)


@pytest.mark.req("ML-GATE-13", "D-11")
def test_each_failing_clause_is_named() -> None:
    short, labels = comparison(1_000, started=NOW - timedelta(hours=3))
    reasons = " | ".join(promotion_gate(short, labels, NOW).reasons)
    assert "under 24 h" in reasons
    assert "need 50000" in reasons

    rng = random.Random(2)  # noqa: S311 - test data, not secrets
    worse, labels = comparison(50_000)
    worse.shadow = type(worse.shadow)(
        (rng.random() for _ in worse.shadow), maxlen=worse.shadow.maxlen
    )
    decision = promotion_gate(worse, labels, NOW)
    assert any("AUC delta" in r for r in decision.reasons)
    assert any("PSI" in r for r in decision.reasons)


def test_psi_is_zero_for_identical_distributions_and_large_for_a_shift() -> None:
    values = [i / 1000 for i in range(1000)]
    assert psi(values, values) == pytest.approx(0.0, abs=1e-12)
    assert psi(values, [min(1.0, v + 0.5) for v in values]) > 0.2


def test_the_comparison_reports_its_running_metrics() -> None:
    assert Comparison(started=NOW).metrics() == {"shadow_scored": 0.0}
    c, _ = comparison(100, shift=0.1)
    metrics = c.metrics()
    assert metrics["shadow_scored"] == 100
    assert metrics["shadow_mean_abs_score_diff"] > 0.05


# ------------------------------------------------------------------ D-11's numbers, at the edge


def _windowed(
    production: list[float], shadow: list[float], labels: list[bool]
) -> tuple[Comparison, dict[str, bool]]:
    """A comparison that satisfies every D-11 clause except the ones a test varies."""
    c = Comparison(started=NOW - timedelta(hours=30), shadow_version="v")
    marks: dict[str, bool] = {}
    for i, (p, s, y) in enumerate(zip(production, shadow, labels, strict=True)):
        c.add(f"t{i}", p, s)
        marks[f"t{i}"] = y  # full label coverage: the clause under test is the only one failing
    return c, marks


def _ranked(demoted: float) -> tuple[list[float], list[float], list[bool]]:
    """50,000 rows with 100 frauds. The shadow model demotes `demoted` frauds' worth of ranking,
    which costs `demoted / 100` of AUC: the gate's bound is 0.010, so 0.6 passes and 1.4 fails."""
    positives, negatives = 100, 49_900
    labels = [True] * positives + [False] * negatives
    production = [0.90 + i / 1e6 for i in range(positives)] + [
        0.10 + i / 1e6 for i in range(negatives)
    ]
    shadow = list(production)
    whole, part = int(demoted), demoted - int(demoted)
    for i in range(whole):  # below every negative
        shadow[i] = 0.01
    if part:  # one fraud placed so it beats only (1 - part) of the negatives
        shadow[whole] = 0.10 + (1.0 - part) * negatives / 1e6
    return production, shadow, labels


@pytest.mark.req("ML-GATE-13", "D-11")
@pytest.mark.parametrize(
    ("demoted", "promotes"), [(0.6, True), (1.4, False)], ids=["delta -0.006", "delta -0.014"]
)
def test_the_auc_delta_bound_is_where_d11_puts_it(demoted: float, promotes: bool) -> None:
    production, shadow, labels = _ranked(demoted)
    comparison, marks = _windowed(production, shadow, labels)
    decision = promotion_gate(comparison, marks, NOW)
    assert decision.auc_delta is not None
    assert decision.auc_delta == pytest.approx(-demoted / 100, abs=0.002)
    assert decision.promote is promotes, decision.reasons
    if not promotes:
        assert any("AUC delta" in reason for reason in decision.reasons)


@pytest.mark.req("ML-GATE-13", "D-11")
@pytest.mark.parametrize("shift", [0.088, 0.090], ids=["psi 0.184", "psi 0.205"])
def test_the_psi_bound_is_where_d11_puts_it(shift: float) -> None:
    """A monotone shift leaves the ranking, and so the AUC delta, untouched: only PSI moves.

    The scores are spread over [0, 1) rather than the two tight clusters `_ranked` builds, because
    PSI is a statement about a distribution across equal-mass bins, and any shift empties a
    degenerate one.
    """
    rows = 50_000
    production = [i / rows for i in range(rows)]
    labels = [i % 500 == 0 for i in range(rows)]
    # Affine, not clipped: clipping ties the top rows together and moves the AUC.
    shadow = [shift + p * (1.0 - shift) for p in production]
    comparison, marks = _windowed(production, shadow, labels)
    decision = promotion_gate(comparison, marks, NOW)
    assert decision.auc_delta == pytest.approx(0.0, abs=1e-9), "the shift is rank-preserving"
    assert decision.psi is not None
    # Either side of D-11's 0.2, and close to it: a loosened bound must fail this, not sail past.
    over = decision.psi >= 0.2
    assert over is (shift >= 0.089), f"psi {decision.psi:.3f} for shift {shift}"
    assert decision.psi == pytest.approx(0.2, abs=0.03)
    assert decision.promote is not over, decision.reasons
    if over:
        assert any("PSI" in reason for reason in decision.reasons)
