# 0012 — Event, scoring and webhook contracts

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** FR-01-01, FR-02-01, FR-02-08, FR-02-10, FR-03-02, FR-05-05, FR-06-07
- **Defects referenced:** D-04, D-05, D-06, D-10, D-12, D-13, D-14, D-15, D-25, D-32

## Context

Build prompt C.3 lists thirteen Kafka topics, each needing a versioned schema, a documented key,
partitions, retention and a dead-letter queue; C.2 puts a gRPC call between the Java API and the Python
scorer; D-14 requires a signed `decision.final` webhook. These contracts cross three languages and two
processes, so their conventions must be explicit and tested.

## Decision

1. **Kafka messages** are JSON (not Avro or Protobuf) in a common envelope
   (`event_id`, `event_type`, `schema_version`, `occurred_at`, `institution_id`, `producer`,
   `traceparent`, `payload`). JSON keeps events readable in audits and in the dead-letter queue, and
   the JSON Schemas share primitives with the OpenAPI contract; throughput-critical paths stay on the
   synchronous gRPC call. Schemas are JSON Schema 2020-12 under `contracts/kafka/schemas`, identified
   by `urn:fraudshield:kafka:<name>`.
2. **Topics** are declared in `contracts/kafka/topics.yaml` with key, partitions, retention, producer
   and consumers, and created from it; producer auto-creation is disabled. Every topic has
   `<topic>.dlq` with 30-day retention. Labels keep 400 days so retraining sees a full year plus label
   delay.
3. **Partition keys** preserve the ordering each consumer needs: `account_token` for per-account state
   (feature store, alerts, freeze), `transaction_id` for per-transaction lifecycles, a writer partition
   for the audit hash chain (D-32), and a single partition for configuration changes, which are applied
   strictly by version.
4. **No personal data in events.** Customer notifications are *intents* (account token, template key,
   locale, masked account, amount, reference code); the notification service resolves contact details
   from the PII vault at send time (D-25, NFR-SEC-03).
5. **Schema evolution.** Additive changes keep `schema_version`; a breaking change creates a new
   version consumed side by side until producers migrate. The M9 breaking-change check compares schemas
   with `main`.
6. **Scoring gRPC** (`fraudshield.scoring.v1`): the API sends the transaction and the Redis account
   context; the scorer returns the FR-02-01 fields plus `anomaly_raw` (D-06), all 44 SHAP contributions
   for flagged transactions in margin space with base value and final margin (D-05), the feature
   registry version, and per-stage timings. Features that are structurally missing use
   `FeatureValue.missing` (D-04). The tier returned is the model's default; the API recomputes it from
   the institution's per-channel thresholds. Money is a decimal string. Fields are only added; removed
   numbers are reserved.
7. **Webhook signatures** follow `contracts/webhooks/decision-final.md`: `t=<unix>,v1=<hex HMAC-SHA256>`
   over `t + "." + raw body`, a 300-second replay window, multiple `v1` values during key rotation, and
   at-least-once delivery with deduplication by the receiver. `contracts/webhooks/signature-test-vectors.json`
   is the shared test oracle, verified by the Python reference verifier now and the Java dispatcher in M6.

## Consequences

- Contract tests (`contracts/tests`) compile the proto, validate every schema and topic example, check
  the shared primitives are identical to OpenAPI, and verify the signature vectors.
- JSON events are larger than binary encodings; if Kafka throughput measurements in M10 show the
  envelope matters, `fs.transactions.scored` can move to Protobuf through a new ADR.
