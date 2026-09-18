"""Anti-leakage and realism checks on a generated dataset (Part E.3, D-08, owner direction).

Gate checks fail the run; report-only checks are measured and shown. The checks read the dataset
month by month and keep numeric columns only, so they run on the full dataset in modest memory.

Leakage is assessed against the observed label (the one a model would train on):

- single-feature AUC (``max(AUC, 1 - AUC)``) at most 0.80 for every raw and cheap per-row feature;
- a shortcut detector: a depth-3 tree on non-behavioural per-row columns only (identifier bytes,
  sub-second timestamp parts, row position within its file) must have a cross-validated AUC
  within 0.5 +/- 0.03. Folds are grouped by account, so the rows of one incident, which share a
  time, cannot sit on both sides of a split. File (month) order is judged alone, because months
  are calendar time and fraud prevalence drifts over time by design;
- identifier construction: the same tree over distinct token values (account, counterparty,
  device), labelled by whether a fraud row uses them, must also stay within 0.5 +/- 0.03. Tokens
  are judged per distinct value because victims and mule accounts legitimately recur;
- fraud and legitimate rows share value formats and, per channel, null signatures.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq
from numpy.typing import NDArray

from fraudshield_dataset.generator.config import CHANNELS, SimulationConfig
from fraudshield_dataset.generator.fraud import NOVEL_VARIANT
from fraudshield_dataset.generator.legit import MINOR_UNITS
from fraudshield_dataset.generator.pipeline import peak_rss_bytes
from fraudshield_dataset.normal import inverse_cdf
from fraudshield_dataset.realism.stats import (
    auc,
    cross_validated_auc,
    null_auc_stderr,
    separation,
    wilson_interval,
)

SINGLE_FEATURE_AUC_LIMIT = 0.80
SHORTCUT_TOLERANCE = 0.03
CI_Z = 1.96
# Family-wise level for the identifier-construction bands. Three token columns are tested at once,
# so a per-column 5% band fails about one run in seven by chance; the band is widened to keep the
# 5% for the check as a whole (Bonferroni, two-sided).
FAMILY_ALPHA = 0.05
DISTRIBUTION_TOLERANCE_PP = 0.5
MIN_ROWS_FULL = 5_000_000
PEAK_RSS_LIMIT_BYTES = 2 * 2**30
FOLDS = 5
LEGITIMATE_SAMPLE_PER_MILLE = 40
_MICROS_PER_HOUR = 3_600_000_000
_MICROS_PER_DAY = 86_400_000_000
_TOKEN = re.compile(r"^tok_[A-Za-z0-9]{24,64}$")
_MCC = re.compile(r"^[0-9]{4}$")
_CATEGORICALS = ("channel", "currency", "merchant_category_code")


@dataclass
class CheckResult:
    name: str
    passed: bool
    gate: bool
    value: str
    requirement: str
    detail: str = ""


@dataclass
class Columns:
    """Numeric per-row columns gathered month by month (stored as float32)."""

    names: list[str] = field(default_factory=list)
    parts: dict[str, list[NDArray[np.float32]]] = field(default_factory=dict)

    def add(self, name: str, values: NDArray[Any]) -> None:
        if name not in self.parts:
            self.names.append(name)
            self.parts[name] = []
        self.parts[name].append(values.astype(np.float32))

    def get(self, name: str) -> NDArray[np.float64]:
        return np.concatenate(self.parts[name]).astype(np.float64)

    def replace(self, name: str, values: NDArray[Any]) -> None:
        self.parts[name] = [values.astype(np.float32)]


def _category_rates(codes: NDArray[np.int64], labels: NDArray[np.bool_]) -> NDArray[np.float64]:
    """Score each row by its category's fraud rate: the single-feature AUC of a categorical."""
    positives = np.bincount(codes, weights=labels.astype(np.float64))
    counts = np.bincount(codes)
    return (positives / np.maximum(counts, 1))[codes]


