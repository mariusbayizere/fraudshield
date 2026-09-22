# M5 updates for integration

Written by the M5 agent (branch `m5/scoring`). Under the parallel-work rules the M5 agent does not
edit `docs/traceability/requirements.yaml`, `docs/backlog/`, `lab_notebook.md`, `SESSION_STATE.md`,
or anything outside `ml/`. Everything below is a request for the owner to integrate. Each item says
where it goes and why it cannot be done from `ml/`.

## 1. Licence exceptions: `tools/src/fraudshield_tools/licences.py`, `EXCEPTIONS`

**The CI `licences` job fails on `m5/scoring` until these two entries land.** Both packages declare
only a classifier. The bundled licence text was read in the wheel's dist-info on 2026-09-22.

```python
    "python:cloudpickle@3.1.2": (
        "BSD-3-Clause",
        "classifier says only 'BSD License'; dist-info/licenses/LICENSE is the 3-clause text with "
        "the non-endorsement clause naming the University of California, Berkeley (verified "
        "2026-09-22). Runtime, via scikit-learn -> joblib (M5 IsolationForest, D-06)",
    ),
    "python:sortedcontainers@2.4.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; METADATA 'License: Apache 2.0' and "
        "dist-info/LICENSE is the Apache License, Version 2.0 notice (verified 2026-09-22). Dev "
        "only, via fakeredis (M5 unit tests without Docker)",
    ),
```

**MLflow client deliberately not added.** `mlflow-skinny==3.16.0` was tried first. It brings
`certifi` (MPL-2.0, not allowed at runtime under ADR 0009) plus nine packages with unidentifiable
metadata (`requests`, `databricks-sdk`, `google-auth`, `gitdb`, `smmap`, `sqlparse`, among others).
The scorer needs only a handful of registry REST calls: resolve an alias, download a version's
artifacts, log metrics. So `ml/` talks to the MLflow REST API with the standard library instead,
which also keeps every serving process lighter.

## 2. Generated and shared files changed on this branch

Owner rule (2026-09-22): `uv.lock` and `docs/traceability/requirements_matrix.md` may change on any
branch whose inputs changed. They are not ownership violations. On merge, never hand-resolve a
conflict in either: merge the inputs, then re-run `uv lock` or `fs-traceability render`.
`requirements.yaml` stays untouched. Never use `--no-verify` (G.6).

Each commit on `m5/scoring` that touches one of them:

| Commit | `uv.lock` | matrix | Input that changed |
|---|---|---|---|
| 9e46dfc | yes | | `ml/pyproject.toml`: serving dependencies |
| 18c92ac | | yes | new tagged test `ml/tests/serving/test_generated.py` |
| 5552530 | | yes | new tagged tests `ml/tests/models/` |
| 78dd576 | | yes | new tagged tests `ml/tests/models/` |
| 57fcf7c | | yes | new tagged tests `ml/tests/featurestore/`, `ml/tests/serving/` |

## 3. Requirement status for `requirements.yaml` (proposed; the owner integrates)

Statuses are proposals. Test locations are in the re-rendered matrix. "Laptop" means
`dev-laptop-01`, shared with two other agents: under ADR 0010 no latency number from it is gate
evidence.

