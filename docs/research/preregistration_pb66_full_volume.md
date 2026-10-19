# Pre-registration: full-volume training and PB-66's evaluations, on a GitHub-hosted runner

> **Wording (owner, 2026-10-19).** This document is **pre-specified in repository history:** it was
> first committed as `da3a492` (2026-10-19), and every change is a dated amendment in a later
> commit on `ml/full-volume`, made before any run. The paper does not use the word
> "pre-registered". The title and file name keep the word only because the approved text and its
> path are cited.

- **Status:** APPROVED as amended (owner, 2026-10-19), with § 5 answered in the last amendment.
  **Nothing below has been run** at the time of approval.
  No figure in this file was computed from the test period. The anchors are figures already
  committed on `main`.
- **Rows:** PB-66 (E.5 items 5, 7 and 8), PB-67's follow-up (full volume), C-5.
- **Rule:** this file is not edited after the first run starts. A change made after approval but
  before any run is recorded as a dated amendment at the end. Nothing is changed after a run.

## 1. What runs, where

- **Draw.** `d8083dbc` (seed 20260917, 1,006,317 rows). The runner regenerates it with `main`'s
  generator; `dataset/src` and the feature code are unchanged since `m4-complete` (checked with
  `git diff m4-complete origin/main`). Before any feature is computed, the job asserts:
  - the fingerprint `d8083dbc742c…e6be32`;
  - the split boundaries `test_start = 1761688920337485` and
    `validation_start = 1756099490732196`.
- **Machine.** `ubuntu-24.04`, a GitHub-hosted standard runner. Every evidence file names it in
  `# evidence-machine:`. Accuracy is admissible from any machine (ADR 0010). **No latency figure is
  produced or quoted.**
- **Two jobs, one workflow** (`.github/workflows/full-volume.yml`). It is triggered by a push to
  `evaluate/**`, because a hand-started workflow must sit on the default branch.
  - **Job 1, features.** Generate, assert, then build one feature cache with
    `fs-features evaluate --cache-only`.
    - Volume: the whole corpus (1,006,317 rows), **every train-period row (792,162)**, every
      validation row outside the calibration tail (60,632), every calibration row (40,700) and
      every test row (101,909). The 10,914 embargo rows are history only and are never featurised.
    - It uploads the cache and its SHA-256. No model is fitted and no test row is scored.
  - **Job 2, evaluation.** Download the cache, verify its SHA-256, then run:
    - `fs-features gate --seeds 1 2 3 4 5 --resamples 1000`: the declared gate at full volume;
    - the new `fs-features pb66` report (section 3) on the same model's seed-1 test scores.

    Both commands write to the test-set access log (I.2). The log, the evidence files and the
    per-row seed-1 scores are uploaded as an artifact, `if: always()`, so a failed run's access is
    recorded too.
- **Evidence path.** The artifact is downloaded and committed locally through
  `tools/bin/safe-push`. **No write token in CI** (`permissions: contents: read`).
- **Expected cost.** About 80–130 runner minutes for job 1 and 30–75 for job 2. Public-repository
  standard runners are not billed. A job past 330 minutes is cancelled by its timeout; that run
  reports nothing and is recorded as failed.

## 2. Full-volume gate (PB-67's follow-up)

**What changes from the 240K point** (`m4_learning_curve_240k_d8083dbc.txt`):

- **Training rows:** 240,000 (2,132 frauds) become 792,162 (about 6,830).
- **Corpus depth:** the corpus now reaches back to the draw's first day. Training rows in the first
  90 days have truncated windowed history; that comes from where the data starts, not from a corpus
  choice (PB-76). The report prints their share.
- **Validation (early stopping):** 20,000 rows become 60,632.
- **Calibration:** 20,000 rows become 40,700.

Volume, depth and evaluation rows therefore all change at once, and the result is reported as
"full volume", not as "volume alone".

**Prediction.**

