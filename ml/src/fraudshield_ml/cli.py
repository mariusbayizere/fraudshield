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
from collections.abc import Sequence
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
    context = FeatureContext(
        countries=load_packs(packs),
        outcomes=read_outcomes(root, {row.transaction_id for row in rows}),
        sim_swaps=read_sim_swaps(root),
        cash_out_codes=frozenset({CASH_DISBURSEMENT_MCC}),
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
    context = FeatureContext(
        countries=load_packs(packs),
        outcomes=outcomes,
        sim_swaps=read_sim_swaps(root),
        cash_out_codes=frozenset({CASH_DISBURSEMENT_MCC}),
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
    args = parser.parse_args(argv)

    try:
        if args.command == "auc":
            return run_auc(args.dataset, args.packs, args.corpus_rows, args.sample_rows)
        return run_computability(args.dataset, args.packs, args.corpus_rows, args.sample_rows)
    except DatasetGapError as gap:
        print(f"ERROR {gap}", file=sys.stderr)
        return 2


if __name__ == "__main__":  # pragma: no cover - module entry point
    raise SystemExit(main())
