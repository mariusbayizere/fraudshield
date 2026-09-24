# Re-review: M1 contracts, `m1/contracts-openapi` @ c2ec944

Reviewer role: Principal Reviewer (independent, build prompt Part I.2)
Date: 2026-09-17 (UTC)
Branch/commit: `m1/contracts-openapi` @ c2ec944ff0619ae49977e9fa06d47f48428b8829 (base `main` 1b99782). Fix
commits reviewed: d2eaccd (ADR 0014, ADR 0011 amendment), add5d86 (contract, matrix, examples, vectors,
tests), c2ec944 (response). Earlier branch commits b60145d, bd497ec and 5419096 were checked for the owner
constraints only.
Inputs: `docs/reviews/M1/contracts-review.md` (8f29a5e), `docs/reviews/M1/contracts-response.md` (c2ec944).
Scope: openapi-owned findings CR-01, CR-03 … CR-10, CR-19, CR-20, CR-22 … CR-25, CR-28, CR-30 … CR-32, plus
the API-side parts of CR-02, CR-18, CR-21, CR-26, CR-27, CR-29 and CR-33. Events-branch findings are out of
scope and remain legitimately OPEN.
Verdict: **CHANGES_REQUIRED**

Summary: 23 of the 26 in-scope rows are verified as fixed or accepted. CR-08, and the API sides of CR-26 and
CR-33, are only partly fixed. The fixes introduced **2 MAJOR, 10 MINOR and 3 NIT** new findings. The
original survivors M1, M1b and M2c are now caught. 23 of my 38 mutations were caught (one survivor is an
equivalent mutant). Every test count and total the author gave was reproduced; two claims in the response
overstate the evidence (NF-10, NF-15). The two
MAJOR findings share a cause. The 400/422 rule from CR-08 is written down, but in most operations the
contract does not follow it, and one result of the CR-01 fix contradicts the contract's own vectors. No
test can see either problem, because the vector test only checks whether an error exists, not which
error.

Environment: isolated worktree `<scratchpad>/fs-rereview-openapi` at c2ec944 (removed after review);
`export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`; Docker not used (ADR 0010). `make ci` was not run in
full; the job-level CI results are quoted below.

## Checks re-run (command → result)

| Command (worktree at c2ec944) | Result |
|---|---|
| `uv sync --all-packages --locked` | installed, lockfile unchanged |
| `cd contracts && uv run pytest -q` | **262 passed, coverage 96.95 %** (claim in add5d86: 262 tests, 96.95 %) ✓ |
| `uv run ruff check contracts` / `ruff format --check contracts` | All checks passed / 8 files already formatted |
| `uv run mypy contracts/src contracts/tests` | Success: no issues found in 7 source files |
| `uv run fs-traceability check` | 258 rows, 84 tagged tests, 0 errors, 0 warnings |
| `uv run fs-defect-register --check`; `uv run fs-scope-guard` | up to date (51 defects); 0 files |
| `tools/bin/gitleaks git --log-opts=HEAD …` / `gitleaks dir contracts docs` | no leaks found / no leaks found |
| Operations counted from `paths` at c2ec944 and at 70e083b; rows in `authorisation-matrix.yaml` | 78 and 77; 78 rows (claims 78 and 77) ✓ |
| `ProblemType` enum, `ValidationErrorCode` enum, request vectors (ingest) | 26, 16, 45 (26) ✓ |
| Password vectors (accept/reject) with byte lengths | 7/9; boundaries at 72 (ASCII and 2-byte é) accepted, 73 (emoji) and 74 rejected ✓ |
| Committed examples for `AlertDetail`, `Rule`, `IpAllowlistEntry`, `ApiKeyCreated` validated against the **70e083b** contract | 1 error each (all four rejected), as the author claimed ✓; the same examples pass at c2ec944 |
| `OBJECT_SCHEMAS` in `test_openapi_examples.py` | 62 object schemas used by operations (60 with examples in `schema-examples.yaml`, 2 covered by operation media examples; the response says "60 … plus media examples") ✓ |
| `grep -rn 250788 contracts`; phone-shaped values in vectors | empty; `+999000000001` (unassigned E.164 country code) ✓ |
| `format: date-time` anywhere in `fraudshield-api.yaml` (schemas **and** paths) | only `Timestamp` (line 2418) ✓ |
| Schema keyword and path behind each schema-detectable vector (`iter_errors` on each body) | codes are consistent with the keywords, **but** `IpAllowlistEntryCreate: no prefix` also produces a root `unevaluatedProperties` error (NF-01) |
| For each operation with a request body, is a 422 `ValidationProblem` documented? | **only 8 of 31** (NF-02) |
| Whitespace class used by `Password`: Python `re`, Node `/u` and the reference implementation for U+00A0, U+FEFF, U+2028, U+001C, U+200B, U+0000 | the engines disagree on U+FEFF and U+001C; Java's default `\s` differs again (NF-06) |
| Commit authors and trailers for 1b99782..c2ec944 | 8 commits, all `Marius Bayizere <bayizeremarius119@gmail.com>`; no Co-Authored-By, Signed-off-by or tool signature ✓ |
| PII scan of added lines | only `example.com` addresses (RFC 2606), `203.0.113.0/24` (RFC 5737), synthetic names that are also person-name vectors, `+999…` numbers; no internal hostnames ✓. `0788…` numbers appear only as quotations in the review record |
| History | c2ec944 = `origin/m1/contracts-openapi`; 70e083b is unchanged and still the parent chain; nothing was rewritten ✓ |
| Worktree `git status --porcelain` after all mutations | clean |

