# Review: M5 milestone (scoring service)            Reviewer role: Principal Reviewer

Date: 2026-09-22   Branch/commit: `m5/scoring` at ac3aa18 (reviewed), findings addressed after it
as noted.
Requirements: FR-02-01, FR-02-03 (PB-64), FR-02-04 (serving), FR-02-05 … FR-02-10, ML-GATE-12,
ML-GATE-13, TEST-08, TEST-10. Defects: D-05, D-06, D-11, D-16, D-50.

**Verdict: CHANGES_REQUIRED.** Three MAJOR findings stay open, and none can be closed from `ml/`
alone. Each needs a CI run, an owner decision or another milestone's table (below). No BLOCKER.
M5 is not to be tagged until each MAJOR is fixed, or carried by an owner decision in the ADR 0027
shape.

**Independence.** This review was written by the agent that built M5. Per I.1 it worked from the
diff, the requirement text and the tests, and re-executed every claim; it cannot substitute for
an independent reviewer, and says so.

`main` does not contain M5 (merging is the owner's). Step 1 of I.3 was run on the branch head, in
a clean worktree (`/home/marius/fs-review-m5`, detached at ac3aa18) with a fresh environment
(`uv sync --all-packages --frozen`) and no caches.

## Checks re-run (command → result)

| Check | Result |
|---|---|
| `ruff check ml tools contracts dataset`; `ruff format --check ml tools` | clean; 140 files formatted |
| `mypy --no-incremental` over the Makefile's eight directories | no issues in 197 files |
| `fs-traceability check` | 258 rows, 963 tagged tests, 0 errors, 0 warnings (966 after the D-16 tags below) |
| `fs-licences` | 0 Python violations; 144 npm entries unidentified, because a worktree has no `node_modules` (CI installs them) |
| `ml`: `pytest -p no:cacheprovider` | **502 passed, 2 skipped, 0 failed; coverage 94.23%** (gate 90%). The 2 skips are the `requires_docker` tests, with the explicit message |
| `contracts`: `pytest -p no:cacheprovider` | 490 passed, coverage 93.21%. The frozen proto still matches its baseline, and M5 left `contracts/` untouched |

## Mutation spot checks (what was broken → which test failed)

Each mutation was applied in the review worktree, its tagged test run, and the file restored; the
worktree was verified clean afterwards. **12 of 12 caught.**

| Requirement | Mutation | Failing test |
|---|---|---|
| FR-02-01 | `feature_vector` left empty | `test_every_result_carries_the_nine_fields` |
| FR-02-10 | result written with an empty `model_version` | `test_every_result_carries_the_nine_fields` |
| FR-02-04 | SHAP threshold 0.60 → 0.99 | `test_shap_is_computed_at_and_above_the_threshold_only` |
| FR-02-05 | anomaly above threshold no longer routes to MEDIUM | `test_a_synthetic_outlier_reaches_review_even_below_the_ensemble_threshold` |
| FR-02-06 | Redis thresholds never re-read | `test_a_threshold_change_takes_effect_within_the_refresh_without_a_reload` |
| FR-02-08 | shadow `offer` blocks on a full queue | `test_a_full_queue_drops_rather_than_blocking_production` |
| FR-02-09 | keys written without the 30-day TTL | `test_every_written_key_carries_the_thirty_day_ttl` |
| FR-02-09 | exact ages dropped, so the store path floors | `test_every_feature_agrees_with_the_batch_path_at_every_prefix` |
| FR-02-03 (PB-64) | ECE block raised to 1.0 | `test_a_bundle_over_the_ece_limit_cannot_become_production` |
| FR-02-10 | bundle hash check removed | `test_a_tampered_file_is_refused` |
| D-11 | label-coverage floor removed | `test_below_thirty_percent_coverage_the_gate_says_insufficient_labels` |
| E.4 | the build's parity bound disabled | `test_a_build_whose_served_path_disagrees_is_refused` |

The feature-store parity suite also carries four permanent mutation tests
(`test_the_replay_detects_a_divergence`, `test_the_replay_detects_labels_that_never_arrive`).

## Requirements walked (matrix → test → does it assert the criterion?)

| Row | Assertion reviewed | Criterion met in `ml/`? |
|---|---|---|
| FR-02-01 | nine fields present and typed; exactly the 44 keys; `missing` marker for structural NaN; over gRPC as well | **Yes** |
| FR-02-03 | PB-64: publish, alias move and worker swap each refuse ECE > 0.05 or an unmeasured ECE | **Yes** (the calibration itself is M4's) |
| FR-02-04 | SHAP only at ≥ 0.60; all 44 plus the top five; additivity < 0.001 per model in margin space | **Yes** for serving |
| FR-02-05 | FR-02-05's outlier scenario (amount, location, device) reaches MEDIUM below 0.60 | **Yes** at the model tier; the routing decision is M6's |
| FR-02-06 | the scorer re-reads the threshold hash within 10 s, with no model reload | **Scorer side only.** `PATCH /api/v1/admin/thresholds` is M7's |
| FR-02-07 | harness measures service time and 200-in-flight throughput | **No.** Laptop 288 req/s; the gate is a throughput requirement for M10 (ADR 0032) |
| FR-02-08 | off the hot path, never blocking; only the production result returned; events valid against `fs.ml.shadow`; MLflow comparison flushed | **Partly.** "Enabled via admin panel" is an alias move whose UI is M7/M8; "both scores in audit log" is M6/M7 consuming the topic; the MLflow flush is on an interval, not per transaction (documented deviation) |
| FR-02-09 | 30-day TTL; update latency histogram; parity replay agrees with batch at every step; context-score-write loop | **Partly.** "Stale key handled by DB fallback" is a protocol with no database implementation (MAJOR M5-3); "p99 < 100 ms" is unmeasured on real Redis |
| FR-02-10 | both versions score during a switch and every request begun after it gets the new one; failed verification never swapped in | **Yes** (real MLflow not yet exercised, MAJOR M5-1) |
| ML-GATE-12 | — | **No**, dedicated hardware (M10) |
| ML-GATE-13 | every D-11 clause, with its reason named | **Gate logic yes**; the live 24 h window needs a deployment |
| TEST-08 | as FR-02-08 | **Partly**, as FR-02-08 |
| TEST-10 | memory growth 0.54 MB over 10,000 scorings, model loaded once | **Memory yes; latency no** (M10) |
| D-11, D-50 | promotion gate; alias production / shadow / previous_production with one-move rollback | **Yes** |
| D-16 | multi-process workers, `nthread=1` / single-thread ONNX sessions | **Yes** for serving (tagged after this review, finding M5-5) |

## Findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| M5-1 | MAJOR | `ml/tests/featurestore/test_serving_parity.py`, `ml/tests/serving/test_registry.py` | The two `requires_docker` tests (the parity replay on Redis 7.2.16; publish, swap and metrics on MLflow 3.16.0) have **never run**. fakeredis and the fake MLflow encode my reading of those servers, including ZADD LT, pipelining and the REST shapes | Run them in CI (`REQUIRE_DOCKER=1`) and record the run IDs. This laptop has no Docker, and `gh` is not logged in, so CI results cannot be read here | Open: owner or CI |
| M5-2 | MAJOR | `AccountContext` (frozen contract) | When the API sends its own context, M4's model sees the two trained ages as whole days. On the gate model that moves **10 of 101,909** test transactions across a risk tier (22 scores by > 0.1). Fixed on the store-read path (eca66a6), which is valid today | Approve ADR 0033, making the store-read path the only one, or accept the contract-only path's skew in writing | Open: owner decision |
| M5-3 | MAJOR | `ml/src/fraudshield_ml/featurestore/store.py` `Fallback` | FR-02-09's "stale key handled by DB fallback" and C.4's Redis-down path exist as a protocol with test doubles only; no PostgreSQL reader, because the tables are M6's (`account_velocity_cache`, and PB-37's absent per-account durable table) | Implement the reader once M6's schema exists, or carry the clause to M6 by owner decision | Open: depends on M6 |
| M5-4 | MINOR | FR-02-07, ML-GATE-12, TEST-10 latency | Unmet on the laptop by design. The owner decided (ADR 0032) that this is a throughput requirement measured in M10, gate unchanged | None in M5; the rows stay open with the analysis attached | Carried to M10 (owner) |
| M5-5 | MINOR | `docs/traceability/requirements_matrix.md` | D-16 had no tagged test although its serving half is exercised | Tag the multi-process test and the single-thread ONNX parity test | **Fixed** after ac3aa18 |
| M5-6 | MINOR | `docs/security/threat_model.md` | Not updated for M5's components (I.3 step 4). It is a shared document the M5 agent does not own | The delta is below, for integration | Open: integration |
| M5-7 | MINOR | `ml/src/fraudshield_ml/models/build.py` | The calibrated score differs from `Ensemble.score` by up to 1.97e-4 on 37 test rows, because the fitted isotonic map is near-vertical there. No tier changes; E.4's raw parity is 8.3e-7 | None; it is recorded in the bundle and ADR 0032. Watch it on each new model | Accepted |
| M5-8 | MINOR | FR-02-08 / TEST-08 | The MLflow comparison is flushed every 60 s, not "after each transaction". An HTTP call per scoring would put the registry on the hot path's rate | Owner to accept the deviation, or change the interval | Open: owner |

**Out of `ml/` scope, noted rather than found:** D.3 M5's "fallback rules engine in Java" is
`backend/`, so it is M6's, as are the API's circuit breaker and replay job (C.4).

## Evidence reproduced (claimed vs measured)

| Claim | Claimed | Measured in this review |
|---|---|---|
| ml suite | 501 passed, 2 skipped, 94.21% at d64b6a8 | 502 passed, 2 skipped, 94.23% at ac3aa18 (one test added since) |
| Served model = M4's gate model | 191 / 342 rounds, AUC 0.970, recall 0.871 at 1% FPR, equal-width ECE 0.0014 (gate v3) | same, from `fs-model build` on the gate cache at seed 1 (894fbb4, eca66a6) |
| E.4 ONNX parity | M4: XGBoost 2.98e-7, LightGBM 9.02e-7 | bundle build: 8.27e-7 worst raw over 101,909 rows |
| Mutation spot checks | — | 12 of 12 caught |
| Laptop throughput at 200 in flight | 288 req/s (eca66a6) | not re-run: the machine was shared with two other agents' suites during this review, and a second run would not be more repeatable (ADR 0010) |

## Threat-model delta (for `docs/security/threat_model.md`; M5 does not edit it)

| New element | Threat | Mitigation in M5 | Residual |
|---|---|---|---|
| gRPC scorer (`fs-scorer serve`) | a caller other than the API scores or probes the model (model extraction, D-12) | mTLS with a required client certificate, unless `--insecure`; `test_the_server_requires_a_client_certificate` | certificate issuance and rotation are M9's |
| Model registry (MLflow aliases) | a substituted or tampered model is promoted | bundles are hash-checked per file on load; no pickle anywhere; a failed verification never swaps in; the ECE block on production | anyone with registry write access can move an alias; M9 should restrict it |
| Redis feature store | poisoned or flushed state skews features (PB-37's failure shape) | absent state is "unknown" unless authoritative, counted as `fs_feature_store_unknown_state_total`; members JSON-encoded, so no separator injection; keys hold tokens only, no PII | Redis AUTH/TLS is deployment configuration (M9); no integrity protection on values |
| Redis threshold hash | lowered thresholds weaken review | read-only in the scorer; invalid sets rejected; writer is M7 under ADR 0014's dual control | as M7 implements |
| Shadow events (`fs.ml.shadow`) | shadow scores leak model behaviour | tokens and scores only; schema-validated | topic ACLs are M9's |
| Admin port (FastAPI) | status and metrics expose versions | read-only endpoints; no state change | should not be exposed beyond the cluster (M9 NetworkPolicy) |

## Residual risks

- **Real servers.** The two Docker tests (M5-1) are the only evidence that the Redis layout and the
  MLflow client work against the real servers.
- **Laptop latency figures.** Every one is from a machine shared with other agents' suites. The gate
  is M10's.
- **Contract gaps.** ADR 0033 (proposed) removes the Java reimplementation of the context and, with
  it, M5-2. Until the owner decides, M6 should build its client against the proposed shape, as
  `docs/parallel/M5_updates.md` asks.
- **Self-review.** An independent review pass is still owed (I.1 rule 7's spirit).
