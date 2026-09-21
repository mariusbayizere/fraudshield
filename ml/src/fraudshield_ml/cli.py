"""``fs-features``: run the feature pipeline against a generated dataset (PB-44, ML-DATA-07).

Reads the dataset's **published output** — Parquet partitions and the pack facts
``fs-dataset packs`` writes — and never imports the generator. That is the same arrangement the
dataset's own consumers have, and it is deliberate: a feature pipeline that imported the producer
could read values the published format does not carry, and would then work here and fail on a
release.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import pyarrow.parquet as pq

from fraudshield_ml.features.registry import REGISTRY, Dtype
from fraudshield_ml.features.types import CountryFacts, Outcome, Transaction
from fraudshield_ml.features.vector import (
    CorpusIndex,
    FeatureContext,
    computability,
    compute,
    describe,
)
from fraudshield_ml.metrics.single_feature import (
    SINGLE_FEATURE_AUC_LIMIT,
    auc_standard_error,
    class_counts,
    out_of_fold_target_encoding,
    separation,
)
from fraudshield_ml.training import evaluation, smoke
from fraudshield_ml.training import split as split_module

#: Columns the vector needs from each table. Named rather than read wholesale so that a schema
#: change removing one fails here, with the column named, instead of somewhere inside a feature.
TRANSACTION_COLUMNS = (
    "transaction_id",
    "account_id",
    "counterparty_id",
    "amount",
    "currency",
    "amount_rwf",
    "channel",
    "merchant_category_code",
    "latitude",
    "longitude",
    "device_fingerprint",
    "agent_id",
    "counterparty_country",
    "transaction_timestamp",
)
LABEL_COLUMNS = ("transaction_id", "is_fraud_observed", "label_available_at")
EVENT_COLUMNS = ("account_id", "event_type", "event_timestamp")

SIM_SWAP = "SIM_SWAP"
CASH_DISBURSEMENT_MCC = "6011"


class DatasetGapError(RuntimeError):
    """The dataset cannot answer something the feature pipeline needs, and says which."""


def load_packs(path: Path) -> dict[str, CountryFacts]:
    facts = json.loads(path.read_text(encoding="utf-8"))
    return {
        code: CountryFacts(
            alpha2=str(entry["alpha2"]),
            continent=str(entry["continent"]),
            blocs=frozenset(str(b) for b in entry["blocs"]),
            utc_offset_hours=int(entry["utc_offset_hours"]),
        )
        for code, entry in facts.items()
    }


def minor_units_by_currency(path: Path) -> dict[str, int]:
    """How many decimal places each currency is quoted to, from the packs.

    `round_sum_flag` asks whether an amount is an exact multiple of a denomination, and "exact" is
    a property of integers. The stored `amount` is a decimal with four places regardless of the
    currency, so converting it needs the currency's own scale: 12.34 in a two-place currency is
    1,234 minor units and in a zero-place one it is 12. Truncating without the scale would make
    every amount in a two-place currency a multiple of everything below its integer part.
    """
    facts = json.loads(path.read_text(encoding="utf-8"))
    return {str(entry["currency"]): int(entry["currency_minor_units"]) for entry in facts.values()}


def denominations_by_currency(path: Path) -> dict[str, tuple[int, ...]]:
    """Common denominations in minor units, keyed by currency, from the published packs (PB-44).

    `round_sum_flag` was NO_SOURCE_DATA for the whole of M3 not because it was unimplemented — it
    has been on both paths since the group landed — but because nothing supplied this table. It is
    read from `packs.json` rather than from the generator's YAML for the reason every other pack
    fact is: the feature pipeline consumes the published interchange format and never imports
    `fraudshield_dataset`.

    Two packs sharing a currency must agree, and disagreement is refused rather than resolved:
    picking one would make the flag depend on which pack was read first.
    """
    facts = json.loads(path.read_text(encoding="utf-8"))
    table: dict[str, tuple[int, ...]] = {}
    for code, entry in sorted(facts.items()):
        currency = str(entry["currency"])
        steps = tuple(int(v) for v in entry["round_denominations"])
        existing = table.setdefault(currency, steps)
        if existing != steps:
            raise DatasetGapError(
                f"{currency} has two denomination tables in the packs ({list(existing)} and "
                f"{list(steps)} from {code}); roundness would depend on which pack was read first"
            )
    return table


def country_by_currency(path: Path) -> dict[str, str]:
    """Which country a transaction happened in, recovered from its currency.

    **The dataset does not carry the sender's country.** `transactions` has
    `counterparty_country` and nothing for the account's own, so the six features that need a UTC
    offset or a corridor's origin have no column to read. The generator derives a transaction's
    currency *from* the customer's country, so the map inverts exactly — while currencies are
    distinct across the simulated packs.

    That last clause is the whole risk, so it is a refusal rather than a caveat: two packs sharing
    a currency (XOF across West Africa is the obvious case) makes the inversion ambiguous, and an
    ambiguous inversion would silently place transactions in the wrong country and shift every
    local-time feature by hours. Recorded as a dataset gap rather than a feature gap — the honest
    fix is a column, not a cleverer inference.
    """
    facts = json.loads(path.read_text(encoding="utf-8"))
    mapping: dict[str, str] = {}
    for code, entry in sorted(facts.items()):
        currency = str(entry["currency"])
        if currency in mapping:
            raise DatasetGapError(
                f"{mapping[currency]} and {code} share the currency {currency!r}, so a "
                "transaction's country cannot be recovered from it. The dataset carries no "
                "account-country column; the fix is that column, not a tie-break here"
            )
        mapping[currency] = code
    return mapping


def read_transactions(root: Path, packs: Path, limit: int) -> list[Transaction]:
    """The last `limit` transactions in timestamp order, as feature records.

    Partitions are read **newest first and only as far as needed**, because a release-size dataset
    does not fit in memory as Python objects and a command that only works below a million rows is
    a command that will not be run on the release.

    One partition beyond the requirement is always read. The partition key is the simulated local
    month while timestamps are UTC (PB-26), so a row near a boundary can sit one month early by up
    to three hours; stopping exactly at the requirement would drop those rows from the tail rather
    than order them.
    """
    origin = country_by_currency(packs)
    minor_units = minor_units_by_currency(packs)
    rows: list[Transaction] = []
    partitions = sorted((root / "transactions").glob("month=*/part-*.parquet"), reverse=True)
    for taken, part in enumerate(partitions, start=1):
        table = pq.read_table(part, columns=list(TRANSACTION_COLUMNS))
        columns = {name: table.column(name).to_pylist() for name in TRANSACTION_COLUMNS}
        for i in range(table.num_rows):
            currency = str(columns["currency"][i])
            if currency not in origin:
                raise DatasetGapError(
                    f"no pack declares the currency {currency!r}, so the transaction's country "
                    "is unknown; run fs-dataset packs against the same parameters"
                )
            rows.append(
                Transaction(
                    transaction_id=str(columns["transaction_id"][i]),
                    account_id=str(columns["account_id"][i]),
                    timestamp=columns["transaction_timestamp"][i],
                    amount_rwf=float(columns["amount_rwf"][i]),
                    latitude=float(columns["latitude"][i]),
                    longitude=float(columns["longitude"][i]),
                    account_country=origin[currency],
                    counterparty_country=str(columns["counterparty_country"][i]),
                    counterparty_id=str(columns["counterparty_id"][i]),
                    amount_minor=int(columns["amount"][i].scaleb(minor_units[currency])),
                    currency=currency,
                    channel=str(columns["channel"][i]),
                    device_fingerprint=_optional_text(columns["device_fingerprint"][i]),
                    agent_id=_optional_text(columns["agent_id"][i]),
                    merchant_category_code=str(columns["merchant_category_code"][i]),
                )
            )
        # `taken > 1` is the extra partition: enough rows AND one more month behind them.
        if limit and len(rows) >= limit and taken > 1:
            break
    rows.sort(key=lambda row: (row.timestamp, row.transaction_id))
    return rows[-limit:] if limit and len(rows) > limit else rows


def _optional_text(value: object) -> str | None:
    return None if value is None else str(value)


def read_outcomes(root: Path, keep: set[str]) -> dict[str, Outcome]:
    outcomes: dict[str, Outcome] = {}
    for part in sorted((root / "labels").glob("month=*/part-*.parquet")):
        table = pq.read_table(part, columns=list(LABEL_COLUMNS))
        ids = table.column("transaction_id").to_pylist()
        fraud = table.column("is_fraud_observed").to_pylist()
        available = table.column("label_available_at").to_pylist()
        for i, transaction_id in enumerate(ids):
            if transaction_id in keep:
                outcomes[str(transaction_id)] = Outcome(
                    transaction_id=str(transaction_id),
                    is_fraud=bool(fraud[i]),
                    available_at=available[i],
                )
    return outcomes


def read_first_seen(root: Path) -> tuple[dict[str, datetime], dict[str, datetime]]:
    """Each account's first transaction and each device's first sighting, over **all** partitions.

    This is the batch equivalent of consulting the durable store (PB-37), and it is read from the
    whole dataset rather than from the corpus the features are computed on. The distinction is the
    entire point: a truncated corpus's earliest row for an account is the window's edge, and
    `velocity_ratio_1h_vs_30d` divides by observed history, so inferring first-seen from it
    inflates the ratio for exactly the accounts that look newest.

    Cheap despite reading everything: three columns, a running minimum, nothing retained per row.
    An account absent here has no first-seen and its ratio is NaN, which is the honest output and
    the same one the online path gives.
    """
    first_seen: dict[str, datetime] = {}
    device_first_seen: dict[str, datetime] = {}
    columns = ["account_id", "device_fingerprint", "transaction_timestamp"]
    for part in sorted((root / "transactions").glob("month=*/part-*.parquet")):
        table = pq.read_table(part, columns=columns)
        accounts = table.column("account_id").to_pylist()
        devices = table.column("device_fingerprint").to_pylist()
        stamps = table.column("transaction_timestamp").to_pylist()
        for account, device, when in zip(accounts, devices, stamps, strict=True):
            key = str(account)
            if key not in first_seen or when < first_seen[key]:
                first_seen[key] = when
            if device is not None:
                fingerprint = str(device)
                if fingerprint not in device_first_seen or when < device_first_seen[fingerprint]:
                    device_first_seen[fingerprint] = when
    return first_seen, device_first_seen


def read_known_before(
    root: Path, corpus_start: datetime
) -> tuple[dict[str, frozenset[str]], dict[str, frozenset[str]], dict[str, frozenset[str]]]:
    """What each account had already used before `corpus_start`: payees, corridors, devices.

    The three novelty flags declare **unbounded** history, so a corpus that begins part-way through
    an account's life cannot answer them: every long-standing payee, corridor and handset looks
    new, and it looks newest for the accounts with the longest histories. That is the same defect
    as inferring a first-seen timestamp from a truncated corpus, in a boolean.

    Read from every partition earlier than the corpus, which is what the online path's durable
    sets hold and what `restore_counterparties`, `restore_countries` and `restore_devices` exist
    to reload after a flush.
    """
    counterparties: dict[str, set[str]] = {}
    countries: dict[str, set[str]] = {}
    devices: dict[str, set[str]] = {}
    columns = [
        "account_id",
        "counterparty_id",
        "counterparty_country",
        "device_fingerprint",
        "transaction_timestamp",
    ]
    for part in sorted((root / "transactions").glob("month=*/part-*.parquet")):
        table = pq.read_table(part, columns=columns)
        values = {name: table.column(name).to_pylist() for name in columns}
        for i in range(table.num_rows):
            if values["transaction_timestamp"][i] >= corpus_start:
                continue
            account = str(values["account_id"][i])
            counterparties.setdefault(account, set()).add(str(values["counterparty_id"][i]))
            countries.setdefault(account, set()).add(str(values["counterparty_country"][i]))
            device = values["device_fingerprint"][i]
            if device is not None:
                devices.setdefault(account, set()).add(str(device))
    return (
        {k: frozenset(v) for k, v in counterparties.items()},
        {k: frozenset(v) for k, v in countries.items()},
        {k: frozenset(v) for k, v in devices.items()},
    )


def read_sim_swaps(root: Path) -> dict[str, list[datetime]]:
    """SIM swaps per account, from `account_events`.

    This is the join that makes `days_since_sim_swap` computable, and forgetting it is exactly the
    failure the computability check reports: the feature would be NaN for every row while nothing
    raised.
    """
    swaps: dict[str, list[datetime]] = {}
    for part in sorted((root / "account_events").glob("month=*/part-*.parquet")):
        table = pq.read_table(part, columns=list(EVENT_COLUMNS))
        accounts = table.column("account_id").to_pylist()
        kinds = table.column("event_type").to_pylist()
        when = table.column("event_timestamp").to_pylist()
        for i, kind in enumerate(kinds):
            if str(kind) == SIM_SWAP:
                swaps.setdefault(str(accounts[i]), []).append(when[i])
    return swaps


def run_computability(root: Path, packs: Path, corpus_rows: int, sample_rows: int) -> int:
    rows = read_transactions(root, packs, limit=corpus_rows)
    if len(rows) < sample_rows * 2:
        raise DatasetGapError(
            f"read {len(rows)} transactions, too few to score {sample_rows} with history behind "
            "them; raise --corpus-rows or lower --sample-rows"
        )
    first_seen, device_first_seen = read_first_seen(root)
    payees, corridors, handsets = read_known_before(root, rows[0].timestamp)
    context = FeatureContext(
        countries=load_packs(packs),
        outcomes=read_outcomes(root, {row.transaction_id for row in rows}),
        sim_swaps=read_sim_swaps(root),
        first_seen=first_seen,
        device_first_seen=device_first_seen,
        counterparties_before=payees,
        countries_before=corridors,
        devices_before=handsets,
        cash_out_codes=frozenset({CASH_DISBURSEMENT_MCC}),
        denominations=denominations_by_currency(packs),
        cell_rate_prior=0.0087,
    )
    sample = list(range(len(rows) - sample_rows, len(rows)))
    result = computability(rows, context, sample=sample)
    print(f"corpus: {len(rows)} transactions from {root}")
    print(describe(result))
    return 0 if result.ok else 1


def run_auc(root: Path, packs: Path, corpus_rows: int, sample_rows: int) -> int:
    """Single-feature AUC over the 44, at a stated scale (E3), including the event join (E6).

    Every figure is printed with the corpus size and the scored-row count beside it, because E2
    makes a metric quoted without its scale inadmissible — single-feature AUC on this benchmark
    moves with dataset size by more than seed noise and the cause is unexplained.
    """
    rows = read_transactions(root, packs, limit=corpus_rows)
    if len(rows) < sample_rows * 2:
        raise DatasetGapError(
            f"read {len(rows)} transactions, too few to score {sample_rows} with history behind "
            "them; raise --corpus-rows or lower --sample-rows"
        )
    outcomes = read_outcomes(root, {row.transaction_id for row in rows})
    first_seen, device_first_seen = read_first_seen(root)
    payees, corridors, handsets = read_known_before(root, rows[0].timestamp)
    context = FeatureContext(
        countries=load_packs(packs),
        outcomes=outcomes,
        sim_swaps=read_sim_swaps(root),
        first_seen=first_seen,
        device_first_seen=device_first_seen,
        counterparties_before=payees,
        countries_before=corridors,
        devices_before=handsets,
        cash_out_codes=frozenset({CASH_DISBURSEMENT_MCC}),
        denominations=denominations_by_currency(packs),
        cell_rate_prior=0.0087,
    )

    sample = list(range(len(rows) - sample_rows, len(rows)))
    corpus_index = CorpusIndex.build(rows)
    vectors = [compute(rows, i, context, corpus_index) for i in sample]
    labels = [bool(outcomes[rows[i].transaction_id].is_fraud) for i in sample]
    accounts = [rows[i].account_id for i in sample]
    positives = sum(labels)
    if positives == 0:
        raise DatasetGapError(
            f"no confirmed fraud among the {len(sample)} scored rows, so every AUC would be "
            "undefined and the ceiling would pass over nothing (ADR 0009); widen --sample-rows"
        )

    print(f"single-feature separation: corpus {len(rows)}, scored {len(sample)}, fraud {positives}")
    print(
        f"  ceiling max(AUC, 1-AUC) <= {SINGLE_FEATURE_AUC_LIMIT} (D-08), folds grouped by account"
    )
    over = []
    for name in sorted(REGISTRY):
        raw = [vector[name] for vector in vectors]
        if REGISTRY[name].dtype is Dtype.CATEGORICAL:
            scores = out_of_fold_target_encoding([str(value) for value in raw], labels, accounts)
            note = "  (out-of-fold target encoding, account-grouped)"
        else:
            scores = [float(value) for value in raw if not isinstance(value, str)]
            note = ""
        value = separation(scores, labels)
        scored_positives, scored_negatives = class_counts(scores, labels)
        usable = scored_positives + scored_negatives
        if math.isnan(value):
            print(f"  {name:38s}      -- ({usable} usable rows){note}")
            continue
        error = auc_standard_error(value, scored_positives, scored_negatives)
        interval = "" if math.isnan(error) else f" +/-{1.96 * error:.3f}"
        if value > SINGLE_FEATURE_AUC_LIMIT:
            flag = "  ** OVER THE CEILING **"
            over.append(name)
        elif not math.isnan(error) and value + 1.96 * error > SINGLE_FEATURE_AUC_LIMIT:
            # Under the ceiling, but not by more than the sample can resolve. Reported rather than
            # failed: the point estimate is what the criterion tests, and pretending the interval
            # is not there would be the same sentence as pretending it does not overlap.
            flag = "  (interval reaches the ceiling)"
        else:
            flag = ""
        print(
            f"  {name:38s} {value:6.3f}{interval} "
            f"({scored_positives} fraud of {usable} usable){note}{flag}"
        )

    if over:
        print(f"ERROR over the D-08 ceiling: {', '.join(over)}", file=sys.stderr)
        return 1
    print(f"OK no feature separates the classes beyond {SINGLE_FEATURE_AUC_LIMIT}")
    return 0


def _build_context(root: Path, packs: Path, rows: list[Transaction]) -> FeatureContext:
    """Everything the 44 features read that is not a transaction column.

    Extracted because three commands built it identically and a fourth would have been a fourth
    place to forget a field — which is precisely how `denominations` went missing from two tests
    and read as a leak.
    """
    first_seen, device_first_seen = read_first_seen(root)
    payees, corridors, handsets = read_known_before(root, rows[0].timestamp)
    return FeatureContext(
        countries=load_packs(packs),
        outcomes=read_outcomes(root, {row.transaction_id for row in rows}),
        sim_swaps=read_sim_swaps(root),
        first_seen=first_seen,
        device_first_seen=device_first_seen,
        counterparties_before=payees,
        countries_before=corridors,
        devices_before=handsets,
        cash_out_codes=frozenset({CASH_DISBURSEMENT_MCC}),
        denominations=denominations_by_currency(packs),
        cell_rate_prior=0.0087,
    )


@dataclass(frozen=True)
class EvaluationRun:
    """What decides an evaluation's figures, in one object (PB-49)."""

    dataset: Path
    packs: Path
    split: Path
    corpus_rows: int
    train_rows: int
    test_rows: int
    seed: int


