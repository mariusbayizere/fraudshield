# 0093 — The metrics the code emits that Part E.10 does not list

- **Status:** Proposed (for the owner's decision)
- **Decided by:** not decided. The owner asked for this record on 2026-10-20
  (`docs/parallel/M9_updates.md` 3c, item 4; backlog PB-178); the M9 agent wrote it.
- **Date:** 2026-10-20
- **Requirements affected:** OPS-OBS-01, OPS-OBS-03, OPS-OBS-05
- **Backlog:** PB-174, PB-175, PB-176, PB-177, PB-178

## Context

Build prompt Part E.10 is the single catalogue of FraudShield metrics.
`infrastructure/checks/metric_catalogue.py` parses the backticked `fs_` names in E.10 and fails
any alert rule, recording rule, dashboard query or canary query that names anything else. That is
what catches a renamed metric before it silently empties an alert.

M5, M6 and M7 have since added metrics E.10 does not list. No rule or dashboard uses them today,
because the catalogue would reject it. Some of them are the signals M9 has been asked to alert on
(M5's `fs_feature_store_missing_producer_reads_total`, in the "From M5" section of
`M9_updates.md`). Three E.10 entries also do not match what is or could be emitted.

Inventory, read from the source on `main` at `a7f0528` on 2026-10-20. The registered name is the
one in the code. Prometheus exports Micrometer meters with a unit suffix and Timers in seconds.
**One exported name is observed:** `fs_spool_depth` is exported as `fs_spool_depth_bytes`, by the
scrape test added on 2026-10-20 in `AssembledApplicationTest` (CI run 39376711927, on `m9/infra`
commit `97f4504`, whose backend main code is the same as `main`'s). The other
exported names in the table are what that naming rule predicts, not observations; PB-174's scrape
test pins them.

### API (`backend/ingest`, Micrometer)

| Registered name | Type | Labels | Expected export |
|---|---|---|---|
| `fs_decision_stage` | Timer, client percentiles | `stage` | `fs_decision_stage_seconds` |
| `fs_hold_timeout_lateness_ms` | DistributionSummary | none | `fs_hold_timeout_lateness_ms` (`_count`, `_sum`, `_max`) |
| `fs_feature_store_degraded_total` | Counter | none | same |
| `fs_decision_state_write_failures_total` | Counter | `state` | same |
| `fs_degraded_mode` | Gauge | none | same |
| `fs_spool_lag` | Gauge, unit bytes | `consumer` | `fs_spool_lag_bytes` |
| `fs_idempotency_unverified_claims_total` | Counter | none | same |
| `fs_rate_limit_decisions_total` | Counter | `limiter` (`local`, `redis`) | same |

### Notification consumers (`backend/notify`, `backend/ingest`; every one carries `group`)

| Registered name | Type | Extra labels |
|---|---|---|
| `fs_notify_consumer_running` | Gauge | none |
| `fs_notify_head_failing_seconds` | Gauge | none |
| `fs_notify_webhook_delivery_running` | Gauge | none (no `group`) |
| `fs_notify_consumer_errors_total` | Counter | none (`group` includes `webhook-delivery`, which is not a consumer group in `contracts/kafka/topics.yaml`) |
| `fs_notify_resolved_before_send_total` | FunctionCounter | none |
| `fs_notify_offsets_skipped_total` | Counter | none |
| `fs_notify_retries_total` | Counter | `class` (`outage`, `may_be_own`) |
| `fs_notify_wait_warnings_total` | Counter | none |
| `fs_notify_malformed_headers_total` | Counter | none |
| `fs_notify_ignored_kept_results_total` | Counter | none |
| `fs_notify_sms_outcome_unknown_total` | Counter | none |
| `fs_notify_dead_letters_total` | Counter | `reason` |
| `fs_notify_dropped_total` | Counter | none |

### Feature store and scorer (`ml/src`, prometheus_client)

| Name | Type | Labels |
|---|---|---|
| `fs_feature_store_update_seconds` | Histogram, 1 ms to 1 s | none |
| `fs_feature_store_fallbacks_total` | Counter | `entity` |
| `fs_feature_store_missing_producer_reads_total` | Counter | `state` |
| `fs_feature_store_unknown_state_total` | Counter | `entity` |
| `fs_feature_store_writes_dropped_total` | Counter | none |
| `fs_feature_store_writes_failed_total` | Counter | none |
| `fs_model_swaps_total` | Counter | `alias` |
| `fs_model_swap_failures_total` | Counter | `alias` |
| `fs_shadow_scored_total` | Counter | none |
| `fs_shadow_dropped_total` | Counter | none |
| `fs_shadow_failed_total` | Counter | none |

That is 32 names: 8 in the API, 13 in the notification consumers and 11 in ML.

### E.10 entries that do not match the code

1. **`fs_spool_depth`**: E.10 and M9's contract mean records not yet acknowledged by Kafka. The
   code exports bytes: the lag of the slower of two consumers, Kafka and PostgreSQL
   (`durableEnd - minimumCommitted()`), registered with unit bytes. What it should count depends on the spool's redesign (PB-171).
2. **`fs_db_pool_in_use`**: not emitted. Spring Boot already exports HikariCP's pool as
   `hikaricp_connections_active{pool}`.
3. **`fs_redis_hit_ratio`**: not emitted, and E.10 does not say which cache it measures: the
   API's key and rate-limit caches, or the scorer's feature reads.

## Options considered

1. **List every emitted name in E.10, under the exported name and with its labels.** The catalogue
   then covers everything the code exports, and any later rename fails CI. Cost: E.10 grows by
   32 names, and new labels (`stage`, `state`, `consumer`, `limiter`, `group`, `class`, `reason`,
   `entity`, `alias`) need their value sets stated, or the check accepts any value for them.
2. **List only the names an alert, dashboard or canary will use.** E.10 stays short. Every other
   metric remains usable for ad-hoc queries in Prometheus, but no reviewed rule can use it until a
   further amendment. Each new alert then needs its own ADR, and a rename of an unlisted metric is
   caught by nothing.
3. **Rename the code to E.10's conventions** (for example `fs_hold_timeout_lateness_ms` to a
   `_seconds` histogram, the notification metrics under one prefix). This changes M5's, M6's and
   M7's code and tests and every runbook that names them, for no gain in what can be observed.

For the three mismatched entries:

- **`fs_spool_depth`**: (a) keep E.10's meaning (records, Kafka only) and change the code; or
  (b) amend E.10 to the code's meaning (bytes, minimum over consumers), exported as
  `fs_spool_depth_bytes`.
- **`fs_db_pool_in_use`**: (a) M6 emits it; or (b) replace it in E.10 with
  `hikaricp_connections_active{pool}` and list it among the external metrics the catalogue
  accepts.
- **`fs_redis_hit_ratio`**: (a) define it (which cache, ratio of what to what) and have its owner
  emit it; or (b) drop it from E.10 and remove the dashboard panel's half that reads it.

## Recommendation (the M9 agent's; the owner decides)

- **Option 1**, with the exported names. The catalogue check exists to stop a metric's rename
  emptying an alert silently, and it can only do that for names it lists; option 2 leaves 32
  names unprotected. For a label whose values are not a contract (`state`, `reason`, `entity`),
  E.10 states that it is free-form, and the check accepts any value. The catalogue's suffix rule
  also accepts `_max` and a non-`_seconds` distribution, so the timers and
  `fs_hold_timeout_lateness_ms` are queryable. That is PB-178's code change in
  `metric_catalogue.py`, with tests.
- **`fs_spool_depth`: (b)**, bytes, exported as `fs_spool_depth_bytes`. Revisit it with PB-171,
  which may leave one consumer per pod's volume. The scrape test settled the exported name.
- **`fs_db_pool_in_use`: (b)**, HikariCP's own metric. It already exists, and a second name for
  the same number adds nothing.
- **`fs_redis_hit_ratio`: (b)**, drop it until someone can say what it measures. The SRS 8.2 key
  metrics do not name it.

## Consequences

If accepted:
- E.10 is amended in `docs/prompts/FraudShield_Master_Build_Prompt.md`, citing this ADR, as ADR
  0091 did.
- `metric_catalogue.py` gains the suffix handling and the free-form labels, with tests.
- M9 can then write the alert for M5's missing-producer counter and a spool-lag alert.

Until it is decided, nothing changes. The alerts and panels that read metrics nobody emits are
marked not functional in M9's traceability rows (PB-174 to PB-177).
