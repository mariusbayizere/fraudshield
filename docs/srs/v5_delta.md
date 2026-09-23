# SRS v5.0 — delta against v1.0, and what it means for the code that exists

**Nothing in this document has been adopted.** No requirement, schema, migration, ADR or line of
code was changed to match v5. This is a reading of the new document against the built system, for
the owner to decide from. Where v5 repeats something the project has already corrected, the
correction is stated beside it and **stands until the owner says otherwise**.

## 0. Provenance

| Item | Value |
|---|---|
| New document | `docs/srs/FraudShield_SRS_v5_0.docx`, SHA-256 `8226e5ed2d7c93fb…d924e6`, supplied 2026-09-23 |
| Extraction | `docs/srs/FraudShield_SRS_v5_0.md`, 960 lines, produced by `scratchpad/extract_srs.py` (stdlib only: zipfile + ElementTree), same shape as the v1 extraction — paragraphs as lines, tables as pipe tables with `<br>` for in-cell breaks, so `fs-srs-tables` can parse either |
| Compared against | `docs/srs/FraudShield_SRS_v1_0.md`, 600 lines, SHA-256 `d69e7de5e3ca…21ebc7` |
| Tree | `m8/frontend` @ 4f6828e; M5, M6, M7 and M9 work is on unmerged branches and was read with `git show <branch>:<path>` |

**Three things about the document itself, before any requirement:**

1. It is named v5.0 but calls itself **v4.0** on its cover block (`:3`) and in its own description
   ("v2.0 + v3.0 combined", `:20`); its colophon then says "Complete SRS v5.0" (`:957`).
2. Its contents table lists **sections 00–16** (`:23-40`), but the file contains **sections 17–23**
   (Docker, Kubernetes, Prometheus/Grafana, Operational reporting, Helm, OpenTelemetry/Jaeger,
   infrastructure roadmap) and the new FR-08 table. The contents page predates the content.
3. Every functional requirement v1 had is present, with the **same IDs and same thresholds**. The
   substantive FR delta is FR-08 plus a small number of changed rows listed in §3.

## 1. Counts

| Measure | v1.0 | v5.0 |
|---|---|---|
| Sections | 16 (+ 05B) | 23 |
| Functional requirements | 60 (FR-01-01 … FR-07-09) | 68 (+ FR-08-01 … FR-08-08) |
| Non-functional rows (performance / security / reliability) | 26 | 26 |
| ML deployment gate metrics | 13 | 13 (identical thresholds) |
| Data-model tables | 13 | 13 (same names) |
| Requirements in the traceability register | 60 of 60 | 86 of 94; **8 absent** (all of FR-08) |
| Register rows "done" among v5's 94 | — | 5 (3 DONE, 2 DONE_WITH_DEVIATION) |

Full per-requirement statuses, counts per milestone and the M6/M10/M12 remainders are in
`docs/traceability/v5_coverage.md`.

## 2. NEW — in v5, not in v1

Status is one of **ALREADY IMPLEMENTED** (with file and test), **NOT IMPLEMENTED** (with the
milestone that should own it), or **CONFLICTS** (with what it contradicts, and the evidence).

