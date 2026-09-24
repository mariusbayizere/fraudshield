# M7 — parallel-branch updates (`m7/staff-auth`)

The M7 session writes these entries here instead of in `requirements.yaml`, `docs/backlog/`,
`lab_notebook.md` and `SESSION_STATE.md`, which this branch does not edit. Whoever merges M7 folds
them into those files. Newest entries go at the bottom of each section.

Branch: `m7/staff-auth`, cut from `origin/main` at `f8885d6` (`m3-complete`).

## Numbering reserved by this branch

ADR and Flyway numbers are global.

| Kind | Reserved for M7 | Used |
|---|---|---|
| ADR | `0070`–`0079` | `0070` (staff identity, authorisation enforcement and audit anchoring), `0071` (hybrid persistence) |
| Flyway | `V70`–`V79` | `V70__staff_identity_and_audit_anchoring.sql` |

**Owner decision (2026-09-22), superseding the earlier "M7 owns V12":** migrations are reserved in
ranges, so that merge order can never put an unmerged migration below a merged one (PB-24).
`fs-migration-guard` refuses a new migration at or below the highest merged version, so a low
number held by a slow branch becomes unmergeable once a faster branch merges a higher one.

| Range | Owner |
|---|---|
| `V1`–`V11` | merged (M1) |
| `V60`–`V69` | M6 |
| `V70`–`V79` | M7 |
| any other | **claim a range in your milestone's `docs/parallel/Mx_updates.md` before creating a migration** |

M7's migration, numbered `V12` until 2026-09-22, is now `V70`. It had never been on `main`, and no
test depends on the number.

> **FLAG FOR M6:** M6 owns `V60`–`V69`; its `V60`–`V63` already comply, and nothing changes for
> it. The earlier note asking M6 to use "`V13` or above" is withdrawn in favour of the ranges.
> Future migrations must stay inside `V60`–`V69`; claim a new range first if that runs out.

## Persistence rule for M6 and later milestones (ADR 0071, owner decision 2026-09-22)

M7 adopted a hybrid persistence layer, and **M6 must follow the same rule**:

- **Spring Data JPA with Hibernate** for configuration tables: thresholds (`risk_threshold_versions`,
  `risk_thresholds`), rules (`alert_rules`, `alert_rule_versions`), circuit-breaker settings,
  `config_changes`, model and dataset records.
- **Explicit SQL (`JdbcTemplate`) or batched `COPY`** for the decision hot path, the hypertables
  (`transactions`, `fraud_scores`, `shadow_scores`, `audit_events`) and every append-only table.
  Any JPA entity mapped over an append-only table must be `@Immutable`. Audit records go through
  `AuditLog` only.
- The settings are inherited from the auth module: `ddl-auto=validate` (Flyway owns the schema) and
  `open-in-view=false`, enforced at startup by `PersistenceSettingsGuard`.
- Register entity packages with `@AutoConfigurationPackage`; `@EnableJpaRepositories` would switch
  off the other modules' repository scanning.
- Run every query in `TenantTransactions`, so that row-level security applies to Hibernate's
  statements as well.
- Guard listing endpoints against N + 1 with a Hibernate-statistics test, as `QueryCountTest` does.

### The owner's full statement of the rule (2026-09-22, for M6)

The owner restated this directly for M6, as an ADR on M6's branch in M6's ADR range. Recorded here
because it reached the M7 session; M6 owns the work and the ADR. Read
`origin/m7/staff-auth:docs/adr/0071-hybrid-persistence-jpa-and-explicit-sql.md` first and follow
the same pattern, so both backends match.

1. **JPA with Hibernate for M6's CRUD and configuration data:** thresholds and their versions,
   circuit-breaker settings, rules, alert queue entries, batch jobs, institutions, and anything an
   administrator edits.
2. **Explicit SQL only where it is required**, with each case justified in the ADR:
   - the audit hash chain and every append-only table (change tracking can emit UPDATEs and
     reorder writes; chain correctness depends on write order);
   - the synchronous decision hot path (per-request latency);
   - TimescaleDB hypertables, continuous aggregates and batched `COPY`;
   - the PII vault;
   - PostgreSQL features with no clean JPA expression: `set_config` for tenant isolation, ARRAY
     columns, `ON CONFLICT`.

   Any JPA entity over an append-only table is `@Immutable`.
