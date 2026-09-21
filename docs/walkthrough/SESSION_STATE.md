# Session state — M4 begun; the pipeline runs and the benchmark is reproducible

Rewritten 2026-09-21. Facts only; where something is unverified, assumed or open it says so.

## The dataset M4 trains on

**Do not regenerate it to find out where it is.** It is kept outside the session scratchpad
precisely so that clearing a session cannot cost another 20-minute run.

| | |
|---|---|
| Path | `dataset/output/bench1m` (gitignored; 96 MB) |
| Rows | 1,006,249, 24 months, 2024-01 to 2025-12 |
| Seed | 20260917, `--rows 1000000` |
| Fingerprint | `40a77bb66868777f81c2cc72b5d23cf900c41c1d86c2fb84620b2d589b4f42a1` |
| Generated at | commit `49b2bcb` on `m3/features`, 2026-09-21 |
| Published packs | regenerate with `fs-dataset packs --output <path>`; the feature CLI needs them |
| Describes it | `dataset/realism_report.md`, which now carries that fingerprint (PB-41 closed) |

It carries the `split` block in `manifest.json`, so it is the first dataset in this repository a
consumer can evaluate against D-07's boundaries without reconstructing them.

## Where the work is

- **Branch:** `m3/features`. **Tag `m3-complete`** at `f8885d6`; `m2-complete` at `ed7a8d9`.
- **Register:** `current: M4`, `completed: [M0, M1, M2, M3]`, README follows it, and
  `fs-traceability check` passes on its own — 258 rows, 726 tagged tests, 0 errors. It took ADR
  0027 to get there (PB-50).
- **M4 has no exit criteria yet.** PB-45's checker was built first on purpose. Note that
  `fs-exit-criteria` still defaults to the M3 register and document, so writing
  `docs/traceability/m4_exit_criteria.yaml` means pointing it there too, not only adding a file.

## The one model figure, and it is not a result

`docs/benchmarks/m4_pipeline_smoke.md`. One XGBoost booster, **36** computable features, 20,000
scored rows with 200,000 of history, a 70/30 time-ordered holdout:

| | |
|---|---|
| Model AUC | 0.993 ±0.018, on **40** confirmed fraud rows |
| Single-feature floor, same rows | 0.881 (`velocity_ratio_1h_vs_30d`) |
| **Margin** | **+0.112** |
| Recall at 1% FPR | 0.925 |

**Do not quote it.** Not D-07's split — no embargo, so an incident can straddle the cut — no
tuning, no calibration, and forty positives cannot separate 0.99 from 0.97. Expect it to fall when
the real split arrives; that is the split working. It trained on 36 because it ran before
`round_sum_flag` was fed; **the trainable set is now 37** and a figure measured on 36 stays one.

## The three constraints every M4 metric is subject to

1. **37 features, never 44.** 44 are implemented on both paths; five have no source data (PB-44)
   and two are constant (PB-47). `trainable_features()` derives the set from the registry and the
   count is printed beside every number the smoke command reports.
2. **Every headline metric is a margin over the single-feature baseline and the best trivial rule**
   (PB-46, owner decision). Published floor **0.894**
   (`docs/benchmarks/single_feature_baseline.md`); the floor is *re-measured on the same rows* and
   subtracted, because quoting a figure from another sample compares two populations. A model
   metric without its baseline is inadmissible on the same terms as one without its scale (E2).
3. **No real metric until the evaluation runs on D-07's split.** The boundaries are published now
   and the benchmark carries them, so the remaining work is on the feature side (PB-49).

## Blocking order

1. **PB-49 — teach `fs-features` to read `split.json`.** Its first half is done: the dataset now
   publishes the split. What remains is the consumer — assign each scored row to train,
   validation, calibration or test by the published boundaries, excluding the embargo, reading the
   published format and never importing `fraudshield_dataset`. No M4 headline metric before this.
2. **PB-40 before training anything that matters.** No device is shared in this draw, so
   `accounts_per_device_7d` is constant and `synthetic_identity_score` is a term short. Note that
   fixing it **changes the draw**, so it invalidates this dataset and its fingerprint — which is a
   reason to do it before M4 measures anything, not after.
3. **PB-52 — the unexplained draw.** Not blocking a metric, but blocking any re-quotation of an
   M2-era figure.

## What landed this session

- **PB-48 closed.** The generator records D-07's four boundaries into `manifest.json` at
  generation — the only point where the target row count the planner scales by is still known —
  `fs-dataset split --output split.json` adds measured segment counts, `release.json` carries the
  same block, and `realism.checks.split_counts` delegates to the same function so the report's
  table and the sidecar cannot disagree. A dataset without the block refuses, and export refuses
  **before** copying rather than after.
- **PB-50 closed by ADR 0027.** Three M3 Must rows were filed under a milestone that could not
  meet them: FR-02-09 → M5, ML-DATA-07 → M6, TEST-01 → M4. D-03 and D-04 became DONE with
  evidence. FR-02-02 stays in M3 as `DONE_WITH_DEVIATION` — its acceptance criterion was met and
  only the `< 10 ms` needs dedicated hardware, carried to M10 as PB-51.
