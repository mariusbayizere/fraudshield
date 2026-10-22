package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The instance-wide outbound budget (ADR 0115 point 8): the sum of the frame sizes queued across
 * every session, counted per queue as if unshared, which over-counts.
 */
final class InstanceBudget {

  private final long limit;
  private final AtomicLong queued = new AtomicLong();

  InstanceBudget(long limit) {
    this.limit = limit;
  }

  void add(long bytes) {
    queued.addAndGet(bytes);
  }

  void release(long bytes) {
    queued.addAndGet(-bytes);
  }

  long queued() {
    return queued.get();
  }

  boolean exceeded() {
    return queued.get() > limit;
  }

  /** Shedding stops below 75 % of the budget (point 8). */
  long lowWater() {
    return limit * 3 / 4;
  }
}
