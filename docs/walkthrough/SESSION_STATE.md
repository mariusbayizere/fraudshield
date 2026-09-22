# Session state — M4 reviewed and approved; the gate passes 9 of 11 and M4 is not yet closed

Rewritten 2026-09-22 (evening). Facts only; where something is unverified, assumed or open it says
so.

## Read this first

**One decision is waiting for the owner: whether M4 closes with two gate metrics failing.** The
declared gate run (`docs/benchmarks/m4_gate_d8083dbc_v3.txt`) passes 9 of 11. **ML-GATE-03 (recall
at 0.60, 0.736 against 0.88) and ML-GATE-06 (FNR at 0.60, 0.264 against 0.12) fail.** D.3 says
record, do not tune. The rows stay `IN_PROGRESS`, M4 is not in `milestones.yaml`'s `completed`,
and `m4-complete` is not tagged (ADR 0031). PB-62 lists the three options.

**The M4 milestone review is APPROVED_WITH_MINORS** (`docs/reviews/M4/milestone-review.md`). The
first pass found 5 BLOCKERs and 5 MAJORs; five more were found while fixing them. All fifteen are
fixed. The reviewer is the author, and the record says so.

## Branches and CI

- **`main` is at `m3-complete` (`f8885d6`)**, green on every job. It is fast-forwarded to the M4
  head only after CI is green there.
- **`m4/generalisation`** is PR #1 against `main`. The default branch is still `m0/bootstrap`, a
  repository setting only the owner can change (PB-25 stays blocked).
- `m2-complete` sits on a commit whose python CI job fails; it was tagged before any CI run on it
  (lab notebook). Not moved.

## What M4 now contains

| | Where |
|---|---|
| D-05's ensemble: XGBoost and LightGBM, early-stopped on validation average precision, imbalance-weighted, one isotonic calibrator on the 0.55/0.45 combination, float32-exact | `ml/src/fraudshield_ml/training/model.py` |
| D-06's Isolation Forest, raw score and percentile score | `training/anomaly.py` |
| Per-model exact SHAP, the margin-space combination, top-5, coverage | `training/explain.py`, `docs/ml/explainability.md` |
| The eleven M4 gate metrics, bootstrap CIs, DeLong, baselines, ablations, seeds, `metrics.json`, LaTeX, SVG | `training/gate*.py`, `fs-features gate` |
| ONNX export and parity | `training/onnx_export.py` |
| Test-set access log, back-filled | `docs/benchmarks/test_set_access.jsonl` |
| E1's time-ordered target encoding | `training/smoke.py` |

## The results that matter

| | Evidence |
|---|---|
| Gate: AUC 0.970 [0.962, 0.977], +0.091 over the best single feature, +0.311 over the best trivial rule | `m4_gate_d8083dbc_v3.txt` |
| Gate fails: recall at 0.60 0.736, FNR 0.264; even at the 1% FPR budget recall is 0.871 | same, PB-62 |
| ONNX parity < 1e-6 on all 101,909 test rows | same |
| Reversal-scam variant **9.1%** (6/66, [4.2%, 18.4%]) vs base 94.4%; below the pre-registered 0.15 as a point estimate, not as a whole interval | `m4_battery_d8083dbc_e1.txt` |
| C-6: ensemble −0.6% vs XGBoost, +12.5% vs LightGBM | `m4_seed_variance_d8083dbc_e1.txt` |
| Ablation: card-style features only −0.042 AUC (C-4's measurement; PB-60's redundancy caveat applies) | gate run |

## Open, in the order I would take them

| Item | What |
|---|---|
| **Owner** | ML-GATE-03/06 (PB-62), then the M4 tag; ADR 0030 (MLflow) awaits confirmation |
| PB-67 | a larger training sample, reported beside the current run |
| PB-63 | the CI ML gate needs a dataset in CI (M9) |
| PB-64, 65 | clauses carried to M5 and M6 |
| PB-66 | E.5 items beyond the gate |
| PB-68 | frontier and battery prose at `d8083dbc` |
| M8 | the owner's next milestone once M4 is settled |

## Things that will bite whoever picks this up

- **Read a requirement's notes before building it.** E1 and PB-46's reporting rule were both
  written on the M4 rows as settled decisions, and both were missed until the register was being
  updated at the very end.
- **A parity test on random data proves little.** The ONNX test passed on normals and the real
  data failed on 0.39% of rows; the new test builds doubles that collapse to one float32.
- **An impossible number is the best bug report** — precision above its own ceiling pointed
  straight at isotonic ties.
- **Commit before running a mutation script that restores from git.** One run reset an
  uncommitted fix; a stash taken beforehand is what saved it.
- **Never let a cleanup step run after a refused evidence run.** One reverted the access log and
  lost a line, now reconstructed and marked.
- **Several sessions share `/home/marius/fraudshield`; `backend/` belongs to the M7 agent.** Work in
  a worktree with its own `UV_PROJECT_ENVIRONMENT`, with absolute paths.
- **Read CI on the branch you push to.** This branch's python job was red for 22 runs.
- **`fs-evidence` refuses on a dirty tree, untracked files included** — which is why evidence runs
  from a separate frozen worktree here.
- **Unauthenticated GitHub API: 60 requests an hour.**
- Timings: ml suite ~1–2 min with coverage; dataset suite 30–60 min under load; gate cache build
  ~13 min; `fs-features gate` with ablations and five seeds ~5 min; battery ~3 min.
