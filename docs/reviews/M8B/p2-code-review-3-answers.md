# P2 code review 3: answers

The review is `p2-code-review-3.md` (HEAD `c54286b`; 0 BLOCKER, 1 MAJOR, 6 MINOR, 9 NOTE). The
answers below are made in the commit that follows it. The unit tests named run on this machine; the
integration tests run in CI only.

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MAJOR | `toShed` sorts on live sizes and can throw on three threads | Fixed: the sort runs on a snapshot of (session, bytes). Every thread's loop also survives one failure: the fan-out catches per record, the figures per tick, and each guard task per run (a scheduled task that throws never runs again) |
| 2 | MINOR | The hard close waits for Tomcat's 20 s blocking-send timeout | Fixed: each session sets Tomcat's `BLOCKING_SEND_TIMEOUT` user property to 2 s (ASSUMED), which bounds a blocked send and the close frame, so a failed session is closed within about 3 s whatever its link does. Not covered by a unit test: it needs the container |
| 3 | MINOR | A failed upgrade keeps its reservation | Fixed: `afterHandshake` releases it when the upgrade raised an exception or answered other than 101. Test: `HandshakeGateTest.failedUpgradeReleasesItsReservedSlot` |
| 4 | MINOR | The 5 s revocation check also passes through the backstop | Fixed: the analyst integration tests run with a 10-minute backstop, so only the push can pass it; the backstop is unit-tested in `SessionGuardTest` |
| 5 | MINOR | Overclaims remain | Fixed: `StompEndpointTest`'s Javadoc names what runs where; the ADR states the overshoot per delivering thread, and says `AnalystRealtimeTest` is written to run in CI, citing no run |
| 6 | MINOR | `positioned()` makes an unbounded broker request; its comment says none | Fixed: the request is bounded (5 s, ASSUMED) and the comment says what it does. A refresh that fails keeps the current assignment and is tried again, never failing the consumer. Test: `FanOutTest.partitionRefreshThatTimesOutKeepsTheAssignment` |
| 7 | MINOR | Three named tests do not test what they are cited for | Added: `SessionRegistryTest.connectsRefusedForFullInstanceDoNotUseTheUsersRate` (PAIRS_FULL), `reservationIsReleasedWhenItsSocketIsCountedOrItsUpgradeFails`, and the `FanOutTest` refresh case |

The notes are accepted as written, with one remark on N6: V82 has not reached `main`, and no shared
database applied the earlier text, so its checksum change affects only a developer's local
database, which is rebuilt.
