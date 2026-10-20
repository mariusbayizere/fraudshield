# Review 2: the analyst decision commit and undo contract (draft 2)

Subject: `docs/architecture/analyst-decision-commit-contract.md` at `3f9f7f5` (branch `m8b/analyst-api`).
Reviewer: independent; wrote none of the work and not review 1. Method: reading only. No build, test
or container was run. Contract line numbers (`C:<n>`) and code line numbers are at `3f9f7f5`. OpenAPI
lines are from `git show 3f9f7f5:contracts/openapi/fraudshield-api.yaml`, because the working tree
has unrelated uncommitted OpenAPI edits.

## Verdict: SOUND_WITH_CHANGES

2 MAJOR, 12 MINOR. The two MAJORs must be fixed before code is written against the contract.

- **The core holds.** Moving both arbiters into PostgreSQL closes review 1's double-decision
  interleavings. I found no interleaving in which the analyst and the timeout both decide a held
  transaction, or in which two writers reserve one sequence. H1's upserts behave as claimed under
  READ COMMITTED and RLS. I also found no deadlock cycle among D1 to D6, the committer, the claims,
  T2, the sink and the audit head. The stated lock order is not followed literally, though (finding
  7).
- **MAJOR 1:** recording a reservation again is not idempotent on the customer path, and nothing
  says who records a stranded reservation. This can double a label, or leave a reserved transition
  unrecorded while the unblock sweep stops for every institution.
- **MAJOR 2:** under the `DECLINE_AND_VERIFY` timeout policy, a timed-out MEDIUM entry is never
  closed. P13 and §6 are false for that policy.

## Review 1's findings

| # | Review 1 finding | Status | Why |
|---|---|---|---|
| 1 | MAJOR: put-back after a lost commit | RESOLVED | H4 (C:148-151): no request path touches Redis before commit, and nothing is put back. If an undo's commit acknowledgement is lost, its after-commit reschedule does not run and the sweep covers the hold. Nothing can hand an ANALYST-owned hold to a timeout, because every timeout path claims first (H2). |
| 2 | MAJOR: sweep against an uncommitted decide | RESOLVED | The sweep's TIMEOUT upsert waits on D1's `hold_owners` row lock or its speculative insertion, then re-checks against the committed owner. H3's cutoff is DB time. The poller's JVM clock remains (finding 5), but the arbiter makes it harmless to P4. |
| 3 | MAJOR: `take` not idempotent | RESOLVED | `take` is gone. The claim runs inside D1 and is rolled back with it, so a 40001/40P01 retry re-runs (a) to (h) exactly. |
| 4 | MAJOR: deadlock through the audit head | RESOLVED | One decision per committer transaction, a try-lock, the audit write last in every transaction, and 40P01 retried. No transaction waits on anything after writing its audit row, so the head is never part of a cycle. The written lock order is still violated (finding 7). |
| 5 | MAJOR: override direction not stored | RESOLVED | §3.1 `override_decision` and §3.9 `supersedes_label_id`. The `DecisionRecord` change is deferred to P2 (C:362-363), which is acceptable for a contract document. |
| 6 | MAJOR: override transition lost or doubled | PARTLY | Doubling is closed: T2 serialises reservations and the PK forbids two at one sequence. Losing is not fully closed, because a reservation whose writer dies before recording can stay unrecorded (MAJOR 1). |
| 7 | the replay race | RESOLVED | D1(b) runs under the entry lock. A new race through another entry is finding 8(c). |
| 8 | idempotency vs schema and API | PARTLY | `alert_version` and `request_fingerprint` were added. `idempotency-conflict` and 503 are still absent from `StateConflict` and the four operations (OpenAPI :2614-2623, :888-1032), deferred to P2. D5(a) says "idempotency as in D1(b)", which would answer an override replay with D1's 202 `PENDING_COMMIT` body instead of 201 `DecisionRecord`. D6's replay answer is unspecified (finding 8). |
| 9 | clocks | PARTLY | The trigger-set DB times, DB-time `undo_until`, the DB-time sweep and AUTO_RELEASED keeping its own `created_at` are fixed. But §2 (C:54) now says no comparison uses a JVM clock, and the poller and local fallback still claim due holds by JVM clock (finding 5). The suggested `lock_timeout`/`statement_timeout` for D1 was not adopted (finding 11). |
| 10 | the 10-15 s sweep; undo near the deadline | RESOLVED | H3 and D2(f) state 10 to 15 s; §9 states the undo cost. |
| 11 | escalation left open forever | PARTLY | The TIMEOUT claim closes the sink-lag race: D6(b) sees TIMEOUT before any timeout event exists. The AUTO_RELEASED row goes to the open entry. T2's need for sequence 1 also closes the "timeout drains before AlertRaised" case. But under `DECLINE_AND_VERIFY` the sink never closes any MEDIUM entry, escalated or not (MAJOR 2), and the suggested closing reconciler was not adopted. |
| 12 | incomplete case list | RESOLVED | `take` is gone, and the local fallback claims in PostgreSQL like every other timeout path. |
| 13 | HIGH in the breaker count | RESOLVED | D4(d) excludes HIGH. Escalated HIGH entries keep tier HIGH, so they are excluded too. `SET NX` makes the count once per transaction. |
| 14 | committer and worker mechanics | PARTLY | The committer is fully specified. The effects worker's try-lock transaction and its outside side effects contradict §2's runner rule, and its lock is not in the lock order (finding 7). |
| 15 | same ids need changes | PARTLY | T3 and §8 cover event ids, and analyst and override label ids. The customer's label id is still random, the reservation row cannot rebuild an identical event, and `UnblockReconciler` is not listed (MAJOR 1, finding 9). |
| 16 | AUTO_RELEASED never commits | PARTLY | §3.7 now reads it as COMMITTED. That puts AUTO_RELEASED rows into D4, whose "Otherwise" branch would emit a label no rule intends. Excluding them naively would block D4(b) for overrides of a timeout (finding 3). Whether timeouts may be overridden is implied by D5, not stated. |
| 17 | the properties table | PARTLY | P1 to P15 cover most of review 1's list. But P13 is false (MAJOR 2), and P7's second case cannot fail with T1/T2 removed. P1's double guard and P3's fault points were not reworded as review 1 asked. Stranded reservations and replay-after-undo have no property (finding 12). |

