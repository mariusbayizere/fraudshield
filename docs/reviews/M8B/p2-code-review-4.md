# P2 code review 4: the answers to code review 3

- **Scope:** `git diff c54286b..HEAD` on `m8b/analyst-api` (HEAD `0add0b5`), without
  `docs/traceability/requirements_matrix.md`. It answers `p2-code-review-3.md`; the answers are
  `p2-code-review-3-answers.md`.
- **Method:** reading, `git`, `grep` and `javap -c` on the jars in `~/.m2` (Spring Framework 7.0.9,
  Tomcat 11.0.24, Kafka clients 4.2.1). No Maven, Docker or Testcontainers run: every "the test
  passes" below is by reasoning, not a run.

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 0 |
| MINOR | 3 |
| NOTE | 9 |

| # | Severity | Where | Finding |
|---|---|---|---|
| 1 | MINOR | `FanOut.java:156`, `:174-181`, `:193-194`; `RealtimeConfiguration.java:150-156` | A refresh that fails after positioning some new partitions rolls back the assignment, but leaves their lag at 0 and their gauge registered. Nothing exports `refreshMisses`. A partition whose refresh keeps failing is never consumed, and the metrics report it as up to date |
| 2 | MINOR | ADR 0115 open question 3 (`:1108-1121`), `:323-324`; `StompEndpoint.java:57-61`; `FanOut.java:48-49` | Two new ASSUMED values (2 s blocking send, 5 s position timeout) are missing from the ADR's "complete list". The ADR does not state that a slow link is now dropped after 2 s rather than 20 s. "At most 5 minutes of data" no longer holds after a failed refresh |
| 3 | MINOR | `FanOut.java:280-281`; `StompEndpointTest.java:34-35` | Review 3's MINOR 5 overclaims are fixed in the ADR but remain in code. The comment says "at most one record's frames", and the Javadoc says the test "runs in CI" with no run cited |

## Verification of the answers table

| # | Answer | Verdict |
|---|---|---|
| 1 | Snapshot sort; per-record, per-tick and per-run catches | **Fix present and correct.** `toShed` (`SessionRegistry.java:266-280`) reads each `queuedBytes()` once into `Sized` and sorts on those fixed values, so the comparator is consistent and TimSort cannot throw on it. The fan-out catches per record (`FanOut.java:117-123`). `tick()` catches the whole tick (`FiguresPusher.java:69-75`). `sweep`, `probeRedis`, `refreshPresence` and the backstop are wrapped in `guarded` (`SessionGuard.java:91-96`, `:155`), so no scheduled task dies on an exception. The backstop still reschedules in `finally`. The answer names no test, and none was added for the snapshot (review 3 asked for one; NOTE 3) |
| 2 | Tomcat `BLOCKING_SEND_TIMEOUT` user property, 2 s | **Fix present; it works by bytecode, not by a run.** Details below. "Within about 3 s" (1 s grace plus at most 2 s waiting for the send lock) is consistent with the bytecode. It holds because every close in the module uses `CloseStatus.NORMAL` (`RealtimeSession.java:85`, `:93`, `:183`). Other close codes use Tomcat's 50 ms abnormal-close timeout, not this property. "Not covered by a unit test" is stated honestly |
| 3 | `afterHandshake` releases on an exception or a non-101 status | **Fix present and correct for Spring 7.0.9 with Tomcat 11.0.24** (details below). The successful upgrade's status is 101 when `afterHandshake` runs, and `opened` runs later, so a success is never released and never counted twice. Every failure after admission reaches `afterHandshake` with an exception or a non-101 status. `HandshakeGateTest.failedUpgradeReleasesItsReservedSlot` passes as written and fails without the fix (the last handshake gets 503). It does not assert that a 101 is *not* released (NOTE 1) |
| 4 | 10-minute backstop in the analyst integration tests | **Fix present.** It is inherited by all seven `AnalystIntegrationTest` subclasses. The context-cache key is consistent (details below). The push is now the only path that can close the socket within 5 s. No other analyst IT depends on the backstop (NOTE 7) |
| 5 | Overclaims | **Fixed in the ADR** (`:1047-1050`, `:1054-1056`). **Partly in code:** `FanOut.java:280-281` keeps "at most one record's frames", and `StompEndpointTest`'s new Javadoc says the test "runs in CI only" with no run cited (MINOR 3) |
| 6 | `position(p, 5 s)`; a failed refresh keeps the assignment | **Fix present and correct.** `positioned` uses `position(p, POSITION_TIMEOUT)` (`FanOut.java:190`), and the comment is now true. `refresh` catches, counts and calls `consumer.assign(assigned)`. Kafka 4.2.1's `SubscriptionState.assignFromUser` keeps the existing `TopicPartitionState` (and so the position) of partitions that stay assigned. The rollback therefore loses no position on the old partitions. The rollback leaves the lag map and gauges behind (MINOR 1). The `< 0` check stays, with an honest comment. `FanOutTest.partitionRefreshThatTimesOutKeepsTheAssignment` passes and fails without the fix, but it covers only a failure before `assign(now)` (NOTE 4) |
| 7 | Three tests added | **Present, and they pass by reasoning** (details below). `connectsRefusedForFullInstanceDoNotUseTheUsersRate` drives `PAIRS_FULL` only; its comment also names `INSTANCE_FULL` (NOTE 2). `reservationIsReleasedWhenItsSocketIsCountedOrItsUpgradeFails` covers `opened`'s release and `released` |

