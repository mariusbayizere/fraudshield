"""The gRPC scoring server (M5; D-16): multi-process, hot-swapping, shadow off the hot path.

**Processes.** Python holds the GIL through tree traversal, so throughput comes from processes, not
threads: `fs-scorer serve --workers N` starts N processes that share one port through
`SO_REUSEPORT`, each with its own model copy loaded once and boosters at `nthread=1` (D-16). The
kernel spreads connections across them. The parent process supervises the workers and serves the
admin port (`serving.admin`); it never scores.

**Transport.** mTLS by default, as the contract's header specifies (a persistent HTTP/2 channel from
the API). `--insecure` exists for local development and tests and says so in its name.

**Status codes.** A request the features cannot be computed from is `INVALID_ARGUMENT` with the
reason; no production model loaded is `UNAVAILABLE`, which the API's circuit breaker turns into the
rule-based fallback (C.4); anything else is `INTERNAL`, never a silent default score.
"""

from __future__ import annotations

import json
import logging
import multiprocessing
import os
import signal
import threading
from concurrent import futures
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import grpc  # type: ignore[import-untyped]
from grpc_health.v1 import health, health_pb2, health_pb2_grpc  # type: ignore[import-untyped]

from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.featurestore.store import ContextRead, FeatureStore
from fraudshield_ml.featurestore.writer import StoreWriter
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving import features
from fraudshield_ml.serving.contexts import ContextSource, StaticContexts
from fraudshield_ml.serving.features import RequestError
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.serving.registry import AliasWatcher, MlflowRegistry
from fraudshield_ml.serving.scorer import ModelHolder, Scorer
from fraudshield_ml.serving.shadow import JsonLinesSink, MlflowComparisonLog, ShadowRunner
from fraudshield_ml.serving.thresholds import PRODUCTION, Thresholds, ThresholdStore

SERVICE = "fraudshield.scoring.v1.ScoringService"
LOG = logging.getLogger(__name__)


class ScoringService:
    def __init__(
        self,
        holder: ModelHolder,
        shadow: ShadowRunner | None = None,
        writer: StoreWriter | None = None,
        contexts: ContextSource | None = None,
    ) -> None:
        self.holder = holder
        self.shadow = shadow
        self.writer = writer
        #: ADR 0033: the scorer reads each account's context itself, from the feature store, so
        #: the window arithmetic has one implementation. The API sends only the transaction.
        self.contexts = contexts

    def _read(self, request: pb.ScoreRequest, scorer: Scorer, context: Any) -> ContextRead:
        """The account's context from the store; a failure is UNAVAILABLE, never an empty one."""
        if self.contexts is None:
            context.abort(grpc.StatusCode.UNAVAILABLE, "no feature store configured")
        try:
            tx = features.domain_transaction(request.transaction, scorer.reference)
        except RequestError as error:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(error))
        try:
            return self.contexts.read(tx)  # type: ignore[union-attr]
        except Exception as error:
            LOG.warning("feature store unreadable: %s", error)
            context.abort(grpc.StatusCode.UNAVAILABLE, "feature store unavailable")
        raise AssertionError("unreachable: abort raises")  # pragma: no cover

    def Score(self, request: pb.ScoreRequest, context: Any) -> pb.ScoreResponse:  # noqa: N802
        scorer = self.holder.production  # read once: this request stays on this model
        if scorer is None:
            context.abort(grpc.StatusCode.UNAVAILABLE, "no production model loaded")
        # Outside the try below: abort() raises, and the broad handler would report it as INTERNAL.
        read = self._read(request, scorer, context)  # type: ignore[arg-type]
        try:
            scored = scorer.score(request, read)  # type: ignore[union-attr]
        except RequestError as error:
            context.abort(grpc.StatusCode.INVALID_ARGUMENT, str(error))
        except Exception as error:
            LOG.exception("scoring failed")
            context.abort(grpc.StatusCode.INTERNAL, f"scoring failed: {type(error).__name__}")
        if self.shadow is not None:
            self.shadow.offer(request, scored.result, read)
        if self.writer is not None:
            self.writer.offer(scored.transaction)
        return pb.ScoreResponse(result=scored.result)

    def GetModelStatus(  # noqa: N802
        self, request: pb.GetModelStatusRequest, context: Any
    ) -> pb.GetModelStatusResponse:
        production, shadow = self.holder.production, self.holder.shadow
        if production is None:
            return pb.GetModelStatusResponse(status=pb.GetModelStatusResponse.STATUS_NOT_SERVING)
        response = pb.GetModelStatusResponse(
            status=pb.GetModelStatusResponse.STATUS_SERVING,
            production_model_version=production.model_version,
            feature_registry_version=production.bundle.registry_version,
        )
        if shadow is not None:
            response.shadow_model_version = shadow.model_version
        return response


