# Re-review: M1 contracts, `m1/contracts-events`

Reviewer role: Principal Reviewer (independent; build prompt Part I.2)
Date: 2026-09-17T08:20Z (UTC)
Branch/commit: `m1/contracts-events` @ 4a7d8d2debbe263b8ce496fcf81e0ccb31737dc2, rebased onto `m1/contracts-openapi` @ fb9093f.
Diff reviewed: `git diff fb9093f..4a7d8d2`, plus `git show` for 962cb40, ee3c685, ced4324, 065a66c, b419e1e, 837dd48, 07638e2 and 4a7d8d2.
Inputs: `contracts-review.md` (8f29a5e), `contracts-response.md` section "m1/contracts-events".
Scope: CR-02 (events), CR-11 … CR-17, CR-18/21/26/27 (events), CR-29 (D-15), CR-33 (proto), CR-34. OpenAPI findings are out of scope.

Verdict: **CHANGES_REQUIRED**

Summary: 13 of 15 in-scope findings are verified fixed and 2 are partly fixed (CR-11, CR-12). There are 11 new findings: **0 BLOCKER, 2 MAJOR, 6 MINOR, 3 NIT**. The gitleaks configuration now meets the owner's constraint, and an independent 9-secret probe was fully detected. All original mutations except the alert half of M4, which the chosen mode allows, are now caught. The two MAJOR findings are real breaking changes that the new checkers accept:

- **Kafka:** widening a `oneOf` branch rejects every staff notification with a recipient user, and all 415 tests pass.
- **Protobuf:** changing `Score` to server streaming passes all tests, including the baseline equality test. Dropping a reservation is also reported as compatible, so a removed field number can be reused with another type in the next change.

Environment: `export PATH=~/.local/opt/node/bin:~/.local/bin:$PATH`. Isolated worktree at `<scratchpad>/fs-rereview-events` (4a7d8d2). Short-lived worktrees at 962cb40, ced4324, 065a66c and b419e1e were removed. gitleaks 8.30.1 (pinned). Docker was not used.

## Checks re-run

| Command (worktree at 4a7d8d2 unless stated) | Result |
|---|---|
| `uv sync --all-packages --locked` | ok |
| `cd contracts && uv run pytest -q` | **415 passed**, coverage 93.64 % |
| `uv run ruff check` / `ruff format --check` (tools ml contracts) | All checks passed / 51 files already formatted |
| `uv run mypy tools/src tools/tests ml/src ml/tests contracts/src contracts/tests` | Success, 46 source files |
| `cd tools && uv run pytest -q` | 132 passed |
| `make governance` (defect register, traceability seed, `fs-traceability check`, scope guard, compose budget) | all clean; traceability 258 rows, 108 tagged tests, 0 errors |
| `uv run fs-licences` | 144 violations, all `npm:` with an unknown licence. `frontend/node_modules` is not installed in the worktree, so this is environmental; the CI licence job succeeded |
| `tools/bin/gitleaks-selftest` | exit 0: "9 planted secrets detected in git and dir scans; 15 allowlisted fixtures otherwise clean" |
| `tools/bin/gitleaks git --log-opts=HEAD --redact --no-banner --exit-code 1 .` | 58 commits, no leaks, exit 0 |
| `tools/bin/gitleaks dir --redact --no-banner --exit-code 1 .` | no leaks, exit 0 |
| Independent gitleaks probe: throw-away repo under the scratchpad with the repository config and fixtures, plus 9 random fake secrets. P1: the exact `fsk_` placeholder in another file. P2: an exact history `tok_` value outside the Kafka examples. P3: an AWS key in `schema-examples.yaml`. P4: a generic `password` in the vector file. P5: an allowlisted `whsec_test_` value with a random suffix. P6: an `fsk_test_` key in a Kafka example. P7: `whsec_stg_` in `schema-examples.yaml`. P8: a random `tok_` in a Kafka example. P9: a published test secret in `deploy/` | **9/9 reported in both `git` and `dir` modes** |
| Rebase check: `git show a160160` vs `git show 962cb40` (index lines ignored) | Only `docs/traceability/requirements_matrix.md` context differs ✓ |
| `contracts` pytest at 962cb40 / ced4324 / 065a66c / b419e1e | 334 passed / **2 failed**, 412 passed / **2 failed**, 412 passed / 415 passed (NF-E07) |
| `grep -rn '250788\|0788\|+250' contracts docs/adr/0012* tools/bin/gitleaks-selftest`; `grep -rn D-15 contracts/tests` | empty; empty |
| Added-line scan (`git diff fb9093f..HEAD`) for emails, URLs, private IPs and hostnames | Emails found: `selftest@example.com` (self-test git identity) and `a@b.c` (negative test value) (NF-E10). URLs found: json-schema.org, pypi.org and files.pythonhosted.org (uv.lock), and `https://x` (negative test). No internal hostnames or IPs |
| Commit metadata `fb9093f..HEAD` | author and committer `Marius Bayizere <bayizeremarius119@gmail.com>` on all 8 commits; no Co-Authored-By or tool signatures |
| `git status --porcelain` in the worktree after every mutation; `git -C /home/marius/fraudshield status --porcelain` | clean; clean |