3. **Hibernate configuration, each with a test:** `ddl-auto=validate` (Flyway owns the schema),
   `open-in-view=false`, no N + 1 queries (fetch joins or `@EntityGraph`, with a
   Hibernate-statistics test), and row-level security still enforced by setting the institution per
   transaction on the same connection Hibernate uses.
4. **`DemoDataSeeder`:** convert its user, institution and risk-configuration inserts to JPA
   repositories; keep its audit insert as explicit SQL, with the reason in the ADR.
5. **All existing tests stay green**, and the mutation checks are re-run on anything converted.

`DemoDataSeeder` is in `backend/persistence`, which neither M6 nor M7 owns outright. M7 changed
only that module's migrations. Whoever converts the seeder should say so in their updates file, so
the other milestone sees it at merge.

What M7 learned doing this, which M6 can reuse (all in ADR 0071):

- lock a row with `refresh(entity, PESSIMISTIC_WRITE)`, never a locking query: a locking query
  returns a copy the transaction already holds, or rejects the row outright when its version moved
  (§4);
- bulk JPQL updates bypass `@Version`, so advance it explicitly where a trigger used to (§3);
- flush after every JPA write that explicit SQL later reads in the same transaction (§4);
- `JpaTransactionManager` shares its connection with `JdbcTemplate`, which is what keeps
  `set_config` applying to Hibernate's statements (§3);
- anchor any cache TTL before its database read, so the bound does not include the read (§6).

## Files outside the M7 modules that this branch touches

M7 owns `backend/{audit,auth,admin}`. These edits outside those modules could not be avoided:

| File | Change | Why |
|---|---|---|
| `backend/pom.xml` | adds `audit`, `auth`, `admin` to `<modules>` | a module must be listed by its parent; merges trivially with M6's four modules |
| `backend/persistence/src/main/resources/db/migration/V70__staff_identity_and_audit_anchoring.sql` (was `V12`) | new, additive migration | see "Schema additions" below; migrations live only in the persistence module |
| `backend/README.md` | module table rows for the three M7 modules | describes the new modules |
| `docs/traceability/requirements_matrix.md` | regenerated by `fs-traceability render` | generated from test tags; the governance hook fails when it is stale. `requirements.yaml` is not edited |
| `docs/security/threat_model.md` | new section 3.6, R-2 updated, R-5 … R-8 added | milestone threat-model delta (build prompt I.3) |
| `docs/adr/0070-…`, `docs/walkthrough/M7.md`, `docs/reviews/M7/…`, `docs/benchmarks/2026-09-22-M7-…` | new | milestone documentation and evidence |
| `tools/src/fraudshield_tools/licences.py` | version-pinned `EXCEPTIONS` for Jakarta Mail and Angus Mail (owner-approved), and for `jakarta.persistence-api` 3.2.0 and `jakarta.transaction-api` 2.0.1, which the owner-requested JPA adoption brings in | CI's licence job fails without them; the same ADR 0020 dual-licence case, each verified from the jar's `NOTICE.md` |
| `docs/research/lab_notebook.md` | one appended entry, "2026-09-22 · A fix whose test never reached it", under a new M7 heading | written at the owner's explicit instruction (2026-09-22), overriding this branch's "do not edit" rule for that entry only. Append-only: on merge, keep both sides' appended entries |
| `docs/parallel/M7_updates.md` | this file | |

### Schema additions (V70, formerly V12)

All additions are additive. `DatabaseSecurityTest` and `SchemaPoliciesTest` still pass with V70
applied.

- `users.version` and trigger `users_version`. This is the optimistic lock behind
  `StaffUserUpdate.version`. It moves only when an administrator-editable field changes.
- `users_employee_id` index and `auth_employee_id_registered(text)`. The registration availability
  check runs before any institution is known.
- `auth_find_user_institution(uuid)` finds the institution of an account named by a signed unlock
  or reset token.
