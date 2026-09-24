# 0057: A customer SMS waits for its auto-block event, however long that takes

- **Status:** Superseded by ADR 0056 (2026-09-24). The owner confirmed "never silently drop a
  waiting SMS", but rejected the unbounded wait: "an unbounded wait that can block a partition
  forever is not 'safest for customers' either". The bounded wait with dead-letter and replay in
  `docs/architecture/decision-fact-ordering.md` replaces this record.
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

Two deadlines were tried, and each failed review:

- **Review 11:** timing the wait from the record's Kafka timestamp dead-lettered every intent written
  during a PostgreSQL outage longer than the deadline, as soon as PostgreSQL answered and before its
  drainer had written the backlog.
- **Review 12:** timing it only while PostgreSQL answers still lost them whenever PostgreSQL accepts
  reads but not the writer's inserts for longer than the deadline. Examples: a full disk, a lock, or
  the writer's own pool, credentials or network path failing.

Nothing replays the dead-letter topic, so each of these customers would never be told about their
blocked payment. The consumer cannot tell a writer that is late from a parent that will never
arrive, so any deadline trades a partition's delay for a customer's lost notice.

## Decision

1. **Nothing is sent, issued or recorded before the auto-block event exists.**
   `CustomerSmsSender` checks for the row under the tenant. Until it exists, the sender throws
   `NotYetRecordedException`.
2. **The intent waits until its auto-block event is there, and is never dead-lettered for waiting.**
   - It is re-read every 500 ms, at a fixed pace rather than on the exponential backoff, so the
     consumer's other partitions keep flowing. When another partition's record is failing in the
     same poll, the retry backoff applies instead.
   - Its age and the database's behaviour in the meantime play no part.
3. **A long wait is loud, and the operator resolves it.** After one minute, and every minute after
   that, the consumer logs a WARN naming the partition, the offset and the missing event. Every
   record behind the waiting one on that partition waits too, which is the price of losing no
   notice.
   - A writer that is late needs no action: the wait ends when the event is written.
   - A parent that will never arrive is one that `PostgresSink` refused for a non-transient reason
     and wrote to its dead-letter file (ADR 0064 point 3). That is already an incident: a fact is
     missing from PostgreSQL. Replaying that file writes the event, and the SMS follows.
   - As a last resort, an operator can move the consumer group's offset for that partition past the
     record (`kafka-consumer-groups --reset-offsets --to-offset <offset + 1>`). The record stays on
     the topic for its retention period, to be sent by hand.
   - A replay tool for the writer's dead-letter files is open for the owner.
4. **An intent whose outcome is already recorded is not sent again.** A re-read after a crash
   between the recorded outcome and the offset commit finds the SENT or FAILED row and returns it.
   A transient failure between a successful send and its record can still send twice, which the
   provider interface cannot prevent without an idempotency key. That existed before this ADR.

## Consequences

- Tests:
  - `VerificationFlowTest`: nothing is sent or issued before the event exists, and an intent
    whose outcome is recorded is not sent again.
  - `ParentWaitTest`: the WARN cadence.
  - `EnvelopeConsumerTest.recordsWaitForTheirParentForAsLongAsItTakesAndAreNeverDeadLetteredForIt`:
    a held-up writer, an hour-old intent and an intermittent outage are each handled in order,
    and nothing is dead-lettered.
- **Open for the owner:**
  - a replay tool for `PostgresSink`'s dead-letter files;
  - a provider idempotency key for the window between a send and its record;
  - an alert on the WARN, or a lag alert on `notification-service`.
