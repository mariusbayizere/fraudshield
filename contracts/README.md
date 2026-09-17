# contracts

Versioned interface contracts shared by Java, Python and TypeScript. Delivered in **M1**.

- `openapi/` — OpenAPI 3.1 for ingestion, decisions, jobs and staff APIs (RFC 9457 problems).
- `proto/` — gRPC scoring contract between the API and the ML scorer.
- `kafka/` — JSON Schema per topic (C.3) with key, partitions, retention and DLQ.
- `webhooks/` — `decision.final` payload and HMAC-SHA256 signature specification (D-14).
