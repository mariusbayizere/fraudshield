# Review: M0 milestone (bootstrap and governance), build prompt I.3            Reviewer role: Principal Reviewer
Date: 2026-09-17 (UTC)   Branch/commit: m0/bootstrap @ 83a90905a78b7c671e39d758ead9ed5d92b2b2dd (the commit proposed for fast-forward to `main`)   Requirements: D-47, D-48 (all rows with milestone M0); M0 gate items of D.3   Defects: D-17, D-20, D-21, D-43, D-47, D-48, D-51
Verdict: APPROVED_WITH_MINORS

Summary: I checked out the merge candidate into a clean worktree and ran the M0 gate there.
Everything that can run on this machine passed. The Docker gate items are evidenced by CI runs,
which I verified through the public API. The review history is in `m0-bootstrap.md` (review, re-review,
delta review, final delta check). No BLOCKER or MAJOR finding is open. The remaining MINOR and NIT
findings concern governance tooling or later-milestone deliverables. They are logged in
`docs/backlog/governance.md` with due milestones (owner direction, 2026-09-17: governance is
time-boxed; only BLOCKERs are fixed in M0), except the threat-model seed (M-1), which is due in M1.
**Open findings: 0 BLOCKER, 0 MAJOR, 4 MINOR, 8 NIT.**

## Checks re-run (command → result)

Clean worktree: `git worktree add ../fs-review-m0 83a90905a78b7c671e39d758ead9ed5d92b2b2dd` (fresh
checkout, no `.venv`, `node_modules`, `target/` or `build/`), toolchain via
`export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`. The worktree was removed afterwards
(`git worktree remove ../fs-review-m0`; `git worktree list` shows only the main checkout).

| Command (in the clean worktree) | Result |
|---|---|
| `uv sync --all-packages --locked --no-cache` | exit 0; resolved 29, installed 27 packages from the lockfile, bypassing the uv cache |
| `cd frontend && pnpm install --frozen-lockfile` | exit 0; "Lockfile passes supply-chain policies" (the pnpm content store and `~/.m2` could not be bypassed; the lockfiles and checksums bound them) |
| `make ci` | **exit 0**, 2 m 55 s |
| ↳ ruff check / format / mypy --strict | clean / 32 files formatted / no issues in 30 files |
| ↳ tools pytest / ml pytest | 131 passed, 92.94 % branch coverage / 14 passed, 100 % (gate 90 %) |
| ↳ `make test-java` | `SKIPPED: JUnit tests tagged requires-docker require Docker, verified in CI (job: java)`; 1064 tests (1028 + 36 in `MoneyBoundaryTest`), 0 failures; Spotless, Checkstyle, SpotBugs clean; JaCoCo gate met |
| ↳ frontend typecheck / lint / format / vitest | clean; 16 passed; 100 % |
| ↳ governance | defect register current (51); `traceability seed up to date`; `traceability-check: 258 rows, 24 tagged tests, 0 errors, 0 warnings`; scope-guard 0; `compose-budget: core 3968/4096 MiB …; 0 errors` |
| ↳ compose-config | exit 0 |
| ↳ secrets-scan | 37 commits, no leaks; working tree no leaks |
| ↳ licences | 187 dependencies (runtime 0 / dev 187), 0 violations |
| ↳ stack-test | `SKIPPED: stack-test requires Docker, verified in CI (job: stack)` (Docker daemon unavailable on this machine; see CI evidence) |
| Mutation spot check in the main checkout (`MoneyBoundaryTest`), each reverted with `git checkout` | `requirePositive` accepting zero (`!isPositive()` → `signum() < 0`): **killed** (`zeroIsStorableButRejectedWherePositiveAmountRequired`). `HALF_EVEN` → `HALF_UP`: **killed** (5 rounding cases). `smallestMinorUnit` using storage scale: **killed** (7 cases). `git status --short` clean |

### CI evidence (public GitHub Actions API)

Gate "`make up` healthy" (ADR 0010: three consecutive green `stack` runs plus green runs on the merge head).

