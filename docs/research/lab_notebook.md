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
