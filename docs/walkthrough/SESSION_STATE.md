# Session state — M4 has calibration, SHAP, ablations, breakdowns and LOCO

Rewritten 2026-09-22. Facts only; where something is unverified, assumed or open it says so.

## The dataset

| | |
|---|---|
| Path | `dataset/output/bench1m` (gitignored) · split `dataset/output/split.json` · packs `dataset/output/packs.json` |
| Rows | 1,006,249, seed 20260917, `--rows 1000000` |
| Fingerprint | `6abde44e5e952f4f5c6b86aac0fb934cd4de023b53ec1f2cfe9dc01cf0a3e420` (**v2**) |
| Generated at | commit `ca2007d`, through `fs-evidence` on a clean tree |
| Feature cache | `dataset/output/features_full.parquet` — 110,000 rows, 12 MB, carries segment, country and channel |
| Report | `dataset/realism_report.md`, carrying that fingerprint; **all gate checks pass** |

Earlier draws: `c8856a0e` (before the lead tail), `b896b632`/`40a77bb6` (before device sharing;
`40a77bb6` is a **v1** fingerprint and not comparable).

## The results

`docs/benchmarks/m4_first_evaluation.md` and `docs/benchmarks/m4_battery.md`. **60,000 held-out
rows, 551 confirmed fraud**, so the interval is ±0.006 rather than the ±0.020 a 63-fraud sample gave.

| | |
|---|---|
| Model AUC | **0.991** ±0.006 |
| Margin over the best complete-coverage feature (`velocity_ratio_1h_vs_30d`, 0.893) | **+0.098** |
| Margin over the best trivial rule (0.665) | **+0.326** |
| Recall at 1% FPR | 0.947 |
| Expected calibration error | 0.0005 (Brier 0.00187 → 0.00186) |
| SHAP additivity error | 1.28 × 10⁻⁵ |

Untuned and trained on 30,000 rows spanning 128 days. Comparable with the gates in its **split**,
not in its training volume.

## The two findings that change M4's plan

**1. Leave-one-country-out measures nothing (PB-59).** Removing a country from training entirely
costs at most 0.002 AUC. PB-46 assigned LOCO half the weight the headline AUC could no longer
carry; it carries none. PB-46 also *named the mechanism* as a risk four days earlier —
"burstiness is not country-specific" — which was a prediction written as a hedge.

**2. The feature set is massively redundant (PB-60).** Removing any of ten groups costs ≤0.031;
four disjoint groups each reach **≥0.845 alone**. So an ablation on this benchmark cannot say
which features matter, and C-4's planned "card-style vs full EAC" comparison will not work.

**What still carries weight:** the **novel sub-variant**, a fraud shape present only in the test
period. A temporal hold-out is the one test here that can still fail. **It is the next thing M4
owes.**

## Where PB-56 landed

Predicted before the draw existed (`0138099`): giving `takeover_lead_minutes` a long tail would
drop the event-delay channel from 0.758 into 0.68–0.74. Measured **0.730** — confirmed, and the
residual is the finding: the fall of 0.028 is **11% of the excess over 0.5**, so nine tenths of
that channel was the scenario and one tenth was the assumed window. C-11 moves to MEASURED.

## Open, in the order I would take them

| Item | What |
|---|---|
| **PB-59** | make the novel sub-variant experiment load-bearing; report LOCO as a negative result |
| **PB-60** | decide what C-4 becomes; report the two ablation tables together or neither |
| **PB-57** | `fs-evidence` captures instead of streaming, so a long run is silent |
| **PB-51** | FR-02-02's `< 10 ms`, carried to M10, needs the machine in `docs/benchmarks/hardware.md` |
| **PB-44** | five features still have no source data; all wait on a per-account or agent table |
| **PB-43** | `corridor_class` reaches two of its four classes; owner decision is to keep them |
| **PB-36/37/38** | untested DB fallback; no per-account durable table; 8-day refresh under a 30-day feature |
| **PB-25** | the 5M run, still blocked on the default branch |

Not yet covered by any run: seed variance (C-6), precision at a fixed alert budget, per-month
performance (C-5), ONNX export parity, and the LaTeX tables.

## What landed today

- **PB-56 closed** with the prediction recorded first and confirmed.
- **PB-40's draw regenerated**; all gates green at `6abde44e`.
- **23× faster feature pass.** `geo_cell_fraud_rate_30d` and `counterparty_confirmed_fraud_90d`
  were each handed `dict(context.outcomes)` — a copy of every outcome in the corpus, twice per
  scored row, 40.5 ms against a total of about 40. Passing the mapping: 452 rows/s against 19,
  zero values changed. This is what made the rest of the day affordable.
- **PB-58:** recall at 1% FPR counted negatives tied *on* the threshold for free, and printed
  recall 0.904 for a model at AUC 0.551. Strictly-above fixes it. Every figure quoted before is
  unaffected, because ties are rare when a model works — which is the shape worth remembering.
- **`fs-features battery`**: calibration, SHAP, both ablation directions, breakdowns, LOCO — all
  off one cached matrix, no new dependency.

## Things that will bite whoever picks this up

- **Profile before optimising.** I read the code, found `CorpusIndex.before` rebuilding a
  timestamp list per call, and was right about the code and wrong about the cost: it was 0.097 s
  of 11.8. The profile said 88% was `compute`'s *own* body, and a function that mostly delegates
  should not have self time.
- **Watch memory before sizing a run.** 700k corpus + 161,841 scored rows does not fit in 1.5 GB
  available; 400k + 110,000 does, at about 1 GB.
- **A perfectly separated fixture has a Hanley–McNeil variance of exactly zero**, so an interval
  comparison passes at `0.0 > 0.0` whatever the code does. Two tests have now fallen into it.
- **Platt scaling on a signal-free fixture fits a negative slope** and reverses the order, which
  is correct and refutes the assertion rather than the code.
- **`fs-evidence` refuses on a dirty tree**, so regenerate → commit → run, in that order.
- **One heavy job at a time**, and keep evidence datasets in `dataset/output/`, which survives a
  session clear; the scratchpad does not.
- Timings here: ml suite ~8 s; dataset suite ~31–46 min; a 1M generation ~24; `fs-dataset report`
  on 1M ~4; feature pass **~450 rows/s** at a 400k corpus; `fs-features battery` ~6 min.
