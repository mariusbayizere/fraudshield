# 0011 — API contract conventions

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** FR-01-01 … FR-01-07, FR-02-06, FR-03-02, FR-04-*, FR-05-*, FR-06-*,
  FR-07-*, NFR-SEC-03
- **Defects referenced:** D-12, D-14, D-19, D-24, D-27, D-29, D-44
- **Amended:** 2026-09-17 after the M1 contracts review (CR-02, CR-07, CR-08, CR-20 … CR-23, CR-31);
  authorisation decisions moved to ADR 0014

## Context

M1 delivers the contracts that Java, Python and TypeScript implement. Decisions made implicitly in
code tend to diverge between languages, so the conventions are fixed once here and enforced by
`contracts/tests`.

## Decision

1. **Contract-first.** `contracts/openapi/fraudshield-api.yaml` (OpenAPI 3.1, JSON Schema 2020-12)
   is the source of truth. The API serves it at `/api/docs`; M6/M7 tests assert that controllers
   match it. Front-end types are generated from it (build prompt H.4).
2. **Base path** `/api/v1`. The customer verification page (`/verify/{token}`) and the orchestration
   probe `/actuator/health` (management port, status only, ADR 0014) are outside `/api/v1` and are
   documented in the same file with a path-level `servers` override.
3. **Money** travels as a decimal string with its currency, never as a JSON number:
   `amount` matches `^(0|[1-9]\d{0,13})(\.\d{1,4})?$` (at most 14 integer digits and 4 decimals,
   `DECIMAL(18,4)`), and `currency` is an ISO 4217 code. Ingested amounts must be greater than zero
   (`Money.requirePositive`). This matches the Java `Money` type exactly.
4. **Identifiers.** `transaction_id` is a UUID and the idempotency key (FR-01-03). Account,
   counterparty, device and agent identifiers are opaque tokens matching `^tok_[A-Za-z0-9]{24,64}$`;
   raw phone or account numbers are rejected at the schema boundary (E.1, NFR-SEC-03).
5. **Time** is RFC 3339 in UTC with a `Z` suffix; local times are derived and shown with a zone
   abbreviation only in the UI (D-43).
6. **Errors** use RFC 9457 `application/problem+json`. `type` is a stable URN
   `urn:fraudshield:problem:<name>` from the `ProblemType` catalogue in the contract; each response
   lists the types it may carry in `x-problem-types`, and a test fails if a type is used anywhere
   without being catalogued. The project owns no domain name to host problem URLs. `fraudshield` is
   not a registered URN namespace (RFC 8141), so these identifiers are opaque names, not resolvable
   URNs; if a domain is acquired they can be mapped to URLs without changing their meaning. Every
   problem carries `correlation_id`; validation problems carry `errors[]` with `field`, a `code`
   from `ValidationErrorCode`, and `message`.

   **400 or 422** (E.1, FR-01-02). E.1 says missing fields → 400, type mismatches → 422 and raw
   MSISDNs → 400; the rule that reconciles all three is whether the body is a usable instance of
   the schema at all:

   | Check | Status | `errors[].code` |
   |---|---|---|
   | Body is not valid JSON | 400 | `malformed_json` |
   | Required field missing (including conditional, e.g. `agent_id` for AGENT_BANKING) | 400 | `required` |
   | Field not in the schema | 400 | `unknown_field` |
   | Identifier field is not a `tok_` token (raw MSISDN or account number) | 400 | `not_a_token` |
   | Object with too few properties (an update that changes nothing) | 400 | `required` |
   | JSON type differs (amount as a number, latitude as a string); no other code is reported for that field | 422 | `type_mismatch` |
   | Wrong shape (`format`, `pattern`): UUID, decimal scale above 4, leading zero, exponent, MCC digits, timestamp not in UTC | 422 | `invalid_format` |
   | Value outside an enum or not the required constant | 422 | `unsupported_value` |
   | Number or amount outside its range (`minimum`, `maximum`, `not` zero; a canonical decimal string that is negative or has more than 14 integer digits) | 422 | `out_of_range` |
   | String too short or too long (`minLength`, `maxLength`; e.g. an override reason under 20 characters) | 422 | `length_out_of_range` |
   | Too few or too many array items (`minItems`, `maxItems`; e.g. a batch above 1,000) | 422 | `item_count_out_of_range` |
   | Repeated array items (`uniqueItems`) | 422 | `duplicate_items` |
   | Escalation target not above the caller (ADR 0014) | 422 | `escalation_target_not_higher` |
   | Timestamp more than 5 minutes ahead of the server clock | 422 | `timestamp_in_future` |
   | Threshold medium ≥ high; duplicate channel | 422 | `thresholds_not_ordered`, `duplicate_channel` |
   | CIDR that does not parse or has host bits set | 422 | `invalid_cidr` |
   | Webhook URL resolving to a non-public address, IP literal or user info | 422 | `webhook_url_not_allowed` |
   | Password or person-name rule (ADR 0013, 0014) | 422 | `password_policy`, `person_name` |

   All errors are reported; if any is a 400-class error the status is 400. Every operation with a
   request body documents both 400 and 422. The contract holds the status per code in
   `ValidationErrorCode.x-status-by-code`; `fraudshield_contracts.validation` is the executable
   keyword-to-code mapping (it fails on an unmapped keyword); and
   `contracts/validation/request-validation-vectors.json` gives request bodies with the expected
   status and (field, code) pairs. Contract tests require the schema to produce exactly those pairs,
   require server-only cases to pass the schema, and require each operation to document the statuses
   its vectors expect. The M6 and M7 controller tests consume the same file. Composed schemas are
   closed by listing their property names beside `additionalProperties: false`, not with
   `unevaluatedProperties`, which would report every valid field as unexpected when one value is
   invalid. Every `if` requires the property it tests, so it cannot pass vacuously.
