# Product backlog

Owner direction (2026-09-17): from the M1 database work on, reviews fix BLOCKER and MAJOR findings
(security, data integrity, RLS isolation, append-only guarantees, audit hash chain) in place. MINOR and
NIT findings, and backlog candidates raised by re-checks, are logged here instead of starting
fix-and-re-review rounds. Governance-tooling items stay in `governance.md`.

Format: ID · title · source · priority · due · problem · acceptance. Due dates are milestone IDs
from build prompt Part D.3 (M2 is the dataset generator; the scoring service is M5, ingestion and
the decision engine M6, staff identity, admin and audit M7).

---

### PB-1 · Test rejecting and confirming a folded tightening
- **Source:** contracts-events final re-check B-1 (mutation M11 survived) · **Priority:** medium · **Due:** M7 (risk configuration APIs)
- **Problem:** rejecting a folded tightening is not tested for restoring the true baseline, and
  confirming it is not tested for keeping both tightenings. The implementation is correct today.
- **Acceptance:** the fold test has a rejection branch asserting the defaults are restored and a
  confirmation branch asserting both tightenings stay; mutation M11 is caught.

### PB-2 · Document `previous` of a folded configuration change
- **Source:** contracts-events final re-check B-2 · **Priority:** low · **Due:** M7 (risk configuration APIs)
- **Problem:** for a folded change `previous` is the earlier baseline, not the settings at
  `base_version`; the javadoc of `ConfigChange.previous` and the OpenAPI property do not say so.
- **Acceptance:** javadoc and OpenAPI descriptions state it.

### PB-3 · Link superseded configuration changes to their successor
- **Source:** contracts-events final re-check B-3 · **Priority:** medium · **Due:** M7 (audit, with the configuration audit events)
- **Problem:** neither the `SUPERSEDED` audit event nor `ConfigChange` names the superseding change.
- **Acceptance:** `superseded_by` in the domain, the contract, `config_changes` and the audit event.

### PB-4 · State the consequences of folding in ADR 0014
- **Source:** contracts-events final re-check B-4 · **Priority:** low · **Due:** M7 (risk configuration APIs)
- **Problem:** ADR 0014 §3 does not say that chained small tightenings keep an unconfirmed tightening
  in force indefinitely, or that the first officer may confirm a change that folds their own.
- **Acceptance:** ADR 0014 states both; decide on an alert or a cap for chained folds.

### PB-5 · Complete `DOMAIN_OPERATIONS` in the authorisation matrix test
- **Source:** contracts-events final re-check B-5 (NIT) · **Priority:** low · **Due:** next change to the test
- **Problem:** refusal-to-operation coverage is partial (for example `ROLE_NOT_PERMITTED` omits
  `rejectConfigChange` and `proposeCircuitBreakerChange`).
- **Acceptance:** every operation that can raise a refusal is listed.

### PB-6 · Licence inventory for container images
- **Source:** ADR 0018 · **Priority:** medium · **Due:** M9 (deployment)
- **Problem:** the ADR 0009 inventory covers library dependencies only; the TimescaleDB image (TSL) is
  recorded only in ADR 0018.
- **Acceptance:** every Compose and deployment image is listed with its licence and policy status.

### PB-7 · Compliance access to `v_auto_block_status`
- **Source:** M1 database review (MINOR) · **Priority:** medium · **Due:** M7 (compliance and audit access)
- **Problem:** the view is granted to `fs_compliance_ro`, but querying it fails because the role has no
  SELECT on `customer_verifications` and `customer_notifications`.
- **Acceptance:** grant the needed columns or drop the grant; a test queries the view as the role.

### PB-8 · Verification expiry must use the database clock
- **Source:** M1 database review (MINOR) · **Priority:** medium · **Due:** M6 (verification page)
- **Problem:** the `customer_verification_responses` expiry trigger trusts the caller's `responded_at`.
- **Acceptance:** the trigger compares `expires_at` with `now()`; test with a backdated `responded_at`.

### PB-9 · Role passwords off process arguments in the Compose scripts
- **Source:** M1 database review (MINOR) · **Priority:** low · **Due:** M9
- **Problem:** `20-fraudshield-roles.sh` passes passwords as `psql --set` arguments, and
  `check-seeded-stack.sh` passes `PGPASSWORD` through `docker compose exec -e`; both are visible in
  process listings while they run (local development stack only).
- **Acceptance:** passwords reach psql through the environment or stdin only.

### PB-10 · Commit/undo exclusivity under REPEATABLE READ
- **Source:** M1 database review (MINOR) · **Priority:** medium · **Due:** M6 (decision engine)
- **Problem:** `forbid_commit_and_undo` is exclusive only under READ COMMITTED; under REPEATABLE READ
  the check after the advisory lock uses the old snapshot.
- **Acceptance:** document the required isolation level and test it, or model commit and undo as one
  outcome row keyed on `decision_id`.

