# Principal review: M1 database (`m1/database`)

- **Branch/commit:** `m1/database` @ `bd222feeb72c10995ee5f8d600e9d23e3ac5b5f4`, compared with merge base `0781e6f` (5 commits)
- **Reviewer:** independent Principal Reviewer (build prompt Part I.2)
- **Scope:** bootstrap.sql, V1–V11, `DatabaseSecurityTest`/`TestDatabase`, demo seeding (Java guard/seeder/flag, `seed_demo.py`, Makefile, Compose init script, `check-seeded-stack.sh`, `stack.yml`, `gitleaks-worktree`), `GET /environment` contract and banner module, ADR 0017/0018/0019/0020, threat model. Per the owner's pace direction, I dug deep only for BLOCKER/MAJOR issues. MINOR and NIT items are backlog one-liners.
- **Verdict:** **CHANGES_REQUIRED** (0 BLOCKER, 3 MAJOR)

## Checks run

| Command / action | Result |
|---|---|
| `git checkout --detach bd222fe` | HEAD at bd222fe |
| `FRAUDSHIELD_TEST_POSTGRES_URL=… ./mvnw -B -ntp verify -pl persistence -am` (local PG16 + TimescaleDB 2.30.0) | BUILD SUCCESS. 39 tests, 0 failures: DatabaseSecurityTest 18, DemoDataSeederTest 5, DemoSeedGuardTest 13, SyntheticDataFlagTest 3 |
| Scratch DB `review_dbsec`: bootstrap.sql as postgres, then V1..V11 as fs_migrator via psql | Applied cleanly |
| fs_app, bank A tenant: INSERT `audit_events` with forged `seq`/`prev_hash`/`row_hash` (INSERT and COPY) | Trigger overwrote them. Stored seq 1,2 with correct links. `verify_audit_chain(1)` → 2 rows OK |
| fs_app, bank A: INSERT and COPY of a bank B row into `audit_events` / `transactions` (including a new future chunk) | Rejected by `tenant_insert_guard` |
| fs_app: `SELECT` on the `transactions` hypertable | permission denied. `v_transactions` in the wrong tenant → 0 rows |
| ACLs on `_timescaledb_internal` chunks, materialised hypertables, `_partial_view`/`_direct_view` | Only fs_migrator can read. fs_app has INSERT only on chunks |
| fs_app INSERT into a compressed audit chunk, then compress and verify | Chain intact, `verify_audit_chain` OK |
| Owner: `DELETE FROM audit_events` on a compressed chunk; `TRUNCATE audit_events` / `TRUNCATE transactions` | Rejected (append-only) |
| Owner: `TRUNCATE _timescaledb_internal._hyper_4_2_chunk` | **Succeeds** (see backlog item) |
| fs_app: cross-tenant `replaced_by` on `api_keys` / `refresh_tokens` | **Succeeds** (MAJOR-1) |
| fs_app: backdated `event_at`, then the retention job | **Chain gap** (MAJOR-2) |
| fs_compliance_ro: `SELECT FROM v_auto_block_status` | permission denied for `customer_verifications` (backlog) |
| `SELECT jsonb_build_array(NULL::jsonb) = jsonb_build_array('null'::jsonb)` | `t` (backlog) |
| Code read of the demo guard, relaxed binding and profiles: env var `FRAUDSHIELD_DEMOSEED_ENABLED`, value spellings (`TRUE`/`on`/`yes`), no profile, `spring.profiles.default`, `dev,prod`, profile groups | The guard is conservative in every case: any value that `@ConditionalOnProperty(havingValue="true")` accepts is also `true` to the guard, and default profiles give empty `getActiveProfiles()`, which the guard refuses. No bypass found |
| Code read of the CI log filter (`grep -v -e fsk_dev_ -e @example.com`, bash `-eo pipefail`) | Password and key lines are filtered from the log and the uploaded artifact |
| Mutation testing | **Not run.** The isolated worktree was removed mid-review, after which every Bash call was refused |

## Findings (BLOCKER/MAJOR)