## New findings

### 1. MAJOR: re-recording a reservation is not idempotent on the customer path, and stranded reservations have no specified owner

**Rule:** T3 (C:170-176), T4 (C:178-188), H3 (C:141-146), D7 (C:295-296), §8 (C:329-340).

T4 has a writer record its reservation *after* the reservation commits. A writer can therefore
commit a reservation and then fail to record it, through a crash, a full spool, or a spool "outcome
unknown" (ADR 0064 point 6). The contract states the happy path ("when the reservation already
exists with this writer's event id, the writer re-records it"). It does not specify the following
four things.

**(a) The customer's label id is random, so re-recording doubles the label.**
- T3 gives the customer's *event* a deterministic id. `customerAnswered` still draws the label id
  with `UUID.randomUUID()` (`DecisionTransitionService.java:149-157`), and records it in the same
  `record` call as the transition (`:167`).
- Interleaving:
  1. The customer answers "Yes" and `VerificationService.answer` commits the response and
     `unblock_events` (`VerificationService.java:217-240`).
  2. `customerAnswered` reserves seq 2 (T2) and records it. The spool takes the record but raises
     "outcome unknown", so `answer` catches the `RuntimeException` and returns pending (`:254-262`).
  3. The record becomes durable, but the leader's `PostgresSink` drain is more than 10 s behind.
  4. `UnblockReconciler` (GRACE 10 s, `UnblockReconciler.java:36`) finds no CUSTOMER_VERIFICATION
     row in `decision_states` (`:50-51`) and calls `customerAnswered` again (`:95-96`).
  5. T2 finds the reservation with this writer's event id and re-records it, with a *new* random
     label id.
  6. `label_events` keeps both rows, because its PK is the label id (`V5:143-153`;
     `PostgresSink.java:137-142`). Retraining gets two CUSTOMER LEGITIMATE labels for one answer.
- This doubles an effect.

