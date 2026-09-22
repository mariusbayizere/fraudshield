# FraudShieldMlHealthProbeFailing (P1)

**Meaning.** `/actuator/health/ml` on the API management port failed twice in a row, 60 s apart.
The API cannot reach a healthy scorer, so its Resilience4j circuit is open and decisions come from
the rule-based fallback engine with `ml_unavailable_fallback=true` (C.4).
`FraudShieldMlFallbackActive` is inhibited while this fires.

**Impact.** Decisions continue, but from versioned rules instead of the model: expect more false
positives and missed fraud. Every fallback decision carries the `ML_UNAVAILABLE` label and is
re-scored by the replay job after recovery.

## Diagnose

1. `kubectl -n fraudshield get pods -l app.kubernetes.io/name=fraudshield-ml`: Ready, restarts,
   `OOMKilled`?
2. `kubectl -n fraudshield logs deploy/fraudshield-ml --tail=200`: model load failures after an
   alias move, gRPC TLS errors, MLflow unreachable.
3. Recent model change? `GET /api/v1/admin/models` (ADMIN) shows the `@production` alias and when
   it moved.
4. mTLS: an expired scorer or client certificate fails every call while pods look healthy.

## Mitigate

- A new model that fails to load or serve: roll back with `POST /api/v1/admin/models/rollback`
  (ADMIN, audited as `MODEL_LIFECYCLE`); the previous alias is re-served within 30 s (FR-06-03).
- Resource exhaustion: scale the scorer (`kubectl -n fraudshield scale deploy/fraudshield-ml
  --replicas=<n>`), then fix the HPA bounds in the overlay.

## Escalate

ML on-call when a model change is involved. Risk officers should know the system is on rules
only; the replay job's disagreements will land in the analyst queue after recovery.
