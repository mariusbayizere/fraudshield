# Risk configuration: the propagation contract

Status: **draft 3, for independent review.**

**Review history.**
- Review 1 found draft 1 UNSOUND (4 MAJOR, 8 MINOR): `docs/reviews/M8B/config-propagation-contract-review-1.md`.
- Review 2 found draft 2 UNSOUND (3 MAJOR, 11 MINOR): `docs/reviews/M8B/config-propagation-contract-review-2.md`.
  It confirmed that review 1's two ordering MAJORs are resolved.
- §8 maps every finding of review 2 to its answer.

**If draft 3 is found unsound, the author stops and takes the design to the owner** rather than
writing a fourth draft. That was the owner's rule for M7's cache contract.

Code is written against this document, not the other way round.

**Scope.** This contract governs three things:
- how an approved threshold or circuit-breaker change (P4) and a rule change (P5) reach every
  running decision instance;
- the timed transitions that make a setting take effect or lose it;
- the locking that every dual-control action needs so those transitions stay consistent.

It does not govern who may propose, approve or reject. That is ADR 0014 §3, implemented by
`DualControlWorkflow`'s rules.

**Sources:**
- FR-05-05, FR-05-07, FR-03-07, and E.6: effective within 60 s, target 5 s;
- ADR 0014 §3;
- the contract's threshold, breaker, config-change and rule operations;
- V4, V6, V8, V11, V62 and V71;
- in the decision module: `JpaConfiguration`, `ConfigurationPort`, `DecisionService`,
  `BackgroundTasks`, `CircuitBreakerMonitor`, `RedisCircuitBreakers`, `MccCircuitBreaker`,
  `DualControlWorkflow`, `JdbcOverdueHolds` and `PostgresSink`;
- the Python scorer's `serving/thresholds.py`.

## 1. What exists

- **The snapshot.** The decision path reads a per-institution snapshot of thresholds, rules and
  breaker settings (`JpaConfiguration`).
  - An institution's first request loads its snapshot synchronously.
  - `refreshAll` reloads every cached institution one after another, then waits 5 s. It runs on a
    two-thread scheduler shared with four other tasks.
  - A failed reload keeps the previous snapshot.
- **Breaker settings and decisions.** The breaker monitor reads breaker settings and runs on every
  instance. Decisions read only each instance's local copy of breaker state (`isOpen`), which that
  instance's own monitor evaluation refreshes.
- **Nothing else propagates.**
  - `fs.config.changes` has no producer or consumer.
  - `invalidate()` has no caller.
  - `DualControlWorkflow` is in memory and unwired.
- **Defects this contract removes:**
  - **Install order:** a snapshot is installed with `put`, so a slower, older load can overwrite a
    newer one.
  - **Consistency:** a load makes three reads at READ COMMITTED, so they can see different commits.
  - **Clock:** "in force" is decided against the JVM clock.
  - **Rule-set version:** it can fall, because it is `max(alert_rules.version)`.
  - **Mixed snapshots:** a decision reads thresholds and rules in two calls, so one decision can
    mix two snapshots.
  - **No version 1:** nothing writes version 1, so institutions run at "version 0". That version
    cannot be named as a base version, and it breaks the foreign key on
    `mcc_circuit_breaker_events`.
  - **Recomputed policy:** the overdue-hold sweep recomputes a hold's timeout policy from JVM time.
  - **Racing monitors:** every instance's monitor writes breaker state, including instances that
    have not installed a change yet.

## 2. Terms and assumptions

| Term | Meaning |
|---|---|
| **R** | refresh period, 5 s (configurable) |
| **K** | refresh worker threads, 4 |
| **T_L** | deadline for one whole load, 2 s: a transaction timeout covering acquire, every statement and commit |
| **T_P** | deadline for one probe, 2 s |
| **C** | one refresh cycle's duration: probe plus loads (C4). Measured and exported |
| **part** | thresholds, breaker settings or rules |
| **version of a part** | `risk_threshold_versions.version`, `mcc_circuit_breaker_settings_versions.version`, or the rule-set version (§3.3) |
| **visible** | committed before the reading transaction's snapshot |
| **the lock of a kind** | `pg_advisory_xact_lock(('risk_config', institution, kind))`, kind ∈ {CHANNEL_THRESHOLDS, MCC_CIRCUIT_BREAKER, RULES}. Transaction-scoped. **A transaction takes at most one such lock.** |
| **the leader** | the instance holding M6's 2 s Redis lease (`acquireLeadership`), already used by the hold poller |

