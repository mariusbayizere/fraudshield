# Review 4: the analyst decision commit and undo contract (draft 4)

Subject: `docs/architecture/analyst-decision-commit-contract.md` at `5873764` (branch `m8b/analyst-api`).

Reviewer: independent. I wrote none of the drafts and none of reviews 1 to 3.

Method: reading only. No build, test or container was run. Contract line numbers (`C:<n>`) and code
line numbers are at `5873764`, read with `git show 5873764:<path>`. Code paths are abbreviated:
`decision/` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`, and
`V<n>` is `backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

Scope, as instructed: review 3's three MAJORs, the owner's Q1 to Q3, and anything draft 4's own
changes introduced. Review 3's MINORs m4 to m12, carried in §12, are not re-reported. None of them
was made worse.

## Verdict: SOUND_WITH_CHANGES

**New MAJOR caused by draft 4's own changes: YES (finding 1).**

- **The arbiters still hold.** I found no interleaving in which both an analyst (or senior) and a
  timeout decide a held transaction. I also found none in which two writers reserve one sequence
  through T2, including with the new ESCALATED owner, re-escalation, and an undo that restores
  ESCALATED.
- **Finding 1 (MAJOR, new).** It is caused by MAJOR 1's fix: H2 requires sequence 1 to be visible,
  and T2's Redis exception is withdrawn. A MEDIUM hold whose ingest record never reaches PostgreSQL
  is then never timed out. It is rescheduled every second, for ever. The case is narrow:
  - a record `PostgresSink` dead-letters;
  - or a spool that never drains.

  Draft 3 and M6 both time such a hold out. It is a regression, and it falsifies C:203-205's bound.
  Under the owner's rule this calls for simplification, not another patch. A simplification is
  suggested below.
- **Finding 2 (MAJOR, residual, not new).** It is what remains of review 3's MAJOR 2. A3's cutover
  stops only *ingest*. The poller, the sweep, the reconciler, the verification endpoint and the
  analyst API run in the same process, and keep appending to the spool after "lag is zero" has been
  observed. A3's consequence "no older spool record drains after it" (C:94) is therefore false. The
  window is narrow, and the fix is one runbook line.
- **Findings 3 to 9 are MINOR.** Four of them come from draft 4's text (3, 4, 5, 9). Finding 6 is
  also draft 4's wording, but it changes nothing for the worse compared with draft 3.

## Review 3's MAJORs

| # | Review 3 MAJOR | Status | Why |
|---|---|---|---|
| 1 | A timeout committing before its alert entry existed, leaving that entry open for ever | **RESOLVED** (with MINOR 8) | See "Why MAJOR 1 is resolved" below. |
| 2 | A3 false; a one-shot backfill; the reconciler starved | **PARTLY** | See "Why MAJOR 2 is only partly resolved" below. |
| 3 | The override's `unblock_events` write fails, and the worker stalls | **RESOLVED** (with MINORs 5, 6 and 7) | See "Why MAJOR 3 is resolved" below. |

**Why MAJOR 1 is resolved.**
- **The row and the entry commit together, on every path.**
  - `DecisionService` puts `TransactionDecided` and `AlertRaised` in one spool record, for the
    model path and the fallback path alike. The fallback changes only the `ScoringRecord`
    (`decision/application/DecisionService.java:209-240, 302-309, 385-407`).
  - `PostgresSink.accept` writes a batch in one transaction (`PostgresSink.java:186-194`). On a
    non-transient error it retries record by record, committing each one (`:201-212`).
  - `write` handles each record's facts in order, writing the alert only when
    `transactionDecided` succeeded (`:239-249`).

  So the `decision_states` sequence-1 row (`:336`) and the entry (`:373-386`) commit in the same
  transaction, or not at all, whether in a batch or in a record-by-record retry. A dead-lettered
  record loses both (`:210`, `:218-237`). That is finding 1.
