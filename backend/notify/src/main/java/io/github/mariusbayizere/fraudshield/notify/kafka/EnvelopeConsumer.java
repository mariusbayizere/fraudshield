package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
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
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes one topic's envelopes (ADR 0012) as one consumer group and hands each payload, with the
 * institution the producing service set from its authenticated principal, to a handler. Offsets are
 * committed only after the handler returns, so a failing handler re-reads the record rather than
 * drop it; handlers are idempotent on the event's own id.
 *
 * <p>A record the handler can never accept - a malformed envelope, a missing institution, a row the
 * database refuses - is written to {@code <topic>.dlq} (C.3, ADR 0012) with the reason in its
 * headers and committed past, because retrying it forever would stall every customer SMS or webhook
 * behind it on that partition. A transient failure (the database or Redis is down) is retried with
 * exponential backoff up to {@value #MAX_BACKOFF_MS} ms and never dead-lettered, so no record is
 * lost to an outage.
 *
 * <p>A record whose parent fact has not reached PostgreSQL yet ({@link NotYetRecordedException}) is
 * re-read every {@value #PARENT_BACKOFF_MS} ms, or on the retry backoff while another partition's
 * record is failing, for as long as it takes. It is never dead-lettered for that reason: a deadline
 * would lose the customer's SMS whenever PostgreSQL accepts reads but not the writer's inserts for
 * longer than the deadline. After {@link #WARN_AFTER} of waiting, and every {@link #WARN_AFTER}
 * after that, the wait is logged at WARN so an operator can act (ADR 0057).
 */
public final class EnvelopeConsumer implements AutoCloseable {

  /** Handles one payload; throws to have the record re-read. */
  @FunctionalInterface
  public interface Handler {
    /**
     * Handles a payload.
     *
     * @param institutionId institution from the envelope
     * @param payload the envelope's payload
     * @throws SQLException when the record should be re-read
     */
    void handle(UUID institutionId, JsonNode payload) throws SQLException;
  }

  /** What to do with a record the handler refused. */
  private enum Outcome {
    HANDLED,
    RETRY,
    WAIT_FOR_PARENT,
    DEAD_LETTER
  }

  /** The record a partition is waiting on, since when, and when it was last reported. */
  private record Waiting(long offset, long sinceNanos, long reportedNanos) {}

  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeConsumer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  /** The ceiling of the retry backoff, in milliseconds. */
  static final long MAX_BACKOFF_MS = 30_000;

  private static final long FIRST_BACKOFF_MS = 100;

  /** How long a record waits for its parent before the wait is logged at WARN, and how often. */
  static final Duration WARN_AFTER = Duration.ofMinutes(1);

  /**
   * The pause between re-reads of a record waiting for its parent. It is fixed, not the exponential
   * backoff, so the other partitions of this consumer keep flowing while one waits.
   */
  static final long PARENT_BACKOFF_MS = 500;

  private final KafkaConsumer<String, byte[]> consumer;
  private final Producer<String, byte[]> deadLetters;
  private final String deadLetterTopic;
  private final Handler handler;
  private final Thread thread;
  private final AtomicLong deadLettered = new AtomicLong();
  private final Map<TopicPartition, Waiting> waiting = new HashMap<>();
  private volatile boolean running = true;
  private long backoffMs;

  /**
   * Starts consuming.
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
    Map<String, Object> properties = new HashMap<>(extra);
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
    this.consumer =
        new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
    Map<String, Object> producerProperties = new HashMap<>(extra);
    producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
    producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, group + "-dlq");
    this.deadLetters =
        new KafkaProducer<>(producerProperties, new StringSerializer(), new ByteArraySerializer());
    this.deadLetterTopic = topic + ".dlq";
    this.handler = Objects.requireNonNull(handler, "handler");
    this.thread = Thread.ofPlatform().name("consumer-" + group).daemon(true).unstarted(this::run);
    consumer.subscribe(List.of(topic));
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

  private void run() {
    while (running) {
      try {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
        Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
        boolean retrying = false;
        boolean parked = false;
        for (TopicPartition partition : records.partitions()) {
          for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
            Outcome outcome = handle(record);
            if (outcome == Outcome.RETRY || outcome == Outcome.WAIT_FOR_PARENT) {
              consumer.seek(partition, record.offset());
              retrying |= outcome == Outcome.RETRY;
              parked |= outcome == Outcome.WAIT_FOR_PARENT;
              break;
            }
            done.put(partition, new OffsetAndMetadata(record.offset() + 1));
          }
        }
        if (!done.isEmpty()) {
          consumer.commitSync(done);
        }
        backoff(retrying);
        if (!retrying && parked) {
          pause(PARENT_BACKOFF_MS);
        }
      } catch (WakeupException stopping) {
        return;
      } catch (RuntimeException e) {
        LOG.warn("consumer poll failed; continuing", e);
        backoff(true);
      }
    }
  }

  /** Waits before the next poll while records are failing, and resets once one is handled. */
  private void backoff(boolean retrying) {
    if (!retrying) {
      backoffMs = 0;
      return;
    }
    backoffMs = backoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
    pause(backoffMs);
  }

  /** Sleeps in steps of 100 ms, so closing the consumer is not held up. */
  private void pause(long ms) {
    long waited = 0;
    while (running && waited < ms) {
      try {
        Thread.sleep(Math.min(100, ms - waited));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      waited += 100;
    }
  }

  /** Handles a record, and forgets its partition's wait once the record is no longer waiting. */
  private Outcome handle(ConsumerRecord<String, byte[]> record) {
    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
    Outcome outcome = handleOnce(record, partition);
    if (outcome != Outcome.WAIT_FOR_PARENT) {
      waiting.remove(partition);
    }
    return outcome;
  }

  private Outcome handleOnce(ConsumerRecord<String, byte[]> record, TopicPartition partition) {
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
      handler.handle(institution, payload);
      return Outcome.HANDLED;
    } catch (NotYetRecordedException e) {
      // PostgreSQL answered, and the parent is not there yet. However long that lasts, the record
      // is not dead-lettered: its customer would never be told (ADR 0057).
      long now = System.nanoTime();
      Waiting since = waiting.get(partition);
      if (since == null || since.offset() != record.offset()) {
        since = new Waiting(record.offset(), now, now);
      }
      if (reportDue(since.sinceNanos(), since.reportedNanos(), now)) {
        LOG.warn(
            "{} offset {} has waited {} s for its parent to reach PostgreSQL ({}); every record"
                + " behind it on the partition waits too. If the PostgreSQL writer dead-lettered"
                + " the parent, replay it; see ADR 0057",
            partition,
            record.offset(),
            Duration.ofNanos(now - since.sinceNanos()).toSeconds(),
            e.getMessage());
        since = new Waiting(since.offset(), since.sinceNanos(), now);
      } else {
        LOG.debug("a record's parent is not in PostgreSQL yet; it will be re-read", e);
      }
      waiting.put(partition, since);
      return Outcome.WAIT_FOR_PARENT;
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

  /**
   * Whether a wait should be reported now: once it has lasted {@link #WARN_AFTER}, then every
   * {@link #WARN_AFTER}.
   *
   * @param sinceNanos when the record started waiting ({@link System#nanoTime})
   * @param reportedNanos when it was last reported, or {@code sinceNanos} if never
   * @param nowNanos now ({@link System#nanoTime})
   * @return true if a WARN is due
   */
  static boolean reportDue(long sinceNanos, long reportedNanos, long nowNanos) {
    long every = WARN_AFTER.toNanos();
    return nowNanos - sinceNanos >= every && nowNanos - reportedNanos >= every;
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

  /** Writes a record to {@code <topic>.dlq}; if that send fails, the record is retried instead. */
  private Outcome deadLetter(ConsumerRecord<String, byte[]> record, String reason, Exception why) {
    ProducerRecord<String, byte[]> dead =
        new ProducerRecord<>(deadLetterTopic, record.key(), record.value());
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
      return Outcome.RETRY;
    } catch (RuntimeException
        | java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException notSent) {
      LOG.warn("a record could not be dead-lettered; it will be re-read", notSent);
      return Outcome.RETRY;
    }
    deadLettered.incrementAndGet();
    LOG.error(
        "a record was dead-lettered to {} ({}); the consumer commits past it",
        deadLetterTopic,
        reason,
        why);
    return Outcome.DEAD_LETTER;
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
