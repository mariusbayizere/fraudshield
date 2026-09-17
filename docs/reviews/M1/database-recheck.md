# Principal re-check: M1 database (`m1/database`)

- **Branch/commit:** `m1/database` @ `6cfe658fe88052297e68798c3e43fb7a21a96b36` (fixes 5b05a47, b5a601c, bc77d5f, 6546b73; docs 6cfe658)
- **Previous review:** `docs/reviews/M1/database-review.md` @ bd222fe, CHANGES_REQUIRED (0 BLOCKER, 3 MAJOR). Author response: `docs/reviews/M1/database-response.md`
- **Reviewer:** independent Principal Reviewer (build prompt Part I.2)
- **Scope:** MAJOR-1..3 fixes, the new tests, the author's mutation table, the CI and tooling fixes. Per the owner's pace direction, I dug deep only for BLOCKER/MAJOR. New MINOR/NIT items are one-line backlog entries.
- **Verdict:** **APPROVED_WITH_MINORS** (0 BLOCKER, 0 MAJOR; all 3 previous MAJORs resolved)

## Checks run

| Command / action | Result |
|---|---|
| `git checkout --detach 6cfe658` | HEAD at 6cfe658 |
| `FRAUDSHIELD_TEST_POSTGRES_URL=… ./mvnw -B -ntp -o verify -pl persistence -am` (PG16 + TimescaleDB 2.30.0) | BUILD SUCCESS, **47 tests, 0 failures** (DatabaseSecurityTest 20, SyntheticDataStatusTest 4, SyntheticDataFlagTest 5, DemoSeedGuardTest 13, DemoDataSeederTest 5) |
| Scratch DB `recheck_db`: bootstrap.sql as postgres, V1..V11 as fs_migrator via psql | Applied cleanly |
| Catalogue test query run by hand, plus blind-spot queries (see MAJOR-1) | Test query empty. The only FKs it cannot see are global → `users(id)` (already PB-14) |
| MAJOR-1 original repro (fs_app, tenant A): `api_keys.replaced_by` and `refresh_tokens.replaced_by` → bank B ids; random uuid; `UPDATE … SET replaced_by = <bank B key>`; same-tenant rotation | Cross-tenant INSERT and UPDATE rejected (`*_replaced_by_institution_id_fkey`). Bank B id and random uuid give the **same** error, so the id oracle is gone. Same-tenant rotation works |
| MAJOR-2 as fs_app: backdated `event_at` (2001); explicit `recorded_at` of now−10y, now+10y, `clock_timestamp()`, NULL; COPY with forged `recorded_at`; COPY without it; several rows in one transaction and one statement | Backdated `event_at` accepted and stored at the transaction time. Every explicit `recorded_at` ≠ `now()` → 42501; NULL → not-null. COPY with forged value → 42501; COPY without → accepted. Several rows in one transaction share `recorded_at` and chain correctly |
| fs_app direct `INSERT` into `_timescaledb_internal._hyper_4_1_chunk` with forged seq/hashes | `permission denied` |
| Races (two psql sessions, partition 20): (A) no head row yet, later tx B creates the head and holds it uncommitted, earlier tx A inserts; (B) the same but B rolls back; (C) earlier REPEATABLE READ tx vs a committed later insert | (A) A blocks on `ON CONFLICT`, then **40001**; B commits. (B) A succeeds. (C) RR tx → 40001 from `ON CONFLICT` visibility check. Afterwards the head is `last_seq=3` = stored rows, and `verify_audit_chain(20)` = 3 OK, so a failed insert does not advance the head |
| Retention simulation (scratch DB `recheck_ret`): `set_chunk_time_interval('audit_events','1 second')`; a long transaction started first and inserted 1.5 s later; 6 transactions ~1.1 s apart, 2 rows each, with `event_at` of 2001 and now−5y mixed in; then `drop_chunks(older_than => <recorded_at of the 2001 row>)` | 13 rows across 7 chunks, chain OK. Drop removed 3 chunks = **seqs 1–5 exactly (a prefix)**. The 2001-`event_at` row (seq 6) survived. Anchored verification from seq 5 (hash snapshotted before the drop) → 8 rows OK. From genesis → "sequence gap" at 1, as expected without an anchor |
| `compress_chunk` on all remaining chunks, then anchored verification | 8 rows OK (`audit_row_hash` including `recorded_at` stays stable through compression) |
| `institutions` grants, RLS force flag, `deployment_has_synthetic_data` definition | Only fs_migrator can write `synthetic`. RLS is not forced, so the SECURITY DEFINER owner sees all rows. `search_path = fraudshield, pg_temp` |
| Probe (temporary test, deleted): `DatabaseToolApplication` with profile `prod` and `--spring.main.lazy-initialization=true` against a seeded DB | **Starts** (guard bypassed; see backlog item 1) |
| Mutations (below) | 3 mutations, all caught |
| `uv run --offline --package fraudshield-tools fs-seed-demo --help` | Resolves and prints usage. `tools/pyproject.toml` defines the `fs-seed-demo` script in `fraudshield-tools` |
| Cleanup | `recheck_db`, `recheck_ret` and the `fs_test_*` databases created by my own Maven runs (named in my logs) were dropped. `git status` clean |