- **What draft 4 changes.**
  - The timeout now requires sequence 1 to be visible (C:186-191).
  - T2's Redis exception is withdrawn (C:256-263).
  - A refused claim rolls back whole and reschedules (C:190).
- **The rest of the path is safe.** T2's other writers (C:259-261) are verified below. The
  backstop is unreachable under H2, so it is harmless, but see finding 8.

**Why MAJOR 2 is only partly resolved.**
- **(b) The recordings: resolved.** The backfill writes recording rows (C:96-97).
- **(c) The reconciler: resolved.** It treats an unblock as handled if a `decision_states` row by
  CUSTOMER_VERIFICATION exists (C:275-277). That is what the existing query already does
  (`UnblockReconciler.java:44-53`; `V64:21-24`), so historical unblocks are not re-selected.
- **(a) The cutover: not closed.** The stop-and-drain cutover (C:84-97) is the right shape, but
  step 1 is too narrow (finding 2). Review 3's belt-and-braces suggestion was not adopted: T2's
  "latest" still ignores `decision_states` rows above sequence 1 (C:252). So any record that slips
  past the cutover still doubles a sequence.

**Why MAJOR 3 is resolved.**
- **(a) and (b).** The write is made only when an auto-block exists, with `ON CONFLICT DO NOTHING`
  (C:356-360). With no conflict target, that clause covers `auto_block_event_id`'s UNIQUE key
  (`V5:93`). The row needs `actor_user_id` (`V5:102`), which D4(c) leaves implicit.
- **(c) The stall.** D4(e′) records a permanent failure and moves on (C:368-375).

## New findings

### 1. MAJOR (new, caused by draft 4): a hold whose ingest record never reaches PostgreSQL is never timed out

**Stems from draft 4:** yes. It comes from MAJOR 1's fix: H2's sequence-1 requirement (C:186-193)
together with the withdrawal of T2's Redis exception (C:256-257).

**Rule:**
- H2 (C:186-191): "When the row is not visible: the claim rolls back, the path reschedules the
  hold 1 s later". No bound is stated.
- H3 (C:213-218).
- The Q3 text (C:202-205): "every MEDIUM payment stays held until … the sink has written its
  sequence-1 row. The breach of D-18's ±500 ms is bounded by the outage's duration plus the sink's
  catch-up."

**Why it can happen:**
- **The sink is allowed to give up on a record.** It dead-letters any record PostgreSQL refuses for
  a non-transient reason, rather than block the spool (`PostgresSink.java:201-212, 218-237`; ADR
  0064 point 3). That record held both the sequence-1 row and the entry, so neither will ever
  exist.
- **No replay tool exists.** A replay tool for the sink's dead-letter files is "carried to M9" (ADR
  0056:54).
- **The ingest path still holds the payment.** It schedules the hold in Redis or the local
  fallback (`DecisionService.java:251-263`). Kafka has its own consumer, so the bank received the
  HOLD.

**Interleaving:**
1. Instance A ingests MEDIUM transaction X. The spool record is durable, the HOLD is answered, the
   Redis state is saved (`:247`), and the hold is scheduled for t+30 s.
2. A's PostgreSQL consumer refuses X's record with a class-22 or class-23 SQLSTATE. Any divergence
   between ingest validation and a V3 CHECK is enough. The record is dead-lettered and the
   consumer commits past it.
3. At t+30 s the poller claims X. Sequence 1 is not visible, so the claim rolls back and reschedules
   at t+31 s. This repeats every second, for ever. The case is counted (C:191), and nothing else
   happens.
4. Nothing else can resolve X:
   - H3 selects from `decision_states` (`JdbcOverdueHolds.java:28-40`; `V62:18-23`), so it never
     sees X;
   - no entry exists, so no analyst can decide it;
   - MEDIUM has no auto-block, so no customer path exists.
5. **Result.** X's payment is held indefinitely. The transaction is never decided after its HOLD,
   and C:205's bound is false.

