# Product backlog

Owner direction (2026-09-17): from the M1 database work on, reviews fix BLOCKER and MAJOR findings
(security, data integrity, RLS isolation, append-only guarantees, audit hash chain) in place. MINOR and
NIT findings, and backlog candidates raised by re-checks, are logged here instead of starting
fix-and-re-review rounds. Governance-tooling items stay in `governance.md`.

Format: ID · title · source · priority · due · problem · acceptance. Due dates are milestone IDs
from build prompt Part D.3 (M2 is the dataset generator; the scoring service is M5, ingestion and
the decision engine M6, staff identity, admin and audit M7).

**Identifiers are claimed before they are used** (owner decision, 2026-09-24, applied 2026-10-17).
A milestone that needs a backlog id, an ADR number or a migration version writes the claim into
`docs/parallel/<milestone>_updates.md` first, after reading the other milestones' updates files on
every branch, and only then uses it. "Next free" is only true on the branch that asks: parallel
branches do not see each other's edits to this file, which is how `PB-69` and `PB-73` were each
taken twice. Claims made on branches not yet merged, as of 2026-10-17:

| Id | Claimed by | Where | Subject |
|---|---|---|---|
| PB-70, PB-71 | M5, owned by M6 (owner confirmed 2026-10-17) | `M5_updates.md` §15 | the `fs.labels` consumer; the reference-state contract and producer |
| PB-72 | M5, owned by M10 (owner confirmed 2026-10-17) | `M5_updates.md` §15 | re-measuring training-serving skew |
| PB-76 | M4 | `M4_updates.md` on `m4/generalisation` | training rows near the corpus's start see truncated history (was a second `PB-69`) |
| PB-77 to PB-101 | M6 | `M6_updates.md`, "Numbering reserved by this branch" | amendment 1's known gaps and the 22 section-8 rows proved only in part |
| PB-102 to PB-130 | M7 | `M7_updates.md`, "Numbering reserved by this branch" | the merge round's unowned items and M7's rows proved only in part (PB-102 to PB-124 used) |
| PB-131 to PB-140 | M8 | `M8_updates.md`, "Numbering reserved by this branch" | the console's own follow-ups (PB-131 to PB-133 used) |

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
- **Closed 2026-09-21 (M4), two mechanisms.** A **ring** of synthetic identities transacts from one
  handset, which is the "shared device and phone attributes across accounts" term Part E.2 names
  and the scenario had no way to produce; and a share of ordinary customers use a **handed-down**
  handset, so that a device seen on several accounts is not by itself a fraud signal. The second
  is load-bearing: without it the ring device would be a shortcut and the realism gate would refuse
  the dataset, correctly. Both rates are `ASSUMED` and the packs say why — the 2026-09-18 sourcing
  pass established that no publication gives handset-sharing rates for these markets.
  `test_devices_are_shared_between_accounts` asserts the **distribution**, not the existence of one
  shared device, because a single shared handset would satisfy "sharing happens" while leaving the
  feature constant for every row that matters. A shared handset has no device-change generation of
  its own: two people using one phone are using one phone, so a sharer keeps the token and the
  owner moves off it when they replace their own — which is how a device comes to be seen on two
  accounts and later on one. The predicate deciding who is a synthetic identity is duplicated
  between `Population` and `FraudModel` (factoring it out would move the bust-out draw that follows
  it in the same stream) and pinned by
  `test_the_population_and_the_fraud_model_agree_on_who_is_synthetic`.
- **Measured before it was believed.** The first ring assignment bucketed consecutive customer
  indices, and synthetic identities are about a ninth of the population, so four consecutive
  indices held less than one of them: it produced a single device with four accounts where it
  should have produced some two hundred. Ring membership is drawn from a sized pool instead. The
  check that caught it took seconds and ran before the 20-minute regeneration, which is the only
  reason it was not found afterwards.

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
- **CLOSED 2026-09-20, before M4's criteria were written**, which was the point of the due date.
  `fs-exit-criteria` resolves three kinds mechanically — a test that exists in a tracked test file,
  a gate the `governance` target runs, an artefact that names the commit it was produced at — and
  refuses `judgement` in **both** directions: a judgement may only be `author_asserted`, and a
  criterion whose evidence *is* mechanisable may not hide behind that label. The M3 table is now
  generated between markers, so the prose around it survives while the rows cannot drift.
- **It found something on its first run**: the M3 milestone review named no commit, so the row
  claiming it as evidence could have pointed at any version of the file. The review now names the
  commit its findings were fixed at.
- **What it deliberately does not do.** It checks that a gate is *wired into* `make governance`
  rather than running it, because running it would double what CI does and — since the checker is
  itself part of `governance` — recurse. `--run-gates` executes them when someone wants that.
- **Three M3 rows remain author-asserted and say so in the table**: E2 (every metric states its
  scale), E12 (preconditions asserted first) and E13 (features exercised non-trivially). None is
  mechanically decidable, and two of them describe habits this milestone twice failed to keep — so
  the label is accurate rather than modest.

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

### PB-48 · The dataset does not publish its temporal split boundaries
- **Source:** writing the M4 pipeline smoke test, 2026-09-20 · **Priority:** high · **Due:** before
  any M4 metric is reported, because every one of them is defined on this split
- **Problem:** D-07 specifies a temporal train/validation/calibration/test split with a seven-day
  embargo, and `plan_split` computes its four boundaries from the row count and the calibrated
  volume. **None of them reaches the published output.** `manifest.json` carries rows, checksums,
  seed and per-month counts; `release.json` adds the partitioning convention and the fingerprint.
  A consumer holding the Parquet cannot say which rows are training rows.
- **Why it matters more than it looks.** The boundaries are not a convenience: an evaluation
  computed on a different split is not comparable with the gates, and the embargo is the thing
  standing between a validation row and a training row of the same incident. A consumer who has to
  reconstruct them will reconstruct them slightly differently, and nothing will say so — the same
  shape as PB-26's partition key, where a convention that lived only in the generator made
  pruning unsound for everyone else.
- **Why the smoke test did not just import the planner.** `fraudshield_ml` must not import
  `fraudshield_dataset`: the feature pipeline consumes the published interchange format so that it
  cannot read values a release does not carry (the arrangement `fs-dataset packs` already
  establishes for country facts). Re-implementing `plan_split` inside ml would duplicate a
  non-trivial algorithm and give it two places to drift.
- **Acceptance:** `fs-dataset split --output split.json` publishes the four boundaries as
  timestamps, alongside the row counts and observed fraud rate of each segment; `release.json`
  carries the same block so a release is self-describing; a test asserts the published boundaries
  reproduce the segment row counts the realism report states. The smoke test's time-ordered
  holdout is replaced by the real split the day it exists.
- **Interim, and stated in the smoke test's own output:** it uses a 70/30 time-ordered holdout of
  its scored sample, which is *a* temporal split and not *the* one, so its numbers are not
  comparable with anything M4 will report.
- **Closed 2026-09-20 (M4).** `planned_block` records the four boundaries into `manifest.json` at
  generation, where the target row count the planner scaled by is still known; `fs-dataset split
  --output split.json` adds the measured segment counts and fraud rates; `release.json` carries the
  same block from the same function. `realism.checks.split_counts` now delegates to
  `release.split.segment_counts`, so the report's table and the published sidecar have one
  definition, and `test_the_published_split_reproduces_the_realism_report` parses the rendered
  markdown and compares it with `split.json` — the comparison happens where a reader reads.
  A dataset generated before this **refuses** rather than reconstructing: the target row count is
  recorded nowhere else, and a boundary guessed from the realised count lands within hours of the
  right answer, which is the error that would never be noticed. The existing 1M benchmark draw is
  one of those datasets, so consuming the split in `fraudshield_ml` waits on the regeneration
  PB-41 and PB-44 already require (PB-49).

### PB-49 · The feature pipeline still cuts its own holdout
- **Source:** closing PB-48, 2026-09-20 · **Priority:** high · **Due:** with the first M4 metric
- **Problem:** the split is published now, but `fs-features smoke` still takes a 70/30 time-ordered
  holdout of its scored sample, because the benchmark draw in use predates the block and refuses to
  reconstruct it. Every number it prints is therefore computed on a split no gate is defined on.
- **Acceptance:** regenerate the benchmark (which PB-41's fingerprint and PB-44's
  `round_sum_flag` gap already require), then teach the feature CLI to read `split.json` — the
  published format, never `fraudshield_dataset` — and assign each scored row to train, validation,
  calibration or test by the published boundaries, excluding the embargo. No M4 headline metric is
  reported on anything else.
- **Closed 2026-09-21 (M4).** `fraudshield_ml.training.split` reads the published `split.json` —
  the interchange format, never `fraudshield_dataset` — and `fs-features evaluate` fits on the
  train period and scores the test period. The embargo is unreachable by construction rather than
  by remembering: `FITTABLE` names the two segments a model may be fitted on and the embargo is
  not one of them. **Calibration overlaps validation and the reader says so**: `segment_of`
  returns one of four disjoint periods and `in_calibration` is asked separately, because a
  `segment_of` returning one of five names would silently remove the calibration rows from
  validation and a model would be tuned on a period it had also calibrated on.
