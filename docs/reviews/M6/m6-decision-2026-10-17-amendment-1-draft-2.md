# M6 review: amendment 1, draft 2, to the decision-fact ordering contract (2dafbf0)

Reviewer: a fresh, independent Principal reviewer. I did not write the amendment or the code.

## Scope

- `git diff b7eefd5 2dafbf0`, then the whole of `docs/architecture/decision-fact-ordering.md` as it
  stands at 2dafbf0 (read in full, not only the diff), ADR 0056's amendment section and the last
  section of `docs/parallel/M6_updates.md`.
- The findings it answers: `m6-decision-2026-10-17-implementation-2.md` (M1, M2, M3, m1, m2, m3, m4,
  m6, m8; m5 restored; m7 named as a gap) and `m6-decision-2026-10-17-amendment-1.md` (draft 1:
  3 MAJOR, 7 MINOR, 6 NIT, cited `[A1-…]`).
- The restored invariants I7e, I8, I9 and I14, compared line by line with
  `git show 1288143:docs/architecture/decision-fact-ordering.md` (draft 5, reviewed sound).
- Code read only to judge whether the text can be implemented and is complete:
  `notify/kafka/EnvelopeConsumer.java`, `WaitBudget.java` (checkpoint and resume),
  `DeadLetterReplay.java` (reason refusal), `sms/CustomerSmsSender.java` (classify, record, render,
  send), `sms/AfricasTalkingGateway.java`, `sms/SmsCatalogue.java`,
  `verification/VerificationService.issue`, `webhook/WebhookDispatcher.enqueue`, `vault/`
  (`VaultException`, `VaultContacts.find`, `KmsClient`, `KmsKeyProvider`),
  `ingest/.../NotificationWiring.java`, `ingest/src/main/resources/application.yml`,
  `contracts/kafka/topics.yaml`, the section 8 test names in `EnvelopeConsumerTest`,
  `WaitBudgetTest`, `SmsOrderingTest` and `VerificationFlowTest`, and migration `V5` (the
  `provider_reference` column).

## Method

Static review only: `git`, `grep`, `sed`, `cat`. Nothing was built or run (owner's constraint: little
memory, shared machine). Kafka client behaviour is from the documented `KafkaConsumer`,
`KafkaProducer` and `ConsumerRebalanceListener` APIs (the listener's `onPartitionsRevoked` is also
called from `KafkaConsumer.close()`; `delivery.timeout.ms` set explicitly below
`linger.ms + request.timeout.ms` is a `ConfigException`; `LogTruncationException` is a subclass of
`OffsetOutOfRangeException` carrying `divergentOffsets()`). PostgreSQL behaviour is from its manual
(`statement_timeout` and `SET LOCAL` apply inside a transaction block only). Points only execution
can settle are marked **needs execution**.

## Verdict: NOT SOUND. 2 MAJOR, 9 MINOR, 8 NIT

Draft 2 answers draft 1 well. All three of draft 1's MAJORs are closed. The provider's result is
kept and checkpointed, so a failed outcome write no longer sends again. Shared outages are F1, and
F3 is now only classes `22` and `23`. W-e now carries the retry state on every commit and in the
revoke commit, in an exact `v3` grammar. The four restored invariants match draft 5 word for word;
the one change, I14's bound, is justified. W-b neutral time, W-c2's guarantee, the W-e checkpoints
and D-c/D-d replay are not reopened, apart from one stale paragraph in D-d (m6).

The two MAJORs come from the new text of 12.2, and each has a small fix:

1. **The `Error` rule loses records silently.** "Closes (releasing its partitions to the group)"
   runs `onPartitionsRevoked`, which commits `position(p)`. Mid-batch, that position is past every
   fetched record, the one that threw included (M1).
2. **The dead-letter producer's failures are split so that shared Kafka faults drop records.** A
   timed-out future, an ACL or authentication error, or `InvalidTopicException` on the fixed
   `<topic>.dlq` falls under "not retriable". That is F4, and at the bound the record is committed
   past with no copy anywhere, `parent_not_recorded` intents included (M2).

## Closure of the draft-1 findings

