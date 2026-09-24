# Lab notebook

Raw material for the method and discussion sections of the paper (arXiv, M11). Appended at the end
of every milestone and whenever something notable happens — a decision and its reason, something
tried and rejected, an assumption that proved wrong, a bug whose root cause is methodologically
interesting, or a number that changed and what changed it.

Entries are dated, short and factual. Every claim cross-references an ADR, a review record or a
commit, so a reader can check it. Nothing enters the paper from here without passing the gate in
`claims_register.md`: SOURCED with a citation, or MEASURED with a reproducible run.

Entries below M2 were backfilled on 2026-09-18 from the records named in each one.

---

## M0 — governance and the defect register

### 2026-09 · A specification target that was arithmetically impossible (D-01)

The SRS required "Precision at 1% FPR ≥ 0.720". At a 0.87% fraud base rate this cannot happen: at
FPR = 1% the false positives are about 0.99% of all transactions while true positives cannot exceed
0.87%, so precision is bounded by 0.0087 / (0.0087 + 0.01 × 0.9913) ≈ **0.467**, even with perfect
recall.

The gate metric became **Recall at 1% FPR ≥ 0.720**; precision at 1% FPR is still reported, next to
its theoretical ceiling for the test set's actual base rate, which `evaluate.py` computes and prints.

*Why it belongs in the paper:* a benchmark target can be infeasible rather than merely hard, and the
only way to find out is to compute the ceiling from the base rate before agreeing to it. Reference:
`docs/srs/defect_register.md` D-01.

### 2026-09 · Fifty-one defects found by reading the specification against itself

