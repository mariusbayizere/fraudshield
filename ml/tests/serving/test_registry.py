"""Hot swap by registry alias (D-50, FR-02-10) against a fake MLflow REST server.

The fake implements the endpoints `MlflowRegistry` calls, with MLflow's error shapes
(`RESOURCE_DOES_NOT_EXIST` as HTTP 404, `RESOURCE_ALREADY_EXISTS` and an unset alias as 400). It
checks the client
against MLflow's documented REST API, not against a running MLflow; that is an integration check
for the CI stack, where the compose MLflow server runs.
"""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Iterator
from dataclasses import replace
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from prometheus_client import CollectorRegistry

from fraudshield_ml.models import cli as model_cli
from fraudshield_ml.models.bundle import MANIFEST, Bundle
from fraudshield_ml.models.cli import _gate as read_gate
from fraudshield_ml.serving.registry import (
    PREVIOUS,
    PRODUCTION,
    SERVED_TAG,
    SHADOW,
    AliasWatcher,
    MlflowRegistry,
    ModelVersion,
    PromotionRefused,
    RegistryError,
    WatcherMetrics,
    promotion_problems,
    wait_until,
)
from fraudshield_ml.serving.scorer import ModelHolder, Scorer
from fraudshield_ml.serving.shadow import Comparison, GateDecision, MlflowComparisonLog

NAME = "fraudshield-ensemble"
#: No shadow window exists for a first deployment; D-11's gate needs one.
FIRST = "first deployment: no shadow window yet"


