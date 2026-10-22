# Review 4: the decision-owner contract (draft 4), end to end

Subject: `docs/architecture/decision-owner-contract.md` (draft 4), with ADR 0114, ADR 0111 and
ADR 0113, at local commit `19ef238` (worktree `fs-wt/m8b`).

Reviewer: independent and adversarial. I wrote none of this contract, none of the old contract's
drafts, and none of the earlier reviews, reviews 1 to 3 included.

Method: reading only. No build, test, container or network call was made. Contract line numbers
are `C:<n>` at `19ef238`. Code paths are abbreviated as in reviews 1 to 3:
- `decision/` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`;
- `notify/` is `backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`;
- `ingest/` is `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/`;
- `audit/` is `backend/audit/src/main/java/io/github/mariusbayizere/fraudshield/audit/`;
- `V<n>` is `backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

**Scope, as the owner's lesson asks.** I reviewed the whole contract, §1 to §17, not its diff from
draft 3. That covers the rule table and its precedence, the lock order and both exceptions, the
first-decision lock, owner creation, §6.1's sink cases, §6.2 to §6.5, §7 (detection, exit,
resolver, alarms, residual, lease), §8's poll, §9's D1 to D6, §10, §11, §12 and §14. Underneath, I
read and re-checked against the owner:
- in `decision/`: `DecisionService`, `HoldTimeoutService`, `RedisHoldSchedule`, `ResilientPorts`'
  local fallback, `PostgresSink` (batch, per-record fallback, `skip`, `sameFirstDecision`,
  `decisionChanged`), `Tenant`, `adapter/jpa/TenantTransactions`, `DecisionTransitionService`,
  `DecisionState.allowed`, `KafkaMessages.changeAction`;
- in `ingest/`: `RedisIdempotency` and its claim script, `ResilientIdempotency`,
  `JdbcIdempotency`, `IngestService`, `IngestController`'s poll, `DecisionWiring`,
  `BackgroundTasks`;
- in `notify/`: `VerificationService`, `UnblockReconciler`, `CustomerSmsSender`'s classification,
  `WebhookDispatcher.enqueue`;