| # | v5 item | Where | Status |
|---|---|---|---|
| N1 | **FR-08-01…08: operational reporting** — daily 06:00, weekly Monday 07:00, monthly 1st 08:00 (BNR format), Reports tab, custom ranges ≤ 90 days, S3 retention 90 days / 1 year / 7 years, `GET /api/v1/reports/{type}`, Grafana report dashboard | §20.5 `:878-889` | **NOT IMPLEMENTED.** No owner exists: these are the only v5 requirements with no register row (`grep -rn 'FR-08' docs/traceability/` → nothing), and no milestone in the build prompt covers scheduled reporting. M9 owns observability, not reporting; M12 is audit and handover. Needs an owner decision on milestone placement before it can be filed. |
| N2 | **Celery beat + WeasyPrint + Jinja2 + SMTP + S3** as the reporting stack | §20 `:826`, §23 `:918` | **NOT IMPLEMENTED**, and a new runtime dependency set. Nothing on any branch references Celery or WeasyPrint (`git grep -il "celery\|weasyprint"` across `main`, `m5/scoring`, `m6/decision`, `m9/infra` → no hits). The Python services are FastAPI + uv; adding a Celery broker is an architectural decision, not a library choice. |
| N3 | **Helm 3 charts** for all Kubernetes deployments, `infrastructure/helm/fraudshield/` | §21 `:891-896` | **CONFLICTS.** The prescribed layout is `infrastructure/k8s/ (kustomize base + overlays)` (`docs/prompts/FraudShield_Master_Build_Prompt.md:302`), and M9 built exactly that: `m9/infra:infrastructure/k8s/{base,overlays/staging,overlays/production}` with `kustomization.yaml` files, validated by kubeconform in `infrastructure/checks/`. Adopting Helm means re-packaging M9's manifests and re-doing its validation. |
| N4 | **OpenTelemetry Java agent + SDK, Jaeger UI at `tracing.fraudshield.rw`, 10% sampling** | §22 `:897-907` | **NOT IMPLEMENTED.** OpenTelemetry is already planned (`Master_Build_Prompt.md:259` lists an OpenTelemetry Collector; E.10 requires traces edge→scorer→consumer), so the SDK part is consistent. **Jaeger specifically is new** — the prompt's stack pairs the OTel Collector with Loki and Grafana. Owner: M9. |
| N5 | **Docker images for six services** with size caps (api < 280 MB, ml < 1.2 GB, frontend < 45 MB, worker < 300 MB), multi-stage builds, non-root users, HEALTHCHECK | §17.1 `:743-752` | **NOT IMPLEMENTED.** No service Dockerfile exists on any branch (`git ls-tree -r --name-only <branch>` for `main`, `m9/infra` → only `docker-compose.yml`). The dev stack exists (`docker-compose.yml`, `infrastructure/docker/`), the shippable images do not. Owner: M9. |
| N6 | **Kubernetes deployment specification** — replicas, CPU/RAM requests and limits, HPA triggers including Kafka-lag custom metrics, probe paths per service | §18.1 `:771-778` | **PARTLY ALREADY IMPLEMENTED.** `m9/infra:infrastructure/k8s/` has the manifests with PodSecurity restricted, NetworkPolicies, HPA and PDBs, checked by `infrastructure/checks/k8s_policy.py` and 24 break-one-rule tests. v5's specific numbers (2/10 replicas, 500m/2 CPU, lag > 500) are **not** what those manifests contain and would need a line-by-line comparison before adoption. |
| N7 | **Prometheus/Grafana specifics** — ServiceMonitor per service, PrometheusRule alerts, dashboards as code | §19 `:800-824` | **ALREADY IMPLEMENTED.** `m9/infra:infrastructure/prometheus/rules/{recording,alerts,thresholds}.yml` with promtool unit tests both sides of every threshold; `infrastructure/grafana/dashboards/*.json` (5 dashboards) generated by `generate_dashboards.py --check`. |
| N8 | **§02 four-layer MVC package tree** (`com.fraudshield.{controller,service,repository,entity}`) and **§03 Spring Security filter chain** as concrete code | §02 `:83-94`, §03 `:95-114` | **CONFLICTS** — see §7. |
| N9 | **§07 column-by-column justification for 13 tables** and **§08 entity relationships** | §07 `:311-468`, §08 `:469-484` | **CONFLICTS** — see §6. |
| N10 | **§12 per-role frontend specification** (ANALYST / SENIOR_ANALYST / RISK_OFFICER / ADMIN screens) | §12 `:566-613` | **NOT IMPLEMENTED** (M8, in progress). The shell, design system, i18n and auth exist; the four role dashboards do not. v5 names Zustand and React Router v6, which **conflict** with ADR 0080 (TanStack Query only, no global store; TanStack Router) — see §7. |
| N11 | **§23.1 complete technology stack table with versions** | §23.1 `:921-956` | **CONFLICTS** with ADR 0003 — see §7.4. |
| N12 | Grafana at `grafana.fraudshield.rw`, Jaeger at `tracing.fraudshield.rw`, production Ingress/TLS hostnames | §18.4 `:790`, §19 `:813`, §22 `:906` | **NOT IMPLEMENTED.** No domain is registered or referenced in any manifest. Deployment prerequisite, not code. |

