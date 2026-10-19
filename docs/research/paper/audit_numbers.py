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
from functools import partial
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
GATE_JSON = "docs/benchmarks/m4_gate_d8083dbc_v3/metrics.json"
ROC = "docs/benchmarks/m4_gate_d8083dbc_v3/figures/roc.svg"
REQUIREMENTS = "docs/traceability/requirements.yaml"
#: Evidence produced for the paper itself, after the tag; read at the commit it was added in.
REV_LABELS = "docs/research/paper/evidence/reversal_labels_d8083dbc.txt"
REV_LABELS_REF = "3559f8c"
#: PB-67's learning curve, merged to main after m4-complete; read at the commit that last
#: touched it.
LC = "docs/benchmarks/m4_learning_curve_{}_d8083dbc.txt"
LC_CACHE = "docs/benchmarks/m4_learning_curve_cache_{}_d8083dbc.txt"
LC_REF = "3dacca6"
GATE_CACHE = "docs/benchmarks/m4_evaluate_gate_d8083dbc.txt"
#: The notebook on main, which carries PB-67's pre-registration (no tag carries it yet).
NOTEBOOK_MAIN_REF = "488c5b4"
ADR55 = "docs/adr/0055-five-features-have-no-source-data-on-this-benchmark.md"
ADR55_REF = "m6-complete"
REGISTRY = "ml/src/fraudshield_ml/features/registry.py"
FRAUD_YAML = "dataset/generator/params/fraud.yaml"
FRAUD_PY = "dataset/src/fraudshield_dataset/generator/fraud.py"
FINGERPRINT = "dataset/src/fraudshield_dataset/fingerprint.py"
TEST_REGISTRY = "ml/tests/features/test_registry.py"
FLOOR_RAW = "docs/benchmarks/m3_single_feature_auc_2c80ef6.txt"
REF_AUDIT = "docs/research/paper/reference_audit.md"
DEFECTS = "docs/srs/defect_register.md"
#: The commit on this branch that last changed the reference audit.
REF_AUDIT_REF = "ee97bbd"
#: The gate line for recall at the 1% FPR budget, up to its value.
BUDGET = r"ML-GATE-02  Recall at 1% FPR \(D-01\)\s+"


@dataclass(frozen=True)
class Src:
    """A value read from `path` by the first group of `pattern` (re.S and re.M)."""

    path: str
    pattern: str
    note: str = ""
    #: A git ref to read this file at instead of the audit's --ref (paper-local evidence).
    ref: str = ""


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


WORDS: dict[str, str] = {
    "one": "1",
    "two": "2",
    "three": "3",
    "four": "4",
    "five": "5",
    "six": "6",
    "seven": "7",
    "eight": "8",
    "nine": "9",
    "ten": "10",
    "fourteen": "14",
}


def _word(ratio: float) -> str:
    """A whole number as the word the paper prints."""
    names = {v: k for k, v in WORDS.items()}
    return names.get(str(int(ratio)), "?") if ratio == int(ratio) else f"not whole: {ratio}"


def _front_cell(col: int, row: int, table: str) -> str:
    """Trees (col 0) or depth (col 1) of the frontier table's `row`-th configuration."""
    return re.findall(r"^\s+(\d+)\s+(\d+)\s+0\.", table, re.M)[row][col]


def _pct(part: str, whole: str) -> str:
    """`part` over `whole` as a percentage to two places."""
    ratio = Decimal(part.replace(",", "")) / Decimal(whole.replace(",", "")) * 100
    return f"{ratio:.2f}%"


def _pct_key(value: str) -> Decimal:
    return Decimal(value.rstrip("%"))


def _rows(block: str, pattern: str) -> str:
    """The number of table rows in `block` matching `pattern`, as the word the paper prints."""
    return _word(len(re.findall(pattern, block, re.M)))


def _above(threshold: str, *values: str) -> str:
    """How many `values` exceed `threshold`, as a word."""
    return _word(sum(Decimal(v) > Decimal(threshold) for v in values))