class FakeMlflow:
    def __init__(self) -> None:
        self.artifacts: dict[str, bytes] = {}
        self.models: dict[str, dict[str, Any]] = {}
        self.fail_downloads = False
        self.experiments: dict[str, str] = {}
        self.runs: dict[str, dict[str, Any]] = {}
        self.requests: list[tuple[str, str]] = []
        self.tags: dict[tuple[str, str], dict[str, str]] = {}
        self.refuse_tags = False
        #: How this registry words "that alias is not set"; overridden by the shape tests.
        self.alias_miss: tuple[int, dict[str, str]] = (
            400,
            {
                "error_code": "INVALID_PARAMETER_VALUE",
                "message": "Registered model alias {alias} not found.",
                "sqlstate": "KAM00",
            },
        )

    def handle(  # noqa: PLR0911, PLR0912 - one return per endpoint and error
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
        if self.refuse_tags and "tag" in path:
            return 500, {"error_code": "INTERNAL_ERROR"}
        if path == "/api/2.0/mlflow/model-versions/set-tag":
            self.tags.setdefault((data["name"], data["version"]), {})[data["key"]] = data["value"]
            return 200, {}
        if path == "/api/2.0/mlflow/model-versions/get":
            if self.refuse_tags:
                return 500, {"error_code": "INTERNAL_ERROR"}
            source = self.models[query["name"]]["versions"][query["version"]]
            tags = self.tags.get((query["name"], query["version"]), {})
            return 200, {
                "model_version": {
                    "version": query["version"],
                    "source": source,
                    # MLflow returns tags on the version, which is how a rollback is recognised.
                    "tags": [{"key": k, "value": v} for k, v in sorted(tags.items())],
                }
            }
        if path == "/api/2.0/mlflow/registered-models/alias":
            if method == "POST":
                self.models[data["name"]]["aliases"][data["alias"]] = data["version"]
                return 200, {}
            found = self.models.get(query["name"])
            if found is None:
                # The real server distinguishes these two: a missing model is 404, an unset alias
                # is 400. Answering both the same way hid the difference from the client.
                return 404, {
                    "error_code": "RESOURCE_DOES_NOT_EXIST",
                    "message": f"Registered Model with name={query['name']} not found",
                }
            alias = found["aliases"].get(query["alias"])
            if alias is None:
                # MLflow 3.16.0's own answer, checked against the container in the Docker test
                # below: 400 INVALID_PARAMETER_VALUE, not the 404 this fake used to return.
                status, miss = self.alias_miss
                return status, {k: v.format(alias=query["alias"]) for k, v in miss.items()}
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
    assert registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST) == "1"
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path)
    assert w.poll_once() == {PRODUCTION: "1", SHADOW: None}
    assert holder.production is not None
    assert holder.production.model_version == a.model_version

    assert registry.publish(NAME, bundle_dirs[1], alias=SHADOW) == "2"
    w.poll_once()
    assert holder.shadow is not None
    assert holder.shadow.model_version == b.model_version

    registry.publish(
        NAME, bundle_dirs[1], alias=PRODUCTION, override=FIRST
    )  # v3, same bundle as v2
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
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
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
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
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
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path, poll=0.2)
    w.start()
    try:
        assert wait_until(lambda: holder.production is not None, 10)
        registry.publish(NAME, bundle_dirs[1], alias=PRODUCTION, override=FIRST)
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
    with pytest.raises(RegistryError, match="not found"):
        registry.by_alias("absent", PRODUCTION)  # a missing model is not an unset alias
    registry.ensure_model(NAME)
    assert registry.by_alias(NAME, PRODUCTION) is None, "the model exists; the alias is unset"
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
            [
                "publish",
                "--bundle",
                str(bundle_dirs[0]),
                "--mlflow",
                url,
                "--alias",
                PRODUCTION,
                "--override",
                FIRST,
            ]
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
    3.16.0, the MLflow version docker-compose.yml pins (it runs the `-full` image on PostgreSQL;
    this runs the slim one on sqlite, the same `SqlAlchemyStore` path), with the artifact proxy
    on. Skipped without Docker,
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
        assert registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST) == "1"
        holder = ModelHolder()
        w = watcher(url, holder, kit, tmp_path)
        w.poll_once()
        assert holder.production is not None
        registry.publish(NAME, bundle_dirs[1], alias=PRODUCTION, override=FIRST)
        w.poll_once()
        assert holder.production.model_version == Bundle.load(bundle_dirs[1]).model_version

        # The promotion record and the rollback exemption both depend on version tags coming
        # back from a real server, not only from the fake above (re-review N1b, V1).
        # **Pin the fake against the server.** The fake encodes a reading of this API, and a
        # wrong reading is invisible to every test that uses it -- that is what shipped the alias
        # defect (M5_updates §19, lab notebook 2026-09-23). So the shapes the client branches on
        # are compared with what MLflow 3.16.0 actually answers, here, against the container.
        fake = FakeMlflow()
        status, body = _alias_reply(url, "fraudshield-not-a-model", PRODUCTION)
        assert status == 404, "a missing model is 404 RESOURCE_DOES_NOT_EXIST"
        assert body["error_code"] == "RESOURCE_DOES_NOT_EXIST"
        assert "with name=" in body["message"], "and names the model, which is how it is told apart"

        status, body = _alias_reply(url, NAME, "no-such-alias")
        fake_status, fake_body = fake.alias_miss
        assert (status, body["error_code"]) == (fake_status, fake_body["error_code"]), (
            f"the fake answers an unset alias {fake_status} {fake_body['error_code']}; "
            f"MLflow 3.16.0 answers {status} {body['error_code']}"
        )
        assert body["message"] == fake_body["message"].format(alias="no-such-alias")

        tags = registry.version_tags(NAME, "2")
        assert tags["fraudshield.promotion_override"] == FIRST
        assert SERVED_TAG in tags
        assert registry.has_served(NAME, "2")
        assert not registry.has_served(NAME, "3"), "a version that does not exist never served"
        registry.promote(NAME, PRODUCTION, "1", tmp_path / "rollback")
        rolled_back = registry.by_alias(NAME, PRODUCTION)
        assert rolled_back is not None
        assert rolled_back.version == "1", "a rollback needs no shadow gate (D-50)"
        run = registry.start_run(registry.experiment("fs-shadow-it"), "it", {"k": "v"})
        registry.log_metrics(run, {"shadow_scored": 1.0}, 1)
    finally:
        subprocess.run(["docker", "stop", container], capture_output=True, check=False)  # noqa: S603, S607


def _alias_reply(url: str, name: str, alias: str) -> tuple[int, dict[str, str]]:
    """The registry's raw answer for `registered-models/alias`, status and decoded body."""
    query = urllib.parse.urlencode({"name": name, "alias": alias})
    try:
        with urllib.request.urlopen(  # noqa: S310 - a localhost container in this test
            f"{url}/api/2.0/mlflow/registered-models/alias?{query}", timeout=10
        ) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


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
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
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


def _badly_calibrated(bundle_dir: Path, tmp_path: Path) -> Path:
    """A copy of a bundle whose manifest records a held-out ECE over FR-02-03's 0.05."""
    copy = tmp_path / "miscalibrated"
    shutil.copytree(bundle_dir, copy)
    manifest = json.loads((copy / "manifest.json").read_text())
    manifest["provenance"]["held_out"]["ece_equal_width_10"] = 0.08
    (copy / "manifest.json").write_text(json.dumps(manifest))
    return copy


