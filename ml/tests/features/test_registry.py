"""The registry's job is to refuse an underspecified feature. These tests prove it can.

Per E12 every bound-or-absence test asserts its own precondition first: a test that a blank field
is refused is worthless if the fixture's field was never blank.
"""

from __future__ import annotations

import re
from collections import Counter

import pytest

from fraudshield_ml.features.registry import (
    REGISTRY,
    Computability,
    Dtype,
    FallbackBehaviour,
    FeatureSpec,
    Group,
    HistoryBasis,
    HistoryKey,
    HistoryRequirement,
    LabelBasis,
    MinimumHistory,
    Nesting,
    PriorSource,
    ReferenceDataBasis,
    SelfInclusion,
    Smoothing,
    SmoothingPlacement,
    Source,
    WindowContract,
    categories_for,
    validate,
)

CONTRACT_FIELDS = (
    "self_inclusion",
    "nesting",
    "smoothing",
    "history_basis",
    "history_requirement",
    "fallback_behaviour",
    "label_basis",
    "minimum_history",
    "history_key",
)


def _contract(**overrides: object) -> WindowContract:
    defaults: dict[str, object] = {
        "self_inclusion": SelfInclusion.EXCLUDED,
        "nesting": Nesting.NOT_NESTED,
        "smoothing": None,
        "history_basis": HistoryBasis.NOT_TIME_NORMALISED,
        "history_requirement": HistoryRequirement.CACHE_SUFFICIENT,
        "fallback_behaviour": FallbackBehaviour.EXACT,
        "label_basis": LabelBasis.NOT_LABEL_DERIVED,
        "minimum_history": None,
        "history_key": HistoryKey.ACCOUNT,
    }
    defaults.update(overrides)
    return WindowContract(**defaults)  # type: ignore[arg-type]


def _spec(**overrides: object) -> FeatureSpec:
    defaults: dict[str, object] = {
        "name": "example_count_24h",
        "group": Group.VELOCITY,
        "dtype": Dtype.INT64,
        "definition": "A count over 24 h.",
        "source": Source.REDIS,
        "nan_rule": "Never NaN.",
        "leakage_note": "Backward-looking only.",
        "template_id": "velocity.example",
        "reference_data_basis": ReferenceDataBasis.NOT_REFERENCE_DATA,
        "computable": Computability.COMPUTABLE,
        "window": "24h",
        "contract": _contract(),
    }
    defaults.update(overrides)
    return FeatureSpec(**defaults)  # type: ignore[arg-type]


@pytest.mark.req("FR-02-02")
def test_a_windowed_feature_without_a_contract_is_refused() -> None:
    """The validation the seven fields exist for."""
    with pytest.raises(ValueError, match="no WindowContract"):
        _spec(contract=None)


@pytest.mark.req("FR-02-02")
def test_a_contract_without_a_window_is_refused() -> None:
    """The fields describe how a window is read; without one they assert nothing."""
    with pytest.raises(ValueError, match="declares no window"):
        _spec(window=None)


@pytest.mark.req("FR-02-02")
@pytest.mark.parametrize("field", ["definition", "nan_rule", "leakage_note", "template_id"])
def test_a_blank_prose_field_is_refused(field: str) -> None:
    """Whitespace is not an answer. A blank leakage note is the one that matters most."""
    assert _spec().__getattribute__(field).strip(), "precondition: the default field is non-blank"
    with pytest.raises(ValueError, match=f"{field} is blank"):
        _spec(**{field: "   "})


@pytest.mark.req("FR-02-02")
def test_observed_capped_history_forces_a_durable_first_seen() -> None:
    """Dividing by observed history needs a first-seen timestamp a cache flush would destroy.

    Without the rule a feature could declare OBSERVED_CAPPED and CACHE_SUFFICIENT together, and
    after a flush would silently divide by a shorter apparent history — inflating every rate it
    feeds, in the direction that makes a busy account look normal.
    """
    with pytest.raises(ValueError, match="requires history_requirement=DURABLE"):
        _contract(
            history_basis=HistoryBasis.OBSERVED_CAPPED,
            history_requirement=HistoryRequirement.CACHE_SUFFICIENT,
        )