## Status of previous MAJOR findings

| # | Status | Evidence |
|---|---|---|
| 1 | **RESOLVED** | Both `replaced_by` FKs are composite `(replaced_by, institution_id)` with `UNIQUE (id, institution_id)`, and `institution_id` is NOT NULL, so MATCH SIMPLE always enforces them. The repro now fails for INSERT and UPDATE, and the existence oracle is gone.<br>**Catalogue test soundness:**<br>- *Hypertables:* FKs sit on the hypertable in `fraudshield`, so the test sees them; there are no FKs from chunk schemas into `fraudshield`.<br>- *Views:* cannot have FKs.<br>- *`institution_id` mapped to another column:* caught, because the test pairs `conkey`/`confkey` by position and requires `institution_id → institution_id`.<br>- *Tenant columns under other names:* none exist. The only RLS table without `institution_id` is `institutions`.<br>- *Known blind spot:* FKs from tables **without** `institution_id` into tenant tables: `training_datasets.uploaded_by` and `retraining_jobs.requested_by` → `users(id)`. Already backlog PB-14.<br>M1 mutation caught |
| 2 | **RESOLVED** | **Argument verified.** The head row is serialised by `SELECT … FOR UPDATE`. Each accepted row has `recorded_at = now() ≥ head.last_recorded_at`, and the head is then set to that value. So `recorded_at` never decreases along seq within a partition.<br>Chunks are disjoint half-open ranges, so equal timestamps land in one chunk. `drop_chunks(older_than)` removes every chunk whose end ≤ the cutoff, so per partition it removes exactly the rows below a boundary, which is a prefix. Confirmed empirically with 1-second chunks.<br>**Holes checked:**<br>- *Head creation race:* the later session waits on `ON CONFLICT`, and the next plpgsql statement takes a fresh snapshot under READ COMMITTED, so it sees the committed head → 40001. Under REPEATABLE READ it gets 40001 directly.<br>- *Multiple inserts, multi-row statements, COPY:* same `now()`; forged values rejected.<br>- *Compression:* the author's test inserts into a compressed chunk; I verified after compressing.<br>- *`now()` under SECURITY DEFINER:* it is `transaction_timestamp()`, the same value as the column default.<br>- *Direct chunk insert:* denied.<br>- *40001 path:* raised before the head `UPDATE`, and the statement rollback also undoes the `ON CONFLICT` head insert. Head verified consistent.<br>- *A writer creating a mid-chain gap:* not possible. A writer cannot choose `recorded_at`, delete rows, or write chunks. A transaction whose own chunk was already dropped would need to be older than the 7-year retention, and even then only a prefix is affected.<br>- *Clock going backwards (document only):* after a clock step back of X, every writer on a partition written in that window gets 40001 for up to X. That is an audit-write availability stall, not an integrity break. Monotonicity still holds because the check uses the stored head.<br>M2 and my M6 mutations caught |
| 3 | **RESOLVED** (bypass via lazy initialisation → backlog item 1) | `institutions.synthetic` is written by the seeder. Only fs_migrator can change it. The SECURITY DEFINER function works for all three application roles without a tenant.<br>`SyntheticDataStatus` is a regular singleton that depends on `JdbcTemplate`. Boot's database-initialisation dependency detection orders `JdbcOperations` after Flyway, so the check runs after migration and during context refresh, before any runner or web server starts.<br>**`SyntheticDataStatusTest` is meaningful:** it starts the real `DatabaseToolApplication` against a seeded and a clean DB and covers:<br>- `prod` and no profile fail;<br>- `demo` starts with the flag on;<br>- `prod` on a clean DB has the flag off;<br>- the marker is visible to every role.<br>**Owner requirements:**<br>- *Demo accounts only under dev/demo:* holds (per process and now across processes).<br>- *Startup fails with any other profile:* holds with default settings.<br>- *Banner whenever demo data is present:* `syntheticData()` is true whenever the marker exists. The endpoint is wired in M2.<br>Excluding the auto-configuration takes an explicit operator exclude (out of scope). |