- in `audit/`: `TenantTransactions` and `AuditAutoConfiguration`;
- every module `pom.xml`;
- V3, V4, V5, V8, V62, V64, V71, V80 (and V11's grants by reference);
- ADRs 0067, 0111, 0113 and 0114.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: NO.** I found no new MAJOR, and none of the findings below is an accepted residual
re-raised.

**MAJOR in the owner mechanism: NO.**

| # | Severity | Origin | Subject |
|---|---|---|---|
| 1 | MINOR | EDGE SPECIFICATION | No alarm sees an analyst decision that is past its undo window and not committed. On a payment that is not held, the customer's "yes" waits behind it unseen |
| 2 | MINOR | EDGE SPECIFICATION | An undo after a RESOLVE reopens a closed entry. A reopened MEDIUM entry can never be decided or closed. Only a RESOLVE within G of the decision reaches it |
| 3 | MINOR | EDGE SPECIFICATION | D5's text was not updated to §5's new 409s, and an override's `supersedes_label_id` names a label that does not exist when the original has none |
| 4 | MINOR | EDGE SPECIFICATION | Module placement contradicts itself for D3 and D4. Where the five new periodic workers and the alarms run is unstated, and M6's two-thread scheduler and unbounded `tick` loop can starve them |
| 5 | MINOR | EDGE SPECIFICATION | §4.3's "unique violation is a re-run" cannot work as written in PostgreSQL, and its only case is unreachable. RELEASE_FAILED's request id is missing from §4.5. The SQL listers need the name-based ids computed in SQL, which is unstated |
| 6 | MINOR | EDGE SPECIFICATION | The deferred-answer gauge's age has no stated origin. Measured from the response, it raises a false alert after every escalation window or contest. A NOT_ME the reconciler cannot submit is not gauged |
| 7 | MINOR | EDGE SPECIFICATION | `lock_timeout` must be per runner transaction. If it is set for the role, the sink inherits it, and SQLSTATE 55P03, which `Tenant.transientError` does not retry, dead-letters a first decision |
| 8 | MINOR | INHERITED FROM M6 | §7.3 and §7.5 leave out the order in which the TIMEOUT comes before the retry. PostgreSQL then holds the evidence (the owner row), and the verification does not read it |

**What the verdict says.**
- **The owner mechanism holds,** for the third review running. I found no defect in the owner row,
  its lock, or the rule table's ordering. Under the stopping rule the author does **not** stop.
- **Review 3's two MAJORs are answered.**
  - The failed-claim gauge covers the unheld payment.
  - `decision_owners.resolution_id` makes the value of a resolution final through the API against
    every request (see "Attack points", point 3).
- **Why SOUND_WITH_CHANGES and not SOUND.** Eight MINORs need local text changes. Findings 1 and 2
  sit close to the MAJOR line. Each is rated MINOR, and each says why, so that the owner can
  re-weigh it. Each has a local fix.

---

## Findings

### 1. MINOR (EDGE SPECIFICATION): an analyst decision past its undo window and not committed is seen by no alarm

**Rules:**
- §7.4, `fs_customer_answers_deferred_oldest_seconds`: an answer waiting "on a claim whose effect
  is pending … is bounded by G + P and the pending-effects alert" (C:804-807);
- `fs_analyst_effects_oldest_pending_seconds` counts "**committed** analyst decisions with no
  effects row" (C:808-809);
- D3: "Bound: `undo_until + P + d` while PostgreSQL is up" (C:907-913). D3 does not say that it
  isolates a failing decision. D4(e) says so for effects (C:935-938), and §2 says so for the timeout
  paths (C:119-124).

**The gap.** Between `undo_until` and its commit row, an analyst decision holds an ANALYST claim
that no gauge counts:
- the pending-effects gauge needs a commit row;
- the failed-claims gauge needs a FAILED effects row;
- the deferred-answer gauge excludes answers behind a pending claim.

If the committer cannot commit, the claim stays. The customer's "yes" is DEFERRED (§5 CUSTOMER_YES
step 1), OVERRIDE and ESCALATE answer 409, and nothing alerts. Two ways the committer can fail to
commit:
- **it stalls,** for example its audit write fails;
- **it stops at its first failure, oldest first,** as M6's `tick` and `UnblockReconciler` do
  (`notify/verification/UnblockReconciler.java:106`). One bad decision then blocks every later one.

**Why MINOR and not MAJOR.**
- **A held payment is still seen.** The overdue gauge reports a held payment caught this way, and
  any stall that affects every decision soon catches a held MEDIUM decision.
- **The exposure is narrow.** It is a payment that is not held, behind a stall that affects only
  some decisions. I found no failure of D3's transaction that would affect only some decisions:
  the commit row, the commit trigger and the audit append do not depend on the decision's contents.
- **It matches review 3's grading.** Review 3 graded a worker backlog with no gauge as MINOR
  (review 3, finding 8). It graded review 3's MAJOR 1 higher because the contract itself modelled
  the permanent failure there (D4(e)).
- **The coverage sentence in §7.4 is still too strong,** and the gap has the same shape as review
  3's MAJOR 1.

**Fix.**
- Make the pending-effects gauge count every analyst decision past `undo_until + P` that has no
  undo row and no effects row, whether committed or not. Alternatively, add
  `fs_analyst_commits_overdue_oldest_seconds`.
- State that D3 catches failures per decision and moves on.

### 2. MINOR (EDGE SPECIFICATION): an undo after a RESOLVE reopens a closed entry, and a reopened MEDIUM entry is stuck

**Rules:**
- RESOLVE closes **open** entries only (C:393, C:713-716). An entry whose analyst decision is still
  inside its undo window is not open (D1(f) set its status).
