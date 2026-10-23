# M6 review: amendment 1 to the decision-fact ordering contract (b7eefd5)

Reviewer: a fresh, independent Principal reviewer. I did not write the amendment or the code.

## Scope

- `git show b7eefd5`: the contract `docs/architecture/decision-fact-ordering.md` (read in full, not
  only the diff), ADR 0056's amendment section and `docs/parallel/M6_updates.md`.
- The findings it answers: `m6-decision-2026-10-17-implementation-2.md` (M1, M2, M3, m1, m2, m4,
  m6, m8; m5 restored; m7 named as a gap).
- The standard: `-contract-1.md` to `-contract-5.md`. The four restored invariants were compared
  with `git show 1288143:docs/architecture/decision-fact-ordering.md`.
- Code read only to check that the amendment can be implemented and is complete:
  `notify/kafka/EnvelopeConsumer.java`, `WaitBudget.java`, `DeadLetterReplay.java` (reason
  handling), `sms/CustomerSmsSender.java`, `sms/AfricasTalkingGateway.java`,
  `webhook/WebhookDispatcher.enqueue`, `vault/` (how `VaultException` wraps failures),
  `ingest/.../NotificationWiring.java`, `ingest/src/main/resources/application.yml` (the
  datasource), `contracts/kafka/topics.yaml` and the two payload schemas.

## Method

Static review only: `git`, `grep`, `sed`, `cat`. Nothing was built or run (owner's constraint: little
memory, shared machine). PostgreSQL SQLSTATE meanings are from the PostgreSQL error-code appendix.
Kafka client behaviour is from the documented `KafkaConsumer` API (`auto.offset.reset=none`,
`NoOffsetForPartitionException`, `OffsetOutOfRangeException`, `LogTruncationException`). Where only
execution can settle a point, it is marked **needs execution**.

## Verdict: NOT SOUND. 3 MAJOR, 7 MINOR, 6 NIT

