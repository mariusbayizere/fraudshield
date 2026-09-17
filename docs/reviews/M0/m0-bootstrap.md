# Review: M0 bootstrap (branch `m0/bootstrap`, 9 commits 89d6859..3f82d6a)            Reviewer role: Principal Reviewer
Date: 2026-09-17 (UTC)   Branch/commit: m0/bootstrap @ 3f82d6a8db26c35026dde1dc13c38e0f72a1d84f   Requirements: ML-GATE-02, ML-GATE-03, ML-GATE-04, OPS-CI-01, OPS-CI-02 (partial, M0 scope)   Defects: D-01, D-02, D-09, D-20, D-21, D-33, D-43, D-47, D-48, D-51
Verdict: CHANGES_REQUIRED

Summary: 0 BLOCKER, 5 MAJOR, 11 MINOR, 6 NIT. Every number I could reproduce matched the
claim. Changes are required because the traceability tooling is weaker than the ADRs, README
and commit messages say. The M0 gate item "`make up` healthy" is still unverified.

**How the gate should treat "`make up` healthy".** This machine cannot run Docker. The daemon
is inactive (`systemctl is-active docker` → `inactive`), so this item is **NOT VERIFIED**. It
must not be marked as passed from reading the compose file or from `docker compose config`.
GitHub's `ubuntu-24.04` runners do have Docker, so the item can be verified automatically
there (finding 5). M0 must not be tagged `m0-complete`, and `M0` must not be added to
`docs/traceability/milestones.yaml` `completed`, until one of these is true:
(a) a CI job brings up the `core` profile with `--wait` and passes, or (b) the author runs
`make up` on a Docker-capable machine and commits the `docker compose --profile core ps`
output. Until then, record the gap in the M0 status block as open.

## Checks re-run (command → result)

Environment: `export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`; uv 0.12.15, Node 24.21.0, pnpm 12.4.2, OpenJDK 21.0.12, Docker Compose v5.5.1 (daemon not running).

| Command | Result |
|---|---|
| `make ci` (repo root) | exit 0, 1 m 18 s wall |
| ↳ `uv run ruff check tools ml` / `ruff format --check` | All checks passed / 15 files already formatted |
| ↳ `pnpm lint` / `pnpm format:check` | 0 warnings / all files formatted |
| ↳ `./mvnw -q checkstyle:check` | 0 violations |
| ↳ `uv run mypy tools/src tools/tests ml/src ml/tests` | Success: no issues in 13 source files |
| ↳ `pnpm typecheck` | clean |
| ↳ `cd tools && uv run pytest -q` | 20 passed |
| ↳ `cd ml && uv run pytest -q` | 12 passed |
| ↳ `./mvnw -B -ntp verify` | BUILD SUCCESS; 22 tests (MoneyTest 19 + property 1 + ArchitectureTest 2); SpotBugs 0 bugs; JaCoCo "All coverage checks have been met" |
| ↳ `pnpm test:coverage` | 16 passed; 100 % statements/branches/functions/lines (21/21, 4/4, 4/4, 21/21) |
| ↳ `uv run fs-defect-register --check` | up to date (51 defects) |
| ↳ `uv run fs-traceability check` | 258 rows, 20 tagged tests, 0 errors |
| ↳ `uv run fs-scope-guard` | 0 files with out-of-scope terms |
| ↳ `docker compose --env-file .env.example --profile full config -q` | exit 0 |
| ↳ `gitleaks git --redact .` / `gitleaks dir --redact .` | 9 commits, no leaks / no leaks |
| `git status --short` after all runs and mutations | clean |
| GitHub Actions run 35175017636 (`push`, `m0/bootstrap`, head_sha 3f82d6a) via public API | completed / **success**; 5/5 jobs success: python, java, frontend, traceability-check/defect register/scope guard (incl. compose validation), gitleaks |
| Action pins vs tags (`GET /repos/<action>/git/ref/tags/<tag>`) | checkout v7.0.1, setup-uv v10.1.0, setup-java v6.0.1, setup-node v7.0.0 and pnpm/action-setup v6.1.0 (annotated tag → commit ea17c68) all match the pinned SHAs |
| Image tags on registries (Docker Hub v2 API, GHCR manifest) | all 8 exist (timescaledb 2.30.0-pg16, postgres 16.15, redis 7.2.16, apache/kafka 4.3.1, seaweedfs 4.47, mailpit v1.31.1, wiremock 3.13.2, mlflow v3.16.0-full) |
| `make up` | **not executable here**: Docker daemon inactive |
| Remote branches (`GET /repos/.../branches`) | only `m0/bootstrap` @ 3f82d6a; gate item "test commit visible on the remote" satisfied |

