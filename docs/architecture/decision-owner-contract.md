# One owner per transaction's decision: the contract

Status: **draft 4, for independent review.** This is a new contract, not draft 9 of
`analyst-decision-commit-contract.md`. It replaces that contract, which stays in the repository as
history and is marked superseded.

**Draft 2** answers review 1 (`docs/reviews/M8B/decision-owner-contract-review-1.md`), §15:
- its three MAJORs from the design, which were edge specifications with local fixes, as the
  reviewer framed them;
- its MINORs;
- its inherited MAJOR, which is recorded as M6's residual with its real frequency (§7.3).

**Draft 3** answers review 2 (`docs/reviews/M8B/decision-owner-contract-review-2.md`), §16. Review 2
found no MAJOR in the owner mechanism. It found one edge MAJOR, which draft 2's PRE_V81 markers
introduced, the inherited MAJOR again with a frequency correction, and seven MINORs.

**Draft 4** answers review 3 (`docs/reviews/M8B/decision-owner-contract-review-3.md`), §17. Review 3
found no MAJOR in the owner mechanism, and no path that moves a state outside it. It found two edge
MAJORs: a failed claim that defers a customer with no alarm, and a resolution that is not final. It
also found seven MINORs.

**The owner's decision (2026-10-22), on review 8:**
- **One owner per transaction.** Each held payment, and every transaction that a later request
  touches, gets a single owner of its state. The owner is one PostgreSQL row.
- **Requests, not writers.** The timeout, the analyst's effect, an escalation, a senior's override,
  the customer's answer and an operator's resolution all become requests to that owner. None writes
  a state in its own right.
- **State moves forward only from the owner.** The owner holds the only rule table (§5).
- **Freezing is a state.** It has an explicit operator exit and an alert (§7).
- **A senior's decline is recorded as a state** even when the outcome does not change. That is what
  makes the asymmetry hold in review 8's MAJOR 3 case.

In the owner's words: eight drafts of refereeing is enough evidence. This contract replaces the
referee rules (H1 to H5, T1, T2, T8, D7) rather than adding to them. §12 says what the old contract
promised that this one does not.

**The stopping rule** (owner, 2026-10-22, after review 1):
- **Stop** if a review finds a MAJOR in the owner mechanism itself: the owner row, its lock, or the
  rule table's ordering.
- **Draft work, not a stop:** edge-specification errors with local fixes.
- **Recorded and scoped:** a MAJOR inherited from M6.

Code is written against this document, not the other way round.

**Scope:**
- P2's `decideAlert`, `undoAlertDecision`, `escalateAlert` and `overrideAlertDecision`;
- how they meet M6's hold timeout (the poller, its local fallback and the overdue sweep), M6's
  customer verification (`VerificationService`, `UnblockReconciler`) and M6's sink (`PostgresSink`);
- the final decision a bank reads (`GET /api/v1/decisions/{transaction_id}`) and receives (webhook,
  Kafka);
- what a queue lists (§10), which P1's reads depend on.

**Sources:**
- requirements D-10, D-13, D-14, D-18, D-29, D-44, FR-03-02, FR-03-05;
- the four operations, the `FinalDecision` schema and the `IdempotencyKey` rule ("replays return the
  original outcome") in `contracts/openapi/fraudshield-api.yaml`;
- migrations V3, V4, V5, V8, V11, V62, V63, V64, V71, V80;
- in `backend/decision`: `DecisionService`, `DecisionState`, `DecisionTransitionService`,
  `HoldTimeoutService`, `RedisHoldSchedule`, `ResilientPorts`, `JdbcOverdueHolds`, `PostgresSink`,
  `Tenant`, `RedisDecisionStates`, `JdbcDecisionStates`, `RedisCircuitBreakers`, `KafkaMessages`;
- in `backend/ingest`: `IngestService`, `IngestController`, `RedisIdempotency`,
  `ResilientIdempotency`, `JdbcIdempotency`;
- in `backend/notify`: `VerificationService`, `UnblockReconciler`, `CustomerSmsSender`,
  `WebhookDispatcher`;
- in `backend/audit`: `TenantTransactions`;
- ADRs 0056, 0064, 0067, 0068, 0111, 0113, and ADR 0114, which records this contract.

## 1. The problem, and the shape of the answer

**M6 today.** After the model's decision (sequence 1), four paths move a transaction's state: the
timeout, the analyst, customer verification and a senior override. Each reads the current state
from Redis or a lagging PostgreSQL, computes the next one, and records it through the spool with a
random id.

**The old contract's approach.** Eight drafts added arbiters and rules between those writers:
- `hold_owners`, for who owns a hold;
- `transaction_transitions`, with an advisory lock, for who reserves the next sequence;
- `decision_conflicts` and a freeze, for two first decisions;
- a rule for the customer path keyed on who made the latest state.

Each rule closed one interleaving and opened another. Review 8 found three MAJORs, two of them in
draft 8's own rules.

**This contract's approach. Every transaction that any later request touches has one row,
`decision_owners`.** Every request is evaluated by one procedure, under that row's lock, against
one rule table:
- every request takes the row's lock;
- the procedure reads the current state from the row, never from Redis or the spool;
- it applies the table (§5);
- it writes the outcome in the same transaction.

Two requests on one transaction are therefore serialised by the row, and the rule table is the only
place where the next state is decided. What a writer used to decide on its own, it now asks for.

## 2. Terms and the transaction discipline

| Term | Meaning |
|---|---|
| **G** | undo grace, 5 s (D-44) |
| **P** | the committer's, effects worker's and recorder's period, 500 ms |
| **W** | the escalation review window, 15 min by default, ASSUMED (§9, D6) |
| **DB time** | `clock_timestamp()` on the primary. Every grace, deadline and sweep comparison uses it. |
| **open entry** | an alert queue entry with status PENDING or IN_REVIEW |
| **analyst decision** | CONFIRM_FRAUD or MARK_LEGITIMATE |
| **committed** | a commit row exists, or `undo_until` IS NULL |
| **the owner** | the transaction's `decision_owners` row, and the procedure that evaluates requests under its lock (§4) |
| **a request** | a call to the procedure (§5). Its outcome is APPLIED, LABEL_ONLY, REFUSED or DEFERRED |
| **the runner** | a tenant transaction runner with the settings and retries below: the audit module's `TenantTransactions.inTenant` for the analyst module, and the decision module's own `adapter/jpa/TenantTransactions` for `decision` and `notify`, which do not depend on `audit` (review 2, finding 8). P2 gives the decision module's runner the same settings and retries; today it retries nothing |

- **Every transaction here runs through the runner at READ COMMITTED,** with `lock_timeout` 2 s and
  `statement_timeout` 5 s. A timeout answers 503 with `Retry-After`.
- **The runner retries SQLSTATE 40001 and 40P01.** Today the audit module's retries 40001 only, and
  the decision module's none. P2 adds 40P01 **before** any code of this contract runs, because §3's
  exceptions to the lock order rely on it.
- **Every requester runs on the runner** (review 1, finding 11): D1 to D6, the effects worker, the
  resolver (§7.2), the timeout paths' request transactions, `VerificationService.answer` and
  `UnblockReconciler`. M6's raw-connection code in the last four moves onto it. The sink is not a
  requester and keeps its own batch handling (§6.1).
- **What a timeout path does when its request transaction fails** after it has taken a hold from
  Redis or memory (review 2, finding 5). On **any** failure of that hold's request transaction,
  whatever the SQLSTATE or exception, it reschedules that hold 1 s later and counts it. It catches
  per hold, so one failure never loses the rest of a claimed batch: M6's `tick()` today leaves the
  loop on the first exception, and the members it had already removed from Redis are lost until
  the sweep. A hold is dropped only on a REFUSED outcome that §6.3 says to drop.
- **No transaction has an effect outside PostgreSQL before it commits.** Redis, spool, SMS and Kafka
  calls happen after the commit.
- **A1.** Everything runs on the primary.
- **A2.** No committed transaction is lost.
- **A3. The release that brings V81 is deployed by a stop-and-drain cutover, never rolled.** This is
  a runbook requirement for M9, unchanged from the old contract:
  1. stop every spool writer on every instance: ingest, the hold poller and overdue sweep, the
     unblock reconciler, the verification endpoint, the analyst API, and the committer, effects
     worker and recorder. M9's dead-letter replay stays stopped too;
  2. wait until every spool has drained, with the lag gauges at zero;
  3. stop the instances;
  4. run V81;
  5. start only instances of the new release.

  So no writer that bypasses the owner runs after V81, and V81's backfill (§6.4) is complete.

## 3. Locks

**Lock order, everywhere.** A transaction may skip levels but never reverses them:
1. alert queue entry rows (`FOR UPDATE`), in `id` order when there are several;
2. the entry's advisory lock;
3. the analyst decision's advisory lock;
4. **the first-decision lock:** `pg_advisory_xact_lock` on the institution and transaction (§4.2);
5. **the owner row** (`FOR UPDATE`);
6. foreign-key share locks on `users` and `institutions`;
7. the audit chain head, always last.

**Exception 1: a request that closes entries.** A request that closes entries (§5: TIMEOUT, RESOLVE) holds level 5
when it updates the transaction's open entries (level 1). Two cases:
- **Entries that exist when it starts.** It takes them first (level 1, then 5), so these keep the
  order.
- **An entry committed after it started.** Such an entry is a late entry the sink wrote. The
  request's closing UPDATE then locks it after level 5.

A decide on that same late entry, holding level 1 and waiting for level 5, can deadlock with it.
PostgreSQL detects the deadlock and the runner retries the loser. A retried decide then reads the
owner and answers by D1.

**Exception 2: the sink** (review 1, finding 18).
- **Within a batch** it takes levels 4 and 5 per record, so record A's level 5 precedes record B's
  level 4.
- **In §6.2** it inserts an entry after level 4.

No cycle with a request is possible, because a request touches one transaction. Two sinks can
deadlock on two transactions that were each decided twice, in opposite orders. That is 40P01, which
`Tenant.transientError` treats as transient, and the batch is retried.

Nothing else reverses the order.

## 4. The owner

### 4.1 What the row holds

`decision_owners` (V81), one row per `(institution_id, transaction_id)`, mutable, tenant-isolated,
no foreign key to `transaction_ids` (a timeout may create it before the sink runs, §4.2):

| Column | Meaning |
|---|---|
| `first_event_id`, `first_decision`, `first_deadline`, `timeout_policy` | the sequence-1 decision the owner stands on, its review deadline (HOLD only) and its policy |
| `sequence`, `decision`, `decided_by` | the current state: its sequence, value (HOLD, APPROVE, DECLINE, TIMEOUT_RELEASE) and who made it |
| `claim` | NONE, ANALYST or ESCALATED: who is reviewing the transaction now |
| `claim_decision_id` | the analyst decision holding an ANALYST claim; NULL otherwise |
| `deadline` | while `decision` is HOLD: the time the hold runs to, the review deadline or an escalation's longer one. Under an ESCALATED claim on a transaction that is not held: the end of the escalation window |
| `prior_claim`, `prior_deadline` | what an ANALYST claim replaced, so that an undo restores it |
| `contested_since` | NULL, or when two different first decisions were found (§7) |
| `resolution_id` | the last RESOLVE applied to the transaction, or NULL. While it is set, OVERRIDE is refused (§5) |
| `version`, `updated_at` | for audit and tests |

CHECKs:
- `claim_decision_id IS NOT NULL` exactly when `claim = 'ANALYST'`;
- `decision = 'HOLD'` only when `sequence = 1`;
- `deadline IS NOT NULL` when `decision = 'HOLD'` or `claim = 'ESCALATED'`.

`fs_app` has SELECT, INSERT and UPDATE. No other role writes it.

### 4.2 Creating the row

A row is created, at most once, by whichever comes first:
- **the sink,** for a MEDIUM `AlertRaised` (§6.2), or when it finds two first decisions (§7.1);
- **any request,** for a transaction that has no row yet.

**The creation rule.**
1. Take the first-decision lock (level 4).
2. `INSERT … ON CONFLICT DO NOTHING`, then take the row `FOR UPDATE`.
3. **What a new row stands on:**
   - **The sequence-1 row in `decision_states`, if one is visible.**
   - **Otherwise, the request's basis.** Only a timeout carries a basis: the `DueHold`, which holds
     the HOLD's event id, `decided_at`, deadline, reason codes and policy, written by
     `DecisionService` when it schedules the hold. This is option A from the old contract: the
     timeout does not wait for the sink.
   - **Any other request with no visible sequence-1 row is DEFERRED.** It never guesses.
4. **Where `timeout_policy` comes from** (review 1, finding 15):
   - from a sequence-1 row: `decision_states.timeout_policy`, which the sink writes from the
     record's own threshold snapshot (`TransactionDecided`'s thresholds version);
   - from a `DueHold`: the policy it carries, which is the same snapshot;
   - in V81's backfill: `risk_thresholds` in force at `decided_at`, as `JdbcOverdueHolds` derives it
     today.

   V81 adds `decision_states.timeout_policy` (nullable for pre-V81 rows). The configuration
   contract's §3.4 then uses this column rather than adding it.
