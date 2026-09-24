package io.github.mariusbayizere.fraudshield.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ADR 0058: the budget's default, and the configurations the application refuses to start with. */
@Tag("FR-01-02")
@Tag("NFR-SEC-03")
class RateLimitConfigurationTest {

  @Test
  void theDefaultIsTheAssumedTwoThousandPerSecondWithFourThousandBurst() {
    assertThat(FraudShieldProperties.RateLimit.DEFAULT)
        .isEqualTo(new FraudShieldProperties.RateLimit(2_000, 4_000));
  }

  @Test
  void burstsSmallerThanTheLargestBatchAreRefused() {
    // A batch is charged whole, so a smaller burst would refuse a full batch for ever.
    assertThatThrownBy(() -> new FraudShieldProperties.RateLimit(5, 999))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 1000");
    assertThat(new FraudShieldProperties.RateLimit(5, 1_000).burst()).isEqualTo(1_000);
  }

  @Test
  void burstsBelowTheRateAndNonPositiveRatesAreRefused() {
    assertThatThrownBy(() -> new FraudShieldProperties.RateLimit(0, 4_000))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FraudShieldProperties.RateLimit(5_000, 4_000))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