| Row | Proposed status | Implementation | Evidence and what is still open |
|---|---|---|---|
| FR-02-01 | DONE | `ml/src/fraudshield_ml/serving/scorer.py`, `serving/server.py` | `tests/serving/test_scorer.py` (all nine fields, types, 44-key vector, missing marker), `test_server.py` (over gRPC). |
| FR-02-03 | evidence offered to M4 | `models/isotonic.py`, `models/build.py` | M4 owns the row. M5 now fits D-05's single isotonic regression on the combined score (the M4 battery used Platt). bench1m cache, seed 1: ECE 0.0011 (10 equal-width bins) on 101,909 test rows. Not an `fs-evidence` run yet, so not citable. |
| FR-02-04 | DONE (serving side) | `models/bundle.py` `explain`, `serving/scorer.py` | Additivity per model in margin space below 0.001 (`tests/models/test_bundle.py`); SHAP at or above 0.60 only, all 44 plus the top five (`test_scorer.py`). Deviation: TreeSHAP comes from the boosters' native `pred_contribs` (the same exact algorithm), not the `shap` package. |
| FR-02-05 | DONE | `models/forest.py`, `serving/thresholds.py` | The acceptance scenario (extreme amount, new location, new device, ensemble < 0.60 reaching MEDIUM) in `test_scorer.py`. D-06: the percentile anomaly score, test profile 0.7, production 0.995. The API decides; `model_risk_tier` carries the routing. |
| FR-02-06 | DONE (scorer side) | `serving/thresholds.py` | The scorer re-reads `fs:config:model_thresholds` every 10 s without a model reload (`test_scorer.py`). **Handoff to M7:** `PATCH /api/v1/admin/thresholds` writes that Redis hash (`medium`, `high`, `anomaly_review`) under ADR 0014's dual control. |
| FR-02-07 | VERIFIED_AT_REDUCED_SCALE at best | `serving/benchmark.py` (`fs-bench serve`) | Section 7 has the laptop run. Gate numbers need the dedicated machine (ADR 0010). |
| FR-02-08 | DONE | `serving/shadow.py`, `serving/registry.py` | Off the hot path (a bounded queue that drops and counts, never blocks); events valid against the frozen `fs.ml.shadow` schema; only the production result returned; the comparison flushed to MLflow per shadow version (`test_shadow.py`, `test_registry.py`). **Handoff:** the Kafka producer client (`ProducerSink` takes any producer) and the admin-panel switch, which is an alias move (`fs-model alias shadow <v>`). |
| FR-02-09 | DONE in `ml/`, with a contract gap (section 5) | `featurestore/store.py`, `featurestore/writer.py` | 30-day TTL on every key; the update timed as `fs_feature_store_update_seconds`; a prefix replay agreeing with the batch path at every step, plus four mutation checks (`tests/featurestore/test_serving_parity.py`); the context-score-write loop over gRPC (`test_store_loop.py`). **Not yet run:** the same replay on real Redis 7.2.16 (`requires_docker`, runs in CI). **Handoff to M6:** the `Fallback` protocol (DB fallback from `account_velocity_cache` and the per-account durable table, PB-37). |
| FR-02-10 | DONE | `serving/registry.py`, `serving/scorer.py` `ModelHolder` | Under concurrent load both versions score during a switch and every request begun after it gets the new one (`test_server.py`); alias polling at most every 10 s; a bundle that fails verification is never swapped in (`test_registry.py`). **Not yet run:** against real MLflow 3.16.0 (`requires_docker`). |
| ML-GATE-12 | VERIFIED_AT_REDUCED_SCALE at best | as FR-02-07 | as FR-02-07 |
| ML-GATE-13 | DONE (gate logic) | `serving/shadow.py` `promotion_gate` | Every D-11 clause with its reason named (`test_shadow.py`). The 24-hour live evidence needs a deployment. The gate over a full window runs in the `shadow-comparator` consumer (topics.yaml); the scorer keeps only a bounded in-process window. |
| TEST-08 | DONE | as FR-02-08 | as FR-02-08 |
| TEST-10 | VERIFIED_AT_REDUCED_SCALE | `fs-bench serve`, `fs-bench memory` | Section 7. |
| D-11 | DONE | `promotion_gate` | as ML-GATE-13 |
| D-16 | IN_PROGRESS | multi-process workers, `nthread=1`, SO_REUSEPORT | The latency-constrained hyperparameter search is training (E.4/M4), not serving. |
| D-50 | DONE | `serving/registry.py` | Aliases `production`, `shadow`, `previous_production`; publishing to production records the outgoing version for a one-move rollback. |

**Not in `ml/`, so not done here:** the rule-based fallback engine in Java (D.3 M5 lists it; it is
`backend/`, M6), the API's circuit breaker and replay job (C.4, M6), the API reading
`AccountContext` from Redis (section 5), and the Kafka client wiring (M6/M9).

## 4. The ensemble gap: M5 owns it