def _count(rate: str, rows: str, fraud: str) -> str:
    legitimate = int(rows.replace(",", "")) - int(fraud.replace(",", ""))
    exact = Decimal(rate) * legitimate
    whole = exact.to_integral_value()
    return str(whole) if abs(exact - whole) < Decimal("1e-6") else f"not whole: {exact}"


#: The ROC figure's plot area: origin at (48, 312) in SVG pixels, 264 pixels to 1.0 on each axis.
ROC_ORIGIN_X, ROC_ORIGIN_Y, ROC_SPAN = 48.0, 312.0, 264.0


def _roc(points: str) -> list[tuple[float, float]]:
    """(FPR, recall) at each vertex of the plotted curve."""
    pairs = (vertex.split(",") for vertex in points.split())
    return [
        ((float(x) - ROC_ORIGIN_X) / ROC_SPAN, (ROC_ORIGIN_Y - float(y)) / ROC_SPAN)
        for x, y in pairs
    ]


def _crossing(points: str, target: str) -> tuple[str, str, str, str]:
    """The plotted vertices either side of the first one whose recall reaches `target`."""
    curve = _roc(points)
    goal = float(target)
    first = next(i for i, (_, recall) in enumerate(curve) if recall >= goal)
    (low_fpr, low_recall), (high_fpr, high_recall) = curve[first - 1], curve[first]
    return (
        f"{low_fpr * 100:.1f}%",
        f"{low_recall:.3f}",
        f"{high_fpr * 100:.1f}%",
        f"{high_recall:.3f}",
    )


OTHER_COUNTRIES = (
    "nLocoKEf",
    "nCoRowsKE",
    "nLocoRWf",
    "nCoRowsRW",
    "nLocoTZf",
    "nCoRowsTZ",
    "nLocoUGf",
    "nCoRowsUG",
)

