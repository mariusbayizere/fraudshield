# Decision facts: who writes what, in which order, and what readers may assume

Status: **draft 2 for review** (2026-09-24). This is the ordering contract the owner asked for
before the next SMS-race fix. Draft 1 (`75a2509`) was reviewed on its own and found not sound
(4 MAJOR, 8 MINOR, 6 NIT; `docs/reviews/M6/m6-decision-2026-09-24-contract-1.md`). This draft
answers each finding; the finding it answers is given in brackets. Code follows the contract only
after the contract is reviewed sound. Where the code today differs, the text says **(change)**.

## 1. Scope

This contract covers the facts one decision produces and every reader of those facts that depends
on another writer having finished. Today that means one Kafka reader, the customer SMS consumer.
The contract says why the other readers are unaffected, so that a new reader can be checked
against it.

## 2. Writers and what each one writes

| # | Writer | Writes | Unit of atomicity |
|---|---|---|---|
| W1 | `DecisionService.decide` | One spool record per decision, holding every fact of that decision: `TransactionDecided(T)`, plus, when they apply, `AlertRaised`, `AutoBlocked(B, T)`, `CustomerNotificationRequested(N, B)` and `AccountFrozen(B)`. Each instance has its own spool. | The spool record, fsynced (group commit) before the response is sent. |
| W2 | Spool → PostgreSQL drainer (`PostgresSink`) | The record's facts as rows: `transaction_ids`, `transactions`, `fraud_scores`, `decision_states(T, 1)`, `account_profiles`, `alert_queue_entries`, `auto_block_events(B)`, `customer_notifications(N, 'REQUESTED')`, `account_freeze_events` | One database transaction per spool record. In a batch, all of the batch's records commit together; when the batch is refused, it falls back to one transaction per record. A record's rows therefore become visible together or not at all. A duplicate decision's record is skipped as a whole. |
| W3 | Spool → Kafka drainer (`KafkaSink`) | The record's facts as envelopes. The SMS intent goes to `fs.notifications.customer`, keyed by `account_token`. | None across messages. The producer is idempotent but not transactional. Each message is published at least once. |
| W4 | SMS consumer (`EnvelopeConsumer` + `CustomerSmsSender`) | `customer_verifications(B)` when a link is allowed, the SMS through the provider, then `customer_notifications(N, 'SENT' / 'FAILED')` | Each write stands alone. The provider send cannot be undone. |

`decision_states` sequence 1 is written only by W2: V3 requires sequence 1 to be decided by
`MODEL`. `auto_block_events` is written only by W2. `auto_block_events` has
`UNIQUE (institution_id, transaction_id)`, so a transaction is auto-blocked at most once.

## 3. Ordering guarantees

**Guaranteed:**

- **G1.** For one instance's spool, W2 handles records in spool order. Once any row of record *r* is
  visible, every earlier record of that spool is visible, has been dead-lettered to W2's files, or
  has been skipped as a duplicate decision.
- **G2.** For one record, W2 makes `decision_states(T, 1)`, `auto_block_events(B)` and
  `customer_notifications(N, 'REQUESTED')` visible in the same transaction.
- **G3.** On one Kafka partition, W3's messages from one instance keep spool order. Both intents for
  an account share a key, so they share a partition.
- **G4.** Both W2 and W3 deliver at least once. A re-delivery carries the same ids, so it writes
  nothing new in PostgreSQL and repeats `event_id` on Kafka.

**Not guaranteed:**

- **N1. Nothing orders W2 against W3.** The drainers are independent by design (ADR 0064 point 2),
  so either one can lead the other by any amount: milliseconds normally, the length of an outage or
  write stall otherwise, or for ever if W2 dead-letters the record.
- **N2.** Nothing orders messages across topics, across partitions, or across instances.
- **N3.** Kafka does not see which decision PostgreSQL kept.

## 4. Absent, late and duplicated facts

| Case | How it arises | PostgreSQL | Kafka |
|---|---|---|---|
| **Late** | W3 ran ahead of W2: by milliseconds, or through an outage or write stall | the rows arrive later | the intent is already there |
| **Absent for ever, lost** | W2 refused the record for a non-transient reason and wrote it to its dead-letter files (ADR 0064 point 3) | never, unless an operator replays the file | the intent is there |
| **Absent for ever, duplicate** | T was decided twice, and W2 kept the first decision and skipped all of the second. Sources: the process died between the spool append and the response and the client retried; two submissions raced while Redis was down; a retry after "outcome unknown" while W2 lagged, so `JdbcIdempotency` saw no row; a claim left unverified because PostgreSQL failed during verification; Redis lost data (ADR 0067). | the kept decision's rows only | both decisions' messages, in either order (N1, N2) |
| **Duplicated delivery** | G4 | idempotent | the same `event_id` twice |