| Finding | Closed? | Why |
|---|---|---|
| A1-M1 (outcome write re-runs the send) | **closed** | 12.4 keeps R, checkpoints it (W-e, W-e2), and a re-read writes R in S2 or S2r without calling the provider. F1 retries only write R. A dead letter while R is kept is `outcome_unrecorded`, which D-c refuses by default. 13.1 is reduced to a crash before the checkpoint. Residual: when the checkpoint is taken, and so how wide the window is, is not fixed (m1). D-d still has the old text (m6). |
| A1-M2 (shared outages misclassified) | **closed for SQL; in part for KMS** | The default is now F4, and F3 is only `22`/`23`. F1 gains `28`, `3D000`, `25006`, `53100`, `SQLTransientConnectionException` and `SQLRecoverableException`, and the cause chain is walked. `42` and `XX` are F4. "A KMS call the client reports as unreachable" cannot be implemented against today's `KmsClient` contract (m4). The DLQ side over-reaches in the other direction (M2, a new defect). |
| A1-M3 (W-e does not carry F4 state) | **closed** | Three places amended. There is a checkpoint after every failed attempt and on keeping R. The grammar is exact, and the rollout rule is stated (no deployed `v2`-only reader; two-step from now on). |
| A1-m1 (`Error`, hangs) | **in part** | The rule exists, but its "closes" step skips records (M1) and its "DOWN" can cascade (m8). The vault has no socket timeout, so a hang is still possible (m3). |
| A1-m2 (`auto.offset.reset=none`) | **closed, one new point** | (a), (c) and (d) are answered. (b) is answered, but the above-end target re-reads the whole retention (m5). |
| A1-m3 (S4/F4 alternation, F4 increment at a failed DLQ send, W-b/W-c) | **closed** | S4 no longer clears the F4 state. The increment stands. W-b and W-c are amended. |
| A1-m4 (section 8 honesty) | **closed** | I14 and I9 say **in part**. I14 cites W-e's bound. I18 covers the F4 WARN, and I22 the no-offset start. |
| A1-m5 (counts not exact) | **closed** | Each count is defined per attempt or per (partition, offset), with the gauge's start and reset. `fs_notify_sms_outcome_unknown_total` is added (one wording point: n4). |
| A1-m6 (meaning of FAILED; link used up) | **in part** | FAILED is redefined for every reader. Render-before-issue is required, but the placeholder is under-specified (m2). |
| A1-m7 (`statement_timeout` vs socket timeout; `08P01`) | **closed for the main pool; not for the vault** | 5 s below 10 s, and `08P01` is F4. The vault's `PGSimpleDataSource` has no socket timeout, and its read is not in a transaction (m3). |
| A1-n1 | closed | `decision_sequence` ≥ 2 for the ids; 1 to 2³¹−1. |
| A1-n2 | closed | Listed in the status block. |
| A1-n3 | closed | S5 is "the statement fails". |
| A1-n4 | **closed, but over-broad** | It was answered with a drop rule whose trigger, "not retriable", catches shared faults (M2). |
| A1-n5 | closed | Computed from `record.headers().headers(name)`. |
| A1-n6 | closed | `malformed_header`, replayed under D-c2. |

## Findings