As of this writing M4 has no milestone review recording whether the single-booster versus 0.55/0.45
ensemble gap is fixed in M4 or carried. Under the owner's instruction, **M5 owns it either way**:
the served model is the specified ensemble. XGBoost and LightGBM are combined as
0.55·p_xgb + 0.45·p_lgb, one isotonic regression is fitted on the combined score from the
calibration split, and per-model calibrators are kept for display (D-05). SHAP is
0.55·φ_xgb + 0.45·φ_lgb in margin space. `models/bundle.py`, built by `fs-model build`.

Measured on the bench1m cache (seed 1, laptop, **not** a citable `fs-evidence` run):
- Calibrated ensemble test AUC **0.9659**, against a single-feature floor of **0.8789** on the same
  rows (margin +0.087).
- ECE 0.0011, recall 0.875 at 1% FPR.
- **XGBoost alone reaches 0.9683**, so the specified ensemble is not the best single number on this
  split. This is consistent with C-6 (the ensemble does not reduce variance against XGBoost). The
  specification wins; the number is reported as is.

## 5. Contract gaps found (contracts untouched; for the owner to decide)

1. **`AccountContext` carries four ages as whole days (`uint32`)** where the batch features are
   fractional: account age, device age, counterparty account age, days since SIM swap. Two of them
   are trained features. Without a contract change, the bundle is trained on the whole-day view
   serving can reproduce (`models/build.py` `serving_view`, recorded in the manifest), and the
   parity test compares them at whole-day resolution. A future `double` field would remove the
   rounding.
2. **`fs.transactions.scored` has no `counterparty_country` and an optional location.** A consumer
   of that topic cannot keep the store's corridor set or location history, so the scorer workers
   write each scored transaction to the store themselves (`featurestore/writer.py`,
   `fs-scorer serve --feature-store`). Consequence: transactions decided by the rule fallback while
   ML was down reach the store only when the replay job re-scores them.
3. **Who assembles `AccountContext`.** The proto says the API reads it from Redis in one round trip.
   The store's layout (documented in `featurestore/store.py`) and its read logic are Python. A Java
   reader in M6 would be a third implementation of the window arithmetic, and it must pass the same
   prefix-replay parity or the model will be fed different features from the ones it was trained
   on. The alternatives are the API calling a Python context service, or a contract change that
   lets the scorer read the store itself. The owner decides.

## 6. Other notes

- **MLflow REST client.** Tested against a fake that encodes MLflow's REST API, and against real
  MLflow only in the `requires_docker` test, which this laptop has not run.
- **Test packaging.** `ml/tests/serving` is a package, so its `conftest.py` does not collide with
  `ml/tests/features/conftest.py` under the workspace's single mypy run. 6821af1 broke that run and
  ba576f2 fixed it.
- **Commits touching generated files after 8fd9dde:** 6821af1, ba576f2, 84ac2c0, 8253368, 0900246 and
  the integration-test commit, all touching the matrix only.

## 7. Laptop benchmark: machine dev-laptop-01, commit 4af4e5e, clean tree (not gate evidence)

Raw reports: `docs/benchmarks/m5_serve_laptop_4af4e5e.json` and `m5_memory_laptop_4af4e5e.json`.
The machine is `dev-laptop-01` (2 cores / 4 threads, recorded in `hardware.md`), shared with two
other agents; load average 5.4 before, 8.6 after. The bundle was built at the same commit from the
bench1m cache (seed 1): test AUC 0.9647 against a 0.8789 single-feature floor, ECE 0.0010.
Requests are the last 10,500 of 40,000 real bench1m transactions, each with the `AccountContext`
the store assembled on replay.

**TEST-10 memory: passes at the stated scale.** Resident memory grew 0.54 MB over 10,000
consecutive scorings after a 500-scoring warm-up (limit 50 MB), with the model loaded once.

**FR-02-07 / ML-GATE-12 / TEST-10 latency: fails at 200 concurrent here, and the reason matters.**
3 worker processes, 200 in flight, 10,000 requests, 0 errors:

| | p50 | p95 | p99 |
|---|---|---|---|
| server-side `scoring_duration_ms` | 12 | 27 | 39 |
| client-observed, 200 in flight | 1,164 | 1,399 | 1,439 |
| gate | < 15 | < 25 | < 40 |

