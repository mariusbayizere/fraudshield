# Review 1: P1's contract changes for the analyst reads (ADR 0110)

- **Subject:** `git diff eaaacb8 ec96fd5 -- contracts backend/auth/src/test`, ADR 0110 (Proposed)
- **Branch / commit:** `m8b/analyst-api` at `ec96fd5`
- **Reviewer:** independent; wrote none of this work. No Maven, no Docker. The Python contract
  tests were run (495 passed), plus mutation runs on a scratch copy of `contracts/` (below).
- **Date:** 2026-10-20

## Verdict: APPROVE_WITH_CHANGES

Points 1 (CSRF), 2 (queue semantics), 6 (comparison list) and 7 (unique keys) are sound OpenAPI
3.1 and can be frozen once the MINOR wording fixes are made. Point 4 (`anomaly_score` on every
alert) needs one schema fix (finding 4). Point 5 (local time) makes a claim about the features
that the code contradicts (finding 5). **Point 3 (`masked_account`) should not be frozen as
written.** Its schema is valid. But serving it means the analyst API decrypts vault contacts for
every feed row, which ADR 0069 forbids, and it changes an SRS requirement without recording the
deviation (findings 1 to 3). The owner decides point 3 anyway. That decision should be taken on
an ADR that states these costs.

## Findings

### 1. MAJOR: serving `masked_account` crosses ADR 0069's vault boundary, and the ADR does not say so

The only place the masked number exists is in the encrypted `vault.contacts` JSON document,
next to the phone number. So to fill the field, the analyst API has to:
- read `vault.contacts` as `fs_vault`, a role ADR 0069 point 1 gives to "the notification
  service";
- decrypt the whole contact, phone included (`VaultContacts.find`,
  `backend/notify/.../notify/vault/VaultContacts.java:107-150`);
- do this for every alert on every page of `listAlerts`, on every 60 s re-read by every
  analyst.

That contradicts accepted decisions:
- ADR 0069 point 4: the value "is resolved for one send, never logged, stored or put on a topic".
- `ContactDirectory` javadoc (`backend/notify/.../notify/sms/ContactDirectory.java:6-9`): "only
  for the duration of one send".
- ADR 0069 point 1: "Nothing that can read a transaction can read a phone number". The
  alert-feed code reads transactions.

The cost is also unmentioned. Under `KmsKeyProvider`, the production default (ADR 0069 point 10),
each `find` unwraps a data key through the KMS, so one feed page costs up to `limit` KMS calls.

The ADR's null rule is also tied to the wrong table: "Null when the vault holds no contact for
the account" (`fraudshield-api.yaml:3359-3366`). The account number exists for every tokenised
account in `vault.account_tokens` (vault `V2__account_tokens.sql`, ADR 0069 point 9), whether or
not a contact was enrolled. So the field would be null for accounts whose number FraudShield
actually holds.

**Fix.** Either:
- (a) Amend ADR 0069 explicitly, with the owner's sign-off. Name a new reader and purpose, for
  example a `MaskedAccounts` port that returns only the masked string and never the phone, sourced
  from `account_tokens`, with a stated cache and KMS cost and its own tests; or
- (b) Store the masked suffix at enrolment as a separate non-secret value that the main database
  may hold, and say why that is not PII under NFR-SEC-03.

Until one of these is chosen, the honest option is the ADR's own alternative: the field nullable
and always null, under whichever name.

### 2. MAJOR: the change rewrites FR-04-03 and NFR-SEC-03 without recording a deviation

The SRS says:
- **FR-04-03:** the card shows a "masked account name", and the acceptance is "All 8 elements
  present on every alert card" (`docs/srs/FraudShield_SRS_v1_0.md:140`).
- **The alert feed's columns** include "masked account name" (`:279`).
- **NFR-SEC-03** (`:211`): "account holder name … stored as opaque tokens … API response
  audit: account_name field in all analyst endpoints shows masked token only".

