# Review: M1 milestone (contracts and data model), build prompt I.3            Reviewer role: Principal Reviewer
Date: 2026-09-17 (UTC)   Branch/commit: `main` @ 754ade14c1eb0016304d27d3fb200e957dde0703 (M1 contracts and database work, fast-forwarded)   Requirements: FR-01-07, NFR-SEC-03, NFR-SEC-05 (all rows with milestone M1); M1 scope and gate items of D.3   Defects: D-20, D-30, D-31, D-32, D-49
Verdict: **CHANGES_REQUIRED**

Summary: I checked out `main` at 754ade1 into a clean worktree and re-ran `make ci` and the database gate suite there. Everything passed. **The three D.3 M1 gate criteria are met**, and each is backed by a test that has teeth (mutations below). The contracts (OpenAPI 3.1, protobuf, Kafka JSON Schemas, webhook spec) and the Flyway schema cover the D.3 M1 scope list. CI for 754ade1 on `main` is green, with the `stack` and `devcontainer` jobs actually executed. The MLflow memory fix holds with a wide margin.

The milestone cannot close as it stands, for two reasons:
1. **Four of the eight Must rows marked M1 cannot be completed in M1, and there is no written plan for them** (MAJOR-1). D-20 and NFR-SEC-03 are not implemented at all. D-32 and D-49 contain parts that belong to later milestones. Nothing records this: `completed: [M0, M1]` would fail `traceability-check`, and moving the rows silently would be dishonest.
2. **Several M1 schema guarantees have no test** (MAJOR-2). Two mutations survived the full 53-test persistence suite: dropping the 7-year audit retention policy, and letting `is_token` accept any value.

Both fixes are small (documentation plus one test class). A delta re-check of those two items is enough; nothing else needs re-running.
**Open findings: 0 BLOCKER, 2 MAJOR, 4 MINOR, 2 NIT.**

## Checks re-run (command → result)

**Clean worktree.** `git worktree add …/scratchpad/fs-review-m1 754ade14c1eb` gave a fresh checkout with no `.venv`, `node_modules` or `target/`. PATH was `$HOME/.local/bin:$PATH`. Afterwards I removed it with `git worktree remove --force`; `git worktree list` no longer shows it (the two `.claude/worktrees/agent-*` entries were already there and I left them alone). The uv cache, pnpm store and `~/.m2` were **not** bypassed; the lockfiles and checksums bind them.

| Command (in the clean worktree) | Result |
|---|---|
| `uv sync --all-packages --locked` | exit 0, 10 s |
| `cd frontend && pnpm install --frozen-lockfile` | exit 0, 2.7 s |
| `make ci` | **exit 0**, 7 m 17 s |
| ↳ ruff check / format / mypy --strict | clean / 55 files formatted / no issues in 50 files |
| ↳ tools / ml / contracts pytest | 155 passed, 93.34 % / 14 passed, 100 % / **490 passed, 93.21 %** (gate 90 %) |
| ↳ `make test-java` | `SKIPPED: JUnit tests tagged requires-docker require Docker, verified in CI (job: java)`<br>`common`: 1131 tests, 0 failures (MoneyTest 1026, MoneyBoundaryTest 36, DualControlWorkflowTest 34, PersonNameTest 33, ArchitectureTest 2)<br>`persistence` without Docker: 21 tests (DemoSeedGuardTest 13, SyntheticDataFlagTest 5, SyntheticDataGuardCoverageTest 3)<br>Checkstyle 0, SpotBugs clean, JaCoCo gates met |
| ↳ frontend typecheck / lint / format / vitest | clean; 60 tests passed; 100 % statements and branches |
| ↳ governance | defect register current (51)<br>seed up to date<br>`traceability-check: 258 rows, 159 tagged tests, 0 errors, 0 warnings`<br>scope-guard 0<br>`compose-budget: core 3968/4096 MiB … 0 errors`<br>`contract-baselines: merge base 754ade14c1eb with origin/main; 13 published Kafka schemas, proto present; 0 breaking changes` |
| ↳ compose-config / secrets | exit 0<br>gitleaks: 83 commits, no leaks; working tree: no leaks<br>self-test: 37 planted secrets detected, 15 allowlisted fixtures clean |
| ↳ licences | 280 dependencies (39 runtime, 241 dev), 0 violations |
| ↳ stack-test | `SKIPPED: stack-test requires Docker, verified in CI (job: stack)` |
| **M1 database gate** (local PostgreSQL 16.15 + TimescaleDB 2.30.0 at 127.0.0.1:55432): `cd backend && FRAUDSHIELD_TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:55432/postgres?user=postgres" ./mvnw -B -ntp verify -pl persistence -am` | **BUILD SUCCESS, 53 tests, 0 failures, 0 skipped**, 5 m 51 s: DatabaseSecurityTest 20, DemoDataSeederTest 5, DemoSeedGuardTest 13, SyntheticDataGuardCoverageTest 3, SyntheticDataGuardTest 7, SyntheticDataFlagTest 5. Checkstyle, SpotBugs and JaCoCo clean. (`-am` builds only the parent POM, because `persistence` does not depend on `common`.) |
| Cross-check: enums and patterns between the OpenAPI, Kafka and SQL files (ad-hoc script) | Role (4), Channel (6), currencies (10), `Token` pattern `^tok_[A-Za-z0-9]{24,64}$` and the 12 audit event types all agree. **Mismatch:** Kafka `audit-event.writer_partition` allows 0..1023, but the SQL CHECK allows 0..63 (MINOR-4) |
| Database cleanup | 17 databases created by my runs (found by diffing `pg_database` before and after; no other Maven process ran meanwhile) dropped with `DROP DATABASE … WITH (FORCE)`. The database list is identical to the pre-review snapshot |

### CI evidence (public GitHub Actions API, 6 unauthenticated requests)

Runs for `head_sha=754ade1`, all completed:

| Workflow | Run | Event / branch | Jobs (job-level conclusion) |
|---|---|---|---|
| ci | **35227799846** | push / `main` | 7/7 success: traceability/governance, python, java (13:33:22Z–13:35:12Z), pre-commit, licences, frontend, gitleaks |
| stack | **35227800080** | push / `main` | `detect stack input changes` success; `core compose stack healthy + smoke test` **executed**, success (13:33:29Z–13:35:41Z). Only the failure-only steps were skipped |
| devcontainer | **35227799885** | push / `main` | `detect` success; `build devcontainer, post-create make ci, smoke test inside` **executed**, success (13:33:30Z–13:38:53Z). Only `Report post-create log on failure` was skipped |
| ci / stack / devcontainer | 35224607902 / 35224607797 / 35224608001 | push / `m1/database` | run-level success (I did not fetch job details for these, to save API quota) |

