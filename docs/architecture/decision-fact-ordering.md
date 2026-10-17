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

**Amendment 1, draft (2026-10-17): not yet reviewed.** It answers the second implementation review
(`-2026-10-17-implementation-2.md`: M1, M2, M3, m1, m2, m4, m6, m8, with m5 and m7) on the owner's
scope of the same day. Code follows only after this amendment is reviewed sound on its own. It
changes:

- **Section 12 (new): failures of an attempt.** Every failure is in exactly one class. Only an
  outage of a shared dependency is retried without bound; anything else that keeps failing is
  dead-lettered as `handler_failure` after a bound, and can be replayed. Everything the handler
  reads is validated before any side effect. A provider call that has started is never repeated
  by a retry. A reset across a retention gap is never silent. Each of these is counted (12.6).
- **5.1 and 5.3: a malformed `fs-transaction-id`** is told apart from an absent one (row S3m).
- **W-a, S5, D-a, D-g and section 11** are changed to match; section 9 lists what was rejected.
- **Section 8**: I7e, I8, I9 and I14 are restored to the wording reviewed sound in draft 5. The
  implementation round had narrowed them without saying so (implementation-2, m5); where a test
  proves less than the restored text, the row says so instead of narrowing the text. I10 and the
  "(malformed)" row claimed more than their tests showed (M3, m4) and are restated. New invariants
  I17 to I24 cover section 12.
- **Section 13 (new): known gaps**, starting with the window between a provider's acceptance and the
  recorded outcome (m7).

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
corruption, a foreign producer or a manual publish. It is logged at WARN with the partition and
offset, and counted (12.6). It never decides a send: S0 to S2 depend on B alone (5.3).

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
| S3m | no `block`; the header is **malformed** (5.1) | B is not visible, and which decision PostgreSQL kept cannot be read from the record (change, amendment 1) | **dead-letter at once**, reason `malformed_envelope`, `fs-dlq-error` naming the header; no send, no wait. A replay keeps the header (D-a): if B exists by then the intent is S2 and is sent, otherwise it returns to the DLQ at once (D-d) |
| S4 | no `block`; the header absent, or valid with `kept` false | late or lost: W2 has not written T's kept decision | **wait** (section 6); a replayed record does not wait (section 7) |
| S5 | the statement fails | PostgreSQL did not answer | the failure's class decides (section 12): an outage (F1) is retried on the backoff without bound; anything else that may be transient (F4) is retried up to the bound of 12.3. Either way the wait is paused, not reset |

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
  follows. It succeeds or fails (S5, or the DLQ send fails). It ends when the handler returns, or
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
  `fs-dlq-topic`, `fs-dlq-offset` and `fs-dlq-at`. This applies to **every** dead-letter reason:
  `not_the_kept_decision`, `parent_not_recorded`, `malformed_envelope`, `rejected_by_the_database`,
  `permanent_vault_failure` and `handler_failure`, in both consumers.
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
- **D-g. (change, amendment 1)** `malformed_envelope`, `permanent_vault_failure` and
  `handler_failure` (12.3) are replayed once their cause is fixed, with `--reason`. A replayed
  `handler_failure` whose cause persists returns to the DLQ after the bound of 12.3 again; it never
  loops without bound. The default reason of a replay stays `parent_not_recorded`.

## 8. Invariants, and the test that proves each

A test named here fails if the invariant is broken. Where a property is proved only in part, or
only by review, the table says so: the implementation review of `bd48557` found that an earlier
claim, "every invariant is tested", was not true.