| # | Commit | Run | Job checked | Conclusion |
|---|---|---|---|---|
| 1 | 2882a99 | 35178641969 (ci) | `stack` executed | success |
| 2 | cd99dc9 | 35179479949 (ci) | `stack` executed | success |
| 3 | b08ba61 | 35181726185 (ci) | `stack` executed, 04:23:13Z–04:24:53Z | success |
| 4 | ed7223c | 35182920003 (stack.yml) | `stack` executed, 04:42:36Z–04:44:15Z | success |
| 5 | 5761d21 | 35183327832 (stack.yml) | `stack` executed, 04:48:55Z–04:50:30Z | success |
| 6 | ff2e30d | 35183578414 (stack.yml) | `stack` executed, 04:52:58Z–04:54:32Z | success |
| 7 | 6942c96 | **35184012351** (stack.yml) | `detect stack input changes` success; `stack` executed, 05:00:07Z–05:01:41Z; the only non-success step is the failure-only diagnostics step (skipped) | **success** |

Merge-candidate runs on 6942c96 (last commit with code or workflow changes):

| Workflow | Run | Jobs |
|---|---|---|
| ci | **35184012244** | 7/7 success: python, governance, licences, pre-commit, frontend, java, gitleaks |
| stack | **35184012351** | change detection success; `stack` success (executed) |
| devcontainer | **35184012247** | change detection success; `build devcontainer, post-create make ci, smoke test inside` **success**, 05:00:08Z–05:04:07Z (the failure-log step was skipped). The workflow requires the `.post-create-ok` sentinel, which `post-create.sh` writes only after `REQUIRE_DOCKER=1 make ci` succeeds under `set -Eeuo pipefail`. It had failed on b08ba61, ed7223c, 5761d21 and ff2e30d |

Runs on 83a9090 (documentation only), all completed:

| Workflow | Run | Jobs |
|---|---|---|
| ci | 35184418101 | 7/7 success |
| stack | 35184418147 | change detection success; `stack` **skipped** (no stack inputs changed); run-level conclusion "success" |
| devcontainer | 35184418108 | change detection success; `build-and-verify` **skipped**; run-level conclusion "success" |

A path-filtered workflow reports run-level "success" even when its job was skipped, so gate
evidence must always cite the **job** conclusion (F-4). On 83a9090 skipping is correct, because
nothing the stack or devcontainer depends on changed since 6942c96.

Remote: only `m0/bootstrap` exists; there is no `main`, so a fast-forward creates it without force
and there is no earlier history to integrate (O-1).

## Mutation spot checks (what was broken → which test failed)

Milestone-level spot check on the only code added since the delta review (money boundaries,
6a48410). Earlier mutation campaigns for this branch are in `m0-bootstrap.md`: 12 in the first
review, 12 + 7 in the re-review, 18 in the delta review.

| # | Mutation (`Money.java`) | Result |
|---|---|---|
| MB1 | `requirePositive`: `!isPositive()` → `amount.signum() < 0` (zero accepted) | killed: `MoneyBoundaryTest.zeroIsStorableButRejectedWherePositiveAmountRequired` |
| MB2 | `displayAmount`: `HALF_EVEN` → `HALF_UP` | killed: `roundingAtEachCurrencyMinorUnit` cases 1, 5, 7, 8, 11 |
| MB3 | `smallestMinorUnit`: minor-unit exponent → `STORAGE_SCALE` | killed: `smallestMinorUnitPerCurrency` (7 cases) |

## Milestone walk (I.3 steps 2–5)

### Gate items (D.3 M0)

