# Session state — one non-burst variant was measured, and it fails hard

Rewritten 2026-09-22 (late). Facts only; where something is unverified, assumed or open it says so.

## Read this first

**Both of M4's original generalisation experiments (LOCO, the temporal novel-variant) were null,
for one reason: this benchmark encodes fraud as bursts.** A third, pre-registered experiment was
then added and measured this session — a variant differing in *mechanism*, not just timing or
geography — and it is **not** null.

**`reversal_scam_social_engineering`** (victim-initiated, single transaction, established
counterparty — a real "sent by mistake, please return" typology, grounded outside the benchmark's
own construction; pre-registered before generation, ADR 0028) is caught at **6.1%** (4 of 66 fraud
rows, Wilson 95% interval **[2.4%, 14.6%]**), against **94.2%** [92.4%, 95.6%] for the base
scenarios at the same 1%-FPR threshold. The intervals do not overlap. This falls **below** the
pre-registered prediction range (0.15–0.55) — the model's dependence on burst-structure and
counterparty-novelty was underestimated, not overestimated. Full writeup:
`docs/research/lab_notebook.md`, entries dated 2026-09-22 ("Pre-registration" and "The result").

This is now the benchmark's one measured non-burst detection failure. It does not reopen the
geographic or temporal generalisation claims — those remain null for the reasons already recorded
— and it does not generalise past this one typology (romance scams, invoice fraud, other
authorised-push-payment variants are untested).

## The dataset

| | |
|---|---|
| Path | `dataset/output/bench1m` · split `split.json` · packs `packs.json` · cache `features_full.parquet` |
| Rows | 1,006,317, seed 20260917 (+68 over the previous draw, all `reversal_scam_social_engineering`) |
| Fingerprint | `d8083dbc742c20437bf3d060614f88849059eb8cf12bd0d3bbb092b518e6be32` (**v2**) |
| Generated at | commit `656d24e`, through `fs-evidence` on a clean tree |
| Realism gates | all pass — `merchant_category_code` 0.709, shortcut 0.503, event delay 0.726, trivial rule 0.606 (`docs/benchmarks/m4_realism_pb61.txt`) |
| Cache | full test period scored (not a sample): 101,909 test rows (985 fraud), 30,000 train, 20,000 calibration — sized so all 66 reversal-scam rows are captured |

## The results

| | Evidence |
|---|---|
| Model AUC **0.968** ±0.008, **+0.089** over the floor (0.879) | `docs/benchmarks/m4_evaluate_pb61.txt` |
| **Redundancy: four disjoint groups each ≥ 0.845 alone**, removing any one costs ≤ 0.031 | `docs/benchmarks/m4_battery_pb61.txt`, PB-60 |
| **LOCO null**: removing a country from training costs ≤ 0.002 | same, PB-59 |
| **Temporal novel-variant null**: unseen shape caught at 97.3% [90.5%, 99.2%] vs 94.2% [92.4%, 95.6%] for the familiar one — intervals overlap, easier not harder | same, PB-61 |
| **Reversal-scam variant fails**: 6.1% [2.4%, 14.6%] vs 94.2% [92.4%, 95.6%] — intervals do not overlap, a supported finding | same, PB-61, ADR 0028 |
| **C-6 measured**: ensemble seed-variance stdev **-0.5%** vs XGBoost alone (did not reduce), **+62.9%** vs LightGBM alone (reduced). D-09's claimed 12% figure holds only against the weaker base model | `docs/benchmarks/m4_seed_variance_pb61.txt` |
| LightGBM cleared through `fs-licences`: 288 deps, 0 violations. `xgboost`, `scipy`, `nvidia-nccl-cu12` licence exceptions verified against bundled text, not declared metadata | ADR 0009 Amendment 2026-09-22 |

**Every table now carries its own recall confidence interval** (Wilson score), not just AUC's
Hanley-McNeil interval — a bare recall on ~66 rows was reading as more settled than the sample
size supports, so the artefact says so itself rather than requiring a reader to compute it.

## Branch hygiene — a wrong premise, corrected

**`main` has not merged M2 or M3.** `main`'s HEAD is still `a7e6896`, the M1-close commit;
`git merge-base --is-ancestor m3-complete origin/main` returns NO. M0 and M1 genuinely were merged
(linear history, no merge commits), but M2 (`m2/generator`) and M3 (`m3/features`) were not, despite
being tagged complete. This corrects an assumption stated earlier in the session that every
milestone had merged after review.

**This session's M4 work moved off `m3/features`** onto a new branch, **`m4/generalisation`**,
created from `m3/features`'s tip (129 commits ahead of `main` at the branch point) and pushed to
origin. All of today's commits — the recall-interval feature, the re-run battery evidence, C-6,
and the PB-61 writeup — are on this branch, not on `m3/features`.

