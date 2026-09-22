package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThreshold;
import java.math.BigDecimal;

/** Maps a calibrated probability to a tier with half-open intervals (E.6, FR-05-07). */
public final class Tiering {

  private Tiering() {}

  /**
   * The tier of a score. HIGH at or above {@code high}; MEDIUM at or above {@code medium} and below
   * {@code high}; LOW otherwise. Compared exactly in decimal, so 0.85 is HIGH at a 0.85 threshold
   * and 0.8499999 is not, with no gap between the tiers (removing the SRS 0.84–0.85 gap).
   *
   * @param score calibrated probability
   * @param threshold the channel's thresholds
   * @return the tier
   */
  public static RiskTier tierOf(double score, ChannelThreshold threshold) {
    BigDecimal value = BigDecimal.valueOf(score);
    if (value.compareTo(threshold.high()) >= 0) {
      return RiskTier.HIGH;
    }
    if (value.compareTo(threshold.medium()) >= 0) {
      return RiskTier.MEDIUM;
    }
    return RiskTier.LOW;
  }
}
