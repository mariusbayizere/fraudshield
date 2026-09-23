package io.github.mariusbayizere.fraudshield.ingest.ratelimit;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per key in this process's memory (E.1, ADR 0070).
 *
 * <p>It is the fallback the API uses while Redis is unavailable. Each instance then holds the whole
 * budget on its own, so a deployment of N instances lets through up to N times the limit — which is
 * the point: an outage of the limiter's store must not remove the control, only weaken it, and
 * every answer it gives is marked degraded.
 */
public final class LocalRateLimiter implements RateLimiter {

  private static final class Bucket {
    private double tokens;
    private long lastRefillMillis;

    private Bucket(double tokens, long millis) {
      this.tokens = tokens;
      this.lastRefillMillis = millis;
    }
  }

  private final int limit;
  private final int burst;
  private final Clock clock;
  private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * Creates the limiter.
   *
   * @param limit requests per second
   * @param burst how many requests may arrive at once
   * @param clock clock, in milliseconds
   */
  public LocalRateLimiter(int limit, int burst, Clock clock) {
    if (limit < 1 || burst < 1) {
      throw new IllegalArgumentException("the rate limit and burst must be at least one");
    }
    this.limit = limit;
    this.burst = burst;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Permit take(UUID apiKeyId) {
    long now = clock.millis();
    Bucket bucket = buckets.computeIfAbsent(apiKeyId, key -> new Bucket(burst, now));
    synchronized (bucket) {
      double refill = (now - bucket.lastRefillMillis) / 1000.0 * limit;
      bucket.tokens = Math.min(burst, bucket.tokens + Math.max(0, refill));
      bucket.lastRefillMillis = now;
      if (bucket.tokens >= 1) {
        bucket.tokens -= 1;
        return new Permit(true, limit, (int) bucket.tokens, 0, true);
      }
      int retryAfter = (int) Math.max(1, Math.ceil((1 - bucket.tokens) / limit));
      return new Permit(false, limit, 0, retryAfter, true);
    }
  }
}
