# Claims register (D-09)

Numeric claims made in the SRS that could be quoted in the README, paper or presentations.
A claim may be stated as fact only when its status is `VERIFIED` (primary source supplied and
read by the author) or `MEASURED` (produced by FraudShield with a reproducible command).
Until then it must not appear in public-facing text.

**Every `MEASURED` figure below comes from the dataset whose fingerprint is
`6abde44e5e952f4f5c6b86aac0fb934cd4de023b53ec1f2cfe9dc01cf0a3e420`** — 1,006,249 rows, seed
20260917, regenerated 2026-09-21 and described by `dataset/realism_report.md`, which now carries
that fingerprint (PB-41). The dataset itself is kept at `dataset/output/bench1m`. A figure inherits
the run it was produced by; any later edit that moves a figure must move the fingerprint with it,
and a figure quoted without one is not admissible (E2).

**Correction, 2026-09-21.** This paragraph used to say the figures came from "the dataset generated
at tree `d85385fa43b8a392271135bf226d7053eaab01ce`" at **1,012,522 rows**, and attributed the
difference from M2's published numbers to PB-29 re-drawing the dataset by changing the country
iteration order in `countries.simulated()`. Both halves are now known to be wrong, and the
regeneration is what showed it:

- The generator code and every generator parameter at `d85385f` are **byte-identical** to the
  current tree (`git diff d85385f HEAD -- dataset/generator/params dataset/src/.../generator` is
  empty), and that code produces **1,006,249** rows at this seed, verified twice on 2026-09-21 with
  identical fingerprints. So the 1,012,522-row report committed at `d85385f` was produced by some
  other tree or other inputs, and committing it there did not make it that tree's artefact — which
  is the exact error the sentence above warns against, made by the sentence above.
- Country iteration order **does not change the draw at all**: `Population._apportioned` sorts
  internally, which is why PB-41's prescribed mutation (reversing the pack order) produced a
  byte-identical dataset and had to be replaced with a `_GOLDEN` constant mutation. So PB-29 cannot
  be the mechanism that moved those numbers, whatever did.

What produced the 1,012,522-row draw is **unexplained** and is recorded as PB-52. Nothing about the
current dataset is in doubt: it is reproducible from the seed, its fingerprint is published, and
every gate passes. What was wrong was a provenance sentence attached to a superseded report.

