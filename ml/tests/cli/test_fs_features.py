"""`fs-features` against a dataset written to disk (PB-44, E3, E6).

Writes the Parquet the generator writes, rather than mocking a reader: the point of this entry
point is that the feature pipeline consumes the **published** format and never imports the
generator, so a test that bypassed the format would be testing the thing the design avoids.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from fraudshield_ml import cli

START = datetime(2025, 1, 1, tzinfo=UTC)

PACKS = {
    "AA": {
        "alpha2": "AA",
        "continent": "XX",
        "blocs": ["BLOC1"],
        "utc_offset_hours": 2,
        "currency": "AAA",
        "currency_minor_units": 0,
    },
    "BB": {
        "alpha2": "BB",
        "continent": "XX",
        "blocs": ["BLOC1"],
        "utc_offset_hours": 3,
        "currency": "BBB",
        "currency_minor_units": 2,
    },
}

TRANSACTIONS = pa.schema(
    [
        pa.field("transaction_id", pa.string(), nullable=False),
        pa.field("account_id", pa.string(), nullable=False),
        pa.field("counterparty_id", pa.string(), nullable=False),
        pa.field("amount", pa.decimal128(18, 4), nullable=False),
        pa.field("currency", pa.string(), nullable=False),
        pa.field("amount_rwf", pa.decimal128(18, 4), nullable=False),
        pa.field("channel", pa.string(), nullable=False),
        pa.field("merchant_category_code", pa.string(), nullable=False),
        pa.field("latitude", pa.float64(), nullable=False),
        pa.field("longitude", pa.float64(), nullable=False),
        pa.field("device_fingerprint", pa.string(), nullable=True),
        pa.field("agent_id", pa.string(), nullable=True),
        pa.field("counterparty_country", pa.string(), nullable=False),
        pa.field("transaction_timestamp", pa.timestamp("us", tz="UTC"), nullable=False),
    ]
)
LABELS = pa.schema(
    [
        pa.field("transaction_id", pa.string(), nullable=False),
        pa.field("is_fraud_observed", pa.bool_(), nullable=False),
        pa.field("label_available_at", pa.timestamp("us", tz="UTC"), nullable=False),
    ]
)
EVENTS = pa.schema(
    [
        pa.field("account_id", pa.string(), nullable=False),
        pa.field("event_type", pa.string(), nullable=False),
        pa.field("event_timestamp", pa.timestamp("us", tz="UTC"), nullable=False),
    ]
)


def _write(root: Path, table: str, data: pa.Table) -> None:
    part = root / table / "month=2025-01"
    part.mkdir(parents=True, exist_ok=True)
    pq.write_table(data, part / "part-0000.parquet")


@pytest.fixture
def dataset(tmp_path: Path) -> Path:
    """Enough rows, with fraud, agents and USSD, for every feature to be reachable."""
    rows, labels, events = [], [], []
    when = START
    for i in range(240):
        when += timedelta(minutes=17 + (i * 11) % 91)
        account = f"A{i % 8}"
        on_ussd = i % 5 == 0
        at_agent = i % 3 == 0 and not on_ussd
        fraud = i % 17 == 0
        rows.append(
            {
                "transaction_id": f"t{i:04d}",
                "account_id": account,
                "counterparty_id": f"C{i % 6}",
                "amount": Decimal(f"{1000 + (i * 37) % 9000}.0000"),
                "currency": "AAA" if i % 2 == 0 else "BBB",
                "amount_rwf": Decimal(f"{1000 + (i * 37) % 9000}.0000"),
                "channel": "USSD" if on_ussd else ("AGENT_BANKING" if at_agent else "CARD"),
                "merchant_category_code": "6011" if at_agent else "5411",
                "latitude": -1.9441 + 0.01 * (i % 7),
                "longitude": 30.0619 + 0.01 * (i % 5),
                "device_fingerprint": None if on_ussd else f"D{i % 4}",
                "agent_id": f"AG{i % 2}" if at_agent else None,
                "counterparty_country": ("AA", "BB")[i % 2],
                "transaction_timestamp": when,
            }
        )
        labels.append(
            {
                "transaction_id": f"t{i:04d}",
                "is_fraud_observed": fraud,
                "label_available_at": when + timedelta(days=1),
            }
        )
    for a in range(4):
        events.append(
            {
                "account_id": f"A{a}",
                "event_type": "SIM_SWAP",
                "event_timestamp": START + timedelta(days=1 + a),
            }
        )

    root = tmp_path / "bench"
    _write(root, "transactions", pa.Table.from_pylist(rows, schema=TRANSACTIONS))
    _write(root, "labels", pa.Table.from_pylist(labels, schema=LABELS))
    _write(root, "account_events", pa.Table.from_pylist(events, schema=EVENTS))
    return root


@pytest.fixture
def packs(tmp_path: Path) -> Path:
    path = tmp_path / "packs.json"
    path.write_text(json.dumps(PACKS), encoding="utf-8")
    return path


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_computability_runs_against_the_published_format(
    dataset: Path, packs: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """The whole path: Parquet in, 44 features computed, declarations checked.

    Exits 0 because the six declared NO_SOURCE_DATA features are exactly the ones this dataset
    cannot feed — which is the same answer the benchmark gives, reached through the same code.
    """
    code = cli.main(
        [
            "computability",
            str(dataset),
            "--packs",
            str(packs),
            "--corpus-rows",
            "240",
            "--sample-rows",
            "60",
        ]
    )
    output = capsys.readouterr().out
    assert "over 60 scored rows" in output
    assert "44 features" in output
    assert code == 0, output


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_shared_currency_is_refused_rather_than_tie_broken(
    dataset: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """The dataset carries no account-country column, so the country is recovered from the
    currency — and that inversion is only valid while currencies are distinct.

    Two packs sharing one (XOF across West Africa is the real case) makes it ambiguous, and an
    ambiguous inversion would place transactions in the wrong country and shift every local-time
    feature by hours. Refused, with the pair named, rather than tie-broken.
    """
    shared = dict(PACKS)
    shared["CC"] = dict(PACKS["AA"], alpha2="CC")
    path = tmp_path / "shared.json"
    path.write_text(json.dumps(shared), encoding="utf-8")

    code = cli.main(["computability", str(dataset), "--packs", str(path)])
    assert code == 2
    assert "share the currency" in capsys.readouterr().err


@pytest.mark.req("FR-02-02")
def test_too_small_a_corpus_is_refused_rather_than_scored_without_history(
    dataset: Path, packs: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Scoring rows that have no history behind them would make most windows empty and report
    features as dead for a reason that is about the sample, not the data."""
    code = cli.main(
        [
            "computability",
            str(dataset),
            "--packs",
            str(packs),
            "--corpus-rows",
            "40",
            "--sample-rows",
            "30",
        ]
    )
    assert code == 2
    assert "too few to score" in capsys.readouterr().err


