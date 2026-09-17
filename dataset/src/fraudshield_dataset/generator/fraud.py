"""Eight fraud scenarios (Part E.3, ML-DATA-04, ADR 0022 section 6).

Design notes per scenario: ``docs/ml/scenarios/``. Scenarios are described at the level of the
signals they leave, not as instructions. Every fraudulent row is written through the same
:class:`~fraudshield_dataset.generator.schema.Rows` buffer, formats and null rules as legitimate
rows, so only behaviour distinguishes them.

Calibration: for each scenario and month the incident probability per eligible customer is the
target number of fraudulent rows (monthly volume x ramped fraud rate x scenario share) divided by
the expected rows per incident and the expected number of eligible customers. Role membership
(mule accounts, synthetic identities, compromised agents, colluding merchants) comes from
per-entity streams, so it does not depend on which batch simulates a customer.
"""

from __future__ import annotations

import calendar
from dataclasses import dataclass
from functools import cached_property

import numpy as np

from fraudshield_dataset.generator.config import SimulationConfig, seasonal_factor
from fraudshield_dataset.generator.keys import stream, token, transaction_uuid
from fraudshield_dataset.generator.legit import (
    CASH_MCC,
    P2P_MCC,
    LegitimateBehaviour,
    month_start_micros,
)
from fraudshield_dataset.generator.population import Customer, Population
from fraudshield_dataset.generator.schema import Rows
from fraudshield_dataset.params import ParameterError

SCENARIOS = (
    "sim_swap",
    "account_takeover",
    "agent_fraud",
    "velocity",
    "card_not_present",
    "mule_account",
    "merchant_fraud",
    "synthetic_identity",
)
NOVEL_VARIANT = "novel_esim_delayed_drain"
ADAPTED_VARIANT = "adapted_below_threshold"
BASE_VARIANT = "base"
_MINUTE = 60 * 1_000_000
_DAY = 86_400_000_000


@dataclass(frozen=True)
class FraudEvent:
    account_id: str
    kind: str
    timestamp: int


@dataclass(frozen=True)
class Payment:
    timestamp: int
    channel: str
    counterparty: str
    mcc: str
    device: str | None
    agent_id: str | None = None
    destination: str | None = None
    round_sum: bool = False


@dataclass
class Incident:
    scenario: str
    customer: Customer
    month_index: int
    rng: np.random.Generator
    variant: str = BASE_VARIANT
    sequence: int = 0
    day: int = 1
    seconds: int = 0


