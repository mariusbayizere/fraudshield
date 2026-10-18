# M6 review: amendment 1, draft 3, to the decision-fact ordering contract (d7c1554)

Reviewer: a fresh, independent Principal reviewer. I did not write the amendment or the code.

## Scope

- `git diff 2dafbf0 d7c1554`, then the whole of `docs/architecture/decision-fact-ordering.md` as it
  stands at d7c1554 (read in full, not only the diff), ADR 0056's amendment section and the last
  section of `docs/parallel/M6_updates.md`.
- The findings it answers: `m6-decision-2026-10-17-amendment-1-draft-2.md` (draft 2: 2 MAJOR,
  9 MINOR, 8 NIT, cited `[A2-…]`), with draft 1's MAJORs (`-amendment-1.md`, `[A1-…]`) and the
  implementation review it all answers (`-implementation-2.md`).
- Sections 6 and 7 compared with `git show 1288143:docs/architecture/decision-fact-ordering.md`
  (draft 5, reviewed sound), and the restored invariants I7e, I8, I9 and I14 compared line by line.
- Code read only to judge whether the text can be implemented and is complete:
  `notify/kafka/EnvelopeConsumer.java` (consumer and producer settings, the poll loop's handlers,
  the 10 s DLQ future wait), `sms/CustomerSmsSender.java` (render, send, record),
  `sms/SmsCatalogue.java` (`MAX_LINK_LENGTH` = 54), `sms/InstitutionMessaging.Settings` (base + 22
  ≤ 54), `sms/AfricasTalkingGateway.java`, `vault/` (`KmsClient`, `KmsKeyProvider`,
  `VaultContacts`, `VaultException`), `ingest/.../config/NotificationWiring.java` (the vault's
  `PGSimpleDataSource`, the `webhook-delivery` executor), `ingest/src/main/resources/application.yml`
  (management section), `contracts/kafka/topics.yaml`, migration `V5` (`provider_reference`,
  `locale` CHECKs), and the probe paths in ADR 0011, ADR 0014 and `M9_updates.md`.

## Method

Static review only: `git`, `grep`, `sed`, `cat`. Nothing was built or run (owner's constraint: little
memory, shared machine). Kafka client behaviour is from the documented `KafkaConsumer` and
`KafkaProducer` APIs: `KafkaConsumer` is not safe for access from more than one thread; `commitSync`
without a `Duration` blocks up to `default.api.timeout.ms` (60 s); `send` may block up to
`max.block.ms` (60 s) before it returns a future; `LogTruncationException` (a subclass of
`OffsetOutOfRangeException`) is raised only when the client validates a position that carries a
leader epoch, and a committed `OffsetAndMetadata` built without one gives the next owner none.
Spring Boot behaviour is from its actuator documentation: the root `/actuator/health` aggregates
every contributor, and health groups are subsets of it. Points only execution can settle are marked
**needs execution**.

## Verdict: SOUND. 0 BLOCKER, 0 MAJOR, 7 MINOR, 9 NIT

Draft 3 closes both MAJORs of draft 2.

- **The `Error` rule no longer skips records.** It seeks every partition of the batch back, commits
  those offsets and only then closes. The revoke commit that `close()` runs therefore commits the
  same positions.
- **Every dead-letter send failure but `RecordTooLargeException` is F1.** The producer's timeouts
  are now consistent: 15 s + 0 ≤ 30 s ≤ the future's 35 s. Only a record too large even without
  `fs-dlq-error` can be dropped, and that drop is bounded, counted and logged with the source
  coordinates.

The kept result is now committed before the outcome write, which narrows 13.1's window to one reply and one commit. It rides
on the dead letter as `fs-dlq-result`, and a replay records it without a send. Draft 1's MAJORs stay
closed. The four restored invariants match draft 5. W-b's neutral time, W-c2's guarantee, the W-e
checkpoints and D-c/D-d replay are not reopened: the only change to section 6 is W-e2's format.

What remains is MINOR. Several fixes from draft 2 are only partly effective:

- the `notify` health group cannot keep `/actuator/health` UP (m1);
- the `Error` rule cannot apply to the webhook delivery executor (m2);
- the truncation fix leaves a divergence below the log end unhandled, and I22 still describes the
  old rewind (m3, m4);
- the time budget's arithmetic leaves out two 60 s client defaults, and the vault's connect and login timeouts are still not named (m5).

The lifecycle of `fs-kept-result` is under-specified (m6). D-g contradicts the new D-c (m7). None of these
resends an SMS, loops without bound on a common path, or skips a record silently on a common path.

## Closure of the draft-2 findings

