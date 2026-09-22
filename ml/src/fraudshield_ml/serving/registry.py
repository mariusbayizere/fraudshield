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
from fraudshield_ml.serving import shadow
from fraudshield_ml.serving.shadow import GateDecision

PRODUCTION = "production"
SHADOW = "shadow"
PREVIOUS = "previous_production"
POLL_SECONDS = 5.0
MAX_POLL_SECONDS = 10.0
ARTIFACT_SCHEME = "mlflow-artifacts:/"
LOG = logging.getLogger(__name__)


class RegistryError(RuntimeError):
    """The registry answered with an error, or not at all."""


class PromotionRefused(RegistryError):  # noqa: N818 - a refusal, named as one
    """A bundle that may not become production (FR-02-03's calibration block, PB-64)."""


#: FR-02-03: "deployment blocked if ECE > 0.05".
MAX_ECE = 0.05


def _d11_recheck(gate: GateDecision) -> list[str]:
    """D-11's clauses re-derived from the decision's own numbers.

    The in-house `shadow.promotion_gate` always names a reason when it refuses, but the design
    hands this file a *report*, which an external comparator writes. A report is not trusted to
    have applied D-11 correctly: its `promote` flag is checked against the figures beside it.
    The 24-hour window is the one clause a `GateDecision` carries no field for, so it can only be
    checked by the comparator that produced it.
    """
    problems: list[str] = []
    if gate.scored < shadow.MIN_SCORED:
        problems.append(f"{gate.scored} shadow scores, need {shadow.MIN_SCORED} (D-11)")
    if gate.label_coverage < shadow.MIN_LABEL_COVERAGE:
        problems.append(f"label coverage {gate.label_coverage:.1%} is under 30% (D-11)")
    elif gate.auc_delta is None:
        problems.append("the gate report carries no AUC delta (D-11)")
    if gate.auc_delta is not None and gate.auc_delta < shadow.MIN_AUC_DELTA:
        problems.append(f"AUC delta {gate.auc_delta:+.4f} is below {shadow.MIN_AUC_DELTA} (D-11)")
    if gate.psi is not None and gate.psi >= shadow.MAX_PSI:
        problems.append(f"score PSI {gate.psi:.3f} is not below {shadow.MAX_PSI} (D-11)")
    return problems


def _gate_problems(gate: GateDecision | None, override: str | None) -> list[str]:
    """D-11's shadow gate as a promotion condition: a decision, or a recorded override.

    A rollback does not come here: `promote` exempts the version `previous_production` holds,
    which has already served (D-50).
    """
    if gate is None:
        if override is None:
            return [
                "no shadow-gate decision (D-11): pass the comparator's report, or an override "
                "reason for a first deployment. A rollback to the version "
                "`previous_production` holds needs neither"
            ]
        return []
    problems = list(gate.reasons) or _d11_recheck(gate)
    if not problems and not gate.promote:
        problems.append("the shadow comparator refused promotion and named no reason (D-11)")
    return problems