The remark on N6 (V82 not on `main`) was not checked; unverified.

### Answer 2: what the bytecode shows

- `WsRemoteEndpointImplBase.getBlockingSendTimeout()` reads
  `wsSession.getUserProperties().get("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT")`. It
  uses the value only if it is a `java.lang.Long`, otherwise 20000. `BLOCKING_SEND_MILLIS` is
  declared as `Long` (`StompEndpoint.java:61`), so it is used.
- `sendMessageBlock(byte, ByteBuffer, boolean)` (text and binary blocking sends) passes
  `getBlockingSendTimeout()` on. The timeout bounds both the `messagePartInProgress` acquire
  (`acquireMessagePartInProgressSemaphore(…, timeoutExpiry)`; on failure, `doClose` and a
  `SocketTimeoutException`) and the write.
- `WsSession.sendCloseMessage` calls the same overload for `NORMAL_CLOSURE`. For every other code
  it calls `getAbnormalSessionCloseSendTimeout()` (default 50 ms).
- `WsSession`'s `userProperties` is a per-session `ConcurrentHashMap`, filled with `putAll` from
  the endpoint config. The `put` does not leak into other sessions.
- Spring: `StandardWebSocketSession extends AbstractWebSocketSession<jakarta.websocket.Session>`,
  which `implements NativeWebSocketSession`. `StandardWebSocketHandlerAdapter.onOpen` calls
  `initializeNativeSession` before `afterConnectionEstablished`. The handler is registered without
  session decorators (`RealtimeConfiguration.java:61-67`), so the `instanceof` chain matches the
  real session. The unit tests' `FakeSocket` skips the branch.
