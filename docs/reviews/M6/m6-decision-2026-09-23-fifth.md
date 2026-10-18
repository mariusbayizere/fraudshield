> Recorded by the author on 2026-09-23. Verdict APPROVED: the review loop ended here. The four
> observations below the bar were not acted on (see `docs/parallel/M6_updates.md`).

# M6 fifth independent review: fix round 5

Reviewer: fresh Principal Reviewer, 2026-09-23. Trees: `m6/decision` @ `9f68ec7` (`/home/marius/fraudshield-m6-review5`), `m6/featurestore-fallback` @ `db250d8` (`/home/marius/fraudshield-m6-fallback-review5`), `origin/m9/infra` `6a7af4f` (read with `git show` only).

## Verdict: **APPROVED**. 0 BLOCKER, 0 MAJOR

I found no BLOCKER and no MAJOR finding in fix round 5. The round did not introduce a defect I could find or reproduce. Four observations below the bar are listed at the end for the record. None blocks.

## Checks run (command → result)

Docker Compose v5.5.1 and Engine 29.1.3 locally. Everything ran under project `fs-review5`. I started only `pii-vault`, `pii-vault-migrate` and (for the timing case) `kafka`. Each ran with `docker compose -p fs-review5 --profile core up -d --wait --wait-timeout 300 <services>` followed by `await-oneshot.sh pii-vault-migrate -p fs-review5 --profile core`.

