# Author response to M0 review findings

Branch `m0/bootstrap`. Original review: `m0-bootstrap.md` (commit 3f82d6a, verdict CHANGES_REQUIRED,
0 BLOCKER / 5 MAJOR / 11 MINOR / 6 NIT). The repository owner added seven findings (O-1 … O-7).
Fixes are new commits on the branch; no published commit was rewritten.

## Principal Reviewer findings

| # | Sev | Resolution | Commit(s) | Verification |
|---|---|---|---|---|
| 1 | MAJOR | `fs-traceability-seed --check` proves `requirements.yaml` equals a fresh seed merged with progress fields; runs in CI governance, `make governance`, pre-commit | ca173d2 | `test_seed_check_detects_edited_or_deleted_rows` (priority/milestone edit and deleted row both fail, reseed passes) |
| 2 | MAJOR | Evidence must start with existing path, existing commit or CI run URL; deviations must be existing `docs/adr/NNNN-*.md`; non-test verification only for `VERIFICATION_OVERRIDES`; implementation paths must exist. ADR 0001/0004, README, walkthrough and defence answers corrected | ca173d2, 6b92a3f | `test_evidence_must_reference_path_commit_or_ci_run` ("tested", "should work", "ADR 9999 (nonexistent)"), `test_deviation_must_be_existing_adr_file`, `test_non_test_verification_only_for_reviewed_overrides` |
| 3 | MAJOR | Tag discovery rewritten (`test_tags.py`): Python via `ast` (multi-line decorators, class marks, `skip`), Java and TypeScript with comments/literals removed, multi-line titles, `@Disabled`/`.skip`/`.todo`/`x*` ignored, annotation spans parenthesis-aware. Runtime skips remain a documented limitation until executed reports are used (M9, ADR 0004) | ca173d2 | `tools/tests/test_test_tags.py` |
| 4 | MAJOR | `minimumReleaseAgeExclude` removed; prettier pinned to 3.9.6; lockfile rebuilt from fresh resolution (only prettier changed); frozen install passes the policy | 172c3d6 | `pnpm install --frozen-lockfile` → "Lockfile passes supply-chain policies" |
| 5 | MAJOR | CI job `stack`: `make env`, `make up` (`--wait`), `make smoke` (functional checks incl. MLflow artifact round-trip through S3), annotated diagnostics on failure. Intermittent MLflow worker death found through those diagnostics and fixed | 485bdf8, ce4c10d, ce6df6a, 4c7a81e, 2882a99 | CI run for the branch head (see "Gate evidence" below) |
| 6 | MINOR | `KAFKA_LOG_DIRS=/var/lib/kafka/data`; healthcheck CLI heap `-Xmx64m` | 485bdf8 | `docker compose config`; stack job |
| 7 | MINOR | Object-store healthcheck probes master and S3 gateway; init retries then asserts bucket exists; init memory limit 128m | 485bdf8 | stack job (`object-store-init exited 0`, bucket listed in smoke test) |
| 8 | MINOR | CI job `pre-commit` runs all hooks on all files (skipping hooks that have dedicated jobs); Prettier and google-java-format (Spotless) hooks added; Spotless check in Maven `validate` | 5ad62fa, 162cc44 | CI job `pre-commit`; `./mvnw verify` |
| 9 | MINOR | pytest-cov 7.1.0, `--cov-fail-under=90` branch-aware for tools and ML | ca173d2, 87fdcb8 | tools 94.60%, ML 100% |
| 10 | MINOR | Boundary tests P = 1 (accepted, FPR 0) and P = 1.1667 (rejected) | 87fdcb8 | Mutant `> 1.0` → `> 1.5` now fails `test_precision_just_above_one_is_rejected` (re-run by author) |
| 11 | MINOR | `main()` tested in a temporary git repository: stale matrix fails, render then passes, removing a tag makes it stale again | ca173d2 | `test_main_check_fails_on_stale_matrix_and_passes_after_render` |
| 12 | MINOR | Object rows with `$label` / `$expected` | db6ac93 | Vitest verbose output shows e.g. "fails AA text contrast at 3.19:1" |
| 13 | MINOR | ADR 0003: evidence commands, precise pinned/not-yet-installed/deferred lists, Redis 8 AGPL and Valkey options (verified from Redis LICENSE and Valkey repository), JDK pinned to Temurin 21.0.12 in CI and `.tool-versions` | 6b92a3f, 544f3b3 | — |
| 14 | MINOR | `fs-licences` inventory with scope-based allowlist (ADR 0009), CI job `licences`, build plugins assessed in the ADR. jqwik removed (its runtime notice restricts use by automated agents); property test replaced by 1,000 fixed-seed JUnit cases plus edges | 5ad62fa, 162cc44 | 187 dependencies, 0 violations; `tools/tests/test_licences.py` |
| 15 | MINOR | `.jqwik-database` deleted from git (and jqwik removed); wrapper-jar ignore pattern `**/.mvn/wrapper/maven-wrapper.jar` | 5ad62fa | `git ls-files \| grep jqwik` empty |
| 16 | MINOR | Empty local directories removed; `docs/architecture/repository_layout.md` maps each C.5 path to the milestone that creates it | 6b92a3f | — |
| 17 | NIT | `fs-commit-msg` as commit-msg hook and in CI over commits introduced by each push/PR; published history not rewritten (ba14868 remains, the only violation) | 162cc44 | `tools/tests/test_commit_msg.py` |
| 18 | NIT | Accepted: push-on-any-branch plus PR-to-main is kept so branches without a PR (no `gh` auth) still get CI; concurrency cancels superseded runs | — | — |
| 19 | NIT | `REPO_ROOT` from `git rev-parse --show-toplevel`, file-relative fallback | ca173d2 | tools tests run from `tools/` and repo root |
| 20 | NIT | Tie cases 0.125 → 0.12, 0.135 → 0.14 KES (HALF_EVEN vs HALF_UP), negative tie; negative amounts documented as intentional, positivity checked at boundaries | 5ad62fa | `MoneyTest` |
| 21 | NIT | Redis password written to a private config file, not argv | 485bdf8 | smoke test authenticated round-trip and unauthenticated refusal |
| 22 | NIT | pre-commit-hooks pinned to commit 3e8a870 (v6.0.0) | 4c7a81e | — |

