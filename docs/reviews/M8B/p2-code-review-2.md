# P2 code review 2: the real-time channel, assignment and figures

- **Branch / commit:** `m8b/analyst-api`, HEAD `5e7e50c`, range `02cb47e..HEAD` (13 commits).
- **Specifications reviewed against:** ADR 0115 (including "Implementation notes (P2)"), ADR 0116,
  ADR 0117, the decision-owner contract.
- **Method:** reading the code, the contracts and the tests, with git and grep. Nothing was built or
  run: no Maven, no Docker, no Testcontainers. Every statement about runtime behaviour comes from
  reading the code. One fact about a dependency was checked in its bytecode: Tomcat 11.0.24's
  `WsSession.doClose` calls `fireEndpointOnClose` on the thread that closes (`javap` on the jar in
  `~/.m2`). Anything not confirmed is marked "unverified". Timings and loads are marked ASSUMED.

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 3 |
| MINOR | 12 |
| NOTE | 12 |

| # | Severity | Where | One line |
|---|---|---|---|
| 1 | MAJOR | `SessionRegistry.java:103-129`, `StompEndpoint.java:141-152` | A CONNECT that races a server-side close binds a dead session. Its counters, its pair slot and its per-user slot leak until restart |
| 2 | MAJOR | `FanOut.java:114-117`, `:171-178`, `:123-127` | One `endOffsets` call over 50 ms kills the consumer, so within 250 ms every session on the instance is closed |
| 3 | MAJOR | `VisibilityTest.java:12`, `ChangePublisher.java:42-44`, ADR 0115 `:1033-1036`, `AuthorisationMatrixTest.java:215-232` | Tests the ADR requires do not exist. Code comments and the ADR say one of them "checks it in CI" |
| 4 | MINOR | `RealtimeSession.java:109-115`, `:145-147` | `fail()` relies only on the writer to close the socket; a blocked or dead writer keeps a revoked socket open |
| 5 | MINOR | `SessionRegistry.java:163-166`, `:191-193`; `FiguresPusher.java:71`, `:90` | `byInstitution` keeps empty sets, so figures are computed forever for institutions with no session |
| 6 | MINOR | `Outbound.java:50-67`; `SessionGuard.java:227-229` | The 32 MiB instance budget is checked only every 250 ms; offers never refuse on it |
| 7 | MINOR | `StompEndpoint.java:278-285`; `SessionGuard.java:218-222` | A client heart-beat above 60 s is capped, not refused, and the session is closed at about the client's own interval |
| 8 | MINOR | `StompEndpoint.java:248-289` | CONNECT ignores `guard.open()`; a socket upgraded just before a Redis-down closure can still bind |
| 9 | MINOR | `HandshakeGate.java:61-73`; `StompEndpoint.java:136` | Admitting a handshake and counting its socket are not atomic, so the pending caps can be exceeded |
| 10 | MINOR | `SessionRegistry.java:105-114` | The per-user CONNECT rate also counts CONNECTs refused because the instance or the pairs are full |
| 11 | MINOR | `ChangePublisher.java:135-168` | The publisher's re-read sets no lock or statement timeout and is not read-only |
| 12 | MINOR | `SessionGuard.java:152-159`; `RealtimeConfiguration.java:182-183` | Database loads for revocation, the sweep and the Redis probe share one thread |
| 13 | MINOR | `PerformanceQueries.java:180-195`; `AnalystPerformanceTest.java:263` | No test covers a non-null `average_seconds_from_assignment` |
| 14 | MINOR | `HandshakeGate.java` (all) | `HandshakeGate` has no test at all |
| 15 | MINOR | ADR 0115 `:1041-1043`; `Presence.java:12-15` | Documentation left over from before the merge |
| N1-N12 | NOTE | various | See "Notes" |

## MAJOR findings

### 1. MAJOR: a CONNECT that races a close binds a dead session, and the registry leaks

- **Where:** `SessionRegistry.java:103-129` (`bind`), `:147-150` (`closed`);
  `StompEndpoint.java:141-152` (`afterConnectionClosed`), `:254-264` (`connect`).
