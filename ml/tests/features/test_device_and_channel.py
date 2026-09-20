"""The five device and channel features on both paths, hand-computed (Part E.2, E13, D-04).

Four of them are NaN **together** for a null device fingerprint, which every USSD transaction has.
That is a contract about which features go missing at the same time, so it is asserted as a set
rather than four times over: four separate assertions that each happen to hold would not notice a
fifth feature quietly joining them, or one of the four leaving.

A null fingerprint is data, not a missing input, which is why these return NaN where the
counterparty and currency features raise.
"""

from __future__ import annotations

import math
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.registry import REGISTRY, Group, categories_for
from fraudshield_ml.features.types import Transaction

T = datetime(2025, 6, 1, 12, 0, tzinfo=UTC)
KIGALI = (-1.9441, 30.0619)


def tx(
    hours_before: float,
    *,
    device: str | None,
    account: str = "A",
    channel: str = "MOBILE_MONEY",
    tid: str | None = None,
) -> Transaction:
    return Transaction(
        transaction_id=tid or f"{account}-{device}-{hours_before:g}",
        account_id=account,
        timestamp=T - timedelta(hours=hours_before),
        amount_rwf=1_000.0,
        latitude=KIGALI[0],
        longitude=KIGALI[1],
        counterparty_id="M1",
        channel=channel,
        device_fingerprint=device,
    )


def warm(rows: list[Transaction]) -> OnlineFeatures:
    features = OnlineFeatures()
    for row in rows:
        features.observe(row)
    return features


SCORED = tx(0.0, device="D1", tid="scored")
USSD = tx(0.0, device=None, channel="USSD", tid="ussd")


# --- channel ------------------------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-03")
def test_the_channel_is_passed_through_and_checked_against_the_declared_values() -> None:
    """All six declared channels are accepted; an undeclared one is refused on both paths.

    Refused rather than passed through: ADR 0025 allows a categorical no tolerance, so an
    undeclared value is not a slightly-wrong number — it reaches the model as a level no encoder
    has a cell for, which is silent.
    """
    features = OnlineFeatures()
    for value in categories_for("channel"):
        scored = tx(0.0, device="D1", channel=value)
        assert batch.channel(scored) == value
        assert features.channel(scored) == value

    invented = tx(0.0, device="D1", channel="CARRIER_PIGEON")
    with pytest.raises(ValueError, match="CARRIER_PIGEON"):
        batch.channel(invented)
    with pytest.raises(ValueError, match="CARRIER_PIGEON"):
        features.channel(invented)


@pytest.mark.req("FR-02-02")
def test_the_channel_is_never_nan_even_without_a_device() -> None:
    """The fifth device-group feature is the one that is *not* in the structural-NaN set: a USSD
    transaction has a channel, it just has no device."""
    assert batch.channel(USSD) == "USSD"
    assert OnlineFeatures().channel(USSD) == "USSD"


# --- the structural NaN set (D-04) ---------------------------------------------------------------


def _device_values(rows: list[Transaction], scored: Transaction) -> dict[str, float]:
    features = warm(rows)
    return {
        "batch.device_is_new_for_account": batch.device_is_new_for_account(rows, scored),
        "batch.accounts_per_device_7d": batch.accounts_per_device_7d(rows, scored),
        "batch.device_changes_24h": batch.device_changes_24h(rows, scored),
        "batch.device_age_days": batch.device_age_days(scored, T - timedelta(days=10)),
        "online.device_is_new_for_account": features.device_is_new_for_account(scored),
        "online.accounts_per_device_7d": features.accounts_per_device_7d(scored),
        "online.device_changes_24h": features.device_changes_24h(scored),
        "online.device_age_days": features.device_age_days(scored),
    }


@pytest.mark.req("FR-02-02", "D-04")
def test_exactly_four_device_features_are_nan_together_for_a_null_fingerprint() -> None:
    """The D-04 contract, asserted as a set on both paths.

    A USSD transaction has no device to fingerprint, so all four go missing at once — and the
    contract is that it is *these four*, no more and no fewer. Asserting them one at a time would
    not notice a fifth joining them or one of the four leaving.
    """
    rows = [tx(2.0, device="D1"), tx(3.0, device="D2")]
    values = _device_values(rows, USSD)
    assert all(math.isnan(v) for v in values.values()), (
        f"not every device feature is NaN for a null fingerprint: {values}"
    )

    # The control: with a device present, none of them is NaN. Without it the assertion above
    # would hold over an implementation that returned NaN unconditionally.
    with_device = _device_values(rows, SCORED)
    with_device["online.device_age_days"] = 0.0  # covered separately; needs a durable value
    assert not any(math.isnan(v) for v in with_device.values()), (
        f"a device feature is NaN even with a fingerprint present: {with_device}"
    )


@pytest.mark.req("FR-02-02", "D-04")
def test_the_registry_names_the_same_four_features() -> None:
    """The code's list and the contract must not drift apart.

    `DEVICE_FEATURES` exists so the parity suite can assert NaN positions as a set; if it stopped
    matching the registry's device group minus `channel`, the set being asserted would no longer
    be the set D-04 describes.
    """
    group = {
        name
        for name, spec in REGISTRY.items()
        if spec.group is Group.DEVICE_AND_CHANNEL and name != "channel"
    }
    assert set(batch.DEVICE_FEATURES) == group
    assert len(group) == 4, "D-04 fixes the structural NaN count at four"


