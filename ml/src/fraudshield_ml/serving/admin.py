"""The admin port (M5): liveness, readiness, loaded model versions and Prometheus metrics.

Served by the supervising parent process with FastAPI, never on the scoring path. Workers publish
their loaded versions to a status directory on every swap; the admin port reports each live
worker's, so a blue-green switch in progress is visible as two versions at once.

Shadow mode is switched on and off by moving the `@shadow` alias in the registry (D-50) — the admin
panel's action (FR-02-08) goes through the API to the registry, and every worker picks it up within
its poll interval. Nothing here changes a model.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, generate_latest


def worker_status(directory: Path, alive: set[int]) -> list[dict[str, Any]]:
    statuses = []
    for path in sorted(directory.glob("[0-9]*.json")):
        status = json.loads(path.read_text())
        if status["pid"] in alive:
            statuses.append(status)
    return statuses


def create_app(
    status_dir: Path, alive: Callable[[], set[int]], registry: CollectorRegistry
) -> FastAPI:
    app = FastAPI(title="FraudShield scorer admin", docs_url=None, redoc_url=None)

    @app.get("/health/live")
    def live() -> dict[str, Any]:
        return {"status": "UP", "workers": len(alive())}

    @app.get("/health/ready")
    def ready() -> Response:
        statuses = worker_status(status_dir, alive())
        serving = [s for s in statuses if s["production_model_version"]]
        body = {"status": "UP" if serving else "DOWN", "serving_workers": len(serving)}
        return JSONResponse(body, status_code=200 if serving else 503)

    @app.get("/models")
    def models() -> dict[str, Any]:
        statuses = worker_status(status_dir, alive())
        versions = sorted({s["production_model_version"] for s in statuses} - {None})
        return {
            "workers": statuses,
            "production_versions": versions,
            "switch_in_progress": len(versions) > 1,
        }

    @app.get("/metrics")
    def metrics() -> Response:
        return Response(generate_latest(registry), media_type=CONTENT_TYPE_LATEST)

    return app
