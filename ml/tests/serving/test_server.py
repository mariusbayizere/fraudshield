"""The gRPC scorer end to end: status codes, health, blue-green swap, mTLS and multiple workers."""

from __future__ import annotations

import json
import socket
import subprocess
import threading
import time
import urllib.request
from collections.abc import Iterator
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import grpc  # type: ignore[import-untyped]
import pytest
import uvicorn
from grpc_health.v1 import health_pb2, health_pb2_grpc  # type: ignore[import-untyped]
from prometheus_client import CollectorRegistry

from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving import admin, server
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.serving.registry import wait_until
from fraudshield_ml.serving.scorer import ModelHolder, Scorer
from fraudshield_ml.serving.shadow import ShadowMetrics, ShadowRunner

METHOD = f"/{server.SERVICE}/"


def stubs(channel: Any) -> tuple[Any, Any]:
    score = channel.unary_unary(
        METHOD + "Score",
        request_serializer=pb.ScoreRequest.SerializeToString,
        response_deserializer=pb.ScoreResponse.FromString,
    )
    status = channel.unary_unary(
        METHOD + "GetModelStatus",
        request_serializer=pb.GetModelStatusRequest.SerializeToString,
        response_deserializer=pb.GetModelStatusResponse.FromString,
    )
    return score, status


