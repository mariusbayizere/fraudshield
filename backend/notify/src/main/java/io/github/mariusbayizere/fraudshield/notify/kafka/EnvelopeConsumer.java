package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
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
 * <p>A record the handler can never accept - a malformed envelope, a missing institution, a row the
 * database refuses, an SMS intent that is not the kept decision's - is written to {@code
 * <topic>.dlq} (C.3, ADR 0012) with its original headers and the reason, and committed past. A
 * transient failure is re-read on its partition's own exponential backoff, up to {@value
 * #MAX_BACKOFF_MS} ms, and never dead-lettered.
 *
 * <p>An SMS intent whose parent has not reached PostgreSQL yet ({@link NotYetRecordedException})
 * waits, re-read every {@value #PARENT_BACKOFF_MS} ms, within its partition's {@link WaitBudget}; a
 * partition whose budget is full dead-letters such intents ({@code parent_not_recorded}) for replay
 * (docs/architecture/decision-fact-ordering.md, sections 6 and 7). Every re-read pauses only its
 * partition: the consumer thread never sleeps for one, so the other partitions keep flowing.
 */
public final class EnvelopeConsumer implements AutoCloseable {

  /** Handles one envelope; throws to have the record re-read or dead-lettered. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Handles an envelope.
     *
     * @param envelope the envelope
     * @throws SQLException when the record should be re-read (a transient state) or dead-lettered
     */
    void handle(Envelope envelope) throws SQLException;
  }

  /**
   * One envelope as the handler sees it.
   *
   * @param institutionId institution from the envelope
   * @param payload the envelope's payload
   * @param headers the record's headers, last value per name
   */
  public record Envelope(UUID institutionId, JsonNode payload, Map<String, String> headers) {

    /** Copies the headers. */
    public Envelope {
      headers = Map.copyOf(headers);
    }

    /**
     * The transaction a customer SMS intent belongs to, when its record carries it.
     *
     * @return the transaction id
     */
    public Optional<UUID> transactionId() {
      String value = headers.get(TRANSACTION_ID_HEADER);
      try {
        return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
      } catch (IllegalArgumentException malformed) {
        return Optional.empty();
      }
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

  /** The header naming a customer SMS intent's transaction. */
  public static final String TRANSACTION_ID_HEADER = "fs-transaction-id";

  /** The header a replayed record carries: how many times it has been replayed. */
  public static final String REPLAYED_HEADER = "fs-replayed";

  /** The prefix of the headers a dead-lettered copy gains. */
  public static final String DLQ_HEADER_PREFIX = "fs-dlq-";

  /** The ceiling of the retry backoff, in milliseconds. */
  static final long MAX_BACKOFF_MS = 30_000;

  /** The pause between re-reads of a record waiting for its parent. */
  static final long PARENT_BACKOFF_MS = 500;

  /** How long a partition waits before the wait is logged at WARN, and how often. */
  static final Duration WARN_AFTER = Duration.ofMinutes(1);

  private static final long FIRST_BACKOFF_MS = 100;
  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeConsumer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** What to do with a record after one attempt. */
  private enum Outcome {
    HANDLED,
    RETRY,
    WAIT_FOR_PARENT,
    DEAD_LETTERED
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

    PartitionState(WaitBudget budget) {
      this.budget = budget;
    }
  }

  private final KafkaConsumer<String, byte[]> consumer;
  private final Producer<String, byte[]> deadLetters;
  private final String deadLetterTopic;
  private final Handler handler;
  private final ParentWait parentWait;
  private final Clock wall;
  private final Thread thread;
  private final Map<TopicPartition, PartitionState> partitions = new HashMap<>();
  private final AtomicLong deadLettered = new AtomicLong();
  private final AtomicLong waitWarnings = new AtomicLong();
  private volatile boolean running = true;
  private long pollBackoffMs;

  /**
   * Starts consuming with the default wait budget.
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
   * Starts consuming.
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
    Map<String, Object> properties = new HashMap<>(extra);
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
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
    this.deadLetters =
        new KafkaProducer<>(producerProperties, new StringSerializer(), new ByteArraySerializer());
    this.deadLetterTopic = topic + ".dlq";
    this.handler = Objects.requireNonNull(handler, "handler");
    this.parentWait = Objects.requireNonNull(parentWait, "parentWait");
    this.wall = Objects.requireNonNull(wall, "wall");
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

  private void run() {
    while (running) {
      ConsumerRecords<String, byte[]> records;
      try {
        resumeDue();
        records = consumer.poll(Duration.ofMillis(100));
        pollBackoffMs = 0;
      } catch (WakeupException stopping) {
        return;
      } catch (RuntimeException e) {
        LOG.warn("consumer poll failed; continuing", e);
        pollBackoff();
        continue;
      }
      try {
        handleBatch(records);
      } catch (WakeupException stopping) {
        return;
      } catch (RuntimeException e) {
        // Positions have advanced past the whole batch: seek back so that no fetched record is
        // skipped without being handled or dead-lettered (5-n3, I7f).
        LOG.warn("a batch could not be completed; its unhandled records will be re-read", e);
        seekBack(records);
      }
    }
  }

  /** Where each partition of the batch stands; read by {@link #seekBack} after a failure. */
  private final Map<TopicPartition, Long> firstUnhandled = new HashMap<>();

  private void handleBatch(ConsumerRecords<String, byte[]> records) {
    firstUnhandled.clear();
    Map<TopicPartition, OffsetAndMetadata> commits = new HashMap<>();
    for (TopicPartition partition : records.partitions()) {
      PartitionState state = state(partition);
      for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
        firstUnhandled.putIfAbsent(partition, record.offset());
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
        commits.put(partition, checkpoint(state, record.offset() + 1));
      }
    }
    if (!commits.isEmpty()) {
      consumer.commitSync(commits);
    }
  }

  private void seekBack(ConsumerRecords<String, byte[]> records) {
    Set<TopicPartition> assigned = consumer.assignment();
    for (TopicPartition partition : records.partitions()) {
      Long offset = firstUnhandled.get(partition);
      if (offset == null) {
        offset = records.records(partition).getFirst().offset();
      }
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
   * The commit for {@code offset}, carrying the budget's checkpoint while anything is spent (W-e).
   */
  private OffsetAndMetadata checkpoint(PartitionState state, long offset) {
    String metadata =
        state.budget.checkpoint(offset, System.nanoTime(), wall.instant().toEpochMilli());
    return new OffsetAndMetadata(offset, metadata);
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
        if (assigned.contains(entry.getKey())) {
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

  private PartitionState state(TopicPartition partition) {
    return partitions.computeIfAbsent(
        partition,
        p ->
            new PartitionState(
                new WaitBudget(
                    parentWait.bound(), parentWait.drainRate(), Duration.ZERO, System.nanoTime())));
  }

  /** One attempt on the partition's head record, with the budget and WARN kept (W-b to W-d). */
  private Outcome attempt(
      TopicPartition partition, PartitionState state, ConsumerRecord<String, byte[]> record) {
    if (state.headOffset != record.offset()) {
      state.headOffset = record.offset();
      state.headAnsweredS4 = false;
    }
    long start = System.nanoTime();
    Outcome outcome;
    boolean failed;
    try {
      outcome = handle(partition, state, record);
      failed = outcome == Outcome.RETRY;
    } catch (DeadLetterNotSent notSent) {
      outcome = Outcome.RETRY;
      failed = true;
    }
    long end = System.nanoTime();
    if (failed) {
      state.budget.failed(start);
    } else {
      state.budget.succeeded(
          end,
          outcome == Outcome.WAIT_FOR_PARENT ? WaitBudget.Kind.WAITING : WaitBudget.Kind.DRAINING);
    }
    if (outcome == Outcome.WAIT_FOR_PARENT) {
      state.headAnsweredS4 = true;
    }
    warnIfLong(partition, state, record, outcome, end);
    return outcome;
  }

  private void warnIfLong(
      TopicPartition partition,
      PartitionState state,
      ConsumerRecord<String, byte[]> record,
      Outcome outcome,
      long now) {
    // "In a row" (W-d): across records and trips, as long as waiting follows waiting within a few
    // re-read intervals; a record handled normally, or a pause in the waiting, ends the run.
    boolean waiting =
        outcome == Outcome.WAIT_FOR_PARENT || (outcome == Outcome.RETRY && state.headAnsweredS4);
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

  private Outcome handle(
      TopicPartition partition, PartitionState state, ConsumerRecord<String, byte[]> record) {
    Map<String, String> headers = headers(record);
    UUID institution;
    JsonNode payload;
    try {
      JsonNode envelope = JSON.readTree(record.value());
      institution = UUID.fromString(envelope.get("institution_id").asString());
      payload = envelope.get("payload");
    } catch (RuntimeException malformed) {
      // Not an envelope this consumer will ever read: no amount of retrying changes it.
      return deadLetter(record, "malformed_envelope", malformed);
    }
    try {
      handler.handle(new Envelope(institution, payload, headers));
      return Outcome.HANDLED;
    } catch (NotYetRecordedException notYet) {
      state.missing = String.valueOf(notYet.getMessage());
      return parentMissing(partition, state, record, headers, notYet);
    } catch (MalformedPayloadException malformed) {
      // Well-formed envelope, unreadable payload: no retry changes it.
      return deadLetter(record, "malformed_envelope", malformed);
    } catch (NotTheKeptDecisionException notKept) {
      return deadLetter(record, "not_the_kept_decision", notKept);
    } catch (SQLException e) {
      if (transientError(e)) {
        LOG.warn("a record could not be handled yet; it will be re-read", e);
        return Outcome.RETRY;
      }
      return deadLetter(record, "rejected_by_the_database", e);
    } catch (RuntimeException e) {
      if (permanentVaultFailure(e)) {
        // A vault row that does not verify: retrying would stall every customer behind this record
        // on the partition, for ever (the independent review, 2026-09-23). A key this instance
        // does not hold yet is not permanent and is retried (ADR 0069, rolling rotation).
        return deadLetter(record, "permanent_vault_failure", e);
      }
      // A dependency that is down, a timeout: the record itself may be fine.
      LOG.warn("a record could not be handled yet; it will be re-read", e);
      return Outcome.RETRY;
    }
  }

  /** S4: wait within the budget, or dead-letter for replay (W-c, D-d). */
  private Outcome parentMissing(
      TopicPartition partition,
      PartitionState state,
      ConsumerRecord<String, byte[]> record,
      Map<String, String> headers,
      NotYetRecordedException notYet) {
    if (headers.containsKey(REPLAYED_HEADER)) {
      // A replayed record never waits: replayed before its parent exists, it goes straight back.
      return deadLetter(record, "parent_not_recorded", notYet);
    }
    boolean first = !state.headAnsweredS4;
    if (!first && state.budget.full(System.nanoTime())) {
      LOG.warn(
          "{} is tripped: its wait budget of {} is spent; offset {} is dead-lettered for replay",
          partition,
          parentWait.bound(),
          record.offset());
      return deadLetter(record, "parent_not_recorded", notYet);
    }
    // Waits: while tripped, a record's first S4 answer earns exactly one grace re-read.
    LOG.debug("a record's parent is not in PostgreSQL yet; it will be re-read", notYet);
    return Outcome.WAIT_FOR_PARENT;
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

  private static boolean permanentVaultFailure(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof io.github.mariusbayizere.fraudshield.notify.vault.VaultException vault
          && vault.permanent()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Transient SQLSTATE classes: connection (08), serialisation and deadlock (40), out of resources
   * (53), operator intervention (57) and system error (58); an absent state is treated as
   * transient, as {@code PostgresSink} does.
   */
  private static boolean transientError(SQLException e) {
    String state = e.getSQLState();
    return state == null
        || state.startsWith("08")
        || state.startsWith("40")
        || state.startsWith("53")
        || state.startsWith("57")
        || state.startsWith("58");
  }

  /** The dead-letter send failed: the attempt failed, and the record is re-read (W-e). */
  private static final class DeadLetterNotSent extends RuntimeException {
    private static final long serialVersionUID = 1L;

    DeadLetterNotSent(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Writes a record to {@code <topic>.dlq} with its original headers and the reason (D-a).
   *
   * @throws DeadLetterNotSent when the send fails, so the record is re-read instead
   */
  private Outcome deadLetter(ConsumerRecord<String, byte[]> record, String reason, Exception why) {
    ProducerRecord<String, byte[]> dead =
        new ProducerRecord<>(deadLetterTopic, record.key(), record.value());
    for (Header header : record.headers()) {
      if (!header.key().startsWith(DLQ_HEADER_PREFIX)) {
        dead.headers().add(header.key(), header.value());
      }
    }
    dead.headers()
        .add("fs-dlq-reason", reason.getBytes(StandardCharsets.UTF_8))
        .add("fs-dlq-error", String.valueOf(why).getBytes(StandardCharsets.UTF_8))
        .add("fs-dlq-topic", record.topic().getBytes(StandardCharsets.UTF_8))
        .add(
            "fs-dlq-offset",
            (record.partition() + ":" + record.offset()).getBytes(StandardCharsets.UTF_8))
        .add("fs-dlq-at", Instant.now().toString().getBytes(StandardCharsets.UTF_8));
    try {
      deadLetters
          .send(dead)
          .get(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new DeadLetterNotSent(interrupted);
    } catch (RuntimeException
        | java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException notSent) {
      LOG.warn("a record could not be dead-lettered; it will be re-read", notSent);
      throw new DeadLetterNotSent(notSent);
    }
    deadLettered.incrementAndGet();
    LOG.error(
        "a record was dead-lettered to {} ({}); the consumer commits past it",
        deadLetterTopic,
        reason,
        why);
    return Outcome.DEAD_LETTERED;
  }

  /** Keeps each partition's budget across owners, and drops what a revoked partition held (W-e). */
  private final class Rebalance implements ConsumerRebalanceListener {

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
      Map<TopicPartition, OffsetAndMetadata> checkpoints = new HashMap<>();
      for (TopicPartition partition : revoked) {
        PartitionState state = partitions.remove(partition);
        if (state == null) {
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
          consumer.commitSync(checkpoints);
        } catch (RuntimeException e) {
          LOG.warn("could not checkpoint the wait budget of revoked partitions", e);
        }
      }
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> lost) {
      // Not ours any more: nothing may be committed; the last checkpoint stands.
      lost.forEach(partitions::remove);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> assigned) {
      if (assigned.isEmpty()) {
        return;
      }
      Map<TopicPartition, OffsetAndMetadata> committed;
      try {
        committed = consumer.committed(Set.copyOf(assigned));
      } catch (RuntimeException e) {
        LOG.warn("could not read the committed wait budgets; starting from zero", e);
        committed = Map.of();
      }
      long now = System.nanoTime();
      long epochMillis = wall.instant().toEpochMilli();
      for (TopicPartition partition : assigned) {
        OffsetAndMetadata offset = committed.get(partition);
        WaitBudget.Resumed resumed =
            offset == null
                ? new WaitBudget.Resumed(Duration.ZERO, false)
                : WaitBudget.resume(
                    offset.metadata(), offset.offset(), epochMillis, parentWait.drainRate());
        if (resumed.futureCheckpoint()) {
          LOG.warn("{}'s wait checkpoint lies in the future (clock skew); clamped", partition);
        }
        partitions.put(
            partition,
            new PartitionState(
                new WaitBudget(parentWait.bound(), parentWait.drainRate(), resumed.spent(), now)));
      }
    }
  }

  /**
   * Stops consuming as a crashed process would: the thread ends, but the group is not left and
   * nothing is revoked or committed, so the partitions stay with this member until its session
   * times out. For tests of W-e; {@link #close()} still releases the clients afterwards.
   */
  void crash() {
    running = false;
    consumer.wakeup();
    try {
      thread.join(Duration.ofSeconds(10).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    running = false;
    consumer.wakeup();
    try {
      thread.join(Duration.ofSeconds(10).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    consumer.close();
    deadLetters.close(Duration.ofSeconds(5));
  }
}
