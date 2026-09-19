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
