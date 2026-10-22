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
5. **Does `listAlerts` list an alert whose decision is still PENDING_COMMIT, or one just
   escalated?** The contract does not say. The console takes a decided alert out of the feed at once (FR-04-04) and, so that a
   re-read cannot put it straight back, does not re-read the feed until the 60 s refresh or an
   Undo. If listAlerts lists it, the decided alert reappears in the analyst's feed at the next
   refresh. The contract should state which statuses the standard queue holds.
6. **What time zone is `BehaviourProfile.hourly_counts` in?** Its 24 numbers are counts by hour of
   day, and the contract does not say whose day: UTC, the institution's zone or the account's. The
   console labels the hours 00 to 23 without a zone (screen 4). An analyst who reads "busiest at
   08:00" needs to know which 08:00. The comparison's `local_hour` suggests local time; the
   contract should say so on `hourly_counts` too.
7. **Which `comparison.attribute` names can the server send?** The contract gives a free string of
   up to 64 characters (`local_hour` in the example). Without the list, the console cannot give
   them plain-language names in four languages, so it shows the raw key, as the SHAP table shows
   feature names. An enum, or a catalogue beside the 44 features, would let the names be written
   and reviewed with D-45's templates (PB-132). The names belong to PB-132's owner (owner,
   2026-10-19); M8B's part is the list.
8. **Are the account context's lists unique?** Nothing in the contract says a network's node
   tokens, its (source, target) pairs, the profile's comparison attributes or channels, or a
   history's transaction ids are distinct (no `uniqueItems`, no stated key). The console keeps every
   row it is sent and draws a repeated network token once, where it first appears. The contract should state the keys.
9. **How is the anomaly queue ordered, and why is each alert in it?** `queue=anomaly` lists
   Isolation-Forest-only alerts (D-10), so every one is ANOMALY and has an ensemble score below
   0.60. `sort=priority` is defined as HIGH first, then fraud probability descending: in this
   queue that orders by the supervised score, which is low for all of them by construction, and
   not by how anomalous each is. And `AlertSummary` has no `anomaly_score` (only `AlertDetail`
   does), so a card cannot show why it is in the queue, and its gauge shows the low fraud
   probability. The contract should say what `priority` means for this queue (the anomaly score,
   descending, would be the natural reading) and carry `anomaly_score` on `AlertSummary`. Until
   then the console shows the server's order and the card as it is (screen 5). Related: the
   contract does not say whether `DashboardStats.reviewed_today` (and the average review time)
   counts reviews of anomaly alerts; the anomaly queue shows no figures meanwhile.
10. **Can a search span the queues?** `listAlerts` takes one `queue` a call, standard by default,
    so FR-04-09's search (screen 7) finds only standard-queue alerts: an anomaly or an escalation
    with the searched transaction ID is not found. A `queue=all` (or an omitted queue meaning all)
    would let one search cover them.
11. **A daily series for "My performance"?** UX-ROLE-AN-04 asks for a daily trend; `AnalystPerformance`
    has today's figures only. Without a series (e.g. the last 30 days' reviews and average time)
    the trend cannot be drawn (screen 8).

## Before starting

Claim backlog, ADR and Flyway ids in `docs/backlog/product.md`'s claims table before use. Any
cache or concurrency rule this milestone adds is written as a contract and reviewed before the code
(the API-key cache's lesson, ADR 0073).

## Numbering reserved by this branch

Claimed 2026-10-20, after reading every branch's updates files, the backlog's claims table, and the
ADR and migration directories of every remote branch.

| Kind | Range | Used |
|---|---|---|
| ADR | `0110`–`0119` (`0080`–`0089` are M8's, `0090`–`0099` M9's, `0100`–`0109` M10's) | `0110` (P1's contract changes), `0111` (held payments wait for the arbiter), `0112` (how P1 serves the reads), `0113` (the final decision is read from PostgreSQL first), `0114` (one owner per transaction's decision); claimed for P2's contract additions: `0115` (the real-time channel), `0116` (alert reassignment), `0117` (team performance and P2's figures) |
| Flyway | `V80`–`V89` (`V60`–`V69` are M6's, `V70`–`V79` M7's) | `V80` (P1: SHAP base value and margin, counterparty index); `V81` (P2: the decision-owner contract, ADR 0114); planned `V82` (configuration contract), `V83` (P2: the institution's time zone and the decision-duration trigger, ADR 0117) and `V84` (P2: alert assignments, ADR 0116), because P1 merges first (ADR 0112) |
| Backlog | `PB-151` to `PB-170` (`PB-131`–`PB-140` are M8's, `PB-141`–`PB-150` M11's) | `PB-151` to `PB-161` |

The scope and the owner's answers are in `M8B_scope.md`.

## For M8: the console's half of the CSRF pin (ADR 0110 point 1)

The contract now names the CSRF cookie: `components.parameters.CsrfToken["x-csrf-cookie"]` is
`fs_csrf`. The server asserts its constant against the packaged contract (`CsrfCookieContractTest`).
The console should do the same, so that neither side can drift from the contract:

- **What to add.** A Vitest case in `frontend/src/auth/csrf.test.ts` that reads
  `contracts/openapi/fraudshield-api.yaml` and asserts `CSRF_COOKIE` equals that value, and that
  the header the console sends is the parameter's `name`.
- **Further console changes ADR 0110 asks for.** Regenerate the client, then:
  - show `masked_account` (no longer a holder name). Until the owner decides ADR 0110 point 3 it
    is always null, and the card must render its absence without showing "null";
  - pass `outcome` in search, or decided alerts are not found;
  - show `anomaly_score` on the card;
  - search with `queue=all`;
  - use the closed comparison-attribute list.

## For M12: an identity recorded but not proven (owner, 2026-10-22)

The owner asked for this to be flagged to M12's audit.

**What happens.** The decision-owner contract's operator exits are SQL functions:
`resolve_contested_decision` and `release_failed_claim` (`docs/architecture/decision-owner-contract.md`,
§7.2). Each takes the acting risk officer's `staff_user_id` from its caller, and cannot authenticate
it.

**What is recorded.** Each resolution records:
- the asserted staff user, with the database login (`session_user`) beside it;
- an audit entry for the authorisation, in the function's transaction;
- a second audit entry for the state change, written by the resolver.

**What is not proven.** The officer named on a resolution has not been shown to be the person who
acted. Accountability rests on M9's runbook giving each operator an individual database login that
is granted the functions, never a shared one.

**What an auditor will ask.** "Who resolved this contested payment, and how do you know?" Today the
answer is "the login that called the function, and the officer that login asserted". M12 should
decide whether that is acceptable evidence. If not, the remedy is an authenticated operator
endpoint. That would be a frozen-contract change, so a reviewed proposal comes first.