## Mutation table

Each mutation was applied in the worktree and followed by `cd contracts && uv run pytest -q --no-cov -rfE` or `tools/bin/gitleaks-selftest`. The worktree was then restored with `git checkout -- . && git clean -fdq`. R5c was run in Python on compiled edited copies (`proto_compat.summarise` + `breaking_changes`).

| # | Mutation | Result |
|---|---|---|
| M3 | Proto: `model_version` 14→18 and `shap_base_value = 12` deleted without `reserved` | **caught**: `test_proto_matches_its_committed_baseline` ("renumbered from 14 to 18"), plus 4 self-tests |
| M3b | Proto: `Transaction.amount` `Money`→`string` | **caught**: `test_proto_matches_its_committed_baseline` ("type changed to 'TYPE_STRING'") |
| M4 | Remove `decision` from `decision-final.required` and `holds_transaction` from `alert.required` | decision-final **caught** (`test_decision_final_payload_is_the_openapi_final_decision`). Alert alone **survived**. Under BACKWARD mode (consumers first), making a field optional is compatible, so this is accepted by policy (NF-E11) |
| M4b | (a) Remove `APPROVE` from `FinalDecisionValue`. (b) Replace `decision` with a local enum without `APPROVE`. (c) `received_at` → integer. (d) `model_version.maxLength` 10 | **all caught** by `test_current_schema_is_backward_compatible_with_its_baseline[…]`, and also by the identity or example tests |
| M5 | `transaction-raw.account_id` → `{type: string}` | **caught**: compatibility (`$ref` changed) and `test_raw_transaction_payload_has_the_ingest_request_constraints` |
| M7 | `sig == exp` instead of `hmac.compare_digest` | **caught**: `test_signatures_are_compared_in_constant_time` |
| M8 | `notification-customer.parameters.additionalProperties: true` | **caught**: `test_customer_notifications_carry_no_contact_details` |
| M9 | `tok_` allowlist value → `.*` and the vector-file `paths` removed | **caught**: self-test exit 1 (planted Kafka `tok_` and copied test secret NOT DETECTED) |
| M9a / M9b | Each half of M9 separately | **caught** / **caught** |
| R1 | Delete `kafka/baseline/alert.schema.json` | **caught**: `test_every_published_schema_has_a_baseline` |
| R2 | Widen envelope `producer` pattern `{2,40}`→`{2,60}` | **caught** (conservative: any pattern change is reported) |
| R2b | Tighten `producer` to `{2,30}` in **both** the schema and its baseline | **survived**: 415 passed (NF-E04) |
| R3 | Remove `required` from one `if` in `alert.schema.json` | **caught**: compatibility + `test_if_conditions_require_their_discriminator` |
| R3b | R3 in both the schema and the baseline | **caught**: `test_if_conditions_require_their_discriminator` |
| R4 / R4b | Add `core-banking` as producer of `fs.transactions.raw` / consumer of `fs.decisions.final` | **caught** / **caught**: `test_only_internal_services_touch_kafka` |
| R5 | Delete field 12 and write `reserved 13; reserved "shap_base_value";` | **caught** (baseline test and self-tests) |
| R5a | Delete field 12 and reserve the number only | **caught** |
| R5b | `rpc Score(ScoreRequest) returns (stream ScoreResponse)` | **survived**: 415 passed, baseline equality included (NF-E02) |
| R5c | v1 → reserve 12 → drop the reservation → `string shap_base_value = 12`; the same for enum `CHANNEL_USSD = 4` | **survived**: `breaking_changes` returns `[]` at every step (NF-E02) |
| R6 / R6b / R6c | Drop `targetRules` from the vector / Kafka / schema-examples allowlist | **caught** / **caught** / **caught** (self-test) |
| R7a | Vector-file value → `^whsec_test_[A-Za-z0-9]{32}$` | **caught** |
| R7b | Vector-file value → `^whsec_test_[0-9a-f]{32}$` | **survived**: the planted value is alphanumeric, so it is not matched (NF-E03) |
| R7c | Schema-examples value → `^fsk_prod_.*$` | **caught** |
| R7d | Schema-examples value → `^whsec_.*$` | **survived**: no `whsec_` value is planted in that file (NF-E03) |
| R7e | Kafka value → `^tok_[A-Za-z0-9]+$` | **caught** |
| R7f / R7h | Kafka allowlist `paths` → `.*` / removed | **survived** / **survived** (NF-E03) |
| R7g | Schema-examples allowlist `paths` removed | **survived** (NF-E03) |
| R8 | Verifier accepts duplicate `t` (`len(timestamps) < 1`) | **caught**: `test_signature_vectors[duplicate t]` |
| R8b | Verifier accepts duplicate identical `t` (`len(set(timestamps)) != 1`) | **survived** (NF-E09) |
| R8c | Verifier returns MALFORMED for unknown header keys | **survived**: the vectors do not pin either behaviour (NF-E09) |
| R8d | `value.isdigit()` restored | **caught**: `[t in non-ASCII digits]` |
| R9 | Topic binding check in `validate_event` disabled | **caught**: `test_envelope_must_match_the_topic_binding[*]` |
| R10 | `WelcomeParameters` made unsatisfiable (requires an undeclared `user_name`), schema and baseline | **survived** (NF-E08) |
| R10b | WELCOME bound to `ModelGateResultParameters`, schema and baseline | **survived** (NF-E08) |
| R11 | Staff `recipient_user_id` `oneOf[1]` `{type: null}` → `{type: [null, string]}` (every UUID now matches both branches, so `oneOf` fails) | **survived**: 415 passed; `breaking_changes` = `[]` (NF-E01) |
| R11b | `common.$defs.Uuid.type` → `[string, null]` | caught only **incidentally** by examples and identity tests; `breaking_changes` = `[]` (NF-E01) |
| R12 | Envelope `payload` (open object) gains `properties: {notification_id: {type: integer}}` | caught only **incidentally** by two examples; `breaking_changes` = `[]` (NF-E01) |
| R14 | `tolerant()` not applied for readers | **caught** |
| R15 | `should_apply` uses `>=` | **caught**: `[the same state delivered twice]` |
| R16 | `REPLAY_WINDOW_SECONDS = 301` | **caught**: `[301 seconds old]`, `[301 seconds in the future]` |

