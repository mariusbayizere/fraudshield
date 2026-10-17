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

**Amendment 1 (2026-10-17): reviewed sound in draft 3, its MINORs and NITs applied.** It answers the second implementation
review (`-2026-10-17-implementation-2.md`: M1, M2, M3, m1, m2, m3, m4, m6, m8, with m5 and m7) on
the owner's scope of the same day. Draft 1 (`b7eefd5`) was reviewed on its own and was **not sound**:
3 MAJOR, 7 MINOR, 6 NIT (`-2026-10-17-amendment-1.md`, cited `[A1-…]`). Draft 2 (`2dafbf0`) closed
those MAJORs and was **not sound** either: 2 MAJOR, 9 MINOR, 8 NIT (`-amendment-1-draft-2.md`,
cited `[A2-…]`): an `Error` closing the consumer committed past unhandled records, and shared Kafka
faults on the dead-letter send were dropped at the bound. Draft 3 (`d7c1554`) was reviewed **sound**: 0 BLOCKER, 0 MAJOR,
7 MINOR, 9 NIT (`-amendment-1-draft-3.md`, cited `[A3-…]`), applied below before any code, as draft
5's were. It changes:

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
- **D-a, D-c, D-d, D-g, section 9 and section 11** are changed to match [A3-n1].
- **Section 8**: I7e, I8, I9 and I14 are restored to the wording reviewed sound in draft 5 (the
  implementation round had narrowed them without saying so, implementation-2 m5). Every row was
  checked against its named test (owner, 2026-10-17): where a test proves less than the row says,
  the row says **in part** and names the gap, instead of narrowing the text. I10 and the
  "(malformed)" row claimed more than their tests (M3, m4) and are restated. New invariants I17 to
  I29 cover section 12.
- **Draft 3 also adds:** an `Error` seeks back and commits before the consumer closes, and is
  reported outside readiness [A2-M1] [A2-m8]; every dead-letter send failure but a record that is
  too large is an outage [A2-M2]; the kept result is committed before the outcome write and rides
  on the dead letter, so replaying `outcome_unrecorded` records the outcome without a send
  [A2-m1] [A2-m9]; a batch time budget, vault timeouts and a typed KMS outage [A2-m3] [A2-m4]; a
  truncation no longer rewinds to the log start [A2-m5].
- **Applied from draft 3's review:** no health indicator for a stopped consumer, so the liveness
  probe is never affected; the webhook executor's own `Error` rule; commits carry the leader epoch
  and a log divergence resumes at its divergent offset; the full timeout arithmetic; and a
  `fs-kept-result` honoured only on replayed records [A3-m1…m7].
- **Section 13 (new): known gaps**: a crash between the provider call and the commit that keeps
  its result (m7, `PB-77`); a provider outage ending the notice (`PB-79`).

**Rule for every row of section 8** (owner, 2026-10-17): a row that names a test is a claim that the
test fails when the invariant is broken. Each review checks that the named test proves the row as
worded, not a narrower property.

**The standard each row is held to** (owner, 2026-10-17, after the third review running found rows
claiming more than their tests): would the named test fail if the code it claims to prove were
deleted? A row says "**Proved:**" only for what passes that test, and names each part that does not
as "**Not proved:**", in those terms. Audited at `7047567`: of 38 rows, 13 were proved in full, 24
in part, and 1 (I7e) not at all. On the owner's decision, I7e, I8, I12 and I14's revocation clause
were then closed with tests: **16 rows are proved in full and 22 in part**, each partial row carried
to the backlog with its wording (`PB-80` to `PB-101`); I26 and I27 are partial by the owner's
decision.

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
    the BCP 47 tag the SMS was rendered in (letters, digits and hyphens only), `<link>` is `1` or
    `0` for whether it carried a link, and `<reference>` is the provider's message id as kept in R
    (at most 128 Unicode code points, 12.4), UTF-8 encoded and then base64url-encoded without
    padding. The limit applies to the raw reference, before encoding. An empty reference is
    allowed, encodes as the empty string, and is recorded as null [A2-n1] [A2-m9]. A U+0000 in a
    reference, which PostgreSQL's `text` refuses, is replaced by U+FFFD before it is kept [A3-n4].
  - With those limits the line is at most about 900 bytes, below Kafka's default
    `offset.metadata.max.bytes` (4,096) [A3-n4].
  - The same `result` form is carried by the `fs-dlq-result` and `fs-kept-result` headers (12.4,
    D-c).
  - A reader accepts `v2` (as before, with no retry state and no kept result) and `v3`. Anything
    else, or a line that does not parse, is read as no metadata and logged at WARN.
  - **Rollout.** No release of these consumers has been deployed, so no group mixes a `v2`-only
    reader with a `v3` writer. From this version on, a new format is written only by a release
    whose predecessor already reads it (two-step rollout).
- **W-e3. (change, amendment 1)** Every commit W-e describes also carries a leader epoch, chosen as
  12.5 says [A3-D-n2].
- **W-f.** Records behind a waiting intent on its partition wait with it, within W-c2's limit, plus
  neutral time. Other partitions are not held up (W-a).
- ADR 0057 (no deadline) is **superseded** by this contract.

## 7. Dead letters and replay

