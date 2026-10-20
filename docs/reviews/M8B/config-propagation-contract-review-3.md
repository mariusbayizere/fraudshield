# Review 3: risk configuration propagation contract, draft 3

Subject: `docs/architecture/risk-configuration-propagation-contract.md` at `8831400` (branch
`m8b/analyst-api`), written "contract:" below. The reviewer is independent. It wrote none of the work
and neither of the earlier reviews. The review was done by reading only: no build, test or container
was run. Every line reference is to `8831400` (`git show 8831400:<path>`). The worktree's uncommitted
edits under `contracts/` were ignored.

Paths are abbreviated as follows:
- `decision/…` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/…`;
- `ingest/…` is `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/…`;
- `audit/…` is `backend/audit/src/main/java/io/github/mariusbayizere/fraudshield/audit/…`;
- migrations are under `backend/persistence/src/main/resources/db/migration/`.

The MAJOR test applied is the one the brief sets. A finding is MAJOR only if it can do one of these:
- make an instance use a setting older than one it already used;
- apply a change outside the stated bound;
- confirm and revert the same change;
- deadlock without a retry;
- make a stated property false.

## Verdict: UNSOUND (2 MAJOR, 9 MINOR)

Draft 3 closes most of review 2:
- C6 is now one kind lock per transaction, and a refusal is returned as a value after the revert has
  committed.
- The rule-set version is appended by the database.
- The seed, the completeness triggers, the freshness metric, the arithmetic of C4 and the absence of
  eviction are all correct as written.
- The C3 ordering argument still holds. It survives the move of the RULES lock into a trigger (see
  "Verified claims").

Two rules fail as written:

- **M1. The leader-only breaker monitor makes every instance's breaker state depend on one instance
  being fresh.** A leader whose own refresh is failing, or that is still behind, keeps the lease,
  because the lease is renewed through Redis alone. It then evaluates every breaker under old
  settings: it undoes a change that an earlier leader had already applied, or never applies it.
  - This lasts for as long as the leader stays behind, while every other instance is healthy and
    quiet.
  - The breaker bound in C8, and the claim that a stale transition "lasts at most until the
    leader's first evaluation after it installs the new version", both assume the leader installs.
    Nothing makes it.
- **M2. The RULES lock is taken inside AFTER triggers, after the row write, so rule writes can
  deadlock. No retry is specified for them.** `setRuleState` racing `createRuleVersion` on one rule
  forms a cycle between the rule's row lock and the RULES advisory lock. C6's retrying runner covers
  only dual-control actions and sweep steps. The same lateness also makes C7's "last-writer-wins,
  both stay" false: the second edit fails with a unique violation.

Each fix is a few sentences: a monitor lease conditioned on freshness, and the RULES lock taken
first plus 40P01 retried for rule writes. The rest of the draft is sound in substance.

---

## Review 2's findings

| # | Finding (review 2) | Status | Why |
|---|---|---|---|
| N1 | MAJOR: a stale instance closes a breaker, and nothing reopens it | **PARTLY** | Only the leader writes now (contract:257-260), and a leader holding a newer settings version re-decides the breaker (:263-267). That reopens the original counterexample's breaker once the leader installs v2. But N1's core, a stale evaluator undoing a change, survives whenever the stale evaluator *is* the leader, and nothing bounds how long it stays stale (M1). The re-decision function is also undefined in places (m2). |
| N2 | MAJOR: two kind locks, deadlock, 40P01 not retried | **RESOLVED** | "Exactly one kind lock" per action and per sweep step (:203-205, :227-229). The lister orders its pairs (:131-132). The audit head comes last (:232-233). 40001 and 40P01 are retried, and `inTenant` gains 40P01 (:230-231). This holds for C6 transactions. Rule writes are outside the argument (M2). The FK key-share locks are a wording gap only (m9). |
| N3 | MAJOR: a lost commit and a reused version number | **RESOLVED (by assumption)** | A2, plus a runbook restart (:81-86, :317). Adequacy is in m5: "lossy failover" cannot be detected reliably, so the trigger should be "any failover or restore". |
| N4 | The age alert fires for quiet institutions | **RESOLVED** | "Confirmed" means the probe matched or a load succeeded. It is measured on the monotonic clock and exported as a per-instance maximum (:177-178, :187-190). P13 covers it. |
| N5 | A version with no rows; existing data unchecked | **RESOLVED** | There are deferred triggers on both tables, and V81 refuses to run on an incomplete version (:104-107). P9 includes "one inserted with none". |
| N6 | Rule writes that skip the set version; rules from before V81 | **RESOLVED** | Triggers append the set version, V81 seeds version 1, and the probe uses `coalesce(..., 0)` (:114-120). P10 covers it. The trigger placement causes a new problem (M2). |
| N7 | Threads, T_L, probe timeout, arithmetic, sweep scheduler | **RESOLVED** | There is a K-thread pool, a whole-load deadline, a probe deadline and a sweep scheduler of its own (:71-72, :169-176, :227). The arithmetic is now right: `5 + 2·(2 + 12·2) = 57 ≤ 60`, with `⌈49/4⌉ = 13` giving 61. The enforcement of T_L as written has a mechanical gap (m6). |
| N8 | A refusal rolls back the revert; early timestamps | **RESOLVED** | Step (a) commits by itself. Step (b) returns refusals as values after its own revert (:209-226). `reverted_at` and the audit `event_at` use `clock_timestamp()`, and §3.6 adds a check (:133-134). The revert *version's* `effective_at` (V6:82, :112) was not addressed (m8). |
| N9 | The NULL fallback after V81 | **PARTLY** | The payload is versioned, and the fallback is stated as "NULL" and limited to old-release rows (:121-125, :276-280). But the middle fallback, "the decision's own `thresholds_version` if the row records one", is dead: no table records it (m3). |
| N10 | Decisions use breaker state; the breaker bound | **PARTLY** | C5 and P5 now cover thresholds and rules only (:192-196, :295), and C8 has a bound of its own (:270-271). But the bound's claim "within 60 s on C4's condition" is arithmetically false. The monitor and the follower read loop also have no named scheduler (m1). |
| N11 | Eviction is dead or harmful | **RESOLVED** | There is no eviction, and an empty probe counts as a failure (:163-166, :173-174). |
| N12 | The seeding trigger: owner, fixtures, grants | **RESOLVED** | Owner rights are stated, the fixture is changed, and `GRANT EXECUTE` plus the V71 list apply to both functions (:96-101, :126-127). The new *table* `alert_rule_set_versions` has no grant stated (m4). |
| N13 | The lock is not the first statement | **RESOLVED** | Step (0) pre-reads `kind`, which is immutable (V6:61-67) (:207-208). |
| N14 | P3 overclaims | **RESOLVED** | Restated (:293). |

---

## New findings

### M1. MAJOR: a behind or refresh-failing leader holds back, or undoes, a breaker-settings change for every instance (C8, P11, §6)

**Rule.** C8 (contract:256-271) says:
- "Only the instance holding the lease evaluates and writes breaker state";
- "When the leader holds a newer settings version than a breaker's recorded one, it re-decides";
- "So a transition made under old settings lasts at most until the leader's first evaluation after it
  installs the new version";
- "A settings change reaches decisions by `t + R + 2C + 2·R_monitor`, plus one evaluation … within
  60 s".

§6 (:318-319) accepts that a stale transition "can stand until the leader's next evaluation after it
installs the new ones".

**Why it fails.**
- The lease is M6's hold lease: 2 s (`decision/adapter/redis/RedisHoldSchedule.java:24`), taken and
  renewed by a Lua script on Redis alone (`:53-60, :136-147`). It is renewed by the hold tick every
  100 ms (`ingest/config/BackgroundTasks.java:29, 83`). Nothing in the lease depends on the holder's
  configuration being fresh.
- Draft 3 states what a leader does when it is *newer* than a breaker's recorded version. It states
  nothing for a leader that is *older* than the recorded version. Such a leader evaluates under its
  old settings and writes.
- Leadership moves often. The hold tick shares a two-thread pool with the breaker monitor, the hold
  sweep and the unblock sweep (`BackgroundTasks.java:80-87`). Draft 3 moves only refresh and the
  config sweep off that pool (:169, :227), so a tick delayed by 2 s hands the lease over. A rolling
  deploy does the same.
- The C4 bound holds only "on every instance whose probe and loads succeed" (:180-181). In C8 a
  single instance's failure or lag now governs the breaker state that *every* instance's decisions
  read (`decision/application/DecisionService.java:185`). Only that one instance's freshness alert
  fires.

**Counterexample (a loosening applied, then undone).** Instances A and B. Breaker settings v1 have a
rate threshold of 5 %. MCC 5999 is open under v1, recorded as v1, and the window's fraud rate is
7 %.

| Time | Event |
|---|---|
| 13:05:00 | Loosening v2 is approved: rate threshold 10 % (ADR 0014 §3, `docs/adr/0014-staff-authorisation-model.md:59-62`) |
| 13:05:00 | B loses its PostgreSQL path (a partition, or its pool exhausted). Its Redis path is fine. B keeps v1 (C4 step 2: "a failed load keeps the old snapshot") |
| 13:05:06 | A, the leader, installs v2. Its version 2 is above the recorded 1, so it re-decides: 7 % does not breach 10 %, and the breaker closes, recorded as v2. Followers read "closed" within 5 s. Decisions now apply v2's effect |
| 13:05:10 | A's hold tick waits more than 2 s behind the monitor on the shared pool, or A is restarted. B's tick takes the lease |
| 13:05:15 | B leads on v1. Closed, and 7 % > 5 % is a breach, so it opens (`decision/domain/MccCircuitBreaker.java:95-100`) and records v1. Followers read "open" |
| later | B renews the lease every 100 ms through Redis. It never installs v2, so it never re-decides. Each 5 % breach moves `last_breach_at` forward, and the breaker stays open |

**The tightening variant.** Clean reset goes from 60 to 120 min, and the leader is B, which is
behind. B closes the breaker at `last_breach_at + 60` and never reopens it. This is review 2's N1
counterexample with B as leader.

**What breaks.**
- Every instance's decisions apply v2's breaker effect and then v1's. That is an older setting than
  one already applied, at the breaker level.
- A change approved by two officers is not in effect on any instance long after `t + 60 s`, while
  every instance except B reports itself fresh.
- C8's bound and its "lasts at most until" sentence hold only when the leader installs, and nothing
  makes it.

**Fix.**
1. Give the monitor a lease of its own, not the hold lease. An instance takes or renews it only
   while its `fs_config_unconfirmed_seconds` is below a limit F, for example `R + 2·C_max`. Beyond F
   it lets the lease lapse, so a fresh instance takes over. Use the raw Redis lease, not the
   resilient wrapper, which returns true without a lease when Redis fails
   (`decision/adapter/resilience/ResilientPorts.java:235-238`).
2. A leader never writes a breaker whose recorded settings version is above its own installed one.
   It first loads that institution synchronously, or skips the breaker and counts it.
3. Add both rules to P11. Restate the breaker bound with the lease-handover term:
   `+ F + lease`.

### M2. MAJOR: the RULES lock is taken after the row write, so rule writes deadlock with no retry stated; C7's "last-writer-wins" is false (§3.3, C7, P10)

**Rule.** §3.3 (:114-118): "AFTER INSERT on `alert_rule_versions`, and AFTER INSERT or UPDATE OF
`state`, `current_version` on `alert_rules`. Each trigger takes the lock of RULES, then appends."

Two other parts of the contract leave rule writes uncovered:
- C6's deadlock argument and its retrying runner cover dual-control actions and sweep steps only
  (:203-205, :230-233).
- C7 (:247-250) says rule writes "are serialised by the RULES lock" and that `createRuleVersion` is
  "last-writer-wins … Both stay in `alert_rule_versions`".

**Why it fails.**
- An AFTER row trigger runs after its statement has written the row and holds that row's lock. So a
  rule write holds the rule's row lock *before* it asks for RULES.
- A writer that has already taken RULES through an earlier statement, the INSERT into
  `alert_rule_versions`, then asks for that row lock.
- The foreign key from `alert_rule_versions` to `alert_rules` (V4:152) takes only FOR KEY SHARE.
  That lock is compatible with an UPDATE of non-key columns, so nothing serialises the two writers
  earlier.

**Counterexample (deadlock).** Rule X. T2 is `createRuleVersion(X)`, which inserts the version row
and then points the header at it. T1 is `setRuleState(X, DISABLED)`.

1. T2: `INSERT INTO alert_rule_versions (rule X, n)`. The FK takes key-share on X. Its AFTER INSERT
   trigger takes RULES.
2. T1: `UPDATE alert_rules SET state = 'DISABLED', version = version + 1 WHERE id = X AND version =
   ?`. This takes the no-key-update row lock on X, which is compatible with T2's key-share. Its
   AFTER UPDATE trigger waits for RULES, which T2 holds.
3. T2: `UPDATE alert_rules SET current_version = n WHERE id = X` waits for T1's row lock.

That is a cycle, and PostgreSQL aborts one of the two with 40P01.
- The decision module's runner retries nothing (`decision/adapter/jpa/TenantTransactions.java`, the
  `as` method).
- Nothing in the contract routes rule writes through the 40P01-retrying `inTenant`.

So one of two officers' edits returns 500.

**Counterexample (C7's claim).** Two concurrent `createRuleVersion(X)` calls both pick the new
version number before any lock: the lock arrives only in the trigger, after the insert. Under READ
COMMITTED both pick n. The second INSERT waits on the first's uncommitted primary key
`(rule_id, version)` (V4:151) and then fails with 23505. So it is not "last-writer-wins, both stay".
Under REPEATABLE READ the trigger's own `max + 1` reuses the transaction snapshot and fails the same
way.

**Commit-order allocation itself is unaffected** (see "Verified claims"). This finding concerns
liveness and C7's wording.

**Fix.**
1. Every rule operation takes the RULES lock as its *first* statement, before any row write or audit
   insert. The trigger's lock call then re-enters a lock the transaction already holds, and stays as
   the backstop for other writers.
2. Rule writes run in READ COMMITTED, through the runner that retries 40001 and 40P01.
3. Version numbers are chosen after the lock. Restate C7: either the second `createRuleVersion` gets
   the next number and becomes current, or it gets 409.
4. Add "setRuleState racing createRuleVersion on one rule" to P10 or P7.

### m1. MINOR: C8's "within 60 s on C4's condition" is false; the monitor and the follower reads have no named scheduler (C8, N10)

- **The arithmetic.** C4's condition allows `R + 2C` up to 57 s (:183-185). C8 adds
  `2·R_monitor = 10 s` plus one evaluation, which gives at least 67 s.
  - The breaker bound stays under 60 s only while `5 + 10 + d_eval + 2(2 + 2⌈N/4⌉) ≤ 60`. That is
    `⌈N/4⌉ ≤ 10`, so N ≤ 40 if `d_eval` < 1 s.
  - `d_eval` itself has no deadline. `counts` issues one HMGET per window minute, up to 1440 of them
    (`RedisCircuitBreakers.java:110-117`; V6:109), for every active MCC.
- **The schedulers.** The monitor still runs on the shared two-thread pool
  (`BackgroundTasks.java:85`), and the follower read loop has no thread at all. Their lateness is
  not in the bound.
- **Fix.** Restate the N limit for breakers (N ≤ 40 at the stated parameters, or measure it). Give
  the monitor and the follower reads a scheduler of their own, and give an evaluation a deadline.

### m2. MINOR: C8's re-decision is undefined, and its "stored history" is not stored for long windows (C8, P11)

- **History.** Buckets live for 25 h (`RedisCircuitBreakers.java:33`). The window can be up to
  1440 min and the clean reset up to 10080 min (V6:109, :111). Re-deciding whether a breaker should
  be open under new settings needs to know whether any breach occurred over the last clean-reset
  period. That needs buckets reaching back as far as clean reset plus window, which is up to 8 days.
  - Analyst-confirmed fraud is also added later to past buckets
    (`RedisCircuitBreakers.java:81-89`), so replaying those buckets does not reproduce what the
    original evaluation saw.
- **Scope.** The recorded version changes only when state changes (:261-262;
  `CircuitBreakerMonitor.java:61-63`). So a re-decision that changes nothing is repeated at every
  evaluation. It also applies retroactively to transitions legitimately made *before* the change
  committed. For example, a longer clean reset reopens a breaker that closed an hour before the
  change. The contract does not say whether that is intended.
- **Fix.** Define the re-decision exactly, using only `opened_at`, `last_breach_at` and the current
  window. For example:
  - reopen a closed breaker if `now < last_breach_at + clean_reset_new`;
  - close an open breaker if the current window does not breach under the new settings.

  Record the evaluator's settings version on every evaluation, in a separate field (review 2's N1
  fix). State whether re-decision is retroactive.

### m3. MINOR: C9's second fallback cannot run; no table records the decision's `thresholds_version` (C9, N9)

`decision_states` has no such column (V3:95-114). No migration other than V6 has a threshold
column. The version exists only on the Kafka message (`decision/adapter/events/KafkaMessages.java:454`).
So the sweep's choice is: the stored policy, or today's recomputation (`JdbcOverdueHolds.java:28-40`).

The residual stated in §6 and C9 is still true, because NULL appears only on rows the old release
wrote. The sentence describes a mechanism that does not exist, though.

**Fix.** Delete the clause, or add `thresholds_version` to `decision_states` in V81.

### m4. MINOR: `alert_rule_set_versions` has no grant stated (§3.3, §3.5)

The §3.3 triggers are not declared SECURITY DEFINER, so they run as the writing role. `fs_app` then
needs SELECT and INSERT on the new table (`V11__grants.sql:20-27` is the pattern). Without them every
rule write fails. `fs_compliance_ro` and `fs_app_readonly` probably want SELECT too (`V11:56-71`).

**Fix.** Either list the grants, or make the trigger function SECURITY DEFINER with a fixed
`search_path`, as V8's chain trigger is (`V8__audit_log.sql:73-75`).

### m5. MINOR: A2's runbook trigger, "lossy failover", cannot be detected reliably (A2, N3)

PostgreSQL synchronous replication still lets a commit become visible on the primary without the
standby having it in three cases:
- when the commit's wait for the standby is cancelled or the backend is terminated (the transaction
  is then "committed locally");
- when a session or role lowers `synchronous_commit`;
- with quorum `ANY` and a failover to a standby outside the quorum.

An operator cannot tell whether a given failover lost something.

**Fix.** Restart every instance after *every* failover or restore, which is a timeline change. Or
adopt review 2's epoch of system identifier plus timeline. State also that `synchronous_commit`
must not be lowered.

### m6. MINOR: T_L "as a transaction timeout covering acquire, every statement and commit" is not what a transaction timeout does (C4, P4)

A Spring transaction timeout bounds statements only, through the JDBC query timeout. It does not
bound connection acquisition or COMMIT, and a socket read with no `socketTimeout` blocks forever.

- A hung load therefore keeps one of the K threads and one of the K connections. After K hangs,
  refresh loads nothing, and P4 ("one whose loads hang") fails.
- The same applies to T_P.

**Fix.** State the mechanism:
- a client-side deadline on the future;
- `SET LOCAL statement_timeout` and a driver `socketTimeout` no longer than T_L;
- a pool acquire timeout;
- a timed-out load's connection is aborted, so its thread and slot are freed.

### m7. MINOR: P6's revert bound `confirm_by + R_sweep + d` leaves out the sweep's own length and lock waits (C6, P6)

The sweep runs at a fixed delay on one thread (:227-229). A change that becomes overdue just after a
lister call is reverted only after three waits:
- the rest of that sweep's steps;
- R_sweep;
- every step listed before it in the next sweep.

Each step may also wait on its kind lock behind an action, or retry on 40001.

**Fix.** State the bound as `confirm_by + D_prev + R_sweep + Σ steps before it`, or bound the number
of steps per sweep. Measure the lag and alert on it.

### m8. MINOR: a revert version's `effective_at` can predate `confirm_by` (C6, N8 residue)

`effective_at` defaults to `now()`, the transaction's start (V6:82, :112). An action whose (b)
transaction began before `confirm_by` and waited on the kind lock reverts in (b) after the deadline.
It then writes a revert version dated before the deadline. C1 says that `effective_at` "records the
earliest possible effect" (:145), and an examiner reads it that way.

**Fix.** Set it to `clock_timestamp()` in reverts. Optionally add a check that a
NOT_CONFIRMED_IN_TIME revert's version has `effective_at ≥ confirm_by`.

### m9. MINOR: C6's lock inventory omits foreign-key locks; "audit last" must also hold for a fold's two audit events (C6)

The claim "the only locks taken after a kind lock are rows of that kind, and the audit chain head"
(:232-233) is incomplete:
- inserting `config_changes` takes key-share on `users` through `proposed_by` (V6:28);
- version rows take key-share on `institutions` (V6:79, :105).

`StaffAccountRepository` locks `users` rows FOR UPDATE and then audits
(`backend/auth/.../StaffAccountRepository.java:109-119`). Suppose a fold writes its SUPERSEDED audit
before inserting the new change, as `DualControlWorkflow.java:152-157` orders it in memory. It then
holds a chain head while waiting on a `users` row whose holder waits on that head. 40P01 is now
retried, so this is not MAJOR.

**Fix.** Name the foreign-key locks. Say that every audit insert, including both of a fold's, comes
after every row write.

---

## Verified claims

| Claim (contract §) | Evidence |
|---|---|
| **C3 ordering survives the trigger-held RULES lock** | A transaction-scoped advisory lock is released after the commit becomes visible. In READ COMMITTED, each statement of a volatile PL/pgSQL trigger takes a fresh snapshot, so the `max` read *after* the lock sees the predecessor's commit. Independently, `max + 1` together with the primary key `(institution_id, version)` cannot invert commit order: a writer that read `max = v` and inserts `v + 1` collides with any concurrent `v + 1` and waits for it, so it fails rather than committing out of order. So the rule-set version `v` is visible only together with every `v' < v`, and a later snapshot is componentwise ≥. The only exception is REPEATABLE READ rule writers, which fail with 23505 rather than misorder (M2). |
| The triggers cover every field a load reads | A load reads `state` and `current_version` from `alert_rules`, and `rule_expression` and `risk_tier_override` from append-only `alert_rule_versions` (V4:123-156; `JpaConfiguration.java:197-217`). `fs_app` has no DELETE on `alert_rules` (`V11:30-33`), and deletion is `state = 'DELETED'` (V4:127). So no unseen rule write remains |
| C6's (a)/(b)/(c) split is consistent | (a) commits by itself, under the lock. (b) retakes the lock and re-reads, so whatever ran in between is seen. A refusal is a value, so (b)'s own revert commits. A 40001 or 40P01 retry reruns (b) whole. Confirm and revert cannot both happen: each runs under the kind lock, and each decides on a status re-read under it (V6 guard: `:68-70`). The overdue check uses `clock_timestamp()`, consistent with ADR 0014's "exactly 24 hours and not one nanosecond before" (`docs/adr/0014-staff-authorisation-model.md:80`; `DualControlWorkflow.java:280-281`) |
| §3.6's check is null-safe | Rows that are not reverted have a NULL `revert_cause`, so the check evaluates to NULL and passes (V6:27, :41-42) |
| C4 arithmetic | `5 + 2·(2 + ⌈48/4⌉·2) = 57`, and N = 49 gives 61 |
| §3.1 defaults | `JpaConfiguration.java:52-58` (0.60, 0.85, RELEASE_WITH_TIMEOUT_LABEL; 0.05, 15 min, 100, 60 min). V6 allows version 1 without `config_change_id` (V6:85, :115) |
| §1 inventory | `put` (`JpaConfiguration.java:121, 137, 167`); JVM clock (`:172`); `max(alert_rules.version)` (`:203`); version 0 with defaults (`:179, 189-194, 222-224`); `invalidate` and `fs.config.changes` have no caller in main code (grep); two reads per decision (`DecisionService.java:172, 184`) and `isOpen` (`:185`); monitor on every instance, on the shared pool (`BackgroundTasks.java:80-87`); `isOpen` is a local copy refreshed by this instance's own `state`/CAS (`RedisCircuitBreakers.java:61-64, 131-177`); the sweep recomputes the policy from `decided_at` (`JdbcOverdueHolds.java:28-40`) |
| The Redis hold carries the decision's policy | `DecisionService.java:252-260` |
| Only the thresholds version is recorded per decision, clamped to ≥ 1 | `KafkaMessages.java:454` |
| `inTenant` retries only 40001 today; V8 refuses with 40001 | `audit/jdbc/TenantTransactions.java:58-73, 91-97`; `V8__audit_log.sql:92-95` |
| The audit partition is the thread id mod 64 unless configured | `audit/jdbc/JdbcAuditLog.java:80-84` |
| `kind` is immutable, so the step (0) pre-read is safe | `V6__risk_configuration.sql:61-67` |
| Fold, supersede and revert-to-`previous` | `DualControlWorkflow.java:140-155, 276-289`; V6 one-open index (`:47-48`) |
| V62's lister pattern; V71 requires an explicit EXECUTE grant | `V62__hold_reconciliation.sql:13-26`; `V71__close_new_functions_to_public.sql:1-12` |
