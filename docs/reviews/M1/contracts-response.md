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
| CR-28 | MINOR | **Not split.** 70e083b is published on both M1 branches, and the build prompt prohibits rewriting published history, so the commit cannot be split or reworded. Disclosure instead: the licence exceptions in 70e083b (`jsonschema-path@0.5.0`, `pathable@0.6.0`, `openapi-schema-validator@0.9.0`, `nodeenv@1.10.0`, `archunit@1.5.0`) and the ADR 0009 amendment are named here. Each was verified from the package's licence file at the time. The M1 walkthrough, written when M1 closes, will repeat this disclosure. The owner may decide otherwise | — | This row (the earlier pointer to `docs/walkthrough/M1.md` was wrong: that file does not exist yet, re-review NF-15) |
| CR-29 | MINOR | **API side done**: the FR-07-01 tag moved to the matrix tests; route-presence tests carry no requirement tags. **Events side open**: D-15 tag | add5d86 | `fs-traceability check` |
| CR-30 | MINOR | Campaigns RISK_OFFICER only; circuit breakers ADMIN only; ADMIN on model performance justified by FR-06-03 in the matrix | add5d86 | matrix test |
| CR-31 | NIT | URN kept; ADR 0011 §6 notes that `fraudshield` is an unregistered namespace | d2eaccd | — |
| CR-32 | NIT | All timestamps use `Timestamp` | add5d86 | `test_timestamps_are_utc` (only the `Timestamp` schema declares `date-time`) |
| CR-33 | NIT | **API side done**: server-side CIDR parsing documented with vectors. **Events side open**: proto items | add5d86 | vectors |

Also resolved while fixing: the new `schema-examples.yaml` first used random-looking `tok_` values
under `token:` keys, and the default gitleaks `generic-api-key` rule flagged them in `make ci`
(`gitleaks dir`, 8 findings). The examples now use low-entropy placeholders
(`tok_exampleAccount0000000001`), so no allowlist entry is needed for them.

## Re-review of m1/contracts-openapi (NF-01 … NF-15)

Re-review: `contracts-openapi-rereview.md` (at c2ec944; verdict CHANGES_REQUIRED, 0 BLOCKER / 2 MAJOR /
10 MINOR / 3 NIT; 22 original findings verified fixed, 3 partly fixed, CR-28 accepted as a deviation).
All fixes are in one commit after c2ec944, recorded in the commit message. The ADR amendments are
included in it because the contract, tests and ADR text change together.