The register holds 51 entries, each with a resolution. They are not bugs found by testing; they are
contradictions, impossible targets and unsupported numbers found by reading. D-08 ("results on
synthetic data can look perfect and prove nothing") and D-09 (unsupported numeric claims) set the
agenda for the whole of M2.

Reference: `docs/srs/defect_register.md`.

---

## M2 — the dataset generator

### 2026-09-18 · Sourcing pass: seven of thirteen assumptions were wrong, two structurally

Owner direction: "0 of 79 parameters sourced" was the weakest point of M2. Parameters were ranked by
measured influence first (`docs/research/parameter_influence.md`), so effort went where it moved the
dataset, and then eight documents were downloaded and read in full.

Result: SOURCED 0 → 12, ASSUMED 65 → 55, total 79 → 81.

The assumed values were not merely imprecise. Transactions per active customer per month was assumed
14, sourced 8.8. Customers per agent: 150 assumed, 30 sourced. Customers per merchant: 40 assumed,
12 sourced. The person-to-person share of wallet payments: **0.6 assumed, 0.216 sourced** — a factor
of nearly three, on the parameter that turns out to drive the dataset's strongest transaction
feature. Cross-border share: 0.03 assumed, 0.005 sourced. Monthly growth: 0.03 assumed, 0.0201
sourced. School terms: the assumed May term actually begins in April.

Two changes went beyond a value and forced a design change:

1. The sourced population is **68% rural**. The old channel model gave one segment a fixed mix and
   let the others absorb the remainder of the SRS mix; with a rural majority that remainder pushed
   urban customers to a fifth of their payments on cards. Channel mixes are now *fitted* by
   iterative proportional fitting, so every segment has relative preferences and the dataset still
   meets the SRS mix.
2. The sourced urban share (0.32, census weighted by usage) now **constrains** the assumed segment
   split rather than sitting beside it: the generator refuses to run if the urban segments stop
   summing to it.

*Why it belongs in the paper:* this is the honest answer to "your parameters are invented". Some
were, and when they were checked most were wrong, two of them badly enough to change the model's
structure. Reference: `docs/research/sourcing_pass.md`.

### 2026-09-18 · An account-events table leaked the label (BLOCKER)

Fraud-planted `SIM_SWAP` and `DEVICE_CHANGE` events were written to the microsecond and on any day
of the month, while legitimate ones were quantised to whole seconds and confined to days 1–27. Any
event with a non-zero sub-second part identified a victim's account: **99.84%** of them sat on a
fraud account against a 41.96% base rate. The table ships in the release and the datasheet invites
joining it; no check read it.

Fixed by placing legitimate events exactly as planted ones are, and by adding an event-construction
gate that reads the table. Re-measured after the fix: base rate, oracle gone.

*Why it belongs in the paper:* the leak was in a *secondary table*, not the feature matrix. A
leakage audit that only reads the training columns would have passed it.

### 2026-09-18 · The exclusion that was justified with the wrong number

The event-construction gate deliberately excludes two channels as designed signal rather than
construction artefact: which kind of event it is, and how soon a transaction follows. The written
justification cited one figure, "0.537 on its own", for both.

0.537 is the **type** channel. The **delay** channel, never measured, separates the classes at
**0.758** at 1,006,249 rows — above `merchant_category_code` at 0.711, which the datasheet, the
walkthrough and the defence questions all named as the dataset's strongest single feature. Neither
excluded channel was judged by any check: the event gate reads only construction, and the
single-feature AUC gate reads transaction columns and never joins the events table. They fell
through the gap between two gates.

Both are now measured and reported on every run, still ungated. The claim was corrected everywhere
it appeared. Part of the 0.758 is an artefact of an assumed schedule —
`fraud.takeover_lead_minutes = [5, 60]`, provenance ASSUMED — so every enabling event is followed by
its drain inside a tight uniform window, which real SIM swaps do not guarantee.

Commits `bf682fe`, `d321e58`. Reference: `docs/reviews/M2/delta-recheck.md`.

### 2026-09-18 · A guard with two doors

MAJOR 4.1 introduced a parameter digest so a shipped report could not describe a superseded
parameter set. It hashed parameter *values* only. Adding two reported checks moved no parameter, so
the digest still matched, the guard stayed green, and the shipped report silently described a run
performing two fewer checks than the code.

A check-set digest now covers the check names, what each requires, and whether it gates. Measured
values are deliberately excluded — they differ legitimately between runs, and digesting them would
mark every report stale on arrival.

The same shape then appeared one level up: both digests guard exactly one file, the realism report.
The datasheet — the document that actually ships to a dataset user — had a temporal-split table
matching neither the current report nor the previous one, left over from a generator that no longer
existed, and wrong throughout the dataset review, the addendum and the delta re-check.

Commits `1e5d441`, `3f4ba41`.

*Why it belongs in the paper:* a guard is only as good as its reach, and the natural failure is not
that the guard is wrong but that it watches one artefact while the claim lives in another.

### 2026-09-18 · The shortcut detector's 30,000-row failure was a tail draw, not a leak

A clean 30,159-row dataset failed its own anti-leakage gate (AUC 0.446, band ±0.051). The instruction
was to diagnose rather than widen the band, which had already been widened once.

A sweep over scales and seeds, with deliberately planted leaks at three strengths, found: clean mean
AUC 0.500 at 10,000 rows and 0.508 at 30,000 — centred at both, where a real construction leak would
show a persistent displacement. The 0.446 is 1.77 standard deviations below the 30,000-row mean.

It also found that the gate's band is **under-covered**: band ÷ observed sd is 1.44–1.62 where a 95%
interval needs 1.96, predicting a 10–15% false-alarm rate against 7% and 14% observed. So
`CV_TREE_NULL_INFLATION = 1.3` is not conservative, contrary to what the fix that introduced it
claimed and what the delta re-check marked CLOSED. Reopened; see the entry below when it resolves.

*Why it belongs in the paper:* a detector can be correctly centred and still fail honest datasets at
three times its nominal rate, and the two properties have to be measured separately.

*Methodological caution:* the sweep's raw records were written to a session scratch directory and
lost when the session ended. The summary statistics survive in `docs/reviews/M2/milestone-review.md`
but cannot be re-derived without re-running. Evidence that will be cited belongs in the repository.

### 2026-09-18 · Below ~170,000 rows the dataset silently lacked a fraud scenario (MAJOR)

The capacity guard skipped any scenario whose role pool was empty, on the reasoning that the
eight-scenario check would catch it. That check is release-only (MAJOR 1.5 made it so, correctly, to
stop it blocking development runs). Together: below about 170,000 rows the mule pool is usually
empty, `mule_account` was absent, and nothing said so.

Two individually-correct decisions; the hole was in their interaction, which is why neither review
caught it.

It now refuses by default and names the size that would work, with three distinct messages for three
distinct causes (parameters unfixable at any size; run too small; size adequate but this seed drew
badly). A development run may waive it explicitly, and the scenarios that could not be staged are
recorded in `manifest.json` as `scenarios_not_staged`, so a deficient dataset identifies itself.

**Two derivations were attempted and rejected before the third worked**, and both failed the same
sanity check — they returned a minimum *below* a size that had already failed:

1. Treating the month-summed capacity as the random quantity. Wrong: one role holder supplies about
   24 customer-months, so the month-sum is a binomial count magnified 24-fold, not a count with its
   own mean's variance.
2. Solving `n ≥ 9b/(a−b)²` in closed form on per-row rates. Wrong: the requirement is **floored at
   one holder**, so below 300,000 rows it is not proportional to n and no closed form applies.

The third solves `E[holders](n) ≥ needed(n) + 3√needed(n)` numerically. It is pinned by a test
requiring the same answer from four different scales: 169,492 / 170,455 / 169,972 / 170,069.

*Why it belongs in the paper:* the minimum viable scale of a synthetic benchmark is a property worth
publishing, and it is not obtainable by reasoning alone — two plausible closed forms were wrong.

### 2026-09-18 · Method: establish the mechanism before assessing what a defect invalidates

When M-4 was found, the obvious move was to discard every figure measured below 170,000 rows. That
would have been wrong in both directions — throwing away sound evidence and keeping unsound
evidence — because it treats "measured on a deficient dataset" as a single category.

What worked instead was to derive the *mechanism* first. A scenario that cannot be staged has its
share reallocated to the scenarios that can (`_month_plan`), so the month's fraud target is still
met exactly. The deficiency is therefore a wrong scenario **mix**, not a wrong **rate**. Once that
is established, each figure can be judged by whether it depends on the mix:

- rate-like figures (overall fraud rate, channel mix, country mix) — unaffected;
- construction-like figures (the event-oracle probe, the plant-a-leak tests, the shortcut
  detector's centring) — unaffected, because identifiers and timestamps are built identically
  whatever scenario produced a row;
- composition-like figures (scenario mix, the MCC lift, detector *power*) — affected, since they
  depend on which rows carry the positive label.

That third category included a claim of our own: the single-feature AUC stability series, 0.753 at
12,033 rows and 0.776 at 60,000 against 0.711 at 1,006,249. The first two points come from
seven-scenario datasets and the third from eight, so the series is not one property measured at
three sizes. Part of its non-monotonicity may be composition rather than sampling, which makes the
"no longer a function of sample size" claim weaker than it was already known to be.

*Why it belongs in the paper:* the discussion should state this as a rule rather than an anecdote.
A defect's blast radius is not the set of results that touched the defective component; it is the
set of results that depend on the property the defect actually changed, and those two sets are
rarely the same. Deriving the mechanism first is what separates them.

### 2026-09-18 · Standing rule: every quoted figure states its scale

Adopted after the above. Any number offered as evidence must say what scale it was measured at, and
whether that scale is at or above the 170,000-row minimum at which the shipped parameters stage all
eight scenarios. A figure from a smaller run is not thereby wrong — most are fine, per the mechanism
above — but the reader cannot tell which category it falls into without the scale, and the author
cannot either.

### 2026-09-18 · An evidence run owns the tree *and* the environment for its duration

Already recorded as a rule about the tree: never edit source while a run you intend to cite is in
flight, because pytest imports at collection and the run then reflects a tree that no longer exists.
That rule was necessary and not sufficient.

While a suite was running, `uv sync --reinstall` was used to register a new console script. It
removed the workspace packages **out from under the running pytest**, which terminated the suite and
voided the run. A second run was then lost to a timeout that was too short for a loaded machine. The
first of those is the instructive one: nothing in the source tree changed, and the run still died,
because the interpreter's view of its dependencies changed underneath it.

The rule is therefore: **an evidence run owns the tree and the environment for its duration.**
Anything needing either — an edit, a dependency change, an environment repair — waits, or kills the
run first and restarts it afterwards. "Be careful" is not the lesson; ownership is, because the
failure is invisible from inside the run and the result can look like an ordinary failure rather
than a void one.

*Why it belongs in the paper:* reproducibility statements usually pin the tree — a commit SHA, a
lockfile — and say nothing about who may mutate the environment while a measurement is being taken.
A result measured against a mutating environment is not reproducible even when the commit and the
lockfile are both recorded, and the failure mode does not announce itself.

*Practical corollary, learned the same day:* a timeout on an evidence run must be sized for a loaded
machine, not an idle one. A suite that takes 13 minutes idle took over 33 under a load average of 7,
and the timeout that killed it produced an exit code indistinguishable at a glance from a real
failure.

### 2026-09-18 17:5x · PREDICTION, recorded before the above-minimum tiers land

Sweep commit `a761eb3`; at the time of writing the 10,000 / 30,000 / 60,000-row tiers are complete
(15 seeds each) and 100,000 upward have not run. Recorded now so that it can be checked rather than
rationalised afterwards.

**Predicted:** power against the 0.6-strength plant will *not* simply carry over from the
seven-scenario tiers to the eight-scenario ones, and the fire rate at 200,000 rows may sit below the
15/15 seen at 60,000 despite the larger sample.

**Reasoning:** the plant marks the first identifier byte on *fraud* rows, so the detector's task is
to find a signal carried by the positive class. Restoring `mule_account` above 170,000 rows changes
both the size and the makeup of that class — mule incidents are longer and their rows differ in
channel and counterparty from the scenarios whose shares were inflated to cover the gap. The
detector's power is therefore a property of the positive class's composition, not of `n` alone.

**How it will be judged:** fire rate on the 0.6 plant at 200,000 / 300,000 / 500,000 / 1,000,000
rows, against 15/15 at 60,000. Monotone non-decreasing would refute the prediction. A dip at
200,000 that recovers by 1,000,000 would support it.

**Either outcome is a result.** If power depends on composition, a leakage detector's power figure
is not transferable between datasets with different scenario mixes, which is worth stating.

### 2026-09-18 · The band stops being sized to its own null exactly where it matters most

Found while verifying the assumption behind the inflation derivation, and larger than the thing it
was checking. The shortcut band is

    max(SHORTCUT_TOLERANCE, cv_auc_null_band(positives, negatives, z))   # 0.03, z = 1.96
    cv_auc_null_band = z * CV_TREE_NULL_INFLATION * null_auc_stderr      # 1.3

so the analytic term only sets the band while it exceeds 0.03. Measured bands across the sweep:
0.088 at 10,000 rows, 0.050 at 30,000, 0.036 at 60,000, **0.030 at 100,000** — the floor.

Above roughly 60,000–100,000 rows the gate is the flat tolerance, not a band sized to the
statistic's own null. At the 1,006,249-row verification run the detector sees 48,597 rows with about
8,700 positives, whose analytic band is **0.011** under the corrected inflation; the floor is
**2.8× wider**. So at release scale the gate tolerated a deviation nearly three times larger than
the statistic's null spread implies, and was blind to any construction leak landing between roughly
0.509 and 0.530.

(An earlier draft of this entry said 3.4×. That was computed with the superseded inflation of 1.3,
which the same investigation replaced with 1.62; against the corrected value the factor is 2.8×.
The argument and its direction are unchanged.)

This inverts the problem at the two ends. Below 60,000 rows the band is too *narrow* relative to the
observed spread (`band ÷ sd` ≈ 1.5 where 1.96 is needed), so clean datasets fail about one run in
nine. Above 100,000 it is too *wide*, so real leaks can pass. MAJOR 1.3/1.4 replaced a fixed 0.03
with a null-sized band precisely to stop a fixed number being wrong at every scale — and kept the
fixed number as a floor, which reintroduces the defect at the scales that ship.

*Consequence for the inflation derivation:* only runs whose band exceeds the floor carry information
about the inflation factor, so the fit uses the three dense tiers (45 clean runs) and cannot use the
larger scales at all. That is a limitation of the data, not a choice.

*Why it belongs in the paper:* a tolerance that is the maximum of a principled quantity and a
convenient constant is two different tests at two different scales, and the switch-over is silent.

### 2026-09-18 19:5x · PREDICTION: the shortcut detector's positive offset is real

Sweep v2 commit `be2d7b0`; no above-minimum tier has produced a record yet. Recorded before the
measurement, like the composition prediction above.

**Predicted:** the clean mean AUC sits above 0.5 at every scale above the minimum, the one-sided
test rejects zero offset at 300,000 rows and above, and the effect is a roughly constant AUC offset
of about **+0.005 to +0.010** rather than something that shrinks with n.

**Reasoning — the standardised deviations are not flat.** Writing `t = (AUC − 0.5) / SE` with
`SE = band / (1.96 × 1.62)`:

| rows | mean t | implied offset |
|---:|---:|---:|
| 10,000 | −0.01 | ~0 |
| 30,000 | +0.31 | +0.006 |
| 60,000 | +0.37 | +0.005 |
| 1,006,249 (single run) | **+2.89** | **+0.010** |

A *fixed* AUC offset produces `t` growing like `√n`, because SE shrinks while the offset does not.
From 60,000 to 1,006,249 rows is 16.7× the data, so √n predicts about 4.1× the t; observed is 7.8×.
Same order, and far from the flat line that pure noise would give. Sampling noise would leave the
implied offset scattering about zero rather than landing between +0.005 and +0.010 four times.

The single-run t of +2.89 is what a floored band of 0.03 could never have revealed: it passed at
0.010 against 0.030 with room to spare, and passes at 0.010 against 0.011 only just.

**Mechanism hypothesis, to be tested by ablation:** `row_position_in_file`. Fraud arrives as
incidents — short bursts of rows sharing an account and a time — while legitimate rows are spread
across the month, and rows are written in timestamp order. Anything that makes fraud intensity vary
*within* a month (month-end salary and school-fee peaks are in the generator by design) leaves
position within the file weakly predictive of the label. The other candidates are weaker: identifier
bytes are UUID-uniform, the label delay is drawn independently of the label by construction, and the
sub-second part was equalised when the BLOCKER was fixed.

**How it will be judged:** pooled clean AUCs per tier at 200,000 / 300,000 / 500,000 / 1,000,000
rows, one-sided against 0.5 with bootstrap CIs. Intervals excluding zero at the larger tiers confirm
it; intervals straddling zero, or an implied offset shrinking with n, refute it.

**If confirmed, the response is to find the cause, not to move the threshold.** Ablate the detector's
columns one at a time, identify which carries the signal, and trace it to the construction code. A
generator with a small, quantified, disclosed construction signal is more credible than one claiming
a perfect 0.500, so the outcome is a limitation to state with its size — not a failure to hide.

### 2026-09-19 · THE central methodological error of M2: an analytic null where the empirical spread applied

The most valuable thing found in this milestone, and it was our own mistake.

A single 1,006,249-row run read a shortcut-detector AUC of 0.510. Standardising it as
`t = (0.510 − 0.5) / SE` with the **analytic** null standard error of 0.00346 gave **t = +2.89** —
nearly three sigma — and a hypothesis was built on it: a real, small, persistent construction
signal, predicted to reject at 300,000 rows and above.

Five seeds at 1,000,000 rows gave `0.5096, 0.4916, 0.5070, 0.5119, 0.5044`. The empirical
seed-to-seed standard deviation is **0.0079**, more than twice the analytic 0.00346. Against the
denominator that actually applies, 0.510 is the second-highest of five ordinary readings. Pooled
across all tiers above the scenario minimum: n = 24, mean 0.5031, bootstrap 95% CI
[0.4992, 0.5070] — straddling 0.5. The prediction was refuted.

**The error was using a theoretical null where an operational spread was the right denominator.**
The analytic SE describes the variability of the statistic on one fixed dataset. What varies between
releases is the dataset itself, and that spread is larger. Every run is a new draw of the data, not
a new draw of the estimator on fixed data.

**The same error, one level down, is what the band floor investigation had just corrected.**
`CV_TREE_NULL_INFLATION` existed precisely because a cross-validated tree is noisier than the
analytic null implies, and it was raised from 1.3 to 1.62 by measuring the spread across clean
datasets rather than trusting a Monte Carlo over a fixed one. Having made that correction, the same
analytic denominator was then used to standardise a single reading, in the same investigation,
within the same hour.

*Why it belongs in the paper:* this is the discipline that separates the two, stated as a rule —
**a single three-sigma reading is not evidence; repeated seeds are.** It generalises to every result
this project will report. Any figure standardised against a theoretical null must say so, and any
claim of an effect must rest on repeated draws of whatever actually varies in deployment. A result
quoted with the wrong denominator is not conservative or aggressive, it is simply unmeasured.

### 2026-09-19 · Two recorded predictions, both refuted by measurement

Both were written down before their tiers ran, with reasoning and a stated test. Both were wrong.

**Composition-dependent power.** Predicted: the detector's power against a 0.6-strength plant would
not carry over from seven-scenario to eight-scenario datasets, possibly dipping at 200,000 rows,
because restoring `mule_account` changes the size and makeup of the positive class the detector must
find. Measured: power rose monotonically and fired on every planted leak from 60,000 rows upward —
mean AUC 0.5781 / 0.5886 / 0.5890 / 0.5975 at 60K / 100K / 200K / 300K, 100% firing throughout. No
dip, not even directionally.

**A real positive offset.** Predicted above; refuted above.

*Why both belong in the paper:* two falsifiable hypotheses, each recorded with its mechanism and its
test before the data existed, each refuted by the data. The method worked exactly as intended — the
value was in being able to be wrong cheaply and visibly, rather than in being right. Predictions
written after the measurement cannot do that, which is why they were timestamped and committed
first.

### 2026-09-19 · The inflation factor: grounded where measured, extrapolated above

`CV_TREE_NULL_INFLATION = 1.62` is derived from 45 clean datasets at 10,000 / 30,000 / 60,000 rows,
where 15 seeds per scale give estimates of 1.57 / 1.73 / 1.63 with overlapping intervals — a
constant is the right form over that range, and leave-one-scale-out coverage at the fitted value is
100 / 93 / 100%.

Above 60,000 rows it is an extrapolation, and the sweep cannot settle it: with 4 to 10 seeds the
per-scale estimates scatter from 0.84 to 2.32 with bootstrap intervals spanning almost that whole
range. Estimating a standard deviation from five seeds carries about ±35% relative error. Settling
whether `k` drifts with `n` needs roughly **50 seeds per scale** (±10%), which at 1,000,000 rows is
about eight hours of compute. Recorded as future work rather than guessed at.

### 2026-09-19 · The pattern: a fix that keeps the old behaviour as a floor reinstates the defect it replaced

Three instances in one milestone, each a correct fix that stopped one step short of the structural
form of the same problem. Recorded together because the recurrence, not any single case, is the
finding.

| # | Defect | Fix applied | Structural form left untouched |
|---|---|---|---|
| 1 | A shipped report described superseded parameters | digest the parameter **values** | the **check set** was not digested, so adding a check left the report stale and green |
| 2 | A fixed 0.03 tolerance was wrong at every scale | size the band to the statistic's **own null** | the fixed tolerance was kept as a **floor**, so above ~100,000 rows the gate reverted to it and became 2.8x too permissive |
| 3 | A row's own label scored that row | compute category rates **out of fold** | folds were assigned **per row**, so the other rows of the same fraud incident stayed in the estimate that scored it |

In each case the obvious form of the defect — the individual value, the individual row — was
addressed, and the aggregate form — the set, the regime, the group — was not. Instance 2 is the
sharpest: the floor was inert exactly where it was meant to protect (small samples, where the
analytic term is larger anyway) and active exactly where it did harm (large samples, where it
replaced a tight null with a loose constant). A fix that retains the old behaviour as a fallback
does not reduce to the old behaviour rarely; it reduces to it in whichever regime the fallback binds,
and that regime is usually not the one the author was thinking about.

None of the three was found by the guard it concerned. Instance 1 was found by a delta re-check
looking at something else, instance 2 by verifying an assumption inside an unrelated derivation, and
instance 3 by asking what mechanism could explain a residual that instance 2's correction had
exposed. A guard cannot audit itself, and the defects lived in the seam between two guards that were
each individually right.

**The testable habit this leaves:** for every guard, ask what the grouped, floored or aggregated
version of the same defect looks like. It becomes an M3 exit criterion rather than a note — any
target encoding, fold assignment, sampling or split in the feature pipeline must be account-grouped
and time-respecting, with a test that fails if it is not.

### 2026-09-19 · Out-of-fold by row is not out-of-fold by incident

`_category_rates` assigned folds with `rng.integers(0, folds, codes.size)` — one draw per row. Fraud
arrives as incidents: several rows sharing an account and a time. So an incident's rows landed in
different folds, and the "out of fold" category rate used to score a row still contained that row's
own siblings. The shortcut detector had grouped its folds by account since MAJOR 1.2, for exactly
this reason; the encoding never did.

Measured at seed 20260917, per-row folds against account-grouped folds:

| feature | 300,000 rows | 1,006,249 rows |
|---|---|---|
| `merchant_category_code` | 0.7107 → 0.7064 (−0.0043) | 0.7106 → 0.7060 (−0.0046) |
| `channel` | 0.5897 → 0.5803 (−0.0094) | 0.5973 → 0.5915 (−0.0057) |
| `currency` | 0.5353 → 0.5054 (−0.0299) | 0.5093 → 0.5086 (−0.0007) |

Every single-feature AUC reported before this was inflated by roughly that much. The headline figure
is restated 0.711 → **0.706**, still inside the 0.80 D-08 ceiling.

**The mechanism was confirmed as a source of inflation and refuted as the explanation for the size
dependence**, which was the reason it was investigated. If sibling leakage drove the 300,000-versus-
1,000,000 gap, grouping would remove more at the smaller scale. It removes the same at both, and for
the one seed measured at both there is no gap at all (0.7107 against 0.7106). The gap lives in the
seed ensemble: ten seeds at 300,000 span 0.7107–0.7298 while five at 1,000,000 cluster 0.7091–0.7139.
Smaller runs read both higher and far more variable, and grouping does not touch that. **The residual
size dependence is unexplained** and is recorded as such rather than attributed to a second mechanism
invented to cover it.

That matters past this milestone: M3's engineered features use the same encoding, so M4 evaluation
must control for dataset size when comparing feature sets, not only for the leakage the fix removes.

*Also worth recording:* while reconciling the probe against the sweep, a single seed's 0.7107 was
briefly treated as disagreeing with the sweep's ten-seed **mean** of 0.7193. They agree exactly —
that seed is simply the lowest of the ten. Same family of error as the t-denominator mistake above:
comparing quantities whose populations differ.

### 2026-09-19 · OPEN QUESTION carried out of M2: single-feature AUC depends on scale, and we do not know why

The one scientific question M2 leaves unanswered. Recorded rather than investigated further, on the
owner's direction to close the milestone.

**What is measured.** Ten seeds at 300,000 rows give a seed-to-seed standard deviation of 0.0055 for
the maximum single-feature AUC (`merchant_category_code` is the argmax in all ten). Across scales the
figure ranges 0.065 — **twelve times** the seed-level spread — so the variation is not seed noise.
Ten seeds at 300,000 span 0.7107–0.7298; five at 1,000,000 cluster 0.7091–0.7139. Smaller runs read
both higher and markedly more variable.

**What it is not.** It is not the fold-grouping defect. That defect is real and was fixed, but
account-grouped folds remove the same amount at both scales — 0.0043 at 300,000 and 0.0046 at
1,000,000 — and for the one seed measured at both there is no gap at all (0.7107 against 0.7106). A
mechanism that inflated small runs preferentially would have removed more at 300,000. Sibling
leakage does not account for it.

**What would settle it.** Candidates that can be separated by experiment:

1. *Per-category sample size.* Out-of-fold rate estimates for each merchant category are noisier when
   fewer rows carry each category. Test by holding the number of rows per category fixed while
   varying total rows — subsample large runs down to a small run's per-category counts and see
   whether the AUC rises to match.
2. *Incident concentration.* At smaller n a category's fraud rows come from fewer, larger incidents,
   so its realised lift is a draw from a wider distribution. Test by measuring the AUC against the
   number of distinct fraud incidents rather than rows.
3. *Fold count interacting with n.* Five folds is fixed while rows per fold change with n. Test by
   varying folds with n held constant, and n with folds scaled to keep rows per fold constant.

Each is a few hours of compute and none needs new generator code.

**How it is contained meanwhile.** M3 exit criterion E2: every metric depending on target encoding is
reported at a fixed, stated scale, and comparisons between feature sets, models or ablations use the
same dataset size. A metric quoted without its scale is not admissible as evidence. That does not
explain the effect; it stops it being mistaken for one.

*Why it belongs in the paper:* a benchmark whose headline leakage statistic moves with dataset size
by more than its own seed noise is a property of the benchmark worth publishing, whether or not the
cause is found. Reporting it as an unexplained limitation with a stated containment is more useful
than a clean number that quietly depends on how much data was generated.

### 2026-09-19 · The fourth instance: a generated check refused a tag six review passes had approved

M2 was ready to tag. A principal dataset review had run and been answered, a delta re-check had
verified the fixes, a four-area milestone review had found and fixed four MAJORs, the full suite was
green at 106 tests, and the owner had said to tag.

`fs-traceability check` refused. Four **Must** requirements the register assigns to M2 had not
reached a final status: `ML-DATA-07` (all 44 features computable), `RES-01` (5M+ transactions and 44
features), `RES-02` (the datasheet) and `ML-DATA-08` (HuggingFace and Zenodo publication).

On inspection the refusal was substantially right. `RES-02` was genuine bookkeeping staleness — the
datasheet had existed for days. But `ML-DATA-07` asks for features that are M3's subject and do not
exist; `RES-01` carries a 5,000,000-row clause blocked on the default branch and the same feature
clause; `ML-DATA-08` needs the owner's accounts on two external platforms. None of the six review
passes had noticed, because all six were reading code, measurements and prose — and none was reading
the register row by row against the milestone it claimed to close.

This is the fourth instance of the day's pattern, and the first where the check that caught it was
not one a human was performing:

| # | Found by | Missed by |
|---|---|---|
| 1 | a delta re-check looking at something else | the guard that digested parameters |
| 2 | verifying an assumption inside an unrelated derivation | the gate whose band it was |
| 3 | asking what mechanism explained a residual | the encoding's own out-of-fold fix |
| 4 | a generated governance check | six review passes, including this one |

The resolution is ADR 0024: `ML-DATA-07` moves to M3, because the build prompt's own milestone
definitions put feature engineering there and M2's gate never mentions features — a requirement
filed under the wrong milestone, the same family as D-01. `RES-01` stays in M2 at
`VERIFIED_AT_REDUCED_SCALE` with its two unsatisfied clauses named. `ML-DATA-08` takes
`REQUIRES_EXTERNAL_PARTY` rather than a milestone, because publication is not a scheduling problem.
`RES-02` becomes `DONE`. The hook was not bypassed; the gate passes on its own.

*Why it belongs in the paper:* the argument for machine-checkable governance is usually made about
consistency or speed. This is the stronger version — the check caught what careful reading did not,
because it was asking a different question. Reviews ask "is this right?"; the register asks "does
every row you claimed still hold?" Those are not the same question, and a project that only does the
first will close milestones on requirements it has not met.

*The uncomfortable corollary:* the register only caught it because the rows were there to check. A
requirement never entered cannot be missed by any amount of review.

---

## M3 — feature engineering

### 2026-09-19 · The pattern caught before the code, for the first time

M2 found the same defect shape three times: a fix addressing the individual case and stopping short
of the aggregate one. A digest over parameter *values* but not the check *set*. A null-sized band
that kept the old fixed tolerance as a *floor*. An out-of-fold encoding that folded per *row* rather
than per *incident*. Each was found after the code was written, and each invalidated results already
reported.

The fourth instance was caught before a line of the code it concerns exists.

Part E.2 specifies the training/serving skew test as "identical vectors to 1e-9". Checking that
number against the feature catalogue's actual magnitudes, before writing any feature:

| Feature | Magnitude | One ulp | `1e-9` in ulps |
|---|---:|---:|---|
| `round_sum_flag` | 1 | 2.2e-16 | ~4,500,000 — no constraint |
| `amount_sum_7d` | 1e6 | 1.2e-10 | ~9 |
| `amount_sum_7d`, heavy user | 1e7 | 1.9e-9 | **< 1 — unsatisfiable** |

A single absolute tolerance across features spanning 0/1 flags and multi-million-RWF sums means
nothing at one end and is below float64's resolution at the other. That is exactly
`max(0.03, z × k × SE)` again: one constant spanning regimes where it means different things.

Replaced (ADR 0025) by exact equality for counts, flags, ordinals and categoricals; a relative
tolerance with an absolute floor for real-valued features; and exact NaN positions, since structural
NaN is the D-04 contract rather than a numerical detail.

**Why this one is the interesting entry.** The first three cost restatements of published numbers.
This one cost an afternoon's arithmetic before the first feature was written, because the M2 habit —
*for every guard, ask what the grouped, floored or aggregated version of the same defect looks like*
— was applied to a specification rather than to code. The habit was recorded as an M3 exit criterion
in the expectation that it would catch something during M3; it caught something before M3 started.

*Why it belongs in the paper:* the claim is not that this project found four defects, it is that the
fourth was cheap because the first three were expensive and were written down. That is the argument
for a lab notebook being part of the method rather than a record of it.

*Also settled while the design was open, and worth stating because both are easy to get wrong:* a
parity test is blind to any bug in code the two paths share, so independence is enforced by an
import-graph test and the shared surface is limited to a declarative registry plus an explicit list
of primitives that carry their own hand-computed tests. And the mutation suite includes a control
that must *pass* — an honest reassociation of a window sum — because without a case that should not
fire, a tolerance that rejects everything looks identical to one that works. That is the 0.5-strength
plant from M2's power curve, in a new place.

### 2026-09-19 · A test whose fixture lacks the condition it tests passes vacuously, and reading cannot detect it

Twice in one session, and the second time in the same session the first was written down.

**Instance one.** The test pinning that categorical folds group by account constructed 40 accounts
with `code = account % 4` and `fraud = account % 2 == 0`. Every category therefore came out *purely*
fraud or *purely* legitimate, so each category's rate was 0 or 1 whichever way the folds were drawn,
and grouped and per-row folding produced identical scores. The test asserted they differed; it
failed, which is how it was noticed — but had it been written the other way round, asserting they
agreed, it would have passed forever while testing nothing.

**Instance two.** The test pinning that partition-key drift is bounded by the largest UTC offset ran
on the shared 6,000-row fixture at seed 11. Seed 11 produces **zero** drifting rows. The assertion
`earliest >= start - max_offset` therefore held over an empty set. It was found by mutating the
permitted drift to zero — claiming no drift is allowed at all — and watching the test still pass.
Seed 13 produces 7 drifting rows; on that fixture the same mutation fails with
"2024-06 reaches 1.4h before its start, beyond the 3.0h maximum UTC offset".

**What the two have in common is not carelessness.** Both tests were correct as written. Both would
survive review by reading, because nothing in the test's text is wrong — the defect is in the
*fixture*, one level away, and the assertion is simply never reached in a state that could fail. A
reviewer checking the assertion sees an assertion that would catch the bug if the data reached it.

**Two mechanisms, one cheap and one expensive.** Mutation testing finds these reliably but costs a
run per mutation and is easy to skip. A **precondition assertion** — `assert drifting > 0` before
bounding the drift, `assert mixed_categories` before checking the encoding — costs nothing, runs
every time, and converts a vacuous pass into a loud failure naming what the fixture is missing. It
is now M3 exit criterion E12, with E13 requiring each of the 44 feature tests to exercise its
feature non-trivially and E14 requiring mutation results to be recorded as they are produced rather
than assembled at the end.

*Why it belongs in the paper:* this is the same recurrence structure as the guard/floor/folds family,
in a different dimension. There the fix addressed the individual case and missed the aggregate; here
the test asserts the right property over data that cannot exhibit it. In both, the artefact is
correct in isolation and wrong in context, and in both, reading is the wrong instrument — the first
needed the question "what does the aggregated version look like?", the second needs "does the
fixture contain the case?". A methodology section that lists tests written is weaker than one that
states how the tests were shown capable of failing.

*The uncomfortable part:* instance two happened after instance one had been analysed, written up and
turned into an exit criterion, by the same author, in the same session. Knowing the failure mode did
not prevent repeating it. That is the argument for the structural fix — a precondition the test
cannot omit — over the habit of remembering.

### 2026-09-19 · An evidence run's inputs can change from outside the run, and the run cannot tell

Three broken runs in one session, three different causes, one shared property.

| # | What changed | From where | How the run failed |
|---|---|---|---|
| 1 | `realism/checks.py`, edited mid-run | the operator | the suite reported on a tree that no longer existed; the result read as ordinary green |
| 2 | the installed workspace packages, via `uv sync --reinstall` | the operator | pytest died with its dependencies removed underneath it; no source file changed |
| 3 | `geography.yaml`, edited by a test in the suite | **inside the suite being measured** | a `finally` did not run when the body raised, leaving a country share naming a pack that did not exist |

The first two are about operator discipline and were already covered by "an evidence run owns the
tree and the environment". **The third is the one that makes the rule insufficient**, because the
mutation came from inside the run. No care about what happens outside the suite would have prevented
it, and the damage was invisible from within: the suite simply began failing for a reason unrelated
to what it was measuring.

The test in question was the Country Z portability test. It edited the repository's parameter tree
to add a synthetic country, and restored it in a `finally`. `finally` is the wrong instrument twice
over: it does not run if the process is killed, and when the body raises inside something later
tests depend on, it runs too late to help them. Copying the tree into `tmp_path` has no cleanup path
to get wrong — there is nothing to restore.

The structural fix is M3 exit criterion E15: no test writes outside `tmp_path`, shared state is
copied rather than restored, and a session-scoped fixture hashes the parameter and configuration
trees before and after the suite, failing with the name of whatever changed. That fixture catches
the case a `finally` cannot, which is the case that actually occurred.

*Why it belongs in the paper:* reproducibility statements pin the code and the environment, and
treat the test suite as an observer of the system rather than a participant in it. A suite that can
modify the inputs it is measuring is neither — and the failure does not announce itself as
contamination, it announces itself as an unrelated test failure, which is how it survives review.

### 2026-09-19 · Writing the hostile case before the feature: Country Z found a defect on its first run

ADR 0023's acceptance criterion is behavioural rather than a lint: *"a synthetic Country Z pack,
entirely assumed, is added in CI, and the generator, the feature pipeline and the UI must work for it
with zero code changes. If adding a pack requires touching code, this decision has been violated."*

It was violated, on the first run, and the test said so: generation died with `KeyError('ZZZ')`
because the FX rate lived in a shared `currencies.rwf_per_unit` table rather than in the pack. ADR
0023 enumerated what a pack carries — currency, minor units, timezones, population, density, channel
mix, languages, holidays, KYC tiers, phone formats, blocs — and **omitted the exchange rate**. The
omission was invisible in the ADR's own prose and obvious the moment a country existed that nothing
else knew about.

The pack is deliberately hostile: an invented currency, three minor units where every real currency
in the dataset has zero or two, a +7 offset far outside the +2..+3 range of the simulated countries,
and a bloc of one sharing membership with nobody. Every value is chosen so that a hard-coded East
African assumption fails rather than accidentally passes — the same reasoning as the 0.5-strength
plant in M2's power curve, which exists so that "the gate fired" can be distinguished from "the gate
fires at anything".

*Why it belongs in the paper:* the generalisability of a synthetic benchmark is usually argued from
its design. This is the stronger form — a case the design says must work, run in CI, failing loudly
when it does not. It cost one test and found an incomplete specification immediately.

### 2026-09-19 · A contract fitted to one example is not a contract: the second feature found three gaps

The feature registry exists because writing the first windowed feature exposed questions Part E.2
does not answer — whether the scored transaction counts toward its own window, whether a 1 h
numerator is also inside its own 30 d denominator, what a zero-history account returns, whether the
denominator divides by elapsed time or observed history, whether that state must survive a cache
flush, and what the feature emits when the online store is down. Each is a fork where two
independent implementations can disagree while both look correct in review. Making them **declared
fields that validation refuses to leave blank** closes the fork once, before either path is written.

Six fields, derived from `velocity_ratio_1h_vs_30d`. The schema looked complete. Registering the
**second** feature, `geo_cell_fraud_rate_30d`, broke it in three places within minutes:

1. **`Smoothing` had `alpha` and `placement` but no `prior`.** A ratio smooths toward 1.0; a rate
   smooths toward the base rate. With no prior field the "shrink toward 1.0" was not a declared
   value but an assumption baked into the arithmetic — and applied to a fraud rate it would make
   every unseen H3 cell read as certain fraud.
2. **`history_basis` had no value for a proportion.** Its two values, `OBSERVED_CAPPED` and
   `ASSUMED_FULL`, both answer "what does the denominator divide by *in time*". A cell's fraud
   proportion divides by a count of transactions seen, so the question does not apply. It needed
   `NOT_TIME_NORMALISED` — the same escape hatch `Nesting` already had as `NOT_NESTED`, which I had
   written for the first feature and failed to generalise from.
3. **Nothing declared label availability.** E.2's rule is unambiguous — only labels whose
   `label_available_at` precedes the transaction — and the fork is still real, because the two paths
   are asymmetric by construction: the online path *cannot* see an unarrived label, the batch path
   *can* and will unless stopped. A batch implementation filtering on `confirmed_at` instead looks
   correct and leaks the investigation delay straight into the feature. That became `label_basis`.

**The lesson is not "seven fields rather than six".** It is that a schema extracted from a single
example encodes that example's assumptions as structure, invisibly, and no amount of reading detects
it — the gaps were obvious the instant a second, differently-shaped feature had to fill the fields
in, and not before.

This is the same result as Country Z, in a different medium. ADR 0023 enumerated what a country pack
carries and omitted the exchange rate; the omission was invisible in the ADR's prose and immediate
the moment a country existed that nothing else knew about. Here the omission was invisible in a
schema with full test coverage and immediate the moment a feature existed that the schema had not
been fitted to.

*Why it belongs in the paper:* the methods section will claim the feature contract prevents
training/serving skew by construction. The honest version of that claim has to say how the contract
was arrived at, and "we wrote it against one feature and the second one broke it three times" is
both true and the reason to trust the seventh field more than the sixth. The generalisable claim is
the procedure — **derive a contract from one case, then immediately attack it with the most
differently-shaped case available, before the contract has any dependents.** Two features cost
minutes. Forty-four would have cost a schema migration across all of them.

**Still untested:** the contract was attacked with two features, not forty-four. Features drawing on
counterparty aggregates, device sharing and the agent float ratio may each break it again. The
prediction, recorded before writing them: **`counterparty_unique_senders_24h` will need an eighth
field**, because its window is keyed by the *counterparty*, not the account, and every field in the
contract silently assumes the account is the unit of history.

### 2026-09-19 · PREDICTION OUTCOME: confirmed in substance, wrong in sequence — and it found a live defect

**The prediction, recorded before the remaining 42 were written:** `counterparty_unique_senders_24h`
will force an eighth contract field, because its window is keyed by the *counterparty* while every
field then in the contract silently assumed the account is the unit of history.

**Outcome: the mechanism was right and the bookkeeping was wrong.** Two fields arrived, not one, and
the first came from somewhere I had not predicted:

- **Field 8, `minimum_history`, arrived from the amount group**, not the counterparty group.
  `amount_zscore_90d` is specified as "robust: median/MAD; NaN->0 with < 5 history" — a sentence
  containing three forks and settling none: whether 5 counts transactions or days, whether the
  scored transaction is one of them, and whether the output below the threshold is `0.0` or NaN (the
  arrow reads as "NaN becomes 0" to one implementer and "emit NaN, or 0" to another). It is a
  distinct mechanism from smoothing and cannot be folded into it: smoothing degrades gracefully
  toward a prior and is defined at zero evidence, while a minimum-history rule is a hard cliff
  refusing an estimate the statistic cannot support. A MAD over four points is not imprecise, it is
  meaningless.
- **Field 9, `history_key`, arrived exactly as predicted**, from `counterparty_unique_senders_24h`,
  for exactly the stated reason.

**So the prediction is confirmed on mechanism and refuted on sequence.** I predicted the next field;
what I got was the field after next. Recording the distinction rather than claiming a hit, because
"the unit of history is an undeclared assumption" was the falsifiable content and "it surfaces from
this particular feature" was not the part that mattered.

**And then field 9 found a defect in a feature registered two hours earlier.**

`history_key` is E1 at one level up. E1 requires every fold, split, sample and target encoding to
group by **account**, because fraud arrives as incidents sharing an account. That rule assumes the
thing a feature aggregates over *is* the account. `counterparty_unique_senders_24h` aggregates over
the counterparty: a ring moving money from ten victim accounts into one mule produces ten rows whose
feature values come from one counterparty-keyed object. Split those accounts across folds and the
validation rows sit inside the feature the training rows saw — **cross-account leakage that
account-grouped folds are blind to by construction, because the leak does not travel through the
account.**

The moment that was written down it was obvious that `geo_cell_fraud_rate_30d`, registered earlier
the same session, has the same property: an H3 cell aggregates across accounts. Its leakage note
claimed *"the prior is fitted on training folds grouped by account (E1), so no validation label
reaches any cell estimate."* **That sentence is false.** Account-grouped folds do not stop a
validation account's transactions entering a cell's rate. The note asserted a protection the design
did not have, in the feature I had myself described as "the one most able to leak", in a session
whose whole subject was that kind of omission.

**This is the M-8 shape for the fifth time**, and the first four are in this notebook under "The
pattern": a fix that is correct for the case in front of it and stops short of the structural form.
M-8 folded per row when the unit was the incident. This folds per account when the unit is the
counterparty or the cell. Same defect, one level up, found the same way — by asking what the
grouping is actually grouping.

**What changed as a result, so this is structural and not a correction:** `history_key` is a required
field, and a contract whose key is not `ACCOUNT` is **refused at construction** unless it also
carries `cross_account_control` naming what makes it fold-safe. There is no default, because there
is no safe default. `geo_cell_fraud_rate_30d` now states that every cell estimate *and* the fitted
prior are computed from training-fold rows only, and names the mutation that would detect the leak —
computing cell rates over all rows and asserting the single-feature AUC rises, which is its only
observable signature.

*Why it belongs in the paper:* the anti-leakage section will claim account-grouped folds as the
defence. The honest claim is narrower — **account grouping defends only features whose unit of
history is the account** — and the benchmark contains features for which it is not. Stating the
limit is worth more than the guarantee, and a reviewer who knows the M-8 story will ask this exact
question.

**Next prediction, recorded now:** the device group will need `history_key=DEVICE` and will find that
`accounts_per_device_7d` is *defined* by cross-account aggregation — a device shared by many
accounts is the entire signal — so it cannot be made fold-safe by restricting to training rows
without destroying the feature. If that holds, it is the first feature where the contract's
requirements and the feature's purpose are in genuine conflict, and it needs an owner decision
rather than an implementation.

### 2026-09-19 · The fifth instance is not the fourth: an asserted protection is worse than a missing one

The pattern in this notebook — a fix correct for the case in front of it, stopping short of the
structural form — now has five instances. **The fifth is a different failure mode and is recorded
separately, because treating it as more of the same would lose what makes it dangerous.**

The first four were **omissions**. A guard digested values but not the check set. A band kept its
old constant as a floor. An encoding folded per row when the unit was the incident. A tolerance
spanned regimes where it meant different things. In each case the code did less than it should, and
nothing in the repository claimed otherwise. A reviewer reading them had no false statement to
detect — only an absence, which is what made them hard to see.

The fifth is an **assertion**. `geo_cell_fraud_rate_30d`'s leakage note said:

> the prior is fitted on training folds grouped by account (E1), so no validation label reaches any
> cell estimate

That is false. An H3 cell aggregates across accounts, so account-grouped folds do not keep a
validation account's transactions out of a cell's rate. The sentence names the right mechanism (E1),
cites the right requirement, and draws a conclusion the mechanism does not support.

**Why that is worse than an omission.** An absent leakage note invites scrutiny; a present, specific,
confident one *consumes* it. A reviewer auditing that feature would have read a note that answered
the question they came to ask, and moved on — the note actively redirects the attention that would
otherwise have found the gap. The four omissions survived review by being invisible. This one would
have survived review by being **reassuring**, which is a stronger form of survival.

The aggravating detail, recorded because it is the useful part: **I wrote that note myself, in the
same session, about the feature I had myself labelled "the one most able to leak", in a work stream
whose entire subject was this class of omission.** Knowing the failure mode, having just written it
up, and being on guard for it were all insufficient. That is evidence about what does not work as a
control, and it is worth more than the fix.

**So the control cannot be attention.** `cross_account_control` is now refused at construction
unless it **names the mutation that would detect the leak if the control failed**. A control that
only asserts safety raises `ValueError`. The check is crude — it looks for the word "mutation" — and
crude is the point: it cannot be satisfied by writing a more convincing sentence, which is exactly
how the original note passed. Every non-account-keyed feature now ships with its own falsification
test rather than with a claim.

*Why it belongs in the paper:* the threats-to-validity section will describe the anti-leakage
guards. The credible version has to distinguish guards that failed by omission from a guard that
failed by asserting a protection that never existed, and say that the second was caught by a
mechanical check rather than by a careful reader — because the careful reader was the author, and
the author had just finished writing about this exact failure mode.

### 2026-09-19 · The prediction register: four recorded, one partially right

Four falsifiable predictions have now been written down **before** the evidence that would settle
them, each with its mechanism and its test. Consolidated here because the register is the
methodological claim, not any individual entry.

| # | Milestone | Prediction | Outcome |
|---|---|---|---|
| 1 | M2 | The shortcut detector's power against a 0.6-strength plant would not carry from seven-scenario to eight-scenario datasets, dipping around 200,000 rows | **Refuted.** Power rose monotonically; fired on every planted leak from 60,000 rows up, 100% throughout, no dip even directionally |
| 2 | M2 | The detector's positive offset was a real effect, not noise | **Refuted.** The t-statistic used an analytic SE of 0.00346 where the empirical sd was 0.0079 — the wrong denominator produced a confident three-sigma reading of nothing |
| 3 | M3 | `counterparty_unique_senders_24h` would force an eighth contract field, its window being keyed by the counterparty while every field assumed the account | **Confirmed on mechanism, refuted on sequence.** `minimum_history` arrived eighth from the amount group; `history_key` arrived ninth, from the named feature, for the stated reason |
| 4 | M3 | `accounts_per_device_7d` would be the first feature whose fold-safety requirement and purpose genuinely conflict, needing an owner decision | **Refuted.** Component folding resolves it: grouping accounts that share a device into one fold makes the cross-account count both complete and leak-free. The conflict exists only for the training-fold restriction, which that feature does not need |

**One partially right out of four.** That is the point worth making, and it is a stronger record than
four hits would be.

Four confirmed predictions would be consistent with two explanations that the data cannot separate:
that the reasoning is good, or that the predictions were safe, vague, or written once the answer was
already visible. A register with one partial hit and three refutations cannot be that. It is evidence
that the predictions were **genuinely at risk** — and each refutation cost one paragraph rather than
a wrong result shipped and defended.

The refutations were also individually productive, which is the part that would be lost by recording
only the score. Prediction 2's refutation found the central methodological error of M2, an analytic
null used where the empirical spread applied, and that error is now a standing rule about
standardising against theoretical nulls. Prediction 3's near-miss found the real content: the unit of
history is an undeclared assumption. Prediction 4's refutation produced the distinction between
component folding and the training-fold restriction, and therefore the density threshold in E1 —
which would not exist if the prediction had simply been right.

*Why it belongs in the paper:* a methods section claiming that predictions were recorded in advance
is only credible with the misses in it. The register is reproduced whole, including the scoring, and
the two M2 refutations are the ones that make the two M3 entries believable.

**Standing rule.** A prediction is recorded before the evidence exists, with its mechanism and its
test, and **its outcome is reported in the same place whether it is confirmed or refuted.** A
refuted prediction is never edited away or quietly dropped; prediction 4 stays in this notebook with
its refutation beside it.

### 2026-09-19 · Measuring cell density on a lat/lon grid, and declining a dependency to do it

E1's amendment turns on whether the account-to-entity graph is sparse enough for component folding.
For `geo_cell_fraud_rate_30d` the entity is an H3 resolution-6 cell, and computing H3 indices means
taking the `h3` dependency — which the ML package does not yet have, and which would be its first
runtime dependency ever (ADR 0009).

**Decision: measure on a lat/lon grid of comparable area, labelled as an approximation.**

H3 resolution 6 averages about 36 km² per cell. A 0.05° grid gives roughly 5.5 km x 5.5 km at these
latitudes, about 30 km² — the same order, and the same order is what the question needs. The question
is **"how many accounts share a cell of roughly this size?"**, and the answer is a distribution over
an equal-area-ish tiling. It does not depend on the tiling being hexagonal, on cells being H3's
cells, or on any particular cell's identity.

**Why the approximation is adequate here specifically:** the measurement exists to decide whether the
largest connected component exceeds a **10% threshold that is itself a judgement**, not a derived
constant. Spending a first-ever runtime dependency to make an input exact when the decision rule it
feeds is a considered opinion gets the precision budget backwards. If the measured largest component
lands near 10%, the right response is to argue about the threshold, not to re-measure the input more
precisely.

**What would require the exact computation.** Any claim about **specific cells** rather than about
the distribution: that a named cell is high-risk, that a particular cluster spans a given number of
cells, that the feature's value for a transaction is such-and-such. Those are claims about H3's
actual tiling and a lat/lon grid cannot support them. The moment `geo_cell_fraud_rate_30d` is
*implemented* rather than *characterised*, H3 is required and ADR 0009's inventory applies — and
that is also the first time the licence check's runtime path will ever have run against real input.

The grid's own distortion is recorded and bounded: cell area shrinks with cos(latitude), and across
the simulated countries (roughly 4 deg S to 4 deg N) that is under 0.3%, far below the resolution
this decision needs.

### 2026-09-19 · Why three refutations are worth more than four confirmations (discussion section)

Intended as a **discussion-section paragraph in the paper**, not a footnote, and written out here so
the argument survives in the form it should be made.

Four predictions were recorded in advance, each with its mechanism and its test, before the evidence
that would settle it existed. One was partially right. Three were refuted.

A register of four confirmations would be consistent with two explanations the reader cannot
separate: that the reasoning was sound, or that the predictions were safe, vague, or written once the
answer was already visible. Nothing in a list of hits distinguishes foresight from hindsight, and a
reader is right to suspect the second — pre-registration is only as good as the reader's ability to
tell that the registration was genuinely at risk. **Three refutations cannot be manufactured.** A
record that is mostly wrong is evidence that the predictions were real, in a way that a record that
is entirely right can never be.

The refutations were also the productive part, which is lost if only the score is reported. One found
the central methodological error of M2 — an analytic null used where the empirical spread applied —
and produced a standing rule about standardising against theoretical nulls. One found that the unit
of history is an undeclared assumption, which became the `history_key` field and then E1's amendment.
One produced the distinction between component folding and the training-fold restriction, and hence
E1's density threshold, which would not exist had the prediction simply been correct.

So the claim the paper should make is not "we predicted well". It is: **we recorded falsifiable
predictions before the evidence, we were mostly wrong, each refutation cost a paragraph rather than a
shipped result, and three of the guards now in the system exist because of what the refutations
found.** That is a claim about method, and it is checkable — the register is reproduced whole,
including the scoring, and the predictions are in the commit history with timestamps preceding the
runs that settled them.

### 2026-09-19 · The density measurement refuted the fold plan I had just written, and found a dead feature

E1's amendment assigned component folding to `counterparty`, `device` and `agent`, and the spatial
block split to `geo_cell`. I wrote that table from reasoning about which graphs "should" be sparse.
The measurement, on 1,012,522 rows at tree `d85385f` (`docs/research/component_sizes.json`):

| Key | Components | Largest share of accounts | Component folding |
|---|---:|---:|---|
| `counterparty` | **1** | **100%** | **invalid** |
| `agent` | 250 | **38.0%** | **invalid** |
| `geo_cell` | **1** | **100%** | invalid (as predicted) |
| `device` | 5,920 | 0.0169% | valid — see below |

Verified independently of the script: 5,920 accounts, 8,621 counterparties of which 6,616 are shared
by more than one account (max 162), 199 agents of which **all 199** are shared, and 5,484 devices of
which **zero** are shared. Max accounts on one device: **1**.

**Three of four keys are degenerate.** The one that is not is degenerate in the other direction.

**`accounts_per_device_7d` is identically 1 in this dataset.** No device is ever used by two accounts,
so a feature whose entire purpose is detecting device sharing has zero variance. That also kills the
"shared device and phone attributes across accounts" term of `synthetic_identity_score`, which Part
E.2 names explicitly. This is a **generator gap, not a feature defect**: the simulation has no
device-sharing mechanism, and ML-DATA-07 requires all 44 features computable. The device graph's
"sparsity" is therefore not evidence that component folding works there — it is evidence that there
is nothing to fold.

**What this says about the method, which is the part worth keeping.** The intuition was not sloppy:
counterparty and agent graphs plausibly *look* sparse, and in a real institution with millions of
accounts they might be. At 5,920 accounts sharing 199 agents, every account is a few hops from every
other, and the counterparty graph is fully connected. **Density is a property of the dataset's scale
and structure, not of the entity type**, which is exactly why the threshold had to be measured per
dataset rather than decided once per key. Had the E1 table shipped as written, six features would
have carried a control that does not work, each with a `cross_account_control` string asserting
fold-safety — the `geo_cell` leakage-note failure reproduced six times, by the author who had just
written the entry about it.

The measurement cost one script and twenty minutes. It was demanded precisely because assertion had
already failed once here.

**Consequence for E1, not yet resolved:** with component folding unavailable for `counterparty`,
`agent` and `geo_cell`, the remaining controls are the training-fold restriction (which trades
leakage for training/serving skew) or **temporal separation**. The dataset already has a temporal
split with an embargo, and for a strictly backward-looking aggregate a validation row reading earlier
cross-account rows is not leakage at all — it is what serving does. The exposure is narrower than
first thought and sits in **out-of-fold target encoding within the training period**, where folds are
random and account-grouped rather than temporal. That is where M-8 lived, and it is where a
counterparty-keyed or cell-keyed aggregate still crosses folds. Recorded as the open question; the
plausible answer is that out-of-fold encodings must be computed over rows strictly earlier in time
than the row being encoded, not over random folds.

### 2026-09-19 · A refactor that changed no parameter value re-drew the entire dataset

**Every figure in the entries above this line is from the pre-PB-29 draw and is left exactly as it
was recorded.** This notebook is chronological; editing past entries to carry today's numbers would
turn a record of what was found, when, into a claim that it was always known. The mapping is here
instead, once.

PB-29 moved every country fact into packs. `countries.simulated()` returns
`sorted(parameters.mapping("geography.country_share"))`, so country iteration went from the
declaration order `{RW, KE, TZ, UG, CD}` to alphabetical `[CD, KE, RW, TZ, UG]`. Every downstream
random draw shifted. **No parameter value changed** — the pack FX rates are byte-identical to the
`currencies.rwf_per_unit` table they replaced, verified value by value.

| Figure | Pre-PB-29 | Current (tree `d85385f`) |
|---|---:|---:|
| Rows | 1,006,249 | 1,012,522 |
| Event delay (strongest channel) | 0.758 | **0.746** |
| `merchant_category_code` | 0.706 | **0.707** |
| Shortcut detector | 0.510 | 0.509 |
| Event construction | 0.513 | 0.526 |
| Fraud rate, test split | 0.905% | 0.927% |
| Partition drift | 447 rows (0.044%) | 463 rows (0.046%) |

**The sort is correct and should stay.** A dataset that changes because someone reorders a YAML file
would be worse. What was wrong was that the consequence went unremarked: the commit message
described moving facts into packs, and nothing said "this re-draws the benchmark".

**Why no guard caught it for three commits, which is the part that generalises.** The report carries
a `parameter_digest` — a hash over parameter *values* — and the test asserts that digest appears in
the committed report. A change that alters the draw while leaving every value identical is
**invisible to it by construction**. It caught this only by accident: the pack refactor also changed
the parameter *structure*, so the digest moved for an unrelated reason. Had the sort been introduced
on its own, in a commit touching only `countries.py`, the guard would have passed while every figure
in the report became wrong.

This is the **sixth instance** of the shape recorded above as "The pattern": a guard that checks the
input it was written against rather than the output it exists to protect. The digest answers *did the
parameters change?*; the question the report needs answered is *is this report still about this
dataset?* Recorded as PB-41, whose fix is a **dataset fingerprint** — a hash over a deterministic
sample of output rows — carried in `release.json` and the report, so any change to the draw from any
cause invalidates it. The parameter digest stays alongside: it localises *why* a report went stale,
the fingerprint detects *that* it did.

*Why it belongs in the paper:* a synthetic benchmark's reproducibility claim is only as good as its
weakest invariant. "Deterministic from a seed" is true here and was never violated — and it was not
sufficient, because determinism guarantees the same output from the same code, not the same output
across a refactor nobody thought could matter. The honest claim names the tree, not just the seed.

### 2026-09-19 · Two fields justified themselves the day they were written, by different routes

**`history_requirement=DURABLE` earned its keep when the cold-cache test was written, not when the
field was added.**

The field was added by reasoning: `history_basis=OBSERVED_CAPPED` divides by history actually
observed, which needs a first-seen timestamp, which a rolling window cannot reconstruct once an
account is older than the window. That argument is correct and it was, by itself, **decorative** —
the online path was implemented with `first_seen_at` set on the first `observe()`, which looks
right and is wrong. It takes the rolling window's earliest *arrival* for the account's *start*.
Those coincide only when a replay happens to begin at an account's very first transaction, which is
true in a from-scratch backfill and false in every other situation: a cache flush, a mid-stream
restart, a shard rebalance.

Nothing in the warm-path tests could see it. Every hand-computed case passed. It surfaced the moment
the cold-cache case was written, as a parity failure with batch reading 0.96 and online reading 1.0
on a one-row fixture — and the first thing I did was assume the fixture was wrong, which it also
was, for an unrelated reason.

`restore_first_seen` now exists, and the test asserts that a flush **without** it changes the
feature. That assertion is the field's content: **without a test proving the durable value's loss is
detectable, `DURABLE` is a label with nothing behind it** — a declaration that something must
survive, in a system where nothing checks that it did.

The generalisation: **a contract field is not real until something fails when it is violated.** The
registry can refuse a blank field, which is cheap and catches omissions. It cannot tell whether the
value declared is the value implemented. Only a test that breaks the declared property and asserts
the consequence can do that, and for `DURABLE` the cold-cache case is the only place that is
possible.

### 2026-09-19 · A guard that vanishes under an optimisation flag is absent exactly where it matters

Both feature paths were first written with `assert contract is not None` to narrow an optional type
before reading it. `python -O` **strips every `assert`**. In a production deployment running with
optimisations, those guards are not weakened — they do not exist, and the code proceeds to an
attribute access on `None`.

Same family as the band floor and the parameter digest: **a protection whose reach is narrower than
its appearance.** The band floor was inert at the scale it was meant to bind; the digest could not
see the change it existed to catch; an `assert` is absent in the configuration most likely to be
production. In each case reading the code suggests a guard, and the guard is missing in the specific
regime that matters.

Replaced by `registry.contract_for()` and `registry.smoothing_for()` — lookups that **raise**, and
which keep the feature paths free of the `WindowContract | None` narrowing that invited the assert
in the first place.

**The check already existed, which is the more useful finding.** Ruff's `S` rules are in `select`,
so `S101` is enforced across all Python source with `**/tests/**` exempted — asserts in tests are
the assertion mechanism, not a guard. It flagged these the moment they were written. Mutation-proved
rather than assumed: an `assert`-as-guard planted in `primitives.py` was reported as
`S101 Use of 'assert' detected`, and the file restored with `git status` verified clean.

Audited for the same shape elsewhere: **zero** bare asserts in any Python source directory, and
**zero** in Java main source — which matters because the JVM disables `assert` unless `-ea` is
passed, so a Java assert-as-guard has exactly the same failure mode and no lint rule caught the
first one because there has never been one.

### 2026-09-19 · Fail closed: the interim fix for a schema gap that only bites during recovery

Parity mutation 10 turned PB-37 from a scheduling item into a correctness one, by showing that the
two cache-flush scenarios behave **oppositely**:

- *Arrivals and first-seen both lost.* Numerator and denominator shrink together, the ratio barely
  moves. This is the scenario intuition reaches for, and it is not the problem.
- *Arrivals restored, first-seen not.* Thirty days of rows divided by whatever span the cache
  happens to hold. The baseline inflates and **the ratio collapses on every established account at
  once.**

The second is not hypothetical: it is what M1's schema produces today. Transactions come back from
the database because they live in `transactions`; the per-account first-seen does not, because **no
table holds it**. So the damaging case is the one the system is actually built to produce, and it
appears during a recovery — when the system is already degraded and a shifted feature distribution
is the least likely thing anyone is watching.

**The interim fix is to fail closed.** Without a durable first-seen the online path emits NaN, which
D-04's native missing handling already covers, and `observe()` no longer infers first-seen from its
earliest arrival — the inference that made `DURABLE` decorative in the first place. A genuinely new
account's first-seen is supplied by the caller, having consulted the durable store, rather than
guessed by the feature.

**The asymmetry that makes this the right call:** a missing feature during recovery costs one
feature's worth of signal, on the rows scored during the incident, and announces itself. A plausible
wrong number costs the whole account base's velocity signal, silently, and is indistinguishable from
a genuine surge in behaviour — so the natural reading of the resulting alert volume is "the attack
is real", which is the worst possible interpretation to reach during an incident.

Two tests hold it: one asserting the NaN, and a control asserting `observe()` never invents a
first-seen. The control matters more than the assertion — without it, the NaN test would pass only
until the first transaction arrived, and every post-flush account would quietly receive a wrong
denominator instead of a NaN.

*Why it belongs in the paper:* the feature contract's claim is that declaring a property prevents a
class of defect. The honest version is narrower — declaring it **located** the defect, and only the
mutation test proved the declaration was being honoured. A field that nothing checks is a comment
with a type annotation.

### 2026-09-19 · The fingerprint found that the defect it was built for had already closed

PB-41 asked for a dataset fingerprint — a hash over **output rows** rather than over parameters —
because PB-29 re-drew the entire benchmark while changing no parameter value, and
`parameter_digest` could not see it by construction. The acceptance named the mutation to prove it
with: *change the country iteration order alone, leaving every parameter value identical, and
assert the guard fails.*

**It doesn't fail. Reversing the pack order leaves the dataset byte-identical.** Measured on a
12,000-row run at seed 20260917: same fingerprint, both orders. The reason is in the code PB-29
itself produced — `Population._apportioned` starts with `countries = sorted(self._country_share)`,
so apportionment re-sorts whatever order the packs arrive in, and the merchant and agent tables are
dicts keyed by country rather than sequences consumed in order. The specific door PB-29 came
through is shut.

Three things follow, and the order matters.

**First, the mutation had to be replaced rather than reported as passing.** A mutation that no
longer reproduces its defect proves nothing about the guard; writing "no difference detected" beside
it would have been true and useless. The replacement is `_GOLDEN`, the low-discrepancy step the
activity multipliers walk: a constant that lives in generator **code**, is covered by no provenance
record, and moves every customer's activity multiplier and therefore every row drawn afterwards.
Changing it re-draws the dataset with every parameter value byte-identical, the fingerprint moves,
and the parameter digest does not.

**Second, the class is what matters, not the instance.** PB-41's framing — "a changed iteration
order" — was one instance of *anything outside the parameter set that steers the draw*: a code
constant, an algorithm replaced by an equivalent one, a library's sampler changing between
versions. Fixing the instance closed one door and left the class open, which is the
guard-with-two-doors shape appearing **in the fix for a guard-with-two-doors**. The fingerprint is
the right response precisely because it does not enumerate the ways an input can escape notice; it
hashes the output.

**Third, the closure is now pinned.** That the generator is insensitive to pack order is a property
someone could remove without noticing — deleting one `sorted()` would do it — so it is a test
rather than a paragraph. If it breaks, PB-29 is possible a second time and the test says so.

*The uncomfortable part, recorded because it is the useful part:* had the mutation been written as
specified and simply run, it would have passed with the guard absent, because the fingerprint did
not yet exist when the behaviour it was meant to detect stopped occurring. The thing that caught it
was running the mutation **before** writing the assertion it was supposed to satisfy, and being
surprised. A mutation table is only evidence if the mutations are executed; a row filled in from
the design document is a plan, not a result.

**Where the guard actually bites.** The fingerprint is written into `realism_report.md` and
`release.json`, and `export()` **refuses** to bundle a realism report that does not carry the
fingerprint of the dataset being exported. Refusing rather than warning: a release ships the data
and the report together and a consumer has nothing to tell them apart with, which is exactly the
state PB-39 shipped for two commits. The committed report describes a 1,012,522-row run and carries
no fingerprint until its next regeneration, so today it cannot be bundled with any other dataset —
which is the correct behaviour and is visible rather than silent.

### 2026-09-19 · The control mutation failed its own precondition, and that was the finding

Parity mutation 1 is the row that must **pass**: an honest reassociation of a window sum, there so
that the suite can tell "the tolerance catches bugs" from "the tolerance catches everything". It
was written as *accumulate the window in reverse order* and asserted the two orders differ before
asserting they agree within ADR 0025's tolerance.

**It failed on the precondition. The two orders produce the identical float.** Both feature paths
sum with the built-in `sum()`, and since CPython 3.12 `sum()` applies **Neumaier compensated
summation** to floats. Both paths are therefore correctly rounded and agree bit-for-bit for the
same multiset however they visit it, so the reassociation the tolerance exists to permit does not
happen between them.

The first reading of that is "the tolerance is unnecessary, and rows 1 and 8 are about nothing".
The correct reading is narrower and points forward. **The production online path will not re-add a
window on every request.** A Redis-backed store keeps an incrementally updated running total,
because re-reading a 30-day window per transaction is what the store exists to avoid — and a
running total cannot be compensated, since it never sees the window twice. So the difference the
tolerance must admit is real; it arrives at M6, without any change to these features, and the
suite would meet it for the first time in production if the row had been deleted as vacuous.

The row now compares the compensated sum against a naive running total in both directions:
measured gap **7.5e-9** against a tolerance of **1.0e-5** at a heavy user's seven-day total.

**What makes rows 1 and 8 evidence is that they are a pair.** Row 1 says the tolerance admits a
correct implementation differing in the last bits; row 8 says it rejects one that has lost seven
significant digits — float32 accumulation of the same window, gap **1.33**. A suite holding only
row 1's reasoning ("the paths accumulate differently, so small differences are fine") has no rule
that separates 7.5e-9 from 1.33, and would accept both. ADR 0025's relative rule does, by five
orders of magnitude.

*The general point, which is the third time this session has produced it:* **a mutation is only
evidence once it has been executed.** Row 1 was written from the design document, was plainly
correct as reasoning, and described arithmetic the interpreter stopped doing in 3.12. Rows filled
in from a design are a plan. The table's value is that it records results, and a result can
contradict the design that predicted it.

### 2026-09-20 · The estimate said eight; computing it said six

PB-44 was opened with a count in it: eight of the 44 features could not be fed by the benchmark. The
number came from reading each feature's inputs and asking whether the dataset held them — careful
reasoning, done once, by the same method that produced the first `geo_cell` leakage note.

**It was wrong, in both directions.** `days_since_sim_swap` was on the list and is computable:
`account_events` carries `SIM_SWAP` rows, and the feature needs a join rather than a schema change.
`synthetic_identity_score` was on it too, and is computable because its terms contribute **zero**
when their evidence is absent — impaired rather than dead, which is a degeneracy and was already
declared as one. Two others were not on the list and should have been examined:
`corridor_class` and the five local-time features need the account's country, which the dataset
does not carry at all.

The measured answer is **six**: `account_age_days`, `counterparty_account_age_days`, `kyc_tier`,
`agent_float_utilisation_ratio`, `agent_distance_from_registered_km`, `round_sum_flag`.

**The owner's instruction is what made this checkable**: make "NaN everywhere" structurally
visible rather than a backlog note. `computable: COMPUTABLE | NO_SOURCE_DATA` is an eleventh
registry field with no default, and `fs-features computability` computes all 44 over the benchmark
and fails **in both directions** — a COMPUTABLE feature NaN for 100% of rows, and a NO_SOURCE_DATA
feature that is not. The second direction is the one that keeps the register from drifting into
pessimism once somebody wires the data, and pessimism is no more true than optimism while being
considerably more trusted.

**100% and not a threshold.** Four device features are NaN on every USSD row and four agent
features on every non-agent one, correctly; a cut-off would mean deciding how dead is dead, and
would have reported nine healthy features as gaps.

*The generalisation, which is the fourth time this session has produced it:* **a count obtained by
reasoning about code is a prediction, and predictions belong in the register with their outcomes.**
The parameter digest could not see a changed draw, mutation 1 described arithmetic the interpreter
had stopped doing, and this count was off by four in two directions at once. In each case the
reasoning was sound and the world had moved.

### 2026-09-20 · The country the dataset does not record, and the inference that recovers it

Six features need the country a transaction happened in: five local-time features need a UTC offset
and `corridor_class` needs the corridor's origin. **`transactions` has `counterparty_country` and
nothing for the sender.**

The pipeline recovers it from the transaction's **currency**, which inverts exactly because the
generator derives the currency *from* the customer's country — but only while currencies are
distinct across the simulated packs. So `fs-features` **refuses** when two packs share one rather
than tie-breaking. XOF across West Africa is the case that would trigger it, and PB-31's assumed
packs are meant to include exactly that.

Worth recording as a shape rather than as a fact about this schema: **an inference that is exact
today and ambiguous after a data change is more dangerous than one that is approximate**, because
nothing about it looks provisional. A silent tie-break would have placed transactions in the wrong
country and shifted every local-time feature by hours, on the run after someone added a pack. The
honest fix is an `account_country` column and it is recorded in PB-44.

### 2026-09-20 · A performance change found a correctness defect

Computing 44 features per row by scanning the whole corpus is quadratic, and the corpus has to be
large for the window features to mean anything: at 5,920 accounts, a ten-thousand-row slice of the
benchmark gives under two rows per account, so every window would be empty and the measurement
would be of the sample rather than of the dataset. So the corpus is indexed by `history_key` and
each feature is handed the rows that share its key — the same rows that survive its own filter.

The equivalence is exact, so it is asserted bit for bit: `test_the_corpus_index_changes_no_value`
compares all 44 slots through `float.hex()` for every sampled row, on the grounds that an unchecked
performance change applied to the computation behind every figure the milestone reports is not a
performance change, it is a rewrite.

**It failed, and the index was right.** `velocity_ratio_1h_vs_30d` never filtered by account:
handed a mixed history it counted strangers' transactions as the scored account's burst. Every
hand-computed test for that feature passes a single-account history, so none could produce it, and
the online path cannot make the mistake at all because its state is keyed by account. The defect
lived exactly in the gap between the two — reachable only by a caller that passed a mixed corpus,
which is what the batch pipeline does and what no test had done.

*The useful part:* the parity suite is blind to this class by construction. Both paths were
"correct" in the sense that each agreed with the other on every fixture either was given, because
the fixtures were single-account. **A parity test proves the paths agree on the inputs they are
given, and says nothing about the inputs nobody thought to give them.**

### 2026-09-20 · The status table said "met" and cited a run that did not exist

The M3 exit-criteria table was written in the same edit as the machinery it describes. E3 read
**Met**, followed by "see the evidence run below", and no evidence run had completed — the
measurement was killed for memory pressure an hour later and had never produced a figure. E6 read
**Met** for a measurement that rides on E3's run.

Caught by re-reading before committing, so nothing was published; the shape is what matters.
**Nothing generated those cells.** An author typed "Met" while writing the code that would one day
justify it, and a reader of the table would have found a record of results where there was a record
of intentions.

**This is the third instance of the same family in this project:**

1. the committed realism report describing a superseded parameter set for two commits (M2, MAJOR
   4.1, and again as PB-39);
2. the README status line saying "M0 — bootstrap and governance" through two merged milestones,
   because every other record-accuracy guard watches a *generated* artefact and the README is
   written by hand;
3. this table.

All three are hand-written records of machine-checkable facts. The first two were each fixed with a
guard that compares the hand-written claim against the thing it describes — a parameter digest, and
`fs-readme-status`. The third has no guard, and is currently the only record in the repository that
asserts a milestone's completeness.

**The rule, stated so it outlives this instance: a status table must derive "met" from the
existence of an evidence artefact, not from an author's edit.** The corollary is sharper and worth
saying: *writing the checker is not optional politeness, it is what makes the table a record.*
Until one exists, every cell is a claim by a person, and the table now says so in its own header.

**Can it be mechanised, the way `milestones.yaml` is?** Yes, and the criteria sort into four kinds
of evidence, which is what makes it tractable rather than a matter of parsing prose:

| Evidence | Criteria | Check |
|---|---|---|
| a named test that exists and passes | E4, E5, E12–E15 | the test id is collected, as `fs-traceability` already resolves tagged tests |
| an existing gate | E9, E10 | the gate command exits 0 |
| an artefact on disk with a tree hash | E3, E6, E11 | the file exists and names the commit it was produced at |
| judgement | E1, E2, E7, E8 | **never auto-met**; must carry prose and a human's initials |

The fourth row is the load-bearing one. A checker that quietly marked judgement criteria "met"
would be worse than no checker, because it would carry the authority of a machine over a claim no
machine made. Logged as PB-45.

*Why this belongs in the notebook and not only in the backlog:* the first two instances were each
treated as a bug in one file. Three instances make it a property of how this project records
things — hand-written summaries of machine-checkable facts drift, always in the flattering
direction, and always in the document a reader trusts most.

### 2026-09-20 · E3 ran and failed, which is the first thing it has ever done

The first measurement of the D-08 ceiling over the **engineered** features, at commit `fad43dd`:
corpus 200,000 rows, 20,000 scored, 166 confirmed fraud, folds grouped by whole accounts.

**Five of the 44 exceed 0.80.** `velocity_ratio_1h_vs_30d` 0.894, `tx_count_1h` 0.826,
`counterparty_is_new_for_account` 0.816, `implied_speed_kmh` 0.812, `seconds_since_last_tx` 0.811,
each ±0.04. Four more sit within the interval of the ceiling.

M2 measured 0.707 and reported the benchmark as comfortably inside D-08. That measurement was of
the dataset's **columns**, and it was correct. The ceiling is a property of **what a model can be
given**, and a model is given the engineered features — so the claim was true of the thing measured
and false of the thing it was quoted about. C-9 is marked refuted as stated; C-14 carries the new
measurement; the datasheet's bullet now says "column" where it said "feature".

*The generalisation, and this is the **fourth instance of the family**:* **a control measured on
the inputs is not a control on the system.**

1. the **parameter digest** checked parameter values and not the draw, so a re-drawn dataset
   passed it (PB-41);
2. the **licence check** ran green over an empty scope for three milestones (ADR 0009's
   generalisation);
3. the **fold guard** removed a row's own label and folded per row, when the unit was the incident
   (M-8);
4. the **D-08 ceiling** was measured over the dataset's columns and quoted as a property of the
   features a model is given.

Each time the check was real, ran green, and was about a narrower object than the sentence it
justified. What makes the fourth the worst of them is the gap: **three milestones passed between
the claim and its refutation**, and in that time it was quoted in the datasheet, the walkthrough
and the defence questions. A control's scope is part of the control, and a scope narrower than the
claim is not a weaker control — it is a different one, with the claim resting on nothing.

The practical test, which would have caught all four at the point of writing: **name the object the
check ranges over, and then read the sentence the check is supposed to justify.** If the two nouns
differ — parameters against draws, scanned files against all files, rows against incidents, columns
against features — the check does not justify the sentence.

**What the failure is, and what it is not.** It is not a leak in the features: all five are
strictly backward-looking, exclude the scored transaction, and are the code the parity suite
replays. The separation is in the data. Every one of the five is a burst or recency indicator, and
the generator's fraud scenarios are burst-shaped by construction — a drain, a velocity run and a
bust-out are all rapid sequences. The strongest single feature reaches within 0.05 of the 0.940 AUC
that ML-GATE-01 asks of an entire model, so a headline result here would not be evidence that the
model learned anything a one-line rule could not.

I have recorded it as failed rather than choosing a resolution. Three are available — change the
draw, restate D-08's scope to columns, or declare the benchmark velocity-separable and report every
metric beside the single-feature baseline — and which one is right is an owner's call, not a
consequence of the measurement. PB-46.

**A second finding fell out of reading the two runs side by side.** Three features sit at a
separation of exactly 0.500–0.502, which is what a constant looks like. `just_below_limit_flag` is
constant `False` **by construction** — the benchmark supplies no limit configuration, so the band
test runs over an empty set — and it is declared `COMPUTABLE`. The computability check could not
see it, because it asks whether a feature ever produces a number and this one always produces the
same number. **NaN-everywhere is a missing input; constant-everywhere is a missing distribution**,
D-04 covers the first and nothing covers the second, and a constant column looks in every
completeness count exactly like a working feature. PB-47.

*Worth noting about the order of discovery:* the computability check passed cleanly, and the thing
it missed was visible only once a second measurement put a number beside each feature. A check
that answers one question well will be read as answering the neighbouring one.

### 2026-09-20 · The fifth evidence run lost, and the first one lost to a path I invented

The 1M benchmark was generated into `/tmp/claude-1000/.../d9f158ee-…-850/scratch` — a directory I
composed myself rather than the session scratchpad the harness names. It was reaped, and with it
the dataset, the packs sidecar and a completed computability run. Regenerating cost thirty-five
minutes; nothing else was lost, because the dataset is deterministic from its seed and the tree was
already frozen.

Five evidence runs now, five different causes: source edited mid-run, `uv sync --reinstall` mid-run,
a test mutating the repository's parameters, memory pressure from two heavy runs started in
parallel, and now an invented scratch path. **The rule that covers all five is about custody rather
than care:** an evidence run owns its tree, its environment, its machine and its output location,
and each of those is a thing to be *chosen deliberately at the start* rather than defaulted into.
The harness names a scratchpad; using it is not a convenience, it is the only location with a
stated lifetime.

### 2026-09-20 · Option 3, and why the other two were worse

The owner's decision on PB-46: **declare the benchmark velocity-separable, report every model
metric beside the single-feature baseline, and make the baseline a first-class result rather than a
caveat.**

The reasoning is worth keeping because both rejected options are tempting in a way that this one is
not.

**Changing the draw to suppress the burst structure would make the benchmark less realistic.** Real
SIM-swap drains and real mule fan-out *are* bursty. A generator whose fraud was evenly spread would
pass D-08 and be the weaker artifact — and the thing that failed would have been fixed by making
the data less like the world. That is tuning the fixture until the test passes, at dataset scale.

**Restating D-08's ceiling as a claim about columns would be redefining a control so that it
passes.** The ceiling would then be true and useless: a benchmark can be trivially solvable by a
feature while no column exceeds 0.80, which is exactly the state this one is in. This project
exists in large part to catch that move, and it is available here precisely because the original
wording was ambiguous about its object — the same ambiguity that let the claim stand for three
milestones.

What is left is to say the true thing plainly and carry it everywhere a result appears: **on this
benchmark a single velocity feature reaches 0.894, so an ensemble is judged against that floor and
not against 0.5.** It costs nothing except the comfort of a large-looking number.

*The part worth generalising:* when a control fails, there are usually three moves — change the
system, change the control, or change what you report. The third is the only one that adds
information, and it is the one that feels like giving up.

### 2026-09-20 · A missing distribution, and the check that could not see it

PB-47, accepted and built: **`CONSTANT` is now a third computability state.** A feature that
produces a number for every row and always the same number is as dead as one that produces none,
and until today nothing in this project could see it.

The asymmetry is the point. `NaN`-everywhere is a **missing input**, and D-04's native missing
handling exists for exactly that — the model is told. Constant-everywhere is a **missing
distribution**: the column is present, complete, well-typed and carries one value, so every
completeness count reports it as working, the model trains on it, and it contributes nothing.
Nothing covered that case.

`just_below_limit_flag` is the instance. It is constant `False` **by construction** on this
benchmark, because no channel or KYC-tier limit configuration exists, so the band test runs over an
empty set. It was declared `COMPUTABLE`, the computability check passed it, and it would have been
counted among the features a model was given.

**How it was found is the uncomfortable part.** The computability run passed cleanly. The E3 run
passed a separation beside every feature. Neither found it; reading the two outputs **side by
side** did, because three features sat at exactly 0.500 and a separation of exactly one half is
what a constant looks like. *A check that answers one question well will be read as answering the
neighbouring one*, and the neighbour here was one column away in a different file.

Two smaller findings came out of implementing the variance check, both about fixtures rather than
features:

- The unit fixture made **eight** features constant because its transaction gaps were all in the
  same order of magnitude. The check was right and the fixture was wrong; gaps now span 25 seconds
  to sixty-four days, chosen so that each window feature has something to see and the dormancy flag
  has a silence to notice.
- The CLI fixture built rows round-robin across accounts, which put eight other accounts' gaps
  between an account's own consecutive rows — so every per-account window was empty and nine
  features read constant. Built per account and sorted afterwards, they vary.

Both are the same mistake as measuring E3 on a ten-thousand-row slice of a 5,920-account dataset:
**a fixture that cannot exercise a feature will report the feature as broken, and the report will
be believed.**

### 2026-09-20 · Fixtures built to be small instead of built to contain the thing

Three instances now, and they are the same mistake each time.

1. **Seed 11 with no drifting rows.** The partition-drift test bounded how far a row may drift from
   its month, on a 6,000-row fixture whose seed produced **zero** drifting rows. The bound held
   over an empty set. Mutating the permitted drift to zero left the test passing, which is how the
   vacuity was found — not by reading it (PB-26).
2. **The geo fixture with no shared cells.** `geo_cell_fraud_rate_30d`'s first parity replay used a
   **single account**, so every row in the cell belonged to the scored account and a cell-keyed
   aggregate was indistinguishable from an account-keyed one. That is the feature whose leakage
   note was already wrong for exactly the same reason.
3. **Both PB-47 fixtures, too uniform to vary.** The unit fixture spaced every transaction by tens
   of hours, so eight features took one value and the new variance check reported them as constant.
   The CLI fixture built rows round-robin across eight accounts, putting seven other accounts'
   gaps between an account's own consecutive rows, so every per-account window was empty and nine
   features read constant. In both cases the check was right and the fixture was wrong.

**The common cause is not carelessness about preconditions. It is that each fixture was built to be
small, and its content was whatever fell out of being small.** Six thousand rows, one account, one
order of magnitude of spacing, a tidy round-robin loop — every one of those is a decision about
*size or shape*, taken first, with the property under test left to chance. Then the test is written
against whatever the fixture happens to contain, and it passes.

E12 already requires the precondition to be asserted, and that rule is doing real work — it turns a
vacuous pass into a loud failure without a mutation run. But it is a **catch**, and it only fires
when someone thinks to write the assertion. Twice here the assertion existed and the fixture still
did not contain the condition, because the assertion was written after the fixture and phrased to
match it.

**The stronger habit, stated as a construction rule rather than a check:**

> When writing a fixture, **first name the property the test needs it to have. Then construct for
> that property. Let size follow.**

"Six accounts sharing three H3 cells, one of them used by four of them" is a property; "sixty rows"
is not. "Gaps spanning 25 seconds to sixty-four days, so every window opens and closes and one
silence exceeds sixty days" is a property; "fourteen transactions each" is not. Written that way
the precondition assertion is a transcription of the sentence that produced the fixture, rather
than a guess about what it ended up holding — and when it fails, it fails at the fixture rather
than at the assertion.

*Why this belongs beside the guard-with-two-doors family rather than inside it:* that family is
about a check whose **scope** is narrower than the claim it justifies. This one is about a check
whose **input** does not contain the case it tests. Both produce a green result that means nothing,
and both are invisible to review, but the remedies differ — name the object for the first, name the
property for the second.

### 2026-09-20 · Counting NaN as a value hid the feature the check was built to find

The variance check was built, run on the benchmark, and passed — and passing was wrong.

`accounts_per_device_7d` is NaN on every USSD row (38.5% of them) and the number **1** on every
other row. It carries nothing: no device in this dataset is shared, so wherever it is defined it is
1. The first implementation counted NaN as a value, so it saw **two distinct values** and reported
the feature as varying. The check built to find dead columns declared this one alive.

Counting only **non-NaN** values fixed it, and the fix was larger than the bug. The three
computability states turn out to be a **partition on a single measured number**:

| Distinct non-NaN values | State |
|---:|---|
| 0 | `NO_SOURCE_DATA` — the inputs are absent |
| 1 | `CONSTANT` — present, complete, carrying nothing |
| ≥ 2 | `COMPUTABLE` |

That replaced four hand-enumerated lists — *dead*, *flat*, *revived*, *varied* — with one
comparison of declared state against observed state. The lists were each correct and, between
them, incomplete: two of the six possible declared-versus-observed pairs had no list at all
(`CONSTANT` observed as absent, `NO_SOURCE_DATA` observed as constant), and nobody would have
noticed until one occurred.

*The generalisable point:* **a state machine derived from one measurement is checkable in a way a
hand-enumerated list of failures is not.** The list can only contain the cases its author thought
of, and its coverage is invisible — it looks complete because each entry is right. Deriving the
states from a single quantity makes exhaustiveness a property of the arithmetic: every value of the
number maps to exactly one state, so every mismatch is reachable and named by construction. The
same shape as replacing a `assert x is not None` with a lookup that raises: the question stops
being *did we remember this case* and becomes *what does this value mean*.

Missingness is deliberately not variation, and the reason is worth keeping: a feature that is NaN
on USSD and constant elsewhere is separated only by the channel, `channel` already carries that,
and counting the NaN as a second value credits this feature with the other one's signal.

### 2026-09-20 · I wrote the entry about fixtures shaped by the answer, then shaped one

The entry above it in this notebook — *"Fixtures built to be small instead of built to contain the
thing"* — was written in response to three instances, and closes with a construction rule: name the
property the test needs, construct for that property, let size follow.

Within the same hour, implementing the variance check, I made the fourth instance. The 70-row unit
fixture reported two agent features as constant. I widened the fixture. That made five *other*
features constant. I had started tuning a fixture until a check returned the verdict I expected,
which is precisely the failure the entry describes — and I was doing it while the entry was open in
the same file.

The correct move, once seen, was not a better fixture: it was noticing that whole-registry
agreement is a property of the **benchmark** and does not belong in a unit test at all. The unit
tests now assert the check's logic directly, plus an **exact set** of the mismatches 70 rows cannot
avoid, so a new one still fails while the known ones do not mask it.

**This is the same recurrence shape as the t-denominator mistake**, which used an analytic null to
standardise a single reading *in the same investigation, within the same hour* as correcting
exactly that error one level down. Two instances of the same thing: the correction was understood,
written down, and then not applied to the next instance a few minutes later.

*Why it belongs in the discussion rather than as an anecdote:* **knowing a failure mode does not
immunise against it.** Both times the author had just finished articulating the rule — the
articulation was fresh, correct, and did not transfer. Recognition happens after the fact, when the
result looks wrong, not while the choice is being made; at the moment of choosing, the tempting
option does not present itself as an instance of the general error, it presents itself as a
reasonable local fix.

The consequence for how this project defends itself is concrete: **a defence that depends on
remembering a rule at the moment of temptation is not a defence.** The rules that have actually
held here are the ones with machinery behind them — a registry that refuses a blank field, a check
that fails when a declaration disagrees with data, a hook that rejects a malformed commit message,
a guard that hashes the parameter tree before and after a suite. The rules that have failed twice
are the ones carried as understanding. Every lesson in this notebook should therefore be read with
the question *what would enforce this?* attached, and the ones with no answer should be treated as
known-weak rather than as settled.

### 2026-09-20 · The comment was true, and that is what made it dangerous

The M3 milestone review's most important finding, and the one worth the most space.

`fraudshield_ml.features.vector._first_seen` returned an account's earliest row **in the supplied
corpus**. The online path is explicitly forbidden from that inference: `observe()` does not set
`first_seen_at`, and `device_age_days` fails closed to NaN without a durable value, because the
earliest arrival is the rolling window's edge rather than the account's beginning (PB-37, with two
tests whose names say so). The batch caller did it anyway, and then divided by it — the feature is
`history_basis=OBSERVED_CAPPED`, so a short denominator inflates the ratio for exactly the accounts
that look newest.

**The defect is not the inference. The defect is the docstring underneath it**, which said: over a
*complete* dataset the earliest row genuinely is the account's first.

That sentence is **true**. It is also a statement about complete corpora, attached to a function
that has only ever been called with truncated ones — every run this repository has performed passes
the last 200,000 rows of a million. The justification and the usage were about different objects,
and the justification was the more visible of the two.

**A wrong value with a plausible justification attached survives review in a way a bare wrong value
does not.** A bare `min(timestamps)` invites the question "which timestamps?". The same line under a
paragraph explaining why it is sound does not: the reader's attention has already been answered.
The comment did not merely fail to prevent the defect, it actively defended it — and it would have
defended it against me, in a month, reading my own file.

**This is the second time a written claim of safety had nothing behind it.** The first was
`geo_cell_fraud_rate_30d`'s original leakage note, which asserted that account-grouped folds kept
validation labels out of cell estimates. False, and it cited the right requirement while drawing a
conclusion the requirement does not support. The registry's response was to make every
non-`ACCOUNT` feature declare **the mutation that would detect the leak if its control failed** —
because a control that only asserts safety is the claim-with-nothing-behind-it this field exists to
prevent. The same disease, in the same repository, five days apart. The difference is that the
geo-cell note was wrong; this one was *right*, about something else.

So the earlier six guard-with-two-doors instances are a milder family than this. There, a check
ranged over a narrower object than the sentence it justified — a scope error, findable by asking
what the check ranges over. Here, a **correct statement** was placed where a reader would take it
as a warrant for a different statement. No amount of reading the sentence finds it, because the
sentence is fine. The only question that finds it is: *is this claim about the situation the code
is actually in?*

#### What would catch this class

The owner's proposal: forbid any helper that derives a durable fact from a passed-in corpus, by the
same rule that enforces path independence, on the grounds that the corpus cannot know what it does
not contain.

The principle is exactly right and I would implement it differently, because the import-graph rule
detects *imports* and a corpus arrives as a **parameter**. The same machinery — AST inspection in
`test_independence.py`, which already greps `primitives` for banned shapes — applies to
**signatures** instead: no function in the vector module may take a `Sequence[Transaction]` and
return a `datetime`, which is precisely the shape of both deleted helpers.

But the signature rule is narrower than the class, and that matters, because looking for the class
found three more instances the review had missed. `counterparty_is_new_for_account`,
`is_new_country_for_account` and `device_is_new_for_account` all declare unbounded history and all
answered "never before" from the corpus prefix — the same defect returning a **boolean** instead of
a timestamp, so a signature rule keyed on `datetime` would have passed them. They reported every
long-standing payee, corridor and handset as new, most strongly for the accounts with the longest
histories, and `counterparty_is_new_for_account` was one of the five features measured over the
D-08 ceiling.

The rule that covers all five, stated as a property rather than a type:

> **An aggregate over a window is corpus-derivable. A statement about all of history is not.**
> Anything a feature declares as unbounded — `window="unbounded"`, or
> `history_requirement=DURABLE` — must arrive as an input, and a corpus may never be asked for it.

That is checkable from the registry rather than from a signature, which makes it the better
mechanism: for every feature declaring DURABLE or unbounded, assert that the vector obtains its
durable input from `FeatureContext` and not from a corpus argument. It is registry-driven, so a new
unbounded feature is covered the day it is declared, and it does not depend on anyone remembering
the rule — which is the standard the previous entry set.

All five now take their durable state from `FeatureContext`; the CLI reads first-seen over every
partition and the prior sets over every partition earlier than the corpus;
`test_truncating_the_corpus_changes_no_unbounded_feature` scores the same row against the full
corpus and a truncated one and requires all five to be identical, with a control proving the
truncated corpus really does hide the history.

### 2026-09-20 · Eleven minutes of feature computation to discover a missing import

The M4 pipeline smoke test computed 36 features for 20,000 rows — eleven minutes — and then died
on the first line of the model half:

```
ImportError: sklearn needs to be installed in order to use this module
```

`xgboost.XGBClassifier` is the scikit-learn wrapper and imports scikit-learn, which this package
does not depend on. The fix was to use the booster API instead, which is four lines and the right
dependency footprint. The cost was the whole feature pass, thrown away.

What makes this worth an entry is not the import. It is the **shape of the command**: an expensive
irreversible stage followed by a cheap fragile one, with nothing between them. Every minute of the
expensive stage is wagered on the cheap stage being correct, and the wager is settled only at the
end. The same shape produced the earlier loss today, where a 30,000-row run spent 106 minutes
printing nothing at all, because the progress line came before the loop rather than inside it.

Three changes, and the third is the general one:

1. **The model half is a function with a test.** `fit_and_score` trains a booster on 200 synthetic
   rows with one planted signal column, in under a second. The failing import fails there now, and
   a column of pure NaN is exercised beside it (D-04), because that is what the benchmark hands it.
2. **The expensive stage writes its output.** `--cache` stores the computed matrix keyed on the
   dataset, the corpus size, the sample size and the feature list, so a failure after it costs
   seconds rather than the pass. The key matters as much as the cache: one keyed on nothing is a
   way to report one run's numbers under another run's settings, and each field is asserted to
   invalidate on its own.
3. **The expensive stage reports progress.** Rate and estimated time, every 500 rows. A silent
   hour and a hang are the same observation, and I spent 106 minutes not distinguishing them.

The habit to carry: **when a pipeline has an expensive stage and a cheap one, test the cheap one
first and persist the expensive one's output.** Neither is a new idea; what is new is noticing that
"run the whole thing and see" is the default shape and costs a working session's worth of machine
time before it teaches anything.

### 2026-09-21 · The explanation was wrong for two days, and the artefact could not say so

`dataset/realism_report.md` said **1,012,522 rows**. Regenerating it said **1,006,249**. The gap
had a published explanation — PB-29 moved country facts into packs, `countries.simulated()` sorts,
so iteration went from declaration order to alphabetical and the draw shifted — and that
explanation had been copied into the datasheet, the claims register, the walkthrough and four M2
review documents. It was wrong, and so was the commit it was attached to.

Three candidates, tested cheapest first:

1. **A different `--rows`.** Refuted from the artefact. The split boundaries are planned from the
   *target* row count, so a different target moves them. Both reports state the same test start
   and the same spans to two decimals.
2. **A committed generator change.** Refuted from the history. The figure appears exactly once in
   the report's whole history, and the only commit touching the generator or its parameters in the
   window is the pack refactor itself.
3. **The pack refactor.** Refuted by measurement. Generating 200,000 rows at the same seed on the
   commit before the refactor and on the current tree gives 201,243 rows **and the identical
   fingerprint**. PB-29 did not re-draw anything.

So the run was made on a **dirty working tree**, and the change is unrecoverable: never committed,
never stashed, never described.

#### What is actually interesting here

Not that someone ran a job on uncommitted code — that is ordinary. It is that **the mistake was
undetectable by construction**, and then the project reasoned around it with great care for two
days. The explanation was not lazy: it named a specific mechanism, in a specific function, with a
plausible causal story, and it was repeated in six places by someone checking their work. Detail
and confidence are not evidence, and a wrong explanation that survives scrutiny does more damage
than an unexplained gap, because it closes the question.

The earlier entry about `_first_seen` said *a true comment can defend a wrong value*. This is the
same shape one level up: **a plausible mechanism can defend a wrong provenance**. In both cases
the defence was written by someone trying to be careful, and in both cases the thing that finally
settled it was a measurement nobody had thought to take because the story already accounted for
the facts.

#### What would catch this class

The commit hash on an artefact is written by hand or passed as a flag, so it records what the
author *believed* the tree was. A commit identifies a tree in the object database; the interpreter
imports the **working** tree; the two coincide only when it is clean, and nothing checked that.

So the stamp carries two values (`fs-evidence`, PB-53): the commit, and a hash of
`git status --porcelain` — `clean` for an unmodified tree, and varying with any modification,
staged or not, **tracked or not**. Untracked files count as dirty deliberately: the archetypal
accident is a new module that is imported and not yet added, and a guard diffing tracked content
would call exactly that case clean. An evidence run refuses to start on a dirty tree;
`--allow-dirty` runs anyway and stamps the artefact `NOT CITABLE`, so a development run stays
possible and can never be mistaken for evidence.

`fs-exit-criteria` now reads the stamp rather than only the prose hash. A missing stamp is a
**warning**, not an error — every artefact predating the guard lacks one, and failing them would
either block the milestone or invite the stamps to be pasted in by hand, which is the disease and
not the cure. A stamp that is present and contradicts the row is an error, because that can only
happen to a run made after the guard existed.

The general rule, which is the one to carry: **a record of provenance that the author writes is a
record of intention. Provenance has to be taken from the system at the moment the work happens, or
it records what someone meant to do.** That is the same principle as deriving a status table from
its evidence rather than from an author's edit — the entry from two days ago — applied to the
evidence itself.

### 2026-09-21 · Three runs of one measurement, and only the third was about the model

The first M4 metric on D-07's published split took three runs. The model barely changed. What
changed was what the numbers were about.

**Run 1** printed: model AUC 0.998, best single feature **1.000** (`days_since_sim_swap`), margin
**−0.002**. I wrote a backlog item saying the benchmark had a second perfect separator.

**Run 2** printed the denominator. Those 1.000s were over **660 rows holding two confirmed fraud**.
`auc` drops NaN with its labels — right, a structurally missing value is not a low value — so the
feature was scored on the accounts that had a SIM swap while the model was scored on all of them.
Two numbers over different populations had been subtracted and called a margin.

**Run 3** made the sample representative: an even stride across each period instead of its tail.
The feature stopped being the strongest at any coverage. The strongest became
`velocity_ratio_1h_vs_30d` at 0.871 on 100% of rows — the baseline PB-46 already knew about — and
the model's margin came out at **+0.115**. The finding from run 1 did not shrink; it evaporated.

Two separate defects, and it is worth keeping them apart.

**The comparison was cross-population.** PB-46's rule is "report every metric as a margin over the
single-feature baseline". The code implemented that rule literally, and "the baseline" silently
meant "over whatever rows that feature happens to be defined on". A rule followed to the letter and
broken in substance is the same shape as a guard that checks the input it was written for rather
than the output it protects — and that shape appeared three times in three days: in the parameter
digest (PB-41), in the fingerprint that replaced it (PB-54), and here.

**The sample was the tail.** Both early runs took the last N rows of each period, so they trained
on about a week no matter how deep the corpus read — the sample size decided the window and the
corpus depth did nothing. Deepening the corpus from 400,000 to 560,000 rows changed the answer not
at all, which should have been the tell. A stride over a pool already in timestamp order costs the
same, spans 235 days, and needs no seed.

#### The habit

**The first version of a measurement is the one most likely to be about its sampling rather than
about its subject.** Not wrong arithmetic — the AUCs were all computed correctly. The question
"what rows is this over?" simply had a different answer for each number being compared, and
nothing in the output said so.

So the report prints coverage and fraud count beside every baseline, and refuses to subtract a
partial-coverage feature from the model at all. The denominator arrives *with* the number rather
than after it, which is the only arrangement under which a reader can catch this before a backlog
item gets written about it.

#### And the guard reintroduced a solved problem

`fs-evidence`, built the same afternoon to stamp provenance, captures its child's output instead of
streaming it. A 34-minute run is now completely silent — which is the failure the notebook already
has an entry about, from the 106-minute run that printed nothing. Recorded as PB-57. Fourth
instance of *knowing a failure mode does not immunise against it*, and the second where the
immunity was expected to come from having just written about it.

### 2026-09-21 · The fingerprint read three columns of fourteen — seventh instance

Recorded separately from the run that found it, because it belongs to a family and the family is
now long enough to be the point.

`dataset_fingerprint` exists to answer one question: *is this report still about this dataset?*
PB-40 gave the generator a device-sharing mechanism, which rewrote `device_fingerprint` for 504
devices and changed nothing else. The fingerprint returned **the identical value** for the old
draw and the new one. `SAMPLED_COLUMNS` hashed three columns per table and `device_fingerprint`
was not among them.

#### The family, in order

1. A licence check that ran over an empty scope and passed for three milestones.
2. E1's grouping check, which ranged over folds rather than over units of history.
3. D-08's ceiling, measured on the dataset's **columns** and quoted as a claim about the
   **features** — the fourth instance was the one that made E3 fail.
4. …and two more of the same "control on inputs" shape recorded at M3.
5. **PB-41:** the parameter digest answered "did the parameters change?" while the report needed
   "is this about this dataset?". The fix was a second digest over the output rows.
6. **PB-54, this one:** that second digest then sampled three columns of fourteen.
7. **PB-55, the next morning:** "a margin over the single-feature baseline" implemented as a
   margin over a feature measured on a *different set of rows* from the model.

#### What is new at seven

The early instances were scope errors findable by reading: name the object the check ranges over,
name the object the claim is about, compare the nouns. Five and six are not findable that way,
because the check's scope is not written down anywhere near the claim — it is in a constant two
files away, and the claim is in a docstring that is *correct about the check* and silent about the
gap.

So the question that finds the early ones — *what does this range over?* — has to become a
question about distance: **how far apart are the definition of the check and the statement of the
claim, and is anything keeping them in step?** For PB-54 the answer was "a tuple in another
module, and nothing". The fix is not a better list of columns; it is removing the list, so that
the sample is the row and there is no scope left to get wrong.

That generalises better than "check more things": **prefer a guard with no scope parameter to a
guard with a well-chosen one.** A scope that can be set correctly can be set incorrectly, and
usually will be, one refactor after the person who chose it stopped looking.

### 2026-09-22 · Prediction, recorded before the draw exists

The owner has directed that `fraud.takeover_lead_minutes` gain a long tail (PB-56). It is `[5, 60]`
drawn uniformly: every SIM swap or device change that enables a takeover is followed by the drain
inside the hour, with no long tail and no unexploited event. C-11 has said since M2 that part of
the event-delay channel's separation is an artefact of that window rather than of the scenario.

This entry is written and committed **before** the regeneration, so the prediction cannot be
adjusted to the result.

#### The change

`takeover_lead_minutes` becomes the **bounds** `[5, 43200]` — five minutes to thirty days — with
the draw a lognormal of median `takeover_lead_median_minutes = 45` and
`takeover_lead_log_sigma = 1.8`, clipped to those bounds. Measured over 200,000 draws: 25% inside
13 minutes, median 45, **43.6% beyond an hour**, 95% inside 14 hours, **2.7% beyond a day**, 99%
inside two days. Provenance stays ASSUMED and says so: no publication read in the 2026-09-18
sourcing pass gives takeover-to-drain delays for these markets, and a lognormal is chosen because
it is the ordinary shape for a delay — a mode early, a tail that does not end — and not because
any source supports these two numbers.

A mixture of "fast" and "slow" leads was considered and rejected: two clusters would be structure
of its own, and a model could learn the gap between them as readily as it learns the old tight
window.

#### The predictions

1. **The event-delay channel falls from 0.758.** It is `max(AUC, 1−AUC)` for "seconds from an
   account event to that account's next transaction", and it separates because fraud is *fast*
   while a legitimate account's next transaction lands whenever it lands. Moving 44% of fraud
   leads past an hour and 2.7% past a day puts that much of the fraud distribution inside the
   legitimate one. **I predict 0.68–0.74.** A fall of less than 0.01 refutes the mechanism claim
   C-11 has carried for four days — it would mean the separation never came from the window.
2. **`days_since_sim_swap` stops being a candidate for the strongest partial feature.** It already
   stopped when the sample was made representative, so this is the weaker prediction; what it
   adds is that the effect should now survive a tail-sampled run too.
3. **The single-feature ceiling gate still passes**, because it measures columns and the delay
   channel is reported rather than gated; and **the model's margin over the velocity baseline does
   not move materially**, because none of this touches velocity. If the margin moves by more than
   its interval, something other than the lead window changed and I have mis-attributed the
   effect.

The first is the one to hold me to.

### 2026-09-22 · The prediction held, and the number it produced is more interesting than the prediction

Predicted before the draw existed (committed at `0138099`): giving `takeover_lead_minutes` a long
tail would drop the event-delay channel from **0.758**, into **0.68–0.74**, and a fall of less
than 0.01 would refute the mechanism claim C-11 has carried since M2.

Measured on the regenerated draw (`6abde44e`): **0.730**. Inside the band, at its top. The
mechanism claim is confirmed — part of that separation *was* the assumed window — and the single
-feature gate is untouched at 0.706, because it measures columns and the lead is not one.

#### What the residual says, which is the part I did not predict

The channel fell by 0.028. Against a baseline of 0.5 that is **11% of the excess**, so roughly
nine tenths of the delay separation is the *scenario* and one tenth was the window. I had written
the prediction expecting to learn "how much of this is an artefact"; the answer is "much less than
the framing implied".

That reframes four days of hedging. C-11 said the interpretation was "part designed causal signal,
part artefact of an assumed schedule" and could not say in what proportion. It can now: the
artefact was the small part. A SIM swap shortly before a drain is the scenario working, and saying
so is now a measurement rather than a hope.

It also means the hedge was **doing work in the wrong direction**. Every quotation of the delay
channel has carried a caveat implying the figure might be mostly artefact. It was not, and the
caveat was free to write and cost nothing to leave in place, which is exactly how a caveat becomes
permanent. The test for keeping one: *what measurement would remove it?* If there is no answer,
the caveat is a decoration. If there is — and here it was one parameter and one regeneration —
then leaving it unmeasured is a choice, and four days is a long time to choose that.

#### On the prediction landing at the top of the band

0.730 against 0.68–0.74 is confirmed but poorly centred: I expected a larger fall than I got. The
error is legible. I reasoned from the *fraction of fraud leads moved past an hour* (43.6%) and not
from what the channel actually measures, which is fraud leads against **legitimate** next-
transaction delays. Those are mostly hours to days, so moving a fraud lead from 20 minutes to 90
minutes leaves it well inside the fraud end of the ordering; only the 2.7% beyond a day cross into
the legitimate mass in a way that changes a rank. The right predictor was the tail weight past a
day, not the weight past an hour — and it was in the rationale I had just written.

### 2026-09-22 · Leave-one-country-out carries no weight, and the ablation said so first

PB-46's owner decision, four days old, read: "leave-one-country-out and the novel sub-variant
carry the weight the headline AUC no longer can". The reasoning was sound — once one velocity
feature reaches 0.894, a headline AUC stops discriminating between a model that learned fraud and
one that learned a threshold, so the load moves to the generalisation experiments.

Measured, on 60,000 held-out rows with 551 fraud:

| Country | In-sample | Unseen in training |
|---|---:|---:|
| KE | 0.996 | 0.996 |
| RW | 0.994 | 0.993 |
| TZ | 0.981 | 0.979 |
| UG | 0.975 | 0.976 |

Removing a country from training **entirely** costs nothing measurable. LOCO is not a demanding
test on this benchmark, and half of PB-46's plan is refuted.

#### The ablation had already said it

Every one of the ten feature groups can be removed for ≤0.031 AUC, and eight for ≤0.001. Read
alone that says every group is worthless, which cannot be true of a model at 0.991 — so I added
the complementary experiment, keeping only one group at a time. **Four disjoint groups each reach
0.845 or better alone**: counterparty 0.949, velocity 0.871, temporal 0.861, geographic 0.845.

That is the same fact as the LOCO result, one level down. The benchmark can be solved several
different ways, so removing any one route — a feature group, a country — leaves the others
intact. A generalisation test works by removing the thing the model relied on; when the model
relies on four interchangeable things, removing one measures nothing.

#### What I should have predicted and did not

PB-46 *named this mechanism* when it set the plan: "burstiness is not country-specific, so a
velocity threshold transfers trivially". It was written as a risk to watch. It was in fact a
prediction, and it was available four days before the measurement — no run required, only the
observation that the generator draws fraud from one scenario library applied to every country.

So the failure was not the plan; it was reading a mechanism as a caveat. The two are
distinguishable by one question: **does this sentence say what a measurement would show?** "LOCO
may transfer trivially" is a hedge. "LOCO will transfer trivially, because the scenarios are not
country-specific" is a prediction, costs nothing more to write, and would have been recorded and
tested. The same distinction retired C-11's delay-channel hedge this morning, which is twice in
one day that a caveat turned out to be a measurable claim nobody had measured.

#### What is left holding the weight

The **novel sub-variant** — a fraud shape present only in the test period. It is a *temporal*
hold-out, not a geographic one, and no amount of cross-country transfer helps a model learn a
pattern that does not exist in its training window. That is the experiment M4 still owes, and it
is now the only one of PB-46's two that is still standing.

The honest summary for the paper: on this benchmark, geographic generalisation is easy and says
nothing; temporal generalisation to an unseen variant is the test that can fail.

### 2026-09-22 · Two generalisation experiments, both chosen before anyone asked if they could fail

PB-46, four days ago, moved the evidential weight off the headline AUC — correctly, since one
velocity feature reaches 0.89 — and onto two experiments: **leave-one-country-out** and the
**novel sub-variant**. Both are now measured. Both are null.

| | In-sample | Held out |
|---|---:|---:|
| KE / RW / TZ / UG, country removed from training | 0.996 / 0.994 / 0.981 / 0.975 | 0.996 / 0.993 / 0.979 / 0.976 |
| Novel variant, never in the training period | base recall 95.1% | **novel recall 100.0%** |

The unseen fraud shape is caught *more* often than the familiar one.

#### One cause, and it was knowable in advance

The benchmark encodes fraud as **bursts**. The novel variant's novelty is in the *lead time* — a
drain delayed by days rather than minutes — and not in the transaction pattern, which is still a
burst. Every country's fraud is the same burst, drawn from one scenario library with no parameter
keyed by country. Four disjoint feature groups each reach ≥0.845 alone because each is a different
view of that one structure.

So a country hold-out withholds no mechanism, a variant hold-out withholds no pattern, and an
ablation removes no unique signal. Three experiments, one reason, and the reason is a property of
the generator that was fully visible in its source the whole time.

#### The habit this breaks

**I chose both experiments as headline evidence before checking whether the benchmark could make
either one informative.** That is the error, and it is not the same as being wrong about an
outcome. An experiment is a question put to a dataset; before running it you can ask whether the
dataset is capable of answering — and here the answer was in `fraud.yaml`, in the absence of any
country key, and in the novel variant's own definition, which changes a delay and nothing else.

PB-46 even *named* the mechanism when it set the plan: "burstiness is not country-specific, so a
velocity threshold transfers trivially". It was written as a risk to watch rather than as a
prediction to test, which is the third time this week a hedge turned out to be a measurable claim
nobody had measured — the delay channel this morning, C-11's proportion, and now this.

The check to run before promoting an experiment to headline evidence: **what property of the data
would have to hold for this to be able to fail, and is it there?** For LOCO: fraud mechanisms
differing by country. For the variant hold-out: a *pattern* differing, not a schedule. Neither was
present, and neither would have taken a run to establish.

#### What not to do about it

Not to make the countries differ. Not to design a harder variant. Engineering the data until the
experiment becomes informative is tuning the benchmark to produce a result, and it is the failure
this project spent two milestones learning to avoid — the owner's direction, and the right one.
The honest output is a null result reported as a null result, plus a statement that country-level
generalisation belongs to real-data validation and not to this benchmark.

**And what is left is better than what was lost.** The latency-explainability frontier does not
depend on the data being hard: explanation cost grows 35× from the smallest tree configuration to
the largest while accuracy moves inside its own interval. That is a claim about the model and the
SHAP algorithm, and an easy benchmark cannot weaken it.

### 2026-09-22 · Pre-registration: one non-burst fraud variant, before the draw exists

Committed before the dataset is regenerated and before any measurement of it exists (ADR 0028).
PB-46's two generalisation experiments both came back null today: leave-one-country-out changes an
AUC by at most 0.002, and the novel sub-variant is *easier* than the familiar one (100.0% recall
against 95.1%) because it differs from its parent scenario only in timing, not in shape — still a
burst. One cause explains both and the redundancy finding: this benchmark encodes fraud as bursts,
and every country, variant and feature group is a view of that structure.

The owner's direction: do not invent country differences (there is no source to ground them in —
that would be circular, ADR 0028 §Decision 1). Do add one variant whose *mechanism* differs, drawn
from a real, independently-documented fraud typology rather than from what would defeat this
model.

#### The variant: `reversal_scam_social_engineering`

**Typology.** "Sent by mistake, please return" scams: a scammer contacts a victim, convinces them
that a payment was sent in error (a real payment, a fabricated one, or an actual small transfer
followed by a much larger "correction" request), and asks the victim to send back an amount
described as the mistake. The transfer is the **victim's own act**, not an attacker's — no
compromised account, no enabling SIM swap or device change. It is a **single transaction** — the
whole incident is the one transfer the scam depends on, not a drain. And it typically goes to a
**counterparty the victim has established contact with** in the course of the scam, which by the
time of the transfer is not a fresh, unrecognised payee to the account.

**Mechanism, as implemented.** Drawn independently per (customer, test-period month) — not from
`mule_account`'s own incident budget, whose test-period allocation tops out at ~15 incidents even
at 100% conversion (measured against the 1,000,000-row plan before any probability was set, a
sizing calculation and not a result). One row. The counterparty is drawn from the customer's own
`counterparties` list — the same pool their legitimate P2P transactions already draw from —
verified directly against `Population.customer(i).counterparties`, not inferred from rows a small
draw happened to have already written (an earlier check against realised legitimate rows gave a
false negative for exactly that reason, corrected before this entry was written). The amount uses
a dedicated multiplier (3.5×, against `mule_account`'s own 2.0×) because the scam's request is
described as an unusual, specific "overpayment" — notably larger than a habitual transfer — and is
never a round sum, matching the "return exactly what I mistakenly sent" framing.

**What this removes, stated plainly rather than left implicit.** Burst-structure (one row) and
counterparty-novelty (an established payee) — the two axes PB-60 and PB-61 found doing the work.
Amount, channel and timing remain available to a detector. This is not a claim of invisibility on
every axis; it is a claim about which two axes are absent, chosen because those are the two this
benchmark's models are shown to rely on, not because the model's SHAP ranking was checked feature
by feature and a value picked to duck under each one.

**Sizing.** `reversal_scam_probability = 0.005`, chosen against ~17,000 test-period customer-month
draws to land near 60-70 rows — the same order as `novel_esim_delayed_drain`'s realised 73, and
well clear of the "fewer than 30 fraud rows, read as direction not measurement" line this project's
own reporting convention already draws. A power calculation, made before generating: it decides
how much evidence the experiment produces, not whether the model catches it.

**Verified mechanically before this entry was committed** (`test_the_reversal_scam_variant_is_one_
transaction_to_a_known_payee_in_the_test_period`, dataset suite; full realism gate check re-run at
200,000-row scale, all gates still pass): exactly one row per selected incident, every counterparty
on the account's own established list, every timestamp at or after the test-period boundary, zero
rows in any earlier month.

#### The prediction, recorded before the draw exists

**I predict the model still detects a meaningful share of this variant, though less well than the
base scenarios, and the honest range is wide because the removed axes (SHAP-ranked #1 counterparty
novelty, and the whole velocity/temporal group) carried most of the separation measured today.**
Concretely: recall at the same 1%-FPR threshold used for the novel-variant comparison, **between
0.15 and 0.55** — well below `base`'s 95.1% and `novel_esim_delayed_drain`'s 100.0%, but not zero,
because amount (a 3.5× multiplier is still an unusual amount for the account) and the counterparty-
confirmed-fraud-90d feature (the account's history, if any prior fraud touched this exact
counterparty pool) remain live.

**What would refute the mechanism claim in either direction.** Recall above roughly 0.7 would say
the amount and channel signal alone still catch most of it — a real result, but one saying this
particular variant did not remove enough of what the model uses, not that non-burst fraud is
undetectable in general. Recall below roughly 0.10 would say burst-structure and counterparty-
novelty account for nearly all of this benchmark's detection power on their own, which PB-60's
redundancy table already makes plausible (four independent groups each ≥0.845 alone means a lot of
signal is concentrated in a few mechanisms) but has not yet been measured directly on a case built
to lack both at once.

This entry will not be edited after the measurement. The result — whichever direction it lands —
follows in its own entry, dated after the regeneration this commit precedes.

### 2026-09-22 · The reversal-scam result: below the predicted range, not inside it

Measured against `dataset/output/features_full.parquet` at fingerprint `d8083dbc`, the full test
period (101,909 rows, so every one of the variant's rows is scored, not a sample of them):
`docs/benchmarks/m4_battery_pb61.txt`.

**`reversal_scam_social_engineering`: recall 6.1% (4 of 66 fraud rows detected), Wilson 95%
interval [2.4%, 14.6%]. AUC 0.656 ± 0.072.** Against `base` at recall 94.2%, interval [92.4%,
95.6%], on 827 rows. The two intervals do not overlap — at 66 fraud rows a single point estimate
would not be enough on its own to say the variant is genuinely harder to detect rather than the
result of sampling noise, but the non-overlap is what makes this a supported comparison rather than
one asserted from the point estimate alone.

**This falls below the pre-registered prediction range (0.15–0.55), not inside it.** The
pre-registration's own refutation condition for "recall below roughly 0.10" is the one that fired:
"burst-structure and counterparty-novelty account for nearly all of this benchmark's detection
power on their own." The prediction undershot in the direction of *underestimating* how much the
model depends on those two axes — the lower bound of the predicted range (0.15) was itself set from
"amount and the counterparty-confirmed-fraud-90d feature remain live," and at 6.1% those two
signals are evidently much weaker load-bearing features than the prediction assumed, once the
burst shape and payee novelty are both absent.

**What this changes.** PB-59 (LOCO) and PB-61's first variant (`novel_esim_delayed_drain`) were
both null because the axis they varied was never the axis the model uses. This is the first
experiment in the M4 generalisation series to measure the model actually failing at something —
and it fails hard, not marginally. It is also the sharpest confirmation yet of PB-60's redundancy
finding: four disjoint feature groups each reaching AUC ≥0.845 alone was already evidence that
detection power concentrates in a few mechanisms; a purpose-built case lacking two of them landing
at 6.1% recall is that same finding, now demonstrated on a single held-out shape rather than
inferred from ablation margins.

**What this does not change.** The pre-registration was explicit that this is one variant, chosen
from one typology, and that a low recall here does not generalise to "non-burst fraud is
undetectable" — it generalises only as far as "this benchmark's two strongest signals are absent in
this shape, and the remaining signals do not compensate." Whether that holds for other non-burst
typologies (romance scams, invoice fraud, authorised-push-payment variants with different
counterparty patterns) is untested and out of scope for this entry.

The 2026-09-22 pre-registration entry above is unedited. This entry supersedes nothing in it; it
reports the measurement that entry said would follow.

### 2026-09-22 · The m2-complete tag sits on a commit whose python CI job fails

Found when `main` was fast-forwarded from the M1 merge (`a7e6896`) to `m2-complete` (`ed7a8d9`),
the first time either M2 or M3 reached `main`. The `ci` run that push triggered failed at
`mypy --strict`, before pytest ran:

```
dataset/tests/test_generator.py:501: error: Item "None" of "Match[str] | None" has no attribute "group"  [union-attr]
dataset/tests/test_generator.py:520: error: Item "None" of "Match[str] | None" has no attribute "group"  [union-attr]
```

Reproduced locally at the same commit with the lockfile's own mypy (2.3.1). The calls are
`re.search(...).group(1)` with no `None` check, introduced by `1ff1d2f` ("refuse a run too small to
stage every fraud scenario", 2026-09-18) — seventeen commits into M2's review work and one day
before the tag.

**What the record said.** `docs/reviews/M2/gate-evidence.md` cites `ci` run 35323956798 as green
on every job, at `817db76`, which it calls "the current branch head". It also states the rule
this breaks, in its own words: the `ci` citation "should be moved forward whenever the head moves
— otherwise the tag rests on a commit that is not the one being tagged". The head then moved
seventeen commits, including `1ff1d2f`, and the tag went on `ed7a8d9` with the citation still at
`817db76`.

**What CI actually recorded on the tagged commit.** Before 2026-09-22, no `ci` check-run exists on
`ed7a8d9` at all — the python, java, frontend and governance jobs never ran there, although `ci`
triggers on every push; why is not established from the API alone (the per-ref
`cancel-in-progress` concurrency group is one candidate, not a verified cause). The `stack` and
`devcontainer` workflows did run on 2026-09-19, starting at 08:35 UTC — ten minutes *after* the
tag (10:25 +0200, 08:25 UTC), so they could not have informed it: stack succeeded, and **`build
devcontainer, post-create make ci, smoke test inside` failed**. So the tag was placed with no CI
result on its commit at all, and the first two results that commit ever received included a
failure nobody read.

**How it was fixed, and why that did not surface it either.** The `None` checks were added in
`5f0a8a1` (2026-09-19), a `feat(ml)` commit declaring the 44 features, whose message does not
mention the fix. M3's CI went green, `m3-complete` is green on every job including the
devcontainer, and nothing pointed back at the tag.

**Not fixed forward on `main`.** A commit on `main` at `m2-complete` would break the ancestry, and
`main` could then no longer fast-forward to `m3-complete`. `main` was red at `m2-complete` only
transiently and was fast-forwarded to the green `m3-complete` the same morning. The tag is not
moved or re-cut: it records what was signed off, and this entry records what that was.

**The pattern.** The same one PB-52 and PB-53 were about, on a tag instead of an artefact: the
record ran ahead of the facts. A citation written when a commit was the head was left standing
after the head moved, and the tag inherited a green it never had. It was caught only because
`main` made the tagged commit build again.

**The structural fix.** PB-53's `fs-evidence` stamp already makes an evidence artefact carry the
commit and tree state it was produced from, rather than the one its author believed. The same
rule belongs on a milestone tag: **a milestone tag requires a green run of every CI job on the
exact commit being tagged**, cited by run ID against that SHA, not a green run on an ancestor.
A citation to any other commit is evidence about a different tree.

### 2026-09-22 · The M4 gate: nine of eleven, and three runs to get there honestly

The M4 milestone review (`docs/reviews/M4/milestone-review.md`) found the model and gate
machinery missing, and it was built: D-05's ensemble, D-06's Isolation Forest, the eleven M4 gate
metrics with stratified bootstrap intervals, baselines, ablations, ONNX parity, LaTeX tables. The
gate was then run three times on the same test period, and each run is committed and logged in
`docs/benchmarks/test_set_access.jsonl`, because each is a look at the test set.

- **Run 1** (`m4_gate_d8083dbc`, at `590ca14`) exposed two defects. The LightGBM ONNX export
  disagreed with the evaluated model on 398 of 101,909 rows, by up to 0.229; and precision at "1%
  FPR" printed above its own theoretical ceiling, because isotonic ties left the realised FPR at
  0.47%. Neither fix touched a model setting or a threshold.
- **Run 2** (`_v2`, at `07ed1ed`) passed parity. It still trained with a random-fold target
  encoding, which E1 forbids — a settled rule recorded on FR-02-03 that the review's first pass
  had not checked.
- **Run 3** (`_v3`, at `d40fca2`) is the declared result.

**Nine of eleven pass.** AUC 0.970 [0.962, 0.977], recall at the 1% FPR budget 0.871, F1 at 0.60
0.821, FPR at 0.85 0.000, channel AUCs 0.953 / 0.953 / 0.979, SHAP coverage 1.000, ECE 0.001.
Every ranking metric clears both of PB-46's baselines: the headline AUC is +0.091 over
`velocity_ratio_1h_vs_30d` (0.879) and +0.311 over the best trivial rule, and the narrowest
margin is USSD's +0.049. On this benchmark a random forest and XGBoost alone are statistically
indistinguishable from the ensemble (DeLong p 0.21 and 0.67), and logistic regression sits 0.005
below it (p 0.0049): the velocity-separability of PB-46 again.

**Two fail, both at D-02's 0.60 flag threshold, and are recorded rather than tuned:** recall
0.736 [0.707, 0.765] against 0.88, and FNR 0.264 against 0.12 — one fact, stated twice, since FNR
is 1 − recall there. What the committed numbers already say about why, without another look:

- **The threshold is not the only constraint.** Even at the 1% FPR budget — a threshold far below
  0.60, flagging 0.96% of legitimate rows — recall is 0.871. D-02's reference point (recall 0.88
  at about 0.28% FPR) is beyond this model on this training sample at any threshold the artefact
  shows.
- **At 0.60 the model is conservative, not wrong:** precision 0.927 at an FPR of 0.06%. A
  calibrated probability of 0.60 means "60% of rows like this are fraud", and most fraud rows here
  do not reach that confidence.
- **It is not one seed:** over five refits recall at 0.60 is 0.744 ± 0.041.
- **Training volume is a candidate cause, not a finding:** the model sees 30,000 training rows
  and 260 frauds of a period holding 792,162 (PB-67). Testing that means a larger training sample
  reported beside this one, not a new threshold.

F1 at 0.60 passed in run 1 (0.819), failed in run 2 (0.795) and passed in run 3 (0.821). It sits
at the threshold, and its five-seed spread in run 3 (0.820 ± 0.020) covers it.

**The reversal-scam figure, restated.** The PB-61 battery evidence was produced with the
forbidden encoding, so it was re-run on the same cache. The variant is caught at **9.1% (6 of 66),
Wilson 95% [4.2%, 18.4%]**, against 94.4% for base; it was 6.1% [2.4%, 14.6%]. The intervals
still do not overlap and the point estimate is still below the pre-registered 0.15–0.55. **What
changed is that the interval now reaches 18.4%, past the prediction's lower edge**, so "below the
predicted range" holds for the point estimate and no longer for the whole interval. The
pre-registration entry above is unedited; this is the correction the result needed, stated
against it.

**C-6, restated.** With the E1 encoding the ensemble's seed-to-seed AUC standard deviation is
−0.6% against XGBoost alone (no reduction) and +12.5% against LightGBM alone. The first
measurement's +62.9% was LightGBM being far more seed-sensitive under random-fold encoding.

## M5 — the scoring service

### 2026-09-22 · A small error, amplified: raw parity says nothing after a steep transformation

**What was measured.** M5 serves M4's evaluated ensemble through ONNX Runtime. On the gate model's
whole test period (101,909 rows, draw `d8083dbc`, bundle built at 894fbb4), E.4's parity test
passes with a wide margin: the ONNX probabilities differ from the native boosters by at most
**8.3e-7** per model and **4.1e-7** after the 0.55/0.45 combination, against a bound of 1e-5. The
*calibrated* ensemble score, the number every threshold is read on, differs by up to **1.97e-4**,
on **37 rows** above 1e-5. None of them crosses a risk tier at 0.60 or 0.85.

**Why.** The isotonic calibrator is the same function on both paths. It is exact when reproduced
as knots and interpolation: the difference between the knots and scikit-learn's own `predict` is
0.0 on identical inputs. But the fitted calibrator is near-vertical on one segment, with a slope of
about **1.2e5** between two adjacent thresholds, where the calibration split's labels jump over a
tiny range of raw scores. A 4e-7 difference entering that segment leaves it roughly 1e5 times
larger.

**The general point.** A component's error can be small and still be amplified downstream, so a
parity bound on raw outputs says nothing about outputs after a steep transformation. Bounding the
calibrated score at 1e-5 would have been unmeetable by any inference path that is not bit-identical
to the native boosters. Not bounding it at all would have hidden the one place the difference
could matter: a decision threshold. The bound has to be stated in the space where the consequence
is.

**What M5 does with it** (`ml/src/fraudshield_ml/models/build.py`, ADR 0032 amended):
- The build bounds E.4's quantity, raw probabilities, at 1e-5.
- It requires **zero risk-tier changes** over the test period, which is the consequence that
  matters.
- It records the calibrated gap and its row count in the bundle's provenance, where a new model's
  steeper calibrator would show.
- The owner confirmed this handling on 2026-09-22.

**What it does not establish.** The slope is a property of this calibrator on this calibration
split (20,000 rows, 172 frauds). A different draw or a larger split will have its own steepest
segment, and possibly a threshold inside it, which is why the tier check runs on every build rather
than being argued once here.

### 2026-09-22 · AUC moved 0.009; a fifth of the detections disappeared

**What was measured.** M5 serves M4's evaluated ensemble. Four of its trained features read state
that no deployed component writes — transaction outcomes and account reference state — so in
production they are constants: `counterparty_confirmed_fraud_90d` is 0, `geo_cell_fraud_rate_30d`
is the prior, `days_since_sim_swap` is NaN, and `synthetic_identity_score` silently loses terms.
The gate model was rebuilt from `features_gate_d8083dbc.parquet` at seed 1 and scored over the
whole test period (101,909 rows, 985 frauds), replacing each piece of state with what serving
actually supplies:

| Serving state | Test AUC | Risk-tier changes | Frauds reaching 0.60 |
|---|---|---|---|
| as trained | 0.9700 | — | 725 |
| outcomes missing (no labels consumer) | 0.9619 | 235 | 585 |
| SIM swaps missing (no topic exists) | 0.9699 | 25 | 712 |
| **both, as M5 ships** | **0.9611** | **254** | **563** |

Measured twice, independently: by the author and by the independent Principal Reviewer, who raised
it (`docs/reviews/M5/principal-review.md`, finding 1).

**The point.** **AUC fell by 0.0089 — about one half-width of the gate's own 95% interval
(±0.0075), so the two intervals overlap heavily and the drop would not be called a regression —
while
162 of 725 frauds stopped reaching the 0.60 flag threshold: a 22% fall in detections at the
operating point the system actually decides on.** A ranking metric averages over every pair of
rows; a threshold reads one row at a time. Degrading a feature that matters near the threshold and
nowhere else moves the second and barely touches the first. A gate expressed in AUC will pass a
system whose decisions have materially changed, and the closer a model's AUC is to 1, the less room
there is for such a change to show up in it at all.

**Why it was invisible.** Nothing failed. Every feature had a defined value, the parity tests
compared serving against the batch path with the *same* inputs, and the store answered every read.
The skew lives between "the store returns a feature" and "something writes what the feature reads",
which no per-feature test covers. It was found by asking who calls the store's writers — the
question "where does this data come from in production?" rather than "does this code compute the
right thing?".

**What follows for reporting.** Any claim of the form "serving matches training" needs to name the
quantity it holds for. Parity within 1e-9 on the same inputs says nothing about whether the inputs
are the same in production. The honest statement is the table above, and the M5 rows say so: the
owner carried the gap (ADR 0034, option 3) with acceptance tests against M6/M9, a metric counting
every affected read, an alert on it, and M10 required to re-measure the skew and find zero.

### 2026-09-23 · A fake is a hypothesis about an API, and 537 tests can confirm it wrongly

`m5/scoring` had been reviewed four times — a principal review, a re-review of its fixes, a
verification pass, and an adversarial pass over the promotion path — and CI was still red on one
test. The failure:

```
RegistryError: GET /api/2.0/mlflow/registered-models/alias: HTTP 400
{"error_code": "INVALID_PARAMETER_VALUE", "message": "Registered model alias production not found."}
```

**MLflow 3.16 reports an unset alias with HTTP 400 `INVALID_PARAMETER_VALUE`.** Everywhere else in
its registry API an absent thing is HTTP 404 `RESOURCE_DOES_NOT_EXIST`, and that is what
`MlflowRegistry.by_alias` was written to expect. So the client raised on a state that is not an
error at all: *no production alias set yet*, which is the state every first deployment starts in.
Publishing with `--alias production` reads the outgoing alias before moving it, so every first
promotion into a fresh registry failed.

**Why no review caught it.** `FakeMlflow`, the in-process double the serving tests run against,
returned the 404 shape. It encodes the author's reading of the REST API — and every test that used
it therefore tested the client against that reading, not against MLflow. 537 tests passed. Four
reviewers read the code, mutated it, probed it adversarially, and found eleven real defects in the
promotion path between them; none found this one, because each reasoned about the same double. The
only thing that caught it was the single `requires_docker` test that starts a real MLflow container,
and its whole purpose is stated in its docstring: "The fake above encodes my reading of MLflow's
REST API; this checks it against MLflow 3.16.0, the image docker-compose.yml pins."

**The rule.** A test double is a hypothesis about an external service, and tests written against it
confirm the hypothesis rather than the service. So: **any fake standing in for an external service
needs at least one test that exercises the real thing, and the fake's behaviour must be pinned
against what that test observes.** Not a test per behaviour — a real-service test per *fake*, whose
job is to catch the double drifting from its subject. When it fires, the fix has two halves: the
client, and the double. Fixing only the client leaves the next wrong assumption undetectable.

The fix here did both. `by_alias` accepts either wording (and still raises on an
`INVALID_PARAMETER_VALUE` that is not about an absent alias, so a real client bug cannot hide as
"no alias set"); `FakeMlflow` now answers as MLflow 3.16.0 does, with two tests pinning both shapes
so this class of defect fails in the fast suite instead of only in CI.

**The uncomfortable general point.** Mutation testing, adversarial probing and independent review
all operate *inside* the world the test fixtures define. They cannot see a boundary that is
mis-drawn, because every instrument agrees with every other. Only contact with the real dependency
is outside that world. This is worth stating in the paper's limitations: the verification effort
reported for this system is large, and it was still blind in exactly one direction until a
container was started.

### 2026-09-24 · A flag that enables a safeguard is not the safeguard running

Every commit I made in this session ran with `git -c core.hooksPath=.githooks commit`. **There is
no `.githooks/` directory in this repository.** The project installs its hooks into the common git
directory (`make bootstrap`, `default_install_hook_types: [pre-commit, commit-msg]`), and they were
installed and working. Pointing `core.hooksPath` at a directory that does not exist disables hooks
silently: git finds no hook to run and reports nothing.

So for the whole session the commit-msg and pre-commit checks did not run on a single commit, while
I believed they were running and said so. No `--no-verify` was ever used -- and the effect was
exactly the same as if it had been. The flag was written to *comply* with the rule that forbids
`--no-verify`; it defeated it instead.

**What it cost.** Seven commit messages violated G.3's 72-character body limit, 77 problems in all,
and CI's commit-message job had been failing on this branch since `f606234`. I did not see it
because I had no GitHub credentials and was reading CI second-hand, where the red run was
attributed to a test failure that was also real. Fixing it needed a history rewrite of four commits
another agent had already merged, which cost that agent a re-merge. Two trailing blank lines
slipped through the same hole.

**The rule.** *Verify that a safeguard is running; do not infer it from the flag you passed.* A
configuration flag expresses an intention, and an intention that names a path, a file or a hook
that does not exist fails open and says nothing. The check is cheap and direct: run the guard by
hand once (`fs-commit-msg --rev-range …`, `pre-commit run --all-files`) and see it object to
something it should object to. A safeguard that has never refused anything in your presence has not
been observed working.

The same shape appeared twice more in this milestone and is worth naming as a family: a fake that
encodes a wrong reading of an API (2026-09-23), a guard whose condition was vacuous because it
matched a substring always present, and now a hook path that silently matched nothing. Each was a
check that reported success while checking nothing, and none of them was visible from inside the
system that contained it.

## M7 — staff identity, authorisation and audit

### 2026-09-22 · A fix whose test never reached it

This belongs with the vacuous-test entries (2026-09-19, "A test whose fixture lacks the condition it
tests passes vacuously"). It is the same defect one level up: there the *test* was vacuous; here the
*fix* was, and a test existed but never reached the fix.

**The defect.** After M7 moved staff accounts onto JPA (ADR 0071), the independent review found
that a row-locking query hands back the entity the transaction already holds, without refreshing it.
The case is an administrator editing their own account, whom the service reads as the actor before
locking the target. A full-row flush could then write an old password hash and token version back
over a concurrent password change. The fix added a `refresh` after the locking query, plus
`@DynamicUpdate`. Its test passed, and the review record said "fixed".

**The fix did not work in the case it was written for.** Hibernate 7 compares the version of the row
the locking query returns with the copy it holds. When the version has moved, which is exactly
when the copy is stale, it throws "conflicting version of entity already held in persistence
context" *from the query itself*. The `refresh` on the next line never ran, and the race answered
500 instead of 409. The test had seeded the conflict with a password change, which does not advance
the version, so the query succeeded and the refresh ran. It only ever exercised the branch where the
fix was not needed. `@DynamicUpdate` independently blocked the write-back, so every assertion held.

**How it was found.** Mutation K1 removed only the refresh, and it survived. The survivor had a
ready explanation ("defence in depth: `@DynamicUpdate` covers it"), and the review record briefly
filed it that way. The owner asked instead whether the refresh protects anything the column-only
save does not, and required either a test that fails without it or a written argument that nothing
does. Writing that test meant forcing the real interleaving: holding the row lock while a lockout
committed, which advanced the version. The first run failed *with the fix in place*, and the stack
trace pointed at the locking query, not at the refresh. The fix became lock-by-refresh,
`refresh(entity, PESSIMISTIC_WRITE)`, which has no query to reject the row (`8f6bdba`).

**It happened again the same day.** The analytic staleness bound for the session cache had a
surviving mutation too: B1, which anchors only the version tier after the read. The ready
explanation was that a missing session entry reloads both tiers, so the version tier can never act
alone. That is true for one instance and one session. It is false once another instance has cached
a second session of the same account in Redis. The test for that path kills B1 at exactly 2,000 ms
(`5b728a1`).

**The rule.** A surviving mutation is a question, not a verdict. "Equivalent" and "defence in
depth" are claims, and each needs the same evidence as any other: a test that fails without the
code, or a written argument covering every path. Two survivors were explained away with a
plausible sentence, and both sentences were wrong.

*Why it belongs in the paper:* the earlier vacuous tests were silent. This one was worse, because
it produced a false positive about a fix. The review table read "Fixed; test; mutation killed" —
killed for K2, which removed *both* defences. A reader of the record would have seen three
confirmations of a fix that did nothing in its own scenario. Counting killed mutations measures the
tests; only investigating the survivors measures the claims.
