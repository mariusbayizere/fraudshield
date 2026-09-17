"""Customers, merchants and agents (ADR 0022).

Each customer's static attributes and account events come from that customer's own keyed stream,
so a customer is identical whichever shard batch simulates it. Pools of merchants and agents are
sized from expected counts, not from which customers happen to be simulated.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from functools import cached_property

import numpy as np

from fraudshield_dataset.generator.config import COUNTRIES, SEGMENTS, SimulationConfig
from fraudshield_dataset.generator.keys import stream, token
from fraudshield_dataset.normal import inverse_cdf


@dataclass(frozen=True)
class AccountEvent:
    month: int
    day: int
    seconds: int
    kind: str  # SIM_SWAP | DEVICE_CHANGE


@dataclass(frozen=True)
class Customer:
    index: int
    join_month: int
    country: str
    segment: str
    kyc_tier: str
    activity: float
    home: tuple[float, float]
    salary_day: int
    market_weekday: int
    merchants: tuple[int, ...]
    agent: int
    counterparties: tuple[tuple[int, str], ...]  # (customer index, country)
    events: tuple[AccountEvent, ...]
    seed: int

    @property
    def has_smartphone(self) -> bool:
        return self.segment != "rural_ussd"

    @cached_property
    def account(self) -> str:
        return token(self.seed, "account", self.index)

    def device_at(self, month: int, day: int, seconds: int) -> str | None:
        """The customer's device token at a moment; a new device after each device change."""
        if not self.has_smartphone:
            return None
        generation = sum(
            1
            for e in self.events
            if e.kind == "DEVICE_CHANGE" and (e.month, e.day, e.seconds) <= (month, day, seconds)
        )
        return token(self.seed, "device", self.index, generation)


