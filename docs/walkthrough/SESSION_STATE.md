# Session state — M2 (dataset generator)

Written 2026-09-18 so this session can be cleared without losing the thread. Facts only; where
something is unverified it says so.

## Where the work is

- **Branch:** `m2/generator`
- **Head commit:** `037631a` — *docs(reviews): cite an executed stack job for the M2 gate*
- **Pushed:** yes, `origin/m2/generator` is at the same commit. Working tree clean apart from
  untracked `.claude/`.
- **Commits ahead of `main`:** 47. Nothing has been merged to `main`; M2 has not been tagged.
- **CI at the head (job level, per GOV-10):** `ci` run 35310160693 — all seven jobs executed and
  succeeded. `stack` run 35310160631 — `core compose stack healthy + smoke test (M0 gate)` executed
  and succeeded. `devcontainer` — its job last *executed* at `6071444` (run 35274800793, success);
  later runs skipped it behind the path filter. Recorded in `docs/reviews/M2/gate-evidence.md`.

## What is done in M2

1. **Generator** (`dataset/src/fraudshield_dataset/generator/`, ADR 0022): 24 months, five countries,
   partitioned Parquet, streamed a month at a time; 1,006,249-row verification run in ~7 minutes at
   184 MiB peak resident memory; byte-identical output across chunk sizes, including across a forced
   Parquet row-group boundary.
2. **SRS 7.1 targets met by construction:** calibrated monthly fraud intensity schedule; per-scenario
   quotas placed as whole incidents with reallocation when a scenario cannot yet run; sequential
   apportionment of country and segment; exact per-cell monthly row quotas; channel mixes fitted to
   the SRS mix by iterative proportional fitting. Measured at 1M: fraud 0.870% overall and 0.905% in
   the test period, all 24 monthly Wilson intervals covering target, channel mix 0.16 pp, country mix
   0.01 pp.
3. **Leakage and realism as gates** (`realism/checks.py`, `realism/stats.py`): single-feature AUC with
   out-of-fold categorical encoding (0.711 max at 1M, limit 0.80); shortcut detector over all sixteen
   transaction-id bytes, sub-second timestamp, row position and label delay, grouped by account;
   identifier construction over every character of every token; **account-event construction** (new);
   null signatures per channel; value formats; identifier uniqueness; label noise; novelty placement
   and presence. Every tree band is sized to its own null with a calibrated inflation factor. Eleven
   plant-a-leak tests, one per gate, each failing without its fix.
4. **Provenance:** 81 parameters, 12 SOURCED from eight documents read in full, 14 calibrated to SRS
   targets, 55 modelling choices. The loader refuses citations without a locator, a rationale, 80
   characters or a non-future access date. `docs/research/sourcing_pass.md` records every document and
   what could not be sourced; `docs/research/parameter_influence.{md,json}` records the measured
   ranking that decided what to source; `docs/research/limitations.md` is the paper's limitations
   material.
5. **Release:** `fs-dataset export` (CSV only at export, licence and generated documents alongside,
   `SHA256SUMS` verifiable with one command); CC BY 4.0 legal code verbatim with its SHA-256;
   Gebru-template datasheet.
6. **Independent review answered:** 1 BLOCKER, 11 MAJOR and 4 addendum MAJORs fixed across six
   commits; `docs/reviews/M2/dataset-response.md` records each finding, the fix, and the measurement
   before and after. MINOR/NIT logged as PB-26, PB-27, PB-28.
7. **Local suites at the head:** `uv run pytest dataset/tests tools/tests -q` → 262 passed; dataset
   coverage 97.2%.

## What is outstanding

- **Delta re-check: requested, not delivered.** The reviewer subagent (`a3e44ee5fead2ec08`, worktree
  `.claude/worktrees/agent-a3e44ee5fead2ec08`) was asked to verify the fixes rather than re-review the
  milestone. It has since stopped **without producing a re-check report**. Nothing about the fixes has
  been independently confirmed, and no verdict should be assumed. It must be re-requested — resuming
  that agent by name keeps its context, or a fresh reviewer can be given the same brief.
