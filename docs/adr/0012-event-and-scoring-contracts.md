# 0012 — Event, scoring and webhook contracts

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** FR-01-01, FR-02-01, FR-02-08, FR-02-10, FR-03-02, FR-05-05, FR-06-07
- **Defects referenced:** D-04, D-05, D-06, D-10, D-12, D-13, D-14, D-15, D-25, D-31, D-32
- **Amended:** 2026-09-17 after the M1 contracts review (CR-02, CR-11 … CR-17, CR-26, CR-33)

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
   delay. Each topic also binds one `event_type` and `schema_version`; an envelope that disagrees
   with its topic is invalid.

   **Tenant isolation (D-31).** Kafka is internal to a deployment. Every producer and consumer is a
   FraudShield service with its own SASL/mTLS principal and ACLs matching the topic's `producers` and
   `consumers`. A test fails if any other principal is listed. Core banking never touches Kafka: it
   sends transactions over the HTTP API, where the API key identifies the institution, and receives
   decisions by signed webhook or `GET /decisions`. The producing service sets `institution_id` from
   the authenticated principal. The SRS 3.2 step in which core banking publishes to
   `fs.transactions.raw` is replaced: the API publishes accepted requests there after authentication
   (which D-13 already requires for the decision path), and `fs.decisions.final` feeds the webhook
   dispatcher, which calls only the owning institution's URL. Per-institution topics were rejected:
   they multiply topics and ACLs per tenant without protecting anything the internal-only rule does
   not.
3. **Partition keys** preserve the ordering each consumer needs: `account_token` for per-account state
   (feature store, alerts, freeze), `transaction_id` for per-transaction lifecycles, a writer partition
   for the audit hash chain (D-32), and a single partition for configuration changes, which are applied
   strictly by version.
4. **No personal data or credentials in events.** Customer notifications are *intents* (account token,
   template key, locale, masked account, amount, local time with zone, reference code, and a required
   `verification_link_allowed` flag so a missing value can never be read as allowed). The notification
   service resolves contact details from the PII vault and mints the single-use verification link at
   send time (D-25, NFR-SEC-03). Staff notifications have a closed parameter schema per kind. Temporary
   passwords, one-time codes and single-use links are minted by the auth module when the notification
   service sends the message; they exist in plaintext only in that request, and only their hashes are
   stored. None of them is ever on a topic or in a dead-letter queue.
5. **Schema evolution: backward compatible only.** A new schema must accept every message the
   previous one accepted, so **consumers deploy first** and producers follow. Producers validate
   against the closed schemas. Consumers validate as tolerant readers that ignore unknown properties
   (`validate_event(..., reader=True)`), so a producer may add an optional field once consumers are
   deployed. Anything else is a breaking change and needs a new `schema_version`: removing or renaming a
   property; making one required; narrowing a type, enum, pattern or bound; closing an object; changing
   a `$ref` or a conditional. The new version is published alongside the old one until producers have
   migrated. `contracts/kafka/baseline/` holds the published schemas, and
   `fraudshield_contracts.compatibility` compares every current schema with its baseline on every CI run.
   It is deliberately conservative: a change it cannot prove widening counts as breaking. Its self-tests
   apply each kind of breaking change and require it to be reported. A committed baseline replaces the
   earlier plan to compare with `main`, because CI checks out one commit.
6. **Scoring gRPC** (`fraudshield.scoring.v1`): the API sends the transaction and the Redis account
   context; the scorer returns the FR-02-01 fields plus `anomaly_raw` (D-06), all 44 SHAP contributions
   for flagged transactions in margin space with base value and final margin (D-05), the feature
   registry version, and per-stage timings. Features that are structurally missing use
   `FeatureValue.missing` (D-04). The tier returned is the model's default; the API recomputes it from
   the institution's per-channel thresholds. Money is a decimal string. Structural missingness is an
   empty `FeatureValue.Missing` message, so "not missing" cannot be expressed; stage timings use a
   `Stage` enum. Liveness uses the standard `grpc.health.v1.Health` service; `GetModelStatus` reports
   loaded model versions.

   **Evolution.** Fields and enum values are only added. A removed number and its name are reserved, and
   no number, name, type, label or oneof membership changes. `contracts/proto/baseline/scoring-v1.json`
   summarises the compiled descriptor, and `fraudshield_contracts.proto_compat` fails the build on any
   such change. Its self-tests compile edited copies of the proto (a renumbered field, a deletion
   without `reserved`, a type change, a rename, a label change, a renamed enum value, a removed method).
   `buf breaking` was not added, to avoid another pinned binary.
7. **Webhook signatures** follow `contracts/webhooks/decision-final.md`: `t=<unix>,v1=<hex HMAC-SHA256>`
   over `t + "." + raw body`. Each attempt is signed with that attempt's time, so late retries stay
   inside the 300-second window. Several `v1` values are sent during key rotation. Delivery is
   at-least-once, and receivers order and deduplicate by `decision_sequence` (ADR 0011 §9), never by
   decision value. The signature and delivery-ordering vectors are the shared test oracle, verified by
   the Python reference implementation now and the Java dispatcher in M6.
8. **Secret scanning** (`.gitleaks.toml`) extends the default rules with rules for `fsk_` API keys and
   `whsec_` signing secrets. Allowlists name exact values in exact files for the rules that match them.
   Every allowlist is rule-scoped, because gitleaks 8.30.1 `dir` skips whole files that match a global
   allowlist's paths. `tools/bin/gitleaks-selftest` plants fake secrets (outside and inside the
   allowlisted fixtures) in a throw-away repository and fails unless every one is reported. It runs in
   `make secrets-scan` and in the CI secrets job.

## Consequences

- Contract tests (`contracts/tests`) compile the proto and compare it with its baseline, validate
  every schema and topic example, check Kafka schemas for backward compatibility, check that shared
  primitives, the raw transaction and the final decision resolve to exactly the OpenAPI schemas, and
  verify the signature and ordering vectors.
- Consumers must be deployed before producers for any schema change, and ACLs must be kept in step with
  `topics.yaml`.
- JSON events are larger than binary encodings; if Kafka throughput measurements in M10 show the
  envelope matters, `fs.transactions.scored` can move to Protobuf through a new ADR.