| Finding | Closed? | Why |
|---|---|---|
| A2-M1 (`Error` close commits past unhandled records) | **closed** | 12.2 steps 1–3: seek back (the record that threw included), commit with metadata, then close. The revoke listener's `position(p)` is now the seeked offset. I26 asserts that the next owner re-reads them. Residuals: the executor half cannot be implemented (m2). If step 1 fails, nothing stops the close from committing an unseeked position (n6). |
| A2-M2 (shared DLQ faults dropped) | **closed** | The F1 row covers any DLQ send failure except `RecordTooLargeException`, timeouts, ACLs and an invalid topic included. The drop is only the too-large case, retried once without `fs-dlq-error`. The ERROR names topic, partition and offset. 12.3 gives consistent producer timeouts. I27 tests a timed-out send. Residual: `max.block.ms` is not set (m5). |
| A2-m1 (moment of the R checkpoint) | **closed** | 12.4 fixes the order: call, keep, commit X with R for that partition alone, then write. 13.1 is restated to "one provider reply and one `commitSync`, longer while commits fail". Residual wording: n7. |
| A2-m2 (placeholder) | **closed** | The placeholder is `MAX_LINK_LENGTH` (54), and `InstitutionMessaging.Settings` already requires base + 22 ≤ 54, so the trial is conservative. Both templates are trial-rendered. The trial text is never sent. A re-read holding R skips the vault, the render and `issue`. |
| A2-m3 (vault timeouts; batch size) | **in part** | (a) The vault gets `socketTimeout` and `statement_timeout` as connection options. That can be implemented on `PGSimpleDataSource` (`setSocketTimeout`, `setOptions`). `connectTimeout` and `loginTimeout`, which the finding named, are not given. (b) A 60 s batch budget replaces a bound on `max.poll.records`. That is a valid choice, but its arithmetic is wrong (m5). |
| A2-m4 (KMS outage cannot be typed) | **closed** | `KmsUnavailableException` decides at once as F1 (12.2 rule 2). The contract change is in 11.18 and the status block. Whether it extends `VaultException`, and where the 5 s timeout lives, is left open (n3). |
| A2-m5 (truncation rewinds to the log start) | **in part** | 12.5 now seeks to the divergent offset, or to the log end. I22 still says "restarted from the log start" (m4). A divergence below the log end has no branch, and the commits carry no leader epoch, so the divergent offset is rarely available (m3). |
| A2-m6 (D-d stale) | **closed in D-d** | D-d is rewritten correctly. The status block's list of changed parts still leaves out D-d (n1). D-g is now the stale paragraph (m7). |
| A2-m7 (section 8 gaps) | **closed** | (a) I28; (b) I29; (c) I26 extended (timeouts, `Error` no-skip); (d) I1 cites I21 for S3m (wording: n5); (e) I18 "as 12.6 defines". |
| A2-m8 (DOWN takes the API out; executor silent) | **not closed** | (a) The `notify` group sits outside readiness and liveness, but the root `/actuator/health` still aggregates it, and that root endpoint is this project's liveness and uptime probe (m1). (b) The executor is given a rule it cannot carry out (m2). |
| A2-m9 (R never recorded; 128-character limit) | **closed** | (a) `fs-dlq-result` → `fs-kept-result` → the consumer writes R without a send (12.4, D-c, I28). (b) A reference is cut to 127 characters + `~`, in R and the row alike. The 128-character limit is checked against the raw string. Residuals: the header's lifecycle (m6) and an encoding detail (n4). |
| A2-n1 | closed | W-e2: the raw reference is limited before encoding; empty allowed; recorded as null. |
| A2-n2 | closed | 12.2 rules 1 and 2. |
| A2-n3 | closed | The bound is checked only at the end of an F4 attempt; "`f4_ms`" is used. |
| A2-n4 | **in part** | A request timeout (`HttpTimeoutException`, an `IOException`) may have been delivered but is not counted. An unreadable 2xx also arrives as `IOException` and must be told apart (n2). |
| A2-n5 | closed | D-c names `--allow-rejected`, and `outcome_unrecorded` is replayed with `--reason`. |
| A2-n6 | closed | `request.timeout.ms` 15 s, `linger.ms` 0, `delivery.timeout.ms` 30 s. |
| A2-n7 | closed | The drop ERROR names N and R. |
| A2-n8 | closed | I7d says "F1 or F4"; PB-78 is "to be resolved by this round" in both places. |

**Draft 1's MAJORs stay closed.**

- **A1-M1.** A re-read holding R never calls the provider, and R is now committed before the write.
- **A1-M2.** SQL is unchanged and KMS is now typed.
- **A1-M3.** W-e and W-e2 carry `f4_count`, `f4_ms` and R on every commit and in the revoke commit.

## Findings