| # | Severity | Location | Finding and reproduction | Required fix |
|---|---|---|---|---|
| 1 | MAJOR | `V2__identity_and_access.sql`: `refresh_tokens.replaced_by uuid REFERENCES refresh_tokens (id)` and `api_keys.replaced_by uuid REFERENCES api_keys (id)` | ADR 0017 says "Cross-tenant references are impossible by construction: child tables reference `(id, institution_id)` … with composite foreign keys." These two self-references use a single column. FK checks ignore RLS, so a bank A row can point at a bank B row, and the FK error is an existence oracle for other tenants' ids. **Repro** (fs_app, `SET LOCAL fraudshield.institution_id='1111…'`): `INSERT INTO api_keys (…, created_by, replaced_by) VALUES ('1111…', …, '<userA>', 'bbbbbbbb-1111-…-0001' /* bank B key */) RETURNING replaced_by` → `INSERT 0 1`. The same works for `refresh_tokens` with a bank B token id. A random uuid → `violates foreign key constraint "refresh_tokens_replaced_by_fkey"` (oracle). The existing `referencesCannotCrossInstitutions` covers only `alert_decisions → alert_queue_entries`. | Make both composite: `FOREIGN KEY (replaced_by, institution_id) REFERENCES api_keys (id, institution_id)`, and add `refresh_tokens_id_institution` plus the composite FK. Add a catalog test: every FK from an RLS or tenant-guarded table to another tenant table must include `institution_id`. List allowed exceptions explicitly (`institutions`, global tables). |
| 2 | MAJOR | `V8__audit_log.sql` (`audit_events.event_at` supplied by the application; `audit_events_chain` sets only `seq`/`prev_hash`/`recorded_at`/`row_hash`) with `V10__…sql` (`add_retention_policy('audit_events', drop_after => '7 years')`) | The chain is ordered by `seq`, but chunking and retention use `event_at`, which the writer chooses freely. A row with an old `event_at` sits in the middle of the chain but is dropped by normal retention, leaving a permanent "missing row: sequence gap". That is indistinguishable from tampering, and every later row in the partition fails from-genesis verification until the next anchor. This is the denial-of-verification case and breaks D-32's "gap = tampering" meaning. **Repro:** as fs_app, tenant A, partition 7: insert event_at `now()`, then `'2001-01-01'`, then `now()` → `verify_audit_chain(7)` = 3 rows OK. As owner, `CALL run_job(<audit_events retention job>)` → remaining seqs are 1 and 3, and `verify_audit_chain(7)` → `checked_rows=1, first_bad_seq=2, 'missing row: sequence gap'`. | In the chain trigger, reject `event_at` outside a narrow window around `now()` (for example `now() - interval '1 day' .. now() + interval '5 minutes'`; pick the bound in ADR 0017). Alternatively, partition and retain by the trigger-assigned `recorded_at`. Add a test that a backdated `event_at` is rejected, and a test that retention can only remove a chain prefix. |
| 3 | MAJOR | `SyntheticDataFlag.java`, `DemoSeedGuard.java`, ADR 0019 §4 ("a deployment with demo data always shows the banner") | The guard and the banner flag only look at the **current process's** profiles and properties. `make seed-demo` seeds through a separate one-shot tool process (`SPRING_PROFILES_ACTIVE=demo`). Nothing in the database records that it holds synthetic demo accounts. An API started later against the same database with `prod` and seeding disabled starts normally, returns `synthetic_data=false` and shows no banner, although the five demo accounts and demo API key exist. That contradicts the owner requirements "demo accounts exist only when dev/demo is active" and "banner whenever demo seeding is active" (D-21). Code-level proof: `isSyntheticData` = `isEnabled(env) \|\| activeProfiles ∩ {dev,demo}`, and `check()` returns early when the property is false. No test covers the "database was seeded, service runs as prod" case. | Persist a database marker when seeding (for example `institutions.synthetic boolean NOT NULL DEFAULT false`, set true for `demo-bank`, or a one-row deployment flag). Then: (a) the synthetic-data flag is true when the marker exists; (b) startup under any profile other than dev/demo fails when a synthetic institution exists. Add both to ADR 0019, with a test (a Java test against the migrated database is enough now; the API wires it in M2). |

