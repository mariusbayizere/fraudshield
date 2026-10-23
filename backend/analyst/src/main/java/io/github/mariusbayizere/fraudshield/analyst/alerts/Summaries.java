package io.github.mariusbayizere.fraudshield.analyst.alerts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The key and parameters of an alert's plain-language summary (D-45). The text behind each key is
 * written and reviewed with the feature templates (PB-132); the server only chooses the key, and
 * chooses it deterministically from what the score holds (ADR 0112):
 *
 * <ul>
 *   <li>{@code summary.rule_fallback} when no model scored the transaction;
 *   <li>{@code summary.anomaly} for an Isolation-Forest-only alert;
 *   <li>otherwise {@code summary.<feature>} for the strongest contribution, or {@code
 *       summary.model} when the score kept none.
 * </ul>
 */
final class Summaries {

  private Summaries() {}

  static String key(String tier, boolean fallback, List<Map<String, Object>> reasons) {
    if (fallback) {
      return "summary.rule_fallback";
    }
    if ("ANOMALY".equals(tier)) {
      return "summary.anomaly";
    }
    return reasons.isEmpty() ? "summary.model" : "summary." + reasons.getFirst().get("feature");
  }

  static Map<String, Object> parameters(
      double probability, List<Map<String, Object>> reasons, Double anomalyScore) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("fraud_probability", probability);
    if (!reasons.isEmpty()) {
      parameters.put("top_feature", String.valueOf(reasons.getFirst().get("feature")));
    }
    if (anomalyScore != null) {
      parameters.put("anomaly_score", anomalyScore);
    }
    return parameters;
  }
}
