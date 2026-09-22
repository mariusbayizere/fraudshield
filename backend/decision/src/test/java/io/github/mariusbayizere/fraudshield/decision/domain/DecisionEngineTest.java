package io.github.mariusbayizere.fraudshield.decision.domain;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.threshold;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.ChannelThreshold;
import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.testing.Properties;
import io.github.mariusbayizere.fraudshield.rules.dsl.CompiledRule;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import io.github.mariusbayizere.fraudshield.rules.dsl.TierOverride;
import io.github.mariusbayizere.fraudshield.rules.dsl.Truth;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** E.6: tiers, precedence, holds and anomaly routing. */
@Tag("FR-03-01")
@Tag("FR-03-02")
@Tag("FR-03-03")
@Tag("FR-05-07")
@Tag("D-02")
@Tag("D-10")
@Tag("D-17")
class DecisionEngineTest {

  private static final Duration REVIEW = Duration.ofSeconds(30);
  private static final RuleSet.Evaluation NO_RULES = RuleSet.EMPTY.evaluate(field -> null);

  private static Scoring.Model model(double score, double anomaly) {
    return new Scoring.Model(
        UUID.randomUUID(),
        "fs-ensemble-test",
        score,
        anomaly,
        score >= 0.60
            ? List.of(
                new FeatureContribution("tx_count_60s", 1.2, true),
                new FeatureContribution("counterparty_is_new_for_account", 0.8, true),
                new FeatureContribution("kyc_tier", -0.3, false))
            : List.of(),
        Map.of());
  }

  private static DecisionInputs inputs(
      Scoring scoring, boolean frozen, boolean breaker, RuleSet.Evaluation rules) {
    return new DecisionInputs(
        transaction("15000"), scoring, threshold(), frozen, breaker, rules, 0.7, NOW, REVIEW);
  }

  private static DecisionOutcome decide(double score) {
    return DecisionEngine.decide(inputs(model(score, 0.1), false, false, NO_RULES));
  }

  private static RuleSet.Evaluation ruleRaisingTo(TierOverride override) {
    return new RuleSet(
            1, List.of(new CompiledRule(UUID.randomUUID(), 1, override, s -> Truth.TRUE)))
        .evaluate(field -> null);
  }

  @ParameterizedTest(name = "score {0} -> {1} {2}")
  @CsvSource({
    "0.0, LOW, APPROVE",
    "0.5999999, LOW, APPROVE",
    "0.60, MEDIUM, HOLD",
    "0.8499999, MEDIUM, HOLD",
    "0.85, HIGH, DECLINE",
    "1.0, HIGH, DECLINE"
  })
  void tiersAreHalfOpenWithNoGap(double score, RiskTier tier, Decision decision) {
    DecisionOutcome outcome = decide(score);
    assertThat(outcome.tier()).isEqualTo(tier);
    assertThat(outcome.decision()).isEqualTo(decision);
  }

  @Test
  void highIsDeclinedAndAutoBlockedWithModelReasons() {
    DecisionOutcome outcome = decide(0.93);
    assertThat(outcome.autoBlock()).isTrue();
    assertThat(outcome.alert()).contains(AlertTier.HIGH);
    assertThat(outcome.reasonCodes()).containsExactly("VELOCITY_SPIKE", "NEW_COUNTERPARTY");
    assertThat(outcome.reviewDeadlineAt()).isNull();
  }

