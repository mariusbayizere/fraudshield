# Review 3: the decision-owner contract (draft 3), end to end

Subject: `docs/architecture/decision-owner-contract.md` (draft 3), with ADR 0114, ADR 0111 and
ADR 0113, at local commit `8720ebf` (worktree `fs-wt/m8b`).

Reviewer: independent and adversarial. I wrote none of this contract, none of the old contract's
drafts, and none of the earlier reviews, reviews 1 and 2 included.

Method: reading only. No build, test, container or network call was made. Contract line numbers
are `C:<n>` at `8720ebf`. Code paths are abbreviated as in reviews 1 and 2:
- `decision/` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`;
- `notify/` is `backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`;
- `ingest/` is `backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/`;
- `audit/` is `backend/audit/src/main/java/io/github/mariusbayizere/fraudshield/audit/`;
- `V<n>` is `backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

**Scope, as the owner's lesson asks.** I reviewed the whole contract, §1 to §16, not its diff from
draft 2. That covers the rule table and its precedence, the lock order and both exceptions, the
first-decision lock, owner creation, §6.1 to §6.5, §7, §8, §9's D1 to D6 and §14. I also checked
the M6 mechanisms underneath and asked of each whether its assumptions still hold under the owner:
- in `decision/`: `DecisionService`, `HoldTimeoutService`, `RedisHoldSchedule` (its member index),
  `ResilientPorts`' local fallback, `JdbcOverdueHolds`, `PostgresSink` (batch, per-record fallback,
  the `skip` path), `Tenant.transientError`, the module's `adapter/jpa/TenantTransactions`,
  `DecisionTransitionService`, `DecisionState`, `RedisDecisionStates`, `JdbcDecisionStates`;
- in `ingest/`: `RedisIdempotency`'s claim script, `ResilientIdempotency.verify`,
  `JdbcIdempotency`, `IngestService`, `IngestController`, and `DecisionWiring`, which wires both
  runners;
- in `notify/`: `VerificationService`, `UnblockReconciler`, `CustomerSmsSender`'s classification,
  `WebhookDispatcher`'s supersede rule;
- in `audit/`: `TenantTransactions`;
- the module `pom.xml` dependencies;
- migrations V3, V4, V5, V8, V11, V62, V63, V64, V71 and V80;
- ADRs 0064, 0067, 0111, 0113 and 0114.

## Verdict: UNSOUND

**MAJOR present: YES.** Two new MAJORs, findings 1 and 2. Both are EDGE SPECIFICATION and each has a
local fix. Neither is an accepted residual.

**MAJOR in the owner mechanism: NO.**

| # | Severity | Origin | Subject |
|---|---|---|---|
| 1 | MAJOR | EDGE SPECIFICATION | An ANALYST claim whose effect FAILED, on a payment that is not held, defers the customer's "yes" for ever, and no alert covers it. §7.4's coverage statement is false |
| 2 | MAJOR | EDGE SPECIFICATION | "A resolution is final through the API" is false. `resolution_id` marks only the entries RESOLVE closes, so an entry that was already closed can be overridden after the resolution |
| 3 | MINOR | EDGE SPECIFICATION | RELEASE_FAILED's success has no stated outcome or request row, so the resolver re-runs it, and the second run records a false `not-failed` |
| 4 | MINOR | EDGE SPECIFICATION | OVERRIDE on HOLD is called "not reachable", but RELEASE_FAILED makes it reachable, and the rule table has no cell for it |
| 5 | MINOR | EDGE SPECIFICATION | Module placement: `analyst` does not depend on `decision`, where `DecisionOwner` and the spool live, and the resolver, which audits, is placed in no module |
| 6 | MINOR | INHERITED FROM M6 | The PRE_V81 marker calls every NOT_ME "complete". A NOT_ME whose label M6 failed to spool lost its FRAUD label, unalerted, and the marker makes that loss permanent |
| 7 | MINOR | EDGE SPECIFICATION | V81's backfilled APPLIED rows have no stated `request_id` |
| 8 | MINOR | EDGE SPECIFICATION | No gauge or alert covers the recorder's backlog of unrecorded requests |
| 9 | MINOR | EDGE SPECIFICATION | A second ESCALATE on a payment that is not held, after W has passed, opens no new window |

