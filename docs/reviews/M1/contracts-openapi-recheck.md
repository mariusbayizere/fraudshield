# Focused re-check: M1 contracts, `m1/contracts-openapi` @ fb9093f

Reviewer role: Principal Reviewer (independent, build prompt Part I.2)
Date: 2026-09-17 (UTC)
Branch/commit: `m1/contracts-openapi` @ fb9093fa019cae7b9db57ddb0356fa817c5a2710 (= `origin/m1/contracts-openapi`;
base `main` 1b99782). Fix commit reviewed: 70f6283; response commit: fb9093f.
Inputs: `contracts-openapi-rereview.md` (c16d392, NF-01 … NF-15), `contracts-response.md` section
"Re-review of m1/contracts-openapi (NF-01 … NF-15)", build prompt E.1, E.6, E.8, G.3, I.2.
Scope: NF-01 … NF-15, the survivors of the previous mutation run, regressions from 70f6283 and the owner
constraints. Events-branch findings (CR-11 … CR-17, CR-34 and the events parts) are out of scope.
Verdict: **APPROVED_WITH_MINORS**

Summary: all 15 NF findings are verified as fixed (NF-01 and NF-02, the two MAJOR findings, included).
All 13 earlier survivors are now caught. 42 of 50 mutations were caught; the 8 survivors are 2 accepted
two-file edits, 1 wording-only change, 1 equivalent mutant, 1 response-shape change with no invariant by
design, and 3 gaps in the keyword mapping (NEW-03). Every number in the commit message and the response
was reproduced. The fix commit adds **0 BLOCKER, 0 MAJOR, 3 MINOR and 4 NIT** new findings.

Environment: isolated worktree `<scratchpad>/fs-recheck-openapi` at fb9093f (removed after review);
`export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`; no Docker; `make ci` not run in full (CI jobs
quoted below).

## Checks re-run

| Command (worktree at fb9093f) | Result |
|---|---|
| `uv sync --all-packages --locked` | ok, lockfile unchanged |
| `cd contracts && uv run pytest -q` | **292 passed, coverage 98.31 %** (claim in 70f6283: 292, 98.31 %) ✓ |
| `ruff check contracts` / `ruff format --check contracts` / `mypy contracts/src contracts/tests` | passed / 9 files formatted / no issues in 8 files |
| `fs-traceability check`; `fs-defect-register --check`; `fs-scope-guard` | 258 rows, 89 tagged tests, 0 errors, 0 warnings; 51 defects up to date; 0 files |
| `tools/bin/gitleaks git --log-opts=c16d392..fb9093f`; `gitleaks dir contracts`; `gitleaks dir docs` | no leaks found (all three) |
| Operations; operations with a request body; 400 **and** 422 `ValidationProblem` | 79 (78 + `getMlComponentHealth`); 31 bodies: 30 JSON/multipart all have both ✓ ("all 30" in the response ✓); `submitVerification` (form-urlencoded, HTML) has neither (NEW-04) |
| Composed closed schemas; one wrong-typed value in each example | 8 (claim 8) ✓; each reports exactly 1 `type` error at the field ✓ (NF-01) |
| Password vectors | 8 accept / 16 reject, 7 CHARACTERS (claim: 7 new + currency accept) ✓ |
| Request vectors; codes used by vectors vs `ValidationErrorCode` | 45 unchanged; no vector uses `length_out_of_range`, `item_count_out_of_range`, `duplicate_items`, `escalation_target_not_higher` (NEW-03) |
| `grep -rn 'escalation-target-not-higher\|too_many_items\|StaleAlert\b'` outside `docs/reviews` | empty (no stale references in backend, frontend or docs) ✓ |
| `error_codes("RuleDraft", …)` with one bad leaf `op` in `expression` | `{expression.all: required, expression.field/op/value: unknown_field}` → status 400; expected `expression.op: unsupported_value` → 422 (NEW-01) |
| Commit authors and trailers 1b99782..fb9093f | 11 commits, author and committer `Marius Bayizere <bayizeremarius119@gmail.com>`; no Co-Authored-By, Signed-off-by or tool signature ✓ |
| PII scan of lines added in c16d392..fb9093f | no e-mail addresses, hostnames, URLs or phone numbers; only `0000000001` and package versions ✓ |
| History | c16d392 is an ancestor of fb9093f; `origin/m1/contracts-openapi` = fb9093f; `origin/main` 1b99782 is an ancestor (fast-forward possible); nothing rewritten ✓ |
| Unrelated changes | 70f6283 also regenerates `requirements_matrix.md` (line shifts and new tags only) and adds OPS-CI-07/OPS-OBS-05 notes (disclosed); fb9093f also corrects the CR-28 row (disclosed) ✓ |
| Worktree `git status --porcelain` after all mutations | clean |

