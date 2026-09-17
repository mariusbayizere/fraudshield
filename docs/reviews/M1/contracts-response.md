# Author response to the M1 contracts review

Review: `contracts-review.md` (commit 8f29a5e; verdict CHANGES_REQUIRED, 2 BLOCKER / 17 MAJOR /
11 MINOR / 4 NIT). Fixes are new commits. The merge order set by the repository owner is:
`m1/contracts-openapi` first, then `m1/contracts-events` rebased onto `main`. This file therefore
records the openapi-branch findings first. Findings owned by the events branch stay **OPEN** here
until that branch is fixed.

## m1/contracts-openapi

| # | Sev | Resolution | Commit | Verification |
|---|---|---|---|---|
| CR-01 | BLOCKER | Open bases (`AlertSummaryBase`, `RuleDraftBase`, `IpAllowlistEntryBase`, `ApiKeyBase`); `AlertSummary`, `AlertDetail`, `RuleDraft`, `Rule`, `IpAllowlistEntryCreate`, `IpAllowlistEntry`, `ApiKey` and `ApiKeyCreated` close with `unevaluatedProperties: false`. `schema-examples.yaml` holds a valid example for each of the 60 object schemas used by an operation (plus the operation media examples) | add5d86 | `test_openapi_examples.py`: every example validates; each fails when an unknown property is added (except the four RFC 9457 problem schemas, open by design); no closed schema sits inside `allOf` with siblings; `test_the_check_rejects_the_unsatisfiable_composition_it_replaced`. The same examples run against the 70e083b contract fail for all four schemas (author re-ran this) |
| CR-02 | BLOCKER | **API side done**: `FinalDecision` has `event_id` (the dedup key, constant across retries), `decision_sequence` (1 at ingest, +1 per change), `reason_codes` and `supersedes_decision`. Allowed transitions are in the description, and the structural ones are enforced (HOLD ⇔ not final with a deadline; sequence 1 decided by MODEL with no predecessor; later states name a predecessor, are never decided by MODEL and never HOLD). ADR 0011 §9 holds the receiver rule. **Events side open**: Kafka schema, webhook spec (re-signing per attempt, ordering) and vectors | add5d86, d2eaccd | `test_final_decision_states_are_ordered_and_consistent` (11 cases) |
| CR-03 | MAJOR | `authorisation-matrix.yaml` gives the access for each of the 78 operations (77 at 70e083b, counted from `paths`, although the owner's finding said 72; plus `/actuator/health`), each row with an FR, E-section or ADR reference. `matrix_differences` compares it with the document in both directions | add5d86 | `test_document_matches_the_reviewed_matrix_exactly`. Mutations M1/M1b and ADMIN on `updateThresholds` are detected (`test_matrix_check_detects_a_widened_role_list`). There are invariant tests for ADMIN, RISK_OFFICER, lifecycle and API-key reach. The API-key check is now derived from `x-required-scopes`, not a path list |
| CR-04 | MAJOR | RISK_OFFICER only for `updateThresholds` and `previewThresholdImpact`; ADMIN keeps read access. Decided in ADR 0014 §3, with dual control recorded as the alternative **for the owner to confirm** | d2eaccd, add5d86 | `test_only_risk_officers_override_and_change_thresholds` |
| CR-05 | MAJOR | One role per account (ADR 0014 §1). `updateUser` declares `self-modification` (403) and `last-active-admin` (409) | d2eaccd, add5d86 | `test_object_level_rules_are_declared_with_documented_problems[updateUser]`; runtime behaviour is an M7 test obligation |
| CR-06 | MAJOR | `x-authorisation-rules` on `decideAlert`, `undoAlertDecision`, `escalateAlert` and `overrideAlertDecision`: escalation level, strictly higher escalation target, undo by author within the window, override only of COMMITTED decisions and never your own. `queue=escalated` visibility is documented. New problem responses `AuthorisationRuleViolation` and `StateConflict` | add5d86 | The rule test checks each problem is catalogued, documented under the rule's status and listed in that response's `x-problem-types`; runtime behaviour is an M7 obligation (ADR 0014 §5) |
| CR-07 | MAJOR | Canonical request fingerprint; mismatch → 409 `idempotency-conflict`, not scored, audited; in-flight duplicates wait for the first result; batch items report REJECTED | add5d86 | `test_ingest_documents_idempotency_conflicts`; behaviour is an M6 obligation |
| CR-08 | MAJOR | ADR 0011 §6 mapping table. `ValidationErrorCode` has 16 codes with `x-status-by-code`, and `errors[].code` references it. `request-validation-vectors.json` has 45 cases (26 ingest) with expected status and codes | d2eaccd, add5d86 | `test_request_vector` checks the codes are catalogued, the status follows the code classes, schema-detectable cases fail the schema, and server-only cases pass it; `test_ingest_vectors_cover_both_statuses_and_every_ingest_code` |
| CR-09 | MAJOR | `Password` is 8–72 characters plus `x-max-utf8-bytes: 72`. The special-character class is "neither ASCII alphanumeric nor whitespace" (ADR 0014 §7). Sign-in and current-password fields are capped the same way | add5d86 | `test_password_*`: 7 accepted and 9 rejected vectors, including 72/73/74-byte boundaries with multi-byte characters, against a reference implementation. The first vector file had a 72-byte case mislabelled as 75; the test caught it before commit |
| CR-10 | MAJOR | `/health/ml` and `/health/kafka` are ADMIN only; `/actuator/health` returns only `UP` or `DOWN` on the management port | add5d86 | `test_health_detail_is_not_public` |
| CR-18 | MAJOR | **API side done**: the exact `DecisionResponse` property set is asserted (M2c now fails). **Events side open**: raw topic resolved constraints, primitives, `compare_digest` | add5d86 | `test_ingest_response_exposes_no_model_internals` |
| CR-19 | MAJOR | PersonName 2–100 with the Unicode NFC rule (b60145d, ADR 0013); `StaffReference` now uses `PersonName` | b60145d, add5d86 | `test_every_staff_name_field_uses_the_person_name_rule`; `PersonNameTest` (Java) and `personName.test.ts` against the shared vectors ("Jean Bosco", "Marie Claire Uwase" and "Ọlá" in NFD and NFC are accepted) |
| CR-20 | MINOR | `ml_unavailable_fallback` recorded in ADR 0011 §9. `if`/`then` rules: non-HOLD ⇒ deadline null, HOLD ⇒ MEDIUM, APPROVE ⇒ LOW, HIGH ⇒ DECLINE; DECLINE at a lower tier stays allowed for frozen accounts | d2eaccd, add5d86 | `test_decision_tier_and_deadline_are_consistent` (7 cases) |
| CR-21 | MINOR | **API side done** (one shape, `decided_at` defined for every state). **Events side open**: identical-schema test | add5d86 | — |
| CR-22 | MINOR | `ProblemType` catalogue (26 types); `Problem.type` references it; `x-problem-types` on every problem response; `ValidationProblem` restricted to the validation type and 400/422 | add5d86 | `test_problem_types_come_from_the_catalogue`, `test_validation_problem_is_400_or_422_with_the_validation_type` |
| CR-23 | MINOR | `/actuator/health` added with a path-level server override | add5d86 | route-presence test |
| CR-24 | MINOR | Login 403 `account-not-active` only after correct credentials; registration returns a neutral 202 (409 removed); logout revokes by the `sid` claim | add5d86 | ADR 0014 §8; M7 obligations |
| CR-25 | MINOR | `thresholds_not_ordered` and `duplicate_channel` documented; vectors (server-only checks) | add5d86 | `test_request_vector[ThresholdUpdate: …]` |
| CR-26 | MINOR | **API side done**: `webhook_url` SSRF rules and `invalid_cidr` documented with vectors. **Events side open**: signature vectors and strict `t` parsing | add5d86 | `test_request_vector[ApiKeyCreate: …]`, `[IpAllowlistEntryCreate: …]` |
| CR-27 | MINOR | **API side done**: the MSISDN cases moved to vectors using the unassigned E.164 country code 999. **Events side open**: `test_events.py` fixtures | add5d86 | `grep -rn 250788 contracts` is empty on this branch |
| CR-28 | MINOR | **Not split.** 70e083b is published on both M1 branches, and the build prompt prohibits rewriting published history, so the commit cannot be split or reworded. Disclosure instead: the licence exceptions in 70e083b (`jsonschema-path@0.5.0`, `pathable@0.6.0`, `openapi-schema-validator@0.9.0`, `nodeenv@1.10.0`, `archunit@1.5.0`) and the ADR 0009 amendment are named here and in the M1 walkthrough. Each was verified from the package's licence file at the time. The owner may decide otherwise | — | This row; `docs/walkthrough/M1.md` |
| CR-29 | MINOR | **API side done**: the FR-07-01 tag moved to the matrix tests; route-presence tests carry no requirement tags. **Events side open**: D-15 tag | add5d86 | `fs-traceability check` |
| CR-30 | MINOR | Campaigns RISK_OFFICER only; circuit breakers ADMIN only; ADMIN on model performance justified by FR-06-03 in the matrix | add5d86 | matrix test |
| CR-31 | NIT | URN kept; ADR 0011 §6 notes that `fraudshield` is an unregistered namespace | d2eaccd | — |
| CR-32 | NIT | All timestamps use `Timestamp` | add5d86 | `test_timestamps_are_utc` (only the `Timestamp` schema declares `date-time`) |
| CR-33 | NIT | **API side done**: server-side CIDR parsing documented with vectors. **Events side open**: proto items | add5d86 | vectors |

Also resolved while fixing: the new `schema-examples.yaml` first used random-looking `tok_` values
under `token:` keys, and the default gitleaks `generic-api-key` rule flagged them in `make ci`
(`gitleaks dir`, 8 findings). The examples now use low-entropy placeholders
(`tok_exampleAccount0000000001`), so no allowlist entry is needed for them.

## m1/contracts-events

CR-02 (events side), CR-11 … CR-17, CR-18 (events side), CR-21 (events side), CR-26 (vectors),
CR-27 (events fixtures), CR-29 (D-15), CR-33 (proto), CR-34: **OPEN**, to be fixed after the
branch is rebased onto `main`.

## Other process notes

- **Owner finding 3 (mixed commits).** Every commit in this round was made with the owner's
  procedure. Changes outside the commit were parked (tracked diffs to a patch, untracked files to
  an archive), only that commit's files were staged, `pre-commit run --files` was run on the staged
  files, and the parked changes were restored afterwards.
- **bd497ec** claims a CI and Makefile change but contains only the Makefile. 5419096 completes it
  and says so. Neither is rewritten.
- **Remote branches.** `git ls-remote origin` still shows `refs/heads/m0/bootstrap` (83a9090),
  although the owner reported deleting it. The author did not delete it (it was not created in
  this session's work on M1).
