# M8B: the analyst API

Created 2026-10-18 by the owner's decision. Branch: `m8b/analyst-api` (not created yet).

## What M8B is

The 48 contract operations no milestone built (PB-121): the analyst-facing API behind M8's
console. The scope, sizes and the order to build in are in `docs/parallel/M7_updates.md`, "Unowned
work: the analyst API". It is about 8,000 to 12,000 lines, 15 to 18 controllers and 15 to 20
services. Suggested order:
1. alerts and decisions;
2. thresholds, dual control and rules;
3. models, campaigns and SAR.

**Placement (owner, 2026-10-18).**
- It is called M8B so the existing numbering holds.
- It runs alongside M9.
- **It must be on `main` before M10 starts.**
- In the traceability tooling it sorts after M9 and before M10. Completing M9 does not hold M8B's
  rows closed; completing M10 does (`traceability.py`, `MILESTONES`).

## Mocks are not evidence (owner, 2026-10-18)

M8 builds the console against mocks generated from the frozen contract. **A mock generated from
the contract is not evidence that the API works.** M8's console passing against mocks says nothing
about the real endpoints, and M8's rows must say so rather than read DONE. What follows is derived
from that clause, not quoted:
- **M8's own rows:** any row whose criterion depends on the analyst API must say so in its notes
  rather than read DONE. Its status stays IN_PROGRESS until the M8B endpoint behind it exists and
  the console is exercised against it.
- **M8B's acceptance:** each operation is implemented to the contract and tested against the real
  application. A console test against a mock never counts as M8B's evidence.

## Rows carried to M8B (owner decision 2026-10-18; `traceability_seed.py` records each)

| Row | What remains | Backlog |
|---|---|---|
| NFR-SEC-03 | The inspector test that every analyst endpoint shows only masked tokens | PB-114 |
| FR-06-06 | Each audit type asserted from a real action. Six types (ANALYST_DECISION, SENIOR_OVERRIDE, THRESHOLD_CHANGE, RULE_CHANGE, MODEL_LIFECYCLE, REGULATORY_REPORT) are emitted end to end only by these endpoints. Two more, AUTO_BLOCK and CUSTOMER_VERIFICATION, are emitted by M6 code with no end-to-end assertion; they go with the row, so M8B asserts them through the assembled application. Also a test that every write endpoint is audited | PB-109 |

## Acceptance carried from PB-121

- Each group's operations are implemented to the contract.
- NFR-SEC-03's inspector test.
- `decideAlert` calls M6's `DecisionTransitionService.analystDecidesHold`.
- Confirmed fraud reaches `CircuitBreakerPort.countConfirmedFraud`.

## Before starting

Claim backlog, ADR and Flyway ids in `docs/backlog/product.md`'s claims table before use. Any
cache or concurrency rule this milestone adds is written as a contract and reviewed before the code
(the API-key cache's lesson, ADR 0073).
