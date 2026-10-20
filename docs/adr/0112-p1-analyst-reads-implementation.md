# 0112: How P1 serves the analyst reads

- **Status:** Proposed (M8B, 2026-10-20), for code review with P1
- **Date:** 2026-10-20
- **Requirements affected:** FR-02-01, FR-04-02 to FR-04-09, NFR-SEC-03, D-04, D-45
- **ADRs referenced:** 0014, 0069, 0073, 0110

## Context

P1 serves the seven analyst reads of the frozen contract, as amended by ADR 0110:
- `listAlerts`, `getAlert` and `getAlertExplanation`;
- `getScoringResult`;
- `listAccountTransactions`, `getBehaviourProfile` and `getAccountNetwork`.

Some of what these operations return is not stored as the contract shapes it, and a few rules the
contract leaves open had to be fixed. This ADR records each choice so the code review can check
it.

## Decision

1. **A module of its own, `backend/analyst`.** It is wired by an auto-configuration after M7's
   staff identity, as `backend/admin` is, and assembled into the API application through
   `backend/ingest`'s dependency (ADR 0073).
   - Each handler names its contract operation and the matrix's roles.
     `AnalystAuthorisationTest` checks both, and the method and path, against the packaged contract.
   - Every read runs in the caller's tenant transaction, so row-level security hides other
     institutions' rows: they read as 404, never 403.
2. **The SHAP base value and final margin are kept** (migration V80). The scorer returns both, and
   nothing stored them.
   - **What changes in the decision path.** It carries both through the spool and `PostgresSink`
     into `fraud_scores.shap_base_value` and `final_margin`. A spool record from before the upgrade
     decodes them as null.
   - **When the explanation is a 404.** That happens when the score has no SHAP: below 0.60, the
     rule-based fallback, or a row scored before V80. The operation already declares 404.
3. **The summary template key is derived, not stored** (D-45). Its text belongs to PB-132's owner.
   The server picks the key deterministically:
   - `summary.rule_fallback` when no model ran;
   - `summary.anomaly` for an Isolation-Forest-only alert;
   - otherwise `summary.<feature>` of the strongest contribution, or `summary.model` when the score
     kept none.

   The parameters are the fraud probability, the top feature, and the anomaly score when there is
   one.
4. **A decision's state.** Only an analyst's decision (CONFIRM_FRAUD or MARK_LEGITIMATE) has a grace
   period, so only it reads the commit and undo tables. Every other kind is COMMITTED when written:
   AUTO_RELEASED, AUTO_BLOCKED, ESCALATE and SENIOR_OVERRIDE. The decision-commit contract §3.7
   would put the same rule in the view; P1 does not wait for that contract.
5. **`listAlerts`.**
   - **Queue membership and order** follow ADR 0110 point 2. Without `outcome`, a queue lists PENDING
     and IN_REVIEW entries only. "Entries the caller escalated" are those whose original carries an
     ESCALATE decision by the caller; none exist until P2.
   - **The amount filters** compare the amount in RWF, the unit expected loss is in. They are
     inclusive at both ends.
   - **The time filters** compare the transaction's time: `from` inclusive, `to` exclusive, as M7's
     audit search does.
   - **`analyst_id`** matches the entry's assigned analyst, or an analyst who decided it.
   - **`newest`** orders by when the alert was raised.
   - **Paging** is a keyset over the order's own keys and the alert id. A cursor names its order, so
     a cursor from one order or queue is refused (422) under another.
6. **The account's history** gives each transaction's latest score and an outcome from its latest
   decision state: APPROVE and TIMEOUT_RELEASE are APPROVED, DECLINE is BLOCKED, and HOLD (or none
   yet) is PENDING. An account with no transaction is a 404.
7. **The behavioural fingerprint and local time.**
   - **Where the offsets come from.** Local time comes from the country packs, packaged into the
     module from `dataset/generator/params/countries` and never copied. Each transaction's hour is in
     its currency's country, as the scorer computes it (ADR 0110 point 5).
   - **Unknown currencies.** A currency no pack declares, which the scorer cannot score either, uses
     the institution's country.
   - **Histogram buckets** are fixed RWF edges (0, 1,000, 5,000, 10,000, 50,000, and onwards).
     Only the buckets from the lowest non-empty one to the highest are sent.
   - **Percentiles** are nearest-rank.
8. **The network** is selected as the contract describes. Its window is the 90 days before the
   database's `now()`.
   - **`confirmed_fraud`** reads the latest label by availability, with ties to the greater label
     id. The supersedes chain of the decision-commit contract does not exist yet, so it is not
     followed.
   - **The counterparty direction gets an index** (V80), because the transactions table was indexed
     by account only.
9. **The scoring result of a fallback decision** carries all 44 features as null. That is how D-04
   writes a structurally missing feature, and no feature was computed. Its anomaly score is the
   stored 0, which PB-155 tracks.
10. **The masked account is null** until PB-157 (ADR 0110 point 3).
11. **NFR-SEC-03's inspector** (`AnalystInspectorTest`) calls every analyst endpoint on seeded data
    and walks each response:
    - account identifiers must be tokens;
    - the masked account must be the SMS's masked form or null;
    - no customer-PII field name may appear;
    - no value may look like an e-mail address, a phone number or an account number.

    It fails if an analyst endpoint exists without a case. Every response in the integration tests
    is also validated against the contract's own schema.

## Migration numbering

The migration guard refuses a version at or below the highest merged one, and P1 merges first. So
P1 takes **V80**, and the planned migrations move up:
- the decision-commit contract's from V80 to **V81**;
- the configuration contract's from V81 to **V82**.

Both contracts' texts still say V80 and V81. They are corrected when they are next edited, and
`M8B_updates.md` records the change now.

## Consequences

- Seven of the 48 operations are served, and the inspector covers them all.
- The M8 console must regenerate its client and ship in lockstep (ADR 0110).
- M8's rows move past IN_PROGRESS only when the console runs against these endpoints in CI (the
  mock rule).

## Deferred, with reasons (P1's code review, round 1)

- **V80's counterparty index** (finding 6).
  - **It locks writes while it builds.** A plain `CREATE INDEX` on `transactions` blocks ingest
    inserts while it runs. The migration runs in the release's cutover, with writers stopped, so
    nothing waits on it.
  - **It doesn't help compressed chunks.** Chunks older than 30 days are compressed (V10, segmented
    by account), so a network lookup by counterparty still scans the compressed part of the 90-day
    window. On a production-sized history that is slow.
  - Neither was measured on TimescaleDB 2.30. Both are filed as PB-159.
- **The decision test double's SHAP base value** (finding 8). `InMemoryPorts` gives every
  non-fallback score a base value and margin, with or without SHAP. So the sink's test checks that
  the columns are written, not the rule that they are null without SHAP. That rule lives in
  `ScoringContract`, and `GrpcScorerTest` checks it at both ends (SHAP present, and below 0.60).
  Kept as is.
