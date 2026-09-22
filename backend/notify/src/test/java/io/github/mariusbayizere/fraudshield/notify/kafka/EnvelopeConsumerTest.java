package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.decision.testing.KafkaTestCluster;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Envelopes reach their handler with the envelope's institution; a failure re-reads the record. */
@Tag("requires-docker")
@Tag("D-14")
class EnvelopeConsumerTest {

  private static KafkaTestCluster kafka;

  @BeforeAll
  static void start() {
    kafka = new KafkaTestCluster();
  }

  @AfterAll
  static void stop() {
    kafka.close();
  }

  private static void publish(String topic, UUID institution, int sequence) throws Exception {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()),
            new StringSerializer(),
            new ByteArraySerializer())) {
      String envelope =
          "{\"event_id\":\""
              + UUID.randomUUID()
              + "\",\"institution_id\":\""
              + institution
              + "\",\"payload\":{\"decision_sequence\":"
              + sequence
              + "}}";
      producer
          .send(new ProducerRecord<>(topic, "key", envelope.getBytes(StandardCharsets.UTF_8)))
          .get();
    }
  }

  @Test
  void payloadsAreHandledInOrderAndFailuresAreReReadNotDropped() throws Exception {
    UUID institution = UUID.randomUUID();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    AtomicInteger failuresLeft = new AtomicInteger(2);
    try (EnvelopeConsumer consumer =
        new EnvelopeConsumer(
            kafka.bootstrapServers(),
            Map.of(),
            "fs.decisions.final",
            "test-group-" + UUID.randomUUID(),
            (inst, payload) -> {
              assertThat(inst).isEqualTo(institution);
              int sequence = payload.get("decision_sequence").asInt();
              if (sequence == 3 && failuresLeft.getAndDecrement() > 0) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              handled.add(sequence);
            })) {
      for (int sequence = 2; sequence <= 4; sequence++) {
        publish("fs.decisions.final", institution, sequence);
      }
      await().atMost(Duration.ofSeconds(30)).until(() -> handled.size() >= 3);
      assertThat(handled).containsExactly(2, 3, 4);
      assertThat(failuresLeft.get()).isNegative();
    }
  }
}
