> Recorded by the author on 2026-09-23. The one MAJOR finding and the observations were fixed;
> `docs/parallel/M6_updates.md` ("Third independent review") maps each to its fix.

# FraudShield M6: independent review of the last fix round and the owner-decision commit

Reviewer: independent Principal Reviewer (third review), 2026-09-23.
Trees: `m6/decision` @ `0747d6c` (worktree `/home/marius/fraudshield-m6-review3`) and
`m6/featurestore-fallback` @ `a513165` (worktree `/home/marius/fraudshield-m6-fallback-review3`).
Scope: `52481fc..ac6e4a6` (D2), `c7b4ae7` (D1) and `3155415` on the fallback branch, and `0747d6c`.

## Verdict: CHANGES_REQUIRED (0 BLOCKER, 1 MAJOR)

The one MAJOR finding was introduced by the D1 fix (`c7b4ae7`). D2, the owner-decision commit
(ADR 0059, ADR 0060's numbering, ADR 0065's FR-03-05 deviation, the HdrHistogram election, the
budget table) and the compose change have no BLOCKER or MAJOR finding. The below-threshold
observations are listed at the end without a severity.

## Checks run (command → result)

| Command | Result |
|---|---|
| `uv sync --all-packages --locked` (both worktrees) | OK |
| `uv run pytest ml/tests/featurestore/test_postgres_fallback.py ml/tests/serving/test_entry_points.py` (fallback) | 20 passed (the coverage gate fails only because the run is narrow) |
| `rm -rf .mypy_cache && uv run mypy postgres.py server.py`; `ruff check` (fallback) | clean |
| `./mvnw -pl notify -am test -Dtest=KmsKeyProviderTest,InMemoryKmsTest,EnvelopeConsumerTest,VaultContactsTest,AccountTokensTest` (with mutation J1 applied) | 27 tests, 0 failures |
| `uv run fs-compose-budget` | core 4096/4096 MiB, 0 errors (at the limit, as documented) |
| `uv run fs-licences` | HdrHistogram passes. All 144 violations are `npm:` "Unknown" because this worktree has no `node_modules`, as M6_updates says |
| `docker compose -p fs-review3 --profile core config -q` | OK |
| `compose -p fs-review3 up -d --wait pii-vault pii-vault-migrate`, fresh volume | exit 0. The init script ran ("vault roles ready"). Flyway applied V1–V3 as `fs_vault_migrator` and exited 0. `bootstrap.sql` was skipped by naming, correctly. Note: `--wait` returned while Flyway was still running |
| Same, with a wrong `FS_VAULT_MIGRATOR_DB_PASSWORD` | **`up --wait` exit 0**, while `pii-vault-migrate` exited 1 (auth failed) |
| Same, on a volume first created by the pre-change compose file (`accf442:docker-compose.yml`) | **`up --wait` exit 0**. The init script did not run, and Flyway exited 1 with "password authentication failed for user fs_vault_migrator". This case is documented in M6_updates.md:352–353 (see observations) |
| Real PostgreSQL 16 (the `pii-vault` container, port 5433) + `PostgresFallback`/`connector` at production defaults (100 ms statement timeout, 200 ms socket timeout) | healthy cold read in 71 ms. One slow statement (57014), then healthy reads **refused 100 times for 5.09 s** (finding 1) |
| `docker compose -p fs-review3 --profile core down -v` (after each run) | containers, volume and network removed |
| `git status` in both worktrees at the end | clean except this file. `.env` (from `make env`) removed |

## Mutations (mutation → result)

