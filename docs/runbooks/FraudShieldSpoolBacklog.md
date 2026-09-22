# FraudShieldSpoolBacklog

**Meaning.** An API pod's local disk spool (`fs_spool_depth`) has not drained to zero for
5 minutes. The spool holds decisions already returned to core banking but not yet acknowledged by
Kafka (`acks=all`); it is replayed in order when Kafka accepts writes again (D-15).

**Impact.** Decisions made during the backlog are not yet in TimescaleDB, alert feeds, audit or
webhooks. They are not lost while the pod lives. **They are lost if that pod is deleted before the
spool drains** (the spool is on the pod's volume; see `docs/parallel/M9_updates.md`, open
decision on spool durability).

## Diagnose

1. `kubectl -n fraudshield exec <api pod> -- wget -qO- localhost:8081/actuator/health/kafka`:
   can this pod publish?
2. All pods backed up: Kafka is down or rejecting writes (disk full, ISR below
   `min.insync.replicas`). One pod: its network path or credentials.
3. Spool depth trend on **FraudShield / Kafka and spool**: growing towards the bound means new
   decisions will soon be refused by that pod.

## Mitigate

- Restore Kafka first; the spool replays itself.
- **Do not delete, evict or drain nodes hosting API pods with a non-zero spool.** Pause any rollout
  (`kubectl argo rollouts -n fraudshield pause fraudshield-api`) and cordon rather than drain.
- A pod near its spool bound: scale up the API so new traffic lands on pods with free spool.

## Escalate

Engineering lead immediately if any pod with a non-zero spool is at risk of termination.
