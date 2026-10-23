# Review 5: the analyst decision commit and undo contract (draft 5)

Subject: `docs/architecture/analyst-decision-commit-contract.md` (draft 5) and
`docs/adr/0111-held-payments-wait-for-the-arbiter.md`, both at `49c4830` (branch `m8b/analyst-api`).

Reviewer: independent. I wrote none of the drafts and none of reviews 1 to 4.

Method: reading only. No build, test or container was run. Contract line numbers (`C:<n>`) and code
line numbers are at `49c4830`, read with `git show 49c4830:<path>`. Uncommitted changes in the
worktree were not read. Code paths are abbreviated: `decision/` is
`backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`, and `V<n>` is
`backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

Scope, as instructed: option A (H2, H5 and its new rule, T2), A3's new step 1, W recorded as an
assumption, and ADR 0111. Findings carried in §12 and §13 are not re-reported. None of them was made
worse by draft 5. Review 4's MINOR 4 (a Redis score separate from the deadline) still matters, but
it is unchanged.

## Verdict: SOUND_WITH_CHANGES

**MAJOR in draft 5: YES.** It is finding 1, and it comes from draft 5's own change. Option A removed
draft 4's wait on the sink. The H5 rule then closes the late *entry*, but nothing completes the late
*`decision_states` row*. The race behind it also exists in M6 today, and draft 4 had closed it.

- **The arbiters still hold.** I found no interleaving in which both an analyst and a timeout decide
  a held transaction. I also found none in which two writers reserve one sequence.
- **The H5 rule works for the entry.** It holds in all three READ COMMITTED interleavings (sink
  first, claim first, and the sink's insert landing between the claim's statements). It cannot
  deadlock with the claim, and it writes exactly one AUTO_* row on a first delivery.
- **Finding 1 (MAJOR, from draft 5).** The leader times out a hold that another instance ingested,
  and that instance's sequence-1 record has not reached PostgreSQL yet. The timeout's
  `DecisionChanged` then reaches the sink first. `decision_states`' foreign key to `transaction_ids`
  refuses it with 23503, and the sink dead-letters it. T5 never re-records it, because its
  recording row already exists.
  - PostgreSQL loses the transition for good.
  - D-14's `GET /api/v1/decisions/{id}` falls back to PostgreSQL, and then reports HOLD for a
    payment that was released or declined.
  - The payment itself is decided correctly through Kafka. No entry is left open, and no
    transaction is decided twice. The loss is the PostgreSQL record of the effect.
- **Findings 2 to 9 are MINOR.** Five of them come from draft 5's text (2, 3, 4, 5, 6). Three are
  records and wording (7, 8, 9). Finding 8 also covers a pre-existing double fault that draft 5 did
  not make worse.

## Review 4's MAJORs

| # | Review 4 MAJOR | Status | Why |
|---|---|---|---|
| 1 | A dead-lettered hold waits for ever | **RESOLVED** (conditions in findings 3, 6, 8) | The claim no longer needs anything the sink writes (C:198-204, C:282-286). See below. |
| 2 | The cutover stopped only ingest | **RESOLVED** (MINOR residue, finding 9) | A3 step 1 now stops every writer of a sequence ≥ 2 (C:94-97). |

**Why MAJOR 1 is resolved.**

The case: transaction X's ingest record is dead-lettered (`PostgresSink.java:201-212, 218-237`),
and its `DueHold` is still in Redis or in the local map. Then:
1. At the deadline, the poller claims X. No `hold_owners` row exists, so the claim compares DB time
   with `DueHold.deadline` (C:187-188, C:194-195). It inserts TIMEOUT. It closes no entries,
   because none exist.
2. T2 reserves sequence 2 from the hold's own sequence-1 state (C:283-286). `HoldTimeout.resolve`
   needs only that state's decision and sequence (`decision/domain/HoldTimeout.java`;
   `DecisionState.next`).
3. T4 records it. Kafka releases or declines the payment.
4. The `DecisionChanged` reaches the sink, which refuses it with 23503 and dead-letters it, like X's
   own record. That is harmless, because X has no rows in PostgreSQL to complete.
5. Nothing is left open or held.

It holds only on three conditions:
- `hold_owners` and `transaction_transitions` have no foreign key to `transaction_ids` (finding 3);
- every failed claim reschedules (finding 6);
- the `DueHold` survives. When it is lost, the same hold is stranded, exactly as in M6 (finding 8).

**Why MAJOR 2 is resolved.**
- Step 1 now names ingest, the poller and sweep, the reconciler, the verification endpoint, the
  analyst API, the committer and effects workers, and M9's replay (C:94-97). That covers every
  writer of a `DecisionChanged`: `DecisionTransitionService.java:127,159,167` and
  `HoldTimeoutService.java:124`.
- Review 4's interleaving, a customer "yes" or an old timeout appending after zero lag, is closed.
- One residue is MINOR: finding 9.

## New findings

### 1. MAJOR (new, from draft 5's option A): the timeout's sequence-2 row can reach PostgreSQL before sequence 1, and is dead-lettered for good

**Stems from draft 5:** yes. The dependency was:
- draft 4's H2 waited until sequence 1 was visible;
- draft 5 withdraws that wait (C:198-200) and takes sequence 1 from the `DueHold` (C:283-286).

The H5 rule (C:249-265) fixes the half of the late-ingest race that concerns the alert entry, but
not the `decision_states` half. M6 has the same race today (`HoldTimeoutService.java:117-125`
records without consulting PostgreSQL), so this is a regression against draft 4, not against M6.

**Rules:**
- H2: "No dependency on the sink … the claim needs nothing the sink writes" (C:199-200).
- T4: a reservation is recorded once its spool record is written (C:311-313).
- T5: "No reservation is stranded … always completed" (C:317-320).

**Evidence:**
- **The foreign key.** `decision_states` has `FOREIGN KEY (institution_id, transaction_id)
  REFERENCES transaction_ids` (`V3:108`). Both rows come only from the transaction's ingest record
  (`PostgresSink.java:279-286, 336`).
- **The sink still writes the row.** It writes every `DecisionChanged` into `decision_states`
  (`PostgresSink.java:431-434`), and draft 5 keeps that write (C:245, §8).
- **23503 is not transient.** It is class 23 (`Tenant.java:43-51`), so the batch falls back to
  record by record, and the record is dead-lettered (`PostgresSink.java:195-212`).
- **Each instance drains its own spool.** Every instance has one spool and an independent
  PostgreSQL drainer (`DecisionWiring.java:128-174`). The leader records its timeouts into *its
  own* spool (`DecisionWiring.java:178-179, 303-310`). Nothing orders records across instances.
- **A stopped instance keeps its tail.** `SpoolDrainer.close()` stops without draining
  (`SpoolDrainer.java:141-150`), and the tail waits until that instance runs again.
- **Nothing re-records it.** T5 lists only reservations with no recording row. T4 wrote that row
  after the spool append, so the dead-lettered record is never recorded again. No replay tool for
  the sink's dead letters exists (ADR 0056:54, carried to M9).

**Interleaving:**
1. Instance A ingests MEDIUM transaction X at t. The spool record is durable, the HOLD is answered,
   and the `DueHold` is in Redis (`DecisionService.java:240, 251-263`).
2. A's PostgreSQL drainer does not deliver X's record within 30 s. Any of these does it:
   - A crashes or its node fails, and A returns after more than 30 s with the same volume;
   - A loses its route to PostgreSQL while B keeps its own;
   - after a PostgreSQL outage, A's backlog drains after B's.
3. At t+30 s, leader B claims X. Under option A nothing waits.
   - The claim inserts `hold_owners` TIMEOUT and reserves sequence 2 from the `DueHold`.
   - T4 appends the `DecisionChanged` to B's spool, publishes to Kafka (the bank gets the release),
     and inserts the recording row.
4. B's sink writes sequence 2. `transaction_ids` has no row for X yet, so the insert fails with 23503,
   and the record is dead-lettered.
5. A's record finally drains. Sequence 1 HOLD and the entry are written. The H5 rule finds TIMEOUT
   and closes the entry, which is correct.
6. **Final state.**
   - `decision_states` holds only sequence 1, HOLD, for ever.
   - `transaction_transitions` holds sequence 2.
   - The recording row exists, so T5 never re-records.
   - The sink's dead-letter count rises, and so does the alerting noise.

**Consequences:**
- **The effect is lost in the system of record.** D-14's per-transaction history in PostgreSQL
  never gets the timeout.
- **The bank sees HOLD.** `GET /api/v1/decisions/{transaction_id}` reads Redis, then PostgreSQL on a
  miss (`IngestController.java:279-297`; `ResilientPorts.java:110-113`; `RedisDecisionStates.java:92`;
  `JdbcDecisionStates` takes the highest sequence). The bank is told HOLD for a payment it was told
  was released:
  - after the Redis state expires (8 days, `RedisDecisionStates.java:28`);
  - and at once during any Redis outage.
- **Later sequences leave a gap.** A later override reserves sequence 3 (T2 reads reservations), so
  `decision_states` ends up with a gap at 2.
- **The sweep is not the cause.** H3 excludes X because a reservation exists (C:229-230). V62's
  lister will name X's institution on every sweep (`V62:18-23`), which costs one empty query, not
  a wrong claim.

**Reach:**
- It is narrow, but it is designed-for and reachable. It needs an ingest instance whose PostgreSQL
  delivery runs more than one review window behind the leader's.
- Every such event loses one transition for each MEDIUM hold in the lagging tail.
- The local fallback is not affected: it times out only holds its own instance ingested, through
  the same ordered spool (`ResilientPorts.java:203-232`).
- The sweep is not affected either: it reads sequence 1 from PostgreSQL.

**Suggested fix.** Under the owner's rule, prefer a reduction. Either one is a single rule.
- **(a) Reduce what is promised (simplest).** State that `decision_states` may lack a timeout's row
  when the ingest record reached PostgreSQL after the timeout.
  - Make `transaction_transitions` the record of every state after sequence 1. It already is for
    T2.
  - `JdbcDecisionStates.latest` (the D-14 read) then reads the higher of the highest reservation's
    `event_body` and `decision_states`.
  - A dead-lettered `DecisionChanged` whose event id has a reservation is then expected and
    harmless. Count it apart from other dead letters.
- **(b) Complete it from the reservation.** T5's recorder also lists reservations with no
  `decision_states` row whose `transaction_ids` row exists. It inserts that row from `event_body`
  (`ON CONFLICT DO NOTHING`), straight into PostgreSQL, without the spool or Kafka, because Kafka
  already has the record.
  - The bound becomes the sequence-1 arrival + 30 s + P.
  - The sink should count a 23503 on a `DecisionChanged` that has a reservation as "completed
    later", rather than raise a dead-letter alarm.

Either way, add a property: a timeout committed before its sequence-1 row exists still appears in
the D-14 read once the sequence-1 row arrives.

### 2. MINOR (draft 5): the H5 rule's step 4 needs a guard and a conflict clause, and its "each of the two writes" is inaccurate

**Rule:** C:251-257 ("sets the new entry to `timeout_outcome` with `version + 1`, writes the AUTO_*
decision row with H5's idempotency key") and C:265 ("Each of the two writes the AUTO_* row with
the same key").

**What happens on a first delivery.** Only one of the two writes the row in any interleaving:
- sink first: the claim's UPDATE closes the entry, and the sink saw FREE;
- claim first: the claim closed nothing, and the sink writes.

The claim writes "on the newest of those entries" (C:242-243), so with no entry closed it writes
nothing. C:243 should say so.

**Redelivery.** Spool delivery is at least once (`SpoolDrainer.java:117-118`: the sink commits
before the offset). Take a redelivered MEDIUM record after X timed out:
- step 1's `ON CONFLICT (id) DO NOTHING` inserts nothing (`PostgresSink.java:95-100`);
- step 3 reads TIMEOUT;
- step 4, as worded, updates "the new entry" by id.

If that UPDATE is not guarded, it rewrites the original entry of an escalated transaction, whose
status is ESCALATED, to AUTO_RELEASED, and bumps `version` again. The AUTO_* insert then meets
`UNIQUE (institution_id, idempotency_key)` (`V4:54`):
- **without `ON CONFLICT DO NOTHING`,** the record fails with 23505 and is dead-lettered. The
  rollback happens to undo the rewrite, so only noise remains;
- **with it,** the rewrite commits.

**Fix:** step 4 applies only when step 1 inserted the row (or `WHERE status IN
('PENDING','IN_REVIEW')`), and its AUTO_* insert uses `ON CONFLICT DO NOTHING`. Reword C:265:
exactly one of them writes it, and a redelivery writes nothing.

### 3. MINOR (draft 5): option A depends on `hold_owners` and `transaction_transitions` having no foreign key to `transaction_ids`, and the contract does not say so

**Rule:** §3.5 and §3.6 (C:139-161) list columns and keys, but no foreign keys.

**Why it matters.** The project's convention is to reference `transaction_ids` (`V3:108`). With such
a key on either table, option A's claim fails with 23503:
- while the sink lags;
- for ever for a dead-lettered record. That is review 4's MAJOR 1 again, by another route.

**Fix:** state in §3.5 and §3.6 that neither table references `transaction_ids`, and why.

### 4. MINOR (draft 5): the sink's new lock waits are subject to §2's `lock_timeout`, and 55P03 dead-letters an ingest record

**Rule:**
- §2 (C:86-87): every transaction has `lock_timeout` 2 s.
- The H5 rule (C:253-255): the sink now waits on the `hold_owners` row, on its insert's conflict and
  on `FOR UPDATE`.

**Evidence:**
- The sink does not run through the runner.
- 55P03 (`lock_not_available`) is not in the sink's transient classes 08, 40, 53, 57 and 58
  (`Tenant.java:43-51`).

**Interleaving:**
1. The sink waits more than 2 s on a claim's lock. A claim slowed by its T2 lock, its audit write or
   a loaded primary after an outage is enough.
2. The batch fails with 55P03 and falls back to record by record.
3. If the wait repeats, X's whole ingest record is dead-lettered: sequence 1, the scores and the
   entry.

The drainer never waited on a lock before draft 5.

**Fix:** state that the sink runs without `lock_timeout` (it is bounded by `statement_timeout`, and
57014 is transient), or that it treats 55P03 as transient.

### 5. MINOR (draft 5): the extended `DueHold` has three unspecified sources

**Rule:** T2 (C:283-286): the `DueHold` gains the HOLD state's event id, `decided_at`, deadline and
reason codes, written by `DecisionService`. `DecisionService` can do so: the state is in scope
where the hold is built (`DecisionService.java:199-206, 253-259`).

The gaps:
- **Members written before V80.** A3 drains the spool, not Redis.
  - Members scheduled by the old release survive the cutover, in the old five-field format
    (`RedisHoldSchedule.java:84-85`).
  - The claim script removes up to 500 due members *before* Java parses them
    (`RedisHoldSchedule.java:41-51, 121-131`). A parser that requires the new fields throws, and
    the whole batch leaves the schedule.
  - Those holds wait for the sweep, 10 to 15 s late. They are not lost, because their sequence 1 is
    in PostgreSQL after the drain.
  - State the fallback: an old member takes sequence 1 from `decision_states`.
- **Reschedules from a request path.** D2(e) (C:360-361) and D6(e) (C:451) reschedule a hold after
  their commit, but do not say where the new fields come from. `decision_states` holds them there:
  an entry exists, so sequence 1 does.
- **The sweep's `DueHold`.** It is built without the new fields (`JdbcOverdueHolds.java:76-81`).
  T2 already says the sweep reads `decision_states` (C:287). That is consistent, and should be
  named in §8.

Note: only the HOLD state's decision and sequence are used to compute the timeout
(`HoldTimeout.resolve`, `DecisionState.next`). The other fields are for the reservation's body.

### 6. MINOR (draft 5): H2 reschedules only when "PostgreSQL is unavailable"; any other failed claim removes the hold from every schedule

**Rule:** C:213-214.

**Evidence:**
- `claimDue` removes the member from Redis before the claim runs (`RedisHoldSchedule.java:41-51`).
- The local fallback removes it from its map (`ResilientPorts.java:221-229`).
- `tick` loops over the claimed batch with no per-hold handler (`HoldTimeoutService.java:76-82`).
  `BackgroundTasks` catches the exception and logs it (`BackgroundTasks.java:91-100`), so the rest
  of the batch is dropped as well.

**What it costs:**
- **A claim that fails for another reason** loses the hold from the schedule. For example, 55P03
  while waiting on a sink batch in the sink-first interleaving, or a class-23 defect.
- **When sequence 1 is in PostgreSQL,** the sweep recovers it 10 to 15 s late.
- **When the ingest record is dead-lettered,** nothing ever recovers it: the payment is held for
  ever. This needs a second fault on top of the dead letter, so it is MINOR. But it is exactly the
  case option A was adopted for.

**Fix:** "a claim that fails for any reason reschedules that hold 1 s later and counts it; the path
continues with the rest of its batch."

### 7. MINOR (draft 5 and ADR 0111): the deviation records are incomplete

- **The D-18 row of ADR 0111 says "Met otherwise".** That is false for an escalated MEDIUM payment,
  which auto-releases (or declines) at W, not at 30 s ± 500 ms. FR-03-02's row records the
  escalation case, but D-18's row and its register note (`requirements.yaml`, the D-18 entry) do
  not.
- **The traceability matrix was not re-rendered.** `requirements_matrix.md:28` (FR-03-02) and `:234`
  (D-18, still DONE) do not list ADR 0111, although `requirements.yaml` does. If the matrix is
  generated and gated, as the earlier "re-render the matrix" commit suggests, this fails the
  governance chain.
- **The visibility statement** (ADR 0111, Consequences) is sound as a forward plan.
  - The rescheduled-claim count does not exist yet. H2 creates it.
  - `holdLateness` is recorded from `hold.deadline()` above 500 ms
    (`HoldTimeoutService.java:129-132`).
  - The outage signal is confounded by escalated holds, which report about W of lateness. That is
    review 4's MINOR 4, carried.
  - Say that the lateness distribution separates outage lateness from escalation only once MINOR
    4 is fixed.

**Fix:** add the escalation case to D-18's row and register note, and re-render the matrix.

### 8. MINOR (pre-existing, not worsened): review 4's requested property is missing, and a double fault still holds a dead-lettered payment for ever

**The missing property.** Review 4 asked for a property: a MEDIUM transaction whose ingest record is
dead-lettered is still timed out within a stated bound. §7 has no such property.

**The double fault.** The bound holds only while the `DueHold` survives. A dead letter combined
with a lost `DueHold` holds the payment for ever, because the sweep reads `decision_states`
(`JdbcOverdueHolds.java:28-40`). The `DueHold` is lost when:
- Redis fails over empty, the very case ADR 0064 point 4 built the sweep for;
- or the instance holding it in its local map restarts (`ResilientPorts.java:195`).

M6 has the same outcome, so H2's "as under M6" (C:203-204) is accurate. It needs two independent
faults, and draft 5 did not make it worse. I record it rather than count it as a draft-5 MAJOR,
but its outcome is of the MAJOR kind. The owner may choose to state it as a limitation.

**Fix:**
- add the property, with the condition "while its schedule entry survives";
- list the double fault in §9's limitations.

### 9. MINOR (residue of review 4's MAJOR 2, and the text of §13)

- **A3's consequence is slightly too strong.** Step 1 does not name `BackgroundTasks`' breaker
  monitor (`BackgroundTasks.java:85`; `CircuitBreakerMonitor.java:66` appends to the spool) or the
  batch-ingest workers. So "no older spool record drains after it" (C:105) is literally false.
  It is harmless: neither writes a sequence ≥ 2. Say "every spool writer" without an exhaustive
  list, or add both.
- **The replay constraint covers only the cutover.** M9's dead-letter replay "stays stopped" only
  during the cutover (C:96-97). Review 4's second gap was replay *after* V80 of a pre-V80,
  non-reserving `DecisionChanged`. That can double sequence 2 if it lands before a reserving
  writer. State the constraint for M9.
- **§13's lock-order exception text** (C:609-612) says a retried decide "reads TIMEOUT and answers
  409". It does answer 409, but as `stale-alert` from D1(d) (C:336-337): the claim has closed the
  entry, and D1(d) runs before (e) reads `hold_owners`. The text also omits the other loser. A
  retried claim finds ANALYST and drops the hold. That outcome is consistent.

## Attacks on option A that found nothing (verified claims)

**A MEDIUM `AlertRaised` always carries a review deadline.**
- MEDIUM ⇒ HOLD with `decidedAt + reviewWindow` (`DecisionEngine.java:80-87`).
- HOLD ⇔ deadline, and HOLD ⇒ MEDIUM (`DecisionOutcome.java:37-41`).
- A frozen account raises no alert (`DecisionEngine.java:55-66`).
- `AlertRaised` copies the outcome's deadline (`DecisionService.java:224-234`), and V4 requires it
  for an original MEDIUM entry (`V4:29`).
- So the H5 rule's FREE row always has its deadline, and it equals the `DueHold`'s
  (`DecisionService.java:259`).

**The sink's isolation and retry.**
- One transaction per batch, at the default READ COMMITTED (nothing sets another level). On a
  non-transient error it falls back to one commit per record (`PostgresSink.java:186-212`).
- Classes 40 and 57 are transient (`Tenant.java:43-51`), so a 40P01 or a 57014 retries the batch.
- The `skip` guard drops a duplicate decision's alert, so a duplicate never creates a FREE row
  (`PostgresSink.java:240-249, 287-290`).

**The rule, sink first (READ COMMITTED).**
1. The sink inserts the entry and the FREE row, uncommitted.
2. The claim's `INSERT … ON CONFLICT DO UPDATE` meets the in-progress insert and waits for the
   sink's commit.
3. It then updates the committed FREE row to TIMEOUT (DB time ≥ deadline).
4. Its next statement, the UPDATE of open entries, takes a new snapshot, sees the committed entry
   and closes it. It writes the AUTO_* row.
5. The sink read FREE and wrote nothing. Result: one closure and one AUTO_* row.

**The rule, claim first.**
- The claim inserts TIMEOUT and closes nothing; the sink's entry is uncommitted and invisible, so
  the UPDATE does not wait.
- The sink's `ON CONFLICT DO NOTHING` waits for the claim's transaction if it is still in progress,
  then does nothing.
- Its `FOR UPDATE` reads TIMEOUT (committed), and it closes the entry and writes the AUTO_* row.
- If the claim aborts, the sink inserts FREE and a later claim proceeds as in "sink first".

**No deadlock between the sink and a claim.**
- A claim holds one `hold_owners` row. Its later statements touch only rows the sink cannot hold:
  committed open entries (the sink's entry is invisible), the `alert_decisions` key (the sink writes
  it only after reading TIMEOUT, which needs the claim committed), the T2 lock and the audit chain.
- So the sink may wait on a claim, but never the reverse. A batch holding several FREE rows only
  delays claims; that is latency, bounded by findings 4 and 6.
- The sink takes the entry (level 1) before `hold_owners` (level 4), within §2's order.

**The sink never sees ESCALATED or ANALYST before its entry exists.**
- D1, D2 and D6 all start from the committed entry (C:327, 350, 422), which arrives only with the
  sink's commit.
- Those owners appear only on a redelivery. There, step 4 does nothing, and the `FOR UPDATE` waits
  on D1, D2 or D6 without a cycle, because the redelivery's inserts conflict with committed rows
  and take no lock.

**AUTO_* uniqueness.** `alert_decisions` has `UNIQUE (institution_id, idempotency_key)` (`V4:54`)
and is append-only (`V4:67`). With finding 2's clause, a redelivery cannot add a second row.

**A dead-lettered ingest record whose `DueHold` survives.** It is timed out at its deadline. Nothing
is left open (it has no entry) and nothing is held. Its sequence-2 record is dead-lettered as well
(finding 1's mechanism), which is harmless here. The sweep never sees it and does not need to.

**The stated lock-order exception.**
- D1 holds the entry and waits for `hold_owners`; the claim holds `hold_owners` and waits for the
  entry. That deadlock is real.
- PostgreSQL detects it, and the runner retries 40P01.
- Either loser ends with one owner: a retried D1 gets 409 (finding 9 on its wording), and a retried
  claim finds ANALYST and drops the hold.
- D6 against a claim behaves the same way.
- D2 against a claim cannot deadlock. The decided entry is not open in the claim's snapshot, so the
  claim's UPDATE never waits on it. After D2 commits, the claim re-reads FREE and closes the
  restored entry.

**A3's new step 1** closes review 4's interleaving (above).

**W.**
- D6 states it as ASSUMED: 15 minutes by default, configurable (C:432-442).
- §9 (C:549-551) and ADR 0111 point 3 agree.
- Both halves of the reasoning are marked unmeasured. Nothing claims more.

**ADR 0111.**
- The Q2 and Q3 decisions match H2 and D6.
- The FR-03-02 deviation names both cases.
- ADR 0064's status line is amended (`49c4830`). The effective date ("when P2 ships") is stated
  consistently in the ADR, ADR 0064 and both register notes.
- The exception is the D-18 row (finding 7).
