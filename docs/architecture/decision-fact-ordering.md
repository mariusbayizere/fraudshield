# Decision facts: who writes what, in which order, and what readers may assume

Status: **reviewed sound** (2026-09-24): draft 5 was reviewed sound, 0 BLOCKER, 0 MAJOR
(`-contract-5.md`). Its 4 MINOR and 4 NIT, all wording or lifecycle rules, are applied below and
cited `[5-…]`.

**Changed after the sound review, during implementation**, and flagged by the implementation review
(`-implementation-1.md`, MINOR 1):

1. W-b: an attempt ends when the handler returns, so for a record that waited, its SMS send counts
   towards the end of its wait. The reviewed text had it as draining. The handler is one call, so
   that is where an attempt can be observed.
2. Section 11 item 4: a SENT row records the vault locale the SMS was rendered in, as before. Only
   a FAILED row written before the vault answered records the REQUESTED row's locale.

Neither change affects W-c2's bound.

**Amendment 1, draft 2 (2026-10-17): not yet reviewed.** It answers the second implementation
review (`-2026-10-17-implementation-2.md`: M1, M2, M3, m1, m2, m3, m4, m6, m8, with m5 and m7) on
the owner's scope of the same day. Draft 1 (`b7eefd5`) was reviewed on its own and was **not sound**:
3 MAJOR, 7 MINOR, 6 NIT (`-2026-10-17-amendment-1.md`, cited `[A1-…]`). Code follows only after
the amendment is reviewed sound. It changes:

- **Section 12 (new): failures of an attempt.** Every failure is in exactly one class (12.2). Only
  an outage of a shared dependency is retried without bound; a failure that may be the record's own
  is dead-lettered as `handler_failure` after a bound (12.3), with its state checkpointed across
  owners; only data and integrity errors are permanent [A1-M2]. Every field the handler reads from
  the record is validated before any side effect (12.1). Once the provider has been called for a
  record, the result is kept and checkpointed, so a re-read writes the outcome and **never calls the
  provider again**; a dead letter after a send gets a reason the replay tool refuses unless asked
  (12.4) [A1-M1]. Every external call in an attempt has a timeout, and an `Error` stops the
  consumer loudly (12.2, 12.3) [A1-m1]. A reset across a retention gap is never silent (12.5).
  Each of these is counted (12.6).
- **W-b, W-c and W-e** are amended: a failed attempt is F1 or F4, and the checkpoint carries the head
  record's retry state and kept provider result, in a `v3` metadata format given exactly [A1-M3].
- **5.1 and 5.3: a malformed `fs-transaction-id`** is told apart from an absent one (row S3m,
  dead-lettered as `malformed_header`).
- **Behaviour changes beyond the findings:** an amount `Money` refuses is now `malformed_envelope`
  rather than a FAILED row [A1-n2]; FAILED means "not known to be accepted by the provider"
  [A1-m6]; the SMS is rendered before the verification is issued, so a rendering failure no longer
  uses up the block's only link [A1-m6]; statements run under a `statement_timeout` below the
  socket timeout [A1-m7].
- **D-a, D-c, D-g, section 9 and section 11** are changed to match.
- **Section 8**: I7e, I8, I9 and I14 are restored to the wording reviewed sound in draft 5 (the
  implementation round had narrowed them without saying so, implementation-2 m5). Every row was
  checked against its named test (owner, 2026-10-17): where a test proves less than the row says,
  the row says **in part** and names the gap, instead of narrowing the text. I10 and the
  "(malformed)" row claimed more than their tests (M3, m4) and are restated. New invariants I17 to
  I27 cover section 12.
- **Section 13 (new): known gaps**: a crash between a provider's acceptance and the checkpoint of
  its result (m7, `PB-77`).

**Rule for every row of section 8** (owner, 2026-10-17): a row that names a test is a claim that the
test fails when the invariant is broken. Each review checks that the named test proves the row as
worded, not a narrower property.

This is the ordering contract the owner asked for
before the next SMS-race fix. It has been reviewed on its own four times:

- Draft 1 (`75a2509`) was not sound: 4 MAJOR (`docs/reviews/M6/m6-decision-2026-09-24-contract-1.md`).
- Draft 2 (`cf79844`) was not sound: 2 MAJOR (`-contract-2.md`).
- Draft 3 (`d8b897b`) was not sound: 1 MAJOR (`-contract-3.md`).
- Draft 4 (`bad4263`) was not sound: 1 MAJOR, a definition gap (`-contract-4.md`).

This draft answers each finding. Findings are cited in brackets: `[M1]` refers to draft 1's review,
`[2-M1]` to draft 2's, and so on to `[4-M1]`. Code follows the contract only after the contract is reviewed sound. **Every
difference from today's code is listed in section 11** and marked **(change)** where it is
described.

## 1. Scope

This contract covers the facts one decision produces and every reader of those facts that depends
on another writer having finished. Today that means one Kafka reader, the customer SMS consumer.
The contract says why the other readers are unaffected, so that a new reader can be checked
against it. Section 12 applies to every consumer built on `EnvelopeConsumer`, the webhook
dispatcher included, because a failure loop does not depend on the ordering.

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
  the same B, N and envelope `event_id` as the kept decision. Its amount, time, account and
  reference code are the same too, because they come from the body and from B.
- Two things can still differ: the envelope's `occurred_at`, which is the decision time, and
  `verification_link_allowed`. Neither is used. The link comes from the kept decision (5.2)
  [3-n2].
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
- `kept`: whether `decision_states(T, 1)` exists, for T from the header; null when the header is
  absent or malformed.

**(change, amendment 1) The header has three states** [impl-2 m1]: **absent**, **valid** or
**malformed**. Valid means exactly one `fs-transaction-id` header whose value is a UUID in canonical
form: 36 characters, five hexadecimal groups of 8, 4, 4, 4 and 12 separated by hyphens.
`UUID.fromString` alone is not a valid check, because it accepts short forms such as `1-2-3-4-5`
and maps them to another id. Anything else that is present, including an empty value or a repeated
header, is malformed. W3 always writes the canonical form (section 4), so a malformed header means
corruption, a foreign producer or a manual publish. The state is computed from every value of the
header on the record (`record.headers().headers("fs-transaction-id")`), before any map that keeps
one value per name, so a repeated or null-valued header is seen [A1-n5]. A malformed header is
logged at WARN with the partition and offset, and counted once per record (12.6). It never decides a
send: S0 to S2 depend on B alone (5.3).

Reading each fact in its own statement, or on its own connection (as `CustomerSmsSender` does
today), is not allowed. It could see `block` absent and then `kept` present, and dead-letter a
valid intent.

### 5.2 The kept decision's parameters [M2] (change)

With derived ids, the first intent for N to reach the partition while B exists may belong to the
decision PostgreSQL **discarded**. That can happen with two instances, or with one instance when W2
dead-lettered the first record. The intent's decision-dependent field is `verification_link_allowed`,
the SIM-swap control from `SelfServicePolicy`, and it may differ between the two decisions.