**What the verdict says.**
- **The owner mechanism holds.** I found no defect in the owner row, its lock, or the rule table's
  ordering (see "Mechanisms checked and found sound"). Under the stopping rule the author does
  **not** stop.
- **Why UNSOUND rather than SOUND_WITH_CHANGES.**
  - Finding 1 leaves a payment blocked against a customer's confirmed "yes" with no alert, and it
    makes a stated coverage claim false.
  - Finding 2 makes a stated property false and lets a lower role overturn a risk officer's
    resolution through the API.
  - Both fixes are local: an alert condition, and one D5 check.
- **Both MAJORs sit on the draft-2 and draft-3 edge additions** (RELEASE_FAILED, `resolution_id`),
  not on the mechanism. Like review 2's MAJOR 1, they are answers to earlier findings that were
  specified for the path the finding named and not for the neighbouring paths.
- **The inherited residual.** §7.3's statement of M6's reverse-order double decision is true as
  corrected. It is not re-raised.

---

## Findings

### 1. MAJOR (origin: EDGE SPECIFICATION): a FAILED analyst effect on a payment that is not held defers the customer's "yes" for ever, with no alert

**Rules:**
- D4(e): a permanent failure writes an effects row FAILED, and "the claim stays until an operator
  runs `release_failed_claim` … The overdue alarm (§7.4) shows a held payment caught by it"
  (C:901-904);
- §5 CUSTOMER_YES step 1: DEFERRED while `claim` is ANALYST (C:397-399);
- §6.5: the reconciler skips owners with a pending claim (C:608);
- §7.4: `fs_customer_answers_deferred_oldest_seconds` counts answers "with no request row and no
  pending claim or contest … Answers waiting on a claim or contest are covered by the overdue and
  contested alerts, or bounded by G + P and W" (C:778-780);
- `fs_analyst_effects_oldest_pending_seconds` counts "committed analyst decisions with **no effects
  row**" (C:781-782).

**Interleaving.**
1. The model declines a HIGH payment and auto-blocks it. The SMS goes out.
2. An analyst decides the HIGH entry (D1). CLAIM is allowed "for every tier" (C:857), so the owner
   now has `claim` ANALYST.
3. The decision commits. Its EFFECT fails with a class-22 or class-23 SQLSTATE, so D4(e) writes an
   effects row FAILED and counts `fs_analyst_effects_failed_total`.
4. The customer answers WAS_ME. CUSTOMER_YES is DEFERRED (step 1). The reconciler skips the answer
   on every run (§6.5).

**What each alarm sees:**
- **Overdue:** nothing. The payment is DECLINE, not HOLD.
- **Contested:** nothing.
- **Deferred answers:** nothing. The answer has a pending claim, so it is excluded by definition.
- **Pending effects:** nothing. An effects row exists.
- **`fs_analyst_effects_failed_total`:** a counter with no alert condition. §7.4 states one for
  each of its four gauges, and none for this counter.

**The result.**
- The payment stays blocked against the customer's confirmed "yes", indefinitely, until someone
  happens to run `release_failed_claim`.
- Every human path is shut too:
  - OVERRIDE answers 409 `decision-not-committed`, "the original's effect is still pending"
    (C:370, C:917). That is false, because the effect has run and FAILED.
  - ESCALATE answers 409, because `claim` is ANALYST (C:369).
- §7.4's sentence that answers waiting on a claim are "covered by the overdue … alerts, or bounded
  by G + P" is false for this claim.

