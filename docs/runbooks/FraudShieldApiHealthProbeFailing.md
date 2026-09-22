# FraudShieldApiHealthProbeFailing (P1)

**Meaning.** The blackbox probe of `/actuator/health` on the API management port failed twice in a
row, 30 s apart (SRS 8.2 uptime). That endpoint reports process liveness only (ADR 0014 section 6),
so the API process is down, hung, or unreachable from inside the cluster.

**Impact.** Core banking cannot get decisions. Its own timeout policy decides what happens to
payments; FraudShield does not see them. Holds already issued still resolve through the deadline
scheduler only if some API pod is alive.

## Diagnose

1. `kubectl -n fraudshield get pods -l app.kubernetes.io/name=fraudshield-api -o wide`: are pods
   `Running` and `Ready`? Note restarts and the node of each pod.
2. `kubectl -n fraudshield describe pod <pod>`: look for `OOMKilled`, failed liveness or readiness
   probes, image pull errors, or `Evicted`.
3. `kubectl -n fraudshield logs <pod> --previous --tail=200` for the last crash.
4. During a deploy: `kubectl argo rollouts -n fraudshield get rollout fraudshield-api`. A canary
   that fails analysis is rolled back automatically; a stable version that fails is not.
5. If pods are Ready but the probe fails, check the blackbox exporter itself
   (`kubectl -n fraudshield logs deploy/fraudshield-blackbox-exporter`) and NetworkPolicy changes.

## Mitigate

- Bad release: `kubectl argo rollouts -n fraudshield abort fraudshield-api`, then
  `kubectl argo rollouts -n fraudshield undo fraudshield-api`.
- Out of memory: raise the memory limit in the overlay and redeploy; do not disable the limit.
- Node loss: the PodDisruptionBudget keeps one pod; confirm the scheduler has capacity.

## Escalate

Engineering lead after 15 minutes without a Ready pod. Tell the institution's operations contact
that decisions are unavailable, with the start time, so core banking applies its fallback policy.
