package io.github.mariusbayizere.fraudshield.notify.kafka;

/**
 * The head record's retry state under the bound of docs/architecture/decision-fact-ordering.md,
 * 12.3: how many attempts on it failed in F4, and its F4 time, the wall time since its first F4
 * failure minus every interval from an F1 failure to the next attempt on it. Cleared only when the
 * head changes, which the owner does by replacing the instance.
 *
 * <p>Not thread-safe: the consumer thread owns it. Times are {@link System#nanoTime()} values.
 */
final class HeadRetry {

  private int count;
  private long baseMillis;
  private long sinceNanos = -1;
  private long excludedNanos;
  private long outageSinceNanos = -1;

  /** No F4 failure yet. */
  HeadRetry() {}

  /**
   * Retry state resumed from a checkpoint (W-e): the count and time carried, the clock restarted
   * now, so the unowned time between owners is not added.
   *
   * @param count the checkpoint's F4 count
   * @param millis the checkpoint's F4 time
   * @param nowNanos now
   */
  HeadRetry(int count, long millis, long nowNanos) {
    this.count = count;
    this.baseMillis = millis;
    if (count > 0 || millis > 0) {
      this.sinceNanos = nowNanos;
    }
  }

  /**
   * An attempt starts: an outage interval in progress ends here and is not F4 time.
   *
   * @param startNanos when the attempt starts
   */
  void attemptStarts(long startNanos) {
    if (outageSinceNanos >= 0) {
      if (sinceNanos >= 0) {
        excludedNanos += Math.max(0, startNanos - outageSinceNanos);
      }
      outageSinceNanos = -1;
    }
  }

  /**
   * An attempt failed in F4: it is counted, and the clock starts if it has not.
   *
   * @param nowNanos when it failed
   */
  void failedOwn(long nowNanos) {
    count++;
    if (sinceNanos < 0) {
      sinceNanos = nowNanos;
    }
  }

  /**
   * An attempt failed in F1: neither counted nor cleared; the time until the next attempt is not F4
   * time.
   *
   * @param endNanos when it failed
   */
  void failedOutage(long endNanos) {
    outageSinceNanos = endNanos;
  }

  /**
   * The F4 count.
   *
   * @return attempts that failed in F4
   */
  int count() {
    return count;
  }

  /**
   * The F4 time at {@code nowNanos}.
   *
   * @param nowNanos now
   * @return milliseconds
   */
  long millis(long nowNanos) {
    if (sinceNanos < 0) {
      return baseMillis;
    }
    long excluded = excludedNanos;
    if (outageSinceNanos >= 0) {
      excluded += Math.max(0, nowNanos - outageSinceNanos);
    }
    return baseMillis + Math.max(0, nowNanos - sinceNanos - excluded) / 1_000_000;
  }

  /**
   * Whether the bound is reached: at least {@code attempts} F4 failures and {@code minimumMillis}
   * of F4 time.
   *
   * @param attempts the fewest F4 failures
   * @param minimumMillis the least F4 time
   * @param nowNanos now
   * @return true at the bound
   */
  boolean bounded(int attempts, long minimumMillis, long nowNanos) {
    return count >= attempts && millis(nowNanos) >= minimumMillis;
  }
}
