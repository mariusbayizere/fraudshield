# M1 contracts-events: final review (N-01 … N-09, owner decisions 1–6, history rewrite)

- **Role:** independent Principal Reviewer (build prompt Part I.2)
- **Date (UTC):** 2026-09-17
- **Branch / commit:** `m1/contracts-events` at `e5276e82dd6a9e8a9f41927ac1237aba84c10397`. The target is a fast-forward of `main` (fb9093f).
- **Verdict:** **CHANGES_REQUIRED**. One MAJOR finding (F-01): owner decision 1 says tightening takes effect immediately, but the domain workflow refuses a tightening proposal with 409 whenever any change of the same kind is open. Neither the contract nor the domain lets the proposer withdraw a change.

All work was done in an isolated worktree created from e5276e8. The main checkout at `/home/marius/fraudshield` was not modified. Every mutation was reverted with `git checkout -- .`, and `git status --short` was empty afterwards.

## Checks

| Check | Result |
|---|---|
| `uv sync --all-packages --locked` | ok |
| Contract tests (`contracts/`, pytest) | 478 passed, coverage 93.21% (gate 90%) |
| `backend/./mvnw -B -ntp verify -pl common` (Java 21) | BUILD SUCCESS: 1,113 tests, 0 failures. `DualControlWorkflowTest`: 16 tests. JaCoCo gate met |
| `uv run python tools/bin/gitleaks-selftest` | exit 0: "37 planted secrets detected in git and dir scans; 15 allowlisted fixtures otherwise clean" |
| `uv run fs-contract-baselines --against origin/main` | exit 0: "merge base fb9093fa019c; 0 published Kafka schemas, proto not yet published; 0 breaking changes". This is expected, because main does not yet contain the event contracts |
| Guard with a real `buf breaking` against a git ref (`--against e013785`) | exit 1 with 5 findings (the GetModelStatus request and response renames). So `.git#ref=…,subdir=contracts/proto` works, including from a git worktree. `--against HEAD`: exit 0 |
| buf checksum, checked independently | The release `sha256.txt` for v1.72.0 (fetched from github.com) lists `a9c6186c…ceee8  buf-Linux-x86_64.tar.gz` and `8720830e…ca5af  buf-Linux-x86_64`. Both equal `tools/bin/buf:10-11`. The cached binary hashes to `8720830e…ca5af`. `buf --version` = 1.72.0. LICENSE is Apache 2.0 |
| buf configuration | `buf config ls-breaking-rules --configured-only` shows the FILE rules with FIELD_NO_DELETE and ENUM_VALUE_NO_DELETE replaced by the four `…_UNLESS_NUMBER/NAME_RESERVED` rules, as ADR 0016 §2 says |
| Tree identity: `git diff 0ea2f2f e013785` | empty |
| Tree identity: `git diff backup/events-before-history-rewrite(0efa2cf) e5276e8` | only `tools/bin/verify-branch-commits` (the disclosed script fix) and the two review documents |
| Old → new tree mapping (every row of the response table) | 962cb40, ee3c685, 837dd48↔50e250c, 4a7d8d2↔40b80e3, 3c24b6c↔e389d5a, 556099c↔b6290b9, 339ed23↔b538cd7, 792ca4b↔696ea58, 0ea2f2f↔e013785, 6920324↔c86db7c, 888e984↔437c401, 7a01c77↔e692225: identical trees. 0efa2cf↔efb3d40 differ only by the script fix |
| Squash contents | `git diff ced4324 dba1021` is exactly the 8-file diff of b419e1e (the event `if` discriminators). 50e250c = 065a66c + 837dd48. 40b80e3 = 07638e2 + 4a7d8d2. The commit messages describe the folded content |
| `tools/bin/verify-branch-commits fb9093f e5276e8` (run by me) | exit 0. All 15 commits pass, e5276e8 included |
| Control run of the same script on the old history (`ee3c685..065a66c`) | ced4324 **FAIL** (2 contract tests fail), so the script is not vacuous. 065a66c **passes** although its tree has the same red contract tests: the script ran only tools checks, because that commit touches no `contracts/` file (see F-05) |
| Full Python suites (contracts, tools, ml) at every rewritten commit that the script ran selectively (ee3c685, 40b80e3, e389d5a, 696ea58, e013785, c86db7c, efb3d40, e5276e8) | all pass (see the appendix). The backend is unchanged between 437c401 and e5276e8 |
| Push scope | GitHub branches: main fb9093f, m1/contracts-openapi fb9093f, m0/bootstrap 83a9090, m1/contracts-events e5276e8. The tag `m0-complete` is still at 1b99782. The local remote-tracking reflog shows `origin/main` last moved to fb9093f before this work, and `origin/m1/contracts-events` moved 0ea2f2f → e5276e8. Only the feature branch changed |
| Owner decision 6 (unauthenticated API) | `GET /repos/mariusbayizere/fraudshield` → `default_branch: m0/bootstrap`. `git merge-base --is-ancestor 83a9090 fb9093f` succeeds. The branch was correctly not deleted |
| Public repository constraints (`git diff fb9093f e5276e8`, added lines) | Emails: the author address, and `selftest@example.com` quoted only inside earlier review records. Only RFC 2606 example.com addresses (owner decision 3). No internal hostnames. `/home/marius` appears only in review records (mutation hygiene lines) |
| Commit metadata fb9093f..e5276e8 | 15 commits, all authored and committed by `Marius Bayizere <bayizeremarius119@gmail.com>`. No Co-Authored-By, Signed-off-by or tool signature |
| Undisclosed changes | none found. Each commit's file list matches its message |

