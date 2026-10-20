# Review 7: the analyst decision commit and undo contract (draft 7), end to end

Subject: `docs/architecture/analyst-decision-commit-contract.md` (draft 7), with ADR 0111 and ADR
0113, at local commit `4221065` (worktree `fs-wt/m8b`).

Reviewer: independent and adversarial. I wrote none of the drafts and none of reviews 1 to 6.

Method: reading only. No build, test, container or network call was made. Contract line numbers
are `C:<n>` at `4221065`. Code paths are abbreviated: `decision/` is
`backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`, `notify/` is
`backend/notify/src/main/java/io/github/mariusbayizere/fraudshield/notify/`, `ingest/` is
`backend/ingest/src/main/java/io/github/mariusbayizere/fraudshield/ingest/`, and `V<n>` is
`backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

**Scope, as the owner's lesson asks.** This review does not stop at draft 7's diff. It checks the
whole contract and the M6 mechanisms it rests on. For each inherited mechanism the question was:
does what it assumes still hold once PostgreSQL, and not Redis, is the authority? Carried MINORs
(§12 to §16) are confirmed where I checked them, not re-reported.

## Verdict: SOUND_WITH_CHANGES

**MAJOR present: YES** (one; finding 1).

- **Where finding 1 comes from.** The defect is **M6's**: a transaction can have two different
  sequence-1 decisions. Nothing in M6 broke as a result, because M6 read Redis first everywhere.
- **Which drafts expose it.**
  - The earlier drafts' timeout paths (H2 and H3, and T2's option A since draft 5) consult only
    PostgreSQL or the `DueHold`. That turns the defect into a payment decided twice.
  - Draft 7's T7 extends it to the bank-facing `GET /decisions/{id}`.
  - T7 step 2's stated property ("Redis is right then") is false in this case.
- **What it rests on.** No contract rule written so far addresses it, and every one of the 7 drafts
  carried it unreported. It lives in an inherited assumption: sequence 1 is unique.
- **What fixing it takes.** The owner has to choose which sequence-1 decision stands (finding 1,
  fix). The contract's wording alone cannot settle that. Under the owner's rule the author
  therefore stops.

**The arbiters themselves hold.** I re-derived the interleavings below from the code and found none
in which:
- an analyst and a timeout both decide one held transaction;
- two writers reserve one sequence;
- a committed decision is also undone.

**Findings 2 to 9 are MINOR.**
- Three come from draft 7's own text (3, 4, 7).
- Two come from earlier drafts (2, 6).
- Three come from M6's code or assumptions (5, 8, 9).

---

## Findings

### 1. MAJOR (origin: M6's code; exposed by earlier drafts' H2/H3/T2, and by draft 7's T7): a transaction with two different sequence-1 decisions is timed out against the one the bank was not given, and `GET /decisions/{id}` flips between them

**Rules:** T2 (the sequence-1 state), H2, H3, T6 (`decision_states` keeps sequence 1), T7 steps 1
and 2.

**The inherited assumption.** Every one of these rules assumes that a transaction has one
sequence-1 decision, and that Redis, the `DueHold`, `decision_states` and the ingest response all
carry it. M6 does not guarantee that, and says so:
- `decision/application/DecisionService.java:44-47`: "If the process dies between the two, the
  Redis state is missing and a retry is decided again; the PostgreSQL writer keeps the first
  decision".
- `decision/adapter/jdbc/PostgresSink.java:36-40, 288-291`: a second `TransactionDecided` for a
  transaction is dropped (`duplicateDecisions`), together with its alert, auto-block and SMS
  (`skip`, `:245-264`).
- ADR 0067 (`docs/adr/0067-ingest-idempotency.md:16-45`) lists the routes to a second decision:
  - a crash inside the 10 s lease (`ingest/src/main/resources/application.yml:73`;
    `RedisIdempotency.java:40-58`, where the INFLIGHT claim expires with the lease and a retry is
    `CLAIMED` again);
  - two first submissions racing while Redis is down (point 3);
  - a claim "decided unverified" while PostgreSQL is down (point 6).
- **Why the two decisions differ.** Each decision draws a fresh event id
  (`DecisionState.initial`, `decision/domain/DecisionState.java`, `UUID.randomUUID()`). The second
  is re-scored, against a breaker set, freeze counter, configuration version and scorer
  availability that may have moved. So the outcome can differ: for example HOLD the first time,
  then DECLINE, or HOLD and then APPROVE under the fallback rules.
- **Which decision wins where.** Each store keeps the first that reaches it:
  - PostgreSQL keeps the first record *drained*;
  - Redis keeps the first `states.save`, because M6's script admits sequence 1 only onto an
    empty key (`RedisDecisionStates.java:32-39`);
  - the `DueHold` is scheduled only by a decision that is HOLD (`DecisionService.java:251-263`);
  - the bank's client has the response that completed (ADR 0067 point 4).

  These four races are independent.

**Why M6 did not decide such a payment twice.** Every M6 read that drives a timeout goes Redis first:
- `HoldTimeoutService.timeOut` returns without acting unless `states.latest(...)` is HOLD
  (`decision/application/HoldTimeoutService.java:117-121`);
- `states` is `ResilientPorts.decisionStates` over `RedisDecisionStates.latest`, which answers from
  the Redis key when it exists (`ResilientPorts.java:107-117`; `RedisDecisionStates.java:79-96`).

So the overdue sweep, which selects the PostgreSQL HOLD (`JdbcOverdueHolds.java:28-40`), skipped a
transaction whose Redis key held the other decision. It skipped it for up to the key's 8 days
(`RedisDecisionStates.java:28`). That is M6's latent form of the same defect: after expiry, the
sweep times it out.

**The contract removes that guard.**
- H3 (`C:230-236`) selects on `decision_states`, `hold_owners` and the view, and claims.
- H2 and T2 (`C:196-216, 285-296`) reserve from the `DueHold` or from `decision_states`. They
  never read the state the bank was answered with.
- T7 (`C:381-394`) answers from the view whenever it holds the transaction.

**Interleaving A: a payment decided twice.**
1. Instance 1 decides T as HOLD (MEDIUM). It spools `TransactionDecided` and `AlertRaised`, then
   dies before `states.save`, `holds.schedule` and `complete`.
2. 10 s later the bank's retry is `CLAIMED` and decided as **DECLINE** (HIGH, for instance with the
   breaker now open, or the account frozen). Redis is set to DECLINE. No hold is scheduled. The
   bank is told DECLINE.
3. Instance 1 restarts and its spool drains first. Under a single instance, or a retry routed back
   to it, the order is restart then drain then retry.
   - PostgreSQL keeps **HOLD**, the MEDIUM entry and the FREE `hold_owners` row.
   - The DECLINE record is dropped as a duplicate, with its auto-block and its SMS.
4. At HOLD's deadline + 10 s, H3 selects T: it is a HOLD, with no later state, and its owner is not
   ANALYST.
   - H2 claims it. H5 closes the entry. T2 reserves sequence 2: TIMEOUT_RELEASE under
     RELEASE_WITH_TIMEOUT_LABEL.
   - T4 publishes it, and the webhook sends `decision.final` sequence 2, "TIMEOUT_RELEASE,
     supersedes HOLD".
   - The bank was told DECLINE at ingest. It is now told the payment was released.
   - The analyst also had a MEDIUM alert for a payment the bank had declined.

**Interleaving B: `GET /decisions/{id}` contradicts itself.** Take the same start, with the bank
told DECLINE.
1. Before the sink drains, T7 step 2 answers DECLINE from Redis.
2. After the sink drains, T7 step 1 answers **HOLD**: sequence 1 from the view, carrying a review
   deadline.
3. Then it answers TIMEOUT_RELEASE.

The answer moves from a final state back to HOLD, which `DecisionState` forbids ("no state returns
to HOLD", `DecisionState.java` constructor). It is the exact failure ADR 0113 exists to prevent: a
bank told one decision and then another.

**Interleaving C: the reverse order.**
1. The first decision is APPROVE, and PostgreSQL keeps it. The second is HOLD.
2. Redis holds HOLD and the `DueHold` is scheduled. The bank is told HOLD.
3. Before the sink, GET answers HOLD from Redis. After it, GET answers APPROVE, which the bank may
   act on. At the deadline the poller reserves sequence 2 from the `DueHold`'s HOLD (T2), say
   DECLINE.
4. The view now holds sequence 1 APPROVE and sequence 2 "DECLINE supersedes HOLD". No analyst ever
   saw an alert, because the HOLD's `AlertRaised` was dropped.

**Why it is a MAJOR.** Interleaving A decides a payment twice, as a DECLINE at ingest and then a
timeout release. Interleaving B makes the GET and the webhook final decision wrong. And T7 step 2's
stated property, "a transaction the view does not hold has no later state anywhere, so Redis is
right", is false whenever the two sequence-1 decisions differ.

**Why not lower.** Each route is a documented, accepted M6 path, and ADR 0067 calls two of them
"rare" but possible: a crash inside the lease, and racing first submissions with Redis down. M6
masked the result for 8 days. The contract makes it act within 10 to 15 s, every time it happens.

**Fix, which needs the owner.** Sequence 1 must be single-valued before PostgreSQL is made the
authority. The options:
1. **Make the decision PostgreSQL keeps the one that stands everywhere.** A re-claim after an
   expired or uncertain lease must not decide afresh while an earlier decision may still drain. It
   must wait for, or read, the durable record, as ADR 0067 point 5 already does for `UNCERTAIN`.
   That closes the crash route, not the Redis-down race.
2. **Detect divergence and stop the automatic paths.** When the sink drops a duplicate whose
   decision differs, it records it in a durable table (today it only increments a counter).
   - H2, H3 and T7 refuse to act on, or answer from, a contested sequence 1: no timeout, and a
     503 or an explicit flag on the GET.
   - The case is alerted for an operator.
3. **Carry the ingest decision's event id in the `DueHold`, and compare it.** The claim refuses when
   it differs from `decision_states`' sequence 1. This covers interleaving C only.

The contract must also state the case in T7, and stop claiming in step 2 that "Redis is right"
unconditionally. P20 needs a test for it.

### 2. MINOR, required (origin: an earlier draft, T3): the timeout's deterministic id omits the institution, but event ids are unique across tenants

**Rule:** T3 (`C:298-306`); §3.6 (`C:156`, "`event_id` unique").

`hold_timeout|` + transaction is the only T3 name built from a value the client chooses. Transaction
ids are bank-supplied, and unique only per institution: the primary key is
`(institution_id, transaction_id)` (`V3`, `decision_states` and `transaction_ids`). `event_id` is
globally unique in `transaction_transitions` (§3.6) and in `webhook_deliveries`
(`V63:14`, `UNIQUE`), and Kafka consumers deduplicate on it.

**What happens on a collision.** Suppose two institutions submit the same transaction id, and both
are held.
- The second timeout's T2 lookup runs under row-level security. It does not see the first
  institution's reservation, so it inserts and gets 23505 for ever. Its claim transaction rolls
  back, and the hold is never timed out.
- Were it reserved, its webhook would be dropped by `ON CONFLICT (event_id) DO NOTHING`, and Kafka
  consumers would ignore it.

**Why only MINOR.** It needs two banks to submit the same UUID, and the contract does not show that
to be reachable. If deterministic client UUID schemes are in use, it is a MAJOR.

**Fix.** Name every T3 id with the institution, as M6's own `DecisionService.derivedId` does
(`DecisionService.java:367-376`). For example `hold_timeout|<institution>|<transaction>`, and the
same for the H5 idempotency key "derived from the transaction".

### 3. MINOR (origin: draft 7, T7 step 4): during a PostgreSQL outage a decided transaction can be answered 404

**Rule:** T7 step 4 (`C:394`): "404 when neither holds the transaction".

**When step 4 is wrong.** While PostgreSQL is unavailable, "neither holds" includes cases in which
PostgreSQL holds the transaction but cannot say so:
- Redis's key has expired (8 days, `RedisDecisionStates.java:28`);
- the key was lost in a failover;
- the ingest write was tolerated as failed (`DecisionService.java:246-250`).

A bank then reads "not found in your institution" for a payment that was decided. M6 answered with
an error in this case (`RedisDecisionStates.java:93-94`). T7 also does not say what happens when
Redis is down too.

**Fix.** 404 only when PostgreSQL answered and found nothing, and Redis has no key. With PostgreSQL
unavailable and a Redis miss, or with both down, answer 503 with `Retry-After`. Add both cases to
P20.

### 4. MINOR (origin: draft 7, T7 step 3 and ADR 0113): "PostgreSQL unavailable" is not defined, and the staleness bound is stated with too few causes

**Rule:** T7 step 3 (`C:389-393`); ADR 0113, "Stale only from before the outage".

**What T7 claims.** The bound is argued from "every writer of a later state needs PostgreSQL, so no
later state is created while it is down". That holds only if "unavailable" means the database is
down.

**What the GET's fallback actually triggers on.** A GET falls back whenever its own read fails: a
pool timeout, its `statement_timeout`, or a programming error of class 42. Meanwhile writers keep
reserving.

**Why Redis can then be behind.** Since draft 7, T4's Redis set is best effort and never retried
(`C:318-322`). A transaction whose set failed stays behind in Redis indefinitely. So is one whose
reservation is still stranded: T5 needs 30 s, and needs PostgreSQL. Such a transaction is answered
stale by any later fallback, not only by one inside an outage that followed a lost acknowledged
write. The bound "at most for the outage" survives only if it is restated as "while the GET's
PostgreSQL read fails".

**Fix.**
- Fall back only on connection-class failures: SQLSTATE classes 08 and 53, and 57P01 to 57P03. Any
  other error answers 500.
- List the causes of staleness in T7 and ADR 0113: a lost acknowledged write, a failed and
  unretried T4 set, and a reservation not yet recorded.
- Count fallbacks, so the bound can be measured.

### 5. MINOR (origin: M6's code; the contract's sentence is draft 7's): `DecisionStatePort.latest` still feeds a decision-path check

**Rule:** T7's last sentence (`C:396-397`), T2.

After §8, the remaining callers of `DecisionStatePort.latest` are:
- `DecisionTransitionService.current` (`decision/application/DecisionTransitionService.java:172`),
  used by `customerAnswered`'s precheck "not DECLINE → NOT_ALLOWED" (`:147, 163`);
- `HoldTimeoutService.timeOut:118`, which §8 replaces.

If P2 keeps that precheck, a Redis key that is behind can refuse a valid "yes".

**Interleaving.**
1. Override 1 lifts a block. Override 2 re-declines, and its T4 Redis set fails.
2. Redis reads APPROVE while PostgreSQL holds DECLINE.
3. The customer's "yes" is refused before T2 runs.
4. `VerificationService` treats it as "already resolved" (`notify/verification/VerificationService.java:251`).

T7's sentence implies that some decision still trusts Redis.

**Fix.** State that no writer reads `DecisionStatePort.latest`, so that T2's read is the only one
that decides, and remove the precheck or move it under T2.

### 6. MINOR (origin: an earlier draft, D4(c) and T3, over M6's V5): the customer's "yes" after an override's unblock has no T3 id, and can reverse a senior's re-decline

**Rules:** T3, D4(c), Q1.

**The schema behind it.**
- `unblock_events.auto_block_event_id` is UNIQUE (`V5:93`).
- The customer's insert is `ON CONFLICT DO NOTHING` (`VerificationService.java:59-62, 231-237`).
- The override's insert is too (D4(c)).

**Case (a): the override's row is already there.** If an override wrote first, the customer's "yes"
inserts no CUSTOMER_VERIFICATION row. T3's `unblock|` + `unblock_events.id` then has no row of its
own to name, and V64's reconciler never selects it.

**Case (b): a senior's re-decline is reversed.** Within the link's 10 minutes
(`VerificationService.java:36`):
1. override 1 lifts the block (DECLINE → APPROVE);
2. override 2 re-declines it (APPROVE → DECLINE);
3. the customer answers "yes";
4. T2 reads DECLINE, and `DecisionState.allowed` permits DECLINE → APPROVE by
   CUSTOMER_VERIFICATION. The customer reverses a senior's explicit fraud decision.

Q1 does not cover this.

**Fix.**
- The customer path reserves only when its own CUSTOMER_VERIFICATION row was inserted
  (`RETURNING id`). Otherwise it reports SKIPPED.
- The owner decides whether a customer may lift a decline that a senior override made.

### 7. MINOR (origin: draft 7, D4(e′) and D4(f) not updated): Redis is still described as blocking E1

**Rule:** D4(e′) and D4(f) (`C:479-482`).

D4(f) says an ANALYST-owned hold "stays HOLD while Redis or the spool is unavailable until E1
succeeds". Since draft 7, E1 does not touch Redis (T4). Only E2 does, and E2 failing re-runs E1
idempotently.

**Fix.** "While the spool is unavailable". Then say that a Redis outage delays only E2 and the
effects row, not the transition.

### 8. MINOR (origin: M6's code): the overdue sweep and the unblock reconciler need the Redis lease, so H3's "10 to 15 s" is false during a Redis outage

**Rules:** H3 (`C:236`), D2(e) ("If rescheduling fails, H3 acts 10 to 15 s after the deadline").

**What the code does.**
- `HoldTimeoutService.reconcile` and `UnblockReconciler.reconcile` run only if
  `acquireLeadership` succeeds (`HoldTimeoutService.java:97-100`; `UnblockReconciler.java:88-89`).
- Without Redis, the fallback grants it only to an instance with local holds
  (`ResilientPorts.java:234-239`).

**What that means.** While Redis is down, an instance with no local holds never sweeps.
- Holds scheduled in Redis before the outage wait until Redis returns.
- Customer unblocks that missed their transition wait too.

This is a second D-18 breach, besides ADR 0111's PostgreSQL one, and ADR 0111 does not record it.

**Fix.** Run the sweep and the reconciler on every instance while Redis is down (H1 and T2
serialise them), or record the breach in ADR 0111.

### 9. MINOR (origin: M6's assumption, kept by T4): the recording row means "spooled", not "published"

**Rule:** T4 and T5 (`C:316-317, 327-331`).

The recording row is written once the local spool append succeeds. If that instance's spool volume
is lost before it drains, the state is in PostgreSQL but never reaches Kafka or the webhook. T5
will not re-record it, because its recording row exists.

M6 had the same exposure for every record (ADR 0064 assumes the spool survives). But the contract
now has the durable reservation that could re-drive it.

**Fix.** Either state spool durability as an assumption beside A2, or write the recording row from
the Kafka drainer's acknowledgement.

---

## Carried findings re-checked against the code

- **Review 3's m4 and m12: confirmed, and still load-bearing.**
  - `TenantTransactions.inTenant` retries only SQLSTATE 40001
    (`backend/audit/.../TenantTransactions.java:21, 60-64`).
  - So §2's "the runner … retries 40001 and 40P01" (`C:84`) and §13's "the runner retries the loser"
    are false of today's code until P2 changes the runner.
  - The reversals are real: claim before entries, and the committer's level 3 before the commit
    trigger's level 2.
- **Review 4's finding 6: confirmed.** A FAILED E1 leaves an ANALYST-owned hold HOLD for ever. H3
  excludes ANALYST owners (`C:234`). It would be a MAJOR if a class 22 or 23 failure of E1 were
  reachable. I found none: T2 inserts under its lock, and `transaction_transitions` has no foreign
  key.
- **PB-156: confirmed.** The reconciler's refused rows are never marked, and it selects
  `ORDER BY unblocked_at LIMIT 100` (`UnblockReconciler.java` SELECT_UNAPPLIED). So 100 permanently
  refused unblocks starve every later one. P8's "the reconciler does not stop" depends on PB-156.
- **Review 5's finding 5 (`DueHold`'s new fields from old Redis members): confirmed.**
  `RedisHoldSchedule` members are `inst|txn|channel|policy|deadline` (`RedisHoldSchedule.java`
  schedule and claimDue). A3's cutover does not drain the sorted set.

## Mechanisms checked and found sound

- **H1 at READ COMMITTED.**
  - An `INSERT … ON CONFLICT DO UPDATE … WHERE owner IN (…)` waits for a concurrent uncommitted
    claimant, then re-evaluates against the committed row.
  - The two orders: an analyst claim that commits after the deadline is respected by the timeout
    (ANALYST is found, and the hold dropped); a timeout committed first makes D1's upsert return no
    row, so D1 answers 409.
  - D1(e)'s deadline read and the claim are not under one lock, but the upsert's WHERE closes that
    gap.
- **H5, the sink against the claim.** Both orders were traced through `PostgresSink`'s
  one-transaction batch (`PostgresSink.java:187-217`).
  - The claim's UPDATE does not see, and so does not wait for, an entry the sink has inserted but
    not committed.
  - The sink's `ON CONFLICT DO NOTHING` waits for the claim's insert, and its `FOR UPDATE` then
    reads TIMEOUT.
  - A batch holding several FREE rows forms no cycle with single-hold claims.
- **Commit against undo.** V4's `forbid_commit_and_undo` takes the decision's advisory lock and
  checks for the other row (`V4:91-111`). Together with §3.2 and §3.3's DB-time triggers,
  "committed or undone, never both" holds. The lock-order reversal is m4's, retried, at the
  accepted cost of D2(c).
- **T2's lock, and the lookup of its own id.** With deterministic ids (finding 2 aside), a re-run
  or the recorder never reserves twice, and two writers never get one sequence.
- **T6 and T7's core claim**, finding 1 aside. Every later state is committed to PostgreSQL before
  it is published. The view is therefore never behind Kafka or the webhook. P18's case, a timeout
  before the sink, is answered correctly.
- **Webhook ordering.** `WebhookDispatcher` drops a same-or-lower sequence and supersedes a pending
  lower one, with `ON CONFLICT (event_id)` (`notify/webhook/WebhookDispatcher.java:37-51`). A
  re-record and T6's re-timeout under a new event id are therefore harmless.
- **The spool.** It is at-least-once per consumer and ordered per instance
  (`SpoolDrainer.java` run loop). Receivers are idempotent on event and label ids. Repeated
  recording is safe, m6's feature-store caveat aside.
- **`RedisHoldSchedule.schedule`** leaves an older member in the sorted set when a hold is
  rescheduled. Under H2's DB-time check, the stale member's claim is refused and rescheduled. This
  is benign.
- **D6** serialises with a timeout through the `hold_owners` row lock. A timeout that wins closes
  the original, and the escalation gets 409.
- **§6 against P1's code.** `AlertQueries.java:169` (open statuses only without `outcome`) and
  `:347-364` (the four queues) match. `AccountQueries.java:34-44` reads `decision_states`' latest,
  and §8 moves it to the view.
- **The audit chain's 40001** (`V8:93`) is retried by the runner. No contract transaction has an
  outside effect before commit, so the retry is safe.
- **Redundant timeout paths**, meaning two pollers after a lease lapse, local fallbacks and the
  sweep, are serialised by H1 and T2's own-id lookup.