@dataclass(frozen=True)
class Tls:
    certificate: Path
    key: Path
    client_ca: Path

    def credentials(self) -> Any:
        return grpc.ssl_server_credentials(
            [(self.key.read_bytes(), self.certificate.read_bytes())],
            root_certificates=self.client_ca.read_bytes(),
            require_client_auth=True,
        )


def build_server(
    service: ScoringService,
    address: str,
    *,
    tls: Tls | None,
    threads: int = 4,
    reuse_port: bool = False,
) -> tuple[Any, Any, int]:
    """A started-ready (not yet started) server, its health servicer, and the bound port."""
    handler = grpc.method_handlers_generic_handler(
        SERVICE,
        {
            "Score": grpc.unary_unary_rpc_method_handler(
                service.Score,
                request_deserializer=pb.ScoreRequest.FromString,
                response_serializer=pb.ScoreResponse.SerializeToString,
            ),
            "GetModelStatus": grpc.unary_unary_rpc_method_handler(
                service.GetModelStatus,
                request_deserializer=pb.GetModelStatusRequest.FromString,
                response_serializer=pb.GetModelStatusResponse.SerializeToString,
            ),
        },
    )
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=threads),
        options=[("grpc.so_reuseport", 1 if reuse_port else 0)],
    )
    server.add_generic_rpc_handlers((handler,))
    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    port = (
        server.add_secure_port(address, tls.credentials())
        if tls is not None
        else server.add_insecure_port(address)
    )
    if port == 0:
        raise OSError(f"could not bind {address}")
    return server, health_servicer, port


def set_health(health_servicer: Any, serving: bool) -> None:
    status = (
        health_pb2.HealthCheckResponse.SERVING
        if serving
        else health_pb2.HealthCheckResponse.NOT_SERVING
    )
    for name in ("", SERVICE):
        health_servicer.set(name, status)


@dataclass(frozen=True)
class WorkerConfig:
    address: str
    packs: Path
    tls: Tls | None
    bundle: Path | None = None
    mlflow_url: str | None = None
    model_name: str = "fraudshield-ensemble"
    cache: Path = Path("/tmp/fraudshield-models")  # noqa: S108 - overridden in deployment
    redis_url: str | None = None
    #: When set, each worker writes every scored transaction to the feature store at this Redis
    #: URL (FR-02-09; see `featurestore.writer` for why the scorer is the writer).
    feature_store_url: str | None = None
    #: Benchmark only: precomputed contexts on a machine with no Redis (`serving.contexts`).
    static_contexts: Path | None = None
    #: D-06's profile until the admin API writes the Redis hash. Production (anomaly review at the
    #: 0.995 percentile) is the default; the 0.7 test profile routed 31% of the gate model's test
    #: period to review (review finding 3).
    thresholds: Thresholds = PRODUCTION
    shadow_log: Path | None = None
    #: Each worker writes `<pid>.json` here on every swap, for the admin port (`serving.admin`).
    status_dir: Path | None = None
    threads: int = 4
    reuse_port: bool = True


@dataclass
class Worker:
    """A started scoring worker: its server, models and watcher, and how to stop them."""

    server: Any
    health: Any
    holder: ModelHolder
    port: int
    watcher: AliasWatcher | None
    writer: StoreWriter | None
    shadow: ShadowRunner | None

    def stop(self, grace: float = 5.0) -> None:
        set_health(self.health, False)
        if self.watcher is not None:
            self.watcher.stop()
        self.server.stop(grace=grace).wait()