ADR 0110 replaces the name with a partially revealed account number, and makes it nullable, so
the element can be absent from a card. It also brings a value derived from real PII (the last
characters of the account number) into analyst responses for the first time. That is precisely
what NFR-SEC-03's inspector test (PB-114) has to judge. The ADR lists FR-04-03 and NFR-SEC-03 as
affected but records no deviation for either, and the traceability rows will not say so.

**Fix.** Record the deviation for both requirements in the ADR's Decision section, with the
owner's decision. Say whether a masked suffix counts as a "masked token" under NFR-SEC-03, and
put a line in each matrix row's notes.

### 3. MINOR: "the same value the customer's SMS shows" is not guaranteed, and the pattern is not enforced at the source

Nothing validates the vault's `masked_account` against `^\*{3,}[0-9A-Za-z]{2,4}$`:
- `VaultContacts.store` writes whatever it is given (`VaultContacts.java:77-81`);
- `find` returns it unchecked (`:146-148`);
- `ContactDirectory.Contact` has no check.

The SMS pattern applies to the intent's placeholder, which is the token's last four characters
(`CustomerSmsPolicy.java:17-19`). It does not apply to the vault value the SMS actually renders.
So an enrolment that stores, say, `XXXX-4821` would make the API emit responses that violate the
contract.

The schema also has no `maxLength`, so `\*{3,}` is unbounded. A 500-asterisk value validates
(checked with `jsonschema`, Draft 2020-12).

**Fix.**
- Validate the pattern at enrolment (`store`) and on read. On read, map a non-conforming value to
  null and count it.
- Add `maxLength` (16 is ample).
- Add a contract test that the OpenAPI pattern equals `notification-customer.schema.json`'s
  `parameters.masked_account.pattern`. Today the ADR's "that is the pattern of the customer SMS"
  is pinned by nothing: changing the OpenAPI pattern to `{2,6}` passed every test.

### 4. MAJOR: `anomaly_score` is required and non-null, but fallback alerts store a fabricated 0

When the scorer is unavailable:
- `DecisionService.fallbackRecord` builds the `ScoringRecord` with `anomalyScore = 0`
  (`backend/decision/.../application/DecisionService.java:385-404`; parameter order at
  `DecisionEvent.java:289-295`);
- `PostgresSink` writes it to `fraud_scores.anomaly_score` (`PostgresSink.java:320`);
- `DecisionEvent` itself carries null for a fallback (`DecisionService.java:230`,
  `DecisionEvent.java:100`).

A fallback HIGH or MEDIUM alert therefore gets `anomaly_score: 0`, which the schema describes as
"The Isolation Forest's score" (`fraudshield-api.yaml:3378-3380`). The card ADR 0110 asks M8 to
build would show a measured anomaly of 0% for a transaction no model scored. This was already
latent in `AlertDetail` and in `ScoringResult` (`:3296`). The change carries it onto every feed
card.

**Fix.**
- Make the field `oneOf: [Probability, null]`, documented as "null when the decision was a
  fallback (`ml_unavailable_fallback`)", in both `AlertSummaryBase` and `ScoringResult`.
- Keep it required, so absent never means unknown.
- Describe it as the Isolation Forest **percentile** (D-06; `Scoring.java:39`), not a
  probability-like "score", so the console does not present it as a likelihood.

### 5. MAJOR: "the same local time the `local_hour` features use" is false for non-domestic currencies

The `hourly_counts` description (`fraudshield-api.yaml:3522-3526`) and ADR point 5 say the hours
use the institution's country pack offset, "the same local time the `local_hour` features use
(… V67)". The features use two different definitions:
- **Scoring and training** take the account's country from the transaction's **currency**
  (`ml/src/fraudshield_ml/serving/features.py:104-127`, `featurestore/reference.py:74-81`,
  `cli.py:136`, `features/batch.py:521-545`).
- **Only V67's fallback** uses the institution's country
  (`V67__feature_store_fallback.sql:62-75`).

Ingest does not restrict the currency to the institution's: V3 allows ten currencies, and nothing
in `backend/ingest` checks domesticity. So a KES transaction at a Rwandan institution:
- is scored with Kenya's offset (+3);
- would be fallback-scored with Rwanda's (+2);
- would be counted in the profile with Rwanda's.

