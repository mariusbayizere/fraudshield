package io.github.mariusbayizere.fraudshield.decision.adapter.spool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("D-15")
@Tag("FR-01-01")
class DurableSpoolTest {

  private static final Set<String> CONSUMERS = Set.of("kafka", "postgres");
  private static final Duration WAIT = Duration.ofSeconds(10);

  @TempDir Path directory;

  private DurableSpool open(DurableSpool.Settings settings) {
    return new DurableSpool(directory, settings, CONSUMERS);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static List<String> texts(List<SpoolRecord> records) {
    return records.stream().map(r -> new String(r.payload(), StandardCharsets.UTF_8)).toList();
  }

  @Test
  void recordsAreReadBackInOrderByEachConsumerIndependently() {
    try (DurableSpool spool = open(DurableSpool.Settings.DEFAULTS)) {
      for (int i = 0; i < 5; i++) {
        spool.appendAndWait(bytes("record-" + i), WAIT);
      }
      List<SpoolRecord> all = spool.read(spool.committed("kafka"), 100);
      assertThat(texts(all))
          .containsExactly("record-0", "record-1", "record-2", "record-3", "record-4");
      spool.commit("kafka", all.get(2).nextOffset());
      assertThat(texts(spool.read(spool.committed("kafka"), 100)))
          .containsExactly("record-3", "record-4");
      assertThat(texts(spool.read(spool.committed("postgres"), 2)))
          .containsExactly("record-0", "record-1");
      assertThat(spool.lagBytes("kafka")).isLessThan(spool.lagBytes("postgres"));
      assertThat(spool.depthBytes()).isEqualTo(spool.lagBytes("postgres"));
    }
  }

  @Test
  void concurrentAppendsShareFsyncs() throws Exception {
    int appends = 2000;
    try (DurableSpool spool = open(DurableSpool.Settings.DEFAULTS);
        ExecutorService pool = Executors.newFixedThreadPool(32)) {
      List<CompletableFuture<Long>> done = new ArrayList<>();
      for (int i = 0; i < appends; i++) {
        int n = i;
        done.add(
            CompletableFuture.supplyAsync(() -> spool.appendAndWait(bytes("r" + n), WAIT), pool));
      }
      CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);
      assertThat(spool.read(0, appends + 1)).hasSize(appends);
      assertThat(spool.fsyncCount()).isLessThan(appends);
      assertThat(done.stream().map(CompletableFuture::join).distinct()).hasSize(appends);
    }
  }

  @Test
  void positionsAndRecordsSurviveRestarts() {
    long committed;
    try (DurableSpool spool = open(DurableSpool.Settings.DEFAULTS)) {
      spool.appendAndWait(bytes("a"), WAIT);
      spool.appendAndWait(bytes("b"), WAIT);
      committed = spool.read(0, 1).getFirst().nextOffset();
      spool.commit("kafka", committed);
    }
    try (DurableSpool reopened = open(DurableSpool.Settings.DEFAULTS)) {
      assertThat(reopened.committed("kafka")).isEqualTo(committed);
      assertThat(texts(reopened.read(reopened.committed("kafka"), 10))).containsExactly("b");
      assertThat(texts(reopened.read(reopened.committed("postgres"), 10)))
          .containsExactly("a", "b");
      reopened.appendAndWait(bytes("c"), WAIT);
      assertThat(texts(reopened.read(committed, 10))).containsExactly("b", "c");
    }
  }

  @Test
  void tornFinalRecordsAreTruncatedOnRecovery() throws IOException {
    try (DurableSpool spool = open(DurableSpool.Settings.DEFAULTS)) {
      spool.appendAndWait(bytes("complete"), WAIT);
    }
    Path segment = onlySegment();
    // A crash mid-write: a header announcing 100 bytes followed by 3.
    Files.write(segment, new byte[] {0, 0, 0, 100, 1, 2, 3, 4, 9, 9, 9}, StandardOpenOption.APPEND);
    try (DurableSpool spool = open(DurableSpool.Settings.DEFAULTS)) {
      assertThat(texts(spool.read(0, 10))).containsExactly("complete");
      spool.appendAndWait(bytes("after"), WAIT);
      assertThat(texts(spool.read(0, 10))).containsExactly("complete", "after");
    }
  }