| Measure | Predicted |
|---|---|
| AUC-ROC, seed 1 | 0.960 to 0.980 |
| Recall at 0.60, seed 1 | 0.75 to 0.83 |
| Recall at 0.60, five-seed mean | 0.77 to 0.83 |
| Recall at 0.60, interval's upper bound | below 0.88 (ML-GATE-03 still fails) |
| Recall at the 1 % FPR budget, seed 1 | 0.84 to 0.90 |

The reason: eight times the data (30K to 240K) moved the five-seed mean by only +0.028 (lab
notebook, 2026-09-24). Which rows are used moved recall more than how many.

**Decisions, fixed now.**

- **"Volume moves recall past the 240K point"** is claimed only if both hold:
  - the full-volume seed-1 interval for recall at 0.60 lies entirely above 0.783;
  - the five-seed mean exceeds 0.7945 by more than 0.049 (twice the 240K five-seed SD).
- **"The gate is met at full volume"** is claimed only if the seed-1 point estimate reaches 0.88.
  The paper then states the volume.
- **Otherwise the plateau extends to the full train period.** The paper says the recall shortfall
  is not training volume, up to all 792,162 rows of this draw.

## 3. PB-66's evaluations: what each measures, predictions, decisions

**Scores.** All analyses use seed 1's calibrated test scores, from the same fit the gate reports.
Intervals are 1,000-resample bootstraps: stratified for row-level metrics, as the gate does, and
clustered by UTC day where the metric is per day.

**Baselines (PB-66 and PB-46).** Where a metric ranks rather than reads a probability, the best
single feature on main's evidence, `velocity_ratio_1h_vs_30d`, is reported beside the model. That
covers PR-AUC, precision at a budget, monthly AUC and PR-AUC, and the cost curve. Metrics read at
0.60 or 0.85 have no single-feature counterpart, because a lone feature has no calibrated
probability. They are reported without one, as the gate does.

### 3.1 PR-AUC beside ROC

- **Measures:** average precision with its interval, next to ROC-AUC, and a precision-recall
  figure beside the ROC figure.
- **Prediction:** average precision of **0.80 to 0.93** (seed 1). It will be clearly below
  ROC-AUC, because the test base rate is 0.97 %.
- **Decision:** descriptive. The paper reports both curves wherever it reports ROC.

### 3.2 Precision at a fixed alert budget

- **Source of the budget.** The SRS states none:
  - its only volume control is the target "precision at 1 % FPR, 0.720";
  - D-10 asks for `docs/ml/capacity_model.md` with an alerts-per-hour calculation, and that file
    does not exist.

  So **three budgets are assumed, not required (owner, 2026-10-19): 50, 100 and 200 alerts per day
  per institution.** The draw has no institution column, so the whole draw counts as one
  institution.