def run_evaluate(run: EvaluationRun) -> int:
    """Fit on the train period, score on the test period, never touch the embargo (PB-49).

    The sample is the **tail** of each period rather than a draw from all of it: the corpus is
    bounded, the feature pass is the whole cost of this command, and a uniform draw over the train
    period would need every row of it in the index. That is a limit on training volume, not on the
    split, and the report states it beside every figure rather than leaving it to be assumed.
    """
    boundaries = split_module.load(run.split)
    rows = read_transactions(run.dataset, run.packs, limit=run.corpus_rows)
    context = _build_context(run.dataset, run.packs, rows)

    by_segment: dict[split_module.Segment, list[int]] = {}
    for index, row in enumerate(rows):
        by_segment.setdefault(boundaries.segment_of(row.timestamp), []).append(index)
    train_pool = by_segment.get(split_module.Segment.TRAIN, [])
    test_pool = by_segment.get(split_module.Segment.TEST, [])
    for name, pool, wanted in (
        ("train", train_pool, run.train_rows),
        ("test", test_pool, run.test_rows),
    ):
        if len(pool) < wanted:
            raise DatasetGapError(
                f"the corpus holds {len(pool)} rows in the {name} period and {wanted} were asked "
                f"for. Raise --corpus-rows so the read reaches further back, or lower "
                f"--{name}-rows. The corpus is read newest-first, so the train period is the part "
                "that runs out first"
            )
    train_index = train_pool[-run.train_rows :]
    test_index = test_pool[-run.test_rows :]

    sample = train_index + test_index
    corpus_index = CorpusIndex.build(rows)
    print(f"computing {len(smoke.trainable_features())} features for {len(sample)} rows...")
    started = time.monotonic()
    vectors = []
    for done, i in enumerate(sample, start=1):
        vectors.append(compute(rows, i, context, corpus_index))
        if done % 500 == 0 or done == len(sample):
            rate = done / (time.monotonic() - started)
            print(
                f"  {done}/{len(sample)} rows  {rate:.0f}/s  "
                f"~{(len(sample) - done) / rate / 60:.1f} min left",
                flush=True,
            )
    labels = [bool(context.outcomes[rows[i].transaction_id].is_fraud) for i in sample]
    accounts = [rows[i].account_id for i in sample]
    train = list(range(len(train_index)))
    test = list(range(len(train_index), len(sample)))
    for name, part in (("train", train), ("test", test)):
        if not any(labels[i] for i in part):
            raise DatasetGapError(
                f"the {name} sample holds no confirmed fraud, so the model would be fitted or "
                f"scored against a single class; raise --{name}-rows"
            )

    names = smoke.trainable_features()
    encoded = smoke.encode_categoricals(vectors, labels, accounts, train)
    matrix = [
        [encoded[name][i] if name in encoded else float(vectors[i][name]) for name in names]
        for i in range(len(vectors))
    ]
    scores = smoke.fit_and_score(matrix, labels, train, test, seed=run.seed)
    test_labels = [labels[i] for i in test]
    model_auc, error, recall = smoke.evaluate(scores, test_labels)

    def on_test(feature: str) -> float:
        column = names.index(feature)
        return smoke.floor_from([matrix[i][column] for i in test], test_labels)

    best_feature = max(names, key=on_test)
    best_trivial = max(evaluation.TRIVIAL_FEATURES, key=on_test)
    span = rows[train_index[-1]].timestamp - rows[train_index[0]].timestamp
    result = evaluation.Evaluation(
        features=len(names),
        train=evaluation.SegmentCounts("train", *evaluation.counts([labels[i] for i in train])),
        test=evaluation.SegmentCounts("test", *evaluation.counts(test_labels)),
        corpus_rows=len(rows),
        train_covers_days=span.total_seconds() / 86_400,
        model_auc=model_auc,
        model_auc_error=error,
        recall_at_1pct_fpr=recall,
        baseline_auc=on_test(best_feature),
        baseline_feature=best_feature,
        trivial_auc=on_test(best_trivial),
        trivial_feature=best_trivial,
        boundaries=boundaries,
    )
    print(evaluation.summarise(result))
    return 0


