# Analyst decisions: the commit and undo contract

Status: **draft 3, for independent review.**

**Reviews so far:**
- Review 1 found draft 1 UNSOUND (6 MAJOR, 11 MINOR).
- Review 2 found draft 2 **SOUND_WITH_CHANGES** (2 MAJOR, 12 MINOR). It found no interleaving in
  which both the analyst and the timeout decide a held transaction, and none in which two writers
  reserve one sequence. Its MAJORs were:
  - a stranded or re-recorded reservation on the customer path;
  - timed-out entries left open under DECLINE_AND_VERIFY.
- Both records are in `docs/reviews/M8B/`. §11 maps every review 2 finding to its answer.

Code is written against this document, not the other way round.

**Scope:**
- P2's `decideAlert`, `undoAlertDecision`, `escalateAlert` and `overrideAlertDecision`;
- how they meet M6's hold timeout (the poller, its local fallback and the overdue sweep) and M6's
  customer verification (`VerificationService`, `UnblockReconciler`);
- §6, what a queue lists, which P1's reads depend on.

**Sources:**
- D-44, D-29, D-10 and D-18;
- the four operations and the `IdempotencyKey` rule ("replays return the original outcome");
- V3, V4, V5, V8, V11, V62 and V71;
- in `backend/decision`: `DecisionTransitionService`, `HoldTimeoutService`, `RedisHoldSchedule`,
  `ResilientPorts`, `JdbcOverdueHolds`, `PostgresSink`, `DecisionService`, `DecisionState`,
  `RedisDecisionStates`, `RedisCircuitBreakers` and `KafkaMessages`;
- in `notify`: `VerificationService` and `UnblockReconciler`;
- ADR 0056 and ADR 0064.

## 1. What exists, and what changes

**M6 today:**
- Entries are mutable rows (`status`, `version`). Decisions, commits and undos are append-only.
- MEDIUM holds wait in a Redis sorted set. Three paths can time one out: the leader's poller, each
  instance's local fallback while Redis is down, and the PostgreSQL overdue sweep.
- **Transitions after the model's decision.** The analyst, a timeout, customer verification and a
  senior override each compute the next state from Redis, or from a lagging PostgreSQL. Each
  records the event through the spool with random ids, then ignores the result of a Redis
  compare-and-set. `PostgresSink` closes an entry only on a timeout *release*.

**This contract adds two arbiters in PostgreSQL**, each written synchronously, inside the
transaction that relies on it:
- `hold_owners`: who owns a held transaction (H1 to H5);
- `transaction_transitions`: the reservation, with its full event body, of every transition after
  sequence 1 (T1 to T5).

It also sets the decision lifecycle (D1 to D7) and lists the changes to M6 (§8).

## 2. Terms and the transaction discipline

| Term | Meaning |
|---|---|
| **G** | undo grace, 5 s (D-44) |
| **P** | the committer's, effects worker's and recorder's period, 500 ms |
| **DB time** | `clock_timestamp()` on the primary. Every grace, deadline and sweep comparison in this contract uses it. |
| **open entry** | status PENDING or IN_REVIEW |
| **analyst decision** | CONFIRM_FRAUD or MARK_LEGITIMATE |
| **live decision** | an analyst decision with no undo row |
| **committed** | a commit row exists, or `undo_until` IS NULL (§3.7) |
| **the runner** | the audit module's `inTenant`, which retries SQLSTATE 40001 and 40P01 |

- **Every transaction here runs through the runner, at READ COMMITTED,** with `lock_timeout` 2 s and
  `statement_timeout` 5 s. A timeout answers 503 with `Retry-After`. **No transaction has an
  effect outside PostgreSQL before it commits.** Redis, spool and Kafka calls happen between
  transactions.
- **A1.** Everything runs on the primary.
- **A2.** No committed transaction is lost (as in the configuration contract).
- **A3.** The analyst operations are enabled only once every instance runs the release that
  implements this contract. V80 backfills `transaction_transitions` from `decision_states` (§3.6),
  so writers on an older release, which do not reserve, never run alongside reserving writers.

