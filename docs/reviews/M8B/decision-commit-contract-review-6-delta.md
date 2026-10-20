# Review 6, delta: the answers to review 6 of the decision commit contract

Subject: `git diff 5946878 ee8f702 -- docs/architecture/analyst-decision-commit-contract.md`
(branch `m8b/analyst-api`, local commit `ee8f702`).

Reviewer: independent. I wrote none of the drafts and none of the earlier reviews.

Method: reading only. No build, test or container was run. Contract line numbers (`C:<n>`) are at
`ee8f702`. `decision/` is `backend/decision/src/main/java/io/github/mariusbayizere/fraudshield/decision/`;
`V<n>` is `backend/persistence/src/main/resources/db/migration/V<n>__*.sql`.

Scope, as instructed: whether review 6's findings 1 to 7 are resolved as stated, and whether the
edits introduce a new error or MAJOR. Nothing else is re-reviewed.

## Verdict: NOT_CONFIRMED

**MAJOR remaining: YES**

- The one MAJOR is review 6's finding 1, **partly resolved**. The new order (spool, Redis, then the
  recording row; T5 redoes all three) fixes the case review 6 described: a Redis write that
  **fails and is reported**. It does not fix a Redis write that **succeeds and is later lost** (an
  AOF `everysec` crash, or a Sentinel failover with asynchronous replication). The key then holds
  the older state, the recording row exists, nothing re-sets it, and `GET /decisions/{id}` returns
  the superseded state until the key's 8-day expiry. That is the same consequence, from one fault.
- The MAJOR is **pre-existing** (M6 and every draft have it). The delta did not introduce it and
  introduced no new MAJOR. But the caller's question (1) asks about exactly this case, and the
  answer is yes, so finding 1 cannot be confirmed as resolved.
- Findings 2 to 7: resolved, with small residues (D1 to D5 below, all MINOR).

| Review 6 finding | Status |
|---|---|
| 1 MAJOR, Redis write never retried | **PARTLY RESOLVED**: reported failures fixed; lost acknowledged writes not (D0, MAJOR); the existing wrapper hides failures (D1, MINOR, required) |
| 2 view isolation, listers | RESOLVED, with a contradiction in the text and a missing `fs_app` grant (D2) |
| 3 missing properties | MOSTLY RESOLVED: P18, P19 added; two of the asked properties missing (D3) |
| 4 costs section | RESOLVED |
| 5 old dead letters | RESOLVED for the case review 6 described; "benign" is over-general (D4) |
| 6 wording | RESOLVED |
| 7 `CHECK ... NOT VALID` | RESOLVED |
| New from the edits | D5 (MINOR): re-recording now repeats the spool append for every reservation on every recorder pass while Redis is down |

## D0. MAJOR (pre-existing, finding 1's residue): a Redis write that succeeds and is then lost leaves `GET /decisions/{id}` stale for up to 8 days

**Rules:** T4 (C:312-323): the recording row is written only after the spool record and the Redis
set succeed. T5 (C:325-327): the recorder lists only reservations with no recording row.

**Evidence from the code:**
- `GET` reads Redis first and returns any cached value (`RedisDecisionStates.java:79-92`).
  PostgreSQL is read only when the key is absent or Redis is unreachable.
- A key's value lives 8 days from its last successful write (`RedisDecisionStates.java:28, 37`).
- The local stack runs Redis with `appendonly yes`, `maxmemory-policy noeviction`, and the default
  `appendfsync everysec` (`docker-compose.yml:118`). The master prompt's production target is
  "Cluster/sentinel" (`docs/prompts/FraudShield_Master_Build_Prompt.md:257`). Both lose writes
  that Redis acknowledged: up to one second on a crash, or the replication lag on a failover.

**The three cases the caller asked about:**

1. **A key that expires or is evicted after being set.** Safe.
   - An expired key is absent, so `GET` reads `JdbcDecisionStates`, which T6 moves to the view.
     The view holds the latest state.
   - Eviction cannot happen under `noeviction`. A write refused for memory is an error, so T4 sees
     a failure and T5 retries.
   - The only way back to a stale value is a **lower** write into an absent key (see case 2).
