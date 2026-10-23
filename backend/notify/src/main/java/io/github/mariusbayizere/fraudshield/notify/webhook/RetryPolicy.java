package io.github.mariusbayizere.fraudshield.notify.webhook;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * Webhook retries (contracts/webhooks/decision-final.md): exponential backoff with full jitter
 * starting at 30 seconds, each wait capped at one hour, for up to 24 hours after the first attempt.
 */
public final class RetryPolicy {

  /** First backoff ceiling. */
  public static final Duration BASE = Duration.ofSeconds(30);

  /** Largest single backoff ceiling. */
  public static final Duration CAP = Duration.ofHours(1);

  /** How long delivery is attempted before dead-lettering. */
  public static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

  private final RandomGenerator random;

  /** A policy with a secure random source. */
  public RetryPolicy() {
    this(new SecureRandom());
  }

  RetryPolicy(RandomGenerator random) {
    this.random = random;
  }

  /**
   * The wait before the next attempt: uniform in {@code [0, min(cap, base × 2^(n-1))]}.
   *
   * @param failedAttempts attempts made so far, at least 1
   * @return the wait
   */
  public Duration delay(int failedAttempts) {
    long ceiling = BASE.toMillis() << Math.min(failedAttempts - 1, 20);
    ceiling = Math.min(ceiling, CAP.toMillis());
    return Duration.ofMillis(random.nextLong(ceiling + 1));
  }

  /**
   * Whether to stop retrying.
   *
   * @param firstAttempt when the first attempt was made
   * @param now current time
   * @return true 24 hours after the first attempt
   */
  public boolean exhausted(Instant firstAttempt, Instant now) {
    return !now.isBefore(firstAttempt.plus(GIVE_UP_AFTER));
  }
}
