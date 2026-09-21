# M4 pipeline smoke test — a pipeline check, not a result

**Nothing here may be quoted as a result.** This is the first end-to-end run of
dataset → features → model → number, done before M4's machinery exists, to find out whether the
path works at all. One model, one split, no tuning, no calibration, and a holdout chosen for speed
rather than for the evaluation design D-07 specifies. The figure it produces is not comparable with
anything M4 will report, and the reasons are listed below rather than left to be inferred.

## What it is not comparable with, and why

1. **It is not D-07's split.** The sample is time-ordered and cut 70/30, which is *a* temporal
   split and not *the* one: no seven-day embargo, different boundaries, and the calibration period
   does not exist. The real split is published now (PB-48) but the benchmark draw in use predates
   the block and refuses to be reconstructed, so the replacement waits on the regeneration PB-41
   and PB-44 already require (PB-49).
2. **It scores a sample, not the dataset.** 20,000 rows with 200,000 transactions of history
   behind them, on a 1,006,249-row draw at seed 20260917.
3. **It trains on the computable features, never on 44.** The count is printed beside every
   metric it reports (PB-44).
4. **It tunes nothing and calibrates nothing**, so a low figure is not evidence that the features
   are weak, and nothing here speaks to ML-GATE-11.

## The number that gives it meaning

Every figure is reported as a **margin over the single-feature floor** (PB-46). On this benchmark
one feature, `velocity_ratio_1h_vs_30d`, reaches `max(AUC, 1−AUC)` of **0.894**
(`docs/benchmarks/single_feature_baseline.md`), so a model at 0.94 is 0.046 better than one
threshold on one feature, not 0.44 better than chance. The command refuses to print a model AUC
without the floor above it, and prints the margin even when it is negative — especially then. The
floor is re-measured on the same held-out rows rather than quoted, so the two numbers describe one
population.

## The run

`fs-features smoke <dataset> --packs <packs.json> --corpus-rows 200000 --sample-rows 20000`,
2026-09-20, on the working tree committed as the change that introduced this file. 13.5 minutes:
**the feature pass is the entire cost** — 20,000 rows at 28 rows/second, about twelve minutes —
and the model fit is seconds. Raw output: `docs/benchmarks/m4_pipeline_smoke.txt`.

| | |
|---|---|
| Features trained on | **36**, the computable ones (PB-44) |
| Train rows / confirmed fraud | 14,000 / 126 |
| Test rows / confirmed fraud | 6,000 / **40** |
| Single-feature floor, same rows | **0.881** (`velocity_ratio_1h_vs_30d`) |
| Model AUC | **0.993** ±0.018 |
| **Margin over the floor** | **+0.112** |
| Recall at 1% FPR | 0.925 |

The floor reads 0.881 here against the 0.894 published in
`docs/benchmarks/single_feature_baseline.md`. That is not a correction to the baseline: it is the
same feature measured on a different 6,000 rows, and the difference is well inside the interval
either figure carries. The published number is the one to cite; this one is the one to subtract.

## How to read the margin, and how not to

**+0.112 over one threshold on one feature** is what this run says. It does not say the model is
0.493 better than chance, and quoting 0.993 against 0.5 would be wrong by four times the quantity
that actually matters.

Read it with two things in mind:

- **Forty confirmed fraud rows.** The ±0.018 interval is Hanley–McNeil at that count. Forty
  positives can support a claim that a signal exists; they cannot distinguish 0.99 from 0.97.
- **The holdout is the easy one.** A 70/30 cut of a time-ordered sample puts the test rows
  immediately after the training rows with no embargo, so an incident spanning the cut has rows on
  both sides. D-07's split exists precisely to prevent that, and this run does not have it. Expect
  the figure to fall when the real split arrives; a fall is the split working, not a regression.

So: **the pipeline works end to end, and nothing beyond that is established.** Dataset to features
to model to a number, on the 36 features the benchmark can feed, with the floor printed above the
model's own figure by construction.

## What it cost, and what that changed

The first attempt at this run spent 106 minutes on 30,000 rows and printed nothing, because the
progress line sat outside the loop; the second spent 11 minutes computing features and then died
on `ImportError: sklearn needs to be installed`, because `XGBClassifier` is the scikit-learn
wrapper. Both are recorded in `docs/research/lab_notebook.md`. Three changes came out of it and
they belong to M4 rather than to this document: the model half is now a tested function
(`fit_and_score`), the feature pass reports its rate and its remaining time, and `--cache` stores
the computed matrix under a key naming the dataset, the corpus size, the sample size and the
feature list, so the next failure after the expensive stage costs seconds.
