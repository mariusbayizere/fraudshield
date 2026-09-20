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
- **Source:** M2 principal review (MINOR 2.1) · **Priority:** high · **Due:** before M3 features begin
  (owner direction 2026-09-18: M3 reads these partitions and a wrong key would silently distort
  time-window features)
- **Problem:** `transactions/month=YYYY-MM` is the simulated *local* month while `transaction_timestamp`
  is UTC, so rows near a month boundary land one UTC month early: 11 rows of 40,213 in the reviewer's
  run, and the first partition holds timestamps before the dataset's nominal start. Partition pruning
  on `month=` is therefore unsound for anyone filtering by timestamp.
- **Acceptance:** either partition on the UTC month, or state the convention in the datasheet and the
  export README and add a test pinning it. A consumer must not have to discover it.
- **Closed 2026-09-19 (M3), second option.** Repartitioning on UTC would move rows into
  already-written partitions, so it needs either a month of buffering or a change to how activity is
  placed — and the latter alters every row, invalidating the report, the checksums and the tagged M2
  evidence. Disproportionate for 0.044% of rows whose drift is bounded and one-directional.
  Instead: measured precisely (447 of 1,006,249 rows drift backward, none forward, first partition
  reaching to 2023-12-31 21:15 UTC); documented in the datasheet with the consumer rule (to select
  UTC month M, read partitions M and M+1 and filter on the timestamp); carried in `release.json`
  under `partitioning` so a machine reading the release learns it without the prose; and pinned by
  `test_the_partition_key_is_the_simulation_month_and_its_drift_is_bounded`, which asserts no row is
  carried forward and none reaches back further than the +3 h maximum UTC offset.
  The test is on seed 13, not the shared fixture's seed 11: seed 11 produces no drifting row at
  6,000 rows, so the bound held vacuously — mutating the permitted drift to zero left the test
  passing, which is how the vacuity was found.

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

### PB-29 · Country packs: no country, currency or bloc hard-coded
- **Source:** owner direction 2026-09-18 (Africa-wide), ADR 0023 · **Priority:** high · **Due:** scoped
  branch after M2 closes, before M3 features
- **Problem:** country-specific values are spread across the generator, the Java side and the frontend
  (`COUNTRIES`, currency and timezone tables, country lists in the UI), so the system reads as
  EAC-shaped and a new country cannot be added without code changes.
- **Acceptance:** every country-specific value lives in `dataset/params/countries/<alpha-2>.yaml`
  (currency and ISO 4217 minor units, timezones per region, population and urban share, mobile money
  penetration, agent and merchant density, channel mix, languages, school terms and public holidays,
  KYC tiers, phone formats, bloc memberships); no country, currency or bloc named in Java, Python or
  TypeScript; a synthetic, entirely assumed "Country Z" pack in CI makes the generator, the features
  and the UI work with zero code changes; the M2 gates stay green with it added.

### PB-30 · Generalise corridor_class to blocs
- **Source:** owner direction 2026-09-18, ADR 0023 (refs D-03, Part E.2) · **Priority:** high · **Due:**
  with PB-29
- **Acceptance:** `corridor_class` becomes `DOMESTIC | INTRA_BLOC | CROSS_BLOC_AFRICA |
  INTERCONTINENTAL` with bloc membership (EAC, ECOWAS, SADC, COMESA, CEMAC, AMU) read from the packs,
  a country may hold several memberships, the feature count stays 44, and no EAC special case remains
  in code.
- **CLOSED 2026-09-19 (M3).** The packs carried `blocs` and `continent` from PB-29; this implemented
  the feature on both paths. The four classes are declared in the registry as `categories` — a new
  declarative field, because ADR 0025 allows a categorical no tolerance and so the two paths must
  not be free to name the classes for themselves — and both paths read them from there while each
  decides for itself which class a pair falls into. No country, currency, bloc or continent is named
  in either path, asserted by a source scan and, behaviourally, by an invented country in an invented
  bloc classifying correctly with no code change. Two findings came out of it: PB-42 (the class name
  says AFRICA while the rule tests continent equality) and PB-43 (two of the four classes are
  unreachable on the generated dataset, now declared as a `degeneracy`).

