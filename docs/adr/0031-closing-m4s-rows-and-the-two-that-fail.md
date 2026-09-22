# 0031 — How M4's rows close, and the two gate metrics that fail

- **Status:** Accepted for the rows it settles; **the two failing gate metrics await the owner**
- **Date:** 2026-09-22
- **Decided by:** author, after the M4 milestone review and its fixes; the owner decides ML-GATE-03
  and ML-GATE-06 and may overrule anything here
- **Requirements affected:** FR-02-03, FR-02-04, ML-GATE-01 … ML-GATE-11, TEST-01, TEST-02,
  TEST-14, D-01, D-02, D-05, D-06, D-09
- **ADRs referenced:** 0027 (the M3 precedent), 0029 (Platt stays in the battery), 0030 (MLflow)

## Context

M4 has 21 Must rows. `fs-traceability check` will list M4 as completed only when every one is in a
final status with evidence, and `milestones.yaml` lists a milestone as completed "only after its
gate passed". The declared gate run (`docs/benchmarks/m4_gate_d8083dbc_v3.txt`, at `d40fca2`)
passes nine of eleven gate metrics. D.3 says a failing metric is recorded with its analysis, not
tuned away.

ADR 0027 set the rule this follows: a requirement filed under a milestone that cannot meet it is
carried to the milestone that can, with a backlog item as its address, and **no row is marked
done that is not.**

## Decision

| Row | Status | Why |
|---|---|---|
| ML-GATE-01, 02, 04, 05, 07, 08, 09, 10, 11 | `DONE` | Measured on the declared run and passing, each with its 95% bootstrap interval, and the ranking metrics beside PB-46's baselines |
| **ML-GATE-03, ML-GATE-06** | **`IN_PROGRESS`** | **Measured and failing**: recall at 0.60 is 0.736 against 0.88, FNR 0.264 against 0.12. Not marked done, deviated or moved. PB-62 records the analysis and the options; the choice is the owner's |
| FR-02-04 | `DONE` | Per-model additivity within 0.001 in margin space, 100% coverage for HIGH and MEDIUM |
| FR-02-03 | `DONE_WITH_DEVIATION` | Isotonic calibration of the combined score is built and ECE is 0.001. "Deployment blocked if ECE > 0.05" needs a promotion step, which M5 builds (PB-64) |
| TEST-01 | `DONE` | All 44 features on all six channels with D-04's NaN pattern, and the three named scenarios, each tagged |
| TEST-02 | `DONE_WITH_DEVIATION` | The ensemble weighting, the Isolation Forest's raw range, SHAP additivity and calibration are tested. "Fallback rule engine activates when the ML scorer returns 503" needs M5's scoring service (PB-64) |
| TEST-14 | `DONE_WITH_DEVIATION` | `fs-features gate` reports the eleven M4 metrics with LaTeX tables and `--enforce` for CI. ML-GATE-12 and 13 are M5's; the shadow delta and MLflow logging go with them (PB-64, ADR 0030); running the gate *in* CI needs a dataset there (PB-63, M9) |
| D-01, D-02, D-05 | `DONE` | Recall at 1% FPR is the gate with precision beside its ceiling at the realised rate; the two operating points; the ensemble, its calibration and margin-space SHAP |
| D-06 | `DONE_WITH_DEVIATION` | Both scores exist as D-06 defines them. The routing threshold (0.7 in test, a 0.995 percentile in production) is decision-engine configuration, M6 (PB-65) |
| D-09 | moved to **M11** | C-6 is measured and the card-style ablation behind C-4 now exists, but C-1, C-3, C-5, C-7 and C-8 need primary sources only the author can supply, and D-09's resolution closes when the paper states each claim. That is M11 |

`milestones.yaml` keeps `current: M4` and M4 stays out of `completed` until the owner decides
ML-GATE-03 and ML-GATE-06. The `m4-complete` tag waits on the same decision.

## Consequences

- The register asserts nothing false: two failing gate metrics read as in progress, with their
  measured values in the row and a backlog item that says what would settle them.
- Everything M4 could finish is final, with evidence and tagged tests.
- If the owner accepts the shortfall as measured (PB-62's option c), the two rows need a status that
  records a failed threshold honestly; the register has none today. That is a decision about the
  register, and it is the owner's too.

## Alternatives rejected

- **Mark ML-GATE-03 and 06 `DONE_WITH_DEVIATION`** so the milestone closes. A deviation status on a
  threshold that was missed reads as "met, with a difference", which it was not. ADR 0027 rejected
  the same move for ML-DATA-07.
- **Lower D-02's flag threshold until recall reaches 0.88.** A threshold chosen after seeing test
  metrics is tuning on test (D.3), and at the 1% FPR budget recall is 0.871, so no threshold this
  run shows would meet the gate anyway.
- **Move the two rows to a later milestone.** They are M4's by every reading of the prompt; moving
  them would file a failure under a milestone that has not started.
