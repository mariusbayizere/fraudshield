# Review 2: risk configuration propagation contract, draft 2

Subject: `docs/architecture/risk-configuration-propagation-contract.md` at `837915d` (branch
`m8b/analyst-api`). The reviewer is independent: it wrote none of the work and did not write review 1.
The review is by reading only: no build, test or container was run. Line references are to `837915d`.
The OpenAPI file has uncommitted edits in the worktree, so its lines are cited from
`git show 837915d:contracts/openapi/fraudshield-api.yaml` (written "API:").
Code paths are abbreviated: `decision/…` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/…`, and migrations are under
`backend/persistence/src/main/resources/db/migration/`.

## Verdict: UNSOUND (3 MAJOR, 11 MINOR)

Draft 2 fixes the core of review 1. It drops the time filter, installs by versions, locks every
dual-control action per (institution, kind) and reverts overdue changes under that lock. It also
probes before loading and stores the hold policy. The C3 ordering argument holds on a single primary
that loses no commits (verified below).

Three rules still fail as written:

- **C8 does not stop a stale instance from undoing a breaker change** (finding N1). The settings
  version reaches Redis only when the state changes. So the first evaluation after a change can still
  come from a stale instance and close a breaker that the new settings keep open. Nothing reopens it.
- **C6 does not say a sweep takes at most one kind lock per transaction** (finding N2). An
  implementation that reverts both kinds of one institution in one transaction can deadlock with an
  approval through the audit chain head. The resulting 40P01 is not retried.
- **The probe makes a lost commit permanent** (finding N3). After a failover or restore that drops a
  committed version, version numbers are reused. The probe then compares equal, so the instance never
  reloads. C3 says that "a failover cannot hold a newer snapshot back". That is false here, and so is
  C4's bound.

Each fix is a sentence or two. The rest of draft 2 is sound in shape.

---

## Review 1's findings

| # | Review 1 finding | Status | Why |
|---|---|---|---|
| 1 | MAJOR: read time does not order snapshots | **RESOLVED** | C1 drops the `effective_at` filter (contract:104-105, 116). C3 installs a snapshot only if it is componentwise ≥ the installed one (:118-128). The ordering argument is correct for lock-serialised writers (see "Verified claims"). Two caveats: "a PostgreSQL snapshot sees a prefix of commit order" (:122) is true only for causally ordered transactions, which is all the argument needs; and the failover claim (:127-128) is false when commits are lost (N3). |
| 2 | MAJOR: supersede and fold were unlocked | **RESOLVED** | Every action, the sweep included, takes the kind lock first. It reverts overdue changes in the same transaction, re-reads under the lock and decides by the status it read (:167-184). That closes both of review 1's counterexamples: confirm racing fold, and folding an overdue change. P6 is restated for folding (:251), and P7 lists the races (:252). Residual gaps: N2 (granularity of the sweep's transactions), N8 (rollback on a 409, and the revert's timestamps), N13 (the lock cannot literally be taken first). |
| 3 | MAJOR: the bound ignored the refresh loop | **PARTLY** | The probe, the loads of changed institutions only, a dedicated thread and the bound in C (:130-153) answer the counterexample. But the mechanics as written do not deliver the stated bound: one thread cannot run K loads at once; T_L limits a statement, not a load; the probe has no timeout; the arithmetic is off; the sweep's scheduler is not named (N7). And the age alert that is meant to catch a broken bound fires for every quiet institution (N4). |
| 4 | MAJOR: the sweep recomputed a held policy | **PARTLY** | `decision_states.timeout_policy` (§3.4, :89-91) and C9 (:226-231) fix new rows. But the fallback applies to every row whose policy is NULL, not only to rows "written before V81". That includes HOLD rows that the sink writes after V81 from spool records made by the previous release (N9). |
| 5 | C5: two reads, and what is recorded | **RESOLVED** | One snapshot object per decision (:155-157), and PB-151 for the other versions (:162-164). C5 and P5 still overclaim about breaker settings (N10). |
| 6 | Version 0, partial versions | **PARTLY** | Version 1 is seeded by V81 and by a trigger on `institutions` (:68-77). But the completeness trigger sits on the wrong table and lets a version with zero channel rows through. Existing data is not checked (N5). The rule-set version of rules that existed before V81 is undefined (N6). The seeding trigger collides with the test fixtures (N12). |
| 7 | C6: clock, rejection, lister, retries, actor | **RESOLVED** | The status is read under the lock, the deadline is compared with `clock_timestamp()`, a rejection after the deadline is refused, the lister uses the DB's `now()`, the actions run in `inTenant` and the revert has the system actor (:167-189). One residue on timestamps is in N8. |
| 8 | C8: stale evaluations; one cold load | **PARTLY** | The second half is resolved: evaluation runs per institution (:222-224). The first half is not: the settings version is written only on a state change, so a stale instance can still close a breaker first (N1). |
| 9 | Rule-set lock details, lost edit, `trigger_count` | **RESOLVED** | §3.3 (:86-88) and C7 (:207-215). Draft 2 adds a new dependency, though: the probe relies on every rule-affecting write appending a set version (N6). |
| 10 | Anomaly threshold, eviction, first request, compiler skew | **RESOLVED** | C10 and PB-153 (:237-240), §6 (:266-270), and C4(c) (:137). The probe removes the cost of reloading idle institutions. The eviction rule itself is dead or harmful (N11). |
| 11 | Wording | **PARTLY** | The `effective_at` wording is fixed (:104-105). But the age metric is now wrong in a new way (N4), and P3 still overclaims for a DB clock step, because the 24-hour revert is clock-timed (N14). |
| 12 | Missing properties | **PARTLY** | P6 to P12 cover the races, the late rejection, the seed, C8, C9's sweep path and C4 with N institutions. There is still no row for C7's skipped-rule accounting, none for the incomparable-snapshot counter, and none for the age alert. N1, N2 and N3 each need a row. |

---

## New findings

### N1. MAJOR: C8's settings version does not stop a stale instance from undoing a breaker change (C8, P11)

**Rule.** C8 (:218-221): "The breaker's state in Redis records the settings version it was last
evaluated under. A compare-and-set from an evaluation under a lower settings version is refused, so
an instance that has not yet installed a change can never undo the change's effect." P11 (:256) says
the same.

**Why it fails.** The monitor writes to Redis only when the state changes:
`if (evaluation.state().equals(current) || !breakers.compareAndSet(...)) continue;`
(`decision/application/CircuitBreakerMonitor.java:61-63`). The only write is the Lua CAS on the change counter (`decision/adapter/redis/RedisCircuitBreakers.java:35-42`).

- So the settings version in Redis is the version of the last *state change*, not of the last
  evaluation.
- And even if every evaluation wrote it, the first evaluation after a change can come from an
  instance that has not installed the change. Nothing re-checks a closure later.

ADR 0014 §3 counts "a longer clean reset" as a tightening (`docs/adr/0014-staff-authorisation-model.md:55`).

**Counterexample (stale close).** Breaker settings are at v1, with a clean reset of 60 min. The
breaker opened under v1, and the Redis state holds settings version 1 and `last_breach_at` =
12:05:03.

| Time | Event |
|---|---|
| 13:05:00 | Tightening v2 commits: clean reset 120 min |
| 13:05:02 | Instance A installs v2 and evaluates: no breach, open, 13:05 < 14:05. The state is unchanged, so nothing is written and Redis still says settings version 1 |
| 13:05:04 | Instance B, still on v1, evaluates: 13:05:04 ≥ 13:05:03, so it closes the breaker (`domain/MccCircuitBreaker.java:102-105`). Its CAS carries settings version 1, which is not lower than 1, so the CAS is accepted |
| later | A and B, both on v2, evaluate: the breaker is closed and there is no breach, so it stays closed |

The breaker closes an hour earlier than v2 allows, and v2 is never applied to it. That is a change
not applied within C4's bound, and C8's "can never undo" is false. The case where B evaluates before
A's first v2 evaluation defeats even a "write on every evaluation" reading.

The loosening direction has the same problem. A rate threshold goes from 5 % to 10 % (a loosening),
and a stale instance opens the breaker at 6 %. The CAS is accepted. Instances on v2 cannot close it
until `last_breach_at + clean_reset`, and the stale instance keeps moving `last_breach_at` forward
until it installs v2. A loosening approved by two officers is thereby held back for a full
clean-reset period.

**Implementability.** If C8 is read as "every evaluation records its settings version", then the
no-change path must write too. Through `SET_STATE` that write increments the change counter, so the
CAS of every concurrent instance fails. A breach that should open the breaker can then lose several
5-second rounds in a row.

**Fix.**

- Keep the settings version in a separate field. A Lua `HSET`-if-greater writes it on *every*
  evaluation without touching the change counter.
- Add a re-check: an evaluator whose settings version is higher than the one the last transition was
  made under re-evaluates that transition. It reopens a breaker that was closed early (keeping
  `opened_at` and `last_breach_at`, which `MccCircuitBreaker.java:104` already preserves), and it
  closes one that was opened under a lower version and does not breach under the new settings.
- Record the reopening or closing as an event.
- Or, more simply, state that a stale transition within the window is final, and drop "can never
  undo" and P11.

### N2. MAJOR: C6 allows a sweep transaction that holds two kind locks, which can deadlock, and 40P01 is not retried (C6, P7)

**Rule.** C6 (:185-187): the sweep "asks a SECURITY DEFINER lister … which institutions and kinds
have an overdue change. It then runs step (a) for each, under the lock". Each action "takes the lock
as its first statement after the tenant is set" (:168-169). Neither sentence limits a transaction to
one kind lock. Since `inTenant` sets one institution per transaction, the natural implementation runs
one transaction per institution and reverts both kinds in it.

**Counterexample.**

1. Sweep S (one transaction for institution I) takes lock(I, CHANNEL_THRESHOLDS) and reverts. Its
   audit insert locks the chain head of partition p, `FOR UPDATE`, until commit
   (`V8__audit_log.sql:88-89`).
2. Approval A, on another instance, takes lock(I, MCC_CIRCUIT_BREAKER) and applies a loosening. Its
   audit insert lands on partition p too. Without a configured fixed partition, the partition is the
   thread id mod 64 (`audit/jdbc/JdbcAuditLog.java:80-84`), and thread ids collide across instances.
   A now waits for S.
3. S asks for lock(I, MCC_CIRCUIT_BREAKER) and waits for A.

That is a cycle. PostgreSQL aborts one of them with 40P01. `inTenant` retries only 40001
(`audit/jdbc/TenantTransactions.java:69, 91-97`), so the approval returns 500. Two sweeps on
different instances that walk the lister's rows in different orders can deadlock without any audit
involvement at all.

**Fix.** State it as a rule:

- every transaction in C6 takes exactly one kind lock, so the sweep runs one transaction per
  (institution, kind);
- the rule-set lock is never combined with a kind lock;
- the lister returns (institution, kind) pairs in a fixed order.

Add "sweep across both kinds racing an approval" to P7.

### N3. MAJOR: a lost commit makes C3 and the probe keep a version that no longer exists, with its number reused (C3, C4, A1)

**Rule.** A1 (:64) asks only that every transaction run on the primary. C3 (:127-128) claims that "a
clock step or a failover cannot hold a newer snapshot back". C4(b) (:134-135) reloads an institution
only when the probed versions *differ* from the installed ones.

**Counterexample.** Replication is asynchronous, or the database is restored to a point in time.

1. Tightening T commits threshold v5 on the old primary. Instance X installs v5.
2. The primary fails over to a standby that never received v5, or the database is restored to before
   v5.
3. The new primary's highest version is v4. X's loads come back with v4 ≤ v5, so C3 discards them.
   X keeps deciding on settings that no longer exist anywhere, while other instances run v4.
4. An officer re-proposes, against base version 4, and commits a different v5'. X's probe shows 5,
   which equals what X has installed, so X never loads v5'. It keeps the lost v5 until some v6
   commits, which may be never.

Under draft 1's reload-everything loop, step 4 would at least have reloaded v5'. Draft 2's probe
makes the stale state permanent. P2 is about the instance, so it is satisfied, but C4's bound (P4)
is false for v5'.

**Fix.** Choose one:

- Add assumption A2, "no committed transaction is lost (synchronous replication; no PITR while
  instances run)", and make the runbook clear every instance's cache after a restore.
- Add an epoch to the probe and to each snapshot: the database system identifier plus the timeline
  (`pg_control_system()` and `pg_control_checkpoint().timeline_id`), or a row that an epoch-bump
  migration rewrites. Probe and install compare (epoch, versions). A new epoch replaces every
  snapshot unconditionally.

Add the case to P2 or P4.

### N4. MINOR: the 60-second age alert fires for every institution that has not changed (C4, Q1)

**Rule.** "An alert fires when any installed snapshot's age passes 60 s. Age is measured … from the
install, plus that load's own duration" (:150-153).

**Why it fails.** Under draft 2 an unchanged institution is never reloaded (C4(b)). Its snapshot's age
since install therefore grows without bound in normal operation, and the alert fires for every quiet
institution after 60 s. An always-firing alert gets silenced, and then a probe that really is failing
goes unnoticed. That is exactly the case Q1 relies on the alert for.

**Fix.** Define the age as the time since the last successful probe that confirmed the installed
versions (probe versions equal to installed, or a successful load), plus that probe's or load's
duration. Measure it on the monotonic clock. Export it per instance as a maximum, not per institution.

### N5. MINOR: the completeness trigger cannot see a version with no channel rows; existing data is unchecked (§3.2, P9)

- "A deferred constraint trigger on `risk_thresholds` refuses a commit that leaves a version with
  other than six channel rows" (:78-79). A transaction that inserts a `risk_threshold_versions` row
  and no `risk_thresholds` rows fires no trigger on `risk_thresholds`, so a zero-row version commits.
  V6 allows that: the foreign key runs from rows to version (`V6__risk_configuration.sql:98`), not the
  other way. P9's "every threshold version has six channels" is then false, and C2's load of "the
  highest visible threshold version, with its six rows" finds none.
  - Put the deferred constraint trigger on `risk_threshold_versions` (AFTER INSERT, DEFERRABLE
    INITIALLY DEFERRED), counting rows at commit. Keep the one on `risk_thresholds` too.
- V81 must also check, or refuse to run on, an existing version with fewer than six rows. Otherwise
  the loader, whose default branch "becomes an error" (:75), stops loading that institution entirely.
- This is MINOR, not MAJOR, only because it needs a writer that breaks the contract. The trigger
  exists as the backstop for exactly such a writer.

### N6. MINOR: the probe depends on every rule-affecting write appending a set version; pre-V81 rules have none (§3.3, §3.5, C4)

- Under draft 1, any write to `alert_rules` or `alert_rule_versions` was picked up by the next full
  reload. Under draft 2, a write that does not append to `alert_rule_set_versions` is never seen by
  refresh. Examples: a demo seeder, a manual fix, a future `trigger_count` writer that also flips the
  state, or a migration. Only first-request loads would see it. §3.3 states this as the writers'
  discipline, not as something the database enforces.
  - Fix: append the set version from a trigger on `alert_rules` (on INSERT, and on UPDATE OF `state`
    and `current_version`) and on `alert_rule_versions` (on INSERT), under the RULES lock inside the
    trigger. The application then cannot forget it.
- An institution with rules created before V81 has no `alert_rule_set_versions` row. §3.5 does not
  say whether the probe returns NULL or 0 for it. If it returns NULL, `NULL <> 0` makes C4(b) either
  reload that institution every cycle or never, depending on how the comparison is written.
  - Fix: V81 seeds rule-set version 1 for every institution that has rules, and the probe returns
    `coalesce(max, 0)`.

### N7. MINOR: C4's mechanics do not deliver the stated bound (C4, P4)

- **Threads.** "Refresh runs on its own single-thread scheduler" (:131), but it "loads, at most K at a
  time" (:134). Blocking JPA loads need K worker threads. Say that the scheduler thread runs the probe
  and hands loads to a K-thread pool. The pool must also not take decision-path connections without a
  limit: name the pool.
- **T_L.** T_L is "a statement and connection-acquire timeout" (:57). A load is at least four
  statements (thresholds, breaker, rules, set version), plus acquire and commit. One load can
  therefore take about 5·T_L, and the bound `⌈N/K⌉·T_L` does not cap it. Use a deadline for the whole
  load: a Spring transaction timeout of T_L, or `SET LOCAL statement_timeout` plus an overall future
  timeout.
- **Probe.** The probe has no timeout, so a hung probe stops refresh for every institution. Give it
  one.
- **Arithmetic.** `R + 2(probe + ⌈N/K⌉·T_L) ≤ 60` gives `⌈N/K⌉·T_L ≤ 27.5 − probe`, so `⌈N/4⌉ ≤ 13`
  and N ≤ 52, not "about 54" (:148). At N = 54, `⌈54/4⌉·2 = 28 s`.
- **Sweep scheduler.** C6's sweep runs "every R" (:185) but on no named scheduler. If it joins the
  shared two-thread pool (`ingest/config/BackgroundTasks.java:80-87`), R_sweep in the revert bound
  (:197-198) has the same unbounded lateness that finding 3 removed from refresh.

### N8. MINOR: a refusal can roll back the revert it just made; the revert's timestamps can predate the deadline (C6, P8)

- C6 runs the overdue revert (a) and the refusal (d) in one transaction (:170-184). If the 409 is
  raised as an exception inside `TransactionTemplate`, which is the natural way, the revert is rolled
  back with it. P8's "gets 409, and the change is REVERTED" (:253) then holds only after the next
  sweep.
  - Fix: say that (a) commits before (b)-(d), in its own transaction under the lock or by returning
    the refusal as a value. Or restate P8 as "within R_sweep".
- `reverted_at`, the revert version's `effective_at` and the audit row's `recorded_at` default to, or
  must equal, the transaction's `now()`, its *start* (`V6__risk_configuration.sql:82, 112`,
  `V8__audit_log.sql:80`). A sweep or action transaction that started before `confirm_by` and waited
  on the lock across it passes the `clock_timestamp()` check. It then records a NOT_CONFIRMED_IN_TIME
  revert dated *before* the deadline, which an examiner reads as a premature revert.
  - Fix: set `reverted_at` to `clock_timestamp()`, or `greatest(now(), confirm_by)`, and add a check
    `revert_cause <> 'NOT_CONFIRMED_IN_TIME' OR reverted_at >= confirm_by`.
  - The audit row's `recorded_at` cannot change (V8:80). Its `event_at`, though, should be the
    deadline or `clock_timestamp()`.

### N9. MINOR: C9's fallback is not limited to rows written before V81 (C9, P12)

C9 says (:229-230): "Only for a row written before V81 does it fall back." But the sweep can only test
`timeout_policy IS NULL`.

- `PostgresSink` drains a durable on-disk spool (`decision/adapter/jdbc/PostgresSink.java:33-43`;
  `adapter/spool/SpoolRecord.java:10`). Records spooled by the previous release survive a restart,
  have no policy in their payload, and are written after V81 with NULL.
- During a rolling deploy, instances on the old release write HOLD rows with NULL after V81 has run.

Those holds are swept with today's recomputation from JVM `decided_at` (`JdbcOverdueHolds.java:30-34`),
so P12 fails for them.

Fix: version the event payload, and state the fallback as "NULL policy". Better, fall back to the
policy of the decision's own `thresholds_version` where that is known. Add a spool-replay case to
P12's test.

### N10. MINOR: decisions do not use breaker settings; a breaker change reaches decisions later than C4's bound (C5, P5, C4)

- `DecisionService` reads `breakers.isOpen(...)` (`decision/application/DecisionService.java:185`). That
  is a local copy refreshed only by this instance's own monitor evaluation
  (`RedisCircuitBreakers.java:62-64, 132-147`), not the snapshot's breaker settings. So "takes
  thresholds, rules and breaker settings from it" (:156-157) and P5's "breaker settings come from one
  snapshot" (:250) describe nothing that the decision does.
- The effect of a breaker change on decisions is reached after: install (≤ t + R + 2C), plus the
  monitor's next evaluation (5 s, on the shared pool), plus this instance's own evaluation to refresh
  its local copy.
- Fix: restate C5 and P5 as thresholds and rules. Give the breaker its own bound,
  `t + R + 2C + R_monitor` plus the monitor's lateness, and check it against E.6.

### N11. MINOR: C4(c)'s eviction is dead, and if it fires it re-opens the "no snapshot yet" install (C4, C3, P2)

- `institutions` has no status column and is never deleted: it is referenced by foreign keys
  everywhere, and fs_app has only SELECT on it (`V11__grants.sql:40`). So a probe "for every
  institution" (:92-94) never drops one, and C4(c) never fires.
- If it does fire, for example because a mis-granted or RLS-bound probe returns nothing, C3 installs
  any loaded snapshot "if there is no snapshot yet" (:119-120). An in-flight load that began before
  the eviction, with an older snapshot, can then install below the versions the instance has already
  used, and P2 is false. An empty probe would also evict every institution at once, forcing
  synchronous loads on the decision path.
- Fix: drop C4(c), or keep a version tombstone per evicted institution and install only at or above
  it. Treat an empty or failed probe as a failure, never as "evict everything".

### N12. MINOR: the `institutions` seeding trigger: who inserts, grants, RLS and fixtures (§3.1)

- Only the owner or a superuser inserts institutions. fs_app has SELECT only (`V11__grants.sql:40`),
  and no main code inserts any (grep). The trigger therefore runs as the owner. RLS on the version
  tables does not apply to the owner without FORCE, and no migration uses FORCE (grep). That is
  fine, but say so, and say that if a future onboarding role inserts institutions the trigger must
  become SECURITY DEFINER with a fixed `search_path`, as V8's chain trigger is
  (`V8__audit_log.sql:73-75`).
- The decision test fixture inserts version 1 itself right after the institution
  (`decision/…/testing/TestDatabase.java:169-197`, in `src/test`). With the trigger, that insert hits
  the primary key. `PostgresAdaptersTest.java:316-322` also expects version 0 for a bare institution.
  Plan the fixture change, or make the trigger insert `ON CONFLICT DO NOTHING`. With the second
  option, V81's seed and any older version 1 can disagree silently, so prefer the fixture change.
- Per V71, the probe and the overdue-change lister need an explicit `GRANT EXECUTE` to fs_app, and an
  entry in `DatabaseSecurityTest`'s pinned list (`V71__close_new_functions_to_public.sql:1-12`).

### N13. MINOR: the lock cannot be the first statement of approve, reject or withdraw (C6)

These operations are addressed by `change_id` (API:1673, 1705, 1741). The kind, and therefore the lock
key, is known only after reading the change. Say that a pre-read of `(institution_id, kind)` by id
comes first. It is safe because the guard trigger makes `kind` immutable
(`V6__risk_configuration.sql:61-67`). Everything else is then read again under the lock. A 404 for an
unknown id is decided by the pre-read.

### N14. MINOR: P3 overclaims for the 24-hour revert (P3, C6)

P3 (:248) says that "a DB clock stepped back … changes nothing". The overdue revert is timed by the
DB clock (`clock_timestamp()` against `confirm_by`, :171-172). A DB clock stepped back by an hour keeps
an unconfirmed tightening in force an hour longer. Restate P3 as: "which version is in force is
decided by versions alone; only the 24-hour deadline reads a clock, and that clock is the
database's".

---

## Verified claims

| Claim (contract §) | Evidence |
|---|---|
| **C3 ordering.** Versions of one part are allocated in commit order, and a later snapshot is componentwise ≥ | PostgreSQL releases transaction-scoped locks, advisory ones included, *after* the commit becomes visible: lock release in the resource-owner phase follows the ProcArray update of `CommitTransaction`. So a lock waiter in READ COMMITTED, whose next statement takes a fresh snapshot, sees the predecessor's commit and allocates `max + 1`. Of two causally ordered writers, any snapshot that sees the second also sees the first. Visibility on one primary grows with snapshot time, so a later snapshot sees a superset. This holds for every writer the contract names: actions and sweep reverts under the kind lock (C6), rule writes under the RULES lock (§3.3), V81's seed (version 1 only, before any writer of M8B's code exists) and the trigger seed (a new institution has no other writer). It fails only with lost commits (N3). |
| The probe can differ from the load without harm | The load's snapshot is later than the probe's, so it is ≥ the probe. A first-request load installed after a probe makes the probe look lower, which triggers one harmless reload. Lower probed versions never install anything, because C3 compares against the installed snapshot |
| The institution that has never been cached | Refresh loads only cached institutions (:134). The first request loads synchronously (`decision/adapter/jpa/JpaConfiguration.java:161-168`), and §6 states the PostgreSQL dependency (:266-268) |
| Fold under V6's one-open index and guard trigger | Fold sets SUPERSEDED, then inserts the new APPLIED_PENDING_CONFIRMATION row, under the lock. The guard allows the update from an open state (`V6__risk_configuration.sql:68-70`), SUPERSEDED requires `reviewed_by` NULL (V6:38), and the index frees the slot (V6:47-48) |
| Revert restores the right settings | While a tightening is open, no other writer of that kind exists: a loosening gets 409, and a tightening folds. So `previous` is the version in force before the chain (`common/config/DualControlWorkflow.java:141-151`; API:1515-1520) |
| §1 inventory | Two-thread pool shared with four tasks (`ingest/config/BackgroundTasks.java:80-87`); sequential `refreshAll` with `put` (`JpaConfiguration.java:117-127`); `invalidate` unused (`:135`); JVM `now` (`:172`); `max(alert_rules.version)` (`:203`); defaults at version 0 (`:189-194, 222-224`); two reads per decision (`DecisionService.java:172, 184`); JVM-time policy in the sweep (`JdbcOverdueHolds.java:30-34`) |
| Version 1 may have no change id; `base_version ≥ 1` | `V6__risk_configuration.sql:18, 85, 115` |
| The breaker event foreign key needs a real version | `V6__risk_configuration.sql:127-129`; `PostgresSink.java:147, 478`; `CircuitBreakerMonitor.java:73` passes `settings.version()` |
| Only the thresholds version is recorded, clamped | `DecisionService.java:216`; `KafkaMessages.java:454` |
| The Redis hold carries the decision's policy | `DecisionService.java:253-260` |
| The anomaly threshold is a deploy-time property | `DecisionWiring.java:297` |
| `inTenant` retries only 40001; V8 refuses with 40001 | `audit/jdbc/TenantTransactions.java:56-74, 91-97`; `V8__audit_log.sql:92-95` |
| The decision module's runner uses the template it is given (READ COMMITTED today) | `decision/adapter/jpa/TenantTransactions.java:44-48`; `DecisionWiring.java:209-214` |
| The OpenAPI rules C6 relies on | `conflict` for a stale base version; `open-change-exists` for a loosening; fold and supersede (API:1505-1532, 1574-1598); `change-not-open` for review after the deadline (API:1705-1714, 1741-1750); `setRuleState` carries `version` and has a 409 (API:1348-1373) |
| Tightening and loosening classification (C8 example) | `docs/adr/0014-staff-authorisation-model.md:53-63` |
| The lister pattern | `V62__hold_reconciliation.sql:13-26` (which, unlike C6's lister, takes `p_before` from the JVM) |
| fs_app's grants allow the writes C6 needs | `V11__grants.sql:20-33` |