## Mutations

Java mutations ran `DualControlWorkflowTest` (`mvnw test -pl common -Dtest=DualControlWorkflowTest`). Contract and buf mutations ran the contracts pytest suite, or `test_proto_breaking.py` for the buf-only rows.

| ID | Mutation | Result |
|---|---|---|
| J1 | Skip the self-review check (`DualControlWorkflow.java:256`) | caught (`selfApprovalAndSelfRejectionAreForbidden`) |
| J2 | Revert strictly after 24 h (`isAfter` instead of `!isBefore`, `:237`) | caught (`unconfirmedTighteningChangeRevertsAfter24Hours`) |
| J3 | Allow ADMIN in `requireRiskOfficer` (`:265`) | caught (`onlyRiskOfficersProposeApproveOrReject[ADMIN]`) |
| J4 | Treat a timeout-policy switch to RELEASE as tightening (`ChannelThresholds.java:66`) | caught |
| J5 | Skip `apply` on loosening approval (`:190`) | caught |
| J6 | Drop the one-open-change-per-kind check (`:129`) | caught |
| J7 | No `revertOverdue()` before review (`:251`) | caught (`lateConfirmationIsRefused…`) |
| J8 | Classify a mixed change as tightening (`ChannelThresholds.java:71-74`) | caught |
| J9 | Treat a breaker window change as not loosening (`CircuitBreakerSettings.java:56`) | caught |
| J10 | Rejecting a tightening does not restore the previous settings (`:216`) | caught |
| J11 | Auto-revert not audited (`:241`) | caught |
| J12 | Lower minimum volume no longer counts as tightening (`CircuitBreakerSettings.java:57`) | **survived** |
| J13 | Shorter clean reset no longer counts as loosening (`:56`) | **survived** |
| J14 | Stale base version accepted (`:124`) | caught |
| J15 | Lower MEDIUM threshold ignored (`ChannelThresholds.java:63`) | **survived** |
| J16 | Higher MEDIUM threshold ignored (`:64`) | caught |
| J17 | Auto-revert audited with the proposer as actor | caught |
| J18 | No version increment on re-applying earlier settings | caught |
| J19 | Rejecting a loosening applies it | caught |
| J20 | **Shorter clean reset applied immediately as tightening**, which bypasses dual control for a loosening | **survived** (F-03) |
| J21 | A pending loosening no longer blocks a tightening (probe for F-01) | survived: the blocking behaviour is not pinned either way |
| C1 | Add ADMIN to `approveConfigChange` in both the contract and the matrix | caught (`test_authorisation_matrix.py`) |
| C2 | `/health` 200/503 return `ComponentHealth` (with `detail`) | caught (`test_health_detail_is_not_public`) |
| C3 | Add `detail` to `ProbeStatus` | caught |
| C12 | Remove the self-review rule from `approveConfigChange` in both the contract and the matrix | caught |
| C18 | Mark `/health` as `x-network: management` | caught |
| C19 | Move `/actuator/health/db` to a public `/health/db` in the contract | caught |
| C4 | `buf.yaml` FILE → WIRE_JSON | caught ("method removed") |
| C5 | FILE → WIRE | caught ("field renamed") |
| C10 | except FIELD_SAME_CARDINALITY | caught |
| C11 | except ENUM_VALUE_SAME_NAME | caught |
| C13 | except FILE_SAME_PACKAGE | caught |
| C14 | except RPC_SAME_SERVER_STREAMING | caught |
| C15 | except FIELD_SAME_TYPE | caught |
| C16 | except RESERVED_ENUM_NO_DELETE | caught |
| C17 | except FIELD_SAME_NAME, FIELD_SAME_JSON_NAME | caught |
| C21 | except RPC_SAME_CLIENT_STREAMING | caught |
| C22 | except RPC_NO_DELETE | caught |
| C23 | except RESERVED_MESSAGE_NO_DELETE | caught |
| C6 | FILE → PACKAGE | survived (weaker category, no test moves a type between files; NIT) |
| C7 | Drop ENUM_VALUE_NO_DELETE_UNLESS_NAME_RESERVED | **survived** (F-04) |
| C20 | except ENUM_VALUE_NO_DELETE_UNLESS_NUMBER_RESERVED | **survived** (F-04): an enum value can be removed with only its name reserved, which leaves the number reusable |
| C8 | except FIELD_SAME_ONEOF | **survived** (F-04): the old checker compared oneof membership |
| C9 | except MESSAGE_NO_DELETE, ENUM_NO_DELETE, SERVICE_NO_DELETE | **survived** (F-04): the old checker reported message, enum and service removal |
| C24 | except ONEOF_NO_DELETE, FIELD_SAME_DEFAULT | survived (NIT) |
| GL-a | Vector allowlist path widened to `^contracts/webhooks/.*\.json$` | caught |
| GL-b | Kafka examples path widened to `^contracts/.*\.json$` | caught |
| GL-c | schema-examples path widened to `^contracts/openapi/.*\.yaml$` | caught |
| GL-e | Kafka examples path widened to `^contracts/kafka/.*\.json$` | caught |
| GL-d | schema-examples path widened to any directory with the same file name (`^contracts/.*/schema-examples\.yaml$`) | survived (NIT F-11) |

