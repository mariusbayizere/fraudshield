package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.common.config.CircuitBreakerSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * MCC circuit breaker (FR-03-07, D-18). Opens when the fraud rate over the rolling window
 * <em>exceeds</em> the threshold <em>and</em> the window holds at least the minimum volume; closes
 * after a clean reset period with no breach. Fraud = auto-blocked HIGH plus analyst-confirmed
 * fraud; the denominator is every scored transaction for the MCC.
 */
public final class MccCircuitBreaker {

  private MccCircuitBreaker() {}

  /**
   * Transaction and fraud counts in the rolling window.
   *
   * @param transactions scored transactions
   * @param fraud auto-blocked HIGH plus analyst-confirmed fraud
   */
  public record WindowCounts(long transactions, long fraud) {
    /** Requires {@code 0 <= fraud <= transactions}. */
    public WindowCounts {
      if (transactions < 0 || fraud < 0 || fraud > transactions) {
        throw new IllegalArgumentException("0 <= fraud <= transactions");
      }
    }

    /**
     * The fraud rate, rounded to four decimals for the event record.
     *
     * @return the rate, 0 for an empty window
     */
    public BigDecimal rate() {
      return transactions == 0
          ? BigDecimal.ZERO.setScale(4)
          : BigDecimal.valueOf(fraud)
              .divide(BigDecimal.valueOf(transactions), 4, RoundingMode.HALF_EVEN);
    }
  }

  /** A state change to record in {@code mcc_circuit_breaker_events}. */
  public enum Change {
    /** The breaker opened. */
    OPENED,
    /** The breaker closed. */
    CLOSED
  }

  /**
   * The result of one evaluation.
   *
   * @param state the new state
   * @param change the change to record, if any
   */
  public record Evaluation(CircuitBreakerState state, Optional<Change> change) {}

  /**
   * Whether the window breaches the threshold. Exact: {@code fraud / transactions > threshold} is
   * evaluated as {@code fraud > threshold × transactions}, so exactly 5.0% does not breach and one
   * more fraud does.
   *
   * @param counts window counts
   * @param settings the institution's settings
   * @return true on a breach
   */
  public static boolean breaches(WindowCounts counts, CircuitBreakerSettings settings) {
    return counts.transactions() >= settings.minimumTransactions()
        && BigDecimal.valueOf(counts.fraud())
                .compareTo(
                    settings
                        .fraudRateThreshold()
                        .multiply(BigDecimal.valueOf(counts.transactions())))
            > 0;
  }

  /**
   * Evaluates the breaker.
   *
   * @param counts window counts now
   * @param settings the institution's settings
   * @param current the current state
   * @param now evaluation time
   * @return the new state and any change
   */
  public static Evaluation evaluate(
      WindowCounts counts,
      CircuitBreakerSettings settings,
      CircuitBreakerState current,
      Instant now) {
    if (breaches(counts, settings)) {
      if (current.open()) {
        return new Evaluation(
            new CircuitBreakerState(true, current.openedAt(), now), Optional.empty());
      }
      return new Evaluation(new CircuitBreakerState(true, now, now), Optional.of(Change.OPENED));
    }
    if (current.open() && !now.isBefore(current.lastBreachAt().plus(settings.cleanReset()))) {
      return new Evaluation(
          new CircuitBreakerState(false, current.openedAt(), current.lastBreachAt()),
          Optional.of(Change.CLOSED));
    }
    return new Evaluation(current, Optional.empty());
  }
}