| # | Severity | Location | Finding | Failure scenario | Minimal change to the text |
|---|---|---|---|---|---|
| M1 | MAJOR | 12.2, the `Error` bullet ("closes (releasing its partitions to the group)"); W-a "No skipped records"; I26 | Closing a subscribed `KafkaConsumer` invokes `onPartitionsRevoked`. W-e makes that listener commit `OffsetAndMetadata(position(p), m)` for every partition with `spent` above zero, retry state or a kept result. When an `Error` escapes mid-batch, `position(p)` is past every record `poll` returned: the one that threw, and the unhandled tail of every partition in the batch. W-a's seek-back is specified only for an exception, and the code runs it only for `RuntimeException`. The consumer therefore commits past records it never handled or dead-lettered. That breaks W-a, and contradicts 12.2's own "stops each member that reads it in turn", because the next member never reads it. | Partition 3 waited recently (`spent` > 0). A batch returns offsets 100–140 on partition 3 and 200–260 on partition 5 (with retry state). The handler throws `NoClassDefFoundError` at 3:120. The consumer logs, counts, reports DOWN and closes. The revoke listener commits 3:141 and 5:261. Offsets 3:120–140 and 5:200–260, all valid intents, are never sent and never dead-lettered. Only `fs_notify_consumer_errors_total` moves, and nothing names the skipped offsets. | In 12.2: "Before it closes, the consumer seeks every partition of the current batch back to its first unhandled offset (W-a), the record that threw included, so that the revoke commit carries that offset; a partition whose position cannot be set is treated as lost and not committed." In W-a, "if any call … throws" becomes "throws an exception or an `Error`". I26: assert that after the stop the next owner re-reads the record that threw and the ones behind it. |
| M2 | MAJOR | 12.2, F1 row ("the dead-letter send failing with a `RetriableException`"), F4 row ("an exception that is not retriable (`RecordTooLargeException`, `InvalidTopicException`)"), the drop bullet; 12.3 (DLQ timeout); I27 | The split looks at the producer's exception type, not at whether the fault belongs to the record. Three shared faults land in F4, and so in "dropped" at the bound. (1) The send's future is awaited with its own timeout (10 s in the code). Kafka's `delivery.timeout.ms` defaults to 120 s, and 12.3 only caps it. A slow DLQ partition therefore surfaces as `java.util.concurrent.TimeoutException`, which is not a Kafka `RetriableException`. (2) `TopicAuthorizationException`, `ClusterAuthorizationException` and `AuthenticationException` are not retriable, yet they come from ACLs and credentials (`topics.yaml`: ACLs per topic). (3) `InvalidTopicException` is listed as record-specific, but every record of a consumer goes to the same `<topic>.dlq`. The drop log keeps only the value's SHA-256, and no tool reads the source topic by offset. | An ACL change removes WRITE on `fs.notifications.customer.dlq` for 30 minutes, or its leader is unavailable longer than the future's wait. Each partition head that is due a dead letter (an S3, a `parent_not_recorded` after a W2 stall, an F2) fails the send, is F4, and after 10 minutes is committed past as "dropped". Then the next head does the same. Over the incident, roughly three records per partition (36 on 12 partitions) are gone. The `parent_not_recorded` ones are valid notices that a replay would have sent. Recovery is by hand from the source topic within its 3 days, with only a hash to check against. | F1: "the dead-letter send fails for any reason other than the record's own size: any Kafka exception, an authorization or authentication error, `InvalidTopicException`, or the future's wait timing out". F4 and drop: "only `RecordTooLargeException` (the record with the `fs-dlq-*` headers exceeds `max.request.size`)". In 12.3: "the future is awaited for at least `delivery.timeout.ms`; `request.timeout.ms` + `linger.ms` ≤ `delivery.timeout.ms` ≤ 30 s". In the drop bullet: "the ERROR also names the source topic, partition and offset to recover from; the record stays there for the topic's retention". I27: also test a DLQ without WRITE ACL, which must be F1. |
| m1 | MINOR | 12.4 ("checkpoints it … before X is re-read"); W-e ("as soon as a provider result is kept for X"); 13.1 ("one provider call plus one `commitSync`") | When R is checkpointed is not fixed. The sender records inside the same handler call as the send, and the consumer commits only at the end of the batch. Read that way, the window is: provider call, plus the failing outcome write (up to the 10 s socket timeout), plus every other partition's record in the batch, plus the `commitSync`. If that commit fails and the partition is then lost, the window lasts until a commit carrying R succeeds or the outcome row commits. 13.1 states one provider call plus one commit. | A member is killed (OOM, node loss) while the outcome INSERT waits on a socket timeout during a database blip, or while the batch handles other partitions' S2 sends (about 8 s each through the gateway). The next owner sends a second SMS without a link, a case 13.1 describes as well under a second. | Choose and state: "the handler hands R to the consumer, which commits `OffsetAndMetadata(X, m)` synchronously right after the provider call and before the outcome write; if that commit fails, the outcome write proceeds and R stays in memory and on the next commit". 13.1: "the window runs from the provider call until a commit carrying R, or the outcome row, succeeds". |
| m2 | MINOR | 12.4, render before issue; I25 | "Rendering is attempted with a placeholder link" is under-specified for this catalogue. `SmsCatalogue.autoBlock` refuses a link longer than `MAX_LINK_LENGTH` and any text over one GSM-7 segment, and it picks the `link` or `no_link` template from whether a link is present. (a) A placeholder shorter than `verificationBase + token` passes the trial, and the real render then fails after `issue`, which uses up the link (FAILED, no SMS). (b) `issue` returns empty on a re-attempt (once per block), and the `no_link` template is then rendered without a trial. (c) Nothing says the placeholder text is never sent, or that a re-read with R skips the vault, the render and `issue`. | An institution's `verification_base` is longer than the placeholder: the trial passes, `issue` writes the token, the real text is 161 septets, and the result is FAILED with the link used up, which is the case the change exists to prevent. | "The placeholder is a link of exactly the real link's length (base plus a token of the minted length). When the link is not allowed, or `issue` returns none, the `no_link` template is rendered, and it is trial-rendered too before `issue`. The placeholder text is never sent. A re-read holding R runs only the classification and the outcome write." |
| m3 | MINOR | 12.3 timeouts ("PostgreSQL, the vault included … below the pool's socket timeout"); 12.3 `max.poll.interval.ms`; 11.17 | (a) The vault's `DataSource` is a bare `PGSimpleDataSource` (`NotificationWiring.vaultDataSource`). It has no pool, no `socketTimeout` (PgJDBC's default 0 means for ever) and no explicit connect or login timeout. `VaultContacts.find` runs in autocommit, so "each transaction the consumers open sets `statement_timeout`" never reaches it (`SET LOCAL` has no effect outside a transaction block). A vault that stops answering still hangs the attempt, which is A1-m1(b). (b) "`max.poll.interval.ms` above `max.poll.records` × the longest attempt": with the default 500 records and an attempt of about 30 s or more, that is hours, and W-c2's g and W-e's crash bound grow with the batch. | A network partition to the vault host: the consumer thread blocks in `executeQuery` with no limit. With (b), the member is not expelled for hours, and every partition it owns stops. | Name the vault data source's `socketTimeout` (10 s), `connectTimeout` and `loginTimeout`, and a `statement_timeout` set per connection (`options=-c statement_timeout=5000`, since the vault connection is not shared with the API). Bound `max.poll.records` (for example 50) and derive `max.poll.interval.ms` from it. List both in 11.17. |
| m4 | MINOR | 12.2 F1 ("a KMS call the KMS client reports as unreachable or timed out"); 11; status block | `KmsClient`'s contract makes "cannot be reached" and "a key it does not have (yet)" the same non-permanent `VaultException`, and the binding is the deployment's (M9). The F1/F4 split cannot be implemented without changing that contract, and neither section 11 nor the status block lists the change. KMS throttling or an IAM change, both shared, fall into F4 and dead-letter a head per partition every 10 minutes, the behaviour section 9 rejects. | KMS throttles for 20 minutes. Every partition's head becomes `handler_failure` every 10 minutes, about 24 records, all replayable but not expected. | Classify by cause: F1 when the chain holds `ConnectException`, `SocketTimeoutException`, `HttpTimeoutException` or `UnknownHostException`, or an exception the `KmsClient` marks as unavailable or throttled. Amend `KmsClient`'s contract (and `KmsClientContract`) to require that marking and the 5 s timeout. List it in section 11 and the status block. |
| m5 | MINOR | 12.5 "above the log end … seeks to the log start"; I22 | After an unclean truncation, the right target is `LogTruncationException.divergentOffsets()`, or the log end. Seeking to the log start re-reads the whole retention (3 or 7 days). Most records are S1 and harmless. But every record whose outcome was never recorded is S2 again and **sent again**: the `outcome_unrecorded` ones, and those lost in a 13.1 crash. Every S3, S3m and `parent_not_recorded` is dead-lettered again. | An unclean election on `fs.notifications.customer` partition 4 truncates past the committed offset. The consumer restarts from 3 days back and re-sends each of that partition's `outcome_unrecorded` intents. | "Above the log end: seek to the divergent offset `LogTruncationException` gives, or else to the log end; log at ERROR. Start at the log start only for a partition the client reports as having no offset (a new or re-created topic)." I22 to match. |
| m6 | MINOR | 7, D-d (second and third bullets); status block ("D-a, D-c, D-g … changed") | D-d was not amended. It still says that `rejected_by_the_database` can follow a provider acceptance, and that this is why the tool refuses it. Under 12.4, any dead letter written while R is kept is `outcome_unrecorded`, so `rejected_by_the_database` now always comes before any send. D-d also does not name `outcome_unrecorded` or 13.1. This is a contradiction between sections on the replay-safety rule an operator reads. | An operator reads D-d, takes `rejected_by_the_database` to be the risky reason, and replays `outcome_unrecorded` with the flag, expecting only S1s. | Rewrite D-d's bullets: "excludes a crash in 13.1's window and a record dead-lettered as `outcome_unrecorded`; the tool refuses `outcome_unrecorded` unless asked. `rejected_by_the_database` is now always written before any send; it stays refused by default only as a precaution." List D-d in the status block. |
| m7 | MINOR | Section 8: I11, I1, I18, I26; W-e2 | Section 12 has untested or uncovered behaviour, and I18 contradicts 12.6. (a) D-c's refusal of `outcome_unrecorded` is in no row; I11 names only `rejected_by_the_database`, which the test asserts. (b) W-e2's grammar has no row: `v2` accepted, an unparseable line read as none with a WARN, a `~` truncation round trip. (c) I26 tests only `statement_timeout` among the timeouts, and not M1's no-skip. (d) I1 leaves out S3m ("nothing done" while B is absent). (e) I18's "every failed attempt … is counted" contradicts 12.6, where the attempt that reaches the F4 bound is not counted as a retry. | A regression that replays `outcome_unrecorded` by default, or a `v3` parser that accepts trailing garbage, passes every listed test. | Add `outcome_unrecorded` to I11's claim and test. Add an invariant for W-e2 (parse, round trip, `v2`, garbage → none + WARN). Extend I26 (vault, KMS and DLQ timeouts; no skip on an `Error`) and I1 (S3m). Make I18 "counted as 12.6 defines". |
| m8 | MINOR | 12.2 `Error` bullet ("reports itself DOWN to the application's health check"; "stops each member that reads it in turn"); `NotificationWiring` | (a) The consumers run inside the ingest API (`NotificationWiring`), and the only health endpoint exposed is `/actuator/health`. A DOWN contributor there makes the aggregate 503. Any probe or load balancer on it takes the decision API out of service, and because each member stops in turn, every instance goes. A liveness restart then crash-loops on the same record. (b) The webhook delivery loop (`scheduleWithFixedDelay`, which catches only `SQLException` and `RuntimeException`) is silently cancelled by an `Error`. Section 1 says section 12 covers the webhook dispatcher, and this path is still silent. | One SMS intent that triggers an `ExceptionInInitializerError` turns into a fraud-decision outage. | Report the stop in a separate health group (for example `notify`) that is excluded from the API's liveness and readiness groups, and state it. Optionally stop only the partition for an `Error` that is not a `VirtualMachineError`. Wrap the delivery task so that an `Error` is logged, counted and reported the same way. |
| m9 | MINOR | 12.4 ("Its `fs-dlq-error` records R"), D-g ("unless its outcome has since been recorded"); `V5` `provider_reference text CHECK (char_length ≤ 128)`; W-e2 truncation | (a) Nothing ever records R for an `outcome_unrecorded` record. The replay tool strips `fs-dlq-*` and would send again, and no tool or procedure writes the outcome row. D-g's "since been recorded" therefore has no mechanism, and the notification stays REQUESTED for ever. (b) W-e2 truncates the reference at 1,024 bytes of metadata, but the column refuses more than 128 characters. A reference of 129 characters or more fails the outcome write with `23514` (F3), so a delivered SMS becomes `outcome_unrecorded` at once. | (b): a provider or proxy that returns a 200-character message id. Every SENT becomes `outcome_unrecorded`, and replay is refused. | (a) State the procedure: an operator inserts the outcome from `fs-dlq-error`, or a `--record-outcome` mode of the tool, carried to M9 with `PB-77`. Or accept that the row stays REQUESTED, and say so. (b) "The reference is truncated to 128 characters (the column's limit) and marked `~`, in the outcome row and in R alike." The 1,024-byte bound then always holds. |
| n1 | NIT | W-e2 | Whether the `~` truncation applies to the raw reference or to its base64url form is not said. The same for whether an empty reference (the adapter returns `""` when `messageId` is missing) is allowed after `SENT:<locale>:<link>:`. | | "`<reference>` is base64url of the reference as recorded (after 128-character truncation, m9), possibly empty." |
| n2 | NIT | 12.2 "the first row … that matches any exception in the chain" | Two edge cases. (a) An explicit classification by a wrapper should beat its causes: a permanent `VaultException` whose chain held a timeout would become F1 and loop. (b) An attempt with two failures (the handler's, then the DLQ send's) is not covered by "the exception's cause chain". | | "A permanent `VaultException` or a `MalformedPayloadException` decides before its causes. When the dead-letter send fails, the attempt is classified by the send's exception alone." |
| n3 | NIT | 12.3 bound ("at the end of an attempt that did not handle the record"; "10 minutes of F4 wall time") | The bound is tested at S4 and F1 attempts too. A waiting record with five earlier F4s is dead-lettered as `handler_failure` at an S4 answer, and D-c2's default replay (`parent_not_recorded`) misses it. "F4 wall time" does not match `f4_ms`'s definition, which includes S4 time. | | Test the bound at the end of an F4 attempt only, or say the reason may be `handler_failure` for a record that was last S4. Use "`f4_ms`" for "F4 wall time". |
| n4 | NIT | 12.6 `fs_notify_sms_outcome_unknown_total` | "Whose call threw after it may have reached the provider" is not exact. It does not say whether a connect timeout counts, or a request timeout, or an HTTP 5xx. | | List them: a request timeout, an unreadable 2xx and any unchecked exception count; a connect failure and a non-2xx do not. |
| n5 | NIT | D-c | "Unless the operator passes them explicitly" differs from the tool, which also needs `--allow-rejected` (`DeadLetterReplay.replay`). The text does not say which flag allows `outcome_unrecorded`. | | "Unless `--allow-rejected` is given (covering both)", or a flag per reason. |
| n6 | NIT | 12.3 DLQ timeout | Setting `delivery.timeout.ms` explicitly to 30 s or less is a `ConfigException` when `linger.ms + request.timeout.ms` exceeds it. Kafka 4's defaults are 5 ms and 30 s. | | Covered by M2's fix: set `request.timeout.ms` too. |
| n7 | NIT | 12.2 drop bullet | A dropped record whose head held R loses R without naming N (12.5's reset names N; the drop does not). | | "If R is kept, the ERROR names N and R." |
| n8 | NIT | I7d; `M6_updates.md` PB-78 | I7d still says "a RETRY", a term the contract no longer defines. PB-78's backlog row says "resolved" before the code round has run. | | "an F1 or F4 failure"; "to be resolved in this round". |

## a. Closure

Draft 1's findings: the closure table above. Every MAJOR is closed. A1-m1, A1-m6 and A1-m7 are
closed in part, and A1-n4 is closed in a way that creates M2.

Implementation-2, at contract level:

| Finding | Closed? | Note |
|---|---|---|
| M1 (unbounded RETRY) | yes, with M1/M2 above | 12.1 (validation), 12.2 (classes), 12.3 (bound, checkpointed across owners), 12.5 (no silent reset). The two new loss paths are in the new text, not in the old loop. |
| M2 (re-send on an unreadable reply) | yes | The adapter returns `IOException`. Any exception from the call is FAILED. R is kept, so no second call. |
| M3 (false test claim) | yes | The "(malformed)" row is replaced by I19, to be written. |
| m1 (malformed header) | yes | Three states, a strict parse, computed before the map, S3m, `malformed_header`. |
| m2 (glue untested) | yes, as a claim | I21 goes through `Envelope` and the wiring. I3a is marked in part until then. |
| m3 (I7e move not shown) | yes, as a claim | I7e is in part until this round asserts the move (`PB-78`). |
| m4 (I10 overstated) | yes | "To be proved for every reason." |
| m6 (SQLSTATE loops) | yes for SQL | 58, 53400 and 57014 are bounded. The vault and KMS gaps are m3 and m4. |
| m8 (metrics) | yes | 12.6 is exact enough for I23 (n4 aside). |
| m5 (restoration) | yes | I7e, I8, I9 and I14 are identical to draft 5, except I14's bound (section c). |
| m7 (send-then-record) | yes, as a named gap | 13.1. Its window is understated (m1). |

## b. New defects in draft 2

**The kept result (12.4, W-e, W-e2):**

- **Ordering.**
  - The success path is send, keep R in memory, outcome write, commit X+1 with no R. That is
    correct.
  - The failure path is send, keep R, failed write, then a checkpoint "before the re-read". The
    checkpoint's moment is not fixed, so 13.1's window is understated (m1).
- **If the checkpoint commit fails.**
  - With membership kept, R stays in memory and the re-read writes it. That is correct, but unsaid.
  - On a rebalance, the revoke commit carries R.
  - On a lost partition, R is gone and the next owner sends again. That is 13.1's "expelled",
    widened by m1.
- **S2r with R.** R is written and not counted as S2r. That is correct.
- **A reset.** R is dropped, and the ERROR names N. That is correct, but the above-end reset
  re-sends (m5).
- **A change of owner.** R resumes only when the metadata's offset equals the committed offset. A
  failed `committed()` is never read as "none". That is correct.
- **The grammar.**
  - It is unambiguous and parseable:
    - BCP 47 tags hold only `[A-Za-z0-9-]`;
    - base64url holds only `[A-Za-z0-9_-]`;
    - neither holds `:`, a space or `~`;
    - the fields are in a fixed order.
  - The 1,024-byte bound sits well below `offset.metadata.max.bytes` (4,096).
  - The truncation targets the wrong limit (m9), and two details are open (n1).

**The F1 to F4 table with chain walking:**

- **A `VaultException` wrapping `23505`.** The vault path only reads, so it cannot occur. If it did,
  F3 would match through the inner `SQLException` and give `rejected_by_the_database`. That is
  acceptable.
- **A permanent wrapper whose chain holds an F1 cause** would become F1 and loop (n2a).
- **A DLQ send that fails while R is kept.** The attempt has two exceptions, and the rule does not
  say which one decides (n2b). Whichever decides, no send repeats. The real defect is the DLQ split
  itself (M2).
- **Is F1 still safe?** Yes. With R kept, an F1 retry only writes R. 25006 and 53100 in F1 are now
  harmless. Nothing left in F1 is the record's own, apart from one exotic case (a statement that
  kills its backend), which I accept.
