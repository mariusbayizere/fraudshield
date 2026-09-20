"""The shared feature-vector fixture, as pytest fixtures rather than an importable module.

Two test modules need the same corpus: `test_vector` checks the computability declarations against
it, and `test_exit_criteria` perturbs it to show which columns the features do not read. A plain
module would have to be imported, which means making `ml/tests` a package — and that collides with
`dataset/tests` under the workspace's single mypy invocation, both becoming a top-level module
named `tests`. Fixtures need no package and are what pytest provides for exactly this.

Deterministic rather than random. The computability check answers a yes/no question per feature,
and a fixture whose coverage varied by seed would make the answer vary with it.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features.types import CountryFacts, Outcome, Transaction
from fraudshield_ml.features.vector import FeatureContext

START = datetime(2025, 1, 1, tzinfo=UTC)

PACKS = {
    "AA": CountryFacts("AA", "XX", frozenset({"BLOC1"}), 2),
    "BB": CountryFacts("BB", "XX", frozenset({"BLOC1"}), 3),
    "CC": CountryFacts("CC", "YY", frozenset({"BLOC2"}), -4),
}


def _corpus(accounts: int = 5, per_account: int = 14) -> list[Transaction]:
    """A corpus with everything the 44 read from transaction columns.

    Deterministic rather than random: the computability check answers a yes/no question per
    feature, and a fixture whose coverage varies by seed would make the answer vary with it.
    Every account transacts through agents and on USSD, so both structural-NaN sets are exercised
    and neither is NaN for *every* row.
    """
    rows: list[Transaction] = []
    for a in range(accounts):
        when = START + timedelta(hours=a)
        for i in range(per_account):
            when += timedelta(hours=1 + (i * 7) % 53)
            on_ussd = i % 5 == 0
            at_agent = i % 3 == 0 and not on_ussd
            rows.append(
                Transaction(
                    transaction_id=f"a{a}t{i}",
                    account_id=f"A{a}",
                    timestamp=when,
                    amount_rwf=1_000.0 * (1 + (i * 13) % 17),
                    latitude=-1.9441 + 0.01 * ((i + a) % 7),
                    longitude=30.0619 + 0.01 * ((i * 3 + a) % 5),
                    account_country=("AA", "BB")[a % 2],
                    counterparty_country=("AA", "BB", "CC")[i % 3],
                    counterparty_id=f"C{i % 4}",
                    amount_minor=1_000 * (1 + i % 3),
                    currency="AAA",
                    channel="USSD" if on_ussd else ("AGENT_BANKING" if at_agent else "CARD"),
                    device_fingerprint=None if on_ussd else f"D{a}{i % 2}",
                    agent_id=f"AG{i % 2}" if at_agent else None,
                    merchant_category_code="6011" if at_agent else "5411",
                )
            )
    rows.sort(key=lambda row: row.timestamp)
    return rows


CORPUS = _corpus()
#: Score the second half, so every scored row has real history behind it. A sample from the front
#: would make most windows empty and several features NaN for reasons that are about the fixture.
SAMPLE = list(range(len(CORPUS) // 2, len(CORPUS)))


#: SIM swaps for three of the five accounts, which is what `account_events` gives: the events
#: exist, and not for everyone. A fixture that gave every account one would make
#: `days_since_sim_swap` never NaN and hide the partial-NaN case; one that gave none would make it
#: dead, which is a different claim about the benchmark and a false one.
SWAPS = {f"A{a}": [START + timedelta(days=5 + a)] for a in range(3)}


def _context(**overrides: object) -> FeatureContext:
    defaults: dict[str, object] = {
        "countries": PACKS,
        "sim_swaps": SWAPS,
        "outcomes": {
            row.transaction_id: Outcome(
                row.transaction_id, i % 11 == 0, row.timestamp + timedelta(days=2)
            )
            for i, row in enumerate(CORPUS)
        },
        "cash_out_codes": frozenset({"6011"}),
        "cell_rate_prior": 0.0087,
    }
    defaults.update(overrides)
    return FeatureContext(**defaults)  # type: ignore[arg-type]


@pytest.fixture(scope="session")
def corpus() -> list[Transaction]:
    """The corpus, built once: it is immutable and every test reads it."""
    return CORPUS


@pytest.fixture(scope="session")
def sample() -> list[int]:
    """Indices of the rows to score — the second half, so each has real history behind it.

    Scoring from the front would leave most windows empty and report features as missing for a
    reason that is about the sample rather than the data.
    """
    return SAMPLE


@pytest.fixture
def context() -> FeatureContext:
    """The benchmark's own context: labels and SIM swaps, and none of the reference data the
    dataset does not hold."""
    return _context()


@pytest.fixture
def make_context() -> Callable[..., FeatureContext]:
    """The same context with fields overridden, for the tests that withhold or supply one.

    Handed out as a factory rather than as a mutated fixture because each test wants a *different*
    single change, and a fixture that took the change as a parameter would be a factory with extra
    steps.
    """
    return _context
