# Analyst decisions: the commit and undo contract

Status: **draft 6, reviewed SOUND_WITH_CHANGES with no MAJOR from its own change (review 6); its
findings applied below, for a delta review.** Draft 6 is the owner's option (a) of 2026-10-20, and
nothing else: `transaction_transitions` becomes **the only record** of every decision state after
the model's first (T6).
- **Why.** Review 5 (`docs/reviews/M8B/decision-commit-contract-review-5.md`) found a MAJOR from
  draft 5's own change: the sink, still writing later states into `decision_states`, could break
  that table's foreign key and lose a transition for good.
- **What it does.** Option (a) **removes that writer** instead of coordinating two. In the owner's
  words, it "removes a writer rather than coordinating two".
- **What it costs** is recorded in §9.
- **What is carried.** Review 5's other findings are carried unchanged (§14).

**The owner's rule.** If draft 6 still produces a MAJOR, the author stops. The queue semantics then
need rethinking, not the contract's wording.

**Reviews so far:**
- Review 1 found draft 1 UNSOUND (6 MAJOR, 11 MINOR).
- Review 2 found draft 2 **SOUND_WITH_CHANGES** (2 MAJOR, 12 MINOR). It found no interleaving in
  which both the analyst and the timeout decide a held transaction, and none in which two writers
  reserve one sequence. Its MAJORs were:
  - a stranded or re-recorded reservation on the customer path;
  - timed-out entries left open under DECLINE_AND_VERIFY.
- Review 3 found draft 3 **SOUND_WITH_CHANGES** (3 MAJOR, 9 MINOR). The arbiters held. The MAJORs
  were:
  - a timeout committing before its alert entry existed;
  - a deploy assumption that was false;
  - an override's unblock write that could fail for ever.
- All records are in `docs/reviews/M8B/`.
  - §11 maps review 2's findings.
  - §12 maps review 3's MAJORs, and lists the MINORs it carries.

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
- **A3. The release that brings V81 is deployed by a stop-and-drain cutover, never rolled.** This
  is a runbook requirement for M9 (review 3, MAJOR 2). The cutover runs in this order:
  1. stop every spool writer on every instance. That means ingest, the hold poller and overdue
     sweep, the unblock reconciler, the verification endpoint, the analyst API and the committer
     and effects workers: everything that appends to the spool, not only ingest (review 4,
     finding 2). M9's dead-letter replay stays stopped too;
  2. wait until every instance's spool has drained to PostgreSQL and Kafka, which the spool's lag
     gauges show as zero;
  3. stop the instances;
  4. run V81;
  5. start only instances of the new release.

  Its consequences:
  - no writer that does not reserve ever runs after V81, and no older spool record drains after it;
  - V81's backfill of `transaction_transitions` is complete;
  - the backfill also writes a recording row for every backfilled reservation, so T5 never
    re-records history.

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

## 3. Schema (V81, M8B's range; renumbered from V80 because P1 took V80, ADR 0112)

1. **`alert_decisions` gains these columns, each written once at insert:**
   - `undo_until`: analyst decisions only, set by trigger to `created_at + G`, with `created_at` set
     to DB time by the same trigger;
   - `prior_status`, `alert_version`, `hold_owned`, `request_fingerprint`;
   - `override_decision`: NOT NULL exactly for SENIOR_OVERRIDE;
   - `prior_hold_owner` and `prior_hold_deadline`: the owner and deadline an analyst decision's claim
     replaced, so that an undo restores them (D2);
   - `escalated_to_alert_id`: for ESCALATE.
2. **Commits:** `committed_at` is set to DB time by trigger. The commit trigger refuses while DB
   time < `undo_until`.
3. **Undos:** `undone_at` is set to DB time by trigger. The undo trigger refuses unless it is an
   analyst decision and DB time < `undo_until`.
4. **The backstop for one live analyst decision per entry.** A BEFORE INSERT trigger on
   `alert_decisions`, under the entry advisory lock, refuses a second live analyst decision.
