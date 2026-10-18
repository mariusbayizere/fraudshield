package io.github.mariusbayizere.fraudshield.common.config;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Thresholds for one channel. Tiers are half-open: HIGH at or above {@code high}, MEDIUM at or
 * above {@code medium} and below {@code high}, LOW otherwise (E.6).
 *
 * @param medium lowest calibrated probability that is MEDIUM
 * @param high lowest calibrated probability that is HIGH
 * @param timeoutPolicy what happens when a MEDIUM hold times out
 */
public record ChannelThreshold(
    BigDecimal medium, BigDecimal high, MediumTimeoutPolicy timeoutPolicy) {

  /** Validates that {@code 0 <= medium < high <= 1}. */
  public ChannelThreshold {
    Objects.requireNonNull(medium, "medium");
    Objects.requireNonNull(high, "high");
    Objects.requireNonNull(timeoutPolicy, "timeoutPolicy");
    if (medium.signum() < 0 || high.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("thresholds must be probabilities between 0 and 1");
    }
    if (medium.compareTo(high) >= 0) {
      throw new IllegalArgumentException("medium threshold must be below the high threshold");
    }
  }

  /**
   * Creates a threshold from decimal strings.
   *
   * @param medium medium threshold, for example {@code "0.60"}
   * @param high high threshold, for example {@code "0.85"}
   * @param timeoutPolicy MEDIUM timeout policy
   * @return the threshold
   */
  public static ChannelThreshold of(String medium, String high, MediumTimeoutPolicy timeoutPolicy) {
    return new ChannelThreshold(new BigDecimal(medium), new BigDecimal(high), timeoutPolicy);
  }
}