**Lock order, everywhere.** A transaction may skip levels but never reverses them:
1. the entry row (`FOR UPDATE`);
2. the entry's advisory lock, taken explicitly right after the row, and again (re-entrantly) by
   §3.4's trigger;
3. the decision's advisory lock;
4. the `hold_owners` row;
5. the transition advisory lock (T2);
6. foreign-key share locks on `users` and `institutions`, which never conflict with one another;
7. the audit chain head, always last.

§3.2 and §3.3's triggers live in one trigger function per table, and each takes levels 2 then 3,
in that order.

## 3. Schema (V80, M8B's range)

1. **`alert_decisions` gains these columns, each written once at insert:**
   - `undo_until`: analyst decisions only, set by trigger to `created_at + G`, with `created_at` set
     to DB time by the same trigger;
   - `prior_status`, `alert_version`, `hold_owned`, `request_fingerprint`;
   - `override_decision`: NOT NULL exactly for SENIOR_OVERRIDE;
   - `escalated_to_alert_id`: for ESCALATE.
2. **Commits:** `committed_at` is set to DB time by trigger. The commit trigger refuses while DB
   time < `undo_until`.
3. **Undos:** `undone_at` is set to DB time by trigger. The undo trigger refuses unless it is an
   analyst decision and DB time < `undo_until`.
4. **The backstop for one live analyst decision per entry.** A BEFORE INSERT trigger on
   `alert_decisions`, under the entry advisory lock, refuses a second live analyst decision.
5. **`hold_owners`** (mutable):
   - columns: `institution_id`, `transaction_id`, `owner` (FREE, ANALYST or TIMEOUT),
     `decision_id`, `updated_at`;
   - primary key `(institution_id, transaction_id)`;
   - a CHECK that `decision_id` is not NULL exactly when the owner is ANALYST;
   - a trigger that refuses any change away from TIMEOUT;
   - tenant-isolated, with SELECT, INSERT and UPDATE granted to `fs_app`.
6. **`transaction_transitions`** (append-only):
   - columns: `institution_id`, `transaction_id`, `sequence` (≥ 2), `event_id`, `decided_by`,
     `decided_at` (DB time), `event_body`;
   - `event_body` is a jsonb object holding everything a re-record needs: the `DecisionState`
     (decision, reason codes, deadline, supersedes), the channel, the actor, and the label that
     goes with it (`label_id`, label, source, `supersedes_label_id`, `transaction_timestamp`);
   - primary key `(institution_id, transaction_id, sequence)`, with `event_id` unique;
   - tenant-isolated.
   - **V80 backfills it** from every `decision_states` row with sequence > 1, with the event id it
     already has.
   - **A companion append-only table, `transaction_transition_recordings`** (`event_id` primary key,
     `recorded_at`), marks a reservation whose spool record has been written.
7. **`v_alert_decision_state`:**
   - COMMITTED if a commit row exists or `undo_until` IS NULL;
   - UNDONE if an undo row exists;
   - otherwise PENDING_COMMIT.
8. **`alert_decision_effects`** (append-only): `decision_id` (primary key), `institution_id`,
   `done_at`, `outcome` (APPLIED or SKIPPED) and `reason`.
9. **`label_events` gains `supersedes_label_id`,** and so do `LabelRecorded` and `KafkaMessages`' label
   payload. The Kafka schema already has the field.
10. **`alert_queue_entries.status` admits `AUTO_DECLINED`** (H5). This is a contract addition,
    proposed with P2.
11. **SECURITY DEFINER listers.** Each compares with its own DB time. Each is granted to `fs_app`
    and added to `DatabaseSecurityTest`'s pinned function list, as V71 requires. There is one lister
    for each of these:
    - due decisions;
    - pending effects;
    - unrecorded reservations;
    - overdue holds.

## 4. The arbiters

**H1. `hold_owners` decides who owns a hold.** A missing row means FREE.
- **The analyst's claim** is an upsert that succeeds only from FREE:
  `INSERT … ON CONFLICT (institution_id, transaction_id) DO UPDATE SET owner='ANALYST',
  decision_id=… WHERE hold_owners.owner='FREE' RETURNING owner`.
