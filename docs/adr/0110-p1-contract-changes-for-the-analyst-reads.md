# 0110: P1's contract changes for the analyst reads

- **Status:** Proposed (M8B, 2026-10-20). Review 1 (`docs/reviews/M8B/p1-contract-review-1.md`)
  gave APPROVE_WITH_CHANGES with 3 MAJOR findings. This revision answers them; points 3 and 5 need
  the owner's decision.
- **Date:** 2026-10-20
- **Decided by:** the owner decided points 1 and 2's ownership (`docs/parallel/M8B_scope.md` §7).
  The author proposes the content; the owner decides point 3.
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
   - The full lifecycle is in `docs/architecture/analyst-decision-commit-contract.md` §6.
3. **`masked_account_name` becomes `masked_account`, null for now. This is a recorded deviation from
   FR-04-03, and the owner decides it.**
   - **Nothing can produce a masked holder name.** The vault holds the phone, the locale and the
     masked account number (ADR 0069 point 4). The ingest contract carries no holder name, and no
     table stores one.
   - **The SRS asks for a "masked account name"** (FR-04-03 and the feed columns), and NFR-SEC-03 for
     masked tokens only. This change shows the masked account number instead: at most its last four
     characters, in the customer SMS's own format (`^\*{3,}[0-9A-Za-z]{2,4}$`, `maxLength` 7). It is
     recorded here as a deviation from FR-04-03, and the owner decides whether to accept it.
   - **The vault boundary (review 1, finding 1).** ADR 0069 resolves a contact for one send and lets
     nothing that reads transactions read a phone. Filling this field from the contact would decrypt
     phone numbers for every feed row. So the field is **null** until the vault offers a masked-only
     lookup, an amendment to ADR 0069 for the owner. That lookup would derive the masked number
     from `vault.account_tokens`, which holds the number of every tokenised account, and would
     return nothing else. P1 serves null and builds no vault access.
   - **The alternative:** keep `masked_account_name`, make it nullable, and serve it as always null.
4. **`anomaly_score` on every alert, nullable, and the anomaly queue's order** (item 9).
   - `anomaly_score` moves from `AlertDetail` to the shared base, so the feed card can show why an
     ANOMALY entry is in its queue.
   - It is **null** when the rule-based fallback decided, because no model ran (review 1, finding 4).
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
   - **A related skew, filed as PB-154 for the owner:** V67's feature-store fallback uses the
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
  - the search view;
  - `csrf.ts`'s comment.
- **Deferred to P2:** whether `DashboardStats.reviewed_today` counts anomaly reviews (item 9's
  second question), with the other figures.
- **Implementation notes for P1:**
  - Network lookups in either direction need an index on `transactions (institution_id,
    counterparty_token, transaction_timestamp)`: V82, M8B's range.
  - Ordering by `anomaly_score` joins `fraud_scores`.

- **M8 must change its console.** It regenerates its client and makes four changes:
  - masked account;
  - `anomaly_score` on cards;
  - `queue=all` for search;
  - the comparison attribute list.

  It also adds its side of the CSRF test.
- **What the contract tests check:**
  - the CSRF names;
  - the masked-account pattern, and that no name is shown;
  - the closed attribute list;
  - `queue=all`;
  - the unique keys.

  `test_openapi_examples` validates every changed example.
- **Item 11** (a daily series for My performance) belongs to P2, with the other figures.
