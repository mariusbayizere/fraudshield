# FraudShield threat model

- **Method:** STRIDE per component and per data flow (build prompt D-28, I.3). S = spoofing,
  T = tampering, R = repudiation, I = information disclosure, D = denial of service, E = elevation of
  privilege.
- **Version:** M1 (database, contracts, demo seeding), plus the M9 delta (Kubernetes platform,
  observability and alerting, supply chain: sections 3.8 to 3.10, risks R-14 to R-18). Updated at
  every milestone that adds a component or a data flow; each milestone review checks this file
  against the change (M0 review condition M-1).
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

### 3.6 Staff identity, administration and audit (M7: implemented, ADR 0070)

| STRIDE | Threat | Control | Evidence |
|---|---|---|---|
| S | Wrong role, API key or anonymous caller reaching a staff operation | URL policy read from the packaged contract, deny by default; method-level rules equal to the matrix; API keys hold scopes, never roles | `AuthorisationMatrixTest` (every operation × anonymous, four roles, four scope sets) |
| S | Stolen access token used after sign-out, password change, role change or deactivation | `token_version` and `sid` checked per request through a 1 s / 2 s / database cache with pub/sub invalidation | `SessionTest`, `SessionInvalidationTimingTest` (measured, < 5 s even with the announcement lost) |
| S | Stolen refresh token | Rotation on every use; reuse revokes the family; httpOnly, Secure, SameSite=Strict, path-scoped cookie; double-submit CSRF on cookie-bearing operations | `SessionTest` |
| S | Password guessing | 10 attempts / 15 min per IP (office ceiling per institution allowlist), per (IP, email) limit, 5-failure lock with 30-min auto-unlock, bcrypt cost 12 | `LoginTest`, `PasswordHasherTest` |
| I | Account enumeration | Same 401/423 sequence and one bcrypt for unknown, inactive and real accounts; 202 for every registration and reset request, padded to a minimum duration | `LoginTest`, `RegistrationTest`, `PasswordResetTest` |
| S | Google account from outside the institution | Server-side ID-token verification (signature, iss, aud, exp, email_verified); self-service domain allowlist; new accounts PENDING_APPROVAL with no access (D-23) | `GoogleSignInTest` |
| E | Self-elevation | Registration records a requested role only; administrators cannot change their own role or status; admin writes re-check the actor's role in the database | `RegistrationTest`, `UserAdministrationTest` |
| E | Two administrators demoting each other | Admin-affecting edits lock the institution's active administrators first | `concurrentMutualDemotionLeavesOneActiveAdmin` |
| S | API-key guessing or timing | 256-bit secrets, HMAC with pepper, constant-time compare, unknown key IDs hashed too; 2 s cache and pub/sub revocation | `ApiKeyLifecycleTest`, `NetworkAndKeysTest` |
| T | SSRF through webhook URLs | Two checks with different jobs. When a key is created or rotated, M7's `WebhookUrlValidator` accepts only HTTPS, no user info, no IP literals and a host whose every resolved address is public. At every delivery, M6's dispatcher resolves the host again and checks each address against its special-purpose deny-list (ADR 0066, 3.7), so a name re-pointed after registration is caught then; rebinding between that check and the connection is R-12 | `WebhookUrlValidatorTest` (registration); M6's `HttpWebhookTransport` tests, one per denied range (delivery) |
| T/R | Audit rows edited, deleted or re-ordered, including by a superuser who recomputes hashes | Database-assigned chain (V8); daily Ed25519-signed RFC 6962 anchors over sequence ranges; `fraudshield audit verify` | `AuditAnchoringTest`, `AuditVerifyCommandTest` |
| R | Decisions, holds and blocks missing from the audit trail, which would then look complete while covering staff actions only | The decision engine's audit events, published to `fs.audit.events`, are appended to the same chain by the audit writer's consumer (group `audit-writer`), once each: the envelope's `event_id` goes into `audit_event_receipts` in the append's transaction (V72); a record the database refuses as data goes to `fs.audit.events.dlq` | `RelayedAuditAppenderTest`; `AssembledApplicationTest` (a hold made through the API is found by audit search and passes `audit verify`) |
| S | Ingestion authenticated by something other than the key store | In the assembled API the ingestion port is M7's authenticator (`StaffIdentityWiring`); M7's chain authorises the request against the contract before M6's filter runs | `AssembledApplicationTest` (a key issued through the admin API submits, and is refused within 5 s of its revocation) |
| T | Functions created by later migrations executable by every role | `ALTER DEFAULT PRIVILEGES FOR ROLE fs_migrator REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC` (V71), the global form; V11's per-schema form never had an effect. Every SECURITY DEFINER function is pinned to its callers | `DatabaseSecurityTest` (`functionsTheMigratorCreatesAreNotExecutableByPublic`, `everyOwnerRightsFunctionPinsItsSearchPathAndHasOnlyItsDeclaredCallers`) |
| I | Secrets at rest | Password bcrypt; OTP HMAC; API keys HMAC; webhook and Google tokens AES-256-GCM with associated data; unlock and reset tokens stateless; emails with credentials never written to the outbox | `CryptoTest`, `ApiKeyLifecycleTest` |