def promotion_problems(manifest: dict[str, Any]) -> list[str]:
    """Why a bundle may not be production, from its own manifest; empty when it may.

    The ECE is the held-out figure the bundle was built with (`fs-model build`). A bundle that does
    not record one is refused too: an unmeasured calibration is not a passing one.
    """
    held_out = manifest.get("provenance", {}).get("held_out", {})
    ece = held_out.get("ece_equal_width_10")
    if ece is None:
        return ["the bundle records no held-out ECE, so FR-02-03's block cannot be checked"]
    if float(ece) > MAX_ECE:
        return [f"held-out ECE {float(ece):.4f} exceeds {MAX_ECE} (FR-02-03)"]
    return []


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

    def promote(  # noqa: PLR0913 - a promotion's conditions, each named
        self,
        name: str,
        alias: str,
        version: str,
        cache: Path,
        *,
        gate: GateDecision | None = None,
        override: str | None = None,
    ) -> None:
        """Move an alias. Becoming production means passing FR-02-03's calibration block and
        D-11's shadow gate, and leaving `previous_production` behind for a one-move rollback.

        `gate` is the decision a shadow comparator produced from `fs.ml.shadow` (M6/M9 deploys it;
        `shadow.promotion_gate` computes it). `override` is the owner promoting without one, for a
        first deployment that has no shadow window to show; it is written to the version's tags,
        so a promotion that skipped the gate can be found afterwards.

        **A rollback is one alias move (D-50).** Moving `production` back to the version
        `previous_production` holds needs no shadow decision: that model has already served. The
        calibration block still applies — a bundle that cannot be loaded and checked is never
        pointed at by `production`, whichever direction it is moving.
        """
        if alias != PRODUCTION:
            self.set_alias(name, alias, version)
            return
        bundle = self.fetch_bundle(ModelVersion(name, version, self.source(name, version)), cache)
        problems = promotion_problems(json.loads((bundle / MANIFEST).read_text()))
        previous = self.by_alias(name, PREVIOUS)
        rollback = previous is not None and previous.version == version
        if not rollback:
            problems.extend(_gate_problems(gate, override))
        if problems:
            raise PromotionRefused("; ".join(problems))
        outgoing = self.by_alias(name, PRODUCTION)
        if outgoing is not None and outgoing.version != version:
            self.set_alias(name, PREVIOUS, outgoing.version)
        self._record_promotion(name, version, gate=gate, override=override, rollback=rollback)
        self.set_alias(name, alias, version)

    def _record_promotion(
        self,
        name: str,
        version: str,
        *,
        gate: GateDecision | None,
        override: str | None,
        rollback: bool = False,
    ) -> None:
        """Write how this version came to be production onto the version itself.

        `fs-model alias … --override` advertises the reason as recorded; this is where it is
        recorded. Tagging is best effort: a tracking server that refuses the tag must not leave
        `production` pointing at nothing, so the failure is logged and the move goes ahead.
        """
        tags = {"fraudshield.promoted_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
        if rollback:
            tags["fraudshield.promotion_gate"] = "rollback to previous_production (D-50)"
        elif gate is not None:
            tags["fraudshield.promotion_gate"] = (
                f"D-11 passed: {gate.scored} scores, coverage {gate.label_coverage:.1%}, "
                f"auc_delta {gate.auc_delta}, psi {gate.psi}"
            )
        if override is not None:
            tags["fraudshield.promotion_override"] = override
        for key, value in tags.items():
            try:
                self.set_version_tag(name, version, key, value)
            except RegistryError:
                LOG.warning("could not tag %s version %s with %s", name, version, key)

    def set_version_tag(self, name: str, version: str, key: str, value: str) -> None:
        self._json(
            "POST",
            "/api/2.0/mlflow/model-versions/set-tag",
            body={"name": name, "version": version, "key": key, "value": value},
        )

    def source(self, name: str, version: str) -> str:
        reply = self._json(
            "GET", "/api/2.0/mlflow/model-versions/get", query={"name": name, "version": version}
        )
        return str(reply["model_version"]["source"])

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
            # Per-process staging: every worker of a scorer shares the cache and sees the alias
            # move at about the same moment, so a shared staging path would be written by several
            # processes at once. Whoever renames first wins; the others use its copy.
            staging = target.with_name(f".{version.version}.{os.getpid()}.partial")
            shutil.rmtree(staging, ignore_errors=True)
            staging.mkdir(parents=True)
            for name in (MANIFEST, *FILES):
                (staging / name).write_bytes(self.download(f"{prefix}/{name}"))
            try:
                staging.rename(target)
            except OSError:
                if not (target / MANIFEST).exists():
                    raise
                shutil.rmtree(staging, ignore_errors=True)
            return target
        local = Path(version.source.removeprefix("file://"))
        if (local / MANIFEST).exists():
            return local
        raise RegistryError(
            f"{version.name} v{version.version}: unsupported source {version.source}"
        )

    # ---------------------------------------------------------------- tracking

    def experiment(self, name: str) -> str:
        """The id of the named experiment, created if absent."""
        try:
            reply = self._json(
                "GET", "/api/2.0/mlflow/experiments/get-by-name", query={"experiment_name": name}
            )
            return str(reply["experiment"]["experiment_id"])
        except RegistryError as error:
            if "RESOURCE_DOES_NOT_EXIST" not in str(error) and "HTTP 404" not in str(error):
                raise
        reply = self._json("POST", "/api/2.0/mlflow/experiments/create", body={"name": name})
        return str(reply["experiment_id"])

    def start_run(self, experiment_id: str, name: str, tags: dict[str, str]) -> str:
        reply = self._json(
            "POST",
            "/api/2.0/mlflow/runs/create",
            body={
                "experiment_id": experiment_id,
                "run_name": name,
                "start_time": int(time.time() * 1000),
                "tags": [{"key": k, "value": v} for k, v in sorted(tags.items())],
            },
        )
        return str(reply["run"]["info"]["run_id"])

    def log_metrics(self, run_id: str, metrics: dict[str, float], step: int) -> None:
        now = int(time.time() * 1000)
        self._json(
            "POST",
            "/api/2.0/mlflow/runs/log-batch",
            body={
                "run_id": run_id,
                "metrics": [
                    {"key": k, "value": v, "timestamp": now, "step": step}
                    for k, v in sorted(metrics.items())
                ],
            },
        )

    # ---------------------------------------------------------------- publishing

    def publish(
        self,
        name: str,
        bundle_dir: Path,
        *,
        alias: str | None = None,
        gate: GateDecision | None = None,
        override: str | None = None,
    ) -> str:
        """Upload a bundle, register it as a new version, and optionally point an alias at it.

        Moving `production` also records the outgoing version as `previous_production`, so a
        rollback is one alias move (D-50).
        """
        manifest = json.loads((bundle_dir / MANIFEST).read_text())
        if alias == PRODUCTION:
            problems = promotion_problems(manifest)
            problems.extend(_gate_problems(gate, override))
            if problems:
                raise PromotionRefused("; ".join(problems))
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
                self._record_promotion(name, version, gate=gate, override=override)
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
                directory = self.registry.fetch_bundle(version, self.cache)
                if alias == PRODUCTION and (
                    problems := promotion_problems(json.loads((directory / MANIFEST).read_text()))
                ):
                    raise PromotionRefused("; ".join(problems))
                bundle = Bundle.load(directory)
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
