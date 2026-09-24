package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.ingest.web.RateLimitFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/**
 * E.1, ADR 0058 (adopting ADR 0100): every API key has a budget counted in transactions, and going
 * over it is 429 with {@code Retry-After}. A single submission costs one unit and a batch its item
 * count, charged once and all or nothing, so batching buys no extra throughput.
 *
 * <p>The budget here is small (5 transactions per second) with the smallest burst the application
 * accepts, one full batch (1,000). One batch of 1,000 therefore spends a key's whole burst, which
 * is itself the proof that a batch is charged by its item count: charged as one request it would
 * leave 999 units. The batches here hold empty objects, which every item's validation refuses, so
 * they cost no scoring. It also checks the control that matters most in an outage: with Redis
 * paused the API still limits, from this instance's own bucket, and says so in the response.
 */
@Tag("requires-docker")
@Tag("FR-01-02")
@Tag("FR-01-06")
@Tag("NFR-SEC-03")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(ApiHarness.Collaborators.class)
@org.springframework.test.context.ActiveProfiles(ApiHarness.PROFILE)
class RateLimitApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final int LIMIT = 5;
  private static final int BURST = 1_000;

  @Autowired Environment environment;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
    registry.add("fraudshield.rate-limit.transactions-per-second", () -> Integer.toString(LIMIT));
    registry.add("fraudshield.rate-limit.burst", () -> Integer.toString(BURST));
  }

  private String url(String path) {
    return "http://127.0.0.1:" + environment.getProperty("local.server.port") + path;
  }

  private HttpResponse<String> get(String path, String key) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path))).GET();
    if (key != null) {
      request.header("X-API-Key", key);
    }
    return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** A batch of {@code items} empty objects: a valid envelope whose items all fail validation. */
  private HttpResponse<String> batch(String key, int items) throws Exception {
    StringBuilder body = new StringBuilder("{\"transactions\":[");
    for (int i = 0; i < items; i++) {
      body.append(i == 0 ? "{}" : ",{}");
    }
    body.append("]}");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url("/api/v1/transactions/ingest/batch")))
            .header("X-API-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /**
   * Sends single requests for one key all at once and returns every response. Concurrent, so they
   * arrive faster than the bucket refills however slow the host is; sent one by one on a loaded
   * machine, each took longer than a refill and the budget never bit.
   */
  private List<HttpResponse<String>> spend(String key, int requests) throws Exception {
    List<java.util.concurrent.CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
    for (int i = 0; i < requests; i++) {
      sent.add(
          HTTP.sendAsync(
              HttpRequest.newBuilder(URI.create(url("/api/v1/decisions/" + UUID.randomUUID())))
                  .header("X-API-Key", key)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString()));
    }
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (java.util.concurrent.CompletableFuture<HttpResponse<String>> response : sent) {
      responses.add(response.get(30, java.util.concurrent.TimeUnit.SECONDS));
    }
    return responses;
  }

  private static long jobsOf(String key) throws Exception {
    try (Connection c = ApiHarness.DB.superuser();
        Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery(
                "SELECT count(*) FROM fraudshield.batch_jobs WHERE api_key_id = '"
                    + ApiHarness.BUDGET_KEY_IDS.get(key)
                    + "'")) {
      r.next();
      return r.getLong(1);
    }
  }

  @Test
  void keysOverTheirBudgetAreRefusedWithRetryAfterAndRecover() throws Exception {
    // The server charges the batch before it answers, so the refill is counted from the send.
    final long sent = System.nanoTime();
    HttpResponse<String> full = batch(ApiHarness.BUDGET_KEY_ONE, BURST);
    assertThat(full.statusCode()).as("one full batch fits the burst").isEqualTo(202);
    assertThat(full.headers().firstValue("RateLimit-Limit")).contains(Integer.toString(LIMIT));
    int left = Integer.parseInt(full.headers().firstValue("RateLimit-Remaining").orElseThrow());
    assertThat(left).as("a batch of 1,000 spent 1,000 units, not one").isLessThanOrEqualTo(LIMIT);
    assertThat(full.headers().firstValue(RateLimitFilter.DEGRADED_HEADER))
        .as("Redis is up, so the shared limiter answered")
        .isEmpty();

    // Three seconds' worth of refill, all at once: the budget must bite.
    List<HttpResponse<String>> responses = spend(ApiHarness.BUDGET_KEY_ONE, LIMIT * 3);
    double seconds = (System.nanoTime() - sent) / 1e9;
    long refused = responses.stream().filter(r -> r.statusCode() == 429).count();
    assertThat(responses.size() - refused)
        .as(
            "no more than what the batch left plus what refilled since it was sent (%.2f s)",
            seconds)
        .isLessThanOrEqualTo(left + (long) Math.ceil(LIMIT * seconds) + 1);
    assertThat(refused).as("the requests past the budget").isPositive();

    HttpResponse<String> overBudget =
        responses.stream().filter(r -> r.statusCode() == 429).findFirst().orElseThrow();
    int retryAfter = Integer.parseInt(overBudget.headers().firstValue("Retry-After").orElseThrow());
    assertThat(retryAfter).as("whole seconds, at least one").isGreaterThanOrEqualTo(1);
    assertThat(JSON.readTree(overBudget.body()).get("type").asString())
        .isEqualTo("urn:fraudshield:problem:rate-limited");
    assertThat(JSON.readTree(overBudget.body()).get("status").asInt()).isEqualTo(429);
    assertThat(overBudget.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/problem+json");

    // The bucket refills: within a few seconds of waiting the key is served again.
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                get("/api/v1/decisions/" + UUID.randomUUID(), ApiHarness.BUDGET_KEY_ONE)
                        .statusCode()
                    != 429);
  }

  @Test
  @Tag("FR-01-06")
  void batchCallersCannotExceedTheTransactionBudget() throws Exception {
    // M10's finding (ADR 0100): charged per request, a caller using batches got up to 1,000 times
    // the throughput of one using single submissions. Batches of 300 as fast as the client can
    // send: whatever the timing, the transactions accepted never exceed the burst plus what
    // refilled.
    String key = ApiHarness.BUDGET_KEY_FOUR;
    int accepted = 0;
    int acceptedBatches = 0;
    HttpResponse<String> lastRefusal = null;
    long start = System.nanoTime();
    // Until the budget has bitten and for at least three seconds, whatever the host's speed (on a
    // loaded host three seconds of 300-item batches did not reach the burst); at most thirty.
    while ((lastRefusal == null || System.nanoTime() - start < Duration.ofSeconds(3).toNanos())
        && System.nanoTime() - start < Duration.ofSeconds(30).toNanos()) {
      HttpResponse<String> response = batch(key, 300);
      assertThat(response.headers().firstValue(RateLimitFilter.DEGRADED_HEADER))
          .as("the bound holds for one limiter; degraded means a test left Redis down")
          .isEmpty();
      if (response.statusCode() == 202) {
        accepted += JSON.readTree(response.body()).get("accepted").asInt();
        acceptedBatches++;
      } else {
        assertThat(response.statusCode()).isEqualTo(429);
        lastRefusal = response;
      }
    }
    double seconds = (System.nanoTime() - start) / 1e9;
    assertThat(accepted)
        .as(
            "transactions accepted in %.1f s, against a budget of %d plus %d per second",
            seconds, BURST, LIMIT)
        .isLessThanOrEqualTo((int) Math.ceil(BURST + LIMIT * (seconds + 1)))
        .isGreaterThanOrEqualTo(900);
    assertThat(lastRefusal).as("the budget bit").isNotNull();
    assertThat(Integer.parseInt(lastRefusal.headers().firstValue("Retry-After").orElseThrow()))
        .isGreaterThanOrEqualTo(1);
    assertThat(JSON.readTree(lastRefusal.body()).get("type").asString())
        .isEqualTo("urn:fraudshield:problem:rate-limited");
    assertThat(jobsOf(key))
        .as("a refused batch is refused whole: no job was recorded for it")
        .isEqualTo(acceptedBatches);

    // All or nothing: a refused 300 took nothing, so what remains still buys a smaller batch.
    int remaining =
        Integer.parseInt(lastRefusal.headers().firstValue("RateLimit-Remaining").orElseThrow());
    assertThat(remaining).as("the refusal took nothing from what was left").isLessThan(300);
    if (remaining >= 1) {
      HttpResponse<String> fits = batch(key, remaining);
      assertThat(fits.statusCode()).as("%d units remained", remaining).isEqualTo(202);
      assertThat(jobsOf(key)).isEqualTo(acceptedBatches + 1L);
    }
  }

  @Test
  void exhaustedKeysAreRefusedBeforeTheirBatchBodyIsRead() throws Exception {
    // Review 8 (2026-09-24): with the batch exempt from the filter, an exhausted key made the
    // server
    // read and parse up to 4 MiB per request without limit. RateLimitFilterTest proves the order
    // deterministically; this checks it end to end. The bucket refills (LIMIT per second) while
    // the stalled requests are opened, so up to that many may be admitted and wait for their body:
    // the bound allows for it, and the rest must be refused while their bodies are still arriving.
    final int stalled = 10;
    final long sent = System.nanoTime();
    assertThat(batch(ApiHarness.BUDGET_KEY_SIX, BURST).statusCode()).isEqualTo(202);
    int port = Integer.parseInt(environment.getProperty("local.server.port"));
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(stalled);
    try {
      List<java.util.concurrent.Future<String>> answers = new ArrayList<>();
      for (int i = 0; i < stalled; i++) {
        answers.add(pool.submit(() -> stalledBatch(port)));
      }
      int refused = 0;
      for (java.util.concurrent.Future<String> answer : answers) {
        if ("HTTP/1.1 429 ".equals(answer.get(10, java.util.concurrent.TimeUnit.SECONDS))) {
          refused++;
        }
      }
      double seconds = (System.nanoTime() - sent) / 1e9;
      assertThat(refused)
          .as("refused while their bodies were still arriving (%.2f s of refill)", seconds)
          .isGreaterThanOrEqualTo(stalled - (int) Math.ceil(LIMIT * seconds) - 1)
          .isPositive();
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Sends a batch request's headers and part of its body, then stalls: the status line, or null.
   */
  private static String stalledBatch(int port) throws java.io.IOException {
    try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
      socket.setSoTimeout(3_000);
      var out = socket.getOutputStream();
      out.write(
          ("POST /api/v1/transactions/ingest/batch HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                  + "X-API-Key: "
                  + ApiHarness.BUDGET_KEY_SIX
                  + "\r\nContent-Type: application/json\r\nContent-Length: 1000000\r\n\r\n"
                  + "{\"transactions\":[{},{},{}")
              .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      out.flush();
      return new java.io.BufferedReader(
              new java.io.InputStreamReader(
                  socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII))
          .readLine();
    } catch (java.net.SocketTimeoutException admittedAndWaitingForItsBody) {
      return null;
    }
  }

  @Test
  void batchesRefusedAtTheirSecondChargeAreToldWhenTheWholeBatchWillFit() throws Exception {
    // Review 9 (2026-09-24): the admission unit is kept on a refusal, so Retry-After must cover the
    // whole batch; a client that waits exactly that long is then accepted.
    String key = ApiHarness.BUDGET_KEY_EIGHT;
    assertThat(batch(key, BURST - 5).statusCode()).isEqualTo(202);
    HttpResponse<String> refused = batch(key, 20);
    assertThat(refused.statusCode()).isEqualTo(429);
    int remaining = remaining(refused);
    int retryAfter = Integer.parseInt(refused.headers().firstValue("Retry-After").orElseThrow());
    assertThat(retryAfter)
        .as("the wait for all 20 units from %d, at %d per second", remaining, LIMIT)
        .isGreaterThanOrEqualTo((int) Math.ceil((20.0 - remaining) / LIMIT));
    assertThat(JSON.readTree(refused.body()).get("detail").asString())
        .contains("costs 20 transactions");
    Thread.sleep(Duration.ofSeconds(retryAfter));
    assertThat(batch(key, 20).statusCode())
        .as("a client that honours Retry-After is accepted")
        .isEqualTo(202);
  }

  @Test
  void refusedOrInvalidBatchesCostOneUnitAndValidOnesTheirItemCount() throws Exception {
    String key = ApiHarness.BUDGET_KEY_SEVEN;
    final long start = System.nanoTime();
    HttpRequest malformed =
        HttpRequest.newBuilder(URI.create(url("/api/v1/transactions/ingest/batch")))
            .header("X-API-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"transactions\": ["))
            .build();
    HttpResponse<String> first = HTTP.send(malformed, HttpResponse.BodyHandlers.ofString());
    assertThat(first.statusCode()).isEqualTo(400);
    HttpRequest empty =
        HttpRequest.newBuilder(URI.create(url("/api/v1/transactions/ingest/batch")))
            .header("X-API-Key", key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"transactions\": []}"))
            .build();
    HttpResponse<String> second = HTTP.send(empty, HttpResponse.BodyHandlers.ofString());
    assertThat(second.statusCode())
        .as("a valid JSON body that is not a valid batch")
        .isEqualTo(422);
    HttpResponse<String> valid = batch(key, 10);
    assertThat(valid.statusCode()).isEqualTo(202);
    HttpResponse<String> two = batch(key, 2);
    assertThat(two.statusCode()).isEqualTo(202);
    double seconds = (System.nanoTime() - start) / 1e9;
    int refill = (int) Math.ceil(LIMIT * seconds);
    assertThat(remaining(first))
        .as("a malformed batch costs one unit")
        .isBetween(BURST - 1, BURST - 1 + refill);
    assertThat(remaining(second))
        .as("an invalid envelope costs one unit")
        .isBetween(BURST - 2, BURST - 2 + refill);
    assertThat(remaining(valid))
        .as("a valid batch of ten costs ten units, one on admission and nine more")
        .isBetween(BURST - 12, BURST - 12 + refill);
    assertThat(remaining(two))
        .as("a batch of two costs two units")
        .isBetween(BURST - 14, BURST - 14 + refill);
  }

  private static int remaining(HttpResponse<String> response) {
    return Integer.parseInt(response.headers().firstValue("RateLimit-Remaining").orElseThrow());
  }

  @Test
  void singleSubmissionsAndBatchesDrawOnOneBudget() throws Exception {
    // A key that spent its budget on a batch is over budget for single requests too.
    assertThat(batch(ApiHarness.BUDGET_KEY_TWO, BURST).statusCode()).isEqualTo(202);
    assertThat(spend(ApiHarness.BUDGET_KEY_TWO, LIMIT * 3))
        .as("single requests after the batch spent the budget")
        .anyMatch(r -> r.statusCode() == 429);
    assertThat(
            get("/api/v1/decisions/" + UUID.randomUUID(), ApiHarness.BUDGET_KEY_THREE).statusCode())
        .as("a different key, a different budget")
        .isNotEqualTo(429);
  }

  @Test
  @Tag("NFR-REL-01")
  void withRedisDownTheBudgetIsStillHeldAndTheAnswerSaysSo() throws Exception {
    ApiHarness.REDIS.pause();
    try {
      List<HttpResponse<String>> responses = new ArrayList<>();
      responses.add(batch(ApiHarness.BUDGET_KEY_FIVE, BURST));
      responses.addAll(spend(ApiHarness.BUDGET_KEY_FIVE, LIMIT + 3));
      assertThat(responses.getFirst().statusCode())
          .as("the batch is charged by this instance's own bucket")
          .isEqualTo(202);
      assertThat(responses.stream().filter(r -> r.statusCode() == 429).count())
          .as("an outage of the shared limiter must not remove the limit")
          .isPositive();
      assertThat(
              responses.stream()
                  .allMatch(
                      r -> r.headers().firstValue(RateLimitFilter.DEGRADED_HEADER).isPresent()))
          .as("every degraded answer says it is degraded")
          .isTrue();
    } finally {
      ApiHarness.REDIS.unpause();
      // Leave the limiter on Redis for the next test: for a second after a failure the fallback
      // answers, with a fresh local bucket, and a test that started inside that window would see
      // two bursts (it did, on CI, 2026-09-24).
      await()
          .atMost(Duration.ofSeconds(15))
          .until(
              () ->
                  get("/api/v1/decisions/" + UUID.randomUUID(), ApiHarness.KEY)
                      .headers()
                      .firstValue(RateLimitFilter.DEGRADED_HEADER)
                      .isEmpty());
    }
  }

  @Test
  void keysInsideTheirBudgetAreNeverRefusedOrDelayed() throws Exception {
    // One request per bucket refill, well inside the budget: the limiter must be invisible.
    for (int i = 0; i < 3; i++) {
      long start = System.nanoTime();
      HttpResponse<String> response = get("/api/v1/decisions/" + UUID.randomUUID(), ApiHarness.KEY);
      assertThat(response.statusCode()).isNotEqualTo(429);
      assertThat(Duration.ofNanos(System.nanoTime() - start))
          .as("the limiter adds one Redis round trip, not a wait")
          .isLessThan(Duration.ofSeconds(2));
      Thread.sleep(Duration.ofMillis(1000L / LIMIT + 50));
    }
  }
}
