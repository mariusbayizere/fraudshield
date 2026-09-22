"""`fs-scorer serve`: the scoring service's supervising process."""

from __future__ import annotations

import argparse
import logging
import os
import sys
import tempfile
import threading
import time
from collections.abc import Sequence
from pathlib import Path

LOG = logging.getLogger("fs-scorer")


def parse(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(prog="fs-scorer", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve", help="run the gRPC scorer and its admin port")
    source = serve.add_mutually_exclusive_group(required=True)
    source.add_argument("--bundle", type=Path, help="serve this bundle directory (no hot swap)")
    source.add_argument("--mlflow", help="MLflow tracking URL; serve @production, watch @shadow")
    serve.add_argument("--model-name", default="fraudshield-ensemble")
    serve.add_argument("--packs", type=Path, required=True, help="the published packs.json")
    serve.add_argument("--port", type=int, default=50051)
    serve.add_argument("--admin-port", type=int, default=8081)
    serve.add_argument("--workers", type=int, default=os.cpu_count() or 1)
    serve.add_argument("--threads", type=int, default=4, help="gRPC threads per worker")
    serve.add_argument("--redis", help="Redis URL of the threshold config store (FR-02-06)")
    serve.add_argument(
        "--feature-store",
        help="Redis URL of the feature store the scorer reads and writes (FR-02-09, ADR 0033)",
    )
    serve.add_argument(
        "--static-contexts", type=Path, help="benchmark only: precomputed contexts, no Redis"
    )
    serve.add_argument("--shadow-log", type=Path, help="JSON-lines sink for fs.ml.shadow events")
    serve.add_argument("--cache", type=Path, default=Path(tempfile.gettempdir()) / "fs-models")
    tls = serve.add_argument_group("mTLS (required unless --insecure)")
    tls.add_argument("--tls-cert", type=Path)
    tls.add_argument("--tls-key", type=Path)
    tls.add_argument("--tls-client-ca", type=Path)
    serve.add_argument("--insecure", action="store_true", help="plaintext; development only")
    args = parser.parse_args(argv)
    if not (args.feature_store or args.static_contexts):
        parser.error("the scorer reads account context from the store: pass --feature-store")
    if not args.insecure and not (args.tls_cert and args.tls_key and args.tls_client_ca):
        parser.error("mTLS needs --tls-cert, --tls-key and --tls-client-ca; or pass --insecure")
    return args


def main(argv: Sequence[str] | None = None) -> int:
    args = parse(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    # Multiprocess metrics must be configured before any worker imports prometheus_client.
    metrics_dir = Path(tempfile.mkdtemp(prefix="fs-scorer-metrics-"))
    os.environ["PROMETHEUS_MULTIPROC_DIR"] = str(metrics_dir)

    import uvicorn  # noqa: PLC0415 - after the environment is set
    from prometheus_client import CollectorRegistry, multiprocess  # noqa: PLC0415

    from fraudshield_ml.serving import admin, server  # noqa: PLC0415

    status_dir = metrics_dir / "status"
    tls = None if args.insecure else server.Tls(args.tls_cert, args.tls_key, args.tls_client_ca)
    config = server.WorkerConfig(
        address=f"[::]:{args.port}",
        packs=args.packs,
        tls=tls,
        bundle=args.bundle,
        mlflow_url=args.mlflow,
        model_name=args.model_name,
        cache=args.cache,
        redis_url=args.redis,
        feature_store_url=args.feature_store,
        static_contexts=args.static_contexts,
        shadow_log=args.shadow_log,
        status_dir=status_dir,
        threads=args.threads,
    )
    processes = server.serve(config, args.workers)
    registry = CollectorRegistry()
    multiprocess.MultiProcessCollector(registry)  # type: ignore[no-untyped-call]
    app = admin.create_app(
        status_dir, lambda: {p.pid for p in processes if p.is_alive() and p.pid}, registry
    )
    web = uvicorn.Server(
        uvicorn.Config(
            app,
            host="0.0.0.0",  # noqa: S104 - probes reach the admin port on the pod address
            port=args.admin_port,
            log_level="warning",
        )
    )
    threading.Thread(target=web.run, name="admin", daemon=True).start()
    LOG.info("%d workers on :%d, admin on :%d", args.workers, args.port, args.admin_port)
    try:
        while all(p.is_alive() for p in processes):
            time.sleep(1.0)
        dead = [p.name for p in processes if not p.is_alive()]
        LOG.error("worker(s) %s exited; stopping so the orchestrator restarts the pod", dead)
        return 1
    except KeyboardInterrupt:
        return 0
    finally:
        for p in processes:
            if p.is_alive():
                p.terminate()
        for p in processes:
            p.join(timeout=10)
        web.should_exit = True


if __name__ == "__main__":
    sys.exit(main())