Totals: 50 mutations, 38 caught, 12 survived. Of the survivors, J12, J13, J15, J20, C7, C8, C9 and C20 are findings. J21, C6, C24 and GL-d are informational or NIT.

## Status of the re-check findings

| # | Sev | Status | Evidence |
|---|---|---|---|
| N-01 | MAJOR | **Resolved** | `Makefile:113` runs `uv run python tools/bin/gitleaks-selftest`, and `.github/workflows/ci.yml:202` still calls the script directly. The devcontainer workflow run 35205785618 for e5276e8, job "build devcontainer, post-create make ci, smoke test inside", has conclusion **success** |
| N-02 | MINOR | Resolved (test option) | `contracts/tests/test_events.py:78-106` forbids `$ref` under `if`, `not`, `contains`, `propertyNames` and `dependentSchemas`. ADR 0012:57-60 wording is narrowed. Whole-document `oneOf` references remain untested, which the owner's freeze on the checker accepts |
| N-03 | MINOR | Superseded | The custom checker was deleted in c86db7c, and buf handles `reserved … to max` natively |
| N-04 | MINOR | Resolved | `baseline_guard.py:84-105` (fail-closed when schemas exist but no baselines are read; clear ref errors). Tested by `test_baseline_guard.py:11-35`. Pushes to main are documented in `baseline_guard.py:9-10` and ADR 0012:71-73. Residual: a partial read (some baselines missing) is not detected; accepted as within the checker freeze |
| N-05 | NIT | Resolved | `tools/bin/gitleaks-selftest:60-85`. GL-a, b, c and e are caught. Same-name widening into other directories (GL-d) survives; see F-11 |
| N-06 | NIT | Resolved / accepted | `request-validation-vectors.json:676` pins `maxItems`. The only `maxProperties` is on the response schema `ScoringResult` (`fraudshield-api.yaml:3222`), so the acceptance is correct |
| N-07 | NIT | Resolved | `contracts/webhooks/decision-final.md:73-75` |
| N-08 | NIT | Accepted (owner decision 5) | ADR 0012 records the conservative behaviour |
| N-09 | NIT | Resolved | `baseline_guard.py:84-95`, `test_guard_explains_a_missing_ref` (`test_baseline_guard.py:32`) |