- `jakarta.websocket-api` `provided`: its version (2.2.0) is managed by Spring Boot 4.1.1's BOM.
  At run time the classes come from `tomcat-embed-websocket`, which bundles `jakarta/websocket/*`
  and reaches the application through `backend/ingest`'s `spring-boot-starter-websocket`. On the
  test class path the API classes appear twice (the API jar and Tomcat's). No duplicate-class
  enforcer rule is configured, so this is harmless (unverified beyond `grep` of the POMs).

### Answer 3: when `afterHandshake` runs, and with what status

- `WebSocketHttpRequestHandler.handleRequest` (7.0.9, bytecode) does three things in order:
  - `applyBeforeHandshake`. When an interceptor refuses, it calls `afterHandshake` only on the
    interceptors before it. The gate is last, after Spring's `OriginHandshakeInterceptor` from
    `setAllowedOriginPatterns("*")`, so its own refusals never reach its `afterHandshake`.
  - `doHandshake`, ignoring the returned boolean.
  - `applyAfterHandshake(…, null)`. Any exception becomes a `HandshakeFailureException`, passed to
    `applyAfterHandshake(…, failure)`.
- **Success.** Tomcat's `UpgradeUtil.doUpgrade` calls `HttpServletRequest.upgrade`, and
  `org.apache.catalina.connector.Request.upgrade` calls `response.setStatus(101)`. This happens
  inside `doHandshake`, so `getStatus()` is 101 when `afterHandshake` runs. `onOpen`, and with it
  `registry.opened`, runs only after the servlet returns, when the upgrade handler is
  initialised. The gate therefore never releases the slot of a successful upgrade.
- **Failures:**
  - Missing key or wrong version: `doUpgrade`'s `sendError(400)`/`setStatus(426)`/`sendError(403)`,
    or the `Handshakes` overrides' 426.
  - A rejected subprotocol or method: Spring sets the status and returns false.
  - An exception: the release happens with the exception.
- No response wrapper in the main code could hide the status (`grep` for `ResponseWrapper` and
  `ContentCaching`: none). Spring Security's wrappers delegate `getStatus`.
- Double release would need an interceptor's `afterHandshake` to throw on the success path, which
  makes Spring call `applyAfterHandshake` again. Neither the gate's nor Spring's origin
  interceptor throws there.

### Answer 4: the test context

- Spring collects `@DynamicPropertySource` methods from the class hierarchy. All seven analyst ITs
  extend `AnalystIntegrationTest` and declare no property source, `@TestPropertySource`,
  `@MockitoBean` or `@DirtiesContext` of their own (`grep`). So they carry the same two methods,
  `AuthIntegrationTest.properties` and `AnalystIntegrationTest.realtime`, and share one context.
  The names differ, so neither hides the other.
- No other module's test extends `AnalystIntegrationTest`.

### Answer 7 and the new unit tests: will they pass?

- **`connectsRefusedForFullInstanceDoNotUseTheUsersRate`:**
  - It binds 100 holders, each a distinct pair. `sessionsPerInstance` is 500, so this hits
    `PAIRS_FULL` and not `INSTANCE_FULL`.
  - It then makes 20 binds for one user. Each passes the rate check (nothing recorded) and gets
    `PAIRS_FULL`.
  - `closed(holder)` frees one pair, and the 21st bind is accepted.
  - If `record` moved above the refusals, binds 11 to 20 would return `RATE_LIMITED`, and the test
    would fail. Passes.
- **`reservationIsReleasedWhenItsSocketIsCountedOrItsUpgradeFails`:**
  - It admits 50 distinct addresses and opens them. `opened` releases each reservation, but each
    socket is still pending.
  - Binding all 50 (50 pairs, under 100) clears the pending count, so 10.4.5.1 is admitted.
  - 49 more admissions fill 50, so `PENDING_FULL` follows.
  - `released("10.4.6.1")` frees one slot. Passes.
- **`FanOutTest.partitionRefreshThatTimesOutKeepsTheAssignment`:** It uses Kafka's unchecked
  `TimeoutException` (imported from `org.apache.kafka.common.errors`), so Mockito accepts it.
  `partitionsFor(anyString())` matches the one-argument overload that `partitions()` calls. The
  test verifies `assign(assigned)` once. Passes.
- **`HandshakeGateTest.failedUpgradeReleasesItsReservedSlot`:**
  - `pendingPerAddress` (200) and `handshakesPerAddressPerMinute` (1,000) do not bind before
    `pendingPerInstance` (50), so 50 handshakes from one address get 101 and the 51st gets 503.
  - `afterHandshake` with status 400 releases one slot, and the next handshake gets 101. Passes.

## MINOR findings

### 1. MINOR: a refresh rolled back after partial positioning leaves the partitions' lag at 0

- **Where:** `FanOut.java:174-181` (the rollback), `:193-194` (`lag.put(p, 0L)`,
  `assignedHook.accept(p)`, run per positioned partition before the failure);
  `RealtimeConfiguration.java:150-156` (the gauge reads `lag().getOrDefault(p, 0L)`);
  `refreshMisses` (`:46`, `:212`) has no meter.
- **What is wrong.**
  - Suppose two partitions are added and the second's `position` times out. The first has
    already been given lag 0 and a registered `fs_realtime_consumer_lag` gauge. The rollback
    unassigns it. `measureLag` iterates only `assigned`, so nothing updates that entry again.
  - The only trace of the miss is a WARN log every 5 minutes. `refreshMisses` is read only by the
    test.
- **Failure scenario.**
  - A new partition's leader cannot answer ListOffsets for a while, but the existing partitions
    poll normally.
  - Every refresh fails and rolls back. That partition's alerts are never pushed, and consoles
    see them only at the 60 s re-read.
  - The lag gauge reports 0 for the partition, if a sibling was positioned first or an earlier
    attempt got that far. No metric shows the misses.
  - Before this change the same condition killed the consumer, which was loud. Now it is quiet.
  - When the refresh finally succeeds, `seekToBeginning` replays everything since the partition
    was created. That is (misses + 1) × 5 minutes, not the "at most one refresh period" of the
    Javadoc at `:156`.
- **Fix:**
  - In the catch, remove `lag` entries for partitions not in `assigned`. Better, put lag 0 only
    after the whole refresh succeeds.
  - Export `refreshMisses` as a `FunctionCounter`, as `invalid` is.
  - Correct the Javadoc (and ADR `:323-324`) to "one refresh period per failed refresh, plus one".

### 2. MINOR: the ADR's list of ASSUMED numbers is no longer complete

- **Where:** ADR 0115 open question 3 (`:1108-1121`) says "The complete list". The code adds
  `BLOCKING_SEND_MILLIS = 2_000L` "(ASSUMED)" (`StompEndpoint.java:60-61`) and
  `POSITION_TIMEOUT = 5 s` "(ASSUMED)" (`FanOut.java:48-49`). Neither appears in the ADR (`grep`).
- **What is wrong.**
  - The 2 s bound applies to every blocking send, not only the close. A console on a slow link
    that cannot take one frame within 2 s now has its session aborted by Tomcat. Before, it had
    20 s.
  - That trade is a behaviour change the ADR does not state. The answers present it only as a
    bound on the close.
  - The "at most 5 minutes of data" at `:323-324` is also no longer true (MINOR 1).
- **Fix:** add both values to the list in open question 3. State in point 8 that a blocking send
  (frame or close) is bounded at 2 s, so a link slower than one frame in 2 s is closed. Correct
  `:323-324`.

### 3. MINOR: two overclaims remain in code (paper-evidence rule)

- **`FanOut.java:280-281`:** "so the counted total passes the budget by at most one record's
  frames". This is the claim review 3's MINOR 5 corrected in the ADR (one record's frames *per
  delivering thread*). The code comment still has the old wording.
