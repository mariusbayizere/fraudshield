# Review 1: the analyst decision commit and undo contract (draft 1)

Subject: `docs/architecture/analyst-decision-commit-contract.md` at `a757ac8` (branch `m8b/analyst-api`).
Reviewer: independent; wrote none of the work. Method: reading only. No build, test or container
was run. Line numbers are at `a757ac8`. OpenAPI lines are from the committed file, because the
working tree has uncommitted OpenAPI edits that do not touch the four operations.

## Verdict: UNSOUND

6 MAJOR, 11 MINOR.

- Two interleavings let **both the analyst and the timeout decide** one held transaction (1, 2).
- The hold claim is **not idempotent** under retries the code already performs, so P10 is false (3).
- The committer and undo can **deadlock** through the audit chain head (4).
- Override effects **cannot be derived** from what the schema stores (5). They can also be **lost
  or doubled** through the sequence/CAS rules they inherit (6).

The core shape is right: take/claimDue atomic on one Redis key, commit/undo exclusive under one
lock, effects keyed by derived ids. The problems are at the edges between PostgreSQL commit and
Redis/spool side effects, which are where M6's earlier rounds failed too.

## Findings

### 1. MAJOR: D2(e)'s put-back can hand a claimed hold back to the poller after T committed

**Rule:** D2(e), P4.

**Interleaving:**
1. An analyst decides a MEDIUM entry. D2 `take` returns the hold, and T inserts the decision with
   `hold_claimed = true`.
2. T's `COMMIT` reaches the server and commits. The connection then drops before the
   acknowledgement arrives (pod network blip, or a pool socket timeout).
3. The application sees an exception, so per D2(e) it calls `schedule` and puts the hold back.
4. The client retries with the same key. D1(a) finds the stored row and answers 202.
5. At the deadline the leader's `claimDue` takes the restored hold, and `timeOut` records
   TIMEOUT_RELEASE (or DECLINE).
6. The committer commits the analyst decision. E1 finds the state neither HOLD nor its own event,
   so the outcome is SKIPPED `transaction_not_held`.

Result: the alert says CONFIRMED_FRAUD and the analyst got 202, but the money was released by the
timeout. D2(f) does not help, because it guards only the sweep. The poller never consults
PostgreSQL. The same happens if put-back is wired to run after any failure of a TenantTransactions
attempt, including a failure from which the transaction actually committed.

**Evidence:**
- Put-back has no check of the commit outcome: contract §4 D2(e).
- The poller times out whatever it claims, after checking only the Redis/PG latest state:
  `HoldTimeoutService.java:76`, `:117-133`.
- The CLAIM script: `RedisHoldSchedule.java:41-51`.

**Fix:** Never put a hold back from the request path. Use one of these instead:
- (a) Drop D2(e). An orphaned take, where the decision does not exist, is then timed out by the
  sweep, which D2(f) does not exclude because no live decision exists.
- (b) Let `take` move the hold into a claimed record (see finding 3). A reconciler restores it
  only after a fresh PostgreSQL read shows no decision with that idempotency key, at least one
  statement timeout after the take.

### 2. MAJOR: the sweep can time out a hold whose claiming T has not committed yet

**Rule:** D2(f), D2(g), P4.

D2(f) excludes holds with a *live decision*. The sweep's read sees only committed rows. Nothing
bounds the time between D1(f)'s deadline check and T's commit.

**Interleaving:**
1. At DB time deadline−50 ms, T passes (f) and `take` succeeds (g).
2. T then waits. It might wait on the `alert_entry:` advisory lock, or on an audit chain-head row
   lock (`V8__audit_log.sql:88-89`, held until commit by every transaction that wrote an audit
   row on that partition, including a 100-decision committer batch). It might also stall on I/O.
   No `lock_timeout` or `statement_timeout` is specified.