7. **Authentication.** Machine clients use `X-API-Key: fsk_<env>_<keyId>_<secret>` (D-19); staff use
   `Authorization: Bearer <RS256 JWT>` (FR-07-04). API keys never reach staff endpoints (FR-01-05).
   Webhook signing secrets have the form `whsec_<env>_<32–64 alphanumerics>`; both prefixes exist
   so secret scanners can recognise the project's credentials.
8. **Authorisation is declared per operation**, not inferred: `x-required-scopes` for API-key
   operations, `x-required-roles` for staff operations, `x-public: true` with `security: []` for
   public ones, and `x-authorisation-rules` for object-level rules. The declarations must equal the
   reviewed golden matrix; the model and its decisions are in ADR 0014.
9. **Ingest response** is the minimal `DecisionResponse` (D-12); the full `ScoringResult` is only on
   staff endpoints. Its property set is exactly the seven D-12 fields plus two additions, and a test
   asserts the exact set: `review_deadline_at` (required by D-14 for HOLD, null otherwise) and
   `ml_unavailable_fallback` (true when the rule-based fallback decided, so integrators can route
   those decisions to their own review; it reveals no model internals). The schema also enforces
   E.6 consistency: HOLD ⇔ a deadline, HOLD ⇒ MEDIUM, APPROVE ⇒ LOW, HIGH ⇒ DECLINE. The schema
   allows DECLINE at a lower tier; that DECLINE happens only for a frozen account (reason
   `ACCOUNT_FROZEN`) is server-enforced and covered by the M6 decision-engine tests.

   **Final decisions (D-14).** One `FinalDecision` shape is returned by
   `GET /decisions/{transaction_id}`, published on `fs.decisions.final` and sent as the
   `decision.final` webhook body. A decision can change several times (HOLD → DECLINE → APPROVE by
   customer verification → DECLINE by senior override), so each state carries an `event_id` (the
   deduplication key, constant across delivery retries) and a per-transaction `decision_sequence`
   that starts at 1 for the ingest decision and increases by 1 per change. Receivers apply a state
   only if its sequence is greater than the last one applied, and acknowledge and ignore anything
   else; deduplicating on the decision value would drop a repeated DECLINE and let a delayed APPROVE
   undo a newer DECLINE. The allowed transitions are listed in the schema description; the schema
   enforces the structural ones (sequence 1 is decided by MODEL, later states name their
   predecessor and never return to HOLD).
10. **Concurrency and offline replay.** Mutations on alerts carry the alert `version` (optimistic
    locking) and an `Idempotency-Key` header; stale versions return 409 with the current state
    (D-29). Analyst decisions return `202` with `undo_until` (D-44).

    **Ingest idempotency (FR-01-03).** The idempotency record stores a SHA-256 fingerprint of the
    canonical validated request with the cached response. The same `transaction_id` with the same
    fingerprint within 24 hours replays the cached decision; with a different fingerprint it returns
    409 `idempotency-conflict` without scoring (otherwise a resubmitted larger amount would inherit
    an approval it never earned). A duplicate that arrives while the first is still being decided
    waits for that result.
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