Under draft 3, and under M6 today (`HoldTimeoutService.java:117-134`), the poller times X out from
the Redis state at t+30 s and the release reaches the bank through Kafka.

**A second trigger.** An instance whose spool volume is lost, or which is never restarted, holds
every MEDIUM transaction it ingested but did not drain. This may be what "the sink's catch-up"
intends. If so, the bound should say it lasts until that instance returns.

**Reach:** narrow. It takes a sink dead-letter, or a spool that never drains. Both paths are
designed and counted, so this is reachable, not hypothetical. The poller also spends one claim
transaction per second on each such hold, for ever.

**Suggested fix.** The owner's rule asks for a simplification, not another clause. Two options:
- **(A) Simplest. One mechanism instead of two.** Drop H2's sequence-1 requirement.
  - Make the sink's backstop the guarantee, and make it race-free: the sink upserts the
    `hold_owners` row (`ON CONFLICT DO NOTHING`) and takes it `FOR SHARE` before inserting the
    entry. When the owner is TIMEOUT, it inserts the entry already closed, *with* its AUTO_* row.
  - The timeout's T2 takes sequence 1 from the DueHold or the Redis state, which ingest saved.
  - **Why this works.** The claim's `hold_owners` upsert and the sink's share lock serialise. Either
    the entry is committed before the claim's later statements run, and the claim closes it; or
    the claim's TIMEOUT is committed before the sink reads the row, and the sink inserts the entry
    closed. That closes review 3's MAJOR 1, including its READ COMMITTED window, with no wait on
    the sink at all. Q3 still holds: there is no claim without PostgreSQL.
- **(B) Keep H2 and add a bound.** After the deadline plus a stated bound with sequence 1 still
  absent, proceed as in (A). This needs (A)'s backstop anyway, so it is strictly more mechanism
  than (A).

Either way, add a property: a MEDIUM transaction whose ingest record is dead-lettered is still
timed out within a stated bound.

### 2. MAJOR (residual of review 3's MAJOR 2, not new): A3's cutover stops only ingest, while the same process keeps appending to the spool

**Stems from draft 4:** no. It is review 3's MAJOR 2(a) not fully closed. A3's text is draft 4's,
but its incompleteness leaves review 3's interleaving reachable. It does not create a new failure
mode.

**Rule:** A3 (C:84-97), in particular step 1 "stop every instance's ingest" and the consequence "no
older spool record drains after it" (C:94). T2 step 3 (C:252).

**Evidence:**
- One process runs every writer:
  - the hold poller and the sweep (`backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/config/BackgroundTasks.java:83-84`);
  - the reconciler (`:87`);
  - `VerificationService`, which calls `customerAnswered` (`DecisionWiring.java:303-320, 407-409`).
- Every one of them records through the same spool (`DecisionTransitionService.java:127, 159,
  167`; `HoldTimeoutService.java:124`).
- `SpoolDrainer.close()` stops without draining (`SpoolDrainer.java:142-151`).
- `fs_spool_lag` exists per instance and consumer (`DecisionWiring.java:138-146`), so zero lag *is*
  observable. But it is a moment, not a state, while writers still run.

**Interleaving:**
1. Step 1 stops ingest. The operator sees `fs_spool_lag` at zero on every instance (step 2).
2. Before step 3, a customer answers "Yes" on the still-running verification endpoint. On the old
   release this writes `unblock_events` and appends sequence 2 with random ids. A hold ingested
   just before step 1 timing out on the old poller has the same effect.
3. Step 3 stops the instance. The record is still in the spool, because `close()` does not drain.
   A transient PostgreSQL error in the drain's backoff widens this window to seconds.
4. V80 runs. Its backfill does not contain this row. The new release starts and drains the record:
   `decision_states` now has a sequence 2 with no reservation.
