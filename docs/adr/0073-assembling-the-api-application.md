# 0073 — Assembling the API application: M6's decision path with M7's identity, keys and audit

- **Status:** Proposed (M7, awaiting owner review)
- **Date:** 2026-10-17
- **Requirements affected:** FR-01-05, FR-06-06, FR-06-07, FR-07-05, D-32; NFR-REL-02 and D-15
  (see the open conflict below)
- **Defects referenced:** D-15, D-19, D-32
- **Builds on:** ADR 0012 (events and idempotent consumers), ADR 0014 (staff authorisation), ADR 0064
  (spool), ADR 0066 (ports for parallel milestones), ADR 0068 (amended 2026-10-17), ADR 0070,
  ADR 0071

## Context

M6 and M7 were built in parallel, each with its own test application. Each expected the other to
assemble the real one. M6 shipped two ports, `ApiKeyAuthenticator` and `WebhookEndpoints`, with
only test doubles behind them, and an API application that refused to start without them. M7
shipped auto-configured libraries and asked M6 to depend on them. On the merged tree nobody had,
and M6's audit events were published to a topic nobody read (M7 merge review, findings 1 and 2).
The owner assigned both to M7 (2026-10-17), with an acceptance test each.

## Decisions

1. **The API application depends on `fraudshield-admin`**, which brings `auth` and `audit`. It
   stays M6's composition root (`backend/ingest`); M7 adds two wiring classes to it.
2. **`StaffIdentityWiring` binds M6's ports to M7.**
   - `ingest.auth.ApiKeyAuthenticator` delegates to M7's authenticator and maps its scopes by wire
     name. In the assembled application a key is checked twice: by M7's security chain, which
     authorises the request against the contract (ADR 0014), and again by M6's filter, which
     attaches the principal M6's controllers read. Both read the same cached record, so the second
     check costs one HMAC. Removing it would mean M6's controllers reading Spring Security's
     context, a change to M6's code that buys nothing measurable.
   - `WebhookEndpoints` is `ApiKeyService.webhookEndpoint`: the URL of the institution's newest live
     key that has one, and the signing secret of every live key with that URL, so both keys sign
     during a rotation overlap. `ApiKeyLifecycleTest` proves it, in
     `webhookEndpointIsTheNewestLiveUrlSignedByEveryLiveSecretOfThatInstitutionOnly`: overlap,
     expiry, revocation, keys without a webhook, another institution.
   - `/api/docs` gets its own chain, ordered first, which permits `GET` on exactly the two paths
     `ApiDocsController` serves. It is the only route M6 serves that the contract does not list
     (the document *is* the contract), and M7's chain denies whatever the contract does not list.
3. **Bean names that collided are renamed, not overridden.** Boot refuses to let one definition
   replace another, and that refusal is kept. The run found three collisions; review had predicted
   two:
   - M6's `tenantTransactions` is now `decisionTenantTransactions` (the audit module's is a
     different type with the same name);
   - M7's `persistenceSettingsGuard` is now `authPersistenceSettingsGuard`; both guards run;
   - M7's staff-persistence package is registered as an auto-configuration package only when no
     registered package already covers it. The API application's root package covers it, and
     registering it again made Spring Data define every staff repository twice.
4. **The audit writer's consumer** (`AuditRelayWiring`, group `audit-writer`) reads
   `fs.audit.events` with M6's `EnvelopeConsumer`, so the delivery rules are M6's tested ones:
   offsets committed after the append, a database outage re-read without bound, a record the
   database refuses as data dead-lettered to `fs.audit.events.dlq`. `RelayedAuditAppender` appends
   each event through the audit module's writer:
   - **once:** the envelope's `event_id` header goes into `audit_event_receipts` (V72) in the same
     transaction, and a conflicting insert means the event was appended before;
   - **to the producer's partition:** the payload's `writer_partition` (0–63, D-32), so M6's choice
     of chain is kept;
   - **with the actor's name and role** looked up when the event is appended, as every audit row
     stores them. M6 leaves the payload's `actor_role` null today, so a replay from the dead-letter
     topic days later records the role the staff member holds then; M6 filling `actor_role`, and
     the appender preferring it, is proposed in M7_updates;
   - a payload that is not an audit event is refused before any database access and dead-lettered
     at once.
