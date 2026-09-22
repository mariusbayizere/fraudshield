# Session state — M4's evidence is in, and it is mostly about the benchmark

Rewritten 2026-09-22 (late). Facts only; where something is unverified, assumed or open it says so.

## Read this first

**Both of M4's generalisation experiments are null, for one reason.** The benchmark encodes fraud
as **bursts**, so every country, every fraud variant and every feature group is a view of that one
structure. A country hold-out withholds no mechanism; a variant differing only in timing withholds
no pattern; an ablation removes no unique signal. This benchmark supports **no generalisation
claim**. It supports claims about detection *given* burst-structured fraud, and about the cost of
computing and explaining that detection.

The generator has deliberately **not** been changed to make any of these informative. Engineering
a difference so a test can fail is tuning the benchmark to produce a result (owner decision).

## The dataset

| | |
|---|---|
| Path | `dataset/output/bench1m` · split `split.json` · packs `packs.json` · cache `features_full.parquet` |
| Rows | 1,006,249, seed 20260917 |
| Fingerprint | `6abde44e5e952f4f5c6b86aac0fb934cd4de023b53ec1f2cfe9dc01cf0a3e420` (**v2**) |
| Generated at | commit `ca2007d`, through `fs-evidence` on a clean tree |
| Cache | 110,000 rows: 30,000 train, 60,000 test (551 fraud), 20,000 calibration; carries segment, country, channel and fraud sub-variant |

## The results

| | Evidence |
|---|---|
| Model AUC **0.991** ±0.006, **+0.098** over the best complete-coverage feature, **+0.326** over the best trivial rule, recall@1%FPR 0.947 | `docs/benchmarks/m4_first_evaluation.md` |
| Calibration: overall ECE 0.0005, **decision region (≥0.60) ECE 0.0145 over 465 rows / 436 fraud**, Brier 0.04855 | `docs/benchmarks/m4_battery.md` |
| SHAP additivity error 1.28 × 10⁻⁵, exact TreeSHAP, no new dependency | same |
| **Redundancy: four disjoint groups each ≥ 0.845 alone**, removing any one costs ≤ 0.031 | same, PB-60 |
| **LOCO null**: removing a country from training costs ≤ 0.002 | same, PB-59 |
| **Novel variant null**: unseen shape caught at 100% vs 95.1% for the familiar one | same, PB-61 |
| **Frontier**: explanation cost at p50 grows **0.08 → 2.80 ms** (35×) across tree complexity while AUC moves −0.004, inside every interval | `docs/benchmarks/m4_frontier.md` |

**PB-56's prediction held.** Recorded at `0138099` before the draw existed: the event-delay channel
would fall from 0.758 into 0.68–0.74. Measured **0.730**, so 11% of that channel's excess over 0.5
was the assumed lead window and the rest is the scenario. C-11 is now MEASURED.

## The paper

`docs/research/paper/contributions.md` — the evidence-backed contribution list. The LaTeX draft is
M11 and unstarted. **Kept:** benchmark-construction methodology and its measured failure modes;
the latency–explainability frontier; the redundancy finding; the system as setting. **Removed:**
cross-country generalisation, unseen-variant generalisation, and C-4's card-style ablation.

## Open, in the order I would take them

| Item | What |
|---|---|
| **C-6** | seed variance — every figure so far is one seed, and it is the cheapest remaining gap now the cache exists |
| **C-5** | per-month performance, measurable and unmeasured |
| — | precision at a fixed alert budget: the operational number, not yet reported |
| **PB-57** | `fs-evidence` captures instead of streaming, so a long run is silent |
| **PB-51** | FR-02-02's `< 10 ms`, carried to M10, needs the dedicated machine |
| **PB-44** | five features still have no source data; all wait on a per-account or agent table |
| **PB-43** | `corridor_class` reaches two of its four classes; owner decision is to keep them |
| **PB-36/37/38** | untested DB fallback; no per-account durable table; 8-day refresh under a 30-day feature |
| **PB-25** | the 5M run, still blocked on the default branch |

Also unmeasured: ONNX export parity, the ensemble the SRS specifies (this is one booster, not
0.55·XGB + 0.45·LGBM), and the LaTeX tables.

## Things that will bite whoever picks this up

- **Ask whether an experiment can fail before promoting it to headline evidence.** Both
  generalisation tests were chosen as the load-bearing evidence before anyone checked whether the
  benchmark could make either informative. The answer was in `fraud.yaml` — no parameter keyed by
  country — and in the variant's own definition, which changes a delay and nothing else.
- **A hedge is often a measurable claim nobody measured.** Three times this week: C-11's delay
  proportion, PB-46's "burstiness is not country-specific", and the novel variant. The test is
  *does this sentence say what a measurement would show?*
- **The p99 of a single request is not measurable on a development machine.** Two of five
  configurations produced a negative explanation cost, which is impossible. p50 is stable.
- **Profile before optimising.** The 23× feature-pass win was `dict(context.outcomes)` copied
  twice per row, not the indexing I had reasoned my way to from the code.
- **A perfectly separated fixture has a Hanley–McNeil variance of exactly zero**, so an interval
  comparison passes at `0.0 > 0.0` whatever the code does. Three tests have now hit this.
- **`fs-evidence` refuses on a dirty tree**, so commit before running. It caught me twice today,
  both times correctly.
- **Impossible numbers are the useful kind.** Recall 0.904 at AUC 0.551 (PB-58) and a negative
  explanation cost both pointed straight at their causes; a merely *surprising* number would not
  have.
- Timings: ml suite ~8 s; dataset suite ~31–46 min; 1M generation ~24 min; feature pass ~450
  rows/s at a 400k corpus; `fs-features battery` ~4 min; `fs-features frontier` ~2 min.
