package io.github.mariusbayizere.fraudshield.ingest.ratelimit;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The deployment-wide request budget, as a token bucket in Redis (E.1).
 *
 * <p>One Lua script refills and takes in a single round trip, so every instance shares one budget
 * per API key. The bucket holds only two fields and expires after the time it would take to refill
 * from empty, so an idle key costs nothing. The call is bounded by the same timeout as the rest of
 * the decision path's Redis use: a limiter must never be the reason a request inside its budget is
 * slow.
 */
public final class RedisRateLimiter implements RateLimiter {

  private static final String TAKE =
      """
      local limit = tonumber(ARGV[1])
      local burst = tonumber(ARGV[2])
      local now = tonumber(ARGV[3])
      local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
      local tokens = tonumber(state[1])
      local at = tonumber(state[2])
      if tokens == nil then
        tokens = burst
        at = now
      end
      tokens = math.min(burst, tokens + (now - at) / 1000.0 * limit)
      local allowed = 0
      if tokens >= 1 then
        allowed = 1
        tokens = tokens - 1
      end
      redis.call('HSET', KEYS[1], 'tokens', tokens, 'at', now)
      redis.call('PEXPIRE', KEYS[1], math.ceil(burst / limit * 1000) + 1000)
      return {allowed, tostring(tokens)}
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final int limit;
  private final int burst;
  private final Duration timeout;
  private final Clock clock;

  /**
   * Creates the limiter.
   *
   * @param connection shared Lettuce connection
   * @param limit requests per second
   * @param burst how many requests may arrive at once
   * @param timeout bound on the call
   * @param clock clock, in milliseconds
   */
  public RedisRateLimiter(
      StatefulRedisConnection<String, String> connection,
      int limit,
      int burst,
      Duration timeout,
      Clock clock) {
    if (limit < 1 || burst < 1) {
      throw new IllegalArgumentException("the rate limit and burst must be at least one");
    }
    this.redis = connection.async();
    this.limit = limit;
    this.burst = burst;
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Permit take(UUID apiKeyId) {
    List<Object> result =
        await(
            redis.eval(
                TAKE,
                ScriptOutputType.MULTI,
                new String[] {"fs:ratelimit:" + apiKeyId},
                Integer.toString(limit),
                Integer.toString(burst),
                Long.toString(clock.millis())));
    boolean allowed = ((Long) result.get(0)) == 1L;
    double tokens = Double.parseDouble((String) result.get(1));
    if (allowed) {
      return new Permit(true, limit, (int) tokens, 0, false);
    }
    return new Permit(false, limit, 0, (int) Math.max(1, Math.ceil((1 - tokens) / limit)), false);
  }

  private <T> T await(CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while rate limiting", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("the rate limiter is unavailable", e);
    }
  }
}
