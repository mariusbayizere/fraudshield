package io.github.mariusbayizere.fraudshield.ingest.ratelimit;

import io.github.mariusbayizere.fraudshield.decision.adapter.resilience.DegradedMode;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The shared limiter with this instance's own as its fallback (E.1, C.4).
 *
 * <p>An outage of Redis must not remove a control: it weakens it to a per-instance budget rather
 * than letting every caller through. Each degraded answer is reported, so the response headers and
 * the metric say the budget was held locally.
 */
public final class ResilientRateLimiter implements RateLimiter {

  private final RateLimiter redis;
  private final RateLimiter local;
  private final DegradedMode mode;
  private final Consumer<Boolean> reported;

  /**
   * Creates the limiter.
   *
   * @param redis the shared limiter
   * @param local this instance's limiter
   * @param mode the shared degraded-mode flag, so one Redis outage is reported once
   * @param reported called with true when an answer was degraded, false otherwise
   */
  public ResilientRateLimiter(
      RateLimiter redis, RateLimiter local, DegradedMode mode, Consumer<Boolean> reported) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.local = Objects.requireNonNull(local, "local");
    this.mode = Objects.requireNonNull(mode, "mode");
    this.reported = Objects.requireNonNull(reported, "reported");
  }

  @Override
  public Permit take(UUID apiKeyId) {
    if (!mode.skipPrimary()) {
      try {
        Permit permit = redis.take(apiKeyId);
        mode.recovered();
        reported.accept(false);
        return permit;
      } catch (RuntimeException redisDown) {
        mode.failed();
      }
    } else {
      mode.fellBack();
    }
    Permit permit = local.take(apiKeyId);
    reported.accept(true);
    return permit;
  }
}
