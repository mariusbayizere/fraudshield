package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.decision.adapter.resilience.DegradedMode;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.notify.verification.VerificationService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The API under a duplicate storm and with Redis down, and the customer verification page over
 * HTTP.
 */
@Tag("requires-docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(ApiHarness.Collaborators.class)
class ResilienceApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Autowired Environment environment;
  @Autowired VerificationService verifications;
  @Autowired DegradedMode degraded;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
    // These tests are about idempotency and fallbacks, not about E.1's budget: a 10,000-duplicate
    // storm from one key would be rate-limited in production, and RateLimitApiTest is where that
    // is asserted. The budget here is lifted so the storm reaches the idempotency store.
    registry.add("fraudshield.rate-limit.requests-per-second", () -> "50000");
    registry.add("fraudshield.rate-limit.burst", () -> "50000");
  }

  @BeforeEach
  void resetScorer() {
    ApiHarness.SCORER.score = r -> 0.1;
    ApiHarness.SCORER.failure = null;
    ApiHarness.SCORER.delayMillis = 0;
  }

  private String url(String path) {
    return "http://127.0.0.1:" + environment.getProperty("local.server.port") + path;
  }

  private HttpResponse<String> post(String path, String json) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .header("X-API-Key", ApiHarness.KEY)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String one(String sql) throws Exception {
    try (Connection c = ApiHarness.DB.superuser();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      return r.next() ? r.getString(1) : null;
    }
  }

  @Test
  @Tag("FR-01-03")
  void tenThousandDuplicatesAreScoredOnceAndAnsweredIdentically() throws Exception {
    String payload = IngestApiTest.body(UUID.randomUUID(), "MOBILE_MONEY").toString();
    int before = ApiHarness.SCORER.calls.get();
    List<Callable<HttpResponse<String>>> storm = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      storm.add(() -> post("/api/v1/transactions/ingest", payload));
    }
    List<String> bodies = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
      for (Future<HttpResponse<String>> future : pool.invokeAll(storm)) {
        HttpResponse<String> response = future.get();
        assertThat(response.statusCode()).isEqualTo(200);
        bodies.add(response.body());
      }
    }
    assertThat(ApiHarness.SCORER.calls.get() - before).isEqualTo(1);
    assertThat(bodies).hasSize(10_000);
    assertThat(bodies.stream().distinct()).hasSize(1);
  }

  @Test
  @Tag("NFR-REL-01")
  @Tag("FR-01-03")
  void withRedisDownDecisionsContinueAndDuplicatesReplayFromPostgres() throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode body = IngestApiTest.body(id, "CARD");
    final String original = body.toString();
    HttpResponse<String> first;
    ApiHarness.REDIS.pause();
    try {
      first = post("/api/v1/transactions/ingest", body.toString());
      assertThat(first.statusCode()).isEqualTo(200);
      assertThat(degraded.degraded()).isTrue();
      await()
          .atMost(Duration.ofSeconds(20))
          .until(
              () ->
                  one("SELECT count(*) FROM"
                          + " fraudshield.fraud_scores WHERE transaction_id = '"
                          + id
                          + "'")
                      .equals("1"));
      HttpResponse<String> replay = post("/api/v1/transactions/ingest", body.toString());
      assertThat(replay.statusCode()).isEqualTo(200);
      assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
      assertThat(JSON.readTree(replay.body())).isEqualTo(JSON.readTree(first.body()));
      body.put("amount", "99");
      assertThat(post("/api/v1/transactions/ingest", body.toString()).statusCode()).isEqualTo(409);
      HttpResponse<String> state =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(url("/api/v1/decisions/" + id)))
                  .header("X-API-Key", ApiHarness.KEY)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(state.statusCode()).isEqualTo(200);
    } finally {
      ApiHarness.REDIS.unpause();
    }
    Thread.sleep(DegradedMode.RETRY_AFTER_NANOS / 1_000_000 + 200);
    assertThat(
            post(
                    "/api/v1/transactions/ingest",
                    IngestApiTest.body(UUID.randomUUID(), "CARD").toString())
                .statusCode())
        .isEqualTo(200);
    assertThat(degraded.degraded()).isFalse();

    // Review finding 2: Redis never saw the outage's decision. Resubmitting it after recovery must
    // replay the decision PostgreSQL kept, not score and decide it a second time.
    int scored = ApiHarness.SCORER.calls.get();
    HttpResponse<String> afterRecovery = post("/api/v1/transactions/ingest", original);
    assertThat(afterRecovery.statusCode()).isEqualTo(200);
    assertThat(afterRecovery.headers().firstValue("Idempotent-Replayed")).contains("true");
    assertThat(JSON.readTree(afterRecovery.body())).isEqualTo(JSON.readTree(first.body()));
    assertThat(ApiHarness.SCORER.calls.get()).isEqualTo(scored);
  }

  @Test
  @Tag("NFR-REL-01")
  void withTheScorerDownTheFallbackDecidesAndSaysSo() throws Exception {
    ApiHarness.SCORER.failure = io.grpc.Status.UNAVAILABLE;
    for (int i = 0; i < 20; i++) {
      JsonNode decision =
          JSON.readTree(
              post(
                      "/api/v1/transactions/ingest",
                      IngestApiTest.body(UUID.randomUUID(), "USSD").toString())
                  .body());
      assertThat(decision.get("ml_unavailable_fallback").asBoolean()).isTrue();
      assertThat(decision.get("model_version").asString()).isEqualTo("fallback-rules-2");
      assertThat(decision.get("reason_codes").toString()).contains("ML_UNAVAILABLE");
    }
    ApiHarness.SCORER.failure = null;
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(500))
        .until(
            () ->
                !JSON.readTree(
                        post(
                                "/api/v1/transactions/ingest",
                                IngestApiTest.body(UUID.randomUUID(), "USSD").toString())
                            .body())
                    .get("ml_unavailable_fallback")
                    .asBoolean());
  }

  @Test
  @Tag("NFR-REL-02")
  @Tag("D-15")
  void withPostgresDownDecisionsContinueAndArePersistedAfterRecovery() throws Exception {
    // Warm the configuration cache, then take PostgreSQL away.
    post("/api/v1/transactions/ingest", IngestApiTest.body(UUID.randomUUID(), "CARD").toString());
    var docker = org.testcontainers.DockerClientFactory.instance().client();
    String container =
        io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase.container()
            .getContainerId();
    List<UUID> decided = new ArrayList<>();
    docker.pauseContainerCmd(container).exec();
    try {
      for (int i = 0; i < 20; i++) {
        UUID id = UUID.randomUUID();
        HttpResponse<String> response =
            post("/api/v1/transactions/ingest", IngestApiTest.body(id, "MOBILE_MONEY").toString());
        assertThat(response.statusCode()).isEqualTo(200);
        decided.add(id);
      }
    } finally {
      docker.unpauseContainerCmd(container).exec();
    }
    String ids =
        decided.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + "," + b).orElseThrow();
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofSeconds(1))
        .until(
            () ->
                one("SELECT count(*) FROM fraudshield.decision_states WHERE transaction_id IN ("
                        + ids
                        + ")")
                    .equals("20"));
  }

  @Test
  @Tag("FR-03-05")
  @Tag("D-42")
  void theVerificationPageIsSmallScriptFreeAndLiftsTheBlock() throws Exception {
    ApiHarness.SCORER.score = r -> 0.9;
    UUID id = UUID.randomUUID();
    assertThat(
            post("/api/v1/transactions/ingest", IngestApiTest.body(id, "MOBILE_MONEY").toString())
                .statusCode())
        .isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                one(
                        "SELECT id FROM"
                            + " fraudshield.auto_block_events WHERE transaction_id = '"
                            + id
                            + "'")
                    != null);
    UUID block =
        UUID.fromString(
            one(
                "SELECT id FROM fraudshield.auto_block_events WHERE"
                    + " transaction_id = '"
                    + id
                    + "'"));
    String token = verifications.issue(Fixtures.INSTITUTION, block).orElseThrow();

    HttpResponse<String> page =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/verify/" + token)))
                .header("Accept-Language", "fr-RW, en;q=0.5")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(page.statusCode()).isEqualTo(200);
    assertThat(page.body().getBytes(StandardCharsets.UTF_8).length).isLessThan(30 * 1024);
    assertThat(page.body())
        .doesNotContain("<script")
        .contains("lang=\"fr\"", "15000 RWF", "Oui, c'est moi");
    assertThat(page.headers().firstValue("Content-Security-Policy"))
        .get()
        .asString()
        .contains("default-src 'none'");
    assertThat(page.headers().firstValue("Referrer-Policy")).contains("no-referrer");

    long start = System.nanoTime();
    HttpResponse<String> answered =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/verify/" + token)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("answer=yes&lang=en"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(answered.body()).contains("unblocked");
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                ApiHarness.WEBHOOKS.stream()
                    .anyMatch(
                        w ->
                            w[0].contains(id.toString())
                                && w[0].contains("CUSTOMER_VERIFICATION")));
    assertThat(Duration.ofNanos(System.nanoTime() - start))
        .as("block lifted within 10 s")
        .isLessThan(Duration.ofSeconds(10));
    JsonNode state =
        JSON.readTree(
            HTTP.send(
                    HttpRequest.newBuilder(URI.create(url("/api/v1/decisions/" + id)))
                        .header("X-API-Key", ApiHarness.KEY)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .body());
    assertThat(state.get("decision").asString()).isEqualTo("APPROVE");
    assertThat(state.get("decided_by").asString()).isEqualTo("CUSTOMER_VERIFICATION");

    HttpResponse<String> again =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/verify/" + token + "?lang=sw"))).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(again.body()).contains("Kiungo hiki kimeisha muda au kimetumika");
    HttpResponse<String> bogus =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/verify/not-a-token")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("answer=maybe"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(bogus.body()).contains("expired or was already used");
  }
}