| Gate / scope item | Evidence | Result |
|---|---|---|
| Repository skeleton (C.5) | present components plus `docs/architecture/repository_layout.md` mapping every planned path to its milestone | met (planned directories are documented, not created) |
| SRS copied and extracted | `.docx` + `.md`; word-token comparison showed nothing missing (first review) | met |
| `defect_register.md` from Part B | generated; `fs-defect-register --check` | met |
| Traceability YAML seeded with all IDs | 258 rows; `fs-traceability-seed --check`; FR counts 7/10/8/12/7/7/9 | met |
| ADR template | `docs/adr/0000-template.md`; ADRs 0001–0010 | met |
| Toolchains pinned (Java 21, Python 3.12 + uv, Node LTS + pnpm) | `.tool-versions`, `.nvmrc`, `.python-version`, CI `env`, devcontainer and `package.json` agree (see cross-component check) | met |
| pre-commit (Gitleaks, formatters) | checksum-pinned gitleaks launcher; ruff, Prettier, Spotless hooks; CI `pre-commit` job green | met |
| docker-compose `core` profile (Kafka, PostgreSQL+TimescaleDB, PII vault, Redis, S3 store, MLflow, Mailpit, WireMock) | compose file; SeaweedFS replaces MinIO (ADR 0005) | met with a recorded deviation |
| CI lint + type-check + unit skeleton, all three languages | ci run 35184012244 | met |
| **Gate: `make up` healthy** | ADR 0010: CI stack job authoritative; 7 consecutive green executed runs; merge-candidate stack and devcontainer green | **met via CI** (not reproducible on the reference laptop; owner decision) |
| **Gate: CI green** | ci/stack/devcontainer on 6942c96; ci on 83a9090 | met |
| **Gate: traceability-check runs** | clean worktree: 0 errors; CI governance job green | met |
| **Gate: test commit pushed to a branch and visible on the remote** | `m0/bootstrap` @ 83a9090 on the remote | met |

### Traceability rows with milestone M0

Only two rows carry milestone M0. The other M0 scope items (CI, toolchains, compose) correspond to
rows owned by later milestones (OPS-CI-* in M9) and are evidenced by the gate table above.

| Row | Priority / verification | Current status | Implementation (exists) | Tagged tests | Assessment |
|---|---|---|---|---|---|
| D-47 (copied 05B text) | M / test | IN_PROGRESS | `tools/src/fraudshield_tools/scope_guard.py`, `traceability_seed.py` | 5 × `@pytest.mark.req("D-47")` in `tools/tests/test_scope_and_registers.py` (lines 26, 31, 56, 73, 169) | Resolution implemented: substitutions at seeding, guard on paths and content, reviewed allowlist, pragma limited to docs (ADR 0008). Ready for `DONE` |
| D-48 (ignore calendar roadmap) | M / inspection | IN_PROGRESS | `docs/adr/0001-record-architecture-decisions.md`, `docs/traceability/requirements.yaml` | n/a (inspection) | Inspected: every one of 258 rows carries a milestone M0–M12 and none carries a week or date. Ready for `DONE` |

With `completed: [M0]`, the check currently fails exactly as expected (simulated in memory against
this commit: "D-47: Must row in closed M0 has status IN_PROGRESS", same for D-48). The closing
commit requirements are below.

### Cross-component consistency (I.3 step 3)

- **Toolchains agree everywhere.** Java 21.0.12 (`.tool-versions`, CI `JAVA_VERSION`, devcontainer
  `21.0.12-tem`; Maven `release` 21). Python 3.12.14 (`.python-version`, `.tool-versions`, CI;
  `requires-python ==3.12.*` in all three projects; ruff `py312`; mypy 3.12). Node 24.21.0
  (`.nvmrc`, `.tool-versions`, CI, devcontainer, `package.json` engines). pnpm 12.4.2
  (`packageManager`, `.tool-versions`). uv 0.12.15 (CI, devcontainer, checksum-verified). Maven
  3.9.16 (wrapper with SHA-256).
- **No inter-component contracts exist yet.** There is no OpenAPI, proto or Kafka schema until M1,
  so there are no contract tests to run. Money semantics are defined once, in Java
  (`CurrencyCode`, `Money`, DECIMAL(18,4), ISO 4217 minor units). The Python and TypeScript
  components do not handle money yet. M1 contracts must carry amounts as decimal strings with the
  currency (H.1), matching `Money`.
- **Compose and CI agree.** `stack.yml`, `devcontainer.yml` and the smoke test use the same `make
  up` / `make smoke` targets as local Codespaces work. The memory budget (3,968 / 4,096 MiB core)
  matches `docs/benchmarks/hardware.md`.

