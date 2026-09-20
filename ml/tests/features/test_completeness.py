"""Every registered feature is implemented on both paths (Part E.2, ML-DATA-07).

The registry declares 44 features and the two paths implement them. Nothing else checks that the
two lists are the same list: a feature could be declared and never written, or written under a
name the registry does not know, and every other test in this suite would still pass — each one
tests the features it names.

The map below is the check and is also the call surface, written out. Adding a feature to the
registry without adding it here fails; adding a callable that no feature declares fails too.
"""

from __future__ import annotations

import pytest

from fraudshield_ml.features import batch
from fraudshield_ml.features.online import OnlineFeatures
from fraudshield_ml.features.registry import REGISTRY, Group

#: feature name -> (batch callable, online method). Several features share one implementation,
#: because the registry gives them one contract: the four trailing counts, the two amount sums.
#: Where that happens the shared callable takes the feature's name and reads its window from the
#: registry, so the features stay distinguishable at the call site rather than in the body.
IMPLEMENTATIONS: dict[str, tuple[str, str]] = {
    # velocity (8)
    "velocity_ratio_1h_vs_30d": ("velocity_ratio_1h_vs_30d", "velocity_ratio_1h_vs_30d"),
    "tx_count_60s": ("tx_count", "tx_count"),
    "tx_count_1h": ("tx_count", "tx_count"),
    "tx_count_24h": ("tx_count", "tx_count"),
    "tx_count_7d": ("tx_count", "tx_count"),
    "amount_sum_24h": ("amount_sum", "amount_sum"),
    "amount_sum_7d": ("amount_sum", "amount_sum"),
    "unique_counterparties_24h": ("unique_counterparties_24h", "unique_counterparties_24h"),
    # amount behaviour (5)
    "amount_log1p": ("amount_log1p", "amount_log1p"),
    "amount_zscore_90d": ("amount_zscore_90d", "amount_zscore_90d"),
    "amount_to_max_90d_ratio": ("amount_to_max_90d_ratio", "amount_to_max_90d_ratio"),
    "round_sum_flag": ("round_sum_flag", "round_sum_flag"),
    "just_below_limit_flag": ("just_below_limit_flag", "just_below_limit_flag"),
    # temporal (6)
    "local_hour_sin": ("local_hour_sin", "local_hour_sin"),
    "local_hour_cos": ("local_hour_cos", "local_hour_cos"),
    "local_day_of_week": ("local_day_of_week", "local_day_of_week"),
    "is_local_night": ("is_local_night", "is_local_night"),
    "is_month_end_window": ("is_month_end_window", "is_month_end_window"),
    "seconds_since_last_tx": ("seconds_since_last_tx", "seconds_since_last_tx"),
    # geographic (5)
    "distance_from_last_tx_km": ("distance_from_last_tx_km", "distance_from_last_tx_km"),
    "implied_speed_kmh": ("implied_speed_kmh", "implied_speed_kmh"),
    "distance_from_home_centroid_km": (
        "distance_from_home_centroid_km",
        "distance_from_home_centroid_km",
    ),
    "is_new_country_for_account": ("is_new_country_for_account", "is_new_country_for_account"),
    "geo_cell_fraud_rate_30d": ("geo_cell_fraud_rate_30d", "geo_cell_fraud_rate_30d"),
    # counterparty (5)
    "counterparty_is_new_for_account": (
        "counterparty_is_new_for_account",
        "counterparty_is_new_for_account",
    ),
    "counterparty_account_age_days": (
        "counterparty_account_age_days",
        "counterparty_account_age_days",
    ),
    "counterparty_unique_senders_24h": (
        "counterparty_unique_senders_24h",
        "counterparty_unique_senders_24h",
    ),
    "counterparty_confirmed_fraud_90d": (
        "counterparty_confirmed_fraud_90d",
        "counterparty_confirmed_fraud_90d",
    ),
    "tx_count_to_counterparty_30d": (
        "tx_count_to_counterparty_30d",
        "tx_count_to_counterparty_30d",
    ),
    # device and channel (5)
    "channel": ("channel", "channel"),
    "device_is_new_for_account": ("device_is_new_for_account", "device_is_new_for_account"),
    "accounts_per_device_7d": ("accounts_per_device_7d", "accounts_per_device_7d"),
    "device_changes_24h": ("device_changes_24h", "device_changes_24h"),
    "device_age_days": ("device_age_days", "device_age_days"),
    # account profile (4)
    "account_age_days": ("account_age_days", "account_age_days"),
    "kyc_tier": ("kyc_tier", "kyc_tier"),
    "days_since_sim_swap": ("days_since_sim_swap", "days_since_sim_swap"),
    "dormancy_reactivation_flag": ("dormancy_reactivation_flag", "dormancy_reactivation_flag"),
    # agent (4)
    "agent_float_utilisation_ratio": (
        "agent_float_utilisation_ratio",
        "agent_float_utilisation_ratio",
    ),
    "agent_cashout_count_1h": ("agent_cashout_count_1h", "agent_cashout_count_1h"),
    "agent_unique_customers_1h": ("agent_unique_customers_1h", "agent_unique_customers_1h"),
    "agent_distance_from_registered_km": (
        "agent_distance_from_registered_km",
        "agent_distance_from_registered_km",
    ),
    # corridor (1) and synthetic identity (1)
    "corridor_class": ("corridor_class", "corridor_class"),
    "synthetic_identity_score": ("synthetic_identity_score", "synthetic_identity_score"),
}