5. **The proof is a test that boots the real application with no test bean**
   (`AssembledApplicationTest`). Only infrastructure is provided. The scorer, M5's separate Python
   process, is absent, so the C.4 fallback decides; that is a production path, and the fallback holds
   payments of 2,000,000 RWF or more for review (ADR 0061), which the decision engine audits. One
   journey crosses both milestones: an administrator signs in and issues a key; the key submits
   such a payment; the hold's audit event is found by audit search and passes `fraudshield audit
   verify`; and the revoked key is refused within 5 seconds. The same class asserts that no bean in
   the context comes from test code (a test-jar on the classpath once leaked M7's test application,
   with a fixed clock, into every API test's context), that each port is bound by
   `StaffIdentityWiring`, that there is one transaction manager and it is JPA's, that one entity
   maps `users`, and that Spring Security's proxy runs ahead of M6's filters. Two more tests cover
   the edges: `/api/docs` opens its two paths to `GET` and nothing else, and a record that is not an
   audit event reaches `fs.audit.events.dlq` on its first attempt.

## D-15 against FR-06-07's cache: decided (owner, 2026-10-17, option B)

M7's authenticator reads a key's record from PostgreSQL when its 2-second cache entry expires,
while D-15 and NFR-REL-02 require decisions to continue while PostgreSQL is down. CI showed the
conflict on 2026-10-17: M6's
`ResilienceApiTest.withPostgresDownDecisionsContinueAndArePersistedAfterRecovery` failed on the
assembled application, first with **401** (the database exception
escaped M7's filter, the error dispatch went through the contract chain, and the client was told
its key was bad), then, once the filter answered the outage itself, with 503.

The owner chose option B: **a key's cached record keeps being used while the database is
unavailable, for at most 10 minutes after the read that produced it**
(`fraudshield.auth.api-keys.stale-if-error`, default 10 minutes, `AuthProperties`).

**The rules are in [the API-key cache contract](../architecture/api-key-cache-contract.md)**
(2026-10-18). The code implements that document, which was written and independently reviewed
before the code. It was written because four code re-checks in a row had each found a MAJOR in
this cache.

On 2026-10-18 the owner tried to narrow the window to keys read shortly before the outage (option
2, a derived bound of 26 s). Its contract was found unsound in three drafts. Under the owner's
rule, option 2 was then abandoned for option 1, the 10-minute window below.

In short:

- **A key with a cached record is admitted through an outage** until 10 minutes after the read
  that produced the record.
  - It is read again once per TTL, counted from the end of the failed read.
  - Requests blocked in the same outage share one outcome.
  - A failed read that began before a fresher successful read is answered from the fresher record.
- **A key with no cached record is refused with 503 and `Retry-After: 1`, never 401.** That covers
  a key never read by this instance, one whose entry an announcement removed, and one past its 10
  minutes. Both filters answer it so.
- **Only database unavailability gives 503** (contract R8): connection failures, timeouts,
  shutdown, recovery and exhausted resources. Bad SQL and other errors give 500
  `urn:fraudshield:problem:internal`.
- **Announcements.** A received announcement is never undone. A read that overlapped any
  announcement answers its own request but is not installed. A successful read that sees the key
  revoked, or past its overlap, removes the entry.
- **Unauthenticated floods.** The failure limiter is checked before any read. Reads of uncached
  keys go through a bulkhead of 4 permits, which must be below the connection pool's size. So a
  flood of unknown key IDs can neither exhaust the pool nor push an instance into a local outage.
- **Residual risk (threat model R-13), per instance:** a revocation committed after the cached
  record's read, whose announcement this instance did not receive, is missed for up to 10 minutes
  after that read. That holds in any outage: one on every instance, or one local to this instance,
  where the others still commit. The contract lists the accepted costs.
- **D-15 therefore holds for outages up to 10 minutes.** `ResilienceApiTest` asserts both halves:
  decisions continue for the warmed key, and a key never verified gets 503 with `Retry-After`.

## Consequences

- `IngestApiTest`, `RateLimitApiTest`, `ResilienceApiTest` and `ApiDocsTest` now run on M7's real
  key verification: each test key is an `api_keys` row with the HMAC of its secret under the test
  pepper. The webhook registry stays a test bean there, because `api_keys.webhook_url` must be HTTPS
  and the test receiver is plain HTTP.
- The decision path's audit rows commit after the decision, by the spool's and the consumer's lag
  (ADR 0068, amendment).
- `AssembledApplicationTest` closes its application context after its class. Spring caches test
  contexts, and a cached assembled context kept its real SMS gateway and webhook registry in the
  same consumer groups as the other API tests' doubles, taking some of their partitions: those
  tests' SMS and webhooks went to the real collaborators and never arrived (CI, 2026-10-17).
- The API application needs M7's settings (`application.yml`, `fraudshield.auth.*`, from the
  environment); without any secret it refuses to start. M9 provides them.
- Staff-facing endpoints of the decision path that M6 expected M7 to call
  (`DecisionTransitionService.analystDecidesHold`, `CircuitBreakerPort.countConfirmedFraud`)
  belong to the analyst API (`decideAlert` and its neighbours), which no milestone builds: the
  owner decides where it goes.
