> Recorded by the author on 2026-09-23. Both MAJOR findings and the unrated test gaps were fixed;
> `docs/parallel/M6_updates.md` ("Fourth independent review") maps each to its fix.

# M6 fourth review: fix round 4 and the docs commits after it

Reviewer: independent Principal Reviewer (fresh). Date: 2026-09-23.
Trees: `m6/decision` @ `c94e165` (tree of `2bc1b72` = `8a83da8c`), `m6/featurestore-fallback` @ `35a3752`, `origin/m9/infra` @ `3f8028d` (read only).

## Verdict: **CHANGES_REQUIRED**: 0 BLOCKER, 2 MAJOR

The Python fallback fix (`5f74db3`) is correct. I reproduced it on a real PostgreSQL 16 server and found no new stall mode. Finding 1 is a new defect from this round, in the `Makefile` change of `2bc1b72`: it makes `make up`, and so the CI `stack.yml` job, fail after a migration that succeeded. Finding 2 is an M9 acceptance criterion that says a contract test checks something it does not check.

## Checks run

| Command | Result |
|---|---|
| `uv sync --all-packages --locked` (fallback worktree) | ok; pg8000 **1.31.5** |
| `pytest ml/tests/featurestore/test_postgres_fallback.py` | 14 passed |
| Read the pg8000 1.31.5 source (`core.py`, `dbapi.py`, `converters.py`) | Server errors: `handle_messages` reads up to ReadyForQuery before it raises `DatabaseError(dict)`, with SQLSTATE in `args[0]["C"]`, so the protocol stays in sync after any server ERROR. A FATAL is always followed by EOF, so it comes out as `InterfaceError("network error")`, not as a DatabaseError carrying 57P0x. A timeout on the first `sock.read` in `_read` sits outside the `try` and comes out as a raw `TimeoutError`, which the `OSError` branch catches. Every converter returns the raw string when it cannot parse a value (infinity, year > 9999, BC). No converter exception that could desynchronise the stream was found. |
| Real server (`pii-vault`, postgres:16.15, project `fs-review4`, port 5433), `PostgresFallback` with `connector()` at the production defaults (100 ms statement timeout, 200 ms socket timeout), script `scratchpad/pgrepro.py` | 1: read ok. 2: `pg_sleep(0.3)` gives 57014 at 0.102 s; session kept, no cool-down. 3: the next read runs on the **same backend pid**, `statement_timeout=100ms`, `default_transaction_read_only=on` (the SETs survived the rollback). 4 and 5: 22012 fails its own read, next read ok. 6: `pg_terminate_backend` gives `InterfaceError`, connection dropped, cool-down started. 7: fails at once, "failed recently". 8: after the cool-down, a new backend. 9: `docker pause` mid-query gives `TimeoutError` at 0.201 s, cool-down. 10: reconnects. 11 and 12: 25006 (a write refused) is statement-level, session kept. |
| Real server, a rollback that raises (`docker pause` inside `rollback()`), script `scratchpad/pgrepro2.py` | Failure path: `FallbackUnavailableError` at 0.255 s, connection dropped, cool-down set. Success path: the result `[42]` is returned, connection dropped, cool-down set. `_end` handles a rollback that raises on both paths. |
| `docker compose -p fs-review4 --profile core up -d --wait pii-vault pii-vault-migrate`, then `docker compose … wait pii-vault-migrate` straight away | `container "…" exited with status code 0`, rc 0 (Flyway was still running) |
| The same `wait` once `pii-vault-migrate` had exited (0) | **`no containers for project "fs-review4"`, rc 1** |
| `up -d --wait`, `sleep 12`, then the Makefile recipe line verbatim under `bash -euo pipefail` | **`pii-vault-migrate failed`, rc 1**, although the container shows `ExitCode 0` (it ran 15:37:41.96 → 15:37:47.04, about 5 s) |
| Docker Compose version | v5.5.1 |
| Teardown | `down -v`: volume and network removed, no `fs-review4` containers left, `.env` deleted, `git status` clean in both worktrees (apart from this file) |

## Mutations (`postgres.py` at `35a3752`, `test_postgres_fallback.py`; each restored with `git checkout`)

| Mutation | Result |
|---|---|
| M1 `_end` without try/except (a rollback that raises escapes) | **survived** (14/14) |
| M2 `_end`'s except calls `_drop()` only, no cool-down | **survived** |
| M3 `InterfaceError` not classified as connection-level | **survived**. On a real server this is masked: the rollback on the dead socket raises, and `_end` starts the cool-down. |
| M4 remove `"08"` | killed |
| M5 remove `"57P01"` | killed |
| M6 remove `"53"` | **survived** |
| M7 statement-level failure drops the session | killed |
| M8 `OSError`/`TimeoutError` not connection-level | killed |

M1, M2, M3 and M6 leave the rollback-raises path and the InterfaceError and 53 branches unpinned by unit tests. I checked the behaviour on a real server (above) and it is correct today. These are test gaps, not defects; I list them for the author and they are not findings.

## Findings

### 1. MAJOR: `make up` (and CI `stack.yml`) fails whenever `pii-vault-migrate` has already exited, which is the normal case

- **Where:** `Makefile:36-37` (`2bc1b72`):
  ```make
  $(COMPOSE) --profile core wait pii-vault-migrate | grep -q 'exited with status code 0$$' || \
    { echo "pii-vault-migrate failed: …" >&2; exit 1; }
  ```