### Threat-model delta (I.3 step 4)

`docs/security/threat_model.md` does not exist yet; `repository_layout.md` plans it for M1. M0
adds no production component and no data flow carrying real data. The security-relevant surface it
does add, which the M1 threat model must start from:

| Element | Threat (STRIDE) | Current control | Residual |
|---|---|---|---|
| Local `core` stack (dev only, synthetic data, D-21) | I/E: plaintext Kafka without auth, Redis without TLS, MLflow and Mailpit without auth, PII vault on the same flat network as every service | ports bound to 127.0.0.1; per-machine random credentials in git-ignored `.env`; Redis password not in argv; memory limits | acceptable for dev only; production controls (D-20 TLS/vault isolation, network policies) are M1/M9 |
| CI (GitHub Actions) | T: action or tool supply chain; I: token misuse | actions pinned by SHA; `permissions: contents: read`, plus `actions: read` for one step; gitleaks and uv checksum-verified; lockfiles frozen; pnpm release-age policy | images pinned by tag, not digest (GOV-8, M9) |
| Devcontainer / Codespaces | E: Docker-in-Docker runs privileged inside the Codespace; T: features pulled from GHCR | base image by digest; features by version | feature versions are not digest-pinned; Codespace secrets policy is up to the owner |
| Governance tooling calling the GitHub API | S/I: token in environment | read-only token passed only to the traceability step in CI | none material |

Condition carried to M1 (M-1): create `docs/security/threat_model.md` with at least this delta
before the first M1 data-flow component merges.

### Architecture fitness (I.3 step 5)

No hot path exists; the C.2 latency budget is not applicable at M0. Module boundaries:
`ArchitectureTest` keeps `common` free of framework and I/O dependencies (green in the clean run).
The dependency graph is limited to test and build tooling (licence inventory: 0 runtime
dependencies).

## Findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| DR-2 / GOV-1 | MINOR | `tools/src/fraudshield_tools/evidence.py` | Benchmark evidence does not yet require a dedicated machine for `DONE`, accepts any file under `docs/benchmarks/`, and misses some timing rows | Per backlog GOV-1, **before FR-02-02 moves (M3)** | OPEN (backlogged) |
| DR-3 / GOV-2 | MINOR | `pytest_docker.py`, CI java/python jobs, `test_tags/*` | Runtime skips pass under `REQUIRE_DOCKER`; some never-running forms count as tagged tests | Per GOV-2, **before M1 closes** | OPEN (backlogged) |
| DR-4 / GOV-3 | MINOR | `compose_budget.py` | Unprofiled services and replicas are not counted; tracebacks instead of reported errors | Per GOV-3, **before the first ml/obs service (M5)** | OPEN (backlogged) |
| M-1 | MINOR | `docs/security/threat_model.md` (absent) | I.3 step 4 expects the threat model updated for new components; the file does not exist. The M0 delta is recorded above | Create it in M1 with the delta above before the first M1 data-flow component merges. Add it to the backlog or the M1 plan | OPEN |
| DR-6 / GOV-4 | NIT | `tools/bin/docker-gate`, `tools/bin/gitleaks`, scope-guard allowlist | Surviving mutants D1, G1, S4 | GOV-4, M1 | OPEN (backlogged) |
| DR-7 / GOV-5 | NIT | `.github/workflows/devcontainer.yml` inputs; default branch | Remaining trigger inputs; schedules need `main` as the default branch | GOV-5. Give it a concrete due milestone (M1) and pair it with GOV-9 | OPEN (backlogged) |
| DR-9 / GOV-6 | NIT | scope-guard pragma | Honoured inside fenced code blocks | GOV-6 is "unscheduled". The owner's rule requires a due milestone, so assign one (M1 or M8) | OPEN (backlogged, due date missing) |
| R-8 / GOV-7 | NIT | `commit_msg.py` | `Assisted-by`/`Co-developed-by` tool trailers and trailing-word placeholders pass | GOV-7, M1 | OPEN (backlogged) |
| F-2 | NIT | `backend/common/src/test/java/…/money/MoneyBoundaryTest.java:22` | Class-level `@Tag("D-17")` makes money tests count as tagged tests for D-17, whose resolution concerns decision-engine tests (JUnit 5 + properties, M6). At M6 the D-17 row could pass the "has a tagged test" rule without any decision-engine property test | Keep the tag (owner asked for it), but when D-17 closes, reviewers require the M6 decision-engine property tests named in ADR 0009 as the evidence. Alternatively add a D-17 row note saying the money tests alone do not close it | OPEN |
| F-3 | NIT | `MoneyBoundaryTest.roundingAtEachCurrencyMinorUnit` | The owner asked for rounding at *each* currency's minor units. Ties are tested for RWF, UGX, KES, TZS and CDF; BIF, SSP, SOS, USD and EUR are covered only by the exponent table (`MoneyTest`) and `smallestMinorUnitPerCurrency` | Add one tie case each for BIF (0 dp) and USD/EUR (2 dp) when the file is next touched (M1) | OPEN |
| F-4 | NIT | `docs/reviews/M0/stack-gate-evidence.md`; path-filtered `stack.yml` / `devcontainer.yml` | A skipped `stack` or `devcontainer` job still yields run-level "success" (e.g. 35184418147). Anyone reading run conclusions instead of job conclusions could record a skipped stack as green | In evidence files, always cite the job conclusion and whether it executed. The closing evidence must use the `main`-push runs, where both jobs are forced to execute | OPEN |
| F-1 | NIT | `docs/backlog/governance.md` | GOV-6 is unscheduled and GOV-5 and GOV-9 have no milestone, contrary to the owner's "logged … with a due milestone" | Assign due milestones | OPEN |