### PB-11 · Only the anchoring job writes `audit_anchors`
- **Source:** M1 database review (MINOR) · **Priority:** medium · **Due:** audit service milestone
- **Problem:** `fs_app` can insert anchors for future dates and so block the signing job (a forged
  anchor would fail signature verification).
- **Acceptance:** a dedicated role for the anchoring job; `fs_app` has no INSERT on `audit_anchors`.

### PB-12 · Distinguish SQL NULL from JSON null in the audit row hash
- **Source:** M1 database review (NIT) · **Priority:** low · **Due:** before the first audit writer (M6 decision audit events)
- **Problem:** `audit_row_hash` hashes `before_value`/`after_value` SQL NULL and JSON `null` alike.
- **Acceptance:** the canonical form tags NULL; a test changes one into the other and verification
  reports the row.

### PB-13 · Column grant for `customer_verification_responses.ip_address`
- **Source:** M1 database review (NIT) · **Priority:** low · **Due:** M6
- **Problem:** `fs_app_readonly` can read customers' IP addresses (personal data).
- **Acceptance:** column grants without `ip_address`, covered by the credential-material test.

### PB-14 · Existence probing through global foreign keys to `users`
- **Source:** M1 database review (NIT) · **Priority:** low · **Due:** M5 (retraining)
- **Problem:** `training_datasets.uploaded_by` and `retraining_jobs.requested_by` reference `users (id)`
  globally, so a writer can learn whether a user id exists in another institution.
- **Acceptance:** reference through an operator table or validate the actor in the service; decision
  recorded in ADR 0017.

### PB-15 · Synthetic-data guard must survive lazy initialisation
- **Source:** M1 database re-check item 1 (MINOR, reproduced) · **Priority:** high · **Due:** M1 (owner direction: fixed before the merge)
- **Status:** fixed on `m1/database`: the guard is now a `spring.factories` listener with its own JDBC connection (`SyntheticDataGuard`).
- **Problem:** with `spring.main.lazy-initialization=true`, `SyntheticDataStatus` is never created, so a
  `prod` process starts against a seeded database.
- **Acceptance:** `LazyInitializationExcludeFilter` for the bean (or a `SmartInitializingSingleton`);
  `SyntheticDataStatusTest` covers lazy initialisation.

### PB-16 · Synthetic-data guard fails loudly without a `JdbcTemplate`
- **Source:** M1 database re-check item 2 (MINOR) · **Priority:** high · **Due:** M1 (owner direction: fixed before the merge)
- **Status:** fixed on `m1/database`: independent of beans; fails closed when it cannot check.
- **Problem:** `@ConditionalOnBean(JdbcTemplate.class)` silently skips the guard in an application
  with several data sources or a custom `JdbcOperations`.
- **Acceptance:** condition on `DataSource` and fail startup when the check cannot run.

### PB-17 · Synthetic-data check for non-Spring database clients
- **Source:** M1 database re-check item 3 (MINOR) · **Priority:** medium · **Due:** when a non-Spring service gets database access
- **Acceptance:** that service calls `deployment_has_synthetic_data()` at startup; ADR 0019 updated.

### PB-18 · Audit writers: retry on 40001, prefer READ COMMITTED
- **Source:** M1 database re-check items 4–5 · **Priority:** medium · **Due:** M6 (first audit writer: decision events)
- **Problem:** REPEATABLE READ writers get 40001 whenever the partition was written after their
  snapshot; a wall-clock step back stalls audit writes with 40001 until the clock catches up.
- **Acceptance:** the audit writer retries on 40001 in READ COMMITTED; ADR 0017 documents both.

### PB-19 · Test hygiene for `SyntheticDataStatusTest` and `TestDatabase`
- **Source:** M1 database re-check items 6–7 (NIT) · **Priority:** low · **Due:** next change to either
- **Acceptance:** the no-profile case asserts the message; `TestDatabase` drops its `fs_test_*`
  databases when the JVM exits.

### PB-20 · Migrations are immutable from the first merge
- **Source:** M1 database re-check item 8 (NIT) · **Priority:** medium · **Due:** M1 merge
- **Problem:** V1, V2, V8, V10 and V11 were edited in place before merge; a local stack volume
  migrated at bd222fe fails Flyway validation and needs `make down` with volumes removed.
- **Acceptance:** from the merge to `main` on, migrations only change through new versions, enforced
  by a governance check that fails when a merged `V*.sql` file changes; the walkthrough notes the reset.
- **Status:** fixed at the M1 close: `fs-migration-guard --against origin/main` (in `make governance`
  and the CI governance job) fails when a migration file present at the merge base is modified,
  renamed or deleted. Like the contract-baseline guard, it protects branches before they merge.

### PB-21 · One `writer_partition` range for the audit event contract and the table
- **Source:** M1 milestone review MINOR-4 · **Priority:** medium · **Due:** M6 (before the first audit writer)
- **Problem:** the Kafka `audit-event` schema allows `writer_partition` 0..1023, while the database
  CHECK and `audit_chain_heads` allow 0..63, so an event valid on Kafka can be rejected by the database.