- **A1.** Every transaction in this contract runs on the primary.
- **A2. No committed transaction is lost.** Replication is synchronous, and the database is never
  restored to an earlier point while instances run.
  - Snapshots and versions are memory-only. After any restore or lossy failover, every API instance
    is restarted, which clears them. This is a runbook item for M9.
  - Without A2, a lost version's number can be reused, and an instance that installed the lost
    version would keep it.

## 3. Schema additions (V81, M8B's range)

1. **Version 1 is seeded.** V81 inserts version 1 for every institution with no versions:
   - thresholds for all six channels at the SRS defaults (0.60, 0.85,
     RELEASE_WITH_TIMEOUT_LABEL);
   - breaker settings 5 %, 15 min, 100 transactions, 60 min.

   `config_change_id` is NULL, as V6 allows for version 1 only.
   - **For new institutions,** an AFTER INSERT trigger on `institutions` does the same.
     - Institutions are inserted only by the owner, so the trigger runs as the owner and row-level
       security does not apply. If an onboarding role is ever added, the trigger becomes SECURITY
       DEFINER with a fixed `search_path`, as V8's chain trigger is.
     - The decision test fixture, which inserts version 1 itself, is changed to rely on the
       trigger.
   - **Version 0 never occurs again.** A load that finds no version raises an error, which is
     counted, rather than using defaults.
2. **Every threshold version is complete.** Two deferred constraint triggers refuse to commit a
   threshold version without exactly six channel rows: one on `risk_threshold_versions`, so a
   version with no rows is caught, and one on `risk_thresholds`. V81 refuses to run if an existing
   version is incomplete.
3. **The rule-set version.** A new append-only table, `alert_rule_set_versions`:
   - columns: `institution_id`, `version` bigint, `changed_at`, `rule_id`, and `change` (CREATED,
     VERSIONED, ENABLED, DISABLED or DELETED);
   - primary key `(institution_id, version)`;
   - tenant-isolated.

   **The database appends it, not the application.** Triggers do the appending: AFTER INSERT on
   `alert_rule_versions`, and AFTER INSERT or UPDATE OF `state`, `current_version` on `alert_rules`.
   Each trigger takes the lock of RULES, then appends `coalesce(max, 0) + 1`. So no write can change
   the rule set without raising the version, and versions are allocated in commit order. The lock
   is released after the commit becomes visible, so the next writer sees it.
   - V81 seeds version 1 for every institution that already has rules.
   - The probe returns `coalesce(max, 0)`.
4. **`decision_states` gains `timeout_policy text`.**
   - It is written on HOLD rows by `PostgresSink` from the decision event. The event payload gains
     the field, in a new payload version.
   - It is NULL on rows the older release wrote. That includes records spooled before the upgrade
     and drained after it.
5. **Two SECURITY DEFINER functions, each with `GRANT EXECUTE` to `fs_app` and an entry in V71's
   pinned list:**
   - `risk_configuration_versions()` returns `(institution_id, thresholds_version,
     breaker_version, rule_set_version)` for every institution. It returns numbers only.
   - `overdue_config_changes()` returns `(institution_id, kind)` pairs that have an
     APPLIED_PENDING_CONFIRMATION change past `confirm_by`. It compares with `clock_timestamp()`
     and orders the pairs by `(institution_id, kind)`.
6. **`config_changes` gains a check:** `revert_cause <> 'NOT_CONFIRMED_IN_TIME' OR reverted_at >=
   confirm_by`.

## 4. Rules

**C1. A change is in force when its version row is visible.**
- **Which writes create a version.** Each is written in the same transaction as the change that
  causes it:
  - a tightening is applied;
  - a loosening is approved;
  - a tightening is rejected or reverted, which writes the previous settings as a new version;
  - a rule is written (§3.3).
- `effective_at` records the earliest possible effect. No load compares it with any clock.
- What decided a transaction is the version the decision recorded (C5).
- No change is announced by message.

**C2. One load reads one consistent snapshot.** A load is one `REPEATABLE READ READ ONLY` tenant
transaction, with the deadline T_L. It reads, with no time filter:
- the highest visible threshold version and its six rows;
- the highest visible breaker version;
- the highest visible rule-set version, and the enabled rules at their current versions.

