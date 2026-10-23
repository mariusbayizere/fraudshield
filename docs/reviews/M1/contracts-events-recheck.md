# Focused re-check: M1 contracts, `m1/contracts-events` @ 0ea2f2f

Reviewer role: Principal Reviewer (independent; build prompt Part I.2)
Date: 2026-09-17T09:00Z (UTC)
Branch/commit: `m1/contracts-events` @ 0ea2f2f6389ca93db23c906cefd2dc1618701889 (= `origin/m1/contracts-events`),
directly on `main` @ fb9093f. Fix commits reviewed: 556099c (OpenAPI minors), 339ed23 (events), 792ca4b
(gitleaks self-test); response commit 0ea2f2f.
Inputs: `contracts-events-rereview.md` (NF-E01 … NF-E11 and its surviving mutations),
`contracts-openapi-recheck.md` (NEW-01 … NEW-07), `contracts-response.md` sections "Re-check of
m1/contracts-openapi" and "Re-review of m1/contracts-events".

Verdict: **CHANGES_REQUIRED**

Summary: the review findings themselves are resolved. Of NF-E01 … NF-E11, 10 are verified fixed and
1 is accepted as disclosed (NF-E07). Of NEW-01 … NEW-07, 6 are verified fixed and 1 is partly fixed
(NEW-03). All 16 surviving mutations from the re-review listed in the brief are now caught, directly or
by `fs-contract-baselines`. However, the head commit's **`devcontainer` workflow failed**:
`make ci` inside the devcontainer stops at `tools/bin/gitleaks-selftest` with
`/usr/bin/env: 'python3': No such file or directory`. This is a regression from this branch. On `main` at
fb9093f the devcontainer job passed, and the self-test first entered `make secrets-scan` in 065a66c.
Merging would turn `main` red and break the M0 Codespaces gate. There are 9 new findings:
**0 BLOCKER, 1 MAJOR, 3 MINOR, 5 NIT**.

Environment: isolated worktree `<scratchpad>/fs-recheck-events` at 0ea2f2f. Short-lived worktrees at
556099c and 339ed23 were used for per-commit checks. A throw-away clone `<scratchpad>/guardrepo` simulated
a published `main` for the guard. All were removed after the review.
`export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`; gitleaks 8.30.1 (pinned); Docker not used.

## Checks

