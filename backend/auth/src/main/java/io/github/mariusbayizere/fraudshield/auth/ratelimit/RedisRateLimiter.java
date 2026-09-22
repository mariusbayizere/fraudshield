package io.github.mariusbayizere.fraudshield.auth.ratelimit;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Cluster-wide sliding-window limiter on a Redis sorted set, applied atomically by a Lua script:
 * drop entries older than the window, refuse if {@code limit} remain, otherwise add this attempt.
 * Falls back to {@link InMemoryRateLimiter} when Redis is unreachable.
 */
public final class RedisRateLimiter implements RateLimiter {

  private static final String PREFIX = "fs:rl:";
  private static final RedisScript<List> SCRIPT =
      new DefaultRedisScript<>(
          """
          local now = tonumber(ARGV[1])
          local window = tonumber(ARGV[2])
          local limit = tonumber(ARGV[3])
          redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
          local count = redis.call('ZCARD', KEYS[1])
          if count >= limit then
            local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
            return {0, tonumber(oldest[2]) + window - now}
          end
          redis.call('ZADD', KEYS[1], now, ARGV[4])
          redis.call('PEXPIRE', KEYS[1], window)
          return {1, 0}
          """,
          List.class);

  private final SafeRedis redis;
  private final RateLimiter fallback;
  private final Clock clock;

  /**
   * Creates the limiter.
   *
   * @param redis Redis
   * @param fallback limiter used when Redis is unreachable
   * @param clock clock
   */
  public RedisRateLimiter(SafeRedis redis, RateLimiter fallback, Clock clock) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.fallback = Objects.requireNonNull(fallback, "fallback");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Decision attempt(String key, int limit, Duration window) {
    long now = clock.millis();
    Optional<List> result =
        redis.execute(
            SCRIPT,
            List.of(PREFIX + key),
            Long.toString(now),
            Long.toString(window.toMillis()),
            Integer.toString(limit),
            now + "-" + Crypto.randomToken(6));
    if (result.isEmpty() || result.get().size() != 2) {
      return fallback.attempt(key, limit, window);
    }
    long allowed = ((Number) result.get().get(0)).longValue();
    if (allowed == 1) {
      return Decision.ALLOWED;
    }
    long retryMillis = ((Number) result.get().get(1)).longValue();
    return new Decision(false, Math.max(1, (retryMillis + 999) / 1000));
  }
}