class FraudModel:
    def __init__(
        self, config: SimulationConfig, population: Population, legitimate: LegitimateBehaviour
    ) -> None:
        self.config = config
        self.population = population
        self.legitimate = legitimate
        p = config.parameters
        self.share = p.mapping("fraud.scenario_share")
        if set(self.share) != set(SCENARIOS):
            raise ParameterError("fraud.scenario_share must name exactly the eight scenarios")
        ranges = p.value("fraud.rows_per_incident")
        if not isinstance(ranges, dict):
            raise ParameterError("fraud.rows_per_incident must map scenarios to ranges")
        self.rows_range = {s: (int(ranges[s][0]), int(ranges[s][1])) for s in SCENARIOS}
        self.multiplier = p.mapping("fraud.amount_multiplier")
        low, high = p.numbers("fraud.burst_minutes")
        self.burst = (int(low), int(high))
        lead_low, lead_high = p.numbers("fraud.takeover_lead_minutes")
        self.lead = (int(lead_low), int(lead_high))
        self.night = p.number("fraud.night_probability")
        self.new_device = p.number("fraud.new_device_probability")
        self.mule_fraction = p.number("fraud.mule_account_fraction")
        self.agent_fraction = p.number("fraud.compromised_agent_fraction")
        self.merchant_fraction = p.number("fraud.colluding_merchant_fraction")
        self.synthetic_fraction = p.number("fraud.synthetic_identity_fraction")
        bust_low, bust_high = p.numbers("fraud.synthetic_identity_bust_out_months")
        self.bust = (int(bust_low), int(bust_high))
        self.adapt_start = p.integer("fraud.adaptation_start_month")
        self.threshold = p.number("fraud.rule_amount_threshold_rwf")
        band_low, band_high = p.numbers("fraud.adaptation_band")
        self.band = (band_low, band_high)
        self.adapt_probability = p.number("fraud.adaptation_probability")
        self.novelty_share = p.number("fraud.novelty_share_of_sim_swap")
        delay_low, delay_high = p.numbers("fraud.novelty_delay_days")
        self.novelty_delay = (int(delay_low), int(delay_high))
        # Incidents start early enough that bursts and delayed drains stay in their calendar month.
        self.latest_start_margin_days = self.novelty_delay[1] + 1
        self.mule_cross_border = p.number("fraud.mule_cross_border_share")
        self.smartphone_share = 1.0 - p.mapping("population.segment_share")["rural_ussd"]
        self.to_mule = p.number("fraud.drain_to_mule_probability")
        self.choice = p.mapping("fraud.scenario_channel_share")
        self.probability = {s: self._probabilities(s) for s in SCENARIOS}

    # --- calibration -------------------------------------------------------------------------

    def _joiners(self, month: int) -> int:
        active = self.config.customers_active
        return active[month] - (active[month - 1] if month > 0 else 0)

    def _eligible(self, scenario: str, month: int) -> float:
        active = self.config.customers_active[month]
        if scenario in ("account_takeover", "card_not_present", "merchant_fraud"):
            return active * self.smartphone_share
        if scenario == "mule_account":
            return active * self.mule_fraction
        if scenario == "synthetic_identity":
            low, high = self.bust
            joined = [self._joiners(month - k) for k in range(low, high + 1) if month - k >= 0]
            return self.synthetic_fraction * sum(joined) / (high - low + 1)
        return float(active)

    def _probabilities(self, scenario: str) -> tuple[float, ...]:
        low, high = self.rows_range[scenario]
        per_incident = (low + high) / 2
        probabilities = []
        for month, label in enumerate(self.config.months):
            target_rows = (
                self.config.monthly_volume[month]
                * seasonal_factor(self.config.parameters, label)
                * self.config.fraud_rate_by_month[month]
                * self.share[scenario]
            )
            eligible = self._eligible(scenario, month)
            probability = target_rows / (per_incident * eligible) if eligible > 0 else 0.0
            probabilities.append(probability)
        if max(probabilities) > 1.0:
            raise ParameterError(
                f"scenario {scenario}: calibration needs more eligible customers than exist "
                f"(incident probability {max(probabilities):.2f} > 1); raise its role fraction"
            )
        return tuple(probabilities)

    # --- roles --------------------------------------------------------------------------------

    def is_mule(self, index: int) -> bool:
        return bool(stream(self.config.seed, "role", "mule", index).random() < self.mule_fraction)

    def bust_out_month(self, customer: Customer) -> int | None:
        rng = stream(self.config.seed, "role", "synthetic", customer.index)
        if rng.random() >= self.synthetic_fraction:
            return None
        return customer.join_month + int(rng.integers(self.bust[0], self.bust[1] + 1))

    @cached_property
    def mules_by_country(self) -> dict[str, list[int]]:
        mules: dict[str, list[int]] = {}
        for index in range(self.config.customers_total):
            if self.is_mule(index):
                mules.setdefault(self.population.country_of(index), []).append(index)
        return mules

    def _members(self, kind: str, country: str, pool: int, fraction: float) -> list[int]:
        members = [
            m
            for m in range(pool)
            if stream(self.config.seed, "role", kind, country, m).random() < fraction
        ]
        return members or [int(stream(self.config.seed, "role", kind, country).integers(0, pool))]

    @cached_property
    def compromised_agents(self) -> dict[str, list[int]]:
        return {
            c: self._members("agent", c, n, self.agent_fraction)
            for c, n in self.population.agents_per_country.items()
        }

    @cached_property
    def colluding_merchants(self) -> dict[str, list[int]]:
        return {
            c: self._members("merchant", c, n, self.merchant_fraction)
            for c, n in self.population.merchants_per_country.items()
        }

    # --- simulation ---------------------------------------------------------------------------

    def __call__(
        self, customer: Customer, month: int, rows: Rows, events: list[FraudEvent]
    ) -> None:
        if month < customer.join_month:
            return
        label = self.config.months[month]
        for scenario in SCENARIOS:
            if not self._eligible_customer(scenario, customer, month):
                continue
            rng = stream(self.config.seed, "fraud", scenario, customer.index, label)
            if rng.random() < self.probability[scenario][month]:
                incident = Incident(scenario, customer, month, rng)
                getattr(self, f"_{scenario}")(incident, rows, events)

    def _eligible_customer(self, scenario: str, customer: Customer, month: int) -> bool:
        if scenario in ("account_takeover", "card_not_present", "merchant_fraud"):
            return customer.has_smartphone
        if scenario == "mule_account":
            return self.is_mule(customer.index)
        if scenario == "synthetic_identity":
            return self.bust_out_month(customer) == month
        return True

    def _start(self, incident: Incident) -> int:
        """UTC start of an incident, early enough that the whole incident stays in its month."""
        rng, customer = incident.rng, incident.customer
        label = self.config.months[incident.month_index]
        days = calendar.monthrange(int(label[:4]), int(label[5:]))[1]
        day = int(rng.integers(1, days - self.latest_start_margin_days + 1))
        if rng.random() < self.night:
            seconds = int(rng.integers(0, self.legitimate.night_end * 3600))
        else:
            seconds = self.legitimate.local_seconds(rng, "MOBILE_MONEY")
        incident.day, incident.seconds = day, seconds
        offset = self.legitimate.offset[customer.country] * 3600
        return (
            month_start_micros(label)
            + (day - 1) * _DAY
            + (seconds - offset) * 1_000_000
            + int(rng.integers(0, 1_000_000))
        )

    def _times(self, incident: Incident, start: int) -> list[int]:
        low, high = self.rows_range[incident.scenario]
        count = int(incident.rng.integers(low, high + 1))
        span = int(incident.rng.integers(self.burst[0], self.burst[1] + 1)) * _MINUTE
        return sorted(start + int(t) for t in incident.rng.integers(0, span, size=count))

    def _device(self, incident: Incident, channel: str, fraud_device: bool) -> str | None:
        customer = incident.customer
        if channel == "USSD" or not customer.has_smartphone:
            return None
        if fraud_device:
            return token(
                self.config.seed,
                "fraud-device",
                incident.scenario,
                customer.index,
                incident.month_index,
            )
        return customer.device_at(incident.month_index, incident.day, incident.seconds)

    def _new_counterparty(self, incident: Incident) -> str:
        return token(
            self.config.seed,
            "fraud-counterparty",
            incident.scenario,
            incident.customer.index,
            incident.month_index,
            incident.sequence,
        )

    def _mule_or_new(self, incident: Incident) -> str:
        mules = [
            m
            for m in self.mules_by_country.get(incident.customer.country, [])
            if m != incident.customer.index
        ]
        if mules and incident.rng.random() < self.to_mule:
            return token(
                self.config.seed, "account", mules[int(incident.rng.integers(0, len(mules)))]
            )
        return self._new_counterparty(incident)

    def _add(self, incident: Incident, rows: Rows, payment: Payment) -> None:
        timestamp, channel, counterparty = payment.timestamp, payment.channel, payment.counterparty
        mcc, device, agent_id = payment.mcc, payment.device, payment.agent_id
        destination, round_sum = payment.destination, payment.round_sum
        rng, customer = incident.rng, incident.customer
        median = self.legitimate.median[channel] * self.multiplier[incident.scenario]
        amount_rwf = median * float(rng.lognormal(0.0, self.legitimate.sigma))
        variant = incident.variant
        if (
            incident.month_index >= self.adapt_start
            and amount_rwf > self.threshold
            and rng.random() < self.adapt_probability
        ):
            amount_rwf = self.threshold * float(rng.uniform(*self.band))
            if variant == BASE_VARIANT:
                variant = ADAPTED_VARIANT
        currency, local, rwf = self.legitimate.money.local(customer.country, amount_rwf, round_sum)
        latitude, longitude = self.legitimate.location(rng, customer)
        rows.add(
            transaction_id=transaction_uuid(
                self.config.seed,
                "fraud",
                incident.scenario,
                customer.index,
                incident.month_index,
                incident.sequence,
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
            device_fingerprint=device,
            agent_id=agent_id,
            counterparty_country=destination or customer.country,
            transaction_timestamp=timestamp,
            is_fraud_true=True,
            fraud_type=incident.scenario,
            scenario_variant=variant,
        )
        incident.sequence += 1

    def _wallet_channel(self, customer: Customer) -> str:
        return "MOBILE_MONEY" if customer.has_smartphone else "USSD"

    def _random_merchant(
        self, incident: Incident, merchants: list[int] | None = None
    ) -> tuple[str, str]:
        country = incident.customer.country
        pool = merchants or list(range(self.population.merchants_per_country[country]))
        merchant = pool[int(incident.rng.integers(0, len(pool)))]
        return self.population.merchant_token(country, merchant), self.population.merchant_mcc(
            country, merchant
        )

    # --- the eight scenarios (signals: docs/ml/scenarios/) ------------------------------------

    def _sim_swap(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        rng, customer = incident.rng, incident.customer
        start = self._start(incident)
        lead = int(rng.integers(self.lead[0], self.lead[1] + 1)) * _MINUTE
        novel = start - lead >= self.config.split.test_start and rng.random() < self.novelty_share
        if novel:
            # Novel sub-variant, test period only: re-provisioning seen as a device change, and a
            # drain delayed by days rather than minutes.
            incident.variant = NOVEL_VARIANT
            events.append(FraudEvent(customer.account, "DEVICE_CHANGE", start - lead))
            start += int(rng.integers(self.novelty_delay[0], self.novelty_delay[1] + 1)) * _DAY
        else:
            events.append(FraudEvent(customer.account, "SIM_SWAP", start - lead))
        channel = (
            "BANK_TRANSFER" if novel and customer.has_smartphone else self._wallet_channel(customer)
        )
        fraud_device = customer.has_smartphone and rng.random() < self.new_device
        for timestamp in self._times(incident, start):
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    channel,
                    self._mule_or_new(incident),
                    P2P_MCC,
                    self._device(incident, channel, fraud_device),
                    round_sum=rng.random() < self.choice["sim_swap_round_sum"],
                ),
            )

    def _account_takeover(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        rng, customer = incident.rng, incident.customer
        start = self._start(incident)
        lead = int(rng.integers(self.lead[0], self.lead[1] + 1)) * _MINUTE
        events.append(FraudEvent(customer.account, "DEVICE_CHANGE", start - lead))
        for timestamp in self._times(incident, start):
            channel = ("ONLINE", "MOBILE_MONEY", "BANK_TRANSFER")[int(rng.integers(0, 3))]
            if channel == "ONLINE":
                counterparty, mcc = self._random_merchant(incident)
            else:
                counterparty, mcc = self._mule_or_new(incident), P2P_MCC
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    channel,
                    counterparty,
                    mcc,
                    self._device(incident, channel, fraud_device=True),
                ),
            )

    def _agent_fraud(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        del events
        country = incident.customer.country
        agents = self.compromised_agents[country]
        agent = agents[int(incident.rng.integers(0, len(agents)))]
        for timestamp in self._times(incident, self._start(incident)):
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    "AGENT_BANKING",
                    self.population.agent_account(country, agent),
                    CASH_MCC,
                    self._device(incident, "AGENT_BANKING", fraud_device=False),
                    agent_id=self.population.agent_token(country, agent),
                    round_sum=True,
                ),
            )

    def _velocity(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        del events
        rng, customer = incident.rng, incident.customer
        start = self._start(incident)
        for timestamp in self._times(incident, start):
            if customer.has_smartphone and rng.random() < self.choice["velocity_card"]:
                channel = "CARD"
                counterparty, mcc = self._random_merchant(incident)
            else:
                channel = self._wallet_channel(customer)
                counterparty, mcc = self._new_counterparty(incident), P2P_MCC
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    channel,
                    counterparty,
                    mcc,
                    self._device(incident, channel, fraud_device=False),
                ),
            )

    def _card_not_present(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        del events
        rng = incident.rng
        fraud_device = rng.random() < self.new_device
        for timestamp in self._times(incident, self._start(incident)):
            channel = "ONLINE" if rng.random() < self.choice["card_not_present_online"] else "CARD"
            counterparty, mcc = self._random_merchant(incident)
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    channel,
                    counterparty,
                    mcc,
                    self._device(incident, channel, fraud_device),
                ),
            )

    def _mule_account(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        del events
        rng, customer = incident.rng, incident.customer
        for timestamp in self._times(incident, self._start(incident)):
            transfer = customer.has_smartphone and rng.random() < self.choice["mule_bank_transfer"]
            channel = "BANK_TRANSFER" if transfer else self._wallet_channel(customer)
            destination = customer.country
            if rng.random() < self.mule_cross_border:
                corridors = self.population.corridors[customer.country]
                destination = corridors[int(rng.integers(0, len(corridors)))]
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    channel,
                    self._mule_or_new(incident),
                    P2P_MCC,
                    self._device(incident, channel, fraud_device=False),
                    destination=destination,
                    round_sum=rng.random() < self.choice["mule_round_sum"],
                ),
            )

    def _merchant_fraud(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        del events
        country = incident.customer.country
        for timestamp in self._times(incident, self._start(incident)):
            counterparty, mcc = self._random_merchant(incident, self.colluding_merchants[country])
            self._add(
                incident,
                rows,
                Payment(
                    timestamp,
                    "CARD",
                    counterparty,
                    mcc,
                    self._device(incident, "CARD", fraud_device=False),
                ),
            )

    def _synthetic_identity(self, incident: Incident, rows: Rows, events: list[FraudEvent]) -> None:
        del events
        rng, customer = incident.rng, incident.customer
        for timestamp in self._times(incident, self._start(incident)):
            if rng.random() < self.choice["synthetic_cash_out"]:
                country = customer.country
                agent = customer.agent
                self._add(
                    incident,
                    rows,
                    Payment(
                        timestamp,
                        "AGENT_BANKING",
                        self.population.agent_account(country, agent),
                        CASH_MCC,
                        self._device(incident, "AGENT_BANKING", fraud_device=False),
                        agent_id=self.population.agent_token(country, agent),
                        round_sum=True,
                    ),
                )
            else:
                channel = self._wallet_channel(customer)
                self._add(
                    incident,
                    rows,
                    Payment(
                        timestamp,
                        channel,
                        self._mule_or_new(incident),
                        P2P_MCC,
                        self._device(incident, channel, fraud_device=False),
                    ),
                )
