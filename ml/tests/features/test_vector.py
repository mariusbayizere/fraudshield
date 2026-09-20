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
from datetime import UTC, datetime, timedelta

import pytest

from fraudshield_ml.features import batch, vector
from fraudshield_ml.features.registry import REGISTRY, Computability
from fraudshield_ml.features.types import Transaction
from fraudshield_ml.features.vector import FeatureContext

#: Defined here rather than imported from `conftest`: a test module importing a conftest is not a
#: package import mypy can resolve, and the value is a literal either way.
START = datetime(2025, 1, 1, tzinfo=UTC)


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


#: Features this 70-row fixture cannot make vary, with the reason. Asserted as an **exact set**,
#: so a new mismatch is still a failure while the known ones do not mask it.
#:
#: Whether the whole registry agrees with the data is a property of the **benchmark**, and it is
#: checked there by an evidence run (`docs/benchmarks/m3_computability_fad43dd.txt`). Demanding it
#: of a toy corpus would be asking a fixture to be a benchmark — and tuning one until the verdict
#: read zero would shape the fixture by the answer.
FIXTURE_CANNOT_VARY = {"agent_cashout_count_1h", "agent_unique_customers_1h"}


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_only_mismatches_are_the_ones_this_fixture_cannot_avoid(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """The check runs over all 44 and disagrees only where 70 rows cannot show variation.

    Accounts are staggered by days so that the month-end and novelty flags vary, which means an
    agent never serves two of them within an hour and the two agent counts are constant. Starting
    the accounts hours apart instead fixes those two and makes five others constant. Neither is a
    fact about the data, so both are recorded rather than tuned away.
    """
    result = vector.computability(corpus, context, sample=sample)
    assert {name for name, _, _ in result.mismatched} == FIXTURE_CANNOT_VARY, vector.describe(
        result
    )


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_six_without_source_data_are_dead_at_any_size(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """A property of the data's *shape* rather than its size, so it must hold here too.

    Opening dates, tier histories, agent standing and denominations are absent from this fixture
    for the same reason they are absent from the benchmark: nothing produces them. Every declared
    NO_SOURCE_DATA feature must therefore produce no value at all, and none may appear among the
    mismatches — one that did would mean the fixture supplies something the benchmark does not.
    """
    result = vector.computability(corpus, context, sample=sample)
    declared = {
        name for name, spec in REGISTRY.items() if spec.computable is Computability.NO_SOURCE_DATA
    }
    assert len(declared) == 6
    for name in declared:
        assert result.distinct[name] == 0, f"{name} produced a value"
        assert result.nan_rate[name] == 1.0
        assert name not in {n for n, _, _ in result.mismatched}


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_three_states_are_decided_by_the_distinct_count_alone() -> None:
    """`observed_state` is the whole rule, so it is tested directly rather than through a corpus.

    Zero values means the inputs are absent; one means present and carrying nothing; two or more
    means it varies. Testing it here rather than only through fixtures is what lets the fixture
    tests be about fixtures.
    """
    assert vector.observed_state(0) is Computability.NO_SOURCE_DATA
    assert vector.observed_state(1) is Computability.CONSTANT
    assert vector.observed_state(2) is Computability.COMPUTABLE
    assert vector.observed_state(9_999) is Computability.COMPUTABLE


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_missingness_is_not_counted_as_variation(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """The choice that makes the check work, asserted on the feature that exposed it.

    `accounts_per_device_7d` is NaN on every USSD row and 1 on all the others. Counting the NaN as
    a second value reported it as varying, when what separates those rows is the channel and
    `channel` already carries it. It is declared CONSTANT and must be observed as CONSTANT despite
    being NaN a third of the time.
    """
    result = vector.computability(corpus, context, sample=sample)
    assert result.nan_rate["accounts_per_device_7d"] >= 0.15, (
        "precondition: the feature is NaN on a large minority of rows, or this proves nothing"
    )
    assert result.distinct["accounts_per_device_7d"] == 1
    assert vector.observed_state(result.distinct["accounts_per_device_7d"]) is (
        Computability.CONSTANT
    )


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_dead_feature_is_reported_even_though_nothing_raises(
    corpus: list[Transaction],
    sample: list[int],
    make_context: Callable[..., FeatureContext],
) -> None:
    """Withhold the `account_events` join and `days_since_sim_swap` goes NaN for every row.

    Nothing raises, nothing warns, the vector still has 44 slots, and D-04's missing handling
    covers it — which is the state PB-44 is about, produced deliberately. The mismatch must name
    the feature, both states, and why it matters.
    """
    forgot_the_join = vector.computability(corpus, make_context(sim_swaps={}), sample=sample)
    mismatches = {name: (d, o) for name, d, o in forgot_the_join.mismatched}
    assert "days_since_sim_swap" in mismatches
    declared, observed = mismatches["days_since_sim_swap"]
    assert declared is Computability.COMPUTABLE
    assert observed is Computability.NO_SOURCE_DATA
    assert "D-04's missing handling hides it" in vector.describe(forgot_the_join)


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_revived_feature_is_reported_so_the_register_cannot_drift_into_pessimism(
    corpus: list[Transaction],
    sample: list[int],
    make_context: Callable[..., FeatureContext],
) -> None:
    """Supply the denominations and `round_sum_flag` comes alive while the register says it is dead.

    Without this direction, wiring the data and forgetting the declaration would leave the register
    stale in a way a reader has no reason to question — pessimism reads as caution.
    """
    with_denominations = vector.computability(
        corpus, make_context(denominations={"AAA": (1_000, 5_000)}), sample=sample
    )
    mismatches = {name: (d, o) for name, d, o in with_denominations.mismatched}
    assert "round_sum_flag" in mismatches
    assert mismatches["round_sum_flag"][0] is Computability.NO_SOURCE_DATA
    assert "still says it is dead" in vector.describe(with_denominations)


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


@pytest.mark.req("FR-02-02", "D-04")
def test_the_vector_never_infers_a_durable_first_seen(
    corpus: list[Transaction], sample: list[int], make_context: Callable[..., FeatureContext]
) -> None:
    """Milestone review M3-2: the batch caller must not make the inference `observe()` is forbidden.

    Taking an account's earliest row *in the supplied corpus* is the same mistake PB-37 removed
    from the online path — a truncated corpus's earliest row is the window's edge, not the
    account's beginning, and `velocity_ratio_1h_vs_30d` divides by observed history, so the error
    inflates the ratio for exactly the accounts that look newest. Every run this repository has
    performed passed a truncated corpus.

    Withhold the durable maps and both features must be NaN for every row, not computed from
    whatever the corpus happens to start at.
    """
    without = vector.computability(
        corpus, make_context(first_seen={}, device_first_seen={}), sample=sample
    )
    for name in ("velocity_ratio_1h_vs_30d", "device_age_days"):
        assert without.distinct[name] == 0, f"{name} was inferred from the corpus"
        assert without.nan_rate[name] == 1.0

    # And the control: with them supplied the features are alive, so the NaN above is about the
    # missing durable state and not about the features being broken.
    with_durable = vector.computability(corpus, make_context(), sample=sample)
    for name in ("velocity_ratio_1h_vs_30d", "device_age_days"):
        assert with_durable.distinct[name] > 1, f"{name} is dead even with durable state supplied"


@pytest.mark.req("FR-02-02", "D-04")
def test_a_truncated_corpus_does_not_change_the_ratio(
    corpus: list[Transaction], context: FeatureContext
) -> None:
    """The consequence that made this a MAJOR finding rather than a tidiness one.

    The same scored row, computed against the whole corpus and against a corpus that begins
    part-way through the account's life, must give the **same** ratio — because the denominator
    comes from the durable first-seen and not from whichever row the corpus happens to start at.
    Under the old inference these differed, and the truncated one read higher.
    """
    # Score the last row of an account that is active on both sides of the cut, so truncation
    # genuinely hides part of its history rather than none of it.
    cut = len(corpus) // 2
    early = {row.account_id for row in corpus[:cut]}
    candidates = [i for i, row in enumerate(corpus) if i >= cut and row.account_id in early]
    assert candidates, "precondition: some account spans the cut"
    index = candidates[-1]
    scored = corpus[index]
    full = vector.compute(corpus, index, context)["velocity_ratio_1h_vs_30d"]

    truncated = corpus[cut:]
    truncated_index = truncated.index(scored)
    assert any(row.account_id == scored.account_id for row in corpus[:cut]), (
        "precondition: the scored account has rows before the cut, so truncation really does "
        "hide part of its history"
    )
    partial = vector.compute(truncated, truncated_index, context)["velocity_ratio_1h_vs_30d"]

    assert isinstance(full, float)
    assert isinstance(partial, float)
    assert not math.isnan(full), "precondition: the ratio is defined on the full corpus"
    assert full == partial, (
        f"truncating the corpus moved the ratio from {full!r} to {partial!r}, so the denominator "
        "is coming from the corpus rather than from the durable first-seen"
    )


@pytest.mark.req("FR-02-02", "D-04")
def test_truncating_the_corpus_changes_no_unbounded_feature(
    corpus: list[Transaction], context: FeatureContext
) -> None:
    """The whole class, not the two instances the review named.

    Five features declare **unbounded** history: the velocity ratio and the device age through a
    timestamp, and three novelty flags through a set. Every one of them asks a question about all
    of an account's life, and **a corpus cannot know what it does not contain** — so computing any
    of them from a passed-in corpus is wrong whenever that corpus is truncated, which is every run
    this repository has performed.

    The test is the same for all five: score a row against the full corpus and against a corpus
    beginning part-way through that account's life, having told the second what came before, and
    require the values to be identical. Under the old code the timestamps moved and the flags
    flipped to "new".
    """
    cut = len(corpus) // 2
    early = corpus[:cut]
    later = corpus[cut:]
    spanning = {row.account_id for row in early} & {row.account_id for row in later}
    assert spanning, "precondition: some account is active on both sides of the cut"

    index = max(i for i, row in enumerate(corpus) if row.account_id in spanning)
    scored = corpus[index]
    account = scored.account_id
    assert any(row.account_id == account for row in early), (
        "precondition: the scored account has history the truncated corpus cannot see"
    )

    truncated_context = FeatureContext(
        countries=context.countries,
        outcomes=context.outcomes,
        sim_swaps=context.sim_swaps,
        first_seen=context.first_seen,
        device_first_seen=context.device_first_seen,
        counterparties_before={
            account: frozenset(
                r.counterparty_id for r in early if r.account_id == account and r.counterparty_id
            )
        },
        countries_before={
            account: frozenset(
                r.counterparty_country
                for r in early
                if r.account_id == account and r.counterparty_country
            )
        },
        devices_before={
            account: frozenset(
                r.device_fingerprint
                for r in early
                if r.account_id == account and r.device_fingerprint
            )
        },
        cash_out_codes=context.cash_out_codes,
        cell_rate_prior=context.cell_rate_prior,
    )

    full = vector.compute(corpus, index, context)
    partial = vector.compute(later, later.index(scored), truncated_context)
    unbounded = (
        "velocity_ratio_1h_vs_30d",
        "device_age_days",
        "counterparty_is_new_for_account",
        "is_new_country_for_account",
        "device_is_new_for_account",
    )
    for name in unbounded:
        a, b = full[name], partial[name]
        if isinstance(a, float) and isinstance(b, float) and math.isnan(a) and math.isnan(b):
            continue
        assert a == b, (
            f"{name} moved from {a!r} to {b!r} when the corpus was truncated, so it is answering "
            "an unbounded question from a bounded corpus"
        )


@pytest.mark.req("FR-02-02", "D-04")
def test_a_truncated_corpus_without_prior_knowledge_reports_everything_as_new() -> None:
    """The control: the defect is real and this fixture can produce it.

    Without `known_before` the same truncated corpus reports a payee the account has used for
    months as new. If this did not happen, the test above would be passing over a corpus that hid
    nothing.
    """
    earlier = Transaction(
        transaction_id="old",
        account_id="A",
        timestamp=START,
        amount_rwf=1_000.0,
        latitude=-1.9441,
        longitude=30.0619,
        counterparty_id="PAYEE",
        counterparty_country="AA",
    )
    scored = Transaction(
        transaction_id="now",
        account_id="A",
        timestamp=START + timedelta(days=200),
        amount_rwf=1_000.0,
        latitude=-1.9441,
        longitude=30.0619,
        counterparty_id="PAYEE",
        counterparty_country="AA",
    )
    assert batch.counterparty_is_new_for_account([earlier], scored) is False
    assert batch.counterparty_is_new_for_account([], scored) is True, (
        "precondition: a corpus that hides the earlier payment reports the payee as new"
    )
    assert batch.counterparty_is_new_for_account([], scored, known_before={"PAYEE"}) is False