Throughput was 183 requests/s. By Little's law, 200 in flight at 183/s is about 1.1 s of queueing
per request, which is exactly what the client saw. **The gate as written is a capacity
requirement:** p50 < 15 ms with 200 in flight needs about 13,000 requests/s sustained. One worker
here scores about 61/s, so this Python path at its current cost would need on the order of 200
cores. The per-request cost is close to the target (server p50 12 ms, p99 39 ms, measured while
sharing the CPU with the load client), but only 0.38% of requests took the SHAP path, so the p99
barely includes it.

This is D-16 measured. The levers are E.4's latency-constrained tree complexity (M4/training), a
compiled model path (ONNX, which the M4 gate lists as unverified), or both, then a run on the
dedicated machine. Proposed status: VERIFIED_AT_REDUCED_SCALE for memory; the latency rows stay
open with this analysis attached.

## 8. The ml suite on this branch

At c8ce5c8, clean tree, dev-laptop-01: **429 passed, 2 skipped, 0 failed**. The two skipped tests
are `requires_docker` (real Redis, real MLflow) and print the explicit message. **The coverage gate
fails at 89.08% against 90%.** The M5 modules sit at 86–99% each, about 97% together (the protoc
output is omitted by config). The shortfall is M4's `cli.py` (51%), `training/report.py` (22%) and
`training/frontier.py` (52%). At 4af4e5e, before the M5 entry-point tests, the total was 85.69%,
and the non-M5 code alone is below 90%, which matches M4's red CI. `origin/m4/generalisation` has
commits after this branch's base that test those commands; the rebase onto `m4-complete` will
show whether they close it.


## 9. After the owner's decisions of 2026-09-22

**Licence exceptions, done (owner-approved).** `tools/src/fraudshield_tools/licences.py` gained the
two version-pinned entries from section 1, `python:cloudpickle@3.1.2` and
`python:sortedcontainers@2.4.0`, in c0b4655, a change outside `ml/` made on the owner's approval.
The `tools` licence tests pass (56).

**Three more exceptions, awaiting approval.** The latency work (ADR 0032) added packages whose
metadata the checker cannot identify, so **the licences job fails on this branch again** until
these land. The texts were verified 2026-09-22:

```python
    "python:treelite@4.7.2": (
        "Apache-2.0",
        "classifier says only 'Apache Software License' and the wheel ships no licence file; "
        "METADATA 'License: Apache-2.0', confirmed against the tagged source (github.com/dmlc/"
        "treelite, tag 4.7.2, LICENSE: 'Apache License Version 2.0, January 2004') on "
        "2026-09-22. The wheel bundles libgomp (GCC Runtime Library Exception). Runtime: M5 "
        "serving inference (ADR 0032)",
    ),
    "python:skl2onnx@1.20.0": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; dist-info/licenses/LICENSE is the Apache "
        "License, Version 2.0 text (verified 2026-09-22). Dev only, via onnxmltools (E.4's ONNX "
        "parity test, ADR 0032)",
    ),
    "python:flatbuffers@25.12.19": (
        "Apache-2.0",
        "classifier says only 'Apache Software License'; METADATA 'License: Apache 2.0' "
        "(verified 2026-09-22). Dev only, via onnxruntime (E.4's ONNX parity test)",
    ),
```

**Latency: ADR 0032, accepted by the owner.**
- The served configuration is 50 trees at depth 3, the smallest inside the best AUC's interval
  (0.9685 against 0.9702 ±0.0075).
- Inference runs through Treelite for both boosters, exact to 1e-6.
- The Isolation Forest is vectorised, and the SHAP `DMatrix` is built on one thread.
- Per-request service time on this laptop is 0.91 ms p50 unflagged and 2.79 ms p50 on the SHAP
  path, about 900 requests/s per worker (from about 326).
- The 200-in-flight run reached 296 requests/s on 3 workers (from 183).
- Evidence: `docs/benchmarks/m5_serve_laptop_576637f.json` and
  `m5_service_time_laptop_576637f.txt`, **not gate evidence**. The gate stays as written and is
  recorded as a throughput requirement of about 13,300 requests/s, at least 15 dedicated cores of
  this class, measured in M10.
