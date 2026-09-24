# Decision facts: who writes what, in which order, and what readers may assume

Status: **reviewed sound** (2026-09-24): draft 5 was reviewed sound, 0 BLOCKER, 0 MAJOR
(`-contract-5.md`). Its 4 MINOR and 4 NIT, all wording or lifecycle rules, are applied below and
cited `[5-…]`. This is the ordering contract the owner asked for
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
| S4 | no `block`; `kept` false or null | late or lost: W2 has not written T's kept decision | **wait** (section 6); a replayed record does not wait (section 7) |
| S5 | the statement fails transiently | PostgreSQL is unavailable | retry on the exponential backoff; the wait is paused, not reset |

Why S3 is permanent: `kept` true means T's kept decision is visible. Had that decision auto-blocked,
its B would be derived from T and so equal this B, and it would be visible in the same snapshot (G2,
5.1). A kept decision from before this change with a random B had its own intent, which found its
own B and was sent. The later intent with a derived B lands in S3, which is correct, because the
customer was already told. The reason is named for what is certain: this intent is not the kept
decision's [NIT].

## 6. The bounded wait (S4)

- **W-a. (change) Pausing a partition, never the thread** [4-m2] [5-m1]. Every re-read is paused
  per partition: an S4 wait, a grace re-read, S5, and any other transient RETRY (vault, provider,
  DLQ send). The partition whose head record must be re-read is sought back to that record and
  paused with `KafkaConsumer.pause`. It is resumed when its own re-read is due:
  - 500 ms for S4 and grace re-reads;
  - its own exponential backoff, up to `MAX_BACKOFF_MS`, for S5 and other transient failures.

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
  classification of the head record: the single statement of 5.1, plus the dead-letter send where
  one follows. It succeeds or fails (S5, or the DLQ send fails). It ends when the statement returns,
  or when the DLQ send is acknowledged. The SMS send and the outcome write of S2 come after the
  attempt, and their time is draining [5-n4].
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
  - If the grace re-read fails (S5), the time is neutral, and the re-read is repeated on the
    backoff until it succeeds.
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
  - **Every commit** for a partition whose `spent` is above zero carries
    `m = "fs-wait:v2 offset=<committed offset> spent_ms=<n> at=<epoch ms>"`. That includes the
    commit of X+1 after a dead-letter, and commits while the partition is tripped.
  - While a partition waits at head X, the consumer also checkpoints `OffsetAndMetadata(X, m)` at the
    first S4 answer and after every attempt, S5 included, so that `at` stays fresh during an
    outage [5-n1]. Where `done` already holds the same partition, the two
    are merged into one entry in the same `commitSync` [4 task 3]. Committing X moves nothing,
    because X is already the position.
  - `onPartitionsRevoked` commits `OffsetAndMetadata(position(p), m)` synchronously for every
    revoked partition whose `spent` is above zero. It uses `position(p)`, which is right even if an
    earlier `commitSync` failed [4-n3].
  - `onPartitionsLost` is overridden: it commits nothing and drops the partitions' state.
  - `onPartitionsAssigned` reads the committed metadata. If its `offset` equals the committed
    offset, `spent` resumes from it, drained for `max(0, now − at)`. A future `at` (clock skew) is
    clamped to zero and logged at WARN.
  - Otherwise, including when there is no metadata, `spent` starts at zero.
  - Instances' clocks are assumed synchronised (NTP). With skew s, a takeover drains up to s × drain
    rate too much or too little, which is negligible at NTP skew.
  - A crash loses at most the waiting since the last checkpoint: one re-read interval, 500 ms plus
    at most the thread's current batch (W-a). The time until the
    new owner is assigned (up to the session timeout, 45 s by default) is draining, as W-b defines,
    so it lowers `spent` by at most 7.5 s. The bound still holds, because draining is part of the
    guarantee.
  - A commit made once `spent` has drained to zero carries no metadata.
- **W-f.** Records behind a waiting intent on its partition wait with it, within W-c2's limit, plus
  neutral time. Other partitions are not held up (W-a).
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
| I7 | The partition's budget: once waiting has filled `spent` to the configured bound, that record is dead-lettered as `parent_not_recorded`. A record whose first answer while tripped is S4 gets one grace re-read, and is dead-lettered if still S4, or sent if its parent arrived. Draining time as defined in W-b drains `spent` at the configured rate, neutral time does not, and no outcome resets it [5-m4]. |
| I7b | Orphans interleaved with valid intents (orphan, valid, orphan, valid, and so on) keep the partition within W-c2's limit: bound + t × drain rate of waiting in a window t, not k × bound [3-M1]. |
| I7c | An orphan that arrives after an idle period following a trip waits for what the idle time drained: the full bound after 60 minutes idle at the default rate [4-M1]. |
| I7d | While one partition waits, is tripped, or backs off after S5 or a RETRY, another partition's intents are handled without delay: the thread never sleeps (W-a) [5-m1]. |
| I7e | A partition paused on one member and moved to another: the first member's other partitions keep flowing and no `IllegalStateException` escapes; the second member re-reads at once, with `spent` resumed from the metadata [5-m2]. |
| I7f | An exception between `poll` and the end-of-batch commit skips no fetched record [5-n3]. |
| I8 | Neutral time, from a failed attempt (S5, or a failed DLQ send) to the next successful answer, neither adds to nor drains `spent`. A grace re-read that meets S5 is retried until it succeeds [4-m1] [4-n4]. |
| I9 | The classification is one statement. A test holds W2's transaction open, writing `decision_states(T, 1)` and B together, and sees S4 (never S3) until the commit, then S2. |
| I10 | A dead-lettered intent keeps its headers, for every reason. Replaying it republishes key, value and headers, plus `fs-replayed` incremented. Replayed after its parent arrived, it is sent; replayed before, it returns to the DLQ at once and never waits; its handling time drains (W-b) [5-m3]. |
| I11 | A replay stops at the end offsets taken when it starts, commits nothing, republishes only the newest copy per `event_id`, leaves other reasons for later runs, and refuses `rejected_by_the_database` unless asked. |
| I12 | The one-minute WARN fires under intermittent S5 failures. |
| I13 | A block that is resolved (unblocked, or its latest decision no longer DECLINE) before its SMS goes is not sent (S2r). |
| I14 | The budget survives a change of owner. After a revocation, and after a crash with no revocation while the partition is tripped or waiting, the new owner resumes `spent` from the committed metadata: at most 500 ms of waiting lost, and the unowned time drained. A lost partition commits nothing. A future `at` is clamped to zero [2-M2] [3-m1] [3-m3] [4-m3]. |
| I14b | A crash between a dead-letter send and the commit past it re-reads the record. A second DLQ copy may result, and a replay republishes it once [4-n4]. |
| I15 | A header-less intent (on the topic before deployment) is S4 with `kept` null, and is dead-lettered at the bound if its B never appears. |
| I16 | S0: an intent whose B row belongs to another account is dead-lettered as `not_the_kept_decision` and never sends. It is tested with a crafted intent [3-n5]. |

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
4. `CustomerSmsSender` classifies with one statement (5.1), takes link eligibility, and the locale
   it records on a SENT or FAILED row, from the REQUESTED row (5.2) [4-n2], and implements S0 to S5
   (5.3).
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
