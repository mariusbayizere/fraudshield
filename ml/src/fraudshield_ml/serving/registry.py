"""Model hot swap from the MLflow registry by alias (D-50), and publishing a bundle to it.

**Aliases, not stages.** D-50: `@production`, `@shadow` and `@previous_production`; promotion and
rollback move aliases. The scorer polls the two it serves every `poll_seconds` (at most 10 s, M5's
gate), and on a change downloads the version's bundle, **loads and verifies it completely** —
hashes, format, feature registry — and only then swaps it in (`ModelHolder`). A version that fails
to load is logged and counted, and the current model keeps serving: a bad promotion must not take
scoring down.

**Blue-green in memory.** The new model is fully resident before the swap and the old one keeps
serving every request that already holds it, so during the switch both versions score and every
result names its own (FR-02-10). Nothing is scored by an unversioned model: a bundle without a
`model_version` cannot be built or loaded.

**Standard library only.** The client is a few REST calls over `urllib` rather than the MLflow
package, whose client pulls a certifi licence the policy does not allow at runtime (see
`docs/parallel/M5_updates.md`). Endpoints are MLflow 3's REST API: registered-model aliases, model
versions, and the artifact proxy the compose server runs with `--serve-artifacts`.
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from prometheus_client import CollectorRegistry, Counter

from fraudshield_ml.models.bundle import FILES, MANIFEST, Bundle, BundleError

PRODUCTION = "production"
SHADOW = "shadow"
PREVIOUS = "previous_production"
POLL_SECONDS = 5.0
MAX_POLL_SECONDS = 10.0
ARTIFACT_SCHEME = "mlflow-artifacts:/"
LOG = logging.getLogger(__name__)


class RegistryError(RuntimeError):
    """The registry answered with an error, or not at all."""


@dataclass(frozen=True)
class ModelVersion:
    name: str
    version: str
    source: str


class MlflowRegistry:
    """The handful of MLflow REST calls the scorer and `fs-model publish` need."""

    def __init__(self, base_url: str, *, token: str | None = None, timeout: float = 10.0) -> None:
        self.base_url = base_url.rstrip("/")
        self._token = token if token is not None else os.environ.get("MLFLOW_TRACKING_TOKEN")
        self._timeout = timeout

    # ---------------------------------------------------------------- transport

    def _request(
        self,
        method: str,
        path: str,
        *,
        query: dict[str, str] | None = None,
        body: Any = None,
        raw: bytes | None = None,
    ) -> bytes:
        url = f"{self.base_url}{path}"
        if not url.startswith(("http://", "https://")):
            raise RegistryError(f"{url} is not an http(s) URL")
        if query:
            url += "?" + urllib.parse.urlencode(query)
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = urllib.request.Request(url, data=data, method=method)  # noqa: S310 - checked
        request.add_header(
            "Content-Type", "application/json" if raw is None else "application/octet-stream"
        )
        if self._token:
            request.add_header("Authorization", f"Bearer {self._token}")
        try:
            with urllib.request.urlopen(request, timeout=self._timeout) as response:  # noqa: S310
                return bytes(response.read())
        except urllib.error.HTTPError as error:
            detail = error.read().decode(errors="replace")[:300]
            raise RegistryError(f"{method} {path}: HTTP {error.code} {detail}") from None
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            raise RegistryError(f"{method} {path}: {error}") from None

    def _json(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        payload = self._request(method, path, **kwargs)
        return json.loads(payload) if payload else {}

    # ---------------------------------------------------------------- registry

    def by_alias(self, name: str, alias: str) -> ModelVersion | None:
        try:
            reply = self._json(
                "GET",
                "/api/2.0/mlflow/registered-models/alias",
                query={"name": name, "alias": alias},
            )
        except RegistryError as error:
            if "RESOURCE_DOES_NOT_EXIST" in str(error) or "HTTP 404" in str(error):
                return None
            raise
        version = reply["model_version"]
        return ModelVersion(name=name, version=str(version["version"]), source=version["source"])

    def set_alias(self, name: str, alias: str, version: str) -> None:
        self._json(
            "POST",
            "/api/2.0/mlflow/registered-models/alias",
            body={"name": name, "alias": alias, "version": version},
        )

    def ensure_model(self, name: str) -> None:
        try:
            self._json("POST", "/api/2.0/mlflow/registered-models/create", body={"name": name})
        except RegistryError as error:
            if "RESOURCE_ALREADY_EXISTS" not in str(error):
                raise

    def create_version(self, name: str, source: str, description: str) -> str:
        reply = self._json(
            "POST",
            "/api/2.0/mlflow/model-versions/create",
            body={"name": name, "source": source, "description": description},
        )
        return str(reply["model_version"]["version"])

    # ---------------------------------------------------------------- artifacts

    def upload(self, path: str, content: bytes) -> None:
        self._request(
            "PUT", f"/api/2.0/mlflow-artifacts/artifacts/{urllib.parse.quote(path)}", raw=content
        )

    def download(self, path: str) -> bytes:
        return self._request(
            "GET", f"/api/2.0/mlflow-artifacts/artifacts/{urllib.parse.quote(path)}"
        )

    def fetch_bundle(self, version: ModelVersion, cache: Path) -> Path:
        """Download a version's bundle into `cache/<name>/<version>`, once, atomically."""
        target = cache / version.name / version.version
        if (target / MANIFEST).exists():
            return target
        if version.source.startswith(ARTIFACT_SCHEME):
            prefix = version.source[len(ARTIFACT_SCHEME) :].strip("/")
            staging = target.with_name(f".{version.version}.partial")
            shutil.rmtree(staging, ignore_errors=True)
            staging.mkdir(parents=True)
            for name in (MANIFEST, *FILES):
                (staging / name).write_bytes(self.download(f"{prefix}/{name}"))
            staging.rename(target)
            return target
        local = Path(version.source.removeprefix("file://"))
        if (local / MANIFEST).exists():
            return local
        raise RegistryError(
            f"{version.name} v{version.version}: unsupported source {version.source}"
        )

    # ---------------------------------------------------------------- publishing

    def publish(self, name: str, bundle_dir: Path, *, alias: str | None = None) -> str:
        """Upload a bundle, register it as a new version, and optionally point an alias at it.

        Moving `production` also records the outgoing version as `previous_production`, so a
        rollback is one alias move (D-50).
        """
        manifest = json.loads((bundle_dir / MANIFEST).read_text())
        prefix = f"bundles/{name}/{manifest['model_version']}"
        for file in (MANIFEST, *FILES):
            self.upload(f"{prefix}/{file}", (bundle_dir / file).read_bytes())
        self.ensure_model(name)
        version = self.create_version(
            name, f"{ARTIFACT_SCHEME}{prefix}", f"model_version={manifest['model_version']}"
        )
        if alias is not None:
            if alias == PRODUCTION:
                outgoing = self.by_alias(name, PRODUCTION)
                if outgoing is not None and outgoing.version != version:
                    self.set_alias(name, PREVIOUS, outgoing.version)
            self.set_alias(name, alias, version)
        return version


