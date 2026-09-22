package io.github.mariusbayizere.fraudshield.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FR-07-06")
class InMemoryRateLimiterTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-22T08:00:00Z"));
  private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(clock);
  private static final Duration WINDOW = Duration.ofMinutes(15);

  @Test
  void allowsTheLimitThenRefusesWithRetryAfterUntilTheWindowSlides() {
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.attempt("ip", 10, WINDOW).allowed()).isTrue();
      clock.advance(Duration.ofSeconds(1));
    }
    RateLimiter.Decision eleventh = limiter.attempt("ip", 10, WINDOW);
    assertThat(eleventh.allowed()).isFalse();
    assertThat(eleventh.retryAfterSeconds())
        .isEqualTo(Duration.ofMinutes(15).minusSeconds(10).toSeconds());
    clock.advance(Duration.ofMinutes(15).minusSeconds(10));
    assertThat(limiter.attempt("ip", 10, WINDOW).allowed())
        .as("the oldest attempt left the window")
        .isTrue();
    assertThat(limiter.attempt("ip", 10, WINDOW).allowed()).isFalse();
    assertThat(limiter.attempt("other", 10, WINDOW).allowed()).as("keys are independent").isTrue();
  }

  @Test
  void refusedAttemptsAreNotCounted() {
    for (int i = 0; i < 3; i++) {
      limiter.attempt("k", 3, WINDOW);
    }
    for (int i = 0; i < 50; i++) {
      assertThat(limiter.attempt("k", 3, WINDOW).allowed()).isFalse();
    }
    clock.advance(WINDOW);
    assertThat(limiter.attempt("k", 3, WINDOW).allowed()).isTrue();
  }
}
