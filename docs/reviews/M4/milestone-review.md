# Review: M4 milestone (models, calibration, explainability, evaluation)   Reviewer role: Principal Reviewer

Date: 2026-09-22 (UTC)   Branch/commit: `m4/generalisation` at `afd35be` (PR #1)
Requirements: FR-02-03, FR-02-04, ML-GATE-01 … ML-GATE-11, TEST-01, TEST-02, TEST-14
Defects: D-01, D-02, D-05, D-06, D-09 (and D-07, D-08, D-16 as applied)
**Verdict of the first pass: CHANGES_REQUIRED** — 5 BLOCKER, 5 MAJOR. One pass, BLOCKER and MAJOR only.
**Verdict after re-review (below): APPROVED_WITH_MINORS** for every finding; the gate itself passes 9 of 11, and the two failing metrics await the owner (ADR 0031).

**Independence, stated rather than implied.** This review was run in REVIEW MODE (I.1.1) by the
same agent that wrote much of M4, including the reversal-scam variant, C-6 and every fix after
`6a99e47`. Every check below was re-executed from a clean worktree, and the mutation checks were
chosen to break code this reviewer wrote. That does not substitute for a reviewer who did not
write it; the owner may want a second pass on the BLOCKERs' fixes by one.

**What M4 is measured against.** No M4 exit-criteria document was written (PB-45 anticipated one),
so the definition of done is the build prompt's: D.3's gate — *`evaluate.py` reports all 13 gate
metrics with 95% bootstrap CIs; baselines and ablations complete; SHAP additivity test passes;
LaTeX tables and figures generated; ONNX export parity verified* — with E.4 and E.5 as the
detailed scope and D-01, D-02, D-05, D-06 as binding resolutions. ML-GATE-12 (latency) and
ML-GATE-13 (shadow delta) belong to M5 and are out of scope here.

**The short version.** M4 as built is a research-grade **evaluation battery** over one fixed
XGBoost booster, and its findings are real and reproduced below: the reversal-scam detection
failure, the two generalisation nulls, the redundancy finding, C-6, the latency frontier. What it
does not contain is the **model and gate machinery** M4 exists to deliver: the D-05 ensemble with
early stopping and isotonic calibration, the Isolation Forest, `evaluate.py` with the 13 corrected
gate metrics and bootstrap CIs, per-model SHAP and top-5 contributions, ONNX parity, LaTeX tables.
Of M4's 21 traceability rows, none is DONE and 18 are NOT_STARTED. Nothing is marked DONE that is
not, so this is not a fabricated-evidence finding; it is an unmet gate.

---

## Checks re-run (command → result)

All in a fresh worktree at `afd35be` with a virtualenv built with `UV_NO_CACHE=1` and mypy run
`--no-incremental`.

| Check | Result |
|---|---|
| `ruff check tools ml contracts dataset` | all checks passed |
| `ruff format --check …` | 152 files already formatted |
| `mypy --no-incremental` over the 143 files CI checks | no issues |
| `pytest` tools | 198 passed, 90.57% |
| `pytest` ml | 338 passed, 92.07% |
| `pytest` contracts | 490 passed, 93.21% |
| `pytest` dataset | 143 passed, 94.05% (58 min under load average ~10) |
| `fs-traceability check` | 258 rows, 797 tagged tests, 0 errors, 0 warnings |
| `fs-licences` | 288 dependencies, 144 violations — every one an npm package reading `Unknown` because the review worktree has no `frontend/node_modules`; CI installs it, and CI's licence job at `afd35be` passed |
| CI at `afd35be` | run 35685070420: python, java, frontend, governance, licences, pre-commit, gitleaks all success; stack and devcontainer **skipped** (path-filtered), so not evidence for this head |

## Mutation spot checks (what was broken → which test failed)

| # | Mutation | Result |
|---|---|---|
| M1 | `recall_at_fpr` counts positives *at* the threshold (`>=`), the PB-58 tie defect | caught — `test_recall_at_a_false_positive_rate_does_not_charge_nothing_for_ties` |
| M2 | the variant table's `detected` counts ties at the threshold (`>=`) | **survived** — 65 passed. See MAJOR M4-7 |
| M3 | target encoding stops holding out a training row's own fold | caught — `test_the_fast_encoding_matches_the_obvious_one` and `test_a_training_row_is_scored_without_any_row_of_its_own_account` |
| M4 | embargo rows classified as validation, i.e. fittable | caught — `test_each_period_claims_the_rows_inside_it_and_the_boundaries_are_half_open` |
| M5 | SHAP additivity check drops the bias column | caught — `test_additivity_is_measured_rather_than_assumed` |
| M6 | Platt calibration fitted on the **test** rows instead of the calibration period | **survived** — all 338 ml tests passed. See MAJOR M4-6 |
| M7 | ensemble weights swapped to 0.45/0.55 | caught — `test_the_ensemble_is_the_d05_weighted_combination_and_nothing_else` |

Every mutated file was restored from git and the worktree confirmed clean afterwards. The
mutations were run twice, the second time to record each failing test by name; that pass
overlapped the battery reproduction below in the same worktree. The reproduction had imported its
modules before any mutation was applied, and its output is byte-identical to the committed
artefact, which a leaked M1 or M6 mutation would have changed.

## Evidence reproduced (claimed vs measured)

| Claim | Source | Reproduced |
|---|---|---|
| The whole M4 battery at `d8083dbc`, including the reversal-scam row 4/66, AUC 0.656 ±0.072, and base 779/827 | `docs/benchmarks/m4_battery_pb61.txt` | **identical** — re-run against the same cache, the output matches the committed artefact byte for byte apart from the cache-path line |
| Reversal-scam Wilson interval [2.4%, 14.6%] | lab notebook, PB-61 | recomputed independently from 4 and 66: [0.0238, 0.1457] |
| `main` at `m3-complete` green on every job, stack and devcontainer executed | PR #1 | confirmed from check-runs on `f8885d6` |

---

## Findings

| # | Severity | Location | Finding | Required action | Status |
|---|---|---|---|---|---|
| M4-1 | BLOCKER | `ml/src/fraudshield_ml/training/` | No `evaluate` step computes the 13 gate metrics as corrected by D-01/D-02; no bootstrap CI anywhere in `ml/src`; `metrics/operating_points.py` is called by nothing and cites an `evaluate.py` that does not exist | Build E.5's evaluate: the 11 M4 gate metrics at D-02's operating points (0.60 flag, 0.85 block), Recall@1%FPR with precision and its D-01 ceiling, ECE with 15 equal-mass bins plus equal-width and Brier, 1,000-resample stratified bootstrap CIs, E.5's baselines (rule engine, logistic regression, random forest, XGBoost alone, LightGBM alone, Isolation Forest alone — the gate's "baselines complete"; the battery has only the single-feature floor and trivial rules), `reports/metrics.json`, a pass/fail gate summary, and a CI ML gate (TEST-14) | fixed — `d7a3a6f`, `bbbc4d8`, `bcaea94`, `07ed1ed`, `d40fca2` |
| M4-2 | BLOCKER | `training/smoke.py`, `cli.py` | The evaluated model is one XGBoost booster at a fixed 200 rounds. There is no early stopping on validation, no class-imbalance weighting, no LightGBM in the model, no isotonic regression on the combined score (D-05, FR-02-03), and no Isolation Forest (D-06). The battery calibrates with Platt scaling, a deviation from binding resolution D-05 with a docstring justification and no ADR. ECE (ML-GATE-11) is therefore not measured on the score the SRS defines | Implement D-05: both models with early stopping and imbalance weighting, 0.55/0.45 on raw probabilities, one isotonic on the combined score fitted on the calibration split, per-model isotonic for display; the Isolation Forest on imputed features with D-06's percentile `anomaly_score` and its reference distribution. If Platt is kept anywhere, an ADR says why | fixed — `d4b4197`, `232ea1e`, `8abde0f`; Platt scoped by ADR 0029 |
| M4-3 | BLOCKER | `training/battery.py` | Explainability is XGBoost-only: no LightGBM contributions, no weighted 0.55·φ + 0.45·φ in margin space, no per-transaction top-5 (`shap_top5`), no ML-GATE-10 coverage measure, no `docs/ml/explainability.md` (D-05 (4), FR-02-04, E.4) | Per-model exact contributions with the 0.001 additivity test in margin space, the weighted combination, top-5 records, coverage for HIGH and MEDIUM, and the explainability document | fixed — `a34d471` |
| M4-4 | BLOCKER | — | No ONNX export and no parity test (D.3 gate; E.4: max absolute probability difference < 1e-5 on 100,000 rows) | Export both models; parity test; new dependencies through `fs-licences` and ADR 0009 | fixed — `8bf26af`, `8abde0f` |
| M4-5 | BLOCKER | — | No LaTeX tables or figures (D.3 gate; TEST-14; E.5 item 9) | Generate `reports/tables/*.tex` and figures from `metrics.json`, as a CI artefact | fixed — `bcaea94` |
| M4-6 | MAJOR | `cli.py` `_battery_calibration` | Calibration–test separation has no guard test: fitting Platt on the test rows passes the whole suite (mutation M6). I.2 requires calibration split separation to be verified by guard tests | A test that fails if the calibrator sees a test-period row, applying to the D-05 isotonic fit as well | fixed — `2c2cd2d` |
| M4-7 | MAJOR | `training/battery.py` `by_variant` | The variant table's tie handling is unguarded (mutation M2). This is the code path behind the PB-61 headline, and the same tie defect produced PB-58's impossible recall | A test with negatives tied on the threshold that fails under `>=` | fixed — `2c2cd2d` |
| M4-8 | MAJOR | whole milestone | No test-set access counter (I.2). Test periods have been scored by at least 11 committed evaluation runs across three draws (`c8856a0e`, `6abde44e`, `d8083dbc`) — 7 `evaluate`, 2 `battery` (each refitting ~25 models), 1 `frontier`, 1 `seed-variance` — plus uncommitted previews. The generator was changed after test-period results were seen (the PB-61 variant, pre-registered, and PB-56). No model was tuned, but nothing records the looks | An append-only access log written by every command that scores test rows; the M4 gate evaluation declared as *the* final evaluation for this draw; the prior looks listed in the model card | fixed — `bcaea94` (log and back-fill); every later run logged |
| M4-9 | MAJOR | whole milestone | No MLflow tracking. I.2 requires metrics reproducible from an MLflow run ID; E.4 requires params, metrics, data hashes, git SHA, environment lock and hardware logged. `fs-evidence` stamps are sound provenance but are not what is specified | Log `train` and `evaluate` to MLflow alongside the `fs-evidence` stamp, or an ADR recording the substitution | resolved by ADR 0030 (pending owner confirmation) and `0220bea` |
| M4-10 | MAJOR | `docs/traceability/requirements.yaml` | No M4 row carries the evidence that does exist (per-channel AUCs, recall at 1% FPR, calibration, C-6, SHAP additivity); all 21 read as if nothing were built. The matrix is the milestone's record of completeness | Update each row with its evidence as the BLOCKERs close; nothing moves to DONE without a test that asserts its criterion | fixed — every M4 row updated; see the re-review |

