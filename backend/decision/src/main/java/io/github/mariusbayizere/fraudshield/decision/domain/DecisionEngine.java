package io.github.mariusbayizere.fraudshield.decision.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The risk decision (E.6), as a pure function of {@link DecisionInputs}.
 *
 * <p>Order of evaluation: a frozen account is declined outright; otherwise the ML score (or the
 * fallback's tier) gives the tier from the channel's half-open thresholds; custom rules may only
 * raise it; an open MCC circuit breaker makes it at least MEDIUM; and a LOW transaction whose
 * anomaly score reaches the review threshold goes to the non-holding ANOMALY_REVIEW queue (D-10).
 * HIGH declines and auto-blocks, MEDIUM holds until the review deadline, LOW approves.
 */
public final class DecisionEngine {

  private DecisionEngine() {}

  /**
   * Decides.
   *
   * @param in everything the decision depends on
   * @return the decision and its side effects
   */
  public static DecisionOutcome decide(DecisionInputs in) {
    final boolean fallback = in.scoring() instanceof Scoring.Fallback;
    List<String> scoreReasons;
    RiskTier tier;
    switch (in.scoring()) {
      case Scoring.Model model -> {
        tier = Tiering.tierOf(model.ensembleScore(), in.threshold());
        scoreReasons = ReasonCodes.fromContributions(model.topContributions());
      }
      case Scoring.Fallback rules -> {
        tier = rules.tier();
        scoreReasons = rules.reasonCodes();
      }
    }

    List<String> raised = new ArrayList<>();
    if (in.rules().override().isPresent()) {
      RiskTier ruled = tier.atLeast(RiskTier.of(in.rules().override().get()));
      if (ruled != tier) {
        raised.add(ReasonCodes.CUSTOM_RULE);
        tier = ruled;
      }
    }
    if (in.mccCircuitOpen() && tier == RiskTier.LOW) {
      raised.add(ReasonCodes.MCC_CIRCUIT_BREAKER);
      tier = RiskTier.MEDIUM;
    }

    List<String> leading = fallback ? List.of(ReasonCodes.ML_UNAVAILABLE) : List.of();
    if (in.accountFrozen()) {
      return new DecisionOutcome(
          Decision.DECLINE,
          tier,
          ReasonCodes.merge(List.of(ReasonCodes.ACCOUNT_FROZEN), leading),
          null,
          Optional.empty(),
          false,
          fallback);
    }
    List<String> modelReasons =
        scoreReasons.isEmpty() && !fallback ? List.of(ReasonCodes.MODEL_RISK_SCORE) : scoreReasons;
    return switch (tier) {
      case HIGH ->
          new DecisionOutcome(
              Decision.DECLINE,
              tier,
              ReasonCodes.merge(leading, raised, modelReasons),
              null,
              Optional.of(AlertTier.HIGH),
              true,
              fallback);
      case MEDIUM ->
          new DecisionOutcome(
              Decision.HOLD,
              tier,
              ReasonCodes.merge(leading, raised, modelReasons),
              in.decidedAt().plus(in.reviewWindow()),
              Optional.of(AlertTier.MEDIUM),
              false,
              fallback);
      case LOW ->
          new DecisionOutcome(
              Decision.APPROVE, tier, leading, null, anomalyAlert(in), false, fallback);
    };
  }

  private static Optional<AlertTier> anomalyAlert(DecisionInputs in) {
    if (in.scoring() instanceof Scoring.Model model
        && model.anomalyScore() >= in.anomalyReviewThreshold()) {
      return Optional.of(AlertTier.ANOMALY);
    }
    return Optional.empty();
  }
}
