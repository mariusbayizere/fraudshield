package io.github.mariusbayizere.fraudshield.decision.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Machine-readable reasons returned to integrators (OpenAPI {@code ReasonCode}, at most three).
 *
 * <p>Model reasons are grouped: each feature maps to a coarse code, so the ingest response says why
 * without naming model features one by one (D-12 limits what the machine-to-machine response
 * reveals). The full SHAP explanation stays on staff endpoints.
 */
public final class ReasonCodes {

  /** At most three reasons per response (D-12). */
  public static final int MAX_REASONS = 3;

  /** The account is frozen (E.6 step 2). */
  public static final String ACCOUNT_FROZEN = "ACCOUNT_FROZEN";

  /** The rule-based fallback decided (C.4). */
  public static final String ML_UNAVAILABLE = "ML_UNAVAILABLE";

  /** A custom rule raised the tier (E.6 step 5). */
  public static final String CUSTOM_RULE = "CUSTOM_RULE";

  /** The merchant category's circuit breaker is open (FR-03-07). */
  public static final String MCC_CIRCUIT_BREAKER = "MCC_CIRCUIT_BREAKER";

  /** The model score alone put the transaction in its tier, with no feature attribution. */
  public static final String MODEL_RISK_SCORE = "MODEL_RISK_SCORE";

  /** A hold was released at its deadline without review (D-10). */
  public static final String REVIEW_TIMEOUT = "REVIEW_TIMEOUT";

  /** A hold was declined at its deadline under DECLINE_AND_VERIFY (D-10). */
  public static final String REVIEW_TIMEOUT_DECLINED = "REVIEW_TIMEOUT_DECLINED";

  private static final Map<String, String> BY_FEATURE =
      Map.ofEntries(
          Map.entry("velocity_ratio_1h_vs_30d", "VELOCITY_SPIKE"),
          Map.entry("tx_count_60s", "VELOCITY_SPIKE"),
          Map.entry("tx_count_1h", "VELOCITY_SPIKE"),
          Map.entry("tx_count_24h", "VELOCITY_SPIKE"),
          Map.entry("tx_count_7d", "VELOCITY_SPIKE"),
          Map.entry("unique_counterparties_24h", "MANY_COUNTERPARTIES"),
          Map.entry("amount_sum_24h", "HIGH_RECENT_VOLUME"),
          Map.entry("amount_sum_7d", "HIGH_RECENT_VOLUME"),
          Map.entry("amount_log1p", "AMOUNT_ABOVE_NORMAL"),
          Map.entry("amount_zscore_90d", "AMOUNT_ABOVE_NORMAL"),
          Map.entry("amount_to_max_90d_ratio", "AMOUNT_ABOVE_NORMAL"),
          Map.entry("round_sum_flag", "ROUND_AMOUNT"),
          Map.entry("just_below_limit_flag", "JUST_BELOW_LIMIT"),
          Map.entry("local_hour_sin", "UNUSUAL_HOUR"),
          Map.entry("local_hour_cos", "UNUSUAL_HOUR"),
          Map.entry("local_day_of_week", "UNUSUAL_HOUR"),
          Map.entry("is_local_night", "UNUSUAL_HOUR"),
          Map.entry("is_month_end_window", "MONTH_END_PATTERN"),
          Map.entry("seconds_since_last_tx", "RAPID_SUCCESSION"),
          Map.entry("distance_from_last_tx_km", "UNUSUAL_LOCATION"),
          Map.entry("distance_from_home_centroid_km", "UNUSUAL_LOCATION"),
          Map.entry("implied_speed_kmh", "IMPLAUSIBLE_TRAVEL"),
          Map.entry("is_new_country_for_account", "NEW_COUNTRY"),
          Map.entry("geo_cell_fraud_rate_30d", "HIGH_RISK_AREA"),
          Map.entry("counterparty_is_new_for_account", "NEW_COUNTERPARTY"),
          Map.entry("counterparty_account_age_days", "NEW_COUNTERPARTY_ACCOUNT"),
          Map.entry("counterparty_unique_senders_24h", "MULE_PATTERN"),
          Map.entry("counterparty_confirmed_fraud_90d", "COUNTERPARTY_FRAUD_HISTORY"),
          Map.entry("tx_count_to_counterparty_30d", "COUNTERPARTY_PATTERN"),
          Map.entry("channel", "CHANNEL_RISK"),
          Map.entry("device_is_new_for_account", "NEW_DEVICE"),
          Map.entry("accounts_per_device_7d", "SHARED_DEVICE"),
          Map.entry("device_changes_24h", "DEVICE_CHANGES"),
          Map.entry("device_age_days", "NEW_DEVICE"),
          Map.entry("account_age_days", "NEW_ACCOUNT"),
          Map.entry("kyc_tier", "LOW_KYC_TIER"),
          Map.entry("days_since_sim_swap", "RECENT_SIM_SWAP"),
          Map.entry("dormancy_reactivation_flag", "DORMANT_REACTIVATION"),
          Map.entry("agent_float_utilisation_ratio", "AGENT_RISK"),
          Map.entry("agent_cashout_count_1h", "AGENT_RISK"),
          Map.entry("agent_unique_customers_1h", "AGENT_RISK"),
          Map.entry("agent_distance_from_registered_km", "AGENT_RISK"),
          Map.entry("corridor_class", "CROSS_BORDER"),
          Map.entry("synthetic_identity_score", "SYNTHETIC_IDENTITY"));

  private ReasonCodes() {}

  /**
   * The reason code for a feature.
   *
   * @param feature registered feature name
   * @return its group code, or {@link #MODEL_RISK_SCORE} for an unknown name
   */
  public static String forFeature(String feature) {
    return BY_FEATURE.getOrDefault(feature, MODEL_RISK_SCORE);
  }

  /**
   * The number of features with a reason code.
   *
   * @return 44
   */
  public static int mappedFeatureCount() {
    return BY_FEATURE.size();
  }

  /**
   * Model reasons: risk-increasing contributions by descending contribution, grouped and
   * de-duplicated.
   *
   * @param contributions the scorer's top contributions
   * @return distinct codes, most important first
   */
  public static List<String> fromContributions(List<FeatureContribution> contributions) {
    List<FeatureContribution> sorted = new ArrayList<>(contributions);
    sorted.sort(Comparator.comparingDouble((FeatureContribution c) -> -Math.abs(c.shap())));
    Set<String> codes = new LinkedHashSet<>();
    for (FeatureContribution contribution : sorted) {
      if (contribution.increasesRisk()) {
        codes.add(forFeature(contribution.feature()));
      }
    }
    return List.copyOf(codes);
  }

  /**
   * Merges reason lists in priority order, removes duplicates and keeps at most three.
   *
   * @param lists lists in priority order
   * @return at most {@link #MAX_REASONS} codes
   */
  @SafeVarargs
  public static List<String> merge(List<String>... lists) {
    Set<String> codes = new LinkedHashSet<>();
    for (List<String> list : lists) {
      for (String code : list) {
        if (codes.size() == MAX_REASONS) {
          return List.copyOf(codes);
        }
        codes.add(code);
      }
    }
    return List.copyOf(codes);
  }
}
