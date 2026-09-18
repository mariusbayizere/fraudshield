# M2 dataset review — response

Reviewer: independent Principal Review of `m2/generator` at `4a4e79d`, in its own worktree, plus an
addendum after a delegated area-4 pass returned. Verdict: **CHANGES_REQUESTED** — 1 BLOCKER,
1 blocking-grade MAJOR, 11 MAJOR, ~15 MINOR/NIT.

Owner direction for this cycle: fix BLOCKER and MAJOR only; log MINOR/NIT. That is what this record
does. Every fix below is verified by a test that fails without it, or by a measurement quoted here.

## BLOCKER 1.1 — account events carried a label oracle · FIXED

The reviewer found that fraud-planted `SIM_SWAP` and `DEVICE_CHANGE` events were written with
microsecond precision and on any day of the month, while legitimate ones were quantised to whole
seconds and confined to days 1–27. Any event with a non-zero sub-second part therefore identified a
victim's account. I reproduced it at a million rows before changing anything:

```
events 2417 | on fraud accounts 1380
sub-second != 0: 632  ->  99.84% of them sit on a fraud account
sub-second == 0: 1785 ->  41.96% (the base rate)
day >= 28: 111        ->  94.59%
```

The table is shipped in the release and the datasheet invites joining it, and no check read it.

**Fixed** in three parts:

1. Legitimate events are now placed exactly as planted ones are: any day of the real month, drawn
   from the customer's own daily profile, and to the microsecond. The day-weighting logic that
   behaviour and events both need moved into `generator/daily.py` so the two cannot drift apart
   again.
2. The checks read `account_events` for the first time. A new gate, **event construction**, runs the
   detector over each event's sub-second part and day of the month, labelled by whether its account
   carries observed fraud anywhere in the dataset.
3. `test_fraud_enabling_events_are_recorded_like_legitimate_events` asserted only that both event
   types exist — vacuous with respect to the property in its own name. It now compares the two
   populations, and a new plant-a-leak test re-quantises legitimate events and asserts the gate
   fails.

Two event properties are deliberately **not** judged, and the check says so: how soon a transaction
follows an event, and which kind of event it is. A SIM swap before a takeover is how that fraud
works — it separates the classes at 0.537 on its own — and a model is meant to learn it. Including
it made the new gate fail on a clean dataset, which is how the distinction got drawn explicitly
rather than by accident.

At a million rows after the fix: **event construction AUC 0.513, band ±0.030, PASS**.

## MAJOR 1.2 — three planted leaks passed every check · FIXED

The detectors read 2 of 16 identifier bytes and 3 of 32 token characters, and nothing read
`label_available_at`. The reviewer planted a marker in byte 5, a marker in token character 10, and a
one-microsecond label delay on fraud rows: every leakage number stayed bit-identical to the clean
baseline.

**Fixed:** the shortcut detector now takes all sixteen id bytes and the label delay; the identifier
check takes every character of every token (the length was a constant column and is gone). Three new
plant-a-leak tests, one per channel the reviewer exploited, each failing before the fix.

## MAJOR 1.3 / 1.4 — bands were not sized to the statistic they judged · FIXED

`null_auc_stderr` is the Mann-Whitney null for a single fixed score vector; both gates applied it to
a cross-validated tree's AUC. The reviewer's Monte Carlo showed it understates that statistic's null
spread by 17–26%, giving a family-wise false-alarm rate near 1 in 7 — the rate the previous commit
had claimed to remove. Separately the shortcut band was a flat ±0.03, which is 1.5 standard errors
at development scale, and a clean 40K-row dataset failed it.

**Fixed:** `cv_auc_null_band` multiplies the analytic error by `CV_TREE_NULL_INFLATION = 1.3`, and
every tree-based band — identifier, shortcut, event — is sized from its own sample with a
family-wise z. `test_the_tree_null_band_is_conservative_for_the_statistic_it_judges` re-measures the
true null spread under the exact null and fails if 1.3 stops being conservative. A column with fewer
than 30 fraud tokens is now reported as unjudged instead of passing vacuously, and a release run must
be able to judge every column.

