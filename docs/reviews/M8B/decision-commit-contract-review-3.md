# Review 3: the analyst decision commit and undo contract (draft 3)

Subject: `docs/architecture/analyst-decision-commit-contract.md` at `8424499` (branch `m8b/analyst-api`).
Reviewer: independent. I wrote none of the work, neither of the earlier reviews, and none of the
drafts. Method: reading only. No build, test or container was run. Contract line numbers (`C:<n>`)
and code line numbers are at `8424499`. OpenAPI lines are from
`git show 8424499:contracts/openapi/fraudshield-api.yaml`.

## Verdict: SOUND_WITH_CHANGES

3 MAJOR, 9 MINOR. The three MAJORs must be fixed before code is written against the contract. Each
has a local fix, and none of them reopens the double-decision question.

- **The core still holds.** I found no interleaving in which both the analyst and a timeout decide a
  held transaction, and none in which two writers reserve one sequence. T2's "own event id first"
  closes review 2's MAJOR 1. The recorder (T5) gives every stranded reservation an owner.
- **MAJOR 1. A new mechanism leaves entries open for ever.** H5 moves entry closing into the claim
  transaction. T2 lets a timeout take sequence 1 from Redis. Together they let a timeout commit
  before the transaction's alert entry reaches PostgreSQL. The sink then inserts that entry as
  PENDING, and nothing closes it. P13 and P15 are false after any drain backlog longer than the
  30 s review window, for example after a PostgreSQL outage.
- **MAJOR 2. A3's rollout claim is false, and the V80 backfill is a one-shot snapshot.**
  - Writers on the old release, and old spool records drained after V80, add `decision_states`
    rows with sequence > 1 and no reservation. T2 ignores those rows, so it reserves their sequence
    again. That doubles a customer transition and its label.
  - The backfilled rows have no recording row, so T5 re-records the whole history.
- **MAJOR 3. D4(c)'s `unblock_events` write cannot succeed in two reachable cases:**
  - an override of a `DECLINE_AND_VERIFY` timeout, which has no auto-block row;
  - a second lift of the same block, which the UNIQUE key refuses.

  The reservation transaction then fails on every run. The override's transition is never made,
  and nothing says the worker skips past the failure.

## Review 2's findings

