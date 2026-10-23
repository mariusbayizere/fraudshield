# 0111: Held payments wait for the arbiter, through a PostgreSQL outage and an escalation

- **Status:** Accepted (owner, 2026-10-20: the answers to Q2 and Q3 of the decision-commit contract)
- **Date:** 2026-10-20
- **Decided by:** the owner. The author recorded it, as review 4 of the contract asked (finding 9).
- **Requirements affected:** FR-03-02, D-18, D-10, FR-04-10
- **ADRs amended:** 0064 (the durable spool, and reconciling lost holds)
- **Contract:** `docs/architecture/analyst-decision-commit-contract.md` (H1, H2, D6)

## Context

M6 times out a MEDIUM hold from Redis alone: the leader's poller, or each instance's local
fallback while Redis is down (ADR 0064). PostgreSQL is not needed for the timeout to happen.

M8B's decision-commit contract makes PostgreSQL the arbiter of who decides a held payment, the
analyst or the timeout (`hold_owners`). A timeout that skipped the arbiter could release a payment
an analyst had already declined, with a committed decision the bank cannot undo.

The owner was asked two questions:
- what happens to held payments while PostgreSQL is down (Q3);
- what an escalation does to the 30-second timer (Q2).

## Decision

1. **During a PostgreSQL outage, held payments stay held until the database returns** (Q3). No path
   times out a hold without claiming it in `hold_owners` (contract H2).
   - **This amends ADR 0064.** Its local-fallback timeout, which acts without PostgreSQL, now
     reschedules instead, and the overdue sweep waits for the database as before.
   - The owner's reason: "A delayed payment is recoverable; a timeout that contradicts an analyst's
     committed decision is not."
2. **An escalation stops the timer** (Q2). An escalated MEDIUM payment is held until a senior
   decides, or until the escalation window W passes. Then the channel's MEDIUM timeout policy
   applies (release, or decline for verification), and the escalation stays on record.
   - The owner's reason: "An escalation that expires before a senior can see it is not an
     escalation."
3. **W is an assumed value, 15 minutes by default, and configurable. It is not a requirement.** The
   reasoning, which nothing has measured, is in the contract's D6:
   - the console re-reads the escalated queue every 60 s;
   - a hold of about a quarter of an hour is roughly where a customer starts to call.

## Deviations recorded

| Requirement | SRS | Under this ADR |
|---|---|---|
| **D-18** | MEDIUM auto-release at 30 s ± 500 ms | Not met **during a PostgreSQL outage**: the payment is held for the outage's duration. Met otherwise. |
| **FR-03-02** | a 30-second countdown, then auto-release | Not met for an **escalated** payment, which waits up to W, nor during a PostgreSQL outage. Met otherwise. |

## Consequences

- **No measurement exists of how long PostgreSQL outages last.** The size of the D-18 breach
  cannot be stated from evidence; it is bounded only by each outage's duration.
- **Escalated payments wait up to W.** D-10's capacity model, which does not exist yet, should
  replace the assumed W.
- **Effective dates.** Both deviations take effect when P2 ships the arbiter. Until then, M6's
  behaviour, and ADR 0064 as written, stand. The register rows for D-18 and FR-03-02 say so.
- **Visibility.**
  - **While the outage lasts,** the live signal is the count of claims the timeout paths could not
    make and rescheduled (contract H2). M9's alert rules should alert on that count.
  - **Afterwards,** `DecisionMetrics.holdLateness` records each hold's lateness once it is finally
    timed out, so the outage shows up in the lateness distribution.