- **What is wrong.** `bind` never checks that the session is still open (in `sockets`). The server
  closes sockets from threads other than the container thread:
  - the writer, after an ERROR (`RealtimeSession.java:91-93`);
  - the guard, on heart-beat silence (`SessionGuard.java:220`).

  Tomcat 11.0.24 runs `afterConnectionClosed` on the closing thread: `WsSession.doClose` calls
  `fireEndpointOnClose`, which I checked in the bytecode. So `afterConnectionClosed` can run while
  the container thread is still inside `handleTextMessage`, between `authenticate` and
  `registry.bind`.
  - `authenticate` can take a while: `SessionTokenValidator` may load from PostgreSQL.
  - If `closed()` runs first, it removes the socket and unbinds nothing, because the binding is
    still null. `bind()` then adds the dead session to `byUser`, `pairs` and `byInstitution`, and
    increments `authenticated`.
  - Nothing ever removes it. It is no longer in `sockets`, so the backstop, the sweep and
    `toShed` never see it.
- **Failure scenario.** A 3G client sends CONNECT at about 4.9 s. The sweep fails the socket at
  5 s ("no CONNECT within the time allowed"). The writer sends ERROR and closes, and
  `afterConnectionClosed` runs on the writer thread. Then the container thread finishes
  `connect()`. The result:
  - one of the 100 (`user`, `sid`) pair slots and one of the 500 authenticated slots are held
    until restart;
  - the user's per-user deque holds a dead session, which counts against their 5;
  - every event of that institution offers to the dead session's closed queue and increments
    `fs_realtime_overflow_total` (`FanOut.java:235-237`);
  - `guard.present` writes presence once for a user who is not connected.

  Redis-down closures (`closeAll`) and consumer stalls trigger the same race. Each leak lowers the
  instance's capacity a little more, until it refuses CONNECTs with `PAIRS_FULL`.
- **Fix.**
  - In `bind`, under the registry lock, refuse when `!sockets.containsKey(session.id())`, and have
    the caller close the session.
  - Better, give `RealtimeSession` a `closed` flag that `closed()` sets under the same lock.
  - Add a unit test: call `closed(session)` before `bind(session, …)` and assert that
    `authenticated()` and `pairs()` stay 0.

### 2. MAJOR: one slow `endOffsets` kills the fan-out consumer and closes every session

- **Where:** `FanOut.java:114-117`, `:171-178` (`measureLag`), `:123-127`;
  `SessionGuard.java:223-225`.
- **What is wrong.** Every 5 s, `measureLag` calls `consumer.endOffsets(assigned,
  Duration.ofMillis(50))` on the poll thread.
  - When the call does not complete in time, Kafka's `endOffsets(Collection, Duration)` throws
    `org.apache.kafka.common.errors.TimeoutException`, a `RuntimeException`. The call covers 48
    partitions, often on several leaders.
  - `consume()` catches it as "the consumer stopped", logs at ERROR and sets `ready = false`.
  - `healthy()` is then false. The next sweep, within 250 ms, fails every authenticated session
    with `service-unavailable`, and handshakes answer 503 until the consumer has slept 5 s and
    repositioned.
  - Lag is a metric, but its failure is treated as a consumer failure.
- **Failure scenario.** A broker GC pause or a leader election makes one ListOffsets round trip take
  more than 50 ms. Every console on the instance is closed and reconnects at once: the reconnect
  storm that point 8 sizes the per-IP limits for. The ASSUMED p99 of ListOffsets under load is
  above 50 ms often enough to matter; unverified.
- **Fix.**
  - Catch `TimeoutException` (or `RuntimeException`) inside `measureLag`, keep the last values,
    and count the miss.
  - Or compute lag from `consumer.currentLag(p)` or `metrics()`, which make no request.
  - Apply the same treatment to the unbounded `endOffsets` in `positioned()` (`:166-167`), which
    runs on the poll thread at each partition refresh.

### 3. MAJOR: the ADR's tests do not exist, and the code says one runs in CI

- **Where:** `VisibilityTest.java:12` ("its equality with listAlerts is proven in CI");
  `ChangePublisher.java:42-44` ("which the push-equals-list test checks"); ADR 0115
  `:1033-1036` ("the push-equals-list test in CI checks it"); `AuthorisationMatrixTest.java:215-232`.