## Backlog candidates (MINOR/NIT)

- MINOR: ADR 0017 says the append-only triggers "also stop the owner" for TRUNCATE, but the owner can `TRUNCATE _timescaledb_internal._hyper_*_chunk` (verified), use `drop_chunks`, or `DISABLE TRIGGER`. Reword the ADR. For `audit_events` the chain still detects it.
- MINOR: `v_auto_block_status` is granted to fs_compliance_ro but fails for that role (no SELECT on `customer_verifications`/`customer_notifications`). Grant the needed columns or drop the grant, and add a test.
- MINOR: `20-fraudshield-roles.sh` passes the role passwords as `psql --set=…` command-line arguments (visible in `ps`), contrary to its comment "on stdin". `check-seeded-stack.sh` passes `PGPASSWORD` via `docker compose exec -e` argv.
- MINOR: the `customer_verification_responses` expiry trigger trusts the caller-supplied `responded_at`. Compare with `now()`.
- MINOR: commit/undo exclusivity (`forbid_commit_and_undo`) holds only under READ COMMITTED. Under REPEATABLE READ the post-lock EXISTS uses the old snapshot. Document the required isolation level, or merge commit and undo into one outcome table keyed on `decision_id`.
- MINOR: `fraudshield.institution_id` can be set by every role, so fs_app_readonly and fs_compliance_ro are not tenant-bound in the database. State this trust assumption in ADR 0017 and the threat model.
- MINOR: fs_app can insert `audit_anchors` for future `(anchor_date, writer_partition)` and block the signing job. The signature check would expose a forged anchor, but the job is still blocked.
- NIT: `audit_row_hash` hashes SQL NULL and JSON `null` in `before_value`/`after_value` identically (verified), and omits `recorded_at`, although ADR 0017 says "every column".
- NIT: `verify_audit_chain(p, s, NULL)` skips the first `prev_hash` check (`<>` with NULL). Use `IS DISTINCT FROM`.
- NIT: fs_app_readonly can read `customer_verification_responses.ip_address` (personal data). Consider a column grant.
- NIT: global single-column FKs (`training_datasets.uploaded_by`, `retraining_jobs.requested_by` → `users(id)`) let a writer probe whether a user id exists in another tenant.

## Owner requirements checked

| Requirement | Status |
|---|---|
| Demo accounts only under dev/demo; startup fails otherwise | Holds per process (DemoSeedGuardTest; tried the relaxed env var, value spellings, empty/default/mixed profiles, no bypass). Not enforced across processes (MAJOR-3) |
| No committed secrets; random passwords and key made locally, printed once, `.demo-credentials` git-ignored with mode 600 | Holds (`seed_demo.py` uses `O_EXCL` 0600, refuses a group/world-readable file, passes secrets via the child environment; `.gitignore` entry; CI log filter) |
| Banner "SYNTHETIC DATA — NOT FOR PRODUCTION" | Contract and parser present; flag logic incomplete (MAJOR-3) |
| Test that seeding refuses a production profile | Present (`prod`, `production`, `staging`, `default`, `dev,prod`, `demo,staging`, no profile) |
| Two RISK_OFFICER accounts | Present and tested |
| D-30 event-sourced split, D-31 tables/columns, D-32 12 event types/chain/anchors/compression/retention, D-49 ADR 0018 feature list | Present; D-32 gap issue is MAJOR-2 |
| gitleaks-worktree | Scans tracked files plus untracked non-ignored files, so force-added ignored files are still included; history scan unchanged |

## Mutation table

Not run. The review worktree was removed during the review, and every Bash call after that was refused, so no mutations could be made. Every repro above was run on the scratch database before that happened.

## Housekeeping

The scratch database **`review_dbsec`** on 127.0.0.1:55432 could **not be dropped**, because Bash calls were refused once the worktree disappeared. Please run `DROP DATABASE review_dbsec;` as postgres. No other database was touched, and no worktree edits were made before it was removed.