The consumer therefore takes link eligibility **only** from `requested_link_allowed`, the kept
decision's REQUESTED row. It also records that value, not the intent's, on the SENT or FAILED row
[3-n3]. W2 writes that row in the same transaction as B (G2). If B exists without
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
| S0 | `block`, but `block_account` is not the intent's account | not this intent's block (cannot happen with derived ids) | dead-letter at once, reason `not_the_kept_decision`, no send; log at ERROR [3-n5] |
| S1 | `block`, and `outcome` present | already handled: a re-read, or a duplicate decision with derived ids | commit past it, **no send** |
| S2r | `block`, no `outcome`, `resolved` | the block was lifted, or superseded by a later decision, before the SMS could go | commit past it, **no send**; log at INFO and count it [m3] (change) |
| S2 | `block`, no `outcome`, not `resolved` | the normal case | issue a link only if `requested_link_allowed`; send; record the outcome; commit |
| S3 | no `block`; `kept` true | the kept decision for T has no block B, so B will never be written (G2). This covers the kept decision not blocking, and a duplicate with a different body. | **dead-letter at once**, reason `not_the_kept_decision`, no send; log at ERROR with B and T (change) |
| S3m | no `block`; the header is **malformed** (5.1) | B is not visible, and which decision PostgreSQL kept cannot be read from the record (change, amendment 1) | **dead-letter at once**, reason `malformed_header` [A1-n6]; no send, no wait. A replay keeps the header (D-a): if B exists by then the intent is S2 and is sent, otherwise it returns to the DLQ at once (D-d). It is replayed under D-c2's condition, as `parent_not_recorded` is |
| S4 | no `block`; the header absent, or valid with `kept` false | late or lost: W2 has not written T's kept decision | **wait** (section 6); a replayed record does not wait (section 7) |
| S5 | the statement fails | the failure's class decides (12.2) [A1-n3] | an outage (F1) is retried on the backoff without bound; a failure that may be the record's own (F4) is retried up to the bound of 12.3; a data or integrity error (F3) is dead-lettered as `rejected_by_the_database`. While retried, the wait is paused, not reset |

Why S3m does not wait: waiting spends the partition's budget (W-b), which valid intents share. A
record that W3 could not have produced should not spend it. It is not dead-lettered before B is
checked, because a corrupt header must never refuse an SMS whose block is visible.

Why S3 is permanent: `kept` true means T's kept decision is visible. Had that decision auto-blocked,
its B would be derived from T and so equal this B, and it would be visible in the same snapshot (G2,
5.1). A kept decision from before this change with a random B had its own intent, which found its
own B and was sent. The later intent with a derived B lands in S3, which is correct, because the
customer was already told. The reason is named for what is certain: this intent is not the kept
decision's [NIT].

## 6. The bounded wait (S4)

- **W-a. (change) Pausing a partition, never the thread** [4-m2] [5-m1]. Every re-read is paused
  per partition: an S4 wait, a grace re-read, and every failure that section 12 retries (an
  outage, F1; a failure that may be transient, F4 until its bound). A provider call is never
  retried (12.4). The partition whose head record must be re-read is sought back to that record and
  paused with `KafkaConsumer.pause`. It is resumed when its own re-read is due:
  - 500 ms for S4 and grace re-reads;
  - its own exponential backoff, up to `MAX_BACKOFF_MS`, for F1 and F4 failures (section 12).

  The consumer thread never sleeps. It polls (100 ms), resumes the partitions that are due, and
  handles what it is given. A re-read therefore happens when it is due, plus at most the time the
  thread needs to finish the batch it is handling.
  - **Lifecycle** [5-m2]. `pause`, `resume` and `seek` are called only on partitions in
    `assignment()`. `onPartitionsRevoked` and `onPartitionsLost` drop every per-partition entry:
    the due time, the backoff, the WARN state and the in-memory `spent`. The client clears the pause
    on every eager rebalance, so a partition that comes back is unpaused and re-read at once, with
    `spent` from its metadata (W-e).
  - **No skipped records** [5-n3]. If any call between `poll` and the end-of-batch `commitSync`
    throws, the loop seeks every partition of the batch back to its first unhandled offset before
    polling again. A fetched record is never skipped without being handled or dead-lettered.
- **W-b. (change) The wait budget, a leaky bucket per partition** [M4] [3-M1] [4-M1] [4-m1]. Each
  partition has `spent`, the waiting it has used, between 0 and the **bound**. Neither a record's
  outcome nor a change of record resets it.

  Every moment of an owned partition's time is exactly one of three kinds. An **attempt** is one
  classification of the head record: the handler's call, plus the dead-letter send where one
  follows. It succeeds or fails: a failure is F1 or F4 (12.2), or a failed DLQ send [A1-m3]. It ends when the handler returns, or
  when the DLQ send is acknowledged. For S2 that includes the SMS send and the outcome write, so a
  record that waited counts its send as the end of its wait; a record that did not wait counts it as
  draining [5-n4]. (Implementation note: the handler is one call, so this is where the attempt can
  be observed.)
  - **Waiting.** From the end of a successful S4 answer for record X to the end of the next
    successful attempt for X, whatever that attempt answers (S4, S2, S3 and so on), except any
    neutral time inside it. The final interval of a wait therefore counts [4-m2]. Waiting adds to
    `spent`, up to the bound.
  - **Neutral.** From the start of a failed attempt to the end of the next successful attempt for
    the same record. This is outage time. It neither adds nor drains, and so neither uses up the
    budget nor refills it [4-m1].
  - **Draining.** All other time: the partition idle with nothing to read, and handling records that
    do not wait. A replayed record returned to the DLQ at once is one of these [5-m3]. Draining
    lowers `spent` at the **drain rate**.

  After a change of owner, all time from the `at` of the last checkpoint to the assignment is
  counted as draining. That includes, after a crash, time that was waiting or neutral on the old
  owner. W-c2 still holds, because it charges the drain rate over the whole window [5-n1].

  Parameters, both ASSUMED and configurable so tests can shorten them [2-n6]: a **bound of 10
  minutes**, and a **drain rate of one sixth**, so a full budget empties in 60 minutes.
- **W-c. (change) Trip.** A partition is **tripped** while `spent` equals the bound.
  - The record whose waiting fills the budget is dead-lettered as `parent_not_recorded`, at its next
    successful S4 answer, with no further re-read [5-n2].
  - A record whose **first** answer, given while the partition is tripped, is S4 gets **one grace
    re-read, 500 ms later**, with the partition paused (W-a). It is dead-lettered only if the
    re-read is still S4 [2-m1].
  - "Tripped" is usually momentary. Draining starts as soon as the tripping record is handled, so
    in practice the next orphan is governed by the last bullet.
  - If the grace re-read fails (F1 or F4, 12.2), the time is neutral, and the re-read is repeated on
    the backoff until it succeeds, or, for F4, until 12.3's bound [A1-m3].
  - Once draining has taken `spent` below the bound, the next orphan waits only for what has
    drained, then is dead-lettered.