## Status of the owner decisions

| # | Decision | Status | Evidence |
|---|---|---|---|
| 1 | Asymmetric dual control | **Partially met. MAJOR F-01, MINOR F-02 and F-03** | Correct: tightening applies at once with `confirmBy` = now + 24 h (`DualControlWorkflow.java:139-162`). Loosening stays pending until a different RISK_OFFICER approves (`:185-192`). Self-review returns 403 (`:256-259`). ADMIN, ANALYST and SENIOR_ANALYST get 403 (`:263-270`). DECLINE_AND_VERIFY → RELEASE is loosening (`ChannelThresholds.java:65-69`). Mixed changes are loosening (`:71-74`). A window change is loosening (`CircuitBreakerSettings.java:56`). Revert happens at exactly 24 h, with an injectable clock (`:236-243`, test `:99-128`), and produces a THRESHOLD_CHANGE audit event with a system actor (`ConfigAuditEvent.java:18`). Every public method is `synchronized`, so a check-then-act race cannot bypass one-open-change-per-kind. A revert cannot overwrite a later change, because no later change of that kind can exist while the tightening is open. Contract: propose (`fraudshield-api.yaml:1483`, `:1548`), pending list (`:1595`), approval (`:1644`), rejection (`:1680`), and the matrix (`authorisation-matrix.yaml:185-219`). Required tests exist and J1–J3, J5, C1 and C12 are caught. **Not met:** "tightening takes effect immediately" fails while any change of the kind is open (F-01). Contract/domain gaps: F-02. Classification test gaps: F-03. Demo seed: deferred to the M1 database seed (ADR 0014:75-76, FR-05-07 note). This is acceptable because no seed exists yet, but see F-10 |
| 2 | Public status-only health | **Met** | `fraudshield-api.yaml:202-223` (`getPublicHealth`, `ProbeStatus` only). `ProbeStatus` (`:3172`) has `additionalProperties: false` and `status` ∈ {UP, DOWN}. There are no version, hostname or component fields on any public path. Detail is on `/actuator/health/{ml,kafka,db,redis}` with `x-network: management` and a management-port server. Tests: `test_authorisation_matrix.py:163-187` and `:241-249`. C2, C3, C18 and C19 are caught. The deviation is on OPS-CI-07 and OPS-OBS-05 (`requirements.yaml:2621`, `:2713`) and in ADR 0014 §6. Stale proto comments: F-09 |
| 3 | Keep example.com addresses | Met | `schema-examples.yaml:4`. No other email domains were added |
| 4 | History rewrite | **Met, with MINOR F-05 and F-06** | Trees and squashes are verified above. My own run of the script: 15/15 pass. The full Python suites pass at every commit the script ran selectively. Only the feature branch moved. ADR 0015:18-19 has the owner's wording ("allowed on unmerged feature branches before merge, and forbidden on `main` and on tags"). The script's selective checks (F-05) and an unsupported ruleset claim (F-06) remain |
| 5 | buf replaces the custom proto checker | **Met, with MINOR F-04** | `tools/bin/buf:9-11` pins 1.72.0 with an archive and binary SHA-256 that match the release `sha256.txt`. `contracts/proto/buf.yaml` has lint STANDARD and breaking FILE with the reserved-deletion variants. `buf lint` is in `test_proto_breaking.py:52`. The guard runs `buf breaking` against the merge base (`baseline_guard.py:57-68`), wired in `ci.yml:115` with `fetch-depth: 0`. The custom checker, its tests and the baseline are deleted. The JSON Schema checker is untouched except for the N-02 test. The claim that tests cover "all cases the custom checker had" is overstated (F-04) |
| 6 | Delete m0/bootstrap only if it is an ancestor of main AND main is the default branch | Met (correctly not deleted) | The API reports `default_branch: m0/bootstrap`. The owner must switch the default branch to `main` before deleting |

