# Decision facts: who writes what, in which order, and what readers may assume

Status: **draft 3 for review** (2026-09-24). This is the ordering contract the owner asked for
before the next SMS-race fix. It has been reviewed on its own twice:

- Draft 1 (`75a2509`) was not sound: 4 MAJOR (`docs/reviews/M6/m6-decision-2026-09-24-contract-1.md`).
- Draft 2 (`cf79844`) was not sound: 2 MAJOR (`docs/reviews/M6/m6-decision-2026-09-24-contract-2.md`).

This draft answers each finding. Findings are cited in brackets: `[M1]` refers to draft 1's review,
`[2-M1]` to draft 2's. Code follows the contract only after the contract is reviewed sound. **Every
difference from today's code is listed in section 11** and marked **(change)** where it is
described.

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

**(change) Ids derive from the submission: the transaction and its request fingerprint** [2-M1].

- `B = nameUUID("auto-block|" + institution + "|" + T + "|" + hex(F))`.
- `N = nameUUID("customer-notification|" + institution + "|" + T + "|" + hex(F))`.
- F is the SHA-256 request fingerprint that `DecisionService.decide` already receives (FR-01-03).
- Both are UUID v3, as the codebase already derives freeze and envelope ids.

Consequences:

- A duplicate decision **of the same submission** (same T, same body) that also auto-blocks yields
  the same B, N and envelope `event_id` as the kept decision. Its payload is the same too: amount,
  time and account come from the body, which is identical.
- A duplicate of T **with a different body** gets a different B. That B is never written, because
  PostgreSQL keeps one decision per T. Its intent therefore lands in S3 (`kept` is true, `block` is
  false) and is never sent. Draft 2 derived B from T alone. Such an intent then found the kept B
  and could text another account a working unblock link [2-M1].
- `UNIQUE (institution_id, transaction_id)` on `auto_block_events` means at most one B per T is
  ever written. N includes the institution because `customer_notifications` is unique globally.
- The reference code stays a one-way hash of B. Anyone who knows (institution, T, F) could compute
  it, but it is **not an authenticator**: nothing accepts B or the code as proof of anything, and
  verification tokens are random and separate.
- The audit `AUTO_BLOCKED` event id and the staff `ACCOUNT_FROZEN` notification id derive from B, so
  they now repeat across duplicate decisions of one submission. No consumer of those topics exists
  in this repository. Their consumers are to deduplicate on `event_id`, as ADR 0012 says [2-n2].

**(change) The intent carries its transaction as a Kafka header.** W3 adds `fs-transaction-id` to
the intent, taking T from the `AutoBlocked` fact in the **same spool record** as the intent, which
already carries T.

- Neither the event nor the spool codec changes. `KafkaMessage` gains a headers field, which
  `KafkaMessages` fills and `KafkaSink` writes [2-n4].
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
| Audit and staff-notification consumers | `fs.audit.events`, `fs.notifications.staff` | not in this repository | they deduplicate on `event_id` (ADR 0012); derived ids repeat across duplicate decisions [2-n2] |

### 5.1 One snapshot [M1] (change)

The consumer classifies an intent with **one plain SQL statement**, run under the tenant. It must
not be a VOLATILE function that runs its own queries, because each of those would take a new
snapshot [2-n5]. Under READ COMMITTED a single statement reads one snapshot, so G2's facts are seen
together or not at all. The statement returns:

- `block`: whether `auto_block_events(B)` exists;
- `block_account`: that row's `account_token`. If it differs from the intent's `account_token`,
  the intent is classified as S3. With fingerprint-derived ids this cannot happen; it is a second
  guard against sending another account's block [2-M1].
- `requested_link_allowed`: `verification_link_allowed` from `customer_notifications(N, 'REQUESTED')`;
- `outcome`: SENT or FAILED from `customer_notifications(N)`, if either is present;
- `resolved`: whether `unblock_events(B)` exists, or whether the **latest** `decision_states` row
  of `T_B`, the transaction of block B, is not DECLINE [2-n1];
- `kept`: whether `decision_states(T, 1)` exists, for T from the header; null without the header.

