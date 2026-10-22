# P2 code review 2: answers

The review is `p2-code-review-2.md` (HEAD `5e7e50c`; 0 BLOCKER, 3 MAJOR, 12 MINOR, 12 NOTE). The
answers below are made in the commit that follows it. The new unit tests run on this machine; the
new integration tests (`AnalystRealtimeTest`, the added `AnalystPerformanceTest` case) run in CI only.

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MAJOR | A CONNECT racing a close binds a dead session | Fixed: `bind` refuses (`CLOSED`) a socket the registry no longer holds, under the same lock as `closed`, and the endpoint closes it quietly. Test: `SessionRegistryTest.socketClosedWhileItsConnectWasAuthenticatedIsNeverCounted` |
| 2 | MAJOR | One slow `endOffsets` kills the consumer and closes every session | Fixed: lag comes from `currentLag`, which makes no request; an unreadable lag is counted (`lagMisses`) and never fails the consumer; `positioned()` makes no `endOffsets` call. Test: `FanOutTest` |
| 3 | MAJOR | The ADR's tests do not exist; comments claim CI coverage | Written: `AnalystRealtimeTest` in the application, with real sockets, the security chain and Redis, Kafka as `MockConsumer`: the handshake's one answer for every caller (426) and closed sub-paths; CONNECT without CSRF, routing by institution, re-authentication without loss, revocation by push within 5 s; push equals list for every entry (each status, chains of depth 2), role and user, through the publisher's re-read and the fan-out's filter. Not written, and now stated as open in ADR 0115's implementation notes and follow-ups: card equality (6.1), the load test and reconnect storm (8, 11), the backstop alone end to end, overflow and budget over real sockets. The three overclaiming texts now name the test that exists, and the admin matrix test's comment says its path is unmapped there |
| 4 | MINOR | `fail()` relies on the writer to close | Fixed: the writer closes its socket in `finally` and catches every `RuntimeException`; `fail` closes the socket after a 1 s grace whatever the writer does |
| 5 | MINOR | Empty institution sets keep the figures running | Fixed: `unbind` removes an empty set. Tests: `SessionRegistryTest.institutionLeavesWithItsLastSession`, `FiguresPusherTest.figuresStopWhenTheInstitutionsLastSessionEnds` |
| 6 | MINOR | The budget is enforced only by the sweep | Fixed: the fan-out and the figures shed after each delivery; the ADR states the residual overshoot (one record's frames) |
| 7 | MINOR | A heart-beat above 60 s is capped | Fixed: refused with ERROR `validation`. Test in `StompEndpointTest` |
| 8 | MINOR | CONNECT does not check the channel is open | Fixed: CONNECT is refused with `service-unavailable` while the guard is closed. Test in `StompEndpointTest` |
| 9 | MINOR | Admission and counting are not atomic | Fixed: `admit` reserves a pending slot, released when the socket is counted or after the CONNECT timeout. Test: `SessionRegistryTest.admittedHandshakesReserveTheirSlotUntilCountedOrLapsed` |
| 10 | MINOR | Refused CONNECTs use the user's rate | Fixed: only accepted CONNECTs are recorded. Test: `SessionRegistryTest.refusedConnectsDoNotUseTheUsersRate` |
| 11 | MINOR | The publisher's re-read is unbounded | Fixed: lock 1 s, statement 2 s, read-only |
| 12 | MINOR | Revocation loads share the sweep's thread | Fixed: re-checks and the backstop run on their own thread (`fs-realtime-checks`) |
| 13 | MINOR | Time from assignment untested when non-null | Test added: `AnalystPerformanceTest.timeFromAssignmentStartsAtTheEarliestAssignmentByAnotherUser`, one analyst per rule (earliest, self, after, ANOMALY, undone) |
| 14 | MINOR | `HandshakeGate` untested | Test added: `HandshakeGateTest` (426 twice, the default and configured origins, 503 with `Retry-After`, 429) |
| 15 | MINOR | Leftover documentation | Fixed: ADR 0115's figures note, `Presence`'s Javadoc. Commit `f723974`'s subject is history and is not cited |
| N1 | NOTE | Writers pass the tenant, not the row's column | Accepted: row-level security makes them equal |
| N2 | NOTE | Expiry within 250 ms | Accepted |
| N3 | NOTE | Default origin behind a proxy | Accepted: the default is ASSUMED in the ADR; a deployment behind a proxy configures `allowed-origins` |
| N4 | NOTE | Frames over the container buffer close with 1009 | Stated in ADR 0115's implementation notes |
| N5 | NOTE | Rate windows scanned under the lock | Accepted for now |
| N6 | NOTE | Application errors count as Redis down | Accepted: the probe resets it |
| N7 | NOTE | Name cache keeps expired entries | Accepted: bounded by staff count |
| N8 | NOTE | Random correlation ids | Accepted |
| N9 | NOTE | CONNECT without `accept-version` | Fixed: refused with `validation`; test in `StompEndpointTest` |
| N10 | NOTE | Overflow metric over-counts | Fixed: counted only when the ERROR is queued by that call |
| N11 | NOTE | Dead code | Removed `hasUser`, `boundNanos` and `closed`'s return value; `published()` is kept for the tests |
| N12 | NOTE | A listener exception escapes during shutdown | Fixed: a refused hand-off during shutdown is logged |

Found while answering, outside the review: the CI run on `5e7e50c` failed one
`DatabaseSecurityTest` case. V82's zone trigger raised `check_violation` for a missing zone before
the NOT NULL constraint could; the trigger now leaves a null zone to NOT NULL.
