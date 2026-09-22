package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookSignatures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

/** The ingestion API end to end over HTTP, against real PostgreSQL, Redis and Kafka. */
@Tag("requires-docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(ApiHarness.Collaborators.class)
class IngestApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @Autowired Environment environment;

  @Autowired io.github.mariusbayizere.fraudshield.ingest.application.BatchJobs batches;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
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

  static ObjectNode body(UUID id, String channel) {
    ObjectNode body = JSON.createObjectNode();
    body.put("transaction_id", id.toString());
    body.put("account_id", "tok_ApiAccount" + id.toString().replace("-", "").substring(0, 16));
    body.put("counterparty_id", "tok_Z9y8X7w6V5u4T3s2R1q0P9o8");
    body.put("amount", "15000");
    body.put("currency", "RWF");
    body.put("channel", channel);
    body.put("merchant_category_code", "4829");
    body.put("latitude", -1.9441);
    body.put("longitude", 30.0619);
    if (!channel.equals("USSD")) {
      body.put("device_fingerprint", "tok_D3v1c3F1ng3rpr1ntAaBbCc0");
    }
    if (channel.equals("AGENT_BANKING")) {
      body.put("agent_id", "tok_AgentLlllMmmmNnnnOoooPp01");
    }
    body.put("counterparty_country", "RW");
    body.put("transaction_timestamp", Instant.now().minusSeconds(5).toString());
    return body;
  }

  HttpResponse<String> post(String path, String key, String json) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url(path)))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (key != null) {
      request.header("X-API-Key", key);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> get(String path, String key) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path))).GET();
    if (key != null) {
      request.header("X-API-Key", key);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String one(String sql) throws Exception {
    try (Connection c = ApiHarness.DB.superuser();
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      return r.next() ? r.getString(1) : null;
    }
  }

  @Test
  @Tag("FR-01-01")
  @Tag("FR-03-03")
  @Tag("D-12")
  void lowRiskTransactionsAreApprovedWithExactlyTheDecisionResponseFields() throws Exception {
    UUID id = UUID.randomUUID();
    HttpResponse<String> response =
        post("/api/v1/transactions/ingest", ApiHarness.KEY, body(id, "MOBILE_MONEY").toString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Idempotent-Replayed")).isEmpty();
    assertThat(response.headers().firstValue("X-Correlation-Id")).isPresent();
    JsonNode decision = JSON.readTree(response.body());
    List<String> fields = new ArrayList<>(decision.propertyNames());
    assertThat(fields)
        .containsExactly(
            "transaction_id",
            "decision",
            "risk_tier",
            "reason_codes",
            "scoring_result_id",
            "model_version",
            "decision_latency_ms",
            "review_deadline_at",
            "ml_unavailable_fallback");
    assertThat(decision.get("decision").asString()).isEqualTo("APPROVE");
    assertThat(decision.get("transaction_id").asString()).isEqualTo(id.toString());
    assertThat(decision.get("model_version").asString()).isEqualTo("fs-ensemble-test-double");
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                one("SELECT count(*) FROM"
                        + " fraudshield.decision_states WHERE transaction_id = '"
                        + id
                        + "'")
                    .equals("1"));
    assertThat(
            one("SELECT channel FROM fraudshield.transactions WHERE transaction_id = '" + id + "'"))
        .isEqualTo("MOBILE_MONEY");
  }

  @Test
  @Tag("FR-01-04")
  void everyChannelIsDecidedIncludingUssdWithoutDevices() throws Exception {
    for (String channel :
        List.of("MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER")) {
      HttpResponse<String> response =
          post(
              "/api/v1/transactions/ingest",
              ApiHarness.KEY,
              body(UUID.randomUUID(), channel).toString());
      assertThat(response.statusCode()).as(channel).isEqualTo(200);
    }
    assertThat(
            ApiHarness.SCORER.requests.stream()
                .filter(r -> r.getTransaction().getChannel().name().equals("CHANNEL_USSD"))
                .allMatch(r -> !r.getTransaction().hasDeviceToken()))
        .isTrue();
  }

  @Test
  @Tag("FR-01-02")
  void everySharedValidationVectorGetsItsStatusAndErrorsOverHttp() throws Exception {
    JsonNode root =
        JSON.readTree(
            Files.readString(
                Path.of("..", "..", "contracts", "validation", "request-validation-vectors.json")));
    for (JsonNode vector : root.get("schemas").get("TransactionIngestRequest")) {
      String payload;
      if (vector.has("raw_body")) {
        payload = vector.get("raw_body").asString();
      } else {
        ObjectNode body = (ObjectNode) vector.get("body").deepCopy();
        if (body.path("transaction_id")
            .asString("")
            .equals("3f8e0c5a-6b1d-4f5e-9a2b-7c4d1e8f0a11")) {
          body.put("transaction_id", UUID.randomUUID().toString());
        }
        if (body.path("transaction_timestamp").asString("").startsWith("2026-09-17T08:15:30")) {
          body.put("transaction_timestamp", Instant.now().minusSeconds(5).toString());
        }
        payload = body.toString();
      }
      HttpResponse<String> response = post("/api/v1/transactions/ingest", ApiHarness.KEY, payload);
      String label = vector.get("label").asString();
      assertThat(response.statusCode()).as(label).isEqualTo(vector.get("expected_status").asInt());
      if (response.statusCode() >= 400) {
        JsonNode problem = JSON.readTree(response.body());
        assertThat(response.headers().firstValue("Content-Type"))
            .get()
            .asString()
            .startsWith("application/problem+json");
        assertThat(problem.get("type").asString()).isEqualTo("urn:fraudshield:problem:validation");
        Set<String> expected = new HashSet<>();
        vector
            .get("expected_errors")
            .forEach(e -> expected.add(e.get("field").asString() + " " + e.get("code").asString()));
        Set<String> actual = new HashSet<>();
        problem
            .get("errors")
            .forEach(e -> actual.add(e.get("field").asString() + " " + e.get("code").asString()));
        assertThat(actual).as(label).isEqualTo(expected);
        assertThat(problem.get("correlation_id").asString()).isNotBlank();
      }
    }
  }

  @Test
  @Tag("FR-01-03")
  void oneHundredIdenticalSubmissionsAreScoredOnceAndAnsweredIdentically() throws Exception {
    UUID id = UUID.randomUUID();
    String payload = body(id, "MOBILE_MONEY").toString();
    ApiHarness.SCORER.delayMillis = 50;
    int before = ApiHarness.SCORER.calls.get();
    List<Callable<HttpResponse<String>>> submissions = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      submissions.add(() -> post("/api/v1/transactions/ingest", ApiHarness.KEY, payload));
    }
    List<HttpResponse<String>> responses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
      for (Future<HttpResponse<String>> future : pool.invokeAll(submissions)) {
        responses.add(future.get());
      }
    }
    assertThat(ApiHarness.SCORER.calls.get() - before).as("scored exactly once").isEqualTo(1);
    assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(200));
    assertThat(responses.stream().map(HttpResponse::body).distinct()).hasSize(1);
    assertThat(
            responses.stream()
                .filter(r -> r.headers().firstValue("Idempotent-Replayed").isPresent()))
        .hasSize(99);
    try (var redis = ApiHarness.REDIS.connect()) {
      long ttl = redis.sync().pttl("fs:{" + Fixtures.INSTITUTION + "}:idem:" + id);
      assertThat(Duration.ofMillis(ttl))
          .isBetween(Duration.ofHours(23).plusMinutes(59), Duration.ofHours(24));
    }
  }

  @Test
  @Tag("FR-01-03")
  void changedRequestsUnderTheSameIdAreAuditedConflicts() throws Exception {
    UUID id = UUID.randomUUID();
    ObjectNode original = body(id, "CARD");
    assertThat(
            post("/api/v1/transactions/ingest", ApiHarness.KEY, original.toString()).statusCode())
        .isEqualTo(200);
    final int calls = ApiHarness.SCORER.calls.get();
    original.put("amount", "150000");
    HttpResponse<String> conflict =
        post("/api/v1/transactions/ingest", ApiHarness.KEY, original.toString());
    assertThat(conflict.statusCode()).isEqualTo(409);
    assertThat(JSON.readTree(conflict.body()).get("type").asString())
        .isEqualTo("urn:fraudshield:problem:idempotency-conflict");
    assertThat(ApiHarness.SCORER.calls.get()).as("nothing scored").isEqualTo(calls);
    List<String> audits = new ArrayList<>();
    ApiHarness.KAFKA.readAll(
        "fs.audit.events",
        r -> {
          String value = new String(r.value(), StandardCharsets.UTF_8);
          if (value.contains("IDEMPOTENCY_CONFLICT") && value.contains(id.toString())) {
            audits.add(value);
          }
          return value;
        },
        10_000,
        Duration.ofSeconds(15));
    assertThat(audits).isNotEmpty();
  }

  @Test
  @Tag("FR-01-05")
  void keysAreRequiredScopedAndMeanNothingOnStaffEndpoints() throws Exception {
    String payload = body(UUID.randomUUID(), "CARD").toString();
    assertThat(post("/api/v1/transactions/ingest", null, payload).statusCode()).isEqualTo(401);
    assertThat(
            post("/api/v1/transactions/ingest", "fsk_test_zzzzzzzzzzzz_" + "x".repeat(43), payload)
                .statusCode())
        .isEqualTo(401);
    HttpResponse<String> forbidden =
        post("/api/v1/transactions/ingest", ApiHarness.READ_ONLY_KEY, payload);
    assertThat(forbidden.statusCode()).isEqualTo(403);
    assertThat(JSON.readTree(forbidden.body()).get("type").asString())
        .isEqualTo("urn:fraudshield:problem:forbidden");
    for (String staff : List.of("/api/v1/alerts", "/api/v1/admin/users")) {
      assertThat(get(staff, ApiHarness.KEY).statusCode() / 100).as(staff).isNotEqualTo(2);
    }
    // Rotation overlap: both of the institution's keys work at once.
    assertThat(
            post(
                    "/api/v1/transactions/ingest",
                    ApiHarness.ROTATED_KEY,
                    body(UUID.randomUUID(), "CARD").toString())
                .statusCode())
        .isEqualTo(200);
    HttpResponse<String> unsupported =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/transactions/ingest")))
                .header("X-API-Key", ApiHarness.KEY)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(unsupported.statusCode()).isEqualTo(415);
    assertThat(
            post(
                    "/api/v1/transactions/ingest",
                    ApiHarness.KEY,
                    "{\"x\":\"" + "a".repeat(20_000) + "\"}")
                .statusCode())
        .isEqualTo(413);
    // The same body with no Content-Length (chunked) was truncated and answered 400 malformed
    // (Principal Review finding 13).
    String oversized = "{\"x\":\"" + "a".repeat(20_000) + "\"}";
    HttpResponse<String> chunked =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url("/api/v1/transactions/ingest")))
                .header("X-API-Key", ApiHarness.KEY)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofInputStream(
                        () ->
                            new java.io.ByteArrayInputStream(
                                oversized.getBytes(StandardCharsets.UTF_8))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(chunked.statusCode()).isEqualTo(413);
  }

  @Test
  @Tag("FR-03-02")
  @Tag("D-14")
  @Tag("D-18")
  void holdsAreReleasedAtThirtySecondsAndDeliveredBySignedWebhook() throws Exception {
    ApiHarness.SCORER.score = r -> 0.7;
    UUID id = UUID.randomUUID();
    HttpResponse<String> held =
        post("/api/v1/transactions/ingest", ApiHarness.KEY, body(id, "MOBILE_MONEY").toString());
    JsonNode decision = JSON.readTree(held.body());
    assertThat(decision.get("decision").asString()).isEqualTo("HOLD");
    Instant deadline = Instant.parse(decision.get("review_deadline_at").asString());
    JsonNode first = JSON.readTree(get("/api/v1/decisions/" + id, ApiHarness.KEY).body());
    assertThat(first.get("decision").asString()).isEqualTo("HOLD");
    assertThat(first.get("final").asBoolean()).isFalse();
    Instant decidedAt = Instant.parse(first.get("decided_at").asString());
    assertThat(Duration.between(decidedAt, deadline)).isEqualTo(Duration.ofSeconds(30));

    await()
        .atMost(Duration.ofSeconds(40))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () ->
                JSON.readTree(get("/api/v1/decisions/" + id, ApiHarness.KEY).body())
                        .get("decision_sequence")
                        .asInt()
                    == 2);
    JsonNode released = JSON.readTree(get("/api/v1/decisions/" + id, ApiHarness.KEY).body());
    assertThat(released.get("decision").asString()).isEqualTo("TIMEOUT_RELEASE");
    assertThat(released.get("decided_by").asString()).isEqualTo("TIMEOUT_POLICY");
    Duration late =
        Duration.between(deadline, Instant.parse(released.get("decided_at").asString()));
    assertThat(late)
        .as("released at 30 s within ±500 ms")
        .isBetween(Duration.ofMillis(-500), Duration.ofMillis(500));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> ApiHarness.WEBHOOKS.stream().anyMatch(w -> w[0].contains(id.toString())));
    String[] webhook =
        ApiHarness.WEBHOOKS.stream()
            .filter(w -> w[0].contains(id.toString()))
            .findFirst()
            .orElseThrow();
    assertThat(JSON.readTree(webhook[0])).isEqualTo(released);
    assertThat(
            WebhookSignatures.verify(
                webhook[1],
                webhook[0].getBytes(StandardCharsets.UTF_8),
                List.of(ApiHarness.WEBHOOK_SECRET),
                Instant.now().getEpochSecond()))
        .isEqualTo(WebhookSignatures.Verdict.VALID);
    assertThat(get("/api/v1/decisions/" + UUID.randomUUID(), ApiHarness.KEY).statusCode())
        .isEqualTo(404);
  }

  @Test
  @Tag("FR-03-01")
  @Tag("FR-03-04")
  @Tag("FR-03-08")
  void highRiskTransactionsAreDeclinedBlockedAndTheCustomerIsTexted() throws Exception {
    ApiHarness.SCORER.score = r -> 0.9;
    UUID id = UUID.randomUUID();
    HttpResponse<String> response =
        post("/api/v1/transactions/ingest", ApiHarness.KEY, body(id, "MOBILE_MONEY").toString());
    JsonNode decision = JSON.readTree(response.body());
    assertThat(decision.get("decision").asString()).isEqualTo("DECLINE");
    assertThat(decision.get("risk_tier").asString()).isEqualTo("HIGH");
    assertThat(decision.get("reason_codes")).isNotEmpty();
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                one("SELECT count(*) FROM"
                        + " fraudshield.auto_block_events WHERE transaction_id = '"
                        + id
                        + "'")
                    .equals("1"));
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                one("SELECT count(*) FROM"
                        + " fraudshield.customer_notifications n"
                        + " JOIN fraudshield.auto_block_events b"
                        + " ON b.id = n.auto_block_event_id WHERE b.transaction_id = '"
                        + id
                        + "' AND n.event = 'SENT'")
                    .equals("1"));
    // FR-03-04 measures the customer SMS from the block, and both timestamps are the server's
    // own. Measuring from the client's clock made this assertion fail at host load ~20 while the
    // product met the requirement (Principal Review finding 14); it is now the database's answer.
    assertThat(
            Double.parseDouble(
                one(
                    "SELECT EXTRACT(EPOCH FROM (n.occurred_at - b.blocked_at))"
                        + " FROM fraudshield.customer_notifications n"
                        + " JOIN fraudshield.auto_block_events b ON b.id = n.auto_block_event_id"
                        + " WHERE b.transaction_id = '"
                        + id
                        + "' AND n.event = 'SENT'")))
        .as("SMS within 5 s of the block (FR-03-04)")
        .isLessThan(5.0);
    assertThat(ApiHarness.SMS.getLast()).contains("15000 RWF", "+250788100100");
  }

  @Test
  @Tag("FR-01-06")
  void thousandTransactionBatchesAreDecidedWithinThirtySeconds() throws Exception {
    List<ObjectNode> items = new ArrayList<>();
    for (int i = 0; i < 999; i++) {
      items.add(body(UUID.randomUUID(), "MOBILE_MONEY"));
    }
    ObjectNode invalid = body(UUID.randomUUID(), "CARD");
    invalid.put("amount", "0");
    items.add(invalid);
    ObjectNode batch = JSON.createObjectNode();
    batch.putArray("transactions").addAll(items);
    long start = System.nanoTime();
    HttpResponse<String> accepted =
        post("/api/v1/transactions/ingest/batch", ApiHarness.KEY, batch.toString());
    assertThat(accepted.statusCode()).isEqualTo(202);
    JsonNode job = JSON.readTree(accepted.body());
    assertThat(job.get("accepted").asInt()).isEqualTo(1000);
    String status = job.get("status_url").asString();
    assertThat(accepted.headers().firstValue("Location")).contains(status);
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(250))
        .until(
            () ->
                JSON.readTree(get(status, ApiHarness.KEY).body())
                    .get("state")
                    .asString()
                    .equals("COMPLETED"));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
    JsonNode page = JSON.readTree(get(status + "?limit=200", ApiHarness.KEY).body());
    assertThat(page.get("processed").asInt()).isEqualTo(1000);
    assertThat(page.get("failed").asInt()).isEqualTo(1);
    assertThat(page.get("results")).hasSize(200);
    assertThat(page.get("next_cursor").asString()).isEqualTo("200");
    JsonNode last = JSON.readTree(get(status + "?cursor=999&limit=1", ApiHarness.KEY).body());
    assertThat(last.get("results").get(0).get("outcome").asString()).isEqualTo("REJECTED");
    assertThat(
            last.get("results").get(0).get("problem").get("errors").get(0).get("code").asString())
        .isEqualTo("out_of_range");
    assertThat(page.get("results").get(0).get("decision").get("decision").asString())
        .isEqualTo("APPROVE");
    assertThat(
            post("/api/v1/transactions/ingest/batch", ApiHarness.KEY, "{\"transactions\":[]}")
                .statusCode())
        .isEqualTo(422);
    assertThat(get("/api/v1/jobs/" + UUID.randomUUID(), ApiHarness.KEY).statusCode())
        .isEqualTo(404);
    assertThat(get(status + "?cursor=not-a-number", ApiHarness.KEY).statusCode())
        .as("a cursor that is not a position is a validation error, never a 500")
        .isEqualTo(422);
  }

  /**
   * A job whose process died has no items anywhere and can only be failed; before the start-up
   * sweep it stayed RUNNING for ever (Principal Review finding 12).
   */
  @Test
  @Tag("FR-01-06")
  void unfinishedJobsAreFailedWhenAnInstanceStarts() throws Exception {
    UUID job = UUID.randomUUID();
    try (Connection c = ApiHarness.DB.superuser();
        Statement s = c.createStatement()) {
      s.execute(
          "INSERT INTO fraudshield.batch_jobs (id, institution_id, api_key_id, state, total)"
              + " SELECT '"
              + job
              + "', '"
              + Fixtures.INSTITUTION
              + "', k.id, 'RUNNING', 10 FROM fraudshield.api_keys k WHERE k.institution_id = '"
              + Fixtures.INSTITUTION
              + "' LIMIT 1");
    }
    assertThat(batches.failUnfinishedJobs()).isPositive();
    JsonNode status = JSON.readTree(get("/api/v1/jobs/" + job, ApiHarness.KEY).body());
    assertThat(status.get("state").asString()).isEqualTo("FAILED");
  }

  @Test
  void thePublicHealthSaysOnlyUpOrDown() throws Exception {
    HttpResponse<String> health = get("/api/v1/health", null);
    assertThat(health.statusCode()).isEqualTo(200);
    assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
  }
}