@pytest.mark.req("D-08", "ML-DATA-01")
def test_the_auc_command_reports_every_feature_with_its_scale(
    dataset: Path, packs: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """E3 and E6: all 44 against the D-08 ceiling, with the scale printed beside the figures.

    E2 makes a metric quoted without its scale inadmissible, so the corpus size, the scored-row
    count and the fraud count lead the output rather than trailing it.
    """
    code = cli.main(
        [
            "auc",
            str(dataset),
            "--packs",
            str(packs),
            "--corpus-rows",
            "240",
            "--sample-rows",
            "80",
        ]
    )
    output = capsys.readouterr().out
    assert "corpus 240, scored 80, fraud" in output
    assert "days_since_sim_swap" in output, "E6: the event-join feature is reported too"
    assert "out-of-fold target encoding, account-grouped" in output
    assert code in (0, 1), output


@pytest.mark.req("D-08")
def test_the_auc_command_refuses_a_sample_with_no_fraud(
    dataset: Path, packs: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Every AUC would be undefined and the ceiling would pass over nothing (ADR 0009)."""
    clean = tmp_path / "clean"
    for table in ("transactions", "account_events"):
        source = dataset / table / "month=2025-01" / "part-0000.parquet"
        target = clean / table / "month=2025-01"
        target.mkdir(parents=True, exist_ok=True)
        target.joinpath("part-0000.parquet").write_bytes(source.read_bytes())
    labels = pq.read_table(dataset / "labels" / "month=2025-01" / "part-0000.parquet")
    honest = labels.set_column(
        labels.schema.get_field_index("is_fraud_observed"),
        "is_fraud_observed",
        pa.array([False] * labels.num_rows, pa.bool_()),
    )
    _write(clean, "labels", honest)

    code = cli.main(
        [
            "auc",
            str(clean),
            "--packs",
            str(packs),
            "--corpus-rows",
            "240",
            "--sample-rows",
            "80",
        ]
    )
    assert code == 2
    assert "no confirmed fraud" in capsys.readouterr().err
