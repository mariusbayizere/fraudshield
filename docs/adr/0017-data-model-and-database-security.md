# 0017 — Data model and database security

- **Status:** Accepted
- **Date:** 2026-09-17
- **Requirements affected:** D-30, D-31, D-32, FR-06-06, NFR-SEC-05, FR-03-08; M1 gate (roles, RLS,
  append-only, audit chain)
- **Implementation:** `backend/persistence` (`db/bootstrap/bootstrap.sql`, `db/migration/V1..V11`);
  tests `DatabaseSecurityTest`, `DemoDataSeederTest`

## Context

The SRS schema has mutable columns on "immutable" tables (D-30), lacks tables that functional
requirements need (D-31), and protects the audit log with permissions only (D-32). Build prompt C.3
asks for multi-tenancy by `institution_id` with PostgreSQL row-level security (RLS), least-privilege
roles and tamper evidence. The time-series tables use TimescaleDB (ADR 0018).

## Decision

### Roles and ownership

`bootstrap.sql` (run once by a superuser, idempotent) creates four login roles, none of them
superuser, `CREATEDB`, `CREATEROLE`, `REPLICATION` or `BYPASSRLS`:

| Role | Use | Rights |
|---|---|---|
| `fs_migrator` | Flyway | owns the `fraudshield` schema and every object in it |
| `fs_app` | API, scoring consumers, jobs | per-table least privilege (V11) |
| `fs_app_readonly` | reporting, support | `SELECT` on application tables and tenant views |
| `fs_compliance_ro` | compliance reviews | `SELECT` on audit, decision and configuration history; `verify_audit_chain` |

`PUBLIC` loses every right on the database, the `public` schema and the `fraudshield` schema. Passwords
are never in migrations: Compose sets them from `.env` (`20-fraudshield-roles.sh`), tests generate
random ones. The migrations run as `fs_migrator`, never as a superuser.

### Tenant isolation

- `current_institution()` reads the transaction setting `fraudshield.institution_id`. When it is not
  set, it returns NULL, so every tenant-scoped read returns no rows and every write fails: a missing
  setting fails closed.
- Ordinary tenant tables enable RLS with one policy, `institution_id = current_institution()`, for
  both `USING` and `WITH CHECK`.
- Cross-tenant references are impossible by construction: every foreign key between tenant tables,
  self-references such as `api_keys.replaced_by` included, is composite on `(id, institution_id)`.
  A single-column key would ignore RLS, so it could point at another institution's row, and its
  error would reveal that the row exists. `everyForeignKeyBetweenTenantTablesIncludesTheInstitution`
  checks the catalogue (review MAJOR-1). Global tables (models, training datasets) may still
  reference `users (id)`.
- Trust assumption: every application role can set `fraudshield.institution_id`, so the database
  binds a connection to a tenant only as far as the service sets it correctly. Services set it in one
  place from the authenticated principal (M2).
- Authentication runs before the tenant is known (sign-in by email, API key, refresh cookie,
  email-verification and customer-verification links). Narrow `SECURITY DEFINER` functions
  (`auth_find_*`, `verification_find_by_token`) with a fixed `search_path` return only ids, the
  institution and what the check needs. The caller then sets the institution.
- **TimescaleDB hypertables cannot use RLS.** TimescaleDB rejects columnstore compression on a table
  with row security (`columnstore cannot be used on table with row security`), and compression is
  required (D-32, ADR 0018). Hypertables (`transactions`, `fraud_scores`, `shadow_scores`,
  `audit_events`) are isolated instead by:
  1. a `BEFORE INSERT` trigger `tenant_insert_guard` that rejects rows for another institution;
  2. a `security_barrier` view `v_<table>` filtered by `current_institution()`;
  3. no application role having `SELECT` on the hypertable itself.

  Continuous aggregates are exposed the same way (`v_account_activity_hourly`,
  `v_merchant_activity_15m`).