- **A timeout's claim** succeeds from FREE or TIMEOUT.
- **Concurrent claims.** A second claimant waits for the first's transaction and then re-checks.
  Exactly one of ANALYST and TIMEOUT holds at a time, and TIMEOUT is final.

**H2. Every timeout path claims first, in its own transaction.** The paths are the leader's poller,
the local fallback and the overdue sweep.
- **The claim checks the deadline on DB time.** It is refused while DB time < the hold's deadline:
  `DueHold.deadline`, which equals the entry's `review_deadline_at`. A JVM clock that runs fast
  therefore cannot time a hold out early. A refused claim reschedules the hold at its deadline.
- **What the claim's transaction does when it succeeds** (H5):
  - closes the transaction's open entries;
  - writes the AUTO_* decision row;
  - reserves the timeout's transition (T2).
- **After it commits,** the path records the reservation (T4).
- **If the claim finds ANALYST,** the hold is dropped and nothing is recorded.
- **If PostgreSQL is unavailable,** the poller and the local fallback reschedule the hold 1 s later
  and count it, and the sweep retries on its next run.
  - This amends ADR 0064: no hold is timed out without a claim, so a PostgreSQL outage delays every
    MEDIUM timeout past D-18's ±500 ms for the outage's length (question Q3).
  - The poller keeps its lease while it reschedules. After the outage it drains at most 500 holds
    a tick, as today.
  - The lateness metric measures from the original deadline, which the DueHold carries unchanged.

**H3. The overdue sweep** selects HOLDs that meet all three conditions:
- DB time ≥ their deadline + 10 s;
- the transaction has no reservation in `transaction_transitions`, and no `decision_states` row with
  sequence > 1;
- its owner is not ANALYST.

It claims each by H2. It runs every 5 s, so it acts 10 to 15 s after a deadline.

**H4. No request path changes the Redis schedule before committing.** A decision claims in
PostgreSQL only. The Redis entry stays scheduled; at the deadline the poller's claim finds ANALYST
and drops it. An undo frees the claim and, after its commit, reschedules the hold (D2).

**H5. A timeout closes the entries itself.** In H2's claim transaction:
- every open entry of the transaction is set to AUTO_RELEASED (RELEASE_WITH_TIMEOUT_LABEL) or
  AUTO_DECLINED (DECLINE_AND_VERIFY), with `version + 1`;
- one AUTO_RELEASED, or AUTO_BLOCKED for a decline, decision row is written on the newest of those
  entries, with an idempotency key derived from the transaction.

`PostgresSink` no longer updates entries or writes that row. Its status guard makes any redelivered
old record a no-op. Entries are therefore closed synchronously under both policies, including an
escalated entry.

**T1. Every transition after sequence 1 is reserved before it is recorded.** The writers are:
- the analyst's E1;
- a timeout;
- a senior override;
- the customer's "yes, this was me";
- `UnblockReconciler`.

**T2. Reserving.** In one runner transaction at READ COMMITTED, the writer:
1. takes `pg_advisory_xact_lock('txn_transition:' || transaction_id)`;
2. **looks up its own event id first.** If that event id is already reserved, the reservation is its
   own and it goes to T4;
3. otherwise reads the **latest** state: the highest reservation, or else the sequence-1 state;
4. computes `next` by `DecisionState`'s rules;
5. inserts the reservation with its full `event_body` and `decided_at` = DB time.