## Area by area

**Leakage — sound where guarded, with two gaps.** The temporal split, the embargo and the
account-grouped out-of-fold encoding are each guarded by a test that a mutation proves has teeth
(M3, M4). Calibration separation (M4-6) and test-set access (M4-8) are not.

**Metric code — sound where it exists.** Recall at a false-positive rate handles ties (M1), the
Wilson interval is correct and now clamped, and additivity is measured rather than assumed (M5).
The variant table's tie handling (M4-7) is correct but unguarded.

**Contracts, services, hot path.** M4 touches only `ml`, `dataset`, `tools`, the Makefile,
`uv.lock`, the README and docs. No contract, schema, backend or front-end file changed, so the
cross-component and latency-budget checks (I.3.3, I.3.5) have no delta.

**Threat model.** No new component, service, data flow or credential. The one generator change
adds synthetic test-period rows. `docs/security/threat_model.md` needs no update for M4 as built;
M4-4's model artefacts and M4-9's MLflow store will need one when they land.

## Confirmed sound, and worth saying

- **The pre-registration held.** The reversal-scam prediction (0.15–0.55) was committed at
  `656d24e` before the draw and has not been edited; the measured 6.1% fell outside it and is
  reported as outside it.
- **Two nulls were published as nulls,** with the shared cause named rather than the experiments
  dropped.