**(change) Ids derive from the transaction.**

- `B = nameUUID("auto-block|" + institution + "|" + T)`.
- `N = nameUUID("customer-notification|" + institution + "|" + T)`.
- Both are UUID v3, as the codebase already derives freeze and envelope ids.

Consequences:

- A duplicate decision that also auto-blocks yields the same B, N and envelope `event_id` as the
  kept decision. `UNIQUE (institution_id, transaction_id)` on `auto_block_events` makes a derived B
  collision-free, and N includes the institution because `customer_notifications` is unique
  globally.
- The reference code stays a one-way hash of B. It becomes computable from (institution, T), but it
  is **not an authenticator**: nothing accepts B or the code as proof of anything, and verification
  tokens are random and separate.

**(change) The intent carries its transaction as a Kafka header.** W3 adds `fs-transaction-id` to
the intent, taking T from the `AutoBlocked` fact in the **same spool record** as the intent, which
already carries T.

- Neither the event nor the spool codec changes.
- Spool records written before this change get the header too, once W3 drains them. Only intents
  already on the topic at deployment lack it.
- Headers are outside the frozen contracts: `topics.yaml`, the schemas and ADR 0012 define topic,
  key and payload only, and the code already adds an `event_id` header.
- ADR 0012 point 5 would allow an optional payload property. The payload is left alone because the
  owner froze the contracts (M6 brief).

## 5. What each reader may assume

| Reader | Reads | Depends on W2? | May assume |
|---|---|---|---|
| Webhook dispatcher | `fs.decisions.final` | no: it writes only `webhook_deliveries`, which references `institutions` | nothing about PostgreSQL; it deduplicates on `event_id` |
| Verification (HTTP) | persisted rows | `auto_block_events` joined to `customer_verifications` | the block exists: a verification is issued only in S2 |
| Unblock reconciler | persisted rows | its own queries | whatever PostgreSQL shows; it never reads Kafka |
| **SMS consumer** | `fs.notifications.customer` | yes | **nothing about PostgreSQL from the message**, and nothing from the intent's own `verification_link_allowed`, `template_key` or `locale` (section 5.2) |

### 5.1 One snapshot [MAJOR 1]

The consumer classifies an intent with **one SQL statement** under the tenant. Under READ COMMITTED
a single statement reads one snapshot, so G2's facts are seen together or not at all. The
statement returns:

- `block`: whether `auto_block_events(B)` exists;
- `requested_link_allowed`: `verification_link_allowed` from `customer_notifications(N, 'REQUESTED')`;
- `outcome`: SENT or FAILED from `customer_notifications(N)`, if either is present;
- `resolved`: whether `unblock_events(B)` exists, or `decision_states(T_B, s)` with `s > 1`, where
  `T_B` is the transaction of block B;
- `kept`: whether `decision_states(T, 1)` exists, for T from the header; null without the header.

Reading each fact in its own statement, or on its own connection (as `CustomerSmsSender` does
today), is not allowed. It could see `block` absent and then `kept` present, and dead-letter a
valid intent.

### 5.2 The kept decision's parameters [MAJOR 2]

With derived ids, the first intent for N to reach the partition while B exists may belong to the
decision PostgreSQL **discarded**. That can happen with two instances, or with one instance when W2
dead-lettered the first record. The intent's decision-dependent field is `verification_link_allowed`,
the SIM-swap control from `SelfServicePolicy`, and it may differ between the two decisions.

The consumer therefore takes link eligibility **only** from `requested_link_allowed`, the kept
decision's REQUESTED row. W2 writes that row in the same transaction as B (G2). If B exists without
its REQUESTED row, which G2 rules out, the link is **not** issued:
the control fails closed.

The template and locale are the same for both decisions: the template is a constant, and the
customer's locale comes from the vault at send time. Amount, local time, masked account and
reference code depend only on T and B, which both decisions share.

### 5.3 The decision table

Every intent carries B and N, and T when the header is present. Rows are checked in order, from one
snapshot.

| # | Snapshot shows | Meaning | Action |
|---|---|---|---|
| S1 | `block`, and `outcome` present | already handled: a re-read, or a duplicate decision with derived ids | commit past it, **no send** |
| S2r | `block`, no `outcome`, `resolved` | the block was lifted, or superseded by a later decision, before the SMS could go | commit past it, **no send**; log at INFO and count it [MINOR 3] |
| S2 | `block`, no `outcome`, not `resolved` | the normal case | issue a link only if `requested_link_allowed`; send; record the outcome; commit |
| S3 | no `block`; `kept` true | the kept decision for T has no block B, so B will never be written (G2) | **dead-letter at once**, reason `not_the_kept_decision`, no send |
| S4 | no `block`; `kept` false or null | late or lost: W2 has not written T's kept decision | **wait** (section 6); a replayed record does not wait (section 7) |
| S5 | the statement fails transiently | PostgreSQL is unavailable | retry on the exponential backoff; the wait is paused, not reset |

