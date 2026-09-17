# contracts

Versioned interface contracts shared by Java, Python and TypeScript. Delivered in **M1**.

- `openapi/` — OpenAPI 3.1 for ingestion, decisions, jobs and staff APIs (RFC 9457 problems).
  - `authorisation-matrix.yaml` — reviewed roles, scopes or public access per operation (ADR 0014).
  - `schema-examples.yaml` — a valid example for every object schema an operation uses.
- `validation/` — vectors shared by the Java, TypeScript and contract tests: person names (ADR 0013),
  passwords (ADR 0014) and request validation with expected status and error codes (ADR 0011).
- `proto/` — gRPC scoring contract between the API and the ML scorer.
- `kafka/` — JSON Schema per topic (C.3) with key, partitions, retention and DLQ.
- `webhooks/` — `decision.final` payload and HMAC-SHA256 signature specification (D-14).