- **`StompEndpointTest.java:34-35`:** "AnalystRealtimeTest runs a subset … (it needs Docker, so it
  runs in CI only)". This says it runs, with no CI run cited. The ADR now says "is written to run".
- **Fix:** use the ADR's wording in both places. Cite the CI run once one is recorded.

## Notes

1. **`HandshakeGateTest` has no case for the success path.** An `afterHandshake` that always
   released would pass `failedUpgradeReleasesItsReservedSlot`. Add an assertion that a 101
   response releases nothing: fill 50, call `afterHandshake` with status 101, and the next
   handshake must still get 503.
2. **`SessionRegistryTest.java:180`:** the comment says "the INSTANCE_FULL and PAIRS_FULL
   refusals", but the test reaches only `PAIRS_FULL`. The answers row says "(PAIRS_FULL)", which is
   accurate. Correct the comment, or add the `INSTANCE_FULL` case with a settings object whose
   `sessionsPerInstance` is below `pairsPerInstance`.
3. **No regression test for the snapshot sort.** Review 3 asked for 40 sessions with changing
   sizes. By reading, the fix cannot throw, but nothing guards it against a revert.
4. **`FanOutTest`'s refresh case fails before `assign(now)`.** The rollback after a partially
   applied `assign`/`seekToBeginning`/`position` is untested; `verify(consumer).assign(assigned)`
   is trivially satisfied here. A `MockConsumer` with two new partitions, one of which throws on
   `position`, would cover MINOR 1 as well.
5. **`FanOut.java:121`:** a routing exception is counted in `invalid`, which is exported as
   `fs_realtime_invalid_total`. Server faults and malformed records then share one metric. A
   separate counter would keep them apart.
6. **`guarded` works per run, not per session** (`SessionGuard.java:133-141`). A session whose
   `fail`/`closeQuietly` threw on every run would stop the sweep for the sessions after it in
   `registry.all()`'s order. That order is stable for a fixed key set. I found no path that
   throws there, so this is only a note. `FiguresPusher` is not affected: `computedNanos` is set
   before `push`, so a failing institution is skipped on the next tick.
7. **The shared 10-minute backstop.**
   - All seven analyst ITs now run with it, and the UNKNOWN fail-closed limit (two periods)
     becomes 20 minutes there.
   - No current analyst IT depends on either: only `AnalystRealtimeTest` touches revocation, and
     `AnalystDecisionsTest`'s "backstop" is a different mechanism.
   - The open item "revocation by the backstop alone end to end" (ADR `:1061`) will need its own
     context. The comment "Every analyst integration test shares one context" will then be untrue.
8. **`released` pops the newest reservation for the address** (`pollLast`), and `opened` pops the
   oldest. The count stays right. If a refusal body took longer than `connectTimeout` to flush,
   the slot would already have lapsed, and `released` would free another reservation of the
   *same* address. That is harmless to other callers.
9. **The answers' "The unit tests named run on this machine"** cites no run or log. Whether they
   were run is unverified; this review checked them by reading only. A shutdown `interrupt()`
   during `refresh` is also counted and logged as a refresh miss, which is cosmetic.
