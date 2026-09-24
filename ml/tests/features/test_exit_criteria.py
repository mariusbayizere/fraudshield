"""M3 exit criteria E4 and E5, asserted behaviourally rather than by reading the source.

**E4** — feature computation is deterministic and byte-identical across batch sizes, as the
generator is.

**E5** — no feature reads a column the anti-leakage gates exclude by construction: identifier
bytes, sub-second timestamp parts, row position, label delay. A source scan would prove only that
today's spelling avoids them; these tests change the excluded quantity and assert the vector does
not move, which is the property the criterion is about.
"""

from __future__ import annotations

import math
from dataclasses import replace
from datetime import timedelta

import pytest

from fraudshield_ml.features.registry import REGISTRY
from fraudshield_ml.features.types import Outcome, Transaction
from fraudshield_ml.features.vector import FeatureContext, FeatureValue, compute


def _bits(value: FeatureValue) -> str:
    """A float's exact bits, so "byte-identical" means what it says.

    `==` on floats would accept two values that differ in the last bit of their representation
    while comparing equal is not the risk — the risk is the reverse, a comparison that passes
    because both sides were rounded the same way in the printing. `float.hex()` is exact and
    reversible, and it distinguishes 0.0 from -0.0, which `==` does not.
    """
    if isinstance(value, str):
        return value
    return "nan" if math.isnan(value) else float(value).hex()


def _vectors(
    corpus: list[Transaction], indices: list[int], context: FeatureContext
) -> dict[str, dict[str, str]]:
    return {
        corpus[i].transaction_id: {k: _bits(v) for k, v in compute(corpus, i, context).items()}
        for i in indices
    }