GROUPS_ABOVE = (
    "nCeiling",
    "nGroupCounterparty",
    "nGroupVelocity",
    "nGroupTemporal",
    "nGroupGeographic",
    "nGroupDevice",
    "nGroupSynth",
    "nGroupAmount",
    "nGroupAgent",
    "nGroupProfile",
    "nGroupCorridor",
)

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
    "nGatePassed": Src(GATE, r"(\d+) of \d+ gate metrics pass"),
    "nGateMeasured": Src(GATE, r"\d+ of (\d+) gate metrics pass"),
    "nGateTotal": Src(REQUIREMENTS, r".*- id: ML-GATE-(\d+)\n", "the last gate id in the file"),
    "nGateFPRFlag": Src(GATE, r"at 0\.60: precision [\d.]+, FPR ([\d.]+)"),
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
    "nAblCardAUC": Src(GATE, r"card-style features only\s+\d+ feat  AUC ([\d.]+)"),
    "nAblCardp": Src(GATE, r"card-style features only\s+.*?, p ([\d.e-]+)\)"),
    "nAblCardR": Src(GATE, r"card-style features only\s+.*?R@1%FPR ([\d.]+)"),
    "_GateFPRBlockExact": Src(GATE_JSON, r'"id": "ML-GATE-05",.*?"value": ([\d.eE-]+)'),
    "_RocPoints": Src(ROC, r'<polyline points="(48\.0,312\.0 48\.0,[^"]*)"'),
    "nRevScoredUnlabelled": Src(
        REV_LABELS,
        r"reversal_scam_social_engineering\n(?:[^\n]*\n){4}[^\n]*missed-fraud noise\): (\d+)",
        ref=REV_LABELS_REF,
    ),
    "nRevTrueFraud": Src(
        REV_LABELS,
        r"reversal_scam_social_engineering\n(?:[^\n]*\n){2}\s+fraud by true label: (\d+)",
        ref=REV_LABELS_REF,
    ),
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
        ("nPredEarly", "_P5", "_P6", "_P7"),
        lambda early, p5, p6, p7: (
            "seven" if WORDS.get(early.lower(), early) == "4" and p5 and p6 and p7 else "?"
        ),
        "the register's four plus the three later pre-registrations (PB-56 lead tail; ADR 0028 "
        "reversal scam; PB-67 learning curve), each located in the notebook",
    ),
    "_P7": Src(
        NOTEBOOK,
        r"(Pre-registration: the PB-67 learning curve, before any size is run)",
        ref=NOTEBOOK_MAIN_REF,
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
    # ---- values computed from the gate run's metrics file and ROC figure (m4-complete) ----
    "nTestLegit": Derived(
        ("nTestRows", "nTestFraud"),
        lambda rows, fraud: str(int(rows.replace(",", "")) - int(fraud.replace(",", ""))),
        "test rows minus test fraud",
    ),
    "nGateFPRBlockCount": Derived(
        ("_GateFPRBlockExact", "nTestRows", "nTestFraud"),
        _count,
        "ML-GATE-05's unrounded FPR (metrics.json) times the legitimate test rows; must be whole",
    ),
    "nGateFPRBlockPct": Derived(
        ("_GateFPRBlockExact",),
        lambda rate: f"{Decimal(rate) * 100:.2f}%",
        "ML-GATE-05's unrounded FPR (metrics.json) as a percentage",
    ),
    "nRocPoints": Derived(
        ("_RocPoints",), lambda points: str(len(_roc(points))), "vertices of the plotted ROC curve"
    ),
    "nRocLowFPR": Derived(
        ("_RocPoints", "nTargetRecall"),
        lambda points, target: _crossing(points, target)[0],
        "FPR of the last plotted ROC vertex below the recall target (axes 264 px from x=48, y=312)",
    ),
    "nRocLowRecall": Derived(
        ("_RocPoints", "nTargetRecall"),
        lambda points, target: _crossing(points, target)[1],
        "recall of that vertex",
    ),
    "nRocHighFPR": Derived(
        ("_RocPoints", "nTargetRecall"),
        lambda points, target: _crossing(points, target)[2],
        "FPR of the first plotted ROC vertex at or above the recall target",
    ),
    "nRocHighRecall": Derived(
        ("_RocPoints", "nTargetRecall"),
        lambda points, target: _crossing(points, target)[3],
        "recall of that vertex",
    ),
    # ---- PB-67 learning curve (read at LC_REF) and row selection ----
    **{
        f"nLc{kind}{point}": Src(LC.format(size), pattern, ref=LC_REF)
        for point, size in (("A", "30k"), ("B", "60k"), ("C", "120k"), ("D", "240k"))
        for kind, pattern in (
            ("Train", r"train ([\d,]+)/[\d,]+"),
            ("Fraud", r"train [\d,]+/([\d,]+)"),
            ("Recall", r"ML-GATE-03  Recall at 0\.60\s+([\d.]+)"),
            ("Recallci", r"ML-GATE-03  Recall at 0\.60\s+[\d.]+\s+(\[[\d., ]+\])"),
            ("SeedRecall", r"SEEDS.*?ML-GATE-03  ([\d.]+ \+/- [\d.]+)"),
        )
    },
    "nLcBudgetRecallD": Src(LC.format("240k"), BUDGET + r"([\d.]+)", ref=LC_REF),
    "nLcBudgetRecallciD": Src(
        LC.format("240k"),
        BUDGET + r"[\d.]+\s+(\[[\d., ]+\])",
        ref=LC_REF,
    ),
    "nLcSeedBudgetD": Src(
        LC.format("240k"), r"SEEDS.*?ML-GATE-02  ([\d.]+ \+/- [\d.]+)", ref=LC_REF
    ),
    "nLcBudgetRecallC": Src(LC.format("120k"), BUDGET + r"([\d.]+)", ref=LC_REF),
    "nLcCorpusSmall": Src(LC_CACHE.format("120k"), r"--corpus-rows (\d+)", ref=LC_REF),
    "nLcCorpusLarge": Src(LC_CACHE.format("240k"), r"--corpus-rows (\d+)", ref=LC_REF),
    "nGateCorpus": Src(GATE_CACHE, r"--corpus-rows (\d+)"),
    "nLcStepCD": Derived(
        ("nLcRecallC", "nLcRecallD"),
        lambda c, d: f"{Decimal(d) - Decimal(c):.3f}",
        "seed-1 recall at 0.60, 240K minus 120K",
    ),
    "nLcMaxUpper": Derived(
        ("nLcRecallciA", "nLcRecallciB", "nLcRecallciC", "nLcRecallciD"),
        lambda *cis: str(max(Decimal(ci.strip("[]").split(",")[1]) for ci in cis)),
        "largest upper bound of the four seed-1 intervals",
    ),
    "nLcSeedGain": Derived(
        ("nLcSeedRecallA", "nLcSeedRecallD"),
        lambda a, d: f"{Decimal(d.split()[0]) - Decimal(a.split()[0]):.3f}",
        "five-seed mean recall at 0.60, 240K minus 30K, to three places",
    ),
    "nLcSpanSeedOne": Derived(
        ("nLcRecallA", "nLcRecallB", "nLcRecallC", "nLcRecallD"),
        lambda *r: f"{max(map(Decimal, r)) - min(map(Decimal, r)):.3f}",
        "largest minus smallest of the four seed-1 values",
    ),
    "nSelGapSeedOne": Derived(
        ("nLcRecallA", "nGateRecallSix"),
        lambda curve, gate: f"{Decimal(curve) - Decimal(gate):.3f}",
        "seed 1 at 30,000 rows: the curve's corpus minus the gate's",
    ),
    "nSelGapMeans": Derived(
        ("nLcSeedRecallA", "nGateSeedRecallSix"),
        lambda curve, gate: f"{Decimal(curve.split()[0]) - Decimal(gate.split()[0]):.3f}",
        "five-seed means at 30,000 rows: the curve's corpus minus the gate's, to three places",
    ),
    "nLcStepAC": Derived(
        ("nLcRecallA", "nLcRecallC"),
        lambda a, c: f"{Decimal(c) - Decimal(a):.3f}",
        "seed-1 recall at 0.60, 120K minus 30K (both from one cache)",
    ),
    "nLcSeedGainAC": Derived(
        ("nLcSeedRecallA", "nLcSeedRecallC"),
        lambda a, c: f"{Decimal(c.split()[0]) - Decimal(a.split()[0]):.3f}",
        "five-seed mean recall at 0.60, 120K minus 30K, to three places",
    ),
    "nLcSeedStepCD": Derived(
        ("nLcSeedRecallC", "nLcSeedRecallD"),
        lambda c, d: f"{Decimal(d.split()[0]) - Decimal(c.split()[0]):.3f}",
        "five-seed mean recall at 0.60, 240K minus 120K, to three places",
    ),
    "nLcVolumeFactor": Derived(
        ("nLcTrainA", "nLcTrainD"),
        lambda a, d: _word(int(d.replace(",", "")) / int(a.replace(",", ""))),
        "largest training size over the smallest, as a word",
    ),
    "nLcVolumeFactorAC": Derived(
        ("nLcTrainA", "nLcTrainC"),
        lambda a, c: _word(int(c.replace(",", "")) / int(a.replace(",", ""))),
        "120K over 30K, as a word",
    ),
    "nLcPredLow": Src(
        NOTEBOOK, r"rising to \*\*between ([\d.]+) and\s+[\d.]+ at 240K", ref=NOTEBOOK_MAIN_REF
    ),
    "nLcPredHigh": Src(
        NOTEBOOK, r"rising to \*\*between [\d.]+ and\s+([\d.]+) at 240K", ref=NOTEBOOK_MAIN_REF
    ),
    "nLcPredBudgetLow": Src(
        NOTEBOOK,
        r"1% FPR budget rises to \*\*between ([\d.]+) and [\d.]+\*\* at 240K",
        ref=NOTEBOOK_MAIN_REF,
    ),
    "nLcPredBudgetHigh": Src(
        NOTEBOOK,
        r"1% FPR budget rises to \*\*between [\d.]+ and ([\d.]+)\*\* at 240K",
        ref=NOTEBOOK_MAIN_REF,
    ),
    # ---- counts and specification values typed into the text before 2026-10-19 ----
    "_CountryTable": Src(REAL, r"(^\| RW \| .*?^\| CD \| [^\n]*)"),
    "_ChannelTable": Src(REAL, r"(^\| MOBILE_MONEY \| .*?^\| BANK_TRANSFER \| [^\n]*)"),
    "nCountries": Derived(
        ("_CountryTable",),
        lambda t: _rows(t, r"^\| [A-Z]{2} \|"),
        "rows of the realism report's country-mix table",
    ),
    "nChannels": Derived(
        ("_ChannelTable",),
        lambda t: _rows(t, r"^\| [A-Z_]+ \|"),
        "rows of the realism report's channel-mix table",
    ),
    "nScenarios": Src(REAL, r"fraud scenarios \| pass \| no \| (\d+) types"),
    "nScenariosCap": Src(REAL, r"fraud scenarios \| pass \| no \| (\d+) types"),
    "nGroupsAboveCeiling": Derived(
        GROUPS_ABOVE,
        _above,
        "diagnostic-model groups alone whose AUC exceeds the single-feature ceiling",
    ),
    "nGroupsAboveCeilingCap": Derived(
        GROUPS_ABOVE,
        _above,
        "the same count, capitalised at a sentence start",
    ),
    "nDeviceNaNFeatures": Src(REGISTRY, r"\((?:\d+)\)\. (Four) are NaN for a null fingerprint"),
    "nAdaptMonthIndex": Src(FRAUD_YAML, r"adaptation_start_month:\s*\n\s*value: (\d+)"),
    "nAdaptMonthOrdinal": Src(FRAUD_YAML, r"as index 12, the (thirteenth) month"),
    "nEventLeakShare": Src(NOTEBOOK, r"\*\*([\d.]+%)\*\* of them sat on a"),
    "nEventLeakBase": Src(NOTEBOOK, r"fraud account against a ([\d.]+%) base rate"),
    "nMinSigmas": Src(FRAUD_PY, r"b\*n \+ (\d+)\*sqrt\(b\*n\)"),
    "nFingerprintSampleRows": Src(FINGERPRINT, r"^SAMPLE_ROWS = (\d+)"),
    "nResolutions": Src(PROMPT, r"All (\d+) defect resolutions"),
    "nLatencyTargetFlagged": Src(PROMPT, r"budget targets p95 ≤ (\d+) ms for flagged"),
    "nSpecRows": Src(PROMPT, r"Targets \(SRS 7\.1\): ≥ ([\d,]+) rows"),
    "nRazaqInterviews": Src(REF_AUDIT, r"We interviewed (\d+) participants", ref=REF_AUDIT_REF),
    "_GroupCounts": Src(TEST_REGISTRY, r"(EXPECTED_GROUP_COUNTS = \{.*?\n\})"),
    "nFeatureGroups": Derived(
        ("_GroupCounts",),
        lambda t: _rows(t, r"^\s+Group\.\w+: \d+,"),
        "entries of the registry test's EXPECTED_GROUP_COUNTS",
    ),
    "nSynthTerms": Src(REGISTRY, r"the unweighted mean of (four) \"\s+\"terms each in"),
    "nSpecTolerance": Src(PROMPT, r"yields identical vectors to (1e-9)\)"),
    "nSlackUlps": Src(PARITY, r"\| `round_sum_flag` \| 1 \| [\d.e-]+ \| ([\d.]+ million) ulps"),
    "nParityCrossover": Derived(
        ("nSpecTolerance", "nParityRel"),
        lambda spec, rel: str(int((Decimal(spec) - Decimal(rel)) / Decimal(rel))),
        "|b| at which 1e-12 + 1e-12|b| equals the specification's absolute 1e-9",
    ),
    "nFeatureLatencyTarget": Src(PROMPT, r"`benchmark\.py features` p95 < (\d+) ms"),
    "nCounterpartyNewMci": Src(
        FLOOR, r"\| `counterparty_is_new_for_account` \| [\d.]+ \| ±([\d.]+) \|"
    ),
    "nTxCountHourMci": Src(FLOOR, r"\| `tx_count_1h` \| [\d.]+ \| ±([\d.]+) \|"),
    "nImpliedSpeedMci": Src(FLOOR, r"\| `implied_speed_kmh` \| [\d.]+ \| ±([\d.]+) \|"),
    "nSecondsSinceMci": Src(FLOOR, r"\| `seconds_since_last_tx` \| [\d.]+ \| ±([\d.]+) \|"),
    "nFeaturesAboveCeilingCI": Derived(
        (
            "nCeiling",
            "nFloorM",
            "nFloorMci",
            "nCounterpartyNewM",
            "nCounterpartyNewMci",
            "nTxCountHourM",
            "nTxCountHourMci",
            "nImpliedSpeedM",
            "nImpliedSpeedMci",
            "nSecondsSinceM",
            "nSecondsSinceMci",
        ),
        lambda c, *v: _word(
            sum(Decimal(a) - Decimal(b) > Decimal(c) for a, b in zip(v[::2], v[1::2], strict=True))
        ),
        "features whose separation minus its interval half-width exceeds the ceiling",
    ),
    "nUnboundedFixed": Src(FLOOR_RAW, r"fix: (five) unbounded features were then deriving"),
    "nGateFailed": Derived(
        ("nGateMeasured", "nGatePassed"),
        lambda measured, passed: _word(int(measured) - int(passed)),
        "gate metrics measured minus gate metrics passing",
    ),
    "nGateUnmeasured": Derived(
        ("nGateTotal", "nGateMeasured"),
        lambda total, measured: _word(int(total) - int(measured)),
        "gate metrics specified minus gate metrics measured",
    ),
    "nEceBins": Src(GATE, r"ML-GATE-11  ECE, (\d+) equal-mass bins"),
    "nGateRealisedFPR": Src(GATE, r"budget's threshold the realised FPR is ([\d.]+)"),
    "nGatePrecCeilingRealised": Src(GATE, r"against D-01's ceiling of ([\d.]+) at that rate"),
    "nSmallCountThreshold": Src(BAT, r"CD rest on fewer than (\d+) fraud rows"),
    "nBaseLRGap": Derived(
        ("nGateAUC", "nBaseLR"),
        lambda ensemble, lr: f"{Decimal(ensemble) - Decimal(lr):.3f}",
        "ensemble AUC minus logistic regression's",
    ),
    "nLcCorpusPlanned": Src(
        NOTEBOOK,
        r"built from a \*\*([\d,]+)-row corpus\*\* \(a train-period",
        ref=NOTEBOOK_MAIN_REF,
    ),
    **{
        f"nLcRealFpr{point}": Src(LC.format(size), r"realised FPR is ([\d.]+)", ref=LC_REF)
        for point, size in (("A", "30k"), ("B", "60k"), ("C", "120k"), ("D", "240k"))
    },
    **{
        f"nAbl{name}p": Src(GATE, rf"{re.escape(label)}\s+\d+ feat .*?, p ([\d.e-]+)\)")
        for name, label in (
            ("Agent", "without agent features"),
            ("Corridor", "without the corridor feature"),
            ("Round", "without round-sum awareness"),
            ("Month", "without month-end awareness"),
            ("USSD", "without USSD-aware device handling"),
        )
    },
    "nBatLowBinRows": Src(BAT, r"\[0\.0, 0\.1\)\s+(\d+)"),
    "nBatLowBinTop": Src(BAT, r"\[0\.0, (0\.1)\)"),
    "nEarlyHeldOutRows": Src(BAT_EARLY, r"\*\*([\d,]+) held-out rows holding"),
    "nLatencyPNineNineShap": Src(PROMPT, r"exceeds (\d+) ms on the reference hardware"),
    "_FrontRows": Src(FRONT, r"(^\s+trees depth.*?)\n\n"),
    **{
        f"nFr{kind}{point}": Derived(
            ("_FrontRows",),
            partial(_front_cell, col, i),
            f"{kind.lower()} of frontier row {i + 1}",
        )
        for kind, col in (("Trees", 0), ("Depth", 1))
        for i, point in enumerate("ABCDE")
    },
    "nCoFraudRateCD": Derived(
        ("nLocoCDf", "nCoRowsCD"),
        _pct,
        "CD's test-period fraud rows over its test rows",
    ),
    "nCoFraudRateMin": Derived(
        OTHER_COUNTRIES,
        lambda *v: min((_pct(f, r) for f, r in zip(v[::2], v[1::2], strict=True)), key=_pct_key),
        "lowest test-period fraud rate among KE, RW, TZ and UG",
    ),
    "nCoFraudRateMax": Derived(
        OTHER_COUNTRIES,
        lambda *v: max((_pct(f, r) for f, r in zip(v[::2], v[1::2], strict=True)), key=_pct_key),
        "highest test-period fraud rate among KE, RW, TZ and UG",
    ),
    "nLeadMedianMinutes": Src(FRAUD_YAML, r"takeover_lead_median_minutes:\s*\n\s*value: (\d+)"),
    "nRevAmountMultiplier": Src(
        FRAUD_YAML, r"reversal_scam_amount_multiplier:\s*\n\s*value: ([\d.]+)"
    ),
    "nBandWider": Src(NOTEBOOK, r"the floor is\s+\*\*([\d.]+)× wider\*\*"),
    "nBandBlindLow": Src(NOTEBOOK, r"landing between roughly\s+([\d.]+) and [\d.]+\."),
    "nBandBlindHigh": Src(NOTEBOOK, r"landing between roughly\s+[\d.]+ and ([\d.]+)\."),
    "nSiblingLeakChannel": Src(CLAIMS, r"`channel` by ([\d.]+) and"),
    "nSiblingLeakCurrency": Src(CLAIMS, r"`currency` by ([\d.]+) at"),
    "nFingerprintColsRead": Src(NOTEBOOK, r"The fingerprint read (three) columns of fourteen"),
    "nFingerprintColsTotal": Src(NOTEBOOK, r"The fingerprint read three columns of (fourteen)"),
    "nFurtherFeatures": Src(NOTEBOOK, r"found (three) more instances the review had missed"),
    "nDetectorT": Src(NOTEBOOK, r"gave \*\*t = (\+[\d.]+)\*\*"),
    "nMarginRows": Src(NOTEBOOK, r"over \*\*(\d+) rows holding two confirmed fraud\*\*"),
    "nMarginFraud": Src(NOTEBOOK, r"over \*\*\d+ rows holding (two) confirmed fraud\*\*"),
    "nSelfServiceSimDays": Src(DEFECTS, r"`days_since_sim_swap` < (\d+) or unknown"),
    "nSelfServiceDeviceHours": Src(DEFECTS, r"device changed in last (\d+) h;"),
    "nSelfServiceScore": Src(DEFECTS, r"ensemble score ≥ ([\d.]+);"),
    "nComputableTarget": Src(PROMPT, r"≥ (\d+%) feature completeness"),
    # ---- ADR 0055 (accepted 2026-10-17) ----
    "nNoSourceFeatures": Src(
        ADR55, r"not merely in form\.\*\* (Five) of 44 features, 11 %", ref=ADR55_REF
    ),
    "nNoSourceShare": Derived(
        ("nNoSourceFeatures", "nFeatures"),
        lambda n, total: f"{Decimal(WORDS[n.lower()]) * 100 / Decimal(total):.0f}%",
        "the no-source count over the catalogue's 44, to a whole per cent",
    ),
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


def _tex_path(path: str) -> str:
    return path.replace("_", "\\_\\allowbreak ").replace("/", "/\\allowbreak ")


def comparable(name: str, path: str, got: str) -> str:
    """The evidence value in the form the paper prints it, for the few sources that differ."""
    if path == BAT_EARLY and name in ("nEarlyNovelRecall", "nEarlyBaseRecall"):
        return f"{Decimal(got) * 100:.1f}%".replace("100.0%", "100%")
    if name == "nSourceDocs":
        return got.removeprefix("S-")
    return got


def count_helpers(used: dict[tuple[str, str, str], int], cache: dict[str, str], ref: str) -> None:
    """Add the `_`-prefixed sources, read only as inputs to derived values, to the index."""
    for name, spec in SOURCES.items():
        if name.startswith("_") and isinstance(spec, Src):
            at = spec.ref or ref
            key = (spec.path, at, evidence_commit(cache[f"{at}:{spec.path}"]))
            used[key] = used.get(key, 0) + 1


def write_index(used: dict[tuple[str, str, str], int]) -> None:
    """Appendix D's table: every file a value is read from, the ref it is read at, and how many."""
    lines = [
        "% GENERATED by audit_numbers.py from the sources it read. Do not edit: run the audit.",
    ]
    for (path, ref, commit), count in sorted(used.items()):
        at = rf"\texttt{{{ref}}}" + (rf", stamp \texttt{{{commit}}}" if commit else "")
        lines.append(rf"\texttt{{{_tex_path(path)}}} & {at} & {count} \\")
    (HERE / "sections" / "generated-evidence-index.tex").write_text("\n".join(lines) + "\n")


def write_report(rows: list[tuple[str, str, str, str, str, str]], ref: str, ref_sha: str) -> None:
    lines = [
        "# Number audit",
        "",
        f"Every macro in `numbers.tex` checked against its evidence file at `{ref}` "
        f"(`{ref_sha}`), or at the ref its row names, read with `git show`, by "
        "`audit_numbers.py`. Values are compared as "
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


def helper_values(ref: str, cache: dict[str, str]) -> dict[str, str]:
    """The `_`-prefixed sources, which exist only as inputs to derived values."""
    found: dict[str, str] = {}
    for name, spec in SOURCES.items():
        if not (name.startswith("_") and isinstance(spec, Src)):
            continue
        at = spec.ref or ref
        text = cache.setdefault(f"{at}:{spec.path}", git_show(at, spec.path))
        hit = re.search(spec.pattern, text, re.S | re.M)
        found[name] = hit.group(1) if hit else ""
    return found


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ref", default="m4-complete")
    args = parser.parse_args()
    ref_sha = subprocess.run(  # noqa: S603 - fixed git command
        ["git", "rev-parse", "--short", f"{args.ref}^{{commit}}"],  # noqa: S607
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.strip()  # fmt: skip
    cache: dict[str, str] = {}
    rows: list[tuple[str, str, str, str, str, str]] = []
    problems = 0
    defined = macros()
    found_values = helper_values(args.ref, cache)
    used: dict[tuple[str, str, str], int] = {}
    for name, (_, value) in defined.items():
        source = SOURCES.get(name)
        if source is None:
            rows.append((name, value, "—", "no source recorded", "", "MISSING SOURCE"))
            problems += 1
            continue
        if isinstance(source, Derived):
            continue
        ref = source.ref or args.ref
        text = cache.setdefault(f"{ref}:{source.path}", git_show(ref, source.path))
        m = re.search(source.pattern, text, re.S | re.M)
        if not m:
            rows.append((name, value, "—", source.path, source.note, "PATTERN NOT FOUND"))
            problems += 1
            continue
        got = m.group(1)
        got_cmp = comparable(name, source.path, got)
        found_values[name] = got
        where = (
            f"`{source.path}`"
            + (f" at `{source.ref}`" if source.ref else "")
            + (f" (evidence commit `{c}`)" if (c := evidence_commit(text)) else "")
        )
        key = (source.path, ref, c)
        used[key] = used.get(key, 0) + 1
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
    count_helpers(used, cache, args.ref)
    write_report(rows, args.ref, ref_sha)
    write_index(used)
    print(f"number-audit: {len(rows)} macros at {args.ref} ({ref_sha}); {problems} problem(s)")
    for r in rows:
        if r[5] != "MATCH":
            print(f"  {r[5]}: \\{r[0]} paper={r[1]!r} evidence={r[2]!r} ({r[3]})", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
