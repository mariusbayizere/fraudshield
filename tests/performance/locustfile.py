"""Distributed Locust scenarios for the FraudShield ingest API.

**NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).** No run,
no number and no report exists. The file is the harness the M10 campaign will run once the ingest
API is deployed and a benchmark machine is recorded in `docs/benchmarks/hardware.md`.

What each scenario exists to measure (requirement text in `docs/traceability/requirements.yaml`):

* ``sustained`` — NFR-PERF-01, NFR-PERF-10, TEST-09, FR-01-01: the specified sustained ingest rate
  held for the specified duration, with the error percentage and the decision contract checked on
  every response.
* ``replay`` — FR-01-03 and the duplicate-storm reliability row: the same ``transaction_id``
  resent unchanged must return the cached decision with ``Idempotent-Replayed: true`` and must not
  rescore.
* ``conflict`` — FR-01-03's other half: the same ``transaction_id`` with a changed body must be
  refused with 409 and the idempotency-conflict problem type.
* ``batch`` — FR-01-06: a full-size batch accepted with 202 and a job that reaches COMPLETED.
* ``hold`` — D-14: after a HOLD, the final decision is read from ``GET /decisions/{id}``.
* ``validation`` — NFR-PERF-10's denominator: deliberately invalid bodies, driven by the frozen
  vectors, whose declared status is a success for this harness and must not be counted as an
  error against the budget.

Select scenarios with ``--tags``; the sustained mix runs alone by default in the campaign, because
mixing the error paths into a throughput measurement changes what the number means.

Run (see README.md for the full campaign procedure)::

    locust -f tests/performance --master --expect-workers N \\
        --host https://<ingest-host> --tags sustained --headless
    locust -f tests/performance --worker --master-host <master>
"""

from __future__ import annotations

import json
import random
import urllib.request
from pathlib import Path
from typing import Any
from uuid import uuid4

from common import (
    API_ROOT,
    AccountPool,
    Settings,
    decision_problem,
    headers,
    transaction,
)
from locust import FastHttpUser, LoadTestShape, constant_throughput, events, tag, task
from locust.env import Environment
from locust.runners import MasterRunner

#: Vectors of invalid bodies with the status each must produce, frozen alongside the API contract.
VALIDATION_VECTORS = (
    Path(__file__).resolve().parents[2]
    / "contracts"
    / "validation"
    / "request-validation-vectors.json"
)

#: How many requests per second one simulated user issues. The campaign sets the user count from
#: this and the target rate (target / FS_PERF_USER_TPS), spread over the worker machines.
USER_THROUGHPUT = 10.0


@events.init_command_line_parser.add_listener
def _add_arguments(parser: Any) -> None:
    parser.add_argument(
        "--fs-user-throughput",
        type=float,
        default=USER_THROUGHPUT,
        env_var="FS_PERF_USER_TPS",
        help="requests per second per simulated user (user count = target rate / this)",
    )
    parser.add_argument(
        "--fs-plateau-seconds",
        type=int,
        default=300,
        env_var="FS_PERF_PLATEAU_SECONDS",
        help="seconds to hold the target rate once the ramp finishes",
    )
    parser.add_argument(
        "--fs-ramp-seconds",
        type=int,
        default=120,
        env_var="FS_PERF_RAMP_SECONDS",
        help="seconds to reach the target rate before the plateau begins",
    )
    parser.add_argument(
        "--fs-target-users",
        type=int,
        default=0,
        env_var="FS_PERF_TARGET_USERS",
        help="users at the plateau; 0 leaves the shape disabled and honours -u/-r",
    )


@events.test_start.add_listener
def _refuse_real_data(environment: Environment, **_: Any) -> None:
    """Stop before the first request if the target is not serving synthetic data.

    The public `GET /api/v1/environment` endpoint reports it (ADR 0019's banner reads the same
    flag). A load test points thousands of requests per second at whatever it is given; this is
    the one check that keeps a mistyped host from becoming an incident.
    """
    settings = Settings.from_env()
    if isinstance(environment.runner, MasterRunner) or not settings.require_synthetic:
        return
    url = f"{environment.host.rstrip('/')}{API_ROOT}/environment"
    request = urllib.request.Request(url, headers={"Accept": "application/json"})  # noqa: S310
    with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
        payload = json.load(response)
    if payload.get("synthetic_data") is not True:
        environment.runner.quit()
        raise RuntimeError(
            f"{url} reports synthetic_data={payload.get('synthetic_data')!r}; refusing to load "
            "test a target that is not serving synthetic data (set FS_PERF_ALLOW_REAL_DATA=1 "
            "only with the owner's written authorisation)"
        )


