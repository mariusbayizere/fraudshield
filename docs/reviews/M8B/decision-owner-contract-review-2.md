# Review 2: the decision-owner contract (draft 2), end to end

Subject: `docs/architecture/decision-owner-contract.md` (draft 2), with ADR 0114, ADR 0111 and
ADR 0113, at local commit `1466a38` (worktree `fs-wt/m8b`).

Reviewer: independent and adversarial. I wrote none of this contract, none of the old contract's
drafts, and none of the earlier reviews, review 1 included.

Method: reading only. No build, test, container or network call was made. Contract line numbers
are `C:<n>` at `1466a38`. Code paths are abbreviated as in review 1:
- `decision/` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`;
- `notify/` is `backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`;
- `ingest/` is `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/`;
- `audit/` is `backend/audit/src/main/java/io/github/mariusbayizere/fraudshield/audit/`;
- `V<n>` is `backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

**Scope, as the owner's lesson asks.** I reviewed the whole contract, §1 to §15, and not its diff
from draft 1. I also reviewed the M6 mechanisms it rests on and asked of each whether its
assumptions still hold under the owner: `DecisionService`, the idempotency claim (`RedisIdempotency`
and its claim script, `ResilientIdempotency`, `JdbcIdempotency`), `HoldTimeoutService`,
`RedisHoldSchedule`, `ResilientPorts`, `JdbcOverdueHolds`, `PostgresSink` (batch, per-record
fallback, the first-decision skip), `Tenant`, `DecisionTransitionService`, `DecisionState`,
`RedisDecisionStates`, `JdbcDecisionStates`, `KafkaMessages`, `SpoolDrainer`, `VerificationService`,
`UnblockReconciler`, `CustomerSmsSender`, `WebhookDispatcher`, `IngestService`, `IngestController`,
`TenantTransactions`, `BackgroundTasks`, `AccountQueries`, migrations V3, V4, V5, V8, V11, V62, V63,
V64, V71 and V80, and ADRs 0064 and 0067.

## Verdict: UNSOUND

**MAJOR present: YES** (two: finding 1, EDGE SPECIFICATION; finding 2, INHERITED FROM M6).

**MAJOR in the owner mechanism: NO.**

