# Review 2: P1's contract changes for the analyst reads (ADR 0110), the revision

- **Subject:** `git diff ec96fd5 8424499 -- contracts backend/auth/src/test
  docs/adr/0110-p1-contract-changes-for-the-analyst-reads.md docs/backlog/product.md`
- **Branch / commit:** `m8b/analyst-api` at `8424499`
- **Reviewer:** independent; wrote neither the change nor review 1. No Maven, no Docker. The
  Python contract tests were run (497 passed). Schema behaviour was probed with the repository's
  own validator (`fraudshield_contracts.openapi.errors`) on mutated copies of the examples, in
  memory; nothing in the tree was changed except this file.
- **Date:** 2026-10-20

## Verdict: APPROVE_WITH_CHANGES

The three MAJOR findings of review 1 are answered in substance. `masked_account` is now nullable
and served as always null, so nothing crosses ADR 0069's vault boundary. `anomaly_score` is
nullable and required, with null for fallback decisions. The local-time sentence now matches the
scorer. The OpenAPI changes are valid and the new tests do what they claim, with one exception
(finding 8's NFR-SEC-03 tag). What remains is MINOR: residue of findings 2, 4, 6, 8, 9, 10 and 11,
and five new MINOR findings. The most important of these are N1 (the examples, and so the
console's mocks, show a masked account P1 will never serve) and N4 (point 3 is not yet laid out
so the owner can decide it). None blocks freezing points 1, 2, 4, 6 and 7. Points 3 and 5 wait
for the owner, as the ADR says.

## Review 1's findings

| # | Sev. (r1) | Status | Why |
|---|---|---|---|
| 1 | MAJOR | RESOLVED | The field is `type: [string, "null"]` and the ADR says P1 serves null and builds no vault access until ADR 0069 is amended (ADR point 3; `fraudshield-api.yaml:3363-3373`). That leaves no decrypt, no KMS cost and no `fs_vault` reader. The null rule no longer depends on `vault.contacts`. The ADR's sourcing claim is correct: `vault.account_tokens` holds every tokenised number as ciphertext (`db/vault/V2__account_tokens.sql:1-24`). Option (b) of review 1 is not mentioned (see N4). |
| 2 | MAJOR | PARTLY | Resolved: the FR-04-03 deviation is recorded in point 3's heading and body, and in the field's description. Not resolved: (a) NFR-SEC-03 is cited, but the ADR does not say whether a masked suffix counts as a "masked token". That is the question PB-114's inspector test must answer, and it should be marked as moving with the ADR 0069 amendment. (b) No matrix row carries a note: `requirements_matrix.md:37` (FR-04-03) still shows "masked account name" with no deviation, and `:82` (NFR-SEC-03) cites the masked-account test as evidence (see 8). (c) FR-04-03's acceptance criterion, "All 8 elements present on every alert card", cannot be met while the field is always null. The deviation should say so in those words. |
| 3 | MINOR | RESOLVED | Two fixes made: `maxLength: 7` added, and the OpenAPI pattern is pinned to `notification-customer.schema.json`'s by `test_alerts_carry_the_sms_masked_account_and_no_holder_name` (`test_openapi.py:579-593`). Widening the pattern now fails that test. Validating at the vault is moot while P1 serves null. Residue: N2. |
| 4 | MAJOR | PARTLY (MAJOR part resolved) | Resolved: `anomaly_score` is `type: [number, "null"]`, `minimum: 0`, `maximum: 1`, still required (`:3385-3391`); null is defined as the fallback, which P1 reads from `fraud_scores.ml_unavailable_fallback` (exists, `V3__transactions_and_scoring.sql:69`). A test pins nullable and required (`test_openapi.py:596-600`). Deferred: `ScoringResult.anomaly_score` (`:3300`) is still a non-null Probability. It is filed as PB-155 with an owner, which is acceptable. Not done: the description still says "the Isolation Forest's score", not the percentile (D-06; `Scoring.java:39`). |
| 5 | MAJOR | RESOLVED | `hourly_counts` (`:3536-3540`) and ADR point 5 now use the country derived from the transaction's currency. Verified at `serving/features.py:113-117` (`reference.account_country(currency)`) and `:240-241` (that country pack's `utc_offset_hours`). The window is anchored on the compared transaction, which the operation requires (`transaction_id`, `:1111-1115`). The scorer/V67 skew is filed as PB-154 with a problem and an acceptance. |
| 6 | MINOR | PARTLY | Resolved: "below its channel's MEDIUM threshold"; "the escalated entries the caller created"; `queue=all`'s `sort=priority` order; and searching decided alerts with an explicit `outcome` (`:802-818`). Not resolved: point 2 still freezes semantics from `analyst-decision-commit-contract.md` §6, now "draft 3, for independent review" (line 3), without making point 2 conditional on that contract's acceptance. |
| 7 | MINOR | RESOLVED (small residue in N5) | Resolved: `minItems`/`maxItems` 6 and a six-item example; the window anchor; the null counterparty country of this transaction; the `local_hour` tie-break; the network's ordering count; the current label via the supersedes chain with a tie rule; the V82 index; and the `fraud_scores` join. |
| 8 | MINOR | PARTLY | Resolved: making the field non-nullable, or diverging from the SMS pattern, now fails a test. The new `test_examples_respect_the_unique_keys_their_schemas_declare` exercises all six keys: an in-memory mutation duplicating one row of each keyed array was caught for every one of the six. Not resolved: (a) the test keeps `@pytest.mark.req("NFR-SEC-03")`, and its new name still says "no holder name". It proves only that the property `masked_account_name` is absent: `***Anne` validates (probed). The matrix cites it as NFR-SEC-03 evidence (`requirements_matrix.md:82`, `test_openapi.py:578`). Per the evidence rule, drop the tag or rename it to what it proves. (b) The format cases still use `re.fullmatch`. Under the Python validator `***482\n` validates (probed). ECMA-262 `$` would reject it, so the contract is right and only the Python harness is lax. Note it, or add the case through the validator. |
| 9 | MINOR | PARTLY | Resolved: `CsrfCookieContractTest` now pins `in: header` and `in: cookie`. Not resolved: login's description still states the CSRF cookie's attributes as contract (readable by script, Secure, SameSite=Strict, path `/`; `:402-405`). Nothing pins them, and the description does not say that only the name is normative. |
| 10 | MINOR | PARTLY | Resolved: the ADR now requires lockstep and lists M8's changes. The counts match `origin/m8/frontend-rebase`: eight fixture occurrences, the generated files and the mocks. Not resolved: the field is now **always null**, and neither the ADR's consequence nor the M8 handover (`docs/parallel/M8B_updates.md:145-150`, "show `masked_account`", "search with `queue=all`") says so. M8 needs to know that the element is absent on every card, and that the five interpolated strings must not render "null". It also needs to know that search must pass an explicit `outcome`. "The console is not on `main`" is loose: `origin/main` has a 14-file `frontend/` skeleton, but none of the alert code. |
| 11 | MINOR | PARTLY | Resolved: `reviewed_today` is deferred to P2 by name; `AlertSummaryBase`'s description is fixed (`:3344-3348`); and the `allOf` wrapper is gone. Not resolved: `amount_histogram`'s key `lower_rwf` is still compared as a string. Say "by value", or require a canonical decimal form. |

