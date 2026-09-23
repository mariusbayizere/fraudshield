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

## 12. Owner decisions on the three majors, applied (2026-09-22)

**M5-1, the Docker tests: the owner checks CI.** The owner is checking the `ml` job for
`m5/scoring` on GitHub Actions and will report whether the real-Redis and real-MLflow tests
passed. **Do not tag `m5-complete` until that is confirmed green.**

**M5-2, ADR 0033: approved and applied (c787021).** `contracts/` was changed on this branch, with
the freeze lifted by the owner for this one change:
- `ScoreRequest.context` is removed, with 2 and its name reserved.
- `ScoringResult.account_context` (18) and `ScoringResult.feature_store_degraded` (19) are added.
- `buf lint` and `buf breaking` pass against `origin/main`. Two buf self-tests in
  `contracts/tests/test_proto_breaking.py` moved their injected field from 18 to 90. The contracts
  suite passes (490).
- The scorer reads the account's context itself, so the whole-day skew is gone.

**M5-3, the DB fallback: carried to M6 (0ac0491).** The `Fallback` protocol now covers everything
an expired account's keys held. `featurestore/fallback.py` (`ReplayFallback`) is the reference. The
acceptance test is `ml/tests/featurestore/test_db_fallback.py::test_a_read_through_the_fallback_equals_the_redis_read`:
Redis state expired, the fallback reads the database, and the context and full-precision ages are
identical to the Redis path's. It is parametrised; the `m6-postgresql` parameter is skipped until
M6 supplies its reader. Three deliberately broken fallbacks were caught.

**Proposed traceability row, for the owner to integrate** (M5 does not edit `requirements.yaml` or
`docs/backlog/`), following ADR 0027's carry shape:

```yaml
# ROW_MILESTONE_OVERRIDES / backlog: FR-02-09's DB-fallback clause, carried M5 -> M6
- id: PB-69            # PB-68 is taken (docs/backlog/product.md); confirm at integration
  title: "FR-02-09 / C.4: feature-store DB fallback over M6's tables"
  due_milestone: M6
  carried_from: M5 (milestone review finding M5-3, owner decision 2026-09-22)
  acceptance: >
    ml/tests/featurestore/test_db_fallback.py::test_a_read_through_the_fallback_equals_the_redis_read
    passes with the m6-postgresql parameter enabled: with an account's Redis keys expired, a read
    served by the PostgreSQL Fallback (account_velocity_cache, the transactions hypertable, PB-37's
    per-account durable table) yields an AccountContext and exact ages identical to the Redis
    read, flagged feature_store_degraded.
  requirement_note: "FR-02-09 stays IN_PROGRESS until PB-69 closes; the rest of the row is met in M5."
```

**The lab notebook** gained the entry the owner asked for, in 2c54c87, appended only: "A small
error, amplified: raw parity says nothing after a steep transformation".

### For M6: paste into `docs/parallel/M6_updates.md` (M5 does not edit M6's file)

> **From M5, two things M6 must build against.**
>
> 1. **The scoring contract changed, owner-approved (ADR 0033, merged with M5).**
>    - `ScoreRequest` no longer has `context`: the API sends `transaction` (with
>      `account_token`), `configured_limits` and `traceparent` only. The scorer reads the account
>      context from the Redis feature store itself.
>    - Read `ScoringResult.feature_store_degraded` (19) to raise DEGRADED_MODE. Persist
>      `ScoringResult.account_context` (18) with the decision, for audit and replay.
>    - `UNAVAILABLE` also means the feature store is unreadable; the circuit breaker treats it as
>      a scorer outage.
>    - **Do not implement `AccountContext` assembly in Java.**
> 2. **The DB fallback is M6's (PB-69, carried from M5).** Implement the `Fallback` protocol in
>    `ml/src/fraudshield_ml/featurestore/store.py`, a Python reader over M6's tables, returning
>    what `featurestore.fallback.ReplayFallback` returns. Enable the `m6-postgresql` parameter in
>    `ml/tests/featurestore/test_db_fallback.py`. The test passes only if the features are
>    identical to the Redis path's.

