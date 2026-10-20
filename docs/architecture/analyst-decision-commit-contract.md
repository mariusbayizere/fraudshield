# Analyst decisions: the commit and undo contract

Status: **draft 1, for independent review.** Code is written against this document, not the other way round. The owner asked for it on
2026-10-20, before any of M8B's code: "P2's undo is exactly the kind of cross-instance timing
problem" that produced M6's cycle. It governs P2's operations: `decideAlert`, `undoAlertDecision`,
`escalateAlert` and `overrideAlertDecision`. It also covers how they meet M6's timeout poller and
overdue sweep. P1's reads depend on it only through §5, which says what a queue lists.

Sources: D-44 and D-29 in `docs/srs/defect_register.md`; the contract's four operations;
`V4__alerts_rules_and_campaigns.sql`; `DecisionTransitionService`, `HoldTimeoutService`,
`JdbcOverdueHolds` and `PostgresSink` in `backend/decision`; ADR 0056 and
`decision-fact-ordering.md`, whose spool and id conventions this document reuses.

## 1. What exists, and what this contract adds

**Exists (M6, V4):**
- `alert_queue_entries` is mutable, with `status` and an optimistic-lock `version`. An escalation
  is a new entry pointing at the old one.
- `alert_decisions` is append-only. Each row is one decision, unique on
  `(institution_id, idempotency_key)`.
- `alert_decision_commits` and `alert_decision_undos` are append-only. The trigger
  `forbid_commit_and_undo` takes `pg_advisory_xact_lock('alert_decision:' || decision_id)` and
  refuses the second of a commit and an undo.
- `v_alert_decision_state` derives PENDING_COMMIT, COMMITTED or UNDONE.
- MEDIUM holds wait in a Redis schedule (`fs:holds`). Exactly one caller removes each entry: the
  leader's poller (`claimDue`) or an analyst (`cancel`). A PostgreSQL sweep (`JdbcOverdueHolds`)
  times out holds the schedule lost, 10 s after their deadline.
- `DecisionTransitionService.analystDecidesHold` cancels the hold and records the transition and a
  label through the spool. Only tests call it.

**Missing, and added here:** a grace timestamp; anything that ever commits a decision; the link
between a decision and the hold it stops; idempotent side effects; and the rules below.

## 2. Terms

| Term | Meaning |
|---|---|
| **G** | the undo grace, 5 s (D-44) |
| **P** | the committer's and the effects worker's period, 500 ms |
| **DB time** | `clock_timestamp()` on the PostgreSQL primary. Every grace and deadline comparison in this contract uses it, so no instance clock decides one. |
| **entry** | a row of `alert_queue_entries` |
| **open entry** | an entry whose status is PENDING or IN_REVIEW |
| **analyst decision** | an `alert_decisions` row whose decision is CONFIRM_FRAUD or MARK_LEGITIMATE |
| **live decision** | an analyst decision with no undo row. It may or may not be committed. |
| **hold** | the transaction's MEDIUM hold: `decision_states` sequence 1 is HOLD, plus its entry in the Redis schedule |
| **claimed hold** | a hold that an analyst decision removed from the schedule (`hold_claimed = true` on the decision) |
| **effects** | what a committed decision does outside its own tables (§3, D5) |

**Assumption A1.** Every transaction in this contract runs on the primary.

## 3. Schema additions (V80, M8B's range)

1. `alert_decisions` gains three columns. All are written once, at insert:
   - `undo_until timestamptz`, set by a BEFORE INSERT trigger to `created_at + G` for analyst
     decisions and NULL for every other decision. `created_at` is also set by the trigger, to DB
     time, so a caller cannot choose either value.
   - `hold_claimed boolean NOT NULL DEFAULT false`.
   - `prior_status text`, the entry's status when the decision was made: PENDING or IN_REVIEW for
     an analyst decision, NULL otherwise.
2. `alert_decision_commits` gains a BEFORE INSERT check, inside the existing advisory lock: refuse
   if the decision's `undo_until` is not NULL and DB time is before it.
3. `alert_decision_undos` gains a BEFORE INSERT check, inside the same lock: refuse unless the
   decision is an analyst decision, `undo_until` is not NULL, and DB time is before `undo_until`.