| Command / check | Result |
|---|---|
| `uv sync --all-packages --locked` | ok |
| `cd contracts && uv run pytest -q` (0ea2f2f) | **450 passed, coverage 94.24 %** (claim in 339ed23: 450, 94.24 %) ✓ |
| `ruff check` / `ruff format --check` (tools ml contracts) / `mypy` (6 trees) at 0ea2f2f | passed / 53 files formatted / no issues in 48 files |
| `cd tools && uv run pytest -q`; `cd ml && uv run pytest -q` | 132 passed; 14 passed |
| **556099c** individually: ruff, format, mypy, contracts pytest | passed / 51 formatted / 46 files ok / **440 passed** (claim 440 ✓), 93.40 % |
| **339ed23** individually: ruff, format, mypy, contracts pytest, `tools/bin/gitleaks-selftest` (old self-test) | passed / 53 formatted / 48 files ok / **450 passed**, 94.24 % / exit 0, 9 planted |
| **792ca4b** individually | its tree differs from 0ea2f2f only in `contracts-response.md`, so the 0ea2f2f results apply ✓ |
| Rebuild claim: `git range-diff 556099c..f3fdc0e 556099c..339ed23`; `git diff 4159527 792ca4b` | Only difference: the `VALID_STAFF_PARAMETERS: dict[str, dict[str, Any]]` annotation in `test_events.py` (the mypy fix). The API reports 0 runs for f3fdc0e and 4159527, and no remote branch contains them, so no published history was rewritten ✓ |
| `tools/bin/gitleaks-selftest` | exit 0: "28 planted secrets detected in git and dir scans; 15 allowlisted fixtures otherwise clean" (claim 28 ✓) |
| `tools/bin/gitleaks git --log-opts=HEAD --redact --no-banner --exit-code 1 .` | 63 commits, no leaks, exit 0 |
| `tools/bin/gitleaks dir --redact --no-banner --exit-code 1 .` | no leaks, exit 0 |
| `uv run fs-contract-baselines --against origin/main` | exit 0: "merge base fb9093fa019c with origin/main; 0 published Kafka schemas, proto baseline not yet published; 0 breaking changes". This is expected, because nothing is published on `main` yet, so today the guard protects nothing on this branch |
| Guard in a throw-away clone with `main` := 0ea2f2f (G0–G6, below) | branch edits caught; vacuous on a push to `main` itself; exit 2 without `origin/main` |
| `uv run fs-commit-msg --rev-range fb9093f..HEAD` | 13 messages, 0 problems |
| Commit metadata fb9093f..0ea2f2f | author and committer `Marius Bayizere <bayizeremarius119@gmail.com>` on all 13; no Co-Authored-By or tool signatures |
| Added-line scan 3c24b6c..0ea2f2f (emails, URLs, private IPs, hostnames, `+250`/`250788`) | only `https://hooks.example.com/fraudshield` (RFC 2606, request vectors). No email-shaped literals, internal hosts or IPs |
| Unrelated changes in 556099c/339ed23/792ca4b | `requirements_matrix.md` regenerated (tags and line shifts), `ci.yml`/`Makefile` guard step, `contracts/pyproject.toml` script entry. All are disclosed in the commit messages ✓ |
| Counts in the response | 62 request vectors ✓; 18 signature vectors ✓; 8 staff kinds with valid parameters ✓ |
| `git status --porcelain` in the review worktree after every mutation | clean |

Observation (not a finding against 0ea2f2f): during the review, `/home/marius/fraudshield` (checked out on
`m1/contracts-events`) had uncommitted work that the reviewer did not make: `proto_compat.py` deleted,
`buf.yaml`, `tools/bin/buf` and `test_proto_breaking.py` added, edits to `baseline_guard.py`, `pyproject.toml` and `uv.lock`,
modified between 10:54 and 10:58 local time. The reviewer did not touch it. Any follow-up commit that swaps
`proto_compat` for `buf` needs its own review, and it will supersede N-03.

## Mutation table

Kafka, proto, webhook and OpenAPI mutations were applied in the worktree and followed by
`cd contracts && uv run pytest -q --no-cov`. Gitleaks mutations edited `.gitleaks.toml` and ran
`tools/bin/gitleaks-selftest`. After each run: `git checkout -- . && git clean -fdq`. Synthetic probes
(S) ran the checker functions in Python on constructed schema sets or compiled edited proto copies, and
checked real validity with `jsonschema`. Guard cases (G) are commits in a throw-away clone where `main` is
set to 0ea2f2f, so every baseline counts as published.