## New BLOCKER/MAJOR findings

None.

## Backlog candidates (MINOR/NIT)

1. MINOR: `spring.main.lazy-initialization=true` (or `SPRING_MAIN_LAZYINITIALIZATION=true`) stops `SyntheticDataStatus` from being created, so a `prod` process starts against a seeded DB (reproduced). Register a `LazyInitializationExcludeFilter.forBeanTypes(SyntheticDataStatus.class)` or run the check in a `SmartInitializingSingleton`. Fix before M2 wires the API.
2. MINOR: `@ConditionalOnBean(JdbcTemplate.class)` silently skips the guard in an app without an auto-configured `JdbcTemplate` (two DataSources, a custom `JdbcOperations`, R2DBC). Condition on `DataSource` and fail loudly instead.
3. MINOR: non-Spring services that connect to the database (Python ML or tools in later milestones) are not covered by the synthetic-data startup check. Note this in ADR 0019 when such a service gets DB access.
4. MINOR: REPEATABLE READ writers get 40001 whenever another transaction wrote to the same partition after their snapshot (the `ON CONFLICT` visibility check), even when time order is fine. Document "audit inserts must retry on 40001; prefer READ COMMITTED" next to PB-10.
5. NIT: document in ADR 0017 that a backwards wall-clock step stalls audit writes (40001) until the clock catches up.
6. NIT: `SyntheticDataStatusTest` "no profile" case asserts only the root exception type, not the "synthetic demo data" message.
7. NIT: `TestDatabase` never drops the `fs_test_*` databases it creates (60+ accumulate on a shared server).
8. NIT: V8 was edited in place. Any local stack volume migrated at bd222fe needs `make down -v` (Flyway checksum mismatch). Mention it in the PR description.

## Mutation table (re-run by the reviewer)

