# P2 code review 1: the answers

The review is `p2-code-review-1.md`: 0 BLOCKER, 6 MAJOR and 17 MINOR. This file records what
answers each finding, at the commit that follows this file.

| Finding | Answer |
|---|---|
| MAJOR 1: owner errors bypass the runner's retry; timeouts answer 500 | `AlertDecisions` and `DecisionWorkers` translate the owner's `SQLException` with the template's translator, so 40001 and 40P01 reach `TenantTransactions`, which now retries both. `AlertDecisions.inTenant` maps lock and statement timeouts and lost connections to 503 with Retry-After. |
| MAJOR 2: counters and gauges not exported | `fs_hold_requests_rescheduled_total` (the outage signal, ADR 0111); function counters for the sink's dropped states, the recorder's cache and record failures, the reconciler's failures, the analyst effects FAILED and the committer's failures; the seven alarms exported under §7.4's names. |
| MAJOR 3: `changeAction` | `DECLINE_CONFIRMED`, `CUSTOMER_APPROVAL_OVERRULED` and `CONTEST_RESOLVED`, each tested in `KafkaMessagesContractTest`. |
| MAJOR 4: the resolver re-audits | It audits only a state change it made: never a replay, never DEFERRED. |
| MAJOR 5: undo after RESOLVE reopens the claimed entry | RESOLVE stamps `resolution_id` on the claimed decision's entry, and an undo whose RELEASE is refused rolls back and answers 409 `stale-alert`. |
| MAJOR 6: properties without tests | Added: P1 (concurrent decides, analyst), P5 (concurrent requests), P7a and P7 (already), P8 (effect deferred by a contest, then its label only), P8a and P9 (an operator's resolution applied by the resolver), P13 (AUTO_DECLINED), P16 (`FinalDecisionsTest`), P17 (the sequence-1 CHECK), P21 (the breaker counts once), the operator functions and `decision_alarm_figures` (`DatabaseSecurityTest`). Still without a test, carried to the next review: P3 (two concurrent effects workers), P4 against the real owner with a fast JVM clock, P6 byte for byte, P8b (V81's markers on pre-V81 data, which needs a migration-to-V80 harness), P14, P18, P19, P19a and the 53200 halving. |
| Minor 1: DB time before the lock | Read after the lock and the request-id lookup. |
| Minor 2: OVERRIDE's refusal order | Claim, then resolution, then HOLD. |
| Minor 3: halving while holding the connection | The batch is halved after its connection is closed. |
| Minor 4: a swept hold's NPE on reschedule | The member's basis is written only when both the event id and `decided_at` are present. |
| Minor 5: one failure stops a whole run | The reconciler and the recorder catch per item, count and continue. |
| Minor 6: JVM-time cutoffs | Kept, with the skew's effect stated where each cutoff is taken; the owner always judges on DB time. |
| Minor 7: `decision_states.timeout_policy` not backfilled | The contract is corrected: the table is append-only even for the migration, and the owner rows carry the policy. |
| Minor 8: the poll's two snapshots | REPEATABLE READ for that read. |
| Minor 9: the backstop answers 500 | The stale-alert problem is built after the rollback, in a fresh transaction. |
| Minor 10: AUTO keys in the client's key space | A random key, and one AUTO_* row per entry by a partial unique index (V81); the contract is corrected. |
| Minor 11: timeouts not set everywhere | Every analyst and worker transaction sets §2's bounds. |
| Minor 12: dead code | `HoldTimeout`, the sink's wrapper and the TIMEOUT request's unread fields are removed. `DecisionState.next` and the three-argument `allowed` stay for M6's tests. |
| Minor 13: CUSTOMER_YES on HOLD | DEFERRED, and the contract says so. |
| Minor 14: untranslated page text | PB-160. |
| Minor 15: SpotBugs | Run locally on all four modules: two decision findings fixed, the analyst findings fixed, and `DecisionController` added to the controllers' exclusion. |
| Minor 16: the inspector narrowed | The escalate body and a stale-alert's `current` are inspected in `AnalystDecisionsTest`. |
| Minor 17: test names | Renamed to say what they check. |
