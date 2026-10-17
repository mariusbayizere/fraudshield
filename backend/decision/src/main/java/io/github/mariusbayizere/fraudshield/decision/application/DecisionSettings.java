package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.domain.FallbackRules;
import java.time.Duration;
import java.util.Objects;

/**
 * Decision-path configuration, validated at startup (H.1: fail fast on invalid configuration).
 *
 * @param reviewWindow how long a hold waits for review (FR-03-02: 30 seconds)
 * @param anomalyReviewThreshold anomaly percentile for the ANOMALY_REVIEW queue (D-06: 0.995 in
 *     production, 0.7 in the test profile)
 * @param fallbackRules the rule-based fallback (C.4)
 */
public record DecisionSettings(
    Duration reviewWindow, double anomalyReviewThreshold, FallbackRules fallbackRules) {

  /** SRS and D-06 production defaults. */
  public static final DecisionSettings DEFAULTS =
      new DecisionSettings(Duration.ofSeconds(30), 0.995, FallbackRules.DEFAULTS);

  /** Validates. */
  public DecisionSettings {
    Objects.requireNonNull(reviewWindow, "reviewWindow");
    Objects.requireNonNull(fallbackRules, "fallbackRules");
    if (reviewWindow.isNegative() || reviewWindow.isZero()) {
      throw new IllegalArgumentException("review window must be positive");
    }
    if (!(anomalyReviewThreshold > 0 && anomalyReviewThreshold <= 1)) {
      throw new IllegalArgumentException("anomaly review threshold must be in (0, 1]");
    }
  }
}
