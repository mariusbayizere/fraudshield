# FraudShieldMlFallbackActive

**Meaning.** For 2 minutes some decisions came from the rule-based fallback engine
(`fs_decisions_total{fallback="true"}`), because the API's circuit breaker to the scorer is open
(C.4). Inhibited while `FraudShieldMlHealthProbeFailing` fires, so on its own it usually means a
partial failure: some API pods cannot reach the scorer, or the scorer is timing out under load.

**Impact.** Affected decisions use versioned rules instead of the model and carry the
`ML_UNAVAILABLE` label; the replay job re-scores them after recovery and flags disagreements.

## Diagnose

1. **FraudShield / Decision path** dashboard, fallback share: all API pods or some?
2. Some pods only: their node, NetworkPolicy, or mTLS client certificate.
3. All pods: scorer latency (`fs_scoring_latency_seconds`) near the client timeout means
   overload; scorer errors mean a model or runtime fault.
4. Scorer pod CPU and HPA state (`kubectl -n fraudshield get hpa`).

## Mitigate

Scale the scorer, roll back a model that serves slowly (`POST /api/v1/admin/models/rollback`), or
restart only the affected API pods. The circuit closes by itself once calls succeed.

## Escalate

ML on-call if a model change is involved; tell risk officers the fallback share and duration.
