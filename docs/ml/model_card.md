# Model card — FraudShield detection model

**Status: no model has been trained.** This card is written before M4 rather than after it, for
the same reason the training/serving parity design was written before the 44 features existed: the
limitations below are known now, they are the ones a reader of a result would need and would not
think to ask for, and a card assembled after the numbers is a card written by someone who already
knows which numbers they want to keep.

Every section marked **[M4]** is a placeholder. Nothing in this file is a measurement, and no
figure appears in it.

## Model details

- **[M4]** Architecture, ensemble members, calibration method, training date, version.
- **Intended use:** ranking transactions by fraud risk on a synthetic benchmark, as a research
  artifact. **Not** for use against real customers or real transaction streams.
- **Out of scope:** any claim about real fraud prevalence, any claim about a named institution, and
  any decision affecting a real person.

## Training data

`FraudShield-Africa-Transactions`, synthetic, with a validated EAC-5 core (ADR 0023). See
`docs/ml/datasheet.md`; the constraints there apply here and are not repeated.

## Features

44 are declared and implemented; **38 carry information on this benchmark**. Six read reference
data that neither the dataset nor M1's schema holds and are NaN for every row (PB-44):
`account_age_days`, `counterparty_account_age_days`, `kyc_tier`,
`agent_float_utilisation_ratio`, `agent_distance_from_registered_km`, `round_sum_flag`.

**[M4] must state the number of features that carried information alongside every metric**, and
must not report "44 features" where fewer were used. If one of the six becomes computable later,
that is a documented change to the model's input and a reason to restate earlier numbers — not a
silent improvement. `docs/features.md` lists what each needs and which milestone supplies it.

## Limitations that are known before any training run

### The benchmark is velocity-separable, and this governs every metric below

A single feature, `velocity_ratio_1h_vs_30d`, reaches `max(AUC, 1−AUC)` of **0.894 ±0.032** on this
benchmark; four more exceed 0.80 (commit `fad43dd`,
`docs/benchmarks/single_feature_baseline.md`). The generator injects fraud as incidents — drains,
velocity runs, mule fan-out — so recency and rate features find it.

**[M4] every metric in this card must appear beside the best single feature and the best trivial
rule, on the same split and scale, reported as the margin over that baseline.** A metric quoted
alone is inadmissible. This is a gate in the traceability register on ML-GATE-01 to -04, -07 to -09
and -13, not a stylistic preference: 0.94 against a 0.894 baseline and 0.94 against chance are
different claims and only the first is true here.

**[M4] the evidence that the model learned something has moved.** Leave-one-country-out and the
novel SIM-swap sub-variant confined to the test period are where a velocity threshold cannot
follow — burstiness is not country-specific, and a variant never seen in training separates a model
that learned the shape of fraud from one that learned its rate. Those results, with their margins,
carry the weight the headline AUC no longer can.


### The model will never have seen two of `corridor_class`'s four values

`corridor_class` is `DOMESTIC | INTRA_BLOC | CROSS_BLOC_AFRICA | INTERCONTINENTAL`. The last two
are implemented and unit-tested through a synthetic country pack, and **no row of the shipped
dataset produces either**: every simulated country is in the EAC and on one continent, and
`behaviour.remittance_corridors` sends every cross-border transfer to another simulated country.

So a model trained here has observed two of the four classes, and an encoder fitted here has no
cell for the other two — the first real cross-bloc transaction in serving arrives as an unseen
level. **No claim that this model generalises across corridor classes can rest on this benchmark.**

This is a stated limitation, not a defect awaiting a fix: ADR 0023's owner direction is that the
simulated country set is not to be broadened, and the portability packs exist to show the code is
not EAC-specific rather than to be simulated. Producing the two absent classes would mean
simulating a corridor leaving the validated core, which is a change to the dataset draw (PB-43).

### Two features are degenerate until the generator is fixed

No device in the dataset is shared between accounts, so `accounts_per_device_7d` is identically 1
and the device-sharing term of `synthetic_identity_score` is dead with it (PB-40). The generator
fix is scheduled **before M4 training**; if a model is trained before it lands, the card must say
so, because the two features would then be present and empty.

### The synthetic-identity composite's weights are a choice

Part E.2 delegates the form of the composite ("a deterministic, documented composite in [0, 1]").
It is the unweighted mean of four terms because the composite carries no fitted quantity and there
was no evidence from which to argue any other weighting. **Equal weighting is a documented default,
not a derived optimum.** M4 may revisit it with evidence and must say so if it does.

### A score of zero on that composite means "no evidence", not "checked and clean"

Each of its four terms contributes zero when its input is absent, so an account about which
nothing is known scores the same as one that has been examined and found ordinary. The two are
indistinguishable in the output.

### Single-feature AUC depends on dataset size by more than seed noise

Unexplained (M2 milestone review). Every metric must be reported at a stated scale and comparisons
must use the same size (M3 exit criterion E2).

## Evaluation

- **[M4]** Metrics against ML-GATE-01 to -11, at a stated scale, with confidence intervals.
- **[M4]** Leave-one-country-out and the fine-tuning variant (ADR 0023 §4, PB-34), reported as a
  portability probe on assumed data where the packs are assumed.
- **[M4]** Calibration, and the operating point the decision engine will use.

## Ethical considerations and risks

- The dataset is synthetic; a detection rate measured on it is not a claim about real fraud.
- **[M4]** Fairness analysis across country, segment and channel, and what was done about it.
- Agent and device features read cross-account state. The grouping rules that keep them fold-safe
  are M3 exit criterion E1 and are carried into the evaluation design through the register.
