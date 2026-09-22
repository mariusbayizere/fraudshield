# audit

Append-only, hash-chained audit log (FR-06-06, D-32), tenant transactions and the verification
command.

**Purpose.**

- `AuditLog` / `JdbcAuditLog` write `audit_events` inside the caller's transaction, so a change and
  its audit record commit together. The database trigger (V8) assigns `seq`, `prev_hash` and
  `row_hash`.
- `TenantTransactions` runs work in a transaction bound to one institution
  (`set_config('fraudshield.institution_id', …, true)`) and retries the chain's SQLSTATE 40001
  refusals.
- `AuditAnchorService` makes the daily Ed25519-signed Merkle anchors over each writer partition's
  new rows (ADR 0070 §10). It is enabled with `fraudshield.audit.anchor.enabled=true` plus a
  signing key.
- `fraudshield audit verify --from YYYY-MM-DD --to YYYY-MM-DD` (`cli.AuditCli`) runs as
  `fs_compliance_ro`. It checks anchor signatures, recomputes anchors and re-hashes the chain.
  Connection and keys come from the environment: `FRAUDSHIELD_DB_URL`,
  `FRAUDSHIELD_DB_COMPLIANCE_USER`, `FRAUDSHIELD_DB_COMPLIANCE_PASSWORD` and
  `FRAUDSHIELD_AUDIT_ANCHOR_PUBLIC_KEYS=keyId=path.pem,…`. Exit code 0 means verified, 1 means
  tampering found, 2 means a usage error.

**Boundaries.** The root package is framework-free (`ArchitectureTest`); `jdbc`, `anchor`, `cli`
and `config` are adapters. The anchor job and the verifier read hashes only, through the V12
functions `audit_chain_head` and `audit_chain_hashes`.

**Test.** `./mvnw -pl audit verify`. Database tests are tagged `requires-docker` and use a
TimescaleDB container, or the server named by `FRAUDSHIELD_TEST_POSTGRES_URL`.
`testing.TestDatabase` is shared with the auth and admin tests through the test jar.