- **A separate command rather than a flag on `smoke`.** The smoke test stays exactly what it is —
  a pipeline check that cuts its own holdout and says so in its first three lines. Teaching it to
  produce a real metric under a flag would have made one report either caveat a result or promote
  a check, and the caveat is the part that gets dropped when a figure is quoted.
- **Stated limit, not a hidden one:** the corpus is read newest-first and bounded, so the training
  rows are the *tail* of the train period rather than a draw from all of it. The report prints the
  number of days covered beside every figure. A figure from here is comparable with the gates in
  its split and not in its training volume.

### PB-50 · The milestone register cannot advance to M4, and that is a finding
- **Source:** starting M4, 2026-09-20 · **Priority:** high · **Due:** before the register records
  M3 as completed
- **Problem:** setting `current: M4` and `completed: [M0, M1, M2, M3]` makes `fs-traceability
  check` fail with nine errors across six requirements — FR-02-02, FR-02-09, ML-DATA-07, TEST-01,
  D-03 and D-04 are Must rows assigned to M3 whose `status` still reads `NOT_STARTED` and whose
  `implementation` and `evidence` lists are **empty**. The work exists for most of them: 239
  tagged feature tests, `test_completeness.py`, the four device features NaN together for D-04,
  the 46-versus-44 count settled as bookkeeping for D-03. The record does not say so.
- **Why it is not just bookkeeping.** Two of the six cannot be marked done at all.
  **FR-02-09** (a Redis feature store refreshing velocity features within 100 ms) is M5 work
  carrying an M3 milestone, and **FR-02-02** carries a `< 10ms` latency claim which ADR 0010 says
  may only be measured on the dedicated machine in `docs/benchmarks/hardware.md` — so it reaches
  `VERIFIED_AT_REDUCED_SCALE` at best. A register that advanced anyway would be asserting a gate
  passed that did not.
- **Held deliberately:** `milestones.yaml` still reads `current: M3`, and the README follows it.
  The tag `m3-complete` records the owner's judgement; the register records the gate, and the two
  disagree until this is closed. That disagreement is the accurate state, not a bug to paper over.
- **Acceptance:** fill `implementation` and `evidence` for the four rows the work covers and set
  their status from the evidence; reassign FR-02-09 to the milestone that will build it; decide
  FR-02-02's status against ADR 0010's hardware rule. Then advance the register and the README in
  one commit, with `fs-traceability check` green.