- **What is wrong.** `git grep` over every `src/test`, the Python tests and `.github` finds no test
  that:
  - **push equals list** (ADR 0115 point 4, normative: "a test proves them equal"): compares
    "would be pushed" with `listAlerts queue=all` for every outcome, role and user, with chains of
    depth 2. `VisibilityTest` has one case-by-case test. The comments that cite a CI test describe
    a test that does not exist;
  - **card equality** (point 6.1): checks that the mapped `AlertSummary` of a raised alert equals
    `listAlerts`' item, with SHAP, without SHAP and under the fallback;
  - **STOMP integration in CI** (points 2, 9 and 11): drives a real Spring WebSocket stack, the
    security filter chain and Kafka. Nothing covers revocation within 5 s by push and by backstop
    end to end, re-authentication without loss, the reconnect storm, or the server leg's p99. The
    STOMP rules are tested only against `FakeSocket` (`StompEndpointTest`);
  - **the handshake through the application:**
    - `backend/` contains no reference to `openRealtimeChannel` outside the matrix and the policy.
    - The admin module's `AuthorisationMatrixTest` has a new in-band branch, but `backend/admin/pom.xml`
      does not depend on the analyst module, so the test application has no handler at
      `/api/v1/realtime`. The branch passes on any uniform status; I expect 404 for an unmapped
      path, unverified.
    - It never observes the 101 or 426 that ADR 0115 point 9 says it must;
  - **isolation per writer** (point 4): proves that "a session of institution B never receives it"
    through the writers. `AnalystDecisionsTest` checks only the offered institution; the B side is
    checked only in the `StompEndpointTest` unit test.
- **Failure scenario.** Under the paper-evidence and overclaim rules, the ADR's implementation
  note and two code comments state a verification that was never made. If `Visibility` and
  `ESCALATION_VISIBLE` drift apart, nothing fails. I compared them by hand and they agree today
  (see "Correctness against ADR 0115", point 4).
- **Fix.**
  - Add the tests; the push-equals-list test can run in `AnalystIntegrationTest` without Kafka by
    calling `ChangePublisher.message`'s routing fields and `Visibility.admits`.
  - Until they exist, remove "proven in CI" and "checks it" from `VisibilityTest.java:12`,
    `ChangePublisher.java:44` and ADR 0115 `:1035-1036`, and list the missing tests as open.

## MINOR findings

### 4. MINOR: `fail()` relies on the writer to close the socket

- **Where:** `RealtimeSession.java:145-147` (`fail`), `:109-115` (the writer's catch).
- **What is wrong.** `fail` only queues the ERROR. The socket is closed only when the writer takes
  that frame:
  - **A blocked writer.** If the writer is blocked in `sendMessage` on a stalled TCP connection,
    the close waits for the container's blocking-send timeout (Tomcat's default is 20 s, ASSUMED).
  - **A dead writer.** The writer catches only `IOException | IllegalStateException`. Any other
    `RuntimeException`, for example from `written.written(...)` (Micrometer), ends the virtual
    thread with the queue still open. From then on, revocation, expiry and overflow all call
    `fail()`, and no one ever closes the socket.
- **Failure scenario.** A revoked session on a stalled 3G link stays open past D-27's 5 s. No new
  events reach it, because offers are refused after `fail`. With a dead writer the socket never
  closes.
- **Fix.**
  - Wrap the writer's loop in `try { … } finally { closeQuietly(); }` and catch `RuntimeException`.
  - In `fail`, schedule a hard `closeQuietly()` after a short grace (for example 1 s), so the
    close never depends on the writer.

### 5. MINOR: `byInstitution` keeps empty sets, and the figures run for nobody

- **Where:** `SessionRegistry.java:163-166` (the session is removed, but the empty set never is),
  `:191-193`; `FiguresPusher.java:71-91`.
- **What is wrong.** After an institution's last local session ends, `institutions()` still
  returns it. `tick()` then:
  - recomputes `DashboardStats` every 30 s (one tenant transaction and one Redis script);
  - pushes the result to zero sessions;
  - never prunes `states`.

  ADR 0115 point 6.3 says "only while it has at least one session of that institution".
  `FiguresPusherTest.nothingIsComputedForAnInstitutionWithNoLocalSession` covers only an
  institution that never had a session.
