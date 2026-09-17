# 0011 — API contract conventions

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** FR-01-01 … FR-01-07, FR-02-06, FR-03-02, FR-04-*, FR-05-*, FR-06-*,
  FR-07-*, NFR-SEC-03
- **Defects referenced:** D-12, D-14, D-19, D-24, D-27, D-29, D-44

## Context

M1 delivers the contracts that Java, Python and TypeScript implement. Decisions made implicitly in
code tend to diverge between languages, so the conventions are fixed once here and enforced by
`contracts/tests`.

## Decision

1. **Contract-first.** `contracts/openapi/fraudshield-api.yaml` (OpenAPI 3.1, JSON Schema 2020-12)
   is the source of truth. The API serves it at `/api/docs`; M6/M7 tests assert that controllers
   match it. Front-end types are generated from it (build prompt H.4).
2. **Base path** `/api/v1`. The customer verification page (`/verify/{token}`) and health endpoints
   outside `/api/v1` (`/actuator/health`) are documented in the same file.
3. **Money** travels as a decimal string with its currency, never as a JSON number:
   `amount` matches `^(0|[1-9]\d{0,13})(\.\d{1,4})?$` (at most 14 integer digits and 4 decimals,
   `DECIMAL(18,4)`), and `currency` is an ISO 4217 code. Ingested amounts must be greater than zero
   (`Money.requirePositive`). This matches the Java `Money` type exactly.
4. **Identifiers.** `transaction_id` is a UUID and the idempotency key (FR-01-03). Account,
   counterparty, device and agent identifiers are opaque tokens matching `^tok_[A-Za-z0-9]{24,}$`;
   raw phone or account numbers are rejected at the schema boundary (E.1, NFR-SEC-03).
5. **Time** is RFC 3339 in UTC with a `Z` suffix; local times are derived and shown with a zone
   abbreviation only in the UI (D-43).
6. **Errors** use RFC 9457 `application/problem+json`. `type` is a stable URN
   `urn:fraudshield:problem:<name>` (the project owns no domain name to host problem URLs). Every
   problem carries `correlation_id`; validation problems carry `errors[]` with `field`, `code` and
   `message`. 400 is for missing or malformed fields, 422 for type mismatches and semantic
   violations (FR-01-02).
7. **Authentication.** Machine clients use `X-API-Key: fsk_<env>_<keyId>_<secret>` (D-19); staff use
   `Authorization: Bearer <RS256 JWT>` (FR-07-04). API keys never reach staff endpoints (FR-01-05).
8. **Authorisation is declared per operation**, not inferred: `x-required-scopes` for API-key
   operations and `x-required-roles` for staff operations (roles ANALYST, SENIOR_ANALYST,
   RISK_OFFICER, ADMIN; ADMIN is not implied to decide alerts, E.8). Public operations declare
   `security: []` and `x-public: true`. The M7 role × endpoint matrix test reads these extensions.
9. **Ingest response** is the minimal `DecisionResponse` (D-12); the full `ScoringResult` is only on
   staff endpoints. MEDIUM returns `HOLD` with `review_deadline_at`; the final decision is available
   from `GET /decisions/{transaction_id}` and the signed `decision.final` webhook (D-14).
10. **Concurrency and offline replay.** Mutations on alerts carry the alert `version` (optimistic
    locking) and an `Idempotency-Key` header; stale versions return 409 with the current state
    (D-29). Analyst decisions return `202` with `undo_until` (D-44).
11. **Pagination** is cursor-based (`cursor`, `limit` ≤ 200, response `next_cursor`).
12. **Schema additions to FR-01-02**, each optional and required only where stated: `agent_id` (token;
    required when `channel` is `AGENT_BANKING`, for the agent-specific features of E.2),
    `counterparty_country` (ISO 3166-1 alpha-2; for `corridor_class`, E.2), and `merchant_name`
    (display only). No field carries personal data.

## Consequences

- A single place defines the wire format for all three languages; drift is caught by contract tests.
- The URN problem types are stable without hosting; if a domain is acquired later, a mapping can be
  published without changing clients.
- Adding an endpoint requires declaring its roles or scopes up front, which the authorisation tests
  depend on.