5. Any later writer, for example an override, runs T2. T2 ignores that row (C:252) and reserves
   sequence 2 again. `PostgresSink` keeps the first sequence 2 (`PostgresSink.java:88-93`, `ON
   CONFLICT DO NOTHING`), so the override's state is dropped from PostgreSQL while Kafka carries
   both. That loses or doubles an effect, exactly as review 3 described.

**A second gap.** The M9 replay tool for sink dead letters (ADR 0056:54) would replay pre-V80,
non-reserving records after V80. A3 does not exclude it.

**Suggested fix (text only):**
- Step 1 becomes "stop every spool writer on every instance": ingest, the analyst operations, the
  verification endpoint, and `BackgroundTasks`, which runs the poller, the sweep, the reconciler and
  the breaker monitor.
- Then wait for zero lag, then stop.
- State that pre-V80 dead letters are never replayed after V80, or are replayed only through a
  path that reserves.
- Optionally, adopt review 3's belt-and-braces: T2's "latest" becomes the higher of the highest
  reservation and the highest `decision_states` sequence. That makes the cutover non-critical.

### 3. MINOR (new): an escalation is accepted after the review deadline, and extends the hold by W

**Stems from draft 4:** yes (D6(c), Q2).

**Rule:**
- D6(b) and (c) (C:394-405) check that the entry is open and that the owner is not TIMEOUT, but not
  the deadline.
- D1(e) (C:309-311) refuses a decision once DB time ≥ the deadline.
- H1 (C:174): an escalation's claim succeeds from FREE.

**Interleaving:**
1. The review deadline of hold X passes at T, and the entry is still open until a claim closes it.
   Normally that takes up to one poller tick. When the schedule entry is lost and the sweep acts,
   it takes 10 to 15 s (C:220).
2. At T+5 s, D1 on the entry answers 409 `review-deadline-passed`, but D6 on the same entry
   succeeds. The owner goes FREE → ESCALATED with deadline T+5 s+W.
3. The sweep's or poller's claim is then refused (C:182-185). The payment is held about 15 minutes
   past a deadline that had already expired.

There is no double decision, so this is MINOR. But Q2's "escalation stops the timer" is applied to
a timer that had already run out, and D1 and D6 disagree.

**Also.** D6(c) says nothing for owner ANALYST. I found it unreachable, because a decided entry is
not open and a transaction has one open entry at a time, but the case should be stated.

**Fix:** in D6(c), answer 409 `review-deadline-passed` when DB time ≥ the hold's deadline (as in
D1(e)), and 409 `stale-alert` when the owner is ANALYST.

### 4. MINOR (new): after an escalation, the DueHold's deadline and the lateness metric disagree

**Stems from draft 4:** yes (D6(e), H2).

**Rule:** C:211 says the DueHold carries the original deadline unchanged, and the lateness metric
measures from it. D6(e) (C:412-414) and H2's refused claim (C:185) reschedule at the escalation
deadline.

**Evidence:**
- The Redis score *is* `DueHold.deadline`, and the member embeds it
  (`decision/adapter/redis/RedisHoldSchedule.java:82-95`).
- `HoldTimeoutService` reports lateness against `hold.deadline()` (`:129-132`).

**Consequence:**
- If the DueHold keeps the original deadline as C:211 says, every escalated timeout reports about
  W of lateness against D-18's ±500 ms (`holdLateness`). That pollutes the breach evidence that Q3
  depends on.
- If it is rewritten instead, C:211 is false for escalated holds.
- Either way, the port needs a score separate from the deadline, both for the 1 s reschedule and
  for this. The contract does not say so.

**Fix:**
- Measure lateness from the effective deadline: `hold_owners.deadline` when it exists.
- State that `HoldSchedulePort.schedule` takes a firing time separate from the DueHold's deadline.

### 5. MINOR (new): FAILED is not in §3.8's outcome domain

**Stems from draft 4:** yes (D4(e′)).

**The gap.** §3.8 (C:154-155) still declares `outcome` as APPLIED or SKIPPED, with `reason`. D4(e′)
(C:371-372) writes FAILED with an SQLSTATE and the effect.

