package io.github.mariusbayizere.fraudshield.auth.support;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Pads a response to a minimum duration, so responses that differ by whether an account exists
 * (registration, availability, reset request) take the same time (E.8: neutral timing).
 */
public final class MinimumDuration {

  private MinimumDuration() {}

  /**
   * Runs the work and returns no earlier than the minimum after it started.
   *
   * @param minimum floor on the duration
   * @param work the work
   * @param <T> result type
   * @return the work's result
   */
  public static <T> T atLeast(Duration minimum, Supplier<T> work) {
    long deadline = System.nanoTime() + minimum.toNanos();
    try {
      return work.get();
    } finally {
      long remaining = deadline - System.nanoTime();
      if (remaining > 0) {
        try {
          Thread.sleep(Duration.ofNanos(remaining));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }
}
