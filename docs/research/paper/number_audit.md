# Number audit

Every macro in `numbers.tex` checked against its evidence file at `m4-complete` (`72e7790`), read with `git show`, by `audit_numbers.py`. Values are compared as printed: rounding, sign and interval bounds included. Regenerate with `python3 docs/research/paper/audit_numbers.py`; do not edit by hand.

**307 macros, 307 MATCH, 0 not matching.**

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
| `\nGatePass` | `9 of 11` | `9 of 11` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
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
| `\nBaseRFp` | `0.21` | `0.21` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseXGBp` | `0.67` | `0.67` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
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
| `\nFrontShapSmall` | `0.08` | `0.08` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrontShapLarge` | `2.80` | `2.80` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrontPredSmall` | `0.48` | `0.48` | `docs/benchmarks/m4_frontier.md` |  | MATCH |
| `\nFrontPredLarge` | `0.56` | `0.56` | `docs/benchmarks/m4_frontier.md` |  | MATCH |
| `\nFrontAUCDelta` | `-0.004` | `-0.004` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrontRatio` | `35` | `35` | `docs/benchmarks/m4_frontier.md` |  | MATCH |
| `\nFrontNegative` | `2 of 5` | `2 of 5` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nPredEarly` | `four` | `four` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nPredEarlyRight` | `one` | `One` | `docs/research/lab_notebook.md` |  | MATCH |
| `\nPredTotal` | `six` | `six` | derived | the register's four plus the two later pre-registrations (PB-56 lead tail; ADR 0028 reversal scam), each located in the notebook | MATCH |
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
| `\nBaseRulesp` | `\ensuremath{1.2 \times 10^{-223}}` | `1.2e-223` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLRp` | `0.0049` | `0.0049` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseLGBp` | `\ensuremath{2.7 \times 10^{-8}}` | `2.7e-08` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nBaseIFp` | `\ensuremath{4.8 \times 10^{-20}}` | `4.8e-20` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
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
| `\nAblUSSDD` | `-0.0004` | `-0.0004` | `docs/benchmarks/m4_gate_d8083dbc_v3.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupDevice` | `0.720` | `0.720` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupSynth` | `0.706` | `0.706` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupAmount` | `0.655` | `0.655` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupAgent` | `0.556` | `0.556` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupProfile` | `0.548` | `0.548` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupCorridor` | `0.507` | `0.507` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nGroupWithoutCounterparty` | `0.938` | `0.938` | `docs/benchmarks/m4_battery_d8083dbc_e1.txt` (evidence commit `d40fca2`) |  | MATCH |
| `\nFrAucA` | `0.995` | `0.995` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrPredA` | `0.48` | `0.48` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrShapA` | `0.56` | `0.56` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrCostA` | `0.08` | `0.08` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrAucB` | `0.992` | `0.992` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrPredB` | `0.48` | `0.48` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrShapB` | `0.81` | `0.81` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrCostB` | `0.33` | `0.33` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrAucC` | `0.991` | `0.991` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrPredC` | `0.49` | `0.49` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrShapC` | `1.49` | `1.49` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrCostC` | `1.00` | `1.00` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrAucD` | `0.990` | `0.990` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrPredD` | `0.52` | `0.52` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrShapD` | `2.38` | `2.38` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrCostD` | `1.86` | `1.86` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrAucE` | `0.990` | `0.990` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrPredE` | `0.56` | `0.56` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrShapE` | `3.36` | `3.36` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrCostE` | `2.80` | `2.80` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
| `\nFrRequests` | `900` | `900` | `docs/benchmarks/m4_frontier_6abde44e.txt` (evidence commit `95b722c`) |  | MATCH |
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