| # | Review 2 finding | Status | Why |
|---|---|---|---|
| N1 | MAJOR: re-record not idempotent on the customer path; stranded reservations unowned | RESOLVED | (a) the customer's label id is now derived (`unblock_label|` + `unblock_events.id`, C:218). (b) `UnblockReconciler` selects `u.id` (C:221, §8 C:381). (c) T2 looks up its own event id before reading the latest (C:198-199), so N1(c)'s 23505 on `event_id` and the resulting `return` (`UnblockReconciler.java:104-106`) can no longer happen. (d) H3 now tests both the reservation and `decision_states` (C:169-170), and T5's recorder (C:231-233) completes a stranded timeout, so the sweep need not be the recorder of last resort. Residues are new findings 2 and 9. |
| N2 | MAJOR: entries left open under DECLINE_AND_VERIFY | PARTLY | Closed for the normal ordering. H5 (C:179-187) closes entries under both policies, adds `AUTO_DECLINED` (C:128-129), and stops the sink closing entries. But the new placement opens a different "open forever" path (MAJOR 1), so P13 is still false. |
| N3 | AUTO_* rows in D4; overriding ESCALATE | RESOLVED | D4(a) excludes ESCALATE and AUTO_* (C:285-286). D4(b) waits only on originals that have effects (C:287-288). D5(b) refuses ESCALATE (C:310-312). P14 and P16 test this. The widened override scope is not declared against the API (finding 10). |
| N4 | sequence 1 not visible to the timeout | PARTLY | The verification-row wording is corrected (C:207). But the chosen answer, "a timeout may take sequence 1 from Redis" (C:204-205), is the cause of MAJOR 1. "The writer retries later" (C:209) does not say what happens to the claim, which shares the transaction, or to the hold that `claimDue` already removed (`RedisHoldSchedule`; `ResilientPorts.java:221-227`). |
| N5 | the poller's JVM clock | RESOLVED | The claim is refused while DB time < `DueHold.deadline` (C:150-152). `DueHold.deadline` and the entry's `review_deadline_at` are both `outcome.reviewDeadlineAt()` (`DecisionService.java:232,259`), so the equality C:151 asserts holds. A fast leader clock now causes only a refused claim and a reschedule. |
| N6 | the PostgreSQL outage | PARTLY | The ADR 0064 amendment, the drain rate (500 a tick), and lateness measured from the original deadline are stated (C:161-165; Q3). Two things are still missing. "The poller keeps its lease while it reschedules" (C:163) is asserted with no mechanism: each claim waits on a pool timeout, and nothing renews the 2 s lease. Review 2's "skip claiming while the PostgreSQL breaker is open" was not adopted. Finding 11. |
| N7 | lock order violated | PARTLY | The three cases review 2 named are fixed: D1 takes the entry advisory lock explicitly (C:241), there is one trigger function per table (C:84-85), and the effects worker takes no lock (C:283-284). But H2/H5 and D3 now reverse the order (finding 4). |
| N8 | idempotency gaps | RESOLVED | (a) A replay returns the original outcome, and §9 states the cost (C:243-245, C:398). (b) The override and escalate replay bodies are stated (C:308-309, C:320-321). (c) A unique violation from the cross-entry key re-runs D1 (C:247-248), but the text cites step (g) where it means (f) (finding 12). (d) The 409 and 503 declarations are listed for P2 (C:400-403). |
| N9 | reservation body; three policy sources | PARTLY | `event_body` (C:111-113) holds what a re-record needs, except the label's `availableAt` (finding 6). The policy has no single source yet (finding 8). The body references `decision_states.timeout_policy` (C:270, C:384), but §3 never adds that column to V3's append-only table. `TransactionDecided` carries only `thresholdsVersion` (`DecisionEvent.java:43-50`). H2 and H3 never say where they read the policy. |
| N10 | override unblock; ANOMALY; reconciler starvation | PARTLY | Q1 states the ANOMALY asymmetry (C:406-408). D4(c) now writes `unblock_events` (C:295-296), but as written that write fails (MAJOR 3). Re-selection and starvation are deferred to PB-156. That is acceptable only if the reconciler's reservation check also matches backfilled rows (MAJOR 2). |
| N11 | undo retry, timeouts, undo replay | RESOLVED | §2 sets the timeouts (C:64-67). D2(b) reschedules on a replay (C:262-263). §9 states the retry cost (C:395-396). |
| N12 | properties and text | PARTLY | P1, P7, P8 and P12 are reworded, P16 and P17 are added, the V80 backfill is stated, and the §-numbers and the V71 wording are fixed. Still missing are properties for: sequence 1 not drained when the timeout fires; no timeout while PostgreSQL is down; a replay after an undo; and a fault point per writer. §8 still omits the runner's 40P01 retry, `VerificationService`, and the V62/V64 listers (finding 12). |

## New findings

### 1. MAJOR: a timeout that commits before its alert entry reaches PostgreSQL leaves that entry open for ever

**Rule:** H2 (C:153-157), H5 (C:179-187), T2's Redis exception (C:204-205), D1(d,e) (C:250-254),
D6(c) (C:324-326), P13 (C:366), P15 (C:368), §6 (C:338-342).

**Why it can happen:**
- **Ingest does not wait for PostgreSQL.** It appends `[TransactionDecided, AlertRaised]` to the
  ingest instance's own spool (`DecisionService.java:240`), then schedules the hold in Redis
  (`:253-259`). Each instance drains its own spool to PostgreSQL (`DecisionWiring.java:160-176`). A
  timeout's record goes to the leader's spool.
- **The alert row arrives late if the drain lags.** The alert row is inserted by that drain with the
  default status `PENDING` (`PostgresSink.java:95-100, 373-386`; `V4:10`).
- **Nothing closes it any more.** Draft 3 removes the sink's closing UPDATE (C:185;
  `PostgresSink.java:444-448` today).
- **The timeout no longer waits for PostgreSQL's sequence 1.** T2 lets it read sequence 1 from Redis,
  where it was saved at ingest (`DecisionService.java:247`). Without the Redis exception, the
  timeout would have to wait for sequence 1, which commits in the same sink transaction as the alert
  row. With it, the claim can commit while the alert row does not exist yet.

**Interleaving:**
1. PostgreSQL is unavailable for 60 s. Instance A ingests a MEDIUM transaction X: the spool record
   is durable, the HOLD is answered, and the hold is scheduled in Redis with a 30 s deadline.