- `audit_chain_head(smallint)` and `audit_chain_hashes(smallint, bigint, bigint)` let the anchoring
  job (`fs_app`) and `fraudshield audit verify` (`fs_compliance_ro`) read positions and hashes
  across institutions, never content.

## Traceability updates to apply at merge

Proposed statuses once the M7 review is approved. The evidence is the named tests, run on this
branch (commit and run record below).

| Row | Proposed status | Evidence |
|---|---|---|
| FR-07-01 | DONE | `AuthorisationMatrixTest` (756 calls: every operation × anonymous, 4 roles, 4 API-key scope sets); `UserAdministrationTest.adminCannotChangeOwnRoleOrStatus`, `concurrentMutualDemotionLeavesOneActiveAdmin`, `adminDemotedInsideTheCacheWindowCannotAct`; `SessionTest.tamperedOrForeignTokensAreRefused` (role claim cannot be altered) |
| FR-07-02 | DONE (server side; the form, strength meter and confirm field are M8) | `RegistrationTest`, `PasswordPolicyTest` (shared vectors) |
| FR-07-03 | DONE_WITH_DEVIATION ("< 3 s" measured against a local fake of Google; D-51) | `GoogleSignInTest` (401 for invalid token, email-verified, audience and foreign-key checks; linking by verified email; names and avatar stored) |
| FR-07-04 | DONE | `AccessTokensTest`, `SessionTest.accessTokenExpiresAfterFifteenMinutesAndTheFamilyAfterSevenDays`, `LoginTest.signInReturnsBearerTokenAndHardenedCookies` (httpOnly, SameSite=Strict, path, 7-day Max-Age) |
| FR-07-05 | DONE | `AuthorisationMatrixTest`, `NetworkAndKeysTest.rawKeyIsShownOnceListShowsOnlyNameScopesAndLastFour` (403 on `/alerts` and `/admin`) |
| FR-07-06 | DONE | `LoginTest.eleventhAttemptFromOneAddressIsRateLimitedWithRetryAfter`, `fifthFailureLocksTheAccountAndEmailsAnUnlockLinkThatWorksOnce` (unlock email awaited ≤ 30 s), `failedSignInLockLiftsByItselfAfterThirtyMinutes`, `officeAddressOnTheAllowlistGetsTheHigherCeiling` |
| FR-07-07 | DONE_WITH_DEVIATION (weak password is 422 `password_policy` per ADR 0011, not 400) | `PasswordPolicyTest`, `PasswordHasherTest` (cost 12, min of 5 runs > 100 ms), `RegistrationTest.weakPasswordIsRejectedWithTheSpecificReason` |
| FR-07-08 | DONE_WITH_DEVIATION (an expired or used code is 422 `invalid_format`, not 400, per ADR 0011) | `PasswordResetTest` |
| FR-07-09 | DONE | `SessionTest.passwordChangeEndsEverySessionAndOldJwtReturns401`, `PasswordResetTest`, `GoogleSignInTest.existingActiveAccountLinksByVerifiedEmailSignsInAndLogoutRevokesTheGoogleToken` (revocation call recorded by the fake) |
| FR-06-01 | DONE | `UserAdministrationTest.adminCreatesAccountThatSignsInWithTheEmailedTemporaryPassword` (UNIQUE email, UNIQUE (institution, employee ID), idempotent replay, welcome email) |
| FR-06-02 | DONE | `UserAdministrationTest.deactivationEndsSessionsAndBlocksPasswordSignIn`, `GoogleSignInTest.deactivatedAccountIsBlockedFromGoogleSignIn`, `SessionInvalidationTimingTest` |
| FR-06-06 | DONE for M7 events; see note | `AuditSearchTest` (12 types written and searched by type, user, date range, entity), `JdbcAuditLogTest`, `AuditAnchoringTest`, `DatabaseSecurityTest` (no UPDATE/DELETE) |
| FR-06-07 | DONE | `ApiKeyLifecycleTest` (revocation across instances, 24 h overlap), `NetworkAndKeysTest` (401 within 5 s over HTTP, raw key once, `no-store`) |
| D-19 | DONE | `ApiKeyLifecycleTest`, `ApiKeyFormatTest` |
| D-23 | DONE | `GoogleSignInTest.newGoogleUserOnAnAllowedDomainCreatesAnAnalystAccountPendingApproval`, `googleUserOutsideTheAllowlistIsRefusedAndNothingIsCreated` |
| D-24 | DONE | `RegistrationTest.registrationCreatesPendingAccountWithRequestedRoleButNoGrantedRole`, `UserAdministrationTest.approvalGrantsTheRoleOnlyAfterEmailVerificationAndRejectionDeactivates` |
| D-26 | DONE | `LoginTest` (office ceiling, per-(IP, email), auto-unlock), audit events for lock and unlock |
| D-27 | DONE | `SessionTest`, `SessionInvalidationTimingTest`, `ContractPolicyTest.csrfIsRequiredExactlyWhereTheContractDeclaresIt` |
| D-32 | DONE (anchoring role: PB-11 open) | `AuditAnchoringTest`, `AuditVerifyCommandTest`, `MerkleRootTest`, `AnchorSignerTest` |

