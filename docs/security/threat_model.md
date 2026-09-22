# FraudShield threat model

- **Method:** STRIDE per component and per data flow (build prompt D-28, I.3). S = spoofing,
  T = tampering, R = repudiation, I = information disclosure, D = denial of service, E = elevation of
  privilege.
- **Version:** M1 (database, contracts, demo seeding). Updated at every milestone that adds a component
  or a data flow; each milestone review checks this file against the change (M0 review condition M-1).
- **Status legend:** *implemented* (control exists and is tested), *contracted* (fixed in the
  OpenAPI, Kafka or proto contract, implemented later), *planned* (milestone named).

## 1. Assets

| Asset | Why it matters |
|---|---|
| Customer PII (names, phone numbers, account numbers) | Privacy law (Rwanda DPL 058/2021); lives only in the PII vault (D-20); everything else holds tokens |
| Transactions, scores and decisions | Fraud losses, customer harm when wrong, regulatory evidence |
| Audit log | BNR evidence; must be tamper-evident (D-32) |
| Risk configuration (thresholds, circuit breakers, MEDIUM timeout policy) | Loosening lets fraud through; dual control (ADR 0014) |
| Staff credentials, sessions, API keys, webhook signing secrets | Account takeover, forged ingestion or decisions |
| Models and training data | Poisoning or theft changes decisions |
| Tenant separation | One institution must never see another's data (ADR 0017) |

## 2. Trust boundaries and data flows

```
 Bank systems ──(1) HTTPS + API key──▶ API ──(3) gRPC──▶ scoring service
 Staff browser ─(2) HTTPS + JWT/cookie─▶ API ──(4) SQL (fs_app, tenant set)──▶ PostgreSQL/TimescaleDB
 Customer ──(5) verification link──▶ verify-web          API ──(6) outbox → Kafka──▶ consumers
 API ──(7) signed webhook──▶ bank endpoint               jobs ──(8) SQL──▶ PII vault (separate DB)
 Developer ──(9) make seed-demo──▶ local DB             CI ──(10) build, test, scan──▶ GitHub
```

Boundaries: internet ↔ API; API ↔ data stores (cluster network); tenant ↔ tenant inside the database;
developer machine ↔ public repository.

## 3. Components

### 3.1 PostgreSQL + TimescaleDB (M1: implemented)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| S | Application connects with an over-privileged role | Four roles, none superuser/CREATEROLE/CREATEDB/REPLICATION/BYPASSRLS; only `fs_migrator` owns objects; test `applicationRolesHaveNoElevatedAttributesAndOwnNothing` | implemented |
| S | Default or committed database passwords | Passwords only from git-ignored `.env` (random per machine) or test-generated; none in migrations | implemented |
| T | Application rewrites history (decisions, blocks, labels, configuration versions, audit) | INSERT/SELECT grants only, plus `forbid_modification` triggers that also stop the owner; tests per table | implemented |
| T | Audit row altered, deleted or reordered by someone with storage access | SHA-256 hash chain per writer partition assigned by a SECURITY DEFINER trigger; `verify_audit_chain` detects altered rows, gaps and a head ahead of rows; test with tampering and gaps, and across compression | implemented |
| T | Backdated audit rows dropped by retention from the middle of a chain (denial of verification) | Chunks and retention follow the database-assigned `recorded_at`, which never decreases along a chain (`retentionFollowsTheChainOrderNotTheWritersEventTime`) | implemented |
| T | Whole chain rewritten consistently by a database superuser | Daily signed Merkle-root anchors in `audit_anchors` (schema M1); signing key outside the database | planned (audit service) |
| R | Staff deny a decision or configuration change | Audit events for every decision and dual-control transition with user, role and before/after values; append-only | implemented (schema), planned (writers M6, M7) |
| I | Tenant A reads tenant B's rows | RLS on every tenant table, fail-closed `current_institution()`; hypertables and continuous aggregates via security-barrier views with no direct SELECT; composite FKs on every tenant-to-tenant reference prevent cross-tenant references and existence oracles; tests `institutionsSeeOnlyTheirOwnRows`, `withoutAnInstitutionNothingIsVisibleOrWritable`, `referencesCannotCrossInstitutions`, `everyForeignKeyBetweenTenantTablesIncludesTheInstitution`, `everyTenantTableIsIsolated` | implemented |
| I | Forgotten tenant setting leaks everything | Unset setting returns no rows and rejects writes | implemented |
| I | Pre-authentication lookups become a cross-tenant oracle | `auth_find_*` and `verification_find_by_token` return ids and the fields the check needs only; lookup by exact email, key id or token hash | implemented |
| I | Outbox (no RLS) exposes other tenants | Only `fs_app` has access; payloads carry tokens, no PII (ADR 0012); documented exception | implemented |
| I | Read-only or compliance roles read credential material | `fs_compliance_ro` has no access to `users`, tokens or keys; `fs_app_readonly` gets column grants without password hashes, token hashes, API-key HMACs, webhook secrets or OAuth ids; test `readOnlyRolesCannotReadCredentialMaterial` (a full-table grant makes it fail) | implemented |
| D | Audit chain serialisation limits throughput | Up to 64 writer partitions; measured in the audit milestone | planned |
| E | SECURITY DEFINER function abused through `search_path` | Fixed `search_path = fraudshield, pg_temp`; EXECUTE revoked from PUBLIC and granted per role | implemented |
| E | Timescale licence position lost by exposing SQL to tenants | No tenant SQL access, no CREATE for application roles (ADR 0018 invariant) | implemented |

