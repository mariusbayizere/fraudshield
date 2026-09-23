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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Envelopes reach their handler with the envelope's institution; a transient failure re-reads the
 * record, and a record that can never be handled goes to the topic's dead-letter queue instead of
 * stalling the partition (C.3, Principal Review finding 7).
 */
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

  private static void publishRaw(String topic, String record) throws Exception {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()),
            new StringSerializer(),
            new ByteArraySerializer())) {
      producer
          .send(new ProducerRecord<>(topic, "key", record.getBytes(StandardCharsets.UTF_8)))
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

  @Test
  @Tag("NFR-REL-01")
  void poisonRecordsGoToTheDeadLetterTopicAndTheGoodOnesBehindThemAreHandled() throws Exception {
    UUID institution = UUID.randomUUID();
    String topic = "fs.notifications.customer";
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        new EnvelopeConsumer(
            kafka.bootstrapServers(),
            Map.of(),
            topic,
            "test-group-" + UUID.randomUUID(),
            (inst, payload) -> {
              int sequence = payload.get("decision_sequence").asInt();
              if (sequence == 7) {
                // A row the database will never accept: a unique violation (SQLSTATE class 23).
                throw new SQLException("duplicate key value (test)", "23505");
              }
              if (sequence == 9) {
                // A vault row that will never verify, reached through the SMS sender.
                throw new IllegalStateException(
                    "could not send",
                    io.github.mariusbayizere.fraudshield.notify.vault.VaultException.permanent(
                        "a vault row does not verify", null));
              }
              handled.add(sequence);
            })) {
      publishRaw(topic, "{not json");
      publishRaw(topic, "{\"event_id\":\"" + UUID.randomUUID() + "\",\"payload\":{}}");
      publish(topic, institution, 7);
      publish(topic, institution, 8);
      publish(topic, institution, 9);
      publish(topic, institution, 10);

      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(10));
      assertThat(handled).containsExactly(8, 10);
      await().atMost(Duration.ofSeconds(30)).until(() -> consumer.deadLettered() == 4);
    }

    List<ConsumerRecord<String, byte[]>> dead = drain(topic + ".dlq");
    assertThat(dead).hasSize(4);
    assertThat(dead)
        .extracting(
            r ->
                new String(r.headers().lastHeader("fs-dlq-reason").value(), StandardCharsets.UTF_8))
        .containsExactly(
            "malformed_envelope",
            "malformed_envelope",
            "rejected_by_the_database",
            "permanent_vault_failure");
    assertThat(new String(dead.get(2).value(), StandardCharsets.UTF_8))
        .contains("\"decision_sequence\":7");
    assertThat(new String(dead.getLast().value(), StandardCharsets.UTF_8))
        .contains("\"decision_sequence\":9");
    assertThat(dead)
        .allSatisfy(
            r ->
                assertThat(
                        new String(
                            r.headers().lastHeader("fs-dlq-topic").value(), StandardCharsets.UTF_8))
                    .isEqualTo(topic));
  }

  private static List<ConsumerRecord<String, byte[]>> drain(String topic) {
    List<ConsumerRecord<String, byte[]>> records = new java.util.ArrayList<>();
    try (KafkaConsumer<String, byte[]> consumer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "drain-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      consumer.subscribe(List.of(topic));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (System.nanoTime() < deadline && records.size() < 4) {
        ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(500));
        polled.forEach(records::add);
      }
    }
    return records;
  }
}