Reading each fact in its own statement, or on its own connection (as `CustomerSmsSender` does
today), is not allowed. It could see `block` absent and then `kept` present, and dead-letter a
valid intent.

### 5.2 The kept decision's parameters [M2] (change)

With derived ids, the first intent for N to reach the partition while B exists may belong to the
decision PostgreSQL **discarded**. That can happen with two instances, or with one instance when W2
dead-lettered the first record. The intent's decision-dependent field is `verification_link_allowed`,
the SIM-swap control from `SelfServicePolicy`, and it may differ between the two decisions.

The consumer therefore takes link eligibility **only** from `requested_link_allowed`, the kept
decision's REQUESTED row. W2 writes that row in the same transaction as B (G2). If B exists without
its REQUESTED row, which G2 rules out, the link is **not** issued:
the control fails closed.

The template and locale are the same for both decisions: the template is a constant, and the
customer's locale comes from the vault at send time. Because B and N derive from F as well as T
(section 4), an intent that reaches S1 or S2 is always of **the same submission** as the kept
decision. Its amount, local time, account and reference code are therefore the kept decision's
[2-M1]. Link eligibility is the only field that can differ, because it depends on the scoring.

### 5.3 The decision table

Every intent carries B and N, and T when the header is present. Rows are checked in order, from one
snapshot.

| # | Snapshot shows | Meaning | Action |
|---|---|---|---|
| S0 | `block`, but `block_account` is not the intent's account | not this intent's block (cannot happen with derived ids) | as S3 |
| S1 | `block`, and `outcome` present | already handled: a re-read, or a duplicate decision with derived ids | commit past it, **no send** |
| S2r | `block`, no `outcome`, `resolved` | the block was lifted, or superseded by a later decision, before the SMS could go | commit past it, **no send**; log at INFO and count it [m3] (change) |
| S2 | `block`, no `outcome`, not `resolved` | the normal case | issue a link only if `requested_link_allowed`; send; record the outcome; commit |
| S3 | no `block`; `kept` true | the kept decision for T has no block B, so B will never be written (G2). This covers the kept decision not blocking, and a duplicate with a different body. | **dead-letter at once**, reason `not_the_kept_decision`, no send; log at ERROR with B and T (change) |
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
- **W-b. (change) The bound is per partition** [M4]. Each partition has one wait clock: the counted
  time the partition has spent in S4 **in a row**. The clock:
  - adds each interval between two consecutive S4 answers on that partition, whether for the same
    record or for a following one;
  - does not add an interval that contains an S5 failure, or a failed DLQ send;
  - resets to zero, and clears the trip (W-c), only when a record on the partition reaches S0, S1,
    S2, S2r or S3, or leaves for any reason other than S4 [2-m3].

  A replayed record in S4 never touches the clock (D-d).
- **W-c. (change) Bound: 10 minutes of counted time, ASSUMED, configurable** (so tests can shorten
  it) [2-n6]. When the partition's clock reaches the bound, the partition **trips**:
  - the S4 record at its head is dead-lettered as `parent_not_recorded`;
  - while the partition is tripped, each following S4 record gets **one grace re-read after 500 ms**,
    and is dead-lettered only if it is still S4 [2-m1]. An intent whose parent is milliseconds
    behind is therefore normally sent, not dead-lettered.
  - One that loses the race by more than 500 ms while the partition is tripped is dead-lettered, and
    recovered by a replay. This is the cost of the bound, and it is accepted.
  - A partition therefore spends at most 10 counted minutes in S4 in a row, plus 500 ms per record
    after the trip, however many consecutive intents lack their parent.
- **W-d. (change)** A WARN is logged after the partition has spent 1 minute of wall time in S4 in a
  row, and every minute after that. It names the partition, the offset and B. S5 failures do not
  reset it [review 13, MINOR 3].
