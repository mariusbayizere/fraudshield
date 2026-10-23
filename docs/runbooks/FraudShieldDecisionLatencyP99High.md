# FraudShieldDecisionLatencyP99High

**Meaning.** p99 of `fs_decision_latency_seconds` (server-side, whole synchronous path of C.2)
has been above 80 ms for 5 minutes. The target is p95 ≤ 40 ms for flagged and ≤ 30 ms for LOW;
80 ms is also the canary rollback threshold (SRS 8.1).

**Impact.** Core banking waits longer for every payment; callers with tight timeouts may apply
their own fallback, which FraudShield does not see.

## Diagnose

Open the **FraudShield / Decision path** dashboard and find the stage that grew:

1. `fs_scoring_latency_seconds{stage}`: scorer time (features, ensemble, Isolation Forest, SHAP).
   SHAP runs only on flagged transactions, so a jump in the flagged share raises p99.
2. `fs_feature_latency_seconds{source}`: Redis round-trip; `fs_redis_hit_ratio` falling means the
   DB fallback for velocity is in use (C.4 degraded mode).
3. `fs_db_pool_in_use`: pool saturation stalls the path when Redis is degraded.
4. CPU throttling on API or scorer pods (`kubectl -n fraudshield top pods`), and HPA at maximum.
5. During a rollout the canary analysis should already have aborted; check
   `kubectl argo rollouts -n fraudshield get rollout fraudshield-api`.

## Mitigate

- Throttled pods at HPA maximum: raise `maxReplicas` in the overlay.
- New model slower than its latency budget (D-16): roll back the model
  (`POST /api/v1/admin/models/rollback`).
- Redis degraded: see the Redis provider's status; decisions stay correct but slower.

## Escalate

Engineering lead if p99 stays above 80 ms for 30 minutes or reaches the callers' timeout.
