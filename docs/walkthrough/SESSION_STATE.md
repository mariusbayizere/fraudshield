# Session state — M4 has its first metric on the real split

Rewritten 2026-09-21 (evening). Facts only; where something is unverified, assumed or open it
says so.

## The dataset M4 trains on

| | |
|---|---|
| Path | `dataset/output/bench1m` (gitignored, 96 MB) — **not** in a session scratchpad |
| Rows | 1,006,249, 24 months, 2024-01 to 2025-12, seed 20260917, `--rows 1000000` |
| Fingerprint | `c8856a0ecb2d76a114495a66c09a6b56296133c11dd92065d15237e21654496f` (**version 2**) |
| Generated at | commit `6af491e`, through `fs-evidence` on a clean tree |
| Alongside it | `dataset/output/split.json`, `dataset/output/packs.json` — both needed by `fs-features` |
| Described by | `dataset/realism_report.md`, carrying that fingerprint; **all gate checks pass** |

**Fingerprints are version 2 as of today and version 1 values are not comparable** (PB-54). The
same draw reads `b896b632` under v1; the pre-PB-40 draw read `40a77bb6`.

## The result

`docs/benchmarks/m4_first_evaluation.md`. Fitted on D-07's train period, scored on its test
period, embargo untouched.

| | |
|---|---|
| Model AUC | **0.986** ±0.020, on **63** confirmed fraud rows |
| Best single feature, defined on every row | 0.871 (`velocity_ratio_1h_vs_30d`) |
| **Margin over the single feature** | **+0.115** |
| Best trivial rule | 0.658 (`amount_log1p`) |
| **Margin over the trivial rule** | **+0.329** |
| Recall at 1% FPR | 0.873 |
| Features | **38** |

Untuned, uncalibrated, and trained on 9,000 rows spanning 235 days rather than the whole train
period. Comparable with the gates in its **split**, not in its training volume.

## What a reader should distrust first

**The first version of a measurement is usually about its sampling.** This figure took three runs.
Run 1 reported a second perfect separator; run 2 printed its denominator (two fraud rows); run 3
made the sample representative and it evaporated. Only the third run is about the model. Full
account in `docs/research/lab_notebook.md` and in `docs/benchmarks/m4_first_evaluation.md`.

## Constraints every M4 metric is still subject to

1. **38 features, never 44.** Five have no source data (PB-44); one is constant
   (`just_below_limit_flag`, PB-47). The count is derived from the registry and printed beside
   every figure. It has been 36, 37 and 38 in two days — a figure carries the count it was
   measured with.
2. **Every metric is a margin over a baseline measured on the same rows** (PB-46, PB-55). A
   partial-coverage feature is reported separately with its coverage and is never subtracted.
3. **Evidence runs go through `fs-evidence`** and refuse to start on a dirty tree (PB-53). The
   artefact carries the commit *and* the tree state.

## Open, in the order I would take them

| Item | What |
|---|---|
| **PB-57** | `fs-evidence` captures instead of streaming, so a 34-minute run is silent |
| **PB-51** | FR-02-02's `< 10 ms`, carried to M10, needs the machine in `docs/benchmarks/hardware.md` |
| **PB-44** | five features still have no source data; all five wait on a per-account or agent table |
| **PB-43** | `corridor_class` reaches two of its four classes; owner decision is to keep them |
| **PB-36/37/38** | untested DB fallback; no per-account durable table; 8-day refresh under a 30-day feature |
| **PB-25** | the 5M run, still blocked on the default branch |

**An owner decision is waiting** (PB-56): `fraud.takeover_lead_minutes` is `[5, 60]` with ASSUMED
provenance, so every enabling event is followed by its drain in a tight uniform window with no long
tail. Giving it a tail would make the benchmark more realistic and **would change the draw**, which
is why it is not a decision to take while closing a backlog item.

## What landed today

- **PB-52 closed as unexplained-with-guard.** The 1,012,522-row report was produced on a dirty
  working tree; the change is unrecoverable. Proved by measurement that PB-29 re-drew nothing:
  200,000 rows on the commit before the pack refactor and on the current tree give the identical
  fingerprint.
- **PB-53:** `fraudshield_tools.provenance` and `fs-evidence`. A commit hash written by hand
  records an intention; the stamp records the commit *and* a digest of `git status --porcelain`.
  `fs-exit-criteria` reads it — absent is a warning, present-and-contradicting is an error.
- **PB-54:** the fingerprint hashed three columns of fourteen and could not see PB-40's device
  change. Version 2 hashes every column of each sampled row.
- **PB-40:** the generator shares devices — a synthetic-identity ring on one handset, plus
  handed-down handsets so a shared device is not by itself a fraud signal. 504 devices serve more
  than one account, up to seven. `accounts_per_device_7d` takes 7 distinct values where it took
  one, and `synthetic_identity_score`'s fourth term fires.
- **PB-49:** `fs-features evaluate` reads the published split. The embargo is unreachable by
  construction; calibration overlaps validation and the reader says so.
- **PB-55, PB-56:** above.

## Things that will bite whoever picks this up

- **One heavy job at a time**, and keep evidence datasets in `dataset/output/` rather than the
  scratchpad, which does not survive a session clear.
- **A declaration follows its measurement.** `accounts_per_device_7d` moved to COMPUTABLE only
  after `fs-features computability` failed on it saying "the degeneracy has cleared and the
  declaration has not". Editing the register in the same breath as the code it describes produces
  a register of intentions.
- **`pkill -f` matches your own shell.** Kill by PID.
- **Editing `requirements.yaml` by hand needs a reseed**; FR rows use `FR_MILESTONE_OVERRIDES`,
  everything else `ROW_MILESTONE_OVERRIDES`.
- **Build a `FeatureContext` with `dataclasses.replace`,** never field by field.
- **`fs-evidence` refuses on a dirty tree**, so regenerate → commit → run, in that order. It
  caught me once already, correctly.
- Timings here: ml suite ~10 s; tools ~40 s; dataset suite **~31–46 minutes**; a 1M generation
  ~20–25; `fs-dataset report` on 1M ~4; `fs-features computability` at 200k/20k ~21;
  `fs-features evaluate` at 560k/15k **~34 minutes**, nearly all of it feature computation.
