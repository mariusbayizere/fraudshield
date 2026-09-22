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
4. **A claim is owned** by a random token. Only its owner can release it or mark it uncertain.
   Completion always stores the fingerprint, whether or not the lease is still held. A decision that
   finishes after its lease expired is therefore replayed to identical requests instead of being
   reported as a conflict. If a duplicate re-claimed and completed first, the first completed
   response stands and is returned to both clients (Principal Review finding 1).
5. **Outcome unknown.** The spool can take a record and fail to confirm its fsync in time. The
   decision may then still become durable, so the claim is marked `UNCERTAIN` for 24 hours instead
   of being released. The client gets 503, and the next claim verifies against PostgreSQL first.
   A caller that stops waiting before the writer took the record withdraws it: the record is never
   written, and the claim is released.
6. **Verification after decisions Redis does not hold** (Principal Review finding 2). Each
   institution has a Redis key recording the time until which new claims are checked against
   `transaction_ids` before being decided. A decision found there is copied into Redis and replayed.
   The API extends the window by 24 hours after deciding anything without Redis. The key is
   created with a 24-hour window on first use, so a Redis that lost its data never trusts its own
   emptiness for longer than the lost records would have lived. The cost is one indexed PostgreSQL
   read per claim during the window, including the first day after a fresh deployment. If
   PostgreSQL cannot answer, the claim is **decided unverified** and counted in
   `fs_idempotency_unverified_claims_total`. Decisions must continue while PostgreSQL is down
   (C.4), and in the rare double fault the PostgreSQL writer still keeps the first decision.
7. Once the decision is durable it stands. A failed Redis write of the decision state, the hold
   timer or the idempotency record no longer turns it into an error the client would retry.
   PostgreSQL covers each of them: states are read from it on a miss, overdue holds are reconciled
   (V62), and point 6 covers the idempotency record.
8. The PostgreSQL reads on the hot path (idempotency, freeze counts, freeze status) have a
   1-second statement timeout. Together with Hikari's 2-second connection timeout, a stalled
   PostgreSQL costs a request at most about 3 seconds per read, inside the 10-second lease
   (Principal Review finding 10). That bounds the wait but does not make it fast. A per-dependency
   hot-path budget answered as 503 is still open.

## Consequences

`IngestApiTest` (100 concurrent identical submissions: one scoring, one body, TTL 24 h; the 409 and
its audit event), `ResilienceApiTest` (10,000 duplicates scored once; replay from PostgreSQL with
Redis paused; after Redis recovers, a resubmission of a decision made during the outage is
replayed, not decided again; decisions continue with PostgreSQL paused), `RedisIdempotencyTest`
(completion after lease expiry, racing duplicates, ownership, uncertain claims, the verification
window), `SpoolDrainerTest` (refused batches are retried and never committed past; withdrawn
records are never written).
