package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains one consumer's view of the spool into a sink, in order, committing only after the sink
 * accepted a batch. A failing sink is retried with exponential backoff and full jitter; the batch
 * is re-delivered from the committed position, so delivery is at-least-once and sinks must be
 * idempotent (Kafka consumers deduplicate on event ids, the PostgreSQL writer on unique keys).
 */
public final class SpoolDrainer implements AutoCloseable {

  /** Receives a batch of records in order; throws to have the batch retried. */
  @FunctionalInterface
  public interface Sink {
    /**
     * Accepts records.
     *
     * @param records records in spool order
     * @throws SinkException when the batch was not accepted
     */
    void accept(List<SpoolRecord> records) throws SinkException;
  }

  /** A sink did not accept a batch; the batch is retried. */
  public static final class SinkException extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause the underlying failure
     */
    public SinkException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final SecureRandom JITTER = new SecureRandom();

  private final Logger log;

  private final DurableSpool spool;
  private final String consumer;
  private final Sink sink;
  private final int batchSize;
  private final Duration idle;
  private final Duration maxBackoff;
  private final Thread thread;
  private final AtomicLong failures = new AtomicLong();
  private final AtomicLong delivered = new AtomicLong();
  private volatile boolean running = true;

  /**
   * Starts a drainer thread.
   *
   * @param spool the spool
   * @param consumer the consumer name
   * @param sink where records go
   * @param batchSize most records per batch
   * @param idle wait when there is nothing to drain
   * @param maxBackoff longest wait between retries
   */
  public SpoolDrainer(
      DurableSpool spool,
      String consumer,
      Sink sink,
      int batchSize,
      Duration idle,
      Duration maxBackoff) {
    this.spool = Objects.requireNonNull(spool, "spool");
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.batchSize = batchSize;
    this.idle = idle;
    this.maxBackoff = maxBackoff;
    this.log = LoggerFactory.getLogger(SpoolDrainer.class.getName() + "." + consumer);
    spool.committed(consumer);
    this.thread = Thread.ofPlatform().name("spool-" + consumer).daemon(true).start(this::run);
  }

  /**
   * Consecutive failures since the last accepted batch.
   *
   * @return failure count
   */
  public long failures() {
    return failures.get();
  }

  /**
   * Records delivered since start.
   *
   * @return delivered count
   */
  public long delivered() {
    return delivered.get();
  }

  private void run() {
    long backoff = 10;
    while (running) {
      try {
        List<SpoolRecord> batch = spool.read(spool.committed(consumer), batchSize);
        if (batch.isEmpty()) {
          Thread.sleep(idle.toMillis());
          continue;
        }
        sink.accept(batch);
        spool.commit(consumer, batch.getLast().nextOffset());
        delivered.addAndGet(batch.size());
        failures.set(0);
        backoff = 10;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (SpoolDrainer.SinkException | RuntimeException e) {
        long attempt = failures.incrementAndGet();
        if (attempt == 1 || attempt % 100 == 0) {
          log.warn("spool consumer failed; retrying with backoff (count in failures())", e);
        }
        try {
          Thread.sleep(JITTER.nextLong(backoff + 1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        backoff = Math.min(backoff * 2, maxBackoff.toMillis());
      }
    }
  }

  @Override
  public void close() {
    running = false;
    thread.interrupt();
    try {
      thread.join(Duration.ofSeconds(10).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