@pytest.mark.req("FR-02-02")
def test_the_durable_rule_permits_the_combination_it_is_meant_to_permit() -> None:
    """The control, per the mutation table: a rule that refuses everything is not a rule."""
    contract = _contract(
        history_basis=HistoryBasis.OBSERVED_CAPPED,
        history_requirement=HistoryRequirement.DURABLE,
    )
    assert contract.history_basis is HistoryBasis.OBSERVED_CAPPED


@pytest.mark.req("FR-02-02")
@pytest.mark.parametrize("alpha", [0.0, -1.0])
def test_a_non_positive_smoothing_alpha_is_refused(alpha: float) -> None:
    """alpha=0 is no smoothing at all, and would divide by zero on a zero-history account."""
    with pytest.raises(ValueError, match="alpha must be positive"):
        Smoothing(alpha=alpha, placement=SmoothingPlacement.BOTH_TERMS, prior=1.0)


@pytest.mark.req("FR-02-02")
def test_a_registry_key_must_match_its_spec_name() -> None:
    spec = _spec()
    with pytest.raises(ValueError, match="does not match spec name"):
        validate({"a_different_key": spec})


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_every_registered_feature_answers_every_contract_field() -> None:
    """E13: exercise the registry non-trivially — it must hold features, not be empty."""
    assert REGISTRY, "precondition: the registry is not empty"
    windowed = {n: s for n, s in REGISTRY.items() if s.window is not None}
    assert windowed, "precondition: at least one registered feature is windowed"
    for name, spec in windowed.items():
        assert spec.contract is not None
        for field in CONTRACT_FIELDS:
            assert hasattr(spec.contract, field), f"{name} is missing {field}"


@pytest.mark.req("FR-02-02")
def test_the_velocity_ratio_returns_one_for_a_zero_history_account_by_construction() -> None:
    """The settled convention, asserted as a registry fact before either path computes it.

    Equal alpha on both terms with prior 1.0 is what makes 0-history return exactly 1.0 rather
    than 0.0 (which reads as suspiciously quiet) or NaN (which discards the row).
    """
    contract = REGISTRY["velocity_ratio_1h_vs_30d"].contract
    assert contract is not None
    smoothing = contract.smoothing
    assert smoothing is not None, "precondition: the feature declares smoothing"
    assert smoothing.placement is SmoothingPlacement.BOTH_TERMS
    assert smoothing.prior == 1.0
    assert contract.nesting is Nesting.SHORT_EXCLUDED
    assert contract.self_inclusion is SelfInclusion.EXCLUDED


@pytest.mark.req("FR-02-02")
def test_the_label_derived_feature_declares_its_lag_and_a_fitted_prior() -> None:
    """A fraud rate shrunk toward 1.0 would make every unseen cell certain fraud."""
    contract = REGISTRY["geo_cell_fraud_rate_30d"].contract
    assert contract is not None
    assert contract.label_basis is LabelBasis.AVAILABLE_AT_LAG
    smoothing = contract.smoothing
    assert smoothing is not None, "precondition: the feature declares smoothing"
    assert smoothing.prior is PriorSource.GLOBAL_TRAIN_RATE, (
        "a fitted prior must not be a literal: it is scale-dependent (E2) and computing it over "
        "all rows would leak validation labels into every cell"
    )


@pytest.mark.req("FR-02-02")
@pytest.mark.parametrize("observations", [0, -3])
def test_a_minimum_history_below_one_observation_is_refused(observations: int) -> None:
    """A threshold of zero is not a threshold; it declares a cliff that can never be reached."""
    with pytest.raises(ValueError, match="at least 1"):
        MinimumHistory(minimum_observations=observations, below_threshold_value=0.0)


