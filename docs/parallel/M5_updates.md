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