def _hex_byte(values: list[str], position: int) -> NDArray[np.float64]:
    return np.array(
        [int(v.replace("-", "")[position : position + 2], 16) for v in values], dtype=np.float64
    )


def token_features(values: list[str]) -> NDArray[np.float64]:
    """Characters after the ``tok_`` prefix and the length: what construction could leak."""
    return np.array([[ord(v[4]), ord(v[5]), ord(v[-1]), len(v)] for v in values], dtype=np.float64)


def _sampled(transaction_id: str, seed: int) -> bool:
    digest = hashlib.blake2b(f"{seed}:{transaction_id}".encode(), digest_size=8).digest()
    return int.from_bytes(digest, "big") % 1000 < LEGITIMATE_SAMPLE_PER_MILLE


def _account_group(account: str) -> int:
    return int.from_bytes(hashlib.blake2b(account.encode(), digest_size=7).digest(), "big")


class Dataset:
    """One pass over the months of a generated dataset, accumulating what the checks need."""

    def __init__(self, root: Path, config: SimulationConfig) -> None:
        self.config = config
        p = config.parameters
        self.offsets = p.mapping("currencies.utc_offset_hours")
        self.country_of_currency = {
            v: k for k, v in p.texts("currencies.currency_by_country").items()
        }
        self.features = Columns()
        self.shortcut = Columns()
        self.observed_parts: list[NDArray[np.bool_]] = []
        self.feature_label_parts: list[NDArray[np.bool_]] = []
        self.true_parts: list[NDArray[np.bool_]] = []
        self.time_parts: list[NDArray[np.int64]] = []
        self.file_index_parts: list[NDArray[np.float64]] = []
        self.shortcut_labels: list[NDArray[np.bool_]] = []
        self.shortcut_groups: list[NDArray[np.int64]] = []
        self.novel_timestamps: list[int] = []
        self.fraud_types: dict[str, int] = {}
        self.fraud_types_by_month: dict[str, dict[str, int]] = {}
        self.rows_by_month: dict[str, int] = {}
        self.channel_counts: dict[str, int] = {}
        self.currency_counts: dict[str, int] = {}
        self.format_violations = dict.fromkeys(
            ("token_format", "mcc_format", "amount_scale", "coordinate_precision"), 0
        )
        self.null_signatures: dict[str, dict[str, set[tuple[bool, bool]]]] = {
            "fraud": {},
            "legitimate": {},
        }
        self.tokens: dict[str, dict[str, bool]] = {}
        self.id_digests: list[NDArray[np.uint64]] = []
        self._vocabularies: dict[str, dict[str, int]] = {}
        months = sorted(
            p.name.removeprefix("month=") for p in (root / "transactions").glob("month=*")
        )
        if not months:
            raise FileNotFoundError(f"no month partitions under {root / 'transactions'}")
        for file_index, month in enumerate(months):
            transactions = pq.read_table(
                root / "transactions" / f"month={month}" / "part-0000.parquet"
            )
            labels = pq.read_table(root / "labels" / f"month={month}" / "part-0000.parquet")
            self._month(file_index, transactions, labels)
            self.rows_by_month[month] = transactions.num_rows
            monthly = self.fraud_types_by_month.setdefault(month, {})
            for kind in labels.filter(labels["is_fraud_true"])["fraud_type"].to_pylist():
                monthly[kind] = monthly.get(kind, 0) + 1
        feature_labels = self.feature_labels
        for name in _CATEGORICALS:
            codes = self.features.get(name).astype(np.int64)
            self.features.replace(name, _category_rates(codes, feature_labels))

    @property
    def observed(self) -> NDArray[np.bool_]:
        return np.concatenate(self.observed_parts)

    @property
    def feature_labels(self) -> NDArray[np.bool_]:
        """Observed labels of the rows the feature matrix holds (all fraud, sampled legitimate)."""
        return np.concatenate(self.feature_label_parts)

    @property
    def true(self) -> NDArray[np.bool_]:
        return np.concatenate(self.true_parts)

    @property
    def timestamps(self) -> NDArray[np.int64]:
        return np.concatenate(self.time_parts)

    def _month(self, file_index: int, t: pa.Table, labels: pa.Table) -> None:
        observed = labels["is_fraud_observed"].to_numpy(zero_copy_only=False)
        true = labels["is_fraud_true"].to_numpy(zero_copy_only=False)
        micros = t["transaction_timestamp"].cast(pa.int64()).to_numpy()
        ids = t["transaction_id"].to_pylist()
        # All fraud rows plus a keyed sample of legitimate ones. AUC compares the two classes, so
        # sampling legitimate rows at a fixed rate leaves it unbiased, and the feature matrix then
        # costs the sample rather than the dataset: 404 MiB at a million rows would have been two
        # gigabytes at five million, over the memory budget for this project.
        keep = np.flatnonzero(true | np.array([_sampled(i, self.config.seed) for i in ids]))
        self.observed_parts.append(observed)
        self.true_parts.append(true)
        self.time_parts.append(micros)
        self.file_index_parts.append(np.full(t.num_rows, float(file_index)))
        variants = labels["scenario_variant"].to_pylist()
        self.novel_timestamps.extend(
            int(m) for m, v in zip(micros, variants, strict=True) if v == NOVEL_VARIANT
        )
        for kind in labels["fraud_type"].drop_null().to_pylist():
            self.fraud_types[kind] = self.fraud_types.get(kind, 0) + 1
        self._behavioural_features(t, micros, keep)
        self.feature_label_parts.append(observed[keep])
        self._formats_and_nulls(t, true)
        self._shortcut_columns(t, micros, observed, ids, keep)
        self._identifier_tokens(t, observed)
        # Eight bytes of a keyed digest per identifier: uniqueness over millions of rows can then
        # be checked with one sort instead of holding every string in memory.
        self.id_digests.append(
            np.fromiter(
                (
                    int.from_bytes(hashlib.blake2b(i.encode(), digest_size=8).digest(), "big")
                    for i in ids
                ),
                dtype=np.uint64,
                count=t.num_rows,
            )
        )

    def _behavioural_features(
        self, t: pa.Table, micros: NDArray[np.int64], keep: NDArray[np.int64]
    ) -> None:
        channel, currency = t["channel"].to_pylist(), t["currency"].to_pylist()
        for c in channel:
            self.channel_counts[c] = self.channel_counts.get(c, 0) + 1
        for c in currency:
            self.currency_counts[c] = self.currency_counts.get(c, 0) + 1
        # The mixes above count every row; the features below describe the sample only.
        home = [self.country_of_currency[currency[i]] for i in keep]
        offset = np.array([self.offsets[h] for h in home]) * _MICROS_PER_HOUR
        local = micros[keep] + offset.astype(np.int64)
        amount = pc.cast(t["amount"], pa.float64()).to_numpy()[keep]
        destination = t["counterparty_country"].to_pylist()
        f = self.features
        f.add("amount_rwf", pc.cast(t["amount_rwf"], pa.float64()).to_numpy()[keep])
        f.add("local_hour", (local // 1_000_000) % 86_400 // 3600)
        f.add("day_of_week", (local // _MICROS_PER_DAY + 3) % 7)
        f.add("day_of_month", pc.day(t["transaction_timestamp"]).to_numpy()[keep])
        f.add("round_amount", np.mod(amount, 1000) == 0)
        f.add("latitude", t["latitude"].to_numpy()[keep])
        f.add("longitude", t["longitude"].to_numpy()[keep])
        f.add(
            "device_missing",
            pc.is_null(t["device_fingerprint"]).to_numpy(zero_copy_only=False)[keep],
        )
        f.add("agent_present", pc.is_valid(t["agent_id"]).to_numpy(zero_copy_only=False)[keep])
        f.add(
            "cross_border",
            np.array([destination[i] != h for i, h in zip(keep, home, strict=True)]),
        )
        for name in _CATEGORICALS:
            encoded = pc.dictionary_encode(t[name]).combine_chunks()
            vocabulary = self._vocabularies.setdefault(name, {})
            codes = np.array(
                [vocabulary.setdefault(v, len(vocabulary)) for v in encoded.dictionary.to_pylist()]
            )
            f.add(name, codes[encoded.indices.to_numpy()][keep])

    def _formats_and_nulls(self, t: pa.Table, true: NDArray[np.bool_]) -> None:
        columns = {
            name: t[name].to_pylist()
            for name in (
                "account_id",
                "counterparty_id",
                "merchant_category_code",
                "amount",
                "currency",
                "latitude",
                "channel",
                "device_fingerprint",
                "agent_id",
            )
        }
        v = self.format_violations
        for i in range(t.num_rows):
            if not (
                _TOKEN.match(columns["account_id"][i])
                and _TOKEN.match(columns["counterparty_id"][i])
            ):
                v["token_format"] += 1
            if not _MCC.match(columns["merchant_category_code"][i]):
                v["mcc_format"] += 1
            amount = columns["amount"][i]
            if (amount * 10 ** MINOR_UNITS[columns["currency"][i]]) % 1 != 0:
                v["amount_scale"] += 1
            if round(columns["latitude"][i], 6) != columns["latitude"][i]:
                v["coordinate_precision"] += 1
            group = "fraud" if true[i] else "legitimate"
            self.null_signatures[group].setdefault(columns["channel"][i], set()).add(
                (columns["device_fingerprint"][i] is None, columns["agent_id"][i] is None)
            )

    def _shortcut_columns(
        self,
        t: pa.Table,
        micros: NDArray[np.int64],
        observed: NDArray[np.bool_],
        ids: list[str],
        keep: NDArray[np.int64],
    ) -> None:
        kept_ids = [ids[i] for i in keep]
        accounts = t["account_id"].to_pylist()
        self.shortcut.add("transaction_id_first_byte", _hex_byte(kept_ids, 0))
        self.shortcut.add("transaction_id_last_byte", _hex_byte(kept_ids, 30))
        self.shortcut.add("timestamp_microseconds", micros[keep] % 1_000_000)
        self.shortcut.add("row_position_in_file", keep / max(t.num_rows - 1, 1))
        self.shortcut_labels.append(observed[keep])
        self.shortcut_groups.append(
            np.array([_account_group(accounts[i]) for i in keep], dtype=np.int64)
        )

    def _identifier_tokens(self, t: pa.Table, observed: NDArray[np.bool_]) -> None:
        labels = observed.tolist()
        for column in ("account_id", "counterparty_id", "device_fingerprint"):
            seen = self.tokens.setdefault(column, {})
            for value, label in zip(t[column].to_pylist(), labels, strict=True):
                if value is not None:
                    seen[value] = seen.get(value, False) or label


def _leakage_checks(
    data: Dataset, config: SimulationConfig, measures: dict[str, Any]
) -> list[CheckResult]:
    observed = data.observed
    feature_labels = data.feature_labels
    single = {
        name: separation(data.features.get(name), feature_labels) for name in data.features.names
    }
    worst = max(single, key=lambda k: single[k])
    measures["single_feature_auc"] = single

    matrix = np.column_stack([data.shortcut.get(n) for n in data.shortcut.names])
    shortcut_labels = np.concatenate(data.shortcut_labels)
    groups = np.concatenate(data.shortcut_groups)
    detector = cross_validated_auc(
        matrix, shortcut_labels, folds=FOLDS, seed=config.seed, groups=groups
    )
    measures["shortcut_detector_auc"] = detector
    measures["shortcut_features"] = data.shortcut.names

    file_order = separation(np.concatenate(data.file_index_parts), observed)
    measures["file_order_auc"] = file_order

    construction = {}
    token_bands = {}
    columns = max(len(data.tokens), 1)
    family_z = inverse_cdf(1.0 - FAMILY_ALPHA / (2 * columns))
    for column, seen in data.tokens.items():
        values = list(seen)
        labels = np.array([seen[v] for v in values])
        positives = int(labels.sum())
        construction[column] = (
            cross_validated_auc(token_features(values), labels, folds=FOLDS, seed=config.seed)
            if labels.any() and not labels.all()
            else 0.5
        )
        # Few fraud tokens make this AUC noisy on its own, so the band is the wider of the fixed
        # tolerance and the 95% sampling band under the null hypothesis of no construction signal.
        token_bands[column] = max(
            SHORTCUT_TOLERANCE,
            family_z * null_auc_stderr(positives, len(values) - positives),
        )
    measures["identifier_construction_auc"] = construction
    measures["identifier_construction_band"] = token_bands
    measures["identifier_token_counts"] = {
        column: {"values": len(seen), "fraud_values": sum(seen.values())}
        for column, seen in data.tokens.items()
    }

    threshold = config.parameters.number("fraud.rule_amount_threshold_rwf")
    rule = (data.features.get("amount_rwf") >= threshold).astype(float) + (
        data.features.get("local_hour") < 5
    )
    measures["trivial_rule_auc"] = auc(rule, feature_labels)
    return [
        CheckResult(
            "single-feature AUC",
            single[worst] <= SINGLE_FEATURE_AUC_LIMIT,
            True,
            f"max {single[worst]:.3f} ({worst})",
            f"every feature <= {SINGLE_FEATURE_AUC_LIMIT} (D-08)",
        ),
        CheckResult(
            "shortcut detector",
            abs(detector - 0.5) <= SHORTCUT_TOLERANCE,
            True,
            f"AUC {detector:.3f} on {matrix.shape[0]} rows",
            f"within 0.5 +/- {SHORTCUT_TOLERANCE} (owner direction)",
            "depth-3 tree, 5-fold CV grouped by account, features: "
            + ", ".join(data.shortcut.names),
        ),
        CheckResult(
            "file order",
            file_order <= 0.5 + SHORTCUT_TOLERANCE,
            True,
            f"AUC {file_order:.3f}",
            f"file (month) index alone within 0.5 + {SHORTCUT_TOLERANCE}",
            "the designed fraud-rate ramp gives a small, expected separation",
        ),
        CheckResult(
            "identifier construction",
            all(abs(construction[k] - 0.5) <= token_bands[k] for k in construction),
            True,
            ", ".join(
                f"{k} {v:.3f} (band +/-{token_bands[k]:.3f})" for k, v in construction.items()
            ),
            "token characters do not identify fraud tokens, within the null band",
            "depth-3 tree, 5-fold CV over distinct token values; band is max(0.03, z SE) with z "
            f"for a family-wise {FAMILY_ALPHA:.0%} level over {columns} columns",
        ),
        CheckResult(
            "trivial rule baseline",
            True,
            False,
            f"AUC {measures['trivial_rule_auc']:.3f}",
            "reported (amount >= rule threshold, or local hour before 05:00)",
        ),
    ]


def _label_and_format_checks(
    data: Dataset, config: SimulationConfig, full: bool, measures: dict[str, Any]
) -> list[CheckResult]:
    true, observed = data.true, data.observed
    true_count = max(int(true.sum()), 1)
    missed_rate = int((true & ~observed).sum()) / true_count
    false_rate = int((~true & observed).sum()) / true_count
    measures["label_noise"] = {"missed_fraud_rate": missed_rate, "false_fraud_rate": false_rate}
    before = [t for t in data.novel_timestamps if t < config.split.test_start]
    violations = sum(data.format_violations.values())
    digests = np.concatenate(data.id_digests)
    duplicates = digests.size - int(np.unique(digests).size)
    measures["duplicate_transaction_ids"] = duplicates
    unmatched = {
        channel: sorted(signatures - data.null_signatures["legitimate"].get(channel, set()))
        for channel, signatures in data.null_signatures["fraud"].items()
    }
    unmatched = {c: s for c, s in unmatched.items() if s}
    return [
        CheckResult(
            "label noise",
            0.01 <= missed_rate <= 0.02 and 0.01 <= false_rate <= 0.02,
            full,
            f"missed {missed_rate:.2%}, false {false_rate:.2%} of true fraud",
            "each direction 1-2% of true fraud labels (D-08)",
        ),
        CheckResult(
            "novel sub-variant placement",
            not before and (bool(data.novel_timestamps) or not full),
            True,
            f"{len(data.novel_timestamps)} rows, {len(before)} before the test start",
            "only in the temporal hold-out test period, and present (D-08)",
        ),
        CheckResult(
            "identifier uniqueness",
            duplicates == 0,
            True,
            f"{duplicates} duplicate transaction ids in {digests.size} rows",
            "every transaction id occurs once (ingestion contract)",
        ),
        CheckResult(
            "value formats",
            violations == 0,
            True,
            f"{violations} violations",
            "tokens, MCC, amount scale and coordinate precision valid for every row",
            json.dumps(data.format_violations, sort_keys=True),
        ),
        CheckResult(
            "null signatures per channel",
            not unmatched,
            True,
            f"unmatched: {unmatched}"
            if unmatched
            else "every fraud null pattern also occurs in legitimate rows",
            "identical null patterns per channel for fraud and legitimate rows",
        ),
    ]


def _distribution_checks(
    data: Dataset, root: Path, config: SimulationConfig, full: bool, measures: dict[str, Any]
) -> list[CheckResult]:
    p = config.parameters
    rows = int(data.observed.size)
    splits = split_counts(data, config)
    overall = float(data.true.mean())
    test_rate = splits["test"]["true_fraud_rate"]
    channel_share = {c: data.channel_counts.get(c, 0) / rows for c in CHANNELS}
    worst_channel = (
        max(abs(channel_share[c] - p.mapping("channels.channel_share")[c]) for c in CHANNELS) * 100
    )
    country_share = {data.country_of_currency[c]: n / rows for c, n in data.currency_counts.items()}
    worst_country = (
        max(
            abs(country_share.get(c, 0.0) - s)
            for c, s in p.mapping("geography.country_share").items()
        )
        * 100
    )
    run = json.loads((root / "run.json").read_text())
    measures.update(
        rows=rows,
        splits=splits,
        channel_share=channel_share,
        country_share=country_share,
        fraud_types=data.fraud_types,
        fraud_types_by_month=data.fraud_types_by_month,
        rows_by_month=data.rows_by_month,
        novel_rows=len(data.novel_timestamps),
        novel_earliest=min(data.novel_timestamps) if data.novel_timestamps else None,
        test_start=config.split.test_start,
        seed=config.seed,
        peak_rss_bytes=run["peak_rss_bytes"],
        machine=run["machine"],
        chunk_size=run["chunk_size"],
    )
    monthly = []
    for index, month in enumerate(sorted(data.rows_by_month)):
        month_rows = data.rows_by_month[month]
        month_fraud = sum(data.fraud_types_by_month.get(month, {}).values())
        low, high = wilson_interval(month_fraud, month_rows, CI_Z)
        target = config.fraud_rate_by_month[index]
        monthly.append(
            {
                "month": month,
                "rows": month_rows,
                "fraud": month_fraud,
                "rate": month_fraud / month_rows if month_rows else 0.0,
                "ci_lower": low,
                "ci_upper": high,
                "target": target,
                "covers_target": low <= target <= high,
            }
        )
    measures["monthly_fraud"] = monthly
    covered = sum(1 for m in monthly if m["covers_target"])
    overall_ci = wilson_interval(int(data.true.sum()), rows, CI_Z)
    test_rows = int(splits["test"]["rows"])
    test_ci = wilson_interval(round(test_rate * test_rows), test_rows, CI_Z)
    measures["fraud_rate_ci"] = {"overall": overall_ci, "test": test_ci}
    fraud_ok = (
        abs(overall - p.number("fraud.fraud_rate_overall")) * 100 <= DISTRIBUTION_TOLERANCE_PP
        and abs(test_rate - p.number("fraud.fraud_rate_test")) * 100 <= DISTRIBUTION_TOLERANCE_PP
    )
    return [
        CheckResult(
            "fraud rate",
            fraud_ok,
            full,
            f"overall {overall:.3%} (95% CI {overall_ci[0]:.3%}-{overall_ci[1]:.3%}), "
            f"test {test_rate:.3%} (95% CI {test_ci[0]:.3%}-{test_ci[1]:.3%})",
            "0.87% overall, 0.91% test, +/- 0.5 pp (ML-DATA-02)",
        ),
        CheckResult(
            "monthly fraud rate",
            covered == len(monthly),
            False,
            f"{covered} of {len(monthly)} months cover their target within a 95% CI",
            "each month's Wilson 95% CI covers its calibrated intensity target",
            "a CI that covers the target is sampling noise; one that does not is bias",
        ),
        CheckResult(
            "fraud scenarios",
            len(data.fraud_types) == 8,
            True,
            f"{len(data.fraud_types)} types",
            "8 distinct scenario types (ML-DATA-04)",
            json.dumps(data.fraud_types, sort_keys=True),
        ),
        CheckResult(
            "channel mix",
            worst_channel <= DISTRIBUTION_TOLERANCE_PP,
            full,
            f"max deviation {worst_channel:.2f} pp",
            "SRS channel mix +/- 0.5 pp (ML-DATA-03)",
        ),
        CheckResult(
            "country mix",
            worst_country <= DISTRIBUTION_TOLERANCE_PP,
            full,
            f"max deviation {worst_country:.2f} pp",
            "SRS country mix +/- 0.5 pp (ML-DATA-05)",
        ),
        CheckResult(
            "size",
            rows >= MIN_ROWS_FULL,
            full,
            f"{rows} rows",
            ">= 5,000,000 rows in a release run (ML-DATA-01)",
            "development runs are smaller on purpose; this is a gate only with --full",
        ),
        CheckResult(
            "generator peak memory",
            run["peak_rss_bytes"] < PEAK_RSS_LIMIT_BYTES,
            True,
            f"{run['peak_rss_bytes'] / 2**20:.0f} MiB",
            "< 2 GiB peak RSS (owner direction)",
        ),
    ]


def run_checks(
    root: Path, config: SimulationConfig, full: bool
) -> tuple[list[CheckResult], dict[str, Any]]:
    """Run every check. ``full`` gates the size and distribution targets (5M+ rows, +/- 0.5 pp)."""
    data = Dataset(root, config)
    measures: dict[str, Any] = {}
    results = (
        _leakage_checks(data, config, measures)
        + _label_and_format_checks(data, config, full, measures)
        + _distribution_checks(data, root, config, full, measures)
    )
    # Measured, not assumed: the checks read the dataset month by month and keep per-row arrays
    # plus a sample of the features, so their cost is reported next to the generator's.
    measures["checks_peak_rss_bytes"] = peak_rss_bytes()
    return results, measures


def split_counts(data: Dataset, config: SimulationConfig) -> dict[str, dict[str, float]]:
    s = config.split
    t = data.timestamps
    true, observed = data.true, data.observed
    masks = {
        "train": t < s.validation_start,
        "validation": (t >= s.validation_start) & (t < s.embargo_start),
        "calibration (last part of validation)": (t >= s.calibration_start) & (t < s.embargo_start),
        "embargo (excluded)": (t >= s.embargo_start) & (t < s.test_start),
        "test": t >= s.test_start,
    }
    out = {}
    for name, mask in masks.items():
        count = int(mask.sum())
        out[name] = {
            "rows": count,
            "true_fraud_rate": float(true[mask].mean()) if count else 0.0,
            "observed_fraud_rate": float(observed[mask].mean()) if count else 0.0,
            "span_days": round(float(t[mask].max() - t[mask].min()) / _MICROS_PER_DAY, 2)
            if count
            else 0.0,
        }
    return out