### PB-31 · Three assumed generalisation packs, kept out of every validated claim
- **Source:** owner direction 2026-09-18, ADR 0023 · **Priority:** medium · **Due:** with PB-29
- **Acceptance:** packs for West Africa (NG, GH or SN, covering XOF) and Southern Africa (ZA) marked
  entirely ASSUMED; the datasheet, claims register and paper state the validated-core/portability-pack
  split plainly; no result computed on them is reported as evidence about those countries.

### PB-32 · Front-end portability and right-to-left support
- **Source:** owner direction 2026-09-18, ADR 0023 · **Priority:** high · **Due:** with PB-29 (not
  retrofitted later)
- **Acceptance:** currency and number formatting driven by the packs' ISO 4217 minor units; dates and
  times per country timezone; pluggable i18n locale packs; RTL via CSS logical properties and
  direction-aware layout, with a Playwright test running the UI in RTL.

### PB-33 · Rename the dataset to FraudShield-Africa-Transactions
- **Source:** owner direction 2026-09-18, ADR 0023 · **Priority:** medium · **Due:** with PB-29
- **Acceptance:** the dataset is `FraudShield-Africa-Transactions` with a validated EAC-5 core; README,
  datasheet, claims register and paper wording carry no sentence implying validation beyond the sourced
  countries; the manifest's `dataset` field and the export follow.

### PB-34 · Leave-one-country-out and fine-tuning experiments (M4)
- **Source:** owner direction 2026-09-18, ADR 0023 (Part E.5) · **Priority:** high · **Due:** M4
- **Problem:** generalisation is currently asserted, not measured, and the traceability register cannot
  hold the rows because it is generated from the SRS and Part B only.
- **Acceptance:** `fs-traceability-seed` gains Part E.5 as a row source (with tests), and rows exist for
  (a) leave-one-country-out over the five EAC countries, reporting AUC, recall at 1% FPR and
  calibration drift against the in-distribution model with confidence intervals; (b) the same model run
  over the assumed non-EAC packs, reported as a portability probe on assumed data; (c) a fine-tuning
  variant that adapts on a small labelled sample from the held-out country and reports how much target
  data recovers performance.

### PB-35 · CLOSED — duplicate of D-03
- **Closed:** 2026-09-19, bookkeeping. FR-02-02's register row lists group counts summing to 46
  (temporal 7, account profile 5) where Part E.2 enumerates 6 and 4. This is **D-03**, already in the
  defect register and already carried on FR-02-02's own row, and the build prompt resolves it: E.2's
  catalogue names every feature and sums to 44, so E.2 is authoritative. The arithmetic was never in
  question and this entry should not have been opened as though it were. No work item remains; the
  wording fix belongs to D-03.

### PB-36 · The DB fallback is a third feature path, and it is untested
- **Source:** found writing the parity design's coverage cases, 2026-09-19 · **Priority:** high ·
  **Due:** M3, with the parity suite
- **Acceptance:** the parity suite exercises the `account_activity_hourly` fallback path alongside
  batch and online, under prefix replay and ADR 0025's tolerance; every windowed feature declares
  `fallback_behaviour`; the cold-cache case asserts `EXACT` features unchanged and
  `NAN_UNDER_FALLBACK` features NaN across a flush. M1's milestone review already recorded the
  continuous aggregates as implemented-but-untested (MAJOR-2); this is that defect reaching the
  feature layer, where it becomes a training/serving skew that appears only during an incident.
  Bucket-aligned substitution for a trailing window is not an acceptable resolution.

### PB-37 · `account_first_seen_at` has no table, and the online path collapses without it
- **Source:** checking M1's schema for a durable first-seen, 2026-09-19; re-ranked the same day
  after parity mutation 10 · **Priority:** **HIGH — correctness, not convenience** · **Due:**
  **before M6 wires the online path**, not before M4 training. The failure is a production-recovery
  failure, not a training one.
