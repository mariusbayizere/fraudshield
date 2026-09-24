> Recorded by the author on 2026-09-23. The one MAJOR finding (documentation) was fixed;
> `docs/parallel/M6_updates.md` ("Sixth independent review") maps it to its fix.

# M6 review 6: `e7a5be5` (vault migration moved out of `up --wait`), at `c5b0cbe`

**Verdict: CHANGES_REQUIRED.** There is one MAJOR finding, and it is in the documentation only.
The code change is correct. I reproduced the original failure locally, so its cause is no longer
just inferred. The new `make up` path held in every case I ran. No other one-shot in
`docker-compose.yml` has the race. The remaining problem is the M9 carry that M6 points to. It
still describes the design that `e7a5be5` removed, including the Compose behaviour this fix
disproves.

## Checks run (command → result)

| # | Check | Result |
|---|---|---|
| 1 | `git show e7a5be5`, `git show c5b0cbe` | As described: migrate moved to `profiles: [full]`, `make up` runs `--profile core run --rm pii-vault-migrate` after `up -d --wait`, `await-oneshot.sh` deleted, `hardware.md` back at 3,968 MiB |
| 2 | Actions API `runs?head_sha=` for 0747d6c / 2bc1b72 / 9f68ec7 | stack #302, #303, #308 failed. Jobs 107222187781 and 107263630618 fail at step 4 "make up". Annotations: `pii-vault-migrate exited exit=0` (Flyway "Successfully applied 3 migrations") and "exit code 2" |
| 3 | Same for e7a5be5 / c5b0cbe | stack #313 and #316 green, with the real job **executed**: steps 4 make up, 5 smoke, 8 seed-demo all `success`. ci #311 and #314 green, devcontainer #314 and #317 green. The #313 resources annotation shows 8 running containers plus exited `object-store-init` (9) and no lingering migrate container, as `run --rm` should leave it |
| 4 | **Reproduced the original failure.** 0747d6c's `pii-vault` and `pii-vault-migrate` copied verbatim (host ports removed), plus a stand-in for mlflow's start gate: `slowdb`, healthy after N s, and `waiter`, which depends on it `service_healthy`. Command: `-p fs-review6 --profile core up -d --wait` | **Fails** with `container fs-review6-pii-vault-migrate-1 exited (0)`, exit 1 (make reports 2) on Compose **v5.5.1** (N=60), **v2.29.7** (N=30) and **v2.33.1** (N=60). It passed on v2.39.4 (N=60) and v5.5.1 (N=30) only because Flyway was still running when `--wait` looked (`Up 3 seconds` / `Up 11 seconds` in `ps`). This confirms a timing race |
| 5 | Same file plus one service depending on migrate with `service_completed_successfully` | **Passes** on v5.5.1 and v2.29.7 with migrate `Exited (0)`. The protection the author relies on (mlflow → object-store-init) works as claimed |
| 6 | New layout (migrate `full`-only). `core up --wait`, then `--profile core run --rm pii-vault-migrate`, run twice | v5.5.1 and v2.29.7: up 0, run 0 ("Successfully applied 3"). Second pass: up 0, run 0 ("up to date"). No migrate container is left in `ps -a`. `run` works on a service outside the active profile |
| 7 | `--profile full down` (volumes kept), then up and run again | 0 / 0 ("up to date") on an existing vault volume |
| 8 | Wrong migrator password (vault volume initialised with a different `FS_VAULT_MIGRATOR_DB_PASSWORD`) | up 0, run **1** (`password authentication failed`). The failure propagates, so `make up` fails under `-euo pipefail` |
| 9 | New layout, `--profile full up -d --wait` | **Fails** (`pii-vault-migrate exited (0)`, exit 1). The race is back under `full` (see inventory) |
| 10 | New layout plus a `core` service with `depends_on: pii-vault-migrate` | `config` fails on v5.5.1 and v2.29.7 with `depends on undefined service "pii-vault-migrate"`. A core service can no longer declare that dependency |
| 11 | `git grep` for `--profile full`, `--wait`, `compose … run` on m6/decision and `origin/{main,m5/scoring,m6/featurestore-fallback,m7/staff-auth,m8/frontend,m9/infra}` | On m6/decision, `full` is used only by `down`, `ps` and `config -q`. `origin/m9/infra` `.github/workflows/security.yml:132` runs `docker compose --profile full up -d --wait fraudshield-api` (dormant: no `fraudshield-api` service yet) |
| 12 | Cleanup | Project `fs-review6` torn down with `down -v` after every run. 0 containers, 0 volumes, 0 networks left. No `.env` created. `git status` clean apart from this untracked file |

The full `core` profile with host ports was not started (5432 and 6379 are taken). The mlflow
start gate was simulated as described in check 4.

## What I could and could not reproduce

- **Reproduced:** the `0747d6c` failure. `up --wait` fails on a one-shot that has already exited,
  even with exit 0, unless a dependent uses `service_completed_successfully`. This holds on
  v2.29.7, v2.33.1 and the installed v5.5.1. The author's inferred cause is right. The trigger in
  the real core stack is that Compose's start phase does not return until `mlflow` starts, and
  `mlflow` waits for `timescaledb` to be healthy and `object-store-init` to finish. Flyway usually
  exits well before that, so in CI the failure was close to deterministic (3 of 3), not a coin flip.
  Local subsets without such a gate let `--wait` look while Flyway was still running. That is why
  earlier attempts could not reproduce it. **Confidence in the fix is high:** the fix takes the
  one-shot out of `up --wait` entirely, the reproduced mechanism no longer applies, and CI executed
  the stack job green twice.