| # | Scenario | Result |
|---|---|---|
| 1 | Fresh volume, the two vault services. The script starts while the migration is `running 0` | `up` rc 0. The script polled and returned **0** once it saw `exited 0` |
| 2 | 20 s later, the migration long exited (`exited 0`), then a second `up` on the existing volume | Compose restarted the one-shot (`running 0`), Flyway said "up to date", the script returned **0** |
| 3 | **Full-stack timing**: `down -v`, then `up --wait` with pii-vault, pii-vault-migrate and kafka. Kafka became healthy about 20 s after the migration exited, which is the case round 4 got wrong | `up --wait` rc 0. At return time `ps` showed `pii-vault-migrate exited 0 (Exited 5 seconds ago)`. The script returned **0** |
| 4 | Third `up` on that stack, then `stop` + `up` (a restart of the whole project) | script **0** both times |
| 5 | Failing migration (mutation: a `V4__bad.sql` of non-SQL added to `db/vault`). Checked right after `up` and 25 s later, with the two vault services and again with kafka | `up --wait` rc **0** (confirms `--wait` ignores the one-shot's exit). The script returned **1** with "failed (1)" both immediately and 25 s later |
| 6 | Never ran: no containers in the project | script **1**, "has no container: it did not run" |
| 7 | Container stuck in `created` (`compose create`), `ONESHOT_TIMEOUT_SECONDS=5` | script **1** after about 4 s, "did not finish in time (state: created 0)". It does not hang past the deadline |
| 8 | Two containers for the service (a leftover `docker compose run pii-vault-migrate` one-off, without `--rm`) | script **1** (fails closed, see observation O1) |
| 9 | `sh -n` and `dash -n` on the script. `git ls-files -s` mode | syntax OK under dash (the `/bin/sh` of Ubuntu and CI). Committed as `100755` |
| 10 | `./mvnw -B -ntp -pl notify -am test -Djacoco.skip=true -Dtest='InMemoryKmsTest,KmsKeyProviderTest'` | 5 + 6 tests pass |
| 11 | `git for-each-ref` over all local and remote refs: `git grep 'extends KmsClientContract'` | only `InMemoryKmsTest` (on m6/decision and the fallback branch). Nothing else has to implement `unreachableClient()`, so no compile breakage |
| 12 | Fallback tree: `uv sync --all-packages --locked`. `pytest ml/tests/featurestore/test_postgres_fallback.py` | 15 passed (the coverage-gate message comes from the narrow run and is expected) |
| 13 | `make traceability` (decision tree) and `fs-traceability render` (fallback tree) | no diff in either tree: the matrix is current |
| 14 | `git diff 9f68ec7 db250d8` on `Makefile`, `await-oneshot.sh`, `backend/notify`, compose, `stack.yml`. `git show --remerge-diff db250d8` | the merge carries round 5 unchanged. The only Makefile difference is M5's `fs-exit-criteria`. The merge resolution is clean |
| 15 | Read: `stack.yml` (it runs `make env`, then `make up`, then smoke, then `down -v`; the Makefile and `infrastructure/docker/**` are in `STACK_INPUTS`, so the job will run), ADR 0069's permanence and rotation rule, `KmsClient`, `KmsKeyProvider`, `VaultException`, `InMemoryKms`, `M6_updates.md` round-5 sections, `M9_updates.md` 8.1 and 8.2.2 at `6a7af4f` | consistent (see below) |

**What I could not run.** I did not run the full `core` profile. Host ports 5432 and 6379 are held by other processes, so under the owner's rule I report that clash and did not edit compose. I could not run CI's compose version: it is whatever `ubuntu-24.04` ships, a v2.x, not v5.5.1. `ps --format` Go templates and the `.State`/`.ExitCode` fields have existed since well before any v2 that image ships. If they were missing, the script's `2>/dev/null || true` turns the error into an empty status. That fails closed ("did not run", exit 1). It never reports a false success. Case 3 reproduces the timing CI will see: the migration had exited before `up --wait` returned. The first run of `stack.yml` on the pushed head is the authoritative check.

## Mutations tried (mutation → killed or survived)

| Mutation | Result |
|---|---|
| J-A `InMemoryKms`: unknown key → `VaultException.permanent` | **killed** (`anUnknownKeyIsRefusedButNotPermanently`) |
| J-B `InMemoryKms`: unreachable → `VaultException.permanent` | **killed** by two tests, `InMemoryKmsTest.anUnreachableServiceIsNeverPermanent` and one in `KmsKeyProviderTest`. This matches the M6_updates claim of "fails two tests" |
| P-A `postgres.py` `_end`: swallow a failed rollback (`pass`) | **killed** |
| P-B drop `"53"` from `_CONNECTION_STATES` | **killed** |
| P-C drop the `InterfaceError` branch | **killed** |
| P-D failed rollback → `_drop()` without starting the cool-down | **killed** |
| S-A a failing migration (non-SQL `V4__bad.sql`), end to end | **detected**: the script returned 1 while `up --wait` returned 0 (checks 5a and 5b) |

I restored every mutation (`git checkout --` / `rm`). Both worktrees show `git status` clean. I deleted the `.env` I created. `fs-review5` is down with `-v`: 0 containers, 0 volumes.

## Answers to the brief's questions

- **Full `core` profile and CI.** The script reads `ps -a` state, so the migration having exited before `up --wait` returns is handled (case 3). The Makefile passes `--profile core` and no `-p`. The compose file's `name: fraudshield` gives the script the same project as `up`, both locally and in CI.
- **Robustness of the `case` match.** Command substitution strips the trailing newline. `restarting` (unreachable anyway with `restart: "no"`), `created`, `paused` and `dead` loop until the deadline and then fail. Case 7 shows the wait is bounded. A missing `ExitCode` or a template error gives an empty status, which fails. More than one container also fails, which is fail-closed but misleading (O1).
- **Can it hang or pass a failed migration?** It cannot hang: the deadline is 300 s by default, and CI's job limit is 25 min. It cannot pass a failed migration: only an exact single-line `exited 0` exits 0. A second `make up` restarts the one-shot, so a stale `exited 0` from an earlier run is not what gets read (case 2 shows `running 0` right after `up`).
- **`make up` twice, and after `make down`.** Cases 2 and 4 cover both. Flyway is idempotent on the existing volume. `down` keeps volumes, and the fresh container then reports "up to date".
- **The contract hook.** `unreachableClient()` makes sense for a real binding: the same adapter aimed at a closed port or blackholed endpoint. Nothing else extends the contract. The permanence rules in `KmsClient`, `VaultException`, `KmsKeyProvider` and ADR 0069 all agree: only a wrapping or context that fails to verify is permanent.
- **Do the docs overclaim?** The four tested cases in `M6_updates.md` match what I reproduced. The fix-round table is accurate. `M9_updates.md` 8.2.2 says the contract cannot provoke throttling and puts that on the binding's own tests, which is correct. The claim that the notify test jar is published holds (`backend/notify/pom.xml:129`).

## Findings

**None at BLOCKER or MAJOR.**

### Observations below the bar (not findings; no action required to approve)

- **O1 (minor): a second container for the service gives a false, misleading failure.** At `infrastructure/docker/scripts/await-oneshot.sh:17-21`, `ps -a` for the service also lists leftover `docker compose run` one-offs. The output then spans several lines (`exited 0\nexited 0`). It does not match `"exited 0"` but does match `exited\ *`, so `make up` fails with a garbled `failed (0\nexited 0)` until `make down` removes the one-off. I reproduced this (case 8). It fails closed, and nothing in the repository runs `compose run pii-vault-migrate`. A developer-only nuisance. If touched: add `--filter`-free handling, e.g. `head -n1` on a `{{.Name}}`-sorted list, or reject multi-line output with a clear message.
- **O2 (minor, a neighbour, diagnostics only):** `infrastructure/docker/scripts/diagnose-stack.sh:25` whitelists only `object-store-init` as an acceptable exited container. On any failed CI stack run it will count a healthy `pii-vault-migrate exited 0` as a "problem" and annotate it. That is noise in failure diagnostics only; it has no effect on pass or fail.
- **O3:** the script has no automated test. Its failure path (exit ≠ 0) is shown only by manual runs, here and in round 5. CI exercises the success path only.
- **O4:** I did not observe the CI compose version (see "What I could not run"). The first `stack.yml` run on the pushed head is the evidence to record before tagging.
