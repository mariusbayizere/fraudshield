# 0113: The final decision is read from PostgreSQL first

- **Status:** Accepted (owner, 2026-10-20)
- **Date:** 2026-10-20
- **Decided by:** the owner, on review 6 of the decision-commit contract and its delta review
- **Requirements affected:** D-14 (a recorded deviation), FR-03-02, FR-03-04
- **ADRs referenced:** 0058, 0064, 0068, 0111
- **Contract:** `docs/architecture/analyst-decision-commit-contract.md`, draft 7 (T4, T6, T7)

## Context

D-14 lets a bank poll `GET /api/v1/decisions/{transaction_id}` for a held payment's final decision.
M6 answers from Redis first, through `RedisDecisionStates.latest`, and from PostgreSQL only when
Redis has no key.

Review 6 of the decision-commit contract, and its delta review, found that this read can be wrong
for days:
- **A write that is lost after it was acknowledged.** Redis can acknowledge a write and then lose
  it: a crash under its once-a-second disk sync, or a failover with asynchronous replication. The
  key then comes back holding the older state.
- **How long the wrong answer lasts.** The read answers from that key until it expires, up to 8
  days. A bank told a payment was declined would be told so after the customer's "yes, this was me"
  had approved it.
- **Where the defect came from.** It was in M6 for a month, and five drafts of the contract carried
  it unreported. A draft-6 fix that made the recording wait for the Redis write closed only the case
  of a write that fails, not one that succeeds and is then lost.

## Decision

**`GET /api/v1/decisions/{transaction_id}` reads PostgreSQL first, and Redis only when PostgreSQL is
unavailable.**
1. **It reads `v_decision_states`** (contract T6): the model's decision, joined with every later
   state.
   - If the view holds any state of the transaction, the answer is the highest sequence there.
   - If it holds none, the model's decision has not yet reached PostgreSQL. The answer is then
     Redis's state, written at ingest. It is right, because every later state is committed to the
     view before it is published (option (a)), so a transaction absent from the view has no later
     state anywhere.
2. **When PostgreSQL is unavailable, the answer is Redis's state.**

The owner's reasoning, recorded as given:
- **The cache is a second source of truth.** "A cache serving a bank-facing decision is a second
  source of truth, and every attempt to make it reliable has added a mechanism that opened another
  hole."
- **The database is never behind.** Option (a) already commits every state after the first to
  PostgreSQL before publishing it.
- **The trade is not worth it.** The cache was buying latency on a status poll that is not on the
  ingest hot path. That is "a poor trade against telling a bank a payment was declined when it was
  approved".

## Costs, recorded

- **One database read per status poll.** It is one indexed read through the view, by
  `(institution_id, transaction_id)`, on the primary.
  - **Where the load lands.** It is not on the ingest path, but the bank's polling rate becomes
    PostgreSQL load.
  - **What limits it.** It is bounded by the per-key budget, which charges every request on the
    machine paths, the poll included (ADR 0058, point 2).
  - **Not measured.**
- **The D-14 deviation.** D-14's resolution names Redis, Kafka and the webhook as delivery routes,
  and M6 answered the poll from Redis first. The poll now reads PostgreSQL first. Webhook and Kafka
  delivery are unchanged. Recorded on D-14's register row.
- **What the Redis path still does, and when it can be stale.** Redis answers only when PostgreSQL
  cannot.
  - **No new state while PostgreSQL is down.** Every writer of a later state needs PostgreSQL: the
    analyst's effect, the timeout's claim (ADR 0111), an override, and the customer's answer. So no
    later state is created during the outage, and Redis cannot fall behind *during* it.
  - **Stale only from before the outage.** Redis can be stale during an outage only if it lost an
    acknowledged write before the outage began, the case this ADR removes otherwise. Such a stale
    answer lasts at most for the outage's duration: once PostgreSQL answers again, the read is
    correct.
  - **The bound is not measured.** No measurement of outage duration exists (ADR 0111's
    limitation).
- **Redis's role shrinks.** For final decisions it is now an availability fallback, not a source the
  read trusts.
  - **Best effort.** The writers still set it (T4), best effort and set-if-higher.
  - **No longer on the commit path.** A failure to set it is counted, never retried, and no longer
    holds up a reservation's recording (review 6's delta, findings D1 and D5).

## Consequences

- **Removed from the contract:** draft 6's ordering of the Redis set before the recording row,
  and the recorder's Redis step (review 6, finding 1).
- **Code changes:**
  - **The read:** P2 changes `getFinalDecision`'s read, and not `DecisionStatePort.latest`, which
    the decision path uses for its own purposes.
  - **Who changes it:** P2 owns the change, because P2 delivers the view.
- **A test must pin the order** (contract P20): a Redis key holding an older state never wins
  over the view.
