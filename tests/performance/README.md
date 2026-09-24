# Load tests (M10)

**NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).** Nothing
here has been run. No throughput, latency, error rate or lag figure exists, and none may be quoted
from a laptop or a shared CI runner.

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
$ uv run --with 'locust==2.*' locust -f tests/performance --master --expect-workers <N> \
    --host https://<ingest-host> --tags sustained --headless \
    --fs-target-users <target rate / FS_PERF_USER_TPS> --fs-plateau-seconds <held seconds>
$ uv run --with 'locust==2.*' locust -f tests/performance --worker --master-host <master>
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

## Two safeguards worth knowing about

1. **The target must report synthetic data.** Before the first request the run reads
   `GET /api/v1/environment` and stops unless `synthetic_data` is true. Overriding it needs
   `FS_PERF_ALLOW_REAL_DATA=1`, which exists so that the override is a deliberate act with the
   owner's authorisation, not a default.
2. **Every response is checked against the contract.** A 200 that has stopped carrying its
   decision, or that returns HOLD without a review deadline, is counted as a failure. Load tests
   that count only status codes are how a system passes its throughput gate while returning
   nothing useful.

## What this harness cannot decide by itself

The "exactly one scored" half of the duplicate-storm requirement, Kafka consumer lag, and zero
data loss are read from the system, not from the client: the scoring counter, the consumer group's
lag, and a reconciliation of every decided `transaction_id` against `fs.transactions.scored` and
TimescaleDB. The procedure is in the campaign plan; this harness produces the traffic and the
client-side view of it.