Teeth score. Original mutations: 8 of 8 caught, apart from the alert half of M4, which the chosen mode allows. Own mutations, counting R6a–c and R7a–h individually: 22 caught (2 of them only incidentally) and 13 survived.

## Original findings

| # | Sev | Status | Evidence |
|---|---|---|---|
| CR-02 (events) | BLOCKER | **VERIFIED_FIXED** | `contracts/kafka/schemas/decision-final.schema.json:7-18` (event_id, decision_sequence, final, reason_codes, supersedes_decision required), `:78-157` (transition `if`s with required discriminators). `contracts/webhooks/decision-final.md:35-37` (each attempt signed with its own `t`), `:39-52` (apply only a higher sequence; 2xx either way; `event_id` stable across retries). `contracts/webhooks/delivery-ordering-vectors.json`. `contracts/src/fraudshield_contracts/webhooks.py:58-65`. Tests: `contracts/tests/test_events.py:304-308`, `contracts/tests/test_webhooks.py:63-83`. M4 (decision-final) and R15 caught. Delivery head-of-line issue: NF-E06 |
| CR-11 | MAJOR | **PARTIALLY_FIXED** | Done: 13 baseline files (`contracts/kafka/baseline/`), `compatibility.py`, `test_compatibility.py:25-31` (each schema has a baseline and is compatible with it), `:46-116` (13 breaking kinds), `:119-133` (5 compatible). BACKWARD mode, consumers first and tolerant readers are in ADR 0012:53-64, `events.py:74-97`, `test_events.py:122-133`. Topic binding: `topics.yaml`, `events.py:115-124`, `test_events.py:115-119`. M4b, M5, R1, R2, R3 and R9 caught. Open: real breaking changes accepted (NF-E01); baseline editable in the same commit (NF-E04); the "new schema_version alongside the old" mechanism contradicts the single-version binding (NF-E05) |
| CR-12 | MAJOR | **PARTIALLY_FIXED** | Done: `contracts/proto/baseline/scoring-v1.json`, `proto_compat.py`, `test_compatibility.py:139-222`; M3, M3b, R5 and R5a caught. Open: streaming flags are not in the summary, and dropping a reservation is accepted, so reserve→unreserve→reuse passes (NF-E02) |
| CR-13 | MAJOR | **VERIFIED_FIXED** | `.gitleaks.toml:24-56`: three allowlists, each with `targetRules`, `condition = "AND"`, one anchored file path and anchored exact values. The broad `tok_` rule is gone. Independent probe 9/9 detected in `git` and `dir` modes. M9, M9a, M9b, R6a–c, R7a, R7c and R7e caught. CI step runs `tools/bin/gitleaks-selftest` (`.github/workflows/ci.yml`, job `gitleaks`). The earlier inaccurate claim is corrected in the response. The self-test does not guard every scope: NF-E03 |
| CR-14 | MAJOR | **VERIFIED_FIXED** | `.gitleaks.toml:12-22` rules `fraudshield-api-key` and `fraudshield-webhook-signing-secret`; formats match `contracts/openapi/fraudshield-api.yaml:3677,3681`. Probe P1, P6, P7 and P9 detected; self-test plants a bare `fsk_` key and a bare `whsec_prod_` secret |
| CR-15 | MAJOR | **VERIFIED_FIXED** | `docs/adr/0012-event-and-scoring-contracts.md:30-40` (Kafka internal; institution from the principal; SRS 3.2 step replaced; per-institution topics rejected with reasons). `contracts/kafka/topics.yaml:9-14,27-39,80-89`. `envelope.schema.json:34-37`. `test_events.py:136-141`. R4 and R4b caught |
| CR-16 | MAJOR | **VERIFIED_FIXED** | `notification-staff.schema.json:60-178` (8 closed `$defs`, no credential fields), `:180-…` (`if kind` with `required` → `$ref`). ADR 0012:45-52. `test_events.py:190-221`. There are no valid examples per kind: NF-E08 |
| CR-17 | MAJOR | **VERIFIED_FIXED** | `notification-customer.schema.json:6-14` (`verification_link_allowed` required), `:43-70` (closed parameters with the E.7 fields required; `local_time` needs a zone), `:72-90` (`sms.auto_block` ⇒ `auto_block_event_id`). `test_events.py:159-187`. M8 caught |
| CR-18 (events) | MAJOR | **VERIFIED_FIXED** | `test_events.py:272-294` (13 primitives compared after resolving `$ref`s), `:297-301` (raw transaction equals the ingest request), `:311-315`; `test_webhooks.py:49-60`. M5 and M7 caught |
| CR-21 (events) | MINOR | **VERIFIED_FIXED** | `test_events.py:304-308`: exact structural equality with the OpenAPI `FinalDecision`. M4 (decision-final) caught |
| CR-26 (events) | MINOR | **VERIFIED_FIXED** | `webhooks.py:16-17,38-48` (`[0-9]{1,12}`, 64 lower-case hex, exactly one `t`). `signature-test-vectors.json`: 15 vectors including 300/301 s past and future, rotation ×3, duplicate `t`, non-ASCII digits and upper-case hex. `test_webhooks.py:27-46`. R8, R8d and R16 caught. Remaining edge cases: NF-E09 |
| CR-27 (events) | MINOR | **VERIFIED_FIXED** | `test_events.py:21-24` (E.164 code 999); grep for `250788`/`0788`/`+250` is empty |
| CR-29 (D-15) | MINOR | **VERIFIED_FIXED** | `grep -rn D-15 contracts/tests` is empty; `test_events.py:318-321` has no tag; `fs-traceability check` reports 0 errors |
| CR-33 (proto) | NIT | **VERIFIED_FIXED** | `scoring.proto:171-177` (`message Missing {}` in the oneof), `:191-199` (`Stage` enum), `:33-37` (`GetModelStatus`; `grpc.health.v1` noted); ADR 0012:65-73; `test_proto.py:86-97` |
| CR-34 | NIT | **VERIFIED_FIXED** | 13 distinct example `event_id`s; `test_events.py:110-112` |