**Note on FR-06-06 ("12 action types logged").** The writer, the schema and the search cover all 12
types (`AuditSearchTest`). M7 itself emits AUTH, USER_ADMIN and API_KEY_LIFECYCLE. The decision,
rule, threshold, model and report events are emitted by the modules of M6 and M8, which should
call `AuditLog.record` inside the transaction of each change.

## Contract gaps found (contract is frozen; proposals for the owner)

1. `StaffUser` has no `version`, yet `StaffUserUpdate` requires one. M7 returns it as an `ETag`
   header (ADR 0070 §11). Proposal: add `version` to `StaffUser`.
2. `StaffUserUpdate` cannot change `employee_id`, so a Google-created account keeps its placeholder
   (`G` + 19 hex characters). Proposal: allow `employee_id` in the update, with the uniqueness 409.
3. There is no way to tell the console that a temporary password must be changed (threat model
   R-5). Proposal: `password_change_required` on `TokenResponse.user`.

**Owner decision (2026-09-22):** contracts stay frozen; the `ETag` version is acceptable for now; the
proposals are decided when the contracts are next opened.
4. FR-07-07 and FR-07-08 say 400 where ADR 0011 prescribes 422. They are recorded as deviations
   above. If the owner prefers the SRS wording, a 400-class code is needed.

## Items for the owner or other milestones

- **Licence tool (done on this branch, owner-approved 2026-09-22).** `make licences` reported
  `jakarta.mail:jakarta.mail-api@2.1.5` and `org.eclipse.angus:angus-mail@2.0.5` (runtime, pulled in
  by `spring-boot-starter-mail`) as unidentified. Each jar's `META-INF/NOTICE.md` declares
  `EPL-2.0 OR GPL-2.0-only with Classpath-exception-2.0`, the case ADR 0020 settled for
  `jakarta.annotation-api`. Two version-pinned `EXCEPTIONS` entries were added to
  `tools/src/fraudshield_tools/licences.py` (commit named in the evidence record below). The same run
  still reports three **pre-existing** Python dev dependencies, left alone for integration:
  `scipy@1.18.1` ('BSD License'), `xgboost@3.0.2` ('Apache Software License') and
  `nvidia-nccl-cu12@2.31.2` ('LicenseRef-NVIDIA-Proprietary').
- **Application assembly (M6).** The three modules are auto-configured libraries; the API
  application must depend on `fraudshield-admin` and set the properties listed in
  `backend/auth/README.md`, including Redis timeouts of a few hundred milliseconds.
- **Decision and rule modules (M6).** These must write their audit events through `AuditLog`
  inside their own transactions, and use `TenantTransactions` for every tenant-scoped query.
- **Webhook dispatcher (M6).** It must re-check `WebhookUrlValidator` at every delivery. The
  signing secret is available through `ApiKeyService.webhookSecret`.
- **Staff email translations (D-43).** `fraudshield/auth/mail.properties` is English only;
  Kinyarwanda, French and Kiswahili fall back to English until reviewed catalogues exist.
- **Backlog.** PB-11 (dedicated anchoring role) remains open; a forged anchor is detected but can
  block that day's anchor. PB-1 … PB-4 and PB-7 are risk-configuration and compliance-grant items
  that are not part of this branch's scope.