- **Measures:** for each full UTC day inside the test period (29 October to 30 December 2025;
  partial first and last days are excluded and counted), the day's top-k scores are alerts.
  Reported per budget:
  - precision and recall, with day-clustered intervals;
  - the alert rate;
  - **the precision ceiling** (that day's frauds ÷ k).
- **Context, stated in advance.** The draw is a small institution: about 1,593 test transactions
  and about 15.4 frauds a day. Every budget is therefore **larger than the daily fraud count**:

  | Budget | Share of traffic | Precision ceiling |
  |---|---|---|
  | 50 | about 3.1 % | about 0.31 |
  | 100 | about 6.3 % | about 0.15 |
  | 200 | about 12.6 % | about 0.08 |

  At these budgets precision is limited by the draw's volume, not by the model. Recall within the
  budget is the informative figure.
- **Prediction:**

  | Budget | Recall within budget | Precision, as a share of its ceiling |
  |---|---|---|
  | 50 | at least 0.88 | at least 85 % |
  | 100 | at least 0.92 | (no prediction) |
  | 200 | at least 0.92 | (no prediction) |

- **Decision:** descriptive. The paper states the budgets as assumed and gives the ceiling beside
  each precision.

### 3.3 Performance by month of the test period (C-5)

- **Buckets, fixed now:**

  | Bucket | Span | Expected frauds |
  |---|---|---|
  | 2025-10 | 28 Oct 22:02 to 31 Oct, about 3.1 days | about 47 |
  | 2025-11 | the whole month | about 462 |
  | 2025-12 | to the end of the test period | about 476 |

  The fraud counts are expectations from the overall test rate, not counts. The run prints the real
  ones.
- **Measures:** per bucket, row and fraud counts, ROC-AUC, PR-AUC, recall, precision and FPR at
  0.60, each with its interval. Also the November-to-December difference in recall at 0.60, with a
  bootstrap interval.
- **What it can and cannot show, stated in advance:**
  - **Scale.** C-5 claims 8–15 % degradation a year, about 1 % a month. That would move recall at
    0.60 by about 0.01 between November and December. The difference's interval will be about
    ±0.05 to ±0.08 wide, so **this run cannot confirm or refute C-5's rate**. It can only rule out
    large drift inside the test period.
  - **The generator.** It has no deliberate monthly drift, apart from the novel SIM-swap
    sub-variant. That variant first appears on 7 November (73 rows).
  - **The October bucket** is reported but too small to read.
- **Prediction:** the November-to-December difference in recall at 0.60 has an interval that
  includes 0, and its point estimate is within ±0.05.
- **Decision:**
  - **If the interval includes 0:** the paper says no change between months is detectable at this
    size, and that C-5 remains unverified by this benchmark.
  - **If it excludes 0:** the paper reports the direction and size, and the sub-variant's share of
    each month beside it.

### 3.4 Cost curve

- **Primary, parameter-free:** the Drummond–Holte cost curve. The normalised expected cost,
  minimised over thresholds, against the probability-cost value from 0 to 1, with the trivial
  classifier min(PC, 1 − PC) drawn beside it.
- **The check that failed before:** every plotted cost is ≥ 0 and ≤ 1, asserted by a test before the
  run. (The latency frontier once produced a negative cost.)
- **Prediction:** the model's curve lies below the trivial classifier's for every PC from 0.01 to
  0.99, and its normalised cost at PC = 0.5 is below 0.06.
- **Decision:** any PC range where it does not is reported as such.
- **Optional, for the owner's decision (see § 5):** an amount-weighted curve. Missed fraud costs its
  `amount_rwf`; a false positive costs an assumed amount at three stated values. It is not built
  unless approved.

### 3.5 Fairness report

**The paper's text before the table, fixed now** (owner's wording, 2026-10-19):

> Fraud mechanisms in this benchmark are country-invariant by construction: no fraud parameter is
> keyed by country (`test_no_fraud_parameter_is_keyed_by_country`). What this report measures is
> therefore the generator's base rates and amount mixes per group, passed through one model, not
> differential treatment of groups by the model.

**Groups.**

- **Country:** the account's home country, read from `currency`. The generator sets currency from
  `customer.country` for legitimate and fraud rows alike, and the five packs have five distinct
  currencies. The run asserts the mapping is one-to-one before using it. This is not
  `counterparty_country`.
- **Channel:** from the cache.
- **Urban/rural: not available.** The exported tables carry no urban/rural or customer-segment
  column; the segment exists only inside the generator. No proxy is joined. The paper says so.
- **KYC tier (also in PB-66): not available,** for the same reason.

**Measures, per group.**

- Rows, legitimate rows and frauds.
- False-positive rate at 0.60 (analyst review) and at 0.85 (auto-block), with intervals.
- **Blocked amount:** the `amount_rwf` of legitimate rows at or above 0.85, as a sum and as a share
  of that group's legitimate amount. The `amount_rwf` of all rows at or above 0.85 is reported
  beside it.

**Counts, stated in advance.** At 240K the FPR was 0.0006 at 0.60 and 0.0002 at 0.85, about 60 and
20 false positives in about 101,000 legitimate rows. Per group, most counts will be single digits,
and many 0.

**Prediction.** Every group's FPR at 0.60 is below 0.5 %, and every group's FPR at 0.85 is below
0.2 %. No pair of countries has non-overlapping intervals.

**Decision.** A group is called "different" only if its interval excludes the overall point
estimate. Even then the paper attributes the difference to the generator's per-group base rates and
amounts, after the paragraph above. It does not attribute it to the model.

## 4. Test-set access