2. The poller's claims fail and reschedule 1 s later (H2, C:159-160).
3. PostgreSQL returns. In the first 100 ms tick the leader B claims X:
   - DB time ≥ deadline, and `hold_owners` is FREE, so the claim becomes TIMEOUT;
   - "closes the transaction's open entries" updates nothing, because A has not drained X yet;
   - no AUTO_* row is written, because no entry exists;
   - T2 finds no reservation and no PostgreSQL sequence 1, takes sequence 1 from Redis, and
     reserves sequence 2.

   The transaction commits, and B records sequence 2.
4. A's drain then writes X's sequence 1 and its alert row, as `PENDING`.
5. Every later path leaves the entry where it is:
   - D1 on it: open, so (d) passes; DB time ≥ deadline, so (e) answers 409
     `review-deadline-passed`, for ever;
   - D6: the owner is TIMEOUT, so (c) answers 409, for ever;
   - H3 excludes X, because it has a reservation;
   - a second claim (TIMEOUT → TIMEOUT) would close the entry, but nothing schedules one.
6. Result: the standard queue lists X for ever (§6), with no AUTO_* decision row. P13 and P15 are
   false.

**A narrower window exists even without the Redis exception.** Under READ COMMITTED each statement
takes a new snapshot. If H5's UPDATE runs before the sink commits, and T2's read of sequence 1 runs
after, the same outcome follows.

**Reach:** this happens to every MEDIUM transaction ingested during a PostgreSQL outage longer than
30 s, or whenever any instance's PostgreSQL drain lags by more than 30 s. A held-back drain is
exactly what the 1 s reschedules produce on recovery.

**Fix:**
- Drop T2's Redis exception for the timeout.
- In the claim transaction, read the PostgreSQL sequence 1 **first**, in its own statement, before
  closing entries. Sequence 1 and the alert row commit in one sink transaction (`PostgresSink`
  `write`, `:239-273`), so once sequence 1 is visible, the entry is visible to every later
  statement.
- If sequence 1 is absent, roll the whole claim back and reschedule the hold one tick later. The
  hold stays FREE, so H3 acts if the reschedule is lost.
- As a backstop, make the sink's alert insert lock `hold_owners` (`INSERT … ON CONFLICT DO NOTHING`,
  then `FOR SHARE`). When the owner is TIMEOUT, insert the entry already closed, with its AUTO_* row.
- Add a property: a timeout whose alert has not drained leaves no open entry.

### 2. MAJOR: A3 is false, and the V80 backfill is a one-shot snapshot of a moving table

**Rule:** A3 (C:70-72), §3.6 backfill and recordings (C:116-119), T2 step 3 (C:200), T5 (C:231-233),
§8 `UnblockReconciler` (C:381).

**(a) Writers that do not reserve keep running after V80.**
- **Only the analyst operations are gated.** A3 gates the analyst operations, but the timeout poller,
  the sweep, `VerificationService` and `UnblockReconciler` all exist in M6 and run on both releases.
- **During a rolling deploy:**
  - an old leader times out holds through Redis alone (`HoldTimeoutService.java:117-127`);
  - an old instance applies customer answers through `customerAnswered`
    (`DecisionTransitionService.java:145-170`).

  Neither reserves.
- **Even with a stop-all deploy:** the durable spool survives the restart, and records the old
  release appended are drained by the new release *after* V80 ran.
- **What T2 then does.** Every such record adds a `decision_states` row with sequence > 1 that has
  no reservation. T2 step 3 reads "the highest reservation, or else the sequence-1 state", so it
  never sees these rows.

**Interleaving (customer path):**
1. An old-release instance accepts a customer "Yes". `unblock_events` is committed, and sequence 2
   APPROVE is recorded with a random event id and a random label id.
2. Rollout: V80 runs before that record drains, or concurrently with it.
3. The new `UnblockReconciler` (10 s grace) finds the `unblock_events` row with no reservation. It
   calls `customerAnswered`. T2 finds no reservation, reads sequence 1 (DECLINE), and reserves
   sequence 2 APPROVE with `unblock|u.id`.
4. Both sequence-2 events reach Kafka. `decision_states` keeps whichever arrives first, because
   `INSERT … ON CONFLICT DO NOTHING` matches either key (`PostgresSink.java:88-93`).
5. `label_events` keeps **two** CUSTOMER LEGITIMATE labels, because their ids differ. The label is
   doubled.