| # | Mutation | Result |
|---|---|---|
| R2b | `producer` `{2,40}`→`{2,30}` in schema **and** baseline | contract tests: 450 passed (by design). **G1: caught by `fs-contract-baselines`** ("pattern changed"), exit 1 |
| R2b′ | same, schema only | **caught** (`test_current_schemas_are_backward_compatible_with_their_baseline`) |
| R5b | `Score` returns `stream ScoreResponse` | **caught** (`test_proto_matches_its_committed_baseline`); **G3** (proto + regenerated baseline): **caught by the guard** |
| R5c | reserve 12 → drop reservation → `string shap_base_value = 12` | **caught** at the unreserve step (`test_proto_checker_rejects_dropping_a_reservation_to_reuse_it`); **G3b** across two branch commits: **caught by the guard** (type changed) |
| P1 | client streaming: `rpc Score(stream ScoreRequest)` | **caught** (baseline test); S: `breaking_changes` reports "streaming mode changed" |
| P2 | (S) message `reserved 12, 100 to 103` → `100 to 101` / → `100, 102 to 103` / name dropped / drop and reuse 102 | **caught** ×4 |
| P3 | (S) enum `reserved 4, 100 to 102` → `100 to 101` / drop and reuse 102 / name dropped | **caught** ×3 |
| P5 | (S) `option java_package` changed | survived (not required; the re-review only said "consider") |
| P6 | (S) `reserved 12, 1000 to max`, baseline compared with itself | **did not finish in 20 s** (N-03) |
| R7b | vector value → `^whsec_test_[0-9a-f]{32}$` | **caught** (self-test stops: "is a pattern, not a value") |
| R7d | schema-examples value → `^whsec_.*$` | **caught** (pattern) |
| R7f | Kafka allowlist `paths` → `.*` | **caught**, but by a crash: `PermissionError: '/copied-4.txt'`, because the self-test writes outside its temp directory (N-05) |
| R7g | schema-examples allowlist `paths` removed | **caught** (copies in `elsewhere/` NOT DETECTED) |
| R7h | Kafka allowlist `paths` removed | **caught** |
| GL1 | Kafka value written as a pattern `^tok_[A-Za-z0-9]{24}$` | **caught** (pattern) |
| GL9 | value with an unescaped `.` wildcard | **caught** (pattern) |
| GL2 | vector allowlist with no `paths` | **caught** |
| GL6 | schema-examples allowlist `condition = "OR"` | **caught** |
| GL10 | new global allowlist `stopwords = ["prod"]` | **caught** |
| GL3 | vector path → `^contracts/webhooks/.*\.json$` | **survived** (N-05) |
| GL4 | Kafka path → `^contracts/.*\.json$` | **survived** (N-05) |
| GL5 | Kafka path loses its `$` anchor | **survived** (N-05) |
| GL7 | new allowlist with only `paths = ['^ops/']` for `generic-api-key` and `fraudshield-api-key` | **survived** (N-05) |
| GL8 | rule-level `[[rules.allowlists]] paths = ['^ops/']` on `fraudshield-api-key` | **survived** (N-05) |
| R8b | verifier accepts an identical duplicate `t` | **caught** (`[the same t twice]`) |
| R8c | unknown header key → MALFORMED | **caught** (`[unknown key ignored …]`) |
| W1 | `_TIMESTAMP` back to `[0-9]{1,12}` | **caught** (`[t with a leading zero]`) |
| W2 | `_TIMESTAMP` drops the `0` alternative | survived (equivalent in practice: `t=0` is always outside the replay window) |
| R10 | `WelcomeParameters` requires undeclared `user_name` (schema + baseline) | **caught** (`test_every_staff_notification_kind_accepts_its_own_parameters_only[WELCOME]`) |
| R10b | WELCOME bound to `ModelGateResultParameters` (schema + baseline) | **caught** (same test) |
| R11 | staff `recipient_user_id` `oneOf[1]` → `[null, string]`, schema only | **caught** (set checker: "oneOf changed") |
| R11′ | R11 in schema **and** baseline | contract tests: caught only incidentally (the R11 self-test mutates an already mutated baseline). **G2: caught by the guard** |
| R11b | `common.$defs.Uuid.type` → `[string, null]` | **caught** by the set checker ("a oneOf uses it"), plus 13 example/identity tests |
| R12 | envelope `payload` gains `properties.notification_id: integer` | **caught** by the set checker ("constrained property added to an open object"), plus examples |
| K1 | (S) `$def` reachable only transitively from `oneOf` (oneOf → Wrapper → Id) widened so branches overlap | **caught** |
| K2 | (S) `items` narrowed inside a `oneOf` branch | **caught** |
| K3 | `anyOf` branch narrowed (staff `allOf[0].anyOf[0]` requires one more key); (S) `maxLength` inside `anyOf` | **caught** / **caught** |
| K7 | `allOf` member removed (alert `allOf[1]`); (S) generic | **caught** (conservative; the removal actually widens) |
| K4 | (S) enum widened in a `$def` used inside an `if` condition (real break: the old-valid instance now falls into `then`) | **survived** (N-02) |
| K5 | (S) enum widened in a `$def` used under `not` (real break) | **survived** (N-02) |
| K8 | (S) enum widened in a `$def` used by `contains` + `maxContains` (real break) | **survived** (N-02) |
| K6b | (S) whole-document `$ref` (no fragment) from `oneOf`, target type widened (real break) | **survived** (N-02) |
| K-ann | `description` added to `common.$defs.Uuid` | reported as breaking (false positive; N-08) |
| T1 | `topics.yaml` `schema_version` 1→2 | **caught** (examples) |
| G4 | R2b committed directly on `main` (merge base = HEAD) | guard **0 breaking changes, exit 0** (vacuous; N-04) |
| G5 | clone without `origin/main`, `--against origin/main` | exit 2 "Not a valid object name origin/main" (N-09) |
| G6 | `--against` a ref with no common history | exit 2, empty message "git failed: " (N-09) |
| GD1 | `baseline_guard._has` always returns False | **survived**: 450 passed (N-04) |
| GD2 | `KAFKA_BASELINE` path typo | **survived**: 450 passed (N-04) |
| NEW01a | `_branch_codes` takes the first candidate branch again | **caught** (2 RuleDraft vectors) |
| NEW01b | tie-break by error count only (required-key rule removed) | survived (N-06) |
| NEW01c | over-matched `oneOf` → `type_mismatch` instead of `unsupported_value` | survived (N-06) |
| K2 (re-check) | `minLength` removed from `KEYWORD_CODES` | **caught** |
| K4 (re-check) | `maxItems` → `out_of_range` | **survived** (NEW-03 partly fixed) |
| K5 (re-check) | `minProperties` removed | **caught** |
| K9 | `maxProperties` → `out_of_range` | survived (N-06) |
| N06-R3 | `self-modification` removed from contract **and** matrix | **caught** (`test_self_elevation_and_self_override_rules_cannot_be_dropped`) |
| N02-H | `x-network: management` removed from `/actuator/health/kafka` | **caught** |
| N07 | reference rejects `Cn` again | **caught** (U+0378 accept vector) |

