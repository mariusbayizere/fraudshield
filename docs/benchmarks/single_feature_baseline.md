# The single-feature baseline: what a model on this benchmark must beat

**This is a result, not a caveat.** A reader should meet it before any model figure, because it
changes what every model figure means.

On `FraudShield-Africa-Transactions`, **one feature reaches `max(AUC, 1−AUC)` of 0.894**. So a
model reported at, say, AUC 0.94 is not 0.44 better than chance; it is **0.046 better than a single
threshold on `velocity_ratio_1h_vs_30d`**. Those are different claims, and only the second is
honest.

## The measurement

Commit `2c80ef6`, dataset of 1,006,249 rows at seed 20260917, corpus 200,000 transactions, 20,000
scored, **166 confirmed fraud**. `max(AUC, 1−AUC)` throughout, categoricals target-encoded
out-of-fold with folds grouped by **whole accounts** (E1). Hanley–McNeil 95% intervals; at this
fraud count that is about ±0.04. Raw output:
`docs/benchmarks/m3_single_feature_auc_2c80ef6.txt`.

| Feature | Separation | Interval |
|---|---:|---|
| `velocity_ratio_1h_vs_30d` | **0.894** | ±0.032 |
| `counterparty_is_new_for_account` | 0.851 | ±0.037 |
| `tx_count_1h` | 0.826 | ±0.039 |
| `implied_speed_kmh` | 0.812 | ±0.040 |
| `seconds_since_last_tx` | 0.811 | ±0.040 |
| `amount_sum_24h` | 0.776 | ±0.042 |
| `tx_count_24h` | 0.765 | ±0.043 |
| `unique_counterparties_24h` | 0.759 | ±0.043 |
| `synthetic_identity_score` | 0.759 | ±0.043 |

### An earlier version of this table was measured under a bias, now removed

The first measurement (`fad43dd`, retained as
`docs/benchmarks/m3_single_feature_auc_fad43dd.txt`) was taken before the M3 milestone review found
that five features declaring **unbounded** history were deriving their durable state from the
corpus they were handed. Because that corpus is the last 200,000 rows of a million, an account's
"first transaction" was the corpus's edge, and "never used this payee before" meant "not in this
window".

The review expected the headline to be inflated. **It was not.** Exactly three figures moved and
none was the one under suspicion:

| Feature | Before | After | Why |
|---|---:|---:|---|
| `counterparty_is_new_for_account` | 0.816 | **0.851** | truncation made legitimate payees look new too, suppressing the feature |
| `device_age_days` | 0.752 | 0.743 | devices looked younger than they were |
| `is_new_country_for_account` | 0.502 | 0.501 | negligible |
| `velocity_ratio_1h_vs_30d` | 0.894 | **0.894** | unchanged — `OBSERVED_CAPPED` caps the denominator at thirty days, so only accounts appearing solely in the final month were affected |

The last row is the useful one: the earlier record *argued* that the cap bounded the bias, and the
argument was right. It is now measured rather than argued, which is the difference between a caveat
and a result. A reader comparing the two versions of this page should read the movement as a
correction of method, not of conclusion — the benchmark was velocity-separable before the fix and
is velocity-separable after it, and the strongest single feature is the same one at the same
value.

Every figure is at that stated scale (E2), because single-feature AUC on this benchmark moves with
dataset size by more than seed noise and the cause is unexplained.

## Why the benchmark is like this, and why that is not a defect to fix

The five strongest are all **burst or recency** indicators, and the generator injects fraud as
**incidents**: a SIM-swap drain, a velocity run, a mule fan-out and a bust-out are each a rapid
sequence of transactions on one account. Recency and rate features find them because that is what
they are.

**Suppressing the burst structure would make the benchmark less realistic, not more.** Real
SIM-swap drains and real mule fan-out are genuinely bursty; a generator whose fraud was not
burst-shaped would be the weaker artifact, and tuning the data until a control passes is the
failure mode this project exists to catch.

**Restating D-08's ceiling as a claim about columns was also rejected** (owner decision
2026-09-20). It would be redefining a control so that it passes. D-08's ceiling stands as written,
it is **not met by the engineered features**, and the resolution is to make the failure visible in
every result rather than to move the line.

## The rule this imposes on M4

**No model metric may be reported without its single-feature baseline beside it.** A gate, not a
convention:

1. Every headline metric — ML-GATE-01 to -04, the channel AUCs ML-GATE-07 to -09, and the shadow
   delta ML-GATE-13 — is reported with **the best single feature** and **the best trivial rule**
   (one threshold on one feature) computed on the same split, at the same scale, with the same
   fold grouping.
2. A metric quoted alone is not admissible, in the paper or the review record, on the same terms
   as a metric quoted without its scale (E2).
3. The comparison is reported as the **margin over the baseline**, because that is the quantity a
   reader wants and the one that can be small while the headline is large.

## What now carries the weight the headline AUC cannot

If a single velocity threshold reaches 0.894, then the headline AUC is no longer where a model
demonstrates that it learned something. Two experiments are:

- **Leave-one-country-out** (ADR 0023 §4, PB-34). A velocity threshold transfers across countries
  trivially — burstiness is not country-specific — so LOCO measures something a threshold cannot
  fake. Its margin over the baseline is the result worth reporting.
- **The novel SIM-swap sub-variant**, which appears only in the test period (D-08). A model that
  has learned the shape of fraud should catch a variant it never saw; a velocity threshold will
  catch it only insofar as it is also bursty, and the difference between those two outcomes is
  informative.

These were supporting experiments. They are now the primary evidence, and the traceability rows say
so.
