# P2 code review 1: the decision-owner implementation (11448ce..e3c99ce)

Reviewer: independent, adversarial. Scope: `git diff 11448ce e3c99ce -- backend contracts` in
`/home/marius/fs-wt/m8b` (branch m8b/analyst-api, e3c99ce). Specification:
`docs/architecture/decision-owner-contract.md`, draft 5 (approved). This was a static review only:
nothing was built and no tests were run (low-memory host). Nothing in the repository was modified.

## Verdict

**0 BLOCKER, 6 MAJOR, 17 MINOR.**

The owner mechanism is implemented faithfully. Every state change after sequence 1 goes through
`DecisionOwner.submit` under the owner row's `FOR UPDATE`. `OwnerRules` is a direct transcription of
§5: the contested pre-check, TIMEOUT's order, the EFFECT and OVERRIDE tables, CUSTOMER_YES's four
steps, RESOLVE's `contested_since` equality, and RELEASE_FAILED. Request-id lookup follows the lock,
the backstop trigger sits on `transaction_requests`, the sink takes the first-decision lock before
`transaction_ids`, and §6.1's five cases are present in `firstDecisionArrives`. I found:
- no path that moves a state without the owner row's lock;
- no Redis read in a decision path;
- no effect outside PostgreSQL before a commit;
- no BLOCKER.

The MAJORs are of three kinds:
- **The analyst runner's handling of the owner's SQL errors,** which drops the 40P01 retry that §3
  relies on and turns timeouts into 500s.
- **Contract-mandated signals and labels that were not implemented:** the outage counter, the
  effects-failed counter and `changeAction`.
- **Two edge-case behaviours,** plus a large test gap against §13.

## Lock order and transactions as taken

I traced each path against §3:
- **D1 and D6:** entry `FOR UPDATE` (1), then `alert_entry:` advisory (2), then the owner (4 only
  when creating, then 5). The V81 timing trigger re-takes the same `alert_entry:` key re-entrantly,
  with the same `hashtextextended` text.
- **D2:** entry (1), then advisory (2). The undo insert fires `forbid_commit_and_undo`, which takes
  `alert_decision:` (3), then RELEASE (5).
- **The committer:** `pg_try_advisory_xact_lock` on `alert_decision:` (3), with no entry lock.
- **TIMEOUT and RESOLVE:** `LOCK_OPEN_ENTRIES` ordered by id (1), then the owner (4/5), then
  CLOSE_* (Exception 1). RESOLVE then audits (7).
- **The sink:** level 4 per `TransactionDecided`, then 5 in `firstDecisionArrives`/`lateEntry`
  (Exception 2).
- **D5 and the undo:** they insert rows whose FK `KEY SHARE` locks on `users`/`institutions` (6) come
  before level 5. That conflicts only with `FOR UPDATE`/key updates of those rows, so it is
  harmless in practice.

**Spring connections.** `DataSourceUtils.getConnection(dataSource)` inside `TenantTransactions` gets
the transaction-bound connection, because the bean is the same `DataSource` the template's manager
binds. `releaseConnection` does not close it. That part is correct.

**What is wrong is error handling around it** (MAJOR 1).

## MAJOR

### MAJOR 1: the analyst runner never retries a 40P01 or 40001 raised in the owner's SQL, and 55P03 or 57014 answer 500, not 503

**Where:**
- `backend/analyst/.../decisions/AlertDecisions.java:769-779`
- `backend/analyst/.../decisions/DecisionWorkers.java:422-436`
- `backend/audit/.../jdbc/TenantTransactions.java:70-73`
- `backend/auth/.../web/ProblemHandler.java:298`

**What.** `submit` catches the `SQLException` that `DecisionOwner.submit` throws on raw JDBC and
rethrows it as `IllegalStateException`. `TenantTransactions.inTenant` catches only
`DataAccessException`, so the retry that P2 added for 40P01 never sees an error raised inside the
owner. That is where §3's Exception 1 deadlock is detected: a decide holding entry row (1) and
waiting for the owner (5), against a TIMEOUT holding 5 and updating a late entry. The same applies
to the sink-versus-D1 deadlock on a re-delivered `AlertRaised` (§3, Exception 2).

