# M7 — parallel-branch updates (`m7/staff-auth`)

The M7 session writes these entries here instead of in `requirements.yaml`, `docs/backlog/`,
`lab_notebook.md` and `SESSION_STATE.md`, which this branch does not edit. Whoever merges M7 folds
them into those files. Newest entries go at the bottom of each section.

Branch: `m7/staff-auth`, cut from `origin/main` at `f8885d6` (`m3-complete`).

## Numbering reserved by this branch

ADR and Flyway numbers are global.

| Kind | Reserved for M7 | Used |
|---|---|---|
| ADR | `0070`–`0079` | `0070` (staff identity, authorisation enforcement and audit anchoring), `0071` (hybrid persistence), `0072` (employee IDs unique per institution), `0073` (assembling the API application) |
| Flyway | `V70`–`V79` | `V70__staff_identity_and_audit_anchoring.sql`, `V71__close_new_functions_to_public.sql`, `V72__audit_event_receipts.sql` |
| Backlog | `PB-102`–`PB-130` (claimed 2026-10-17, after reading every branch's updates files; the highest id in use anywhere was `PB-101`) | `PB-102`–`PB-120` |

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
| `backend/ingest/pom.xml`, `.../ingest/config/StaffIdentityWiring.java`, `.../ingest/config/AuditRelayWiring.java`, `backend/ingest/src/main/resources/application.yml` (M7's settings) | new dependency on `fraudshield-admin`; M6's two ports bound to M7; the audit writer's consumer | the owner assigned assembly and the audit consumer to M7 (2026-10-17, ADR 0073) |
| `.../ingest/config/DecisionWiring.java` (bean `decisionTenantTransactions`), `.../ingest/web/ApiKeyFilter.java` (keeps an existing correlation id), `.../FraudShieldApiApplication.java` (javadoc) | M6 files | a bean-name collision; one correlation id per request in the assembled application |
| `backend/ingest/src/test/.../api/ApiHarness.java`, `AssembledApplicationTest.java` (new) | M6's harness on M7's real key verification; the no-doubles boot test | ADR 0073 |
| `backend/persistence/.../V71__…`, `V72__…`, `DatabaseSecurityTest.java` | two migrations; two tests (every SECURITY DEFINER function pinned; V71's default) | M7 merge review, findings 3 and 4 |
| `docs/adr/0068-…` | amendment: how the decision path's audit records reach the chain | review finding 2: ADR 0068 and this file disagreed |
| `docs/parallel/M9_updates.md` | the licence criterion widened to every installed distribution, and xgboost's wheel | owner, 2026-10-17 |
| `docs/security/threat_model.md` | 3.6 rows for assembly, the audit relay and V71; the SSRF row; 3.5 before 3.6; R-10, R-11 owners; R-13 | review findings 8 and 12 |

### Schema additions (V70, V71, V72)

All additive. `DatabaseSecurityTest` and `SchemaPoliciesTest` pass with them applied (CI, 2026-10-17).

- **V70** (formerly V12):
  - `users.version`, the JPA `@Version` behind `StaffUserUpdate.version` (no trigger; ADR 0071);
  - `users_employee_id` index and `auth_employee_id_registered(text)`, for the registration
    availability check before any institution is known;
  - `auth_find_user_institution(uuid)`, the institution of an account named by a signed unlock or
    reset token;
  - `audit_chain_head(smallint)`, `audit_chain_hashes(smallint, bigint, bigint)` and
    `audit_chain_last_seq_before(smallint, timestamptz)`, positions and hashes across institutions,
    never content;
  - all five closed to PUBLIC, then granted (`auth_*` and the first two audit functions to
    `fs_app`; the audit functions to `fs_compliance_ro`).
- **V71** sets the global default privilege `FOR ROLE fs_migrator REVOKE EXECUTE ON FUNCTIONS FROM
  PUBLIC`. V11's per-schema form never had an effect (lab notebook, 2026-10-17).
- **V72** adds `audit_event_receipts` (append-only, tenant-isolated), the audit consumer's
  deduplication of Kafka events (ADR 0073).

**Developer databases.** A persistent database that ran V70 before `3c13c5c` has its five functions
executable by PUBLIC, and `flyway repair` fixes only the checksum. Rebuild it, or run V70's
`REVOKE ... FROM PUBLIC` statement by hand before `repair`.

## Traceability updates to apply at merge (rewritten 2026-10-17 under the deletion standard)

Every row below was held to the deletion standard (master prompt, PR checklist): would the named
test fail if the code it claims to prove were deleted? The first audit (2026-10-17, at `419b5cb`)
found **4 of the table's 19 rows proved in full and 15 in part**, and in M7's other claim tables
**46 of 58 claims proved, 9 in part, 3 not at all**. The owner's decision the same day: close the
four deletable guards, the vacuous seven-day assertion and the two unpinned defaults with tests;
an ADR for the employee-ID deviation; the UI clauses re-filed to M8, since a row with an unowned UI
clause is not DONE; everything else to the backlog, one row each, in its deletion wording, the row
staying IN_PROGRESS.

`requirements.yaml` files **25 rows under M7**, not 19: M6's carry (2026-10-17) added FR-01-05,
NFR-REL-06 and D-51, and NFR-SEC-03, TEST-04 and TEST-07 were already M7's. All 25 are below.

**Counts after this round.** Traceability: **8 of 25 proved in full, 17 in part, none with
nothing proved.** Of the original 19: 5 in full (was 4), 14 in part. Other claim tables: **52 of
58 proved, 5 in part, 1 not at all** (was 46, 9, 3). Two of the first audit's verdicts were wrong
rather than changed by this round: mutation K1 (NO) and review finding P1 (PART) both concern
deleting the reload after the row lock, and the review record shows that mutation, renamed R1,
killed by `UserAdministrationTest.selfEditRacingFailureLockDecidesOnTheLockedRow`
(`docs/reviews/M7/staff-auth.md`, "R1 replaces K1, the former survivor").

| Row | Proved | Status at merge | Evidence (each fails if the code it names is deleted) | What is not proved, and where it goes |
|---|---|---|---|---|
| FR-01-05 | in full | DONE | `AuthorisationMatrixTest` (every key scope against every operation: 403 on `/alerts`, `/admin`); `ApiKeyLifecycleTest.rotationKeepsTheOldKeyForTwentyFourHours`; `AssembledApplicationTest` (an admin-issued key submits in the real application); `IngestApiTest` on real keys | — |
| FR-07-01 | in part | IN_PROGRESS | `AuthorisationMatrixTest` against `ContractAuthorizationManager`; `UserAdministrationTest.adminCannotChangeOwnRoleOrStatus`, `adminDemotedInsideTheCacheWindowCannotAct`; `SessionTest.tamperedOrForeignTokensAreRefused` | PB-104: the matrix test's expected answers come from `ContractPolicy` itself; method security; the unreachable last-active-administrator guard |
| FR-07-02 | in part | IN_PROGRESS, **re-filed to M8** | `RegistrationTest` (every failed rule listed: `everyFailedPasswordRuleIsListedNotOnlyTheFirst`), `PasswordPolicyTest` | UI: confirm field, four-level meter, submit disabled (M8) |
| FR-07-03 | in part | IN_PROGRESS, **re-filed to M8** | `GoogleSignInTest` (401 for invalid tokens; linking by verified email; names and avatar on the new-user path) | UI: sign-in on the login and registration pages (M8); "< 3 s" measured against a local fake (D-51), a deviation to record when it closes |
| FR-07-04 | in part | IN_PROGRESS, **re-filed to M8** | `AccessTokensTest`; `SessionTest.accessTokenExpiresAfterFifteenMinutesAndTheFamilyAfterSevenDays` (no longer vacuous: the family ends at sign-in + 7 days however recently it rotated); `LoginTest.signInReturnsBearerTokenAndHardenedCookies` | UI: "httpOnly cookie confirmed in browser DevTools" (M8's Playwright) |
| FR-07-05 | in full | DONE | `AuthorisationMatrixTest`; `NetworkAndKeysTest.rawKeyIsShownOnceListShowsOnlyNameScopesAndLastFour` | — |
| FR-07-06 | in part | IN_PROGRESS | `LoginTest` (11th attempt 429 with Retry-After; 5th failure locks; auto-unlock at 30 min) | PB-105: email measured against `RecordingStaffMailer`; the 15-minute window never advanced |
| FR-07-07 | in full | DONE_WITH_DEVIATION (422 per ADR 0011, not 400) | `PasswordPolicyTest`; `PasswordHasherTest` (cost 12); `RegistrationTest.everyFailedPasswordRuleIsListedNotOnlyTheFirst` | — |
| FR-07-08 | in part | IN_PROGRESS | `PasswordResetTest` (single use; `codeIsValidForExactlyTenMinutes`; 422 per ADR 0011) | PB-106: delivery within 30 s measured against `RecordingStaffMailer`; UI "change form" (M8) |
| FR-07-09 | in full | DONE | `SessionTest.passwordChangeEndsEverySessionAndOldJwtReturns401` (old JWT and old refresh cookie both refused); `GoogleSignInTest` (revocation call recorded) | — |
| FR-06-01 | in part | IN_PROGRESS | `UserAdministrationTest.adminCreatesAccountThatSignsInWithTheEmailedTemporaryPassword` (UNIQUE email; UNIQUE (institution, employee ID) and the same ID accepted at another institution, ADR 0072) | PB-107: no Google set-up link in the welcome email; most fields unasserted; the database backstop behind the pre-check |
| FR-06-02 | in part | IN_PROGRESS | `UserAdministrationTest.deactivationEndsSessionsAndBlocksPasswordSignIn`, `roleChangeEndsTheOldTokenAndStaleVersionsConflict` (refresh revoked) | PB-108: the version bump proved only together with the revocation; Google refusal within 5 s of a deactivation |
| FR-06-06 | in part | IN_PROGRESS | `AuditSearchTest` (filters, the user filter now proved); `RelayedAuditAppenderTest`; `AssembledApplicationTest` (a hold is found by audit search); `DatabaseSecurityTest` | PB-109: most types written synthetically; "all write operations audited" untested |
| FR-06-07 | in part | IN_PROGRESS, **re-filed to M8** | `ApiKeyLifecycleTest`; `NetworkAndKeysTest` (401 within 5 s, raw key once); `AuthPropertiesTest` (24 h and 2 s defaults); `AssembledApplicationTest` (revoked key refused within 5 s in the real application) | UI: the raw key visible once (M8) |
| NFR-SEC-03 | in part | IN_PROGRESS | M1's token-only schema and contracts (`SchemaPoliciesTest`) | PB-114: the analyst-endpoint inspector test needs the analyst API, which no milestone builds (owner decision) |
| NFR-REL-06 | in part | IN_PROGRESS | `GoogleSignInTest.googleOutageIsServiceUnavailableWithRetryAfterAndPasswordSignInStillWorks` (token and JWKS endpoints both 503) | PB-112: OAuth-only users' recovery by email OTP untested; UI "Temporarily unavailable" (M8) |
| TEST-04 | in full | DONE | `AccessTokensTest`, `SessionTest`, `AuthorisationMatrixTest`, `GoogleSignInTest`, `PasswordHasherTest` | — |
| TEST-07 | in full | DONE_WITH_DEVIATION (D-23: a new Google user is PENDING_APPROVAL, not an active ANALYST) | `GoogleSignInTest` (JWKS verification, invalid token 401, linking, new user, outage with password sign-in working) | — |
| D-19 | in part | IN_PROGRESS, **re-filed to M10** | `ApiKeyFormatTest`; `ApiKeyLifecycleTest.theStoredSecretIsTheHmacOfTheSecretUnderTheCurrentPepper`, `revocationAnnouncementAloneEvictsAnotherInstancesCachedKey` (pub/sub, with a ten-minute cache), `revokedKeyIsRefusedWithinTheAnalyticBoundHoweverSlowTheRead` | PB-115: the 10,000 TPS rationale, measured on M10's machine (as D-13 and D-16 were carried) |
| D-23 | in full | DONE | `GoogleSignInTest.newGoogleUserOnAnAllowedDomainCreatesAnAnalystAccountPendingApproval`, `googleUserOutsideTheAllowlistIsRefusedAndNothingIsCreated` | — |
| D-24 | in full | DONE | `RegistrationTest.registrationCreatesPendingAccountWithRequestedRoleButNoGrantedRole`, `UserAdministrationTest.approvalGrantsTheRoleOnlyAfterEmailVerificationAndRejectionDeactivates` | — |
| D-26 | in part | IN_PROGRESS | `LoginTest` (office ceiling; per-(IP, email) limit, now isolated from the lock; auto-unlock) | PB-110: an unlock by password reset or change writes no unlock event |
| D-27 | in part | IN_PROGRESS, **re-filed to M8** | `SessionTest` (version check, reuse detection, CSRF, refresh revoked on password change); `SessionInvalidationTimingTest.announcementAloneEndsAnotherInstancesCachedSession` (pub/sub, with ten-minute caches) | UI: access tokens held in memory only in the browser (M8) |
| D-32 | in part | IN_PROGRESS | `AuditAnchoringTest`, `AuditVerifyCommandTest`, `MerkleRootTest`, `AnchorSignerTest`, `RelayedAuditAppenderTest`, `AssembledApplicationTest`, `DatabaseSecurityTest` | PB-111: the daily `@Scheduled` trigger untested; anchoring off by default (PB-11) |
| D-51 | in part | IN_PROGRESS | M7's share: `FakeGoogle` behind `GoogleSignInTest` (no real credentials) | PB-113: no Google fake in the local stack; no sandbox contract suite reported as skipped |

**Re-filed rows.** The seed records each carry (`tools/src/fraudshield_tools/traceability_seed.py`):
FR-07-02, FR-07-03, FR-07-04, FR-06-07 and D-27 to M8, whose remaining clauses are UI; D-19 to M10,
whose remaining clause is a throughput measurement. Statuses, evidence and notes travel with them.

**Other claim tables** (ADR 0070, ADR 0071, `docs/walkthrough/M7.md`, the review record's findings
and mutation tables, the evidence table):

- **Proved now that were not:** the walkthrough's "a sign-in lasts at most 7 days" (was NO); the
  evidence table's two "pub/sub" claims and the walkthrough's pub/sub claim (were PART).
- **Proved by a test the claim did not cite:** review P1 and mutation K1, as above; P1 now cites
  `selfEditRacingFailureLockDecidesOnTheLockedRow`.
- **Still in part (5):** ADR 0070 §1, method security (PB-104); ADR 0071's `validate` default
  (PB-117) and key-list N + 1 (PB-118); walkthrough #4, inactive accounts' 423 and the unknown-email
  bcrypt burn (PB-119); walkthrough #5, the measured revocation time, now stated as measured (103 ms) but asserted by no test (PB-120).
- **Still not proved (1):** review #4, `concurrentSignInsAllSucceed` asserts only 200s, so moving
  bcrypt back inside the transaction would not fail it (PB-116).

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

- **Decisions for the owner (2026-10-17).**
  - **D-15 against FR-06-07's cache** (ADR 0073, threat model R-13): in the assembled application a
    PostgreSQL outage longer than the key cache's 2 seconds refuses ingestion at authentication.
    M6's `ResilienceApiTest.withPostgresDownDecisionsContinueAndArePersistedAfterRecovery`
    (NFR-REL-02, D-15) fails on the assembled application for this reason, so `ci` cannot be green
    until this is decided. Keep refusing (and change M6's test and NFR-REL-02's evidence to say so),
    or serve a key's last verified record, bounded, while the database cannot answer. Either way
    the refusal is now 503 with `Retry-After`, not the 401 it first was.
  - **Nobody builds the analyst API** (`listAlerts`, `decideAlert`, escalations, overrides, the
    dashboard, rules, thresholds, SAR, models): the contract defines it, M8 builds its console
    against mocks, and M6's `analystDecidesHold` and `countConfirmedFraud` wait for it. NFR-SEC-03's
    inspector test (PB-114) cannot be written until it exists.
  - **`DemoUserEntity`** stays, as a decision rather than an oversight. ADR 0068 point 4 says to
    delete it in favour of `StaffUserEntity`, but that would make the database tool
    (`backend/persistence`) depend on `fraudshield-auth` and so load its security and secrets. The two
    entities are never in one persistence unit: `AssembledApplicationTest` asserts the API maps
    `users` with `StaffUserEntity` alone. The cost is two mappings to keep in step, which each
    application's `validate` start-up checks. Reverse it if you prefer the dependency.
- **Licences (decided 2026-10-17).** scipy and xgboost approved and recorded; NCCL recorded from its
  shipped text. M9's image criterion covers every installed distribution (`M9_updates.md`, From M7).
- **Application assembly: done** (ADR 0073). The API application depends on `fraudshield-admin`
  and sets the properties in `backend/auth/README.md`; M9 must supply them from the environment
  (`FRAUDSHIELD_TOKEN_KEY_HEX`, `FRAUDSHIELD_SECRET_BOX_KEY_ID`/`_HEX`, `FRAUDSHIELD_JWT_KEY_ID`,
  `FRAUDSHIELD_JWT_PRIVATE_KEY_FILE`, `FRAUDSHIELD_API_KEY_PEPPER_1`,
  `FRAUDSHIELD_API_KEY_ENVIRONMENT`, `FRAUDSHIELD_CONSOLE_BASE_URL`, `FRAUDSHIELD_MAIL_FROM`,
  `FRAUDSHIELD_SMTP_HOST`/`_PORT`).
- **Decision-path audit: done**, through the audit writer's consumer rather than in the decision's
  transaction (ADR 0068 amendment, ADR 0073). Rule, threshold, model and report events still need
  their emitters (PB-109).
- **Webhook dispatcher.** M6's dispatcher re-checks every resolved address at delivery against its
  own deny-list (ADR 0066), which is the check this item asked for; the URL comes from
  `ApiKeyService.webhookEndpoint`.
- **Staff email translations (D-43).** `fraudshield/auth/mail.properties` is English only;
  Kinyarwanda, French and Kiswahili fall back to English until reviewed catalogues exist.
- **Backlog.** PB-102 to PB-120 (this round). PB-11 (dedicated anchoring role) remains open; a
  forged anchor is detected but can block that day's anchor. PB-1 … PB-4 and PB-7 are
  risk-configuration and compliance-grant items that are not part of this branch's scope.

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

No merge to `main` and no `m7-complete` tag until the owner says so. Since 2026-10-17 M7's HTTP
surface runs in the real API application (`AssembledApplicationTest`), not only in
`AuthTestApplication`.

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

## Merge state — resume from here (rewritten 2026-10-17 after merging M6)

**Status:** M7 is not merged and not tagged. M6 is on `main` (`b77008e`, tagged `m6-complete`), and
`main` is merged into this branch (`8c8cf48`). The merge round's independent review
(`docs/reviews/M7/m7-merge-2026-10-17.md`) was CHANGES_REQUIRED, with two MAJORs; the owner assigned
both to M7, and this round closes them (ADR 0073).

| Merge | `main` at | What it brought | Conflicts |
|---|---|---|---|
| `0fe8ca7` (2026-09-22) | `72e7790` | M4 close-out | `lab_notebook.md` only |
| `84a61eb` (2026-09-24) | `0547539` | M5 scoring | `lab_notebook.md` only |
| `8c8cf48` (2026-10-17) | `b77008e` | M6 decision path | six: `backend/pom.xml`, `backend/README.md`, `licences.py`, `threat_model.md`, `lab_notebook.md`, `requirements_matrix.md` (re-rendered, not merged) |

The resolution rules were unchanged: the notebook is append-only on both sides; `uv.lock` and the
matrix are regenerated; `licences.py` keeps both sides' entries. `fs-migration-guard` reported 19
merged migrations and 0 changed.

**What M6 brought that touched M7.** V70 was found executable by PUBLIC by M6's
`DatabaseSecurityTest` and fixed (`3c13c5c`; the cause corrected in this round). The API application
could not start: it needed M7's two adapters, three bean-name collisions resolved, and the audit
consumer, all added this round.

**Build with `clean`:** a tree from before the V12 → V70 rename still holds a stale `V12` in
`persistence/target/classes`, and the persistence tests then fail with "column version already
exists".

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

**At merge (checklist), as it stands on 2026-10-17:**
1. Done: M6 is on `main` and merged into this branch (`8c8cf48`).
2. Done: `fs-migration-guard --against origin/main` reports 0 changed; V70, V71 and V72 sort above
   every merged version.
3. M6's persistence follows the rule of this file by inspection (`DecisionWiring` takes Boot's single
   `PlatformTransactionManager`), and **`AssembledApplicationTest` now proves it**: it fails if the
   assembled application has more than one transaction manager or one that is not JPA's. The earlier
   claim that `HybridPersistenceTest` and `QueryCountTest` detect a non-JPA manager in M6 was false
   under the deletion standard: both build M7's own stack and never load M6's wiring.
4. CI at job level on the head to be merged: `ci`, `stack` and `devcontainer`, each job executed,
   not skipped.
5. Fold this file into `requirements.yaml` (the table above, with the seed's carries),
   `docs/backlog/` (PB-102 to PB-120), `lab_notebook.md` and `SESSION_STATE.md`; done in this
   round.
6. Re-measure the M7 timing gates on CI. The bounds are analytic and asserted in fake time
   (ADR 0071 §6), so CI's figures are confirmation, not the basis of the gate.
7. Merge and tag only on the owner's instruction, after CI is green on the exact merge commit.

**Open items that do not block the merge:**
- The contract-gap proposals (above), which need a contract change.
- The residual risks in ADR 0070 and threat model R-5 to R-8.
- The owner decisions listed under "Items" (R-13, the analyst API, `DemoUserEntity`).
- The backlog rows PB-102 to PB-120, under the rows that stay IN_PROGRESS.
- σ, the inter-instance clock skew in the session bound, assumes NTP-synchronised hosts. That
  belongs in the deployment runbook (M9).