Resolved in this cycle (details in `m0-bootstrap.md`, "Final delta check"): DR-1 (devcontainer
green, run 35184012247), DR-5 (ADR 0010 attribution; `stack-gate-evidence.md`), DR-8 (smoke test
captures, then asserts). All findings of the review, re-review and delta review are now resolved,
accepted with reasons, or backlogged.

## Evidence reproduced (claimed vs measured)

| Claim | Measured | Match |
|---|---|---|
| Three consecutive green stack runs, seven in total, none failed after 2882a99 (`stack-gate-evidence.md`) | 7 executed `stack` jobs all success (IDs above); branch run list shows no stack failure after 162cc44 | yes |
| 6942c96: ci 7/7, stack executed and green, devcontainer post-create + smoke green | jobs verified individually, including the job-level `stack` and `build-and-verify` executions | yes |
| Devcontainer failures on b08ba61, ed7223c, 5761d21, ff2e30d fixed in 70046b1/ff2e30d/6942c96 (ADR 0010 item 2) | runs 35181726205, 35182920025, 35183327917, 35183578516 failure; 35184012247 success | yes |
| `make ci` green without Docker, skipping visibly | clean worktree exit 0, two SKIPPED lines | yes |
| Money boundary tests cover the owner's list | zero rejected where positive is required ✔; smallest storable (0.0001) and smallest minor unit per currency ✔; DECIMAL(18,4) maximum and one step beyond ✔; rounding at minor units for the five EAC currencies ✔ (others partial, F-3); negatives ✔; null-fingerprint case recorded as an M3 note on FR-01-04 and D-04, not implemented (no feature code exists) ✔ | yes (F-3) |
| Tests tagged D-17 and linked to ADR 0009 | `@Tag("D-17")`, `@Tag("D-43")`; class Javadoc cites ADR 0009 and D-17 | yes |
| 258 rows, seed intact, 0 traceability errors | reproduced in the clean worktree (24 tagged tests) | yes |
| 187 dependencies, 0 licence violations | reproduced | yes |

## Residual risks

- The stack has never run on the reference laptop (owner decision). Its health rests on shared-runner CI
  and Codespaces. From M1, Docker-dependent failures surface after a push, not before.
- The CI minutes policy skips `stack` and `devcontainer` on unrelated pushes. A regression caused
  by a file outside the input patterns (for example a Python dependency used only inside the
  container) would surface only on the nightly run or the next push to `main`.