## Repository owner findings

| # | Sev | Resolution | Evidence |
|---|---|---|---|
| O-1 | BLOCKER | `git fetch origin` and `git ls-remote origin` show only `refs/heads/m0/bootstrap`; `origin/main` does not exist (`git log origin/main` → unknown revision). There is no earlier "first commit" to integrate, so `main` is created from the approved branch head by fast-forward, without force. `gh` is not authenticated on the build machine, so the default branch cannot be changed from here: **the owner must set `main` as the default branch** (GitHub → Settings → Branches), then may delete `m0/bootstrap` | commands re-run at 2026-09-17 ~05:05 UTC |
| O-2 | MAJOR | Same as reviewer finding 4 | 172c3d6 |
| O-3 | BLOCKER for tagging | On the build machine `docker.service` and `docker.socket` are inactive, no `dockerd` process exists and no socket is present (`/var/run/docker.sock`, rootless and Desktop paths checked), so `make up` cannot run locally. The gate is instead evidenced by the CI `stack` job on GitHub's Docker runner (`make up` with `--wait` plus `make smoke`). `m0-complete` is not tagged until that job passes on the merged commit | CI run URL recorded in the re-review |
| O-4 | MAJOR | ADR 0007 (Spring Boot 4.1 compatibility table, Jackson 3 impact, Resilience4j risk); ADR 0005 table of all three MinIO mentions plus dataset uploads and generated data | 6b92a3f |
| O-5 | MINOR | Allowlist with reasons and `scope-guard: allow D-47` line pragma (ADR 0008); the rephrased defence question restored to its direct wording with the pragma; D-47 row keeps its verbatim title. Doing this exposed a real bug: compound project names were missed after camelCase splitting (fixed, tested) | ca173d2 |
| O-6 | MINOR | `tools/bin/gitleaks` pins 8.30.1 with SHA-256 (551f6fc8…); a deliberately wrong checksum was rejected ("checksum mismatch", exit 1). Used by the pre-commit hook, `make secrets-scan` and the CI `secrets` job, which scans full history independently of any local hook. Note: the hook configuration referencing the launcher was pushed one commit before the launcher itself (4c7a81e before 162cc44) | 4c7a81e, 162cc44 |
| O-7 | — | 60 FR IDs confirmed per section: 3.1 FR-01 7 (01–07), 3.2 FR-02 10, 3.3 FR-03 8, 3.4 FR-04 12, 3.5 FR-05 7, 3.6 FR-06 7, 3.7 FR-07 9; each section contiguous, none misplaced; YAML FR set identical to the SRS set; priorities M 47, S 12, C 1. No mismatch. (An early count of 59 during bootstrap was a hand-count error, caught by the seeder's assertion before any commit.) | parsing script output in this session |

## Observations for the re-review

- One ML test run failed once immediately after the boundary tests were added and did not reproduce
  in five subsequent runs; baseline, mutant and restored runs behaved as expected. Cause not
  identified.
- During the licence work the Maven report parser silently returned 0 of 18 dependencies because
  the plugin skipped regenerating an existing file; fixed with per-scope filenames, `force`, and a
  declared-count check that raises.
