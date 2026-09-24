package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.rules.dsl.TierOverride;

/** Risk tiers in increasing order of risk (E.6). */
public enum RiskTier {
  /** Below the MEDIUM threshold. */
  LOW,
  /** At or above MEDIUM and below HIGH. */
  MEDIUM,
  /** At or above HIGH. */
  HIGH;

  /**
   * The higher of two tiers.
   *
   * @param other the other tier
   * @return the riskier tier
   */
  public RiskTier atLeast(RiskTier other) {
    return compareTo(other) >= 0 ? this : other;
  }

  /**
   * The tier a rule override stands for.
   *
   * @param override a rule's tier override
   * @return the tier
   */
  public static RiskTier of(TierOverride override) {
    return override == TierOverride.HIGH ? HIGH : MEDIUM;
  }
}
