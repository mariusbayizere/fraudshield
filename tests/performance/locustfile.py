"""Distributed Locust scenarios for the FraudShield ingest API.

**NO MEASUREMENT EXISTS — measurements require dedicated hardware (ADR 0010).** The only runs made
are the campaign's step 2 (`docs/benchmarks/m10_plan.md` §3): a short, low-rate run on a CI runner
against the Compose stack, and the refusals that prove the run can fail
(`.github/workflows/m10-harness.yml`). No number from those runs is evidence of any requirement.

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
import logging
import random
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any
from uuid import uuid4

import gevent
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
from locust.runners import MasterRunner, WorkerRunner

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


#: Process exit status when the preflight refuses the target: unreachable, not serving synthetic
#: data, or refusing the key. Distinct from Locust's 1 (requests failed) so that a run which never
#: sent load cannot be mistaken for one that sent load and saw errors.
EXIT_PREFLIGHT_REFUSED = 3

#: Process exit status when the API refuses the key during the run (401 or 403 on any request).
EXIT_KEY_REFUSED_IN_RUN = 4


def _call(
    url: str, request_headers: dict[str, str], data: bytes | None = None
) -> tuple[int, bytes]:
    """One GET (or POST with ``data``); the status and body, an HTTP error status returned."""
    request = urllib.request.Request(url, data=data, headers=request_headers)  # noqa: S310
    try:
        with urllib.request.urlopen(request, timeout=10) as response:  # noqa: S310
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


# One return per refusal reason, each with its own message: splitting them would hide the list.
def preflight_problem(host: str, settings: Settings) -> str | None:  # noqa: PLR0911
    """Why the run must not start against ``host``, or None if it may.

    Two requests, neither of which writes a transaction or a decision:

    1. The public `GET /api/v1/environment` must answer 200 with ``synthetic_data`` true (ADR
       0019's banner reads the same flag). A load test points thousands of requests per second at
       whatever it is given; this is the one check that keeps a mistyped host from becoming an
       incident. An endpoint that cannot be reached or read is a refusal, not a pass: a check that
       cannot run has not checked anything.
    2. `POST /api/v1/transactions/ingest` with the key and a body that is not JSON must answer 400.
       The API checks the key and its `ingest:write` scope before it reads the body, so a 401 or
       403 means every request of the run would be refused, and a 400 means the key may ingest;
       the malformed body is refused before anything is written. A run that measures only
       refusals measures nothing.
    """
    base = f"{host.rstrip('/')}{API_ROOT}"
    try:
        status, body = _call(f"{base}/environment", {"Accept": "application/json"})
    except (OSError, ValueError) as error:
        return f"{base}/environment could not be read ({error}); refusing to start"
    if status != 200:
        return f"{base}/environment answered HTTP {status}; refusing to start"
    try:
        payload = json.loads(body)
    except ValueError:
        return f"{base}/environment did not answer JSON; refusing to start"
    synthetic = payload.get("synthetic_data") if isinstance(payload, dict) else None
    if synthetic is not True and settings.require_synthetic:
        return (
            f"{base}/environment reports synthetic_data={synthetic!r}; refusing to load test a "
            "target that is not serving synthetic data (set FS_PERF_ALLOW_REAL_DATA=1 only with "
            "the owner's written authorisation)"
        )
    try:
        status, _ = _call(f"{base}/transactions/ingest", headers(settings), data=b"{")
    except (OSError, ValueError) as error:
        return f"the key check could not reach {base} ({error}); refusing to start"
    if status in (401, 403):
        return f"the API refused the key (HTTP {status} on the key check); refusing to start"
    if status != 400:
        return f"the key check expected HTTP 400 for a malformed body, got {status}; refusing"
    return None


@events.test_start.add_listener
def _preflight(environment: Environment, **_: Any) -> None:
    """Stop before the first request unless the preflight passes (see `preflight_problem`).

    Local (single-process) runs only report the refusal as the exit status. In a distributed run a
    worker that refuses quits alone, with the status on the worker; the master does not see it.
    """
    if isinstance(environment.runner, MasterRunner):
        return
    try:
        problem = preflight_problem(environment.host or "", Settings.from_env())
    except Exception as error:
        # Anything the checks did not anticipate is a refusal: the event hook would otherwise log
        # the exception and spawn users at a target nobody checked.
        problem = f"the preflight itself failed ({type(error).__name__}: {error})"
    if problem is None:
        logging.info("preflight passed: synthetic data, key accepted")
        return
    logging.error("PREFLIGHT REFUSED: %s", problem)
    environment.process_exit_code = EXIT_PREFLIGHT_REFUSED
    # From its own greenlet: quit() kills the runner's greenlets, and this listener runs in one.
    gevent.spawn(environment.runner.quit)


#: The runner, captured at init, so the request listener can stop it.
_RUNNER: dict[str, Any] = {}


@events.init.add_listener
def _capture_runner(environment: Environment, **_: Any) -> None:
    """Keep the runner where the request listener, which is not given it, can reach it."""
    _RUNNER["runner"] = environment.runner


@events.request.add_listener
def _stop_on_refused_key(response: Any = None, **kwargs: Any) -> None:
    """Stop the run the first time the API refuses the key.

    The preflight catches a wrong key; this catches one revoked during the run. Continuing would
    turn the rest of the run into a measurement of the refusal path. As with the preflight, the
    status and the stop are a local run's; in a distributed run only the worker that saw it stops.
    """
    status = getattr(response, "status_code", None)
    if status not in (401, 403):
        return
    runner = _RUNNER.get("runner")
    if runner is None or runner.environment.process_exit_code == EXIT_KEY_REFUSED_IN_RUN:
        return
    logging.error("KEY REFUSED IN RUN: HTTP %s on %s; stopping", status, kwargs.get("name"))
    runner.environment.process_exit_code = EXIT_KEY_REFUSED_IN_RUN
    gevent.spawn(runner.quit)


def json_object(response: Any) -> dict[str, Any] | None:
    """The response body as a JSON object, or None when it is empty, not JSON, or not an object.

    An earlier version read ``response.js``, which Locust's ``FastHttpUser`` responses do not have:
    every task raised after its first request.
    """
    try:
        payload = response.json()
    except ValueError:
        return None
    return payload if isinstance(payload, dict) else None


#: Problem members carried into a failure message. Not ``correlation_id``: it differs on every
#: response, and Locust groups failures by message, so it would make every failure its own row.
PROBLEM_MEMBERS = ("type", "title", "detail")


def refusal(response: Any, prefix: str = "", sent: dict[str, Any] | None = None) -> str:
    """A failure message that says why, not only that: the status, the problem's type, title and
    detail, and for a transaction the currency it was sent with.

    The API's own detail does not always carry the cause: its 503 says only "retry the same
    request", and the reason it decided (no FX rate for the currency, idempotency unavailable) is
    not sent or logged (PB-196). The request's currency is added so that failures group by it, and
    a refusal confined to some currencies shows as such in the run's failure table.
    """
    parts = [f"{prefix}HTTP {response.status_code}"]
    problem = json_object(response) or {}
    parts += [f"{member}={problem[member]}" for member in PROBLEM_MEMBERS if problem.get(member)]
    if sent is not None:
        parts.append(f"currency={sent.get('currency')}")
    return "; ".join(parts)


class IngestUser(FastHttpUser):
    """Base class: one API key, one account pool, one pacing rule."""

    abstract = True

    def on_start(self) -> None:
        self.settings = Settings.from_env()
        # Load generation, not cryptography: the draws pick synthetic accounts and amounts.
        self.rng = random.Random()  # noqa: S311
        self.pool = AccountPool(settings=self.settings, rng=self.rng)
        self.headers = headers(self.settings)
        self.pacing = constant_throughput(self.environment.parsed_options.fs_user_throughput)

    def wait_time(self) -> float:
        """Pace each user at ``--fs-user-throughput`` requests a second.

        Locust's pacing function takes the user as its argument, as a class attribute would be
        called. An earlier version assigned it to the instance, where it is called without one:
        every user raised on its first wait.
        """
        return float(self.pacing(self))

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
                response.failure(refusal(response, sent=body))
                return None
            payload = json_object(response)
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
                    response.failure(refusal(response, "replay returned ", body))
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
                response.failure(refusal(response, "duplicate returned ", self.storm_body))
            elif response.headers.get("Idempotent-Replayed") != "true":
                response.failure("duplicate lacked Idempotent-Replayed: true")
            elif json_object(response) != self.first_decision:
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
            problem = json_object(response) or {}
            if response.status_code != 409:
                response.failure(refusal(response, "expected 409, got ", mutated))
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
            payload = json_object(response) or {}
            if response.status_code != 202:
                response.failure(refusal(response, "expected 202, got "))
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
            payload = json_object(response) or {}
            state = payload.get("state")
            if response.status_code != 200:
                response.failure(refusal(response))
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
            payload = json_object(response) or {}
            if response.status_code != 200:
                response.failure(refusal(response))
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
            payload = json_object(response) or {}
            if response.status_code != expected:
                response.failure(refusal(response, f"expected HTTP {expected}, got "))
            elif "correlation_id" not in payload:
                response.failure("problem body carries no correlation_id")
            else:
                response.success()


class SustainedPlateauShape(LoadTestShape):
    """Ramp to the target user count, hold it for the specified plateau, then stop.

    Without ``--fs-target-users`` (or ``FS_PERF_TARGET_USERS``) it follows ``-u``, ``-r`` and
    ``-t`` instead, so that an exploratory run and step 2's harness proof can set them. Locust uses
    a shape class whenever the file defines one, and a ``tick`` that returns None stops the run:
    an earlier version returned None in that case, so every run without a target ended at once,
    sent nothing, and exited 0. The plateau is what the requirement asks for: a rate *sustained*,
    measured over the flat part only, with the ramp excluded when the campaign reports percentiles.
    """

    #: Tells Locust this shape reads -u, -r and -t, so it does not warn that they are ignored.
    use_common_options = True

    def tick(self) -> tuple[int, float] | None:
        parsed = self.runner.environment.parsed_options
        elapsed = self.get_run_time()
        target = getattr(parsed, "fs_target_users", 0)
        if not target:
            run_time = getattr(parsed, "run_time", None)
            if run_time and elapsed >= run_time:
                return None
            return parsed.num_users or 1, float(parsed.spawn_rate or 1)
        ramp = max(1, getattr(parsed, "fs_ramp_seconds", 120))
        plateau = getattr(parsed, "fs_plateau_seconds", 300)
        if elapsed < ramp:
            users = max(1, int(target * elapsed / ramp))
            return users, max(1.0, target / ramp)
        if elapsed < ramp + plateau:
            return target, max(1.0, target / ramp)
        return None


#: Process exit status when a run that was allowed to start sent no request at all.
EXIT_NOTHING_SENT = 5

#: Process exit status when a task raised: the harness itself broke, whatever the API answered.
#: Locust's own status for it is 1, the same as for failed requests; this one tells them apart.
EXIT_TASK_RAISED = 6


@events.quitting.add_listener
def _mark_unexecuted(environment: Environment, **_: Any) -> None:
    """Every run writes its own provenance next to the results, and a run that sent nothing fails.

    A throughput number without the machine, the commit and the scenario that produced it is not
    evidence (ADR 0010); the campaign's report reads this file rather than a pasted summary. A run
    with no request has no failures either, and Locust would exit 0; that is the failure that looks
    like success, so it exits with its own status unless a refusal already set one. Workers are
    exempt: their statistics go to the master, which judges the whole run.
    """
    stats = environment.stats.total
    # Read without creating: stats.get() would add an empty row to the run's report.
    accepted = environment.stats.entries.get(("POST /transactions/ingest", "POST"))
    if (
        stats.num_requests == 0
        and environment.process_exit_code is None
        and not isinstance(environment.runner, WorkerRunner)
    ):
        logging.error("NOTHING SENT: the run made no request; failing it")
        environment.process_exit_code = EXIT_NOTHING_SENT
    if environment.runner.exceptions and environment.process_exit_code is None:
        count = len(environment.runner.exceptions)
        logging.error("TASK RAISED: %d distinct exception(s) in tasks; failing the run", count)
        environment.process_exit_code = EXIT_TASK_RAISED
    run_id = uuid4()
    print(
        json.dumps(
            {
                "run_id": str(run_id),
                "host": environment.host,
                "requests": stats.num_requests,
                "failures": stats.num_failures,
                # Non-replay ingests the API accepted: what step 2 reconciles against Kafka.
                "accepted_new": accepted.num_requests - accepted.num_failures if accepted else 0,
                "note": "no gate number may be read from this run unless the machine is recorded "
                "in docs/benchmarks/hardware.md (ADR 0010)",
            }
        )
    )
