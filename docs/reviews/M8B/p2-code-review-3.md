# P2 code review 3: the answers to code review 2

M8B | `m8b/analyst-api` | `/home/marius/fs-wt/m8b`

- **Commit:** HEAD `c54286b`, range `5e7e50c..HEAD` (`3f4b67e` and the traceability commit
  `c54286b`). `docs/traceability/requirements_matrix.md` is excluded.
- **Reviewed against:** `p2-code-review-2.md`, `p2-code-review-2-answers.md`, ADR 0115.
- **Method:** reading the code and the tests, with git and grep. Some library behaviour was checked
  in bytecode with `javap` on the jars in `~/.m2`:
  - Kafka clients 4.2.1: `MockConsumer`, `ClassicKafkaConsumer.currentLag` and `OffsetFetcher.currentLag`.
  - Tomcat WebSocket 11.0.24: `WsSession.sendCloseMessage`, `WsRemoteEndpointImplBase` and
    `WsRemoteEndpointImplServer.acquireMessagePartInProgressSemaphore`, and `Constants`.
  - Spring Boot 4.1.1: the starter poms.

  Nothing was built or run: no Maven, no Docker, no Testcontainers. When I say a test "would fail
  without the fix", I worked it out from the code; none of the tests was run. The new integration
  tests have not run anywhere I can see. Anything not confirmed is marked "unverified".

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 1 |
| MINOR | 6 |
| NOTE | 9 |

| # | Severity | Where | One line |
|---|---|---|---|
| 1 | MAJOR | `SessionRegistry.java:260-277`; `FanOut.java:256`, `:111-127`; `FiguresPusher.java:111`; `SessionGuard.java:239`, `:91` | `toShed` sorts sessions by queue sizes that keep changing during the sort. TimSort can throw, which now happens on the fan-out thread and closes every session; on the figures and sweep threads it stops those schedules for good |
| 2 | MINOR | `RealtimeSession.java:159-168`, `:180-187` | The 1 s hard close uses `CloseStatus.NORMAL`, so Tomcat waits up to its 20 s blocking-send timeout behind a writer stalled on the link |
| 3 | MINOR | `SessionRegistry.java:94-95`; `HandshakeGate.java:77-83` | A reservation is never released when the upgrade fails after `admit`. Cheap malformed upgrades hold the 50 pending slots for 5 s each |
| 4 | MINOR | `AnalystRealtimeTest.java:188-194` | The "revocation by push" check also passes through the 1.5 s backstop alone, so it cannot detect a broken push path |
| 5 | MINOR | `StompEndpointTest.java:34`; ADR 0115 `:1047-1049`, `:1052-1056` | Remaining overclaims: "the same rules … run in CI", "at most one record's frames", and "In CI … runs" with no CI run cited |
| 6 | MINOR | `FanOut.java:164-173` | `positioned()` still makes an unbounded broker request (`position()` after a lazy seek). Its comment says it makes none, and its `< 0` check is dead code |
| 7 | MINOR | `SessionRegistryTest.java:139-150`, `:166-175`; `FanOutTest.java` | The named tests do not cover finding 10's scenario or the reservation's release, and the fan-out test does not cover `positioned()` |
| N1-N9 | NOTE | various | See "Notes" |

## Verification of the answers table