- **Closed 2026-09-20 (M4), ADR 0027**, on the owner's direction to treat a requirement filed under
  a milestone that cannot satisfy it as a specification error rather than a deviation — the same
  reasoning ADR 0024 used at M2 close. **FR-02-09 → M5**, because the build prompt's own M5 gate
  reads "FR-02-01, 02-04 … 02-10 tests pass" and there is no feature store, no Redis and no
  Prometheus. **ML-DATA-07 → M6**, because a completeness requirement cannot be judged before the
  data it counts exists, and the note claiming "≥ 98% computable" was satisfied "in the sense of
  not raising while six features carry nothing" is withdrawn as a fudge. **TEST-01 → M4**, because
  its fourth named scenario needs `round_sum_flag`, whose gap the registry schedules for M4.
  **D-03 and D-04 are DONE** with evidence; only their register fields were unfilled.
  **FR-02-02 stays in M3 as DONE_WITH_DEVIATION** — its acceptance criterion ("unit tests confirm
  all 44 computed for all 6 channel types; USSD handles missing device_fingerprint gracefully") was
  met, and only the `< 10 ms` in its specification line is carried, to M10 under ADR 0010's
  dedicated-hardware condition (PB-51). Moving the whole row would have taken the 44-feature
  requirement out of the milestone that delivered it. `milestones.yaml` now reads `current: M4`,
  `completed: [M0, M1, M2, M3]`, and `fs-traceability check` passes on its own with the hook
  intact.

### PB-51 · FR-02-02's `< 10 ms` is carried to M10 and needs the dedicated machine
- **Source:** ADR 0027, 2026-09-20 · **Priority:** medium · **Due:** M10, the verification campaign
- **Problem:** FR-02-02 specifies 44 features per transaction **within < 10 ms**. The acceptance
  criterion is met and the row is closed on it, but the latency figure has never been measured.
  ADR 0010 forbids taking it from a shared CI runner, where CPU model, neighbours and I/O vary
  between runs, and the reference laptop cannot host the stack.
- **What is known:** the batch path computes 36 features for 20,000 rows at about 28 rows/second
  with a 200,000-row corpus index — roughly 36 ms per row, and that is the *offline* path scanning
  a corpus, not the online path reading prepared state. The online path has never been timed and
  the two are not comparable; quoting the batch figure against a `< 10 ms` serving budget would be
  a category error.
- **Acceptance:** `benchmark.py features` p95 on the machine named in `docs/benchmarks/hardware.md`,
  with the run recorded there, reported against the 10 ms budget, and FR-02-02's carried clause
  given a final status in M10's matrix — `VERIFIED_AT_REDUCED_SCALE` at best until then.

### PB-52 · A committed report described a draw this tree does not produce
- **Source:** regenerating the 1M benchmark for PB-41/PB-49, 2026-09-21 · **Priority:** high ·
  **Due:** before any figure from the M2 era is quoted again
- **Problem:** `dataset/realism_report.md` described **1,012,522 rows**, and the claims register
  attributed that draw to tree `d85385f` and its difference from M2's published numbers to PB-29
  changing the country iteration order. Regenerating produced **1,006,249 rows**, twice, with
  identical fingerprints — and `git diff d85385f HEAD -- dataset/generator/params
  dataset/src/fraudshield_dataset/generator` is **empty**, so that tree's code is this tree's code
  and it does not produce 1,012,522 rows.
- **And the stated mechanism is independently false.** Country iteration order does not change the
  draw: `Population._apportioned` sorts internally, which is why PB-41's prescribed mutation
  (reversing the pack order) yielded a byte-identical dataset and had to be replaced with a
  `_GOLDEN` constant mutation. PB-29 cannot have re-drawn anything.
- **What is not in doubt:** the current dataset. It is reproducible from the seed, its fingerprint
  `40a77bb6` is published in the report that describes it, and every gate passes. The defect is a
  provenance sentence, not data.
- **The suspicious part:** the regenerated figures (`merchant_category_code` 0.706, event delay
  0.758, event type 0.537) are *exactly* M2's published ones. So the 1,012,522-row run is the
  outlier, not the current one, and whatever produced it was present for one measurement and is
  absent now.
- **Acceptance:** find what produced the 1,012,522-row draw — check whether the run used a
  different `--rows`, an uncommitted change, or a different parameter file — and either reproduce
  it or record that it cannot be reproduced and why. Then state the rule the episode teaches: a
  generated artefact must be regenerated by the tree that commits it, or the commit is not its
  provenance. `fs-exit-criteria`'s artefact kind already checks that a cited artefact names its
  commit; it does not check that the artefact was produced *at* that commit, and this is the case
  that distinguishes them.

#### Investigated 2026-09-21, 30-minute box. Cause: a dirty working tree. Change: unrecoverable.

Three candidate causes, tested in order of cheapness:

1. **A different `--rows`.** Refuted from the artefact itself. The split boundaries are planned
   from the *target* row count, so a different target moves them; the 1,012,522-row report and the
   current one state the **same test start (2025-10-28 22:02 UTC)** and the same segment spans to
   two decimals. Same target, different rows.
2. **A committed generator change.** Refuted from the history. The 1,012,522 figure appears
   **exactly once** in the report's entire history, at `a36d252` (2026-09-19 15:16); every other
   committed report, before and after, says 1,006,249. The only commit touching
   `dataset/generator` or `dataset/generator/params` in the window between the last 1,006,249
   report and that one is `dce89fe`, the pack refactor itself.
3. **The pack refactor.** Refuted by measurement, which is the part worth keeping. Generating
   200,000 rows at seed 20260917 on `683b7b6` (the commit *before* the refactor) and on the
   current tree gives **201,243 rows on both** and the **identical fingerprint**
   `9525c27fe636ce7ed5964215d700886ea15233cbbc4357f7c98f8d95937c5d6c`. The planned volume,
   `customers_total` and `customers_active` are also identical. **PB-29 did not re-draw anything**,
   and the explanation that stood in six documents for two days was never true.

What remains is that the run was made on a **dirty working tree**: code that is in no commit. The
commit message for `a36d252` says the run was "over tree `d85385f`", and `d85385f`'s committed
generator is byte-identical to today's, which produces 1,006,249. The specific uncommitted change
is **unrecoverable** — it was never committed, stashed or described, and no artefact from the run
records anything but the commit hash it was *believed* to be at.

**That last sentence is the whole finding, and it is why the fix is a guard rather than an
answer.** A hash recorded by hand records an intention.

**Closed 2026-09-21 as UNEXPLAINED-WITH-GUARD.** The cause is established (a dirty working tree);
the specific change is not, and cannot be — it exists in no object this repository holds. PB-53
supplies the guard so the next one is detected at the moment it happens rather than two days
later. Every quotation of the 1,012,522-row figure is corrected or annotated.

### PB-53 · An evidence run records the commit it believed it was at
- **Source:** PB-52's investigation, 2026-09-21 · **Priority:** high · **Due:** immediately; it is
  the only thing standing between this project and a second PB-52
- **Problem:** every evidence artefact here names a commit, and that name is written by hand or
  passed as a flag. It therefore records what the author *believed* the tree was, not what it was.
  PB-52 is exactly that failure: a report was produced from uncommitted code, labelled with a
  commit whose generator does not produce it, and six documents then explained the discrepancy
  with a mechanism that measurement has since refuted. Nothing detected it for two days, and the
  code that produced it is gone.
- **Why naming a commit is not enough.** A commit hash identifies a tree in the object database.
  It says nothing about the files the interpreter actually imported, which are the working tree.
  The two agree only when the working tree is clean, and nothing checked that.
- **Acceptance:**
  1. A shared provenance stamp: the commit, **and** a hash of `git status --porcelain`, which is a
     constant for a clean tree and varies with any modification. Both go into every evidence
     artefact's header.
  2. Evidence runs **refuse to start** on a dirty tree, with an explicit escape that stamps the
     artefact `DIRTY` so an unciteable run is unciteable on its face rather than by omission.
  3. `fs-exit-criteria`'s `artifact` kind checks the header: the artefact must carry a stamp, its
     commit must match the one cited, and its tree state must be clean. That closes the gap
     between "names a commit" and "was produced at that commit".
- **Not in scope:** proving the interpreter imported the working tree rather than an installed
  copy. `uv run` from the repo makes that true here, and a guard for it would be a different
  mechanism.
- **Closed 2026-09-21.** `fraudshield_tools.provenance` supplies the stamp: the commit plus a
  digest of `git status --porcelain`, which is `clean` for an unmodified tree and varies with any
  modification, **including untracked files** — the archetypal accident is a new module imported
  and not yet added, which a tracked-content diff would call clean. `fs-evidence --output <file>
  -- <command>` refuses on a dirty tree and stamps `NOT CITABLE` under `--allow-dirty`; it is a
  wrapper rather than a flag on each producer so that `fraudshield_dataset` and `fraudshield_ml`
  do not acquire a dependency on the governance package. `fs-exit-criteria` reads the stamp:
  absent is a **warning** (every artefact predating the guard lacks one, and failing them would
  get the threshold lowered until nothing failed), present-and-contradicting is an **error**.

### PB-54 · The fingerprint read three columns of fourteen
- **Source:** regenerating for PB-40, 2026-09-21 · **Priority:** high · **Resolved:** same day
- **What happened:** PB-40 gave the generator a device-sharing mechanism. It rewrote
  `device_fingerprint` for 504 devices and changed nothing else — and `dataset_fingerprint`
  returned **the identical value** for the old draw and the new one. Measured, not suspected: the
  new dataset holds devices serving up to seven accounts and the old one held none serving two.
- **Cause:** `SAMPLED_COLUMNS` hashed three columns per table, and `device_fingerprint` was not
  among them. The fingerprint exists to answer "is this report still about this dataset?" and
  could not see a change to eleven of the fourteen transaction columns.
- **Why it matters more than an ordinary bug.** PB-41 built this guard *because* the parameter
  digest checked the input it was written for rather than the output it exists to protect — the
  sixth instance of that shape in the notebook. The fingerprint then made the same mistake one
  level down, in the module whose own docstring names the pattern. **A guard is not exempt from
  the failure it was built to catch.**
- **Fix:** version 2 hashes **every** column of each sampled row. `ORDERING_COLUMN` now names only
  the column the sample is *ordered* by, which decides which rows are sampled and nothing else.
  `FINGERPRINT_VERSION` is bumped so a version 1 value is never compared with a version 2 one —
  which is what that constant was for.
- **The test is per column, over every column the table has**, because choosing which columns to
  check would reproduce the original mistake inside the test written to prevent it. It perturbs a
  row the sample actually contains: the first attempt edited row 0 of the first partition and
  failed for all fourteen columns, since the sample is the 1,024 rows that sort first by
  identifier and row 0 is almost never among them. A test that edits an unsampled row proves
  nothing about any column.
- **Consequence for the record:** every fingerprint published before 2026-09-21 is a version 1
  value. `40a77bb6` (v1) is `b896b632` under v2; the current dataset is `c8856a0e`.

### PB-55 · The first real metric compared two numbers over different populations
- **Source:** the first `fs-features evaluate` run, 2026-09-21 · **Priority:** high ·
  **Resolved:** same day, before the figure was quoted anywhere
- **What it printed:** model AUC 0.998, best single feature **1.000** (`days_since_sim_swap`),
  margin **-0.002**. The margin is meaningless. `auc` drops NaN scores with their labels — which
  is correct, a structurally missing value is not a low value — so `days_since_sim_swap` was
  scored on the accounts that had a SIM swap while the model was scored on all 8,000 held-out
  rows. Subtracting them compares different populations.
- **Fix:** a baseline now carries the rows it was measured on. The **margin** is taken against the
  strongest feature defined on *every* held-out row; the strongest feature of any coverage is
  reported separately, with its coverage, and the report says in as many words that the two cannot
  be subtracted.
- **The shape, which is the part worth keeping.** PB-46 established the rule "report every metric
  as a margin over the single-feature baseline". The rule was implemented and the implementation
  did not satisfy it, because "the baseline" silently meant "over whatever rows that feature
  happens to be defined on". A rule that is followed literally and violated in substance is the
  same failure as a guard that checks the input it was written for — the seventh and eighth
  instances of that family are three days apart.

### PB-56 · `days_since_sim_swap` separates perfectly where it is defined
- **Source:** the first `fs-features evaluate` run, 2026-09-21 · **Priority:** high · **Due:**
  before any M4 headline metric is published
- **Measured, and immediately overstated by me.** The first run reported `max(AUC, 1-AUC)` of
  **1.000** on the rows where it is defined, and this entry was first written as though that
  settled something. The corrected run prints the denominator: those are **660 held-out rows
  holding 2 confirmed fraud**. An AUC of 1.000 over two positives is not evidence of perfect
  separation; it is evidence of a subsample too small to say anything. The entry is kept with its
  correction rather than rewritten, because "the figure looked decisive until its denominator was
  printed" is the finding.
- **It is not a leak, and that is why it matters.** A SIM swap before a takeover is how that fraud
  works and a model is meant to learn it (C-11). But the *degree* is an artefact of the generator:
  `fraud.takeover_lead_minutes = [5, 60]`, provenance **ASSUMED**, so every enabling event is
  followed by its drain inside a tight uniform window with no long tail and no unexploited swap.
  Among accounts that had a swap, "days since" therefore orders fraud from legitimate perfectly.
- **What survives the correction.** Not "a feature separates perfectly" — two positives cannot
  support that. What survives is the *mechanism*: the lead window is tight, uniform and ASSUMED,
  so among accounts with a swap the ordering is near-deterministic by construction, and the
  dataset-level gate cannot see it because D-08 measures *columns* over *all* rows, where the
  delay channel reads 0.758. The right response is to widen the measurement, not to quote the
  1.000: score enough test rows that the defined subset holds tens of fraud rather than two.
- **PB-46's velocity separability is still the larger, better-measured finding**:
  `velocity_ratio_1h_vs_30d` reaches **0.871 on every held-out row**, which is what the model's
  margin is taken against.
- **Closed 2026-09-21 by a third run, and it was never a finding at all.** The first two runs
  sampled the *tail* of each period. With the sample spread across the periods instead, the
  held-out rows hold 63 fraud rather than 40, and `days_since_sim_swap` is no longer the strongest
  feature at any coverage — the strongest is `velocity_ratio_1h_vs_30d`, defined on 100% of rows.
  The 1.000 was an artefact of a tail sample over two positives, twice over.
- **What to keep from it.** Nothing about SIM swaps; something about method. A figure was printed,
  written into the backlog as a finding, corrected once when its denominator was printed, and
  withdrawn entirely when the sample was made representative. **The first version of a measurement
  is the one most likely to be about the sampling.** The report now prints coverage and fraud
  counts beside every baseline so the denominator arrives with the number rather than after it.
- **Acceptance:** state it in the datasheet, the model card and the paper's limitations with the
  same prominence as the velocity separability and with the mechanism named; report it beside
  every model metric as the evaluation command now does; and decide whether
  `takeover_lead_minutes` should carry a long tail, which **changes the dataset draw** and is
  therefore an owner decision rather than one to take while closing a backlog item.
- **Closed 2026-09-22 (owner decision).** The lead is now a clipped lognormal: bounds `[5, 43200]`
  minutes, median 45, log-sigma 1.8 — a quarter inside 13 minutes, 43.6% beyond an hour, 2.7%
  beyond a day, all ASSUMED and the pack rationale says why a lognormal and why not a mixture.
  Regenerated at fingerprint `6abde44e`; all gate checks pass.
- **The prediction was recorded first and held.** Committed at `0138099` before the draw existed:
  the event-delay channel would fall from 0.758 into 0.68–0.74, and a fall under 0.01 would refute
  C-11's mechanism claim. Measured **0.730**.
- **And the residual is the finding.** A fall of 0.028 is **11% of the excess over 0.5**, so nine
  tenths of that channel's separation is the scenario and one tenth was the window. C-11 moves
  from PARTLY ASSUMED to MEASURED. The hedge that has ridden along with every quotation of this
  figure for four days implied it might be mostly artefact; it was not.

### PB-57 · `fs-evidence` captures its command's output instead of streaming it
- **Source:** the first `fs-features evaluate` run, 2026-09-21 · **Priority:** medium · **Due:**
  before the next long evidence run
- **Problem:** `evidence_run` uses `subprocess.run(..., capture_output=True)`, so nothing appears
  until the command exits. A 35-minute run is completely silent, and a silent run cannot be told
  apart from a hung one.
- **Why it is worth its own item.** The lab notebook already carries an entry on exactly this —
  106 minutes spent on a run that printed nothing because the progress line sat outside the loop —
  and the conclusion was that an expensive stage must report progress. The guard written three
  hours later reintroduced the silence for every run that goes through it. **Knowing a failure
  mode does not immunise against it**, which is the notebook's other standing lesson, and this is
  its fourth instance.
- **Acceptance:** tee rather than capture — stream the child's output to this process's stdout as
  it arrives *and* accumulate it for the artefact. The artefact's content must not change.

### PB-58 · Recall at 1% FPR charged nothing for ties
- **Source:** the M4 battery's keep-one-only ablation, 2026-09-22 · **Priority:** high ·
  **Resolved:** same day, before any gate quoted it
- **What it printed:** `agent alone (2)` — AUC **0.551**, recall at 1% FPR **0.904**. That is
  arithmetically impossible, and impossible figures are the useful kind: they cannot be argued
  with.
- **Cause:** the agent features are NaN for every non-agent row, so a model given only them scores
  almost the whole population identically. The threshold was the 99th percentile of negatives and
  recall counted positives **at or above** it, which admits every negative tied *on* the threshold
  for free. The realised false-positive rate was near 1.0, not 0.01.
- **Why it matters beyond one silly row:** ML-GATE-02 and ML-GATE-03 are defined at this operating
  point. The error is invisible on a well-separated model — every figure the project has quoted so
  far is unaffected, because ties are rare when a model works — and appears exactly when a model
  is degenerate, which is when a gate most needs to fail.
- **Fix:** count positives **strictly above** the threshold. That is the operating point a rule
  engine could actually run, it never claims a rate the scores cannot deliver, and a degenerate
  model now reports a recall near zero.
- **The general shape:** a metric that is correct on good inputs and wrong on bad ones is worse
  than one that is wrong on both, because nothing exercises it until the day it matters.

### PB-59 · Leave-one-country-out measures nothing on this benchmark
- **Source:** the M4 battery, 2026-09-22 · **Priority:** high · **Due:** before the paper's
  generalisation section is written
- **Measured:** removing a country from training entirely changes its AUC by at most 0.002 — KE
  0.996 → 0.996, RW 0.994 → 0.993, TZ 0.981 → 0.979, UG 0.975 → 0.976.
- **Why:** the generator applies one scenario library to every country, so fraud patterns are not
  country-specific and there is nothing for a country hold-out to withhold. The ablation says the
  same thing one level down: four disjoint feature groups each reach 0.845 or better alone, so
  removing any one route leaves the others intact.
- **This refutes half of PB-46.** That decision recorded that "leave-one-country-out and the novel
  sub-variant carry the weight the headline AUC no longer can". LOCO carries none of it. PB-46
  also *named the mechanism* — "burstiness is not country-specific, so a velocity threshold
  transfers trivially" — as a risk, four days before it was measured.
- **Acceptance:** report LOCO as a **negative result** rather than dropping it — a generalisation
  test that cannot fail is worth publishing as such, because a reader would otherwise assume it
  was omitted for being unflattering. State in the paper that geographic generalisation is easy on
  this benchmark and says nothing. Then make the **novel sub-variant** experiment the load-bearing
  one, since a temporal hold-out is the only one of the two that can still fail.
- **Closed 2026-09-22 (owner decision).** LOCO is accepted as a null result and the generator is
  **not** changed to make countries differ: engineering country-specific fraud so the experiment
  becomes informative would be tuning the benchmark to produce a result. The mechanism is
  confirmed and now tested — `test_no_fraud_parameter_is_keyed_by_country` asserts that no fraud
  parameter is keyed by a country code, so country enters only as a lookup for *which* mule,
  merchant, agent, currency or offset an incident uses. Recorded in the datasheet, the claims
  register and the paper's limitations; removed from the contribution list. Country-level
  generalisation belongs to the real-data validation plan, not to a benchmark whose fraud
  mechanisms are country-invariant.
- **And the replacement failed the same way (PB-61).** The novel sub-variant was promoted to
  load-bearing on 2026-09-22 and measured the same evening: the unseen shape is caught at **100%**
  (38 of 38) against 95.1% for the shape the model trained on. Both of PB-46's generalisation
  experiments are null, for one reason — see PB-61.

### PB-61 · Both generalisation experiments are null, and the cause is the same
- **Source:** the novel-variant hold-out, 2026-09-22 · **Priority:** high · **Due:** before the
  paper's evaluation section is written
- **Measured:** the novel sub-variant (`novel_esim_delayed_drain`, present only in the test period
  so the model has provably never seen it) is caught at **100.0%** on 38 fraud rows, AUC 0.999,
  against **95.1%** and 0.994 for the base variant at the same threshold and against the same
  legitimate rows. The unseen shape is *easier*, not harder.
- **Why, and it is the same reason as PB-59 and PB-60.** The benchmark encodes fraud as **bursts**.
  The novel variant's novelty is in the *lead time* — a drain delayed by days rather than minutes
  after the enabling event — and not in the transaction pattern, which is still a burst. A model
  that detects bursts catches it without ever having seen the variant. Equally, four disjoint
  feature groups each reach ≥0.845 alone because each is a different view of the same burst, and
  removing a country removes no mechanism because every country's fraud is the same burst.
- **The honest statement:** this benchmark supports **no** generalisation claim. Not geographic,
  not temporal-to-an-unseen-variant. It supports claims about detection *given* burst-structured
  fraud, and about the cost of computing and explaining that detection.
- **Acceptance:** report both experiments as null results with the shared cause named; remove
  generalisation from the paper's contribution list; and state in the datasheet and the model card
  that a variant differing only in timing is not an out-of-distribution test. If a future draw
  wants a real one, it needs a variant whose **transaction pattern** differs — which changes the
  draw and is an owner decision.
- **Not a reason to engineer one now.** The same argument as PB-59: designing a variant until the
  experiment fails would be tuning the benchmark to produce a result.
- **Not in scope here:** making the generator's scenarios country-specific. That would change the
  draw and is an owner decision; it would also be a claim about how fraud differs between these
  markets, which the 2026-09-18 sourcing pass established no publication supports.
- **Updated 2026-09-22 — the real variant was added and measured (ADR 0028).** One
  pre-registered, typology-grounded, non-burst variant (`reversal_scam_social_engineering`:
  victim-initiated, single transaction, established counterparty — see the lab notebook's
  2026-09-22 entries for the pre-registration and the result). Measured recall **9.1%** (6 of 66
  fraud rows), Wilson 95% interval [4.2%, 18.4%], against base's 94.4% [92.7%, 95.8%] — intervals
  do not overlap (first measured at 6.1% [2.4%, 14.6%] with a target encoding E1 forbids, and restated after the correction; the interval now reaches past the pre-registered range's 0.15 lower edge). The point estimate is below the pre-registered prediction range
  (0.15–0.55), though the interval no longer excludes it entirely: the model's dependence on
  burst-structure and counterparty-novelty was underestimated, not overestimated. This is now the
  benchmark's one measured non-burst detection failure and belongs in the paper alongside the two
  null results, not as a replacement for either. Evidence: `docs/benchmarks/m4_battery_d8083dbc_e1.txt`
  (restated), `docs/benchmarks/m4_battery_pb61.txt` (first measurement).

### PB-60 · An ablation on this benchmark cannot say which features matter
- **Source:** the M4 battery, 2026-09-22 · **Priority:** medium · **Due:** before C-4's ablation
  is reported
- **Measured:** removing any one of ten feature groups costs ≤0.031 AUC and eight cost ≤0.001,
  while four groups each reach ≥0.845 **alone** (counterparty 0.949, velocity 0.871, temporal
  0.861, geographic 0.845).
- **Consequence for C-4.** The plan was to replace the unverifiable "Western models achieve
  0.72–0.78 on East African data" with a measured ablation, "card-style feature set only vs full
  EAC feature set". On a benchmark where four disjoint groups each reach 0.85 alone, that
  comparison will show a small difference whatever is true of real systems, and the small
  difference will mean nothing. Reporting it as evidence for the EAC-specific feature claim would
  be the D-08 shape again: a control whose scope is narrower than the claim it justifies.
- **Acceptance:** report the leave-one-out and keep-one-only tables **together**, since either
  alone is misleading in opposite directions, and state that redundancy is why. Decide what C-4
  becomes: either withdraw the claim, or replace it with a comparison that redundancy cannot
  flatten.

### PB-62 · Recall and FNR at D-02's 0.60 flag threshold fail the M4 gate
- **Source:** the declared M4 gate run, 2026-09-22 (`docs/benchmarks/m4_gate_d8083dbc_v3.txt`) ·
  **Priority:** high · **Due:** owner decision before M5 sets serving thresholds
- **Measured:** recall at 0.60 **0.736** [0.707, 0.765] against ML-GATE-03's 0.88; FNR at 0.60
  **0.264** against ML-GATE-06's 0.12 (the same fact, since FNR = 1 − recall there). Five-seed
  recall 0.744 ± 0.041. The other nine gate metrics pass.
- **What the artefact already says:** at 0.60 the calibrated ensemble is conservative — precision
  0.927 at 0.06% FPR — and even at the 1% FPR budget, far below 0.60, recall is 0.871. D-02's
  reference point (recall 0.88 at about 0.28% FPR) is out of reach at every threshold this run
  shows, so moving the threshold would not meet the gate either.
- **Not a reason to tune on the test period** (D.3). A threshold chosen by looking at these
  numbers would be exactly that.
- **Acceptance, one of:** (a) PB-67's larger training sample, reported beside this run whichever
  way it lands; (b) D-02's operating point revisited as a defect resolution, argued from the
  validation period and D-10's alert budget, not from the test period; (c) the shortfall accepted
  and carried into the paper as measured. The choice is the owner's.
- **Decided 2026-09-22 (owner): option (c).** Both rows are `DONE_WITH_DEVIATION` against ADR 0031's
  amendment, the miss recorded in each and in the paper as a finding. Option (a) stays open as
  PB-67.

### PB-63 · The CI ML gate has no dataset to run on
- **Source:** M4 milestone review, TEST-14, 2026-09-22 · **Priority:** medium · **Due:** M9
- **Problem:** E.5 item 9 asks CI to fail when a gate metric's point estimate misses its
  threshold. `fs-features gate --enforce` does exactly that, but CI has no dataset: the 1M draw is
  gitignored and generating one takes about 24 minutes, beyond the python job's 15-minute budget.
  The gate therefore runs as committed `fs-evidence` artefacts, not as a CI check.
- **Acceptance:** a CI job that generates (or restores from a cache) a pinned draw, builds the
  gate cache and runs `fs-features gate --enforce`, with its failure policy decided first — PB-62
  means it would fail today, and D.3 says to record a failing metric rather than tune it away.

### PB-64 · M4 clauses that need the scoring service
- **Source:** M4 milestone review, ADR 0031, 2026-09-22 · **Priority:** high · **Due:** M5
- **Problem:** four M4 acceptance clauses need a service that does not exist until M5: TEST-02's
  fallback rule engine when the ML scorer returns 503; TEST-14's shadow-mode AUC delta and its
  MLflow logging (ADR 0030); FR-02-03's "deployment blocked if ECE > 0.05", which needs a
  promotion step to block; and loading the exported ONNX models, whose parity M4 established.
- **Acceptance:** each clause tested in M5, against the models `fs-features gate` evaluated.

### PB-65 · D-06's anomaly routing threshold is not configured anywhere
- **Source:** M4 milestone review, ADR 0031, 2026-09-22 · **Priority:** medium · **Due:** M6
- **Problem:** `anomaly_score` exists (percentile rank against the training reference, D-06), but
  the routing D-06 specifies — 0.7 in the test profile, a configurable percentile defaulting to
  0.995 in production, sized by D-10's alert budget — is a decision-engine setting, and there is no
  decision engine yet.
- **Acceptance:** the threshold is versioned configuration in M6, with both profiles tested.

### PB-66 · E.5 protocol items M4 did not build
- **Source:** M4 milestone review, re-review, 2026-09-22 · **Priority:** medium · **Due:** M11,
  except the search, which is M5
- **Problem:** the D.3 gate is met or recorded, but E.5's research protocol is wider than the gate
  and these were not built: fairness and harm by country, channel, KYC tier and urban/rural (item
  7); the cost curve and alert-budget analysis (item 8); leave-one-fraud-type-out and performance
  by test month (item 5, C-5); reliability diagrams per channel (item 6). E.4's latency-constrained
  hyperparameter search (D-16) was not run either: the gate model's configuration is fixed.
- **Acceptance:** each item reported with the single-feature baselines PB-46 requires, or
  withdrawn from the paper's claims with a reason. The search waits for M5's latency measurement.

### PB-67 · The gate model trains on 30,000 of the train period's 792,162 rows
- **Source:** M4 gate run, 2026-09-22 · **Priority:** high · **Due:** before the paper's results
- **Problem:** the feature pass costs about 270 rows a second, so the gate cache samples 30,000
  training rows (260 fraud) from a 400,000-row corpus that reaches only the last 128 days of the
  train period. Every M4 model figure is a model trained on that sample, and PB-62's recall
  shortfall may be partly its cost.
- **Acceptance:** a gate run on a larger training sample, reported beside the current one — not as
  a replacement chosen because it passes.

### PB-68 · The frontier and the battery write-up still describe the 6abde44e draw
- **Source:** M4 milestone review, 2026-09-22 · **Priority:** low · **Due:** before M11
- **Problem:** `docs/benchmarks/m4_frontier.md` and `m4_battery.md`'s prose were measured at
  `6abde44e`; the gate, battery and C-6 evidence are at `d8083dbc`, with the E1 encoding.
- **Acceptance:** both re-run at `d8083dbc` through `fs-evidence`, prose restated from the output.

### PB-73 · The M6 latency gate, measured on dedicated hardware with the real scorer
- **Source:** M6 gate, ADR 0059 (owner decision 2026-09-23) · **Priority:** high · **Due:** M10
- **Problem:** "end-to-end p95 decision latency < 50 ms at the largest achievable load" could not
  be shown met or refuted: both measurements (`docs/benchmarks/2026-09-22-M6-decision-latency.json`,
  `2026-09-23-…`) ran on shared hosts with a scorer double, and are recorded as shapes, not gate
  figures. FR-01-01, FR-03-01, FR-03-03, TEST-05 and D-13 wait on it.
- **Acceptance:** `DecisionLatencyBenchmark` (`-Dfs.benchmark=true`) on the dedicated benchmark
  machine recorded in `docs/benchmarks/hardware.md`, with M5's real scorer, at rising rates up to
  the largest the machine sustains without errors; the result file names the machine; p95 < 50 ms
  at that rate. Also NFR-REL-05's "< 1 ms cache hit".

### PB-74 · FR-01-06's 30-second batch, measured on dedicated hardware
- **Source:** M6 final verify, ADR 0059 point 5 (owner decision 2026-09-23) · **Priority:** medium
  · **Due:** M10
- **Problem:** a 1,000-transaction batch measured 30.20 s against the 30 s limit once on
  `dev-laptop-01` at load ~7, and timed out at loads 12-15 while passing at loads 9-13 in paired
  runs: a ~0.2 s, load-sensitive margin is not evidence.
- **Acceptance:** `IngestApiTest.thousandTransactionBatchesAreDecidedWithinThirtySeconds`, or a
  dedicated batch benchmark, on the dedicated machine, with the margin recorded over repeated runs.

### PB-75 · The gitleaks self-test fails on some generated secrets
- **Source:** M5, 2026-09-24, a red gitleaks job on `m5/scoring` (`M5_updates.md` §21, proposed
  as `PB-73`; renumbered by owner decision 2026-09-24, M10_updates.md §1) · **Priority:** medium
  · **Due:** M9 (the security workflows; `tools/` is shared)
- **Problem:** `tools/bin/gitleaks-selftest` plants randomly generated fake secrets and asserts
  gitleaks reports exactly those. Some generated values do not match the `generic-api-key` rule,
  so the guard fails with "NOT DETECTED generic-api-key in <path>", on a different path each run:
  1 failure in 31 local runs at `16035d2` (about 3%), and CI run 35909780245. A flaky pass of a
  guard on the secret scanner is worth as little as a flaky failure costs.
- **Acceptance:** the self-test passes 200 consecutive runs, with planted values the pinned rules
  are guaranteed to match (fixed high-entropy shapes per rule, or each rule's own regex asserted
  against the generated value before planting it), so a failure means the configuration
  regressed.

### PB-77 · A second SMS when the outcome cannot be committed after the provider was called
- **Source:** M6 implementation-2 review m7, and amendment 1 (contract 13.1), 2026-10-17 · **Priority:** medium · **Due:** later than M6
- **Problem:** a crash or eviction after the provider call and before the commit that keeps its result lets the next owner find B with no outcome and no kept result, and send a second SMS, without its link.
- **Acceptance:** one of the two designs on record: a `SENDING` row written before the provider call and read as "outcome unknown, do not send", or the transactional outbox (contract section 9).

### PB-78 · I7e's test did not show the paused partition moving (resolved)
- **Source:** M6 implementation-2 review m3, 2026-10-17 · **Priority:** low · **Due:** M6
- **Problem:** the test never showed the paused partition moved to the second member.
- **Resolution:** resolved in M6's amendment-1 round: `EnvelopeConsumerTest.partitionsMovedWhilePausedKeepEveryoneFlowing` moves it while the old owner runs, with the rebalance made to land inside a 6.4 s pause on every run, and fails if the old owner then resumes the partition it no longer owns. Kept so the id is not reused.

### PB-79 · A provider outage records FAILED and the customer is never told
- **Source:** M6 amendment-1 draft-3 review n9 (contract 13.2), 2026-10-17 · **Priority:** medium · **Due:** later than M6; the FAILED-rate alert with M9
- **Problem:** a provider call that fails records FAILED and the intent is committed past; nothing retries it. Today's behaviour, unchanged by amendment 1.
- **Acceptance:** a retry for failures known not to have reached the provider (a failure to connect), and an alert on the rate of FAILED outcomes.

### PB-80 · Ordering contract I3a: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I3a) · **Priority:** low · **Due:** M10
- **Problem:** replacing `envelope.transaction()` with `ABSENT` in the `NotificationWiring` lambda would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I3a restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-81 · Ordering contract I7: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I7) · **Priority:** low · **Due:** M10
- **Problem:** deleting the consumer's `!first` guard would not fail any test: any draining since a trip leaves the budget just below full at a record's first answer, so the grace re-read happens without it.
- **Acceptance:** a test that fails when the code named above is deleted, and row I7 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-82 · Ordering contract I7d: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I7d) · **Priority:** low · **Due:** M10
- **Problem:** a tripped partition and an F4 backoff are never produced; the row's "is tripped" and F4 clauses rest on review.
- **Acceptance:** a test that fails when the code named above is deleted, and row I7d restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-83 · Ordering contract I7f: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I7f) · **Priority:** low · **Due:** M10
- **Problem:** deleting `seekBack` and the cycle's catch would not fail it: the rejoin resets positions from the committed offsets. It proves only that commits happen at the end of a batch; a commit failure while membership is kept, which `seekBack` is for, is never produced.
- **Acceptance:** a test that fails when the code named above is deleted, and row I7f restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-84 · Ordering contract I10: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I10) · **Priority:** low · **Due:** M10
- **Problem:** the replay republishing the original key is never asserted.
- **Acceptance:** a test that fails when the code named above is deleted, and row I10 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-85 · Ordering contract I11: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I11) · **Priority:** low · **Due:** M10
- **Problem:** stopping at the end offsets taken at the start, committing nothing, and choosing the newest by timestamp would not fail any test if deleted: nothing is written to the DLQ during a read, the replay seeks explicitly, and the copies compared are identical.
- **Acceptance:** a test that fails when the code named above is deleted, and row I11 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-86 · Ordering contract I13: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I13) · **Priority:** low · **Due:** M10
- **Problem:** the "decided again" half of `resolved` (a later decision that is not DECLINE) is never produced; deleting it would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I13 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-87 · Ordering contract I14: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I14) · **Priority:** low · **Due:** M10
- **Problem:** a crash while the partition is tripped is never produced, and deleting the lost path's absence of a commit (`onPartitionsLost`) would not be noticed by any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I14 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-88 · Ordering contract I14b: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I14b) · **Priority:** low · **Due:** M10
- **Problem:** the crash between a dead letter and its commit is never produced.
- **Acceptance:** a test that fails when the code named above is deleted, and row I14b restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-89 · Ordering contract I15: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I15) · **Priority:** low · **Due:** M10
- **Problem:** deleting the CASE that makes `kept` null without a header would not fail it: `kept` is then false, which classifies the same. The bound is I7's.
- **Acceptance:** a test that fails when the code named above is deleted, and row I15 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-90 · Ordering contract I17: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I17) · **Priority:** medium · **Due:** M10
- **Problem:** deleting the consumer's `failedOutage` or `attemptStarts` call would not fail any test, because the consumer tests that mix F1 and F4 use a zero time bound, so "F1 adds no F4 time" is proved for the clock but not for the consumer (implementation-3 R-m1); "on the backoff" (the 2 s bound alone meets the timing assertion), and the next F4 attempt dead-lettering after a failed dead-letter send, which is never produced.
- **Acceptance:** a test that fails when the code named above is deleted, and row I17 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-91 · Ordering contract I18: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I18) · **Priority:** low · **Due:** M10
- **Problem:** the 1-minute failing WARN; deleting it would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I18 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-92 · Ordering contract I19: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I19) · **Priority:** low · **Due:** M10
- **Problem:** deleting the webhook payload's validation, or the check of a replayed `fs-kept-result`, would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I19 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-93 · Ordering contract I20: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I20) · **Priority:** medium · **Due:** M10
- **Problem:** deleting `AfricasTalkingGateway`'s catch of an unreadable 2xx reply would not fail `…providerRepliesThatCannotBeReadAreFailedOnceAndNeverResent`, because the sender's catch gives the same result; only an outage is produced for "whatever class"; the commit-before-write order and the wiring lambda that passes the kept result and keeper are by review.
- **Acceptance:** a test that fails when the code named above is deleted, and row I20 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-94 · Ordering contract I21: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I21) · **Priority:** low · **Due:** M10
- **Problem:** the WARN line; deleting it would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I21 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-95 · Ordering contract I22: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I22) · **Priority:** medium · **Due:** M10
- **Problem:** the drop is made twice, by the reset handler and the head check, so deleting either alone would not fail any test; the kept-result drop, the ERROR line, a failed lookup never read as "no offset", the leader epoch on commits, and the log-end and divergence branches (an unclean election cannot be produced on one broker) would not fail any test if deleted.
- **Acceptance:** a test that fails when the code named above is deleted, and row I22 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-96 · Ordering contract I23: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I23) · **Priority:** low · **Due:** M10
- **Problem:** deleting the code that moves `fs_notify_wait_warnings_total`, `fs_notify_sms_outcome_unknown_total`, `fs_notify_resolved_before_send_total`, `fs_notify_webhook_delivery_running` or `fs_notify_head_failing_seconds`, or the 1 of `fs_notify_consumer_running`, would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I23 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-97 · Ordering contract I24: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I24) · **Priority:** low · **Due:** M10
- **Problem:** the cause chain's cycle guard; deleting it would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I24 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-98 · Ordering contract I26: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I26) · **Priority:** medium · **Due:** M10
- **Problem:** (known partial, owner's decision) the seek in the stop and the commit-only-if-every-seek-succeeded gate, the ERROR line, a statement actually timing out, the vault's, provider's, producer's and commit timeouts, the webhook executor's `Error` rule, the health endpoint staying UP, a reset's lookup failing without ending the thread, the stopping flag, the rule that the revoke listener commits nothing off the poll thread while it runs (with `close()`'s longer wait), and the seek back across several partitions: deleting any of them would not fail any test (implementation-3 R-m2). `…batchesPastTheirTimeBudgetPollAgainWithoutSkipping` passes with the budget deleted and proves only the seek back.
- **Acceptance:** a test that fails when the code named above is deleted, and row I26 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-99 · Ordering contract I27: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I27) · **Priority:** medium · **Due:** M10
- **Problem:** (known partial, owner's decision) the consumer's outage branch for a failed dead-letter send, a real send timing out, and the line's ERROR level: deleting any of them would not fail any test.
- **Acceptance:** a test that fails when the code named above is deleted, and row I27 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-100 · Ordering contract I28: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I28) · **Priority:** low · **Due:** M10
- **Problem:** the command line's `--allow-rejected` and `--reason` parsing.
- **Acceptance:** a test that fails when the code named above is deleted, and row I28 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-101 · Ordering contract I29: a clause no test would catch if its code were deleted
- **Source:** M6 section-8 audit under the deletion standard, 2026-10-17 (`docs/architecture/decision-fact-ordering.md`, row I29) · **Priority:** low · **Due:** M10
- **Problem:** the overflow guards on numbers past `long` or `int`.
- **Acceptance:** a test that fails when the code named above is deleted, and row I29 restated as proved; or the row's claim narrowed to what is proved, by a reviewed contract change.

### PB-102 · The MNO SIM-swap adapter, for when a source exists
- **Source:** threat model R-10, assigned in M7's merge round, 2026-10-17 · **Priority:** low · **Due:** REQUIRES_EXTERNAL_PARTY
- **Problem:** with no SIM-swap signal, D-25 refuses customer self-service for every block, so no customer reaches the verification page in production. The refusal is correct (owner, 2026-09-23); the adapter cannot be built without an MNO data agreement, which only the project owner can make.
- **Acceptance:** once a source exists, an adapter behind ADR 0065's port with a fake in the local stack (D-51), and R-10 closed.

### PB-103 · An operator command and runbook for dead-letter replay
- **Source:** threat model R-11, assigned in M7's merge round, 2026-10-17 · **Priority:** medium · **Due:** M9
- **Problem:** M6's `DeadLetterReplay` replays `<topic>.dlq` (now including `fs.audit.events.dlq`), but an operator has no packaged way to run it (docs/architecture/decision-fact-ordering.md D-c carries packaging to M9).
- **Acceptance:** a packaged command with a runbook entry, exercised once in the stack against a planted dead letter.

### PB-104 · FR-07-01: three things no test would catch if deleted
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (`docs/parallel/M7_updates.md`, FR-07-01; ADR 0070 §1) · **Priority:** high · **Due:** M7
- **Problem:** (1) changing which roles or scopes `ContractPolicy` reads for an operation would not fail `AuthorisationMatrixTest`, because the test takes its expected answers from `ContractPolicy` itself; only four operations are pinned independently (`ContractPolicyTest.accessComesFromTheMatrix`). (2) Deleting `@EnableMethodSecurity` from `SecurityConfiguration` would not fail any test, because the URL layer refuses first. (3) Deleting the last-active-administrator guard in `UserAdministrationService.update` would not fail any test, because no request can reach it: the self-modification guard and the acting-administrator check, both proved, already leave one active administrator.
- **Owner decision (2026-10-17) on (3):** the guard is **kept as defence in depth**. No request can reach it today; a future route that does reach it inherits a guard tested by nothing, so whoever adds such a route adds the test. Removing it would be tidier and wrong. (The API-key half of (1) now has an independent oracle: `AuthorisationMatrixTest.apiKeysReachOnlyTheMachinePathsByTheContractFileAlone`.)
- **Acceptance:** the matrix test reads `authorisation-matrix.yaml` itself for the roles' expected answers; a test invoking a controller method with a wrong-role authentication expects `AccessDeniedException`; a test that reaches the last-active-administrator guard, added with the first route that can.

### PB-105 · FR-07-06: the unlock email and the 15-minute window
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (FR-07-06) · **Priority:** medium · **Due:** M7
- **Problem:** deleting `SmtpStaffMailer`'s send would not fail any test, because the "unlock email sent within 30 seconds" assertion uses `RecordingStaffMailer`; and the 15-minute window is never advanced, so changing it to an hour would not fail any test.
- **Acceptance:** an SMTP test against Mailpit that the unlock email arrives within 30 s; a test that advances past the window.

### PB-106 · FR-07-08: the reset code's delivery
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (FR-07-08) · **Priority:** medium · **Due:** M7
- **Problem:** deleting `SmtpStaffMailer`'s send would not fail "OTP email delivered within 30 seconds", which is measured against `RecordingStaffMailer`. (The 10-minute expiry is now pinned; the "change form" clause is M8's.)
- **Acceptance:** the same SMTP test for the reset code.