| # | Claim (SRS location) | Status | Resolution path | Owner |
|---|---|---|---|---|
| C-1 | "Mobile money fraud in sub-Saharan Africa grew 64% between 2021 and 2024 (GSMA, 2024)" (section 00) | UNVERIFIED | Author to locate the GSMA publication, confirm the figure, geography and period, and record the exact citation; otherwise remove | Author |
| C-2 | "Rwanda's digital payment volume exceeded RWF 18 trillion in 2023" (section 00) | REPLACED | The SRS figure could not be verified: no published series was found that matches its definition ("digital payment volume") or its calendar year, so it must not be quoted. Replaced by what a read source supports — **retail e-payments were 211% of GDP in FY2023/24 and 321% in FY2024/25** (National Bank of Rwanda, NBR Annual Report 2024-2025, Figure 21 "Value of retail e-payment to GDP", page 89, read 2026-09-18), which against nominal GDP of RWF 17.18 trillion for 2023 (World Bank NY.GDP.MKTP.CN) is roughly RWF 36 trillion. Use the ratio and the fiscal year, not an absolute calendar-year total | Author |
| C-3 | "Analysts dismiss > 60% of alerts without action when explanations are absent" (1.1) | UNVERIFIED | Primary study required; otherwise remove. FraudShield cannot measure this without real analysts | Author |
| C-4 | "Western models achieve AUC-ROC 0.72–0.78 on East African mobile money data" (1.1) | UNVERIFIED, **and the planned replacement will not work** | The plan was an ablation, "card-style feature set only" vs "full EAC feature set". The M4 battery (2026-09-22) measured the benchmark's redundancy and it defeats that design: removing any one of ten feature groups costs at most 0.031 AUC while **four disjoint groups each reach 0.845 or better alone**. Any subset comparison will show a small difference whatever is true of real systems, and reporting it as evidence would be a control narrower than the claim it justifies — the D-08 shape. Either withdraw the claim or find a comparison redundancy cannot flatten (PB-60) | M4 |
| C-5 | "Model accuracy degrades 8–15% per year without retraining" (1.1) | UNVERIFIED | Primary source required; the synthetic benchmark can report performance by test month (E.5 item 5), which is not evidence about real-world degradation | Author / M4 |
| C-6 | "Ensemble reduces variance 12% vs single model" (2.3) | MEASURED, MIXED | Over 5 fixed seeds (1-5): ensemble stdev 0.000646, vs XGBoost alone 0.000642 (-0.5%, did not reduce) and LightGBM alone 0.001742 (+62.9%, reduced). The claimed 12% figure does not hold against XGBoost, the stronger of the two base models; it holds only relative to the weaker one. See `docs/benchmarks/m4_seed_variance_pb61.txt`. | M4 |
| C-7 | "SIM-swap fraud … dominant fraud type in Rwanda and Kenya" (glossary) | UNVERIFIED (searched) | Sourcing pass of 2026-09-18 read the NBR Annual Report 2024-2025 and the Bank of Tanzania Payment Systems Annual Report for 2024 in full: neither publishes fraud incidents by type, and the NBR report mentions mobile money fraud only as a consumer complaint category. No primary source found; the claim must not be stated as fact, and the generator's scenario shares remain ASSUMED for the same reason (docs/research/sourcing_pass.md) | Author |
| C-8 | "First public East African financial fraud benchmark dataset" (7.1, 10) | UNVERIFIED | Novelty claim requires a documented literature and dataset search by the author before publication | Author |
| C-9 | "No single feature separates the classes beyond AUC 0.80" (D-08 control, quoted in the datasheet, walkthrough and defence questions) | **REFUTED as stated** — true of the dataset's columns, false of the engineered features. See C-14 | `uv run fs-dataset report dataset/output/bench1m --rows 1000000 --seed 20260917`, regenerated 2026-09-21 at 1,006,249 rows, fingerprint `6abde44e`. Strongest transaction column `merchant_category_code` **0.706**; strongest channel overall, via the `account_events` join, event-to-transaction delay **0.730**; event type 0.537; best trivial rule 0.604. All inside the 0.80 ceiling. **Restated from 0.707 / 0.746 / 0.536**, which were read off a report describing a 1,012,522-row draw this tree does not produce — see the correction above and PB-52; the three figures are M2's published ones again, which is itself part of what PB-52 has to explain. Raw output `dataset/realism_report.md` | M2 |
| C-10 | "The dataset's strongest single feature is `merchant_category_code` at 0.711" (walkthrough M2, before this correction) | REPLACED | True only of the *transaction columns*. The `account_events` join the datasheet invites reaches a delay channel at **0.730** on the current draw, which is larger. The claim is now stated as "strongest single transaction feature", with the delay named separately as the strongest overall. Found by the author after the M2 review closed, by measuring the channel an exclusion had excused; recorded in `docs/reviews/M2/delta-recheck.md` | M2 |
| C-12 | "Single-feature AUCs are measured without leakage" (implicit in every AUC this project reports) | MEASURED, CORRECTED | The encoding removed a row's own label from its own score but folded **per row**, leaving the other rows of the same fraud incident in the estimate that scored it. Grouping folds by account lowers `merchant_category_code` by 0.005, `channel` by 0.006 and `currency` by 0.001 at 1,006,249 rows. Every AUC reported before this correction is inflated by about that much; `merchant_category_code` was restated 0.711 → 0.706 on that draw, and reads **0.706** on the current one (fingerprint `6abde44e`) — the correction's size is a property of the encoding, the absolute value is a property of the draw. Fixed in `_category_rates`, pinned by `test_the_categorical_encoding_folds_by_account_not_by_row` | M2 |
| C-14 | "No single ENGINEERED feature separates the classes beyond AUC 0.80" (M3 exit criterion E3, the re-check C-9's wording always implied) | **MEASURED, REFUTED** | `uv run fs-features auc <dataset> --packs <packs.json> --corpus-rows 200000 --sample-rows 20000` at commit `2c80ef6`, on a 1,006,249-row dataset at seed 20260917. Scored 20,000 rows holding 166 confirmed fraud, so the 95% interval is about +/- 0.04. **Five of the 44 exceed the ceiling:** `velocity_ratio_1h_vs_30d` **0.894** +/-0.032, `counterparty_is_new_for_account` **0.851** +/-0.037, `tx_count_1h` **0.826** +/-0.039, `implied_speed_kmh` **0.812** +/-0.040, `seconds_since_last_tx` **0.811** +/-0.040. **Restated from an earlier run at `fad43dd`**, taken before the milestone review found five unbounded features deriving durable state from a truncated corpus: three figures moved, `counterparty_is_new_for_account` 0.816 -> 0.851 the largest, and `velocity_ratio_1h_vs_30d` did not move — the review expected the headline to be inflated and the measurement says it was not. Four more sit within the interval of it: `amount_sum_24h` 0.776, `tx_count_24h` 0.765, `unique_counterparties_24h` 0.759, `synthetic_identity_score` 0.759. C-9 measured the dataset's **columns** and remains true of them (strongest 0.706); the ceiling is a property of what a model can be given, and the engineered features are what it is given. Raw output kept with the run; the strongest feature alone reaches within 0.05 of ML-GATE-01's 0.940 target for a whole model | M3 / PB-46 |
| C-13 | "Out-of-fold encoding removed the single-feature AUC's dependence on run size" (M2 dataset response) | REFUTED | Withdrawn. Ten seeds at 300,000 rows give a seed-to-seed sd of 0.0055, while the quoted three-point series spanned 0.065 — twelve times that, so the variation was not seed noise. The mechanism fix is sound and the range narrowed from 0.147 to 0.065, but a residual size dependence remains and is **unexplained**: account-grouped folds remove the same amount at 300,000 and 1,000,000 rows (0.0043 vs 0.0046), so sibling leakage does not account for it | M2 / M3 |
| C-11 | "The event delay is designed scenario signal a model is meant to learn" (event gate rationale, `realism/checks.py`) | **MEASURED** | The proportion is no longer assumed. The lead was `[5, 60]` minutes drawn uniformly; on 2026-09-22 it became a clipped lognormal with a real tail (PB-56, owner decision), and the channel fell from **0.758 to 0.730** on the regenerated draw `6abde44e`. So about **11% of the excess over 0.5 was the assumed schedule and the rest is the scenario** — the artefact was the small part. The fall was predicted before the draw existed (`0138099`, band 0.68-0.74) and the prediction held. What remains assumed is the lead distribution's own shape, not its contribution: `takeover_lead_median_minutes` and `takeover_lead_log_sigma` are ASSUMED and no publication read gives takeover-to-drain delays for these markets | M2 / M4 |

## Claims this benchmark cannot support (added 2026-09-22)

Recorded here rather than in a limitations paragraph, because the register is what this project
checks before quoting anything.

- **No generalisation claim, geographic or temporal.** Leave-one-country-out changes a country's
  AUC by at most 0.002 (PB-59) and the novel sub-variant is caught at 100% against 95.1% for the
  familiar one (PB-61). Both are null for one reason: the benchmark encodes fraud as bursts, and
  every country and every variant is a view of that structure. Country-level generalisation
  belongs to a real-data validation plan.
- **No claim about which features matter.** Four disjoint feature groups each reach ≥ 0.845 alone,
  so an ablation measures redundancy rather than importance (PB-60).
- **No latency gate figure from a development machine.** Two of five configurations in the D-16
  frontier show a *negative* explanation cost at p99, which is impossible and is what establishes
  that the p99 here is scheduler jitter. The p50 shape is reportable; the 40 ms constraint is not
  applicable (ADR 0010).

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
