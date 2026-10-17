# M6 review: the fix round of the ordering contract's implementation (3729de8)

Reviewer: a fresh, independent Principal reviewer. I did not write this code.

## Scope

- The delta `git diff bd48557 3729de8`: the fix round answering
  `m6-decision-2026-09-24-implementation-1.md`. 14 files, all of them read.
- The ordering path against its contract: `docs/architecture/decision-fact-ordering.md` (all of
  it, including section 8) and `docs/adr/0056-decision-fact-ordering-contract.md`.
- Main code read in full: `notify/kafka/EnvelopeConsumer.java`, `WaitBudget.java`,
  `DeadLetterReplay.java` (core loop), `MalformedPayloadException.java`,
  `notify/sms/CustomerSmsSender.java`, `AfricasTalkingGateway.java`, `SmsGateway.java`,
  `SmsCatalogue.autoBlock`, `VerificationService.issue`, `VaultException`, and
  `WebhookDispatcher.enqueue`.
- The producer side: `DecisionService.addAutoBlock` and `derivedId`, `KafkaMessages.render`
  (where the `fs-transaction-id` header is set), and `NotificationWiring` (how both consumers
  are wired).
- Tests read: `EnvelopeConsumerTest` (all of it), `SmsOrderingTest` (helpers and every test the
  section 8 table names), the new `KafkaSpoolChaosTest` test, the `ResilienceApiTest` and
  `VaultContactsTest` changes, and `KafkaTestCluster.readAll`.

## Method

