# M10 verification campaign — plan

**Status: nothing in this plan has been executed.** No measurement, no chaos experiment and no
journey has been run. This document states what M10 must measure, what each measurement needs, and
the order to run them in. **It deliberately quotes no figure of any kind** — no target, no
threshold and no earlier result. Every threshold lives in `docs/traceability/requirements.yaml`
and every earlier measurement in the file named beside it, and a plan that restated them would
become a second, drifting copy of both.

M10's gate is that every matrix row has a final status with evidence, and the campaign's output is
`docs/benchmarks/verification_report.md`, which does not exist yet.

## 1. What must exist before any of it can run

| Prerequisite | State today | Owner |
|---|---|---|
| The integrated system: ingest, decision, scorer, staff identity and console deployed together | M6, M7, M8 and M9 are unmerged; `main` carries M0–M5, and `backend/` has no ingest or decision module | the merge sequence, owner-directed |
| Kubernetes manifests applied to a real cluster | Written on `m9/infra`, never applied | M9 |
| Data-store manifests (PostgreSQL/TimescaleDB, Redis, Kafka, the vault, MLflow, object store) | Not written; M9 fixes only their selector contract, `fraudshield.io/datastore` | M9 |
| A dedicated benchmark machine, recorded as its own section in `docs/benchmarks/hardware.md` **before** measuring | Not provisioned; the only machine recorded is the development laptop | owner (see `m10_machine_spec.md`) |
| Chaos Mesh installed in its own namespace, with a network policy that lets it act | Not installed; the application namespace enforces restricted pod security and default-deny networking | M9 / M10 |
| Locust on the load machines | Not a repository dependency, installed per run | M10 |
| The console's screens | Every route renders a placeholder on `m8/frontend` | M8 |

Two of these are decisions rather than work: whether the campaign waits for all four branches to
merge, and who provisions the machine. Both are the owner's.

## 2. Carried measurements

Each row names what must be measured, the document that carries it into M10, and the machine it
needs. "Benchmark machine" means the dedicated machine of §4; ADR 0010 forbids gate numbers from
shared runners and from the development laptop.

### From M5 (scoring service)

| # | Measurement | Row | Carried by | Needs |
|---|---|---|---|---|
| 1 | Scoring latency at the specified concurrency | FR-02-07 | ADR 0035 §3, ADR 0032 | Benchmark machine, deployed scorer |
| 2 | Inference latency at the specified percentile, including explanations for flagged transactions | ML-GATE-12 (filed under the model deployment gate, milestone M5 in `requirements.yaml`, defect D-16) | ADR 0035 §3, row note | Benchmark machine, deployed scorer |
| 3 | Throughput and memory over a long scoring run; the memory clause is already verified at its stated scale, the latency clause is not | TEST-10 | ADR 0035 §3 | Benchmark machine |
| 4 | Shadow-mode AUC delta over the specified window and volume | ML-GATE-13 | ADR 0035 §3, row note | A deployment running the shadow comparator for the full window; **not** a short run |
| 5 | **Training/serving skew, re-measured end to end and required to be zero** | FR-02-09, PB-72 | ADR 0034 condition 2, `docs/parallel/M10_updates.md` | Deployed scorer plus the M6 producers (PB-70, PB-71); a verification set scored through the service and compared per feature against the batch path |

Row 5 is the one that cannot be settled by a percentage. M5 measured what the missing state costs
and the ADR records it; the acceptance condition here is equality per row and per feature, not a
tolerable difference in a summary metric. The same ADR requires checking that the missing-producer
counter falls silent **because a producer now writes**, verified against a deliberately withheld
verdict, so that a broken counter cannot pass as a fixed system.

### From M6 (decision engine)

| # | Measurement | Row | Carried by | Needs |
|---|---|---|---|---|
| 6 | End-to-end decision latency at the largest load the machine sustains without errors, **with M5's real scorer** in place of the test double | PB-73 (M6's numbering), affecting FR-01-01, FR-03-01, FR-03-03 | ADR 0059, `docs/parallel/M6_updates.md` on `m6/decision` | Benchmark machine, whole system; `DecisionLatencyBenchmark` opted in |
| 7 | A full-size batch decided within its limit, with a margin recorded over repeated runs | PB-74, affecting FR-01-06 | ADR 0059 point 5 | Benchmark machine; also covered by the `batch` load scenario |
| 8 | High-risk event fan-out at the specified rate | FR-03-01 | `docs/parallel/M6_updates.md` | Whole system under load |
| 9 | The account-profile database fallback bound for long histories | ADR 0062 point 5 | ADR 0062 | Deployed database with realistic history |
| 10 | Whether the event encoding's size is a problem at Kafka throughput | ADR 0012 | ADR 0012 | Measured during row 11 |

### From M9 and the infrastructure decisions

| # | Measurement | Row | Carried by | Needs |
|---|---|---|---|---|
| 11 | Spool append latency on the shared volume and storage class, against the budget of the decision path | D-15 acceptance, ADR 0090 | ADR 0090 acceptance condition | Benchmark machine, the real storage class |
| 12 | The orphan-spool chaos case: Kafka stopped, spools filled, an API pod deleted, Kafka restored, every decided `transaction_id` reconciled | D-15, NFR-REL-02 | ADR 0090 point 5 | Cluster, load, `tests/chaos/06-api-pod-loss-with-spool.yaml` |