- **The schema gap:** M1 has **no per-account table at all** — accounts appear only as
  `account_token` columns on `transactions` and its aggregates — so there is nowhere a durable
  first-seen can live. `velocity_ratio_1h_vs_30d` is registered `history_basis=OBSERVED_CAPPED`,
  which divides by observed history and therefore requires one; the registry refuses that
  combination without `history_requirement=DURABLE`.
- **The mechanism, which is why this is high and not medium.** Parity mutation 10 showed two flush
  scenarios behaving oppositely:
  - *Arrivals and first-seen both lost.* Numerator and denominator shrink in step, the ratio barely
    moves, the damage is small. This is the case intuition reaches for, and it is not the problem.
  - *Arrivals restored, first-seen not.* The transactions come back from the database — which is
    exactly what happens, because they are in `transactions` — while the per-account first-seen does
    not, **because no table holds it**. Thirty days of rows are then divided by whatever span the
    cache happens to hold. The baseline inflates and **the ratio collapses on every established
    account simultaneously**, during a recovery, while the system is already degraded and nobody is
    positioned to notice a feature distribution shifting.
  It is training/serving skew that appears only in an incident, which is the class the parity suite
  exists to prevent and the one no warm-path test visits.
- **Interim mitigation, already shipped:** the online path **fails closed**. Without a durable
  first-seen the feature is NaN, covered by D-04's native missing handling, and `observe()` no
  longer infers first-seen from its earliest arrival — the inference that made `DURABLE`
  decorative. Asserted by `test_the_online_path_fails_closed_without_a_durable_first_seen` and its
  control `test_observing_transactions_never_invents_a_first_seen`. A missing feature during
  recovery is honest; a collapsed ratio across the account base is not.
- **Acceptance:** a migration adds durable per-account state carrying **both** the account opening
  date (`account_age_days`) and the first-seen-in-data timestamp — an account may be opened long
  before it transacts, so one does not substitute for the other. The online path reads it and stops
  returning NaN, and a test asserts the feature survives a cache flush with the durable store
  present. Related: `transactions` has a compression policy (30 days) but **no retention policy**,
  so first-seen is currently recoverable by full scan — which is a property of today's data volume,
  not a contract.

### PB-38 · `account_activity_hourly` refreshes 8 days, but a 30-day feature reads it
- **Source:** reading V10's refresh policy against the registry, 2026-09-19 · **Priority:** medium ·
  **Due:** M6
- **Acceptance:** either the refresh window covers the longest window any feature reads from the
  aggregate, or the ingest path's out-of-order tolerance is documented as shorter than 8 days and a
  test asserts it. `add_continuous_aggregate_policy(start_offset => interval '8 days')` means buckets
  older than 8 days are materialised once and never refreshed, so a transaction arriving more than 8
  days late is never reflected in a 30-day basis.

### PB-39 · The committed realism report describes a superseded parameter set
- **Source:** full dataset suite over `5855386`, 2026-09-19 · **Priority:** high · **Due:** M3, before
  any M3 result is cited
- **Observed:** `test_the_committed_report_describes_the_current_parameters` fails. The committed
  `dataset/realism_report.md` carries parameter digest `aa0ec909…`; the current parameters digest to
  `58f314e4…`.
- **Cause:** PB-29 (`dce89fe`) moved every country fact into packs, changing the parameter set. The
  report was last regenerated at `984351d`, before that refactor. The report's own footer still says
  "of 81" parameters, a count that predates the packs.
- **This is the guard working, not failing.** It is the guard M2 built for MAJOR 4.1, whose defect
  was a shipped report describing a superseded parameter set. It caught the recurrence on the first
  full run after the refactor, which is exactly what it was built to do.
- **Acceptance:** the report is regenerated and the test passes. Regeneration is a **1,006,249-row
  generation at seed 20260917** — a citable evidence run, so it owns the tree and the environment for
  its duration and its commit/tree hash is recorded beside it.