The **sequence-1 state** comes from `decision_states`. A timeout may instead take it from the Redis
state cache, where it was saved at ingest. PostgreSQL holds it for every other writer:
- the analyst's and the override's alerts arrive in the same sink transaction as it;
- an unblock can exist only after its auto-block row, which arrives with it too (V5's foreign key).

If neither is visible, the writer retries later. It never guesses.

**T3. Deterministic ids.** Every event id and label id is the name-based UUID of a stable source:

| Writer | Event id from | Label id from |
|---|---|---|
| Analyst E1 | `alert_decision|` + decision id | `alert_label|` + decision id |
| Override | `alert_decision|` + override's decision id | `alert_label|` + override's decision id |
| Timeout | `hold_timeout|` + transaction | — |
| Customer "yes" and `UnblockReconciler` | `unblock|` + `unblock_events.id` | `unblock_label|` + `unblock_events.id` |
| Customer "no" (a label, no transition) | — | `verification_label|` + the verification response's id |

`UnblockReconciler` reads `u.id` in its query, so it re-derives the same ids.
`DecisionState.next` takes the event id and `decided_at` as arguments.

**T4. Recording.**
- After the reservation commits, the writer records **the reservation's stored `event_body`**
  through the spool. A re-record is therefore byte-identical, whoever performs it.
- It then inserts the reservation's recording row (`ON CONFLICT DO NOTHING`).
- It then sets the Redis state cache with a "set if this sequence is higher" script.
- Receivers ignore repeated event ids, so recording twice is recording once.

**T5. No reservation is stranded.** Every instance runs the recorder every P. It lists
reservations older than 30 s that have no recording row, and records each from its stored body.
Recording is idempotent, so the recorder needs no lock. A writer that crashed after reserving is
therefore always completed. **T4's "re-decide" happens only before a reservation exists:**
- a writer that finds the latest is not what it expected re-reads and re-decides;
- if no transition is allowed, it records nothing and reports SKIPPED.

## 5. Decision rules

**D1. Deciding** (`decideAlert`) is one runner transaction:
- **(a)** `FOR UPDATE` on the entry, then its advisory lock. No row: 404.
- **(b) Idempotency.** Look up `(institution_id, idempotency_key)`:
  - same caller, alert and fingerprint: return the original response, 202 with the stored
    `decision_id`, `PENDING_COMMIT` and `undo_until`, whatever has happened since. A replay is not
    a status read; the client reads the alert for the current state;
  - anything different: 409 `idempotency-conflict`.
  - If the insert at (g) hits the key's unique constraint (the same key used concurrently on
    another entry), D1 is re-run once and answers by (b).
- **(c)** If the entry is escalated and the caller's role ranks below its target: 403.
- **(d)** If `alert_version` ≠ `version`: 409 `stale-alert`. If the entry is not open: 409
  `stale-alert`.
- **(e)** For MEDIUM:
  - if DB time ≥ `review_deadline_at`: 409 `review-deadline-passed`;
  - otherwise claim (H1). If the owner is TIMEOUT: 409 `review-deadline-passed`.
- **(f)** Insert the decision. Set the entry's status to CONFIRMED_FRAUD or MARKED_LEGITIMATE, and
  `version + 1`. Audit `ANALYST_DECISION` / `DECIDED`.
- **(g)** Commit, and answer 202.

**D2. Undoing** (`undoAlertDecision`) is one runner transaction:
- **(a)** Entry row and advisory lock. The decision must be an analyst decision on this entry,
  otherwise 404; it must be the caller's, otherwise 403.
- **(b)** If an undo row by this caller already exists: answer 204, and, if `hold_owned`, still
  perform (e)'s reschedule.
- **(c)** Insert the undo row. If §3.3's trigger refuses: 409 `undo-window-expired`.
  - This includes a retry, after a 40001 or 40P01, that lands past `undo_until`. That is an accepted
    cost (§9).
- **(d)** If `hold_owned`: set `hold_owners` to FREE where `decision_id` is this decision. Restore
  `prior_status`, with `version + 1`. Audit `ANALYST_DECISION` / `UNDO`.
- **(e)** After the commit, if `hold_owned`: reschedule the hold with its original deadline and the
  stored policy (`decision_states.timeout_policy`).
  - A past deadline is claimed on the poller's next tick, within one tick plus one claim
    transaction.
  - If rescheduling fails, H3 acts 10 to 15 s after the deadline.

**D3. Committing.** Every instance runs the committer every P.
- **(a)** For each due analyst decision, oldest first, one runner transaction:
  - `pg_try_advisory_xact_lock` on the decision (level 3); skip it if held;
  - insert the commit row `ON CONFLICT DO NOTHING RETURNING`;
  - if a row was returned, audit `ANALYST_DECISION` / `COMMITTED`.
