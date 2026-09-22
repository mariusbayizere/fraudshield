"""Hot swap by registry alias (D-50, FR-02-10) against a fake MLflow REST server.

The fake implements the endpoints `MlflowRegistry` calls, with MLflow's error shapes
(`RESOURCE_DOES_NOT_EXIST` as HTTP 404, `RESOURCE_ALREADY_EXISTS` as 400). It checks the client
against MLflow's documented REST API, not against a running MLflow; that is an integration check
for the CI stack, where the compose MLflow server runs.
"""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import threading
import urllib.parse
import urllib.request
from collections.abc import Iterator
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from prometheus_client import CollectorRegistry

from fraudshield_ml.models import cli as model_cli
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving.registry import (
    PREVIOUS,
    PRODUCTION,
    SHADOW,
    AliasWatcher,
    MlflowRegistry,
    ModelVersion,
    RegistryError,
    WatcherMetrics,
    wait_until,
)
from fraudshield_ml.serving.scorer import ModelHolder, Scorer
from fraudshield_ml.serving.shadow import Comparison, MlflowComparisonLog

NAME = "fraudshield-ensemble"


class FakeMlflow:
    def __init__(self) -> None:
        self.artifacts: dict[str, bytes] = {}
        self.models: dict[str, dict[str, Any]] = {}
        self.fail_downloads = False
        self.experiments: dict[str, str] = {}
        self.runs: dict[str, dict[str, Any]] = {}
        self.requests: list[tuple[str, str]] = []

    def handle(  # noqa: PLR0911 - one return per endpoint and error
        self, method: str, path: str, query: dict[str, str], body: bytes
    ) -> tuple[int, Any]:
        self.requests.append((method, path))
        prefix = "/api/2.0/mlflow-artifacts/artifacts/"
        if path.startswith(prefix):
            key = urllib.parse.unquote(path[len(prefix) :])
            if method == "PUT":
                self.artifacts[key] = body
                return 200, {}
            if self.fail_downloads or key not in self.artifacts:
                return 404, {"error_code": "RESOURCE_DOES_NOT_EXIST"}
            return 200, self.artifacts[key]
        data = json.loads(body) if body else {}
        if path == "/api/2.0/mlflow/registered-models/create":
            if data["name"] in self.models:
                return 400, {"error_code": "RESOURCE_ALREADY_EXISTS"}
            self.models[data["name"]] = {"versions": {}, "aliases": {}}
            return 200, {"registered_model": {"name": data["name"]}}
        if path == "/api/2.0/mlflow/model-versions/create":
            model = self.models[data["name"]]
            version = str(len(model["versions"]) + 1)
            model["versions"][version] = data["source"]
            return 200, {"model_version": {"version": version, "source": data["source"]}}
        if path == "/api/2.0/mlflow/registered-models/alias":
            if method == "POST":
                self.models[data["name"]]["aliases"][data["alias"]] = data["version"]
                return 200, {}
            found = self.models.get(query["name"])
            alias = found["aliases"].get(query["alias"]) if found is not None else None
            if found is None or alias is None:
                return 404, {"error_code": "RESOURCE_DOES_NOT_EXIST"}
            return 200, {"model_version": {"version": alias, "source": found["versions"][alias]}}
        return self.tracking(path, query, data)

    def tracking(self, path: str, query: dict[str, str], data: dict[str, Any]) -> tuple[int, Any]:
        if path == "/api/2.0/mlflow/experiments/get-by-name":
            if query["experiment_name"] not in self.experiments:
                return 404, {"error_code": "RESOURCE_DOES_NOT_EXIST"}
            return 200, {
                "experiment": {"experiment_id": self.experiments[query["experiment_name"]]}
            }
        if path == "/api/2.0/mlflow/experiments/create":
            self.experiments[data["name"]] = str(len(self.experiments) + 1)
            return 200, {"experiment_id": self.experiments[data["name"]]}
        if path == "/api/2.0/mlflow/runs/create":
            run = f"run{len(self.runs) + 1}"
            self.runs[run] = {"tags": data["tags"], "metrics": []}
            return 200, {"run": {"info": {"run_id": run}}}
        if path == "/api/2.0/mlflow/runs/log-batch":
            self.runs[data["run_id"]]["metrics"].extend(data["metrics"])
            return 200, {}
        return 404, {"error_code": "ENDPOINT_NOT_FOUND"}


