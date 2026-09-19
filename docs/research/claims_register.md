# Claims register (D-09)

Numeric claims made in the SRS that could be quoted in the README, paper or presentations.
A claim may be stated as fact only when its status is `VERIFIED` (primary source supplied and
read by the author) or `MEASURED` (produced by FraudShield with a reproducible command).
Until then it must not appear in public-facing text.

**Every `MEASURED` figure below comes from the dataset generated at tree
`d85385fa43b8a392271135bf226d7053eaab01ce`** (1,012,522 rows, seed 20260917), whose output is
`dataset/realism_report.md`. Any later edit that moves a figure must move this hash with it, and a
figure quoted without one is not admissible (E2). **These are not the figures M2 published:** PB-29
changed the country iteration order in `countries.simulated()` from declaration order to
alphabetical, which re-drew the whole dataset without altering a single parameter value. The
dataset's *properties* are unchanged — every gate still passes and every rank order holds — but the
*draw* is different, so the numbers moved. See the dated note in `docs/ml/datasheet.md`.

| # | Claim (SRS location) | Status | Resolution path | Owner |
|---|---|---|---|---|
| C-1 | "Mobile money fraud in sub-Saharan Africa grew 64% between 2021 and 2024 (GSMA, 2024)" (section 00) | UNVERIFIED | Author to locate the GSMA publication, confirm the figure, geography and period, and record the exact citation; otherwise remove | Author |
| C-2 | "Rwanda's digital payment volume exceeded RWF 18 trillion in 2023" (section 00) | REPLACED | The SRS figure could not be verified: no published series was found that matches its definition ("digital payment volume") or its calendar year, so it must not be quoted. Replaced by what a read source supports — **retail e-payments were 211% of GDP in FY2023/24 and 321% in FY2024/25** (National Bank of Rwanda, NBR Annual Report 2024-2025, Figure 21 "Value of retail e-payment to GDP", page 89, read 2026-09-18), which against nominal GDP of RWF 17.18 trillion for 2023 (World Bank NY.GDP.MKTP.CN) is roughly RWF 36 trillion. Use the ratio and the fiscal year, not an absolute calendar-year total | Author |
| C-3 | "Analysts dismiss > 60% of alerts without action when explanations are absent" (1.1) | UNVERIFIED | Primary study required; otherwise remove. FraudShield cannot measure this without real analysts | Author |
| C-4 | "Western models achieve AUC-ROC 0.72–0.78 on East African mobile money data" (1.1) | UNVERIFIED | Replace with a FraudShield measurement: ablation "card-style feature set only" vs "full EAC feature set" on the synthetic benchmark (E.5 item 4), reported as a result on synthetic data, not as a statement about real deployments | M4 |
| C-5 | "Model accuracy degrades 8–15% per year without retraining" (1.1) | UNVERIFIED | Primary source required; the synthetic benchmark can report performance by test month (E.5 item 5), which is not evidence about real-world degradation | Author / M4 |
| C-6 | "Ensemble reduces variance 12% vs single model" (2.3) | UNVERIFIED | Replace with measured seed-variance comparison, ensemble vs XGBoost and LightGBM alone over 5 seeds (E.5 item 4) | M4 |
| C-7 | "SIM-swap fraud … dominant fraud type in Rwanda and Kenya" (glossary) | UNVERIFIED (searched) | Sourcing pass of 2026-09-18 read the NBR Annual Report 2024-2025 and the Bank of Tanzania Payment Systems Annual Report for 2024 in full: neither publishes fraud incidents by type, and the NBR report mentions mobile money fraud only as a consumer complaint category. No primary source found; the claim must not be stated as fact, and the generator's scenario shares remain ASSUMED for the same reason (docs/research/sourcing_pass.md) | Author |
| C-8 | "First public East African financial fraud benchmark dataset" (7.1, 10) | UNVERIFIED | Novelty claim requires a documented literature and dataset search by the author before publication | Author |
| C-9 | "No single feature separates the classes beyond AUC 0.80" (D-08 control, quoted in the datasheet, walkthrough and defence questions) | MEASURED | `uv run fs-dataset report <dataset> --rows 1000000 --seed 20260917` at 1,012,522 rows (tree `d85385f`), re-measured after the encoding and band fixes and again after PB-29 re-drew the dataset. Strongest transaction column `merchant_category_code` **0.707**; strongest channel overall, via the `account_events` join, event-to-transaction delay **0.746**; event type 0.536. (M2 published 0.706 / 0.758 / 0.537 from the pre-PB-29 draw.) All inside the 0.80 ceiling. Raw output `dataset/realism_report.md` | M2 |
| C-10 | "The dataset's strongest single feature is `merchant_category_code` at 0.711" (walkthrough M2, before this correction) | REPLACED | True only of the *transaction columns*. The `account_events` join the datasheet invites reaches a delay channel at **0.746** (0.758 before PB-29 re-drew the dataset), which is larger. The claim is now stated as "strongest single transaction feature", with the delay named separately as the strongest overall. Found by the author after the M2 review closed, by measuring the channel an exclusion had excused; recorded in `docs/reviews/M2/delta-recheck.md` | M2 |
| C-12 | "Single-feature AUCs are measured without leakage" (implicit in every AUC this project reports) | MEASURED, CORRECTED | The encoding removed a row's own label from its own score but folded **per row**, leaving the other rows of the same fraud incident in the estimate that scored it. Grouping folds by account lowers `merchant_category_code` by 0.005, `channel` by 0.006 and `currency` by 0.001 at 1,006,249 rows. Every AUC reported before this correction is inflated by about that much; `merchant_category_code` was restated 0.711 → 0.706 on that draw, and reads **0.707** on the current one (tree `d85385f`) — the correction's size is a property of the encoding, the absolute value is a property of the draw. Fixed in `_category_rates`, pinned by `test_the_categorical_encoding_folds_by_account_not_by_row` | M2 |
| C-13 | "Out-of-fold encoding removed the single-feature AUC's dependence on run size" (M2 dataset response) | REFUTED | Withdrawn. Ten seeds at 300,000 rows give a seed-to-seed sd of 0.0055, while the quoted three-point series spanned 0.065 — twelve times that, so the variation was not seed noise. The mechanism fix is sound and the range narrowed from 0.147 to 0.065, but a residual size dependence remains and is **unexplained**: account-grouped folds remove the same amount at 300,000 and 1,000,000 rows (0.0043 vs 0.0046), so sibling leakage does not account for it | M2 / M3 |
| C-11 | "The event delay is designed scenario signal a model is meant to learn" (event gate rationale, `realism/checks.py`) | PARTLY MEASURED, PARTLY ASSUMED | The separation is measured (**0.746** at 1M on tree `d85385f`, 0.758 on the pre-PB-29 draw, 0.709 at 60K — it grows with n). Its *interpretation* is not: the lead is drawn from `fraud.takeover_lead_minutes = [5, 60]`, provenance `ASSUMED`, so every enabling event is followed by its drain in a tight uniform window with no long tail and no unexploited event. How much of it is the scenario and how much the assumed schedule cannot be separated from the data as it stands. Must not be quoted as evidence about real SIM-swap-to-takeover timing | M2 / M4 |