Static review only: `git`, `grep`, `sed` and `cat`. Nothing was built or run, because the machine
is short of memory and other work shares it (owner's constraint). Anything that only execution
can settle is marked **needs execution**, with the test that would settle it.

## Verdict: CHANGES_REQUIRED. 3 MAJOR, 8 MINOR, 8 NIT

The delta does what it says for the findings of implementation-1. The new branches are ordered
correctly: `MalformedPayloadException` is caught before the generic `RuntimeException`, and it is
thrown before `classify`, so nothing has been done when it is thrown. `ParentWait`'s new
constructor is safe to initialise. The WARN's B and its configurable interval, the S2r counter,
the `fs-replayed` guard, the Redis and PostgreSQL pause guards and the crash hook are all correct.

The two paths the owner put in scope are another matter:

- **(a)** A record that deterministically throws an unexpected unchecked exception is re-read for
  ever. Its partition stalls with no bound, no counter and no dead letter, and the source topic's
  retention eventually drops what is queued behind it. The fix round claims to have closed this
  ("a malformed payload is now dead-lettered"), but it closed it for three fields of one handler.
  The same path also allows repeated sends of one notice when the SMS provider's reply cannot be
  parsed.
- **(b)** A malformed header is conflated with an absent one. This causes no wrong send and no
  duplicate send, and it cannot stall a partition indefinitely. It can misroute a dead letter, and
  nothing records that it happened.

Checked and correct:

- `MalformedPayloadException` is mapped to `malformed_envelope`, before the generic branch. It is
  thrown before `classify`, so no verification is issued and nothing is recorded or sent.
- The `ParentWait` static initialisation order. `DEFAULT` calls the two-argument constructor,
  which reads `EnvelopeConsumer.WARN_AFTER`. `EnvelopeConsumer`'s static initialiser never touches
  `ParentWait`, so the read is never circular.
- `warnAfter` is validated, and the WARN names B through `state.missing`. `missing` is always set
  by the current head's S4, because `headAnsweredS4` is reset on a change of head.
- `resolvedBeforeSend` is incremented only on S2r.
- `DeadLetterReplay` now survives a non-numeric `fs-replayed`. A null value still aborts the run
  (n4).
- `ResilienceApiTest` unpauses only what it paused.
- The producer always sets the header on new records. `DecisionService.addAutoBlock`
  (`DecisionService.java:312-336`) always emits `AutoBlocked` and its
  `CustomerNotificationRequested` together, and `KafkaMessages.render`
  (`KafkaMessages.java:85-98, 203-211`) adds `fs-transaction-id` from that `AutoBlocked`.
- Tenant scoping. `CLASSIFY` and `RECORD` both set `fraudshield.institution_id` and also filter
  on `institution_id` explicitly, including the `kept` subquery.
- No commit passes an unhandled record. RETRY and WAIT commit X, not X+1
  (`EnvelopeConsumer.java:323-333`). Revocation commits `position(p)`, which the seek has set to
  X.
- The I12 test tells the right implementation from a wrong one. If S5 reset the WARN run, the
  alternating S4/S5 sequence would never reach the 1 s interval, so the test would fail.
- The crash test (I14 without revocation) is sound. `crash()` stops the poll thread, so expulsion
  by poll timeout runs no listener and commits nothing. The test asserts both the checkpointed
  `spent` and that the next owner gets less than a fresh budget.

## Findings

| # | Severity | Location | Finding | Failure scenario | Minimal fix shape |
|---|---|---|---|---|---|
| M1 | MAJOR | `EnvelopeConsumer.java:515-524` (RETRY); `:323-329`, `:367-371` (scheduling); `:425-431` with `WaitBudget.failed` (budget goes NEUTRAL); triggers at `CustomerSmsSender.java:225-240`, `SmsCatalogue.java:105-111`, `WebhookDispatcher.java:136-141`, and `EnvelopeConsumer.java:493` (payload presence not checked) | Any unchecked exception other than a permanent vault failure is RETRY, with no bound. A deterministic one re-reads the same record for ever. Path (a) below. | An SMS intent with readable ids whose `parameters.local_time` is missing reaches S2. The vault is read, a verification token is minted and stored, and then `parameters.get("local_time").asString()` throws an NPE. Only `IllegalArgumentException` is caught (`:241`), so the NPE goes to RETRY, re-read at 100 ms, doubling to 30 s, for ever. Every intent behind it on that partition (1 of 12) waits. No counter, no DLQ, no W-d WARN (the head never answered S4); the operator sees one stack-trace WARN per attempt. After 3 days (`retention: P3D`) offset X is deleted, the fetch goes out of range, and `auto.offset.reset=earliest` jumps to the new log start. Every intent that aged out behind X is then **lost**: never sent, never dead-lettered. The same happens on `fs.decisions.final` (7 days) with an envelope whose `payload` is absent or lacks `decision_sequence`. | Validate everything the handler reads before any side effect, and map failures to `MalformedPayloadException`: the whole SMS intent, including `parameters`, in the up-front block; the webhook payload; and in `EnvelopeConsumer`, a payload that is null or not an object. As a backstop, give RETRY for exceptions not known to be transient a per-head limit (attempts or neutral time). At the limit, dead-letter the record as a replayable reason (for example `handler_failure`), with a counter. Keep S5 (transient SQLSTATE) and a non-permanent `VaultException` as they are. State the rule in the contract (W-a, S5). |
| M2 | MAJOR | `AfricasTalkingGateway.java:77-78`; `CustomerSmsSender.java:253-276`; `EnvelopeConsumer.java:515-524` | The provider adapter can fail with an unchecked exception **after the provider accepted the message**. `tools.jackson` (Jackson 3) `readTree` throws `JacksonException`, which is a `RuntimeException`. `send` catches only `IOException`, so nothing is recorded and the record is RETRY: S2 again, and **sent again**. | The provider, or an egress proxy, answers 2xx with a body that is not JSON (an HTML maintenance page, a truncated body). The SMS may well have gone. No SENT or FAILED row is written; the record is re-read, finds S2, and sends again, every 30 s at most, for ever. Every rebalance restarts the backoff at 100 ms, which gives a burst. `issue` runs once per block (`VerificationService.java:151-165`), so every SMS after the first has no link. One notice becomes an unbounded number of SMS to the customer, and the partition stalls as in M1. | Never re-send after a call that may have reached the provider. In the adapter, convert any failure to parse the reply into `IOException` (recorded as FAILED). In `CustomerSmsSender`, also catch `RuntimeException` around `gateway.send` and record FAILED. **Needs execution:** a `SmsOrderingTest` case with a gateway that returns 200 with `<html>`, asserting one send and a FAILED row. |
| M3 | MAJOR | contract section 8, row "(malformed)"; `-implementation-1.md` NIT row; `docs/parallel/M6_updates.md` | A false test claim. The documents say a payload that cannot be read "is dead-lettered (`malformed_envelope`), not retried for ever", and that this is tested. The fix covers only `notification_id`, `auto_block_event_id` and `account_token` in `CustomerSmsSender`. `anIntentWithoutReadableIdsIsMalformedNotRetriedForEver` covers one of them (`notification_id`). | See M1: an intent whose `parameters` cannot be read, or a webhook payload without `decision_sequence`, is retried for ever, and no test fails. | Correct the three documents. Once M1 is fixed, add a test for an intent with readable ids and missing `parameters`, and one for the webhook consumer. |
| m1 | MINOR | `EnvelopeConsumer.java:90-97` | A present but malformed `fs-transaction-id` is treated as absent, silently. `UUID.fromString` is also lenient: `1-2-3-4-5` parses to a different id. Path (b) below. | Without B and with the kept decision written, an intent that should be S3 becomes S4. It waits up to the rest of the budget, fills it, and is dead-lettered as `parent_not_recorded`, not `not_the_kept_decision`. The D-e ERROR with B and T is never logged, and every replay bounces it back. No wrong send and no duplicate send. | A three-way result: absent, valid, malformed. Parse strictly (36-character canonical form). On a malformed header, log a WARN and count it. Keep classification driven by B (S0 to S2 unchanged). For S4 with a malformed header, the contract must choose between dead-lettering at once as `malformed_envelope` and waiting as header-less. Add a sentence to contract 5.1 and a test through `Envelope`. |
| m2 | MINOR | `SmsOrderingTest.java:182-187`; `NotificationWiring.java:300-307` | The glue is untested. `SmsOrderingTest` parses the header itself, with `UUID::fromString`, and `EnvelopeConsumerTest` uses handlers that throw. `Envelope.transactionId()` and the wiring lambda that passes it on have no test. I3a's "the consumer dead-letters it at once" is proved only for a handler that throws. | A regression in the wiring that passed `Optional.empty()` would turn every S3 into S4 (a wait, then `parent_not_recorded`), with every test green. | One test that drives a real intent record, with its header, through `EnvelopeConsumer` into the real sender. Or a unit test of `transactionId()` plus a test of the wiring. |
| m3 | MINOR | `EnvelopeConsumerTest.java:569-607` (I7e) | The test never shows that the paused partition 0 moved to the second member. It asserts only that orphan attempts continue and that partitions 1 and 2 flow. Under the default range assignor, the member whose id sorts first keeps partitions 0 and 1. | If partition 0 stays with the first member, "the new owner re-reads at once" is not exercised; the test proves a revoke and reassign on the same member. **Needs execution:** record which consumer instance handles the orphan after the join (for example the handler's thread name) and assert it is the second. | Assert the owner, or force the move (stop the first member's ownership with a static assignment, or close the first member and check that the second re-reads at once). |
| m4 | MINOR | `EnvelopeConsumerTest.java:187-218` (I10, "headers, every reason") | Headers are asserted for `malformed_envelope` (envelope parse) and `not_the_kept_decision` in this test, and for `parent_not_recorded` in another. `rejected_by_the_database`, `permanent_vault_failure` and a malformed payload are published without headers, so the test cannot show that theirs are kept. | All reasons share `deadLetter`, so it is correct by review. The claim "every reason" is still stronger than the test. | Publish every poison record with `fs-transaction-id` and assert `allSatisfy`. |
| m5 | MINOR | contract section 8 rewrite; status block lines 7-15 | The section 8 rewrite narrowed the invariant text of a contract reviewed sound. Dropped: I7e's "no `IllegalStateException` escapes"; I9's held-open-writer description; I14's "at most 500 ms of waiting lost, while tripped or waiting"; I8's definition of neutral time. The status block lists only two changes after the sound review. This is the class of implementation-1 m1. | A reader takes the narrowed invariants as the reviewed ones. | Flag the narrowing in the status block, or restore the text and mark what is proved in part. |
| m6 | MINOR | `EnvelopeConsumer.java:578-586` | Path (a), SQL part. Transient means: SQLSTATE null, or classes 08, 40, 53, 57 and 58. Classes 53, 57 and 58 hold states that can persist: 58030 `io_error` on a damaged page, 58P01 `undefined_file`, 53400 `configuration_limit_exceeded`, and 57014 for a statement that always exceeds `statement_timeout`. These loop as in M1, without a bound or a counter, and every intent on the partition is held. | `CLASSIFY` touches a damaged heap page of `customer_notifications`. Every attempt fails with 58030, so the partition holds for ever. | Accept the loop as an outage, but make it visible: a retry counter or gauge, and a WARN naming partition, offset and SQLSTATE after N minutes. Optionally treat 58P01/58030 as permanent. |
| m7 | MINOR | `CustomerSmsSender.java:254-263`; `VerificationService.java:151-165`; contract D-d | Send, then record: if the outcome write fails transiently (S5), the record is re-read, finds S2 and sends a second SMS. Because `issue` runs once per block, the second SMS has no link. D-d names this window only for eviction and `rejected_by_the_database`. Pre-existing. | A PostgreSQL blip right after the provider accepted: the customer gets two SMS, the second without the link. | Name the case in the contract (W4 or D-d). Optionally write a pre-send marker row and treat it as S1-unknown. |
| m8 | MINOR | `EnvelopeConsumer.java:272-283`; `CustomerSmsSender.java:138-140` | None of the consumer's counters (`deadLettered`, `waitWarnings`, `resolvedBeforeSend`) is exported as a metric, and RETRY is not counted at all. S2r is "counted" only as an in-process getter. | During M1 or m6, the only signal is the log. | Export the counters and add a RETRY counter per reason (can be carried to M9 with the DLQ alert). |
| n1 | NIT | `CustomerSmsSender.java:176` | `MalformedPayloadException` is built with `cause` null, although its Javadoc says the cause is "the parsing failure". `fs-dlq-error` does not say which field. | | Pass the cause, or name the field. |
| n2 | NIT | `CustomerSmsSender.java:174` | `Objects.requireNonNull(...asString())`: Jackson 3's `asString()` does not return null for a present node, so the check adds nothing, and an empty account passes (it is caught later by S0). | | Check for an empty string. |
| n3 | NIT | `EnvelopeConsumer.java:713-721` | `crash()` is a test-only hook in a production class. | | Accept, or move it behind a test seam. |
| n4 | NIT | `DeadLetterReplay.java:162-167` | An `fs-replayed` header with a null value throws an NPE (`new String(null)`), which aborts the run. The new guard catches only `NumberFormatException`. | | Treat a null value as 0. |
| n5 | NIT | `KafkaSpoolChaosTest.java` new test | `anySatisfy` dereferences `lastHeader(...)` without a null check, so a header-less intent read earlier can throw an NPE (depends on order). It also always waits the full 10 s, because it expects 1,000 keys. | | Null-safe lambda; stop once the id is seen. |
| n6 | NIT | ADR 0056, Consequences | The ADR says "two properties rest on review only". Section 8 also marks I14b's crash point and the `!first` guard as unproved. | | Point to section 8 instead of counting. |
| n7 | NIT | `SmsOrderingTest.theClassificationIsOneStatementOnOneConnection` | The structural test counts only `prepareStatement`. A split classification made with `createStatement` or `prepareCall` would pass. | | Record those calls too. |
| n8 | NIT | `CustomerSmsSender.java:142-149` | The two-argument `send` no longer documents `NotYetRecordedException`, which it always throws when B is absent (no header means `kept` is null). | | Restore the `@throws`. |

## Path (a): RuntimeException goes to RETRY with no bound

**Verdict: defect, MAJOR (M1, with M2 and M3; the SQL part is m6).**

How RETRY is scheduled:

- `handle` returns RETRY (`EnvelopeConsumer.java:522-524`). `attempt` marks the attempt failed,
  and `WaitBudget.failed` puts the budget in NEUTRAL (`:430-431`).
- `handleBatch` seeks the partition back to X, pauses it, and sets `resumeAtNanos` from
  `nextBackoff`: 100, 200, 400 ms and so on, capped at `MAX_BACKOFF_MS` = 30 s (`:323-329`,
  `:367-371`). It commits `OffsetAndMetadata(X, checkpoint)`.
- `backoffMs` is reset only by a record that is not re-read (`:331`). A rebalance drops the state,
  so the new owner starts again at 100 ms.

What bounds it: nothing.

- RETRY is not S4, so `parentMissing` and its budget check never see it.
- NEUTRAL freezes `spent`, so the budget never trips. There is no attempt counter, and no dead
  letter is reachable from RETRY.
- `DeadLetterNotSent` is also RETRY, which is intended.
- The contract allows this. W-a and S5 classify "any other transient RETRY (vault, provider, DLQ
  send)" and set no bound. It assumes every non-SQL unchecked exception is transient. So this is
  a contract gap as well as an implementation defect.