- RESOLVE clears the ANALYST claim (C:393, C:724-727).
- D2(d): "RELEASE (§5). Restore `prior_status`, `version + 1`" (C:902). This happens even when
  RELEASE has "nothing to do" (C:387).
- §10: "A timeout or a resolution closes the transaction's entries" (C:1003).

**Interleaving.**
1. An analyst decides a held payment's MEDIUM entry (CLAIM ANALYST).
2. Within G, the contest is detected, through the TIMEOUT's basis check (which comes before the
   claim check) or through the sink.
3. An operator calls `resolve_contested_decision`, and the resolver applies RESOLVE. Both happen
   still within G of step 1. The owner is no longer HOLD, and the claim is cleared.
4. The analyst undoes. RELEASE does nothing, but D2(d) restores the entry to PENDING.

**The result.** An open MEDIUM entry on a transaction that is not held:
- D1(e) answers `review-deadline-passed`, because the owner is not HOLD (C:890-892);
- D6(c) answers `stale-alert`;
- D5(b) finds no original, because the latest decision is undone;
- no TIMEOUT will run, and RESOLVE has already run.

The entry stays in the standard queue for ever, and no gauge counts open entries. For a HIGH or
ANOMALY entry the reopened entry can still be decided, so only MEDIUM is stuck.

**Why MINOR.** Step 3 needs a manual operator to resolve within 5 s of detection. The function
refuses until the transaction is contested, and the alarm is sampled every 30 s. With resolution
as a manual function this cannot be reached in practice. It becomes reachable if M9 ever automates
resolution.

**Fix.** Choose one:
- D2(d) restores `prior_status` only when RELEASE released this decision's claim. Otherwise it
  closes the entry as OVERRIDDEN with the owner's `resolution_id`.
- RESOLVE also stamps entries whose analyst decision is neither committed nor undone, and D2
  leaves a stamped entry closed.

### 3. MINOR (EDGE SPECIFICATION): D5's text lags §5, and an override's `supersedes_label_id` can name no label

**D5(d) versus §5.**
- §5 gives OVERRIDE four refusals (C:390, C:411):
  - `decision-effect-failed`;
  - `stale-alert` when resolved;
  - `stale-alert` on HOLD;
  - `decision-not-committed`.
- D5(d) (C:949-953) still lists only "Contested" and "A pending ANALYST claim", then "Otherwise the
  outcome is written".

An implementer reading D5 alone writes the outcome in the three new cases. §5 is authoritative, but
§9 is where the operation is specified.

**The label.** "Every OVERRIDE outcome carries a label … whose `supersedes_label_id` is the
original's label" (C:413-414). D5(b) admits originals that have no recorded label:
- **AUTO_RELEASED and AUTO_BLOCKED** rows carry no label at all;
- **an analyst decision whose effect FAILED,** which RELEASE_FAILED makes overridable on a payment
  that is not held (C:394). Its `alert_label|` id was derived but never recorded.

The override's label then names a label id that does not exist. Two outcomes are possible:
- with a foreign key on the new column, the synchronous D5 fails with a 23503 and the senior gets a
  500;
- without one, `label_events` carries a dangling reference that retraining or a lineage query
  reads as a supersession.

**Fix.**
- Make D5(d) list §5's four refusals.
- State that `supersedes_label_id` is NULL when the original has no recorded label, meaning an
  AUTO_* original or an effects row that is FAILED or SKIPPED.
- State whether the column has a foreign key.

### 4. MINOR (EDGE SPECIFICATION): module placement contradicts itself, and the new periodic work has no stated home

**The contradiction.** §4.3 (C:264-270) places "the effects worker" in `decision` and "D1 to D6" in
`analyst`. D4 is the effects worker, and D3, the committer, is also one of D1 to D6.
- **D3 audits `COMMITTED`,** so it needs `audit` and must be in `analyst`. That is consistent with
  "D1 to D6" but leaves the effects worker named twice.
- **D4 needs no audit.** It can live in either module, but the text must pick one.

