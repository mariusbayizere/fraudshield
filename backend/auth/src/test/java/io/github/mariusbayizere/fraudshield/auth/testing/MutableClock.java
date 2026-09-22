package io.github.mariusbayizere.fraudshield.auth.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock that tests move forward, to cross expiries without waiting (H.1: injectable clocks). */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> now;

  /**
   * Creates the clock.
   *
   * @param start initial instant
   */
  public MutableClock(Instant start) {
    this.now = new AtomicReference<>(start);
  }

  /**
   * Moves the clock forward.
   *
   * @param duration how far
   */
  public void advance(Duration duration) {
    now.updateAndGet(current -> current.plus(duration));
  }

  /**
   * Sets the clock.
   *
   * @param instant the new time
   */
  public void set(Instant instant) {
    now.set(instant);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now.get();
  }
}