4. **A backstop for at most one live decision per entry.** A BEFORE INSERT trigger on
   `alert_decisions` takes `pg_advisory_xact_lock('alert_entry:' || alert_queue_entry_id)` for an
   analyst decision. It refuses if the entry already has a live analyst decision. The undo
   trigger takes the same entry lock first.
   - Lock order everywhere is entry, then decision, so undo and decide cannot deadlock.
   - The application rule (D1) already prevents this case. The trigger is the database's
     independent backstop, as V6's constraints are for dual control.
5. A new append-only table, `alert_decision_effects`:
   - columns: `decision_id` (primary key, FK), `institution_id`, `done_at` (DB time), and
     `outcome` (APPLIED or SKIPPED, with a reason code);
   - tenant-isolated, and granted SELECT and INSERT to `fs_app`.
6. Two SECURITY DEFINER functions, as V62's `institutions_with_overdue_holds` is:
   - `institutions_with_due_decisions()` lists institutions with an analyst decision past
     `undo_until` that has neither a commit nor an undo;
   - `institutions_with_pending_effects()` lists institutions with a committed decision that has
     no effects row.

   The workers then run in each tenant's own transaction, under row-level security.

## 4. Rules

**D1. Deciding** (`decideAlert`). This is one tenant transaction T. The steps run in this order.
- **(a) Idempotency first.** Look up `(institution_id, idempotency_key)` in `alert_decisions`.
  - **Found, with the same caller, alert, decision, comment and `alert_version`:**
    - if the decision is still undoable (no commit, no undo, and DB time before `undo_until`), the
      answer is 202 with the stored `decision_id` and `undo_until`;
    - otherwise the answer is 409 `stale-alert` with the current alert.
  - **Found with anything different:** 409 `idempotency-conflict`.
  - **Not found:** continue. If two requests with the same key race, the unique constraint refuses
    the second insert at (h). That transaction rolls back and is answered by re-running (a), once.