- Job 2 is the only job that scores test rows. Each of its two commands appends one line to the
  access log, with the purpose `PB-66 full-volume evaluation` and the run's ID.
- Job 1 asserts that it writes none.
- **A re-run is a new access.** It is made only to repair a failure that stopped a run from
  completing, and the reason is logged.
- **No threshold, budget, bucket or feature is changed after test scores are seen.** A code fix
  after a look is reported beside the results it changes.

## 5. For the owner's approval before anything runs

1. **The three budgets (50, 100 and 200 a day) all exceed the draw's roughly 15 frauds a day.**
   Precision at each is therefore capped by volume, as § 3.2 says. Keep them as the three, or add
   one budget below the daily fraud count (for example 10) so that one point measures ranking
   rather than volume?
2. **The amount-weighted cost curve** (§ 3.4). Leave it out, or approve three assumed false-positive
   costs?
3. **Scope.** PB-66 also lists leave-one-fraud-type-out and per-channel reliability diagrams. They
   are not in this run. Add them to job 2 (a few minutes each), or leave them for later?

## Amendments

- **2026-10-19, while building, before approval and before any run.** Two details the build fixed,
  both stricter than the text above:
  - **An attempt line.** Job 2 appends an access line *before* any test row is scored
    (`full-volume-attempt`). The gate logs its own access only when it finishes, so a run that died
    part-way would otherwise leave no record.
  - **The gate runs in full.** It includes its baselines and ONNX parity, not `--metrics-only` as
    the PB-67 points did. The gate metrics are computed identically either way.
- **2026-10-19, after the build's independent review, before approval and before any run.** The
  review found two flaws in the methods above, and four places where a promise was not enforced:
  - **Ties at the budget cut (§ 3.2).** Calibrated scores tie heavily. Choosing which tied rows to
    alert by row order would choose by time of day, because the cache is in time order. Instead,
    every row above a tied block straddling the k-th place is alerted, and the block's remaining
    slots are shared evenly: its frauds count in proportion. The report gives the number of alerts
    a tie decided.
  - **Per-group FPR intervals (§ 3.5) are Wilson score intervals, not bootstrap.** With a handful of
    false positives per group, often none, a bootstrap gives [0, 0], and the rule "different if its
    interval excludes the overall estimate" would then flag every group with no false positive.
    Blocked amounts keep bootstrap intervals.
  - **The truncated-history share (§ 2)** is printed by the `pb66` report, from the cache's
    timestamps.
  - **The model is checked, not assumed.** Job 2 fails unless pb66's seed-1 AUC and recall at 0.60
    equal the gate's seed-1 values.
  - **Job 1 asserts every pool's size** before building the cache. `evaluate` would otherwise
    subsample a pool larger than requested without saying so.
  - **Only the pre-registered month difference** (December minus November) is computed.
- **2026-10-19, owner's decisions relayed through M11 (`M11_updates.md` §§ 13 and 14), before
  approval and before any run.**
  - **A memory curve and swap use.** Every heavy step in both jobs runs under
    `.github/scripts/with-memory-curve.sh`. It prints `swapon --show`, then the memory and swap
    used every 30 s, to the job log (which survives a runner lost to memory) and to
    `memory_curve.txt`, which is uploaded.
  - **Per-row scores are not committed** (G.5): they stay in the artifact. The committed evidence
    gives their SHA-256 and the workflow that regenerates them. They carry each test row's score,
    label, timestamp, amount, country and channel, and no account token. M11 computes the paper's
    raw-axes cost view from them: fraud amount caught against legitimate amount blocked and reviews
    required. Drummond–Holte stays this report's cost curve.