- **W-e. (change) The clock survives a change of owner** [2-M2]. The group uses the eager range
  assignor, so every membership change revokes every partition. A clock kept only in memory would be
  reset by every restart or scaling event, and an orphan could then stall its partition for ever.
  So:
  - While a partition waits at head offset X, the consumer commits `OffsetAndMetadata(X, m)`, where
    `m = "fs-wait:v1 counted_ms=<n> tripped=<0|1>"`. It commits at most every 5 s, and synchronously
    when the partition is revoked.
  - A new owner reads the committed metadata when the partition is assigned. If it is for the
    committed offset, the clock resumes from it.
  - The first normal commit past X (offset X+1, no metadata) clears it.
  - A crash, or a lost partition that cannot be committed, loses at most the last 5 s of counted
    time. The bound therefore holds as long as each owner keeps the partition for more than 5 s.
  - Committing offset X with metadata moves nothing: X is already the position.
- **W-f.** Records behind a waiting intent on its partition wait with it: at most 10 counted
  minutes, plus the time PostgreSQL was failing, plus the time lost to crashes (at most 5 s each),
  plus 500 ms for each record re-read after the trip.
- ADR 0057 (no deadline) is **superseded** by this contract.

## 7. Dead letters and replay

- **D-a. (change)** A dead-lettered copy keeps the original key, value and **headers**
  (`event_id`, `fs-transaction-id`, `fs-replayed`), and adds `fs-dlq-reason`, `fs-dlq-error`,
  `fs-dlq-topic`, `fs-dlq-offset` and `fs-dlq-at`. This applies to **every** dead-letter reason.
  ADR 0064 point 5, which lists the DLQ headers, is amended to match [m6].
- **D-b.** `<topic>.dlq` keeps records for 30 days (`topics.yaml`, `dlq_retention: P30D`). The source
  topic keeps them for 3 days.
- **D-c. (change) Replay tool: `DeadLetterReplay`**, in the notify module, with a `main` that
  operators run from the ingest image. It reads `<topic>.dlq` with **no committed consumer group**
  [M3], from a time window the operator gives (default: the whole retention) to the DLQ's end
  offsets when the run starts. It takes the reasons to replay (default `parent_not_recorded`).
  - Within the run, it keeps **only the newest copy of each envelope `event_id`** among the matching
    records [2-m2]. Repeated runs therefore republish each intent once, instead of doubling the
    copies left by earlier bounces.
  - It republishes that copy's key, value and headers, without the `fs-dlq-*` headers, to the topic
    named in `fs-dlq-topic`, with `fs-replayed` set to the copy's value plus one (1 if absent)
    [2-n3].
  - It prints how many records it republished, and how many it skipped by reason. Nothing is
    committed, so other reasons stay available to later runs.
  - It refuses `rejected_by_the_database` unless the operator passes it explicitly, and then warns
    that each such SMS may already have been delivered.
- **D-c2. (change) When to replay.** Replay `parent_not_recorded` only after W2 has caught up:
  - the spool's `postgres` consumer lag, a new gauge `fraudshield_spool_lag_bytes{consumer}` taken
    from `DurableSpool.lagBytes`, is zero on every instance;
  - and, for a lost parent, after W2's dead-letter files have been replayed (D-f).

  A replay made earlier only moves the records back to the DLQ (D-d). It costs nothing but a run.
- **D-d. (change) A replayed record never waits** [M4]. The consumer treats a record carrying
  `fs-replayed` in S4 as done waiting: it goes straight back to the DLQ as `parent_not_recorded`,
  and it neither starts, extends nor resets the partition's clock. Replaying before the parents exist therefore
  cannot stall a partition. It only moves the records back to the DLQ.
  - Repeating a replay is safe while each earlier send's outcome is visible: an already-sent intent
    lands in S1. That excludes the window between a successful send and its recorded outcome
    [m4]: a consumer evicted mid-send, or a record dead-lettered as `rejected_by_the_database`
    after the provider accepted it.
  - The tool therefore replays `rejected_by_the_database` only when asked, and warns that such an
    SMS may already have been delivered.
- **D-e.** `not_the_kept_decision` (S3) lands in S3 again on any replay. Telling that customer is a
  **manual** decision [m1]. The client was answered by the discarded decision while PostgreSQL
  kept the other, which is an existing inconsistency of duplicate decisions (ADR 0067). The consumer
  logs each at ERROR with B and T, and the tool reports them. An alert on the DLQ topic's growth is
  carried to M9.
