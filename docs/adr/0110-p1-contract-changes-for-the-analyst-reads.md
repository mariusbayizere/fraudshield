# 0110: P1's contract changes for the analyst reads

- **Status:** Accepted (owner, 2026-10-20). Point 3 takes route (a), the masked account number,
  with the PII-vault amendment PB-157; until it exists `masked_account` is always null and FR-04-03
  stays incomplete (its row says so). PB-154 is to be fixed, not kept as a backlog item. Reviews:
  `docs/reviews/M8B/p1-contract-review-1.md` and `-review-2.md` (both APPROVE_WITH_CHANGES; every
  MAJOR answered).
- **Date:** 2026-10-20
- **Decided by:** the owner: points 1 and 2's ownership (`docs/parallel/M8B_scope.md` §7), point 3
  (route (a)) and PB-154 (fix it), on 2026-10-20. The author proposed the content.
- **Requirements affected:** D-27, FR-04-02, FR-04-03, FR-04-06, FR-04-07, FR-04-08, FR-04-09,
  NFR-SEC-03, D-10
- **ADRs referenced:** 0014, 0065, 0069, 0081

## Context

The contract is frozen, so every change is a reviewed proposal before code, as `/system/status`
was (ADR 0081). P1 serves seven read operations. Before they can be built honestly, the contract
has to answer:
- M8's items 5 to 10 in `docs/parallel/M8B_updates.md`;
- the owner's instruction to name the CSRF cookie in P1;
- one gap found while scoping, point 3 below.

## Decision

1. **The CSRF cookie is named in the contract** (owner, 2026-10-20).
   - The `CsrfToken` parameter gains `x-csrf-cookie: fs_csrf`, and login's description names the
     cookie and its attributes.
   - The Python contract test pins the header, the cookie and the refresh cookie.
   - `CsrfCookieContractTest` (auth) asserts the server's constants against the packaged contract.
   - The console's side, `CSRF_COOKIE` in `frontend/src/auth/csrf.ts`, lives on `m8/frontend-rebase`.
     M8 adds the matching test there (handed over in `M8B_updates.md`).
   - **This changes no behaviour.** It names what both sides already do.
2. **What a queue lists, and the queue for search** (items 5 and 10).
   - Without `outcome`, a queue lists only entries awaiting a decision (PENDING or IN_REVIEW).
   - A decision removes its entry from every queue while it is still PENDING_COMMIT, and an undo
     returns it with the next `version`.
   - `outcome` selects statuses explicitly.
   - A new `queue=all` is the union of the queues the caller may see. Paging ties break on
     `alert_id`.
   - The full lifecycle is in `docs/architecture/analyst-decision-commit-contract.md` §6. That
     contract is still under review, and point 2 stands or falls with it.
   - A search that should find decided alerts must pass `outcome` with every status it wants.
3. **The card shows the masked account number: route (a), decided by the owner on 2026-10-20.** Until
   the PII-vault amendment (PB-157) exists, the field `masked_account` is always null.
   - **Nothing can produce a masked holder name.** The vault holds the phone, the locale and the
     masked account number (ADR 0069 point 4). The ingest contract carries no holder name, and no
     table stores one.
   - **Two requirements are in tension:**
     - FR-04-03 puts a "masked account name" among the eight elements on every card;
     - NFR-SEC-03 allows only masked tokens in analyst responses.
   - **The routes:**

     | Route | Shows | FR-04-03 | NFR-SEC-03 | Cost |
     |---|---|---|---|---|
     | **(a)** A masked-only vault lookup | `***4821`, the customer SMS's format | met, with "number" for "name", a recorded deviation | a suffix of the real number is not a token: a recorded deviation | An ADR 0069 amendment (PB-157). Either a non-secret suffix is stored at enrolment beside `account_tokens`, or each row is decrypted (a KMS call per row, which review 1 rejected) |
     | **(b)** Keep `masked_account_name`, nullable, always null | nothing | **not met**: one of the eight elements is missing | met | none |
     | **(c)** Show the account token | `tok_…` | not met in spirit: a token is no name | met literally | none; `AlertDetail` already carries `account_token` |

   - **Decided: (a), with the suffix stored at enrolment.** It shows the analyst what the
     customer sees, and it decrypts nothing at read time.
   - **What P1 builds meanwhile.** P1 serves null and builds no vault access. While the field is
     null, FR-04-03's "all 8 elements on every card" cannot be met, and its row says so.
   - **Format.** The field's format is the SMS's `^\*{3,}[0-9A-Za-z]{2,4}$`, narrowed by
     `maxLength` 7 to exactly three asterisks. A future lookup must emit that narrower form, and
     must validate what it emits.
