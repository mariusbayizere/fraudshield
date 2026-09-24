package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One partition's wait budget: a leaky bucket of the time its records have spent waiting for their
 * parent facts (docs/architecture/decision-fact-ordering.md, section 6, W-b and W-e).
 *
 * <p>Every moment is one of three kinds. <b>Waiting</b> fills the budget, up to the bound: from the
 * end of a successful S4 answer to the end of the next successful attempt for the same record.
 * <b>Neutral</b> neither fills nor drains it: from the start of a failed attempt to the end of the
 * next successful one, which is an outage and not a wait. <b>Draining</b> is all other time, and
 * empties it at the drain rate. No outcome resets the budget.
 *
 * <p>Not thread-safe: the consumer thread owns it. Times are {@link System#nanoTime()} values.
 */
final class WaitBudget {

  /** What the time since the last transition counts as. */
  enum Kind {
    WAITING,
    NEUTRAL,
    DRAINING
  }

  private static final Pattern CHECKPOINT =
      Pattern.compile("^fs-wait:v2 offset=(\\d+) spent_ms=(\\d+) at=(\\d+)$");

  private final long boundNanos;
  private final double drainRate;
  private double spentNanos;
  private Kind kind = Kind.DRAINING;
  private long sinceNanos;

  /**
   * Creates a budget.
   *
   * @param bound the most waiting the budget holds
   * @param drainRate how much of each draining second it gives back, in (0, 1]
   * @param spent what is already spent
   * @param nowNanos the current {@link System#nanoTime()}
   */
  WaitBudget(Duration bound, double drainRate, Duration spent, long nowNanos) {
    Objects.requireNonNull(bound, "bound");
    if (bound.isNegative() || bound.isZero()) {
      throw new IllegalArgumentException("the bound must be positive");
    }
    if (!(drainRate > 0 && drainRate <= 1)) {
      throw new IllegalArgumentException("the drain rate must be in (0, 1]");
    }
    this.boundNanos = bound.toNanos();
    this.drainRate = drainRate;
    this.spentNanos = Math.min(boundNanos, Math.max(0, spent.toNanos()));
    this.sinceNanos = nowNanos;
  }

  /**
   * A successful attempt ended: from now on the time counts as {@code next}. The time since the
   * last transition is settled first, as the kind it was.
   *
   * @param nowNanos when the attempt ended
   * @param next {@link Kind#WAITING} after an S4 answer the record keeps waiting on, {@link
   *     Kind#DRAINING} otherwise
   */
  void succeeded(long nowNanos, Kind next) {
    if (next == Kind.NEUTRAL) {
      throw new IllegalArgumentException("a successful attempt ends neutral time");
    }
    settle(nowNanos);
    kind = next;
  }

  /**
   * An attempt that started at {@code startNanos} failed: from its start until the next successful
   * attempt the time is neutral.
   *
   * @param startNanos when the failed attempt started
   */
  void failed(long startNanos) {
    settle(Math.max(sinceNanos, startNanos));
    kind = Kind.NEUTRAL;
  }

  /**
   * Whether the budget is full at {@code nowNanos}, counting the time since the last transition as
   * its kind, without settling it.
   *
   * @param nowNanos now
   * @return true when the partition is tripped
   */
  boolean full(long nowNanos) {
    return projected(nowNanos) >= boundNanos;
  }

  /**
   * What is spent at {@code nowNanos}, without settling.
   *
   * @param nowNanos now
   * @return spent waiting
   */
  Duration spent(long nowNanos) {
    return Duration.ofNanos((long) projected(nowNanos));
  }

  /**
   * The kind the time since the last transition counts as.
   *
   * @return the kind
   */
  Kind kind() {
    return kind;
  }

  /**
   * The checkpoint carried by a commit of {@code offset}, or empty when nothing is spent (W-e).
   *
   * @param offset the committed offset
   * @param nowNanos now
   * @param epochMillis now, on the wall clock, for the next owner's drain
   * @return the metadata, or an empty string
   */
  String checkpoint(long offset, long nowNanos, long epochMillis) {
    long spentMillis = spent(nowNanos).toMillis();
    return spentMillis <= 0
        ? ""
        : "fs-wait:v2 offset=" + offset + " spent_ms=" + spentMillis + " at=" + epochMillis;
  }

  /**
   * What a new owner starts from: the committed checkpoint, if it is for the committed offset,
   * drained for the wall time since it was written. A time in the future is clamped to zero (W-e).
   *
   * @param metadata the committed metadata, possibly empty
   * @param committedOffset the committed offset
   * @param epochMillis now, on the wall clock
   * @param drainRate the drain rate
   * @return the spent waiting to resume from, and whether its time lay in the future
   */
  static Resumed resume(String metadata, long committedOffset, long epochMillis, double drainRate) {
    if (metadata == null) {
      return new Resumed(Duration.ZERO, false);
    }
    Matcher m = CHECKPOINT.matcher(metadata);
    if (!m.matches() || Long.parseLong(m.group(1)) != committedOffset) {
      return new Resumed(Duration.ZERO, false);
    }
    long spent = Long.parseLong(m.group(2));
    long at = Long.parseLong(m.group(3));
    long elapsed = Math.max(0, epochMillis - at);
    long left = Math.max(0, spent - (long) (elapsed * drainRate));
    return new Resumed(Duration.ofMillis(left), at > epochMillis);
  }

  /**
   * A resumed budget.
   *
   * @param spent spent waiting
   * @param futureCheckpoint whether the checkpoint's time lay in the future (clock skew)
   */
  record Resumed(Duration spent, boolean futureCheckpoint) {}

  /**
   * The checkpoint's offset, for tests and logs.
   *
   * @param metadata committed metadata
   * @return the offset it names
   */
  static Optional<Long> checkpointOffset(String metadata) {
    Matcher m = CHECKPOINT.matcher(metadata == null ? "" : metadata);
    return m.matches() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
  }

  private void settle(long nowNanos) {
    spentNanos = projected(nowNanos);
    sinceNanos = Math.max(sinceNanos, nowNanos);
  }

  private double projected(long nowNanos) {
    long dt = Math.max(0, nowNanos - sinceNanos);
    return switch (kind) {
      case WAITING -> Math.min(boundNanos, spentNanos + dt);
      case DRAINING -> Math.max(0, spentNanos - dt * drainRate);
      case NEUTRAL -> spentNanos;
    };
  }
}