- **Failure scenario.** Over a day, every institution that ever connected costs one transaction
  every 30 s on every instance.
- **Fix.** In `unbind`, remove the key when its set is empty (`byInstitution.computeIfPresent(id,
  (k, s) -> { s.remove(session); return s.isEmpty() ? null : s; })`), and add the test.

### 6. MINOR: the instance budget is enforced every 250 ms, never on the offer

- **Where:** `Outbound.java:56-61`; `SessionGuard.java:227-229`; `InstanceBudget.java`.
- **What is wrong.** `offer` checks only the per-session bounds. The instance total is compared
  with the budget only by the sweep, every 250 ms. Between sweeps the counted total can reach
  sessions × 256 KiB: 125 MiB at 500 sessions, against the 32 MiB that point 8's one-eighth-of-heap
  check assumes.
- **Mitigation:** frames are shared, so real heap use is far below the counted figure.
- **Fix.** Either refuse an offer that would take `queued` above the budget (and fail that session
  with overflow), or state in the ADR that the budget is a soft limit enforced every 250 ms.

### 7. MINOR: a client heart-beat above 60 s is capped, not refused

- **Where:** `StompEndpoint.java:278-285`; `SessionGuard.java:218-222`.
- **What is wrong.** A client that sends `heart-beat:120000,…` still sends every
  max(120000, 25000) = 120 s under STOMP's rule. The server caps its expectation at 60 s and closes
  after 2 × 60 s = 120 s of silence, so every beat arrives at the edge and a little latency closes
  a healthy session.
- **Fix.** Refuse a CONNECT whose `cx` or `cy` exceeds the cap (ERROR `validation`), or base
  `expectEvery` on the client's uncapped `cx`.

### 8. MINOR: CONNECT does not check that the channel is open

- **Where:** `StompEndpoint.java:248-289`; `SessionGuard.java:185-199`, `:283-287`.
- **What is wrong.** `closeAll` runs once, when Redis is found down. A socket that passed the
  handshake gate before that, but reached `afterConnectionEstablished` after `closeAll` iterated
  the registry, can still CONNECT and bind while Redis is down. That contradicts point 3: "the
  channel is closed". The consumer-stall path re-fails sessions every sweep; the Redis path does not.
- **Fix.** In `connect`, refuse with `service-unavailable` when `!guard.open()`.

### 9. MINOR: admitting a handshake and counting its socket are not atomic

- **Where:** `HandshakeGate.java:61-73` (`admit`); `StompEndpoint.java:136` (`opened`).
- **What is wrong.** The pending count is computed in `admit`, but the socket is added only in
  `afterConnectionEstablished`. Concurrent handshakes can all pass `admit` before any of them is
  counted, so the 50-per-instance and 200-per-address caps are soft.
- **Fix.** Reserve a pending slot in `admit` (a counter, keyed by a token stored in the
  attributes), and release it in `opened` or, on failure, in `afterHandshake`.

### 10. MINOR: the per-user CONNECT rate counts refused CONNECTs

- **Where:** `SessionRegistry.java:105-114`.
- **What is wrong.** `withinRate` records the attempt before the `INSTANCE_FULL` and `PAIRS_FULL`
  refusals. Point 8 counts "accepted CONNECTs".
- **Failure scenario.** In a storm, a user who retries against a full instance uses up their 10 a
  minute, and is then refused with `rate-limited` once capacity is free.
- **Fix.** Check the rate first without recording, and record the attempt only on success.

### 11. MINOR: the publisher's re-read has no bounds

- **Where:** `ChangePublisher.java:135-168`.
- **What is wrong.** Point 6.2 asks for "one short read-only tenant transaction". The re-read sets
  no `lock_timeout` or `statement_timeout`, unlike every other transaction in the module, and is not
  read-only. A slow database stalls the single publisher thread. The hand-off queue then fills and
  drops, which is accounted, but nothing bounds the wait itself.
- **Fix.** Run the same `set_config` bounds first (for example 2 s and 5 s), and mark the
  transaction read-only.

