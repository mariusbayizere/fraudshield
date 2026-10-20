# M8B scope: the analyst API, in phases (proposed 2026-10-20, for the owner's approval)

**Status: approved by the owner on 2026-10-20, with the answers in §7.** This file is the scope
the owner asked for before any code: the 48 operations grouped into phases, the design questions each phase
needs answered first, what M8's rows need from each, and which M10 items depend on which.

**Sources:**
- `docs/parallel/M8B_updates.md`. Items 1 to 11 are on `m8/frontend-rebase`, not yet on `main`.
- `docs/parallel/M7_updates.md`, "Unowned work: the analyst API".
- `docs/parallel/M8_updates.md` §10, on `m8/frontend-rebase`.
- The frozen contract, `contracts/openapi/fraudshield-api.yaml`.
- The register, `docs/traceability/requirements.yaml`.

**Checked on `main` (`a7f0528`):** none of the 48 operations has a controller. 36 are implemented
(7 by M6, 29 by M7), and 5 are served on the management port only. That matches M7's count.

## 1. The phases

| Phase | Operations | Count | Backing code that exists | Size |
|---|---|---|---|---|
| **P1 Alert and account reads** | `listAlerts`, `getAlert`, `getAlertExplanation`, `getScoringResult`, `listAccountTransactions`, `getBehaviourProfile`, `getAccountNetwork` | 7 | The alert tables (V4); `PostgresSink` writes queue entries; `fraud_scores.shap_all`; account context (V66) | M |
| **P2 Decisions** | `decideAlert`, `undoAlertDecision`, `escalateAlert`, `overrideAlertDecision`, `getDashboardStats`, `getMyPerformance` | 6 | `DecisionTransitionService.analystDecidesHold` and `CircuitBreakerPort.countConfirmedFraud`, both called only by tests; the labels table (V5) | L |
| **P3 Operations** | `getSystemHealth`, `listFailedWebhookDeliveries`, `getDeploymentEnvironment` | 3 | `webhook_deliveries`; Prometheus; the `fraudshield.auth.environment` property | S–M |
| **P4 Dual control, thresholds, breakers** | `getThresholds`, `previewThresholdImpact`, `proposeThresholdChange`, `getCircuitBreakerSettings`, `proposeCircuitBreakerChange`, `listCircuitBreakers`, `listConfigChanges`, `getConfigChange`, `withdrawConfigChange`, `approveConfigChange`, `rejectConfigChange` | 11 | `DualControlWorkflow`, in memory only; the config tables (V6); the breaker ports | M–L |
| **P5 Rules** | `listRules`, `createRule`, `previewRule`, `createRuleVersion`, `setRuleState` | 5 | The rule DSL and compiler; JPA rule entities and cache | M |
| **P6 Risk analytics and campaigns** | `getPortfolioSummary`, `getFraudHeatmap`, `getModelPerformance`, `listCampaigns`, `updateCampaignStatus` | 5 | Labels, decisions, the V10 aggregates; campaign tables only, with **no detection code** | S–M for the endpoints, M–L for detection |
| **P7 SAR** | `listSarReports`, `getSarReport`, `signOffSarReport` | 3 | The table only; **no draft generator** | M |
| **P8 Models, datasets, retraining** | `listModelVersions`, `promoteModel`, `rollbackModel`, `setShadowModel`, `listTrainingDatasets`, `uploadTrainingDataset`, `startRetraining`, `getRetrainingJob` | 8 | Python's MLflow registry; tables with no Java user (V7); **no orchestrator** | L |
| | | **48** | | |

**Contract additions outside the 48.** Each of these is a contract change first: a reviewed
proposal, then the code. These are items 1 to 4 of M8's list.
- **The real-time alert channel:** with P2.
- **Reassignment:** with P2.
- **Team performance:** with P2.
- **`/system/status`:** with P3.

**Why this order.** It differs from M7's suggested order in two places, both to unblock work
sooner.
1. **P1 first.** It has no side effects, and it carries the most of M8's waiting rows: 14 of the
   36 IN_PROGRESS (22 with P2). It also sets the patterns every later phase reuses:
   - the caller's institution scope;
   - masked tokens and NFR-SEC-03's inspector, built here as a test over every analyst endpoint
     and extended each phase;
   - cursor paging;
   - the controller and service layout.