class Population:
    def __init__(self, config: SimulationConfig) -> None:
        self.config = config
        p = config.parameters
        self._country_share = p.mapping("geography.country_share")
        self._segment_share = p.mapping("population.segment_share")
        self._kyc_share = p.mapping("population.kyc_tier_share")
        self._centres = {
            k: tuple(v) for k, v in _pairs(p.value("currencies.country_centre")).items()
        }
        self._activity_sigma = p.number("population.activity_log_sigma")
        low, high = _int_pair(p.value("behaviour.salary_day_range"))
        self._salary_days = (low, high)
        self._sim_swap = p.number("population.legit_sim_swap_monthly_probability")
        self._device_change = p.number("population.device_change_monthly_probability")
        self._counterparties = p.number("population.counterparties_per_customer")
        self._home_spread = p.number("population.home_spread_degrees")
        self._merchants_range = _int_pair(p.value("population.merchants_per_customer"))
        self._min_counterparties = p.integer("population.min_counterparties")
        self.corridors = {
            k: list(v) for k, v in _lists(p.value("behaviour.remittance_corridors")).items()
        }
        total = config.customers_total
        self.merchants_per_country = {
            c: max(
                3,
                math.ceil(
                    total * self._country_share[c] / p.number("population.customers_per_merchant")
                ),
            )
            for c in COUNTRIES
        }
        self.agents_per_country = {
            c: max(
                2,
                math.ceil(
                    total * self._country_share[c] / p.number("population.customers_per_agent")
                ),
            )
            for c in COUNTRIES
        }
        mcc_share = p.mapping("behaviour.merchant_mcc_share")
        self._mcc_codes = list(mcc_share)
        self._mcc_weights = np.array([mcc_share[c] for c in self._mcc_codes])
        self._mcc_weights /= self._mcc_weights.sum()

    def _offset(self, name: str, *parts: str) -> float:
        return float(stream(self.config.seed, "stratification", name, *parts).random())

    @cached_property
    def _apportioned(self) -> tuple[list[str], list[str], list[float]]:
        """Country, segment and activity multiplier per customer index.

        Countries are apportioned sequentially (each index goes to the country furthest below its
        quota), so **every prefix** of the population matches the SRS country mix within one
        customer: the mix holds while the population grows month by month, without sampling noise
        (ML-DATA-05). Segments are apportioned the same way within each country. Activity
        multipliers are placed on the log-normal's quantiles rather than drawn, and rescaled so
        that they average exactly one within each country and segment. The heavy tail is kept, but
        the planned volume per country and segment no longer depends on where a finite sample
        happens to land in that tail, which is what made small runs miss their row count, their
        country mix (ML-DATA-05) and, through the segment mix, their channel mix (ML-DATA-03).
        """
        total = self.config.customers_total
        countries = sorted(self._country_share)
        segments = sorted(self._segment_share)
        country_counts = dict.fromkeys(countries, 0)
        segment_counts = {c: dict.fromkeys(segments, 0) for c in countries}
        assigned_country: list[str] = []
        assigned_segment: list[str] = []
        activity: list[float] = []
        cells: dict[tuple[str, str], list[int]] = {}
        sigma = self._activity_sigma
        for index in range(total):
            country = max(
                countries,
                key=lambda c: self._country_share[c] * (index + 1) - country_counts[c],
            )
            country_counts[country] += 1
            rank = country_counts[country]
            segment = max(
                segments,
                key=lambda t: self._segment_share[t] * rank - segment_counts[country][t],
            )
            segment_counts[country][segment] += 1
            # The quantile sequence runs within the (country, segment) cell, so activity is not a
            # function of the apportionment order and stays independent of the segment a customer
            # lands in; the channel mix then follows the segment mix rather than the ordering.
            cell_rank = segment_counts[country][segment]
            point = (self._offset("activity", country, segment) + cell_rank * _GOLDEN) % 1.0
            assigned_country.append(country)
            assigned_segment.append(segment)
            cells.setdefault((country, segment), []).append(index)
            activity.append(math.exp(sigma * inverse_cdf(point) - sigma**2 / 2))
        for members in cells.values():
            mean = sum(activity[i] for i in members) / len(members)
            for i in members:
                activity[i] /= mean
        return assigned_country, assigned_segment, activity

    @cached_property
    def activity_array(self) -> np.ndarray:
        """Activity multiplier per customer index, for the volume apportionment."""
        return np.array(self._apportioned[2], dtype=np.float64)

    @cached_property
    def cell_codes(self) -> np.ndarray:
        """Country-and-segment cell per customer index; volume is apportioned within a cell."""
        countries, segments, _ = self._apportioned
        names = sorted({(c, s) for c, s in zip(countries, segments, strict=True)})
        code = {cell: i for i, cell in enumerate(names)}
        return np.array(
            [code[(c, s)] for c, s in zip(countries, segments, strict=True)], dtype=np.int64
        )

    def country_of(self, index: int) -> str:
        return self._apportioned[0][index]

    def segment_of(self, index: int) -> str:
        return self._apportioned[1][index]

    def activity_of(self, index: int) -> float:
        return self._apportioned[2][index]

    def join_month(self, index: int) -> int:
        for month, active in enumerate(self.config.customers_active):
            if index < active:
                return month
        raise ValueError(f"customer {index} is beyond the simulated population")

    def customer(self, index: int) -> Customer:
        seed = self.config.seed
        rng = stream(seed, "customer", index)
        # Country and segment are stratified (low-discrepancy sequences), not drawn independently,
        # so the SRS country mix is met without sampling noise; other attributes use the stream.
        country = self.country_of(index)
        segment = self.segment_of(index)
        kyc = _choice(rng, self._kyc_share)
        activity = self.activity_of(index)
        centre = self._centres[country]
        home = (
            float(centre[0] + rng.normal(0, self._home_spread)),
            float(centre[1] + rng.normal(0, self._home_spread)),
        )
        salary_day = int(rng.integers(self._salary_days[0], self._salary_days[1] + 1))
        market_weekday = int(rng.integers(0, 7))
        merchant_count = int(rng.integers(self._merchants_range[0], self._merchants_range[1] + 1))
        merchants = tuple(
            int(m) for m in rng.integers(0, self.merchants_per_country[country], merchant_count)
        )
        agent = int(rng.integers(0, self.agents_per_country[country]))
        n_counterparties = max(self._min_counterparties, int(rng.poisson(self._counterparties)))
        counterparties = []
        for _ in range(n_counterparties):
            # Counterparties are other simulated customers, so money lands in real accounts.
            other = int(rng.integers(0, self.config.customers_total))
            counterparties.append(
                (other if other != index else (other + 1) % self.config.customers_total, country)
            )
        events = []
        join = self.join_month(index)
        for month in range(join, len(self.config.months)):
            if rng.random() < self._sim_swap:
                events.append(
                    AccountEvent(
                        month, int(rng.integers(1, 28)), int(rng.integers(0, 86400)), "SIM_SWAP"
                    )
                )
            if segment != "rural_ussd" and rng.random() < self._device_change:
                events.append(
                    AccountEvent(
                        month,
                        int(rng.integers(1, 28)),
                        int(rng.integers(0, 86400)),
                        "DEVICE_CHANGE",
                    )
                )
        return Customer(
            index=index,
            join_month=join,
            country=country,
            segment=segment,
            kyc_tier=kyc,
            activity=activity,
            home=home,
            salary_day=salary_day,
            market_weekday=market_weekday,
            merchants=merchants,
            agent=agent,
            counterparties=tuple(counterparties),
            events=tuple(events),
            seed=seed,
        )

    def merchant_token(self, country: str, merchant: int) -> str:
        return token(self.config.seed, "merchant", country, merchant)

    def merchant_mcc(self, country: str, merchant: int) -> str:
        rng = stream(self.config.seed, "merchant", country, merchant)
        return self._mcc_codes[int(rng.choice(len(self._mcc_codes), p=self._mcc_weights))]

    def agent_token(self, country: str, agent: int) -> str:
        return token(self.config.seed, "agent", country, agent)

    def agent_account(self, country: str, agent: int) -> str:
        return token(self.config.seed, "agent-account", country, agent)


_GOLDEN = (math.sqrt(5.0) - 1.0) / 2.0


def _choice(rng: np.random.Generator, shares: dict[str, float]) -> str:
    keys = list(shares)
    weights = np.array([shares[k] for k in keys], dtype=float)
    return keys[int(rng.choice(len(keys), p=weights / weights.sum()))]


def _pairs(value: object) -> dict[str, list[float]]:
    if not isinstance(value, dict):
        raise TypeError("expected a mapping of coordinate pairs")
    return {str(k): [float(x) for x in v] for k, v in value.items()}


def _lists(value: object) -> dict[str, list[str]]:
    if not isinstance(value, dict):
        raise TypeError("expected a mapping of lists")
    return {str(k): [str(x) for x in v] for k, v in value.items()}


def _int_pair(value: object) -> tuple[int, int]:
    if not (isinstance(value, list) and len(value) == 2):
        raise TypeError("expected a two-element list")
    return int(value[0]), int(value[1])


__all__ = ["SEGMENTS", "AccountEvent", "Customer", "Population"]