**C3. A snapshot is installed only if no part goes back.** Installation is one atomic step on the
institution's map entry (`ConcurrentHashMap.compute`).
- **The rule.** A snapshot is installed if the entry has none, or if each of its three versions is
  ≥ the installed snapshot's. Otherwise it is discarded.
- **Why it holds.** Under A1 and A2, versions are allocated in commit order: by the lock of a kind
  (C6), the rule-set triggers (§3.3) and the primary keys. A later snapshot sees a superset of
  commits. So of two loads, the one whose snapshot is later is componentwise ≥, whatever their start
  times.
- **Incomparable snapshots.** A snapshot that is neither ≥ nor ≤ the installed one is discarded and
  counted (`fs_config_incomparable_snapshots_total`).
- **Entries are never removed.** Institutions are never deleted, and the map has no eviction. So
  "no snapshot" is true only before an instance's first install for that institution.

**C4. Refresh, and the bound.**
- **Threads.** A dedicated single-thread scheduler runs the probe, and hands loads to a dedicated
  pool of K threads. Each load uses its own connection, capped at K. Nothing here shares the
  decision path's threads.
- **One cycle:**
  1. Probe once, with deadline T_P. A probe that fails, times out or returns no rows ends the cycle
     as a failure. Nothing is evicted or loaded.
  2. Load every cached institution whose probed versions differ from its installed ones, K at a
     time, each with deadline T_L. A failed load keeps the old snapshot and is retried next cycle.
  3. Mark every institution whose probed versions equal its installed ones, or that loaded
     successfully, as **confirmed** at this cycle's end.
  4. Wait R.
- **The bound.** A version row committing at DB time t is installed, on every instance whose probe
  and loads succeed, by `t + R + 2C`. The worst case is
  `R + 2(T_P + ⌈N/K⌉·T_L)`:
  - E.6's 60 s holds while that sum is at most 60 s;
  - with R = 5 s, K = 4 and T_L = T_P = 2 s, that allows N ≤ 48 institutions all changing at once
    (⌈48/4⌉ = 12, and 5 + 2·(2 + 24) = 57 s);
  - with one change and ordinary loads, it is about 6 s.
- **Freshness.** `fs_config_unconfirmed_seconds` is the largest, over this instance's
  institutions, of the time since the institution was last confirmed. It is measured on the
  monotonic clock. An alert fires at 60 s. A quiet institution is confirmed every cycle, so the
  alert fires only when probes or loads fail (question Q1).

**C5. A decision reads thresholds and rules from one snapshot.**
- **One read per decision.** `ConfigurationPort` returns the snapshot object, and `DecisionService`
  reads it once.
- **Breaker state is outside the snapshot.** Decisions read the breaker *state* (`isOpen`), not
  breaker settings. A settings change reaches decisions through C8.
- **What is recorded today.** The decision records the thresholds version on
  `fs.transactions.scored`.
- **Not done here.** Recording the rule-set version and a hash of the compiled rule set needs a
  change to the Kafka contract, filed as PB-151.

**C6. Dual-control actions and the overdue revert.**
- **One lock per transaction.** Every action (propose, which includes supersede and fold; approve
  or confirm; reject; withdraw) and every sweep step runs in READ COMMITTED and takes **exactly
  one** kind lock.
- **Action order.**
  - **(0)** Approve, reject and withdraw first read the change's `(institution_id, kind)` by id. An
    unknown id gives 404. This pre-read is safe because V6's guard trigger makes `kind` immutable.
  - **(a) Overdue revert, its own transaction.** It takes the lock of the kind, then reverts every
    APPLIED_PENDING_CONFIRMATION change of that kind whose `confirm_by` ≤ `clock_timestamp()`, and
    commits. A revert:
    - sets REVERTED, with cause NOT_CONFIRMED_IN_TIME and `reverted_at = clock_timestamp()`;
    - writes the change's `previous` settings as a new version;
    - writes a `THRESHOLD_CHANGE` / `REVERTED` audit event with the system actor (ADR 0014 §3) and
      `event_at = clock_timestamp()`.
  - **(b) The action's transaction.** It takes the lock again, then repeats (a) for any change that
    became overdue in between. It then re-reads the open change of that kind and the current
    version, and decides by what it read:
    - a proposal with a stale `base_version` gets 409 `conflict`;
    - a confirmation or rejection of a change that is no longer open gets 409 `change-not-open`;
    - a tightening finding an open loosening supersedes it;
    - a tightening finding an APPLIED_PENDING_CONFIRMATION change folds it: it takes that change's
      `previous` as its baseline and gets its own 24-hour `confirm_by` (ADR 0014 §3);
    - a loosening finding any open change gets 409 `open-change-exists`.
  - **(c)** A refusal is returned as a value, after any revert in (b) has committed, so a refusal
    never rolls a revert back.