5. **`hold_owners`** (mutable):
   - columns: `institution_id`, `transaction_id`, `owner` (FREE, ANALYST, ESCALATED or TIMEOUT),
     `decision_id`, `deadline`, `timeout_outcome` (AUTO_RELEASED or AUTO_DECLINED, set with
     TIMEOUT), `updated_at`;
   - `deadline` is the time the hold runs to: the review deadline, or an escalation's longer one
     (D6). It is written on insert from the transaction's `review_deadline_at`, and changed only by
     D6 and D2.
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
   - tenant-isolated;
   - **no foreign key to `transaction_ids`, deliberately.** A writer reserves a later state without
     waiting for the sink, and a foreign key would bring back review 4's MAJOR 1 (review 5, m3).
   - **V81 backfills it** from every `decision_states` row with sequence > 1, with the event id it
     already has. A backfilled row's `event_body` holds only the `DecisionState`: the channel, the
     actor and the label were never stored, and a backfilled reservation already has its recording
     row, so it is never re-recorded. From then on, `decision_states` receives sequence 1 only (T6).
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
- **The analyst's claim** is an upsert that succeeds from FREE or ESCALATED:
  `INSERT … ON CONFLICT (institution_id, transaction_id) DO UPDATE SET owner='ANALYST',
  decision_id=… WHERE hold_owners.owner IN ('FREE','ESCALATED') RETURNING owner`.
- **An escalation's claim** (D6) succeeds from FREE, and leaves ESCALATED unchanged.
- **A timeout's claim** succeeds from FREE, ESCALATED or TIMEOUT, and only when DB time ≥ the row's
  `deadline`. With no row, it compares against the hold's own deadline instead.
- **Concurrent claims.** A second claimant waits for the first's transaction and then re-checks.
  Exactly one of ANALYST and TIMEOUT holds at a time, and TIMEOUT is final.

**H2. Every timeout path claims first, in its own transaction.** The paths are the leader's poller,
the local fallback and the overdue sweep.
- **The claim checks the deadline on DB time.** It is refused while DB time < the hold's deadline:
  `hold_owners.deadline`, or `DueHold.deadline` if there is no row. A JVM clock that runs fast
  therefore cannot time a hold out early, and a hold whose escalation extended it is not timed out
  at its first deadline. A refused claim reschedules the hold at that deadline.