- **2026-10-19, the owner's approval and answers to § 5, before any run.**
  1. **A fourth budget, 10 alerts a day, is added**, which is below the draw's roughly 15.4 frauds a
     day. The three larger budgets measure a regime where the budget barely binds; this one shows
     what a binding review limit costs. Its alert rate is about 0.63 %, and its precision ceiling is close to 1:
     a day with fewer than 10 frauds is rare at a mean of 15.4.
     - **Prediction, fixed now:** precision at 10 is **at least 0.90**, and recall is
       **between 0.55 and 0.65**.
     - **Why:** at 240K the 0.60 threshold flagged about 13 rows a day at 0.928 precision, so a
       day's top 10 sit above it, and 10 × 0.93 ÷ 15.4 ≈ 0.60.
     - **Decision:** descriptive, like the others. It is the budget the paper leads with, because
       it is the only one that binds.
  2. **No amount-weighted cost curve.** Drummond–Holte here, plus M11's raw axes from the per-row
     scores. Three assumed false-positive costs would give the most quotable number in the paper
     and the least supported.
  3. **Leave-one-fraud-type-out and per-channel reliability diagrams wait** for a later run. Adding
     them would widen this pre-registration after its review.
  4. **A second review round** of the review's fixes and of this budget change, before the run.
- **2026-10-19, after the second review round (APPROVED_WITH_MINORS), before any run.**
  - **The ceiling's definition, made exact.** § 3.2 says "that day's frauds ÷ k". As computed, each
    day's frauds are capped at k, and the ceiling is pooled over days:
    Σ min(frauds, k) ÷ Σ alerts. At k = 10 an uncapped ratio would exceed 1 on most days. The
    cap is what "close to 1" in the previous amendment assumed.
  - **The per-row scores** are written outside the evidence folder (`scores/`), so they cannot be
    committed by accident with it. The job records their SHA-256, which the committed evidence
    cites.
  - **The memory-curve wrapper** can no longer turn a successful step into a failed one. A failed
    reading is skipped, and stopping the sampler cannot change the command's exit status. A
    spurious failure after scoring would have forced a re-run, which § 4 counts as a new access.
