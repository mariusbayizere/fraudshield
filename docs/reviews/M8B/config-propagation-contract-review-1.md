# Review 1: risk configuration propagation contract, draft 1

Subject: `docs/architecture/risk-configuration-propagation-contract.md` at `a757ac8` (branch
`m8b/analyst-api`). The reviewer is independent and wrote none of the work. The review is by
reading only: no build, test or container was run. Line references are to `a757ac8`. The OpenAPI
file had uncommitted edits in the worktree, so its lines are cited from `git show
a757ac8:contracts/openapi/fraudshield-api.yaml`.

## Verdict: UNSOUND (4 MAJOR, 8 MINOR)

The design is close: one snapshot per load, install-if-newer, no announcement channel, and a
database-timed revert are the right shape. Four rules fail as written, though.

- C3 orders snapshots by a timestamp that does not order snapshots (finding 1).
- C6 leaves out the one action that touches an open change without a review lock: a tightening that
  supersedes or folds (finding 2).
- C4's bound ignores how `refreshAll` is actually scheduled (finding 3).
- C9's second path recomputes the policy of a hold that was already scheduled (finding 4).

Each of these four can make a stated property false.

---

## Findings

### 1. MAJOR: C3's "newer" is not snapshot order. An older snapshot can replace a newer one (C3, C2, C1, P2)

**Rule.** C3 installs a snapshot "if its read time is at or after the present one's". The read time
is the load transaction's `now()`. The rule claims: "Read times come from one clock, the primary's,
so 'newer' is total ... a later read sees at least every commit an earlier read saw."

**Why it fails.** `now()` is the transaction *start* timestamp. Under REPEATABLE READ, the snapshot is
taken at the transaction's first statement, which is the `set_config` SELECT
(`adapter/jpa/TenantTransactions.java:381-384`), not at BEGIN. The two events are separate and
nothing bounds the gap between them: the backend can be descheduled, or wait on `ProcArrayLock`. A
load's read time therefore says nothing about which commits its snapshot contains, relative to
another load's.

**Counterexample 1 (start-to-snapshot gap).** Thresholds are at v1, and W is a tightening that
writes v2.

| DB time | Event |
|---|---|
| 10.000 | Refresh load A begins: `now()` = 10.000. Its backend stalls before the first statement |
| 10.001 | First-request load B begins: `now()` = 10.001. It takes its snapshot at once (v1 only) |
| 10.002 | W commits v2 (`effective_at` = 9.990) |
| 10.003 | A takes its snapshot, sees v2 and finishes first. It is installed with read time 10.000 |
| 10.004 | B finishes. Its read time 10.001 ≥ 10.000, so C3 installs B: v1 replaces v2 |

The instance has now used v2 and gone back to v1, until the next refresh. P2 is false. The same
thing happens on a tie, because C3 says "at or after" and two loads can share a microsecond.

**Counterexample 2 (one clock is not one clock over time).** The primary fails over to a replica
whose clock is 3 s behind, or NTP steps the primary's clock back. Every load after that has a read
time below the installed snapshot's, so C3 discards them all for 3 s. A change committed in that
interval is not used until the clock catches up, which breaks C4's bound by the size of the step.
A1 asks for loads on the primary. It does not say that the primary's `now()` never moves backwards
across a failover.

**C1 sub-point.** The `effective_at <= now()` filter in C2 makes things worse. A load whose `now()`
is earlier than a row's `effective_at`, but whose snapshot already contains that committed row,
filters the row out. That needs the same gap: the writer starts and commits between the load's
start and its snapshot. Thresholds and breaker settings are filtered this way, but rules are not
(C2), so such a load is internally "consistent" only by the filter's definition.

`effective_at` is always the writer's `now()`, which is never later than its commit (C1, and V6:82
and V6:112 default it to `now()`). So every visible row is already effective, and the filter only
does harm.

**Fix.**

- (a) Drop the time filter. A version is in force when it is visible: take the highest *visible*
  version of each part. Keep `effective_at` as a record only.
- (b) Order installs by versions, not by read time. Install a snapshot only if each of its three
  versions is ≥ the installed snapshot's. A PostgreSQL snapshot sees a prefix of commit order.
  Threshold and breaker versions are allocated in commit order: by the one-open index together with
  the primary key, or better by the per-(institution, kind) lock of finding 2. The rule-set version
  is allocated in commit order by §3's lock. So a snapshot taken later is componentwise ≥.
  - If neither snapshot is componentwise ≥ the other, that is a bug: keep the present snapshot and
    count it.
  - Keep the read time only for the age metric.