- **The claim does not wait for the sink** (option A; draft 4's wait is withdrawn).
  - **No dependency on the sink.** The timeout's sequence-1 state comes from the hold it claimed
    (T2), so the claim needs nothing the sink writes.
  - **Late entries.** An alert entry the sink writes after the claim is closed by the sink itself
    (H5's backstop, now the only mechanism for that case).
  - **A dead-lettered ingest record** no longer blocks its timeout: the payment times out at its
    deadline, as under M6.
- **The claim records the outcome:** `hold_owners.timeout_outcome` (AUTO_RELEASED or
  AUTO_DECLINED, from the hold's policy), for the sink's use.
- **What the claim's transaction does when it succeeds** (H5):
  - closes the transaction's open entries;
  - writes the AUTO_* decision row;
  - reserves the timeout's transition (T2).
- **After it commits,** the path records the reservation (T4).
- **If the claim finds ANALYST,** the hold is dropped and nothing is recorded.
- **If PostgreSQL is unavailable,** the poller and the local fallback reschedule the hold 1 s later
  and count it, and the sweep retries on its next run.
  - **This amends ADR 0064, and breaches D-18 for the outage (owner, Q3; recorded in ADR 0111).** No
    hold is timed out without a claim, so every MEDIUM payment stays held until the database
    returns.
    - The breach of D-18's ±500 ms is bounded by the outage's duration.
    - **No measurement of that duration exists** (§9, limitations).
    - The owner's reason: "a delayed payment is recoverable; a timeout that contradicts an analyst's
      committed decision is not." 
  - The poller keeps its lease while it reschedules. After the outage it drains at most 500 holds
    a tick, as today.
  - The lateness metric measures from the original deadline, which the DueHold carries unchanged.

**H3. The overdue sweep** selects HOLDs that meet all three conditions:
- DB time ≥ their deadline + 10 s, where the deadline is `hold_owners.deadline` if a row exists,
  else the HOLD's `review_deadline_at`;
- the transaction has no later state in `v_decision_states` (T6);
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

**The sink closes an entry that arrives after its timeout** (option A; review 3, MAJOR 1). This is
the only mechanism for that case, so it is specified as a rule, not a backstop.
- **The rule.** In the transaction that writes a MEDIUM `AlertRaised`, `PostgresSink`:
  1. inserts the entry (open);
  2. inserts the `hold_owners` row as FREE, with `deadline` = the entry's `review_deadline_at`, `ON
     CONFLICT DO NOTHING`;
  3. takes that row `FOR UPDATE`;
  4. if the owner is TIMEOUT, sets the new entry to `timeout_outcome` with `version + 1`, writes
     the AUTO_* decision row with H5's idempotency key, and counts the case.
- **Why no entry is left open.** The claim's own transaction (H5) upserts the same row, and only
  then closes the open entries with an UPDATE. Either:
  - the sink holds the row first, so the claim's upsert waits for the sink's commit, and the
    claim's later UPDATE sees the new entry and closes it; or
  - the claim holds it first, so the sink's `FOR UPDATE` waits, then reads TIMEOUT and closes the
    entry itself.

  Each of the two writes the AUTO_* row with the same key, so there is exactly one.

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

**The sequence-1 state:**
- **For the timeout,** it is carried by the hold itself. `DueHold` gains the HOLD state's event id,
  `decided_at`, deadline and reason codes, written by `DecisionService` when it schedules the hold.
  The poller and the local fallback therefore reserve from the hold they claimed, and never read
  `decision_states` or the Redis state cache for it (option A).
- **For the overdue sweep,** it comes from `decision_states`, the table the sweep selects from.
- **For every other writer,** it comes from `decision_states`. It is always there when needed:
  - the analyst's and the override's alerts arrive in the same sink transaction as that row;
  - an unblock can exist only after its auto-block row, which arrives with it too (V5's foreign
    key).

  If it is not visible, that writer retries later. It never guesses.

**T3. Deterministic ids.** Every event id and label id is the name-based UUID of a stable source:

| Writer | Event id from | Label id from |
|---|---|---|
| Analyst E1 | `alert_decision|` + decision id | `alert_label|` + decision id |
| Override | `alert_decision|` + override's decision id | `alert_label|` + override's decision id |
| Timeout | `hold_timeout|` + transaction | — |
| Customer "yes" and `UnblockReconciler` | `unblock|` + `unblock_events.id` | `unblock_label|` + `unblock_events.id` |
| Customer "no" (a label, no transition) | — | `verification_label|` + the verification response's id |

`UnblockReconciler` reads `u.id` in its query, so it re-derives the same ids. It treats an unblock
as already handled if `v_decision_states` holds a state by CUSTOMER_VERIFICATION (T6). That
includes unblocks handled before V81, which the backfill moved (review 3, MAJOR 2).
`DecisionState.next` takes the event id and `decided_at` as arguments.

**T4. Recording.**
- After the reservation commits, the writer records **the reservation's stored `event_body`**
  through the spool. A re-record is therefore byte-identical, whoever performs it.
- It then sets the Redis state cache with a "set if this sequence is higher" script.
- **Only after both succeed** does it insert the reservation's recording row (`ON CONFLICT DO
  NOTHING`). A failed Redis write therefore leaves the reservation unrecorded, and T5 repeats both
  steps.
  - **Why the order matters.** `GET /decisions/{id}` reads Redis first (D-14). Draft 6 and every
    draft before it wrote the recording row first, so a Redis failure between the two steps was never
    retried. The bank then read a superseded state from the cache until its key expired, up to
    8 days. That defect is M6's own, and review 6 (finding 1) found it.
- Receivers ignore repeated event ids, and the set-if-higher script ignores a state it already
  holds, so recording twice is recording once.

**T5. No reservation is stranded.** Every instance runs the recorder every P. It lists
reservations older than 30 s that have no recording row, and performs T4 for each: the spool record
from the stored body, the Redis set, then the recording row.
Recording is idempotent, so the recorder needs no lock. A writer that crashed after reserving is
therefore always completed. **T4's "re-decide" happens only before a reservation exists:**
- a writer that finds the latest is not what it expected re-reads and re-decides;
- if no transition is allowed, it records nothing and reports SKIPPED.

**T6. `transaction_transitions` is the only record of every state after the first** (option (a)).
- **`decision_states` holds sequence 1 only:** the model's decision, written by `PostgresSink` with
  its transaction.
- **Every later state exists in PostgreSQL only as its reservation,** written synchronously by its
  writer: the analyst's effect, the timeout's claim, an override, or the customer's answer.
- **The sink writes no later state.**
  - A `DecisionChanged` with sequence > 1 that reaches the sink through the spool is not inserted
    into `decision_states`. Its reservation already is the record.
  - The sink still writes the label that a later state's record carries. A later state's record
    carries no notification or unblock row (review 6, finding 6).
  - A `DecisionChanged` with sequence > 1 that has no reservation can only come from a writer that
    does not reserve, or from a dead letter that M6 wrote before V81 and that is replayed after it.
    The cutover drains spools, not dead letters, so the second can happen. The sink counts and logs
    each such record and drops it, and the outcome is benign: the overdue sweep times the
    transaction out again under a new event id, the webhook suppresses the repeat, and H5 closes
    any entry left open (review 6, finding 5).
  - **V81 also adds `CHECK (decision_sequence = 1) NOT VALID`** to `decision_states`. Any stray write
    of a later state then fails loudly, while the existing rows (backfilled) are not re-checked.
- **One view, `v_decision_states`, is what every reader reads.** It is `decision_states` with
  sequence 1, joined with `transaction_transitions` in the same columns, one row per
  `(institution, transaction, sequence)`.
  - **Tenant isolation.** It is a `security_invoker` view over the two row-level-secured tables, so it
    isolates exactly as they do (review 6, finding 2). It does not use the usual `v_` security-barrier
    pattern, which filters by `current_institution()` and would return nothing to the
    cross-institution SECURITY DEFINER listers.
  - **Grants.** It is granted to `fs_app`, `fs_app_readonly` and `fs_compliance_ro`, as
    `decision_states` is. `transaction_transitions` gets SELECT for the two read-only roles.
  - **The V62 and V64 listers** read the two base tables directly, as their owner.
  - **The readers that move to it:**
  - `JdbcDecisionStates`, the fallback of the bank-facing `GET /decisions/{id}` (D-14);
  - `JdbcOverdueHolds` and V62's lister (H3);
  - `UnblockReconciler` and V64's lister (T3);
  - `CustomerSmsSender`'s classification, which asks whether the latest state is still DECLINE;
  - P1's account history (`AccountQueries`), which reads each transaction's latest state.

  `JdbcIdempotency` reads sequence 1 only, and keeps reading `decision_states`.
- **Why the foreign-key failure is gone.** The only writer of `decision_states` is the sink, and it
  writes sequence 1 in the same transaction as the `transaction_ids` row its foreign key needs.
  `transaction_transitions` has no such key. So a timeout that reserves before the ingesting
  instance's sink has run loses nothing: its state is in the view from the moment the claim
  commits, and the sink's later sequence-1 row joins it there.

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
  - if DB time ≥ the hold's deadline: 409 `review-deadline-passed`. The deadline is
    `hold_owners.deadline` if a row exists, which is the escalation deadline for an escalated
    transaction; otherwise it is `review_deadline_at`;
  - otherwise claim (H1), from FREE or ESCALATED, storing the replaced owner and deadline in
    `prior_hold_owner` and `prior_hold_deadline`. If the owner is TIMEOUT: 409
    `review-deadline-passed`.
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
- **(d)** If `hold_owned`: where `decision_id` is this decision, set `hold_owners` back to
  `prior_hold_owner` (FREE or ESCALATED) and its deadline to `prior_hold_deadline`. Restore
  `prior_status`, with `version + 1`. Audit `ANALYST_DECISION` / `UNDO`.
- **(e)** After the commit, if `hold_owned`: reschedule the hold at `prior_hold_deadline`, with the
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
    - **An override that lifts an auto-block** (DECLINE → APPROVE) also writes an `unblock_events`
      row with cause SENIOR_OVERRIDE, in the reservation's transaction. It does so **only when an
      `auto_block_events` row exists** for the transaction, and with `ON CONFLICT DO NOTHING`.
      - An override of a DECLINE_AND_VERIFY timeout has no auto-block, so it writes none.
      - A block already lifted writes nothing again (review 3, MAJOR 3).
  - **Otherwise:** the label only. That covers HIGH (already blocked) and ANOMALY (never held).
  - **A transition that is not allowed, or is already in force:** outcome SKIPPED, with the reason,
    counted.
- **(d) E2, the breaker.** On a CONFIRM_FRAUD (analyst or override) of a MEDIUM or ANOMALY entry,
  never HIGH: one Lua step, `SET NX fs:mcc:confirmed:<inst>:<txn>` (expiry 25 h), then `HINCRBY`.
  It is called on Redis directly. A failure leaves the decision for the next run.
- **(e) E3.** Insert the effects row `ON CONFLICT DO NOTHING`.
- **(e′) A permanent failure does not stall the worker** (review 3, MAJOR 3).
  - **What counts as permanent:** an effect whose transaction fails with a non-retryable SQLSTATE
    (classes 22 and 23).
  - **What happens:** the worker records the effects row with outcome FAILED, the SQLSTATE and the
    effect, raises `fs_analyst_effects_failed_total`, and moves on. A FAILED effect is for an
    operator to resolve.
  - **Everything else is retried on the next run:** a 40001 or 40P01, a lost connection, Redis or
    the spool unavailable.
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
- **(c) For MEDIUM, escalation stops the timer** (owner, Q2):
  - insert the `hold_owners` row as FREE if it is absent, then `FOR UPDATE` it;
  - if the owner is TIMEOUT: 409 `stale-alert`;
  - if it is FREE: claim ESCALATED, and set `deadline` to DB time + W, the escalation review window;
  - if it is already ESCALATED (a senior escalating on to a risk officer): keep the existing
    deadline. One escalation window covers the whole chain.
  - **W, the escalation review window, is an ASSUMED value, not a requirement.** The default is 15
    minutes, and it is configurable. The SRS states no figure, and no capacity model exists to
    derive one. The reasoning for the default:
    - **Long enough for a senior to see it.** The console re-reads the escalated queue every 60 s,
      so a senior on shift sees an escalation within a minute or two. 15 minutes leaves room to open
      it, read the context and decide.
    - **Short enough for the customer.** A payment held longer than about a quarter of an hour
      turns into a support call, and the channel's own timeout policy then decides the payment, as
      it would without escalation.

    Neither half is measured. D-10's capacity model, when it exists, replaces the assumption.
  - The lock serialises the escalation with a timeout claim. A claim that wins first closes the
    original entry, and the escalation is refused.
- **(d) The new entry.** Insert it, copying the transaction, score, tier, probability and expected
  loss, with the target and the reason.
  - Its `review_deadline_at` is the escalation deadline, so the senior sees the time they have.
  - Insert ESCALATE on the original, with `escalated_to_alert_id`; it is committed on insert.
  - Set the original's status to ESCALATED, and `version + 1`.
  - Audit `ANALYST_DECISION` / `ESCALATED`.
- **(e)** After the commit, reschedule the Redis hold at the escalation deadline. If that fails, the
  poller fires at the old deadline, its claim is refused because DB time is before the row's
  deadline, and it reschedules at the row's deadline (H2).
- **(f) If no senior answers by the escalation deadline,** the timeout claims the hold (H1, H2). The
  channel's MEDIUM timeout policy then applies, releasing the payment or declining it for
  verification. The escalated entry is closed AUTO_RELEASED or AUTO_DECLINED (H5).
  - **The escalation stays on record:** the ESCALATE decision on the original, and the AUTO_*
    decision on the escalated entry.
  - The owner's choice was "a longer bound, then the payment releases with the escalation
    recorded". This contract says which: the longer bound is W, and then the channel's timeout
    policy applies, so a DECLINE_AND_VERIFY channel declines rather than releases.

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
| P18 | A timeout that commits before the ingesting instance's sequence-1 row exists appears in `GET /decisions/{id}`'s database read at once, and the sink's later sequence-1 row joins it without error | T6 |
| P19 | The sink writes no `decision_states` row with sequence > 1; a stray one is refused by the CHECK | T6 |
| P20 | A Redis failure between the spool record and the cache set leaves the reservation unrecorded, and T5 sets the cache; `GET /decisions/{id}` then shows the new state | T4, T5 |

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
  - writes `decision_states` for sequence 1 only, and no later `DecisionChanged` (T6);
  - stops updating entries and stops writing the AUTO_RELEASED decision row (H5);
  - writes `decision_states.timeout_policy`;
  - writes `label_events.supersedes_label_id`.
- **`LabelRecorded` and `KafkaMessages`** carry `supersedes_label_id`.
- **`RedisDecisionStates`** gains the set-if-higher script.
- **Every reader of later states reads `v_decision_states`** (T6): `JdbcDecisionStates`,
  `JdbcOverdueHolds`, the V62 and V64 listers, `UnblockReconciler`, `CustomerSmsSender`, and
  `backend/analyst`'s `AccountQueries`.
- **`RedisCircuitBreakers`** gains the idempotent confirmed-fraud script.

## 9. Accepted costs, contract additions, and questions for the owner

**What reducing to one record costs (option (a), owner 2026-10-20):**
- **What the sink can no longer express:**
  - **A later state that only exists on the spool.** Before V81, a transition reached PostgreSQL
    only through the sink. Now a later state exists in PostgreSQL only if its writer reserved it.
    A `DecisionChanged` with sequence > 1 and no reservation is dropped from PostgreSQL, counted and
    logged, though Kafka still carries it. Only a writer that does not reserve can produce one: an
    old release, or a pre-V81 spool record. A3's cutover excludes both. Outside the cutover, such a
    state would be visible to Kafka's consumers and not to the database's readers.
  - **Replaying history from the spool.** Replaying a spool's later states into PostgreSQL, for
    instance after restoring the database from an older backup, no longer restores them. Their
    record is the reservation table, which a restore brings back with everything else (A2).
- **What ADR 0064 and D-13 assumed** (review 6, finding 4: the assumption is theirs, not ADR 0068's).
  - **Its assumption.** Every decision fact reaches PostgreSQL through the spool, asynchronously,
    so PostgreSQL is never on the decision's critical path.
  - **What now holds:** the model's decision on ingest, which is the hot path, still does. Every
    later state does not: it is written to PostgreSQL synchronously, by a path that already needs
    PostgreSQL to arbitrate (an analyst's request, a timeout's claim, a customer's answer, an
    override). The ingest hot path is unchanged.
  - **Where it is recorded:** in ADR 0111's consequences, and in a note on ADR 0064 when P2 lands.
  - **Order of arrival.** PostgreSQL now holds a later state before Kafka and the webhook do: the
    reservation commits first, then the spool publishes. A consumer that compares PostgreSQL with
    Kafka can see PostgreSQL ahead, for the spool's lag.
  - **`decision-fact-ordering.md` needs amending.** Its `resolved` classification (line 209) reads
    later states from `decision_states`, and `SmsOrderingTest` pins it (line 486). Both move to
    `v_decision_states`, and the amendment is made with P2's code.
  - **Read-only roles.** Support and compliance queries that read `decision_states` for later states
    (as `fs_app_readonly` or `fs_compliance_ro`) must read `v_decision_states` instead. The view is
    granted to both.
- **What D-44 assumed.** Its resolution publishes a decision's side effects "through the outbox"
  after the grace.
  - **What replaces it.** The reservation plus its recorder (T4, T5) does that job: the state is
    written transactionally first, then published, and re-published until recorded. `outbox_events`
    stays unused.
  - **Why this is not a deviation.** The outbox's guarantee, published after a transactional write
    and at least once, holds by another mechanism.
- **Readers.** Every reader of later states moves to the view, which is one more join for
  `GET /decisions/{id}`'s fallback, and that fallback is not on the ingest hot path.

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

**The owner's decisions (2026-10-20):**
- **Q1, kept as proposed.** MARK_LEGITIMATE on an auto-blocked HIGH alert labels it, and leaves the
  block. Lifting a block needs customer verification or a senior override. In the owner's words:
  "One analyst reversing a model block with a click makes them a single point of failure for a
  compromised account, and the extra step protects the customer rather than the system."
- **Q2, escalation stops the timer.** It holds the payment for up to W. If no senior answers, the
  channel's timeout policy applies, with the escalation recorded (D6(c) to (f)).
- **Q3, payments stay held until the database returns.** ADR 0064 is amended, and D-18 is breached
  for the outage's duration (H2).

**Limitations:**
- **No measurement of PostgreSQL outage duration exists.** The length of a Q3 hold, and therefore
  the size of D-18's breach, cannot be stated from evidence. Under option A the sink's catch-up no
  longer delays a timeout; only the database's own availability does.
- **W (15 min) is ASSUMED.** Escalated MEDIUM payments are held up to W. D-10 calls for a capacity
  model of analysts and alerts (`docs/ml/capacity_model.md`). It does not exist yet, so nothing
  measures whether seniors can answer within W.

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

## 12. Review 3's findings: the MAJORs answered here, the MINORs carried

| Finding | Answer in draft 4 |
|---|---|
| MAJOR 1: a timeout committing before its alert entry existed | H2 requires the sequence-1 row (written with the entry), and rolls back and reschedules otherwise; T2's Redis exception is withdrawn; the sink's closed-entry backstop |
| MAJOR 2: A3 false, an incomplete backfill, the reconciler starved | A3 is a stop-and-drain cutover; the backfill writes recording rows; the reconciler also matches `decision_states` rows by CUSTOMER_VERIFICATION |
| MAJOR 3: the override's unblock write fails, and the worker stalls | D4(c) writes it only with an auto-block, `ON CONFLICT DO NOTHING`; D4(e′) records permanent failures as FAILED and moves on |

**Carried unchanged to the implementation's review,** under the owner's limit on draft 4:
- **m4.** The timeout claim and the committer reverse §2's lock order. The runner retries the
  deadlock, but §2's "never reverses" is false as written.
- **m5.** Is an override's label recorded when its transition is SKIPPED?
- **m6.** The feature store keeps the label that arrives last, so "receivers ignore repeated event
  ids" is false for it. `availableAt` is missing from the stored body.
- **m7.** A timeout's behaviour when sequence 1 is not visible. **Answered by MAJOR 1's fix** (H2).
- **m8.** The policy's sources, and the `timeout_policy` column, are added by the configuration
  contract's §3.4, not by this one's §3.
- **m9.** `VerificationService` is missing from §8. The key is `verification_id`. The customer's
  "No" label has no recorder of last resort.
- **m10.** D5(b)'s overridable kinds go beyond the API's wording.
- **m11.** The poller's lease during an outage, and skipping claims while the PostgreSQL breaker is
  open.
- **m12.** The 40P01 retry is not in §8. D1(b) cites (g) where it means (f). V80 drops V4's
  triggers. The V62 and V64 listers. Six missing properties.

## 13. Review 4's findings: what draft 5 answers and what it carries

| Finding | In draft 5 |
|---|---|
| 1 MAJOR (draft 4's own): a dead-lettered hold waits for ever | Option A: the wait is withdrawn; the timeout's sequence-1 state is carried by `DueHold`; the sink closes late entries under the hold-owner lock (H2, H5, T2) |
| 2 MAJOR (residual): the cutover stopped only ingest | A3 step 1: stop every spool writer, then wait for zero lag |
| 3 to 9 MINOR | Carried unchanged under the owner's limit on draft 5. They are: escalation's missing deadline check and the unreachable ANALYST owner (3); the lateness metric after an escalation (4); FAILED missing from the effects table's outcomes (5); a FAILED analyst transition strands its hold (6); P17 and `v_auto_block_status` after a re-decline (7); the order of the claim's statements (8, now specified by H5's rule); and the ADR and register records (9, now ADR 0111 and the register) |

**One exception to §2's lock order,** stated rather than hidden (review 3's m4, still carried). The
timeout claim takes the `hold_owners` row before the entries it closes. A decide holding an entry
can therefore deadlock with a claim. PostgreSQL detects it, the runner retries the loser, and a
retried decide then reads TIMEOUT and answers 409.

## 14. Review 5's findings: what draft 6 answers and what it carries

| Finding | In draft 6 |
|---|---|
| 1 MAJOR: the sink's later-state write can lose a transition for good | T6: the sink writes no later state; the reservation is the only record; one view for every reader |
| 3 MINOR: option A needs no foreign key on the arbiters | §3.6 states it for `transaction_transitions` (and `hold_owners` has none) |
| 2, 4 to 9 MINOR | Carried unchanged under the owner's limit on draft 6. They are: the H5 rule's status guard and `ON CONFLICT` (2); the sink's lock timeout (4); `DueHold`'s new fields from old Redis members and reschedules (5); non-database claim failures dropping a batch (6); ADR 0111's D-18 row and the matrix (7); a dead letter plus a lost `DueHold` holding forever, as in M6 (8, a limitation); and the cutover's writer list and wording (9) |

## 15. Review 6's findings and their answers

| Finding | Answer |
|---|---|
| 1 MAJOR (M6's, not draft 6's): a Redis failure between recording and caching is never retried | T4: the recording row comes after the Redis set; T5 redoes both; P20 |
| 2 the view's tenant isolation and the listers | T6: `security_invoker` view; listers read the base tables; grants |
| 3 missing properties | P18, P19 |
| 4 the costs section | §9: ADR 0064 and D-13, the ordering document and its test, the read-only roles, PostgreSQL ahead of Kafka |
| 5 old dead letters | T6: the case stated, benign, counted |
| 6 wording | T6 (labels only); §3.6 (backfilled bodies) |
| 7 hardening | T6: `CHECK (decision_sequence = 1) NOT VALID` |