class IngestUser(FastHttpUser):
    """Base class: one API key, one account pool, one pacing rule."""

    abstract = True

    def on_start(self) -> None:
        self.settings = Settings.from_env()
        # Load generation, not cryptography: the draws pick synthetic accounts and amounts.
        self.rng = random.Random()  # noqa: S311
        self.pool = AccountPool(settings=self.settings, rng=self.rng)
        self.headers = headers(self.settings)
        self.wait_time = constant_throughput(  # type: ignore[method-assign]
            self.environment.parsed_options.fs_user_throughput
        )

    def ingest(self, body: dict[str, Any], *, name: str) -> dict[str, Any] | None:
        """POST one transaction and check the decision against the contract."""
        with self.client.post(
            f"{API_ROOT}/transactions/ingest",
            json=body,
            headers=self.headers,
            name=name,
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"HTTP {response.status_code}")
                return None
            payload = response.js if isinstance(response.js, dict) else None
            if payload is None:
                response.failure("response body is not a JSON object")
                return None
            problem = decision_problem(payload)
            if problem is not None:
                response.failure(f"decision contract: {problem}")
                return None
            response.success()
            return payload


@tag("sustained")
class SustainedIngestUser(IngestUser):
    """The throughput scenario: the specified channel mix at the specified rate.

    A small share of requests resends an accepted transaction unchanged, because a real integrator
    retries and the idempotent path must hold at load rather than only in a unit test.
    """

    weight = 10

    def on_start(self) -> None:
        super().on_start()
        self.accepted: list[dict[str, Any]] = []

    @task
    def ingest_transaction(self) -> None:
        replay = self.accepted and self.rng.random() < self.settings.replay_share
        if replay:
            body = self.rng.choice(self.accepted)
            with self.client.post(
                f"{API_ROOT}/transactions/ingest",
                json=body,
                headers=self.headers,
                name="POST /transactions/ingest (replay)",
                catch_response=True,
            ) as response:
                if response.status_code != 200:
                    response.failure(f"replay returned HTTP {response.status_code}")
                elif response.headers.get("Idempotent-Replayed") != "true":
                    response.failure("replay lacked Idempotent-Replayed: true")
                else:
                    response.success()
            return
        body = transaction(self.pool)
        if self.ingest(body, name="POST /transactions/ingest") is not None:
            self.accepted.append(body)
            # Bound the memory a worker holds at the specified rate: the replay share only needs a
            # recent window, and an unbounded list would grow for the whole plateau.
            if len(self.accepted) > 1_000:
                self.accepted.pop(0)


@tag("replay")
class DuplicateStormUser(IngestUser):
    """The duplicate storm: one transaction, resent again and again.

    The reliability requirement is that exactly one of them is scored and every response carries
    the same cached decision. This scenario cannot prove the "exactly one scored" half by itself:
    that is read afterwards from the scoring counter and `fs.transactions.scored`, and the
    procedure is in the campaign plan.
    """

    weight = 1

    def on_start(self) -> None:
        super().on_start()
        self.storm_body = transaction(self.pool)
        self.first_decision: dict[str, Any] | None = None

    @task
    def resend(self) -> None:
        if self.first_decision is None:
            self.first_decision = self.ingest(
                self.storm_body, name="POST /transactions/ingest (storm first)"
            )
            return
        with self.client.post(
            f"{API_ROOT}/transactions/ingest",
            json=self.storm_body,
            headers=self.headers,
            name="POST /transactions/ingest (storm duplicate)",
            catch_response=True,
        ) as response:
            if response.status_code != 200:
                response.failure(f"duplicate returned HTTP {response.status_code}")
            elif response.headers.get("Idempotent-Replayed") != "true":
                response.failure("duplicate lacked Idempotent-Replayed: true")
            elif response.js != self.first_decision:
                response.failure("duplicate returned a different decision from the first")
            else:
                response.success()


@tag("conflict")
class IdempotencyConflictUser(IngestUser):
    """The same id with a changed body must be refused, under load as well as at rest."""

    weight = 1

    @task
    def conflict(self) -> None:
        body = transaction(self.pool)
        if self.ingest(body, name="POST /transactions/ingest (conflict first)") is None:
            return
        mutated = dict(body, amount="999999.99")
        with self.client.post(
            f"{API_ROOT}/transactions/ingest",
            json=mutated,
            headers=self.headers,
            name="POST /transactions/ingest (conflict)",
            catch_response=True,
        ) as response:
            problem = response.js if isinstance(response.js, dict) else {}
            if response.status_code != 409:
                response.failure(f"expected 409, got HTTP {response.status_code}")
            elif problem.get("type") != "urn:fraudshield:problem:idempotency-conflict":
                got = problem.get("type")
                response.failure(f"expected the idempotency-conflict type, got {got!r}")
            else:
                response.success()