- **Documented exception:** `outbox_events` has no RLS. The relay publishes for every institution,
  payloads are Kafka envelopes with tokens and no personal data (ADR 0012), and only `fs_app` has
  access.

### Append-only tables (D-30)

- Event-sourced splits replace mutable "immutable" tables. For example, `auto_block_events` holds only
  facts known at block time. Lifecycle facts go to `customer_notifications`, `unblock_events` and
  `account_freeze_events`, and `v_auto_block_status` derives the current state. The same pattern
  applies to alert decisions (commit and undo tables), rule versions and threshold versions.
- Append-only tables get `INSERT` and `SELECT` grants only. They also get `forbid_modification`
  triggers for `UPDATE`, `DELETE` and `TRUNCATE`, which also stop the owner's ordinary statements, so
  a mistaken grant or migration cannot modify history. They do not stop the owner from disabling
  triggers, truncating TimescaleDB chunks directly or dropping chunks; for the audit log the hash
  chain and anchors detect that. `sar_reports` is frozen by trigger after sign-off.

### Audit hash chain (D-32)

- `audit_events` accepts exactly the 12 `event_type` values of D-32, each with an `action`.
- A `SECURITY DEFINER` trigger per writer partition (0–63) locks that partition's `audit_chain_heads`
  row and assigns `seq`, `prev_hash` and `row_hash`. `row_hash` is SHA-256 of a canonical JSONB array
  of the row's columns (`audit_row_hash`). The application cannot supply or skip these values, and
  concurrent writers to one partition are serialised.
- **Time dimension (review MAJOR-2).** The hypertable is partitioned, compressed and retained by
  `recorded_at`, the inserting transaction's start time, not by the writer-chosen `event_at`
  (business time, which may be old for replayed or delayed events). Partitioning by `event_at` would
  let retention drop a backdated row from the middle of a chain, which verification cannot tell apart
  from tampering. TimescaleDB routes a row to its chunk before BEFORE triggers run, so the trigger
  cannot assign the time itself. Instead it:
  - rejects any `recorded_at` other than `now()`;
  - raises `serialization_failure` (the writer retries) when the transaction started before the last
    row of its chain.

  So `recorded_at` never decreases along a chain, and time-based retention can only remove a prefix.
- `verify_audit_chain(partition, after_seq, after_hash)` (`SECURITY DEFINER`, returns positions only,
  never content) reports sequence gaps, broken links, altered rows and a head ahead of stored rows.
- `audit_anchors` (append-only) stores the daily signed Merkle root per partition. The signing job and
  the `fraudshield audit verify` CLI belong to the audit service milestone; the schema and
  verification primitive are M1.
- Compression after 30 days, retention 7 years (ADR 0018).

### Other D-31 tables and constraints

All tables listed in D-31 exist, with CHECK constraints for enumerations and formats, partial unique
indexes for "at most one" rules (one open configuration change per kind, one production, shadow and
previous-production model, one active retraining job), and database-side guards for dual control
(`config_changes`: a reviewer can never be the proposer, and status transitions are checked; ADR 0014).

## Consequences

- Every transaction must start with `SET LOCAL fraudshield.institution_id`. Code that forgets it reads
  nothing and cannot write, rather than leaking data. The persistence layer (M2) must set it in one
  place.
- Hypertable reads must use the `v_` views. A test asserts that no application role can select from
  a hypertable.
- Table owners bypass RLS unless it is forced. Only `fs_migrator` owns tables and it never serves
  requests. The demo seeder runs as `fs_migrator` and sets the institution explicitly.
- The audit trigger serialises writers per partition. Throughput scales with the number of partitions
  in use (up to 64), measured in the audit milestone.
- Verification: `DatabaseSecurityTest` (Testcontainers, `requires-docker`) runs in the CI `java` job.
  It covers role attributes, grants, RLS read and write isolation, view isolation, append-only
  triggers, chain tamper and gap detection, compression and the uniqueness rules. The CI stack job
  also checks the migrated Compose database (`check-seeded-stack.sh`).
