"""Anti-leakage and realism checks on a generated dataset (Part E.3, D-08, owner direction).

Gate checks fail the run; report-only checks are measured and shown. The checks read the dataset
month by month and keep numeric columns only, so they run on the full dataset in modest memory.

Leakage is assessed against the observed label (the one a model would train on):

- single-feature AUC (``max(AUC, 1 - AUC)``) at most 0.80 for every raw and cheap per-row feature;
- a shortcut detector: a depth-3 tree on non-behavioural per-row columns only (identifier bytes,
  sub-second timestamp parts, row position within its file) must have a cross-validated AUC
  inside a band sized to that statistic's own null. Folds are grouped by account, so the rows of
  one incident, which share a time, cannot sit on both sides of a split. File (month) order is
  judged alone, because months are calendar time and fraud prevalence drifts by design;
- identifier construction: the same tree over every character of the distinct token values
  (account, counterparty, device), labelled by whether a fraud row uses them, must stay inside a
  band sized to its own null. Tokens are judged per distinct value because victims and mule
  accounts legitimately recur;
- event construction: the account events fraud plants (a SIM swap before a takeover) must be built
  like the legitimate ones. They were not: the microsecond part of the timestamp and the day of the
  month identified a victim's account outright, in a table the release ships. Only those two
  construction properties are gated. Which kind of event it is, and how soon a transaction follows
  it, are the scenario's own signal rather than construction, so they are **measured and reported
  but never gated** — the same line this module already draws when the shortcut detector takes
  non-behavioural columns only, and when file order is judged apart because drift is designed;
- fraud and legitimate rows share value formats and, per channel, null signatures.

Every band that judges a cross-validated tree is sized to that statistic's null rather than fixed,
because a fixed tolerance is several standard errors at release size and a fraction of one at
development size. That held only above a 0.03 floor until the M2 milestone review: the floor was
inert at small samples, where the analytic term is larger anyway, and bound only at large ones,
where it made the gate 3.4x more permissive than the null implies -- reinstating, at release scale,
the defect the null-sized band replaced.
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
from fraudshield_dataset.generator.countries import minor_units_by_currency
from fraudshield_dataset.generator.fraud import NOVEL_VARIANT
from fraudshield_dataset.generator.pipeline import peak_rss_bytes
from fraudshield_dataset.normal import inverse_cdf
from fraudshield_dataset.params import ParameterSet
from fraudshield_dataset.realism.stats import (
    CV_TREE_NULL_INFLATION,
    auc,
    cross_validated_auc,
    cv_auc_null_band,
    separation,
    wilson_interval,
)

SINGLE_FEATURE_AUC_LIMIT = 0.80
SHORTCUT_TOLERANCE = 0.03
# Every kind of account event the generator writes, in a fixed order so the reported type code
# means the same thing from one month and one run to the next.
EVENT_KINDS = ("DEVICE_CHANGE", "SIM_SWAP")
CI_Z = 1.96
# Family-wise level for the identifier-construction bands. Three token columns are tested at once,
# so a per-column 5% band fails about one run in seven by chance; the band is widened to keep the
# 5% for the check as a whole (Bonferroni, two-sided).
FAMILY_ALPHA = 0.05
# Below this many fraud-carrying token values, a construction AUC says nothing either way.
MIN_TOKEN_POSITIVES = 30
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


def _category_rates(
    codes: NDArray[np.int64],
    labels: NDArray[np.bool_],
    groups: NDArray[np.int64],
    *,
    seed: int,
    folds: int = FOLDS,
) -> NDArray[np.float64]:
    """Score each row by its category's fraud rate, computed without that row.

    A categorical has no natural order, so its separation is measured by encoding each category
    with its fraud rate. Encoding on the same rows the AUC is then read from counts a row's own
    label as evidence about itself: with a hundred fraud rows over twenty merchant categories it put
    the reported separation at 0.809 on a 12,000-row run and 0.662 at a million, so the D-08 limit
    appeared to depend on the size of the run (M2 principal review, MINOR 1.6 and addendum). Rates
    are computed out of fold instead, which measures signal rather than self-inclusion.

    Out of fold by *account*, not by row. Removing a row's own label while leaving the other rows of
    its incident in the same estimate fixes the obvious form of the defect and not its structural
    one (M2 milestone review).
    """
    rng = np.random.default_rng(seed)
    # Folds are whole accounts, not individual rows. Fraud arrives as incidents -- several rows
    # sharing an account -- so per-row folds leave an incident's siblings in the "out of fold" rate
    # that scores it. Measured at 1,006,249 rows, that inflated merchant_category_code by 0.005,
    # channel by 0.006 and currency by 0.001 (M2 milestone review). The shortcut detector has always
    # grouped its folds by account for exactly this reason; the encoding did not.
    unique, inverse = np.unique(groups, return_inverse=True)
    assignment = rng.integers(0, folds, unique.size)[inverse]
    width = int(codes.max()) + 1 if codes.size else 1
    overall = float(labels.mean()) if labels.size else 0.0
    scores = np.full(codes.size, overall, dtype=np.float64)
    for fold in range(folds):
        held_out = assignment == fold
        rest = ~held_out
        if not held_out.any() or not rest.any():
            continue
        positives = np.bincount(
            codes[rest], weights=labels[rest].astype(np.float64), minlength=width
        )
        counts = np.bincount(codes[rest], minlength=width)
        # A category the other folds never saw falls back to the overall rate, which is what a
        # model with no information about it would predict.
        rates = np.where(counts > 0, positives / np.maximum(counts, 1), overall)
        scores[held_out] = rates[codes[held_out]]
    return scores


def id_bytes(values: list[str]) -> NDArray[np.float64]:
    """Every byte of a UUID-shaped identifier.

    Two bytes used to be read, the first and the last. A marker written into any of the other
    fourteen was a perfect oracle that every check passed (M2 principal review, MAJOR 1.2), so all
    sixteen are fed to the detector now.
    """
    stripped = [v.replace("-", "") for v in values]
    return np.array(
        [[int(v[i : i + 2], 16) for i in range(0, 32, 2)] for v in stripped], dtype=np.float64
    )


def token_features(values: list[str]) -> NDArray[np.float64]:
    """Every character of a token's body: what construction could leak.

    Three of the thirty-two characters used to be read, so a marker in any of the other
    twenty-nine identified an account without any check noticing (M2 principal review, MAJOR 1.2).
    The length is not a feature: it is the same for every token.
    """
    return np.array([[ord(c) for c in v[4:]] for v in values], dtype=np.float64)


@dataclass(frozen=True)
class _MonthColumns:
    """The per-row arrays one month's shortcut features are built from."""

    micros: NDArray[np.int64]
    observed: NDArray[np.bool_]
    ids: list[str]
    keep: NDArray[np.int64]
    delay: NDArray[np.int64]


