"""Legitimate customer behaviour for one customer-month (Part E.3, ADR 0022).

Salary cycles, market days, school-fee peaks, family remittances along EAC corridors, round-sum
transfers, cash-agent peaks and rural USSD use. Every draw comes from the customer-month stream.
"""

from __future__ import annotations

import datetime as dt
import math
from dataclasses import dataclass
from decimal import Decimal

import numpy as np

from fraudshield_dataset.generator.config import CHANNELS, SimulationConfig, seasonal_factor
from fraudshield_dataset.generator.daily import Rhythm, day_weights
from fraudshield_dataset.generator.keys import stream, token, transaction_uuid
from fraudshield_dataset.generator.population import Customer, Population
from fraudshield_dataset.generator.schema import Rows

# ISO 4217 List One, published 2026-09-17: RWF and UGX have no minor unit, KES, TZS and CDF have
# two (see currencies.currency_by_country for the citation). The Java side asserts the same table
# against the JDK's ISO data (D-43).
MINOR_UNITS = {"RWF": 0, "UGX": 0, "KES": 2, "TZS": 2, "CDF": 2}
P2P_MCC = "4829"
CASH_MCC = "6011"
_EPOCH = dt.datetime(1970, 1, 1, tzinfo=dt.UTC)


@dataclass(frozen=True)
class Moment:
    """Where one transaction sits: its month, sequence number, channel and local and UTC time."""

    month_index: int
    sequence: int
    channel: str
    day: int
    seconds: int
    timestamp: int


class Money:
    """Local amounts in minor units, rounded like a real payment of that currency."""

    def __init__(self, config: SimulationConfig) -> None:
        p = config.parameters
        self.currency = {
            k: str(v) for k, v in _mapping(p.value("currencies.currency_by_country")).items()
        }
        self.rate = p.mapping("currencies.rwf_per_unit")

    def local(
        self, country: str, amount_rwf: float, round_sum: bool
    ) -> tuple[str, Decimal, Decimal]:
        currency = self.currency[country]
        value = amount_rwf / self.rate[currency]
        if round_sum:
            step = 10 ** max(0, int(np.floor(np.log10(max(value, 1.0)))) - 1)
            value = max(step, round(value / step) * step)
        places = MINOR_UNITS[currency]
        local = Decimal(str(round(value, places))).quantize(Decimal(1).scaleb(-4))
        if local <= 0:
            local = Decimal(1).scaleb(-places).quantize(Decimal(1).scaleb(-4))
        rwf = (local * Decimal(str(self.rate[currency]))).quantize(Decimal(1).scaleb(-4))
        return currency, local, rwf


def month_start_micros(month: str) -> int:
    start = dt.datetime.fromisoformat(f"{month}-01T00:00:00+00:00")
    return int((start - _EPOCH).total_seconds()) * 1_000_000