Nothing is pending.

**MLflow fix, from the "stack resources" annotations of both executed jobs:**
- MLflow anonymous memory: 410,443,776 B (stack) and 410,607,616 B (devcontainer), about 391 MiB of 1,024 MiB, i.e. **38 %** against the 60 % guard.
- `memory.peak` about 522 MiB.
- `memory.events max 0`, and `oom_kill 0` in **every** container in both jobs.
- Only two MLflow processes (server, uvicorn); the job-runner and Huey processes are gone.

The commit message's earlier figures (830–860 MiB anonymous memory, peak at the limit) are consistent with the root cause. **Owner waiver recorded:** the "3 consecutive devcontainer `workflow_dispatch` runs" condition is waived. Evidence: two executed green devcontainer runs on 754ade1 (branch and `main`), plus the deterministic memory and OOM guard in `smoke-test.sh`.

## Mutation spot checks (what was broken → which test failed)

Each mutation was reverted with `git checkout -- .`, and `git status --short` was clean before the worktree was removed.

| # | Mutation | Run | Result |
|---|---|---|---|
| MU1 | V4: `CALL make_append_only('alert_decisions')` removed **and** V11: `GRANT UPDATE, DELETE ON alert_decisions TO fs_app` (the gate property fully broken for one table) | `-Dtest=DatabaseSecurityTest,SyntheticDataGuardTest` | **killed**: `appRoleCannotUpdateOrDeleteAppendOnlyTables[3]` ("Expecting code to raise a throwable"), `appendOnlyTriggersStopEvenTheOwner`, `everyAppendOnlyTableDeniesUpdateAndDeleteToEveryApplicationRole` |
| MU2 | `SyntheticDataGuard`: `boolean database = checked && databaseHasSyntheticData(...)` → `false` (post-re-check commit 02bb017) | same run | **killed**: `productionRefusesToStartAgainstSeededDatabase`, `lazyInitialisationDoesNotSkipTheCheck`, `applicationWithoutJdbcTemplateIsStillChecked`, `unreachableDatabaseFailsClosed` |
| MU3 | V11: stray `GRANT UPDATE, DELETE ON audit_events TO fs_app`, triggers left in place (a mistaken grant) | `-Dtest=DatabaseSecurityTest` | **killed**: `everyAppendOnlyTableDeniesUpdateAndDeleteToEveryApplicationRole:119 [fs_app UPDATE on audit_events]`. The behavioural gate test still sees 42501 from the trigger, which is correct defence in depth |
| MU4 | V1: tenant policy `USING (institution_id = current_institution())` → `USING (true)` (same run as MU3; different tests) | same run | **killed**: `institutionsSeeOnlyTheirOwnRows:144`, `withoutAnInstitutionNothingIsVisibleOrWritable:165` |
| MU5 | V10: `add_retention_policy('audit_events', drop_after => interval '7 years')` removed | full persistence suite (53 tests) | **SURVIVED** → MAJOR-2 |
| MU6 | V3: `is_token` body → `SELECT p_value IS NOT NULL` (any raw phone or account number accepted in `account_token`, `counterparty_token` and the other token columns) | same run as MU5 (both expected to survive) | **SURVIVED** → MAJOR-2 |

## Milestone walk (I.3 steps 2–5)

### D.3 M1 gate

| Gate criterion | Test (opened) | Asserts the criterion? | Result |
|---|---|---|---|
| OpenAPI validates against the 3.1 schema | `contracts/tests/test_openapi.py::test_document_is_valid_openapi_3_1` (`@req FR-01-07`): `DOC["openapi"].startswith("3.1.")` and `openapi_spec_validator.validate(DOC)` | yes (document 3.1.0, 82 paths, 89 operations) | **met** |
| `UPDATE`/`DELETE` on `audit_events`, `auto_block_events`, `alert_decisions` → permission denied for `fs_app` | `DatabaseSecurityTest.appRoleCannotUpdateOrDeleteAppendOnlyTables` (parameterised over exactly the three tables): as `fs_app` with a tenant set, `UPDATE`, `DELETE` and `TRUNCATE` each assert SQLSTATE `42501`. Backed by `everyAppendOnlyTableDeniesUpdateAndDeleteToEveryApplicationRole` (catalogue privileges for all three application roles) and `appendOnlyTriggersStopEvenTheOwner` | yes (MU1, MU3) | **met** |
| RLS proves institution isolation | `institutionsSeeOnlyTheirOwnRows` (bank A sees only its user, institution, decision and audit row; bank B sees its two users and no A audit rows)<br>`withoutAnInstitutionNothingIsVisibleOrWritable`<br>`rowsForAnotherInstitutionCannotBeWritten` (RLS `WITH CHECK` and the hypertable insert guard)<br>`referencesCannotCrossInstitutions`, `everyForeignKeyBetweenTenantTablesIncludesTheInstitution`, `everyTenantTableIsIsolated` | yes (MU4) | **met** |

### D.3 M1 scope list

