# Risk configuration: the propagation contract

Status: **draft 2, for independent review.**
- **Review 1** (`docs/reviews/M8B/config-propagation-contract-review-1.md`) found draft 1 UNSOUND,
  with 4 MAJOR and 8 MINOR findings. Draft 2 answers every finding; §8 maps each one to its answer.
- **The rule for this document:** code is written against it, not the other way round. The owner
  asked for it on 2026-10-20, before any of M8B's code.

**Scope.** This contract governs three things:
- how an approved threshold or circuit-breaker change (P4) and a rule change (P5) reach every
  running decision instance;
- the timed transitions that make a setting take or lose effect;
- the locking that every dual-control action needs so those transitions stay consistent.

It does not govern who may propose, approve or reject. That is ADR 0014 §3, implemented by
`DualControlWorkflow`'s rules.

**Sources:**
- requirements FR-05-05, FR-05-07, FR-03-07, and E.6 ("effective within 60 s, target 5 s");
- ADR 0014 §3;
- the contract's threshold, breaker, config-change and rule operations;
- migrations V4, V6 and V62;
- code: `JpaConfiguration`, `ConfigurationPort`, `BackgroundTasks`, `CircuitBreakerMonitor`,
  `DualControlWorkflow`, `JdbcOverdueHolds`, and the Python scorer's `serving/thresholds.py`.

## 1. What exists

- **The decision path reads configuration from a per-institution snapshot** (`JpaConfiguration`)
  of thresholds, rules and breaker settings.
  - An institution's first request loads its snapshot synchronously.
  - `refreshAll` reloads every cached institution in turn, one after another, then waits 5 s. It
    runs on a two-thread scheduler that it shares with four other tasks.
  - A failed reload keeps the previous snapshot.
- **Nothing else propagates.**
  - `fs.config.changes` has no producer and no consumer.
  - `invalidate()` has no caller.
  - `DualControlWorkflow` is in memory and unwired.
- **Defects this contract removes:**
  - out-of-order installs by `put`;
  - three separate reads per load, at READ COMMITTED;
  - "in force" decided against the JVM clock;
  - a rule-set version (`max(alert_rules.version)`) that can fall;
  - a decision that reads thresholds and rules in two calls (`DecisionService:172,184`), so one
    decision can mix two snapshots;
  - nothing ever writes version 1, so an institution runs at "version 0" (defaults). That version
    cannot be named as a `base_version` (V6 requires ≥ 1), and it breaks the foreign key of
    `mcc_circuit_breaker_events.settings_version` when a breaker opens on defaults;
  - the overdue-hold sweep recomputes a hold's timeout policy from the JVM time of the decision
    (`JdbcOverdueHolds:30-34`).

## 2. Terms

| Term | Meaning |
|---|---|
| **R** | the refresh period: 5 s, configurable |
| **K** | the number of loads run at once: 4 |
| **T_L** | the timeout of one load: 2 s (a statement and connection-acquire timeout) |
| **C** | the duration of one refresh cycle (C4). It is measured and exported. |
| **part** | one of: thresholds, breaker settings, rules |
| **version of a part** | thresholds: `risk_threshold_versions.version`. Breaker: `mcc_circuit_breaker_settings_versions.version`. Rules: the rule-set version (§3). |
| **visible** | committed before the reading transaction's snapshot was taken |
| **the lock of a kind** | `pg_advisory_xact_lock` on the key ('risk_config', institution, kind), where kind is CHANNEL_THRESHOLDS, MCC_CIRCUIT_BREAKER or RULES. It is transaction-scoped, so a pooled connection never keeps it. |

**Assumption A1.** Every transaction in this contract runs on the primary.

## 3. Schema additions (V81, M8B's range)

1. **Version 1 is seeded.** For every institution, V81 inserts:
   - threshold version 1: the SRS defaults for all six channels (0.60, 0.85,
     RELEASE_WITH_TIMEOUT_LABEL);
   - breaker version 1: 5 %, 15 min, 100 transactions, 60 min.

   Both have `config_change_id` NULL, which V6 already allows for version 1 only. An AFTER INSERT
   trigger on `institutions` does the same for every new institution. Version 0 then never
   occurs, and the loader's default branch becomes an error that is counted. This also fixes the
   `mcc_circuit_breaker_events` foreign-key failure for any institution created after V81 or
   seeded by it.
2. **Every threshold version is complete.** A deferred constraint trigger on `risk_thresholds`
   refuses a commit that leaves a version with other than six channel rows.