**(b) The customer path has no verification id at the sweep.** `UnblockReconciler` selects only
`transaction_id` and `transaction_timestamp` (`UnblockReconciler.java:44-53`), and calls
`customerAnswered(institution, transaction, true, at)` (`:95-96`). Without the verification id it
cannot form T3's event id, so it cannot recognise its own reservation. D7 says T1 to T4 is "the only
change to it", and §8 does not list `UnblockReconciler` or `VerificationService`.

**(c) "Latest" is the wrong test for "my reservation".** T4's branches are keyed on the latest
reservation. A writer whose reservation is no longer the latest falls into "re-decide".
- Interleaving (HIGH, auto-blocked, seq 1 DECLINE):
  1. An analyst marks the alert legitimate (label only; committed; effects done).
  2. The customer answers "Yes". `customerAnswered` reserves seq 2 APPROVE with id
     `verification|v`, and then the spool append fails (spool full; 503 path).
  3. A risk officer overrides the analyst decision to CONFIRM_FRAUD. D4(b) is satisfied, because
     the original has an effects row. E1's T2 reads seq 2 APPROVE, and APPROVE→DECLINE by
     SENIOR_OVERRIDE is allowed (`DecisionState.java:140-141`), so it reserves and records seq 3.
  4. `UnblockReconciler` calls `customerAnswered`. Latest is seq 3 (DECLINE), not "mine", so T4
     re-decides: DECLINE→APPROVE by CUSTOMER_VERIFICATION is allowed. T2 inserts seq 4 with event id
     `verification|v`, which violates `event_id`'s unique key (§3.6). The runner does not retry
     23505.
  5. The reconciler catches the `RuntimeException` and **returns** (`UnblockReconciler.java:104-106`).
     Its query orders by `unblocked_at` (`:52`), so on every later tick it stops at this row. No
     later customer unblock is applied, in any institution.
- Seq 2, the customer's own transition with its label, is never recorded: `decision_states` and
  Kafka see 1 and 3. This loses an effect and stops a sweep. If the implementer instead lets
  re-decide draw a fresh id, the customer is answered twice (seq 2 reserved, seq 4 recorded).

**(d) H3's "the transaction has no transition" (C:143) is ambiguous.**
- If it means no `transaction_transitions` row, a timeout whose reservation committed but whose
  record was lost is excluded from the sweep for ever. The Redis entry is already gone, removed by
  `claimDue` (`RedisHoldSchedule.java:41-51`). The transaction stays HOLD in `decision_states` and
  on the webhook for ever.
- Only "no `decision_states` row with sequence > 1", as `JdbcOverdueHolds.java:37-38` reads today,
  makes the sweep the recorder of last resort for timeouts.

**Fix:**
- In T4, look up this writer's event id **anywhere** in `transaction_transitions` first, by its
  unique key. If found, re-record exactly that row and stop. Re-deciding applies only when this
  writer has no reservation.
- Derive the customer's label id: `verification_label|` + the verification id.
- Pass the verification id through `customerAnswered`. Have `UnblockReconciler` select
  `u.verification_id`, and list both changes in §8.
- Change `UnblockReconciler` to skip and continue on a failure, not `return`.
- State H3's test as "no `decision_states` row with sequence > 1". Name the recorder of last resort
  for each writer:
  - E1 and overrides: the effects worker, because E3 is written only after recording;
  - timeouts: the sweep;
  - customers: `UnblockReconciler`.
- Add a property with a fault point between the reservation commit and the spool append, for each
  of the four writers.

### 2. MAJOR: under `DECLINE_AND_VERIFY`, a timed-out MEDIUM entry is never closed, so P13 and §6 are false

**Rule:** D6(d) (C:289-293), P13 (C:325), §6 (C:300-301), P15.

- **What the sink handles.** `PostgresSink.decisionChanged` inserts the AUTO_RELEASED decision and
  closes the entry only when `decidedBy == TIMEOUT_POLICY && decision == TIMEOUT_RELEASE`
  (`PostgresSink.java:435-449`).
- **The other policy.** Under `DECLINE_AND_VERIFY` the timeout's state is DECLINE by
  TIMEOUT_POLICY (`HoldTimeout.java:32-37`). The policy is a per-channel setting
  (`JdbcOverdueHolds.java:31-34`).
