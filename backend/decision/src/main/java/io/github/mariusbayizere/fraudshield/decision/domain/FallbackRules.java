package io.github.mariusbayizere.fraudshield.decision.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The rule-based fallback that decides while the ML scorer is unavailable (C.4, NFR-REL-01).
 *
 * <p>The fallback sees the transaction and nothing else. The account's behaviour (velocity,
 * novelty, SIM-swap recency) exists only as model features, which the scorer reads from the feature
 * store and returns (ADR 0033); the decision path never computes them, so it cannot hand the
 * fallback a second, drifting definition of them (ADR 0061). The institution's own rules on request
 * fields still apply as always. The fallback therefore only holds large payments for review and
 * never blocks: while the model is down, a MEDIUM hold costs an analyst review, and a block on
 * amount alone would decline legitimate payments with no evidence. A replay job re-scores fallback
 * decisions with the model after recovery (C.4). Versioned; the threshold is configuration, with
 * the default recorded in ADR 0061.
 *
 * @param version rule-set version reported as the model version, for example {@code
 *     fallback-rules-2}
 * @param reviewAmountRwf amount at or above which a transaction is held for review (MEDIUM)
 */
public record FallbackRules(String version, BigDecimal reviewAmountRwf) {

  /** Fallback rule-set version 2 with its default threshold (ADR 0061). */
  public static final FallbackRules DEFAULTS =
      new FallbackRules("fallback-rules-2", new BigDecimal("2000000"));

  /** Validates the threshold. */
  public FallbackRules {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(reviewAmountRwf, "reviewAmountRwf");
    if (reviewAmountRwf.signum() <= 0) {
      throw new IllegalArgumentException("the review amount must be positive");
    }
  }

  /**
   * Scores a transaction without the model.
   *
   * @param transaction the transaction
   * @return a fallback scoring, LOW or MEDIUM
   */
  public Scoring.Fallback score(Transaction transaction) {
    boolean review = transaction.amountRwf().compareTo(reviewAmountRwf) >= 0;
    return new Scoring.Fallback(
        UUID.randomUUID(),
        version,
        review ? RiskTier.MEDIUM : RiskTier.LOW,
        review ? List.of("AMOUNT_ABOVE_NORMAL") : List.of());
  }
}