3. The leader's sweep runs with cutoff `clock.instant() − 10 s`, using the **leader's JVM clock**,
   not DB time (`HoldTimeoutService.java:102`). The hold has no later state, and T's decision is
   invisible, so the sweep calls `timeOut`. Its `cancel` returns false because the hold was taken,
   but the result is ignored (`HoldTimeoutService.java:103-104`).
4. T commits. Both decided.

With leader clock skew s, step 2 needs only about 10 s − s.

D2(g)'s claim that skew "only decides which of the analyst and the timeout wins" holds for
`claimDue` against `take`. It does not hold for the sweep, which never touches Redis atomically
with T. §2's "every grace and deadline comparison uses DB time" is also not true of the sweep or
the poller.

**Evidence:**
- `JdbcOverdueHolds.java:28-40` (a read-only, plain-snapshot query).
- `HoldTimeoutService.java:97-109`.
- `BackgroundTasks.java:35,84`.

**Fix:**
- Compute the sweep's cutoff in SQL with DB time (`clock_timestamp() - interval '10 s'`).
- For each candidate, re-check in a tenant transaction that first takes `FOR SHARE` on every
  `alert_queue_entries` row of that transaction and tier MEDIUM (fs_app has UPDATE, so this is
  permitted, V11:30-33). T holds `FOR UPDATE` from D1(b) to commit, so the re-check serialises
  with T.
- Run the D2(f) `NOT EXISTS` inside that transaction, and record the timeout only if it still
  holds.
- Any T that starts after this fails D1(f), because its DB time is past the deadline.
- `JdbcOverdueHolds` is currently a read-only connection, so this is a change to M6's sweep beyond
  the one D2(f) admits.

### 3. MAJOR: `take` is not idempotent, so ordinary retries lock the analyst out and P10 is false

**Rule:** D2(c), D2(d), P10, D1(a).

Three existing retry paths re-run `take` after the first one has already removed the hold:

- **(a) Retry inside TenantTransactions.** It re-runs the whole work on SQLSTATE 40001
  (`audit/jdbc/TenantTransactions.java:58-73`). V8's chain trigger raises 40001 whenever a
  transaction that started earlier writes behind a later one on the same partition
  (`V8__audit_log.sql:91-94`). D1's audit write at (h) is therefore a routine 40001 source. That
  class's own Javadoc forbids side effects outside the transaction except after-commit callbacks
  (`TenantTransactions.java:19-22`), and D1(g) violates that. Attempt 2's `take` returns nothing,
  so the answer is 409 `review-deadline-passed` before the deadline. The hold is gone, so the
  transaction waits for the sweep, which comes 10-15 s after the deadline (D-18's ±500 ms is
  missed).
- **(b) A Redis response timeout after the Lua script ran.** D2(d) returns 503 and says "changes
  nothing". The hold is in fact removed. The client's retry with the same key then gets 409
  `review-deadline-passed`. P10 is false.
- **(c) A client retry after any 5xx** following a successful take. This is the same as (b).