3. **A new append-only table, `alert_rule_set_versions`:**
   - columns: `institution_id`, `version` (bigint), `changed_at`, `rule_id`, and `change` (CREATED,
     VERSIONED, ENABLED, DISABLED or DELETED);
   - primary key `(institution_id, version)`;
   - tenant-isolated.

   Every rule write runs in READ COMMITTED and takes the lock of RULES **before** it reads `max`. It
   then appends `max + 1` in the same transaction as the change. Versions are therefore allocated in
   commit order, and the primary key is a backstop.
4. **`decision_states` gains `timeout_policy text`.** It is NULL except on HOLD rows, where it is the
   policy the decision used, written by `PostgresSink` from the decision event. Rows written before
   V81 keep NULL.
5. **A new SECURITY DEFINER function, `risk_configuration_versions()`.** It returns, for every
   institution, `(institution_id, thresholds_version, breaker_version, rule_set_version)`: the
   highest visible version of each part. It returns numbers only, never settings.

## 4. Rules

**C1. A change is in force when its version row is visible.**
- A version row is written in the same transaction as the state change that causes it (C6):
  - a tightening applied;
  - a loosening approved;
  - a tightening rejected or reverted, which writes the previous settings as a new version;
  - a rule written (§3.3).
- `effective_at` keeps its default (the writer's `now()`). It is a record of the earliest possible
  effect, not a filter. No load compares it with any clock.
- What decided a transaction is the version the decision recorded (C5).
- No change is announced by message. An instance learns of one only by reading PostgreSQL.

**C2. One load reads one consistent snapshot.** A load is one `REPEATABLE READ READ ONLY` tenant
transaction that reads:
- the highest visible threshold version, with its six rows;
- the highest visible breaker version;
- the highest visible rule-set version;
- the rules that are enabled at their current versions.

There is no time filter. All parts therefore come from one database snapshot.

**C3. A snapshot is installed only if no part goes back.** It is installed in one atomic step on the
institution's map entry (`ConcurrentHashMap.compute`). It is installed if there is no snapshot yet,
or if each of its three versions is at least the installed snapshot's. Otherwise it is discarded.
- **Why this orders correctly.** Versions are allocated in commit order: by the lock of a kind (C6,
  §3.3) and the primary keys. A PostgreSQL snapshot sees a prefix of commit order. So of two loads,
  the one whose snapshot was taken later has every version at least the other's, whatever their
  start times.
- **A snapshot that is neither ≥ nor ≤ the installed one** can come only from a lagging source,
  which A1 excludes. It is discarded and counted (`fs_config_incomparable_snapshots_total`).
- **Time plays no part in the ordering.** A clock step or a failover cannot hold a newer snapshot
  back.

**C4. Refresh, and the bound.**
- Refresh runs on its own single-thread scheduler, shared with nothing.
- **Each cycle** does the following:
  - (a) It calls `risk_configuration_versions()` once.
  - (b) It loads, at most K at a time, every cached institution whose probed versions differ from
    its installed ones. Each load has the timeout T_L. A load that fails or times out keeps the old
    snapshot and is retried next cycle.
  - (c) It evicts institutions that the probe no longer returns.
  - (d) It waits R.
- C is the duration of steps (a) and (b), exported as `fs_config_refresh_cycle_seconds`.
- **Bound.** A version row that commits at DB time t is installed on every instance whose probe
  and loads succeed by `t + R + 2C`. That is: the cycle in progress at t, the wait, then the next
  cycle.
- **What C costs.** A cycle loads only the institutions that changed, so C is the probe plus about
  ⌈changed / K⌉ loads, each at most T_L.
  - With one change and an ordinary load, C is well under 1 s and the bound is about 6 s.
  - The guaranteed worst case, with every institution changing at once, is
    `R + 2(probe + ⌈N/K⌉·T_L)`.
  - E.6's 60 s holds while `⌈N/K⌉·T_L ≤ 27 s`, which is about 54 institutions at K = 4 and T_L = 2 s.
    K is configurable for larger deployments.
- **The bound does not hold while the probe or the loads fail.** The instance then keeps its
  snapshots, and an alert fires when any installed snapshot's age passes 60 s. Age is measured on
  the JVM's monotonic clock from the install, plus that load's own duration, so no clock mixes
  with another (question Q1).

**C5. A decision reads one snapshot.**
- `ConfigurationPort` returns the institution's snapshot object. `DecisionService` reads it once
  and takes thresholds, rules and breaker settings from it.
- **What is recorded today.** The decision records the thresholds version on
  `fs.transactions.scored` (`DecisionService:216`).
  - That schema is closed and requires `thresholds_version ≥ 1`. After §3.1 the clamp in
    `KafkaMessages:454` is never exercised.
- **Not done here (PB-151).** Recording the rule-set version, the breaker version and a hash of the
  compiled rule set needs a change to the Kafka contract. It is filed as PB-151. Until it lands, a
  decision's rule set is known only to within the propagation bound.

**C6. Dual-control actions and the overdue revert.**
- **Every action takes the lock of its kind first.** The actions are propose (which includes
  supersede and fold), approve or confirm, reject, withdraw, and the overdue sweep. Each runs in
  READ COMMITTED and takes the lock as its first statement after the tenant is set. Under the lock,
  in the same transaction and in this order, it:
  - (a) reverts every APPLIED_PENDING_CONFIRMATION change of that kind whose `confirm_by` ≤
    `clock_timestamp()`. A revert sets REVERTED with cause NOT_CONFIRMED_IN_TIME, writes the
    change's `previous` settings as a new version, and writes the `THRESHOLD_CHANGE` / `REVERTED`
    audit event with the **system actor** (ADR 0014 §3);
  - (b) re-reads the open change of that kind and the current version;
  - (c) for a proposal, checks `base_version` against the current version: 409 `conflict` when
    stale;
  - (d) decides by the status read in (b). The decision is one of these:
    - a confirmation or rejection of a change that is no longer open gets 409 `change-not-open`;
    - a tightening that finds an open loosening supersedes it;
    - a tightening that finds an APPLIED_PENDING_CONFIRMATION change folds it: the new change
      takes the folded change's `previous` as its baseline, and gets its own 24-hour `confirm_by`
      (ADR 0014 §3, as `DualControlWorkflow` does);
    - a loosening that finds any open change gets 409 `open-change-exists`.
- **The sweep.** Every instance runs it every R. It asks a SECURITY DEFINER lister, which compares
  with its own `now()` and never a JVM time, which institutions and kinds have an overdue change. It
  then runs step (a) for each, under the lock.
- **Retries.** Every action and the sweep run through the audit module's `inTenant`, which retries
  SQLSTATE 40001 (the audit chain's refusal). Each transaction is kept short.
- **Consequences:**
  - an overdue change is always reverted before anything else can touch it, so it is never folded,
    confirmed or rejected;
  - a confirmation and a fold of the same change are serialised, and the second sees the first's
    status;
  - a proposal always builds on the settings in force, never on a state an approval is about to
    replace;
  - a revert is in force on every instance by `confirm_by + R_sweep + d + R + 2C`, where d is one
    sweep transaction.
- **Folding, stated as the owner decided it (ADR 0014 §3).** A fold takes over the folded change's
  baseline and starts a new 24-hour window. So an unconfirmed tightening's settings stay in force
  past its own deadline while a later tightening carries them. They are restored when the carrying
  change is reverted, unless that change is confirmed first.

**C7. Rules:**
- **No dual control.** The frozen contract gives the rule operations none, and V6's `kind` admits no
  rules, so a rule write is in force on commit (C1).
- **Concurrency.** Every rule write takes the lock of RULES (§3.3). `setRuleState` carries the
  rule's `version` and gets 409 when it is stale.
- **Last writer wins on `createRuleVersion`.** The operation carries no expected version, so two
  officers editing the same version are serialised and the second's draft becomes current. Both
  drafts stay in `alert_rule_versions`. The frozen contract allows nothing better; filed as PB-152.
- **Two notes for implementers:**
  - Writing `trigger_count` or `last_triggered_at` must not increment `alert_rules.version`.
  - A rule that does not compile is refused at write with 422. A stored rule that a later compiler
    rejects is skipped at load and counted.

**C8. The circuit-breaker monitor:**
- **Each evaluation reads the snapshot.** The breaker's state in Redis records the settings version
  it was last evaluated under. A compare-and-set from an evaluation under a lower settings version
  is refused, so an instance that has not yet installed a change can never undo the change's
  effect.
- **One institution's failure does not stop the others.** The monitor evaluates each institution
  separately. A snapshot that cannot be loaded skips that institution for one evaluation and is
  counted.

**C9. Holds keep the policy they were held under.**
- The Redis entry carries the policy from the decision's snapshot, and so does `decision_states`
  (§3.4).
- The overdue sweep uses the stored policy. Only for a row written before V81 does it fall back to
  today's computation.
- A policy change therefore never alters a hold already scheduled, by either path.

**C10. Outside this contract:**
- **The scorer's threshold hash.** `fs:config:model_thresholds` is one global value: the model's
  defaults, used only for the informational `model_risk_tier`. An institution's threshold change
  does not touch it, and it belongs to model lifecycle (P8) (question Q2).
- **The anomaly-review threshold.** It is a per-process deploy-time setting
  (`DecisionWiring:297`). It must be uniform across instances by deployment, and it is not recorded
  on the decision. Java compares with `>=` and the scorer with `>`. That mismatch is filed as
  PB-153.

## 5. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | A load's parts come from one snapshot. A change committed between two of its statements is wholly visible or wholly invisible | C2 |
| P2 | No part's version ever decreases on an instance. This holds for a slow refresh finishing after a first-request load, and for a load with an earlier `now()` but a later snapshot | C3 |
| P3 | Whether a change is in force never depends on a clock. A JVM skewed by minutes, or a DB clock stepped back, changes nothing | C1, C2, C3 |
| P4 | A committed change is installed by `t + R + 2C`. This holds with N institutions cached, and with one institution whose loads hang | C4 |
| P5 | A decision's thresholds, rules and breaker settings come from one snapshot | C5 |
| P6 | Every applied tightening ends in exactly one of CONFIRMED, REVERTED or SUPERSEDED. It is reverted by `confirm_by` plus the sweep's lateness unless confirmed or folded first. A confirmed change's settings are never restored away except by a later officer's change | C6 |
| P7 | Races under the lock: confirm vs fold, approve-loosening vs supersede, sweep vs fold, sweep vs confirm, sweep vs reject. Each ends consistently and answers 409 to the loser | C6 |
| P8 | A confirmation or rejection after `confirm_by` gets 409, and the change is REVERTED with cause NOT_CONFIRMED_IN_TIME | C6(a,d) |
| P9 | Every institution has threshold and breaker version 1, a new institution included. Every threshold version has six channels | §3.1, §3.2 |
| P10 | The rule-set version never decreases, and each rule write raises it by exactly one | §3.3, C7 |
| P11 | An instance under stale breaker settings cannot change a breaker evaluated under newer settings | C8 |
| P12 | A hold's timeout policy is the decision's, through Redis and through the overdue sweep | C9 |

## 6. Accepted costs

- **Propagation is by polling.** Instances can disagree within the bound. Until PB-151, only the
  thresholds version is recorded per decision.
- **There is no announcement channel.** Without one there is nothing to race, and the API-key cache
  needed six reviews of exactly that. The cost is seconds instead of milliseconds.
  `fs.config.changes` stays declared and unproduced: its schema has no `institution_id`.
- **An instance that cannot reach PostgreSQL keeps its snapshots, but cannot load a new
  institution.** An institution's first decision on an instance needs PostgreSQL. After a restart
  during a database outage, those first decisions fail.
- **Rolling deploys.** Instances on different compiler versions can skip different stored rules
  under the same rule-set version, until PB-151 records the compiled set.

## 7. Questions for the owner

- **Q1.** After 60 s of failed refreshes, an instance keeps deciding with its last snapshots, and
  an alert fires. The alternative, failing closed, turns a database outage into an ingest outage
  for every institution already loaded.
- **Q2.** The scorer's global threshold hash is left to model lifecycle (P8). M5's handoff expected
  `PATCH /admin/thresholds` to write it, but a global hash cannot represent per-institution,
  per-channel thresholds.

## 8. Review 1's findings and their answers

| Finding | Answer |
|---|---|
| 1 MAJOR: read time does not order snapshots | C1 drops the time filter; C3 installs by versions; P2 and P3 |
| 2 MAJOR: supersede and fold were unlocked | C6: the lock of a kind for every action, overdue reverts first, decisions by the status read under the lock; P6 restated for folding; P7 |
| 3 MAJOR: the bound ignored the refresh loop | C4: a version probe, loads only of what changed, K parallel loads with T_L, a dedicated thread; the bound stated in C; P4 with N institutions and a hung load |
| 4 MAJOR: the sweep recomputed a held policy | §3.4 and C9: the policy stored on `decision_states`; P12 |
| 5 C5: two reads, and what is recorded | C5: one snapshot per decision; other versions filed as PB-151 |
| 6 version 0 and partial versions | §3.1 seeds version 1 (and fixes the breaker foreign key for seeded institutions); §3.2 completeness; P9 |
| 7 C6 clock, rejection, lister, retries, actor | C6: status under the lock, `clock_timestamp()`, rejection refused, a DB-time lister, `inTenant` retries, the system actor; P8 |
| 8 C8 stale evaluations, one cold load | C8: the settings version in the breaker state, evaluation per institution; P11 |
| 9 rule-set lock details, lost edit, `trigger_count` | §3.3 (READ COMMITTED, lock before `max`, transaction-scoped); C7 (last writer wins, PB-152; the `trigger_count` note) |
| 10 anomaly threshold, eviction, first request, compiler skew | C10 and PB-153; C4(c) evicts; §6 states the first-request dependency and compiler skew |
| 11 wording | C1 (`effective_at` is a record); P3 restated; C4's age on the monotonic clock |
| 12 missing properties | P6 to P12 |
