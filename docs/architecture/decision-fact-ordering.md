# Decision facts: who writes what, in which order, and what readers may assume

Status: **draft for review** (2026-09-24). This is the ordering contract the owner asked for before the
next SMS-race fix. Code follows it after it has been reviewed on its own. Where the code today
differs, the difference is marked **(change)**.

## 1. Scope

This contract covers the facts one decision produces and every reader of those facts that depends
on another writer having finished. Today that means one Kafka reader, the customer SMS consumer.
The contract says why the other readers are unaffected, so that a new reader can be checked
against it.

## 2. Writers and what each one writes

| # | Writer | Writes | Unit of atomicity |
|---|---|---|---|
| W1 | `DecisionService.decide` | **One spool record per decision**, holding every fact of that decision: `TransactionDecided(T)`, plus, when they apply, `AlertRaised`, `AutoBlocked(B)`, `CustomerNotificationRequested(N, B)` and `AccountFrozen(B)` | The spool record. It is fsynced (group commit) before the response is sent. |
| W2 | Spool → PostgreSQL drainer (`PostgresSink`) | The record's facts as rows: `transaction_ids`, `transactions`, `fraud_scores`, `decision_states` (sequence 1), `alert_queue_entries`, `auto_block_events(B)`, `customer_notifications(N, 'REQUESTED')`, `account_freeze_events` | **One database transaction per spool record.** In a batch, all of the batch's records commit together; when the batch is refused, it falls back to one transaction per record. A record's rows therefore become visible together or not at all. |
| W3 | Spool → Kafka drainer (`KafkaSink`) | The record's facts as envelopes on their topics. The SMS intent goes to `fs.notifications.customer`, keyed by `account_token`. | **None across messages.** The producer is idempotent but not transactional. Each message is published at least once. |
| W4 | SMS consumer (`EnvelopeConsumer` + `CustomerSmsSender`) | `customer_verifications(B)` when a link is allowed, the SMS through the provider, then `customer_notifications(N, 'SENT' / 'FAILED')` | Each write stands alone. The provider send cannot be undone. |

A decision is **durable** once W1 has fsynced its record. W2 and W3 only copy the record onward.

## 3. Ordering guarantees

**Guaranteed:**

- **G1.** W2 writes records in spool order. Once any row of record *r* is visible, every earlier
  record is visible or is in W2's dead-letter directory.
- **G2.** For one record, W2 makes `decision_states(T, 1)` and `auto_block_events(B)` visible in the
  same transaction.
- **G3.** On one Kafka partition, W3's messages keep spool order. Both intents for an account share
  a key, so they share a partition.
- **G4.** Both W2 and W3 deliver at least once. A re-delivery carries the same ids, so it writes
  nothing new in PostgreSQL and repeats `event_id` on Kafka.

**Not guaranteed:**

- **N1. Nothing orders W2 against W3.** The drainers are independent by design (ADR 0064 point 2),
  so either one can lead the other by any amount: milliseconds normally, the length of an outage
  otherwise, or for ever if W2 dead-letters the record.
- **N2.** Nothing orders messages across topics, or across partitions of one topic.
- **N3.** Kafka does not see which decision PostgreSQL kept.

## 4. Absent, late and duplicated facts

| Case | How it arises | PostgreSQL | Kafka |
|---|---|---|---|
| **Late** | W3 ran ahead of W2 (N1), whether by milliseconds or through a PostgreSQL outage or write stall | the record's rows arrive later | the intent is already there |
| **Absent for ever, lost** | W2 refused the record for a non-transient reason and wrote it to its dead-letter directory (ADR 0064 point 3) | never, unless an operator replays the file | the intent is there |
| **Absent for ever, duplicate** | T was decided twice: the process died between the spool append and the response and the client retried, or two submissions raced while Redis was down (ADR 0067). W2 keeps the **first** decision and skips every row of the second (`PostgresSink.transactionDecided`). | the first decision's rows only | **both** decisions' messages |
| **Duplicated delivery** | G4 | idempotent | the same `event_id` twice |

**(change) Ids derive from the transaction.** Today B and N are random, so a duplicate decision's
intent refers to a B that will never exist. From this change:

- `B = nameUUID("auto-block|" + institution + "|" + T)` (UUID v3, as the codebase already derives
  freeze and envelope ids);
- `N = nameUUID("customer-notification|" + institution + "|" + T)`.