### PB-40 · The generator never shares a device between accounts, so two features are dead
- **Source:** E1's component-size measurement on the regenerated 1M dataset, 2026-09-19 ·
  **Priority:** high · **Due:** **before M4 training, deliberately NOT during M3** (owner decision
  2026-09-19: changing the generator re-draws the dataset, and a session has just been spent on the
  consequences of one re-draw. The features ship declared-degenerate now and are non-degenerate by
  the time the model using them is fitted.)
- **Observed:** 5,484 distinct device fingerprints across 5,920 accounts, and **zero** used by more
  than one account (max accounts per device: 1). Measured at tree `d85385f`,
  `docs/research/component_sizes.json`.
- **Consequence:** `accounts_per_device_7d` is identically 1 — zero variance, no signal. The
  "shared device and phone attributes across accounts" term of `synthetic_identity_score`, which
  Part E.2 names explicitly, is dead with it. ML-DATA-07 requires all 44 features computable; two
  are computable but meaningless, which the completeness check would not catch.
- **Acceptance:** the generator shares devices between accounts at a rate carrying provenance, so
  that `accounts_per_device_7d` has a distribution and the synthetic-identity ring scenario has the
  mechanism Part E.2 describes. A test asserts the feature is non-constant on a generated dataset
  (E13: a feature whose tests never see sharing proves nothing about the feature).
- **Done in M3 instead:** both features declare `degeneracy` in the registry, naming PB-40 and what
  would clear it, with `test_a_feature_with_no_signal_on_this_dataset_declares_it` asserting the
  declaration and `test_no_other_feature_silently_claims_to_be_fine` as its control. Clearing PB-40
  must also delete those declarations, which the second test will force.

### PB-41 · The report's digest covers parameters, so a changed draw is invisible to it
- **Source:** PB-39's root cause, 2026-09-19 · **Priority:** high · **Due:** M3, before the next
  release export
- **Observed:** PB-29 changed **no parameter value** — the pack FX rates are byte-identical to the
  table they replaced — yet the generated dataset changed completely, because `simulated()` sorts
  and country iteration went from declaration order to alphabetical. `parameter_digest` hashes
  parameter values, so it could not see this. It caught PB-39 only incidentally, because the pack
  refactor also changed the parameter *structure*; had the sort been introduced on its own, in a
  commit touching only `countries.py`, **the guard would have passed while every figure in the
  report silently became wrong.**
- **Why this is the family, not an instance.** Sixth appearance of the guard-with-two-doors shape
  recorded in `docs/research/lab_notebook.md` ("The pattern"): a guard that checks the input it was
  written for and not the output it exists to protect. The digest answers "did the parameters
  change?" when the question the report needs answered is **"is this report still about this
  dataset?"**
- **Acceptance:** `release.json` and `realism_report.md` carry a **dataset fingerprint** — a hash
  over a deterministic sample of output rows (the first N transaction ids and amounts under a fixed
  ordering), not over the parameters. The existing report test asserts the fingerprint as it
  currently asserts the parameter digest. Mutation-proved per E14: change the country iteration
  order alone, leaving every parameter value identical, and assert the report test **fails** — the
  exact case that passed this time.
- **Note:** the parameter digest is kept alongside it. The two answer different questions and one
  does not replace the other: the digest localises *why* a report went stale, the fingerprint
  detects *that* it did.
- **CLOSED 2026-09-19 (M3), with one correction to its own acceptance.**
  `fraudshield_dataset.fingerprint` hashes the `SAMPLE_ROWS` rows of each of the three tables that
  sort first under a canonical ordering, plus each table's row count. Sampled by **value**, never
  by position, so it is independent of partition layout and chunk size — E4 permits the chunk size
  to change and requires the dataset not to, and a fingerprint that moved with it would be loosened
  the first time it fired. The row counts are in the payload because a sample answers "are these
  the same rows?" and only the count answers "are these all of them?". The parameter digest is kept
  alongside, as the note above requires.
