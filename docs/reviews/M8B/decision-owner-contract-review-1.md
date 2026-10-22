# Review 1: the decision-owner contract (draft 1), end to end

Subject: `docs/architecture/decision-owner-contract.md` (draft 1), with ADR 0114, ADR 0111 and
ADR 0113, at local commit `7ec1c8e` (worktree `fs-wt/m8b`).

Reviewer: independent and adversarial. I wrote none of this contract, none of the old contract's
drafts and none of the earlier reviews.

Method: reading only. No build, test, container or network call was made. Contract line numbers
are `C:<n>` at `7ec1c8e`; the superseded contract is `OLD:<n>`. Code paths are abbreviated:
`decision/` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`,
`notify/` is `backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`, `ingest/`
is `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/`, `audit/` is
`backend/audit/src/main/java/io/github/mariusbayizere/fraudshield/audit/`, and `V<n>` is
`backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

**Scope, as the owner's lesson asks.** I reviewed the whole contract, §1 to §14, and the M6
mechanisms underneath it, not a diff. For each inherited mechanism I asked whether its assumptions
still hold once the owner exists.

## Verdict: UNSOUND

**MAJOR present: YES** (four: findings 1, 2, 3 and 4).

**MAJOR from the single-owner design: YES** (three: findings 1, 2 and 3).

