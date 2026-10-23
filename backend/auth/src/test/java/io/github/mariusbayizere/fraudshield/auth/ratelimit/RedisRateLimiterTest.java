package io.github.mariusbayizere.fraudshield.auth.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.testing.Instances;
import io.github.mariusbayizere.fraudshield.auth.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** The cluster-wide limiter's check against a real Redis (API-key cache contract R0(a), P24). */
@Tag("FR-07-06")
class RedisRateLimiterTest {

  private static final Duration WINDOW = Duration.ofMinutes(15);

  @Test
  void checkSeesTheScriptsCountAndNeverAddsToIt() {
    MutableClock clock = new MutableClock(Instant.now());
    StringRedisTemplate redis = Instances.redis();
    RedisRateLimiter limiter =
        new RedisRateLimiter(Instances.safe(redis), new InMemoryRateLimiter(clock), clock);
    String key = "check-" + UUID.randomUUID();
    for (int i = 0; i < 100; i++) {
      assertThat(limiter.check(key, 3, WINDOW).allowed()).isTrue();
    }
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.attempt(key, 3, WINDOW).allowed())
          .as("the checks counted nothing")
          .isTrue();
      clock.advance(Duration.ofSeconds(1));
    }
    assertThat(redis.opsForZSet().size("fs:rl:" + key))
        .as("counted in Redis, not the fallback")
        .isEqualTo(3);
    RateLimiter.Decision checked = limiter.check(key, 3, WINDOW);
    assertThat(checked.allowed()).isFalse();
    assertThat(checked.retryAfterSeconds())
        .isEqualTo(Duration.ofMinutes(15).minusSeconds(3).toSeconds());
    clock.advance(Duration.ofMinutes(15).minusSeconds(3));
    assertThat(limiter.check(key, 3, WINDOW).allowed()).as("the oldest left the window").isTrue();
  }
}