- **Unbounded resends.** None.
- **Unbounded loops.** None, apart from F1 by design and a vault hang (m3).
- **Losses.** M1 and M2.

**The F4 clock:**

- **Can it fail to reach the bound?** Each change of owner loses at most the last backoff interval
  (30 s or less, and the backoff restarts at 100 ms). The bound is therefore reached unless the group
  rebalances more often than about every 100 ms.
- **Can it reach the bound wrongly?**
  - Only through a shared fault classed F4: KMS throttling (m4), DLQ faults (M2), or `57014` under
    lock contention, which the design accepts as bounded and replayable.
  - It can also be reached at an S4 answer with a misleading reason (n3).

**Rendering before issuing.** Feasible, but the placeholder must have the real link's length, the
`no_link` path must be trial-rendered, and the placeholder text must never be sent (m2).

**`Error` handling.**

- The "closes" step skips records (M1).
- The DOWN status can take down the decision API (m8a).
- The webhook delivery executor still dies silently on an `Error` (m8b).

**Timeouts.**

- The main pool is consistent with `application.yml`:
  - `statement_timeout` 5 s is below `socketTimeout` 10 s;
  - Hikari's `connection-timeout` of 2 s gives `SQLTransientConnectionException`, which is F1;
  - `connectTimeout` is 2 s;
  - `SET LOCAL` works, because `tenant()` opens a transaction.
