# Number audit

Every macro in `numbers.tex` checked against its evidence file at `m4-complete` (`72e7790`), or at the ref its row names, read with `git show`, by `audit_numbers.py`. Values are compared as printed: rounding, sign and interval bounds included. Regenerate with `python3 docs/research/paper/audit_numbers.py`; do not edit by hand.

**1073 macros, 1073 MATCH, 0 not matching.**

| Macro | Value in paper | Value in evidence | File | Note | Result |
|---|---|---|---|---|---|
| `\nRowsDrawA` | `1{,}006{,}249` | `1,006,249` | `docs/research/claims_register.md` |  | MATCH |
| `\nSeed` | `20260917` | `20260917` | `docs/research/claims_register.md` |  | MATCH |
| `\nParamsTotal` | `81` | `81` | `docs/research/sourcing_pass.md` |  | MATCH |
| `\nParamsSourced` | `12` | `12` | `docs/research/sourcing_pass.md` |  | MATCH |
| `\nParamsAssumed` | `55` | `55` | `docs/research/sourcing_pass.md` |  | MATCH |
| `\nParamsCalibrated` | `14` | `14` | `docs/research/sourcing_pass.md` |  | MATCH |
| `\nSourceDocs` | `8` | `S-8` | `docs/research/sourcing_pass.md` | rows S-1 to S-8 of 'Documents read'; last is S-8 | MATCH |
| `\nFraudParams` | `25` | `25` | `docs/research/limitations.md` |  | MATCH |
| `\nFraudParamsChoice` | `21` | `21` | `docs/research/limitations.md` |  | MATCH |
| `\nFraudParamsCalibrated` | `4` | `four` | `docs/research/limitations.md` |  | MATCH |
| `\nMinRows` | `170{,}000` | `170,000` | `docs/reviews/M2/milestone-review.md` |  | MATCH |
| `\nDetectorMinRows` | `60{,}000` | `60,000` | `docs/reviews/M2/milestone-review.md` |  | MATCH |
| `\nMinRowsA` | `169{,}492` | `169,492` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nMinRowsB` | `170{,}455` | `170,455` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nMinRowsC` | `169{,}972` | `169,972` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nMinRowsD` | `170{,}069` | `170,069` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nColumnMaxAUC` | `0.706` | `0.706` | `docs/research/claims_register.md` |  | MATCH |
| `\nEventDelayAUC` | `0.730` | `0.730` | `docs/research/claims_register.md` |  | MATCH |
| `\nSiblingLeak` | `0.005` | `0.005` | `docs/research/claims_register.md` |  | MATCH |
| `\nSeedSdSizeSeries` | `0.0055` | `0.0055` | `docs/research/claims_register.md` |  | MATCH |
| `\nSizeSeriesSpan` | `0.065` | `0.065` | `docs/research/claims_register.md` |  | MATCH |
| `\nEventDelayBefore` | `0.758` | `0.758` | `docs/research/claims_register.md` |  | MATCH |
| `\nEventDelayAfter` | `0.730` | `0.730` | `docs/research/claims_register.md` |  | MATCH |
| `\nEventDelayShare` | `11\%` | `11%` | `docs/research/claims_register.md` |  | MATCH |
| `\nEventDelayBandLow` | `0.68` | `0.68` | `docs/research/claims_register.md` |  | MATCH |
| `\nEventDelayBandHigh` | `0.74` | `0.74` | `docs/research/claims_register.md` |  | MATCH |
| `\nRowsDrawB` | `1{,}006{,}317` | `1,006,317` | `dataset/realism_report.md` |  | MATCH |
| `\nFraudRateAll` | `0.877\%` | `0.877%` | `dataset/realism_report.md` |  | MATCH |
| `\nFraudRateTest` | `0.971\%` | `0.971%` | `dataset/realism_report.md` |  | MATCH |
| `\nLabelNoiseMissed` | `1.55\%` | `1.55%` | `dataset/realism_report.md` |  | MATCH |
| `\nLabelNoiseFalse` | `1.64\%` | `1.64%` | `dataset/realism_report.md` |  | MATCH |
| `\nShortcutAUC` | `0.503` | `0.503` | `dataset/realism_report.md` |  | MATCH |
| `\nNovelRows` | `73` | `73` | `dataset/realism_report.md` |  | MATCH |
| `\nTrainRows` | `792{,}162` | `792,162` | `dataset/realism_report.md` |  | MATCH |
| `\nValRows` | `101{,}332` | `101,332` | `dataset/realism_report.md` |  | MATCH |
| `\nCalRows` | `40{,}700` | `40,700` | `dataset/realism_report.md` |  | MATCH |
| `\nEmbargoRows` | `10{,}914` | `10,914` | `dataset/realism_report.md` |  | MATCH |
| `\nTestRows` | `101{,}909` | `101,909` | `dataset/realism_report.md` |  | MATCH |
| `\nTestSpanDays` | `63.97` | `63.97` | `dataset/realism_report.md` |  | MATCH |
| `\nEmbargoDays` | `6.99` | `6.99` | `dataset/realism_report.md` |  | MATCH |
| `\nChannelMixDev` | `0.16` | `0.16` | `dataset/realism_report.md` |  | MATCH |
| `\nCountryMixDev` | `0.01` | `0.01` | `dataset/realism_report.md` |  | MATCH |
| `\nFeatures` | `44` | `44` | `docs/research/claims_register.md` |  | MATCH |
| `\nScoredRows` | `20{,}000` | `20,000` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nScoredFraud` | `166` | `166` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nFloorM` | `0.894` | `0.894` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nFloorMci` | `0.032` | `0.032` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nCounterpartyNewM` | `0.851` | `0.851` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nTxCountHourM` | `0.826` | `0.826` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nImpliedSpeedM` | `0.812` | `0.812` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nSecondsSinceM` | `0.811` | `0.811` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nFeaturesAboveCeiling` | `5` | `Five` | `docs/research/claims_register.md` |  | MATCH |
| `\nCeiling` | `0.80` | `0.80` | `docs/research/claims_register.md` |  | MATCH |
| `\nCounterpartyNewBefore` | `0.816` | `0.816` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nParityRel` | `\ensuremath{10^{-12}}` | `1e-12` | `docs/ml/training_serving_parity.md` |  | MATCH |
| `\nBatchSizes` | `1, 5 and 17` | `1, 5 and 17` | `docs/reviews/M3/milestone-review.md` |  | MATCH |
| `\nTestFraud` | `985` | `985` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrainUsed` | `30{,}000` | `30,000` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateAUC` | `0.970` | `0.970` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateAUCci` | `[0.962, 0.977]` | `[0.962, 0.977]` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateFloor` | `0.879` | `0.879` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateMargin` | `+0.091` | `+0.091` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateTrivial` | `0.659` | `0.659` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateTrivialMargin` | `+0.311` | `+0.311` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRecallFPR` | `0.871` | `0.871` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRecallFPRci` | `[0.847, 0.891]` | `[0.847, 0.891]` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRecallFPRFloor` | `0.598` | `0.598` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRecallFPRMargin` | `+0.273` | `+0.273` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateMM` | `0.953` | `0.953` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateMMFloor` | `0.882` | `0.882` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateMMMargin` | `+0.070` | `+0.070` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateUSSD` | `0.953` | `0.953` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateUSSDFloor` | `0.904` | `0.904` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateUSSDMargin` | `+0.049` | `+0.049` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateAgent` | `0.979` | `0.979` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateAgentFloor` | `0.843` | `0.843` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateAgentMargin` | `+0.136` | `+0.136` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRecallSix` | `0.736` | `0.736` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateFone` | `0.821` | `0.821` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateFNR` | `0.264` | `0.264` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateECE` | `0.001` | `0.001` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGatePassed` | `9` | `9` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateMeasured` | `11` | `11` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateTotal` | `13` | `13` | `docs/traceability/requirements.yaml` | the last gate id in the file | MATCH |
| `\nGateFPRFlag` | `0.0006` | `0.0006` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGatePrecFPR` | `0.471` | `0.471` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGatePrecCeiling` | `0.494` | `0.494` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateOnnx` | `\ensuremath{4.06 \times 10^{-7}}` | `4.06e-07` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateSeedAUC` | `\ensuremath{0.9667 \pm 0.0040}` | `0.9667 +/- 0.0040` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseRules` | `0.707` | `0.707` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLR` | `0.965` | `0.965` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseRF` | `0.967` | `0.967` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseXGB` | `0.970` | `0.970` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLGB` | `0.952` | `0.952` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseIF` | `0.943` | `0.943` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatAUC` | `0.972` | `0.972` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatECEAll` | `0.0012` | `0.0012` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatECEDecision` | `0.0185` | `0.0185` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatDecisionRows` | `767` | `767` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatAdditivity` | `\ensuremath{1.13 \times 10^{-5}}` | `1.13e-05` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupCounterparty` | `0.925` | `0.925` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupVelocity` | `0.860` | `0.860` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupTemporal` | `0.856` | `0.856` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupGeographic` | `0.829` | `0.829` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupMaxDrop` | `0.034` | `0.034` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupsAtMostSmall` | `0.007` | `0.007` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) | second-largest removal cost; device_and_channel ties at the same value | MATCH |
| `\nRevRecall` | `9.1\%` | `9.1%` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nRevCI` | `[4.2\%, 18.4\%]` | `[4.2%, 18.4%]` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nRevCaught` | `6` | `6` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nRevFraud` | `66` | `66` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nRevAUC` | `0.708` | `0.708` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseVarRecall` | `94.4\%` | `94.4%` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseVarCI` | `[92.7\%, 95.8\%]` | `[92.7%, 95.8%]` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseVarFraud` | `827` | `827` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nNovelRecall` | `98.6\%` | `98.6%` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nNovelCI` | `[92.6\%, 99.8\%]` | `[92.6%, 99.8%]` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nRevRecallFirst` | `6.1\%` | `6.1%` | `docs/benchmarks/m4_battery_pb61.txt` (evidence commit `dbda585`) |  | MATCH |
| `\nRevCIFirst` | `[2.4\%, 14.6\%]` | `[2.4%, 14.6%]` | `docs/benchmarks/m4_battery_pb61.txt` (evidence commit `dbda585`) |  | MATCH |
| `\nBaseVarRecallFirst` | `94.2\%` | `94.2%` | `docs/benchmarks/m4_battery_pb61.txt` (evidence commit `dbda585`) |  | MATCH |
| `\nBaseVarCIFirst` | `[92.4\%, 95.6\%]` | `[92.4%, 95.6%]` | `docs/benchmarks/m4_battery_pb61.txt` (evidence commit `dbda585`) |  | MATCH |
| `\nLocoMaxLoss` | `0.006` | `0.006` | derived | max over countries of in-sample AUC minus unseen AUC | MATCH |
| `\nEarlyGroupMin` | `0.845` | `0.845` | `docs/benchmarks/m4_battery.md` |  | MATCH |
| `\nEarlyLocoMaxLoss` | `0.002` | `0.002` | `docs/benchmarks/m4_battery.md` |  | MATCH |
| `\nEarlyNovelRecall` | `100\%` | `1.000` | `docs/benchmarks/m4_battery.md` | printed as a proportion; the paper states it as a percentage | MATCH |
| `\nEarlyBaseRecall` | `95.1\%` | `0.951` | `docs/benchmarks/m4_battery.md` | printed as a proportion; the paper states it as a percentage | MATCH |
| `\nSeedSdEnsemble` | `0.000612` | `0.000612` | `docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nSeedSdXGB` | `0.000609` | `0.000609` | `docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nSeedSdLGB` | `0.000700` | `0.000700` | `docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nFrontShapSmall` | `0.03` | `0.03` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrontShapLarge` | `0.84` | `0.84` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrontAUCDelta` | `-0.003` | `-0.003` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrontRatio` | `28` | `28` | derived | the largest configuration's SHAP cost over the smallest's, rounded | MATCH |
| `\nFrontRatioLow` | `24` | `24` | derived | largest SHAP cost over the smallest, low end their rounding allows | MATCH |
| `\nFrontRatioHigh` | `34` | `34` | derived | largest SHAP cost over the smallest, high end their rounding allows | MATCH |
| `\nFrontNegative` | `2 of 5` | `2 of 5` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nPredEarly` | `four` | `four` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nPredEarlyRight` | `one` | `One` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nPredTotal` | `seven` | `seven` | derived | the register's four plus the three later notebook predictions (PB-56 lead tail; ADR 0028 reversal scam; PB-67 learning curve), each located in the notebook | MATCH |
| `\nPrespecShown` | `six` | `six` | derived | rows of Table tab:predictions whose commits the audit orders (check_prespecified) | MATCH |
| `\nPrespecNotShown` | `four` | `four` | derived | rows of Table tab:predictions committed with or after their evidence, or rebased after it | MATCH |
| `\nRevPredLow` | `0.15` | `0.15` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nRevPredHigh` | `0.55` | `0.55` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nRevPredFloor` | `0.10` | `0.10` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nWeightXGB` | `0.55` | `0.55` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nWeightLGB` | `0.45` | `0.45` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nFlagThreshold` | `0.60` | `0.60` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nBlockThreshold` | `0.85` | `0.85` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nFPRBudget` | `1\%` | `1%` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nTargetRecall` | `0.880` | `0.880` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nTargetFNR` | `12.0\%` | `12.0%` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nResamples` | `1{,}000` | `1,000` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nSeeds` | `five` | `5` | `docs/prompts/FraudShield_Master_Build_Prompt.md` | the paper writes the count as a word | MATCH |
| `\nGateTrainFraud` | `260` | `260` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateXGBRounds` | `191` | `191` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateLGBRounds` | `342` | `342` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateSPW` | `114.4` | `114.4` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRecallSixci` | `[0.707, 0.765]` | `[0.707, 0.765]` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateFoneci` | `[0.800, 0.840]` | `[0.800, 0.840]` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateFPRBlock` | `0.000` | `0.000` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateShapCov` | `1.000` | `1.000` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGatePrecSix` | `0.927` | `0.927` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateSeedRecallSix` | `\ensuremath{0.7440 \pm 0.0410}` | `0.7440 +/- 0.0410` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseRulesR` | `0.019` | `0.019` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLRR` | `0.815` | `0.815` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseRFR` | `0.874` | `0.874` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseXGBR` | `0.874` | `0.874` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLGBR` | `0.826` | `0.826` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseIFR` | `0.619` | `0.619` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblAgentD` | `-0.0038` | `-0.0038` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblCorridorD` | `-0.0041` | `-0.0041` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblRoundD` | `-0.0025` | `-0.0025` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblMonthD` | `-0.0008` | `-0.0008` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblCardD` | `-0.0420` | `-0.0420` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblCardAUC` | `0.928` | `0.928` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAblCardR` | `0.677` | `0.677` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTestLegit` | `100{,}924` | `100924` | derived | test rows minus test fraud | MATCH |
| `\nGateFPRBlockCount` | `32` | `32` | derived | ML-GATE-05's unrounded FPR (metrics.json) times the legitimate test rows; must be whole | MATCH |
| `\nGateFPRBlockPct` | `0.03\%` | `0.03%` | derived | ML-GATE-05's unrounded FPR (metrics.json) as a percentage | MATCH |
| `\nRocPoints` | `200` | `200` | derived | vertices of the plotted ROC curve | MATCH |
| `\nRocLowFPR` | `1.2\%` | `1.2%` | derived | FPR of the last plotted ROC vertex below the recall target (axes 264 px from x=48, y=312) | MATCH |
| `\nRocLowRecall` | `0.871` | `0.871` | derived | recall of that vertex | MATCH |
| `\nRocHighFPR` | `1.7\%` | `1.7%` | derived | FPR of the first plotted ROC vertex at or above the recall target | MATCH |
| `\nRocHighRecall` | `0.882` | `0.882` | derived | recall of that vertex | MATCH |
| `\nRevTrueFraud` | `68` | `68` | `docs/research/paper/evidence/reversal_labels_d8083dbc.txt` at `94bfd69` (evidence commit `0b04197`) |  | MATCH |
| `\nRevScoredUnlabelled` | `2` | `2` | `docs/research/paper/evidence/reversal_labels_d8083dbc.txt` at `94bfd69` (evidence commit `0b04197`) |  | MATCH |
| `\nAblUSSDD` | `-0.0004` | `-0.0004` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupDevice` | `0.720` | `0.720` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupSynth` | `0.706` | `0.706` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupAmount` | `0.655` | `0.655` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupAgent` | `0.556` | `0.556` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupProfile` | `0.548` | `0.548` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupCorridor` | `0.507` | `0.507` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupWithoutCounterparty` | `0.938` | `0.938` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nFrAucA` | `0.971` | `0.971` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrPredA` | `0.14` | `0.14` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrShapA` | `0.18` | `0.18` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrCostA` | `0.03` | `0.03` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrAucB` | `0.974` | `0.974` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrPredB` | `0.15` | `0.15` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrShapB` | `0.27` | `0.27` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrCostB` | `0.13` | `0.13` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrAucC` | `0.972` | `0.972` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrPredC` | `0.15` | `0.15` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrShapC` | `0.51` | `0.51` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrCostC` | `0.36` | `0.36` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrAucD` | `0.969` | `0.969` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrPredD` | `0.16` | `0.16` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrShapD` | `0.74` | `0.74` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrCostD` | `0.57` | `0.57` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrAucE` | `0.968` | `0.968` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrPredE` | `0.17` | `0.17` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrShapE` | `1.01` | `1.01` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrCostE` | `0.84` | `0.84` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nFrRequests` | `900` | `900` | `docs/research/paper/evidence/round2_frontier_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nSpecVarianceClaim` | `12\%` | `12%` | `docs/research/claims_register.md` |  | MATCH |
| `\nLocoKEin` | `0.980` | `0.980` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoKEout` | `0.977` | `0.977` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoKEf` | `264` | `264` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoRWin` | `0.969` | `0.969` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoRWout` | `0.964` | `0.964` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoRWf` | `445` | `445` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoTZin` | `0.967` | `0.967` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoTZout` | `0.964` | `0.964` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoTZf` | `152` | `152` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoUGin` | `0.967` | `0.967` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoUGout` | `0.961` | `0.961` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoUGf` | `110` | `110` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoCDin` | `0.989` | `0.989` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoCDout` | `0.988` | `0.988` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLocoCDf` | `14` | `14` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nNovelCaught` | `72` | `72` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAdaptRecall` | `90.9\%` | `90.9%` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAdaptCI` | `[62.3\%, 98.4\%]` | `[62.3%, 98.4%]` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nAdaptFraud` | `11` | `11` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nRevCaughtFirst` | `4` | `4` | `docs/benchmarks/m4_battery_pb61.txt` (evidence commit `dbda585`) |  | MATCH |
| `\nRevAUCFirst` | `0.656` | `0.656` | `docs/benchmarks/m4_battery_pb61.txt` (evidence commit `dbda585`) |  | MATCH |
| `\nHwCPU` | `Intel Core i5-6200U at 2.30~GHz, 2 cores / 4 threads` | `Intel Core i5-6200U @ 2.30 GHz, 2 cores / 4 threads` | `docs/benchmarks/hardware.md` |  | MATCH |
| `\nHwMem` | `7.8~GiB` | `7.8 GiB` | `docs/benchmarks/hardware.md` |  | MATCH |
| `\nBatTestRows` | `101{,}909` | `101909` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatTestFraud` | `985` | `985` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nFrHeldOutRows` | `60{,}000` | `60,000` | `docs/benchmarks/m4_frontier.md` |  | MATCH |
| `\nFrHeldOutFraud` | `551` | `551` | `docs/benchmarks/m4_frontier.md` |  | MATCH |
| `\nFloorDrawRows` | `1{,}006{,}249` | `1,006,249` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nFingerprintA` | `6abde44e5e952f4f5c6b86aac0fb934cd4de023b53ec1f2cfe9dc01cf0a3e420` | `6abde44e5e952f4f5c6b86aac0fb934cd4de023b53ec1f2cfe9dc01cf0a3e420` | `docs/research/claims_register.md` |  | MATCH |
| `\nFingerprintB` | `d8083dbc742c20437bf3d060614f88849059eb8cf12bd0d3bbb092b518e6be32` | `d8083dbc742c20437bf3d060614f88849059eb8cf12bd0d3bbb092b518e6be32` | `dataset/realism_report.md` |  | MATCH |
| `\nPeriodStart` | `2024-01-01` | `2024-01-01` | `docs/ml/datasheet.md` |  | MATCH |
| `\nPeriodEnd` | `2025-12-31` | `2025-12-31` | `docs/ml/datasheet.md` |  | MATCH |
| `\nMonths` | `24` | `24` | `dataset/realism_report.md` |  | MATCH |
| `\nTestStart` | `2025-10-28 22:02 UTC` | `2025-10-28 22:02 UTC` | `dataset/realism_report.md` |  | MATCH |
| `\nMixMM` | `40.84\%` | `40.84%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixTargetMM` | `41.0\%` | `41.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixUSSD` | `18.08\%` | `18.08%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixTargetUSSD` | `18.0\%` | `18.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixAgent` | `13.96\%` | `13.96%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixTargetAgent` | `14.0\%` | `14.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixCard` | `12.02\%` | `12.02%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixTargetCard` | `12.0\%` | `12.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixOnline` | `9.08\%` | `9.08%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixTargetOnline` | `9.0\%` | `9.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixBank` | `6.01\%` | `6.01%` | `dataset/realism_report.md` |  | MATCH |
| `\nMixTargetBank` | `6.0\%` | `6.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixRW` | `42.01\%` | `42.01%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixTargetRW` | `42.0\%` | `42.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixKE` | `27.99\%` | `27.99%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixTargetKE` | `28.0\%` | `28.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixTZ` | `15.00\%` | `15.00%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixTargetTZ` | `15.0\%` | `15.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixUG` | `10.00\%` | `10.00%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixTargetUG` | `10.0\%` | `10.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixCD` | `4.99\%` | `4.99%` | `dataset/realism_report.md` |  | MATCH |
| `\nCMixTargetCD` | `5.0\%` | `5.0%` | `dataset/realism_report.md` |  | MATCH |
| `\nScenATO` | `1{,}402` | `1402` | derived | `account_takeover` column of the scenario table, summed over 24 months | MATCH |
| `\nScenAgent` | `1{,}048` | `1048` | derived | `agent_fraud` column of the scenario table, summed over 24 months | MATCH |
| `\nScenCNP` | `1{,}237` | `1237` | derived | `card_not_present` column of the scenario table, summed over 24 months | MATCH |
| `\nScenMerchant` | `700` | `700` | derived | `merchant_fraud` column of the scenario table, summed over 24 months | MATCH |
| `\nScenMule` | `1{,}105` | `1105` | derived | `mule_account` column of the scenario table, summed over 24 months | MATCH |
| `\nScenSimSwap` | `1{,}496` | `1496` | derived | `sim_swap` column of the scenario table, summed over 24 months | MATCH |
| `\nScenSynth` | `796` | `796` | derived | `synthetic_identity` column of the scenario table, summed over 24 months | MATCH |
| `\nScenVelocity` | `1{,}038` | `1038` | derived | `velocity` column of the scenario table, summed over 24 months | MATCH |
| `\nScenTotal` | `8{,}822` | `8822` | derived | sum of all eight scenario columns (equals the sum of the monthly Fraud column) | MATCH |
| `\nRevRows` | `68` | `68` | `docs/ml/datasheet.md` |  | MATCH |
| `\nTrainDays` | `602.34` | `602.34` | `dataset/realism_report.md` |  | MATCH |
| `\nValDays` | `57.69` | `57.69` | `dataset/realism_report.md` |  | MATCH |
| `\nCalDays` | `25.36` | `25.36` | `dataset/realism_report.md` |  | MATCH |
| `\nProvSourced` | `33` | `33` | `dataset/params_provenance.md` |  | MATCH |
| `\nProvAssumed` | `76` | `76` | `dataset/params_provenance.md` |  | MATCH |
| `\nProvCalibrated` | `15` | `15` | `dataset/params_provenance.md` |  | MATCH |
| `\nProvTotal` | `124` | `124` | `dataset/params_provenance.md` |  | MATCH |
| `\nProvFraudTotal` | `30` | `30` | `dataset/params_provenance.md` |  | MATCH |
| `\nProvFraudAssumed` | `26` | `26` | `dataset/params_provenance.md` |  | MATCH |
| `\nProvFraudCalibrated` | `4` | `4` | `dataset/params_provenance.md` |  | MATCH |
| `\nChRowsMM` | `41{,}439` | `41439` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChFraudMM` | `250` | `250` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChAucMM` | `0.958` | `0.958` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChRowsUSSD` | `18{,}325` | `18325` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChFraudUSSD` | `193` | `193` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChAucUSSD` | `0.956` | `0.956` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChRowsAgent` | `14{,}075` | `14075` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChFraudAgent` | `151` | `151` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChAucAgent` | `0.981` | `0.981` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChRowsCard` | `12{,}228` | `12228` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChFraudCard` | `148` | `148` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChAucCard` | `0.988` | `0.988` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChRowsOnline` | `9{,}527` | `9527` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChFraudOnline` | `125` | `125` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChAucOnline` | `0.981` | `0.981` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChRowsBank` | `6{,}315` | `6315` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChFraudBank` | `118` | `118` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nChAucBank` | `0.998` | `0.998` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nCoRowsRW` | `42{,}810` | `42810` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nCoRowsKE` | `28{,}516` | `28516` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nCoRowsTZ` | `15{,}274` | `15274` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nCoRowsUG` | `10{,}244` | `10244` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nCoRowsCD` | `5{,}065` | `5065` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nColumnMaxAUCB` | `0.709` | `0.709` | `dataset/realism_report.md` |  | MATCH |
| `\nEventDelayAUCB` | `0.726` | `0.726` | `dataset/realism_report.md` |  | MATCH |
| `\nIntervalLevel` | `95\%` | `95%` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrainableFeatures` | `38` | `38` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nLcTrainA` | `30{,}000` | `30,000` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcFraudA` | `259` | `259` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcTrainB` | `60{,}000` | `60,000` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcFraudB` | `532` | `532` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcTrainC` | `120{,}000` | `120,000` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcFraudC` | `1{,}066` | `1,066` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcTrainD` | `240{,}000` | `240,000` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcFraudD` | `2{,}132` | `2,132` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcRecallA` | `0.795` | `0.795` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRecallciA` | `[0.766, 0.818]` | `[0.766, 0.818]` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRecallB` | `0.799` | `0.799` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRecallciB` | `[0.773, 0.823]` | `[0.773, 0.823]` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRecallC` | `0.798` | `0.798` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRecallciC` | `[0.772, 0.821]` | `[0.772, 0.821]` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRecallD` | `0.783` | `0.783` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcRecallciD` | `[0.755, 0.808]` | `[0.755, 0.808]` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcSeedRecallA` | `\ensuremath{0.7669 \pm 0.0245}` | `0.7669 +/- 0.0245` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcSeedRecallB` | `\ensuremath{0.7722 \pm 0.0376}` | `0.7722 +/- 0.0376` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcSeedRecallC` | `\ensuremath{0.7787 \pm 0.0230}` | `0.7787 +/- 0.0230` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcSeedRecallD` | `\ensuremath{0.7945 \pm 0.0243}` | `0.7945 +/- 0.0243` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcBudgetRecallD` | `0.860` | `0.860` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcBudgetRecallciD` | `[0.838, 0.881]` | `[0.838, 0.881]` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcSeedBudgetD` | `\ensuremath{0.8763 \pm 0.0128}` | `0.8763 +/- 0.0128` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nLcBudgetRecallC` | `0.878` | `0.878` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcCorpusSmall` | `340{,}000` | `340000` | `docs/benchmarks/m4_learning_curve_cache_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcCorpusLarge` | `520{,}000` | `520000` | `docs/benchmarks/m4_learning_curve_cache_240k_d8083dbc.txt` at `3dacca6` (evidence commit `2a876d8`) |  | MATCH |
| `\nGateCorpus` | `400{,}000` | `400000` | `docs/benchmarks/m4_evaluate_gate_d8083dbc.txt` (evidence commit `bcaea94`) |  | MATCH |
| `\nLcStepCD` | `-0.015` | `-0.015` | derived | seed-1 recall at 0.60, 240K minus 120K | MATCH |
| `\nLcMaxUpper` | `0.826` | `0.826` | derived | largest upper bound of the seed-1 row-bootstrap and account-clustered intervals | MATCH |
| `\nLcSeedGain` | `0.028` | `0.028` | derived | five-seed mean recall at 0.60, 240K minus 30K, to three places | MATCH |
| `\nSelDepthCount` | `six` | `six` | derived | corpus depths in the training-selection test | MATCH |
| `\nSelMaxSd` | `0.0410` | `0.0410` | derived | largest five-seed standard deviation of recall at 0.60 across corpus depths | MATCH |
| `\nFvSeedGainFixed` | `0.032` | `0.032` | derived | five-seed mean recall at 0.60 on the whole-draw corpus, full volume minus 30,000 rows | MATCH |
| `\nLoFraudSum` | `977` | `977` | derived | test frauds over the eight scenarios of leave-one-fraud-type-out | MATCH |
| `\nLcSpanSeedOne` | `0.016` | `0.016` | derived | largest minus smallest of the four seed-1 values | MATCH |
| `\nSelGapSeedOne` | `0.059` | `0.059` | derived | seed 1 at 30,000 rows: the curve's corpus minus the gate's | MATCH |
| `\nSelGapMeans` | `0.023` | `0.023` | derived | five-seed means at 30,000 rows: the curve's corpus minus the gate's, to three places | MATCH |
| `\nLcStepAC` | `0.003` | `0.003` | derived | seed-1 recall at 0.60, 120K minus 30K (both from one cache) | MATCH |
| `\nLcSeedGainAC` | `0.012` | `0.012` | derived | five-seed mean recall at 0.60, 120K minus 30K, to three places | MATCH |
| `\nLcSeedStepCD` | `0.016` | `0.016` | derived | five-seed mean recall at 0.60, 240K minus 120K, to three places | MATCH |
| `\nLcVolumeFactor` | `eight` | `eight` | derived | largest training size over the smallest, as a word | MATCH |
| `\nLcVolumeFactorAC` | `four` | `four` | derived | 120K over 30K, as a word | MATCH |
| `\nLcPredLow` | `0.76` | `0.76` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nLcPredHigh` | `0.84` | `0.84` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nLcPredBudgetLow` | `0.87` | `0.87` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nLcPredBudgetHigh` | `0.91` | `0.91` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nNoSourceFeatures` | `five` | `Five` | `docs/adr/0055-five-features-have-no-source-data-on-this-benchmark.md` at `m6-complete` |  | MATCH |
| `\nNoSourceShare` | `11\%` | `11%` | derived | the no-source count over the catalogue's 44, to a whole per cent | MATCH |
| `\nCountries` | `five` | `five` | derived | rows of the realism report's country-mix table | MATCH |
| `\nChannels` | `six` | `six` | derived | rows of the realism report's channel-mix table | MATCH |
| `\nScenarios` | `eight` | `8` | `dataset/realism_report.md` |  | MATCH |
| `\nScenariosCap` | `Eight` | `8` | `dataset/realism_report.md` |  | MATCH |
| `\nGroupsAboveCeiling` | `four` | `four` | derived | diagnostic-model groups alone whose AUC exceeds the single-feature ceiling | MATCH |
| `\nGroupsAboveCeilingCap` | `Four` | `four` | derived | the same count, capitalised at a sentence start | MATCH |
| `\nDeviceNaNFeatures` | `four` | `Four` | `ml/src/fraudshield_ml/features/registry.py` |  | MATCH |
| `\nAdaptMonthIndex` | `12` | `12` | `dataset/generator/params/fraud.yaml` |  | MATCH |
| `\nAdaptMonthOrdinal` | `thirteenth` | `thirteenth` | `dataset/generator/params/fraud.yaml` |  | MATCH |
| `\nEventLeakShare` | `99.84\%` | `99.84%` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nEventLeakBase` | `41.96\%` | `41.96%` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nMinSigmas` | `3` | `3` | `dataset/src/fraudshield_dataset/generator/fraud.py` |  | MATCH |
| `\nFingerprintSampleRows` | `1{,}024` | `1024` | `dataset/src/fraudshield_dataset/fingerprint.py` |  | MATCH |
| `\nResolutions` | `51` | `51` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nLatencyTargetFlagged` | `40` | `40` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nSpecRows` | `5{,}000{,}000` | `5,000,000` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nRazaqInterviews` | `96` | `96` | `docs/research/paper/reference_audit.md` at `ee97bbd` |  | MATCH |
| `\nFeatureGroups` | `ten` | `ten` | derived | entries of the registry test's EXPECTED_GROUP_COUNTS | MATCH |
| `\nSynthTerms` | `four` | `four` | `ml/src/fraudshield_ml/features/registry.py` |  | MATCH |
| `\nSpecTolerance` | `\ensuremath{10^{-9}}` | `1e-9` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nSlackUlps` | `4.5 million` | `4.5 million` | `docs/ml/training_serving_parity.md` |  | MATCH |
| `\nParityCrossover` | `999` | `999` | derived | |b| at which 1e-12 + 1e-12|b| equals the specification's absolute 1e-9 | MATCH |
| `\nFeatureLatencyTarget` | `10` | `10` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nCounterpartyNewMci` | `0.037` | `0.037` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nTxCountHourMci` | `0.039` | `0.039` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nImpliedSpeedMci` | `0.040` | `0.040` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nSecondsSinceMci` | `0.040` | `0.040` | `docs/benchmarks/single_feature_baseline.md` |  | MATCH |
| `\nFeaturesAboveCeilingCI` | `two` | `two` | derived | features whose separation minus its interval half-width exceeds the ceiling | MATCH |
| `\nUnboundedFixed` | `five` | `five` | `docs/benchmarks/m3_single_feature_auc_2c80ef6.txt` |  | MATCH |
| `\nGateFailed` | `two` | `two` | derived | gate metrics measured minus gate metrics passing | MATCH |
| `\nGateUnmeasured` | `two` | `two` | derived | gate metrics specified minus gate metrics measured | MATCH |
| `\nEceBins` | `15` | `15` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGateRealisedFPR` | `0.0096` | `0.0096` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nFvRealisedFPR` | `0.0049` | `0.0049` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nGatePrecCeilingRealised` | `0.505` | `0.505` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nSmallCountThreshold` | `30` | `30` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLRGap` | `0.005` | `0.005` | derived | ensemble AUC minus logistic regression's | MATCH |
| `\nLcCorpusPlanned` | `360{,}000` | `360,000` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nLcRealFprA` | `0.0068` | `0.0068` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRealFprB` | `0.0030` | `0.0030` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRealFprC` | `0.0082` | `0.0082` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcRealFprD` | `0.0026` | `0.0026` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nBatLowBinRows` | `100{,}798` | `100798` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBatLowBinTop` | `0.1` | `0.1` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nEarlyHeldOutRows` | `60{,}000` | `60,000` | `docs/benchmarks/m4_battery.md` |  | MATCH |
| `\nLatencyPNineNineShap` | `40` | `40` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nFrTreesA` | `50` | `50` | derived | trees of frontier row 1 | MATCH |
| `\nFrDepthA` | `3` | `3` | derived | depth of frontier row 1 | MATCH |
| `\nFrTreesB` | `100` | `100` | derived | trees of frontier row 2 | MATCH |
| `\nFrDepthB` | `4` | `4` | derived | depth of frontier row 2 | MATCH |
| `\nFrTreesC` | `200` | `200` | derived | trees of frontier row 3 | MATCH |
| `\nFrDepthC` | `5` | `5` | derived | depth of frontier row 3 | MATCH |
| `\nFrTreesD` | `400` | `400` | derived | trees of frontier row 4 | MATCH |
| `\nFrDepthD` | `6` | `6` | derived | depth of frontier row 4 | MATCH |
| `\nFrTreesE` | `800` | `800` | derived | trees of frontier row 5 | MATCH |
| `\nFrDepthE` | `8` | `8` | derived | depth of frontier row 5 | MATCH |
| `\nCoFraudRateCD` | `0.28\%` | `0.28%` | derived | CD's test-period fraud rows over its test rows | MATCH |
| `\nCoFraudRateMin` | `0.93\%` | `0.93%` | derived | lowest test-period fraud rate among KE, RW, TZ and UG | MATCH |
| `\nCoFraudRateMax` | `1.07\%` | `1.07%` | derived | highest test-period fraud rate among KE, RW, TZ and UG | MATCH |
| `\nLeadMedianMinutes` | `45` | `45` | `dataset/generator/params/fraud.yaml` |  | MATCH |
| `\nRevAmountMultiplier` | `3.5` | `3.5` | `dataset/generator/params/fraud.yaml` |  | MATCH |
| `\nBandWider` | `2.8` | `2.8` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nBandBlindLow` | `0.509` | `0.509` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nBandBlindHigh` | `0.530` | `0.530` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nSiblingLeakChannel` | `0.006` | `0.006` | `docs/research/claims_register.md` |  | MATCH |
| `\nSiblingLeakCurrency` | `0.001` | `0.001` | `docs/research/claims_register.md` |  | MATCH |
| `\nFingerprintColsRead` | `three` | `three` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nFingerprintColsTotal` | `fourteen` | `fourteen` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nFurtherFeatures` | `three` | `three` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nDetectorT` | `+2.89` | `+2.89` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nMarginRows` | `660` | `660` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nMarginFraud` | `two` | `two` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nSelfServiceSimDays` | `7` | `7` | `docs/srs/defect_register.md` |  | MATCH |
| `\nSelfServiceDeviceHours` | `24` | `24` | `docs/srs/defect_register.md` |  | MATCH |
| `\nSelfServiceScore` | `0.95` | `0.95` | `docs/srs/defect_register.md` |  | MATCH |
| `\nComputableTarget` | `98\%` | `98%` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nPctlFlag` | `95` | `95` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nPctlTail` | `99` | `99` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nSeedSdDiffXGB` | `-0.6\%` | `-0.6%` | `docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nSeedSdDiffLGB` | `12.5\%` | `12.5%` | `docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nPctlFeature` | `95` | `95` | `docs/prompts/FraudShield_Master_Build_Prompt.md` |  | MATCH |
| `\nSkewConstantFeatures` | `three` | `Three` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewAucTrained` | `0.9700` | `0.9700` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewFlagTrained` | `725` | `725` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewAucOutcomes` | `0.9619` | `0.9619` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewFlagOutcomes` | `585` | `585` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewAucSim` | `0.9699` | `0.9699` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewFlagSim` | `712` | `712` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewAucBoth` | `0.9611` | `0.9611` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewFlagBoth` | `563` | `563` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewTierBoth` | `254` | `254` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewHalfWidth` | `0.0075` | `0.0075` | `docs/adr/0034-serving-feeds-three-trained-features-from-state-nothing-writes.md` at `151e796` |  | MATCH |
| `\nSkewAucDrop` | `0.0089` | `0.0089` | derived | AUC as trained minus AUC as served | MATCH |
| `\nSkewLost` | `162` | `162` | derived | frauds reaching the flag threshold as trained minus as served | MATCH |
| `\nSkewLostPct` | `22\%` | `22%` | derived | that loss as a share of the frauds flagged as trained, to a whole per cent | MATCH |
| `\nFakeReviews` | `four` | `four` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nFakeDefects` | `eleven` | `eleven` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nFakeTests` | `537` | `537` | `docs/research/lab_notebook.md` at `488c5b4` |  | MATCH |
| `\nRevNovelScored` | `19` | `19` | `docs/research/paper/evidence/reversal_novelty_d8083dbc.txt` at `ab03392` (evidence commit `94bfd69`) |  | MATCH |
| `\nRevNotNovelScored` | `47` | `47` | `docs/research/paper/evidence/reversal_novelty_d8083dbc.txt` at `ab03392` (evidence commit `94bfd69`) |  | MATCH |
| `\nRevPriorThreePlus` | `27` | `27` | `docs/research/paper/evidence/reversal_novelty_d8083dbc.txt` at `ab03392` (evidence commit `94bfd69`) |  | MATCH |
| `\nRevPriorThreshold` | `3` | `3` | `docs/research/paper/evidence/reversal_novelty_d8083dbc.txt` at `ab03392` (evidence commit `94bfd69`) |  | MATCH |
| `\nSeedSdRelXGB` | `+0.62\%` | `+0.62%` | derived | ensemble SD over XGBoost's minus one, per-seed AUCs | MATCH |
| `\nSeedSdRelLGB` | `-12.49\%` | `-12.49%` | derived | ensemble SD over LightGBM's minus one, per-seed AUCs | MATCH |
| `\nCluTestAccounts` | `5{,}878` | `5878` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluFraudAccounts` | `307` | `307` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluAUCci` | `[0.962, 0.978]` | `[0.962, 0.978]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluRecallFPRci` | `[0.846, 0.893]` | `[0.846, 0.893]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluRecallSixci` | `[0.705, 0.767]` | `[0.705, 0.767]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluFoneci` | `[0.798, 0.843]` | `[0.798, 0.843]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nRowUSSDci` | `[0.931, 0.972]` | `[0.931, 0.972]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluUSSDci` | `[0.922, 0.974]` | `[0.922, 0.974]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluCounterpartyLow` | `0.910` | `0.910` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluVelocityLow` | `0.839` | `0.839` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluTemporalLow` | `0.837` | `0.837` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluGeographicLow` | `0.811` | `0.811` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluDeviceci` | `[0.677, 0.763]` | `[0.677, 0.763]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nGroupsAboveCeilingClu` | `four` | `four` | derived | groups whose account-clustered lower bound exceeds the single-feature ceiling | MATCH |
| `\nNovelAccounts` | `16` | `16` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nRevAccounts` | `66` | `66` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluNovelci` | `[0.955, 1.000]` | `[0.955, 1.000]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluRevci` | `[0.029, 0.161]` | `[0.029, 0.161]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nBaseAccounts` | `222` | `222` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nCluBaseci` | `[0.930, 0.958]` | `[0.930, 0.958]` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nVelRatioAlone` | `0.879` | `0.879` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nVelPeak` | `0.875` | `0.875` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nVelPeakRound` | `25` | `25` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nVelRounds` | `200` | `200` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nVelAtRounds` | `0.860` | `0.860` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nVelWeightedAtRounds` | `0.844` | `0.844` | `docs/research/paper/evidence/clustered_intervals_d8083dbc.txt` at `33596a2` (evidence commit `c85a82c`) |  | MATCH |
| `\nFvTrainRows` | `792{,}162` | `792,162` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvTrainFraud` | `6{,}846` | `6,846` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvAUC` | `0.975` | `0.975` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvAUCci` | `[0.969, 0.981]` | `[0.969, 0.981]` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvRecallFPR` | `0.852` | `0.852` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvRecallFPRci` | `[0.828, 0.873]` | `[0.828, 0.873]` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvRecallSix` | `0.764` | `0.764` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvRecallSixci` | `[0.736, 0.790]` | `[0.736, 0.790]` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvFone` | `0.848` | `0.848` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvFNR` | `0.236` | `0.236` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvMM` | `0.969` | `0.969` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvUSSD` | `0.960` | `0.960` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvAgent` | `0.985` | `0.985` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvMargin` | `+0.096` | `+0.096` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvRecallFPRMargin` | `+0.254` | `+0.254` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvMMMargin` | `+0.087` | `+0.087` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvUSSDMargin` | `+0.056` | `+0.056` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvAgentMargin` | `+0.142` | `+0.142` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvSeedRecallSix` | `\ensuremath{0.7884 \pm 0.0169}` | `0.7884 +/- 0.0169` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvSeedAUC` | `\ensuremath{0.9738 \pm 0.0015}` | `0.9738 +/- 0.0015` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvXgbRounds` | `108` | `108` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvLgbRounds` | `13` | `13` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvPrecSix` | `0.953` | `0.953` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvPassed` | `9` | `9` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvBaseRules` | `0.705` | `0.705` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvBaseLR` | `0.966` | `0.966` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvBaseRF` | `0.967` | `0.967` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvBaseXGB` | `0.975` | `0.975` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvBaseLGB` | `0.946` | `0.946` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvBaseIF` | `0.935` | `0.935` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFullVolumeFactor` | `26` | `26` | derived | full-volume training rows over the gate's, rounded to a whole number | MATCH |
| `\nFvRuleBound` | `0.783` | `0.783` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nFvRuleMean` | `0.7945` | `0.7945` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nFvRuleMargin` | `0.049` | `0.049` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nFvRuleNeeded` | `0.8435` | `0.8435` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nFvPredTop` | `0.83` | `0.83` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nFvTruncated` | `102{,}522` | `102522` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvTruncatedShare` | `0.1294` | `0.1294` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFvTruncatedDays` | `90` | `90` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLcCluciA` | `[0.765, 0.825]` | `[0.765, 0.825]` | `docs/research/paper/evidence/round2_curve_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLcCluciB` | `[0.771, 0.826]` | `[0.771, 0.826]` | `docs/research/paper/evidence/round2_curve_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLcCluciC` | `[0.772, 0.825]` | `[0.772, 0.825]` | `docs/research/paper/evidence/round2_curve_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLcCluciD` | `[0.752, 0.814]` | `[0.752, 0.814]` | `docs/research/paper/evidence/round2_curve_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseRulesDiff` | `-0.2633` | `-0.2633` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseRulesDiffci` | `[-0.288, -0.240]` | `[-0.288, -0.240]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseLRDiff` | `-0.0050` | `-0.0050` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseLRDiffci` | `[-0.009, -0.002]` | `[-0.009, -0.002]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseRFDiff` | `-0.0027` | `-0.0027` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseRFDiffci` | `[-0.007, 0.001]` | `[-0.007, 0.001]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseXGBDiff` | `+0.0000` | `+0.0000` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseXGBDiffci` | `[-0.000, 0.000]` | `[-0.000, 0.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseLGBDiff` | `-0.0184` | `-0.0184` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseLGBDiffci` | `[-0.026, -0.011]` | `[-0.026, -0.011]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseIFDiff` | `-0.0275` | `-0.0275` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBaseIFDiffci` | `[-0.034, -0.021]` | `[-0.034, -0.021]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nAblAgentDiffci` | `[-0.007, -0.001]` | `[-0.007, -0.001]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nAblCorridorDiffci` | `[-0.007, -0.001]` | `[-0.007, -0.001]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nAblRoundDiffci` | `[-0.005, -0.000]` | `[-0.005, -0.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nAblMonthDiffci` | `[-0.003, 0.001]` | `[-0.003, 0.001]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nAblCardDiffci` | `[-0.051, -0.033]` | `[-0.051, -0.033]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nAblUSSDDiffci` | `[-0.002, 0.002]` | `[-0.002, 0.002]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateCalECEAll` | `0.0014` | `0.0014` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateCalRegionRows` | `782` | `782` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateCalRegionFraud` | `725` | `725` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateCalRegionECE` | `0.0284` | `0.0284` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nFvCalECEAll` | `0.0011` | `0.0011` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvCalRegionRows` | `790` | `790` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvCalRegionFraud` | `753` | `753` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvCalRegionECE` | `0.0247` | `0.0247` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nPbDays` | `63` | `63` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPerThousand` | `1{,}000` | `1,000` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbPartialRows` | `1{,}922` | `1922` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbPRAUC` | `0.844` | `0.844` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbPRAUCci` | `[0.820, 0.866]` | `[0.820, 0.866]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbPRBase` | `0.527` | `0.527` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbPRBaseci` | `[0.495, 0.557]` | `[0.495, 0.557]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudAPrec` | `0.770` | `0.770` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudAPrecci` | `[0.692, 0.841]` | `[0.692, 0.841]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudACeil` | `0.849` | `0.849` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudAShare` | `0.91` | `0.91` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudARecall` | `0.499` | `0.499` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudARecallci` | `[0.461, 0.547]` | `[0.461, 0.547]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudAPer` | `6.30` | `6.30` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudATies` | `266` | `266` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudAFraudRwf` | `34{,}627{,}844` | `34627844` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudALegitRwf` | `9{,}945{,}998` | `9945998` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBPrec` | `0.275` | `0.275` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBPrecci` | `[0.232, 0.316]` | `[0.232, 0.316]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBCeil` | `0.309` | `0.309` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBShare` | `0.89` | `0.89` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBRecall` | `0.892` | `0.892` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBRecallci` | `[0.871, 0.910]` | `[0.871, 0.910]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBPer` | `31.50` | `31.50` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBTies` | `605` | `605` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBFraudRwf` | `59{,}306{,}772` | `59306772` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudBLegitRwf` | `127{,}526{,}710` | `127526710` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCPrec` | `0.142` | `0.142` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCPrecci` | `[0.121, 0.163]` | `[0.121, 0.163]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCCeil` | `0.154` | `0.154` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCShare` | `0.92` | `0.92` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCRecall` | `0.922` | `0.922` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCRecallci` | `[0.903, 0.939]` | `[0.903, 0.939]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCPer` | `63.01` | `63.01` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCTies` | `1{,}035` | `1035` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCFraudRwf` | `60{,}148{,}079` | `60148079` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudCLegitRwf` | `235{,}317{,}045` | `235317045` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDPrec` | `0.073` | `0.073` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDPrecci` | `[0.062, 0.084]` | `[0.062, 0.084]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDCeil` | `0.077` | `0.077` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDShare` | `0.95` | `0.95` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDRecall` | `0.947` | `0.947` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDRecallci` | `[0.931, 0.962]` | `[0.931, 0.962]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDPer` | `126.02` | `126.02` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDTies` | `2{,}144` | `2144` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDFraudRwf` | `60{,}690{,}533` | `60690533` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudDLegitRwf` | `477{,}012{,}050` | `477012050` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudAPrec` | `0.577` | `0.577` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudAShare` | `0.68` | `0.68` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudARecall` | `0.374` | `0.374` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudBPrec` | `0.196` | `0.196` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudBShare` | `0.64` | `0.64` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudBRecall` | `0.635` | `0.635` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudCPrec` | `0.103` | `0.103` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudCShare` | `0.67` | `0.67` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudCRecall` | `0.667` | `0.667` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudDPrec` | `0.056` | `0.056` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudDShare` | `0.72` | `0.72` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBaseBudDRecall` | `0.720` | `0.720` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudKA` | `10` | `10` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudKB` | `50` | `50` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudKC` | `100` | `100` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nBudKD` | `200` | `200` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctRows` | `5{,}599` | `5599` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctFrauds` | `54` | `54` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctAUC` | `0.955` | `0.955` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctAUCci` | `[0.909, 0.988]` | `[0.909, 0.988]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctPR` | `0.850` | `0.850` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctRecall` | `0.796` | `0.796` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonOctRecallci` | `[0.685, 0.907]` | `[0.685, 0.907]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovRows` | `47{,}692` | `47692` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovFrauds` | `466` | `466` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovAUC` | `0.977` | `0.977` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovAUCci` | `[0.969, 0.985]` | `[0.969, 0.985]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovPR` | `0.851` | `0.851` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovRecall` | `0.781` | `0.781` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonNovRecallci` | `[0.742, 0.820]` | `[0.742, 0.820]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecRows` | `48{,}618` | `48618` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecFrauds` | `465` | `465` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecAUC` | `0.976` | `0.976` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecAUCci` | `[0.967, 0.983]` | `[0.967, 0.983]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecPR` | `0.837` | `0.837` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecRecall` | `0.744` | `0.744` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDecRecallci` | `[0.705, 0.783]` | `[0.705, 0.783]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDiff` | `-0.037` | `-0.037` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nMonDiffci` | `[-0.091, 0.015]` | `[-0.091, 0.015]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbDHLow` | `0.01` | `0.01` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbDHHigh` | `0.99` | `0.99` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbDHModel` | `0.0588` | `0.0588` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nPbDHBase` | `0.1909` | `0.1909` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDRows` | `5{,}065` | `5065` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDLegit` | `5{,}051` | `5051` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDFrauds` | `14` | `14` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDFPR` | `0.0004` | `0.0004` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDFPRci` | `[0.0001, 0.0014]` | `[0.0001, 0.0014]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDFPRBlock` | `0.0004` | `0.0004` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDFPRBlockci` | `[0.0001, 0.0014]` | `[0.0001, 0.0014]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDLegitRwf` | `54{,}178` | `54178` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCDShare` | `0.00035` | `0.00035` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKERows` | `28{,}516` | `28516` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKELegit` | `28{,}252` | `28252` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKEFrauds` | `264` | `264` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKEFPR` | `0.0004` | `0.0004` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKEFPRci` | `[0.0002, 0.0007]` | `[0.0002, 0.0007]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKEFPRBlock` | `0.0003` | `0.0003` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKEFPRBlockci` | `[0.0002, 0.0006]` | `[0.0002, 0.0006]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKELegitRwf` | `383{,}980` | `383980` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairKEShare` | `0.00045` | `0.00045` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWRows` | `42{,}810` | `42810` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWLegit` | `42{,}365` | `42365` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWFrauds` | `445` | `445` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWFPR` | `0.0003` | `0.0003` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWFPRci` | `[0.0002, 0.0006]` | `[0.0002, 0.0006]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWFPRBlock` | `0.0003` | `0.0003` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWFPRBlockci` | `[0.0002, 0.0006]` | `[0.0002, 0.0006]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWLegitRwf` | `1{,}068{,}383` | `1068383` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairRWShare` | `0.00083` | `0.00083` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZRows` | `15{,}274` | `15274` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZLegit` | `15{,}122` | `15122` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZFrauds` | `152` | `152` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZFPR` | `0.0003` | `0.0003` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZFPRci` | `[0.0001, 0.0008]` | `[0.0001, 0.0008]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZFPRBlock` | `0.0003` | `0.0003` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZFPRBlockci` | `[0.0001, 0.0008]` | `[0.0001, 0.0008]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZLegitRwf` | `348{,}664` | `348664` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairTZShare` | `0.00075` | `0.00075` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGRows` | `10{,}244` | `10244` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGLegit` | `10{,}134` | `10134` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGFrauds` | `110` | `110` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGFPR` | `0.0005` | `0.0005` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGFPRci` | `[0.0002, 0.0012]` | `[0.0002, 0.0012]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGFPRBlock` | `0.0004` | `0.0004` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGFPRBlockci` | `[0.0002, 0.0010]` | `[0.0002, 0.0010]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGLegitRwf` | `190{,}765` | `190765` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUGShare` | `0.00063` | `0.00063` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllRows` | `101{,}909` | `101909` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllLegit` | `100{,}924` | `100924` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllFrauds` | `985` | `985` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllFPR` | `0.0004` | `0.0004` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllFPRci` | `[0.0003, 0.0005]` | `[0.0003, 0.0005]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllFPRBlock` | `0.0003` | `0.0003` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllFPRBlockci` | `[0.0002, 0.0005]` | `[0.0002, 0.0005]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllLegitRwf` | `2{,}045{,}969` | `2045969` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAllShare` | `0.00067` | `0.00067` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentRows` | `14{,}075` | `14075` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentLegit` | `13{,}924` | `13924` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentFrauds` | `151` | `151` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentFPR` | `0.0009` | `0.0009` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentFPRci` | `[0.0005, 0.0015]` | `[0.0005, 0.0015]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentFPRBlock` | `0.0007` | `0.0007` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentFPRBlockci` | `[0.0004, 0.0013]` | `[0.0004, 0.0013]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentLegitRwf` | `346{,}675` | `346675` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairAgentShare` | `0.00148` | `0.00148` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankRows` | `6{,}315` | `6315` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankLegit` | `6{,}197` | `6197` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankFrauds` | `118` | `118` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankFPR` | `0.0010` | `0.0010` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankFPRci` | `[0.0004, 0.0021]` | `[0.0004, 0.0021]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankFPRBlock` | `0.0010` | `0.0010` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankFPRBlockci` | `[0.0004, 0.0021]` | `[0.0004, 0.0021]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankLegitRwf` | `880{,}572` | `880572` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairBankShare` | `0.00203` | `0.00203` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardRows` | `12{,}228` | `12228` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardLegit` | `12{,}080` | `12080` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardFrauds` | `148` | `148` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardFPR` | `0.0002` | `0.0002` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardFPRci` | `[0.0001, 0.0007]` | `[0.0001, 0.0007]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardFPRBlock` | `0.0002` | `0.0002` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardFPRBlockci` | `[0.0001, 0.0007]` | `[0.0001, 0.0007]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardLegitRwf` | `351{,}438` | `351438` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairCardShare` | `0.00027` | `0.00027` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMRows` | `41{,}439` | `41439` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMLegit` | `41{,}189` | `41189` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMFrauds` | `250` | `250` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMFPR` | `0.0001` | `0.0001` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMFPRci` | `[0.0001, 0.0003]` | `[0.0001, 0.0003]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMFPRBlock` | `0.0001` | `0.0001` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMFPRBlockci` | `[0.0001, 0.0003]` | `[0.0001, 0.0003]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMLegitRwf` | `90{,}032` | `90032` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairMMShare` | `0.00014` | `0.00014` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineRows` | `9{,}527` | `9527` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineLegit` | `9{,}402` | `9402` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineFrauds` | `125` | `125` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineFPR` | `0.0007` | `0.0007` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineFPRci` | `[0.0004, 0.0015]` | `[0.0004, 0.0015]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineFPRBlock` | `0.0006` | `0.0006` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineFPRBlockci` | `[0.0003, 0.0014]` | `[0.0003, 0.0014]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineLegitRwf` | `294{,}086` | `294086` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairOnlineShare` | `0.00105` | `0.00105` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDRows` | `18{,}325` | `18325` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDLegit` | `18{,}132` | `18132` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDFrauds` | `193` | `193` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDFPR` | `0.0002` | `0.0002` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDFPRci` | `[0.0001, 0.0006]` | `[0.0001, 0.0006]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDFPRBlock` | `0.0002` | `0.0002` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDFPRBlockci` | `[0.0001, 0.0006]` | `[0.0001, 0.0006]` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDLegitRwf` | `83{,}168` | `83168` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nFairUSSDShare` | `0.00056` | `0.00056` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoProduction` | `0.852` | `0.852` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimFrauds` | `152` | `152` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimProd` | `0.941` | `0.941` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimProdci` | `[0.891, 0.969]` | `[0.891, 0.969]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimHeld` | `0.980` | `0.980` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimHeldci` | `[0.944, 0.993]` | `[0.944, 0.993]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimDrop` | `-0.039` | `-0.039` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimProdSix` | `0.908` | `0.908` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimHeldSix` | `0.789` | `0.789` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSimDropSix` | `0.118` | `0.118` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoFrauds` | `145` | `145` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoProd` | `0.993` | `0.993` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoProdci` | `[0.962, 0.999]` | `[0.962, 0.999]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoHeld` | `0.834` | `0.834` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoHeldci` | `[0.766, 0.886]` | `[0.766, 0.886]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoDrop` | `0.159` | `0.159` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoProdSix` | `0.952` | `0.952` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoHeldSix` | `0.807` | `0.807` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAtoDropSix` | `0.145` | `0.145` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentFrauds` | `106` | `106` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentProd` | `0.962` | `0.962` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentProdci` | `[0.907, 0.985]` | `[0.907, 0.985]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentHeld` | `0.434` | `0.434` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentHeldci` | `[0.344, 0.529]` | `[0.344, 0.529]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentDrop` | `0.528` | `0.528` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentProdSix` | `0.887` | `0.887` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentHeldSix` | `0.226` | `0.226` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoAgentDropSix` | `0.660` | `0.660` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelFrauds` | `103` | `103` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelProd` | `0.903` | `0.903` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelProdci` | `[0.830, 0.946]` | `[0.830, 0.946]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelHeld` | `0.864` | `0.864` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelHeldci` | `[0.785, 0.917]` | `[0.785, 0.917]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelDrop` | `0.039` | `0.039` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelProdSix` | `0.806` | `0.806` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelHeldSix` | `0.680` | `0.680` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoVelDropSix` | `0.126` | `0.126` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpFrauds` | `126` | `126` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpProd` | `0.913` | `0.913` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpProdci` | `[0.850, 0.951]` | `[0.850, 0.951]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpHeld` | `0.841` | `0.841` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpHeldci` | `[0.768, 0.895]` | `[0.768, 0.895]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpDrop` | `0.071` | `0.071` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpProdSix` | `0.817` | `0.817` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpHeldSix` | `0.706` | `0.706` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoCnpDropSix` | `0.111` | `0.111` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleFrauds` | `181` | `181` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleProd` | `0.575` | `0.575` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleProdci` | `[0.502, 0.644]` | `[0.502, 0.644]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleHeld` | `0.580` | `0.580` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleHeldci` | `[0.507, 0.650]` | `[0.507, 0.650]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleDrop` | `-0.006` | `-0.006` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleProdSix` | `0.508` | `0.508` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleHeldSix` | `0.453` | `0.453` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleDropSix` | `0.055` | `0.055` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchFrauds` | `78` | `78` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchProd` | `0.782` | `0.782` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchProdci` | `[0.678, 0.859]` | `[0.678, 0.859]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchHeld` | `0.679` | `0.679` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchHeldci` | `[0.570, 0.773]` | `[0.570, 0.773]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchDrop` | `0.103` | `0.103` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchProdSix` | `0.641` | `0.641` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchHeldSix` | `0.128` | `0.128` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMerchDropSix` | `0.513` | `0.513` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynFrauds` | `86` | `86` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynProd` | `0.895` | `0.895` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynProdci` | `[0.813, 0.944]` | `[0.813, 0.944]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynHeld` | `0.547` | `0.547` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynHeldci` | `[0.442, 0.647]` | `[0.442, 0.647]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynDrop` | `0.349` | `0.349` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynProdSix` | `0.640` | `0.640` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynHeldSix` | `0.395` | `0.395` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoSynDropSix` | `0.244` | `0.244` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrFrauds` | `115` | `115` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrProd` | `0.861` | `0.861` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrProdci` | `[0.786, 0.913]` | `[0.786, 0.913]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrHeld` | `0.870` | `0.870` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrHeldci` | `[0.796, 0.919]` | `[0.796, 0.919]` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrDrop` | `-0.009` | `-0.009` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrProdSix` | `0.800` | `0.800` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrHeldSix` | `0.713` | `0.713` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoMuleNrDropSix` | `0.087` | `0.087` | `docs/benchmarks/lofto_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLoRangeBurst` | `0.85` | `0.85` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeBurstDrop` | `0.10` | `0.10` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeMule` | `0.80` | `0.80` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeSingle` | `0.70` | `0.70` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeSingleDrop` | `0.20` | `0.20` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeAto` | `0.80` | `0.80` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeAtoDrop` | `0.10` | `0.10` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeSynLow` | `0.70` | `0.70` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeSynHigh` | `0.92` | `0.92` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nLoRangeSynDropHigh` | `0.20` | `0.20` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nFrontMachine` | `AMD EPYC 9V45 96-Core Processor` | `AMD EPYC 9V45 96-Core Processor` | `docs/research/paper/evidence/round2_run_d8083dbc.txt` at `f29793c` |  | MATCH |
| `\nReScoredRows` | `20{,}000` | `20000` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReScoredFraud` | `177` | `177` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReCorpusRows` | `200{,}000` | `200000` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReVelocity` | `0.880` | `0.880` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReVelocityci` | `0.033` | `0.033` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReCounterparty` | `0.825` | `0.825` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReCounterpartyci` | `0.038` | `0.038` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReTxHour` | `0.805` | `0.805` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReTxHourci` | `0.039` | `0.039` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReSpeed` | `0.784` | `0.784` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReSpeedci` | `0.040` | `0.040` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReSeconds` | `0.786` | `0.786` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nReSecondsci` | `0.040` | `0.040` | `docs/research/paper/evidence/round2_single_feature_auc_d8083dbc.txt` at `f29793c` (evidence commit `72e7790`) |  | MATCH |
| `\nGrVelocity` | `0.879` | `0.879` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGrVelocityci` | `[0.862, 0.894]` | `[0.862, 0.894]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGrCounterparty` | `0.819` | `0.819` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGrCounterpartyci` | `[0.796, 0.843]` | `[0.796, 0.843]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGrTxHour` | `0.814` | `0.814` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGrTxHourci` | `[0.797, 0.831]` | `[0.797, 0.831]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nReAboveCeiling` | `three` | `three` | derived | re-run features whose point estimate exceeds the ceiling | MATCH |
| `\nReAboveCeilingCI` | `one` | `one` | derived | re-run features whose interval lies wholly above the ceiling | MATCH |
| `\nLocoCluCDin` | `0.989` | `0.989` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluCDinci` | `[0.955, 1.000]` | `[0.955, 1.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluCDout` | `0.988` | `0.988` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluCDoutci` | `[0.944, 1.000]` | `[0.944, 1.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluCDAccounts` | `294` | `294` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluKEin` | `0.980` | `0.980` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluKEinci` | `[0.964, 0.991]` | `[0.964, 0.991]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluKEout` | `0.977` | `0.977` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluKEoutci` | `[0.958, 0.989]` | `[0.958, 0.989]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluKEAccounts` | `1{,}646` | `1646` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluRWin` | `0.969` | `0.969` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluRWinci` | `[0.954, 0.980]` | `[0.954, 0.980]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluRWout` | `0.964` | `0.964` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluRWoutci` | `[0.950, 0.977]` | `[0.950, 0.977]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluRWAccounts` | `2{,}469` | `2469` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluTZin` | `0.967` | `0.967` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluTZinci` | `[0.939, 0.984]` | `[0.939, 0.984]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluTZout` | `0.964` | `0.964` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluTZoutci` | `[0.935, 0.983]` | `[0.935, 0.983]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluTZAccounts` | `882` | `882` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluUGin` | `0.967` | `0.967` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluUGinci` | `[0.930, 0.987]` | `[0.930, 0.987]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluUGout` | `0.961` | `0.961` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluUGoutci` | `[0.922, 0.985]` | `[0.922, 0.985]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nLocoCluUGAccounts` | `587` | `587` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllBudgetCaught` | `6` | `6` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllBudgetRows` | `66` | `66` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllBudgetRecall` | `0.091` | `0.091` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllBudgetWilson` | `[0.042, 0.184]` | `[0.042, 0.184]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllBudgetClustered` | `[0.029, 0.161]` | `[0.029, 0.161]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllFlagCaught` | `0` | `0` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllFlagRows` | `66` | `66` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllFlagRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllFlagWilson` | `[0.000, 0.055]` | `[0.000, 0.055]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevAllFlagClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewBudgetCaught` | `6` | `6` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewBudgetRows` | `19` | `19` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewBudgetRecall` | `0.316` | `0.316` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewBudgetWilson` | `[0.154, 0.540]` | `[0.154, 0.540]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewBudgetClustered` | `[0.105, 0.522]` | `[0.105, 0.522]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewFlagCaught` | `0` | `0` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewFlagRows` | `19` | `19` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewFlagRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewFlagWilson` | `[0.000, 0.168]` | `[0.000, 0.168]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevNewFlagClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownBudgetCaught` | `0` | `0` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownBudgetRows` | `47` | `47` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownBudgetRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownBudgetWilson` | `[0.000, 0.076]` | `[0.000, 0.076]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownBudgetClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownFlagCaught` | `0` | `0` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownFlagRows` | `47` | `47` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownFlagRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownFlagWilson` | `[0.000, 0.076]` | `[0.000, 0.076]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nGateRevKnownFlagClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_gate_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nFvRevAllBudgetCaught` | `5` | `5` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllBudgetRows` | `66` | `66` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllBudgetRecall` | `0.076` | `0.076` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllBudgetWilson` | `[0.033, 0.165]` | `[0.033, 0.165]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllBudgetClustered` | `[0.016, 0.143]` | `[0.016, 0.143]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllFlagCaught` | `0` | `0` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllFlagRows` | `66` | `66` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllFlagRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllFlagWilson` | `[0.000, 0.055]` | `[0.000, 0.055]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevAllFlagClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewBudgetCaught` | `5` | `5` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewBudgetRows` | `19` | `19` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewBudgetRecall` | `0.263` | `0.263` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewBudgetWilson` | `[0.118, 0.488]` | `[0.118, 0.488]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewBudgetClustered` | `[0.062, 0.500]` | `[0.062, 0.500]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewFlagCaught` | `0` | `0` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewFlagRows` | `19` | `19` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewFlagRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewFlagWilson` | `[0.000, 0.168]` | `[0.000, 0.168]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevNewFlagClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownBudgetCaught` | `0` | `0` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownBudgetRows` | `47` | `47` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownBudgetRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownBudgetWilson` | `[0.000, 0.076]` | `[0.000, 0.076]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownBudgetClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownFlagCaught` | `0` | `0` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownFlagRows` | `47` | `47` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownFlagRecall` | `0.000` | `0.000` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownFlagWilson` | `[0.000, 0.076]` | `[0.000, 0.076]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nFvRevKnownFlagClustered` | `[0.000, 0.000]` | `[0.000, 0.000]` | `docs/research/paper/evidence/round2_full_volume_d8083dbc.txt` at `4b9499d` (evidence commit `f29793c`) |  | MATCH |
| `\nRecBaseConfirmed` | `827` | `827` | derived | base: observed and true | MATCH |
| `\nRecBaseNoise` | `8` | `8` | derived | observed, not true fraud | MATCH |
| `\nRecBaseMissed` | `11` | `11` | derived | true, not observed | MATCH |
| `\nRecNovel` | `73` | `73` | derived | novel variant, observed | MATCH |
| `\nRecAdapt` | `11` | `11` | derived | adapted variant | MATCH |
| `\nRecRevConfirmed` | `66` | `66` | derived | reversal, observed | MATCH |
| `\nRecRevMissed` | `2` | `2` | derived | reversal, missed | MATCH |
| `\nRecObserved` | `985` | `985` | derived | every observed fraud of the test period | MATCH |
| `\nRecTrue` | `990` | `990` | derived | every true fraud of the test period | MATCH |
| `\nRecMissed` | `13` | `13` | derived | every missed-fraud noise row | MATCH |
| `\nPriorReads` | `23` | `23` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nPriorReadsFull` | `11` | `11` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nPriorReadsFullRows` | `100{,}000` | `100,000` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nSelAMedian` | `30.2` | `30.2` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelAUnder` | `0.495` | `0.495` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelAUnderLong` | `1.000` | `1.000` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelARecall` | `0.683` | `0.683` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelARecallci` | `[0.646, 0.719]` | `[0.646, 0.719]` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelAMean` | `\ensuremath{0.7060 \pm 0.0191}` | `0.7060 +/- 0.0191` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBMedian` | `51.0` | `51.0` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBUnder` | `0.290` | `0.290` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBUnderLong` | `0.878` | `0.878` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBRecall` | `0.629` | `0.629` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBRecallci` | `[0.592, 0.664]` | `[0.592, 0.664]` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBMean` | `\ensuremath{0.6569 \pm 0.0211}` | `0.6569 +/- 0.0211` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCMedian` | `63.0` | `63.0` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCUnder` | `0.250` | `0.250` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCUnderLong` | `0.707` | `0.707` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCRecall` | `0.736` | `0.736` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCRecallci` | `[0.705, 0.767]` | `[0.705, 0.767]` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCMean` | `\ensuremath{0.7440 \pm 0.0410}` | `0.7440 +/- 0.0410` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDMedian` | `100.9` | `100.9` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDUnder` | `0.145` | `0.145` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDUnderLong` | `0.444` | `0.444` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDRecall` | `0.782` | `0.782` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDRecallci` | `[0.751, 0.811]` | `[0.751, 0.811]` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDMean` | `\ensuremath{0.7641 \pm 0.0130}` | `0.7641 +/- 0.0130` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelEMedian` | `177.4` | `177.4` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelEUnder` | `0.086` | `0.086` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelEUnderLong` | `0.238` | `0.238` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelERecall` | `0.759` | `0.759` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelERecallci` | `[0.729, 0.791]` | `[0.729, 0.791]` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelEMean` | `\ensuremath{0.7346 \pm 0.0384}` | `0.7346 +/- 0.0384` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFMedian` | `328.6` | `328.6` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFUnder` | `0.049` | `0.049` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFUnderLong` | `0.129` | `0.129` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFRecall` | `0.788` | `0.788` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFRecallci` | `[0.760, 0.816]` | `[0.760, 0.816]` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFMean` | `\ensuremath{0.7565 \pm 0.0384}` | `0.7565 +/- 0.0384` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelShortDays` | `30` | `30` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelLongDays` | `90` | `90` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelMeanRange` | `0.107` | `0.107` | derived | largest minus smallest five-seed mean recall at 0.60 across corpus depths | MATCH |
| `\nSelACorpus` | `300{,}000` | `300000` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBCorpus` | `360{,}000` | `360000` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCCorpus` | `400{,}000` | `400000` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDCorpus` | `520{,}000` | `520000` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelECorpus` | `700{,}000` | `700000` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nBudPredPrec` | `0.90` | `0.90` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nBudPredRecallLow` | `0.55` | `0.55` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nBudPredRecallHigh` | `0.65` | `0.65` | `docs/research/preregistration_pb66_full_volume.md` at `c61d512` |  | MATCH |
| `\nBaseBudATies` | `23` | `23` | `docs/benchmarks/pb66_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLcLgbRoundsA` | `592` | `592` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcLgbRoundsB` | `837` | `837` | `docs/benchmarks/m4_learning_curve_60k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcLgbRoundsC` | `924` | `924` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcLgbRoundsD` | `167` | `167` | `docs/benchmarks/m4_learning_curve_240k_d8083dbc.txt` at `3dacca6` (evidence commit `6b36fc5`) |  | MATCH |
| `\nBudAAlerts` | `630` | `630` | derived | the smallest budget's alerts over the period: alerts a day times whole days | MATCH |
| `\nBudATiesPct` | `42\%` | `42%` | derived | share of the smallest budget's alerts decided by ties | MATCH |
| `\nBudTiesPctLow` | `16\%` | `16%` | derived | smallest share of alerts decided by ties at the three larger budgets, rounded down | MATCH |
| `\nBudTiesPctHigh` | `19\%` | `19%` | derived | largest share of alerts decided by ties at the three larger budgets, rounded down | MATCH |
| `\nPredPb` | `three` | `three` | derived | rows of Table tab:predictions whose evidence is PB-66's evidence commit | MATCH |
| `\nSelABudgetMean` | `\ensuremath{0.8443 \pm 0.0206}` | `0.8443 +/- 0.0206` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelAAucMean` | `\ensuremath{0.9583 \pm 0.0120}` | `0.9583 +/- 0.0120` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBBudgetMean` | `\ensuremath{0.8526 \pm 0.0155}` | `0.8526 +/- 0.0155` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBAucMean` | `\ensuremath{0.9632 \pm 0.0054}` | `0.9632 +/- 0.0054` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCBudgetMean` | `\ensuremath{0.8408 \pm 0.0252}` | `0.8408 +/- 0.0252` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelCAucMean` | `\ensuremath{0.9667 \pm 0.0040}` | `0.9667 +/- 0.0040` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDBudgetMean` | `\ensuremath{0.8603 \pm 0.0274}` | `0.8603 +/- 0.0274` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelDAucMean` | `\ensuremath{0.9639 \pm 0.0030}` | `0.9639 +/- 0.0030` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelEBudgetMean` | `\ensuremath{0.8593 \pm 0.0169}` | `0.8593 +/- 0.0169` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelEAucMean` | `\ensuremath{0.9614 \pm 0.0039}` | `0.9614 +/- 0.0039` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFBudgetMean` | `\ensuremath{0.8471 \pm 0.0169}` | `0.8471 +/- 0.0169` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelFAucMean` | `\ensuremath{0.9638 \pm 0.0067}` | `0.9638 +/- 0.0067` | `docs/research/paper/evidence/round2_selection_d8083dbc.txt` at `f29793c` (evidence commit `74ea29a`) |  | MATCH |
| `\nSelBudgetRange` | `0.020` | `0.020` | derived | largest minus smallest five-seed mean (recall at the 1% FPR budget) across corpus depths | MATCH |
| `\nSelAucRange` | `0.008` | `0.008` | derived | largest minus smallest five-seed mean (AUC) across corpus depths | MATCH |
| `\nLcSeedBudgetA` | `\ensuremath{0.8514 \pm 0.0315}` | `0.8514 +/- 0.0315` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcSeedBudgetC` | `\ensuremath{0.8688 \pm 0.0186}` | `0.8688 +/- 0.0186` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcSeedAucA` | `\ensuremath{0.9562 \pm 0.0129}` | `0.9562 +/- 0.0129` | `docs/benchmarks/m4_learning_curve_30k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nLcSeedAucC` | `\ensuremath{0.9714 \pm 0.0025}` | `0.9714 +/- 0.0025` | `docs/benchmarks/m4_learning_curve_120k_d8083dbc.txt` at `3dacca6` (evidence commit `c59da11`) |  | MATCH |
| `\nFvSeedBudget` | `\ensuremath{0.8680 \pm 0.0135}` | `0.8680 +/- 0.0135` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nLcSeedBudgetGainAC` | `0.017` | `0.017` | derived | five-seed mean recall at the 1% budget, 120K minus 30K rows within one cache | MATCH |
| `\nLcSeedAucGainAC` | `0.015` | `0.015` | derived | five-seed mean AUC, 120K minus 30K rows within one cache | MATCH |
| `\nFvSeedBudgetGainFixed` | `0.021` | `0.021` | derived | five-seed mean recall at the 1% budget on the whole-draw corpus, full volume minus 30,000 rows | MATCH |
| `\nFvSeedAucGainFixed` | `0.010` | `0.010` | derived | five-seed mean AUC on the whole-draw corpus, full volume minus 30,000 rows | MATCH |
| `\nOffsetRuns` | `24` | `24` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nOffsetMean` | `0.5031` | `0.5031` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nOffsetCI` | `[0.4992, 0.5070]` | `[0.4992, 0.5070]` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nTrivAuc` | `0.659` | `0.659` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivRecall` | `0.039` | `0.039` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivMM` | `0.699` | `0.699` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivUSSD` | `0.613` | `0.613` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivAgent` | `0.707` | `0.707` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivAucMarginGate` | `+0.311` | `+0.311` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivAucMarginFv` | `+0.317` | `+0.317` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nTrivRecallMarginGate` | `+0.832` | `+0.832` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivRecallMarginFv` | `+0.813` | `+0.813` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nTrivMMMarginGate` | `+0.253` | `+0.253` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivMMMarginFv` | `+0.270` | `+0.270` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nTrivUSSDMarginGate` | `+0.339` | `+0.339` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivUSSDMarginFv` | `+0.347` | `+0.347` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
| `\nTrivAgentMarginGate` | `+0.273` | `+0.273` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nTrivAgentMarginFv` | `+0.278` | `+0.278` | `docs/benchmarks/full_volume_gate_d8083dbc.txt` at `850192b` (evidence commit `fcec52c`) |  | MATCH |