- **W-c2. What the budget guarantees** [3-M1] [4-m2]. The time a partition's records spend waiting
  equals the waiting time of W-b. `spent` counts that time, except while the partition is tripped,
  when each grace re-read adds up to 500 ms that `spent`, already at the bound, cannot count.
  - So, in any window of length t, a partition spends at most **bound + drain rate × t + g ×
    (grace re-reads)** waiting, plus neutral time, which is PostgreSQL's outage and not the wait.
    Here g, one grace interval, is 500 ms plus at most the time the thread needs to finish its
    current batch (W-a) [5-m1].
  - That holds however orphans and valid intents are mixed.
  - A late parent after a quiet period gets up to the full bound. A steady stream of lost parents
    takes at most about one seventh of that partition's time.
  - Because a waiting or failing partition is paused rather than the thread (W-a), **other
    partitions are not slowed**. While a partition is tripped and every intent on it is S4, as in a
    W2 write stall, that partition handles up to 2 intents a second, less while other partitions'
    batches are slow. The lag this builds on that partition is
    bounded by the stall, and clears when it ends.
  - Everything dead-lettered can be replayed (section 7). An intent that loses the race by more than
    500 ms while its partition is tripped is dead-lettered and recovered by a replay. That is the
    cost of the bound, and it is accepted.
- **W-d. (change)** A WARN is logged once a partition has had a record waiting (in S4, or in neutral
  time following S4) for 1 minute of wall time **in a row**, counted across records and across
  trips, and every minute after that. It names the partition, the offset and B. S5 does not reset
  it [review 13, MINOR 3]. Each trip is logged at WARN as well [4-n4].
- **W-e. (change) The budget survives a change of owner** [2-M2] [3-m1] [3-m2] [3-m3] [4-m3].
  - **Every commit** for a partition whose `spent` is above zero, **or whose head record holds
    retry state or a kept provider result (12.3, 12.4)**, carries the metadata m of W-e2 below.
    That includes the commit of X+1 after a dead-letter, and commits while the partition is tripped
    [A1-M3].
  - While a partition waits at head X, the consumer also checkpoints `OffsetAndMetadata(X, m)` at the
    first S4 answer and after every attempt, failed ones included, so that `at` stays fresh during
    an outage [5-n1]. **(amendment 1)** It also checkpoints `OffsetAndMetadata(X, m)` after every
    failed attempt (F1 or F4) of a head that is not waiting, and as soon as a provider result is
    kept for X (12.4), before the next re-read [A1-M3]. Where `done` already holds the same partition, the two
    are merged into one entry in the same `commitSync` [4 task 3]. Committing X moves nothing,
    because X is already the position.
  - `onPartitionsRevoked` commits `OffsetAndMetadata(position(p), m)` synchronously for every
    revoked partition whose `spent` is above zero or whose head holds retry state or a kept
    provider result. It uses `position(p)`, which is right even if an
    earlier `commitSync` failed [4-n3].
  - `onPartitionsLost` is overridden: it commits nothing and drops the partitions' state.
  - `onPartitionsAssigned` reads the committed metadata. If its `offset` equals the committed
    offset, `spent` resumes from it, drained for `max(0, now − at)`, and the head's retry state and
    kept provider result resume as recorded. A future `at` (clock skew) is clamped to zero and
    logged at WARN.
  - Otherwise, including when there is no metadata, `spent` starts at zero and the head has no
    retry state and no kept result. "No metadata" is concluded only from a successful
    `committed()` call that returns none; a failed call is retried, never read as "none" [A1-m2].
  - Instances' clocks are assumed synchronised (NTP). With skew s, a takeover drains up to s × drain
    rate too much or too little, which is negligible at NTP skew.
  - A crash loses at most the waiting since the last checkpoint: one re-read interval, 500 ms plus
    at most the thread's current batch (W-a). The time until the
    new owner is assigned (up to the session timeout, 45 s by default) is draining, as W-b defines,
    so it lowers `spent` by at most 7.5 s. The bound still holds, because draining is part of the
    guarantee.
  - A commit made once `spent` has drained to zero, and whose head holds no retry state and no kept
    result, carries no metadata.
- **W-e2. (change, amendment 1) The metadata format** [A1-M3]. Exactly one line, fields in this
  order, separated by single spaces:

  `fs-wait:v3 offset=<o> spent_ms=<n> at=<epoch ms> f4_count=<n> f4_ms=<n> result=<r>`

  - `o`, `n` and the epoch are decimal integers without sign or leading zeros (0 is `0`).
  - `r` is `none`; `FAILED:<locale>:<link>`; or `SENT:<locale>:<link>:<reference>`. `<locale>` is
    the BCP 47 tag the SMS was rendered in, `<link>` is `1` or `0` for whether it carried a link,
    and `<reference>` is the provider's message id, base64url-encoded without padding.
  - The whole line is at most 1,024 bytes, below Kafka's default `offset.metadata.max.bytes`
    (4,096). A provider reference that would exceed it is truncated and marked `~` at its end, and
    the outcome row records the truncated reference.
  - A reader accepts `v2` (as before, with no retry state and no kept result) and `v3`. Anything
    else, or a line that does not parse, is read as no metadata and logged at WARN.
  - **Rollout.** No release of these consumers has been deployed, so no group mixes a `v2`-only
    reader with a `v3` writer. From this version on, a new format is written only by a release
    whose predecessor already reads it (two-step rollout).
- **W-f.** Records behind a waiting intent on its partition wait with it, within W-c2's limit, plus
  neutral time. Other partitions are not held up (W-a).
- ADR 0057 (no deadline) is **superseded** by this contract.

## 7. Dead letters and replay

- **D-a. (change)** A dead-lettered copy keeps the original key, value and **headers**
  (`event_id`, `fs-transaction-id`, `fs-replayed`), and adds `fs-dlq-reason`, `fs-dlq-error`,
  `fs-dlq-topic`, `fs-dlq-offset` and `fs-dlq-at`. This applies to **every** dead-letter reason:
  `not_the_kept_decision`, `parent_not_recorded`, `malformed_envelope`, `malformed_header`,
  `rejected_by_the_database`, `permanent_vault_failure`, `handler_failure` and `outcome_unrecorded`,
  in both consumers.
  ADR 0064 point 5, which lists the DLQ headers, is amended to match [m6].
- **D-b.** `<topic>.dlq` keeps records for 30 days (`topics.yaml`, `dlq_retention: P30D`). The source
  topic keeps them for 3 days.
- **D-c. (change) Replay tool: `DeadLetterReplay`**, in the notify module, with a `main`. Until the
  deployable image exists (M9), it runs from a build:
  `./mvnw -q -pl notify -am -DskipTests package dependency:copy-dependencies -DincludeScope=runtime`,
  then
  `java -cp 'notify/target/classes:notify/target/dependency/*'
  io.github.mariusbayizere.fraudshield.notify.kafka.DeadLetterReplay --bootstrap-servers <servers>
  --topic fs.notifications.customer [--reason <r>]... [--from <instant>] [--allow-rejected]`.
  `-am` takes the sibling modules from the reactor, not from a shared `~/.m2`. Packaging it into
  the image is carried to M9. It reads `<topic>.dlq` with **no committed consumer group**
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
  - It refuses `rejected_by_the_database` and `outcome_unrecorded` (12.4) unless the operator
    passes them explicitly, and then warns that each such SMS may already have been delivered
    [A1-M1].