- **(b) Bound:** `undo_until + P + d`. This holds while PostgreSQL is up and the instances together
  commit faster than decisions fall due: about 100 a period per instance.

**D4. Effects.** Every instance runs the effects worker every P. It takes no lock: concurrent runs
are safe by T3 to T5 and E2.
- **(a) Which decisions have effects.** Committed analyst decisions and overrides with no effects
  row, oldest commit first. ESCALATE, AUTO_RELEASED and AUTO_BLOCKED rows have none.
- **(b) Override ordering.** An override's effects wait for its original's effects row, only when
  the original is a kind that has effects.
- **(c) E1, the transaction.**
  - **An analyst decision with `hold_owned`:** reserve and record HOLD → APPROVE or DECLINE, by
    ANALYST, with an ANALYST label.
  - **An override:** reserve and record the transition by SENIOR_OVERRIDE to the outcome of
    `override_decision`, where `DecisionState` allows it, with a SENIOR_OVERRIDE label. The label's
    `supersedes_label_id` is the original's label.
    - An override that lifts an auto-block (DECLINE → APPROVE) also writes the `unblock_events`
      row, cause SENIOR_OVERRIDE, in the reservation's transaction.
  - **Otherwise:** the label only. That covers HIGH (already blocked) and ANOMALY (never held).
  - **A transition that is not allowed, or is already in force:** outcome SKIPPED, with the reason,
    counted.
- **(d) E2, the breaker.** On a CONFIRM_FRAUD (analyst or override) of a MEDIUM or ANOMALY entry,
  never HIGH: one Lua step, `SET NX fs:mcc:confirmed:<inst>:<txn>` (expiry 25 h), then `HINCRBY`.
  It is called on Redis directly. A failure leaves the decision for the next run.
- **(e) E3.** Insert the effects row `ON CONFLICT DO NOTHING`.
- **(f)** While Redis or the spool is unavailable, an ANALYST-owned hold stays HOLD until E1
  succeeds. `fs_analyst_effects_oldest_pending_seconds` alerts (Q3).

**D5. Overriding** (`overrideAlertDecision`) is one runner transaction:
- **(a)** Entry row and lock, then idempotency as in D1(b). A replay returns the stored decision as
  the original 201 `DecisionRecord`. Then the version check.
- **(b) The original.** `original_decision_id` must be the entry's latest decision that is
  neither undone nor overridden, and of kind CONFIRM_FRAUD, MARK_LEGITIMATE, AUTO_RELEASED,
  AUTO_BLOCKED or SENIOR_OVERRIDE (never ESCALATE). Otherwise 409 `stale-alert`.
  - It must be committed: 409 `decision-not-committed`.
  - It must not be the caller's own decision or override: 403.
- **(c)** Insert SENIOR_OVERRIDE with `override_decision`; it is committed on insert. Set the
  entry's status to OVERRIDDEN, and `version + 1`. Audit `SENIOR_OVERRIDE`. Answer 201.
- **(d)** A downgrade from fraud cannot remove a breaker count. That is an accepted cost.

**D6. Escalating** (`escalateAlert`) is one runner transaction:
- **(a)** Entry row and lock, then idempotency. A replay returns the original 201 `AlertSummary` of
  `escalated_to_alert_id`, as it now stands.
- **(b)** The version check, and the entry must be open. The target must rank strictly above the
  caller (422). An escalated entry needs the caller's role at or above its target (403).
- **(c)** For MEDIUM: insert the `hold_owners` row as FREE if it is absent, then `FOR UPDATE` it. If
  the owner is TIMEOUT: 409 `stale-alert`. The lock serialises the escalation with a timeout claim,
  which then closes the new entry too (H5).
- **(d)** Insert the new entry, copying the transaction, score, tier, probability, expected loss and
  deadline, with the target and reason. Insert ESCALATE on the original, with
  `escalated_to_alert_id`; it is committed on insert. Set the original's status to ESCALATED, and
  `version + 1`. Audit `ANALYST_DECISION` / `ESCALATED`.
- **(e)** The hold is untouched, and the timer keeps running (Q2).

**D7. Customer verification** (M6) reserves through T1 to T5 with T3's ids. That is the only change
to it.