- **Not reproduced:** the exact CI runner timing, since CI's job logs need authentication (the
  annotations are consistent with it). I did not run the full core profile locally.

## One-shot / dependency inventory (`docker-compose.yml` at c5b0cbe)

| Service | Profiles | Exits by design | Healthcheck | Waited on by `up --wait`? | Protection |
|---|---|---|---|---|---|
| timescaledb | core, full | no | yes | yes (healthy) | n/a |
| pii-vault | core, full | no | yes | yes | n/a |
| **pii-vault-migrate** (82–100) | **full** | **yes** | no | `core`: **not started**. `full`: **yes, no dependent → race (check 9)** | `make up` runs it with `run --rm` (checks 6–8) |
| redis | core, full | no | yes | yes | n/a |
| kafka | core, full | no | yes | yes | n/a |
| object-store | core, full | no | yes | yes | n/a |
| **object-store-init** (186–204) | core, full | **yes** | no | yes, with condition `completed_successfully` | `mlflow` depends on it (212) in **every** profile it is in. No path starts it without mlflow except an explicit `up object-store-init`, which nothing does |
| mlflow | core, full | no | yes | yes | n/a |
| mailpit, wiremock | core, full | no | yes | yes | n/a |

There are no `ml` or `obs` services on this branch or on `origin/m5/scoring`.

Every path that starts the stack:
- Makefile `up` (34–37), used by `stack-test` (96) and so by `make ci` and devcontainer
  post-create (`REQUIRE_DOCKER=1 make ci`).
- `stack.yml` (`make up`, teardown `--profile full down -v`).
- `smoke-test.sh`, `check-seeded-stack.sh` and `seed-demo`, which use `exec` only.
- `diagnose-stack.sh` and `collect-stack-diagnostics.sh`, which are read-only.

None of them starts `full` with `--wait`. **No other one-shot on m6/decision has the race.** The
race survives only as a latent trap under `--profile full up --wait`, which nothing runs today.

## Findings

### 1. MAJOR: the M9 carry that M6 hands over still describes the removed design and a Compose behaviour this fix disproves

- **Where:**
  - `docs/parallel/M6_updates.md:584` ("Carried: M9 — `M9_updates.md` section 8 (`6a7af4f`), plus
    O2 … moot"), which points M9 at `origin/m9/infra:docs/parallel/M9_updates.md` §8.1 (lines
    ~213–219).
  - The shared-file ledger, `docs/parallel/M6_updates.md:40-41`.
- **What the documents say:**
  - §8.1 tells M9 that "`make up` fails if that job fails (`infrastructure/docker/scripts/await-oneshot.sh` …: `docker compose up --wait` ignores a one-shot's exit code …)".
  - `await-oneshot.sh` no longer exists.
  - The claim about `up --wait` is the mistaken model behind the CI failure: `--wait` does not
    ignore an exited one-shot, it **fails** on one, even on exit 0 (checks 4 and 9).
  - The ledger still records `hardware.md` as "4,096 of 4,096 MiB, `pii-vault-migrate` added" (it
    is reverted to 3,968) and `Makefile` as "`up` checks the vault migration" via the removed
    mechanism.
- **Concrete failure scenario:**
  1. M9 containerises the API. `origin/m9/infra` `.github/workflows/security.yml:132` already runs
     `docker compose --profile full up -d --wait fraudshield-api`, and the M9 author works from §8.1.
  2. Trusting "`up --wait` ignores a one-shot's exit code", M9 either lets the API depend on
     `pii-vault-migrate` without `service_completed_successfully`, or puts both in `full` with
     `--wait`. Either way this is the check 9 failure, and it lands in CI exactly as it did for M6.
  3. Or M9 puts the API in `core` with any dependency on the migration. Compose then rejects the
     project outright (`depends on undefined service "pii-vault-migrate"`, check 10), because the
     migration is `full`-only.
  4. Or M9 leaves out the dependency. `up --wait fraudshield-api` then never runs the migration,
     and the API starts against an unmigrated vault.
- **None of these constraints is written anywhere M9 reads.** At the M5→M6 merge, the ledger also
  misstates which shared files changed and how.
- **How verified:**
  - `git show origin/m9/infra:docs/parallel/M9_updates.md` §8.1.
  - `git show origin/m9/infra:.github/workflows/security.yml` line 132.
  - `M6_updates.md:40-41` and `:584` against the tree.
  - The compose behaviour in checks 4, 9 and 10.
- **Required action:**
  - Correct the carry on `m9/infra`, appending only, as a dated correction per the parallel-branch
    rule:
    - `pii-vault-migrate` is `full`-only and run by `make up` via `run --rm`.
    - `up --wait` **fails** on an exited one-shot unless a dependent uses
      `service_completed_successfully`.
    - Any service that needs the vault migrated must depend on it with that condition, and then
      the migration has to go back into that service's profile (with the dependent that protects
      it); otherwise config fails.
  - Record the new carry SHA in `M6_updates.md:584`.
  - Update ledger rows 40–41 to the current state: `hardware.md` unchanged, and `make up` runs the
    migration with `run --rm`.

No other BLOCKER or MAJOR. The `e7a5be5` code change (`Makefile:34-37`,
`docker-compose.yml:74-100`) is correct in every path I exercised:
- core `up`, then `run`, twice
- after `down`
- on an existing vault volume
- with a failing migration (exit 1)
- with Compose v2.29.7 and v5.5.1

CI executed it green on `e7a5be5` and `c5b0cbe`. The `--profile full up --wait` trap (check 9) is
not a defect today, because nothing runs it. It belongs in the corrected carry above.
