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

**Status: no measurement exists.** No requirement has been measured, and no chaos experiment
or browser journey has been run. The only thing executed is step 2's ingest leg on a shared CI
runner on 2026-10-23, which proves the load harness can run and can fail; its numbers measure
nothing (section "Step 2, ingest leg", below). Step 2 is not finished: its chaos and journey legs
cannot run yet (section "Step 2 re-run with the FX seed", below).

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

## Owner decisions, 2026-09-24 (second round)

1. **ADR 0100 accepted**, with the transaction-counting change, which the owner named as the
   important part. The change is recorded for M6 as **a defect to fix before M6 merges** — a
   security control that does not currently hold — and not as a proposal for later. The budget
   figures stay ASSUMED and may be revised without reopening the ADR; the unit of account is the
   merge condition. `docs/parallel/M6_updates.md` is written in those terms.
2. **Recorded in the lab notebook** (`docs/research/lab_notebook.md`, 2026-09-24, under a new M10
   section): *a control whose unit of account differs from the unit of load is not a control.* It
   belongs with the guard failures of M2 and M4 — the parameter digest guarding inputs while the
   claim lived in the output, the D-08 ceiling true of columns and false of features — and it adds
   a third instance to the rule that a guard must range over the object the sentence is about. The
   entry also records how it was found: not by review, which it survived, but because a number had
   to be justified in writing rather than defaulted.
3. **ADR 0101 accepted as written.**
4. **Hardware:** the owner decides before the campaign runs. `docs/benchmarks/m10_machine_spec.md`
   stands as the requirement in the meantime and now says so in its status line.

**M10 stops here.** It waits for M6, M7, M8 and M9 on `main`, and for the hardware decision.

## Step 2, ingest leg (2026-10-23)

**Nothing from this is a measurement.** It ran on a shared GitHub runner at 2 requests a second
against the Compose stack (ADR 0010). It shows whether the load harness runs and fails when it
must, and no figure below is evidence for any requirement row.

The owner asked for step 2 (`docs/benchmarks/m10_plan.md` §3) on `main` once the API image, the
API in Compose and `GET /api/v1/environment` had merged. The owner's words: *"Prove the harness can fail, not only that it passes: it must stop on
a wrong key, and stop when the environment endpoint reports synthetic_data false or is
unreachable. A run that only succeeds proves nothing."*

What ran: `.github/workflows/m10-harness.yml` running `tests/performance/step2/prove-harness.sh`,
run 39909394023, job 120268607480, on `4ac1092` (main `e17cc18` plus this branch). The workflow and
harness changes had an independent review before they ran (`docs/reviews/M10/step2-reviews.md`):
12 findings, all answered, the distributed-mode one by documenting it (PB-197); the recheck found
nothing blocking.

Counts are from the final console tables in each case's log in the run's artifact
(`m10-step2-ingest`). The per-case CSVs are rewritten once a second and miss the last of a run, so
they read slightly lower (117 and 94, 40 and 33).

| Case | Exit (expected) | What reached the API | Verdict |
|---|---|---|---|
| pass: the real API, 2 users at 1 request a second for 60 s | 1 (0) | 122 requests: 118 new ingests, 94 of them answered 503, and 4 replays; Kafka +24, the 24 accepted | **FAIL** (PB-195) |
| wrong key | 3 (3) | nothing; Kafka +0 | pass |
| `synthetic_data: false` (fault proxy) | 3 (3) | proxy forwarded 0; Kafka +0 | pass |
| environment answers without the flag | 3 (3) | forwarded 0 | pass |
| environment answers 503 | 3 (3) | forwarded 0 | pass |
| nothing listening at the host | 3 (3) | nothing | pass |
| key revoked mid-run (fault proxy) | 4 (4) | stopped 4 s into a 60 s run; forwarded 10, the preflight's key check included | pass |
| proxy with no fault | 1 (0) | 42 ingests, 35 answered 503 | **FAIL** (PB-195) |
| mutant: the shape defect restored | 5 (5) | nothing | pass |
| mutant: the pacing defect restored | 6 (6) | the task's exception caught | pass |

The fault proxy (`step2/fault_proxy.py`) forwards every request to the real API except the fault it
injects, so a harness that ignored the fault would have sent real load and Kafka would have shown it.

### 1. The harness ended every run with no request and exit 0

