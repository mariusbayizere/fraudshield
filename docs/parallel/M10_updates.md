# M10 updates (created by the M5 agent, branch `m5/scoring`)

This file exists so that the obligation M10 must discharge is written where M10 will read it,
rather than only inside documents addressed to M6 and M9 (M5 re-review, finding N7). It carries
nothing else. An M10 agent should keep its own content in this file and treat the section below as
inherited work.

## From M5 — PB-72: re-measure the training–serving skew, and require it to be zero

**Owner: M10. Source: ADR 0034 (Accepted 2026-09-22, option 3), owner's condition 2.**

M5's scoring service reads four trained features from state that no deployed component writes, so
in production they are served as constants. M5 measured what that costs, on M4's gate model over
the whole test period (101,909 rows, 985 frauds); the figures were reproduced independently by the
M5 re-review (`docs/reviews/M5/principal-re-review.md`):

| Serving state | Test AUC | Risk-tier changes | Frauds reaching 0.60 |
|---|---|---|---|
| as trained | 0.9700 | — | 725 |
| outcomes missing | 0.9619 | 235 | 585 |
| SIM swaps missing | 0.9699 | 25 | 712 |
| **both, as M5 ships** | **0.9611** | **254** | **563** |

AUC moves 0.0089 — just outside the ±0.0075 half-width of the gate's own 95% interval — while
**162 of 725 frauds that the evaluated model flags at 0.60 are not flagged by the served one: a
22% fall in detections at the operating point.** The measurement is conservative: the gate cache
has no `kyc_tier`/`account_age_days` columns, so `synthetic_identity_score` could not be degraded
in it, and production skew is therefore larger.

### What M10 must do

1. **Re-measure the skew end to end**, on the deployed scorer rather than in a notebook: score a
   verification set through the running service and compare each feature with the batch value the
   training path computes for the same transaction.
2. **Require the skew to be zero.** Not "close on AUC": the served features must equal the trained
   ones for every row. AUC is the quantity that hid this defect — it moved by less than one
   interval half-width while a fifth of the detections disappeared — so it is not the acceptance
   criterion here.
3. **FR-02-09 may not read DONE until this passes**, along with PB-70 and PB-71 (both owner M6):
   the `fs.labels` consumer that calls `apply_label`, and a contract plus producer for account
   reference state. Their acceptance criteria are in `docs/parallel/M6_updates.md` under "From M5".
4. **Check the alerts are silent for the right reason**: once PB-70 is deployed,
   `fs_feature_store_missing_producer_reads_total{state="outcomes"}` must stop increasing, and
   after PB-71 the other three states must too (`docs/parallel/M9_updates.md`, "From M5"). A
   silent counter with no producer deployed would mean the counter broke, so verify against a
   deliberately withheld verdict.

The backlog numbers above are proposals for the owner to confirm at integration: M5 does not edit
`docs/backlog/`, and PB-68 is already taken by the frontier write-up item.

---

# M10 preparation (branch `m10/verification`)

Written by the M10 agent. The section above is inherited from M5 and is not edited here.

**Status: harnesses written, nothing executed.** No measurement, chaos experiment or browser
journey has been run, and no number of any kind is recorded on this branch. Every file states
"not yet executed — requires the integrated system and dedicated hardware (ADR 0010)".

## What this branch adds

| Path | Contents |
|---|---|
| `tests/performance/` | Distributed Locust scenarios for the ingest API: sustained mix, duplicate storm, idempotency conflict, batch and job, hold follow-up, and a validation mix driven by the frozen request-validation vectors. Plus a plateau shape, a per-response contract check, and a refusal to run against a target that does not report synthetic data |
| `tests/chaos/` | One manifest per Part C.4 failure case, plus ADR 0090's orphan-spool case that the ADR assigns to M10. Each case carries its requirement, its acceptance condition and what must be reconciled afterwards |
| `tests/e2e/` | The five SRS journeys (TEST-12) with their own Playwright configuration, against a deployed instance rather than mocks, on three desktop browsers plus the device matrix |
| `docs/benchmarks/m10_plan.md` | Every carried measurement with its source, what hardware each needs, the order to run them in, and the evidence rules |
| `docs/benchmarks/m10_machine_spec.md` | The machine to rent: cores, memory, disk, network and the properties ADR 0010 requires recorded |