## New findings

| ID | Severity | Location | Finding | Required action |
|---|---|---|---|---|
| F-01 | **MAJOR** | `DualControlWorkflow.java:129-132`; ADR 0014:63-64; `fraudshield-api.yaml` x-authorisation-rules `open-change-exists` on both propose operations; no withdraw operation in the contract or domain | Owner decision 1 says tightening takes effect immediately. The workflow instead refuses **any** proposal, tightening included, with 409 `open-change-exists` while a change of the same kind is open. (1) If RO-A proposes a loosening of one channel, nobody can tighten any of the six channels until a second officer approves or rejects it. `PENDING_APPROVAL` never expires, and the proposer cannot withdraw (self-rejection is 403, `:256`), so one open proposal blocks emergency tightening indefinitely. (2) After one tightening, a further tightening during the same fraud wave (for example a second channel an hour later) is refused for up to 24 h unless someone confirms the first. ADR 0014 presents the rule only as protection against revert overwrites, and its Consequences (`:141-142`) do not disclose that tightening can be blocked. J21 shows no test pins this behaviour either way | Make tightening always apply at once, as the owner decided. For example: allow a tightening while other changes are open; on revert, restore only the elements the change tightened, if they are unchanged since; and mark any pending loosening whose base version was overtaken as stale, so it must be re-proposed. Add a withdraw operation for the proposer (contract, matrix, domain, audit event). Add tests: tightening while a loosening is pending, a second tightening while one awaits confirmation, revert with a later change present, and withdraw. Alternatively, obtain and record an explicit owner decision accepting the restriction as a deviation, with withdraw still added |
| F-02 | MINOR | `fraudshield-api.yaml:3782-3801` (`ConfigReviewRequest`/`ConfigRejectionRequest.change_version`, `comment`); approval and rejection `404` (`:1644-1712`) vs `DualControlWorkflow.java:253-255` | Contract and domain disagree. (a) `change_version` is an enum of status names that the domain never receives or checks. Because an open change cannot move between the two open statuses, the field cannot detect a concurrent change, and its name suggests an optimistic-locking version it is not. The approval `comment` has no domain counterpart. (b) The contract answers an unknown `change_id` with 404, but the domain returns `CHANGE_NOT_OPEN`, which maps to 409. (c) No test ties `DualControlException.Refusal` statuses and problem URNs to the contract's `x-authorisation-rules`, so either side can drift silently | Remove `change_version`, or replace it with a real per-change version that the domain checks. Carry the reviewer comment in the domain and audit event, or drop it. Add a `NOT_FOUND` refusal (404). Add a contract test that reads the Refusal → status/URN table (for example from a shared vector file) against the matrix |
| F-03 | MINOR | `DualControlWorkflowTest.java:229-270` | Classification tests miss several directions: a lower minimum volume (tightening, J12), a shorter clean reset (loosening, J13), and a lower MEDIUM threshold alone (tightening, J15). J20 shows a real bypass: a mutant that applies a shorter clean reset immediately as "tightening" passes all 16 tests | Add one case per settings element and direction for both kinds (lower/higher MEDIUM, lower/higher HIGH, each policy switch, rate, volume, reset, window), including a workflow-level assertion that each loosening element ends in `PENDING_APPROVAL` |
| F-04 | MINOR | `contracts/tests/test_proto_breaking.py:57-149`; c86db7c message and ADR 0016:39-45 ("every mutation case the custom checker had") | Weakening buf goes undetected for several rules. Dropping only ENUM_VALUE_NO_DELETE_UNLESS_NAME_RESERVED (C7) or only …_UNLESS_NUMBER_RESERVED (C20) survives, so an enum value can be deleted with just one reservation. The field test has an "only number reserved" case (`:70-74`), but there is no enum equivalent and no "only name reserved" case for either. FIELD_SAME_ONEOF (C8) and MESSAGE/ENUM/SERVICE_NO_DELETE (C9) survive, although the deleted `proto_compat` reported message, enum and service removal and oneof membership changes. The old enum-renumber test case (`CHANNEL_USSD` 4 → 7) was not carried over | Add cases: enum value deleted with only its number reserved, and with only its name reserved; field deleted with only its name reserved; field moved into or out of a oneof; message removed; enum removed; enum value renumbered. Correct the ADR 0016 wording |
| F-05 | MINOR | `tools/bin/verify-branch-commits:48-58`; ADR 0015:22-29; `contracts-response.md` ("every commit passes on its own") | The script runs a package's tests only when the commit touches that package. My control run on the old history passed 065a66c although its tree fails two contract tests, because that commit touched only tools, Makefile, CI and `.gitleaks.toml`. For this branch I confirmed independently that the full suites pass at every commit, so the rewrite itself is sound. The tool, however, cannot prove the ADR's per-commit guarantee | Run all fast Python suites, plus `mvnw verify` when the backend exists in the tree, at every commit, or at least whenever any file changes outside `docs/`. Alternatively, cache by tree hash. Alternatively, state in ADR 0015 §3 that checks are selective and that the report is not proof for untouched components |
| F-06 | MINOR | ADR 0015:25 ("the `main` ruleset also blocks force pushes and deletions") | This claim is unsupported. The unauthenticated API returns `GET /repos/…/branches/main` → `protected: false`, `GET /repos/…/rules/branches/main` → `[]`, and `GET /repos/…/rulesets` → `[]`. The ADR relies on a safeguard that does not appear to exist | Ask the owner to create the ruleset (block force pushes and deletions on `main` and tags), or remove the sentence from the ADR |
| F-07 | NIT | Commit messages: e013785 (cites 556099c, 339ed23, 792ca4b, ced4324, 065a66c), e692225 ("superseded by buf (6920324)"), dba1021 | After the rewrite, these messages cite SHAs that no longer exist on the branch. `contracts-response.md` maps old SHAs to new ones and says it "includes the old-to-new SHA mapping", but the commit messages do not point to it. The response prose also says "7203304 → efb3d40", while its table says 0efa2cf → efb3d40 | No history change needed. Note in the response that SHAs in commit messages before e5276e8 are pre-rewrite, and reconcile 7203304 vs 0efa2cf |
| F-08 | NIT | `fraudshield-api.yaml` `ChannelThreshold.medium_timeout_policy` (optional); `:1486` ("Channels not listed keep their current values") | In a threshold proposal, it is unspecified whether an omitted `medium_timeout_policy` on a listed channel keeps the current value or resets to the default. A reset from DECLINE_AND_VERIFY would silently become a loosening. The `no_change` vector implies "keep". `ThresholdSet` also leaves the policy optional, while the domain requires it | State that an omitted policy keeps the current value, and require the policy in `ThresholdSet` (response) |
| F-09 | NIT | `contracts/proto/fraudshield/scoring/v1/scoring.proto:19`, `:35` | Comments still refer to `/api/v1/health/ml`, which owner decision 2 removed | Point them to `/actuator/health/ml` |
| F-10 | NIT | ADR 0014:68 ("`fraudshield_contracts` declares the rules"); `requirements.yaml` FR-05-07 note ("demo seed has two RISK_OFFICER accounts") | Overstated. The Python package does not declare the dual-control rules (only the OpenAPI document and the matrix do), and the seed does not exist yet. The deferral is acceptable but is recorded only in prose | Reword both. Record the two-risk-officer seed as a tracked obligation of the M1 database item (for example a seed test asserting two distinct active RISK_OFFICER accounts) |
| F-11 | NIT | `tools/bin/gitleaks-selftest:70-77` | Widening a path to the same file name in any directory (`^contracts/.*/schema-examples\.yaml$`, GL-d) is not detected. The planted copies sit only in the allowed directory and `elsewhere/` under `copied-N` names. The values are exact low-entropy placeholders, so the risk is negligible | Optionally, plant a copy under the fixture's own file name in a sibling directory. Owner decision 5 does not freeze the gitleaks self-test |

