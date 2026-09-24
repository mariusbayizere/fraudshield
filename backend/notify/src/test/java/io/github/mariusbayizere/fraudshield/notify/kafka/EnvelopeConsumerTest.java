package io.github.mariusbayizere.fraudshield.notify.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.decision.testing.KafkaTestCluster;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Envelopes reach their handler with the envelope's institution and the record's headers; a
 * transient failure re-reads the record on its own partition's backoff; a record that can never be
 * handled goes to the topic's dead-letter queue with its headers; an SMS intent whose parent is
 * missing waits within its partition's budget and is then dead-lettered for replay (C.3, Principal
 * Review finding 7, docs/architecture/decision-fact-ordering.md). Each test uses its own topic.
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

  private static String envelope(UUID institution, int sequence) {
    return "{\"event_id\":\""
        + UUID.nameUUIDFromBytes(("e" + institution + sequence).getBytes(StandardCharsets.UTF_8))
        + "\",\"institution_id\":\""
        + institution
        + "\",\"payload\":{\"decision_sequence\":"
        + sequence
        + "}}";
  }

  private static void publish(String topic, UUID institution, int sequence) throws Exception {
    publish(topic, "key", envelope(institution, sequence), Map.of());
  }

  private static void publish(String topic, String key, String value, Map<String, String> headers)
      throws Exception {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()),
            new StringSerializer(),
            new ByteArraySerializer())) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(topic, key, value.getBytes(StandardCharsets.UTF_8));
      headers.forEach((k, v) -> record.headers().add(k, v.getBytes(StandardCharsets.UTF_8)));
      producer.send(record).get();
    }
  }

  private static EnvelopeConsumer consumer(
      String topic, String group, EnvelopeConsumer.Handler handler, Duration bound) {
    return consumer(topic, group, handler, bound, Map.of());
  }

  private static EnvelopeConsumer consumer(
      String topic,
      String group,
      EnvelopeConsumer.Handler handler,
      Duration bound,
      Map<String, Object> extra) {
    return new EnvelopeConsumer(
        kafka.bootstrapServers(),
        extra,
        topic,
        group,
        handler,
        new EnvelopeConsumer.ParentWait(bound, 1.0 / 6),
        Clock.systemUTC());
  }

  private static int sequence(EnvelopeConsumer.Envelope envelope) {
    return envelope.payload().get("decision_sequence").asInt();
  }

  private static String header(ConsumerRecord<String, byte[]> record, String name) {
    var h = record.headers().lastHeader(name);
    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
  }

  @Test
  void payloadsAreHandledInOrderAndFailuresAreReReadNotDropped() throws Exception {
    UUID institution = UUID.randomUUID();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    AtomicInteger failuresLeft = new AtomicInteger(2);
    try (EnvelopeConsumer consumer =
        consumer(
            "fs.decisions.final",
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              assertThat(envelope.institutionId()).isEqualTo(institution);
              int sequence = sequence(envelope);
              if (sequence == 3 && failuresLeft.getAndDecrement() > 0) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              handled.add(sequence);
            },
            Duration.ofMinutes(10))) {
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
  void poisonRecordsGoToTheDeadLetterTopicWithTheirHeadersAndTheGoodOnesBehindThemAreHandled()
      throws Exception {
    UUID institution = UUID.randomUUID();
    String topic = "fs.notifications.customer";
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
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
              if (sequence == 11) {
                // S3: not the kept decision's intent.
                throw new NotTheKeptDecisionException("not the kept decision (test)");
              }
              handled.add(sequence);
            },
            Duration.ofMinutes(10))) {
      publish(topic, "key", "{not json", Map.of("fs-transaction-id", "t-1"));
      publish(
          topic, "key", "{\"event_id\":\"" + UUID.randomUUID() + "\",\"payload\":{}}", Map.of());
      publish(topic, institution, 7);
      publish(topic, institution, 8);
      publish(topic, institution, 9);
      publish(topic, institution, 10);
      publish(topic, "key", envelope(institution, 11), Map.of("fs-transaction-id", "t-11"));
      publish(topic, institution, 12);

      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(12));
      assertThat(handled).containsExactly(8, 10, 12);
      await().atMost(Duration.ofSeconds(30)).until(() -> consumer.deadLettered() == 5);
    }

    List<ConsumerRecord<String, byte[]>> dead = drain(topic + ".dlq", 5);
    assertThat(dead)
        .extracting(r -> header(r, "fs-dlq-reason"))
        .containsExactly(
            "malformed_envelope",
            "malformed_envelope",
            "rejected_by_the_database",
            "permanent_vault_failure",
            "not_the_kept_decision");
    assertThat(dead).allSatisfy(r -> assertThat(header(r, "fs-dlq-topic")).isEqualTo(topic));
    assertThat(header(dead.getFirst(), "fs-transaction-id"))
        .as("a dead-lettered copy keeps its original headers, whatever the reason (D-a)")
        .isEqualTo("t-1");
    assertThat(header(dead.getLast(), "fs-transaction-id")).isEqualTo("t-11");
    assertThat(new String(dead.get(2).value(), StandardCharsets.UTF_8))
        .contains("\"decision_sequence\":7");
  }

  @Test
  @Tag("FR-03-04")
  void intentsWaitWithinTheirPartitionsBudgetAndAreThenDeadLetteredForReplay() throws Exception {
    // I6 and I7 end to end, with a five-second budget. Record 1's wait is taken from the same
    // budget, and counts wall time, so it is kept to one re-read to leave record 2 a clear margin.
    UUID institution = UUID.randomUUID();
    String topic = "fs.notifications.staff";
    List<Integer> handled = new CopyOnWriteArrayList<>();
    AtomicInteger notYet = new AtomicInteger(1);
    AtomicLong orphanFirstSeen = new AtomicLong();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 1 && notYet.getAndDecrement() > 0) {
                throw new NotYetRecordedException("parent late (test)");
              }
              if (sequence == 2) {
                orphanFirstSeen.compareAndSet(0, System.nanoTime());
                throw new NotYetRecordedException("parent never written (test)");
              }
              handled.add(sequence);
            },
            Duration.ofSeconds(5))) {
      publish(topic, institution, 1);
      publish(topic, "key", envelope(institution, 2), Map.of("fs-transaction-id", "t-2"));
      publish(topic, institution, 3);

      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(3));
      long deadAfter = System.nanoTime() - orphanFirstSeen.get();
      assertThat(handled).containsExactly(1, 3);
      assertThat(consumer.deadLettered()).isEqualTo(1);
      assertThat(Duration.ofNanos(deadAfter))
          .as("it waited, within the budget left after record 1's wait")
          .isGreaterThan(Duration.ofSeconds(2));
    }
    List<ConsumerRecord<String, byte[]>> dead = drain(topic + ".dlq", 1);
    assertThat(header(dead.getFirst(), "fs-dlq-reason")).isEqualTo("parent_not_recorded");
    assertThat(header(dead.getFirst(), "fs-transaction-id")).isEqualTo("t-2");
  }

  private static String keyFor(int partition, int partitions) {
    for (int i = 0; ; i++) {
      String key = "acct-" + i;
      if (Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % partitions
          == partition) {
        return key;
      }
    }
  }

  @Test
  @Tag("FR-03-04")
  void otherPartitionsFlowWhileOneWaitsForItsParentAndAnotherBacksOff() throws Exception {
    // I7d: the thread never sleeps for a waiting or failing partition (W-a).
    UUID institution = UUID.randomUUID();
    String topic = "fs.alerts.high";
    Map<Integer, Long> handledAt = new ConcurrentHashMap<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 1) {
                throw new NotYetRecordedException("parent late (test)");
              }
              if (sequence == 2) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              handledAt.put(sequence, System.nanoTime());
            },
            Duration.ofMinutes(10))) {
      publish(topic, keyFor(0, 3), envelope(institution, 1), Map.of());
      publish(topic, keyFor(1, 3), envelope(institution, 2), Map.of());
      Thread.sleep(3000); // the failing partition's backoff has grown past a second
      List<Long> latencies = new ArrayList<>();
      String flowing = keyFor(2, 3);
      for (int sequence = 10; sequence < 15; sequence++) {
        long sent = System.nanoTime();
        publish(topic, flowing, envelope(institution, sequence), Map.of());
        int s = sequence;
        await().atMost(Duration.ofSeconds(10)).until(() -> handledAt.containsKey(s));
        latencies.add(handledAt.get(s) - sent);
      }
      assertThat(latencies)
          .allSatisfy(ns -> assertThat(Duration.ofNanos(ns)).isLessThan(Duration.ofMillis(1500)));
      assertThat(consumer.deadLettered()).isZero();
    }
  }

  @Test
  @Tag("FR-03-04")
  void replayRepublishesTheNewestCopyOnceAndReplayedOrphansNeverWait() throws Exception {
    // I10, I11 and I14b.
    UUID institution = UUID.randomUUID();
    String topic = "fs.alerts.anomaly";
    String group = "test-group-" + UUID.randomUUID();
    AtomicBoolean parentExists = new AtomicBoolean();
    List<Map<String, String>> seenHeaders = new CopyOnWriteArrayList<>();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    EnvelopeConsumer.Handler handler =
        envelope -> {
          seenHeaders.add(envelope.headers());
          if (!parentExists.get()) {
            throw new NotYetRecordedException("parent missing (test)");
          }
          handled.add(sequence(envelope));
        };
    try (EnvelopeConsumer first = consumer(topic, group, handler, Duration.ofSeconds(1))) {
      publish(topic, "key", envelope(institution, 1), Map.of("fs-transaction-id", "t-1"));
      await().atMost(Duration.ofSeconds(30)).until(() -> first.deadLettered() == 1);
    }
    // A crash between a dead-letter and the commit past it leaves a second copy of the same event
    // (I14b): one is added by hand, with a record of another reason.
    ConsumerRecord<String, byte[]> copy = drain(topic + ".dlq", 1).getFirst();
    Map<String, String> copyHeaders = new HashMap<>();
    copy.headers()
        .forEach(h -> copyHeaders.put(h.key(), new String(h.value(), StandardCharsets.UTF_8)));
    publish(
        topic + ".dlq", copy.key(), new String(copy.value(), StandardCharsets.UTF_8), copyHeaders);
    publish(
        topic + ".dlq",
        "key",
        "{not json",
        Map.of("fs-dlq-reason", "malformed_envelope", "fs-dlq-topic", topic));
    assertThatThrownBy(
            () ->
                DeadLetterReplay.replay(
                    Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()),
                    topic,
                    Set.of(DeadLetterReplay.REJECTED),
                    Instant.EPOCH,
                    false))
        .isInstanceOf(IllegalArgumentException.class);

    try (EnvelopeConsumer second = consumer(topic, group, handler, Duration.ofMinutes(10))) {
      // Replayed while the parent is still missing: back to the DLQ at once, not after 10 minutes.
      DeadLetterReplay.Result early = replay(topic);
      assertThat(early.republished()).isEqualTo(1);
      assertThat(early.olderCopies()).isEqualTo(1);
      assertThat(early.skipped()).containsEntry("malformed_envelope", 1);
      await().atMost(Duration.ofSeconds(20)).until(() -> second.deadLettered() == 1);

      // Replayed once the parent exists: handled, with its headers and the replay count.
      parentExists.set(true);
      DeadLetterReplay.Result late = replay(topic);
      assertThat(late.republished()).isEqualTo(1);
      assertThat(late.olderCopies()).isEqualTo(2);
      await().atMost(Duration.ofSeconds(20)).until(() -> handled.contains(1));
      assertThat(seenHeaders.getLast())
          .containsEntry("fs-transaction-id", "t-1")
          .containsEntry(EnvelopeConsumer.REPLAYED_HEADER, "2")
          .doesNotContainKey("fs-dlq-reason");
    }
  }

  private static DeadLetterReplay.Result replay(String topic) {
    return DeadLetterReplay.replay(
        Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()),
        topic,
        Set.of("parent_not_recorded"),
        Instant.EPOCH,
        false);
  }

  @Test
  @Tag("FR-03-04")
  void theBudgetSurvivesChangesOfOwner() throws Exception {
    // I14 (contract review 2, MAJOR 2): a rebalance must not hand the next owner a fresh budget.
    Duration bound = Duration.ofSeconds(8);
    UUID institution = UUID.randomUUID();
    String topic = "fs.labels";
    String group = "test-group-" + UUID.randomUUID();
    AtomicLong firstAttempt = new AtomicLong();
    EnvelopeConsumer.Handler orphan =
        envelope -> {
          firstAttempt.compareAndSet(0, System.nanoTime());
          throw new NotYetRecordedException("parent never written (test)");
        };
    try (EnvelopeConsumer first = consumer(topic, group, orphan, bound)) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(30)).until(() -> firstAttempt.get() != 0);
      Thread.sleep(4000);
      assertThat(first.deadLettered()).isZero();
    }
    OffsetAndMetadata committed = committed(group, topic);
    assertThat(committed.metadata()).startsWith("fs-wait:v2 offset=" + committed.offset() + " ");
    long spent = Long.parseLong(committed.metadata().replaceAll(".*spent_ms=(\\d+).*", "$1"));
    assertThat(spent).isBetween(3000L, 8000L);

    firstAttempt.set(0);
    try (EnvelopeConsumer second = consumer(topic, group, orphan, bound)) {
      await().atMost(Duration.ofSeconds(30)).until(() -> firstAttempt.get() != 0);
      long resumedAt = firstAttempt.get();
      await().atMost(Duration.ofSeconds(30)).until(() -> second.deadLettered() == 1);
      assertThat(Duration.ofNanos(System.nanoTime() - resumedAt))
          .as("the second owner spent only what was left of the budget")
          .isLessThan(bound.minusSeconds(2));
    }
  }

  private static OffsetAndMetadata committed(String group, String topic) {
    try (KafkaConsumer<String, byte[]> reader =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                group),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      Set<TopicPartition> partitions = new HashSet<>();
      reader
          .partitionsFor(topic)
          .forEach(p -> partitions.add(new TopicPartition(topic, p.partition())));
      return reader.committed(partitions).values().stream()
          .filter(o -> o != null && !o.metadata().isEmpty())
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void batchesThatCannotBeCommittedSkipNoRecord() throws Exception {
    // I7f: the handler outlives max.poll.interval.ms, so the member is expelled and the batch's
    // commit fails; every record is still handled, none skipped.
    UUID institution = UUID.randomUUID();
    String topic = "fs.config.changes";
    List<Integer> handled = new CopyOnWriteArrayList<>();
    AtomicBoolean slow = new AtomicBoolean(true);
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 2 && slow.getAndSet(false)) {
                try {
                  Thread.sleep(8000);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              handled.add(sequence);
            },
            Duration.ofMinutes(10),
            Map.of(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 5000))) {
      for (int sequence = 1; sequence <= 5; sequence++) {
        publish(topic, institution, sequence);
      }
      await()
          .atMost(Duration.ofSeconds(90))
          .until(() -> handled.containsAll(List.of(1, 2, 3, 4, 5)));
    }
  }

  private static List<ConsumerRecord<String, byte[]>> drain(String topic, int expected) {
    List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
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
      while (System.nanoTime() < deadline && records.size() < expected) {
        ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(500));
        polled.forEach(records::add);
      }
    }
    return records;
  }
}