## Prerequisites that belong to other milestones

1. **Data-store manifests** (PostgreSQL/TimescaleDB, Redis, Kafka) do not exist. M9 fixes their
   selector contract as `fraudshield.io/datastore: <name>`, and the chaos manifests select on it;
   until those manifests ship, chaos cases 02, 03 and 04 select nothing — which looks exactly like
   a system that survived. The campaign's first chaos step is a deliberate no-op experiment to
   prove the controller can act at all. **[M9]**
2. **Chaos Mesh cannot run inside the application namespace** as configured: it enforces restricted
   pod security and default-deny networking in both directions. The controller needs its own
   namespace and an explicit network policy. **[M9 / M10 at integration]**
3. **The console's screens** are placeholders on `m8/frontend`, so all five journeys fail today at
   the first assertion after the feed. `tests/e2e/README.md` lists the selectors that exist
   (`fs-pulse`, the countdown's `role="timer"` and `data-urgent`, the gauge's `role="meter"`) and
   the ones the specs assume M8 will provide, including a stable handle to follow one alert from
   an analyst's feed into a risk officer's queue. **[M8]**
4. **The Google sign-in button does not exist** although `POST /api/v1/auth/google` does. Journey 1
   signs in with email and password and covers every step after that; the OAuth leg, and the
   requirement row timed from it, are recorded as not exercised rather than quietly dropped. **[M8
   / owner]**

## Things the owner must decide

1. **`PB-73` is proposed twice.** M5 proposes it for a security-tooling flake owned by M9; M6
   proposes it for the carried end-to-end latency measurement owned by M10, alongside `PB-74` for
   the batch row. Both branches numbered "next free" without seeing each other. One must be
   renumbered before the verification report cites it. This branch does not edit `docs/backlog/`.
2. **No numeric ingest rate limit is specified anywhere.** The contract declares the refusal and
   its retry header, and the only quantified limits in the specification are for authentication.
   The load campaign therefore cannot distinguish a correct refusal from a defect, and the
   error-rate row has no rule to test against.
3. **Whether the campaign waits for M6, M7, M8 and M9 to merge**, and who provisions the machine.
   Both gate the entire milestone; nothing in the plan can start without them.

## Notes for the M4/M5 record

`ML-GATE-12` is described in the brief as M4's; in `docs/traceability/requirements.yaml` the row
sits in the model deployment gate with milestone M5 and defect D-16, and ADR 0035 carries it to
M10. The plan cites it as that row, so the reference is unambiguous whichever milestone the owner
files it under.

## Owner decisions, 2026-09-24

### 1. The `PB-73` collision is resolved: M6's allocation stands

**M6 keeps `PB-73`** — the carried end-to-end decision latency measurement — because ADR 0059
already cites it and M6's settled traceability rows already point at it. `PB-74` (the batch row) is
M6's and unaffected.

