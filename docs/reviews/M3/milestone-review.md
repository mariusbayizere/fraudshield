# M3 milestone review

One pass, **BLOCKER and MAJOR only**, against E11's four areas: leakage under the new features,
determinism, the grouping rules, and whether every claim in the M3 walkthrough is backed by a test
or a measurement.

Reviewed at commit `4e16e11` plus the then-uncommitted PB-46/PB-47 work. **Both MAJOR findings
were fixed at commit `2c80ef6`**, and the corrected measurement they prompted is
`docs/benchmarks/m3_single_feature_auc_2c80ef6.txt`. Verified state: ml 266 passed
(96.72% branch), dataset 126 passed (94.01%), tools 177, contracts 490, mypy clean over 122 files,
governance 258 rows / 637 tagged tests / 0 errors.

**Two MAJOR findings. No BLOCKER.** Both concern what a reader of a result would conclude, not
whether the code is correct.

---

## MAJOR M3-1 — the strongest single feature is inflated by the measurement's own corpus truncation, and the figure is already published

`velocity_ratio_1h_vs_30d` is reported at **0.894** in the datasheet, the model card, the claims
register (C-14), the baseline document and the traceability gate on eight ML-GATE rows. It is the
number the entire option-3 decision is built on.

That number was measured on **the last 200,000 rows** of a 1,006,249-row dataset. The feature
divides by history *actually observed* (`history_basis=OBSERVED_CAPPED`), and the vector's
`_first_seen` takes an account's earliest row **in the corpus supplied**. For any account whose
first corpus row falls within 30 days of the scored row, the denominator is shorter than the truth
and the ratio is inflated — which is **precisely the PB-37 failure mode this project documented and
fixed in the online path**: arrivals present, first-seen absent, baseline inflated, ratio collapsed
or exaggerated across the account base at once.

The existing record acknowledges the bias and bounds it by the 30-day cap, arguing that only
accounts appearing solely in the final month are affected and that the bias cannot be 0.09. **That
argument is asserted and not measured.** Nothing in the repository establishes what fraction of the
scored rows belong to such accounts, and the fraction is a property of the draw rather than of the
reasoning.

**Why MAJOR rather than MINOR.** The figure is not a supporting detail; it is the floor every future
model metric will be reported against, it has been written into a datasheet section that a reader
is told to meet before any model result, and it is cited in a refutation of a previously published
claim. If it is materially wrong, the correction propagates to all of those and to any M4 result
already compared against it.

**What would settle it, cheaply.** Re-run `fs-features auc` restricted to accounts whose first
corpus row precedes the scored row by more than 30 days — the accounts for which `_first_seen` is
exact — and report the separation on that subset beside the full-sample figure. If the two agree
within the interval, the bias is bounded by measurement rather than by argument. If they do not,
the baseline moves and everything quoting it moves with it.

**Until then**, every place quoting 0.894 should say the corpus was truncated and the bias is
unquantified. Four of the five over-ceiling features are unaffected by this mechanism
(`tx_count_1h`, `implied_speed_kmh`, `seconds_since_last_tx` read no observed-history denominator;
`counterparty_is_new_for_account` is biased *against* separation by truncation), so the finding
that the benchmark is velocity-separable survives regardless — it is the specific headline number
that is in question.

---

## MAJOR M3-2 — the vector's `_first_seen` and `_device_first_seen` make exactly the inference the online path is forbidden to make

`fraudshield_ml.features.vector._first_seen` returns the account's earliest row in the supplied
corpus, falling back to the scored transaction's own timestamp. `_device_first_seen` does the same
for a device. The online path is **explicitly forbidden** from this: `observe()` does not set
`first_seen_at`, and `device_age_days` fails closed to NaN without a durable value, because "the
earliest arrival is the rolling window's edge, not the account's beginning" (PB-37, and two tests
whose names say so).

The vector's docstring argues the batch case is different: over a *complete* dataset the earliest
row genuinely is the account's first. That is true of a complete dataset and **false of every run
this repository has actually performed**, all of which pass a truncated corpus — the argument
describes a usage that has not occurred.

**Why MAJOR.** It is the same defect the milestone spent real effort eliminating from one path,
reintroduced in the caller that produces every published figure, with a docstring that explains why
it is safe. A future reader has every reason to trust that docstring. It is also a live trap for
M4: the training pipeline will call this code, and if it calls it per-fold or per-window the
inference silently becomes wrong in the same direction.

**Fix.** `_first_seen` should return `None` — and the feature NaN — when the account's earliest
corpus row is not demonstrably its first, i.e. when the corpus does not start before the account
does. The honest signal is "this corpus cannot tell you", which is what the online path already
says. A caller with a complete dataset can pass the true first-seen explicitly, which is the
arrangement every other reference input already uses (`prior`, `opened_at`, `tiers`, `standing`).

---

## Area by area

**Leakage under the new features — sound, with one structural limit stated.** Every window is
strictly backward-looking and excludes the scored transaction; label-derived features gate on
`available_at` and `Outcome` carries no `confirmed_at` at all; E5's four excluded columns are
tested behaviourally by perturbation rather than by source scan. The limit: the parity suite is
blind by construction to a defect both paths share, and one such defect
(`velocity_ratio_1h_vs_30d` not filtering by account) was found by an index-equivalence test rather
than by parity. That is recorded, not hidden.

**Determinism — sound.** Byte-identical across batch sizes 1, 5 and 17, compared through
`float.hex()` rather than `==`. Fold assignment uses a stable digest rather than `hash()`, which is
salted per process and would have made folds differ between runs while every value test still
passed.

**The grouping rules — sound and measured.** E1 is stated per `history_key`; component folding was
rejected by measuring the graphs rather than by reasoning about them; the encoding folds by whole
accounts with a test that fails if the grouping is removed, constructed so that grouped and
ungrouped genuinely disagree.

**Claims backed by tests or measurements — mostly, and the exceptions are listed.** The M3
walkthrough carries a "what is claimed and not backed" section naming four. The two MAJOR findings
above are both cases where a claim is backed by an *argument* that reads like a measurement.

---

## Confirmed sound, and worth saying

- **The computability check finds what it was built to find, including in its author's own work.**
  It caught `days_since_sim_swap` mis-declared, then caught its own NaN-counting flaw hiding
  `accounts_per_device_7d`.
- **E3 was run and reported as failed** before any resolution was chosen, and the three resolutions
  were written down with the two rejections argued rather than dismissed.
- **The exit-criteria table's own inaccuracy was caught and written into the table** rather than
  quietly corrected, and PB-45 designs the checker.
- **Refutations are recorded, not edited away**: C-9 keeps its original wording and its status.
