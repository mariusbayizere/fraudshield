"""Derived simulation settings, computed once from the parameter files (ADR 0022)."""

from __future__ import annotations

import datetime as dt
from dataclasses import dataclass

from fraudshield_dataset.generator.countries import CountryPack, simulated
from fraudshield_dataset.params import ParameterError, ParameterSet

CHANNELS = ("MOBILE_MONEY", "USSD", "AGENT_BANKING", "CARD", "ONLINE", "BANK_TRANSFER")
SEGMENTS = ("urban_salaried", "informal_trader", "rural_ussd", "student")
# Which segments live in towns: the urban share of the customer population is sourced (census
# usage-weighted), so the assumed split between segments has to add up to it.
URBAN_SEGMENTS = ("urban_salaried", "student")
URBAN_SHARE_TOLERANCE = 0.005
FITTING_PASSES = 200
FITTING_TOLERANCE = 1e-12


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
    # ISO 3166-1 alpha-2 codes this run simulates, derived from the packs named by
    # geography.country_share. Never a literal in code: naming a country there is what ADR 0023
    # forbids, and the Country Z test fails if adding one needs a code change.
    countries: tuple[str, ...]
    packs: dict[str, CountryPack]
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
    """Per-segment channel mixes whose mixture is the SRS channel mix exactly (ML-DATA-03).

    Each segment has *relative* channel preferences (a rural customer reaches for USSD, a salaried
    one for a card) rather than absolute shares, and the mixture of those preferences will not
    match the SRS mix on its own. Iterative proportional fitting scales the channels until it
    does: every pass multiplies each channel by the ratio of its target to its current mixture,
    then renormalises each segment to sum to one. The relative ordering inside a segment survives,
    and the mix is met by construction at any segment split.

    Solving absolute shares instead, with one segment fixed and the others absorbing the
    remainder, broke as soon as the population became mostly rural: the remainder pushed urban
    customers to a fifth of their payments on cards and almost nothing through agents.
    """
    target = parameters.mapping("channels.channel_share")
    segments = parameters.mapping("population.segment_share")
    check_urban_share(parameters)
    preference = parameters.value("behaviour.segment_channel_preference")
    if not isinstance(preference, dict) or set(preference) != set(SEGMENTS):
        raise ParameterError(
            "behaviour.segment_channel_preference must give weights for exactly "
            f"{SEGMENTS}, got {sorted(preference) if isinstance(preference, dict) else preference}"
        )
    shares: dict[str, dict[str, float]] = {}
    for segment, weights in preference.items():
        if not isinstance(weights, dict) or set(weights) != set(CHANNELS):
            raise ParameterError(f"segment {segment} must have a weight for every channel")
        total = float(sum(weights.values()))
        if total <= 0 or min(float(w) for w in weights.values()) <= 0:
            raise ParameterError(f"segment {segment} needs a positive weight for every channel")
        shares[segment] = {c: float(weights[c]) / total for c in CHANNELS}
    for _ in range(FITTING_PASSES):
        mixture = {c: sum(segments[s] * shares[s][c] for s in SEGMENTS) for c in CHANNELS}
        if max(abs(mixture[c] - target[c]) for c in CHANNELS) < FITTING_TOLERANCE:
            return shares
        for segment in SEGMENTS:
            scaled = {c: shares[segment][c] * target[c] / mixture[c] for c in CHANNELS}
            total = sum(scaled.values())
            shares[segment] = {c: v / total for c, v in scaled.items()}
    raise ParameterError(
        "channel preferences could not be fitted to the SRS mix in "
        f"{FITTING_PASSES} passes; check channels.channel_share and the segment weights"
    )


def check_urban_share(parameters: ParameterSet) -> None:
    """The urban segments must sum to the sourced urban share of customers (ML-DATA-05).

    The share of customers living in towns is a sourced figure; how those customers divide between
    the behavioural segments is a modelling choice. Checking the sum here keeps the choice from
    quietly contradicting the source.
    """
    segments = parameters.mapping("population.segment_share")
    urban = sum(segments[s] for s in URBAN_SEGMENTS)
    target = parameters.number("population.urban_share_of_customers")
    if abs(urban - target) > URBAN_SHARE_TOLERANCE:
        raise ParameterError(
            f"population.segment_share puts {urban:.3f} of customers in urban segments "
            f"{URBAN_SEGMENTS}, but population.urban_share_of_customers is {target:.3f}"
        )


def build_config(
    parameters: ParameterSet, seed: int, total_rows: int | None = None
) -> SimulationConfig:
    packs = simulated(parameters)
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
        countries=tuple(packs),
        packs=packs,
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