@pytest.fixture
def running(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> Iterator[tuple[ModelHolder, Any, Any, Any]]:
    holder = ModelHolder(Scorer(bundles[0], kit.reference))
    service = server.ScoringService(holder, contexts=kit.contexts(burst=True))
    grpc_server, health, port = server.build_server(service, "127.0.0.1:0", tls=None, threads=8)
    server.set_health(health, True)
    grpc_server.start()
    channel = grpc.insecure_channel(f"127.0.0.1:{port}")
    score, status = stubs(channel)
    yield holder, score, status, channel
    channel.close()
    grpc_server.stop(0)


@pytest.mark.req("FR-02-01")
def test_a_transaction_is_scored_over_grpc(
    running: tuple[ModelHolder, Any, Any, Any], kit: SimpleNamespace
) -> None:
    holder, score, status, channel = running
    reply = score(kit.request(1), timeout=10)
    assert reply.result.model_version == holder.production.model_version  # type: ignore[union-attr]
    assert len(reply.result.feature_vector) == 44
    model = status(pb.GetModelStatusRequest(), timeout=5)
    assert model.status == pb.GetModelStatusResponse.STATUS_SERVING
    assert model.production_model_version == reply.result.model_version
    assert not model.HasField("shadow_model_version")
    check = health_pb2_grpc.HealthStub(channel).Check(
        health_pb2.HealthCheckRequest(service=server.SERVICE), timeout=5
    )
    assert check.status == health_pb2.HealthCheckResponse.SERVING


def test_an_unscorable_request_is_invalid_argument_with_the_reason(
    running: tuple[ModelHolder, Any, Any, Any], kit: SimpleNamespace
) -> None:
    _, score, _, _ = running
    request = kit.request(2)
    request.transaction.amount.currency = "XOF"
    with pytest.raises(grpc.RpcError) as caught:
        score(request, timeout=5)
    assert caught.value.code() == grpc.StatusCode.INVALID_ARGUMENT
    assert "XOF" in caught.value.details()


def test_no_model_is_unavailable_so_the_api_falls_back(kit: SimpleNamespace) -> None:
    holder = ModelHolder()
    service = server.ScoringService(holder, contexts=kit.contexts())
    grpc_server, _, port = server.build_server(service, "127.0.0.1:0", tls=None)
    grpc_server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            score, status = stubs(channel)
            with pytest.raises(grpc.RpcError) as caught:
                score(kit.request(3), timeout=5)
            assert caught.value.code() == grpc.StatusCode.UNAVAILABLE
            reply = status(pb.GetModelStatusRequest(), timeout=5)
            assert reply.status == pb.GetModelStatusResponse.STATUS_NOT_SERVING
    finally:
        grpc_server.stop(0)


def test_an_internal_failure_is_internal_never_a_default_score(kit: SimpleNamespace) -> None:
    class Broken:
        model_version = "broken"
        reference = kit.reference

        def score(self, request: pb.ScoreRequest, read: Any) -> Any:
            raise RuntimeError("boom")

    service = server.ScoringService(ModelHolder(Broken()), contexts=kit.contexts())  # type: ignore[arg-type]
    grpc_server, _, port = server.build_server(service, "127.0.0.1:0", tls=None)
    grpc_server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            with pytest.raises(grpc.RpcError) as caught:
                stubs(channel)[0](kit.request(4), timeout=5)
            assert caught.value.code() == grpc.StatusCode.INTERNAL
    finally:
        grpc_server.stop(0)


@pytest.mark.req("FR-02-08", "TEST-08")
def test_only_the_production_result_is_returned_while_shadow_scores(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace
) -> None:
    production, shadow = (Scorer(b, kit.reference) for b in bundles)
    holder = ModelHolder(production, shadow)
    events: list[Any] = []

    class Sink:
        def emit(self, key: str, event: Any) -> None:
            events.append(event)

    runner = ShadowRunner(
        lambda: holder.shadow, Sink(), metrics=ShadowMetrics.create(CollectorRegistry())
    )
    service = server.ScoringService(holder, runner, contexts=kit.contexts(burst=True))
    grpc_server, _, port = server.build_server(service, "127.0.0.1:0", tls=None)
    grpc_server.start()
    try:
        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            score, status = stubs(channel)
            reply = score(kit.request(5), timeout=10)
            assert status(pb.GetModelStatusRequest(), timeout=5).shadow_model_version == (
                shadow.model_version
            )
    finally:
        grpc_server.stop(0)
    runner.drain()
    assert reply.result.model_version == production.model_version
    assert len(events) == 1
    assert events[0]["payload"]["shadow_model_version"] == shadow.model_version


@pytest.mark.req("FR-02-10")
def test_both_versions_score_during_a_blue_green_switch_and_none_is_unversioned(
    running: tuple[ModelHolder, Any, Any, Any],
    bundles: tuple[Bundle, Bundle],
    kit: SimpleNamespace,
) -> None:
    holder, score, _, _ = running
    blue, green = bundles[0].model_version, bundles[1].model_version
    seen: list[tuple[float, str]] = []
    swapped_at: list[float] = []
    stop = threading.Event()

    def client(worker: int) -> None:
        i = 0
        while not stop.is_set():
            began = time.monotonic()
            reply = score(kit.request(worker * 10_000 + i), timeout=10)
            seen.append((began, reply.result.model_version))
            i += 1

    with ThreadPoolExecutor(max_workers=6) as pool:
        for w in range(6):
            pool.submit(client, w)
        assert wait_until(lambda: len(seen) >= 30, 30)
        swapped_at.append(time.monotonic())
        holder.swap_production(Scorer(bundles[1], kit.reference))
        assert wait_until(lambda: sum(1 for _, v in seen if v == green) >= 30, 30)
        stop.set()

    versions = {v for _, v in seen}
    assert versions == {blue, green}, "both versions served during the switch"
    assert all(v for _, v in seen), "no result without a model version"
    # A request that began before the swap may finish on blue; one that began after cannot.
    late = [v for began, v in seen if began > swapped_at[0]]
    assert late
    assert set(late) == {green}


# ------------------------------------------------------------------ mTLS


def _openssl(*args: str, cwd: Path) -> None:
    subprocess.run(["openssl", *args], cwd=cwd, check=True, capture_output=True)  # noqa: S603, S607


@pytest.fixture(scope="module")
def certificates(tmp_path_factory: pytest.TempPathFactory) -> Path:
    d = tmp_path_factory.mktemp("pki")
    _openssl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-days",
        "1",
        "-subj",
        "/CN=test-ca",
        "-keyout",
        "ca.key",
        "-out",
        "ca.pem",
        cwd=d,
    )
    for name, san in (("server", "DNS:localhost"), ("client", "DNS:fraudshield-api")):
        _openssl(
            "req",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-subj",
            f"/CN={name}",
            "-keyout",
            f"{name}.key",
            "-out",
            f"{name}.csr",
            cwd=d,
        )
        (d / f"{name}.ext").write_text(f"subjectAltName={san}\n")
        _openssl(
            "x509",
            "-req",
            "-in",
            f"{name}.csr",
            "-CA",
            "ca.pem",
            "-CAkey",
            "ca.key",
            "-CAcreateserial",
            "-days",
            "1",
            "-extfile",
            f"{name}.ext",
            "-out",
            f"{name}.pem",
            cwd=d,
        )
    return d