Counts: VERIFIED_FIXED 13 · PARTIALLY_FIXED 2 · NOT_FIXED 0 · ACCEPTED_DEVIATION 0.

## New findings

| # | Severity | Location | Finding | Required action |
|---|---|---|---|---|
| NF-E01 | **MAJOR** | `contracts/src/fraudshield_contracts/compatibility.py:95-115` (`_object_changes` looks only at old properties), `:127-136` (`oneOf` branches recursed as if widening were safe); ADR 0012:61-62 ("deliberately conservative") | The owner requires a test that fails on a breaking Kafka change, and the checker accepts real ones. (1) **Widening one `oneOf` branch** can make valid instances match two branches, so they are rejected. R11 does this to the staff `recipient_user_id`, which then rejects every notification addressed to a user. All 415 tests pass, because the only example uses `null`. (2) **A new `properties` entry on an open object**, or one with an `additionalProperties` schema, narrows what was accepted (R12, and `{additionalProperties: {type: [string, integer]}}` + `properties: {a: integer}`), but the checker returns `[]`. (3) Making a shared `$defs` type nullable (R11b) is accepted by the checker. Examples caught R11b and R12 only by chance. The ADR's claim that the checker is conservative is therefore overstated. | Treat any non-identical change inside a `oneOf` branch as not provably compatible. For a new property, report it unless the old object had `additionalProperties: false`, or the new property schema is provably at least as wide as the old `additionalProperties` schema. Add R11, R12 and the `additionalProperties`-schema case to `BREAKING`. Correct ADR 0012 §5 if any case stays unhandled |
| NF-E02 | **MAJOR** | `contracts/src/fraudshield_contracts/proto_compat.py:94-100` (a method records only input and output), `:142-171` and `:174-184` (reserved ranges and names are never compared old→new; no enum reuse check); ADR 0012:75-80 | (1) Changing `rpc Score(ScoreRequest) returns (ScoreResponse)` to `returns (stream ScoreResponse)` breaks every client on the wire, yet all 415 tests pass. The summary does not change, so even `current == baseline` holds and there is no baseline diff to review (R5b). (2) Removing a reservation is reported as compatible. Following the test's own instruction ("compatible proto change: regenerate … baseline"), reserve 12 → drop the reservation → `string shap_base_value = 12` passes at every step (R5c). The same holds for enum values. The owner's rule that removed numbers stay reserved is enforced only for one change. | Add `client_streaming` and `server_streaming` to the summary and compare them. Report any reserved number or name that disappears, for messages and enums. Check new enum values against reserved numbers and names. Consider comparing the Java file options as a generated-code break. Add self-tests for streaming, reservation removal and enum reuse, then regenerate the baseline |
| NF-E03 | MINOR | `tools/bin/gitleaks-selftest:51-101`; `docs/reviews/M1/contracts-response.md` CR-13 row | The configuration is correct today (probe 9/9), but the self-test does not guard every scope. Four edits leave it green: widening the `schema-examples.yaml` `whsec_` value to `^whsec_.*$` (R7d), removing or widening the `paths` of the Kafka allowlist (R7f, R7h) or the schema-examples allowlist (R7g), and widening the vector value to hex-only (R7b; the planted value is alphanumeric). The response says removing a path scope fails the self-test, but that holds for only one of the three allowlists. | For every (allowlist, targetRule) pair, plant a non-allowlisted value of the same shape in that file. Copy every exact allowlisted value into a non-fixture file and expect it reported, which proves the path scopes. Draw planted values from the same alphabet as the allowlisted values. Correct the response wording |
| NF-E04 | MINOR | `contracts/tests/test_compatibility.py:25-31,139-145`; `contracts/kafka/baseline/README.md:3-7` | The current schema is compared only with a baseline in the same commit. Tightening a schema and its baseline together passes (R2b, 415 passed), and so does NF-E02 (2). Only human review of the baseline diff protects published versions. | In CI, compare each baseline file with the merge-base version on `main` using the same checkers (fetch `main` with enough depth), or require owner review on `contracts/*/baseline/**` and document it |
| NF-E05 | MINOR | ADR 0012:27-28 and 57-60; `contracts/kafka/topics.yaml:16` (one `schema_version` per topic); `events.py:115-124`; `baseline/README.md:3-7` (unversioned file names) | ADR §5 says a breaking change adds a new `schema_version` "published alongside the old one until producers have migrated". Each topic binds exactly one version, however, and `validate_event` rejects any other. Schema and baseline files carry no version. The documented migration path cannot be expressed. | Define the mechanism: accepted versions per topic mapped to versioned schema and baseline files, with a test. Alternatively, state that a breaking change needs a new topic |
| NF-E06 | MINOR | `contracts/webhooks/decision-final.md:70-72` | "A state is not sent while an earlier state of the same transaction is still being retried." With 24-hour retries, a newer DECLINE (for example a senior override after a customer-verification APPROVE) can be held for up to a day behind an earlier state that keeps failing, such as a payload-specific 4xx. Receivers already order by `decision_sequence`, so superseded states need not block. The spec also does not say whether sequence 1, the synchronous ingest decision, is ever delivered by webhook. | Deliver the latest state and cancel or skip superseded retries, or bound the wait. State whether `decision_sequence = 1` is published to the webhook, and add a vector if it is |
| NF-E07 | MINOR | ced4324, 065a66c (commit messages and trees) | After the rebase, ced4324 and 065a66c fail `test_raw_transaction_payload_has_the_ingest_request_constraints` and `test_decision_final_payload_is_the_openapi_final_decision` (2 failed, 412 passed); b419e1e fixes them. The ced4324 message still claims "Contracts: 384 tests, 92.42% coverage". Measured: 414 collected, 2 failed, 93.64 %. No CI ran on these SHAs, and the response does not say that two commits are red. | Record in the response that ced4324 and 065a66c are not green, and why, and that the ced4324 test-count claim is stale. Do not rewrite history further unless the owner directs it |
| NF-E08 | MINOR | `contracts/kafka/schemas/notification-staff.schema.json:60-178`; `contracts/tests/test_events.py:190-221` | Only `ACCOUNT_FROZEN` has a valid instance. Seven of the eight per-kind parameter schemas are tested only with invalid payloads, so an unsatisfiable `WelcomeParameters` (R10) or WELCOME bound to the wrong definition (R10b) passes. This is the CR-01 defect class, on the event side. | Add one valid payload per `kind` and validate it with `validate_event`, alongside the negative cases |
| NF-E09 | NIT | `contracts/src/fraudshield_contracts/webhooks.py:38-48`; `decision-final.md:26,41-42`; `signature-test-vectors.json` | The shared oracle leaves three cases open. (a) The spec signs the literal `<t>`, but the verifier signs `str(int(t))`. `t=01789624961` with a signature over `1789624961.` is VALID in Python, and INVALID_SIGNATURE for a signature over the raw string, so the Java and integrator verifiers can diverge. (b) The spec says "anything else is malformed", yet unknown keys are ignored, and neither behaviour is pinned (R8c). (c) A duplicate identical `t` is not covered (R8b). | Restrict `t` to `0\|[1-9][0-9]{0,11}`, or sign the raw header value. Decide the rule for unknown keys and word the spec to match. Add vectors for all three |
| NF-E10 | NIT | `tools/bin/gitleaks-selftest:127` (`selftest@example.com`); `contracts/tests/test_events.py:204` (`a@b.c`) | Two email-shaped strings were added. Both are reserved or synthetic, not personal data, but the owner rule allows only the author's address. | Confirm with the owner that RFC 2606 or synthetic test addresses are acceptable, or avoid them (for example use `example.invalid` and build the negative value at run time) |
| NF-E11 | NIT | `contracts/tests/test_compatibility.py:47`; response CR-11 row | The comment and the response say `BREAKING` includes the review's M4 edits. M4 (removing fields from `required`) is compatible under BACKWARD mode and is not in the list; the alert half of M4 survives, as designed. | Correct the wording, and record in ADR 0012 §5 that relaxing `required` is compatible, with the consumer-side consequence |

