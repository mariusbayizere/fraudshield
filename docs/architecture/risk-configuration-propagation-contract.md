# Risk configuration: the propagation contract

Status: **draft 1, for independent review.** Code is written against this document, not the other way round. The owner asked for it on
2026-10-20, before any of M8B's code. It governs how an approved threshold or circuit-breaker
change (P4) and a rule change (P5) reach every running decision instance. It does not govern the
dual-control state machine itself (P4-a: who may propose, approve or reject, and when), which is
`DualControlWorkflow`'s and V6's. It does govern the timed transitions that make a setting take or
lose effect.

Sources: FR-05-05, FR-05-07, FR-03-07 and E.6 ("effective within 60 s, target 5 s"); the contract's
threshold, breaker, config-change and rule operations; V4 and V6; `JpaConfiguration`,
`ConfigurationPort`, `BackgroundTasks`, `CircuitBreakerMonitor`, `DualControlWorkflow`; the
Python scorer's `serving/thresholds.py`.

## 1. What exists

- **The decision path reads configuration from a per-institution snapshot** (`JpaConfiguration`):
  thresholds, rules and breaker settings, swapped whole.
  - An institution's first request loads its snapshot synchronously.
  - `refreshAll` reloads every cached institution every 5 s plus the load time
    (`BackgroundTasks`, fixed delay). A failed reload keeps the previous snapshot.
- **Nothing else propagates.**
  - `fs.config.changes` is declared in `contracts/kafka` but has no producer or consumer.
  - `invalidate()` has no caller.
  - `DualControlWorkflow` lives in memory and is not wired to anything.
- **Defects in the existing snapshot, which this contract fixes:**
  - **Out-of-order installs.** A load is installed with `put`. A slow load can overwrite a newer
    snapshot installed by a concurrent first-request load.
  - **Inconsistent reads.** The three parts are read by separate statements, so one snapshot can
    mix commits.
  - **Clock skew.** "In force" is decided by comparing `effective_at` with the instance's JVM
    clock, so instance skew moves when a change takes effect.
  - **A rule-set version that can fall.** `RuleSet.version` is `max(alert_rules.version)`, a
    per-row lock counter, so disabling the rule that holds the maximum lowers it.

## 2. Terms

| Term | Meaning |
|---|---|
| **R** | the refresh period, 5 s, fixed delay |
| **L** | the duration of one snapshot load |
| **DB time** | the PostgreSQL primary's `now()` for the transaction in question |
| **part** | one of thresholds, breaker settings, rules |
| **version of a part** | thresholds: `risk_threshold_versions.version`; breaker: `mcc_circuit_breaker_settings_versions.version`; rules: the rule-set version (§3) |
| **snapshot** | the three parts for one institution, read by one load, with that load's **read time** (the DB time of its transaction) |

**Assumption A1.** Loads read the primary. A replica that lags would break C4's bound, as it would
break the API-key cache's.

## 3. Schema addition (V81, M8B's range)

- **A new append-only table, `alert_rule_set_versions`:**
  - columns: `institution_id`, `version` (bigint), `changed_at` (DB time), `rule_id`, and
    `change` (CREATED, VERSIONED, ENABLED, DISABLED or DELETED);
  - primary key `(institution_id, version)`;
  - tenant-isolated.
- **Every rule write appends the next version in the same transaction as the change:**
  `createRule`, `createRuleVersion` and `setRuleState`.
  - The next version is `max + 1` under a per-institution advisory lock. Two writers therefore
    never take the same version, and the primary key is the backstop.
- **The rule-set version is the institution's highest `version` in this table, or 0 if it has
  none.** It only ever increases.

## 4. Rules

**C1. A change takes effect by a committed row, at DB time.** A threshold or breaker change is in
force once a version row exists whose `effective_at` ≤ DB time. That row is written in the same
transaction as the state change that causes it:
- a tightening proposal (APPLIED_PENDING_CONFIRMATION);
- an approval of a loosening (APPROVED);
- a rejection of a tightening (REVERTED, with the previous settings as a new version);
- an overdue revert (C6).

`effective_at` is that transaction's DB time. A rule change is in force when its rule-set version
row commits. No change is ever announced by message, and no instance learns of one except by
reading PostgreSQL.

**C2. One load reads one consistent snapshot.** A load is one `REPEATABLE READ READ ONLY` tenant
transaction:
- it reads `now()` as its read time;
- it reads the threshold and breaker versions in force at that read time, comparing
  `effective_at <= now()` in SQL, never with a JVM instant;
- it reads the current rule-set version, and the enabled rules at their current versions.

All three parts therefore come from the same database snapshot, and the snapshot's versions are
the versions in force at its read time.

**C3. A snapshot is installed only if it is newer.** It is installed into the instance's map in one
atomic step on the institution (`ConcurrentHashMap.compute`). The step:
- installs it if no snapshot is present, or if its read time is at or after the present one's;
- otherwise discards it.

This applies to both the synchronous first load and the periodic refresh. Read times come from one
clock, the primary's, so "newer" is total. Each part's version therefore never decreases on an
instance: a later read sees at least every commit an earlier read saw (A1).

**C4. Bound.** A change whose row commits at DB time t is used, on every instance that can reach the
primary, by every decision that reads the snapshot after `t + R + 2L`.
- That is one wait of at most R, plus the load in progress (which may have started before t), plus
  the next load. With R = 5 s and L well under 1 s, that is about 6 s in the worst case: just over
  E.6's 5 s target, and well within its 60 s requirement. R = 4 s would meet the target; the period
  is configurable, and the bound is stated in R.