None of these decides twice: the sweep decides. But each makes a stated property false (P10,
D2(c)'s "either the poller took it, or it was never scheduled"), and each refuses a valid
analyst decision.

**Fix:**
- Make `take` idempotent per idempotency key. The Lua script moves the member into
  `fs:holds:claimed` with field `<inst>|<txn>` and value `key|member`, with an expiry past
  deadline + G.
- A `take` carrying the same key returns the same hold again.
- The sweep (finding 2) treats claimed records with no matching decision as orphans.
- This also replaces D2(e).
- Say explicitly how D1 runs inside TenantTransactions' retry loop.

### 4. MAJOR: committer and undo deadlock through the audit chain head

**Rule:** §3.4 ("lock order everywhere is entry, then decision, so undo and decide cannot
deadlock"), D3, D4(b).

The audit row lock is a third resource the contract does not order. `JdbcAuditLog` picks the
partition by thread id mod 64, or a fixed partition when configured (`JdbcAuditLog.java:80-84`).
The chain trigger locks that partition's head `FOR UPDATE` until the transaction ends
(`V8__audit_log.sql:88-89`). Advisory xact locks taken inside a savepoint that is *released* also
stay held until the transaction ends.

**Interleaving** (committer C and undo U share partition p):
1. U, undoing decision b at DB time < `undo_until(b)`, holds the entry row, the `alert_entry` lock
   and the `alert_decision:b` lock (undo row inserted). It has not yet written its audit row.
2. C started its batch at DB time ≥ `undo_until(b)`. U's undo row is not committed, so C selected
   b among its 100.
3. C committed decision a and wrote a's COMMITTED audit row, so it holds head p. It now inserts
   b's commit row, and the trigger waits on `alert_decision:b`, held by U.
4. U writes its UNDO audit row and waits on head p, held by C. Deadlock.

PostgreSQL aborts one side with 40P01, which TenantTransactions does not retry
(`TenantTransactions.java:69`). If U is chosen, the undo fails with a 500. The analyst's retry is
past `undo_until`, so the undo is lost. State stays consistent, but the stated property is false.
The window is small but recurs under load. With a configured fixed partition (`AuditProperties`)
every pair shares p.

Long committer batches also hold head p for the whole batch. That makes the finding 2 and finding
3(a) windows wider, and makes the batch itself prone to 40001 restarts.

**Fix:**
- Commit one decision per transaction in the committer, so it holds at most one decision lock
  and never waits for a second one while holding a head. Alternatively, skip any decision whose
  lock is held, using `pg_try_advisory_xact_lock` before the insert.
- State the full lock order: entry row, then entry advisory, then decision advisory, then audit
  head.
- Treat 40P01 as retryable where the work is retry-safe.

### 5. MAJOR: an override's direction is not stored, so D5 cannot run its effects

**Rule:** D6(e), D6(f), D5(a), D5(c), P3.

`OverrideRequest` carries `new_decision` (CONFIRM_FRAUD or MARK_LEGITIMATE; OpenAPI committed
:3413-3420). `alert_decisions` stores only `decision = 'SENIOR_OVERRIDE'` and the comment
(`V4:42-62`). §3 adds no column. `DecisionRecord` (:3422-3434) cannot show the direction either.

D5 runs effects from the database on any instance after a crash (D5(e)). The worker therefore
cannot know whether to write a FRAUD or a LEGITIMATE label, which transition to apply, or whether
E2 applies. The override's effects are unimplementable, or lost after a crash if the
implementation keeps them in memory.

Also, `supersedes_label_id`:
- is in the Kafka label schema (`contracts/kafka/schemas/label.schema.json:63-72`);
- is not in `DecisionEvent.LabelRecorded` (`DecisionEvent.java:224-232`);
- is not emitted by `KafkaMessages.label` (`KafkaMessages.java:527-536`);
- has no column in `label_events` (`V5:143-153`, which has `source_reference`).

D6(f) needs all four changes, and the contract does not list them.

**Fix:**
- Add `override_decision text CHECK (... IN ('CONFIRM_FRAUD','MARK_LEGITIMATE'))` to §3, NOT NULL
  exactly when `decision = 'SENIOR_OVERRIDE'`.
- Add it to `DecisionRecord`.
- List the LabelRecorded, KafkaMessages and V5 changes, or drop `supersedes_label_id` from D6(f).

### 6. MAJOR: override transitions can be silently skipped or emitted twice with one sequence

**Rule:** D5(b), D5(d) E1, D6(f) ("where `DecisionState` allows it"), P3.

E1 for an override computes `latest.next(...)`, with sequence = latest + 1
(`DecisionState.java:119-122`). `latest` is Redis first, and **PostgreSQL when Redis lacks the
key** (`RedisDecisionStates.java:79-96`). PostgreSQL lags, because each instance has its own
spool and nothing orders two instances' spools (`decision-fact-ordering.md` W1, N2). D5(b) waits
only for the original's *effects row*, which says the event was appended to the original
instance's spool, not that anyone can read it.

**Interleaving A (lost):**
1. The analyst's E1 runs on instance A: spool append (seq 2, DECLINE), Redis save, effects row.
2. Redis fails over empty, the case V62/ADR 0067 exist for. A's PostgreSQL drainer is lagging.
3. The override's E1 runs on B. `latest` is PostgreSQL seq 1 HOLD, and SENIOR_OVERRIDE from HOLD
   is not allowed (`DecisionState.java:131-143`).
4. The worker records "not allowed", writes the effects row, and the override's transition never
   happens.

**Interleaving B (doubled):**
1. Both the override (DECLINE→APPROVE) and the customer's "yes, this was me"
   (`DecisionTransitionService.java:145-170`) read seq 2.
2. Both spool a seq-3 event with different event ids. Recording happens before the CAS, and the
   CAS result is ignored (`DecisionTransitionService.java:127-128`,
   `RedisDecisionStates.java:62-76`).
3. PostgreSQL keeps whichever drains first and drops the other silently. The PK is
   `(institution_id, transaction_id, decision_sequence)` (`V3:107`) with `ON CONFLICT DO NOTHING`
   (`PostgresSink.java:88-93`).
4. Kafka and the webhook get two seq-3 states. The override's effects row says done either way.

**Fix:**
- Make the override's transition a function of durable data. The effects row stores the state
  and sequence each effect produced, so the override's predecessor is read from the original's
  effects row, not from `latest`.
- Reserve the sequence in Redis before spooling: a Lua CAS that writes the new event id, so a
  rerun recognises its own reservation. On a lost CAS, re-read and re-decide before anything is
  spooled.
- Record a distinct SKIPPED reason, and count it, when the transition is not allowed.
- Restate D5(b) as ordering of the spool appends only, and say what consumers may assume (N2).

### 7. MINOR: D1(a)'s race clause is wrong

**Rule:** D1(a), P5.

A concurrent replay with the same key and body never reaches (h).
1. It blocks at (b) behind the first T's `FOR UPDATE`.
2. Under READ COMMITTED it then reads the incremented `version` and answers 409 `stale-alert` at
   (d). A MEDIUM replay would also fail at (g).

P5's "returns the same `decision_id` while undoable" is therefore false for a concurrent replay.

**Fix:** Run (a) after (b), so the lookup happens under the entry lock. Alternatively, re-run (a)
on any 409 from (c) to (g).

### 8. MINOR: idempotency rules do not match the schema or the API

**Rule:** D1(a), D6(a), D7(a), P5.

- "Same … `alert_version`" cannot be checked, because `alert_decisions` has no `alert_version`
  column (`V4:42-62`) and §3 adds none.
- For escalate, the request's `target_role` and `reason` live on the new entry, not on the
  ESCALATE decision.
- For override, see finding 5.
- D1(a) answers a replay after the window with 409 `stale-alert`. The OpenAPI says "replays
  return the original outcome (D-29)" (committed :2491). Pick one and change the other.
- `idempotency-conflict` is not among `StateConflict`'s problem types (:2614-2623), and it is the
  409 that every one of the four operations references.
- Neither the 503 of D2(d) nor its `Retry-After` is declared on `decideAlert` (:888-927).
- D6 and D7 inherit D1(a)'s "still undoable" branch, which is meaningless for decisions committed
  at once. Their replay answer is unspecified.

### 9. MINOR: clock details make P2 and the grace inexact

**Rule:** §2 (DB time), §3.1, P2.

- `committed_at` and `undone_at` default to `now()`, the transaction start (`V4:73,83`). A
  committer transaction that starts before `undo_until` and inserts after it stores
  `committed_at < undo_until`. A test of P2 on stored values fails. Set both to
  `clock_timestamp()` in the §3 triggers.
- `undo_until` is fixed at insert (h), but the decision becomes visible and the 202 is sent only
  after commit. Any wait in (h) and commit, such as the lock waits in findings 2 and 4, eats into
  the analyst's 5 s. Bound T with `lock_timeout` and `statement_timeout`, and say so.
- §3.1 says the trigger sets `created_at`. If that applies to every decision, it overwrites
  `PostgresSink`'s explicit `created_at` for AUTO_RELEASED rows (`PostgresSink.java:123-129,
  437-443`) with the sink's lagging write time. Restrict it to analyst decisions.
- §2's "every comparison uses DB time" is not true of the poller (`HoldTimeoutService.java:76`)
  or the sweep (`:102`). Say which comparisons use which clock.

### 10. MINOR: the sweep's bound is 10-15 s, not 10 s; undo near the deadline extends the hold

**Rule:** §1, D2(e), D3(h), P4.

- The sweep runs every 5 s with a 10 s grace (`BackgroundTasks.java:32-35,84`). It times out up
  to about 15 s after the deadline, plus the batch time. P4's "within 10 s of it" is false as
  worded, and its test would be flaky.
- D1(f) allows a decision up to the deadline, and an undo up to G later. D3(h) then reschedules a
  past deadline, so the transaction is held up to G past its deadline, which is outside D-18's
  ±500 ms. This is acceptable, but it belongs in §7.
- Restoring the entry to `prior_status` past the deadline is correct. D1(f) refuses any new
  decide, and the poller's TIMEOUT_RELEASE then reaches the open entry through the sink's UPDATE.

### 11. MINOR: escalation against the timeout can leave the new entry open forever

**Rule:** D7(a), D7(e), §5.

- **D7 has no deadline check.** During sink lag after the poller has timed out a hold, the
  original is still open, so D7 escalates it. The sink's UPDATE (`PostgresSink.java:131-135`)
  can then run concurrently. It blocks on the original's row lock and re-evaluates the original
  as ESCALATED. Its snapshot predates the new entry, so the new entry is never updated. That
  entry stays PENDING in the escalated queue. Every decide on it gets 409
  `review-deadline-passed`, and nothing ever closes it.
- **The same stuck-open state arises without escalation** when the timeout's record (the
  leader's spool) reaches PostgreSQL before the `AlertRaised` record (the ingest instance's
  spool), as N2 permits.
- **The AUTO_RELEASED decision lands on whichever entry PostgreSQL returns first.** The insert is
  `INSERT … SELECT … WHERE transaction_id = ? AND tier = 'MEDIUM' ON CONFLICT DO NOTHING`
  (`PostgresSink.java:123-129`). With two MEDIUM entries for one transaction, only one row
  survives the unique key, on either entry.

**Fix:**
- Give D7 the D1(f) deadline check.
- Add a reconciler that closes open MEDIUM entries whose transaction has a TIMEOUT_POLICY state
  in `decision_states`.
- Restrict the sink's insert to the open entry.

### 12. MINOR: D2(c)'s case analysis and §1's "exactly one caller removes each entry" are incomplete

**Rule:** §1, D2(a), D2(c), D3(h).

- The sweep also removes entries: `HoldTimeoutService.java:103` calls `cancel`.
- Holds can sit in another instance's `LocalFallbackHolds` map. This happens during a Redis
  outage or while that instance's `DegradedMode` skips the primary (`ResilientPorts.java:43,
  203-218`, `:249`). `take` on Redis cannot see such a hold. The analyst gets 409
  `review-deadline-passed` well before the deadline, and that instance's local poller times it
  out.
- If D3(h) reschedules through the resilient port, the same applies.

No double decision results, but D2(c)'s "either the poller took it, or it was never scheduled"
is false, so the message misleads. Name the case, and have D3(h) use Redis directly.

### 13. MINOR: D6(f)'s breaker count must exclude HIGH explicitly

**Rule:** D6(f), E2, P8.

A HIGH transaction is always auto-blocked (`DecisionEngine.java:71-78`) and counted as fraud at
ingest (`DecisionService.java:271-272`). That count does not set the E2 marker key. Consider a
HIGH alert marked legitimate by the analyst, then overridden to fraud. Read literally, "a breaker
count for an upgrade to fraud" counts it a second time. E2's tier restriction probably applies,
but D6(f) should say so, or P8 is open to a double count.

### 14. MINOR: the committer and effects worker need concrete selection and locking rules

**Rule:** D4(a), D4(c), D5(a), E3.

- Every instance runs the same committer query with no claim. `SELECT … FOR UPDATE SKIP LOCKED`
  is unavailable, because fs_app lacks UPDATE on `alert_decisions` (`V11:20-26`). All committers
  therefore serialise on the BEFORE trigger's advisory lock (`V4:96`), which fires even for
  `ON CONFLICT DO NOTHING`. Specify `ORDER BY undo_until, id` and per-decision
  `pg_try_advisory_xact_lock`.
- D4(c)'s bound holds only while due decisions per institution per P stay at or below 100, and
  absent 40001 restarts. Add the throughput condition.
- E3 should be `ON CONFLICT DO NOTHING`. If PostgreSQL drops the worker's connection, it
  releases the lock while the JVM is still running E1, and two workers can reach E3.
- E1 and E2 make Redis and spool calls while holding a pooled connection inside a transaction.
  Say what bounds them.

### 15. MINOR: E1's "same ids" needs changes the contract does not list

**Rule:** D5(c), D5(e), E1.

- `DecisionState.next` always draws a random event id (`DecisionState.java:119`).
- `analystDecidesHold` draws a random label id (`DecisionTransitionService.java:120`).
- `decidedAt` is the running JVM's `clock.instant()`, so two runs give one event id with two
  bodies. PostgreSQL keeps one, Redis keeps the other.
- Fix: derive `decidedAt` from `committed_at`, and add a `next` overload that takes the event id.
- E1's "SKIPPED … impossible by D2(f)" holds only once findings 1 and 2 are fixed.

### 16. MINOR: AUTO_RELEASED decisions never commit

**Rule:** D4(d), D6(b), D6(c).

- The sink inserts AUTO_RELEASED decisions without a commit row (`PostgresSink.java:123-129`), so
  `v_alert_decision_state` reports them as PENDING_COMMIT forever (`V4:113-121`).
- D6(b) can select such a row as "the latest decision", and D6(c) then answers
  `decision-not-committed` forever. Yet `DecisionState` allows TIMEOUT_RELEASE→DECLINE by
  SENIOR_OVERRIDE (`DecisionState.java:139-140`).
- Fix: say whether timeouts can be overridden, and give system decisions a commit row (in the
  sink) or exclude them from both the view's state and D6(b).

### 17. MINOR: the properties table is incomplete, and two rows cannot meet the deletion standard as worded

**Rule:** §6.

**Missing properties:**
- the deadlock-free lock order (finding 4);
- D2(e) and D2(d) ownership after a failure (findings 1 and 3);
- the sweep against an in-flight T (finding 2);
- D3(h) rescheduling and timing;
- D4(c)'s bound;
- D5(b)'s override ordering;
- D7(e)'s escalation against the timeout;
- §5's queue membership.

**Rows that need rewording:**
- P1 is guarded twice, by D1(e) and §3.4. A test fails "with its rule removed" only if it
  asserts the 409 `stale-alert` for D1(e) and, separately, the trigger's refusal with D1(e)
  bypassed. Say so.
- P3's "same however many times" needs a fault point between each effect and E3: after E1's spool
  append, after E1's Redis save, and after E2.
- P4's 10 s, as in finding 10.

## Factual claims verified as correct

- `alert_queue_entries` is mutable, with `status` and `version`. An escalation is a new row
  pointing at the old one. (`V4:4-33`)
- `alert_decisions` is append-only and unique on `(institution_id, idempotency_key)`.
  (`V4:54,67`)
- `alert_decision_commits` and `alert_decision_undos` are append-only. `forbid_commit_and_undo`
  takes a per-decision advisory xact lock and refuses the second of the two. (`V4:76,87,91-111`)
  The lock key is `hashtextextended('alert_decision:' || id, 0)` (`V4:96`); the contract's
  shorthand is fine.
- `v_alert_decision_state` derives PENDING_COMMIT, COMMITTED or UNDONE. (`V4:113-121`)
- `alert_decision_*` grants are SELECT and INSERT for fs_app; `alert_queue_entries` has UPDATE.
  (`V11:20-33`)
- `decision_states.event_id` is unique (`V3:96`). `label_events` has id as its PK (`V5:144`), and
  the sink inserts it `ON CONFLICT DO NOTHING` (`PostgresSink.java:137-142`). Kafka envelope ids
  derive from the state's event id (`KafkaMessages.java:384`). So D5(e)'s receiver-side
  deduplication holds, given derived ids.
- `RedisHoldSchedule`'s cancel is one Lua step of HGET, HDEL and ZREM (`:33-39`), and `claimDue`
  is atomic (`:41-51`). D2(g) is correct for poller against analyst.
- `DecisionService` counts a failed schedule and goes on. (`DecisionService.java:252-262`)
- The `ResilientPorts` hold fallback is per-process (`:203-218`), and the `countConfirmedFraud`
  fallback drops the count (`:148-156`). So D2(a) and E2 are right to call Redis directly.
- The poller ticks every 100 ms (`BackgroundTasks.java:29`). The sweep's grace is 10 s
  (`:35`), but see finding 10 for its period.
- V62's SECURITY DEFINER, ids-only function pattern (`V62:108-121`) exists. The workers'
  per-tenant transactions under RLS mirror `JdbcOverdueHolds`.
- `analystDecidesHold` cancels the hold and records `DecisionChanged` and `LabelRecorded` through
  the recorder. Only tests call it (`HoldTimeoutServiceTest`, `FactScenarios`).
  (`DecisionTransitionService.java:95-130`)
- `DecisionState`:
  - HOLD→APPROVE or DECLINE is allowed by ANALYST.
  - DECLINE→APPROVE is allowed only by CUSTOMER_VERIFICATION or SENIOR_OVERRIDE, so Q1's premise
    holds.
  - Nothing returns to HOLD.

  (`DecisionState.java:131-143`)
- HIGH is always auto-blocked and counted as fraud at ingest, so E2's "never on HIGH" is right
  for analyst decisions. (`DecisionEngine.java:71-78`, `DecisionService.java:271-272`)
- `countConfirmedFraud` buckets by the transaction's minute, with a 25 h TTL and no decrement.
  (`RedisCircuitBreakers.java:33,82-89`)
- **D3(g) holds for status.** The only other writer of `alert_queue_entries.status` in main code
  is the sink's AUTO_RELEASED UPDATE, which requires PENDING or IN_REVIEW
  (`PostgresSink.java:131-135`). D7 requires an open entry, and D6 requires a commit, which
  cannot coexist with an undo. Caveat: the sink's AUTO_RELEASED *decision* insert ignores status
  (finding 11). It does not change status.
- `AuditLog` writes in the caller's transaction (`AuditLog.java`, `JdbcAuditLog.java:55-58`), so
  P9's "same transaction" is achievable. The committer must use the Spring-managed
  TenantTransactions, not a raw connection.
- OpenAPI (committed):
  - `AlertDecisionAccepted` is `{decision_id, state: const PENDING_COMMIT, undo_until}`
    (:3397-3403), which matches D1(i).
  - undo is 204 (:930-955); escalate is 201 `AlertSummary`; override is 201 `DecisionRecord`.
  - The problem types `stale-alert`, `review-deadline-passed`, `decision-not-committed`,
    `undo-window-expired`, `not-decision-author`, `own-decision-override` and
    `escalation-level-required` exist.
- The Kafka label schema has source SENIOR_OVERRIDE and `supersedes_label_id`.
  (`label.schema.json:29-38,63-72`)
- The defect register's D-44 (PENDING_COMMIT, 5 s grace, compensating record, ≤ 2 s + 5 s), D-29
  (version and key on every action, 409 on stale) and D-10 (MEDIUM timer arithmetic, ANOMALY
  holds nothing) say what the contract cites. (`defect_register.md:49-51,111-112,162-163`)