| # | Answer | Verdict |
|---|---|---|
| 1 | `bind` refuses `CLOSED` under the registry lock | **True.** `bind` checks `sockets.containsKey` (`SessionRegistry.java:132`) under the same monitor as `closed` (`:178`). If `closed` runs first, `bind` refuses. If `bind` runs first, `closed` → `unbind` rereads the binding and removes it. The endpoint closes quietly (`StompEndpoint.java:282`). The test would fail without the fix, because the old `bind` counted the closed socket. Residual: N2 |
| 2 | Lag from `currentLag`; misses counted; `positioned()` makes no `endOffsets` call | **Partly true.** `measureLag` (`FanOut.java:175-188`) catches per partition and can no longer fail the consumer. `positioned()` makes no `endOffsets` call, but its `position()` call is the same unbounded broker request (finding 6). `FanOutTest` covers `measureLag` only |
| 3 | Integration tests written; overclaiming texts fixed | **Partly true.** `AnalystRealtimeTest` exists, and reading it against the libraries finds nothing that must fail (see "The new integration tests"). It has not run. Its revocation check does not isolate push (finding 4). One overclaiming text remains (finding 5) |
| 4 | The writer closes in `finally`; `fail` hard-closes after 1 s "whatever the writer does" | **Partly true.** A dead writer now closes its socket: `finally` covers it, and `RuntimeException` is caught. A writer blocked on a stalled link still holds the close for up to Tomcat's 20 s (finding 2). No test |
| 5 | `unbind` removes an empty set | **True.** `computeIfPresent` runs under the registry lock (`SessionRegistry.java:195-200`). Every add (`:154-156`) is under the same lock, so no add can land in a set being removed. Both named tests would fail without the fix |
| 6 | Fan-out and figures shed after each delivery | **True, but it introduces finding 1.** The ADR's bound is overstated (finding 5) |
| 7 | Heart-beat above 60 s refused | **True** (`StompEndpoint.java:255-262`). The test would fail without the fix, because the old code capped the value and answered CONNECTED |
| 8 | CONNECT refused while the guard is closed | **True** (`StompEndpoint.java:263-268`). The test would fail without the fix. A narrow window remains (N1) |
| 9 | `admit` reserves; released when counted or after the CONNECT timeout | **True, with a gap.** A failed upgrade releases its reservation only after 5 s (finding 3). The test checks the lapse, not the release (finding 7). `HandshakeGateTest`'s 429 case depends on the reservation |
| 10 | Only accepted CONNECTs are recorded | **The code is true** (`allowed` at `:135`, `record` at `:159`). **The test does not pin it** (finding 7) |
| 11 | Re-read bounded: lock 1 s, statement 2 s, read-only | **True; see the next paragraph.** |
| 12 | Checks on `fs-realtime-checks` | **True.** `unknownSince` and `LoadGate` are touched only by `backstop()` and by `recheck()` → `check()`, and both run on `checks`. `close()` shuts down both executors, and `hand()` absorbs the rejection during shutdown (N12) |
| 13 | `timeFromAssignmentStartsAtTheEarliestAssignmentByAnotherUser` | **True; see "The new integration tests".** Each of the five rules is pinned: reverting any one makes the test fail |
| 14 | `HandshakeGateTest` | **True for the gate.** Spring's 400 and 426 answers overridden by `HandshakeGate.Handshakes` are still untested (N9) |
| 15 | Leftover documentation | **True** (ADR figures note, `Presence` Javadoc) |
| N9-N12 | `accept-version` required; overflow counted once; dead code removed; rejection logged | **True** |

Finding 11 in detail:
- **PostgreSQL accepts the statement.** `set_config('transaction_read_only', 'on', true)` after
  earlier statements in the same transaction is allowed. PostgreSQL's check hook
  (`check_transaction_read_only`) refuses only the change to read-write after the first snapshot.
  This is from PostgreSQL's source as I know it; I did not run it here.
- **Nothing is written after it.** `TenantTransactions.inTenant`
  (`audit/.../jdbc/TenantTransactions.java:62-69`) runs `set_config('fraudshield.institution_id', …)`,
  then the supplier, then commits.
- **The re-read writes nothing either.** `AlertQueries.summary` and `ROUTING` are plain SELECTs.
- **Untested here.** `AnalystRealtimeTest.pushedEqualsListedForEveryEntryRoleAndUser` runs this path
  against a real database, in CI only.

## MAJOR findings

### 1. MAJOR: `toShed` sorts on live queue sizes; TimSort can throw on the fan-out thread

- **Where:**
  - `SessionRegistry.java:264-265`:
    `largest.sort(Comparator.comparingLong(RealtimeSession::queuedBytes).reversed())`.
  - Callers: `FanOut.java:256`, inside the poll loop at `:111-113`, with no per-record catch, so
    an exception reaches `catch (RuntimeException failed)` at `:124`.
  - `FiguresPusher.java:111`, run by `scheduleWithFixedDelay(figures::tick, …)`
    (`RealtimeConfiguration.java:244`).
  - `SessionGuard.java:239`, inside `sweep`, scheduled with `scheduleWithFixedDelay`
    (`SessionGuard.java:91`).
