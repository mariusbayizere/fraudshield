"""Streaming generation, month by month, into partitioned Parquet (ADR 0022 sections 2-4).

Customers are simulated in batches of ``chunk_size`` shards. The batch size affects only peak
memory: every value comes from entity-keyed streams, and each month is sorted by
``(transaction_timestamp, transaction_id)`` before it is written with fixed writer options, so the
files are byte-identical for any batch size.
"""

from __future__ import annotations

import hashlib
import json
import os
import platform
import resource
from collections import Counter
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.compute as pc
import pyarrow.parquet as pq

from fraudshield_dataset.generator.config import SimulationConfig
from fraudshield_dataset.generator.fraud import FraudEvent, FraudModel
from fraudshield_dataset.generator.keys import SHARDS, shard_of, stream
from fraudshield_dataset.generator.legit import LegitimateBehaviour, month_start_micros
from fraudshield_dataset.generator.population import Customer, Population
from fraudshield_dataset.generator.schema import ACCOUNT_EVENTS, LABELS, TRANSACTIONS, Rows

ROW_GROUP_SIZE = 65_536
_WRITE_OPTIONS = {
    "compression": "zstd",
    "compression_level": 3,
    "use_dictionary": True,
    "write_statistics": True,
    "row_group_size": ROW_GROUP_SIZE,
}
_MICROS_PER_HOUR = 3_600_000_000

# A scenario adds fraud rows (and the account events that enable them) for a customer-month.
Scenario = Callable[[Customer, int, Rows, list[FraudEvent]], None]


@dataclass(frozen=True)
class GenerationResult:
    output: Path
    rows: int
    rows_by_month: dict[str, int]
    peak_rss_bytes: int


def peak_rss_bytes() -> int:
    """Peak resident set size of this process (Linux reports kilobytes)."""
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * 1024


def _customers_by_shard(config: SimulationConfig) -> dict[int, list[int]]:
    shards: dict[int, list[int]] = {s: [] for s in range(SHARDS)}
    for index in range(config.customers_total):
        shards[shard_of(config.seed, index)].append(index)
    return shards


def _labels(config: SimulationConfig, rows: Rows) -> dict[str, list[object]]:
    p = config.parameters
    noise = p.number("labels.label_noise_rate")
    fraud_rate = p.number("fraud.fraud_rate_overall")
    median = p.number("labels.label_delay_median_hours")
    sigma = p.number("labels.label_delay_log_sigma")
    observed: list[object] = []
    available: list[object] = []
    for transaction_id, truth, timestamp in zip(
        rows.columns["transaction_id"],
        rows.columns["is_fraud_true"],
        rows.columns["transaction_timestamp"],
        strict=True,
    ):
        rng = stream(config.seed, "label", transaction_id)
        # Noise in both directions with equal expected counts: a fraud is missed with probability
        # `noise`; a legitimate row is marked fraud with probability `noise * fraud_rate`.
        flip = rng.random() < (noise if truth else noise * fraud_rate)
        observed.append(bool(truth) != flip)
        delay = float(np.exp(np.log(median) + sigma * rng.standard_normal())) * _MICROS_PER_HOUR
        available.append(int(timestamp) + int(delay))  # type: ignore[call-overload]
    return {
        "transaction_id": rows.columns["transaction_id"],
        "is_fraud_observed": observed,
        "is_fraud_true": rows.columns["is_fraud_true"],
        "fraud_type": rows.columns["fraud_type"],
        "scenario_variant": rows.columns["scenario_variant"],
        "label_available_at": available,
    }


def _transactions_table(rows: Rows) -> pa.Table:
    return pa.table(
        {
            name: pa.array(rows.columns[name], type=TRANSACTIONS.field(name).type)
            for name in TRANSACTIONS.names
        },
        schema=TRANSACTIONS,
    )


def _labels_table(config: SimulationConfig, rows: Rows) -> pa.Table:
    return pa.table(
        {
            name: pa.array(values, type=LABELS.field(name).type)
            for name, values in _labels(config, rows).items()
        },
        schema=LABELS,
    )


def _write(table: pa.Table, path: Path) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(table, path, **_WRITE_OPTIONS)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _month_events(
    config: SimulationConfig,
    customers: Iterable[Customer],
    month: int,
    fraud_events: Iterable[FraudEvent],
) -> pa.Table:
    start = month_start_micros(config.months[month])
    offsets = config.parameters.mapping("currencies.utc_offset_hours")
    accounts, kinds, times = [], [], []
    for customer in customers:
        for event in customer.events:
            if event.month == month:
                accounts.append(customer.account)
                kinds.append(event.kind)
                local = (event.day - 1) * 86_400 + event.seconds
                times.append(
                    start
                    + (local - int(offsets[customer.country]) * 3600) * 1_000_000
                    + event.micros
                )
    for fraud_event in fraud_events:
        accounts.append(fraud_event.account_id)
        kinds.append(fraud_event.kind)
        times.append(fraud_event.timestamp)
    table = pa.table(
        {
            "account_id": accounts,
            "event_type": kinds,
            "event_timestamp": pa.array(times, pa.timestamp("us", tz="UTC")),
        },
        schema=ACCOUNT_EVENTS,
    )
    order = pc.sort_indices(
        table,
        [
            ("event_timestamp", "ascending"),
            ("account_id", "ascending"),
            ("event_type", "ascending"),
        ],
    )
    return table.take(order)


