# M4 battery: calibration, explanations, ablations, breakdowns, generalisation

Raw output and provenance stamp: `docs/benchmarks/m4_battery_6abde44e.txt`. Every figure is on
D-07's test period of the draw `6abde44e`, every model is fitted on its train period, nothing is
tuned. **60,000 held-out rows holding 551 confirmed fraud**, so the headline interval is ±0.006
rather than the ±0.020 a 63-fraud sample gave.

| | |
|---|---|
| Model AUC | **0.991** ±0.006 |
| Margin over the best complete-coverage single feature (`velocity_ratio_1h_vs_30d`, 0.893) | **+0.098** |
| Margin over the best trivial rule (`amount_to_max_90d_ratio`, 0.665) | **+0.326** |
| Recall at 1% FPR | 0.947 |
| Expected calibration error | 0.0005 |
| SHAP additivity error | 1.28 × 10⁻⁵ |

## Two results that change what M4 can claim

### 1. The feature set is massively redundant

Removing any one of the ten registry groups costs **at most 0.031 AUC**, and eight of the ten cost
0.001 or less. Read alone that says every group is worthless, which cannot be true of a model at
0.991. Keeping only one group says why:

| Group alone | AUC | | Group removed | AUC |
|---|---:|---|---|---:|
| counterparty (4) | **0.949** | | without counterparty | 0.960 |
| velocity (8) | **0.871** | | without velocity | 0.990 |
| temporal (6) | **0.861** | | without temporal | 0.989 |
| geographic (5) | **0.845** | | without geographic | 0.990 |
| device and channel (5) | 0.764 | | without device and channel | 0.990 |
| synthetic identity (1) | 0.730 | | without synthetic identity | 0.991 |
| amount behaviour (4) | 0.660 | | without amount behaviour | 0.991 |
| account profile (2) | 0.561 | | without account profile | 0.990 |
| agent (2) | 0.551 | | without agent | 0.990 |
| corridor (1) | 0.491 | | without corridor | 0.990 |

**Four independent groups each reach 0.845 or better on their own.** The benchmark can be solved
several different ways, so no single group is load-bearing and a leave-one-out ablation reports
every one of them as free. That is a fact about the benchmark, not about the model, and it means
an ablation table on this dataset cannot be read as a statement about which features matter in
production.

It also disposes of a planned experiment. C-4 proposed "card-style feature set only vs full EAC
feature set" as the replacement for an unverifiable literature claim. On a benchmark where four
disjoint groups each reach 0.85 alone, that comparison will show a small difference whatever is
true, and the small difference will mean nothing.

### 2. Leave-one-country-out is not a demanding test here

| Country | In-sample | Country unseen in training | Difference |
|---|---:|---:|---:|
| KE | 0.996 | 0.996 | 0.000 |
| RW | 0.994 | 0.993 | −0.001 |
| TZ | 0.981 | 0.979 | −0.002 |
| UG | 0.975 | 0.976 | +0.001 |
| CD | 0.999 | 0.999 | 0.000 (7 fraud rows — direction only) |

Removing a country from training **entirely** costs nothing measurable. PB-46 recorded the
expectation that "leave-one-country-out and the novel sub-variant carry the weight the headline
AUC no longer can". Half of that is now refuted: LOCO carries no weight on this benchmark,
because the fraud patterns are not country-specific. PB-46 anticipated the mechanism — "burstiness
is not country-specific, so a velocity threshold transfers trivially" — and named it as a risk;
the measurement says the risk was real.

**What still carries weight** is the novel sub-variant, which is a *temporal* hold-out rather than
a geographic one: a fraud shape that appears only in the test period cannot be learned from the
training period however transferable the rest is. That experiment is not in this battery and is
the next thing M4 owes.

## Calibration

Platt scaling fitted on D-07's calibration period — 20,000 rows that are neither fitted on nor
scored. AUC is unchanged by construction, since a monotone map cannot reorder.

Brier 0.00187 → 0.00186; expected calibration error **0.0005**. The model was already calibrated,
which is the honest reading: an untuned XGBoost on a 0.9% base rate with 30,000 training rows is
not obviously miscalibrated, and the reliability table bears that out — the top bin predicts 0.980
against an observed 0.978 over 357 rows.

The thin middle bins (19 to 66 rows each) swing widely, and that is sampling rather than
miscalibration. There is very little probability mass between 0.1 and 0.9 because the model is
usually confident.

## Explanations

Exact TreeSHAP from XGBoost's own `pred_contribs` — no new dependency, and the additivity error of
1.28 × 10⁻⁵ is measured rather than assumed, because a value that violates additivity is not a
slightly wrong explanation but a different quantity wearing the name.

Top by mean |SHAP|: `counterparty_is_new_for_account` (1.19), `velocity_ratio_1h_vs_30d` (0.84),
`counterparty_confirmed_fraud_90d` (0.68), `device_age_days` (0.53), `seconds_since_last_tx`
(0.43), **`round_sum_flag` (0.36)**.

`round_sum_flag` is worth a note: it was `NO_SOURCE_DATA` and identically NaN until 2026-09-20,
when `round_denominations` became a pack field (PB-44). Three days later it is the sixth most
important feature in the model. A feature that returns NaN for every row is indistinguishable from
one that carries nothing, which is precisely why that distinction was made declarable.

## Breakdowns

By country, AUC runs 0.975 (UG) to 0.999 (CD, 7 fraud rows). By channel, 0.981 (ONLINE) to 0.998
(BANK_TRANSFER). No slice is anomalous, and every cell prints its fraud count because a slice runs
out of positives long before it runs out of rows.

## What this battery does not establish

- **It is one model and one seed.** No seed-variance study, so nothing here speaks to C-6.
- **It is untuned.** The validation period is identified and deliberately unused; a tuned model
  would need its tuning reported or the test figure is optimistic in a way no reader could detect.
- **It trains on 30,000 rows spanning 128 days**, not the whole train period. Comparable with the
  gates in its split, not in its training volume.
- **Thirteen gate metrics are not all covered.** Recall, AUC and calibration are; precision at a
  fixed alert budget, latency and the operational gates are not.