- **The acceptance's mutation no longer reproduces its defect, and was replaced rather than
  recorded as passing.** Reversing the country pack order leaves the dataset byte-identical
  (12,000 rows, seed 20260917): `Population._apportioned` sorts the country share itself, so
  apportionment is insensitive to the order packs arrive in. That closure is now pinned by
  `test_the_generator_no_longer_re_draws_when_the_pack_order_changes`, because deleting one
  `sorted()` would reopen it. The executed mutation is `_GOLDEN` — a constant in generator code,
  covered by no provenance record, which moves every activity multiplier and therefore every
  subsequent row. Fingerprint moves, parameter digest does not, both asserted.
- **Enforced at the export, not only recorded in a field.** `export()` refuses to bundle a realism
  report that does not carry the exported dataset's fingerprint, and takes `--report` so that a
  release-size run bundles the report generated *by that run*. A release ships the data and the
  report together and a consumer has nothing to tell them apart with, so a warning would reach only
  whoever ran the export.
- **Remaining, deliberately:** the committed `dataset/realism_report.md` describes a 1,012,522-row
  run and carries no fingerprint, since adding one means regenerating it — a citable evidence run.
  Until then it cannot be bundled with any other dataset, which the export refuses and a test
  asserts. It gains a fingerprint at its next regeneration, whether that is PB-25's release-size CI
  run or the next full local run; no release can ship without one, so this is a visible gap rather
  than a silent one.

### PB-42 · `CROSS_BLOC_AFRICA` names a continent the rule does not test
- **Source:** implementing `corridor_class` (PB-30), 2026-09-19 · **Priority:** low · **Due:** with
  the first non-African country pack, if there ever is one
- **Problem:** the class is implemented as "same continent, no shared bloc", comparing the two
  packs' `continent` codes for equality and never against a literal — which is what keeps ADR
  0023's "no country, currency or bloc named in code" true and is what the pack field's own
  rationale describes. For every pack that exists the two readings coincide, since all are African.
  For a hypothetical pair of *non-African* countries sharing no bloc, the rule is still right and
  the class **name** is a misnomer.
- **Why it is not fixed now:** the alternative is testing the continent code against `AF` in feature
  code, which reintroduces exactly the hard-coding ADR 0023 removed, in order to improve a label
  that no row in any dataset can currently carry. The fix, when it is worth making, is to rename the
  class — which is a contract change to a declared categorical and moves an encoder's cell.
- **Acceptance:** either the class is renamed (registry `categories`, ADR 0023, Part E.2 deviation
  note, and any fitted encoder) or this entry records the decision to keep the name with the
  mismatch stated.

### PB-43 · Two of `corridor_class`'s four classes are unreachable on the dataset
- **Source:** implementing `corridor_class` (PB-30), 2026-09-19 · **Priority:** medium · **Due:**
  **owner decision, before any claim rests on the feature's four-way structure**
- **Observed:** every simulated country is in the EAC and on the same continent, and
  `behaviour.remittance_corridors` sends every cross-border transfer to another simulated country
  (`{RW: [UG, KE, CD], KE: [UG, TZ], TZ: [KE, UG], UG: [KE, RW], CD: [RW, UG]}`). So a generated row
  is `DOMESTIC` or `INTRA_BLOC`, never `CROSS_BLOC_AFRICA` or `INTERCONTINENTAL`.
- **Consequence:** a model fitted on this benchmark learns nothing about the two absent classes, and
  an encoder fitted on it has no cell for them — so the first real cross-bloc transaction in serving
  meets an unseen category. The feature is correct and its four-way rule is tested against invented
  packs; what the *dataset* cannot do is exercise half of it. ML-DATA-07's completeness check cannot
  catch this: the feature is computable for 100% of rows.
- **Unlike PB-40, this is not a generator defect.** ADR 0023's owner direction is explicit that the
  simulated country set is not to be broadened, and the three assumed portability packs (PB-31)
  exist to prove the machinery generalises, not to be simulated. Producing the two absent classes
  means simulating a corridor that leaves the validated core, which is a change to the dataset draw.