- **What is wrong.**
  - `queuedBytes()` reads each `Outbound`'s live byte count under its lock. Writer threads drain
    those queues while the sort runs, and the resolver and figures threads add to them. So the
    comparator does not see a fixed order.
  - `List.sort` uses TimSort. With 32 or more elements it merges runs, and it can throw
    `IllegalArgumentException("Comparison method violates its general contract!")` when
    comparisons contradict each other. Below 32 elements it uses a binary insertion sort, which
    never throws.
  - The fix for code review 2's MINOR 6 moved this call onto three more threads. What happens on
    each:
    - **The fan-out thread:** the exception ends `consume()`. `ready` becomes false, and within
      250 ms the sweep fails every authenticated session with `service-unavailable`, the same
      outcome as code review 2's MAJOR 2.
    - **The figures thread:** the exception escapes `tick()`, and `scheduleWithFixedDelay` stops
      running it. Figures stop for every institution until restart.
    - **The sweep:** this existed before this commit and code review 2 missed it. The sweep stops
      for good, and with it expiry, the CONNECT deadline, heart-beat silence, the consumer-stall
      closure and shedding. A session whose token expires keeps receiving events.
- **Failure scenario.** A burst of events, or a slow network, takes the counted total above
  32 MiB while 32 or more sockets are open. On the next routed record the fan-out sorts the
  sessions while their writers are draining. If TimSort detects the inconsistency, every console
  on the instance is closed at once. That is the reconnect storm the overflow policy exists to
  avoid. I did not reproduce it. How often it happens is unverified: it needs the budget exceeded,
  32 or more sockets, and a size change during one sort lasting tens of microseconds. While over
  budget, though, the sort runs after every record.
- **Fix.**
  - In `toShed`, read each session's `queuedBytes()` once into a list of (session, bytes) pairs,
    then sort that snapshot.
  - Also catch `RuntimeException` around `route(...)` per record in `consume()`, around the body of
    `sweep()`, and around `push()` in `tick()`. One bad record or session must not stop a schedule
    or the consumer.
  - Add a unit test: 40 sessions whose `queuedBytes` changes between calls, for example through a
    fake `Outbound`. `toShed` must not throw.

## MINOR findings

### 2. MINOR: the hard close waits behind a stalled writer for Tomcat's 20 s

- **Where:** `RealtimeSession.java:159-168` (the grace thread), `:180-187` (`closeQuietly`, with
  `CloseStatus.NORMAL`).
- **What is wrong.** Checked in Tomcat 11.0.24's bytecode:
  - `WsSession.sendCloseMessage` sends a 1000 (normal) close through `sendMessageBlock` with the
    blocking-send timeout. `Constants.DEFAULT_BLOCKING_SEND_TIMEOUT` is 20000 ms.
  - Any other code uses `getAbnormalSessionCloseSendTimeout()`, which defaults to 50 ms.
  - The close frame must take `messagePartInProgress`. A writer blocked in `sendMessage` on a
    stalled TCP connection holds it.

  So the grace thread's `socket.close(NORMAL)` waits up to 20 s, the same bound the writer's own
  send already had. The answer's "whatever the writer does" holds for a dead writer, not for a
  blocked one. Queued events are still dropped by `fail`, so no new event reaches a revoked socket.
  The TCP connection, the in-flight frame and the session's slots stay for up to 20 s.
- **Fix.**
  - Close with a non-normal status in the grace path, for example `CloseStatus.SESSION_NOT_RELIABLE`
    or `POLICY_VIOLATION`, so Tomcat gives up after 50 ms and closes the connection.
  - Or set the `org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT` user property on the session to
    a few seconds.
  - Either way, correct the answer's wording.
  - Also checked: in normal operation the writer writes the ERROR within milliseconds, and the
    later hard close finds the session closed and does nothing. The ERROR is lost only when the
    writer has been blocked for more than 1 s. Under a mass closure (Redis down closing 500
    sessions), the cost is 500 virtual threads sleeping 1 s, which is cheap.

### 3. MINOR: a failed upgrade keeps its reservation for 5 s

- **Where:** `SessionRegistry.java:94-95` (reserve), `:99-108` (release only in `opened`);
  `HandshakeGate.java:77-83`, where `afterHandshake` does nothing. Its comment, "Nothing to undo:
  the socket is counted only once it is established", is no longer true.
