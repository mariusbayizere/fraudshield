# FraudShieldSpoolBacklog

**Meaning.** An API pod's local disk spool (`fs_spool_depth`) has not drained to zero for
5 minutes. The spool holds decisions already returned to core banking but not yet acknowledged by
Kafka (`acks=all`); it is replayed in order when Kafka accepts writes again (D-15).

**Impact.** Decisions made during the backlog are not yet in TimescaleDB, alert feeds, audit or
webhooks. Each pod's spool is its own directory on the shared volume. **Nothing replays a dead
pod's directory** (stop-gap of 2026-10-20; ADR 0090's replayer was never built, the real fix is
PB-171): if the pod is deleted, its unsent records stay on the volume until an operator drains
them, and no metric shows them, because `fs_spool_depth` covers live pods only.

## Diagnose

1. `kubectl -n fraudshield exec <api pod> -- wget -qO- localhost:8081/actuator/health/kafka`:
   can this pod publish?
2. All pods backed up: Kafka is down or rejecting writes (disk full, ISR below
   `min.insync.replicas`). One pod: its network path or credentials.
3. Spool depth trend on **FraudShield / Kafka and spool**: growing towards the bound means new
   decisions will soon be refused by that pod.

## Mitigate

- Restore Kafka first; each **live** pod drains its own spool once Kafka accepts writes.
- Check the shared volume: `kubectl -n fraudshield get pvc fraudshield-api-spool` and its usage.
  A full volume refuses new spool writes on every API pod.
- **Do not delete, evict or drain API pods while a spool is non-zero**, and pause any rollout
  while Kafka is down (`kubectl argo rollouts -n fraudshield pause fraudshield-api`): a deleted
  pod's spool is stranded, not replayed. The autoscaler deletes pods too: while any spool is
  non-zero, stop the HPA scaling down (`kubectl -n fraudshield patch hpa fraudshield-api --type
  merge -p '{"spec":{"behavior":{"scaleDown":{"selectPolicy":"Disabled"}}}}'`) and revert the patch
  once every spool is empty.
- A pod that was deleted anyway: its directory under `/var/lib/fraudshield/spool/<pod name>/` on
  `fraudshield-api-spool` still holds its records. Escalate; there is no packaged drain command
  yet (PB-171).
- A spool near its bound: scale up the API only if the shared volume has room.

## Escalate

Engineering lead immediately if the shared spool volume is unavailable, degraded or near full, or
if an API pod was deleted with a non-zero spool: until PB-171, those are the cases in which
decisions are stranded or lost.