## Mutation spot checks

Applied by loading and re-dumping YAML/JSON (control re-dump: 292 passed) or by text edit of
`validation.py`; each run `cd contracts && uv run pytest -q --no-cov`, then `git checkout -- .`.

| # | Mutation | Result |
|---|---|---|
| N2 | `self-modification` rule removed from `updateUser` (contract only) | **caught** |
| N2b | `own-decision-override` removed from `overrideAlertDecision` | **caught** |
| N2c | escalation-target rule status 422 → 403 | **caught** |
| N3b | "amount as a JSON number" → 400 + `required` | **caught** |
| N3c | "raw MSISDN as account_id" → 422 `invalid_format` | **caught** |
| N4b | `LoginRequest.password.maxLength` 1024 → 72 | **caught** |
| N4d | `changePassword.current_password.maxLength` 1024 → 72 | **caught** |
| N6c | `supersedes_decision` removed from `FinalDecision.required` | **caught** |
| N6d | `FinalDecision.event_id` `format: uuid` removed | **caught** |
| N9 | `webhook_signing_secret.pattern` → `^whsec_.*` | **caught** |
| N13 | RISK_OFFICER added to `escalateAlert` in contract and matrix | **caught** |
| N17 | `listAlerts` filter rule removed | **caught** |
| N17b | filter rule text replaced by "all entries" and description sentence removed | survived (matrix pins `effect: filter`, not its content; NEW-06) |
| N19 | `register` 409 restored | **caught** |
| N20 | `login` 403 removed | **caught** |
| K1 | `uniqueItems` removed from `KEYWORD_CODES` | **caught** |
| K2 | `minLength` removed from `KEYWORD_CODES` | **survived** (NEW-03) |
| K3 | `enum` → `invalid_format` | **caught** |
| K4 | `maxItems` → `out_of_range` | **survived** (NEW-03) |
| K5 | `minProperties` removed from `KEYWORD_CODES` | **survived** (NEW-03) |
| K6 | canonical-decimal rule removed (always `invalid_format`) | **caught** |
| K7 | type-mismatch filter removed | **caught** |
| K8 | `oneOf`/`anyOf` branch choice always `type_mismatch` | **caught** |
| C1 | `AlertSummary` listed names gain `anomaly_raw` | **caught** |
| C2 | `AlertDetail` listed names lose `decisions` | **caught** |
| C3 | `AlertDetail` gains `shap_all` in its member **and** its listed names | survived; by design: a consistent response addition, and no exact-set invariant exists for `AlertDetail` (only `DecisionResponse` has one, CR-18) |
| C4 | `ApiKeyCreated.additionalProperties: false` removed | **caught** |
| C5 | `RuleDraft` closed with `unevaluatedProperties` again (NF-01 regression) | **caught** |
| I1 | `required: [channel]` removed from the ingest `if` | **caught** |
| I2 | `required: [decision]` removed from the `FinalDecision` HOLD `if` | **caught** |
| I3 | `required: [risk_tier]` removed from the `DecisionResponse` HIGH `if` | **caught** |
| R1 | `self-modification` status 403 → 409 in contract **and** matrix | **caught** (the parametrised `pop(0)` self-test changes) |
| R2 | escalation rule code → `out_of_range` in contract and matrix | survived; accepted two-file edit (ADR 0014 §2) |
| R3 | `self-modification` removed from contract **and** matrix | survived; two-file edit, but FR-07-01 has no invariant (NEW-06) |
| P1 | U+00A0 vector `schema_detectable` false → true | **caught** |
| P2 | "NoSpecial 123" `schema_detectable` true → false | **caught** |
| P3 | special pattern → `[^A-Za-z0-9]` (space counts) | **caught** |
| P4 | special pattern → `[^A-Za-z0-9\\s]` (pre-fix) | survived; **equivalent** on every password the reference accepts (every `\s` match except U+0020 is category C or Z) |
| P5 | all CHARACTERS vectors deleted | **caught** |
| P6 | U+00A0 vector reason → SPECIAL | **caught** |
| S1 | `StateConflict` `then` → `Problem` (stale-alert without `current`) | **caught** |
| S2 | `current` removed from `StaleAlertProblem.required` | **caught** |
| S3 | `StateConflict` reverted to `anyOf [StaleAlertProblem, Problem]` | **caught** |
| V1 | 422 removed from `createApiKey` | **caught** |
| V2 | 422 removed from `uploadTrainingDataset` (multipart) | **caught** |
| V3 | `escalateAlert` 422 → `AuthorisationRuleViolation` | **caught** |
| V4 | "no prefix" vector field `cidr` → `description` | **caught** |
| V5 | server-only "duplicate channel" marked `schema_detectable` | **caught** |
| J1 | `IdempotencyConflictProblem` status const removed | **caught** |
| J2 | batch item `problem` → `ValidationProblem` only | **caught** |
| H1 | `x-network` removed from `getMlComponentHealth` | **caught** |
| H2 | path-level management `servers` removed from `/actuator/health/ml` | **caught** |
| H3 | `/health/kafka` made public in contract and matrix | **caught** |