- **The sweep.** It runs every R_sweep = 5 s on its own single-thread scheduler. It calls
  `overdue_config_changes()` and, for each `(institution, kind)` in the order returned, runs step
  (a) as one transaction.
- **Retries.** All of these run through a runner that retries SQLSTATE 40001 (the audit chain's
  refusal) and 40P01. `inTenant` gains the second.
- **Deadlock.** A transaction never holds two kind locks. The only locks taken after a kind lock are
  rows of that kind, and the audit chain head, always last. That leaves no cycle.
- **Consequences:**
  - An overdue change is reverted before anything else can touch it.
  - A confirmation and a fold are serialised, and each sees the other's result.
  - A proposal builds on the settings in force.
  - A revert is in force everywhere by `confirm_by + R_sweep + d + R + 2C`, where d is one sweep
    step.
- **Folding, as the owner decided it (ADR 0014 §3).** A fold keeps the folded change's baseline
  and starts a new 24-hour window. So an unconfirmed tightening's settings can stay in force past
  their own deadline while a later tightening carries them.

**C7. Rules.**
- **No dual control.** The frozen contract gives the rule operations none, and V6 admits no rules.
  A rule write is in force on commit.
- **Concurrent writes.** Rule writes are serialised by the RULES lock in the §3.3 triggers.
  `setRuleState` carries the rule's `version`, and a stale one gets 409.
- **`createRuleVersion` is last-writer-wins.** It carries no expected version, so of two concurrent
  edits, the second draft becomes current. Both stay in `alert_rule_versions`. Filed as PB-152.
- **Two constraints on future code:**
  - Writing `trigger_count` or `last_triggered_at` must not increment `alert_rules.version`.
  - A rule that does not compile is refused at write (422). A stored rule a later compiler rejects
    is skipped at load and counted.

**C8. The circuit-breaker monitor runs on the leader only.**
- **Who writes.** Only the instance holding the lease evaluates and writes breaker state. Other
  instances refresh their local `isOpen` copy from Redis, reading every R_monitor = 5 s, and never
  write. Two instances can overlap only across a lease expiry. The existing compare-and-set settles
  that overlap.
- **State records its settings version.** The breaker state in Redis records the settings version
  of the evaluation that last changed it.
- **Re-evaluation under newer settings.** When the leader holds a newer settings version than a
  breaker's recorded one, it re-decides that breaker from its stored history under the new
  settings. It keeps `opened_at` and `last_breach_at`, and reopens or closes as the new settings
  require, recording an event. So a transition made under old settings lasts at most until the
  leader's first evaluation after it installs the new version.
- **One institution's failure is isolated.** Each institution is evaluated separately, and one that
  cannot load its snapshot is skipped and counted.
- **The breaker bound.** A settings change reaches decisions by `t + R + 2C + 2·R_monitor`, plus one
  evaluation: about 16 s in the usual case, within 60 s on C4's condition.

**C9. Holds keep the policy they were held under.**
- **Where the policy is kept.** The Redis entry carries the policy of the decision's snapshot.
  `decision_states.timeout_policy` stores it too (§3.4).
- **The overdue sweep** uses the stored policy. When it is NULL, the sweep uses the policy of the
  decision's own `thresholds_version` if the row records one. Only otherwise does it use today's
  computation from `decided_at`.
- **Residual, stated in §6.** That last fallback applies only to holds written by the older release:
  during a rolling deploy, or drained from a spool written before the upgrade.

**C10. Outside this contract.**
- **The scorer's global threshold hash** belongs to model lifecycle, P8 (Q2).
- **The anomaly-review threshold** is a per-process deploy-time setting that must be uniform. Java
  compares with `>=` and the scorer with `>`. That mismatch is filed as PB-153.

