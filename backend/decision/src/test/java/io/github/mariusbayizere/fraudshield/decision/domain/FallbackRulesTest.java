package io.github.mariusbayizere.fraudshield.decision.domain;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** C.4: the versioned rule-based fallback, on the transaction alone (ADR 0033, ADR 0061). */
@Tag("NFR-REL-01")
class FallbackRulesTest {

  private static final FallbackRules RULES = FallbackRules.DEFAULTS;

  @Test
  void largePaymentsAreHeldAtTheReviewAmountAndNotBelowIt() {
    Scoring.Fallback at = RULES.score(transaction("2000000"));
    assertThat(at.tier()).isEqualTo(RiskTier.MEDIUM);
    assertThat(at.reasonCodes()).containsExactly("AMOUNT_ABOVE_NORMAL");
    assertThat(at.modelVersion()).isEqualTo("fallback-rules-2");
    Scoring.Fallback below = RULES.score(transaction("1999999.9999"));
    assertThat(below.tier()).isEqualTo(RiskTier.LOW);
    assertThat(below.reasonCodes()).isEmpty();
  }

  @Test
  void theFallbackNeverBlocksOnAmountAlone() {
    assertThat(RULES.score(transaction("999999999")).tier()).isEqualTo(RiskTier.MEDIUM);
  }

  @Test
  void theFallbackHasNoFeaturesToOffer() {
    assertThat(RULES.score(transaction("1")).features()).isEmpty();
  }

  @Test
  void thresholdsAreValidated() {
    assertThatThrownBy(() -> new FallbackRules("v", BigDecimal.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