- **Acceptance:** an owner decision, recorded either way. Either a cross-bloc corridor is simulated
  (a draw change, with its own provenance and a regenerated benchmark), or the paper and the claims
  register state that `corridor_class` is evaluated over two of its four classes and that the other
  two are untested in evaluation. Declared meanwhile in the registry's `degeneracy` field, with
  `test_a_feature_with_no_signal_on_this_dataset_declares_it` asserting the declaration.
- **DECIDED 2026-09-20 (owner): keep the classes, do not broaden the simulated set.** ADR 0023
  forbids it for good reason and the packs exist so the code is not EAC-specific. Recorded as a
  stated limitation rather than a defect: `CROSS_BLOC_AFRICA` and `INTERCONTINENTAL` are
  implemented and unit-tested through the synthetic Country Z pack, and unexercised by the shipped
  dataset, so **a model trained here has never seen them** and an encoder fitted here has no cell
  for them. Written into the datasheet, `docs/features.md` and the model card, and carried into
  M4's evaluation caveats: no claim that a model generalises across corridor classes can rest on
  this benchmark. The registry keeps its `degeneracy` declaration, which the existing test asserts.

### PB-44 · Eight features read reference data the dataset does not carry
- **Source:** implementing the remaining 42 features, 2026-09-20 · **Priority:** high · **Due:**
  **before M4 training** for the evaluation to cover all 44, and before M6 for the online path
- **Observed:** the features are implemented and tested on both paths, and their inputs are
  supplied by the caller — as `prior` and `first_seen_at` already were. What no caller can supply
  today is the data itself, because neither M1's schema nor the generated dataset holds it:

  | Feature | Needs | Where it would come from |
  |---|---|---|
  | `account_age_days` | the account's opening date | a per-account table (PB-37) |
  | `counterparty_account_age_days` | the counterparty account's opening date | the same table |
  | `kyc_tier` | tier assignments with `effective_at` | a per-account tier history (ADR 0026) |
  | `days_since_sim_swap` | SIM swaps before the transaction | **available**: `account_events` carries `SIM_SWAP` |
  | `agent_float_utilisation_ratio` | float balance and limit, as-of | an agent standing table |
  | `agent_distance_from_registered_km` | registered premises, as-of | the same table |
  | `round_sum_flag` | the currency's common denominations | a `round_denominations` pack field |
  | `synthetic_identity_score` | the KYC tier range, plus the four terms' inputs | the pack, plus the rows above |

- **Consequence:** each returns NaN when its input is absent, which D-04's native missing handling
  covers, so nothing breaks — and that is exactly the danger. **A feature that is NaN for every row
  is indistinguishable in a training run from one that is merely often missing**, and the model
  simply learns nothing from it while the feature count still reads 44. ML-DATA-07's completeness
  check cannot catch it for the same reason it could not catch PB-40: the feature is computable.
- **`days_since_sim_swap` is the exception and is worth stating separately**, because it shows the
  gap is not uniform: `account_events` already carries `SIM_SWAP` rows with timestamps, so that
  feature is fully computable on the current dataset as soon as the join is wired.
- **`round_sum_flag` is the cheapest to close and is a parameter change**, which is why it is not
  done here: adding `round_denominations` to the packs changes `parameter_digest`, which makes the
  committed realism report stale and requires the regeneration PB-41 already has pending. The two
  should be done together, in one evidence run, rather than costing two.
- **Acceptance:** either the data exists and the features are measured on it, or the datasheet and
  the claims register state which of the 44 were evaluated and which were constant-NaN, with the
  count. A feature set reported as "44 features" when some of them carried no information would be
  a claim about a model that was never fitted.
