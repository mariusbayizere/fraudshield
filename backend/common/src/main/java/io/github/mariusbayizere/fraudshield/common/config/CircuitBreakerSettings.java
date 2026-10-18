package io.github.mariusbayizere.fraudshield.common.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * When an MCC circuit breaker opens and closes (FR-03-07, D-18).
 *
 * <p>The breaker opens when the fraud rate over {@code window} exceeds {@code fraudRateThreshold}
 * with at least {@code minimumTransactions} in the window, and closes after {@code cleanReset}
 * without a breach. Tightening: a lower rate threshold, a lower minimum volume or a longer clean
 * reset. Loosening: the opposite. Changing the window is always treated as loosening, because a
 * different window can let a burst go unnoticed and needs a second approver.
 *
 * @param fraudRateThreshold fraud rate above which the breaker opens, between 0 and 1
 * @param window rolling window over which the rate is measured
 * @param minimumTransactions minimum transactions in the window before the breaker may open
 * @param cleanReset time without a breach before the breaker closes
 */
public record CircuitBreakerSettings(
    BigDecimal fraudRateThreshold, Duration window, int minimumTransactions, Duration cleanReset)
    implements ConfigSettings {

  /** Validates ranges. */
  public CircuitBreakerSettings {
    Objects.requireNonNull(fraudRateThreshold, "fraudRateThreshold");
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(cleanReset, "cleanReset");
    if (fraudRateThreshold.signum() <= 0 || fraudRateThreshold.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException("fraud rate threshold must be between 0 and 1");
    }
    if (window.isNegative() || window.isZero() || cleanReset.isNegative() || cleanReset.isZero()) {
      throw new IllegalArgumentException("window and clean reset must be positive");
    }
    if (minimumTransactions < 1) {
      throw new IllegalArgumentException("minimum transactions must be at least 1");
    }
  }

  @Override
  public ConfigKind kind() {
    return ConfigKind.MCC_CIRCUIT_BREAKER;
  }

  @Override
  public Optional<ChangeDirection> directionFrom(ConfigSettings previous) {
    if (!(previous instanceof CircuitBreakerSettings before)) {
      throw new IllegalArgumentException(
          "cannot compare circuit-breaker settings with " + previous);
    }
    int rate = fraudRateThreshold.compareTo(before.fraudRateThreshold);
    int volume = Integer.compare(minimumTransactions, before.minimumTransactions);
    int reset = cleanReset.compareTo(before.cleanReset);
    boolean loosens = rate > 0 || volume > 0 || reset < 0 || !window.equals(before.window);
    boolean tightens = rate < 0 || volume < 0 || reset > 0;
    if (loosens) {
      return Optional.of(ChangeDirection.LOOSENING);
    }
    return tightens ? Optional.of(ChangeDirection.TIGHTENING) : Optional.empty();
  }
}
