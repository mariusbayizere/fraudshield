# Session state — M2 closed, M3 not started

Written 2026-09-19 so the session can be cleared without losing the thread. Facts only; where
something is unverified or unexplained it says so.

## Where the work is

- **Branch:** `m2/generator`
- **Tag:** **`m2-complete`** is at `ed7a8d9`, the governance commit that closed the milestone.
  This handover note is the commit immediately after it, so the branch head is one ahead of the
  tag — deliberately: the tag marks M2's state, not the note describing it. It is annotated with the four MAJORs found after
  the dataset review closed, how each was found, the four measurements the no-leakage claim rests
  on, and a statement that the list is not assumed complete. Read it with `git show m2-complete`.
- **Milestone register:** `current: M3`, `completed: [M0, M1, M2]`.
- **Governance gate:** passes on its own — 258 rows, 281 tagged tests, **0 errors, 0 warnings**.
  The hook was not bypassed.
- **Dataset suite at close:** **106 passed, 94.51% coverage.**

## Open owner actions

- **Default branch is still `m0/bootstrap`** (verify with `git ls-remote --symref origin HEAD`).
  Until it is `main`, no `workflow_dispatch` workflow registers, so PB-25 stays blocked.
- **`gh` has no credentials in this session.** CI is read through the unauthenticated REST API;
  workflows cannot be dispatched; the `protect-main` ruleset cannot be verified (GOV-9).
- **ML-DATA-08 needs your accounts** on HuggingFace Datasets Hub and Zenodo. It is recorded as
  `REQUIRES_EXTERNAL_PARTY`, deliberately not moved to a later milestone: publication is an access
  problem, not a scheduling one (ADR 0024).

## M2 final state

The generator, its realism and anti-leakage gates, provenance for all 81 parameters and the release
export. Verified at 1,006,249 rows, seed 20260917: **all gate checks pass**; fraud rate 0.870%
overall and 0.905% in the test period; 24 of 24 monthly intervals covering target; channel mix
within 0.16 pp; country mix within 0.01 pp; zero duplicate identifiers.

**The milestone review found six defects after the dataset review had already closed, four of them
MAJOR. None was found by the guard it concerned.**

| # | Defect | Found by | State |
|---|---|---|---|
| M-1 | The datasheet's figures matched no run the repository could produce | a delta re-check looking elsewhere | FIXED, with a guard |
| M-4 | Below ~170,000 rows a dataset silently lacked a fraud scenario | auditing what a refusal message meant | FIXED, refuses and names the size |
| M-7 | The null band reverted to a fixed 0.03 floor above ~100,000 rows — 2.8x too permissive at release scale | verifying an assumption in an unrelated derivation | FIXED, floor removed |
| M-8 | The categorical encoding folded per row, not per incident, inflating every single-feature AUC | asking what explained a residual M-7's fix exposed | FIXED, folds by account |
| M-2/3/5/6 | MINORs: `SOURCED` conflates stated with derived; determinism tested only at 6,000 rows; a size limit reported as a parameter error; a docstring claiming to be scale-free | — | Logged |

Also corrected: `CV_TREE_NULL_INFLATION` 1.3 → **1.62**, derived from 45 clean datasets; and the
claim that out-of-fold encoding removed the size dependence, which measurement **refutes**.

**Numbers to quote:**

| Figure | Value |
|---|---|
| Strongest single transaction feature | **0.706** (`merchant_category_code`) |
| Strongest channel overall | **0.758** (event-to-transaction delay, via the `account_events` join) |
| Shortcut detector at 1M | 0.510 against a null-sized band of **0.011** — 91% of it |
| False alarms on clean data | **1 of 45** (2.2%), nominal 5% |
| Power against a 0.6-strength planted leak | fires **15/15 from 60,000 rows** |
| Minimum for all eight fraud scenarios | **~170,000 rows** |

## Three limitations carried out of M2

1. **Single-feature AUC depends on dataset size by more than seed noise, and the cause is unknown.**
   Ten seeds at 300,000 rows give a seed-to-seed sd of 0.0055; the range across scales is 0.065,
   twelve times that. **This is the open scientific question** — see below.
2. **`CV_TREE_NULL_INFLATION = 1.62` is grounded at ≤ 60,000 rows and extrapolated above.** Four to
   ten seeds above that give estimates from 0.84 to 2.32. Settling the form needs ~50 seeds per
   scale, about eight hours at 1M.
3. **ML-DATA-01 is `VERIFIED_AT_REDUCED_SCALE`.** The 5,000,000-row run has never happened (PB-25),
   blocked on the default branch; the run must not happen on the build laptop.

## The open scientific question

Single-feature AUC is higher and far more variable at smaller scales: ten seeds at 300,000 rows span
0.7107–0.7298 while five at 1,000,000 cluster 0.7091–0.7139.