New-finding counts: BLOCKER 0 · MAJOR 2 · MINOR 6 · NIT 3.

## CI evidence (unauthenticated GitHub REST API; job-level conclusions)

| Commit | Workflow run | Jobs |
|---|---|---|
| 4a7d8d2 | `ci` 35197325905 (push, completed) | pre-commit hooks on all files: **success**; gitleaks: **success**, including steps `gitleaks git …` success and `tools/bin/gitleaks-selftest` success; python (ruff, mypy --strict, pytest): **success**; java: **success**; frontend: **success**; dependency licence inventory: **success**; traceability, defect register, scope guard, commit messages: **success** |
| 4a7d8d2 | `stack` 35197325909 | detect stack input changes: success; core compose stack healthy + smoke test: **skipped** |
| 4a7d8d2 | `devcontainer` 35197325954 | detect devcontainer input changes: success; build devcontainer, post-create make ci, smoke test: **skipped** |
| 07638e2 | `ci` 35197285915 / `stack` 35197285890 / `devcontainer` 35197285908 | cancelled / cancelled (no jobs) / detect success, build **skipped** |
| 837dd48 | `ci` 35197192069 / `devcontainer` 35197192054 / `stack` 35197192098 | success / build **cancelled** / stack job **cancelled** |
| 962cb40, ee3c685, ced4324, 065a66c, b419e1e | — | no runs |

Observation: the stack and devcontainer jobs (the devcontainer job runs `make ci`, including `make secrets-scan`, with the new `grpcio-tools`/`protobuf` dependencies) never completed on this branch's new content. They were cancelled at 837dd48, and later pushes were judged to have no input changes. Locally, `gitleaks dir` and the self-test are clean. These jobs should run on the push to `main`.

## Merge decision

**No.** `m1/contracts-events` may not be fast-forward merged to `main`, even after `m1/contracts-openapi`, while NF-E01 and NF-E02 (MAJOR) are open. Both are owner focus areas: Kafka backward compatibility, and protobuf reserved numbers and evolution. Fix them in new commits with self-tests for R5b, R5c, R11 and R12, then re-review. The MINOR and NIT findings should be fixed or explicitly accepted in the response. The branch is otherwise in good shape: the gitleaks constraint is met, tenant isolation and the notification findings are resolved, and the webhook ordering contract is sound.
