package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcConfiguration;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcOverdueHolds;
import io.github.mariusbayizere.fraudshield.decision.application.CircuitBreakerMonitor;
import io.github.mariusbayizere.fraudshield.decision.application.HoldTimeoutService;
import io.github.mariusbayizere.fraudshield.ingest.application.BatchJobs;
import io.github.mariusbayizere.fraudshield.notify.verification.UnblockReconciler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The decision path's periodic work (E.6): the MEDIUM deadline poller every 100 ms (leader only),
 * the overdue-hold sweep, the unblock sweep and the MCC breaker monitor every 5 s, and
 * configuration refresh every 5 s, which keeps threshold and rule changes effective well within 60
 * s. A failing task is logged and runs again on its next tick.
 */
@Component
public final class BackgroundTasks implements SmartLifecycle {

  /** Deadline poller period (E.6). */
  public static final Duration HOLD_TICK = Duration.ofMillis(100);

  /** Sweep, monitor and refresh period. */
  public static final Duration SLOW_TICK = Duration.ofSeconds(5);

  /** How long after a deadline the sweep waits for the poller. */
  public static final Duration SWEEP_GRACE = Duration.ofSeconds(10);

  private static final Logger LOG = LoggerFactory.getLogger(BackgroundTasks.class);

  private final HoldTimeoutService holds;
  private final JdbcOverdueHolds overdue;
  private final CircuitBreakerMonitor breakers;
  private final JdbcConfiguration configuration;
  private final UnblockReconciler unblocks;
  private final BatchJobs batches;
  private ScheduledExecutorService scheduler;

  /**
   * Creates the tasks.
   *
   * @param holds hold timeouts
   * @param overdue durable overdue holds
   * @param breakers MCC breaker monitor
   * @param configuration risk configuration
   * @param unblocks customer unblocks that did not reach the decision path
   * @param batches batch jobs, for the start-up sweep
   */
  public BackgroundTasks(
      HoldTimeoutService holds,
      JdbcOverdueHolds overdue,
      CircuitBreakerMonitor breakers,
      JdbcConfiguration configuration,
      UnblockReconciler unblocks,
      BatchJobs batches) {
    this.holds = Objects.requireNonNull(holds, "holds");
    this.overdue = Objects.requireNonNull(overdue, "overdue");
    this.breakers = Objects.requireNonNull(breakers, "breakers");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.unblocks = Objects.requireNonNull(unblocks, "unblocks");
    this.batches = Objects.requireNonNull(batches, "batches");
  }

  @Override
  public synchronized void start() {
    try {
      // Jobs an earlier process left RUNNING have no items anywhere and can only be failed (V65).
      batches.failUnfinishedJobs();
    } catch (java.sql.SQLException | RuntimeException e) {
      LOG.warn("could not fail the batch jobs left unfinished by an earlier process", e);
    }
    scheduler =
        Executors.newScheduledThreadPool(
            2, Thread.ofPlatform().name("decision-tasks-", 0).daemon(true).factory());
    every(HOLD_TICK, holds::tick);
    every(SLOW_TICK, () -> holds.reconcile(overdue, SWEEP_GRACE));
    every(SLOW_TICK, breakers::evaluate);
    every(SLOW_TICK, configuration::refreshAll);
    every(SLOW_TICK, unblocks::reconcile);
  }

  private void every(Duration period, Runnable task) {
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            task.run();
          } catch (RuntimeException e) {
            LOG.warn("a decision background task failed; it runs again on its next tick", e);
          }
        },
        period.toMillis(),
        period.toMillis(),
        TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return scheduler != null;
  }
}
