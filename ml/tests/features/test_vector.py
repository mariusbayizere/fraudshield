"""The 44-slot vector and the computability check (PB-44, ML-DATA-07, E13).

The check exists because of a failure nothing else can see: a feature whose inputs do not exist
returns NaN for every row, D-04's missing handling covers it, and the feature count still reads
44. These tests prove the check fires in **both** directions, because a check that only caught
dead features would let the register drift into pessimism once someone wired the data — which is
no more true than optimism and is exactly as trusted.
"""

from __future__ import annotations

import math
from collections.abc import Callable

import pytest

from fraudshield_ml.features import vector
from fraudshield_ml.features.registry import REGISTRY, Computability
from fraudshield_ml.features.types import Transaction
from fraudshield_ml.features.vector import FeatureContext

# --- the vector -----------------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_vector_has_a_slot_for_every_registered_feature(
    corpus: list[Transaction], context: FeatureContext
) -> None:
    """44 slots, named by the registry. A missing slot is a feature that silently stops being
    trained on, which no per-feature test would notice."""
    values = vector.compute(corpus, len(corpus) - 1, context)
    assert set(values) == set(REGISTRY)
    assert len(values) == 44


@pytest.mark.req("FR-02-02")
def test_the_vector_reads_only_rows_before_the_one_it_scores(
    corpus: list[Transaction], context: FeatureContext
) -> None:
    """The same discipline as the parity suite's prefix replay, asserted at the vector level.

    Computing the same row against a corpus that also holds its future must give the identical
    vector; if any feature reached forward, appending later rows would change it.
    """
    index = len(corpus) // 2
    from_prefix = vector.compute(corpus[: index + 1], index, context)
    from_whole = vector.compute(corpus, index, context)
    for name in REGISTRY:
        a, b = from_prefix[name], from_whole[name]
        if isinstance(a, float) and isinstance(b, float) and math.isnan(a) and math.isnan(b):
            continue
        assert a == b, f"{name} changed when the corpus gained future rows: {a!r} vs {b!r}"


