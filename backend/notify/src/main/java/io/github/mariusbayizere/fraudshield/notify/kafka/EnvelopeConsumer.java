package io.github.mariusbayizere.fraudshield.notify.kafka;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes one topic's envelopes (ADR 0012) as one consumer group and hands each payload, with the
 * institution the producing service set from its authenticated principal, to a handler. Offsets are
 * committed only after the handler returns, so a failing handler re-reads the record rather than
 * drop it; handlers are idempotent on the event's own id.
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

  private static final Logger LOG = LoggerFactory.getLogger(EnvelopeConsumer.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final KafkaConsumer<String, byte[]> consumer;
  private final Handler handler;
  private final Thread thread;
  private volatile boolean running = true;

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
    this.handler = Objects.requireNonNull(handler, "handler");
    this.thread = Thread.ofPlatform().name("consumer-" + group).daemon(true).unstarted(this::run);
    consumer.subscribe(List.of(topic));
    thread.start();
  }

  private void run() {
    while (running) {
      try {
        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(100));
        Map<TopicPartition, OffsetAndMetadata> done = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
          for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
            if (!handled(record)) {
              consumer.seek(partition, record.offset());
              break;
            }
            done.put(partition, new OffsetAndMetadata(record.offset() + 1));
          }
        }
        if (!done.isEmpty()) {
          consumer.commitSync(done);
        }
      } catch (WakeupException stopping) {
        return;
      } catch (RuntimeException e) {
        LOG.warn("consumer poll failed; continuing", e);
      }
    }
  }

  private boolean handled(ConsumerRecord<String, byte[]> record) {
    try {
      JsonNode envelope = JSON.readTree(record.value());
      handler.handle(
          UUID.fromString(envelope.get("institution_id").asString()), envelope.get("payload"));
      return true;
    } catch (SQLException | RuntimeException e) {
      LOG.warn("a record could not be handled; it will be re-read", e);
      return false;
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
  }
}