"An institution's accounts are domestic to it" is an assumption V67 makes and the scorer does
not.

**Fix.**
- Define the profile's hours as the institution's country pack offset, and drop the claim that it
  is the features' local time; or state the divergence for non-domestic currencies.
- Report the scorer/fallback disagreement separately as a train/serve skew (it is outside P1, but
  this review found it).

### 6. MINOR: queue semantics rest on a draft contract and leave three cases open

- **A dependency on a draft.** Point 2 freezes semantics taken from
  `analyst-decision-commit-contract.md` §6, which is "draft 2, for independent review" (line 3).
  The ADR should make point 2 conditional on that contract's acceptance, or say that the two are
  accepted together.
- **"Plus entries the caller escalated"** (`fraudshield-api.yaml:806-807, 817`). The original
  becomes ESCALATED, which no queue lists without `outcome`, so the clause can only mean the new
  entries the caller's escalation created. That includes entries targeted above the caller's role.
  Say so.
- **The order of `queue=all` under `sort=priority` is undefined.** ANOMALY entries are ordered by
  `anomaly_score` in their own queue, but by fraud probability once mixed into `all`. State the
  order.
- **Search finds only open entries by default.** Without `outcome`, a `queue=all` search returns
  only PENDING and IN_REVIEW. FR-04-09's search by transaction ID will usually want decided
  entries, so the ADR's consequence should be "search with `queue=all` and an explicit `outcome`".
- **"Below 0.60 by construction" is wrong** (`:805-806`). ANOMALY is LOW tier plus the anomaly
  threshold (`DecisionEngine.java:94-99`). LOW is below the channel's configurable
  `medium_threshold` (`V6__risk_configuration.sql:90-99`), not below 0.60. Say "below the
  channel's MEDIUM threshold".

### 7. MINOR: the comparison and network definitions leave gaps an implementer must guess

The `comparison` rules (`fraudshield-api.yaml:3553-3580`):
- "One item per attribute, in the enum's order" implies exactly six items, but the schema allows
  any number. Add `minItems: 6, maxItems: 6`. The `BehaviourProfile` example
  (`schema-examples.yaml:258-262`) has one item, contradicting the description.
- The window's anchor is not stated: the 90 days before the compared transaction, or before
  now? "The window's earlier transactions" only makes sense for the former, and `hourly_counts`
  should use the same anchor.
- `counterparty_country` is nullable in V3 (`V3__transactions_and_scoring.sql:36`). The rule for
  a null value, and whether nulls count as "seen", is not given.
- The `local_hour` rule (the shortest range holding at least 90%) has no tie-break.

All six attributes are computable from `transactions` (amount_rwf, channel, counterparty_token,
device_token, counterparty_country, transaction_timestamp) through `transactions_account_time`.

The network (`:3581-3613`):
- "By transaction count descending" does not say with whom: with the account, or each node's
  total in the window.
- "Latest label" needs a tie rule, and should follow the supersedes chain the decision-commit
  contract introduces (§3.9, `supersedes_label_id`), not just `label_available_at`.
- "Either direction" needs lookups by `counterparty_token`, which has no index (only V3:46 and
  V67:56-60). The 90-day degree-2 walk is a hypertable scan without one.

Ordering the anomaly queue by `anomaly_score` likewise needs a join to the `fraud_scores`
hypertable, or a copied column, for keyset paging. Neither is mentioned. The ADR should name the
migrations (V8x) this implies.

### 8. MINOR: test gaps (what a revert or a drift would not catch)

Mutations were run against a scratch copy of `contracts/`, with the package path pointed at the
copy. The baseline in the copy has one unrelated failure (the Java Money path).

| Mutation | Caught? |
|---|---|
| Drop `anomaly_score: true` from AlertDetail's list | yes (examples) |
| Drop `anomaly_score: true` from AlertSummary's list | yes |
| Remove `all` from the queue enum | yes |
| Change `x-csrf-cookie` | yes |
| Remove an `x-unique-key` | yes (`found == 6`) |
| Make `masked_account` non-nullable | **no** |
| Widen the pattern so it diverges from the SMS schema | **no** |
| Revert the anomaly-queue ordering sentence, or `hourly_counts`'s zone | **no** (prose; expected) |