## Evidence record

| What | Where |
|---|---|
| Principal review, fixes, re-review (verdict APPROVED_WITH_MINORS) and 12 mutation spot checks | `docs/reviews/M7/staff-auth.md` |
| Full verify, M7 gate measurements, machine and load | `docs/benchmarks/m7_evidence_fe757fa.md` and the files `m7_*_fe757fa.*` |

Final branch state: `2dc718c`, pushed.

- **Last full verify per module:** common, persistence and audit at `fe757fa`; auth at `e0b9391`;
  admin at `2dc718c`. No module changed after its last passing run.
- **Commits touching `requirements_matrix.md`** (regenerated, never hand-edited): `c280402`,
  `7be13e7`, `fe757fa`, `e0b9391`.
- **Commit touching `tools/`:** `4a91ebc` (the licence exceptions).

## Not done by this branch (by instruction)

No merge to `main`, no `m7-complete` tag, no milestone walkthrough status block in
`SESSION_STATE.md`. M7's HTTP surface runs only in the test application until M6 assembles the API
application.

## Hybrid persistence (ADR 0071), 2026-09-22

- Commits `11cadf6` (migration), `24016f9` (review fixes) and `f1e9d48` (ADR).
- Evidence: `docs/benchmarks/m7_evidence_f1e9d48.md`. Review: addendum in
  `docs/reviews/M7/staff-auth.md` (2 MAJOR, both fixed; 12 of 13 mutations killed, and the
  survivor is covered by a second defence).
- **V12 changed in place:** the `users_bump_version` trigger is removed and JPA `@Version` owns
  `users.version`. V12 exists only on this branch. A developer database that already ran the old
  V12 needs a rebuild before integration (now V70; see the owner decisions below).
- **Licence exceptions** extend the owner's earlier approval for Jakarta Mail/Angus Mail to the
  same ADR 0020 case: `jakarta.persistence-api` 3.2.0 (EPL-2.0 OR BSD-3-Clause) and
  `jakarta.transaction-api` 2.0.1 (EPL-2.0 OR GPL-2.0 with Classpath exception), version-pinned
  in `tools/src/fraudshield_tools/licences.py`. **Please confirm at integration.**
- The rule for M6 is under "Persistence rule for M6 and later milestones" above.

### Owner decisions on the hybrid persistence (2026-09-22)

- **Licence exceptions approved.** Each entry states the licence the project elects, and that
  licence is the expression `fs-licences` checks:
  - `jakarta.persistence-api` 3.2.0: **BSD-3-Clause**, the permissive option of
    `EPL-2.0 OR BSD-3-Clause`;
  - `jakarta.transaction-api` 2.0.1: **EPL-2.0**, used unmodified as a dependency, of
    `EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0`.

  `fs-licences` reports only the three pre-existing Python flags (`scipy`, `nvidia-nccl-cu12`,
  `xgboost`, all dev scope), which are left for integration.
- **V12 was edited in place** (the `users_bump_version` trigger was removed), and was later
  renumbered to **V70**. Both are acceptable because it has never been on `main`.
  - Any developer database that ran the old V12 **must be rebuilt**. Since the renumbering,
    `flyway repair` is no longer enough: it would mark V12 as deleted, and V70 would then try to
    create objects V12 already made (`ALTER TABLE users ADD COLUMN version` fails). Test databases
    are created fresh, so only persistent developer databases are affected.
  - From the moment M7 merges, V70 is frozen like every merged migration. `fs-migration-guard`
    covers it automatically: the guard protects every migration at the merge base with
    `origin/main` (CI job at `.github/workflows/ci.yml:121`, and `make governance`).
- **The surviving mutation was resolved.** A stale-read path existed. It is now tested, and
  finding it also fixed a defect (`8f6bdba`). See the review addendum.

## Merge state — resume from here (rewritten 2026-09-24 after merging M5)

**Status:** M7 is approved by the owner, including the hybrid persistence change, the analytic
staleness bounds and the V70 renumbering. It is **not merged and not tagged**, by instruction.

**Merge order is M5, then M6, then M7** (owner, 2026-09-22). State on 2026-09-24:

| Milestone | State | Blocks M7 because |
|---|---|---|
| M5 (`m5/scoring`) | **merged**, `main` = `0547539`, tagged `m5-complete` | — |
| M6 (`m6/decision`) | **merging now** | M7's admin API is the operator surface over M6's decisions; M7 merges after it |
| M7 (`m7/staff-auth`) | ready, prepared against `main` at `0547539` | — |

Before merging M7, confirm M6 has landed:
`git merge-base --is-ancestor origin/m6/decision origin/main`. It was still false on 2026-09-24.

**Prepared merges (this branch).** `main` is merged into `m7/staff-auth` as it advances, so the
branch stays mergeable while it waits. Nothing has gone to `main` and nothing is tagged.

| Merge | `main` at | What it brought | Conflicts |
|---|---|---|---|
| `0fe8ca7` (2026-09-22) | `72e7790` | M4 close-out | `lab_notebook.md` only |
| `84a61eb` (2026-09-24) | `0547539` | M5 scoring | `lab_notebook.md` only |

In both, the resolution rule was the same:

- `docs/research/lab_notebook.md` is append-only on both sides: keep `main`'s entries first and
  M7's section after them;
- `uv.lock` and `docs/traceability/requirements_matrix.md` are **regenerated, never hand-merged**
  (`uv lock --check`, then `fs-traceability render`, which reproduced the merged matrix exactly:
  258 rows, 1,083 tagged tests after M5);
- `docs/traceability/requirements.yaml` takes `main`'s version. M7 does not edit it;
- `tools/src/fraudshield_tools/licences.py` merged cleanly, keeping both sides' `EXCEPTIONS`.

**Neither merge changed `backend/` or `frontend/`.** M4 and M5 landed in `ml/`, `dataset/`,
`docs/`, `contracts/` and `tools/`. M5's contract change is the gRPC scoring proto
(`ScoreRequest.context` reserved, ADR 0033), not the OpenAPI that M7's `ContractPolicy` reads, so
the authorisation matrix is unaffected and still measures 756 calls.

**M6 will be the first merge that touches M7's half of the backend.** Expect:

- `backend/pom.xml` `<modules>`: M6 adds `decision`, `ingest`, `rules`, `notify`; M7 adds `audit`,
  `auth`, `admin`. Keep both sides.
- Flyway: M6 owns `V60`–`V69`, M7 owns `V70`–`V79`. Either order passes `fs-migration-guard`,
  because each range sorts above every merged migration. Nothing in M6's `V60`–`V63` referenced
  M7's objects; recheck with `git grep` if M6 has added more.
- Persistence: M6 was asked to adopt the same hybrid layer (see the rule above). If M6 defines its
  own transaction manager it must stay a `JpaTransactionManager`, or `TenantTransactions`'
  `set_config` and Hibernate stop sharing a connection. `HybridPersistenceTest` and
  `QueryCountTest` detect this.
- Both milestones write audit records. M6's must go through `AuditLog`, so the V8 chain trigger
  sees them in order.

**Last verified state:** `84a61eb`, `./mvnw clean verify` over every backend module. See the
evidence record named below. **Build with `clean`:** a tree from before the V12 → V70 rename still
holds a stale `V12` in `persistence/target/classes`, and the persistence tests then fail with
"column version already exists".

**Working in this repository:** `/home/marius/fraudshield` is shared with other sessions, which
switch branches in it. Use a git worktree, and run `uv sync --all-packages` inside it: plain
`uv sync` installs no `fs-*` scripts, and borrowing another checkout's `.venv` runs that branch's
tool code against your files (it produced a false `fs-traceability-seed` failure on 2026-09-22).
`fs-licences` also needs `frontend/node_modules`, or every npm package is reported "Unknown".

## Shared files changed to unblock a milestone (2026-09-24)

Two flaky failures in shared tooling were blocking M7's CI. The owner asked M7 to fix them rather
than route them, so M7 changed files it does not own. **For M9 (the workflow owner) and whoever
owns `tools/`:** review these, and move them if they belong elsewhere.