- **D-c2. (change) When to replay.** Replay `parent_not_recorded` only after W2 has caught up:
  - the spool's `postgres` consumer lag, a new gauge `fs_spool_lag{consumer}` (base unit bytes,
    alongside `fs_spool_depth`) taken from `DurableSpool.lagBytes`, is zero on every running
    instance, **and** the spool volume of every stopped instance has been drained by starting it
    [3-n4];
  - and, for a lost parent, after W2's dead-letter files have been replayed (D-f).

  A replay made earlier only moves the records back to the DLQ (D-d). It costs nothing but a run.
- **D-d. (change) A replayed record never waits** [M4]. The consumer treats a record carrying
  `fs-replayed` in S4 as done waiting: it goes straight back to the DLQ as `parent_not_recorded`,
  and its handling time drains the budget like any record that does not wait (W-b) [5-m3]. Replaying before the parents exist therefore
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
- **D-g. (change, amendment 1)** `malformed_envelope`, `permanent_vault_failure` and
  `handler_failure` (12.3) are replayed once their cause is fixed, with `--reason`; `malformed_header`
  under D-c2's condition (S3m). A replayed `handler_failure` whose cause persists returns to the DLQ
  after the bound of 12.3 again; it never loops without bound. `outcome_unrecorded` was sent or may
  have been: replaying it sends again unless its outcome has since been recorded, so D-c refuses it
  by default. The default reason of a replay stays `parent_not_recorded`.

## 8. Invariants, and the test that proves each

A test named here fails if the invariant is broken. Where a property is proved only in part, or
only by review, the table says so: the implementation review of `bd48557` found that an earlier
claim, "every invariant is tested", was not true.