# --- device_is_new_for_account -------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_a_device_is_new_until_this_account_has_used_it() -> None:
    """Another account's use of the same device does not make it familiar to this one.

    That is the difference between this feature and `accounts_per_device_7d`, and the fixture
    contains the other account's row so the two readings are distinguishable.
    """
    rows = [tx(10.0, device="D1", account="B", tid="other-account")]
    assert batch.device_is_new_for_account(rows, SCORED) == 1.0
    assert warm(rows).device_is_new_for_account(SCORED) == 1.0

    own = [*rows, tx(5.0, device="D1", account="A", tid="own")]
    assert batch.device_is_new_for_account(own, SCORED) == 0.0
    assert warm(own).device_is_new_for_account(SCORED) == 0.0


@pytest.mark.req("FR-02-02", "D-04")
def test_a_flush_reports_every_device_as_new_until_restored() -> None:
    """Unbounded history makes the device set `DURABLE`, with PB-37's failure shape again."""
    rows = [tx(200 * 24.0, device="D1", account="A", tid="ancient")]
    features = warm(rows)
    assert features.device_is_new_for_account(SCORED) == 0.0

    features.flush_cache()
    assert features.device_is_new_for_account(SCORED) == 1.0
    features.restore_devices("A", {"D1"})
    assert features.device_is_new_for_account(SCORED) == 0.0


# --- accounts_per_device_7d ----------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_device_sharing_count_includes_the_scored_account() -> None:
    """Three other accounts on D1 inside 7 d, plus the scored one, is **4**.

    The scored account is in the set because the question is "how many accounts use this device",
    and the account asking is one of them. A count that excluded it would read 0 for a device used
    only by its owner, where the honest answer is 1 — and 0 would be indistinguishable from a
    device nobody has used.
    """
    rows = [
        tx(2.0, device="D1", account="B"),
        tx(3.0, device="D1", account="C"),
        tx(4.0, device="D1", account="D"),
        tx(8 * 24.0, device="D1", account="E", tid="too-old"),
        tx(2.0, device="D2", account="F", tid="other-device"),
    ]
    assert batch.accounts_per_device_7d(rows, SCORED) == 4.0
    assert warm(rows).accounts_per_device_7d(SCORED) == 4.0


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_an_unshared_device_reads_one_which_is_the_degenerate_case() -> None:
    """PB-40: no device in the current dataset is used by more than one account, so this is the
    value the feature takes for **every row** of the benchmark.

    Implemented correctly anyway. The generator fix is scheduled before M4 training, and a feature
    written to match a degenerate dataset would become the defect once the dataset stops being
    degenerate.
    """
    own_only = [tx(2.0, device="D1", account="A"), tx(3.0, device="D1", account="A")]
    assert batch.accounts_per_device_7d(own_only, SCORED) == 1.0
    assert warm(own_only).accounts_per_device_7d(SCORED) == 1.0
    assert REGISTRY["accounts_per_device_7d"].degeneracy, (
        "the feature must stay declared degenerate while the generator shares no device"
    )


# --- device_changes_24h ---------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_a_single_consistent_device_scores_zero_changes() -> None:
    """Minus one, so the ordinary case is 0 rather than 1 — the feature counts changes, not
    devices."""
    steady = [tx(2.0, device="D1"), tx(6.0, device="D1"), tx(12.0, device="D1")]
    assert batch.device_changes_24h(steady, SCORED) == 0.0
    assert warm(steady).device_changes_24h(SCORED) == 0.0


@pytest.mark.req("FR-02-02")
def test_switching_devices_counts_each_additional_one() -> None:
    """D2 and D3 in the window plus D1 scored is three devices, so **2** changes.

    The scored transaction's own device is in the set although the window excludes the scored row:
    a switch is only visible if the device switched *to* is in the comparison, and excluding it
    would make the first transaction from a new device look like no change at all.
    """
    switching = [tx(2.0, device="D2"), tx(6.0, device="D3"), tx(30.0, device="D4", tid="too-old")]
    assert batch.device_changes_24h(switching, SCORED) == 2.0
    assert warm(switching).device_changes_24h(SCORED) == 2.0

    first_use_of_a_new_device = [tx(2.0, device="D2")]
    assert batch.device_changes_24h(first_use_of_a_new_device, SCORED) == 1.0, (
        "a first transaction from a new device is a change, not zero"
    )


# --- device_age_days -----------------------------------------------------------------------------


@pytest.mark.req("FR-02-02")
def test_the_device_age_is_measured_across_the_institution_not_the_account() -> None:
    """10 days exactly, from a first sighting supplied by the durable store.

    "Anywhere in the institution" is the point: a device first seen an hour ago is the signal, and
    scoping it to the account would make every device new on its first use there — which is what
    `device_is_new_for_account` already says, so the two features would be the same one.
    """
    first_seen = T - timedelta(days=10)
    assert batch.device_age_days(SCORED, first_seen) == 10.0

    features = warm([tx(1.0, device="D1", account="B", tid="another-account")])
    features.restore_device_first_seen("D1", first_seen)
    assert features.device_age_days(SCORED) == 10.0


@pytest.mark.req("FR-02-02", "D-04")
def test_the_device_age_fails_closed_without_a_durable_first_sighting() -> None:
    """PB-37's reasoning, applied to the device store.

    `observe` must not infer the first sighting from the earliest arrival: after a flush that is
    the rolling window's edge, so every device in the estate would report as a few days old at the
    moment the store came back — suspicious-looking, for the whole estate, simultaneously.
    """
    features = warm([tx(5.0, device="D1"), tx(50.0, device="D1", tid="older")])
    assert math.isnan(features.device_age_days(SCORED)), (
        "observe() inferred a first sighting from an arrival"
    )
    assert math.isnan(batch.device_age_days(SCORED, None))

    features.restore_device_first_seen("D1", T - timedelta(days=3))
    assert features.device_age_days(SCORED) == 3.0