**It is not the fold-grouping defect.** Account-grouped folds remove the same amount at both scales
(0.0043 at 300,000, 0.0046 at 1,000,000) and for the one seed measured at both there is no gap at
all. Sibling leakage does not account for it.

Three candidate causes, each separable by experiment and none needing new generator code, are
written out in `docs/research/lab_notebook.md`: per-category sample size, incident concentration, and
fold count interacting with n. **Do not investigate in M3.** It is contained by M3 exit criterion E2
and belongs to M4 evaluation as a control.

## M3 exit criteria

Full list in `docs/traceability/m3_exit_criteria.md`. Two exist because M2 paid for them:

- **E1** — every fold, split, sample and target encoding groups by **account** and respects time,
  each with a test that **fails if the grouping is removed**. A test that passes under both
  groupings tests nothing; M2's first attempt at exactly this test did that.
- **E2** — every reported metric states the scale it was measured at, and comparisons between
  feature sets, models or ablations use the same dataset size. A metric quoted without its scale is
  not admissible as evidence.

M3 also inherits `ML-DATA-07` and RES-01's 44-feature clause, reassigned from M2 by ADR 0024.

## Exact first three steps of M3

1. **Design the batch/online parity test, before any of the 44 features is written.** A divergence
   found later invalidates every model metric measured in between — the same retroactive damage
   M-8's fold defect did to every AUC this project had reported. Two decisions to settle first:
   *what counts as identical* (bit-identical by default; a derived tolerance only where an
   order-dependent aggregation makes that impossible; never "same decision" alone, which is the
   floor-shaped weaker check that passes where the stronger one would catch something); and *what
   the paths are fed* — replay an account's events in order, snapshot the online features after
   each, and compare each snapshot against the batch path computed **on that prefix only**. Handing
   both a completed history proves they agree on a situation the online path never meets, and hides
   future-leakage in a batch window.
2. **Fix PB-26 before any feature reads the partitions.** The `month=` partition key is the local
   month while timestamps are UTC, so 11 rows in 40,213 land one UTC month early. M3's time-window
   features read those partitions; a wrong key distorts them silently.
3. **Land ADR 0023's country packs and `corridor_class` in this same branch.** They are a
   prerequisite for M3 features rather than a separate milestone, and splitting them would cost a
   full review cycle. No country, currency or bloc hard-coded in feature code (E8).

## Things that will bite whoever picks this up

- **An evidence run owns the tree *and* the environment.** Editing source mid-run voids it; so does
  `uv sync --reinstall`, which removes the workspace packages out from under a running pytest. Use
  `uv sync --all-packages` to repair. Size timeouts for a loaded machine: a 13-minute suite took over
  33 under load average 7, and `timeout` kills with SIGTERM, which reads like an ordinary failure.
- **A single three-sigma reading is not evidence; repeated seeds are.** An analytic null was used
  where the empirical spread applied, and it produced a confident, wrong hypothesis. Any figure
  standardised against a theoretical null must say so.
- **A run conclusion is not evidence** (GOV-10). `stack` and `devcontainer` are path-filtered and
  conclude "success" with their real job skipped. Quote jobs. The `ci` and `stack` citations in
  `gate-evidence.md` are pinned to *different* commits on purpose.
- **The traceability register is generated.** Milestone assignments live in
  `ROW_MILESTONE_OVERRIDES` in `traceability_seed.py`, not in `requirements.yaml`. Run
  `fs-traceability-seed` then `fs-traceability render`; `pre-commit` stashes unstaged changes, so
  the rendered matrix must be staged with any test whose tags changed.
- **`git add -A` sweeps the agent worktrees into the commit** as gitlinks. It happened twice this
  session. `.claude/` is now gitignored.
- **The commit-message hook wraps at 72 characters** and rejects a longer line; use `git commit -F`.
- **Scratch directories do not survive a session.** 163 sweep records and a harness were lost that
  way. Anything that will be cited belongs in the repository, committed.
- **This laptop is shared.** Check `uptime` and `ps --sort=-rss` before assuming it is free.

## Where the evidence lives

- `docs/reviews/M2/milestone-review.md` — the review, all four areas.
- `docs/reviews/M2/delta-recheck.md` — the re-check of the dataset review's fixes.
- `docs/reviews/M2/shortcut_diagnosis.jsonl` — 273 sweep records, every one labelled
  `categorical_encoding: per_row_folds`: they predate M-8's fix, so their `single_feature_max` sits
  about 0.005 high. Re-run with `uv run python -m fraudshield_dataset.shortcut_diagnosis`.
- `docs/research/figures/power_curve.svg` — from a committed script and that data.
- `docs/research/lab_notebook.md` — decisions, rejected approaches, two refuted predictions, and the
  mistakes, including the t-denominator error.
- `docs/adr/0024-feature-requirements-belong-to-m3.md` — why M2 could close.