@pytest.mark.req("FR-02-02")
def test_emitting_zero_below_the_threshold_is_representable_and_distinct_from_nan() -> None:
    """The two options must be distinguishable in the contract, because they are not equivalent.

    A z-score of 0.0 is the *most normal possible value*, so a thin-history account emitting 0.0 is
    scored as perfectly typical; emitting NaN scores it as unknown and lets the models' native
    missing handling decide. E.2's prose ("NaN->0 with < 5 history") admits both readings, which is
    exactly why the registry has to pick one out loud.
    """
    emits_zero = MinimumHistory(minimum_observations=5, below_threshold_value=0.0)
    emits_nan = MinimumHistory(minimum_observations=5, below_threshold_value=None)
    assert emits_zero != emits_nan, "precondition: the two readings are distinct contract values"
    assert emits_zero.below_threshold_value == 0.0
    assert emits_nan.below_threshold_value is None


@pytest.mark.req("FR-02-02")
@pytest.mark.parametrize(
    "key", [HistoryKey.COUNTERPARTY, HistoryKey.GEO_CELL, HistoryKey.DEVICE, HistoryKey.MERCHANT]
)
def test_a_non_account_history_key_without_a_stated_control_is_refused(key: HistoryKey) -> None:
    """E1's account-grouped folds are blind to a leak that does not travel through the account.

    A fraud ring moving money from ten victim accounts into one mule gives ten rows whose
    counterparty-keyed aggregate is the same object. Split those accounts across folds and the
    validation rows are inside the feature the training rows saw. The grouping rule cannot see it,
    so the registry refuses the feature until something else is named that can.
    """
    assert key is not HistoryKey.ACCOUNT, "precondition: the key aggregates across accounts"
    with pytest.raises(ValueError, match="cross_account_control must state"):
        _contract(history_key=key)


@pytest.mark.req("FR-02-02")
def test_an_account_keyed_feature_may_not_claim_a_cross_account_control() -> None:
    """A control on an already-isolated feature describes something that is not doing anything."""
    with pytest.raises(ValueError, match="not doing anything"):
        _contract(history_key=HistoryKey.ACCOUNT, cross_account_control="folds are grouped")


@pytest.mark.req("FR-02-02")
def test_the_non_account_rule_accepts_a_stated_control() -> None:
    """The control case: a rule that refuses every non-account feature would be unusable."""
    contract = _contract(
        history_key=HistoryKey.COUNTERPARTY,
        cross_account_control=(
            "Aggregates computed from training-fold rows only, grouped by account. Detected by the "
            "mutation that computes them over all rows and asserts the single-feature AUC rises."
        ),
    )
    assert contract.history_key is HistoryKey.COUNTERPARTY


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_every_non_account_keyed_registered_feature_names_its_control() -> None:
    """E13: the assertion is worthless unless a non-account-keyed feature is actually registered."""
    non_account = {
        n: s
        for n, s in REGISTRY.items()
        if s.contract is not None and s.contract.history_key is not HistoryKey.ACCOUNT
    }
    assert non_account, (
        "precondition: at least one registered feature aggregates across accounts; without one "
        "this test passes vacuously (E12)"
    )
    for name, spec in non_account.items():
        assert spec.contract is not None
        control = (spec.contract.cross_account_control or "").strip()
        assert control, f"{name} aggregates across accounts without a stated control"


@pytest.mark.req("FR-02-02")
def test_a_cross_account_control_that_only_asserts_safety_is_refused() -> None:
    """The exact shape of the defect this field was created by, refused at construction.

    geo_cell_fraud_rate_30d's first leakage note asserted that account-grouped folds kept
    validation labels out of cell estimates. The claim was false and a reader auditing the note
    would have been reassured by it — which is worse than an omission, because it consumes the
    reviewer attention that would otherwise have found the gap. A control must therefore name the
    mutation that would detect the leak if the control failed.
    """
    plausible_but_unfalsifiable = (
        "Cell estimates are computed from training-fold rows only, with folds grouped by account, "
        "so no validation label reaches any estimate."
    )
    assert "mutation" not in plausible_but_unfalsifiable.lower(), (
        "precondition: the control reads as a safety claim and names no falsification test"
    )
    with pytest.raises(ValueError, match="must name the mutation"):
        _contract(
            history_key=HistoryKey.GEO_CELL,
            cross_account_control=plausible_but_unfalsifiable,
        )


