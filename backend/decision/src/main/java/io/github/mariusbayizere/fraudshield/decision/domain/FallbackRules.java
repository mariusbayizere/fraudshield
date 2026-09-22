package io.github.mariusbayizere.fraudshield.decision.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The rule-based fallback that decides while the ML scorer is unavailable (C.4, NFR-REL-01).
 *
 * <p>A status-quo rule engine in the sense of E.5's baseline: amount thresholds, velocity bursts
 * and the SIM-swap takeover pattern, over the same Redis account state the scorer would have
 * received. It leans towards holding: while the model is down, a MEDIUM hold costs an analyst
 * review, while a wrong approval costs the customer's money. Versioned; a replay job re-scores
 * fallback decisions with the model after recovery (C.4). Thresholds are configuration, with the
 * defaults below recorded in ADR 0061.
 *
 * @param version rule-set version reported as the model version, for example {@code
 *     fallback-rules-1}
 * @param burstCount60s transactions in 60 seconds that make a burst (HIGH)
 * @param hourlyCount transactions in one hour that warrant review (MEDIUM)
 * @param reviewAmountRwf amount at or above which a transaction is reviewed (MEDIUM)
 * @param newCounterpartyAmountRwf amount to a new counterparty that is reviewed (MEDIUM)
 * @param blockAmountRwf amount to a new counterparty that is declined (HIGH)
 * @param simSwapDays a SIM swap more recent than this, with a new counterparty and at least the
 *     new-counterparty amount, is the takeover pattern (HIGH)
 */
public record FallbackRules(
    String version,
    int burstCount60s,
    int hourlyCount,
    BigDecimal reviewAmountRwf,
    BigDecimal newCounterpartyAmountRwf,
    BigDecimal blockAmountRwf,
    int simSwapDays) {

  /** Fallback rule-set version 1 with its default thresholds (ADR 0061). */
  public static final FallbackRules DEFAULTS =
      new FallbackRules(
          "fallback-rules-1",
          5,
          10,
          new BigDecimal("2000000"),
          new BigDecimal("500000"),
          new BigDecimal("10000000"),
          7);

  /** Validates the thresholds. */
  public FallbackRules {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(reviewAmountRwf, "reviewAmountRwf");
    Objects.requireNonNull(newCounterpartyAmountRwf, "newCounterpartyAmountRwf");
    Objects.requireNonNull(blockAmountRwf, "blockAmountRwf");
    if (burstCount60s < 1 || hourlyCount < 1 || simSwapDays < 1) {
      throw new IllegalArgumentException("counts and days must be positive");
    }
    if (newCounterpartyAmountRwf.compareTo(blockAmountRwf) >= 0) {
      throw new IllegalArgumentException("the review amount must be below the block amount");
    }
  }

  /**
   * Scores a transaction without the model.
   *
   * @param transaction the transaction
   * @param history its account state
   * @return a fallback scoring
   */
  public Scoring.Fallback score(Transaction transaction, AccountHistory history) {
    BigDecimal amount = transaction.amountRwf();
    boolean newCounterparty = history.counterpartyNewForAccount();
    List<String> high = new ArrayList<>();
    // The history excludes the scored transaction, so it is added here.
    if (history.txCount60s() + 1 >= burstCount60s) {
      high.add("VELOCITY_SPIKE");
    }
    if (newCounterparty && amount.compareTo(blockAmountRwf) >= 0) {
      high.add("AMOUNT_ABOVE_NORMAL");
    }
    if (history.daysSinceSimSwap() != null
        && history.daysSinceSimSwap() < simSwapDays
        && newCounterparty
        && amount.compareTo(newCounterpartyAmountRwf) >= 0) {
      high.add("RECENT_SIM_SWAP");
    }
    if (!high.isEmpty()) {
      return result(RiskTier.HIGH, high);
    }
    List<String> medium = new ArrayList<>();
    if (history.txCount1h() + 1 >= hourlyCount) {
      medium.add("VELOCITY_SPIKE");
    }
    if (amount.compareTo(reviewAmountRwf) >= 0) {
      medium.add("AMOUNT_ABOVE_NORMAL");
    }
    if (newCounterparty && amount.compareTo(newCounterpartyAmountRwf) >= 0) {
      medium.add("NEW_COUNTERPARTY");
    }
    if (history.device() != null
        && history.device().newForAccount()
        && amount.compareTo(newCounterpartyAmountRwf) >= 0) {
      medium.add("NEW_DEVICE");
    }
    return result(medium.isEmpty() ? RiskTier.LOW : RiskTier.MEDIUM, medium);
  }

  private Scoring.Fallback result(RiskTier tier, List<String> reasons) {
    return new Scoring.Fallback(UUID.randomUUID(), version, tier, reasons);
  }
}