@dataclass
class WatcherMetrics:
    swaps: Counter
    failures: Counter

    @staticmethod
    def create(registry: CollectorRegistry | None = None) -> WatcherMetrics:
        kwargs: dict[str, Any] = {"registry": registry} if registry is not None else {}
        return WatcherMetrics(
            swaps=Counter("fs_model_swaps_total", "Hot swaps completed", ["alias"], **kwargs),
            failures=Counter(
                "fs_model_swap_failures_total",
                "Alias changes whose bundle could not be fetched or verified; the old model "
                "kept serving",
                ["alias"],
                **kwargs,
            ),
        )


class AliasWatcher:
    """Polls `@production` and `@shadow`, and swaps a verified bundle in on change."""

    def __init__(  # noqa: PLR0913 - two callbacks and their configuration
        self,
        registry: MlflowRegistry,
        name: str,
        cache: Path,
        on_production: Callable[[Bundle], None],
        on_shadow: Callable[[Bundle | None], None],
        *,
        poll_seconds: float = POLL_SECONDS,
        metrics: WatcherMetrics | None = None,
    ) -> None:
        if poll_seconds > MAX_POLL_SECONDS:
            raise ValueError("M5's gate requires alias changes to be seen within 10 s")
        self.registry = registry
        self.name = name
        self.cache = cache
        self._on = {PRODUCTION: on_production, SHADOW: on_shadow}
        self.poll_seconds = poll_seconds
        self.metrics = metrics or WatcherMetrics.create()
        self.loaded: dict[str, str | None] = {PRODUCTION: None, SHADOW: None}
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def poll_once(self) -> dict[str, str | None]:
        for alias in (PRODUCTION, SHADOW):
            try:
                version = self.registry.by_alias(self.name, alias)
            except RegistryError as error:
                LOG.warning("registry unreachable polling @%s: %s", alias, error)
                continue
            current = version.version if version else None
            if current == self.loaded[alias]:
                continue
            if version is None:
                if alias == SHADOW:
                    self._on[SHADOW](None)  # type: ignore[arg-type]
                    self.loaded[SHADOW] = None
                # A removed production alias keeps the loaded model: never serve nothing.
                continue
            try:
                bundle = Bundle.load(self.registry.fetch_bundle(version, self.cache))
            except (RegistryError, BundleError, OSError, ValueError) as error:
                self.metrics.failures.labels(alias).inc()
                LOG.error("@%s -> v%s not swapped in: %s", alias, current, error)
                continue
            self._on[alias](bundle)
            self.loaded[alias] = current
            self.metrics.swaps.labels(alias).inc()
            LOG.info("@%s now v%s (%s)", alias, current, bundle.model_version)
        return dict(self.loaded)

    def start(self) -> None:
        def loop() -> None:
            while not self._stop.is_set():
                self.poll_once()
                self._stop.wait(self.poll_seconds)

        self._thread = threading.Thread(target=loop, name="alias-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=self.poll_seconds + 1)


def wait_until(predicate: Callable[[], bool], timeout: float, step: float = 0.05) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(step)
    return predicate()
