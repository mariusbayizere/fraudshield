# 0031 — How M4's rows close, and the two gate metrics that fail

- **Status:** Accepted; amended 2026-09-22 with the owner's decision on the two failing gate metrics
- **Date:** 2026-09-22
- **Decided by:** author for the rows it settles; **the owner**, 2026-09-22, for ML-GATE-03 and
  ML-GATE-06 (below)
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
ML-GATE-03 and ML-GATE-06. The `m4-complete` tag waits on the same decision. **Superseded for
those two rows by the owner's amendment below; the text is kept as it was decided.**

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
  the same move for ML-DATA-07. **Overruled by the owner** (amendment below), on the condition that
  each row records the miss itself — value, interval and cause — so the status cannot be read as
  "met", and that the miss is published as a finding.
- **Lower D-02's flag threshold until recall reaches 0.88.** A threshold chosen after seeing test
  metrics is tuning on test (D.3), and at the 1% FPR budget recall is 0.871, so no threshold this
  run shows would meet the gate anyway.
- **Move the two rows to a later milestone.** They are M4's by every reading of the prompt; moving
  them would file a failure under a milestone that has not started.

## Amendment 2026-09-22 — the owner's decision on ML-GATE-03 and ML-GATE-06

The owner's rule, applied to each failing gate metric: a metric that **cannot be measured until a
later milestone** is deferred to it with a traceability row, not failed; a **genuine miss on this
benchmark** is recorded with its value, interval and cause, marked `DONE_WITH_DEVIATION` with an
ADR, and goes into the paper as a finding, with no threshold moved and nothing tuned on the test
set; and a failure that **points to a defect in code or measurement** blocks M4 until it is fixed.

**Both are genuine misses.** They are one shortfall stated twice (FNR = 1 − recall at 0.60):

| Row | Measured (declared run, `d40fca2`) | Threshold |
|---|---|---|
| ML-GATE-03, recall at 0.60 | **0.736** [0.707, 0.765]; five seeds 0.744 ± 0.041 | ≥ 0.880 |
| ML-GATE-06, FNR at 0.60 | **0.264** [0.235, 0.293]; five seeds 0.256 ± 0.041 | < 0.120 |

**Not deferrable.** Both were measured in M4, with intervals and seeds; neither needs later
hardware or a later service, unlike ML-GATE-12 (dedicated hardware, ADR 0010) and ML-GATE-13
(M5's shadow scoring), which are already filed under M5.

**Not a defect, on every check available without another look at the test set.** The threshold is
D-02's and half-open as E.6 defines tiers, pinned by a test a mutation proved has teeth. FNR is
read at 0.60, which D-02 binds over the SRS's "at threshold 0.85" wording. The score is calibrated
(ECE 0.001) and at 0.60 its precision is 0.927. The model has no bug that moves this number that
the review, fifteen fixed findings and sixteen mutation checks found.

**The cause, as far as the evidence reaches:**

- **Ranking, not the threshold.** At the 1% FPR budget — a threshold far below 0.60, flagging 0.96%
  of legitimate rows — recall is 0.871. No threshold the run shows reaches 0.88 at an FPR anywhere
  near D-02's reference point of about 0.28%.
- **Not the reversal-scam variant.** About 725 fraud rows are flagged at 0.60 (0.736 × 985). Even
  if every one were among the 919 fraud rows that are not the variant, recall on those would be at
  most 725 / 919 ≈ 0.789.
- **A candidate cause, untested:** the model is trained on 30,000 rows and 260 frauds of a train
  period holding 792,162 (PB-67). Testing it means a larger training sample reported beside this
  run, whichever way it lands.

**Decision.** Both rows become `DONE_WITH_DEVIATION` against this ADR, carrying the measured value,
its interval and the cause above. D-02's thresholds are not changed and nothing is tuned on the
test period. The shortfall is a finding in `docs/research/paper/contributions.md`. M4 is listed as
completed, and `m4-complete` is tagged on the first commit carrying this decision whose CI is green
on that exact commit.
