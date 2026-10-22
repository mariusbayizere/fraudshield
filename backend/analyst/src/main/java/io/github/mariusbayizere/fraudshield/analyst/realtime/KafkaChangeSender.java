package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * The change publisher's own producer (ADR 0115 point 6.2): idempotent, {@code acks=all}, a small
 * {@code max.block.ms} and a budgeted {@code buffer.memory}, never the spool's.
 */
public final class KafkaChangeSender implements ChangePublisher.Sender, AutoCloseable {

  private final Producer<String, byte[]> producer;

  /**
   * Creates the producer.
   *
   * @param bootstrapServers the brokers
   * @param settings the channel's settings
   */
  public KafkaChangeSender(String bootstrapServers, RealtimeSettings settings) {
    this(new KafkaProducer<>(properties(bootstrapServers, settings)));
  }

  KafkaChangeSender(Producer<String, byte[]> producer) {
    this.producer = producer;
  }

  static Properties properties(String bootstrapServers, RealtimeSettings settings) {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    p.put(ProducerConfig.CLIENT_ID_CONFIG, "fs-realtime-changes");
    p.put(ProducerConfig.ACKS_CONFIG, "all");
    p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) settings.producerMaxBlock().toMillis());
    p.put(ProducerConfig.BUFFER_MEMORY_CONFIG, settings.producerBufferBytes());
    p.put(ProducerConfig.LINGER_MS_CONFIG, 5);
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    return p;
  }

  @Override
  public void send(String key, byte[] value, Runnable failed) {
    producer.send(
        new ProducerRecord<>(ChangePublisher.TOPIC, key, value),
        (metadata, error) -> {
          if (error != null) {
            failed.run();
          }
        });
  }

  @Override
  public void close() {
    producer.close();
  }
}
