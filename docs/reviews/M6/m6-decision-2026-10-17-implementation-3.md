# M6 decision: implementation review 3 (amendment 1 code round, final contract edits, `e7a5be5`)

Date: 2026-10-17. Reviewer: an independent Principal reviewer who wrote none of the material reviewed.

## Scope

1. The code round implementing amendment 1 of `docs/architecture/decision-fact-ordering.md`:
   `git diff e8341d7 fb42eb8` on `m6/amendment-1` (commits `72f24a3`, `1b9f322`, `207f9d5`,
   `acc405e`, `cf3e762`, `3496a26`, `fb42eb8`). Main code in `backend/notify/.../notify/`
   (`kafka/EnvelopeConsumer`, `FailureClass`, `Checkpoint`, `ProviderResult`, `TransactionHeader`,
   `WaitBudget`, `DeadLetterReplay`; `sms/CustomerSmsSender`, `AfricasTalkingGateway`,
   `ProviderReplyUnknownException`; `webhook/WebhookDispatcher`; `vault/KmsClient`,
   `KmsUnavailableException`, `VaultException`; `verification/VerificationService`) and
   `backend/ingest/.../config/NotificationWiring.java`. Tests: `EnvelopeConsumerTest`,
   `SmsOrderingTest`, `FailureClassTest`, `CheckpointTest`, `WaitBudgetTest`.