**The consequence.** Implemented to §3.8 with a CHECK, the FAILED insert itself fails with 23514.
That is class 23 again, so the worker cannot record the failure and re-meets it on every run:
review 3's stall, through the back door.

**Fix:** add FAILED, and columns for the SQLSTATE and the effect, to §3.8.

### 6. MINOR (draft 4's wording; no worse than draft 3): FAILED on a hold-owned E1 strands the payment with no resolution path

**Stems from draft 4:** the wording does (D4(e′), C:372-373: "A FAILED effect is for an operator
to resolve"). But draft 3 left the same hold stuck, while also stalling every other decision. Draft
4 is strictly better, so this is not a MAJOR caused by draft 4.

**Why nothing can resolve it.** When E1 for an analyst decision with `hold_owned` is recorded FAILED:
- the effects row is final: its primary key is `decision_id` and it is append-only (§3.8), so D4(a)
  never reselects it;
- `hold_owners` stays ANALYST, and the undo window has passed;
- the poller drops the hold (C:199);
- H3 excludes ANALYST owners (C:218);
- an override cannot help. From HOLD only ANALYST and TIMEOUT_POLICY may transition
  (`decision/domain/DecisionState.java:131-136`), so the override's transition is SKIPPED.

So no API or job path can move the payment out of HOLD. D4(f) (C:376) says the hold "stays HOLD
until E1 succeeds", which never happens.

**Reach.** I could name no class-22 or class-23 cause in E1's own writes for a MEDIUM hold. After
MAJOR 3's fix, those writes are T2's reservation and keyed, conflict-tolerant inserts. So the case
needs a defect.

**Fix:** exclude hold-owned E1 from FAILED. Retry it alone, letting the worker move past it, with
`fs_analyst_effects_failed_total` raised. Or define the operator's procedure, for example an
audited "apply the decision" path, and a property for it.

### 7. MINOR (residue of review 3's MAJOR 3): P17 and the block view after a second lift

**Stems from draft 4:** no. These are the parts of review 3's MAJOR 3 fix that draft 4 did not
carry.

- **P17 (C:461) is unchanged.** It now reads false for the two cases D4(c) deliberately leaves
  out: an override of a DECLINE_AND_VERIFY timeout, and a second lift, both of which write no row.
- **The re-decline has no fact.** Nothing states that `v_auto_block_status` (`V5:124-140`) keeps
  showing an account unblocked after an override declines it again.

**Fix:** extend P17 to the cases with no row, and add that one sentence.

### 8. MINOR (residue of review 3's MAJOR 1): the order of H2's check, and the backstop's shape

**Stems from draft 4:** yes, in wording.

- **The order of statements is not stated.** H2 (C:186-198) does not say that the sequence-1 check
  is the claim transaction's *first* statement, before H5 closes the entries. Under READ COMMITTED
  only that order makes the guarantee in C:188-189 true; review 3 named the window.
- **The backstop is incomplete.** It (C:236-239) reads `hold_owners` without a lock and writes no
  AUTO_* row, so if it ever fired, P13's "one AUTO_* row" would not hold. If finding 1's option (A)
  is taken, this text becomes the main mechanism and must say both.

### 9. MINOR (new): Q2 and Q3 are not recorded where the owner asked

**Stems from draft 4:** yes.

- **ADR 0064 is unchanged at `5873764`.** `git show --stat 5873764` touches neither ADR 0064 nor
  ADR 0056. C:202 says "This amends ADR 0064", but no amendment exists yet.
- **The breaches are not in the traceability files.** `requirements_matrix.md:234` still shows D-18
  as DONE, and FR-03-02's row (`:28`, "30-second countdown; auto-released") records no deviation
  for:
  - the outage hold (Q3);
  - an escalated MEDIUM hold of up to W (Q2).

**Fix:** amend ADR 0064, and record both deviations against D-18 and FR-03-02, when the contract is
accepted or the code lands. Say which in the contract.

## Attacks on the new mechanisms that found nothing (verified claims)

**Escalation against a timeout claim.**
- D6 locks the entry, then `hold_owners` `FOR UPDATE`. The claim upserts `hold_owners`, then
  updates entries.
- That is m4's reversal, so the deadlock is retried.
- If the claim wins, the original entry is closed, and the retried D6 gets 409 (the entry is not
  open).
- If D6 wins, the retried claim is refused until the escalation deadline and reschedules.
- Either way, exactly one outcome.

**Escalation, then the senior decides, then undoes.**
- The senior's claim goes ESCALATED → ANALYST and stores `prior_hold_owner` = ESCALATED and
  `prior_hold_deadline` = the escalation deadline (C:312-313).
- The undo restores both (C:327-328) and reschedules at that deadline (C:330). A past deadline is
  claimed on the next tick.
- If that reschedule fails, H3 claims it 10 to 15 s later, because ESCALATED is not excluded
  (C:218).
- A poller claim racing the senior's live decision finds ANALYST and drops the hold. The undo
  re-arms it. No double owner.

**Re-escalation.**
- ESCALATED is kept with its deadline (C:400-401). The new entry's `review_deadline_at` is the same
  deadline (C:408).
- Lock order and outcome are as for the first escalation.

**Deciding the original entry after its escalation.**
- The original's status is ESCALATED, which is not open, so D1(d) answers 409 before the H1 claim,
  which would otherwise succeed from ESCALATED.
- D1 and D6 on the original both take its row lock first, so they serialise.
- An override of the original's ESCALATE gets 409 (D5(b)).

**A Redis reschedule lost after an escalation.**
- The member is keyed per transaction in `HOLDS_INDEX`, with ZADD by member
  (`RedisHoldSchedule.java:26-31`). Extra or stale members fire, are refused on DB time, and are
  rescheduled, or they meet TIMEOUT.
- A TIMEOUT → TIMEOUT re-claim finds its own reservation (T2 step 2) and opens no entry.
- With Redis down, the escalating instance's and the ingesting instance's local maps can both hold
  the hold (`ResilientPorts.java:192-238`). Both claims serialise on `hold_owners`. Only the first
  changes anything.

**H3 with ESCALATED rows.**
- H3's conditions select on the effective deadline (C:214-215). V62's lister is coarser: it uses
  the review deadline (`V62:18-19`), so it may name an institution with nothing due. That costs an
  empty query, never a wrong claim.
- Starvation needs the per-institution query to filter after its `LIMIT`. H3 puts every condition
  in the selection, so it does not.

**Sequence 1 for T2's other writers (C:259-261).**
- An analyst or override needs an entry. An entry exists only with sequence 1, or, for an
  escalated entry, after its original did.
- An unblock needs its auto-block, which is written in the same record under the same `skip` guard
  (`PostgresSink.java:250-253`).

**MAJOR 3's write.**
- The write is limited to an existing auto-block, with `ON CONFLICT DO NOTHING`.
- The reconciler selects only CUSTOMER_VERIFICATION unblocks (`UnblockReconciler.java:49`;
  `V64:20`), so a SENIOR_OVERRIDE row never enters its loop.

**The cutover's observability.**
- `fs_spool_lag{consumer}` exists for both consumers (`DecisionWiring.java:138-146`).
- `lagBytes` is durable end minus committed (`DurableSpool.java:336-338`). With every writer
  stopped (finding 2), it is a sound "drained" signal.
- Dead-lettered records count as drained. That is correct for the cutover, but see finding 2's
  second gap.

**The Q3 text.**
- It is consistent with H2 for a PostgreSQL outage. The poller and the local fallback reschedule.
  No claim is possible without PostgreSQL, so no timeout contradicts an analyst.
- Its bound is wrong only in finding 1's cases.
- The lease during the outage remains m11, which is carried.