### 3.7 The M6 decision path, as built

| Component | Threat | Control as built | Residual |
|---|---|---|---|
| PII vault (8) | I: a compromised service or a stolen backup reveals phone and account numbers | A separate PostgreSQL instance whose roles do not include any role of the main database; AES-256-GCM per-row data keys wrapped under a master key held by a `KeyProvider`; the institution and account token are authenticated data, so a row copied elsewhere fails to decrypt; contacts are resolved for one send and never logged, stored or published (ADR 0069, `VaultContactsTest`) | Master keys come from configuration until M9's KMS; key rotation re-wraps lazily, so an old key must stay configured until every row is rewritten; anyone holding both the vault dump and the configuration holds the data |
| Ingestion API (1) | D: one key floods the decision path | A token bucket per API key in Redis, counted in transactions so a batch costs its item count, all or nothing (ADR 0058: 2,000/s, burst 4,000, ASSUMED), 429 with `Retry-After`; while Redis is down each instance holds the budget alone and says so in `RateLimit-Degraded` and in `fs_rate_limit_decisions_total` (E.1, `RateLimitApiTest`, `TransactionBudgetTest`) | The degraded budget is per instance, so a deployment of N instances allows up to N times the limit during a Redis outage — a deliberate trade against refusing traffic the store cannot vouch for |
| Idempotency (C.2) | T: one payment decided twice with different answers, across a lease expiry or a Redis outage | Claim ownership tokens, fingerprints always stored, UNCERTAIN claims, and a per-institution verification window against `transaction_ids` after any decision Redis did not see (ADR 0067 points 4–6) | With both Redis and PostgreSQL unavailable the claim is decided unverified and counted; the durable writer still keeps the first decision |
| Notification consumers | D: a poison record stalls every customer SMS or webhook on a partition | Permanent failures go to `<topic>.dlq` with the reason and the source offset; transient ones back off to 30 s (C.3, ADR 0064 point 5) | A dead-lettered record needs an operator; nothing replays the DLQ yet |
| Webhook egress (7) | I/E: a registered URL points at an internal address (SSRF) | An explicit deny-list of IPv4 and IPv6 special-purpose ranges, including CGNAT, checked on every resolved address, with a test per range (ADR 0066) | DNS rebinding between the check and the connection is declared, not prevented; pinning is open work |
| `/api/docs` (1) | I: a documentation endpoint that leaks internals or drifts from the contract | The frozen `contracts/openapi/fraudshield-api.yaml` served byte for byte from the jar, with a test that fails on any difference; no API key required, because it is the public contract | The document names every endpoint, which is the point of publishing it |
| Configuration reads (JPA) | T: an ORM writing where the schema forbids it; I: a tenant reading another's configuration | Append-only tables map to `@Immutable` entities; `ddl-auto=validate` and `open-in-view=false` are enforced at start-up; the institution is set inside the JPA transaction, so row-level security covers Hibernate's statements (ADR 0068) | A future entity over an append-only table that forgets `@Immutable` fails at the trigger, not at review; the guard covers settings, not annotations |

### 3.8 Kubernetes platform (M9: manifests validated offline, not yet applied to a cluster)