Separately, §2 says a lock or statement timeout "answers 503 with Retry-After". Here:
- 55P03 or 57014 from the owner becomes `IllegalStateException`, which `ProblemHandler.unexpected`
  answers 500 `internal`;
- 55P03 or 57014 from a `JdbcTemplate` statement becomes `CannotAcquireLockException` or
  `QueryTimeoutException`, which no handler maps, so it also answers 500.

**Scenario.**
1. Analyst decides entry E of transaction T while T's TIMEOUT is closing a late entry.
2. PostgreSQL picks the decide as the deadlock victim.
3. The analyst gets 500 instead of the retried answer, which §3 says is "answers by D1": 409
   `review-deadline-passed`.

An owner row held by a sink batch for more than 2 s gives a 500 instead of a 503 with `Retry-After`.

**Fix.**
- Translate the owner's `SQLException` with the template's translator,
  `jdbc.getExceptionTranslator().translate("owner", null, e)`, so it reaches the runner as a
  `DataAccessException` with its SQLSTATE cause.
- Map 55P03, 57014 and class 08 (`PessimisticLockingFailureException`, `QueryTimeoutException`,
  `DataAccessResourceFailureException`) to `ProblemException.serviceUnavailable` in the analyst
  module, or in `ProblemHandler`.
- Add a test that injects 40P01 on the owner's connection.

### MAJOR 2: the contract's counters and gauges are not exported

**Where:**
- `decision/.../port/DecisionMetrics.java:59`, where `holdRequestRetried` defaults to a no-op;
- `ingest/.../config/MicrometerDecisionMetrics.java`, which does not override it;
- `analyst/.../DecisionWorkers.java:53-54, 232`;
- `decision/.../jdbc/PostgresSink.java:167`;
- `decision/.../owner/RequestRecorder.java:62`;
- `ingest/.../config/DecisionWiring.java:353-361`.

**What:**
- **The outage signal.** §7.4 says that during a PostgreSQL outage "the live signal is the timeout
  paths' count of rescheduled requests … M9 alerts on it (ADR 0111)". `HoldTimeoutService` calls
  `metrics.holdRequestRetried()`, but the production `DecisionMetrics` does not implement it, so the
  only live outage signal is never exported.
- **Other counters held only in `AtomicLong`s that no meter reads:**
  - `fs_analyst_effects_failed_total` (D4(e));
  - the sink's count of dropped pre-V81 later states (§6.1, "counted");
  - the recorder's count of failed cache writes (§4.6, "counted");
  - the committer's failures.
- **The alarm names.** The seven alarms are exported as `fs_decision_alarm_count{alarm=…}` and
  `fs_decision_alarm_oldest_seconds{alarm=…}`. §7.4 names the gauges `fs_decisions_contested`,
  `fs_holds_overdue_oldest_seconds` and so on, and M9's rules are to be written against those names.

**Scenario.** PostgreSQL is down for 10 minutes. Every held payment stays held, and every hold is
rescheduled every second. No metric shows it, so the "alert on it" that ADR 0111 promises cannot be
written.

**Fix.**
- Implement `holdRequestRetried` in `MicrometerDecisionMetrics`, as a counter
  (`fs_hold_requests_rescheduled_total`).
- Register function counters for `effectFailures`, `commitFailures`, `unreservedStates` and
  `cacheFailures`.
- Either rename the gauges to §7.4's names or record the mapping in the contract or ADR 0114.

### MAJOR 3: `KafkaMessages.changeAction` was not updated (§5.1, §14)

**Where:** `decision/.../adapter/events/KafkaMessages.java:288-296`

**What.** §5.1 and §14 require that an ANALYST DECLINE from DECLINE, or from APPROVE by
CUSTOMER_VERIFICATION, is labelled as a confirmation or re-decline, not `HOLD_DECLINED`. The method is
unchanged: `case ANALYST -> APPROVE ? "HOLD_APPROVED" : "HOLD_DECLINED"`. The SENIOR_OVERRIDE
transitions and RESOLVE (`CONTEST_RESOLVED`) also fall to the generic `DECISION_<value>`.

**Scenario.** An analyst confirms a model DECLINE (DECLINE → DECLINE by ANALYST). The audit event says
`HOLD_DECLINED` for a payment that was never held.