- **Scenario:** With Compose v5.5.1, `docker compose wait` only considers containers that are still running. If the one-shot has already exited, even with status 0, it prints `no containers for project "…"` and exits 1. The grep then fails and `make up` stops with "pii-vault-migrate failed".

  In the full `core` profile, `up --wait` returns only once every service is healthy. MLflow has `interval: 10s` and `start_period: 30s`, depends on `timescaledb` being healthy, and waits for `object-store-init` to complete. Kafka and TimescaleDB (with initdb on a fresh volume) take longer still. `pii-vault-migrate` starts as soon as `pii-vault` is healthy (5 s interval) and ran for about 5 s here. So it has normally exited before `up --wait` returns, and `make up` fails on a healthy, correctly migrated stack.

  `.github/workflows/stack.yml:90-91` runs `make up`, which is the M0 gate evidence ("make up healthy"). The check only passes when the one-shot happens to still be running. The author's test (`M6_updates.md:349`, "tested with a correct and a wrong migrator password on a throwaway project") started only the two vault services, where `up --wait` returns while Flyway is still running. The third review recorded exactly that ("`--wait` returned while Flyway was still running"). So the passing test was an artefact of the subset.
- **Verified:** reproduced (Compose v5.5.1, project `fs-review4`). An immediate `wait` gave rc 0. `wait` after the exit gave `no containers`, rc 1. The recipe line, run verbatim under the Makefile's `-euo pipefail` 12 s after `up --wait`, gave rc 1 with the container's `ExitCode 0`.
- **Required action:** Read the exit status without depending on timing. One option: `docker compose ps -a --format '{{.ExitCode}} {{.State}}' pii-vault-migrate`, polled until the state is `exited`, or `docker inspect -f '{{.State.ExitCode}}' $(docker compose ps -aq pii-vault-migrate)` after the one-shot has stopped. Another: give a long-running service `depends_on: pii-vault-migrate: {condition: service_completed_successfully}`, the pattern `mlflow` already uses for `object-store-init`, so that `up --wait` itself fails when the migration fails. Test it on the full `core` profile, or with a delay longer than the migration, in both the success and the failure case. Correct `M6_updates.md:349` and M9_updates §8.1 ("`make up` fails if that job fails") to match.

### 2. MAJOR: M9 acceptance 8.2.2 says `KmsClientContract` checks transient-versus-permanent classification, but it does not

- **Where:** `origin/m9/infra:docs/parallel/M9_updates.md` §8.2, criterion 2 (`3f8028d`): "It passes `KmsClientContract` … an unknown key refused; a wrapping that does not verify is `VaultException.permanent`, **an unreachable service or a key it does not have is not**." The contract is `backend/notify/src/test/java/io/github/mariusbayizere/fraudshield/notify/vault/KmsClientContract.java:41-79`.
- **Scenario:** The contract has four tests. The only `permanent()` assertion is `isTrue()` for a tampered wrapping (line 71). `anUnknownKeyIsRefused` (line 75) only asserts that some `VaultException` is thrown. No test covers an unreachable service. §8 says "M6 does not re-check them", so M9's gate relies on the contract.

  Suppose M9's AWS binding maps `NotFoundException`/`DisabledException` or `ThrottlingException`/network errors to `VaultException.permanent`. It passes the contract and satisfies the criterion as written. `EnvelopeConsumer` then dead-letters (`permanent_vault_failure`) every customer block-SMS that hits a KMS throttle or blip. That is the failure class of D2 ("a key rotation would dead-letter customers' block SMS"), and it moves from M6's `KmsKeyProvider`, which is correct, to the unguarded binding. `KmsClient`'s Javadoc (lines 16-20) states the rule, but nothing enforces it for a binding.
- **Verified:** read. The only `permanent()` assertion in the contract is line 71. `InMemoryKms.java:75,79` throws non-permanent exceptions for an unreachable service and an unknown key, but the contract never asserts this, so changing either to `permanent` would still pass `InMemoryKmsTest`.
- **Required action:** Either add contract tests: `generateDataKey`/`decrypt` for an unknown key id is `permanent() == false`, plus an abstract hook such as `unreachableClient()` with a non-permanent failure. Or reword §8.2.2 so that M9 must show these classifications with its own tests, rather than asserting that the contract covers them.

## Checked with no finding

- `_connection_level` against pg8000 1.31.5 as installed. `_CONNECTION_STATES` matches ADR 0062 point 5. `57014` is statement-level. The lock, the in-lock re-check and `_failed_until` are correct on the open-fails, work-fails (both classes), rollback-fails and success paths. A statement-level failure cannot leave the session broken without a cool-down: every server ERROR is read through to ReadyForQuery and then rolled back, and every other failure of the socket surfaces as `InterfaceError` or `OSError`, or makes the rollback raise, and all of these start the cool-down.
- `VaultContactsTest` J1 pin and the `VaultException`/`EnvelopeConsumer` comments agree with `KmsKeyProvider.unwrap` and `PassphraseKeyProvider`.
- ADR 0062 point 5 (rewritten) matches the code and the reproduction.
- ADR 0059 point 5, the FR-01-06 row (IN_PROGRESS, PB-74), the FR-01-01, FR-03-01 and FR-03-03 rows and the PB-74 carry block are consistent with each other and with the recorded 30.20 s measurement.
- The frozen tree `8a83da8c` is the tree of `2bc1b72`, and `35a3752` contains `2bc1b72`.
- The fix-round record's round-4 row says "none, if the fourth review below comes back clean". With finding 1, round 4 also introduced a defect, so that row needs updating.
- M9 §8.1.1 (V67 grants to `fs_scorer` and fails without it) and §8.2.3/8.2.4 (`kms` is the default; `master-keys` with `kms` is refused; `index-key-id` is required with `kms`; `configured` only under dev/demo/test) match the code.
