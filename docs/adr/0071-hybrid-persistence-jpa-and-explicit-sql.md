# 0071 — Hybrid persistence: JPA for the CRUD domain, explicit SQL for chains and hypertables

- **Status:** Accepted (owner decision, 2026-09-22)
- **Date:** 2026-09-22
- **Requirements affected:** FR-06-01, FR-06-02, FR-06-06, FR-06-07, FR-07-01 … FR-07-09, D-27,
  D-32; and, by rule, M6's configuration and decision persistence
- **Defects referenced:** D-27, D-30, D-31, D-32
- **Builds on:** ADR 0017 (data model and database security), ADR 0070 (M7 implementation)

## Context

M7 was first written with Spring `JdbcTemplate` throughout. The owner asked for the persistence
layer common in large payment systems: an ORM for the ordinary create-read-update domain, and
hand-written SQL where write order, append-only guarantees or database-specific features matter.
The schema has to stay Flyway's (ADR 0017), with row-level security per institution, append-only
triggers and a hash-chained audit log.

## Decision

1. **Spring Data JPA with Hibernate 7 for M7's CRUD domain.** It covers staff users (including
   approvals), API-key records for the administrator lifecycle, and the office IP allowlist.
   - Entities live in `auth.persistence` (`StaffUserEntity`, `ApiKeyEntity`,
     `OfficeIpRangeEntity`), with Spring Data repositories next to them.
   - The existing adapters (`StaffAccountRepository`, `ApiKeyRepository`, `OfficeIpAllowlist`)
     keep their public methods, so the services and every test above them are unchanged. The
     services still see the framework-free domain record `StaffAccount`.
   - The package is registered as an auto-configuration package rather than with
     `@EnableJpaRepositories`, so an application's own entity and repository scanning stays intact.
2. **Explicit SQL (`JdbcTemplate`) stays** for:
   - the audit hash chain and anchors, and everything append-only;
   - the TimescaleDB hypertables and their tenant views;
   - the SECURITY DEFINER lookups that run before an institution is known (email, API key,
     refresh token, verification token, employee ID);
   - the refresh-token family and the one-time codes.

   The reasons:
   - Hibernate's change tracking (dirty checking) writes an `UPDATE` for any managed entity whose
     state changed. On an append-only table that is at best an error from the append-only trigger,
     and at worst a mutation where a grant is wrong. Any future JPA entity over an append-only
     table must be `@Immutable`. None exists in M7.
   - The audit chain's correctness depends on each row being inserted exactly when the code says,
     in the transaction of the change it records. Hibernate reorders and batches its own writes at
     flush time. Explicit SQL runs where it is written.
   - Refresh-token revocation is a set-based `UPDATE … WHERE family_id = ?`. The race fix of ADR
     0070 depends on its exact statement order after the account row lock.
   - Hypertables are written in bulk and read through security-barrier views, neither of which
     JPA models well.
3. **Hibernate configuration**, each part tested:
   - **`spring.jpa.hibernate.ddl-auto=validate`.** Flyway owns the schema; Hibernate checks its
     mappings at startup and never creates or alters anything.
     - Set as a lowest-precedence default by `PersistenceDefaults`.
     - `PersistenceSettingsGuard` refuses `update`, `create` and `create-drop` (also through
       `hibernate.hbm2ddl.auto`).
     - Tests: `PersistenceSettingsTest`, and
       `HybridPersistenceTest.schemaValidationRefusesMappingsThatDoNotMatchFlyway` (a dropped
       column fails startup and is not re-added).
   - **`spring.jpa.open-in-view=false`.** No persistence context outlives the service
     transaction, so no lazy load can run outside the tenant transaction. The guard refuses
     `true`. Test: `QueryCountTest.applicationRunsWithValidateAndWithoutOpenInView`.
   - **No N + 1.** Associations are lazy. The office-range list fetches its creators through an
     `@EntityGraph`, so one query serves any number of ranges; the user, approval and API-key lists
     have no associations. `QueryCountTest` uses Hibernate statistics to check that each listing
     endpoint's statement count does not grow with the rows listed, and that it makes no lazy
     entity or collection fetch.
   - **Row-level security still holds.** `TenantTransactions` sets `fraudshield.institution_id`
     with `set_config(..., true)` (transaction-local, the effect of `SET LOCAL`) at the start of
     each transaction. With JPA the transaction manager is a `JpaTransactionManager`, which exposes
     its JDBC connection to `JdbcTemplate`, so the setting and every Hibernate statement run on the
     same connection in the same transaction. Tests:
     - `HybridPersistenceTest.jpaQueriesAreTenantIsolatedAndFailClosedWithoutTenant`: another
       institution's row is invisible to Hibernate; without a tenant nothing is visible; a row for
       another institution cannot be written.
     - The persistence module's isolation tests and the admin cross-institution tests all still
       pass.
   - **`users.version` is a JPA `@Version`.** Hibernate increments it on every entity update and
     adds it to the `WHERE` clause, so a write based on an old version fails. Administrator edits
     go through the entity and advance it. Sign-in bookkeeping uses bulk JPQL updates:
     - They advance the version exactly when the status changes: a failure lock, an unlock, or a
       sign-in or password change that lifts a lock. An administrator can therefore never act on a
       status they have not seen, such as unlocking a brute-force lock by resending ACTIVE.
     - They leave it alone for the failure count, last sign-in, token version, email verification
       and Google link, so an ordinary sign-in never makes an administrator's edit stale.

     This keeps the behaviour of the removed V70 trigger (which fired when an editable column
     changed); two writers of one version column would disagree. One deliberate difference: an
     entity edit that only clears the failure count or moves a lock's end also advances it. The
     API still returns the version as an `ETag` (the contract is frozen, ADR 0070 §11). Tests:
     - `HybridPersistenceTest.versionIsJpaOptimisticLock`;
     - `HybridPersistenceTest.signInBookkeepingDoesNotAdvanceTheVersion`;
     - `HybridPersistenceTest.statusChangesAdvanceTheVersionAsTheRemovedTriggerDid`;
     - `UserAdministrationTest.roleChangeEndsTheOldTokenAndStaleVersionsConflict`.