**M5's item is renumbered to `PB-75`:** "tools/bin/gitleaks-selftest fails intermittently on
generated secrets", owner M9, proposed in `docs/parallel/M5_updates.md` §21 with its own detail and
acceptance criterion. `PB-75` is the next identifier free on every branch read on 2026-09-24
(`docs/backlog/product.md` ends at `PB-68`; `PB-69` to `PB-72` are proposals in the M5, M6 and M10
updates files; `PB-73` and `PB-74` are M6's). Whoever integrates the backlog should take M5's §21
entry across under the new number; M10 does not edit `docs/backlog/` or `M5_updates.md`.

### 2. Identifiers are claimed in the updates files, not guessed from the backlog

The collision was not a mistake by either milestone: both took "the next free identifier" from a
backlog file that neither could see the other editing, because parallel branches do not share
`docs/backlog/`. The same shape will recur for every kind of identifier M11 through M12 allocate.

**The convention, from the owner:** claim identifiers the way migration ranges are already claimed
— write the claim into `docs/parallel/<milestone>_updates.md` **before** using it, and read the
other milestones' updates files before choosing. `docs/parallel/M6_updates.md` already does this
for ADRs (`0060`–`0069`) and Flyway versions (`V60`–`V69`), and that section is the model. A claim
costs one line and is visible on every branch that fetches; "next free" is only ever true on the
branch that asks.

Claimed by M10 on 2026-09-24: **ADR `0100`–`0109`** (`0100` and `0101` used), and backlog
identifier **`PB-75`** assigned to M5's renumbered item above, so that the number is not taken
twice while the backlog file is still unedited.

### 3. The ingest rate limit: ADR 0100

A specification gap rather than an M10 problem. `docs/adr/0100-an-assumed-ingest-rate-limit.md`
proposes a budget derived from the specified system throughput and M6's per-key token bucket,
marked **ASSUMED** with its reasoning, and records the defect it uncovered: the budget is counted
in requests, and the batch endpoint accepts up to a thousand transactions per request, so the limit
protects the request path rather than the capacity the load costs. Flagged to M6 as the implementer
and to M11 for the limitations. Until the owner accepts it, the campaign records refusals as
observations, not as pass or fail, and excludes them from the error-rate row.

### 4. The Google sign-in leg is recorded as untested

`docs/benchmarks/m10_plan.md` §5 now lists the paths the campaign will not exercise, and the OAuth
leg heads it: the endpoint exists, the button does not, journey 1 stays on email and password, and
the verification report must say the leg was **not exercised** rather than implying it passed.
Raised with M8 in `docs/parallel/M8_updates.md`, together with the selectors the journeys need.

### 5. Prerequisites filed with their acceptance criteria

`docs/adr/0101-prerequisites-for-the-chaos-and-benchmark-campaign.md` records how M10 conducts
itself: prove each harness can fail before any measured row, record the machine before the first
measurement, install the chaos controller outside the application namespace, and record a case
whose target does not exist as not run rather than approximating it.

The items belonging to other milestones are written into their files, not decided for them:

| Filed in | Item | Acceptance criterion M10 needs |
|---|---|---|
| `M9_updates.md` | Data-store manifests carrying `fraudshield.io/datastore`, plus the operator's primary-role label | Chaos cases 02, 03 and 04 select real pods instead of reporting success against nothing |
| `M9_updates.md` | Chaos Mesh in its own namespace with a NetworkPolicy allowing it to act | The controller can disturb `fraudshield` pods under restricted pod security and default-deny networking |
| `M8_updates.md` | A Google sign-in control, the journey screens, and a stable per-alert handle | Journey 3 can prove the escalation that arrived is the one that was sent |
| `M6_updates.md` | ADR 0100's rate limit, counted in transactions | A `429` during the campaign can be classified |
| `M11_updates.md` | The rate limit as an assumed parameter | The paper states it as a modelling choice or not at all |

### 6. Merge notes

* `docs/parallel/M8_updates.md` is created here because M8's own copy lives on `m8/frontend` and
  this branch needs a place on `main` to leave findings. At merge, M8's file wins and this
  section is appended to it.
* `docs/parallel/M11_updates.md` and `docs/parallel/M6_updates.md` are appended to on this branch
  and are also being appended to on `m11/paper` and `m6/decision`; expect a conflict at the end of
  each file, resolved by keeping both sections.
* `main`'s copy of `M6_updates.md` is an older snapshot than `m6/decision`'s; the section added
  here is written to make sense against either.

**M10 does not run** until M6, M7, M8 and M9 are on `main` and the owner has decided the hardware.