Further gaps:
- `test_alerts_show_a_masked_account_number_and_never_a_name` (`contracts/tests/test_openapi.py:579-585`)
  is tagged NFR-SEC-03 and claims "never a name". But `[0-9A-Za-z]{2,4}` accepts `***Anne`, so
  the test proves the format, not the absence of a name. Rename it, or drop the NFR-SEC-03 tag
  until PB-114's inspector test exists (paper evidence rule).
- It uses `re.fullmatch`, while validators use search semantics. With Python's `$`,
  `"***4821\n"` validates under `jsonschema`. Use `\z`-safe wording, or test through the
  validator.
- `x-unique-key` is only checked to name required properties. No example or response is
  checked for uniqueness. A small helper in `test_openapi_examples.py` that asserts each
  example's keyed arrays are unique would make the key mean something before the server tests
  do.

### 9. MINOR: the CSRF tie is real but only for names

`CsrfCookieContractTest` reads the packaged contract (`ContractPolicy.OPENAPI`, packaged from
`contracts/openapi` by `backend/auth/pom.xml:125-130`). It compares against the server's
constants:
- `CsrfDoubleSubmitFilter.HEADER` and `COOKIE` (`CsrfDoubleSubmitFilter.java:26,29`);
- `SessionCookies.REFRESH` (`SessionCookies.java:19`).

The Python test pins the literals. Together they do tie server to contract: changing either side
fails a test.

Not pinned:
- the `in` of the header and the cookie on the Java side;
- the attributes that the login description now states as contract: readable by script,
  SameSite=Strict, path `/` (`fraudshield-api.yaml:403-405`; the server sets them at
  `SessionCookies.java:55-60`).

Either pin them (`x-csrf-cookie-attributes`, or assert the `issue()` output against the
description), or say in the description that only the name is normative.

The console's half does not exist yet. `frontend/src/auth/csrf.ts:6` on `origin/m8/frontend-rebase`
still says "The contract names the header and the refresh cookie but not this cookie", which must
change with the test.

### 10. MINOR: consumer breakage the ADR understates

**On the console** (`git grep origin/m8/frontend-rebase -- frontend`), everything that must change:
- **The generated client:** `src/api/generated/types.gen.ts`, `zod.gen.ts`, and
  `src/mocks/generated/examples.json` (4 occurrences).
- **The cut-over must be in lockstep.** `zAlertSummaryBase` requires `masked_account_name`, and
  `checked('zAlertPage', …)` (`src/alerts/queries.ts:80`), `checked('zAlertDetail', …)`
  (`detail/queries.ts:38`) and `checked('zAlertSummary', …)` (`detail/actions.ts:133`) reject
  every alert response from a server built to the new contract. The ADR should say the console
  and P1's server must ship together, or the console first.
- **`masked_account_name` usages:**
  - `src/alerts/AlertCard.tsx:125,136`;
  - `detail/AlertDetailPanel.tsx:193` (already null-safe);
  - `detail/DecisionPanel.tsx:141,169`;
  - fixtures in `AlertFeedPage.test.tsx:33`, `AnomalyQueuePage.test.tsx:36,42`,
    `EscalationsQueuePage.test.tsx:35,42`, `SearchPage.test.tsx:35,40`, `auth/session.test.tsx:177`.
- **The i18n strings now read wrongly, and render "null" when the field is null:**
  - "Alert for {{name}}", "Investigation of {{name}}", "Fraud confirmed for {{name}}",
    "{{name}} marked legitimate.", "{{name}} escalated to {{role}}." in
    `src/i18n/locales/{en,fr,rw,sw}/alerts.json` (en: lines 57, 115, 252-254);
  - `translation-status.json`.
- **`anomaly_score`:** `detail/SummaryTab.tsx:102` formats it as a percentage. The card must add
  it, and must handle null under finding 4.