- **(b)** `SELECT … FOR UPDATE` on the entry. If there is no row (row-level security hides other
  institutions' rows too), the answer is 404.
- **(c)** If the entry is escalated and the caller's role ranks below its `escalation_target_role`:
  403 `escalation-level-required`.
- **(d)** If `alert_version` is not the entry's `version`: 409 `stale-alert`.
- **(e)** If the entry is not open: 409 `stale-alert`. The body carries the current alert, which
  shows who decided it or whether it was auto-released (D-29).
- **(f)** For a MEDIUM entry with a `review_deadline_at`: if DB time is at or after it, 409
  `review-deadline-passed`.
- **(g)** For a MEDIUM entry, claim the hold (D2). If the claim refuses, T rolls back and the
  claim's answer is returned.
- **(h)** Insert the decision, with `hold_claimed` and `prior_status`. In the same transaction:
  - set the entry's status to CONFIRMED_FRAUD or MARKED_LEGITIMATE and increment `version`;
  - write the audit event `ANALYST_DECISION` / `DECIDED` through `AuditLog`, with the actor, the
    decision and the comment.
- **(i)** Commit T. The answer is 202 `{decision_id, state: PENDING_COMMIT, undo_until}`.
- **Nothing outside PostgreSQL happens before T commits, except the hold claim.** The effects
  happen only after the commit row exists (D5).

**D2. Claiming the hold.**
- **(a)** M6's port gains `take(institution, transaction)`. It is the same Lua step as `cancel`
  (`HGET`, `HDEL`, `ZREM`), and returns the removed hold or nothing. It is called on Redis
  directly, never through the `ResilientPorts` fallback, because a local fallback cannot see a hold
  scheduled by another instance.
- **(b)** **It returns the hold:** `hold_claimed = true`.
- **(c)** **It returns nothing:** the hold is gone. Either the timeout poller took it, or it was
  never scheduled (`DecisionService` counts a failed schedule and goes on). In both cases a
  timeout will decide, or already has, so the answer is 409 `review-deadline-passed`. That holds
  for an escalated MEDIUM entry too.
- **(d)** **Redis fails or times out:** T rolls back and the answer is 503 with `Retry-After`. A
  MEDIUM decision is never accepted without knowing who owns the hold.
- **(e)** **T fails after a successful take:** the hold is put back with `schedule`, best effort,
  after the rollback. If that also fails, M6's overdue sweep times the transaction out 10 s after
  its deadline. That is the sweep's existing guarantee, and the decision never existed.
- **(f)** **The sweep must not time out a claimed hold.** `JdbcOverdueHolds`' query gains
  `AND NOT EXISTS` (a live decision with `hold_claimed` on the same transaction). This is the one
  change to M6's sweep.
- **(g)** **Clock skew between DB time and the leader's clock** only decides which of the analyst
  and the timeout wins near the deadline. `take` and `claimDue` are atomic on the same Redis keys,
  so they never both win.

**D3. Undoing** (`undoAlertDecision`). This is one tenant transaction.
- **(a)** `SELECT … FOR UPDATE` on the entry.
- **(b)** The decision must belong to that entry and be an analyst decision. Otherwise the answer
  is 404.
- **(c)** If the caller is not the decision's author: 403 `not-decision-author`.
- **(d)** If the decision already has an undo row by this caller, the answer is 204. A repeated
  undo is harmless, and the operation takes no idempotency key.
- **(e)** Insert the undo row. If the §3 trigger refuses (a commit exists, or DB time is at or after
  `undo_until`): 409 `undo-window-expired`.
- **(f)** In the same transaction:
  - set the entry's status back to the decision's `prior_status` and increment `version`;
  - write the audit event `ANALYST_DECISION` / `UNDO`. D-44's "compensating record" is the undo
    row itself.
- **(g)** Nothing else could have changed the entry since D1.
  - Every writer needs an open entry: escalation needs one, the sink's AUTO_RELEASED update
    needs PENDING or IN_REVIEW, and override needs a committed decision.
  - The decision moved the entry out of open, so restoring `prior_status` overwrites no one.
- **(h)** **After the commit**, if `hold_claimed`, the hold is scheduled again.
  - It is rebuilt exactly as `JdbcOverdueHolds` rebuilds an overdue one: the transaction's
    channel, the timeout policy in force at the hold, and `review_deadline_at`.
  - If the deadline has passed, the poller times it out on its next tick (100 ms).
  - If scheduling fails, the sweep times it out within 10 s of the deadline. The decision is no
    longer live, so D2(f) no longer excludes it.

**D4. Committing.** Every instance runs the committer every P. There is no leader.
- **(a)** For each institution that `institutions_with_due_decisions()` returns, one tenant
  transaction selects up to 100 analyst decisions where all of these hold:
  - DB time ≥ `undo_until`;
  - there is no commit row;
  - there is no undo row.
- **(b)** For each one, it inserts a commit row with `ON CONFLICT (decision_id) DO NOTHING
  RETURNING`, and for each row returned it writes the audit event `ANALYST_DECISION` /
  `COMMITTED`.
  - The §3 trigger serialises the insert with an undo of the same decision. Exactly one of the two
    succeeds.
  - A commit that loses to an undo raises inside its savepoint, is skipped, and does not abort the
    batch.
  - A second committer inserting the same commit row does nothing.
- **(c)** **Bound:** while PostgreSQL is up and at least one instance runs, a decision commits
  within `undo_until + P + d`, where d is one committer transaction.
- **(d)** **The state is shown only once a commit exists.** Until the commit row exists, the
  reported state stays PENDING_COMMIT, even after `undo_until`. Override requires the commit row
  (D6), and undo is already refused by time (D3(e)).

**D5. Effects.** Every instance runs the effects worker every P.
- **(a) Which decisions.** For each institution `institutions_with_pending_effects()` returns, the
  worker takes committed decisions with no effects row, oldest commit first. Each runs in its own
  tenant transaction, under `pg_try_advisory_xact_lock('alert_decision_effects:' || decision_id)`.
  A decision whose lock is held is skipped.
- **(b) Ordering.** An override's effects wait until the decision it overrides has its effects row.
- **(c) Every effect's identity derives from the decision id,** so running it twice is the same as
  running it once:
  - the transition's event id is `nameUUIDFromBytes("alert_decision|" + decision_id)`;
  - the label id is `nameUUIDFromBytes("alert_label|" + decision_id)`.
- **(d) The effects, in order:**
  - **E1, the transaction.**
    - **When `hold_claimed`:** M6's service gains `applyAnalystDecision(...)`. It is
      `analystDecidesHold` without the cancel, because D2 already holds the lock, and it uses the
      ids above.
      - It records `DecisionChanged` (HOLD → APPROVE or DECLINE, by ANALYST) and `LabelRecorded`
        (source ANALYST) through the spool.
      - If the latest state is already this event, nothing is recorded again.
      - If it is neither HOLD nor this event, the outcome is SKIPPED with reason
        `transaction_not_held`. A metric counts it, because D2(f) makes it impossible.
    - **Otherwise** (a HIGH entry, already auto-blocked, or an ANOMALY entry, never held): only
      `LabelRecorded` is written, through the spool. MARK_LEGITIMATE on a HIGH entry does not
      unblock the transaction. Under `DecisionState`'s rules only customer verification or a
      senior override can (question Q1).
  - **E2, the circuit breaker.** For a CONFIRM_FRAUD on a MEDIUM or ANOMALY entry (never on HIGH,
    whose auto-block already counted), call `countConfirmedFraud`. It is made idempotent per
    transaction, not per decision: one Lua step, `SET NX` on
    `fs:mcc:confirmed:<institution>:<transaction>` with a 25-hour expiry, then `HINCRBY`. The
    worker calls Redis directly, not through the fallback, which drops the count. A failure leaves
    the decision for the next run.
  - **E3.** Insert the effects row, and commit.
- **(e) A crash between an effect and E3** runs the effects again, on any instance, with the same
  ids:
  - the spool's receivers ignore the repeated event ids (`decision_states.event_id` is unique,
    `label_events` inserts are `ON CONFLICT DO NOTHING`, and Kafka consumers dedupe by event id);
  - the breaker's `SET NX` refuses the second count.
