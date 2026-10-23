package io.github.mariusbayizere.fraudshield.ingest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR 0058 (adopting ADR 0100): the budget counts transactions, a charge is all or nothing, and
 * both limiters — the shared one in Redis and this instance's own — agree on every case.
 */
@Tag("requires-docker")
@Tag("FR-01-02")
@Tag("NFR-SEC-03")
class TransactionBudgetTest {

  private static final Instant T0 = Instant.parse("2026-09-24T09:00:00Z");
  private static RedisTestServer redis;
  private static StatefulRedisConnection<String, String> connection;

  @BeforeAll
  static void start() {
    redis = new RedisTestServer();
    connection = redis.connect();
  }

  @AfterAll
  static void stop() {
    connection.close();
    redis.close();
  }

  static List<Function<MutableClock, RateLimiter>> limiters() {
    return List.of(
        clock -> new LocalRateLimiter(100, 1_000, clock),
        clock -> new RedisRateLimiter(connection, 100, 1_000, Duration.ofSeconds(2), clock));
  }

  @ParameterizedTest
  @MethodSource("limiters")
  void batchesCostTheirItemCountAndRefusedChargesTakeNothing(
      Function<MutableClock, RateLimiter> make) {
    MutableClock clock = new MutableClock(T0);
    RateLimiter limiter = make.apply(clock);
    UUID key = UUID.randomUUID();

    RateLimiter.Permit first = limiter.take(key, 700);
    assertThat(first.allowed()).isTrue();
    assertThat(first.remaining()).as("700 of 1,000 spent by one batch").isEqualTo(300);

    RateLimiter.Permit refused = limiter.take(key, 400);
    assertThat(refused.allowed()).as("400 does not fit in 300").isFalse();
    assertThat(refused.remaining()).as("all or nothing: the refusal took nothing").isEqualTo(300);
    assertThat(refused.retryAfterSeconds())
        .as("100 more units at 100 per second: one second")
        .isEqualTo(1);

    assertThat(limiter.take(key, 300).allowed()).as("what remained is still there").isTrue();
    assertThat(limiter.take(key, 1).allowed()).as("and now it is gone").isFalse();

    clock.advance(Duration.ofSeconds(2));
    RateLimiter.Permit refilled = limiter.take(key, 150);
    assertThat(refilled.allowed()).as("two seconds refill 200").isTrue();
    assertThat(refilled.remaining()).isEqualTo(50);
  }

  @ParameterizedTest
  @MethodSource("limiters")
  void singleAndBatchChargesDrawOnOneBucketPerKey(Function<MutableClock, RateLimiter> make) {
    MutableClock clock = new MutableClock(T0);
    RateLimiter limiter = make.apply(clock);
    UUID key = UUID.randomUUID();
    for (int i = 0; i < 10; i++) {
      assertThat(limiter.take(key, 1).allowed()).isTrue();
    }
    assertThat(limiter.take(key, 991).allowed()).as("990 remain, not 1,000").isFalse();
    assertThat(limiter.take(key, 990).allowed()).isTrue();
    assertThat(limiter.take(UUID.randomUUID(), 1_000).allowed())
        .as("another key has its own budget")
        .isTrue();
  }

  @ParameterizedTest
  @MethodSource("limiters")
  void chargesOfNothingAreRefusedAsProgrammingErrors(Function<MutableClock, RateLimiter> make) {
    RateLimiter limiter = make.apply(new MutableClock(T0));
    assertThatThrownBy(() -> limiter.take(UUID.randomUUID(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
