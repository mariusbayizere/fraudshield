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
        # Powers of three, not of ten, so a denomination table hard-coded to decimal steps or a
        # roundness test that counted trailing zeros would be caught here.
        "round_denominations": [3, 9, 27],
    },
    "BB": {
        "alpha2": "BB",
        "continent": "XX",
        "blocs": ["BLOC1"],
        "utc_offset_hours": 3,
        "currency": "BBB",
        "currency_minor_units": 2,
        "round_denominations": [500, 1000],
    },
    # A third pack on another continent, reached only late in the corpus. It gives
    # `is_new_country_for_account` something still to discover inside the scored half, and gives
    # `corridor_class` an INTERCONTINENTAL value so it is not constant either. No account is
    # domiciled here, so it never appears as a currency and the inversion stays unambiguous.
    "CC": {
        "alpha2": "CC",
        "continent": "YY",
        "blocs": ["BLOC2"],
        "utc_offset_hours": -4,
        "currency": "CCC",
        "currency_minor_units": 0,
        "round_denominations": [7],
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
    # Gaps spanning five orders of magnitude, including a sixty-four-day silence: a fixture whose
    # transactions are all tens of minutes apart makes eight features constant, and the variance
    # check then reports the fixture's shape as the dataset's.
    gaps = (25, 40, 900, 7_200, 50_000, 260_000, 95, 43_000, 610_000, 55, 5_500_000, 20_000)
    rows, labels, events = [], [], []
    # Built per account and sorted afterwards, not round-robin by index. Round-robin puts eight
    # other accounts' gaps between an account's own consecutive rows, so every per-account window
    # is empty and nine features read constant — a property of the loop, not of the data.
    for a in range(8):
        # Accounts start hours apart rather than days, so they interleave in time and an agent
        # serves several of them inside an hour. Staggering by days makes every agent feature
        # constant at one customer, which is a property of the loop and not of the data.
        when = START + timedelta(hours=3 * a)
        for j in range(30):
            i = a * 30 + j
            when += timedelta(seconds=gaps[(j + a) % len(gaps)])
            account = f"A{a}"
            on_ussd = j % 5 == 0
            at_agent = j % 3 == 0 and not on_ussd
            fraud = i % 17 == 0
            rows.append(
                {
                    "transaction_id": f"t{i:04d}",
                    "account_id": account,
                    # Every seventh payee is one this account has never used, so
                    # `counterparty_is_new_for_account` is neither always True nor always False in
                    # the scored half. Six recurring payees alone leave it constant by then.
                    "counterparty_id": f"C{j % 6}" if j % 7 else f"NEW{a}-{j}",
                    "amount": Decimal(f"{1000 + (i * 37) % 9000}.0000"),
                    "currency": "AAA" if a % 2 == 0 else "BBB",
                    "amount_rwf": Decimal(f"{1000 + (i * 37) % 9000}.0000"),
                    "channel": "USSD" if on_ussd else ("AGENT_BANKING" if at_agent else "CARD"),
                    "merchant_category_code": "6011" if at_agent else "5411",
                    "latitude": -1.9441 + 0.01 * (j % 7),
                    "longitude": 30.0619 + 0.01 * (j % 5),
                    # Devices are per account, as in the benchmark, where no fingerprint is
                    # shared (PB-40). A fixture that shared them would make
                    # `accounts_per_device_7d` vary here and constant there, so the check would
                    # disagree with the registry for a reason belonging to the fixture.
                    "device_fingerprint": None if on_ussd else f"D{a}-{j % 3}",
                    "agent_id": f"AG{j % 2}" if at_agent else None,
                    # A third country only late in each account's life, so the novelty flag is
                    # still firing inside the scored half rather than settling before it starts.
                    "counterparty_country": ("AA", "BB")[j % 2] if j < 22 else "CC",
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
    rows.sort(key=lambda row: str(row["transaction_timestamp"]))
    labels.sort(key=lambda row: str(row["transaction_id"]))
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
    """The whole path: Parquet in, 44 features computed, declarations compared against the data.

    **The verdict is expected to be non-zero here, and that is the correct behaviour.** The
    registry's `computable` states describe the *benchmark*; this fixture is 240 rows built to
    exercise the reader, and a few features it cannot make vary at this size are declared
    COMPUTABLE because they vary on a million rows. A fixture tuned until the verdict read 0 would
    be a fixture shaped by the answer, which is the failure the E3 corpus-size work ran into from
    the other direction.

    What is asserted is the mechanism: every feature computed, the scan's size reported, each
    mismatch named with its observed state and the reason it matters.
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
    assert "distinct" in output, "the distinct-value count must be reported per feature"
    assert code == 1, output
    assert "declared COMPUTABLE, observed CONSTANT" in output
    assert "carrying one value" in output, "a mismatch must say why it matters, not only that"


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_six_features_without_source_data_are_dead_here_too(
    dataset: Path, packs: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """The part of the profile that is a property of the data's *shape*, not of its size.

    Opening dates, tier histories, agent standing and denominations are absent from this fixture
    for the same reason they are absent from the benchmark: nothing produces them. So the six
    declared NO_SOURCE_DATA features must be NaN here at any size, and none of them may appear as
    a mismatch — if one did, this fixture would be supplying data the benchmark does not.
    """
    cli.main(
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
    for name in (
        "account_age_days",
        "counterparty_account_age_days",
        "kyc_tier",
        "agent_float_utilisation_ratio",
        "agent_distance_from_registered_km",
        "round_sum_flag",
    ):
        assert f"{name} " in output
        assert f"ERROR {name}:" not in output, f"{name} produced a value the benchmark cannot"


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
