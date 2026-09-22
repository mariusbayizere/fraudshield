"""Shadow scoring off the hot path (FR-02-08) and D-11's promotion gate (ML-GATE-13).

**Off the hot path, literally.** After the production result is built, the request is offered to a
bounded queue and the call returns. A worker thread scores it with the shadow model and emits an
`fs.ml.shadow` event (the frozen schema in `contracts/kafka/schemas/ml-shadow.schema.json`, in the
standard envelope). A full queue **drops** the shadow score and counts the drop: a shadow model must
never slow, block or fail a production decision, and a dropped comparison costs a sample, not a
customer's latency. Only the production result is returned or acted on.

**Where the comparison goes.** Events go to a `ShadowSink`: a JSON-lines file here, or any producer
with `produce(topic, key, value)` for Kafka (the client and its wiring belong to the worker
deployment, M6/M9). The running comparison — count, score-distribution PSI, mean absolute score
difference — is flushed to MLflow as metrics on an interval, not per transaction: an HTTP call to
the tracking server for every scored transaction would put the registry on the scoring rate.

**D-11's gate.** Promotion needs **all** of: at least 24 h of shadow scoring; at least 50,000 shadow
scores; label coverage of at least 30% over the window; an absolute AUC-ROC delta (shadow minus
production, on the labelled rows) of at least -0.010; and a score-distribution PSI below 0.2. Below
30% coverage the gate reports "Insufficient labels" and blocks, whatever the other numbers say.
"""

from __future__ import annotations

import json
import logging
import math
import queue
import threading
import uuid
from collections import deque
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Protocol

from prometheus_client import CollectorRegistry, Counter

from fraudshield_ml.featurestore.store import ContextRead
from fraudshield_ml.metrics.single_feature import auc
from fraudshield_ml.serving.generated import scoring_pb2 as pb

TOPIC = "fs.ml.shadow"
EVENT_TYPE = "shadow.scored"
SCHEMA_VERSION = 1
PRODUCER = "fraudshield-ml-worker"
QUEUE_SIZE = 10_000
TIERS: dict[int, str] = {
    pb.RISK_TIER_LOW: "LOW",
    pb.RISK_TIER_MEDIUM: "MEDIUM",
    pb.RISK_TIER_HIGH: "HIGH",
}
LOG = logging.getLogger(__name__)

MIN_DURATION = timedelta(hours=24)
MIN_SCORED = 50_000
MIN_LABEL_COVERAGE = 0.30
MIN_AUC_DELTA = -0.010
MAX_PSI = 0.2
PSI_BINS = 10