2. **P2 next, because it is the riskiest piece of design in M8B.**
   - The five-second undo needs a decision to move from PENDING_COMMIT to COMMITTED on a timer,
     with its side effects firing once and on every instance.
   - It is the first consumer of M6's two hooks.
   - It emits the first two audit types FR-06-06 needs.
   - The real-time channel lands here because it pushes the same events.
3. **P3 is moved forward.** M7 grouped these operations last, but they are small. `getSystemHealth`
   is what M9's FR-06-05 waits on, and M9 runs in parallel with M8B now. `/system/status` sits
   beside it (E.9's banners on every screen).
4. **P4 and P5 follow.** Most of their backing code exists. P4 comes first because dual control
   has to be persistent before anything relies on it, and rules may go through it too (see
   question P5-a).
5. **P6, P7 and P8 come last. They are new domain work, not endpoints:** campaign detection, SAR
   drafting, and a Java path to MLflow plus a retraining orchestrator. P8 is the largest and the
   most expensive in memory.

**Delivery.** I propose each phase reaches `main` on its own, after an independent review and CI
at job level. M10 cannot start until all of M8B is on `main`, so landing early phases early lets
M8 re-run screens against real operations while later phases are built. This is your decision
(question X-3).

## 2. Design questions to answer before each phase

**Before any phase (cross-cutting):**
- **X-1. Claims.** Backlog, ADR and Flyway numbers are claimed in `docs/backlog/product.md`
  before use.
- **X-2. Module placement.** Do the analyst controllers go in a new `backend/analyst` module or in
  `decision`? Either way the assembled API application has to load them, as M7's admin module
  is loaded (ADR 0073).
- **X-3. Delivery.** One merge per phase, or M8B merged once?
- **X-4. The CSRF cookie name (M8 §4).** The contract never names `fs_csrf`. The owner said this
  must be fixed "before the console consumes the real endpoints", so it has to land before P1's
  console re-run. Who makes the contract change?
- **X-5. M8's numbered list reaches `main`.** Items 1 to 11 exist only on `m8/frontend-rebase`.
  The answers below cite them, so they should be on `main` with this scope.
- **X-6. Each contract change goes through review.** The contract is frozen, so every answer
  below that changes it (items 5 and 7 to 11, and the four additions) is a reviewed proposal, as
  ADR 0081 was, before the code that relies on it.

**P1: alert and account reads**
- **P1-a (item 5).** Does `listAlerts` list an alert whose decision is PENDING_COMMIT, or one just
  escalated? The contract should say which statuses each queue holds. It is decided here because
  P1 builds the queries, even though PENDING_COMMIT is created in P2.
- **P1-b (item 9).** The anomaly queue's order: does `priority` mean the anomaly score,
  descending, in that queue? And `anomaly_score` on `AlertSummary`: a contract addition.
- **P1-c (item 10).** Can a search span the queues (`queue=all`, or an omitted queue meaning
  all)?
- **P1-d (item 8).** The keys of the account context's lists:
  - network node tokens;
  - (source, target) pairs;
  - comparison attributes;
  - channels;
  - transaction ids.
- **P1-e (item 6).** Which time zone is `hourly_counts` in: UTC, the institution's, or the
  account's?
- **P1-f (item 7).** The list of `comparison.attribute` names, an enum or a catalogue. M8B supplies
  the list; the plain-language names belong to PB-132's owner.
- **P1-g.** Where the network and behaviour profile are computed: from PostgreSQL at request time,
  from the feature store, or precomputed? And what depth and row limits keep `getAccountNetwork`
  bounded (FR-04-08 asks for second-degree connections)?
- **P1-h.** What the inspector asserts (NFR-SEC-03). Which fields count as masked tokens, and how
  the test finds "every analyst endpoint" so that it fails when one is added without coverage.

**P2: decisions**
- **P2-a. The commit after the undo window (D-44).** What moves PENDING_COMMIT to COMMITTED after
  5 seconds, given several API instances and restarts?
  - The options are a database-driven sweep, a delayed message, or a lease.
  - This is a concurrency rule, so it is written as a contract and reviewed before the code
    (ADR 0073's lesson).
- **P2-b. Side effects, in order and exactly once.** `analystDecidesHold`, `countConfirmedFraud`,
  the label to `fs.labels` (PB-70's consumer applies it), and the ANALYST_DECISION audit event.
  Each fires on commit, never on PENDING_COMMIT.
- **P2-c. Stale and replayed decisions (D-29).** The console replays offline decisions later.
  The server needs a rule for a decision on an alert already decided, undone, escalated or
  overridden: 409 with the current state, and the idempotency key's scope.
- **P2-d. Escalation and override.** Escalation's targets and who is assigned; override applies
  only to COMMITTED decisions and is limited to particular roles (SENIOR_OVERRIDE audit); and
  what an override does to an alert's labels and the breaker count it already fed.
- **P2-e. Figures.**
  - Whose "today" `getDashboardStats` and `getMyPerformance` use.
  - Whether anomaly reviews count (item 9).
  - Whether `getMyPerformance` gains a daily series (item 11).
- **P2-f. The real-time channel (item 2), a contract proposal:**
  - WebSocket or server-sent events;
  - authentication with the 15-minute JWT, and what happens on expiry;
  - fan-out across instances through the `websocket-fanout` consumer named in `topics.yaml`;
  - what is pushed (new alert, decided, escalated, figures).

  `startRetraining` also streams over WebSocket (P8), so the design must cover both.
- **P2-g.** Reassignment and team performance (items 3 and 4): does M8B own them? M8's handover
  says yes; `M8B_updates.md` files them as unowned. If so, the contract shape and who may
  reassign.

**P3: operations**
- **P3-a.** `/system/status` (ADR 0081, Proposed, on `m8/system-status-proposal`): does M8B take
  the proposal on? If so, its three open questions:
  1. **How `KAFKA_SPOOLING` is detected.** `DurableSpool` exposes only `depthBytes` and
     `lagBytes`, so either a threshold or a Kafka-failure signal has to be defined.
  2. **Whether `REALTIME_PAUSED` belongs on the server.** Nothing tracks it, and it may be the
     console's own state (a dropped socket). It depends on P2-f.
  3. **How instances agree.** `ML_UNAVAILABLE` (the scorer's breaker) and `DEGRADED_MODE` (the
     last Redis call failed) are per JVM, so banners can flap behind a load balancer. The options
     are shared state in Redis, aggregation, or reporting per instance with a stated rule.
- **P3-b.** What `getSystemHealth` aggregates, how far it may block on a slow component, and
  whether it is the source `/system/status` reads.
- **P3-c.** Which failed webhook deliveries are listed, and for how long, given that deliveries
  are re-checked against the deny-list (ADR 0066).

**P4: dual control, thresholds, breakers**
- **P4-a.** A persistent `DualControlWorkflow`: its state machine in V6's tables, expiry, and
  whether more than one proposal can be open on the same setting. The proposer can never approve
  their own proposal.
- **P4-b.** How an approved change reaches every running decision instance and the scorer. This
  is a cache rule, so it is a contract first and reviewed. It also needs D-18's tolerances for
  "exactly 30 s" and "exactly at 5 %".
- **P4-c.** `previewThresholdImpact` reads "the last 24 h of scores". It needs a cost bound on
  PostgreSQL, and a rule for what it shows when there is too little volume.
- **P4-d.** Do these close the API halves carried from FR-02-06 (M5) and FR-03-07 (M6)?

**P5: rules**
- **P5-a.** Do rule changes go through dual control? The contract says only "effective within 60
  seconds".
- **P5-b.** How a rule reaches every instance within 60 s: the cache refresh, as a contract.
- **P5-c.** `previewRule`: over which window of data, and at what cost bound.
- **P5-d.** Whether rule versions are immutable, and what a soft delete keeps for audit
  (RULE_CHANGE).

**P6: risk analytics and campaigns**
- **P6-a.** Campaign detection does not exist. Does M8B build the clustering (M–L), or serve the
  table and leave FR-05-04 open? Endpoints over an empty table would not meet FR-05-04.
- **P6-b.** `getModelPerformance` computes "live" AUC from analyst labels. It needs a rule for
  label delay, a minimum label count before a figure is shown, and the window.
- **P6-c.** The heatmap's geographic granularity. D-46 rules out public map tiles, so the API
  returns aggregates only and the base map is the console's concern.

**P7: SAR**
- **P7-a.** The draft generator's content and format. D-22 says the regulatory mapping is
  unverified, so this needs your decision, and possibly someone outside the project.
- **P7-b.** Who may sign off, whether a signed-off report is immutable, and the REGULATORY_REPORT
  audit fields.

**P8: models, datasets, retraining**
- **P8-a.** How Java reads and writes the MLflow registry: its REST API, or a Python service.
- **P8-b.** How promotion and rollback reach the running M5 scorer (hot reload), and how shadow
  mode mirrors traffic. ML-GATE-13 requires a minimum 24-hour shadow period.
- **P8-c.** Dataset uploads.
  - Their size limit and storage.
  - The raw-PII scan the contract promises, with G.5 in mind.
  - Where the files live.
- **P8-d.** Where retraining runs. It cannot run on a 7.6 GB machine at production volume. Is the
  orchestrator a production component or a CI dispatch, and what does `startRetraining` start in
  the demo?

## 3. What M8's rows need from each phase

**The mock rule (owner, 2026-10-18):** no row leaves IN_PROGRESS until the operation behind it is
on `main` and M8 has exercised the screen against it, with evidence at job level in CI. M8B's own
evidence is tests against the real application, never a console test against a mock.

| Phase | IN_PROGRESS rows it releases (with any other condition) | NOT_STARTED rows it makes buildable |
|---|---|---|
| P1 | FR-04-03, FR-04-05 (also D-45/PB-132), FR-04-06, FR-04-07, FR-04-08, FR-04-09 (P1-c), UX-DASH-02, UX-DASH-04, UX-DASH-05, UX-DASH-07, MOB-COMP-02, MOB-COMP-08, UX-ROLE-AN-01, UX-ROLE-AN-02 | UX-DASH-03, UX-DASH-08, UX-DASH-09 |
| P2 | FR-04-04, FR-04-10, FR-04-11 (item 11), UX-DASH-06, UX-ROLE-AN-03, UX-ROLE-AN-04, D-29, D-44. **BLOCKED until the real-time channel exists:** FR-04-02, UX-DASH-01 | FR-05-01, UX-ROLE-SN-01, UX-ROLE-SN-03, TEST-06; UX-ROLE-SN-02 and SN-04 if P2-g is yes |
| P3 | none by name; E.9's banners have no row (M8 §4) | UX-ROLE-AD-09 |
| P4 | none | FR-05-07, UX-ROLE-RO-05 |
| P5 | none | FR-05-05, UX-ROLE-RO-04 |
| P6 | none | FR-05-02, FR-05-03, FR-05-04 (P6-a), UX-ROLE-RO-01, UX-ROLE-RO-02, UX-ROLE-RO-03, UX-ROLE-SN-05, D-46 |
| P7 | none | FR-05-06, UX-ROLE-RO-06, D-22 |
| P8 | none | FR-06-03, FR-06-04, UX-ROLE-AD-05, UX-ROLE-AD-06 |

**Not waiting on M8B:**
- D-27 (PB-140's open part), D-33, D-43 and MOB-PERF-01 to 06 (PB-133).
- FR-07-02 to 04, FR-06-07, UX-ROLE-AD-01 to 04, 07 and 08: M7's operations already exist, so the
  screens can be built against them.

**M8B's own carried rows:**
- **NFR-SEC-03:** the inspector starts in P1 and is extended each phase. It closes after P8.
- **FR-06-06:** one audit type per phase, each asserted from a real action:
  - ANALYST_DECISION and SENIOR_OVERRIDE in P2;
  - THRESHOLD_CHANGE in P4;
  - RULE_CHANGE in P5;
  - REGULATORY_REPORT in P7;
  - MODEL_LIFECYCLE in P8.

  AUTO_BLOCK and CUSTOMER_VERIFICATION are asserted through the assembled application in P2.
  The every-write-endpoint test grows with each phase.

## 4. Which M10 items depend on which phase

The owner's rule puts all of M8B on `main` before M10 starts, so this table orders the work; it
does not let M10 start early.

| M10 item | Needs |
|---|---|
| TEST-05 (end to end "from HTTP to analyst dashboard") | P1 (`listAlerts`, `getAlert`), P2 (the decision path), and the real-time channel for "dashboard" |
| NFR-PERF-05 (Kafka event to DOM in under 1 s) | the real-time channel (P2-f) |
| NFR-PERF-06 (SHAP render under 500 ms) | P1 (`getAlertExplanation`) |
| NFR-PERF-07 (500 alerts in under 1 s) | P1 (`listAlerts`), plus a way to seed 500 alerts |
| NFR-PERF-08 (Google sign-in to dashboard in under 3 s) | P1 and P2 (`getDashboardStats`): the dashboard must render real data |
| MOB-DEV-03 (alert feed, detail, swipe action, WebSocket reconnect) | P1, P2, the real-time channel |
| MOB-DEV-04 (split panel, filters, notes, admin analytics) | P1, P2, P6 |
| MOB-DEV-05 (full admin panel, model management, all charts) | P3, P6, P8: in effect every phase |
| MOB-DEV-06 (full dashboard, SHAP, account history) | P1, P2 |
| ML-GATE-13 (shadow AUC delta, 24-hour shadow period) | P8 (`setShadowModel`, `promoteModel`) |
| MOB-DEV-01, 02 and 07 | none that I can see: their journeys are registration, the customer's alert and the SMS path. To confirm when M10 starts |
| FR-01-01, FR-01-06, FR-02-07, FR-02-09, FR-03-01, FR-03-03, NFR-PERF-01 to 04, 09 and 10, NFR-REL-03 to 05, ML-GATE-12, TEST-09, TEST-10, D-13, D-16, D-19, PB-72 | none |

## 5. How the work runs

- **Memory.** Java builds, Testcontainers suites and anything that loads models run on GitHub
  Actions. Locally, only unit tests run, through `tools/bin/mem-gate`, one at a time.
- **Pushing.** Pushes go through `tools/bin/safe-push` only. It is on `m8/frontend-rebase` and
  not yet on `main`, so until M8 merges, M8B runs it from that worktree. That is how PB-66's
  pushes ran.
- **Review.** Each phase gets an independent review. Each concurrency or cache contract (P2-a,
  P4-b, P5-b, P2-f) is reviewed before its code.

## 6. Decisions needed from the owner to approve this scope

1. **The phase order:** P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8.
2. **X-3:** one merge per phase.
3. **The four contract additions** (the real-time channel, reassignment, team performance,
   `/system/status`): are they M8B's?
4. **P6-a, P7-a and P8-d:** build campaign detection, SAR drafting and a retraining orchestrator,
   or serve the endpoints and leave those rows open?
5. **X-4:** who names the CSRF cookie in the contract, and when.

## 7. The owner's answers (2026-10-20)

1. **Phase order:** P1 to P8, as proposed.
2. **Delivery:** one merge to `main` per phase. "Eight smaller reviews beat one enormous one — M6
   is the evidence."
3. **The four contract additions are M8B's.** Each changes a frozen contract, so each gets its own
   reviewed proposal before implementation, as `/system/status` did. Each is built in the phase
   where it belongs:
   - `/system/status` in P3;
   - the real-time channel in P2;
   - reassignment and team performance in P2, which serves their screens.
4. **Domain logic is not built.** Serve the endpoints over the existing tables and leave these rows
   open, each with its reason:
   - **Campaign detection (P6, FR-05-04):** what counts as a campaign is a domain question.
   - **SAR drafting (P7, FR-05-06):** what a regulator requires is a domain question, and D-22's
     regulatory mapping is unverified and needs a compliance professional. "An endpoint that
     returns a well-formed SAR draft nobody qualified has reviewed is a liability, not a feature."
   - **The retraining orchestrator (P8, FR-06-04):** when a model should retrain is a domain
     question.
5. **CSRF:** M8B makes the contract change, in P1, since that is when the console first reads real
   endpoints. `fs_csrf` is pinned in the contract, with a test both sides must satisfy.

**Before P1's code**, the concurrency and cache contracts (P2-a, P4-b, P5-b) are written and
reviewed on their own. That approach is what broke M6's cycle, and P2's undo is the same kind of
cross-instance timing problem. P1 is reported to the owner before it merges.