@pytest.mark.req("FR-02-03")
def test_a_bundle_over_the_ece_limit_cannot_become_production(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    kit: SimpleNamespace,
    tmp_path: Path,
) -> None:
    fake, url = mlflow
    registry = MlflowRegistry(url)
    bad = _badly_calibrated(bundle_dirs[1], tmp_path)
    with pytest.raises(PromotionRefused, match=r"ECE 0\.0800 exceeds 0\.05"):
        registry.publish(NAME, bad, alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)  # v1, well calibrated
    registry.publish(NAME, bad, alias=SHADOW)  # v2: shadowing a model is not deploying it
    with pytest.raises(PromotionRefused):
        registry.promote(NAME, PRODUCTION, "2", tmp_path / "cache", override=FIRST)
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"

    # Defence in depth: a production alias moved around these checks is still not served.
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path / "watch")
    w.poll_once()
    serving = holder.production
    fake.models[NAME]["aliases"][PRODUCTION] = "2"
    w.poll_once()
    assert holder.production is serving
    assert w.metrics.failures.labels(PRODUCTION)._value.get() == 1


def test_a_bundle_without_a_measured_ece_is_refused_too() -> None:
    assert promotion_problems({"provenance": {"held_out": {}}})
    assert promotion_problems({"provenance": {"held_out": {"ece_equal_width_10": 0.01}}}) == []


def _gate_report(path: Path, bundle: Path | None = None, **overrides: object) -> Path:
    """A shadow comparator's decision, in the shape `fs-model --gate` reads.

    `bundle` is the bundle the decision measured: D-11 is re-checked against the version being
    promoted, so a report that names a different model (or none) is refused (re-review V2).
    """
    report: dict[str, object] = {
        "promote": True,
        "reasons": [],
        "scored": 60_000,
        "label_coverage": 0.42,
        "auc_delta": 0.002,
        "psi": 0.05,
        "window_hours": 30.0,
        "shadow_version": (
            json.loads((bundle / MANIFEST).read_text())["model_version"] if bundle else None
        ),
    }
    path.write_text(json.dumps(report | overrides))
    return path


@pytest.mark.req("ML-GATE-13", "D-11")
def test_production_promotion_needs_the_shadow_gate(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1], alias=SHADOW)

    with pytest.raises(PromotionRefused, match="no shadow-gate decision"):
        registry.promote(NAME, PRODUCTION, "2", tmp_path / "c")
    failed = _gate_report(
        tmp_path / "failed.json",
        bundle_dirs[1],
        promote=False,
        reasons=["Insufficient labels: coverage 11.0% is under 30%"],
    )
    with pytest.raises(PromotionRefused, match="Insufficient labels"):
        registry.promote(NAME, PRODUCTION, "2", tmp_path / "c", gate=_gate(failed))
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1", "neither refusal moved the alias"

    registry.promote(
        NAME,
        PRODUCTION,
        "2",
        tmp_path / "c",
        gate=_gate(_gate_report(tmp_path / "ok.json", bundle_dirs[1])),
    )
    assert fake.models[NAME]["aliases"][PRODUCTION] == "2"
    assert fake.models[NAME]["aliases"][PREVIOUS] == "1", "rollback is one alias move (D-50)"


@pytest.mark.req("D-50", "ML-GATE-13")
def test_fs_model_alias_reads_the_gate_report(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])
    assert model_cli.main(["alias", "--mlflow", url, PRODUCTION, "2"]) == 1
    report = _gate_report(tmp_path / "gate.json", bundle_dirs[1])
    assert (
        model_cli.main(
            [
                "alias",
                "--mlflow",
                url,
                PRODUCTION,
                "2",
                "--gate",
                str(report),
                "--cache",
                str(tmp_path / "c"),
            ]
        )
        == 0
    )
    assert fake.models[NAME]["aliases"][PRODUCTION] == "2"


def _gate(path: Path) -> GateDecision:
    decision = read_gate(path)
    assert decision is not None
    return decision