Score: 42 of 50 caught (control excluded). All 13 earlier survivors (N2, N2b, N2c, N3b, N3c, N4b, N6c,
N6d, N9, N13, N17, N19, N20) are caught.

## NF findings

| # | Sev | Status | Evidence |
|---|---|---|---|
| NF-01 | MAJOR | **VERIFIED_FIXED** | No `unevaluatedProperties` left; the 8 composed schemas list member names beside `additionalProperties: false` (`fraudshield-api.yaml:2966,3008,3338,3584,3643,3666`). One bad value → one error in all 8 (probe). `test_openapi_examples.py:112` pins listed = member names and count 8; `:133` pins the vector case; C1, C2, C4, C5 caught |
| NF-02 | MAJOR | **VERIFIED_FIXED** | 30/30 JSON or multipart bodies document 400 and 422 `ValidationProblem` (`test_validation_vectors.py:88`); per-schema vector statuses documented (`:80`); ADR 0011 §6 rows for length, items, uniqueness, const, minProperties and escalation (`0011-api-contract-conventions.md:50-78`); `ValidationErrorCode` 19 codes (`fraudshield-api.yaml:2595`); `escalateAlert` 422 is `ValidationProblem`. V1–V3 caught. Remaining gaps: form-urlencoded wording (NEW-04), no vectors for the new codes (NEW-03) |
| NF-03 | MINOR | **VERIFIED_FIXED** | Matrix `rules` (`authorisation-matrix.yaml:81-118,191-197`) compared by `matrix_differences` (`authorisation.py:170-203`); `test_authorisation_matrix.py:150,170`. N2, N2b, N2c, N17, N19, N20 caught. Two-file removal of an FR-mandated rule still passes (NEW-06) |
| NF-04 | MINOR | **VERIFIED_FIXED** | `validation.py:18-112`; `test_request_vector` requires exact (field, code) equality (`test_validation_vectors.py:41`); N3b, N3c, V4, V5, K1, K3, K6–K8 caught. The mapping misreports nested `oneOf` object branches (NEW-01) and three keyword rows are untested (NEW-03) |
| NF-05 | MINOR | **VERIFIED_FIXED** | `LoginRequest.password` and `current_password` `maxLength: 1024`, no byte cap (`fraudshield-api.yaml:635,3448-3461`); ADR 0014 §7; asserted at `test_validation_vectors.py:179`; N4b, N4d caught |
| NF-06 | MINOR | **VERIFIED_FIXED** | Category-based rule in ADR 0014 §7 (`0014-staff-authorisation-model.md:84-93`) and `Password` (`fraudshield-api.yaml:2513`); pattern uses a literal space; 7 CHARACTERS vectors + `schema_detectable`; reference uses `unicodedata.category`; P1–P3, P5, P6 caught. Unicode-version dependence of `Cn` is NEW-07 |
| NF-07 | MINOR | **VERIFIED_FIXED** (ML) | `/actuator/health/ml` public on the management network (`fraudshield-api.yaml:231-260`); deviation in ADR 0014 §6 and notes on OPS-CI-07/OPS-OBS-05 (`requirements.yaml:2610,2701`); H1, H2 caught. The Kafka half of the staging gate is not covered and the ADR contradicts the contract (NEW-02) |
| NF-08 | MINOR | **VERIFIED_FIXED** | `StateConflict` `allOf [Problem, if stale-alert then StaleAlertProblem]` (`fraudshield-api.yaml:2277-2296`); orphan `StaleAlert` removed; `test_openapi.py:361`; S1–S3 caught |
| NF-09 | MINOR | **VERIFIED_FIXED** | `problem` `oneOf [ValidationProblem, IdempotencyConflictProblem]` (`fraudshield-api.yaml:2643,2884`); example added (counts consistent: 3 results, 2 failed); `test_openapi.py:377`; J1, J2 caught |
| NF-10 | MINOR | **VERIFIED_FIXED** | `register` description softened (`fraudshield-api.yaml:451-458`); ADR 0014 §8 records the accepted residual risk (`0014-staff-authorisation-model.md:101-104`) |
| NF-11 | MINOR | **VERIFIED_FIXED** | `test_openapi.py:338,353`: exact patterns with positive and negative samples, required = properties, UUID formats; N6c, N6d, N9 caught |
| NF-12 | NIT | **VERIFIED_FIXED** | `test_authorisation_matrix.py:164`; N13 caught |
| NF-13 | NIT | **VERIFIED_FIXED** | ADR 0011 §9 (`0011-api-contract-conventions.md:92-94`) and `DecisionResponse` description say server-enforced |
| NF-14 | NIT | **VERIFIED_FIXED** | `{scheme}://{instance}:{managementPort}` on both `/actuator` paths; `test_authorisation_matrix.py:178` |
| NF-15 | MINOR | **VERIFIED_FIXED** | `contracts-response.md` CR-28 row no longer cites `docs/walkthrough/M1.md` as existing |