## Mutation spot checks

Each mutation was applied in the worktree by loading and re-dumping the YAML or JSON file. A control
re-dump with no change passed 262 tests, so re-dumping alone changes nothing. Each run used
`cd contracts && uv run pytest -q --no-cov`, followed by `git checkout -- .`.

| # | Mutation | Result |
|---|---|---|
| M1 | ADMIN added to `decideAlert` roles | **caught** (7 failed: matrix equality + widened-role self-tests + `test_admin_never_decides_alerts`) |
| M1b | `overrideAlertDecision` and `createApiKey` → `[ANALYST, ADMIN]` | **caught** (10 failed) |
| M1c | M1 applied to **both** the contract and the matrix | **caught** by the `test_admin_never_decides_alerts` invariant |
| M2c | `anomaly_raw`, `shap_all` added to closed `DecisionResponse` | **caught** (`test_ingest_response_exposes_no_model_internals`) |
| N1 | `AlertSummaryBase.additionalProperties: false` restored | **caught** (2 failed) |
| N1b | `ApiKeyBase.additionalProperties: false` restored | **caught** (2 failed) |
| N8 | `unevaluatedProperties` removed from `AlertDetail` | **caught** (`test_unknown_property_is_rejected`) |
| N2 | `self-modification` rule removed from `updateUser` `x-authorisation-rules` | **survived** (NF-03) |
| N2b | `own-decision-override` rule removed from `overrideAlertDecision` | **survived** (NF-03) |
| N2c | `escalation-target-not-higher` status 422 → 403 | **survived** (NF-03) |
| N3 | vector "amount as a JSON number": only `expected_status` 422 → 400 | **caught** |
| N3b | same vector: status 400 **and** code `required` | **survived** (NF-04) |
| N3c | "raw MSISDN as account_id" → 422 `invalid_format` (reverses E.1's "raw MSISDN → 400") | **survived** (NF-04) |
| N4 | `Password.maxLength` 72 → 128 | **caught** |
| N4b | `LoginRequest.password.maxLength` 72 → 128 | **survived** (NF-05) |
| N4c | password special class narrowed back to `[!@#$%^&*]` | **caught** |
| N5 | `/health/ml` made public (roles removed, `security: []`, `x-public`) | **caught** (8 failed) |
| N5b | N5 applied to both the contract and the matrix | **caught** (`test_health_detail_is_not_public`) |
| N5c | `/health/ml` keeps ADMIN but gains `security: []` | **caught** (one-class test) |
| N6 | `FinalDecision` sequence `if/then/else` removed | **caught** (4 failed) |
| N6b | `decision_sequence.minimum` removed | **caught** |
| N6c | `supersedes_decision` removed from `required` | **survived** (NF-11) |
| N6d | `event_id` `format: uuid` removed | **survived** (NF-11) |
| N7 | `DecisionResponse`: `else` branch (non-HOLD ⇒ deadline null) removed | **caught** |
| N7b | `DecisionResponse`: HIGH ⇒ DECLINE rule removed | survived; **equivalent mutant**: `Decision` is `[APPROVE, DECLINE, HOLD]`, so APPROVE ⇒ LOW and HOLD ⇒ MEDIUM already imply it |
| N9 | `webhook_signing_secret.pattern` widened to `^whsec_.*` | **survived** (NF-11) |
| N10 | ingest 409 → generic `Conflict` | **caught** |
| N11 | `x-status-by-code.not_a_token` 400 → 422 | **caught** (2 failed) |
| N12 | SENIOR_ANALYST added to `listCampaigns` in both the contract and the matrix | survived. This is by design: ADR 0014 accepts a change made in two files. It is a reviewer control, not a test (see NF-12) |
| N13 | RISK_OFFICER added to `escalateAlert` in both the contract and the matrix (no role is above RISK_OFFICER) | **survived** (NF-12) |
| N14 | `ProbeStatus` gains `detail` | **caught** |
| N15 | `ValidationProblem.status` restriction removed | **caught** |
| N16 | all BYTES password rejection vectors deleted | **caught** |
| N17 | `queue=escalated` visibility sentence removed from `listAlerts` | **survived** (NF-03) |
| N18 | `decideAlert` matrix `ref` emptied | **caught** |
| N19 | `register` 409 restored (account enumeration, CR-24) | **survived** (NF-03) |
| N20 | `login` 403 `account-not-active` removed (CR-24) | **survived** (NF-03) |
| N21 | `ApiKeyBase.last_used_at` back to bare `format: date-time` | **caught** (`test_timestamps_are_utc`) |

Score: 23 of the 38 mutations were caught, including all three original survivors in scope (M1, M1b,
M2c). One survivor is equivalent (N7b) and one survives by design (N12). The other 13 survivors are
object-level rules, CR-24 responses, vector classification and secondary schema constraints.

## In-scope original findings

| # | Sev | Status | Evidence |
|---|---|---|---|
| CR-01 | BLOCKER | **VERIFIED_FIXED** | `api.yaml:2879-2890,3247-3255,3487-3495` and `ApiKeyBase`: bases are open, and the composing schemas close with `unevaluatedProperties`. 62 object schemas have valid examples; the four old schemas reject those examples at 70e083b. N1, N1b and N8 are caught. A side effect is NF-01 |
| CR-02 (API) | BLOCKER | **VERIFIED_FIXED** (API side) | `api.yaml:2692-2780`: `event_id`, `decision_sequence ≥ 1`, `supersedes_decision`, `reason_codes`, the HOLD⇔not-final⇔deadline rule, sequence-1 = MODEL with no predecessor, and later states are never MODEL or HOLD. ADR 0011 §9 gives the receiver rule. N6 and N6b are caught; N6c and N6d survive (NF-11). The events side (Kafka schema, webhook re-signing, vectors) is OPEN, out of scope |
| CR-03 | MAJOR | **VERIFIED_FIXED** | `authorisation-matrix.yaml` has 78 rows, each with a ref. `matrix_differences` checks both directions (`authorisation.py:50-72`). Invariants are at `test_authorisation_matrix.py:217-248`, and the API-key check is based on `x-required-scopes` (`:248`). M1, M1b and M1c are caught |
| CR-04 | MAJOR | **VERIFIED_FIXED** (pending owner confirmation) | ADR 0014 §3; `updateThresholds` and `previewThresholdImpact` are `[RISK_OFFICER]`; `test_only_risk_officers_override_and_change_thresholds`. The dual-control alternative is flagged for the owner |
| CR-05 | MAJOR | **VERIFIED_FIXED** (contract level) | ADR 0014 §1 (one role) and §5; `api.yaml:1454-1463` declares `self-modification` 403 and `last-active-admin` 409. Deleting the rule is not detected (N2, NF-03) |
| CR-06 | MAJOR | **VERIFIED_FIXED** (contract level) | `x-authorisation-rules` at `api.yaml:751,788,815,853`; `queue=escalated` rule in `listAlerts`; problem types are catalogued. Deleting a rule or changing its status is not detected (N2b, N2c, N17, NF-03). The `StateConflict` schema weakens D-29 (NF-08) |
| CR-07 | MAJOR | **VERIFIED_FIXED** | Fingerprint, 409 `IdempotencyConflict`, in-flight wait and audit (`api.yaml:52-73`); N10 is caught. The batch REJECTED item cannot carry the problem it promises (NF-09) |
| CR-08 | MAJOR | **PARTIALLY_FIXED** | Done: the ADR 0011 §6 table, 16 codes with `x-status-by-code`, and 45 vectors with status and codes. Still open: 23 of 31 body operations do not document 422, including those whose vectors expect 422 (NF-02). The schema's own output contradicts a vector (NF-01). Vector codes are not tied to schema keywords (NF-04). The table has no rows for `minLength`/`maxLength`, `minItems`/`uniqueItems` or `const` (NF-02) |
| CR-09 | MAJOR | **VERIFIED_FIXED** | `Password` is 8–72 characters with `x-max-utf8-bytes: 72` (`api.yaml:2449-2467`); ADR 0014 §7; vectors cover the byte boundaries; N4, N4c and N16 are caught. The login schema contradicts the ADR (NF-05). The meaning of "whitespace" differs between engines (NF-06) |
| CR-10 | MAJOR | **VERIFIED_FIXED** | `getMlHealth` and `getKafkaHealth` require `[ADMIN]` (`api.yaml:229-265`); the status-only `ProbeStatus`; N5, N5b, N5c and N14 are caught. The conflict with the SRS monitoring and smoke-test rows is not recorded (NF-07) |
| CR-18 (API) | MAJOR | **VERIFIED_FIXED** (API side) | `test_openapi.py:137-156` asserts the exact property set; M2c is caught. The events side is OPEN |
| CR-19 | MAJOR | **VERIFIED_FIXED** | `StaffReference` first and last names use `PersonName` (`test_openapi.py:311-322`); PersonName is 2–100 with no pattern; the vectors contain "Jean Bosco", "Marie Claire Uwase" and NFD `Ọlá` → NFC `Ọlá`. The Java and TS suites were not re-run locally; the CI `java` and `frontend` jobs pass on add5d86 and c2ec944 |
| CR-20 | MINOR | **VERIFIED_FIXED** | ADR 0011 §9 records `ml_unavailable_fallback`; the `if/then/else` rules are at `api.yaml:2688-2718`; there are 7 test cases; N7 is caught. The ADR says DECLINE at a lower tier happens "only for a frozen account", but the schema allows any DECLINE at LOW. That is acceptable as a server rule; see NF-13 |
| CR-21 (API) | MINOR | **VERIFIED_FIXED** (API side) | One `FinalDecision` shape; `decided_at` is defined for HOLD. The events-side identity test is OPEN |
| CR-22 | MINOR | **VERIFIED_FIXED** | `ProblemType` (26) with `x-problem-types` on every problem response; `ValidationProblem` has the `validation` type const and status 400/422; N15 is caught. `StaleAlert` is now unreferenced (NF-13) |
| CR-23 | MINOR | **VERIFIED_FIXED** | `/actuator/health` with a path-level `servers` (`api.yaml:200-225`). The management port is not expressed (NF-14) |
| CR-24 | MINOR | **VERIFIED_FIXED** (contract level) | Login 403 `AccountNotActive`, neutral 202 register with 409 removed, `sid` logout (`api.yaml:272-437`), ADR 0014 §8. N19 and N20 survive (NF-03). The public availability oracle remains (NF-10) |
| CR-25 | MINOR | **VERIFIED_FIXED** | `updateThresholds` description and 422; `thresholds_not_ordered` and `duplicate_channel` server-only vectors (the schema accepts them) |
| CR-26 (API) | MINOR | **PARTIALLY_FIXED** | The SSRF rules are documented on `webhook_url` with 4 server-only vectors, but `createApiKey` (`api.yaml:1615`) does not document the 422 those vectors expect (NF-02). Events side OPEN |
| CR-27 (API) | MINOR | **VERIFIED_FIXED** | No `250788` under `contracts/`; vectors use `+999…` and `0000000001`. Events fixtures OPEN |
| CR-28 | MINOR | **ACCEPTED_DEVIATION** | Splitting would rewrite published history (70e083b is on `origin`), so disclosure is the right remedy. The disclosure in `contracts-response.md:33` names the 5 exceptions. Its second pointer, `docs/walkthrough/M1.md`, does not exist on any branch (NF-15) |
| CR-29 (API) | MINOR | **VERIFIED_FIXED** (API side) | Route-presence tests are untagged (`test_openapi.py:282`); FR-07-01 → matrix tests; `fs-traceability check` 0 errors. Events D-15 tag OPEN |
| CR-30 | MINOR | **VERIFIED_FIXED** | `listCampaigns` → `[RISK_OFFICER]`, `listCircuitBreakers` → `[ADMIN]`; `getModelPerformance` ADMIN justified by FR-06-03 in the matrix |
| CR-31 | NIT | **VERIFIED_FIXED** | ADR 0011 §6 records the unregistered namespace |
| CR-32 | NIT | **VERIFIED_FIXED** | Only `Timestamp` declares `date-time`, in schemas and paths; N21 is caught |
| CR-33 (API) | NIT | **PARTIALLY_FIXED** | CIDR `maxLength` plus the server parse rule and vectors (`api.yaml:3491-3503`), but `addOfficeIpRange` (`api.yaml:1558`) does not document 422 (NF-02). Proto items OPEN (events) |

Count (in scope, 26 rows): VERIFIED_FIXED 22 · PARTIALLY_FIXED 3 (CR-08, CR-26 API, CR-33 API) ·
NOT_FIXED 0 · ACCEPTED_DEVIATION 1 (CR-28).

## New findings

| # | Severity | Location | Finding | Required action |
|---|---|---|---|---|
| NF-01 | **MAJOR** | `api.yaml:3247-3255` (`RuleDraft`), `3487-3495` (`IpAllowlistEntryCreate`); `request-validation-vectors.json` "no prefix" case; `test_validation_vectors.py:171-173` | CR-01 turned two **request** schemas that had been flat into `allOf` + `unevaluatedProperties`. In JSON Schema 2020-12, a failing subschema drops its annotations. As a result, one bad value makes the root report every valid property as unevaluated. Reproduced: `IpAllowlistEntryCreate` `cidr: "10.0.0.0"` → `pattern` **and** `unevaluatedProperties ('cidr', 'description' were unexpected)`; `RuleDraft` with a bad `rule_name` → all four fields "unexpected". Under ADR 0011 §6 ("field not in the schema → 400 `unknown_field`; any 400-class error makes the status 400"), a schema-driven validator returns **400** for the vector that expects **422 `invalid_format`**. The same applies to `createRule` and `createRuleVersion`. The test cannot see this because it asserts only `bool(schema_errors)`. The same noise affects the response schemas used in conformance tests (`ApiKey`, `AlertSummary`) | Keep request bodies flat and closed with `additionalProperties: false`. Copy the base properties, or keep the open base only for response composition. Alternatively, state in ADR 0011 §6 that `unevaluatedProperties` errors are reported only when no other error exists, and make the vector test apply that rule. Extend `test_request_vector` to compare the set of (field, code) implied by the schema errors with `expected_errors`, so this case fails |
| NF-02 | **MAJOR** | 23 operations with request bodies, e.g. `addOfficeIpRange` `api.yaml:1558`, `createApiKey` `:1615`, `createUser`, `changePassword` `:573`, `overrideAlertDecision` `:850`, `login` `:272`; `escalateAlert` 422 slot `:812`; ADR 0011 §6 table (`docs/adr/0011-api-contract-conventions.md:46-62`) | ADR 0011 §6 defines type, format, enum, range, password and person-name errors as **422**. Only 8 of the 31 operations with a request body document a 422 `ValidationProblem`. Contradictions: (1) the `invalid_cidr`, `webhook_url_not_allowed`, `password_policy` and `person_name` codes are 422 by the contract's own `x-status-by-code`, and the committed vectors expect 422 for `addOfficeIpRange` and `createApiKey`, but those operations document only 400. (2) The 422 of `escalateAlert` is `AuthorisationRuleViolation` alone, so an invalid `target_role` (422 `unsupported_value`) has no documented response. (3) The mapping table has no row for `minLength`/`maxLength` (for example an override reason under 20 characters, a description over 1,000), `minItems`/`maxItems`, `uniqueItems` or `const`, so their status and code are undefined. The CR-08 rule therefore holds for ingest and thresholds, not across the API that M6 and M7 controller tests will be written against | Add 422 `ValidationProblem` to every operation with a request body. Where a 422 also carries rule violations, use a response whose `x-problem-types` includes both. Add table rows and codes (or map explicitly to existing codes) for string length, item count, uniqueness and const. Add tests: every operation with a request body documents 400 and 422 with the `validation` type; for each vector schema, each operation using that schema documents the vector's `expected_status` |
| NF-03 | MINOR | `test_authorisation_matrix.py:251-263`; `api.yaml:751,788,815,853,1454`, `listAlerts` description, `register`, `login` | Object-level rules, `queue=escalated` visibility and the CR-24 responses have no golden list. The rule test only requires that *some* rules exist and that each is catalogued. Deleting `self-modification` (FR-07-01 "cannot self-elevate") or `own-decision-override`, changing the status of `escalation-target-not-higher`, removing the escalated-queue rule, restoring `register` 409 or removing `login` 403 all passed (N2, N2b, N2c, N17, N19, N20). M7 test obligations can disappear without anyone noticing | Add the expected `{operationId: [(problem, status)]}` to `authorisation-matrix.yaml` (or a sibling file) and assert equality, like the role matrix. Assert that `register` has no 409 and 202 is its only 2xx, and that `login` 403 → `AccountNotActive` |
| NF-04 | MINOR | `test_validation_vectors.py:156-175` | Vector classification is not tied to the schema. A consistent code-and-status change passes: "amount as a JSON number" → 400 `required` (N3b), and "raw MSISDN as account_id" → 422 `invalid_format`, which reverses E.1's "raw MSISDNs → 400" (N3c). `field` is never compared with the error path. These vectors are the authority M6 and M7 consume | For schema-detectable cases, derive (field path, keyword) from `iter_errors` (leaf errors) and map them with a committed keyword/field → code table (for example `pattern` on a `tok_` field → `not_a_token`; `pattern` on `PositiveAmount` → `invalid_format` or `out_of_range` by an explicit rule). Assert equality with `expected_errors` |
| NF-05 | MINOR | `api.yaml:3353-3364` (`LoginRequest.password` `maxLength: 72`, description "rejected as invalid credentials (401)"); `login` responses 400 only; ADR 0014 §7 (`0014-staff-authorisation-model.md:78`) | The ADR and the description say that sign-in input over 72 bytes returns 401 without calling bcrypt. The schema instead makes a 73-character password a validation failure (400/422), which also tells the caller about the policy. N4b (`maxLength` 128) survived because only `x-max-utf8-bytes` is asserted | Choose one behaviour. Recommended: drop `maxLength` on the sign-in field (keep a generous transport cap such as 1,024) and keep "over 72 bytes → 401". Assert the chosen value in `test_password_schema_limits_match_the_vectors`. Apply the same to `changePassword.current_password` |
| NF-06 | MINOR | `api.yaml:2467` (`[^A-Za-z0-9\\s]`); ADR 0014 §7; `test_validation_vectors.py:209`; `password-vectors.json` | "Not whitespace" is not defined, and the engines differ. Python `re` and `str.isspace` count U+FEFF as special but U+001C as whitespace; JavaScript `\s` does the opposite; Java's default `\s` is ASCII-only, so U+00A0 and U+2028 count as special there. Invisible characters such as U+200B, U+FEFF and NUL satisfy the "special" class. Zod, Bean Validation and the schema will disagree, and the vectors do not cover it | Define the special class explicitly in ADR 0014, for example "a code point of Unicode category P, S or L/M outside ASCII; controls (Cc), format characters (Cf) and White_Space are rejected or do not count". Add vectors for U+00A0, U+2028, U+FEFF, U+200B, U+001C and U+0000 |
| NF-07 | MINOR | ADR 0014 §6; SRS `FraudShield_SRS_v1_0.md:369` (staging smoke "/api/v1/health/ml = UP") and `:380` (blackbox probe of `/api/v1/health/ml` every 60 s, PagerDuty P1) | Making `/health/ml` ADMIN-only conflicts with two SRS operational requirements. An unauthenticated blackbox exporter and a smoke test cannot present a 15-minute staff JWT, and a monitoring robot holding an ADMIN account contradicts the one-person, one-role model. ADR 0014 records only the orchestration probe | Record the deviation in ADR 0014 and the traceability deviations. Define how monitoring reads ML health, for example a management-port `/actuator/health/ml` component reachable only from the cluster network, or a dedicated scope. Add a contract entry for it |
| NF-08 | MINOR | `api.yaml:2217-2238` (`StateConflict` `anyOf: [StaleAlertProblem, Problem]`); `StaleAlert` `:2146` now unreferenced | At 70e083b a stale `decideAlert`, `escalateAlert` or `overrideAlertDecision` had to return `StaleAlertProblem` with `current` (D-29). `anyOf` with plain `Problem` lets a `stale-alert` problem without `current` validate, so the requirement is now prose only | Use `if: {properties: {type: {const: …stale-alert}}} then: {$ref: StaleAlertProblem}`, or split the 409 by type. Add a test that a `stale-alert` body without `current` is rejected. Remove or use the orphaned `StaleAlert` response |
| NF-09 | MINOR | `api.yaml:118-125` (batch description) vs `JobStatus.results[].problem` → `ValidationProblem` (`:2801`) | Batch items rejected for an idempotency conflict are documented as carrying an `idempotency-conflict` problem. `ValidationProblem` now requires the `validation` type and status 400/422, so such an item cannot be represented (reproduced: 2 errors) | Make `problem` `anyOf` `ValidationProblem` / `Problem` (constrained to `idempotency-conflict`, 409). Add an example to `schema-examples.yaml` |
| NF-10 | MINOR | `register` description `api.yaml:413-419`; `checkAvailability` `:439-469` | The response and ADR 0014 §8 say registration "cannot be used to discover accounts". The public `/auth/availability` still returns `{available: bool}` for an email or employee ID. E.8 allows it with rate limiting and neutral timing, but the enumeration risk is unchanged and the claim overstates the protection | State in ADR 0014 §8 that availability remains an accepted, rate-limited enumeration channel (E.8), or limit it (for example CAPTCHA, or employee ID only). Soften the `register` description |
| NF-11 | MINOR | `api.yaml:2692-2780` (`FinalDecision`), `3574-3580` (`webhook_signing_secret`); `test_openapi.py:325-329` | Secondary constraints are untested. N6c (`supersedes_decision` optional), N6d (`event_id` not a UUID, which is the integrator dedup key) and N9 (`whsec_` pattern widened to `.*`, which undoes the scanner format) all survived. The prefix test only checks `startswith("^whsec_")` | Assert that the `FinalDecision` required set equals the documented set and that `event_id` is a UUID. Assert the exact `raw_key` and `webhook_signing_secret` patterns, with one matching and one non-matching sample each |
| NF-12 | NIT | `authorisation-matrix.yaml:90-92`; `test_authorisation_matrix.py` | The `escalateAlert` matrix row says "RISK_OFFICER cannot escalate". Adding RISK_OFFICER in both files survived (N13), although "target strictly above the caller" makes that role list impossible | Add the invariant `escalateAlert` roles ⊆ {ANALYST, SENIOR_ANALYST} |
| NF-13 | NIT | ADR 0011 §9 (`0011-api-contract-conventions.md`, "DECLINE with a lower tier is allowed only for a frozen account"); `api.yaml:2711-2718` | The schema accepts DECLINE at LOW or MEDIUM with any reason codes. "Only for a frozen account" is a server rule and is not marked as one | Say "server-enforced" in the ADR and the schema description, or add `if decision=DECLINE and tier≠HIGH then reason_codes contains ACCOUNT_FROZEN` |
| NF-14 | NIT | `api.yaml:200-203` (`servers: - url: /`) | A relative `/` server resolves to the same origin and port as the document. It does not express "management port, not routed through the public ingress", so generated clients and docs will call the public port | Use a server variable (for example `{scheme}://{host}:{managementPort}/`) or state that the entry is informational |
| NF-15 | MINOR | `docs/reviews/M1/contracts-response.md:33` (CR-28) | The response says the licence exceptions are named "here and in the M1 walkthrough" and cites `docs/walkthrough/M1.md` as verification. That file does not exist at c2ec944 or on any local or remote ref (`docs/walkthrough/` has only `M0.md`, `defence_questions.md`, `demo_script.md`). The disclosure in the response row itself is sufficient | Correct the response (remove the pointer, or commit the walkthrough) |

New findings: BLOCKER 0 · MAJOR 2 · MINOR 10 · NIT 3 (15).

Other observations (not findings):
- bd497ec says "CI and Makefile" but contains only the Makefile. 5419096 completes it and says so, and
  the response discloses this.
- add5d86 also regenerates `docs/traceability/requirements_matrix.md` and edits `contracts/README.md`.
  Both follow directly from the new tests and files, so they are not unrelated changes.
- `FR-01-06` lost its only (route-presence) tagged test. This is correct under CR-29.
- The claims in commit messages and the response were reproduced (262 tests, 96.95 %, 78/77 operations,
  26 problem types, 16 codes, 45/26 vectors, 7/9 password vectors, 11 FinalDecision cases, 7 tier cases),
  except the two overstated claims in NF-10 and NF-15.

## CI evidence (GitHub Actions public API, job-level conclusions)

| Commit | Workflow run | Jobs |
|---|---|---|
| c2ec944 | `ci` 35193230736 (success) | gitleaks; java (checkstyle, junit, spotbugs, jacoco); pre-commit hooks; traceability/defect register/scope guard/commit messages; python (ruff, mypy --strict, pytest); licence inventory; frontend (tsc, eslint, prettier, vitest): all **success** |
| c2ec944 | `stack` 35193230721 (success) | detect stack input changes: success; core compose stack + smoke: **skipped** (no stack inputs changed) |
| c2ec944 | `devcontainer` 35193230806 (success) | detect inputs: success; build + post-create `make ci` + smoke: **skipped** |
| add5d86 | `ci` 35193117461 (success) | same 7 jobs, all **success** |
| add5d86 | `stack` 35193117415 (success) | detect: success; core stack + smoke: **skipped** |
| add5d86 | `devcontainer` 35193117410 (success) | detect: success; build + `make ci` + smoke: **skipped** |

All runs were completed. The Docker-dependent jobs were skipped by their path filters, so they give no
evidence for these commits. The contract changes do not affect the stack, so this is acceptable.

## Merge decision

**`m1/contracts-openapi` may NOT be fast-forward merged to `main` yet.** No BLOCKER remains, but two
MAJOR findings are open: NF-01 and NF-02. Because of them, CR-08, and the API sides of CR-26 and CR-33,
are only partly fixed. Both fixes are small and mechanical: flat closed request schemas, 422 responses on
the operations with a request body, missing mapping rows, and a (field, code) comparison in the vector
test. After those fixes and a focused re-check, the branch can merge, with the MINOR and NIT findings
tracked or fixed. The events-branch findings stay OPEN and do not block this branch.