- **D-a. (change)** A dead-lettered copy keeps the original key, value and **headers**
  (`event_id`, `fs-transaction-id`, `fs-replayed`), and adds `fs-dlq-reason`, `fs-dlq-error`,
  `fs-dlq-topic`, `fs-dlq-offset` and `fs-dlq-at`. This applies to **every** dead-letter reason:
  `not_the_kept_decision`, `parent_not_recorded`, `malformed_envelope`, `malformed_header`,
  `rejected_by_the_database`, `permanent_vault_failure`, `handler_failure` and `outcome_unrecorded`,
  in both consumers. A copy re-sent because it was too large lacks `fs-dlq-error` (12.2) [A3-n8].
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
  - Within the run, it keeps **only the newest copy of each envelope `event_id`**, chosen across
    every reason, and republishes it only if that copy's own reason was asked for [2-m2]
    **(change, implementation-3 MAJOR 3)**. An older copy is therefore never republished over a
    newer one, such as a `parent_not_recorded` copy of an intent whose newer copy is
    `outcome_unrecorded`. Repeated runs republish each intent once, instead of doubling the copies
    left by earlier bounces.
  - It republishes that copy's key, value and headers, without the `fs-dlq-*` headers (except
    `fs-dlq-result`, carried as `fs-kept-result`) and without any `fs-kept-result` the copy
    carried, which only its `fs-dlq-result` may supply (implementation-3 m3), to the topic
    named in `fs-dlq-topic`, with `fs-replayed` set to the copy's value plus one (1 if absent)
    [2-n3].
  - It prints how many records it republished, and how many it skipped by reason. Nothing is
    committed, so other reasons stay available to later runs.
  - It refuses `rejected_by_the_database` unless `--allow-rejected` is given, and then warns that
    each such SMS may already have been delivered [A2-n5]. Since amendment 1 no new
    `rejected_by_the_database` follows a send (12.4), but copies written before it may.
  - **(change, amendment 1)** It replays `outcome_unrecorded` when asked with `--reason`, without
    `--allow-rejected`: it republishes the copy's `fs-dlq-result` as `fs-kept-result`, replacing any
    `fs-kept-result` the copy already carries, so a record never carries more than one; the
    consumer then records that result and sends nothing (12.4) [A2-m9] [A3-m6].
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
    lands in S1. **(change, amendment 1)** A record dead-lettered after a send is
    `outcome_unrecorded` and carries its result, so replaying it records the outcome and does not
    send (12.4); and since the tool replays only the newest copy of an event (D-c), an older copy of
    an intent already sent is never republished. What remains is 13.1's window, a consumer crashing
    or evicted between the provider call and the commit that keeps its result, and
    `rejected_by_the_database` copies written before amendment 1 [A2-m6] [implementation-3 R-m3].
  - The tool therefore replays `rejected_by_the_database` only with `--allow-rejected`, and warns
    that such an SMS may already have been delivered.
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
  after the bound of 12.3 again; it never loops without bound. `outcome_unrecorded` carries its
  provider result; replaying it records that result and sends nothing (12.4, D-c). The default
  reason of a replay stays `parent_not_recorded` [A3-m7].

## 8. Invariants, and the test that proves each

A row says **Proved:** only for what its named test would catch if the enforcing code were deleted,
and **Not proved:** for every part it would not (the standard set out in the status block: of 38
rows, 16 are proved in full and 22 in part). Where a property is proved only in part, or
only by review, the table says so: the implementation review of `bd48557` found that an earlier
claim, "every invariant is tested", was not true.

