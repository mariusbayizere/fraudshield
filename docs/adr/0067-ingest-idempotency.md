# 0067 — Ingest idempotency: leases, waiting and degraded mode

- **Status:** Accepted
- **Date:** 2026-09-22
- **Requirements affected:** FR-01-03
- **Defects referenced:** D-12

## Context

FR-01-03 and ADR 0011 section 10 say a duplicate within 24 hours replays the cached decision, a
changed request is a 409, and a duplicate that arrives while the first is being decided waits for
it. They do not say how long a claim may block, or what happens without Redis.

## Decision

1. One Lua script claims, replays or reports a conflict. A claim is a **10-second in-flight lease**,
   so a process that dies mid-decision blocks a retry for seconds, not 24 hours; the completed
   record holds the exact response bytes for 24 hours.
2. A duplicate **waits up to 1 s** for the first result, then answers 503 with `Retry-After: 1`
   rather than deciding again.
3. **Redis down**: PostgreSQL answers read-only. A known id with another fingerprint is a conflict;
   with the same fingerprint the persisted decision is rendered again byte for byte, or, until it is
   persisted, 503. Nothing is inserted, because `transaction_ids` is append-only and a claim for a
   decision that then failed could never be released. Two first submissions racing while Redis is
   down can both be decided; the PostgreSQL writer keeps the first.

## Consequences

`IngestApiTest` (100 concurrent identical submissions: one scoring, one body, TTL 24 h; the 409 and
its audit event), `ResilienceApiTest` (10,000 duplicates scored once; replay from PostgreSQL with
Redis paused).
