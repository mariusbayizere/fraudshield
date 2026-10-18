# contracts

Versioned interface contracts shared by Java, Python and TypeScript. Delivered in **M1**.

- `openapi/` — OpenAPI 3.1 for ingestion, decisions, jobs and staff APIs (RFC 9457 problems).
  - `authorisation-matrix.yaml` — reviewed roles, scopes or public access per operation (ADR 0014).
  - `schema-examples.yaml` — a valid example for every object schema an operation uses.
- `validation/` — vectors shared by the Java, TypeScript and contract tests: person names (ADR 0013),
  passwords (ADR 0014) and request validation with expected status and error codes (ADR 0011).
- `proto/` — gRPC scoring contract between the API and the ML scorer; `buf.yaml` configures `buf lint`
  and `buf breaking`, which runs against the proto on main (ADR 0016).
- `kafka/` — JSON Schema per topic (C.3) with key, partitions, retention, DLQ and event-type binding;
  `baseline/` holds the published schemas that the backward-compatibility check compares with.
- `webhooks/` — `decision.final` signature and delivery specification with signature and ordering
  vectors (D-14).