Score. The 16 re-review survivors named in the brief (R2b, R5b, R5c, R7b, R7d, R7f, R7g, R7h, R8b, R8c,
R10, R10b, R11, R11b, R12, plus the review's R11 two-file variant) are **all caught**, R2b and R11′
by the guard as designed. Of 60 mutations in total, 44 were caught, 13 survived (one of them, W2,
equivalent), and P6 did not terminate. The surviving mutations are in new code paths (N-02, N-04, N-05,
N-06) or were optional (P5).

## Re-review findings (events)

| # | Sev | Status | Evidence |
|---|---|---|---|
| NF-E01 | MAJOR | **VERIFIED_FIXED** | `compatibility.py:92-105` (`fragile_definitions`, transitive), `:108-118` (set comparison, removed schemas), `:189-196` (constrained property added to an open object, or checked against the old `additionalProperties` schema), `:212-217` (fragile `$defs`), `:224-225` (any `oneOf` change). Tests: `test_compatibility.py:41-45`, `:47-50`, `:53-85` (R11, R11b, R12), `:88-94`. R11, R11b, R12, K1–K3 caught. Remaining latent gap for `if`/`not`/`contains` and whole-document references: N-02 |
| NF-E02 | MAJOR | **VERIFIED_FIXED** | `proto_compat.py:95-106` (streaming flags in the summary), `:143-144`, `:148-159` (reservations never dropped, range by range), `:182-185` and `:200-201` (reuse of reserved numbers and names for fields and enums). Baseline regenerated (`scoring-v1.json` services). Tests: `test_compatibility.py:230-234` (R5b), `:295-311` (R5c), `:314-330` (enum reuse). R5b, R5c, P1–P3 caught. Performance defect with `to max`: N-03 |
| NF-E03 | MINOR | **VERIFIED_FIXED** | `tools/bin/gitleaks-selftest:52-68` (exact values read from `.gitleaks.toml`; patterns stop the test), `:107-138` (new values in each allowlisted path, hex and alphanumeric), `:143-149` (each value copied to `elsewhere/` and next to its file). R7b, R7d, R7f, R7g, R7h all caught. Residual widenings and allowlist kinds not examined, and the response's "vector path widened within its directory" holds only for some widenings: N-05 |
| NF-E04 | MINOR | **VERIFIED_FIXED** | `contracts/src/fraudshield_contracts/baseline_guard.py:37-84`; `ci.yml:114-115` (governance job, `fetch-depth: 0`, step success in CI); `Makefile:99`; `test_baseline_guard.py:21-44`. G1, G2, G3 and G3b caught in a simulated publish. It is fail-open and vacuous on pushes to `main`: N-04. It exits 2 without `origin/main`: N-09 |
| NF-E05 | MINOR | **VERIFIED_FIXED** | ADR 0012 `docs/adr/0012-event-and-scoring-contracts.md:61-63` (a breaking change becomes a new topic `<topic>.v<N>` with its own schema file; each topic binds one version); `contracts/kafka/topics.yaml:16-17`; `contracts/kafka/baseline/README.md:3-8`. The single-version binding in `events.py` now matches the ADR |
| NF-E06 | MINOR | **VERIFIED_FIXED** | `contracts/webhooks/decision-final.md:73-78` (sequence 1 is not sent; a newer state cancels pending retries and is sent immediately); ADR 0012 §7. Wording on batch ingest: N-07 |
| NF-E07 | MINOR | **ACCEPTED** (disclosed) | `contracts-response.md` NF-E07 row: ced4324 and 065a66c are red after the rebase and fixed in b419e1e; the stale "384 tests, 92.42 %" is explained; no further history rewrite |
| NF-E08 | MINOR | **VERIFIED_FIXED** | `contracts/tests/test_events.py:222-250`: one valid parameter set per kind (8), each rejected under the other kinds' parameters. R10 and R10b caught |
| NF-E09 | NIT | **VERIFIED_FIXED** | `webhooks.py:16-17` (`0\|[1-9][0-9]{0,11}`, `fullmatch` at `:41`); `decision-final.md:41-44` (canonical `t`, unknown keys ignored, signed text is `t` as sent); 3 new vectors (`signature-test-vectors.json`, 18 in total). R8b, R8c and W1 caught |
| NF-E10 | NIT | **VERIFIED_FIXED** (test side); owner decision open on `example.com` examples on `main` | `gitleaks-selftest:182` (`user.email=selftest`), `test_events.py:208` (email built at run time); no email-shaped literal added in 3c24b6c..0ea2f2f |
| NF-E11 | NIT | **VERIFIED_FIXED** | `test_compatibility.py:110`, `:189-190`; ADR 0012:57-58 (making a field optional is compatible); response CR-11 row corrected |

Counts: VERIFIED_FIXED 10 · PARTIALLY_FIXED 0 · NOT_FIXED 0 · ACCEPTED 1.

## Re-check findings (OpenAPI, fixed on this branch in 556099c)

| # | Sev | Status | Evidence |
|---|---|---|---|
| NEW-01 | MINOR | **VERIFIED_FIXED** | `contracts/src/fraudshield_contracts/validation.py:80-107` (branch ruled out by type; the branch with its required keys present wins, then the fewest errors; an over-matched `oneOf` gives `unsupported_value`). RuleDraft vectors "unknown operator inside all/at the root". NEW01a caught. Untested tie-break and over-match: N-06 |
| NEW-02 | MINOR | **VERIFIED_FIXED** | `contracts/openapi/fraudshield-api.yaml:262-290` (`/actuator/health/kafka`, `x-network: management` at `:279`); `authorisation-matrix.yaml:27-29`; ADR 0014:77-81; `requirements.yaml` OPS-CI-07 and OPS-OBS-05 notes. N02-H caught |
| NEW-03 | MINOR | **PARTIALLY_FIXED** | 62 vectors; `test_validation_vectors.py:138-146` requires every mapped code to be used. The review's survivors: K2 and K5 are now caught, but **K4 (`maxItems` → `out_of_range`) still survives**. The suggested 1,001-item batch vector was not added (only "empty batch", `request-validation-vectors.json:1294`), because `item_count_out_of_range` is already "used" through `minItems`. Track under N-06 |
| NEW-04 | NIT | **VERIFIED_FIXED** | `docs/adr/0011-api-contract-conventions.md:68-70` |
| NEW-05 | NIT | **VERIFIED_FIXED** | `docs/traceability/requirements.yaml` OPS-CI-07 and OPS-OBS-05 `deviations: [docs/adr/0014-staff-authorisation-model.md]`; checked by `fs-traceability check` (governance job success) |
| NEW-06 | NIT | **VERIFIED_FIXED** | `contracts/tests/test_authorisation_matrix.py:163-175`; N06-R3 (two-file removal) caught |
| NEW-07 | NIT | **VERIFIED_FIXED** | ADR 0014 §7; `fraudshield-api.yaml` `Password` description; `password-vectors.json:40` (U+0378 accepted); reference `test_validation_vectors.py:175-176`. N07 caught |

Counts: VERIFIED_FIXED 6 · PARTIALLY_FIXED 1 · NOT_FIXED 0 · ACCEPTED 0.

## New findings

| # | Severity | Location | Finding | Required action |
|---|---|---|---|---|
| N-01 | **MAJOR** | `Makefile:113` (`secrets-scan` → `tools/bin/gitleaks-selftest`); `tools/bin/gitleaks-selftest:1` (`#!/usr/bin/env python3`); `.devcontainer/devcontainer.json` (`UV_PYTHON_PREFERENCE: only-managed`, base image without `python3`); `.devcontainer/post-create.sh:62` | The `devcontainer` workflow for 0ea2f2f **failed**: run 35200932638, job "build devcontainer, post-create make ci, smoke test inside" = failure. The post-create log annotation shows every governance step passing, including `fs-contract-baselines`, and both gitleaks scans clean. Then: `tools/bin/gitleaks-selftest` → `/usr/bin/env: 'python3': No such file or directory` → `make: *** [Makefile:113: secrets-scan] Error 127`. `make ci` in the devcontainer is the M0 Codespaces gate. It passed on `main` at fb9093f (run 35197895202), so this branch introduces the regression (065a66c added the self-test to `secrets-scan`). The previous re-review saw the job cancelled or skipped, so the failure was not visible then. The response does not mention it | Make `make secrets-scan` independent of a system `python3`, e.g. `uv run python tools/bin/gitleaks-selftest` in the Makefile. The CI `secrets` job, which has `python3` and no uv, can keep calling the script directly. Push, get the `devcontainer` workflow green on the new head, and record it in the response before merging |
| N-02 | MINOR | `contracts/src/fraudshield_contracts/compatibility.py:69-79` (`_oneof_refs` looks only under `oneOf`), `:212-217` (only `$defs` entries can be fragile); module docstring `:8`; ADR 0012:57-58 ("widening a type, enum or bound (outside a `oneOf`) is compatible") | Widening can also reject previously valid messages when the widened definition is used under `if` (moves instances into `then`), `not`, or `contains`/`maxContains`, or when a `oneOf` branch references a whole document without a fragment. Probes K4, K5, K8 and K6b are real breaks (checked with `jsonschema`), and the checker returns `[]`. Today's schemas have no `$ref` under `if`/`not`/`contains` and only fragment references, so this is latent. The ADR statement is too broad and the owner rule ("a test fails on a breaking Kafka change") is not guaranteed for future schemas | Treat definitions reachable from `oneOf`, `not`, `if` and `contains` (and whole-document references) as fragile, with self-tests for K4, K5, K8 and K6b. Or add a test that fails when a schema introduces `$ref` under those keywords, and narrow the ADR 0012 §5 wording |
| N-03 | MINOR | `contracts/src/fraudshield_contracts/proto_compat.py:115-116`, `:148-153` (`all(_in_ranges(n, …) for n in range(start, end + 1))`) | Reservation checks enumerate every number in a reserved range. `reserved 1000 to max` (a common protobuf idiom; message max 536,870,911, enum max 2,147,483,647) makes `breaking_changes` run for minutes: P6 did not finish in 20 s when comparing the proto with itself. `test_proto_matches_its_committed_baseline` and the guard would then hang until the CI job timeout | Compare ranges arithmetically (each old range covered by the union of new ranges), and add a self-test with `to max` for a message and an enum. If the uncommitted `buf` replacement in the author's checkout lands, this is superseded; review that change separately |
| N-04 | MINOR | `contracts/src/fraudshield_contracts/baseline_guard.py:37-64`, `:66-84`; `contracts/tests/test_baseline_guard.py:13-18`; ADR 0012:65-69 | (a) **Fail-open.** If the baselines cannot be read at the merge base (moved directory, path typo, a bug in `_has`), the guard prints "0 published Kafka schemas, proto baseline not yet published" and exits 0. GD1 and GD2 survive all 450 tests: `test_published_baselines_are_read_from_git_history` accepts `{}` and `None`. (b) **Vacuous on `main`.** On a push to `main` the merge base with `origin/main` is HEAD, so a schema and baseline edited together and pushed straight to `main` pass (G4). Protection exists only on branch and PR runs, and the ADR does not say so | Fail (exit 2) when the merge base contains `contracts/kafka/schemas` or the proto but no baseline was read. At HEAD, assert 13 Kafka baselines and a proto baseline. Document that the guard protects branch and PR runs, or on `main` pushes compare with `github.event.before` |
| N-05 | NIT | `tools/bin/gitleaks-selftest:52-68`, `:143-153`; `contracts-response.md` NF-E03 row | Remaining blind spots. (1) Path widenings that keep the fixture's file shape survive: GL3 `^contracts/webhooks/.*\.json$`, GL4 `^contracts/.*\.json$`, GL5 (no `$`). The copies are always named `copied-N.txt`, so they never match such widenings. The response claims "vector path widened within its directory" is caught, which holds only for widenings that match `.txt`. (2) Allowlists without `regexes` (GL7), rule-level allowlists (GL8) and a `.gitleaksignore` file are never examined, although `.gitleaks.toml:2-3` says nothing is allowed by pattern alone. (3) A path regex with no `/` gives an empty directory, so the self-test writes `/copied-N.txt` outside its temp repository (R7f: `PermissionError`, which fails closed) | Name copies in each allowed directory in the fixture's shape (e.g. `fs.zz.json`, `other-vectors.json`, `schema-examples.yml`) and also plant in a sibling directory. Fail on any allowlist without `regexes`, any `[rules.allowlist(s)]`, and any `.gitleaksignore`. Build plant paths with a guard that keeps them inside the temp repository. Correct the response wording |
| N-06 | NIT | `contracts/validation/request-validation-vectors.json`; `validation.py:80-106` | Mapping rows without a vector: `maxItems` (K4, the NEW-03 survivor) and `maxProperties` (K9) can be remapped to `out_of_range`. Also unpinned: the over-matched `oneOf` → `unsupported_value` rule (NEW01c) and the required-keys tie-break (NEW01b) | Add vectors: a batch of 1,001 items, a `maxProperties` case if any request schema uses it, a value matching two `oneOf` branches, and a case where required-keys and fewest-errors choose different branches. Or make `test_vectors_exercise_every_error_code_the_mapping_produces` compare per keyword, not per code |
| N-07 | NIT | `contracts/webhooks/decision-final.md:73-74` vs `fraudshield-api.yaml:115-147` (`ingestTransactionBatch`, 202 asynchronous) | "Sequence 1 … is never sent by webhook: the synchronous ingest response already delivered it". Batch items get sequence 1 only in `GET /jobs/{job_id}` results (`JobStatus.results[].decision`), not in a synchronous response | Reword: sequence 1 is returned by the ingest response or the batch job results, and never sent by webhook |
| N-08 | NIT | `compatibility.py:216` (`_same` on raw JSON); ADR 0012 §5 | Any edit to a definition used by a `oneOf` counts as breaking, even an annotation (K-ann: a `description` on `common.$defs.Uuid`) or an enum value added to `Role`/`FinalDecisionValue`. Under the new-topic rule, a documentation edit to `Uuid`, `Timestamp`, `Token`, `Role` or `FinalDecisionValue` would force new versions of several topics. Conservative and documented, but costly | Strip `ANNOTATIONS` before comparing fragile definitions. Optionally accept widenings of a branch that stays type-disjoint from its sibling branches (e.g. `$ref` vs `{type: null}`) |
| N-09 | NIT | `baseline_guard.py:67`, `:94-97`; `Makefile:99` | `make governance` exits 2 in any clone without an `origin/main` ref (G5): a fork with an `upstream` remote, a mirror, or a local repository with no remote. When no merge base exists, the message is an empty "git failed: " (G6) | Print which ref or merge base is missing and how to fetch it. Consider `--against` defaulting to `origin/main` with a fallback to `main` |

New-finding counts: BLOCKER 0 · MAJOR 1 · MINOR 3 · NIT 5 (9).

## CI evidence (unauthenticated GitHub REST API; job-level conclusions)

| Commit | Workflow run | Jobs |
|---|---|---|
| 0ea2f2f | `ci` 35200932661 (push, completed, **success**) | python (ruff, mypy --strict, pytest): **success**; java: **success**; frontend: **success**; dependency licence inventory: **success**; gitleaks: **success** (step `tools/bin/gitleaks-selftest` success, system `python3`); traceability-check, defect register, scope guard, commit messages: **success** (step `uv run fs-contract-baselines --against origin/main` success, full history); pre-commit hooks on all files: **success** |
| 0ea2f2f | `stack` 35200932649 (completed, **success**) | detect stack input changes: success; core compose stack healthy + smoke test (M0 gate): **success** |
| 0ea2f2f | `devcontainer` 35200932638 (completed, **failure**) | detect devcontainer input changes: success; build devcontainer, post-create make ci, smoke test inside: **failure**. Annotation "post-create log tail": governance clean (`fs-contract-baselines` 0 breaking changes), `gitleaks git`/`dir` no leaks, then `tools/bin/gitleaks-selftest` → `/usr/bin/env: 'python3': No such file or directory`, `make: *** [Makefile:113: secrets-scan] Error 127` (N-01) |
| 792ca4b, 339ed23, 556099c | none (`total_count: 0`) | pushed together with 0ea2f2f; covered locally (checks table) |
| f3fdc0e, 4159527 (pre-rebuild) | none (`total_count: 0`) | never published |
| fb9093f (`main`) | `ci` 35197895291, `stack` 35197895156, `devcontainer` 35197895202 | all **success**, including the devcontainer build (confirms the N-01 regression is new on this branch) |

The python job (shallow checkout) runs `test_baseline_guard.py`, which uses only `HEAD` and a missing
ref, and it passed. The guard itself runs only in the governance job (`fetch-depth: 0`) and in the
devcontainer (`fetch-depth: 0`, `origin/main` present). Both resolved the merge base fb9093f.

## Merge decision

**No.** `m1/contracts-events` may not be fast-forward merged to `main` while N-01 (MAJOR) is open.
The head commit's `devcontainer` workflow is red, and merging would break `make ci` in Codespaces on
`main`. Fix N-01 in a new commit, get all three workflows green on the new head, and record this in the
response. A focused re-check of that commit and its CI is then enough. The review findings themselves are
closed: NF-E01 … NF-E11 are fixed or accepted, and NEW-03 is only partly fixed. Before M6/M7 depend on
the checkers, fix the MINOR findings N-02 … N-04 and NEW-03's K4 vector, or track them as issues. The
NITs can be fixed or explicitly accepted in the response.