If row 11 misses its budget, ADR 0090 names the fallback (per-pod volumes with a partitioned
rolling update) and that is an architecture change, not a re-run: it belongs to the owner, and the
campaign stops at reporting the measurement.

### M10's own rows

| # | Measurement | Row |
|---|---|---|
| 13 | Ingest throughput sustained for the specified duration, with consumer lag and zero data loss | NFR-PERF-01, TEST-09 |
| 14 | Error percentage at the peak rate | NFR-PERF-10 |
| 15 | End-to-end auto-block latency percentiles | NFR-PERF-02 |
| 16 | Ensemble scoring latency percentiles | NFR-PERF-03 |
| 17 | Feature engineering latency including the feature-store fetch | NFR-PERF-04 |
| 18 | The remaining NFR-PERF rows (05 to 08), including the console's timings | NFR-PERF-05…08 |
| 19 | The five failure cases of Part C.4 | NFR-REL-01…04, NFR-REL-06 |
| 20 | The duplicate storm: exactly one scoring, every response cached | NFR-REL-05 |
| 21 | The five analyst journeys on the three desktop browsers | TEST-12 |
| 22 | The device matrix | MOB-DEV-01…07 |
| 23 | The security suite | the M10 gate's security row |
| 24 | The release-size dataset run | ML-DATA-01, PB-25 |

Row 24 is not a benchmark-machine item: PB-25 directs it to a runner, and it is blocked on a
repository setting only the owner can change (`workflow_dispatch` registers from the default
branch). It is listed here because the M10 report must carry its status either way.

## 3. The order to run them in, and why

1. **Record the machine first.** `docs/benchmarks/hardware.md` gains a section for the benchmark
   machine, written from commands run on it, before any measurement. A number measured on an
   unrecorded machine is not evidence and cannot be rescued afterwards.
2. **Prove the harnesses run at all**: a short ingest run at a low rate, one chaos experiment that
   injects nothing, one journey on one browser. This step exists to catch the failure that looks
   like success — a chaos experiment whose selector matches nothing, a load generator that cannot
   authenticate, a journey that passes because the page never loaded.
3. **Component latency rows, quiet system** (2, 1, 3, 16, 17). Scoring before decisions: an
   end-to-end percentile that includes a scorer nobody has characterised cannot be attributed.
4. **Spool append (11)** before any throughput run, because the decision path's budget depends on
   it and because row 12's chaos case assumes it behaves.
5. **End-to-end decision latency (6, 15)** with the real scorer, at rising rates, stopping at the
   largest rate the machine sustains without errors.
6. **Ingest throughput (13, 14)**, held for the specified duration, with the consumer lag and the
   reconciliation read afterwards; then the batch row (7) and the fan-out row (8).
7. **The duplicate storm (20)** and the idempotency-conflict scenario, at load.
8. **Chaos, one case at a time, with traffic flowing** (19, 12): scorer, Kafka and the spool,
   Redis, PostgreSQL, OAuth. Each case is run, held, removed, and followed by reconciliation
   before the next begins; two faults at once produce a story nobody can attribute.
9. **The skew re-measurement (5)** once the M6 producers are deployed, plus the counter check.
10. **The shadow window (4)**, which runs for its full specified duration and therefore starts as
    early as the deployment allows and finishes late; it is the one row the campaign cannot
    compress.
11. **Journeys and device matrix (21, 22)**, on the deployed console with real data.
12. **The security suite (23)**, last on the shared environment because it is the run most likely
    to leave state behind.
13. **The dataset run (24)** in parallel on a runner, whenever the owner unblocks it.

## 4. Evidence rules for every row

* Each measurement writes a file under `docs/benchmarks/` whose header records the command, its
  exit status, the commit, whether the working tree was clean, and the machine id. This is the
  existing convention (`fs-traceability check` enforces the presence of the file and the machine).
* No row moves to a final status on the strength of a summary: the report links the evidence file
  and states the machine, the scale and the scenario.
* A row that misses its threshold is recorded as missed, with its analysis, and the milestone does
  not hold itself open by re-running until it passes.
* Rows that need capacity the machine cannot give are `VERIFIED_AT_REDUCED_SCALE` with the scale
  named, never `DONE`.

## 5. Open decisions for the owner

1. **A backlog identifier collides.** `PB-73` is proposed twice on different branches: once by M5
   for a security-tooling flake owned by M9, once by M6 for the carried latency measurement owned
   by M10. Both were numbered "next free" on branches that could not see each other. The campaign
   needs one of them renumbered before the report cites it.
2. **Where the ingest API's rate limit is defined.** The contract declares the refusal and its
   retry header, but no numeric limit is stated anywhere, so the load campaign cannot tell a
   refusal that is correct from one that is a defect.
3. **Whether the OAuth leg of journey 1 is in scope**, given that the console has no Google button
   yet while the endpoint exists.
4. **Machine provisioning**, `m10_machine_spec.md`.
