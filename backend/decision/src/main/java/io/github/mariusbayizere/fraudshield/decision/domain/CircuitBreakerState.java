package io.github.mariusbayizere.fraudshield.decision.domain;

import java.time.Instant;

/**
 * The state of one merchant category's circuit breaker.
 *
 * @param open whether transactions to the MCC are forced to at least MEDIUM
 * @param openedAt when it last opened; null when never opened
 * @param lastBreachAt when the threshold was last breached; null when never breached
 */
public record CircuitBreakerState(boolean open, Instant openedAt, Instant lastBreachAt) {

  /** A closed breaker that has never opened. */
  public static final CircuitBreakerState CLOSED = new CircuitBreakerState(false, null, null);

  /** Requires the times an open breaker must have. */
  public CircuitBreakerState {
    if (open && (openedAt == null || lastBreachAt == null)) {
      throw new IllegalArgumentException("an open breaker has opening and breach times");
    }
  }
}