**Not done, and an owner decision rather than something to resolve unilaterally:** whether/how to
merge M2, M3 and now M4 into `main`. Three milestones' worth of history would land in one merge if
done now; whether that happens as one merge, three sequential ones, or `main` is redefined to track
a later branch is a call for the owner, not something this session should decide by pushing to
`main` directly.

## The paper

`docs/research/paper/contributions.md` — the evidence-backed contribution list. The LaTeX draft is
M11 and unstarted. **Kept:** benchmark-construction methodology and its measured failure modes; the
latency–explainability frontier; the redundancy finding; the reversal-scam variant as the one
measured non-burst failure; the system as setting. **Removed:** cross-country generalisation,
temporal-novel-variant generalisation, and C-4's card-style ablation.

## Open, in the order I would take them

| Item | What |
|---|---|
| **Branch/main** | owner decision above — how M2/M3/M4 reach `main`, if at all before M11 |
| **C-5** | per-month performance, measurable and unmeasured |
| — | precision at a fixed alert budget: the operational number, not yet reported |
| **PB-57** | `fs-evidence` captures instead of streaming, so a long run is silent |
| **PB-51** | FR-02-02's `< 10 ms`, carried to M10, needs the dedicated machine |
| **PB-44** | five features still have no source data; all wait on a per-account or agent table |
| **PB-43** | `corridor_class` reaches two of its four classes; owner decision is to keep them |
| **PB-36/37/38** | untested DB fallback; no per-account durable table; 8-day refresh under a 30-day feature |
| **PB-25** | the 5M run, still blocked on the default branch |

Also unmeasured: ONNX export parity, the ensemble the SRS specifies (this is one booster, not
0.55·XGB + 0.45·LGBM, in the deployed model — the ensemble exists only in `training/ensemble.py`
for C-6), and the LaTeX tables.

## Things that will bite whoever picks this up

- **A null generalisation result is not the end of the question — ask what a genuinely different
  mechanism would show before concluding "this benchmark supports no generalisation claim at
  all."** The two null results (country, timing) never removed the axes the model actually uses;
  the one variant that did (burst-structure, counterparty-novelty) found a real 6.1% recall
  failure. The difference between a null result and a missing test is whether the thing that
  varies is the thing the model depends on.
- **Pre-registration only works if the commit predates the measurement, provably.** The lab
  notebook's prediction (0.15–0.55) was wrong — the actual result (6.1%) fell below it — and that
  is reported as-is rather than revised, which is the entire point of writing the prediction down
  first.
- **A citable-run artefact can go stale relative to its own rendering code.** The battery evidence
  had to be re-run after `report.py` changed to print the CI column, even though the underlying
  measurement was unchanged — the artefact is a function of the code that rendered it, not just the
  data it scored.
- **A mechanism built and tested is not evidence until it runs through `fs-evidence`.** C-6 was
  implemented, unit-tested and even run manually as a preview earlier this session, and still
  needed a citable run before the claims register could move off `UNVERIFIED`.
- **Ask whether an experiment can fail before promoting it to headline evidence.** Both original
  generalisation tests were chosen as load-bearing evidence before anyone checked whether the
  benchmark could make either informative. The answer was in `fraud.yaml` — no parameter keyed by
  country — and in the variant's own definition, which changed a delay and nothing else.
- **A hedge is often a measurable claim nobody measured.** C-11's delay proportion, PB-46's
  "burstiness is not country-specific", the temporal novel variant, and now C-6's 12% figure — all
  four turned out to have a real measurement behind the hedge, in each case different from the
  claim.
- **A package's declared licence metadata is not authoritative.** `nvidia-nccl-cu12` declares
  `LicenseRef-NVIDIA-Proprietary` in its own metadata while bundling genuine BSD-3-Clause NCCL
  text — the bundled file is what was checked, following the `h3`/`nodeenv` precedent.
- **`fs-evidence` refuses on a dirty tree**, including untracked files — a stale evidence artefact
  left over from before a rendering change has to be removed or committed before the next run, not
  just the source.
- **A perfectly separated fixture has a Hanley–McNeil variance of exactly zero**, so an interval
  comparison passes at `0.0 > 0.0` whatever the code does. The same trap exists for Wilson
  intervals at `recall == 1.0`; a dedicated test now checks the interval stays inside [0, 1] there.
- Timings: ml suite ~8 s (excluding coverage gate on a partial run); dataset suite ~31–46 min; 1M
  generation ~24 min; feature pass ~450 rows/s at a 400k corpus; `fs-features battery` on the full
  101,909-row test period, a few minutes; `fs-features seed-variance`, five seeds, a few minutes.