### 12. MINOR: revocation loads, the sweep and the Redis probe share one thread

- **Where:** `SessionGuard.java:86-94`, `:152-159`; `RealtimeConfiguration.java:182-183`.
- **What is wrong.**
  - Backstop checks, push re-checks, the 250 ms sweep (expiry, the CONNECT timeout, budget
    shedding, the consumer stall), the 1 s Redis probe and the presence refresh all run on one
    platform thread.
  - Gated database loads (up to 50 a second) run on that thread. At an ASSUMED 10 to 20 ms a
    load, that is half to all of its time.
  - With a slow database, the backstop period stretches past 1.5 s, the 5 s revocation bound no
    longer holds, and expiry and shedding are delayed as well. Unverified under load.
- **Fix.** Run the database-touching checks on their own small executor, so the sweep, the probe
  and expiry keep their cadence.

### 13. MINOR: the non-null `average_seconds_from_assignment` is untested

- **Where:** `PerformanceQueries.java:180-195`; `AnalystPerformanceTest.java:263`.
- **What is wrong.** The only assertion is that the value is null. These rules are untested:
  - the earliest assignment wins;
  - a self-assignment never starts the clock (`assigned_by <> analyst_id`);
  - an assignment after the decision is ignored;
  - ANOMALY entries are excluded;
  - undone decisions are excluded.

  The SQL implements each rule correctly when read against ADR 0117 point 3. A NULL subquery is
  dropped by `avg`, and `extract(epoch …)` is numeric.
- **Fix.** Add one seeded case per rule.

### 14. MINOR: `HandshakeGate` has no test

- **Where:** `HandshakeGate.java` (all); no test file mentions it.
- **What is wrong.** None of this is tested:
  - the 426 for a non-upgrade or a missing `v12.stomp`;
  - the 403 when `Origin` is absent or foreign, and the default "own origin" comparison;
  - the 503 with `Retry-After`;
  - the 429 on the per-address limits;
  - the `Handshakes` overrides of Spring's 400 and 426 answers.
- **Fix.** Add a unit test with `MockHttpServletRequest` and `ServletServerHttpRequest`.

### 15. MINOR: documentation left over from before the merge

- **Where:**
  - ADR 0115 `:1041-1043`: "frames are sent once ADR 0117's computation is merged; until then
    `/user/queue/figures` carries nothing". It is merged: `RealtimeConfiguration.java:110-126` and
    `FiguresPusher`.
  - `Presence.java:12-15`: "that writer is built separately (m8b/analyst-api) … Until it runs, the
    set does not exist". The writer is `SessionGuard.mark` on this branch.
  - Commit `f723974`'s subject says "(V84)", but the file is `V83__alert_assignments.sql`. It is
    history only and needs no change, but it should not be cited.
- **Fix.** Update the two texts.

## Notes

- **N1. Institution for writers in the `analyst` module.** `AlertDecisions`
  (`Changes.publish`), `AlertAssignments` and `DecisionWorkers` pass the tenant they ran under:
  `caller.institutionId()` or the resolver's `institution`. They do not pass the row's
  `institution_id` column. The same holds for `OwnerHoldTimeouts` (`hold.institutionId()`) and
  `PostgresSink` (`fact.institutionId()`, under `Tenant.use`). Row-level security makes the two
  equal, because the update could only touch a row of that tenant. The port's Javadoc, "read in
  the writing tenant transaction", is true in that sense. Acceptable, but point 4 says "never taken
  from a principal".
- **N2. Expiry granularity.** Expiry is enforced by the 250 ms sweep, with no clock-skew allowance.
  CONNECT accepts a token up to the decoder's skew after `exp`, and the sweep then closes it within
  250 ms. Point 3's "stops sending at once" is "within 250 ms".
- **N3. Default Origin behind a proxy.** The default "own origin" compares against
  `request.getURI()`. Behind a TLS-terminating proxy without forwarded-header handling, that is the
  internal scheme and host, so the console is refused. The default is ASSUMED in the ADR.
