package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The change publisher's own producer (ADR 0115 point 6.2). */
class KafkaChangeSenderTest {

  @Test
  @Tag("FR-04-02")
  void theProducerIsIdempotentBoundedAndNeverTheSpools() {
    Properties p = KafkaChangeSender.properties("broker:9092", RealtimeSettings.defaults());
    assertThat(p)
        .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
        .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
        .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 100)
        .containsEntry(ProducerConfig.BUFFER_MEMORY_CONFIG, 8L * 1024 * 1024)
        .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "fs-realtime-changes");
  }

  @Test
  @Tag("FR-04-02")
  void sendsToTheChangesTopicAndReportsFailedDelivery() {
    MockProducer<String, byte[]> producer =
        new MockProducer<>(false, null, new StringSerializer(), new ByteArraySerializer());
    AtomicInteger failures = new AtomicInteger();
    try (KafkaChangeSender sender = new KafkaChangeSender(producer)) {
      sender.send("tx-1", new byte[] {1}, failures::incrementAndGet);
      sender.send("tx-2", new byte[] {2}, failures::incrementAndGet);
      producer.completeNext();
      producer.errorNext(new RuntimeException("broker down"));
      assertThat(producer.history())
          .extracting(r -> r.topic() + "/" + r.key())
          .containsExactly(ChangePublisher.TOPIC + "/tx-1", ChangePublisher.TOPIC + "/tx-2");
      assertThat(failures).hasValue(1);
    }
  }
}