- **Acceptance:** one range, chosen with the audit writer design; either a new migration widens the
  CHECKs (merged migrations are immutable) or a new schema version narrows the contract (ADR 0012); a
  test compares the two.

### PB-22 · Synthetic-data guard coverage edges
- **Source:** M1 milestone review NIT-1 · **Priority:** low · **Due:** M5 (first Spring service with database access)
- **Problem:** `requireCoverage` does not initialise `FactoryBean`s, so a `DataSource` declared with
  another return type can be missed; R2DBC connection factories are not covered; the guard runs only
  in applications that depend on `fraudshield-persistence`.
- **Acceptance:** ADR 0019 requires every Spring service with database access to depend on the
  module (or its guard), states that R2DBC is out of scope unless matched, and a test covers a
  `FactoryBean`-produced data source.

### PB-23 · Catalogue test: every token column carries `is_token`
- **Source:** M1 delta re-check (MINOR) · **Priority:** medium · **Due:** M6 (tokenisation at ingestion)
- **Problem:** `SchemaPoliciesTest` rejects raw values in the four `transactions` token columns only;
  the other `CHECK (is_token(...))` columns (V3 `fraud_scores`, V5 blocks, notifications, freezes) are
  not asserted.
- **Acceptance:** a catalogue test fails when any `*_token` text column lacks an `is_token` CHECK.

### PB-24 · Migration guard robustness
- **Source:** M1 delta re-check (NITs) · **Priority:** low · **Due:** M2
- **Problem:** blob ids are computed from working-tree bytes (false positives with eol filters); a
  new migration whose version sorts below the highest merged one is not flagged; the guard's tests
  are tagged D-31 although the guard is governance (PB-20).
- **Acceptance:** compare with `git hash-object --path`; reject out-of-order versions; retag the
  tests; D-31 evidence no longer cites them.
- **Status:** CLOSED in M2. The guard asks git for the blob id so a file that differs only by an
  end-of-line filter is no longer reported as modified, and a new migration at or below the highest
  merged version is rejected because Flyway would skip it. The tests are untagged (the guard is
  governance tooling, not evidence for a schema-completeness defect) and the D-31 row no longer
  cites them.

### PB-25 · Release-size dataset run (ML-DATA-01)
- **Source:** M2 gate item, owner direction 2026-09-18 · **Priority:** high · **Due:** M2 gate
- **Problem:** the 5,000,000-row run that ML-DATA-01 requires has not been produced. It must not run
  on the build laptop (the owner's direction; a million rows takes twenty minutes there and the
  machine is often busy), and the `dataset` workflow cannot be dispatched because GitHub registers
  `workflow_dispatch` only from the default branch, which is still `m0/bootstrap`.
- **Acceptance:** once `main` is the default branch, `.github/workflows/dataset.yml` is pushed to
  `main` as a single CI-only commit with an ADR recording the direct-to-main exception, the workflow
  is dispatched with the branch input set to the working branch, and the run's realism report,
  manifests and runner spec are attached to the M2 gate record. `fs-dataset check --full` must pass,
  including the size gate and the distribution gates.
- **Interim evidence:** the 1,005,621-row verification run, regenerated on the sourced parameters.

### PB-26 · Month partition key is the local month, not the UTC month
- **Source:** M2 principal review (MINOR 2.1) · **Priority:** medium · **Due:** M3 (before any release)
- **Problem:** `transactions/month=YYYY-MM` is the simulated *local* month while `transaction_timestamp`
  is UTC, so rows near a month boundary land one UTC month early: 11 rows of 40,213 in the reviewer's
  run, and the first partition holds timestamps before the dataset's nominal start. Partition pruning
  on `month=` is therefore unsound for anyone filtering by timestamp.
- **Acceptance:** either partition on the UTC month, or state the convention in the datasheet and the
  export README and add a test pinning it. A consumer must not have to discover it.

### PB-27 · Generator caches contradict the memory rationale
- **Source:** M2 principal review (MINOR 2.2) · **Priority:** low · **Due:** M3
- **Problem:** `FraudModel._plans` and `_month_plans` grow for the whole run (72 and 24 entries) while
  the neighbouring comment says "one month at a time keeps memory flat", which is true only of
  `LegitimateBehaviour._planned`. `_validate_scenario_capacity` also recomputes `planned_counts` once
  per month at construction, so it runs 25 times rather than 24.
- **Acceptance:** either bound the caches or correct the comments; drop the redundant recomputation.

### PB-28 · Dead `combine_chunks()` and the determinism test's reach
- **Source:** M2 principal review (addendum, area 2) · **Priority:** low · **Due:** M3
- **Problem:** the reviewer found no mutation of `pipeline.combine_chunks()` that
  `test_same_seed_is_byte_identical_across_chunk_sizes` catches, because `Table.take` already returns
  single-chunk columns. The determinism property is verified (including across a forced row-group
  boundary), but that one call is unguarded and may be dead.
- **Acceptance:** establish whether the call is needed at all — a mutation the test kills, or its
  removal with the row-group test as the guard.
