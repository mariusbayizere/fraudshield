# Review 8: the analyst decision commit and undo contract (draft 8), end to end

Subject: `docs/architecture/analyst-decision-commit-contract.md` (draft 8), with ADR 0111, ADR 0113
and ADR 0067, at local commit `0b80483` (worktree `fs-wt/m8b`).

Reviewer: independent and adversarial. I wrote none of the drafts and none of reviews 1 to 7.

Method: reading only. No build, test, container or network call was made. Contract line numbers
are `C:<n>` at `0b80483`. Code paths are abbreviated: `decision/` is
`backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`, `notify/` is
`backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`, `ingest/` is
`backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/`, and `V<n>` is
`backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

**Scope, as the owner's lesson asks.** I checked the whole contract and the M6 mechanisms it rests
on, not only draft 8's diff. T8 and D7 were attacked hardest, as asked. Carried MINORs (§12 to §17)
are confirmed only where they bear on a finding.

## Verdict: UNSOUND

**MAJOR present: YES** (three; findings 1, 2 and 3).

| # | Severity | Origin |
|---|---|---|
| 1 | MAJOR | M6, review 7's MAJOR by the `DueHold` route. Draft 8's T8 states it only in part, and its "once detected" bound is false |
| 2 | MAJOR | draft 8 (T8(b): the freeze has no exit, and it turns pending effects into terminal ones) |
| 3 | MAJOR | draft 8 (D7: the senior block keys on a state that a senior's fraud decision often does not create) |
| 4 to 11 | MINOR | 4, 5, 6, 7, 8, 10 and 11: draft 8. 9: an earlier draft, not corrected as review 7 asked |

**Why UNSOUND rather than SOUND_WITH_CHANGES.**
- Finding 1 lets a payment be decided twice. In one reachable variant the double decision is never
  detected, so no alert is raised.
- Findings 2 and 3 are mechanisms draft 8 introduces, and each makes a property draft 8 states false.
- Under the owner's rule ("if a draft still produces a MAJOR, the author stops"), this draft stops.
  Finding 1's fix needs the owner, because it reopens review 7's option 3, which the owner did not
  choose. Findings 2 and 3 can be fixed in the wording. Their fixes are given below.

**The core arbiters still hold.** I re-derived H1, H5 against the sink, T2's lock and own-id lookup,
and commit against undo, all from the code. They hold as review 7 found (see "Mechanisms checked").
The MAJORs come from the sequence-1 assumption that T8 patches, and from the two rules draft 8 adds.

---

## Findings

### 1. MAJOR (origin: M6, review 7's MAJOR; draft 8's T8 leaves the `DueHold` route open, and its residual is stated too narrowly): a crash after scheduling the hold still decides a payment twice, sometimes with no detection ever

**Rules:** T8(a), T8(b) and its residual (`C:414-448`); H1's "with no row, it compares against the
hold's own deadline" (`C:191-192`); T2's timeout state "carried by the hold itself … never read
`decision_states`" (`C:287-290`); H5's "every open entry of the transaction" (`C:242-247`).

**The mechanism underneath.** `DecisionService.decide` spools the events, then saves the Redis
state, then schedules the hold, then returns for `complete` (`decision/application/DecisionService.java:240,
247, 253`; `ingest/application/IngestService.java:254-262`). The Postgres drainer polls every 5 ms
(`ingest/config/DecisionWiring.java`, `postgresDrainer`). An instance that dies between
`holds.schedule` and `complete` therefore leaves:
- a `DueHold` for its decision X, in Redis;
- X's record on its local spool, which may or may not have drained.

**Interleaving.**
1. Instance 1 decides T as **HOLD** (X). It spools, saves Redis, schedules the `DueHold` with X's
   event id (T2's new field), and dies before X drains and before `complete`.
2. After the 10 s lease, the bank's retry lands on instance 2. T8(a) verifies against PostgreSQL,
   finds nothing, and decides afresh. Say it decides **DECLINE** (Y), HIGH, with an auto-block,
   because the breaker or the freeze counter has moved. The bank is told DECLINE. Y drains within
   milliseconds. PostgreSQL's sequence 1 is now Y, with an open HIGH entry and no `hold_owners` row.
3. At X's deadline, the poller claims X's `DueHold`.
   - There is no `hold_owners` row, so H1 compares against `DueHold.deadline` and succeeds from FREE.
   - H5 closes "every open entry of the transaction". That includes **Y's HIGH entry**, which gets
     AUTO_RELEASED and an AUTO_RELEASED decision row.
   - T2 reserves sequence 2 from the `DueHold`'s HOLD, without reading `decision_states`. The new
     state is TIMEOUT_RELEASE, which supersedes HOLD.
   - T4 publishes it, and the webhook sends `decision.final` sequence 2, TIMEOUT_RELEASE
     (`notify/webhook/WebhookDispatcher.java:147-185`).

   The bank was told DECLINE, and is now told the payment was released. The view holds sequence 1
   DECLINE and then sequence 2 "TIMEOUT_RELEASE supersedes HOLD".
4. **Detection.**
   - If instance 1 restarts, X drains and the sink records the conflict, after the release.
     Draft 8's residual covers this case (`C:445-447`).
   - If instance 1's spool volume is lost (accepted as possible in §9, `C:746-750`), or X is
     dead-lettered, X never drains. **No conflict is ever recorded and no alert fires.** The
     contradiction stands in the view and at the bank for good.

**Why it is a MAJOR.**
- A payment is decided twice.
- T8's residual says "Once detected, nothing automatic acts again, and the alert names the
  transaction". That assumes detection happens. In the variant in step 4 it never does, and the
  residual does not mention it.
- H5 also closes an analyst's HIGH alert as AUTO_RELEASED for a hold that does not exist in
  PostgreSQL.

**Why the evidence is already in hand.** By the time the claim runs, Y's sequence-1 row has
usually drained, and the `DueHold` carries X's event id (T2). This is review 7's option 3, which was
not taken.

**Fix (needs the owner, because it adds review 7's option 3 to options 1 and 2).**
- **A second detector, at the claim.** In the claim's transaction, if `decision_states` holds a
  sequence-1 row for the transaction and its `event_id` differs from the `DueHold`'s, the claim
  records `decision_conflicts` (other = the `DueHold`'s event and decision), refuses, and drops the
  hold.
- **Option A is kept.** If no sequence-1 row exists, the claim proceeds as now.
- **The residual, restated.** It must name a double decision that is never detected because the
  other record never drains. It belongs with §9's spool-loss limitation.
- **A property** for the crash after `holds.schedule`.

### 2. MAJOR (origin: draft 8, T8(b)): the freeze has no exit, and it turns pending effects into terminal SKIPPED rows, so an analyst's committed decision can leave a payment held forever with no alert

**Rules:** T8(b) (`C:430-444`), T7 step 6 (`C:407-408`), D4(c) and (e) (`C:506-523`), D4(f)
(`C:532-534`), H3 (`C:230-236`).

**(a) No exit.**
- The freeze predicate is "a `decision_conflicts` row exists".
- That table is **append-only**, keyed on `(institution_id, transaction_id)`, with `ON CONFLICT DO
  NOTHING` (`C:433-436`).
- The contract represents no resolution. "Resolves the conflict by hand" is left to M9's runbook
  (`C:443-444`).

So T7's "503 … until an operator resolves it" and every freeze check can stop applying only through
a write the schema does not provide and `fs_app` is not granted. Meanwhile the open entry stays in
the queue (§6), and a decide on it gets 409.

**(b) Terminal SKIPPED.**
- T8 makes every reservation refuse a contested transaction "with outcome SKIPPED".
- The effects worker writes the effects row with that outcome (D4(e), E3). D4(a) then never selects
  the decision again.

**Interleaving.**
1. An analyst decides a MEDIUM hold. The claim makes the hold owner ANALYST, and the decision is
   committed at G.
2. Before the effects worker's E1 runs (G + P), the other first decision drains and the conflict is
   recorded. This happens when a stalled or restarted instance drains.
3. E1 refuses: SKIPPED, `contested`. The effects row is written.
4. The operator resolves the conflict, by whatever means. Then:
   - the hold is still ANALYST-owned, so H3 excludes it (`C:234`);
   - the poller's claim finds ANALYST and drops it (H2);
   - D4(a) never selects the decision again;
   - `fs_analyst_effects_oldest_pending_seconds` does not fire, because an effects row exists.

   The payment stays HOLD for ever. The only alert ever raised was the conflict alert, and it does
   not say that an analyst's committed decision was discarded.

The same applies to a committed override whose E1 falls after detection. Its effect is lost, though
no payment is held. The window is narrow, from G to G + P after a decision, but it is reachable, and
it is exactly the moment that a stalled instance's drain decides.

**Fix.**
- **Represent resolution.** Add an append-only `decision_conflict_resolutions(institution_id,
  transaction_id, chosen_event_id, resolved_by, resolved_at)`, with a SECURITY DEFINER writer for
  the operator role. "Contested" then means a conflict row with no resolution row. State what the
  chosen event means for the view and for the bank, and audit the resolution.
- **Make the contested refusal retriable, not terminal.**
  - E1 writes no effects row for a contested transaction, so the worker retries it after
    resolution.
  - Count it in its own gauge, `fs_contested_pending`, with an alert.
  - Customer unblocks already behave this way through `UnblockReconciler`.
  - State that after resolution a hold dropped by the timeout is picked up again by H3.
- **Add properties** for "a decision committed before detection has its effect after resolution"
  and "a resolution ends the 503".

### 3. MAJOR (origin: draft 8, D7): a senior's fraud decision on a payment that is already declined creates no state, so D7's block does not fire and the customer's "yes" overturns it

**Rule:** D7 (`C:593-599`): "When the latest state is DECLINE by SENIOR_OVERRIDE, a customer's 'yes' …
makes no transition". The stated property is "A senior's fraud decision blocks the customer path",
and "the customer path … cannot overturn a human who declined it".

**Evidence.**
- `DecisionState.allowed` permits no DECLINE → DECLINE transition
  (`decision/domain/DecisionState.java:131-142`).
- D4(c) records such an override as SKIPPED, "already in force" (`C:518-519`).
- The customer path exists only for auto-blocked transactions, which are HIGH and whose sequence 1
  is a DECLINE by MODEL (V5's `customer_verifications` → `auto_block_events`).

**Interleaving** (inside the link's 10 minutes, `notify/verification/VerificationService.java:36`):
1. A HIGH payment is auto-blocked, and the SMS with the link is sent.
2. Analyst A marks the alert MARK_LEGITIMATE. Under Q1 that is only a label: the block stays and no
   transition is made. It is committed at G.
3. A senior overrides it to CONFIRM_FRAUD (D5). Its E1 is DECLINE → DECLINE, so it is SKIPPED. Only
   a FRAUD label is recorded. The latest state is still DECLINE **by MODEL**.
4. The customer, or whoever holds the compromised phone, taps "yes, this was me".
   - T2 reads DECLINE by MODEL, and D7's test does not match.
   - DECLINE → APPROVE by CUSTOMER_VERIFICATION is allowed. The block is lifted, and a LEGITIMATE
     label is recorded after the senior's FRAUD label.

This is the owner's stated threat exactly: "a customer's 'yes' is exactly what a compromised
account would send".

**Where the rule does and does not fire.**
- It fires only after a lift and then a re-decline. That requires two overrides by two different
  people (D5(b) forbids overriding one's own).
- It misses the common case: one senior confirming fraud on a payment that is still blocked.
- It also misses an analyst's CONFIRM_FRAUD on a HIGH alert, which "a human who declined it" also
  covers.

**Fix.**
- Key the block on the alert's decisions, not on the state's `decided_by`. Refuse the "yes" when
  the transaction's latest decision that is neither undone nor overridden is a committed
  CONFIRM_FRAUD (by a senior, and by an analyst if the owner wants the stated asymmetry in full). A
  SENIOR_OVERRIDE with `override_decision` = CONFIRM_FRAUD counts.
- Read that inside T2. An override inserted after the read is still applied by its own E1, since
  APPROVE → DECLINE is allowed by SENIOR_OVERRIDE. So the order resolves to the senior.
- Narrow the stated asymmetry to whatever the owner chooses.
- Also see finding 10: the refusal must not leave an `unblock_events` row behind.

### 4. MINOR (origin: draft 8, T8(a)): "a claim that finds an expired lease" cannot be detected by M6's claim script, and the behaviour change for the bank is not stated

**Evidence.** The INFLIGHT claim is the whole hash with `PEXPIRE` set to the lease
(`ingest/idempotency/RedisIdempotency.java:56-57`). When the lease expires the key disappears.
The next claim sees `state == nil`, the same as a first submission, and returns `CLAIMED` with
`verify` only when the institution's window is open (`:42-58`). T8(a) and §8 (`C:663`) require the
claim to tell the two cases apart, and specify no change that would make that possible.

**What the implementation must decide.** The record has to outlive the lease, for example with a
`lease_until` field and a 24 h TTL. The INFLIGHT branch must then treat `lease_until < now` as
UNCERTAIN, not as InFlight. Otherwise the bank's retry waits 1 s and gets 503 for the record's whole
life (`ingest/application/IngestService.java:214-217`). That would be a real change to ADR 0067's
behaviour.

**What happens to the bank, if done as `ResilientIdempotency.verify` already does it**
(`ingest/idempotency/ResilientIdempotency.java:81-105`):
- **No new 503s.** The retry gets a Replay from PostgreSQL, or decides. It gets InFlight, and so 503,
  only if `transaction_ids` exists without its sequence-1 row (`JdbcIdempotency.java:242-249`).
- **Up to about 3 s added** to such a retry (ADR 0067 point 8).
- **If PostgreSQL cannot answer,** the retry is still "decided unverified" (point 6), and falls to
  T8(b).
- **A first attempt that is slow, not dead,** past 10 s, is also treated as UNCERTAIN. A retry that
  verifies before the first attempt drains still decides afresh, and only T8(b) catches it.

T8 states none of this. **ADR 0067 points 3 and 4, which "accept" double deciding, are not amended,**
and no ADR records T8.

**Fix.** Specify the tombstone and the INFLIGHT-expired branch. State that the bank sees no new 503s
and some added latency. Amend ADR 0067, or record T8 in an ADR.

### 5. MINOR (origin: draft 8, T8(b)): "another decision" is undefined; the sink compares event ids, not decisions

**Evidence.** `PostgresSink.sameFirstDecision` compares `event_id` (`decision/adapter/jdbc/PostgresSink.java:349-357`),
and every decision draws a random one (`DecisionState.initial`). T8(b) says "holds another decision"
and stores `other_decision`.

**The two readings.**
- **Conflict by event id.** Every duplicate is frozen, including the case ADR 0067 point 4 already
  makes consistent, where both decisions are the same. Each such HOLD then waits for an operator.
- **Conflict by value.** A same-value duplicate passes, but it is not identical. Two HOLDs carry
  different `review_deadline_at` values (the bank was told one, and `hold_owners` holds the other's,
  at least the lease apart), and possibly different timeout policies if the configuration changed in
  between. The poller reads the `DueHold`'s policy, while the sweep reads `decision_states`.

**When both drain in one batch.** The sink handles it correctly: the second `INSERT … ON CONFLICT`
sees the batch's own uncommitted row. Two sinks racing are also handled correctly at READ COMMITTED.

**Fix.** Define a conflict as differing `(decision, review_deadline_at, timeout policy)`. State the
same-value case and its deadline skew.

### 6. MINOR (origin: draft 8, T8(b)): the freeze list omits paths that still act after detection

`C:437-442` freezes the claim, the sweep, reservations, the analyst writes and the poll. These still
act after detection:
- **The auto-block SMS and its link.** `CustomerSmsSender`'s CLASSIFY does not look at conflicts
  (`notify/sms/CustomerSmsSender.java:60-76`).
- **Webhook deliveries already PENDING.** They keep being retried and delivered
  (`WebhookDispatcher`).
- **T4 and T5 recording a reservation made before detection.** It is published to Kafka and the
  webhook after detection. The webhook then says TIMEOUT_RELEASE while the poll says 503.
- **E2's breaker count** for a decision committed before detection.
- **Kafka.** Both sequence-1 decisions are published on `fs.decisions.final`
  (`decision/adapter/events/KafkaMessages.java:104-124`), and nothing retracts either. This is M6's.

Separately, the customer's page answers APPLIED when the refusal arrives as
`TransitionRefusedException` (`VerificationService.java:251-264`).

So "nothing automatic acts again" (`C:447`) is too strong. **Fix.** List each of these as frozen or as
an accepted residual. Recording a reservation made before detection probably should continue: the
state is in the view either way.

### 7. MINOR (origin: draft 8, T8(b)): detection and the freeze checks are not serialised

The sink inserts the conflict row without taking any lock the freeze checks take. A claim or a T2
reservation that read "not contested" before the sink commits proceeds and commits afterwards. This
is benign as the residual frames it, but "once detected" needs defining. **Fix.** Either the sink
takes T2's transition advisory lock (level 5, after its level-4 `hold_owners` row, so the order is
kept) before inserting the conflict, or the contract defines detection as that commit and states the
boundary.

### 8. MINOR (origin: draft 8, T8(b) with M6's listers): contested rows can starve the sweep and the reconciler

**The two listers.**
- H3's lister selects by deadline, oldest first, with a limit (`decision/adapter/jdbc/JdbcOverdueHolds.java:28-40`,
  BATCH 500).
- `UnblockReconciler` selects `ORDER BY unblocked_at LIMIT 100` (`notify/verification/UnblockReconciler.java:44-53`).

**Why they starve.** If the refusal happens after selection, a contested HOLD or unblock is selected
again on every run, ahead of newer rows. This is PB-156's starvation, with a new permanent cause.
**Fix.** Exclude contested transactions in the V62 and V64 listers and in the base-table queries.

### 9. MINOR (origin: earlier draft, not corrected as review 7 asked): T7 step 2 still says "Redis is right then" without condition, and the order of step 6 against step 3 is unset

`C:389-392` keeps the unconditional claim that review 7 (finding 1, last paragraph) asked to
remove. Before detection, with two different first decisions, the poll can still move from Redis's
answer to the view's. Step 6 needs PostgreSQL to know a transaction is contested. During an outage,
step 3 answers from Redis, so a contested transaction is answered. **Fix.** Qualify step 2 with T8,
and say that step 3 can answer a contested transaction while PostgreSQL is unavailable.

### 10. MINOR (origin: draft 8, D7 and T8 over M6's `VerificationService`): a refused "yes" still writes `unblock_events`

**Evidence.** `VerificationService.answer` commits the response **and** an `unblock_events` row
(cause CUSTOMER_VERIFICATION) before it calls `customerAnswered` (`VerificationService.java:212-262`).

**Where this matters.**
- **D7's re-decline case.** There the insert conflicts with the earlier lift's row (V5's unique
  `auto_block_event_id`), so nothing is written. Fine.
- **A contested transaction, or finding 3's fix.** There the row *is* written, and the transition
  is refused. Then:
  - `v_auto_block_status.unblocked_at` (V5:124-136) shows the block lifted while the state is
    DECLINE;
  - CLASSIFY counts the SMS as resolved;
  - `UnblockReconciler` re-selects the row on every tick (finding 8).

D7's "the response itself is stored, as every response is" leaves this out.

**Fix.** Make the decision in the same transaction as the response, or write the unblock row only
when T2 reserves.

### 11. MINOR (origin: draft 8): T8 and D7 are missing from §3, §7 and §1

- **§3.** `decision_conflicts` is not in the V81 schema section: its tenant isolation, its grants,
  and the lister for the operator.
- **§7.** No property covers T8(a), T8(b), the contested 503 or D7.
- **§1.** The Sources list does not name `RedisIdempotency`, `ResilientIdempotency` or
  `IngestService`.

**Fix.** Add them: at least P22 (an expired lease is verified), P23 (a contested transaction is
refused by every path in T8's list) and P24 (finding 3's case).

---

## Carried findings re-checked

- **Review 3's m4 and m12, and §2's runner.** `TenantTransactions.inTenant` still retries only
  40001 (`backend/audit/.../TenantTransactions.java:26`). §8 lists the change (`C:661-662`). Until
  P2 makes it, §13's "the runner retries the loser" is false of the code. Still carried, still
  load-bearing.
- **PB-156.** Confirmed unchanged (`UnblockReconciler.java:44-53, 102-104`). Finding 8 adds a cause.
- **Review 7's finding 6, case (a).** Answered for the re-decline case by V5's unique
  `auto_block_event_id`. See finding 10 for the case it does not cover.

## Mechanisms checked and found sound

- **H1 at READ COMMITTED.** Every order of the analyst's and the timeout's upserts was checked,
  including D1(e)'s deadline read before the claim: the upsert's WHERE closes the gap.
- **H5 against the sink.** Both orders of the `hold_owners` row lock were traced through
  `PostgresSink`'s one-transaction batch (`PostgresSink.java:187-217`): no entry is left open, and
  there is exactly one AUTO_* row. (Finding 1's case is a claim that has no sink row at all.)
- **T2.** The lock and the own-id lookup mean that no two writers reserve one sequence and no
  re-run reserves twice. T3's ids now name the institution (review 7's finding 2 is answered).
- **Commit against undo.** V4's `forbid_commit_and_undo`, with §3.2 and §3.3's DB-time triggers,
  holds. The reversal of lock levels is m4's and is retried once the runner retries 40P01.
- **T6 and T7's core.** Every later state commits before it is published, so the view is never
  behind Kafka or the webhook. T7 steps 3 to 5 (unavailability defined, 503 instead of 404) match
  review 7's findings 3 and 4.
- **The sink's skip path.** It detects a duplicate whether the two records drain in one batch or
  from two sinks racing: the `ON CONFLICT` waits and then re-reads. An SMS intent of a dropped
  decision is refused by CLASSIFY's "kept" test, because the auto-block ids are derived from the
  submission (`DecisionService.java` `derivedId`; `CustomerSmsSender.java:60-76`).
- **The webhook.** Sequence 1 is never sent. A lower or equal sequence is superseded, and event ids
  are deduplicated (`WebhookDispatcher.java:37-51, 147-185`).
- **D7's "yes" after an approval.** It makes no transition and writes no second unblock row: the
  unique `auto_block_event_id` (V5) and T2's read cover it.
- **The `UnblockReconciler` against the 10-minute link.** A "yes" whose transition failed is applied
  later under T2. If a senior re-declined in between, D7 refuses it. Its row exists only when no
  override lifted first, so the senior re-decline case adds no permanent row to the reconciler. The
  contested case and finding 3's fix do (findings 8 and 10).
- **D4(e′) and D4(f).** These are corrected: Redis now gates only E2.
- **D6 against a timeout.** It is serialised through the `hold_owners` row, and an escalation that
  loses gets 409.
- **§6 against P1's queries.** Unchanged since review 7, and consistent.