5. **A `DueHold` scheduled before the cutover** carries no basis fields. `RedisHoldSchedule`'s
   parser accepts both member formats. A TIMEOUT from an old member creates the owner only from a
   visible sequence-1 row, and is otherwise rescheduled 1 s later (review 1, finding 16).

**Why the first-decision lock.** The sink takes the same lock for every `TransactionDecided`, before
it writes `transaction_ids` (§6.1). The two cases:
- **The sink commits first.** A request creating the row waits for the sink's commit, then sees its
  sequence-1 row.
- **The row is created first.** The sink waits for that commit, then sees the row and compares
  (§7.1).

So no first decision can reach PostgreSQL unseen by an owner that already exists. This closes the
route in review 8's MAJOR 1, in which a timeout acted from a `DueHold` while PostgreSQL held a
different first decision.

### 4.3 How a request is evaluated

Every request runs inside the caller's runner transaction:
1. **Lock the row.** Take the owner row (level 5), creating it by §4.2 if absent.
2. **Then find the request's id** (review 1, finding 9). If `transaction_requests` (§4.4) already
   holds a row with this request's id, the stored outcome is returned and nothing else happens.
   Because the lookup follows the lock, a concurrent re-run waits and then finds the first run's
   row. A unique violation on `request_id` (possible only for CUSTOMER_NO, which may run without a
   row) is treated as a re-run: the stored outcome is read and returned. A retried or re-run request
   is therefore applied at most once.
3. **Evaluate.** Apply §5's table to the row as locked.
4. **Write the outcome:**
   - **APPLIED:** a `transaction_requests` row with the new state (sequence + 1) and its full
     `event_body`. The owner row is updated. The side writes that §5 names are made.
   - **LABEL_ONLY:** a `transaction_requests` row with the label and no state.
   - **REFUSED:** a `transaction_requests` row with the reason, for requests §5 marks as recorded.
     Otherwise nothing is written, and the caller gets the reason.
   - **DEFERRED:** nothing is written. The caller retries later (§5 names who).

The procedure is Java in the decision module (`DecisionOwner`), called by every requester inside
its own transaction, **on the caller's transaction-bound connection**, which it takes as an
argument and never opens itself (review 3, finding 5). It is not a database function, so that its
rule table is tested as plain code.

**Where each part lives** (review 3, finding 5):
- **`decision`:** `DecisionOwner`, the timeout paths, the effects worker, the recorder and the
  alarms;
- **`notify`:** `VerificationService` and `UnblockReconciler`. It already depends on `decision`;
- **`analyst`:** D1 to D6 and the resolver. It gains a dependency on `fraudshield-decision`, beside
  `fraudshield-audit`, which the resolver needs for the audit chain. `decision` depends on neither,
  so no cycle forms.
A database trigger on `transaction_requests` refuses an APPLIED row whose sequence is not the owner
row's sequence + 1, as a backstop.

### 4.4 `transaction_requests`, the record of everything after sequence 1

`transaction_requests` (V81), append-only, tenant-isolated, no foreign key to `transaction_ids`.

**Columns:**
- `institution_id`, `transaction_id`;
- `request_id` (unique across institutions), `kind` (§5);
- `outcome` (APPLIED, LABEL_ONLY, REFUSED, RELEASED or PRE_V81), `reason`. RELEASED is a
  RELEASE_FAILED that succeeded: it has no event and is never recorded (review 3, finding 3);
- `sequence`: set only for APPLIED, and unique per transaction when set;
- `event_id` (unique), `decided_by`, `decided_at` (DB time);
- `event_body`, a jsonb object holding everything a re-record needs:
  - the `DecisionState`, when APPLIED;
  - the channel and the actor;
  - the label, when there is one: `label_id`, label, source, `supersedes_label_id` and
    `transaction_timestamp`.

**What it replaces.** It replaces the old contract's `transaction_transitions`.
- **The backfill.** V81 backfills it from every `decision_states` row with sequence > 1. Each
  backfilled row is APPLIED, with the row's event id, request id `backfill|` + that event id (a
  prefix no live request uses, so it cannot collide with a PRE_V81 marker; review 3, finding 7), and
  a recording row already written, so it is never re-recorded.
- **From then on,** `decision_states` receives sequence 1 only. A CHECK `(decision_sequence = 1)
  NOT VALID` refuses any stray later write.

**PRE_V81 markers** (review 1, MAJOR 3; corrected after review 2, MAJOR 1). V81 writes one row with
outcome PRE_V81, request id `verification|` + the response's `verification_id`, no event and no
label, never recorded, for each customer response at the cutover **whose effect is complete or
moot**:
- a NOT_ME whose transaction has a CUSTOMER label in `label_events`. A NOT_ME whose label M6 failed
  to spool gets no marker, and the reconciler submits its CUSTOMER_NO after the cutover (review 3,
  finding 6);
- a WAS_ME whose transaction has a CUSTOMER_VERIFICATION state;
- a WAS_ME whose transaction's latest state is no longer DECLINE by MODEL or TIMEOUT_POLICY.

**A WAS_ME that M6 recorded but never applied gets no marker.** That is one still under DECLINE by
MODEL or TIMEOUT_POLICY, with no CUSTOMER_VERIFICATION state: its transition failed before the
cutover, or PB-156 starved it.
- **After the cutover** the reconciler submits it like any deferred answer, and §5 applies it once.
- **Its `unblock_events` row already exists,** written by M6. CUSTOMER_YES therefore writes the
  unblock row `ON CONFLICT (auto_block_event_id) DO NOTHING` (§5).