  @Test
  void corruptionBeforeTheLastSegmentRefusesToOpen() throws IOException {
    DurableSpool.Settings small = new DurableSpool.Settings(1 << 20, 64, 100);
    try (DurableSpool spool = open(small)) {
      for (int i = 0; i < 10; i++) {
        spool.appendAndWait(bytes("record-" + i + "-with-some-length"), WAIT);
      }
    }
    Path first;
    try (Stream<Path> files = Files.list(directory)) {
      first = files.filter(p -> p.toString().endsWith(".seg")).sorted().findFirst().orElseThrow();
    }
    byte[] content = Files.readAllBytes(first);
    content[content.length - 1] ^= 0x55;
    Files.write(first, content);
    assertThatThrownBy(() -> open(small))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("corrupt");
  }

  @Test
  void theBoundRefusesRecordsInsteadOfDroppingThem() {
    DurableSpool.Settings tiny = new DurableSpool.Settings(100, 1 << 20, 100);
    try (DurableSpool spool = open(tiny)) {
      spool.appendAndWait(new byte[40], WAIT);
      spool.appendAndWait(new byte[40], WAIT);
      assertThatThrownBy(() -> spool.append(new byte[40])).isInstanceOf(SpoolFullException.class);
      // Only when every consumer has passed a record is its space released.
      long end = spool.durableEnd();
      spool.commit("kafka", end);
      assertThatThrownBy(() -> spool.append(new byte[40])).isInstanceOf(SpoolFullException.class);
      spool.commit("postgres", end);
      assertThat(spool.appendAndWait(new byte[40], WAIT)).isEqualTo(end);
    }
  }

  @Test
  void segmentsAreDeletedOnlyAfterEveryConsumerPassesThem() throws IOException {
    DurableSpool.Settings small = new DurableSpool.Settings(1 << 20, 100, 100);
    try (DurableSpool spool = open(small)) {
      for (int i = 0; i < 20; i++) {
        spool.appendAndWait(new byte[60], WAIT);
      }
      long segmentsBefore = segmentCount();
      assertThat(segmentsBefore).isGreaterThan(5);
      spool.commit("kafka", spool.durableEnd());
      assertThat(segmentCount()).isEqualTo(segmentsBefore);
      spool.commit("postgres", spool.durableEnd());
      assertThat(segmentCount()).isEqualTo(1);
      assertThat(spool.depthBytes()).isZero();
    }
    try (DurableSpool reopened = open(small)) {
      assertThat(reopened.read(reopened.committed("kafka"), 10)).isEmpty();
      reopened.appendAndWait(bytes("next"), WAIT);
      assertThat(texts(reopened.read(reopened.committed("postgres"), 10))).containsExactly("next");
    }
  }

  @Test
  void invalidUseIsRejected() {
    try (DurableSpool spool = open(DurableSpool.Settings.DEFAULTS)) {
      assertThatThrownBy(() -> spool.append(new byte[0]))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> spool.committed("other"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> spool.commit("kafka", 10))
          .isInstanceOf(IllegalArgumentException.class);
      spool.close();
      assertThatThrownBy(() -> spool.append(bytes("x"))).isInstanceOf(SpoolFullException.class);
    }
    assertThatThrownBy(() -> new DurableSpool(directory, DurableSpool.Settings.DEFAULTS, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DurableSpool.Settings(0, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    SpoolRecord record = new SpoolRecord(1, 5, bytes("x"));
    assertThat(record)
        .isEqualTo(new SpoolRecord(1, 9, bytes("y")))
        .hasSameHashCodeAs(new SpoolRecord(1, 9, bytes("y")));
    assertThat(record.toString()).isEqualTo("SpoolRecord[1..5]");
  }

  private Path onlySegment() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(p -> p.toString().endsWith(".seg")).findFirst().orElseThrow();
    }
  }

  private long segmentCount() throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.filter(p -> p.toString().endsWith(".seg")).count();
    }
  }
}