## Checks requested

1. **Valid OpenAPI 3.1 / JSON Schema.** All checks were probed with the repository's validator
   on the `AlertSummary` and `AlertDetail` examples.
   - **`masked_account`** is `type: [string, "null"]` with `pattern` and `maxLength`. Both
     keywords apply only to strings:
     - null is valid;
     - `***4821` and `*****21` are valid;
     - `****4821` is invalid (`maxLength`).
   - **`anomaly_score`** is `type: [number, "null"]` with `minimum`/`maximum` in the open base.
     It is still named in both closed lists (`:3341` and `:3394`):
     - null is valid;
     - 1.5 and -0.1 are invalid;
     - removing the property is invalid (required);
     - an extra property is invalid (`additionalProperties: false` holds).
   - **The comparison list** has `minItems`/`maxItems` 6. `BehaviourProfile` is the only example
     with a `comparison` (6 items). Five items are rejected.
   - **The example-key test** covers all six `x-unique-key`s, each with a non-empty array, and
     each duplicate is caught (above). Its nested-object branch is dead code today, since no key
     sits on an inline nested object. Its floor `checked >= 6` counts (example, key) pairs, not
     distinct keys. That is harmless while each schema has one example.
2. **No new error** of schema validity was found. The new problems are N1 to N5.
3. **The owner's decisions.**
   - **PB-154** is decision-ready: it has the problem, the example, an acceptance and "Due: owner".
   - **Point 3 is stated, but not yet decision-ready** (N4).
   - **Overclaims:**
     - The ADR's Consequences say the tests check "that no name is shown". They do not (8a).
     - The Status line says points 3 and 5 need the owner, while "Decided by" says point 3 only
       (N3).
   - **Correct claims:**
     - "P1 serves null when `ml_unavailable_fallback` is true" is a proposal for P1, and the
       column exists.
     - The `features.py:115, 240` references are correct.

