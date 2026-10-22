package io.github.mariusbayizere.fraudshield.analyst.decisions;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the committer, the effects worker and the resolver every P on a scheduler of their own
 * (decision-owner contract §4.3), so that neither the timeout backlog nor one another can starve
 * them. A failing run is logged and runs again on its next tick.
 */
public final class DecisionWorkerTasks implements SmartLifecycle {

  /** P, the committer's, effects worker's and resolver's period (§2). */
  public static final Duration PERIOD = Duration.ofMillis(500);

  private static final Logger LOG = LoggerFactory.getLogger(DecisionWorkerTasks.class);

  private final DecisionWorkers workers;
  private ScheduledExecutorService scheduler;

  /**
   * Creates the tasks.
   *
   * @param workers the workers
   */
  public DecisionWorkerTasks(DecisionWorkers workers) {
    this.workers = Objects.requireNonNull(workers, "workers");
  }

  @Override
  public synchronized void start() {
    scheduler =
        Executors.newScheduledThreadPool(
            3, Thread.ofPlatform().name("analyst-decisions-", 0).daemon(true).factory());
    every(workers::commitDue);
    every(workers::runEffects);
    every(workers::applyResolutions);
  }

  private void every(Runnable task) {
    scheduler.scheduleWithFixedDelay(
        () -> {
          try {
            task.run();
          } catch (RuntimeException e) {
            LOG.warn("an analyst decision task failed; it runs again on its next tick", e);
          }
        },
        PERIOD.toMillis(),
        PERIOD.toMillis(),
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
