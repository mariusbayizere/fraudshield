> Recorded by the author on 2026-09-24. This is the review of draft 2 of the ordering contract (at
> `cf79844`), reviewed on its own. Draft 3 answers each finding.

# M6 review: the decision-fact ordering contract, draft 2

Reviewer: a fresh, independent reviewer. Read-only, against HEAD.

## Verdict: not sound (2 MAJOR, 5 MINOR, 6 NIT)

Resolved from draft 1: M1, M3, m1–m6, m8 and n1–n6. Partly resolved: M2 (the link only), M4 (not
across rebalances) and m7. Verified: one plain SQL statement run as `fs_app` can return all five
facts (these are plain tables with RLS, and READ COMMITTED gives one snapshot per statement); G2;
`AutoBlocked` carries T in the same record; `unblock_events` references B.

| # | Severity | Finding |
|---|---|---|
| M1 | MAJOR | With B derived from T alone, a duplicate submission of T with a **different body** (another account), decided twice while Redis is down, shares B and N with the kept decision. Its intent reaches S2 and SMSes the wrong customer a working link. That customer's WAS_ME answer lifts the right customer's block. |
| M2 | MAJOR | The eager RangeAssignor revokes every partition on each membership change, and W-e drops the clock on revoke. Restarts or scaling reset the bound for ever. |
| m1 | MINOR | After the trip, an intent a few ms late is dead-lettered at once. W-f contradicts W-c. State it, or give each record one grace re-read. Operators must wait for W2 to catch up before replaying. |
| m2 | MINOR | Repeated default replays double each unresolved orphan's DLQ copies. Deduplicate by `event_id` and define how `fs-replayed` increments. |
| m3 | MINOR | S2r's reset, and the failed DLQ send keeping the clock, have no invariant. |
| m4 | MINOR | Several changes are unmarked: the single statement, link eligibility, S2r, S3 and W-c dead-lettering, `fs-replayed`, the ERROR log, and the Handler signature. |
| m5 | MINOR | Missing invariants: a different-payload duplicate, the bound across rebalances, header-less intents, a replayed S4 not touching the clock, S1/S2r/S3 resets, the DLQ-send failure, `rejected_by_the_database` replay, headers kept for other reasons, and a configurable bound. |
| n1–n6 | NIT | S2r's "s > 1" is fragile: prefer "latest decision is not DECLINE" or `unblock_events`. Audit and staff ids now repeat too; say that no consumer exists. How `fs-replayed` survives a bounce. `KafkaMessage`, `KafkaMessages` and `KafkaSink` must change for headers. Require plain SQL, not a VOLATILE function. The bound must be configurable. |
