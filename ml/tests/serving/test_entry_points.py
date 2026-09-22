"""The entry points: worker start-up in process, the CLIs, and the benchmark's dataset path."""

from __future__ import annotations

import json
import socket
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import grpc  # type: ignore[import-untyped]
import pytest

from fraudshield_ml import cli as pipeline
from fraudshield_ml.models import build as builder
from fraudshield_ml.models import cli as model_cli
from fraudshield_ml.models.bundle import Bundle
from fraudshield_ml.serving import benchmark, server
from fraudshield_ml.serving import cli as scorer_cli
from fraudshield_ml.serving.contexts import StaticContexts
from fraudshield_ml.serving.generated import regenerate
from fraudshield_ml.serving.generated import scoring_pb2 as pb
from fraudshield_ml.training import smoke

PACKS = {
    "RW": {
        "alpha2": "RW",
        "blocs": ["EAC"],
        "continent": "AF",
        "currency": "RWF",
        "currency_minor_units": 0,
        "round_denominations": [500, 1000],
        "utc_offset_hours": 2,
    },
    "KE": {
        "alpha2": "KE",
        "blocs": ["EAC"],
        "continent": "AF",
        "currency": "KES",
        "currency_minor_units": 2,
        "round_denominations": [5000],
        "utc_offset_hours": 3,
    },
}


@pytest.fixture
def packs(tmp_path: Path) -> Path:
    path = tmp_path / "packs.json"
    path.write_text(json.dumps(PACKS))
    return path


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


@pytest.mark.req("FR-02-08", "FR-02-10")
def test_a_worker_starts_serves_publishes_its_status_and_stops(
    bundle_dirs: tuple[Path, Path], packs: Path, tmp_path: Path, kit: SimpleNamespace
) -> None:
    request = kit.request(1)
    contexts = StaticContexts.write(
        tmp_path / "contexts.jsonl", [(request.transaction.transaction_id, kit.read())]
    )
    config = server.WorkerConfig(
        address="127.0.0.1:0",
        packs=packs,
        tls=None,
        bundle=bundle_dirs[0],
        status_dir=tmp_path / "status",
        shadow_log=tmp_path / "shadow.jsonl",
        reuse_port=False,
        static_contexts=contexts,
    )
    worker = server.start_worker(config)
    try:
        with grpc.insecure_channel(f"127.0.0.1:{worker.port}") as channel:
            score = channel.unary_unary(
                f"/{server.SERVICE}/Score",
                request_serializer=pb.ScoreRequest.SerializeToString,
                response_deserializer=pb.ScoreResponse.FromString,
            )
            reply = score(request, timeout=10)
        (status,) = [json.loads(p.read_text()) for p in (tmp_path / "status").glob("[0-9]*.json")]
        assert status["production_model_version"] == reply.result.model_version
        assert worker.shadow is not None, "a shadow log configures the shadow runner"
    finally:
        worker.stop(grace=0)


def test_the_scorer_cli_refuses_plaintext_unless_asked(packs: Path) -> None:
    store = ["--feature-store", "redis://localhost:6379/0"]
    with pytest.raises(SystemExit):
        scorer_cli.parse(["serve", "--bundle", "b", "--packs", str(packs), *store])
    with pytest.raises(SystemExit):  # ADR 0033: no store, no context to score with
        scorer_cli.parse(["serve", "--bundle", "b", "--packs", str(packs), "--insecure"])
    args = scorer_cli.parse(["serve", "--bundle", "b", "--packs", str(packs), "--insecure", *store])
    assert args.insecure
    assert args.feature_store == store[1]


def test_the_supervisor_exits_when_a_worker_dies(
    packs: Path, monkeypatch: pytest.MonkeyPatch, bundle_dirs: tuple[Path, Path]
) -> None:
    class Dead:
        name, pid, exitcode = "scorer-0", None, 1

        def is_alive(self) -> bool:
            return False

        def terminate(self) -> None:
            return

        def join(self, timeout: float | None = None) -> None:
            return

    started: list[Any] = []

    def serve(config: server.WorkerConfig, workers: int) -> list[Dead]:
        started.append(config)
        return [Dead()]

    monkeypatch.setattr(server, "serve", serve)
    code = scorer_cli.main(
        [
            "serve",
            "--bundle",
            str(bundle_dirs[0]),
            "--packs",
            str(packs),
            "--insecure",
            "--feature-store",
            "redis://localhost:6379/0",
            "--workers",
            "1",
            "--port",
            str(_free_port()),
            "--admin-port",
            str(_free_port()),
        ]
    )
    assert code == 1, "a dead worker stops the pod so the orchestrator restarts it"
    assert started[0].tls is None


def test_regeneration_is_idempotent(capsys: pytest.CaptureFixture[str]) -> None:
    before = {n: (Path(regenerate.__file__).parent / n).read_text() for n in regenerate.OUTPUTS}
    assert regenerate.main() == 0
    after = {n: (Path(regenerate.__file__).parent / n).read_text() for n in regenerate.OUTPUTS}
    assert before == after
    assert "regenerated" in capsys.readouterr().out


@pytest.mark.req("FR-02-03", "D-05")
def test_fs_model_build_writes_a_loadable_bundle_from_a_cache(
    tmp_path: Path, kit: SimpleNamespace, capsys: pytest.CaptureFixture[str]
) -> None:
    vectors, extras = kit.cache(1500, 4)
    cache = tmp_path / "features.parquet"
    smoke.cache_write(cache, {"dataset": "synthetic"}, vectors, extras)
    out = tmp_path / "bundle"
    assert model_cli.main(["build", "--cache", str(cache), "--out", str(out), "--seed", "4"]) == 0
    bundle = Bundle.load(out)
    assert bundle.provenance["cache_key"]["dataset"] == "synthetic"
    assert bundle.provenance["trained_by"].endswith("(M4)")
    assert "single-feature floor" in capsys.readouterr().out
    with pytest.raises(ValueError, match="no usable feature matrix"):
        builder.build_from_cache(tmp_path / "absent.parquet", out, seed=1, root=tmp_path)


def test_the_benchmark_builds_requests_from_a_dataset_replay(
    packs: Path, monkeypatch: pytest.MonkeyPatch, kit: SimpleNamespace
) -> None:
    rows = [kit.transaction(i) for i in range(30)]
    monkeypatch.setattr(pipeline, "read_transactions", lambda root, packs_path, limit: rows)
    requests = benchmark.dataset_requests(Path("unused"), packs, 30, 10)
    assert len(requests) == 10
    request, read = requests[-1]
    assert request.transaction.transaction_id == rows[-1].transaction_id
    assert read.context.tx_count_24h == 29, "each context holds every earlier row"
