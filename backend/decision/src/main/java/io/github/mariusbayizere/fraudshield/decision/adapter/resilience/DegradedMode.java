package io.github.mariusbayizere.fraudshield.decision.adapter.resilience;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Whether the decision path is running on its fallbacks because Redis failed (C.4: the {@code
 * DEGRADED_MODE} metric and UI banner). Degraded while the last Redis call failed; each fallback
 * use is counted.
 */
public final class DegradedMode {

  /** After a failure, Redis is not tried again for this long; calls go straight to fallbacks. */
  public static final long RETRY_AFTER_NANOS = 1_000_000_000L;

  private volatile boolean degraded;
  private volatile long failedAtNanos;
  private final AtomicLong fallbacks = new AtomicLong();

  /** Records a Redis failure answered by a fallback. */
  public void fellBack() {
    if (!degraded) {
      failedAtNanos = System.nanoTime();
    }
    degraded = true;
    fallbacks.incrementAndGet();
  }

  /** Records that Redis failed now, restarting the retry window. */
  public void failed() {
    failedAtNanos = System.nanoTime();
    fellBack();
  }

  /**
   * Whether to skip Redis and use the fallback directly: within {@link #RETRY_AFTER_NANOS} of the
   * last failure, so an unreachable Redis costs one timeout per second, not one per call.
   *
   * @return true inside the retry window
   */
  public boolean skipPrimary() {
    return degraded && System.nanoTime() - failedAtNanos < RETRY_AFTER_NANOS;
  }

  /** Records a successful Redis call. */
  public void recovered() {
    degraded = false;
  }

  /**
   * Whether the last Redis call failed.
   *
   * @return true while degraded
   */
  public boolean degraded() {
    return degraded;
  }

  /**
   * Fallback uses since start.
   *
   * @return count
   */
  public long fallbacks() {
    return fallbacks.get();
  }
}
