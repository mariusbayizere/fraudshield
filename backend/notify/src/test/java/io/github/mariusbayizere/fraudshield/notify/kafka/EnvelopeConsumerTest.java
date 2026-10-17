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

  private static EnvelopeConsumer consumer(
      String topic,
      String group,
      EnvelopeConsumer.Handler handler,
      EnvelopeConsumer.RetryBound retryBound,
      io.micrometer.core.instrument.MeterRegistry meters,
      Map<String, Object> extra) {
    return new EnvelopeConsumer(
        kafka.bootstrapServers(),
        extra,
        topic,
        group,
        handler,
        new EnvelopeConsumer.ParentWait(Duration.ofMinutes(10), 1.0 / 6, Duration.ofSeconds(1)),
        retryBound,
        meters,
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
              if (sequence == 13) {
                // A well-formed envelope whose payload the handler cannot read.
                throw new MalformedPayloadException("unreadable payload (test)", null);
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
      publish(topic, institution, 13);
      publish(topic, institution, 14);

      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(14));
      assertThat(handled).containsExactly(8, 10, 12, 14);
      await().atMost(Duration.ofSeconds(30)).until(() -> consumer.deadLettered() == 6);
    }

    List<ConsumerRecord<String, byte[]>> dead = drain(topic + ".dlq", 6);
    assertThat(dead)
        .extracting(r -> header(r, "fs-dlq-reason"))
        .containsExactly(
            "malformed_envelope",
            "malformed_envelope",
            "rejected_by_the_database",
            "permanent_vault_failure",
            "not_the_kept_decision",
            "malformed_envelope");
    assertThat(dead).allSatisfy(r -> assertThat(header(r, "fs-dlq-topic")).isEqualTo(topic));
    assertThat(header(dead.getFirst(), "fs-transaction-id"))
        .as("a dead-lettered copy keeps its original headers, whatever the reason (D-a)")
        .isEqualTo("t-1");
    assertThat(header(dead.get(4), "fs-transaction-id")).isEqualTo("t-11");
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
          .as("far below the failing partition's backoff and the waiting one's budget")
          .allSatisfy(ns -> assertThat(Duration.ofNanos(ns)).isLessThan(Duration.ofSeconds(3)));
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
    assertThat(committed.metadata()).startsWith("fs-wait:v3 offset=" + committed.offset() + " ");
    long spent = Long.parseLong(committed.metadata().replaceAll(".*spent_ms=(\\d+).*", "$1"));
    assertThat(spent).isBetween(3000L, 8000L);

    firstAttempt.set(0);
    try (EnvelopeConsumer second = consumer(topic, group, orphan, bound)) {
      await().atMost(Duration.ofSeconds(30)).until(() -> firstAttempt.get() != 0);
      long resumedAt = firstAttempt.get();
      await().atMost(Duration.ofSeconds(30)).until(() -> second.deadLettered() == 1);
      assertThat(Duration.ofNanos(System.nanoTime() - resumedAt))
          .as("the second owner spent only what was left of the budget")
          .isLessThan(bound.minusSeconds(1));
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

  @Test
  @Tag("FR-03-04")
  void recordsArrivingAfterTrippingGetOneGraceReReadEvenThroughTransientFailures()
      throws Exception {
    // I7's second sentence and I8's second clause (the implementation review of bd48557): record
    // 1 fills a four-second budget and is dead-lettered; record 2 arrives while the budget is
    // spent, gets its one re-read, meets S5, and is sent once its parent is there; record 3, still
    // orphaned, is dead-lettered after that one re-read, not after a fresh budget.
    UUID institution = UUID.randomUUID();
    String topic = "fs.transactions.raw";
    List<Integer> handled = new CopyOnWriteArrayList<>();
    AtomicInteger secondAttempts = new AtomicInteger();
    AtomicLong thirdFirstSeen = new AtomicLong();
    AtomicLong thirdDeadAt = new AtomicLong();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 1) {
                throw new NotYetRecordedException("parent never written (test)");
              }
              if (sequence == 2) {
                int attempt = secondAttempts.incrementAndGet();
                if (attempt == 1) {
                  throw new NotYetRecordedException("parent late (test)");
                }
                if (attempt == 2) {
                  throw new SQLException("database unavailable (test)", "08006");
                }
              }
              if (sequence == 3) {
                thirdFirstSeen.compareAndSet(0, System.nanoTime());
                throw new NotYetRecordedException("parent never written (test)");
              }
              handled.add(sequence);
            },
            Duration.ofSeconds(4))) {
      publish(topic, institution, 1);
      publish(topic, institution, 2);
      publish(topic, institution, 3);
      await().atMost(Duration.ofSeconds(60)).until(() -> consumer.deadLettered() == 2);
      thirdDeadAt.set(System.nanoTime());
      assertThat(handled).containsExactly(2);
      assertThat(secondAttempts.get()).isEqualTo(3);
      assertThat(Duration.ofNanos(thirdDeadAt.get() - thirdFirstSeen.get()))
          .as("one grace re-read, not a fresh four-second budget")
          .isLessThan(Duration.ofSeconds(3));
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void longWaitsAreWarnedEvenWhilePostgresKeepsFailing() throws Exception {
    // I12 (review 13, MINOR 3): S5 between the waits must not silence the WARN.
    UUID institution = UUID.randomUUID();
    String topic = "fs.transactions.scored";
    AtomicInteger attempts = new AtomicInteger();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        new EnvelopeConsumer(
            kafka.bootstrapServers(),
            Map.of(),
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int attempt = attempts.incrementAndGet();
              if (attempt < 12) {
                if (attempt % 2 == 0) {
                  throw new SQLException("database unavailable (test)", "08006");
                }
                throw new NotYetRecordedException("parent late (test)");
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.ParentWait(Duration.ofMinutes(10), 1.0 / 6, Duration.ofSeconds(1)),
            Clock.systemUTC())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(1));
      assertThat(consumer.waitWarnings()).isGreaterThanOrEqualTo(2);
      assertThat(consumer.deadLettered()).isZero();
    }
  }

  @Test
  @Tag("FR-03-04")
  void partitionsMovedWhilePausedKeepEveryoneFlowing() throws Exception {
    // I7e (contract review 5, MINOR 2; PB-78). The orphan sits on partition 2 and pauses it. When
    // the second member joins, the range assignor gives partitions 0 and 1 to the first member,
    // whose client id sorts first, and partition 2 to the second: the paused partition moves while
    // its old owner keeps running. A resume of the partition the first member no longer owns would
    // throw IllegalStateException inside its poll cycle; the cycle's catch keeps the thread alive
    // but logs "cycle failed", which this test watches for. Deleting both the owned-partition check
    // before resume and the revocation's drop of the partition's state fails it; either one alone
    // still prevents the resume.
    UUID institution = UUID.randomUUID();
    String topic = "fs.ml.retrain";
    String group = "test-group-" + UUID.randomUUID();
    List<String> orphanAttempts = new CopyOnWriteArrayList<>();
    List<Long> secondOrphanAt = new CopyOnWriteArrayList<>();
    Map<Integer, Long> handledAt = new ConcurrentHashMap<>();
    java.util.function.Function<String, EnvelopeConsumer.Handler> handler =
        member ->
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 1) {
                orphanAttempts.add(member);
                if (member.equals("second")) {
                  secondOrphanAt.add(System.nanoTime());
                }
                throw new NotYetRecordedException("parent late (test)");
              }
              handledAt.put(sequence, System.nanoTime());
            };
    var logs = captureLogs();
    // Client ids fix the members' order for the range assignor: "a-…" sorts before "b-…".
    try (EnvelopeConsumer first =
        consumer(
            topic,
            group,
            handler.apply("first"),
            Duration.ofMinutes(10),
            Map.of(ConsumerConfig.CLIENT_ID_CONFIG, "a-first-" + UUID.randomUUID()))) {
      publish(topic, keyFor(2, 3), envelope(institution, 1), Map.of());
      await().atMost(Duration.ofSeconds(30)).until(() -> orphanAttempts.size() >= 2);
      long joinedAt = System.nanoTime();
      try (EnvelopeConsumer second =
          consumer(
              topic,
              group,
              handler.apply("second"),
              Duration.ofMinutes(10),
              Map.of(ConsumerConfig.CLIENT_ID_CONFIG, "b-second-" + UUID.randomUUID()))) {
        await().atMost(Duration.ofSeconds(60)).until(() -> !secondOrphanAt.isEmpty());
        assertThat(Duration.ofNanos(secondOrphanAt.getFirst() - joinedAt))
            .as("the new owner re-reads the moved partition at once, not after a pause")
            .isLessThan(Duration.ofSeconds(30));
        // The first member keeps its partitions and keeps flowing.
        publish(topic, keyFor(0, 3), envelope(institution, 10), Map.of());
        publish(topic, keyFor(1, 3), envelope(institution, 11), Map.of());
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> handledAt.containsKey(10) && handledAt.containsKey(11));
        int movedAt = orphanAttempts.indexOf("second");
        Thread.sleep(3000); // several of the old owner's resume deadlines pass
        assertThat(orphanAttempts.subList(movedAt, orphanAttempts.size()))
            .as("after the move only the second member re-reads the paused partition")
            .containsOnly("second");
        assertThat(logs.list)
            .as("no poll cycle of either member failed (no IllegalStateException from a resume)")
            .noneMatch(e -> e.getFormattedMessage().contains("cycle failed"));
        assertThat(first.deadLettered() + second.deadLettered()).isZero();
      }
    } finally {
      release(logs);
    }
  }

  @Test
  @Tag("FR-03-04")
  void outageTimeAfterWaitingIsNeutralNotWaiting() throws Exception {
    // I8 at the consumer: a record answers S4 once, then PostgreSQL is down for 6 s, then the
    // record answers S4 twice more and is handled. With a 3 s budget, counting the outage as
    // waiting would fill it and dead-letter the record at its next S4 answer; neutral, it waits
    // and is handled. Deleting the consumer's budget.failed(start) call fails this test.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    AtomicLong outageEndsAt = new AtomicLong();
    AtomicInteger waitsAfter = new AtomicInteger();
    AtomicInteger attempts = new AtomicInteger();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int attempt = attempts.incrementAndGet();
              if (attempt == 1) {
                outageEndsAt.set(System.nanoTime() + Duration.ofSeconds(6).toNanos());
                throw new NotYetRecordedException("parent late (test)");
              }
              if (System.nanoTime() < outageEndsAt.get()) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              if (waitsAfter.getAndIncrement() < 2) {
                throw new NotYetRecordedException("parent still late (test)");
              }
              handled.add(sequence(envelope));
            },
            Duration.ofSeconds(3))) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(1));
      assertThat(consumer.deadLettered()).isZero();
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void outagesAfterWaitingKeepTheWaitWarnGoing() throws Exception {
    // I12 (review 13, MINOR 3): after one S4 answer, PostgreSQL is down and the record is never
    // answered S4 again before it is handled. The WARN, every 1 s here, must still fire, because
    // failed attempts after S4 count as waiting for W-d. Deleting that clause leaves no WARN.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        new EnvelopeConsumer(
            kafka.bootstrapServers(),
            Map.of(),
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int attempt = attempts.incrementAndGet();
              if (attempt == 1) {
                throw new NotYetRecordedException("parent late (test)");
              }
              if (attempt <= 7) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.ParentWait(Duration.ofMinutes(10), 1.0 / 6, Duration.ofSeconds(1)),
            Clock.systemUTC())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(1));
      assertThat(consumer.waitWarnings()).isGreaterThanOrEqualTo(1);
      assertThat(consumer.deadLettered()).isZero();
    }
  }

  @Test
  @Tag("FR-03-04")
  void theRevocationCommitCarriesWaitingNoOtherCommitDoes() throws Exception {
    // I14 after a revocation (implementation-3 audit): the revocation's commit is the only one that
    // carries waiting when the batch's own commit fails. Here close() is called while an S4 attempt
    // runs for 3 s; the wakeup makes the batch's commitSync throw WakeupException, so the waiting
    // of
    // that attempt reaches the next owner only through the revocation commit. Deleting it leaves
    // the checkpoint about 3 s short.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    AtomicLong firstAnswerAt = new AtomicLong();
    AtomicLong slowEndsAt = new AtomicLong();
    java.util.concurrent.CountDownLatch slowStarted = new java.util.concurrent.CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    EnvelopeConsumer consumer =
        consumer(
            topic,
            group,
            envelope -> {
              int attempt = attempts.incrementAndGet();
              if (attempt == 1) {
                firstAnswerAt.set(System.currentTimeMillis());
              }
              if (attempt == 5) {
                slowStarted.countDown();
                try {
                  Thread.sleep(3000);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                slowEndsAt.set(System.currentTimeMillis());
              }
              throw new NotYetRecordedException("parent never written (test)");
            },
            Duration.ofSeconds(60));
    publish(topic, institution, 1);
    assertThat(slowStarted.await(60, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    consumer.close();
    OffsetAndMetadata committed = committed(group, topic);
    long spent = Long.parseLong(committed.metadata().replaceAll(".*spent_ms=(\\d+).*", "$1"));
    long waited = slowEndsAt.get() - firstAnswerAt.get();
    assertThat(spent)
        .as("the revocation commit carries the slow attempt's waiting")
        .isGreaterThanOrEqualTo(waited - 1000);
  }

  @Test
  @Tag("FR-03-04")
  void crashedOwnersBudgetsAreResumedFromTheirLastCheckpoint() throws Exception {
    // I14 without a revocation (the implementation review of bd48557): the owner dies, nothing is
    // committed on its way out, and the next owner resumes from the checkpoint of its last re-read.
    Duration bound = Duration.ofSeconds(10);
    UUID institution = UUID.randomUUID();
    String topic = "fs.ml.shadow";
    String group = "test-group-" + UUID.randomUUID();
    // crash() stops the poll thread, but the client's heartbeat thread lives on, as it would not in
    // a real crash: a short poll interval gets the stalled member expelled within seconds, and an
    // expulsion by poll timeout runs no listener, so nothing is committed on the way out.
    Map<String, Object> fastSession =
        Map.of(
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
            6000,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,
            2000,
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
            8000);
    AtomicLong firstAttempt = new AtomicLong();
    EnvelopeConsumer.Handler orphan =
        envelope -> {
          firstAttempt.compareAndSet(0, System.nanoTime());
          throw new NotYetRecordedException("parent never written (test)");
        };
    EnvelopeConsumer crashed = consumer(topic, group, orphan, bound, fastSession);
    try {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(30)).until(() -> firstAttempt.get() != 0);
      Thread.sleep(6000);
      crashed.crash();
      // Read once the poll thread has ended, so no checkpoint can follow it.
      long crashedAt = System.currentTimeMillis();
      OffsetAndMetadata checkpoint = committed(group, topic);
      long spent = Long.parseLong(checkpoint.metadata().replaceAll(".*spent_ms=(\\d+).*", "$1"));
      assertThat(spent).as("checkpointed on its re-reads, with no revocation").isGreaterThan(4000L);
      long at = Long.parseLong(checkpoint.metadata().replaceAll(".*at=(\\d+).*", "$1"));
      // I14's loss bound (W-e): the waiting lost is the time from the last checkpoint to the crash,
      // at most one re-read interval (500 ms) plus the thread's current batch, here one record. One
      // second is added for scheduling on a shared CI runner.
      assertThat(crashedAt - at)
          .as("waiting lost to the crash: at most one re-read interval, plus scheduling")
          .isBetween(0L, 1500L);

      firstAttempt.set(0);
      try (EnvelopeConsumer next = consumer(topic, group, orphan, bound, fastSession)) {
        await().atMost(Duration.ofSeconds(60)).until(() -> firstAttempt.get() != 0);
        long resumedAt = firstAttempt.get();
        await().atMost(Duration.ofSeconds(30)).until(() -> next.deadLettered() == 1);
        assertThat(Duration.ofNanos(System.nanoTime() - resumedAt))
            .as("what was left of the budget, not a fresh ten seconds")
            .isLessThan(Duration.ofSeconds(9));
      }
    } finally {
      crashed.close();
    }
  }

  // ---- Amendment 1 (docs/architecture/decision-fact-ordering.md, section 12) ----

  /** A topic of its own, one partition, with its DLQ, so the test sees only its records. */
  private static String newTopic() throws Exception {
    String topic = "test-" + UUID.randomUUID();
    try (org.apache.kafka.clients.admin.Admin admin =
        org.apache.kafka.clients.admin.Admin.create(
            Map.of(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new org.apache.kafka.clients.admin.NewTopic(topic, 1, (short) 1),
                  new org.apache.kafka.clients.admin.NewTopic(topic + ".dlq", 1, (short) 1)))
          .all()
          .get();
    }
    return topic;
  }

  private static double count(
      io.micrometer.core.instrument.MeterRegistry meters, String name, String... tags) {
    io.micrometer.core.instrument.Counter counter = meters.find(name).tags(tags).counter();
    return counter == null ? 0 : counter.count();
  }

  private static final ProviderResult SENT = ProviderResult.sent("en", true, "ATXid_42");

  @Test
  @Tag("NFR-REL-01")
  void everyDeadLetterReasonKeepsTheRecordsHeaders() throws Exception {
    // I10 for every reason of D-a, and the dead-letter counter of 12.6 (I23).
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    EnvelopeConsumer.Handler handler =
        envelope -> {
          int sequence = sequence(envelope);
          switch (sequence) {
            case 2 -> throw new MalformedHeaderException("S3m (test)");
            case 3 -> throw new NotTheKeptDecisionException("S3 (test)");
            case 4 -> throw new SQLException("duplicate key value (test)", "23505");
            case 5 ->
                throw io.github.mariusbayizere.fraudshield.notify.vault.VaultException.permanent(
                    "does not verify (test)", null);
            case 6 -> throw new IllegalStateException("always fails (test)");
            case 7 -> throw new NotYetRecordedException("parent missing (test)");
            case 8 -> {
              envelope.keeper().keep(SENT);
              throw new SQLException("check violation after the send (test)", "23514");
            }
            default -> handled.add(sequence);
          }
        };
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            handler,
            new EnvelopeConsumer.RetryBound(2, Duration.ZERO, Duration.ofSeconds(60)),
            meters,
            Map.of())) {
      publish(topic, "key", "{not json", Map.of("fs-transaction-id", "t-1", "x-test", "1"));
      for (int sequence = 2; sequence <= 9; sequence++) {
        Map<String, String> headers = new HashMap<>();
        headers.put("fs-transaction-id", "t-" + sequence);
        headers.put("x-test", Integer.toString(sequence));
        if (sequence == 7) {
          headers.put(EnvelopeConsumer.REPLAYED_HEADER, "1");
        }
        publish(topic, "key", envelope(institution, sequence), headers);
      }
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(9));
      await().atMost(Duration.ofSeconds(30)).until(() -> consumer.deadLettered() == 8);
    }
    List<ConsumerRecord<String, byte[]>> dead = drain(topic + ".dlq", 8);
    assertThat(dead)
        .extracting(r -> header(r, "fs-dlq-reason"))
        .containsExactly(
            "malformed_envelope",
            "malformed_header",
            "not_the_kept_decision",
            "rejected_by_the_database",
            "permanent_vault_failure",
            "handler_failure",
            "parent_not_recorded",
            "outcome_unrecorded");
    assertThat(dead)
        .as("every reason keeps the original headers (D-a)")
        .allSatisfy(
            r -> {
              assertThat(header(r, "x-test")).isNotNull();
              assertThat(header(r, "fs-transaction-id")).isEqualTo("t-" + header(r, "x-test"));
              assertThat(header(r, "fs-dlq-topic")).isEqualTo(topic);
            });
    assertThat(header(dead.getLast(), EnvelopeConsumer.DLQ_RESULT_HEADER))
        .as("a dead letter after a send carries its result (12.4)")
        .isEqualTo(SENT.text());
    for (String reason :
        List.of(
            "malformed_envelope",
            "malformed_header",
            "not_the_kept_decision",
            "rejected_by_the_database",
            "permanent_vault_failure",
            "handler_failure",
            "parent_not_recorded",
            "outcome_unrecorded")) {
      assertThat(count(meters, "fs_notify_dead_letters_total", "reason", reason))
          .as(reason)
          .isEqualTo(1.0);
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void recordsThatKeepFailingAreDeadLetteredAtTheBoundAndTheNextIsHandled() throws Exception {
    // I17: F4 is re-read on the backoff up to the bound of 12.3 (here 3 attempts and 2 s), then
    // dead-lettered as handler_failure, and the record behind it is handled.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    List<Long> attempts = new CopyOnWriteArrayList<>();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              if (sequence(envelope) == 1) {
                attempts.add(System.nanoTime());
                throw new NullPointerException("parameters (test)");
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.RetryBound(3, Duration.ofSeconds(2), Duration.ofSeconds(60)),
            meters,
            Map.of())) {
      publish(topic, institution, 1);
      publish(topic, institution, 2);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(2));
      assertThat(consumer.deadLettered()).isEqualTo(1);
    }
    assertThat(attempts).hasSizeGreaterThanOrEqualTo(3);
    assertThat(Duration.ofNanos(attempts.getLast() - attempts.getFirst()))
        .as("not before the bound's time")
        .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
    assertThat(header(drain(topic + ".dlq", 1).getFirst(), "fs-dlq-reason"))
        .isEqualTo("handler_failure");
    assertThat(count(meters, "fs_notify_retries_total", "class", "may_be_own"))
        .as("every failed attempt but the one that dead-letters")
        .isEqualTo(attempts.size() - 1.0);
  }

  @Test
  @Tag("NFR-REL-01")
  void theRetryStateSurvivesChangesOfOwnerWhenNothingIsSpent() throws Exception {
    // I17 and amendment-1 review A1-M3: the F4 count is checkpointed whatever the wait budget, so a
    // rebalance does not restart it. Six attempts in all, across two owners.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    EnvelopeConsumer.Handler poison =
        envelope -> {
          attempts.incrementAndGet();
          throw new IllegalStateException("always fails (test)");
        };
    EnvelopeConsumer.RetryBound bound =
        new EnvelopeConsumer.RetryBound(6, Duration.ZERO, Duration.ofSeconds(60));
    try (EnvelopeConsumer first =
        consumer(
            topic,
            group,
            poison,
            bound,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(30)).until(() -> attempts.get() >= 2);
    }
    int byFirst = attempts.get();
    OffsetAndMetadata committed = committed(group, topic);
    assertThat(committed.metadata())
        .as("nothing spent waiting, yet the retry state is carried")
        .contains("spent_ms=0 ")
        .contains("f4_count=" + byFirst + " ");
    try (EnvelopeConsumer second =
        consumer(
            topic,
            group,
            poison,
            bound,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      await().atMost(Duration.ofSeconds(60)).until(() -> second.deadLettered() == 1);
    }
    assertThat(attempts.get()).as("the second owner went on from the first's count").isEqualTo(6);
  }

  @Test
  @Tag("NFR-REL-01")
  void recordsWaitingAndFailingInTurnReachTheBound() throws Exception {
    // I17 and amendment-1 review A1-m3: an S4 answer does not clear the retry state, so a record
    // alternating S4 and F4 is dead-lettered at the F4 bound, not kept alive by the wait budget.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              if (attempts.incrementAndGet() % 2 == 1) {
                throw new NotYetRecordedException("parent late (test)");
              }
              throw new SQLException("statement timeout (test)", "57014");
            },
            new EnvelopeConsumer.RetryBound(3, Duration.ofSeconds(1), Duration.ofSeconds(60)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(60)).until(() -> consumer.deadLettered() == 1);
    }
    assertThat(header(drain(topic + ".dlq", 1).getFirst(), "fs-dlq-reason"))
        .isEqualTo("handler_failure");
  }

  @Test
  @Tag("NFR-REL-01")
  void outagesAreRetriedWithoutBoundAndCounted() throws Exception {
    // I18: an outage never reaches the F4 bound (here 2 attempts, no time), and each failed attempt
    // is counted as an outage.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    AtomicInteger attempts = new AtomicInteger();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              if (attempts.incrementAndGet() <= 6) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.RetryBound(2, Duration.ZERO, Duration.ofSeconds(60)),
            meters,
            Map.of())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(1));
      assertThat(consumer.deadLettered()).isZero();
    }
    assertThat(count(meters, "fs_notify_retries_total", "class", "outage")).isEqualTo(6.0);
    assertThat(count(meters, "fs_notify_retries_total", "class", "may_be_own")).isZero();
  }

  @Test
  @Tag("FR-03-04")
  void keptResultsAreWrittenOnReReadsAndAcrossOwnersAndNeverSentAgain() throws Exception {
    // I20 at the consumer: once the provider has been called, a failed outcome write re-reads the
    // record with the result kept, on this owner and the next, and the provider is not called
    // again.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    AtomicInteger sends = new AtomicInteger();
    AtomicInteger rereadsWithResult = new AtomicInteger();
    List<ProviderResult> written = new CopyOnWriteArrayList<>();
    AtomicBoolean outcomeWriteWorks = new AtomicBoolean(false);
    EnvelopeConsumer.Handler handler =
        envelope -> {
          if (envelope.kept().isEmpty()) {
            sends.incrementAndGet();
            envelope.keeper().keep(SENT);
          } else {
            rereadsWithResult.incrementAndGet();
          }
          if (!outcomeWriteWorks.get()) {
            throw new SQLException("outcome write failed (test)", "08006");
          }
          written.add(envelope.kept().orElseThrow());
        };
    try (EnvelopeConsumer first =
        consumer(
            topic,
            group,
            handler,
            EnvelopeConsumer.RetryBound.DEFAULT,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(30)).until(() -> rereadsWithResult.get() >= 2);
    }
    assertThat(committed(group, topic).metadata())
        .as("the result is in the checkpoint (W-e2)")
        .endsWith(" result=" + SENT.text());
    outcomeWriteWorks.set(true);
    try (EnvelopeConsumer second =
        consumer(
            topic,
            group,
            handler,
            EnvelopeConsumer.RetryBound.DEFAULT,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      await().atMost(Duration.ofSeconds(30)).until(() -> written.size() == 1);
    }
    assertThat(sends.get()).as("one provider call, whatever happened after it").isEqualTo(1);
    assertThat(written).containsExactly(SENT);
  }

  @Test
  @Tag("FR-03-04")
  void theTransactionHeadersStateReachesTheHandlerAndMalformedOnesAreCountedOnce()
      throws Exception {
    // I21 through the real consumer, not by parsing the header in the test (impl-2 m2).
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    Map<Integer, TransactionHeader> seen = new ConcurrentHashMap<>();
    AtomicInteger failuresLeft = new AtomicInteger(2);
    UUID valid = UUID.randomUUID();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 2 && failuresLeft.getAndDecrement() > 0) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              seen.put(sequence, envelope.transaction());
            },
            EnvelopeConsumer.RetryBound.DEFAULT,
            meters,
            Map.of())) {
      publish(topic, "key", envelope(institution, 1), Map.of());
      publish(topic, "key", envelope(institution, 2), Map.of("fs-transaction-id", "1-2-3-4-5"));
      publish(
          topic, "key", envelope(institution, 3), Map.of("fs-transaction-id", valid.toString()));
      await().atMost(Duration.ofSeconds(30)).until(() -> seen.containsKey(3));
    }
    assertThat(seen.get(1)).isEqualTo(TransactionHeader.ABSENT);
    assertThat(seen.get(2)).isEqualTo(TransactionHeader.MALFORMED);
    assertThat(seen.get(3)).isEqualTo(TransactionHeader.valid(valid));
    assertThat(count(meters, "fs_notify_malformed_headers_total"))
        .as("once per record, though it was read three times")
        .isEqualTo(1.0);
  }

  @Test
  @Tag("NFR-REL-01")
  void offsetsBelowTheLogStartAreCountedNotSkippedSilently() throws Exception {
    // I22: retention deleted records behind a held partition. The reset to the log start is
    // counted; the records still there are handled.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    for (int sequence = 0; sequence < 10; sequence++) {
      publish(topic, institution, sequence);
    }
    TopicPartition partition = new TopicPartition(topic, 0);
    try (KafkaConsumer<String, byte[]> committer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                group),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      committer.assign(List.of(partition));
      committer.commitSync(Map.of(partition, new OffsetAndMetadata(0)));
    }
    try (org.apache.kafka.clients.admin.Admin admin =
        org.apache.kafka.clients.admin.Admin.create(
            Map.of(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers()))) {
      admin
          .deleteRecords(
              Map.of(partition, org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(5)))
          .all()
          .get();
    }
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            group,
            envelope -> handled.add(sequence(envelope)),
            EnvelopeConsumer.RetryBound.DEFAULT,
            meters,
            Map.of())) {
      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(9));
    }
    assertThat(handled).containsExactly(5, 6, 7, 8, 9);
    assertThat(count(meters, "fs_notify_offsets_skipped_total")).isEqualTo(5.0);
  }

  /** A JVM {@code Error} a handler might meet; any {@code Throwable} that is not an exception. */
  private static final class TestError extends Error {
    private static final long serialVersionUID = 1L;

    TestError() {
      super("an Error from a handler (test)");
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void anErrorMidBatchSkipsNoRecordAndStopsOnlyThatMember() throws Exception {
    // I26 (amendment-1 review A2-M1): the stopping member seeks back and commits before it closes,
    // so the record that threw and every record after it are handled by the next owner.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    for (int sequence = 1; sequence <= 5; sequence++) {
      publish(topic, institution, sequence);
    }
    List<Integer> byFirst = new CopyOnWriteArrayList<>();
    List<Integer> bySecond = new CopyOnWriteArrayList<>();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    EnvelopeConsumer first =
        consumer(
            topic,
            group,
            envelope -> {
              if (sequence(envelope) == 3) {
                throw new TestError();
              }
              byFirst.add(sequence(envelope));
            },
            EnvelopeConsumer.RetryBound.DEFAULT,
            meters,
            Map.of());
    try {
      await().atMost(Duration.ofSeconds(30)).until(() -> !first.running());
      assertThat(byFirst).containsExactly(1, 2);
      assertThat(count(meters, "fs_notify_consumer_errors_total")).isEqualTo(1.0);
      assertThat(meters.find("fs_notify_consumer_running").gauge().value()).isZero();
      try (EnvelopeConsumer second =
          consumer(
              topic,
              group,
              envelope -> bySecond.add(sequence(envelope)),
              EnvelopeConsumer.RetryBound.DEFAULT,
              new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
              Map.of())) {
        await().atMost(Duration.ofSeconds(60)).until(() -> bySecond.contains(5));
      }
      assertThat(bySecond).containsExactly(3, 4, 5);
    } finally {
      first.close();
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void batchesPastTheirTimeBudgetPollAgainWithoutSkipping() throws Exception {
    // I26: a 50 ms batch budget with 30 ms records: the consumer polls again many times, and every
    // record is handled once, in order.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    for (int sequence = 1; sequence <= 10; sequence++) {
      publish(topic, institution, sequence);
    }
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              try {
                Thread.sleep(30);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.RetryBound(5, Duration.ofMinutes(10), Duration.ofMillis(50)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(10));
    }
    assertThat(handled).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
  }

  @Test
  @Tag("NFR-REL-01")
  void deadLettersTooLargeAreResentWithoutTheirErrorThenDroppedAtTheBound() throws Exception {
    // I27 (amendment-1 review A2-M2): the producer's max.request.size is set low. Record 1's dead
    // letter fits once fs-dlq-error is left out; record 2's never fits and is dropped, counted, at
    // the bound; record 3 is handled.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    String longReason = "x".repeat(3000);
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              int sequence = sequence(envelope);
              if (sequence == 1 || sequence == 2) {
                throw new MalformedPayloadException(longReason, null);
              }
              handled.add(sequence);
            },
            new EnvelopeConsumer.RetryBound(2, Duration.ZERO, Duration.ofSeconds(60)),
            meters,
            Map.of(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2000))) {
      publish(topic, institution, 1);
      String padded =
          "{\"event_id\":\""
              + UUID.randomUUID()
              + "\",\"institution_id\":\""
              + institution
              + "\",\"payload\":{\"decision_sequence\":2,\"pad\":\""
              + "p".repeat(4000)
              + "\"}}";
      publish(topic, "key", padded, Map.of());
      publish(topic, institution, 3);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(3));
    }
    List<ConsumerRecord<String, byte[]>> dead = drain(topic + ".dlq", 1);
    assertThat(dead).hasSize(1);
    assertThat(header(dead.getFirst(), "fs-dlq-reason")).isEqualTo("malformed_envelope");
    assertThat(dead.getFirst().headers().lastHeader("fs-dlq-error"))
        .as("re-sent without its error (D-a)")
        .isNull();
    assertThat(count(meters, "fs_notify_dropped_total")).isEqualTo(1.0);
  }

  @Test
  @Tag("FR-03-04")
  void replayingAnOutcomeUnrecordedRecordsItsResultAndSendsNothing() throws Exception {
    // I28: the copy's fs-dlq-result is republished as one fs-kept-result; the consumer hands it to
    // the handler as a kept result. A fs-kept-result on a record that was not replayed is ignored.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    var meters = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    AtomicBoolean recordable = new AtomicBoolean(false);
    AtomicInteger sends = new AtomicInteger();
    Map<Integer, java.util.Optional<ProviderResult>> keptSeen = new ConcurrentHashMap<>();
    EnvelopeConsumer.Handler handler =
        envelope -> {
          int sequence = sequence(envelope);
          keptSeen.put(sequence, envelope.kept());
          if (sequence == 1 && envelope.kept().isEmpty()) {
            sends.incrementAndGet();
            envelope.keeper().keep(SENT);
          }
          if (sequence == 1 && !recordable.get()) {
            throw new SQLException("check violation (test)", "23514");
          }
        };
    try (EnvelopeConsumer first =
        consumer(topic, group, handler, EnvelopeConsumer.RetryBound.DEFAULT, meters, Map.of())) {
      publish(topic, institution, 1);
      publish(
          topic,
          "key",
          envelope(institution, 2),
          Map.of(EnvelopeConsumer.KEPT_RESULT_HEADER, SENT.text()));
      await().atMost(Duration.ofSeconds(30)).until(() -> keptSeen.containsKey(2));
      await().atMost(Duration.ofSeconds(30)).until(() -> first.deadLettered() == 1);
    }
    assertThat(keptSeen.get(2)).as("not replayed: the header is ignored").isEmpty();
    assertThat(count(meters, "fs_notify_ignored_kept_results_total")).isEqualTo(1.0);

    recordable.set(true);
    keptSeen.clear();
    DeadLetterReplay.Result replayed =
        DeadLetterReplay.replay(
            Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers()),
            topic,
            Set.of("outcome_unrecorded"),
            Instant.EPOCH,
            false);
    assertThat(replayed.republished()).isEqualTo(1);
    try (EnvelopeConsumer second =
        consumer(
            topic,
            group,
            handler,
            EnvelopeConsumer.RetryBound.DEFAULT,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      await().atMost(Duration.ofSeconds(30)).until(() -> keptSeen.containsKey(1));
    }
    assertThat(keptSeen.get(1)).contains(SENT);
    assertThat(sends.get()).as("the replay recorded the result; it did not send").isEqualTo(1);
  }

  // ---- Implementation-3 review: each test fails if the code it names is deleted ----

  /** Captures what the consumer logs, for rows that claim a log line. */
  private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>
      captureLogs() {
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EnvelopeConsumer.class))
        .addAppender(appender);
    return appender;
  }

  private static void release(
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
    ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EnvelopeConsumer.class))
        .detachAppender(appender);
  }

  @Test
  @Tag("NFR-REL-01")
  void envelopesWithoutAnObjectPayloadAreMalformed() throws Exception {
    // I19, the envelope: a payload that is absent, null or not an object is dead-lettered at once
    // as malformed_envelope; the handler is never called with it.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> handled.add(sequence(envelope)),
            EnvelopeConsumer.RetryBound.DEFAULT,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      String prefix =
          "{\"event_id\":\"" + UUID.randomUUID() + "\",\"institution_id\":\"" + institution + "\"";
      publish(topic, "key", prefix + "}", Map.of());
      publish(topic, "key", prefix + ",\"payload\":null}", Map.of());
      publish(topic, "key", prefix + ",\"payload\":[1,2]}", Map.of());
      publish(topic, "key", prefix + ",\"payload\":\"text\"}", Map.of());
      publish(topic, institution, 5);
      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(5));
      assertThat(consumer.deadLettered()).isEqualTo(4);
    }
    assertThat(handled).containsExactly(5);
    assertThat(drain(topic + ".dlq", 4))
        .extracting(r -> header(r, "fs-dlq-reason"))
        .containsOnly("malformed_envelope");
  }

  @Test
  @Tag("FR-03-04")
  void recordsThatKeepFailingAfterTheirSendLeaveAsOutcomeUnrecordedWithTheResult()
      throws Exception {
    // I17 with I20: a kept result, then F4 until the bound: the dead letter is outcome_unrecorded,
    // not handler_failure, and carries the result.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    AtomicInteger attempts = new AtomicInteger();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              if (attempts.incrementAndGet() == 1) {
                envelope.keeper().keep(SENT);
              }
              throw new IllegalStateException("outcome write keeps failing (test)");
            },
            new EnvelopeConsumer.RetryBound(3, Duration.ZERO, Duration.ofSeconds(60)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      publish(topic, institution, 1);
      await().atMost(Duration.ofSeconds(30)).until(() -> consumer.deadLettered() == 1);
    }
    ConsumerRecord<String, byte[]> dead = drain(topic + ".dlq", 1).getFirst();
    assertThat(attempts.get()).as("the F4 bound, not an immediate dead letter").isEqualTo(3);
    assertThat(header(dead, "fs-dlq-reason")).isEqualTo("outcome_unrecorded");
    assertThat(header(dead, EnvelopeConsumer.DLQ_RESULT_HEADER)).isEqualTo(SENT.text());
  }

  @Test
  @Tag("NFR-REL-01")
  void outagesBetweenOwnFailuresNeitherCountTowardsTheBoundNorClearIt() throws Exception {
    // I17: with a bound of three F4 failures and no time, the pattern F4, F1, F1, F4, F1, F4 is
    // dead-lettered at its sixth attempt. Counting F1 would stop it at the third; clearing on F1
    // would never reach three.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    List<String> pattern = List.of("F4", "F1", "F1", "F4", "F1", "F4", "F4", "F4");
    AtomicInteger attempts = new AtomicInteger();
    AtomicInteger attemptsAtDeadLetter = new AtomicInteger();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              if (sequence(envelope) == 2) {
                attemptsAtDeadLetter.compareAndSet(0, attempts.get());
                handled.add(2);
                return;
              }
              int attempt = attempts.incrementAndGet();
              if (pattern.get(Math.min(attempt, pattern.size()) - 1).equals("F1")) {
                throw new SQLException("database unavailable (test)", "08006");
              }
              throw new IllegalStateException("the record's own failure (test)");
            },
            new EnvelopeConsumer.RetryBound(3, Duration.ZERO, Duration.ofSeconds(60)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      publish(topic, institution, 1);
      publish(topic, institution, 2);
      await().atMost(Duration.ofSeconds(60)).until(() -> handled.contains(2));
    }
    assertThat(attemptsAtDeadLetter.get()).isEqualTo(6);
  }

  @Test
  @Tag("NFR-REL-01")
  void resetsDropTheOldHeadsRetryState() throws Exception {
    // I22: the committed checkpoint carries four F4 failures for offset 0, which retention then
    // deletes. With a bound of five, an inherited count would dead-letter record 5 at its first
    // failure; dropped, record 5 fails once and is then handled. The drop is made twice, by the
    // reset handler and by the consumer's head check, so deleting one of them alone does not fail
    // this test; deleting both does.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    for (int sequence = 0; sequence < 7; sequence++) {
      publish(topic, institution, sequence);
    }
    TopicPartition partition = new TopicPartition(topic, 0);
    try (KafkaConsumer<String, byte[]> committer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                group),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      committer.assign(List.of(partition));
      committer.commitSync(
          Map.of(
              partition,
              new OffsetAndMetadata(
                  0, "fs-wait:v3 offset=0 spent_ms=0 at=1 f4_count=4 f4_ms=600000 result=none")));
    }
    try (org.apache.kafka.clients.admin.Admin admin =
        org.apache.kafka.clients.admin.Admin.create(
            Map.of(
                org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers()))) {
      admin
          .deleteRecords(
              Map.of(partition, org.apache.kafka.clients.admin.RecordsToDelete.beforeOffset(5)))
          .all()
          .get();
    }
    AtomicInteger failuresLeft = new AtomicInteger(1);
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            group,
            envelope -> {
              if (sequence(envelope) == 5 && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("one failure (test)");
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.RetryBound(5, Duration.ZERO, Duration.ofSeconds(60)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(6));
      assertThat(consumer.deadLettered()).isZero();
    }
    assertThat(handled).containsExactly(5, 6);
  }

  @Test
  @Tag("NFR-REL-01")
  void theBatchBudgetKeepsEachMemberWithinItsPollInterval() throws Exception {
    // I26: eight 700 ms records in one batch take 5.6 s, past a 3 s max.poll.interval.ms. With a
    // 1 s batch budget the consumer polls in time and every record is handled once; without the
    // budget the member is expelled, its commit fails, and the re-read handles records twice.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    for (int sequence = 1; sequence <= 8; sequence++) {
      publish(topic, institution, sequence);
    }
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              try {
                Thread.sleep(700);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              handled.add(sequence(envelope));
            },
            new EnvelopeConsumer.RetryBound(5, Duration.ofMinutes(10), Duration.ofSeconds(1)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 3000))) {
      await().atMost(Duration.ofSeconds(90)).until(() -> handled.contains(8));
      Thread.sleep(2000); // a duplicate, had there been an expulsion, would arrive by now
    }
    assertThat(handled).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
  }

  @Test
  @Tag("FR-03-04")
  void replayTakesTheNewestCopyAcrossReasonsAndCarriesOnlyTheResultItRecorded() throws Exception {
    // I28 and implementation-3 MAJOR 3 and m3, on DLQ copies made by hand. Event E1 has an older
    // parent_not_recorded copy and a newer outcome_unrecorded one carrying its result and a stale
    // fs-kept-result; event E2 is handler_failure with an fs-kept-result it never recorded.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    String e1 = envelope(institution, 1);
    String e2 = envelope(institution, 2);
    publish(
        topic + ".dlq",
        "key",
        e1,
        Map.of("fs-dlq-reason", "parent_not_recorded", "fs-dlq-topic", topic));
    publish(
        topic + ".dlq",
        "key",
        e1,
        Map.of(
            "fs-dlq-reason",
            "outcome_unrecorded",
            "fs-dlq-topic",
            topic,
            EnvelopeConsumer.DLQ_RESULT_HEADER,
            SENT.text(),
            EnvelopeConsumer.KEPT_RESULT_HEADER,
            "SENT:en:0:c3RhbGU",
            EnvelopeConsumer.REPLAYED_HEADER,
            "1"));
    publish(
        topic + ".dlq",
        "key",
        e2,
        Map.of(
            "fs-dlq-reason",
            "handler_failure",
            "fs-dlq-topic",
            topic,
            EnvelopeConsumer.KEPT_RESULT_HEADER,
            SENT.text()));
    Map<String, Object> kafkaProperties =
        Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());

    DeadLetterReplay.Result parents =
        DeadLetterReplay.replay(
            kafkaProperties, topic, Set.of("parent_not_recorded"), Instant.EPOCH, false);
    assertThat(parents.republished())
        .as("E1's newest copy is outcome_unrecorded: its older copy is not replayed over it")
        .isZero();

    DeadLetterReplay.Result rest =
        DeadLetterReplay.replay(
            kafkaProperties,
            topic,
            Set.of("outcome_unrecorded", "handler_failure"),
            Instant.EPOCH,
            false);
    assertThat(rest.republished()).isEqualTo(2);
    List<ConsumerRecord<String, byte[]>> again = drain(topic, 2);
    ConsumerRecord<String, byte[]> first =
        again.stream()
            .filter(r -> new String(r.value(), StandardCharsets.UTF_8).equals(e1))
            .findFirst()
            .orElseThrow();
    ConsumerRecord<String, byte[]> second =
        again.stream()
            .filter(r -> new String(r.value(), StandardCharsets.UTF_8).equals(e2))
            .findFirst()
            .orElseThrow();
    List<String> keptOnFirst = new ArrayList<>();
    first
        .headers()
        .headers(EnvelopeConsumer.KEPT_RESULT_HEADER)
        .forEach(h -> keptOnFirst.add(new String(h.value(), StandardCharsets.UTF_8)));
    assertThat(keptOnFirst).as("exactly one, the recorded result").containsExactly(SENT.text());
    assertThat(second.headers().headers(EnvelopeConsumer.KEPT_RESULT_HEADER))
        .as("a result the dead letter did not record is not carried")
        .isEmpty();
  }

  @Test
  @Tag("NFR-REL-01")
  void droppedRecordsAreLoggedInFull() throws Exception {
    // I27's log line: a record whose dead letter never fits is logged at ERROR with its offset,
    // key, headers, value digest and length, its notification id and its kept result.
    String topic = newTopic();
    UUID institution = UUID.randomUUID();
    var logs = captureLogs();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            "test-group-" + UUID.randomUUID(),
            envelope -> {
              envelope.keeper().keep(SENT);
              throw new MalformedPayloadException("unreadable (test)", null);
            },
            new EnvelopeConsumer.RetryBound(2, Duration.ZERO, Duration.ofSeconds(60)),
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 2000))) {
      UUID notification = UUID.randomUUID();
      String padded =
          "{\"event_id\":\""
              + UUID.randomUUID()
              + "\",\"institution_id\":\""
              + institution
              + "\",\"payload\":{\"decision_sequence\":1,\"notification_id\":\""
              + notification
              + "\",\"pad\":\""
              + "p".repeat(4000)
              + "\"}}";
      publish(topic, "drop-key", padded, Map.of("x-test", "drop"));
      await()
          .atMost(Duration.ofSeconds(60))
          .until(
              () ->
                  logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("is DROPPED")));
      String line =
          logs.list.stream()
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .filter(m -> m.contains("is DROPPED"))
              .findFirst()
              .orElseThrow();
      assertThat(line)
          .contains("offset 0")
          .contains("key drop-key")
          .contains("x-test=drop")
          .contains("sha256")
          .contains("length " + padded.getBytes(StandardCharsets.UTF_8).length)
          .contains("N " + notification)
          .contains("kept result " + SENT.text());
    } finally {
      release(logs);
    }
  }

  @Test
  @Tag("NFR-REL-01")
  void committedMetadataThatDoesNotParseIsWarnedAndReadAsNone() throws Exception {
    // I29's WARN: a checkpoint that is not v2 or v3 is logged and read as no metadata.
    String topic = newTopic();
    String group = "test-group-" + UUID.randomUUID();
    UUID institution = UUID.randomUUID();
    publish(topic, institution, 1);
    TopicPartition partition = new TopicPartition(topic, 0);
    try (KafkaConsumer<String, byte[]> committer =
        new KafkaConsumer<>(
            Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                group),
            new StringDeserializer(),
            new ByteArrayDeserializer())) {
      committer.assign(List.of(partition));
      committer.commitSync(Map.of(partition, new OffsetAndMetadata(0, "fs-wait:v9 garbage")));
    }
    var logs = captureLogs();
    List<Integer> handled = new CopyOnWriteArrayList<>();
    try (EnvelopeConsumer consumer =
        consumer(
            topic,
            group,
            envelope -> handled.add(sequence(envelope)),
            EnvelopeConsumer.RetryBound.DEFAULT,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
            Map.of())) {
      await().atMost(Duration.ofSeconds(30)).until(() -> handled.contains(1));
      assertThat(logs.list)
          .anySatisfy(
              e -> {
                assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                assertThat(e.getFormattedMessage()).contains("does not parse");
              });
    } finally {
      release(logs);
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
