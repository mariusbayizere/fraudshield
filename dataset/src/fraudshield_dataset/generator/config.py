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
        fraud_rate_by_month=fraud_schedule(parameters, months, volume, split),
    )


_DAY = 86_400_000_000
# The schedule must land within 0.02 percentage points of the SRS test-period rate.


def month_bounds(month: str) -> tuple[int, int]:
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
        for lo, hi in (month_bounds(m),)
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


def fraud_schedule(
    parameters: ParameterSet, months: tuple[str, ...], volume: tuple[int, ...], split: SplitPlan
) -> tuple[float, ...]:
    """Monthly true fraud rate from the calibrated intensity schedule (ML-DATA-02, D-07).

    The schedule carries the rising trend and the month-to-month variation. Here it is scaled so
    that the volume-weighted overall rate equals the target exactly, and checked against the
    test-period target, so both SRS figures hold by construction instead of by sampling luck.
    """
    overall = parameters.number("fraud.fraud_rate_overall")
    test_target = parameters.number("fraud.fraud_rate_test")
    intensity = parameters.numbers("fraud.monthly_intensity")
    if len(intensity) != len(months):
        raise ParameterError(
            f"fraud.monthly_intensity has {len(intensity)} values for {len(months)} months"
        )
    weights, test_weights = [], []
    for month, planned in zip(months, volume, strict=True):
        lo, hi = month_bounds(month)
        scaled = planned * seasonal_factor(parameters, month)
        weights.append(scaled)
        test_weights.append(scaled * max(0, hi - max(lo, split.test_start)) / (hi - lo))
    total = sum(weights)
    weighted_mean = sum(i * w for i, w in zip(intensity, weights, strict=True)) / total
    rates = tuple(overall * i / weighted_mean for i in intensity)
    test_total = sum(test_weights)
    if test_total <= 0:
        raise ParameterError("the planned test period carries no volume")
    test_rate = sum(r * w for r, w in zip(rates, test_weights, strict=True)) / test_total
    tolerance = parameters.number("fraud.test_rate_tolerance")
    if abs(test_rate - test_target) > tolerance:
        raise ParameterError(
            f"fraud.monthly_intensity gives a test-period rate of {test_rate:.5f}, more than "
            f"{tolerance} from the {test_target} target; adjust the schedule's trend"
        )
    return rates
