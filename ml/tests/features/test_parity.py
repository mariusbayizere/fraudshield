"""Prefix replay and the cold-cache case (parity Decisions 2, 3 and 6).

Handing both paths a completed history proves they agree on a situation the online path never
encounters, and cannot detect a batch window that reaches forward in time — both paths would see
the same future rows. So the online path is fed arrivals one at a time and snapshotted, and the
batch path is computed on **the prefix ending at that transaction only**.
"""

from __future__ import annotations

import itertools
import random
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.primitives import h3_cell, haversine_km
from fraudshield_ml.features.types import Outcome, Transaction

START = datetime(2025, 1, 1, tzinfo=UTC)
TOLERANCE_ABS = 1e-12
TOLERANCE_REL = 1e-12


def _agree(batch_value: float, online_value: float) -> bool:
    return abs(batch_value - online_value) <= TOLERANCE_ABS + TOLERANCE_REL * abs(batch_value)


def _history(n: int = 60, seed: int = 20260919) -> list[Transaction]:
    """An account's arrivals, deliberately uneven so windows open and close mid-replay."""
    rng = random.Random(seed)  # noqa: S311 - fixture shape, not a security context
    rows: list[Transaction] = []
    when = START
    for i in range(n):
        when += timedelta(minutes=rng.choice([3, 17, 90, 400, 1500, 5000]))
        rows.append(
            Transaction(
                transaction_id=f"t{i}",
                account_id="A",
                timestamp=when,
                amount_rwf=float(rng.randrange(1000, 500000)),
                latitude=-1.9441 + rng.uniform(-0.2, 0.2),
                longitude=30.0619 + rng.uniform(-0.2, 0.2),
            )
        )
    return rows


@pytest.mark.req("FR-02-02")
def test_prefix_replay_agrees_at_every_step() -> None:
    """The core parity assertion, at every k rather than once at the end."""
    rows = _history()
    first_seen = rows[0].timestamp

    spans_a_window_boundary = any(
        (b.timestamp - a.timestamp) > timedelta(hours=1) for a, b in itertools.pairwise(rows)
    )
    assert spans_a_window_boundary, (
        "precondition: the replay must cross the 1 h boundary, or the short window never empties "
        "and the test cannot detect a bound error (E12)"
    )

    online = OnlineFeatures()
    online.restore_first_seen("A", first_seen)
    checked = 0
    for k, scored in enumerate(rows):
        online_value = online.velocity_ratio_1h_vs_30d(scored)
        batch_value = batch.velocity_ratio_1h_vs_30d(rows[:k], scored, first_seen_at=first_seen)
        assert _agree(batch_value, online_value), (
            f"prefix {k} ({scored.transaction_id}): "
            f"batch {batch_value!r} vs online {online_value!r}"
        )
        online.observe(scored)
        checked += 1
    assert checked == len(rows)


@pytest.mark.req("FR-02-02")
def test_the_replay_is_not_trivially_constant() -> None:
    """A replay where every value is 1.0 would pass the test above while proving nothing.

    This is the same failure E12 was written for: the assertion holds over a fixture that never
    exercises the thing being asserted.
    """
    rows = _history()
    online = OnlineFeatures()
    online.restore_first_seen("A", rows[0].timestamp)
    values = []
    for scored in rows:
        values.append(online.velocity_ratio_1h_vs_30d(scored))
        online.observe(scored)
    assert len(set(values)) > 5, f"the replay produced only {len(set(values))} distinct values"
    assert max(values) > 1.5, "no burst in the fixture: the numerator never dominates"


@pytest.mark.req("FR-02-02")
def test_a_cache_flush_without_restoring_durable_state_changes_the_feature() -> None:
    """Decision 6's cold-cache case, and the only test that exercises `DURABLE`.

    `velocity_ratio_1h_vs_30d` declares `history_basis=OBSERVED_CAPPED`, which divides by history
    actually observed and therefore needs a first-seen timestamp. A flush destroys it. If the
    online path silently re-derives first-seen from its next arrival, the denominator shrinks and
    the ratio inflates for every established account at once — invisible on every warm-path test.

    This asserts the damage is real, so that `restore_first_seen` is not decorative.
    """
    rows = _history()
    first_seen = rows[0].timestamp
    scored = rows[-1]

    warm = OnlineFeatures()
    warm.restore_first_seen("A", first_seen)
    for row in rows[:-1]:
        warm.observe(row)
    warm_value = warm.velocity_ratio_1h_vs_30d(scored)

    cold = OnlineFeatures()
    cold.restore_first_seen("A", first_seen)
    for row in rows[:-1]:
        cold.observe(row)
    cold.flush_cache()
    for row in rows[-4:-1]:  # only recent arrivals return after a flush
        cold.observe(row)
    unrestored = cold.velocity_ratio_1h_vs_30d(scored)

    assert not _agree(warm_value, unrestored), (
        "a flush that loses the durable first-seen must change the feature; if it does not, "
        "either the fixture has too little history or OBSERVED_CAPPED is not being applied"
    )

    cold.restore_first_seen("A", first_seen)
    restored = cold.velocity_ratio_1h_vs_30d(scored)
    assert restored != unrestored, "restoring the durable field must undo the flush's effect"


@pytest.mark.req("FR-02-02")
def test_the_shared_primitives_against_hand_computed_values() -> None:
    """The parity test cannot see into these, so they are covered only here.

    Kigali (-1.9441, 30.0619) to Nairobi (-1.2921, 36.8219) is 754.9 km great-circle on a sphere
    of radius 6371.0088 km. A point and itself is exactly 0.
    """
    assert haversine_km(-1.9441, 30.0619, -1.2921, 36.8219) == pytest.approx(754.9, abs=0.1)
    assert haversine_km(-1.9441, 30.0619, -1.9441, 30.0619) == 0.0
    assert haversine_km(0.0, 0.0, 0.0, 1.0) == pytest.approx(111.195, abs=0.01)

    # H3 resolution 6 averages ~36 km^2, so two points ~100 m apart share a cell and Kigali and
    # Nairobi do not.
    assert h3_cell(-1.9441, 30.0619) == h3_cell(-1.9450, 30.0625)
    assert h3_cell(-1.9441, 30.0619) != h3_cell(-1.2921, 36.8219)


@pytest.mark.req("FR-02-02")
def test_the_cell_rate_agrees_across_paths_under_prefix_replay() -> None:
    """Prefix replay for the label-derived feature, where the batch path can see the future."""
    rows = _history(n=40)
    outcomes = {
        row.transaction_id: Outcome(
            transaction_id=row.transaction_id,
            is_fraud=(i % 7 == 0),
            available_at=row.timestamp + timedelta(days=3),
        )
        for i, row in enumerate(rows)
    }
    assert any(o.is_fraud for o in outcomes.values()), "precondition: the fixture contains fraud"

    online = OnlineFeatures()
    for outcome in outcomes.values():
        online.observe_outcome(outcome)

    for k, scored in enumerate(rows):
        online_value = online.geo_cell_fraud_rate_30d(scored, prior=0.0087)
        batch_value = batch.geo_cell_fraud_rate_30d(rows[:k], outcomes, scored, prior=0.0087)
        assert _agree(batch_value, online_value), (
            f"prefix {k}: batch {batch_value!r} vs online {online_value!r}"
        )
        online.observe(scored)