@pytest.fixture
def mlflow() -> Iterator[tuple[FakeMlflow, str]]:
    fake = FakeMlflow()

    class Handler(BaseHTTPRequestHandler):
        def _serve(self) -> None:
            parsed = urllib.parse.urlparse(self.path)
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length else b""
            query = dict(urllib.parse.parse_qsl(parsed.query))
            status, reply = fake.handle(self.command, parsed.path, query, body)
            payload = reply if isinstance(reply, bytes) else json.dumps(reply).encode()
            self.send_response(status)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        do_GET = do_POST = do_PUT = _serve  # noqa: N815

        def log_message(self, *_: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield fake, f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def watcher(
    url: str, holder: ModelHolder, kit: SimpleNamespace, cache: Path, poll: float = 5.0
) -> AliasWatcher:
    def production(bundle: Bundle) -> None:
        holder.swap_production(Scorer(bundle, kit.reference))

    def shadow(bundle: Bundle | None) -> None:
        holder.swap_shadow(Scorer(bundle, kit.reference) if bundle else None)

    return AliasWatcher(
        MlflowRegistry(url),
        NAME,
        cache,
        production,
        shadow,
        poll_seconds=poll,
        metrics=WatcherMetrics.create(CollectorRegistry()),
    )


def current_shadow(holder: ModelHolder) -> Scorer | None:
    """Read through a call, so the type checker does not carry an earlier narrowing forward."""
    return holder.shadow


@pytest.mark.req("FR-02-10", "D-50")
def test_publish_then_serve_then_promote_and_roll_back_by_alias(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    kit: SimpleNamespace,
    tmp_path: Path,
) -> None:
    fake, url = mlflow
    registry = MlflowRegistry(url)
    a, b = (Bundle.load(d) for d in bundle_dirs)
    assert registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION) == "1"
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path)
    assert w.poll_once() == {PRODUCTION: "1", SHADOW: None}
    assert holder.production is not None
    assert holder.production.model_version == a.model_version

    assert registry.publish(NAME, bundle_dirs[1], alias=SHADOW) == "2"
    w.poll_once()
    assert holder.shadow is not None
    assert holder.shadow.model_version == b.model_version

    registry.publish(NAME, bundle_dirs[1], alias=PRODUCTION)  # v3, same bundle as v2
    assert fake.models[NAME]["aliases"][PREVIOUS] == "1", "rollback is one alias move"
    w.poll_once()
    assert holder.production.model_version == b.model_version

    registry.set_alias(NAME, PRODUCTION, "1")
    w.poll_once()
    assert holder.production.model_version == a.model_version
    assert w.metrics.swaps.labels(PRODUCTION)._value.get() == 3


@pytest.mark.req("FR-02-10")
def test_a_bundle_that_fails_verification_is_not_swapped_in(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    kit: SimpleNamespace,
    tmp_path: Path,
) -> None:
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION)
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path)
    w.poll_once()
    serving = holder.production

    registry.publish(NAME, bundle_dirs[1])
    corrupt = next(
        k
        for k in fake.artifacts
        if k.endswith("forest.json")
        and "bundles" in k
        and fake.models[NAME]["versions"]["2"].endswith(k.rsplit("/", 1)[0])
    )
    fake.artifacts[corrupt] = b"{}"
    registry.set_alias(NAME, PRODUCTION, "2")
    w.poll_once()
    assert holder.production is serving, "the old model keeps serving"
    assert w.metrics.failures.labels(PRODUCTION)._value.get() == 1


def test_an_unreachable_registry_keeps_the_current_model(
    bundle_dirs: tuple[Path, Path], kit: SimpleNamespace, tmp_path: Path
) -> None:
    holder = ModelHolder(Scorer(Bundle.load(bundle_dirs[0]), kit.reference))
    w = watcher("http://127.0.0.1:9", holder, kit, tmp_path)
    serving = holder.production
    assert w.poll_once() == {PRODUCTION: None, SHADOW: None}
    assert holder.production is serving