## 5. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | A load's parts come from one snapshot | C2 |
| P2 | No part's version ever decreases on an instance. This includes a slow refresh after a first-request load, and a load with an earlier `now()` but a later snapshot | C3 |
| P3 | Which version is in force is decided by versions alone. Only the 24-hour deadline reads a clock, and that clock is the database's | C1, C2, C6 |
| P4 | A committed change is installed by `t + R + 2C`, including with N institutions cached and one whose loads hang | C4 |
| P5 | A decision's thresholds and rules come from one snapshot | C5 |
| P6 | Every applied tightening ends in exactly one of CONFIRMED, REVERTED or SUPERSEDED. It is reverted by `confirm_by + R_sweep + d` unless confirmed or folded first | C6 |
| P7 | Confirm vs fold, approve-loosening vs supersede, and the sweep vs fold, confirm, reject, and an approval of the other kind: each ends consistently, the loser gets 409, and nothing deadlocks | C6 |
| P8 | A confirmation or rejection after `confirm_by` gets 409, and the change is REVERTED with cause NOT_CONFIRMED_IN_TIME and `reverted_at ≥ confirm_by` | C6, §3.6 |
| P9 | Every institution, new ones included, has threshold and breaker version 1. Every threshold version has six channels, including one inserted with none | §3.1, §3.2 |
| P10 | Every write to `alert_rules` or `alert_rule_versions` raises the rule-set version by exactly one, and it never decreases | §3.3 |
| P11 | Only the leader writes breaker state. A transition made under older settings is re-decided at the leader's first evaluation after it installs the newer ones | C8 |
| P12 | A hold's timeout policy is the decision's, through Redis and through the sweep, for every row the new release writes | C9 |
| P13 | A failed or empty probe evicts nothing. The freshness alert stays quiet for unchanged institutions and fires when probes fail | C4 |
| P14 | An incomparable snapshot is discarded and counted | C3 |
| P15 | A stored rule that no longer compiles is skipped and counted | C7 |

## 6. Accepted costs

- **Propagation is by polling,** so instances can disagree within the bound. Until PB-151, only the
  thresholds version is recorded per decision.
- **There is no announcement channel.** `fs.config.changes` stays unproduced.
- **An instance without PostgreSQL** keeps its snapshots, but cannot load an institution for the
  first time.
- **Rolling deploys** have two costs:
  - instances on different compilers can skip different stored rules (PB-151);
  - holds the old release writes fall back to a recomputed policy (C9).
- **A2 is required.** A restore or lossy failover requires restarting every instance.
- **A breaker transition made under old settings can stand** until the leader's next evaluation
  after it installs the new ones.

## 7. Questions for the owner

- **Q1.** After 60 s of failed refreshes, should an instance keep deciding with its last snapshots
  (and alert), or fail closed?
- **Q2.** The scorer's global threshold hash is left to model lifecycle (P8). M5's handoff to
  `PATCH /admin/thresholds` cannot be met as written.

## 8. Review 2's findings and their answers

| Finding | Answer |
|---|---|
| N1 MAJOR: a stale instance closes a breaker | C8: leader-only writes; re-decision under newer settings; P11 |
| N2 MAJOR: two kind locks, deadlock, 40P01 | C6: one kind lock per transaction, a fixed lister order, audit last, 40P01 retried; P7 |
| N3 MAJOR: a lost commit and a reused version | A2 and the runbook restart; §6 |
| N4: the age alert fires for quiet institutions | C4: "confirmed" by probe; P13 |
| N5: a version with no rows | §3.2: a trigger on the version table, and V81 checks existing data; P9 |
| N6: rule writes that skip the set version, and pre-V81 rules | §3.3: triggers append it; V81 seeds; `coalesce` to 0; P10 |
| N7: threads, T_L, probe timeout, arithmetic, sweep scheduler | C4: a K-thread pool, whole-load and probe deadlines, N ≤ 48; C6: the sweep's own scheduler |
| N8: a refusal rolls back the revert, and early timestamps | C6 (a) commits on its own, (c) refusals are values, `clock_timestamp()`; §3.6; P8 |
| N9: the NULL fallback after V81 | C9: the decision's own version first; the residual stated in §6 |
| N10: decisions use breaker state | C5 restated; C8's own bound |
| N11: dead eviction | C3 and C4: no eviction, and an empty probe is a failure; P13 |
| N12: the seeding trigger | §3.1: owner rights stated, the fixture changed; §3.5 grants and V71 |
| N13: the lock is not the first statement | C6 (0): a pre-read of the immutable kind |
| N14: P3 overclaims | P3 restated |
