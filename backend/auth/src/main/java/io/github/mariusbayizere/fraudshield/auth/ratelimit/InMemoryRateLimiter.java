package io.github.mariusbayizere.fraudshield.auth.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-instance sliding-window limiter: the fallback when Redis is unreachable (C.4). Limits then
 * apply per instance rather than cluster-wide, which is weaker but never fails open.
 */
public final class InMemoryRateLimiter implements RateLimiter {

  private static final int MAX_KEYS = 100_000;

  private final Clock clock;
  private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

  /**
   * Creates the limiter.
   *
   * @param clock clock
   */
  public InMemoryRateLimiter(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Decision attempt(String key, int limit, Duration window) {
    long now = clock.millis();
    long windowMillis = window.toMillis();
    if (attempts.size() >= MAX_KEYS) {
      attempts
          .values()
          .removeIf(
              deque -> {
                synchronized (deque) {
                  return deque.isEmpty() || deque.peekLast() <= now - windowMillis;
                }
              });
    }
    Deque<Long> deque = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
    synchronized (deque) {
      while (!deque.isEmpty() && deque.peekFirst() <= now - windowMillis) {
        deque.removeFirst();
      }
      if (deque.size() >= limit) {
        long retryMillis = deque.peekFirst() + windowMillis - now;
        return new Decision(false, Math.max(1, (retryMillis + 999) / 1000));
      }
      deque.addLast(now);
      return Decision.ALLOWED;
    }
  }
}