- ONNX: XGBoost meets E.4's 1e-5 parity on 100,000 rows. LightGBM cannot, because its converter is
  float32-only against double thresholds. This is a recorded deviation from E.4's artefact list.

**FR-02-03's deployment block (PB-64, from ADR 0031): done.** `fs-model publish --alias
production`, `fs-model alias production <v>` and the workers' swap each refuse a bundle whose
held-out ECE exceeds 0.05, or that records none (`test_registry.py`). Proposed: FR-02-03 moves from
`DONE_WITH_DEVIATION` to `DONE`, since the promotion step now exists.

### For the M6 agent: ADR 0033 (proposed contract change, awaiting the owner)

**Do not implement the `AccountContext` assembly in Java.** The owner rejected a third
implementation of the parity-critical window arithmetic. The proposal
(`docs/adr/0033-the-scorer-reads-account-context-from-the-feature-store.md`, with the diff in
`docs/parallel/M5_proto_proposal.diff`; `buf lint` and `buf breaking` pass):

- **`ScoreRequest.context` is removed**, number 2 and its name reserved. The API sends the
  transaction, which carries `account_token`, plus `configured_limits` and `traceparent`.
- **The scorer reads the context from the Redis feature store itself**, one pipelined round trip.
  It moves from the API to the scorer, so no hop is added.
- **`ScoringResult.account_context` (18)** returns what the scorer read, for audit and replay.
  **`ScoringResult.feature_store_degraded` (19)** tells the API to raise DEGRADED_MODE.
- **Status codes:** `UNAVAILABLE` now also means the feature store is unreadable, and the circuit
  breaker handles it as it handles a scorer outage. `INVALID_ARGUMENT` covers a request the scorer
  cannot place.
- **Until approval**, the scorer already accepts a `ScoreRequest` without `context` when started
  with `--feature-store` (`test_store_loop.py`). So M6 can build its client against the proposed
  shape now.
- **The database fallback moves to the scorer side.** M6 owns the tables
  (`account_velocity_cache` and the per-account durable table, PB-37); M5 owns the reader, the
  `Fallback` protocol in `featurestore/store.py`.
- **On approval, the owner or M6 regenerates** `contracts/proto/baseline/scoring-v1.json`, and M5
  regenerates the Python messages.

**A message meant for M7 reached this session.** The owner's hybrid-persistence instruction (Spring
Data JPA for M7's CRUD domain, explicit SQL for the audit chain, `M7_updates.md`) arrived in the M5
session. M5 owns `ml/` only and did not act on it. If the M7 agent has not received it, it needs
resending there.

## 10. The separate-trainer duplication, caught at rebase time

**What happened.** M4 and M5 ran in parallel. M4 built the production model in
`training/model.py`: XGBoost and LightGBM with class-imbalance weighting (`scale_pos_weight`,
`is_unbalance`), early stopping on validation average precision, the 0.55/0.45 combination and
isotonic calibration. It also built its Isolation Forest (`training/anomaly.py`), its SHAP
(`training/explain.py`) and an ONNX export whose LightGBM thresholds are made float32-exact
(`training/onnx_export.py`, `model.float32_exact`). Without seeing that work, M5 wrote
`models/build.py` to train its own copy: the same weights and calibration, but no imbalance
weighting and no early stopping, at fixed rounds and later at the 50 × 3 frontier size.

**How it was caught.** Rebasing onto `m4-complete` conflicted in `ml/pyproject.toml`, where M4 had
added scikit-learn and ONNX Runtime. Reading why showed that M4's docstring names its ONNX export
as "what M5's scoring service runs".

**Why serving the evaluated model matters.**
- ML-GATE-01 to 11 and the paper's figures are measurements of M4's model. Serve anything else and
  those figures describe no system in production: the gate passes on a model nobody runs, and the
  model that runs was never gated.
- Two models with the same calibration can still differ in the decisions that matter. Imbalance
  weighting and early stopping change the score distribution at 0.60 and 0.85, the thresholds that
  hold or decline payments, and the recall and FNR rows (ML-GATE-03 and 06) that M4 recorded as
  missed.
- The owner decided (2026-09-22) that production is the evaluated model, packaged without pickle.

**What changed.**
- The merge, not a rebase, is 2f83fac: no history rewrite. The regenerated `uv.lock` and matrix
  and M4's scikit-learn 1.8.0 pin were taken, and at that commit the ml suite ran 501 passed with
  coverage at 94.27%.
- d64b6a8: `fs-model build` runs M4's gate functions on the gate cache and seed. M5's trainer, its
  own forest and isotonic fitting, its duplicate ONNX module, the complexity options and the
  whole-day training view are deleted.
- The gate bundle reproduces M4: 191 and 342 rounds, AUC 0.970, recall 0.871 at 1% FPR,
  equal-width ECE 0.0014.
- ONNX against Treelite on M4's model (ADR 0032, amended): both exact to within 1e-5, ONNX about
  4.8× faster, so ONNX is served and Treelite removed. **The three pending licence exceptions in
  section 9 (`treelite`, `skl2onnx`, `flatbuffers`) are withdrawn.** M4 had already added
  `skl2onnx` and `flatbuffers`, and `treelite` is no longer a dependency, so the licences job
  needs nothing further from M5.
- 894fbb4: the build bounds E.4 parity on the boosters' probabilities (8.3e-7 measured) and
  requires no risk-tier change. The calibrated gap, 1.97e-4 on 37 rows from the calibrator's own
  steepness, is recorded in the bundle, not bounded at 1e-5.
- eca66a6: M4's model was trained on fractional day ages, and the contract carries whole days.
  Flooring moved 10 test transactions across a risk tier, so the store's read now returns full
  precision for a scorer that reads the store itself (ADR 0033).

**Corrections to earlier sections.**
- Section 4's model numbers (0.9659, 50 × 3 and so on) describe the deleted trainer. The served
  figures are M4's.
- Section 5's first contract gap (whole-day ages) is now handled on the store-read path, not by
  retraining.
- Section 7's latency run used the deleted model. The current figures are in ADR 0032's
  amendment: 288 requests/s at 200 in flight, 1.35 ms p50 unflagged, 8.36 ms on the SHAP path.

**Generated files in these commits:** `uv.lock` and the matrix in 2f83fac and d64b6a8; the matrix
in 894fbb4 and eca66a6.

## 11. M5 milestone review

`docs/reviews/M5/milestone-review.md`, run on ac3aa18 in a clean worktree with a fresh environment.

- **Verdict: CHANGES_REQUIRED**, with three MAJOR findings open and none closable from `ml/`:
  - M5-1: the two Docker tests have never run and need CI.
  - M5-2: the contract-only path's whole-day skew needs ADR 0033 approved, or accepted in
    writing.
  - M5-3: the DB fallback needs M6's tables, or a carry by owner decision.
- **Re-run:** ml 502 passed, 2 skipped, coverage 94.23%; contracts 490 passed; traceability 0
  errors; mypy clean; 0 Python licence violations.
- **Mutation spot checks:** 12 of 12 caught.
- **Threat-model delta:** in the review, for integration into `docs/security/threat_model.md`.

**Proposed statuses** (supersede section 3 where they differ):
- FR-02-01, FR-02-10, D-11 and D-50: `DONE`.
- FR-02-03: `DONE`, now that PB-64's promotion block exists.
- FR-02-04: `DONE`, unchanged.
- FR-02-05: `DONE` for scoring; the routing decision is M6's.
- FR-02-06, FR-02-08, TEST-08: `DONE_WITH_DEVIATION` or `IN_PROGRESS`, pending M7's admin
  endpoint and panel and M6/M7's audit consumer.
- FR-02-09: `IN_PROGRESS`, pending M5-3.
- FR-02-07, ML-GATE-12 and TEST-10's latency: open, carried to M10 by ADR 0032.
- TEST-10's memory clause: passes.
- ML-GATE-13: gate logic done; live evidence needs a deployment.
- D-16: `DONE` for serving.

**Generated file:** the matrix, in the review commit (D-16 tags).