@tag("batch")
class BatchIngestUser(IngestUser):
    """A full-size batch, then the job read back until it completes (FR-01-06)."""

    weight = 1
    #: The contract's `maxItems` for a batch; one more is a 422 `item_count_out_of_range`.
    batch_size = 1_000

    @task
    def submit_batch(self) -> None:
        body = {"transactions": [transaction(self.pool) for _ in range(self.batch_size)]}
        with self.client.post(
            f"{API_ROOT}/transactions/ingest/batch",
            json=body,
            headers=self.headers,
            name="POST /transactions/ingest/batch",
            catch_response=True,
        ) as response:
            payload = response.js if isinstance(response.js, dict) else {}
            if response.status_code != 202:
                response.failure(f"expected 202, got HTTP {response.status_code}")
                return
            if payload.get("accepted") != self.batch_size:
                response.failure(f"accepted {payload.get('accepted')!r} of {self.batch_size}")
                return
            response.success()
            job_id = payload.get("job_id")
        if isinstance(job_id, str):
            self.poll_job(job_id)

    def poll_job(self, job_id: str) -> None:
        """Read the job once. The campaign asserts completion within the specified window from the
        job's own timestamps rather than from this client's wall clock."""
        with self.client.get(
            f"{API_ROOT}/jobs/{job_id}",
            headers=self.headers,
            name="GET /jobs/{job_id}",
            catch_response=True,
        ) as response:
            payload = response.js if isinstance(response.js, dict) else {}
            state = payload.get("state")
            if response.status_code != 200:
                response.failure(f"HTTP {response.status_code}")
            elif state not in {"QUEUED", "RUNNING", "COMPLETED"}:
                response.failure(f"job state {state!r}")
            elif payload.get("failed"):
                response.failure(f"{payload['failed']} item(s) failed in the job")
            else:
                response.success()


@tag("hold")
class HoldFollowUpUser(IngestUser):
    """After a HOLD, the final decision is read from the decisions endpoint (D-14)."""

    weight = 1

    @task
    def ingest_then_read(self) -> None:
        body = transaction(self.pool)
        decision = self.ingest(body, name="POST /transactions/ingest (hold follow-up)")
        if decision is None:
            return
        with self.client.get(
            f"{API_ROOT}/decisions/{body['transaction_id']}",
            headers=self.headers,
            name="GET /decisions/{transaction_id}",
            catch_response=True,
        ) as response:
            payload = response.js if isinstance(response.js, dict) else {}
            if response.status_code != 200:
                response.failure(f"HTTP {response.status_code}")
            elif payload.get("decision_sequence") == 1 and payload.get("decided_by") != "MODEL":
                response.failure(f"sequence 1 decided by {payload.get('decided_by')!r}")
            elif payload.get("transaction_id") != body["transaction_id"]:
                response.failure("decision is for another transaction")
            else:
                response.success()


@tag("validation")
class ValidationMixUser(IngestUser):
    """Invalid bodies from the frozen vectors, each expected to fail exactly as declared.

    A rejected request is a correct response, not an error: every vector counts as a success when
    the API returns the status the vector declares. Keeping this scenario out of the sustained tag
    is deliberate, so that the error percentage measured against the budget contains only
    unintended failures.
    """

    weight = 1

    def on_start(self) -> None:
        super().on_start()
        vectors = json.loads(VALIDATION_VECTORS.read_text())
        self.cases = [
            case
            for case in vectors["schemas"]["TransactionIngestRequest"]
            if case.get("schema_detectable", True)
        ]

    @task
    def send_invalid(self) -> None:
        case = self.rng.choice(self.cases)
        expected = case["expected_status"]
        with self.client.post(
            f"{API_ROOT}/transactions/ingest",
            json=case["body"],
            headers=self.headers,
            name=f"POST /transactions/ingest (invalid: {case['label']})",
            catch_response=True,
        ) as response:
            payload = response.js if isinstance(response.js, dict) else {}
            if response.status_code != expected:
                response.failure(f"expected HTTP {expected}, got {response.status_code}")
            elif "correlation_id" not in payload:
                response.failure("problem body carries no correlation_id")
            else:
                response.success()


class SustainedPlateauShape(LoadTestShape):
    """Ramp to the target user count, hold it for the specified plateau, then stop.

    Disabled unless ``--fs-target-users`` (or ``FS_PERF_TARGET_USERS``) is set, so that an
    exploratory run can still use ``-u``/``-r``. The plateau is what the requirement asks for: a
    rate *sustained*, measured over the flat part only, with the ramp excluded when the campaign
    reports percentiles.
    """

    def tick(self) -> tuple[int, float] | None:
        options = getattr(self.runner, "environment", None)
        parsed = getattr(options, "parsed_options", None) if options else None
        target = getattr(parsed, "fs_target_users", 0) if parsed else 0
        if not target:
            return None
        ramp = max(1, getattr(parsed, "fs_ramp_seconds", 120))
        plateau = getattr(parsed, "fs_plateau_seconds", 300)
        elapsed = self.get_run_time()
        if elapsed < ramp:
            users = max(1, int(target * elapsed / ramp))
            return users, max(1.0, target / ramp)
        if elapsed < ramp + plateau:
            return target, max(1.0, target / ramp)
        return None


@events.quitting.add_listener
def _mark_unexecuted(environment: Environment, **_: Any) -> None:
    """Every run writes its own provenance next to the results.

    A throughput number without the machine, the commit and the scenario that produced it is not
    evidence (ADR 0010); the campaign's report reads this file rather than a pasted summary.
    """
    stats = environment.stats.total
    run_id = uuid4()
    print(
        json.dumps(
            {
                "run_id": str(run_id),
                "host": environment.host,
                "requests": stats.num_requests,
                "failures": stats.num_failures,
                "note": "no gate number may be read from this run unless the machine is recorded "
                "in docs/benchmarks/hardware.md (ADR 0010)",
            }
        )
    )