# --- the computability check ---------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_benchmarks_context_leaves_exactly_the_declared_six_without_data(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """The run that mirrors the dataset: `account_events` supplies SIM swaps, and nothing supplies
    opening dates, tier histories, agent standing or denominations.

    Every declared NO_SOURCE_DATA feature is NaN for every row, and **nothing else is** — which is
    the assertion that makes the registry's six a measured set rather than an estimate. An earlier
    note in this repository said eight, from reasoning about which inputs were missing instead of
    computing the features.
    """
    result = vector.computability(corpus, context, sample=sample)
    assert result.ok, vector.describe(result)

    all_nan = {name for name, rate in result.nan_rate.items() if rate == 1.0}
    declared = {
        name for name, spec in REGISTRY.items() if spec.computable is Computability.NO_SOURCE_DATA
    }
    assert all_nan == declared, (
        f"NaN for every row: {sorted(all_nan)}; declared NO_SOURCE_DATA: {sorted(declared)}"
    )
    assert len(declared) == 6


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_partially_missing_feature_is_not_treated_as_dead(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """100%, not a threshold, and this is the case that makes the distinction matter.

    Three of five accounts have a SIM swap, so `days_since_sim_swap` is NaN for a large minority
    of rows and alive. So are the structural NaNs: four device features on every USSD row, four
    agent features on every non-agent one. Picking a cut-off would mean deciding how dead is dead,
    and would report all nine of these as gaps.
    """
    result = vector.computability(corpus, context, sample=sample)
    partial = result.nan_rate["days_since_sim_swap"]
    assert 0.0 < partial < 1.0, f"precondition: the feature is partly missing, got {partial:.1%}"
    assert "days_since_sim_swap" not in result.dead

    for name in ("device_age_days", "agent_unique_customers_1h"):
        rate = result.nan_rate[name]
        assert 0.0 < rate < 1.0, f"precondition: {name} is structurally NaN on some rows"
        assert name not in result.dead


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_dead_feature_is_reported_even_though_nothing_raises(
    corpus: list[Transaction],
    sample: list[int],
    context: FeatureContext,
    make_context: Callable[..., FeatureContext],
) -> None:
    """The first direction, and the reason the check exists.

    `days_since_sim_swap` is declared COMPUTABLE because `account_events` carries SIM_SWAP rows.
    Withhold them — which is exactly what a pipeline that forgot the join would do — and the
    feature is NaN for every row while nothing raises, nothing warns, and the vector still has 44
    slots. That is the state PB-44 is about, produced deliberately.
    """
    assert vector.computability(corpus, context, sample=sample).ok, (
        "precondition: with the join wired the feature is alive, so the failure below is about "
        "the missing join and not about the feature"
    )

    forgot_the_join = vector.computability(corpus, make_context(sim_swaps={}), sample=sample)
    assert forgot_the_join.dead == ("days_since_sim_swap",)
    assert not forgot_the_join.ok
    assert "NaN for every row" in vector.describe(forgot_the_join)


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_revived_feature_is_reported_so_the_register_cannot_drift_into_pessimism(
    corpus: list[Transaction], sample: list[int], make_context: Callable[..., FeatureContext]
) -> None:
    """The second direction. Without it, wiring the data and forgetting the declaration would
    leave the register saying a feature is dead while it is alive — stale in the direction a
    reader has no reason to question."""
    context = make_context(denominations={"AAA": (1_000, 5_000)})
    result = vector.computability(corpus, context, sample=sample)
    assert "round_sum_flag" in result.revived
    assert not result.ok
    assert "register is stale" in vector.describe(result)


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_an_empty_sample_is_refused_rather_than_passing_over_nothing(
    corpus: list[Transaction], context: FeatureContext
) -> None:
    """ADR 0009's generalisation: a check that has only run over an empty scope is untested.

    Over an empty sample every feature is neither dead nor revived, so the check would pass having
    examined nothing — which is the state three milestones of green licence checks were in.
    """
    with pytest.raises(ValueError, match="empty sample"):
        vector.computability(corpus, context, sample=[])


@pytest.mark.req("FR-02-02")
def test_the_report_says_how_much_it_examined(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """ "Passed" and "had nothing to check" must be distinguishable in the output, not only in the
    exit code."""
    report = vector.describe(vector.computability(corpus, context, sample=sample))
    assert f"over {len(sample)} scored rows" in report
    assert "44 features" in report


@pytest.mark.req("FR-02-02", "ML-DATA-01")
def test_the_corpus_index_changes_no_value(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """The index exists to make the pipeline affordable, so it must not change a single number.

    Every feature already filters the history it is given by its own key, so a slice that shares
    that key is the same rows after the filter — the claim is exact, not approximate, and this
    asserts it bit for bit over every sampled row and all 44 slots.

    Without this test the index would be a performance change nobody had checked, applied to the
    computation that produces every figure the milestone reports.
    """
    index = vector.CorpusIndex.build(corpus)
    for i in sample:
        unindexed = vector.compute(corpus, i, context)
        indexed = vector.compute(corpus, i, context, index)
        assert set(unindexed) == set(indexed)
        for name, plain in unindexed.items():
            fast = indexed[name]
            if isinstance(plain, float) and isinstance(fast, float):
                if math.isnan(plain) or math.isnan(fast):
                    assert math.isnan(plain), f"{name}: indexed NaN where unindexed was not"
                    assert math.isnan(fast), f"{name}: unindexed NaN where indexed was not"
                    continue
                assert plain.hex() == fast.hex(), f"{name}: {plain!r} vs {fast!r}"
            else:
                assert plain == fast, name


@pytest.mark.req("FR-02-02")
def test_the_index_groups_every_key_and_keeps_each_in_time_order(
    corpus: list[Transaction],
) -> None:
    """The two properties `before()` relies on: every row is filed, and each list is sorted.

    Bisecting an unsorted list returns a silently wrong prefix rather than an error, so the
    ordering is asserted rather than assumed.
    """
    index = vector.CorpusIndex.build(corpus)
    assert sum(len(rows) for rows in index.by_account.values()) == len(corpus)
    assert sum(len(rows) for rows in index.by_cell.values()) == len(corpus)
    assert len(index.cell_of) == len(corpus)
    for group in (index.by_account, index.by_counterparty, index.by_device, index.by_agent):
        for key, rows in group.items():
            stamps = [row.timestamp for row in rows]
            assert stamps == sorted(stamps), f"{key} is not in time order"

    # Rows without a device or an agent are absent rather than filed under a placeholder: a
    # placeholder key would make every USSD transaction share one "device".
    with_device = sum(1 for row in corpus if row.device_fingerprint is not None)
    assert sum(len(rows) for rows in index.by_device.values()) == with_device
    assert with_device < len(corpus), "precondition: some rows have no device"