| Scope item | Implemented | Tested | Assessment |
|---|---|---|---|
| OpenAPI 3.1: all endpoints, examples for 6 channels, RFC 9457 | `contracts/openapi/fraudshield-api.yaml` (89 operations), `authorisation-matrix.yaml`, `schema-examples.yaml` | `test_ingest_examples_cover_all_six_channels_and_validate` (exactly 6 channels, each validates; USSD fingerprint null; AGENT_BANKING has `agent_id`)<br>`test_srs_named_routes_exist` (untagged, NIT-2)<br>`test_problem_examples_validate`, `test_problem_types_come_from_the_catalogue`<br>authorisation matrix tests | done |
| Protobuf for scoring | `contracts/proto/fraudshield/scoring/v1` (2 RPCs), buf (ADR 0016) | `test_proto.py`, `test_proto_breaking.py` (buf lint; breaking and compatible cases); CI and `make governance` baseline guard against `origin/main` | done |
| Kafka JSON Schemas | 13 schemas + `topics.yaml`, 13 examples | `test_events.py`, `test_compatibility.py`, `test_baseline_guard.py`; `schemas/` byte-identical to `baseline/` (`diff -rq`: only the baseline README differs) | done (MINOR-4 on `writer_partition`) |
| Webhook schema and signature spec | `contracts/webhooks/decision-final.md`, signature and ordering vectors | `test_webhooks.py` (vectors, boundaries and rotation, constant-time compare, retry signing, body valid on Kafka and in the API) | done |
| Flyway: all SRS tables + D-30/D-31 additions | V1–V11: all 13 SRS schema tables and all 19 D-31 tables exist, along with the `users` columns, `alert_queue_entries.version` and `UNIQUE (institution_id, employee_id)` | Table existence is enforced implicitly (V11 grants every D-31 table, so a missing table fails migration) | done |
| Hypertables | `transactions`, `fraud_scores`, `shadow_scores`, `audit_events` | `everyTenantTableIsIsolated` (no direct SELECT; view-only reads); `theChainSurvivesCompression` | done |
| Compression and retention policies | V10: compression 30 d / 30 d / 7 d / 30 d; retention `shadow_scores` 180 d, `audit_events` 7 y | **no test asserts any policy** (MU5 survived); the compression test calls `compress_chunk` manually | implemented, untested → MAJOR-2 |
| Continuous aggregates | `account_activity_hourly`, `merchant_activity_15m` + refresh policies + tenant views | **no test** | implemented, untested → MAJOR-2 |
| Partial unique indexes (one production, one shadow) | V7 `model_versions_one_production` / `_one_shadow` (+ previous production, one active retraining job) | `atMostOneProductionAndOneShadowModel` (23505 for a second of each) | done |
| CHECK constraints | extensive | selected: commit/undo exclusivity, configuration self-review and stacking, verification token single use, chain timestamps; **`is_token` untested (MU6)** | mostly done → MAJOR-2 |
| RLS by institution | `enable_tenant_isolation`; hypertables through security-barrier views + insert trigger; derived views `security_invoker = true` | gate tests above | done (deviation from D-31's "RLS on every tenant table" recorded in ADR 0017) |
| Roles (`fs_app`, `fs_app_readonly`, `fs_compliance_ro`, `fs_migrator`) | `bootstrap.sql` (NOSUPERUSER … NOBYPASSRLS) | `applicationRolesHaveNoElevatedAttributesAndOwnNothing`, `readOnlyRolesCannotReadCredentialMaterial` | done |
| INSERT/SELECT-only grants on append-only tables | V11 + `make_append_only` triggers | MU1, MU3 | done |
| Audit hash chain | V8: database-assigned chain per writer partition, `verify_audit_chain`, `audit_anchors` schema | `theChainIsAssignedByTheDatabaseAndDetectsTampering`, `theChainSurvivesCompression`, `retentionFollowsTheChainOrderNotTheWritersEventTime` (verified in depth in `database-recheck.md`) | done (signing job and CLI come with the audit service) |

### Traceability rows with milestone M1

| Row | Criterion / resolution | Implementation | Tests opened | Remaining (milestone) | Recommended status |
|---|---|---|---|---|---|
| **FR-01-07** (M) | "spec validates against OpenAPI 3.1; all endpoints documented; East African channel examples present" | `contracts/openapi/fraudshield-api.yaml`; ADR 0011 | `test_document_is_valid_openapi_3_1`, `test_operations_have_unique_ids_summaries_and_tags` (≥ 60 operations), `test_ingest_examples_cover_all_six_channels_and_validate` (all `@req FR-01-07`); `test_srs_named_routes_exist` (untagged) | The title says "served at `/api/docs`". Serving it is controller work, asserted in M6 per ADR 0011. The acceptance criterion itself is met now | **DONE** (evidence: the three tests, 754ade1, this review; row note: "`/api/docs` serving asserted in M6 (ADR 0011)") |
| **NFR-SEC-03** (M) | "name and phone stored as opaque tokens; actual PII in separate encrypted PII store; no raw PII in analyst-facing API responses; inspector test on analyst endpoints" | **Partial:**<br>- contracts: `Token` pattern everywhere; `test_no_raw_personal_data_fields_in_any_schema`; 4 `test_events.py` tests; `test_validation_vectors.py`<br>- database: `is_token` CHECK on every customer-identifying column<br>**Missing:** the encrypted PII store, the tokenisation service, the analyst-endpoint inspector test | contract tests assert the contract half. The DB tag sits on `institutionsSeeOnlyTheirOwnRows`, which is RLS, not tokenisation (MINOR-3). The `is_token` CHECK is untested (MU6) | PII store and tokenisation at ingestion: **M6** (with D-20)<br>inspector test on analyst endpoints: **M7** | **IN_PROGRESS**; milestone → **M7** with a recorded decision (MAJOR-1) |
| **NFR-SEC-05** (M) | "INSERT-only for the app role; no UPDATE or DELETE; attempted UPDATE returns permission denied; security test" | `V8__audit_log.sql`, `V11__grants.sql`, `V1` `forbid_modification` | `appRoleCannotUpdateOrDeleteAppendOnlyTables` asserts exactly this (MU1, MU3). **But it is not tagged NFR-SEC-05**: the tag sits on `readOnlyRolesCannotReadCredentialMaterial`, which asserts something else (MINOR-3) | none | **DONE** once the tag is moved (evidence: the gate test, 754ade1, this review) |
| **D-20** (M) | Separate PII vault DB with its own role and credentials; AES-256-GCM envelope encryption; analyst-facing role has no grant; encrypted volumes and KMS; Redis TLS; no raw PII in Redis | **Not implemented.** The only artefact is the M0 Compose `pii-vault` PostgreSQL instance with a superuser password: no schema, no roles, no grants, no encryption code, no test. The threat model (§3.4) already plans "M6, M9" | none | vault schema, roles, grants and envelope encryption with a key-provider interface: **M6** (first consumers are tokenisation at ingestion and customer notifications)<br>KMS, encrypted volumes, Redis TLS, network policy: **M9** | **NOT_STARTED**; milestone → **M6** (M9 note) with a recorded decision (MAJOR-1) |
| **D-30** (M) | Event-sourced split; append-only `customer_notifications`, `unblock_events`, `account_freeze_events`; view `v_auto_block_status` | V5 (+V1, V4) | `everyAppendOnlyTableDeniesUpdateAndDeleteToEveryApplicationRole` asserts the new tables are append-only. **No test derives state through `v_auto_block_status`** (and PB-7: `fs_compliance_ro` cannot query it) | view test (MAJOR-2) | **DONE** after MAJOR-2. The row lists ADR 0017 under `deviations`, but the resolution is applied as written, so move ADR 0017 to `implementation`, or state the deviation |
| **D-31** (M) | Added tables and columns; `institution_id` + RLS on every tenant table; `UNIQUE (institution_id, employee_id)` | V2, V3, V6, V7, V9, V11: all 19 tables and all listed columns verified by inspection | RLS and FK tests above; table existence is enforced through the V11 grants | none. Deviations: hypertables use views + insert trigger instead of RLS; `outbox_events` has no RLS (ADR 0017) | **DONE_WITH_DEVIATION** (ADR 0017) |
| **D-32** (M) | 12 event types with action; `prev_hash`/`row_hash` chain; daily signed Merkle root in `audit_anchors`; `fraudshield audit verify` CLI; INSERT/SELECT only; compliance read role; compression 30 d; retention 7 y | Schema, 12 types, chain, `verify_audit_chain`, anchors table, compliance role, compression and retention policies | chain tests (strong); 12-type CHECK not directly tested (it agrees with the OpenAPI and Kafka enums); **retention and compression policies untested (MU5)** | policy test (MAJOR-2, M1)<br>signing job and CLI: audit service **M7** (row note already says so) | **IN_PROGRESS**; milestone → **M7** with the M1 evidence in the note (MAJOR-1) |
| **D-49** (M) | Self-host PG16 + TimescaleDB on Kubernetes; ADR confirming the licence and listing the features used | ADR 0018; image pinned `timescale/timescaledb:2.30.0-pg16` in Compose and `TestDatabase` | features exercised by the suite (hypertables, compression; retention and aggregates only as migrations) | Kubernetes self-hosting (CloudNativePG or equivalent): **M9**; PB-6 image licence inventory (M9) | **IN_PROGRESS**; milestone → **M9** with a recorded decision, ADR 0018 as evidence (MAJOR-1) |

**D-20 and NFR-SEC-03: BLOCKER or recordable gap?** A **recordable gap, not a BLOCKER.**
- Neither the D.3 M1 gate nor the D.3 M1 scope list names the PII vault or tokenisation. The rows got M1 from the author's own seed (`traceability_seed.py`, `dict.fromkeys(("D-20", …), "M1")`, `"NFR-SEC-03": "M1"`).
- No raw customer PII can enter the M1 schema by design: every customer identifier column is `CHECK (is_token(...))`, and the contracts reject raw PII fields. There is also no data flow yet that could carry PII.
- D.3 explicitly allows closing with "the gap … recorded honestly".
- It becomes a BLOCKER if either row is marked DONE, or if M1 is tagged with the rows silently re-dated. Until a written re-plan exists, it is MAJOR-1.

### Cross-component consistency (I.3 step 3)

- Contract tests: green (490).
- Kafka `schemas/` identical to `baseline/`. `fs-contract-baselines` reported 0 breaking changes, but on `main` the merge base is HEAD itself, so the guard is trivially satisfied here. It bites on branches (residual risk).
- Proto passes buf lint and the breaking-change cases.
- `test_authorisation_matrix.py::test_domain_refusals_match_the_contract` parses `DualControlException.java` and requires every Java refusal to map to a documented status and catalogued problem type, and the refusal set to equal `DOMAIN_OPERATIONS`. It passed (PB-5: coverage of operations per refusal is partial, backlogged).
- `DualControlWorkflowTest` 34/34.
- Database ↔ contracts: roles, channels, currencies, token pattern and audit event types agree. `writer_partition` does not (MINOR-4).
- No generated server or client code exists yet, so "schemas identical to generated code" does not apply until M5/M6.

### Threat-model delta (I.3 step 4)

`docs/security/threat_model.md` exists (M0 condition M-1 / GOV-13 delivered). It covers the database (§3.1: roles, append-only, chain, retention order, RLS, FK oracles, pre-authentication lookups, outbox, credential columns, SECURITY DEFINER), demo seeding and the banner (§3.2, including the new `SyntheticDataGuard`), the public API contract (§3.3), planned Kafka, webhooks, scoring, vault and front end (§3.4), and dev/CI (§3.5). Gaps (MINOR-2):

| Missing element | Threat | Suggested entry |
|---|---|---|
| CI stack diagnostics (720a5ee, d96638f): resources and service logs published as **public** annotations and artifacts | I: credentials in MLflow, object-store or TimescaleDB logs. Redaction replaces only `.env` values of ≥ 8 characters plus `://user:pass@` in MLflow command lines; values from `.demo-credentials` and anything shorter or not in `.env` are not redacted | control: pure-bash redaction that fails closed without `.env`; residual: non-`.env` secrets; consider an allowlist-based excerpt |
| `fs_migrator` credential | T/R: the owner can `DROP TRIGGER` / `DISABLE TRIGGER` and rewrite append-only tables. Only `audit_events` is hash-chained; decisions, blocks, labels and configuration versions carry no tamper evidence | control: migrator credentials only in the migration job, never in services (M9); anchors (M7); state the residual for non-audit tables |
| Webhook signing secrets at rest (`webhook_secret_ciphertext`, `webhook_secret_key_id`) | I: the key-management owner and key rotation are unspecified | planned control and milestone (M6/M9, with the D-20 key provider) |
| D-20 inconsistency | §3.4 says the vault is "M6, M9" while the traceability row says M1 | align after MAJOR-1 |

### Architecture fitness (I.3 step 5)

- No hot path exists, so the C.2 latency budget does not apply.
- `ArchitectureTest` is unchanged since `m0-complete` (empty diff) and green.
- New module `backend/persistence` is documented: `docs/architecture/repository_layout.md` row, ADR 0017 (schema and security), ADR 0019 (seeding and banner), ADR 0020 (EPL-2.0 runtime binaries). It depends on Spring JDBC, Flyway and the PostgreSQL driver only, not on `common`.
- `common` gained `config` (dual control), `identity` (PersonName) and `transaction.Channel`: pure domain, within the ArchUnit rules (ADR 0013, 0014).
- `backend/spotbugs-exclude.xml` holds one scoped, justified exclusion (`PersonName` / `IMPROPER_UNICODE`).
- Runtime dependencies rose from 0 to 39, with 0 licence violations.

### Commits after the database re-check (reviewed here)

- **02bb017** `SyntheticDataGuard` (PB-15, PB-16). A `spring.factories` `ApplicationPreparedEvent` listener with its own JDBC connection, failing closed. SQLSTATE 42883/3F000 (not migrated) counts as clean; everything else stops startup. A second DataSource, or a DataSource without `spring.datasource.url`, is refused. Tests cover `prod`/no profile, lazy initialisation, no `JdbcTemplate`, an unreachable DB, demo, clean/unmigrated and marker visibility. MU2 killed. Sound; NIT-1 for edges.
- **720a5ee, d96638f** diagnostics: no functional risk; threat-model entry missing (MINOR-2).
- **754ade1** `MLFLOW_SERVER_ENABLE_JOB_EXECUTION=false` plus the OOM-kill and 60 % memory guard. The guard is deterministic and reads the kernel's cgroup counters. Verified in both executed CI jobs.
- **48f70d7**: docs only.

## Findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| MAJOR-1 | MAJOR | `docs/traceability/requirements.yaml` (D-20, NFR-SEC-03, D-32, D-49); `tools/src/fraudshield_tools/traceability_seed.py` (milestone assignment) | Four Must rows with milestone M1 cannot reach a final status in M1:<br>- D-20: not started (no vault schema, role, grants or encryption).<br>- NFR-SEC-03: vault, tokenisation service and analyst inspector test missing.<br>- D-32: signing job and CLI are audit-service work.<br>- D-49: Kubernetes self-hosting is M9.<br>There is no written re-plan. With `completed: [M0, M1]`, `traceability-check` fails ("Must row in closed M1 has status …"). Changing milestones without a recorded reason would be an unrecorded gap (D.3). | 1. Write the decision: an ADR, or an amendment to ADR 0017 (D-20/NFR-SEC-03/D-32) and ADR 0018 (D-49). It must state why and which parts land in which milestone: D-20 → M6 (vault DB schema, dedicated role, no grant for `fs_app`/`fs_app_readonly`/`fs_compliance_ro`, AES-256-GCM envelope encryption behind a key-provider interface, tests) + M9 (KMS, encrypted volumes, Redis TLS, NetworkPolicy). NFR-SEC-03 → M7 (analyst-endpoint inspector test), vault part M6. D-32 → M7 (signed Merkle-root job, `fraudshield audit verify`). D-49 → M9.<br>2. Apply it through the seed's milestone overrides, with a comment citing the ADR; keep `fs-traceability-seed --check` green.<br>3. Put the M1 evidence already delivered in each row's `notes`.<br>4. Align threat model §3.4. | OPEN |
| MAJOR-2 | MAJOR | `DatabaseSecurityTest` (missing tests); V3 `is_token`, V5 `v_auto_block_status`, V10 policies and aggregates | M1 resolution elements with no test. **Mutations MU5 (audit retention removed) and MU6 (`is_token` accepts anything) survived all 53 tests.** Also untested: compression policies, both continuous aggregates and their refresh policies, and state derivation through `v_auto_block_status` (D-30). An edit to `drop_after => interval '7 days'` would silently destroy regulatory audit evidence. A loosened token CHECK would let raw phone or account numbers into the main database, the database half of NFR-SEC-03. | Add tests:<br>(a) `timescaledb_information.jobs`: `audit_events` retention exactly 7 years and compression 30 days; `shadow_scores` retention 180 days; compression 30 d on `transactions`/`fraud_scores`, 7 d on `shadow_scores`.<br>(b) Both continuous aggregates exist with their refresh policies, and are readable only through their tenant views.<br>(c) As `fs_app`, inserting a raw E.164 phone and a raw account number into `account_token`/`counterparty_token`/`device_token` → 23514, and a valid `tok_…` value is accepted.<br>(d) `v_auto_block_status` goes from blocked → SMS sent → verified → unblocked → frozen, and another tenant's rows are invisible through it.<br>Tag (a) D-32, (c) NFR-SEC-03 and FR-01-02, (d) D-30. Re-run MU5 and MU6 and show them killed. | OPEN |
| MINOR-1 | MINOR | `docs/backlog/governance.md`, `docs/backlog/product.md` | Backlog items due in M1 are still open:<br>- **GOV-2** (high, "before M1 closes": fail on runtime-skipped tests under `REQUIRE_DOCKER`; not implemented. Current risk is low because `TestDatabase` throws rather than skips without Docker)<br>- GOV-4, GOV-5, GOV-7, GOV-10 (evidence verifier still checks only the run conclusion, `evidence.py:81`), GOV-14<br>- **PB-20** (due "M1 merge": no governance check stops in-place edits of merged `V*.sql` files, and V1–V11 are now on `main`)<br>- GOV-9 (owner action: default branch and protection) | In the closing commit, either implement them (PB-20 and GOV-2 recommended now) or re-date each with a one-line reason. Record GOV-9 as an open owner action in the status block | OPEN |
| MINOR-2 | MINOR | `docs/security/threat_model.md` | Missing: the public CI diagnostics flow and its redaction limits; `fs_migrator` misuse (dropping append-only triggers; non-audit append-only tables have no tamper evidence); webhook-secret key management; D-20 milestone inconsistency | Add the four entries in the delta table above | OPEN |
| MINOR-3 | MINOR | `DatabaseSecurityTest.java:141, 345`; `test_openapi.py::test_srs_named_routes_exist` | Requirement tags in the wrong places:<br>- `@Tag("NFR-SEC-05")` is on `readOnlyRolesCannotReadCredentialMaterial`, while the test that asserts NFR-SEC-05 (`appRoleCannotUpdateOrDeleteAppendOnlyTables`) lacks the tag.<br>- `@Tag("NFR-SEC-03")` is on the RLS test, so a tokenisation row counts an unrelated test as coverage.<br>- `test_srs_named_routes_exist` supports FR-01-07 "all endpoints documented" but is untagged. | Move NFR-SEC-05 to the gate test; replace the NFR-SEC-03 tag on the RLS test (the MAJOR-2 (c) test carries it); tag the routes test FR-01-07 | OPEN |
| MINOR-4 | MINOR | `contracts/kafka/schemas/audit-event.schema.json` (`writer_partition` max 1023) vs `V8__audit_log.sql` (`BETWEEN 0 AND 63`) | An audit event valid on Kafka can be rejected by the database (dead-lettered at the first audit writer, M6). The threat model says "up to 64 partitions" | Decide on one range. Tightening a published schema is a breaking change under ADR 0012, so either widen the SQL CHECK in a new migration (V12) or publish a new schema version. Add a test comparing the two | OPEN |
| NIT-1 | NIT | `SyntheticDataGuard.requireCoverage` | `getBeanNamesForType(DataSource.class, true, false)` (no eager init) can miss a DataSource produced by a `FactoryBean` or declared with a non-`DataSource` return type. R2DBC connection factories are not covered. The guard applies only to applications with `fraudshield-persistence` on the classpath | ADR 0019: state that every Spring service with database access must depend on the module (or its guard), and that R2DBC is out of scope; optionally match `ConnectionFactory` too | OPEN (backlog) |
| NIT-2 | NIT | FR-01-07 row | The title's "served at `/api/docs`" is not part of the acceptance criterion and is M6 work | Add a row note when closing it | OPEN |

Resolved in this cycle: all findings of `contracts-review.md` and its follow-ups (final re-check APPROVED_WITH_MINORS); database review MAJOR-1..3 (re-check APPROVED_WITH_MINORS); PB-15 and PB-16 (02bb017, verified by MU2 and by the tests listed); M0 M-1 / GOV-13 (threat model exists); the devcontainer MLflow HTTP 500 (754ade1, measured).

## Evidence reproduced (claimed vs measured)

| Claim | Measured | Match |
|---|---|---|
| 53 database tests pass on PG16 + TimescaleDB 2.30.0 | 53 run, 0 failures, 0 skipped (20/5/13/3/7/5) | yes |
| Gate: UPDATE/DELETE denied for `fs_app` on the three tables | test asserts 42501 for UPDATE, DELETE and TRUNCATE; MU1 and MU3 killed | yes |
| Gate: RLS isolates institutions | MU4 killed by two tests | yes |
| Gate: OpenAPI validates against 3.1 | `validate(DOC)` passes; `openapi: 3.1.0` | yes |
| `make ci` green without Docker, skipping visibly | exit 0; both SKIPPED lines present | yes |
| `SyntheticDataGuard` survives lazy initialisation and missing beans, fails closed (02bb017) | tests pass; MU2 killed by 4 tests | yes |
| MLflow HTTP 500 caused by job-execution processes; fixed; guard < 60 % and no OOM kills | both executed CI jobs on 754ade1: MLflow anon about 391 MiB / 1,024 MiB (38 %), peak about 522 MiB, `max 0`, `oom_kill 0` in all containers; job-runner and Huey processes absent | yes |
| ci, stack and devcontainer green for 754ade1 (branch runs 35224607902 / 35224607797 / 35224608001) | branch runs success at run level; `main` runs 35227799846 (7/7), 35227800080 (stack executed), 35227799885 (build-and-verify executed) all success at job level | yes |
| D-31 tables and columns added | all 19 tables, the `users` columns, `alert_queue_entries.version` and the composite employee unique constraint found; all 13 SRS schema tables present | yes |
| Compression/retention "M1" (D-32 note) | policies present in V10, **not tested** | partial (MAJOR-2) |
| Traceability: 258 rows, 0 errors | reproduced (159 tagged tests) | yes |

## Residual risks

- Docker suites still run only in CI on the reference laptop (ADR 0010). The local PostgreSQL run reproduces the database gate, but it is not the Compose image.
- On `main`, `fs-contract-baselines --against origin/main` compares HEAD with itself. Breaking-change protection depends on feature branches running the guard before the fast-forward, and on branch protection (GOV-9, still not in place).
- Only `audit_events` has tamper evidence. The other append-only tables rely on grants and triggers that `fs_migrator` or a superuser can bypass. Anchors are unsigned until M7 (R-2).
- The MLflow memory guard fixes today's cause. A future MLflow upgrade that re-enables background processes will fail the smoke test deterministically, which is intended, but it will block unrelated pushes to `main`.
- The CI diagnostics are public. The redaction covers only `.env` values of 8 or more characters.
- Carried backlog due later: PB-7 (compliance cannot read `v_auto_block_status`), PB-8, PB-10, PB-12, PB-18 (audit writer isolation and retry, M6), GOV-8 (image digests, M9).

## Decisions

### (a) Close M1 and tag `m1-complete`: **not yet**

Closing needs MAJOR-1 and MAJOR-2 fixed, plus a delta re-check that:
1. reads the ADR and the seed change;
2. re-runs the persistence suite against the local PostgreSQL;
3. re-runs MU5 and MU6 and confirms both are killed;
4. confirms `traceability-check` is at 0 errors with `completed: [M0, M1]`.

MINOR-1..4 and the NITs may go into the closing commit or be re-dated in the backlog with reasons (owner pace direction). They do not need a re-review.

### (b) Closing commit contents (after the delta re-check is APPROVED/APPROVED_WITH_MINORS)

1. This record, `docs/reviews/M1/milestone-review.md`, and the delta re-check appended to it.
2. `docs/traceability/requirements.yaml` (evidence entries must start with an existing path or an ancestor commit):
   - **FR-01-07** DONE: `contracts/tests/test_openapi.py` (tagged tests), `754ade1`, this review; note on `/api/docs` (M6).
   - **NFR-SEC-05** DONE: `backend/persistence/src/test/java/…/DatabaseSecurityTest.java` (`appRoleCannotUpdateOrDeleteAppendOnlyTables`, tag moved), `754ade1`, this review.
   - **D-30** DONE: `DatabaseSecurityTest` (append-only tables + the new `v_auto_block_status` test); ADR 0017 moved out of `deviations`, or the status changed to DONE_WITH_DEVIATION if a deviation is stated.
   - **D-31** DONE_WITH_DEVIATION: `deviations: docs/adr/0017-data-model-and-database-security.md`.
   - **D-20** NOT_STARTED → M6; **NFR-SEC-03** IN_PROGRESS → M7; **D-32** IN_PROGRESS → M7; **D-49** IN_PROGRESS → M9. Milestones changed through the seed with the ADR reference (MAJOR-1).
3. The re-rendered `requirements_matrix.md`; `milestones.yaml` with `completed: [M0, M1]`, `current: M2`.
4. `CHANGELOG.md` M1 entry and the M1 status block (Part J), including the PB-20 note that local volumes migrated before the merge need `make down -v`.
5. After the push: ci 7/7 with `stack` and `build-and-verify` **executed** and green on the closing commit (job-level conclusions), then the annotated tag `m1-complete`.

### Recommended traceability status per M1 row

| Row | Recommendation |
|---|---|
| FR-01-07 | DONE |
| NFR-SEC-03 | IN_PROGRESS, re-milestone M7 (vault part M6) |
| NFR-SEC-05 | DONE (after the tag fix) |
| D-20 | NOT_STARTED, re-milestone M6 (+M9) |
| D-30 | DONE (after the MAJOR-2 view test) |
| D-31 | DONE_WITH_DEVIATION (ADR 0017) |
| D-32 | IN_PROGRESS, re-milestone M7 (after the MAJOR-2 policy test, M1 part evidenced in notes) |
| D-49 | IN_PROGRESS, re-milestone M9 (ADR 0018 as evidence) |

---

Housekeeping: the review worktree `…/scratchpad/fs-review-m1` has been removed, and `git worktree list` no longer lists it. The 17 databases my runs created on 127.0.0.1:55432 were dropped, and the database list matches the pre-review snapshot. The main checkout was not touched: its existing modification to `docs/reviews/M1/database-recheck.md` and the untracked `.claude/` directory were already there. I used 6 GitHub API requests.

## Delta re-check

Date: 2026-09-17 (UTC)   Branch/commit: `m1/close` @ 837f31d43d9d (on top of `main` 754ade1: 2f40a67 tests and tag fixes, 31774f5 migration guard, 837f31d re-plan and closing docs)   Reviewer role: Principal Reviewer (independent)
Scope: what Decisions (a) asks for (ADR and seed change, persistence suite, MU5 and MU6, `traceability-check` with `completed: [M0, M1]`), plus PB-20 and a check of how MINOR-1..4 and the NITs were handled. Per the owner's pace direction, only BLOCKER and MAJOR findings are raised; smaller points go to the backlog. The GitHub API was not called.
Verdict: **APPROVED_WITH_MINORS**

### Checks re-run (clean worktree at 837f31d, `PATH=$HOME/.local/bin:$PATH`)

| Command | Result |
|---|---|
| `uv sync --all-packages --locked` | exit 0 |
| `cd backend && FRAUDSHIELD_TEST_POSTGRES_URL=… ./mvnw -B -ntp verify -pl persistence -am` (local PG16 + TimescaleDB 2.30.0) | **BUILD SUCCESS, 60 tests, 0 failures, 0 skipped**, 3 m 22 s. DatabaseSecurityTest 20, DemoDataSeederTest 5, DemoSeedGuardTest 13, SyntheticDataGuardCoverageTest 3, SyntheticDataGuardTest 7, SyntheticDataFlagTest 5, **SchemaPoliciesTest 7**. Checkstyle 0 violations, SpotBugs clean |
| MU5: V10 `SELECT add_retention_policy('audit_events', drop_after => interval '7 years');` deleted; `-Dtest=SchemaPoliciesTest -Dsurefire.failIfNoSpecifiedTests=false` | **killed**: `compressionAndRetentionPoliciesMatchTheRetentionRules:76`. The policy map lacked `audit_events:policy_retention=7 years`. BUILD FAILURE, 1 of 7 failed |
| MU6: V3 `is_token` body → `SELECT p_value IS NOT NULL`; same test selection | **killed**: `rawPhoneAndAccountNumbersAreRejectedInTokenColumns[1..4]` (`account_token`, `counterparty_token`, `device_token`, `agent_token`). 4 of 7 failed |
| Restore | `git checkout -- .` after each mutation; `git status --short` empty |
| `uv run fs-traceability-seed --check` | `traceability seed up to date`, exit 0 |
| `uv run fs-traceability check` (`milestones.yaml`: `current: M2`, `completed: [M0, M1]`) | `258 rows, 171 tagged tests, 0 errors, 0 warnings`, exit 0 |
| Does that check actually catch a problem? (D-32 milestone temporarily set back to M1) | `ERROR D-32: Must row in closed M1 has status IN_PROGRESS` → 2 errors. Restored → 0 errors |
| `uv run fs-migration-guard --against 754ade1` | `11 merged migrations, 0 changed`, exit 0 |
| Does the guard actually catch a problem? (one line appended to V1) | `ERROR …V1__tenancy_and_common_functions.sql: modified after it was merged…`, exit 1. Restored |
| `uv run pytest tools/tests/test_migration_guard.py` | 5 passed |
| Database cleanup | 9 databases created by these runs (found by diffing `pg_database` before and after) dropped with `DROP DATABASE … WITH (FORCE)`. The list now matches the snapshot taken before the runs (`fraudshield_db`, `postgres`, `template0`, `template1`) |

### MAJOR-1: **RESOLVED**

- **The re-plan is honest and complete.** `docs/adr/0021-m1-scope-replan.md` takes each of the four rows in turn:
  - what M1 delivered;
  - what remains;
  - the new milestone, and why that milestone (it builds the component the row needs).

  It matches this review's recommendation exactly:
  - D-20 → M6, with KMS, encrypted volumes, Redis TLS and NetworkPolicy in M9.
  - NFR-SEC-03 → M7, with the vault and tokenisation in M6.
  - D-32 → M7: signed Merkle-root job and `fraudshield audit verify`.
  - D-49 → M9: Kubernetes.

  The "delivered" column claims nothing that is missing. D-20 honestly lists only the Compose instance. D-49's claim that the features are "exercised by tests" is now true through SchemaPoliciesTest.
- **The seed applies the re-plan, citing the ADR.** In `traceability_seed.py`:
  - `DEFECT_MILESTONES` now keeps only D-30 and D-31 on M1.
  - D-20, D-32 and D-49 have explicit entries, under a comment citing ADR 0021 and MAJOR-1.
  - `ROW_MILESTONE_OVERRIDES["NFR-SEC-03"] = "M7"` carries the same reference.

  `--check` is green.
- **The moved rows' statuses are justified.**
  - D-20 is NOT_STARTED / M6.
  - NFR-SEC-03, D-32 and D-49 are IN_PROGRESS on M7, M7 and M9.
  - Each row's `notes` records the M1 part delivered and the remaining part per milestone, and `deviations` cites ADR 0021.
- **The remaining M1 rows' statuses are justified by their evidence.**
  - FR-01-07 DONE. Evidence: tagged `test_openapi.py` tests (the SRS route test is now tagged too), `test_openapi_examples.py:84` (tagged), 754ade1, this review. The `/api/docs` note closes NIT-2.
  - NFR-SEC-05 DONE. Evidence: `appRoleCannotUpdateOrDeleteAppendOnlyTables`, which now carries the tag.
  - D-30 DONE. Evidence: the append-only test plus `autoBlockStateIsDerivedFromAppendOnlyFacts`, which walks blocked → SMS sent → verified → unblocked → frozen and checks that bank B sees nothing. ADR 0017 was moved from `deviations` to `implementation`, as asked.
  - D-31 DONE_WITH_DEVIATION. ADR 0017 is cited, and the deviation (hypertables use views plus an insert guard; `outbox_events` has no RLS) is spelled out in `notes`.
- **Threat model §3.4 is aligned.** The PII vault row now reads "M6 (envelope encryption, key provider) / M9 (TLS, KMS, volumes, network policy)", cites ADR 0021, and names the interim control: token-only columns.
- **Remaining conditions for the closing commit:**
  - Several `evidence` entries cite "milestone-review.md (… and delta re-check)", so this section must be appended before the tag.
  - `milestones.yaml` was set to `completed: [M0, M1]` ahead of this verdict. That is consistent once this APPROVED_WITH_MINORS is recorded.

### MAJOR-2: **RESOLVED**

`SchemaPoliciesTest` covers every item MAJOR-2 asked for:
- **(a) Compression and retention policies.** An exact `containsExactlyInAnyOrderEntriesOf` over `timescaledb_information.jobs`: audit retention 7 years and compression 30 days; shadow retention 180 days and compression 7 days; compression 30 days on `transactions` and `fraud_scores`. Tagged D-32 and D-49.
- **(b) Continuous aggregates.** Both aggregates and their refresh schedules (15 min and 5 min). No direct SELECT for `fs_app` or `fs_app_readonly`, SELECT only through the `v_` views. Tenant isolation checked through both views, including rows not yet materialised.
- **(c) Token columns.** Raw E.164 phone numbers, local phone numbers, 16-digit card numbers, too-short tokens and wrong-case prefixes are rejected with SQLSTATE 23514 in all four `transactions` token columns. A valid `tok_…` value is accepted. Tagged NFR-SEC-03 and FR-01-02.
- **(d) `v_auto_block_status`.** The state walk and the cross-tenant check described under D-30 above. Tagged D-30.

MU5 and MU6 are both killed (table above). No main-source migration changed in the three commits (`git diff --stat 754ade1 837f31d -- backend/persistence/src/main` is empty), so the PB-20 guard is satisfied.

### MINOR-1..4 and NITs: how each was handled

| Finding | Handling | Check |
|---|---|---|
| MINOR-1 | PB-20 implemented: `migration_guard.py`, 5 tests, in `make governance` and the CI governance job (`fetch-depth: 0`). GOV-2 → M3, GOV-4 → M3, GOV-5 → M2, GOV-7 → M2, GOV-10 → M2, GOV-14 → M3, each with a one-line reason. GOV-9 recorded as an open owner action, not verifiable from the build session | honest; GOV-2's reason (no runtime-skip form exists yet; `TestDatabase` throws) is accurate. GOV-9 must also appear in the Part J status block, per Decisions (b) item 4 |
| MINOR-2 | Threat model: public CI diagnostics, including their redaction limits (non-`.env` and short secrets); `fs_migrator` misuse, with the residual for non-audit append-only tables stated; webhook secrets at rest (M6/M9); D-20 alignment | complete |
| MINOR-3 | NFR-SEC-05 tag moved to the gate test; NFR-SEC-03 tag removed from the RLS test; `test_srs_named_routes_exist` tagged FR-01-07 | verified in the diff |
| MINOR-4 | Backlog PB-21 (due M6, before the first audit writer); acceptance respects ADR 0012 and immutable migrations | acceptable deferral: no audit writer exists yet |
| NIT-1 | Backlog PB-22 (due M5) | ok |
| NIT-2 | FR-01-07 row note | ok |

### New findings

BLOCKER: none. MAJOR: none.

### Backlog candidates (MINOR/NIT, one line each)

- MINOR: `rawPhoneAndAccountNumbersAreRejectedInTokenColumns` checks only the four `transactions` columns. The other `CHECK (is_token(...))` columns (V3:121, V5:8/25/110) are not asserted per column. A catalogue test ("every `*_token text` column carries `is_token`") would back the "every customer identifier column" claim in the NFR-SEC-03 note and the threat model.
- NIT: `migration_guard.blob_id` hashes working-tree bytes. A checkout with eol or filter attributes (for example `autocrlf` on Windows) would report false "modified" errors. Comparing `git hash-object --path` (or `git diff --name-status base -- MIGRATIONS`) would avoid that. Fold into PB-22 or a GOV item.
- NIT: the guard does not flag a new migration whose version sorts below the highest merged one. Flyway refuses that on migrated databases unless `outOfOrder` is enabled.
- NIT: `tools/tests/test_migration_guard.py` is tagged D-31 and is cited as D-31 evidence. The guard is PB-20 governance, not D-31's resolution. Harmless, but it inflates D-31 coverage.
- NIT: NFR-SEC-03 lists ADR 0021 under `deviations`. ADR 0021 changes the milestone, not the requirement. Consider `notes` only, or a convention note in the traceability README.
- NIT: threat model §3.1 (anchor signing, "planned (audit service)") and §4 R-2 ("Audit service milestone") could name M7, as ADR 0021 does.

### Decision on closing M1

**M1 may close.** MAJOR-1 and MAJOR-2 are resolved, and everything Decisions (a) required was reproduced.

Tag `m1-complete` (annotated) only after all of the following:
1. This delta re-check is appended to `docs/reviews/M1/milestone-review.md`.
2. The remaining Decisions (b) items are in the closing commit:
   - the `CHANGELOG.md` M1 entry;
   - the Part J status block, with GOV-9 as an open owner action;
   - the PB-20 `make down -v` note for local volumes migrated before the merge.
3. `m1/close` reaches `main`.
4. On that `main` commit, CI is green at job level:
   - `ci` 7/7;
   - `stack`: the `core compose stack healthy + smoke test` job actually **executed**, not skipped by path filters;
   - `devcontainer`: `build-and-verify` actually **executed**.

   The author checks CI and quotes the job-level results in the status block (GOV-10 practice).

Housekeeping: the worktree `…/scratchpad/fs-delta-m1` was removed. `git worktree list` shows only the main checkout and the two existing `.claude/worktrees/agent-*` entries, which were not touched. The main checkout's working tree was not modified. 0 GitHub API requests.