Counts: BLOCKER 0, MAJOR 1, MINOR 5, NIT 5.

## CI evidence for e5276e8

From the unauthenticated GitHub REST API, `actions/runs?head_sha=e5276e82dd6a9e8a9f41927ac1237aba84c10397`. All runs are `push` on `m1/contracts-events`.

| Workflow (run) | Job | Conclusion |
|---|---|---|
| ci (35205785602) | traceability-check, defect register, scope guard, commit messages | success |
| ci | java (checkstyle, junit, spotbugs, jacoco) | success |
| ci | gitleaks | success |
| ci | python (ruff, mypy --strict, pytest) | success |
| ci | pre-commit hooks on all files (G.5 guards) | success |
| ci | dependency licence inventory (ADR 0009) | success |
| ci | frontend (tsc, eslint, prettier, vitest) | success |
| stack (35205785623) | detect stack input changes | success |
| stack | core compose stack healthy + smoke test (M0 gate) | success |
| devcontainer (35205785618) | detect devcontainer input changes | success |
| devcontainer | **build devcontainer, post-create make ci, smoke test inside** | **success** (N-01 evidence, completed 2026-09-17T09:40:21Z) |

## Merge decision

**Fast-forward of `main` to e5276e8: NOT allowed.** CI is green in every job, including the devcontainer job, but MAJOR F-01 is open. After F-01 is fixed, or the owner explicitly accepts the restriction as a recorded deviation with a withdraw operation added, a focused re-check of F-01 through F-06 is enough. The rewrite does not need to be repeated. Owner actions that do not block the merge: switch the GitHub default branch to `main` (so decision 6 can be carried out), and create the `main`/tag ruleset that ADR 0015 assumes (F-06).

## Appendix: full suites at rewritten commits (reviewer run)

| Commit | contracts | tools | ml |
|---|---|---|---|
| ee3c685 | 334 passed | 132 passed | 14 passed |
| 40b80e3 | 415 passed | 132 passed | 14 passed |
| e389d5a | 415 passed | 132 passed | 14 passed |
| 696ea58 | 450 passed | 132 passed | 14 passed |
| e013785 | 450 passed | 132 passed | 14 passed |
| c86db7c | 457 passed | 132 passed | 14 passed |
| efb3d40 | 478 passed | 132 passed | 14 passed |
| e5276e8 | 478 passed | 132 passed | 14 passed |

The other commits (962cb40, dba1021, 50e250c, b6290b9, b538cd7, 437c401, e692225) ran the contracts, tools and ml suites inside `verify-branch-commits`. 437c401 also ran the full `mvnw verify`.