def run_smoke(run: smoke.SmokeRun) -> int:
    """Train one model on the computable features and report it against the single-feature floor.

    A pipeline check. Everything about it is chosen for speed and legibility rather than for
    evaluation quality, and `summarise` says so in its own first three lines so the caveat travels
    with the number instead of living in a docstring nobody pastes.
    """
    root, packs = run.dataset, run.packs
    corpus_rows, sample_rows, seed, cache = (
        run.corpus_rows,
        run.sample_rows,
        run.seed,
        run.cache,
    )
    rows = read_transactions(root, packs, limit=corpus_rows)
    if len(rows) < sample_rows * 2:
        raise DatasetGapError(
            f"read {len(rows)} transactions, too few to score {sample_rows} with history behind "
            "them; raise --corpus-rows or lower --sample-rows"
        )
    outcomes = read_outcomes(root, {row.transaction_id for row in rows})
    first_seen, device_first_seen = read_first_seen(root)
    payees, corridors, handsets = read_known_before(root, rows[0].timestamp)
    context = FeatureContext(
        countries=load_packs(packs),
        outcomes=outcomes,
        sim_swaps=read_sim_swaps(root),
        first_seen=first_seen,
        device_first_seen=device_first_seen,
        counterparties_before=payees,
        countries_before=corridors,
        devices_before=handsets,
        cash_out_codes=frozenset({CASH_DISBURSEMENT_MCC}),
        denominations=denominations_by_currency(packs),
        cell_rate_prior=0.0087,
    )

    sample = list(range(len(rows) - sample_rows, len(rows)))
    cached = smoke.cache_read(cache, run.key) if cache else None
    if cached is not None:
        vectors, labels, accounts = cached
        print(f"reusing {len(vectors)} cached feature rows from {cache}")
    else:
        corpus_index = CorpusIndex.build(rows)
        print(f"computing {len(smoke.trainable_features())} features for {len(sample)} rows...")
        # Progress, because the feature pass is the expensive half and a silent hour cannot be
        # told apart from a hang — which is exactly how the first attempt at this run was spent.
        started = time.monotonic()
        vectors = []
        for done, i in enumerate(sample, start=1):
            vectors.append(compute(rows, i, context, corpus_index))
            if done % 500 == 0 or done == len(sample):
                rate = done / (time.monotonic() - started)
                left = (len(sample) - done) / rate
                print(
                    f"  {done}/{len(sample)} rows  {rate:.0f}/s  ~{left / 60:.1f} min left",
                    flush=True,
                )
        labels = [bool(outcomes[rows[i].transaction_id].is_fraud) for i in sample]
        accounts = [rows[i].account_id for i in sample]
        if cache:
            smoke.cache_write(cache, run.key, vectors, labels, accounts)
            print(f"wrote the feature matrix to {cache}")

    # A time-ordered holdout: the sample is already in timestamp order, so the last 30% is later
    # than the first 70%. This is A temporal split, not D-07's, which has an embargo and different
    # boundaries — the report says so rather than leaving it to be assumed.
    cut = int(len(sample) * 0.7)
    train, test = list(range(cut)), list(range(cut, len(sample)))
    if not any(labels[i] for i in train) or not any(labels[i] for i in test):
        raise DatasetGapError(
            "one side of the holdout holds no confirmed fraud, so the model would train or be "
            "scored against a single class; raise --sample-rows"
        )

    names = smoke.trainable_features()
    encoded = smoke.encode_categoricals(vectors, labels, accounts, train)
    # One row per scored transaction, one column per trainable feature, in a fixed order. The two
    # categoricals come from `encoded`; everything else is already a float, including the
    # structural NaNs, which XGBoost splits on natively (D-04) rather than having them imputed.
    matrix = [
        [encoded[name][i] if name in encoded else float(vectors[i][name]) for name in names]
        for i in range(len(vectors))
    ]

    scores = smoke.fit_and_score(matrix, labels, train, test, seed=seed)
    test_labels = [labels[i] for i in test]

    model_auc, error, recall = smoke.evaluate(scores, test_labels)
    floor_index = names.index(smoke.FLOOR_FEATURE)
    floor = smoke.floor_from([matrix[i][floor_index] for i in test], test_labels)

    result = smoke.SmokeResult(
        features=len(names),
        train_rows=len(train),
        test_rows=len(test),
        train_fraud=sum(1 for i in train if labels[i]),
        test_fraud=sum(1 for i in test if labels[i]),
        model_auc=model_auc,
        model_auc_error=error,
        recall_at_1pct_fpr=recall,
        baseline_auc=floor,
        baseline_feature=smoke.FLOOR_FEATURE,
    )
    print(smoke.summarise(result))
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="fs-features", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser(
        "computability",
        help="compute every feature over a generated dataset and check the registry's "
        "computable declarations against what the data produces (PB-44)",
    )
    check.add_argument("dataset", type=Path)
    check.add_argument("--packs", type=Path, required=True, help="from: fs-dataset packs")
    check.add_argument("--corpus-rows", type=int, default=40_000)
    check.add_argument("--sample-rows", type=int, default=2_000)
    auc_command = commands.add_parser(
        "auc",
        help="single-feature separation for all 44 against the D-08 ceiling, with "
        "account-grouped out-of-fold encoding, at a stated scale (E3, E6)",
    )
    auc_command.add_argument("dataset", type=Path)
    auc_command.add_argument("--packs", type=Path, required=True)
    auc_command.add_argument("--corpus-rows", type=int, default=40_000)
    auc_command.add_argument("--sample-rows", type=int, default=2_000)
    evaluate_command = commands.add_parser(
        "evaluate",
        help="fit on D-07's train period and score its test period, using the published split",
    )
    evaluate_command.add_argument("dataset", type=Path)
    evaluate_command.add_argument("--packs", type=Path, required=True)
    evaluate_command.add_argument(
        "--split",
        type=Path,
        required=True,
        help="split.json from fs-dataset split. Required: an evaluation on any other split is "
        "not comparable with the gates, and defaulting would hide that",
    )
    evaluate_command.add_argument("--corpus-rows", type=int, default=400_000)
    evaluate_command.add_argument("--train-rows", type=int, default=12_000)
    evaluate_command.add_argument("--test-rows", type=int, default=8_000)
    evaluate_command.add_argument("--seed", type=int, default=20260917)
    smoke_command = commands.add_parser(
        "smoke",
        help="train one model on the computable features and report it against the "
        "single-feature floor. A PIPELINE CHECK, not a result",
    )
    smoke_command.add_argument("dataset", type=Path)
    smoke_command.add_argument("--packs", type=Path, required=True)
    smoke_command.add_argument("--corpus-rows", type=int, default=200_000)
    smoke_command.add_argument("--sample-rows", type=int, default=30_000)
    smoke_command.add_argument("--seed", type=int, default=20260917)
    smoke_command.add_argument(
        "--cache",
        type=Path,
        default=None,
        help="reuse the computed feature matrix here when it was computed for this dataset, "
        "corpus size, sample size and feature set; write it there otherwise",
    )
    args = parser.parse_args(argv)

    try:
        if args.command == "evaluate":
            return run_evaluate(
                EvaluationRun(
                    dataset=args.dataset,
                    packs=args.packs,
                    split=args.split,
                    corpus_rows=args.corpus_rows,
                    train_rows=args.train_rows,
                    test_rows=args.test_rows,
                    seed=args.seed,
                )
            )
        if args.command == "smoke":
            return run_smoke(
                smoke.SmokeRun(
                    dataset=args.dataset,
                    packs=args.packs,
                    corpus_rows=args.corpus_rows,
                    sample_rows=args.sample_rows,
                    seed=args.seed,
                    cache=args.cache,
                )
            )
        if args.command == "auc":
            return run_auc(args.dataset, args.packs, args.corpus_rows, args.sample_rows)
        return run_computability(args.dataset, args.packs, args.corpus_rows, args.sample_rows)
    except DatasetGapError as gap:
        print(f"ERROR {gap}", file=sys.stderr)
        return 2


if __name__ == "__main__":  # pragma: no cover - module entry point
    raise SystemExit(main())