What stalls:

- The record's partition, completely: W-f's "within W-c2's limit" does not apply to neutral time.
- Other partitions keep flowing, because only the partition is paused. The thread is blocked
  only for the duration of each attempt: up to about 8 s for the gateway (3 s connect plus 5 s
  request), once every 30 s.
- A rebalance moves the poison record with its partition, to whichever member gets it.
- After the source retention (3 days for `fs.notifications.customer`, 7 days for
  `fs.decisions.final`), the out-of-range reset to `earliest` silently skips every record that
  aged out behind X. Those are lost notices, neither handled nor dead-lettered. This needs an
  operator to ignore a WARN every 30 s for days, so it is MAJOR rather than BLOCKER. It would be
  a BLOCKER if those WARNs are not alerted on (M9).

What the operator sees:

- One `WARN "a record could not be handled yet; it will be re-read"` with a stack trace per
  attempt.
- No metric, and no W-d WARN, because `waiting` requires `headAnsweredS4`.
- Consumer lag on the partition, if anyone monitors it.

Realistic triggers, each deterministic for its record:

- **SMS intent.** Missing or null `parameters`, `amount`, `local_time` or `reference_code`
  (`CustomerSmsSender.java:225-240`: NPE, and only `IllegalArgumentException` is caught).
- **Institution settings or contact.** A null `officialPhone`, `maskedAccount`, `senderId` or
  `phoneE164` (`SmsCatalogue.java:105-111`: `String.replace` with null; `URLEncoder.encode`
  with null).