| # | Severity | Location | Finding | Failure scenario | Minimal change to the text |
|---|---|---|---|---|---|
| m1 | MINOR | 12.2, the paragraph after the `Error` steps; 11.19; I26 ("readiness and liveness groups stay UP") | A health indicator in a `notify` group is still part of the root `/actuator/health`: groups are subsets, not exclusions. `application.yml` defines no groups and does not enable probes. ADR 0014 calls `/actuator/health` "process liveness only", ADR 0011 names it as the probe, and `M9_updates.md` (OPS-OBS-05) says the probes read `/actuator/health`. So the stated goal is not met: "a stopped consumer must not take the ingest API out of service". This is A2-m8(a) unchanged. | One SMS intent raises `ExceptionInInitializerError`. Each member stops in turn (12.2), and every instance's `/actuator/health` turns 503. The uptime probe pages P1. A liveness probe on that path restarts every instance. After each restart the consumer re-reads the record and stops again. The result is a repeating decision-API outage caused by one notification. | "The stop is reported by `fs_notify_consumer_running` and the ERROR log. It is not registered as a contributor of the root `/actuator/health`. If it is exposed as a health indicator, then only together with `management.endpoint.health.probes.enabled=true`, probes moved to `/actuator/health/liveness` and `/readiness`, and ADR 0014 point 6 and M9's OPS-OBS-05 amended to match." I26 then asserts that `/actuator/health` stays UP. **Needs execution** to confirm the aggregate. |
| m2 | MINOR | 12.2 `Error` bullet ("the poll thread or the webhook delivery executor, does in order: 1. seeks … 2. commits … 3. … closes the consumer"); 11.19; I26 | The webhook delivery executor (`NotificationWiring`, the `webhook-delivery` thread running `dispatcher.deliverDue`) owns no `KafkaConsumer` and has no batch. Calling `seek`, `commitSync` or `close` from it on the `webhook-dispatcher` consumer is multi-threaded access, which the client forbids (`ConcurrentModificationException` while the poll thread holds it). The rule as written cannot be implemented for that thread. | An implementer follows the text. The executor catches an `Error` and calls `consumer.seek`. That throws `ConcurrentModificationException` out of the catch block, and the scheduled task dies silently: A2-m8(b)'s failure, now with the appearance of handling. | "The webhook delivery executor catches every `Throwable` around each run. On an `Error` it logs at ERROR, counts `fs_notify_consumer_errors_total{group="webhook-delivery"}`, sets that loop's running gauge to 0 and stops scheduling. It touches no consumer. Queued deliveries stay in `webhook_deliveries`." I26 to match. |
| m3 | MINOR | 12.5, `OffsetOutOfRangeException` → "above the log end (`LogTruncationException` …)"; W-e (commits) | (a) The branches are chosen by comparing the position with `beginningOffsets` and `endOffsets`. After an unclean election, the new leader may already hold more records than the old one did: divergence at D < committed C ≤ log end E. The client raises `LogTruncationException`, but the position is neither below the start nor above the end, and no branch applies. With `none`, the client keeps raising it until the application seeks. `poll` therefore throws on every call and stops every partition of that consumer, with no bound. (b) The divergent offset is reported only for a position that carries a leader epoch. W-e commits `OffsetAndMetadata(offset, m)` without one, so after a change of owner a truncation surfaces as a plain `OffsetOutOfRangeException`, and the log-end fallback skips [D, E) uncounted. | An unclean election on `fs.notifications.customer` partition 4, with production continuing on the new leader. The owner's `poll` throws `LogTruncationException` (position inside [start, end]). The handler finds no case, and the loop polls again and throws again. All 12 partitions owned by that member stop until restart, and then the same thing happens. | "On `LogTruncationException`, seek each partition in `divergentOffsets()` to its divergent offset, whatever its place in the log, and log at ERROR. A plain `OffsetOutOfRangeException` above the log end seeks to the log end, logs C and E at ERROR, and counts E − (log start) … or states the gap as admitted. Every commit carries the leader epoch of the last record handled on that partition (`OffsetAndMetadata(offset, record.leaderEpoch(), m)`), so the next owner's client can detect a divergence." |
| m4 | MINOR | I22 ("An offset above the log end is logged at ERROR and restarted from the log start, uncounted"); 12.5; status block ("a truncation no longer rewinds to the log start") | I22 contradicts 12.5 and the status block, and it still encodes the behaviour A2-m5 showed re-sends SMS. I22's test is still to be written, so a test written to I22 would make the implementation rewind. | The test is written to I22 and the code follows it. After a truncation, a partition re-reads 3 days. Every intent dead-lettered as `outcome_unrecorded` has no outcome row and no metadata R (dropped on reset), so it is S2 again and is sent a second time. | I22: "… above the log end is logged at ERROR and sought to the divergent offset `LogTruncationException` reports, or else to the log end (12.5); it never rewinds to the log start." Add a test case per m3. |
| m5 | MINOR | 12.3, "every external call in an attempt has a timeout" and the batch budget ("about 60 s in the worst case … about 2 minutes, below … 300 s"); 11.17 | The worst case leaves out calls that 12.3 itself adds or relies on. (1) 12.4's commit of R, a `commitSync` that blocks up to `default.api.timeout.ms` (60 s). (2) The DLQ `send`, which can block up to `max.block.ms` (60 s) on metadata or buffer before the 35 s future wait starts. (3) The end-of-batch `commitSync` (60 s). (4) A hung statement is bounded by the 10 s socket timeout, not the 5 s `statement_timeout` (the server must answer to cancel). (5) The vault opens a new connection per call (`PGSimpleDataSource`), with `loginTimeout` unset (0) and `connectTimeout` the default 10 s (A2-m3, still open). An S2 attempt during a Kafka degradation can take about 10+10+10+5+10+8+60+10 s, and an S3 one about 10+95 s. The budget plus one attempt plus the final commit can pass 300 s, and the member is expelled, which widens 13.1. | Brokers degrade and the coordinator answers slowly. A batch spends its 60 s budget, the next S2 record's R commit waits 60 s, a DLQ send waits 60 + 35 s, and the end-of-batch commit waits 60 s. More than 300 s pass without a `poll`, the member is expelled, and a record whose R commit failed and whose outcome write failed is sent again by the next owner. | Add to 12.3: "the producer sets `max.block.ms` 10 s; every `commitSync` passes a `Duration` of 10 s; the vault data source sets `connectTimeout` 2 s and `loginTimeout` 5 s". Recompute the worst case from the socket timeouts, or set `max.poll.interval.ms` explicitly to at least the budget + the worst attempt + one commit, and list it in 11.17. |
| m6 | MINOR | 12.4 ("a replayed copy of X that carries R in `fs-kept-result` … writes R"); 12.1 (header grammar); D-a; D-c; I19; I28 | The header's lifecycle is under-specified. (a) It is honoured on any record, not only on one that also carries `fs-replayed`. It is neither logged nor counted when honoured, so a `SENT` header published by any holder of the topic's producer principal suppresses a real SMS without trace. (b) Whether a header R counts as "R kept" is not said. That decides the reason of a later dead letter: S4 → `parent_not_recorded`, or `outcome_unrecorded` with `fs-dlq-result`; and an F3 at the outcome write. (c) A copy dead-lettered again keeps the original `fs-kept-result` (D-a keeps headers), and the tool adds a second one from `fs-dlq-result`. What a repeated header means is not defined. Read strictly, as 5.1 reads `fs-transaction-id`, it is `malformed_envelope`, and R is never recorded. (d) A malformed `fs-kept-result` is `malformed_envelope` (12.1), but I19 does not cover it. | An `outcome_unrecorded` intent is replayed. Its outcome write fails with `23514` (for example a header locale outside V5's `('en','rw','fr','sw')`), and it returns to the DLQ as `outcome_unrecorded` with both headers. The second replay republishes two `fs-kept-result` headers. A strict reader dead-letters it as `malformed_envelope`, and every later replay does the same. The notification stays REQUESTED for ever, with the result on record only in the DLQ. | "`fs-kept-result` is honoured only on a record that also carries `fs-replayed`; elsewhere it is ignored and logged at WARN. Each honoured header is logged at INFO with N and R. A header R counts as kept for every rule of 12.4. The replay tool replaces any existing `fs-kept-result` with the copy's `fs-dlq-result`, never adds a second; the consumer reads more than one as malformed." I19: add a malformed `fs-kept-result`. |
| m7 | MINOR | 7, D-g ("`outcome_unrecorded` was sent or may have been: replaying it sends again unless its outcome has since been recorded, so D-c refuses it by default") | Stale since draft 3. It contradicts D-c, D-d, 12.4, I20 and I28: replaying `outcome_unrecorded` carries R and sends nothing. This is the same kind of contradiction as A2-m6, on the replay-safety rule an operator reads. | An operator reads D-g, believes a replay re-sends, and leaves `outcome_unrecorded` intents unreplayed. Their outcome is never recorded, which is what A2-m9 was fixed to avoid. | "`outcome_unrecorded` carries its provider result (12.4); replaying it with `--reason outcome_unrecorded` records that result and sends nothing. It is not a default reason." |
| n1 | NIT | Status block ("D-a, D-c, D-g, section 9 and section 11 are changed"); 11 item 11 ("drops a record whose dead letter cannot be written") | D-d changed but is not listed (A2-m6's last point). Item 11 is broader than 12.2, which drops only a dead letter that is too large. | | Add D-d. Item 11: "drops a record whose dead letter is too large to write (12.2)". |
| n2 | NIT | 12.4 (outcome unknown); 12.6 `fs_notify_sms_outcome_unknown_total` | "A 2xx reply that could not be read, or threw an exception other than `IOException`" omits a request timeout. `HttpTimeoutException` is an `IOException` and the request may have been accepted. An unreadable 2xx also surfaces as `IOException` (the adapter's conversion), so it needs its own subtype to be counted. `HttpConnectTimeoutException` extends `HttpTimeoutException` and is not ambiguous. | | "… counted: a request timeout other than a connect timeout, an unreadable 2xx (the adapter's own `IOException` subtype), and any other exception; not a connect failure or a non-2xx reply." |
| n3 | NIT | 11.18; 12.2 rule 2; 12.3 KMS bullet | It is not said whether `KmsUnavailableException` extends `VaultException`. `KmsClient`'s Javadoc today says "throws `VaultException`", and callers rely on that. "The reference implementation throws it": the only implementation is the test's `InMemoryKms`, which is never unreachable. The 5 s timeout can be met only by M9's binding. | | "`KmsUnavailableException` extends `VaultException` (not permanent). The 5 s timeout and the exception are requirements on every binding, asserted by `KmsClientContract` where a binding can simulate them; there is no production binding in M6." |
| n4 | NIT | 12.4 and W-e2 (reference truncation); W-e2 ("at most about 300 bytes") | V5's `char_length` counts code points. "127 characters" does not say code points or UTF-16 units: cutting at a unit can split a surrogate pair. A reference that already ends in `~` cannot be told from a truncated one. A U+0000 is refused by PostgreSQL (`22021`, F3), so the row is `outcome_unrecorded` on every replay. With multi-byte references the line reaches about 850 bytes, which is still below 4,096. | | "127 code points"; "a U+0000 is replaced before R is kept"; "at most about 900 bytes". |
| n5 | NIT | I1 ("S3m: I21"); I17 (last sentence); I20 | I21's claim does not include "nothing sent, issued or recorded", which I1 delegates to it. I17's "the next attempt dead-letters the record" does not hold for `RecordTooLargeException` (drop, I27). No row claims 12.4's commit-before-write order, the core of A2-m1. That order can be tested: hold the outcome write, then read the committed metadata. | | I21: add "and nothing is sent, issued or recorded". I17: "unless the dead letter is too large (I27)". I20: "the committed metadata holds R before the outcome write starts". |
| n6 | NIT | 12.2 `Error` steps; 13.1 | (a) If step 1 or 2 fails, for example a second `OutOfMemoryError`, step 3's `close()` still runs the revoke listener on `position(p)`. That position was not sought back, so it is past the batch. (b) An `Error` between the provider call and the commit of R (step 2 commits X without R) has 13.1's effect, but 13.1 names only a crash or an expulsion. (c) The stop moves every partition of the group, not only the record's, until every member has stopped. | | (a) "During an `Error` stop the revoke listener commits nothing; step 2's commit is the only one." (b) 13.1: "crashes, is expelled, or stops on an `Error`". (c) Say that the whole group stops. |
| n7 | NIT | 12.4 ("the commit is retried before X is re-read"); W-e ("Committing X moves nothing, because X is already the position"; the merge sentence) | The text does not say whether the re-read waits for the commit to succeed, or whether a pending retry is dropped once X is handled. A late retry would commit X after X+1: harmless (S1), but backwards. Mid-batch, X is no longer the position. The R checkpoint is its own `commitSync`, not merged into `done`. | | "The retry is dropped once the head changes; the re-read does not wait for it." Note in W-e that 12.4's checkpoint is a separate commit. |
| n8 | NIT | D-a | A copy resent without `fs-dlq-error` (12.2, too large) lacks a header D-a says every copy gets. | | "`fs-dlq-error`, unless omitted for size (12.2)". |
| n9 | NIT | 12.4; section 13 | A provider outage (connection refused, HTTP 5xx) gives FAILED, and the notice is never sent. That is today's behaviour (`catch (IOException rejected)` records FAILED), kept on purpose. A connect failure provably did not reach the provider, but section 13 does not admit the loss. | | Add to 13: "a provider outage records FAILED and the notice is not retried; a connect failure could be retried safely (later milestone)". |

## a. Closure

See the table above.

- **Both MAJORs are closed.** A2-M1 and A2-M2 are closed in their main paths, with only
  implementability residuals (m2, n6) and one timeout gap (m5).
- **Closed:** A2-m1, A2-m2, A2-m4, A2-m7 and A2-m9.
- **Closed in part:** A2-m3 (m5), A2-m5 (m3, m4) and A2-m6 (D-d done, D-g now stale: m7, n1).
- **Not closed:** A2-m8 (m1, m2).
- **NITs:** every one is closed except A2-n4 (n2).
- **Draft 1's three MAJORs remain closed.**

## b. New defects in draft 3

**The `Error` rule (12.2).**

- **The order is right.** Seek, commit and close commit the same offsets twice. The first unhandled
  offset of a partition whose records were all handled equals its position, so `done` is covered.
  A partition outside the batch has a position that is still its next unreturned offset.
  - The seek step is what makes the revoke commit safe, not the commit.
  - If the seek fails, nothing stops `close()` from committing past (n6a).
- **An `Error` raised inside `poll`** (in a rebalance listener) leaves no batch in hand. The rule
  still holds.
- **The webhook executor cannot apply it** (m2).
- **The health group does not do what it says** (m1).

**The dead-letter classes.**

- F1 for every send failure but `RecordTooLargeException` is right. Every record of a consumer goes
  to one `<topic>.dlq`, and the only record-specific producer failure for `byte[]` records with a
  delete policy (`topics.yaml`: `cleanup_policy: delete`) is size.
- **The retry without `fs-dlq-error`** removes the only header whose size the consumer does not set.
  `fs-dlq-result` is at most about 900 bytes (n4).
- **A record still too large** is F4. It is re-tried on the backoff and dropped at 5 attempts and
  10 minutes: bounded, counted, and logged with topic, partition, offset, N and R. Its value stays
  on the source topic for its retention.
- **No loop without bound** except F1, which is by design.
- D-a's header list needs one word (n8).

**The kept-result order (12.4).**

- Call, keep, commit `OffsetAndMetadata(X, m)` for X's partition alone, then write. I traced it
  against W-e:
  - **`done` for that partition.** Earlier records of the batch on it make `done` = X, and the
    mid-batch commit of X covers them, correctly.
  - **The end-of-batch commit.** It then carries X+1 without R (written) or X with R (failed), as
    W-e's every-commit rule requires.
  - **The merging rule** applies to the end-of-batch commit only. The R checkpoint is a separate,
    earlier commit (n7).
  - **No regression of the committed offset**, unless a failed R commit is retried after X is
    handled. That is harmless: S1 (n7).
  - **A commit failure through expulsion** is 13.1's window, as stated.
- **The batch budget** is checked between records, never between the send and the R commit.
- The R commit adds to the time between polls (m5).

**`fs-dlq-result` and `fs-kept-result`.**

- **A replayed copy whose outcome is already recorded** is S1, so nothing is written or sent.
- **B missing** (a replay into a database without B) is not covered by 12.4's "with B visible". It
  falls to S4, and D-d sends it back to the DLQ. Under which reason, and whether R counts as kept,
  is undefined (m6b).
- **A malformed header** is `malformed_envelope`, with R lost to automation. That causes no resend.
- **A second replay** can produce two headers (m6c).
- **A forged or replayed `SENT` header can suppress a real SMS.** It needs the topic's producer
  principal (`topics.yaml`: producers `[fraudshield-api]`), but nothing limits it to replayed
  records or leaves a trace (m6a).
- **No path re-sends.** A header can only prevent a send, never cause one.

**The batch budget against `max.poll.interval.ms`.** Not guaranteed. The worst case leaves out the R
`commitSync`, `max.block.ms`, the final commit and the vault's login (m5). If the bound is
exceeded, the member is expelled. Records are then re-read from the last commit, not skipped, but
13.1's window opens wider.

**The vault timeouts and the KMS contract.**

- **The vault.** `setSocketTimeout(10)` and `setOptions("-c statement_timeout=5000")` on
  `PGSimpleDataSource` can be implemented. They apply in autocommit, unlike `SET LOCAL`. A vault
  `SQLException` is wrapped in a non-permanent `VaultException` (`VaultContacts`), so walking the
  chain classifies it by SQLSTATE: a socket timeout is `08006` (F1), and `statement_timeout` is
  `57014` (F4).
- **KMS.** The contract change can be implemented against today's `KmsClient` and `KmsKeyProvider`.
  `KmsKeyProvider` calls the client without wrapping, so `KmsUnavailableException` reaches the
  classifier as itself. Its supertype is open (n3).

**Truncation with the Java client.**

- 12.5 handles a position below the start and one above the end.
- It has no case for a divergence below the log end, and for that case the client raises
  `LogTruncationException` on every `poll` until the application seeks (m3a).
- The commits carry no leader epoch, so after a change of owner the divergent offset is never
  reported (m3b).

**The 128-character limit against V5.** `char_length(provider_reference) <= 128` holds for 127
characters plus `~` under either reading of "character", because `char_length` counts code points.
Two details remain: a split surrogate, and U+0000 (`22021`) (n4).

**Can anything still resend, loop, skip or lose beyond the contract?**

- **Resend.** Only through 13.1. The window is widened by an `Error` (n6b) and by an expulsion
  through m5. I22, if implemented as written, would resend (m4).
- **Unbounded loop.** F1 by design. An in-range truncation is a second one (m3), but it needs an
  unclean election (`min_insync_replicas: 2`, and unclean election is off by default).
- **Silent skip.** None on a common path. The log-end fallback after a truncation skips [D, E)
  with only an ERROR (m3b).
- **Lost notice.** The admitted drop (12.2), a retention gap (12.5, counted), and a provider outage
  recorded as FAILED, which is today's behaviour but not admitted in section 13 (n9).

## c. Section 8 under the owner's rule

- **Rows naming existing tests are unchanged from draft 2,** where they were checked: I1 (its
  S3/S4/I4b half), I2, I3b, I4, I4b, I5, I6, I7, I7b, I7c, I8, I11, I12, I13, I15, I16 and (wire).
  They still hold as worded.
- **The "in part" markings are correct and complete.** They are on I3a, I7e, I7f, I9 and I14, each
  with its gap and the test this round adds. I10 is "to be proved". I14b says what it does not
  produce. Rows still to be written claim nothing yet.
- **The restored invariants match draft 5.** I7e, I8 and I9 are word for word. I8's added
  parenthesis only reconciles it with 12.3. I14's change of bound is the justified correction
  already accepted.
- **I17 to I29 against section 12.** All can be tested, apart from I26's executor half (m2). Two
  rows are not faithful: I22 (m4) and I26's health clause (m1).

| Invariant | Section | Note |
|---|---|---|
| I17 | 12.3 | its last sentence misses the too-large case (n5) |
| I18 | 12.5, 12.6 | faithful |
| I19 | 12.1 | does not cover a malformed `fs-kept-result` (m6d) |
| I20 | 12.4 | does not claim the commit-before-write order (n5) |
| I21 | 5.1, 5.3 | should add "nothing done", which I1 delegates to it (n5) |
| I22 | 12.5 | **contradicts 12.5** (m4) |
| I23 | 12.6 | faithful; one count is inexact (n2) |
| I24 | 12.2 | review plus a table, labelled as such |
| I25 | 12.4 | consistent: the test fails if `issue` precedes the render (the replayed SMS would lack a link) |
| I26 | 12.2, 12.3 | executor half cannot be implemented (m2); the health clause rests on a group the root endpoint ignores (m1) |
| I27 | 12.2 | faithful; an ACL case would strengthen it |
| I28 | D-c, 12.4 | faithful |
| I29 | W-e2, 12.4 | faithful |

## d. Other

- **Contradictions between sections:** D-g against D-c, D-d, 12.4 and I28 (m7); I22 against 12.5
  (m4); 11 item 11 against 12.2 (n1).
- **Undefined or unimplementable terms:** the `Error` rule on the delivery executor (m2); "R kept"
  for a header R, and a repeated `fs-kept-result` (m6); "127 characters" (n4); the supertype of
  `KmsUnavailableException` (n3).
- **Behaviour changes the status block does not list:**
  - D-d;
  - a DLQ future wait raised from 10 s to 35 s (in 11.17 only);
  - an `Error` stopping every partition of the group until the operator acts (n6c).
- **ADR 0056 and `M6_updates.md`** match the contract. PB-78's wording is fixed in both places.
- **No BLOCKER or MAJOR.** The draft-2 MAJORs are closed. The remaining defects are in residual
  paths, under-specified edges, or wording that disagrees with section 12. None resends an SMS,
  loops without bound or skips records on a path a normal deployment meets. I fix them as MINOR, at
  the same bar as A2-m5 and A2-m8, which they continue.

## Needs execution (after the code round)

1. m1: start the ingest application with the `notify` indicator DOWN. Assert what `/actuator/health`,
   `/actuator/health/liveness` and `/actuator/health/readiness` return.
2. m3: a consumer whose committed offset lies inside a diverged range (a broker test with unclean
   election, or a mocked `LogTruncationException` with the position in range). Assert that the
   consumer seeks to the divergent offset and polls on.
3. m5: a coordinator that delays `OffsetCommit`, with an S2 record and an S3 record in one batch.
   Measure the time between polls against `max.poll.interval.ms`.
4. m6: replay an `outcome_unrecorded` copy twice, with a forced F3 between. Assert that the second
   replay records R.
5. A2-M1 and I26: an `Error` thrown mid-batch on a partition with `spent` > 0. Assert that the next
   owner re-reads the record that threw and every one behind it.

## Delta check of f47b3cb

Scope: `git diff a21e428 f47b3cb` (contract, ADR 0056, `M6_updates.md`). Where a change touches
other sections, I re-read them in full: 12.2–12.5 and 13, W-e, D-a, D-c, D-g, section 8 (I17, I19
to I22, I26, I28) and section 11. Code was read again only to check claims the fixes make:
`WebhookDispatcher.attempt` and `deliverDue`, `RetryPolicy.exhausted`, V63's `webhook_deliveries`
and `webhook_deliveries_due`. Backlog id `PB-79` was searched for on every local and remote branch;
it is unused elsewhere. The method is the same as above: static only, nothing built or run.

### Closure of draft 3's findings

| Finding | Resolved? | Why |
|---|---|---|
| m1 | **resolved** | There is no health indicator for either stop. `fs_notify_consumer_running`, `fs_notify_webhook_delivery_running` and the counter are the signals, and M9 alerts on them. I26 now asserts that `/actuator/health` stays UP. |
| m2 | **resolved** | The executor has its own rule and touches no consumer. The claim that "deliveries stay `PENDING` and are delivered after a restart" holds in the code. `attempt` runs in a transaction that is never committed when an `Error` escapes: `catch (SQLException)` does not catch it, and closing the pooled connection rolls it back. The row therefore stays `PENDING` with its `next_attempt_at`, and the row lock is released. Any instance's executor picks it up through `webhook_deliveries_due` and `FOR UPDATE SKIP LOCKED`. Wording: D-n4. |
| m3 | **partly** | `LogTruncationException` is now handled wherever the divergence lies, and every commit carries a leader epoch. A partition the exception names *without* a divergent offset still has no rule (D-m2). |
| m4 | **resolved** | I22 matches 12.5: the log end, or the divergent offset, and never the log start. |
| m5 | **resolved, with a new defect** | `max.block.ms` 10 s, a 10 s timeout on every `commitSync`, and vault `connectTimeout` and `loginTimeout` of 5 s. My recomputation: three statements at 10 s each, a vault read of 10 s plus up to 5 s of login, KMS 5 s, provider 8 s, the commit 10 s and the DLQ send 45 s give about 115 s. Adding Hikari's 2 s acquisitions, the 60 s budget and the final 10 s commit gives about 190 s, below 300 s. The text's "about 110 s / 180 s" is close enough. The new sentence "a commit that times out is an F1 failure of the attempt" contradicts 12.4 (D-m1). |
| m6 | **resolved** | `fs-kept-result` is honoured only on a record carrying `fs-replayed`. Elsewhere it is ignored, logged at WARN and counted (`fs_notify_ignored_kept_results_total`). A result from the header is a kept result in every respect. Repeated identical values count as one, and `none` is refused. The tool replaces the header rather than adding one. I19 and I28 cover these rules. **Can a legitimate path lose the result?** No. 12.1 and D-d test whether `fs-replayed` is *present*, not whether its value parses, and the tool always sets it when it republishes. A replayed copy whose `fs-replayed` value is malformed is still "replayed", so its result is honoured. |
| m7 | **resolved** | D-g now agrees with D-c and 12.4. |
| n1 | **resolved** | D-d is listed; section 11 item 11 says "too large to send". |
| n2 | **resolved** | `ProviderReplyUnknownException` (an `IOException` subtype) covers an unreadable 2xx and a request that timed out after it was sent. The adapter can tell these apart from `HttpConnectTimeoutException`. |
| n3 | **resolved** | `KmsUnavailableException` is a non-permanent `VaultException`; the 5 s limit is on M9's binding. |
| n4 | **resolved** | The limit is in code points; U+0000 becomes U+FFFD; `~` is a convention only; the line is about 900 bytes. |
| n5 | **resolved** | I21 adds "nothing sent, issued or recorded"; I17 defers to I27; I20 claims that R is committed before the write. |
| n6 | **resolved** | See the next subsection. |
| n7 | **partly** | The text chose that the re-read *waits* for the commit carrying R, the opposite of my suggestion, and that choice widens 13.1 (D-m1). "At offset X (its position …)" is inaccurate mid-batch (D-n2). |
| n8 | **resolved** | D-a notes that a copy re-sent for size lacks `fs-dlq-error`. |
| n9 | **resolved** | 13.2 and `PB-79` (claimed in the ADR, the contract and `M6_updates.md`). One wording point: D-n3. |

### The `Error` rule as amended

- **The `stopping` flag.** It makes the revoke listener commit nothing during the close, so the
  only commit is step 3's. That removes the old dependence on the revoke commit using a seeked
  `position(p)`.
- **"Commit only if every seek succeeded" is safe.** When a seek fails, nothing is committed, and
  the group resumes from the last successful commit. Every commit the contract makes is at or before
  the first unhandled offset of its partition:
  - `done` offsets of handled records;
  - head checkpoints at X;
  - the R commit at X.

  So the claim "never past an unhandled record" holds.
- **The cost** is the metadata since the last commit, and possibly a kept R whose commit had not
  succeeded. 13.1 now names an `Error` in that interval.
- **When a seek can fail.** Revocation happens only inside `poll`, so every partition of the batch
  is still assigned. A seek can therefore fail only through a second `Error`.

### New findings

| # | Severity | Location | Finding | Failure scenario | Minimal change to the text |
|---|---|---|---|---|---|
| D-m1 | MINOR | 12.3 "Commits" ("a commit that times out is an F1 failure of the attempt"); 12.4 order ("If that commit fails, the outcome write still proceeds. If the outcome write also fails, X is not re-read until a commit carrying R has succeeded …"); 13.1 | (a) 12.3 makes a timed-out R commit an F1 failure of the attempt, which ends the attempt before the outcome write. 12.4 says the write proceeds. For the end-of-batch commit, "the attempt" is undefined, because no attempt is running. (b) Both readings, and 12.4's new wait, put the outcome write behind a successful commit. Commits time out when the group coordinator is in trouble, which is exactly when the partition is likely to be lost. While the outcome is unwritten and R is not committed, losing the partition re-sends the SMS (13.1). Writing the outcome is the other way to close that window, and the text now delays it. | A broker hosting the group coordinator degrades. An S2 send succeeds and the R commit times out after 10 s. Under 12.3 the attempt is F1, so X's partition is paused without the outcome written. Or, under 12.4, the database blips at the same moment, and the re-read waits for a commit that keeps failing. The member's session then expires and the partition moves. The next owner finds B, no outcome and no R, and sends a second SMS. If the outcome write had gone ahead, or the re-read had written R as soon as the database answered, the next owner would have found S1. | 12.3: "A commit that fails or times out is a failed commit, not a failure of an attempt. For the commit that keeps R, 12.4 applies. For the end-of-batch commit, W-a's seek-back applies." 12.4: "If the outcome write also fails, the commit carrying R is retried on the backoff, but a re-read of X that holds R does not wait for it: writing the outcome closes the window as well." 13.1: "until a later commit carrying R, or the outcome row, succeeds". |
| D-m2 | MINOR | 12.5, the `LogTruncationException` bullet ("seeks each named partition to the divergent offset the exception reports") | `LogTruncationException.divergentOffsets()` may leave out a partition the exception names. The client does that when the leader cannot give an end offset for the committed epoch, for example an epoch older than the log start or a re-created topic. With `auto.offset.reset=none`, that partition stays awaiting a reset, and every `poll` throws again. No branch covers it: the "above the log end" rule sits under the plain `OffsetOutOfRangeException`, and a handler that catches the subclass first never reaches it. This is m3's unbounded loop in a narrower case. | After an unclean election and a topic re-creation, partition 7's committed epoch is unknown to the new leader. `poll` throws `LogTruncationException` naming partition 7 with no divergent offset. The consumer seeks nothing, and the next `poll` throws again. Every partition of that member stops. | "A partition the exception names without a divergent offset is handled as an offset above the log end: logged at ERROR with its committed offset and the log end, and sought to the log end." Add this case to I22. |
| D-n1 | NIT | Section 11 | The items run 18, 19, **22**, 20, 21. | | Renumber, with the new item last. |
| D-n2 | NIT | 12.4 ("at offset X (its position; …)"); W-e's revoke and checkpoint bullets; 12.5 ("as `OffsetAndMetadata` allows") | Mid-batch, X is not the position: the position is past X. W-e's bullets do not mention the leader epoch that 12.5 requires on every commit. `KafkaConsumer.position()` returns no epoch, so the revoke commit needs an epoch the consumer tracks itself. That epoch comes from the last record read on the partition (`ConsumerRecord.leaderEpoch()`), or from the `OffsetAndMetadata` returned by `committed()` when nothing has been read since assignment. It is empty after a reset seek. | | "At offset X, the head"; in W-e: "with the leader epoch of 12.5"; in 12.5: "the epoch is that of the last record read on the partition, else the committed one, else none". |
| D-n3 | NIT | 13.2 ("needs to know the provider did not accept it, which 12.4's 'not known to be accepted' cannot give") | Some failures are known not to have reached the provider: `ConnectException`, `HttpConnectTimeoutException`, `UnknownHostException`, and a TLS handshake failure. These are the safe candidates for PB-79. As written, 13.2 suggests no case can be known. | | "… except a failure to connect, which is known not to have reached the provider; PB-79 starts there." |
| D-n4 | NIT | 12.2, webhook executor ("delivered after a restart") | The claim is true but narrower than the code. Any other instance's executor delivers the due `PENDING` rows without a restart (the due query is shared, with `SKIP LOCKED`). A POST that went out before the `Error` is sent again: at least once, deduplicated on `event_id`. A row whose first attempt is older than `GIVE_UP_AFTER` is dead-lettered at its next failing attempt (`RetryPolicy.exhausted`). | | "… are delivered by another instance's executor, or after a restart; a delivery in flight at the `Error` may be posted again." |

### Other checks

- **Leader epochs** can be implemented on the commits the contract makes:
  - the mid-batch R commit at X uses record X's epoch;
  - the end-of-batch commit at X+1 uses X's epoch;
  - head checkpoints use the head record's epoch;
  - the revoke commit needs the epoch the consumer tracks (D-n2).

  An epoch from a later record than the committed offset cannot cause a false divergence. A
  divergence is flagged only when the epoch's end offset lies below the position.
- **I22** is faithful to 12.5, apart from D-m2's case.
- **13.2 and PB-79** are consistent across the contract, ADR 0056 and `M6_updates.md`, and the id
  is free on every branch. The status block lists the applied changes, but not the "commit timeout
  is F1" behaviour (D-m1).
- **W-b, W-c2, the W-e checkpoints, D-c/D-d replay and the restored invariants** are not touched,
  apart from the epoch on commits, which changes no offset or metadata.

### Verdict of the delta check: still SOUND

0 BLOCKER, 0 MAJOR, 2 MINOR (D-m1, D-m2), 4 NIT (D-n1 to D-n4). All 7 MINORs and 9 NITs of draft 3
are resolved except m3 and n7, which are partly resolved. The two new MINORs come from the fixes:

- **D-m1** postpones the outcome write behind a commit, which widens 13.1's resend window during
  coordinator trouble. It is a contradiction between 12.3 and 12.4 with a one-sentence fix.
- **D-m2** leaves a rarer truncation case looping.

Neither creates a common-path resend, skip or loss. I recommend applying D-m1's text before the
code round, because it decides how the R commit is coded.