def _timestamp(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def envelope(  # noqa: PLR0913 - one keyword per schema field, which is the point
    *,
    transaction_id: str,
    institution_id: str,
    production_version: str,
    production_score: float,
    shadow_version: str,
    shadow_score: float,
    shadow_tier: int,
    scored_at: datetime,
    traceparent: str = "",
) -> dict[str, Any]:
    """One `fs.ml.shadow` message value, envelope and payload, per the frozen schemas."""
    event: dict[str, Any] = {
        "event_id": str(uuid.uuid4()),
        "event_type": EVENT_TYPE,
        "schema_version": SCHEMA_VERSION,
        "occurred_at": _timestamp(scored_at),
        "institution_id": institution_id,
        "producer": PRODUCER,
        "payload": {
            "transaction_id": transaction_id,
            "production_model_version": production_version,
            "production_ensemble_score": production_score,
            "shadow_model_version": shadow_version,
            "shadow_ensemble_score": shadow_score,
            "shadow_risk_tier": TIERS.get(shadow_tier, "LOW"),
            "scored_at": _timestamp(scored_at),
        },
    }
    if traceparent:
        event["traceparent"] = traceparent
    return event


class ShadowSink(Protocol):
    def emit(self, key: str, event: dict[str, Any]) -> None: ...


class JsonLinesSink:
    """Append each event to a file: the development sink, and the audit trail in tests."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.Lock()

    def emit(self, key: str, event: dict[str, Any]) -> None:
        line = json.dumps({"key": key, "value": event}, separators=(",", ":"))
        with self._lock, self._path.open("a", encoding="utf-8") as out:
            out.write(line + "\n")


class ProducerSink:
    """Adapts any Kafka producer exposing `produce(topic, key=..., value=...)`."""

    def __init__(self, producer: Any, topic: str = TOPIC) -> None:
        self._producer = producer
        self._topic = topic

    def emit(self, key: str, event: dict[str, Any]) -> None:
        self._producer.produce(
            self._topic, key=key.encode(), value=json.dumps(event, separators=(",", ":")).encode()
        )


#: The in-process comparison keeps at most this many recent pairs. The promotion decision over the
#: full 24 h window belongs to the `shadow-comparator` consumer of `fs.ml.shadow` (topics.yaml),
#: which calls `promotion_gate` on a `Comparison` it builds from the topic; at 10,000 TPS a day is
#: far more pairs than a scoring process should hold.
MAX_KEPT = 200_000


@dataclass
class Comparison:
    """Production-versus-shadow pairs since the shadow model was loaded (bounded in process)."""

    started: datetime
    production: deque[float] = field(default_factory=lambda: deque(maxlen=MAX_KEPT))
    shadow: deque[float] = field(default_factory=lambda: deque(maxlen=MAX_KEPT))
    transactions: deque[str] = field(default_factory=lambda: deque(maxlen=MAX_KEPT))
    shadow_version: str = ""

    def add(self, transaction_id: str, production: float, shadow: float) -> None:
        self.transactions.append(transaction_id)
        self.production.append(production)
        self.shadow.append(shadow)

    def metrics(self) -> dict[str, float]:
        if not self.production:
            return {"shadow_scored": 0.0}
        diff = sum(abs(a - b) for a, b in zip(self.production, self.shadow, strict=True))
        return {
            "shadow_scored": float(len(self.production)),
            "shadow_mean_abs_score_diff": diff / len(self.production),
            "shadow_psi": psi(self.production, self.shadow),
        }


def psi(expected: Sequence[float], actual: Sequence[float], bins: int = PSI_BINS) -> float:
    """Population stability index of `actual` against `expected`, in equal-mass bins of
    `expected`. Empty bins are floored at a small proportion so the log is defined."""
    ordered = sorted(expected)
    edges = [ordered[min(len(ordered) - 1, (i * len(ordered)) // bins)] for i in range(1, bins)]

    def shares(values: Sequence[float]) -> list[float]:
        counts = [0] * bins
        for v in values:
            counts[sum(1 for e in edges if v > e)] += 1
        return [max(c / len(values), 1e-4) for c in counts]

    e, a = shares(expected), shares(actual)
    return sum((y - x) * math.log(y / x) for x, y in zip(e, a, strict=True))


@dataclass(frozen=True)
class GateDecision:
    promote: bool
    reasons: tuple[str, ...]
    scored: int
    label_coverage: float
    auc_delta: float | None
    psi: float | None
    #: How long the shadow window ran, and which model it measured. A promotion re-checks every
    #: D-11 clause from these numbers, so a report that omits one is refused (re-review V2).
    window_hours: float | None = None
    shadow_version: str | None = None


def promotion_gate(comparison: Comparison, labels: dict[str, bool], now: datetime) -> GateDecision:
    """D-11, every clause, with the reason for each failure named."""
    transactions = list(comparison.transactions)
    production, shadow = list(comparison.production), list(comparison.shadow)
    scored = len(production)
    labelled = [i for i, t in enumerate(transactions) if t in labels]
    coverage = len(labelled) / scored if scored else 0.0
    reasons: list[str] = []
    if now - comparison.started < MIN_DURATION:
        reasons.append(f"shadow window {now - comparison.started} is under 24 h")
    if scored < MIN_SCORED:
        reasons.append(f"{scored} shadow scores, need {MIN_SCORED}")
    delta: float | None = None
    stability: float | None = None
    if coverage < MIN_LABEL_COVERAGE:
        reasons.append(f"Insufficient labels: coverage {coverage:.1%} is under 30%")
    else:
        y = [labels[transactions[i]] for i in labelled]
        if any(y) and not all(y):
            delta = auc([shadow[i] for i in labelled], y) - auc(
                [production[i] for i in labelled], y
            )
            if delta < MIN_AUC_DELTA:
                reasons.append(f"AUC delta {delta:+.4f} is below -0.010")
        else:
            reasons.append("Insufficient labels: the labelled rows hold only one class")
    if scored:
        stability = psi(production, shadow)
        if stability >= MAX_PSI:
            reasons.append(f"score PSI {stability:.3f} is not below 0.2")
    return GateDecision(
        promote=not reasons,
        reasons=tuple(reasons),
        scored=scored,
        label_coverage=coverage,
        auc_delta=delta,
        psi=stability,
        window_hours=(now - comparison.started).total_seconds() / 3600,
        shadow_version=comparison.shadow_version or None,
    )


class MlflowComparisonLog:
    """Flushes the running comparison to one MLflow run per shadow model version (FR-02-08).

    `client` is `serving.registry.MlflowRegistry`, or anything with its `experiment`, `start_run`
    and `log_metrics` methods. A tracking-server failure is logged and skipped: the comparison
    dashboard going stale must not stop shadow scoring, let alone production.
    """

    EXPERIMENT = "fraudshield-shadow"

    def __init__(self, client: Any, *, production_version: Callable[[], str]) -> None:
        self._client = client
        self._production = production_version
        self._runs: dict[str, str] = {}
        self._step = 0

    def flush(self, comparison: Comparison) -> None:
        if not comparison.shadow_version:
            return
        try:
            run = self._runs.get(comparison.shadow_version)
            if run is None:
                run = self._client.start_run(
                    self._client.experiment(self.EXPERIMENT),
                    f"shadow {comparison.shadow_version}",
                    {
                        "shadow_model_version": comparison.shadow_version,
                        "production_model_version": self._production(),
                        "window_started": _timestamp(comparison.started),
                    },
                )
                self._runs[comparison.shadow_version] = run
            self._step += 1
            self._client.log_metrics(run, comparison.metrics(), self._step)
        except Exception:
            LOG.warning("shadow comparison not logged to MLflow", exc_info=True)


@dataclass
class ShadowMetrics:
    scored: Counter
    dropped: Counter
    failed: Counter

    @staticmethod
    def create(registry: CollectorRegistry | None = None) -> ShadowMetrics:
        kwargs: dict[str, Any] = {"registry": registry} if registry is not None else {}
        return ShadowMetrics(
            scored=Counter("fs_shadow_scored_total", "Shadow scores emitted", **kwargs),
            dropped=Counter(
                "fs_shadow_dropped_total",
                "Shadow scores dropped because the queue was full (never blocks production)",
                **kwargs,
            ),
            failed=Counter("fs_shadow_failed_total", "Shadow scoring errors", **kwargs),
        )


@dataclass(frozen=True)
class _Job:
    request: pb.ScoreRequest
    production_version: str
    production_score: float
    #: The context production scored with: the shadow model scores exactly the same state.
    read: ContextRead


class ShadowRunner:
    """The worker that scores shadow off the hot path. `offer` never blocks."""

    def __init__(  # noqa: PLR0913 - keyword-only configuration with defaults
        self,
        shadow: Callable[[], Any],
        sink: ShadowSink,
        *,
        metrics: ShadowMetrics | None = None,
        queue_size: int = QUEUE_SIZE,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
        log: MlflowComparisonLog | None = None,
        flush_every: timedelta = timedelta(seconds=60),
    ) -> None:
        #: Returns the current shadow `Scorer`, or None when shadow mode is off.
        self._shadow = shadow
        self._sink = sink
        self._queue: queue.Queue[_Job] = queue.Queue(maxsize=queue_size)
        self._clock = clock
        self.metrics = metrics or ShadowMetrics.create()
        self.comparison = Comparison(started=clock())
        self._log = log
        self._flush_every = flush_every
        self._flushed = clock()
        self._thread = threading.Thread(target=self._run, name="shadow", daemon=True)
        self._thread.start()

    def offer(
        self, request: pb.ScoreRequest, production: pb.ScoringResult, read: ContextRead
    ) -> bool:
        if self._shadow() is None:
            return False
        try:
            self._queue.put_nowait(
                _Job(request, production.model_version, production.ensemble_score, read)
            )
        except queue.Full:
            self.metrics.dropped.inc()
            return False
        return True

    def drain(self) -> None:
        """Block until every queued job has been processed (tests and shutdown)."""
        self._queue.join()

    def reset(self) -> None:
        """A new shadow model starts a new comparison window (D-11 counts from load)."""
        self.comparison = Comparison(started=self._clock())

    def _run(self) -> None:
        while True:
            job = self._queue.get()
            try:
                self._process(job)
            finally:
                self._queue.task_done()

    def _process(self, job: _Job) -> None:
        scorer = self._shadow()
        if scorer is None:
            return
        try:
            result = scorer.score(job.request, job.read).result
        except Exception:
            self.metrics.failed.inc()
            LOG.exception("shadow scoring failed for %s", job.request.transaction.transaction_id)
            return
        if self.comparison.shadow_version != result.model_version:
            self.reset()
            self.comparison.shadow_version = result.model_version
        tx = job.request.transaction
        self.comparison.add(tx.transaction_id, job.production_score, result.ensemble_score)
        self._sink.emit(
            tx.transaction_id,
            envelope(
                transaction_id=tx.transaction_id,
                institution_id=tx.institution_id,
                production_version=job.production_version,
                production_score=job.production_score,
                shadow_version=result.model_version,
                shadow_score=result.ensemble_score,
                shadow_tier=result.model_risk_tier,
                scored_at=self._clock(),
                traceparent=job.request.traceparent,
            ),
        )
        self.metrics.scored.inc()
        now = self._clock()
        if self._log is not None and now - self._flushed >= self._flush_every:
            self._flushed = now
            self._log.flush(self.comparison)