def test_removing_the_shadow_alias_turns_shadow_mode_off(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    kit: SimpleNamespace,
    tmp_path: Path,
) -> None:
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION)
    registry.publish(NAME, bundle_dirs[1], alias=SHADOW)
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path)
    w.poll_once()
    assert holder.shadow is not None
    del fake.models[NAME]["aliases"][SHADOW]
    del fake.models[NAME]["aliases"][PRODUCTION]
    w.poll_once()
    assert current_shadow(holder) is None
    assert holder.production is not None, "a removed production alias never unloads the model"


@pytest.mark.req("FR-02-10")
def test_the_background_watcher_sees_an_alias_move_within_its_poll_interval(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    kit: SimpleNamespace,
    tmp_path: Path,
) -> None:
    _, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION)
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path, poll=0.2)
    w.start()
    try:
        assert wait_until(lambda: holder.production is not None, 10)
        registry.publish(NAME, bundle_dirs[1], alias=PRODUCTION)
        target = Bundle.load(bundle_dirs[1]).model_version
        assert wait_until(
            lambda: holder.production is not None and holder.production.model_version == target,
            10,
        )
    finally:
        w.stop()
    with pytest.raises(ValueError, match="10 s"):
        watcher(url, holder, kit, tmp_path, poll=11)


def test_registry_errors_are_reported_with_their_cause(mlflow: tuple[FakeMlflow, str]) -> None:
    _, url = mlflow
    registry = MlflowRegistry(url, token="t0k")  # noqa: S106 - a test token
    assert registry.by_alias("absent", PRODUCTION) is None
    registry.ensure_model(NAME)
    registry.ensure_model(NAME)  # already exists: not an error
    with pytest.raises(RegistryError, match="HTTP 404"):
        registry.download("nope")
    with pytest.raises(RegistryError, match="not an http"):
        MlflowRegistry("ftp://x").by_alias(NAME, PRODUCTION)
    with pytest.raises(RegistryError, match="unsupported source"):
        registry.fetch_bundle(ModelVersion(NAME, "9", "s3://elsewhere"), Path("/nonexistent"))


def test_a_local_source_is_served_in_place(bundle_dirs: tuple[Path, Path], tmp_path: Path) -> None:
    registry = MlflowRegistry("http://127.0.0.1:9")
    version = ModelVersion(NAME, "1", f"file://{bundle_dirs[0]}")
    assert registry.fetch_bundle(version, tmp_path) == bundle_dirs[0]


@pytest.mark.req("FR-02-08", "TEST-08")
def test_the_shadow_comparison_is_logged_to_mlflow(mlflow: tuple[FakeMlflow, str]) -> None:
    fake, url = mlflow
    log = MlflowComparisonLog(MlflowRegistry(url), production_version=lambda: "ensemble-prod")
    comparison = Comparison(started=datetime(2026, 9, 22, tzinfo=UTC), shadow_version="ens-new")
    for i in range(20):
        comparison.add(f"t{i}", i / 20, i / 20 + 0.01)
    log.flush(comparison)
    log.flush(comparison)
    assert fake.experiments == {"fraudshield-shadow": "1"}
    (run,) = fake.runs.values()
    assert {"key": "production_model_version", "value": "ensemble-prod"} in run["tags"]
    keys = {m["key"] for m in run["metrics"]}
    assert keys == {"shadow_scored", "shadow_mean_abs_score_diff", "shadow_psi"}
    assert [m["step"] for m in run["metrics"] if m["key"] == "shadow_scored"] == [1, 2]


def test_a_tracking_outage_does_not_stop_shadow_scoring() -> None:
    log = MlflowComparisonLog(MlflowRegistry("http://127.0.0.1:9"), production_version=str)
    comparison = Comparison(started=datetime(2026, 9, 22, tzinfo=UTC), shadow_version="v")
    comparison.add("t", 0.1, 0.2)
    log.flush(comparison)  # logs a warning, raises nothing
    log.flush(Comparison(started=datetime(2026, 9, 22, tzinfo=UTC)))  # no shadow model: no-op


