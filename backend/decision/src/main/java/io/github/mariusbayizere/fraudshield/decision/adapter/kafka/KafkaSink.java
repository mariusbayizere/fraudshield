package io.github.mariusbayizere.fraudshield.decision.adapter.kafka;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessage;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessages;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolDrainer;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolRecord;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Publishes spooled facts to Kafka (D-15): {@code acks=all}, idempotent producer, at most five
 * in-flight requests so per-partition order holds, and a batch is acknowledged to the spool only
 * after every record in it is acknowledged by the brokers.
 */
public final class KafkaSink implements SpoolDrainer.Sink, AutoCloseable {

  private final Producer<String, byte[]> producer;
  private final KafkaMessages renderer;
  private final long sendTimeoutMs;

  /**
   * Creates a sink with an owned producer.
   *
   * @param bootstrapServers Kafka bootstrap servers
   * @param extra further producer properties (security, client id)
   * @param renderer fact renderer
   * @param sendTimeoutMs how long a batch may take before it is retried
   */
  public KafkaSink(
      String bootstrapServers,
      Map<String, Object> extra,
      KafkaMessages renderer,
      long sendTimeoutMs) {
    this(
        new KafkaProducer<>(properties(bootstrapServers, extra, sendTimeoutMs)),
        renderer,
        sendTimeoutMs);
  }

  KafkaSink(Producer<String, byte[]> producer, KafkaMessages renderer, long sendTimeoutMs) {
    this.producer = Objects.requireNonNull(producer, "producer");
    this.renderer = Objects.requireNonNull(renderer, "renderer");
    this.sendTimeoutMs = sendTimeoutMs;
  }

  /**
   * Producer settings required by D-15.
   *
   * @param bootstrapServers bootstrap servers
   * @param extra further properties
   * @param sendTimeoutMs delivery timeout
   * @return producer properties
   */
  static Map<String, Object> properties(
      String bootstrapServers, Map<String, Object> extra, long sendTimeoutMs) {
    Map<String, Object> properties = new java.util.HashMap<>(extra);
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    properties.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
    properties.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    properties.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.min(sendTimeoutMs, 30_000));
    properties.put(
        ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) Math.max(sendTimeoutMs, 35_000));
    properties.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, sendTimeoutMs);
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return properties;
  }

  @Override
  public void accept(List<SpoolRecord> records) throws SpoolDrainer.SinkException {
    List<Future<RecordMetadata>> sent = new ArrayList<>();
    for (SpoolRecord record : records) {
      for (KafkaMessage message : renderer.render(FactCodec.decode(record.payload()))) {
        ProducerRecord<String, byte[]> kafkaRecord =
            new ProducerRecord<>(message.topic(), message.key(), message.value());
        kafkaRecord
            .headers()
            .add(new RecordHeader("event_id", message.eventId().getBytes(StandardCharsets.UTF_8)));
        sent.add(producer.send(kafkaRecord));
      }
    }
    producer.flush();
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);
    try {
      for (Future<RecordMetadata> future : sent) {
        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SpoolDrainer.SinkException("interrupted while publishing", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new SpoolDrainer.SinkException("Kafka did not acknowledge the batch", e);
    }
  }

  @Override
  public void close() {
    producer.close(java.time.Duration.ofSeconds(5));
  }
}