**Why it is a MAJOR.** A payment is held, here blocked against an applied-on-answer rule, for ever
without an alert, and a stated coverage property is false. The held-payment variant is covered by
the overdue gauge. The variant that is not held is not covered.

**Fix (local).**
- Give `fs_analyst_effects_failed_total` an alert condition (any increase). Better, add a gauge of
  FAILED claims not yet released (count and oldest) with "above zero" as its alert.
- Alternatively, have the deferred-answer gauge exclude only claims whose effect has **no** effects
  row, so a claim stuck on FAILED counts as deferred and alerts after 60 s.
- Make D5(d) answer a FAILED claim with its own problem type (for example
  `decision-effect-failed`), not "pending".
- Add a property: "a customer's 'yes' deferred by a FAILED claim on a payment that is not held is
  visible in an alerting gauge".

### 2. MAJOR (origin: EDGE SPECIFICATION): "a resolution is final through the API" is false

**Rules:**
- §7.2: open entries are closed as OVERRIDDEN with `resolution_id`. "D5(b) refuses an override on
  an entry with a `resolution_id` (409 `stale-alert`). So **a resolution is final through the
  API**" (C:691-696);
- §11: "A resolution is final through the API (§7.2). Changing it needs another operator action"
  (C:1023);
- D5(b): the original is the entry's latest decision that is neither undone nor overridden, of kind
  CONFIRM_FRAUD, MARK_LEGITIMATE, AUTO_RELEASED, AUTO_BLOCKED or SENIOR_OVERRIDE, and committed
  (C:908-912);
- §5 OVERRIDE applies from any state when `claim` ≠ ANALYST (C:370, C:387-391).

**The gap.** RESOLVE stamps `resolution_id` only on entries that are **open** when it runs. Entries
of the same transaction that were already closed carry none. Three ordinary routes leave such an
entry:
- **A late entry closed by §6.2 before the contest.**
  1. X's TIMEOUT creates the owner from its `DueHold`.
  2. X drains by case 3, and §6.2 closes X's entry as AUTO_RELEASED with an AUTO_* row, which is
     committed because `undo_until` is NULL.
  3. Y (DECLINE) drains by case 1(b) and contests the transaction.
- **An analyst's decision whose claim RESOLVE cleared.** D1 set the entry to CONFIRMED_FRAUD or
  MARKED_LEGITIMATE. It is not open, so RESOLVE does not stamp it. The EFFECT later ends LABEL_ONLY
  `claim-lost` and writes its effects row, so D5(b)'s "committed, effect run" holds.
- **An analyst's decision applied before the contest was detected.** An example is Y's HIGH entry
  decided before X's `DueHold` fires (§7.1, the TIMEOUT detection route).

**Interleaving (route 1).**
1. A risk officer resolves to DECLINE, and RESOLVE applies it at sequence k by SENIOR_OVERRIDE.
2. A senior analyst opens X's closed entry, whose latest decision is AUTO_RELEASED, and overrides it
   to MARK_LEGITIMATE.
3. D5 runs: the version matches, the original is committed and not the caller's own, and there is
   no `resolution_id`. The owner is not contested and has no ANALYST claim.
4. OVERRIDE applies DECLINE → APPROVE by SENIOR_OVERRIDE at k + 1, with an `unblock_events` row,
   and the bank receives APPROVE.

**Why it is a MAJOR.** A stated property, repeated in §11 as a deliberate cost, is false. In effect,
a role that `resolve_contested_decision` would refuse (only a RISK_OFFICER may resolve, C:661)
overturns a risk officer's resolution of a contested payment through the API, and the bank is sent
a new final decision. The override also names a pre-resolution original (an AUTO_RELEASED row, or
an analyst decision whose state was never applied) as the thing it supersedes, so its label's
`supersedes_label_id` and the audit trail point at the wrong decision.

The owner mechanism is not at fault: the state change goes through the row lock and the table. The
defect is in which entries D5(b)'s guard covers.