Commit hygiene (G.3), checked with `git log --format` for each commit:

| Check | Result |
|---|---|
| Conventional Commit types/scopes | all 9 valid (`docs`, `chore`, `build`, `ml`, `feat`, `ci`) |
| Subject ≤ 72 chars | 8/9; `ba14868` is 73 chars (finding 17) |
| Body wrapped ≤ 72 | 9/9 |
| "Why" in body, `Refs:` / `Tests:` trailers | 9/9 have rationale and `Refs:`; `Tests:` on the 5 commits that add code or checks |
| Co-Authored-By / tool signatures | none |
| Author/committer | `Marius Bayizere <bayizeremarius119@gmail.com>`, matching G.1 and the SRS contact |
| Each commit green on its own | **not verified**: all 9 commits were pushed at once, so CI ran only on 3f82d6a. `777ef87` adds Makefile targets (`lint`, `test-java`, `test-frontend`) whose components only arrive in later commits (residual risk) |

## Mutation spot checks (what was broken → which test failed)

Each mutant was applied with `sed`, tested, then reverted with `git checkout -- <file>`. `git status --short` was clean afterwards.

| # | File | Mutation | Result |
|---|---|---|---|
| a1 | `ml/src/fraudshield_ml/metrics/operating_points.py:40` | ceiling `base_rate / (base_rate + fpr)` (dropped `(1 - base_rate)`) | **killed**: `test_precision_ceiling_at_one_percent_fpr_matches_hand_calculation`, `test_ceiling_is_one_at_zero_fpr_and_base_rate_at_full_fpr` |
| a2 | same, line 63 | FPR denominator drops `(1 - base_rate)` | **killed**: `test_implied_operating_point_for_srs_recall_and_f1_targets` |
| a3 | same, line 61 | `precision > 1.0` → `precision > 1.5` | **SURVIVED** (finding 10) |
| b1 | `backend/common/.../money/Money.java:70` | `HALF_EVEN` → `HALF_UP` | **killed**: `MoneyTest.displayAmountRoundsToMinorUnitsWithBankersRounding[1]` (expected 1250) |
| b2 | same, line 36 | scale guard `> STORAGE_SCALE` → `> STORAGE_SCALE + 1` | **killed**: `storesAtDecimal18Scale4AndRejectsWhatCannotBeStoredExactly` |
| b3 | same, line 40 | precision guard `> 18` → `> 19` | **killed**: same test |
| b4 | same, line 74 | currency-mismatch check disabled | **killed**: `additionRequiresTheSameCurrency` |
| b5 | same, line 39 | `RoundingMode.UNNECESSARY` → `HALF_EVEN` | survived. **Equivalent mutant**: the scale guard on line 36 already rejects anything that would round |
| c1 | `frontend/src/design-system/color/contrast.ts:48` | `+ 0.05` → `+ 0.1` | **killed**: 14 tests incl. all `[D-33]` rows |
| c2 | same, line 39 | green weight `0.7152` → `0.7` | **killed** |
| c3 | same, line 25 | gamma `2.4` → `2.2` | **killed** |
| c4 | same, line 12 | `AA_NORMAL_TEXT` 4.5 → 4.4 | **killed**: 2 tests (`#DC2626 on #FEF2F2`, boundary test) |
| c5 | same, line 25 | linearisation threshold `0.04045` → `0.03928` | survived. **Equivalent** for 8-bit input: no channel value lies between 10.02/255 and 10.31/255 |
| c6 | same, lines 46–47 | swap `max`/`min` | **killed** |
| d1 | `tools/src/fraudshield_tools/traceability.py:200` | evidence requirement disabled | **killed**: `test_done_without_evidence_or_test_fails` |
| d2 | same, line 182 | closed-milestone `<=` → `<` | **killed**: `test_closed_milestone_requires_final_status_and_test` |
| d3 | same, line 216 | path-existence check disabled | **killed**: `test_missing_evidence_file_fails` |
| d4 | same, line 144 | unknown-tag check disabled | **killed**: `test_unknown_tag_fails` |
| d5 | same, line 182 | Must-only filter removed | **killed**: `test_should_rows_in_closed_milestone_are_not_forced` |
| d6 | same, lines 275–276 | stale-matrix check disabled | **SURVIVED** (finding 11) |
| d7 | `docs/traceability/requirements.yaml` | FR-01-03 `priority: M` → `S`, `milestone: M6` → `M12`, matrix re-rendered | **not detected**: `fs-traceability check` 0 errors, tools pytest 20 passed (finding 1) |
| e1 | new untracked `frontend/src/probe.ts` containing a string literal with the banned D-47 queue term (the lowercase word listed in `scope_terms.py`) | `uv run fs-scope-guard` | **detected**: exit 1, the file is reported with that term |
| e2 | same file with a PascalCase identifier built from that term plus `Queue` | `uv run fs-scope-guard` | **detected**: exit 1 |

