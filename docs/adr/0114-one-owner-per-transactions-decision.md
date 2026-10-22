# 0114: One owner per transaction's decision

- **Status:** Proposed. It becomes Accepted when the contract is reviewed clean.
- **Date:** 2026-10-22
- **Decided by:** the owner, on review 8 of the decision-commit contract
- **Requirements affected:** D-14, D-44, FR-03-02, FR-03-05, FR-04-10
- **ADRs amended:** 0067 (points 3 and 4), 0111 (its contract reference), 0113 (its contract
  reference)
- **Contract:** `docs/architecture/decision-owner-contract.md`, which replaces
  `docs/architecture/analyst-decision-commit-contract.md`

## Context

Eight drafts of the decision-commit contract added arbiters and rules between the writers of a
transaction's later states: the timeout, the analyst, customer verification and a senior override.
Each rule closed one interleaving and opened another.

Review 8 found three MAJORs:
1. **M6's.** A timeout acting from a scheduled hold after a retry had decided afresh.
2. **Draft 8's.** A freeze with no exit.
3. **Draft 8's.** A senior-block rule that missed the common case.

The owner's rule was that a MAJOR in draft 8 meant the queue semantics needed rethinking, not the
wording.

## Decision

**Each transaction that any later request touches gets one owner: a PostgreSQL row,
`decision_owners`.** Every later change to that transaction is a request to it.
- **The requests:** the timeout, the analyst's effect, an escalation, a senior's override, the
  customer's answer and an operator's resolution. None writes a state in its own right.
- **How a request is decided.** It is evaluated under the row's lock, against one rule table.
- **What the owner holds:** the state, the review claim and the contest.

**The owner's further decisions, recorded with this one:**
- **Two different first decisions make the transaction contested.** That is a state with an
  explicit exit, a risk officer's resolution, and an alarm. While contested, work is deferred. After
  the resolution, a deferred analyst decision keeps its label and is named, but its state is not
  applied.
- **A senior's decline is recorded as a state even when the value does not change.** The customer
  path can confirm a payment the system doubted, but cannot overturn a human who declined it.
- **The new contract is numbered from draft 1,** because what it promises is different. Its §12
  lists what the old contract promised that it does not.

## Amendments

- **ADR 0067, points 3 and 4,** accepted that a retry inside an expired lease decides afresh.
  - **Now:** an INFLIGHT record outlives its lease and is verified against PostgreSQL once the lease
    has passed (contract §7.5).
  - **What remains:** a double decision that still occurs is detected by the owner and made
    contested.
- **ADR 0111 and ADR 0113** stand. Their references to `hold_owners`, `transaction_transitions` and
  the old contract's rules now mean `decision_owners`, `transaction_requests` and the new contract's
  §5, §6.3 and §8.

## Consequences

- **Seven transitions are added to `FinalDecision`** (contract §5.1).
  - **A repeated value.** A bank can receive a final decision whose value repeats the previous one,
    at a higher sequence.
  - **The contract change.** It is a change to the frozen contract, proposed with P2.
- **A customer's "yes" waits while a human reviews,** and is refused after a human's fraud decision.
- **The analyst API answers 409 `decision-contested`** on a contested transaction, and the poll
  answers 503.
- **No measurement exists of how often M6 decides a transaction twice.** The contested path's load
  on risk officers is therefore unknown.
- **M6's double decision in reverse order remains** (contract §7.3). A timeout can release a payment
  before the retry's decline reaches PostgreSQL. It is common after a PostgreSQL outage, detected
  when the decline drains, and resolved by a risk officer. The bank may already have acted on the
  release.
- **The stopping rule** for this contract's reviews (owner, 2026-10-22): stop on a MAJOR in the
  owner mechanism itself, meaning the owner row, its lock, or the rule table's ordering. An
  edge-specification error with a local fix is draft work.
