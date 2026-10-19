# Round-two evidence: the analysis plan, fixed before any of it runs

Written 2026-10-19 for the owner's second work order, and committed and pushed before the first run
of `.github/workflows/paper-evidence.yml`. It is not edited after that run starts; a change before
it is a dated amendment at the end. The test period has been scored many times before (the access
log), so these analyses are pre-specified in repository history but **not blind to the test set**.
Nothing below chooses a threshold, a model, a feature or a subset after seeing a test result: every
threshold is the specification's (flag 0.60, the 1% false-positive budget), every model and seed is
the published one, and every subset is named here.

Common to every interval below: **1,000 resamples of whole accounts, stratified by whether an
account has any confirmed fraud in the rows concerned, resampling seed 1** (the procedure of
`clustered_intervals.py`). A difference between two models is a **paired** interval: both are
evaluated on the same resample. 95% percentile intervals. No p-value is reported for any test the
clustering invalidates; the published DeLong p-values are replaced by these intervals.

## 1. Gate model, as published (job `gate`, code at `m4-complete`)

The declared gate model (D-05 ensemble, 30,000 training rows, seed 1) refitted from the gate cache
of draw `d8083dbc`. Its headline must reproduce to three places (0.970, 0.871, 0.736) or the run
fails after writing its evidence.

1. **Reversal variant.** Rows with `scenario_variant = reversal_scam_social_engineering` and a
   confirmed-fraud label: count, rows caught and recall at the 1% FPR threshold (strictly above,
   as `gate.recall_at_fpr`) and at the flag threshold (at or above 0.60), each with a Wilson and an
   account-clustered interval. The same split by the row's own `counterparty_is_new_for_account`
   (1 and 0). Small counts are reported as counts first. The clustered interval holds the 1%
   budget's threshold at its full-sample value (so it leaves out the threshold's own uncertainty),
   and a resample that contains none of the subgroup's rows is dropped; both are stated beside it.
2. **Calibration.** Row-weighted ECE over all test rows and the decision region (score at or above
   0.60) with its rows, fraud, ECE and Brier, by `battery.decision_region` and
   `battery.reliability`, the definitions behind the diagnostic model's published 0.0185.
3. **Baselines and ablations.** Every baseline and ablation of `gate_run.run` refitted as it fits
   them; AUC, and the paired account-clustered interval of its AUC minus the ensemble's (the full
   model's, for ablations).
4. **Single-feature floor on the gate's test rows.** Every trainable feature defined on every test
   row (a feature missing on some rows is listed and not ranked, as `gate_run._best` does), as
   `max(AUC, 1−AUC)` with the orientation fixed by the point estimate, with Hanley–McNeil and
   account-clustered intervals.
5. **Leave-one-country-out** (diagnostic battery, `m4-complete`'s `_battery_loco` design): each
   country's AUC in-sample and unseen in training, with account-clustered intervals over that
   country's test rows.
6. **Reconciliation of the test period's confirmed frauds** from the dataset's own labels: rows by
   scenario variant, fraud type, observed label and true label, so every one of the confirmed
   frauds is accounted for. The label-noise rows are identified by their cells (observed and true
   labels differ); individual transactions are not listed.
7. **Two commands re-run on `d8083dbc`, stamped:** the single-feature measurement exactly as
   recorded (`fs-features auc --corpus-rows 200000 --sample-rows 20000`), and the frontier exactly as
   recorded (`fs-features frontier <battery cache> --requests 300 --repeats 3`). The frontier's
   latencies come from a shared GitHub-hosted runner; they are reported as medians with the machine
   named (the artefact's `machine.txt`), never as a gate figure (ADR 0010). `fs-features auc` does not
   log its own test-period read, so the workflow logs it.

## 2. Learning curve with clustered intervals (job `curve`, code at `088cfd2`)

The four learning-curve points rebuilt as recorded: the 340,000-row-corpus cache for 30,000, 60,000
and 120,000 training rows, the 520,000-row-corpus cache for 240,000, five seeds each. Seed 1's recall
at 0.60 must reproduce the published value to three places. Reported per point: recall at 0.60, recall
at the 1% budget and AUC, each with the row bootstrap and the account-clustered interval at seed 1, and
the five-seed mean and standard deviation.

## 3. Training-row selection (job `selection`, code at `088cfd2`)

Section 9.2's like-for-like test: the same 101,909 test rows, the same model and seeds 1–5, 30,000
training rows spread across the training period the corpus reaches, and only the corpus depth
varied: 300,000, 360,000, 400,000, 520,000 and 700,000 rows, and the whole draw. For each: recall at
0.60, recall at the 1% budget and AUC (seed 1 with the row-bootstrap and the clustered interval;
five-seed mean and standard deviation), and the
history each training row has, measured as days from the corpus's first transaction to the row
(minimum, quartiles, and the share under 30 and under 90 days). If a depth does not fit the runner
(killed for memory) or stops on a recorded data gap, it is reported as not run, with the reason; any
other failure fails the run. No depth is dropped or added after a result is seen. Two checks fail
the run: the recomputed training rows must equal the cache's, and the test rows must be identical at
every depth; and the 400,000-row depth, the gate's own design, must reproduce the gate's 0.736.

## 4. The full-volume model (PB-66's artefact, after its run)

From PB-66's uncommitted per-row scores (its pre-specification governs that run; this plan only reads
its output): items 1 and 2 above for the full-volume model, and item 6 cross-checked against its
`label_observed`, `label_true`, `fraud_type` and `variant` columns. The code for this section does not
exist yet; it will be written and committed before PB-66's artefact is read, using exactly the
definitions of items 1, 2 and 6, but it will be written after the results of sections 1–3 are known.

## If a run crashes

A crash is not a result. The defect is fixed in the code, the crash and the re-run are recorded as a
dated note here and in the evidence commit, and the analyses above are not changed. The re-run reads
the test period again, and that read is logged like any other.

## Amendments

None.