Count: VERIFIED_FIXED 15 · PARTIALLY_FIXED 0 · NOT_FIXED 0 · ACCEPTED 0.

Also verified: the vacuous-`if` fix reported in the response. Every `if` now requires its property
(`test_openapi.py:387`); an ingest body without `channel` gives exactly one `required` error; I1–I3
caught.

## New findings

| # | Severity | Location | Finding | Required action |
|---|---|---|---|---|
| NEW-01 | MINOR | `contracts/src/fraudshield_contracts/validation.py:80-97`; `RuleExpression` (`fraudshield-api.yaml:3313`), used by `createRule`/`createRuleVersion` (`:1175,1230`) | `_branch_codes` reports the first `oneOf` branch whose JSON type matches. All four `RuleExpression` branches are objects, so the first (`all`) is always chosen. A rule whose only error is a bad leaf `op` becomes `expression.all: required` + `expression.field/op/value: unknown_field` (status 400), not `expression.op: unsupported_value` (422). This is the NF-01 symptom again, now in the mapping ADR 0011 §6 calls executable and that M6/M7 must reproduce. The "composed branches" classifier test covers only the nullable `device_fingerprint` case. No vector depends on it yet, so this is MINOR | Choose the branch by its discriminating `required` key (or the branch with the deepest or fewest errors, as `jsonschema.exceptions.best_match` does), and handle an over-matched `oneOf` (empty `context`). Add `RuleDraft` vectors: bad leaf `op`, bad nested `any`, extra key |
| NEW-02 | MINOR | ADR 0014 §6 (`0014-staff-authorisation-model.md:79`); `getLiveness` (`fraudshield-api.yaml:212-215`); `requirements.yaml:2610,2701`; SRS OPS-CI-07 gate "`/api/v1/health/kafka = UP`" | The ADR says the smoke test reads Kafka "through the aggregate" `/actuator/health`. The contract says ML **or Kafka** degradation does not change that status and is not disclosed. So the staging gate's Kafka check has no unauthenticated replacement, and the ADR contradicts the contract. The traceability notes repeat the ADR claim | Add a management-port `/actuator/health/kafka` (like `/actuator/health/ml`) and cite it, or record that the Kafka gate is dropped or replaced. Align the ADR, the `getLiveness` description and both notes |
| NEW-03 | MINOR | `request-validation-vectors.json` (45 cases, unchanged); `validation.py:20,33,36`; `test_validation_vectors.py:117-131` | None of the new codes (`length_out_of_range`, `item_count_out_of_range`, `duplicate_items`, `escalation_target_not_higher`) and no `minProperties` case is in the shared vectors that M6/M7 consume. Removing `minLength` or `minProperties` from `KEYWORD_CODES`, or remapping `maxItems` to `out_of_range`, passes (K2, K4, K5) | Add vectors, e.g. `StaffUserUpdate {}` → 400 `required`; an override reason under 20 characters → `length_out_of_range`; `ApiKeyCreate` `scopes: []` and duplicate scopes; a batch of 1,001 items; a server-only `escalation_target_not_higher` case |
| NEW-04 | NIT | ADR 0011 §6 (`0011-api-contract-conventions.md:68-69`) vs `submitVerification` (`fraudshield-api.yaml:2046`) | ADR: "Every operation with a request body documents both 400 and 422". The form-urlencoded HTML verification endpoint documents neither, and the test scopes itself to JSON/multipart | Qualify the ADR ("JSON or multipart body"), or document the HTML error page for an invalid form |
| NEW-05 | NIT | `requirements.yaml:2596-2612,2687-2703` | The response says the deviation is "recorded on OPS-CI-07 and OPS-OBS-05", but it is in free-text `notes`; the structured `deviations` field (validated against ADR paths by `evidence.py:147-150`) is still `[]` | Put `docs/adr/0014-staff-authorisation-model.md` in `deviations` now or at closure, or describe it as a note |
| NEW-06 | NIT | `authorisation-matrix.yaml:81-83,191-197`; `test_authorisation_matrix.py` | Object rules are pinned only by agreement between two files. Deleting the FR-07-01 `self-modification` rule from both passes (R3), and the `filter` entry does not pin what is filtered (N17b). Role rules of the same weight have invariants (`test_admin_never_decides_alerts`) | Add invariants: `updateUser` keeps `self-modification` 403 and `overrideAlertDecision` keeps `own-decision-override` 403 |
| NEW-07 | NIT | ADR 0014 §7 (`0014-staff-authorisation-model.md:84-86`); `password-vectors.json` | "No category C" includes `Cn` (unassigned), which depends on each engine's Unicode version (Python 3.12 `unicodedata` 15.0.0; Java and V8 differ by release). A character added in a newer Unicode version is accepted by one implementation and rejected by another | State the Unicode version, or exempt `Cn` and document that; optionally add a vector with a recently assigned code point |

