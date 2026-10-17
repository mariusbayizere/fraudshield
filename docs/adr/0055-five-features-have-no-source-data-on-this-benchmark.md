# 0055 — Five features have no source data on this benchmark, and ML-DATA-07 closes as a deviation

- **Status:** Accepted
- **Date:** 2026-10-17
- **Decided by:** the owner, 2026-10-17 (option 2 of the M6 close: a recorded deviation rather than a
  milestone that would not do the work)
- **Requirements affected:** ML-DATA-07; D-04 (missing-value handling)
- **ADRs referenced:** 0023 (country packs), 0024 and 0027 (where ML-DATA-07 was filed, and why), 0026
  (the KYC as-of rule), 0031 (the precedent for closing a row honestly against what it asks)

## Context

ML-DATA-07 asks that "all 44 features [be] computable for ≥ 98% of records; < 2% any missing
feature". All 44 features are implemented, on both the batch and the online path, and tested. Five
of them read data that this benchmark does not contain, so they cannot be computed for any record:

| Feature | The data it needs | Where a real deployment holds it |
|---|---|---|
| `account_age_days` | the account's opening date | the core banking system's account master |
| `counterparty_account_age_days` | the counterparty account's opening date | the same account master, for the counterparty |
| `kyc_tier` | the account's KYC tier assignments, each with the time it took effect (ADR 0026) | the institution's KYC records |
| `agent_float_utilisation_ratio` | the agent's float balance and float limit as of the transaction | the agent network's float ledger |
| `agent_distance_from_registered_km` | the agent's registered premises as of the transaction | the agent network's registry |

A sixth, `round_sum_flag`, was on the same list until 2026-09-20, when the country packs gained a
`round_denominations` field (ADR 0023) and it became computable. Its denominations are themselves
ASSUMED, not a sourced banknote series (`docs/features.md`); that is a separate limitation of the
packs, not of this row.

ML-DATA-07 was filed under M6 as the last milestone expected to supply tables for these features
(ADR 0027). M6 created no source for this data, and no planned milestone does: the synthetic
generator (M2) does not model account opening dates, KYC history or agent float, and the deployment
milestones (M9, M10) operate the system rather than feed it a bank's master data.

## What happens today

- Each of the five returns NaN for every record. D-04's native missing handling covers NaN, so the
  pipeline, the trainer, the scorer and the explanations all run without error, and the feature
  vector still has 44 entries.
- The feature registry declares the five `NO_SOURCE_DATA`, each with the data it lacks, and
  `fs-features computability` fails if a feature so declared ever produces a number, or a feature
  declared computable is NaN for every row. The gap is therefore checked, not merely written down.
- The models learn nothing from these five features. They are present in name only.

## Decision

**ML-DATA-07 is `DONE_WITH_DEVIATION`.**

- **The criterion is not met in substance, not merely in form.** Five of 44 features, 11 %, are
  missing for 100 % of records, against a criterion of fewer than 2 % missing for any feature. That
  nothing raises is not the criterion; the row asks for features a model can use, and five are not
  usable. The pipeline's graceful degradation is the behaviour D-04 requires, not evidence that
  ML-DATA-07 is satisfied.
- **The gap is a property of the benchmark, not of the design.** The features are specified,
  implemented on both paths, tested, and wired to inputs the caller supplies. A deployment fed by an
  institution's account master, KYC records and agent ledger would supply the five inputs, and the
  same code would compute them. No milestone of this project can close the row by doing work,
  because the missing thing is data the benchmark does not have, so a home for it would be a
  fiction.
- **It is reported where the results are.** Every model figure in this project is trained and
  evaluated without these five features. The paper's limitations section states it beside the two
  generalisation nulls, not in a footnote (flagged to M11, `docs/parallel/M11_updates.md`).

## Consequences

- M6 can be listed as completed: its last open Must row has a final status with its reason.
- Should a later version of the generator model account opening dates, KYC tier histories or agent
  float, the registry's computability check fails at once on the revived features, which is the
  prompt to re-open this row and re-measure.