**Fix.** Branch on `s.supersedes()`:
- DECLINE → DECLINE gives `DECLINE_CONFIRMED`;
- APPROVE → DECLINE gives `CUSTOMER_APPROVAL_OVERRULED`;
- reason code `CONTEST_RESOLVED` gives `CONTEST_RESOLVED`.

Add a test per transition.

The contract mandates this explicitly, but the impact is limited to the audit record and moves no
state. Under the brief's "contradicts the approved contract" clause the caller may raise it to
BLOCKER.

### MAJOR 4: the resolver audits a resolution's state change again on every concurrent run, and audits DEFERRED and replayed outcomes

**Where:** `analyst/.../decisions/DecisionWorkers.java:350-373`

**What.** Every instance runs `applyResolutions` every P, and nothing coordinates two instances that
list the same resolution:
- the second instance waits on the owner lock;
- it gets the stored outcome (`replay = true`);
- it still writes the `RESOLVED` or `CLAIM_RELEASED` audit event, with `outcome APPLIED`, a second
  time.

The audit is also written when the outcome is DEFERRED, which writes no request row, so the
resolution is re-listed and re-audited on every run. P20 says "A resolution is audited twice: its
authorisation … and its state change".

**Scenario.** Two instances. An operator resolves T. Both resolvers list it within the same P. The
audit log then holds two state-change entries for one resolution, with identical bodies.

**Fix.** Audit only when `!r.replay()` and the outcome is not DEFERRED. Alternatively, take
`pg_try_advisory_xact_lock('resolution:' || id)` first and skip the resolution if the lock is held,
as the committer does.

### MAJOR 5: an undo after a RESOLVE reopens the analyst's own entry, which D2(a) exists to prevent

**Where:**
- `analyst/.../decisions/AlertDecisions.java:294-335`
- `decision/.../owner/DecisionOwner.java:107-111` (`CLOSE_ON_RESOLVE`)

**What.** D2(a) refuses an undo when the entry has a `resolution_id`. `CLOSE_ON_RESOLVE` sets
`resolution_id` only on PENDING or IN_REVIEW entries. The analyst's own entry, whose ANALYST claim
RESOLVE clears, is CONFIRMED_FRAUD or MARKED_LEGITIMATE, so it never gets one.

Inside the 5 s window, the undo:
- inserts the undo row;
- submits RELEASE, which is refused as `NOTHING_TO_RELEASE`, and the result is ignored;
- restores `prior_status`, which reopens the entry.

The reopened entry can never be decided: D1 answers `review-deadline-passed`, or `stale-alert`
because the owner is not HOLD. An override then has no non-undone original. So the entry sits in the
open queue for ever, which contradicts §10 and P15. This is review 4's finding 2, which survives
through the claimed entry.

**Likelihood.** Low: an operator must resolve within 5 s of the decision.

**Fix.** Either:
- when RELEASE returns `NOTHING_TO_RELEASE`, answer 409 `stale-alert` and roll back; or
- have RESOLVE also stamp `resolution_id` on the entry of `owner.claimDecisionId()`.

### MAJOR 6: many §13 properties have no test, and several new code paths are never executed

**What.** §13 says each property is "proved by a test that fails with its rule removed". I grepped
the changed tests.

**No test for:**
- **P1:** concurrent decides with different keys.
- **P3:** two concurrent effect workers.
- **P4:** a fast JVM clock, against the real owner. `HoldTimeoutServiceTest` tests the in-memory
  stand-in.
- **P5:** two concurrent requests giving k and k+1.
- **P6:** byte-for-byte re-record. `answerWhoseRecordingFailedIsRecordedByTheRecorder` checks
  presence, not bytes.
- **P8, P8a, P8d:** resolver, effect and release paths end to end.
- **P8b:** V81's PRE_V81 markers and backfill.
- **P8e, P8f, P8g, P9.**
- **P13:** AUTO_DECLINED, and an escalated entry closed by a timeout.
- **P14:** alarms.
- **P16:** `FinalDecisions`, the poll's 503 `decision-contested` and the Redis branches.
- **P17:** `decision_states_first_only`.
- **P18:** racing on one entry.
- **P19:** `lease_until`.
- **P19a:** escalating an ANOMALY alert.
- **P20:** COMMITTED, UNDO, ESCALATED and SENIOR_OVERRIDE are not each asserted.
- **P21:** breaker counts once, never for HIGH.

