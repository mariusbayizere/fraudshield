# The latency–explainability frontier (D-16)

Raw output and provenance stamp: `docs/benchmarks/m4_frontier_6abde44e.txt`.

D-16 makes tree complexity a constrained hyperparameter — reject any configuration whose
single-request p99, model plus exact TreeSHAP, exceeds 40 ms — and asks for the trade-off curve.
This is that curve, measured on the cached feature matrix of the draw `6abde44e`, 60,000 held-out
rows with 551 fraud, 900 timed single requests per configuration after 50 discarded as warm-up.

| Trees | Depth | AUC | predict p50 | +SHAP p50 | **SHAP cost** |
|---:|---:|---|---:|---:|---:|
| 50 | 3 | 0.995 ±0.004 | 0.48 ms | 0.56 ms | **0.08 ms** |
| 100 | 4 | 0.992 ±0.005 | 0.48 | 0.81 | 0.33 |
| 200 | 5 | 0.991 ±0.006 | 0.49 | 1.49 | 1.00 |
| 400 | 6 | 0.990 ±0.006 | 0.52 | 2.38 | 1.86 |
| 800 | 8 | 0.990 ±0.006 | 0.56 | 3.36 | **2.80** |

## What it says

**Complexity buys no accuracy here and costs explanation latency.** From the smallest
configuration to the largest, AUC moves −0.004 — inside every interval in the table — while the
cost of explaining a decision grows **35×**, from 0.08 ms to 2.80 ms. Prediction alone grows 17%,
from 0.48 to 0.56 ms, so essentially all of the additional cost is the explanation.

The accuracy half is a property of *this benchmark* and should not be generalised: the dataset is
easy (`docs/benchmarks/m4_battery.md`), so extra capacity has nothing to find. The latency half is
a property of the **model and the SHAP algorithm** — exact TreeSHAP is linear in trees and
quadratic-ish in depth — and it is what makes this the most defensible quantitative result M4 has.
A tree twice as deep costs what it costs whether the fraud is easy or hard to find.

The practical reading: on a benchmark like this one, the explanation budget and not the accuracy
target should choose the tree configuration, because accuracy stops paying long before latency
does.

## Why the p99 columns are not reported as results

The raw output prints them and then says they are not measurement on this machine. **Two of five
configurations show a negative explanation cost at p99**, which is impossible — explaining cannot
be faster than not explaining. The single-request p99 here is dominated by scheduler jitter rather
than by model complexity, and the negative figure is the proof.

That is ADR 0010's rule demonstrated rather than asserted: latency percentiles may be quoted as
gate evidence only from the dedicated machine in `docs/benchmarks/hardware.md`, because a shared
or busy machine cannot produce a repeatable percentile. **D-16's 40 ms constraint cannot be
applied from this run**, and the table does not pretend to apply it.

It took two attempts to see this. The first run had no warm-up and showed a p99 of 16.67 ms for
the first configuration against 4.86 for the next, with an explanation cost of −4.58 ms; adding a
warm-up made the p99 *worse*, not better, which is what established that the instability was the
machine rather than the code.

## What this does not establish

- **Not a gate.** No figure here may be quoted against D-16's 40 ms.
- **One machine, one seed, one model family.** No ensemble, so nothing here speaks to the
  0.55·XGBoost + 0.45·LightGBM combination the SRS specifies.
- **Single-process.** The SRS's multi-process serving is not exercised; this is one booster in one
  interpreter.
- **The SHAP cost is for every row.** In serving, exact TreeSHAP runs on flagged rows only, so the
  per-request cost in production is this figure times the flag rate, not this figure.