  @Test
  void mediumIsHeldUntilThirtySecondsAfterTheDecision() {
    DecisionOutcome outcome = decide(0.7);
    assertThat(outcome.reviewDeadlineAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(outcome.alert()).contains(AlertTier.MEDIUM);
    assertThat(outcome.autoBlock()).isFalse();
  }

  @Test
  void lowIsApprovedWithoutReasonsOrAlert() {
    DecisionOutcome outcome = decide(0.2);
    assertThat(outcome.reasonCodes()).isEmpty();
    assertThat(outcome.alert()).isEmpty();
  }

  @Test
  void frozenAccountsAreDeclinedWhateverTheScore() {
    DecisionOutcome outcome = DecisionEngine.decide(inputs(model(0.1, 0.1), true, false, NO_RULES));
    assertThat(outcome.decision()).isEqualTo(Decision.DECLINE);
    assertThat(outcome.tier()).isEqualTo(RiskTier.LOW);
    assertThat(outcome.reasonCodes()).containsExactly(ReasonCodes.ACCOUNT_FROZEN);
    assertThat(outcome.autoBlock()).isFalse();
    assertThat(outcome.alert()).isEmpty();
  }

  @Test
  void rulesRaiseButNeverLower() {
    DecisionOutcome raised =
        DecisionEngine.decide(
            inputs(model(0.1, 0.1), false, false, ruleRaisingTo(TierOverride.HIGH)));
    assertThat(raised.tier()).isEqualTo(RiskTier.HIGH);
    assertThat(raised.reasonCodes()).startsWith(ReasonCodes.CUSTOM_RULE);

    DecisionOutcome notLowered =
        DecisionEngine.decide(
            inputs(model(0.9, 0.1), false, false, ruleRaisingTo(TierOverride.MEDIUM)));
    assertThat(notLowered.tier()).isEqualTo(RiskTier.HIGH);
    assertThat(notLowered.reasonCodes()).doesNotContain(ReasonCodes.CUSTOM_RULE);
  }

  @Test
  void openCircuitBreakersMakeLowAtLeastMediumOnly() {
    DecisionOutcome low = DecisionEngine.decide(inputs(model(0.1, 0.1), false, true, NO_RULES));
    assertThat(low.decision()).isEqualTo(Decision.HOLD);
    assertThat(low.reasonCodes())
        .containsExactly(ReasonCodes.MCC_CIRCUIT_BREAKER, ReasonCodes.MODEL_RISK_SCORE);
    DecisionOutcome high = DecisionEngine.decide(inputs(model(0.9, 0.1), false, true, NO_RULES));
    assertThat(high.tier()).isEqualTo(RiskTier.HIGH);
  }

  @Test
  void anomalyOnlyTransactionsAreApprovedAndQueuedWithoutHold() {
    DecisionOutcome anomalous =
        DecisionEngine.decide(inputs(model(0.2, 0.7), false, false, NO_RULES));
    assertThat(anomalous.decision()).isEqualTo(Decision.APPROVE);
    assertThat(anomalous.alert()).contains(AlertTier.ANOMALY);
    assertThat(anomalous.reviewDeadlineAt()).isNull();
    assertThat(DecisionEngine.decide(inputs(model(0.2, 0.6999), false, false, NO_RULES)).alert())
        .isEmpty();
    // A MEDIUM transaction is already held; it does not also go to the anomaly queue.
    assertThat(DecisionEngine.decide(inputs(model(0.7, 0.99), false, false, NO_RULES)).alert())
        .contains(AlertTier.MEDIUM);
  }

  @Test
  void theFallbackDecidesWithItsOwnTierAndSaysSo() {
    Scoring.Fallback fallback =
        new Scoring.Fallback(
            UUID.randomUUID(), "fallback-rules-2", RiskTier.MEDIUM, List.of("AMOUNT_ABOVE_NORMAL"));
    DecisionOutcome outcome = DecisionEngine.decide(inputs(fallback, false, false, NO_RULES));
    assertThat(outcome.fallback()).isTrue();
    assertThat(outcome.decision()).isEqualTo(Decision.HOLD);
    assertThat(outcome.reasonCodes())
        .containsExactly(ReasonCodes.ML_UNAVAILABLE, "AMOUNT_ABOVE_NORMAL");
    Scoring.Fallback low =
        new Scoring.Fallback(UUID.randomUUID(), "fallback-rules-2", RiskTier.LOW, List.of());
    DecisionOutcome approved = DecisionEngine.decide(inputs(low, false, false, NO_RULES));
    assertThat(approved.reasonCodes()).containsExactly(ReasonCodes.ML_UNAVAILABLE);
    assertThat(approved.alert()).isEmpty();
  }

  @Test
  void perChannelThresholdsApply() {
    ChannelThreshold strict =
        ChannelThreshold.of("0.30", "0.50", MediumTimeoutPolicy.DECLINE_AND_VERIFY);
    DecisionOutcome outcome =
        DecisionEngine.decide(
            new DecisionInputs(
                transaction("100"),
                model(0.5, 0.1),
                strict,
                false,
                false,
                NO_RULES,
                0.7,
                NOW,
                REVIEW));
    assertThat(outcome.tier()).isEqualTo(RiskTier.HIGH);
  }

  @Test
  void inputsAndOutcomesRejectInconsistentValues() {
    assertThatThrownBy(
            () ->
                new DecisionInputs(
                    transaction("1"),
                    model(0.1, 0.1),
                    threshold(),
                    false,
                    false,
                    NO_RULES,
                    0,
                    NOW,
                    REVIEW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionInputs(
                    transaction("1"),
                    model(0.1, 0.1),
                    threshold(),
                    false,
                    false,
                    NO_RULES,
                    0.7,
                    NOW,
                    Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionOutcome(
                    Decision.HOLD,
                    RiskTier.MEDIUM,
                    List.of(),
                    null,
                    Optional.empty(),
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionOutcome(
                    Decision.HOLD, RiskTier.HIGH, List.of(), NOW, Optional.empty(), false, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionOutcome(
                    Decision.APPROVE,
                    RiskTier.MEDIUM,
                    List.of(),
                    null,
                    Optional.empty(),
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionOutcome(
                    Decision.DECLINE,
                    RiskTier.MEDIUM,
                    List.of(),
                    null,
                    Optional.empty(),
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionOutcome(
                    Decision.APPROVE,
                    RiskTier.HIGH,
                    List.of(),
                    null,
                    Optional.empty(),
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new DecisionOutcome(
                    Decision.DECLINE,
                    RiskTier.HIGH,
                    List.of("A_A", "B_B", "C_C", "D_D"),
                    null,
                    Optional.empty(),
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Scoring.Model(UUID.randomUUID(), "m", 1.2, 0.1, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Scoring.Model(UUID.randomUUID(), "m", Double.NaN, 0.1, List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void propertyTierIsMonotoneInScoreAndOutcomesAreConsistent() {
    Properties.forAll(
        "tier monotone in score; E.6 consistency",
        5000,
        random -> {
          BigDecimal medium = BigDecimal.valueOf(random.nextInt(1, 9000), 4);
          BigDecimal high =
              medium.add(
                  BigDecimal.valueOf(
                      random.nextInt(1, 10000 - medium.unscaledValue().intValue() + 1), 4));
          double a = random.nextDouble();
          double b = random.nextDouble();
          return new Object[] {
            new ChannelThreshold(
                medium, high.min(BigDecimal.ONE), MediumTimeoutPolicy.RELEASE_WITH_TIMEOUT_LABEL),
            Math.min(a, b),
            Math.max(a, b),
            random.nextBoolean(),
            random.nextBoolean(),
            random.nextInt(3)
          };
        },
        in -> {
          ChannelThreshold t = (ChannelThreshold) in[0];
          assertThat(Tiering.tierOf((double) in[1], t))
              .isLessThanOrEqualTo(Tiering.tierOf((double) in[2], t));
          RuleSet.Evaluation rules =
              switch ((int) in[5]) {
                case 0 -> NO_RULES;
                case 1 -> ruleRaisingTo(TierOverride.MEDIUM);
                default -> ruleRaisingTo(TierOverride.HIGH);
              };
          double score = (double) in[2];
          DecisionOutcome outcome =
              DecisionEngine.decide(
                  new DecisionInputs(
                      transaction("10"),
                      model(score, 0.1),
                      t,
                      (boolean) in[3],
                      (boolean) in[4],
                      rules,
                      0.7,
                      NOW,
                      REVIEW));
          RiskTier ml = Tiering.tierOf(score, t);
          // Rules and the breaker never lower the ML tier (E.6 step 5).
          assertThat(outcome.tier()).isGreaterThanOrEqualTo(ml);
          if ((boolean) in[3]) {
            assertThat(outcome.decision()).isEqualTo(Decision.DECLINE);
            assertThat(outcome.reasonCodes()).first().isEqualTo(ReasonCodes.ACCOUNT_FROZEN);
          }
          assertThat(outcome.reasonCodes()).hasSizeLessThanOrEqualTo(3).doesNotHaveDuplicates();
        });
  }
}