Probes of checker guarantees, run in a scratch directory outside the repository against `fraudshield_tools.traceability`:

- `DONE_WITH_DEVIATION`, `deviations: ["ADR 9999 (nonexistent)"]`, `evidence: ["tested"]` → **0 errors**.
- `DONE`, `verification: inspection`, `evidence: ["should work"]`, no test, milestone closed → **0 errors**.
- A Java test file with `// @Tag("FR-01-03")` (commented out) and `@Disabled @Tag("FR-01-02")` → both counted as tagged tests.
- Python `# @pytest.mark.req("FR-02-05")` (comment) → counted; `@pytest.mark.req(\n "FR-02-04",\n)` (multi-line) → **missed**.

## Findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| 1 | MAJOR | `tools/src/fraudshield_tools/traceability.py:129-156`; `tools/src/fraudshield_tools/traceability_seed.py:428-433`; `.github/workflows/ci.yml:98-100` | Nothing checks that SRS-derived fields in `requirements.yaml` still match the SRS. Mutation d7 changed FR-01-03 from priority M to S and milestone M6 to M12, and every check stayed green. Progressive enforcement (ADR 0004) keys on `priority == "M"` and the milestone, so a one-line YAML edit exempts a Must row. Deleting a row is also undetected: `check()` only iterates the rows present and has no expected ID set. Commit 777ef87 says rows "cannot drift", which is false. | Add a `--check` mode to `traceability_seed` that rebuilds rows with `build_rows()` and fails if the ID set or any SRS-derived field (`title`, `section`, `srs_text`, `priority`, `milestone`, `defects`, `affects`) differs from the YAML. Run it in CI `governance`, `make governance` and pre-commit. Add unit tests that change a priority, change a milestone and delete a row, and assert failure. | OPEN |
| 2 | MAJOR | `tools/src/fraudshield_tools/traceability.py:190-217`; `docs/adr/0001-record-architecture-decisions.md:33-34`; `README.md:74`; `docs/walkthrough/M0.md:15-17`; `docs/walkthrough/defence_questions.md:20-24` | Evidence and deviation contents are not validated. Only non-empty lists are required, and only path-shaped strings are checked. Probes passed `DONE_WITH_DEVIATION` with `deviations: ["ADR 9999 (nonexistent)"]` and evidence `["tested"]`, and passed `DONE` via `verification: inspection` with evidence `["should work"]` and no test. ADR 0001 says `DONE_WITH_DEVIATION` rows "must name an existing ADR file; fs-traceability check enforces this", which is untrue. The README says CI fails on "unevidenced claims", which is overstated. `verification` is a free progress field, so any row can switch from `test` to `inspection` and escape the test rule. | (a) For `DONE_WITH_DEVIATION`, require at least one `deviations` entry matching `docs/adr/\d{4}-[a-z0-9-]+\.md` that exists. (b) For evidenced statuses, require at least one entry that is an existing repository path or a 7–40 hex commit SHA (D.1 step 10). Reject bare prose. (c) Allow `verification` ≠ `test` only for IDs in `VERIFICATION_OVERRIDES` (or require an ADR reference). (d) Add a unit test for each rule. (e) Correct ADR 0001, README and walkthrough wording to match what is enforced. | OPEN |
| 3 | MAJOR | `tools/src/fraudshield_tools/traceability.py:58-64, 101-117` | Tagged-test discovery is a per-line regex over source text. Commented-out tags (`// @Tag(...)`, `# @pytest.mark.req(...)`) and tags on `@Disabled` or skipped tests are counted. Multi-line `@pytest.mark.req(` decorators (the form ruff format produces for long ID lists) are missed. A Must row can therefore pass the closed-milestone rule with no test that runs, while a real test can go unseen. | Preferred: derive tags from what actually executed. Use pytest collection or a small plugin writing `{nodeid, req ids, outcome}` JSON, Surefire/JUnit Platform reports with tags (e.g. a `TestExecutionListener` writing tag JSON), and the Vitest JSON reporter. Count only passed, non-skipped tests. Minimum: strip comments before matching, parse Python with `ast` for decorator arguments, and ignore `@Disabled`/`skip`. Add tests for each case listed. | OPEN |
| 4 | MAJOR | `frontend/pnpm-workspace.yaml:1-2`; `frontend/package.json` (`"prettier": "3.9.7"`) | `minimumReleaseAgeExclude: [prettier@3.9.7]` bypasses pnpm's release-age cooldown. The npm registry shows prettier 3.9.7 published 2026-09-16T08:23Z, about 18 h before the commit. Prettier runs in CI and on the author's machine. The cooldown exists to hold back freshly published, possibly compromised releases. The bypass has no justification, ADR or expiry, and H.1 requires every dependency choice to be justified. prettier 3.9.6 (2026-07-21) is well past any cooldown. | Pin `prettier` to `3.9.6` (or another release older than the cooldown), delete the `minimumReleaseAgeExclude` entry, and re-lock with `pnpm install`. If 3.9.7 is really needed, record the reason and a removal date in an ADR and remove the exclusion once the release has aged. | OPEN |
| 5 | MAJOR | `.github/workflows/ci.yml` (no stack job); `Makefile:29-32`; D.3 M0 gate | The gate item "`make up` healthy" is unverified (daemon inactive), and no automated route exists even though GitHub runners have Docker. Several compose risks can only be settled by running the stack: findings 6 and 7, `up --wait` with the one-shot `object-store-init`, the TimescaleDB init restart window, and MLflow's S3 wiring. | Add a CI job (push to `main` and PRs to `main`, or `workflow_dispatch` to start) that runs `make env` then `docker compose --profile core up -d --wait --wait-timeout 300`, then `docker compose --profile core ps`. On failure, also run `docker compose logs`. As a smoke test, upload one artifact through MLflow to confirm the SeaweedFS bucket. Do not close M0 until this passes or the author commits `make up` output (see the gate treatment above). | OPEN |
| 6 | MINOR | `docker-compose.yml:80-108` | Kafka: (1) the named volume is mounted at `/var/lib/kafka/data`, but `KAFKA_LOG_DIRS` is not set. The broker writes to Kafka's default log dir (upstream `server.properties` has `log.dirs=/tmp/kraft-combined-logs`), so the volume is unused and data is lost when the container is recreated. (2) The healthcheck starts a JVM every 10 s that inherits `KAFKA_HEAP_OPTS=-Xms256m -Xmx512m`. It shares the 768 MiB limit with the broker (`-Xmx512m`), which risks OOM kills and wastes CPU on the 2-core reference laptop. | Set `KAFKA_LOG_DIRS: /var/lib/kafka/data` (the image creates it owned by `appuser`). Run the healthcheck as `KAFKA_HEAP_OPTS=-Xmx64m /opt/kafka/bin/kafka-broker-api-versions.sh ...`, or raise the memory limit and interval. Confirm with finding 5's CI job. | OPEN |
| 7 | MINOR | `docker-compose.yml:127-146`; commit e071a56 message | SeaweedFS: the healthcheck probes only the master's `/cluster/healthz`, not the S3 gateway (`:8333/healthz`) or the filer. `object-store-init` runs `weed shell` once, with no retry and no check that the bucket exists afterwards, so MLflow can start without `mlflow-artifacts`. `object-store-init` has no memory limit, contradicting the commit claim that "every service has a healthcheck and a memory limit". | Healthcheck `wget -qO- http://127.0.0.1:8333/healthz`. In the init container, retry creation in a bounded loop, then assert with `s3.bucket.list \| grep -q mlflow-artifacts` (exit non-zero otherwise). Add a memory limit, or correct the claim. | OPEN |
| 8 | MINOR | `.github/workflows/ci.yml` (whole file); `.pre-commit-config.yaml:5-50` | The G.5 guards (large files, datasets/model binaries/`.env`, private keys) run only in local pre-commit hooks. CI does not run them, so a clone without `make bootstrap`, or a `--no-verify` commit, bypasses them. The M0 scope says "pre-commit (Gitleaks, formatters)", but there is no TypeScript (Prettier) or Java formatter hook, and the Python hook is check-only. | Add a CI step `uv run pre-commit run --all-files --show-diff-on-failure` (with `SKIP=governance,gitleaks`, since separate jobs cover those). Add local hooks for `pnpm -C frontend exec prettier --check` and a Java format check (for example Spotless with google-java-format in check mode, or document that Checkstyle is the Java formatter gate). | OPEN |
| 9 | MINOR | `pyproject.toml:13-20`; `.github/workflows/ci.yml:44-47`; SRS 8.1 row OPS-CI-02 | Java (JaCoCo ≥ 85 %) and TypeScript (Vitest thresholds) have coverage gates. Python has no coverage measurement, although OPS-CI-02 sets ≥ 90 % for the Python ML pipeline. The gates are inconsistent across the three languages. | Add `pytest-cov` (pinned) and `--cov=fraudshield_ml --cov-fail-under=90` (and the same for `fraudshield_tools`) to CI and `make test-python`. | OPEN |
| 10 | MINOR | `ml/tests/metrics/test_operating_points.py:48-56`; `ml/src/fraudshield_ml/metrics/operating_points.py:61` | Mutant a3 (`precision > 1.0` → `> 1.5`) survives. The only unreachable-F1 case that reaches that branch, (0.95, 0.5), gives P = 9.5. There is also no test at the exact boundary P = 1. | Add `(f1=0.7, recall=0.5)` (P ≈ 1.1667) to the rejected cases. Add an accepted boundary case `f1 = 2/3, recall = 0.5` → precision 1.0, FPR 0.0. | OPEN |
| 11 | MINOR | `tools/src/fraudshield_tools/traceability.py:257-283`; `tools/tests/test_traceability.py` | `main()` is untested. Mutant d6 (stale-matrix check disabled) survives, so the rule "CI fails if the matrix is stale" has no test. | Test `main(["check", "--root", tmp])` with a hand-made stale `requirements_matrix.md` and assert return 1. Test `render` followed by `check` and assert return 0. | OPEN |
| 12 | MINOR | `frontend/src/design-system/color/contrast.test.ts:46, 60` | In `it.each` titles, `%s` consumes `_label` and `%d` then consumes `fg` (a hex string). Every D-33 test is reported as "...fails AA text contrast at **NaN**:1" (`vitest run --reporter=verbose`). These titles are traceability and demo evidence (`docs/walkthrough/demo_script.md` step 2). | Use object rows with `$label` and `$expected` in the title, or reorder the table columns so the placeholders line up. Re-check the verbose output. | OPEN |
| 13 | MINOR | `docs/adr/0003-toolchain-and-stack-versions.md:12-13, 29, 34-40, 44-48`; `.tool-versions:1`; `.github/workflows/ci.yml:24` | ADR 0003 has four problems. (1) It says the support-check commands are "in the M0 walkthrough", but they are not (`grep endoflife docs/walkthrough` finds nothing). (2) It says versions "are pinned in lockfiles", but React 19.3, MUI 9.4, `@mui/x-data-grid` 9 and Tailwind 4.3 appear in neither `package.json` nor `pnpm-lock.yaml`. They are decisions for M8, not pins. (3) The Redis options omit Redis 8's AGPLv3 option and Valkey (BSD-3), which should be weighed even if 7.2 is kept. (4) The JDK is pinned only to the major version (`java 21`, `java-version: "21"`), contrary to A.3 rule 10 "pin exact versions". I independently confirmed the Spring Boot, Spring Framework, React, Redis 7.2.16, TypeScript and typescript-eslint facts in the table (see Evidence). | Add the commands and dated outputs (or link a file under `docs/benchmarks/` or `docs/adr/evidence/`). Reword "pinned" to distinguish pinned-now from decided-for-M8. Add the Redis 8 AGPL and Valkey options with reasons. Pin the JDK patch in `.tool-versions` and CI (e.g. `temurin-21.0.12+7`), or record why the major version is enough. | OPEN |
| 14 | MINOR | `backend/pom.xml:37-45`; `backend/common/pom.xml:28-52`; `CONTRIBUTING.md:34-35`; F.3 | There is no dependency licence inventory or automated check. Build/test-scope dependencies under non-permissive licences have no recorded assessment: JUnit 5 and jqwik (EPL-2.0), Checkstyle and SpotBugs (LGPL-2.1), FindSecBugs (LGPL-3.0). jqwik 1.10.1 also prints a maintainer usage notice aimed at automated tooling on every test run, and its terms should be reviewed against how this project is built. The TimescaleDB image licence is deferred to M1 (D-49), which is acceptable. | Add licence reports to CI (e.g. `license-maven-plugin:aggregate-add-third-party`, `pnpm licenses list --prod=false`, `pip-licenses` in the uv env) with an allowlist. Write an ADR accepting EPL/LGPL for build/test scope only. Decide on jqwik explicitly: keep it with a recorded assessment, pin an earlier line, or replace it (JUnit `@ParameterizedTest` or junit-quickcheck). | OPEN |
| 15 | MINOR | `backend/common/.jqwik-database` (committed); `.gitignore:34-36` | jqwik's run-state database (Java-serialised binary) is committed and not ignored, so it will churn whenever a property fails. `.gitignore:36` `.mvn/wrapper/maven-wrapper.jar` contains a slash, so it only matches at the repository root and never matches `backend/.mvn/...` (harmless today with `distributionType=only-script`, but misleading). | `git rm --cached backend/common/.jqwik-database`. Add `**/.jqwik-database` to `.gitignore`, or set `jqwik.database = target/.jqwik-database` in `src/test/resources/junit-platform.properties`. Change the wrapper pattern to `**/.mvn/wrapper/maven-wrapper.jar`. | OPEN |
| 16 | MINOR | Repository tree vs build prompt C.5 | Many C.5 directories are missing from the pushed tree: `docs/{architecture,ml,security,compliance,runbooks}`, `contracts/{openapi,proto,kafka,webhooks}`, `tests/{performance,chaos,security,e2e,contract}`, `ml/configs`, `dataset/generator`, `frontend/src/{app,features,lib,i18n,pwa}`, `infrastructure/{k8s,grafana,prometheus,alertmanager,argo-rollouts}`. The last group exists only as empty local directories, which git does not track, so the local checkout and the remote differ. Some parent READMEs describe these directories, but nothing under `docs/` does. | Either add a short `README.md` (purpose, boundaries, delivering milestone) to each C.5 directory, or record in an ADR that each directory is created by its milestone and list them in the root README map. Remove the empty local directories to avoid confusion. | OPEN |
| 17 | NIT | commit `ba14868` | Subject is 73 characters (G.3 limit 72). | Do not rewrite published history (G.6). Keep subjects ≤ 72 from now on; a `commit-msg` hook (e.g. commitlint or gitlint) would enforce it. | OPEN |
| 18 | NIT | `.github/workflows/ci.yml:7-11` | `push: branches: ["**"]` together with `pull_request: [main]` runs the full pipeline twice for every PR update. | Accept, or limit `push` to `main` and `m*/**` and rely on `pull_request` for PRs. | OPEN |
| 19 | NIT | `tools/src/fraudshield_tools/__init__.py:5` | `REPO_ROOT = Path(__file__).resolve().parents[3]` holds only for an editable install. A non-editable install of the workspace resolves the root inside `site-packages`. | Resolve the root with `git rev-parse --show-toplevel` (fall back to the current directory), or require `--root`. | OPEN |
| 20 | NIT | `backend/common/src/test/java/.../money/MoneyTest.java:30-44` | Of the display rounding cases, only the two RWF rows tell HALF_EVEN apart from HALF_UP. There is no 2-decimal tie that rounds down under banker's rounding. `Money` also accepts negative amounts without saying so. | Add `"0.125, KES, 0.12"` and `"0.135, KES, 0.14"`. Document (or reject) negative amounts in the `Money` Javadoc. | OPEN |
| 21 | NIT | `docker-compose.yml:68` | The Redis password is passed as a `--requirepass` command-line argument, so it is visible to `docker top` and `ps` on the host. It is a local stack bound to 127.0.0.1. | Generate an ACL or config file inside the container (as done for SeaweedFS) and start `redis-server /path/redis.conf`. | OPEN |
| 22 | NIT | `.pre-commit-config.yaml:5-6` | `pre-commit-hooks` is pinned by tag (`v6.0.0`), while CI actions are SHA-pinned. | Pin by commit SHA (`pre-commit autoupdate --freeze`). | OPEN |