| # | Invariant | Proved by |
|---|---|---|
| I1 | No SMS is sent, no verification is issued and no outcome is recorded while B is absent (S3, S4). | `SmsOrderingTest`: `nothingDoneFor` in the S3, S4 and I4b tests |
| I2 | Once an intent's outcome is visible, the intent is not sent again (S1). | `SmsOrderingTest.theSameSubmissionBlockingTwiceSendsOneSms`; `VerificationFlowTest.anIntentReReadAfterItsOutcomeWasRecordedIsNotSentAgain` |
| I3a | A transaction decided twice, first **not** blocking, then blocking, with the kept decision already written: the second intent is dead-lettered at once as `not_the_kept_decision`, never waits, and never sends. | `SmsOrderingTest.keptDecisionWithoutBlockThenBlockIsDeadLetteredAtOnceAndNeverSent` (sender); `EnvelopeConsumerTest.poison…` (the consumer dead-letters it at once). **In part:** the consumer half uses a handler that throws, so the header's path through `Envelope` and the wiring is not shown (implementation-2, m2); I21's test, through the real wiring, closes it in this round |
| I3b | The same, with the intent read **before** W2 writes the kept decision: S4, then S3 once it is written, then dead-lettered. It never sends. | `SmsOrderingTest.keptDecisionWithoutBlockReadBeforeItIsWrittenWaitsThenIsDeadLettered` |
| I4 | A submission decided twice, blocking both times: exactly one SMS. | `SmsOrderingTest.theSameSubmissionBlockingTwiceSendsOneSms`, `…IdsDerivedFromTheSubmission` |
| I4b | T submitted twice **with different bodies** (another account), both blocking, the first kept: the other body's intent is S3 and never sends; the kept intent is sent to the kept account [2-M1]. | `SmsOrderingTest.differentBodyForTheSameTransactionNeverReachesTheKeptAccount` |
| I5 | Link eligibility comes from the kept decision: when the discarded decision allowed a link and the kept one did not, the SMS carries no link. It also fails closed without a REQUESTED row. | `SmsOrderingTest.theLinkIsTheKeptDecisionsAndFailsClosedWithoutItsRequestedRow` |
| I6 | An intent that arrives before its record is written is sent once the record is written (S4, then S2). | `EnvelopeConsumerTest.intentsWaitWithinTheirPartitionsBudget…` (record 1); `SmsOrderingTest.intentReadWhileItsDecisionIsBeingWritten…` |
| I7 | The partition's budget: once waiting has filled `spent` to the configured bound, that record is dead-lettered as `parent_not_recorded`. A record whose first answer while tripped is S4 gets one grace re-read, and is dead-lettered if still S4, or sent if its parent arrived. Draining time as defined in W-b drains `spent` at the configured rate, neutral time does not, and no outcome resets it [5-m4]. | `WaitBudgetTest` (fill, drain, no reset); `EnvelopeConsumerTest.intentsWaitWithinTheirPartitionsBudget…` (end to end); `…recordsArrivingAfterTrippingGetOneGraceReRead…` (the grace re-read: sent if the parent arrived, otherwise dead-lettered after one re-read, not a fresh budget). The consumer's `!first` guard is equivalent in practice to its absence: any draining since the trip leaves the budget just below full at a record's first answer. |
| I7b | Orphans interleaved with valid intents keep the partition within W-c2's limit [3-M1]. | `WaitBudgetTest.orphansBetweenValidIntentsDoNotEachGetTheWholeBound` |
| I7c | An orphan after an idle period following a trip waits for what the idle time drained [4-M1]. | `WaitBudgetTest.idleTimeAfterTrippingDrains…` |
| I7d | While one partition waits, is tripped, or backs off after S5 or a RETRY, another partition's intents are handled without delay [5-m1]. | `EnvelopeConsumerTest.otherPartitionsFlowWhileOneWaits…AndAnotherBacksOff` |
| I7e | A partition paused on one member and moved to another: the first member's other partitions keep flowing and no `IllegalStateException` escapes; the second member re-reads at once, with `spent` resumed from the metadata [5-m2]. (Restored to the draft-5 wording, amendment 1.) | `EnvelopeConsumerTest.partitionsMovedWhilePausedKeepEveryoneFlowing` shows the members flowing and the orphan re-read after the join. **In part until this round's change:** it does not show that the paused partition moved to the second member (implementation-2, m3). This round asserts which member handles the partition after the join, or forces the move (`PB-78`, resolved in this round). That no `IllegalStateException` escapes rests on the same test completing; the resumed `spent`: I14 |
| I7f | An exception between `poll` and the end-of-batch commit skips no fetched record [5-n3]. | **In part.** `EnvelopeConsumerTest.batchesThatCannotBeCommittedSkipNoRecord` covers a member expelled mid-batch, where the rejoin resets positions from the committed offsets. A commit failure while membership is kept, the case `seekBack` is for, is not produced by a test; `seekBack` is verified by review (implementation review, MINOR 5). |
| I8 | Neutral time, from a failed attempt (S5, or a failed DLQ send) to the next successful answer, neither adds to nor drains `spent`. A grace re-read that meets S5 is retried until it succeeds [4-m1] [4-n4]. (Restored to the draft-5 wording, amendment 1; with section 12, "until it succeeds" holds for F1, and an F4 failure is bounded by 12.3.) | `WaitBudgetTest.neutralTimeNeitherFillsNorDrainsTheBudget`; `EnvelopeConsumerTest.recordsArrivingAfterTrippingGetOneGraceReReadEvenThroughTransientFailures` |
| I9 | The classification is one statement. A test holds W2's transaction open, writing `decision_states(T, 1)` and B together, and sees S4 (never S3) until the commit, then S2. (Restored to the draft-5 wording, amendment 1.) | `SmsOrderingTest.intentReadWhileItsDecisionIsBeingWritten…` (the held-open writer: S4, never S3, until the commit). **In part:** `…theClassificationIsOneStatementOnOneConnection`, the structural half, counts only `prepareStatement`, so a split made with `createStatement` or `prepareCall` would pass (implementation-2, n7); this round counts those too |
| I10 | A dead-lettered intent keeps its headers, for every reason of D-a, in both consumers. A replay republishes key, value and headers plus `fs-replayed`. Replayed after its parent arrived, it is sent; replayed before, it returns to the DLQ at once and never waits. | **To be proved for every reason** (implementation-2, m4: only three of six were asserted). Replay half: `…replayRepublishesTheNewestCopyOnceAndReplayedOrphansNeverWait` |
| I11 | A replay stops at the end offsets taken when it starts, commits nothing, republishes only the newest copy per `event_id`, leaves other reasons, and refuses `rejected_by_the_database` unless asked. | `EnvelopeConsumerTest.replayRepublishes…` |
| I12 | The WARN fires under intermittent S5 failures. | `EnvelopeConsumerTest.longWaitsAreWarnedEvenWhilePostgresKeepsFailing` (the WARN interval configurable) |
| I13 | A block resolved before its SMS goes is not sent (S2r), and is counted. | `SmsOrderingTest.blockResolvedBeforeItsSmsIsNotSent` |
| I14 | The budget survives a change of owner. After a revocation, and after a crash with no revocation while the partition is tripped or waiting, the new owner resumes `spent` from the committed metadata: at most one re-read interval of waiting lost (W-e: 500 ms plus at most the thread's current batch), and the unowned time drained. A lost partition commits nothing. A future `at` is clamped to zero [2-M2] [3-m1] [3-m3] [4-m3]. (Restored to the draft-5 wording, amendment 1, except that draft 5's "at most 500 ms" contradicted W-e's own bound and now cites it [A1-m4].) | `EnvelopeConsumerTest.theBudgetSurvivesChangesOfOwner` (revocation); `…crashedOwnersBudgetsAreResumedFromTheirLastCheckpoint` (crash); `WaitBudgetTest.checkpointsFromTheFutureAreClamped…`. **In part:** the crash test asserts only that the next owner resumes less than a fresh budget, not the loss bound; this round asserts the bound in the java job if the test can measure it, and otherwise the row stays in part. **By review only:** the lost path committing nothing (`onPartitionsLost` has no commit) |
| I14b | A crash between a dead-letter and its commit may leave a second DLQ copy, which a replay republishes once. | `EnvelopeConsumerTest.replayRepublishes…` (two copies, one republish). The crash at that exact point is not produced. |
| I15 | A header-less intent is S4 with `kept` null, and is dead-lettered at the bound if its B never appears. | `SmsOrderingTest.headerlessIntentWaitsRatherThanBeingJudgedPermanent`; the bound: I7 |
| I16 | S0: an intent whose B row belongs to another account is dead-lettered and never sends. | `SmsOrderingTest.intentForAnotherAccountsBlockIsNeverSent` |
| (wire) | The intent's record carries `fs-transaction-id` on Kafka. | `KafkaSpoolChaosTest.theSmsIntentCarriesItsTransactionOnTheWire` |
| (malformed) | Replaced by I19 (amendment 1). The earlier row claimed this for every unreadable payload; it held for three fields of one handler (implementation-2, M3). | I19 |
| I17 | A record that fails in F4 (12.2) is retried on the backoff and dead-lettered once the bound of 12.3 is reached, as `handler_failure`, or as `outcome_unrecorded` if a provider result is kept (12.4); the record behind it is then handled. The retry state survives a change of owner, on a partition whose budget is empty too (W-e). An S4 answer does not clear it; F1 failures neither use up nor reset it. If the dead-letter send fails at the bound, the state stands and the next attempt dead-letters the record. | to be written: through `EnvelopeConsumer` with the real sender, an intent that fails deterministically (implementation-2, needs-execution item 2); a poison record across repeated rebalances with `spent` at zero [A1-M3]; a record alternating S4 and F4 [A1-m3] |
| I18 | An F1 failure is retried without bound and without a dead letter. Every failed attempt, F1 or F4, is counted, and a head that has failed for 1 minute of wall time in a row is warned at WARN, and every minute after, naming topic, partition, offset, class and the last exception [A1-m4]. | to be written |
| I19 | Every field a handler reads from the record is validated before any side effect: an envelope whose `payload` is absent or not an object, an SMS intent with any required field absent or unreadable (12.1), and a webhook payload with any required field absent or unreadable are each dead-lettered as `malformed_envelope`, and nothing is sent, issued or recorded. | to be written: one test per required field group, in each consumer |
| I20 | Once the provider has been called for a record, its result is kept and checkpointed: SENT when the reply was read and accepted, FAILED otherwise, a 2xx reply that cannot be read and any unchecked exception from the adapter included. A re-read of that record, on this owner or the next, writes the kept outcome and never calls the provider again, whatever class the failed outcome write was; exactly one send is made. A dead letter after a send is `outcome_unrecorded`. | to be written: a gateway answering HTTP 200 with a non-JSON body through the real `AfricasTalkingGateway` parsing (implementation-2, needs-execution item 1); an outcome write that fails after the send, in F1 and in F4, with an owner change between [A1-M1] |
| I21 | A malformed `fs-transaction-id` (5.1) with B visible is classified exactly as with a valid header; with B absent it is dead-lettered at once as `malformed_header` (S3m); it is logged and counted. An absent header is S4 (I15). A short form such as `1-2-3-4-5` is malformed. | to be written, through `Envelope` and the wiring, not by parsing the header in the test (implementation-2, m2) |
| I22 | A committed offset that has fallen below the log start is never skipped silently: the reset to the log start is logged at ERROR with the number of offsets skipped, and counted; the old head's retry state and kept result are dropped. An offset above the log end is logged at ERROR and restarted from the log start, uncounted. A partition the client reports as having no committed offset starts at the log start; a failed lookup never counts as "no offset" [A1-m2]. | to be written: a short-retention topic behind a held partition (implementation-2, needs-execution item 4); a new group |
| I23 | Each counter and the gauge of 12.6 moves exactly as 12.6 defines its count. | to be written: one assertion per metric, including a re-read that must not count twice |
| I24 | Every failure of an attempt is in exactly one class of 12.2, the cause chain walked, and the class decides the action. | by review of the classification function, plus a table-driven test over every SQLSTATE and exception type 12.2 names |
| I25 | The SMS is rendered before a verification is issued: a rendering failure leaves no verification behind [A1-m6]. | to be written: a settings row without `official_phone`, then the row fixed and the record replayed: the SMS carries a link |
| I26 | Every external call in an attempt is bounded by a timeout (12.3); an `Error` stops the consumer: logged at ERROR, counted, health DOWN, partitions released [A1-m1]. | to be written: a statement held past `statement_timeout` is F4, not F1; an `Error` thrown by a handler stops the member |
| I27 | A record whose dead letter cannot be written for a reason that is not retriable is logged at ERROR in full, counted as dropped, and committed past once the bound of 12.3 is reached [A1-n4]. | to be written: a dead letter over `max.request.size` |

## 9. Alternatives rejected

- **Transactional outbox:** W2 would publish the SMS intent from PostgreSQL after its commit. This
  removes N1 for this reader altogether and is the better long-term design. It moves the intent's
  publisher from W3 to a new PostgreSQL-driven publisher, which is outside what M6 can still carry.
  Recommended for a later milestone.
- **`transaction_id` in the payload:** allowed by ADR 0012 point 5 as an optional property, but the
  owner froze the contracts. The header carries the same fact [m5].
- **Several statements, or several connections, for the checks:** not one snapshot (5.1).
- **A per-record bound, or a clock reset by any other outcome:** orphans could stall a partition
  for k × the bound (W-b) [3-M1].
- **Ids from T alone:** they let a different-body duplicate reach S2 (section 4).
- **A clock kept only in memory:** every rebalance resets it (W-e).
- **The record timestamp as the clock:** after an outage it trips at once, while W2 is still
  catching up (review 11).
- **A replay tool with a committed consumer group:** it loses the reasons it did not replay (D-c).
- **An unbounded wait** (ADR 0057): rejected by the owner.
- **Deadlines without replay** (`3141763`, `098c5dc`): these lose the notice for good.
- **(amendment 1) A bound on outages too (F1):** during a long PostgreSQL outage it would
  dead-letter the head of every partition, again and again, which is review 11's MAJOR. An outage is
  visible, shared and counted instead (12.5, 12.6).
- **(amendment 1) Leaving every unchecked exception unbounded:** a deterministic failure stalls its
  partition until retention drops what is behind it (implementation-2, M1).
- **(amendment 1) A per-record attempt count kept only in memory, or checkpointed only while the
  budget is above zero:** every rebalance restarts it, the same reason as W-e for the budget
  [A1-M3].
- **(amendment 1) F3 as the default for an unlisted SQLSTATE:** it dead-letters every record at once
  during outages the list did not foresee (a credential rotation, a failover to a read-only node)
  [A1-M2].
- **(amendment 1) Re-running the whole attempt after a failed outcome write:** it repeats the send
  on every retry [A1-M1].
- **(amendment 1) Dead-lettering an intent with a malformed header whatever B shows:** it would
  turn a harmless corruption into a lost notice.
- **(amendment 1) Waiting on an intent with a malformed header, as if header-less:** it spends a
  budget that valid intents share, for a record W3 cannot produce, and dead-letters it under the
  wrong reason.
- **(amendment 1) Sharing `malformed_envelope` for S3m:** S3m is replayed when B is expected to
  exist, an unreadable envelope only once its cause is fixed [A1-n6].
- **(amendment 1) Retrying a provider call whose reply cannot be read:** the SMS may already be
  delivered; every retry is another SMS to the customer (implementation-2, M2).
- **(amendment 1) `auto.offset.reset=earliest` on its own:** after a retention gap it skips the lost
  records silently.

## 10. Separate from this contract

The idempotency claim in ingest (`ResilientIdempotency.claim` → `JdbcIdempotency.claim`) fails with
`IllegalStateException` when neither Redis nor PostgreSQL can answer, and the client gets a 500.
The claim comes before any decision, so the failure is retryable. **(change)** `IngestService`
answers 503 `service-unavailable` with `Retry-After`, which the ingest contract already lists. In a
batch, the same outcome is one of the transient results `BatchJobs` retries; if the item still
fails, the job ends FAILED, as for any other unavailable dependency [NIT].

## 11. Changes to the code, in full [m7] [2-m4]

1. `DecisionService` derives B and N from (institution, T, F), since F is already its parameter,
   and passes both to the port. `CustomerNotificationPolicy.compose` gains an N parameter, which
   `CustomerSmsPolicy` implements. The decision tests' `InMemoryPorts` and the notify tests'
   `SmsPolicyTest` are updated (section 4) [3-n1] [4-n1].
2. `KafkaMessage` gains headers. `KafkaMessages` sets `fs-transaction-id` on the SMS intent from the
   `AutoBlocked` fact in the same record, and `KafkaSink` writes the headers (section 4).
3. `EnvelopeConsumer.Handler` receives the record's headers: T and the replayed flag. This applies
   to both consumers built on it: the SMS consumer, and the webhook dispatcher
   (`NotificationWiring`, group `webhook-dispatcher`), which ignores them [4-m4].
4. `CustomerSmsSender` classifies with one statement (5.1) and implements S0 to S5 (5.3). It takes
   link eligibility from the REQUESTED row (5.2). It never records the intent's own locale [4-n2]: a
   FAILED row before the vault answered records the REQUESTED row's locale, and a SENT or later
   FAILED row records the vault locale the SMS was rendered in, as before.
5. `EnvelopeConsumer` implements:
   - the per-partition pause (W-a);
   - the budget (W-b);
   - the trip with its grace re-read (W-c);
   - the WARN (W-d);
   - the offset-metadata checkpoint, with `subscribe(topics, listener)` handling revoked, lost and
     assigned (W-e) [4-m4];
   - the new outcomes (section 6).
6. Dead-letters keep the original headers, for every reason and in both consumers (D-a). ADR 0064
   point 5 is amended.
7. New `DeadLetterReplay` (D-c).
8. New gauge `fs_spool_lag{consumer}` in bytes (D-c2).
9. ADR 0057 is superseded. ADR 0056 records this contract's decisions.
10. `IngestService` answers 503 when the idempotency claim cannot be made (section 10).

Amendment 1 adds:

11. `EnvelopeConsumer` classifies every failure of an attempt into one class of 12.2, walking the
    cause chain; keeps the retry state and the kept provider result per head in memory and in the
    `v3` checkpoint (12.3, 12.4, W-e, W-e2); dead-letters `handler_failure` and
    `outcome_unrecorded`; drops a record whose dead letter cannot be written; stops on an `Error`;
    and warns (12.5).
12. Validation before any side effect (12.1): `EnvelopeConsumer` for the envelope; `CustomerSmsSender`
    for the whole intent; `WebhookDispatcher` for its payload.
13. `AfricasTalkingGateway` converts any failure to read a reply into `IOException`;
    `CustomerSmsSender` renders before it issues, records FAILED for any exception from the provider
    call, and hands the provider result to the consumer to keep, writing a kept result instead of
    sending again (12.4). The handler interface carries the kept result both ways.
14. `Envelope.transactionId()` returns absent, valid or malformed, with a strict parse (5.1);
    `CustomerSmsSender` implements S3m.
15. The consumer handles missing and out-of-range offsets itself (12.5) instead of a silent reset.
    `DeadLetterReplay` refuses `outcome_unrecorded` unless asked (D-c).
16. The counters and the gauge of 12.6, exported through the application's meter registry.
17. Each transaction the consumers open sets `statement_timeout` (12.3); the KMS client and the
    dead-letter producer get the timeouts of 12.3; `max.poll.interval.ms` is set to cover a batch.
18. ADR 0056 records amendment 1; the test claims of section 8 are corrected.

## 12. Failures of an attempt, and the retry bound (amendment 1)

The second implementation review found that the contract treated every unchecked exception as
transient, so a record that failed the same way every time was re-read for ever and stalled its
partition until retention dropped what was queued behind it (implementation-2, M1, m6). This section
replaces that assumption. It applies to both consumers built on `EnvelopeConsumer`.

### 12.1 Validation before any side effect (change)

Each handler validates every field it reads from the record **before** it issues, sends, records or
queues anything. A failure is `malformed_envelope` (F2). Fields read from the database or the vault
are not part of the record; their failures are classified by 12.2.

- **Envelope (`EnvelopeConsumer`, both consumers):** the value is JSON; `institution_id` is a
  canonical UUID (5.1's definition); `payload` is present and is a JSON object.
- **SMS intent (`CustomerSmsSender`):** `notification_id` and `auto_block_event_id` are canonical
  UUIDs; `account_token` is a non-empty string; `parameters` is an object holding `amount.amount`
  (a decimal string `Money` accepts), `amount.currency` (a `CurrencyCode`), `local_time` and
  `reference_code` (non-empty strings). An amount `Money` refuses was recorded as a FAILED row
  before; it is now `malformed_envelope`, with no outcome row [A1-n2].
- **Webhook payload (`WebhookDispatcher`):** `decision_sequence` is an integer from 1 to 2³¹−1;
  when it is 2 or more, `transaction_id` and `event_id` are canonical UUIDs. A sequence of 1 is
  returned from without reading them, as today [A1-n1].
- The `fs-transaction-id` header is not validated here: its malformed state is a classification
  input (5.1, S3m), not a reason to refuse a send.

A field the frozen schema allows but the handler does not read is not validated.

### 12.2 Failure classes (change)

Every failed attempt is in exactly one class. The consumer walks the exception's cause chain, and
the first row below that matches **any** exception in the chain decides [A1-M2]:

| Class | What | Action |
|---|---|---|
| **F1 outage** | A shared dependency is unavailable. SQL: SQLSTATE class `08` except `08P01`; class `28` (credentials, as after a rotation); `3D000`; `25006` (a read-only node, as after a failover); `40001`, `40P01`; `53100`, `53200`, `53300` (disk, memory, connections); `57P01`, `57P02`, `57P03`; any `SQLTransientConnectionException` or `SQLRecoverableException`, whatever its SQLSTATE (a connection-pool timeout has none). Vault: a `VaultException` that is not permanent and whose cause chain holds an F1 SQL failure, or a KMS call the KMS client reports as unreachable or timed out. Kafka: the dead-letter send failing with a `RetriableException`. | Retried on the backoff **without bound**. Neutral time (W-b). Warned and counted (12.5, 12.6). |
| **F2 malformed** | 12.1 refused the record, or the header is malformed with B absent (S3m). | Dead-lettered at once as `malformed_envelope`, or `malformed_header` for S3m. |
| **F3 permanent** | SQLSTATE class `22` (data) or `23` (integrity); a permanent `VaultException`; S0 and S3. | Dead-lettered at once: `rejected_by_the_database`, `permanent_vault_failure`, `not_the_kept_decision`. |
| **F4 may be the record's own** | Everything else, including: any other SQLSTATE (`08P01`, class `42`, `53400`, `57014`, class `58`, class `XX`, a null SQLSTATE on an exception that is neither transient type above); a `VaultException` that is not permanent and not F1, such as a key this instance does not hold yet (ADR 0069); the dead-letter send failing with an exception that is not retriable (`RecordTooLargeException`, `InvalidTopicException`); any other `RuntimeException`. | Retried on the backoff **up to the bound of 12.3**, then dead-lettered as `handler_failure`, or `outcome_unrecorded` after a send (12.4). |

- A record whose dead letter cannot be written because the send fails for a reason that is not
  retriable cannot leave through the DLQ. At the bound of 12.3 it is logged at ERROR in full
  (topic, partition, offset, key, headers, the value's SHA-256 and length), counted as dropped
  (12.6), and committed past [A1-n4].
- An `Error` (a `Throwable` that is not an `Exception`) is not classified: the consumer logs it at
  ERROR, counts it, reports itself DOWN to the application's health check, closes (releasing its
  partitions to the group) and is not restarted by itself [A1-m1]. An `Error` caused by one record
  stops each member that reads it in turn; that is loud by design, and the operator removes the
  record.
- Once the provider has been called for the head record, a failure does not repeat the call: 12.4.

### 12.3 The bound on F4, and timeouts (change)

- For the record at the head of a partition, the consumer keeps **`f4_count`**, the number of
  attempts on that record that failed in F4, and **`f4_ms`**, the wall time since that record's
  first F4 failure minus every interval from an F1 failure to the next attempt on it.
- The state is cleared **only when the head changes**: the record is handled, dead-lettered,
  dropped, or skipped by a reset (12.5). An S4 answer does not clear it, so a record alternating S4
  and F4 reaches the bound in 10 minutes of F4 wall time rather than living on the wait budget
  [A1-m3]. An F1 failure neither adds to it nor clears it.
- At the end of an attempt that did not handle the record, if `f4_count` is at least **5** and
  `f4_ms` at least **10 minutes**, the record is dead-lettered as `handler_failure` (or
  `outcome_unrecorded`, 12.4), with `fs-dlq-error` naming the exception class and message, and the
  record behind it is handled. Both values are ASSUMED and configurable. With the backoff capped at
  `MAX_BACKOFF_MS` (30 s), 10 minutes is about 25 attempts; the count only matters when the cap is
  configured high. If the dead-letter send then fails, the attempt's F4 increment stands, and the
  next attempt dead-letters the record whatever it answers, unless it handles it [A1-m3].
- Ten minutes outlasts a rolling vault-key rotation (ADR 0069) and a blip; a failure that outlasts
  it is treated as the record's own, and the record can be replayed once the cause is fixed (D-g).
- The state is checkpointed after every failed attempt and survives a change of owner (W-e, W-e2).
  The unowned time between owners is not added to `f4_ms`.
- **Every external call in an attempt has a timeout**, so a hang becomes a failure that can be
  classified [A1-m1] [A1-m7]:
  - PostgreSQL, the vault included: each transaction the consumers open sets `statement_timeout`
    to 5 s (ASSUMED), below the pool's socket timeout (10 s), so a slow statement fails as `57014`
    (F4), not as a socket timeout (`08006`, F1);
  - the SMS provider: 3 s to connect and 5 s for the request, as today;
  - the KMS client: at most 5 s per call;
  - the dead-letter send: `delivery.timeout.ms` at most 30 s;
  - webhook delivery: its existing per-request timeout.

  The consumers' `max.poll.interval.ms` is set above `max.poll.records` times the longest attempt
  these timeouts allow, so a slow batch never expels a member.

### 12.4 One send per record: the provider's result is kept (change)

- **The SMS is rendered before the verification is issued.** Rendering is attempted with a
  placeholder link; only if it succeeds is the verification issued and the SMS rendered with the
  real link. A rendering failure therefore leaves no verification behind, and the block's only link
  is not used up by a failure before the send [A1-m6]. A rendering failure that today records a
  FAILED row (`IllegalArgumentException`) still does.
- Once `gateway.send` has been called for head X, it ends in a **provider result** R: **SENT**
  with the provider's reference, when the reply was read and accepted; or **FAILED**, for anything
  else, a 2xx reply that cannot be read and any unchecked exception from the adapter included
  (`AfricasTalkingGateway` turns an unreadable reply into `IOException`; `CustomerSmsSender`
  catches any exception from the call). R also holds the locale the SMS was rendered in and
  whether it carried a link.
- **FAILED means "not known to be accepted by the provider"**, for every reader [A1-m6]. When the
  reply could not be read, the SMS may have been delivered: that case is logged at ERROR with N and
  counted (12.6). No reader may treat FAILED as safe to send again; today only `classify` reads it,
  as S1.
- **The consumer keeps R** for X in the partition's state and checkpoints it (W-e, W-e2) before X is
  re-read [A1-M1]. A re-read of X, on this owner or the next, that finds B and no outcome **writes
  R and never calls the provider again**, whichever state the statement shows (S2, or S2r if the
  block was resolved meanwhile). One that finds an outcome is S1.
- If the outcome cannot be written, the attempt fails in its class: F1 is retried without bound,
  each retry only writing R; F4 is retried up to the bound. Either way no second send is made.
- **Any dead letter written while R is kept** is `outcome_unrecorded`, whatever the cause (F3, or
  the F4 bound). Its `fs-dlq-error` records R. The replay tool refuses it unless asked (D-c).
- What remains open is a crash or eviction after the provider call and before R is checkpointed:
  13.1.

### 12.5 What an operator sees, and the retention gap (change)

- **Warnings.** A partition whose head has failed (F1 or F4) for 1 minute of wall time in a row is
  logged at WARN, naming topic, partition, offset, class and the last exception, and every minute
  after that. The run ends when the head changes or an attempt on it succeeds. W-d's WARN for
  waiting is unchanged.
- **No silent skip** [A1-m2]. The consumer sets `auto.offset.reset=none` and handles both cases
  itself, catching both exceptions before the poll loop's generic handler:
  - `NoOffsetForPartitionException`: the client reports these partitions as having no committed
    offset (a new group or a new partition). They start at the log start, as `earliest` did.
  - `OffsetOutOfRangeException`: for each partition named, the consumer reads `beginningOffsets`
    and `endOffsets`:
    - **below the log start** (retention deleted the offset while the partition was held): it logs
      at ERROR the topic, partition, committed offset and log start, counts the difference as
      skipped (12.6), and seeks to the log start. If the head held a kept provider result, the
      ERROR names its N, whose outcome is now never recorded;
    - **above the log end** (`LogTruncationException`, after an unclean election or a re-created
      topic): it logs at ERROR, counts nothing, and seeks to the log start, as for a new partition.
  - In both cases the old head's retry state and kept result are dropped, the partition is resumed
    if it was paused, and the next commit carries metadata for the new head only.
- With 12.3, only an F1 outage can hold a partition long enough for retention to delete its head:
  3 days on `fs.notifications.customer`, 7 on `fs.decisions.final`.
- Alerts on these signals are M9's, with the DLQ-growth alert already carried there.

### 12.6 Metrics (change)

Exported through the application's meter registry, with the consumer group as the `group` tag. Each
count is defined so that a test can assert it exactly [A1-m5]:

| Name | Type | Tags | Counts |
|---|---|---|---|
| `fs_notify_dead_letters_total` | counter | `group`, `reason` | each dead letter whose send was acknowledged. A second copy after a crash before the commit (I14b) counts again, as it is a second record on the DLQ |
| `fs_notify_retries_total` | counter | `group`, `class` (`outage`, `may_be_own`) | each failed attempt after which the record will be re-read. The attempt that reaches the F4 bound is not counted here; its dead letter is |
| `fs_notify_dropped_total` | counter | `group` | each record committed past because its dead letter could not be written (12.2) |
| `fs_notify_wait_warnings_total` | counter | `group` | each W-d WARN |
| `fs_notify_resolved_before_send_total` | counter | `group` | each S2r answer that commits past a record without a kept result |
| `fs_notify_malformed_headers_total` | counter | `group` | each record with a malformed `fs-transaction-id`, once per (partition, offset) on this owner, however often it is re-read |
| `fs_notify_sms_outcome_unknown_total` | counter | `group` | each FAILED provider result whose reply could not be read, or whose call threw after it may have reached the provider |
| `fs_notify_offsets_skipped_total` | counter | `group` | offsets below the log start skipped by a reset (12.5), as the difference between the log start and the committed offset |
| `fs_notify_consumer_errors_total` | counter | `group` | each `Error` that stopped the consumer (12.2) |
| `fs_notify_head_failing_seconds` | gauge | `group` | at scrape, over the owned partitions, the longest time since the current head's first failed attempt (F1 or F4) in its current run of failures; a run ends when the head changes or an attempt on it succeeds. 0 when no head is failing |

## 13. Known gaps (amendment 1)

### 13.1 A crash between the provider call and the checkpoint of its result [impl-2 m7] [A1-M1]

12.4 keeps the provider's result for a record and checkpoints it before the record is re-read, so a
failed outcome write no longer sends again. One window remains: the member crashes, or is expelled,
after the provider call and before that checkpoint is committed. The next owner finds B and no
outcome, with no kept result, and **sends a second SMS**. `VerificationService.issue` runs once per
block, so the second SMS carries no link.

- The window is one provider call plus one `commitSync`, normally well under a second.
- Not closed in M6. It is carried as **`PB-77`** (claimed in `docs/parallel/M6_updates.md`). Two
  designs are on record: a `SENDING` row written before the provider call and read as "outcome
  unknown, do not send", and the transactional outbox of section 9.