The same happens with an old timeout plus the new sweep, whose H3 tests only the reservation and an
*already drained* `decision_states` row.

**(b) The backfilled rows have no recording row.** §3.6 does not backfill
`transaction_transition_recordings`.
- **The re-record.** T5 lists "reservations older than 30 s that have no recording row", so on its
  first run it re-records every historical transition.
- **Bodies the history cannot supply.** It re-records from `event_body`, which the backfill cannot
  fill: `decision_states` has no channel, no actor and no label (`V3:95-115`). If the backfill
  synthesises a label with a fresh id, every historical label is doubled. If it leaves the label
  out, T4's "byte-identical" claim is false for those rows.

**(c) Historical customer unblocks never match the reconciler's check.**
- **The mismatch.** "checks for a reservation" (C:381), read as T3's id `unblock|u.id`, never
  matches a backfilled customer transition, whose id is the old random one.
- **What follows.** Every historical `CUSTOMER_VERIFICATION` unblock is therefore re-selected. Each
  is refused (latest is APPROVE), and the next tick selects it again.
- **Starvation.** The query is `ORDER BY unblocked_at LIMIT 100` (`UnblockReconciler.java:44-53`). In
  any deployment with 100 or more historical unblocks, the sweep is starved from the first tick, and
  no stranded customer unblock is ever applied again.

**Fix:**
- State a cutover. Stop every M6 transition writer on the old release, drain every spool to
  PostgreSQL, then run V80, then start the reserving writers. Or keep the new release's writers in
  M6 mode until a cutover flag is set after the drain.
- Belt and braces: make T2's "latest" the higher of the highest reservation and the highest
  `decision_states` sequence. Treat a `decision_states` row with no reservation as reserved.
- Have V80 insert a recording row for every backfilled reservation. Allow a backfilled
  `event_body` to be marked "historical, never re-recorded".
- Specify the reconciler's check as "any reservation or `decision_states` row by
  CUSTOMER_VERIFICATION for the transaction", not the event id. Update V64's lister to match.

### 3. MAJOR: D4(c)'s `unblock_events` write fails for a timeout decline and for a second lift, and stalls the override

**Rule:** D4(c) (C:292-296), D5(b) (C:310-312), P17 (C:371).

D4(c) writes the `unblock_events` row, cause SENIOR_OVERRIDE, "in the reservation's transaction",
for "an override that lifts an auto-block (DECLINE → APPROVE)". `unblock_events.auto_block_event_id`
is `NOT NULL UNIQUE`, with an FK to `auto_block_events` (`V5:93,98`).

- **(a) No auto-block row.**
  - Under `DECLINE_AND_VERIFY` a timeout yields DECLINE by TIMEOUT_POLICY (`HoldTimeout.java:32-37`),
    and the timeout records only a `DecisionChanged` (`HoldTimeoutService.java:124`), so no
    auto-block row exists.
  - D5(b) admits overriding AUTO_BLOCKED, and DECLINE → APPROVE by SENIOR_OVERRIDE is allowed
    (`DecisionState.java:177-179`).
  - The write has no `auto_block_event_id` to give: 23502 or 23503.
- **(b) A second lift.** The steps:
  1. A HIGH alert is auto-blocked.
  2. The customer answers "Yes", which writes `unblock_events` (CUSTOMER_VERIFICATION) and
     sequence 2 APPROVE.
  3. Risk officer R1 overrides the analyst's committed decision to CONFIRM_FRAUD: sequence 3,
     APPROVE → DECLINE.
  4. Risk officer R2 overrides R1's override to MARK_LEGITIMATE. D5(b) allows overriding a
     SENIOR_OVERRIDE. That is sequence 4, DECLINE → APPROVE, and D4(c) inserts a second
     `unblock_events` row for the same block: 23505.
- **(c) Consequence.**
  - The reservation transaction rolls back on every run, so the override's transition and label are
    never recorded, and its effects row is never written. That loses an effect.
  - D4(b) also blocks any later override of this override.
  - D4 says "oldest commit first" and nothing about skipping a decision that fails permanently.
    Implemented like `UnblockReconciler` (stop at the first failure), it would stop every
    institution's effects.
- **Related.** After step 3 there is no "re-block" fact. `v_auto_block_status` (`V5:124-140`) keeps
  showing the account unblocked while the decision is DECLINE.