| # | Sev | Resolution | Verification |
|---|---|---|---|
| NF-01 | MAJOR | `unevaluatedProperties` is gone. The eight composed schemas list their members' property names beside `additionalProperties: false`, so one invalid value gives one error | `test_composed_schemas_are_closed_with_exactly_their_members_properties` (listed names must equal the members' properties); `test_one_bad_value_in_a_composed_schema_reports_one_error` |
| NF-02 | MAJOR | All 30 operations with a JSON or multipart body document 400 and 422 `ValidationProblem`. New codes: `length_out_of_range`, `item_count_out_of_range` (replaces `too_many_items`), `duplicate_items`, `escalation_target_not_higher`. `const` → `unsupported_value`, `minProperties` → `required`. ADR 0011 table rows added. The escalation target rule is now a 422 validation error with that code | `test_every_json_request_body_documents_400_and_422_validation_problems`; `test_operations_document_every_status_their_vectors_expect` |
| NF-03 | MINOR | The matrix lists each operation's object-level rules (problem/status/code, or `filter` for the escalated queue, which is now a declared rule), and `matrix_differences` compares them. There are explicit tests for sign-in and registration responses | `test_matrix_check_detects_a_removed_or_changed_object_rule` (N2, N2b, N2c, N17 equivalents); `test_sign_in_and_registration_do_not_disclose_account_state` (N19, N20) |
| NF-04 | MINOR | `fraudshield_contracts.validation.error_codes` maps JSON Schema errors to (field, code); unmapped keywords raise | `test_request_vector` requires exact equality with each vector (N3b and N3c now fail); classifier tests for composed branches, lengths, duplicates and unmapped keywords |
| NF-05 | MINOR | Sign-in `password` and `current_password` have a 1,024-character transport cap; above 72 bytes → 401 (ADR 0014 §7) | `test_password_schema_limits_match_the_vectors` asserts both caps (N4b) |
| NF-06 | MINOR | Character rule defined by Unicode category: no C, no Z except U+0020; special = any other permitted non-space character. New reason CHARACTERS; each vector says whether the schema screen detects it | 7 new vectors (U+00A0, U+2028, U+FEFF, U+200B, U+001C, NUL, tab) plus a currency-symbol accept; the reference implementation uses `unicodedata.category` |
| NF-07 | MINOR | `/actuator/health/ml` on the management port (`x-network: management`) for the probe and smoke test; deviation recorded in ADR 0014 §6 and on OPS-CI-07 and OPS-OBS-05 | `test_management_endpoints_are_public_only_on_the_management_network` |
| NF-08 | MINOR | `StateConflict` requires `StaleAlertProblem` when `type` is stale-alert (`if`/`then`); the orphaned `StaleAlert` response is removed | `test_stale_alert_conflicts_must_carry_the_current_alert` |
| NF-09 | MINOR | `JobStatus` item `problem` is `oneOf` `ValidationProblem` or `IdempotencyConflictProblem` (type and 409 fixed); example added | `test_batch_items_can_report_an_idempotency_conflict`; schema examples |
| NF-10 | MINOR | `register` wording softened; ADR 0014 §8 records availability as an accepted, rate-limited residual enumeration risk (E.8) | — |
| NF-11 | MINOR | Exact `FinalDecision` required set, UUID formats, exact `raw_key` and `webhook_signing_secret` patterns with matching and non-matching samples | `test_final_decision_identity_fields_are_exact` (N6c, N6d); `test_generated_secrets_have_exact_recognisable_formats` (N9) |
| NF-12 | NIT | Invariant: `escalateAlert` roles ⊆ {ANALYST, SENIOR_ANALYST} | `test_only_roles_below_risk_officer_can_escalate` (N13) |
| NF-13 | NIT | ADR 0011 §9 and the schema say the frozen-account DECLINE is server-enforced (M6) | — |
| NF-14 | NIT | Management endpoints use a server URL with `{managementPort}` | management-network test |
| NF-15 | MINOR | CR-28 row corrected above | — |

Found while fixing: every `if` that tested a property without requiring it passed vacuously when the
property was absent. For example, an ingest request without `channel` was also told that `agent_id` was
required. All `if` blocks now require their discriminator, and `test_if_conditions_require_their_discriminator`
walks the whole document.

## m1/contracts-events

Rebased onto `m1/contracts-openapi` at fb9093f. The owner's merge order stands: once that branch is
fast-forwarded to `main`, `main` is fb9093f and this branch needs no further rebase. The original
contract commit a160160 is now 962cb40; its content is unchanged apart from the regenerated
traceability matrix. Fix commits: ee3c685 (ADR 0012), ced4324 (contracts), 065a66c (secret scanning),
b419e1e (event `if` conditions), 837dd48 (secret-format test samples).