# --- E4: determinism -----------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "ML-DATA-01")
def test_e4_the_vector_is_byte_identical_across_batch_sizes(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """Computed one row at a time, in fives, and in one pass: the same bits every time.

    The generator carries this property and the feature pipeline must too, for the same reason: a
    result that depends on how the work was chunked cannot be reproduced by someone who chunked it
    differently, and nothing in the output says how it was chunked.

    Compared through `float.hex()` rather than `==`, so "byte-identical" is not a comparison that
    passes because both sides were printed the same way.
    """
    one_pass = _vectors(corpus, sample, context)
    assert len(one_pass) == len(sample), "precondition: every sampled row produced a vector"

    for size in (1, 5, 17):
        batched: dict[str, dict[str, str]] = {}
        for start in range(0, len(sample), size):
            batched.update(_vectors(corpus, sample[start : start + size], context))
        assert batched == one_pass, f"batch size {size} produced a different vector"


@pytest.mark.req("FR-02-02")
def test_e4_the_vector_is_deterministic_across_repeated_runs(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """No randomness, no clock, no iteration-order dependence.

    Trivially true of pure functions and pinned anyway: a feature that reached for `datetime.now()`
    or iterated a set would pass every value test in this suite and fail only here.
    """
    assert _vectors(corpus, sample, context) == _vectors(corpus, sample, context)


# --- E5: the excluded columns ------------------------------------------------------------------


@pytest.mark.req("FR-02-02", "D-08")
def test_e5_no_feature_reads_identifier_bytes(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """Relabel every identifier through a bijection: the vector must not move.

    Identity is what the features may use — this account, that counterparty, the same device — and
    a bijection preserves every equality while changing every byte. So grouping still works and
    anything that hashed, sliced or ordered an identifier changes. The generator derives
    identifiers from a seeded hash of customer index and scenario, so an identifier's bytes carry
    the generator's structure directly; a feature reading them would be reading the answer.
    """
    relabel = {
        row.transaction_id: f"zz-{i:08d}"
        for i, row in enumerate(sorted(corpus, key=lambda r: r.transaction_id))
    }
    accounts = {row.account_id for row in corpus}
    account_map = {a: f"acct-{i:05d}" for i, a in enumerate(sorted(accounts))}
    parties = {row.counterparty_id for row in corpus if row.counterparty_id}
    party_map = {c: f"cp-{i:05d}" for i, c in enumerate(sorted(parties))}
    devices = {row.device_fingerprint for row in corpus if row.device_fingerprint}
    device_map = {d: f"dev-{i:05d}" for i, d in enumerate(sorted(devices))}
    agents = {row.agent_id for row in corpus if row.agent_id}
    agent_map = {g: f"ag-{i:05d}" for i, g in enumerate(sorted(agents))}

    assert len(relabel) == len(corpus), "precondition: the relabelling is a bijection"
    assert not set(relabel) & set(relabel.values()), "precondition: and changes every value"

    renamed = [
        replace(
            row,
            transaction_id=relabel[row.transaction_id],
            account_id=account_map[row.account_id],
            counterparty_id=party_map.get(row.counterparty_id or ""),
            device_fingerprint=device_map.get(row.device_fingerprint or ""),
            agent_id=agent_map.get(row.agent_id or ""),
        )
        for row in corpus
    ]

    # The bijection has to reach the context too, or this stops being a relabelling and becomes a
    # test that withholding labels and SIM swaps changes the answer — which it does, and which is
    # a different fact. `outcomes` is keyed by transaction and `sim_swaps` by account.
    # `replace` carries every other field across unchanged. Listing them by hand made the claim
    # "only the identifiers moved" depend on the list staying complete, and it did not: adding
    # `denominations` to the context dropped it here and `round_sum_flag` went NaN under the
    # relabelling, which reads as a leak and is a missing keyword argument.
    renamed_context = replace(
        context,
        outcomes={
            relabel[k]: Outcome(relabel[k], v.is_fraud, v.available_at)
            for k, v in context.outcomes.items()
        },
        sim_swaps={account_map[k]: v for k, v in context.sim_swaps.items()},
        # The durable first-seen maps are keyed by account and by device, so the bijection has to
        # reach them too — otherwise this stops being a relabelling and becomes a test that
        # withholding durable state changes the answer, which it does and which is a different
        # fact (PB-37).
        first_seen={account_map[k]: v for k, v in context.first_seen.items()},
        device_first_seen={device_map[k]: v for k, v in context.device_first_seen.items()},
    )

    original = [{k: _bits(v) for k, v in compute(corpus, i, context).items()} for i in sample]
    moved = [{k: _bits(v) for k, v in compute(renamed, i, renamed_context).items()} for i in sample]
    for i, (before, after) in enumerate(zip(original, moved, strict=True)):
        assert before == after, (
            f"{corpus[sample[i]].transaction_id}: the vector changed when every identifier was "
            "relabelled through a bijection, so some feature reads identifier bytes rather than "
            "identity"
        )


@pytest.mark.req("FR-02-02", "D-08")
def test_e5_no_feature_reads_row_position(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """Shift every scored row's index by prepending rows no feature can legitimately see.

    The corpus is ordered by time and that order is data — the prefix is the history. So the test
    cannot shuffle it. What it can do is move every row to a different **index** while leaving
    every row's history identical: prepend transactions from accounts, counterparties, devices and
    H3 cells that appear nowhere else, timestamped before everything.

    A feature reading its row's ordinal position would move. One reading history would not, because
    the filler shares no key with anything: it is invisible to the account-keyed features by
    account, to the counterparty- and device-keyed ones by identifier, and to the cell rate by
    being on another continent.
    """
    filler = [
        Transaction(
            transaction_id=f"filler-{i}",
            account_id=f"FILL{i}",
            timestamp=corpus[0].timestamp - timedelta(days=400 - i),
            amount_rwf=1.0,
            latitude=64.1466,
            longitude=-21.9426,
            account_country="AA",
            counterparty_country="AA",
            counterparty_id=f"FILLCP{i}",
            amount_minor=1,
            currency="AAA",
            channel="CARD",
            device_fingerprint=f"FILLDEV{i}",
            merchant_category_code="5411",
        )
        for i in range(7)
    ]
    shifted_corpus = filler + corpus
    shifted_indices = [i + len(filler) for i in sample]
    assert shifted_indices != sample, "precondition: every scored row sits at a new index"
    assert not ({row.account_id for row in filler} & {row.account_id for row in corpus}), (
        "precondition: the filler shares no account, so no window can legitimately see it"
    )

    assert _vectors(corpus, sample, context) == _vectors(shifted_corpus, shifted_indices, context)


#: The features whose value legitimately depends on sub-second resolution, and why. Asserted as an
#: exact set rather than skipped over, so a seventh joining them is a failure that has to be argued
#: for rather than a silent change in what the pipeline reads.
#:
#: The first five are elapsed-time quantities: a gap, a speed derived from it, a ratio dividing by
#: observed history, and two ages. Sub-second sensitivity there is physics, not an artefact.
#:
#: All five are elapsed-time quantities: a gap, a speed derived from it, a ratio dividing by
#: observed history, and two ages.
#:
#: **`geo_cell_fraud_rate_30d` was a sixth member under an earlier, evenly-spaced fixture, and is
#: not one here.** It never reads the sub-second part; it gates labels on `available_at < t`, and
#: moving `t` by microseconds can flip a label across that boundary *when the two coincide to the
#: second*. The old fixture spaced transactions in whole hours with `available_at` exactly two days
#: later, so the coincidence was systematic. Widening the gaps removed it. The possibility is real
#: and is recorded here rather than in the set, because a set is a claim about what this fixture
#: showed: **a label gate can turn on a quantity nobody intended it to depend on**, and the way to
#: find out is to vary the spacing rather than to assume either answer.
TIME_RESOLUTION_SENSITIVE = {
    "seconds_since_last_tx",
    "implied_speed_kmh",
    "velocity_ratio_1h_vs_30d",
    "device_age_days",
    "days_since_sim_swap",
}


@pytest.mark.req("FR-02-02", "D-08")
def test_e5_the_sub_second_part_of_a_timestamp_is_not_a_signal(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """Plant a label-correlated signal in the microseconds: only the declared six may notice.

    The criterion cannot be "nothing changes", because several features measure elapsed time and a
    gap is a real quantity at any resolution. It is that **no feature becomes a function of the
    sub-second part**, so the set that moves must be exactly the set that reads resolution for a
    stated reason — and any other feature moving means the planted signal reached it.

    The planted shape is the one that would matter: microseconds correlated with the label, which a
    generator can produce without anyone intending it and which no value test would notice.

    **This is a structural check, not a statistical one.** It proves which features are sensitive to
    sub-second resolution at all; whether any feature's residual correlates with sub-second parts on
    the real benchmark is a leakage measurement and belongs to M4.
    """
    outcomes = context.outcomes
    planted = [
        replace(
            row,
            timestamp=row.timestamp.replace(
                microsecond=999_999 if outcomes[row.transaction_id].is_fraud else 1
            ),
        )
        for row in corpus
    ]
    assert any(row.timestamp.microsecond == 999_999 for row in planted), (
        "precondition: the planted signal is present, or this test asserts nothing (E12)"
    )
    assert all(row.timestamp.microsecond == 0 for row in corpus), (
        "precondition: the unplanted corpus carries no sub-second information to begin with"
    )

    original = _vectors(corpus, sample, context)
    with_signal = _vectors(planted, sample, context)
    moved = {
        name
        for name in REGISTRY
        for transaction_id in original
        if original[transaction_id][name] != with_signal[transaction_id][name]
    }
    assert moved == TIME_RESOLUTION_SENSITIVE, (
        f"features that moved: {sorted(moved)}; declared resolution-sensitive: "
        f"{sorted(TIME_RESOLUTION_SENSITIVE)}. A feature on the left and not the right has "
        "started reading the sub-second part; one on the right and not the left no longer reads "
        "elapsed time and the declaration is stale"
    )


@pytest.mark.req("FR-02-02", "D-08")
def test_e5_sub_second_sensitivity_stays_inside_the_second_it_measures(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """The bound, for the features allowed to be sensitive at all.

    Being allowed to read a gap is not being allowed to read it as a signal, and the difference is
    magnitude: a perturbation of under a second may move a gap by under a second, and nothing more.
    Asserted on `seconds_since_last_tx`, where the units make the claim checkable directly.
    """
    outcomes = context.outcomes
    planted = [
        replace(
            row,
            timestamp=row.timestamp.replace(
                microsecond=999_999 if outcomes[row.transaction_id].is_fraud else 1
            ),
        )
        for row in corpus
    ]
    compared = 0
    for i in sample:
        plain = compute(corpus, i, context)["seconds_since_last_tx"]
        shifted = compute(planted, i, context)["seconds_since_last_tx"]
        if isinstance(plain, float) and isinstance(shifted, float) and not math.isnan(plain):
            assert abs(plain - shifted) < 1.0
            compared += 1
    assert compared > 0, "precondition: some scored row had a predecessor to measure a gap from"


@pytest.mark.req("FR-02-02", "D-08")
def test_e5_no_feature_reads_the_label_delay(
    corpus: list[Transaction], sample: list[int], context: FeatureContext
) -> None:
    """Move every label's arrival earlier, keeping it after the transaction it labels.

    The delay between a transaction and its label is an artefact of investigation, and it is the
    strongest leak the dataset has: M2 measured the event-to-transaction delay separating the
    classes at 0.746. Features may use `available_at` as a **gate** — a label is visible or it is
    not — and must never read it as a quantity. Halving every delay changes the quantity and leaves
    the gate's answer identical for these rows, so any feature reading the delay moves.
    """
    by_id = {row.transaction_id: row for row in corpus}
    tightened = {
        transaction_id: Outcome(
            transaction_id=outcome.transaction_id,
            is_fraud=outcome.is_fraud,
            available_at=by_id[transaction_id].timestamp
            + (outcome.available_at - by_id[transaction_id].timestamp) / 2,
        )
        for transaction_id, outcome in context.outcomes.items()
    }
    changed = sum(
        1 for k, v in tightened.items() if v.available_at != context.outcomes[k].available_at
    )
    assert changed > 0, "precondition: the delay actually changed for some labels (E12)"

    label_readers = {"geo_cell_fraud_rate_30d", "counterparty_confirmed_fraud_90d"}
    assert {
        name
        for name, spec in REGISTRY.items()
        if spec.contract is not None and spec.contract.label_basis.value == "available_at_lag"
    } == label_readers, "precondition: the registry still names these two as label-derived"

    # `replace` rather than a fresh FeatureContext listing the fields by hand. The claim under
    # test is "only the labels changed", and re-specifying the context makes that claim depend on
    # the list being complete: when `denominations` was added to the context, the hand-written
    # version silently dropped it and `round_sum_flag` "changed when the label delay changed".
    halved = replace(context, outcomes=tightened)
    for i in sample:
        before = compute(corpus, i, context)
        after = compute(corpus, i, halved)
        for name in REGISTRY:
            if name in label_readers:
                continue
            assert _bits(before[name]) == _bits(after[name]), (
                f"{name} changed when the label delay changed, and it is not label-derived"
            )


@pytest.mark.req("FR-02-02", "D-08")
def test_e5_the_outcome_record_carries_no_confirmation_time() -> None:
    """The structural half of the rule: a field that does not exist cannot be read.

    `label_basis` requires filtering on `available_at` and never on `confirmed_at`, and the record
    the features see has no `confirmed_at` at all. That is stronger than a convention, and it is
    why the batch path cannot make the mistake its asymmetry invites.
    """
    fields = set(Outcome.__dataclass_fields__)
    assert fields == {"transaction_id", "is_fraud", "available_at"}
    assert "confirmed_at" not in fields