Before step 2 the harness had never run. Its first run found three defects:

1. **The plateau shape stopped every run without `--fs-target-users` at once**, with no request
   sent and exit status 0. Observed with Locust 2.43.4 against a local fake upstream. **A load
   test could have reported success having measured nothing.** The docstring said the shape was
   "disabled unless set" and honoured `-u`/`-r`; Locust uses any shape class the file defines, and
   a `tick` that returns None ends the run.
2. **`response.js` does not exist** on Locust's `FastHttpUser` responses; every task raised after
   its first request.
3. **The pacing function was assigned to the instance**, so every user raised on its first wait.

Locust records a task's exception and exits 1, so with `--fs-target-users` the second and third
would have failed the run after at most one request per user (inferred from Locust's source, not
observed). All three are fixed. Two new exit statuses close the class: 5 when a run that was
allowed to start sent nothing, 6 when a task raised: the three defects, and any run that sends
nothing or whose task raises, now fail.

### 2. Every refusal now stops the run, and the reintroduced defects are caught

The preflight refuses with exit 3 before the first request when `GET /api/v1/environment` cannot
be reached, does not answer 200, is not JSON, or does not report `synthetic_data` true; when the key
check (an ingest `POST` with a malformed body, which the API answers 400 only after the key and its
`ingest:write` scope pass) answers anything else; and when the preflight itself raises. A 401 or 403
during the run stops it with exit 4. All six refusal cases exited as specified: the five preflight
cases sent no ingest, and the revocation case stopped at the first 401, after the nine real ingests
the proxy allowed. The two mutants, copies of the harness with the shape and the pacing defect put
back, exited 5 and 6: those two defects are caught if they return.

### 3. The FX gap, found only because something finally sent the specified currency mix

**PB-195** (owner M9 and the demo seed). The passing case failed, and the cause is inferred from
the code and the ratio, not read from the run: the 503 says only "retry the same request"
(**PB-196**, owner M6: the reason is kept from clients by design and not logged). The code: ingest
answers 503 for a currency with no FX rate (ADR 0063), RWF is converted at one without reading the
table (`JdbcFxRates.java:56-57`), and nothing outside the tests inserts into `fx_rates`. The ratio:
the harness draws its currency uniformly from five, so four in five refusals were predicted;
observed 79.7% (94 of 118) and 83.3% (35 of 42). The API's own start test sends one RWF
transaction, and passed.

**Then observed, not inferred, for the currency pattern.** The harness re-ran on `c783540`, with
the request's currency in each failure message (run 39920804992, job 120305281484, the logs' final
tables and the failure CSVs in its artifact). Every refusal was in KES, TZS, UGX or CDF, and no RWF
request was refused: 90 of 113 new ingests in the passing case (79.6%; Kafka +23, the 23 accepted)
and 30 of 39 through the proxy (76.9%). That the reason is the missing rate, rather than something
else confined to those currencies, is still read from the code, since the 503 does not say.

The fix was on `fix/demo-fx-rates` when this was written: the demo seed inserts the country packs'
fixed simulation rates. It merged as `4ce8d4b`, and the re-run with it is recorded below. The harness was not restricted
to RWF; the owner's words: *"Restricting the harness to RWF would make step 2 green while drifting
from the specified currency mix"* (2026-10-23).

The harness's failure messages now carry the problem's type, title and detail, and the request's
currency, so failures group by currency in the run's own failure table. With today's API the detail
is generic; the currency split (observed in run 39920804992, above) shows which currencies are
refused, and why they are refused is still read from the code.

### 4. Distributed mode: exit codes 3 and 4 never reach the master

**PB-197** (owner M10). A refusal sets the status of the process that saw it. In a master/worker
run that is a worker: the master never sees 3 or 4, ends with 5 or 1, and keeps the other workers
loading after a key is revoked. Found by the review and confirmed by reading Locust's source, not
observed. Step 2 ran in local mode only. **This must be fixed before any distributed run.**

### Skipping the Java formatter locally: the hook id is `spotless`

The pre-commit hook displayed as "google-java-format (backend)" has the id `spotless`, and `SKIP`
takes ids, so `SKIP=google-java-format` skips nothing: on 2026-10-23 it let spotless run Maven at
about 1.1 GB free, against mem-gate's 1536 MB floor. To leave the format check to CI's pre-commit
job when memory is short, use `SKIP=spotless`, and read that job at job level.