def _sampled(transaction_id: str, seed: int) -> bool:
    digest = hashlib.blake2b(f"{seed}:{transaction_id}".encode(), digest_size=8).digest()
    return int.from_bytes(digest, "big") % 1000 < LEGITIMATE_SAMPLE_PER_MILLE


def _account_group(account: str) -> int:
    return int.from_bytes(hashlib.blake2b(account.encode(), digest_size=7).digest(), "big")


class Dataset:
    """One pass over the months of a generated dataset, accumulating what the checks need."""

    def __init__(self, root: Path, config: SimulationConfig) -> None:
        self.config = config
        self.offsets = {c: float(k.utc_offset_hours) for c, k in config.packs.items()}
        self.minor_units = minor_units_by_currency(config.packs)
        self.country_of_currency = {pack.currency: code for code, pack in config.packs.items()}
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
        self.event_features = Columns()
        # The two channels excluded from the event gate, measured for the report only.
        self.event_reported = Columns()
        self.event_accounts: list[str] = []
        self.fraud_accounts: set[str] = set()
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
            self._month_events(root, month, transactions)
            self.rows_by_month[month] = transactions.num_rows
            monthly = self.fraud_types_by_month.setdefault(month, {})
            for kind in labels.filter(labels["is_fraud_true"])["fraud_type"].to_pylist():
                monthly[kind] = monthly.get(kind, 0) + 1
        feature_labels = self.feature_labels
        feature_groups = np.concatenate(self.shortcut_groups)
        for name in _CATEGORICALS:
            codes = self.features.get(name).astype(np.int64)
            self.features.replace(
                name, _category_rates(codes, feature_labels, feature_groups, seed=config.seed)
            )

    @property
    def observed(self) -> NDArray[np.bool_]:
        return np.concatenate(self.observed_parts)

    def _month_events(self, root: Path, month: str, transactions: pa.Table) -> None:
        """Account events, scored by whether their account ever carries observed fraud.

        The events a fraud scenario plants (a SIM swap before a takeover) must be built like the
        legitimate ones. When they were not, the microsecond part of the timestamp and the day of
        the month identified a victim's account outright, in a table the release ships and the
        datasheet invites joining (M2 principal review, BLOCKER 1.1).

        Only construction is **gated** here: the microsecond part of the timestamp and the day of
        the month. Two things are deliberately left out of the gate because they are the scenario's
        own signal, which a model is meant to learn rather than be protected from: how soon a
        transaction follows the event, and which kind of event it is. A SIM swap before a takeover
        is how that fraud works.

        Both excluded channels are nevertheless **measured and reported** (ungated), because the
        delta re-check found they were previously judged by nothing at all while the justification
        for excluding them quoted only the smaller of the two. At 60,355 rows, account-level labels
        and the gate's own out-of-fold estimator: delay alone 0.709, type alone 0.581, against the
        gated construction pair at 0.544. The delay figure is the larger channel and is the one the
        earlier "0.537 on its own" wording did not measure.

        The delay is not purely a consequence of the scenario: ``fraud.takeover_lead_minutes`` is
        ``[5, 60]`` and ``provenance: ASSUMED``, so every enabling event is followed by its drain
        inside a tight uniform window with no long tail and no unexploited swaps. Part designed
        causal signal, part artefact of an assumed schedule, and reporting it keeps that visible
        rather than asserted.

        Delay is measured to the account's next transaction **within the same month**, which keeps
        the one-month-at-a-time memory property; an event with no later transaction in its own
        month is left out of the delay measure only.
        """
        path = root / "account_events" / f"month={month}" / "part-0000.parquet"
        if not path.exists():
            return
        events = pq.read_table(path)
        if not events.num_rows:
            return
        micros = events["event_timestamp"].cast(pa.int64()).to_numpy()
        self.event_features.add("event_sub_second", micros % 1_000_000)
        self.event_features.add("event_day_of_month", pc.day(events["event_timestamp"]).to_numpy())
        accounts = events["account_id"].to_pylist()
        self.event_accounts.extend(accounts)
        self._event_reported(accounts, micros, events["event_type"].to_pylist(), transactions)

    def _event_reported(
        self,
        accounts: list[str],
        micros: NDArray[np.int64],
        kinds: list[str],
        transactions: pa.Table,
    ) -> None:
        """The two excluded channels, measured for the report and gated by nothing.

        See :meth:`_month_events`. Delay is seconds to the account's next transaction in this
        month; ``-1`` marks an event with no later transaction here, and those rows are dropped
        before the AUC is taken.
        """
        by_account: dict[str, list[int]] = {}
        tx_micros = transactions["transaction_timestamp"].cast(pa.int64()).to_numpy()
        for account, stamp in zip(transactions["account_id"].to_pylist(), tx_micros, strict=True):
            by_account.setdefault(account, []).append(int(stamp))
        for account_stamps in by_account.values():
            account_stamps.sort()
        delays = np.empty(len(accounts), dtype=np.float64)
        for index, (account, stamp) in enumerate(zip(accounts, micros, strict=True)):
            later = by_account.get(account, [])
            position = int(np.searchsorted(later, stamp))
            delays[index] = (later[position] - stamp) / 1_000_000 if position < len(later) else -1.0
        self.event_reported.add("event_delay_seconds", delays)
        self.event_reported.add(
            "event_type_code",
            np.array(
                [EVENT_KINDS.index(k) if k in EVENT_KINDS else len(EVENT_KINDS) for k in kinds],
                dtype=np.float64,
            ),
        )

    @property
    def event_labels(self) -> NDArray[np.bool_]:
        """Whether each event's account carries an observed-fraud row anywhere in the dataset.

        Resolved after every month has been read, so an event in January is judged against the
        whole dataset's fraud accounts rather than against the ones seen so far.
        """
        return np.array([account in self.fraud_accounts for account in self.event_accounts])

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
        self._shortcut_columns(
            t,
            _MonthColumns(
                micros=micros,
                observed=observed,
                ids=ids,
                keep=keep,
                delay=labels["label_available_at"].cast(pa.int64()).to_numpy() - micros,
            ),
        )
        self._identifier_tokens(t, observed)
        self.fraud_accounts.update(
            account
            for account, flag in zip(t["account_id"].to_pylist(), observed, strict=True)
            if flag
        )
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
            if (amount * 10 ** self.minor_units[columns["currency"][i]]) % 1 != 0:
                v["amount_scale"] += 1
            if round(columns["latitude"][i], 6) != columns["latitude"][i]:
                v["coordinate_precision"] += 1
            group = "fraud" if true[i] else "legitimate"
            self.null_signatures[group].setdefault(columns["channel"][i], set()).add(
                (columns["device_fingerprint"][i] is None, columns["agent_id"][i] is None)
            )

    def _shortcut_columns(self, t: pa.Table, month: _MonthColumns) -> None:
        micros, observed, ids, keep = month.micros, month.observed, month.ids, month.keep
        kept_ids = [ids[i] for i in keep]
        accounts = t["account_id"].to_pylist()
        for position, column in enumerate(id_bytes(kept_ids).T):
            self.shortcut.add(f"transaction_id_byte_{position:02d}", column)
        self.shortcut.add("timestamp_microseconds", micros[keep] % 1_000_000)
        self.shortcut.add("row_position_in_file", keep / max(t.num_rows - 1, 1))
        # When a label became available is drawn independently of the label; nothing checked that.
        self.shortcut.add("label_delay_micros", month.delay[keep])
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


