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


#: Gaps between an account's consecutive transactions, in seconds. Chosen rather than drawn, and
#: spanning five orders of magnitude on purpose: a burst inside a minute so `tx_count_60s` and
#: `tx_count_1h` can be non-zero, and gaps of days so the windows empty again. A fixture whose
#: gaps are all hours makes four velocity features constant, which the variance check would then
#: report as a property of the benchmark rather than of the fixture. The 5,500,000-second gap
#: is about sixty-four days and is there for `dormancy_reactivation_flag`, which needs a
#: silence longer than sixty days followed by a return; without it that feature is constant
#: False and the fixture, not the data, is what says so.
_GAPS_SECONDS = (25, 40, 900, 7_200, 50_000, 260_000, 95, 43_000, 610_000, 55, 5_500_000, 20_000)


def _corpus(accounts: int = 5, per_account: int = 14) -> list[Transaction]:
    """A corpus with everything the 44 read from transaction columns.

    Deterministic rather than random: the computability check answers a yes/no question per
    feature, and a fixture whose coverage varies by seed would make the answer vary with it.
    Every account transacts through agents and on USSD, so both structural-NaN sets are exercised
    and neither is NaN for *every* row.

    Built so that **no feature is constant for a reason that belongs to the fixture**: gaps range
    from 25 seconds to a week, the span crosses month ends, and an account's counterparty countries
    arrive late enough that `is_new_country_for_account` is still True partway through. The one
    feature that is constant here is constant on the benchmark too, for the same reason — no limit
    configuration exists — and the registry declares it.
    """
    rows: list[Transaction] = []
    for a in range(accounts):
        # Staggered starts land accounts on different month boundaries, so `is_month_end_window`
        # and the country and device novelty flags all vary inside the scored half.
        #
        # The cost is that agents never serve two accounts within an hour, so the two agent
        # count features are constant here. That is a limit of 70 rows rather than a fact about
        # the benchmark, and `test_the_only_mismatches_are_the_ones_this_fixture_cannot_avoid`
        # names them — interleaving the accounts to fix it made five other features constant
        # instead, which is the same trade in the other direction.
        when = START + timedelta(days=24 * a, hours=a)
        for i in range(per_account):
            when += timedelta(seconds=_GAPS_SECONDS[(i + a) % len(_GAPS_SECONDS)])
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
                    # A third country only from the ninth transaction, so the novelty flag is
                    # still firing inside the scored half rather than settling before it starts.
                    counterparty_country=("AA", "BB")[i % 2] if i < 9 else "CC",
                    counterparty_id=f"C{i % 4}",
                    # Every fourth amount is deliberately not a multiple of a thousand, so that a
                    # denomination table makes `round_sum_flag` vary rather than fire on every
                    # row. Without it the feature is constant True, which is a different finding
                    # from the one the test means to make.
                    amount_minor=1_000 * (1 + i % 3) + (7 if i % 4 == 0 else 0),
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


#: The true first transaction of each account and first sighting of each device. This fixture's
#: corpus is **complete** — every row of every account is in it — so the earliest row genuinely is
#: the first, and a caller may establish them. A truncated corpus may not, which is why the vector
#: takes them as input rather than inferring them (milestone review M3-2).
FIRST_SEEN = {
    account: min(r.timestamp for r in CORPUS if r.account_id == account)
    for account in {row.account_id for row in CORPUS}
}
DEVICE_FIRST_SEEN = {
    device: min(r.timestamp for r in CORPUS if r.device_fingerprint == device)
    for device in {row.device_fingerprint for row in CORPUS if row.device_fingerprint}
}


def _context(**overrides: object) -> FeatureContext:
    defaults: dict[str, object] = {
        "countries": PACKS,
        "first_seen": FIRST_SEEN,
        "device_first_seen": DEVICE_FIRST_SEEN,
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