### Identifiers claimed by this branch

PB-195 to PB-199 (2026-10-23; PB-191 to PB-194 are claimed on `m9/images`). Used: PB-195 to PB-197.

### What step 2 did not cover

The chaos leg and the journey leg have not run; why, in "What step 2 still cannot run", below.
Every measured row of the plan stays unmeasured; see the plan's §2.

## Step 2 re-run with the FX seed (2026-10-23)

**This is not a measurement: it ran on a shared runner at 2 requests a second.** It shows that the
ingest harness runs end to end against the API with the specified currency mix, and no figure
below is evidence for any requirement row.

The seed merged to `main` as `4ce8d4b` (PB-195). The passing case's refusals:

| Run | Commit | Seed | New ingests refused (passing case) |
|---|---|---|---|
| 39920804992 | `c783540` | no | **90 of 113**, every one in KES, TZS, UGX or CDF |
| 39932312852 | `8547c7c` | yes | **0 of 117** |
| 39942490462 | `7e0c418` | yes | **0 of 118**, accepted in all five currencies (below) |

Run 39932312852 accepted everything, but which currencies were accepted could then only be inferred
from the absence of failures. The harness now counts accepted new ingests by currency and prints
the split in its provenance line (`accepted_new_by_currency`, `7e0c418`; reviewed twice,
`docs/reviews/M10/step2-reviews.md`), and the passing case requires at least 60 accepted, a split
summing to `accepted_new`, and all five pack currencies in it. Run 39942490462, job 120377056768,
on `7e0c418` (main `4ce8d4b` plus this branch); the job log shows "Seeded 4 demo FX rate(s) from
the country packs". Counts are from each case's provenance line and the logs' final tables in the
artifact `m10-step2-ingest`:

| Case | Exit (expected) | What happened | Verdict |
|---|---|---|---|
| pass | 0 (0) | 121 requests, 0 failures: 118 new ingests and 3 replays. Accepted by currency: CDF 28, KES 24, RWF 18, TZS 21, UGX 27. Kafka +119 | pass |
| wrong key | 3 (3) | nothing sent; Kafka +0 | pass |
| `synthetic_data: false` | 3 (3) | proxy forwarded 0; Kafka +0 | pass |
| environment without the flag | 3 (3) | forwarded 0 | pass |
| environment answers 503 | 3 (3) | forwarded 0 | pass |
| nothing listening | 3 (3) | nothing sent; Kafka +0 | pass |
| key revoked mid-run | 4 (4) | stopped 4 s into a 60 s run; forwarded 10 | pass |
| proxy with no fault | 0 (0) | 42 new ingests, 0 failures. CDF 9, KES 8, RWF 10, TZS 8, UGX 7 | pass |
| mutant: shape defect | 5 (5) | nothing sent | pass |
| mutant: pacing defect | 6 (6) | 2 requests sent, then the task raised ("TASK RAISED" in its log); Kafka +2 | pass |

Kafka grew by one more than the 118 accepted in the passing case. The case allows up to one per
user above the count, for requests in flight when the users are stopped, which reach the API but
not the run's statistics; that this is what the extra record is, is an inference, not read from
the run.

### What step 2 still cannot run

**A green `m10-harness` job is not a finished step 2.** The plan's step 2 (§3) has three legs, and
this job runs only the first:

* **Ingest: run, and refused when it must.** Done, as above, on a shared runner in local mode
  only; distributed mode is not proved (PB-197).
* **Chaos: one experiment that injects nothing, confirmed to have selected the intended pods.**
  Cannot run: it needs a Kubernetes cluster with the manifests applied, and Chaos Mesh installed
  in its own namespace with a network policy that lets it act. Neither exists: there is no
  cluster, so the manifests on `main` (`infrastructure/k8s`) have never been applied. Cases 02 to
  04 also need the data-store manifests, which are not written (plan §1).
* **Journeys: one journey on one browser.** Cannot run: it needs M8's console screens and M8B's
  analyst API on `main`. Neither milestone has merged: `m8/frontend` is 37 commits ahead of
  `main`, and of M8B only P1's analyst reads are on `main`, with `m8b/analyst-api` 53 commits
  ahead (counted 2026-10-23).

Until those two legs run, the chaos and journey harnesses have not been proved able to fail, and
the plan allows no measured row before each harness has been (§4).