The reviewer also noted the detector's leaf floor of 50 rows: it made the new event gate score
exactly 0.5 on a few hundred events whatever was planted in them. The floor now shrinks with the
sample and is capped at the original 50, so large runs are unchanged and small ones are analysable.

## MAJOR 1.5 — the eight-scenario gate blocked development runs · FIXED

It was unconditional although the generator's own comment calls it a release property. It is now
gated on `--full`, like every other size-dependent check, with a negative test.

## MAJOR 3.1 — a citation asserted what its own figures contradict · FIXED

`volume.monthly_growth_rate` said "grew 27% in 2024 (6,414 million from 5,061.20 million), which is
2.01% per month". Those volumes give 26.73%, i.e. 1.99% per month; 2.01% comes from the rounded
headline. The value is now **0.0199**, computed from the two volumes, and the citation says so.

## MAJOR 3.2 — the Tanzania-proxy caveat was claimed everywhere and present in two places · FIXED

`sourcing_pass.md` said "each citation says so" and the defence questions said "every affected
citation". It was in 2 of 5. The caveat is now in all of them, including the most influential
parameter (`mean_transactions_per_active_customer_month`), whose rationale also claimed the
non-mobile-money channels were "a small part of the mix" when the SRS mix puts 32% of rows on card,
bank transfer and agent banking and 9% more online. Both corrected.

## MAJOR 3.3 — the loader accepted citations too vague to check · FIXED

It required only a URL, a year and a date. `citation: "http://x/2024"` passed. It now also requires
at least 80 characters, a locator (table, page, figure, annex, section, indicator or version), a
rationale, and an access date that is not in the future. Three junk citations and two missing-field
cases are now rejection tests. All 81 repository parameters still pass.

Also fixed from the relayed area-3 findings: `cross_border_share` stores the derived **0.0046**
rather than a value rounded up by 8%; `urban_share_of_customers` now carries a URL for the FinScope
document that supplies its usage weights, not only for the census; `school_fee_months` no longer
claims its source "implements the national calendar" — the citation names the school, quotes the
week-by-week table and states that the ministry's own publication could not be retrieved.

## MAJOR 4.1 — the shipped report described a superseded parameter set · FIXED

The reviewer's proof is unanswerable: the committed report's own footer said `SOURCED: 0` of 79 while
the parameters said 12 of 81. It was regenerated at `a5836e1`, after the reviewed commit, and again
now that the event fix changes the data.

**A guard exists now rather than a promise:** every report records the SHA-256 of the parameter
values it was generated from, and `test_the_committed_report_describes_the_current_parameters` fails
when the committed report no longer matches the repository's parameters. That test failed on the
stale report while I was writing it, which is the evidence that it works.

## MAJOR 4.2 — the report misstated its own criterion · FIXED

`report.py` hard-coded "1.96 SE" after the code moved to a family-wise z. It now renders the actual
rule, the per-check bands, and the event-construction line.

## MAJOR 4.3 — nine gates had no negative test · FIXED

Eleven plant-a-leak tests now cover every always-on gate and every `--full` gate: value formats
(a malformed MCC), file order (fraud removed from the first half of the year), generator peak memory
(a doctored `run.json`), fraud scenarios, label noise (noise removed), fraud rate (fraud removed),
channel mix (one channel), country mix (one currency), size, plus the three new identifier channels
and the event oracle.

## MAJOR 4.4 — release scale is unverified · ACCEPTED, RECORDED

Nothing here changes it: the 5,000,000-row run has never happened, because the workflow cannot be
dispatched until `main` is the default branch, and the owner's direction is that it must not run on
the build laptop. It is PB-25, and ML-DATA-01 is now `VERIFIED_AT_REDUCED_SCALE` in the register with
the reduced-scale note spelling out what was and was not verified. The M2 gate carries it as an open
item.

## Addendum findings

- **A. The export memory test could not fail** — `ru_maxrss` is a high-water mark and a module-scoped
  fixture had already peaked, so the delta was ~0. It now measures a fresh subprocess through
  `RUSAGE_CHILDREN`. FIXED.