## 6. What each queue lists

- **Without `outcome`, a queue lists open entries only.**
  - Deciding takes an entry out at once.
  - An undo puts it back with the next `version`.
  - Escalating replaces the original with the new entry.
  - A timeout closes the transaction's entries under either policy (H5).
- **`outcome` selects statuses explicitly.**
- **The queues:**
  - **standard:** HIGH and MEDIUM entries with no `escalated_from_alert_id`;
  - **anomaly:** ANOMALY entries with none;
  - **escalated:** entries with one, filtered by the caller's role;
  - **all:** the union.

## 7. Properties, each to be proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | Concurrent decides on one entry with different keys: exactly one 202. Remove the entry lock and the §3.4 trigger, and two succeed | D1(a), §3.4 |
| P2 | Committed or undone, never both. `committed_at` ≥ `undo_until` > `undone_at` | §3.2, §3.3, D2, D3 |
| P3 | An undone decision has no effects. A committed one's effects run after its commit. Running the worker twice concurrently leaves exactly one transition, one label and one breaker count | D3, D4, T3 to T5, E2 |
| P4 | Exactly one of ANALYST and TIMEOUT owns a hold, against the poller, the local fallback and the sweep. This includes a sweep acting while a decide is uncommitted, and a poller whose JVM clock runs ahead | H1 to H3 |
| P5 | A decide whose commit acknowledgement is lost leaves the hold to the poller, which drops it | H4 |
| P6 | A decide retried after 40001, 40P01 or a client 5xx with the same key returns the same 202, or fails having changed nothing | D1 |
| P7 | An override and a customer "yes" racing on one transaction reserve sequences k and k+1 in some order, never both k. Remove T2's lock and both get k | T2 |
| P8 | A writer killed after its reservation commits is recorded by T5, byte-identically. A customer reservation stranded under a later override is still recorded, and the reconciler does not stop | T4, T5, T3 |
| P9 | The breaker counts a transaction at most once, and never for HIGH | D4(d) |
| P10 | DECIDED, UNDO, COMMITTED, ESCALATED and SENIOR_OVERRIDE are each audited in their change's transaction | D1 to D6 |
| P11 | Committer, undo, decide and timeout claim racing on one entry and one audit partition finish without an unretried deadlock | §2 lock order |
| P12 | An undo past the deadline leads to a timeout within one poller tick plus one claim transaction | D2(e), H2 |
| P13 | Under both policies, a timeout closes every open entry of its transaction, an escalated one included, synchronously | H5, D6(c) |
| P14 | ESCALATE, SENIOR_OVERRIDE and AUTO_* rows read COMMITTED, and have no effects | §3.7, D4(a) |
| P15 | A queue without `outcome` never lists a decided or timed-out entry, and lists an undone one again | §6 |
| P16 | An override of a timed-out alert runs its effects without waiting. An override of an ESCALATE gets 409 | D4(b), D5(b) |
| P17 | An override lifting an auto-block writes `unblock_events` with cause SENIOR_OVERRIDE | D4(c) |

## 8. Changes to M6's code that this contract requires

- **`HoldTimeoutService`** (`tick`, the local fallback, `reconcile`) claims by H2, and on success
  performs H5 and T2. **`JdbcOverdueHolds`** selects by H3 on DB time.
- **`DecisionTransitionService`:**
  - every transition goes through T1 to T5;
  - `analystDecidesHold` becomes an effect method without the Redis cancel;
  - `customerAnswered` takes the unblock event's or verification response's id.
- **`DecisionState.next`** takes its event id and `decided_at`.
- **`UnblockReconciler`** selects `u.id` and checks for a reservation instead of `decision_states`.
- **`PostgresSink`:**
  - stops updating entries and stops writing the AUTO_RELEASED decision row (H5);
  - writes `decision_states.timeout_policy`;
  - writes `label_events.supersedes_label_id`.
- **`LabelRecorded` and `KafkaMessages`** carry `supersedes_label_id`.
- **`RedisDecisionStates`** gains the set-if-higher script.
- **`RedisCircuitBreakers`** gains the idempotent confirmed-fraud script.

