# FraudShield threat model

- **Method:** STRIDE per component and per data flow (build prompt D-28, I.3). S = spoofing,
  T = tampering, R = repudiation, I = information disclosure, D = denial of service, E = elevation of
  privilege.
- **Version:** M1 (database, contracts, demo seeding), plus the M9 delta (Kubernetes platform,
  observability and alerting, supply chain: sections 3.6 to 3.8, risks R-5 to R-9). Updated at
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

### 3.6 Kubernetes platform (M9: manifests validated offline, not yet applied to a cluster)

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
| R/I | Decisions spooled on an API pod are lost when the pod is deleted during a Kafka outage (D-15) | Spool on a shared ReadWriteMany volume, one directory per pod; any live API pod claims and replays the directories of dead pods (ADR 0090); `k8s_policy.py` rejects ephemeral spool storage | manifests implemented; replayer M6; latency and chaos proof M10 (R-5) |

### 3.7 Observability and alerting (M9)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| I | Dashboards expose fraud data to anyone who finds Grafana | No anonymous access, no sign-up; reachable only from the API's authenticated proxy by NetworkPolicy (E.8); update checks, reporting and news feed disabled, so Grafana makes no outbound calls | implemented (manifests) |
| I | Personal data in metric labels | Queries may use only the labels Part E.10 defines (no account, device or counterparty tokens); `metric_catalogue.py` fails on any other label | implemented (for queries); emitting services must follow E.10 |
| I | Personal data in log-based alerts and incident notes | Alerts carry counts and service names only; runbooks forbid copying log lines or payloads and treat a PII-bearing log line as a privacy incident | implemented |
| T/R | An alert is weakened or removed silently | Rules and thresholds are reviewed code; promtool unit tests fix every threshold from both sides; `rule_conventions.py` fails when a page alert loses its runbook; the one operator-set level (D-10) lives in its own file | implemented |
| S/I | PagerDuty routing keys leak or are forged | Keys only in the Secret `fraudshield-alertmanager-pagerduty`, mounted as files; never in the repository | implemented (manifests) |
| D | The alerting path itself fails unnoticed | Single Prometheus and Alertmanager with persistent volumes; no high availability and no external dead-man's switch yet | open: R-6 |

### 3.8 Supply chain (M9)

| STRIDE | Threat | Control | Status |
|---|---|---|---|
| T | A tampered validator or scanner binary | Every tool (kustomize, kubeconform, promtool, amtool, lokitool, blackbox_exporter, actionlint, trivy, syft, cosign) runs through a launcher that checks the release checksum and re-checks the binary's SHA-256 on every run | implemented |
| T | Vulnerable dependencies reach a release | Trivy on lockfiles and on images fails on HIGH/CRITICAL with a fix; configuration scanned for misconfiguration | implemented (CI) |
| T/S | An image or SBOM is replaced after the build | Keyless cosign signatures bound to the supply-chain workflow identity, verified in the same job; CycloneDX SBOMs attested to image digests | implemented (CI); images pending Dockerfiles |
| T | An unsigned image is admitted to the cluster anyway | Admission-time signature verification (for example Sigstore policy-controller or Kyverno) is not configured | open: R-7 |
| I | Signing publishes build metadata | Rekor entries show the workflow, commit and repository, all of which are public already | accepted |

## 4. Open risks

| # | Risk | Owner action or milestone |
|---|---|---|
| R-1 | `main` ruleset (no force push, no deletion) not verifiable without authentication | Owner confirms (F-06) |
| R-2 | Audit anchors unsigned until the audit service exists | Audit service milestone |
| R-3 | Application role `fs_app` necessarily reads credential hashes of its tenant; a SQL injection in the API would expose them | Parameterised queries only (M5/M6 persistence layer), static analysis in CI |
| R-4 | Third-party penetration test | REQUIRES_EXTERNAL_PARTY (D-28) |
| R-5 | ADR 0090 (spool on a shared volume with orphan replay) holds only if spool append p95 on the shared volume fits the 2 ms step-10 budget; until M10 measures it and runs the pod-deletion chaos case, D-15 is unverified | M10 benchmark and chaos case; fallback per ADR 0090: StatefulSet with per-pod volumes |
| R-6 | Alerting has no high availability and no dead-man's switch: if Prometheus or Alertmanager stops, nothing pages | M9 follow-up: Watchdog alert to an external heartbeat service; HA pair |
| R-7 | Cluster admission does not verify image signatures | M9 follow-up with the cluster's admission controller |
| R-8 | Data stores and Loki are not in the Kubernetes manifests; their NetworkPolicies and at-rest encryption (D-20) are unverified | M9 follow-up (CloudNativePG, D-49) |
| R-9 | The ZAP baseline and image signing jobs have not executed: the compose stack has no API service and no service Dockerfile exists yet | Runs automatically once M6/M5/M8 add them |