- **M2 milestone review:** not started. It follows the delta re-check, in a clean worktree, and is
  separate from the dataset review already answered.
- **Tag `m2-complete`:** not created. It waits on the two items above.
- **PB-25 — the 5,000,000-row run (ML-DATA-01):** never performed. `workflow_dispatch` registers only
  from the default branch, which is still `m0/bootstrap`, and the owner's direction is that it must not
  run on this laptop. ML-DATA-01 sits at `VERIFIED_AT_REDUCED_SCALE` in the register with that reason.
- **PB-26 — month partition key** is the local month while timestamps are UTC. Owner approved fixing it
  **before M3 features begin**, because M3 reads these partitions and a wrong key would silently
  distort time-window features.

## Open owner actions

- **Default branch is still `m0/bootstrap`** (verified this session with `git ls-remote --symref origin
  HEAD`). Until it is `main`: no `workflow_dispatch` workflow registers, so PB-25 stays blocked, and
  `.github/workflows/dataset.yml` should not be pushed to `main` for the sake of dispatch.
- **`gh` has no credentials in this session.** No `~/.config/gh`, no `GH_TOKEN`. Consequences: CI is
  read through the unauthenticated REST API; workflows cannot be dispatched; `m0/bootstrap` cannot be
  deleted; the `protect-main` ruleset cannot be verified (GOV-9).
- **The Africa-wide direction** is recorded in ADR 0023 and PB-29…PB-34, to be implemented as a scoped
  branch after M2 closes and before M3 features. No code for it has been written.

## Exact next three steps

1. **Re-request the delta re-check** of `037631a` against `docs/reviews/M2/dataset-response.md`, asking
   specifically: does the account-event oracle still reproduce (the probe should now show the base
   rate, not 99.8%); do the three planted leaks now fail their gates; is `CV_TREE_NULL_INFLATION = 1.3`
   conservative at the reviewer's three measured regimes; does a junk citation still get past the
   loader; and are the two judgement calls acceptable — excluding event *type* and event-to-transaction
   delay from the construction check, and accepting 0.711 on `merchant_category_code` as designed
   signal. Constraints: 60,000 rows or fewer, nothing at 1M or above, report only.
2. **Run the M2 milestone review** in a clean worktree once the re-check closes, scoped as the owner
   directed to leakage and shortcut detection, determinism across chunk sizes, provenance honesty, and
   whether realism claims are backed by tests. Fix BLOCKER and MAJOR only; log the rest.
3. **Tag `m2-complete`** on the branch head, annotated with the job-level evidence from
   `docs/reviews/M2/gate-evidence.md` and the two open items stated plainly (PB-25, the release-size
   run; PB-26, the partition key). Then start the Africa-wide branch from ADR 0023, beginning with the
   country packs and the seeder's Part E.5 source, and fix PB-26 in it before any M3 feature work.

## Things that will bite whoever picks this up

- **A run conclusion is not evidence.** `stack` and `devcontainer` are path-filtered: they conclude
  "success" with their real job skipped. Quote jobs. Both traps are documented inside `stack.yml`.
- **A later push cancels a gate run.** The stack concurrency group cancels in-progress runs on the same
  ref, including for a docs-only commit. A run collected as evidence must be the last push until it
  finishes.
- **The traceability register is generated.** `fs-traceability-seed --check` rejects hand-written rows;
  progress fields (status, implementation, evidence, deviations, notes, reduced_scale) are preserved,
  everything else comes from the SRS or the build prompt's Part B. Run `fs-traceability-seed` then
  `fs-traceability render` after editing it.
- **`pre-commit` stashes unstaged changes**, so the rendered matrix must be staged together with any
  test file whose requirement tags changed, or the governance hook fails.
- **The commit-message hook wraps at 72 characters** and rejects a line over it; long messages are
  easier to write to a file and pass with `git commit -F`.
- **This laptop is shared with the owner's own jobs.** Load average reached 14 during M2; long
  generations belong in the background, and the 5M run must not run here at all.
