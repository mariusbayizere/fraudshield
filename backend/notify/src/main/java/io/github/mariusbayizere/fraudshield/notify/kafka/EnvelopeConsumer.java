package io.github.mariusbayizere.fraudshield.notify.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.LogTruncationException;
import org.apache.kafka.clients.consumer.NoOffsetForPartitionException;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes one topic's envelopes (ADR 0012) as one consumer group and hands each, with the
 * institution the producing service set from its authenticated principal and the record's headers,
 * to a handler. Offsets are committed only after the handler returns, so a failing handler re-reads
 * the record rather than drop it; handlers are idempotent on the event's own id.
 *
 * <p>Every failed attempt is in one class (docs/architecture/decision-fact-ordering.md, 12.2): an
 * outage of a shared dependency is re-read without bound; a record that cannot be read, or that the
 * database refuses as data, is written to {@code <topic>.dlq} (C.3, ADR 0012) at once with its
 * original headers and the reason; anything else is re-read up to a bound and then dead-lettered as
 * {@code handler_failure} for replay (12.3). Re-reads follow the partition's own exponential
 * backoff, up to {@value #MAX_BACKOFF_MS} ms.
 *
 * <p>An SMS intent whose parent has not reached PostgreSQL yet ({@link NotYetRecordedException})
 * waits, re-read every {@value #PARENT_BACKOFF_MS} ms, within its partition's {@link WaitBudget}; a
 * partition whose budget is full dead-letters such intents ({@code parent_not_recorded}) for replay
 * (sections 6 and 7). Every re-read pauses only its partition: the consumer thread never sleeps for
 * one, so the other partitions keep flowing.
 *
 * <p>Once a handler has called the SMS provider for a record, it hands the result to the consumer,
 * which keeps it and commits it before the outcome is written, so a re-read writes the kept outcome
 * and never sends again (12.4).
 */
public final class EnvelopeConsumer implements AutoCloseable {

  /** Handles one envelope; throws to have the record re-read or dead-lettered. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Handles an envelope.
     *
     * @param envelope the envelope
     * @throws java.sql.SQLException when the record should be re-read (a transient state) or
     *     dead-lettered
     */
    void handle(Envelope envelope) throws java.sql.SQLException;
  }

  /** Takes a provider result the handler obtained for the record in hand (12.4). */
  @FunctionalInterface
  public interface ResultKeeper {
    /**
     * Keeps the result for the record in hand and commits it before the handler writes the outcome.
     *
     * @param result what the provider answered
     */
    void keep(ProviderResult result);
  }

  /**
   * One envelope as the handler sees it.
   *
   * @param institutionId institution from the envelope
   * @param payload the envelope's payload, a JSON object
   * @param headers the record's headers, last value per name
   * @param transaction the {@code fs-transaction-id} header's state, from every value it carries
   * @param kept the provider result already kept for this record, from this owner's state, the
   *     checkpoint, or a replayed copy's {@code fs-kept-result}
   * @param keeper where the handler hands a new provider result
   */
  public record Envelope(
      UUID institutionId,
      JsonNode payload,
      Map<String, String> headers,
      TransactionHeader transaction,
      Optional<ProviderResult> kept,
      ResultKeeper keeper) {

    /** Copies the headers and checks the rest. */
    public Envelope {
      headers = Map.copyOf(headers);
      Objects.requireNonNull(transaction, "transaction");
      Objects.requireNonNull(kept, "kept");
      Objects.requireNonNull(keeper, "keeper");
    }

    /**
     * The transaction a customer SMS intent belongs to, when its header is valid.
     *
     * @return the transaction id
     */
    public Optional<UUID> transactionId() {
      return transaction.transaction();
    }
  }

  /**
   * The wait budget's parameters (W-b), the bound and rate ASSUMED, and how often a long wait is
   * logged (W-d).
   *
   * @param bound the most waiting a partition's budget holds
   * @param drainRate how much of each draining second it gives back
   * @param warnAfter how long a partition waits before the wait is logged at WARN, and how often
   */
  public record ParentWait(Duration bound, double drainRate, Duration warnAfter) {

    /** Ten minutes, drained in sixty; a WARN after a minute of waiting, then every minute. */
    public static final ParentWait DEFAULT = new ParentWait(Duration.ofMinutes(10), 1.0 / 6);

    /** Validates the parameters. */
    public ParentWait {
      Objects.requireNonNull(bound, "bound");
      Objects.requireNonNull(warnAfter, "warnAfter");
      if (bound.isNegative()
          || bound.isZero()
          || warnAfter.isNegative()
          || warnAfter.isZero()
          || !(drainRate > 0 && drainRate <= 1)) {
        throw new IllegalArgumentException(
            "the bound and WARN interval must be positive and the rate in (0, 1]");
      }
    }

    /**
     * A budget with the default one-minute WARN.
     *
     * @param bound the most waiting a partition's budget holds
     * @param drainRate how much of each draining second it gives back
     */
    public ParentWait(Duration bound, double drainRate) {
      this(bound, drainRate, WARN_AFTER);
    }
  }

  /**
   * The bound on failures that may be the record's own (12.3), ASSUMED, and the batch's time budget
   * (12.3).
   *
   * @param attempts the fewest F4 failures before a record is dead-lettered
   * @param time the least F4 time before a record is dead-lettered
   * @param batchBudget how long one batch is handled before the consumer polls again
   */
  public record RetryBound(int attempts, Duration time, Duration batchBudget) {

    /** Five attempts and ten minutes; a batch budget of 60 seconds. */
    public static final RetryBound DEFAULT =
        new RetryBound(5, Duration.ofMinutes(10), Duration.ofSeconds(60));

    /** Validates the parameters. */
    public RetryBound {
      Objects.requireNonNull(time, "time");
      Objects.requireNonNull(batchBudget, "batchBudget");
      if (attempts < 1 || time.isNegative() || batchBudget.isNegative() || batchBudget.isZero()) {
        throw new IllegalArgumentException(
            "at least one attempt, a time that is not negative, and a positive batch budget");
      }
    }
  }

  /** The header naming a customer SMS intent's transaction. */
  public static final String TRANSACTION_ID_HEADER = "fs-transaction-id";

  /** The header a replayed record carries: how many times it has been replayed. */
  public static final String REPLAYED_HEADER = "fs-replayed";

  /** The header a replayed {@code outcome_unrecorded} copy carries its provider result in. */
  public static final String KEPT_RESULT_HEADER = "fs-kept-result";

  /** The header a dead letter written while a provider result is kept carries it in. */
  public static final String DLQ_RESULT_HEADER = "fs-dlq-result";

  /** The prefix of the headers a dead-lettered copy gains. */
  public static final String DLQ_HEADER_PREFIX = "fs-dlq-";

  /** The ceiling of the retry backoff, in milliseconds. */
  static final long MAX_BACKOFF_MS = 30_000;

  /** The pause between re-reads of a record waiting for its parent. */
  static final long PARENT_BACKOFF_MS = 500;

  /** How long a partition waits, or a head fails, before it is logged at WARN, and how often. */
  static final Duration WARN_AFTER = Duration.ofMinutes(1);

  private static final long FIRST_BACKOFF_MS = 100;
  private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(10);

  /** The longest one attempt can take under the timeouts of 12.3 (about 110 s), with a margin. */
  private static final Duration LONGEST_ATTEMPT = Duration.ofSeconds(120);

  private static final Duration DEAD_LETTER_WAIT = Duration.ofSeconds(35);
  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeConsumer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** What to do with a record after one attempt. */
  private enum Outcome {
    HANDLED,
    RETRY,
    WAIT_FOR_PARENT,
    DEAD_LETTERED,
    DROPPED
  }

  /** One partition's state; the consumer thread owns it. */
  private static final class PartitionState {
    final WaitBudget budget;
    long resumeAtNanos = -1;
    long backoffMs;
    long headOffset = -1;
    boolean headAnsweredS4;
    long waitingSinceNanos = -1;
    long lastWaitingNanos = -1;
    long lastWarnNanos = -1;
    String missing = "";
    Optional<Integer> lastEpoch = Optional.empty();
    // The head record's state (12.3, 12.4), cleared only when the head changes.
    HeadRetry retry = new HeadRetry();
    String notification = "";
    Throwable lastFailure;
    ProviderResult kept;
    boolean keptCounted;
    boolean headerCounted;
    boolean ignoredResultCounted;
    long failingSinceNanos = -1;
    long lastFailWarnNanos = -1;

    PartitionState(WaitBudget budget) {
      this.budget = budget;
    }

    void newHead(long offset) {
      headOffset = offset;
      headAnsweredS4 = false;
      retry = new HeadRetry();
      notification = "";
      lastFailure = null;
      kept = null;
      keptCounted = false;
      headerCounted = false;
      ignoredResultCounted = false;
      failingSinceNanos = -1;
      lastFailWarnNanos = -1;
    }
  }

  /** A dead letter could not be sent; carries whether the broker refused it as too large. */
  private static final class DeadLetterNotSent extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeadLetterNotSent(Throwable cause) {
      super(cause);
    }
  }

  private final KafkaConsumer<String, byte[]> consumer;
  private final Producer<String, byte[]> deadLetters;
  private final String group;
  private final String deadLetterTopic;
  private final Handler handler;
  private final ParentWait parentWait;
  private final RetryBound retryBound;
  private final Clock wall;
  private final MeterRegistry meters;
  private final Thread thread;
  private final Map<TopicPartition, PartitionState> partitions = new HashMap<>();
  private final Set<TopicPartition> unresolved = new HashSet<>();
  private final AtomicLong deadLettered = new AtomicLong();
  private final AtomicLong waitWarnings = new AtomicLong();
  private final AtomicInteger runningGauge = new AtomicInteger(1);
  private final AtomicLong failingSinceNanos = new AtomicLong(-1);
  private volatile boolean running = true;
  private volatile boolean stopping;
  private volatile boolean consumerClosed;
  private long pollBackoffMs;

  /**
   * Starts consuming with the default wait budget and retry bound, and metrics kept in memory.
   *
   * @param bootstrapServers Kafka bootstrap servers
   * @param extra further consumer properties (security)
   * @param topic the topic
   * @param group the consumer group from {@code topics.yaml}
   * @param handler the handler
   */
  public EnvelopeConsumer(
      String bootstrapServers,
      Map<String, Object> extra,
      String topic,
      String group,
      Handler handler) {
    this(bootstrapServers, extra, topic, group, handler, ParentWait.DEFAULT, Clock.systemUTC());
  }

  /**
   * Starts consuming with the default retry bound, and metrics kept in memory.
   *
   * @param bootstrapServers Kafka bootstrap servers
   * @param extra further consumer properties (security)
   * @param topic the topic
   * @param group the consumer group from {@code topics.yaml}
   * @param handler the handler
   * @param parentWait the wait budget's parameters
   * @param wall the wall clock, for the checkpoint's time (W-e)
   */
  public EnvelopeConsumer(
      String bootstrapServers,
      Map<String, Object> extra,
      String topic,
      String group,
      Handler handler,
      ParentWait parentWait,
      Clock wall) {
    this(
        bootstrapServers,
        extra,
        topic,
        group,
        handler,
        parentWait,
        RetryBound.DEFAULT,
        new SimpleMeterRegistry(),
        wall);
  }

  /**
   * Starts consuming.
   *
   * @param bootstrapServers Kafka bootstrap servers
   * @param extra further consumer properties (security)
   * @param topic the topic
   * @param group the consumer group from {@code topics.yaml}
   * @param handler the handler
   * @param parentWait the wait budget's parameters
   * @param retryBound the bound on failures that may be the record's own, and the batch budget
   * @param meters where the consumer's metrics are registered (12.6)
   * @param wall the wall clock, for the checkpoint's time (W-e)
   */
  public EnvelopeConsumer(
      String bootstrapServers,
      Map<String, Object> extra,
      String topic,
      String group,
      Handler handler,
      ParentWait parentWait,
      RetryBound retryBound,
      MeterRegistry meters,
      Clock wall) {
    Map<String, Object> properties = new HashMap<>(extra);
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    // A reset is never silent (12.5): the consumer handles a missing or out-of-range offset itself.
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    this.consumer =
        new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
    Map<String, Object> producerProperties = new HashMap<>(extra);
    // Consumer-only settings (a test's max.poll.interval.ms) are not the producer's; shared ones,
    // security above all, stay.
    producerProperties
        .keySet()
        .removeIf(
            name ->
                ConsumerConfig.configNames().contains(name)
                    && !ProducerConfig.configNames().contains(name));
    producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
    producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, group + "-dlq");
    // Every external call has a timeout (12.3); delivery >= linger + request, as the client needs.
    producerProperties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 15_000);
    producerProperties.put(ProducerConfig.LINGER_MS_CONFIG, 0);
    producerProperties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
    producerProperties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000);
    this.deadLetters =
        new KafkaProducer<>(producerProperties, new StringSerializer(), new ByteArraySerializer());
    this.group = Objects.requireNonNull(group, "group");
    this.deadLetterTopic = topic + ".dlq";
    this.handler = Objects.requireNonNull(handler, "handler");
    this.parentWait = Objects.requireNonNull(parentWait, "parentWait");
    this.retryBound = Objects.requireNonNull(retryBound, "retryBound");
    this.meters = Objects.requireNonNull(meters, "meters");
    this.wall = Objects.requireNonNull(wall, "wall");
    Gauge.builder("fs_notify_consumer_running", runningGauge, AtomicInteger::get)
        .tag("group", group)
        .register(meters);
    Gauge.builder("fs_notify_head_failing_seconds", failingSinceNanos, EnvelopeConsumer::failing)
        .tag("group", group)
        .register(meters);
    this.thread = Thread.ofPlatform().name("consumer-" + group).daemon(true).unstarted(this::run);
    consumer.subscribe(List.of(topic), new Rebalance());
    thread.start();
  }

  /**
   * Records written to the dead-letter topic.
   *
   * @return the count since start-up
   */
  public long deadLettered() {
    return deadLettered.get();
  }

  /**
   * WARNs logged for partitions waiting a minute or more for a parent (W-d).
   *
   * @return the count since start-up
   */
  public long waitWarnings() {
    return waitWarnings.get();
  }

  /**
   * Whether the consumer's poll thread is running; false once an {@code Error} stopped it (12.2).
   *
   * @return true while it runs
   */
  public boolean running() {
    return runningGauge.get() == 1;
  }

  private static double failing(AtomicLong since) {
    long start = since.get();
    return start < 0 ? 0 : Math.max(0, System.nanoTime() - start) / 1e9;
  }

  private Counter counter(String name, String... tags) {
    List<String> all = new ArrayList<>(List.of("group", group));
    all.addAll(List.of(tags));
    return meters.counter(name, all.toArray(String[]::new));
  }

  private void run() {
    try {
      while (running) {
        try {
          cycle();
        } catch (WakeupException stopped) {
          return;
        } catch (RuntimeException unexpected) {
          // Nothing that is not an Error may end the poll thread (implementation-3, MAJOR 2):
          // a failure anywhere in a cycle, a reset's offset lookup included, is retried.
          LOG.warn("consumer {} cycle failed; it backs off and goes on", group, unexpected);
          pollBackoff();
        }
      }
    } catch (Error error) {
      stop(batch, error);
    }
  }

  /** The batch in hand, for {@link #stop} when an {@code Error} interrupts it. */
  private ConsumerRecords<String, byte[]> batch;

  /** One poll and the batch it returns. */
  private void cycle() {
    batch = null;
    ConsumerRecords<String, byte[]> records;
    try {
      resolveCommitted();
      resumeDue();
      records = consumer.poll(Duration.ofMillis(100));
      pollBackoffMs = 0;
    } catch (NoOffsetForPartitionException none) {
      // The client says these partitions have no committed offset: start at the log start.
      LOG.info("{} have no committed offset; starting at the log start", none.partitions());
      consumer.seekToBeginning(none.partitions());
      return;
    } catch (OffsetOutOfRangeException outOfRange) {
      outOfRange(outOfRange);
      return;
    }
    batch = records;
    try {
      handleBatch(records);
    } catch (WakeupException stopped) {
      throw stopped;
    } catch (RuntimeException e) {
      // Positions have advanced past the whole batch: seek back so that no fetched record is
      // skipped without being handled or dead-lettered (5-n3, I7f).
      LOG.warn("a batch could not be completed; its unhandled records will be re-read", e);
      seekBack(records);
    }
    publishFailing();
  }

  /**
   * An {@code Error} on the poll thread (12.2): nothing classifies it, and nothing may be skipped.
   * Seek every partition of the batch back to its first unhandled offset; commit those offsets only
   * if every seek succeeded; then stop, loudly, releasing the partitions to the group.
   */
  private void stop(ConsumerRecords<String, byte[]> records, Error error) {
    stopping = true;
    running = false;
    try {
      if (records != null && !records.isEmpty()) {
        Map<TopicPartition, OffsetAndMetadata> back = new HashMap<>();
        boolean every = true;
        Set<TopicPartition> assigned = consumer.assignment();
        for (TopicPartition partition : records.partitions()) {
          long offset = firstUnhandled(records, partition);
          if (!assigned.contains(partition)) {
            continue;
          }
          try {
            consumer.seek(partition, offset);
            PartitionState state = partitions.get(partition);
            if (state != null) {
              epochAt(records, partition, offset).ifPresent(e -> state.lastEpoch = Optional.of(e));
              back.put(partition, checkpoint(state, offset));
            }
          } catch (RuntimeException | Error seekFailed) {
            every = false;
          }
        }
        if (every && !back.isEmpty()) {
          consumer.commitSync(back, COMMIT_TIMEOUT);
        }
      }
    } catch (RuntimeException | Error commitFailed) {
      LOG.error("could not commit the unhandled offsets before stopping", commitFailed);
    } finally {
      meters.counter("fs_notify_consumer_errors_total", "group", group).increment();
      runningGauge.set(0);
      LOG.error(
          "consumer {} stopped on an Error; it is not restarted, and its partitions go to the"
              + " other members (docs/architecture/decision-fact-ordering.md, 12.2)",
          group,
          error);
      try {
        consumer.close(Duration.ofSeconds(5));
      } catch (RuntimeException | Error closeFailed) {
        LOG.error("could not close consumer {} after an Error", group, closeFailed);
      }
      consumerClosed = true;
    }
  }

  /** The leader epoch of the fetched record at {@code offset}, if the batch holds it (W-e3). */
  private static Optional<Integer> epochAt(
      ConsumerRecords<String, byte[]> records, TopicPartition partition, long offset) {
    for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
      if (record.offset() == offset) {
        return record.leaderEpoch();
      }
    }
    return Optional.empty();
  }

  /** Where each partition of the batch stands; read by {@link #seekBack} after a failure. */
  private final Map<TopicPartition, Long> firstUnhandled = new HashMap<>();

  private long firstUnhandled(ConsumerRecords<String, byte[]> records, TopicPartition partition) {
    Long offset = firstUnhandled.get(partition);
    return offset != null ? offset : records.records(partition).getFirst().offset();
  }

  private void handleBatch(ConsumerRecords<String, byte[]> records) {
    firstUnhandled.clear();
    Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
    long deadline = System.nanoTime() + retryBound.batchBudget().toNanos();
    boolean spent = false;
    for (TopicPartition partition : records.partitions()) {
      PartitionState state = state(partition);
      for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
        firstUnhandled.putIfAbsent(partition, record.offset());
        if (spent || System.nanoTime() - deadline >= 0) {
          // The batch's time budget is spent (12.3): leave the rest for the next poll.
          spent = true;
          consumer.seek(partition, record.offset());
          break;
        }
        state.lastEpoch = record.leaderEpoch();
        Outcome outcome = attempt(partition, state, record);
        if (outcome == Outcome.RETRY || outcome == Outcome.WAIT_FOR_PARENT) {
          consumer.seek(partition, record.offset());
          consumer.pause(Set.of(partition));
          long delay = outcome == Outcome.WAIT_FOR_PARENT ? PARENT_BACKOFF_MS : nextBackoff(state);
          state.resumeAtNanos = System.nanoTime() + Duration.ofMillis(delay).toNanos();
          commits.put(partition, checkpoint(state, record.offset()));
          break;
        }
        state.backoffMs = 0;
        firstUnhandled.put(partition, record.offset() + 1);
        state.newHead(record.offset() + 1);
        commits.put(partition, checkpoint(state, record.offset() + 1));
      }
    }
    if (!commits.isEmpty()) {
      consumer.commitSync(commits, COMMIT_TIMEOUT);
    }
  }

  private void seekBack(ConsumerRecords<String, byte[]> records) {
    Set<TopicPartition> assigned = consumer.assignment();
    for (TopicPartition partition : records.partitions()) {
      long offset = firstUnhandled(records, partition);
      if (assigned.contains(partition)) {
        try {
          consumer.seek(partition, offset);
        } catch (RuntimeException e) {
          LOG.warn("could not seek {} back to {}", partition, offset, e);
        }
      }
    }
  }

  /**
   * The commit for {@code offset}: the budget's spent time and, when {@code offset} is the head,
   * its retry state and kept result (W-e, W-e2), with the last leader epoch read (W-e3).
   */
  private OffsetAndMetadata checkpoint(PartitionState state, long offset) {
    long now = System.nanoTime();
    boolean head = state.headOffset == offset;
    Checkpoint checkpoint =
        new Checkpoint(
            offset,
            state.budget.spent(now).toMillis(),
            wall.instant().toEpochMilli(),
            head ? state.retry.count() : 0,
            head ? state.retry.millis(now) : 0,
            head ? state.kept : null);
    return new OffsetAndMetadata(offset, state.lastEpoch, checkpoint.text());
  }

  private long nextBackoff(PartitionState state) {
    state.backoffMs =
        state.backoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(state.backoffMs * 2, MAX_BACKOFF_MS);
    return state.backoffMs;
  }

  private void resumeDue() {
    long now = System.nanoTime();
    Set<TopicPartition> assigned = consumer.assignment();
    for (Map.Entry<TopicPartition, PartitionState> entry : partitions.entrySet()) {
      PartitionState state = entry.getValue();
      if (state.resumeAtNanos >= 0 && now >= state.resumeAtNanos) {
        state.resumeAtNanos = -1;
        if (assigned.contains(entry.getKey()) && !unresolved.contains(entry.getKey())) {
          consumer.resume(Set.of(entry.getKey()));
        }
      }
    }
  }

  /** Backs off only when poll itself fails, when nothing can flow. */
  private void pollBackoff() {
    pollBackoffMs =
        pollBackoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(pollBackoffMs * 2, MAX_BACKOFF_MS);
    long waited = 0;
    while (running && waited < pollBackoffMs) {
      try {
        Thread.sleep(Math.min(100, pollBackoffMs - waited));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      waited += 100;
    }
  }

  /** The longest-failing head over the owned partitions, for the gauge (12.6). */
  private void publishFailing() {
    long earliest = -1;
    for (PartitionState state : partitions.values()) {
      if (state.failingSinceNanos >= 0
          && (earliest < 0 || state.failingSinceNanos - earliest < 0)) {
        earliest = state.failingSinceNanos;
      }
    }
    failingSinceNanos.set(earliest);
  }

  /**
   * A committed offset the log no longer holds (12.5): below the log start it is counted and logged
   * as a loss; above the log end, or past a divergence, it resumes where the log now stands, never
   * at the log start.
   */
  private void outOfRange(OffsetOutOfRangeException e) {
    Map<TopicPartition, Long> named = e.offsetOutOfRangePartitions();
    boolean truncation = e instanceof LogTruncationException;
    Map<TopicPartition, OffsetAndMetadata> divergent =
        truncation ? ((LogTruncationException) e).divergentOffsets() : Map.of();
    Set<TopicPartition> all = new HashSet<>(named.keySet());
    all.addAll(divergent.keySet());
    all.retainAll(consumer.assignment());
    if (all.isEmpty()) {
      return;
    }
    Map<TopicPartition, Long> start = consumer.beginningOffsets(all, COMMIT_TIMEOUT);
    Map<TopicPartition, Long> end = consumer.endOffsets(all, COMMIT_TIMEOUT);
    for (TopicPartition partition : all) {
      PartitionState state = state(partition);
      String lost =
          state.kept == null
              ? ""
              : "; the head's kept result "
                  + state.kept.text()
                  + " (N "
                  + state.notification
                  + ") is never recorded";
      long fetch = named.getOrDefault(partition, -1L);
      long logStart = start.getOrDefault(partition, 0L);
      long logEnd = end.getOrDefault(partition, logStart);
      long target;
      if (divergent.containsKey(partition)) {
        target = divergent.get(partition).offset();
        LOG.error(
            "{}'s log diverged (an unclean election?); resuming at the divergent offset {}{}",
            partition,
            target,
            lost);
      } else if (!truncation && fetch >= 0 && fetch < logStart) {
        long skipped = logStart - fetch;
        counter("fs_notify_offsets_skipped_total").increment(skipped);
        LOG.error(
            "{}: committed offset {} is below the log start {}: {} records were deleted by"
                + " retention before they were handled and are lost{}",
            partition,
            fetch,
            logStart,
            skipped,
            lost);
        target = logStart;
      } else if (!truncation && fetch >= logStart && fetch <= logEnd) {
        // The log has grown back over the offset since the fetch: nothing is lost.
        target = fetch;
      } else {
        // Above the log end, or a divergence the client could not locate: never the log start,
        // which would re-read the whole retention (12.5).
        target = logEnd;
        LOG.error(
            "{}: committed offset {} is beyond the log end, or its divergence is unknown;"
                + " resuming at the log end {}{}",
            partition,
            fetch,
            target,
            lost);
      }
      consumer.seek(partition, target);
      state.newHead(target);
      state.resumeAtNanos = -1;
      if (!unresolved.contains(partition)) {
        consumer.resume(Set.of(partition));
      }
    }
  }

  private PartitionState state(TopicPartition partition) {
    return partitions.computeIfAbsent(
        partition,
        p ->
            new PartitionState(
                new WaitBudget(
                    parentWait.bound(), parentWait.drainRate(), Duration.ZERO, System.nanoTime())));
  }

  /** One attempt on the partition's head record, with the budget, retry state and WARNs kept. */
  private Outcome attempt(
      TopicPartition partition, PartitionState state, ConsumerRecord<String, byte[]> record) {
    if (state.headOffset != record.offset()) {
      state.newHead(record.offset());
    }
    long start = System.nanoTime();
    // An outage interval ends at the next attempt; it is not F4 time (12.3).
    state.retry.attemptStarts(start);
    Outcome outcome;
    FailureClass failure = null;
    try {
      outcome = handle(partition, state, record);
    } catch (RetryableFailure retry) {
      outcome = Outcome.RETRY;
      failure = retry.failureClass;
      state.lastFailure = retry.getCause();
    }
    long end = System.nanoTime();
    if (failure != null) {
      state.budget.failed(start);
      if (failure == FailureClass.OUTAGE) {
        state.retry.failedOutage(end);
      }
      counter(
              "fs_notify_retries_total",
              "class",
              failure == FailureClass.OUTAGE ? "outage" : "may_be_own")
          .increment();
      if (state.failingSinceNanos < 0) {
        state.failingSinceNanos = start;
      }
      warnIfFailing(partition, state, record, failure, end);
    } else {
      state.budget.succeeded(
          end,
          outcome == Outcome.WAIT_FOR_PARENT ? WaitBudget.Kind.WAITING : WaitBudget.Kind.DRAINING);
      state.failingSinceNanos = -1;
      state.lastFailWarnNanos = -1;
    }
    if (outcome == Outcome.WAIT_FOR_PARENT) {
      state.headAnsweredS4 = true;
    }
    warnIfLong(partition, state, record, outcome, failure != null, end);
    return outcome;
  }

  /** A failed attempt the record is re-read after, and its class (12.2). */
  private static final class RetryableFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final FailureClass failureClass;

    RetryableFailure(FailureClass failureClass, Throwable cause) {
      super(null, cause, false, false);
      this.failureClass = failureClass;
    }
  }

  private void warnIfFailing(
      TopicPartition partition,
      PartitionState state,
      ConsumerRecord<String, byte[]> record,
      FailureClass failure,
      long now) {
    long every = parentWait.warnAfter().toNanos();
    if (now - state.failingSinceNanos >= every
        && (state.lastFailWarnNanos < 0 || now - state.lastFailWarnNanos >= every)) {
      state.lastFailWarnNanos = now;
      LOG.warn(
          "{} offset {} (N {}) has been failing for {} s ({}, {} F4 failures, {} s of F4 time;"
              + " last failure: {}); see docs/architecture/decision-fact-ordering.md, 12.5",
          partition,
          record.offset(),
          state.notification,
          Duration.ofNanos(now - state.failingSinceNanos).toSeconds(),
          failure == FailureClass.OUTAGE ? "an outage" : "a failure that may be the record's own",
          state.retry.count(),
          state.retry.millis(now) / 1000,
          String.valueOf(state.lastFailure));
    }
  }

  private void warnIfLong(
      TopicPartition partition,
      PartitionState state,
      ConsumerRecord<String, byte[]> record,
      Outcome outcome,
      boolean failed,
      long now) {
    // "In a row" (W-d): across records and trips, as long as waiting follows waiting within a few
    // re-read intervals; a record handled normally, or a pause in the waiting, ends the run.
    boolean waiting = outcome == Outcome.WAIT_FOR_PARENT || (failed && state.headAnsweredS4);
    boolean continues =
        state.lastWaitingNanos >= 0
            && now - state.lastWaitingNanos <= Duration.ofMillis(4 * MAX_BACKOFF_MS).toNanos();
    if (outcome == Outcome.HANDLED || (waiting && !continues)) {
      state.waitingSinceNanos = -1;
      state.lastWarnNanos = -1;
    }
    if (!waiting) {
      return;
    }
    state.lastWaitingNanos = now;
    if (state.waitingSinceNanos < 0) {
      state.waitingSinceNanos = now;
      return;
    }
    long every = parentWait.warnAfter().toNanos();
    if (now - state.waitingSinceNanos >= every
        && (state.lastWarnNanos < 0 || now - state.lastWarnNanos >= every)) {
      state.lastWarnNanos = now;
      waitWarnings.incrementAndGet();
      counter("fs_notify_wait_warnings_total").increment();
      LOG.warn(
          "{} has had a record waiting for its parent in PostgreSQL for {} s (offset {}: {};"
              + " spent {} s of its budget); see docs/architecture/decision-fact-ordering.md",
          partition,
          Duration.ofNanos(now - state.waitingSinceNanos).toSeconds(),
          record.offset(),
          state.missing,
          state.budget.spent(now).toSeconds());
    }
  }

  /**
   * Classifies and handles one record (12.1, 12.2).
   *
   * @throws RetryableFailure when the record is to be re-read after a failed attempt
   */
  private Outcome handle(
      TopicPartition partition, PartitionState state, ConsumerRecord<String, byte[]> record) {
    Map<String, String> headers = headers(record);
    UUID institution;
    JsonNode payload;
    try {
      JsonNode envelope = JSON.readTree(record.value());
      String id = envelope.path("institution_id").asString("");
      if (!TransactionHeader.CANONICAL_UUID.matcher(id).matches()) {
        throw new IllegalArgumentException("institution_id is not a canonical UUID");
      }
      institution = UUID.fromString(id);
      payload = envelope.get("payload");
      if (payload == null || !payload.isObject()) {
        throw new IllegalArgumentException("payload is absent or not an object");
      }
      state.notification = payload.path("notification_id").asString("");
    } catch (RuntimeException malformed) {
      // Not an envelope this consumer will ever read: no amount of retrying changes it (12.1).
      return deadLetter(state, record, "malformed_envelope", malformed);
    }
    TransactionHeader transaction = TransactionHeader.of(record.headers(), TRANSACTION_ID_HEADER);
    if (transaction.state() == TransactionHeader.State.MALFORMED && !state.headerCounted) {
      state.headerCounted = true;
      counter("fs_notify_malformed_headers_total").increment();
      LOG.warn(
          "{} offset {} carries a malformed {}; it decides no send (5.1)",
          partition,
          record.offset(),
          TRANSACTION_ID_HEADER);
    }
    try {
      replayedResult(partition, state, record);
    } catch (MalformedPayloadException malformed) {
      return deadLetter(state, record, "malformed_envelope", malformed);
    }
    ResultKeeper keeper = result -> keep(partition, state, record, result);
    try {
      handler.handle(
          new Envelope(
              institution, payload, headers, transaction, Optional.ofNullable(state.kept), keeper));
      return Outcome.HANDLED;
    } catch (NotYetRecordedException notYet) {
      state.missing = String.valueOf(notYet.getMessage());
      return parentMissing(partition, state, record, notYet);
    } catch (NotTheKeptDecisionException notKept) {
      return deadLetter(state, record, "not_the_kept_decision", notKept);
    } catch (Exception e) {
      return failed(state, record, e);
    }
  }

  /** A failure of the handler, by class (12.2): dead-lettered, or re-read. */
  private Outcome failed(PartitionState state, ConsumerRecord<String, byte[]> record, Exception e) {
    FailureClass failure = FailureClass.of(e);
    switch (failure) {
      case MALFORMED -> {
        return deadLetter(
            state,
            record,
            e instanceof MalformedHeaderException ? "malformed_header" : "malformed_envelope",
            e);
      }
      case PERMANENT -> {
        String reason = permanentVault(e) ? "permanent_vault_failure" : "rejected_by_the_database";
        return deadLetter(state, record, reason, e);
      }
      case OUTAGE -> {
        LOG.warn("a record could not be handled during an outage; it will be re-read", e);
        throw new RetryableFailure(FailureClass.OUTAGE, e);
      }
      default -> {
        countF4(state);
        if (bounded(state)) {
          LOG.warn("a record kept failing past the bound of 12.3; it is dead-lettered", e);
          return deadLetter(state, record, "handler_failure", e);
        }
        LOG.warn("a record could not be handled; it will be re-read up to the bound of 12.3", e);
        throw new RetryableFailure(FailureClass.MAY_BE_OWN, e);
      }
    }
  }

  private static boolean permanentVault(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof io.github.mariusbayizere.fraudshield.notify.vault.VaultException vault
          && vault.permanent()) {
        return true;
      }
    }
    return false;
  }

  private void countF4(PartitionState state) {
    state.retry.failedOwn(System.nanoTime());
  }

  private boolean bounded(PartitionState state) {
    return state.retry.bounded(
        retryBound.attempts(), retryBound.time().toMillis(), System.nanoTime());
  }

  /**
   * A replayed copy's {@code fs-kept-result} (12.1, 12.4): honoured only on a record carrying
   * {@code fs-replayed}, as exactly one value that parses; ignored, logged and counted on any
   * other.
   *
   * @throws MalformedPayloadException when a replayed copy's header does not parse
   */
  private void replayedResult(
      TopicPartition partition, PartitionState state, ConsumerRecord<String, byte[]> record) {
    List<String> values = new ArrayList<>();
    for (Header header : record.headers().headers(KEPT_RESULT_HEADER)) {
      values.add(
          header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8));
    }
    if (values.isEmpty()) {
      return;
    }
    if (!replayed(record)) {
      if (!state.ignoredResultCounted) {
        state.ignoredResultCounted = true;
        counter("fs_notify_ignored_kept_results_total").increment();
        LOG.warn(
            "{} offset {} carries {} but was not replayed; the header is ignored",
            partition,
            record.offset(),
            KEPT_RESULT_HEADER);
      }
      return;
    }
    String first = values.getFirst();
    Optional<ProviderResult> result =
        values.stream().allMatch(v -> v != null && v.equals(first))
            ? ProviderResult.parse(first)
            : Optional.empty();
    if (result.isEmpty()) {
      throw new MalformedPayloadException(
          "a replayed record's " + KEPT_RESULT_HEADER + " is not one result", null);
    }
    if (state.kept == null) {
      state.kept = result.get();
      LOG.info(
          "{} offset {} (N {}): a replayed copy carries its provider result {}; it is recorded,"
              + " not sent",
          partition,
          record.offset(),
          state.notification,
          result.get().text());
    }
  }

  /**
   * Keeps a provider result for the head record and commits it before the outcome is written
   * (12.4). A failed commit is not a failed attempt: the outcome write goes on, and the result
   * rides on every later commit of the partition until one succeeds or the head changes.
   */
  private void keep(
      TopicPartition partition,
      PartitionState state,
      ConsumerRecord<String, byte[]> record,
      ProviderResult result) {
    Objects.requireNonNull(result, "result");
    state.kept = result;
    if (result.mayBeDelivered() && !state.keptCounted) {
      state.keptCounted = true;
      counter("fs_notify_sms_outcome_unknown_total").increment();
      LOG.error(
          "{} offset {} (N {}): the SMS provider's answer could not be read; the SMS may have"
              + " been delivered and is recorded FAILED",
          partition,
          record.offset(),
          state.notification);
    }
    try {
      consumer.commitSync(
          Map.of(
              partition,
              new OffsetAndMetadata(
                  record.offset(), record.leaderEpoch(), checkpointText(state, record.offset()))),
          COMMIT_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.warn(
          "{} offset {}: the commit that keeps the provider result failed; later commits carry it",
          partition,
          record.offset(),
          e);
    }
  }

  private String checkpointText(PartitionState state, long offset) {
    return checkpoint(state, offset).metadata();
  }

  /** S4: wait within the budget, or dead-letter for replay (W-c, D-d). */
  private Outcome parentMissing(
      TopicPartition partition,
      PartitionState state,
      ConsumerRecord<String, byte[]> record,
      NotYetRecordedException notYet) {
    if (replayed(record)) {
      // A replayed record never waits: replayed before its parent exists, it goes straight back.
      return deadLetter(state, record, "parent_not_recorded", notYet);
    }
    boolean first = !state.headAnsweredS4;
    if (!first && state.budget.full(System.nanoTime())) {
      LOG.warn(
          "{} is tripped: its wait budget of {} is spent; offset {} is dead-lettered for replay",
          partition,
          parentWait.bound(),
          record.offset());
      return deadLetter(state, record, "parent_not_recorded", notYet);
    }
    // Waits: while tripped, a record's first S4 answer earns exactly one grace re-read.
    LOG.debug("a record's parent is not in PostgreSQL yet; it will be re-read", notYet);
    return Outcome.WAIT_FOR_PARENT;
  }

  /** Whether a record was replayed: it carries {@code fs-replayed}, whatever its value. */
  private static boolean replayed(ConsumerRecord<String, byte[]> record) {
    return record.headers().lastHeader(REPLAYED_HEADER) != null;
  }

  private static Map<String, String> headers(ConsumerRecord<String, byte[]> record) {
    Map<String, String> headers = new HashMap<>();
    for (Header header : record.headers()) {
      if (header.value() != null) {
        headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
      }
    }
    return headers;
  }

  /**
   * Writes a record to {@code <topic>.dlq} with its original headers and the reason (D-a); while a
   * provider result is kept, the reason is {@code outcome_unrecorded} and the copy carries the
   * result (12.4). A send the broker refuses as too large is retried once without {@code
   * fs-dlq-error}; one that still fails is the record's own failure, and at the bound the record is
   * dropped, loudly (12.2).
   *
   * @throws RetryableFailure when the send fails and the record is re-read instead
   */
  private Outcome deadLetter(
      PartitionState state, ConsumerRecord<String, byte[]> record, String reason, Exception why) {
    String actual = state.kept != null ? "outcome_unrecorded" : reason;
    Throwable sendFailure;
    try {
      send(record, actual, why, state.kept, true);
      return recordDeadLetter(state, record, actual, why);
    } catch (DeadLetterNotSent notSent) {
      sendFailure = notSent.getCause();
    }
    if (FailureClass.tooLarge(sendFailure)) {
      try {
        send(record, actual, why, state.kept, false);
        return recordDeadLetter(state, record, actual, why);
      } catch (DeadLetterNotSent stillNot) {
        sendFailure = stillNot.getCause();
      }
    }
    if (FailureClass.ofDeadLetterSend(sendFailure) == FailureClass.OUTAGE) {
      LOG.warn("a record could not be dead-lettered; it will be re-read", sendFailure);
      throw new RetryableFailure(FailureClass.OUTAGE, sendFailure);
    }
    // Too large even without fs-dlq-error: the record's own failure (F4), dropped at the bound.
    if (state.retry.count() == 0 || !bounded(state)) {
      countF4(state);
    }
    if (bounded(state)) {
      return drop(state, record, actual, why);
    }
    LOG.warn("a record's dead letter is too large to send; it will be re-read", sendFailure);
    throw new RetryableFailure(FailureClass.MAY_BE_OWN, sendFailure);
  }

  private void send(
      ConsumerRecord<String, byte[]> record,
      String reason,
      Exception why,
      ProviderResult kept,
      boolean withError) {
    ProducerRecord<String, byte[]> dead =
        new ProducerRecord<>(deadLetterTopic, record.key(), record.value());
    for (Header header : record.headers()) {
      if (!header.key().startsWith(DLQ_HEADER_PREFIX)) {
        dead.headers().add(header.key(), header.value());
      }
    }
    dead.headers().add("fs-dlq-reason", reason.getBytes(StandardCharsets.UTF_8));
    if (withError) {
      dead.headers().add("fs-dlq-error", String.valueOf(why).getBytes(StandardCharsets.UTF_8));
    }
    if (kept != null) {
      dead.headers().add(DLQ_RESULT_HEADER, kept.text().getBytes(StandardCharsets.UTF_8));
    }
    dead.headers()
        .add("fs-dlq-topic", record.topic().getBytes(StandardCharsets.UTF_8))
        .add(
            "fs-dlq-offset",
            (record.partition() + ":" + record.offset()).getBytes(StandardCharsets.UTF_8))
        .add("fs-dlq-at", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
    try {
      deadLetters.send(dead).get(DEAD_LETTER_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DeadLetterNotSent(interrupted);
    } catch (ExecutionException failed) {
      throw new DeadLetterNotSent(failed.getCause() == null ? failed : failed.getCause());
    } catch (RuntimeException | TimeoutException notSent) {
      throw new DeadLetterNotSent(notSent);
    }
  }

  private Outcome recordDeadLetter(
      PartitionState state, ConsumerRecord<String, byte[]> record, String reason, Exception why) {
    deadLettered.incrementAndGet();
    counter("fs_notify_dead_letters_total", "reason", reason).increment();
    LOG.error(
        "offset {} (N {}) was dead-lettered to {} ({}{}); the consumer commits past it",
        record.offset(),
        state.notification,
        deadLetterTopic,
        reason,
        state.kept == null ? "" : ", provider result " + state.kept.text(),
        why);
    return Outcome.DEAD_LETTERED;
  }

  /**
   * The one case a record leaves without being handled or dead-lettered (12.2): its dead letter is
   * too large to send. Everything needed to find it again is logged.
   */
  private Outcome drop(
      PartitionState state, ConsumerRecord<String, byte[]> record, String reason, Exception why) {
    List<String> names = new ArrayList<>();
    for (Header header : record.headers()) {
      names.add(
          header.key()
              + "="
              + (header.value() == null
                  ? "null"
                  : new String(header.value(), StandardCharsets.UTF_8)));
    }
    counter("fs_notify_dropped_total").increment();
    LOG.error(
        "a record is DROPPED: its dead letter is too large to send. topic {} partition {} offset"
            + " {} key {} headers {} value sha256 {} length {} reason {} N {} kept result {}",
        record.topic(),
        record.partition(),
        record.offset(),
        record.key(),
        names,
        sha256(record.value()),
        record.value() == null ? 0 : record.value().length,
        reason,
        state.notification,
        state.kept == null ? "none" : state.kept.text(),
        why);
    return Outcome.DROPPED;
  }

  private static String sha256(byte[] value) {
    if (value == null) {
      return "none";
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }

  /**
   * Retries the committed-offset lookups that failed during an assignment: those partitions stay
   * paused, because a failed lookup must never be read as "no checkpoint" (W-e).
   */
  private void resolveCommitted() {
    if (unresolved.isEmpty()) {
      return;
    }
    Set<TopicPartition> pending = new HashSet<>(unresolved);
    pending.retainAll(consumer.assignment());
    unresolved.retainAll(pending);
    if (pending.isEmpty()) {
      return;
    }
    Map<TopicPartition, OffsetAndMetadata> committed;
    try {
      committed = consumer.committed(pending, COMMIT_TIMEOUT);
    } catch (RuntimeException e) {
      LOG.warn("still cannot read the committed checkpoints of {}; they stay paused", pending, e);
      return;
    }
    resumeFrom(pending, committed);
    unresolved.removeAll(pending);
    consumer.resume(pending);
  }

  private void resumeFrom(
      Collection<TopicPartition> assigned, Map<TopicPartition, OffsetAndMetadata> committed) {
    long now = System.nanoTime();
    long epochMillis = wall.instant().toEpochMilli();
    for (TopicPartition partition : assigned) {
      OffsetAndMetadata offset = committed.get(partition);
      Optional<Checkpoint> checkpoint = Optional.empty();
      if (offset != null) {
        checkpoint = Checkpoint.parse(offset.metadata());
        if (checkpoint.isEmpty() && !offset.metadata().isEmpty()) {
          LOG.warn("{}'s committed metadata does not parse; read as none (W-e2)", partition);
        }
      }
      WaitBudget.Resumed resumed =
          offset == null
              ? new WaitBudget.Resumed(Duration.ZERO, false)
              : WaitBudget.resumeFrom(
                  checkpoint, offset.offset(), epochMillis, parentWait.drainRate());
      if (resumed.futureCheckpoint()) {
        LOG.warn("{}'s wait checkpoint lies in the future (clock skew); clamped", partition);
      }
      PartitionState state =
          new PartitionState(
              new WaitBudget(parentWait.bound(), parentWait.drainRate(), resumed.spent(), now));
      if (offset != null) {
        state.lastEpoch = offset.leaderEpoch();
        state.newHead(offset.offset());
        if (checkpoint.isPresent() && checkpoint.get().offset() == offset.offset()) {
          Checkpoint c = checkpoint.get();
          state.retry = new HeadRetry(c.f4Count(), c.f4Millis(), now);
          state.kept = c.result();
        }
      }
      partitions.put(partition, state);
    }
  }

  /** Keeps each partition's state across owners, and drops what a revoked partition held (W-e). */
  private final class Rebalance implements ConsumerRebalanceListener {

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
      Map<TopicPartition, OffsetAndMetadata> checkpoints = new HashMap<>();
      for (TopicPartition partition : revoked) {
        PartitionState state = partitions.remove(partition);
        unresolved.remove(partition);
        if (state == null || stopping || (Thread.currentThread() != thread && thread.isAlive())) {
          // Stopping on an Error commits nothing here: the stop already committed what is safe.
          // Off the poll thread while it still runs (a close that outlived its wait), the state
          // may be mid-update, so nothing is committed either (implementation-3, m5).
          continue;
        }
        try {
          long position = consumer.position(partition);
          OffsetAndMetadata checkpoint = checkpoint(state, position);
          if (!checkpoint.metadata().isEmpty()) {
            checkpoints.put(partition, checkpoint);
          }
        } catch (RuntimeException e) {
          LOG.warn("could not read the position of revoked {}", partition, e);
        }
      }
      if (!checkpoints.isEmpty()) {
        try {
          consumer.commitSync(checkpoints, COMMIT_TIMEOUT);
        } catch (RuntimeException e) {
          LOG.warn("could not checkpoint the state of revoked partitions", e);
        }
      }
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> lost) {
      // Not ours any more: nothing may be committed; the last checkpoint stands.
      lost.forEach(partitions::remove);
      lost.forEach(unresolved::remove);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> assigned) {
      if (assigned.isEmpty()) {
        return;
      }
      Map<TopicPartition, OffsetAndMetadata> committed;
      try {
        committed = consumer.committed(Set.copyOf(assigned), COMMIT_TIMEOUT);
      } catch (RuntimeException e) {
        // Never read a failed lookup as "no checkpoint": pause until it succeeds.
        LOG.warn("could not read the committed checkpoints of {}; pausing them", assigned, e);
        unresolved.addAll(assigned);
        consumer.pause(assigned);
        return;
      }
      resumeFrom(assigned, committed);
    }
  }

  /**
   * Stops consuming as a crashed process would: the thread ends, but the group is not left and
   * nothing is revoked or committed, so the partitions stay with this member until its session
   * times out. For tests of W-e; {@link #close()} still releases the clients afterwards.
   */
  void crash() {
    running = false;
    if (!consumerClosed) {
      consumer.wakeup();
    }
    try {
      thread.join(Duration.ofSeconds(10).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    running = false;
    if (!consumerClosed) {
      consumer.wakeup();
    }
    // Wait as long as a batch can take: its budget, one attempt and the end-of-batch commit
    // (implementation-3, m5); a shorter wait would close the consumer under a running attempt.
    Duration wait = retryBound.batchBudget().plus(LONGEST_ATTEMPT).plus(COMMIT_TIMEOUT);
    try {
      thread.join(wait.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) {
      // The listener will commit nothing (it checks the thread), so nothing passes an
      // unhandled record; the next owner resumes from the last commit.
      LOG.error(
          "consumer {} did not stop within {}; closing without a final checkpoint", group, wait);
      stopping = true;
    }
    if (!consumerClosed) {
      try {
        consumer.close();
      } catch (RuntimeException closeFailed) {
        LOG.error("could not close consumer {}", group, closeFailed);
      }
      consumerClosed = true;
    }
    runningGauge.set(0);
    deadLetters.close(Duration.ofSeconds(5));
  }
}