@dataclass(frozen=True)
class _Simulation:
    config: SimulationConfig
    population: Population
    legitimate: LegitimateBehaviour
    scenarios: list[Scenario]
    shards: dict[int, list[int]]
    chunk_size: int

    def month(
        self, month_index: int
    ) -> tuple[list[pa.Table], list[pa.Table], list[Customer], list[FraudEvent]]:
        """Simulate one month in batches of ``chunk_size`` shards, each converted to Arrow."""
        transaction_tables: list[pa.Table] = []
        label_tables: list[pa.Table] = []
        simulated: list[Customer] = []
        fraud_events: list[FraudEvent] = []
        for first in range(0, SHARDS, self.chunk_size):
            batch = Rows()
            for shard in range(first, min(first + self.chunk_size, SHARDS)):
                for index in self.shards[shard]:
                    if self.population.join_month(index) > month_index:
                        continue
                    customer = self.population.customer(index)
                    simulated.append(customer)
                    batch.extend(self.legitimate.month(customer, month_index))
                    for scenario in self.scenarios:
                        scenario(customer, month_index, batch, fraud_events)
            # Convert each batch at once so Python objects never accumulate for a whole month.
            transaction_tables.append(_transactions_table(batch))
            label_tables.append(_labels_table(self.config, batch))
        return transaction_tables, label_tables, simulated, fraud_events


def generate(
    config: SimulationConfig,
    output: Path,
    chunk_size: int = 8,
    scenarios: Iterable[Scenario] | None = None,
    allow_missing_scenarios: bool = False,
) -> GenerationResult:
    """Simulate every month and write ``transactions``, ``labels`` and ``account_events``."""
    if chunk_size < 1:
        raise ValueError("chunk_size must be at least 1")
    population = Population(config)
    legitimate = LegitimateBehaviour(config, population)
    scenario_list: list[Scenario] = (
        [
            FraudModel(
                config, population, legitimate, allow_missing_scenarios=allow_missing_scenarios
            )
        ]
        if scenarios is None
        else list(scenarios)
    )
    simulation = _Simulation(
        config, population, legitimate, scenario_list, _customers_by_shard(config), chunk_size
    )
    rows_by_month: dict[str, int] = {}
    daily: Counter[str] = Counter()
    checksums: dict[str, str] = {}
    carried: tuple[pa.Table, pa.Table] | None = None
    dropped_after_end = 0
    for month_index, month in enumerate(config.months):
        transaction_tables, label_tables, simulated, fraud_events = simulation.month(month_index)
        if carried is not None:
            transaction_tables.insert(0, carried[0])
            label_tables.insert(0, carried[1])
        table = pa.concat_tables(transaction_tables)
        labels = pa.concat_tables(label_tables)
        del transaction_tables, label_tables
        # Fraud bursts and delayed drains that run past the month end belong to the next month's
        # file; after the last month they are outside the simulated period and dropped.
        month_end = (
            month_start_micros(config.months[month_index + 1])
            if month_index + 1 < len(config.months)
            else config.split.end
        )
        spills = pc.greater_equal(table["transaction_timestamp"].cast(pa.int64()), month_end)
        if month_index + 1 < len(config.months):
            carried = (table.filter(spills), labels.filter(spills))
        else:
            dropped_after_end = int(pc.sum(spills).as_py() or 0)
            carried = None
        keep = pc.invert(spills)
        table, labels = table.filter(keep), labels.filter(keep)
        order = pc.sort_indices(
            table, [("transaction_timestamp", "ascending"), ("transaction_id", "ascending")]
        )
        # One contiguous chunk per column: the Parquet layout then cannot depend on batch count.
        table = table.take(order).combine_chunks()
        labels = labels.take(order).combine_chunks()
        partition = f"month={month}"
        checksums[f"transactions/{partition}/part-0000.parquet"] = _write(
            table, output / "transactions" / partition / "part-0000.parquet"
        )
        checksums[f"labels/{partition}/part-0000.parquet"] = _write(
            labels, output / "labels" / partition / "part-0000.parquet"
        )
        simulated.sort(key=lambda c: c.index)
        checksums[f"account_events/{partition}/part-0000.parquet"] = _write(
            _month_events(config, simulated, month_index, fraud_events),
            output / "account_events" / partition / "part-0000.parquet",
        )
        rows_by_month[month] = table.num_rows
        days = pc.strftime(table.column("transaction_timestamp"), format="%Y-%m-%d").to_pylist()
        daily.update(days)
        del table, labels, simulated, fraud_events
    peak = peak_rss_bytes()
    manifest = {
        "dataset": "FraudShield-EAC-Transactions",
        "seed": config.seed,
        "rows": sum(rows_by_month.values()),
        "rows_by_month": rows_by_month,
        "rows_by_day": dict(sorted(daily.items())),
        "rows_dropped_after_simulation_end": dropped_after_end,
        # Empty for any dataset worth releasing. Non-empty means the run was too small to stage
        # these scenarios and said so was acceptable, so the dataset identifies its own gap rather
        # than looking like a small release (M2 milestone review, MAJOR M-4).
        "scenarios_not_staged": sorted(
            s for m in scenario_list for s in getattr(m, "missing_scenarios", ())
        ),
        "sha256": dict(sorted(checksums.items())),
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    run = {
        "chunk_size": chunk_size,
        "peak_rss_bytes": peak,
        "machine": {
            "platform": platform.platform(),
            "processor": platform.processor() or platform.machine(),
            "cpus": os.cpu_count(),
            "python": platform.python_version(),
            "pyarrow": pa.__version__,
            "numpy": np.__version__,
        },
    }
    (output / "run.json").write_text(json.dumps(run, indent=2, sort_keys=True) + "\n")
    return GenerationResult(output, manifest["rows"], rows_by_month, peak)  # type: ignore[arg-type]