**SQL that is listed in `DatabaseSecurityTest` but never executed:**
- `resolve_contested_decision` and `release_failed_claim`. Their audit insert passes the hypertable's
  insert guard only because `resolution_actor` calls `set_config('fraudshield.institution_id', …)`.
- `decision_alarm_figures`.
- `institutions_with_unapplied_resolutions`.
- `institutions_with_pending_effects`.

**Other code no test runs:** `DecisionWorkers.applyResolutions` and `countBreakers`, and the sink's
53200 halving.

**Fix.** Add the missing property tests, at least P1, P5, P8, P8a, P8b, P13, P16, P17 and P21. Add a
persistence test that calls both operator functions as an `fs_operator` member and checks the audit
row. Add one test that runs `decision_alarm_figures()` against seeded rows.

## MINOR

1. **DB time is read before the lock.** `decision/.../owner/DecisionOwner.java:180`: `now` comes from
   `clock_timestamp()` before the row lock, which can wait up to `lock_timeout` (2 s).
   - **Effect:** a CLAIM that waited behind a sink batch is evaluated against a `now` up to 2 s old,
     so it can succeed after the deadline. Also, `decided_at` of state k+1 can precede state k's.
   - **Fix:** read DB time after `lockOrCreate`, and after the request-id lookup.

2. **OVERRIDE's refusal order differs from D5(d).** `decision/.../owner/OwnerRules.java:205-212`:
   `resolutionId != null || held` is checked before the ANALYST claim. On a HOLD with a pending or
   FAILED analyst claim, D5(d)'s order gives `decision-not-committed` or `decision-effect-failed`;
   the code gives `stale-alert`.
   - **Fix:** check the ANALYST claim first, then the resolution, then HOLD.

3. **The 53200 halving recurses while holding the outer connection.** `decision/.../jdbc/
   PostgresSink.java:216-228`: `accept` calls itself inside the try-with-resources of a connection
   it has rolled back but not closed.
   - **Effect:** a 200-record batch can hold about 8 pooled connections (pool 20) at the moment the
     database is short of lock memory. Pool exhaustion then surfaces as class 08, which is
     transient, and the drainer retries the full batch for ever.
   - **Fix:** close the connection before splitting. Add a test.

4. **A swept hold that is rescheduled hits an NPE.** `decision/.../jdbc/JdbcOverdueHolds.java:38`
   builds a `DueHold` with `eventId` set and `decidedAt` NULL. `RedisHoldSchedule.java:86-93` then
   calls `hold.decidedAt().toEpochMilli()`.
   - **Effect:** a sweep TIMEOUT refused as EARLY (JVM clock ahead of the database by more than the
     grace) throws `NullPointerException` on reschedule. `ResilientPorts.attempt` counts that as a
     Redis failure, puts the shared `DegradedMode` towards degraded, and parks the hold in local
     memory.
   - **Fix:** select `first_event_id`'s `decided_at`, or write the basis only when both are present.

5. **One permanent failure stops the whole batch.**
   - `notify/.../UnblockReconciler.java:119-123` and `decision/.../RequestRecorder.java:247-249`
     return from the whole run on the first failure. The batches are ordered oldest first across
     institutions.
   - **Effect:** one permanently failing answer or row starves every later one in every
     institution: deferred answers in the reconciler, unrecorded events in the recorder.
   - **Fix:** catch per item, count, continue, and bound retries with a log or metric.

6. **Some grace cutoffs use JVM time, against §2 and §14.** §2 and §14 say grace and sweep
   comparisons use DB time.
   - `HoldTimeoutService.reconcile` passes `clock.instant().minus(grace)`.
   - `RequestRecorder.recordPending` passes `now.minus(AGE)`. Its javadoc (line 131) says "the
     lister compares with the database's time", which is false.
   - `UnblockReconciler` uses the JVM clock for `responded_at` and for its cutoff, which is at least
     consistent.
   - **Fix:** compute the cutoffs in SQL (`clock_timestamp() - interval …`), or correct the comments
     and state the skew bound.

