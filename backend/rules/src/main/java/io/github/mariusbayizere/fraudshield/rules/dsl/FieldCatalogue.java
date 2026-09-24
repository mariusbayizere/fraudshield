package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The fields a rule may reference: raw request fields and the 44 registered features (E.2, E.6).
 *
 * <p>The feature list mirrors {@code fraudshield_ml.features.registry.REGISTRY}; {@code
 * FieldCatalogueDriftTest} compares the two when the Python toolchain is available, so the lists
 * cannot drift silently. Booleans and ordinals are {@link FieldType#NUMBER}; only {@code channel}
 * and {@code corridor_class} are categorical. {@code merchant_name} is display-only (ADR 0011
 * section 12) and deliberately not a rule field.
 */
public final class FieldCatalogue {

  /** Number of registered features (D-03). */
  public static final int FEATURE_COUNT = 44;

  private static final String[] REQUEST_CATEGORIES = {
    "account_id",
    "counterparty_id",
    "currency",
    "channel",
    "device_fingerprint",
    "merchant_category_code",
    "agent_id",
    "counterparty_country"
  };

  /** The 42 numeric features; with {@code channel} and {@code corridor_class}, all 44. */
  static final String[] NUMERIC_FEATURES = {
    "velocity_ratio_1h_vs_30d",
    "geo_cell_fraud_rate_30d",
    "tx_count_60s",
    "tx_count_1h",
    "tx_count_24h",
    "tx_count_7d",
    "amount_sum_24h",
    "amount_sum_7d",
    "unique_counterparties_24h",
    "amount_log1p",
    "amount_zscore_90d",
    "amount_to_max_90d_ratio",
    "round_sum_flag",
    "just_below_limit_flag",
    "local_hour_sin",
    "local_hour_cos",
    "local_day_of_week",
    "is_local_night",
    "is_month_end_window",
    "seconds_since_last_tx",
    "distance_from_last_tx_km",
    "implied_speed_kmh",
    "distance_from_home_centroid_km",
    "is_new_country_for_account",
    "counterparty_is_new_for_account",
    "counterparty_account_age_days",
    "counterparty_unique_senders_24h",
    "counterparty_confirmed_fraud_90d",
    "tx_count_to_counterparty_30d",
    "device_is_new_for_account",
    "accounts_per_device_7d",
    "device_changes_24h",
    "device_age_days",
    "account_age_days",
    "kyc_tier",
    "days_since_sim_swap",
    "dormancy_reactivation_flag",
    "agent_float_utilisation_ratio",
    "agent_cashout_count_1h",
    "agent_unique_customers_1h",
    "agent_distance_from_registered_km",
    "synthetic_identity_score"
  };

  private static final Map<String, RuleField> FIELDS = build();

  private FieldCatalogue() {}

  private static Map<String, RuleField> build() {
    Map<String, RuleField> fields = new LinkedHashMap<>();
    for (String name :
        new String[] {
          "account_id", "counterparty_id", "currency", "channel", "device_fingerprint"
        }) {
      fields.put(name, new RuleField(name, FieldType.CATEGORY, FieldSource.REQUEST));
    }
    for (String name :
        new String[] {"merchant_category_code", "agent_id", "counterparty_country"}) {
      fields.put(name, new RuleField(name, FieldType.CATEGORY, FieldSource.REQUEST));
    }
    for (String name : new String[] {"amount", "amount_rwf", "latitude", "longitude"}) {
      fields.put(name, new RuleField(name, FieldType.NUMBER, FieldSource.REQUEST));
    }
    for (String name : NUMERIC_FEATURES) {
      fields.put(name, new RuleField(name, FieldType.NUMBER, FieldSource.FEATURE));
    }
    // "channel" is both a request field and a feature with the same value; the request field wins.
    fields.put(
        "corridor_class", new RuleField("corridor_class", FieldType.CATEGORY, FieldSource.FEATURE));
    return Collections.unmodifiableMap(fields);
  }

  /**
   * Looks up a field.
   *
   * @param name wire name
   * @return the field, or empty if rules may not reference it
   */
  public static Optional<RuleField> find(String name) {
    return Optional.ofNullable(FIELDS.get(name));
  }

  /**
   * All fields, in a stable order.
   *
   * @return the fields
   */
  public static List<RuleField> all() {
    return List.copyOf(FIELDS.values());
  }

  /**
   * The names of the 44 registered features.
   *
   * @return feature names, request-only fields excluded
   */
  public static Set<String> featureNames() {
    Set<String> names = new TreeSet<>();
    for (String name : NUMERIC_FEATURES) {
      names.add(name);
    }
    names.add("channel");
    names.add("corridor_class");
    return Collections.unmodifiableSet(names);
  }
}
