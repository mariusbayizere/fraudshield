package io.github.mariusbayizere.fraudshield.decision.domain;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** C.4: the versioned rule-based fallback. */
@Tag("NFR-REL-01")
class FallbackRulesTest {

  private static final FallbackRules RULES = FallbackRules.DEFAULTS;

  private static AccountHistory history(
      int in60s,
      int in1h,
      boolean newCounterparty,
      Integer simSwapDays,
      AccountHistory.DeviceHistory device) {
    return new AccountHistory(
        in60s,
        in1h,
        in1h,
        in1h,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        0,
        Double.NaN,
        null,
        null,
        null,
        0,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        simSwapDays,
        null,
        newCounterparty,
        null,
        0,
        0,
        0,
        device,
        null,
        null,
        0,
        null);
  }

  @Test
  void ordinaryPaymentsAreLow() {
    Scoring.Fallback scoring = RULES.score(transaction("15000"), history(0, 1, false, null, null));
    assertThat(scoring.tier()).isEqualTo(RiskTier.LOW);
    assertThat(scoring.reasonCodes()).isEmpty();
    assertThat(scoring.modelVersion()).isEqualTo("fallback-rules-1");
    assertThat(scoring.features()).isEmpty();
  }

  @Test
  void burstsOfFiveInSixtySecondsAreHigh() {
    assertThat(RULES.score(transaction("100"), history(3, 3, false, null, null)).tier())
        .isEqualTo(RiskTier.LOW);
    assertThat(RULES.score(transaction("100"), history(4, 4, false, null, null)).reasonCodes())
        .containsExactly("VELOCITY_SPIKE");
  }

  @Test
  void theSimSwapTakeoverPatternIsHigh() {
    Scoring.Fallback takeover = RULES.score(transaction("500000"), history(0, 0, true, 3, null));
    assertThat(takeover.tier()).isEqualTo(RiskTier.HIGH);
    assertThat(takeover.reasonCodes()).containsExactly("RECENT_SIM_SWAP");
    // An unknown SIM-swap signal is not evidence of a swap, only of a gap; the transaction is
    // still reviewed because the counterparty is new.
    assertThat(RULES.score(transaction("500000"), history(0, 0, true, null, null)).tier())
        .isEqualTo(RiskTier.MEDIUM);
    assertThat(RULES.score(transaction("500000"), history(0, 0, true, 7, null)).tier())
        .isEqualTo(RiskTier.MEDIUM);
  }

  @Test
  void largeAmountsAndNewDevicesAreReviewed() {
    assertThat(RULES.score(transaction("2000000"), history(0, 0, false, null, null)).reasonCodes())
        .containsExactly("AMOUNT_ABOVE_NORMAL");
    assertThat(RULES.score(transaction("10000000"), history(0, 0, true, null, null)).tier())
        .isEqualTo(RiskTier.HIGH);
    assertThat(
            RULES
                .score(
                    transaction("600000"),
                    history(0, 0, false, null, new AccountHistory.DeviceHistory(true, 1, 1, 0)))
                .reasonCodes())
        .containsExactly("NEW_DEVICE");
    assertThat(RULES.score(transaction("100"), history(0, 9, false, null, null)).reasonCodes())
        .containsExactly("VELOCITY_SPIKE");
  }

  @Test
  void settingsAreValidated() {
    assertThatThrownBy(
            () -> new FallbackRules("v", 0, 1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FallbackRules("v", 1, 1, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reasonCodesCoverEveryFeatureAndMergeToThree() {
    assertThat(ReasonCodes.mappedFeatureCount()).isEqualTo(44);
    assertThat(ReasonCodes.forFeature("unknown_feature")).isEqualTo(ReasonCodes.MODEL_RISK_SCORE);
    assertThat(ReasonCodes.merge(List.of("A_A", "B_B"), List.of("B_B", "C_C", "D_D")))
        .containsExactly("A_A", "B_B", "C_C");
    assertThat(RiskTier.of(io.github.mariusbayizere.fraudshield.rules.dsl.TierOverride.MEDIUM))
        .isEqualTo(RiskTier.MEDIUM);
    assertThat(AccountHistory.empty(true).meanHourlyCount30d()).isNaN();
    assertThatThrownBy(() -> new GeoPoint(91, 0)).isInstanceOf(IllegalArgumentException.class);
  }
}