- **(f) No bound while Redis or the spool is unavailable.** A claimed MEDIUM transaction then
  stays HOLD until E1 succeeds (question Q3). `fs_analyst_effects_oldest_pending_seconds` is
  exported, so an alert can fire.

**D6. Overriding** (`overrideAlertDecision`). This is one tenant transaction.
- **(a)** Idempotency as in D1(a); `SELECT … FOR UPDATE` on the entry; then the `alert_version`
  check.
- **(b)** `original_decision_id` must be the entry's latest decision that is neither undone nor
  overridden. Otherwise the answer is 409 `stale-alert`.
- **(c)** It must have a commit row. Otherwise the answer is 409 `decision-not-committed`.
- **(d)** If its author is the caller, or it overrides one of the caller's own decisions: 403
  `own-decision-override`.
- **(e)** Insert the SENIOR_OVERRIDE decision (`undo_until` NULL) and its commit row together, so
  it is COMMITTED at once. In the same transaction:
  - set the entry's status to OVERRIDDEN and increment `version`;
  - write the audit event `SENIOR_OVERRIDE`.

  The answer is 201 with the record.
- **(f) Effects, through D5:**
  - a label with source SENIOR_OVERRIDE, whose `supersedes_label_id` is the original's label id
    (derivable, by D5(c));
  - the transaction transition by SENIOR_OVERRIDE, where `DecisionState` allows it;
  - a breaker count for an upgrade to fraud (E2, once per transaction).
  - A downgrade from fraud cannot remove a count, because the breaker has no decrement. That is an
    accepted cost: the count ages out with its 25-hour bucket.

**D7. Escalating** (`escalateAlert`). This is one tenant transaction.
- **(a)** Idempotency as in D1(a); `FOR UPDATE` on the entry; the version check; the entry must be
  open. The target must rank strictly above the caller (422), and an escalated entry needs the
  caller's role at or above its target (403).
- **(b)** Insert the new entry. It copies the transaction, score, tier, probability, expected loss
  and `review_deadline_at`, and sets `escalated_from_alert_id`, the target and the reason.