@pytest.mark.req("FR-02-02")
def test_the_feature_reading_mutable_config_declares_as_of_event() -> None:
    """ADR 0026: thresholds in force at the transaction, never today's."""
    spec = REGISTRY["just_below_limit_flag"]
    assert spec.window is None, (
        "precondition: this feature declares no window, which is why reference_data_basis lives on "
        "FeatureSpec and not on WindowContract"
    )
    assert spec.reference_data_basis is ReferenceDataBasis.AS_OF_EVENT


@pytest.mark.req("FR-02-02")
def test_the_thin_history_zscore_emits_nan_not_zero() -> None:
    """Owner deviation from E.2, recorded in the nan_rule: 0.0 claims 'exactly average'."""
    contract = REGISTRY["amount_zscore_90d"].contract
    assert contract is not None
    threshold = contract.minimum_history
    assert threshold is not None, "precondition: the feature declares a minimum-history cliff"
    assert threshold.minimum_observations == 5
    assert threshold.below_threshold_value is None, (
        "0.0 is the most normal possible z-score; emitting it scores a thin-history account as "
        "perfectly typical, and new accounts are disproportionately fraud-relevant"
    )


EXPECTED_GROUP_COUNTS = {
    Group.VELOCITY: 8,
    Group.AMOUNT_BEHAVIOUR: 5,
    Group.TEMPORAL: 6,
    Group.GEOGRAPHIC: 5,
    Group.COUNTERPARTY: 5,
    Group.DEVICE_AND_CHANNEL: 5,
    Group.ACCOUNT_PROFILE: 4,
    Group.AGENT: 4,
    Group.CORRIDOR: 1,
    Group.SYNTHETIC_IDENTITY: 1,
}


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_the_catalogue_is_complete_and_its_groups_match_part_e2() -> None:
    """44, and 44 in the right places.

    D-03 records that FR-02-02's own register row lists group counts summing to 46 — temporal (7)
    and account profile (5) where E.2's catalogue names 6 and 4. E.2 enumerates every feature by
    name, so it is authoritative, and this test pins the registry to it. A bare `len == 44` would
    pass with a temporal feature miscounted as account profile, which is exactly the confusion D-03
    is about.
    """
    assert sum(EXPECTED_GROUP_COUNTS.values()) == 44, "precondition: the expectation sums to 44"
    actual = Counter(spec.group for spec in REGISTRY.values())
    assert dict(actual) == EXPECTED_GROUP_COUNTS
    assert len(REGISTRY) == 44


@pytest.mark.req("FR-02-02", "D-04")
def test_exactly_four_device_features_and_four_agent_features_are_structurally_nan() -> None:
    """D-04 fixes the counts at four and four; the parity suite asserts NaN positions match.

    synthetic_identity_score deliberately does NOT propagate NaN from its device-sharing component:
    if it did, a USSD transaction would lose five features and the D-04 contract would be wrong by
    one wherever it is asserted.
    """
    device_nan = [n for n, s in REGISTRY.items() if "null device fingerprint" in s.nan_rule]
    agent_nan = [n for n, s in REGISTRY.items() if "outside AGENT_BANKING" in s.nan_rule]
    assert len(device_nan) == 4, f"expected 4 device NaN features, got {sorted(device_nan)}"
    assert len(agent_nan) == 4, f"expected 4 agent NaN features, got {sorted(agent_nan)}"
    assert "synthetic_identity_score" not in device_nan


@pytest.mark.req("FR-02-02")
def test_every_feature_reading_mutable_configuration_declares_its_as_of_rule() -> None:
    """ADR 0026. E12: vacuous unless at least one feature actually reads mutable config."""
    as_of = [
        n for n, s in REGISTRY.items() if s.reference_data_basis is ReferenceDataBasis.AS_OF_EVENT
    ]
    assert as_of, "precondition: at least one registered feature reads mutable operational config"
    assert "just_below_limit_flag" in as_of
    assert "kyc_tier" in as_of, (
        "kyc_tier is the feature proving reference_data_basis is not a one-feature field: tiers "
        "are upgraded, and the upgrade often follows the very activity being scored"
    )


