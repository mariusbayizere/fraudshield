# FraudShieldSpoolBacklog

**Meaning.** An API pod's local disk spool (`fs_spool_depth`) has not drained to zero for
5 minutes. The spool holds decisions already returned to core banking but not yet acknowledged by
Kafka (`acks=all`); it is replayed in order when Kafka accepts writes again (D-15).

**Impact.** Decisions made during the backlog are not yet in TimescaleDB, alert feeds, audit or
webhooks. The spool is on the shared volume (ADR 0090): if the pod dies, another API pod claims its
directory and replays it once Kafka accepts writes. Nothing is lost unless the shared volume itself
is lost or full.

## Diagnose

1. `kubectl -n fraudshield exec <api pod> -- wget -qO- localhost:8081/actuator/health/kafka`:
   can this pod publish?
2. All pods backed up: Kafka is down or rejecting writes (disk full, ISR below
   `min.insync.replicas`). One pod: its network path or credentials.
3. Spool depth trend on **FraudShield / Kafka and spool**: growing towards the bound means new
   decisions will soon be refused by that pod.

## Mitigate

- Restore Kafka first; the spools replay themselves.
- Check the shared volume: `kubectl -n fraudshield get pvc fraudshield-api-spool` and its usage.
  A full volume refuses new spool writes on every API pod.
- Pause any rollout while Kafka is down (`kubectl argo rollouts -n fraudshield pause
  fraudshield-api`): replay is safe after pod deletion, but there is no reason to add churn.
- A spool near its bound: scale up the API only if the shared volume has room.

## Escalate

Engineering lead immediately if the shared spool volume is unavailable, degraded or near full:
that is the one case in which decisions can be lost.