7. **V81 does not backfill `decision_states.timeout_policy` (§6.4).** V81 adds the column, and
   backfills only the owner rows' policy.
   - **Effect:** nothing today, because every pre-V81 HOLD gets an owner with a policy, but it is a
     stated item of §6.4.
   - **Fix:** add the UPDATE before the `decision_states_first_only` constraint. A NOT VALID check is
     still enforced on UPDATE, and the backfilled rows are sequence 1.

8. **`JdbcDecisionStates.poll` does not read one snapshot.** Its javadoc (line 46) says "from one
   snapshot", but the transaction is READ COMMITTED, so the contested read and the state read have
   separate snapshots.
   - **Effect:** a contest committed between the two statements can still answer 200.
   - **Fix:** use REPEATABLE READ for that read-only transaction, or one query.

9. **The D1 backstop answers 500, not 409.** `analyst/.../AlertDecisions.java:233-236`: on the
   trigger's "live analyst decision" `unique_violation`, `stale(entry.id())` runs `alerts.summary` in
   the aborted transaction (25P02).
   - **Effect:** the backstop path answers 500, not 409. This is relevant to P1 "with its rule
     removed".
   - **Fix:** throw a marker exception, and build the problem after the rollback, in a new
     transaction.

10. **Owner-derived keys share the client's idempotency-key space.** The AUTO_* row's
    `idempotency_key` is `fs_name_uuid('auto_decision|'…)` (`OwnerIds.java:287`,
    `DecisionOwner.java:113-117`). It shares `alert_decisions (institution_id, idempotency_key)` with
    client-supplied keys.
    - **Effect:** the name is computable from ids an analyst sees. A crafted Idempotency-Key makes
      the TIMEOUT's AUTO row a silent `DO NOTHING`, or makes the analyst's own call a 409.
    - **Fix:** a separate unique index, or a key column the client cannot reach.

11. **Not every transaction sets §2's timeouts.** §2 requires `lock_timeout` and
    `statement_timeout` in every transaction. They are not set in:
    - `DecisionWorkers.commit` and its listing transactions;
    - the resolver's and effects' listing transactions;
    - the escalate summary read.

    **Fix:** set them inside `TenantTransactions.inTenant`, or in each lambda.

12. **Dead or misleading code remains:**
    - `domain/HoldTimeout` and `DecisionState.next` and `allowed(from, to, by)` are now used only by
      tests. §14 says `DecisionOwner` is the only caller of `next`.
    - `DecisionOwner.lockOrCreateForSink` only wraps `createFromFirst`, and its name says it locks,
      which it does not.
    - `OwnerRequest.Timeout.basisDecidedAt` and `basisReasons` are carried but never read.

    **Fix:** remove them, or test them as dead.

13. **CUSTOMER_YES on HOLD invents a recorded refusal.** `OwnerRules.java:247-249` refuses
    CUSTOMER_YES on HOLD as `HELD` and records it, so it is never retried. §5 has no HOLD row, and
    `VerificationService` maps it to "stays blocked".
    - **Effect:** it is practically unreachable, but it is behaviour the contract does not state.
    - **Fix:** DEFER, or state it in the contract.

14. **The new page message is untranslated.** `ingest/.../VerificationPageController.java:68, 91,
    113`: the new "recorded" message is English in the fr, rw and sw tables.

15. **SpotBugs is likely to flag the new analyst classes (unverified: SpotBugs was not run).**
    `backend/analyst/spotbugs-exclude.xml` covers only the P1 classes. Likely findings:
    - `DecisionController`: `SPRING_ENDPOINT`;
    - `AlertDecisions` and `DecisionWorkers`: `EI_EXPOSE_REP2`, because they hold a `JdbcTemplate`;
    - the private record `Keyed`, whose component is `byte[] fingerprint`: `EI_EXPOSE_REP`.

    The pattern matches the exclusions P1 needed. Run `mvn -pl analyst spotbugs:check` on CI.

16. **`AnalystInspectorTest` was narrowed on a partly false premise.** `AnalystInspectorTest.java:
    102-125` now checks GET handlers only, on the premise that the writes "carry no customer data".
    `escalateAlert` returns an `AlertSummary`, and every `stale-alert` carries `current`, an
    `AlertSummary`, so both go uninspected.
    - **Fix:** include the write handlers, or inspect the escalate and stale bodies.

