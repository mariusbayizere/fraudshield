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
from bisect import bisect_left
from dataclasses import dataclass
from functools import cached_property

import numpy as np

from fraudshield_dataset.generator.config import SimulationConfig
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
    rows: int = 1
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
        self.mule_cross_border = p.number("fraud.mule_cross_border_share")
        self.smartphone_share = 1.0 - p.mapping("population.segment_share")["rural_ussd"]
        self.to_mule = p.number("fraud.drain_to_mule_probability")
        self.choice = p.mapping("fraud.scenario_channel_share")
        self._plans: dict[tuple[str, int], dict[int, int]] = {}
        self._month_plans: dict[int, tuple[dict[str, int], dict[str, int]]] = {}
        self._validate_scenario_capacity()

    # --- calibration: exact quotas per scenario-month --------------------------------------

    def _joiners(self, month: int) -> int:
        active = self.config.customers_active
        return active[month] - (active[month - 1] if month > 0 else 0)

    def monthly_target(self, month: int) -> int:
        """Fraudulent rows the whole month must produce (ML-DATA-02, the calibrated schedule).

        Fraud rows are added to the legitimate ones, so hitting a fraud *rate* of ``r`` means
        placing ``legitimate * r / (1 - r)`` rows. The legitimate count is exact (see
        :meth:`LegitimateBehaviour.planned_counts`), so the month's fraud rate is the scheduled
        rate up to rounding and to incidents that spill into the next month's file.
        """
        rate = self.config.fraud_rate_by_month[month]
        return round(self.legitimate.planned_rows(month) * rate / (1.0 - rate))

    def allocation(self, month: int) -> dict[str, int]:
        """Fraudulent rows per scenario for this month (ML-DATA-02 exactly, ML-DATA-04 in mix)."""
        return self._month_plan(month)[0]

    def incidents(self, scenario: str, month: int) -> int:
        """How many separate incidents this scenario stages in this month."""
        return self._month_plan(month)[1][scenario]

    def _month_plan(self, month: int) -> tuple[dict[str, int], dict[str, int]]:
        """Rows and incidents per scenario for one month.

        The month's target is split by the ML-DATA-04 shares, turned into a whole number of
        incidents, and only then converted back into row counts that sum to the target exactly.
        Allocating in incidents matters: a scenario whose incidents run from 3 to 40 rows cannot
        deliver 2 rows, so splitting rows first would leave the month beside its target.

        A scenario can be infeasible in a month: at the start of the simulation no customer has
        reached its bust-out month, so synthetic identity cannot run. It then contributes nothing
        and its share is carried by the scenarios that can, which keeps the month exactly on its
        fraud target while the mix stays as close to ML-DATA-04 as the population allows.
        """
        if month in self._month_plans:
            return self._month_plans[month]
        total = self.monthly_target(month)
        available = {s: self.eligible_count(s, month) for s in SCENARIOS}
        incidents = {}
        for scenario in SCENARIOS:
            low, high = self.rows_range[scenario]
            wanted = total * self.share[scenario] / ((low + high) / 2)
            count = min(available[scenario], round(wanted))
            if wanted > 0 and available[scenario] and count == 0:
                count = 1
            incidents[scenario] = count
        self._fit_to_target(incidents, available, total, month)
        rows = self._rows_per_scenario(incidents, total)
        self._month_plans[month] = (rows, incidents)
        return self._month_plans[month]

    def _fit_to_target(
        self, incidents: dict[str, int], available: dict[str, int], total: int, month: int
    ) -> None:
        """Add or drop whole incidents until the target sits inside the achievable row range."""
        by_share = sorted(SCENARIOS, key=lambda s: -self.share[s])
        while sum(incidents[s] * self.rows_range[s][1] for s in SCENARIOS) < total:
            candidates = [s for s in by_share if incidents[s] < available[s]]
            if not candidates:
                reachable = sum(available[s] * self.rows_range[s][1] for s in SCENARIOS)
                raise ParameterError(
                    f"month {self.config.months[month]}: the fraud target of {total} rows exceeds "
                    f"what every scenario can stage ({reachable} rows); raise the role fractions"
                )
            incidents[candidates[0]] += 1
        while sum(incidents[s] * self.rows_range[s][0] for s in SCENARIOS) > total:
            running = [s for s in reversed(by_share) if incidents[s] > 0]
            incidents[running[0]] -= 1

    def _rows_per_scenario(self, incidents: dict[str, int], total: int) -> dict[str, int]:
        """Spread ``total`` rows over the incidents, each scenario inside its own row range."""
        rows = {s: self.rows_range[s][0] * incidents[s] for s in SCENARIOS}
        room = {
            s: (self.rows_range[s][1] - self.rows_range[s][0]) * incidents[s] for s in SCENARIOS
        }
        surplus = total - sum(rows.values())
        room_total = sum(room.values())
        if room_total:
            for scenario in SCENARIOS:
                share = surplus * room[scenario] // room_total
                rows[scenario] += min(room[scenario], share)
                room[scenario] -= min(room[scenario], share)
        # Integer division leaves a few rows over; give them to the largest scenarios with room.
        for scenario in sorted(SCENARIOS, key=lambda s: -self.share[s]):
            outstanding = total - sum(rows.values())
            if outstanding <= 0:
                break
            rows[scenario] += min(room[scenario], outstanding)
        return rows

    def _validate_scenario_capacity(self) -> None:
        """Refuse a scenario share its role holders could never stage (ML-DATA-04).

        Monthly reallocation is right for a scenario that cannot run yet, but a share beyond what
        its role can ever supply would be reallocated away for the whole simulation and the
        scenario would all but disappear. The test is scale-free: the rows the share asks for over
        the simulation must not exceed the rows the eligible customers could stage even if every
        one of them were a victim of a maximum-length incident every month.

        A scenario with no role holder at all is a different matter: the population is simply too
        small to contain one (0.4% of a few hundred development customers is less than one mule).
        That is a size limitation, not a parameter error, so it is left to the eight-scenario gate
        in the realism report, which runs on the released dataset.
        """
        months = range(len(self.config.months))
        for scenario in SCENARIOS:
            intended = sum(self.monthly_target(m) for m in months) * self.share[scenario]
            capacity = sum(self.eligible_count(scenario, m) for m in months)
            if not intended or not capacity:
                continue
            stageable = capacity * self.rows_range[scenario][1]
            if intended > stageable:
                raise ParameterError(
                    f"scenario {scenario} is asked for {intended:.0f} rows but its role holders "
                    f"could stage at most {stageable}; raise its role fraction or lower its share"
                )

    @cached_property
    def _pools(self) -> tuple[list[int], list[int], dict[int, list[int]]]:
        """Role pools in one pass over the population: mules, non-rural customers, bust-outs.

        Roles come from keyed streams, so a pool is a property of the seed and not of the order in
        which months are simulated. Sorted index lists make each month's eligible set a prefix
        slice instead of a fresh pass over every customer.
        """
        mules: list[int] = []
        urban: list[int] = []
        bust_outs: dict[int, list[int]] = {}
        for index in range(self.config.customers_total):
            if self.is_mule(index):
                mules.append(index)
            if self.population.segment_of(index) != "rural_ussd":
                urban.append(index)
            bust = self._bust_out_month(index)
            if bust is not None and bust < len(self.config.months):
                bust_outs.setdefault(bust, []).append(index)
        return mules, urban, bust_outs

    def eligible(self, scenario: str, month: int) -> list[int]:
        """Customer indices that can suffer (or commit) this scenario in this month."""
        active = self.config.customers_active[month]
        mules, urban, bust_outs = self._pools
        if scenario == "mule_account":
            return mules[: bisect_left(mules, active)]
        if scenario == "synthetic_identity":
            return bust_outs.get(month, [])
        if scenario in ("account_takeover", "card_not_present", "merchant_fraud"):
            return urban[: bisect_left(urban, active)]
        return list(range(active))

    def eligible_count(self, scenario: str, month: int) -> int:
        """Size of :meth:`eligible`, without materialising the whole active population."""
        active = self.config.customers_active[month]
        mules, urban, bust_outs = self._pools
        if scenario == "mule_account":
            return bisect_left(mules, active)
        if scenario == "synthetic_identity":
            return len(bust_outs.get(month, ()))
        if scenario in ("account_takeover", "card_not_present", "merchant_fraud"):
            return bisect_left(urban, active)
        return active

    def plan(self, scenario: str, month: int) -> dict[int, int]:
        """Victim index to row count: exactly the incidents and rows the target needs.

        Victims are the customers with the smallest keyed draw among the eligible ones, so the
        count is exact rather than binomial, and independent of which batch simulates a customer.
        Row counts stay inside the scenario's range and sum to the monthly target.
        """
        key = (scenario, month)
        if key in self._plans:
            return self._plans[key]
        low, high = self.rows_range[scenario]
        rows, incident_counts = self._month_plan(month)
        target = rows[scenario]
        incidents = incident_counts[scenario]
        candidates = self.eligible(scenario, month)
        rng = stream(self.config.seed, "select", scenario, self.config.months[month])
        chosen: list[int] = []
        if incidents:
            draws = rng.random(len(candidates))
            order = np.argsort(draws, kind="stable")[:incidents]
            chosen = sorted(candidates[i] for i in order)
        counts = [int(rng.integers(low, high + 1)) for _ in chosen]
        _match_total(counts, target, low, high)
        plan = {index: count for index, count in zip(chosen, counts, strict=True) if count > 0}
        self._plans[key] = plan
        return plan

    # --- roles --------------------------------------------------------------------------------

    def is_mule(self, index: int) -> bool:
        return bool(stream(self.config.seed, "role", "mule", index).random() < self.mule_fraction)

    def _bust_out_month(self, index: int) -> int | None:
        rng = stream(self.config.seed, "role", "synthetic", index)
        if rng.random() >= self.synthetic_fraction:
            return None
        return self.population.join_month(index) + int(rng.integers(self.bust[0], self.bust[1] + 1))

    @cached_property
    def mules_by_country(self) -> dict[str, list[int]]:
        mules: dict[str, list[int]] = {}
        for index in self._pools[0]:
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
            count = self.plan(scenario, month).get(customer.index)
            if count is None:
                continue
            rng = stream(self.config.seed, "fraud", scenario, customer.index, label)
            incident = Incident(scenario, customer, month, rng, rows=count)
            getattr(self, f"_{scenario}")(incident, rows, events)

    def _start(self, incident: Incident) -> int:
        """UTC start of an incident, on any day of its month.

        Rows that fall past the month end are carried into the next month's file by the pipeline,
        so fraud is not missing from the end of months (that gap was caught by the shortcut
        detector as a row-position artefact).
        """
        rng, customer = incident.rng, incident.customer
        label = self.config.months[incident.month_index]
        days = calendar.monthrange(int(label[:4]), int(label[5:]))[1]
        # Incidents follow the victim's own daily activity profile (payday weeks, market days),
        # so fraud blends into busy periods instead of being spread evenly over the month; an
        # even spread made time-of-month (row position) predictive of the label.
        weights = self.legitimate.day_weights(customer, int(label[:4]), int(label[5:]))
        day = int(rng.choice(days, p=weights)) + 1
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
        span = int(incident.rng.integers(self.burst[0], self.burst[1] + 1)) * _MINUTE
        return sorted(start + int(t) for t in incident.rng.integers(0, span, size=incident.rows))

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


def _match_total(counts: list[int], target: int, low: int, high: int) -> None:
    """Nudge ``counts`` in place, staying within ``[low, high]``, until they sum to ``target``."""
    position = 0
    while counts and sum(counts) != target and position < len(counts) * (high - low + 1):
        index = position % len(counts)
        difference = target - sum(counts)
        step = 1 if difference > 0 else -1
        if low <= counts[index] + step <= high:
            counts[index] += step
        position += 1