| # | Severity | Origin |
|---|---|---|
| 1 | MAJOR | SINGLE-OWNER DESIGN (§6.1 against §4.2's creation from a `DueHold`) |
| 2 | MAJOR | SINGLE-OWNER DESIGN (§7.2's RESOLVE as a database function, against §4.3 and §14) |
| 3 | MAJOR | SINGLE-OWNER DESIGN (§6.5's reconciler key against §4.4 and §6.4's backfill) |
| 4 | MAJOR | INHERITED FROM M6 (ADR 0067 points 3 and 4; review 8's MAJOR 1, the order it did not cover) |
| 5 to 18 | MINOR | 5 to 13 and 15 to 18: this contract. 14: M6 and this contract |

**What the verdict does and does not say.**
- **The core holds.** One owner row, one lock and one rule table do serialise every request I
  traced. CLAIM against TIMEOUT, EFFECT against RELEASE, the customer's answer against a human's
  decision, and detection against every request all resolve under the row lock as §5 says.
- **Review 8's three MAJORs are closed in the interleavings review 8 gave** (re-run below).
- **The three design MAJORs are specification defects with local fixes.** None needs the owner
  concept changed:
  - finding 1 is a sink rule that, as written, drops the owner's own first decision when it
    arrives after the timeout;
  - finding 2 is an operator exit specified as a SQL function while the owner is Java-only;
  - finding 3 is a reconciler key that the backfill does not satisfy.
- **They are still MAJORs by the stated criteria,** each with its own: a lost state (1), a stated
  property made false (2), and a doubled effect that can overturn a later label (3). Under the
  contract's stopping rule (C:22-23) the author stops and reports.

---

## Findings

### 1. MAJOR (origin: SINGLE-OWNER DESIGN): the sink drops the owner's own first decision when it arrives after a timeout created the owner from its `DueHold`

**Rules:** §4.2's creation from the request's basis (C:169-175), §6.1's second bullet (C:376-380),
§6.2 (C:396-410), P7 (C:829).

**What §6.1 says.** "If an owner row exists, the sink locks it (level 5) and compares the record
with `first_event_id`. … The same value is a duplicate the owner already stands on. The sink counts
it and drops it, as today." The rule applies whenever an owner exists. It does not depend on
`transaction_ids` already existing, which is M6's duplicate condition
(`decision/adapter/jdbc/PostgresSink.java:288-291`).

**Interleaving: the common case after any PostgreSQL outage longer than the hold window.**
1. During the outage, ingest keeps deciding into the spool (C.4). HOLD X is decided, and its
   `DueHold` (event id X, §14) is scheduled in Redis.
2. PostgreSQL returns. The leader's poller fires every overdue `DueHold` on its next 100 ms tick.
   The sinks are still draining a backlog in batches of 200 (`ingest/config/DecisionWiring.java:166-175`).
3. X's TIMEOUT finds no visible sequence-1 row. It creates the owner from the `DueHold` (C:171-174),
   with `first_event_id` = X, and applies TIMEOUT_RELEASE at sequence 2.
4. X's own record drains. The sink finds the owner, compares, and finds the same value, and in fact
   the same event id. By C:378-380 it "drops it".
   - In M6's code, dropping means that `transactionDecided` returns false. The `skip` flag then also
     drops the record's `AlertRaised`, `AutoBlocked` and notification facts
     (`PostgresSink.java:240-265`).
   - So `transaction_ids`, `transactions`, `fraud_scores`, sequence 1 in `decision_states`,
     `account_profiles` and the MEDIUM alert entry are never written.

**Why it is a MAJOR.**
- **A state is lost.** Sequence 1 never reaches PostgreSQL. The view holds sequence 2 without it,
  and P1's account history loses the transaction.
- **§6.2 contradicts it.** §6.2 exists to close exactly this record's late MEDIUM entry. Under
  §6.1's literal rule that entry is never inserted.
- **JdbcIdempotency then sees nothing.** It reads `transaction_ids` (`ingest/idempotency/JdbcIdempotency.java`,
  LOOKUP), so a later verified retry is `Claimed` and decides afresh.
- **The intent can be guessed from §6.2, but the text says the opposite,** on the path that every
  long outage exercises.

**A second gap in the same rule.** A record with a *different* value can meet an owner created from
a `DueHold` while no `transaction_ids` row exists. This is the reverse order of review 8's MAJOR 1
(finding 4). §6.1 and §7.1 say that detection writes `decision_conflicts` and `contested_since`. They
do not say whether the record's own facts are written:
- its sequence-1 row: if written, the view shows sequence 1 DECLINE followed by sequence 2
  "TIMEOUT_RELEASE supersedes HOLD";
- its auto-block and SMS intent: §7.3 accepts only "the auto-block SMS … for the **kept** decision";
- its HIGH entry.

**Fix.**
- Compare event ids before values.
  - **The same event id as `first_event_id`:** this is the owner's own first decision arriving
    late. Write it in full, then apply §6.2.
  - **The same value, another event id:** drop it only when `transaction_ids` already exists, as
    M6 does. Otherwise state which record becomes sequence 1.
- **A different value:** state which of its facts are written.
- Extend P7 with "the owner's own record arrives after its `DueHold`'s TIMEOUT" and assert sequence
  1 and the entry.

### 2. MAJOR (origin: SINGLE-OWNER DESIGN): RESOLVE, the only exit from CONTESTED, is specified as a SQL function, but the owner and its rule table are Java only

**Rules:** §1 ("the rule table is the only place where the next state is decided", C:74-75), §4.3
("The procedure is Java … It is not a database function", C:204-205), §7.2
(`resolve_contested_decision(…)` "makes the RESOLVE request", C:506-510), §14 (`DecisionOwner` "is
the only caller of `DecisionState.next`", C:847-848).

**What §7.2 needs.** The function must evaluate RESOLVE synchronously, inside the database:
- it "makes the RESOLVE request";
- "Its audit entry is written in the same transaction";
- "The function's result and its audit entry name each such decision", meaning the pending
  analyst claims that RESOLVE clears (C:518-519).

RESOLVE APPLIED also has to write a `transaction_requests` row with a full `event_body`. That body is
a `DecisionState` in `FactCodec`'s shape, which the recorder re-records "byte-identical" (C:259-261).
RESOLVE further updates the owner row, closes entries as OVERRIDDEN, and writes a label.

**The contradiction.** A PL/pgSQL function cannot call `DecisionOwner`. As specified there are two
choices:
- **Implement RESOLVE in SQL.** That is a second implementation of a rule-table row. It builds a
  `DecisionState` outside `DecisionState.next` and `allowed`, and it is not tested as "plain code".
  §1's and §14's stated properties become false, and finding 7's transitions are bypassed.
- **Keep the owner Java-only.** Then no specified path reaches RESOLVE: §7.2 rules out an API
  operation (C:511-512). CONTESTED then has no executable exit. That is review 8's MAJOR 2 again,
  this time with an alarm.

There is also no writer to record a RESOLVE, since the function cannot write a spool. Only the
recorder's 30 s pass (C:259-261) can publish it. That delay is unstated.

**Fix.**
- Keep the SQL function to authorise and append the `decision_conflict_resolutions` row and its
  audit entry.
- Have a Java resolver apply each unapplied resolution through `DecisionOwner`. It runs every P like
  the effects worker, with request id `resolve|` + the resolution id.
- Its outcome names the claims it cleared.
- Alternatively, specify an internal operator endpoint that calls `DecisionOwner`.
- State the delay before the bank receives the resolution.

### 3. MAJOR (origin: SINGLE-OWNER DESIGN): after the cutover the reconciler resubmits every historical customer "yes", which records duplicate LEGITIMATE labels that can arrive after a later FRAUD label

**Rules:** §6.5 ("It lists WAS_ME responses older than 10 s that have no `transaction_requests`
row", C:463), §4.4 and §6.4's backfill ("from every `decision_states` row with sequence > 1 … with
the row's event id", C:226-228, 441), §4.5's request id `verification|` + the response's id (C:245),
§7.4's `fs_customer_answers_deferred_oldest_seconds` (C:560).

**The mechanism.**
- The backfill derives nothing from a response. A backfilled CUSTOMER_VERIFICATION state carries
  M6's random event id and no `verification|…` request id.
- So every WAS_ME response answered before V81 has "no `transaction_requests` row". The lister has
  no lower time bound, so it lists all of them.

**What happens after V81.** For each historical WAS_ME, oldest first, 100 per tick
(`notify/verification/UnblockReconciler.java:39, 44-53`):
- **APPROVE by CUSTOMER_VERIFICATION** (M6 applied it): step 4 gives LABEL_ONLY. This records a
  **second** LEGITIMATE CUSTOMER label, with a new id (`verification_label|…`), published now.
- **DECLINE by MODEL** (PB-156's stuck rows): applied, which is correct.
- **Any later human decline:** REFUSED and recorded. This is harmless.

**Why it is a MAJOR.**
- **An effect is doubled.** §4.3's "a retried or re-run request is therefore applied at most once"
  (C:192-193) is false for every answer M6 already applied.
- **It can overturn a later label.** The feature store keeps the label that arrives last (§11,
  carried m6). A transaction that a customer approved and that later received a FRAUD label (source
  CHARGEBACK is admitted by `V5:148`) gets a LEGITIMATE label that arrives after it.
- **It fires an alarm at cutover.** `fs_customer_answers_deferred_oldest_seconds` reports the oldest
  historical response and keeps firing until the backlog drains.
- **It delays genuine answers.** Truly deferred answers wait behind that backlog.

**This was fixed before.** The old contract solved exactly this (review 3, MAJOR 2): its
reconciler "treats an unblock as already handled if `v_decision_states` holds a state by
CUSTOMER_VERIFICATION … That includes unblocks handled before V81" (`OLD:315-317`). This contract
dropped that rule.

**Fix.** Do either of the following and add a property for it:
- **in V81,** backfill a `transaction_requests` LABEL_ONLY or marker row keyed
  `verification|<response id>` for every pre-V81 response, joining response → verification → block
  → transaction;
- **or in the lister and the alarm,** exclude responses whose transaction has a
  CUSTOMER_VERIFICATION state, as the old contract did.

### 4. MAJOR (origin: INHERITED FROM M6, recorded and scoped, not a reason to stop): review 8's MAJOR 1 in the reverse order still decides a payment twice, and the window is wider than the contract's example suggests

**Rules:** §4.2's option A (C:171-174), §7.3's residual before detection (C:542-550), §7.5
(C:566-583). The mechanism underneath: `DecisionService.decide` spools, saves the Redis state, then
schedules the hold (`decision/application/DecisionService.java:240-263`); ADR 0067 points 3 and 4.

**Interleaving.**
1. Instance 1 decides HOLD X, schedules its `DueHold`, and dies before X drains and before
   `complete`.
2. The bank retries after the lease. §7.5 verifies against PostgreSQL, finds nothing, and the retry
   decides DECLINE Y. The bank is told DECLINE.
3. **Y has not drained when X's deadline passes.** This happens whenever the sinks lag by more
   than the hold window, which is the normal state just after a PostgreSQL outage, when the poller
   fires every overdue hold at once (finding 1, step 2).
4. X's TIMEOUT creates the owner from the `DueHold` and applies TIMEOUT_RELEASE at sequence 2. The
   webhook sends `decision.final` sequence 2 TIMEOUT_RELEASE to a bank that was told DECLINE
   (`notify/webhook/WebhookDispatcher.java:147-185`).
5. Y then drains and the transaction becomes contested (§6.1). If Y's spool volume is lost instead,
   detection never happens and no alarm fires.

**What the contract gets right.** It states both outcomes: the residual and its resolution
(C:542-546), and the never-detected case (C:547-550, §11). The review-8 order, with Y drained first,
is now closed (re-run below). So the contract's claims about what remains are true.

**What it understates.** The example reads as a rare race. In fact a PostgreSQL outage together
with an instance crash and a bank retry produces it, because during the outage the retry is
"decided unverified" (ADR 0067 point 6).

**Fix (optional, narrows the window).**
- Before creating an owner from a `DueHold`, compare the `DueHold`'s event id with the Redis state
  cache's sequence-1 `event_id` (`decision/adapter/redis/RedisDecisionStates.java:31-38`). Redis is
  shared, and Y wrote it at decision time (`DecisionService.java:246-247`).
- On a mismatch, DEFER the TIMEOUT for one tick, or record the contest from the cached state.
- This uses Redis only as a detector, never as the state. It closes the step-3 window except during
  a Redis outage.
- Otherwise, state the outage-recovery trigger in §7.3 and §11.

### 5. MINOR (origin: SINGLE-OWNER DESIGN): TIMEOUT's checks have no stated precedence, and in review 8's own order a natural reading drops the hold without recording the contest

**Rule:** TIMEOUT's "Otherwise" column (C:298).

In review 8's order, Y (DECLINE) has drained and no owner exists. The TIMEOUT creates the owner from
Y's sequence-1 row, so `decision` = DECLINE, `deadline` IS NULL and `first_decision` = DECLINE.
Three "Otherwise" cases then apply at once:
- "DB time < `deadline`", which is NULL here;
- "not HOLD: REFUSED, drop";
- "Basis value ≠ `first_decision`: … second first decision".

Evaluated in the listed order, the contest is never recorded. No release happens, so nothing is
decided twice, but P7 fails and the bank, which may hold X's HOLD, gets no resolution.

**Fix.** State the order: contested, then basis against `first_decision`, then HOLD, then claim,
then deadline.

### 6. MINOR (origin: SINGLE-OWNER DESIGN): re-detecting an already-recorded conflicting decision re-contests a resolved transaction

**Rules:** §7.1's "What detection writes" (C:494-498), §7.2's "A third first decision …" (C:522).

**The routes.**
- **The `DueHold` route.** It records X's conflict from the `DueHold` (C:489-492), and the risk
  officer resolves. Hours later instance 1 restarts and X's record drains for the first time. The
  sink sees a different value and, by §7.1, sets `contested_since` again.
- **Ordinary re-delivery.** The drainer is at least once: it commits the spool offset after the
  sink's commit (`decision/adapter/spool/SpoolDrainer.java:117-118`). The same thing happens on a
  re-delivered batch.

**The effect.**
- The poll answers 503 again.
- Every request is frozen again.
- A second RESOLVE writes yet another final decision to the bank. It may choose differently.

There is an alarm and an exit, so this is not a MAJOR.

**Fix.** Detection sets `contested_since` only when its `decision_conflicts` insert inserts a row.
Its key, `(institution, transaction, event_id)`, already allows that, and the `DueHold` carries the
event id.

### 7. MINOR (origin: SINGLE-OWNER DESIGN): §5.1's "five transitions" undercounts, `allowed` cannot express them, and two code paths mislabel them

**Rules:** §5.1 (C:357-367), §12 (C:807), §14 (C:849).

**The count.** "Any state → APPROVE or DECLINE by SENIOR_OVERRIDE, reason `contest_resolved`" adds
HOLD → APPROVE, HOLD → DECLINE, APPROVE → APPROVE and TIMEOUT_RELEASE → APPROVE, all by
SENIOR_OVERRIDE. Together with the other three, that makes seven new edges, not five.

**`allowed`.** `DecisionState.allowed(from, to, by)` (`decision/domain/DecisionState.java:131-143`)
sees neither the previous maker nor the reason codes. So:
- it cannot admit APPROVE → DECLINE by ANALYST only after CUSTOMER_VERIFICATION;
- it cannot admit SENIOR_OVERRIDE from HOLD only for RESOLVE.

"Exactly §5's transitions" needs a wider signature. The OpenAPI rule list needs the conditions in
words, because `supersedes_decision` carries no maker.

**The reason code.** `contest_resolved` violates the frozen `ReasonCode` pattern
`^[A-Z][A-Z0-9_]{2,63}$` (`contracts/openapi/fraudshield-api.yaml:2904-2906`). Use
`CONTEST_RESOLVED`.

**Audit labels.** `KafkaMessages.changeAction` labels any ANALYST state DECLINE as `HOLD_DECLINED`
(`decision/adapter/events/KafkaMessages.java:288-296`). DECLINE → DECLINE and APPROVE → DECLINE by
ANALYST would be audited as hold decisions. §14 should name this change.

**`FinalDecision.final`.** Its text, "superseded by … customer verification or senior override"
(`fraudshield-api.yaml:3185-3187`), omits the analyst.

### 8. MINOR (origin: SINGLE-OWNER DESIGN): "deferred, not discarded" is false for analyst effects; RESOLVE always discards them

**Rules:** ADR 0114 (line 37-38: "While contested, work is deferred, not discarded"), §12 (C:814),
§7.3's EFFECT DEFERRED (C:528-529), §5 EFFECT's "Otherwise" (C:301), RESOLVE's "`claim` → NONE"
(C:306), P8 (C:830).

**Why every deferred EFFECT is discarded.** RESOLVE always clears the claim. So an EFFECT deferred
by a contest is always REFUSED `claim-lost` afterwards, and P8's first branch ("applied after
RESOLVE") is unreachable.

**What is lost.** The analyst's committed decision is named in the resolution's result, but its
label is never recorded. A human's FRAUD judgement is lost from the training data.

**Fix.** Either:
- record the refused EFFECT's label as LABEL_ONLY after RESOLVE; or
- correct ADR 0114, §12 and P8 to "deferred, then refused and named".

### 9. MINOR (origin: SINGLE-OWNER DESIGN): §4.3 looks up the request id before taking the lock, so a concurrent re-run of EFFECT fails on a unique key instead of returning the stored outcome

**Rules:** §4.3 steps 1 and 2 (C:191-194), D4 ("without a lock", C:654), D4(e) (C:671-673), P3
(C:825).

**Interleaving.**
1. Two effects workers select the same decision. Both find no request row, and both wait on the
   owner row.
2. The first applies the EFFECT and clears the claim.
3. The second evaluates: `claim_decision_id` no longer matches, so the outcome is REFUSED
   `claim-lost`, "recorded".
4. Its INSERT then hits the unique `request_id` (C:215). The error is SQLSTATE 23505, which is class
   23. D4(e) treats it as a permanent failure: it counts `fs_analyst_effects_failed_total`, and it
   tries an effects row FAILED over an existing APPLIED row.

Nothing is applied twice, but P3's "runs once … whether the worker runs once or twice concurrently"
passes only by accident.

**Fix.** Repeat step 1 after the lock (or take the lock first), and treat a unique violation on
`request_id` as a re-run.

### 10. MINOR (origin: SINGLE-OWNER DESIGN): escalating an ANOMALY alert violates V4's CHECK

**Rules:** D6(d) "with `review_deadline_at` = the owner's `deadline`" (C:709-710); §4.1's deadline
for a non-held ESCALATED claim (C:148, 156).

V4 has `CHECK (tier <> 'ANOMALY' OR review_deadline_at IS NULL)` (`V4:28`). An escalated ANOMALY
entry copies its tier and gets a non-null deadline, DB time + W. Every escalation of an anomaly
alert therefore fails with 23514.

**Fix.** Either:
- leave the escalated entry's `review_deadline_at` NULL unless the transaction is held, and show the
  window from the owner; or
- relax the CHECK in V81 for escalated entries.

### 11. MINOR (origin: SINGLE-OWNER DESIGN): the requesters are not on the runner, so §2's lock timeout and §3's deadlock retry do not reach them

**Rules:** §2 "Every transaction here runs through the runner" (C:92-96), §3's exception "the
runner retries the loser" (C:131-133), §14 (C:845-865).

**Where the requesters run today.**
- `VerificationService.answer` uses a raw `DataSource` connection (`notify/verification/VerificationService.java:213-238`).
- The poller and the sweep run through `HoldTimeoutService` with no transaction runner
  (`decision/application/HoldTimeoutService.java:69-109`).
- `UnblockReconciler` and the sink use raw JDBC as well.
- Only `audit/jdbc/TenantTransactions.inTenant` retries, and only 40001 (`TenantTransactions.java:26, 69`).

**Why it matters.**
- §3's exception names a TIMEOUT as one side of the deadlock. If the TIMEOUT loses, nothing retries
  it.
- §6.3 does not say what the poller does with a hold it has already removed from Redis when 40P01 or
  55P03 (lock timeout) arrives. Both are outside §8's definition of "unavailable" (C:601-602). A
  drop leaves it to the sweep 10 to 15 s later, outside D-18.

**Fix.** §14: move every requester onto the runner (or give each the same settings and retry), and
state that the timeout paths reschedule on 40P01, 55P03 and 57014.

### 12. MINOR (origin: SINGLE-OWNER DESIGN): §7.4's "no payment is held past its deadline + 15 s without an alert" has more exceptions than the one stated

**Rules:** §7.4 (C:552-564), §7.3 (C:547-550), §11 (C:776-780).

**What the statement misses.**
- **During a PostgreSQL outage the gauges cannot be computed.** They are computed "from PostgreSQL
  every 30 s" (C:554), and an outage is exactly when ADR 0111 holds every payment. The only live
  signal is §6.3's reschedule counter, which M9 must alert on (ADR 0111). §7.4 should say so.
- **Sampling every 30 s adds up to 30 s** to "+ 15 s".
- **A second first decision that is dead-lettered is also never detected** until M9 replays it.
  §7.3 names only spool loss.

**Fix.** List these exceptions in §7.4, §7.3 and §11, and add the outage signal to §7.4.

### 13. MINOR (origin: SINGLE-OWNER DESIGN): an ANALYST claim whose effect failed permanently has no exit

**Rules:** D4(e) ("the claim is left for an operator", C:671-673), RESOLVE "Applies when
`contested_since` is set" (C:306).

**What a FAILED effect leaves.** No path clears the ANALYST claim:
- a held payment stays held;
- the customer's "yes" stays deferred;
- an override answers 409 `decision-not-committed`.

All of these persist for ever. The alarms (P14) fire, so this is not a MAJOR, but the operator has
nothing to run.

**Fix.** Either:
- extend RESOLVE, or a sibling, to release a FAILED claim; or
- state the manual SQL in M9's runbook.

### 14. MINOR (origin: INHERITED FROM M6 and this contract): the effects worker is not given the reconciler's starvation guard

**Rules:** D4(a) "Committed analyst decisions with no effects row, oldest commit first" (C:655),
§7.3 "EFFECT … DEFERRED, with no effects row" (C:528-529), §6.5's exclusion for the reconciler
(C:464-469).

**The problem.** A DEFERRED effect on a contested transaction is re-selected every P, at the head of
the queue. If the worker uses a batch limit, this is PB-156's starvation pattern.

**Fix.** Exclude contested owners in D4(a). Their age is still shown by
`fs_analyst_effects_oldest_pending_seconds`.

### 15. MINOR (origin: SINGLE-OWNER DESIGN): where the owner's `timeout_policy` comes from is unstated

**Rules:** §4.1 (C:144), §4.2, §6.2, §6.4.

**Why the source matters.**
- `decision_states` stores no policy.
- The sweep derives the policy from `risk_thresholds` by `decided_at` (`decision/adapter/jdbc/JdbcOverdueHolds.java:28-37`).
- The `DueHold` carries the policy that was in force at decision time
  (`DecisionService.java:253-259`).
- These differ across a configuration change, which review 8's finding 5 noted.

**What is unspecified.** For each of the sink, a request that creates the owner from sequence 1, and
the backfill, the contract does not say which source is used.

**Fix.** Name the source for each. `TransactionDecided`'s thresholds version is exact.

### 16. MINOR (origin: SINGLE-OWNER DESIGN): §14 omits three changes the contract depends on

- **V62's `institutions_with_overdue_holds`** still means "no `decision_states` row with sequence
  > 1" (`V62`). After V81, every institution with a timed-out HOLD is listed for good. This is a cost,
  not a wrong answer: §6.3's per-institution query filters.
- **The Redis `DueHold` member format.** `RedisHoldSchedule` packs the hold into the ZSET member
  (`decision/adapter/redis/RedisHoldSchedule.java:217-230, 256-266`). Members scheduled before the
  cutover survive A3's drain without the new fields, so the parser must accept both formats.
  Otherwise the claimed member is lost and the sweep times the hold out 10 to 15 s late.
- **`KafkaMessages.changeAction`** (finding 7).

### 17. MINOR (origin: SINGLE-OWNER DESIGN): the unblock record after RESOLVE and after a human's re-decline

**Rules:** RESOLVE's writes (C:306), EFFECT's APPROVE by CUSTOMER_VERIFICATION → DECLINE (C:314),
V5's `unblock_events` and `v_auto_block_status` (`V5:90-104, 124-140`).

**Two gaps.**
- **RESOLVE to APPROVE on an auto-blocked transaction writes no `unblock_events` row.** OVERRIDE to
  MARK_LEGITIMATE does (C:321). The block view therefore shows "blocked" under a final APPROVE.
- **A re-decline after a customer's lift leaves the CUSTOMER_VERIFICATION unblock row.** This now
  happens through an analyst's EFFECT as well as an override. The view then shows "lifted" under a
  final DECLINE.

**Fix.** State both.

### 18. MINOR (origin: SINGLE-OWNER DESIGN): §3's "Nothing else reverses the order" is not true of the sink

**Rules:** §3 (C:115-133), §6.1 (C:374-375, 392-394), §6.2 (C:398-401).

**Two orders the sink breaks.**
- **Within a batch it takes level 4 and level 5 per record:** 4(A), 5(A), 4(B), and so on. Level 5
  for A therefore precedes level 4 for B.
- **§6.2 inserts the entry (level 1) after level 4.**

**Why it is harmless.** I found no cycle with a request, because a request touches one transaction.
Two sinks can deadlock on two transactions decided twice, in opposite orders. That is 40P01, which
the sink classifies as transient (`decision/adapter/jdbc/Tenant.java:43-51`) and retries.

**Two related costs.**
- **The lock-table check.** "Batch size … within `max_locks_per_transaction` × connections" is the
  shared lock table, not a per-batch limit.
- **Class 53 is transient.** An exhausted lock table therefore retries the same batch for ever,
  without shrinking it.

**Fix.** State the sink as the second exception, and the class-53 behaviour.

---

## Review 8's MAJORs, re-run against this design

- **MAJOR 1 (`DueHold` after a fresh retry decision).**
  - **In review 8's order (Y drained first):** the TIMEOUT takes the first-decision lock, creates
    the owner from Y's visible sequence-1 row, and finds HOLD ≠ DECLINE. It records the contest and
    applies nothing. **Closed**, subject to finding 5's precedence.
  - **Review 8's never-detected variant (X never drains):** the `DueHold` carries the evidence.
    **Closed.**
  - **The reverse order (finding 4):** remains, stated, M6's.
- **MAJOR 2 (a freeze with no exit; terminal SKIPPED).**
  - EFFECT is DEFERRED with no effects row.
  - RESOLVE always leaves HOLD, so no payment stays held after it.
  - The contested and overdue alarms fire.
  - **Closed in substance**, subject to finding 2 (the exit must be implementable) and finding 8
    (deferral ends in discarding).
- **MAJOR 3 (a senior's decline creates no state).** I tried four orders: analyst LEGITIMATE then
  senior FRAUD then "yes"; "yes" during the analyst's claim; "yes" before the senior; "yes" racing
  OVERRIDE under the row lock. **Closed** in every order:
  - DECLINE → DECLINE by SENIOR_OVERRIDE makes step 2 refuse the "yes";
  - APPROVE by CUSTOMER_VERIFICATION → DECLINE by SENIOR_OVERRIDE or ANALYST re-declines after an
    earlier "yes";
  - the refusal writes no label and no unblock row.

## §12 checked

**Complete except for these:**
- the old contract's reconciler rule for pre-V81 unblocks (`OLD:315-317`), dropped without mention
  (finding 3);
- "defers work instead of discarding it" (C:814), false for analyst effects (finding 8);
- "five transitions" (C:807), which is seven (finding 7).

**The other rows match the text and the code.** One addition belongs there: a timeout under the
DECLINE policy now closes entries as AUTO_DECLINED. M6's sink closed MEDIUM entries only on
TIMEOUT_RELEASE (`PostgresSink.java:438-452`).

## Mechanisms checked and found sound

- **The owner row as the serialisation point.**
  - CLAIM and TIMEOUT are mutually exclusive on DB time and `claim`, at READ COMMITTED, including a
    fast JVM clock: an early poller is REFUSED and reschedules (P4).
  - RELEASE against EFFECT is ordered by V4's `forbid_commit_and_undo` advisory lock
    (`V4:91-111`) and the commit or undo row.
  - An undo after the deadline is timed out on the next tick (D2(e)).
- **The first-decision lock between the sink and a creating request.**
  - Both orders work: a request waits for the sink's commit and sees its sequence-1 row, or the
    sink waits and then compares.
  - The per-record fallback path (`PostgresSink.java:202-213`) takes the lock per record.
  - Batch and per-record paths alike see their own uncommitted rows.
  - So §4.2's "no first decision can reach PostgreSQL unseen by an owner that already exists" holds.
    A dead-lettered record does not reach PostgreSQL at all.
- **Late entries (§6.2).** Either order of the sink and a TIMEOUT closes the entry. The AUTO_* key
  derived from the request makes the AUTO_* row unique (`V4:54`).
- **§3's stated exception.** The deadlock it describes is the only one between a request and a
  decide. A late entry cannot commit while a TIMEOUT holds level 5, because §6.2's sink needs level 5
  first.
- **One timeout per transaction.** `hold_timeout|institution|transaction`, together with "REFUSED
  writes nothing", means a reschedule never freezes into a stored refusal. Stray ZSET members left by
  a duplicate `DueHold` (`RedisHoldSchedule.java:161-166`) evaluate as no-ops.
- **CONTESTED behaviour per request.**
  - TIMEOUT is refused and the hold dropped; RESOLVE always leaves HOLD.
  - CLAIM, ESCALATE and OVERRIDE answer 409.
  - RELEASE restores the claim, and its reschedule is refused again.
  - CUSTOMER_YES is deferred, and the reconciler excludes it.
  - CUSTOMER_NO writes its label.
  - A late MEDIUM entry while contested stays open until RESOLVE closes it.
  - A third first decision re-contests the transaction (finding 6 aside).
- **RESOLVE at the bank.** Sequence k + 1 is above everything either first decision led to:
  - the webhook's `sequence > latest` delivers it and supersedes PENDING
    (`WebhookDispatcher.java:37-51, 169-184`);
  - Kafka consumers order by sequence;
  - the poll reads the view's highest sequence.

  A same-value state at a higher sequence (a human's confirmation) is also delivered. Deduplication
  is by `event_id` only.
- **`DecisionState`'s invariants under the new transitions.** HOLD only at sequence 1, MODEL only at
  sequence 1, `supersedes` non-null after 1, and no return to HOLD. All are preserved: the
  constructor places no same-value restriction (`DecisionState.java:37-58`). The FinalDecision
  schema's `allOf` admits them (`fraudshield-api.yaml:3207-3231`).
- **The customer path.**
  - The answer and its request commit together.
  - "Database unavailable" leaves the token unspent.
  - DEFERRED is bounded by G + P under an ANALYST claim and by W under an escalation, and it is
    alarmed under a contest.
  - The reconciler's exclusion prevents a claimed or contested owner from starving its batch.
  - The 10-minute link is enforced at the response insert (V5 triggers), never at the request.
  - The unique `auto_block_event_id` (`V5:93`) prevents a second unblock row.
- **The poll (§8).** Redis cannot fall behind during an outage, because every request needs
  PostgreSQL. "Unavailable" is defined. A contested transaction answers 503 while PostgreSQL
  answers, and is answered from Redis (stated) when it does not. The set-if-higher change replaces
  `RedisDecisionStates`' consecutive-only script (`RedisDecisionStates.java:31-38`) without
  affecting the read order.
- **§7.5's lease.**
  - It is consistent with `ResilientIdempotency.verify` (`ingest/idempotency/ResilientIdempotency.java:81-105`).
  - `JdbcIdempotency` returns InFlight only when `transaction_ids` exists without sequence 1 or its
    score. The sink writes those in one transaction, so "no new 503s" holds, subject to finding 1's
    second gap.
- **The view and the backfill for states.** Sequence 1 comes from `decision_states` and later
  states from `transaction_requests`. The `NOT VALID` CHECK refuses stray writes. The sink's skip of
  sequence > 1 keeps a recorded request's label insertable (`PostgresSink.java:434-467`).
- **The sink's batch deadlocks.** Only sink against sink, on doubly decided transactions. 40P01 is
  transient there and the batch is retried. The batch of 200 holds at most 200 advisory locks.
- **Escalation against TIMEOUT.** It is serialised on the row. ESCALATE never shortens the deadline,
  and a failed reschedule is refused as early and reschedules itself (D6(e)).
