"""Derived simulation settings, computed once from the parameter files (ADR 0022)."""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

from fraudshield_dataset.params import ParameterError, ParameterSet

CHANNELS = ("MOBILE_MONEY", "USSD", "AGENT_BANKING", "CARD", "ONLINE", "BANK_TRANSFER")
COUNTRIES = ("RW", "KE", "TZ", "UG", "CD")
SEGMENTS = ("urban_salaried", "informal_trader", "rural_ussd", "student")


@dataclass(frozen=True)
class SplitPlan:
    """Planned temporal boundaries (UTC microseconds), from the calibrated volume (D-07).

    Boundaries come from planned, not realised, volume so that everything that depends on them
    (the novel sub-variant, the fraud-rate ramp) is fixed before a single row is simulated. The
    realised row counts per split are measured and reported afterwards.
    """

    validation_start: int
    calibration_start: int
    embargo_start: int
    test_start: int
    end: int


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
    split: SplitPlan
    fraud_rate_by_month: tuple[float, ...]


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
    months = month_labels(start, count)
    split = plan_split(parameters, months, volume, rows)
    return SimulationConfig(
        seed=seed,
        total_rows=rows,
        months=months,
        monthly_volume=volume,
        customers_total=max(active),
        customers_active=active,
        parameters=parameters,
        channel_share_by_segment=segment_channel_shares(parameters),
        split=split,
        fraud_rate_by_month=fraud_ramp(parameters, months, volume, split),
    )


_DAY = 86_400_000_000


def _month_bounds(month: str) -> tuple[int, int]:
    first = dt.datetime.fromisoformat(f"{month}-01T00:00:00+00:00")
    following = month_labels(month, 2)[1]
    last = dt.datetime.fromisoformat(f"{following}-01T00:00:00+00:00")
    epoch = dt.datetime(1970, 1, 1, tzinfo=dt.UTC)
    return (
        int((first - epoch).total_seconds()) * 1_000_000,
        int((last - epoch).total_seconds()) * 1_000_000,
    )


def seasonal_factor(parameters: ParameterSet, month: str) -> float:
    """Monthly volume factor from school-fee peaks, normalised to a yearly mean of one."""
    months = {int(m) for m in parameters.numbers("behaviour.school_fee_months")}
    boost = parameters.number("behaviour.school_fee_volume_boost")
    mean = (len(months) * boost + 12 - len(months)) / 12
    return (boost if int(month[5:]) in months else 1.0) / mean


def _planned_rate(
    parameters: ParameterSet, months: tuple[str, ...], volume: tuple[int, ...]
) -> list[tuple[int, int, float]]:
    """(start, end, rows per microsecond) per month, uniform within a month."""
    return [
        (lo, hi, v * seasonal_factor(parameters, m) / (hi - lo))
        for m, v in zip(months, volume, strict=True)
        for lo, hi in (_month_bounds(m),)
    ]


def _time_before(segments: list[tuple[int, int, float]], end: int, rows: float) -> int:
    """The time t such that the planned volume in [t, end) equals ``rows``."""
    remaining = rows
    for lo, hi, rate in reversed(segments):
        if lo >= end:
            continue
        top = min(hi, end)
        available = (top - lo) * rate
        if available >= remaining:
            return int(top - remaining / rate)
        remaining -= available
    raise ParameterError("the split sizes exceed the simulated volume")


def plan_split(
    parameters: ParameterSet, months: tuple[str, ...], volume: tuple[int, ...], rows: int
) -> SplitPlan:
    scale = rows / parameters.integer("volume.total_rows_target")
    segments = _planned_rate(parameters, months, volume)
    end = segments[-1][1]
    test_start = _time_before(segments, end, parameters.integer("split.test_rows") * scale)
    embargo_start = test_start - int(parameters.integer("split.embargo_days")) * _DAY
    validation_rows = parameters.integer("split.validation_rows") * scale
    validation_start = _time_before(segments, embargo_start, validation_rows)
    calibration = parameters.number("split.calibration_fraction_of_validation")
    calibration_start = _time_before(segments, embargo_start, validation_rows * calibration)
    return SplitPlan(validation_start, calibration_start, embargo_start, test_start, end)


def fraud_ramp(
    parameters: ParameterSet, months: tuple[str, ...], volume: tuple[int, ...], split: SplitPlan
) -> tuple[float, ...]:
    """Monthly true fraud rate ``a + b * month`` meeting the overall and test-period targets."""
    overall = parameters.number("fraud.fraud_rate_overall")
    test = parameters.number("fraud.fraud_rate_test")
    weights, test_weights = [], []
    for m, v in zip(months, volume, strict=True):
        lo, hi = _month_bounds(m)
        planned = v * seasonal_factor(parameters, m)
        weights.append(planned)
        test_weights.append(planned * max(0, hi - max(lo, split.test_start)) / (hi - lo))
    index = range(len(months))
    s0, s1 = sum(weights), sum(i * w for i, w in zip(index, weights, strict=True))
    t0, t1 = sum(test_weights), sum(i * w for i, w in zip(index, test_weights, strict=True))
    slope = (test * t0 - overall * s0 * t0 / s0) / (t1 - s1 * t0 / s0)
    intercept = (overall * s0 - slope * s1) / s0
    rates = tuple(intercept + slope * i for i in index)
    if min(rates) <= 0:
        raise ParameterError("the fraud-rate targets imply a non-positive monthly rate")
    return rates