**Fix (local).** Choose one:
- **Guard D5 on the owner rather than the entry.** Refuse OVERRIDE (409 `stale-alert`, or a new
  `decision-resolved`) when the owner's latest APPLIED request is a RESOLVE, for example through a
  `resolved_by_resolution_id` column on `decision_owners` that RESOLVE sets and a later state
  clears; or
- **Stamp every entry.** RESOLVE stamps `resolution_id` on **every** entry of the transaction, open
  or not.

Then add a property: "after RESOLVE, no OVERRIDE on any entry of the transaction is applied".

### 3. MINOR (origin: EDGE SPECIFICATION): RELEASE_FAILED's success has no recorded outcome, so the resolver re-runs it and records `not-failed`

**Rules:**
- §5 RELEASE_FAILED: on success, "`claim` and `deadline` restored … No state"; otherwise "REFUSED,
  recorded, reason `not-failed`" (C:374);
- §4.3 step 4: only APPLIED, LABEL_ONLY and recorded REFUSED write a request row (C:245-251);
- §4.4: outcomes are APPLIED (with a sequence), LABEL_ONLY, REFUSED or PRE_V81 (C:265-266);
- §7.2: the resolver lists resolutions "with no `transaction_requests` row for `resolve|` + their
  id" (C:674-676).

**The gap.** A successful RELEASE_FAILED is not APPLIED, because it has no state and no sequence.
It is not LABEL_ONLY or REFUSED either, so as written it writes no request row. What follows:
1. On its next run, P later, the resolver lists the resolution again.
2. `claim_decision_id` no longer names the decision, so the request is REFUSED and recorded,
   `not-failed`.
3. The permanent record of a release that succeeded therefore says it failed for `not-failed`, and
   the operator's audit trail contradicts the state.

There is a worse window. If a new CLAIM lands between the two runs and its effect also FAILS, the
second run could match a **different** decision only if the resolution named it. It does not,
because `decision_id` is on the resolution row, so that case is safe. Only the false record
remains.

**Fix.** Record RELEASE_FAILED's success as a request row: a new outcome (`RELEASED`), or LABEL_ONLY
with no label and reason `claim-released`. State its request id (`resolve|` + id, as for RESOLVE)
in §4.5's table.

### 4. MINOR (origin: EDGE SPECIFICATION): OVERRIDE on HOLD is reachable after RELEASE_FAILED

**Rules:**
- §5 OVERRIDE's HOLD row: "not reachable: an override needs a committed original whose effect has
  run, and every EFFECT ends HOLD" (C:391);
- RELEASE_FAILED: "a payment that is not held can then be overridden (D5)" (C:374).

**Interleaving.**
1. An analyst decides a held payment, and the EFFECT FAILS.
2. The operator releases the claim, and the owner is HOLD again with `claim` NONE (or ESCALATED).
3. Before the deadline, a senior overrides the entry. Its latest decision is the committed analyst
   decision, whose effect "has run" (FAILED).
4. D5 has no guard against HOLD, and OVERRIDE's guard is only `claim` ≠ ANALYST.

**The result.** The table has no cell. `DecisionState.allowed` admits HOLD → X by SENIOR_OVERRIDE
only for RESOLVE (§5.1), so a faithful implementation throws, and the senior gets a 500. No state is
harmed, which is why this is MINOR. But the table's reachability claim is false, and the outcome is
undefined.

**Fix.** Add the cell (409 `stale-alert`, or "wait for the timeout"), and make D5(b) refuse an
original whose effect is FAILED while the owner is HOLD.

### 5. MINOR (origin: EDGE SPECIFICATION): module placement of `DecisionOwner`'s callers and of the resolver

**Rules:**
- §4.3: `DecisionOwner` is "Java in the decision module …, called by every requester inside its own
  transaction" (C:253-254);
