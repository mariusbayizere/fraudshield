package io.github.mariusbayizere.fraudshield.decision.domain;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThreshold;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Everything the decision engine reads, gathered before it runs so that it is a pure function.
 *
 * @param transaction the transaction
 * @param scoring model score or fallback
 * @param threshold the channel's thresholds in force
 * @param accountFrozen whether the account is frozen (E.6 step 2)
 * @param mccCircuitOpen whether the merchant category's circuit breaker is open (step 6)
 * @param rules the custom rules' evaluation (step 5)
 * @param anomalyReviewThreshold anomaly percentile at or above which a LOW transaction goes to the
 *     ANOMALY_REVIEW queue (D-06: 0.7 in the test profile, 0.995 by default in production)
 * @param decidedAt decision time
 * @param reviewWindow how long a hold waits for review (FR-03-02: 30 seconds)
 */
public record DecisionInputs(
    Transaction transaction,
    Scoring scoring,
    ChannelThreshold threshold,
    boolean accountFrozen,
    boolean mccCircuitOpen,
    RuleSet.Evaluation rules,
    double anomalyReviewThreshold,
    Instant decidedAt,
    Duration reviewWindow) {

  /** Requires every component. */
  public DecisionInputs {
    Objects.requireNonNull(transaction, "transaction");
    Objects.requireNonNull(scoring, "scoring");
    Objects.requireNonNull(threshold, "threshold");
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(decidedAt, "decidedAt");
    Objects.requireNonNull(reviewWindow, "reviewWindow");
    if (!(anomalyReviewThreshold > 0 && anomalyReviewThreshold <= 1)) {
      throw new IllegalArgumentException("the anomaly review threshold is in (0, 1]");
    }
    if (reviewWindow.isNegative() || reviewWindow.isZero()) {
      throw new IllegalArgumentException("the review window must be positive");
    }
  }
}