def _reported_event_auc(
    data: Dataset, event_labels: NDArray[np.bool_], config: SimulationConfig
) -> dict[str, float]:
    """The two channels the event gate excludes, measured on the gate's own footing.

    Account-level labels and the same out-of-fold estimator the gate uses, so these numbers are
    directly comparable to it. Reported, never gated: a SIM swap before a takeover is signal a
    model should learn. They are measured because the M2 delta re-check found both judged by
    nothing at all, while the written justification quoted only the smaller of the two.
    """
    measured: dict[str, float] = {}
    positives = int(event_labels.sum())
    if not positives or positives == event_labels.size:
        return measured
    for channel in data.event_reported.names:
        column = data.event_reported.get(channel)
        # -1 marks an event with no later transaction in its own month; see _event_reported.
        usable = column >= 0.0 if channel == "event_delay_seconds" else np.ones(column.size, bool)
        channel_labels, channel_values = event_labels[usable], column[usable]
        if 0 < int(channel_labels.sum()) < channel_labels.size:
            measured[channel] = cross_validated_auc(
                channel_values.reshape(-1, 1), channel_labels, folds=FOLDS, seed=config.seed
            )
    return measured


def _reported_event_results(measured: dict[str, float]) -> list[CheckResult]:
    """The two excluded event channels, reported and never gated.

    See :func:`_reported_event_auc` for why they are measured at all.
    """

    def value(name: str) -> str:
        return f"AUC {measured[name]:.3f}" if name in measured else "not measurable at this size"

    return [
        CheckResult(
            "event delay (reported)",
            True,
            False,
            value("event_delay_seconds"),
            "reported, not gated: seconds from an event to that account's next transaction",
            "excluded from the event gate as the scenario's own signal, so it is tracked here "
            "instead. The lead is drawn from fraud.takeover_lead_minutes = [5, 60], which is "
            "ASSUMED, so part of this separation is the assumed schedule rather than the scenario",
        ),
        CheckResult(
            "event type (reported)",
            True,
            False,
            value("event_type_code"),
            "reported, not gated: SIM swap versus device change",
            "a SIM swap before a takeover is how that fraud works and a model is meant to learn "
            "it; measured so the claim is a number rather than an assertion",
        ),
    ]