| # | Mutation | Test run | Result |
|---|---|---|---|
| M1 (author's) | `V2`: `api_keys` FK back to `FOREIGN KEY (replaced_by) REFERENCES api_keys (id)` | `-Dtest=DatabaseSecurityTest` | **Caught:** `everyForeignKeyBetweenTenantTablesIncludesTheInstitution` → `Expecting empty but was: ["fraudshield.api_keys.api_keys_replaced_by_fkey"]` |
| M6 (reviewer's, same run as M1) | `V8`: the `recorded_at IS DISTINCT FROM now()` check replaced by `IF false` | `-Dtest=DatabaseSecurityTest` | **Caught:** `retentionFollowsTheChainOrderNotTheWritersEventTime` → `expected: "42501"` |
| M2 (author's) | `V8`: chain-order check `IF NEW.recorded_at < head.last_recorded_at` replaced by `IF false` | `-Dtest=DatabaseSecurityTest` | **Caught:** `retentionFollowsTheChainOrderNotTheWritersEventTime:512` (the 40001 assertion) |

I ran M1 and M6 together because they fail different tests. I restored the files with `git checkout -- .` after each run, and the worktree is clean. The author's M3–M5 were not re-run. Their test design is consistent with the claims (M4/M5: the seeded-DB `prod` start would succeed without the marker or the auto-configuration).

## CI and tooling fixes

- **stack:** `uv run --package fraudshield-tools fs-seed-demo` is plausible. The workspace root `fraudshield-workspace` is not a package, and `tools/pyproject.toml` defines the script (it resolved locally). The failure annotation step prints only the already-filtered log tail.
- **Compression test:** pausing `policy_compression` for `audit_events` with `alter_job(…, scheduled => false)` as fs_migrator (the job owner), then `compress_chunk(…, if_not_compressed => true)`, removes the concurrent-compression race. It passes locally.
- **`verify-branch-commits`:** excluding `requires-docker` without Docker or `FRAUDSHIELD_TEST_POSTGRES_URL` matches `make test-java`. Quoting `[ERROR]` lines is harmless.

## Merge decision

**Fast-forward of `m1/database` to `main` is allowed once CI for 6cfe658 is green.** No BLOCKER or MAJOR remains. Backlog items 1 and 2 should be scheduled before M2 wires `SyntheticDataStatus` into the API service.

---

## Author's addendum: backlog and gate status (not part of the reviewer's report)

- **Backlog:** items 1–2 → PB-15, PB-16 (due before M2 wires the API); 3 → PB-17; 4–5 → PB-18;
  6–7 → PB-19; 8 → PB-20.
- **Per-commit verification** (`tools/bin/verify-branch-commits 0781e6f m1/database`, with
  `FRAUDSHIELD_TEST_POSTGRES_URL` so every backend commit ran all database tests): all 10 commits
  pass.
- **CI for 6cfe658** (public REST API):
  - `ci` run 35220629314: success.
  - `stack` run 35220629403: success. This includes `make seed-demo` and `check-seeded-stack.sh`.
  - `devcontainer` run 35220629326: **failure**. Post-create `REQUIRE_DOCKER=1 make ci` failed in the
    stack smoke test's MLflow artifact upload (HTTP 500 from MLflow). `make ci` runs the Java suite
    (Testcontainers database tests) before the stack test, so those tests had passed inside the
    container. The MLflow failure resembles the intermittent MLflow worker deaths recorded in M0,
    but job logs need authentication, so the cause is not confirmed.
- **Gate:** the merge condition "CI green" is **not met**. `main` is not updated until the
  devcontainer workflow passes on a re-run (`workflow_dispatch`, which needs an authenticated owner),
  or the MLflow failure is diagnosed and fixed.

## Author's addendum 2: MLflow root cause, PB-15/PB-16, and the owner's merge decision

- **MLflow HTTP 500 (owner direction: a bug, not a flake).** Diagnostics added in 720a5ee and d96638f
  (redacted resource snapshot after every smoke test, uploaded and annotated) showed the MLflow
  container at its 1 GiB limit in both the stack and devcontainer jobs:
  - `memory.peak` equal to the limit, 4,015 and 4,500 `memory.max` events, 832–861 MiB of anonymous
    memory;
  - Docker memory (16 GiB), CPUs (4) and free disk (77–82 GB) the same in both environments;
  - only ~340 MiB held by the listed server and uvicorn processes.

  MLflow 3.16.0 starts a job runner and one Huey consumer process per server-side job type when the
  backend store is a database (`MLFLOW_SERVER_ENABLE_JOB_EXECUTION` defaults to true;
  `mlflow/server/__init__.py`, `mlflow/server/jobs/utils.py`). A process the kernel kills at the
  limit leaves the container running, with no OOMKilled flag, and the upload fails with HTTP 500.
  754ade1 disables job execution, which FraudShield does not use, and keeps the limit. The smoke test
  now fails when any container records an `oom_kill` or MLflow's anonymous memory exceeds 60% of its
  limit.

  | 754ade1 | stack job | devcontainer job |
  |---|---|---|
  | MLflow anonymous memory | 393 MiB (38%) | 393 MiB (38%) |
  | `memory.peak` | 528 MiB | 527 MiB |
  | `memory.max` events | 0 | 0 |
  | `oom_kill` | 0 | 0 |
  | MLflow processes | server + uvicorn | server + uvicorn |

- **PB-15/PB-16** (owner direction: fix before merging), 02bb017. `SyntheticDataGuard` is a
  `spring.factories` listener with its own JDBC connection. It runs before any bean is created, is
  independent of lazy initialisation and of the beans present, and fails closed.
  `SyntheticDataGuardTest` covers prod + `spring.main.lazy-initialization=true` and an application
  without `JdbcTemplate` against a seeded database; three mutations of the guard are caught.
- **CI for 754ade1 on `m1/database`:** ci 35224607902, stack 35224607797 and devcontainer 35224608001
  all success, with the stack and devcontainer jobs executed.
- **Owner decision (2026-09-17):** "Evidence accepted. The MLflow root cause is proven by measurement
  and guarded by a deterministic budget check (60% memory, OOM-kill check) that passed on 754ade1 in
  all three workflows. The 3-consecutive-dispatch requirement is waived for this merge." `main` was
  fast-forwarded from 0781e6f to 754ade1 over SSH. Two extra devcontainer dispatches on `main` follow
  as evidence, not as a merge condition, once `gh` is authenticated in the session.