## 9. Accepted costs, contract additions, and questions for the owner

**Costs:**
- **A decided MEDIUM transaction stays held longer.** It stays held for G + P plus the effects time,
  and an undo near the deadline holds it up to G past the deadline. D-44 accepts this.
- **The device and the server keep separate clocks.** The countdown runs on the device and the
  grace on DB time. A click at the end, or a retried undo that lands past `undo_until`, gets 409.
- **An override downgrading from fraud** leaves a breaker count until its bucket expires.
- **A replay returns the original outcome.** A client that needs the current state reads the alert.

**Contract additions, proposed with P2:**
- `idempotency-conflict` and 503 declared on the four operations;
- `override_decision` on `DecisionRecord`;
- `AUTO_DECLINED` in `AlertStatus`.

**Questions for the owner:**
- **Q1.** MARK_LEGITIMATE on an auto-blocked HIGH alert labels it and leaves the block. Only customer
  verification or a senior override lifts a block, as `DecisionState` requires. ANOMALY alerts
  differ too: an analyst's decision only labels, while an override may decline.
- **Q2.** Escalation does not stop the review timer, so a MEDIUM escalation is usually closed by
  the timeout before a senior sees it.
- **Q3.** During a PostgreSQL outage, no hold times out: every MEDIUM payment stays held until the
  database returns, with an alert. This amends ADR 0064 and exceeds D-18's tolerance for the
  outage. The alternative, timing out without the arbiter, can contradict an analyst's committed
  decision.

## 10. Review 1's findings

All are resolved or carried in this draft. Review 2's table shows their state at draft 2. Its
remaining PARTLY entries (6, 8, 9, 11, 14, 15, 16, 17) are answered by the rows of §11 named in
brackets: 6 → N1, N9; 8 → N8; 9 → N5; 11 → N2; 14 → N7; 15 → N1; 16 → N3; 17 → N12.

## 11. Review 2's findings and their answers

| Finding | Answer |
|---|---|
| N1 MAJOR: stranded or re-recorded reservations on the customer path | T2 looks up its own event id first; T3 gives every writer deterministic ids, the reconciler's from `u.id`; T4 records the stored body; T5's recorder completes stranded reservations; H3's "no transition" defined; P8 |
| N2 MAJOR: entries left open under DECLINE_AND_VERIFY | H5: the claim transaction closes the entries under both policies; `AUTO_DECLINED`; the sink no longer closes entries; P13 |
| N3 AUTO_* rows in the effects worker; overriding ESCALATE | D4(a) excludes them; D4(b) waits only on originals that have effects; D5(b) refuses ESCALATE; P14, P16 |
| N4 sequence-1 visibility for the timeout | T2: a timeout may take sequence 1 from Redis; writers retry, never guess; the verification-row wording corrected |
| N5 JVM clocks in the poller | H2: the claim checks the deadline on DB time; P4 |
| N6 the PostgreSQL outage | H2 states the ADR 0064 amendment, D-18, the lease, the drain and the metric; Q3 |
| N7 lock order violated | §2: the entry advisory lock taken explicitly at D1(a); one trigger function per table; the effects worker takes no lock, and no transaction has an outside effect |
| N8 idempotency gaps | D1(b): a replay is the original outcome; a cross-entry key re-runs and gets 409; replay bodies for override and escalate; the 409 and 503 declarations go with P2 |
| N9 the reservation body | §3.6 `event_body`; T4 records it; the policy has one origin, the decision's snapshot |
| N10 override unblock; ANOMALY; reconciler starvation | D4(c) writes `unblock_events`; Q1 states the ANOMALY asymmetry; the reconciler's re-selection and starvation are M6's, filed as PB-156 |
| N11 undo retry, timeouts, undo replay | §2's timeouts; D2(b) reschedules on replay; §9 states the retry cost |
| N12 properties and text | P1, P3, P7, P8 and P12 reworded; P16 and P17 added; the V80 backfill; §8 completed; the §-numbers fixed; V71 means `DatabaseSecurityTest`'s list; T2 at READ COMMITTED |