class LegitimateBehaviour:
    def __init__(self, config: SimulationConfig, population: Population) -> None:
        self.config = config
        self.population = population
        self.money = Money(config)
        p = config.parameters
        self.mean = p.number("population.mean_transactions_per_active_customer_month")
        self.sigma = p.number("behaviour.amount_log_sigma")
        # Sourced means become the medians the log-normal draw needs; channels without a published
        # figure keep an assumed median, so provenance stays per channel rather than averaged.
        assumed = p.mapping("behaviour.amount_median_rwf")
        sourced = p.mapping("behaviour.amount_mean_rwf")
        self.median = {
            channel: sourced[channel] / math.exp(self.sigma**2 / 2)
            if channel in sourced
            else assumed[channel]
            for channel in CHANNELS
        }
        self.round_probability = p.number("behaviour.round_sum_probability")
        self.payday_boost = p.number("behaviour.payday_spend_boost")
        self.payday_window = p.integer("behaviour.payday_window_days")
        self.market_weight = p.number("behaviour.market_day_weight")
        self.school_months = {int(m) for m in p.numbers("behaviour.school_fee_months")}
        self.school_boost = p.number("behaviour.school_fee_volume_boost")
        self.cross_border = p.number("behaviour.cross_border_share")
        self.agent_hours = [int(h) for h in p.numbers("behaviour.agent_peak_hours")]
        self.travel = p.number("behaviour.travel_probability")
        self.jitter = p.number("behaviour.home_jitter_degrees")
        self.p2p_share = p.number("behaviour.p2p_share_of_wallet_payments")
        self.offset = {k: int(v) for k, v in p.mapping("currencies.utc_offset_hours").items()}
        self.shares = config.channel_share_by_segment
        self.hour_mean = p.number("behaviour.wallet_hour_mean_local")
        self.hour_sd = p.number("behaviour.wallet_hour_sd")
        self.agent_hour_sd = p.number("behaviour.agent_hour_sd")
        low, high = p.numbers("behaviour.active_hours_local")
        self.active_hours = (low, high)
        self.travel_distance = p.number("behaviour.travel_distance_degrees")
        self.night = p.number("behaviour.night_activity_probability")
        self.volume_sigma = p.number("behaviour.volume_jitter_log_sigma")
        self._planned: dict[int, np.ndarray] = {}
        self.night_end = int(self.active_hours[0])

    def planned_counts(self, month_index: int) -> np.ndarray:
        """Transactions per active customer this month, apportioned to exact cell quotas.

        Each country-and-segment cell has a row quota for the month: its active customers times
        the mean transaction count times the month's seasonal factor. The quota is shared out
        among the cell's customers in proportion to their activity and a month-to-month jitter, by
        prefix flooring, so the counts sum to the quota exactly.

        Drawing each customer's count from a Poisson distribution instead left the country and
        channel mixes with the sampling noise of a few hundred development customers (2.2 points
        at 200K rows, against a 0.5 point tolerance). Here the mixes and the total volume are
        exact at any size (ML-DATA-01, ML-DATA-03, ML-DATA-05) and only the split within a cell is
        random, which keeps a customer's months uneven.
        """
        if month_index in self._planned:
            return self._planned[month_index]
        active = self.config.customers_active[month_index]
        label = self.config.months[month_index]
        seasonal = seasonal_factor(self.config.parameters, label)
        activity = self.population.activity_array[:active]
        cells = self.population.cell_codes[:active]
        # One draw for the whole month, so the counts cannot depend on how customers are batched.
        rng = stream(self.config.seed, "volume", label)
        jitter = np.exp(self.volume_sigma * rng.standard_normal(active) - self.volume_sigma**2 / 2)
        weights = activity * jitter
        counts = np.zeros(active, dtype=np.int64)
        for cell in np.unique(cells):
            members = np.flatnonzero(cells == cell)
            # The quota follows the cell's active customer count, not its activity sum: the
            # counts are apportioned to quota, so a prefix of the activity sequence must not be
            # able to pull a country's row share away from its customer share (ML-DATA-05).
            quota = round(self.mean * seasonal * len(members))
            cumulative = np.cumsum(weights[members])
            if cumulative[-1] <= 0:
                continue
            edges = np.floor(quota * cumulative / cumulative[-1]).astype(np.int64)
            counts[members] = np.diff(np.concatenate(([0], edges)))
        self._planned = {month_index: counts}  # one month at a time keeps memory flat
        return counts

    def planned_rows(self, month_index: int) -> int:
        """Legitimate rows planned for this month: the sum of every cell's quota."""
        return int(self.planned_counts(month_index).sum())

    def day_weights(self, customer: Customer, year: int, month: int) -> np.ndarray:
        return day_weights(
            segment=customer.segment,
            salary_day=customer.salary_day,
            market_weekday=customer.market_weekday,
            month=(year, month),
            rhythm=Rhythm(self.payday_window, self.payday_boost, self.market_weight),
        )

    def local_seconds(self, rng: np.random.Generator, channel: str) -> int:
        if rng.random() < self.night:
            return int(rng.integers(0, self.night_end * 3600))
        if channel == "AGENT_BANKING":
            hour = float(rng.choice(self.agent_hours)) + rng.normal(0, self.agent_hour_sd)
        else:
            hour = rng.normal(self.hour_mean, self.hour_sd)
        seconds = int(np.clip(hour, *self.active_hours) * 3600) + int(rng.integers(0, 60))
        return min(seconds, 86_399)

    def month(self, customer: Customer, month_index: int) -> Rows:
        """One customer-month of legitimate rows.

        The channel comes from the customer's segment mix, which is fitted to the SRS channel mix
        (ML-DATA-03). Nothing rewrites it afterwards: an earlier version turned a smartphone
        owner's USSD draw into a wallet payment, which quietly moved the realised mix 3.8 points
        away from the fitted one once every segment carried some USSD. Whether a row has a device
        is decided by the channel instead, since a USSD session has no app device.
        """
        rows = Rows()
        if month_index < customer.join_month:
            return rows
        label = self.config.months[month_index]
        year, month = int(label[:4]), int(label[5:])
        rng = stream(self.config.seed, "legit", customer.index, label)
        # Normalised to a yearly mean of one: school-fee months gain volume, the total does not.
        count = int(self.planned_counts(month_index)[customer.index])
        if count == 0:
            return rows
        shares = self.shares[customer.segment]
        channels = rng.choice(len(CHANNELS), size=count, p=np.array([shares[c] for c in CHANNELS]))
        weights = self.day_weights(customer, year, month)
        days = rng.choice(len(weights), size=count, p=weights) + 1
        start = month_start_micros(label)
        for n in range(count):
            channel = CHANNELS[int(channels[n])]
            day = int(days[n])
            seconds = self.local_seconds(rng, channel)
            timestamp = (
                start
                + (day - 1) * 86_400_000_000
                + (seconds - self.offset[customer.country] * 3600) * 1_000_000
                + int(rng.integers(0, 1_000_000))
            )
            moment = Moment(month_index, n, channel, day, seconds, timestamp)
            self.add_payment(rows, rng, customer, moment)
        return rows

    def add_payment(
        self, rows: Rows, rng: np.random.Generator, customer: Customer, moment: Moment
    ) -> None:
        month_index, n, channel = moment.month_index, moment.sequence, moment.channel
        day, seconds, timestamp = moment.day, moment.seconds, moment.timestamp
        country = customer.country
        destination = country
        agent_id = None
        if channel == "AGENT_BANKING":
            counterparty = self.population.agent_account(country, customer.agent)
            agent_id = self.population.agent_token(country, customer.agent)
            mcc = CASH_MCC
            round_sum = rng.random() < self.round_probability
        elif channel in ("CARD", "ONLINE") or (
            channel in ("MOBILE_MONEY", "USSD") and rng.random() >= self.p2p_share
        ):
            merchant = customer.merchants[int(rng.integers(0, len(customer.merchants)))]
            counterparty = self.population.merchant_token(country, merchant)
            mcc = self.population.merchant_mcc(country, merchant)
            round_sum = False
        else:
            other, _ = customer.counterparties[int(rng.integers(0, len(customer.counterparties)))]
            counterparty = token(self.config.seed, "account", other)
            mcc = P2P_MCC
            round_sum = rng.random() < self.round_probability
            if rng.random() < self.cross_border:
                corridors = self.population.corridors[country]
                destination = corridors[int(rng.integers(0, len(corridors)))]
        amount_rwf = self.median[channel] * float(rng.lognormal(0.0, self.sigma))
        currency, local, rwf = self.money.local(country, amount_rwf, round_sum)
        latitude, longitude = self.location(rng, customer)
        rows.add(
            transaction_id=transaction_uuid(
                self.config.seed, "legit", customer.index, month_index, n
            ),
            account_id=customer.account,
            counterparty_id=counterparty,
            amount=local,
            currency=currency,
            amount_rwf=rwf,
            channel=channel,
            merchant_category_code=mcc,
            latitude=latitude,
            longitude=longitude,
            device_fingerprint=None
            if channel == "USSD"
            else customer.device_at(month_index, day, seconds),
            agent_id=agent_id,
            counterparty_country=destination,
            transaction_timestamp=timestamp,
            is_fraud_true=False,
            fraud_type=None,
            scenario_variant=None,
        )

    def location(self, rng: np.random.Generator, customer: Customer) -> tuple[float, float]:
        latitude, longitude = customer.home
        if rng.random() < self.travel:
            latitude += rng.normal(0, self.travel_distance)
            longitude += rng.normal(0, self.travel_distance)
        return (
            round(float(np.clip(latitude + rng.normal(0, self.jitter), -90, 90)), 6),
            round(float(np.clip(longitude + rng.normal(0, self.jitter), -180, 180)), 6),
        )


def _mapping(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise TypeError("expected a mapping")
    return {str(k): v for k, v in value.items()}