## 3. CHANGED — same requirement, different content

Rows whose wording was merely condensed are not listed; these change what the software must do.

| # | Requirement | v1 | v5 | Status |
|---|---|---|---|---|
| C1 | **FR-02-01 ScoringResult fields** | 9 fields: ensemble_score, xgboost_score, lightgbm_score, anomaly_score, risk_tier, shap_top5, feature_vector, model_version, scoring_duration_ms (`v1:108`) | 5 fields: ensemble_score, risk_tier, shap_top5 (nullable for LOW), model_version, requires_analyst_review (`v5:134`) | **CONFLICTS.** v5 drops four fields the contract and the built schema carry, and the M4 evaluation depends on per-model scores. See §6, S1. |
| C2 | **FR-02-10 model version** | Blue-green: both versions score during the switch window | FK consistency with `model_versions`; null throws ValidationException | **NOT IMPLEMENTED** (M5). Not contradictory, but v5 drops the blue-green requirement M5's hot-swap design is built around (ADR 0080 is frontend; the scoring service owns this). |
| C3 | **FR-02-09 feature store** | Redis velocity update < 100 ms, TTL 30 days | adds "DB row upserted every 5 minutes for durability" | **NOT IMPLEMENTED** (M3/M5). New durability requirement on `account_velocity_cache`. |
| C4 | **FR-03-05 / FR-03-02 labels** | false positive logged to retraining; TIMEOUT in audit log | `false_positive_confirmed = true`; TIMEOUT label in `alert_decisions` | **NOT IMPLEMENTED** (M6). v5 pins these to specific tables and columns. |
| C5 | **FR-03-08 append-only** | append-only in the audit log; override creates a new entry | INSERT-only DB permission on `auto_block_events`; "no deletions ever" | **PARTLY ALREADY IMPLEMENTED.** The event-sourced design already forbids mutation; v5's wording is narrower than what exists. See §6, S5. |
| C6 | **FR-06-06 audit log content** | user, role, timestamp, action type, entity, **before/after values**; 12 action types | user, role, timestamp, event type, entity type, entity ID — **no before/after values** | **CONFLICTS.** Dropping before/after values weakens the BNR trail M7 built; the audit chain stores the row hash over the recorded fields. |
| C7 | **FR-04-05 SHAP chart** | "all 44 features ranked" | "all features sorted by \|SHAP\|" | Unchanged in substance; **D-45's correction stands** (all 44 rendered in a scrollable region, top 10 in the initial viewport, top 5 on mobile). |
| C8 | **FR-05-04 campaigns** | clusters by shared feature | adds "transaction count and total amount (computed fresh, not stored)" | **NOT IMPLEMENTED** (M6/M5). A storage constraint, not just a display one. |
| C9 | **NFR security — PII tokenisation** | account holder name and phone as opaque tokens; **actual PII in a separate encrypted PII store** (`v1:210`) | `account_id`/`counterparty_id` as opaque tokens; the separate store is not mentioned (`v5:235`) | **CONFLICTS.** The separate PII vault is D-20's resolution and ADR 0017's design; v5's text would read as permission to drop it. |
| C10 | **NFR reliability — ML down** | fallback engine, transactions labelled `ML_UNAVAILABLE` | fallback, `requires_analyst_review = true` | **CONFLICTS (mild).** `ML_UNAVAILABLE` is in the OpenAPI contract as a degraded mode and is rendered by the console's banners (`frontend/src/design-system/components/SystemBanner/`, test `SystemBanner.test.tsx` pins it to the contract enum). v5's replacement is an additional behaviour, not a substitute for the label. |

## 4. REMOVED — in v1, not in v5

| # | Item | Status |
|---|---|---|
| R1 | **§1.2 "East African Fraud Context — Why This Is a Novel Research Problem"** (`v1:45`) | Dropped entirely. This is the research framing the paper and M11 rest on. No code impact; a publication-plan impact. |
| R2 | **§2.3 "ML Model Architecture — Three-Layer Defence"** (`v1:82`) | Dropped. The three layers (supervised ensemble, Isolation Forest, rules) are still implied by FR-02-05 and the fallback row, but the architectural statement is gone. M4 built all three. |
| R3 | **Four ScoringResult fields** (per-model scores, anomaly score, feature vector, scoring duration) | See C1. |
| R4 | **Audit before/after values** | See C6. |
| R5 | **The separate encrypted PII store** | See C9. |
| R6 | **`ML_UNAVAILABLE` label** | See C10. |