17. **Several test names claim more than the tests check:**
    - `undoInsideTheWindowPutsTheAlertBackAndTheHoldIsTheTimeoutsAgain` checks `claim = NONE` only,
      with no reschedule and no timeout;
    - `decidingHoldIsUndoableThen…` never undoes;
    - `yesDeferredWhileContestedIsSubmittedByTheSweepAfterTheContestEnds` ends the contest with a raw
      `UPDATE … contested_since = NULL`, not through RESOLVE;
    - `HoldTimeoutServiceTest`'s "at thirty seconds, not before" tests the comparison in
      `InMemoryHoldOwner`, not the owner's DB-time rule.

    **Timing.** The analyst tests share one Spring context in which the committer, effects worker and
    resolver run every 500 ms across all institutions. The current tests tolerate that through
    `eventually(…)` and fresh institutions. A test that seeds analyst decisions directly would race
    the committer once its 5 s window closes.

## Checked and found correct (no finding)

- **`fs_name_uuid`:** it reproduces `UUID.nameUUIDFromBytes`. Nibble 13 becomes '3', and nibble 17
  becomes `'89ab'[x & 3]`. `theSqlIdsMatchTheJavaIds` pins it, with a non-ASCII case.
- **Trigger order:** `alert_decision_commits_exclusive` and `alert_decision_undos_exclusive` fire
  before `…_window` (the 'e' < 'w' order holds).
- **The V81 backfill's order:**
  1. `transaction_requests` and its recording rows are backfilled before the owners;
  2. the sequence trigger is created after the backfill, so backfilled APPLIED rows are not refused;
  3. the NOT VALID CHECK is added after the backfill.
- **Backfill against V3's constraints:**
  - V3 forces `review_deadline_at` exactly for HOLD, so `first_deadline` meets
    `(first_decision = 'HOLD') = (first_deadline IS NOT NULL)`;
  - sequence 1 is always `MODEL`, so the hard-coded `'MODEL'` in `INSERT_OWNER` is right.
- **The PRE_V81 marker conditions:** they match §4.4, including no marker for a WAS_ME still under
  DECLINE by MODEL or TIMEOUT_POLICY, and none for a NOT_ME without a CUSTOMER label.
- **Views and grants:**
  - `v_decision_states` excludes `decision_states` rows with sequence > 1, so backfilled history is
    not doubled;
  - both views are `security_invoker`, with the grants they need on the base tables;
  - `CLOSE_ON_TIMEOUT` reads `decision_conflict_resolutions`, and `fs_app` has SELECT on it.
- **`CREATE OR REPLACE` of V62's and V64's listers** keeps their signatures and grants. The new
  definer functions have pinned `search_path`. `resolution_actor` is revoked from PUBLIC.
- **The operator functions' audit insert:** it satisfies `tenant_insert_guard`, because the tenant is
  set by `resolution_actor`. It also satisfies `user_role` and `event_type` CHECKs and the action
  regex.
- **Tenancy:** every unqualified `transaction_id` predicate in `DecisionOwner` and in the workers runs
  under a set tenant. The definer listers join on both institution and transaction.
- **OpenAPI:**
  - `override_decision` on `DecisionRecord`;
  - `AUTO_DECLINED`;
  - the new problem types in `StateConflict` and `ProblemType`;
  - `DecisionUnavailable` on the poll;
  - the §5.1 and §5.2 text in `FinalDecision`;
  - the `supersedes_decision` exception;
  - `stale-alert` carries `current`;
  - a missing Idempotency-Key is a 400 validation problem;
  - the x-authorisation-rules for 403 and 422 match the code.
- **The sink:**
  - case 1(a) is idempotent;
  - case 1(b) drops, and compares with the owner first;
  - cases 3, 4 and 5 write in full, with `standingOn` and the contest;
  - P8c holds in `conflictingFirstDecisionDrainingAfterTheTimeoutIsWrittenAndContests`;
  - `decisionChanged` writes nothing.
- **Recording after the commit** happens on every path: `OwnerHoldTimeouts`, `VerificationService`,
  `UnblockReconciler`, D5 and the workers. Redis and hold reschedules also run after the commit, in
  `AfterCommit` or after `inTenant` returns.
