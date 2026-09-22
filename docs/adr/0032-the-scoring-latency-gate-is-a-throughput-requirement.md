# 0032 — The scoring latency gate is a throughput requirement; serve the smallest frontier model

- **Status:** Accepted; amended 2026-09-22 (below). Decisions 1 to 3 are superseded by the
  amendment; decision 4 and the hardware analysis stand.
- **Date:** 2026-09-22
- **Decided by:** the owner (2026-09-22): serve the smallest configuration on M4's frontier whose
  AUC is inside the best one's interval, evaluate compiling it, re-measure on the laptop labelled
  "not gate evidence", record the gate as a throughput requirement and **leave it as written**.
  The author made the measurements and chose Treelite over ONNX Runtime on them.
- **Requirements affected:** FR-02-07, ML-GATE-12, TEST-10
- **Defects referenced:** D-16
- **ADRs referenced:** 0010 (gate numbers only from the dedicated machine)

## Context

M5's gate says `benchmark.py serve` with **200 concurrent requests** meets **p50 < 15, p95 < 25,
p99 < 40 ms**. The first laptop run (`docs/benchmarks/m5_serve_laptop_4af4e5e.json`, 200 trees at
depth 5) served 183 requests/s on three workers. The server-side scoring time was 12 ms at p50, but
the client waited 1,164 ms, because 200 requests in flight at 183/s queue for about 1.1 s. That is
Little's law, not a slow model.

**The requirement, restated.** Latency with a fixed number of requests in flight is throughput by
another name: *in flight = throughput × latency*. So p50 < 15 ms with 200 in flight needs about
**200 / 0.015 ≈ 13,300 requests/s** sustained, at a median latency that includes any queueing.

M4 measured the other half (`docs/benchmarks/m4_frontier.md`): from 50 trees at depth 3 to 800 at
depth 8, AUC moves by −0.004, inside every interval, while explanation cost grows 35×.

## What was measured for M5 (dev-laptop-01, not gate evidence)

**The frontier, for the served ensemble**, on the bench1m cache (seed 1, commit 5d34bd3, 101,909
test rows, 95% Hanley–McNeil half-widths):

| Trees × depth | Ensemble AUC | Recall at 1% FPR |
|---|---|---|
| 50 × 3 | 0.9685 ± 0.0077 | 0.859 |
| 100 × 4 | **0.9702 ± 0.0075** (best) | 0.858 |
| 200 × 5 (the first bundle) | 0.9647 ± 0.0081 | 0.872 |
| 400 × 6 | 0.9631 ± 0.0083 | 0.876 |

50 × 3 is inside the best configuration's interval, so D-16's rule selects it. Its recall at 1% FPR
is about 1.3 points below 200 × 5, well inside a Wilson interval over the test set's 985 frauds.
The single-feature floor on the same rows is 0.8789.

**Where the time went.** Profiling a scoring showed the trees were not the cost:

- XGBoost's Python wrapper took about 1 ms per call, over half of it probing for `pandas` through
  the import machinery on every call, because a failed import is not cached.
- The Isolation Forest's per-tree Python walk took 0.8 ms.
- On the SHAP path, building the `DMatrix` defaulted to every OpenMP thread, which cost about ten
  times the trees for a single row.

**Compiling.** Parity is measured against the native boosters on the 101,909 test rows; the
timings are single-row p50:

| | XGBoost max difference | LightGBM max difference | XGBoost per row | LightGBM per row |
|---|---|---|---|---|
| native wrappers | — | — | 0.42 ms | 0.047 ms |
| ONNX Runtime 1.30 (float32 input) | 5.4e-7 | **0.20 (7,102 rows over 1e-5)** | 0.013 ms | 0.015 ms |
| Treelite 4.7.2 (GTIL) | 6.0e-7 | 0.0 | 0.057 ms | 0.054 ms |

ONNX cannot carry LightGBM at E.4's 1e-5 parity. Its split thresholds are doubles and the
`onnxmltools` converter accepts float32 input only (it rejects double input outright, pinned by
`test_the_lightgbm_converter_accepts_float32_only_so_serving_uses_treelite`). Treelite is exact on
both and needs no compiler.

**After the changes** (commit 576637f, 50 × 3, Treelite, the vectorised forest, a single-thread
`DMatrix`):

- **Single process:** 0.91 ms p50 unflagged, 2.79 ms p50 on the SHAP path. The mean service time
  is about 1.1 ms, which is **roughly 900 requests/s per worker**, against about 326 before.
- **200 in flight, three workers plus the load client on 2 cores / 4 threads:** 296 requests/s
  (from 183), 0 errors. Server-side p50 10 ms and p99 25 ms, client p50 648 ms
  (`docs/benchmarks/m5_serve_laptop_576637f.json`). Four hardware threads shared by twelve gRPC
  threads and the client make the server time mostly scheduler and interpreter-lock waiting.

## Decision

1. **Serve 50 trees at depth 3** for both boosters (LightGBM `num_leaves` 7), built with
   `fs-model build --trees 50 --depth 3`. Its choice and its interval are recorded in the bundle's
   provenance. A new frontier measurement can move it; this ADR states the rule, not the number.
2. **Predict through Treelite** for both boosters. The native boosters remain for exact TreeSHAP on
   flagged transactions, and `test_treelite_matches_the_native_boosters` holds the two within 1e-6
   for every bundle.
3. **ONNX stays an export**, parity-tested: XGBoost meets 1e-5 on 100,000 rows. LightGBM's export
   is float32-only and is recorded as not meeting E.4's parity, which is a deviation from E.4's
   artefact list for one of the two models.