**Fix:**
- Write the row only when an auto-block row exists, and use `ON CONFLICT (auto_block_event_id) DO
  NOTHING`, as `VerificationService`'s UNBLOCK already does (`VerificationService.java:59-63`).
- Say that a lift with no auto-block (a timeout decline) changes only the decision state.
- Say that the effects worker records a non-transient failure, counts it, and moves on to the next
  decision.
- Either add a re-block fact, or state that `v_auto_block_status` does not reflect an override that
  declines again.
- Extend P17 to both cases.

### 4. MINOR: the claim transaction and the committer reverse the stated lock order

**Rule:** §2 (C:74-85): "may skip levels but never reverses them". H2/H5 (C:153-156, 179-183), D3(a)
(C:276-279), D1(a,e) (C:241, 252-254), D6(a,c).

- **The claim.** It upserts `hold_owners` (level 4), then updates the transaction's entries
  (level 1), then inserts the AUTO_* row, whose §3.4 trigger takes the entry advisory lock (level 2).
  - **The cycle.** D1 and D6 hold level 1 and then 2, and request level 4.
  - **Interleaving:** D1 locks entry E at deadline − 1 ms and passes (e). The claim at the deadline
    upserts TIMEOUT and waits on E's row lock. D1's H1 upsert waits on the claim's `hold_owners`
    row. The result is a 40P01.
- **The committer.** It try-locks the decision (level 3), then inserts the commit row, whose trigger
  "takes levels 2 then 3" (C:84-85). That is level 3 and then level 2.
  - **The cycle.** D2 holds level 2 (C:260) and then requests level 3 through the undo trigger.
