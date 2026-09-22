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

     This keeps the behaviour of the removed V12 trigger (which fired when an editable column
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
   - **A locked read reloads the row.** A locking query returns the instance the persistence
     context already holds and does not refresh it. The acting administrator's own account, read
     earlier in the transaction, would otherwise carry state from before the lock, and a flush
     would write it back over a concurrent password change, including the old hash and token
     version. So:
     - `findByIdForUpdate`, `applyAdminEdit` and the API-key `findForUpdate` refresh after
       locking;
     - both entities are `@DynamicUpdate`, so a flush writes only the columns that changed.

     Found by the independent review; test
     `HybridPersistenceTest.lockedReadReloadsSoAnEditCannotWriteBackStaleState`.
5. **Rule for M6 and later milestones.**
   - Use JPA for configuration tables: thresholds, rules, rule versions, circuit-breaker settings,
     models and similar.
   - Use explicit SQL, or batched `COPY`, for the decision hot path, the hypertables
     (`transactions`, `fraud_scores`, `shadow_scores`, `audit_events`) and every append-only table.
   - Every audit record goes through `AuditLog`.

## Consequences

- Two persistence styles live side by side, and the boundary is per table, not per request. The
  adapters document which one they use and why.
- New runtime dependencies from the Spring Boot BOM:
  - Hibernate ORM 7.4 (Apache-2.0).
  - Spring Data JPA.
  - `jakarta.persistence-api` 3.2.0 (EPL-2.0 OR BSD-3-Clause).
  - `jakarta.transaction-api` 2.0.1 (EPL-2.0 OR GPL-2.0 with Classpath exception).

  The two Jakarta APIs are the ADR 0020 case, with version-pinned licence exceptions.
- Hibernate adds startup time (schema validation) and memory. Neither is on the transaction
  decision path.
- Anyone adding an entity over an append-only or hypertable table must mark it `@Immutable` and
  justify it here; code review enforces this.