- **N4. Frames over 8 KiB.** No `ServletServerContainerFactoryBean` is configured. A text message
  above the container's buffer (Tomcat's 8192 characters, ASSUMED default) is closed with 1009
  before `handleTextMessage`, instead of the ERROR `validation` of point 8.
- **N5. Rate windows under the registry lock.** Once a window map holds more than 10,000 keys,
  `withinRate` (`SessionRegistry.java:267-269`) scans the whole map on every call, under the
  registry lock that `bind` and `closed` also take. Many source addresses (IPv6) can keep it above
  10,000.
- **N6. Application errors count as "Redis down".** `SafeRedis` counts any `DataAccessException`,
  including errors such as WRONGTYPE or script errors. The 1 s `PING` resets the count, so this is
  unlikely to flap the channel.
- **N7. The name cache keeps expired entries.** `StaffNames.cache` never removes them; it is
  bounded by staff count. The 500 ms bound is a `statement_timeout` only, and does not cover
  waiting for a pool connection or the runner's retries.
- **N8. Correlation ids.** ERROR and handshake problem documents carry a random `correlation_id`,
  not the request's (`StompFrames.java:87`).
- **N9. CONNECT without `accept-version`.** It is accepted, which is the STOMP 1.0 form
  (`StompEndpoint.java:249-253`).
- **N10. The overflow metric over-counts.** `fs_realtime_overflow_total` is incremented whenever an
  offer fails, including offers to a session that is already failing (`FanOut.java:235-237`).
- **N11. Dead code.**
  - `SessionRegistry.hasUser` is unused.
  - `RealtimeSession.boundNanos()` is never read.
  - `SessionRegistry.closed`'s return value ("last local session") is ignored.
  - `ChangePublisher.published()` is not exported.
- **N12. A listener exception escapes `onMessage`.** After the guard's executor is shut down,
  `versionChanged` and `sessionEnded` throw `RejectedExecutionException` out of
  `SessionStateCache.onMessage`, which catches only `IllegalArgumentException`. This happens during
  shutdown only.

## Correctness against ADR 0115

### Point 2: the handshake and CONNECT

- **Implemented:**
  - the exact path, and no SockJS (`RealtimeConfiguration.java:60-67`);
  - the subprotocol, and 426 with a problem document (`HandshakeGate.java:44-52`, `:174-207`);
  - the Origin check, 403 (`:53-56`, `:105-119`);
  - cookies ignored, and no 401 or 403 on role at the handshake;
  - CONNECT within 5 s (`SessionGuard.java:208-211`);
  - the bearer token validated by the same `JwtDecoder` bean, whose `SessionTokenValidator` calls
    `isCurrent` (`AuthAutoConfiguration.java:204-206`);
  - the three roles, so ADMIN is refused; an API key is not a JWT and is refused as unauthorized;
  - frames before CONNECT, a second CONNECT, other destinations and SEND all refused;
  - no broker, no user-destination translation, no CSRF interceptor;
  - presence only for the three roles (`SessionGuard.java:239-243`).
- **Differs:** the binding is to the claims, not to separate fields; it is equivalent. Finding 8.
- **Missing:** the integration tests (finding 3).

### Point 3: expiry, re-authentication and revocation