- **Webhook consumer.** A null payload, or no `decision_sequence`, `transaction_id` or `event_id`
  (`WebhookDispatcher.java:136-141, 168`). `EnvelopeConsumer.handle` never checks that `payload`
  is present (`:493`).
- **Provider reply.** A 2xx reply that is not JSON (M2). This one also sends again on every
  attempt.
- **Vault key.** A `VaultException` for a key that never arrives. This is retried by design
  (ADR 0069), with the same missing bound.
- **`classify` itself.** It cannot throw an unchecked exception on well-formed input:
  `Outcome.valueOf` sees only SENT or FAILED, and `row.next()` is always true.

Side effect on every loop through S2: the first attempt mints and stores a verification token
that is never delivered. If the cause is later fixed, the SMS goes without a link, because
`issue` runs once per block.

Transient SQLSTATEs (`:578-586`): null, 08, 40, 53, 57 and 58. Anything else is dead-lettered as
`rejected_by_the_database`, which is right for 22, 23, 42 and so on. A deterministic SQL error
in 53, 57 or 58 (58030, 58P01, 53400, a 57014 that always recurs) loops exactly as above (m6).
PgJDBC rarely raises a null state, but one is also treated as transient.

## Path (b): a malformed `fs-transaction-id` is read as absent

**Verdict: defect, MINOR (m1, with m2 for the missing test).**

