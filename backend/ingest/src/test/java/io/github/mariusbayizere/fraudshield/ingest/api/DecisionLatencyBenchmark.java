package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end decision latency (M6 gate, C.2): fixed arrival rates against the full API on real
 * PostgreSQL, Redis and Kafka, timed from each request's scheduled send time so a stall is not
 * hidden (no coordinated omission). The scorer is a gRPC double that answers at once, so model
 * inference is excluded and measured by M5; the result is the API's own share of the budget.
 * Opt-in: runs only with {@code -Dfs.benchmark=true} and writes docs/benchmarks.
 */
@Tag("requires-docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(ApiHarness.Collaborators.class)
class DecisionLatencyBenchmark {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Payments cycle through this many accounts, so most find their state in Redis. */
  private static final int ACCOUNTS = 1000;

  @Autowired Environment environment;
  @Autowired io.micrometer.core.instrument.MeterRegistry registry;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
    registry.add("fraudshield.scorer.deadline", () -> "35ms");
    registry.add("fraudshield.redis-timeout", () -> "50ms");
  }

  private record Run(int rate, long[] micros, long[] serverMillis, int errors, double seconds) {}

  @Test
  void decisionLatencyAtFixedArrivalRates() throws Exception {
    assumeTrue(Boolean.getBoolean("fs.benchmark"), "opt-in: -Dfs.benchmark=true");
    ApiHarness.SCORER.score =
        r -> {
          int bucket = Math.floorMod(r.getTransaction().getTransactionId().hashCode(), 100);
          return bucket < 3 ? 0.9 : bucket < 10 ? 0.7 : 0.1;
        };
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .executor(Executors.newFixedThreadPool(64))
            .build();
    URI uri =
        URI.create(
            "http://127.0.0.1:"
                + environment.getProperty("local.server.port")
                + "/api/v1/transactions/ingest");
    run(client, uri, 50, Duration.ofSeconds(15));
    List<Run> runs = new ArrayList<>();
    for (String rate : System.getProperty("fs.benchmark.rates", "25,50,100,150,200").split(",")) {
      runs.add(
          run(
              client,
              uri,
              Integer.parseInt(rate.strip()),
              Duration.ofSeconds(Integer.getInteger("fs.benchmark.seconds", 30))));
    }
    write(runs);
  }

  private Run run(HttpClient client, URI uri, int rate, Duration length) throws Exception {
    ConcurrentLinkedQueue<long[]> samples = new ConcurrentLinkedQueue<>();
    AtomicInteger errors = new AtomicInteger();
    long interval = 1_000_000_000L / rate;
    long total = length.toSeconds() * rate;
    long start = System.nanoTime();
    try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
      for (long i = 0; i < total; i++) {
        long scheduled = start + i * interval;
        long wait = scheduled - System.nanoTime();
        if (wait > 0) {
          TimeUnit.NANOSECONDS.sleep(wait);
        }
        final ObjectNode payload =
            IngestApiTest.body(UUID.randomUUID(), i % 10 == 0 ? "USSD" : "MOBILE_MONEY");
        payload.put("account_id", String.format("tok_BenchmarkAccount%08d", i % ACCOUNTS));
        final String body = payload.toString();
        senders.execute(
            () -> {
              try {
                HttpResponse<String> response =
                    client.send(
                        HttpRequest.newBuilder(uri)
                            .header("Content-Type", "application/json")
                            .header("X-API-Key", ApiHarness.KEY)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                        HttpResponse.BodyHandlers.ofString());
                long micros = (System.nanoTime() - scheduled) / 1_000;
                if (response.statusCode() != 200) {
                  errors.incrementAndGet();
                  return;
                }
                samples.add(
                    new long[] {
                      micros, JSON.readTree(response.body()).get("decision_latency_ms").asLong()
                    });
              } catch (Exception e) {
                errors.incrementAndGet();
              }
            });
      }
    }
    double seconds = (System.nanoTime() - start) / 1e9;
    long[] micros = samples.stream().mapToLong(s -> s[0]).sorted().toArray();
    long[] server = samples.stream().mapToLong(s -> s[1]).sorted().toArray();
    return new Run(rate, micros, server, errors.get(), seconds);
  }

  private static double percentile(long[] sorted, double p) {
    if (sorted.length == 0) {
      return Double.NaN;
    }
    return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)];
  }

  private void write(List<Run> runs) throws Exception {
    ObjectNode report = JSON.createObjectNode();
    report.put("benchmark", "M6 end-to-end decision latency (POST /api/v1/transactions/ingest)");
    report.put("measured_at", Instant.now().toString());
    report.put(
        "what_is_measured",
        "client-observed latency from each request's scheduled send"
            + " time to the full response, on one host that also runs the load generator and the"
            + " PostgreSQL, Redis and Kafka containers; the scorer is a gRPC double answering at"
            + " once, so model inference (M5) is excluded; decision mix about 90% LOW, 7% MEDIUM,"
            + " 3% HIGH; 10% USSD; payments cycle through 1,000 accounts; the feature-store"
            + " update and MCC counting run after the response");
    ObjectNode hardware = report.putObject("hardware");
    hardware.put("available_processors", Runtime.getRuntime().availableProcessors());
    hardware.put("max_heap_mb", Runtime.getRuntime().maxMemory() >> 20);
    hardware.put(
        "load_average_1m_at_end",
        java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    hardware.put(
        "shared_with",
        "the dev-container compose stack (PostgreSQL, Kafka, Redis, MLflow, object store,"
            + " PII vault, WireMock, Mailpit) and the editor; none was stopped for the run");
    hardware.put(
        "cpu",
        Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
            .filter(l -> l.startsWith("model name"))
            .findFirst()
            .orElse("unknown")
            .replaceFirst("model name\\s*:\\s*", ""));
    hardware.put("memory", Files.readAllLines(Path.of("/proc/meminfo")).getFirst());
    hardware.put("java", System.getProperty("java.version"));
    ObjectNode stages = report.putObject("server_stage_ms_over_all_runs");
    for (io.micrometer.core.instrument.Timer timer : registry.find("fs_decision_stage").timers()) {
      ObjectNode stage = stages.putObject(timer.getId().getTag("stage"));
      for (var value : timer.takeSnapshot().percentileValues()) {
        stage.put(
            "p" + Math.round(value.percentile() * 100),
            value.value(java.util.concurrent.TimeUnit.MILLISECONDS));
      }
      stage.put("mean", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
    }
    ArrayNode results = report.putArray("runs");
    for (Run run : runs) {
      ObjectNode r = results.addObject();
      r.put("target_rate_per_s", run.rate());
      r.put("achieved_rate_per_s", Math.round(run.micros().length / run.seconds()));
      r.put("requests_ok", run.micros().length);
      r.put("errors", run.errors());
      r.put("client_p50_ms", percentile(run.micros(), 0.50) / 1000.0);
      r.put("client_p95_ms", percentile(run.micros(), 0.95) / 1000.0);
      r.put("client_p99_ms", percentile(run.micros(), 0.99) / 1000.0);
      r.put(
          "client_max_ms",
          run.micros().length == 0 ? 0 : run.micros()[run.micros().length - 1] / 1000.0);
      r.put("server_decision_p50_ms", percentile(run.serverMillis(), 0.50));
      r.put("server_decision_p95_ms", percentile(run.serverMillis(), 0.95));
      r.put("server_decision_p99_ms", percentile(run.serverMillis(), 0.99));
      r.put("p95_under_50_ms", percentile(run.micros(), 0.95) / 1000.0 < 50);
    }
    Path out = Path.of("..", "..", "docs", "benchmarks", "2026-09-22-M6-decision-latency.json");
    Files.writeString(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    System.out.println(Arrays.toString(Files.readAllLines(out).toArray()));
  }
}
