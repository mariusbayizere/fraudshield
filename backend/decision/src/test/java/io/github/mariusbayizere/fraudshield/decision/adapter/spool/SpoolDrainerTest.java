package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Review finding 3: a consumer's position moves only past records its sink accepted, and a record
 * whose writer never took it is never written (D-15).
 */
@Tag("D-15")
class SpoolDrainerTest {

  private static final String CONSUMER = "postgres";
  private static final Duration WAIT = Duration.ofSeconds(10);

  @TempDir Path directory;

  private DurableSpool open() {
    return new DurableSpool(directory, DurableSpool.Settings.DEFAULTS, Set.of(CONSUMER));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String text(SpoolRecord record) {
    return new String(record.payload(), StandardCharsets.UTF_8);
  }

  @Test
  void refusedBatchesAreRetriedAndNothingIsCommittedPastThem() throws Exception {
    List<String> delivered = new CopyOnWriteArrayList<>();
    AtomicInteger refusals = new AtomicInteger(5);
    List<Long> committedWhileRefusing = new CopyOnWriteArrayList<>();
    try (DurableSpool spool = open()) {
      for (int i = 0; i < 20; i++) {
        spool.appendAndWait(bytes("record-" + i), WAIT);
      }
      SpoolDrainer.Sink sink =
          batch -> {
            if (refusals.getAndDecrement() > 0) {
              committedWhileRefusing.add(spool.committed(CONSUMER));
              throw new SpoolDrainer.SinkException("refused (test)", null);
            }
            batch.forEach(record -> delivered.add(text(record)));
          };
      try (SpoolDrainer drainer =
          new SpoolDrainer(spool, CONSUMER, sink, 7, Duration.ofMillis(5), Duration.ofMillis(20))) {
        // The drainer counts a batch after its sink returns, so wait on its count, not the sink's.
        await().atMost(WAIT).until(() -> drainer.delivered() >= 20);
        assertThat(drainer.delivered()).isEqualTo(20);
      }
      assertThat(committedWhileRefusing).hasSize(5).containsOnly(0L);
      assertThat(delivered)
          .as("every record once, in order, none lost to a refused batch")
          .containsExactlyElementsOf(
              java.util.stream.IntStream.range(0, 20).mapToObj(i -> "record-" + i).toList());
      assertThat(spool.read(spool.committed(CONSUMER), 100)).isEmpty();
    }
  }

  @Test
  void consumersStoppedWhileTheirSinkFailsResumeFromTheLastAcceptedRecord() throws Exception {
    List<String> delivered = new CopyOnWriteArrayList<>();
    try (DurableSpool spool = open()) {
      for (int i = 0; i < 4; i++) {
        spool.appendAndWait(bytes("record-" + i), WAIT);
      }
      AtomicInteger calls = new AtomicInteger();
      SpoolDrainer.Sink acceptsOnlyTheFirstBatch =
          batch -> {
            if (calls.getAndIncrement() > 0) {
              throw new SpoolDrainer.SinkException("down (test)", null);
            }
            batch.forEach(record -> delivered.add(text(record)));
          };
      try (SpoolDrainer failing =
          new SpoolDrainer(
              spool,
              CONSUMER,
              acceptsOnlyTheFirstBatch,
              2,
              Duration.ofMillis(5),
              Duration.ofMillis(20))) {
        await().atMost(WAIT).until(() -> calls.get() >= 3);
        assertThat(failing.failures()).isPositive();
      }
      SpoolDrainer.Sink healthy = batch -> batch.forEach(record -> delivered.add(text(record)));
      try (SpoolDrainer resumed =
          new SpoolDrainer(
              spool, CONSUMER, healthy, 2, Duration.ofMillis(5), Duration.ofMillis(20))) {
        await().atMost(WAIT).until(() -> delivered.size() >= 4);
      }
      assertThat(delivered).containsExactly("record-0", "record-1", "record-2", "record-3");
    }
  }

  @Test
  void recordsTheWriterNeverTookAreWithdrawnAndNeverWritten() {
    try (DurableSpool spool =
        new DurableSpool(directory, DurableSpool.Settings.DEFAULTS, Set.of(CONSUMER), false)) {
      assertThatThrownBy(() -> spool.appendAndWait(bytes("withdrawn"), Duration.ofMillis(50)))
          .isInstanceOf(SpoolFullException.class)
          .hasMessageContaining("withdrawn");
      spool.startWriter();
      spool.appendAndWait(bytes("kept"), WAIT);
      assertThat(spool.read(0, 10)).extracting(SpoolDrainerTest::text).containsExactly("kept");
    }
  }
}
