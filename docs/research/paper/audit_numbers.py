"""Audit every number macro in numbers.tex against its evidence file, at a fixed git ref.

For each macro, SOURCES names the evidence file and a regular expression whose first group is the
value as the file prints it. The file is read with ``git show <ref>:<path>`` (never from a working
copy), the captured value is normalised the same way as the macro's value, and the two strings
must be equal: rounding, sign and interval bounds included. DERIVED entries recompute a value from
other captured values and state the arithmetic. Writes number_audit.md and exits 1 on any
mismatch, any macro without a source, or any source whose pattern no longer matches.

Usage (from the repository root): python3 docs/research/paper/audit_numbers.py [--ref m4-complete]
"""

# Patterns are literal copies of evidence text: the Unicode minus and multiplication signs those
# documents print are intentional.
# ruff: noqa: RUF001

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]

GATE = "docs/benchmarks/m4_gate_d8083dbc_v3.txt"
BAT = "docs/benchmarks/m4_battery_d8083dbc_e1.txt"
BAT_FIRST = "docs/benchmarks/m4_battery_pb61.txt"
BAT_EARLY = "docs/benchmarks/m4_battery.md"
FRONT = "docs/benchmarks/m4_frontier_6abde44e.txt"
FRONT_MD = "docs/benchmarks/m4_frontier.md"
SEEDS = "docs/benchmarks/m4_seed_variance_d8083dbc_e1.txt"
REAL = "dataset/realism_report.md"
FLOOR = "docs/benchmarks/single_feature_baseline.md"
CLAIMS = "docs/research/claims_register.md"
SOURCING = "docs/research/sourcing_pass.md"
LIMITS = "docs/research/limitations.md"
M2REV = "docs/reviews/M2/milestone-review.md"
M3REV = "docs/reviews/M3/milestone-review.md"
NOTEBOOK = "docs/research/lab_notebook.md"
PARITY = "docs/ml/training_serving_parity.md"
HARDWARE = "docs/benchmarks/hardware.md"
ADR28 = "docs/adr/0028-one-pre-registered-non-burst-fraud-variant.md"
PROMPT = "docs/prompts/FraudShield_Master_Build_Prompt.md"


@dataclass(frozen=True)
class Src:
    """A value read from `path` by the first group of `pattern` (re.S and re.M)."""

    path: str
    pattern: str
    note: str = ""


@dataclass(frozen=True)
class Derived:
    """A value computed from other macros' audited values."""

    inputs: tuple[str, ...]
    compute: Callable[..., str]
    how: str


def row(label: str, col: int) -> str:
    """Pattern for the `col`-th whitespace-separated number after `label` on its line."""
    skip = r"\S+\s+" * (col - 1)
    return re.escape(label) + r"\s+" + skip + r"(\S+)"


WORDS: dict[str, str] = {"one": "1", "two": "2", "three": "3", "four": "4", "five": "5", "six": "6"}