- **Owner direction 2026-09-20, and the count corrected to six.** The entry above estimated eight
  from reasoning about which inputs were missing. Computing the features says **six**:
  `account_age_days`, `counterparty_account_age_days`, `kyc_tier`,
  `agent_float_utilisation_ratio`, `agent_distance_from_registered_km`, `round_sum_flag`.
  `days_since_sim_swap` is computable, because `account_events` carries `SIM_SWAP` rows; and
  `synthetic_identity_score` is computable because its terms contribute zero rather than NaN when
  their evidence is absent — impaired, which is a degeneracy and already declared, not a source
  gap. The correction is the point: the estimate was made by the same kind of reasoning that
  produced the first `geo_cell` leakage note.
  1. **Done — the gap is structural, not a note.** `computable: COMPUTABLE | NO_SOURCE_DATA` is an
     eleventh registry field with no default, `source_data_gap` must name the data and the
     milestone, and `fs-features computability` fails in both directions: a COMPUTABLE feature NaN
     for 100% of rows, and a NO_SOURCE_DATA feature that is not. The second direction is what
     stops the register drifting into pessimism once somebody wires the data.
  2. **Done — named in `docs/features.md` and the datasheet**, each with what it needs, where it
     comes from and which milestone supplies it.
  3. **Carried to M4:** train on the features that carry information and report that number with
     every metric, never "44 features" where fewer were used. If one of the six becomes computable
     later, that is a documented change to the model's input and a reason to restate earlier
     numbers, not a silent improvement.
  4. **Not yet wired into CI.** `fs-features computability` needs a generated dataset, so it
     belongs in the `dataset` workflow beside the realism checks rather than in `make governance`,
     which runs without data. That workflow cannot be dispatched until the default branch moves
     (PB-25), so the check runs as part of an evidence run until then and the wiring goes in with
     PB-25's CI-only commit.
- **Also found while measuring:** the dataset carries no **account-country** column, which six
  features need (five local-time features for a UTC offset, and `corridor_class` for the origin).
  The pipeline recovers it from the transaction's currency — exact while currencies are distinct
  across simulated packs, and `fs-features` refuses rather than tie-breaking when two share one.
  The honest fix is the column. It does not change the draw, but it does change the schema.

### PB-45 · The exit-criteria table has no checker, so "met" is an author's edit
- **Source:** the M3 exit-criteria table claimed E3 was met and cited an evidence run that did not
  exist, 2026-09-20 · **Priority:** high · **Due:** before the M4 exit criteria are written, so the
  next milestone starts with the mechanism rather than retrofitting it
- **Problem:** `docs/traceability/m3_exit_criteria.md` is the only record in the repository that
  asserts a milestone's completeness, and nothing checks it. Every other record-accuracy guard here
  watches a generated artefact: the parameter digest and the dataset fingerprint for the realism
  report, `fs-readme-status` for the README's status line. The criteria table is hand-written, and
  it drifted in the flattering direction within a day of being written.
- **Third instance of the family.** The stale realism report (M2 MAJOR 4.1, then PB-39) and the
  README status line (M2 milestone review) were each fixed as a bug in one file. Three instances
  make it a property of how this project records things.
- **The design, which is tractable because the criteria sort into four kinds of evidence:** a
  register (`m3_exit_criteria.yaml`, beside `milestones.yaml`) in which each criterion declares how
  it is evidenced, with the markdown table **rendered** from it as `requirements_matrix.md` already
  is:
  - `test: <node id>` — the test exists and is collected. `fs-traceability` already resolves tagged
    tests, so the mechanism exists.
  - `gate: <command>` — the command exits 0.
  - `artifact: <path>` — the file exists **and names the commit it was produced at**, which is the
    standing rule that a figure is quoted with its tree hash rather than only its scale.
  - `judgement` — **never auto-met.** Must carry prose and a named human. A checker that marked a
    judgement criterion met would be worse than no checker: it would lend a machine's authority to
    a claim no machine made.
- **Acceptance:** `fs-exit-criteria --check` fails when a criterion claims `met` without its
  evidence resolving, and when the rendered table disagrees with the register; the M3 table is
  regenerated from the register; a mutation proves it — mark a criterion met with a missing
  artefact and assert the check exits non-zero. It joins `make governance`.

### PB-46 · Five engineered features exceed the D-08 single-feature ceiling
- **Source:** M3 exit criterion E3, measured at commit `fad43dd` and restated at `2c80ef6` after
  the milestone review's M3-2 fix, 2026-09-20 · **Priority:**
  **high — this is the control D-08 exists to enforce** · **Due:** an owner decision before M4
  reports any model metric