Controls are in `infrastructure/k8s/` and are checked on every change by
`infrastructure/checks/validate.sh`: kubeconform (strict, Kubernetes 1.34 schemas) and
`infrastructure/checks/k8s_policy.py`, whose tests break each rule once and expect a failure.

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| E | A compromised container escalates to the node | Namespace enforces Pod Security `restricted`; every pod non-root, `allowPrivilegeEscalation: false`, all capabilities dropped, RuntimeDefault seccomp, read-only root filesystem, no host namespaces or hostPath; checked offline so a violation fails CI, not admission | implemented (manifests) |
| S/E | A stolen service account token calls the Kubernetes API | One service account per workload, token not mounted except Prometheus, whose Role allows only get/list/watch on pods in its own namespace | implemented (manifests) |
| I/T | Lateral movement between workloads | Default-deny ingress and egress; one allow policy per flow of section 2. The management port (health detail, metrics) admits only Prometheus and the blackbox exporter (ADR 0014); the PII vault is reachable from the API only, never from the scorer or ML worker | implemented (manifests) |
| I | The API's internet egress (webhooks, SMS, email, OAuth) used to reach internal services | Egress on 443 and 587 only, with RFC 1918, CGNAT and link-local ranges excluded | implemented (manifests) |
| T | A mutable tag deploys an unreviewed image | Third-party images pinned by digest; FraudShield images untagged in manifests and pinned by digest at deploy; the policy check fails on any tag | implemented (manifests); deploy step planned |
| D | Voluntary disruption (node drain, upgrade) takes a component down | PodDisruptionBudgets for every workload, HPAs for serving components, spread across nodes and zones | implemented (manifests) |
| D/T | A bad release degrades decisions | Argo Rollouts canary: 10% for 30 minutes, background analysis on canary pods only, rollback on 5xx > 0.5% or p99 > 80 ms, fail-closed when metrics are missing (`test_canary_analysis.py`) | implemented (manifests) |
| R/I | Decisions spooled on an API pod are lost when the pod is deleted during a Kafka outage (D-15) | Spool on a shared ReadWriteMany volume, one directory per pod; any live API pod claims and replays the directories of dead pods (ADR 0090); `k8s_policy.py` rejects ephemeral spool storage | manifests implemented; replayer M6; latency and chaos proof M10 (R-14) |

### 3.9 Observability and alerting (M9)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| I | Dashboards expose fraud data to anyone who finds Grafana | No anonymous access, no sign-up; reachable only from the API's authenticated proxy by NetworkPolicy (E.8); update checks, reporting and news feed disabled, so Grafana makes no outbound calls | implemented (manifests) |
| I | Personal data in metric labels | Queries may use only the labels Part E.10 defines (no account, device or counterparty tokens); `metric_catalogue.py` fails on any other label | implemented (for queries); emitting services must follow E.10 |
| I | Personal data in log-based alerts and incident notes | Alerts carry counts and service names only; runbooks forbid copying log lines or payloads and treat a PII-bearing log line as a privacy incident | implemented |
| T/R | An alert is weakened or removed silently | Rules and thresholds are reviewed code; promtool unit tests fix every threshold from both sides; `rule_conventions.py` fails when a page alert loses its runbook; the one operator-set level (D-10) lives in its own file | implemented |
| S/I | PagerDuty routing keys leak or are forged | Keys only in the Secret `fraudshield-alertmanager-pagerduty`, mounted as files; never in the repository | implemented (manifests) |
| D | The alerting path itself fails unnoticed | Single Prometheus and Alertmanager with persistent volumes; no high availability and no external dead-man's switch yet | open: R-15 |

### 3.10 Supply chain (M9)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| T | A tampered validator or scanner binary | Every tool (kustomize, kubeconform, promtool, amtool, lokitool, blackbox_exporter, actionlint, trivy, syft, cosign) runs through a launcher that checks the release checksum and re-checks the binary's SHA-256 on every run | implemented |
| T | Vulnerable dependencies reach a release | Trivy on lockfiles and on images fails on HIGH/CRITICAL with a fix; configuration scanned for misconfiguration | implemented (CI) |
| T/S | An image or SBOM is replaced after the build | Keyless cosign signatures bound to the supply-chain workflow identity, verified in the same job; CycloneDX SBOMs attested to image digests | implemented (CI); images pending Dockerfiles |
| T | An unsigned image is admitted to the cluster anyway | Admission-time signature verification (for example Sigstore policy-controller or Kyverno) is not configured | open: R-16 |
| I | Signing publishes build metadata | Rekor entries show the workflow, commit and repository, all of which are public already | accepted |

## 4. Open risks