| # | Invariant | Proved by |
|---|---|---|
| I1 | No SMS is sent, no verification is issued and no outcome is recorded while B is absent (S3, S4). | `SmsOrderingTest`: `nothingDoneFor` in the S3, S4 and I4b tests |
| I2 | Once an intent's outcome is visible, the intent is not sent again (S1). | `SmsOrderingTest.theSameSubmissionBlockingTwiceSendsOneSms`; `VerificationFlowTest.anIntentReReadAfterItsOutcomeWasRecordedIsNotSentAgain` |
| I3a | A transaction decided twice, first **not** blocking, then blocking, with the kept decision already written: the second intent is dead-lettered at once as `not_the_kept_decision`, never waits, and never sends. | `SmsOrderingTest.keptDecisionWithoutBlockThenBlockIsDeadLetteredAtOnceAndNeverSent` (sender); `EnvelopeConsumerTest.poison…` (the consumer dead-letters it at once) |
| I3b | The same, with the intent read **before** W2 writes the kept decision: S4, then S3 once it is written, then dead-lettered. It never sends. | `SmsOrderingTest.keptDecisionWithoutBlockReadBeforeItIsWrittenWaitsThenIsDeadLettered` |
| I4 | A submission decided twice, blocking both times: exactly one SMS. | `SmsOrderingTest.theSameSubmissionBlockingTwiceSendsOneSms`, `…IdsDerivedFromTheSubmission` |
| I4b | T submitted twice **with different bodies** (another account), both blocking, the first kept: the other body's intent is S3 and never sends; the kept intent is sent to the kept account [2-M1]. | `SmsOrderingTest.differentBodyForTheSameTransactionNeverReachesTheKeptAccount` |
| I5 | Link eligibility comes from the kept decision: when the discarded decision allowed a link and the kept one did not, the SMS carries no link. It also fails closed without a REQUESTED row. | `SmsOrderingTest.theLinkIsTheKeptDecisionsAndFailsClosedWithoutItsRequestedRow` |
| I6 | An intent that arrives before its record is written is sent once the record is written (S4, then S2). | `EnvelopeConsumerTest.intentsWaitWithinTheirPartitionsBudget…` (record 1); `SmsOrderingTest.intentReadWhileItsDecisionIsBeingWritten…` |
| I7 | The partition's budget: once waiting has filled `spent` to the configured bound, that record is dead-lettered as `parent_not_recorded`. A record whose first answer while tripped is S4 gets one grace re-read, and is dead-lettered if still S4, or sent if its parent arrived. Draining time as defined in W-b drains `spent` at the configured rate, neutral time does not, and no outcome resets it [5-m4]. | `WaitBudgetTest` (fill, drain, no reset); `EnvelopeConsumerTest.intentsWaitWithinTheirPartitionsBudget…` (end to end); `…recordsArrivingAfterTrippingGetOneGraceReRead…` (the grace re-read: sent if the parent arrived, otherwise dead-lettered after one re-read, not a fresh budget). The consumer's `!first` guard is equivalent in practice to its absence: any draining since the trip leaves the budget just below full at a record's first answer. |
| I7b | Orphans interleaved with valid intents keep the partition within W-c2's limit [3-M1]. | `WaitBudgetTest.orphansBetweenValidIntentsDoNotEachGetTheWholeBound` |
| I7c | An orphan after an idle period following a trip waits for what the idle time drained [4-M1]. | `WaitBudgetTest.idleTimeAfterTrippingDrains…` |
| I7d | While one partition waits, is tripped, or backs off after S5 or a RETRY, another partition's intents are handled without delay [5-m1]. | `EnvelopeConsumerTest.otherPartitionsFlowWhileOneWaits…AndAnotherBacksOff` |
| I7e | A partition paused on one member and moved to another: the first member's other partitions keep flowing and no `IllegalStateException` escapes; the second member re-reads at once, with `spent` resumed from the metadata [5-m2]. (Restored to the draft-5 wording, amendment 1.) | **In part.** `EnvelopeConsumerTest.partitionsMovedWhilePausedKeepEveryoneFlowing` shows the members flowing and the orphan re-read after the join. It does not show that the paused partition moved to the second member (implementation-2, m3; carried to M10 as `PB-78`). That no `IllegalStateException` escapes rests on the same test completing; the resumed `spent`: I14 |
| I7f | An exception between `poll` and the end-of-batch commit skips no fetched record [5-n3]. | **In part.** `EnvelopeConsumerTest.batchesThatCannotBeCommittedSkipNoRecord` covers a member expelled mid-batch, where the rejoin resets positions from the committed offsets. A commit failure while membership is kept, the case `seekBack` is for, is not produced by a test; `seekBack` is verified by review (implementation review, MINOR 5). |
| I8 | Neutral time, from a failed attempt (S5, or a failed DLQ send) to the next successful answer, neither adds to nor drains `spent`. A grace re-read that meets S5 is retried until it succeeds [4-m1] [4-n4]. (Restored to the draft-5 wording, amendment 1; with section 12, "until it succeeds" holds for F1, and an F4 failure is bounded by 12.3.) | `WaitBudgetTest.neutralTimeNeitherFillsNorDrainsTheBudget`; `EnvelopeConsumerTest.recordsArrivingAfterTrippingGetOneGraceReReadEvenThroughTransientFailures` |
| I9 | The classification is one statement. A test holds W2's transaction open, writing `decision_states(T, 1)` and B together, and sees S4 (never S3) until the commit, then S2. (Restored to the draft-5 wording, amendment 1.) | `SmsOrderingTest.theClassificationIsOneStatementOnOneConnection` (structural: one connection, one statement besides the tenant's); `…intentReadWhileItsDecisionIsBeingWritten…` (an uncommitted writer is seen as S4, never S3) |
| I10 | A dead-lettered intent keeps its headers, for every reason of D-a, in both consumers. A replay republishes key, value and headers plus `fs-replayed`. Replayed after its parent arrived, it is sent; replayed before, it returns to the DLQ at once and never waits. | **To be proved for every reason** (implementation-2, m4: only three of six were asserted). Replay half: `…replayRepublishesTheNewestCopyOnceAndReplayedOrphansNeverWait` |
| I11 | A replay stops at the end offsets taken when it starts, commits nothing, republishes only the newest copy per `event_id`, leaves other reasons, and refuses `rejected_by_the_database` unless asked. | `EnvelopeConsumerTest.replayRepublishes…` |
| I12 | The WARN fires under intermittent S5 failures. | `EnvelopeConsumerTest.longWaitsAreWarnedEvenWhilePostgresKeepsFailing` (the WARN interval configurable) |
| I13 | A block resolved before its SMS goes is not sent (S2r), and is counted. | `SmsOrderingTest.blockResolvedBeforeItsSmsIsNotSent` |
| I14 | The budget survives a change of owner. After a revocation, and after a crash with no revocation while the partition is tripped or waiting, the new owner resumes `spent` from the committed metadata: at most 500 ms of waiting lost, and the unowned time drained. A lost partition commits nothing. A future `at` is clamped to zero [2-M2] [3-m1] [3-m3] [4-m3]. (Restored to the draft-5 wording, amendment 1; the implementation round must show "at most 500 ms lost" or mark it in part.) | `EnvelopeConsumerTest.theBudgetSurvivesChangesOfOwner` (revocation); `…crashedOwnersBudgetsAreResumedFromTheirLastCheckpoint` (crash); `WaitBudgetTest.checkpointsFromTheFutureAreClamped…`. **By review only:** the lost path committing nothing (`onPartitionsLost` has no commit). |
| I14b | A crash between a dead-letter and its commit may leave a second DLQ copy, which a replay republishes once. | `EnvelopeConsumerTest.replayRepublishes…` (two copies, one republish). The crash at that exact point is not produced. |
| I15 | A header-less intent is S4 with `kept` null, and is dead-lettered at the bound if its B never appears. | `SmsOrderingTest.headerlessIntentWaitsRatherThanBeingJudgedPermanent`; the bound: I7 |
| I16 | S0: an intent whose B row belongs to another account is dead-lettered and never sends. | `SmsOrderingTest.intentForAnotherAccountsBlockIsNeverSent` |
| (wire) | The intent's record carries `fs-transaction-id` on Kafka. | `KafkaSpoolChaosTest.theSmsIntentCarriesItsTransactionOnTheWire` |
| (malformed) | Replaced by I19 (amendment 1). The earlier row claimed this for every unreadable payload; it held for three fields of one handler (implementation-2, M3). | I19 |
| I17 | A record that fails in F4 (12.2) is retried on the backoff and dead-lettered as `handler_failure` once the bound of 12.3 is reached, and the record behind it is then handled. The F4 state survives a change of owner. Consecutive F1 failures neither use up nor reset the bound. | to be written: through `EnvelopeConsumer` with the real sender, an intent that fails deterministically (implementation-2, needs-execution item 2); the owner change as in I14 |
| I18 | An F1 failure is retried without bound and without a dead letter, is counted, and is warned at WARN after 1 minute of consecutive failure and every minute after, naming partition, offset and class. | to be written |
| I19 | Every field a handler reads from the record is validated before any side effect: an envelope whose `payload` is absent or not an object, an SMS intent with any required field absent or unreadable (12.1), and a webhook payload with any required field absent or unreadable are each dead-lettered as `malformed_envelope`, and nothing is sent, issued or recorded. | to be written: one test per required field group, in each consumer |
| I20 | Once the provider call has started, the attempt records SENT or FAILED and is never retried: a 2xx reply that cannot be read, or any unchecked exception from the adapter, records FAILED, and exactly one send is made. | to be written: a gateway answering HTTP 200 with a non-JSON body through the real `AfricasTalkingGateway` parsing (implementation-2, needs-execution item 1) |
| I21 | A malformed `fs-transaction-id` (5.1) with B visible is classified exactly as with a valid header; with B absent it is dead-lettered at once as `malformed_envelope` (S3m); it is logged and counted. An absent header is S4 (I15). A short form such as `1-2-3-4-5` is malformed. | to be written, through `Envelope` and the wiring, not by parsing the header in the test (implementation-2, m2) |
| I22 | A committed offset that has fallen out of the log is never skipped silently: the reset to the log start is logged at ERROR with the number of offsets skipped, and counted. | to be written: a short-retention topic behind a stalled head (implementation-2, needs-execution item 4) |
| I23 | The counters and the gauge of 12.6 are exported and move when their event happens. | to be written |
| I24 | Every failure of an attempt is in exactly one class of 12.2, and the class decides the action. | by review of the classification function, plus one test per class |

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
- **(amendment 1) A per-record attempt count kept only in memory:** every rebalance restarts it, the
  same reason as W-e for the budget.
