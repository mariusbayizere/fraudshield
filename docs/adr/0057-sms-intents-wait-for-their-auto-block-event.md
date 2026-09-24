# 0057: A customer SMS waits for its auto-block event, for at most 30 minutes of PostgreSQL answering

- **Status:** Accepted (author decision under the brief's ambiguity rule, 2026-09-24; for the owner
  to confirm)
- **Date:** 2026-09-24
- **Decided by:** the author, choosing the option safest for customers, as the M6 brief directs
  when a question is open
- **Requirements affected:** FR-03-04 (auto-block SMS), D-25, NFR-REL-01
- **ADRs referenced:** 0064 (durable spool; point 5, the reading end dead-letters), 0012
  (dead-letter topics), 0060 (M6 numbering counts down from 0059)

## Context

The decision service writes each fact once to its durable spool. Two drainers then carry it on,
independently: one to Kafka, every 2 ms, and one to PostgreSQL, every 5 ms (ADR 0064). A customer
SMS intent on `fs.notifications.customer` can therefore be read before the `auto_block_events` row
it refers to has been written.

The devcontainer run on `5c3f6b2` (2026-09-24) caught this happening:

- The sender issued the verification link and sent the SMS first.
- Its record of the send was then refused by the foreign key.
- The consumer classes a foreign-key refusal as permanent, so it dead-lettered the intent.
- The customer had an SMS with no record of it. Replaying the dead letter would have sent it again.

The first fix timed the wait from the record's Kafka timestamp. Review 11 showed why that fails.
After a PostgreSQL outage longer than the wait, every intent written during the outage is already
past the bound when PostgreSQL returns. It would be dead-lettered the first time its parent was
found missing, while the drainer was still writing its backlog. Nothing replays the dead-letter
topic, so those customers would never be told about their blocked payment.

## Decision

1. **Nothing is sent, issued or recorded before the auto-block event exists.**
   `CustomerSmsSender` checks for the row under the tenant. Until it exists, the sender throws
   `NotYetRecordedException`.
2. **The wait is timed only while PostgreSQL answers without the parent.**
   - The clock starts the first time PostgreSQL answers and the parent is missing.
   - It restarts after any transient failure, so time spent in an outage does not count.
   - The record's own timestamp plays no part, and a rebalance restarts the clock, which is the
     safe direction.
   - A waiting record is re-read every 500 ms, at a fixed pace rather than on the exponential
     backoff, so the consumer's other partitions keep flowing.
3. **The bound is 30 minutes, and it is ASSUMED.** After 30 minutes of PostgreSQL answering
   without the parent, the intent is dead-lettered with reason `parent_not_recorded`. That happens
   only if the parent will never arrive: `PostgresSink` dead-lettered its batch to a file.
   - 30 minutes is the time the drainer is given to write a post-outage backlog.
   - No measurement supports it over 10 or 60 minutes.
   - The cost of a longer bound is that the partition behind a lost parent stalls for longer.
4. **An intent whose outcome is already recorded is not sent again.** A re-read after a crash
   between the recorded outcome and the offset commit finds the SENT or FAILED row and returns it.
   A transient failure between a successful send and its record can still send twice, which the
   provider interface cannot prevent without an idempotency key. That existed before this ADR.

## Consequences

- `VerificationFlowTest`, `ParentWaitTest` and
  `EnvelopeConsumerTest.recordsWaitForTheirParentWhilePostgresAnswersAndOnlyThenAreDeadLettered`
  test this ADR. The last covers four cases:
  - an intent read before its parent is handled once the parent exists;
  - an intent written an hour earlier still waits;
  - an outage that outlasts the wait does not use it up;
  - a parent that never arrives is dead-lettered and the records behind it are handled.
- `parent_not_recorded` is a new value of the `fs-dlq-reason` header. The contracts define no set
  of reasons, and nothing reads the header.
- **Open for the owner:** a dead-letter replay tool, and a provider idempotency key for the
  send-then-record window.
