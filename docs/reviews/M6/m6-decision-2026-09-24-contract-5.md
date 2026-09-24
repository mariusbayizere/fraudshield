> Recorded by the author on 2026-09-24. Draft 5 of the ordering contract (at `3a95702`) was reviewed
> **sound**. The 4 MINOR and 4 NIT, all wording or lifecycle rules, were applied before
> implementation, as the reviewer asked, and are cited `[5-…]` in the contract.

# M6 review: the decision-fact ordering contract, draft 5

Reviewer: a fresh, independent reviewer. Read-only, against HEAD. The reviewer checked the Kafka
behaviour against `kafka-clients-4.2.1.jar` with javap:

- `pause` and `resume` on an unowned partition throw `IllegalStateException`;
- an eager rebalance rebuilds the partition state, so the pause flag is cleared.

## Verdict: sound (0 BLOCKER, 0 MAJOR, 4 MINOR, 4 NIT)

All of draft 4's findings are resolved (4-m2 in part, which is m1 below). No way was found to send
without B, send twice, or stall for ever. Adversarial sequences that held: idle after a trip, with
or without a change of owner; PostgreSQL flapping between S4 and S5; a grace re-read hitting S5; a
crash loop; a W2 stall; revoke while waiting; and `max.poll.interval.ms` with every partition
paused.

| # | Severity | Finding | Applied |
|---|---|---|---|
| m1 | MINOR | The S5 backoff still sleeps the whole thread, so "resumed after 500 ms whatever others do" was false, and so was W-c2's flat 500 ms. | Every re-read is now paused per partition, and the thread never sleeps. The grace interval is g = 500 ms plus the current batch. |
| m2 | MINOR | The lifecycle of a paused partition on revoke was unstated. `resume` on a revoked partition throws, and can skip fetched records or stop the poll loop. | Revoked and lost drop every per-partition entry. Calls are made only on `assignment()`. I7e. |
| m3 | MINOR | W-b and D-d/I10 contradicted each other on replayed records. | Their handling time is draining. |
| m4 | MINOR | I7 kept draft 4's "non-S4 time drains". | I7 now uses W-b's definitions. |
| n1–n4 | NIT | After a crash, `[at, crash]` counts twice. An ambiguity at the moment the budget fills. Mid-batch exceptions can skip fetched records. Where an attempt ends. | After a change of owner, time counts as draining from `at`, and every attempt is checkpointed. The filling record is dead-lettered with no further re-read. I7f: no record is skipped. The attempt ends when the statement returns; the send is draining. |
