package io.github.mariusbayizere.fraudshield.decision.domain;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.config.CircuitBreakerSettings;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker.Change;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker.WindowCounts;
import io.github.mariusbayizere.fraudshield.decision.testing.Properties;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("TEST-03")
class FreezeAndBreakerTest {

  private static final CircuitBreakerSettings SRS =
      new CircuitBreakerSettings(
          new BigDecimal("0.05"), Duration.ofMinutes(15), 100, Duration.ofMinutes(60));

  @Test
  @Tag("FR-03-06")
  void theThirdHighWithinSixtyMinutesFreezesAtExactlyThree() {
    Instant first = NOW;
    Instant second = NOW.plus(Duration.ofMinutes(20));
    Instant third = NOW.plus(Duration.ofMinutes(59));
    assertThat(FreezePolicy.freezes(List.of(first), first)).isFalse();
    assertThat(FreezePolicy.freezes(List.of(first, second), second)).isFalse();
    assertThat(FreezePolicy.freezes(List.of(first, second, third), third)).isTrue();
    // Exactly 60 minutes after the first, the first has left the half-open window.
    Instant late = NOW.plus(Duration.ofMinutes(60));
    assertThat(FreezePolicy.freezes(List.of(first, second, late), late)).isFalse();
  }

  @Test
  @Tag("FR-03-07")
  @Tag("D-18")
  void theBreakerOpensAboveFivePercentOnlyWithEnoughVolume() {
    // 5 of 100 is exactly 5.0%: not a breach. 6 of 100 is. The same rate below the minimum
    // volume is not (D-18: 1 in 10 must not trip it).
    assertThat(MccCircuitBreaker.breaches(new WindowCounts(100, 5), SRS)).isFalse();
    assertThat(MccCircuitBreaker.breaches(new WindowCounts(100, 6), SRS)).isTrue();
    assertThat(MccCircuitBreaker.breaches(new WindowCounts(99, 6), SRS)).isFalse();
    assertThat(MccCircuitBreaker.breaches(new WindowCounts(10, 1), SRS)).isFalse();
    assertThat(MccCircuitBreaker.breaches(new WindowCounts(2000, 100), SRS)).isFalse();
    assertThat(MccCircuitBreaker.breaches(new WindowCounts(2000, 101), SRS)).isTrue();
  }

  @Test
  @Tag("FR-03-07")
  void theBreakerClosesAfterSixtyCleanMinutes() {
    MccCircuitBreaker.Evaluation opened =
        MccCircuitBreaker.evaluate(new WindowCounts(100, 6), SRS, CircuitBreakerState.CLOSED, NOW);
    assertThat(opened.change()).contains(Change.OPENED);
    assertThat(opened.state().open()).isTrue();

    Instant laterBreach = NOW.plus(Duration.ofMinutes(10));
    MccCircuitBreaker.Evaluation stillOpen =
        MccCircuitBreaker.evaluate(new WindowCounts(200, 20), SRS, opened.state(), laterBreach);
    assertThat(stillOpen.change()).isEmpty();
    assertThat(stillOpen.state().openedAt()).isEqualTo(NOW);
    assertThat(stillOpen.state().lastBreachAt()).isEqualTo(laterBreach);

    WindowCounts clean = new WindowCounts(300, 1);
    assertThat(
            MccCircuitBreaker.evaluate(
                    clean, SRS, stillOpen.state(), laterBreach.plus(Duration.ofMinutes(59)))
                .change())
        .isEmpty();
    MccCircuitBreaker.Evaluation closed =
        MccCircuitBreaker.evaluate(
            clean, SRS, stillOpen.state(), laterBreach.plus(Duration.ofMinutes(60)));
    assertThat(closed.change()).contains(Change.CLOSED);
    assertThat(closed.state().open()).isFalse();
    assertThat(MccCircuitBreaker.evaluate(clean, SRS, CircuitBreakerState.CLOSED, NOW).change())
        .isEmpty();
  }

  @Test
  @Tag("FR-03-07")
  void countsAndRatesAreValidated() {
    assertThat(new WindowCounts(0, 0).rate()).isEqualByComparingTo("0");
    assertThat(new WindowCounts(3, 1).rate()).isEqualByComparingTo("0.3333");
    assertThatThrownBy(() -> new WindowCounts(1, 2)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CircuitBreakerState(true, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Tag("D-18")
  void propertyBreachIsExactRationalComparison() {
    Properties.forAll(
        "breach iff fraud/n > threshold with n >= minimum",
        10_000,
        random -> {
          long n = random.nextLong(0, 5000);
          return new long[] {
            n,
            n == 0 ? 0 : random.nextLong(0, n + 1),
            random.nextLong(1, 10000),
            random.nextLong(1, 300)
          };
        },
        v -> {
          CircuitBreakerSettings settings =
              new CircuitBreakerSettings(
                  BigDecimal.valueOf(v[2], 4),
                  Duration.ofMinutes(15),
                  (int) v[3],
                  Duration.ofMinutes(60));
          // fraud / n > t / 10^4  <=>  fraud * 10^4 > t * n, in exact integers.
          boolean expected =
              v[0] >= v[3]
                  && BigInteger.valueOf(v[1])
                          .multiply(BigInteger.valueOf(10_000))
                          .compareTo(BigInteger.valueOf(v[2]).multiply(BigInteger.valueOf(v[0])))
                      > 0;
          assertThat(MccCircuitBreaker.breaches(new WindowCounts(v[0], v[1]), settings))
              .isEqualTo(expected);
        });
  }
}
