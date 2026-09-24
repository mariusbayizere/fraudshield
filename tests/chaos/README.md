# Chaos manifests (M10)

**NOT YET EXECUTED — requires the integrated system and dedicated hardware (ADR 0010).** Nothing
here has been applied to a cluster. No result, no timing and no verdict exists for any case below.

One manifest per failure case in Part C.4 of the build specification, plus the spool case ADR 0090
assigns to M10. Each file states the case, the requirement whose acceptance condition it tests, the
steady-state signal to watch, what the operator must reconcile afterwards, and what would make the
case fail. Chaos Mesh is the tool E.12 names for Kubernetes; Toxiproxy covers the same cases
locally at test scale and is already used by M6's JUnit suites.

| File | C.4 case | Requirement | What must hold |
|---|---|---|---|
| `01-ml-scorer-down.yaml` | ML scorer down | NFR-REL-01 | Circuit opens inside the specified window, the rule fallback decides, responses carry the fallback flag, the backlog clears after recovery |
| `02-kafka-down-spool.yaml` | Kafka down → spool | NFR-REL-02, D-15, ADR 0090 | The spool absorbs the outage, replay is in order, reconciliation finds no missing `transaction_id` |
| `03-redis-down.yaml` | Redis down | NFR-REL-03 | Velocity features fall back to the database, idempotency falls back to the unique constraint, the degraded-mode metric and banner appear |
| `04-postgres-primary-loss.yaml` | PostgreSQL primary loss | NFR-REL-04 | The replica is promoted inside the specified window, writes queue rather than fail, no committed write is lost |
| `05-oauth-unavailable.yaml` | Google OAuth down | NFR-REL-06 | Email and password sign-in is unaffected; the Google button reports itself unavailable |
| `06-api-pod-loss-with-spool.yaml` | ADR 0090's M10 case | D-15, ADR 0090 | A deleted API pod's spool is replayed by another pod; every decided `transaction_id` is reconciled |

The duplicate-storm case (NFR-REL-05) is not a chaos experiment: it is load, and it lives in
`tests/performance` under the `replay` tag. Its "exactly one scored" half is read afterwards from
the scoring counters and `fs.transactions.scored`, as the campaign plan describes.

## Prerequisites, none of which exist yet

1. **A cluster with the M9 manifests applied.** They are on the `m9/infra` branch and have never
   been applied (`docs/parallel/M9_updates.md`). Workload names and labels used here come from
   that branch: Deployments `fraudshield-api`, `fraudshield-ml`, `fraudshield-ml-worker`, selected
   by `app.kubernetes.io/name`, in namespace `fraudshield`.
2. **Data-store manifests.** PostgreSQL/TimescaleDB, Redis and Kafka are not in the repository's
   manifests yet. M9 fixes their selector contract as `fraudshield.io/datastore: postgresql |
   redis | kafka | pii-vault | mlflow | object-store`, and these files select on it. Cases 02, 03
   and 04 cannot run until those manifests exist; that is a prerequisite on M9, not a gap here.
3. **Chaos Mesh installed in its own namespace.** The `fraudshield` namespace enforces Pod
   Security `restricted`, and its chaos daemon needs privileges that policy refuses. Install the
   controller in `chaos-mesh` and let it act on `fraudshield` through its own service account.
4. **A NetworkPolicy for the chaos controller.** The namespace carries `default-deny-all` in both
   directions, so an agent that cannot reach a pod will report a clean experiment that never ran.
   The campaign's first chaos step is a deliberate no-op experiment used to prove the controller
   can act at all.
5. **Traffic while the experiment runs.** Every case is meaningless at idle: the load generator in
   `tests/performance` supplies the steady state, and the acceptance conditions are read from the
   decisions and the metrics recorded during the injection window.

## How each case is run

The campaign procedure, the order of the cases and the evidence each produces are in
`docs/benchmarks/m10_plan.md`. In outline, for every case: start traffic, record the steady state,
apply the manifest, hold it for its stated duration, remove it, watch recovery, then reconcile.
Chaos Mesh's own `status.experiment` records when injection started and ended; the campaign reads
the acceptance conditions from the system's metrics and data, never from the chaos tool's report.
