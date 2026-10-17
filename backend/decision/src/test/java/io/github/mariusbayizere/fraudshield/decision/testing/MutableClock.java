package io.github.mariusbayizere.fraudshield.decision.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests move by hand (H.1: injectable clocks make timer tests deterministic). */
public final class MutableClock extends Clock {

  private volatile Instant now;

  /**
   * Creates the clock.
   *
   * @param start initial time
   */
  public MutableClock(Instant start) {
    this.now = start;
  }

  /**
   * Moves the clock forward.
   *
   * @param step how far
   */
  public void advance(Duration step) {
    now = now.plus(step);
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
    return now;
  }
}