### PB-107 · FR-06-01: the Google set-up link, the stored fields and the database backstop
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (FR-06-01) · **Priority:** medium · **Due:** M7
- **Problem:** the welcome email carries no Google OAuth set-up link (`fraudshield/auth/mail.properties`), so that clause is not built; of the stored fields only role, status and phone are asserted, so dropping first name, last name, department or locale from the insert would not fail any test; and with the duplicate pre-check in front, whether the database constraint's path still answers 409 is untested.
- **Acceptance:** the link built and asserted; every field asserted after creation; a concurrent-duplicate test answering 409 from the constraint.

### PB-108 · FR-06-02: the version bump alone, and Google within 5 s of a deactivation
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (FR-06-02) · **Priority:** medium · **Due:** M7
- **Problem:** the deactivation test passes with the token-version bump deleted as long as the refresh revocation remains, because each ends the session it checks; and `GoogleSignInTest.deactivatedAccountIsBlockedFromGoogleSignIn` uses an account created deactivated, so deleting the deactivation's effect on Google sign-in within 5 s of an administrator's action would not fail any test.
- **Acceptance:** a test that fails when the bump alone is deleted; Google sign-in refused within 5 s of an administrator deactivating a linked account.

### PB-109 · FR-06-06: audit types written by real actions, and every write audited
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (FR-06-06) · **Priority:** medium · **Due:** M8B (carried 2026-10-18)
- **Problem:** `AuditSearchTest` writes all 12 types itself. A real action is asserted to emit TRANSACTION_DECISION (`AssembledApplicationTest`), AUTH, USER_ADMIN and API_KEY_LIFECYCLE; AUTO_BLOCK, CUSTOMER_VERIFICATION, ANALYST_DECISION, SENIOR_OVERRIDE and THRESHOLD_CHANGE are emitted by M6 code with no end-to-end assertion, and RULE_CHANGE, MODEL_LIFECYCLE and REGULATORY_REPORT have no emitter yet. "All write operations audited" has no test.
- **Acceptance:** each type asserted from a real action as its emitter lands; a test that every write endpoint records an audit event.

