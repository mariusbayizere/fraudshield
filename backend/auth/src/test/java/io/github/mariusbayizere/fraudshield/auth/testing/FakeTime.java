package io.github.mariusbayizere.fraudshield.auth.testing;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * One time source for both the wall clock and the monotonic clock, moved only by the test, so a
 * staleness bound can be asserted to the millisecond whatever the machine load (ADR 0071 §6).
 */
public final class FakeTime extends Clock {

  private final AtomicLong millis = new AtomicLong(1_700_000_000_000L);

  /**
   * The monotonic clock, in nanoseconds.
   *
   * @return nanoseconds
   */
  public long nanos() {
    return millis.get() * 1_000_000L;
  }

  /**
   * The current time in milliseconds.
   *
   * @return milliseconds
   */
  public long now() {
    return millis.get();
  }

  /**
   * Sets the time.
   *
   * @param at milliseconds
   */
  public void set(long at) {
    millis.set(at);
  }

  @Override
  public long millis() {
    return millis.get();
  }

  @Override
  public Instant instant() {
    return Instant.ofEpochMilli(millis.get());
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  /**
   * Runs work whose database read takes {@code stall} of fake time: the table is locked while the
   * work starts, the clock moves once the read is waiting, then the lock is released. The read
   * really happens after its caller's anchors were taken.
   *
   * @param superuser a connection that may lock the table (closed by the caller)
   * @param observer an autocommit connection to watch for the waiting read
   * @param table the table the work reads
   * @param stall how much fake time the read takes
   * @param work the work
   * @param <T> result type
   * @return the work's result
   * @throws Exception if the work fails or never reaches the lock
   */
  public <T> T withSlowRead(
      Connection superuser, Connection observer, String table, Duration stall, Supplier<T> work)
      throws Exception {
    superuser.setAutoCommit(false);
    try (Statement lock = superuser.createStatement()) {
      lock.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
    }
    try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var result = pool.submit(work::get);
      try {
        awaitWaiting(observer);
        millis.addAndGet(stall.toMillis());
      } finally {
        superuser.commit();
      }
      return result.get();
    }
  }

  private static void awaitWaiting(Connection observer) throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      try (var rows =
          observer
              .createStatement()
              .executeQuery(
                  "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'")) {
        rows.next();
        if (rows.getLong(1) > 0) {
          return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError("the read never waited for the table lock");
  }
}