- **Why this is MINOR.** Both cycles are detected by PostgreSQL and retried (§2's runner), so no
  deadlock goes unretried. Neither yields a double decision.
- **The cost.** D1, when it is the victim, retries past the deadline and gets 409 although it was
  in time. §2's sentence and the reasoning behind P11 are wrong as written.
- **Fix:**
  - In the claim: lock the transaction's entry rows (`FOR UPDATE`, by id order), then the entry
    advisory lock, before the `hold_owners` upsert.
  - In D3: take the entry advisory lock before the decision try-lock, or have the commit trigger take
    only level 3.

### 5. MINOR: whether an override's label is recorded when its transition is SKIPPED is unstated

**Rule:** D4(c) (C:292-299).

- **The case.** "A transition that is not allowed, or is already in force: outcome SKIPPED". Example:
  an analyst marks a HIGH alert legitimate (label only, block kept, Q1), and a risk officer
  overrides to CONFIRM_FRAUD. DECLINE is already in force, so the outcome is SKIPPED.
- **The risk.** If SKIPPED also drops the SENIOR_OVERRIDE FRAUD label (whose
  `supersedes_label_id` points at the analyst's label), retraining keeps the overturned LEGITIMATE
  label.
- **Fix:** say the override's label is always recorded, with a derived id, whatever the transition
  outcome. SKIPPED then refers to the transition only.

### 6. MINOR: "receivers ignore repeated event ids" is not true of the label consumer, and the body lacks `availableAt`

**Rule:** T4 (C:225-229), §3.6 (C:111-113), T5.

- **(a) The label consumer does not deduplicate.**
  - **What it does.** The only `fs.labels` consumer in the repository
    (`ml/src/fraudshield_ml/featurestore/ingest.py:43-66`) applies each event. The store replaces
    a transaction's outcome by arrival, with no sequence or time comparison
    (`featurestore/store.py:334-357`).
  - **Why late recording matters.** T5 records a stranded reservation 30 s or more late, possibly
    after a later transition.
  - **Example:** a customer LEGITIMATE label (sequence 2, stranded) is recorded after an override's
    FRAUD label (sequence 3). The store ends at LEGITIMATE.

  The PostgreSQL tables, the webhook queue (`WebhookDispatcher.java:37-51`: `event_id` unique, and
  an older sequence is SUPERSEDED) and audit receipts (`V72`) are all genuinely idempotent. The
  label consumer is not deployed yet (ingest.py:7-8), which is why this is MINOR.
- **(b) `availableAt` is not in the body.** `LabelRecorded.availableAt` becomes
  `label_available_at` in both sinks (`PostgresSink.java:452-463`; `KafkaMessages.java:527-535`).
  It is not in §3.6's label fields. Recomputed at re-record, it breaks "byte-identical", and the
  first arrival wins.
- **Fix:**
  - Store `availableAt` in the body; it equals `decided_at`. Do the same for the label-only path,
    which has no reservation.
  - Require `fs.labels` consumers to keep the label with the latest `label_available_at`, or
    honour `supersedes_label_id`.
  - Narrow T4's sentence to the receivers that actually deduplicate.

### 7. MINOR: what a timeout does when sequence 1 is not visible is unspecified

**Rule:** T2 (C:209), H2 (C:148-160).

- **The gap.** "If neither is visible, the writer retries later". The claim, the entry closing and
  the reservation share one transaction, and `claimDue` has already removed the hold from Redis, or
  from the local map (`ResilientPorts.java:221-227`). The text does not say whether the claim rolls
  back, nor who reschedules.
- **Current backstop.** The hold is caught only by H3, 10 to 15 s late, and only if the claim rolled
  back.
- **Fix:** subsumed by finding 1's fix (roll back, reschedule one tick later). Add the property that
  review 2 asked for.

### 8. MINOR: the timeout policy still has more than one source, and its column is not in the schema

**Rule:** D2(e) (C:269-270), §8 (C:384), H2, H3.

- `decision_states.timeout_policy` is used but never added: §3 has no V80 `ALTER` of V3's
  append-only table (`V3:95-115`).
- The sink cannot write it: `TransactionDecided` carries `thresholdsVersion`, not the policy
  (`DecisionEvent.java:43-50`).
- The poller still uses `DueHold.policy`, which comes from the in-memory thresholds at ingest
  (`DecisionService.java:253-259`). The sweep still looks it up by `effective_at <= decided_at`
  (`JdbcOverdueHolds.java:31-34`).
- These sources can differ around a configuration change. §11's "one origin" (C:434) is not in the
  body.
- **Fix:**
  - Add the column in §3, with a backfill.
  - Add the policy to `TransactionDecided`.
  - Have the claim read it from sequence 1 in PostgreSQL, which finding 1's fix requires anyway.
  - List `JdbcOverdueHolds`' policy change in §8.

### 9. MINOR: loose ends on the customer path

- **`VerificationService` is not in §8.** It must obtain `u.id`, but its UNBLOCK is `ON CONFLICT DO
  NOTHING` (`VerificationService.java:59-63, 232-238`).
  - **The gap.** When an override has already written the block's row, the customer has no row of
    their own. The contract must say that the customer's answer then records only the label, and
    which label id it uses.
- **"The verification response's id" (C:219) does not exist.** `customer_verification_responses`
  is keyed by `verification_id` (`V5:65-67`). Say `verification_id`.
- **The customer "No" label has no recorder of last resort.**
  - **Why it can be lost.** It is not a reservation, `UnblockReconciler` handles only WAS_ME
    (`:49`), and `answer` swallows the failure (`VerificationService.java:250-262`).
  - **The rule.** State this as M6's, as PB-156 does for re-selection, or give "No" a pending fact.

### 10. MINOR: D5(b) widens the operation beyond its published description

- **What the contract allows.** D5(b) lets AUTO_RELEASED, AUTO_BLOCKED and SENIOR_OVERRIDE be
  overridden.
- **What the API says.** `overrideAlertDecision` reads "Senior override of an analyst decision"
  (OpenAPI :1011), and its rule says "Only a COMMITTED decision can be overridden; a PENDING_COMMIT
  one is still undoable by its author" (:1014-1016).
- **The gap.** §9's list of contract additions (C:400-403) does not include the widening.
- **Fix:** add it to the P2 contract additions, with the 409 for ESCALATE.

### 11. MINOR: the PostgreSQL-outage behaviour still lacks a lease mechanism and a breaker short-circuit

**Rule:** H2 (C:159-165).

- **The churn.** With PostgreSQL down, each 100 ms tick takes up to 500 holds a loop. Each claim
  waits for a pool timeout, and is then rescheduled.
- **The lease.** "Keeps its lease" names no renewal, and the lease is 2 s.
- **Fix:**
  - Skip the claims, and reschedule without trying, while the PostgreSQL breaker is open.
  - Renew the lease inside the loop.

### 12. MINOR: text, §8 and properties

- **The runner.** §2 makes the runner retry 40P01. The audit `TenantTransactions` retries only
  40001 (`TenantTransactions.java:26, 68-73`). List the change in §8.
- **D1(b).** The text says "the insert at (g)". The insert is (f), and (g) is the commit (C:247,
  255-257).
- **V4's trigger.** "One trigger function per table" (C:84) implies V80 drops V4's
  `alert_decision_*_exclusive` triggers (`V4:108-111`) and folds `forbid_commit_and_undo` into the
  new functions. Say so.
- **V62 and V64.** Their SECURITY DEFINER listers (`V62:13-24`, `V64:13-25`) still test
  `decision_states`. §3.11 lists a new overdue-holds lister, but not the unblock lister. List both
  changes.
- **Missing properties:**
  - a timeout whose sequence 1 and alert have not drained (finding 1);
  - no timeout while PostgreSQL is down (H2);
  - a replay after an undo;
  - P8's fault point applied to each of the four writers, not only to the customer;
  - the rollout cutover (MAJOR 2);
  - an override of a timeout decline (MAJOR 3).

## Claims verified

- **H1/H2 against D1 near the deadline.** D1 refuses when DB time ≥ `review_deadline_at`. The claim
  refuses when DB time < `DueHold.deadline`. Both values are `outcome.reviewDeadlineAt()`
  (`DecisionService.java:232,259`). So the analyst can claim only before the deadline and a timeout
  only after it. A D1 that checked in time and upserts late waits on the TIMEOUT row lock and sees
  TIMEOUT.
- **T2 "own event id first" closes review 2's N1(c).** Under READ COMMITTED with the advisory lock,
  the step-2 lookup sees every committed reservation. A writer whose reservation is no longer the
  latest re-records it instead of inserting a second one.
- **Concurrent effects workers without a lock are safe.** The second waits on T2's lock, finds its
  own event id, and re-records. E2's `SET NX` and E3's `ON CONFLICT` make the rest idempotent. The
  override's `unblock_events` write is not repeated, because step 2 short-circuits it (subject to
  MAJOR 3).
- **The body is sufficient for every effect the old path produced, except the label's
  `availableAt` (finding 6).** A transition produced:
  - `DecisionChanged`, which yields `decision.final` and the audit envelope
    (`KafkaMessages.java:213-240`);
  - a `LabelRecorded`.

  No transition path emits a notification (`DecisionTransitionService.java:116-127, 149-167`;
  `HoldTimeoutService.java:124`). Webhooks are derived from `decision.final`.
- **Downstream dedupe by id is real for these:**
  - `decision_states` (`V3:96`, `ON CONFLICT DO NOTHING`);
  - `label_events` (PK, `ON CONFLICT DO NOTHING`);
  - `webhook_deliveries` (`event_id` UNIQUE; an older sequence is recorded SUPERSEDED, so a late
    re-record is never sent);
  - audit receipts (`V72`);
  - Kafka envelope ids (`KafkaMessages.java:384`).

  It is not real for the feature-store label consumer (finding 6).
- **The recorder racing a live writer is harmless.** Both write the same stored body under the same
  ids. The recording row is `ON CONFLICT DO NOTHING`, and Redis is set-if-higher.
- **Sequence 1 travels with the alert and the auto-block.** It is in the same spool record
  (`DecisionService.java:209-240`) and the same sink transaction (`PostgresSink.java:186-212,
  239-273`). This is why finding 1's fix works.
- **D1(b)'s cross-entry key.** A 23505 is raised only after the other inserter commits, so the re-run
  of (b) sees the other decision and answers 409.
- **D5(b) and D4(b).** ESCALATE is excluded. Waiting only on originals that have effects removes the
  deadlock-by-waiting that review 2 warned of. V4's CHECKs admit AUTO_BLOCKED and an override of any
  decision (`V4:47-61`).
- **D4(c)'s `unblock_events` write and H2's reservation stay inside PostgreSQL.** §2's rule against
  effects outside the transaction is respected. Redis, spool and Kafka calls all fall between
  transactions (H2 C:157, T4, D2(e), D4(d)).
- **The local fallback** still returns leadership on every instance with local holds while Redis is
  down (`ResilientPorts.java:235-238`). H2's PostgreSQL claim makes concurrent local pollers and
  sweeps safe.
- **H3 with T5.** A stranded timeout reservation is recorded by T5, so excluding reserved
  transactions from the sweep no longer strands them.