New findings: BLOCKER 0 · MAJOR 0 · MINOR 3 · NIT 4 (7).

Other observations (not findings):
- The claims in 70f6283 and fb9093f were reproduced: 292 tests, 98.31 %, 8 composed schemas, 30 body
  operations, 7 CHARACTERS vectors plus a currency accept, 79 operations. The response line "classifier
  tests for composed branches" is accurate but narrow (see NEW-01).
- ADR 0014 §7 now has one unwrapped line of 145 characters (`0014-staff-authorisation-model.md:93`).
  This is cosmetic.
- 70f6283 amends ADR 0011 and 0014 together with the contract and discloses this in the commit message.
  The commit follows G.3 (type(scope), body, Refs, Tests), and the CI commit-message job passed.

## CI evidence (GitHub Actions public API, job-level conclusions)

| Commit | Workflow run | Jobs |
|---|---|---|
| fb9093f | `ci` 35196476935 (completed, success) | pre-commit hooks on all files; frontend (tsc, eslint, prettier, vitest); traceability-check, defect register, scope guard, commit messages; dependency licence inventory; gitleaks; python (ruff, mypy --strict, pytest); java (checkstyle, junit, spotbugs, jacoco): all **success** |
| fb9093f | `stack` 35196476916 (completed, success) | detect stack input changes: success; core compose stack healthy + smoke test: **skipped** |
| fb9093f | `devcontainer` 35196476908 (completed, success) | detect devcontainer input changes: success; build devcontainer + `make ci` + smoke: **skipped** |
| 70f6283 | none (`total_count: 0`) | No runs. The commit was pushed together with fb9093f, so only the branch head was built. fb9093f changes only `contracts-response.md` on top of 70f6283, so the fb9093f runs cover the 70f6283 tree |

The Docker-dependent jobs were skipped by their path filters. The contract changes do not touch stack
inputs, so this is acceptable.

## Merge decision

**`m1/contracts-openapi` may be fast-forward merged to `main`** (`main` 1b99782 is an ancestor of
fb9093f). No BLOCKER or MAJOR finding is open for this branch: NF-01 and NF-02 are verified fixed, and
this check found no new BLOCKER or MAJOR. Before M6/M7 start using the vectors and the mapping, track
or fix the 3 MINOR findings (NEW-01 … NEW-03) as issues, as APPROVED_WITH_MINORS requires, and the 4 NITs
as well. The events-branch findings stay OPEN and are not affected by this decision.