- **The committed evidence reproduces** (above), and every artefact carries its commit and
  tree state.

## Residual risks

- The benchmark is velocity-separable (PB-46): every model metric here is on synthetic data that one
  feature nearly solves, and the gate thresholds will be easy to meet for that reason, not because
  the model is good. E.5's baselines and the single-feature floor must be printed beside every gate
  number.
- The test period has been looked at many times (M4-8). A gate evaluation run after this review is
  still the first *gate* evaluation, but it is not a first look.
- ML-DATA-01 remains `VERIFIED_AT_REDUCED_SCALE` (PB-25).

---

## Re-review — 2026-09-22, at `af8af00`

Every BLOCKER and MAJOR above was fixed and re-checked. Five more were found **after** the first
pass, while fixing it, and are recorded here rather than folded in: two BLOCKERs the first pass
should have caught by reading the rows' own notes, and three that only a real run could expose.

| # | Severity | Found by | Finding | Fixed |
|---|---|---|---|---|
| M4-11 | BLOCKER | reading FR-02-03's notes while updating the register | **Training-row target encoding broke E1**, a rule recorded as settled on 2026-09-19: encodings over rows strictly earlier in time, never random folds. `encode_categoricals` used account-grouped random folds, so a training row read later training rows. Every M4 model was trained this way. Test rows were always encoded from training rows only, so no test metric was optimistic for this reason, but the battery, C-6 and the first two gate runs all rest on it | `71a91d9`; battery, C-6 and gate re-run (`af8af00`) |
| M4-12 | MAJOR | reading ML-GATE-07's notes | **PB-46's reporting rule was unmet**: every model metric beside its best single feature and best trivial rule, as a margin. The gate printed one floor | `d40fca2` |
| M4-13 | BLOCKER | the first declared gate run | **The LightGBM ONNX export disagreed with the evaluated model** on 398 of 101,909 test rows, by up to 0.229. The synthetic parity test passed because random normals rarely collapse to one float32 | `8abde0f`, with a test that fails on the old code |
| M4-14 | MAJOR | the first declared gate run | **Precision at "1% FPR" printed above its own ceiling** (0.641 against 0.494): isotonic ties left the realised FPR at 0.47% | `07ed1ed` |
| M4-15 | MAJOR | updating TEST-01 | **"All 44 features on all 6 channels" had no test.** FR-02-02's evidence claimed one; no feature test used BANK_TRANSFER or ONLINE | `e5959f1` |