Why S3 is permanent: `kept` true means T's kept decision is visible. Had that decision auto-blocked,
its B would be derived from T and so equal this B, and it would be visible in the same snapshot (G2,
5.1). A kept decision from before this change with a random B had its own intent, which found its
own B and was sent. The later intent with a derived B lands in S3, which is correct, because the
customer was already told. The reason is named for what is certain: this intent is not the kept
decision's [NIT].

## 6. The bounded wait (S4)

- **W-a.** A waiting intent is re-read every 500 ms, not on the exponential backoff, so the
  consumer's other partitions keep flowing. When another partition's record is failing in the same
  poll, the retry backoff applies instead.
- **W-b. (change) The bound is per partition, not per record** [MAJOR 4]. Each partition has one
  wait clock, measuring the time the partition has spent in S4 **in a row**. The clock:
  - counts only intervals between two consecutive S4 answers on that partition, whether for the same
    record or for a following one;
  - excludes any interval that contains an S5 failure;
  - keeps running across consecutive S4 records;
  - resets to zero only when a record on the partition reaches S1, S2, S2r or S3, or leaves for any
    reason other than S4.
- **W-c. Bound: 10 minutes of counted time, ASSUMED.** W2 normally trails W3 by milliseconds, and
  10 minutes also covers a long backlog after an outage. Once the partition's clock reaches the
  bound, the S4 record at its head is dead-lettered as `parent_not_recorded`, and so is **every
  following S4 record on that partition, at once**, until a record on the partition reaches S1, S2,
  S2r or S3.
  - A partition therefore spends at most 10 counted minutes in S4 in a row, however many
    consecutive intents lack their parent. Examples: W2 systematically refusing a class of records,
    or pre-change intents without the header whose parents were dropped as duplicates.
  - Everything dead-lettered can be replayed (section 7).
- **W-d. (change)** A WARN is logged after the partition has spent 1 minute of **wall time** in S4
  in a row, and every minute after that. It names the partition, the offset and B. S5 failures do
  not reset it [review 13, MINOR 3].
- **W-e. (change)** When a partition is revoked or lost (a rebalance listener), its clock is
  dropped. A new owner starts from zero, which lengthens the wait, the safe direction, and still
  within the bound per owner. A failed DLQ send is an S5-like failure: the record is retried, and
  the clock is kept, not reset.
- **W-f.** Records behind a waiting intent on its partition wait with it: for at most 10 counted
  minutes, plus the time PostgreSQL was failing, plus 500 ms for each record dead-lettered at once
  after the bound.
- ADR 0057 (no deadline) is **superseded** by this contract.

## 7. Dead letters and replay

- **D-a. (change)** A dead-lettered copy keeps the original key, value and **headers**
  (`event_id`, `fs-transaction-id`), and adds `fs-dlq-reason`, `fs-dlq-error`, `fs-dlq-topic`,
  `fs-dlq-offset` and `fs-dlq-at`. ADR 0064 point 5, which lists the DLQ headers, is amended to match
  [MINOR 6].
- **D-b.** `<topic>.dlq` keeps records for 30 days (`topics.yaml`, `dlq_retention: P30D`). The source
  topic keeps them for 3 days.
- **D-c. (change) Replay tool: `DeadLetterReplay`**, in the notify module, with a `main` that
  operators run from the ingest image. It reads `<topic>.dlq` with **no committed consumer group**
  [MAJOR 3]. The operator gives:
  - the reasons to replay (default `parent_not_recorded`);
  - a time window (default: the whole retention);
  - optionally a single offset.

  The end is the DLQ's end offsets when the run starts. For each matching record, the tool
  republishes the key, value and original headers (without the `fs-dlq-*` headers) to the topic
  named in `fs-dlq-topic`, adding `fs-replayed: <n>`. It prints how many records it republished and
  how many it skipped, by reason. Nothing is committed, so records of other reasons stay where they
  are for a later run with those reasons.
- **D-d. A replayed record never waits** [MAJOR 4]. The consumer treats a record carrying
  `fs-replayed` in S4 as done waiting: it goes straight back to the DLQ as `parent_not_recorded`
  and does not start or extend the partition's clock. Replaying before the parents exist therefore
  cannot stall a partition. It only moves the records back to the DLQ.
  - Repeating a replay is safe while each earlier send's outcome is visible: an already-sent intent
    lands in S1. That excludes the window between a successful send and its recorded outcome
    [MINOR 4]: a consumer evicted mid-send, or a record dead-lettered as `rejected_by_the_database`
    after the provider accepted it.
  - The tool therefore replays `rejected_by_the_database` only when asked, and warns that such an
    SMS may already have been delivered.