4. **Write order in mixed transactions.**
   - The JPA adapters flush at the end of every write, so explicit-SQL statements that follow in
     the same transaction see them. Examples are the email-verification token that references a
     new user, and the refresh token of a new session.
   - Bulk updates flush before and clear the persistence context after, so no managed entity is
     left stale.
   - Pessimistic locks (`@Lock(PESSIMISTIC_WRITE)`) keep the account-then-token order of ADR 0070
     finding 1.
   - **Rows are locked by refresh, not by a locking query.** `StaffAccountRepository.lockCurrent`
     and `ApiKeyRepository.findForUpdate` run `find` and then
     `refresh(entity, PESSIMISTIC_WRITE)`, which issues `SELECT … FOR UPDATE` and replaces the
     held state, version included. A locking query is not enough when the transaction already
     holds the entity, which is the case when the acting administrator edits their own account:
     - if the version is unchanged, Hibernate returns the copy read before the lock, and later
       decisions (status, lock state, token version, the ETag check) run on stale state;
     - if the version moved in between, Hibernate 7 rejects the row ("conflicting version of entity
       already held in persistence context"), and the request fails with a 500 instead of a 409.

     Both entities are also `@DynamicUpdate`, so a flush writes only the columns that changed.
     History:
     - The independent review found the write-back form (a full-row flush restoring an old
       password hash and token version).
     - `24016f9` added a refresh *after* the locking query. A mutation that removed only that
       refresh survived, because `@DynamicUpdate` already stopped the write-back.
     - The owner asked whether a stale *read* after locking could still drive a wrong decision.
       It could: `UserAdministrationService.update` resolves the actor before locking the target,
       and for a self-edit they are the same row. The test written for that path,
       `UserAdministrationTest.selfEditRacingFailureLockDecidesOnTheLockedRow`, holds the row
       lock to force the interleaving. It also showed that the refresh after the query never ran
       in this case, because the query itself threw. Fixed in `8f6bdba`.

     Mutations, now killed:
     - lock without reload (R1): killed by the self-edit race test;
     - reload without lock (R2): killed by `RefreshRaceTest`;
     - API-key lock removed (R3): killed by
       `ApiKeyLifecycleTest.concurrentRotationsOfOneKeyIssueOneReplacement`.
   - `lockActiveAdmins` is still a locking query. It is the first statement of its transaction, so
     no administrator entity is held yet. Code that adds an earlier read must lock by refresh
     instead.
5. **Rule for M6 and later milestones.**
   - Use JPA for configuration tables: thresholds, rules, rule versions, circuit-breaker settings,
     models and similar.
   - Use explicit SQL, or batched `COPY`, for the decision hot path, the hypertables
     (`transactions`, `fraud_scores`, `shadow_scores`, `audit_events`) and every append-only table.
   - Every audit record goes through `AuditLog`.

6. **Staleness bounds, derived rather than measured** (owner decision 2026-09-22). The 5 s gates
   for token-version invalidation (D-27) and API-key revocation (FR-06-07) rest on analysis, not on
   a measurement. At `85aff03` the worst case measured 4,620 ms on a loaded laptop, too thin a
   margin to rest on.

   *Definitions.*
   - A change commits at time *c*. Its pub/sub announcement and (for sessions) its Redis write are
     lost.
   - A **stale answer** is one given from a value read from the database before *c*.
   - The bound is the latest time after *c* at which any instance can still give a stale answer.
   - After that time, the next check reads the database, which has the change. That request's
     own processing (one read) is the "check path". It adds latency to that one response, not
     staleness.

   *API-key cache (one tier, TTL T = 2 s).*
   - `credential()` takes `now` from the monotonic clock *before* the database read, and caches
     the record until `now + T`.
   - A stale record was read before *c*, so `now` < *c*, and it expires before *c + T*. The read's
     duration does not appear.
   - **Bound: T = 2 s**, exposed as `ApiKeyAuthenticator.worstCaseStaleness()`.

   *Session cache, as it was (Redis TTL R = 2 s, in-process TTL L = 1 s).*
   - `load()` set both TTLs *after* the read.
   - An instance could refill its in-process copy from Redis just before the Redis entry expired
     and keep it for a further L.
   - So the bound was R + L + δ, where δ is the time from the read to the Redis write. δ has no
     bound: garbage-collection pauses, scheduling, a slow Redis. That is the 4,620 ms (3 s plus
     about 1.6 s of δ).
   - A bound containing an unbounded term cannot be asserted independently of machine load, so the
     design was changed rather than the TTL:
     - `load()` takes its anchors *before* the read. The in-process copy lives until
       `start + L`. The Redis value carries the wall-clock deadline `start + R`, and it is not
       written at all if that deadline has already passed.
     - A reader accepts a Redis value only before its deadline, and caps its in-process copy at
       that deadline: `min(L, deadline − now)`.
     - Every stale copy therefore descends from a read that began before *c*, and dies by that
       read's start plus max(R, L).
   - **Bound: max(R, L) + σ = 2 s + σ**, exposed as `SessionStateCache.worstCaseStaleness()`. σ is
     the wall-clock skew between instances, because the deadline written by one instance is read
     by another. With NTP-synchronised hosts, σ is milliseconds.

   *The TTL rule.* The owner's rule is to lower the TTL if the bound plus a reasonable scheduling
   allowance comes within 1 s of the 5 s gate.
   - With a 1 s allowance: sessions 2 s + σ + 1 s ≈ 3 s; API keys 2 s + 1 s = 3 s. Both leave
     about 2 s of margin, so **the TTLs stay at their defaults**.
   - The old session design (3 s + δ + 1 s ≥ 4 s) would have triggered the rule, and a lower TTL
     would not have removed δ. The anchoring does.

   *Tests (fake time, so the bound is asserted to the millisecond whatever the load).*
   - `FakeTime` drives both clocks. The database read is made slow *for real*: a held
     `ACCESS EXCLUSIVE` table lock blocks the read after its anchors are taken, while fake time
     advances by 0, 0.5, 1.5 or 2.5 s.
   - `SessionInvalidationTimingTest.staleAnswersEndWithinTheAnalyticBoundHoweverSlowTheRead` and
     `ApiKeyLifecycleTest.revokedKeyIsRefusedWithinTheAnalyticBoundHoweverSlowTheRead` require a
     stale answer 1 ms inside the bound (so the test is not vacuous) and a refusal at the bound
     and at every probe after it.
   - `SessionInvalidationTimingTest.staleVersionCannotOutliveTheBoundThroughAnotherSession` covers
     the one path where the version tier acts alone. Another instance caches a second session of
     the account in Redis after the change, so only the version copy can be stale.

   *Mutations, all killed:*
   - B1: version tier anchored after the read, killed only by the second-session test (it
     survived the first one; see the lab notebook, 2026-09-22);
   - B2: deadline ignored;
   - B3: in-process copy not capped at the deadline;
   - B4: deadline taken after the read;
   - B5: API-key expiry anchored after the read.

   *Re-measured:* with the announcement and Redis write lost, 20 runs gave p50 1,970 ms and max
   2,030 ms. Before, the figures were p50 2,009 ms and max 4,620 ms.

## Consequences

- Two persistence styles live side by side, and the boundary is per table, not per request. The
  adapters document which one they use and why.
- New runtime dependencies from the Spring Boot BOM:
  - Hibernate ORM 7.4 (Apache-2.0).
  - Spring Data JPA.
  - `jakarta.persistence-api` 3.2.0, offered as EPL-2.0 OR BSD-3-Clause. **The project elects
    BSD-3-Clause**, the permissive option.
  - `jakarta.transaction-api` 2.0.1, offered as EPL-2.0 OR GPL-2.0 with the Classpath exception.
    **The project elects EPL-2.0**, used unmodified as a runtime dependency.

  The two Jakarta APIs are the ADR 0020 case. Their version-pinned licence exceptions state the
  elected licence as the expression the tool checks (owner approval, 2026-09-22).
- Hibernate adds startup time (schema validation) and memory. Neither is on the transaction
  decision path.
- Anyone adding an entity over an append-only or hypertable table must mark it `@Immutable` and
  justify it here; code review enforces this.
