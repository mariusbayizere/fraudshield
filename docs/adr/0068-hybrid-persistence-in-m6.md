# 0068 — Hybrid persistence in M6: JPA for configuration and CRUD, explicit SQL for the rest

- **Status:** Accepted (owner decision, 2026-09-22)
- **Date:** 2026-09-22
- **Requirements affected:** FR-01-06, FR-05-05, FR-05-07, E.6; and M6's persistence as a whole
- **Defects referenced:** D-13, D-14, D-20
- **Builds on:** ADR 0017 (data model and database security), ADR 0071 (the same rule, adopted by
  M7, which states it), ADR 0061 (decision semantics), ADR 0064 (spool), ADR 0067 (idempotency)

## Context

The owner asked for the persistence layer common in large payment systems: an ORM for the ordinary
create-read-update domain, hand-written SQL where write order, append-only guarantees or
database-specific features matter. M7 adopted it first and wrote it down as ADR 0071; the owner
restated it for M6, which owns this ADR and this branch's work. Both backends should look alike.

## Decision

1. **Spring Data JPA with Hibernate for M6's configuration and CRUD data.** Two cases exist here:
   - **The risk configuration** (`risk_thresholds`, `risk_threshold_versions`, `alert_rules`,
     `alert_rule_versions`, `mcc_circuit_breaker_settings_versions`). `JpaConfiguration` replaces
     `JdbcConfiguration`; everything above the port is unchanged, including the snapshot semantics,
     the SRS defaults and the skipping of a rule that no longer compiles.
   - **Batch jobs** (`batch_jobs`), the decision path's one mutable CRUD row: created, moved
     through its states, read by the client polling it.

   The demo seeder writes institutions, staff users and risk configuration through repositories
   too (point 4).

2. **Explicit SQL stays** where the rule requires it, each case with its reason:
   - **The synchronous decision path.** Idempotency claims, the frozen-account probe, the freeze
     count, decision-state reads and writes: per-request latency, and each is a single statement
     with no object graph.
   - **Every append-only table.** `transaction_ids`, `decision_states`, `auto_block_events`,
     `account_freeze_events`, `customer_notifications`, `customer_verifications`,
     `customer_verification_responses`, `unblock_events`, `label_events`, `alert_decisions`,
     `mcc_circuit_breaker_events`, `batch_job_items`. Hibernate's change tracking writes an
     `UPDATE` for any managed entity whose state changed, which these tables' triggers refuse.
   - **The hypertables** `transactions` and `fraud_scores`, written in bulk by the spool's
     PostgreSQL sink and read through security-barrier views.
   - **The audit chain.** Every audit record goes through the audit writer; its correctness depends
     on rows being inserted exactly where the code says, in the transaction of the change they
     record, and Hibernate reorders and batches at flush time.
   - **The PII vault** (D-20, ADR 0065): a separate instance, a separate role, values that must
     never be cached in a persistence context or written by change tracking.
   - **PostgreSQL features with no clean JPA expression:** `set_config` for tenant isolation,
     `ARRAY` columns (API-key scopes, reason codes), `ON CONFLICT` upserts, `SELECT … FOR UPDATE
     SKIP LOCKED` and advisory locks in the webhook dispatcher, and the SECURITY DEFINER lookups
     that run before an institution is known.

3. **The append-only tables that JPA does read are `@Immutable`.** Every configuration table except
   `alert_rules` is append-only: a new configuration is a new version, which is what the schema has
   said since V6. `@Immutable` stops Hibernate from ever writing an `UPDATE` for them; the
   append-only trigger would refuse it, and the failure would surface at a refresh rather than at a
   review.

4. **The demo seeder** (`backend/persistence`, which neither M6 nor M7 owns outright). Its
   institution, user and risk-configuration inserts are repositories; its audit event stays
   explicit SQL for the reason in point 2, and so does its API key, whose scopes are an ARRAY and
   whose table M7's staff module owns. `DemoUserEntity` is a stand-in for M7's `StaffUserEntity`:
   at merge it should be deleted and the seeder pointed at M7's entity. `M6_updates.md` records
   this, so the other milestone sees it.

5. **Hibernate configuration, each part tested.**
   - `spring.jpa.hibernate.ddl-auto=validate`: Flyway owns the schema (ADR 0017). Hibernate checks
     its mappings at start-up and creates nothing. `PersistenceSettingsGuard` refuses `update`,
     `create` and `create-drop`, including through `hibernate.hbm2ddl.auto`. It earned its keep
     immediately: it refused the first mapping of `institutions.country`, which is `char(2)`.
   - `spring.jpa.open-in-view=false`: no persistence context outlives its transaction, so no lazy
     load can run on a connection whose institution was never set — row-level security would then
     answer with nothing rather than refuse. The guard refuses `true`.
   - **No N + 1.** No association is navigated: one configuration load is three statements
     (thresholds, rules, breaker settings) whatever the number of channels or rules, asserted with
     Hibernate statistics, which also assert no lazy entity or collection fetch.
   - **Row-level security still holds.** `TenantTransactions` sets `fraudshield.institution_id`
     with `set_config(..., true)` at the start of each transaction. The transaction manager is a
     `JpaTransactionManager`, which lends its JDBC connection to `JdbcTemplate`, so the setting and
     every Hibernate statement run on one connection in one transaction; being transaction-local,
     it never travels with a pooled connection.

6. **Write order in mixed transactions** (M7's lessons, followed rather than rediscovered): the
   persistence context is flushed before any explicit-SQL statement that reads what JPA has just
   written — the seeder's API key and audit event read the user and institution rows it saved. The
   institution row is written before the tenant is set, because the `institutions` policy keys on
   `id`. M6 has no bulk JPQL update and no pessimistic lock; where either arrives, ADR 0071's rules
   apply (advance `@Version` explicitly, lock by `refresh(entity, PESSIMISTIC_WRITE)`).

7. **Entities live in `backend/persistence`**, beside the migrations they mirror, so one mapping
   serves the seeder that writes a configuration version and the decision path that reads it. The
   repositories live with their callers. The application class is in the root package, so Boot's
   auto-configuration package already covers both; `@EnableJpaRepositories` is deliberately not
   used, because it would replace that scanning and could switch off another module's
   repositories.

8. **Licence exceptions** (owner decision, recorded verbatim from M7 so the branches merge):
   `jakarta.persistence-api` 3.2.0 elects **BSD-3-Clause**, `jakarta.transaction-api` 2.0.1 elects
   **EPL-2.0**, both version-pinned in `tools/src/fraudshield_tools/licences.py`.

## Consequences

`PostgresAdaptersTest` (configuration by version, SRS defaults, a rule that no longer compiles,
and one load is three statements with no lazy fetch), `PersistenceSettingsTest` (the refused
settings and the ones the application ships), `IngestApiTest` (the application boots with
`validate`, and a 1,000-item batch runs through the JPA job), `RedisOutageTest`,
`DemoDataSeederTest`, `SyntheticDataGuardTest`, `DatabaseSecurityTest` and `SchemaPoliciesTest`.

The decision module now depends on the persistence module for the entities. Hibernate 7.4 and
Spring Data JPA 4.1 enter the runtime classpath; the API's start-up gains a mapping validation,
which is a check, not a cost worth measuring. The hot path is unchanged: it never touches an
entity.