4. **The gate stays as written.** "p50 < 15 ms at 200 concurrent" is recorded as what it is, a
   throughput requirement of about **13,300 requests/s**, and is measured in M10 on dedicated
   hardware (ADR 0010). It is not restated as per-request service time, which would make it easy.

## What hardware the gate implies

- **Lower bound, from service time alone:** about 1.1 ms of CPU per scoring is about 900/s per core
  of this class (Intel Core i5-6200U, 2016). So 13,300/s needs **at least 15 physical cores given
  wholly to scoring workers**, with the load generator and API elsewhere.
- **What the laptop saw end to end:** about 100/s per worker once gRPC, protobuf and contention are
  included. A dedicated deployment should land between the two figures. For a first M10 sizing,
  plan **24–32 modern cores across scorer pods**, one worker process per core, and measure; the
  answer is the measurement, not this estimate.
- **What would move it:** a C++ or Java scoring path (D-16's premise), or batching several requests
  per model call, which the contract's unary `Score` does not do today.

## Consequences

- FR-02-07, ML-GATE-12 and TEST-10's latency clause stay open until M10, with this analysis and the
  two laptop reports attached. TEST-10's memory clause passes (0.54 MB over 10,000 scorings).
- `treelite==4.7.2` becomes a runtime dependency, with a licence exception, and `onnx`,
  `onnxmltools` and `onnxruntime` become test dependencies.
- The served model is smaller and explains faster. On this benchmark it costs no measurable
  accuracy; on a harder one the frontier must be re-measured before this rule selects again.


## Amendment, 2026-09-22: M4's model, served through ONNX, unchanged in size

**What changed.** Merging `m4-complete` showed that M4 had built the production model in parallel
(`training/model.py`: imbalance weighting, early stopping on validation average precision, the
0.55/0.45 combination, isotonic calibration) together with its Isolation Forest, its SHAP and an
ONNX export. The gate figures ML-GATE-01 to 11 and the paper describe that model. M5's bundle had
been trained separately, so the owner decided (2026-09-22):

1. **Serve M4's evaluated model.** `fs-model build` now runs M4's gate functions on the gate's cache
   and seed and packages what they return. It reproduces the gate's figures: 191 and 342 rounds,
   test AUC 0.970, recall 0.871 at 1% FPR, equal-width ECE 0.0014.
2. **Tree size unchanged.** Decision 1 above (50 × 3) is withdrawn. Changing the model would
   invalidate the gate evidence for no measured need. A smaller configuration is revisited only if
   M10's dedicated-hardware measurement misses the budget, and then the gate is re-run on it.
3. **Inference through ONNX Runtime, not Treelite.** Decision 2 above is withdrawn, on a
   measurement.

**The claim this ADR made about ONNX was wrong, and M4's construction is the fix.** The original
text says ONNX cannot carry LightGBM at E.4's 1e-5, because the converter accepts float32 input
against double thresholds. That was true of the raw boosters measured then. M4 had already
removed the cause: `training.model.float32_exact` rounds every input to float32 and moves each
LightGBM threshold to the largest float32 not above it, so `x <= t` is unchanged for every
float32 `x` and the threshold survives export without moving. The credit is M4's.

**Measured on M4's model, over the whole test period (101,909 rows):**

| | XGBoost max difference | LightGBM max difference | Both models per row, p50 |
|---|---|---|---|
| ONNX Runtime (M4's export) | 8.9e-8 | 8.3e-7 | 0.073 ms (repeat 0.072) |
| Treelite 4.7.2 | 0.0 | 0.0 | 0.357 ms (repeat 0.330) |

Both pass E.4's 1e-5 against the native boosters. ONNX is about 4.8× faster, beyond noise, so it
is served. Treelite is removed, and so is its pending licence exception; ONNX needs no new one.

**A calibrated-score finding.** E.4's parity is on the boosters' probabilities, and it holds. The
isotonic calibrator fitted on the gate model is, however, near-vertical on one segment (slope
about 1.2e5). A 4e-7 raw difference can therefore move a *calibrated* score by up to 1.97e-4:
37 of 101,909 test rows exceed 1e-5 there, and none crosses a risk tier. `models.build` bounds raw
parity at 1e-5, requires no tier change, and records the calibrated gap in the bundle.

**A skew finding, fixed.** M4's model was trained on fractional `device_age_days` and
`days_since_sim_swap`, and today's `AccountContext` carries whole days. Flooring them moves 22 test
scores by more than 0.1 (at most 0.93) and 10 transactions across a risk tier, with AUC unchanged.
The store's read now returns the ages at full precision, so a scorer that reads the store itself
(ADR 0033) feeds the model what it was trained on. Only a request that carries its own context
still sees whole days.

**Re-measured on M4's model** (commit eca66a6, dev-laptop-01, load average 7–12, not gate evidence):
- **Single process:** 1.35 ms p50 unflagged, 8.36 ms p50 on the SHAP path (TreeSHAP over
  191 + 342 trees), about 665 requests/s per worker.
- **200 in flight on three workers:** 288 requests/s, server-side p50 6 ms and p99 25 ms, 0 errors
  (`docs/benchmarks/m5_serve_laptop_eca66a6.json`, `m5_service_time_laptop_eca66a6.txt`).

The hardware estimate above changes only in its per-core figure: about 665/s per core gives **at
least 20 dedicated cores of this class** for 13,300 requests/s, against 15 with the smaller model.
The gate stays as written, for M10.