The join is response (keyed by `verification_id`; V5's responses have no other id) → verification →
auto-block → transaction.

**`transaction_request_recordings`** (append-only; `event_id` primary key, `recorded_at`) marks a
request whose spool record has been written.

### 4.5 Request ids

Every request id, event id and label id is the name-based UUID of a stable source that is unique
across institutions:

| Request | Request id from | Label id from |
|---|---|---|
| EFFECT (analyst decision) | `effect|` + decision id | `alert_label|` + decision id |
| OVERRIDE | `override|` + override's decision id | `alert_label|` + override's decision id |
| TIMEOUT | `hold_timeout|` + institution + `|` + transaction | none |
| CUSTOMER_YES, CUSTOMER_NO | `verification|` + the response's `verification_id` (one response per verification, V5) | `verification_label|` + the same |
| RESOLVE | `resolve|` + the resolution's id | `resolve_label|` + the resolution's id |

- **The event id** of an APPLIED or LABEL_ONLY row is derived from the request id (`event|` +
  request id).
- **One timeout per transaction.** The timeout's request id names only the institution and the
  transaction, so at most one timeout is ever applied to a transaction, whichever path asks.
- `DecisionState.next` takes the event id and `decided_at` as arguments.

### 4.6 Recording and publishing

- **The writer records.** After the request's transaction commits, the writer records the stored
  `event_body` of each APPLIED or LABEL_ONLY row through the spool. Then it inserts the recording
  row (`ON CONFLICT DO NOTHING`).
- **The recorder completes what a writer left.** Every instance runs the recorder every P. It lists
  rows older than 30 s with no recording row, and records each stored body the same way. A writer
  that crashed after its commit is therefore always completed. A re-record is byte-identical.
- **Receivers ignore repeated event ids.** The feature store's last-arriving label is the exception
  (§11, carried).
- **The Redis state cache is set after recording, best effort,** with a set-if-higher script, on
  Redis directly. A failure is counted and not retried. No read trusts Redis while PostgreSQL
  answers (§8), so a missing or stale cache entry matters only during a PostgreSQL outage.

### 4.7 `v_decision_states`, what every reader reads

`v_decision_states` is `decision_states` (sequence 1) together with the APPLIED rows of
`transaction_requests`, in the same columns. It is a `security_invoker` view, granted to `fs_app`,
`fs_app_readonly` and `fs_compliance_ro`. The SECURITY DEFINER listers read the base tables as their
owner.

**The readers that move to it:**
- `JdbcDecisionStates` and the bank-facing poll (§8);
- `JdbcOverdueHolds` (§6.3);
- `UnblockReconciler` (§6.5);
- `CustomerSmsSender`'s classification;
- P1's account history (`AccountQueries`).

`JdbcIdempotency` reads sequence 1 only, and keeps reading `decision_states`.

## 5. The rule table

The owner evaluates each request against the row it has locked. **Two checks apply first:**
- **Contested.** If `contested_since` is set (§7.3):
  - TIMEOUT is REFUSED, and the hold is dropped;
  - CLAIM, ESCALATE and OVERRIDE answer 409 `decision-contested`;
  - EFFECT and CUSTOMER_YES are DEFERRED;
  - RELEASE, RELEASE_FAILED, CUSTOMER_NO and RESOLVE proceed. An undo or a release only gives a
    claim back, and a "no" moves no state.
- **No visible sequence-1 state.** If the row could not be created (§4.2), the request is DEFERRED.
  CUSTOMER_NO needs no row: its label is written as LABEL_ONLY either way.

| Request | From | Applies when | Outcome and writes | Otherwise |
|---|---|---|---|---|
| **TIMEOUT** (basis: the hold's first decision and policy). Checked in this order: contested; basis against `first_decision`; HOLD; claim; deadline (review 1, finding 5) | the poller, the local fallback, the overdue sweep | `decision` = HOLD; `claim` ≠ ANALYST; DB time ≥ `deadline`; the basis's decision value equals `first_decision` | APPLIED: HOLD → TIMEOUT_RELEASE or DECLINE by TIMEOUT_POLICY, from `timeout_policy`. `claim` → NONE. Closes every open entry of the transaction, and every entry whose analyst decision's effect is FAILED and released, as AUTO_RELEASED or AUTO_DECLINED, `version + 1`, and writes one AUTO_RELEASED or AUTO_BLOCKED decision row on the newest, with an idempotency key from the request id | **DB time < `deadline`:** REFUSED, reschedule at `deadline`. **Claim ANALYST, or not HOLD:** REFUSED, drop. **Basis value ≠ `first_decision`:** the basis is recorded as a second first decision (§7.1), and the hold is dropped. Nothing is written to `transaction_requests` |
| **CLAIM** (an analyst decision) | D1 | `claim` is NONE or ESCALATED; for HOLD, DB time < `deadline` | `claim` → ANALYST, `claim_decision_id` set, the replaced claim and deadline kept in `prior_claim`, `prior_deadline`. No request row | 409 by D1 |
| **RELEASE** (an undo) | D2 | `claim_decision_id` is this decision | `claim` and `deadline` restored from `prior_*`. No request row | nothing to do |
| **EFFECT** (a committed analyst decision) | the effects worker, D4 | `claim_decision_id` is this decision | See EFFECT's rows below. `claim` → NONE | **The claim was cleared by a RESOLVE:** LABEL_ONLY, reason `claim-lost`. The analyst's label is recorded and its state is not applied (review 1, finding 8) |
| **ESCALATE** | D6 | `claim` is NONE, or ESCALATED with DB time ≥ `deadline` on a payment that is not held (both set ESCALATED, `deadline` = for HOLD, DB time + W, never earlier than the current `deadline`; otherwise DB time + W: a lapsed window opens afresh, review 3, finding 9), or ESCALATED before its deadline (unchanged: one window covers the chain). For HOLD, DB time < `deadline` | `claim` → ESCALATED. No request row | 409 `stale-alert` |
| **OVERRIDE** (value: the senior's decision) | D5 | `claim` ≠ ANALYST and `resolution_id` IS NULL | See OVERRIDE's rows below | **Claim ANALYST whose effect is pending:** 409 `decision-not-committed`. **Claim ANALYST whose effect FAILED:** 409 `decision-effect-failed`; an operator releases it first (§7.2) (review 3, finding 1). **Resolved:** 409 `stale-alert`; a resolution is final through the API (review 3, finding 2) |
| **CUSTOMER_YES** | `VerificationService`, `UnblockReconciler` | see below | see below | see below |
| **CUSTOMER_NO** | the same | always, contested included | LABEL_ONLY: a FRAUD label, source CUSTOMER | none |
| **RESOLVE** (value: APPROVE or DECLINE) | the resolver, §7.2 | `contested_since` equals the resolution's recorded `contested_since` | APPLIED: the current state → value by SENIOR_OVERRIDE, reason code `CONTEST_RESOLVED`, whatever the current value. `contested_since` → NULL, `claim` → NONE. Closes open entries as OVERRIDDEN. A FRAUD or LEGITIMATE label. On → APPROVE of an auto-blocked transaction with no unblock yet, an `unblock_events` row (cause SENIOR_OVERRIDE, actor the resolving officer) | REFUSED, recorded, reason `contest-changed`: the transaction is no longer contested, or was contested again after the resolution was made. The alarm stays on until a fresh resolution |
| **RELEASE_FAILED** (an ANALYST claim whose effect FAILED) | the resolver, §7.2 | `claim_decision_id` is the decision, and its effects row is FAILED | RELEASED, recorded with no event: `claim` and `deadline` restored from `prior_*`, as an undo would. No state. A re-run returns the stored RELEASED (§4.3). After the commit the resolver reschedules a held payment's hold at `deadline`, as D2(e) does, so it is timed out at its deadline, or on the poller's next tick if that has passed (review 2, finding 6). The entry keeps the analyst's status, with the FAILED effects row as its record; a later TIMEOUT closes it (above), and a payment that is not held can then be overridden (D5) | REFUSED, recorded, reason `not-failed` |

**EFFECT, by the analyst decision and the current state:**

| Current state | CONFIRM_FRAUD | MARK_LEGITIMATE |
|---|---|---|
| HOLD | APPLIED: → DECLINE by ANALYST, FRAUD label | APPLIED: → APPROVE by ANALYST, LEGITIMATE label |
| DECLINE (any maker) | **APPLIED: DECLINE → DECLINE by ANALYST**, FRAUD label. A human confirms the decline, and it is a state | LABEL_ONLY: LEGITIMATE label. The block stays (Q1) |
| APPROVE by CUSTOMER_VERIFICATION | **APPLIED: → DECLINE by ANALYST**, FRAUD label. A human overrules a customer's "yes" | LABEL_ONLY |
| APPROVE by anyone else, or TIMEOUT_RELEASE | LABEL_ONLY: FRAUD label. Re-declining a payment an analyst, a senior or the policy approved is a senior's call (OVERRIDE) | LABEL_ONLY |

**OVERRIDE, by the senior's decision and the current state:**

| Current state | to CONFIRM_FRAUD | to MARK_LEGITIMATE |
|---|---|---|
| DECLINE | **APPLIED: DECLINE → DECLINE by SENIOR_OVERRIDE.** The senior's decline is a state even though the value does not change (MAJOR 3) | APPLIED: → APPROVE by SENIOR_OVERRIDE, with an `unblock_events` row (cause SENIOR_OVERRIDE) when an auto-block exists and has no unblock yet |
| APPROVE or TIMEOUT_RELEASE | APPLIED: → DECLINE by SENIOR_OVERRIDE | LABEL_ONLY |
| HOLD | 409 `stale-alert`. Reachable only after RELEASE_FAILED, which leaves a held payment HOLD with a committed original (review 3, finding 4). The hold is decided by its timeout, at its deadline | 409 `stale-alert`, likewise |

Every OVERRIDE outcome carries a label by SENIOR_OVERRIDE whose `supersedes_label_id` is the
original's label.

**CUSTOMER_YES, in order:**
1. **A claim is pending.** If `claim` is ANALYST, or ESCALATED with DB time < `deadline`, the request
   is **DEFERRED**. A human is reviewing the payment, and the customer's "yes" waits for that
   human's decision.
2. **A human declined it.** If `decision` is DECLINE and `decided_by` is ANALYST or SENIOR_OVERRIDE,
   the request is **REFUSED and recorded,** reason `human-declined`, with no label.
   - The customer path can confirm a payment the system doubted, but cannot overturn a human who
     declined it (the owner, 2026-10-20). This is deliberate.
   - A customer's "yes" is exactly what a compromised account would send, so no LEGITIMATE label is
     recorded.
3. **Declined by the model or the policy.** If `decision` is DECLINE, by MODEL or TIMEOUT_POLICY, the
   request is **APPLIED:** → APPROVE by CUSTOMER_VERIFICATION, with a LEGITIMATE label. The
   `unblock_events` row (cause CUSTOMER_VERIFICATION) is written in the same transaction, and only
   here, `ON CONFLICT (auto_block_event_id) DO NOTHING`: a pre-V81 answer's row already exists.
4. **Already approved.** If `decision` is APPROVE or TIMEOUT_RELEASE, the outcome is **LABEL_ONLY**: a
   LEGITIMATE label, and no unblock row.

**The block record after a re-decline** (review 1, finding 17). `unblock_events` is append-only,
so a customer's lift stays recorded after a human re-declines. V81 therefore adds
`currently_blocked` to `v_auto_block_status`: true when the transaction's latest state in
`v_decision_states` is DECLINE. `unblocked_at` keeps meaning "first lifted at".
`CustomerSmsSender`'s "resolved" test already treats a lifted block as resolved, so a re-decline
sends no new SMS.

**Why the human rules hold in every order.** All three human rules are decided under the one row
lock:
- a human's fraud decision always produces a state (EFFECT and OVERRIDE above);
- the customer's "yes" waits while a human holds a claim;
- a human's fraud decision applied after a customer's "yes" overrules it: APPROVE by
  CUSTOMER_VERIFICATION → DECLINE is allowed to an analyst and to a senior.

Review 8's MAJOR 3 interleaving therefore ends declined:
1. The analyst marks the payment legitimate: LABEL_ONLY, and the claim is cleared.
2. The senior confirms fraud: DECLINE → DECLINE by SENIOR_OVERRIDE.
3. The customer's "yes" is REFUSED as human-declined.

### 5.1 The transitions this adds to `FinalDecision`

`DecisionState.allowed` becomes exactly the transitions §5 can apply. **Seven are new** (review 1,
finding 7), and each is a change to the frozen contract's `FinalDecision` description, proposed with
P2 (§11):
1. DECLINE → DECLINE by ANALYST (a human confirms a decline);
2. DECLINE → DECLINE by SENIOR_OVERRIDE (the same, by a senior);
3. APPROVE → DECLINE by ANALYST, only when the APPROVE was by CUSTOMER_VERIFICATION (a human
   overrules a customer);
4. to 7. HOLD → APPROVE, HOLD → DECLINE, APPROVE → APPROVE and TIMEOUT_RELEASE → APPROVE, by
   SENIOR_OVERRIDE, only for RESOLVE (reason code `CONTEST_RESOLVED`). RESOLVE's other cases
   (DECLINE → APPROVE, DECLINE → DECLINE, APPROVE or TIMEOUT_RELEASE → DECLINE) are already allowed
   to SENIOR_OVERRIDE.

**What the code needs for them:**
- **`allowed`'s signature widens** to `allowed(from, fromBy, to, by, resolution)`. Today's
  `allowed(from, to, by)` cannot see the previous maker (needed for 3) or that a request is a RESOLVE
  (needed for 4 to 7).
- **The OpenAPI description states the conditions in words,** because `supersedes_decision` carries
  no maker.
- **`FinalDecision.final`'s text** ("superseded by … customer verification or senior override")
  gains the analyst.
- **`KafkaMessages.changeAction`** labels an ANALYST DECLINE from DECLINE or APPROVE as a
  confirmation or re-decline, not `HOLD_DECLINED`.
- **The reason code** is `CONTEST_RESOLVED`, which matches the frozen `ReasonCode` pattern.

**A bank can now receive a `decision.final` whose value equals the previous one,** for a human's
confirmation and for a resolution. Its sequence is higher, so the webhook's supersede rule delivers
it.

## 6. The paths that feed the owner

### 6.1 The sink

`PostgresSink` changes as follows:
- **For every `TransactionDecided`, it takes the first-decision lock first** (level 4), before it
  writes `transaction_ids`. It holds that lock until its batch commits.
- **Which records it writes in full, and which it drops** (review 1, MAJOR 1). The sink compares
  event ids before values. "Written in full" means the record's `transaction_ids`, `transactions`,
  `fraud_scores`, sequence-1 `decision_states` row, `account_profiles`, entries, auto-block and
  notification facts, as M6 writes them.
  1. **`transaction_ids` already exists.** Two sub-cases (review 2, finding 3):
     - **(a) The same event id as the kept sequence-1 row:** an at-least-once re-delivery. This is
       M6's `sameFirstDecision` case. It is not dropped, and its writes are idempotent, as today.
     - **(b) Another event id:** M6's duplicate path, where another first record drained first.
       The record is dropped as today and counted. Whether it is a new conflict is decided against
       **the owner first**, if one exists:
       - if its event id is the owner's `first_event_id`, or is already in `decision_conflicts`, it
         is not a new conflict and nothing more happens. This covers the owner's own decision
         draining after case 5;
       - otherwise it is compared by value with the owner's `first_decision`, or, with no owner,
         with the kept sequence-1 row. A different value is a second first decision (§7.1): the sink
         creates the owner from the kept row if absent (§4.2) and records it. The same value is not
         contested (§7.1).
  2. **No `transaction_ids`, no owner row.** The ordinary case: written in full.
  3. **No `transaction_ids`, an owner row whose `first_event_id` is this record's event id.** This is
     the owner's own first decision arriving after a TIMEOUT created the owner from its `DueHold`,
     the normal path after a PostgreSQL outage longer than the hold window. **Written in full**, and
     then §6.2 closes its late entry.
  4. **No `transaction_ids`, an owner row with another event id and the same value.** A same-value
     duplicate whose twin reached the owner only through a `DueHold`. **Written in full**: it is the
     first record of the transaction to reach PostgreSQL, so it becomes sequence 1 in
     `decision_states`. The owner's `first_event_id` is set to it; its `first_decision` is unchanged,
     and its `deadline` stays the one it already governs by. The twin's record, if it ever drains,
     takes case 1.
  5. **No `transaction_ids`, an owner row with a different value.** Review 1's finding 4: the
     reverse order of review 8's MAJOR 1. **Written in full,** because the bank was told this
     decision and PostgreSQL must hold the transaction. It becomes sequence 1 in `decision_states`,
     and the sink records the contest (§7.1) against it.
     - **What the view then shows.** Sequence 1 is this record, and sequence 2 is the TIMEOUT that
       the owner applied from the `DueHold`. The poll answers 503 `decision-contested` until RESOLVE
       (§8).
     - **That history is not a `FinalDecision` chain** (review 2, finding 4). Sequence 2's
       `supersedes_decision` (HOLD) is not sequence 1's decision, which the frozen schema calls a
       server defect. The bank never reads it, because the poll answers 503 while contested and the
       resolution's higher sequence afterwards. P1's account history and exports do read it. The
       exception for a contested transaction's stored history is one of §11's contract additions,
       and §12 lists it.
     - **What the customer is sent.** The auto-block's SMS intent is written, but `CustomerSmsSender`
       classifies a block as resolved when the latest state in `v_decision_states` is not DECLINE.
       After a TIMEOUT_RELEASE it is therefore never sent. After a DECLINE timeout it is sent, which
       matches the payment's state.
     - **If the risk officer later resolves to DECLINE,** the intent was consumed as resolved and
       is not re-requested. The customer receives no SMS and no verification link for that block,
       and the customer path is lost for it. This is a stated cost of case 5 (§11, §12). The bank
       receives the final DECLINE, and its own process informs the customer.
- **It writes `decision_states` for sequence 1 only.** A `DecisionChanged` with sequence > 1 is not
  inserted: its `transaction_requests` row is the record. One with no such row can only be a
  pre-V81 dead letter replayed. It is counted, logged and dropped. The overdue sweep times out a
  hold left open by it, and §4.5's timeout request id makes that a no-op if one was already applied.
- **It no longer closes entries or writes AUTO_* rows.** The owner's TIMEOUT does (§5).
- **It writes `label_events.supersedes_label_id`,** and `LabelRecorded` and `KafkaMessages` carry it.
- **A deadlock (40P01) during a batch is transient.** `Tenant.transientError` already counts class
  40, so the batch is retried, not dead-lettered.
- **Lock budget** (review 1, finding 18). One advisory lock per `TransactionDecided` in a batch is
  held to the batch's commit: at most 200 for M6's batch. The limit is the shared lock table,
  `max_locks_per_transaction` × (`max_connections` + `max_prepared_transactions`), shared by every
  sink and request at once. P2 states the configured figures and the worst case.
- **An exhausted lock table** (SQLSTATE 53200) is class 53, which `Tenant.transientError` retries with
  the same batch. The sink instead halves the batch on 53200, down to one record, so that it cannot
  retry an over-large batch for ever.

### 6.2 Late entries

In the transaction that writes a MEDIUM `AlertRaised`, `PostgresSink`:
1. inserts the entry (open);
2. creates the owner row if absent (§4.2), with `deadline` = the entry's `review_deadline_at`, and
   locks it;
3. if the owner's `decision` is no longer HOLD (a TIMEOUT or a RESOLVE has already applied), it
   closes the new entry at once:
   - after a TIMEOUT: the TIMEOUT's outcome, with the AUTO_* row and its request-derived key;
   - after a RESOLVE: OVERRIDDEN.

**Why no entry is left open.** Either the sink holds the row first, so the TIMEOUT waits and its
UPDATE then sees the committed entry. Or the TIMEOUT holds it first, so the sink waits, then reads
the new state and closes the entry. The AUTO_* row's key is derived from the request, so it is
written exactly once.

### 6.3 The timeout paths

The leader's poller, the local fallback and the overdue sweep are requesters.
- **The poller and the local fallback.** Each `DueHold` they claim from Redis or memory becomes a
  TIMEOUT request, with the `DueHold` as its basis.
- **The overdue sweep.** It lists HOLDs whose owner row is HOLD, unclaimed or escalated, not
  contested, and past `deadline` + 10 s; and HOLD sequence-1 rows with no owner row, past their
  deadline + 10 s. Its basis is the owner's first decision, or the sequence-1 row.
  - V62's `institutions_with_overdue_holds` is changed to the same selection. Today it means "no
    `decision_states` row with sequence > 1", which after V81 would list every institution with a
    timed-out HOLD for good (review 1, finding 16).
- **What the paths do with the outcome:**
  - **APPLIED:** record it (§4.6).
  - **REFUSED with a later deadline:** reschedule the hold at that deadline.
  - **Any other REFUSED:** drop the hold.
- **A Redis-scheduled hold is never cancelled by a request path before it commits.** A decide claims
  in PostgreSQL only, and at the deadline the poller's TIMEOUT finds the ANALYST claim and drops it.
  After an undo commits, the hold is rescheduled at the restored deadline (D2).
- **If PostgreSQL is unavailable,** the poller and the local fallback reschedule the hold 1 s later
  and count it, and the sweep retries on its next run.
  - Held payments stay held until the database returns. This is ADR 0111's decision, and its
    amendment of ADR 0064 and its D-18 deviation stand.
  - The lateness metric measures from the original deadline, which the `DueHold` carries.

### 6.4 V81's backfill

- **`decision_owners`.** V81 creates an owner row for every transaction whose latest state is HOLD,
  and for every transaction with any state after sequence 1.
  - It takes `first_*` from sequence 1, and `sequence`, `decision` and `decided_by` from the latest
    state.
  - Its `claim` is NONE, `deadline` is the HOLD's `review_deadline_at`, and `contested_since` is
    NULL.
- **`transaction_requests`,** as §4.4 says, including the PRE_V81 markers for every existing
  customer response.
- **`decision_states.timeout_policy`** for existing rows, from `risk_thresholds` at `decided_at`
  (§4.2).
- **Analyst decisions.** P2 is the first code to write them, so none are in flight at the cutover.

### 6.5 Customer verification

**`VerificationService.answer` makes the request in the same transaction as the response.** That
transaction:
1. inserts the response;
2. makes the CUSTOMER_YES or CUSTOMER_NO request (§5);
3. writes the `unblock_events` row only when the outcome is APPLIED;
4. commits.

The writer then records the outcome (§4.6).
- **Database unavailable.** If the transaction fails, nothing is stored and the token is not spent.
  The page says to try again. M6 stored the answer first and lifted the block later. That was needed
  only because the transition went through Redis, and it is withdrawn (§12).
- **DEFERRED.** The response is stored and the outcome is not. The page says the answer is being
  reviewed.
- **REFUSED** (human-declined). The page says the payment stays blocked and the bank will contact
  the customer. The refusal answers APPLIED no longer (review 8, finding 6).

**`UnblockReconciler` completes deferred answers.**
- It lists responses older than 10 s, WAS_ME and NOT_ME, that have no `transaction_requests` row.
  A NOT_ME is submitted as CUSTOMER_NO, so a "no" whose label was not recorded has a recorder of
  last resort (the old contract's m9).
- It skips those whose owner is contested or holds a pending claim (ANALYST, or ESCALATED before its
  deadline).
- It submits each by §5.

A deferred answer is re-listed only once its owner can act on it, so contested or claimed
transactions cannot starve the batch (review 8, finding 8).

**V64's `institutions_with_unapplied_unblocks` is rewritten,** not extended (review 2, finding 8).
Today it lists institutions with an `unblock_events` row and no CUSTOMER_VERIFICATION row in
`decision_states`. After V81 no such row is ever written there, so it would list every institution
with any unblock, for good. It becomes the same response-based selection as the reconciler's.

## 7. Contested: two different first decisions

### 7.1 Detection

M6 can decide one transaction twice. ADR 0067 points 3 and 4 accept it:
- a process dies inside its 10-second idempotency lease, and the retry decides afresh;
- two first submissions race while Redis is down.

**Two first decisions are different when their decision values differ.** Two HOLDs, two APPROVEs or
two DECLINEs are the same value. The owner stands on the first it saw, and its deadline and policy
govern.
- **The cost, stated.** A bank told the other HOLD's deadline can see the final decision up to the
  lease plus the retry delay away from the deadline it was told. Two DECLINEs carry the same
  auto-block ids, because those are derived from the submission.

**Detection happens under the owner row's lock, in one of two places:**
- **The sink** (§6.1), when a sequence-1 record with a different value meets an existing owner or an
  existing `transaction_ids` row.
- **The TIMEOUT request** (§5), when its basis is a `DueHold` with a different value from the
  owner's first decision. This is review 8's MAJOR 1 route.
  - It is detected even if the other instance's spool is never drained, because the `DueHold` itself
    carries the evidence.

**What detection writes:**
- **`decision_conflicts`** (append-only, tenant-isolated, primary key `(institution_id,
  transaction_id, event_id)`): one row per distinct first decision the owner did not stand on, with
  its value, deadline and `recorded_at`;
- **the owner row:** `contested_since` = DB time, **only when the `decision_conflicts` insert
  inserted a row** (review 1, finding 6). A conflicting decision already recorded, whether by its
  `DueHold` and then by its own record draining, or by an at-least-once re-delivery of a batch,
  inserts nothing and does not re-contest a resolved transaction. A genuinely new first decision
  does.

Both are written in the detecting transaction, under the owner row's lock, so every request either
sees the contest or committed before it (review 8, finding 7). Detection means that commit.

### 7.2 The exit

**A contested transaction leaves that state only through RESOLVE.**
- **Who resolves, and where the rule table runs** (review 1, MAJOR 2). The decision is split in
  two, so that the rule table stays in one place:
  - **Authorising and recording, in SQL.** A RISK_OFFICER of the institution calls the SECURITY
    DEFINER function `resolve_contested_decision(transaction_id, decision, staff_user_id, reason)`.
    It checks the role, reads the owner row `FOR UPDATE` and refuses unless it is contested, then
    inserts a `decision_conflict_resolutions` row: id, transaction, kind (`RESOLVE` or
    `RELEASE_FAILED`), `decision_id` (for `RELEASE_FAILED`), chosen value (for `RESOLVE`), the
    owner's `contested_since` at that moment, the staff user, the database login, reason and DB
    time. Its audit entry, recording the authorisation, is written in the same transaction. **It
    evaluates no rule and writes no state.**
  - **Who the actor is** (review 2, finding 7). The function cannot authenticate the officer: it
    takes `staff_user_id` from its caller. It therefore records both the asserted staff user and
    `session_user`, the database login that called it. Accountability rests on M9's runbook giving
    each operator an individual database login that is granted the function, never a shared one.
    The officer's identity is asserted, not proven, and that is a stated limitation (§11).
  - **Applying, in Java.** The **resolver** runs on every instance every P, on the runner. It lists
    resolutions with no `transaction_requests` row for `resolve|` + their id, and makes each one's
    RESOLVE request through `DecisionOwner` (§5). In the same transaction it writes the audit entry
    for the state change, naming the claims it cleared. Then it records the outcome (§4.6), as every
    writer does.
  - **The same pair releases a FAILED claim.** `release_failed_claim(decision_id, staff_user_id,
    reason)` inserts a resolution of kind `RELEASE_FAILED`, which the resolver applies as §5's
    RELEASE_FAILED (review 1, finding 13).
  - **Grants.** Both functions are granted to the operator role that M9's runbook uses.
  - **Not an API operation.** Adding one would be another change to the frozen contract.
  - **The delay.** The bank receives the resolution about P plus the spool's lag after the function
    commits. A crash between the two is completed by the next resolver run, and the recorder covers
    a crash after the request commits.
- **What RESOLVE does.** It writes a new state, the current sequence + 1, by SENIOR_OVERRIDE, with
  the chosen value (§5).
  - **The bank.** That sequence is higher than anything either first decision led to, so the webhook
    and Kafka deliver it as the final word, and the poll answers it.
  - **Entries.** Open entries are closed as OVERRIDDEN, with a new column
    `alert_queue_entries.resolution_id` naming the resolution. No `alert_decisions` row is written:
    V4 requires a SENIOR_OVERRIDE row to name an overridden decision and carry a comment, and a
    resolution may have neither.
  - **Finality is enforced on the owner, not the entry** (review 3, finding 2). RESOLVE sets the
    owner's `resolution_id`, and OVERRIDE is refused while it is set (§5), whichever entry it comes
    from: an entry RESOLVE closed, a late entry §6.2 closed, or an analyst's entry whose claim
    RESOLVE cleared. So **a resolution is final through the API**; a further change needs another
    operator action, which is a stated cost (§11). A later contest and resolution replace
    `resolution_id`.
  - **The label** has source SENIOR_OVERRIDE, the only one of V5's four sources that fits.
  - **A pending analyst decision.** RESOLVE clears its claim, so its EFFECT later ends LABEL_ONLY,
    reason `claim-lost`. The analyst's label is recorded, and the state the analyst chose is not
    applied. The RESOLVE request row's `event_body` names each claim it cleared, and the resolver
    audits them.
  - **The block record.** RESOLVE to APPROVE of an auto-blocked transaction with no unblock writes
    the `unblock_events` row (§5).
  - **Deferred customer answers** are then submitted by the reconciler and meet the resolved state.
    After a DECLINE, the "yes" is human-declined.
- **A third first decision** arriving after a resolution makes the transaction contested again.

### 7.3 While contested

**Every owner request but CUSTOMER_NO and RESOLVE is deferred or refused:**
- **TIMEOUT.** It is refused and the hold is dropped. The alarm (§7.4) carries the transaction.
- **EFFECT.** It is DEFERRED, with no effects row written. The effects worker skips contested
  owners when it selects (D4(a)), and resumes after the resolution. RESOLVE clears the claim, so the
  effect then ends LABEL_ONLY: the analyst's label is kept, and the decision is named in the
  resolution's record. Draft 8 instead wrote a terminal SKIPPED that nobody saw (review 8, MAJOR 2).
  So the work is deferred, then reduced to its label and named, never silently discarded.
- **CLAIM, ESCALATE and OVERRIDE** answer 409 `decision-contested`.
- **CUSTOMER_YES** is DEFERRED.
- **The poll** answers 503 `decision-contested` (§8).

**What still acts, as an accepted residual (review 8, finding 6).** Each of these publishes something
that happened before detection, and none moves the owner's state:
- the auto-block SMS and its link for the kept decision;
- webhook deliveries already PENDING;
- the recorder publishing requests applied before detection;
- E2's breaker count for a decision committed before detection;
- both first decisions on Kafka (M6's).

**The residual before detection: M6's double decision in reverse order** (review 1, MAJOR 4;
inherited from M6, recorded and scoped). A path that acted before detection has acted.
- **The interleaving.**
  1. Instance 1 decides HOLD X, schedules its `DueHold`, and dies before X drains.
  2. The bank retries after the lease. The retry is verified against PostgreSQL (§7.5), finds
     nothing, and decides afresh. Say it decides DECLINE Y, and the bank is told DECLINE.
  3. Y has not drained when X's deadline passes.
  4. X's TIMEOUT creates the owner from the `DueHold` and applies TIMEOUT_RELEASE. The webhook sends
     a release to a bank that was told DECLINE.
  5. Y drains, and §6.1 case 5 makes the transaction contested.
- **Its real frequency: not rare.** Step 3 is the normal state of the system just after a PostgreSQL
  outage longer than the hold window:
  - the sinks drain a backlog in batches;
  - the leader's poller fires every overdue `DueHold` on its first tick;
  - during the outage itself, the retry in step 2 cannot be verified and is "decided unverified"
    (ADR 0067 point 6).

  So any PostgreSQL outage during which an instance also dies with undrained HOLDs and banks retry
  produces this case, for every such transaction. Outside an outage it needs a sink lagging by more
  than the hold window less the lease and the bank's retry delay: Y is decided at least a lease
  (10 s) plus the retry delay after X, so about 20 s for a 30-second hold, not the whole window
  (review 2, finding 2). **No measurement exists** of how often instances die inside a lease, how
  often banks retry, or how long outages last, so the number of such transactions cannot be stated
  from evidence.
- **What resolves it.** The contest alarm fires when Y drains, and RESOLVE's new state supersedes
  both at the bank. The payment may already have moved, because the bank acted on the release.
- **An option not taken: Redis as a detector.** Before creating an owner from a `DueHold`, the
  TIMEOUT could compare the `DueHold`'s event id with the Redis state cache's sequence-1 event id,
  which Y wrote at decision time. On a mismatch it would defer or record the contest. That would
  close step 3 except during a Redis outage. It reads Redis as evidence, not as the state, but it
  is a new dependency on the cache that ADR 0113 retired from the read path. It is left to the owner
  (§11).
- **The one case never detected (M6's, inherited).** The other decision's record never reaches
  PostgreSQL, and no `DueHold` carries it either: the retry's instance loses its spool volume
  before draining. PostgreSQL is then consistent with itself, and the bank holds an answer
  PostgreSQL never saw. This is ADR 0064's accepted spool-loss limitation (§11).
- **Undetected until replayed** (review 1, finding 12). If the other decision's record is
  dead-lettered, it is detected only when M9's dead-letter replay brings it to the sink.

### 7.4 Alarms

Each instance computes these from PostgreSQL every 30 s, without a leader lease, through
SECURITY DEFINER listers (§14). Each has an alert condition, which M9's rules implement:
- **`fs_decisions_contested`:** the count, and the oldest `contested_since`. Alert: any value above
  zero.
- **`fs_holds_overdue_oldest_seconds`:** the oldest HOLD past its deadline + 15 s, from owner rows
  and from sequence-1 HOLDs with no owner row. It covers every way a hold can outlive its deadline:
  an ANALYST claim whose effect has not run, a contest, a lost schedule entry, or a lease outage.
  Alert: any value above zero.
- **`fs_customer_answers_deferred_oldest_seconds`:** WAS_ME responses with no request row and no
  pending claim or contest. Alert: above 60 s. An answer waiting on a contest is covered by the
  contested alert; on a claim whose effect is pending, it is bounded by G + P and the pending-effects
  alert; on a claim whose effect FAILED, by the failed-claims alert; on an escalation, by W.
- **`fs_analyst_effects_oldest_pending_seconds`:** committed analyst decisions with no effects row.
  Alert: above 60 s.
- **`fs_analyst_claims_failed`:** the count, and the oldest, of ANALYST claims whose decision's
  effects row is FAILED. Alert: any value above zero. It covers a payment that is not held, which
  the overdue gauge cannot see: an auto-blocked payment whose customer's "yes" is deferred behind a
  failed claim (review 3, finding 1).
- **`fs_requests_unrecorded_oldest_seconds`:** the oldest APPLIED or LABEL_ONLY request with no
  recording row. Alert: above 60 s. While it fires, the poll is right but the webhook and Kafka have
  not been sent the state (review 3, finding 8).

**So every payment held past its deadline + 45 s is visible in the overdue gauge, while PostgreSQL
answers,** whatever holds it: 15 s of grace plus up to 30 s of sampling (review 1, finding 12). That
bounds when the gauge shows it, not when someone is paged: the scrape, the rule evaluation and any
`for:` duration are M9's and are not counted here (review 2, finding 9). The exceptions:
- **A PostgreSQL outage.** No gauge can be computed then, and ADR 0111 holds every payment. The live
  signal is the timeout paths' count of rescheduled requests (§2, §6.3), which every instance
  computes in memory. M9 alerts on it (ADR 0111).
- **A hold with neither an owner row nor a sequence-1 row** (§11).

### 7.5 The idempotency lease (option 1, kept from the owner's decision on review 7)

A retry should not decide afresh while an earlier decision may still drain. Review 8 (finding 4)
showed M6's claim cannot tell an expired lease from a first submission, so the mechanism is
specified here:
- **The INFLIGHT record outlives its lease.** It carries `lease_until`, and its TTL becomes the
  record's 24 h.
- **An INFLIGHT record past `lease_until` is UNCERTAIN.** It is verified against PostgreSQL as
  `ResilientIdempotency.verify` already does: a replay if PostgreSQL holds the decision, otherwise
  the retry decides.

**What the bank sees.**
- **No new 503s.** InFlight arises only while `transaction_ids` exists without its sequence-1 row.
- **Up to about 3 s added** to such a retry (ADR 0067 point 8).
- **A first attempt that is slow, not dead,** past 10 s, is also treated as UNCERTAIN. The owner
  detects whatever double decision follows.

ADR 0114 amends ADR 0067 points 3 and 4 accordingly.

## 8. The final decision a bank reads

`GET /api/v1/decisions/{transaction_id}` follows ADR 0113, with the owner added:
1. **PostgreSQL answers, the owner is contested:** 503 `decision-contested`, with `Retry-After`.
2. **PostgreSQL answers, `v_decision_states` holds the transaction:** the highest sequence.
3. **PostgreSQL answers, the view holds nothing:** Redis's state, written at ingest. The model's
   decision reaches PostgreSQL through the sink, so a transaction can be in Redis before the view.
   Every later state is committed to PostgreSQL before it is published, so a transaction absent from
   the view has no later state anywhere.
   - **Two first decisions not yet detected.** If Redis holds the retry's decision and the other is
     still on a spool, the answer can change from Redis's value to the view's once a record drains.
     That change is detected at that moment (§7.1) (review 8, finding 9).
4. **PostgreSQL unavailable:** Redis's state.
   - Redis cannot fall behind during the outage, because no request runs without PostgreSQL.
   - It can be stale from before the outage, for at most the outage's duration.
   - A contested transaction is answered from Redis too, because the contest cannot be read.
   - "Unavailable" means SQLSTATE class 08, a statement timeout (57014), or the pool's connection
     timeout. Any other error is a 500.
5. **PostgreSQL unavailable and Redis holds nothing, or is down:** 503, never 404.
6. **404** only when PostgreSQL answers and neither the view nor Redis holds the transaction.

## 9. Analyst operations

The commit and undo mechanics are carried from the old contract, where review 8 found them sound.
Its §3 schema for `alert_decisions`, the commits and the undos is kept:
- `undo_until` and `created_at` set by trigger;
- `prior_status`, `alert_version`, `request_fingerprint` and `override_decision`;
- the commit and undo triggers on DB time;
- the one-live-decision backstop;
- `v_alert_decision_state`;
- `alert_decision_effects`, whose outcomes are APPLIED, LABEL_ONLY, SKIPPED and FAILED.

`hold_owned`, `prior_hold_owner` and `prior_hold_deadline` are not added: the owner row holds them.
`alert_queue_entries.status` admits `AUTO_DECLINED`.

**D1. Deciding** (`decideAlert`), one runner transaction:
- **(a)** The entry `FOR UPDATE`, then its advisory lock. No row: 404.
- **(b) Idempotency.**
  - The same key, caller, alert and fingerprint return the original 202, whatever has happened
    since.
  - Anything else with the same key is 409 `idempotency-conflict`.
  - A unique-key collision at insert re-runs D1 once.
- **(c)** Escalated, and the caller ranks below its target: 403.
- **(d)** `alert_version` ≠ `version`, or the entry is not open: 409 `stale-alert`.
- **(e) CLAIM** (§5), for every tier.
  - For HOLD, DB time ≥ `deadline`, or the owner is not HOLD any more: 409
    `review-deadline-passed`.
  - Contested: 409 `decision-contested`.
- **(f)** Insert the decision, set the entry's status, `version + 1`, and audit `DECIDED`.
- **(g)** Commit, and answer 202.

**D2. Undoing** (`undoAlertDecision`), one runner transaction:
- **(a)** The entry row and its lock. The decision must be this entry's analyst decision (404) and
  the caller's (403).
- **(b)** A repeated undo answers 204, and still reschedules.
- **(c)** Insert the undo row. If the trigger refuses it: 409 `undo-window-expired`.
- **(d)** RELEASE (§5). Restore `prior_status`, `version + 1`, and audit `UNDO`.
- **(e)** After the commit, if the owner is HOLD: reschedule the hold at the restored `deadline`. A
  past deadline is timed out on the poller's next tick. If the reschedule fails, the sweep acts 10
  to 15 s after the deadline.

**D3. Committing.** Every instance runs the committer every P. For each due analyst decision, oldest
first, it runs one transaction:
- `pg_try_advisory_xact_lock` on the decision (level 3), skipping it if held;
- the commit row, `ON CONFLICT DO NOTHING RETURNING`;
- the audit `COMMITTED`.

Bound: `undo_until + P + d` while PostgreSQL is up.

**D4. Effects.** Every instance runs the effects worker every P, without a lock.
- **(a) What it selects.** Committed analyst decisions with no effects row, oldest commit first,
  excluding those whose owner is contested (review 1, finding 14). Their age still shows in
  `fs_analyst_effects_oldest_pending_seconds`.
- **(b) For each, one transaction:**
  - the EFFECT request (§5);
  - then the effects row:
    - **APPLIED or LABEL_ONLY,** with the outcome;
    - **REFUSED,** as SKIPPED with the reason;
    - **DEFERRED:** none, so it is selected again.
- **(c) After the commit:** record the outcome (§4.6).
- **(d) E2, the breaker.** For a CONFIRM_FRAUD of a MEDIUM or ANOMALY entry, never HIGH, one Lua
  step: `SET NX fs:mcc:confirmed:<inst>:<txn>` (25 h), then `HINCRBY`. It runs on Redis directly, and
  it is the only part that needs Redis.
  - **Its record.** Success inserts `alert_decision_breaker_counts(decision_id)`, which is
    append-only.
  - **Its retry.** The worker also selects every CONFIRM_FRAUD decision, analyst or override, of a
    MEDIUM or ANOMALY entry, whose effects row is APPLIED or LABEL_ONLY and which has no breaker row.
    A decision whose effect was SKIPPED or FAILED is never counted (review 2, finding 6). A failure is
    retried on the next run, and `SET NX` makes the retry count once.
- **(e) Permanent failure** (SQLSTATE class 22 or 23, other than a unique violation on
  `request_id`, which §4.3 treats as a re-run): an effects row FAILED, with the SQLSTATE, and
  `fs_analyst_effects_failed_total`. The worker moves on. The claim stays until an operator runs
  `release_failed_claim` (§7.2). The overdue alarm (§7.4) shows a held payment caught by it.

**D5. Overriding** (`overrideAlertDecision`), one runner transaction:
- **(a)** The entry row and its lock, idempotency as in D1(b), then the version check.
- **(b) The original.** It must be the entry's latest decision that is neither undone nor
  overridden, and of kind CONFIRM_FRAUD, MARK_LEGITIMATE, AUTO_RELEASED, AUTO_BLOCKED or
  SENIOR_OVERRIDE, never ESCALATE (409 `stale-alert`).
  - It must be committed (409 `decision-not-committed`).
  - It must not be the caller's own (403).
- **(c)** Insert SENIOR_OVERRIDE with `override_decision`, committed on insert. Set the entry's
  status to OVERRIDDEN, `version + 1`, and audit `SENIOR_OVERRIDE`.
- **(d) The OVERRIDE request (§5), in the same transaction.**
  - Contested: 409 `decision-contested`.
  - A pending ANALYST claim: 409 `decision-not-committed`.
  - Otherwise the outcome is written, with an effects row for the override. E2, if it applies, is
    then the effects worker's (D4(d)).
- **(e)** Commit, record the outcome (§4.6), and answer 201.

The override is applied synchronously, not by the effects worker as in the old contract.
- **Its original has already had its effect.** A pending ANALYST claim answers 409, so an override
  cannot overtake its original.
- **Its state needs only PostgreSQL,** because recording is asynchronous.

**D6. Escalating** (`escalateAlert`), one runner transaction:
- **(a)** The entry row and its lock, and idempotency. A replay returns the original 201
  `AlertSummary` as it now stands.
- **(b) The checks.**
  - The version must match, and the entry must be open.
  - The target must rank strictly above the caller (422).
  - On an escalated entry, the caller must rank at or above its target (403).
- **(c) ESCALATE** (§5), for every tier.
  - **For HOLD, escalation stops the timer** (owner, Q2): the hold runs to the escalation deadline.
  - **For a payment that is not held,** the window defers a customer's "yes" until a senior has seen
    it, or until W passes.
  - Timed out, or not HOLD where HOLD was read: 409 `stale-alert`.
  - Contested: 409 `decision-contested`.
- **(d) The new entry.** Insert it. Its `review_deadline_at` is the owner's `deadline` when the
  transaction is HOLD, and NULL otherwise: V4 forbids a deadline on an ANOMALY entry, and only a
  hold has one (review 1, finding 10). The escalation window of a payment that is not held lives
  on the owner row only, and the console does not show it. Insert
  ESCALATE on the original, set the original's status to ESCALATED, `version + 1`, and audit
  `ESCALATED`.
- **(e)** After the commit, reschedule the Redis hold at the escalation deadline. If that fails, the
  poller's TIMEOUT at the old deadline is refused as early and reschedules itself.
- **(f) If no senior answers by the deadline:**
  - **A held payment:** the TIMEOUT applies the channel's policy, and closes the escalated entry as
    AUTO_*.
  - **A payment that is not held:** the claim stops deferring the customer at the deadline, and the
    escalated entry stays open for review.

  The escalation stays on record either way.

**W, the escalation review window, is ASSUMED: 15 minutes by default, and configurable.** The
reasoning is carried from the old contract and ADR 0111:
- the console re-reads the escalated queue every 60 s;
- a hold of about a quarter of an hour is roughly where a customer starts to call.

Nothing has measured either half. D-10's capacity model, when it exists, replaces the assumption.

## 10. What each queue lists

**Without `outcome`, a queue lists open entries only.**
- Deciding takes an entry out at once.
- An undo puts it back with the next `version`.
- Escalating replaces the original with the new entry.
- A timeout or a resolution closes the transaction's entries.

**`outcome` selects statuses explicitly.**

**The queues:**
- **standard:** HIGH and MEDIUM entries with no `escalated_from_alert_id`;
- **anomaly:** ANOMALY entries with none;
- **escalated:** entries with one, filtered by role;
- **all:** the union.

## 11. Costs, limitations and carried items

**Costs:**
- **One owner row per held or touched transaction, and one advisory lock per sink record.**
- **Every later state is written to PostgreSQL synchronously** by a path that already needs
  PostgreSQL. The ingest hot path is unchanged. This is the old contract's option (a), kept:
  - ADR 0064 and D-13's assumption, that every fact reaches PostgreSQL through the spool, holds for
    sequence 1 only;
  - `decision-fact-ordering.md` and `SmsOrderingTest` move to `v_decision_states` with P2's code;
  - D-44's outbox is replaced by the request row and the recorder, the same guarantee by another
    mechanism.
- **A decided MEDIUM transaction stays held** for G + P plus the effect's transaction, and an undo
  near the deadline holds it up to G past it (D-44 accepts this).
- **A customer's "yes" waits while a human reviews.** That is up to G + P under an ANALYST claim,
  and up to W under an escalation.
- **The device's countdown and the server's grace use different clocks.** A click at the very end
  gets 409.
- **An override downgrading from fraud** leaves a breaker count until its bucket expires.
- **A replay returns the original outcome.** Clients read the alert for the current state.
- **Same-value duplicate first decisions are not contested.** The deadline skew of §7.1 is the cost.

**Limitations:**
- **No measurement of PostgreSQL outage duration exists.** The length of a hold during an outage,
  and the size of D-18's breach, cannot be stated from evidence (ADR 0111).
- **The overdue sweep, the unblock reconciler and the poller need M6's Redis leader lease.** While
  Redis is down, only instances' local fallbacks time holds out. The alarms (§7.4) need no lease, so
  such a hold is visible.
- **A recorded request means "written to the local spool", not "published"** (ADR 0064). An instance
  that loses its spool volume loses what it had not published. PostgreSQL and the poll stay right;
  Kafka and the webhook do not receive the lost records. The runbook re-publishes requests recorded
  on that instance.
- **A double first decision is never detected** when the retry's instance loses its spool before
  draining (§7.3). This is M6's, inherited.
- **A hold with neither an owner row nor a sequence-1 row,** because its ingest record was
  dead-lettered and its `DueHold` lost, is invisible to every path and every alarm, as under M6. The
  dead-letter count is its only signal (old contract, review 5's m8).
- **W (15 min) is ASSUMED.**
- **M6's double decision in reverse order is a residual** (§7.3). It is common after a PostgreSQL
  outage, and unmeasured. The Redis-as-detector option that would narrow it is the owner's to take
  or leave.
- **The resolution and the release of a FAILED claim are operator functions, not API operations.**
  The console cannot show or perform them.
- **The resolving officer's identity is asserted, not proven** (§7.2). The database login is recorded
  beside it, and M9's runbook must give each operator an individual login.
- **A resolution is final through the API** (§7.2). Changing it needs another operator action.
- **After §6.1's case 5, a resolution to DECLINE sends no SMS** for that block, because the intent
  was consumed while the payment was released (§6.1).

**Contract additions, proposed with P2:**
- `idempotency-conflict`, `decision-contested` and 503 declared on the four operations;
- `override_decision` on `DecisionRecord`;
- `AUTO_DECLINED` in `AlertStatus`;
- §5.1's seven transitions in `FinalDecision`'s description, with their conditions in words, and
  the analyst in `FinalDecision.final`'s text;
- the problem type `decision-effect-failed` on `overrideAlertDecision`;
- an exception to "`supersedes_decision` is the decision of sequence n-1" for the stored history of
  a transaction contested by §6.1's case 5. The bank never receives that history, but stored reads
  do;
- the poll's 503 `decision-contested`.

**Carried, as recorded in the old contract's §12 to §17, to the implementation's review:**
- m5: answered here, because an override's label is recorded with every outcome;
- m6: the feature store keeps the label that arrives last;
- m8: the policy column comes from the configuration contract;
- m9: answered by §6.5;
- m10: D5(b)'s overridable kinds go beyond the API's wording;
- m11: the poller's lease during an outage;
- review 6's delta D3 and D4: properties for dropped unreserved states, and pre-V81 dead letters.

## 12. What the old contract promised that this one does not

This contract replaces `analyst-decision-commit-contract.md`. These are the differences a reader of
the old one would otherwise assume:

| The old contract (draft 8) promised | This contract |
|---|---|
| A bank's `decision.final` changes value at every new sequence | A human's confirmation and a resolution can repeat the value, at a higher sequence (§5.1) |
| `FinalDecision`'s transitions as frozen | Seven transitions added (§5.1), to be proposed with P2 |
| A customer's answer is stored first and applied within seconds by the reconciler | The answer and its outcome commit together. If the database is down the customer retries, and a "yes" waits while a human holds a claim or a contest stands (§6.5) |
| A refused "yes" still left an `unblock_events` row | An unblock row exists only for an applied "yes" |
| A senior's re-decline after a lift blocks the customer path | Any human's fraud decision blocks it, an analyst's included, and a human's fraud decision overrules a customer's earlier "yes" (§5). This is the stated asymmetry in full, which is wider than draft 8's rule |
| Escalation affects only MEDIUM holds | Escalation on any tier holds a customer's "yes" for up to W |
| An override's effect runs in the effects worker after its original's | An override applies synchronously, and answers 409 while its original's effect is pending |
| An analyst's MARK_LEGITIMATE or CONFIRM_FRAUD on a HIGH alert is a label only | CONFIRM_FRAUD on a declined payment is a state (DECLINE → DECLINE by ANALYST). MARK_LEGITIMATE is still a label only (Q1) |
| A contest freezes the transaction until "an operator resolves it by hand" | A contest is a state with an explicit exit, RESOLVE, which gives the bank a new final decision. The freeze defers work; after RESOLVE a deferred analyst effect is reduced to its label and named, and its state is not applied |
| The reconciler treats unblocks handled before V81 as done by matching a CUSTOMER_VERIFICATION state | V81 writes a PRE_V81 marker for every existing response whose effect is complete or moot. A "yes" M6 recorded but never applied gets none, and the reconciler applies it once after the cutover (§4.4) |
| Every stored history is a `FinalDecision` chain | A transaction contested by §6.1's case 5 stores a sequence 2 that does not supersede sequence 1's decision (§6.1, §11) |
| An auto-blocked customer is always sent the block's SMS | After case 5 and a resolution to DECLINE, the SMS is not sent (§6.1) |
| M6's sink closed a MEDIUM entry only on TIMEOUT_RELEASE | A timeout under either policy closes every open entry, as AUTO_RELEASED or AUTO_DECLINED (§5) |
| A double first decision was a rare race | Its reverse order is common after a PostgreSQL outage, and is stated as M6's residual (§7.3) |
| "Once detected, nothing automatic acts again" | Nothing moves the owner's state. Publications of earlier events still go out (§7.3) |
| Two first decisions conflict when their event ids differ | They conflict when their values differ. Same-value duplicates are accepted with a deadline skew (§7.1) |
| `hold_owners` and `transaction_transitions` | Merged into `decision_owners` and `transaction_requests` |

## 13. Properties, each proved by a test that fails with its rule removed

| # | Property | Rules |
|---|---|---|
| P1 | Concurrent decides on one entry with different keys: exactly one 202 | D1(a), the backstop trigger |
| P2 | Committed or undone, never both | D2, D3 |
| P3 | An undone decision has no effect. A committed one's effect runs once, after its commit, whether the worker runs once or twice concurrently | D4, §4.3 |
| P4 | Exactly one of an analyst's effect and a timeout applies to a hold, against the poller, the fallback and the sweep, including a JVM clock that runs fast | §5 CLAIM and TIMEOUT |
| P5 | Any two requests on one transaction, in either order, produce sequences k and k + 1, never two k. Remove the owner row's lock and the backstop trigger, and two k appear | §4.3 |
| P6 | A writer killed after its commit is recorded by the recorder, byte for byte | §4.6 |
| P7 | A timeout from a `DueHold` whose decision differs from PostgreSQL's sequence 1 is refused and records the contest when the sequence-1 row came first; when it came after, the sink writes it in full and records the contest (review 8, MAJOR 1; review 1, finding 4) | §4.2, §5, §6.1, §7.1 |
| P7a | The owner's own first decision arriving after its `DueHold`'s TIMEOUT is written in full: sequence 1, `transaction_ids`, the scores and the MEDIUM entry, which §6.2 then closes (review 1, MAJOR 1) | §6.1, §6.2 |
| P8 | A committed analyst decision whose effect meets a contest is deferred, then after RESOLVE ends LABEL_ONLY with its label recorded, and is named in the RESOLVE request's record. It is never terminally skipped unnoticed (review 8, MAJOR 2) | §7.2, §7.3, D4 |
| P8a | A resolution made by the SQL function is applied by the resolver through `DecisionOwner`, exactly once, also after a crash between the two; a resolution made before the transaction was contested again is REFUSED `contest-changed` (review 1, MAJOR 2) | §7.2 |
| P8b | After the cutover a response M6 had applied or refused is not resubmitted and raises no alarm; a WAS_ME M6 recorded but never applied is applied exactly once, without a unique violation on `unblock_events` (review 1, MAJOR 3; review 2, MAJOR 1) | §4.4, §5, §6.5 |
| P8c | A conflicting decision detected twice, by its `DueHold` and then by its own record or a re-delivered batch, does not re-contest a resolved transaction; nor does the owner's own first decision draining after §6.1's case 5 (review 2, finding 3) | §6.1, §7.1 |
| P8d | `release_failed_claim` releases an ANALYST claim whose effect FAILED; the hold is rescheduled and timed out at its deadline, the entry is closed by the TIMEOUT, and the breaker never counts the failed decision | §5, §7.2, D4(d) |
| P9 | RESOLVE ends the 503, and the bank receives the resolution at a higher sequence | §7.2, §8 |
| P10 | A senior's CONFIRM_FRAUD on a payment still declined by the model creates a state, and a customer's later "yes" is refused, with no LEGITIMATE label and no unblock row (review 8, MAJOR 3) | §5 |
| P11 | A customer's "yes" during an ANALYST claim is deferred, then refused if the analyst confirmed fraud, or applied if the analyst undid | §5, §6.5 |
| P12 | A human's CONFIRM_FRAUD applied after a customer's "yes" re-declines the payment | §5 |
| P13 | Under both policies, a timeout closes every open entry of its transaction, an escalated one included, and a late entry is closed by the sink | §5, §6.2 |
| P14 | The overdue alarm reports a hold whose ANALYST claim's effect failed permanently, and one held by a contest | §7.4 |
| P15 | A queue without `outcome` never lists a decided, timed-out or resolved entry, and lists an undone one again | §10 |
| P16 | `GET /decisions/{id}` answers from the view whenever it holds the transaction, 503 when contested, and from Redis only when PostgreSQL is down or the view holds nothing | §8 |
| P17 | The sink writes no `decision_states` row with sequence > 1, and a stray one is refused | §4.4 |
| P18 | Committer, undo, decide and timeout racing on one entry finish without an unretried deadlock | §3 |
| P19 | An expired idempotency lease is verified against PostgreSQL, and a lease still running answers InFlight | §7.5 |
| P8e | An auto-blocked payment whose analyst's effect FAILED, with the customer's "yes" deferred behind the claim, raises `fs_analyst_claims_failed`; an override on it answers 409 `decision-effect-failed` (review 3, finding 1) | §5, §7.4 |
| P8f | After a RESOLVE, an override from any entry of the transaction, closed by RESOLVE, by §6.2 or by the analyst's decision, answers 409 `stale-alert` (review 3, finding 2) | §5, §7.2 |
| P8g | A NOT_ME whose label M6 never recorded is submitted after the cutover and its label recorded once; RELEASE_FAILED re-run returns RELEASED | §4.4, §6.5, §5 |
| P19a | Escalating an ANOMALY alert succeeds, with a NULL `review_deadline_at` on the new entry | D6(d) |
| P19b | A timeout path whose request transaction fails, for any reason, reschedules that hold and loses no other hold of its batch | §2 |
| P20 | DECIDED, UNDO, COMMITTED, ESCALATED and SENIOR_OVERRIDE are each audited in their change's transaction. A resolution is audited twice: its authorisation in the function's transaction, and its state change in the resolver's | §9, §7.2 |
| P21 | The breaker counts a transaction at most once, and never for HIGH | D4(d) |

## 14. Changes to M6's code that this contract requires

- **`DecisionOwner`** (new, decision module): §4.3 and §5. It is the only caller of
  `DecisionState.next`.
- **`DecisionState.allowed`:** exactly §5's transitions (§5.1).
- **`HoldTimeoutService`** (`tick`, the local fallback, `reconcile`): TIMEOUT requests (§6.3).
  **`JdbcOverdueHolds`:** §6.3's selection.
- **`DecisionService`:** the `DueHold` carries the HOLD's event id, `decided_at`, deadline, reason
  codes and policy.
- **`DecisionTransitionService`:** removed. Its two methods become EFFECT and CUSTOMER_* requests.
- **`VerificationService.answer`:** one transaction (§6.5). **`UnblockReconciler`:** §6.5's
  selection, and the V64 lister.
- **`PostgresSink`:** §6.1 and §6.2.
- **`RedisIdempotency` and its claim script:** `lease_until` and the UNCERTAIN branch (§7.5).
- **`TenantTransactions.inTenant`:** retries 40P01.
- **`IngestController`'s poll:** §8.
- **The readers of later states:** `v_decision_states` (§4.7).
- **`RedisDecisionStates`:** the set-if-higher script.
- **`RedisCircuitBreakers`:** the idempotent confirmed-fraud script.
- **`LabelRecorded` and `KafkaMessages`:** `supersedes_label_id`.
- **`CustomerSmsSender`:** reads `v_decision_states`, and is otherwise unchanged (§7.3).
- **The resolver** (new, in `analyst`) and the SQL functions `resolve_contested_decision` and
  `release_failed_claim` (§7.2). **`analyst`'s pom** gains `fraudshield-decision`.
- **`decision_owners.resolution_id`** (§4.1).
- **`RedisHoldSchedule`:** the `DueHold` member format with the basis fields, and a parser that
  accepts both formats (§4.2).
- **`KafkaMessages.changeAction`:** the new ANALYST and SENIOR_OVERRIDE transitions (§5.1).
- **V62's `institutions_with_overdue_holds`** (§6.3), and `v_auto_block_status`'s
  `currently_blocked` (§5).
- **`HoldTimeoutService`, `VerificationService`, `UnblockReconciler` and the effects worker** run
  their transactions on the runner (§2). **The decision module's `TenantTransactions`** gains the
  settings and the 40001 and 40P01 retries; `HoldTimeoutService.tick` catches per hold.
- **V64's `institutions_with_unapplied_unblocks`** is rewritten (§6.5).
- **SECURITY DEFINER listers** (review 2, finding 8). Row-level security scopes `fs_app` to one
  institution, so each worker that crosses institutions needs a lister. Each compares on its own DB
  time, is granted to `fs_app` and is pinned in `DatabaseSecurityTest` as V71 requires:
  - the committer's due decisions;
  - the effects worker's pending effects, excluding contested owners, and its pending breaker
    counts;
  - the recorder's unrecorded requests;
  - the resolver's unapplied resolutions;
  - the reconciler's deferred answers (V64, rewritten);
  - the overdue sweep's holds (V62, changed);
  - the six alarms' figures.

## 15. Review 1's findings and their answers

| Finding | Answer in draft 2 |
|---|---|
| 1 MAJOR (design): the sink drops the owner's own first decision | §6.1's five cases, comparing event ids before values; P7a |
| 2 MAJOR (design): RESOLVE as a SQL function against a Java-only owner | §7.2: the function authorises and records; the resolver applies it through `DecisionOwner`; P8a |
| 3 MAJOR (design): the reconciler resubmits every pre-V81 answer | §4.4: PRE_V81 markers written by V81; P8b |
| 4 MAJOR (M6's): the double decision in reverse order | §7.3: recorded as M6's residual, with its real frequency after an outage; §6.1 case 5 says which facts are written; the Redis-detector option left to the owner (§11) |
| 5 TIMEOUT's precedence | §5: the order stated |
| 6 re-detection re-contests | §7.1: `contested_since` set only on a new conflict row; P8c |
| 7 seven transitions, `allowed`, the reason code, `changeAction` | §5.1 |
| 8 "deferred, not discarded" | §5 EFFECT, §7.2, §7.3: the label is kept, the decision named; §12, P8 and ADR 0114 corrected |
| 9 request-id lookup before the lock | §4.3: lock first; a unique violation is a re-run; D4(e) |
| 10 ANOMALY escalation and V4's CHECK | D6(d); P19a |
| 11 requesters off the runner | §2; §14; P19b |
| 12 the alarm's exceptions | §7.3, §7.4 |
| 13 no exit for a FAILED claim | §5 RELEASE_FAILED, §7.2; P8d |
| 14 the effects worker's starvation | D4(a) |
| 15 `timeout_policy`'s source | §4.2 point 4, §6.4 |
| 16 §14's omissions | §6.3 (V62), §4.2 point 5 (the member format), §5.1 and §14 (`changeAction`) |
| 17 the block record after RESOLVE and a re-decline | §5 RESOLVE, `currently_blocked` |
| 18 the sink's lock order, class 53 | §3 exception 2; §6.1 |

## 16. Review 2's findings and their answers

Review 2 found no MAJOR in the owner mechanism: the row, its lock and the rule table's ordering
held.

| Finding | Answer in draft 3 |
|---|---|
| 1 MAJOR (edge): PRE_V81 markers abandon a "yes" M6 recorded but never applied; the resubmitted unblock insert hits V5's unique key | §4.4: markers only for complete or moot responses, keyed by `verification_id`; §5: the unblock insert is `ON CONFLICT DO NOTHING`; P8b and §12 rewritten |
| 2 MAJOR (M6's): the reverse-order double decision | §7.3: recorded, and the non-outage frequency corrected to the hold window less the lease and the retry delay (about 20 s) |
| 3 the owner's own decision re-contests after case 5; case 1's wording | §6.1 case 1 split into (a) and (b); (b) compares with the owner first; P8c extended |
| 4 case 5's history and the lost SMS | §6.1 case 5; §11's contract addition and limitation; §12 |
| 5 the timeout paths' failure list and the lost batch | §2: reschedule on any failure, per hold; §14; P19b aligned |
| 6 RELEASE_FAILED: no reschedule, the entry, the breaker, the decision id | §5 RELEASE_FAILED and TIMEOUT; D4(d); §7.2's resolution row; P8d |
| 7 the asserted actor, the label source, the closed entries, P20 | §7.2; §11; P20 corrected |
| 8 V64, the cross-tenant listers, the runner's module | §2's runner; §6.5; §14 |
| 9 the alarm bound counts the gauge, not the alert | §7.4: "visible in the gauge"; each gauge's alert condition; the pipeline is M9's |

## 17. Review 3's findings and their answers

Review 3 found no MAJOR in the owner mechanism, and no path that moves a state outside it.

| Finding | Answer in draft 4 |
|---|---|
| 1 MAJOR (edge): a failed claim defers a customer's "yes" on a payment that is not held, with no alarm; the override's 409 says "pending" | §7.4: `fs_analyst_claims_failed` with an alert; §5 OVERRIDE: 409 `decision-effect-failed`; §11's contract additions; P8e |
| 2 MAJOR (edge): a resolution is not final, because entries closed before or after RESOLVE carry no `resolution_id` | §4.1 and §7.2: `decision_owners.resolution_id`; §5 OVERRIDE refused while it is set; P8f |
| 3 RELEASE_FAILED's success has no outcome | §4.4: outcome RELEASED; §5 |
| 4 OVERRIDE on HOLD after RELEASE_FAILED | §5: 409 `stale-alert`, and the timeout decides |
| 5 module placement and the caller's connection | §4.3 |
| 6 (M6's) a NOT_ME whose label was never spooled | §4.4: no marker without the CUSTOMER label; §6.5: the reconciler submits CUSTOMER_NO; P8g |
| 7 the backfill's request ids | §4.4: `backfill|` + event id |
| 8 no gauge on the recorder's backlog | §7.4: `fs_requests_unrecorded_oldest_seconds` |
| 9 a second escalation after W on a payment that is not held | §5 ESCALATE: a lapsed window opens afresh |