2. **A Redis failover or crash that loses keys.** Not safe.
   - **Whole key lost:** safe. `GET` falls through to the view.
   - **Latest write lost, older value kept:** not safe. Interleaving:
     1. Transaction X, HIGH, sequence 1 DECLINE, cached at ingest; the AOF has synced it.
     2. The customer answers yes. T2 reserves sequence 2 APPROVE; T4 spools it (the bank gets
        APPROVE by webhook), sets Redis (acknowledged), and writes the recording row.
     3. Within the fsync second, or before the replica receives it, Redis crashes or fails over.
        The key comes back holding sequence 1.
     4. T5 sees the recording row and does nothing. No other path writes the key.
     5. For up to 8 days, `GET /decisions/X` answers DECLINE, while the view and the webhook say
        APPROVE.
   - The same applies to a timeout, an analyst's E1 and an override; every later state written in
     the loss window is affected.
   - **A lower write into a key the failover emptied** is a rarer variant: a reservation still
     unrecorded (sequence 2, its Redis write failed) while a later one (sequence 3) was recorded,
     then the key is lost, then T5 records sequence 2 into the absent key. Set-if-higher cannot
     refuse a write into an empty key, so `GET` answers sequence 2 until expiry.
3. **Set-if-higher against a key holding a lower sequence written by M6's path.** Safe, provided
   the script is the one §8 asks for.
   - M6's script is set-if-**next** (`RedisDecisionStates.java:32-39`: it writes only when
     `current + 1` equals the new sequence, and returns 0 otherwise).
   - The contract replaces it for later states with set-if-higher (C:314, C:572). That overwrites
     sequence 1 or an M6 sequence 2 with any higher sequence, and an absent key is treated as
     sequence 0, so it is set.
   - Kept as set-if-next, it would refuse sequence 3 over sequence 1 and refuse sequence 2 over an
     absent key. The contract should say set-if-higher replaces M6's script for every writer
     (see D1's second point).

**Why it is a MAJOR:** it makes `GET /decisions/{id}` wrong for up to 8 days from one fault, which
is the consequence review 6 classed as MAJOR. It is M6's, and the delta neither causes nor worsens
it. But "T5 redoes all three" does not reach a reservation whose recording row exists, so the
answer to question (1) is yes.

**Fix (one rule, any of these):**
- **Re-assert recent states.** T5 also runs the Redis set-if-higher alone (no spool, no row) for
  every reservation recorded within the last W, with W greater than Redis's worst loss window
  (fsync interval or replication lag, plus failover time, plus P). It is idempotent and needs no
  lock.
- **Or re-assert on restart.** Each instance watches Redis's `run_id` and `master_replid`. On a
  change, the recorder re-asserts every reservation recorded within W of the change, or within
  `RETENTION` for a restore from a backup.
- **Or state it as an accepted limitation**, with its bound (up to 8 days), in §9 and as an
  operator runbook step: after a Redis failover, crash or restore, delete the decision keys, so
  `GET` reads PostgreSQL.
- Add a property: a later state whose acknowledged Redis write is lost by a restart is back in
  Redis, or absent, within W + P.

## D1. MINOR (required): the existing Redis wrapper turns a failed write into success, and "succeed" is not defined

**Rule:** T4 (C:315-317): "Only after both succeed ... A failed Redis write therefore leaves the
reservation unrecorded."

**Evidence:**
- `ResilientPorts.decisionStates(...).save` is `attempt(mode, () -> redis.save(state), () -> true)`
  (`ResilientPorts.java:102-103`). A Redis exception returns `true`.
- Inside the one second after any Redis failure, `attempt` skips Redis entirely and returns the
  fallback, `true`, without trying (`ResilientPorts.java:43-45`; `DegradedMode.java:40-42`).
- §8 does not list `ResilientPorts` among the changes. If T4 and T5 call Redis through the
  existing `DecisionStatePort`, a failed write writes the recording row, and finding 1 returns
  exactly as review 6 described.
- `RedisDecisionStates.save` returns `false` both when it refuses a write and when it does not
  store one. If T4 reads `false` as failure, a reservation whose key already holds a higher
  sequence is never recorded, and T5 re-spools it on every pass, for ever.

P20's test would catch the first point only if it injects the failure below the wrapper.

**Fix:**
- T4 and T5 call `RedisDecisionStates`' set-if-higher directly, never the resilient wrapper, and
  treat any exception, and the degraded skip, as failure. List the change in §8.
- Define success as the script running: stored, or ignored because the key holds the same or a
  higher sequence. The script treats an absent key as sequence 0. It replaces M6's set-if-next for
  every later-state writer.

## D2. MINOR: finding 2 resolved, but T6 and §8 contradict the listers' rule, and `fs_app`'s grant on the base table is not stated

- **Resolved.** The view is `security_invoker` (C:354-358), as `v_alert_decision_state` is
  (`V4:113`). The listers read the base tables as their owner (C:360). `decision_states` and the
  new tables use `ENABLE ROW LEVEL SECURITY` without `FORCE` (`V1:47`), so the owner bypasses RLS
  as V62 and V64 do today. The read-only grants match `V11:56-69`.
- **Contradiction.** C:360 says the V62 and V64 listers read the base tables directly. Two lines
  later, the list of "readers that move to it" still names "V62's lister" and "V64's lister"
  (C:363-364), and so does §8 (C:573-575). Either works: a `security_invoker` view read inside a
  SECURITY DEFINER function runs as the owner, who bypasses RLS. But the text should say one thing.
- **Grant.** A `security_invoker` view needs SELECT on `transaction_transitions` for `fs_app` too.
  §3.6 item 6 says only "tenant-isolated" (C:153), and states no grant to `fs_app` for
  `transaction_transitions` or `transaction_transition_recordings`. Compare `hold_owners` (C:147).
  State SELECT and INSERT to `fs_app` on both.

## D3. MINOR: finding 3 mostly resolved; two of the asked properties are missing

- **Added.** P18 (review 5's property) and P19 (the sink writes no later state; the CHECK refuses a
  stray one) (C:552-553).
- **Missing.**
  - A `DecisionChanged` with sequence > 1 and no reservation is counted and dropped (T6, C:343-348).
  - P18's case where the ingest record is dead-lettered, so sequence 1 never arrives. Review 6
    checked every reader against a lone sequence 2, and nothing pins it.

## D4. MINOR: finding 5's "benign" is shown for a timeout's dead letter only

**Rule:** C:343-348: "the outcome is benign: the overdue sweep times the transaction out again."
- Review 6 showed this for the timeout's 23503 dead letter, the one M6's race produces.
- A pre-V81 dead letter of another later state, for any other cause, is not shown to be benign.
  - An analyst's HOLD decision dropped this way leaves sequence 1 HOLD with no later state. The
    sweep then times the transaction out, and PostgreSQL and `GET` report the timeout's outcome,
    while the bank received the analyst's by webhook. M6's sweep does the same today, so it is not
    worse.
  - A customer's unblock is re-derived by `UnblockReconciler` under its deterministic id, so it is
    benign.
- **Fix:** say "benign for a timeout's or an unblock's record", and have A3 list the dead-letter
  directory and resolve any other kind before V81.

## D5. MINOR (from the edit): during a Redis outage, every recorder pass repeats the spool append

- Under the new order, a reservation stays unrecorded for as long as Redis is down. T5 performs
  all of T4 on every pass for every such reservation older than 30 s (C:325-327). So each one is
  appended to the spool, and published to Kafka, about every P (500 ms) for the whole outage.
- Nothing is doubled. Receivers ignore repeated event ids. `label_events` inserts with `ON CONFLICT
  DO NOTHING` (`PostgresSink.java:138-141`), and its ids are deterministic (T3). But the volume grows
  with the outage's length times the number of reservations, which §9 does not state.
- **Fix:** review 6's alternative. Keep two markers (spooled, cached), and have T5 repeat only the
  step that is missing. Or state the cost and back off the recorder while degraded.

## Findings 4, 6 and 7: resolved

- **4.** §9 now names ADR 0064 and D-13 (C:591), which matches ADR 0064's Context ("without putting
  PostgreSQL on the hot path (D-13)", `docs/adr/0064-durable-spool-and-hold-reconciliation.md:13`).
  It states that PostgreSQL leads Kafka (C:599-601). It names `decision-fact-ordering.md` line 209
  and `SmsOrderingTest` line 486, both correct at `ee8f702`, and the read-only roles (C:605-607).
  - Note: `SmsOrderingTest` asserts that the SQL contains `"decision_states"`, which
    `v_decision_states` also contains. The test stays green after the move without proving it, so
    the amendment should tighten the assertion.
- **6.** T6 now says labels only (C:341-342). §3.6 says a backfilled body holds only the
  `DecisionState` and is never re-recorded (C:159-162).
- **7.** `CHECK (decision_sequence = 1) NOT VALID` (C:349-350). It is correct: NOT VALID skips the
  existing rows and checks every new insert or update. `pg_dump` restores NOT VALID checks after
  the data, so A2's restore is unaffected. It needs A3's order, V81 after the old sink has drained,
  or the old sink's later-state inserts fail with 23514. A3 already gives that order.

## The edits introduce no new error that loses or doubles a state

- The new T4 order adds no lost state: the spool record still precedes the row, and the row still
  gates only T5's retry.
- The edits add no doubled effect: every repeated step is idempotent (D5).
- The new T6 text, the view, the CHECK and the §9 additions introduce no new MAJOR.