- **(c)** Insert an ESCALATE decision on the original, committed at once.
- **(d)** Set the original's status to ESCALATED and increment `version`, and write the audit event
  `ANALYST_DECISION` / `ESCALATED`.
- **(e)** **The hold is not touched.** The review timer keeps running (question Q2).
  - If it fires first, the sink's AUTO_RELEASED update reaches the new entry, which is open for
    that transaction and tier.
  - The senior then gets 409 `stale-alert`, as anyone would.
  - If the senior decides first, D1 and D2 apply to the new entry unchanged.

## 5. What each queue lists (M8's item 5)

A queue lists **open entries only**.
- **Deciding** takes an entry out of every queue at once, because its status leaves open in D1.
- **Undo** puts it back with the next `version`.
- **Escalating** replaces the original with the new entry in the escalated queue.
- **standard:** tiers HIGH and MEDIUM, `escalated_from_alert_id` NULL.
- **anomaly:** tier ANOMALY, `escalated_from_alert_id` NULL.
- **escalated:** `escalated_from_alert_id` not NULL, with the target role at or below the caller's,
  plus entries the caller escalated.

So the console's rule, not re-reading the feed until the next refresh or an Undo, is no longer
needed for correctness. A re-read cannot bring a decided alert back.

## 6. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | An entry has at most one live analyst decision, even under concurrent decides with different keys | D1(b,e), §3.4 |
| P2 | A decision is committed or undone, never both. A commit is never before `undo_until` by DB time; an undo is never at or after it | §3.2, §3.3, D3(e), D4(b) |
| P3 | An undone decision has no effects. A committed decision's effects run after its commit row, and their outcome is the same however many times they run | D4, D5 |
| P4 | Exactly one of the analyst and the timeout decides a held transaction. A claimed hold is never timed out while its decision is live. An undone decision's hold is timed out by its deadline, or within 10 s of it | D2, D3(h), D2(f) |
| P5 | A replayed decide with the same key and body returns the same `decision_id` while undoable, and 409 after. A different body with the same key gets 409 `idempotency-conflict` | D1(a) |
| P6 | A stale `alert_version` gets 409 with the current alert, on decide, escalate and override | D1(d), D6(a), D7(a) |
| P7 | A decision commits on any surviving instance after the deciding instance dies | D4 |
| P8 | The breaker counts a transaction's confirmation at most once, across retries, decisions and overrides | E2 |
| P9 | DECIDED, UNDO, COMMITTED, ESCALATED and SENIOR_OVERRIDE are each audited in the same transaction as their change | D1(h), D3(f), D4(b), D6(e), D7(d) |
| P10 | Redis unavailable: a MEDIUM decide gets 503 and changes nothing | D2(d) |

## 7. Accepted costs

- **A decided MEDIUM transaction stays held for G plus P, plus the effects time,** longer than an
  immediate decision would keep it. D-44 accepts this ("≤ 2 s server processing + 5 s grace").
- **The analyst's countdown runs on their device, and the server's on DB time.** A click near the
  end of the five seconds can get 409 `undo-window-expired`. The console should stop offering undo
  a little before `undo_until`.
- **A downgrade by override leaves the breaker's count in place** until its bucket expires.
- **While Redis is unavailable,** MEDIUM decisions are refused (503), and claimed holds wait for
  their effects (Q3).

## 8. Questions for the owner (not decided by this contract)

- **Q1.** MARK_LEGITIMATE on a HIGH (auto-blocked) alert records a label and leaves the block. This
  follows `DecisionState`: only customer verification or a senior override lifts a block. Should
  the analyst's decision lift it instead?
- **Q2.** Escalation does not stop the review timer, so a MEDIUM escalation is usually auto-released
  before a senior sees it (D-10's arithmetic). The alternative is for escalation to claim the hold
  and stop the clock, which would keep the customer's transaction held for an unbounded time.
- **Q3.** While the effects cannot run (Redis or the spool unavailable), a claimed MEDIUM
  transaction stays held, with an alert. The alternative is to let the timeout policy act after a
  bound, which would contradict a committed analyst decision.