- **2026-10-19, the owner's work order (through M11) and conditions, before any run.** This
  amendment widens the run: it is written, committed and pushed before anything runs, and **the
  owner confirms the leave-one-fraud-type-out ranges below before any run.**
  - **Not blind (0e).** The test period of `d8083dbc` has already been scored: the access log holds
    23 earlier entries, 11 of them over at least 100,000 test rows, and 9 back-filled without a
    row count. These analyses are pre-specified, not blind to the test set.
  - **The "volume helps" rule is out of the prediction's reach (0d).** It needs a five-seed mean
    above 0.7945 + 0.049 = 0.8435. The predicted range for that mean (§ 2) tops out at 0.83, so the
    rule can be met only if the prediction is wrong. That is stated now, and the rule is not
    loosened after the run.
  - **The budgets in other units (0a).** Each budget is also reported as alerts per 1,000 test
    transactions. All four budgets (10, 50, 100, 200 a day) remain assumed.
  - **Outcomes in RWF at each budget (0b).** For each budget: the fraud amount caught and the
    legitimate amount alerted, in RWF, shared at a tie as frauds are. These are outcomes, not
    costs: no false-positive cost is assumed, and Drummond–Holte stays the cost curve.
  - **Per-channel reliability diagrams (E.5 item 6).** Job 2 adds them: ten equal-width bins per
    channel, predicted against observed, as a table and a figure. They are descriptive, with no
    prediction (owner).
  - **The per-row artifact** also carries each row's account ID, fraud type, variant, true label and
    `counterparty_is_new_for_account` (owner: account IDs in the uncommitted artifact only). M11
    computes from it:
    - the reversal variant's recall split by new counterparty;
    - calibration in the decision regions;
    - a reconciliation of the 985 test frauds by variant and label-noise status.

    None of these is computed here. The artifact is never committed, and its SHA-256 is.

  - **Leave-one-fraud-type-out (E.5 item 5), job 3, on its own runner in parallel with job 2.**
    - **Design.** For each of the eight scenarios, every non-test row of that scenario is removed
      from training, validation and calibration, and from the target encoding. That includes
      frauds the observed label missed. The model is refitted with the same seed and scores the
      whole test period.
    - **What is read.** The held-out scenario's test frauds (observed label), at the 1 % FPR
      budget's threshold (the gate's rule, on that model's legitimate test rows) and at 0.60. Each
      recall carries a Wilson interval, beside the production model's recall on the same frauds.
      `mule_account` is also reported without the reversal variant (66 rows, caught 9 % even in
      production; ADR 0028).
    - **Volume.** The production model is fitted first and timed. That fit scores nothing. If nine
      fits at that pace would exceed 270 minutes, all nine fits use 30,000 training rows, spread
      in time, so the comparison stays like for like. The report and the access log say which
      volume ran and why. The decision is made before any test row is scored.
    - **Hypothesis (owner's):** scenarios sharing burst or counterparty-novelty structure keep high
      recall when held out; single-row scenarios drop clearly. The generator's own rows per
      incident are: velocity 6–15, mule 4–12, SIM swap 2–6, account takeover 2–8, synthetic
      identity 3–8, card-not-present 1–5, agent 1–3, merchant 1–3.
    - **Predictions, at the 1 % FPR budget**, held-out recall and drop (production minus held out):

      | Scenario | Held-out recall | Drop | Reason |
      |---|---|---|---|
      | velocity | ≥ 0.85 | ≤ 0.10 | burst (owner's hypothesis) |
      | SIM swap | ≥ 0.85 | ≤ 0.10 | burst (owner's hypothesis) |
      | mule, without reversal | ≥ 0.80 | ≤ 0.10 | burst (owner's hypothesis) |
      | agent | ≤ 0.70 | ≥ 0.20 | single-row (owner's hypothesis) |
      | card-not-present | ≤ 0.70 | ≥ 0.20 | single-row (owner's hypothesis) |
      | merchant | ≤ 0.70 | ≥ 0.20 | single-row (owner's hypothesis) |
      | account takeover | ≥ 0.80 | ≤ 0.10 | mine, see below |
      | synthetic identity | 0.70 to 0.92 | 0.00 to 0.20 | mine, see below |

      - **Account takeover:** a 2–8-row drain from a new device 70 % of the time is the shape SIM
        swap and velocity teach, so never seeing takeover itself should cost little.
      - **Synthetic identity:** its bust-out is a 3–8-row burst at three times the median amount,
        also a taught shape. Its one distinctive signal, a handset shared across a ring, is weak by
        the generator's design (rings are small), so losing it costs some recall but not most.
      - Recall at 0.60 is reported without prediction.
    - **Decisions, fixed now.**
      - A scenario **keeps** its recall if its held-out point estimate meets its row above.
      - A scenario **drops clearly** if the drop is at least 0.20 *and* the held-out Wilson
        interval's upper bound is below the production point estimate.
      - The hypothesis is **supported** if velocity, SIM swap and mule (without reversal) keep and
        agent, card-not-present and merchant drop clearly. It is **refuted** if any of the first
        three drops clearly or any of the last three keeps (held-out recall ≥ 0.85). It is
        otherwise **partly supported**, and the paper names which scenarios held.
      - Account takeover and synthetic identity are reported against my ranges, separately from
        the owner's hypothesis.
- **2026-10-19, after the independent review of the leave-one-fraud-type-out build
  (APPROVED_WITH_MINORS), before any run and before the owner confirms the ranges.** The ranges
  above are unchanged.
  - **What LOFTO measures, stated precisely.** A held-out scenario is removed from every fitted set
    and from the target encoding. The other rows' features were still computed over a history that
    contains its rows: velocity, counterparty novelty, device sharing. So the result is what never
    *fitting on* the scenario costs, not what never *having seen* it costs. The paper says so
    beside the table.
  - **At the 30,000-row fallback,** the rows are spread and then the scenario dropped, so each
    refit trains on 30,000 less that scenario's rows (about 1 %), and its legitimate rows are
    exactly production's. The report gives every fit's training count.
  - **Nothing measured is lost to a time-out.** `lofto.json` is rewritten after every fit and
    marked complete or not. The LOFTO step times out at 315 minutes, before the job's 330, so the
    upload still runs. A run cut short reports the scenarios it finished, and is not re-run unless
    the owner decides.
  - **Transactions missing from `labels` are refused**, not filed as legitimate.
  - **The report also gives the production model's recall over all test frauds** at the 1 % FPR
    budget, so it can be reconciled with job 2's seed-1 model.