The amendment closes most of what it claims. The provider adapter's unreadable reply (impl-2 M2), the
missing validation (M1's triggers), the malformed header (m1), the test claims (M3, m4) and the
restored invariants (m5) are answered correctly. The S3m row is placed right: a corrupt header can
never refuse a send whose block is visible. It does not reopen W-b's neutral time, W-c2's
guarantee, W-e's checkpoints or D-c/D-d replay.

Three gaps remain, each in the amendment's own text:

1. **Re-sending is still possible.** A started provider call is retried whenever the outcome write
   fails in F1 or F4, so one notice can produce many SMS. Section 12.4's title and the status block
   say this cannot happen (M1).
2. **The classification of 12.2 puts shared outages in the wrong class**, in both directions. Some
   go to F3, which dead-letters every record at once. Others go to F4, which dead-letters the head
   of every partition every 10 minutes. Section 9 rejects exactly that behaviour for outages (M2).
3. **12.3 contradicts W-e.** As W-e is still worded, the F4 state is never committed for a partition
   whose budget is empty, so it does not survive a change of owner (M3).

## Findings

| # | Severity | Location | Finding | Failure scenario | Minimal change to the text |
|---|---|---|---|---|---|
| M1 | MAJOR | 12.4, 13.1, status block ("a provider call that has started is never repeated by a retry"), I20, D-g | After a provider call, a failed outcome write re-runs the whole attempt, including the send. In F4 that means up to about 28 sends (10 min at the backoff of 100 ms doubling to 30 s). In F1 there is no bound. 13.1 names only "a second SMS". A `handler_failure` dead-lettered after a send is replayed under D-g without the warning that `rejected_by_the_database` gets, and the replay sends again. | `classify` and `record` use separate connections (`CustomerSmsSender` `tenant()` and `record()`). (a) PostgreSQL's disk fills: `53100` is F4. Every attempt classifies (a read), finds S2, sends, then fails the INSERT. One customer gets about 28 SMS before `handler_failure`, then the next intent on the partition does the same. (b) Connection exhaustion that comes and goes (`53300`, F1): about half the attempts classify, send, then fail to get the second connection. One SMS every backoff interval (30 s or less) for as long as it lasts. (c) An operator replays `handler_failure` after the fix: S2 again, another SMS. | Add to 12.4: "Once the provider call for head X has returned or thrown, the consumer keeps its result (SENT and reference, or FAILED, with locale and link flag) in the partition's state and in the checkpoint metadata. A re-read of X that finds S2, on this owner or the next, writes the kept outcome and never calls the provider again." Any dead letter written after the provider call gets a reason the replay tool refuses unless asked (reuse the `rejected_by_the_database` treatment, or add `outcome_not_recorded`). Then reduce 13.1 to a crash or eviction between the provider call and the checkpoint, and make I20 cover a failing outcome write in F1 and in F4. |
| M2 | MAJOR | 12.2 (F1, F3 default, F4) | F3 is the default for every SQLSTATE not listed, which classes several shared outages as permanent. F4 holds other shared outages, which then hit the 10-minute bound. | **Wrongly F3.** These dead-letter every record on every partition at full speed as `rejected_by_the_database`. That is a reason the replay tool refuses by default, and it mixes records dead-lettered before the send with records dead-lettered after it. Cases: `28000`/`28P01` (database credential rotated; the pool's new connections fail); `3D000`; `25006` (after a failover the pool reaches a read-only node: SMS classifies and sends, then the INSERT fails; every webhook enqueue fails); `42501` (a privilege or RLS change); `42P01`/`42703` (code deployed before its migration, since Flyway is disabled in the application); `XX000`/`XX001` (class XX, internal or data-corrupted, while class 58 is F4). **Wrongly F4.** These dead-letter the head of every partition once every 10 minutes, the "again and again" behaviour section 9 rejects: the vault database down (`VaultContacts` wraps the `08xxx` `SQLException` in a non-permanent `VaultException`); KMS unreachable; Hikari's pool timeout (`SQLTransientConnectionException` with a null SQLSTATE when no attempt failed underneath, `connection-timeout: 2000` on a 20-connection pool shared with the API); `53100`. | (1) The default for an unlisted SQLSTATE is F4, not F3. F3 is only classes `22` and `23`, the data and integrity errors that belong to the record. (2) F1 adds class `28`, `3D000`, `25006`, `SQLTransientConnectionException` and `SQLRecoverableException` whatever their state, and a non-permanent `VaultException` whose cause chain holds an F1 SQLSTATE or a connection failure. State that the classifier walks the cause chain. (3) Move `53100` and `25006` to F1 only together with M1's fix, because in F1 a failure that happens only on writes re-sends without bound. (4) Class `42` and `XX` go in F4: bounded and replayable. |
| M3 | MAJOR | 12.3 ("the F4 state survives a change of owner") against W-e, which was not amended | W-e still says: metadata on "every commit for a partition whose `spent` is above zero"; the per-attempt checkpoint only "while a partition waits"; the revoke commit only for a partition "whose `spent` is above zero"; and "a commit made once `spent` has drained to zero carries no metadata". The code does the same (`WaitBudget.checkpoint` returns `""` at zero; `onPartitionsRevoked` skips empty metadata). A poison record on a partition with nothing spent, the usual case, therefore never commits its F4 state. The `v3` grammar is not given. A `v2` reader (`^fs-wait:v2 …$`) drops `v3` metadata, both budget and F4 state, while instances of both versions share the group. | A poison record (F4 every time) on a partition with `spent` = 0. Each revocation or crash restarts `f4_count`/`f4_ms` and the backoff at 100 ms. A group that rebalances more often than every 10 minutes of owned time (a rolling deploy, autoscaling, or one member in a crash loop) never reaches the bound. The partition stalls until retention, the loop section 9 rejects ("an attempt count kept only in memory"). | Amend W-e: replace "`spent` above zero" with "`spent` above zero **or** F4 state present" in all three places, and checkpoint `OffsetAndMetadata(X, m)` after every failed attempt (F1 or F4), not only while waiting. Give the `v3` grammar exactly, field order included. State the rollout rule: a `v2` reader resets to zero, which happens once per rollout and is accepted; or deploy a `v3`-reading release first. |
| m1 | MINOR | 12.2 ("every failed attempt is in exactly one class") | Two kinds of failure are never classified. (a) An `Error` (not a `RuntimeException`) escapes `handle`, `attempt`, `handleBatch` and `run`, so the consumer thread dies and every partition of the member stops, with nothing counted or alerted. (b) An attempt that never returns: `max.poll.interval.ms` expels the member, the next owner hangs too, and no F4 count ever accrues. | A `NoClassDefFoundError` or `ExceptionInInitializerError` reached from one handler path, or an HTTP call without a timeout. | Say what happens to a `Throwable` that is not an `Exception`: the consumer stops loudly (ERROR, health DOWN, counter), or it is F4. Require every external call in an attempt to have a timeout, so that a hang becomes a failure that can be classified. |
| m2 | MINOR | 12.5 | `auto.offset.reset=none` is under-specified for the Java client. (a) "No committed offset" must come from the client itself (`NoOffsetForPartitionException` from `poll`, or a null from `committed()`), never from a failed `committed()` call: today's listener falls back to `Map.of()` on failure, and a rewind there would re-read 3 days. (b) `OffsetOutOfRangeException` covers both below the log start and above the log end, the latter as `LogTruncationException` after an unclean election or a re-created topic. The rule and the "offsets skipped" count make sense only for the first. (c) Both exceptions must be caught before the poll loop's generic `catch (RuntimeException)`, or the loop spins on `pollBackoff`. (d) After the reset, the committed metadata still names the vanished offset, so the next record inherits the old head's F4 and WARN state. | (a): a broker blip during assignment rewinds a partition to its start. Every record dead-lettered after its send (no outcome) is re-handled and sent again. (d): a fresh head with an inherited `f4_count` of 6 and `f4_ms` of 9 min is dead-lettered as `handler_failure` at its first transient F4. | Say: seek to the beginning only for a partition the client reports as having no offset; on `OffsetOutOfRangeException`, read `beginningOffsets`/`endOffsets` and handle below-start (ERROR, count the start minus the committed offset, seek) differently from above-end (ERROR, no count, seek to the beginning, as for a new partition); drop the head's F4 and WARN state on any reset. |
| m3 | MINOR | 12.3, W-b, W-c | (a) A waiting record whose attempts alternate S4 and F4 is bounded only by W-c. Each S4 clears the F4 state, and F4 time is neutral. Because `backoffMs` is not reset by a WAIT, each cycle is about 500 ms of waiting plus up to 30 s of neutral time, so the wall time is about 10 h. That is bounded, but nowhere stated. (b) An attempt that fails in F4 at the bound and whose DLQ send then fails is F1 by "first matching row". Whether its increment of `f4_count` stands is not said. (c) W-b's "fails (S5, or the DLQ send fails)" and W-c's "repeated on the backoff until it succeeds" were not amended for F1/F4. | (a) `57014` under load alternating with S4. | State (a) and its bound. Say that the F4 increment stands when the dead-letter send then fails. Amend W-b and W-c to "F1 or F4 (12.2)", and in W-c "until it succeeds, or until 12.3's bound for F4". |
| m4 | MINOR | Section 8: I14, I9, I18, I22 | I14 is restored word for word, but its row names its tests as proof of "at most 500 ms of waiting lost" and only defers the question. Under the owner's rule that is a present claim. The crash test asserts "less than a fresh budget" (impl-2), so the row must say **in part** now. Its 500 ms also contradicts W-e's "500 ms plus at most the thread's current batch". I9's structural test counts only `prepareStatement` (impl-2 n7) but is not marked in part. I18 covers F1 only, though 12.5 also warns on F4. I22 leaves out the start of a partition with no committed offset. | A reader takes I14 and I9 as proved as worded. | Mark I14 (the 500 ms) and I9 (structural half) **in part**. Align I14's 500 ms with W-e. Extend I18 to the F4 WARN and I22 to the no-offset start. |
| m5 | MINOR | 12.6 | Several counts cannot be tested exactly (I23). `malformed_headers`: "each record read" does not say whether a re-read after F1 counts again. `dead_letters`: whether a second copy after a crash before the commit counts (I14b). `retries`: whether the F4 at the bound counts (it is not retried). `head_failing_seconds`: when it starts and what resets it (an S4 between failures?). The possibly-delivered FAILED of 12.4 has no counter, although the status block says "each of these is counted". | | Define each count per attempt or per (partition, offset), and per acknowledged DLQ send. Define the gauge's start and reset. Add `fs_notify_sms_outcome_unknown_total` (or a `reason` tag on a FAILED counter). |
| m6 | MINOR | 12.4, 13 | (a) FAILED now also means "may have been delivered", but `CustomerSmsSender.Outcome.FAILED` is documented as "Not sent". Today only `classify` reads FAILED (as S1), so D-d still holds. The contract should still fix the meaning before any reader treats FAILED as safe to send again. (b) A verification issued and then an F4 before the provider call (a null `officialPhone` or `maskedAccount` reaches `SmsCatalogue`) uses up the block's only link: when the cause is fixed, the SMS goes without one. This is not in section 13. | (b) An institution's settings row lacks `official_phone` for 20 minutes. Its intents become `handler_failure`, and after replay their SMS carry no link. | Define FAILED as "not known to be accepted by the provider", for any reader. Add (b) to section 13 (or issue the link after rendering succeeds). |
| m7 | MINOR | 12.2 F4 example, `application.yml` | "`57014`, a statement that always times out" is F4 in the text. With Hikari's `socketTimeout: 10` and no `statement_timeout`, a statement slower than 10 s fails as `08006`, which is F1, unbounded. `08P01` (protocol violation) is also F1 although it is usually deterministic for a statement shape. | A statement made slow by a plan regression on one tenant's data stalls that partition without bound. | Set `statement_timeout` below `socketTimeout` for the consumers' connections, or say that a socket timeout is F4. Move `08P01` to F4. |
| n1 | NIT | 12.1 webhook | `transaction_id` and `event_id` are validated even for `decision_sequence` 1, which `enqueue` returns from without reading them. `decision_sequence` should also be required to fit an `int` (`asInt`). | | "for `decision_sequence` ≥ 2", and "an integer from 1 to 2³¹−1". |
| n2 | NIT | 12.1 SMS, status block | An amount `Money` refuses used to be caught as `IllegalArgumentException` and recorded FAILED. It is now `malformed_envelope` with no outcome row. That is a behaviour change the status block does not list. | | List it. |
| n3 | NIT | 5.3 S5 | "PostgreSQL did not answer" no longer covers S5, which now includes F3 errors from `classify` (`42P01`). | | "the statement fails". |
| n4 | NIT | 12.2 F1 (`DeadLetterNotSent`) | Some DLQ send failures are deterministic for one record: `RecordTooLargeException` when the `fs-dlq-*` headers push a record near the limit over `max.request.size`; `InvalidTopicException`. These loop without bound as F1. | | Make these F4, or dead-letter-impossible: log at ERROR, count, and commit past. |
| n5 | NIT | 5.1 and 11.14 | `Envelope` carries the headers as a map, last value per name, and `headers()` drops null values. A repeated header and a null-valued one, both malformed under 5.1, cannot be seen there. | | Say that the three-way state is computed from `record.headers().headers(name)`, before the map. |
| n6 | NIT | D-g and S3m | "Replayed once their cause is fixed" does not fit S3m: its header is never fixed, and it is sent once B exists, which is D-c2's condition. It shares `malformed_envelope` with envelopes that are truly unreadable. | | Say that S3m copies are replayed under D-c2's condition. Optionally give them their own reason, `malformed_header`. |

## a. Does it close what it claims?

| Finding | Closed? | Note |
|---|---|---|
| impl-2 M1 (unbounded RETRY) | **in part** | Closed for failures of the record itself: validation (12.1) and the F4 bound (12.3). Still open: across owners (M3), for shared outages put in F4 or F3 (M2), for `Error` and hangs (m1), and after the provider call (M1). |
| impl-2 M2 (re-send on an unreadable reply) | **in part** | The adapter's reply is closed: `IOException`, then FAILED. A re-send after a failed outcome write stays, unbounded in F1 (M1). |
| impl-2 M3 (false test claim) | yes | The "(malformed)" row is replaced by I19, marked "to be written". |
| impl-2 m1 (malformed header) | yes | Three states, a strict parse, S3m. Implementability: n5. |
| impl-2 m2 (glue untested) | yes, as a claim | I21 requires the test to go through `Envelope` and the wiring. |
| impl-2 m4 (I10 overstated) | yes | Restated as "to be proved for every reason". |
| impl-2 m6 (SQLSTATE loops) | **in part** | `58`, `53400` and `57014` are now bounded. The F1/F3/F4 boundary is wrong elsewhere (M2, m7). |
| impl-2 m8 (metrics) | in part | Named and typed. Counts are not precise enough to test (m5). |
| impl-2 m5 (narrowed invariants) | yes, text | Identical to draft 5. The proof markings are not fully honest (m4). |
| impl-2 m7 (known gap) | understated | 13.1 says "a second SMS". F1 gives any number, and F4 about 28 (M1). |

Remaining paths:

- **Loop for ever:**
  - F4 state lost on each change of owner (M3);
  - an `Error` kills the thread, or an attempt hangs (m1);
  - a DLQ send that fails the same way every time (n4);
  - a statement past `socketTimeout` (m7).
- **Sent twice by a retry:** M1, and m2(a).
- **Lost silently:** none found. The retention reset is now visible. m2(b) is a case where the
  count would be wrong, not silent.

## b. The failure classification (12.2)

Not complete, and in places not correct (M2, m1, m7, n4). SQLSTATE by SQLSTATE:

- **F1 as listed is right:**
  - class `08`, except `08P01`;
  - `40001` and `40P01`;
  - `57P01`, `57P02` and `57P03`;
  - `53300`.
- **`57014` (`query_canceled`) is right in F4.** It comes from `statement_timeout`, a client
  cancel or `pg_cancel_backend`. In this deployment, though, a slow statement surfaces as `08006`
  through `socketTimeout` (m7).
- **`53100` (`disk_full`) is a shared outage.** It belongs in F1, but only once M1 is fixed. Until
  then F4 is the lesser harm, about 28 sends rather than unbounded.
- **`53200` (`out_of_memory`) is right in F4.** It can be specific to one query.
- **`08P01` (`protocol_violation`) belongs in F4.** It is deterministic for a statement shape or a
  driver bug, not an outage.
- **The F3 default is the dangerous direction:** a misconfiguration or a failover becomes a mass
  dead letter under a reason that replay refuses (M2).

"F1 outage is unbounded" is acceptable together with 12.5, on two conditions:

- **F1 holds only shared outages.** One record cannot keep its partition in F1. Today n4 and m7
  allow that.
- **No side effect repeats per F1 retry.** Today M1 allows that.

12.5 then makes a loss after 3 or 7 days visible (ERROR and counter, alert in M9), which is the
stated and acceptable trade-off. "Exactly one class" also needs the cause-chain rule (M2), because
`VaultException` and Hikari wrap the `SQLException`.

## c. The bound (12.3)

Well defined within one owner:

- "Consecutive" ignores F1 failures, and only a successful attempt (in practice S4 or the grace
  re-read) clears the state.
- `f4_ms` counts only the gaps from an F4 failure to the next attempt, so an F1 outage between them
  adds at most one backoff interval.
- Changes of owner do not add unowned time.
- A replay starts fresh and is bounded again (D-g).
- F4 time stays neutral for the budget, consistent with W-b and W-c2.

The weak points:

- **Unbounded across owners** while W-e is as worded (M3).
- **The S4/F4 alternation** is bounded only by W-c, at about 10 h of wall time (m3).
- **A record that would have succeeded can be dead-lettered** only through inherited state after a
  retention reset (m2(d)), or because a shared outage is classed F4 (M2). A deterministic failure of
  the record itself, once fixed, is replayable (D-g).
- **The v2/v3 change** is not specified for a group running both versions (M3).

## d. The provider call (12.4)

Achievable in the code: wrap `gateway.send` alone in `catch (IOException | RuntimeException)`, with
the parse failure converted inside the adapter. `encode(null)` runs inside `send` and so becomes
FAILED, which is consistent.

It does not hold on every path:

- **Verification issued, then the send fails:** FAILED, then S1. Correct.
- **Verification issued, then an F4 before the send:** the link is used up (m6(b)).
- **Outcome write fails:**
  - in F3, one send, then `rejected_by_the_database`. Correct;
  - in F4, about 28 sends (M1);
  - in F1, unbounded (M1).

FAILED for a possibly-delivered SMS is the right choice for not sending again: S1 is reached, and
D-d's replay guidance holds for every reason except a `handler_failure` dead-lettered after a send
(M1). The meaning of FAILED must be redefined (m6(a)).

## e. S3m and the header states

Correct and consistent:

- The order S0 → S1 → S2r → S2 → S3 → S3m → S4 means a malformed header only matters when B is
  absent.
- S3 cannot match with `kept` null.
- I15's header-less path is unchanged.
- Replay copies the header verbatim (D-a, D-c), so a replayed S3m is sent once B exists, and
  otherwise returns to the DLQ at once without waiting.
- A malformed header never refuses a send whose block is visible.

The cost is that a late B (milliseconds of W2 lag) with a corrupt header becomes a dead letter that
needs a replay rather than a wait. That is within what is replayable, and section 9 explains the
choice. Remaining points: implementability (n5) and replay guidance (n6).

## f. `auto.offset.reset=none` (12.5)

The direction is right: with `none`, the client throws instead of resetting, so the consumer can log
and count. But the text does not say:

- where each exception surfaces (`poll` and `position`);
- that both must be caught before the generic poll handler;
- that "no offset" must never be inferred from a failed `committed()` call;
- how below-start differs from above-end (`LogTruncationException`);
- that the old head's state must be dropped (m2).

Interaction with pause, seek and rebalance is otherwise sound:

- A paused partition is not fetched, so the exception arrives when it is resumed.
- `seek(X)` on a deleted X surfaces on the next fetch.
- Records already fetched for other partitions are returned before the exception is thrown.
- Records removed by retention were never fetched, so W-a's "no fetched record is skipped" is
  respected.

`DeadLetterReplay` (no group, assign and seek) is unaffected.

## g. Metrics (12.6)

The names follow the code base's convention (`fs_*_total` counters through Micrometer, as
`fs_decisions_total` and `fs_spool_lag` do), and the types and tags are sound. The `reason` tag has
D-a's six values, and the `class` tag the two retried classes. Precision is insufficient for I23 on
four counts, and one promised count is missing (m5).

## h. Section 8

- **I7e, I8, I9 and I14** are identical in substance to draft 5 (compared line by line with
  `1288143`).
- **I7e** is honestly marked in part (PB-78).
- **I14 and I9** are not honestly marked (m4).
- **I17 to I24** each map to section 12:

  | Invariant | Section | Note |
  |---|---|---|
  | I17 | 12.3 | |
  | I18 | 12.5 | F1 only |
  | I19 | 12.1 | |
  | I20 | 12.4 | understates M1's write path |
  | I21 | 5.1 and S3m | |
  | I22 | 12.5 | below-start only |
  | I23 | 12.6 | |
  | I24 | 12.2 | |

  Each is testable. "To be written" is honest: no row claims a test that does not exist.
- **I24's "by review ... plus one test per class"** is acceptable under the owner's rule, because
  the review half is labelled as such.

## i. Other

- **The status block omits W-e and W-b**, both of which must change for 12.3 to hold (M3, m3). It
  also omits two behaviour changes (n1, n2).
- **No undefined terms** beyond the `v3` grammar (M3).
- **ADR 0056's amendment and the M6_updates claims** (PB-77, PB-78) match the contract.

## Needs execution (after the code round)

1. M1: `SmsOrderingTest` with a `DataSource` whose second connection fails (`53300`) or whose INSERT
   fails (`53100`) after `classify` succeeds. Assert exactly one provider call over several re-reads.
2. M3: `EnvelopeConsumerTest`, a poison record on a partition with `spent` = 0, with members joining
   and leaving every few seconds. Assert that it is dead-lettered as `handler_failure` within the
   configured owned time.
3. m2: a short-retention topic with a held head. Assert the ERROR, the count, and that the new head
   does not inherit the old F4 state. Also a `committed()` failure during assignment, asserting that
   no rewind happens.