- **PB-44: `round_sum_flag` is fed.** It was never unimplemented; the denomination table was
  missing. `round_denominations` is now a pack field (`ASSUMED`, and the packs say so), published
  by `fs-dataset packs`. Measured: true for **10.33%** of rows, 7.60%–12.58% across the five
  currencies (`docs/benchmarks/m4_round_sum_rate_40a77bb6.txt`), and all 44 declarations match the
  data (`docs/benchmarks/m4_computability_40a77bb6.txt`).
- **PB-41 closed.** The committed realism report carries the fingerprint of the rows it describes.
- **PB-52 opened, and it is the interesting one.** See below.

## The finding worth reading first

**A generated artefact is not provenanced by the commit that contains it.** `realism_report.md`
described a 1,012,522-row draw, and the datasheet, the claims register, four M2 review documents
and the walkthrough all explained the difference from M2's figures as PB-29 re-drawing the dataset
by changing country iteration order. Regenerating refuted both halves at once:

- that tree's generator code and parameters are **byte-identical** to the current ones
  (`git diff d85385f HEAD` over them is empty) and produce **1,006,249** rows, twice, with
  identical fingerprints;
- country iteration order changes **nothing** — `Population._apportioned` sorts internally, which
  is why PB-41's prescribed "reverse the pack order" mutation produced a byte-identical dataset
  and had to be replaced with a `_GOLDEN` constant mutation.

And the regenerated figures (`merchant_category_code` 0.706, event delay 0.758, event type 0.537)
are *exactly* M2's original published ones, so the 1,012,522-row run is the outlier. Every place
that quoted it now says so; the old notes are annotated rather than deleted, because they are the
record of what was believed. What produced that draw is unexplained: **PB-52**.

The rule it teaches, and the gap it exposes in our own tooling: `fs-exit-criteria`'s artefact kind
checks that a cited artefact **names** a commit. It cannot check that the artefact was **produced
at** that commit, and this is exactly the case where those differ.

## Open items

| Item | What |
|---|---|
| **PB-49** | `fs-features` still cuts its own holdout; blocks every M4 metric |
| **PB-52** | a committed report described a draw this tree does not produce; mechanism unexplained |
| **PB-40** | no shared devices — one feature constant, one a term short; fixing it re-draws the dataset |
| **PB-44** | five features still have no source data; all five wait on a per-account or agent table |
| **PB-51** | FR-02-02's `< 10 ms`, carried to M10, needs the machine in `docs/benchmarks/hardware.md` |
| **PB-43** | `corridor_class` reaches two of its four classes; owner decision is to keep them |
| **PB-36/37/38** | untested DB fallback; no per-account durable table; 8-day refresh under a 30-day feature |
| **PB-25** | the 5M run, still blocked on the default branch being `m0/bootstrap` |

## Things that will bite whoever picks this up

- **One heavy job at a time.** Six evidence runs have been lost to six causes: source edited
  mid-run, `uv sync --reinstall` mid-run, a test mutating shared parameters, memory pressure from
  two parallel runs, an invented scratch path that was reaped, and background waiter shells
  competing with the run they were waiting on.
- **Keep evidence datasets out of the scratchpad.** `dataset/output/` is gitignored and survives a
  session clear; the scratchpad does not.
- **`pkill -f` matches your own shell.** `pkill -f "fraudshield_ml.cli smoke"` killed the shell
  running it, because that shell's command line contains the pattern. Kill by PID.
- **Test the cheap stage before paying for the expensive one.** Eleven minutes of feature
  computation ended on `ImportError: sklearn` — `XGBClassifier` is the scikit-learn wrapper and
  this package does not depend on it. Use the booster API; `--cache` now persists the matrix.
- **Do not commit while a suite runs.** `pre-commit` stashes unstaged changes and regenerates the
  traceability matrix against the reduced tree, so it disagrees and governance fails.
- **Editing `requirements.yaml` by hand needs a reseed.** `fs-traceability-seed --check` compares
  the layout and hand-wrapped `notes` text fails it; run `fs-traceability-seed` then
  `fs-traceability render`. Milestone reassignments go in `ROW_MILESTONE_OVERRIDES` — and FR rows
  use a **different** table, `FR_MILESTONE_OVERRIDES`, which is where FR-02-09 had to go.
- **Build a `FeatureContext` with `dataclasses.replace`, never field by field.** Two E5 tests
  listed the fields by hand; adding `denominations` silently dropped it in both and read as a leak.
- **Clear `.mypy_cache` after adding or removing an `__init__.py`**, and **`ml/tests` must not be a
  package** — a top-level `tests` collides with `dataset/tests` under one mypy invocation.
- **The commit hook caps the subject at 72 and wraps the body at 72** — rewrap per paragraph.
- Timings here: ml suite ~8 s; tools ~45 s; dataset suite **~31 minutes**; a 1M generation
  **~20 minutes**; `fs-dataset report` on 1M ~4; `fs-features computability` or `smoke` at
  200k/20k **~13–21 minutes**, nearly all of it feature computation.