- **D-e.** `not_the_kept_decision` (S3) lands in S3 again on any replay. Telling that customer is a
  **manual** decision [MINOR 1]. The client was answered by the discarded decision while PostgreSQL
  kept the other, which is an existing inconsistency of duplicate decisions (ADR 0067). The consumer
  logs each at ERROR with B and T, and the tool reports them. An alert on the DLQ topic's growth is
  carried to M9.
- **D-f. Order of recovery for a lost parent** [MINOR 2]:
  1. Replay W2's dead-letter files, so the parent is written.
  2. Then replay the Kafka DLQ.

  **There is no tool for step 1 yet** (ADR 0057 left it open). Until one exists, step 1 is a manual
  operation and "a wrong guess costs a replay" holds for **late** parents only. The W2 replay tool
  is carried to M9 with this prerequisite stated.
- **D-g.** Other reasons (`malformed_envelope`, `permanent_vault_failure`) are unchanged. They are
  replayed once their cause is fixed.

## 8. Invariants, each to be proved by a test

| # | Invariant |
|---|---|
| I1 | No SMS is sent, no verification is issued and no outcome is recorded while B is absent (S3, S4). |
| I2 | Once an intent's outcome is visible, the intent is not sent again (S1). |
| I3a | A transaction decided twice, first **not** blocking, then blocking, with the kept decision already written: the second intent is dead-lettered at once as `not_the_kept_decision`, never waits, and never sends. |
| I3b | The same, with the intent read **before** W2 writes the kept decision: S4, then S3 once it is written, then dead-lettered. It never sends. |
| I4 | A transaction decided twice, blocking both times: exactly one SMS. |
| I5 | Link eligibility comes from the kept decision: when the discarded decision allowed a link and the kept one did not, the SMS carries no link. It also fails closed without a REQUESTED row. |
| I6 | An intent that arrives before its record is written is sent once the record is written (S4, then S2). |
| I7 | The partition's bound: after 10 counted minutes in S4 in a row, the head intent and every following S4 intent are dead-lettered as `parent_not_recorded`; the next S2 resets the clock. |
| I8 | Time during which PostgreSQL fails (S5) does not count towards the bound, and does not reset it. |
| I9 | The classification is one statement. A test holds a PostgreSQL transaction that writes `decision_states(T, 1)` and B together and shows that no interleaving yields S3. |
| I10 | A dead-lettered intent keeps its headers. Replaying it republishes key, value and headers, plus `fs-replayed`. Replayed after its parent arrived, it is sent; replayed before, it returns to the DLQ at once without waiting. |
| I11 | A replay stops at the end offsets taken when it starts, commits nothing, and leaves other reasons for later runs. |
| I12 | The one-minute WARN fires under intermittent S5 failures. |
| I13 | A block that is resolved (unblocked, or decided again) before its SMS goes is not sent (S2r). |
| I14 | Revoking a partition drops its clock (W-e). |

## 9. Alternatives rejected

- **Transactional outbox:** W2 would publish the SMS intent from PostgreSQL after its commit. This
  removes N1 for this reader altogether and is the better long-term design. It moves the intent's
  publisher from W3 to a new PostgreSQL-driven publisher, which is outside what M6 can still carry.
  Recommended for a later milestone.
- **`transaction_id` in the payload:** allowed by ADR 0012 point 5 as an optional property, but the
  owner froze the contracts. The header carries the same fact [MINOR 5].
- **Several statements, or several connections, for the checks:** not one snapshot (5.1).
- **A per-record bound:** a partition could stall for k × the bound (W-b).
- **A replay tool with a committed consumer group:** it loses the reasons it did not replay (D-c).
- **An unbounded wait** (ADR 0057): rejected by the owner.
- **Deadlines without replay** (`3141763`, `098c5dc`): these lose the notice for good.

## 10. Separate from this contract

The idempotency claim in ingest (`ResilientIdempotency.claim` → `JdbcIdempotency.claim`) fails with
`IllegalStateException` when neither Redis nor PostgreSQL can answer, and the client gets a 500.
The claim comes before any decision, so the failure is retryable. **(change)** `IngestService`
answers 503 `service-unavailable` with `Retry-After`, which the ingest contract already lists. In a
batch, the same outcome is one of the transient results `BatchJobs` retries; if the item still
fails, the job ends FAILED, as for any other unavailable dependency [NIT].