@pytest.mark.req("ML-GATE-13", "D-11")
def test_a_report_that_refuses_without_a_reason_does_not_promote(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    """The gate is a report an external comparator writes, so its `promote` flag is checked
    against the figures beside it, not trusted (re-review N1)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])

    silent = _gate_report(tmp_path / "silent.json", bundle_dirs[1], promote=False, reasons=[])
    with pytest.raises(PromotionRefused, match="named no reason"):
        registry.promote(NAME, PRODUCTION, "2", tmp_path / "c", gate=_gate(silent))

    for field, value, expected in [
        ("scored", 3, "need 50000"),
        ("label_coverage", 0.01, "under 30%"),
        ("auc_delta", -0.5, "below -0.01"),
        ("psi", 0.9, "not below 0.2"),
        ("auc_delta", None, "no usable AUC delta"),
        ("psi", None, "no usable score PSI"),
        ("window_hours", None, "no usable shadow window"),
        ("window_hours", 3.0, "under 24 h"),
        ("shadow_version", None, "does not name the model"),
        ("shadow_version", "ensemble-somethingelse", "not ensemble-"),
    ]:
        lying = _gate_report(
            tmp_path / f"{field}.json", bundle_dirs[1], promote=True, reasons=[], **{field: value}
        )
        with pytest.raises(PromotionRefused, match=expected):
            registry.promote(NAME, PRODUCTION, "2", tmp_path / "c", gate=_gate(lying))
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1", "no refusal moved the alias"


@pytest.mark.req("ML-GATE-13", "D-11")
def test_an_override_is_recorded_on_the_version_it_promoted(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    """`--override` says "(recorded)"; this is the record (re-review N1b)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    assert fake.tags[(NAME, "1")]["fraudshield.promotion_override"] == FIRST
    assert "fraudshield.promoted_at" in fake.tags[(NAME, "1")]

    registry.publish(NAME, bundle_dirs[1])
    registry.promote(
        NAME,
        PRODUCTION,
        "2",
        tmp_path / "c",
        gate=_gate(_gate_report(tmp_path / "ok.json", bundle_dirs[1])),
    )
    recorded = fake.tags[(NAME, "2")]
    assert "fraudshield.promotion_override" not in recorded, "the gate passed; nothing was skipped"
    assert "60000 scores" in recorded["fraudshield.promotion_gate"]


@pytest.mark.req("FR-02-10", "D-50")
def test_a_rollback_through_fs_model_alias_needs_no_shadow_gate(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    """D-50: rollback is one alias move. The version `previous_production` holds has already
    served, so mid-incident nobody is asked for a 24-hour shadow window (re-review N2)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])
    registry.promote(
        NAME,
        PRODUCTION,
        "2",
        tmp_path / "c",
        gate=_gate(_gate_report(tmp_path / "ok.json", bundle_dirs[1])),
    )
    assert fake.models[NAME]["aliases"] == {PRODUCTION: "2", PREVIOUS: "1"}

    argv = ["alias", "--mlflow", url, PRODUCTION, "1", "--cache", str(tmp_path / "c")]
    assert model_cli.main(argv) == 0, "the rollback an operator runs, with no --gate"
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"
    assert fake.models[NAME]["aliases"][PREVIOUS] == "2", "and it can be rolled forward again"
    assert fake.tags[(NAME, "1")]["fraudshield.promotion_gate"].startswith("rollback")
    forward = ["alias", "--mlflow", url, PRODUCTION, "2", "--cache", str(tmp_path)]
    assert model_cli.main(forward) == 0, "rolling forward again is the same one move"


@pytest.mark.req("FR-02-10")
def test_a_swap_callback_that_raises_leaves_the_watcher_watching(
    mlflow: tuple[FakeMlflow, str],
    bundle_dirs: tuple[Path, Path],
    kit: SimpleNamespace,
    tmp_path: Path,
) -> None:
    """The callback warms ONNX sessions before the swap. On the watcher's own thread an exception
    there would end the thread, and the scorer would serve on with nothing watching @production
    for the next alias move — including a rollback (re-review, residual risks)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    holder = ModelHolder()
    w = watcher(url, holder, kit, tmp_path)
    warmed: list[str] = []

    def warm(bundle: Bundle) -> None:
        if not warmed:
            warmed.append(bundle.model_version)
            raise RuntimeError("onnxruntime could not allocate an arena")
        holder.swap_production(Scorer(bundle, kit.reference))

    w._on[PRODUCTION] = warm
    w.poll_once()
    unwarmed = holder.production
    assert unwarmed is None, "a model that did not warm is not swapped in"
    assert w.metrics.failures.labels(PRODUCTION)._value.get() == 1
    assert w.metrics.swaps.labels(PRODUCTION)._value.get() == 0
    assert w.loaded[PRODUCTION] is None, "and the version is not recorded as loaded"

    w.poll_once()
    assert holder.production is not None, "the next poll swaps it in: the watcher is still alive"
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"


@pytest.mark.req("ML-GATE-13", "D-11")
def test_moving_previous_production_by_hand_does_not_buy_a_promotion(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    """The rollback exemption must rest on having served, not on where an alias points.

    `previous_production` is one of the aliases `fs-model alias` moves freely, so taking it as
    proof made D-11 a two-command formality: point it at any version, then promote that version
    (re-review V1).
    """
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])  # v2: never served

    assert model_cli.main(["alias", "--mlflow", url, PREVIOUS, "2"]) == 0
    cache = ["--cache", str(tmp_path / "c")]
    assert model_cli.main(["alias", "--mlflow", url, PRODUCTION, "2", *cache]) == 1, (
        "a version that never served is a promotion, and needs the gate"
    )
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"
    assert SERVED_TAG not in fake.tags.get((NAME, "2"), {})
    assert "rollback" not in json.dumps(fake.tags.get((NAME, "2"), {})), (
        "and nothing may record it as one"
    )

    # The same version, promoted properly, may then be rolled back to.
    registry.promote(
        NAME,
        PRODUCTION,
        "2",
        tmp_path / "c",
        gate=_gate(_gate_report(tmp_path / "ok.json", bundle_dirs[1])),
    )
    assert SERVED_TAG in fake.tags[(NAME, "2")]
    assert registry.has_served(NAME, "2"), "read back through the registry, not the fake's dict"
    assert model_cli.main(["alias", "--mlflow", url, PRODUCTION, "1", *cache]) == 0
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"


@pytest.mark.req("ML-GATE-13", "D-11")
@pytest.mark.parametrize("field", ["label_coverage", "auc_delta", "psi", "window_hours"])
def test_a_not_a_number_figure_is_a_clause_that_was_not_applied(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path, field: str
) -> None:
    """Every D-11 clause is a `<` or a `>=`, and NaN passes all of them. `json` both accepts the
    bare `NaN` token and emits it, so a comparator that serialises its own dataclass writes one
    (adversarial BLOCKER 1)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])

    report = _gate_report(tmp_path / f"nan-{field}.json", bundle_dirs[1])
    decision = _gate(report)
    assert decision is not None
    nan_decision = replace(decision, **{field: float("nan")})  # type: ignore[arg-type]
    with pytest.raises(PromotionRefused, match="no usable"):
        registry.promote(NAME, PRODUCTION, "2", tmp_path / "c", gate=nan_decision)
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"

    # And the report never becomes a decision in the first place: `fs-model` refuses the file.
    on_disk = json.loads(report.read_text())
    on_disk[field] = float("nan")
    report.write_text(json.dumps(on_disk))
    argv = [
        "alias",
        "--mlflow",
        url,
        PRODUCTION,
        "2",
        "--gate",
        str(report),
        "--cache",
        str(tmp_path),
    ]
    assert model_cli.main(argv) == 1
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"


@pytest.mark.req("ML-GATE-13", "D-11")
def test_a_blank_override_is_not_an_override(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    """`--override` promotes without a shadow window and records why. An empty string records
    nothing, and `--override "$REASON"` with REASON unset is one unset variable away from a
    silent promotion (adversarial MAJOR 3)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])

    with pytest.raises(PromotionRefused, match="no shadow-gate decision"):
        registry.promote(NAME, PRODUCTION, "2", tmp_path / "c", override="   ")
    with pytest.raises(SystemExit):
        model_cli.main(["alias", "--mlflow", url, PRODUCTION, "2", "--override", ""])
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1"


