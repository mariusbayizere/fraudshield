package io.github.mariusbayizere.fraudshield.common.config;

import java.util.Optional;

/** A complete, validated set of settings of one {@link ConfigKind}. */
public sealed interface ConfigSettings permits ChannelThresholds, CircuitBreakerSettings {

  /**
   * The kind of configuration these settings belong to.
   *
   * @return the kind
   */
  ConfigKind kind();

  /**
   * Classifies replacing {@code previous} with these settings.
   *
   * @param previous settings of the same kind currently in effect
   * @return the direction, or empty when nothing changes
   * @throws IllegalArgumentException if {@code previous} is of another kind
   */
  Optional<ChangeDirection> directionFrom(ConfigSettings previous);
}