def _shortcut_detector(
    data: Dataset, matrix: NDArray[np.float64], config: SimulationConfig, measures: dict[str, Any]
) -> tuple[float, float]:
    """The depth-3 tree over non-behavioural columns, and the band its own null implies.

    The band was ``max(0.03, ...)`` until the M2 milestone review. The floor never bound where it
    was meant to -- the analytic term grows as positives shrink -- and bound only at large samples,
    making the gate 3.4x more permissive than the null implies at release scale.
    """
    labels = np.concatenate(data.shortcut_labels)
    groups = np.concatenate(data.shortcut_groups)
    detector = cross_validated_auc(matrix, labels, folds=FOLDS, seed=config.seed, groups=groups)
    positives = int(labels.sum())
    band = cv_auc_null_band(positives, labels.size - positives, inverse_cdf(1.0 - FAMILY_ALPHA / 2))
    # Recorded so a band can be recomputed for any inflation factor without regenerating.
    measures["shortcut_positives"] = positives
    measures["shortcut_negatives"] = int(labels.size - positives)
    measures["shortcut_detector_auc"] = detector
    measures["shortcut_detector_band"] = band
    measures["shortcut_features"] = data.shortcut.names
    return detector, band


def _leakage_checks(
    data: Dataset, config: SimulationConfig, full: bool, measures: dict[str, Any]
) -> list[CheckResult]:
    observed = data.observed
    feature_labels = data.feature_labels
    single = {
        name: separation(data.features.get(name), feature_labels) for name in data.features.names
    }
    worst = max(single, key=lambda k: single[k])
    measures["single_feature_auc"] = single

    matrix = np.column_stack([data.shortcut.get(n) for n in data.shortcut.names])
    detector, shortcut_band = _shortcut_detector(data, matrix, config, measures)

    event_labels = data.event_labels
    event_names = data.event_features.names
    event_positives = int(event_labels.sum())
    if event_names and 0 < event_positives < event_labels.size:
        event_matrix = np.column_stack([data.event_features.get(n) for n in event_names])
        event_auc = cross_validated_auc(event_matrix, event_labels, folds=FOLDS, seed=config.seed)
        event_band = cv_auc_null_band(
            event_positives,
            event_labels.size - event_positives,
            inverse_cdf(1.0 - FAMILY_ALPHA / 2),
        )
    else:
        event_auc, event_band = 0.5, SHORTCUT_TOLERANCE
    measures["event_construction_auc"] = event_auc
    measures["event_construction_band"] = event_band
    measures["event_features"] = list(event_names)
    measures["event_counts"] = {
        "events": int(event_labels.size),
        "on_fraud_accounts": event_positives,
    }

    reported_event_auc = _reported_event_auc(data, event_labels, config)
    measures["event_reported_auc"] = reported_event_auc
    reported_event_results = _reported_event_results(reported_event_auc)

    file_order = separation(np.concatenate(data.file_index_parts), observed)
    measures["file_order_auc"] = file_order

    construction = {}
    token_bands = {}
    token_positives: dict[str, int] = {}
    columns = len(data.tokens)
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
        # Sized to this statistic's own null. It used to be the wider of that and a fixed 0.03,
        # which never bound where it was meant to -- the analytic term grows as positives shrink --
        # and bound only at large samples, where it made the gate 3.4x more permissive than the
        # null implies (M2 milestone review). Too few fraud tokens is handled by MIN_TOKEN_POSITIVES
        # reporting the column unjudged, not by widening the band.
        token_bands[column] = cv_auc_null_band(positives, len(values) - positives, family_z)
        token_positives[column] = positives
    # A column with almost no fraud tokens cannot be judged; saying so beats passing vacuously.
    unjudged = sorted(c for c, n in token_positives.items() if n < MIN_TOKEN_POSITIVES)
    measures["identifier_construction_auc"] = construction
    measures["identifier_construction_band"] = token_bands
    measures["identifier_unjudged_columns"] = unjudged
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
            abs(detector - 0.5) <= shortcut_band,
            True,
            f"AUC {detector:.3f} (band +/-{shortcut_band:.3f}) on {matrix.shape[0]} rows",
            "within its null band around 0.5 (owner direction)",
            f"depth-3 tree, 5-fold CV grouped by account, {len(data.shortcut.names)} features: "
            + ", ".join(data.shortcut.names),
        ),
        CheckResult(
            "event construction",
            abs(event_auc - 0.5) <= event_band,
            True,
            f"AUC {event_auc:.3f} (band +/-{event_band:.3f}) on {event_labels.size} events, "
            f"{event_positives} on fraud accounts",
            "an account event's construction does not reveal a victim's account (D-08)",
            "sub-second part and day of month of each SIM swap or device change. Which kind of "
            "event it is, and how soon a transaction follows, are the scenario's own signals and "
            "are deliberately not gated; both are measured and reported below",
        ),
        *reported_event_results,
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
            all(abs(construction[k] - 0.5) <= token_bands[k] for k in construction)
            and not (full and unjudged),
            True,
            ", ".join(
                f"{k} {v:.3f} (band +/-{token_bands[k]:.3f})" for k, v in construction.items()
            )
            + (f"; too few fraud tokens to judge: {', '.join(unjudged)}" if unjudged else ""),
            "token characters do not identify fraud tokens, within the null band",
            f"depth-3 tree, 5-fold CV over every character of {columns} token columns; band is "
            f"z x {CV_TREE_NULL_INFLATION} SE with z for a family-wise "
            f"{FAMILY_ALPHA:.0%} level over those columns. A release run must be able to judge "
            f"every column ({MIN_TOKEN_POSITIVES}+ fraud tokens)",
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
            not before,
            True,
            f"{len(data.novel_timestamps)} rows, {len(before)} before the test start",
            "never outside the temporal hold-out test period (D-08)",
        ),
        CheckResult(
            "novel sub-variant present",
            bool(data.novel_timestamps),
            full,
            f"{len(data.novel_timestamps)} rows",
            "at least one novel sub-variant row in a release run (D-08)",
            "a development run can be too small to contain one; the placement rule above is "
            "always a gate, presence only at release size",
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
            full,
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


def check_digest(results: list[CheckResult]) -> str:
    """SHA-256 of the check set: each check's name, what it requires, and whether it gates.

    ``parameter_digest`` closes one door and left another open. It hashes parameter *values*, so a
    committed report generated before a check existed still matched the repository and passed the
    guard while describing a run that never performed that check. Adding the two reported event
    channels produced exactly that state: no parameter moved, the digest still matched, and the
    shipped report silently omitted two checks the code runs (M2 delta re-check).

    Semantics are digested, not just names, so that renaming what a check *requires* — the sentence
    a reader relies on — invalidates a stale report too. The measured values are deliberately left
    out: they legitimately differ between runs, and digesting them would make every report stale.
    """
    payload = json.dumps(
        [
            {"name": r.name, "requirement": r.requirement, "gate": r.gate}
            for r in sorted(results, key=lambda r: r.name)
        ],
        sort_keys=True,
    )
    return hashlib.sha256(payload.encode()).hexdigest()


def parameter_digest(parameters: ParameterSet) -> str:
    """SHA-256 of every parameter value, so a report states which parameters produced it.

    The committed report described a superseded parameter set for two commits, and its own footer
    said no parameter was sourced while twelve were (M2 principal review, MAJOR 4.1). A digest makes
    that drift a test failure rather than something a reader has to notice.
    """
    payload = json.dumps(
        {p.key: p.value for p in sorted(parameters, key=lambda x: x.key)},
        sort_keys=True,
        default=str,
    )
    return hashlib.sha256(payload.encode()).hexdigest()


def run_checks(
    root: Path, config: SimulationConfig, full: bool
) -> tuple[list[CheckResult], dict[str, Any]]:
    """Run every check. ``full`` gates the size and distribution targets (5M+ rows, +/- 0.5 pp)."""
    data = Dataset(root, config)
    measures: dict[str, Any] = {}
    results = (
        _leakage_checks(data, config, full, measures)
        + _label_and_format_checks(data, config, full, measures)
        + _distribution_checks(data, root, config, full, measures)
    )
    # Measured, not assumed: the checks read the dataset month by month and keep per-row arrays
    # plus a sample of the features, so their cost is reported next to the generator's.
    measures["checks_peak_rss_bytes"] = peak_rss_bytes()
    measures["parameter_values_sha256"] = parameter_digest(config.parameters)
    measures["check_set_sha256"] = check_digest(results)
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