- (c) Restate C3 and P2 in terms of versions. Add a test that injects a load with an earlier
  `now()` but a later snapshot.

### 2. MAJOR: C6's locking omits supersede and fold. A confirmed change can be reverted, and P6 is false for folded changes (C6, P6)

**Rule.** C6 serialises the sweep, `approveConfigChange` and `rejectConfigChange` on the change
row. It claims "a tightening is confirmed, or reverted, never both" (P6: "A tightening past
`confirm_by` is reverted once, never also confirmed").

**What is left out.** The frozen contract and ADR 0014 §3 give a tightening proposal the power to
close another open change:

- it supersedes an open loosening;
- it *folds* an unconfirmed tightening, keeping that change's baseline and getting a new 24-hour
  deadline.

The sources are `fraudshield-api.yaml:1515-1519` and `:1582-1586` at `a757ac8`,
`docs/adr/0014-staff-authorisation-model.md:63-68`, and `DualControlWorkflow.java:140-155`, where
`baseline = existing.previous()`. The contract never mentions either. `proposeThresholdChange` and
`proposeCircuitBreakerChange` take no lock in C6.

**Counterexample A (confirmed, then reverted).** T1 is a tightening, APPLIED_PENDING_CONFIRMATION,
with previous settings S0. Its proposer, officer A, proposes a further tightening T2. Concurrently,
officer B confirms T1.

1. B: `SELECT … FOR UPDATE` on T1, then `UPDATE status = 'CONFIRMED'`. Not yet committed.
2. A (READ COMMITTED): a plain SELECT finds T1 open, so it folds: baseline = T1.previous = S0. Its
   `UPDATE config_changes SET status = 'SUPERSEDED' WHERE id = T1 AND status =
   'APPLIED_PENDING_CONFIRMATION'` blocks.
3. B commits. A's UPDATE re-checks the row and matches 0 rows. Nothing in the contract says A must
   check the row count. A inserts T2 (APPLIED_PENDING_CONFIRMATION, previous = S0). The
   one-open index allows it, because T1 is no longer open.
4. Nobody confirms T2. The sweep reverts T2 and writes S0 as a new version.

T1 was CONFIRMED, and its settings were reverted without any officer's decision.

V6's guard trigger (V6:68-70) saves this only if the UPDATE omits the status predicate. In that case
it raises `insufficient_privilege`, which is a 500 rather than a 409. The contract must not depend on
how the UPDATE happens to be written.

The same race with a loosening L being approved has a similar effect.

- T's `proposed` is built from the pre-L settings: the PATCH merges "channels not listed keep their
  current values" (`:1506-1507`). Applying T silently undoes L's approved loosening on the channels
  T did not list.
- T's revert restores the pre-L settings.
- The base-version check ("the proposal must name the version in effect", `DualControlWorkflow:128`)
  is only meaningful under a lock. C6 gives it none.

**Counterexample B (P6 false as stated).** This is the house test
`DualControlWorkflowTest.secondTighteningFoldsInTheFirstSoOneRevertRestoresTheBaseline`
(`DualControlWorkflowTest.java:317-342`).

1. T1 is applied at 0 h. The same officer folds it into T2 at 20 h.
2. T1 passes its `confirm_by` (24 h) without being confirmed or reverted. It is SUPERSEDED, and its
   settings stay in force until 44 h.

Repeated every 23 h by one officer, an unconfirmed tightening stays in force indefinitely. This is
the owner's decided semantics, but P6 as written says the opposite.

C6's "every action first reverts the overdue changes … in its own transaction" also leaves a gap.
T1 can become overdue between that transaction and the proposal's transaction. The proposal then
folds an overdue T1 instead of letting it be reverted, which extends its life past its deadline.

**Fix.**

- Every action that closes or creates a change (propose, approve, reject, withdraw, sweep) first
  takes a per-(institution, kind) transaction lock, for example `pg_advisory_xact_lock`, or
  `FOR UPDATE` on a per-kind head row. Then, under that lock and in READ COMMITTED:
  - it reverts overdue changes, in the same transaction;
  - it re-reads the open change and the current version;
  - it checks `base_version`;
  - it decides by the *status read under the lock*.
- The same lock gives version allocation in commit order, which finding 1 needs.
- Restate P6 for folding. Two possible wordings:
  - "an applied tightening's settings stay in force without confirmation only until the
    `confirm_by` of the change that currently carries them";
  - "a change is CONFIRMED, or its baseline is restored, never both".
- Add race tests: confirm vs fold, approve-loosening vs supersede, and sweep vs fold.

### 3. MAJOR: C4's bound R + 2L does not describe how refresh runs (C4, P4)

**Rule.** A change committing at t is used after `t + R + 2L`, where L is "the duration of one
snapshot load", and that is "about 6 s in the worst case".

**Why it fails.**

- `refreshAll` loads every cached institution sequentially in one task (`JpaConfiguration.java:117-127`).
  The fixed delay R runs from the end of the whole loop. For one institution, the time between two
  loads is therefore R + Σ L over all cached institutions, not R + L.
- A load that waits for a pooled connection, or fails only after the pool's connection timeout,
  delays every institution after it.
- The task shares a 2-thread scheduler with the 100 ms hold tick, the hold reconcile, the breaker
  monitor and the unblock reconcile (`BackgroundTasks.java:81-87`). C6 adds the config sweep to the
  same pool. When two of those tasks are slow, refresh does not start on time.
- The breaker monitor itself triggers synchronous first loads (finding 8). Every instance evaluates
  every institution's active MCCs from shared Redis, so every instance tends to cache every
  institution with traffic.

**Counterexample.** An instance has 300 cached institutions. Each load takes 40 ms on a loaded
primary, so one loop takes 12 s. A change committed just after institution k's load is used after
about 12 + 5 + 12 = 29 s. That is far outside "about 6 s", although inside 60 s.

With one institution whose loads hang for a 30 s connection timeout, every other institution goes
past 60 s: the E.6 requirement fails, not only the target.

**Fix.**

- State the bound in terms of the *cycle*, not one load: t + R + 2C, where C is the longest
  refresh-loop duration. Or schedule refresh per institution: one task per institution with a
  per-load timeout, a dedicated thread or pool, and load timeout < R.
- Export the cycle time as well as the age.
- P4's test must run with N institutions and one slow load.

### 4. MAJOR: C9's overdue sweep recomputes the policy of a scheduled hold, from JVM time (C9, P8)

**Rule.** "A policy change applies to holds created after its install, never to holds already
waiting" (P8). C9 itself says the Redis entry carries the policy, and that "the overdue sweep
computes it from the version in force at `decided_at`".

**Why it fails.** The two paths do not agree.

- The Redis entry carries `threshold.timeoutPolicy()` from the snapshot the decision used
  (`DecisionService.java:253-260`, `RedisHoldSchedule.java:85`).
- The reconciliation sweep instead selects the policy of
  `max(version) WHERE effective_at <= h.decided_at` (`JdbcOverdueHolds.java:30-34`).
- `decided_at` is the deciding instance's JVM clock (`DecisionService.java:186`), which is exactly
  the skew that C1 and C2 remove elsewhere.

**Counterexample.**

1. Policy change v2 (RELEASE → DECLINE_AND_VERIFY) commits with `effective_at` = 12:00:00.000.
2. Instance I still has its v1 snapshot (it has not refreshed yet). At JVM time 12:00:02 it holds a
   transaction and schedules it in Redis with RELEASE.
3. Redis fails over empty, which is the case V62 exists for (V62:3-8). The sweep then times the hold
   out with DECLINE_AND_VERIFY, because v2 was "in force at `decided_at`".

The policy of an already-scheduled hold has changed, so P8 is false.

With a skewed JVM clock, the converse also happens: a hold decided under v2 is swept under v1.

**Fix.** Record the decision's thresholds version, or the policy itself, on `decision_states`, which
C5 already wants recorded. The sweep then reads that value and never recomputes it. P8's test must
cover the reconciliation path, not only Redis.

### 5. MINOR: C5 needs a port change and a contract change it does not mention (C5, P5)

- **The decision reads two snapshots.** It calls `configuration.thresholds(institution)` and later
  `configuration.rules(institution)` (`DecisionService.java:172, 184`). Each call does its own
  `snapshots.get` (`JpaConfiguration.java:98-105, 161-163`). A refresh installed between the two
  calls gives one decision thresholds and rules from different snapshots. That is an existing
  defect, and §1 does not list it.
  - Fix: `ConfigurationPort` returns one snapshot object, read once per decision.
- **"Recorded today" holds only for Kafka.** The thresholds version is recorded only in the
  `fs.transactions.scored` payload, not in PostgreSQL (V3 has no such column).
  - That payload is closed (`additionalProperties: false`), and its `thresholds_version` has
    `minimum: 1` (`contracts/kafka/schemas/transaction-scored.schema.json:26, 117-119`).
  - `KafkaMessages.java:454` therefore writes `Math.max(1, version)`. A decision on SRS defaults
    (version 0) is reported as version 1, which is indistinguishable from a real seeded version 1.
- **Where the other two versions go.** Recording the rule-set and breaker versions requires a Kafka
  contract change, and a column if they are to be queried. The contract must say where they go, and
  how version 0 is represented.

### 6. MINOR: Version 0 (defaults) is not handled (§2, C1, C5)

- **Nobody writes version 1.** An institution with no rows is decided on SRS defaults at version 0
  (`JpaConfiguration.java:43-46, 189-194, 222-224`). But V6 requires `base_version >= 1` (V6:18),
  and only version 1 may have no `config_change_id` (V6:85, V6:115). No production code inserts
  version 1: a grep finds no writer.
  - So the first proposal for such an institution cannot name the version in effect.
  - The contract must say who seeds version 1, and that it equals the SRS defaults, so the seed is
    not itself a change.
- **Pre-existing defect: breaker events on defaults violate a foreign key.** The breaker monitor
  records `settings.version()`, which is 0 on defaults (`CircuitBreakerMonitor.java:73`). That value
  is inserted into `mcc_circuit_breaker_events.settings_version` (`PostgresSink.java:478`), which
  has a foreign key to the versions table (V6:127-129). The insert fails: `ON CONFLICT DO NOTHING`
  does not cover foreign keys.
  - So a breaker that opens on default settings cannot be recorded.
  - This should be filed separately. Any new foreign-key version column for C5 would inherit it.
- **A partial version is silently completed.** `loadThresholds` fills any channel missing from a
  version with the SRS default (`JpaConfiguration.java:189-193`), and V6 does not require all six
  channels per version. A writer that stored only the changed channels would silently reset the
  others to defaults.
  - The contract should require every threshold version to be complete.

### 7. MINOR: C6's refusal is argued from the wrong clock, and C6 has other loose ends (C6)

- **DB time is the transaction's start.** Under the lock, "DB time" is the confirming transaction's
  `now()`, its start time. A confirmation that started before `confirm_by` and waited on the
  sweep's lock passes the time check after the sweep has reverted the change.
  - "Never both" then holds only because of V6's guard trigger (V6:68-70). The caller gets a 500,
    not a 409.
  - C6 should require the status to be re-read under the lock.
- **Rejection after the deadline.** The frozen contract refuses a *rejection* after the deadline too
  (`:1749`), but C6 refuses only a confirmation. A rejection that wins the race with the sweep
  records cause REJECTED instead of NOT_CONFIRMED_IN_TIME.
- **The lister's time parameter.** The SECURITY DEFINER lister should compare with `now()` itself.
  V62's pattern takes `p_before` from the JVM (`JdbcOverdueHolds.java:61`, V62:13). A lagging JVM
  clock would delay the sweep and break the lateness bound.
- **Audit-chain refusals.** The sweep writes an audit row. V8 refuses, with 40001, a transaction
  that started before the last row of its chain (V8:10-12, V8:91-94). The decision module's
  `TenantTransactions.as` does not retry (`adapter/jpa/TenantTransactions.java:378-387`).
  - C6 must name a runner that retries, such as the audit module's `inTenant`
    (`audit/jdbc/TenantTransactions.java:56-74`).
  - It must also keep the transaction short, or the sweep can keep failing on a busy chain
    partition.
- **The actor.** ADR 0014 §3 (line 57) says the revert's audit event has a *system actor*. C6 says
  "no actor". Pick one.

### 8. MINOR: C8's compare-and-set does not stop a stale instance from undoing a new setting (C8)

- **Stale instances close breakers the new setting keeps open.** The monitor runs on every instance
  (`BackgroundTasks.java:85`; the class Javadoc says "concurrent monitors on several instances").
  Example:
  1. A tightening lengthens `clean_reset` from 60 to 120 min.
  2. An open breaker last breached 70 min ago.
  3. An instance that has not yet installed the change closes it under the old 60 min
     (`MccCircuitBreaker.java:102-105`). The compare-and-set accepts this.
  4. The instances on new settings do not reopen it without a fresh breach.

  The stale use falls inside the propagation window, but its effect lasts after it. "Re-evaluated
  under the new ones" is therefore not guaranteed.
  - Fix: store the settings version in the breaker state. An evaluation with an older settings
    version may not change it. Alternatively, run the monitor on the leader only.
- **One cold load stops all evaluation.** `configuration.breakerSettings` performs a synchronous
  first load inside the monitor loop (`CircuitBreakerMonitor.java:54-55` → `JpaConfiguration.java:166`).
  With PostgreSQL down, one uncached institution throws out of `evaluate()` and stops evaluation of
  every other MCC on that instance.

### 9. MINOR: The rule-set version scheme needs three more sentences (§3, C7, P7)

- **The lock must be transaction-scoped.** It must be `pg_advisory_xact_lock`: a session lock
  survives on a pooled connection.
- **The order matters.** The lock must be taken before `max(version)` is read, and the writer must
  run in READ COMMITTED.
  - In REPEATABLE READ, the snapshot can predate the lock holder's commit, so `max + 1` repeats a
    version. The primary key catches it, but as an error, not as serialisation.
- **RLS is fine.** `max` under RLS sees only the tenant's rows. A hash collision between two
  institutions' lock keys only serialises them.
- **Edits can be lost.** `createRuleVersion` carries no expected version: `RuleDraft`, `:3691-3696`,
  has no `version`. Two officers who both edit version 3 are serialised by `FOR UPDATE`, and the
  second silently replaces the first. That is a lost update, outside propagation but in C7's
  "Concurrent edits".
- **A future writer will break the optimistic lock.** `trigger_count` and `last_triggered_at` are
  published in the contract (`:3717-3722` area) but nothing writes them. Whoever adds that writer
  must not bump `alert_rules.version`, or every `setRuleState` gets spurious 409s.

### 10. MINOR: Settings that affect decisions but are outside the contract, and unhandled cases (scope)

- **The anomaly-review threshold.** It decides the ANOMALY queue (`DecisionEngine.java:95-98`, which
  uses `>=`). It is a static per-process property (`DecisionWiring.java:297`), so instances can
  differ indefinitely, and it is not recorded on the decision. The scorer's hash also carries an
  `anomaly_review` with `>` (`thresholds.py:40, 53`). The contract should say this setting is out of
  scope, deploy-time and uniform, or bring it in.
- **Institutions that are never cached, or removed.** The snapshot map is never evicted. A suspended
  or removed institution is reloaded every R forever, and a load under RLS for it returns defaults
  silently.
- **The first request needs PostgreSQL.** Q1's premise ("the decision path does not otherwise need
  PostgreSQL synchronously") is false for an institution's first request on each instance
  (`JpaConfiguration.java:166`). After a restart while the database is down, every institution's
  first decision fails.
- **Compiler skew across instances.** During a rolling deploy, instances on different compilers can
  skip different rules under the same rule-set version. C5's record then does not identify the rule
  set that was actually applied. Record the skipped rule ids, or a hash of the compiled set.

### 11. MINOR: Wording that overclaims (C1, C4, P3)

- **`effective_at` is earlier than any use.** It is the writer's start time, so a transaction that
  waited on a lock records a time earlier than any instance could have used the change. Say that it
  marks the earliest possible effect, and that the decision's recorded version is authoritative.
- **P3 overclaims.** "Takes the change at the same moment" is wrong under polling. Instances take
  the change within the same bound, independently of their clocks.
- **The age metric reintroduces skew.** `fs_config_snapshot_age_seconds` subtracts a DB read time
  from the JVM clock, which brings back the skew the contract removes. Measure age on the JVM's
  monotonic clock from the install, plus the load's own duration.

### 12. MINOR: Missing properties

The property table has no test for any of the following:

- the races in finding 2 (fold, supersede, and base-version);
- a rejection after the deadline;
- version 0 and the version-1 seed;
- C8 with stale settings;
- C9's reconciliation path;
- C3 under a clock step or failover;
- C4 with N institutions;
- C7's skipped-rule accounting.

Each of these should be a P-row with a test that fails when its rule is removed.

---

## Factual claims verified correct

| Claim (contract §) | Evidence |
|---|---|
| Per-institution snapshot of thresholds, rules and breaker, swapped whole (§1) | `JpaConfiguration.java:63-64, 71, 171-175` |
| First request loads synchronously (§1) | `JpaConfiguration.java:161-168` |
| `refreshAll` on a fixed delay of 5 s, and a failed reload keeps the previous snapshot (§1) | `BackgroundTasks.java:32, 86, 91`; `JpaConfiguration.java:117-127`. The interval is "5 s plus the loop's duration", not plus one load: see finding 3 |
| `invalidate()` has no caller (§1) | grep: only the declaration at `JpaConfiguration.java:135` |
| `fs.config.changes` is declared, with no producer or consumer in code (§1, §6) | `contracts/kafka/topics.yaml:151-160`; grep of `backend` and `ml` |
| Its schema has no `institution_id` (§6) | `contracts/kafka/schemas/config-change.schema.json` (required: `config_kind`, `version`, `effective_at`; closed) |
| `DualControlWorkflow` is in memory and unwired (§1) | `DualControlWorkflow.java:46-48`; no main-code reference outside its own file |
| Out-of-order installs by `put` (§1) | `JpaConfiguration.java:121, 137, 167` |
| Inconsistent reads (§1) | Three statements in one transaction at default isolation (READ COMMITTED): `JpaConfiguration.java:173-174`; `DecisionWiring.java:212-213` builds the template with no isolation |
| JVM clock decides "in force" (§1) | `JpaConfiguration.java:172`; `ThresholdRepository.java` (`effectiveAt <= :now`); `BreakerSettingsRepository.java` (same) |
| Rule-set version is `max(alert_rules.version)` and can fall (§1) | `JpaConfiguration.java:203`; `RuleRepository.java` (`state = 'ENABLED'`); `AlertRuleEntity.java:28-30` (`@Version`); V4:129 |
| `ConfigurationPort` versions are `risk_threshold_versions.version` and `mcc_circuit_breaker_settings_versions.version` (§2) | `ConfigurationPort.java:17, 25` |
| REPEATABLE READ is possible for the load | The decision module's `TenantTransactions.as` (`adapter/jpa/TenantTransactions.java:378-387`, which is the `tenants.as` that `JpaConfiguration` calls) uses whatever template it is given. That is READ COMMITTED today. A separate `TransactionTemplate` set to REPEATABLE_READ and read-only over the JPA transaction manager would do. The audit module's class has `inTenant`, not `as`, and retries 40001 (`audit/jdbc/TenantTransactions.java:56-74`) |
| V6 `kind` admits no rules (C7) | V6:10 |
| One open change per kind (C6) | V6:47-48 |
| Guard trigger forbids updates to closed changes (C6) | V6:54-75 |
| `confirm_by = proposed_at + 24 h` (C6) | V6:35 |
| `effective_at` defaults to `now()` (C1) | V6:82, V6:112 |
| SECURITY DEFINER lister pattern (C6) | V62:13-26 |
| fs_app may UPDATE `config_changes` and INSERT version rows | V11:20-33 |
| The frozen contract gives rules no dual control (C7) | `fraudshield-api.yaml` `createRule`/`createRuleVersion`/`setRuleState` at `:1266`, `:1321`, `:1348` |
| `setRuleState` carries `version` and has a 409 (C7) | `:1355-1375` |
| `createRule` has a 422 (C7) | `:1266-1287` |
| Approval and rejection of a change past its deadline give 409 `change-not-open` (C6) | `:1713`, `:1749` |
| A window change is a loosening (C8) | `:1588` |
| `DualControlWorkflow.revertOverdue` runs before actions (C6) | `DualControlWorkflow.java:126, 294, 276-290` |
| Uncompilable stored rule is skipped and counted (C7) | `JpaConfiguration.java:212-214` |
| The monitor reads breaker settings at each evaluation; one-minute buckets kept 25 h; window ≤ 1,440 min; compare-and-set (C8) | `CircuitBreakerMonitor.java:53-62`; `RedisCircuitBreakers.java:33, 74`; V6:109 |
| The Redis hold entry carries the policy (C9) | `HoldSchedulePort.java:20-27`; `RedisHoldSchedule.java:85, 129`; `DecisionService.java:253-260` |
| The overdue sweep computes the policy from the version in force at `decided_at` (C9) | `JdbcOverdueHolds.java:30-34`. This is what finding 4 objects to |
| The thresholds version is recorded on decisions (C5) | `DecisionService.java:216`. Kafka only, clamped to ≥ 1 (finding 5) |
| The scorer's hash is one global key, used only for `model_risk_tier` (C10) | `thresholds.py:30`; `scorer.py:98, 107`. Java never reads a model tier (grep). The docstring says the admin API writes it (`thresholds.py:9`), which Q2 records |
| No rollback operation exists. A revert is a new version (C1) | OpenAPI has no rollback operation; `DualControlWorkflow.apply` increments the version (`:324-327`); V6 tables are append-only (V6:87, V6:117) |