### Checks re-run

| Check | Result |
|---|---|
| `ml` suite at the head | 389 passed, 93.15% |
| `tools` suite | 198 passed, 90.57% |
| mypy `--strict`, ruff, format | clean over the 158 files CI checks |
| `fs-traceability check` | 258 rows, 888 tagged tests, 0 errors |
| `dataset`, `contracts` | unchanged since `afd35be`, where the from-scratch run passed (143 and 490); CI re-runs both at the head |
| CI at the head | recorded below once the run finishes |

### Mutation spot checks on the new code

All caught: RowSplit's overlap refusal removed; calibrators fitted on validation rows; SHAP
combination weights swapped; operating points made strict; the whole booster exported; a baseline
fitted on test labels; Isolation Forest medians over every row; E1's own-account exclusion
removed; E1's look-ahead reintroduced. The two survivors of the first pass (M2, M6) are now caught.

### The gate

The declared run is `docs/benchmarks/m4_gate_d8083dbc_v3.txt`: **9 of 11 pass**, every ranking
metric beside PB-46's baselines with a positive margin, ONNX parity inside 1e-6. **ML-GATE-03
(recall at 0.60, 0.736) and ML-GATE-06 (FNR at 0.60, 0.264) fail.** D.3 asks for the measured
value and the analysis, not tuning; both are in the lab notebook and PB-62, and the rows stay
`IN_PROGRESS` (ADR 0031). Whether M4 closes with them recorded is the owner's decision, and so is
the `m4-complete` tag. **Decided 2026-09-22:** both are genuine misses — measurable now, and no
defect found in code or measurement — so both are `DONE_WITH_DEVIATION` with the miss recorded in
the row, the cause in ADR 0031's amendment, and a finding in the paper. M4 closes.

Three gate runs were made on the same test period, and all three are committed and logged. Two
defects and one rule violation were fixed between them; no model setting or threshold changed
because of a metric.

### Minors carried

PB-63 (the CI gate needs a dataset), PB-64 (clauses needing M5's service), PB-65 (D-06's routing
threshold, M6), PB-66 (E.5 items beyond the gate: fairness, cost curve, drift, per-channel
reliability, the latency-constrained search), PB-67 (training volume), PB-68 (frontier and battery
prose at the new draw).

**Verdict: APPROVED_WITH_MINORS.** Every finding is fixed; the reviewer is still the author.