| # | Severity | Origin | Subject |
|---|---|---|---|
| 1 | MAJOR | EDGE SPECIFICATION | V81's PRE_V81 markers abandon every customer "yes" that M6 had recorded but not yet applied |
| 2 | MAJOR | INHERITED FROM M6 | The double decision in reverse order (review 1's MAJOR 4): recorded and scoped; its statement holds, with one correction to its frequency |
| 3 | MINOR | EDGE SPECIFICATION | §6.1 case 1 compares a late record with the kept sequence-1 row, not with the decision the owner stands on: after case 5, the owner's own first decision re-contests the transaction |
| 4 | MINOR | EDGE SPECIFICATION | §6.1 case 5's written facts: the stored history contradicts `FinalDecision`, and a later RESOLVE to DECLINE never sends the customer's SMS |
| 5 | MINOR | EDGE SPECIFICATION | The timeout paths' failure handling is an incomplete list, and M6's `tick()` loses the rest of a claimed batch |
| 6 | MINOR | EDGE SPECIFICATION | RELEASE_FAILED: no reschedule ("at once" is false), the entry stays closed as the analyst's, and the breaker counts the failed decision |
| 7 | MINOR | EDGE SPECIFICATION | The resolution's actor is asserted, not authenticated; RESOLVE's label source, entry record and audit placement are unstated |
| 8 | MINOR | EDGE SPECIFICATION | §14 omissions: the V64 lister must be rewritten, the new cross-tenant listers are unnamed, and "the runner" is in a module that `decision` and `notify` do not depend on |
| 9 | MINOR | EDGE SPECIFICATION | §7.4's "+45 s without an alert" counts the gauge, not the alert |

**What the verdict says.**
- **The owner mechanism holds.** I found no defect in the owner row, its lock, or the rule table's
  ordering (see "Mechanisms checked and found sound"). Under the stopping rule the author does
  **not** stop.
- **Why UNSOUND, not SOUND_WITH_CHANGES.** As written, draft 2 loses an effect without an alert
  (finding 1) and states a property that encodes the loss (P8b). The fix is local: a narrower
  marker in V81 and one ON CONFLICT clause. It is draft work, not a redesign.
- **Finding 1 is a regression introduced by answering review 1's MAJOR 3.** Review 1's first
  suggested fix (a marker for every pre-V81 response, review 1 line 183-185) is itself incomplete,
  and draft 2 adopted it. Review 1's alternative, which was the old contract's rule, would not have
  had this defect.

---

## Findings

### 1. MAJOR (origin: EDGE SPECIFICATION): V81's PRE_V81 markers abandon every customer "yes" that M6 had recorded but not yet applied

**Rules:**
- §4.4 PRE_V81 markers: "one row with outcome PRE_V81 for every customer verification response that
  exists at the cutover … So every answer M6 already handled is 'already requested'" (C:274-278);
- §6.4 (C:545-546); §6.5's reconciler key, "WAS_ME responses … that have no
  `transaction_requests` row" (C:570);
- §7.4's deferred-answer alarm, "WAS_ME responses with no request row" (C:718);
- §12's row (C:989) and P8b (C:1010).

**The mechanism underneath (M6).** M6 does not apply a "yes" in the transaction that stores it.
- `VerificationService.answer` commits the response and its `unblock_events` row first
  (`notify/verification/VerificationService.java:206-240`).
- Only then does it call `customerAnswered` (`:250`). If that call fails, it logs and returns
  "pending" (`:254-262`).
- `UnblockReconciler` applies the stranded rows later. It lists unblock rows with no
  CUSTOMER_VERIFICATION state (`notify/verification/UnblockReconciler.java:44-53`, V64).

So at any moment M6 can hold WAS_ME responses that are **recorded but not applied**: the decision is
still DECLINE by MODEL, and `unblock_events` already says "lifted".

**Why such rows exist at the cutover.** Two causes, both in M6 today:
- **A failure just before A3's stop.** A3 step 1 stops the reconciler and the verification endpoint
  (C:119-121). Step 2 waits for the spools to drain (C:122), not for V64's list to empty. An answer
  whose transition failed in the last seconds before the stop is stranded.
- **PB-156, still open.** A row the reconciler refuses (the decision moved on, for example to
  APPROVE by SENIOR_OVERRIDE) is never marked. It is re-listed at the head of every run
  (`UnblockReconciler.java:44-53, 102-104`; V64). An institution with 100 such rows starves every
  newer stranded row indefinitely. So stranded rows can be days old at the cutover.

**Interleaving.**
1. Before the cutover, a customer answers WAS_ME on a block whose decision is DECLINE by MODEL. The
   response and `unblock_events` commit. `customerAnswered` fails (for example, an unknown spool
   outcome), or the reconciler never reaches the row because of PB-156.
2. A3 stops everything, the spools drain, and V81 runs.
3. V81 writes a PRE_V81 marker `verification|<response>` for this response, like every other one.
4. After the cutover, the reconciler lists only responses with no `transaction_requests` row. This
   one has a marker, so it is never submitted.
5. The alarm keys on the same anti-join, so it never fires.

**The result.**
- The decision stays DECLINE for good. The bank is never sent the APPROVE.
- No LEGITIMATE label is recorded.
- The page told the customer the unblock was on its way (`VerificationService.java:254-262`). The
  token is spent (V5's primary key on `verification_id`, `V5:65-73`), so the customer cannot answer
  again.
- `v_auto_block_status` shows `unblocked_at` set and `currently_blocked` true, which no alarm reads.

**Why it is a MAJOR.**
- **An effect is lost, with no alert.** The customer's confirmed "yes" is silently discarded. Fixing
  this is what V64 and the reconciler were built for (Principal Review finding 6, V64's header).
- **A stated property is false.** §4.4's "every answer M6 already handled" is not what the marker
  covers: it covers every answer M6 *received*. P8b ("the reconciler submits no response that
  existed before V81") makes the loss a tested property.
- **§12's row is wrong.** It presents the marker as equivalent to the old contract's rule. The old
  rule matched CUSTOMER_VERIFICATION states, so a stranded row still had no matching state and was
  still applied (`analyst-decision-commit-contract.md:786`).

**A second defect on the same path.** Suppose the fix lets such a response be resubmitted. CUSTOMER_YES
APPLIED then writes `unblock_events` "in the same transaction" (C:388-390). That row already exists,
and `auto_block_event_id` is UNIQUE (`V5:93`). The insert fails with 23505, which the reconciler
cannot get past.

**Fix (local; the owner mechanism is unchanged).**
- **V81 writes a marker only for a response whose effect is complete or moot:**
  - every NOT_ME;
  - a WAS_ME whose transaction has a CUSTOMER_VERIFICATION state;
  - a WAS_ME whose transaction's latest state is no longer DECLINE by MODEL or TIMEOUT_POLICY.
- **A stranded WAS_ME gets no marker.** That is one still under DECLINE by MODEL or TIMEOUT_POLICY,
  with no CUSTOMER_VERIFICATION state. After the cutover the reconciler submits it, and §5 decides
  it (step 3 applies it).
- **CUSTOMER_YES APPLIED writes `unblock_events` with ON CONFLICT (`auto_block_event_id`) DO
  NOTHING.**
- **Rewrite P8b and §12's row:** "a response M6 had applied or refused is not resubmitted; a stranded
  one is applied once".
- **State the join key.** V5's responses have no `id`; the key is `verification_id` (C:275, C:293).

### 2. MAJOR (origin: INHERITED FROM M6, recorded and scoped, not a reason to stop): the double decision in reverse order

**Rules:** §7.3 (C:673-708), §11 (C:950-952), ADR 0114's consequences. The mechanism underneath:
- `DecisionService.decide` spools, then saves the Redis state, then schedules the `DueHold`
  (`decision/application/DecisionService.java:240-263`);
- ADR 0067 points 3, 4 and 6;
- `ResilientIdempotency.verify` decides unverified when PostgreSQL cannot answer
  (`ingest/idempotency/ResilientIdempotency.java:81-105`).

**Re-run.** Review 1's interleaving still decides the payment twice:
1. Instance 1 decides HOLD X and dies.
2. The retry decides DECLINE Y, which is undrained.
3. X's `DueHold` creates the owner and applies TIMEOUT_RELEASE, which the webhook sends
   (`notify/webhook/WebhookDispatcher.java:147-185`).
4. Y drains, and case 5 contests the transaction.

**Is §7.3's statement true?** Yes, in all its parts:
- the steps;
- that detection happens when Y drains;
- that RESOLVE supersedes both at the bank;
- that the bank may already have acted;
- the never-detected spool-loss case;
- the dead-letter case.

I re-checked the outage route against the code:
- **Poller.** It fires every overdue member on its first tick after recovery, in batches of 500
  (`HoldTimeoutService.java:69-84`).
- **Sinks.** They drain backlogs in batches of 200 (`ingest/config/DecisionWiring.java:166-175`).
- **Retries.** A retry during the outage is decided unverified.

**Is the frequency honest?** Mostly. One sentence overstates what the non-outage case needs: "Outside
an outage it needs a sink lagging by more than the hold window" (C:691-692).
- Y is decided at least one lease (10 s) plus the bank's retry delay *after* X.
- X's deadline is 30 s after X.
- So Y needs to lag only by more than about 30 − 10 − r seconds, at most 20 s for a MEDIUM hold.
  That is not the whole window.

**Fix (wording only).** Say "lagging by more than the hold window less the lease and the bank's retry
delay (about 20 s for a 30-second hold)". The Redis-detector option stays the owner's (C:697-702).

### 3. MINOR (origin: EDGE SPECIFICATION): after case 5, the owner's own first decision re-contests the transaction

**Rules:**
- §6.1 case 1: "compared by value with the kept sequence-1 row. A different value is a second
  first decision" (C:454-458);
- §6.1 case 5: the different-value record "becomes sequence 1" (C:470-473);
- §7.1: `decision_conflicts` holds "one row per distinct first decision the owner did **not** stand
  on", and `contested_since` is set "only when the … insert inserted a row" (C:602-609);
- P8c (C:1011).

**Interleaving (the residual of finding 2, continued).**
1. Owner from X's `DueHold` (`first_event_id` = X, HOLD) applies TIMEOUT_RELEASE.
2. Y (DECLINE) drains. By case 5 it is written in full as sequence 1, and `decision_conflicts` gets
   Y. The transaction is contested.
3. A risk officer resolves, and the resolver applies RESOLVE.
4. Instance 1 restarts and X's own record drains. `transaction_ids` exists (Y's), so this is case 1.
   Case 1 compares X with "the kept sequence-1 row", which is Y. HOLD ≠ DECLINE, so X is "a second
   first decision". X is not in `decision_conflicts`, because the owner stands on X. The insert
   inserts, and `contested_since` is set again.

**Effect.**
- The resolved transaction is contested again: 503 on the poll, every request frozen, and a second
  RESOLVE needed.
- If step 4 comes before step 3's application, `contested_since` moves. The officer's pending
  resolution is then refused `contest-changed`, and the officer must resolve again.
- This is the common case, not an exotic one. Every case-5 transaction whose dead instance later
  drains its spool does it.
- §7.1's sentence about which decisions are recorded points the other way, so the text contradicts
  itself.

There is an alarm and an exit, as in review 1's finding 6, so this is not a MAJOR. But §15's answer
to finding 6 does not hold on this route, and P8c does not test it.

**Also in case 1's text.** "A re-delivery of the same record, with the same event id, is M6's
`sameFirstDecision` case and its writes are idempotent, as today. The record is dropped as today"
(C:455-456) reads as if the same-event-id record were dropped. M6 does not drop it
(`decision/adapter/jdbc/PostgresSink.java:288-291, 349-357`); it drops a *different* event id.

**Fix.**
- **When an owner exists, case 1 compares with the owner first.** If the record's event id is
  `first_event_id` or already in `decision_conflicts`, it is not a new conflict. Only otherwise is
  it compared by value.
- **Extend P8c** with "the owner's own decision draining after case 5".
- **Split case 1's sentence** into the same-event-id re-delivery and the different-event-id
  duplicate.

### 4. MINOR (origin: EDGE SPECIFICATION): case 5's written facts contradict `FinalDecision` and suppress the SMS after a RESOLVE to DECLINE

**Rules:** §6.1 case 5 (C:470-480), §12 (C:978-994), §11's contract additions (C:956-962).

**The stored history.**
- After case 5, the view holds sequence 1 = Y (DECLINE) and sequence 2 = "TIMEOUT_RELEASE,
  `supersedes_decision` HOLD".
- The frozen schema says `supersedes_decision` is "The decision of sequence n-1"
  (`contracts/openapi/fraudshield-api.yaml:3202-3206`), and calls any other transition "a server
  defect" (`:3161`). The stored history of a resolved transaction therefore violates the contract
  for good. The poll answers 503 while contested and the latest sequence afterwards, so a bank
  never reads it directly. P1's history and any export do.
- The draft states what the view shows (C:474-476). It does not list this among §12's differences or
  §11's proposed contract changes.

**The SMS.**
- §6.1 says that after a TIMEOUT_RELEASE the block's SMS "is therefore never sent" (C:477-479).
  That is correct at the moment the intent is consumed: `CustomerSmsSender` returns RESOLVED (S2r)
  without recording anything (`notify/sms/CustomerSmsSender.java:60-73, 270-274`), and the intent is
  consumed.
- If the risk officer later resolves to DECLINE, the payment is declined and the customer never
  receives the SMS or its verification link. The block's customer path is lost, unstated.

**Fix.**
- Add both to §12, and add the `supersedes_decision` exception for contested transactions to §11's
  contract additions. Alternatively, state that the view's history of a contested transaction is
  not a `FinalDecision` chain.
- Either re-request the block's SMS on RESOLVE → DECLINE when no SENT or FAILED row exists, or state
  the loss as a cost of case 5.

### 5. MINOR (origin: EDGE SPECIFICATION): the timeout paths' failure handling is an incomplete list, and `tick()` loses the rest of a claimed batch

**Rules:** §2: "on 40P01 after the runner's retries, 55P03, 57014 or class 08, it reschedules the
hold 1 s later … It never drops it" (C:110-112). P19b lists only 40P01, 55P03 and 57014 (C:1025).

**Gaps against the code.**
- **SQLSTATEs outside the list.** None of these reschedule:
  - 40001 after the runner's attempts (`audit/jdbc/TenantTransactions.java:58-73`);
  - 53200, the lock-table exhaustion that §6.1 says the sinks can cause (C:489-495);
  - 57P01 (admin shutdown, class 57 but not 57014);
  - a pool timeout that carries no SQLSTATE;
  - the backstop trigger's refusal;
  - a `RuntimeException` from the rule table or `DecisionState`.
  "It never drops it" is therefore false.
- **The batch.** `claimDue` removes up to 500 members from Redis at once
  (`decision/adapter/redis/RedisHoldSchedule.java:41-51`; `HoldTimeoutService.java:76`). An
  exception on one hold leaves `tick()` (`:69-84`), and `BackgroundTasks.every` swallows it
  (`ingest/config/BackgroundTasks.java:90-105`). Every hold claimed after it in that batch is gone
  from Redis.
- **Where those holds go.** The sweep recovers them 10 to 15 s late (`SWEEP_GRACE` 10 s, every
  5 s). A hold whose sequence-1 row has not drained, which is the normal post-outage case, waits
  until it drains. That is lateness, not a hold for ever: the alarm and the sweep see it once
  drained.

**Fix.** State the rule as "on any failure of the request transaction, the hold is rescheduled 1 s
later and counted", per hold, and catch per hold in `tick()`. Align P19b with §2, including class 08.

### 6. MINOR (origin: EDGE SPECIFICATION): RELEASE_FAILED has no reschedule, leaves the entry closed as the analyst's, and the breaker counts the failed decision

**Rules:** §5 RELEASE_FAILED: "a held payment is then timed out at its deadline, or at once if it
has passed" (C:355); §7.2 (C:629-631); D4(d) and (e) (C:827-838); P8d (C:1012).

**The gaps.**
- **No one reschedules.** The poller dropped the Redis member when it met the ANALYST claim (C:528-530).
  RELEASE_FAILED, unlike D2(e), reschedules nothing. The payment is timed out only by the sweep, at
  the deadline + 10 to 15 s. So "at its deadline, or at once" is false.
- **The entry.** D1(f) set the entry to CONFIRMED_FRAUD or MARKED_LEGITIMATE. RELEASE_FAILED
  restores the claim but not the entry's `prior_status`, as an undo does. The TIMEOUT closes only
  open entries. The entry therefore stays "confirmed fraud" under a TIMEOUT_RELEASE, and has no
  AUTO_* row.
- **The breaker.** D4(d)'s retry selects every CONFIRM_FRAUD "whose effects row exists but which has
  no breaker row". A FAILED row exists, so the MCC breaker counts a confirmed fraud whose effect and
  label never happened.
- **The resolution row** (C:621-623) has no column for the decision id that RELEASE_FAILED needs.

**Fix.**
- The resolver reschedules after RELEASE_FAILED commits, as D2(e) does.
- RELEASE_FAILED restores the entry's `prior_status`, or the TIMEOUT also closes it.
- D4(d) excludes FAILED, and decides SKIPPED explicitly.
- Add the decision id to the resolution row.

### 7. MINOR (origin: EDGE SPECIFICATION): the resolution's actor is asserted, not authenticated; RESOLVE's records are underspecified

**Rules:** §7.2 (C:617-636), §5 RESOLVE (C:354), P20 (C:1026), V4's `alert_decisions` (`V4:41-63`),
V5's `label_events.source` (`V5:148`).

- **The actor.** The function takes `staff_user_id` from whoever calls it. It is granted to "the
  operator role that M9's runbook uses" (C:632). Nothing ties the call to that officer's
  authenticated session. Yet the audit entry, the `unblock_events` actor and the label all
  attribute the resolution to that officer. Accountability rests on the operator's word.
- **The label's source** is unstated. It must be one of V5's four; SENIOR_OVERRIDE is the only one
  that fits.
- **The closed entries.** RESOLVE closes entries as OVERRIDDEN but writes no `alert_decisions` row.
  It cannot write a SENIOR_OVERRIDE row, because V4 requires `override_of_decision_id` and a
  20-character comment. An entry then shows OVERRIDDEN with no decision behind it, so D5(b)'s "the
  entry's latest decision" reads a pre-resolution decision.
- **The audit.** P20 says RESOLVE is audited "in its change's transaction". The function audits the
  authorisation in its own transaction. The state change is audited through Kafka from the recorded
  event. "The resolver audits" the cleared claims, with no transaction stated.

**Fix.** State how the officer is authenticated, or record both the operator and the officer.
Name the label source. Say what decision record, if any, a resolution leaves on an entry. Correct
P20.

### 8. MINOR (origin: EDGE SPECIFICATION): §14 omissions

- **V64's `institutions_with_unapplied_unblocks`** keys on `unblock_events` with no
  CUSTOMER_VERIFICATION row in `decision_states` (`V64:12-24`). After V81, no such row is ever
  written to `decision_states`, so it lists every institution with any customer unblock, for good.
  The same defect that review 1's finding 16 found in V62. It must be rewritten for §6.5's
  response-based selection, not "gain the same exclusion" (C:576, C:1040).
- **Cross-tenant listers.** Row-level security scopes `fs_app` to one institution, which is why V62
  and V64 exist. The old contract named one SECURITY DEFINER lister per worker
  (`analyst-decision-commit-contract.md:179-185`), with V71's explicit grants and
  `DatabaseSecurityTest` pins. Draft 2 adds workers that each need one, and names none:
  - the resolver's unapplied resolutions;
  - the recorder's unrecorded requests;
  - the four alarms;
  - the effects worker's contested exclusion.
- **The runner.**
  - §2 names "the audit module's `inTenant`" (C:99).
  - `fraudshield-decision` and `fraudshield-notify` do not depend on `fraudshield-audit` (their
    `pom.xml`). `decision` has its own `adapter/jpa/TenantTransactions`, which does not retry at
    all.
  - §14 should name the dependency or the class.

### 9. MINOR (origin: EDGE SPECIFICATION): §7.4's "+45 s without an alert" counts the gauge, not the alert

**Rule:** "no payment is held past its deadline + 45 s without an alert, while PostgreSQL answers …
15 s of grace plus up to 30 s of sampling" (C:721-722).

The 45 s bounds when the gauge first shows the hold. An alert also needs:
- a scrape;
- a rule evaluation;
- usually a `for:` duration.

None of these is configured in the repository, and none is counted. Only `fs_decisions_contested` has
a stated alert condition ("any value above zero", C:714). The overdue, deferred-answer and
pending-effects gauges have none.

**Fix.** Say "visible in the gauge within 45 s". State each gauge's alert condition, and leave the
pipeline latency to M9.

---

## Review 1's findings: does draft 2's answer hold?

| # | Review 1 | Draft 2's answer | Holds? |
|---|---|---|---|
| 1 | MAJOR: the sink drops the owner's own first decision | §6.1's five cases, P7a | **Yes** for the finding: case 3 writes X in full and §6.2 closes its entry; re-run below. Case 5 has follow-on defects (findings 3, 4) |
| 2 | MAJOR: RESOLVE as SQL against a Java-only owner | Function authorises; resolver applies through `DecisionOwner` | **Yes.** Exactly once, crash points and `contest-changed` checked below. Smaller gaps in findings 6 and 7 |
| 3 | MAJOR: the reconciler resubmits pre-V81 answers | PRE_V81 marker for every response | **No.** It over-corrects and abandons stranded answers (finding 1) |
| 4 | MAJOR (M6's): reverse-order double decision | §7.3 residual, frequency, Redis option | **Yes**, with one frequency correction (finding 2) |
| 5 | TIMEOUT precedence | Order stated | **Yes.** Review 8's order records the contest before the HOLD check |
| 6 | Re-detection re-contests | `contested_since` only on a new conflict row | **Partly.** It holds for the routes review 1 named. It fails for the owner's own decision after case 5 (finding 3) |
| 7 | Seven transitions, `allowed`, reason code, `changeAction` | §5.1 | **Yes.** I count seven; the widened signature, `final`'s text, `CONTEST_RESOLVED` against `^[A-Z][A-Z0-9_]{2,63}$`, and `changeAction` (`KafkaMessages.java:288-296`) all match |
| 8 | "Deferred, not discarded" | LABEL_ONLY `claim-lost`; ADR 0114 corrected | **Yes** |
| 9 | Request-id lookup before the lock | Lock first; 23505 is a re-run | **Yes** |
| 10 | ANOMALY escalation against V4 | NULL deadline unless held | **Yes.** It is also consistent with V4's MEDIUM CHECK, because escalated entries carry `escalated_from_alert_id` |
| 11 | Requesters off the runner | §2, §14, P19b | **Partly** (findings 5 and 8) |
| 12 | The alarm's exceptions | §7.3, §7.4 | **Partly.** The exceptions are listed, but alerting latency is uncounted (finding 9) |
| 13 | No exit for a FAILED claim | RELEASE_FAILED | **Partly.** The exit exists; its timing and entry claims fail (finding 6) |
| 14 | The effects worker's starvation | D4(a) excludes contested owners | **Yes** |
| 15 | `timeout_policy`'s source | §4.2 point 4, §6.4 | **Yes** |
| 16 | §14's omissions | V62, the member format, `changeAction` | **Partly.** V64 and the new listers are missing (finding 8) |
| 17 | Block record after RESOLVE and a re-decline | RESOLVE writes the unblock row; `currently_blocked` | **Yes** |
| 18 | The sink's lock order, class 53 | §3 exception 2; halving on 53200 | **Yes** |

## The attack points, answered

1. **Paths that move state without the owner.** None found after the changes in §14.
   - `DecisionTransitionService` is removed. `HoldTimeoutService`'s direct `states.latest` and
     `recorder.record` path (`HoldTimeoutService.java:117-125`) becomes a request.
   - The sink writes only sequence 1, under levels 4 and 5.
   - The SQL functions write no state.
   - M9's replay goes through the sink.
   - The Redis `states.save` in `DecisionService` is a cache, not a state.
   - Every other `decision_states` reader (`grep`: `JdbcDecisionStates`, `JdbcOverdueHolds`,
     `UnblockReconciler`, `CustomerSmsSender`, `AccountQueries`, `JdbcIdempotency`, V62, V64) is
     named in §4.7 or §6.
2. **§6.1's five cases.**
   - **Review 1's MAJOR 1, re-run.** X's TIMEOUT creates the owner from its `DueHold`. X then
     drains: no `transaction_ids`, `first_event_id` = X, so case 3. It is written in full, and §6.2
     closes the entry with the request-keyed AUTO_* row.
   - **The reverse order.** X drains first: case 2, then an ordinary TIMEOUT.
   - **Case 4.** Rewriting `first_event_id` is harmless:
     - X later takes case 1 with the same value;
     - X's or Y's `DueHold` meets one request id;
     - `JdbcIdempotency` replays Y (sequence 1) with Y's deadline, the stated skew;
     - the webhook never sends sequence 1 (`WebhookDispatcher.java:147-151`).
   - **Case 5.**
     - The poll, the webhook and Kafka are right: 503, then RESOLVE at k + 1.
     - `JdbcIdempotency` reads sequence 1 and is right.
     - Entries are closed by RESOLVE.
     - Its defects are findings 3 and 4.
3. **The resolver split.**
   - **Exactly once:** the request id plus the row lock, across instances.
   - **Crash points:** function → resolver (next run), resolver → recorder (30 s).
   - **`contest-changed`:** it refuses a stale resolution, and records the refusal, so it is not
     re-listed.
   - **Two officers:** the second is refused.
   - Gaps: findings 6 and 7.
4. **PRE_V81 against the reconciler, the alarm and V5.** Finding 1, and the 23505 on `V5:93`.
5. **Requesters on the runner; reschedule on failure.** Findings 5 and 8.
   - The retry design is otherwise sound. A class-08 failure whose commit in fact succeeded is
     rescheduled. The next TIMEOUT then finds its stored outcome and re-records it, byte-identical.
6. **The seven transitions.**
   - `DecisionState`'s constructor rules hold for each (`decision/domain/DecisionState.java:37-58`).
   - The webhook supersedes by sequence and deduplicates by event id, so same-value states at a
     higher sequence are delivered (`WebhookDispatcher.java:37-51, 169-184`).
   - The OpenAPI `allOf` admits them (`fraudshield-api.yaml:3207-3231`).
   - The description must gain them in words, as §5.1 says.
7. **The alarm claim.** True of the gauge, not of the alert (finding 9). Its two stated exceptions
   are real.
8. **§7.3's residual.** True. Its frequency is honest, with one correction (finding 2).
9. **§12 and §15.**
   - **§12** misses the abandoned pre-V81 answers, and its PRE_V81 row is wrong (finding 1). It also
     misses the contested history's `supersedes_decision` and the lost SMS (finding 4).
   - **§15** claims 3, 6, 11, 12, 13 and 16 more fully than they hold (table above).

## Mechanisms checked and found sound

- **The owner row and its lock.**
  - Every request takes level 5 and reads the state from the row at READ COMMITTED. It looks up its
    request id after the lock.
  - Two requests on one transaction serialise.
  - The backstop trigger refuses a sequence gap.
  - I found no request that reads the state elsewhere.
- **Owner-row creation and the first-decision lock.**
  - Both orders of sink and creating request work. A statement after
    `pg_advisory_xact_lock` sees the sink's commit.
  - The per-record fallback (`PostgresSink.java:202-213`) takes the lock per record, and a rolled-back
    batch releases its locks.
  - `transaction_ids` and sequence 1 are written in one transaction, so `sameFirstDecision`'s "no
    sequence-1 row" branch (`PostgresSink.java:349-357`) cannot be reached.
- **The rule table's ordering.** Each request's checks are applied in order against every state
  §5 can produce:
  - contested and not visible first;
  - TIMEOUT: contested, basis, HOLD, claim, deadline;
  - CUSTOMER_YES: claim, then human decline, then model or policy decline, then approved.

  CLAIM and TIMEOUT are mutually exclusive on DB time. EFFECT's claim-lost branch is reachable only
  through RESOLVE, because RELEASE needs an undone decision and RELEASE_FAILED a FAILED one. Review
  8's MAJOR 3 interleaving ends declined in every order I tried.
- **The lock order and both exceptions.**
  - Exception 1's late-entry deadlock is the only request-against-decide cycle. It is detected
    (40P01) before `lock_timeout`, because `deadlock_timeout` defaults to 1 s and `lock_timeout` is
    2 s.
  - Exception 2: the sink cannot cycle with a request, which touches one transaction. A
    sink-against-sink cycle is 40P01, which `Tenant.transientError` treats as transient
    (`decision/adapter/jdbc/Tenant.java:43-51`). The drainer retries with jittered backoff
    (`SpoolDrainer.java:108-140`).
  - The sink has no `lock_timeout`, so it waits rather than dead-lettering on 55P03. Class 55 is
    not in `transientError`.
- **§6.2's late entries.** Either order of the sink and TIMEOUT or RESOLVE closes the entry exactly
  once. The AUTO_* key is unique per institution (`V4:54`).
- **§6.3's sweep, §6.4's backfill, and V62's new selection.** HOLD always raises a MEDIUM alert
  (`decision/domain/DecisionEngine.java:82-86`), so the sink creates an owner for every new hold.
- **§6.5's same-transaction customer path, after the cutover.**
  - "Database unavailable" leaves the token unspent.
  - DEFERRED is bounded and alarmed.
  - The reconciler's exclusion prevents starvation.
- **§7.1's detection under the row lock, and §7.2's exactly-once application.**
- **§7.5's lease.**
  - It is consistent with the claim script (`ingest/idempotency/RedisIdempotency.java` CLAIM) and
    `ResilientIdempotency.verify`.
  - `JdbcIdempotency` returns InFlight only for `transaction_ids` without sequence 1
    (`JdbcIdempotency.java:78-85`), which the sink never commits. So "no new 503s" holds.
- **§8's poll order.** Redis cannot fall behind during an outage, and a transaction absent from the
  view has no later state anywhere.
- **The webhook and Kafka under same-value and RESOLVE states**, and `KafkaMessages`' audit type for
  ANALYST and SENIOR_OVERRIDE.
- **`currently_blocked` and `CustomerSmsSender`'s "resolved" test** (`CustomerSmsSender.java:65-69`)
  after a re-decline.
- **A3's stop-and-drain**, as it applies to spools and to old-format `DueHold` members. Its gap
  concerns database rows, not spools (finding 1).