- **Implemented:**
  - re-authentication replaces the token version and expiry, refuses a different `sid` ("session
    changed") and a different identity (`StompEndpoint.java:311-331`);
  - expiry closes the session (N2);
  - push revocation through `addChangeListener`, called after the removal, which hands off to the
    guard thread;
  - the backstop over distinct claims, with jitter, the gated `isCurrent(claims, LoadGate)` and
    fail-closed after two periods;
  - Redis-down closure and reopening; `SafeRedis.available()` and `ping()`.
- **Differs:**
  - the backstop groups by claims, so (`user`, `sid`) pairs with different token versions are
    checked separately; harmless;
  - findings 4, 8 and 12.

### Point 4: tenant isolation and role filtering

- **Implemented:**
  - the institution is taken only from the envelope (`AlertFrames.java:121`);
  - routing is only to that institution's bound sessions (`FanOut.java:228`).
- **The role filter matches `ESCALATION_VISIBLE`.** I compared `Visibility.admits` with
  `AlertQueries.java:144-151` and `:391-395`:
  - non-escalated entries go to everyone;
  - an escalated entry goes to a rank at or above its target. The SQL's `CASE` maps only SENIOR
    and RISK_OFFICER, but V4's CHECK restricts `escalation_target_role` to those two, so the
    in-memory rank of ANALYST never arises;
  - the escalator. `ROUTING` (`ChangePublisher.java:45-51`) takes the ESCALATE with
    `escalated_to_alert_id = e.id`, where the list takes any ESCALATE on the parent by the caller.
    They agree because ESCALATE cannot be undone or overridden (`AlertDecisions.java:693-697`) and
    an escalated parent is closed.
- **Missing:** the equality test (finding 3).

### Point 5: fan-out across instances

- **Implemented:**
  - `assign()` with no group, `seekToEnd` and `position()` before ready;
  - 503 until ready (`SessionGuard.open`);
  - new partitions from the earliest offset; the lag gauge; validation against the packaged
    schemas with an invalid counter;
  - a failed or stalled consumer closes its sessions;
  - a shared frame body serialised once;
  - names resolved off the poll thread.
- **Differs:** finding 2.

### Point 6.1: raised alerts

- **Implemented:**
  - version 0, PENDING, null escalation, assignee and masked account;
  - `merchant` and `top_reasons` on `AlertRaised`, `FactCodec` and `KafkaMessages`, written only
    when `topReasons != null`;
  - `provisional` exactly when either field is absent;
  - `anomaly_score` null under the fallback.
- **Missing:** the card-equality test (finding 3). By reading, `DecisionService.topReasons` takes
  the first three of `shapTop5` as `AlertQueries.top3` does; field-level equality is unverified.

### Point 6.2: later changes

- **Implemented:** the port, the no-op `NONE`, and a writer table that matches the code:
  - DECIDED, UNDONE, ESCALATED, ESCALATION_CREATED and OVERRIDDEN in `AlertDecisions`;
  - TIMED_OUT in `OwnerHoldTimeouts`;
  - RESOLVED in `DecisionWorkers.resolve`;
  - the sink's late closes in `PostgresSink.publish`;
  - ASSIGNED in `AlertAssignments`.
- **The decision-owner contract holds:**
  - every offer runs after the runner returns, so after the commit, and never inside the owner's
    transaction;
  - `offer` is a non-blocking `ArrayBlockingQueue.offer`;
  - retries cannot offer twice: `Changes.clear()` and `applied[0] = -1` run at the start of each
    attempt, owner replays return `closedEntries = List.of()` (`DecisionOwner.java:479`), and the
    sink's conditional UPDATE returns no row the second time;
  - the sink's per-record fallback offers per committed record.
- **Also implemented:**
  - the payload carries ids only, with `assigned_analyst` replaced (`idsOnly`);
  - `event_id` is the name UUID of the alert and the re-read version;
  - the dedicated producer: idempotent, `acks=all`, `max.block.ms` 100, 8 MiB `buffer.memory`;
  - drop counters;
  - the coverage scan (`ChangeCoverageTest`).
- **Differs:** finding 11. The resolver's offers are skipped if `recorder.record` throws first
  (best effort; acceptable).

### Point 6.3: figures

- **Implemented:** once per institution, at most every 5 s; dirty on alert, on a FIGURES subscribe
  or on an end; 30 s after a disconnect; every 30 s; `as_of`.
- **Differs:**
  - a CONNECT that does not subscribe to figures does not mark the institution dirty, which is
    covered by the 30 s refresh;
  - finding 5.

### Point 7: ordering and resuming

- **Implemented:** RECEIPT is queued after the subscription is registered
  (`StompEndpoint.java:303-307`); snapshots carry `version`.

### Point 8: back-pressure and limits

- **Implemented:**
  - per-session 256 frames or 256 KiB, overflow closes, shedding of the largest queues below 75 %,
    and the start-up one-eighth check (`RealtimeSettings.checkFits`);
  - every limit in the table, with the defaults equal to the ADR's ASSUMED values;
  - the 25 s heart-beat offered; ERROR on shutdown.
- **Differs:** findings 6, 7, 9 and 10, and N4.

## SQL and migrations

- **Order after renumbering.** V82 (time zone and decision duration) and V83 (alert assignments)
  are independent. No file in `backend/`, `contracts/` or the ADRs refers to V84 or to a stale
  V83/V84 number (`git grep`); `M8B_updates.md:131` records the move.
- **V82.** The trigger `alert_decisions_timing_then_duration` sorts after V81's
  `alert_decisions_timing`, so `NEW.created_at` is already DB time, as its comment says. The
  column `decision_duration_seconds` comes from V4.
- **V83.** It has composite foreign keys to the entry and the three user ids; the
  `previous IS DISTINCT FROM assignee` CHECK; DB-time `assigned_at`; it is append-only and
  tenant-isolated; its grants are INSERT and SELECT for `fs_app`.
- **`TEAM_FROM_ASSIGNMENT`.** Correct by reading (finding 13).

## Covered and not covered by tests

### Unit tests (no Docker; can run on this machine; I did not run them)

- `OutboundTest`: the bounds, the budget count, failure, and heart-beat idle.
- `SessionRegistryTest`: the pending caps, the per-address rate, the pairs and per-user rate, and
  shedding.
- `SessionGuardTest`: the backstop closing a revoked session, the push re-check, fail-closed after
  two periods, Redis down and back, expiry and the missing CONNECT, and a stalled consumer.
- `StompEndpointTest`, against `FakeSocket`:
  - a frame before CONNECT, and CONNECT without CSRF;
  - roles, a second CONNECT, destinations;
  - re-authentication accepted and refused;
  - an oversized frame;
  - the five-session eviction;
  - presence by role;
  - institution and role routing;
  - overflow isolation.
- `AlertFramesTest`, `ChangePublisherTest` (the payload validated against the schema, the version
  re-read, drops and failures, `event_id`), `FiguresPusherTest`, `VisibilityTest`,
  `ChangeCoverageTest` (a source scan).
- In `backend/auth`: `SessionStateCacheHooksTest` (gate order, listener after removal) and
  `SafeRedisAvailabilityTest`.
- The assignment and performance unit tests: `AssignmentRulesTest`, `AssignmentRequestTest`,
  `FiguresTest`, `InstitutionDayTest` and `TeamCsvTest`.

### Docker tests (Testcontainers; CI only)

- `AnalystDecisionsTest`: an offer for each of DECIDED, UNDONE, ESCALATED, ESCALATION_CREATED,
  OVERRIDDEN and RESOLVED, with institution and version.
- `AnalystAssignmentsTest`, `AnalystPerformanceTest`, `AnalystInspectorTest`,
  `AnalystAuthorisationTest`.
- `DecisionOwnerTest`: the TIMEOUT's offer after the commit, and the sink's late-close offer.
- `PostgresAdaptersTest`, `DatabaseSecurityTest` (V82 and V83), `InstitutionTimeZoneMigrationTest`,
  `SchemaPoliciesTest`, `WebhookDeliveryTest`.
- `AuthorisationMatrixTest` (admin): its in-band branch cannot reach the handler (finding 3).
- The contract tests (Python, `contracts/tests`): `test_events.py`, `test_openapi.py`,
  `test_authorisation_matrix.py` and `test_openapi_examples.py`.

### Not covered by any test

- Push equals list, card equality, isolation per writer through the socket, and the STOMP
  integration test with Kafka: latency, revocation within 5 s end to end, re-authentication
  without loss, the reconnect storm (finding 3).
- `HandshakeGate`, and the handshake through Spring Security and the WebSocket stack (findings 3
  and 14).
- `FanOut`'s consumer loop: positioning, readiness, partition refresh, lag, failure and restart
  (finding 2).
- `StaffNames`' resolver: a miss, a full queue, a timeout.
- `KafkaChangeSender` and the 8 MiB `buffer.memory`.
- The bind-after-close race (finding 1), the writer dying or blocking (finding 4), the
  `byInstitution` cleanup (finding 5), and CONNECT while Redis is down (finding 8).
- `RealtimeConfiguration`'s wiring: conditions, lifecycle, the shutdown ERROR frames reaching
  clients.
- A non-null `average_seconds_from_assignment` (finding 13).