- **Which state.** The header affects only `kept`. With a malformed header, `kept` is null, so
  S0, S1, S2r and S2 are decided exactly as with a valid header, because they depend on B alone.
  The only change is when B is absent: S3 (kept decision written, no B) becomes S4.
- **Wrong send.** None: a send needs B, and S0 still guards the account.
- **Duplicate send.** None: S1 depends on N's outcome, not on T.
- **Lost notice.** None beyond what S4 already allows. A late B is sent on the re-read, or after
  `parent_not_recorded` and a replay, exactly as with a valid header.
- **Stall.** Bounded. The intent waits up to the partition's remaining budget (at most the 10-minute
  bound) and then fills it. Later late intents on that partition get only the grace re-read
  until the budget drains, and so may be dead-lettered and need a replay. That is W-c2's accepted
  cost, and the same as I15's header-less case.
- **What is actually wrong.** The reason. The intent goes to the DLQ as `parent_not_recorded`
  rather than `not_the_kept_decision`, so D-e's ERROR with B and T, which an operator needs for
  the manual decision about the discarded decision's customer, is never logged. Each replay
  bounces it back to the DLQ, because a replayed record never waits. Nothing logs or counts the
  malformed header.
- **Lenient parsing.** `UUID.fromString` accepts short forms such as `1-2-3-4-5` and maps them
  to another id. That gives `kept` false: the same S4, not worse, unless the value happens to
  name an existing transaction, which needs a crafted header.