| # | Invariant | Proved by |
|---|---|---|
| I1 | No SMS is sent, no verification is issued and no outcome is recorded while B is absent (S3, S3m, S4). | `SmsOrderingTest`: `nothingDoneFor` in the S3, S4 and I4b tests. S3m: I21 |
| I2 | Once an intent's outcome is visible, the intent is not sent again (S1). | `SmsOrderingTest.theSameSubmissionBlockingTwiceSendsOneSms`; `VerificationFlowTest.anIntentReReadAfterItsOutcomeWasRecordedIsNotSentAgain` |
| I3a | A transaction decided twice, first **not** blocking, then blocking, with the kept decision already written: the second intent is dead-lettered at once as `not_the_kept_decision`, never waits, and never sends. | **Proved:** `SmsOrderingTest.keptDecisionWithoutBlockThenBlockIsDeadLetteredAtOnceAndNeverSent` (sender); `EnvelopeConsumerTest.everyDeadLetterReasonKeepsTheRecordsHeaders` (consumer); `…theTransactionHeadersStateReachesTheHandlerAndMalformedOnesAreCountedOnce` (the header through `Envelope`). **Not proved:** replacing `envelope.transaction()` with `ABSENT` in the `NotificationWiring` lambda would not fail any test. |
| I3b | The same, with the intent read **before** W2 writes the kept decision: S4, then S3 once it is written, then dead-lettered. It never sends. | **Proved:** `SmsOrderingTest.keptDecisionWithoutBlockReadBeforeItIsWrittenWaitsThenIsDeadLettered` (S4, then S3, never sends); the dead letter at the consumer: I3a's consumer test. |
| I4 | A submission decided twice, blocking both times: exactly one SMS. | `SmsOrderingTest.theSameSubmissionBlockingTwiceSendsOneSms`, `…IdsDerivedFromTheSubmission` |
| I4b | T submitted twice **with different bodies** (another account), both blocking, the first kept: the other body's intent is S3 and never sends; the kept intent is sent to the kept account [2-M1]. | `SmsOrderingTest.differentBodyForTheSameTransactionNeverReachesTheKeptAccount` |
| I5 | Link eligibility comes from the kept decision: when the discarded decision allowed a link and the kept one did not, the SMS carries no link. It also fails closed without a REQUESTED row. | `SmsOrderingTest.theLinkIsTheKeptDecisionsAndFailsClosedWithoutItsRequestedRow` |
| I6 | An intent that arrives before its record is written is sent once the record is written (S4, then S2). | `EnvelopeConsumerTest.intentsWaitWithinTheirPartitionsBudget…` (record 1); `SmsOrderingTest.intentReadWhileItsDecisionIsBeingWritten…` |
| I7 | The partition's budget: once waiting has filled `spent` to the configured bound, that record is dead-lettered as `parent_not_recorded`. A record whose first answer while tripped is S4 gets one grace re-read, and is dead-lettered if still S4, or sent if its parent arrived. Draining time as defined in W-b drains `spent` at the configured rate, neutral time does not, and no outcome resets it [5-m4]. | **Proved:** `WaitBudgetTest` (fill, drain, no reset); `EnvelopeConsumerTest.intentsWaitWithinTheirPartitionsBudget…` (end to end); `…recordsArrivingAfterTrippingGetOneGraceReRead…` (one grace re-read, not a fresh budget). **Not proved:** deleting the consumer's `!first` guard would not fail any test: any draining since a trip leaves the budget just below full at a record's first answer, so the grace re-read happens without it. |
| I7b | Orphans interleaved with valid intents keep the partition within W-c2's limit [3-M1]. | `WaitBudgetTest.orphansBetweenValidIntentsDoNotEachGetTheWholeBound` |
| I7c | An orphan after an idle period following a trip waits for what the idle time drained [4-M1]. | `WaitBudgetTest.idleTimeAfterTrippingDrains…` |
| I7d | While one partition waits, is tripped, or backs off after a failed attempt (F1 or F4, 12.2), another partition's intents are handled without delay [5-m1]. | **Proved:** `EnvelopeConsumerTest.otherPartitionsFlowWhileOneWaitsForItsParentAndAnotherBacksOff` (one partition waiting, one backing off after an outage: others under 3 s). **Not proved:** a tripped partition and an F4 backoff are never produced; the row's "is tripped" and F4 clauses rest on review. |
| I7e | A partition paused on one member and moved to another: the first member's other partitions keep flowing and no `IllegalStateException` escapes; the second member re-reads at once, with `spent` resumed from the metadata [5-m2]. (Restored to the draft-5 wording, amendment 1.) | **Proved:** `EnvelopeConsumerTest.partitionsMovedWhilePausedKeepEveryoneFlowing`: the paused partition moves to the second member while its old owner keeps running and flowing; the second member re-reads it, and only it; no poll cycle of either member fails, which a resume of a partition no longer owned would make it do (deleting both the owned-partition check and the revocation's drop of the partition's state fails the test; each alone prevents the resume, by design). The resumed `spent`: I14. |
| I7f | An exception between `poll` and the end-of-batch commit skips no fetched record [5-n3]. | **In part.** `EnvelopeConsumerTest.batchesThatCannotBeCommittedSkipNoRecord` covers a member expelled mid-batch. **Not proved:** deleting `seekBack` and the cycle's catch would not fail it: the rejoin resets positions from the committed offsets. It proves only that commits happen at the end of a batch; a commit failure while membership is kept, which `seekBack` is for, is never produced. |
| I8 | Neutral time, from a failed attempt (S5, or a failed DLQ send) to the next successful answer, neither adds to nor drains `spent`. A grace re-read that meets S5 is retried until it succeeds [4-m1] [4-n4]. (Restored to the draft-5 wording, amendment 1; with section 12, "until it succeeds" holds for F1, and an F4 failure is bounded by 12.3.) | **Proved:** `WaitBudgetTest.neutralTimeNeitherFillsNorDrainsTheBudget` (the arithmetic); `EnvelopeConsumerTest.outageTimeAfterWaitingIsNeutralNotWaiting` (a 6 s outage between two waits does not fill a 3 s budget; deleting the consumer's `budget.failed(start)` dead-letters the record); `…recordsArrivingAfterTrippingGetOneGraceReReadEvenThroughTransientFailures` (a grace re-read meeting an outage is retried and sent). |
| I9 | The classification is one statement. A test holds W2's transaction open, writing `decision_states(T, 1)` and B together, and sees S4 (never S3) until the commit, then S2. (Restored to the draft-5 wording, amendment 1.) | `SmsOrderingTest.intentReadWhileItsDecisionIsBeingWritten…` (the held-open writer: S4, never S3, until the commit); `…theClassificationIsOneStatementOnOneConnection` (structural: one connection, one statement besides the tenant's, counting `prepareStatement`, `prepareCall` and `createStatement`, implementation-2 n7) |
| I10 | A dead-lettered intent keeps its headers, for every reason of D-a, in both consumers. A replay republishes key, value and headers plus `fs-replayed`. Replayed after its parent arrived, it is sent; replayed before, it returns to the DLQ at once and never waits. | **Proved:** `EnvelopeConsumerTest.everyDeadLetterReasonKeepsTheRecordsHeaders` (all eight reasons keep their headers); `…replayRepublishesTheNewestCopyOnceAndReplayedOrphansNeverWait` (`fs-replayed` incremented, `fs-dlq-*` stripped, a replayed orphan never waits). **Not proved:** the replay republishing the original key is never asserted. |
| I11 | A replay stops at the end offsets taken when it starts, commits nothing, republishes only the newest copy per `event_id`, leaves other reasons, and refuses `rejected_by_the_database` unless asked. | **Proved:** `EnvelopeConsumerTest.replayRepublishesTheNewestCopyOnceAndReplayedOrphansNeverWait` (one copy per event, other reasons left, `rejected_by_the_database` refused); `…replayTakesTheNewestCopyAcrossReasonsAndCarriesOnlyTheResultItRecorded` (newest across reasons). **Not proved:** stopping at the end offsets taken at the start, committing nothing, and choosing the newest by timestamp would not fail any test if deleted: nothing is written to the DLQ during a read, the replay seeks explicitly, and the copies compared are identical. |
| I12 | The WARN fires under intermittent S5 failures. | **Proved:** `EnvelopeConsumerTest.outagesAfterWaitingKeepTheWaitWarnGoing` (one S4 answer, then only outages: the WARN still fires, which it does only if failed attempts after S4 count as waiting); `…longWaitsAreWarnedEvenWhilePostgresKeepsFailing` (the configured interval). |
| I13 | A block resolved before its SMS goes is not sent (S2r), and is counted. | **Proved:** `SmsOrderingTest.blockResolvedBeforeItsSmsIsNotSent` (an unblocked block is not sent, and counted). **Not proved:** the "decided again" half of `resolved` (a later decision that is not DECLINE) is never produced; deleting it would not fail any test. |
| I14 | The budget survives a change of owner. After a revocation, and after a crash with no revocation while the partition is tripped or waiting, the new owner resumes `spent` from the committed metadata: at most one re-read interval of waiting lost (W-e: 500 ms plus at most the thread's current batch), and the unowned time drained. A lost partition commits nothing. A future `at` is clamped to zero [2-M2] [3-m1] [3-m3] [4-m3]. (Restored to the draft-5 wording, amendment 1, except that draft 5's "at most 500 ms" contradicted W-e's own bound and now cites it [A1-m4].) | **Proved:** `EnvelopeConsumerTest.crashedOwnersBudgetsAreResumedFromTheirLastCheckpoint` (crash: checkpoints on re-reads, the resume, and the loss bound: the time from the last checkpoint to the crash at most 1.5 s, W-e's 500 ms re-read interval and a one-record batch with 1 s allowed for scheduling); `WaitBudgetTest.checkpointsFromTheFutureAreClamped…`; `WaitBudgetTest.theCheckpointCarriesWhatIsSpentAndNewOwnersDrainTheTimeSince` (the unowned time drained). `EnvelopeConsumerTest.theRevocationCommitCarriesWaitingNoOtherCommitDoes` (the revocation commit: close() during a slow attempt makes the batch's commit fail, so only the revocation carries that waiting; deleting it leaves the checkpoint short). **Not proved:** a crash while the partition is tripped is never produced, and deleting the lost path's absence of a commit (`onPartitionsLost`) would not be noticed by any test. |
| I14b | A crash between a dead-letter and its commit may leave a second DLQ copy, which a replay republishes once. | **In part.** `EnvelopeConsumerTest.replayRepublishes…` (two copies, one republish). **Not proved:** the crash between a dead letter and its commit is never produced. |
| I15 | A header-less intent is S4 with `kept` null, and is dead-lettered at the bound if its B never appears. | **Proved:** `SmsOrderingTest.headerlessIntentWaitsRatherThanBeingJudgedPermanent` (S4). **Not proved:** deleting the CASE that makes `kept` null without a header would not fail it: `kept` is then false, which classifies the same. The bound is I7's. |
| I16 | S0: an intent whose B row belongs to another account is dead-lettered and never sends. | **Proved:** `SmsOrderingTest.intentForAnotherAccountsBlockIsNeverSent` (S0, never sends); the dead letter at the consumer: I3a's consumer test (`not_the_kept_decision`). |
| (wire) | The intent's record carries `fs-transaction-id` on Kafka. | `KafkaSpoolChaosTest.theSmsIntentCarriesItsTransactionOnTheWire` |
| (malformed) | Replaced by I19 (amendment 1). The earlier row claimed this for every unreadable payload; it held for three fields of one handler (implementation-2, M3). | I19 |
| I17 | A record that fails in F4 (12.2) is retried on the backoff and dead-lettered once the bound of 12.3 is reached, as `handler_failure`, or as `outcome_unrecorded` if a provider result is kept (12.4); the record behind it is then handled. The retry state survives a change of owner, on a partition whose budget is empty too (W-e). An S4 answer does not clear it; F1 failures neither use up nor reset it. If the dead-letter send fails at the bound, the state stands and the next attempt that fails in F4 dead-letters the record; if the dead letter is too large, I27 applies. | **Proved:** `EnvelopeConsumerTest.recordsThatKeepFailingAreDeadLetteredAtTheBoundAndTheNextIsHandled` (the bound, `handler_failure`, the next record handled); `…theRetryStateSurvivesChangesOfOwnerWhenNothingIsSpent` (carried across owners with nothing spent); `…recordsWaitingAndFailingInTurnReachTheBound` (S4 does not clear it); `…outagesBetweenOwnFailuresNeitherCountTowardsTheBoundNorClearIt` (F1 neither counts nor clears); `HeadRetryTest` (the clock's arithmetic, F1 time excluded); `…recordsThatKeepFailingAfterTheirSendLeaveAsOutcomeUnrecordedWithTheResult` (`outcome_unrecorded` at the bound). **Not proved:** deleting the consumer's `failedOutage` or `attemptStarts` call would not fail any test, because the consumer tests that mix F1 and F4 use a zero time bound, so "F1 adds no F4 time" is proved for the clock but not for the consumer (implementation-3 R-m1); "on the backoff" (the 2 s bound alone meets the timing assertion), and the next F4 attempt dead-lettering after a failed dead-letter send, which is never produced. |
| I18 | An F1 failure is retried without bound and without a dead letter. Failed attempts are counted as 12.6 defines (`fs_notify_retries_total`), and a head that has failed (F1 or F4) for 1 minute of wall time in a row is warned at WARN, and every minute after, naming topic, partition, offset, class and the last exception [A2-m7]. | **Proved:** `EnvelopeConsumerTest.outagesAreRetriedWithoutBoundAndCounted` (no dead letter with a bound of two; six retries counted as outages). **Not proved:** the 1-minute failing WARN; deleting it would not fail any test. |
| I19 | Every field a handler reads from the record is validated before any side effect: an envelope whose `payload` is absent or not an object, an SMS intent with any required field absent or unreadable (12.1), a webhook payload with any required field absent or unreadable, and a replayed record whose `fs-kept-result` does not parse are each dead-lettered as `malformed_envelope`, and nothing is sent, issued or recorded. | **Proved:** `SmsOrderingTest.everyFieldTheSenderReadsIsValidatedBeforeAnythingIsDone` (ten field groups, nothing done); `EnvelopeConsumerTest.envelopesWithoutAnObjectPayloadAreMalformed` (payload absent, null, an array, a string). **Not proved:** deleting the webhook payload's validation, or the check of a replayed `fs-kept-result`, would not fail any test. |
| I20 | Once the provider has been called for a record, its result is kept and committed before the outcome is written: SENT when the reply was read and accepted, FAILED otherwise, a 2xx reply that cannot be read and any unchecked exception from the adapter included. A re-read of that record, on this owner or the next, writes the kept outcome and never calls the provider again, whatever class the failed outcome write was; exactly one send is made. A dead letter after a send is `outcome_unrecorded` and carries R; replayed, it records R and sends nothing. | **Proved:** `SmsOrderingTest.gatewaysThatThrowUncheckedAreFailedOnceAndNeverResent`; `…failedOutcomeWritesKeepTheResultAndTheReReadWritesItWithoutSending`; `EnvelopeConsumerTest.keptResultsAreWrittenOnReReadsAndAcrossOwnersAndNeverSentAgain` (the result in the checkpoint, written by the next owner, one send); `outcome_unrecorded` with R and its replay: I17's and I28's tests. **Not proved:** deleting `AfricasTalkingGateway`'s catch of an unreadable 2xx reply would not fail `…providerRepliesThatCannotBeReadAreFailedOnceAndNeverResent`, because the sender's catch gives the same result; only an outage is produced for "whatever class"; the commit-before-write order and the wiring lambda that passes the kept result and keeper are by review. |
| I21 | A malformed `fs-transaction-id` (5.1) with B visible is classified exactly as with a valid header; with B absent it is dead-lettered at once as `malformed_header` (S3m), and nothing is sent, issued or recorded; it is logged and counted. An absent header is S4 (I15). A short form such as `1-2-3-4-5` is malformed. | **Proved:** `EnvelopeConsumerTest.theTransactionHeadersStateReachesTheHandlerAndMalformedOnesAreCountedOnce` (through the real consumer; counted once); `SmsOrderingTest.malformedTransactionHeadersDecideNoSend` (S3m with nothing done; sent once B is visible); `CheckpointTest.theTransactionHeaderHasThreeStates`; `EnvelopeConsumerTest.everyDeadLetterReasonKeepsTheRecordsHeaders` (`malformed_header`). **Not proved:** the WARN line; deleting it would not fail any test. |
| I22 | A committed offset that has fallen below the log start is never skipped silently: the reset to the log start is logged at ERROR with the number of offsets skipped, and counted; the old head's retry state and kept result are dropped. An offset above the log end with no divergent offset reported is logged at ERROR and resumed from the log end, uncounted; a `LogTruncationException` resumes from its divergent offset, or from the log end for a partition it names without one, and every commit carries the leader epoch so that one is reported [A3-m3] [A3-m4]. A partition the client reports as having no committed offset starts at the log start; a failed lookup never counts as "no offset" [A1-m2]. | **Proved:** `EnvelopeConsumerTest.offsetsBelowTheLogStartAreCountedNotSkippedSilently` (five counted, the rest handled); a new group starting at the log start (every consumer test); `…resetsDropTheOldHeadsRetryState` (an inherited count does not survive a reset). **Not proved:** the drop is made twice, by the reset handler and the head check, so deleting either alone would not fail any test; the kept-result drop, the ERROR line, a failed lookup never read as "no offset", the leader epoch on commits, and the log-end and divergence branches (an unclean election cannot be produced on one broker) would not fail any test if deleted. |
| I23 | Each counter and the gauge of 12.6 moves exactly as 12.6 defines its count. | **In part.** Asserted in I10's, I17's, I18's, I21's, I22's, I26's, I27's and I28's tests: `fs_notify_dead_letters_total`, `fs_notify_retries_total`, `fs_notify_malformed_headers_total`, `fs_notify_offsets_skipped_total`, `fs_notify_consumer_errors_total`, `fs_notify_consumer_running` (its 0 only), `fs_notify_dropped_total`, `fs_notify_ignored_kept_results_total`. **Not proved:** `fs_notify_wait_warnings_total`, `fs_notify_sms_outcome_unknown_total`, `fs_notify_resolved_before_send_total`, `fs_notify_webhook_delivery_running` and `fs_notify_head_failing_seconds`. |
| I24 | Every failure of an attempt is in exactly one class of 12.2, the cause chain walked, and the class decides the action. | **Proved:** `FailureClassTest` (every SQLSTATE and exception type 12.2 names, the chain rules, the dead-letter send classes); the class deciding the action: I10's, I17's and I18's consumer tests. **Not proved:** the cause chain's cycle guard; deleting it would not fail any test. |
| I25 | The SMS is rendered before a verification is issued: a rendering failure leaves no verification behind [A1-m6]. | `SmsOrderingTest.theMessageIsRenderedBeforeItsLinkIsIssued` (a vault contact without its masked account: no verification left behind; fixed, the SMS carries the link) |
| I26 | Every external call in an attempt is bounded by a timeout, and a batch by its time budget (12.3). An `Error` on the poll thread marks the consumer stopping, seeks every partition of the batch back to its first unhandled offset, commits those offsets only if every seek succeeded, then logs, counts, sets `fs_notify_consumer_running` to 0 and closes; the next owner re-reads the record that threw and every unhandled record after it. An `Error` on the webhook delivery executor stops it, leaving deliveries `PENDING`. No health indicator reports either stop [A2-M1] [A3-m1] [A3-m2] [A3-n6]. | **Proved:** `EnvelopeConsumerTest.anErrorMidBatchSkipsNoRecordAndStopsOnlyThatMember` (the commit before closing: the next owner handles the record that threw and those after it; the counter, the gauge, the close); `…theBatchBudgetKeepsEachMemberWithinItsPollInterval` (without the budget the member is expelled and records are handled twice); `FailureClassTest` (`57014` F4, `08006` F1). **Not proved:** (known partial, owner's decision) the seek in the stop and the commit-only-if-every-seek-succeeded gate, the ERROR line, a statement actually timing out, the vault's, provider's, producer's and commit timeouts, the webhook executor's `Error` rule, the health endpoint staying UP, a reset's lookup failing without ending the thread, the stopping flag, the rule that the revoke listener commits nothing off the poll thread while it runs (with `close()`'s longer wait), and the seek back across several partitions: deleting any of them would not fail any test (implementation-3 R-m2). `…batchesPastTheirTimeBudgetPollAgainWithoutSkipping` passes with the budget deleted and proves only the seek back. |
| I27 | A dead-letter send that fails with `RecordTooLargeException` is retried without `fs-dlq-error`; if it still fails, the record is F4 and, at the bound, logged at ERROR in full (with N and R if a result is kept), counted as dropped, and committed past. Every other dead-letter send failure is F1 and never drops a record [A2-M2] [A2-n7]. | **Proved:** `EnvelopeConsumerTest.deadLettersTooLargeAreResentWithoutTheirErrorThenDroppedAtTheBound` (re-sent without `fs-dlq-error`; dropped and counted; the next handled); `…droppedRecordsAreLoggedInFull` (the line's content: offset, key, headers, digest, length, N, kept result); `FailureClassTest.deadLetterSendFailuresAreOutagesExceptRecordsThatAreTooLarge`. **Not proved:** (known partial, owner's decision) the consumer's outage branch for a failed dead-letter send, a real send timing out, and the line's ERROR level: deleting any of them would not fail any test. |
| I28 | The replay tool refuses `rejected_by_the_database` unless `--allow-rejected` is given; it replays `outcome_unrecorded` with `--reason`, carrying `fs-dlq-result` as `fs-kept-result`, replacing any it already carries, and the consumer records that result without sending; a `fs-kept-result` on a record that was not replayed is ignored and counted [A2-m7] [A3-m6]. | **Proved:** `EnvelopeConsumerTest.replayingAnOutcomeUnrecordedRecordsItsResultAndSendsNothing` (the result republished and recorded, no second send; a header on a record not replayed ignored and counted); `…replayTakesTheNewestCopyAcrossReasonsAndCarriesOnlyTheResultItRecorded` (one `fs-kept-result`, replacing a stale one; none when the dead letter recorded none); refusal: `…replayRepublishesTheNewestCopyOnce…`. **Not proved:** the command line's `--allow-rejected` and `--reason` parsing. |
| I29 | The checkpoint metadata follows W-e2 exactly: `v3` written and read back field for field, a `v2` line read with no retry state and no kept result, and anything else read as no metadata with a WARN; a reference over 128 characters is cut to 127 and `~`, and an empty one is recorded as null [A2-m7]. | **Proved:** `CheckpointTest` (v3 written and read back, v2 read, anything else none, references cut at 128 code points with the mark, empty as null, U+0000 replaced); `EnvelopeConsumerTest.committedMetadataThatDoesNotParseIsWarnedAndReadAsNone` (the WARN). **Not proved:** the overflow guards on numbers past `long` or `int`. |

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
    `outcome_unrecorded`; drops a record whose dead letter is too large to send; stops on an
    `Error`; and warns (12.5) [A3-n1].
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
17. Each transaction the consumers open sets `statement_timeout`; the vault data source gets its
    socket and statement timeouts; the KMS client and the dead-letter producer get the timeouts of
    12.3; a batch gets its time budget (12.3).
18. The `KmsClient` contract gains `KmsUnavailableException`, a non-permanent `VaultException`,
    for an unreachable, timed-out, throttled or failing service, and a 5 s limit per call. The only
    implementation in this repository is the tests' in-memory KMS; M9's vendor binding must honour
    both (12.2, 12.3) [A2-m4] [A3-n3].
19. No health indicator for the consumers; `fs_notify_consumer_running` and
    `fs_notify_webhook_delivery_running` report a stop, and the webhook delivery executor applies
    its own `Error` rule (12.2) [A3-m1] [A3-m2].
20. Every commit carries the record's leader epoch (12.5); the replay tool replaces
    `fs-kept-result`; the consumer honours it only on replayed records (12.1, 12.4).
21. Dead letters after a send carry `fs-dlq-result`; the replay tool republishes it as
    `fs-kept-result`, which the consumer honours (12.4, D-c).
22. ADR 0056 records amendment 1; the test claims of section 8 are corrected.

## 12. Failures of an attempt, and the retry bound (amendment 1)

The second implementation review found that the contract treated every unchecked exception as
transient, so a record that failed the same way every time was re-read for ever and stalled its
partition until retention dropped what was queued behind it (implementation-2, M1, m6). This section
replaces that assumption. It applies to both consumers built on `EnvelopeConsumer`, and its `Error`
rule to every thread those consumers own.

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
  input (5.1, S3m), not a reason to refuse a send. On a replayed record (one carrying
  `fs-replayed`), a `fs-kept-result` header (12.4) is validated: exactly one value, matching W-e2's
  grammar for `result` and not `none`; repeated identical values count as one. Anything else is
  `malformed_envelope` [A3-m6]. On a record without `fs-replayed` the header is ignored, logged at
  WARN and counted (12.4).

A field the frozen schema allows but the handler does not read is not validated.

### 12.2 Failure classes (change)

Every failed attempt is in exactly one class. Two rules decide which [A2-n2]:

1. **Which exception.** An attempt whose dead-letter send failed is classified by the send's
   exception, not by the failure that led to the dead letter.
2. **Walking the chain.** The consumer walks the cause chain from the outermost exception inward.
   An exception that carries its own classification decides at once, before its causes:
   `MalformedPayloadException` (F2); a `VaultException` that is permanent (F3);
   `KmsUnavailableException` (F1). Otherwise the first exception in the chain that matches a row
   of the table, read top to bottom, decides; if none matches, the class is F4.

| Class | What | Action |
|---|---|---|
| **F1 outage** | A shared dependency is unavailable. SQL: SQLSTATE class `08` except `08P01`; class `28` (credentials, as after a rotation); `3D000`; `25006` (a read-only node, as after a failover); `40001`, `40P01`; `53100`, `53200`, `53300` (disk, memory, connections); `57P01`, `57P02`, `57P03`; any `SQLTransientConnectionException` or `SQLRecoverableException`, whatever its SQLSTATE (a connection-pool timeout has none). KMS: `KmsUnavailableException` (unreachable, timed out, throttled, or a server error), which the `KmsClient` contract gains [A2-m4]. Kafka: **any failure of the dead-letter send except `RecordTooLargeException`**, the send's own timeout, authorisation errors and an invalid DLQ topic included [A2-M2]. | Retried on the backoff **without bound**. Neutral time (W-b). Warned and counted (12.5, 12.6). |
| **F2 malformed** | 12.1 refused the record, or the header is malformed with B absent (S3m). | Dead-lettered at once as `malformed_envelope`, or `malformed_header` for S3m. |
| **F3 permanent** | SQLSTATE class `22` (data) or `23` (integrity); a permanent `VaultException`; S0 and S3. | Dead-lettered at once: `rejected_by_the_database`, `permanent_vault_failure`, `not_the_kept_decision`; `outcome_unrecorded` instead while a provider result is kept (12.4). |
| **F4 may be the record's own** | Everything else, including: any other SQLSTATE (`08P01`, class `42`, `53400`, `57014`, class `58`, class `XX`, a null SQLSTATE on an exception that is neither transient type above); a `VaultException` that is neither permanent nor caused by an F1 failure, such as a key this instance does not hold yet (ADR 0069); any other KMS failure; the dead-letter send failing with `RecordTooLargeException`; any other `RuntimeException`. | Retried on the backoff **up to the bound of 12.3**, then dead-lettered as `handler_failure`, or `outcome_unrecorded` while a provider result is kept. |

- **A dead letter that is too large.** If the dead-letter send fails with `RecordTooLargeException`,
  it is retried at once without `fs-dlq-error`, the only header whose size the consumer does not
  control. If that also fails, the attempt is F4. At the bound of 12.3 the record is logged at ERROR
  in full (topic, partition, offset, key, every header, the value's SHA-256 and length, and N with
  the kept result if one is held), counted as dropped (12.6), and committed past. This is the only
  case in which a record leaves without being handled or dead-lettered [A2-M2] [A2-n7].
- **An `Error`** (a `Throwable` that is not an `Exception`) is not classified [A1-m1] [A2-M1].
  - **On the poll thread**, which owns the `KafkaConsumer`, in order:
    1. it marks the consumer as stopping, so that the revocation listener commits nothing during
       the close [A3-n6];
    2. it seeks every partition of the batch in hand back to its first unhandled offset (W-a's "no
       skipped records"), the record that threw included;
    3. if every seek succeeded, it commits those offsets synchronously, with each partition's
       metadata (W-e); if a seek failed, it commits nothing, and the group resumes from the last
       commit, which is never past an unhandled record;
    4. it logs the `Error` at ERROR, counts it, sets `fs_notify_consumer_running` to 0 (12.6), and
       closes the consumer, releasing its partitions to the group. It is not restarted by itself.
  - **On the webhook delivery executor**, which owns no consumer: the task that threw is abandoned,
    the executor logs the `Error` at ERROR, counts it, sets `fs_notify_webhook_delivery_running` to
    0 and accepts no further task. Deliveries already queued stay `PENDING` in `webhook_deliveries`,
    the task that threw included (its transaction rolls back), and another instance's executor
    delivers them through the shared due query, or this one after a restart; the consumer keeps
    enqueuing. A POST already sent before the `Error` may be sent again, which integrators
    deduplicate on `event_id`, and a row past its retry policy is dead-lettered at its next failed
    attempt, as today [A3-m2] [A3-D-n4].
  - **No health indicator** reports either stop: the root `/actuator/health` is the liveness and
    uptime probe (ADR 0011, ADR 0014, OPS-OBS-05), and a stopped consumer must not make every
    instance report DOWN and restart. The gauges and the counter of 12.6 are the signals, and M9
    alerts on them [A3-m1].
  - An `Error` caused by one record stops each member that reads it, in turn, until every member of
    the group has stopped; that is loud by design, and the operator removes the record [A3-n6].
- Once the provider has been called for the head record, a failure does not repeat the call: 12.4.

### 12.3 The bound on F4, and timeouts (change)

- For the record at the head of a partition, the consumer keeps **`f4_count`**, the number of
  attempts on that record that failed in F4, and **`f4_ms`**, the wall time since that record's
  first F4 failure minus every interval from an F1 failure to the next attempt on it.
- The state is cleared **only when the head changes**: the record is handled, dead-lettered,
  dropped, or skipped by a reset (12.5). An S4 answer does not clear it, and an F1 failure neither
  adds to it nor clears it [A1-m3].
- **The bound is checked only at the end of an attempt that failed in F4** [A2-n3]: if `f4_count` is
  then at least **5** and `f4_ms` at least **10 minutes**, the record is dead-lettered as
  `handler_failure` (or `outcome_unrecorded`, 12.4), with `fs-dlq-error` naming the exception class
  and message, and the record behind it is handled. An S4 answer or an F1 failure never dead-letters
  a record under this bound, so a waiting record is governed by the budget (W-c) until it fails in
  F4. Both values are ASSUMED and configurable. With the backoff capped at `MAX_BACKOFF_MS` (30 s),
  10 minutes of `f4_ms` is about 25 F4 attempts.
- If the dead-letter send at the bound fails, that attempt is classified by the send's exception
  (12.2, rule 1); the F4 increment it had already made stands, and the next attempt that fails in F4
  dead-letters the record again [A1-m3].
- Ten minutes outlasts a rolling vault-key rotation (ADR 0069) and a blip; a failure that outlasts
  it is treated as the record's own, and the record can be replayed once the cause is fixed (D-g).
- The state is checkpointed after every failed attempt and survives a change of owner (W-e, W-e2).
  The unowned time between owners is not added to `f4_ms`.
- **Every external call in an attempt has a timeout**, so a hang becomes a failure that can be
  classified [A1-m1] [A1-m7] [A2-m3]:
  - **PostgreSQL through the application pool** (shared with the API): each transaction the
    consumers open sets `SET LOCAL statement_timeout = 5s` (ASSUMED), below the pool's socket
    timeout (`socketTimeout: 10` s), so a slow statement fails as `57014` (F4), not as a socket
    timeout (`08006`, F1). The API's own transactions are unaffected.
  - **The vault database** (its own data source): `socketTimeout` 10 s and `statement_timeout` 5 s
    set as connection options, so they apply to every vault statement, in a transaction or not.
  - **A hung statement** is bounded by the socket timeout (10 s) even if `statement_timeout` is
    not reached; the vault data source also sets `connectTimeout` and `loginTimeout` to 5 s
    [A3-m5].
  - **The SMS provider:** 3 s to connect and 5 s for the request, as today.
  - **The KMS client:** at most 5 s per call; a timeout is `KmsUnavailableException`.
  - **The dead-letter send:** the producer sets `request.timeout.ms` 15 s, `linger.ms` 0,
    `delivery.timeout.ms` 30 s (the client requires `delivery.timeout.ms` ≥ `linger.ms` +
    `request.timeout.ms`) and `max.block.ms` 10 s, and the consumer waits for the send's future for
    35 s [A2-n6] [A3-m5].
  - **Commits:** every `commitSync`, the one that keeps a provider result and the end-of-batch one,
    passes a timeout of 10 s. A failed commit is not a failed attempt: the commit that keeps a
    result is retried as 12.4 says, and a failed end-of-batch commit is handled by W-a's "no
    skipped records" [A3-m5] [A3-D-m1].
  - **Webhook delivery:** its existing per-request timeout.
- **A batch has a time budget of 60 s** (ASSUMED) [A2-m3]. When it is spent, the consumer stops
  handling the batch, seeks every partition with unhandled records back to its first unhandled
  offset (W-a), commits what is done, and polls again. One attempt is bounded by the timeouts above:
  in the worst case two statements and the outcome write (10 s each, the socket timeout), a vault
  read (10 s), a KMS call (5 s), the provider call (8 s), the commit that keeps the result (10 s)
  and a dead-letter send (10 s to block plus 35 s), about 110 s. The time between polls is at most
  the budget, one attempt and the end-of-batch commit (10 s), about 180 s, below the consumers'
  `max.poll.interval.ms` (300 s, the client default) [A3-m5].

### 12.4 One send per record: the provider's result is kept (change)

- **The SMS is rendered before the verification is issued** [A1-m6] [A2-m2]. The message the
  consumer is about to send, with a link or without, is first rendered as a trial: with a link, the
  trial uses a placeholder exactly as long as the longest link the settings can produce
  (`MAX_LINK_LENGTH`), so the one-segment GSM-7 check gives the same answer as for the real link.
  The trial's text is discarded and never sent. Only if the trial succeeds is the verification
  issued and the real message rendered. A rendering failure therefore leaves no verification
  behind. A rendering failure that today records a FAILED row (`IllegalArgumentException`) still
  does.
- Once `gateway.send` has been called for head X, it ends in a **provider result** R: **SENT**
  with the provider's reference, when the reply was read and accepted; or **FAILED**, for anything
  else, a 2xx reply that cannot be read and any unchecked exception from the adapter included
  (`AfricasTalkingGateway` turns an unreadable reply into `IOException`; `CustomerSmsSender`
  catches any exception from the call). R also holds the locale the SMS was rendered in and
  whether it carried a link. The reference is at most 128 code points, the limit of
  `customer_notifications.provider_reference` (`char_length`): a longer one is cut to 127 code
  points followed by `~`, in R and in the row alike [A2-m9] [A3-n4]. The `~` marks a cut by
  convention only; a reference that already ends in `~` is kept as it is.
- **FAILED means "not known to be accepted by the provider"**, for every reader [A1-m6]. A FAILED R
  whose call may have reached the provider without a readable acceptance may have been delivered:
  a 2xx reply that could not be read, a request that timed out after it was sent, or an exception
  other than `IOException`. The adapter reports the first two as `ProviderReplyUnknownException`, a
  subtype of `IOException`. Such an R is logged at ERROR with N and counted once, when it is kept
  (12.6) [A2-n4] [A3-n2]. No reader may treat FAILED as safe to send again; today only `classify` reads
  it, as S1.
- **The order within the attempt** [A2-m1]: the provider call returns or throws; R is kept in the
  partition's state; the consumer commits `OffsetAndMetadata(X, epoch, m)` for X's partition alone,
  synchronously, at offset X, the head record's offset (not the consumer's position, which is past
  X mid-batch), with X's leader epoch and R in m (W-e2); then the outcome is written. If that
  commit fails, the outcome write still proceeds, and a re-read of X holding R writes R without
  waiting for it. The commit carrying R is retried with every later commit of the partition until
  one succeeds or the head changes [A3-n7] [A3-D-m1] [A3-D-n2].
- **A re-read of X that holds R**, on this owner or the next (from the metadata), or a replayed
  copy of X that carries a valid `fs-kept-result` (12.1, D-c), **writes R and never calls the
  provider again**. A result from the header is a kept result in every respect, checkpointed like
  one, and logged at INFO with N and R. A `fs-kept-result` on a record without `fs-replayed` is
  ignored, logged at WARN and counted, so it cannot suppress a first send [A3-m6]. It skips the vault read, the rendering and `issue`. It runs the classification first:
  an outcome already visible is S1; otherwise, with B visible, it writes R whichever state the
  statement shows (S2, or S2r if the block was resolved meanwhile) [A2-m2].
- If the outcome cannot be written, the attempt fails in its class: F1 is retried without bound,
  each retry only writing R; F4 is retried up to the bound. Either way no second send is made.
- **Any dead letter written while R is kept** is `outcome_unrecorded`, whatever the cause (F3, or the
  F4 bound). The copy carries R in the `fs-dlq-result` header, in W-e2's `result` form. Replaying it
  (D-c) records the outcome and sends nothing, so the outcome of an `outcome_unrecorded` intent is
  recorded once its cause is fixed [A2-m9].
- What remains open is a crash or an eviction after the provider call and before the commit that
  carries R is acknowledged: 13.1.

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
      ERROR names its N and R, whose outcome is now never recorded;
    - **above the log end**, with no divergent offset reported: it logs at ERROR, counts nothing,
      and seeks to the log end. It does **not** rewind to the log start, which would re-read the
      whole retention and re-send [A2-m5].
  - `LogTruncationException` (the log diverged after an unclean election, wherever the divergence
    lies): the consumer seeks each named partition to the divergent offset the exception reports,
    logs at ERROR, and counts nothing. A partition the exception names without a divergent offset is
    handled as an offset above the log end: ERROR, and a seek to the log end [A3-D-m2]. So that the client can detect a divergence after a change of
    owner, **every commit carries a leader epoch**, as `OffsetAndMetadata` allows: the epoch of the
    record at the committed offset when the consumer has read it, otherwise of the last record read
    on the partition, otherwise the epoch `committed()` returned; with none of these, the commit
    carries none. The revoke commit, at `position(p)`, uses the last record read [A3-m3]
    [A3-D-n2].
  - In every case the old head's retry state and kept result are dropped, the partition is resumed
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
| `fs_notify_retries_total` | counter | `group`, `class` (`outage`, `may_be_own`) | each failed attempt after which the record will be re-read. An attempt that dead-letters or drops the record is not counted here |
| `fs_notify_dropped_total` | counter | `group` | each record committed past because its dead letter was too large (12.2) |
| `fs_notify_wait_warnings_total` | counter | `group` | each W-d WARN |
| `fs_notify_resolved_before_send_total` | counter | `group` | each S2r answer that commits past a record without a kept result |
| `fs_notify_malformed_headers_total` | counter | `group` | each record with a malformed `fs-transaction-id`, once per (partition, offset) on this owner, however often it is re-read |
| `fs_notify_sms_outcome_unknown_total` | counter | `group` | each FAILED provider result that may have been delivered (12.4), once, when it is kept |
| `fs_notify_offsets_skipped_total` | counter | `group` | offsets below the log start skipped by a reset (12.5), as the difference between the log start and the committed offset |
| `fs_notify_consumer_errors_total` | counter | `group` | each `Error` that stopped a consumer thread (12.2) |
| `fs_notify_consumer_running` | gauge | `group` | 1 while the consumer's poll thread runs, 0 once it has stopped |
| `fs_notify_webhook_delivery_running` | gauge | none | 1 while the webhook delivery executor accepts tasks, 0 once an `Error` stopped it |
| `fs_notify_ignored_kept_results_total` | counter | `group` | each record without `fs-replayed` that carried `fs-kept-result` (12.4) |
| `fs_notify_head_failing_seconds` | gauge | `group` | at scrape, over the owned partitions, the longest time since the current head's first failed attempt (F1 or F4) in its current run of failures; a run ends when the head changes or an attempt on it succeeds. 0 when no head is failing |

## 13. Known gaps (amendment 1)

### 13.1 A crash between the provider call and the commit that keeps its result [impl-2 m7] [A1-M1] [A2-m1]

12.4 keeps the provider's result for a record and commits it before the outcome is written, so a
failed outcome write no longer sends again. One window remains: the member crashes or is expelled
after the provider call returns and before the commit carrying R is acknowledged; an `Error` in
that interval stops the consumer the same way (12.2) [A3-n6]. If that commit
fails, the window stays open until a later commit carrying R succeeds, which is retried before the
record is re-read. The next owner then finds B and no outcome, with no kept result, and **sends a
second SMS**. `VerificationService.issue` runs once per block, so the second SMS carries no link.

- Normally the window is one provider reply and one `commitSync`, well under a second; it is longer
  only while commits to the group coordinator fail.
- Not closed in M6. It is carried as **`PB-77`** (claimed in `docs/parallel/M6_updates.md`). Two
  designs are on record: a `SENDING` row written before the provider call and read as "outcome
  unknown, do not send", and the transactional outbox of section 9.

### 13.2 A provider outage ends the notice [A3-n9]

A provider call that fails, an outage of the provider included, records FAILED (12.4), and the
intent is committed past: the customer is never told. That is today's behaviour, unchanged by this
amendment, and FAILED rows are visible to an operator. Retrying a FAILED send safely needs to know
the provider did not accept it. For most failures 12.4's "not known to be accepted" cannot say so;
a failure to connect can, because the request never left, and that is where `PB-79` can start
[A3-D-n3]. Carried as
**`PB-79`** (claimed in `docs/parallel/M6_updates.md`), together with an alert on the rate of FAILED
outcomes (M9).