@pytest.mark.req("FR-02-02")
def test_no_durable_feature_is_silently_served_by_a_rolling_window() -> None:
    """Every DURABLE feature must say in its leakage note what a cache flush would do to it.

    The cold-cache parity case is the only test that exercises DURABLE, so a DURABLE feature whose
    flush behaviour was never reasoned about is one the suite will assert nothing useful about.
    """
    durable = {
        n: s
        for n, s in REGISTRY.items()
        if s.contract is not None and s.contract.history_requirement is HistoryRequirement.DURABLE
    }
    assert durable, "precondition: at least one registered feature is DURABLE"
    for name, spec in durable.items():
        note = spec.leakage_note.lower()
        assert "flush" in note or "durable" in note, (
            f"{name} is DURABLE but its leakage note does not say what a cache flush would do"
        )


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_feature_with_no_signal_on_this_dataset_declares_it() -> None:
    """PB-40. ML-DATA-07's completeness check cannot catch this class, so the registry must.

    A degenerate feature is *computable* — `accounts_per_device_7d` returns 1 for every row,
    `synthetic_identity_score` returns a number in [0, 1] with one of its four terms constant, and
    `corridor_class` returns a valid class that is only ever one of two of its four.
    "All 44 computable for >= 98% of records" is satisfied by all three. Nothing downstream emits
    a NaN or raises, so the loss is silent until someone asks why a feature has zero importance.

    The three are not degenerate for the same *kind* of reason, which is why the backlog item is
    read out of the declaration rather than fixed at PB-40: the first two are a generator gap that
    will be closed before M4 training, while `corridor_class` is degenerate because the owner has
    ruled that the simulated country set is not to be broadened (ADR 0023). A gap someone has
    decided to keep still has to be declared; what it must not do is look like the other kind.
    """
    degenerate = {n: s.degeneracy for n, s in REGISTRY.items() if s.degeneracy}
    assert degenerate, (
        "precondition: at least one feature is declared degenerate; without one this test passes "
        "vacuously (E12)"
    )
    assert set(degenerate) == {
        "accounts_per_device_7d",
        "synthetic_identity_score",
        "corridor_class",
    }
    for name, note in degenerate.items():
        assert re.search(r"PB-\d+", note), (
            f"{name} must name the backlog item tracking its degeneracy"
        )
        assert "Cleared" in note, (
            f"{name} must say what would clear the degeneracy, so it is a scheduled gap rather "
            "than a permanent property"
        )


@pytest.mark.req("FR-02-02")
def test_no_other_feature_silently_claims_to_be_fine() -> None:
    """The control: `degeneracy` defaults to None, so the test above proves nothing on its own.

    If every feature were accidentally marked degenerate the test above would still pass its
    membership check only by luck. This asserts the default actually applies to the other 41.
    """
    healthy = [n for n, s in REGISTRY.items() if s.degeneracy is None]
    assert len(healthy) == 41, f"expected 41 non-degenerate features, got {len(healthy)}"


@pytest.mark.req("FR-02-02")
def test_a_categorical_without_declared_values_is_refused() -> None:
    """ADR 0025 admits no tolerance for a categorical, so an open value set is unfalsifiable.

    Per E12 the precondition is asserted first: the accepted spec really does declare categories,
    so the refusal below is about the blank and not about some other difference.
    """
    accepted = _spec(dtype=Dtype.CATEGORICAL, window=None, contract=None, categories=("A", "B"))
    assert accepted.categories == ("A", "B"), "precondition: the accepted spec declares values"

    with pytest.raises(ValueError, match="permitted values must be declared"):
        _spec(dtype=Dtype.CATEGORICAL, window=None, contract=None)
    with pytest.raises(ValueError, match="permitted values must be declared"):
        _spec(dtype=Dtype.CATEGORICAL, window=None, contract=None, categories=())


@pytest.mark.req("FR-02-02")
def test_categories_on_a_non_categorical_feature_are_refused() -> None:
    """The control for the test above. Without it the field could be accepted anywhere and mean
    nothing, which is how a declared field becomes decoration."""
    with pytest.raises(ValueError, match="dtype is int64"):
        _spec(dtype=Dtype.INT64, categories=("A", "B"))