- The vault is not covered (m3a), and neither is the batch size (m3b).
- The DLQ settings are inconsistent with the code's future wait (M2, n6).

**12.5 against the Java client.**

- `NoOffsetForPartitionException` and `OffsetOutOfRangeException` both come from `poll`. With
  `none`, `position()` can also throw them, which matters in `onPartitionsRevoked`; it should only
  call `position()` for a partition it will commit.
- A paused partition throws only after it is resumed. Records fetched for other partitions are
  returned first, so pause, seek and rebalance interact soundly.
- The above-end target is the defect (m5).
- `beginningOffsets` and `endOffsets` can time out. The text should say the handling is retried on
  the next poll; I do not raise this as a finding.

## c. Section 8 under the owner's rule

- **Rows checked against their named tests, which all exist.**
  - **Hold as worded:** I1 (S3 and S4 only, see m7d), I2, I3b, I4, I4b, I5, I6, I7, I7b, I7c, I8,
    I11 (`rejected_by_the_database`, asserted with `IllegalArgumentException`), I12, I13, I15, I16
    and (wire).
  - **Marked in part, correctly:** I3a, I7e, I7f, I9 and I14, with the gap named and the test this
    round adds. I10 is honestly "to be proved". I14b states what it does not produce.
  - No row claims more than its test.