- **D-f. Order of recovery for a lost parent** [m2]:
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
| I4 | A submission decided twice, blocking both times: exactly one SMS. |
| I4b | T submitted twice **with different bodies** (another account), both blocking, the first kept: the other body's intent is S3 and never sends; the kept intent is sent to the kept account [2-M1]. |
| I5 | Link eligibility comes from the kept decision: when the discarded decision allowed a link and the kept one did not, the SMS carries no link. It also fails closed without a REQUESTED row. |
| I6 | An intent that arrives before its record is written is sent once the record is written (S4, then S2). |
| I7 | The partition's bound: after the configured counted time in S4 in a row, the head intent is dead-lettered as `parent_not_recorded`; a following S4 intent gets one grace re-read and is dead-lettered if still S4, or sent if its parent arrived. S0, S1, S2, S2r and S3 each reset the clock and the trip. |
| I8 | Time during which PostgreSQL fails (S5), and a failed DLQ send, neither count towards the bound nor reset it. |
| I9 | The classification is one statement. A test holds W2's transaction open, writing `decision_states(T, 1)` and B together, and sees S4 (never S3) until the commit, then S2. |
| I10 | A dead-lettered intent keeps its headers, for every reason. Replaying it republishes key, value and headers, plus `fs-replayed` incremented. Replayed after its parent arrived, it is sent; replayed before, it returns to the DLQ at once and neither starts, extends nor resets the clock. |
| I11 | A replay stops at the end offsets taken when it starts, commits nothing, republishes only the newest copy per `event_id`, leaves other reasons for later runs, and refuses `rejected_by_the_database` unless asked. |
| I12 | The one-minute WARN fires under intermittent S5 failures. |
| I13 | A block that is resolved (unblocked, or its latest decision no longer DECLINE) before its SMS goes is not sent (S2r). |
| I14 | The clock survives a change of owner: after a revocation, the new owner resumes from the committed metadata, and the bound is reached in counted time as if the partition had not moved [2-M2]. |
| I15 | A header-less intent (on the topic before deployment) is S4 with `kept` null, and is dead-lettered at the bound if its B never appears. |

## 9. Alternatives rejected

- **Transactional outbox:** W2 would publish the SMS intent from PostgreSQL after its commit. This
  removes N1 for this reader altogether and is the better long-term design. It moves the intent's
  publisher from W3 to a new PostgreSQL-driven publisher, which is outside what M6 can still carry.
  Recommended for a later milestone.
- **`transaction_id` in the payload:** allowed by ADR 0012 point 5 as an optional property, but the
  owner froze the contracts. The header carries the same fact [m5].
- **Several statements, or several connections, for the checks:** not one snapshot (5.1).
- **A per-record bound:** a partition could stall for k × the bound (W-b).
- **Ids from T alone:** they let a different-body duplicate reach S2 (section 4).
- **A clock kept only in memory:** every rebalance resets it (W-e).
- **The record timestamp as the clock:** after an outage it trips at once, while W2 is still
  catching up (review 11).
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

## 11. Changes to the code, in full [m7] [2-m4]

1. `DecisionService.addAutoBlock` and `CustomerSmsPolicy.compose` derive B and N from (institution,
   T, F) (section 4).
2. `KafkaMessage` gains headers. `KafkaMessages` sets `fs-transaction-id` on the SMS intent from the
   `AutoBlocked` fact in the same record, and `KafkaSink` writes the headers (section 4).
3. `EnvelopeConsumer.Handler` receives the record's headers: T and the replayed flag.
4. `CustomerSmsSender` classifies with one statement (5.1), takes link eligibility from the
   REQUESTED row (5.2), and implements S0 to S5 (5.3).
5. `EnvelopeConsumer` implements the per-partition clock, the trip with its grace re-read, the WARN,
   the offset-metadata checkpoint with a rebalance listener, and the new outcomes (section 6).
6. Dead-letters keep the original headers for every reason (D-a). ADR 0064 point 5 is amended.
7. New `DeadLetterReplay` (D-c).
8. New gauge `fraudshield_spool_lag_bytes{consumer}` (D-c2).
9. ADR 0057 is superseded. ADR 0056 records this contract's decisions.
10. `IngestService` answers 503 when the idempotency claim cannot be made (section 10).