- **Result.**
  - Every MEDIUM entry timed out under it stays PENDING, and so does an escalated copy.
  - D1 answers 409 `review-deadline-passed` for ever (D1(f)), and D6 answers 409 `stale-alert` for
    ever (D6(b)).
  - §6 lists the entry in the standard or escalated queue indefinitely.
- **The stated property is false.** P13's "never open forever" and D6(d)'s "the sink's
  AUTO_RELEASED update reaches the new entry" hold only for `RELEASE_WITH_TIMEOUT_LABEL`. This is
  M6's behaviour, but draft 2 states the property on top of it.

**Fix:**
- Have the sink close the open MEDIUM entry for *any* TIMEOUT_POLICY state, and record its decision
  row. That needs a status and decision value for a timeout decline (V4:10-11 and :47-48 have
  none), or an explicit owner decision to reuse AUTO_RELEASED.
- Add review 1's suggested reconciler for open MEDIUM entries whose transaction has a TIMEOUT_POLICY
  state.
- Extend P13 to both policies.

### 3. MINOR: D4 processes AUTO_RELEASED rows, and the obvious exclusion would block D4(b)

**Rule:** §3.7 (C:107-110), D4(a), D4(b), D4(c) (C:240-254), D5(b).

- **D4 picks them up.** §3.7 makes AUTO_RELEASED rows committed on insert, and D4(a) takes "each
  committed decision without an effects row".
- **No D4(c) branch fits.** AUTO_RELEASED is neither an analyst decision nor an override, so it
  falls to "Otherwise … record the label only".
- **No label source fits either.** `label_events.source` allows no timeout source (`V5:148`).
  Such a label would be dead-lettered by the sink (`PostgresSink.java:201-210`) and published to
  Kafka with an undefined source.