| # | Sev | Resolution | Commit | Verification |
|---|---|---|---|---|
| CR-02 | BLOCKER | **Events side**: `decision-final` has `event_id`, `decision_sequence`, `final`, `reason_codes`, `review_deadline_at` and `supersedes_decision`, with the same transition rules as the OpenAPI `FinalDecision`. The webhook spec signs each attempt with its own `t` (so late retries stay inside the window) and requires receivers to apply only a higher `decision_sequence` and answer 2xx either way. New `delivery-ordering-vectors.json`; reference `should_apply` | ced4324, b419e1e | `test_decision_final_payload_is_the_openapi_final_decision` (exact equality after resolving `$ref`s); `test_delivery_ordering_vectors` (repeated DECLINE with a higher sequence applied; late APPROVE ignored; duplicate ignored); `test_retries_are_signed_with_the_attempt_time` |
| CR-11 | MAJOR | `kafka/baseline/` plus `fraudshield_contracts.compatibility`. Mode: backward compatible only, consumers deploy first as tolerant readers (`validate_event(reader=True)`), producers validate closed. `event_type` and `schema_version` bound per topic | ced4324 | `test_current_schema_is_backward_compatible_with_its_baseline` (13 schema files); `test_checker_reports_breaking_changes` (13 kinds, including the review's M4b edits; the M4 edit, making fields optional, is compatible under this mode, as corrected after re-review NF-E11); `test_checker_accepts_compatible_changes` (5); `test_envelope_must_match_the_topic_binding`; `test_consumers_tolerate_unknown_fields_that_producers_may_not_send` |
| CR-12 | MAJOR | `proto/baseline/scoring-v1.json` (descriptor summary) plus `fraudshield_contracts.proto_compat`; `buf` not added (ADR 0012) | ced4324 | `test_proto_matches_its_committed_baseline`; `test_proto_checker_reports_breaking_changes` compiles edited copies (renumber as in M3, delete without reserve, type change as in M3b, rename, label, enum rename, method removal); reserved removals and added fields accepted, reuse of a reserved number reported |
| CR-13 | MAJOR | The broad `tok_` allowlist is removed; current Kafka examples use low-entropy placeholders. Allowlists name exact values in exact files, including the pre-fix values still in history, and every allowlist is rule-scoped (`targetRules`). While fixing, the author found that gitleaks 8.30.1 `dir` skips whole files matching a global allowlist path even with `condition = "AND"`: an AWS key planted in the vector file was not reported | 065a66c | `tools/bin/gitleaks-selftest` (make and CI secrets job): 9 planted fake secrets, including one inside an allowlisted fixture, must all be reported in both `git` and `dir` scans. The author ran three config mutations (remove `targetRules`, widen an exact value, remove a path scope), and each failed the self-test. Correction after re-review NF-E03: the path-scope mutation was caught for the vector-file allowlist only, not the other two; see the events re-review section below. The earlier commit claim "verified that real-looking keys still fail" was inaccurate, as the review said |
| CR-14 | MAJOR | Rules `fraudshield-api-key` (`fsk_…`) and `fraudshield-webhook-signing-secret` (`whsec_…`); the `whsec_` format is defined in the OpenAPI contract | 065a66c, add5d86 | self-test planted bare `fsk_` and `whsec_prod_` values are reported |
| CR-15 | MAJOR | Kafka is internal to a deployment: only FraudShield service principals produce and consume (ACLs from `topics.yaml`). Core banking uses HTTP ingest and webhooks. `institution_id` is set from the authenticated principal. The SRS 3.2 direct publish is replaced and recorded (ADR 0012 §2) | ee3c685, ced4324 | `test_only_internal_services_touch_kafka` |
| CR-16 | MAJOR | Staff notification parameters are closed per kind (`$defs` plus `if`/`then` on `kind`); credentials and links are minted by the auth module at send time and are never in events | ced4324 | `test_staff_notifications_carry_no_credentials_or_contact_details` (temporary password, code, link, missing field, email); `test_every_staff_notification_kind_has_a_closed_parameter_schema` |
| CR-17 | MAJOR | `verification_link_allowed` required; `auto_block_event_id` required for `sms.auto_block`; E.7 parameters required; `local_time` needs a zone | ced4324 | `test_customer_notifications_fail_closed` (5 cases); phone inside `parameters` rejected (the review's M8 now fails) |
| CR-18 | MAJOR | **Events side**: shared primitives (13), `transaction-raw` and `decision-final` are compared with OpenAPI after inlining every `$ref`; constant-time comparison asserted | ced4324 | `test_shared_primitives_are_identical_to_openapi`; `test_raw_transaction_payload_has_the_ingest_request_constraints`; `test_structural_comparison_notices_a_weakened_identifier` (the review's M5); `test_signatures_are_compared_in_constant_time` (M7) |
| CR-21 | MINOR | **Events side**: identical-shape test (see CR-02) | ced4324 | as CR-02 |
| CR-26 | MINOR | **Events side**: 15 signature vectors (300 and 301 seconds old and in the future, rotation with either or neither secret, missing or duplicate `t`, non-ASCII digits, non-hex `v1`); the verifier accepts exactly one ASCII-digit `t` and 64-hex `v1` values | ced4324 | `test_signature_vectors`, `test_signature_vectors_cover_the_boundaries_and_rotation` |
| CR-27 | MINOR | **Events side**: fixtures use the unassigned E.164 code 999 | ced4324 | `grep -rn 250788 contracts` is empty |
| CR-29 | MINOR | **Events side**: the D-15 tag is removed from the envelope `event_id` test | ced4324 | `fs-traceability check` |
| CR-33 | NIT | **Proto**: `FeatureValue.Missing` empty message; `StageTiming.Stage` enum; `Health` renamed `GetModelStatus`, with the standard `grpc.health.v1` service registered alongside (ADR 0012 §6) | ced4324 | `test_feature_value_can_represent_structural_missingness`, `test_service_exposes_score_and_model_status` |
| CR-34 | NIT | Distinct example event IDs | ced4324 | `test_example_event_ids_are_unique` |

Also changed on this branch:
- `protobuf==7.36.1` is declared in `contracts/pyproject.toml` because `proto_compat` imports it directly. It was already locked at that version; `uv.lock` gains two lines.
- b419e1e applies the OpenAPI finding on vacuous `if` conditions to the alert, decision-final and transaction-raw event schemas. It refreshes the Kafka baseline; nothing on this branch has reached `main`, so the baseline records v1 as first merged.
- 837dd48: the new `fsk_` and `whsec_` rules flagged literal format samples from an OpenAPI test in `.pyc` files and pytest's cache (`gitleaks dir` also reads ignored files). The samples are now built at run time.

## Re-check of m1/contracts-openapi (NEW-01 … NEW-07)

`contracts-openapi-recheck.md`: fb9093f APPROVED_WITH_MINORS. `main` was fast-forwarded to fb9093f;
CI on `main` (runs 35197895291, 35197895156, 35197895202) succeeded in every job, including the stack
smoke test and the devcontainer build. The minor findings are fixed on the events branch in 556099c.

| # | Sev | Resolution | Verification |
|---|---|---|---|
| NEW-01 | MINOR | The error mapping chooses the `oneOf` branch whose own required keys are present, then the fewest errors; a `oneOf` matching several branches is `unsupported_value` | `RuleDraft` vectors: a bad operator inside `all` and at the root → `expression…op` `unsupported_value` |
| NEW-02 | MINOR | `/actuator/health/kafka` (management port) replaces the SRS staging gate's Kafka check; ADR 0014 §6 and both traceability notes updated | `test_management_endpoints_are_public_only_on_the_management_network` |
| NEW-03 | MINOR | Vectors for rules, an empty update (`minProperties` → `required` on field `""`), a short override reason, empty and duplicate scopes, an empty batch, and a server-only escalation target (62 cases) | `test_vectors_exercise_every_error_code_the_mapping_produces` |
| NEW-04 | NIT | ADR 0011 says JSON or multipart bodies | — |
| NEW-05 | NIT | ADR 0014 listed in `deviations` of OPS-CI-07 and OPS-OBS-05 (checked by `fs-traceability check`) | traceability check |
| NEW-06 | NIT | Invariants for self-modification, last active admin, own-decision override, undo by author and the escalated-queue filter wording | `test_self_elevation_and_self_override_rules_cannot_be_dropped` |
| NEW-07 | NIT | Only Cc, Cf, Cs, Co and non-space Z are rejected; unassigned code points are allowed, so runtimes on different Unicode versions agree | vector with U+0378 |

## Re-review of m1/contracts-events (NF-E01 … NF-E11)

`contracts-events-rereview.md`: 4a7d8d2 CHANGES_REQUIRED (13 of 15 verified fixed; 2 MAJOR, 6 MINOR,
3 NIT new). Fixes: 339ed23 (contracts, guard, ADR 0012, webhook spec), 792ca4b (self-test).

| # | Sev | Resolution | Verification |
|---|---|---|---|
| NF-E01 | MAJOR | `breaking_changes_between` compares whole schema sets. Breaking: any change inside a `oneOf`; any change to a definition reachable from a `oneOf` (`fragile_definitions`); constrained properties added to open objects. The ADR states which widenings stay compatible | `test_set_checker_reports_changes_that_break_other_schemas` (R11, R11b, R12); `test_definitions_used_inside_oneof_are_fragile` |
| NF-E02 | MAJOR | Proto summary records client and server streaming; dropped reservations and reuse of reserved or removed enum numbers and names are breaking; baseline regenerated | "response made server-streaming" compiles an edited proto (R5b); `test_proto_checker_rejects_dropping_a_reservation_to_reuse_it` (R5c); `test_proto_checker_rejects_enum_value_reuse` |
| NF-E03 | MINOR | The self-test reads each exact allowlisted value from `.gitleaks.toml` (and stops on a pattern), plants it in an unrelated directory and beside its allowed file, and plants new values in each allowed path in the allowed values' alphabets; 28 planted secrets | Author mutations, all caught: Kafka path widened to `contracts/` and within its directory; vector path widened within its directory; schema-example path removed and widened; schema-example `whsec_` value widened; vector value turned into a hex pattern; `targetRules` dropped |
| NF-E04 | MINOR | `fs-contract-baselines --against origin/main` checks the current Kafka schemas and the compiled proto against the baselines at the merge base (governance CI job with full history, and `make governance`) | `test_guard_uses_the_published_baseline_not_the_edited_one` (R2b: a narrowed enum and a removed method against a "published" baseline) |
| NF-E05 | MINOR | A breaking change becomes a new topic `<topic>.v<N>` with its own schema file and version | ADR 0012 §5, `topics.yaml` comment |
| NF-E06 | MINOR | Sequence 1 is never sent by webhook; a newer state cancels pending retries of older states and is sent immediately | `decision-final.md` Delivery; ADR 0012 §7 |
| NF-E07 | MINOR | **Disclosed.** After the rebase, ced4324 and 065a66c fail 2 contract tests (vacuous event `if` conditions), fixed in b419e1e. ced4324's "384 tests, 92.42%" was measured before the rebase onto the NF-fixed OpenAPI contract; at that commit 414 tests are collected and 2 fail. History is not rewritten again | this row |
| NF-E08 | MINOR | A valid parameter set for each of the 8 staff notification kinds; each is rejected when bound to a kind with different parameters | `test_every_staff_notification_kind_accepts_its_own_parameters_only` (R10, R10b) |
| NF-E09 | NIT | `t` must be ASCII digits without leading zeros, so the signed text is unambiguous; unknown keys are ignored as the spec now says; 18 vectors including an identical duplicate `t`, a leading zero and an unknown key | `test_signature_vectors` |
| NF-E10 | NIT | The self-test's committer email is not email-shaped; the test's email-shaped rejection value is built at run time. The OpenAPI examples on `main` still use RFC 2606 `example.com` addresses as synthetic placeholders; the owner may prefer none | — |
| NF-E11 | NIT | CR-11 row corrected: making a field optional is compatible in this mode, and the self-tests do not include M4 | this file |

## Re-check of m1/contracts-events (N-01 … N-09) and owner decisions

`contracts-events-recheck.md`: 0ea2f2f CHANGES_REQUIRED on one MAJOR (N-01). Every NF-E finding was
verified fixed except NF-E07, which was accepted as disclosed. The owner then decided six further
points (2026-09-17). All the work below is in commits after the history rewrite described next; SHAs
in this section are post-rewrite.

| # | Sev | Resolution | Commit | Verification |
|---|---|---|---|---|
| N-01 | MAJOR | `make secrets-scan` runs `uv run python tools/bin/gitleaks-selftest`; the CI secrets job calls the script directly (the runner has `python3`) | e692225 | devcontainer workflow on the pushed head (CI evidence below) |
| N-02 | MINOR | Contract test forbids `$ref` under `if`, `not`, `contains`, `propertyNames`, `dependentSchemas`; ADR 0012 wording narrowed. The checker itself is unchanged (feature-complete, owner decision 5) | e692225 | `test_no_reference_hides_under_keywords_the_compatibility_checker_does_not_track` |
| N-03 | MINOR | Superseded: the custom proto checker is deleted and replaced by buf (owner decision 5) | c86db7c | `test_proto_breaking.py` |
| N-04 | MINOR | The guard fails (exit 2) when Kafka schemas exist at the merge base but no baseline is read. A test asserts the baseline count read from HEAD equals the committed schema count. Behaviour on `main` pushes is documented | e692225 | `test_guard_fails_closed_when_baselines_cannot_be_read`, `test_published_baselines_are_read_from_git_history` |
| N-05 | NIT | The self-test rejects allowlists without exact values and anchored paths, rule-level allowlists and `.gitleaksignore`, and plants copies beside each allowed file with its extension (37 plants) | e692225 | Author mutations, all caught: vector path widened keeping `.json`; Kafka path widened to `^contracts/.*\.json$`; schema path without `$`; schema path widened keeping `.yaml`; paths-only allowlist; rule-level allowlist; `.gitleaksignore` present. Response wording corrected here |
| N-06 | NIT | Seven-channel `ThresholdUpdate` vector pins `maxItems` → `item_count_out_of_range`. No request schema uses `maxProperties`, and no request `oneOf` can over-match, so neither can be pinned by a request vector (accepted) | 437c401 | `test_request_vector[ThresholdUpdate: more than six channel entries]` |
| N-07 | NIT | Batch sequence 1 is in the job results | e692225 | `decision-final.md` |
| N-08 | NIT | **Accepted, not changed.** Owner decision 5 freezes the JSON Schema checker except for BLOCKERs. Annotation-only edits to definitions a `oneOf` uses will therefore count as breaking | — | — |
| N-09 | NIT | Clear errors for a missing ref and for no common history | e692225 | `test_guard_explains_a_missing_ref` |

**Owner decisions (2026-09-17)**

1. **Asymmetric dual control** for thresholds, the MEDIUM timeout policy and MCC circuit-breaker
   settings. Implemented in 437c401: ADR 0014 §3, `common.config.DualControlWorkflow` with an
   injected clock, and 16 tests (tightening immediate, loosening pending until approved, revert at
   exactly 24 h, self-approval 403, ADMIN, ANALYST and SENIOR_ANALYST 403, classification). The
   contract gains propose (`PATCH /admin/thresholds`, `PATCH /admin/circuit-breaker-settings`), pending
   list, approval and rejection endpoints. Deviations are recorded on FR-02-06, FR-05-07 and FR-03-07.
   The two RISK_OFFICER demo accounts are part of the M1 database seed (next work item).
2. **Health:** public `GET /api/v1/health` returns only `{"status"}`; ml, kafka, db and redis detail is
   on the management port; the deviation is on OPS-CI-07 and OPS-OBS-05 (437c401).
3. **example.com addresses:** kept (RFC 2606).
4. **History rewrite:** ADR 0015 and `tools/bin/verify-branch-commits` (efb3d40). The rewrite and
   per-commit results are below.
5. **buf:** buf 1.72.0 pinned by checksum. All 16 proto mutation cases were first run by hand against
   buf and are now tests, with the custom checker and baseline deleted (c86db7c, ADR 0016). The JSON
   Schema checker is feature-complete.
6. **m0/bootstrap:** `git merge-base --is-ancestor origin/m0/bootstrap origin/main` succeeds, but the
   GitHub API reports `default_branch: m0/bootstrap`, not `main`. Per the owner's instruction the
   branch was **not deleted**; the owner needs to switch the default branch first.

## History rewrite of m1/contracts-events (ADR 0015)

The branch was rebuilt from `main` (fb9093f) with a scripted `git rebase -i`. The final tree is
byte-identical to the pre-rewrite head 0efa2cf (`git diff --quiet` succeeds); the only later change is the verification-script fix noted below. Squashes:

- b419e1e (event `if` discriminators) was folded into ced4324, and the message was reworded to drop
  the stale test count. This makes the combined commit pass on its own (NF-E07).
- 837dd48 (secret-format samples) was folded into 065a66c, with the message extended.
- 4a7d8d2 (count correction) was folded into 07638e2.

| Before | After |
|---|---|
| 962cb40, ee3c685 | unchanged |
| ced4324 + b419e1e | dba1021 |
| 065a66c + 837dd48 | 50e250c |
| 07638e2 + 4a7d8d2 | 40b80e3 |
| 3c24b6c | e389d5a |
| 556099c | b6290b9 |
| 339ed23 | b538cd7 |
| 792ca4b | 696ea58 |
| 0ea2f2f | e013785 |
| 6920324 | c86db7c |
| 888e984 | 437c401 |
| 7a01c77 | e692225 |
| 0efa2cf | efb3d40 |

SHAs earlier in this file, and in the review records, refer to the history before this table.

### Per-commit verification

Per-commit verification of `main..m1/contracts-events` (`tools/bin/verify-branch-commits`, ADR 0015).

| Commit | Subject | Result | Time | Checks |
|---|---|---|---|---|
| 962cb40 | feat(contracts): add Kafka, scoring gRPC and webhook contracts | pass | 39 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts; gitleaks history scan |
| ee3c685 | docs(adr): decide event isolation, evolution checks and secret scanning | pass | 2 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard |
| dba1021 | fix(contracts): resolve the event findings of the contracts review | pass | 28 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts |
| 50e250c | sec(secrets): scope gitleaks allowlists to exact values and prove them | pass | 26 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts; gitleaks self-test |
| 40b80e3 | docs(reviews): respond to the event findings of the contracts review | pass | 2 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard |
| e389d5a | docs(reviews): record the OpenAPI re-check and the events re-review | pass | 2 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard |
| b6290b9 | fix(contracts): resolve the OpenAPI re-check minor findings | pass | 28 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts |
| b538cd7 | fix(contracts): resolve the events re-review findings | pass | 32 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts |
| 696ea58 | sec(secrets): plant every allowlisted value outside its scope | pass | 20 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; gitleaks self-test |
| e013785 | docs(reviews): respond to the OpenAPI re-check and events re-review | pass | 3 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard |
| c86db7c | build(contracts): use buf for protobuf lint and breaking changes | pass | 38 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts |
| 437c401 | feat(contracts): apply the owner decisions on dual control and health | pass | 72 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts; maven verify |
| e692225 | fix(contracts): resolve the events re-check findings | pass | 34 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools; ruff ml; format ml; mypy ml; pytest ml; ruff contracts; format contracts; mypy contracts; pytest contracts; gitleaks self-test |
| efb3d40 | docs(adr): allow history rewriting on unmerged feature branches | pass | 15 s | commit message; uv sync; defect register; traceability seed; traceability; scope guard; ruff tools; format tools; mypy tools; pytest tools |

The first run failed only 962cb40, with a bug in the verification script: it ran the gitleaks self-test at a commit where the self-test does not exist yet. The script was fixed to scan that commit's history with the pinned gitleaks instead, and to ignore an inherited `VIRTUAL_ENV`. The fix was folded into the tip commit (7203304 → efb3d40), and the run above is the second one. This response commit changes only documentation; it passes the governance checks through the pre-commit hook and is covered by `make ci` and CI.

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