- **(amendment 1) Dead-lettering an intent with a malformed header whatever B shows:** it would
  turn a harmless corruption into a lost notice.
- **(amendment 1) Waiting on an intent with a malformed header, as if header-less:** it spends a
  budget that valid intents share, for a record W3 cannot produce, and dead-letters it under the
  wrong reason.
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

11. `EnvelopeConsumer` classifies every failure of an attempt into one class of 12.2, keeps the F4
    state per head in memory and in the checkpoint metadata (12.3), dead-letters `handler_failure`
    at the bound, and warns on F1 (12.5).
12. Validation before any side effect (12.1): `EnvelopeConsumer` for the envelope; `CustomerSmsSender`
    for the whole intent; `WebhookDispatcher` for its payload.
13. `AfricasTalkingGateway` converts any failure to read a reply into `IOException`, and
    `CustomerSmsSender` records FAILED for any exception from the provider call (12.4).
14. `Envelope.transactionId()` returns absent, valid or malformed, with a strict parse (5.1);
    `CustomerSmsSender` implements S3m.
15. The consumer handles out-of-range offsets itself (12.5) instead of a silent reset.
16. The counters and the gauge of 12.6, exported through the application's meter registry.
17. ADR 0056 records amendment 1; the test claims of section 8 are corrected.

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
  canonical UUID; `payload` is present and is a JSON object.