- **The contract.** It says nothing about a header that is present but unreadable. 5.1 defines
  `kept` as "for T from the header; null without the header", and section 4 says W3 adds the
  header to every intent.
- **Can the header legitimately be absent?** Yes, only for intents already on the topic at
  deployment (3-day retention), and for their DLQ copies (30 days) when `DeadLetterReplay`
  republishes them. Replay copies headers verbatim, so a malformed value survives replays too.
- **Can the header legitimately be malformed?** No. W3 always writes `UUID.toString()`
  (`KafkaMessages.java:208-211`) from the `AutoBlocked` fact that `DecisionService` always emits
  with the intent. A malformed value therefore means corruption, a foreign producer, or a manual
  publish.
- **Does the consumer tell absent from malformed?** No.
- **Minimal fix.** Return absent, valid or malformed. Parse strictly. On malformed, log a WARN and
  count it. Keep classification driven by B, so a send is never refused because of the header.
  Decide in the contract what an S4 with a malformed header does. Dead-lettering at once as
  `malformed_envelope` is defensible, since W3 cannot produce such a value. Dead-lettering at
  once regardless of B would turn a harmless corruption into a lost notice, and should not be
  done. Add one test that goes through `Envelope.transactionId()`.

## Section 8 claim check

| Row | Holds? | Note |
|---|---|---|
| I1 | yes | `nothingDoneFor` checks sends, verifications and non-REQUESTED rows after S3 and S4. |
| I2 | yes (sender) | `theSameSubmissionBlockingTwiceSendsOneSms`. `VerificationFlowTest` not re-read. |
| I3a | in part | The sender is proved. The consumer half uses a handler that throws, so the header's path through `Envelope` and the wiring is untested (m2). |
| I3b, I4, I4b, I5, I13, I16 | yes | Each asserts what the row says. I13 now asserts the counter. |
| I6 | yes | |
| I7 | yes, as qualified | The row admits that the `!first` guard is not distinguished. |
| I7b, I7c, I8 (budget) | not re-read | `WaitBudgetTest` is unchanged by the delta. |
| I7d | yes | Latencies under 3 s while one partition waits and one backs off. |
| I7e | **not shown** | Nothing asserts that the paused partition moved (m3). **Needs execution.** |
| I7f | in part, as stated | |
| I8 (grace through S5) | yes | `secondAttempts == 3`, sent. |
| I9 | yes, in part | Structural and behavioural. The structural test counts `prepareStatement` only (n7). |
| I10 | **overstated** | "every reason": headers are asserted for 3 of 6 reasons (m4). Replay half yes. |
| I11, I14b | yes, as qualified | |
| I12 | yes | Discriminates a reset on S5. |
| I14 | yes | Revocation and crash both assert the resumed budget. The lost path is by review, as stated. |
| I15 | yes (sender) | The header-less path only. Malformed headers are untested (m1). |
| (wire) | yes | With n5's fragility. |
| (malformed) | **false** | Only three id fields, one of them tested. `parameters` and the webhook payload still loop (M1, M3). |

## Needs execution

1. M2: `SmsOrderingTest` with a gateway double that returns HTTP 200 and `<html>` from the real
   `AfricasTalkingGateway` parsing path (or a gateway that throws `IllegalStateException` after
   recording the send). Assert exactly one send and a FAILED row. On 3729de8 I expect the
   exception to escape `send` (RETRY in the consumer).
2. M1: `EnvelopeConsumerTest` with the real `CustomerSmsSender` and an intent with readable ids
   and no `parameters`, with B persisted. Assert that it is dead-lettered and the record behind it
   is handled. On 3729de8 I expect it never to be dead-lettered and the record behind it never to
   be handled.
3. m3: in `partitionsMovedWhilePausedKeepEveryoneFlowing`, record the handling member (thread
   name `consumer-<group>` is the same for both, so add an instance tag to the handler) and assert
   that the second member handles partition 0 after the join.
4. M1, retention: on a test broker with `retention.ms` of a few seconds, confirm that a stalled
   partition's position reset to `earliest` skips the aged-out records without handling them.
   This is standard client behaviour, but it is the step that turns the stall into a loss.