## Replaced wording

C-2's original wording is not used anywhere in text this project authors; it survives only in
`docs/srs/FraudShield_SRS_v1_0.md`, in the build prompt, and in `docs/srs/defect_register.md`, which
is generated from the prompt. Those three are verbatim records of what was supplied, and D-09 exists
to track exactly this kind of claim in them, so they are not edited: deleting the sentence would
destroy the evidence that the claim was made. Anything this project publishes — README, paper,
presentation, datasheet — uses the replacement wording above.

## Sourcing pass, 2026-09-18

A sourcing pass over the generator parameters read seven documents in full and raised the sourced
parameter count from 0 to 12 of 81 (`docs/research/sourcing_pass.md`). It also established what is
*not* available: no publication found gives fraud incidence by type for these markets, which is why
every fraud parameter and the claims that depend on them stay unverified. Claims about fraud
prevalence, detection rates in production, or analyst behaviour cannot be resolved from the payment
statistics that exist, and must not be inferred from the synthetic benchmark.

## Rules

- Do not search for and insert sources on the author's behalf unless the primary source has
  been read in full; leave `UNVERIFIED` instead.
- A `MEASURED` claim must link the command, the MLflow run ID and the raw output file.
- Synthetic-benchmark results are always worded as results on the FraudShield-EAC synthetic
  benchmark (D-08).
