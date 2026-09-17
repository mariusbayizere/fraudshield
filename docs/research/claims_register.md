# Claims register (D-09)

Numeric claims made in the SRS that could be quoted in the README, paper or presentations.
A claim may be stated as fact only when its status is `VERIFIED` (primary source supplied and
read by the author) or `MEASURED` (produced by FraudShield with a reproducible command).
Until then it must not appear in public-facing text.

| # | Claim (SRS location) | Status | Resolution path | Owner |
|---|---|---|---|---|
| C-1 | "Mobile money fraud in sub-Saharan Africa grew 64% between 2021 and 2024 (GSMA, 2024)" (section 00) | UNVERIFIED | Author to locate the GSMA publication, confirm the figure, geography and period, and record the exact citation; otherwise remove | Author |
| C-2 | "Rwanda's digital payment volume exceeded RWF 18 trillion in 2023" (section 00) | UNVERIFIED | Author to confirm against a primary BNR publication (value, year, definition of "digital payments"); otherwise remove | Author |
| C-3 | "Analysts dismiss > 60% of alerts without action when explanations are absent" (1.1) | UNVERIFIED | Primary study required; otherwise remove. FraudShield cannot measure this without real analysts | Author |
| C-4 | "Western models achieve AUC-ROC 0.72–0.78 on East African mobile money data" (1.1) | UNVERIFIED | Replace with a FraudShield measurement: ablation "card-style feature set only" vs "full EAC feature set" on the synthetic benchmark (E.5 item 4), reported as a result on synthetic data, not as a statement about real deployments | M4 |
| C-5 | "Model accuracy degrades 8–15% per year without retraining" (1.1) | UNVERIFIED | Primary source required; the synthetic benchmark can report performance by test month (E.5 item 5), which is not evidence about real-world degradation | Author / M4 |
| C-6 | "Ensemble reduces variance 12% vs single model" (2.3) | UNVERIFIED | Replace with measured seed-variance comparison, ensemble vs XGBoost and LightGBM alone over 5 seeds (E.5 item 4) | M4 |
| C-7 | "SIM-swap fraud … dominant fraud type in Rwanda and Kenya" (glossary) | UNVERIFIED | Primary source required before stating as fact; the design treats SIM swap as a high-severity threat regardless (D-25) | Author |
| C-8 | "First public East African financial fraud benchmark dataset" (7.1, 10) | UNVERIFIED | Novelty claim requires a documented literature and dataset search by the author before publication | Author |

## Rules

- Do not search for and insert sources on the author's behalf unless the primary source has
  been read in full; leave `UNVERIFIED` instead.
- A `MEASURED` claim must link the command, the MLflow run ID and the raw output file.
- Synthetic-benchmark results are always worded as results on the FraudShield-EAC synthetic
  benchmark (D-08).