- Governance gaps GOV-1 to GOV-3 are real but not exploited today: no row is `DONE`, no benchmark
  exists, no Docker test exists. Their due milestones must be honoured.
- Carried from earlier reviews: images pinned by tag (GOV-8); Resilience4j Spring Boot 4 starter
  built against Boot 4.0 (ADR 0007, M6 test plan); dev stack deliberately insecure; no branch
  protection until the owner acts (GOV-9).

## Decisions

### (a) Fast-forward `main` to 83a9090: **APPROVED**

Conditions:
1. Fast-forward only, no force. `main` does not exist on the remote, so nothing needs integrating.
2. After the push, the `main` push runs must be green with jobs **executed**. `stack.yml` and
   `devcontainer.yml` force execution on pushes to `main`: ci 7/7, the `stack` job and the
   `build-and-verify` job, all success. If any fails, fix forward on a branch and re-review.
   Do not tag.
3. The owner sets `main` as the default branch and enables branch protection requiring the `ci`
   jobs (GOV-9). If that cannot happen immediately, record it as an open owner action in the M0
   status block.

### (b) Tag `m0-complete` on the closing commit: **APPROVED once its CI is green**

The closing commit (on `main`, a descendant of 83a9090) must contain exactly:

1. **This review record** `docs/reviews/M0/milestone-review.md` and the appended "Final delta
   check" in `docs/reviews/M0/m0-bootstrap.md` (I.1 rule 7). D-47 and D-48 evidence points at the
   first, so it must exist in the same commit.
2. **`docs/traceability/requirements.yaml`**, progress fields only (the seed check must still pass):
   - `D-47`: `status: DONE`; `verification: test` (unchanged). `evidence`, each entry starting
     with an existing path or an ancestor commit, for example:
     - `tools/tests/test_scope_and_registers.py (5 tests tagged D-47: guard, substitutions, allowlist and pragma)`
     - `docs/reviews/M0/milestone-review.md (M0 milestone review, APPROVED_WITH_MINORS)`
     - `83a9090 (reviewed merge candidate)`
     - optionally the `main`-push ci run URL. That is only verified in CI (a warning locally) and
       must be a successful run whose head is in history.
   - `D-48`: `status: DONE`; `verification: inspection` (permitted by `VERIFICATION_OVERRIDES`).
     `evidence`: `docs/reviews/M0/milestone-review.md (inspection: all 258 rows carry milestones
     M0–M12, none carries a calendar date)` and `docs/adr/0001-record-architecture-decisions.md`.
   - No `deviations` entries (neither row is `DONE_WITH_DEVIATION`). Keep `implementation` as is.
   - Do **not** use free text as the first token ("inspected", "tested"), a commit that is not an
     ancestor, or a non-existent path. The check rejects each of these (simulated).
3. **`docs/traceability/requirements_matrix.md`** re-rendered (`uv run fs-traceability render`),
   or the stale-matrix rule fails.
4. **`docs/traceability/milestones.yaml`**: `completed: [M0]` and `current: M1`.
5. **`docs/reviews/M0/stack-gate-evidence.md`** updated with the `main`-push run IDs and **job**
   conclusions (ci, `stack` executed, `build-and-verify` executed) for the fast-forwarded head.
6. `CHANGELOG.md` M0 entry and the M0 status block (walkthrough), per D.3 and J. Do not change
   code or workflows in this commit; otherwise path filters trigger new stack and devcontainer
   runs that must also pass.

Verification before tagging: locally, `uv run fs-traceability-seed --check && uv run fs-traceability
check` shows 0 errors with `completed: [M0]`. I simulated this against 83a9090 with the rows above,
and the only errors were the not-yet-existing `milestone-review.md` path, which this commit adds.
Then push; the `ci` run on the closing commit must show 7/7 success (governance runs the check with
the token, so any CI-run URL evidence is verified). Because the closing commit is a push to `main`,
`stack` and `devcontainer` execute as well. ADR 0010 requires the tagged commit's stack run to be
green, so all three workflows must pass with jobs executed. Then create the annotated tag
`m0-complete` on that commit and push the tag.