### 3.2 Demo seeding and synthetic-data banner (M1: implemented)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| S | Known demo accounts in a production database | `DemoSeedGuard` stops startup when seeding is enabled outside `dev`/`demo`; `@Profile` on the seeder; test under `prod`, `staging`, `default`, mixed and no profile | implemented |
| S | A production service started later against a database that was seeded earlier | `institutions.synthetic` marker; `SyntheticDataGuard` (a `spring.factories` listener with its own JDBC connection, independent of beans and lazy initialisation, failing closed) stops startup of any Spring Boot service outside `dev`/`demo` against a seeded database (`SyntheticDataGuardTest`, `SyntheticDataGuardCoverageTest`) | implemented |
| S/I | Demo passwords or API key published in the public repository | Generated locally, git-ignored `.demo-credentials` (mode 600), printed once, never on a command line; only bcrypt hashes and HMACs stored; gitleaks rule for `fsk_` keys | implemented |
| I | Real data entered into a demo deployment | Banner "SYNTHETIC DATA — NOT FOR PRODUCTION" from public `GET /environment` (D-21) | contracted; UI shell M8 |
| I | Demo credentials leak into CI logs | Stack job filters account and key lines; credentials are throw-away per run | implemented |

### 3.3 Public API (contracted in M1; implemented M5, M6, M7)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| S | Forged ingestion | API keys `fsk_<env>_<keyId>_<secret>`, HMAC-SHA256 with a server pepper, scopes per operation (ADR 0011) | contracted |
| S | Staff session theft | Short-lived JWT, rotating refresh cookie with family revocation, `token_version` | contracted |
| T | Replay or duplicate ingestion | Idempotency keys and `transaction_ids` uniqueness | contracted, schema implemented |
| I | Component detail disclosed publicly | One public health endpoint with `{"status"}` only; detail on the management port (owner decision, ADR 0014) | contracted |
| I | Object-level access across roles or tenants | Golden authorisation matrix and `x-authorisation-rules` tested against the contract | contracted |
| E | Loosening risk configuration without a second officer | Asymmetric dual control; database CHECK forbids self-review and stacked open changes (test `configurationChangesCannotBeSelfReviewedOrStacked`) | contracted, schema implemented |
| D | Request floods | Rate limits (429 contracted), payload limits (413) | contracted |

### 3.4 Kafka, webhooks, scoring service, PII vault, front end

| Component | Main threats | Planned control | Milestone |
|---|---|---|---|
| Kafka (6) | T/I: plaintext, unauthenticated local broker | TLS + SASL, per-service ACLs; events carry tokens only | M6, M9 |
| Webhooks (7) | S/T: forged or replayed callbacks | HMAC signature with timestamp and tolerance, published test vectors (contract) | M6 |
| Scoring service (3) | T: model poisoning; D: latency attack | Model promotion gates, shadow scoring, rule-based fallback (C.4) | M4, M5 |
| PII vault (8) | I: vault reachable from every service; raw PII in the main database | Separate instance, separate roles, AES-256-GCM envelope encryption behind a key-provider interface (M6); TLS, KMS, encrypted volumes, network policy (M9) (D-20, re-planned by ADR 0021). Until then every customer identifier column accepts tokens only (`SchemaPoliciesTest`) | M6, M9 |
| Webhook signing secrets at rest | I: `api_keys.webhook_secret_ciphertext` decrypted by whoever holds the key; rotation undefined | Envelope encryption with the D-20 key provider and a key id per secret (M6); KMS and rotation procedure (M9) | M6, M9 |
| Customer verification (5) | S: guessed or reused links | Token hash lookup, single use (`verificationTokenAnswersOnce`), expiry trigger | schema M1, page M6 |
| Front end (2) | I: cached alert data on shared phones (D-29) | No offline decision replay without re-validation, cache limits | M8 |

### 3.5 Development and CI (carried from the M0 review)