## Evidence reproduced (claimed vs measured)

| Claim (source) | Measured by reviewer | Match |
|---|---|---|
| 258 rows = 60 FR + 147 table rows + 51 defects (README, M0.md, commit 777ef87) | `fs-traceability check`: 258 rows; `build_rows` asserted 60+147+51 in the tools test | yes |
| FR rows copy the SRS IDs, titles and priorities | Script over every `\| FR-xx-xx \|` SRS row vs YAML: 60/60 IDs, titles and priorities identical (0 mismatches) | yes |
| Spot-check of 27 non-FR/FR rows (FR-01-03, FR-02-04, FR-03-02, FR-04-07, FR-05-06, FR-06-05, FR-07-03, NFR-PERF-09, NFR-SEC-08, NFR-REL-04, ML-GATE-01/02/03/05/06/12/13, OPS-CI-05, TEST-03, TEST-14, MOB-PWA-09, MOB-TOUCH-03, MOB-DEV-07, UX-DASH-07, UX-REG-06, RES-07, ML-DATA-07) | Text matches the SRS tables in order (7.2 rows 1–13 in SRS order; D-47 heading substitution applied). Defect links are plausible: D-01→ML-GATE-02, D-02→ML-GATE-03..06, D-05→ML-GATE-11, D-17→TEST-03, D-19→NFR-SEC-08, D-40→MOB-PWA-09, D-42→MOB-DEV-07. Milestones are plausible for the listed rows. D-43→FR-03-04 only is narrow, since currency handling also touches FR-01-02 (not a finding) | yes |
| SRS Markdown is a faithful extraction of the .docx | Word-token multiset of `word/document.xml` vs `.md`: 12,915 vs 12,957 tokens. The only difference is 42 extra `br` tokens from in-cell breaks; nothing is missing | yes |
| Defect register = Part B, 51 defects (commit 777ef87) | `fs-defect-register --check` passes; register body is byte-identical to Part B (tool test); `grep -c '^\*\*D-'` = 51 | yes |
| Precision ceiling at 1 % FPR and 0.87 % base rate = 0.4674 (M0.md, D-01) | 0.467415 (pytest, abs 1e-6); hand check 0.0087/0.018613 | yes |
| Recall 0.88 + F1 0.80 ⇒ P 0.7333, FPR 0.28 % (M0.md, D-02) | P 0.733333, FPR 0.002809 | yes |
| All 11 D-33 contrast ratios reproduce to 2 dp; 5 SRS pairs fail, 6 tokens pass | 16/16 Vitest tests pass; 14 of them fail under mutation c1 | yes (titles misreport, finding 12) |
| JaCoCo line gate 85 % enforced | `mvn verify`: "All coverage checks have been met" (common bundle, 2 classes) | yes |
| CI green for 3f82d6a | Run 35175017636 success; 5/5 jobs and all steps success | yes |
| "Every service has a healthcheck and a memory limit" (commit e071a56) | `object-store-init` has neither (a one-shot does not need a healthcheck, but the limit claim is false) | **no** (finding 7) |
| Rows "cannot drift" (commit 777ef87) | Priority/milestone edit not detected (mutation d7) | **no** (finding 1) |
| "`DONE_WITH_DEVIATION` rows must name an existing ADR file; enforced" (ADR 0001) | Non-path deviation text accepted (probe) | **no** (finding 2) |
| Support-check commands "in the M0 walkthrough" (ADR 0003) | Not present | **no** (finding 13) |
| Spring Boot 3.5 OSS EOL 2026-06-30; Spring Framework 6.2 last release 2026-06-08; Spring Boot 4.1 supported to 2027-07-31 (ADR 0003) | endoflife.date API: 3.5 eol 2026-06-30; 6.2.19 released 2026-06-08; 4.1 eol 2027-07-31, 4.1.1 released 2026-08-20 | yes |
| React 18 active support ended 2024-12; React 19.3 current | endoflife.date: 18 support 2024-12-05, latest 18.3.1 (2024-04-26); npm `react` latest 19.3.0 | yes |
| TypeScript 7 exists; typescript-eslint 8.70 supports `<6.1.0` | npm: typescript latest 7.0.2; typescript-eslint 8.70.0 peer `>=4.8.4 <6.1.0` | yes |
| MUI 9.4, `@mui/x-data-grid` 9.x MIT | npm: @mui/material 9.4.0 MIT; @mui/x-data-grid 9.13.0 MIT | yes |
| Redis 7.2.16 released 2026-08-25 | Docker Hub tag `redis:7.2.16` last_updated 2026-08-25 | yes (EOL 2029-12 not independently verified) |
| MinIO repo archived (last push 2026-04-24); no Docker Hub tags (ADR 0005) | GitHub API `archived: true`, `pushed_at` 2026-04-24T17:54:39Z; `minio/minio` object not found; `bitnami/minio` 0 tags | yes |
| MLflow full image can use PostgreSQL + S3 | `docker/Dockerfile.full` @ v3.16.0 installs `mlflow[extras,azure,db,gateway,genai,auth]` | yes |
| SeaweedFS healthcheck endpoint exists; `-s3` starts a filer | 4.47 `weed/command/master.go:257` registers `/cluster/healthz`; `weed/command/server.go:249-250` sets `isStartingFiler = true` when `-s3` | yes |
| WireMock healthcheck can use curl | wiremock-docker Dockerfile: `eclipse-temurin` base, uses `curl` and its own `HEALTHCHECK ... curl -f .../__admin/health` | yes |
| Hardware record (docs/benchmarks/hardware.md) | i5-6200U @ 2.30 GHz, `nproc` 4, 7825 MiB total, 468 G disk / 182 G free, kernel 7.0.0-31-generic, OpenJDK 21.0.12, Docker inactive | yes ("~1.8 GiB available" is point-in-time; 2.9 GiB at review) |
| Claims register quotes C-1..C-8 appear in the SRS | All eight found verbatim in `FraudShield_SRS_v1_0.md` (sections 00, 1.1, 2.3, glossary, 7.1/10) | yes |
| "Separate project by the same author" (defence_questions.md) | SRS front matter: "two separate projects developed by the same researcher" | yes |
| CITATION.cff affiliation "Independent Researcher, Kigali, Rwanda" | SRS front matter states the same | yes |
| `make up` healthy (D.3 gate) | Not executable: Docker daemon inactive | **not verified** |