@pytest.mark.req("FR-02-10", "D-50")
def test_a_promotion_that_cannot_record_itself_does_not_happen(
    mlflow: tuple[FakeMlflow, str], bundle_dirs: tuple[Path, Path], tmp_path: Path
) -> None:
    """The served tag is the rollback's only proof, so it is written before the alias moves and
    its failure refuses the promotion — otherwise production ends up on a version the operator
    cannot roll back to, and is told mid-incident that it never served (adversarial MAJOR 2)."""
    fake, url = mlflow
    registry = MlflowRegistry(url)
    registry.publish(NAME, bundle_dirs[0], alias=PRODUCTION, override=FIRST)
    registry.publish(NAME, bundle_dirs[1])

    fake.refuse_tags = True
    with pytest.raises(RegistryError):
        registry.promote(
            NAME,
            PRODUCTION,
            "2",
            tmp_path / "c",
            gate=_gate(_gate_report(tmp_path / "ok.json", bundle_dirs[1])),
        )
    assert fake.models[NAME]["aliases"][PRODUCTION] == "1", "production did not move"

    fake.refuse_tags = False
    registry.promote(
        NAME,
        PRODUCTION,
        "2",
        tmp_path / "c",
        gate=_gate(_gate_report(tmp_path / "ok.json", bundle_dirs[1])),
    )
    assert SERVED_TAG in fake.tags[(NAME, "2")]

    # A version that `previous_production` points at but that carries no record of serving is
    # refused -- and told so in those words, not "the gate refused you".
    fake.tags.pop((NAME, "1"))
    with pytest.raises(PromotionRefused, match=r"carries no fraudshield\.served_as_production"):
        registry.promote(NAME, PRODUCTION, "1", tmp_path / "c")
    assert (
        model_cli.main(
            [
                "alias",
                "--mlflow",
                url,
                PRODUCTION,
                "1",
                "--cache",
                str(tmp_path / "c"),
                "--override",
                "its served tag was lost with the tracking database",
            ]
        )
        == 0
    ), "the operator has a way through, and it is recorded"
    assert fake.tags[(NAME, "1")]["fraudshield.promotion_override"].startswith("its served tag")


