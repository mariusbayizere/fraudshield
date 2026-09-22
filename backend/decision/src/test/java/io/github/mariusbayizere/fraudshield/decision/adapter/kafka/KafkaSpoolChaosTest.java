package io.github.mariusbayizere.fraudshield.decision.adapter.kafka;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessages;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.DurableSpool;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolDrainer;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolingEventRecorder;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.KafkaTestCluster;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/**
 * D-15 chaos test: Kafka stops answering while decisions continue, then the API process dies with a
 * backlog in its spool. After both come back, every decided transaction is on Kafka, reconciled by
 * {@code transaction_id}.
 *
 * <p><b>Limitation (Principal Review finding 11, ADR 0064).</b> "Process death" here is an in-JVM
 * {@code close()}: the writer thread is joined and {@code KafkaProducer.close()} flushes whatever
 * it had buffered. That is a clean shutdown with a backlog, not a SIGKILL, so this test shows that
 * the spool replays from its checkpoints, not that it survives an abrupt kill. What a kill would
 * add — a torn final frame, an fsync that never returned — is covered by {@code DurableSpoolTest}
 * (truncated frames, reopen) and {@code SpoolDrainerTest} (a refused sink), but not end to end.
 * D-15's "kill the pod" remains unproven; running the writer in a forked JVM and destroying it is
 * the open work.
 */
@Tag("requires-docker")
@Tag("D-15")
@Tag("FR-01-01")
@Tag("NFR-REL-02")
class KafkaSpoolChaosTest {

  private static final Set<String> CONSUMERS = Set.of("kafka");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static KafkaTestCluster kafka;

  @TempDir Path spoolDirectory;

  @BeforeAll
  static void startKafka() {
    kafka = new KafkaTestCluster();
  }

  @AfterAll
  static void stopKafka() {
    kafka.close();
  }

  private static SpoolDrainer drainer(DurableSpool spool, KafkaSink sink) {
    return new SpoolDrainer(
        spool, "kafka", sink, 200, Duration.ofMillis(20), Duration.ofSeconds(2));
  }

  private static KafkaSink sink() {
    return new KafkaSink(
        kafka.bootstrapServers(), Map.of(), new KafkaMessages("fraudshield-api", 0), 5_000);
  }

  private static Set<UUID> decide(DecisionService service, int count) {
    Set<UUID> ids = new HashSet<>();
    for (int i = 0; i < count; i++) {
      UUID id = UUID.randomUUID();
      service.decide(
          Fixtures.transaction(
              id,
              "tok_ChaosAccountAaaaBbbbCccc" + (i % 10),
              "1500",
              Channel.values()[i % Channel.values().length]),
          new byte[32],
          System.nanoTime());
      ids.add(id);
    }
    return ids;
  }

  private static DecisionService service(DurableSpool spool) {
    InMemoryPorts ports = new InMemoryPorts();
    ports.scores = t -> Optional.of(Math.abs(t.transactionId().hashCode() % 100) / 100.0);
    return new DecisionService(
        ports,
        ports,
        ports,
        ports,
        ports,
        ports,
        ports,
        new SpoolingEventRecorder(spool, Duration.ofSeconds(5)),
        ports,
        DecisionMetrics.NONE,
        DecisionSettings.DEFAULTS,
        new MutableClock(NOW));
  }

  @Test
  void noDecisionIsLostWhenKafkaAndTheApiProcessBothFail() {
    Set<UUID> decided = new HashSet<>();

    DurableSpool spool =
        new DurableSpool(spoolDirectory, DurableSpool.Settings.DEFAULTS, CONSUMERS);
    KafkaSink sink = sink();
    SpoolDrainer drainer = drainer(spool, sink);
    DecisionService service = service(spool);
    decided.addAll(decide(service, 200));

    kafka.pause();
    try {
      // Kafka is gone; decisions keep succeeding because they only need the local spool.
      decided.addAll(decide(service, 300));
      assertThat(spool.lagBytes("kafka")).isPositive();
    } finally {
      // The API process dies with a backlog: nothing is flushed or handed over.
      drainer.close();
      spool.close();
      kafka.unpause();
    }
    sink.close();

    try (DurableSpool restarted =
            new DurableSpool(spoolDirectory, DurableSpool.Settings.DEFAULTS, CONSUMERS);
        KafkaSink newSink = sink();
        SpoolDrainer newDrainer = drainer(restarted, newSink)) {
      await().atMost(Duration.ofSeconds(90)).until(() -> restarted.lagBytes("kafka") == 0);
      List<ConsumerRecord<String, byte[]>> records =
          kafka.readAll(
              KafkaMessages.RAW, ConsumerRecord::key, decided.size(), Duration.ofSeconds(60));
      Set<UUID> published = new HashSet<>();
      for (ConsumerRecord<String, byte[]> record : records) {
        published.add(
            UUID.fromString(
                JSON.readTree(record.value()).get("payload").get("transaction_id").asString()));
      }
      assertThat(published).as("transactions on fs.transactions.raw").isEqualTo(decided);
      assertThat(decided).hasSize(500);
      assertThat(newDrainer.delivered()).isPositive();
    }
  }
}
