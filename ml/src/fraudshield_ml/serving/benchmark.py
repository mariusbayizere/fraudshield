"""`fs-bench`: the M5 benchmarks (FR-02-07, TEST-10, ML-GATE-12).

* `fs-bench serve` — starts the scorer's worker processes (or targets a running one) and keeps
  `--concurrency` requests in flight (200, the gate's figure) for `--requests` requests, then
  reports client-observed p50/p95/p99, the server's own `scoring_duration_ms`, throughput and the
  share of requests that took the SHAP path.
* `fs-bench memory` — scores `--scorings` (10,000) requests consecutively in one process after a
  warm-up and reports resident-memory growth against TEST-10's 50 MB, with the model loaded once.

**Requests are real.** Transactions are read from a published dataset and replayed through the
feature store in timestamp order, so each request carries the `AccountContext` it would have had
in production — the same mixture of quiet and flagged transactions, and so the same share of the
SHAP path, as the data. `--synthetic` is for a machine without the dataset.

**These numbers are not gate evidence on a shared machine.** ADR 0010: latency percentiles count
only from the dedicated machine recorded in `docs/benchmarks/hardware.md`. Every report prints the
machine's CPU count, load average, commit and the caveat, so an excerpt cannot lose it.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import os
import platform
import resource
import socket
import subprocess
import sys
import tempfile
import time
from collections.abc import Sequence
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.featurestore.store import ContextRead
from fraudshield_ml.serving.contexts import StaticContexts
from fraudshield_ml.serving.generated import scoring_pb2 as pb

GATE = {"p50": 15.0, "p95": 25.0, "p99": 40.0}
MEMORY_GATE_MB = 50.0
CAVEAT = (
    "NOT GATE EVIDENCE unless run on the dedicated machine in docs/benchmarks/hardware.md "
    "(ADR 0010); a shared machine's percentiles are not repeatable"
)


def percentile(values: Sequence[float], q: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    # Nearest rank: the smallest value with at least q of the sample at or below it.
    rank = max(0, min(len(ordered) - 1, math.ceil(q * len(ordered)) - 1))
    return ordered[rank]


def _git(*args: str) -> str:
    command = ["git", *args]
    return subprocess.run(command, capture_output=True, text=True, check=True).stdout.strip()  # noqa: S603


def machine() -> dict[str, Any]:
    try:
        commit = _git("rev-parse", "HEAD")
        dirty = bool(_git("status", "--porcelain", "--untracked-files=no"))
    except (OSError, subprocess.CalledProcessError):
        commit, dirty = "unknown", True
    return {
        "host": platform.node(),
        "cpus": os.cpu_count(),
        "load_average": [round(x, 2) for x in os.getloadavg()],
        "python": platform.python_version(),
        "commit": commit,
        "tree_dirty": dirty,
        "caveat": CAVEAT,
    }


# ------------------------------------------------------------------------------ requests


Request = tuple[pb.ScoreRequest, ContextRead]


def dataset_requests(root: Path, packs: Path, corpus: int, keep: int) -> list[Request]:
    """The last `keep` of `corpus` transactions, each with the context the store gave it."""
    import fakeredis  # noqa: PLC0415 - benchmark-only
    from prometheus_client import CollectorRegistry  # noqa: PLC0415

    from fraudshield_ml.cli import read_transactions  # noqa: PLC0415 - pyarrow, benchmark only
    from fraudshield_ml.featurestore.store import FeatureStore, StoreMetrics  # noqa: PLC0415
    from fraudshield_ml.serving.convert import to_proto  # noqa: PLC0415

    reference = Reference.from_packs(packs)
    rows = read_transactions(root, packs, corpus)
    store = FeatureStore(
        fakeredis.FakeRedis(decode_responses=True),
        reference,
        authoritative=False,
        metrics=StoreMetrics.create(CollectorRegistry()),
    )
    requests: list[Request] = []
    start = len(rows) - keep
    for i, tx in enumerate(rows):
        if i >= start:
            requests.append(
                (pb.ScoreRequest(transaction=to_proto(tx, reference=reference)), store.read(tx))
            )
        store.observe(tx)
    return requests


def synthetic_requests(count: int) -> list[Request]:
    """Ordinary requests with a burst in one in twelve, for a machine without the dataset."""
    import random  # noqa: PLC0415
    from datetime import UTC, datetime, timedelta  # noqa: PLC0415

    rng = random.Random(7)  # noqa: S311 - load shape, not secrets
    t0 = datetime(2025, 11, 1, 9, tzinfo=UTC)
    out = []
    for i in range(count):
        burst = i % 12 == 0
        amount = round(rng.lognormvariate(9.3, 0.7))
        tx = pb.Transaction(
            transaction_id=f"bench-{i}",
            institution_id="bench",
            account_token=f"acc{i % 500}",
            counterparty_token=f"cp{i % 90}",
            amount=pb.Money(amount=str(amount), currency="RWF"),
            amount_rwf=f"{amount}.0",
            channel=pb.CHANNEL_MOBILE_MONEY,
            merchant_category_code="5411",
            location=pb.GeoPoint(latitude=-1.95 + rng.gauss(0, 0.05), longitude=30.06),
            device_token=f"dev{i % 700}",
            counterparty_country="RW",
        )
        tx.transaction_timestamp.FromDatetime(t0 + timedelta(seconds=i))
        ctx = pb.AccountContext(
            tx_count_1h=30 if burst else rng.randint(0, 2),
            tx_count_24h=35 if burst else rng.randint(1, 6),
            tx_count_7d=40 if burst else rng.randint(3, 20),
            amount_sum_24h_rwf="50000.0",
            amount_sum_7d_rwf="150000.0",
            mean_hourly_count_30d=0.0 if burst else rng.uniform(0.2, 1.2),
            history_count_90d=rng.randint(5, 80),
            amount_median_90d_rwf="11000.0",
            amount_mad_90d_rwf="3000.0",
            amount_max_90d_rwf="60000.0",
            countries_seen=["RW"],
            geo_cell_fraud_rate_30d=0.009,
        )
        ctx.last_transaction_at.FromDatetime(t0 + timedelta(seconds=i) - timedelta(hours=3))
        ctx.last_location.CopyFrom(pb.GeoPoint(latitude=-1.95, longitude=30.06))
        ctx.device.CopyFrom(pb.DeviceContext(accounts_per_device_7d=1))
        out.append((pb.ScoreRequest(transaction=tx), ContextRead(context=ctx, exact_ages={})))
    return out


# ------------------------------------------------------------------------------ serve


@dataclass(frozen=True)
class ServeReport:
    requests: int
    concurrency: int
    workers: int
    errors: int
    seconds: float
    throughput_rps: float
    client_ms: dict[str, float]
    server_ms: dict[str, float]
    shap_share: float
    gate: dict[str, bool]


async def _drive(
    address: str, requests: Sequence[Request], concurrency: int, warmup: int
) -> tuple[list[float], list[pb.ScoringResult], int, float]:
    import grpc  # type: ignore[import-untyped]  # noqa: PLC0415

    channels = [grpc.aio.insecure_channel(address) for _ in range(8)]
    calls = [
        c.unary_unary(
            "/fraudshield.scoring.v1.ScoringService/Score",
            request_serializer=pb.ScoreRequest.SerializeToString,
            response_deserializer=pb.ScoreResponse.FromString,
        )
        for c in channels
    ]
    gate = asyncio.Semaphore(concurrency)
    latencies: list[float] = []
    results: list[pb.ScoringResult] = []
    errors = 0

    async def one(i: int, pair: Request, record: bool) -> None:
        request = pair[0]
        nonlocal errors
        async with gate:
            began = time.perf_counter()
            try:
                reply = await calls[i % len(calls)](request, timeout=30)
            except grpc.RpcError:
                errors += 1
                return
            if record:
                latencies.append((time.perf_counter() - began) * 1000.0)
                results.append(reply.result)

    await asyncio.gather(*(one(i, r, False) for i, r in enumerate(requests[:warmup])))
    started = time.perf_counter()
    await asyncio.gather(*(one(i, r, True) for i, r in enumerate(requests[warmup:])))
    elapsed = time.perf_counter() - started
    for c in channels:
        await c.close()
    return latencies, results, errors, elapsed


def run_serve(
    requests: Sequence[Request],
    *,
    address: str,
    concurrency: int,
    workers: int,
    warmup: int,
) -> ServeReport:
    latencies, results, errors, elapsed = asyncio.run(
        _drive(address, requests, concurrency, warmup)
    )
    server = [float(r.scoring_duration_ms) for r in results]
    client = {f"p{int(q * 100)}": percentile(latencies, q) for q in (0.5, 0.95, 0.99)}
    return ServeReport(
        requests=len(latencies),
        concurrency=concurrency,
        workers=workers,
        errors=errors,
        seconds=elapsed,
        throughput_rps=len(latencies) / elapsed if elapsed else 0.0,
        client_ms=client,
        server_ms={f"p{int(q * 100)}": percentile(server, q) for q in (0.5, 0.95, 0.99)},
        shap_share=sum(1 for r in results if r.shap_all) / max(len(results), 1),
        gate={k: client[k] < v for k, v in GATE.items()},
    )


# ------------------------------------------------------------------------------ memory


def rss_mb() -> float:
    """Current resident set size, from /proc where available (ru_maxrss is a high-water mark)."""
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024.0
    except OSError:
        pass
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0


@dataclass(frozen=True)
class MemoryReport:
    scorings: int
    warmup: int
    rss_before_mb: float
    rss_after_mb: float
    growth_mb: float
    model_loaded_once: bool
    passed: bool


def run_memory(
    scorer: Any, requests: Sequence[Request], scorings: int, warmup: int = 500
) -> MemoryReport:
    import gc  # noqa: PLC0415

    bundle = scorer.bundle
    for i in range(warmup):
        scorer.score(*requests[i % len(requests)])
    gc.collect()
    before = rss_mb()
    for i in range(scorings):
        scorer.score(*requests[i % len(requests)])
    gc.collect()
    after = rss_mb()
    return MemoryReport(
        scorings=scorings,
        warmup=warmup,
        rss_before_mb=before,
        rss_after_mb=after,
        growth_mb=after - before,
        model_loaded_once=scorer.bundle is bundle,
        passed=after - before < MEMORY_GATE_MB and scorer.bundle is bundle,
    )


# ------------------------------------------------------------------------------ CLI


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _requests(args: argparse.Namespace) -> list[Request]:
    total = args.requests + args.warmup
    if args.synthetic:
        return synthetic_requests(total)
    return dataset_requests(args.dataset, args.packs, args.corpus, min(total, args.corpus))


def _serve_command(args: argparse.Namespace, requests: Sequence[Request]) -> ServeReport:
    """Start the workers unless an address was given, drive them, and always stop them."""
    from fraudshield_ml.serving import server  # noqa: PLC0415

    processes = []
    address = args.address
    if address is None:
        address = f"127.0.0.1:{_free_port()}"
        status = Path(tempfile.mkdtemp(prefix="fs-bench-"))
        # This laptop has no Redis: the workers read the replay's contexts from a file, so the
        # figures cover scoring and exclude the store read (reported in the output).
        contexts = StaticContexts.write(
            status / "contexts.jsonl",
            ((req.transaction.transaction_id, read) for req, read in requests),
        )
        processes = server.serve(
            server.WorkerConfig(
                address=address,
                packs=args.packs,
                tls=None,
                bundle=args.bundle,
                status_dir=status,
                threads=4,
                static_contexts=contexts,
            ),
            args.workers,
        )
        deadline = time.monotonic() + 180
        while len(list(status.glob("[0-9]*.json"))) < args.workers:
            if time.monotonic() > deadline:
                raise SystemExit("workers did not become ready within 180 s")
            time.sleep(0.5)
    try:
        return run_serve(
            requests,
            address=address,
            concurrency=args.concurrency,
            workers=args.workers,
            warmup=args.warmup,
        )
    finally:
        for p in processes:
            p.terminate()
        for p in processes:
            p.join(timeout=15)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-bench", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("serve", "memory"):
        sub = commands.add_parser(name)
        sub.add_argument("--bundle", type=Path, required=True)
        sub.add_argument("--packs", type=Path, required=True)
        source = sub.add_mutually_exclusive_group(required=True)
        source.add_argument("--dataset", type=Path, help="published dataset root")
        source.add_argument("--synthetic", action="store_true")
        sub.add_argument("--corpus", type=int, default=60_000, help="rows replayed for context")
        sub.add_argument("--warmup", type=int, default=500)
        sub.add_argument("--out", type=Path, help="write the report as JSON")
    serve = commands.choices["serve"]
    serve.add_argument("--requests", type=int, default=10_000)
    serve.add_argument("--concurrency", type=int, default=200)
    serve.add_argument("--workers", type=int, default=os.cpu_count() or 1)
    serve.add_argument("--address", help="target a running scorer instead of starting one")
    memory = commands.choices["memory"]
    memory.add_argument("--scorings", type=int, default=10_000)
    memory.set_defaults(requests=2_000)
    args = parser.parse_args(argv)

    requests = _requests(args)
    report: dict[str, Any] = {
        "machine": machine(),
        "bundle": str(args.bundle),
        "store_read": "excluded: contexts precomputed from the replay (no Redis on this machine)",
    }
    if args.command == "memory":
        from fraudshield_ml.models.bundle import Bundle  # noqa: PLC0415
        from fraudshield_ml.serving.scorer import Scorer  # noqa: PLC0415

        scorer = Scorer(Bundle.load(args.bundle), Reference.from_packs(args.packs))
        report["memory"] = asdict(run_memory(scorer, requests, args.scorings, args.warmup))
    else:
        report["serve"] = asdict(_serve_command(args, requests))
    report["machine"]["load_average_after"] = [round(x, 2) for x in os.getloadavg()]
    text = json.dumps(report, indent=2)
    print(text)
    if args.out is not None:
        args.out.write_text(text + "\n")
    print(CAVEAT, file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
