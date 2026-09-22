package io.github.mariusbayizere.fraudshield.decision.application.port;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThresholds;
import io.github.mariusbayizere.fraudshield.common.config.CircuitBreakerSettings;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import java.util.UUID;

/**
 * Versioned risk configuration, cached in memory and replaced atomically by version (E.6: effective
 * within 60 s, target 5 s).
 */
public interface ConfigurationPort {

  /**
   * Thresholds with their version.
   *
   * @param version {@code risk_threshold_versions.version}
   * @param thresholds per-channel thresholds and timeout policies
   */
  record Thresholds(long version, ChannelThresholds thresholds) {}

  /**
   * Circuit-breaker settings with their version.
   *
   * @param version {@code mcc_circuit_breaker_settings_versions.version}
   * @param settings the settings
   */
  record BreakerSettings(long version, CircuitBreakerSettings settings) {}

  /**
   * The institution's thresholds in force.
   *
   * @param institutionId institution
   * @return thresholds
   */
  Thresholds thresholds(UUID institutionId);

  /**
   * The institution's enabled rules.
   *
   * @param institutionId institution
   * @return compiled rules
   */
  RuleSet rules(UUID institutionId);

  /**
   * The institution's circuit-breaker settings.
   *
   * @param institutionId institution
   * @return settings
   */
  BreakerSettings breakerSettings(UUID institutionId);
}