- §2's runner: the audit module's runner for the analyst module (C:103);
- §7.2: the resolver writes an audit entry in its own transaction (C:676-678).

**Evidence.**
- `backend/analyst/pom.xml` depends on `fraudshield-audit`, `-auth`, `-rules` and `-backend`, not
  on `fraudshield-decision`.
- `backend/decision/pom.xml` and `backend/notify/pom.xml` do not depend on `fraudshield-audit`.

**The gaps.**
- **D1 to D6 and the effects worker** must call `DecisionOwner` and record through the decision
  module's spool. That needs a new `analyst → decision` dependency. It adds no cycle, but §14 does
  not name it.
- **The resolver** both calls `DecisionOwner` and writes the audit chain. It must live where both
  are reachable (the analyst module or the `ingest` application), and must run on a runner that
  retries V8's 40001.
- **The recorder and the alarms** have no stated module.
- **One runtime point holds.** "Inside the caller's transaction" works only because both runners
  are built on the same data source and Spring transaction manager
  (`ingest/config/DecisionWiring.java:205-215`; `audit/config/AuditAutoConfiguration.java:52-56`).
  `DecisionOwner` must therefore use the transaction-bound connection (`JdbcTemplate` or
  `DataSourceUtils`), never `dataSource.getConnection()` as M6's JDBC adapters do. That is unstated.

**Fix.** In §14, name the `analyst → decision` dependency and a module for the resolver, the
recorder and the alarms, and require `DecisionOwner` to run on the caller's bound connection.

### 6. MINOR (origin: INHERITED FROM M6): a pre-cutover NOT_ME whose label M6 failed to spool is lost for good

**Rules:** §4.4: a PRE_V81 marker for "every NOT_ME response", as one "whose effect is complete or
moot" (C:282-285).

**Evidence.**
- M6 records a NOT_ME's FRAUD label through the spool after the response commits
  (`decision/application/DecisionTransitionService.java:158-160`).
- If that throws, `VerificationService` logs and returns (`notify/verification/VerificationService.java:250-262`).
- `UnblockReconciler` and V64 handle WAS_ME only (`UnblockReconciler.java:44-53`; `V64:12-24`).

So a NOT_ME can be stored with no label, and no alert fires. V81 then marks it, and the loss becomes
permanent.

**Why MINOR, and why inherited.**
- The loss happened under M6 before the cutover, and draft 3 does not cause it.
- M6's label ids are random (`DecisionTransitionService.java:151`), so resubmitting every NOT_ME
  would duplicate the labels that did land.
- The payment's state is right: it stays declined. What is lost is a training label.
- What draft 3 adds is only the false word "complete".

**Fix.** V81 writes the NOT_ME marker only when a FRAUD label with source CUSTOMER exists for the
transaction. Otherwise it leaves the response unmarked for a one-off CUSTOMER_NO resubmission, or
states the loss in §11 as M6's.

### 7. MINOR (origin: EDGE SPECIFICATION): backfilled APPLIED rows have no stated `request_id`

§4.4 makes `request_id` unique (C:264) and backfills an APPLIED row for every `decision_states` row
with sequence > 1 (C:275-277), without saying what its request id is. The id matters:
- **If V81 derives a backfilled CUSTOMER_VERIFICATION row's id as §4.5 does** (`verification|` +
  `verification_id`), it collides with that response's PRE_V81 marker, and V81 fails on the unique
  key.
- **If it derives a backfilled TIMEOUT's id as §4.5 does** (`hold_timeout|` + institution +
  transaction), the "one timeout per transaction" rule extends across the cutover, which is
  desirable.

**Fix.** State the backfill's request ids: TIMEOUT rows take §4.5's timeout id, and
CUSTOMER_VERIFICATION rows take `backfill|` + event id, so the marker carries `verification|`.

### 8. MINOR (origin: EDGE SPECIFICATION): no gauge for the recorder's backlog