**The schedule.**
- The contract adds five periodic workers, the committer, the effects worker, the recorder, the
  resolver and the alarm computation, at P or 30 s.
- M6 runs its periodic work on one `ScheduledExecutorService` with **two** threads
  (`ingest/config/BackgroundTasks.java:81-91`).
- `HoldTimeoutService.tick` renews the leader lease (2 s, `RedisHoldSchedule.java:24`) only on
  entry. It loops `while (due.size() == BATCH)` (`HoldTimeoutService.java:70-82`).
- Under §2's per-hold catch and 1-second reschedule, a full batch whose requests each fail slowly
  is re-claimed for as long as they keep failing. One example is 500 holds each waiting out
  `lock_timeout`.

This is harmless to correctness:
- duplicate TIMEOUT requests serialise on the owner and return the stored outcome;
- a second "leader" after the lease lapses is equally harmless.

But a worker or alarm sharing that pool can be starved of a thread, and §7.4's "every 30 s" then
does not hold.

**Fix.**
- In §4.3 and §14, put the committer in `analyst` and the effects worker in one named module.
- Say which scheduler runs the new workers and the alarms, and give the alarms their own thread.
- Bound `tick`'s loop: re-check leadership per batch, or stop after one pass over the holds
  claimed at entry.

### 5. MINOR (EDGE SPECIFICATION): request-id mechanics that cannot be implemented as written

- **§4.3 step 2 (C:244-249).** "A unique violation on `request_id` … is treated as a re-run: the
  stored outcome is read and returned."
  - In PostgreSQL a 23505 aborts the transaction, so nothing can be read in it. It also rolls back
    `VerificationService.answer`'s response insert, which shares the transaction (§6.5).
  - The case it covers, CUSTOMER_NO with no visible sequence-1 row, is unreachable:
    - a response needs a verification, which needs an `auto_block_events` row (V5:42, V5:72);
    - the sink writes the auto-block in the transaction that writes sequence 1, and skips it when
      the record's TransactionDecided is dropped (`decision/adapter/jdbc/PostgresSink.java`,
      `write`, the `skip` flag).
  - **Fix:** write request rows `ON CONFLICT (request_id) DO NOTHING` and read the stored row when
    nothing was inserted, or delete the clause.
- **RELEASE_FAILED's request id.** §4.5's table (C:327-333) lists RESOLVE (`resolve|` + resolution
  id) but not RELEASE_FAILED. The resolver lists every resolution by `resolve|` + id (C:696-698),
  so the id is implied. Review 3's finding 3 asked for it to be stated. **Fix:** add the row.
- **Name-based ids in SQL.** Several anti-joins must compute a name-based UUID in SQL:
  - the reconciler's lister and V64's rewrite (`verification|` + `verification_id`);
  - the resolver's lister (`resolve|` + id);
  - V81's markers and backfill ids.

  Java's `UUID.nameUUIDFromBytes` is an MD5 version-3 UUID with no namespace. PostgreSQL can
  compute it from `md5()` by setting the version and variant bits, or a source-key column can be
  stored. Neither is stated, and a mismatch makes every answer look unsubmitted for ever.
  **Fix:** state the SQL derivation, with a test that it equals Java's, or store the source key.

### 6. MINOR (EDGE SPECIFICATION): the deferred-answer gauge's age, and NOT_ME

- **Age origin.** `fs_customer_answers_deferred_oldest_seconds` alerts above 60 s (C:804), but its
  age has no stated origin.
  - If the age is measured from `responded_at`, an answer released from an escalation window
    (W = 15 min) or from a contest enters the gauge already older than 60 s. It alerts until the
    reconciler submits it: up to one 5-second sweep plus up to 30 s of sampling.
  - The same happens after every RESOLVE that releases deferred answers.
  - These are false alerts on correct behaviour.
