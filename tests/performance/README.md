# Load tests (M10)

**NO MEASUREMENT EXISTS — measurements require dedicated hardware (ADR 0010).** No throughput,
latency, error rate or lag figure exists, and none may be quoted from a laptop or a shared CI
runner. The only runs made are the campaign's step 2 (`docs/benchmarks/m10_plan.md` §3), described
below, whose numbers are evidence of nothing.

Distributed Locust scenarios for the ingest API, written against the frozen contract
(`contracts/openapi/fraudshield-api.yaml`, `info.version 1.0.0-m1`).

| File | Contents |
|---|---|
| `locustfile.py` | The scenarios, one user class per tag, and the plateau shape |
| `common.py` | Settings, credentials, the account pool, payload factories and the decision-contract check |

## Scenarios

| Tag | Scenario | Requirements it feeds |
|---|---|---|
| `sustained` | The specified channel mix at a held rate, with a small share of unchanged retries | NFR-PERF-01, NFR-PERF-10, TEST-09, FR-01-01 |
| `replay` | The duplicate storm: one transaction resent repeatedly | NFR-REL-05, FR-01-03 |
| `conflict` | The same `transaction_id` with a changed body | FR-01-03 |
| `batch` | A full-size batch and the job read back | FR-01-06, PB-74 |
| `hold` | A decision followed by the final-decision read | D-14 |
| `validation` | Invalid bodies from the frozen vectors, each expected to fail as declared | NFR-PERF-10's denominator |

The tags exist so that a throughput measurement contains only the traffic it claims to measure.
The campaign runs `sustained` alone for the ingest-rate rows; mixing the error paths in would make
the error percentage meaningless and the latency percentiles describe a different workload.

## Running it

Locust is not a dependency of this repository: nothing in the workspace imports it, and adding it
to the locked environment for a suite that cannot run yet would put a dependency in every
developer's environment for no benefit. The campaign installs it on the load machines:

```console
$ uv run --no-project --with 'locust==2.43.4' locust -f tests/performance --master --expect-workers <N> \
    --host https://<ingest-host> --tags sustained --headless \
    --fs-target-users <target rate / FS_PERF_USER_TPS> --fs-plateau-seconds <held seconds>
$ uv run --no-project --with 'locust==2.43.4' locust -f tests/performance --worker --master-host <master>
```

The user count follows from the rate: each simulated user issues `--fs-user-throughput` requests a
second, so the plateau needs `target rate / that value` users, spread across the workers. The
campaign plan (`docs/benchmarks/m10_plan.md`) states the worker count and the machine each runs on;
a load generator that saturates its own machine measures the generator, not the system.

## Credentials

`X-API-Key` only: the ingest operations are API-key authenticated, and a staff token carries no
ingest scope. The key comes from `FS_PERF_API_KEY`, or from `FRAUDSHIELD_DEMOSEED_APIKEY` as
`make seed-demo` exports it. No key is committed, and the harness refuses to start without one
rather than sending unauthenticated load.

## Safeguards, and the exit statuses that report them

Each refusal has its own exit status, so a run that never sent load cannot be mistaken for one that
sent load and saw errors (Locust's own status is 1 when requests failed):

| Exit | Meaning |
|---|---|
| 0 | The run sent requests and none failed |
| 1 | Requests failed (Locust's own status) |
| 3 | **Preflight refused** before the first request: `GET /api/v1/environment` unreachable, not 200, not JSON, or `synthetic_data` not true; the key check (`POST /api/v1/transactions/ingest` with a body that is not JSON) answered anything but 400, 401 and 403 included; or the preflight itself raised |
| 4 | **The API refused the key during the run** (401 or 403 on any request); the run stops at once |
| 5 | **Nothing was sent**: the run was allowed to start and made no request |
| 6 | **A task raised**: the harness itself broke, whatever the API answered (Locust's own status for this is 1) |

The API checks the key and its `ingest:write` scope before it reads the body, so the key check's
400 means this key may ingest, and no transaction or decision is written. **These statuses are a local
(single-process) run's.** In a distributed run, a worker whose preflight refuses or which sees a
401 quits alone and the status stays on that worker; the master ends with 5 or 1 and keeps the
other workers running (an inference from Locust's source, not observed). Making the master stop
the whole run is PB-197, and must be done before any distributed run.

**Failure messages say why.** A refused request is recorded with its status, the problem's
`type`, `title` and `detail`, and, for a transaction, the currency it was sent with; failures group
by that message, so a refusal confined to some currencies shows as such in the failure table. The
`correlation_id` is left out: it differs on every response and would make each failure its own row.
Successful new ingests (HTTP 200 with the decision contract held) are counted by currency too:
the provenance line printed at quit carries `accepted_new_by_currency` beside `accepted_new`, so
which currencies were accepted is read off the run, not inferred from the absence of failures. The
count is a local run's: in a distributed run the master's is empty and a worker's does not
correspond to its `accepted_new` (PB-197).

1. **The target must report synthetic data.** Overriding it needs `FS_PERF_ALLOW_REAL_DATA=1`,
   which exists so that the override is a deliberate act with the owner's authorisation, not a
   default. It skips only the synthetic-data check: an unreachable endpoint and a refused key still
   stop the run.
2. **Every response is checked against the contract.** A 200 that has stopped carrying its
   decision, or that returns HOLD without a review deadline, is counted as a failure. Load tests
   that count only status codes are how a system passes its throughput gate while returning
   nothing useful.

## Step 2: proving the harness can fail

`step2/prove-harness.sh`, run by `.github/workflows/m10-harness.yml` against the API in the Compose
stack, runs ten cases at a low rate in a local run: two that must pass (against the API, and
through the fault proxy with no fault), six refusals with exit 3 or 4, and two mutants, copies of
the harness with one of the defects below restored, that must exit 5 and 6. Each asserts its exact
exit status and what reached the API (the Kafka topic `fs.transactions.raw`, or the request count of
`step2/fault_proxy.py`, which injects the faults the correctly configured stack cannot produce and
forwards everything else to the real API). The two passing cases also try to name each record in
their window on the topic (PB-198): with `--fs-ids-out` the harness writes the `transaction_id`s it
sent, accepted and replayed, and `prove-harness.sh` reads the case's records by partition and offset
and `step2/reconcile.py` classes each as accepted, late (sent, not counted as accepted), a duplicate
of an earlier record, or foreign. A short read of a partition is reported as such. Records landing
after the case's settle window fall into the next case's. The classes are reported in the summary
and do not yet change a case's verdict. Step 2 found three defects, all fixed, in a harness that had never run: the plateau shape stopped every run without
`--fs-target-users` at once, with no request and exit status 0 (observed with Locust 2.43.4);
`response.js` does not exist on Locust's responses; and the pacing function was assigned to the
instance, so each user raised on its first wait. Locust records a task's exception and exits 1, so a run with
`--fs-target-users` would have failed, after at most one request per simulated user, with the same
status as one whose requests failed (an inference from Locust's source, not observed).

## What this harness cannot decide by itself

The "exactly one scored" half of the duplicate-storm requirement, Kafka consumer lag, and zero
data loss are read from the system, not from the client: the scoring counter, the consumer group's
lag, and a reconciliation of every decided `transaction_id` against `fs.transactions.scored` and
TimescaleDB. The procedure is in the campaign plan; this harness produces the traffic and the
client-side view of it.