### PB-110 · D-26: unlocks by password reset or change are not audited as unlocks
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (D-26) · **Priority:** medium · **Due:** M7
- **Problem:** D-26 says every lock and unlock is audited. `StaffUserJpaRepository.changePassword` lifts LOCKED, but the reset and change paths write only `PASSWORD_*` events: the behaviour is missing, not merely untested.
- **Acceptance:** an unlock event on both paths, asserted.

### PB-111 · D-32: the daily anchoring trigger
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (D-32) · **Priority:** medium · **Due:** M7
- **Problem:** deleting `@Scheduled` from `AuditAnchorScheduler` would not fail any test, because `schedulerAnchorsThePreviousUtcDay` calls the method directly. Anchoring is off by default, and PB-11 (a dedicated anchoring role) is open.
- **Acceptance:** a context test that the cron task is registered when anchoring is enabled.

### PB-112 · NFR-REL-06: OAuth-only users' recovery during a Google outage
- **Source:** M7 audit of the rows carried to M7, 2026-10-17 (NFR-REL-06) · **Priority:** medium · **Due:** M7
- **Problem:** "OAuth-only users prompted to set password via email OTP" has no test; whether the reset path accepts an account without a password is unproved. (The button text is M8's.)
- **Acceptance:** an OAuth-only account completes a reset and signs in by password while Google is down.

### PB-113 · D-51: Google without real credentials in the local stack
- **Source:** M7 audit of the rows carried to M7, 2026-10-17 (D-51) · **Priority:** low · **Due:** M9
- **Problem:** tests use `FakeGoogle`, an in-process fake, but D-51 also asks for WireMock stubs of Google's JWKS and token endpoints in the local stack, and a sandbox contract suite reported as skipped without credentials. Neither exists.
- **Acceptance:** the stubs in the compose stack (M9 owns compose) and the skipped-when-no-credentials suite.

### PB-114 · NFR-SEC-03: the analyst-endpoint inspector test
- **Source:** M7 audit of the rows carried to M7, 2026-10-17 (NFR-SEC-03) · **Priority:** high · **Due:** M8B (owner decision 2026-10-18)
- **Problem:** the test that every analyst endpoint shows only masked tokens cannot be written: no milestone builds the analyst-facing API (`listAlerts`, `decideAlert` and their neighbours). M8 builds its console against mocks.
- **Acceptance:** the inspector test over every analyst endpoint, built in M8B (`docs/parallel/M8B_updates.md`).

### PB-115 · D-19: verification at 10,000 TPS
- **Source:** M7 traceability audit under the deletion standard, 2026-10-17 (D-19) · **Priority:** medium · **Due:** M10
- **Problem:** D-19 chose HMAC over bcrypt so keys verify at 10,000 TPS; that rate is not measured. (HMAC with pepper, the 2 s cache and pub/sub revocation are now proved.)
- **Acceptance:** M10 measures key verification at the target rate on the dedicated machine.

### PB-116 · Review finding 4: concurrent sign-ins prove nothing about where bcrypt runs
- **Source:** M7 audit of the other claim tables, 2026-10-17 (`docs/reviews/M7/staff-auth.md`, finding 4) · **Priority:** low · **Due:** M7
- **Problem:** `concurrentSignInsAllSucceed` asserts only 200s, so moving bcrypt back inside the transaction would serialise sign-ins and still pass.
- **Acceptance:** a test that fails when the hash runs while a row lock is held.

### PB-117 · ADR 0071: the `validate` default
- **Source:** M7 audit of the other claim tables, 2026-10-17 (ADR 0071) · **Priority:** low · **Due:** M7
- **Problem:** `HybridPersistenceTest.schemaValidation…` would pass if the shipped configuration stopped setting `validate`, because `JpaTestStack` sets it itself.
- **Acceptance:** a test against the shipped configuration that a mapping mismatch stops the application.

### PB-118 · ADR 0071: the key list's N + 1 check compares nothing
- **Source:** M7 audit of the other claim tables, 2026-10-17 (ADR 0071) · **Priority:** low · **Due:** M7
- **Problem:** `QueryCountTest` creates no API keys, so its key-list comparison is 0 statements against 0: an N + 1 in the key list would not fail it.
- **Acceptance:** the test lists keys after creating several.

### PB-119 · Walkthrough claim 4: inactive accounts' 423 and the unknown-email bcrypt burn
- **Source:** M7 audit of the other claim tables, 2026-10-17 (`docs/walkthrough/M7.md`, claim 4) · **Priority:** low · **Due:** M7
- **Problem:** deleting `hasher.burn()` for an unknown email (`AuthenticationService`) would not fail any test, and inactive accounts' 423 is untested.
- **Acceptance:** tests that fail on each deletion, or the claim narrowed.

### PB-120 · Walkthrough claim 5: the measured revocation time
- **Source:** M7 audit of the other claim tables, 2026-10-17 (walkthrough claim 5) · **Priority:** low · **Due:** M7
- **Problem:** the walkthrough said a revoked key stopped working in under 100 ms; the last measurement (`85aff03`) was 103 ms at worst with the announcement. `revocationAnnouncementAloneEvictsAnotherInstancesCachedKey` asserts a 2 s bound for the announced path, but nothing asserts the measured figure. The walkthrough now states the measured value.
- **Acceptance:** a test asserting a tighter bound for the announced revocation, or the figure removed by a reviewed change.

### PB-121 · The analyst API: 48 contract operations no milestone builds
- **Source:** M7 merge round 2, scoped 2026-10-17 at the owner's request (`docs/parallel/M7_updates.md`, "Unowned work: the analyst API") · **Priority:** high · **Due:** M8B (owner decision 2026-10-18: a milestone of its own, alongside M9, on `main` before M10; `docs/parallel/M8B_updates.md`)
- **Problem:** the frozen contract defines 89 operations; 36 are implemented (7 by M6, 29 by M7), 5 are management-port only, and 48 have no implementation and no owner: alerts and decisions (8), investigation (3), dashboards and portfolio (5), campaigns (2), rules (5), thresholds, circuit breakers and dual-control config changes (11), SAR reports (3), models, datasets and retraining (8), admin health and webhook deliveries (2), the deployment environment (1). Waiting on it: M6's `DecisionTransitionService.analystDecidesHold` and `CircuitBreakerPort.countConfirmedFraud`, both with no production caller; NFR-SEC-03's inspector test (PB-114); M8's console, whose analyst and risk screens are placeholders over mocks; FR-04-01 to FR-04-11, FR-05-01 to FR-05-07, FR-06-03 to FR-06-05, D-22, D-29, D-44, D-46, TEST-06, TEST-12, NFR-PERF-05, NFR-PERF-07; the WebSocket alert feed (FR-04-02), also unbuilt. Estimate: 15 to 18 controllers, 15 to 20 services, about 8,000 to 12,000 lines of code and tests, larger than M7.
- **Acceptance:** placed in M8B (owner decision 2026-10-18); each group's operations implemented to the contract, with NFR-SEC-03's inspector test, `decideAlert` calling `analystDecidesHold`, and confirmed fraud reaching `countConfirmedFraud`.

### PB-122 · `audit_event_receipts` grows without bound
- **Source:** review of M7 round 2, finding 5, 2026-10-17 (V72) · **Priority:** medium · **Due:** M9
- **Problem:** the receipts that deduplicate relayed audit events are append-only, never compressed and never purged, though deduplication is needed only while a redelivery is possible: the topic's P7D retention, plus the dead-letter topic's for replays. At the design load that is millions of rows a day kept for ever, and append-only forbids deleting them.
- **Acceptance:** a bounded window (for example time partitions dropped past the redelivery horizon, with a stated exemption from the append-only rule) and an ADR note on why receipts may go before their audit rows.

### PB-123 · FR-07-08's password change form (UI)
- **Source:** review of M7 round 2, finding 7, 2026-10-17 · **Priority:** medium · **Due:** M8
- **Problem:** FR-07-08's "a verified OTP triggers the password change form" is a console clause; the row stays with M7 for its delivery gap (PB-106), so without this row the clause had no owner.
- **Acceptance:** the console shows the change form after a verified code, with a component or end-to-end test.

### PB-124 · NFR-REL-06's "Temporarily unavailable" Google button (UI)
- **Source:** review of M7 round 2, finding 7, 2026-10-17 · **Priority:** medium · **Due:** M8
- **Problem:** NFR-REL-06 says the Google button shows "Temporarily unavailable" while Google is down; the server answers 503 with `Retry-After` (proved), but the console clause had no owner while the row stays with M7 (PB-112).
- **Acceptance:** the console shows the state on a 503 from `/auth/google`, with a component test.

### PB-131 · Only `validate.ts` may import the generated Zod schemas
- **Source:** review of M8's bundle canary (`6afebd4`), 2026-10-18 · **Priority:** medium · **Due:** M8
- **Problem:** `productionBundle.build.test.ts`'s H.4 check looks for one response-only pattern (ShapContribution's `template_key`) in the production JavaScript. `validate.ts` imports the whole generated module dynamically, so a leak through it ships that pattern and is caught; but a future static named import of one schema from `generated/zod.gen` would be tree-shaken down to that schema, and 81 of the 88 response schemas do not contain the canary, so a partial leak would pass.
- **Acceptance:** a source-level test fails if any file under `frontend/src` other than `api/validate.ts` (and the generated directory itself) imports `generated/zod.gen`, and deleting that rule's check makes a planted import pass; the H.4 comment names every response schema that carries the canary, not only Explanation.