2. The final contract edits: `42e55ef` (the delta check's MINORs and NITs) and section 8's proof
   column (`3496a26`). The contract at `fb42eb8` is the specification.
3. `e7a5be5` ("run the vault migration after up, not inside it"), checked against
   `docker-compose.yml`, `Makefile`, `infrastructure/docker/pii-vault/`, `.github/workflows/stack.yml`,
   `infrastructure/docker/scripts/diagnose-stack.sh` at HEAD, and against `security.yml` and
   `docs/parallel/M9_updates.md` section 8.4 as they stand on `origin/m9/infra` (neither file
   exists at `fb42eb8` or on `main`; `security.yml` lives only on `m9/infra`, which does not contain
   `e7a5be5`).

## Method

Static only. Nothing was built or run. The CI evidence is taken as given: run 39010096021 on
`fb42eb8`, all 7 jobs executed and green (notify 169 tests, ingest 101). The whole contract was
read, then every changed main and test file in full. For every section 8 row the round touched,
the named test was opened and checked against the row's wording, asking whether the test would fail
if the property were broken (the owner's standing rule). Kafka client and Compose behaviour are
stated from the public APIs and documentation; anything that only a run could settle is in (d).

## Verdict: CHANGES_REQUIRED. 0 BLOCKER, 3 MAJOR, 7 MINOR, 7 NIT

The code implements amendment 1 closely: the classification and its cause-chain rules, the F4
bound with its clock, exclusions and clearing rule, the kept provider result with its commit,
`outcome_unrecorded` and `fs-dlq-result`, the `v3` grammar, leader epochs, the revoke and stop
commits, the `Error` rule, and the reset handling are all where the contract puts them, and the
behaviour reviewed sound before (wait budget, grace re-read, W-d WARN, replay) is intact. Three
things stop approval: section 8 still claims more than its tests prove in several rows, two of
them guarding defects found by earlier reviews (M1); an exception inside the reset handler kills the
poll thread silently while the stop gauge keeps reading 1 (M2); and the replay tool can send a
second SMS for an intent whose outcome was not recorded, a path that neither D-d nor section 13
admits (M3). `e7a5be5` is sound: `make up` migrates the vault idempotently and fails loudly. Its
only defect is diagnostic (m6).

## Findings

| # | Sev | Where | What | Failure scenario | Minimal fix |
|---|---|---|---|---|---|
| M1 | MAJOR | contract section 8: I17 (l. 522), I19 (l. 524), I20 (l. 525), I22 (l. 527), I28 (l. 533), I7e (l. 508); known-partial I26 (l. 531) and I27 (l. 532) wording; preamble l. 490 | Section 8 still says a named test proves properties that no test would catch if they broke. Details in (b). The worst gaps: (1) I20's "any unchecked exception from the adapter" becomes FAILED and is never re-sent: no test has a gateway throw a `RuntimeException`. (2) I19's "an envelope whose `payload` is absent or not an object": the cited poison test covers unreadable JSON and a missing `institution_id` only. (3) I17's "`outcome_unrecorded` at the bound": the cited record throws `23514` (F3) and is dead-lettered at once, never at the F4 bound. (4) I26's batch budget: `batchesPastTheirTimeBudgetPollAgainWithoutSkipping` passes with the budget code deleted. | (1) is implementation-2 M2: delete `catch (RuntimeException unexpected)` at `CustomerSmsSender.java:354-358` and an adapter that throws after the provider accepted (Jackson, a proxy) makes an F4 failure with no kept result. The record is re-read and the SMS re-sent on every attempt up to the bound, about 25 times. All 169 tests stay green. (2) is implementation-2 M1's trigger: delete the check at `EnvelopeConsumer.java:916-919` and nothing fails. | Add the cheap tests: a gateway that throws `IllegalStateException` (FAILED kept with `mayBeDelivered`, one call, re-read writes it); an envelope with `payload` absent and `payload: 1`; a kept result whose outcome write fails F4 until the bound (`outcome_unrecorded`, `fs-dlq-result`); F1 interleaved with F4 (count kept; F1 time not in `f4_ms`); a DLQ copy that already carries `fs-kept-result` (replaced, one header). Or move each gap into the row's "in part" text. Reword I26 and I27 as in (b). |
| M2 | MAJOR | `EnvelopeConsumer.java:503-505` (`outOfRange` called inside a `catch`), `:714-715` (`beginningOffsets`, `endOffsets`, no handler), `:523` (only `Error` is caught around the loop) | A `RuntimeException` thrown inside the `catch (OffsetOutOfRangeException)` body is not caught by the sibling `catch (RuntimeException)`. It leaves the `while`, and the outer `try` catches only `Error`. The poll thread dies uncaught: no seek-back, no ERROR log through SLF4J (the default handler prints to stderr), `fs_notify_consumer_running` stays **1**, `fs_notify_consumer_errors_total` is not counted, and the consumer is not closed. | Retention deletes a head during a long PostgreSQL outage, which is 12.5's own scenario. The fetch reports `OffsetOutOfRangeException`, and `beginningOffsets` times out after `default.api.timeout.ms` (60 s) because the broker is slow, or throws `WakeupException` or an authorisation error. The thread dies. After `max.poll.interval.ms` the member leaves the group and the partition moves. If the broker trouble lasts, each next owner hits the same reset and dies in turn. There is no health indicator by design, so nothing restarts them, and the gauge M9 alerts on says every member is running while the topic ages out under retention. | Wrap the body of both reset `catch` blocks (`outOfRange`, `seekToBeginning`) in `try { ... } catch (RuntimeException e) { LOG.warn(...); pollBackoff(); }`: the next poll raises the reset again. Also add a final `catch (RuntimeException)` around the loop that logs at ERROR and sets the gauge to 0, so that no unchecked exception can stop the thread silently. A unit test can call `outOfRange` with a consumer whose `beginningOffsets` throws. |
| M3 | MAJOR | `DeadLetterReplay.java:128-138` (newest copy chosen only among records of the requested reasons), `:195` ("run it again: it is idempotent"); contract D-c l. 257-259, D-d l. 461-466, 13.1 | The "newest copy per `event_id`" rule is applied after filtering by reason. An event whose newest DLQ copy is `outcome_unrecorded` can therefore still be replayed from an **older** copy of another reason, which carries no result. | An intent is dead-lettered `parent_not_recorded` (copy A). The operator replays: the parent now exists, the SMS is sent, and the outcome write fails F3 (or F4 to the bound), so copy B is `outcome_unrecorded` with `fs-dlq-result`. Days later the operator runs the default replay again for other orphans, as D-c2 invites and the tool calls idempotent. Copy A matches, is the only `parent_not_recorded` copy of that event, and is republished without a result. The consumer finds B visible and no outcome (S2), and **sends a second SMS** (without a link, since `issue` runs once per block). The same happens with `--reason handler_failure`. D-d lists only 13.1's window and pre-amendment `rejected_by_the_database` copies as remaining re-send paths. | Choose the newest copy per `event_id` over **every** record in the window, then replay it only if its reason was requested; count the others as superseded. Alternatively, when any copy of the event carries `fs-dlq-result`, put that result on the replayed copy as `fs-kept-result`. Add the case to D-c/D-d and to I28's test (two copies, older `parent_not_recorded`, newer `outcome_unrecorded`: nothing sent). |
| m1 | MINOR | `EnvelopeConsumer.java:1071-1075` (kept, "may have been delivered"), `:1048-1052` (`fs-kept-result` honoured), `:735-744` (reset below the log start), `:1240-1252` (drop) | N is never logged. 12.4 requires N on the ERROR for a possibly-delivered result and on the INFO for a result taken from `fs-kept-result`. 12.5 requires "its N and R" on the reset ERROR, and 12.2 "N with the kept result" on the drop ERROR. The consumer logs partition, offset and R only. | A dropped record, or a head skipped by a reset, whose kept result is never written: the operator has R but not the notification it belongs to. Recovering it from the value's SHA-256, or from a topic offset that retention is about to delete, is impractical. | Read `notification_id` from the validated payload (when present) into `PartitionState` and add it to these four log lines. |
| m2 | MINOR | `NotificationWiring.java:268-301` | `fs_notify_webhook_delivery_running` is registered with Micrometer's default **weak** reference to a local `AtomicInteger`. The only strong reference is the lambda of the periodic task, and the returned `ScheduledFuture` is discarded. After the `Error`, the task is not re-queued and becomes unreachable. At the next GC the gauge reads `NaN`, not 0. | An `Error` stops webhook delivery. The gauge reads 0 until the next collection, then `NaN`, so an alert on `== 0` stops matching. Only the counter remains as a signal. **Needs execution** to observe, but this is Micrometer's documented behaviour. | `.strongReference(true)` on the builder, or keep the `AtomicInteger` in `Channels`. |
| m3 | MINOR | `DeadLetterReplay.java:172` (an existing `fs-kept-result` is dropped only when the copy has `fs-dlq-result`) | The replay tool forwards an `fs-kept-result` that the consumer ignored. A record published without `fs-replayed` but with `fs-kept-result` is ignored and counted (12.4, [A3-m6]). If it is then dead-lettered for any reason and replayed, the copy gains `fs-replayed` and keeps the stray header, which the consumer now honours. | A manual or foreign publish carries a stale `fs-kept-result`. The intent waits, is dead-lettered `parent_not_recorded`, and is replayed. The consumer writes the stray result as the outcome and the customer is never told. The guarantee that the header "cannot suppress a first send" is lost after one replay. | Drop `fs-kept-result` from every replayed copy that has no `fs-dlq-result`. The consumer's own dead letters always carry `fs-dlq-result` while a result is kept. State it in D-c. |
| m4 | MINOR | `EnvelopeConsumer.java:729-753` | Two deviations from 12.5 in the non-divergent branch. (a) A partition a `LogTruncationException` names without a divergent offset is compared with the log start and, if its fetch offset is below it, counted as skipped and reset to the log start. 12.5 (as `42e55ef` amended it) says to treat it as above the log end. (b) Any fetch offset at or above the log start seeks to the log **end**, even when the end has meanwhile moved past the fetch offset. The offsets between are skipped with no count. | (b) An out-of-range fetch above a lagging leader's end, after a leader change, is followed by `endOffsets` once that leader has caught up. Records in [fetch, end) are skipped silently. **Needs execution** to say how reachable this is with epoch validation on. | (a) Handle named-without-divergent partitions of a `LogTruncationException` as above the log end. (b) Seek to `min(fetch, end)` when `fetch >= logStart`; log ERROR only when `fetch > end`. |
| m5 | MINOR | `EnvelopeConsumer.java:1410-1426` (pre-existing `close()`, more exposed now) | `close()` joins the poll thread for 10 s and then calls `consumer.close()` from the caller's thread. 12.3 sizes one attempt at about 110 s. If the join times out mid-attempt, `onPartitionsRevoked` runs on the closing thread. It races on the `partitions` `HashMap` and commits `position(p)` for partitions with metadata, which mid-batch is past the unhandled records. It can also throw `ConcurrentModificationException`, which leaves later consumers in `Channels` unclosed. | A deploy during a database slowdown. A partition whose budget is non-zero, with records fetched but unhandled, has its position committed past them: they are skipped without being handled. A kept result committed by the revoke before `keep()` runs widens 13.1. | Join for at least the batch budget plus one attempt (about 180 s, 12.3), or let the poll thread close the consumer itself on its way out and have `close()` only signal and join. |
| m6 | MINOR | `Makefile:36`; `diagnose-stack.sh:20-33`; `stack.yml:126-128` | With `run --rm`, a failed migration's container is removed, and `diagnose-stack.sh` lists only `--profile core ps -a` services (and `ps` omits one-off `run` containers). A failed `pii-vault-migrate` therefore yields "services with problems: 0" and no annotation. Its output exists only in the `make up` step log, which needs authentication: the very thing `diagnose-stack` exists to avoid. | CI's `stack` job fails at the migration (a wrong password, a bad V4). The annotations show a healthy stack, and the cause is only in the authenticated log. | In `make up`, capture the run's output (`... run --rm pii-vault-migrate 2>&1 | tee build/stack-diagnostics/vault-migrate.log`, with `pipefail`), and have the workflow annotate its tail on failure. Or run without `--rm` and remove the container after `diagnose-stack`. |
| m7 | MINOR | contract I20 (l. 525) "in part" | I20 admits only the commit-before-write order as by review. The wiring lambda that passes `envelope.kept()` and `envelope.keeper()` to the sender (`NotificationWiring.java:340-347`) is untested, as I3a admits for the header. The consumer tests use stub handlers and `SmsOrderingTest` calls the sender directly. | A wiring regression that passes `Optional.empty()` and a no-op keeper re-sends on every re-read after a failed outcome write, and every test stays green. | Add the wiring lambda to I20's "in part", or one test that drives a real intent through `EnvelopeConsumer` into the real sender with a failing outcome write. |
| n1 | NIT | `EnvelopeConsumer.java:845-853` | The 1-minute failing WARN (12.5) omits "the last exception". Each failed attempt logs its own WARN with the exception, so the information is nearby. | n/a | Add the last exception's class and message. |
| n2 | NIT | `EnvelopeConsumer.java:541-551`, `:596-600` | The stop commit, and the seek when the batch budget is spent, use the partition's previous `lastEpoch` for a record fetched but not yet attempted. 12.5 asks for "the epoch of the record at the committed offset when the consumer has read it". | n/a | Set `lastEpoch` from the fetched record before the budget check, and in `stop()` from `records.records(p)` at the first unhandled offset. |
| n3 | NIT | `EnvelopeConsumer.java:1104` vs `:1025` | "Carrying `fs-replayed`" is decided two ways: through the header map, which drops null values (S4/D-d), and through `lastHeader != null` (kept result). A null-valued `fs-replayed` honours a kept result but still waits. | n/a | Use one predicate. |
| n4 | NIT | `FailureClass.java:42-53`, `:73-79` | The cause-chain walks have no cycle guard. `initCause` can build A→B→A, and the walk then never ends on the poll thread. | n/a | Stop after a visited set or a depth of, say, 32. |
| n5 | NIT | `docs/parallel/M9_updates.md` 8.4 (on `origin/m9/infra`) | The "latent trap" names the wrong mechanism. `docker compose --profile full up -d --wait fraudshield-api` targets one service, so `up` starts only `fraudshield-api` and its `depends_on`: `pii-vault-migrate` is **not** started unless the API depends on it. The real traps are an API that starts against an unmigrated vault (no dependency), and the race if the dependency uses a weaker condition than `service_completed_successfully`. The acceptance item 6 that follows is right. The ZAP job is in any case gated off until a `fraudshield-api` service exists. | n/a | Reword the bullet. |
| n6 | NIT | `docker-compose.yml:65-70` (pii-vault healthcheck) | `pg_isready` without `-h` uses the Unix socket, which the image's temporary init-time server also answers. On a fresh volume `pii-vault` can report healthy before its init scripts finish. Under `make up` this is masked by the rest of the stack's start-up time, and a migration that ran too early would fail loudly (connection refused), not silently. **Needs execution** to see whether it ever triggers. | n/a | `pg_isready -h 127.0.0.1 ...`, or `FLYWAY_CONNECT_RETRIES=10` on the migration. |
| n7 | NIT | `CheckpointTest`; contract I29 (l. 534) | "read as no metadata **with a WARN**": the WARN is in `EnvelopeConsumer.resumeFrom` and no test asserts it. | n/a | Say "the WARN by review", or assert it. |

## (a) Contract conformance

**12.1 validation.** The envelope (JSON, canonical `institution_id`, payload object), the SMS intent
(all fields 12.1 lists, before `classify`) and the webhook payload (integer sequence from 1 to
2³¹−1, canonical ids from sequence 2) are validated before any side effect. A replayed
`fs-kept-result` is validated (one value, identical repeats collapsed, `none` and unparsable values
refused) before the handler runs; on a record without `fs-replayed` it is ignored, warned and counted
once per head. Conformant.

**12.2 classification.** `FailureClass.of` makes two passes: an exception carrying its own class
anywhere in the chain decides, outermost first, then the first SQL match. That is a faithful reading
of rule 2, given that the F4 row is the default and not a matching row. The SQLSTATE table matches
12.2 exactly, including `08P01`, null states and the transient connection types. Rule 1 (the
dead-letter send's exception decides) is implemented in `deadLetter`: anything but
`RecordTooLargeException` is F1, too-large is retried once without `fs-dlq-error` and is then F4 with
a drop at the bound. The `Error` path on the poll thread does what 12.2 lists, in order: stopping
flag, seek back, commit only if every seek succeeded, log, count, gauge 0, close; and the revoke
listener commits nothing while stopping. The executor's `Error` rule is in the wiring (gauge: m2).
Exception: M2, an unchecked exception that escapes classification altogether.

**12.3 F4 bound.** `f4_count` and `f4_ms` are per head. `f4_ms` excludes each interval from an F1
failure to the next attempt (including at checkpoint time, while an outage is open) and starts at
the first F4 failure. The state is cleared only by `newHead` (handled, dead-lettered, dropped,
reset); S4 and F1 neither clear nor add to it. The bound is checked only after an F4 failure
(`failed()` default branch, and the too-large path). A failed DLQ send at the bound keeps the
increment. A checkpoint follows every failed attempt (the RETRY commit at the end of the batch) and
resumes on assignment with the unowned time excluded. Timeouts: `statement_timeout` 5 s in every
consumer transaction (sender, verification issue, webhook tenant), vault socket 10 s, connect and
login 5 s, statement 5 s as a connection option, producer 15/0/30/10 s, a 35 s wait on the future,
10 s on every `commitSync`, and a 60 s batch budget. Conformant. The batch budget's test proves
nothing (M1).

**12.4 kept result.** Render before issue, with a placeholder of `MAX_LINK_LENGTH`. The real link's
length is bounded by `InstitutionMessaging.Settings`, so the second render cannot fail on length.
Any exception from `gateway.send` becomes FAILED: `ProviderReplyUnknownException` marks
"may be delivered", and so does any `RuntimeException`. `keeper.keep(R)` commits `(X, epoch(X), m)`
for X's partition alone before `record`. A failed commit is logged, and R rides on every later
commit of the partition (RETRY checkpoint, revoke) until the head changes. A re-read holding R
classifies first: S0, then S1, then R is written; it never reaches the vault, `issue` or the
provider. Any dead letter while R is held becomes `outcome_unrecorded` with `fs-dlq-result`, once per
dead letter. The `mayBeDelivered` count and ERROR happen once, when R is kept. The reference is
normalised (U+0000, 128 code points, `~`, empty as null) in R and the row alike. Conformant, except
that N is not logged (m1).

**W-e/W-e2/W-e3.** `Checkpoint.text()` writes metadata whenever `spent_ms > 0` or head state
exists, in the exact `v3` field order, with no leading zeros. `v2` is read with no head state, and
anything else is none plus a WARN. Every commit (end of batch, RETRY, keep, revoke, stop) carries an
epoch per 12.5: the record's own for keep and RETRY, the last read for end-of-batch and revoke, and
`committed()`'s after assignment (n2 for two corner cases). The revoke commit uses `position(p)`.
`onPartitionsLost` commits nothing. Conformant.

**12.5 resets.** `auto.offset.reset=none`. `NoOffsetForPartitionException` seeks to the log start.
Below the log start: ERROR, counted by the difference, seek. Above the end: ERROR, uncounted, seek
to the end. A divergent offset is honoured. Head state is dropped and the partition resumed in every
case. Deviations: m4. Robustness: M2.

**12.6 metrics.** All 13 meters exist with the defined tags and are exported through the
application's registry (`fb42eb8` disambiguates the bean). The `retries_total` semantics (not counted
on the attempt that dead-letters or drops) and the once-per-head counts match. The head-failing gauge
is computed on the poll thread and published through an `AtomicLong`, so it is safe to read off-thread.
Defect: m2.

**Replay tool.** It refuses `rejected_by_the_database` without the flag, replays `outcome_unrecorded`
on request with `fs-dlq-result` turned into `fs-kept-result`, and replaces an existing one. Defects:
M3, m3.

**Cross-institution leakage.** None found. The kept result is bound to (partition, head offset),
and the leader epoch on each commit is what protects it against a truncated log reusing an offset.
The institution always comes from the envelope.

**Regressions to behaviour reviewed sound before.** The wait budget, grace re-read (`!first` guard,
`headAnsweredS4` reset only on a new head), W-d WARN and the replay's newest-copy and end-offset
logic are unchanged in behaviour. None found.

## (b) Section 8, row by row (rows the round touched)

| Row | Ruling |
|---|---|
| I3a | Accurate. The sender test proves S3 with nothing done. `everyDeadLetterReasonKeepsTheRecordsHeaders` (record 3) fails if `NotTheKeptDecisionException` were waited on or retried into `handler_failure`. The "in part" (the wiring lambda by review) is accurate and complete. |
| I7e | **Over-claims slightly.** The test proves the move and that later re-reads are the second member's. It does not prove "re-reads at once" (a 30 s await, no timing). "No `IllegalStateException` escapes rests on the same test completing" is not a proof: an ISE from `resume` would be caught by the poll loop's generic handler and the test would still complete; with the `assignment()` guard and per-revoke state removal it cannot occur anyway. Say "by review". (Part of M1.) |
| I9 | Accurate. The held-open-writer test proves S4 before the commit and S2 after. The structural test (one connection, two statements of any kind) is what fails on a split classification. |
| I10 | Accurate. All eight reasons with original headers, the result on `outcome_unrecorded`, the per-reason counter; the replay half in the replay test. |
| I14 | Accurate, including the 1.5 s loss bound and the lost path by review. |
| I17 | **Over-claims (M1).** Proved: the bound in both count and time, the next record handled, the retry count, state surviving a revocation with nothing spent, S4 not clearing. Not proved and not admitted: (i) `outcome_unrecorded` **at the F4 bound**, since the cited record (`23514`) is F3 and dead-lettered at once; (ii) "F1 failures ... nor reset it", since no test interleaves F1 with F4; (iii) F1 time excluded from `f4_ms` (the outage test's bound has zero time). The admitted part (DLQ failure at the bound, too-large via I27) is accurate. |
| I18 | Accurate. Six outages counted, no dead letter at a bound of two; the 1-minute WARN by review is admitted. |
| I19 | **Over-claims (M1).** The SMS intent's ten field groups are proved, with nothing done. The envelope's "payload absent or not an object" is cited to the poison test, which covers unreadable JSON and a missing `institution_id` only; removing the payload check fails nothing. The webhook payload (by review) and the replayed `fs-kept-result` (by review) are admitted. |
| I20 | **Over-claims (M1, m7).** Proved: the real adapter's unreadable 2xx gives a FAILED, possibly delivered result, one call; the sender writes a kept result without sending; the consumer keeps R across owners after F1 outcome failures, one send; the dead letter's reason and result, and the replay recording it (I28). Not proved and not admitted: "any unchecked exception from the adapter" (no test throws one), "whatever class the failed outcome write was" (only F1 is re-read in a test; F4 is not), and the wiring lambda (m7). The admitted part (commit before write) is accurate. |
| I21 | Accurate. Through the real consumer (absent, short form, valid; counted once over three reads); S3m and the B-visible send at the sender; the strict parse. |
| I22 | **Over-claims (M1).** Proved: below the log start, five counted, the rest handled; a new group at the log start (every test). Not proved and not admitted: "the old head's retry state and kept result are dropped", and "a failed lookup never counts as no offset". The admitted part (log end and divergence by review) is accurate. |
| I23 | Accurate. Each counter claimed is asserted exactly (dead letters per reason, retries by class, dropped, malformed headers, skipped offsets, consumer errors, ignored kept results) along with the running gauge, and the five meters listed as by review are the ones with no assertion. |
| I24 | Accurate. `FailureClassTest` covers every listed SQLSTATE, the type rules, own-class precedence, the chain and the DLQ split, and "the class decides the action" is carried by the consumer tests. |
| I25 | Accurate. Issuing before rendering would leave a verification row, and the test checks for it. |
| I26 (known partial) | **The "in part" wording is incomplete.** It omits: the batch budget (the cited test passes with the budget removed); the seek-failure branch (commit nothing), which no test produces; and the stopping flag, which the test cannot distinguish because the revoke listener would commit nothing there anyway (empty metadata); also, the multi-partition seek-back is untested (one-partition topic). What it does admit is stated accurately. |
| I27 (known partial) | **The "in part" wording is incomplete.** The test proves the re-send without `fs-dlq-error`, the drop at the bound, the count and the next record handled. It does not prove "logged at ERROR in full (with N and R)", and the code does not log N (m1). The rest is accurate. |
| I28 | **Over-claims (M1).** Proved: the refusal, the replay of `outcome_unrecorded` without the flag, the result handed to the handler and no second send, the ignored header counted. Not proved: "replacing any it already carries", since the copy in the test carries none. M3's case is not covered either. |
| I29 | Accurate, except that the WARN is untested (n7). |

## (c) `e7a5be5`

- **Core stack, fresh volume.** `--profile core up -d --wait` no longer starts the one-shot, which
  is now `full`-only, so the `--wait` race on an exited one-shot cannot occur. `run --rm
  pii-vault-migrate` explicitly targets the service, which Compose v2 allows whatever its profiles
  (an explicitly targeted service's profile is enabled). Its only dependency, `pii-vault`, is in
  `core` and already healthy. `run` returns Flyway's exit status and each Makefile line aborts `make`
  on failure: nothing is ignored. On a fresh volume, `20-vault-roles.sh` creates the roles before the
  server accepts TCP, so the migration authenticates. See n6 for the healthcheck caveat (loud if it
  ever triggers).
- **Existing volume.** Flyway on an up-to-date schema is a no-op (exit 0), and re-running `make up`
  on a running stack is idempotent. A vault volume created before `0747d6c` has no
  `fs_vault_migrator`, so `make up` fails loudly at the migration. The remedy (`docker compose exec
  pii-vault bash /docker-entrypoint-initdb.d/20-vault-roles.sh`) is documented in
  `M6_updates.md:486` but not printed by `make up`. Acceptable.
- **Waiters.** `stack.yml` runs `make up`, then smoke and seed. Nothing else in the repository runs
  `up --wait` with the `full` profile. `down -v` with `--profile full` is fine. `security.yml`
  exists only on `origin/m9/infra`, which lacks `e7a5be5`. Its ZAP job is gated off until a
  `fraudshield-api` service exists, and its targeted `up --wait fraudshield-api` would not start the
  one-shot at all unless the API depends on it (n5). M9 8.4's acceptance 6 (a
  `service_completed_successfully` dependent in the same profile) is the right rule.
- **Secrets.** No password is echoed: `make` prints the commands, which carry no secrets, and
  Flyway prints the URL and user only. The migrator's password reaches only the one-shot. No
  exposure found.
- **Diagnostics.** m6: a failed migration is invisible to `diagnose-stack.sh`.

## (d) Needs execution

1. m2: the webhook gauge reading `NaN` after a GC following an executor `Error` (Micrometer's weak
   reference semantics make this expected).
2. m4(b): whether an above-end out-of-range with the end since moved past the fetch offset is
   reachable with leader-epoch validation on.
3. M2: confirming the thread death by having `beginningOffsets` throw inside `outOfRange` (the
   language semantics are certain; this is for a regression test).
4. n6: whether `pii-vault` reports healthy during initdb under CI timing.
5. Batch fairness: whether, with slow records, the fixed partition order in `handleBatch` starves a
   later partition across consecutive budget-limited batches. Not claimed by the contract; noted
   for I7d's spirit.
6. I7e's "at once": the elapsed time from the first member's close to the second member's first
   re-read.

## Re-check of 7047567 and a89a0f4

Same constraints: static, read-only, CI taken as given (run 39014509277 on `7047567`: all 7 jobs
executed and green; notify 183 tests, ingest 101). Read: `git diff fb42eb8 7047567` in full (main,
tests, `Makefile`, `diagnose-stack.sh`, wiring), section 8 and the status block at `a89a0f4`, and
the new lab notebook entry, checked against the reviews it cites.

### Verdict: APPROVED_WITH_MINORS. 0 BLOCKER, 0 MAJOR, 3 MINOR, 5 NIT (new), n5 and n6 carried

All three MAJORs are resolved. Under the owner's deletion standard, section 8 now states what it
proves and what it does not, row by row, and the 13/24/1 count is right. Three MINORs remain: one
sub-clause of I17 still claims a consumer behaviour that only a class test covers (R-m1); I26's
known-partial wording still omits three mechanisms (R-m2); and D-c still describes the replay's old
selection rule (R-m3). None affects runtime correctness.

### Closure table

| Finding | Status | Evidence at `7047567` / `a89a0f4` |
|---|---|---|
| M1 section 8 over-claims | **Resolved**, apart from R-m1 and R-m2 | New tests for each gap I named. I checked each against deletion: `gatewaysThatThrowUncheckedAreFailedOnceAndNeverResent` fails if the sender's `catch (RuntimeException)` is removed; `envelopesWithoutAnObjectPayloadAreMalformed` fails if the payload check is removed (null or array payloads become F4 and block record 5 past the 30 s await); `recordsThatKeepFailingAfterTheirSendLeaveAsOutcomeUnrecordedWithTheResult` reaches the real F4 bound; `outagesBetweenOwnFailuresNeitherCountTowardsTheBoundNorClearIt` (F4,F1,F1,F4,F1,F4 is dead-lettered at 6: at 3 if F1 counted, at 8 if F1 cleared); `theBatchBudgetKeepsEachMemberWithinItsPollInterval` fails without the budget, provided one poll returns the whole batch (see (d)); `replayTakesTheNewestCopyAcrossReasons…`; `droppedRecordsAreLoggedInFull`; `committedMetadataThatDoesNotParseIsWarnedAndReadAsNone`. Every remaining gap is written as **Not proved:** in deletion terms. |
| M2 poll thread dies on a reset | **Resolved** | `run()` now catches `RuntimeException` around `cycle()` and backs off. The reset handlers, `outOfRange` included, run inside `cycle()`. `WakeupException` is not caught inside `cycle()` (it is rethrown from the batch's catch) and returns from `run()`. An `Error` anywhere reaches `stop(batch, …)`. `batch` is cleared at the start of each cycle and set only after a successful poll, so an `Error` in a reset or a poll stops with nothing to seek, and an `Error` mid-batch seeks that batch. `beginningOffsets`/`endOffsets` now take a 10 s timeout and only assigned partitions. No regression found. |
| M3 replay re-sends from an older copy | **Resolved in code**; contract not updated (R-m3) | The newest copy per `event_id` is chosen over every record in the window, and dropped unless its own reason was requested. The new test fails if the old order (filter first, then pick newest) comes back. |
| m1 N not logged | Resolved | N, read from the validated payload, is on the possibly-delivered ERROR, the `fs-kept-result` INFO, the reset ERRORs, the drop ERROR, the dead-letter ERROR and the failing WARN. A UUID is not PII. |
| m2 webhook gauge weakly held | Resolved | `Channels.gaugeStates` holds the `AtomicInteger` for the bean's lifetime. |
| m3 replay forwards an ignored `fs-kept-result` | Resolved | Every `fs-kept-result` on a copy is dropped, and only the copy's `fs-dlq-result` is carried. Tested (E2). |
| m4 12.5 branches | Resolved | A truncation without a divergent offset goes to the log end, uncounted. A fetch offset in [log start, log end] is resumed, not skipped. Below the start (non-truncation) is counted. See R-n2 for the in-range branch. |
| m5 close() under a running attempt | Resolved; residue in R-n3 | `close()` waits batch budget + 120 s + 10 s. If the thread is still alive it sets `stopping`, and the revoke listener commits nothing off the poll thread while that thread lives. During `stop()` the listener runs on the poll thread, so the `stopping` check applies. After a clean join the listener runs on the closing thread with a happens-before from `join`. |
| m6 failed migration invisible | Resolved | `make up` writes the run's output to `build/stack-diagnostics/vault-migrate.log`, marks `vault-migrate.failed` on failure, prints the log and exits 1. `diagnose-stack.sh` counts and annotates the failure. The marker is cleared on each `up`, and `build/` is ignored by git. The log carries Flyway's URL and user, never the password. R-n5 is cosmetic. |
| m7 I20's wiring gap | Resolved | I20's **Not proved** names the wiring lambda. |
| n1 to n4 | Resolved | The failing WARN carries the last failure. The stop commit takes the epoch of the record at the committed offset (`epochAt`). One predicate decides "replayed". The cause-chain walk has an identity cycle guard. |
| n5 (M9 8.4 wording, on `m9/infra`) | Open | Not touched. NIT, carried. |
| n6 (vault healthcheck on the socket) | Open | Not touched. NIT, carried. |
| n7 (I29 WARN untested) | Resolved | A new test asserts the WARN. |

### (2) Section 8 under the deletion standard

The count: 38 rows (the "(malformed)" pointer row excluded). With no **Not proved** clause: I1, I2,
I3b, I4, I4b, I5, I6, I7b, I7c, I9, I16, (wire), I25, which is 13. With one: I3a, I7, I7d, I7f, I8,
I10–I15, I14b, I17–I24, I26–I29, which is 24. I7e: 1. **The arithmetic is right.** I spot-checked
the 13 full rows by deletion: the S1, S3 and trial-render branches, the link fallback, and the
held-open writer all fail their named tests if removed. I7b and I7c are proved at the level of
`WaitBudget`, the level the row states. (wire) I did not re-read.

For the 24 partial rows, each **Proved** clause I checked survives deletion of the code it names.
Each **Not proved** clause states a real deletion that survives, and several are sharp and correct:
I7f (deleting `seekBack` and the cycle's catch), I11 (the timestamp comparison: copies arrive in
offset order), I14 (the revocation commit, made redundant by the re-read checkpoints), I15 (the
CASE that makes `kept` null), I20 (the adapter's catch of an unreadable 2xx, covered by the
sender's catch), I22 (the double drop). Exceptions:

- **R-m1, I17.** "`HeadRetryTest` (F1 neither counts, clears nor adds time)" is in **Proved**. The
  "nor adds time" part is proved only for the class. Deleting the consumer's
  `state.retry.failedOutage(end)` (`EnvelopeConsumer.java`, in `attempt()`) or
  `state.retry.attemptStarts(start)` fails no test: the consumer tests that mix F1 and F4 use a
  zero time bound. This is exactly what the I7 row (the consumer's `!first`) and the I8 row (the
  consumer's `budget.failed`) mark as **Not proved**.
- **R-m2, I26 (known partial): the wording is still incomplete.** It omits (a) the stopping flag:
  deleting `stopping = true` in `stop()`, or the listener's check of it, fails no test, because in
  `anErrorMidBatch…` the revoke would carry empty metadata and commit nothing anyway; (b) the new
  `close()` rule (the long join, `stopping` when the thread is alive, the listener's off-thread
  check), for which no test exists; (c) the seek-back across several partitions, since the test
  topic has one partition. What it does list is accurate.
- I27 (known partial): **accurate and complete.**
- I7e: "Not proved" is accurate as worded.

### New findings

| # | Sev | Where | What | Minimal fix |
|---|---|---|---|---|
| R-m1 | MINOR | contract I17 (Proved clause) | See (2): "F1 adds no F4 time" is claimed for the consumer, but deleting either consumer call into `HeadRetry` fails no test. | Move it to **Not proved** ("deleting the consumer's `failedOutage`/`attemptStarts` calls would not fail any test"), or add a consumer test with a non-zero time bound and outages between F4 failures. |
| R-m2 | MINOR | contract I26 (known partial wording) | It omits the stopping flag, the new `close()`/listener thread rule and the multi-partition seek-back, none of which a test would catch if deleted. | Add the three to I26's **Not proved**. |
| R-m3 | MINOR | contract D-c l. 439 (and D-d l. 470) | D-c still says the newest copy is chosen "among the matching records". The code now chooses across every reason and replays only if the newest copy's reason was asked, and it drops every `fs-kept-result` a copy carries unless it has `fs-dlq-result`. The specification and the code disagree, and D-d's "what remains" never mentions the older-copy case the fix closes. | Reword D-c to the new rule, and add the `fs-kept-result` drop. |
| R-n1 | NIT | contract section 8 preamble | It still opens "A test named here fails if the invariant is broken", which the 13/24/1 count contradicts. The count is stated in the status block near the top (l. 70–75), not in section 8's preamble as the hand-off says. | Replace the sentence with the standard and the count, or point to the status block. |
| R-n2 | NIT | `EnvelopeConsumer.outOfRange`, in-range branch | `target = fetch` with no log line and no backoff. If the broker keeps answering out-of-range for an offset that `ListOffsets` reports in range, the partition loops with no backoff and no log line, holding its records without a word. **Needs execution** to know whether a broker can do that. | Log at WARN and back off the partition in that branch. |
| R-n3 | NIT | `EnvelopeConsumer.close()` | On the timed-out path, `consumer.close()` from the caller's thread can throw `ConcurrentModificationException` (now caught) while the poll thread is inside a client call. `consumerClosed` is then set true while the client stays open, and nothing closes it when the poll thread exits, so the member lingers until `max.poll.interval.ms`. Separately, a 190 s wait per consumer (two in sequence) exceeds common pod grace periods, which turns the shutdown into 13.1's crash case. | Let the poll thread close the client on its way out if `close()` could not, and note the grace period for M9. |
| R-n4 | NIT | `docs/research/lab_notebook.md`, last entry | Two small inaccuracies. "The third consecutive review where this is the headline finding": implementation-1's only MAJOR was indeed the claim, but implementation-2's headline was M1 (unbounded retries); its false claim was M3. And of the three examples given for the "five other rows", the batch budget belonged to I26's known-partial wording, not to those five rows (I17, I19, I22, I28, I7e). The family examples (the parameter digest, the D-08 ceiling, `PB-75` at about 3 %, the 537-test MLflow fake) match their entries. | "the third consecutive review to find over-claimed tests among its MAJORs"; take the batch budget out of the five-row examples. |
| R-n5 | NIT | `.github/workflows/stack.yml` "Stack diagnostics" step | `make up` now always creates `build/stack-diagnostics`. On a failure before the smoke test, the step no longer prints "no stack diagnostics collected": it emits an empty notice and a `head` error for the missing `resources.txt`. Cosmetic: the migration's own annotation comes from `diagnose-stack.sh`. | Test for `resources.txt` rather than the directory. |

### (3) The lab notebook entry

Accurate in substance. The quotation from MAJOR 1 is verbatim. Implementation-1 (`bd48557`) did find
"every invariant tested" untrue as its only MAJOR, and implementation-2 M3 is described correctly.
The four older cases match their entries. The rule is stated as the owner set it. Two framing
inaccuracies are listed as R-n4.

### (d) Needs execution (re-check)

1. `theBatchBudgetKeepsEachMemberWithinItsPollInterval` fails without the budget only if one poll
   returns at least five of the eight records. That is expected with default fetch sizes, since all
   eight are published before the consumer starts, but CI cannot show the counterfactual.
2. R-n2: whether an in-range out-of-range answer can recur.

## Delta check of 953cafc

Static and read-only, as before. Read `git diff a89a0f4 953cafc` in full: `EnvelopeConsumer.java`,
`stack.yml`, the contract (D-c, D-d, the section 8 preamble, I17, I26), `M6_updates.md` and the lab
notebook.

### Verdict: APPROVED_WITH_MINORS. 0 BLOCKER, 0 MAJOR, 0 MINOR open, 1 new NIT

All three MINORs of the re-check and all five of its NITs are resolved. One narrow shutdown race is
new (D-n1, NIT).

### Closure

| Finding | Status | Evidence |
|---|---|---|
| R-m1 (I17, F1 time) | Resolved | The F1-time part moved to **Not proved** in deletion terms: deleting the consumer's `failedOutage` or `attemptStarts` would not fail any test. `HeadRetryTest` is credited with the clock's arithmetic only. That matches the code. |
| R-m2 (I26 wording) | Resolved | The stopping flag, the off-thread listener rule with `close()`'s longer wait, and the multi-partition seek-back are added to I26's **Not proved**. I26's wording is now accurate and complete as far as I can find. |
| R-m3 (D-c, D-d) | Resolved | D-c describes the newest copy across every reason, replayed only if its own reason was requested, and every `fs-kept-result` the copy carries dropped. That matches `DeadLetterReplay` at `7047567`. D-d's "what remains" is true within the tool's window: an older copy is always older than the newer one, so if a `--from` includes it, the newer copy is included too. |
| R-n1 (section 8 preamble) | Resolved | The preamble states the deletion standard and the 13/24/1 count, which is unchanged because I17 and I26 were already partial. |
| R-n2 (in-range reset branch) | Resolved | The branch now logs at WARN with the offset and the log's bounds. It still has no backoff, but a recurrence is now visible in the log. That was the concern. |
| R-n3 (client left open) | Resolved, with D-n1 | When `close()` gives up, it sets `closeOnExit`. The poll thread's new `finally` closes the client on its own thread. Its revoke listener then sees `currentThread == thread` with `stopping` set, and commits nothing. No double close: `close()` closes only when the thread is not alive (the `else if`), `finally` only when `closeOnExit && !consumerClosed`, and `stop()` sets `consumerClosed` itself, so after an `Error` the `finally` is a no-op. `crash()` leaves `closeOnExit` false, so a later `close()` closes from the caller once the thread is dead, as before. The 380 s shutdown is carried to M9 in `M6_updates.md`. |
| R-n4 (notebook) | Resolved | It now says "the third consecutive review with this finding as a MAJOR, and the headline of this one"; names implementation-2's headline correctly (MAJOR 1, unbounded retries); and puts the batch budget among the owner-accepted partial rows. Accurate against the three reviews. |
| R-n5 (stack.yml) | Resolved, safe under `bash -e` | `[[ -f "$dir/resources.txt" ]] && echo …`: a failing test on the left of `&&` does not trigger errexit, and the list is not the step's last command. The `if [[ job.status … ]] … fi` after it ends with status 0 when its condition is false. A missing `resources.txt` therefore cannot fail the step. |
| n5, n6 | Carried | Recorded as M9 carries in `M6_updates.md`, with the corrected mechanism for n5. They live on the M6 side; `M9_updates.md` 8.4 on `m9/infra` is unchanged. Acceptable for NITs. |

### New finding

| # | Sev | Where | What | Minimal fix |
|---|---|---|---|---|
| D-n1 | NIT | `EnvelopeConsumer.close()` vs the `finally` in `run()` | A check-then-act race. `close()` reads `thread.isAlive()` as true and only then sets `closeOnExit`. If the poll thread's `finally` runs in that gap, it sees `closeOnExit` false and does not close, and `close()` does not close either (it took the alive branch). The client is never closed: its sockets and heartbeat thread linger, and the member leaves the group only at `max.poll.interval.ms`. This needs a 190 s join timeout followed by the thread ending within a few instructions of the check, so it is very unlikely. Separately, `close()` closes the dead-letter producer while the poll thread may still be mid-attempt. A send then fails, is classed as an outage, and is re-read by the next owner, so nothing is lost. | Set `closeOnExit = true` before the final `isAlive()` check. If the thread turns out dead, close here, guarded by an atomic compare-and-set on a closed flag shared with the `finally`. |

## Delta check of 3f666cd

Static and read-only. Read `git diff 953cafc 3f666cd` (`EnvelopeConsumer.java` only, apart from
this file).

### Verdict: APPROVED. 0 BLOCKER, 0 MAJOR, 0 MINOR, 0 new NIT; D-n1 resolved; n5 and n6 remain M9 carries

**D-n1: resolved.** `close()` and the poll thread's `finally` now decide who closes under one
monitor, `closeLock`, which guards `pollThreadDone` and `closeOnExit`. Whichever takes the lock
second closes the client. Every interleaving:

| Interleaving | Who closes | Once? | Thread not using it? |
|---|---|---|---|
| Normal close (the thread ends within the wait) | The `finally` sets `pollThreadDone` and does not close (`closeOnExit` is false). `close()` then sees it done and closes. | yes | yes: the thread is dead, or at the very end of `run()` with no further client calls |
| `close()` times out, takes the lock first | `close()` sets `stopping` and `closeOnExit` and does not close. The `finally` later closes on the poll thread. | yes | yes: its own thread, and the revoke listener inside that close sees `currentThread == thread` with `stopping` set, so it commits nothing |
| `close()` times out, but the `finally` takes the lock first (the old gap) | The `finally` sets done and does not close. `close()` sees done and closes. | yes | yes: the `finally` is the thread's last act |
| `crash()` then `close()` | `crash()` leaves `closeOnExit` false, so the `finally` only marks done. `close()` finds the thread ended and closes from the caller. If `crash()`'s 10 s join itself timed out, `close()` falls into one of the two timeout rows. | yes | yes |
| `Error` stop | `stop()` closes on the poll thread and sets `consumerClosed`. The `finally` sees `consumerClosed` (and normally `closeOnExit` false) and does nothing. `close()` sees done and `consumerClosed` and does nothing. If `close()` timed out during `stop()`, the `finally` still finds `consumerClosed` set. | yes | yes |

The client is never closed twice, and never from a thread using it. It stays unclosed only while
the poll thread never ends, when it is still in use, which is correct.

**Lock held during the 5 s close.** No deadlock. Only `close()` and the `finally` take `closeLock`.
`consumer.close(5 s)` runs `onPartitionsRevoked` on the poll thread, which never takes `closeLock`,
and Java monitors are re-entrant in any case. With `stopping` set, the listener does no client work
beyond removing state. At worst, `close()` blocks on the lock for the bounded close (about 5 s),
after it has already waited about 190 s. Nothing else waits on the lock. Negligible.

Unchanged and already accepted: in the timeout path, `close()` still closes the dead-letter
producer under a running attempt. That send fails as an outage and the record is re-read by the
next owner, so nothing is lost.

## Delta check of acd8ce0 and 0f6268e

Static and read-only. Read: `git diff 3f666cd acd8ce0` (the four tests); `git show 0f6268e`
(section 8, the status block, `docs/backlog/product.md` PB-77 to PB-101, `M6_updates.md`, the lab
notebook); and the master prompt's anchors for the proposal (`docs/prompts/FraudShield_Master_Build_Prompt.md`).

### Verdict: APPROVED_WITH_MINORS. 0 BLOCKER, 0 MAJOR, 1 MINOR, 3 NIT

Three of the four new tests meet the deletion standard outright. I7e's test meets it only with high
probability (D2-m1). The 16/22 count is right, and the 22 backlog entries carry their rows' wording
verbatim.

### (1) The four tests under deletion

- **I8, `outageTimeAfterWaitingIsNeutralNotWaiting`: sound.** With `budget.failed(start)` deleted,
  the kind stays WAITING after the first S4 answer. The 6 s of F1 attempts then fill the 3 s
  budget, and the next S4 answer (not the record's first, so the `!first` guard does not apply)
  dead-letters it. `handled` never gets the record and the await fails. With the call present,
  about 1.5 s of waiting is spent, well under 3 s.
- **I12, `outagesAfterWaitingKeepTheWaitWarnGoing`: sound.** After one S4 answer, six F1 attempts
  (backoff 0.1 s doubling, about 6 s) run before the record is handled. With the
  `failed && headAnsweredS4` clause deleted, only S4 answers count as waiting: one answer gives no
  WARN, and `waitWarnings() >= 1` fails. With it present, the WARN fires at the 1 s interval (the
  run continues within `4 × MAX_BACKOFF_MS`).
- **I14 revocation, `theRevocationCommitCarriesWaitingNoOtherCommitDoes`: sound.**
  - `close()` is called once attempt 5 has started (the latch). It sets `running = false` and
    calls `wakeup()`.
  - Between the wakeup and the end-of-batch `commitSync`, the poll thread makes only `seek` and
    `pause`, which do not check the wakeup flag; the stub handler calls no `keep()`. The first
    blocking call is the classic coordinator's `commitSync`, whose network poll calls
    `maybeTriggerWakeup` and throws `WakeupException`, which `cycle()` rethrows.
  - `run()` returns, and `close()` finds the thread done (3f666cd) and closes from its own thread.
    The thread is no longer alive, so the listener commits `position(p) = X` with the projected
    `spent`, slow attempt included.
  - With the revocation commit deleted, the last commit is attempt 4's RETRY checkpoint, about
    1.5–2 s spent, against `waited − 1000` of about 4 s. The test fails.
- **I7e, `partitionsMovedWhilePausedKeepEveryoneFlowing`: sound in its positive assertions,
  probabilistic in its counterfactual (D2-m1).**
  - *Assignment.* Range assignment by client id is deterministic under the classic protocol (the
    default for this client). The coordinator's member id is `clientId + "-" + UUID`,
    `RangeAssignor` sorts members by member id (no `group.instance.id` is set), and the client's
    default assignor list puts `range` first. With 3 partitions, `a-first-…` gets 0 and 1 and
    `b-second-…` gets 2, so the paused partition moves while its old owner runs. Confirmed.
  - *The log watch.* "cycle failed" is logged only by `run()`'s catch around `cycle()`. During
    the join, a failed batch commit is caught inside `cycle()` and logs "a batch could not be
    completed", the listener catches its own failures, and `resolveCommitted` catches its own. I
    see no other source that would make the watch flaky.
  - *The counterfactual.* With both the owned-partition check and the revocation's `remove`
    deleted, an `IllegalStateException` arises only if partition 2's stale state still has
    `resumeAtNanos >= 0` when the eager rebalance revokes it. `resumeDue` sets `resumeAtNanos = -1`
    before it resumes. If the rebalance's poll is the one in which partition 2 had just been
    resumed and its record not yet handled, the stale state carries -1, `resumeDue` never touches
    it, no ISE occurs, and the test passes with both deleted. Partition 2 is unpaused for a few to
    about 100 ms of each 500 ms re-read cycle, so the deletion goes unnoticed with a small but real
    probability per run. **Needs execution** to measure it.

### (2) The rewritten rows and the count

- **I8, I12: exact.** Each Proved clause names the deletion its test catches.
- **I14: exact.** The revocation clause moved to Proved with a correct mechanism. The remaining
  Not proved clause (a crash while tripped; `onPartitionsLost`) is right: deleting the override
  would bring back the default, which calls `onPartitionsRevoked` and so commits, and no test would
  notice. "Deleting the lost path's absence of a commit" is awkward wording for that.
- **I7e: claims slightly more than the test.** "Deleting both … fails the test" holds with high
  probability, not always (D2-m1). "The second member re-reads it" is asserted within 30 s, which
  is "promptly" rather than the invariant's "at once" (D2-n1).
- **Count: right.** At the audit: 13 full, 24 partial, 1 none. I7e moves from none to full; I8 and
  I12 from partial to full; I14 stays partial. That gives 16 full and 22 partial, 38 in all,
  consistent across the status block (line 77) and the section 8 preamble (lines 505–506).

### (3) PB-80 to PB-101

Twenty-two entries, one per partial row: I3a, I7, I7d, I7f, I10, I11, I13, I14, I14b, I15, I17–I24,
I26–I29. That is exactly the partial set. Each Problem line is the row's Not proved text verbatim
(I checked all 22; I17's and I26's include the R-m1 and R-m2 additions, and I14's is the post-acd8ce0
text). The ids are unique in `product.md`, and the index row and `M6_updates.md` claim
PB-77 to PB-101. PB-77 and PB-79 match contract 13.1 and 13.2. **PB-78's resolution** ("fails if a
resume of a partition no longer owned is attempted") inherits D2-m1's overstatement. PB-96's Problem
line is a bare list of meter names without the "deleting … would not fail any test" frame, faithful
to its row but unlike the others (D2-n2).

### (4) Notebook and proposal

- **Notebook: accurate in what it cites.** The audit's 13/24/1 and the later 16/22 are right, and
  the rows written before amendment 1 that over-claimed are real cases (I7's `!first`, I8's
  `budget.failed`, I12's clause, I13's "decided again", I14's revocation commit). The figure
  "21 of the 24 partial rows claimed more than their tests prove" comes from the author's own
  audit. It cannot be checked against the reviews: implementation-3 named six rows and two wordings,
  not an exhaustive count (D2-n3: attribute it to the audit).
- **Master-prompt proposal: accurate.** Its three anchors exist as quoted in
  `docs/prompts/FraudShield_Master_Build_Prompt.md`: "Docs and traceability" (line 752), I.3 step 2
  with the exact quoted phrase (line 759), and I.5's "Mutation spot checks" heading (line 783). It
  is correctly framed as a proposal for the owner, not an edit.

### Findings

| # | Sev | Where | What | Minimal fix |
|---|---|---|---|---|
| D2-m1 | MINOR | `EnvelopeConsumerTest.partitionsMovedWhilePausedKeepEveryoneFlowing`; contract I7e; `product.md` PB-78 | The counterfactual is probabilistic. If the eager rebalance happens in the poll right after partition 2 was resumed, the stale state has `resumeAtNanos = -1`, no resume of the unowned partition is ever attempted, and the test passes with both guards deleted. The row and PB-78 state it as certain. | Make partition 2's pause long and stable before the second member joins. For example, let the orphan fail F1 a few times first, so its backoff is several seconds, or join only after observing it paused. Or word the row "fails with high probability" and keep I7e partial. |
| D2-n1 | NIT | contract I7e | "Re-reads at once" is asserted as "within 30 s". Nothing in this code delays it beyond the client's own unpausing of a new assignment. | Say "within 30 s (the client unpauses a new assignment; nothing here delays it)". |
| D2-n2 | NIT | `product.md` PB-96 (and row I23) | The Problem line lists meter names without the deletion frame the standard asks for. | "Deleting the code that moves `…` would not fail any test." |
| D2-n3 | NIT | `docs/research/lab_notebook.md`, audit paragraph | "21 of the 24 partial rows claimed more than their tests prove" is the author's audit figure, not a review's. | Attribute it: "the audit found 21 of 24 …". |