4. **`anomaly_score` on every alert, nullable, and the anomaly queue's order** (item 9).
   - `anomaly_score` moves from `AlertDetail` to the shared base, so the feed card can show why an
     ANOMALY entry is in its queue.
   - It is the percentile rank D-06 defines, and **null** when the rule-based fallback decided,
     because no model ran (review 1, finding 4).
     Today the fallback stores a made-up 0 in `fraud_scores.anomaly_score`, which is NOT NULL.
     - **P1's fix:** P1 serves null when `ml_unavailable_fallback` is true.
     - **Same flaw elsewhere:** `ScoringResult.anomaly_score` has it too. It is filed as PB-155 and
       is not changed here.
   - **Ordering.** In `queue=anomaly`, `sort=priority` orders by `anomaly_score`, descending, nulls
     last. Every entry there has a fraud probability below its channel's MEDIUM threshold.
   - **`queue=all`'s `sort=priority`** is HIGH, then MEDIUM, then ANOMALY, each by probability.
5. **The behaviour profile's hours are each transaction's local time as the scorer computes it**
   (item 6). The scorer takes the country implied by the transaction's currency, and uses that
   country pack's UTC offset (`serving/features.py:115, 240`). Draft 1 claimed the institution's
   country, which the code contradicts (review 1, finding 5).
   - **A related skew, PB-154, which the owner directed to be fixed, not kept as a backlog item:** V67's feature-store fallback uses the
     institution's country. A KES transaction at a Rwandan institution is therefore scored at +3 h
     online, and at +2 h by the fallback.
   - **The window** is the 90 days before the compared transaction.
6. **The comparison attributes are a closed list** (item 7):
   - `local_hour`;
   - `amount_rwf`;
   - `channel`;
   - `counterparty_prior_transactions`;
   - `device_prior_transactions`;
   - `counterparty_country`.

   The list holds exactly six items (`minItems` and `maxItems` 6). Each value's format, its
   anomaly rule and its tie-break are in the schema's description. With fewer than 10 transactions
   in the window, nothing is marked anomalous. The plain-language names belong to PB-132's owner.
7. **Every list in the account context states its key** (item 8), as `x-unique-key`. A contract test
   checks that each key names required properties of its items. The keys are:
   - transactions: `transaction_id`;
   - histogram buckets: `lower_rwf`;
   - channels: `channel`;
   - comparisons: `attribute`;
   - nodes: `token`;
   - edges: (`source`, `target`).

   The network's description now defines:
   - the window, 90 days;
   - the selection: by degree, then by the number of transactions linking a node to the node that
     brought it in, ties by token;
   - the edges;
   - `confirmed_fraud`: the current label, following the supersedes chain, with ties by
     `label_id`.

   A contract test checks the examples against every declared key.

8. **Correction to the frozen contract (2026-10-20, P1's code review, finding 7; accepted by the owner
   the same day): the seven reads declare 400 and 422.** The endpoints already answered these
   statuses. The contract failed to declare them, so this records existing behaviour; no behaviour
   changed. In the owner's words, it "documents reality rather than changing it".
   - **The gap.** They validate a token, a cursor, a UUID path parameter and their filters, and a
     malformed value is answered with `ValidationProblem`. The contract declared neither status on
     the account and alert reads, and `listAlerts` declared only 400.
   - **The change.** Each of the seven now declares both. This adds declarations only; no success
     shape changes.

## Consequences

- **The console and P1's server ship in lockstep** (review 1, finding 10). The console's generated
  client requires `masked_account_name`, so it rejects a P1 response until it is regenerated. M8
  regenerates before its screens are run against P1. The console is not on `main`, so `main` breaks
  nothing in the meantime. What M8 changes:
  - `AlertCard`, `DecisionPanel`, `AlertDetailPanel` and `SummaryTab`;
  - eight fixtures;
  - five strings in four locales;
  - the generated zod and types files, and the mocks;
  - a `device_age_days` fixture that the closed attribute list now rejects;
  - the search view, which must pass `outcome` to find decided alerts;
  - `csrf.ts`'s comment.

  The masked account is always null until point 3 is decided, so the card must render its
  absence without showing "null".
- **Deferred to P2:** whether `DashboardStats.reviewed_today` counts anomaly reviews (item 9's
  second question), with the other figures.
- **Implementation notes for P1:**
  - Network lookups in either direction need an index on `transactions (institution_id,
    counterparty_token, transaction_timestamp)`: V82, M8B's range.
  - Ordering by `anomaly_score` joins `fraud_scores`.

- **What the contract tests check:**
  - the CSRF names;
  - the masked account's format, that it is nullable, and that it equals the SMS's;
  - the closed attribute list;
  - `queue=all`;
  - the unique keys.

  `test_openapi_examples` validates every changed example.
- **Item 11** (a daily series for My performance) belongs to P2, with the other figures.