## New findings

### N1. MINOR: the examples show a masked account that P1 will never serve, and no example is null

**Evidence.**
- The `AlertSummary`, `AlertDetail` and `AlertPage` examples all carry `masked_account: ***4821`
  and `anomaly_score: 0.42` (probed). ADR point 3 says P1 serves null for every alert.
- The console generates its mocks from these examples (`frontend/src/mocks/generated/examples.json`
  on `origin/m8/frontend-rebase`). M8 would therefore build and test the card against a value it
  will never receive.
- Neither null case (an unknown account, a fallback decision) appears in any example.

**Fix.** Set `masked_account: null` in the alert examples. Add a fallback alert example with
`anomaly_score: null`.

### N2. MINOR: `maxLength: 7` makes the API format a strict subset of the SMS format the ADR says it reproduces

**Evidence.**
- `notification-customer.schema.json:47-50` has the same pattern and no `maxLength`. So
  `****4821` is a valid SMS value and an invalid API value.
- The test pins only pattern equality, and the ADR and description say "in the customer SMS's
  own format".
- The future masked-only lookup (point 3) is not required to produce at most seven characters,
  or to validate what it returns.

**Fix.** Do one of these:
- add `maxLength: 7` to the SMS schema, with a test that compares both keywords;
- or say "a subset of the SMS format: three asterisks and up to four characters, or more
  asterisks with fewer characters".

Either way, state in point 3 that the amendment's lookup must validate against the contract and
map a non-conforming value to null.

### N3. MINOR: ADR inconsistencies and a stale consequence

Each item and its fix:

- **Status vs "Decided by".** Status says "points 3 and 5 need the owner's decision"; "Decided
  by" says the owner decides point 3. Point 5's own open item is PB-154. Make the two lines agree.
- **A stale bullet.** Consequences keep the old bullet "M8 must change its console … four
  changes" beside the new lockstep list. The new list supersedes it. Delete the old bullet.
- **What the contract tests check.** The list says "that no name is shown" (see 8a) and omits
  the new anomaly-score test and the example-key test. Correct the list.

### N4. MINOR: point 3 is stated, but not yet in a form the owner can decide

**Evidence.** The ADR names one route, a masked-only lookup from `vault.account_tokens`, as "an
amendment to ADR 0069 for the owner". It does not give:

- review 1's alternative (b): store the masked suffix at enrolment as a non-secret value in the
  main database;
- the cost of route (a): a KMS unwrap per account under `KmsKeyProvider` unless results are
  cached, and a new `fs_vault` reader in the analyst API;
- the NFR-SEC-03 question each route raises (finding 2a).

No backlog item tracks the amendment. Once ADR 0110 is accepted with the field null, nothing
brings the decision back.

**Fix.**
- In point 3, list the two routes with their cost and their NFR-SEC-03 consequence, and keep the
  current null as the default if the owner declines both.
- File a PB from M8B's range (PB-157 is next) with "Due: owner".

### N5. MINOR: two small gaps left in the account-context definitions

**Evidence.**
- **The network's selection count** (`:3600-3608`). "The number of transactions linking the node
  to the node that brought it in" is ambiguous for a degree-2 node reached through several
  degree-1 nodes: it could be the sum, the maximum, or the count via the first such node.
- **The comparison's `counterparty_country`** (`:3581-3584`). The description now covers a
  transaction with no country. It does not say whether window transactions with a null country
  count toward "most frequent" or "seen".

**Fix.** Do both:
- say "summed over its links to the included nodes one degree lower";
- say "window transactions without a counterparty country are ignored".

## What was run

- `cd contracts && ../.venv/bin/python -m pytest -q -p no:cacheprovider`: 497 passed.
- In-memory probes (scratch scripts, outside the repository):
  - the alert examples with `masked_account` and `anomaly_score` mutated;
  - `BehaviourProfile` with five comparisons;
  - one duplicated row in each keyed array against the new example-key test.
- `git grep` on `origin/m8/frontend-rebase` and `origin/main` for the consumer claims.
