# 0110: P1's contract changes for the analyst reads

- **Status:** Proposed (M8B, 2026-10-20). For independent review, then the owner's decision on point 3.
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
3. **`masked_account_name` becomes `masked_account`** (owner's decision needed).
   - **Nothing can produce a masked holder name.** The vault holds the phone, the locale and the
     masked account number (ADR 0069 point 4). The ingest contract carries no holder name, and no
     table stores one. A required field that cannot be produced would have to be invented.
   - **Proposal:**
     - **The value.** The field carries the masked account number from the vault, with the pattern
       `^\*{3,}[0-9A-Za-z]{2,4}$`. That is the pattern of the customer SMS's `masked_account`, so
       the analyst sees what the customer sees.
     - **NULL** when the vault has no contact for the account.
     - **The rename.** It is renamed so the name says what it holds.
   - **The alternative, keeping the name:** keep `masked_account_name` and its pattern, and make it
     nullable and always null. It would be honest, but the field would be useless.
   - **Effect on M8.** The console must regenerate its client and show the field as the account,
     not the holder.
4. **`anomaly_score` on every alert, and the anomaly queue's order** (item 9).
   - `anomaly_score` moves from `AlertDetail` to the shared base, so the feed card can show why an
     ANOMALY entry is in its queue.
   - In `queue=anomaly`, `sort=priority` orders by `anomaly_score`, descending. Every entry there
     has a fraud probability below 0.60 by construction.
5. **The behaviour profile's hours are the account's local time** (item 6). That is the UTC offset
   of the institution's country pack, the same local time the `local_hour` features use. An
   institution's accounts are domestic to it, as V67 already assumes.
6. **The comparison attributes are a closed list** (item 7):
   - `local_hour`;
   - `amount_rwf`;
   - `channel`;
   - `counterparty_prior_transactions`;
   - `device_prior_transactions`;
   - `counterparty_country`.

   Each value's format and its anomaly rule are in the schema's description. With fewer than 10
   transactions in the window, nothing is marked anomalous. The plain-language names belong to
   PB-132's owner.
7. **Every list in the account context states its key** (item 8), as `x-unique-key`. A contract test
   checks that each key names required properties of its items. The keys are:
   - transactions: `transaction_id`;
   - histogram buckets: `lower_rwf`;
   - channels: `channel`;
   - comparisons: `attribute`;
   - nodes: `token`;
   - edges: (`source`, `target`).

   The network's description now defines the window (90 days), the selection (degree, then
   transaction count, ties by token), the edges, and `confirmed_fraud`.

## Consequences

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