- **B. "Novel sub-variant present" had an escape hatch** — zero novel rows passed when `full` was
  false. Split into two checks: placement (never outside the test period) is always a gate; presence
  is a release gate. FIXED.
- **C. `wilson_interval` and `null_auc_stderr` had no tests** although they produce every interval in
  the report and the identifier band. Both now have unit tests against published values, plus the
  calibration test above. FIXED.
- **D. "Every fraud parameter is assumed" was false** — 21 are assumed and 4 are calibrated to SRS
  targets. Corrected in the datasheet, the limitations, the walkthrough and the defence questions.
  FIXED.
- **The register could not answer the audit's own question** — ML-DATA-01…08, D-07 and D-08 were
  `NOT_STARTED` with no implementation or evidence, while tagged tests existed. Filled in, with
  ML-DATA-01 at reduced scale and ML-DATA-08 in progress. FIXED.
- **The datasheet's "no sampling step"** omitted `rows_dropped_after_simulation_end`. Stated now.
  FIXED.
- **Determinism was never tested across a Parquet row-group boundary** — the reviewer verified the
  property at 6,000–24,000 rows but noted no month reached a second row group. A test now forces the
  boundary with a 64-row group size and compares every file. FIXED.
- **Area 2 verdict softened by the reviewer** (`combine_chunks()` is dead code that no mutation
  catches). Logged; the row-group test above is the part that mattered.

## Correction the reviewer made to their own report

Single-feature AUC read 0.809 (a FAIL) at 12,033 rows, 0.779 at 40,213 and 0.662 at a million: the
headline D-08 limit was swinging across the gate with row count. The cause was `_category_rates`
target-encoding the categoricals on the same rows the AUC was read from, so a row's own label was
evidence about itself. **Encoding is now out of fold.** Measured after the change: 0.753 at 12K,
0.776 at 60K, 0.711 at a million — the same property, no longer a function of sample size.

What remains is real signal, and it is worth naming: merchant category `4829`
(person-to-person transfer) carries a 3.7× fraud lift, because the sourced person-to-person share
(0.216) makes merchant payments the norm while fraud drains go to people. It is the dataset's
strongest single feature at 0.711, inside the 0.80 limit, and it is a designed scenario property
rather than a construction artefact.

## MINOR / NIT, logged not fixed

Per the owner's pace direction: `_category_rates` self-inclusion (fixed anyway, as above, because it
moved a gate); `token_features`' constant length column (removed with the rewrite);
`columns = max(len(data.tokens), 1)` dead guard; the stale `CI_Z` comment trail; `sourcing_pass.md`
describing an API query and a tzdata package as "read in full text"; document-count wording drifting
between "seven" and "eight" (now consistent at eight); the tzdata citation linking the landing page
rather than the pinned version; partition key `month=` being the local month while timestamps are
UTC, so 11 rows of 40,213 land one UTC month early (MINOR 2.1 — worth fixing before release, in the
backlog); unbounded `_plans` and `_month_plans` caches contradicting the "one month at a time"
comment (MINOR 2.2); `planned_counts` recomputed 25 times rather than 24.

## Verification after the fixes

A fresh 1,006,249-row run on the corrected generator, every gate passing:

| Check | Result |
|---|---|
| single-feature AUC | 0.711 max (`merchant_category_code`), limit 0.80 |
| shortcut detector | 0.510, band ±0.030, 48,597 rows, 16 id bytes + label delay + sub-second + row position |
| event construction | 0.513, band ±0.030, 2,424 events |
| identifier construction | 0.506 / 0.508 / 0.514, each within ±0.030, every token character read |
| fraud rate | 0.870% overall, 0.905% test, 24 of 24 monthly intervals covering target |
| channel mix / country mix | 0.16 pp / 0.01 pp |
| novel sub-variant | 73 rows, none before the test start |
| identifier uniqueness | 0 duplicates in 1,006,249 rows |
| size | reported not met: 1M of the 5M ML-DATA-01 needs (PB-25) |
