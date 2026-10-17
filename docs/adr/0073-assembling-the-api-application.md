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
     during a rotation overlap.
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
   - **with the actor's name and role** looked up when the event names a staff member, as every
     audit row stores them;
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
   `StaffIdentityWiring`, that there is one transaction manager and it is JPA's, and that one entity
   maps `users`.

## Open conflict, for the owner: D-15 against FR-06-07's cache

M7's authenticator reads a key's record from PostgreSQL when its 2-second cache entry expires.
D-15 and NFR-REL-02 require decisions to continue while PostgreSQL is down, and M6 built and
tested that (`ResilienceApiTest`). In the assembled application a PostgreSQL outage longer than two
seconds therefore refuses ingestion at authentication. CI showed it on 2026-10-17: M6's
`ResilienceApiTest.withPostgresDownDecisionsContinueAndArePersistedAfterRecovery` fails on the
assembled application. It first failed with **401**: the database exception escaped M7's filter,
the servlet container's error dispatch went through the contract chain, and the chain refused it
as unauthenticated, so the client was told its key was bad. The filter now answers **503 with
`Retry-After: 1`** when the key store cannot be read (`ApiKeyAuthenticationFilterTest`), which is
right under either option; the test still fails, because it expects 200. The two options:

- **keep refusing:** strict revocation, ingestion down with the database;
- **serve a key's last verified record while the database cannot answer**, for a bounded time:
  ingestion continues, at the price that a revocation committed in the last two seconds before the
  outage, whose Redis announcement was also lost, is honoured only when the database returns. No
  revocation can be committed during the outage itself.

This ADR does not choose; threat model R-13 records the risk until the owner does.

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
