"""The serving feature path's refusals and the one feature the replay holds constant."""

from __future__ import annotations

from datetime import UTC, datetime

import pytest

from fraudshield_ml.features.types import CountryFacts, Transaction
from fraudshield_ml.featurestore.reference import Reference
from fraudshield_ml.serving import features
from fraudshield_ml.serving.convert import to_proto
from fraudshield_ml.serving.generated import scoring_pb2 as pb

REFERENCE = Reference(
    countries={
        "RW": CountryFacts("RW", "AF", frozenset({"EAC"}), 2),
        "KE": CountryFacts("KE", "AF", frozenset({"EAC"}), 3),
    },
    country_of_currency={"RWF": "RW", "KES": "KE"},
    minor_units={"RWF": 0, "KES": 2},
    denominations={"RWF": (1000,)},
)


def record(**overrides: object) -> Transaction:
    fields: dict[str, object] = {
        "transaction_id": "t1",
        "account_id": "A",
        "timestamp": datetime(2025, 5, 31, 22, 30, tzinfo=UTC),
        "amount_rwf": 9_600.0,
        "latitude": -1.95,
        "longitude": 30.06,
        "account_country": "RW",
        "counterparty_country": "RW",
        "counterparty_id": "C",
        "amount_minor": 9_600,
        "currency": "RWF",
        "channel": "MOBILE_MONEY",
        "merchant_category_code": "5411",
    }
    return Transaction(**(fields | overrides))  # type: ignore[arg-type]


def money(amount: str, currency: str = "RWF") -> pb.Money:
    return pb.Money(amount=amount, currency=currency)


def test_the_limit_band_is_five_percent_below_and_excludes_the_limit() -> None:
    ctx = pb.AccountContext()
    assert (
        features.compute(record(), ctx, [money("10000")], REFERENCE)["just_below_limit_flag"] == 1
    )
    assert features.compute(record(), ctx, [money("9600")], REFERENCE)["just_below_limit_flag"] == 0
    assert (
        features.compute(record(), ctx, [money("20000")], REFERENCE)["just_below_limit_flag"] == 0
    )


def test_a_limit_in_another_currency_is_refused() -> None:
    with pytest.raises(features.RequestError, match="compared in RWF"):
        features.compute(record(), pb.AccountContext(), [money("100", "KES")], REFERENCE)


def test_missing_counterparty_facts_are_refused_not_defaulted() -> None:
    with pytest.raises(features.RequestError, match="counterparty_country"):
        features.compute(record(counterparty_country=None), pb.AccountContext(), [], REFERENCE)
    with pytest.raises(features.RequestError, match="counterparty_token"):
        features.compute(record(counterparty_id=None), pb.AccountContext(), [], REFERENCE)
    with pytest.raises(features.RequestError, match="no country pack"):
        features.compute(record(counterparty_country="ZZ"), pb.AccountContext(), [], REFERENCE)


def test_local_time_comes_from_the_currency_s_country() -> None:
    """22:30 UTC on 31 May is 00:30 on 1 June in Kigali: night, and inside the month-start days."""
    values = features.compute(record(), pb.AccountContext(), [], REFERENCE)
    assert values["is_local_night"] == 1.0
    assert values["is_month_end_window"] == 1.0
    assert values["local_day_of_week"] == 6.0  # Sunday 1 June 2025
    assert values["round_sum_flag"] == 0.0
    no_steps = features.compute(
        record(currency="KES", account_country="KE", amount_minor=960_000),
        pb.AccountContext(),
        [],
        REFERENCE,
    )
    assert no_steps["round_sum_flag"] != no_steps["round_sum_flag"], "no denominations -> NaN"


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda m: setattr(m, "transaction_id", ""), "required"),
        (lambda m: setattr(m, "channel", pb.CHANNEL_UNSPECIFIED), "declared channels"),
        (lambda m: m.ClearField("transaction_timestamp"), "transaction_timestamp"),
        (lambda m: setattr(m.amount, "currency", "XOF"), "no country pack"),
        (lambda m: setattr(m.amount, "amount", "ten"), "not a decimal"),
        (lambda m: setattr(m, "amount_rwf", "Infinity"), "not finite"),
    ],
)
def test_a_malformed_transaction_is_refused(mutate: object, message: str) -> None:
    proto = to_proto(record())
    mutate(proto)  # type: ignore[operator]
    with pytest.raises(features.RequestError, match=message):
        features.domain_transaction(proto, REFERENCE)


def test_the_contract_round_trip_is_exact_including_optional_fields() -> None:
    original = record(device_fingerprint="D", agent_id="G", channel="AGENT_BANKING")
    assert features.domain_transaction(to_proto(original), REFERENCE) == original
    with pytest.raises(ValueError, match="required"):
        to_proto(record(currency=None))