def test_the_server_requires_a_client_certificate(
    bundles: tuple[Bundle, Bundle], kit: SimpleNamespace, certificates: Path
) -> None:
    d = certificates
    tls = server.Tls(d / "server.pem", d / "server.key", d / "ca.pem")
    service = server.ScoringService(
        ModelHolder(Scorer(bundles[0], kit.reference)), contexts=kit.contexts()
    )
    grpc_server, _, port = server.build_server(service, "localhost:0", tls=tls)
    grpc_server.start()
    try:
        mutual = grpc.ssl_channel_credentials(
            root_certificates=(d / "ca.pem").read_bytes(),
            private_key=(d / "client.key").read_bytes(),
            certificate_chain=(d / "client.pem").read_bytes(),
        )
        with grpc.secure_channel(f"localhost:{port}", mutual) as channel:
            assert stubs(channel)[0](kit.request(6), timeout=10).result.model_version
        anonymous = grpc.ssl_channel_credentials(root_certificates=(d / "ca.pem").read_bytes())
        with grpc.secure_channel(f"localhost:{port}", anonymous) as channel:
            with pytest.raises(grpc.RpcError) as caught:
                stubs(channel)[0](kit.request(7), timeout=10)
            assert caught.value.code() == grpc.StatusCode.UNAVAILABLE
    finally:
        grpc_server.stop(0)


# ------------------------------------------------------------------ processes and admin


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.mark.req("D-16", "FR-02-07")
def test_several_worker_processes_share_one_port_and_report_to_the_admin_port(
    bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    packs = tmp_path / "packs.json"
    packs.write_text(
        json.dumps(
            {
                "RW": {
                    "alpha2": "RW",
                    "blocs": ["EAC"],
                    "continent": "AF",
                    "currency": "RWF",
                    "currency_minor_units": 0,
                    "round_denominations": [500, 1000],
                    "utc_offset_hours": 2,
                },
            }
        )
    )
    port = _free_port()
    status_dir = tmp_path / "status"
    config = server.WorkerConfig(
        address=f"127.0.0.1:{port}",
        packs=packs,
        tls=None,
        bundle=bundle_dirs[0],
        status_dir=status_dir,
        threads=2,
    )
    processes = server.serve(config, 2)
    try:
        alive = lambda: {p.pid for p in processes if p.is_alive() and p.pid}  # noqa: E731
        assert wait_until(lambda: len(list(status_dir.glob("[0-9]*.json"))) == 2, 90)
        app = admin.create_app(status_dir, alive, CollectorRegistry())
        admin_port = _free_port()
        web = uvicorn.Server(
            uvicorn.Config(app, host="127.0.0.1", port=admin_port, log_level="warning")
        )
        threading.Thread(target=web.run, daemon=True).start()
        base = f"http://127.0.0.1:{admin_port}"
        assert wait_until(lambda: web.started, 10)

        def get(path: str) -> Any:
            with urllib.request.urlopen(base + path, timeout=5) as reply:  # noqa: S310
                return json.loads(reply.read())

        assert get("/health/live") == {"status": "UP", "workers": 2}
        assert get("/health/ready")["serving_workers"] == 2
        models = get("/models")
        version = Bundle.load(bundle_dirs[0]).model_version
        assert models["production_versions"] == [version]
        assert models["switch_in_progress"] is False
        with urllib.request.urlopen(base + "/metrics", timeout=5) as reply:  # noqa: S310
            assert reply.status == 200

        with grpc.insecure_channel(f"127.0.0.1:{port}") as channel:
            reply = stubs(channel)[1](pb.GetModelStatusRequest(), timeout=10)
            assert reply.production_model_version == version
        web.should_exit = True
    finally:
        for p in processes:
            p.terminate()
        for p in processes:
            p.join(timeout=15)
    assert all(p.exitcode == 0 for p in processes), "workers stop cleanly on SIGTERM"


def test_readiness_is_down_until_a_worker_serves(tmp_path: Path) -> None:
    status_dir = tmp_path / "status"
    status_dir.mkdir()
    (status_dir / "123.json").write_text(
        json.dumps({"pid": 123, "production_model_version": None, "shadow_model_version": None})
    )
    statuses = admin.worker_status(status_dir, {123})
    assert statuses[0]["production_model_version"] is None
    assert admin.worker_status(status_dir, set()) == [], "dead workers are not reported"