- **What is missing (m7).**
  - Rows for `outcome_unrecorded`'s refusal and for W-e2.
  - The S3m half of I1.
  - The no-skip half of I26.
  - I18's wording against 12.6.
- **I17 to I27 against section 12.** Each is testable and maps as follows:

  | Invariant | Section | Note |
  |---|---|---|
  | I17 | 12.3 | including the empty-budget case and the failed DLQ at the bound |
  | I18 | 12.5 | counted, with the 12.6 conflict of m7e |
  | I19 | 12.1 | |
  | I20 | 12.4 | in F1 and F4, with a change of owner |
  | I21 | 5.1 | |
  | I22 | 12.5 | its above-end clause inherits m5 |
  | I23 | 12.6 | |
  | I24 | 12.2 | review plus a table, labelled as such |
  | I25 | 12.4 | its test is consistent: `officialPhone` null is an NPE, so F4, then a replay |
  | I26 | 12.2, 12.3 | too narrow (m7c) |
  | I27 | 12.2 | encodes M2's over-broad trigger, so it must change with M2 |

- **I14's bound: justified.** Draft 5's own W-e already said "one re-read interval, 500 ms plus at
  most the thread's current batch" (`1288143`, W-e crash bullet). "At most 500 ms" contradicted it.
  Citing W-e is a correction, not a narrowing, and the status block says so.