@pytest.mark.req("D-50")
def test_fs_model_publishes_and_moves_aliases(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    capsys: pytest.CaptureFixture[str],
) -> None:
    fake, url = mlflow
    assert (
        model_cli.main(
            ["publish", "--bundle", str(bundle_dirs[0]), "--mlflow", url, "--alias", PRODUCTION]
        )
        == 0
    )
    assert model_cli.main(["publish", "--bundle", str(bundle_dirs[1]), "--mlflow", url]) == 0
    assert model_cli.main(["alias", "--mlflow", url, SHADOW, "2"]) == 0
    assert fake.models[NAME]["aliases"] == {PRODUCTION: "1", SHADOW: "2"}
    assert "@shadow -> v2" in capsys.readouterr().out


@pytest.mark.requires_docker
@pytest.mark.req("FR-02-10", "D-50")
def test_publish_and_hot_swap_against_the_mlflow_the_deployment_runs(
    bundle_dirs: tuple[Path, Path], kit: SimpleNamespace, tmp_path: Path
) -> None:
    """The fake above encodes my reading of MLflow's REST API; this checks it against MLflow
    3.16.0, the image docker-compose.yml pins, with the artifact proxy on. Skipped without Docker,
    run in CI (ADR 0010)."""
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = int(s.getsockname()[1])
    command = [
        "docker",
        "run",
        "-d",
        "--rm",
        "-p",
        f"127.0.0.1:{port}:5000",
        "ghcr.io/mlflow/mlflow:v3.16.0",
        "mlflow",
        "server",
        "--host",
        "0.0.0.0",  # noqa: S104 - inside the container, published to 127.0.0.1 only
        "--port",
        "5000",
        "--backend-store-uri",
        "sqlite:////tmp/mlflow.db",
        "--artifacts-destination",
        "/tmp/artifacts",  # noqa: S108 - a path inside the throwaway container
        "--serve-artifacts",
    ]
    container = subprocess.run(  # noqa: S603
        command, capture_output=True, text=True, check=True, timeout=900
    ).stdout.strip()
    url = f"http://127.0.0.1:{port}"
    try:
        assert wait_until(lambda: _healthy(url), 180), "MLflow did not become healthy"
        registry = MlflowRegistry(url)
        assert registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION) == "1"
        holder = ModelHolder()
        w = watcher(url, holder, kit, tmp_path)
        w.poll_once()
        assert holder.production is not None
        registry.publish(NAME, bundle_dirs[1], alias=PRODUCTION)
        w.poll_once()
        assert holder.production.model_version == Bundle.load(bundle_dirs[1]).model_version
        run = registry.start_run(registry.experiment("fs-shadow-it"), "it", {"k": "v"})
        registry.log_metrics(run, {"shadow_scored": 1.0}, 1)
    finally:
        subprocess.run(["docker", "stop", container], capture_output=True, check=False)  # noqa: S603, S607


def _healthy(url: str) -> bool:
    try:
        with urllib.request.urlopen(url + "/health", timeout=2):  # noqa: S310
            return True
    except OSError:
        return False


def test_a_second_worker_downloading_the_same_version_uses_the_first_copy(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Another worker finishes the same download first: this one's rename fails because the
    target now exists, and it must use that copy rather than report a failed swap."""
    _, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION)
    version = registry.by_alias(NAME, PRODUCTION)
    assert version is not None
    real_rename = Path.rename

    def raced(self: Path, target: Path) -> Path:
        shutil.copytree(self, target)  # the other worker's copy lands first
        raise OSError(39, "Directory not empty")

    monkeypatch.setattr(Path, "rename", raced)
    fetched = registry.fetch_bundle(version, tmp_path)
    monkeypatch.setattr(Path, "rename", real_rename)
    assert Bundle.load(fetched).model_version == Bundle.load(bundle_dirs[0]).model_version
    assert not list(fetched.parent.glob(".*.partial")), "the loser's staging copy is removed"