## 5. The repeats — v5 restates defects the project already corrected

The owner asked for these five explicitly. **Each correction stands; nothing below is re-opened.**

| v5 text | Where | The correction that stands |
|---|---|---|
| "Precision at 1% FPR — 0.720" as a gate metric, and `precision_at_1pct_fpr DOUBLE NOT NULL` as a column | `:505`, `:449` | **D-01**: at a 0.87% base rate the precision ceiling at 1% FPR is ≈ **0.467**, so 0.720 is unreachable. The gate metric is **Recall at 1% FPR ≥ 0.720**; precision is still reported next to its ceiling. Implemented: `ml/src/fraudshield_ml/training/gate.py` (`at_fpr_budget`, `precision_ceiling`), evidence `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (measured recall at the 1% FPR budget 0.871). |
| "PostgreSQL TDE + AES-256 application-layer for PII fields" | `:233` | **D-20**: community PostgreSQL 16 has no TDE. Encrypted volumes with KMS-managed keys, plus AES-256-GCM envelope encryption in a **separate PII vault** with its own role; the analyst-facing role has no grant on the vault (ADR 0017). |
| "Cloud region af-south-1 or equivalent" as evidence of EAC data residency | `:241` | **D-21**: af-south-1 is Cape Town — outside the EAC, contradicting the requirement it claims to satisfy. Infrastructure stays cloud-agnostic Kubernetes; residency is a **deployment prerequisite** recorded as such, and demo environments run synthetic data only. |
| "API keys stored as BCrypt hash" | `:240` | **D-19**: bcrypt cost 12 is ~100–300 ms by design and cannot be verified at 10,000 TPS. Keys are `fsk_<env>_<keyId>_<secret>` with **HMAC-SHA256(server pepper, secret)**, lookup by keyId, constant-time compare, 2-second in-memory cache with Redis pub/sub revocation so a revoked key fails within 5 s. |
| "60%+ alerts dismissed", "Western models achieve AUC-ROC 0.72-0.78", "accuracy degrades 8-15% per year" | `:48`, `:49`, `:50` | **D-09**: these are unsourced. Each claim must be either backed by a primary source the author verifies or replaced by a measurement FraudShield produces; `docs/research/claims_register.md` holds the register, and the "Western models" claim became a measured ablation. |

**Three further repeats, beyond the five asked for** (same treatment — the corrections stand):

- **Lighthouse PWA score ≥ 90** (`:645`) — **D-40**: Lighthouse 12 removed the PWA category, so the
  number cannot be measured; the installability criteria are asserted individually instead.
- **Feature-phone support at 240–319 px on KaiOS/Opera Mini** (`:621`) — **D-42**: a bank analyst
  console is not usable there; the breakpoint exists (fp/240) but the target users are staff on
  smartphones and PCs.
- **Background Sync replay of offline analyst decisions** (`:640`) — **D-29**: replayed decisions can
  be stale or dangerous; every queued action carries an idempotency key and the alert's version, the
  server rejects stale actions with 409, and MEDIUM alerts past their deadline are never silently
  replayed.

## 6. Schema — v5's 13 tables against the migrations that exist

Compared against the union of `main`, `origin/m6/decision` and `m7/staff-auth`
(`backend/persistence/src/main/resources/db/migration/`, V1–V11 identical on all three, V60–V65
m6-only, V70 m7-only). Full column-by-column tables: `scratchpad/analysis_schema.md`.

**The built union is 46 tables, 2 continuous aggregates and 6 views. v5 names 13.** Adopting v5's
schema verbatim would drop 33 built tables. The migrations were not changed and are not proposed
to be.

| # | What v5 would remove or break | Evidence |
|---|---|---|
| S1 | **`institution_id` and row-level security, everywhere.** v5 has no `institution_id` on any table. That is 35 RLS policies (34 `CALL enable_tenant_isolation` sites plus the hand-written one), all 4 `enable_tenant_view_isolation` hypertable views, the `current_institution()` function, and every composite `(id, institution_id)` foreign key that exists so a row cannot point at another tenant. | `V1__tenancy_and_common_functions.sql:7` (`current_institution()`), composite FKs at `V2:62,64,143-144`, `V4:23,55-56`, `V5:47,98-100`; ADR 0017:42-44 states the reason. 67 tracked files on `m7/staff-auth` reference `institution_id`; live setters at `backend/audit/.../jdbc/TenantTransactions.java:63`. The M1 gate suite asserts it: `DatabaseSecurityTest.java:401,757`, `SchemaPoliciesTest.java:326`. |
| S2 | **11 of 19 `fraud_scores` columns**, including `xgboost_score`, `lightgbm_score`, `anomaly_score`, `anomaly_raw`, `shap_all`, `feature_vector`, `feature_registry_version`, `scoring_duration_ms`, `ml_unavailable_fallback`, `trace_id`, `institution_id`. Four of them are **required** fields of the published `ScoringResult` contract. | Built columns `V3__transactions_and_scoring.sql:51-74`; contract `contracts/openapi/fraudshield-api.yaml:3274-3275`, asserted by `contracts/tests/test_openapi.py:272-281` and `test_proto.py:50,56`; written by `origin/m6/decision:backend/decision/.../adapter/jdbc/PostgresSink.java:66-70`. This is the FR-02-01 change C1: v5's 5-field ScoringResult is the SRS text for the same cut. |
| S3 | **The ML-fallback insert path.** v5's `fraud_scores` CHECK drops the `OR ml_unavailable_fallback` disjunct, so a HIGH or MEDIUM score produced while the model is down — the documented degraded path — becomes impossible to insert. | Built: `CHECK (risk_tier = 'LOW' OR shap_top5 IS NOT NULL OR ml_unavailable_fallback)`, `V3:73`. Fallback path tested at `PostgresAdaptersTest.java:141`. |
| S4 | **The audit hash chain.** v5's `audit_events` has 9 columns and none of `writer_partition`, `seq`, `recorded_at`, `prev_hash`, `row_hash`; no `audit_chain_heads`, no `audit_anchors`. `audit_row_hash()` hashes 20 inputs, 11 of which v5 deletes, so the function, its trigger and `verify_audit_chain` all fail to compile. | `V8__audit_log.sql:21,22,40,41,42` (columns), `:54-59` (heads), `:62-71` (hash function), `:73-106` (trigger), `:110-145` (verify), `:147-158` (anchors). Breaks `JdbcAuditLog.java:24-26`, `AuditAnchorService.java:78,92,109-112`, `AuditChainVerifier.java:253-254,272`, and m7's `V70:33-62`, plus the 7-year BNR retention policy `V10:24-29`. |
| S5 | **The event-sourced auto-block tables**, and the append-only guarantee itself. v5 keeps only `auto_block_events` and drops `customer_notifications`, `customer_verification_responses`, `unblock_events`, `account_freeze_events`, `label_events` and the view `v_auto_block_status` that already derives the columns v5 wants to store. It then **adds mutable `customer_verified` / `verified_at` to `auto_block_events`, which is append-only** — UPDATE raises `insufficient_privilege` for every role including the owner. | `V5__blocking_verification_and_labels.sql:3-11` (table, with `account_token`, no verification columns), `:16` (`CALL make_append_only('auto_block_events')`), `:20-37,65-73,90-105,107-122,143-153` (the dropped tables), `:124-140` (the view). Append-only machinery: `V1:32-41`. `DatabaseSecurityTest.java:73` asserts UPDATE is refused. |
| S6 | **`api_keys`** — absent from v5 §07 and §08 entirely, while v5's own FR-07-05 (`:207`) makes machine-to-machine API keys a MUST. The document contradicts itself. | Built `V2__identity_and_access.sql:122-150` (19 columns) plus `auth_find_api_key` `:153-161`. Breaks `ApiKeyRepository.java:91`, `ApiKeyAuthenticator.java`, `ApiKeyAdminController.java:97-166` (4 contract operations), the `batch_jobs.api_key_id` FK (`V3:144,152`) and the grants at `V11:31,51-53,79`. |
| S7 | Other hard breaks: `alert_decisions.analyst_id NOT NULL` contradicts the AUTO_RELEASED insert; loss of `idempotency_key`; loss of `tier='ANOMALY'` (D-10); loss of `fraud_probability` / `expected_loss_rwf` (the feed's sort index); loss of `transactions.merchant_category_code` and `amount_rwf`, which are the GROUP BY and SUM keys of both continuous aggregates and the whole MCC circuit breaker (FR-03-07); loss of `model_versions.is_previous_production`, the FR-06-03 rollback target; and BCrypt token hashes make the equality-lookup functions `verification_find_by_token(bytea)` and `auth_find_refresh_token(bytea)` impossible. | `V4:60` and `PostgresSink.java:123-126`; `V4:52,54`; `V4:9,27-29`; `V4:13-14,34`; `V10:37,47,51` and `V6:120-133`; `V7:21,35`; `V5:55-62`, `V2:164-168`. |
| S8 | `V11__grants.sql` would not apply at all: it names about 40 tables, of which 13 survive, and enumerates columns v5 deletes. | `V11__grants.sql:47-55`. |

**Genuinely new in v5's schema: no new table, five new columns.** `alert_decisions.false_positive_confirmed`
is the only one with design content — today it is a derived event field (`KafkaMessages.java:209`
plus `label_events`), and storing it would require UPDATE on an append-only table. The other four
(`auto_block_events.customer_verified` / `verified_at`, `customer_verifications.verified_at`,
`users.is_active`) are denormalisations of facts already derived by `v_auto_block_status:128-129`
or already modelled by `users.status` (`V2:13-14`). Two v5 constraints **are** worth considering on
their merits: UNIQUE on `auto_block_events.fraud_score_id` and on `alert_queue_entries.fraud_score_id`,
neither of which the built schema has — though both would have to be `(institution_id, fraud_score_id)`.

v5 gives **no DDL at all** for its tables 9–13 (`:458-464` is a five-row summary), so their types and
constraints are unspecified.

## 7. Architecture — v5's MVC and JPA requirements against the modules that exist

Full evidence: `scratchpad/analysis_architecture.md`.

### 7.1 Where the built code already satisfies v5

- **Controllers hold no business logic; entities never cross a boundary.** m6's ingest controller
  carries the rule in its own javadoc ("Controllers only translate HTTP: every decision is the
  application services'", `origin/m6/decision:backend/ingest/.../web/IngestController.java:34-35`).
  Entities stop at the adapter, which is stricter than v5's "never cross a layer boundary".
- **DTO projection**: request and response records with `@Valid` / `@Validated`, e.g.
  `m7/staff-auth:backend/auth/.../web/` and `.../admin/users/`. **No Lombok anywhere**, which the
  build prompt requires (H.2: no Lombok without an ADR) and which v5's `@RequiredArgsConstructor`
  examples assume.
- **`@PreAuthorize` RBAC**: 35 occurrences across 6 files on `m7/staff-auth`, with the enumerating
  deny-by-default test at `AuthorisationMatrixTest.java:37-52`. The four roles match v5 exactly.
- **BCrypt cost 12 and RS256** are both confirmed in code, as v5's FR-07-04 and FR-07-07 require.

### 7.2 Where it does not

- **v5's package tree does not exist.** The base package is
  `io.github.mariusbayizere.fraudshield` (204 files on `m7/staff-auth`), not `com.fraudshield`;
  there is no `controller/`, `service/`, `repository/` or `entity/` package on any branch. Of the
  17 classes v5 names by file, 16 are absent; only `AuthController` exists by that name.
- **v5's per-role frontend (§12) is not built** — the four role dashboards do not exist, and
  `@mui/x-data-grid`, Recharts and Leaflet are not installed, though §12 names DataGrid in most of
  its rows. M8 owns this and is mid-way through the D.3 order.
- **`@PreAuthorize` sits on controllers, not services** as v5 §02 shows, and m6 has none at all
  (API-key scopes instead, which FR-01-05 requires).
- **Only 3 JPA entities exist** (`StaffUserEntity`, `ApiKeyEntity`, `OfficeIpRangeEntity`) against
  v5's 13 tables; m6 has zero `jakarta.persistence` imports in `src/main`.

### 7.3 Why the structure differs, and where ADR 0071 contradicts v5's wording

The built layering is **hexagonal (ports and adapters)**, which the build prompt mandates
(`Master_Build_Prompt.md:657,659`) and ArchUnit enforces per module — `decision`'s `domainIsPure`
and `applicationUsesPortsNotAdapters` (`ArchitectureTest.java:31-59`) are the real rule. v5's
four-layer MVC is a different shape for the same separation, not a stricter one.

The sharp contradiction is persistence. **v5 §02 says "No database queries appear in services" and
§23.1 says "no raw SQL"; ADR 0071 (on `m7/staff-auth`) deliberately keeps explicit SQL** for the
audit chain, the append-only tables, the hypertables and the refresh-token family, because:

1. Hibernate's dirty checking fights append-only triggers;
2. the audit chain depends on insert ordering that Hibernate's flush is free to reorder;
3. refresh-token revocation is set-based and its statement order is what the ADR 0070 race fix
   depends on;
4. hypertables and security-barrier views cannot be modelled by JPA.

`StaffAccountRepository` imports both `EntityManager` and `JdbcTemplate` — the hybrid in one file.
Adopting v5's wording would mean unwinding that, and with it the guarantees the M1 database gate
tests assert.

### 7.4 Stack versions

ADR 0003 already covers this and needs no change: *"Every document that says 'Spring Boot 3',
'React 18', 'TypeScript 5' or 'MUI v5' refers to the successor recorded here; the SRS text itself
is not edited."* Installed today: Spring Boot **4.1.1** (`backend/pom.xml:11-12`), Spring Security
7.1.1, Hibernate 7.4, React **19.3**, MUI **9.4**, TypeScript **6.0.3**, Vite **8.3**, Python
**3.12**. Java 21 and Tailwind 4.3 match v5 as written. v5 §23.1's version column is therefore
**superseded, not conflicting** — except that it is now two majors behind on four of them.

## 8. The ten conflicts that matter most

1. **`institution_id` and row-level security disappear** (S1) — 35 policies, 4 view isolations, every tenant FK, and the M1 database gate.
2. **The audit hash chain cannot compile** (S4) — `audit_row_hash()` loses 11 of its 20 inputs; anchors, verification and the 7-year BNR retention go with it.
3. **`auto_block_events` becomes mutable** (S5) — v5 adds `customer_verified`/`verified_at` to a table whose UPDATE is refused at the database for every role.
4. **`api_keys` is deleted by omission while FR-07-05 still requires API keys** (S6) — the document contradicts itself, and four contract operations lose their table.
5. **`ScoringResult` loses four contract-required fields** (C1, S2) — the OpenAPI contract and its tests would fail, and M4's per-model evidence has nowhere to go.
6. **The ML-fallback path becomes uninsertable** (S3) — a dropped CHECK disjunct blocks exactly the degraded case the reliability section requires.
7. **Precision at 1% FPR ≥ 0.720 returns as a gate metric and as a NOT NULL column** — mathematically unreachable at this base rate (D-01, ceiling ≈ 0.467); the corrected metric is recall at 1% FPR.
8. **Helm replaces the kustomize layout M9 built and validated** (N3) — re-packaging plus re-validation, for no stated gain.
9. **The separate PII vault and `ML_UNAVAILABLE` disappear from the NFR text** (C9, C10) — both are D-20's resolution and a live contract value the console already renders.
10. **FR-08 reporting has no owner** (N1, N2) — 8 new MUST/SHOULD requirements, a new scheduling and PDF stack, and no milestone in the build prompt covers them.

## 9. What this analysis did not do

- Nothing was adopted, and no migration, requirement, ADR or line of code was changed.
- v5's tables 9–13 have no DDL, so their columns could not be compared beyond the five-row summary.
- Statuses in `docs/traceability/v5_coverage.md` are this tree's. M5, M6, M7 and M9 are unmerged;
  a row reading `NOT_STARTED` here may be complete on its own branch.
- v5 §12's per-role screens were compared structurally, not screen by screen; that comparison
  belongs to M8's own review once those screens exist.
