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

---

# Re-review: M0 fixes (branch `m0/bootstrap`, 14 commits 3f82d6a..cd99dc9)            Reviewer role: Principal Reviewer
Date: 2026-09-17 (UTC)   Branch/commit: m0/bootstrap @ cd99dc9ad7dfcd5f1a68b0d8b914025dee33647e   Requirements: ML-GATE-02..04, OPS-CI-01, OPS-CI-02 (M0 scope)   Defects: D-01, D-02, D-17, D-33, D-43, D-47, D-48, D-51
Verdict: APPROVED_WITH_MINORS

Summary: all 5 MAJOR and 11 MINOR findings from the first review, plus the 7 owner findings, were
checked against the code rather than against `m0-bootstrap-response.md`. Every earlier MAJOR is
fixed, and I re-ran the evasions to confirm. Two earlier findings (2 and 3) are only partly
fixed: the traceability checker can still be satisfied by weak or fabricated evidence, and test
discovery still counts some skipped tests. Those gaps and six other new issues are tracked below
(R-1 … R-10). **Open findings: 0 BLOCKER, 0 MAJOR, 6 MINOR, 4 NIT.**

## Checks re-run (command → result)

| Command | Result |
|---|---|
| `make ci` (repo root, `export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`) | exit 0, 1 m 53 s wall |
| ↳ ruff check / format | All checks passed / 22 files already formatted |
| ↳ mypy --strict | no issues in 20 source files |
| ↳ `cd tools && uv run pytest -q` | 69 passed; branch coverage 94.60 % (gate 90 %) |
| ↳ `cd ml && uv run pytest -q` | 14 passed; coverage 100 % (gate 90 %). 8 further consecutive runs: 14 passed each (the author's reported one-off failure did not reproduce) |
| ↳ `./mvnw -B -ntp verify` | BUILD SUCCESS; Spotless clean; Checkstyle 0; 1028 tests (1000 seeded + edges), 0 failures; SpotBugs 0; JaCoCo gate met |
| ↳ frontend lint / format / typecheck / `test:coverage` | clean; 16 passed; 100 % coverage. Verbose titles now read "…fails AA text contrast at 3.19:1" |
| ↳ `fs-defect-register --check` | up to date (51) |
| ↳ `fs-traceability-seed --check` | up to date |
| ↳ `fs-traceability check` | 258 rows, 21 tagged tests, 0 errors |
| ↳ `fs-scope-guard` | 0 files |
| ↳ `docker compose … config -q` | exit 0 |
| ↳ `tools/bin/gitleaks git` / `dir` | 23 commits, no leaks / no leaks |
| ↳ `fs-licences` | 187 dependencies (runtime 0, dev 187), 0 violations |
| `uv run fs-commit-msg --rev-range 3f82d6a..HEAD` | 14 messages, 0 problems; no Co-Authored-By or tool signatures (`git log --format=%B` grep) |
| `tools/bin/gitleaks` pin vs upstream `gitleaks_8.30.1_checksums.txt` | `551f6fc8…70eb` matches. A copy of the launcher with a wrong checksum, run against an empty cache, exited 1 with "checksum mismatch" |
| O-7: SRS FR rows parsed per section | FR-01 7, FR-02 10, FR-03 8, FR-04 12, FR-05 7, FR-06 7, FR-07 9 = 60; every section contiguous; YAML FR set = SRS set; priorities M 47 / S 12 / C 1 |
| O-4: ADR 0007 versions vs Maven Central | `spring-boot-dependencies-4.1.1.pom`: Flyway 12.4.0, Testcontainers 2.0.5, Jackson 3.1.5 / 2.21.5, Security 7.1.1, Framework 7.0.9, JUnit 6.0.3, Kafka 4.2.1, spring-kafka 4.1.1, Lettuce 7.5.2, pgjdbc 42.7.13, Micrometer 1.17.1, Thymeleaf 3.1.5. springdoc-openapi 3.1.1 parent = spring-boot-starter-parent 4.1.0. resilience4j-spring-boot4 2.4.0 → spring-boot-autoconfigure 4.0.0. **All match** |
| O-4: ADR 0005 MinIO mentions | `grep -ci minio` on the build prompt = 3, matching the ADR table |
| O-1: remote state | `git ls-remote origin` shows only `refs/heads/m0/bootstrap` @ cd99dc9; there is no `main`; the GitHub API reports `default_branch: m0/bootstrap` |
| Action pin `actions/upload-artifact` v7.0.1 | tag → commit 043fb46…, matches the pin |
| `git status --short` after all runs and mutations | clean |

**GitHub Actions for cd99dc9: run 35179479949** (push, attempt 1): completed / **success**.

| Job | Conclusion |
|---|---|
| python (ruff, mypy --strict, pytest) | success |
| java (checkstyle, junit, spotbugs, jacoco) | success |
| frontend (tsc, eslint, prettier, vitest) | success |
| traceability-check, defect register, scope guard, commit messages | success |
| dependency licence inventory (ADR 0009) | success |
| pre-commit hooks on all files (G.5 guards) | success |
| core compose stack healthy + smoke test (M0 gate) | success (03:47:34Z → 03:49:15Z) |
| gitleaks | success |

`stack` job history on this branch (public API): 35176401589 @ 485bdf8 failure ("Functional
smoke test"); 35177597483 @ ce4c10d failure ("make up"); 35177814042 @ ce6df6a failure
("make up"); 35178368063 @ 4c7a81e cancelled; 35178432654 @ 162cc44 failure ("make up"; the
annotation shows `mlflow running health=starting`); **35178641969 @ 2882a99 success;
35179479949 @ cd99dc9 success.** Result: two consecutive green runs after the MLflow
worker/memory fix, following three failures before it.

## Mutation and evasion checks (what was broken → what caught it)

Every edit was reverted with `git checkout -- <file>` (scratch files deleted), and `git status --short` was clean afterwards.

Repeats of the first review's survivors and evasions:

| # | Change | Result |
|---|---|---|
| R1 | `operating_points.py:61` `precision > 1.0` → `> 1.5` | **killed**: `test_precision_just_above_one_is_rejected` |
| R2 | `traceability.py:296` stale-matrix check disabled | **killed**: `test_main_check_fails_on_stale_matrix_and_passes_after_render` |
| E1 | `requirements.yaml` FR-01-03 priority M→S, milestone M6→M12, matrix re-rendered | **detected**: `fs-traceability-seed --check` exit 1 (`fs-traceability check` alone still passes, which is by design) |
| E2 | FR-01-04 row deleted | **detected**: seed check exit 1 |
| E3 | D-47 `DONE_WITH_DEVIATION`, evidence "tested", deviation "ADR 9999 (nonexistent)" | **detected**: 2 errors (evidence form; deviation not an existing ADR) |
| E4 | D-47 `DONE`, `verification: inspection`, evidence "should work" | **detected**: 2 errors (verification not permitted; evidence form) |
| E5 | Java tag in `// comment` and on a `@Disabled` test; multi-line `@pytest.mark.req(` | **handled**: comments and `@Disabled` ignored, multi-line decorator found (`tools/tests/test_test_tags.py` and my probes) |

New attempts against the new code:

| # | Target | Change or probe | Result |
|---|---|---|---|
| N1 | `traceability.py` evidence | D-47 `DONE`, evidence = `https://github.com/mariusbayizere/fraudshield/actions/runs/1` (fabricated) | **passes all checks** (R-1) |
| N2 | same | D-47 `DONE`, evidence = unrelated existing commit `89d6859` | **passes all checks** (R-1) |
| N3 | `scope_guard.py` allowlist | a banned D-47 term in the `notes:` progress field of FR-04-01 in `requirements.yaml` | **passes** seed check, traceability check and scope guard (R-2) |
| N4 | `scope_guard.py` pragma | `frontend/src/probe.ts`: exported PascalCase identifier built from a banned term, followed by `// scope-guard: allow D-47` | **passes**: the pragma works in production code (R-2) |
| N5 | `scope_guard.py` allowlist | file under `docs/reviews/` whose *path and content* contain a banned term | **passes** (R-2) |
| N6 | `test_tags.py` | `@pytest.mark.skipif(True)`, module-level `pytestmark = pytest.mark.skip`, `from pytest import mark` + `@mark.skip`, `@pytest.mark.xfail(run=False)` | all **counted as tagged tests** (R-3) |
| N7 | `test_tags.py` | Java `@org.junit.jupiter.api.Disabled` (fully qualified); `@Disabled @Nested` inner class; `@EnabledIf` that never runs; `@Tag` on a private non-test helper | all **counted** (R-3) |
| N8 | `test_tags.py` | TS `describe.skip` wrapping a tagged `it` (documented limitation); tag text inside a string literal | both **counted** (R-3) |
| N9 | `licences.py` | runtime dep with `License-Expression: GPL-3.0-only` plus classifier `MIT License` | **no violation** (R-4) |
| N10 | `licences.py` | dev dep `LGPL-2.1-or-later` | **violation "unidentified licence"**, although ADR 0009 lists it as allowed for dev (R-4) |
| N11 | `commit_msg.py` | `feat: updated things`; indented ` Co-authored-by:`; `Generated-by: Claude Code`; `Signed-off-by: Claude <noreply@anthropic.com>` | all **accepted** (R-8) |
| M1 | `licences.py:112` runtime uses dev allowlist | **killed**: `test_policy_by_scope`, `test_main_writes_inventory_and_fails_on_violation` |
| M2 | `licences.py:205` Maven declared-count check removed | **killed**: `test_parse_third_party_report_verifies_count` |
| M3 | `commit_msg.py:137` `MAX_LINE` 72→73 | **killed** (2 cases) |
| M4 | `commit_msg.py:165` trailer check removed | **killed**: co-author case |
| M5 | `test_tags.py:499` TS skip/todo modifiers ignored | **killed** |
| M6 | `test_tags.py:488` Java declaration `@Disabled` ignored | **killed** |
| M7 | `test_tags.py:382` Python `skip` ignored | **killed** |
| M8 | `scope_guard.py:36` pragma loosened to `scope-guard: allow` | **killed**: `test_allowlist_and_line_pragma` |
| M9 | `scope_guard.py:53` every allowlist entry prefix-matched (`requirements.yaml.bak` etc. exempt) | **SURVIVED** (R-2) |
| M10 | `traceability.py:75` CI run URL accepted for any GitHub repository | **SURVIVED** (R-1) |
| M11 | `traceability.py:220` commit existence not checked | **killed**: `test_unknown_commit_in_evidence_fails` |
| M12 | `traceability_seed.py` `--check` comparison disabled | **killed**: both `test_seed_check_detects_edited_or_deleted_rows` cases |

## Findings

### Status of the first review's findings

| # | Severity | Status | Verified by reviewer |
|---|---|---|---|
| 1 | MAJOR | RESOLVED | E1, E2 detected; M12 killed; `fs-traceability-seed --check` runs in CI `governance`, `make governance` and the pre-commit `governance` hook |
| 2 | MAJOR | PARTIALLY_RESOLVED | Free text, missing ADRs and non-granted verification methods are rejected (E3, E4). Fabricated run URLs and unrelated commits still pass (N1, N2, M10), tracked as R-1. Downgraded to MINOR because no row is DONE yet and D.1 step 10 still requires review of the commit SHA |
| 3 | MAJOR | PARTIALLY_RESOLVED | AST/comment-aware discovery works for the common forms (E5, M5–M7 killed). Several skip/disable forms are still counted (N6–N8), tracked as R-3. Downgraded to MINOR because no milestone is closed and ADR 0004 commits to executed-report tags in M9 |
| 4 | MAJOR | RESOLVED | `frontend/pnpm-workspace.yaml` deleted; `prettier` 3.9.6 in `package.json` and lockfile; `pnpm install --frozen-lockfile` green in CI (frontend and licences jobs) |
| 5 | MAJOR | RESOLVED | CI `stack` job (`make up --wait` + `make smoke`) green in runs 35178641969 and 35179479949 |
| 6 | MINOR | RESOLVED | `KAFKA_LOG_DIRS=/var/lib/kafka/data`; healthcheck `KAFKA_HEAP_OPTS=-Xmx64m`; stack green |
| 7 | MINOR | RESOLVED | healthcheck probes `:9333/cluster/healthz` and `:8333/healthz`; init retries 10×, then asserts the bucket; 128m limit; smoke test asserts `object-store-init` exit 0 and an MLflow artifact round-trip |
| 8 | MINOR | RESOLVED | CI `pre-commit` job green; Prettier and Spotless hooks; Spotless check bound to Maven `validate` (seen in `make ci`) |
| 9 | MINOR | RESOLVED | `--cov-fail-under=90` branch coverage in both packages: 94.60 % / 100 % |
| 10 | MINOR | RESOLVED | R1 killed; exact P = 1 case accepted (computed P = 0.9999999999999998, so the test is deterministic) |
| 11 | MINOR | RESOLVED | R2 killed |
| 12 | MINOR | RESOLVED | verbose titles show the ratios |
| 13 | MINOR | RESOLVED | evidence commands, pinned/decided/deferred split, Redis 8 AGPL and Valkey options; Temurin 21.0.12 in `.tool-versions` and CI (see R-9) |
| 14 | MINOR | RESOLVED | inventory runs in CI and `make ci`; build plugins assessed in ADR 0009; jqwik decision recorded. New defects in the tool: R-4; jqwik vs D-17: R-5 |
| 15 | MINOR | RESOLVED | `.jqwik-database` removed; `**/.mvn/wrapper/maven-wrapper.jar` |
| 16 | MINOR | RESOLVED | `docs/architecture/repository_layout.md`; the empty local directories are gone |
| 17 | NIT | RESOLVED | `fs-commit-msg` hook and CI step; 14 new commits pass (quality of the rule: R-8) |
| 18 | NIT | RESOLVED (accepted with reason) | reason is sound (no `gh`, branches need CI without PRs) |
| 19 | NIT | RESOLVED | `git rev-parse --show-toplevel` with fallback |
| 20 | NIT | RESOLVED | tie cases 0.125→0.12, 0.135→0.14, −0.125→−0.12; negative amounts documented |
| 21 | NIT | RESOLVED | password moved to `/tmp/redis.conf` (umask 077); smoke test checks authenticated and unauthenticated access |
| 22 | NIT | RESOLVED | pre-commit-hooks frozen at `3e8a870` |

### Status of the repository owner's findings

| # | Severity | Status | Verified by reviewer |
|---|---|---|---|
| O-1 | BLOCKER | PARTIALLY_RESOLVED (owner action) | No `main` and no earlier "first commit" on the remote (`git ls-remote`), so nothing needs integrating: creating `main` from the approved head by fast-forward is correct and needs no force. **The default branch is still `m0/bootstrap`** (API). Setting it to `main` and enabling protection is an owner action once `main` is pushed. It does not block the merge itself |
| O-2 | MAJOR | RESOLVED | same evidence as finding 4 |
| O-3 | BLOCKER for tagging | RESOLVED for merge; tag condition below | CI `stack` job green twice. The job runs on GitHub's runner, not on the reference laptop (residual risk) |
| O-4 | MAJOR | RESOLVED | ADR 0007 versions match Maven Central; the Resilience4j Boot 4.0 build is correctly flagged as a risk; the ADR 0005 table covers all 3 MinIO mentions |
| O-5 | MINOR | RESOLVED, with new gaps R-2 | allowlist has reasons; pragma must name D-47 (M8 killed); defence question restored |
| O-6 | MINOR | RESOLVED | checksum matches upstream; mismatch rejected; the CI `secrets` job runs full history independently. Cache trust: R-7 |
| O-7 | — | RESOLVED | counts reproduced exactly (see Checks) |

### New findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| R-1 | MINOR | `tools/src/fraudshield_tools/traceability.py:74-75, 214-222` | Evidence is checked for form, not substance. Any URL matching `…/fraudshield/actions/runs/\d+` passes without checking that the run exists or succeeded (N1: run ID `1`). Any commit in the object store passes, even an unrelated or unreachable one (N2). The repository-specific URL regex has no test (M10 survived). This leaves open the "fabricated or unreproducible evidence" hole that finding 2 was about. | Require commit SHAs to be ancestors of `HEAD` (`git merge-base --is-ancestor`). For CI run URLs, either validate them in the CI `governance` job through the Actions API (run exists, `conclusion == success`, `head_sha` in history) or drop URLs and require a SHA. Add tests for a foreign-repository URL, a non-ancestor commit and a non-existent run. | OPEN |
| R-2 | MINOR | `tools/src/fraudshield_tools/scope_guard.py:23-36, 51-54, 67`; `docs/adr/0008-out-of-scope-content-guard.md` | Three gaps in the D-47 guard. (a) `requirements.yaml` is allowlisted on the grounds that "only the D-47 row carries these terms", but progress fields (`notes`, `evidence`, `implementation`, `blocked_reason`, `reduced_scale`) are neither generated nor scanned (N3). (b) The pragma exempts a line in any file, including production code (N4), whereas ADR 0008 limits it to sentences discussing the defect. (c) `docs/reviews/` is exempt for paths as well as content (N5), and file entries are prefix-safe only by an untested branch (M9 survived). | Scan the progress fields of every YAML row (and render only scanned values into the matrix). Accept the pragma only in `*.md` under `docs/` (or require the line to also contain `D-47` outside the pragma). Keep checking file paths inside allowlisted directories. Add tests for each case and for `requirements.yaml.bak`. | OPEN |
| R-3 | MINOR | `tools/src/fraudshield_tools/test_tags.py:377-411, 479-491, 494-520` | Tests that never run are still counted as evidence: `skipif(True)`, module-level `pytestmark = pytest.mark.skip`, `from pytest import mark` / `@mark.skip`, `xfail(run=False)`; Java fully qualified `@org.junit.jupiter.api.Disabled`, `@Disabled` on a `@Nested` class, `@EnabledIf`/`@DisabledIf`, `@Tag` on non-`@Test` methods; TS `describe.skip` parents and tag text inside string literals (N6–N8). Only runtime skips are documented as a limitation. | Before any milestone closes (M1 at the latest): handle the listed static forms, or only count Java tags on methods annotated `@Test`/`@ParameterizedTest`/`@RepeatedTest`/`@TestFactory`, and treat any `skip*`/`xfail(run=False)`/`pytestmark` skip as disabled. Keep the M9 plan to count only passed tests from executed reports, and bring it forward if feasible. | OPEN |
| R-4 | MINOR | `tools/src/fraudshield_tools/licences.py:44-48, 53-72, 92-100, 151-156`; `docs/adr/0009-dependency-licence-policy.md:36-37` | (a) `spdx_options` takes the union of every metadata field, so conflicting metadata passes. A runtime package whose `License-Expression` is `GPL-3.0-only` and which carries an MIT classifier is accepted (N9). (b) `LGPL-2.1-or-later`/`LGPL-3.0-or-later` are in `DEV_ONLY` but have no normaliser pattern, so they are always "unidentified" (N10), contradicting ADR 0009. (c) The classifier `BSD License` is assumed to be BSD-3-Clause, and the unanchored `permission is hereby granted, free of charge` search can match non-MIT text. Runtime scope is empty today, so there is no current violation. | Give `License-Expression` precedence when present and treat classifiers as fallback only. Parse `AND` expressions as requiring every term. Add LGPL/GPL/AGPL normalisers so copyleft is identified and rejected or allowed explicitly. Map a bare `BSD License` to "unidentified" unless an exception is recorded. Add tests for each. | OPEN |
| R-5 | MINOR | `docs/adr/0009-dependency-licence-policy.md:6, 48-52`; `backend/README.md:19`; build prompt D-17 (line 124) and E.12 (line 524) | jqwik removal deviates from **Part B binding resolution D-17** ("JUnit 5 (plus jqwik property tests)"), not only from E.12. Under A.4, an ADR ranks below Part B, yet ADR 0009 lists "Defects referenced: —" and does not argue from A.4 item 1. The replacement (1000 fixed-seed cases) is deterministic example testing: generation never varies between runs and failures do not shrink, so it is weaker than the property testing D-17 and E.12 ask for. `backend/README.md:19` still says "JUnit 5 + jqwik". The removal itself is defensible given the maintainer's notice aimed at automated agents. | Amend ADR 0009 (or write a dedicated ADR): reference D-17 and E.12, state the precedence argument (A.4 item 1, a legal/usage-terms risk), and name the property-testing approach for the M6 decision engine (for example a per-run random seed printed on failure for reproduction, with fixed regression seeds kept). Add D-17 to the ADR's defects and to the D-17 row's `deviations` when it closes. Fix `backend/README.md:19`. | OPEN |
| R-6 | MINOR | commit `4c7a81e` (`.pre-commit-config.yaml`) | This commit configures hooks that call `tools/bin/gitleaks` and `uv run fs-commit-msg`, both added one commit later in `162cc44`. With hooks installed, that commit blocks every commit. Its subject ("ci: annotate unhealthy stack services…") does not mention the pre-commit changes. CI for it was cancelled, so it was never verified. This breaks G.3 ("each commit builds…", coherent scope). | Do not rewrite published history (G.6). Record this in the merge description. From now on, keep hook changes in the commit that introduces what they call, and run `pre-commit run --all-files` before each commit, not only on the branch head. | OPEN |
| R-7 | NIT | `tools/bin/gitleaks:24` | The checksum is verified only on first download. Afterwards any executable at `~/.cache/fraudshield/gitleaks-8.30.1-linux_x64/gitleaks` is trusted and run. | Also pin the extracted binary's SHA-256 and verify it on every run, or keep the verified archive and re-check before `exec`. | OPEN |
| R-8 | NIT | `tools/src/fraudshield_tools/commit_msg.py:138-141` | Accepted: `feat: updated things` (non-imperative placeholder), an indented ` Co-authored-by:`, `Generated-by: Claude Code`, and `Signed-off-by: Claude <noreply@anthropic.com>` (N11). G.1 rule 3 forbids tool-generated signatures. | Match trailers with optional leading whitespace. Forbid `Generated-by`/`Assisted-by` and trailers naming known tool identities or `noreply@anthropic.com`. Extend the placeholder check to inflected forms (`updated`, `changes`). Add tests. | OPEN |
| R-9 | NIT | `docs/adr/0003-toolchain-and-stack-versions.md:18`; `.github/workflows/ci.yml:24` | The ADR says the latest Temurin GA is 21.0.12+8. The Adoptium API lists `21.0.12+101.0.LTS` (a respin) above `+8`, and CI pins `21.0.12` without a build number, so the resolved build can change. | Pin the exact build (e.g. `21.0.12+8`, or `+101` after checking), or reword the ADR to say the patch level is pinned and the build is not. | OPEN |
| R-10 | NIT | `infrastructure/docker/scripts/smoke-test.sh:82` | The object-store listing of `/buckets/mlflow-artifacts` is printed but not asserted, so the smoke test does not independently prove the artifact landed in SeaweedFS rather than being served some other way. The MLflow round-trip does exercise the proxied S3 path. | Pipe to `grep -q smoke.txt` (or query the S3 API directly with the generated credentials) and fail otherwise. | OPEN |

## Evidence reproduced (claimed vs measured)

| Claim (response document) | Measured | Match |
|---|---|---|
| Seed check catches priority/milestone edits and deleted rows (finding 1) | E1, E2 exit 1; M12 killed | yes |
| Evidence must be an existing path, commit or CI run; "tested", "should work", "ADR 9999" rejected (finding 2) | E3, E4 rejected; fabricated URL and unrelated commit accepted | partly (R-1) |
| Comments and disabled tests are ignored (finding 3) | common forms yes; N6–N8 not | partly (R-3) |
| `pnpm install --frozen-lockfile` passes the release-age policy (finding 4) | CI frontend and licences jobs green; exclusion file deleted | yes |
| Stack job green for the branch head (finding 5 / O-3) | run 35179479949 `stack` success; also 35178641969 @ 2882a99 | yes |
| tools 94.60 %, ML 100 % coverage (finding 9) | 94.60 % / 100.00 % | yes |
| `> 1.0` → `> 1.5` mutant now fails `test_precision_just_above_one_is_rejected` (finding 10) | reproduced | yes |
| D-33 titles show ratios (finding 12) | "…at 3.19:1" | yes |
| 187 dependencies (144 npm, 25 Python, 18 Maven), 0 violations (finding 14) | 187, runtime 0 / dev 187, 0 violations (per-ecosystem split not re-derived) | yes |
| New commits pass `fs-commit-msg`; only ba14868 violates (finding 17) | 14 checked, 0 problems | yes |
| Gitleaks SHA-256 551f6fc8… matches release; wrong checksum rejected (O-6) | matches upstream checksums file; mismatch exit 1 | yes |
| 60 FRs: 7/10/8/12/7/7/9; M 47, S 12, C 1 (O-7) | identical | yes |
| ADR 0007 BOM and library versions (O-4) | identical (see Checks) | yes |
| ADR 0005: three MinIO occurrences (O-4) | 3 | yes |
| `hardware.md`: core memory limits total 3,968 MiB | 768+256+320+768+384+128+1024+64+256 = 3,968 | yes |
| `origin/main` does not exist (O-1) | `git ls-remote`: only `m0/bootstrap`; default branch `m0/bootstrap` | yes |
| One ML test failure did not reproduce | 9 consecutive runs green | consistent |
| Committed copy of the first review is unaltered | `git show HEAD:docs/reviews/M0/m0-bootstrap.md` identical to the reviewer's file (md5 9e2b0a30…) | yes |

## Residual risks

- **Stack health rests on two green CI runs** on GitHub's `ubuntu-24.04` runner, after three
  failures caused by MLflow worker deaths under the memory limit. Flakiness is not ruled out, and
  the stack has still never run on the 2-core / 7.8 GiB reference laptop, where roughly
  3.9 GiB of limits compete with 2–3 GiB of free memory. The workflow has no
  `workflow_dispatch`, so extra runs require new pushes.
- **Traceability evidence** can still be satisfied by plausible-looking but irrelevant references
  (R-1) and by tests that never run (R-3). Until those are fixed, human review of every row moved
  to DONE is the control.
- **The D-47 guard** can be bypassed with the pragma in code, in `requirements.yaml` progress
  fields, and under `docs/reviews/` (R-2).
- **Java property testing** is currently deterministic example testing (R-5). The decision engine
  (M6) needs real generative coverage.
- **Resilience4j Spring Boot 4 starter** is built against Boot 4.0.0 (ADR 0007); the risk is
  accepted until the M6 integration tests.
- **Branch protection and default branch** depend on the owner (O-1); nothing server-side stops a
  red merge yet.
- **Image digests** are still tag-pinned (deferred to M9); the dev stack stays deliberately
  insecure (plaintext Kafka, no Redis TLS, flat network including the PII vault).

## Merge and tag decision

**Merge to `main`: permitted.** No BLOCKER or MAJOR finding is open, and the verdict is
`APPROVED_WITH_MINORS`. Conditions:

1. Commit this review record on `m0/bootstrap`. Wait for that commit's CI run to finish with
   **all 8 jobs green, including `stack`**. Then create `main` by fast-forward from that commit
   and push without force (the remote has no `main`, so there is no history to integrate).
2. Record R-1 … R-10 as tracked issues: GitHub issues once `gh` is authenticated, or until then a
   tracked list in the merge description and `docs/walkthrough/M0.md` open items. The merge
   description (G.4) must note the non-self-consistent commit `4c7a81e` (R-6).

**Tag `m0-complete`: not yet.** Tag only after all of the following:

1. The CI run for the push to `main` (the tagged commit) is green, including `stack`. That
   satisfies the gate items "`make up` healthy", "CI green" and "traceability-check runs" on the
   exact commit being tagged.
2. The milestone-level review (I.3) is written to `docs/reviews/M0/milestone-review.md` from a
   clean worktree of `main`, with verdict `APPROVED` or `APPROVED_WITH_MINORS`.
3. `M0` is added to `docs/traceability/milestones.yaml` `completed` in the closing commit.
   `fs-traceability check` must then pass: the M0 Must rows D-47 and D-48 need a final status and
   valid evidence, and D-47 needs its tagged test.
4. The owner sets `main` as the default branch and enables branch protection requiring the 8 `ci`
   jobs. If that cannot be done immediately, record it as an open owner action in the M0 status
   block. It is not a code condition for the tag, but O-1 stays open until it is done.
5. R-3 is fixed before any later milestone is closed, since that is the first time the
   closed-milestone test rule depends on discovery accuracy. The other minors may follow in M1.

---

# Delta review (2018bec..b08ba61)            Reviewer role: Principal Reviewer
Date: 2026-09-17 (UTC)   Branch/commit: m0/bootstrap @ b08ba61c7a9bef2a36899bb277ed784575849023 (commits c772a97, 426d0f8, 2f9f864, 4238698, b08ba61)   Requirements: OPS-CI-01..04, NFR-REL-01 (note only), benchmark rows per ADR 0010   Defects: D-17, D-47, D-51
Verdict: CHANGES_REQUIRED

Scope: the author's fixes for R-1 … R-10, plus new work following the owner's decision that
Docker will not run on the laptop (ADR 0010, devcontainer, Docker gating, compose memory budget,
benchmark evidence rules).

Summary: the fixes for R-1, R-2, R-4, R-5, R-6, R-7, R-9 and R-10 hold, and every evasion that
worked in the re-review is now caught. The local Docker gating is correct: skips are visible, and
`REQUIRE_DOCKER` fails when Docker is missing. The three required consecutive green `stack` runs
exist. However, **the new devcontainer workflow failed on its first run** (post-create command),
so the Codespaces environment the README tells the author to use has not been shown to work, and
merging would put a red workflow on `main`. That one MAJOR finding requires changes. The
benchmark rule does not yet stop a CI-runner number from closing a benchmark row. **Open findings: 0
BLOCKER, 1 MAJOR, 4 MINOR, 5 NIT.**

## Checks re-run (command → result)

| Command | Result |
|---|---|
| `make ci` (repo root, `export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`; Docker daemon inactive) | exit 0, 1 m 40 s |
| ↳ ruff / format / mypy | clean; 32 files formatted; mypy no issues in 30 files |
| ↳ tools pytest | 131 passed; branch coverage 92.94 % (gate 90 %) |
| ↳ ml pytest | 14 passed; 100 % |
| ↳ `make test-java` | printed `SKIPPED: JUnit tests tagged requires-docker require Docker, verified in CI (job: java)`, then `verify -DexcludedGroups=requires-docker`: 1028 tests, 0 failures, BUILD SUCCESS |
| ↳ frontend | 16 passed |
| ↳ governance | defect register current; seed up to date; `traceability-check: 258 rows, 22 tagged tests, 0 errors, 0 warnings`; scope-guard 0; `compose-budget: core 3968/4096 MiB, ml 0/6144, obs 0/5120, full 3968/10240; 0 errors` |
| ↳ secrets-scan | 29 commits, no leaks; dir no leaks |
| ↳ licences | 187 dependencies (runtime 0, dev 187), 0 violations |
| ↳ `stack-test` | printed `SKIPPED: stack-test requires Docker, verified in CI (job: stack)` |
| `REQUIRE_DOCKER=1 make stack-test` | `ERROR: stack-test requires Docker; REQUIRE_DOCKER is set but no Docker daemon is reachable`, make exit 2 |
| `REQUIRE_DOCKER=1 make test-java` | `ERROR: JUnit tests tagged requires-docker need Docker; REQUIRE_DOCKER is set`, make exit 2 |
| `REQUIRE_DOCKER=0 tools/bin/docker-gate …` / `REQUIRE_DOCKER=` (empty) | exit 1 (any non-empty value means required) / SKIPPED exit 0 |
| pytest plugin, scratch module with one `@pytest.mark.requires_docker` test | no env: skipped with reason and summary line `SKIPPED: 1 test(s) require Docker, verified in CI`. `REQUIRE_DOCKER=1`: exit 4, UsageError. `REQUIRE_DOCKER=1 -m "not requires_docker"`: exit 4 (still fails). Plugin disabled with `--strict-markers` (the repository's setting): exit 2, unknown marker |
| `uv run fs-commit-msg --rev-range 2018bec..b08ba61` | 5 messages, 0 problems |
| Devcontainer pins, upstream | base image `3.0.8-ubuntu24.04` digest `sha256:d7c46867…d724` matches MCR; features `docker-in-docker:4.1.1`, `java:1.8.3`, `node:2.1.0` exist on GHCR; `devcontainers/ci` tag v0.3.1900000450 → commit 513af61, matches; uv 0.12.15 tarball SHA-256 `f9793576…a638` matches the release `.sha256` |
| `git status --short` after every mutation and probe | clean |

**GitHub Actions for b08ba61** (public API, 2026-09-17 04:23–04:26Z):

| Run | Workflow | Conclusion | Jobs |
|---|---|---|---|
| **35181726185** | ci | **success** | gitleaks ✔, java ✔, licences ✔, **stack ✔ (04:23:13Z → 04:24:53Z)**, pre-commit ✔, python ✔, governance ✔, frontend ✔ |
| **35181726205** | devcontainer | **failure** | `build devcontainer, post-create make ci, smoke test inside`: step 3 `devcontainers/ci` failed after ~3 min. Annotations: "Dev container up failed: Command failed: /bin/sh -c bash .devcontainer/post-create.sh … postCreateCommand from devcontainer.json failed." Logs need authentication, so I could not identify which command failed |

**Consecutive `stack` runs** (ci workflow on `m0/bootstrap`, newest first): 35181726185 @ b08ba61
success; 35179479949 @ cd99dc9 success; 35178641969 @ 2882a99 success; 35178432654 @ 162cc44
failure. Commit 2018bec was pushed together with later commits and has no run of its own.
**Three consecutive green `stack` runs exist.** `docker-compose.yml` is unchanged across them (the
last change is 2882a99); `smoke-test.sh` became stricter in 4238698 and passed.
`docs/reviews/M0/stack-gate-evidence.md`, which ADR 0010 requires, does not exist yet (DR-5).

## Mutation and evasion checks (what was broken → what caught it)

Every edit was reverted with `git checkout -- <file>`, scratch files were deleted, and `git status --short` was clean afterwards.

Repeats of the evasions that succeeded in the re-review:

| # | Attempt | Result |
|---|---|---|
| R-1/N1 | D-47 `DONE` with fabricated run URL `…/actions/runs/1` | **caught in CI**: without a token it is a `WARNING` locally (exit 0); with `GITHUB_ACTIONS=true` it is an `ERROR`. B3 (downgrade to warning in CI), B4 (ancestor check replaced by existence) and B5 (run head_sha history check removed) are all **killed** |
| R-1/N2 | unrelated but ancestor commit as evidence | accepted, **by design** (ancestry is what was asked for); relevance remains a review control |
| R-2/N3 | banned term in `notes:` of FR-04-01 | **caught**: scope guard reports `requirements.yaml` |
| R-2/N4 | banned PascalCase identifier with `// scope-guard: allow D-47` in `frontend/src/probe.ts` | **caught** |
| R-2/N5 | banned term in a file *path* under `docs/reviews/` | **caught** (content under `docs/reviews/` stays exempt by design) |
| R-2/M9 | allowlist entries prefix-matched (`rel.startswith(entry)`) | **SURVIVED again** (DR-6) |
| R-3/N6–N8 | `skipif(True)`, module `pytestmark` skip, `from pytest import mark` + `@mark.skip`, `xfail(run=False)`, FQN `@Disabled`, `@Disabled @Nested`, `@EnabledIf`, `@Tag` on helper, `describe.skip` parent, tag inside a string | **all excluded** (none counted) |
| R-4/N9 | GPL expression plus MIT classifier | the collector now keeps only the most authoritative field, so the two can no longer be unioned. LGPL-2.1-or-later (dev) is allowed; bare "BSD License" is unidentified; `MIT AND GPL-3.0-only` is not allowed; SSPL and BUSL are not allowed |
| R-8/N11 | `feat: updated things`; indented `Co-authored-by`; `Generated-by: Claude Code`; `Signed-off-by: Claude <noreply@anthropic.com>` | **all rejected** |

New attempts against the delta:

| # | Target | Attempt | Result |
|---|---|---|---|
| X1 | `docker-gate` | can a Docker suite pass silently? no Docker + `REQUIRE_DOCKER` unset → visible SKIPPED; set → exit 1; `REQUIRE_DOCKER=0` → exit 1 | no silent path found |
| X2 | `pytest_docker` | `-m "not requires_docker"` under `REQUIRE_DOCKER=1`; plugin disabled; `FS_DOCKER_AVAILABLE=1` override | deselection still fails (exit 4); disabling the plugin fails on strict markers; the override makes tests run (and fail if Docker is absent), so no silent pass |
| X3 | runtime skips under `REQUIRE_DOCKER` | test body `pytest.skip("docker not reachable")` (plugin active, `REQUIRE_DOCKER=1`, plugin sees no marker) | **passes as "skipped"**, exit 0. JUnit `Assumptions`/`@Testcontainers(disabledWithoutDocker = true)` behave the same way, and CI has no "fail on skipped" rule (DR-3) |
| X4 | test_tags | `@Testcontainers(disabledWithoutDocker = true)` class; custom meta-annotation `@Pending`; `abstract` `@Test`; `pytest.importorskip` at module level; class `__test__ = False`; `getattr(pytest.mark, "skip")`; Playwright `test.fixme`; `it.only` sibling; `const d = describe.skip`; `test.describe.configure({mode:'skip'})` | **all counted as tagged tests** (DR-3) |
| X5 | benchmark rule | NFR-PERF-02 `DONE` with `docs/benchmarks/2026-09-20-NFR-PERF-02.json` containing CI-runner numbers | **no error** (DR-2) |
| X6 | benchmark rule | NFR-PERF-02 `DONE` with evidence `docs/benchmarks/hardware.md` | **no error**: the hardware record itself counts as a "measurement file" (DR-2) |
| X7 | benchmark rule | `VERIFIED_AT_REDUCED_SCALE` with `machine: github-actions-ubuntu-24-04` after adding a lower-case `## github-actions-ubuntu-24-04` section | **no error**: nothing distinguishes a dedicated machine from a CI runner (DR-2) |
| X8 | benchmark rule | NFR-PERF-02 `DONE` with only a CI run URL | **caught** ("needs a measurement file") |
| X9 | benchmark rule | `MOB-PERF-01` / `D-16` `DONE` with only a CI run URL | **no error**: not in `BENCHMARK_ROWS` (DR-2) |
| X10 | compose_budget | extra service with **no `profiles`** (starts with every profile) and 8 g limit | **not counted**, 0 errors (DR-4) |
| X11 | compose_budget | `deploy.replicas: 4` × 1000 m | counted once (DR-4) |
| X12 | compose_budget | `mem_limit: 8g` with no deploy limit / interpolated `${MEM:-1g}` / `1GiB` | errors or exceptions (fails closed; the exception is a traceback, not a message) |
| X13 | scope guard | pragma inside a fenced code block in `docs/**/*.md` | accepted (DR-9) |
| X14 | scope guard | prefix `title: <D-47 title>` on another YAML row's title | scope guard passes; **seed check catches it** |
| X15 | commit_msg | `Assisted-by: Claude`; `Co-developed-by: Claude <noreply@anthropic.com>`; body line `Made with Cursor`; `feat: updated things across modules` | **all accepted** (R-8 remainder) |
| X16 | licences | `MIT OR GPL-3.0-only`, `(MIT AND (GPL-2.0-only OR BSD-3-Clause))`, `Apache-2.0 WITH LLVM-exception` | allowed (correct SPDX semantics); `LicenseRef-Proprietary` (dev) unidentified; malformed `MIT AND` unidentified |

Mutants (tools test suite, `--no-cov`):

| # | Mutation | Result |
|---|---|---|
| D1 | `tools/bin/docker-gate`: `REQUIRE_DOCKER` branch disabled | **SURVIVED**: no test exercises the script (DR-6) |
| D2 | `pytest_docker.py`: `REQUIRE_DOCKER` ignored | killed |
| D3 | `pytest_docker.py`: summary line removed | killed |
| D4 | `pytest_docker.py`: skip marker not added | killed |
| B1 | `evidence.py`: measurement-file rule removed | killed |
| B2 | `evidence.py`: machine rule removed | killed |
| B3–B5 | see R-1 row | killed |
| C1 | `compose_budget.py`: budget comparison +1024 slack | killed |
| C2 | `compose_budget.py`: missing limit allowed | killed |
| C3 | `compose_budget.py`: core budget 4096 → 8192 | killed |
| S1 | `scope_guard.py`: pragma allowed in every file | killed |
| S2 | `scope_guard.py`: allowlisted directories skip path check | killed |
| S3 | `scope_guard.py`: whole YAML skipped | killed |
| S4 | `scope_guard.py`: allowlist prefix match | **SURVIVED** (DR-6) |
| J1 | `test_tags/java.py`: disabling annotations ignored | killed (3 tests) |
| Y1 | `test_tags/python.py`: `xfail` no longer disables | killed |
| K1 | `commit_msg.py`: tool sign-off rule removed | killed |
| G1 | `tools/bin/gitleaks`: cached-binary re-verification disabled | **SURVIVED**: no test exercises the script (DR-6) |

## Devcontainer and CI inspection

- **Plausibility.** The devcontainer is mostly sound: base image pinned by digest, pinned
  features, uv checksum-verified, a Docker wait loop, and a `.post-create-ok` sentinel checked by
  the workflow so a partial setup cannot pass. Failures do surface: run 35181726205 went red.
  **But the first run failed during post-create**, and the cause is unknown from public data. It
  failed after about 3 minutes, before any long `make ci` step could plausibly finish. Likely
  suspects to check in the log: `uv run pre-commit install --install-hooks` in a workspace whose
  owner UID differs from the container user (git "dubious ownership"), and Corepack or pnpm
  download prompts. `remoteEnv.PATH` uses `${containerEnv:HOME}`, which is unset if the image
  defines no `HOME` in its container environment; post-create exports `PATH` itself, so this
  affects the terminal but not post-create.
- **Post-create runs the full `REQUIRE_DOCKER=1 make ci`**, including licences and the stack, on
  every Codespace creation. That is slow (>10 min expected) and any flaky check marks creation as
  failed. This is acceptable as a verification choice, but it is the author's everyday environment.
- **Workflow triggers** omit inputs that post-create's `make ci` depends on (`uv.lock`,
  `pyproject.toml`, `tools/**`, `frontend/package.json`, `frontend/pnpm-lock.yaml`,
  `backend/pom.xml`, `infrastructure/docker/scripts/**`). The weekly `schedule` runs only on the
  default branch, which is still `m0/bootstrap` (DR-7).
- **`REQUIRE_DOCKER: "1"` at workflow level** is correct: all jobs run on `ubuntu-24.04`, where a
  Docker daemon exists. The `java` and `python` jobs call Maven/pytest directly, not through the
  Makefile gate, so JUnit runtime skips are unguarded (DR-3).
- **Governance token.** Job-level `permissions: {contents: read, actions: read}` replaces the
  workflow default for that job only. The token is passed only to the `fs-traceability check`
  step. This is least-privilege and correct. Pull requests from forks get a read-only token,
  which is enough for `GET /actions/runs/{id}`. A 403 from rate limiting is reported as "not valid
  evidence" (an error), which can fail CI spuriously but never passes silently.

## Owner follow-ups interpreted by the author (item e)

| Item | Author's interpretation | Assessment |
|---|---|---|
| Memory budget per compose profile | `fs-compose-budget`: every service needs a limit; core 4,096 / ml 6,144 / obs 5,120 / full 10,240 MiB; runs in governance | **Reasonable** and mechanically sound (C1–C3 killed). Gaps: services without `profiles`, replicas (DR-4). The ml/obs/full numbers precede any service that uses them and should be marked provisional. They are presented as part of an owner decision (DR-5) |
| ADR 0009 boundary cases | table of SPDX `OR`/`AND`/`WITH`, authoritative-field rule, unknown classifier members block, GPL-family denied, LGPL dev-only, bare BSD unidentified, version-pinned exceptions (nodeenv, ArchUnit) | **Reasonable and tested**. My probes (X16, R-4 repeats) agree with the table |
| Resilience4j on Boot 4.1 | traceability note on NFR-REL-01: M6 circuit-breaker integration test in a Boot 4.1 context is the compatibility proof, with a fallback to the core library | **Reasonable** for M0. At M6 this must become a tagged test named in the row |
| Provenance | ADR 0010 is headed "Accepted (repository owner decision)" and its Decision section includes the budget numbers and benchmark enforcement design, which the author supplied | Mixing author interpretation into a record attributed to the owner blurs A.3 rule 1 accountability (DR-5) |

## Findings

### Status of re-review findings

| # | Severity | Status | Verified by reviewer |
|---|---|---|---|
| R-1 | MINOR | RESOLVED | ancestry and API checks (`evidence.py:52-86`); fabricated URL is an error in CI; B3–B5 killed |
| R-2 | MINOR | RESOLVED | N3–N5 caught; S1–S3 killed. S4 survivor and code-block pragma tracked as DR-6 / DR-9 |
| R-3 | MINOR | RESOLVED for every listed form | all 10 forms excluded. Further forms and runtime skips tracked as DR-3 |
| R-4 | MINOR | RESOLVED | single authoritative field (`licences.py:305-321`); SPDX parser; GPL-family denied; LGPL recognised; bare BSD unidentified; tests `test_licences_policy.py` |
| R-5 | MINOR | RESOLVED | ADR 0009 cites D-17 and E.12 with the A.4 item 1 argument, labels the M0 cases as examples, plans M6 generative properties; D-17 row note; `backend/README.md:19` corrected |
| R-6 | MINOR | RESOLVED | history note `docs/walkthrough/M0.md:94` |
| R-7 | NIT | RESOLVED (untested, DR-6) | binary SHA-256 re-checked on every run (`tools/bin/gitleaks`) |
| R-8 | NIT | PARTIALLY_RESOLVED | listed forms rejected; `Assisted-by: Claude`, `Co-developed-by: Claude <noreply@anthropic.com>`, "Made with Cursor", `feat: updated things across modules` pass (X15) |
| R-9 | NIT | RESOLVED | ADR 0003:18 states patch-level pin and build resolution |
| R-10 | NIT | RESOLVED | `smoke-test.sh:83-86` asserts `smoke.txt` in the bucket; passed in run 35181726185 (robustness: DR-8) |

### New findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| DR-1 | MAJOR | `.devcontainer/post-create.sh`; `.devcontainer/devcontainer.json`; `.github/workflows/devcontainer.yml`; `README.md` (Option A); ADR 0010 Decision item 2 | The devcontainer workflow's first run (**35181726205**, b08ba61) **failed**: `postCreateCommand … post-create.sh` exited non-zero after about 3 min. The README tells the author to use Codespaces for all Docker work, and ADR 0010 says "the Codespaces setup itself is tested", so the owner's decision (b) is not met. The workflow triggers on push when `.devcontainer/**`, `Makefile` or `docker-compose.yml` change, so creating `main` from this branch will very likely run it and put a red check on `main` (A.3 rule 7). | Read the job log (authenticated), fix the failing post-create step, and get a **green devcontainer run** on the branch head before fast-forwarding `main`. Record the run ID with the stack evidence. Until it is green, mark Codespaces in README and ADR 0010 as "unverified". | OPEN |
| DR-2 | MINOR | `tools/src/fraudshield_tools/evidence.py:96-114, 197-213`; `docs/adr/0010-…md` Decision item 4 ("`fs-traceability check` enforces this") | A CI-runner number **can** close a benchmark row. (a) `DONE` needs only an evidence path starting with `docs/benchmarks/`, and `docs/benchmarks/hardware.md` itself qualifies (X5, X6). (b) The machine rule applies only to `VERIFIED_AT_REDUCED_SCALE`, not to `DONE`. (c) Any lower-case `## <id>` heading counts as a recorded machine, including a CI runner section (X7). (d) `BENCHMARK_ROWS` omits rows whose criteria are timings (e.g. `MOB-PERF-*`, FR-04-07 "render within 1 second", D-13, D-16), which can close on a CI run URL (X9). | For every evidenced status of a benchmark row, require `machine: <id>`. The machine section must declare `dedicated: yes` (a line the checker reads), and `DONE` must be impossible until one exists. Exclude `hardware.md` as a measurement file, and require the measurement file to name the same machine ID. Derive or extend `BENCHMARK_ROWS` to cover every timing, throughput or render-time criterion (or record the exclusions in ADR 0010). Add tests for X5–X7 and X9. Must be done before the first benchmark row moves (FR-02-02 in M3). | OPEN |
| DR-3 | MINOR | `tools/src/fraudshield_tools/pytest_docker.py`; `.github/workflows/ci.yml` (java, python jobs); `tools/src/fraudshield_tools/test_tags/{python,java,typescript}.py` | Docker tests can still pass silently at runtime, and never-running tests still count. Under `REQUIRE_DOCKER=1`, `pytest.skip()` in a test body passes (X3). JUnit `Assumptions` or `@Testcontainers(disabledWithoutDocker = true)` would skip without failing the `java` job. Tag discovery counts `disabledWithoutDocker` classes, custom disabling meta-annotations, `abstract` tests, module `importorskip`, `__test__ = False`, `getattr` marks, Playwright `test.fixme`, `it.only` siblings, aliased `describe.skip` and `describe.configure({mode:'skip'})` (X4). ADR 0004 documents only "runtime skips and non-literal conditions". | Under `REQUIRE_DOCKER`: make the pytest plugin fail the session when any test is skipped (or skipped for a Docker reason), and add a JUnit Platform `TestExecutionListener` (or a Surefire report check in CI) that fails on skipped tests. Forbid `disabledWithoutDocker` with a Checkstyle regexp. Extend test_tags for `fixme`, `.only`, `importorskip`, `__test__ = False` and `abstract`, or document each form in ADR 0004. Bring forward counting only executed, passed tests before any milestone with Docker-dependent Must rows closes (M1). | OPEN |
| DR-4 | MINOR | `tools/src/fraudshield_tools/compose_budget.py:39-58` | Services without `profiles` start with every profile but are added to no profile total (X10), so a new unprofiled service bypasses the budget entirely. `deploy.replicas` is ignored (X11). Unparseable limits raise a traceback instead of a reported error (X12). | Count unprofiled services in every budgeted profile (or reject them), multiply by `replicas`, catch `BudgetError` into the error list, and add tests. | OPEN |
| DR-5 | MINOR | `docs/adr/0010-container-stack-verified-in-ci-not-on-authors-laptop.md:3, 38-42, 68-71`; `docs/walkthrough/M0.md:76`; `docs/reviews/M0/stack-gate-evidence.md` (missing) | (a) ADR 0010 is recorded as an owner decision but also contains author interpretations of follow-ups the owner never put in writing: the budget numbers and the benchmark-enforcement design. The ADR does not separate the two. (b) The ADR requires stack runs to be "recorded with run IDs in `docs/reviews/M0/stack-gate-evidence.md`", which does not exist, and the walkthrough already says the stack "needed three consecutive green runs" in the past tense. | Split ADR 0010 into owner decisions (a–d, as given) and author proposals (budgets, enforcement mechanics, the ADR 0009 boundary table, the NFR-REL-01 plan) marked "pending owner confirmation". Create `stack-gate-evidence.md` with run IDs 35178641969, 35179479949, 35181726185 (commits, dates, job conclusions), plus the run for the merge candidate. | OPEN |
| DR-6 | NIT | `tools/bin/docker-gate`; `tools/bin/gitleaks`; `tools/src/fraudshield_tools/scope_guard.py:57-61` | Three mutants survive: D1 (`REQUIRE_DOCKER` branch of `docker-gate` disabled), G1 (gitleaks cached-binary check disabled) and S4 (allowlist prefix match). The shell gates the Docker decision rests on have no tests. | Add subprocess tests: `docker-gate` with a stub `docker` on `PATH` (available / unavailable × `REQUIRE_DOCKER` set / unset / `0`), and `gitleaks` with a tampered cache in a temporary `XDG_CACHE_HOME`. Add a test that `docs/adr/0008-out-of-scope-content-guard.md.bak` is not exempt. | OPEN |
| DR-7 | NIT | `.github/workflows/devcontainer.yml:7-24` | Path triggers omit files that post-create's `make ci` depends on (lockfiles, `pyproject.toml`, `tools/**`, `frontend/package.json`, `backend/pom.xml`, `infrastructure/docker/scripts/**`). The weekly schedule only runs on the default branch (currently `m0/bootstrap`). | Add those paths (or run on every PR to `main`), and set `main` as the default branch (O-1). | OPEN |
| DR-8 | NIT | `infrastructure/docker/scripts/smoke-test.sh:83-86` | `… | tee /dev/stderr | grep -q 'smoke.txt' || fail …` runs under `pipefail`. If `grep -q` exits before `tee` finishes writing, `tee` gets SIGPIPE and the pipeline fails although the artifact exists. | Capture the listing in a variable, print it, then test it with `grep -q`. | OPEN |
| DR-9 | NIT | `tools/src/fraudshield_tools/scope_guard.py` (`PRAGMA_SCOPE`) | The pragma is honoured inside fenced code blocks in `docs/**/*.md` (X13), so code samples in docs can carry banned identifiers. | Ignore the pragma inside fenced blocks, or accept this and record it in ADR 0008. | OPEN |
| R-8 (remainder) | NIT | `tools/src/fraudshield_tools/commit_msg.py:37-49` | See the R-8 status row (X15). | Forbid `Assisted-by`/`Co-developed-by` trailers and tool identities in any trailer; treat `updated things …` as a placeholder prefix; add tests. | OPEN |

## Evidence reproduced (claimed vs measured)

| Claim | Measured | Match |
|---|---|---|
| Local `make ci` prints SKIPPED for Docker suites, never silent (ADR 0010 item 3) | two SKIPPED lines (java, stack-test); plus the pytest summary line when marked tests exist | yes |
| `REQUIRE_DOCKER=1` turns a missing daemon into a failure | `make stack-test` and `make test-java` exit 2; pytest exit 4 | yes |
| All CI jobs set `REQUIRE_DOCKER=1` | workflow-level `env` in `ci.yml` | yes (JUnit runtime skips unguarded, DR-3) |
| Three consecutive green `stack` runs before merge/tag (ADR 0010 item 1) | 35178641969, 35179479949, 35181726185 all green, no failure in between | yes (evidence file missing, DR-5) |
| The devcontainer workflow tests the Codespaces setup (commit 2f9f864) | run 35181726205 **failure** in post-create | **no** (DR-1) |
| Benchmark rows cannot use CI-runner numbers; `fs-traceability check` enforces this (ADR 0010 item 4) | X5–X7, X9 pass | **no** (DR-2) |
| `fs-compose-budget` fails if any service lacks a limit or a profile exceeds its budget | limits and totals enforced; unprofiled services and replicas not counted | partly (DR-4) |
| core profile 3,968 / 4,096 MiB | `compose-budget: core 3968/4096` | yes |
| Licence boundary table (ADR 0009) | my probes agree with every row checked | yes |
| Pinned devcontainer image, features, action, uv checksum (commit 2f9f864) | all verified upstream | yes |
| "tools/tests (120 tests, 95.9% coverage)" (commit c772a97) | not re-derived at c772a97; at b08ba61: 131 tests, 92.94 % | not reproduced for that commit; consistent with later commits adding code |
| 5 new commit messages meet G.3 | `fs-commit-msg`: 0 problems | yes |

## Residual risks

- **Codespaces is unproven** until DR-1 is fixed. From M1 onward, Docker-dependent work has no
  verified interactive environment, only CI.
- **Three green stack runs over about 50 minutes on shared runners** show the MLflow fix holds
  under normal conditions, but not under runner contention. A future red stack run should reset
  the count, as ADR 0010's own wording implies.
- **Benchmark integrity** depends on review until DR-2 is fixed. The first exposure is M3
  (`benchmark.py features`).
- **Runtime-skipped tests** in CI (DR-3) could hide missing Docker coverage once Testcontainers
  suites arrive in M1.
- Unchanged from the re-review: the Resilience4j Boot 4.0 build, tag-pinned images, the
  deliberately insecure dev stack, and no branch protection or default branch until the owner acts.

## Does anything block the M0 closing plan?

Plan: 3 consecutive green stack runs → fast-forward `main` → milestone review from a clean
worktree of `main` → close M0 in `milestones.yaml` → tag `m0-complete`.

- **Step 1 (3 green stack runs):** satisfied by 35178641969, 35179479949, 35181726185.
  `stack-gate-evidence.md` still has to be written (DR-5). The commit actually fast-forwarded,
  which will include this review record and the DR fixes, needs its own green `ci` run. Any red
  `stack` run before then resets the count.
- **Step 2 (fast-forward `main`): BLOCKED by DR-1.** The devcontainer workflow is red on the
  branch head, will run when `main` is created, and `main` must be green. Fix it, get a green
  `devcontainer` run and a green `ci` run on the same head commit, then fast-forward without force.
- **Steps 3–5 (milestone review, `milestones.yaml`, tag):** no additional blocker from this delta.
  DR-5 (provenance split and evidence file) should be done before the milestone review so that
  review has correct records. DR-2, DR-3 and DR-4 may be scheduled (DR-2 before M3; DR-3 before
  M1 closes), with R-8 and DR-6 … DR-9 tracked as issues. The earlier tag conditions still apply:
  the tagged commit's CI green including `stack`, D-47/D-48 final with evidence, and the owner
  setting `main` as default branch with protection (or recorded as an open owner action).

---

# Final delta check (b08ba61..83a9090)            Reviewer role: Principal Reviewer
Date: 2026-09-17 (UTC)   Branch/commit: m0/bootstrap @ 83a90905a78b7c671e39d758ead9ed5d92b2b2dd (6a48410, 63be261, 70046b1, 5761d21, ff2e30d, 6942c96, ed7223c, 83a9090)
Verdict: APPROVED_WITH_MINORS

Owner direction applied (2026-09-17): governance is time-boxed. MINOR and NIT gaps in governance
tooling do not block M0 if they are logged in `docs/backlog/governance.md` with a due milestone.
The milestone-level review is `docs/reviews/M0/milestone-review.md`.

## Checks re-run (command → result)

| Check | Result |
|---|---|
| Clean-worktree `uv sync --locked --no-cache`, `pnpm install --frozen-lockfile`, `make ci` at 83a9090 | exit 0; 1064 Java tests; tools 131 / ml 14; traceability 0 errors; licences 0 violations; visible SKIPPED lines for Docker suites (details in `milestone-review.md`) |
| CI 6942c96: ci 35184012244 / stack 35184012351 / devcontainer 35184012247 | 7/7 success / `stack` executed, success / `build-and-verify` executed, success |
| CI 83a9090: ci 35184418101 / stack 35184418147 / devcontainer 35184418108 | 7/7 success / `stack` job **skipped** (no inputs changed) / `build-and-verify` **skipped**; run-level "success" in both |
| Stack runs on ed7223c, 5761d21, ff2e30d (35182920003, 35183327832, 35183578414) | `stack` job executed and success in each |
| `git status --short` after mutations and worktree removal | clean |

## Mutation spot checks

`Money.requirePositive` accepting zero → killed (`zeroIsStorableButRejectedWherePositiveAmountRequired`).
`HALF_EVEN` → `HALF_UP` → killed (5 rounding cases). `smallestMinorUnit` at storage scale → killed (7 cases).

## Findings

| # | Severity | Status | Verified by reviewer |
|---|---|---|---|
| DR-1 | MAJOR | **RESOLVED** | Devcontainer fixed (70046b1 safe.directory and smoke hardening; ff2e30d pnpm store outside the workspace; 6942c96 PATH/`remoteUser`; 5761d21 failure-log reporting). Run 35184012247 `build-and-verify` executed and succeeded; the `.post-create-ok` sentinel guards partial setups. ADR 0010 item 2 now states "verified" with the run ID |
| DR-2 | MINOR | OPEN, backlogged | GOV-1, due before FR-02-02 moves (M3); ADR 0010 item 4 now names the gaps instead of claiming full enforcement |
| DR-3 | MINOR | OPEN, backlogged | GOV-2, due before M1 closes |
| DR-4 | MINOR | OPEN, backlogged | GOV-3, due before the first ml/obs service (M5); ADR 0010 item 5 names the gap |
| DR-5 | MINOR | **RESOLVED** | ADR 0010 "Decided by" line and per-item attribution (owner decision vs author design, owner confirmation noted, ml/obs/full budgets provisional); `docs/reviews/M0/stack-gate-evidence.md` lists runs 1–7 with job-level detail; walkthrough wording no longer past tense |
| DR-6 | NIT | OPEN, backlogged | GOV-4, M1 |
| DR-7 | NIT | OPEN, backlogged | GOV-5, partly done (stack/devcontainer inputs now include lockfiles, manifests, `.mvn`); due "after `main` exists", which is not a milestone (see F-1 in the milestone review) |
| DR-8 | NIT | **RESOLVED** | `smoke-test.sh` captures the bucket tree in a variable, prints it, then asserts `*smoke.txt*`; stack runs 5–7 green with it |
| DR-9 | NIT | OPEN, backlogged | GOV-6, **unscheduled** (needs a due milestone, F-1) |
| R-8 remainder | NIT | OPEN, backlogged | GOV-7, M1 |

New in this delta (tracked in `milestone-review.md`): F-1 (NIT, backlog entries without due
milestones), F-2 (NIT, `@Tag("D-17")` on money tests must not be mistaken for the D-17
decision-engine property tests), F-3 (NIT, rounding ties not tested for BIF/SSP/SOS/USD/EUR),
F-4 (NIT, path-filtered workflows report run-level "success" when their job is skipped; cite job
conclusions). M-1 (MINOR, threat model absent; delta recorded; due M1).

**Money boundary tests vs the owner's list** (`MoneyBoundaryTest`, 36 cases; `Money.requirePositive`,
`Money.smallestMinorUnit`, `SMALLEST_STORABLE_MAGNITUDE`, `LARGEST_STORABLE_MAGNITUDE`):
- Zero rejected where `amount > 0` is required: covered.
- Smallest representable amount: covered, both storable (0.0001, every currency) and minor unit (RWF/UGX/BIF 1; KES/TZS/CDF/USD 0.01).
- DECIMAL(18,4) maximum and one step beyond: covered.
- Rounding at minor units: covered for RWF, UGX, KES, TZS and CDF, including negative ties (F-3 for the others).
- Negatives: storable, but rejected by `requirePositive`.
- Tags: `@Tag("D-17")`, `@Tag("D-43")`; Javadoc cites ADR 0009.
- Null device fingerprint: recorded as an M3 test-case note on FR-01-04 and D-04, not implemented. This is correct, since no feature code exists.

**Owner-confirmed items:** the per-profile memory budget and the M6 Resilience4j integration-test
plan are recorded as confirmed in ADR 0010 and on NFR-REL-01. CI-minutes policy: `stack.yml` and
`devcontainer.yml` run on input changes, pushes to `main` (forced), nightly and `workflow_dispatch`,
as directed. The nightly schedule starts only once `main` is the default branch.

## Evidence reproduced (claimed vs measured)

| Claim (`stack-gate-evidence.md`, commit messages) | Measured | Match |
|---|---|---|
| 7 consecutive executed green stack jobs | verified job-by-job | yes |
| 6942c96 first head with all three workflows green, every job executed | verified | yes |
| Four earlier devcontainer failures | 35181726205, 35182920025, 35183327917, 35183578516 failure | yes |

## Residual risks

See `milestone-review.md`. In short: the Docker evidence is CI-only, path filters can delay
detection of out-of-pattern regressions until the nightly run or the next push to `main`, and the
backlogged governance gaps must meet their due milestones.

**Merge/tag:** fast-forward `main` to 83a9090 is approved. The `m0-complete` tag is approved on the
closing commit once its ci, stack and devcontainer runs on `main` are green with jobs executed.
Exact closing-commit contents are in `milestone-review.md`, "Decisions".