| # | Mutation | Result |
|---|---|---|
| P1 | `postgres.py:158`: do not set `_failed_until` (no cool-down) | **killed** (the blackhole test) |
| P2 | `postgres.py:142`: `self._lock.acquire()` with no timeout (D1's "lock wait bounded by the statement timeout" removed) | **survived**: 9/9 pass |
| P3 | `postgres.py:145–146`: remove the cool-down re-check inside the lock | **survived** |
| P4 | `postgres.py:45`: `DEFAULT_SOCKET_TIMEOUT_S = 2.0` (the 2 s behaviour D1 fixed) | **survived**: the connector test pins only an explicit `timeout_s=1.5` |
| J1 | `PassphraseKeyProvider.java:95`: unknown key id back to `VaultException.permanent(...)` (D2 reverted for the dev/demo provider) | **survived**: `VaultContactsTest:122–125` checks only the message. Only `KmsKeyProviderTest:47` pins D2, and only for the KMS provider |

Every mutation was restored with `git checkout --`.

## Findings

### 1. MAJOR: one failed statement turns off the database fallback for every account for 5 s, and one heavy account can keep it off

- **Where:** `ml/src/fraudshield_ml/featurestore/postgres.py:156–158` (fallback branch @ `a513165`, introduced by `c7b4ae7`). Any `Exception` inside `_query` sets `self._failed_until = self._clock() + self._cool_down_s`, and `postgres.py:140–141` then refuses every read, for any account or device, until it expires. Also involved:
  - `serving/server.py:74–78, 96–97`: a failed read aborts UNAVAILABLE before `writer.offer`, so nothing is written back to Redis.
  - `V67__feature_store_fallback.sql:91–95`: `feature_fallback_account` scans the account's whole history, with no time bound.
  - `ml/tests/featurestore/test_db_fallback.py:274–276`: the acceptance test runs with `statement_timeout_ms=10_000` and no socket timeout.
- **Failure scenario:**
  1. The database is healthy. One account with a long history misses Redis (expired keys, or a Redis restart, which is the case the fallback exists for). Or one statement meets a checkpoint or autovacuum stall.
  2. Its statement passes the 100 ms statement timeout, and PostgreSQL cancels it (SQLSTATE 57014).
  3. The reader treats this like a dead database. It drops the connection and fails every Redis-missing read in that worker at once for 5 s: every new account, every expired account, every new device (`store.py:649–651` asks the fallback for every device Redis has not seen). Each of those payments goes to UNAVAILABLE and rule-based scoring.
  4. The heavy account's read failed, so its state was never written back to Redis. Its next transaction misses again and restarts the 5 s cool-down. An agent or merchant account that transacts every few seconds keeps the fallback off for everyone, for as long as it keeps transacting.
  5. With Redis cold, UNAVAILABLE can pass 50 % of calls in the API breaker's 5 s window (`GrpcScorer.java:56–62`: at least 10 calls, 50 % failure rate). The breaker then opens, and payments that would have hit Redis also go to the rules.
- **The docs claim otherwise.** ADR 0062 point 5 (`docs/adr/0062-durable-account-profiles.md:61–66`) says "While the database is down, a read that misses Redis makes the scorer answer UNAVAILABLE and that one payment is decided by `fallback-rules-2`". Here the database is not down, and it is not one payment. Before `c7b4ae7`, a statement timeout failed only that one read.
- **Why the tests missed it:** the production defaults were never run against real data. The acceptance test uses a 10 s statement timeout, which also makes the lock wait 10 s, and a connection with no socket timeout. The only test at 100 ms is the `SHOW statement_timeout`/`pg_sleep` session check.
- **How verified:**
  - Read the code.
  - Reproduced with the unit-test doubles (`scratchpad/repro_cooldown.py`): one timed-out account, then 99 refusals of a healthy account, and the first healthy read succeeds after 5.01 s.
  - Reproduced on a real PostgreSQL 16 through `connector()` at the production defaults (`scratchpad/repro_real.py`). A `feature_fallback_device_first_seen` that sleeps 150 ms for one device raised `DatabaseError 57014`. After it, a healthy device's reads were refused 100 times, and the first success came 5.09 s later.
- **Required action:**
  - Start the cool-down only on connection-level failures: connect errors, socket timeouts or `OSError`, `InterfaceError`, and SQLSTATE classes 08, 57P01–57P03 and 53. A statement that is cancelled (57014), a query error, or `_one`'s "no row" should fail only that read, and keep the connection when the session is still usable.
  - Add a test: one statement-timeout failure must not refuse a following healthy read.
  - Pin the lock-wait bound (P2), the in-lock re-check (P3) and the 200 ms default socket timeout (P4).
  - Run the acceptance path, or a reduced version of it, at the production defaults once. Otherwise state in ADR 0062 that 100 ms has not been shown to hold for `feature_fallback_account` on long histories. Consider bounding its `earlier` scan, or taking `first_seen` from `account_profiles` alone.
  - Correct ADR 0062 point 5's "that one payment" claim.

## Questions from the brief, answered

- **Cool-down and lock: wedging, starvation, thread-safety, clock.**
  - The lock is always released (`finally` at `postgres.py:162–163`).
  - `_failed_until` is one float, read without the lock and written under it. That is benign in CPython, and the re-check under the lock covers the race.
  - The clock is `time.monotonic`.
  - A healthy database is used again after the cool-down: the reproduction shows the first read after 5 s succeeds.
  - Starvation happens through finding 1, not through the lock. Reads that wait more than 100 ms fail as "busy" without a cool-down, which is the intended way to fail fast.
- **Do the 200 ms socket timeout and 100 ms statement timeout make healthy reads fail?**
  - A cold connect plus a query took 71 ms on an idle local container, so a healthy small read passes.
  - The acceptance test did not configure the production values (10 s, and no socket timeout). Large histories are unmeasured, and their failure is amplified by finding 1.
- **D2: an infinite retry for a key that will never be configured?**
  - Yes. The partition's notifications stall, with a capped back-off and a WARN on each retry, until the key is configured.
  - This is the documented and intended trade in ADR 0069 (lines 104–113: "a retired key must therefore stay configured"), so it is not a finding.
  - Rows that do not verify stay permanent (`VaultContacts:139`, `AccountTokens:171`, the GCM failures of both providers), which is right.
- **Compose.**
  - The image exists and is the backend's Flyway version.
  - The environment variable names match the init script and Flyway (`FLYWAY_SCHEMAS=vault` matches the schema `bootstrap.sql` creates for the migrator).
  - Passwords reach `psql` as `--set` variables and are not echoed. The container `ps` exposure is the known PB-9.
  - The init script is idempotent (`IF NOT EXISTS`, `ALTER ROLE`).
  - The core budget is exactly 4096/4096.
  - `make env` appends the three new variables to an existing `.env`.
- **Do the docs claim more than the evidence?**
  - ADR 0062 point 5 does (finding 1).
  - ADR 0059 and ADR 0065's deviation text, the ADR 0060 numbering and the M6_updates status rows match the code and the evidence. FR-01-01, FR-03-01 and FR-03-03 stay IN_PROGRESS. No ADR `0059` or lower collides on any remote branch.

## Observations below the MAJOR threshold (not rated; for the owner's judgement)

- `make up` (`--wait`) reports success when `pii-vault-migrate` fails. Without a healthcheck, `--wait` treats a one-shot as ready once it is running, and ignores its exit code. On every existing developer volume (created before `0747d6c`), the init script does not run and the migration fails silently, although M6_updates.md:352–353 documents the manual `exec` step. `make smoke` does not check `vault.flyway_schema_history`.
- The docs are stale after D2:
  - `VaultException.java:11` and `:39–40` still say a permanent failure includes "a key this deployment lacks".
  - The `EnvelopeConsumer.java:201` comment says the same.
  - These contradict ADR 0069 and invite a well-meaning revert, and J1 shows no test would catch that revert for the passphrase provider.
