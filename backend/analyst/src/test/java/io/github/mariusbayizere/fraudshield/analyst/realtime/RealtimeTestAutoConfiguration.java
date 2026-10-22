package io.github.mariusbayizere.fraudshield.analyst.realtime;

import io.github.mariusbayizere.fraudshield.analyst.config.AnalystAutoConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The analyst tests' fan-out: the real one, fed by Kafka's {@link MockConsumer} instead of a
 * broker, so the real-time channel runs end to end in the application (ADR 0115 point 11). What
 * this does not exercise is Kafka itself: partition assignment against a real cluster, fetches and
 * the broker's latency.
 */
@AutoConfiguration(before = AnalystAutoConfiguration.class)
public class RealtimeTestAutoConfiguration {

  /** The topics' one partition each, fed by {@link #publish}. */
  public static final Kafka KAFKA = new Kafka();

  /** A mock consumer that waits a little when it has nothing, so the poll loop never spins. */
  public static final class Kafka extends MockConsumer<String, byte[]> {
    private final AtomicLong offsets = new AtomicLong();

    Kafka() {
      super("latest");
      for (String topic : AlertFrames.TOPICS) {
        updatePartitions(
            topic, List.of(new PartitionInfo(topic, 0, Node.noNode(), new Node[0], new Node[0])));
        updateBeginningOffsets(Map.of(new TopicPartition(topic, 0), 0L));
        updateEndOffsets(Map.of(new TopicPartition(topic, 0), 0L));
      }
    }

    @Override
    public synchronized ConsumerRecords<String, byte[]> poll(Duration timeout) {
      ConsumerRecords<String, byte[]> records = super.poll(Duration.ZERO);
      if (records.isEmpty()) {
        try {
          wait(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return records;
    }

    @Override
    public synchronized void close() {
      // Shared by the whole test context: a reconnect reuses it.
    }

    /**
     * Publishes a record, as a producer would, once the fan-out has assigned the topic.
     *
     * @param topic the topic
     * @param value the envelope
     */
    public synchronized void publish(String topic, byte[] value) {
      addRecord(
          new ConsumerRecord<>(
              topic,
              0,
              offsets.getAndIncrement(),
              System.currentTimeMillis(),
              TimestampType.CREATE_TIME,
              -1,
              -1,
              "key",
              value,
              new RecordHeaders(),
              Optional.empty()));
      notifyAll();
    }
  }

  @Bean(destroyMethod = "close")
  FanOut realtimeFanOut(
      StaffNames names,
      SessionRegistry registry,
      FiguresPusher figures,
      RealtimeSettings settings) {
    return new FanOut(
        () -> KAFKA,
        new AlertFrames(),
        names,
        registry,
        figures::alertSeen,
        settings,
        System::nanoTime,
        partition -> {});
  }
}