## 13. Session state (written for a cleared session to resume from)

**Where M5 stands (2026-09-22).**
- **Branch and worktree:** `m5/scoring` on origin, worktree `/home/marius/fraudshield-m5`, clean
  and in sync. It merged `m4-complete` at 2f83fac. It has not been merged to `main` (that is the
  owner's) and has **no tag**.
- **Built and tested in `ml/`:**
  - The served bundle is M4's evaluated model, served through ONNX.
  - The Redis feature store has batch parity, and the scorer reads it itself (ADR 0033).
  - The gRPC scorer has mTLS, health checks, hot swap by MLflow alias, and shadow scoring off the
    hot path.
  - Also: the store writer, runtime thresholds, the ECE promotion block, the admin port,
    `fs-model`, `fs-scorer` and `fs-bench`.
- **Last full ml suite:** 502 passed, 2 skipped (Docker), coverage 94.22%, at c787021. Commits
  since then add one tested module (the reference fallback) and documents.

**Open before `m5-complete` can be tagged**, in order:
1. **CI green (M5-1).** The owner is checking the `ml` job for `m5/scoring` on GitHub Actions,
   including `test_the_replay_agrees_on_the_redis_the_deployment_runs` and
   `test_publish_and_hot_swap_against_the_mlflow_the_deployment_runs`. `gh` is not logged in on
   this laptop, so the agent cannot read CI.
2. **The independent Principal Review: done, CHANGES_REQUIRED** (section 14). Its six MAJOR
   findings are fixed; its BLOCKER is half fixed and half an owner decision (ADR 0034). The fixes
   need a re-review before the tag.
3. **Tag `m5-complete` only on a commit whose CI is green**, and after the review is clean.

**Carried, not open:**
- The DB fallback goes to M6 (PB-69, proposed in section 12, acceptance test in place).
- The latency gate goes to M10 (ADR 0032).
- The admin endpoint and panel for thresholds and the shadow switch go to M7/M8.
- The audit consumer of `fs.ml.shadow` goes to M6/M7.

**For the owner to integrate:**
- The status proposals in sections 3 and 11.
- PB-69 from section 12.
- The M6 block from section 12, for `M6_updates.md`.
- The threat-model delta in `docs/reviews/M5/milestone-review.md`.
- `SESSION_STATE.md` still says `main` never merged M2/M3; that is stale.

**Rules this session followed, to keep:**
- The agent owns `ml/`. Owner-approved exceptions: `contracts/` for ADR 0033, `tools/` for the two
  licence exceptions, and the lab notebook entry.
- One full suite at a time, checked by process executable (`ps -eo comm,args`), never by command
  text.
- Commits are small and pushed after each.
- Generated files (`uv.lock`, the matrix) are regenerated, never hand-merged.
- No `--no-verify`.

**Useful paths:**
- Gate cache: `/home/marius/fraudshield/dataset/output/features_gate_d8083dbc.parquet`, seed 1.
- Build the served bundle:
  `.venv/bin/fs-model build --cache <gate cache> --out <dir> --seed 1`. It reproduces 191/342
  rounds and AUC 0.970, or refuses on parity.
- Benchmarks: `fs-bench serve|memory --bundle <dir> --packs <packs.json> --dataset <bench1m>`.

## 14. The independent Principal Review, and what it changed

`docs/reviews/M5/principal-review.md`, committed verbatim (cd85f6b). A fresh agent with its own
worktree at 24a9d1a, its own environment, no access to this session's context. **Verdict:
CHANGES_REQUIRED — one BLOCKER, six MAJOR.** It reproduced every headline number (the gate model,
ONNX parity, the calibrated gap, both suites, the buf checks) and found what the self-review
missed. Three of its twelve mutations survived the author's tests; all three now fail.

**Fixed, in 7a12285, 22f6b1d and bb4ece4:**

| # | Finding | What changed |
|---|---|---|
| 2 | reference keys expired under an active account, silently | `observe` refreshes the profile, tier, SIM-swap and agent-standing keys; a test ages them and checks the features survive |
| 3 | the 0.7 test anomaly profile was served (31% of the gate test period routed to review) | workers default to D-06's production profile (0.995); `--threshold-profile test` is explicit; asserted |
| 4 | D-11's gate gated nothing; `promote()` did not record `previous_production` | production promotion needs a comparator decision or a recorded override; `previous_production` is recorded on both paths; `fs-model` exits 1 on refusal |
| 5 | D-11's numbers were not asserted | boundary cases: AUC delta −0.006 promotes, −0.014 does not; PSI 0.184 promotes, 0.205 does not |
| 6 | the shadow event's score was never asserted | the event must carry the shadow model's own score, and differ from production's |
| 7 | the parity replay never hit a window edge | rows at exactly 60 s, 1 h, 24 h, 7 d, 30 d, 90 d and a microsecond either side; flipping the store to `>=` now fails two tests |
| — | residual risks | ONNX sessions built before a swap publishes; hot-path store timeout 1 s → 0.1 s |

**The BLOCKER is half fixed and half an owner decision — ADR 0034 (proposed).** Nothing deployed
writes the store's outcomes or account reference state, so four trained features are constants in
production. Reproduced independently on M4's gate model over 101,909 test rows:

| Serving state | Test AUC | Tier changes | Frauds reaching 0.60 |
|---|---|---|---|
| as trained | 0.9700 | — | 725 |
| outcomes missing | 0.9619 | 235 | 585 |
| SIM swaps missing | 0.9699 | 25 | 712 |
| **both, as shipped** | **0.9611** | **254** | **563** |

- **Outcomes: seam built (22f6b1d).** `featurestore/ingest.apply_label` applies an `fs.labels`
  event, honours E.2's leakage rule and replaces a superseded verdict. **A consumer of that topic
  still has to be deployed** (M6/M9); `ml/` ships no Kafka client.
- **Account reference state: no topic exists** for SIM swaps, KYC tier, account opening or agent
  standing, so nothing can be wired until a contract and a producer exist (M6/M9; D-25 for the MNO
  signal).
- **Visible rather than silent (bb4ece4):** `fs_feature_store_missing_producer_reads_total{state}`
  counts every read that fell back to a constant.
- **The owner chooses** between deploying both producers before M5 closes, dropping the features
  and re-evaluating (reopening M4's gate), or carrying with honest rows. The author recommends
  carrying. **FR-02-09 must not read DONE until then**, and ML-GATE rows need a note that their
  figures describe the trained model.

**Still open before tagging:** CI green (M5-1), the owner's ADR 0034 decision, and a re-review of
the fixes. The latest full `ml` suite is 521 passed, 3 skipped, coverage 94.31% at bb4ece4.

## 15. ADR 0034: option 3, carry (owner decision, 2026-09-22)

**FR-02-09 stays NOT DONE.** The exact row text for `requirements.yaml`, which M5 does not edit:

```yaml
- id: FR-02-09
  status: IN_PROGRESS          # not DONE: the store is built and tested; production is not fed
  notes: >
    The Redis feature store, its parity with the batch path, the 30-day TTL, the update latency
    metric and the scorer's read are done and tested (M5). The row is NOT done because four
    trained features are served from state no deployed component writes (ADR 0034, option 3,
    owner decision 2026-09-22): outcomes (no fs.labels consumer) and account reference state
    (no topic exists). Measured on M4's gate model over the whole test period, 101,909 rows and
    985 frauds, twice and independently: served AUC 0.9611 against 0.9700 as trained; 254
    transactions change risk tier; 162 of 725 frauds no longer reach the 0.60 flag threshold, a
    22% fall in detections at the operating point. Blocked on PB-69 (DB fallback, M6), PB-70
    (fs.labels consumer, M6), PB-71 (account reference-state contract and producer, M6) and
    PB-72 (M10 re-measures this skew and requires it to be zero). Acceptance tests are in
    docs/parallel/M6_updates.md, M9_updates.md and M10_updates.md.
```

**The other conditions, applied:**
1. The row above.
2. Two carries with acceptance tests, written into `docs/parallel/M6_updates.md` and
   `docs/parallel/M9_updates.md`: (a) a consumer of `fs.labels` calling `apply_label`, with an
   acceptance test proving a verdict reaches the store and the affected features change, including
   the leakage case; (b) a contract and producer for account reference state, with an as-of tier
   test. Both state that **M10 re-measures the skew and requires zero**.
3. The fallback-to-constant metric stays, and two Prometheus rules are written out for the M9
   agent in `M9_updates.md` (M9 owns the rules file and the routing):
   `LabelsConsumerNotWritingOutcomes` (page; silent today, so it signals the consumer stopping)
   and `AccountReferenceStateHasNoProducer` (ticket until PB-71 lands, then page). A single
   `FeatureServedFromConstant` rule over every state, as first drafted, would have paged
   continuously from the day it was deployed.
4. The lab notebook has "2026-09-22 · AUC moved 0.009; a fifth of the detections disappeared", and
   `docs/parallel/M11_updates.md` carries the four-row table for the discussion and limitations,
   with what may and may not be claimed from it.
5. The re-review of the six fixes and the blocker handling runs in a fresh subagent; the owner
   confirms CI afterwards so the tag lands on the final commit.

**A collision to expect at merge:** `M6_updates.md`, `M9_updates.md` and `M11_updates.md` exist on
`m6/decision`, `m9/infra` and `m11/paper` as well. Each file here is that branch's content plus one
appended "From M5" section, so a merge should keep both sides; if git reports a conflict, keep
both.

## 16. The independent re-review, and what it changed (2026-09-22)

The owner's condition 5: the six fixes and the blocker handling were re-reviewed by a **second**
independent reviewer, in its own worktree at `2afbb0e`, with a fresh environment and no caches.
The record is committed verbatim at `docs/reviews/M5/principal-re-review.md` (commit `607cb15`).

**Verdict: CHANGES_REQUIRED**, on seven new MAJOR findings. It reproduced every figure this branch
publishes — the ml suite (521 passed, 3 skipped, 94.31%), contracts (490), buf lint and breaking,
the gate rebuild (191/342 rounds, AUC 0.9700 ±0.0075, ECE 0.00139, recall 0.8711, ONNX parity
8.27e-7, calibrated gap 1.97e-4 on 37 rows) and **all four rows of ADR 0034's skew table,
including the worst score moves and "162 of 725 = 22.3%"**. Findings 2, 5 and 6 of the first
review were confirmed closed, and 3, 4 and 7 closed only in part — which is what the new findings
are about. The fixes for two of them had introduced defects in the promotion path as serious as
what they replaced.

| # | What it found | Answered by |
|---|---|---|
| N1 | D-11's gate could be bypassed twice over: a report with `promote: false` and no `reasons` promoted anyway, and `--override <anything>` skipped the gate and was recorded nowhere although the help text said "(recorded)" | `_gate_problems` now re-derives D-11's clauses from the report's own numbers, and a refusal with no reason is still a refusal; the override reason and the gate's figures are written to the model version's MLflow tags. `test_a_report_that_refuses_without_a_reason_does_not_promote`, `test_an_override_is_recorded_on_the_version_it_promoted` |
| N2 | the fix for finding 4 broke D-50: `fs-model alias production <previous_version>` — the only rollback an operator has — was refused without a shadow report | promoting the version `previous_production` holds is a rollback, not a promotion: the ECE block still applies, the shadow gate does not. `test_a_rollback_through_fs_model_alias_needs_no_shadow_gate` rolls back **through the CLI** and forward again |
| N3 | nothing tested `cli.main`, so the line joining `--threshold-profile` to `WorkerConfig` was free to ship D-06's 0.7 test profile to production | `test_the_profile_a_worker_is_started_with_is_the_one_the_flag_names`, parametrised over both profiles, with `server.serve` monkeypatched. Mutating the line to `thresholds=DEFAULT` now fails it (checked) |
| N4 | the window-edge corpus reached only the account-velocity windows; three inclusive/exclusive flips in the geo-cell 30 d, counterparty 90 d and senders 24 h ranges survived the whole featurestore suite | the corpus now carries a labelled cell row, a labelled counterparty row and three *distinct* senders on their edges (a set-valued window needs a sender per position, or a neighbour masks the edge). Each of the reviewer's three flips now fails two tests (checked) |
| N5 | the alert written for M9 would page continuously: `outcomes` fired on ordinary label latency, and the three reference-state arms fire on every read until the producer exists | the counter now fires only where the state could have applied — a counterparty row older than three weeks (~95th percentile of E.3's 72 h log-normal label delay), an account the store already knows, and never on a degraded read. The rule is split in two: `LabelsConsumerNotWritingOutcomes` (page, silent today) and `AccountReferenceStateHasNoProducer` (ticket until PB-71, then page). Both sides asserted in `test_the_store_counts_features_left_constant_for_want_of_a_producer` |
| N6 | ADR 0034 still read "Status: Proposed … Decision: **Not taken**" while five other documents cited it as the owner's settled decision | ADR 0034 is **Accepted**, with option 3 and all five conditions written into `## Decision`, the carries and the alert linked from `## Consequences` |
| N7 | the carries named no owning milestone and no backlog row, M10 had no artefact at all, and PB-68 was already taken by the frontier write-up item | each carry now has one owner and an id: **PB-69** DB fallback (M6, renumbered off the collision), **PB-70** `fs.labels` consumer (M6), **PB-71** account reference-state contract and producer (M6), **PB-72** M10 re-measures the skew and requires zero. `docs/parallel/M10_updates.md` is new, so the milestone that must close this sees it. **The numbers are proposals**: M5 does not edit `docs/backlog/`, so confirm them at integration |

**Residual risks it listed, and what was done:**

- A `_warm` failure escaping `AliasWatcher.poll_once` would have ended the polling thread, leaving
  the scorer serving on with nothing watching `@production` — including for a rollback. The swap
  callback is now caught, counted as a failure and the next poll retries:
  `test_a_swap_callback_that_raises_leaves_the_watcher_watching`.
- ADR 0034's promised startup line naming the absent producers is now logged by every worker.
- `fs-bench memory` built a `Scorer` on the 0.7 test profile; it now uses the production profile,
  so it measures the routing mix production serves.
- "AUC fell 0.009, comfortably inside the gate's ±0.0075 interval" overstated the arithmetic:
  0.0089 is about one interval **half**-width. Corrected in the lab notebook entry, ADR 0034 and
  `M11_updates.md` — the point is that no AUC-expressed gate would refuse the drop, which stands.
- `pytest-timeout` is still absent (a new dependency needs an ADR 0009 licence review; the
  suite has no hanging test today). The in-process shadow `Comparison` series is still asserted
  only through the MLflow log. Both are left open and named here rather than fixed quietly.

**Not changed, deliberately:** the latency figures stay laptop-only and out of the gate (ADR 0032),
and FR-02-09 stays NOT DONE. Neither is affected by the seven findings.

**Suite after V1-V5:** ml 530 passed, 3 skipped, coverage 94.30%; `fs-traceability check` 258
rows, 994 tagged tests, 0 errors.

**Generated files touched by this section** (the owner's rule: name each commit, regenerate on
merge, never hand-resolve): `docs/traceability/requirements_matrix.md` re-rendered in the commit
below the N5-N7 fixes — 258 rows, 990 tagged tests, `fs-traceability check` 0 errors 0 warnings.
`requirements.yaml` is untouched, as throughout.

## 17. Second verification pass, and the regression it caught (2026-09-22)

Because two of the seven findings in §16 existed *because* an earlier fix round went unreviewed, a
**third** independent reviewer verified the fixes themselves, in its own worktree at `bf97ee2`,
with mutation testing of each fix. Verdict: **CHANGES_REQUIRED**, five findings (V1–V5). It
confirmed the suites (ml 527/3, 94.33%; contracts 490, 93.21%; ruff, mypy 202 files,
`fs-traceability check` 990 tagged tests, seed up to date) and that eleven of its mutations of the
new fixes were caught. The five it raised were real; all are now fixed.

| # | What it found | Answered by |
|---|---|---|
| **V1** | **A regression I introduced in §16.** The N2 rollback exemption trusted `previous_production`, which `fs-model alias` moves freely — so `alias previous_production 2` followed by `alias production 2` promoted a never-served model with D-11 skipped, **and tagged it "rollback"**. Proven end to end by the reviewer | The exemption now rests on `fraudshield.served_as_production`, a version tag only `promote` writes, and only when it actually points `production` at a version. `test_moving_previous_production_by_hand_does_not_buy_a_promotion` runs the exact two-command sequence and asserts `rc == 1`, no tag and no "rollback" wording; the same version, promoted properly, can then be rolled back to. The tag is read back **through the registry** (`has_served`), so the read path is exercised, not just the fake's dict |
| **V2** | `_d11_recheck` policed 3 of D-11's 5 clauses: a report omitting `psi` promoted, the 24 h window was unchecked, and no report was bound to the model it measured | `psi` and `window_hours` missing are now problems in their own right; `GateDecision` carries `window_hours` and `shadow_version`, `promotion_gate` fills both, and a report that names a different model than the version being promoted is refused. Six more shapes added to the refusal table |
| **V3** | N4 fixed the three windows the previous reviewer named, not the class: five more flips survived | `_window_edges` is rebuilt **per Redis key** rather than per window — the account key, the counterparty key, the account key filtered by counterparty, the cell key, the device key and the agent key (a second scored row through an agent). Set-valued windows get one sender per position; the 1 h edge carries three rows so the half-open pair `(t-30d, t-1h]` cannot cancel its two errors. All eight flips now fail two tests each (checked one at a time) |
| **V4** | **A second regression from §16.** `if degraded: return` suppressed the counter for any read where the *account* was unknown — but the outcomes arm reads the *counterparty's* key, and "new customer pays established counterparty" is routine. Neither the suppression nor `LABEL_LATENCY` was pinned by a test | The counterparty arm now runs regardless of `degraded`; only the three account-state arms stand down. Two tests added: the new-account-old-counterparty shape, and the horizon itself asserted at `LABEL_LATENCY` and one microsecond past it |
| **V5** | ADR 0034 claimed all five conditions were "discharged"; condition 1 cannot be, since `requirements.yaml` is the owner's file and the generated matrix still renders FR-02-09 `NOT_STARTED` | ADR 0034 now says conditions 2–5 are discharged and **condition 1 is open**, names the owner's render step, and cites §15 (not §12). The proposed row names PB-69/70/71/72, and §15's stale `FeatureServedFromConstant` is replaced by the two rules that exist |

**What this round did not change:** the skew figures (no training or feature code was touched;
the re-review reproduced all four rows at `2afbb0e`), the latency position (ADR 0032), and
FR-02-09's status. Still open and named rather than hidden: `pytest-timeout` (needs an ADR 0009
licence review), the in-process `Comparison` series asserted only through the MLflow log, the
second half of the first review's finding 2 (a missing `prof`/`tier` as a fallback trigger), the
three `requires_docker` suites, and `fs-bench memory`'s new production profile, which is changed
but not executed by any test.

**One thing worth recording for whoever reads a surviving mutant later:** `ACCOUNT_HORIZON` equals
`W_90D`, so the fetch bound and `_amounts`' own 90 d comparison are redundant with each other.
Flipping either alone is an *equivalent* mutant; flipping both fails the suite (checked). That is
a property of the code, not a hole in the corpus, and it is noted in `_window_edges`' docstring.

## 18. Adversarial pass over the promotion path, and the blocker it found (2026-09-22)

The promotion path had now been broken twice by its own fixes, so a **fourth** reviewer took that
path alone, adversarially: probes against `FakeMlflow` rather than a reading of the code, plus
mutation of each new assertion. It confirmed the V1 fix holds against every alias sequence it
tried, that the `shadow_version` binding does not break re-publishing an identical bundle, and
that round 3's assertions are load-bearing (three mutations of them caught). Verdict:
**CHANGES_REQUIRED**, one BLOCKER and five MAJOR. All are fixed.

| # | What it found | Answered by |
|---|---|---|
| **BLOCKER 1** | **NaN defeats the whole gate.** Every D-11 clause is a `<` or `>=`, and NaN fails all of them, so `fs-model alias production 2 --gate nan.json` returned 0, promoted a never-shadowed version and recorded `promotion_gate = "D-11 passed: … coverage nan%, auc_delta nan, psi nan"`. `json` accepts *and emits* the bare `NaN` token, so a comparator serialising its own dataclass writes exactly that file. The in-house gate can produce one too: `auc()` drops non-finite scores, so a shadow model scoring NaN over a whole class yields a NaN delta and `promote=True` | Three layers. `models/cli._gate` parses with a `parse_constant` that raises, so `NaN`/`Infinity` never becomes a decision. `_d11_recheck` tests `math.isfinite` for every figure — absent and NaN are the same thing, a clause that was not applied. `shadow.promotion_gate` names a non-finite delta as a reason. Tested at both layers, parametrised over all four figures |
| **MAJOR 2** | the rollback's only proof was written best-effort: a `set-tag` failure during publish left production on a version whose emergency rollback was then **refused**, telling the operator that the model which had been serving all along had never served | The tag is written **before** the alias moves and its failure refuses the promotion, so production never points at a version that cannot be rolled back to. When `previous_production` points at a version with no tag, the refusal says exactly that and offers `--override`; a test covers the refusal, the wording and the way through. The docstring's "only this module writes it" is corrected: it is an ordinary MLflow tag, so the proof is as strong as the tracking server's ACLs |
| **MAJOR 3** | `--override ''` promoted in one command and recorded a blank reason — one unset shell variable away in any CI step written as `--override "$REASON"` | Blank and whitespace-only overrides are refused at the CLI and in `_gate_problems` |
| **MAJOR 4** | `geo_cell_fraud_rate_30d` — the other feature PB-70 feeds, and the **only** one an agent cash-out with no counterparty has — was served from the smoothing prior and counted nowhere, although the startup log promised the metric covered it | The `outcomes` arm now reads both keys with the same `LABEL_LATENCY` reasoning. Two tests: the cash-out shape, and one verdict in the cell being enough to fall silent |
| **MAJOR 5** | the `sim_swaps` arm fired on ordinary traffic and **could never fall silent**: most accounts never had a swap and the store writes no "checked, none found" sentinel, so absence of a swap and absence of a producer are the same reading — a page that would be silenced even after PB-71 | The arm is removed, with the reason in the code, in ADR 0034 and in `M9_updates.md`: M9 watches the SIM-swap producer's own liveness once PB-71 gives it one. A test asserts the arm does not exist |
| **MAJOR 6** | `poll_once`'s shadow-teardown callback sat outside the `try` that the swap callback got, so an M6/M9 teardown that raised would still kill the watcher thread | Moved inside the same guard, counted as a failure |

Not filed by the reviewer but fixed with MAJOR 4: the outcomes arm now ignores the scored
transaction's own row, so a replay does not count `outcomes` off itself.

**Suite after this round:** ml 537 passed, 3 skipped, coverage 94.31%; ruff, mypy (162 source
files) and `fs-traceability check` clean.

## From M6 (2026-09-23): a real-MLflow test fails on this branch

Written by the M6 session at the owner's direction (M6 does not fix M5's code). CI's `ci` run #282 on
`90078b2` (this branch's head) fails in the python job at step 9, the `ml` pytest step; the M6
session reproduced it on a clean checkout of `origin/m5/scoring` with `REQUIRE_DOCKER=1`:

```
tests/serving/test_registry.py::test_publish_and_hot_swap_against_the_mlflow_the_deployment_runs
fraudshield_ml.serving.registry.RegistryError: GET /api/2.0/mlflow/registered-models/alias:
HTTP 400 {"error_code": "INVALID_PARAMETER_VALUE", "message": "Registered model alias
production not found.", ...}
```

It is the only failure in `ml` (558 passing, coverage 94.54% on the combined M5+M6 tree). This is
the "M5-1, the Docker tests" item in section 12: CI is **not** green for `m5/scoring`, so
`m5-complete` must not be tagged yet, and `m6/featurestore-fallback` (which contains this branch)
is red in CI for the same test until M5 fixes it.
