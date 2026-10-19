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
- **A deviation never promotes a row past this rule** (owner, 2026-10-19). A recorded deviation
  (ADR 0083's cards, ADR 0084's table) is about how a requirement is met; a row whose data comes
  from an endpoint nobody has built stays IN_PROGRESS whatever its deviations say.

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

## Contract additions and operations M8B owns (owner, 2026-10-18)

Recorded from M8's analyst screens. Each is a contract change first, reviewed on its own, then the
implementation; M8's console does not build against any of them until both exist.

1. **`GET /system/status`, the staff-readable degraded modes** (E.9's banners). The proposal is on
   `m8/system-status-proposal` (ADR 0081, Proposed; reviewed SOUND_WITH_MINORS after one MAJOR was
   fixed). Main implements neither it nor `GET /admin/health`. **Three design decisions are open,
   and the proposal does not settle them:**
   - **How `KAFKA_SPOOLING` is detected.** Every event passes through `DurableSpool`, which exposes
     only byte gauges (`depthBytes`, `lagBytes`); there is no "spooling" state, so a threshold or a
     Kafka-failure signal has to be defined.
   - **Whether `REALTIME_PAUSED` belongs on the server at all.** Nothing on main tracks it, and
     there is no real-time channel yet (item 2); it may be the console's own state (a dropped
     socket) and leave the server's enum.
   - **How instances agree.** `ML_UNAVAILABLE` (the scorer's circuit breaker) and `DEGRADED_MODE`
     (`DegradedMode`, true while the last Redis call failed) are per JVM, so two instances can
     answer differently and the banners can flap behind a load balancer.
2. **The real-time alert channel** (FR-04-02's "new alert on all connected dashboards within 1
   second", UX-DASH-01's real-time figures). It is not in `contracts/` at all. Until the contract
   has it and M8B serves it, the console re-reads every 60 s, and FR-04-02 and UX-DASH-01 are
   BLOCKED. The console will not design the channel itself.
3. **Alert reassignment** (UX-ROLE-SN-02: a senior analyst reassigns an alert or self-assigns from
   the unassigned pool). No contract operation exists. Unowned work, filed here like the analyst
   API.
4. **Team performance** (UX-ROLE-SN-04: every analyst's reviews per hour, accuracy, average review
   time, CSV export). Only `getMyPerformance` exists. Unowned work, filed here.

## Before starting

Claim backlog, ADR and Flyway ids in `docs/backlog/product.md`'s claims table before use. Any
cache or concurrency rule this milestone adds is written as a contract and reviewed before the code
(the API-key cache's lesson, ADR 0073).