- **The bound holds only while loads succeed.** If loads fail, the instance keeps deciding with its
  last snapshot. `fs_config_snapshot_age_seconds{institution}`, now minus the installed snapshot's
  read time, is exported, and an alert fires at 60 s (question Q1).

**C5. Every decision records the versions it used:** the threshold version, which is recorded
today, plus the rule-set version and the breaker settings version. Each comes from the one snapshot
the decision read. Nothing re-reads configuration within a decision.

**C6. The 24-hour revert.**
- **The sweep.** Every instance runs it every R. In each institution with an overdue change (a
  SECURITY DEFINER lister, as V62's), one tenant transaction:
  - selects the APPLIED_PENDING_CONFIRMATION changes whose `confirm_by` ≤ DB time, `FOR UPDATE SKIP
    LOCKED`;
  - sets each to REVERTED, with cause NOT_CONFIRMED_IN_TIME;
  - writes a new version row with the change's `previous` settings;
  - writes the `THRESHOLD_CHANGE` / `REVERTED` audit event with no actor.
- **Confirming and rejecting take the same row lock.** `approveConfigChange` (a confirmation) and
  `rejectConfigChange` take `FOR UPDATE` on the change row. Under that lock, a confirmation is
  refused with 409 `change-not-open` when DB time is at or after `confirm_by`, whatever the sweep
  has done.
  - So a tightening is confirmed, or reverted, never both.
  - Lateness is bounded: a revert is in force by `confirm_by + R + d + R + 2L`, where d is one sweep
    transaction.
- **Every action, and the sweep, first reverts the overdue changes of its own kind and
  institution,** in its own transaction, as `DualControlWorkflow.revertOverdue` does in memory.
  An action never sees an overdue change still open.

**C7. Rules:**
- **No dual control.** The frozen contract gives the rule operations none, and V6's
  `config_changes.kind` admits no rules, so a rule change is effective on commit. That is the
  existing answer to M8B's question P5-a. It is restated here so the reviewer can object.
- **Concurrent edits.**
  - `setRuleState` carries the rule's `version` (an optimistic lock), and a stale one gets 409.
  - `createRuleVersion` takes `FOR UPDATE` on the rule row and appends version `current + 1`.
  - Two concurrent edits are therefore serialised, and each appends its own rule-set version.
- **The compiler gate.** A rule that does not compile is refused at write (422), so a stored rule
  can only stop compiling after a compiler change. A load then skips it and counts it, as today.
  The rule-set version is unaffected.

**C8. The circuit-breaker monitor reads its settings from the snapshot at each evaluation.**
- A changed window applies from the first evaluation after the install.
- One-minute buckets kept for 25 hours can serve any window up to 1,440 minutes.
- A breaker opened under old settings is re-evaluated under the new ones, by compare-and-set, as
  today.

**C9. Holds keep the policy they were held under.**
- A MEDIUM hold's timeout policy is fixed when the hold is scheduled. The Redis entry carries it,
  and the overdue sweep computes it from the version in force at `decided_at`.
- A policy change applies to holds created after its install, never to holds already waiting.

**C10. The Python scorer is outside this contract.**
- Its `fs:config:model_thresholds` hash is one global value: the model's default thresholds,
  used only for the informational `model_risk_tier`.
- The API recomputes the tier from the institution's per-channel thresholds (C2), and that tier is
  the one that decides.
- An institution's threshold change therefore does not touch the hash. The hash belongs to model
  lifecycle (P8) (question Q2).

## 5. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | A load's three parts come from one database snapshot. A change committed between two of its statements is either wholly visible or wholly invisible | C2 |
| P2 | An older snapshot never replaces a newer one, including when a slow refresh finishes after a first-request load | C3 |
| P3 | A version row's `effective_at` is compared with DB time. An instance whose clock is skewed by minutes takes the change at the same moment | C1, C2 |
| P4 | A committed change is used by decisions after `t + R + 2L` | C4 |
| P5 | Each decision records the three versions of the snapshot it used | C5 |
| P6 | A tightening past `confirm_by` is reverted once, never also confirmed, even with the sweep and a confirmation concurrent | C6 |
| P7 | The rule-set version never decreases, and each rule write increases it by exactly one | §3, C7 |
| P8 | A policy change does not alter the policy of a hold already scheduled | C9 |

## 6. Accepted costs

- **Propagation is by polling.** Instances can disagree for up to R + 2L after a change, and each
  decision records which version it used.
- **No announcement channel.** Without announcements there is nothing to race, and the API-key
  cache needed six reviews of exactly that. The cost is about 5 s instead of milliseconds.
  `fs.config.changes` stays declared and unproduced. Its schema has no `institution_id`, so it
  could not carry these changes without a contract change anyway.
- **An instance that cannot reach PostgreSQL keeps its last snapshot.**

## 7. Questions for the owner (not decided by this contract)

- **Q1.** Past 60 s of failed loads, an instance keeps deciding with its last snapshot, and an
  alert fires. The alternative is to stop deciding (fail closed), which would turn a database
  outage into an ingest outage. The decision path does not otherwise need PostgreSQL
  synchronously.
- **Q2.** The scorer's global threshold hash is left to model lifecycle (P8), not written by
  institution threshold changes. M5's handoff said M7's `PATCH /admin/thresholds` would write it.
  A global hash cannot represent per-institution, per-channel thresholds, so that handoff cannot be
  met as written.
