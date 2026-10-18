> Recorded by the author on 2026-09-24. This is the review of draft 1 of the ordering contract
> (`docs/architecture/decision-fact-ordering.md` at `75a2509`), reviewed on its own before any
> implementation, as the owner required. Draft 2 answers each finding and cites it in brackets.

# M6 review: the decision-fact ordering contract, draft 1

Reviewer: a fresh, independent reviewer. Read-only, against HEAD.

## Verdict: not sound (4 MAJOR, 8 MINOR, 6 NIT)

**Verified true:**

- G2: `decision_states` sequence 1 and `auto_block_events` are written only by `PostgresSink`, and
  a record's rows commit together in both the batch path and the per-record fallback. A
  transaction can be auto-blocked only once: `UNIQUE (institution_id, transaction_id)`.
- Derived B and N are collision-free.
- Envelope `event_id` repeats for a derived N.
- The header is outside the frozen contracts, apart from ADR 0064's DLQ header list.
- Section 10 is correct, apart from the batch wording.

| # | Severity | Finding |
|---|---|---|
| M1 | MAJOR | S3 and S4 read separate snapshots, whether as separate statements or on separate connections. The consumer can see "B absent", then W2 commits, then it sees "decision_states(T,1) present", and it dead-letters a valid intent as permanent. |
| M2 | MAJOR | With derived ids, the discarded decision's intent can reach S2 first and be sent with its own `verification_link_allowed` (the SIM-swap control), which the kept decision may have refused. |
| M3 | MAJOR | A replay tool with one committed consumer group moves past every reason it did not replay. |
| M4 | MAJOR | The bound is per record, so consecutive orphans stall a partition for k × 10 min. Repeated replays re-stall it. |
| m1 | MINOR | A replayed S3 always lands in S3 again. Telling that customer is manual; say so, and alert. |
| m2 | MINOR | A lost parent needs a replay of W2's files first, and no tool exists for that. |
| m3 | MINOR | There is no rule for a block resolved before its SMS goes. |
| m4 | MINOR | "Already sent lands in S1" needs the outcome to be visible. It is not visible after a mid-send eviction, or after `rejected_by_the_database` following a send. |
| m5 | MINOR | The payload rationale was wrong: ADR 0012 point 5 allows an optional property. The real barrier is the freeze. |
| m6 | MINOR | ADR 0064 point 5 lists the DLQ headers, so it needs an amendment. |
| m7 | MINOR | W-b, W-d and W-e change the code but were not marked as changes. There is no rebalance listener. ADR 0057 must be superseded. |
| m8 | MINOR | I3's "never waits" holds only if the kept decision is already written. Test both orders. |
| n1–n6 | NIT | Take T from `AutoBlocked` in the same record, so the codec does not change. G1 holds per instance spool. The list of duplicate sources was incomplete. The reference code is not an authenticator. The S3 reason name was wrong. The batch wording was wrong, and `account_profiles` was omitted. |