def start_worker(config: WorkerConfig) -> Worker:
    """Load, bind and start serving; separate from `run_worker` so it can be tested in process."""
    reference = Reference.from_packs(config.packs)
    thresholds = ThresholdStore(_redis(config.redis_url), default=config.thresholds)
    holder = ModelHolder()
    registry = MlflowRegistry(config.mlflow_url) if config.mlflow_url is not None else None
    comparison_log = (
        MlflowComparisonLog(
            registry,
            production_version=lambda: holder.production.model_version if holder.production else "",
        )
        if registry is not None
        else None
    )
    shadow = (
        ShadowRunner(lambda: holder.shadow, JsonLinesSink(config.shadow_log), log=comparison_log)
        if config.shadow_log is not None
        else None
    )
    writer = None
    contexts: ContextSource | None = None
    if config.feature_store_url is not None:
        # The store read is on the 40 ms hot path: fail fast to the API's breaker (C.4) rather
        # than holding a worker thread for a second (review, residual risks).
        store = FeatureStore(_redis(config.feature_store_url, timeout=0.1), reference)
        writer = StoreWriter(store)
        contexts = store
    elif config.static_contexts is not None:
        contexts = StaticContexts.load(config.static_contexts)
    service = ScoringService(holder, shadow, writer, contexts)
    server, health_servicer, port = build_server(
        service,
        config.address,
        tls=config.tls,
        threads=config.threads,
        reuse_port=config.reuse_port,
    )

    def publish() -> None:
        if config.status_dir is not None:
            write_status(config.status_dir, holder)

    def production(bundle: Bundle) -> None:
        scorer = Scorer(bundle, reference, thresholds)
        _warm(scorer)
        holder.swap_production(scorer)
        set_health(health_servicer, True)
        publish()

    def shadow_model(bundle: Bundle | None) -> None:
        scorer = Scorer(bundle, reference, thresholds) if bundle else None
        if scorer is not None:
            _warm(scorer)
        holder.swap_shadow(scorer)
        publish()

    set_health(health_servicer, False)
    watcher: AliasWatcher | None = None
    if config.bundle is not None:
        production(Bundle.load(config.bundle))
    if registry is not None:
        watcher = AliasWatcher(
            registry,
            config.model_name,
            config.cache,
            production,
            shadow_model,
        )
        watcher.poll_once()
        watcher.start()
    server.start()
    LOG.info("scoring on %s (pid %d)", config.address, os.getpid())
    LOG.warning(
        "ADR 0034: no deployed component writes %s, so these trained features are served as "
        "constants until PB-70 and PB-71 close: counterparty_confirmed_fraud_90d, "
        "geo_cell_fraud_rate_30d, days_since_sim_swap, kyc_tier, account_age_days. "
        "Watch fs_feature_store_missing_producer_reads_total{state}",
        "fraud outcomes or account reference state",
    )
    return Worker(server, health_servicer, holder, port, watcher, writer, shadow)


def run_worker(config: WorkerConfig) -> None:
    """One scoring process: start, then serve and hot-swap until SIGTERM."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(process)d %(name)s %(message)s")
    worker = start_worker(config)
    # A threading.Event waited on in short slices: the handler only sets a flag, and the main
    # thread returns to the interpreter often enough for it to run. (A multiprocessing.Event set
    # from the handler could block on the lock its own interrupted wait holds.)
    stop = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stop.set())
    signal.signal(signal.SIGINT, lambda *_: stop.set())
    while not stop.wait(0.5):
        pass
    worker.stop()


def _warm(scorer: Scorer) -> None:
    """Build the ONNX sessions before the swap publishes the model.

    `Bundle._sessions` is a cached property built on the first `raw()`, so without this the first
    request after a hot swap pays for it, and concurrent first requests may each build one
    (review, residual risks).
    """
    scorer.bundle.raw([0.0] * len(scorer.bundle.features))


def write_status(directory: Path, holder: ModelHolder) -> None:
    """This process's loaded versions, written atomically for the admin port to read."""
    directory.mkdir(parents=True, exist_ok=True)
    production, shadow = holder.production, holder.shadow
    status = {
        "pid": os.getpid(),
        "production_model_version": production.model_version if production else None,
        "shadow_model_version": shadow.model_version if shadow else None,
        "feature_registry_version": production.bundle.registry_version if production else None,
        "swaps": holder.swaps,
    }
    staging = directory / f".{os.getpid()}.json"
    staging.write_text(json.dumps(status))
    staging.replace(directory / f"{os.getpid()}.json")


def _redis(url: str | None, timeout: float = 0.05) -> Any:
    if url is None:
        return None
    import redis  # noqa: PLC0415 - only when configured

    return redis.Redis.from_url(url, decode_responses=True, socket_timeout=timeout)


def serve(config: WorkerConfig, workers: int) -> list[multiprocessing.process.BaseProcess]:
    """Start `workers` scoring processes on one port. Returns them for the supervisor."""
    context = multiprocessing.get_context("spawn")
    processes: list[multiprocessing.process.BaseProcess] = [
        context.Process(target=run_worker, args=(config,), name=f"scorer-{i}", daemon=False)
        for i in range(workers)
    ]
    for process in processes:
        process.start()
    return processes