- **What is wrong.** The gate admits on `Upgrade: websocket` and the `v12.stomp` subprotocol alone.
  Spring's handshake handler can still refuse after it, for example with no `Sec-WebSocket-Key` or
  the wrong `Sec-WebSocket-Version` (`HandshakeGate.Handshakes`' 400). The reservation then lasts
  the full CONNECT timeout.
- **Failure scenario.** One address sends 50 such requests every 5 s. That is 600 a minute, under
  the 1,000-a-minute per-address rate, and each request can close its connection at once. Every
  other handshake on the instance is answered 503 `PENDING_FULL`.
  - Before this change, the same lock-out needed 50 real sockets held open for 5 s. That was also
    possible, because the per-address cap (200) is above the instance cap (50). So this is cheaper,
    not new.
- **Fix.** In `afterHandshake`, when `exception != null` or the response status is not 101, release
  one reservation for the address the gate stored. That needs the gate to keep the address, because
  `attributes` is not passed to `afterHandshake`; a request attribute works. Then update the
  comment.

### 4. MINOR: the "revocation by push" check is also passed by the backstop alone

- **Where:** `AnalystRealtimeTest.java:188-194`. ADR 0115 `:1052-1056` and the answers' row 3 say
  this test covers "revocation by push within 5 s".
- **What is wrong.** The test context uses the default `backstopPeriod` of 1,500 ms ± 250 ms
  (`RealtimeSettings.java:62-63`; no test property overrides it). After logout, the backstop's
  `isCurrent` finds the session not current and fails it within about 1.75 s plus a database load,
  well inside the 5 s the test allows. If `addChangeListener`, the pub/sub message or `hand()` were
  broken, the test would still pass.
- **Fix.** Pick one:
  - Give this test a backstop period far above 5 s, for example `fraudshield.realtime.backstop-period=60s`
    in a nested context or a dedicated test class.
  - Assert a bound below the backstop's earliest run, which is fragile.
  - Observe the push re-check directly.

  Then keep "revocation by the backstop alone" as the open item it already is.

### 5. MINOR: remaining overclaims (paper-evidence rule)

- **`StompEndpointTest.java:34`:** "The same rules over real sockets, Kafka and Redis run in CI."
  - Kafka in the new test is `MockConsumer`.
  - Most of this class's rules do not run over real sockets: presence by role, overflow isolation,
    the five-session eviction, the oversized frame, the rules on destinations and second CONNECTs.
  - This is the same overclaim as code review 2's MAJOR 3, in a fourth place.
  - Fix: cut the sentence, or name `AnalystRealtimeTest` and what it covers.
- **ADR 0115 `:1047-1049`:** "the counted total passes 32 MiB by at most one record's frames".
  - The fan-out thread, two resolver threads (`resolverThreads` 2) and the figures thread each
    offer and then shed. The overshoot is one record's frames per delivering thread, plus whatever
    lands while `toShed` runs.
  - Fix: "by at most about one record's frames per delivering thread".
- **ADR 0115 `:1052`:** "In CI, `AnalystRealtimeTest` runs the channel …".
  - The answers say the test runs in CI only, and no CI run of `c54286b` is cited.
  - Fix: until a run is recorded, write that the test is written, and cite the run once it passes.

### 6. MINOR: `positioned()` still makes an unbounded broker request on the poll thread

- **Where:** `FanOut.java:164-173`; called from `refresh` (`:156-160`) and from start-up (`:101-103`).
- **What is wrong.**
  - **The comment is false.** "position() makes no broker request once metadata is known" does not
    hold after `seekToEnd` or `seekToBeginning`. Those seeks are lazy, and `position(p)` resolves
    them with a ListOffsets request.
  - **The call is unbounded.** `position(p)` without a timeout blocks for `default.api.timeout.ms`
    (60 s by Kafka's default, ASSUMED; `properties()` does not set it). A timeout throws, which
    ends `consume()`.
  - **The check is dead code.** `position()` never returns a negative value, so `< 0` never fires.
- **Failure scenario.** A partition is added to one of the four topics. At the next refresh, a slow
  broker blocks the poll thread. After `consumerStall` (30 s), or at the timeout, every session on
  every instance is closed. This path is rare, and partitions are not added in normal operation.
- **Fix.**
  - Use `position(p, Duration.ofMillis(…))` and treat a timeout like a lag miss: leave the partition
    to be positioned by the next `poll`, which resolves the reset itself.
  - Or, at refresh, skip `position()` and let `poll` resolve it.
  - Correct the comment, and drop the `< 0` check.

### 7. MINOR: three named tests do not test what the answers cite them for

- **`SessionRegistryTest.refusedConnectsDoNotUseTheUsersRate` (`:139-150`):**
  - It drives only `CLOSED` refusals, which return before the rate is read.
  - Code review 2's finding 10 concerned `INSTANCE_FULL` and `PAIRS_FULL`. If `record` moved back
    above those two checks, the test would still pass.
  - Fix: fill the instance, or the pairs (`pairsPerInstance`), and bind the same user past
    `connectsPerUserPerMinute` times. Then free capacity and assert that the next bind is accepted.
- **`admittedHandshakesReserveTheirSlotUntilCountedOrLapsed` (`:166-175`):** it asserts the lapse but
  never calls `opened`. The "until counted" half, and the release by address, are untested.
- **`FanOutTest`:** it calls `measureLag` directly. `consume()`, `positioned()` and `refresh()`
  remain untested; code review 2 listed this gap.

## Notes

- **N1. A narrow window after the `guard.open()` check.**
  - `connect` checks `guard.open()` before `authenticate` (`StompEndpoint.java:263-269`), and
    `authenticate` may load from PostgreSQL. A `closeAll` from `probeRedis` can run between the check
    and `bind`, and the session then binds while Redis is down. The sweep re-fails sessions for a
    stalled consumer, not for Redis.
  - Whether `SessionTokenValidator` refuses while Redis is down, which would close the window, is
    unverified.
  - Fix: check again after `bind`, or have the sweep fail authenticated sessions while `!redisOpen`.
- **N2. The close callback reads the binding before the registry lock.**
  `afterConnectionClosed` reads `session.binding()` (`StompEndpoint.java:146`) before
  `registry.closed`. A `bind` that runs between the two is unbound correctly by `closed`. But:
  - `subscribed.ended(binding)` is skipped, because the local copy is null;
  - the CONNECT thread still calls `guard.present`, so presence is written for a closed socket and
    lasts until it expires (30 s).

  Fix: have `closed` return the binding it removed.
- **N3. Reservations are matched by address, not by handshake.** A socket opened after its own
  reservation lapsed takes another pending handshake's reservation from the same address. That
  handshake is then uncounted until it opens. This is transient and bounded by the 5 s lapse.
- **N4. `currentLag` is not entirely request-free.** In the classic consumer, `OffsetFetcher.currentLag`
  sends one ListOffsets with a 0 ms timer when the lag is unknown (checked in bytecode). It never
  waits, and `measureLag` catches anything it throws. Only the comment at `FanOut.java:178-180`
  overstates.
- **N5. The test Kafka is one static consumer.** `RealtimeTestAutoConfiguration.KAFKA` is shared by
  every Spring context in the JVM, and its `close()` does nothing.
  - Today every analyst integration test uses one context (`AuthIntegrationTest`), so there is one
    fan-out thread.
  - If an analyst test ever gets its own context (a `@MockitoBean`, a property override), two
    fan-out threads poll the same consumer, and each record goes to whichever polls first. The
    realtime tests would then become flaky.
  - The lag gauge goes negative in tests, because `endOffsets` stays at 0. That is harmless.
- **N6. V82 was edited in place.** The change is correct: the `NOT NULL` check runs after BEFORE ROW
  triggers, so a null zone now reports 23502. V82 is not on `m0/bootstrap`. Any database that
  already applied the old V82, such as a developer's or a cached CI volume, fails Flyway's checksum
  validation and must be recreated.
- **N7. "Re-authentication without loss" observes continuity, not loss.**
  `AnalystRealtimeTest.java:178-186` publishes nothing while the re-auth SEND is in flight, so a
  dropped frame could not be seen. It shows the session keeps working after re-auth.
- **N8. The new test-scope starter is probably redundant.** Spring Boot 4.1.1's
  `spring-boot-starter-tomcat-runtime` already brings `tomcat-embed-websocket`. That jar provides
  the JSR-356 client (`META-INF/services/jakarta.websocket.ContainerProvider`) and the server
  container. `spring-boot-starter-websocket` adds `spring-boot-websocket`, whose only
  auto-configuration is for STOMP brokers. The `pom.xml` comment ("A WebSocket container …") is
  therefore inaccurate, and the dependency is harmless. Whether the analyst test classpath had the
  Tomcat runtime without it is unverified.
- **N9. Spring's 400 and 426 answers are still untested.** These are the ones overridden by
  `HandshakeGate.Handshakes` (code review 2, finding 14).

## The new integration tests: will they pass as written?

By reading, I found nothing that must fail. This is unverified until CI runs them. The points
checked:

### `MockConsumer` (Kafka 4.2.1, bytecode)

- **The API exists.**
  - `MockConsumer(String)` exists.
  - `updatePartitions`, `updateBeginningOffsets`, `updateEndOffsets`, `addRecord`, `position` and
    `poll` are `synchronized`. `currentLag` and `close()` are not.
  - The 11-argument `ConsumerRecord` constructor exists.
- **Positioning works.**
  - `seekToEnd` resets lazily to `endOffsets` (0).
  - `addRecord` throws unless the partition is assigned. The test publishes only after a CONNECTED,
    which requires `healthy()`, which requires the fan-out to be assigned and positioned.
- **Delivery works.**
  - `poll` returns records with `offset >= position` and advances the position to `offset + 1`.
  - One global offset counter across the four topics only increases, so every record is delivered.
  - `currentLag` returns `endOffsets - position(p)`.
- **The subclass's `wait(20)`** releases the monitor, so `publish` (synchronized, `notifyAll`) is not
  blocked.

### Wiring

- **The bean replacement works.**
  - `RealtimeTestAutoConfiguration` is ordered before `AnalystAutoConfiguration`, which imports
    `RealtimeConfiguration`.
  - The test's `realtimeFanOut` definition is therefore registered first. The production method's
    `@ConditionalOnMissingBean(FanOut.class)` skips, and no override of the same bean name occurs.
  - `realtimeLifecycle` injects the replaced `FanOut` and starts it.
- **The WebSocket client works.**
  - `StandardWebSocketClient` uses `ContainerProvider`, which `tomcat-embed-websocket` provides.
  - The Origin `http://localhost:<port>` equals the gate's own-origin, built from the `Host` the
    Tomcat client sends.

### Security and HTTP calls

- **Logout.** `bearerWithCsrf()` carries the CSRF header and cookie from `sessions.start`, as
  `SessionTest` uses them.
- **Escalation.**
  - The body and headers are those `AnalystDecisionsTest` uses, and the 201 body is the new
    entry's `AlertSummary` (`alert_id`).
  - A depth-2 chain is allowed: SENIOR → RISK_OFFICER is a higher rank, and `OwnerRules.escalate`
    answers CLAIMED for an entry already ESCALATED.
- **`listAlerts`.**
  - `queue=all`, repeated `outcome` values, and `limit=200` are allowed (`@Max(200)`).
  - The page puts `items` and an explicit `next_cursor: null`.
  - `queue=all`'s SQL and `Visibility.admits` agree by reading.

### `AnalystPerformanceTest`'s new case

- **The `alert_assignments` insert** names every NOT NULL column of V83. The fingerprint is 32 bytes.
  `previous IS DISTINCT FROM assignee` holds in every row. `assigned_at` is set by the trigger.
- **The `alert_decision_undos` insert** matches V4. It is made right after the decision, inside V81's
  5 s undo window. A CI host pausing over 5 s between the two inserts would fail it; that is
  unlikely.
- **Every user named has a row**, because `TEAM_MEMBERS` lists ACTIVE users of both roles.
- **Each rule is pinned.**
  - The 2.4 s of sleeps against a 2.0 s threshold separate "earliest" (about 2.4 s) from "latest"
    (about 1.2 s).
  - Reverting any one rule in `TEAM_FROM_ASSIGNMENT` turns a null into a value, or changes the
    average.