- **Observed:** on a 1,006,249-row dataset (seed 20260917), corpus 200,000, 20,000 scored holding
  166 confirmed fraud, with folds grouped by whole accounts and `max(AUC, 1 − AUC)` throughout:
  `velocity_ratio_1h_vs_30d` **0.894** ±0.032, `counterparty_is_new_for_account` **0.851** ±0.037,
  `tx_count_1h` **0.826** ±0.039, `implied_speed_kmh` **0.812** ±0.040,
  `seconds_since_last_tx` **0.811** ±0.040. Four more within the interval of the ceiling:
  `amount_sum_24h` 0.776, `tx_count_24h` 0.765, `unique_counterparties_24h` 0.759,
  `synthetic_identity_score` 0.759.
- **Why it matters more than a threshold being crossed.** The strongest single feature comes within
  0.05 of the 0.940 AUC that ML-GATE-01 asks of an entire model. A headline result on this
  benchmark would therefore not be evidence that the model learned anything a one-line rule could
  not, which is precisely the reading D-08 was written to prevent.
- **It is not a leak in the features.** All five are strictly backward-looking, exclude the scored
  transaction, and are the code the parity suite replays. The separation is in the data: the
  generator's fraud scenarios are burst-shaped by construction — a drain, a velocity run and a
  bust-out are all rapid sequences — so burst and recency features find them.
- **Why M2 did not catch it.** M2 measured the dataset's **columns** and found 0.707. The ceiling
  is a property of what a model can be given, and a model is given the engineered features. E3
  exists for exactly this gap, and this is the first time it has been run.
- **Acceptance:** an owner decision, recorded either way, among: (a) the generator spreads fraud
  scenarios in time so that velocity alone does not separate them — a dataset-draw change with its
  own provenance and a regenerated benchmark; (b) D-08 is restated as a claim about dataset
  columns, and the datasheet, claims register and paper say so wherever the ceiling is quoted;
  (c) the benchmark is declared velocity-separable and every model metric is reported beside the
  strongest single-feature baseline, so a reader can see what the model added. Whichever is chosen,
  C-9 stays marked refuted-as-stated and C-14 keeps the measurement.

### PB-47 · The computability check cannot see a constant feature
- **Source:** reading the E3 run beside the computability run, 2026-09-20 · **Priority:** high ·
  **Due:** with PB-44's remaining work, before M4 training
- **Problem:** `fs-features computability` asks whether a feature ever produces a number. A feature
  that always produces **the same** number passes it and carries exactly as little. Three features
  sit at a separation of 0.500 or 0.502 on the E3 run, which is what a constant looks like:
  - `just_below_limit_flag` — **constant `False` by construction**, certain from the code path: the
    benchmark supplies no channel or KYC-tier limits, `_applicable_limits` returns nothing and the
    band test is over an empty set. It is declared `COMPUTABLE` and is not.
  - `accounts_per_device_7d` — constant 1, already declared degenerate (PB-40).
  - `dormancy_reactivation_flag` — 0.500, consistent with constant `False`: accounts transact about
    once every four days here, so a 60-day silence is rare or absent. **Not verified**, and the
    check cannot currently tell "constant" from "uninformative".
- **Why the two directions are not the same check.** NaN-everywhere is a *missing input*;
  constant-everywhere is a *missing distribution*. D-04's native missing handling covers the first
  and nothing covers the second — a constant column is trained on, contributes nothing, and looks
  in every completeness count exactly like a working feature.
- **Acceptance:** `fs-features computability` reports each feature's distinct-value count over the
  sample and fails when a `COMPUTABLE` feature that is not declared `degenerate` takes one value;
  `just_below_limit_flag` is re-declared (either `NO_SOURCE_DATA` naming the limit configuration
  and the milestone that supplies it, or `degeneracy` if a deployment genuinely has no limits);
  `dormancy_reactivation_flag` is measured rather than guessed. Mutation-proved: declare a constant
  feature `COMPUTABLE` and assert the check exits non-zero.