- **SMS intent (`CustomerSmsSender`):** `notification_id` and `auto_block_event_id` are canonical
  UUIDs; `account_token` is a non-empty string; `parameters` is an object holding `amount.amount`
  (a decimal string `Money` accepts), `amount.currency` (a `CurrencyCode`), `local_time` and
  `reference_code` (non-empty strings).
- **Webhook payload (`WebhookDispatcher`):** `decision_sequence` is an integer; `transaction_id` and
  `event_id` are canonical UUIDs.
- The `fs-transaction-id` header is not validated here: its malformed state is a classification
  input (5.1, S3m), not a reason to refuse a send.

A field the frozen schema allows but the handler does not read is not validated.

### 12.2 Failure classes (change)

Every failed attempt is in exactly one class, decided by the first matching row:

| Class | What | Action |
|---|---|---|
| **F1 outage** | A shared dependency is down: SQLSTATE class `08` (connection); `40001` and `40P01` (serialisation, deadlock); `57P01`, `57P02`, `57P03` (shutdown, cannot connect now); `53300` (too many connections); the dead-letter send failing (`DeadLetterNotSent`). | Retried on the backoff **without bound**. Neutral time (W-b). Warned and counted (12.5, 12.6). |
| **F2 malformed** | 12.1 refused the record, or the header is malformed with B absent (S3m). | Dead-lettered at once as `malformed_envelope`. |
| **F3 permanent** | A SQLSTATE outside F1 and F4 (for example `22`, `23`, `42`); a permanent `VaultException`; S0 and S3. | Dead-lettered at once with its existing reason: `rejected_by_the_database`, `permanent_vault_failure`, `not_the_kept_decision`. |
| **F4 may be transient** | Everything else: any other unchecked exception; a SQLSTATE that is null or in classes `53`, `57` or `58` and not listed under F1 (for example `58030` I/O error, `58P01` undefined file, `53400` configuration limit, `57014` a statement that always times out); a `VaultException` that is not permanent, such as a key this instance does not hold yet (ADR 0069). | Retried on the backoff **up to the bound of 12.3**, then dead-lettered as `handler_failure`. |

A provider call that has started is never in any class: it ends in SENT or FAILED (12.4).

### 12.3 The bound on F4 (change)

- For the record at the head of a partition, the consumer keeps **`f4_count`**, the number of
  consecutive attempts that failed in F4, and **`f4_ms`**, the sum of the intervals from each F4
  failure to the next attempt for the same record.
- A successful attempt clears both. An F1 failure neither adds to them nor clears them, so an outage
  in the middle of a poison record's retries neither uses up its bound nor restarts it.