The effect: a duplicate decision that also auto-blocks yields the **same** B, N and envelope
`event_id` as the first. Its intent is the same notification, and once the first has an outcome it
is not sent again. One case stays absent for ever: **the first decision did not auto-block but the
second did.** Section 5 names that case and handles it. The reference code stays a one-way hash of
B. Anyone who knows T can now compute B, but B grants nothing: verification tokens are random and
separate.

**(change) The intent carries its transaction.**

- `CustomerNotificationRequested` gains `transactionId`. The spool codec reads a record without it
  as unknown, so spool files written before this change still drain.
- W3 adds a Kafka header `fs-transaction-id` to the intent. Headers are outside the frozen contracts:
  `topics.yaml` and the JSON schemas define topic, key and payload only, and the code already adds
  `event_id` and `fs-dlq-*` headers. The payload is unchanged.
- **For the owner to confirm:** that a new header counts as no contract change.

## 5. What each reader may assume

| Reader | Reads | Depends on W2? | May assume |
|---|---|---|---|
| Webhook dispatcher | `fs.decisions.final` | no: it writes only `webhook_deliveries`, which references `institutions` | nothing about PostgreSQL; it deduplicates on `event_id` |
| Verification (HTTP) | persisted rows only | reads `auto_block_events` joined to `customer_verifications` | the block exists: a verification row is written only after W4 has seen B |
| Unblock reconciler | persisted rows only | yes, through its own queries | whatever PostgreSQL shows; it never reads Kafka |
| **SMS consumer** | `fs.notifications.customer` | **yes: B** | **nothing.** Before any side effect, it must establish which case of section 4 it is in. |

**The SMS consumer's decision table.** Every intent carries B and N, and T when the header is
present. Each row is checked under the tenant before any side effect.

| # | PostgreSQL shows | Meaning | Action |
|---|---|---|---|
| S1 | `auto_block_events(B)` exists, and `customer_notifications(N)` has SENT or FAILED | already handled (a re-read, or a duplicate decision with derived ids) | commit past it, **no send** |
| S2 | `auto_block_events(B)` exists, no outcome for N | the normal case | issue the link if allowed, send, record the outcome, commit |
| S3 | B absent; T known; `decision_states(T, 1)` exists | the kept decision has no block B, so B will never be written (G2). This is the duplicate decision whose first decision did not auto-block. | **dead-letter at once**, reason `no_auto_block_for_decision`, no send |
| S4 | B absent; T known and `decision_states(T, 1)` absent, or T unknown | late or lost: W2 has not written T's first decision | **wait** (section 6) |
| S5 | the query itself fails transiently | PostgreSQL is unavailable | retry with the exponential backoff; the wait is paused, not reset |

S1 and S2 need B to exist, so nothing is sent for a block PostgreSQL does not hold. S3 relies on G2:
if T's kept decision had auto-blocked, B, derived from T, would be visible in the same transaction as
`decision_states(T, 1)`.

A block from before this change had a random B. If T's kept decision is such a block, its intent
already found B (S1 or S2), and a second intent for T with a derived B lands in S3. That is correct:
the first intent already notified the customer.

## 6. The bounded wait (S4)

- **W-a.** The intent is re-read every 500 ms, at a fixed pace rather than the exponential backoff,
  so the consumer's other partitions keep flowing. When a record on another partition is failing in
  the same poll, the retry backoff applies instead.
- **W-b.** The wait counts **only time during which PostgreSQL answered without T's first decision**.
  - The interval between two consecutive S4 answers counts.
  - An interval that includes an S5 failure does not count.
  - An outage therefore neither uses up the wait nor restarts it.
- **W-c.** **Bound: 10 minutes of counted time, ASSUMED.** W2 normally trails W3 by milliseconds, and
  10 minutes also covers a long backlog after an outage. When the bound is reached, the intent is
  dead-lettered with reason `parent_not_recorded`. It is recoverable (section 7), so a wrong guess
  costs a replay, not a customer's notice.
- **W-d.** A WARN is logged after 1 minute of wall time in S4, and every minute after that. It names
  the partition, the offset and B. It does not reset on S5, so an intermittent outage cannot silence
  it (review 13, MINOR 3).
- **W-e.** When the partition is revoked, the wait for it is dropped. A new owner starts a fresh
  count, which lengthens the wait, the safe direction.