§4.6 makes the recorder the only route by which a committed request reaches Kafka and the webhook
(C:323-328). §7.4 has no gauge for "APPLIED or LABEL_ONLY rows older than 30 s with no recording
row".
- **A persistent recording failure** goes unseen except through spool-level metrics. Examples are an
  undecodable `event_body` from a bug, or a spool that is full on every instance.
- **The poll stays right, but the bank's webhook and Kafka final decision are never sent.**

This is MINOR because the spool's lag and full-spool signals cover the common causes.

**Fix.** Add `fs_requests_unrecorded_oldest_seconds` with an alert condition.

### 9. MINOR (origin: EDGE SPECIFICATION): a second ESCALATE on a payment that is not held, after W, opens no window

**Rules:** §5 ESCALATE: from ESCALATED, "unchanged: one window covers the chain" (C:369); D6(c):
for a payment that is not held, "the window defers a customer's 'yes' until a senior has seen it, or
until W passes" (C:936-937).

**The gap.** After W has passed, the claim stays ESCALATED with a past deadline. A further
escalation, for example a senior analyst escalating to a risk officer, leaves that past deadline
unchanged. The new escalation therefore defers nothing, and D6(c)'s sentence does not hold for it.
For HOLD this cannot arise, because ESCALATE requires DB time < `deadline`.

**Fix.** From ESCALATED with a past deadline on a payment that is not held, set the deadline to DB
time + W. Otherwise, state the limitation.

---

## Review 2's findings: does draft 3's answer hold?