- **NOT_ME.** The reconciler now submits pre-V81 NOT_MEs that have no marker (C:303-305, C:627-629),
  but the gauge counts WAS_ME only.
  - A NOT_ME that fails on every run is seen by nothing.
  - If the rewritten reconciler keeps M6's "return on the first failure"
    (`notify/verification/UnblockReconciler.java:106`), that NOT_ME also blocks every WAS_ME queued
    behind it. The gauge then shows the WAS_MEs, but not the cause.
- **Fix.**
  - Measure the age from when the answer became submittable, meaning the later of `responded_at`
    and the end of its claim, contest or window. Alternatively, accept the flaps and say so.
  - Include NOT_ME in the gauge.
  - Require a per-answer catch in the reconciler, as §2 requires per hold.

### 7. MINOR (EDGE SPECIFICATION): `lock_timeout` must be per runner transaction, or the sink dead-letters first decisions

**Rules:** §2: "Every transaction here runs through the runner … with `lock_timeout` 2 s"
(C:110-111). The sink "keeps its own batch handling" (C:117-118) and waits on levels 4 and 5 (§3
exception 2, §6.1).

**Evidence.**
- `Tenant.transientError` retries classes 08, 40, 53, 57 and 58 only
  (`decision/adapter/jdbc/Tenant.java:43-51`).
- A lock timeout is 55P03, which is class 55.
- `PostgresSink.accept` handles a non-transient error by retrying record by record, then
  **dead-lettering** the record that still fails.

**The trap.** The contract does not say how `lock_timeout` is set. The simplest implementation, a
role-level default for `fs_app`, also applies to the sink, which connects as `fs_app` through the
same data source. Suppose the sink then waits more than 2 s on an owner row, for example behind a
resolver run or behind an operator session left open after `resolve_contested_decision`:
1. The sink gets a 55P03.
2. It retries that one record and gets another 55P03.
3. It dead-letters the bank's first decision: its transaction, scores, entry and auto-block.

Without any `lock_timeout`, the opposite holds. An operator's open transaction on the function
stalls the instance's PostgreSQL drainer for as long as it stays open. The spool-lag gauges would
show that.

**Fix.**
- State that the runner sets `lock_timeout` and `statement_timeout` with `SET LOCAL`, never for
  the role.
- State that the sink either waits or uses `statement_timeout` (class 57, transient), and treats
  55P03 as transient if it ever sets `lock_timeout`.
- Have both SQL functions `SET LOCAL lock_timeout`, and have the runbook call them in autocommit.

### 8. MINOR (INHERITED FROM M6): §7.3's residual leaves out the TIMEOUT-first order, in which PostgreSQL holds the evidence

**The residual stands as recorded, and I do not re-raise it.** Its interleaving and its non-outage
bound are true against the code:
- the poller fires every overdue member in batches (`HoldTimeoutService.java:70-82`);
- the claim script's UNCERTAIN branch (`RedisIdempotency.java:54-55`);
- `ResilientIdempotency.verify`.

**Two points of its statement are incomplete.**
- **The other order.**
  - §7.3 shows the retry deciding before X's TIMEOUT.
  - In the reverse order, the TIMEOUT has already created the owner from the `DueHold` and applied
    sequence 2 before the bank's retry arrives.
  - §7.5's verification reads only `transaction_ids`, `decision_states` and `v_fraud_scores`
    (`ingest/idempotency/JdbcIdempotency.java:28-37`). It finds no `transaction_ids` row and
    answers `Claimed` (line 80), so the retry decides afresh. Yet `decision_owners` and
    `transaction_requests` already show that the transaction was decided.
  - So there is a PostgreSQL-side narrowing option beside the Redis-as-detector option (C:781-786):
    the verification reads the owner, and answers InFlight or replays from the owner's first
    decision. It has its own cost, a 503 for as long as X never drains, and is the owner's to
    weigh. §7.3 and §11 present the Redis option as the only one.
- **The frequency wording.**
  - "Any PostgreSQL outage during which an instance also dies with undrained HOLDs and banks retry
    produces this case" is true only for retried transactions whose idempotency record is not DONE.
  - A bank retries only without a response. That covers three cases:
    - an instance dying between `holds.schedule` (`DecisionService.java:253`) and `complete`
      (`IngestService.java:269`);
    - a failed `complete`;
    - Redis data loss, which forces verification through the claim script's missing `verifyUntil`
      key.
  - This errs towards caution and is not false. It should name that condition.