- **The trap.** If an implementer excludes AUTO_RELEASED from D4, then D4(b) ("an override's effects
  wait until the decision it overrides has an effects row") blocks for ever an override of a
  timeout. D5(b) admits that override, and `DecisionState.java:140-141` allows TIMEOUT_RELEASE →
  DECLINE by SENIOR_OVERRIDE.
- **Also not restricted.** D5(b) does not restrict the overridden decision to analyst decisions, so
  an ESCALATE row can be "overridden". The API says "Senior override of an analyst decision"
  (OpenAPI :997).

**Fix:**
- Give AUTO_RELEASED and ESCALATE an effects row with outcome SKIPPED (`no_effects`) and no E1 or E2.
- State whether a timeout can be overridden, and restrict D5(b) to analyst decisions, AUTO_RELEASED
  if allowed, and overrides.

### 4. MINOR: T2's "sequence 1 is visible to every writer that has a reason to act" is false for the timeout

**Rule:** T2 (C:165-168), H2.

- **Where it holds.** For the analyst, the override and the customer, the claim holds:
  - The alert entry and auto-block rows are written by the same sink transaction as sequence 1
    (verified below).
  - A verification row cannot exist before its auto-block row (FK `V5:47`; ADR 0057 point 1).
- **Where it does not.** The poller and local fallback act on a Redis or in-memory schedule
  written at ingest, *before* the spool drains to PostgreSQL (`DecisionService.java:240-263`).
  PostgreSQL is deliberately not on the ingest path (`ResilienceApiTest.java:266-312`: 20 decisions
  answered 200 with PostgreSQL paused). The review window is 30 s (`DecisionSettings.java:10`). So
  any drain backlog over 30 s, after a PostgreSQL outage or with a slow sink, gives a TIMEOUT claim
  that commits with no sequence 1 to compute from.
- **What "waits" would mean.** T2 says the writer "waits and retries". Inside `tick()` that stalls
  every other hold (`HoldTimeoutService.java:69-84`).
- **Wording.** The text also says the sink writes "verification rows". It does not:
  `VerificationService.issue` writes them later (`VerificationService.java:151-173`).

**Fix:**
- For the timeout, say that a missing sequence 1 ends the attempt: drop the hold (TIMEOUT stays
  claimed), and the sweep records it once sequence 1 drains. H3 then selects it, because the owner
  is not ANALYST and there is no sequence > 1.
- Correct the wording about verification rows.

### 5. MINOR: the poller still compares deadlines by JVM clock, so a leader with a fast clock times out holds early, and §2's sentence is false

**Rule:** §2 (C:54), H2, D1(f).

- **The clocks.**
  - `claimDue(clock.instant(), …)` uses the leader's JVM clock (`HoldTimeoutService.java:76`).
  - The local fallback compares `deadline` with the same JVM clock (`ResilientPorts.java:225`).
- **The claim checks no deadline.** H2's TIMEOUT claim succeeds from FREE, whatever the deadline.
- **Effect.** With the leader's clock *s* ahead, a hold is claimed TIMEOUT up to *s* before its DB
  deadline. An analyst who decides inside D1(f)'s DB-time window then gets 409
  `review-deadline-passed`.
- **Not a double decision.** The arbiter prevents one. But §2's "none uses a JVM clock" is false,
  which review 1 #9 already noted.

**Fix:**
- Make the claim conditional on DB time, for example
  `… WHERE clock_timestamp() >= <deadline from decision_states seq 1>`. If the deadline is still
  ahead, reschedule.
- Or reword §2 to name the poller's clock and accept early timeouts bounded by skew.

### 6. MINOR: the PostgreSQL-outage behaviour of H2 amends accepted ADRs and needs operational rules

**Rule:** H2 (C:137-139), §9 cost 4 and Q3 (C:350-360).

- **What changes.** The contract is honest that no hold times out during a PostgreSQL outage.
  But ADR 0064 says the decision path must not put PostgreSQL on the hot path (D-13), and M6 today
  times out holds without it (`RedisOutageTest.java:190-197` exercises the local path).
  Ingest keeps returning HOLD while PostgreSQL is down (`ResilienceApiTest.java:266-312`). With a
  30 s window, every MEDIUM payment in an outage is held until PostgreSQL returns, outside D-18's
  ±500 ms.
- **ADR amendment.** This is a change to ADR 0064's guarantee, not only a question. It needs an ADR
  amendment once the owner answers Q3.
- **Operational gaps.** Three are unspecified:
  - **Churn.** With PostgreSQL down, every 100 ms tick `claimDue`s up to 500 holds per loop, waits
    on a connection timeout for each claim, and reschedules each one. A pool timeout of seconds
    stalls the tick past the 2 s lease (`RedisHoldSchedule.java:24`).
  - **The deadline.** "1 s later" rewrites the member's deadline, which is part of the member
    (`RedisHoldSchedule.java:84-85`). After the outage, `holdLateness` (`HoldTimeoutService.java:129-132`)
    reports lateness against the rescheduled time, not the real deadline.
  - **The burst.** Each timeout is now a claim, a reservation and a spool record, run in sequence on
    the leader. The drain after an outage has no stated rate.

**Fix:**
- Record the change as an ADR 0064 amendment once Q3 is answered.
- Skip claiming while the PostgreSQL breaker is open.
- Reschedule with the original deadline kept in the member and only the score moved.
- State the post-outage drain rate or batch the claims.

### 7. MINOR: the stated lock order is not followed literally, and the effects worker is outside it

**Rule:** §2 lock order (C:65-73) and the runner (C:59); D1(f,g); §3.4; D4(a).

- **(a) D1.** D1 claims `hold_owners` (level 4) at (f), *then* inserts the decision at (g), whose
  §3.4 trigger takes the entry advisory lock (level 2). I found no cycle, because every holder of
  level 2 first holds level 1 for the same entry. But "never takes them out of order" is false.
- **(b) The undo insert.** It fires two BEFORE INSERT triggers in name order:
  `alert_decision_undos_exclusive` (V4:110), which takes the decision lock (level 3), and the new
  §3.4 trigger (level 2). Unless the new trigger's name sorts first, levels 3 and 2 are taken out of
  order.
- **(c) The effects worker.** D4(a)'s `pg_try_advisory_xact_lock` on the effects key is not in the
  order. Its transaction stays open across E1, which is a *second* runner transaction on another
  connection, and across spool and Redis calls. That contradicts §2's "none has a side effect
  outside PostgreSQL before it commits". It also holds two pool connections per worker.

**Fix:**
- Move D1(f) after (g), or put `hold_owners` at level 2 and the advisory locks after it.
- Name the §3.4 trigger so it fires first.
- Specify D4's lock as a session lock taken outside any transaction, or E1 to E3 as separate runner
  transactions guarded only by idempotency. Say so in §2.

### 8. MINOR: idempotency gaps

**Rule:** D1(b), D5(a), D6(a), OpenAPI `IdempotencyKey` (:2487-2492).

- **(a) A replay after an undo.** It returns 202 `state: PENDING_COMMIT` with an `undo_until` in the
  past (C:196-198). The schema makes `state` a constant (`AlertDecisionAccepted`, :3397-3403). A
  client with a retry queue cannot tell that the decision was undone and the entry reopened.
  "Original outcome" (D-29) is met, but the body states a current state that is false.
  - Fix: say so in the operation's description, or add a replay indicator header. Tell clients to
    re-read the alert after a replay.
- **(b) The replay bodies of D5 and D6.** D5(a) refers to D1(b)'s 202 body, but override answers
  201 `DecisionRecord`. D6's replay answer is not stated: the original `AlertSummary` as stored, or
  the entry as it is now?
- **(c) Two entries, one key.** `(institution_id, idempotency_key)` is unique per institution
  (V4:54), but D1(b) serialises only on *this* entry's lock. Two requests with one key on two
  entries both find nothing and both insert. The second gets 23505, which the runner does not retry,
  so the answer is 500 instead of 409 `idempotency-conflict`.
  - Fix: map 23505 on that key to a re-run of (b).
- **(d) Deferred declarations.** `idempotency-conflict` and 503 are still undeclared on the four
  operations (:2614-2623). The P2 milestone must carry them (review 1 #8).

### 9. MINOR: a reservation row cannot rebuild an identical event, and the timeout policy has three sources

**Rule:** §3.6 (C:103-107), T4 "re-records it", D2(f).

- **(a) Missing columns.** `transaction_transitions` has no `reason_codes`, `supersedes`, `channel`
  or actor. `DecisionChanged` carries all four (`DecisionEvent.java:205`), and `decision_states`
  stores reason codes and supersedes (`V3:104,106`). If a re-record recomputes them, the same event
  id can carry two bodies: review 1 #15's problem again. Receivers keep whichever arrives first
  (`PostgresSink.java:88-93`; Kafka ids derive from the event id, `KafkaMessages.java:384`).
- **(b) Three policy sources.** The policy can come from:
  - the Redis member, written from the in-memory threshold at ingest (`DecisionService.java:253-259`);
  - the sweep's `risk_thresholds` lookup by version (`JdbcOverdueHolds.java:31-34`);
  - D2(f)'s `decision_states.timeout_policy`.

  Only the first timeout reservation counts, so its policy depends on which path got there first.

**Fix:**
- Store everything needed to rebuild the event in the reservation, and re-record from the row only.
- Make every timeout path read `decision_states.timeout_policy`, and list `JdbcOverdueHolds` in §8.

### 10. MINOR: an override that lifts an auto-block writes no `unblock_events` row

**Rule:** D4(c) override branch, D5(d).

- **What happens.** An override of a HIGH CONFIRM_FRAUD to MARK_LEGITIMATE reserves DECLINE →
  APPROVE by SENIOR_OVERRIDE, which is allowed (`DecisionState.java:137-139`).
- **What is missing.** V5 provides `unblock_events.cause = 'SENIOR_OVERRIDE'` with an actor
  (`V5:90-102`), and D-44 names "unblock" as a deferred side effect. D4 writes no such row, so
  `v_auto_block_status` still shows the account blocked, and the customer's link stays usable.
- **The asymmetry.** For ANOMALY, the analyst's CONFIRM_FRAUD is label-only, but a risk officer's
  CONFIRM_FRAUD override declines an already approved transaction (APPROVE → DECLINE). The contract
  should say whether that is intended.
- **Repeated unblocks.** Separately, a customer "Yes" after an override already approved records
  `unblock_events`, then is refused by T4. `UnblockReconciler` then re-selects that unblock every
  5 s for ever (`UnblockReconciler.java:50-51,102-103`). After 100 such rows its batch starves
  (`:38`). D7 needs a durable SKIPPED outcome like E1's.

### 11. MINOR: grace erosion and retries past the grace

**Rule:** D1(g,h), D2(c), §9 cost 2, P2.

- **Undo.** An undo that inserts its row in time and then aborts with 40001 at its audit write (V8:92-94)
  is retried after `undo_until` and answered 409 `undo-window-expired`. The committer then commits.
  §9 names only the device-versus-server cause.
- **Decide.** In D1, `undo_until` is fixed at insert, and lock waits before commit come out of the
  analyst's 5 s. Review 1 #9's `lock_timeout`/`statement_timeout` is not adopted.
- **Lost acknowledgement.** If an undo's commit acknowledgement is lost, its after-commit reschedule
  does not run. D2(b)'s 204 replay does not reschedule either. The sweep covers it at 10 to 15 s;
  say so, or reschedule on the replay too.

**Fix:** state the costs, and bound D1 and D2 with timeouts.

### 12. MINOR: properties, migration and text

- **P7.** Its second case, "timeout vs a late analyst E1", cannot fail with T1/T2 removed: H1
  already makes the two owners exclusive. Replace it with poller vs sweep (two TIMEOUT claimants)
  and override vs customer.
- **P13** is false under `DECLINE_AND_VERIFY` (MAJOR 2).
- **P12's "within one poller tick"** ignores the claim and T2 round trips. Give a bound in
  milliseconds, or "the next tick after the reschedule".
- **Missing properties:** stranded reservations (MAJOR 1); replay after undo (8a); a timeout with
  sequence 1 not yet drained (finding 4); no timeout without a claim while PostgreSQL is down (H2).
- **Not reworded:** P1's double guard and P3's fault points (review 1 #17).
- **Backfill.** V80 must backfill `transaction_transitions` from existing `decision_states` rows with
  sequence > 1. Otherwise T2 computes a sequence that already exists, and the duplicate is dropped
  silently (`PostgresSink.java:88-93`).
- **§8 is incomplete.** It also needs:
  - `RedisDecisionStates.SAVE`, a CAS on `current + 1` (`RedisDecisionStates.java:32-39`), which
    T4's "set if higher" replaces;
  - `UnblockReconciler` and `VerificationService` (MAJOR 1);
  - `JdbcOverdueHolds`' policy source (finding 9);
  - the sink's closing of entries for a timeout decline (MAJOR 2).
- **Text.**
  - The status block says "§9 maps each finding" (C:5); the map is §10 (C:365).
  - §3.10 says listers are "pinned in V71's list". V71 has no list: it says `DatabaseSecurityTest`
    pins each function (V71:6-7).
  - T2 must run at READ COMMITTED, with the advisory lock taken in its own statement before the
    read. Under REPEATABLE READ the snapshot predates the lock, and a 23505 follows. Say so.

## Claims verified

- **H1's upserts are sound under READ COMMITTED.**
  - A claimant that meets another transaction's uncommitted insert waits on it. If that
    transaction commits, the claimant takes the DO UPDATE path. If it aborts, the claimant inserts.
  - A claimant that meets an uncommitted UPDATE waits on the row lock, then evaluates the DO UPDATE
    WHERE on the newest version.
  - When the WHERE is false, no UPDATE and no BEFORE UPDATE trigger fire and RETURNING is empty. So
    "TIMEOUT is final" is never tripped by the analyst's claim, and TIMEOUT→TIMEOUT must be
    allowed by the trigger, as worded.
- **RLS is compatible with ON CONFLICT.** The tenant policy is FOR ALL with USING and WITH CHECK
  (`V1:43-50`). The key includes `institution_id`, so a conflicting row is always the current
  tenant's, and ON CONFLICT's USING check on the existing row cannot fail. `FOR UPDATE` in D6(b)
  needs the UPDATE grant, which §3.5 gives.
- **The D6(b) and timeout race serialises** on the `hold_owners` row. A timeout claimed earlier
  makes D6 answer 409 before any timeout event exists. An escalation committed earlier precedes the
  claim, so it precedes the record and the sink's UPDATE, whose snapshot then includes the new entry.
- **T2's sequence 1 is in the same sink transaction as the rows a writer needs.**
  - `DecisionService.decide` records TransactionDecided, AlertRaised and AutoBlocked in one
    `recorder.record` call (`DecisionService.java:209-240`), which is one spool record (ADR 0064
    point 1).
  - `PostgresSink.accept` writes a batch in one transaction, and falls back to one transaction per
    record (`PostgresSink.java:186-212`).
  - `write` skips AlertRaised and AutoBlocked when its TransactionDecided is a dropped duplicate
    (`:240-263`, `:287-290`). An alert or auto-block row therefore never exists without the
    sequence 1 of its own record. A sequence 1 DECLINE can never land after its auto-block row.
  - Customer verification requires the auto-block row: `customer_verifications` has an FK to it
    (`V5:47`), and the `VerificationService` and `UnblockReconciler` queries join it
    (`VerificationService.java:47-52`, `UnblockReconciler.java:44-53`).
- **MEDIUM ⇔ HOLD, HIGH ⇔ DECLINE plus auto-block, ANOMALY ⇔ APPROVE** (`DecisionEngine.java:55-101`).
  A frozen account declines with no alert. So D1(f)'s "claim for MEDIUM" and E1's branches cover
  every alert.
- **The commit/undo exclusion.** `forbid_commit_and_undo` takes `hashtextextended('alert_decision:'||id,0)`
  (`V4:96`). The view derives the state (`V4:113-121`). `committed_at` and `undone_at` default to
  `now()` (`V4:73,83`), which §3.2's triggers correctly replace.
- **The audit chain.** It locks the partition head `FOR UPDATE` and raises 40001 when a transaction
  that started earlier writes behind a later one (`V8:84-95`). The partition is fixed or thread
  based (`JdbcAuditLog.java:80-84`). The runner retries only 40001 today
  (`TenantTransactions.java:26,68-73`), which is why §2 extends it to 40P01.
- **Grants.** `alert_decision_*` and `decision_states` are SELECT and INSERT; `alert_queue_entries`
  is UPDATE (`V11:20-33`). New tables need explicit grants, as §3.5 and §3.6 say.
- **The sweep today.** `institutions_with_overdue_holds` (`V62:13-26`) and `JdbcOverdueHolds`'
  read-only, JVM-cutoff query (`JdbcOverdueHolds.java:28-40, 54-58`; `HoldTimeoutService.java:102`)
  are as §1 describes. §8's change to them is needed.
- **Today's transition writers.** Only three exist: `analystDecidesHold`, `customerAnswered` and
  `HoldTimeoutService.timeOut` (`DecisionTransitionService.java:117,167`;
  `HoldTimeoutService.java:124`). Its callers are `VerificationService.java:250` and
  `UnblockReconciler.java:95`, so §8 plus MAJOR 1 covers them all.
- **The local fallback.** `acquireLeadership` returns true on every instance with local holds
  while Redis is down (`ResilientPorts.java:235-239`). So the sweep and the local pollers can run on
  several instances at once. H2's PostgreSQL claim makes that safe, which is a real improvement on
  M6.
- **Deterministic event ids give receiver-side dedupe.**
  - `decision_states.event_id` is unique (`V3:96`).
  - Kafka ids derive from the state's event id (`KafkaMessages.java:106,234,303,384`).
  - Audit envelopes use the same base, and the audit writer records receipts (ADR 0068 amendment).
- **E2's marker makes P9 hold.** `countConfirmedFraud` alone has no marker
  (`RedisCircuitBreakers.java:82-89`). With D4(d)'s `SET NX`, a response timeout after the script
  ran cannot count twice.
- **D2(e) holds.** The only other writer of entry status is the sink's AUTO_RELEASED UPDATE, which
  requires an open entry (`PostgresSink.java:131-135`).
- **No deadlock found among** D1, D2, D3, D5, D6, the timeout claims, T2, the sink's entry-row
  locks and the audit head. Every transaction writes its audit row last and waits on nothing after
  it. Every holder of an entry advisory lock holds that entry's row lock first. The committer holds
  at most one decision lock.