### PB-132 · D-45's plain-language explanation templates
- **Source:** M8 screen 2, the investigation drawer, 2026-10-18 · **Priority:** high · **Due:** M8
- **Problem:** D-45 resolves FR-04-05's "plain-English description" with deterministic, localised, unit-tested templates per feature, never an LLM. The scorer sends `template_key` as `feature.<name>.increases_risk` or `.decreases_risk` (`ml/src/fraudshield_ml/serving/scorer.py`), and alerts carry a `summary_template_key` (`summary.*`). No catalogue exists, so the drawer shows each feature's name and raw value instead. The wording is what an analyst acts on, so it needs domain review, not only translation.
- **Acceptance:** a catalogue with every key the scorer can emit (44 features, both directions) and every summary key, in en, fr, rw and sw; a test that fails when the scorer can emit a key the catalogue lacks; the drawer's SHAP rows and summary use it.
- **Also covers (owner, 2026-10-19):** the plain-language names of `BehaviourProfile.comparison`'s attributes (M8 screen 4, FR-04-07), which the drawer shows as raw keys such as `local_hour`. The same gap, so the same owner: whoever writes the feature templates names these too, once M8B has said which attributes the server can send (`M8B_updates.md`, item 7).

### PB-133 · The console's shared React and MUI floor is too heavy for 3G at 1 Mbps
- **Source:** M8, the first 3G timing measurements, 2026-10-19 (owner decision) · **Priority:** high · **Due:** M9 or M10, as a measured performance item; not M8
- **Problem:** a first visit to sign-in downloads 204.8 KB of JavaScript, gzipped, over MOB-PERF-06's 200 KB, and paints first at 2.88 s at 1 Mbps (200 ms, CPU slowed 4x) against MOB-PERF-01's 2.5 s; at 1.6 Mbps it paints at 2.15 s. With the first-visit preloads there is no chain of round trips left: the transfer runs at about 107 KB/s, 86% of the link, so the time is bandwidth. About 50 KB less would bring 1 Mbps under 2.5 s. Most of what remains is the entry (120 KB: React, MUI's styling engine and core, the router, TanStack Query, i18next) and MUI's shared chunk (29 KB); the sign-in route itself is about 37 KB. Shaving a screen's bytes to hide a floor problem is the wrong move (owner).
- **Acceptance:** an architecture decision (an ADR) on the floor, with each option's measured saving on a production build; the timing job's 1 Mbps first paint under 2.5 s and the first-visit JavaScript under 200 KB, or the requirement's condition settled by its owner; the 0.7 KB of unused server-sent-events support in the generated client is considered with it.

### PB-76 · Training rows near the corpus's start see truncated history; test rows do not
- **Source:** PB-67's laptop cache, 2026-09-22 · **Priority:** high · **Due:** before the paper's
  results, and before PB-67 is concluded
- **Measured:** the laptop learning-curve cache (340,000-row corpus) and the declared gate cache
  (400,000 rows) carry the same 20,000 validation rows, and four features differ between them:
  `amount_to_max_90d_ratio`, `amount_zscore_90d`, `counterparty_confirmed_fraud_90d` and
  `distance_from_home_centroid_km`. Test and calibration rows are identical. The only difference
  between the caches is how far back the corpus reaches.
- **Why it matters:** windowed features see only rows inside the corpus. A training row within 90
  days of the corpus's start has its 90-day history truncated. Test rows, which sit at the newest
  end, always have full history. So every M4 model — the declared gate run included — trained on
  some rows whose features were computed differently from the rows it is scored on. M3-1 found the
  same mechanism for `velocity_ratio_1h_vs_30d`; this is its general form. It is a candidate
  contributor to the recall shortfall at 0.60, not a demonstrated one.
- **It also confounds PB-67.** Sizes drawn from one pool share one truncation profile, so the
  laptop's 30K, 60K and 120K compare with each other. The declared gate's 30K (400,000-row corpus)
  and the Codespace's 240K (520,000) have different ones, so the curve's last step changes volume,
  time span and truncation together.
- **Acceptance:** measure the share of training rows whose windows reach before the corpus start,
  and either build training features from a corpus that reaches at least 90 days before the
  earliest training row, or report every model figure with that share stated. The owner decides.
