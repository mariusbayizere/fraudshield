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
