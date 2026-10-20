# Review 6: the analyst decision commit and undo contract (draft 6)

Subject: `docs/architecture/analyst-decision-commit-contract.md` (draft 6) at `5946878`
(branch `m8b/analyst-api`).

Reviewer: independent. I wrote none of the drafts and none of reviews 1 to 5.

Method: reading only. No build, test or container was run. Contract line numbers (`C:<n>`) are at
`5946878`. Code paths are abbreviated: `decision/` is
`backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`, `notify/` is
`backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`, and `V<n>` is
`backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

Scope, as instructed: option (a), meaning T6, the changes in §3.6, H3, T3 and §8, and §9's new
costs section. Findings carried in §12 to §14 are not re-reported. None of them was made worse by
draft 6.

## Verdict: SOUND_WITH_CHANGES

**MAJOR in draft 6: NO.** Draft 6's change produces no MAJOR.
- Finding 1 is a MAJOR by the owner's definition: it makes the bank-facing `GET /decisions/{id}`
  wrong. But it does **not** stem from draft 6:
  - it is in M6's code today, and every draft since draft 2 has carried it unreported;
  - draft 6 neither causes it nor makes it worse;
  - it lives in the Redis state cache (T4's last step), not in the queue semantics or the
    arbiters, and one rule fixes it.
- I record it as a MAJOR, so as not to deflate it. Under the owner's rule it is not a reason to
  rethink the queue semantics.

**Review 5's MAJOR: RESOLVED.** The reasons are given below.

**The arbiters still hold.** I found no interleaving in which both an analyst and a timeout decide a
held transaction. I found none in which two writers reserve one sequence. I found none in which
draft 6 loses or doubles a state in PostgreSQL.

**Findings 2 to 7 are MINOR.** Two are specification gaps in the new view (2, 3). Two are records
and costs (4, 5). Two are wording (6, 7).

## Review 5's MAJOR

| # | Review 5 MAJOR | Status | Why |
|---|---|---|---|
| 1 | The sink's later-state write can lose a transition for good | **RESOLVED** (one MINOR residue, finding 3: the property it asked for is missing) | See below |

**Why it is resolved.**
- **The only foreign key that could refuse a later state is gone from its path.** In the whole
  schema, only `decision_states` references `transaction_ids` (`V3:108`). The other tables the sink
  writes reference only `institutions`, `users` or `auto_block_events`:
  - `auto_block_events` (`V5:3-5`) and `label_events` (`V5:143-145`);
  - `customer_notifications` (`V5:34`), `unblock_events` (`V5:98-100`) and
    `account_freeze_events` (`V5:116-117`);
  - `alert_queue_entries` and `alert_decisions` (`V4:6, 23, 32, 55-56, 64`).
- **A timeout's record carries one fact.** It is a single `DecisionChanged`
  (`HoldTimeoutService.java:124`).
  - Under T6, the sink inserts no later state (C:327-331).
  - Under H5, it no longer writes the AUTO_RELEASED row or closes entries. Those were the only
    other writes of `PostgresSink.decisionChanged` (`PostgresSink.java:434-453`).
  - So the sink writes nothing for a timeout's record, and nothing in it can fail with 23503.
- **The records of the other writers cannot fail either.** The analyst's, the override's and the
  customer's records add a `LabelRecorded` (`DecisionTransitionService.java:117-127, 167`). Its
  table, `label_events`, has no key to `transaction_ids`.
- **The reservation is visible at once.** It commits inside the claim's transaction (H2, H5, T2),
  so `v_decision_states` shows sequence 2 from that commit (C:345-349). Review 5's interleaving
  (steps 4 to 6) therefore ends with:
  - sequence 1 in `decision_states`, once A drains;
  - sequence 2 in `transaction_transitions`;
  - both in the view;
  - no dead letter.
- **The D-14 PostgreSQL fallback reads the view** (C:338). It reports the timeout, not HOLD.
- **The gap at sequence 2 is gone.** No later state is written to `decision_states` any more, so
  `decision_states` cannot end up with a gap at 2.

**Residue.** Review 5 asked for a property: "a timeout committed before its sequence-1 row exists
still appears in the D-14 read once the sequence-1 row arrives". §7 (C:507-527) does not have it.
Finding 3 covers this.

## New findings

### 1. MAJOR (pre-existing in M6, not from draft 6): a failed Redis state write is never retried, so `GET /decisions/{id}` returns the superseded state for up to 8 days

**Stems from draft 6:** no.
- **M6 has it.** `HoldTimeoutService.java:124-125` and `DecisionTransitionService.java:167-168`
  call `states.save` after recording. `ResilientPorts.java:102-103` turns a Redis failure into
  `true`.
- **Drafts 2 to 6 keep the order.** T4 (C:308-313) runs:
  1. the spool append;
  2. the recording row;
  3. the Redis set.

  Once the recording row exists, nothing repeats step 3.
- **Draft 6 does not make it worse.** It fixes the PostgreSQL half of review 5's consequence ("the
  bank sees HOLD"), but `GET` reads Redis first.

**Rules:**
- T4: "It then sets the Redis state cache" (C:312).
- T5: the recorder lists only reservations with no recording row (C:315-317).
- T3: a customer's unblock is treated as handled once the view holds a CUSTOMER_VERIFICATION state
  (C:303-305).

**Evidence:**
- **`GET` reads Redis before PostgreSQL.** `GET /api/v1/decisions/{id}` calls `states.latest`
  (`IngestController.java`, `decision(...)`). That is `RedisDecisionStates.latest`, which returns
  the Redis value whenever one exists (`RedisDecisionStates.java:88-92`). PostgreSQL is read only
  when Redis has no value, or during the first second of degraded mode
  (`ResilientPorts.java:42-55`; `DegradedMode.java:40-41`).
- **The stale value lasts 8 days.** The ingest decision's Redis value expires 8 days after its last
  successful write (`RedisDecisionStates.java:28, 37`).
- **Customer path.** `VerificationService` commits the answer and `unblock_events`, then calls
  `customerAnswered` (`VerificationService.java:61, 250`). A failed Redis write there is swallowed.
  - Under T3 (C:303-305), the reconciler then sees the reservation's CUSTOMER_VERIFICATION state in
    the view and treats the unblock as handled.
  - T5 sees the recording row.
  - So nothing writes Redis again.
- **Leader's poller.** `claimDue`'s Lua removes the member from the schedule before the claim
  (`RedisHoldSchedule.java:41-51`). After a Redis failure, nothing re-fires the hold:
  - the poller no longer has the member;
  - H3 excludes the transaction, because the view has a later state (C:228).

**What does recover.** Some paths retry the Redis write, so they recover:
- **The analyst's and the override's effects** retry on Redis unavailability (D4(e′), C:431-432).
  The retried E1 finds its own reservation (T2 step 2) and re-runs T4.
- **A sweep-path timeout** recovers if its Redis member survived. `reconcile` cancels the member
  first (`HoldTimeoutService.java:103`), and that cancel fails while Redis is down.
  - When Redis returns, the poller fires the member, and the claim succeeds from TIMEOUT (H1,
    C:186).
  - T2 finds `hold_timeout|<txn>` and re-runs T4, which now sets Redis.

**Interleaving (customer path, one fault).**
1. HIGH transaction X is blocked. Sequence 1 DECLINE is in Redis, with an expiry 8 days out
   (`DecisionService.java:247`).
2. Redis becomes unreachable but keeps its data: a partition, or a restart with persistence.
3. The customer answers "yes, this was me". The unblock commits.
   - T2 reserves sequence 2 APPROVE, and T4 records it. Kafka and the webhook tell the bank
     APPROVE.
   - The Redis write fails and is swallowed.
4. Redis returns. For up to 8 days, `GET /decisions/X` answers DECLINE from Redis, although
   PostgreSQL's view and the webhook say APPROVE.

Every customer "yes" during the outage is affected the same way. The poller variant needs a Redis
failure between `claimDue` and that hold's write. During a batch of up to 500 holds, that is the
rest of the batch.

**Why it is a MAJOR:** it makes the bank-facing final decision (D-14's poll) wrong, from a single
fault.

**Fix (one rule).** In T4, write the recording row only after the Redis set succeeds, or after Redis
is known to be absent.
- A failed set then leaves the reservation unrecorded, and T5 repeats T4 30 s later.
- The spool append repeats the same event id, which every receiver ignores. Kafka consumers ignore
  it, and so does `WebhookDispatcher`, through `ON CONFLICT (event_id)` and the sequence check
  (`WebhookDispatcher.java:36-50`).
- Set-if-higher makes the retried write safe.
- Alternatively, keep T4's order and add a separate "cached" marker that T5 also sweeps.
- Add a property: a later state whose Redis write fails is in Redis within 30 s + P of Redis
  returning.

### 2. MINOR (draft 6): the view's tenant isolation is unspecified, and the project's usual `v_` pattern would blind the two SECURITY DEFINER listers

**Rule:** T6 (C:334-337) says the view is "tenant-isolated", and that "V62's lister" and "V64's
lister" move to it (C:339-340, §8 C:546-548).

**Evidence:** the project has two view patterns:
- **The `v_<table>` tenant views.** These are `security_barrier` views that filter on
  `current_institution()` (`V1:69-77`). `current_institution()` is NULL when no tenant is set
  (`V1:7-9`).
- **The `security_invoker` views over RLS tables.** These are `v_alert_decision_state` (`V4:113`)
  and `v_auto_block_status` (`V5:124`).

The listers run with no tenant set:
- `institutions_with_overdue_holds` (`V62:13-24`);
- `institutions_with_unapplied_unblocks` (`V64:39-51`).

**Consequence.** If V81 builds `v_decision_states` on the first pattern, as its `v_` name invites,
both listers return nothing, without any error:
- the overdue sweep never runs. That is the case ADR 0064 point 4 built the sweep for, and a hold
  whose schedule entry was lost would then never time out;
- the unblock reconciler never runs.

The tests behind P4 and the existing sweep tests would probably catch this, but the contract should
not leave it open.

**Grants.** A `security_invoker` view also needs SELECT on `transaction_transitions` for every role
that reads it. `decision_states` is readable by `fs_app_readonly` and `fs_compliance_ro`
(`V11:56-69`). "Granted where `decision_states` is" (C:336) therefore also means granting the base
table to those roles.

**Fix:** state that `v_decision_states` is `security_invoker = true` over the two RLS tables, and
that both listers read the base tables directly. As definers they bypass RLS as the owner, as they
do today. List the grants on `transaction_transitions`.

### 3. MINOR (draft 6): T6 has no property, and review 5's requested property is missing

- **No property for T6.** §7 has nothing that fails with T6 removed. The properties are:
  - the sink writes no `decision_states` row with sequence > 1;
  - a `DecisionChanged` with sequence > 1 and no reservation is counted and not written;
  - every listed reader reads the view.
- **Review 5's requested property is missing.** A timeout committed before its sequence-1 row
  exists still appears in the D-14 PostgreSQL read. It also appears when the ingest record is
  dead-lettered.
- **The new reader behaviour is untested.** While sequence 1 is missing, the view shows sequence 2
  alone. I checked every reader against that case (see "Verified claims"), and each one behaves
  correctly. No property pins that down.

**Fix:** add properties P18 (T6) and P19 (review 5's property).

### 4. MINOR (draft 6's §9): the costs section cites the wrong ADR, and misses one contract and two kinds of reader

- **The assumption is not ADR 0068's.** §9 says ADR 0068 assumed "every decision fact reaches
  PostgreSQL through the spool, asynchronously, so PostgreSQL is never on the decision's critical
  path" (C:564-566). ADR 0068 is about JPA versus explicit SQL, and says nothing of the kind. The
  assumption is in two other places:
  - ADR 0064's Context, which states it ("without putting PostgreSQL on the hot path (D-13)");
  - ADR 0064's point 2: "an outage of one never blocks the other". Since draft 2, the later-state
    writers need PostgreSQL synchronously, and ADR 0111 already amends ADR 0064.

  The note belongs on ADR 0064, and on D-13 in the register, not on ADR 0068.
- **The SMS fact-ordering contract is missing.** `docs/architecture/decision-fact-ordering.md`
  defines `resolved` as "the latest `decision_states` row of `T_B`" (line 209). Its I9 is pinned by
  `SmsOrderingTest`, which asserts the set of tables the classification reads
  (`SmsOrderingTest.java:486`). Moving `CustomerSmsSender` to the view amends that contract, and
  §8 should say so.
- **Two kinds of reader are not named.** Human and reporting readers through `fs_app_readonly` and
  `fs_compliance_ro` (`V11:56-69`) see only sequence 1 in `decision_states` after V81. Their
  queries must move to the view (finding 2's grants).
- **The cost of PostgreSQL leading Kafka is not stated.** Before draft 6, PostgreSQL lagged Kafka.
  Now a reservation is visible in the view before its spool record is written: for up to 30 s + P
  after a crash, or for as long as the spool is unavailable. It is final, because T5 completes it.
  But D-14's PostgreSQL fallback and the readers can report a state that the bank has not yet
  received by webhook. Say so.

ADR 0061 point 6 (`decision_states` "is already an append-only record of every one") is about
unaudited LOW approvals, which are sequence 1. It stays true.

### 5. MINOR (draft 6's §9): "A3's cutover excludes both" is false for pre-V81 dead letters

**Rule:** C:557-560 and C:332-333.

**What A3 does not cover.** A3 drains the spool, not its dead-letter directory. Review 5 showed that
M6's own race already dead-letters a timeout's `DecisionChanged` with 23503. Such a record is in no
spool and in no `decision_states` row, so V81's backfill misses it.

**What happens after V81:**
- **A replay is dropped.** The sink drops a replay of it (C:332-333). That is the intended result,
  and it removes review 5's finding 9 risk of doubling sequence 2.
- **The sweep times the transaction out again.** Its sequence 1 is HOLD with no later state in the
  view, so H3 selects it, and the claim succeeds with no `hold_owners` row (C:186-187). T2 reserves
  sequence 2 with the new id `hold_timeout|<txn>`.
- **The bank sees nothing new.** Kafka carries a second sequence-2 event under a different event id
  but with the same decision, because the policy is derived from the same sequence-1 time. The
  webhook is suppressed (`WebhookDispatcher.java:36-40, 169`), because sequence 2 is already
  delivered.
- **H5 heals the entry.** It closes the entry that M6 left open.

The outcome is benign, and better than M6. Only the sentence is false.

**Fix:**
- say that pre-V81 dead letters are outside A3;
- say that the sweep re-times them out under a new event id, which Kafka consumers see as a repeated
  sequence;
- count the case.

### 6. MINOR (draft 6): wording in T6

- **"The sink still writes … the notification and unblock rows of its kind"** (C:330-331). No
  record that carries a `DecisionChanged` carries a notification. The sink never writes
  `unblock_events`. `VerificationService` writes it (`VerificationService.java:61`), and so does
  D4(c)'s reservation transaction. Say "labels" only.
- **The view's rows from `transaction_transitions` take their columns from `event_body`.** These
  are the decision, reason codes, supersedes and final.
  - For backfilled rows, `event_body` can hold only the `DecisionState`. `decision_states` has no
    channel, actor or label.
  - That is enough, because backfilled rows are never re-recorded.
  - State it in §3.6, so that nobody reads "everything a re-record needs" (C:150-152) as true of
    backfilled rows.

### 7. MINOR (hardening, optional): nothing in the schema enforces T6

T6 relies on the sink's code alone. `decision_states` cannot take a validated CHECK, because its
historical rows with sequence > 1 stay (it is append-only). But `CHECK (decision_sequence = 1) NOT
VALID`, added by V81, refuses any new later state from any writer: an old release, a manual insert,
or a test fixture such as `AnalystIntegrationTest.java:154`.

Readers are safe without it, because the view filters `decision_sequence = 1`. The check turns a
silent violation into a loud one.

## Attacks on draft 6 that found nothing (verified claims)

**The view can show sequence ≥ 2 without sequence 1.** This happens before the ingest record drains,
and for ever if that record is dead-lettered. Each reader is correct in that state:
- **`JdbcDecisionStates`** (`JdbcDecisionStates.java:24-29`, highest sequence) returns the timeout
  state. That is the state Kafka and the webhook carried, so it is right, not wrong. Before draft 6,
  the PostgreSQL fallback answered 404 or HOLD.
- **`JdbcOverdueHolds` and V62** start from sequence-1 HOLD rows (`JdbcOverdueHolds.java:35-38`,
  `V62:18-23`). A lone sequence 2 is never selected.
- **`UnblockReconciler` and V64** join `auto_block_events` (`UnblockReconciler.java:46-51`,
  `V64:44-50`). An auto-block arrives in the same record as sequence 1 (fact-ordering G2).
- **`CustomerSmsSender`** reads the latest state only for a block's transaction
  (`CustomerSmsSender.java:65-69`), which has sequence 1. Its `kept` check reads sequence 1 and stays
  on `decision_states` correctly (`:70-72`).
- **`AccountQueries`** starts from `v_transactions` (`AccountQueries.java:39-45`), so a transaction
  without its ingest rows is not listed.

**No reader still reads `decision_states` for a later state.** The main code that reads it is:
- the sink (`PostgresSink.java:83-94`, sequence 1);
- `JdbcIdempotency` (`JdbcIdempotency.java:30-37`, sequence 1);
- the five readers listed in T6, which all move;
- `JdbcDecisionStates` through `RedisDecisionStates.java:92`.

The application paths that read the latest state through `DecisionStatePort`
(`HoldTimeoutService.java:118`; `DecisionTransitionService.java:172-176`) are replaced by T2's
reservation read. No Python, dashboard or runbook reads the table. The only other readers are
the two read-only roles (finding 4).

**The view is disjoint by construction.** `transaction_transitions` has sequence ≥ 2 (C:148), and
the view takes `decision_states` at sequence 1 only. Historical rows with sequence > 1 stay in
`decision_states` but are filtered out, and the backfill copies them with their event ids. So no
`(institution, transaction, sequence)` appears twice.

**V81 and the pre-V81 spool.**
- **The backfill is complete for every drained record.** A3 drains every spool writer before V81
  (C:91-98).
- **The recording rows stop re-records.** The backfill's recording rows keep T5 from re-recording
  history.
- **Backfilled ids cannot meet deterministic ones.** Backfilled event ids are M6's random ids, and
  T3's ids are name-based, so T2 step 2 never mistakes one for the other.
- **The reconciler finds unblocks handled before V81** through the view (C:303-305).
- **Dead letters** are the one exception, and they are benign (finding 5).

**The sink's drop rule is safe.** A spool record of a sequence > 1 is appended only after its
reservation commits (T4, C:309), so the sink never sees a reserving writer's record before its
reservation.

**The ingest hot path is unchanged.**
- `DecisionService` still appends to the spool, writes Redis and schedules the hold
  (`DecisionService.java:240-263`).
- `JdbcIdempotency` still reads sequence 1 from `decision_states`.
- The sink is off the hot path.
- Draft 6 adds no synchronous PostgreSQL write on ingest. Every synchronous write it relies on (the
  claim, E1, the override, the customer's answer) already existed in draft 5.

**The D-44 paragraph is accurate.** The reservation plus T5 gives the outbox's guarantee: a
transactional write first, then publication at least once. D-44's wording ("through the outbox")
is met in substance.

**Kafka and the webhooks.**
- `KafkaMessages` renders a `DecisionChanged` from the record alone (`KafkaMessages.java:213-238`).
- `WebhookDispatcher` keeps its own `webhook_deliveries` and orders by sequence
  (`WebhookDispatcher.java:36-50`).
- Neither reads `decision_states`, so `fs.decisions.final`, its consumers and the webhooks are
  untouched by T6.

**`VerificationService`.** It writes `unblock_events` from the verification and the auto-block
(`VerificationService.java:48-61`), with no dependency on `decision_states`.