@pytest.mark.req("FR-02-10", "D-50")
@pytest.mark.parametrize(
    ("status", "payload"),
    [
        # MLflow 3.16.0's actual answer for an unset alias, from the container in the Docker test.
        (
            400,
            {
                "error_code": "INVALID_PARAMETER_VALUE",
                "message": "Registered model alias production not found.",
            },
        ),
        # What the rest of its registry API returns for an absent thing, in case a future
        # version words it that way.
        (
            404,
            {
                "error_code": "RESOURCE_DOES_NOT_EXIST",
                "message": "Registered model alias production not found.",
            },
        ),
        # A code with no message at all.
        (404, {"error_code": "RESOURCE_DOES_NOT_EXIST"}),
    ],
)
def test_an_unset_alias_is_absent_however_the_registry_words_it(
    mlflow: tuple[FakeMlflow, str], status: int, payload: dict[str, str]
) -> None:
    """An unset `@production` is the state every first deployment starts in, so reading it as an
    error fails the first promotion on a real server. The fake returned 404 and the real server
    returns 400, which is how this survived 537 local tests (M5_updates §19)."""
    fake, url = mlflow
    fake.alias_miss = (status, payload)
    registry = MlflowRegistry(url)
    registry.ensure_model(NAME)
    assert registry.by_alias(NAME, PRODUCTION) is None


def test_a_registry_error_that_is_not_an_absent_alias_still_raises(
    mlflow: tuple[FakeMlflow, str],
) -> None:
    """`INVALID_PARAMETER_VALUE` is also what a genuinely malformed request gets; swallowing the
    whole error code would hide a client bug as "no alias set"."""
    fake, url = mlflow
    fake.alias_miss = (400, {"error_code": "INVALID_PARAMETER_VALUE", "message": "bad name"})
    registry = MlflowRegistry(url)
    registry.ensure_model(NAME)
    with pytest.raises(RegistryError, match="bad name"):
        registry.by_alias(NAME, PRODUCTION)


@pytest.mark.req("FR-02-10")
def test_a_model_that_does_not_exist_is_not_read_as_an_unset_alias(
    mlflow: tuple[FakeMlflow, str],
) -> None:
    """Otherwise `AliasWatcher` polls a misspelled model name forever: every poll returns "no
    alias set", nothing is logged and no failure is counted (final review, observation 2)."""
    _, url = mlflow
    registry = MlflowRegistry(url)
    with pytest.raises(RegistryError, match="not found"):
        registry.by_alias("fraudshield-typo", PRODUCTION)


def test_an_invalid_alias_name_is_a_client_bug_not_an_absent_alias(
    mlflow: tuple[FakeMlflow, str],
) -> None:
    fake, url = mlflow
    fake.alias_miss = (
        400,
        {
            "error_code": "INVALID_PARAMETER_VALUE",
            "message": "Invalid alias name: 'pro/duction'. Names may only contain alphanumerics.",
        },
    )
    registry = MlflowRegistry(url)
    registry.ensure_model(NAME)
    with pytest.raises(RegistryError, match="Invalid alias name"):
        registry.by_alias(NAME, PRODUCTION)