## Residual risks

- **Local stack never started.** Healthchecks, `up --wait` with the one-shot `object-store-init`
  (depending on the Compose version, `--wait` may report an exited one-shot container as a
  failure), the TimescaleDB first-boot restart window (MLflow can connect while the init server
  restarts; `restart: unless-stopped` should recover), total memory (~3.5 GiB of limits on a
  7.8 GiB laptop), and MLflow → SeaweedFS artifact upload are all judged by inspection only.
  Finding 5 closes this.
- **Per-commit greenness unverified.** CI ran only on the branch head. G.3 requires each commit
  to build and pass tests for the component it touches.
- **Branch protection absent.** `gh` is unauthenticated, so "no merge with failing checks" rests
  on discipline (ADR 0002). The author should enable protection on `main` requiring all five
  `ci` jobs once `main` exists.
- **Container images are pinned by tag, not digest** (acknowledged in ADR 0003 for M9). A
  re-tagged image would change the stack silently.
- **Dev-stack security posture is deliberately weak and must not leak into M9 manifests:**
  Kafka PLAINTEXT with no auth, Redis without TLS (D-20 requires TLS in production), MLflow
  without auth, and every service, including `pii-vault`, on one flat network. Network
  isolation of the PII vault (D-20) is not exercised locally.
- **Governance can still be bypassed by hand edits** to `docs/traceability/milestones.yaml`
  (`completed`) or to `verification`. Diffs must be reviewed until findings 1–3 are fixed.
- **Scope guard is lexical.** It cannot catch concatenated strings or obfuscated identifiers;
  review remains the backstop for D-47.
- **Claims C-1..C-8 remain UNVERIFIED** and depend on the author supplying primary sources (D-09).
  No public-facing text currently states them, which is correct.
