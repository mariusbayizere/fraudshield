# 0090 — The API spool lives on a shared volume, and orphaned spools are replayed

- **Status:** Accepted, conditional on the latency measurement in "Acceptance condition"
- **Decided by:** repository owner, 2026-09-22 (option (b) of `docs/parallel/M9_updates.md`,
  decision 1, with the acceptance condition, the ephemeral-storage prohibition and the chaos case
  below); the M9 agent proposed the options and wrote this record.
- **Date:** 2026-09-22
- **Requirements affected:** NFR-REL-02 (Kafka broker failure), OPS-CI-08
- **Defects referenced:** D-13, D-15

## Context

D-15: the decision path appends every decision to a bounded local disk spool (fsync batched every
5 ms) before Kafka acknowledges it, the spool is replayed in order on recovery, and "the chaos test
kills both Kafka and the API pod and proves zero loss via transaction_id reconciliation". C.2
step 10 gives "record decision in Redis + append to local spool" 2 ms at p95.

The M9 gate also requires the API to be released by an Argo Rollouts canary. Argo Rollouts manages
Deployment-style pods only; it cannot manage a StatefulSet, the usual way to give each pod its own
persistent volume. The first M9 manifests therefore put the spool on the pod's `emptyDir`, which
survives a container restart but is deleted with the pod. A pod deleted during a Kafka outage (node
drain, eviction, scale-down, canary abort) loses decisions that core banking has already received.
That contradicts D-15.

## Options considered

1. **(a) StatefulSet with a PVC per pod**, canary by `partition` rolling updates and the same
   Prometheus analysis run by the deploy workflow. Durable by construction; replaces Argo Rollouts
   for the API, so the canary tooling becomes FraudShield's own code.
2. **(b) Shared ReadWriteMany volume, one directory per pod, and a replayer.** Keeps Argo Rollouts.
   Any live API pod recovers the directories of pods that no longer exist. Depends on the shared
   volume's fsync latency fitting the hot-path budget.
3. **(c) Kafka acknowledgement inside the synchronous path when the spool is not durable.** Adds a
   Kafka round-trip to every decision and fails decisions during a Kafka outage, the case the spool
   exists for; contradicts D-13.
4. **(d) Keep `emptyDir` with a long termination grace period.** Loses decisions whenever a pod is
   deleted while Kafka is down.

## Decision

**Option (b)**, subject to the acceptance condition, with **option (a) as the named fallback**.

1. **Ephemeral storage is not acceptable for the spool.** `emptyDir`, generic ephemeral volumes or
   any other storage deleted with the pod lose decisions on pod deletion and must not back the
   spool. `infrastructure/checks/k8s_policy.py` fails when the API's spool mount is not a
   ReadWriteMany PersistentVolumeClaim.
2. **Layout and replay contract** (for M6): one directory per pod on the shared volume, a heartbeat
   per directory, atomic claiming of orphaned directories, in-order replay through the idempotent
   producer, deletion only after the claimed directory has stayed quiescent. The exact contract is
   in `docs/parallel/M9_updates.md` section 6 and becomes the spool module's README when M6
   implements it.
3. **Acceptance condition.** Spool append p95 on the shared volume (fsync batched every 5 ms, as
   D-15 specifies), measured on the target hardware and storage class in M10, must fit the 2 ms
   budget of C.2 step 10 together with the Redis write. The measurement is made in CI or on target
   hardware, never on the author's laptop (ADR 0010), and saved under `docs/benchmarks/`.
4. **Fallback.** If the condition is not met, switch to option (a): the API becomes a StatefulSet
   with a per-pod PVC, the canary becomes a partitioned rolling update driven by the deploy
   workflow with the same analysis queries and thresholds (`infrastructure/argo-rollouts/
   analysis-template.yaml`, 5xx > 0.5% or p99 > 80 ms), and this ADR is superseded.
5. **Chaos case for M10.** With traffic flowing: stop Kafka, let spools fill, delete an API pod
   (not only kill its container), restore Kafka, and prove zero loss by reconciling every
   `transaction_id` the load generator received a decision for against `fs.transactions.scored`
   and TimescaleDB. Any missing `transaction_id` fails the test.

## Consequences

- The cluster needs a ReadWriteMany StorageClass whose fsync latency is known. The class is
  cluster-specific, so the manifests leave `storageClassName` to the deployment; it is a listed
  prerequisite in `infrastructure/k8s/README.md`.
- M6 owns the spool writer and the replayer; the manifests only provide the volume and the pod name
  (`FRAUDSHIELD_SPOOL_INSTANCE`). Duplicates after a replay are expected and are harmless because
  the producer is idempotent and every event carries a stable `event_id` (ADR 0012).
- Until M10 measures the latency, D-15 is not verified: the spool rows stay open and threat-model
  risk R-5 stays open, re-worded to the latency condition.
- Verification: `k8s_policy.py` (no ephemeral spool; tests in `test_k8s_policy.py`), the M10
  latency benchmark, and the M10 chaos case above.
