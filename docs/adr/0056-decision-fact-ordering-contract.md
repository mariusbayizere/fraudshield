# 0056: The decision-fact ordering contract governs every reader that depends on another writer

- **Status:** Accepted (owner decision, 2026-09-24: options 1 and 3 of `docs/parallel/M6_updates.md`,
  "SMS race: stopped for the owner"; the contract reviewed sound on its own before implementation)
- **Date:** 2026-09-24
- **Decided by:** the owner (options, bound, process); the author (the contract's mechanisms,
  reviewed five times)
- **Requirements affected:** FR-03-04, D-25, NFR-REL-01, FR-01-03 (the idempotency 503)
- **ADRs referenced:** 0057 (superseded), 0064 (point 5 amended), 0067, 0012

## Context

The decision service writes each decision once, to a durable spool. Two independent drainers carry
it to Kafka and to PostgreSQL. The customer SMS consumer reads Kafka but needs PostgreSQL's auto-block
row. Four fix rounds, each aimed at one observed failure, introduced a new MAJOR each (reviews 11 to 13). The
owner then required the ordering to be written down and reviewed on its own before any further fix.

## Decision

`docs/architecture/decision-fact-ordering.md` is the contract. It was reviewed sound in its fifth
draft (reviews `docs/reviews/M6/m6-decision-2026-09-24-contract-1.md` to `-contract-5.md`), and the
code implements it. In short:

1. **Ids derive from the submission.** The auto-block and notification ids are name-based UUIDs of
   (institution, transaction, request fingerprint). A duplicate decision of the same submission is
   then the same notification. A different body for the same transaction id can never reach the kept
   decision's block.
2. **The intent carries its transaction** as the Kafka header `fs-transaction-id`. The frozen
   payload is unchanged.
3. **The consumer classifies each intent from one snapshot**, in one SQL statement, before any side
   effect:
   - already handled: not sent;
   - resolved: not sent;
   - the normal case: sent, with link eligibility from the kept decision's REQUESTED row, failing
     closed;
   - not the kept decision's: dead-lettered at once;
   - parent not written yet: waits.
4. **Waiting is bounded per partition by a leaky budget.** The bound is 10 minutes and a full budget
   drains in 60, both ASSUMED and configurable. The budget survives rebalances in the committed
   offset's metadata. Re-reads pause only their partition. An intent the budget cannot cover is
   dead-lettered for replay.
5. **Dead letters keep their headers.** `DeadLetterReplay` republishes by reason and time window,
   with no committed group and one copy per event. A replayed intent never waits.
6. **Ingest answers 503**, not 500, when neither Redis nor PostgreSQL can answer the idempotency
   claim.

## Consequences

- Each invariant is mapped to the test that proves it in the contract's section 8. Two
  properties rest on review only: `seekBack` after a commit failure with membership kept, and a
  lost partition committing nothing. The first version of this ADR said every invariant was tested;
  the implementation review of `bd48557` showed it was not, and the tests were added.
- **Carried to M9:**
  - a replay tool for `PostgresSink`'s dead-letter files (the first step of recovering a lost
    parent);
  - packaging `DeadLetterReplay` into the deployable image;
  - alerts on the WARN and on the DLQ topics' growth;
  - dashboards on `fs_spool_lag`.
- **Recommended for a later milestone:** a transactional outbox, publishing the intent from
  PostgreSQL after its commit. It removes the race for this reader altogether.
