package io.github.mariusbayizere.fraudshield.decision.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The engine's decision and the side effects it implies (E.6 step 8).
 *
 * @param decision APPROVE, DECLINE or HOLD
 * @param tier the final tier after rules and the circuit breaker
 * @param reasonCodes at most three reasons
 * @param reviewDeadlineAt the hold's deadline; null unless HOLD
 * @param alert the alert queue to publish to, if any
 * @param autoBlock whether an auto-block event is recorded (HIGH; FR-03-01)
 * @param fallback whether the rule-based fallback scored the transaction
 */
public record DecisionOutcome(
    Decision decision,
    RiskTier tier,
    List<String> reasonCodes,
    Instant reviewDeadlineAt,
    Optional<AlertTier> alert,
    boolean autoBlock,
    boolean fallback) {

  /** Enforces the E.6 consistency rules the OpenAPI schema states. */
  public DecisionOutcome {
    Objects.requireNonNull(decision, "decision");
    Objects.requireNonNull(tier, "tier");
    Objects.requireNonNull(alert, "alert");
    reasonCodes = List.copyOf(reasonCodes);
    if (reasonCodes.size() > ReasonCodes.MAX_REASONS) {
      throw new IllegalArgumentException("at most three reason codes");
    }
    if ((decision == Decision.HOLD) != (reviewDeadlineAt != null)) {
      throw new IllegalArgumentException("HOLD, and only HOLD, carries a review deadline");
    }
    if (decision == Decision.HOLD && tier != RiskTier.MEDIUM) {
      throw new IllegalArgumentException("HOLD is the MEDIUM outcome");
    }
    if (decision == Decision.APPROVE && tier != RiskTier.LOW) {
      throw new IllegalArgumentException("only LOW is approved");
    }
    if (tier == RiskTier.HIGH && decision != Decision.DECLINE) {
      throw new IllegalArgumentException("HIGH is always declined");
    }
    if (autoBlock && tier != RiskTier.HIGH) {
      throw new IllegalArgumentException("only HIGH is auto-blocked");
    }
  }
}