- At an F4 failure that brings `f4_count` to at least **5** and `f4_ms` to at least **10 minutes**,
  the record is dead-lettered as `handler_failure`, with `fs-dlq-error` naming the exception class and
  message, and the record behind it is handled. Both values are ASSUMED and configurable so tests can
  shorten them. With the backoff capped at `MAX_BACKOFF_MS` (30 s), 10 minutes is about 25 attempts;
  the count only matters when the cap is configured high.
- Ten minutes outlasts a rolling vault-key rotation (ADR 0069) and a provider or database blip; a
  failure that outlasts it is treated as the record's own, and the record can be replayed once the
  cause is fixed (D-g).
- **The F4 state survives a change of owner**, as the budget does (W-e). The checkpoint metadata of
  a partition whose head has failed in F4 carries `f4_count=<n> f4_ms=<n>` with the committed offset;
  the metadata version becomes `fs-wait:v3`. A new owner resumes the F4 state only if the metadata's
  offset equals the committed offset, and otherwise starts at zero. It reads `v2` metadata as before,
  with no F4 state. The unowned time between owners is not added to `f4_ms`.
- The F4 retries of the head are neutral time for the wait budget (W-b), as before.

### 12.4 A provider call is attempted at most once per attempt, and never retried (change)

- Once `gateway.send` has been called, the attempt ends in **SENT** (the reply was read and accepted)
  or **FAILED** (anything else). That includes a 2xx reply that cannot be read, which
  `AfricasTalkingGateway` turns into `IOException`, and any unchecked exception from the adapter,
  which `CustomerSmsSender` catches and records as FAILED. The provider may have delivered a FAILED
  SMS; the outcome is logged at ERROR with N when the reply could not be read.
- The record is then committed past (S1 on any re-read), so one notice is never sent twice by a
  retry (implementation-2, M2).
- If recording the outcome itself fails, the window of 13.1 applies.

### 12.5 What an operator sees, and the retention gap (change)

- **F1 and F4 warnings.** A partition whose head has failed (F1 or F4) for 1 minute of wall time in
  a row is logged at WARN, naming topic, partition, offset, class and the last exception, and every
  minute after that. W-d's WARN for waiting is unchanged.
- **No silent skip.** The consumer sets `auto.offset.reset=none` and handles the two cases itself:
  - no committed offset (a new group, or a new partition): it starts at the log start, as
    `earliest` did;
  - a committed offset that has fallen out of the log, because retention deleted it while the
    partition was held: it logs at ERROR the topic, partition, committed offset and new log start,
    counts the offsets skipped (12.6), and then starts at the log start. Those records are lost; the
    ERROR and the counter are what make the loss visible. With 12.3, only a long F1 outage can hold a
    partition that long (3 days on `fs.notifications.customer`, 7 on `fs.decisions.final`).
- Alerts on these signals are M9's, with the DLQ-growth alert already carried there.

### 12.6 Metrics (change)

Exported through the application's meter registry, with the consumer group as the `group` tag:

| Name | Type | Tags | Counts |
|---|---|---|---|
| `fs_notify_dead_letters_total` | counter | `group`, `reason` | each record dead-lettered, by D-a reason |
| `fs_notify_retries_total` | counter | `group`, `class` (`outage`, `may_be_transient`) | each failed attempt that is retried |
| `fs_notify_wait_warnings_total` | counter | `group` | each W-d WARN |
| `fs_notify_resolved_before_send_total` | counter | `group` | each S2r |
| `fs_notify_malformed_headers_total` | counter | `group` | each record read with a malformed `fs-transaction-id` |
| `fs_notify_offsets_skipped_total` | counter | `group` | offsets skipped by a reset across a retention gap (12.5) |
| `fs_notify_head_failing_seconds` | gauge | `group` | the longest time any owned partition's head has been failing (F1 or F4), 0 when none |

## 13. Known gaps (amendment 1)

### 13.1 Between the provider's acceptance and the recorded outcome [impl-2 m7]

W4's writes stand alone (section 2). If the provider accepts an SMS and the outcome cannot then be
recorded, the record is re-read with no outcome visible, finds S2 again, and **a second SMS is
sent**. `VerificationService.issue` runs once per block, so the second SMS carries no link. The same
window opens when a consumer is evicted or crashes between the send and the write (D-d).

- The outcome write failing in F1 retries the attempt, which re-sends. In F3 or at the bound of F4
  the record is dead-lettered (`rejected_by_the_database` or `handler_failure`), and a replay may
  re-send, which is why the replay tool refuses `rejected_by_the_database` unless asked (D-c).
- Not closed in M6. It is carried as **`PB-77`** (claimed in `docs/parallel/M6_updates.md`). Two
  designs are on record: a `SENDING` row written before the provider call and treated as "outcome
  unknown, do not send", and the transactional outbox of section 9.