SOURCES: dict[str, Src | Derived] = {
    # ---- dataset (claims register header, realism report at the tag = draw d8083dbc) ----
    "nRowsDrawA": Src(CLAIMS, r"— ([\d,]+) rows, seed\s+20260917"),
    "nSeed": Src(CLAIMS, r"rows, seed\s+(\d+), regenerated"),
    "nRowsDrawB": Src(REAL, r"\| Rows \| ([\d,]+) \|"),
    "nFraudRateAll": Src(REAL, r"overall ([\d.]+%) \(95% CI"),
    "nFraudRateTest": Src(REAL, r"test ([\d.]+%) \(95% CI"),
    "nLabelNoiseMissed": Src(REAL, r"missed ([\d.]+%), false"),
    "nLabelNoiseFalse": Src(REAL, r"false ([\d.]+%) of true fraud"),
    "nShortcutAUC": Src(REAL, r"shortcut detector \| pass \| yes \| AUC ([\d.]+)"),
    "nNovelRows": Src(REAL, r"novel sub-variant placement \| pass \| yes \| (\d+) rows"),
    "nTrainRows": Src(REAL, r"\| train \| ([\d,]+) \|"),
    "nValRows": Src(REAL, r"\| validation \| ([\d,]+) \|"),
    "nCalRows": Src(REAL, r"\| calibration \(last part of validation\) \| ([\d,]+) \|"),
    "nEmbargoRows": Src(REAL, r"\| embargo \(excluded\) \| ([\d,]+) \|"),
    "nTestRows": Src(REAL, r"\| test \| ([\d,]+) \|"),
    "nTestSpanDays": Src(REAL, r"\| test \| [\d,]+ \| [\d.]+% \| [\d.]+% \| ([\d.]+) \|"),
    "nEmbargoDays": Src(
        REAL, r"\| embargo \(excluded\) \| [\d,]+ \| [\d.]+% \| [\d.]+% \| ([\d.]+) \|"
    ),
    "nChannelMixDev": Src(REAL, r"channel mix \| pass \| no \| max deviation ([\d.]+) pp"),
    "nCountryMixDev": Src(REAL, r"country mix \| pass \| no \| max deviation ([\d.]+) pp"),
    # ---- sourcing pass and limitations ----
    "nParamsTotal": Src(SOURCING, r"\| \*\*Total\*\* \| \*\*\d+\*\* \| \*\*(\d+)\*\* \|"),
    "nParamsSourced": Src(SOURCING, r"\| SOURCED \| \d+ \| (\d+) \|"),
    "nParamsAssumed": Src(SOURCING, r"\| ASSUMED \| \d+ \| (\d+) \|"),
    "nParamsCalibrated": Src(SOURCING, r"\| CALIBRATED_TO_SRS_TARGET \| \d+ \| (\d+) \|"),
    "nSourceDocs": Src(
        SOURCING, r"\| (S-\d+) \| \[ISO 4217", "rows S-1 to S-8 of 'Documents read'; last is S-8"
    ),
    "nFraudParams": Src(LIMITS, r"Of the (\d+) in `fraud.yaml`"),
    "nFraudParamsChoice": Src(LIMITS, r"in `fraud.yaml`, (\d+) are modelling choices"),
    "nFraudParamsCalibrated": Src(LIMITS, r"and (four) are calibrated to SRS targets"),
    # ---- the ~170,000-row minimum (M2) ----
    "nMinRows": Src(M2REV, r"all eight fraud scenarios \| ~([\d,]+) rows"),
    "nDetectorMinRows": Src(M2REV, r"0\.6-strength leak \| ~([\d,]+) rows"),
    "nMinRowsA": Src(NOTEBOOK, r"four different scales: ([\d,]+) /"),
    "nMinRowsB": Src(NOTEBOOK, r"four different scales: [\d,]+ / ([\d,]+) /"),
    "nMinRowsC": Src(NOTEBOOK, r"four different scales: [\d,]+ / [\d,]+ / ([\d,]+) /"),
    "nMinRowsD": Src(NOTEBOOK, r"four different scales: [\d,]+ / [\d,]+ / [\d,]+ / ([\d,]+)\."),
    # ---- claims register (C-9, C-11, C-12, C-13, C-6) ----
    "nColumnMaxAUC": Src(
        CLAIMS, r"Strongest transaction column `merchant_category_code` \*\*([\d.]+)\*\*"
    ),
    "nEventDelayAUC": Src(CLAIMS, r"event-to-transaction delay \*\*([\d.]+)\*\*"),
    "nSiblingLeak": Src(CLAIMS, r"lowers `merchant_category_code` by ([\d.]+)"),
    "nSeedSdSizeSeries": Src(CLAIMS, r"seed-to-seed sd of ([\d.]+)"),
    "nSizeSeriesSpan": Src(CLAIMS, r"three-point series spanned ([\d.]+)"),
    "nEventDelayBefore": Src(CLAIMS, r"channel fell from \*\*([\d.]+) to [\d.]+\*\*"),
    "nEventDelayAfter": Src(CLAIMS, r"channel fell from \*\*[\d.]+ to ([\d.]+)\*\*"),
    "nEventDelayShare": Src(CLAIMS, r"about \*\*(\d+%) of the excess"),
    "nEventDelayBandLow": Src(CLAIMS, r"band ([\d.]+)-[\d.]+\)"),
    "nEventDelayBandHigh": Src(CLAIMS, r"band [\d.]+-([\d.]+)\)"),
    "nSpecVarianceClaim": Src(CLAIMS, r"\"Ensemble reduces variance (\d+%) vs single model\""),
    # ---- single-feature floor (M3) ----
    "nFeatures": Src(CLAIMS, r"\*\*Five of the (\d+) exceed the ceiling"),
    "nScoredRows": Src(FLOOR, r"corpus 200,000 transactions, ([\d,]+)\s+scored"),
    "nScoredFraud": Src(FLOOR, r"\*\*(\d+) confirmed fraud\*\*"),
    "nFloorM": Src(FLOOR, r"\| `velocity_ratio_1h_vs_30d` \| \*\*([\d.]+)\*\* \|"),
    "nFloorMci": Src(FLOOR, r"\| `velocity_ratio_1h_vs_30d` \| \*\*[\d.]+\*\* \| ±([\d.]+) \|"),
    "nCounterpartyNewM": Src(FLOOR, r"\| `counterparty_is_new_for_account` \| ([\d.]+) \|"),
    "nTxCountHourM": Src(FLOOR, r"\| `tx_count_1h` \| ([\d.]+) \|"),
    "nImpliedSpeedM": Src(FLOOR, r"\| `implied_speed_kmh` \| ([\d.]+) \|"),
    "nSecondsSinceM": Src(FLOOR, r"\| `seconds_since_last_tx` \| ([\d.]+) \|"),
    "nFeaturesAboveCeiling": Src(CLAIMS, r"\*\*(Five) of the 44 exceed the ceiling"),
    "nCeiling": Src(CLAIMS, r"separates the classes beyond AUC ([\d.]+)\""),
    "nCounterpartyNewBefore": Src(
        FLOOR, r"\| `counterparty_is_new_for_account` \| ([\d.]+) \| \*\*"
    ),
    "nParityRel": Src(PARITY, r"\|batch − online\| ≤ (1e-12) \+ 1e-12"),
    "nBatchSizes": Src(M3REV, r"Byte-identical across batch sizes ([\d, and]+17)"),
    # ---- M4 gate ----
    "nTestFraud": Src(GATE, r"test [\d,]+/(\d+)"),
    "nTrainUsed": Src(GATE, r"train ([\d,]+)/\d+"),
    "nGateTrainFraud": Src(GATE, r"train [\d,]+/(\d+)"),
    "nGateXGBRounds": Src(GATE, r"XGBoost (\d+) rounds"),
    "nGateLGBRounds": Src(GATE, r"LightGBM (\d+),"),
    "nGateSPW": Src(GATE, r"scale_pos_weight ([\d.]+)"),
    "nGateAUC": Src(GATE, r"ML-GATE-01  AUC-ROC\s+([\d.]+)"),
    "nGateAUCci": Src(GATE, r"ML-GATE-01  AUC-ROC\s+[\d.]+\s+(\[[\d., ]+\])"),
    "nGateRecallFPR": Src(GATE, r"ML-GATE-02  Recall at 1% FPR \(D-01\)\s+([\d.]+)"),
    "nGateRecallFPRci": Src(
        GATE, r"ML-GATE-02  Recall at 1% FPR \(D-01\)\s+[\d.]+\s+(\[[\d., ]+\])"
    ),
    "nGateRecallSix": Src(GATE, r"ML-GATE-03  Recall at 0\.60\s+([\d.]+)"),
    "nGateRecallSixci": Src(GATE, r"ML-GATE-03  Recall at 0\.60\s+[\d.]+\s+(\[[\d., ]+\])"),
    "nGateFone": Src(GATE, r"ML-GATE-04  F1 at 0\.60\s+([\d.]+)"),
    "nGateFoneci": Src(GATE, r"ML-GATE-04  F1 at 0\.60\s+[\d.]+\s+(\[[\d., ]+\])"),
    "nGateFPRBlock": Src(GATE, r"ML-GATE-05  FPR at 0\.85\s+([\d.]+)"),
    "nGateFNR": Src(GATE, r"ML-GATE-06  FNR at 0\.60\s+([\d.]+)"),
    "nGateMM": Src(GATE, r"ML-GATE-07  MOBILE_MONEY AUC-ROC\s+([\d.]+)"),
    "nGateUSSD": Src(GATE, r"ML-GATE-08  USSD AUC-ROC\s+([\d.]+)"),
    "nGateAgent": Src(GATE, r"ML-GATE-09  AGENT_BANKING AUC-ROC\s+([\d.]+)"),
    "nGateShapCov": Src(GATE, r"ML-GATE-10  SHAP coverage, HIGH and MEDIUM\s+([\d.]+)"),
    "nGateECE": Src(GATE, r"ML-GATE-11  ECE, 15 equal-mass bins\s+([\d.]+)"),
    "nGatePass": Src(GATE, r"(\d+ of \d+) gate metrics pass"),
    "nGatePrecFPR": Src(GATE, r"precision there ([\d.]+) \["),
    "nGatePrecCeiling": Src(GATE, r"and ([\d.]+) at exactly 1% FPR"),
    "nGatePrecSix": Src(GATE, r"at 0\.60: precision ([\d.]+),"),
    "nGateOnnx": Src(GATE, r"combined ([\d.e-]+) -> PASS"),
    "nGateFloor": Src(GATE, r"ML-GATE-01  model [\d.]+  single ([\d.]+)"),
    "nGateMargin": Src(
        GATE, r"ML-GATE-01  model [\d.]+  single [\d.]+ \(\w+\) margin ([+-][\d.]+)"
    ),
    "nGateTrivial": Src(GATE, r"ML-GATE-01 .*?trivial ([\d.]+) \(amount_log1p\)"),
    "nGateTrivialMargin": Src(
        GATE, r"ML-GATE-01 .*?trivial [\d.]+ \(amount_log1p\) margin ([+-][\d.]+)"
    ),
    "nGateRecallFPRFloor": Src(GATE, r"ML-GATE-02  model [\d.]+  single ([\d.]+)"),
    "nGateRecallFPRMargin": Src(
        GATE, r"ML-GATE-02  model [\d.]+  single [\d.]+ \(\w+\) margin ([+-][\d.]+)"
    ),
    "nGateMMFloor": Src(GATE, r"ML-GATE-07  model [\d.]+  single ([\d.]+)"),
    "nGateMMMargin": Src(
        GATE, r"ML-GATE-07  model [\d.]+  single [\d.]+ \(\w+\) margin ([+-][\d.]+)"
    ),
    "nGateUSSDFloor": Src(GATE, r"ML-GATE-08  model [\d.]+  single ([\d.]+)"),
    "nGateUSSDMargin": Src(
        GATE, r"ML-GATE-08  model [\d.]+  single [\d.]+ \(\w+\) margin ([+-][\d.]+)"
    ),
    "nGateAgentFloor": Src(GATE, r"ML-GATE-09  model [\d.]+  single ([\d.]+)"),
    "nGateAgentMargin": Src(
        GATE, r"ML-GATE-09  model [\d.]+  single [\d.]+ \(\w+\) margin ([+-][\d.]+)"
    ),
    "nGateSeedAUC": Src(GATE, r"SEEDS.*?ML-GATE-01  ([\d.]+ \+/- [\d.]+)"),
    "nGateSeedRecallSix": Src(GATE, r"SEEDS.*?ML-GATE-03  ([\d.]+ \+/- [\d.]+)"),
    "nBaseRules": Src(GATE, row("status-quo rule engine", 1)),
    "nBaseRulesR": Src(GATE, r"status-quo rule engine\s+[\d.]+ \+/-[\d.]+  R@1%FPR ([\d.]+)"),
    "nBaseRulesp": Src(GATE, r"status-quo rule engine.*?  p ([\d.e+-]+)"),
    "nBaseLR": Src(GATE, row("logistic regression", 1)),
    "nBaseLRR": Src(GATE, r"logistic regression\s+[\d.]+ \+/-[\d.]+  R@1%FPR ([\d.]+)"),
    "nBaseLRp": Src(GATE, r"logistic regression.*?  p ([\d.e+-]+)"),
    "nBaseRF": Src(GATE, row("random forest", 1)),
    "nBaseRFR": Src(GATE, r"random forest\s+[\d.]+ \+/-[\d.]+  R@1%FPR ([\d.]+)"),
    "nBaseRFp": Src(GATE, r"random forest.*?  p ([\d.e+-]+)"),
    "nBaseXGB": Src(GATE, row("XGBoost alone", 1)),
    "nBaseXGBR": Src(GATE, r"XGBoost alone\s+[\d.]+ \+/-[\d.]+  R@1%FPR ([\d.]+)"),
    "nBaseXGBp": Src(GATE, r"XGBoost alone.*?  p ([\d.e+-]+)"),
    "nBaseLGB": Src(GATE, row("LightGBM alone", 1)),
    "nBaseLGBR": Src(GATE, r"LightGBM alone\s+[\d.]+ \+/-[\d.]+  R@1%FPR ([\d.]+)"),
    "nBaseLGBp": Src(GATE, r"LightGBM alone.*?  p ([\d.e+-]+)"),
    "nBaseIF": Src(GATE, row("Isolation Forest alone", 1)),
    "nBaseIFR": Src(GATE, r"Isolation Forest alone\s+[\d.]+ \+/-[\d.]+  R@1%FPR ([\d.]+)"),
    "nBaseIFp": Src(GATE, r"Isolation Forest alone.*?  p ([\d.e+-]+)"),
    "nAblAgentD": Src(GATE, r"without agent features\s+\d+ feat  AUC [\d.]+ \(([+-][\d.]+),"),
    "nAblCorridorD": Src(
        GATE, r"without the corridor feature\s+\d+ feat  AUC [\d.]+ \(([+-][\d.]+),"
    ),
    "nAblRoundD": Src(GATE, r"without round-sum awareness\s+\d+ feat  AUC [\d.]+ \(([+-][\d.]+),"),
    "nAblMonthD": Src(GATE, r"without month-end awareness\s+\d+ feat  AUC [\d.]+ \(([+-][\d.]+),"),
    "nAblCardD": Src(GATE, r"card-style features only\s+\d+ feat  AUC [\d.]+ \(([+-][\d.]+),"),
    "nAblUSSDD": Src(
        GATE, r"without USSD-aware device handling\s+\d+ feat  AUC [\d.]+ \(([+-][\d.]+),"
    ),
    # ---- battery, E1-corrected (draw d8083dbc) ----
    "nBatAUC": Src(BAT, r"full model, 38 features\s+([\d.]+)"),
    "nBatECEAll": Src(BAT, r"expected calibration error, all rows\s+([\d.]+)"),
    "nBatDecisionRows": Src(BAT, r"rows / fraud\s+(\d+) /"),
    "nBatECEDecision": Src(BAT, r"DECISION REGION.*?expected calibration error\s+([\d.]+)"),
    "nBatAdditivity": Src(BAT, r"additivity error ([\d.e-]+)"),
    "nGroupCounterparty": Src(BAT, row("counterparty alone (4)", 1)),
    "nGroupVelocity": Src(BAT, row("velocity alone (8)", 1)),
    "nGroupTemporal": Src(BAT, row("temporal alone (6)", 1)),
    "nGroupGeographic": Src(BAT, row("geographic alone (5)", 1)),
    "nGroupDevice": Src(BAT, row("device_and_channel alone (5)", 1)),
    "nGroupSynth": Src(BAT, row("synthetic_identity alone (1)", 1)),
    "nGroupAmount": Src(BAT, row("amount_behaviour alone (4)", 1)),
    "nGroupAgent": Src(BAT, row("agent alone (2)", 1)),
    "nGroupProfile": Src(BAT, row("account_profile alone (2)", 1)),
    "nGroupCorridor": Src(BAT, row("corridor alone (1)", 1)),
    "nGroupWithoutCounterparty": Src(BAT, row("without counterparty (4)", 1)),
    "nGroupMaxDrop": Src(BAT, r"without counterparty \(4\)\s+[\d.]+ \+/-[\d.]+\s+-([\d.]+)"),
    "nGroupsAtMostSmall": Src(
        BAT,
        r"without temporal \(6\)\s+[\d.]+ \+/-[\d.]+\s+-([\d.]+)",
        "second-largest removal cost; device_and_channel ties at the same value",
    ),
    "nRevAUC": Src(BAT, row("reversal_scam_social_engineering", 1)),
    "nRevRecall": Src(BAT, r"reversal_scam_social_engineering: recall ([\d.]+%)"),
    "nRevCI": Src(BAT, r"reversal_scam_social_engineering: recall [\d.]+% (\[[\d.%, ]+\])"),
    "nRevCaught": Src(
        BAT, r"reversal_scam_social_engineering\s+[\d.]+ \+/-[\d.]+\s+[\d.]+ \[[\d., ]+\]\s+(\d+)"
    ),
    "nRevFraud": Src(
        BAT, r"reversal_scam_social_engineering: recall [\d.]+% \[[\d.%, ]+\] on (\d+) fraud rows"
    ),
    "nBaseVarRecall": Src(BAT, r"against\s+base at ([\d.]+%) "),
    "nBaseVarCI": Src(BAT, r"against\s+base at [\d.]+% (\[[\d.%, ]+\])"),
    "nBaseVarFraud": Src(BAT, r"against\s+base at [\d.]+% \[[\d.%, ]+\] on (\d+) rows"),
    "nNovelRecall": Src(BAT, r"novel_esim_delayed_drain: recall ([\d.]+%)"),
    "nNovelCI": Src(BAT, r"novel_esim_delayed_drain: recall [\d.]+% (\[[\d.%, ]+\])"),
    "nNovelCaught": Src(
        BAT, r"novel_esim_delayed_drain\s+[\d.]+ \+/-[\d.]+\s+[\d.]+ \[[\d., ]+\]\s+(\d+)"
    ),
    "nAdaptRecall": Src(BAT, r"adapted_below_threshold: recall ([\d.]+%)"),
    "nAdaptCI": Src(BAT, r"adapted_below_threshold: recall [\d.]+% (\[[\d.%, ]+\])"),
    "nAdaptFraud": Src(
        BAT, r"adapted_below_threshold: recall [\d.]+% \[[\d.%, ]+\] on (\d+) fraud rows"
    ),
    "nLocoKEin": Src(BAT, row("KE", 1)),
    "nLocoRWin": Src(BAT, row("RW", 1)),
    "nLocoTZin": Src(BAT, row("TZ", 1)),
    "nLocoUGin": Src(BAT, row("UG", 1)),
    "nLocoCDin": Src(BAT, row("CD", 1)),
    "nLocoKEf": Src(BAT, r"\n  KE\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nLocoRWf": Src(BAT, r"\n  RW\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nLocoTZf": Src(BAT, r"\n  TZ\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nLocoUGf": Src(BAT, r"\n  UG\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nLocoCDf": Src(BAT, r"\n  CD\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nLocoKEout": Src(BAT, row("KE unseen in training", 1)),
    "nLocoRWout": Src(BAT, row("RW unseen in training", 1)),
    "nLocoTZout": Src(BAT, row("TZ unseen in training", 1)),
    "nLocoUGout": Src(BAT, row("UG unseen in training", 1)),
    "nLocoCDout": Src(BAT, row("CD unseen in training", 1)),
    "nLocoMaxLoss": Derived(
        (
            "nLocoKEin",
            "nLocoKEout",
            "nLocoRWin",
            "nLocoRWout",
            "nLocoTZin",
            "nLocoTZout",
            "nLocoUGin",
            "nLocoUGout",
            "nLocoCDin",
            "nLocoCDout",
        ),
        lambda *v: f"{max(Decimal(v[i]) - Decimal(v[i + 1]) for i in range(0, 10, 2)):.3f}",
        "max over countries of in-sample AUC minus unseen AUC",
    ),
    # ---- first reversal-scam run (random-fold encoding) ----
    "nRevRecallFirst": Src(BAT_FIRST, r"reversal_scam_social_engineering: recall ([\d.]+%)"),
    "nRevCIFirst": Src(
        BAT_FIRST, r"reversal_scam_social_engineering: recall [\d.]+% (\[[\d.%, ]+\])"
    ),
    "nRevCaughtFirst": Src(
        BAT_FIRST,
        r"reversal_scam_social_engineering\s+[\d.]+ \+/-[\d.]+\s+[\d.]+ \[[\d., ]+\]\s+(\d+)",
    ),
    "nRevAUCFirst": Src(BAT_FIRST, row("reversal_scam_social_engineering", 1)),
    "nBaseVarRecallFirst": Src(BAT_FIRST, r"against\s+base at ([\d.]+%) "),
    "nBaseVarCIFirst": Src(BAT_FIRST, r"against\s+base at [\d.]+% (\[[\d.%, ]+\])"),
    # ---- earlier draw 6abde44e (m4_battery.md) ----
    "nEarlyGroupMin": Src(BAT_EARLY, r"\| geographic \(5\) \| \*\*([\d.]+)\*\* \|"),
    "nEarlyLocoMaxLoss": Src(BAT_EARLY, r"\| TZ \| [\d.]+ \| [\d.]+ \| −([\d.]+) \|"),
    "nEarlyNovelRecall": Src(
        BAT_EARLY,
        r"`novel_esim_delayed_drain` \(never in training\) \| [^|]+ \| \*\*([\d.]+)\*\*",
        "printed as a proportion; the paper states it as a percentage",
    ),
    "nEarlyBaseRecall": Src(
        BAT_EARLY,
        r"\| `base` \| [^|]+ \| ([\d.]+) \|",
        "printed as a proportion; the paper states it as a percentage",
    ),
    # ---- seed variance ----
    "nSeedSdXGB": Src(SEEDS, r"xgboost\s+[\d.]+\s+([\d.]+)\n"),
    "nSeedSdLGB": Src(SEEDS, r"lightgbm\s+[\d.]+\s+([\d.]+)\n"),
    "nSeedSdEnsemble": Src(SEEDS, r"ensemble\s+[\d.]+\s+([\d.]+)\n"),
    # ---- frontier (draw 6abde44e) ----
    "nFrRequests": Src(FRONT, r"over (\d+) timed requests per configuration"),
    "nFrontNegative": Src(FRONT, r"(\d+ of \d+) configurations show a NEGATIVE"),
    "nFrontAUCDelta": Src(FRONT, r"From 50 trees at depth 3 to 800 at depth 8: AUC ([+-][\d.]+)"),
    "nFrontRatio": Src(FRONT_MD, r"grows \*\*(\d+)×\*\*"),
    "nFrontShapSmall": Src(FRONT, r"goes ([\d.]+) -> [\d.]+ ms"),
    "nFrontShapLarge": Src(FRONT, r"goes [\d.]+ -> ([\d.]+) ms"),
    "nFrontPredSmall": Src(FRONT_MD, r"from ([\d.]+) to [\d.]+ ms, so essentially"),
    "nFrontPredLarge": Src(FRONT_MD, r"from [\d.]+ to ([\d.]+) ms, so essentially"),
    **{
        f"nFr{kind}{letter}": Src(FRONT, rf"\n\s+{trees}\s+{depth}\s+" + pattern)
        for letter, trees, depth in (
            ("A", 50, 3),
            ("B", 100, 4),
            ("C", 200, 5),
            ("D", 400, 6),
            ("E", 800, 8),
        )
        for kind, pattern in (
            ("Auc", r"([\d.]+) \+/-"),
            ("Pred", r"[\d.]+ \+/-[\d.]+\s+([\d.]+)"),
            ("Shap", r"[\d.]+ \+/-[\d.]+\s+[\d.]+\s+([\d.]+)"),
            ("Cost", r"[\d.]+ \+/-[\d.]+\s+[\d.]+\s+[\d.]+\s+([\d.]+)ms"),
        )
    },
    # ---- predictions ----
    "nPredEarly": Src(NOTEBOOK, r"\*\*One partially right out of (four)\.\*\*"),
    "nPredEarlyRight": Src(NOTEBOOK, r"\*\*(One) partially right out of four\.\*\*"),
    "nPredTotal": Derived(
        ("nPredEarly", "_P5", "_P6"),
        lambda early, p5, p6: (
            "six" if WORDS.get(early.lower(), early) == "4" and p5 and p6 else "?"
        ),
        "the register's four plus the two later pre-registrations (PB-56 lead tail; ADR 0028 "
        "reversal scam), each located in the notebook",
    ),
    "_P5": Src(NOTEBOOK, r"(Prediction, recorded before the draw exists)"),
    "_P6": Src(
        NOTEBOOK, r"(Pre-registration: one non-burst fraud variant, before the draw exists)"
    ),
    "nRevPredLow": Src(
        NOTEBOOK,
        r"threshold used for the novel-variant comparison, \*\*between\s+([\d.]+) and [\d.]+\*\*",
    ),
    "nRevPredHigh": Src(
        NOTEBOOK,
        r"threshold used for the novel-variant comparison, \*\*between\s+[\d.]+ and ([\d.]+)\*\*",
    ),
    "nRevPredFloor": Src(NOTEBOOK, r"Recall below roughly ([\d.]+) would say"),
    # ---- caption counts ----
    "nBatTestRows": Src(BAT, r"full model, 38 features\s+\S+ \S+\s+\S+\s+(\d+)"),
    "nBatTestFraud": Src(BAT, r"full model, 38 features\s+\S+ \S+\s+\S+\s+\d+\s+(\d+)"),
    "nFrHeldOutRows": Src(FRONT_MD, r"draw `6abde44e`, ([\d,]+) held-out"),
    "nFrHeldOutFraud": Src(FRONT_MD, r"held-out\s+rows with (\d+) fraud"),
    "nFloorDrawRows": Src(FLOOR, r"Commit `2c80ef6`, dataset of ([\d,]+) rows"),
    # ---- dataset at a glance ----
    "nFingerprintA": Src(CLAIMS, r"fingerprint is\s+`([0-9a-f]{64})`"),
    "nFingerprintB": Src(REAL, r"Dataset fingerprint SHA-256 \| `([0-9a-f]{64})`"),
    "nPeriodStart": Src("docs/ml/datasheet.md", r"The simulated period is (\d{4}-\d\d-\d\d) to"),
    "nPeriodEnd": Src(
        "docs/ml/datasheet.md", r"The simulated period is \d{4}-\d\d-\d\d to (\d{4}-\d\d-\d\d)"
    ),
    "nMonths": Src(REAL, r"(\d+) of \d+ monthly intervals cover their target"),
    "nTestStart": Src(REAL, r"The temporal hold-out test set starts ([\d-]+ [\d:]+ UTC)"),
    "nRevRows": Src(
        "docs/ml/datasheet.md",
        r"adding the (\d+) test-period rows of the pre-registered reversal-scam variant",
    ),
    "_RevIsMule": Src(
        "dataset/src/fraudshield_dataset/generator/fraud.py",
        r"(\"mule_account\"), customer, month, rng, rows=1, variant=REVERSAL_SCAM_VARIANT",
    ),
    "nTrainDays": Src(REAL, r"\| train \| [\d,]+ \| [\d.]+% \| [\d.]+% \| ([\d.]+) \|"),
    "nValDays": Src(REAL, r"\| validation \| [\d,]+ \| [\d.]+% \| [\d.]+% \| ([\d.]+) \|"),
    "nCalDays": Src(
        REAL,
        r"\| calibration \(last part of validation\) \| [\d,]+ \| "
        r"[\d.]+% \| [\d.]+% \| ([\d.]+) \|",
    ),
    "nProvSourced": Src("dataset/params_provenance.md", r"\| \*\*all\*\* \| \*\*(\d+)\*\* \|"),
    "nProvAssumed": Src(
        "dataset/params_provenance.md", r"\| \*\*all\*\* \| \*\*\d+\*\* \| \*\*(\d+)\*\* \|"
    ),
    "nProvCalibrated": Src(
        "dataset/params_provenance.md",
        r"\| \*\*all\*\* \| \*\*\d+\*\* \| \*\*\d+\*\* \| \*\*(\d+)\*\* \|",
    ),
    "nProvTotal": Src(
        "dataset/params_provenance.md", r"\| \*\*all\*\* \| (?:\*\*\d+\*\* \| ){3}\*\*(\d+)\*\* \|"
    ),
    "nProvFraudTotal": Src(
        "dataset/params_provenance.md", r"^\| fraud \| 0 \| \d+ \| \d+ \| (\d+) \|"
    ),
    "nProvFraudAssumed": Src("dataset/params_provenance.md", r"^\| fraud \| 0 \| (\d+) \|"),
    "nProvFraudCalibrated": Src(
        "dataset/params_provenance.md", r"^\| fraud \| 0 \| \d+ \| (\d+) \|"
    ),
    "nMixMM": Src(REAL, r"^\| MOBILE_MONEY \| [\d.]+% \| ([\d.]+%) \|"),
    "nMixTargetMM": Src(REAL, r"^\| MOBILE_MONEY \| ([\d.]+%) \| [\d.]+% \|"),
    "nChRowsMM": Src(BAT, r"\n  MOBILE_MONEY\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nChFraudMM": Src(BAT, r"\n  MOBILE_MONEY\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nChAucMM": Src(BAT, r"\n  MOBILE_MONEY\s+([\d.]+) \+/-"),
    "nMixUSSD": Src(REAL, r"^\| USSD \| [\d.]+% \| ([\d.]+%) \|"),
    "nMixTargetUSSD": Src(REAL, r"^\| USSD \| ([\d.]+%) \| [\d.]+% \|"),
    "nChRowsUSSD": Src(BAT, r"\n  USSD\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nChFraudUSSD": Src(BAT, r"\n  USSD\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nChAucUSSD": Src(BAT, r"\n  USSD\s+([\d.]+) \+/-"),
    "nMixAgent": Src(REAL, r"^\| AGENT_BANKING \| [\d.]+% \| ([\d.]+%) \|"),
    "nMixTargetAgent": Src(REAL, r"^\| AGENT_BANKING \| ([\d.]+%) \| [\d.]+% \|"),
    "nChRowsAgent": Src(BAT, r"\n  AGENT_BANKING\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nChFraudAgent": Src(
        BAT, r"\n  AGENT_BANKING\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"
    ),
    "nChAucAgent": Src(BAT, r"\n  AGENT_BANKING\s+([\d.]+) \+/-"),
    "nMixCard": Src(REAL, r"^\| CARD \| [\d.]+% \| ([\d.]+%) \|"),
    "nMixTargetCard": Src(REAL, r"^\| CARD \| ([\d.]+%) \| [\d.]+% \|"),
    "nChRowsCard": Src(BAT, r"\n  CARD\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nChFraudCard": Src(BAT, r"\n  CARD\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nChAucCard": Src(BAT, r"\n  CARD\s+([\d.]+) \+/-"),
    "nMixOnline": Src(REAL, r"^\| ONLINE \| [\d.]+% \| ([\d.]+%) \|"),
    "nMixTargetOnline": Src(REAL, r"^\| ONLINE \| ([\d.]+%) \| [\d.]+% \|"),
    "nChRowsOnline": Src(BAT, r"\n  ONLINE\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nChFraudOnline": Src(BAT, r"\n  ONLINE\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nChAucOnline": Src(BAT, r"\n  ONLINE\s+([\d.]+) \+/-"),
    "nMixBank": Src(REAL, r"^\| BANK_TRANSFER \| [\d.]+% \| ([\d.]+%) \|"),
    "nMixTargetBank": Src(REAL, r"^\| BANK_TRANSFER \| ([\d.]+%) \| [\d.]+% \|"),
    "nChRowsBank": Src(BAT, r"\n  BANK_TRANSFER\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nChFraudBank": Src(BAT, r"\n  BANK_TRANSFER\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+\d+\s+(\d+)"),
    "nChAucBank": Src(BAT, r"\n  BANK_TRANSFER\s+([\d.]+) \+/-"),
    "nCMixRW": Src(REAL, r"^\| RW \| [\d.]+% \| ([\d.]+%) \|"),
    "nCMixTargetRW": Src(REAL, r"^\| RW \| ([\d.]+%) \| [\d.]+% \|"),
    "nCoRowsRW": Src(BAT, r"\n  RW\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nCMixKE": Src(REAL, r"^\| KE \| [\d.]+% \| ([\d.]+%) \|"),
    "nCMixTargetKE": Src(REAL, r"^\| KE \| ([\d.]+%) \| [\d.]+% \|"),
    "nCoRowsKE": Src(BAT, r"\n  KE\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nCMixTZ": Src(REAL, r"^\| TZ \| [\d.]+% \| ([\d.]+%) \|"),
    "nCMixTargetTZ": Src(REAL, r"^\| TZ \| ([\d.]+%) \| [\d.]+% \|"),
    "nCoRowsTZ": Src(BAT, r"\n  TZ\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nCMixUG": Src(REAL, r"^\| UG \| [\d.]+% \| ([\d.]+%) \|"),
    "nCMixTargetUG": Src(REAL, r"^\| UG \| ([\d.]+%) \| [\d.]+% \|"),
    "nCoRowsUG": Src(BAT, r"\n  UG\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nCMixCD": Src(REAL, r"^\| CD \| [\d.]+% \| ([\d.]+%) \|"),
    "nCMixTargetCD": Src(REAL, r"^\| CD \| ([\d.]+%) \| [\d.]+% \|"),
    "nCoRowsCD": Src(BAT, r"\n  CD\s+[\d.]+ \+/-[\d.]+\s+\S+\s+[\d.]+\s+(\d+)"),
    "nScenATO": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["account_takeover"]),
        "`account_takeover` column of the scenario table, summed over 24 months",
    ),
    "nScenAgent": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["agent_fraud"]),
        "`agent_fraud` column of the scenario table, summed over 24 months",
    ),
    "nScenCNP": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["card_not_present"]),
        "`card_not_present` column of the scenario table, summed over 24 months",
    ),
    "nScenMerchant": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["merchant_fraud"]),
        "`merchant_fraud` column of the scenario table, summed over 24 months",
    ),
    "nScenMule": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["mule_account"]),
        "`mule_account` column of the scenario table, summed over 24 months",
    ),
    "nScenSimSwap": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["sim_swap"]),
        "`sim_swap` column of the scenario table, summed over 24 months",
    ),
    "nScenSynth": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["synthetic_identity"]),
        "`synthetic_identity` column of the scenario table, summed over 24 months",
    ),
    "nScenVelocity": Derived(
        ("_ScenTable",),
        lambda t: str(scenario_totals(t)["velocity"]),
        "`velocity` column of the scenario table, summed over 24 months",
    ),
    "nScenTotal": Derived(
        ("_ScenTable",),
        lambda t: str(sum(scenario_totals(t).values())),
        "sum of all eight scenario columns (equals the sum of the monthly Fraud column)",
    ),
    "_ScenTable": Src(REAL, r"(## Fraud scenarios over time.*?)## Parameter provenance"),
    "nColumnMaxAUCB": Src(
        REAL, r"single-feature AUC \| pass \| yes \| max ([\d.]+) \(merchant_category_code\)"
    ),
    "nEventDelayAUCB": Src(REAL, r"event delay \(reported\) \| pass \| no \| AUC ([\d.]+)"),
    "nIntervalLevel": Src(GATE, r"value  (\d+%) CI"),
    "nTrainableFeatures": Src(BAT, r"full model, (\d+) features"),
    # ---- hardware ----
    "nHwCPU": Src(HARDWARE, r"\| CPU \| (Intel Core i5-6200U @ 2\.30 GHz, 2 cores / 4 threads) \|"),
    "nHwMem": Src(HARDWARE, r"\| Memory \| ([\d.]+ GiB) total"),
    # ---- specification values (SOURCED, build prompt) ----
    "nWeightXGB": Src(PROMPT, r"Combine raw probabilities as ([\d.]+)·p_xgb"),
    "nWeightLGB": Src(PROMPT, r"·p_xgb \+ ([\d.]+)·p_lgb"),
    "nFlagThreshold": Src(PROMPT, r"\*\*flag threshold\*\* is ([\d.]+)"),
    "nBlockThreshold": Src(PROMPT, r"\*\*block threshold\*\* is (\d\.\d+)"),
    "nFPRBudget": Src(PROMPT, r"\*\*Recall \(TPR\) at (1%) FPR ≥ 0\.720\*\*"),
    "nTargetRecall": Src(PROMPT, r"Recall ≥ ([\d.]+), F1 ≥"),
    "nTargetFNR": Src(PROMPT, r"FNR < ([\d.]+%) are measured at the flag threshold"),
    "nResamples": Src(PROMPT, r"stratified bootstrap \(([\d,]+) resamples\)"),
    "nSeeds": Src(PROMPT, r"mean ± SD over (\d+) seeds", "the paper writes the count as a word"),
}


def scenario_totals(table: str) -> dict[str, int]:
    """Column totals of the realism report's 'Fraud scenarios over time' table."""
    header = re.search(r"^\| Month \| Rows \| (.*) \|$", table, re.M)
    if header is None:
        raise ValueError("scenario table header not found in the realism report")
    names = header.group(1).split(" | ")
    totals = dict.fromkeys(names, 0)
    for values in re.findall(r"^\| \d{4}-\d\d \| [\d,]+ \| (.*) \|$", table, re.M):
        for name, value in zip(names, values.split(" | "), strict=True):
            totals[name] += int(value)
    return totals


def normalise(value: str) -> str:
    """One printed form for a macro value and an evidence string, keeping digits exactly."""
    v = value.replace("{,}", ",").replace("\\%", "%").replace("~", " ").strip()
    v = re.sub(r"\\ensuremath\{(.*)\}$", r"\1", v)
    v = re.sub(r"\s*\\pm\s*", " +/- ", v)
    v = v.replace("−", "-")
    m = re.fullmatch(r"([\d.]+)\s*\\times\s*10\^\{(-?\d+)\}", v)
    if m:
        v = f"{m.group(1)}e{int(m.group(2))}"
    m = re.fullmatch(r"([\d.]+)e([+-]?\d+)", v)
    if m:
        v = f"{m.group(1)}e{int(m.group(2))}"
    v = re.sub(r"^10\^\{(-?\d+)\}$", r"1e\1", v)
    v = v.replace(" at 2.30 GHz", " @ 2.30 GHz")
    v = re.sub(r"(?<=\d),(?=\d{3}\b)", "", v)  # thousands separators are layout, not value
    return WORDS.get(v.lower(), v.lower() if v.lower() in WORDS.values() else v)


def git_show(ref: str, path: str) -> str:
    return subprocess.run(  # noqa: S603 - fixed git command, paths from this file
        ["git", "show", f"{ref}:{path}"],  # noqa: S607
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=True,
    ).stdout


def evidence_commit(text: str) -> str:
    m = re.search(r"^# evidence-commit: ([0-9a-f]{7})", text, re.M)
    return m.group(1) if m else ""


def macros() -> dict[str, tuple[str, str]]:
    text = (HERE / "numbers.tex").read_text()
    found = re.findall(
        r"\\newcommand\{\\(n[A-Z]\w*)\}\{\\(meas|prov|srcd)\{(.+?)\}\}(?=\s|%|\\newcommand|$)",
        text,
        flags=re.M,
    )
    return {name: (kind, value) for name, kind, value in found}


def write_report(rows: list[tuple[str, str, str, str, str, str]], ref: str, ref_sha: str) -> None:
    lines = [
        "# Number audit",
        "",
        f"Every macro in `numbers.tex` checked against its evidence file at `{ref}` "
        f"(`{ref_sha}`), read with `git show`, by `audit_numbers.py`. Values are compared as "
        "printed: rounding, sign and interval bounds included. Regenerate with "
        "`python3 docs/research/paper/audit_numbers.py`; do not edit by hand.",
        "",
        f"**{len(rows)} macros, {sum(r[5] == 'MATCH' for r in rows)} MATCH, "
        f"{sum(r[5] != 'MATCH' for r in rows)} not matching.**",
        "",
        "| Macro | Value in paper | Value in evidence | File | Note | Result |",
        "|---|---|---|---|---|---|",
    ]
    lines += [f"| `\\{r[0]}` | `{r[1]}` | `{r[2]}` | {r[3]} | {r[4]} | {r[5]} |" for r in rows]
    (HERE / "number_audit.md").write_text("\n".join(lines) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", default="m4-complete")
    args = parser.parse_args()
    ref_sha = subprocess.run(  # noqa: S603 - fixed git command
        ["git", "rev-parse", "--short", f"{args.ref}^{{commit}}"],  # noqa: S607
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.strip()  # fmt: skip
    cache: dict[str, str] = {}
    found_values: dict[str, str] = {}
    rows: list[tuple[str, str, str, str, str, str]] = []
    problems = 0
    defined = macros()
    helpers = {k: v for k, v in SOURCES.items() if k.startswith("_") and isinstance(v, Src)}
    for helper, spec in helpers.items():
        text = cache.setdefault(spec.path, git_show(args.ref, spec.path))
        hit = re.search(spec.pattern, text, re.S | re.M)
        found_values[helper] = hit.group(1) if hit else ""
    for name, (_, value) in defined.items():
        source = SOURCES.get(name)
        if source is None:
            rows.append((name, value, "—", "no source recorded", "", "MISSING SOURCE"))
            problems += 1
            continue
        if isinstance(source, Derived):
            continue
        text = cache.setdefault(source.path, git_show(args.ref, source.path))
        m = re.search(source.pattern, text, re.S | re.M)
        if not m:
            rows.append((name, value, "—", source.path, source.note, "PATTERN NOT FOUND"))
            problems += 1
            continue
        got = m.group(1)
        if source.path in (BAT_EARLY,) and name in ("nEarlyNovelRecall", "nEarlyBaseRecall"):
            got_cmp = f"{Decimal(got) * 100:.1f}%".replace("100.0%", "100%")
        elif name == "nSourceDocs":
            got_cmp = got.removeprefix("S-")
        else:
            got_cmp = got
        found_values[name] = got
        where = f"`{source.path}`" + (
            f" (evidence commit `{c}`)" if (c := evidence_commit(text)) else ""
        )
        ok = normalise(value) == normalise(got_cmp)
        problems += not ok
        rows.append((name, value, got, where, source.note, "MATCH" if ok else "MISMATCH"))
    for name, source in SOURCES.items():
        if isinstance(source, Derived) and name in defined:
            got = source.compute(*(found_values[i] for i in source.inputs))
            value = defined[name][1]
            ok = normalise(value) == normalise(got)
            problems += not ok
            rows.append((name, value, got, "derived", source.how, "MATCH" if ok else "MISMATCH"))
    rows.sort(key=lambda r: list(defined).index(r[0]))
    write_report(rows, args.ref, ref_sha)
    print(f"number-audit: {len(rows)} macros at {args.ref} ({ref_sha}); {problems} problem(s)")
    for r in rows:
        if r[5] != "MATCH":
            print(f"  {r[5]}: \\{r[0]} paper={r[1]!r} evidence={r[2]!r} ({r[3]})", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
