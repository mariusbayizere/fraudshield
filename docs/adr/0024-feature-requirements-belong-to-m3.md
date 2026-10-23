# ADR 0024 — Feature requirements belong to M3, not to the dataset milestone

- **Status:** accepted
- **Date:** 2026-09-19
- **Context:** M2 close. The governance gate refused to mark M2 complete.

## Context

Closing M2 requires every **Must** requirement the register assigns to M2 to reach a final status.
`fs-traceability check` refused, naming four:

| Requirement | Asks for | Actual state at M2 close |
|---|---|---|
| `ML-DATA-07` Feature completeness | all **44 features** computable for ≥ 98% of records | The 44 features do not exist. They are the subject of M3. |
| `RES-01` FraudShield-EAC-Transactions | CSV + Parquet; **5M+** transactions; **44 features**; 8 fraud types; 6 channels; 5 EAC countries | Format, fraud types, channels and countries delivered. 5M rows blocked (PB-25). 44 features are M3. |
| `RES-02` Dataset datasheet | Gebru template, CC BY 4.0 | Delivered. The register entry was stale. |
| `ML-DATA-08` Dataset release | HuggingFace Datasets Hub + Zenodo DOI | Export tooling delivered; publication needs the owner's accounts on two external platforms. |

The refusal is correct and worth recording: four independent reviews, a delta re-check and a full
milestone review had all treated M2 as ready, and the generated register was the only check that
noticed these rows were unsatisfied.

## Decision

**`ML-DATA-07` moves to M3.** The build prompt's own milestone definitions place feature engineering
there:

> **M2 — FraudShield-EAC-Transactions generator (Part E.3)**
> *Gate:* 5M+ rows generated deterministically from a seed; all 7.1 distribution targets met within
> ±0.5 percentage points; realism checks pass (no single feature AUC > 0.80, label noise present,
> novelty variant only in test period); datasheet complete.
>
> **M3 — Feature engineering and feature store (Part E.2)**
> *Gate:* **all 44 feature unit tests pass for all 6 channels**; property tests pass; Redis/DB
> fallback tested; `benchmark.py features` p95 < 10 ms including Redis fetch; `docs/features.md`
> generated.

M2's gate names rows, distribution targets, leakage and the datasheet, and says nothing about
features. M3's gate names the 44 features explicitly. `ML-DATA-07` — "all 44 features computable for
≥ 98% of records" — is an M3 requirement filed under M2. The reassignment therefore rests on the
build prompt's own structure rather than on convenience.

This is the same family of defect as **D-01**, where the SRS specified a precision target that the
base rate made arithmetically impossible: a specification error found by taking the specification
seriously, recorded rather than worked around.

**`RES-01` stays in M2**, because its other clauses are M2's subject, with its unsatisfied clauses
carried explicitly:

- *satisfied*: CSV and Parquet output, 8 fraud types, 6 channels, 5 EAC countries, deterministic
  generation, the realism and anti-leakage gates;
- *carried*: 5,000,000 rows — PB-25, blocked because `workflow_dispatch` registers only from the
  default branch, which is still `m0/bootstrap`, and the owner's direction is that the run must not
  happen on the build laptop. The verification run is 1,006,249 rows;
- *carried to M3*: the 44-feature clause, for the reason above.

**`ML-DATA-08` is not reassigned.** Publication to HuggingFace and Zenodo is not a scheduling
problem and moving it to a later milestone would misrepresent it as one. It takes
`REQUIRES_EXTERNAL_PARTY`, which the register's vocabulary already carries and the gate already
accepts as final, with notes naming the platforms and the owner action needed. (The owner asked for
`REQUIRES_OWNER_ACTION`; the existing status means the same thing here and adding a synonym would
split the vocabulary for no gain.)

**`RES-02` becomes `DONE`.** The datasheet exists, follows the Gebru template, carries CC BY 4.0,
and is covered by a tagged test and by a guard asserting its figures match the committed report.

## Consequences

- The register is generated. The reassignment is data in `ROW_MILESTONE_OVERRIDES` inside
  `traceability_seed.py`, not a hand edit to `requirements.yaml`, so re-seeding preserves it.
- M2 can close with its gate passing on its own. **The hook is not bypassed.**
- M3 inherits `ML-DATA-07` and RES-01's feature clause, and its exit criteria already require the 44
  features to be re-checked against the D-08 ceiling with account-grouped encoding at a stated scale
  (`docs/traceability/m3_exit_criteria.md`, E3).
- A reader asking "was M2 really complete?" gets: yes for what M2 was for, with two clauses carried
  and one requirement that was never M2's, each named here rather than buried in a status field.

## Alternatives rejected

- **Bypass the hook and tag anyway.** Rejected. The gate caught what six review passes missed;
  forcing it would remove the only check that worked.
- **Mark the rows DONE.** Rejected: they are not done, and the register would then assert something
  false in the document the project uses to prove it does not.
- **Leave everything as a recurring deviation note.** Rejected: the note would be re-explained at
  every milestone close, which is how a misfiled requirement becomes permanent.
