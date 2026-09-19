# Session state — M2 (dataset generator), at close

Written 2026-09-19 so this session can be cleared without losing the thread. Facts only; where
something is unverified or unexplained it says so.

## Where the work is

- **Branch:** `m2/generator`, ahead of `main`. Nothing merged to `main`; **`m2-complete` is not
  tagged** — it waits on the owner's confirmation.
- **Default branch is still `m0/bootstrap`** (verify with `git ls-remote --symref origin HEAD`).
  Until it is `main`, no `workflow_dispatch` workflow registers, so PB-25 stays blocked.
- **`gh` has no credentials in this session.** CI is read through the unauthenticated REST API;
  workflows cannot be dispatched; the `protect-main` ruleset cannot be verified (GOV-9).

## What M2 delivered

The generator, its realism and anti-leakage gates, provenance for all 81 parameters, and the release
export — all described in `docs/walkthrough/M2.md`. Verified at 1,006,249 rows, seed 20260917: all
gate checks pass, fraud rate 0.870% overall and 0.905% in the test period, channel mix within
0.16 pp, country mix within 0.01 pp.

## What the milestone review found after the dataset review had closed

Six defects, four of them MAJOR, none found by the guard it concerned:

| # | Defect | State |
|---|---|---|
| M-1 | The datasheet's measured figures matched no run the repository could produce | FIXED, with a guard |
| M-4 | Below ~170,000 rows a dataset silently lacked a fraud scenario | FIXED, refuses and names the size |
| M-7 | The null band reverted to a fixed 0.03 floor above ~100,000 rows, making the gate 2.8x too permissive at release scale | FIXED, floor removed |
| M-8 | The categorical encoding folded per row, not per incident, inflating every single-feature AUC | FIXED, folds by account |
| M-2, M-3, M-5, M-6 | MINORs: `SOURCED` conflates stated with derived; determinism tested only at 6,000 rows; a size limit reported as a parameter error; a docstring claiming to be scale-free | Logged |

Also corrected: `CV_TREE_NULL_INFLATION` 1.3 → **1.62**, derived from 45 clean datasets rather than
adjusted by feel, and the claim that out-of-fold encoding removed the size dependence, which is
**refuted** by measurement.

## Numbers that changed, and what to quote

| Figure | Old | Now |
|---|---|---|
| Strongest single transaction feature | 0.711 | **0.706** (`merchant_category_code`) |
| Strongest channel overall | not measured | **0.758** (event-to-transaction delay, via the `account_events` join) |
| Shortcut-detector band at 1M | 0.030 (floor) | **0.011** (null-sized) |
| Detector power against a 0.6-strength leak | not measured | fires 15/15 from **60,000 rows** |
| False-alarm rate on clean data | 11% (5 of 45) | **2.2%** (1 of 45), nominal 5% |
| Minimum for all eight scenarios | not known | **~170,000 rows** |

## Three limitations carried out of M2

1. **Residual size dependence of single-feature AUC is real and unexplained.** Ten seeds at 300,000
   rows give a seed-to-seed sd of 0.0055, while the range across scales is 0.065 — twelve times
   that. Account-grouped folds remove the same amount at 300,000 and 1,000,000 rows, so sibling
   leakage does not account for it. **The one open scientific question from M2.** Carried into M4 as
   a control: any metric depending on target encoding is reported at a fixed, stated scale.
2. **`CV_TREE_NULL_INFLATION = 1.62` is grounded at ≤60,000 rows and extrapolated above.** The sweep
   cannot settle the form above that: 4–10 seeds give estimates from 0.84 to 2.32. Settling it needs
   ~50 seeds per scale, about eight hours at 1M.
3. **ML-DATA-01 is `VERIFIED_AT_REDUCED_SCALE`.** The 5,000,000-row run has never happened (PB-25).

## Evidence and where it lives

- `docs/reviews/M2/milestone-review.md` — the review, all four areas.
- `docs/reviews/M2/delta-recheck.md` — the re-check of the dataset review's fixes.
- `docs/reviews/M2/shortcut_diagnosis.jsonl` — 273 sweep records. **Every record is labelled
  `categorical_encoding: per_row_folds`**: they predate M-8's fix, so their `single_feature_max`
  sits about 0.005 high. Re-run with `uv run python -m fraudshield_dataset.shortcut_diagnosis` for
  post-fix numbers.
- `docs/research/figures/power_curve.svg` — the power curve, from a committed script and that data.
- `docs/research/lab_notebook.md` — decisions, rejected approaches, refuted predictions, mistakes.
- `docs/reviews/M2/gate-evidence.md` — job-level CI evidence, with the ci and stack citations
  deliberately pinned to different commits.

## Next steps

1. **Owner confirms the tag.** Then tag `m2-complete` on the branch head, annotated with the
   job-level gate evidence and the three limitations above stated plainly.
2. **M3 features, with the Africa-wide packs in the same branch.** ADR 0023's country packs and
   `corridor_class` are a prerequisite for M3 rather than a separate milestone; splitting them
   would cost a full review cycle. PB-26 (the `month=` partition key is the local month while
   timestamps are UTC) must be fixed **before** M3 features read those partitions.
3. **M3 exit criteria** are in `docs/traceability/m3_exit_criteria.md`, including the two rules M2
   paid for: every fold, split, sample and target encoding is account-grouped and time-respecting
   with a test that fails otherwise, and every reported metric states its scale.

## Things that will bite whoever picks this up

- **An evidence run owns the tree *and* the environment.** Editing source mid-run voids it; so does
  `uv sync --reinstall`, which removes the workspace packages out from under a running pytest. Use
  `uv sync --all-packages` to repair. Size timeouts for a loaded machine: a 13-minute suite took over
  33 under load average 7, and `timeout` kills with SIGTERM, which reads like an ordinary failure.
- **A run conclusion is not evidence** (GOV-10). `stack` and `devcontainer` are path-filtered and
  conclude "success" with their real job skipped. Quote jobs.
- **A later push cancels a gate run** — the stack concurrency group cancels in-progress runs on the
  same ref, even for a docs-only commit.
- **The traceability register is generated.** Run `fs-traceability-seed` then `fs-traceability
  render`; `pre-commit` stashes unstaged changes, so the rendered matrix must be staged with any
  test whose tags changed.
- **The commit-message hook wraps at 72 characters** and rejects a longer line; use `git commit -F`.
- **Scratch directories do not survive a session.** 163 sweep records and a harness were lost that
  way. Anything that will be cited belongs in the repository, committed.
- **This laptop is shared.** Check `uptime` and `ps --sort=-rss` before assuming it is free; the
  owner runs jobs from other projects on it.
