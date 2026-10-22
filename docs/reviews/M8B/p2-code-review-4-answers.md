# P2 code review 4: answers

The review is `p2-code-review-4.md` (HEAD `0add0b5`; 0 BLOCKER, 0 MAJOR, 3 MINOR, 9 NOTE). The
answers are made in the commit that follows it. The analyst unit tests were run on this machine
after the change: 94 run, 0 failures.

| # | Severity | Finding | Answer |
|---|---|---|---|
| 1 | MINOR | A rolled-back refresh leaves lag 0 and a gauge; `refreshMisses` not exported | Fixed: new partitions get their lag and gauge only after the whole refresh succeeds. `fs_realtime_partition_refresh_failed_total` and `fs_realtime_lag_unreadable_total` are exported. The ADR says a retried refresh can replay more than one refresh period |
| 2 | MINOR | The ASSUMED list lacks the 2 s and 5 s bounds | Fixed: both are in ADR 0115's list, and the implementation notes say a slow link is now dropped after 2 s, not 20 s |
| 3 | MINOR | Two overclaims in code | Fixed: the fan-out's comment says "per delivering thread"; `StompEndpointTest`'s Javadoc says the integration test is written for CI and not run here |
| N | NOTE | A 101 upgrade keeping its slot is untested | Test added: `HandshakeGateTest.successfulUpgradeKeepsItsSlotUntilTheSocketIsCounted` |
| N | NOTE | No test of the snapshot sort | Accepted: the sort's failure needs sizes changing during the sort, which a unit test cannot drive reliably; the snapshot removes the comparator's dependence on live sizes by construction |
| N | NOTE | Other notes | Accepted as written |