| Element | Threat | Control | Residual |
|---|---|---|---|
| Local `core` stack | I/E: plaintext Kafka, Redis without TLS, MLflow and Mailpit without auth | Ports on 127.0.0.1; random credentials in `.env`; synthetic data only | Dev only; production controls M9 |
| CI | T: action or tool supply chain; I: token misuse | Actions pinned by SHA; read-only permissions; gitleaks, buf and uv checksum-verified; frozen lockfiles | Images by tag, not digest (GOV-8) |
| Secret scanning | I: secrets committed | Pre-commit gitleaks on staged changes, CI history scan, committable working-tree scan, self-test with planted secrets | Git-ignored local files are not scanned by design (ADR 0019) |
| CI stack diagnostics (public annotations and artifacts) | I: credentials in MLflow, object-store or TimescaleDB logs published with the run | Redaction of every `.env` value of 8+ characters and of `://user:pass@` in command lines, in pure bash, dropping the logs when `.env` is unreadable; CI credentials are throw-away per run | Secrets not in `.env` (for example `.demo-credentials`) or shorter than 8 characters are not redacted; move to an allowlisted excerpt if logs grow |
| `fs_migrator` credential | T/R: the schema owner can disable or drop append-only triggers and rewrite decisions, blocks, labels or configuration versions | Owner used only by the migration job, never by services (M9); audit log tamper-evident through the hash chain and signed anchors (M7) | Non-audit append-only tables have no tamper evidence beyond grants and triggers |
| Devcontainer | E: Docker-in-Docker privileged | Base image by digest | Feature versions not digest-pinned |

### 3.7 The M6 decision path, as built (numbered 3.7 because M7's branch takes 3.6)

| Component | Threat | Control as built | Residual |
|---|---|---|---|
| PII vault (8) | I: a compromised service or a stolen backup reveals phone and account numbers | A separate PostgreSQL instance whose roles do not include any role of the main database; AES-256-GCM per-row data keys wrapped under a master key held by a `KeyProvider`; the institution and account token are authenticated data, so a row copied elsewhere fails to decrypt; contacts are resolved for one send and never logged, stored or published (ADR 0069, `VaultContactsTest`) | Master keys come from configuration until M9's KMS; key rotation re-wraps lazily, so an old key must stay configured until every row is rewritten; anyone holding both the vault dump and the configuration holds the data |
| Ingestion API (1) | D: one key floods the decision path | A token bucket per API key in Redis, 429 with `Retry-After`; while Redis is down each instance holds the budget alone and says so in `RateLimit-Degraded` and in `fs_rate_limit_decisions_total` (E.1, `RateLimitApiTest`) | The degraded budget is per instance, so a deployment of N instances allows up to N times the limit during a Redis outage — a deliberate trade against refusing traffic the store cannot vouch for |
| Idempotency (C.2) | T: one payment decided twice with different answers, across a lease expiry or a Redis outage | Claim ownership tokens, fingerprints always stored, UNCERTAIN claims, and a per-institution verification window against `transaction_ids` after any decision Redis did not see (ADR 0067 points 4–6) | With both Redis and PostgreSQL unavailable the claim is decided unverified and counted; the durable writer still keeps the first decision |
| Notification consumers | D: a poison record stalls every customer SMS or webhook on a partition | Permanent failures go to `<topic>.dlq` with the reason and the source offset; transient ones back off to 30 s (C.3, ADR 0064 point 5) | A dead-lettered record needs an operator; nothing replays the DLQ yet |
| Webhook egress (7) | I/E: a registered URL points at an internal address (SSRF) | An explicit deny-list of IPv4 and IPv6 special-purpose ranges, including CGNAT, checked on every resolved address, with a test per range (ADR 0066) | DNS rebinding between the check and the connection is declared, not prevented; pinning is open work |
| `/api/docs` (1) | I: a documentation endpoint that leaks internals or drifts from the contract | The frozen `contracts/openapi/fraudshield-api.yaml` served byte for byte from the jar, with a test that fails on any difference; no API key required, because it is the public contract | The document names every endpoint, which is the point of publishing it |
| Configuration reads (JPA) | T: an ORM writing where the schema forbids it; I: a tenant reading another's configuration | Append-only tables map to `@Immutable` entities; `ddl-auto=validate` and `open-in-view=false` are enforced at start-up; the institution is set inside the JPA transaction, so row-level security covers Hibernate's statements (ADR 0068) | A future entity over an append-only table that forgets `@Immutable` fails at the trigger, not at review; the guard covers settings, not annotations |

## 4. Open risks

| # | Risk | Owner action or milestone |
|---|---|---|
| R-1 | `main` ruleset (no force push, no deletion) not verifiable without authentication | Owner confirms (F-06) |
| R-2 | Audit anchors unsigned until the audit service exists | Audit service milestone |
| R-3 | Application role `fs_app` necessarily reads credential hashes of its tenant; a SQL injection in the API would expose them | Parameterised queries only (M5/M6 persistence layer), static analysis in CI |
| R-4 | Third-party penetration test | REQUIRES_EXTERNAL_PARTY (D-28) |
| R-9 | The vault's master keys live in configuration until M9's KMS; whoever holds a vault backup and the process configuration holds customer PII | M9 (D-20, ADR 0069) |
| R-10 | The MNO SIM-swap signal does not exist, so D-25 refuses self-service for every block and no customer reaches the verification page in production | M7/M8 adapter (ADR 0065) |
| R-11 | Nothing replays a dead-lettered notification; a poison record is preserved and counted, not recovered | M7 operations tooling (ADR 0064 point 5) |
| R-12 | Webhook DNS rebinding between the destination check and the connection | Pinning or a proxy with an address allow-list (ADR 0066) |