@pytest.mark.req("FR-02-02")
def test_a_duplicated_or_blank_category_is_refused() -> None:
    """A duplicate makes the declared order ambiguous, which is the thing both paths index by."""
    with pytest.raises(ValueError, match="duplicate category"):
        _spec(dtype=Dtype.CATEGORICAL, window=None, contract=None, categories=("A", "B", "A"))
    with pytest.raises(ValueError, match="a category is blank"):
        _spec(dtype=Dtype.CATEGORICAL, window=None, contract=None, categories=("A", " "))


@pytest.mark.req("FR-02-02")
def test_every_categorical_feature_declares_its_values_and_no_other_does() -> None:
    """The registry-wide form, so a categorical added later cannot skip the declaration."""
    categorical = {n for n, s in REGISTRY.items() if s.dtype is Dtype.CATEGORICAL}
    assert categorical == {"channel", "corridor_class"}, (
        "Part E.2 has exactly two categorical features; a change here is a contract change"
    )
    for name in categorical:
        values = categories_for(name)
        assert len(values) >= 2, f"{name}: a categorical with one value is a constant"
    for name, spec in REGISTRY.items():
        if name not in categorical:
            assert spec.categories is None, f"{name} declares categories but is not categorical"


@pytest.mark.req("FR-02-02")
def test_categories_for_refuses_a_feature_that_is_not_categorical() -> None:
    """A lookup that returned an empty tuple would let a caller iterate over nothing and conclude
    the feature has no permitted values, rather than that the question does not apply."""
    with pytest.raises(ValueError, match="not categorical"):
        categories_for("tx_count_24h")


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_feature_with_no_source_data_must_say_what_it_needs_and_when() -> None:
    """PB-44. A feature that is NaN for every row is invisible in a training run, so the
    declaration is the only place it shows — and a declaration that says nothing is not one.

    The milestone is required for the same reason the mutation is required in
    `cross_account_control`: a crude check, because a check that could be satisfied by writing a
    more convincing sentence is how the original geo_cell note passed. "This needs a table"
    without a date is how a gap survives three milestones.
    """
    accepted = _spec(
        computable=Computability.NO_SOURCE_DATA,
        source_data_gap="A per-account table, supplied by M6.",
    )
    assert accepted.source_data_gap, "precondition: the accepted spec declares a gap"

    with pytest.raises(ValueError, match="which milestone supplies it"):
        _spec(computable=Computability.NO_SOURCE_DATA)
    with pytest.raises(ValueError, match="which milestone supplies it"):
        _spec(computable=Computability.NO_SOURCE_DATA, source_data_gap="   ")
    with pytest.raises(ValueError, match="name the milestone"):
        _spec(computable=Computability.NO_SOURCE_DATA, source_data_gap="Needs a table one day.")


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_a_computable_feature_may_not_carry_a_source_data_gap() -> None:
    """The control. Without it the field could be set anywhere and mean nothing, which is how a
    declared field becomes decoration."""
    with pytest.raises(ValueError, match="not blocking anything"):
        _spec(computable=Computability.COMPUTABLE, source_data_gap="Needs a table in M6.")


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_every_feature_declares_its_computability_and_six_have_no_source_data() -> None:
    """The registry-wide form, with the count asserted so that a change has to be deliberate.

    The six are the measured set, not an estimate: an earlier session's note said eight, from
    reasoning about which inputs were missing rather than from computing the features. The
    computability check exists because that kind of arithmetic is exactly what gets it wrong.
    """
    gaps = {n: s.source_data_gap for n, s in REGISTRY.items() if s.source_data_gap}
    assert set(gaps) == {
        "account_age_days",
        "counterparty_account_age_days",
        "kyc_tier",
        "agent_float_utilisation_ratio",
        "agent_distance_from_registered_km",
        "round_sum_flag",
    }
    assert len(REGISTRY) - len(gaps) == 38, "38 of the 44 have data to read"
    for name, gap in gaps.items():
        assert re.search(r"\bM\d+\b", gap or ""), f"{name}: no milestone named"