- **Comparison:** `detail/AccountContext.test.tsx:81` uses `device_age_days`, which the new enum
  rejects. `detail/BehaviourTab.tsx:117-131` keys by position and shows raw keys; both can change
  under `x-unique-key` and PB-132.
- **Queue and search:** `queueView.ts:73` and `SearchPage.test.tsx:148` search with `standard`
  and must move to `all` with an explicit `outcome` (finding 6). The mocks
  (`src/mocks/handlers.ts:64-109`) need `all` and the open-only filter.
- **CSRF:** `auth/csrf.ts` and `csrf.test.ts` (finding 9).

**Elsewhere in the repository:**
- **The backend** has no `masked_account_name`, `AlertSummary` or `listAlerts` code yet, so
  nothing in it breaks.
- **The Kafka alert schema** (`contracts/kafka/schemas/alert.schema.json:46`) already carries
  `anomaly_score`. It does not change.
- **The SRS wording** at lines 140, 211 and 279 is affected (finding 2).

### 11. MINOR: smaller items missing from the ADR

- **Item 9's second question** (whether `DashboardStats.reviewed_today` and the average review
  time count anomaly reviews) is not answered, nor deferred by name. Item 11 is deferred
  explicitly; do the same here.
- **`AlertSummaryBase`'s description is stale.** It says the composing schemas close it "with
  unevaluatedProperties" (`fraudshield-api.yaml:3341-3344`). They close it with
  `additionalProperties` over listed names. The change follows the real mechanism correctly;
  fix the comment while the schema is open.
- **`allOf: [{$ref: Probability}]` plus `description` is valid** but unnecessary in 3.1, where
  `$ref` may carry `description` directly. Harmless; it will become `oneOf` with null under
  finding 4 anyway.
- **`amount_histogram`'s key `lower_rwf`** is a `DecimalAmount` string. Uniqueness should be
  numeric (`"100"` and `"100.00"`). Say "by value".

## Checks requested, and what was verified

1. **OpenAPI 3.1 / JSON Schema correctness.**
   - `anomaly_score` in the base with `allOf` and `description` is valid. Both closed lists
     include it (`fraudshield-api.yaml:3337, 3383`), and removing either fails the examples test.
   - `masked_account` `type: [string, "null"]` with `pattern`: `pattern` applies only to
     strings, so null validates (checked with `jsonschema` 2020-12: null valid, `***4821` valid,
     `J*** M***` invalid).
   - `x-unique-key` sits on the array schemas. An `x-` key is allowed on a Schema Object, and
     all six name required item properties.
   - The comparison enum and `queue=all` are valid.
   - The contract tests pass (495).
2. **Claims.**
   - Verified: the vault holds no holder name (vault `V1__vault_contacts.sql`,
     `VaultContacts.java:77-81`: phone, locale, masked account) and no table stores one (V3
     `transactions` has tokens only).
   - Verified: the SMS schema's `masked_account` pattern is the one proposed
     (`notification-customer.schema.json`).
   - Not as stated: the vault value is unvalidated (finding 3) and its use is limited to one send
     (finding 1).
   - Verified: V67 uses the institution's country.
   - Refuted: that this is the features' local time (finding 5).
   - Verified: the comparison attributes are computable from V3 over 90 days (finding 7 lists the
     undefined edges). The network is computable, needing an index for efficient either-direction
     lookups.
   - Verified: `fraud_scores.anomaly_score` is NOT NULL, so the join is always possible, but its
     value is fabricated for fallbacks (finding 4).
3. **Tests pin what they claim:** mostly. The gaps are in finding 8.
4. **The CSRF tie:** yes, for the names (finding 9).
5. **Consumer breakage:** finding 10.
6. **Missing from the ADR, and what a frozen-contract reviewer should refuse:**
   - Refuse point 3 as written (findings 1 and 2).
   - Refuse a non-null `anomaly_score` (finding 4).
   - Refuse the local-time sentence (finding 5).
   - The rest can be frozen after the MINOR fixes.