| # | Review 2 | Draft 3's answer | Holds? |
|---|---|---|---|
| 1 | MAJOR (edge): PRE_V81 markers abandon stranded "yes"; 23505 on `V5:93` | Markers only for complete or moot responses; `ON CONFLICT (auto_block_event_id) DO NOTHING`; P8b and §12 rewritten | **Yes.** Re-run below against every pre-cutover state. The word "complete" for NOT_ME overstates (finding 6, M6's) |
| 2 | MAJOR (M6's): reverse-order double decision | §7.3, frequency corrected | **Yes.** The statement is true; see below |
| 3 | The owner's own decision re-contests after case 5; case 1's wording | Case 1(a)/(b); (b) compares with the owner first; P8c | **Yes.** Re-run below in every order I could construct |
| 4 | Case 5's history and lost SMS | §6.1 case 5, §11, §12 | **Yes** |
| 5 | The timeout paths' failure list and lost batch | §2 per hold, any failure; §14; P19b | **Yes.** `reconcile()` cancels before it times out (`HoldTimeoutService.java:103`); a failed sweep hold is rescheduled, so nothing is lost |
| 6 | RELEASE_FAILED: no reschedule, entry, breaker, decision id | Resolver reschedules; TIMEOUT closes FAILED-and-released entries; D4(d) excludes SKIPPED and FAILED; `decision_id` on the resolution | **Yes** for the four items. New gaps on the same path: findings 3 and 4. The 409 wording for a FAILED claim is part of finding 1 |
| 7 | Asserted actor, label source, closed entries, P20 | Both identities recorded; SENIOR_OVERRIDE source; `resolution_id`; P20 | **Partly.** The actor, label and audit hold. The `resolution_id` answer does not deliver the finality it is used to claim (finding 2) |
| 8 | V64, cross-tenant listers, the runner's module | V64 rewritten; listers named; two runners | **Partly.** V64 and the listers hold. The module dependency of `DecisionOwner`'s analyst callers and the resolver's placement are unstated (finding 5) |
| 9 | The alarm bound counts the gauge | "Visible in the gauge"; alert conditions per gauge | **Yes** for the four gauges. A counter the contract relies on has no alert (finding 1) |

## The attack points, answered

1. **Paths that move state without the owner's lock and table.** None found.
   - `DecisionTransitionService` is removed. Today its only callers are `VerificationService` and
     `UnblockReconciler`; `analystDecidesHold` is wired but never called.
   - `HoldTimeoutService.timeOut`'s Redis-read path becomes a request.
   - The sink writes sequence 1 only, under levels 4 and 5. Its case-4 rewrite of
     `first_event_id` runs under the row lock.
   - The SQL functions write no state.
   - Every `decision_states` reader is moved to the view or is sequence-1-only
     (`grep`: `AccountQueries`, `JdbcDecisionStates`, `JdbcOverdueHolds`, `UnblockReconciler`,
     `CustomerSmsSender`, `JdbcIdempotency`, V62, V64).
   - The Redis writes (`DecisionService.java:246-262`, and the recorder's set-if-higher) are a cache
     and a schedule, not states.
   - `RedisHoldSchedule`'s index keeps one member per transaction (`RedisHoldSchedule.java:26-50`).
     Two `DueHold`s for one transaction, or a reschedule that leaves the old member in the sorted
     set, therefore produce duplicate TIMEOUT requests. §4.5's one-timeout request id makes every
     duplicate return the stored outcome and re-record byte-identically. Harmless.
2. **PRE_V81 against every pre-cutover state.** Before V81, M6 can produce only MODEL,
   TIMEOUT_POLICY and CUSTOMER_VERIFICATION makers, because no staff endpoint calls
   `analystDecidesHold`.
   - **Applied:** a CUSTOMER_VERIFICATION state exists, so the response is marked.
   - **Stranded,** whether by a failed transition, PB-156 starvation, or a dead-lettered
     DecisionChanged: still DECLINE by MODEL, so it is not marked. After the cutover it is submitted
     and applied once. The unblock insert does nothing on conflict, which satisfies `V5:93` and
     V5's cause and actor CHECKs.
   - **Refused by M6:** the latest state is not DECLINE, so the response is marked as moot.
   - **NOT_ME:** marked (finding 6).
   - **Expired:** the V5 trigger refuses the response, so there is nothing to mark.
   - **The reconciler and the alarm** key on the same anti-join, and a stranded answer shows in the
     deferred gauge until it is applied.
   - **A stranded answer on a transaction with no owner** (the latest is sequence 1) creates its
     owner from the visible row (§4.2).
3. **§6.1 cases against review 2's finding 3 and other orders.** Every order holds. I ran these:
   - X `DueHold` → TIMEOUT → Y (case 5) → RESOLVE → X (1(b), X is `first_event_id`): no
     re-contest;
   - the same with X draining before RESOLVE: `contested_since` unchanged, and the resolution
     stands;
   - Y drains → X's `DueHold` (the owner stands on Y; the basis differs; contest, X in conflicts) →
     X drains (1(b), already in conflicts);
   - X drains → Y (1(b) against the owner);
   - X `DueHold` → X (case 3) → Y (1(b));
   - X `DueHold` → Y same value (case 4, `first_event_id` := Y) → Y's `DueHold` (not HOLD, drop) →
     X (1(b), same value);
   - re-delivery of the kept record in each, by case 1(a), where §6.2's insert does nothing on
     conflict and the AUTO_* key is request-derived;
   - an owner created from a `DueHold` whose TIMEOUT is refused as early (JVM clock ahead), then Y
     by case 5: owner HOLD at sequence 1 and view sequence 1 = Y. That is consistent with case 5's
     stated exception, and the poll answers 503.
4. **RELEASE_FAILED and the breaker.** The entry is closed by the TIMEOUT. AUTO_* rows are not
   analyst decisions, so the one-live-decision backstop does not refuse them (`V4:60`, analyst id
   NULL). D4(d) excludes FAILED and SKIPPED. Gaps: findings 3 and 4. The held variant of a FAILED
   claim is alarmed; the unheld variant is not (finding 1).
5. **`resolution_id` and finality.** No payment or entry is left stuck by RESOLVE. A late entry
   closed by §6.2 after a RESOLVE has no decision row, so D5 answers 409. But finality is false
   (finding 2).
6. **The runner split and per-hold reschedule.**
   - Both runners share one data source and transaction manager, so a nested call joins the
     caller's transaction.
   - The decision runner retries nothing today, and §2 and §14 require 40001 and 40P01.
   - Per-hold catch and any-failure reschedule are sound. A class-08 failure whose commit in fact
     succeeded is rescheduled, then returns its stored outcome.
   - The module gaps are finding 5.
7. **The alarm conditions.** The four gauges and their conditions hold for what they cover. The
   uncovered cases are findings 1 and 8. The stated exceptions (an outage, a hold with neither
   owner nor sequence-1 row) are real.
8. **§11, §12, §15 and §16.**
   - **§11** omits findings 1, 2 and 8's consequences, and states finality, which is false.
   - **§12** is complete for the differences it intends. I found no promise of the old contract
     silently dropped.
   - **§15** is as review 2 assessed it.
   - **§16** claims 7 and 8 more fully than they hold (table above).

**§7.3's residual, re-checked against the code.**
- **The interleaving is true.**
  - The poller fires overdue members in batches of 500 (`HoldTimeoutService.java:69-84`).
  - The sinks drain in batches of 200 (`DecisionWiring.java:166-175`).
  - A retry in an outage is decided unverified (`ResilientIdempotency.java:81-105`).
  - Under §7.5 an INFLIGHT past its lease takes the claim script's existing UNCERTAIN branch
    (`RedisIdempotency.java:51-58`), so only one retry re-claims.
- **The non-outage bound is right.** The hold is 30 s (`DecisionSettings.java:20`), so Y needs a
  sink lag above about 30 − 10 − r s.
- **The never-detected and dead-letter cases are true.**

## Mechanisms checked and found sound

- **The owner row and its lock.**
  - Every request takes level 5, reads its state from the row, and looks up its request id after
    the lock.
  - DEFERRED and non-recorded refusals write nothing, so they re-evaluate afresh.
  - The one-timeout request id is never consumed by a refusal.
  - Two requests on one transaction serialise.
- **Owner creation and the first-decision lock.** Both orders of sink and creating request are
  correct at READ COMMITTED. Every HOLD gets an owner through its MEDIUM entry (§6.2), and V81
  backfills the rest.
- **The rule table's precedence.** I checked each request's order against every state §5 can
  produce:
  - contested and not-visible first;
  - TIMEOUT: contested, basis, HOLD, claim, deadline;
  - CUSTOMER_YES: claim, human decline, model or policy decline, approved;
  - EFFECT and OVERRIDE by state.

  CLAIM and TIMEOUT are exclusive on DB time. Review 8's MAJOR 3 ends declined in every order. The
  only missing cell is finding 4's, which an edge path opened.
- **The lock order and both exceptions.**
  - Exception 1's late-entry cycle is the only request-against-decide deadlock, and it is detected
    and retried.
  - A D5 on a FAILED-and-released entry racing a TIMEOUT is the same shape, and is also retried.
  - Exception 2's sink-against-sink cycle is class 40, which `Tenant.transientError` treats as
    transient (`Tenant.java:43-51`), so it is retried by the drainer.
- **§6.2's late entries.** In both orders the entry is closed exactly once, with the
  request-derived AUTO_* key.
- **§6.3 and V62's new selection.**
- **§6.5's one-transaction customer path and the reconciler's exclusion.**
- **§7.1's detection** and its "insert-only re-contest" rule, on every route in point 3.
- **§7.2's resolver for RESOLVE:** exactly once, its crash points, and `contest-changed`.
- **§7.5's lease.**
- **§8's poll order**, the webhook's supersede rule (`WebhookDispatcher.java:147-185`), and
  `CustomerSmsSender`'s "resolved" test against `currently_blocked`.
- **A3's stop-and-drain**, for spools and old-format `DueHold` members.
