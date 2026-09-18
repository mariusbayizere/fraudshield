# Claims register (D-09)

Numeric claims made in the SRS that could be quoted in the README, paper or presentations.
A claim may be stated as fact only when its status is `VERIFIED` (primary source supplied and
read by the author) or `MEASURED` (produced by FraudShield with a reproducible command).
Until then it must not appear in public-facing text.

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
