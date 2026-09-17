"""Derived simulation settings, computed once from the parameter files (ADR 0022)."""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

from fraudshield_dataset.params import ParameterError, ParameterSet

CHANNELS = ("MOBILE_MONEY", "USSD", "AGENT_BANKING", "CARD", "ONLINE", "BANK_TRANSFER")
COUNTRIES = ("RW", "KE", "TZ", "UG", "CD")
SEGMENTS = ("urban_salaried", "informal_trader", "rural_ussd", "student")


@dataclass(frozen=True)
class SimulationConfig:
    seed: int
    total_rows: int
    months: tuple[str, ...]
    monthly_volume: tuple[int, ...]
    customers_total: int
    customers_active: tuple[int, ...]
    parameters: ParameterSet
    channel_share_by_segment: dict[str, dict[str, float]]


def month_labels(start: str, count: int) -> tuple[str, ...]:
    first = dt.date.fromisoformat(f"{start}-01")
    labels = []
    for offset in range(count):
        index = first.year * 12 + first.month - 1 + offset
        labels.append(f"{index // 12:04d}-{index % 12 + 1:02d}")
    return tuple(labels)


def segment_channel_shares(parameters: ParameterSet) -> dict[str, dict[str, float]]:
    """Per-segment channel shares whose mixture equals the SRS channel mix in expectation.

    Rural USSD customers use their own (assumed) mix. Every other segment shares the remainder,
    which must be non-negative for every channel; otherwise the parameters are inconsistent.
    """
    target = parameters.mapping("channels.channel_share")
    segments = parameters.mapping("population.segment_share")
    rural_mix = parameters.mapping("behaviour.rural_ussd_channel_share")
    rural = segments["rural_ussd"]
    remainder = {c: target[c] - rural * rural_mix.get(c, 0.0) for c in CHANNELS}
    if min(remainder.values()) < -1e-12:
        raise ParameterError(
            "rural USSD channel shares exceed the SRS channel mix: " + repr(remainder)
        )
    other = {c: max(v, 0.0) / (1.0 - rural) for c, v in remainder.items()}
    shares = {segment: other for segment in SEGMENTS if segment != "rural_ussd"}
    shares["rural_ussd"] = {c: rural_mix.get(c, 0.0) for c in CHANNELS}
    return shares


def build_config(
    parameters: ParameterSet, seed: int, total_rows: int | None = None
) -> SimulationConfig:
    rows = total_rows or parameters.integer("volume.total_rows_target")
    count = parameters.integer("volume.simulation_months")
    start = str(parameters.value("volume.start_month"))
    growth = 1.0 + parameters.number("volume.monthly_growth_rate")
    weights = [growth**m for m in range(count)]
    scale = rows / sum(weights)
    volume = tuple(round(scale * w) for w in weights)
    per_customer = parameters.number("population.mean_transactions_per_active_customer_month")
    active = tuple(max(1, round(v / per_customer)) for v in volume)
    return SimulationConfig(
        seed=seed,
        total_rows=rows,
        months=month_labels(start, count),
        monthly_volume=volume,
        customers_total=max(active),
        customers_active=active,
        parameters=parameters,
        channel_share_by_segment=segment_channel_shares(parameters),
    )