- **W-f.** The records behind a waiting intent on its partition wait with it, for at most the bound
  plus the outage time. They are all intents of the same accounts' hash bucket.

## 7. Dead letters and replay

- **D-a. (change)** A dead-lettered copy keeps the original key, value and **headers**
  (`event_id`, `fs-transaction-id`), and adds `fs-dlq-reason`, `fs-dlq-error`, `fs-dlq-topic`,
  `fs-dlq-offset` and `fs-dlq-at`. Today the headers are dropped.
- **D-b.** `<topic>.dlq` keeps records for 30 days (`topics.yaml`, `dlq_retention: P30D`). The source
  topic keeps them for 3 days.
- **D-c. (change) Replay tool:** `DeadLetterReplay`, in the notify module, with a `main` that
  operators run from the ingest image. For each named reason (default: `parent_not_recorded`), it
  reads `<topic>.dlq` from its own consumer group up to the end offsets taken when it starts. It
  republishes each record's key, value and original headers (without the `fs-dlq-*` headers) to the
  topic named in `fs-dlq-topic`. It commits its offsets only after each publish is acknowledged, and
  it reports how many records it replayed.
- **D-d.** A replay is safe to repeat:
  - a replayed intent re-enters the table in section 5;
  - one already sent lands in S1 and is not sent again;
  - one whose parent is still missing waits again, and lands in the DLQ again after the bound.
- **D-e.** `no_auto_block_for_decision` (S3) is replayed only on purpose. The client was answered by
  the second decision while PostgreSQL kept the first, which is an existing inconsistency of
  duplicate decisions (ADR 0067). Whether the customer is told is for a person to decide.
- **D-f.** Other reasons (`malformed_envelope`, `rejected_by_the_database`, `permanent_vault_failure`)
  are unchanged. They can be replayed once their cause is fixed.

## 8. Invariants, each to be proved by a test

| # | Invariant |
|---|---|
| I1 | No SMS is sent, no verification is issued and no outcome is recorded while B is absent (S3, S4). |
| I2 | An intent whose N already has SENT or FAILED is not sent again (S1). |
| I3 | A transaction decided twice, first **not** blocking and then blocking, produces an intent that is dead-lettered at once as `no_auto_block_for_decision`, never waits, and never sends. This is the ordering the owner named. |
| I4 | A transaction decided twice, blocking both times, sends exactly one SMS: derived ids plus S1. |
| I5 | An intent that arrives before its record is written is sent once the record is written (S4 → S2). |
| I6 | An intent whose parent does not arrive is dead-lettered as `parent_not_recorded` after the bound of **counted** time, and the records behind it are then handled. |
| I7 | Time during which PostgreSQL fails (S5) does not count towards the bound, and does not reset it. |
| I8 | A dead-lettered intent keeps its headers. Replaying it republishes key, value and headers to the source topic, where it is handled by the table (a replay after its parent arrived sends it). |
| I9 | A replay stops at the DLQ end offsets taken when it starts, so a record that lands in the DLQ again during the replay is not replayed in the same run. |
| I10 | The one-minute WARN fires under intermittent S5 failures. |
| I11 | Spool records written before the change, without `transaction_id`, still decode and still drain. |

## 9. Alternatives rejected

- **Transactional outbox:** W2 would publish the SMS intent from PostgreSQL after its commit. This
  removes N1 for this reader altogether and is the better long-term design. It moves the intent's
  publisher from W3 to a new PostgreSQL-driven publisher, which is outside what M6 can still carry.
  Recommended for a later milestone.
- **`transaction_id` in the payload:** the payload schema forbids additional properties, and the
  contracts are frozen. The header carries the same fact.
- **An unbounded wait** (ADR 0057 as of `b6f79a9`): the owner rejected it, since it trades a lost
  notice for a partition blocked for ever.
- **Deadlines without replay** (`3141763`, `098c5dc`): these lose the notice for good whenever the
  deadline is wrong.

## 10. Separate from this contract

The idempotency claim in ingest (`ResilientIdempotency.claim` → `JdbcIdempotency.claim`) fails with
`IllegalStateException` when both Redis and PostgreSQL are unavailable, and the client gets a 500.
The claim comes before any decision, so the failure is retryable. **(change)** `IngestService` will
answer 503 `service-unavailable` with `Retry-After`, which the ingest contract already lists. A batch
item gets the same outcome through the same path.