**Fix.** Add the TIMEOUT-first order and the owner-row option to §7.3 and §11. Qualify "for every
such transaction" by the not-DONE condition.

---

## Review 3's findings: does draft 4's answer hold?

| # | Review 3 | Draft 4's answer | Holds? |
|---|---|---|---|
| 1 | MAJOR (edge): a FAILED claim on a payment that is not held defers the "yes" with no alarm; the 409 says "pending" | `fs_analyst_claims_failed` (alert above zero); OVERRIDE 409 `decision-effect-failed`; §11 addition; P8e | **Yes.** The gauge counts exactly the stuck claim, and RESOLVE or RELEASE_FAILED clearing the claim removes it from the count. A neighbouring gap (a claim stuck before commit) is finding 1, MINOR. D5(d)'s text was not updated (finding 3) |
| 2 | MAJOR (edge): a resolution is not final | `decision_owners.resolution_id`; OVERRIDE refused while set; P8f | **Yes**, for the value. Re-checked against every request in "Attack points", point 3: no request can change the resolved value through the API. A neighbouring edge (an undo reopening an entry after RESOLVE) is finding 2 |
| 3 | RELEASE_FAILED's success has no outcome | Outcome RELEASED, recorded with no event; a re-run returns it | **Yes.** The request id is still missing from §4.5's table (finding 5) |
| 4 | OVERRIDE on HOLD reachable after RELEASE_FAILED | §5 cell: 409 `stale-alert`; the timeout decides | **Yes.** I re-derived reachability: every other route to an overridable original leaves the owner out of HOLD (EFFECT on HOLD always applies, TIMEOUT and RESOLVE always leave HOLD, and the AUTO_* originals follow a TIMEOUT). Only RELEASE_FAILED reaches the cell |
| 5 | Module placement and the caller's connection | §4.3: `analyst → decision`, the resolver in `analyst`, the caller's bound connection | **Yes**, against the poms and the wiring. `analyst` depends on `auth`, `rules` and `audit`, and `decision` on `common` and `rules`, so no cycle forms. Both runners share one transaction manager and data source (`DecisionWiring`'s `decisionTenantTransactions`; `AuditAutoConfiguration.tenantTransactions`). Residue: D3 and D4 are named twice, and the schedule is unstated (finding 4) |
| 6 | (M6's) a NOT_ME whose label was never spooled | Marker only with a CUSTOMER label; the reconciler submits CUSTOMER_NO; P8g | **Yes.** V5 allows one auto-block per transaction (V5:12), one verification per block (V5:42) and one response per verification (V5:66). So a CUSTOMER label on the transaction can only come from that response, and the per-transaction test is exact. Residue: no gauge for NOT_ME (finding 6) |
| 7 | Backfill request ids | `backfill|` + event id | **Yes.** Losing "one timeout per transaction" across the cutover is harmless: a backfilled timeout leaves the owner out of HOLD, and every TIMEOUT needs HOLD |
| 8 | No gauge for the recorder's backlog | `fs_requests_unrecorded_oldest_seconds` (above 60 s) | **Yes.** It counts APPLIED and LABEL_ONLY only, as the recorder does. RELEASED, PRE_V81 and REFUSED rows are never recorded and never counted |
| 9 | A second ESCALATE after W opens no window | A lapsed window on a payment that is not held opens afresh | **Yes.** For HOLD, ESCALATE still needs DB time < `deadline`. On a payment that is not held, the chain is at most two escalations, by D6(b)'s rank rule, and the gap between windows lets a deferred "yes" through. So the deferral stays bounded by W per escalation |

## Attack points, answered

1. **Paths that move state outside the owner.** None found.
   - Every `DecisionChanged` in M6 comes from `DecisionTransitionService` (removed) or
     `HoldTimeoutService.timeOut` (made a request). The only other construction is decoding, in
     `FactCodec`.
   - `states.save` callers are cache writes.
   - The sink writes sequence 1 only and stops closing entries.
   - The SQL functions write no state.
   - Every `decision_states` reader is moved to the view or is sequence-1-only. I checked by
     `grep`: `AccountQueries`, `JdbcDecisionStates`, `JdbcOverdueHolds`, `UnblockReconciler`,
     `CustomerSmsSender`, `JdbcIdempotency`, V62 and V64. Because `decision_states` is append-only,
     its pre-V81 rows after sequence 1 stay, so the view must filter it to sequence 1, as §4.7 says.
2. **Waits, and the alarm that sees each.**
   - **A held payment:** the overdue gauge, from owner rows and from orphan sequence-1 HOLDs. The
     stated exceptions are an outage and a hold with neither row.
   - **An unheld payment's "yes":**
     - behind a contest: the contested gauge;
     - behind a committed effect: the pending-effects gauge;
     - behind a FAILED effect: the failed-claims gauge;
     - behind an escalation: W;
     - otherwise: the deferred gauge;
     - behind a decision not yet committed: nothing (finding 1).
   - **An analyst decision:** pending or deferred by a contest, it shows in the pending-effects and
     contested gauges. Uncommitted, it shows in neither (finding 1).
   - **A resolution or release that fails permanently:** the contested gauge or the failed-claims
     gauge stays on.
   - **A recorded request:** the unrecorded gauge.
   - **An entry:** open entries on payments that are not held are queue work and decidable. The
     one entry that cannot be decided is finding 2's.
3. **`resolution_id` against every request.**
   - **OVERRIDE:** refused.
   - **EFFECT:** cannot change the value.
     - After a resolution to DECLINE: CONFIRM_FRAUD gives DECLINE → DECLINE by ANALYST, and
       MARK_LEGITIMATE gives LABEL_ONLY.
     - After a resolution to APPROVE (by SENIOR_OVERRIDE): both give LABEL_ONLY.
   - **CUSTOMER_YES:** after DECLINE by SENIOR_OVERRIDE it is `human-declined`; after APPROVE it is
     LABEL_ONLY. So the APPROVE-by-CUSTOMER_VERIFICATION route never opens after a resolution.
   - **CUSTOMER_NO:** a label.
   - **ESCALATE, CLAIM and RELEASE_FAILED:** no state.
   - **TIMEOUT:** a resolution never leaves HOLD.
   - **A later contest:** blocks OVERRIDE itself, and a new RESOLVE replaces the id.
   - **Nothing is stranded** except finding 2's undo edge. The FAILED claim a RESOLVE clears leaves
     the failed-claims count. The analyst decision whose claim it clears ends LABEL_ONLY.
4. **The NOT_ME marker and CUSTOMER_NO.** Exact under V5's cardinalities (table, row 6).
   CUSTOMER_NO's label id is derived (`verification_label|`), so re-runs land once in
   `label_events` (`ON CONFLICT DO NOTHING`).
   - **Residue:** an M6 label that was dead-lettered and is later replayed by M9 would land
     beside the derived one, because M6's ids are random. That is a duplicate training label, not a
     state.
   - **The gauge gap:** finding 6.
5. **RELEASED, RELEASE_FAILED and the HOLD cell.** Sound (table, rows 3 and 4).
   - The reschedule after RELEASED is covered by the sweep if the resolver dies before it.
   - The TIMEOUT then closes the FAILED-and-released entry with an AUTO_* row. V4:60's `analyst_id`
     rule lets it through.
6. **ESCALATE's lapsed-window rule.** Sound (table, row 9).
   - It keeps V81's CHECK (`deadline` set under ESCALATED).
   - CLAIM's `prior_deadline` restore is unaffected.
7. **Module placement and the caller's connection.**
   - Sound against the poms and the Spring wiring (table, row 5).
   - Both runners are `TransactionTemplate` plus `JdbcTemplate` over one transaction manager, so a
     `DataSourceUtils` connection inside either is the transaction's.
   - The resolver on the audit runner gets V8's 40001 retry (`audit/jdbc/TenantTransactions.java`)
     and needs P2's 40P01.
   - Residue: findings 4 and 7.
8. **§11, §12 and §15 to §17.**
   - **§12** is complete for the promises it intends to retract; I found none silently dropped.
   - **§11** omits finding 1's gap and finding 8's option.
   - **§15 and §16** stand as earlier reviews assessed them. §16's row 7 describes the
     entry-stamped `resolution_id` that draft 4 replaced; §17 row 2 records the replacement.
   - **§17** is true, with the residues in the table above.

## Mechanisms checked and found sound

- **The owner row and its lock.**
  - Every request takes level 5 before it reads, then looks up its request id. CUSTOMER_NO without
    a row is unreachable (finding 5).
  - DEFERRED results and refusals that are not recorded write nothing, so they re-evaluate afresh.
  - The one-timeout request id is never consumed by a refusal.
  - The APPLIED-sequence trigger backs the row lock up.
- **Owner creation and the first-decision lock.** Both orders of sink and creator hold at READ
  COMMITTED:
  - `INSERT … ON CONFLICT DO NOTHING` under level 4;
  - the sink keeping level 4 until its batch commits;
  - every case of §6.1 reading the owner under level 5.
- **The rule table's precedence.**
  - Contested and not-visible come first.
  - TIMEOUT's order is contested, basis, HOLD, claim, deadline.
  - CUSTOMER_YES's order is claim, human decline, model or policy decline, approved. A HOLD row is
    missing, but a customer answer on HOLD is reachable only while contested, where the contest
    check answers first.
  - EFFECT and OVERRIDE go by state, with the new HOLD, FAILED and resolved cells.
  - CLAIM and TIMEOUT are exclusive on DB time.
  - `DecisionState.allowed` today (`decision/domain/DecisionState.java:131-143`) confirms §5.1's
    count of seven new transitions and its "already allowed" list.
- **The lock order and both exceptions.**
  - I walked D1, D2 (the commit and undo trigger's level 3, V4:96), D3, D4, D5 (commit on insert,
    then level 5), D6, the customer path, TIMEOUT (including a TIMEOUT that races D6 and sees the
    new entry only in its closing UPDATE), RESOLVE, and the sink.
  - The only cycles are exception 1's late entry and exception 2's sink pair. Both are 40P01, which
    the runner (after P2) and `Tenant.transientError` retry.
  - The audit chain is last everywhere (V8:88-94).
- **§6.1's five cases and §6.2,** against `PostgresSink.write`, `transactionDecided` and `skip`.
  Re-delivery, case 3 then case 1(b), case 4's swap of `first_event_id`, and case 5 all keep one
  sequence 1 and close each late entry once.
- **§6.3,** including the poller's early-refusal reschedule against a fast JVM clock. That is
  bounded by the clock skew.
- **§6.4's backfill,** and its `backfill|` ids.
- **§6.5's single customer transaction.** It keeps V5's expiry trigger and its unique response.
- **§7.1's insert-only re-contest rule.**
- **§7.2's resolver,** for RESOLVE and RELEASE_FAILED, with V5's unblock CHECKs on cause and actor.
- **§7.5's lease change,** against the claim, complete, release and uncertain scripts:
  - a slow first attempt whose complete overwrites the retry's INFLIGHT gives both callers one
    response;
  - the double decision is detected when both drain.
- **§8's poll order.** The webhook's supersede rule delivers sequences 2 and up only, and drops a
  late lower sequence (`notify/webhook/WebhookDispatcher.java`, `enqueue`).
  `CustomerSmsSender`'s "resolved" test, moved to the view, gives §6.1 case 5's stated SMS
  behaviour.
- **A3's stop-and-drain** for spools and old-format `DueHold` members.