@pytest.mark.req("FR-02-02", "ML-DATA-07")
def test_every_declared_feature_is_implemented_on_both_paths() -> None:
    """The registry's 44 and the map above are the same set, and every entry resolves."""
    assert set(IMPLEMENTATIONS) == set(REGISTRY), (
        f"declared but not implemented: {sorted(set(REGISTRY) - set(IMPLEMENTATIONS))}; "
        f"implemented but not declared: {sorted(set(IMPLEMENTATIONS) - set(REGISTRY))}"
    )
    assert len(REGISTRY) == 44, "Part E.2's catalogue is 44 features (D-03)"

    for name, (batch_name, online_name) in IMPLEMENTATIONS.items():
        assert callable(getattr(batch, batch_name, None)), f"{name}: batch.{batch_name} missing"
        assert callable(getattr(OnlineFeatures, online_name, None)), (
            f"{name}: OnlineFeatures.{online_name} missing"
        )


@pytest.mark.req("FR-02-02", "D-03")
def test_the_group_counts_still_match_part_e2() -> None:
    """8/5/6/5/5/5/4/4/1/1. Asserted here as well as in `test_registry` because this module is
    what a reader consults to find out whether a group was finished."""
    expected = {
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
    counted = dict.fromkeys(expected, 0)
    for spec in REGISTRY.values():
        counted[spec.group] += 1
    assert counted == expected
    assert sum(expected.values()) == 44


@pytest.mark.req("FR-02-02")
def test_no_feature_is_implemented_by_a_name_the_registry_does_not_know() -> None:
    """The control for the map: a public callable in `batch` that computes a feature must appear
    in it.

    Written as an allowlist of the helpers and constants that are deliberately not features, so
    that a new public function has to be classified rather than silently ignored — which is how a
    feature gets written under a name nothing checks.
    """
    not_features = {
        "window_of",
        "counterparty_of",
        "local_time",
        "local_hour",
        "channel",
        "SHORT_WINDOW",
        "LONG_WINDOW",
        "CELL_WINDOW",
        "MAX_IMPLIED_SPEED_KMH",
        "MAD_TO_SIGMA",
        "LIMIT_BAND",
        "SECONDS_PER_DAY",
        "NIGHT_HOURS",
        "MONTH_END_DAYS",
        "MONTH_START_DAYS",
        "DEVICE_FEATURES",
        "AGENT_FEATURES",
        "NEW_ACCOUNT_DAYS",
        "SYNTHETIC_IDENTITY_TERMS",
        "RAMP_SHORT_WINDOW",
    }
    public = {
        name for name in vars(batch) if not name.startswith("_") and name not in {"annotations"}
    }
    # Imported symbols are not this module's surface.
    imported = {
        "Mapping",
        "Sequence",
        "datetime",
        "timedelta",
        "math",
        "h3_cell",
        "haversine_km",
        "REGISTRY",
        "categories_for",
        "smoothing_for",
        "AgentStanding",
        "CountryFacts",
        "IdentityEvidence",
        "LimitDimension",
        "OperationalLimit",
        "Outcome",
        "TierAssignment",
        "Transaction",
    }
    unexplained = public - imported - not_features - {v[0] for v in IMPLEMENTATIONS.values()}
    assert not unexplained, (
        f"batch exports {sorted(unexplained)}, which is neither a declared feature nor a listed "
        "helper. A feature written under a name the registry does not know is tested by nothing"
    )