## d. Other

- **Contradictions between sections:**
  - D-d against 12.4 (m6);
  - I18 against 12.6 (m7e);
  - 13.1 against W-e, 12.4 and the code's end-of-batch commit (m1);
  - 12.2's "closes" against W-a (M1).
- **Undefined or unimplementable terms:**
  - "the KMS client reports as unreachable" (m4);
  - the placeholder (m2);
  - "as soon as" for the checkpoint (m1).
- **Behaviour changes the status block does not list:**
  - the `KmsClient` contract change (m4);
  - records committed past as dropped (12.2, `fs_notify_dropped_total`);
  - the webhook consumer now dead-letters payloads it used to retry for ever;
  - D-d.

  Each needs one line.
- **ADR 0056 and `M6_updates.md`** match the contract, apart from PB-78's premature "resolved"
  (n8).

## Needs execution (after the code round)

1. M1: `EnvelopeConsumerTest`, a handler that throws `NoClassDefFoundError` mid-batch on a partition
   with `spent` > 0. Assert that the second member re-reads that offset and every one behind it.
2. M2: a DLQ topic without WRITE ACL, or a producer whose future times out. Assert F1 (retried, no
   drop), then delivery once the ACL is restored.
3. m1 and I20: kill the member between the provider call and the outcome write, and between the
   outcome write's failure and the batch commit. Assert which of them sends twice, against the
   window 13.1 states.
4. m2: a `verification_base` longer than the placeholder. Assert that no verification row is left
   when the real render does not fit.