| File | Change | Why |
|---|---|---|
| `tools/bin/gitleaks-selftest` | every random value is now proved detectable before it is planted, and generated values carry a digit | the self-test failed about 4% of runs, measured at 2 of 25 locally |
| `tools/tests/test_gitleaks_selftest.py` | new: the generator's output is always detected, with a control that the probe can fail | otherwise the probe could report everything as detectable and check nothing |
| `infrastructure/docker/scripts/pull-images.sh` | new: pulls a profile's images with bounded retries and backoff | a Docker Hub CDN reset failed a whole devcontainer run |
| `Makefile` (`up`) | pulls through that script before `compose up` | separates a registry failure from a stack failure |

**The self-test flake, diagnosed.** It plants random secrets and requires gitleaks to report each
one. gitleaks' *own* rule allowlists silently skip two kinds of value, which the trace shows as
`skipping finding: rule allowlist`:

- **all-letter values.** A random 24-character alphanumeric body is all letters with probability
  (52/62)^24 ≈ 1.5%. Measured: 0 of 100 all-letter values detected, 98 of 100 with a digit.
- **values containing a stopword substring**, such as `http`, `text` or `rail`, at about 0.4% for a
  24-character body (6 of 1,500 measured).

Neither is avoidable by a construction rule alone, because the stopword list is embedded in the
gitleaks binary. So the generator now scans its own candidates in a neutral path with the
repository's config and regenerates any the scanner would skip, bounded at 8 attempts; exhausting
those attempts is reported as a finding, not retried away. The probe adds one gitleaks run, about
3.5 s.

**The pull retry** covers the pull only: attempts default to 3 with 5 s doubling backoff
(`PULL_ATTEMPTS`, `PULL_BACKOFF_SECONDS`). Container creation, healthchecks and the smoke test are
**not** retried, because a stack that only comes up on the second attempt is a defect.

**At merge (checklist):**
1. Confirm M6 is on `main`
   (`git merge-base --is-ancestor origin/m6/decision origin/main`), then merge `origin/main` into
   `m7/staff-auth` again. The prepared merges carry M4 and M5 already, so only M6's changes
   remain. Expect conflicts in:
   - `docs/traceability/requirements_matrix.md`: regenerate it with
     `uv run fs-traceability render`;
   - `uv.lock`, if touched: regenerate it;
   - `tools/src/fraudshield_tools/licences.py` `EXCEPTIONS`: keep both sides' entries;
   - `docs/research/lab_notebook.md`: append-only, so keep both sides' entries (M7 added one, at
     the owner's instruction);
   - `backend/pom.xml` `<modules>`: keep both sides.
2. Run `uv run fs-migration-guard --against origin/main`. It must report 0 changed; V70 is above
   every merged version.
3. Check that M6 followed the persistence rule of this file: JPA for configuration tables;
   explicit SQL or batched `COPY` for the hot path and hypertables; entities registered with
   `@AutoConfigurationPackage`, not `@EnableJpaRepositories`.
   - If M6 defines its own transaction manager, it must remain a `JpaTransactionManager`.
     Otherwise `TenantTransactions`' `set_config` and Hibernate stop sharing a connection.
   - `HybridPersistenceTest` and `QueryCountTest` detect this.
4. Run `./mvnw clean verify` over every backend module on the merged tree, then `make governance`
   and `make licences`.
5. Fold the sections of this file into `requirements.yaml`, `docs/backlog/`, `lab_notebook.md` and
   `SESSION_STATE.md`. That includes:
   - the traceability table;
   - the contract-gap proposals;
   - the owner items;
   - the M6 persistence rule;
   - the **migration ranges**;
   - the residual risks of ADR 0070.
6. Re-measure the M7 timing gates on CI. The bounds are analytic and asserted in fake time
   (ADR 0071 §6), so CI's figures are confirmation, not the basis of the gate.
7. Tag only on the owner's instruction, after CI is green on the exact merge commit.

**Open items that do not block the merge:**
- The contract-gap proposals (above), which need a contract change.
- The residual risks in ADR 0070 and threat model R-5 to R-8.
- The three pre-existing Python licence flags.
- σ, the inter-instance clock skew in the session bound, assumes NTP-synchronised hosts. That
  belongs in the deployment runbook (M9).