| # | Risk | Owner action or milestone |
|---|---|---|
| R-1 | `main` ruleset (no force push, no deletion) not verifiable without authentication | Owner confirms (F-06) |
| R-2 | Audit anchors are signed (M7), but `fs_app` can still insert a forged anchor that blocks the day's legitimate one (PB-11); verification reports it | Dedicated anchoring role (needs bootstrap and Compose changes) |
| R-3 | Application role `fs_app` necessarily reads credential hashes of its tenant; a SQL injection in the API would expose them | Parameterised queries only (M5/M6 persistence layer), static analysis in CI |
| R-4 | Third-party penetration test | REQUIRES_EXTERNAL_PARTY (D-28) |
| R-5 | A temporary password from an administrator works until the user changes it; the contract cannot tell the console to force a change | Contract change: `password_change_required` (ADR 0070 §9) |
| R-6 | Staff emails carrying unlock links, reset codes or temporary passwords are lost if the instance dies between commit and send | Accepted: the user repeats the request, waits for auto-unlock or asks an administrator (ADR 0070 §8) |
| R-7 | Two colluding administrators, or one who approves a sock-puppet account, bypass separation of duties (ADR 0014) | Dual control on role grants (not built) |
| R-8 | Rate limits and caches degrade to per-instance behaviour while Redis is down | Accepted degraded mode (C.4); per-account lockout stays in the database |
| R-9 | The vault's master keys live in configuration until M9's KMS; whoever holds a vault backup and the process configuration holds customer PII | M9 (D-20, ADR 0069) |
| R-10 | The MNO SIM-swap signal does not exist, so D-25 refuses self-service for every block and no customer reaches the verification page in production | REQUIRES_EXTERNAL_PARTY: an MNO data agreement, for the project owner. No milestone can build the adapter without a source; the refusal is correct per D-25 (owner, 2026-09-23). PB-102 holds the adapter for when a source exists |
| R-11 | A dead-lettered record is preserved and counted, but an operator has no packaged way to replay it | M6 built the replay (`DeadLetterReplay`, docs/architecture/decision-fact-ordering.md D-c), and it now also covers `fs.audit.events.dlq`; packaging it as an operator command with a runbook is M9's (PB-103) |
| R-12 | Webhook DNS rebinding between the destination check and the connection | Pinning or a proxy with an address allow-list (ADR 0066) |
| R-13 | While an instance cannot read PostgreSQL (database unavailable, contract R8), it keeps using a key's cached record for up to 10 minutes after the read that produced it (owner decision 2026-10-17, option B, for D-15; rules in `docs/architecture/api-key-cache-contract.md`). It can therefore miss a revocation committed after that read whose announcement it did not receive: one committed up to 10 minutes **before** an outage on every instance (an idle key's record is still served), or one committed **during** an outage local to this instance (pool exhaustion, a partition, a connection limit) by an instance that can still write. Redis pub/sub drops announcements while a subscriber reconnects. A received announcement is never undone; a read that overlapped one is not installed; a read that sees the key revoked removes the entry (best effort). A key with no cached record is refused with 503 and `Retry-After`, never 401; past 10 minutes every key is refused, so D-15 holds for outages up to 10 minutes. The failure limiter runs before any read and uncached reads share a 4-permit bulkhead, so an unauthenticated flood cannot exhaust the pool or induce a local outage | **Accepted** (owner, 2026-10-18): option 2, a narrower 26 s bound, was abandoned under the owner's rule after its contract was found unsound in three drafts, leaving this 10-minute window. Evidence: `ApiKeyCacheContractTest` (P1-P35), `KeyStoreUnavailabilityTest`, `ApiKeyLifecycleTest` (outage, announcement and forward-only state tests), `ApiKeyAuthenticationFilterTest`, `ApiKeyFilterTest`, `StaffIdentityWiringTest`, `InMemoryRateLimiterTest`, `RedisRateLimiterTest`, `AuthPropertiesTest`, `ResilienceApiTest` (ADR 0073) |
| R-14 | ADR 0090 (spool on a shared volume with orphan replay) holds only if spool append p95 on the shared volume fits the 2 ms step-10 budget; until M10 measures it and runs the pod-deletion chaos case, D-15 is unverified | M10 benchmark and chaos case; fallback per ADR 0090: StatefulSet with per-pod volumes |
| R-15 | Alerting has no high availability and no dead-man's switch: if Prometheus or Alertmanager stops, nothing pages | M9 follow-up: Watchdog alert to an external heartbeat service; HA pair |
| R-16 | Cluster admission does not verify image signatures | M9 follow-up with the cluster's admission controller |
| R-17 | Data stores and Loki are not in the Kubernetes manifests; their NetworkPolicies and at-rest encryption (D-20) are unverified | M9 follow-up (CloudNativePG, D-49) |
| R-18 | The ZAP baseline and image signing jobs have not executed: the compose stack has no API service and no service Dockerfile exists yet | Runs automatically once M6/M5/M8 add them |
