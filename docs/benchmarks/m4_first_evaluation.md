# The first M4 metric: a model on D-07's published split

The first figure this project has produced that is **defined on the rows the gates are defined
on**. The pipeline smoke test of 2026-09-20 cut its own 70/30 holdout and said so; this reads the
boundaries the dataset publishes, fits on the train period, scores the test period, and never
touches the embargo.

Raw output and provenance stamp: `docs/benchmarks/m4_evaluate_v3_c8856a0e.txt`.

## The run

`fs-features evaluate dataset/output/bench1m --packs packs.json --split split.json
--corpus-rows 560000 --train-rows 9000 --test-rows 6000`, at commit `03b9064`, on a clean tree,
against the dataset with fingerprint `c8856a0e` (1,006,249 rows, seed 20260917). 34 minutes,
essentially all of it the feature pass.

| | |
|---|---|
| Features trained on | **38** — the computable ones (PB-44, PB-40) |
| Split | train < 2025-08-25 05:24 ≤ validation (calibration from 2025-09-26 13:13) < 2025-10-21 22:02 ≤ embargo < 2025-10-28 22:02 ≤ test |
| Train rows / fraud | 9,000 / 66 (0.733%), spread over 235 days |
| Test rows / fraud | 6,000 / **63** (1.050%) |
| Best single feature, defined on every row | **0.871** (`velocity_ratio_1h_vs_30d`) |
| Best trivial rule | 0.658 (`amount_log1p`) |
| **Model AUC** | **0.986** ±0.020 |
| **Margin over the single feature** | **+0.115** |
| **Margin over the trivial rule** | **+0.329** |
| Recall at 1% FPR | 0.873 |

## How to read it

**+0.115 over one threshold on one feature** is the claim. Not 0.486 over chance: on this
benchmark a single velocity ratio reaches 0.871 by itself, and quoting 0.986 against 0.5 overstates
the model by four times the quantity that matters (PB-46).

Three limits travel with it, and none is hidden in a docstring:

1. **Sixty-three fraud rows.** The ±0.020 is Hanley–McNeil at that count. Sixty-three positives
   can establish that a signal exists; they cannot separate 0.986 from 0.97.
2. **Nothing is tuned and nothing is calibrated.** The validation and calibration periods are
   identified and deliberately unused. Using them without reporting what was tuned would make the
   test figure optimistic in a way no reader could detect, so this run leaves them alone and the
   figure is what an untuned model does.
3. **Trained on 9,000 rows spanning 235 days, not on the whole train period.** The corpus is
   bounded and the feature pass is the cost. The figure is comparable with the gates in its
   *split* and not in its *training volume*.

## What the two earlier runs taught, which is most of the value

Both are kept as evidence rather than deleted.

**Run 1** (`m4_evaluate_c8856a0e.txt`) reported model 0.998, best feature **1.000**
(`days_since_sim_swap`), margin **−0.002**. The margin was meaningless: `auc` drops NaN scores with
their labels — correct, a structurally missing value is not a low value — so that feature was
scored on the accounts that had a SIM swap while the model was scored on all of them. Subtracting
two numbers over different populations is not a margin (PB-55). The baseline now carries the rows
it was measured on, and the margin is taken against the strongest feature defined on **every**
held-out row.

**Run 2** (`m4_evaluate_v2_c8856a0e.txt`) printed the denominator and the finding shrank: the 1.000
rested on **two** confirmed fraud rows.

**Run 3** made the sample representative — an even stride across each period instead of its tail —
and it vanished. The strongest feature at any coverage is now the complete-coverage one. Both
earlier runs had trained on about a week regardless of corpus depth, because the sample size and
not the corpus decided the window; the stride costs the same and spans 235 days.

The general lesson is recorded in the lab notebook: **the first version of a measurement is the
one most likely to be about the sampling.** Every baseline now prints its coverage and its fraud
count beside its figure, so the denominator arrives with the number instead of after it.
