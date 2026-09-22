package io.github.mariusbayizere.fraudshield.auth.ratelimit;

import java.time.Duration;

/** Sliding-window request limits (FR-07-06, D-26). */
public interface RateLimiter {

  /**
   * The outcome of an attempt.
   *
   * @param allowed whether the attempt is within the limit (and was counted)
   * @param retryAfterSeconds when refused, seconds until the oldest counted attempt leaves the
   *     window
   */
  record Decision(boolean allowed, long retryAfterSeconds) {

    /** An allowed attempt. */
    public static final Decision ALLOWED = new Decision(true, 0);
  }

  /**
   * Counts an attempt against a key unless the key already has {@code limit} attempts in the
   * window. Refused attempts are not counted, so waiting out Retry-After always works.
   *
   * @param key limit key
   * @param limit attempts allowed per window
   * @param window window length
   * @return the decision
   */
  Decision attempt(String key, int limit, Duration window);
}
