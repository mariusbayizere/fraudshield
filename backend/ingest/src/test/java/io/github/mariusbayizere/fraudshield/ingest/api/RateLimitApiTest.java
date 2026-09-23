package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * E.1: every API key has a request budget, and going over it is 429 with {@code Retry-After}.
 *
 * <p>The budget here is small (5 per second, burst 5) so the test can reach it in a handful of
 * requests. It also checks the control that matters most in an outage: with Redis paused the API
 * still limits, from this instance's own bucket, and says so in the response.
 */
@Tag("requires-docker")
@Tag("FR-01-02")
@Tag("NFR-SEC-03")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(ApiHarness.Collaborators.class)
class RateLimitApiTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final int LIMIT = 5;

  @Autowired Environment environment;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
    registry.add("fraudshield.rate-limit.requests-per-second", () -> Integer.toString(LIMIT));
    registry.add("fraudshield.rate-limit.burst", () -> Integer.toString(LIMIT));
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

  /** Spends the budget of one key and returns every response. */
  private List<HttpResponse<String>> spend(String key, int requests) throws Exception {
    List<HttpResponse<String>> responses = new ArrayList<>();
    for (int i = 0; i < requests; i++) {
      responses.add(get("/api/v1/decisions/" + UUID.randomUUID(), key));
    }
    return responses;
  }

  @Test
  void keysOverTheirBudgetAreRefusedWithRetryAfterAndRecover() throws Exception {
    // Three times the burst, as fast as the client can send: the bucket refills at LIMIT per
    // second while this runs, so the exact count depends on the host, but the budget must bite.
    List<HttpResponse<String>> responses = spend(ApiHarness.BUDGET_KEY_ONE, LIMIT * 3);
    long refused = responses.stream().filter(r -> r.statusCode() == 429).count();
    assertThat(refused).as("the requests past the budget").isGreaterThanOrEqualTo(LIMIT);
    assertThat(responses.size() - refused)
        .as("no more than the burst, plus what refilled while the test ran")
        .isLessThanOrEqualTo(LIMIT + 2L);
    assertThat(responses.getFirst().statusCode()).as("inside the budget").isEqualTo(404);
    assertThat(responses.getFirst().headers().firstValue("RateLimit-Limit"))
        .contains(Integer.toString(LIMIT));
    assertThat(
            responses
                .getFirst()
                .headers()
                .firstValue(
                    io.github.mariusbayizere.fraudshield.ingest.web.RateLimitFilter
                        .DEGRADED_HEADER))
        .as("Redis is up, so the shared limiter answered")
        .isEmpty();

    HttpResponse<String> overBudget = responses.getLast();
    assertThat(overBudget.headers().firstValue("Retry-After")).isPresent();
    int retryAfter = Integer.parseInt(overBudget.headers().firstValue("Retry-After").orElseThrow());
    assertThat(retryAfter).as("whole seconds, at least one").isGreaterThanOrEqualTo(1);
    assertThat(JSON.readTree(overBudget.body()).get("type").asString())
        .isEqualTo("urn:fraudshield:problem:rate-limited");
    assertThat(JSON.readTree(overBudget.body()).get("status").asInt()).isEqualTo(429);
    assertThat(overBudget.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/problem+json");

    // The bucket refills: within a second of waiting the key is served again.
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                get("/api/v1/decisions/" + UUID.randomUUID(), ApiHarness.BUDGET_KEY_ONE)
                        .statusCode()
                    != 429);
  }

  @Test
  void oneKeyOverItsBudgetDoesNotSpendAnother() throws Exception {
    spend(ApiHarness.BUDGET_KEY_TWO, LIMIT + 2);
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
      List<HttpResponse<String>> responses = spend(ApiHarness.READ_ONLY_KEY, LIMIT + 3);
      assertThat(responses.stream().filter(r -> r.statusCode() == 429).count())
          .as("an outage of the shared limiter must not remove the limit")
          .isPositive();
      assertThat(
              responses.stream()
                  .allMatch(
                      r ->
                          r.headers()
                              .firstValue(
                                  io.github.mariusbayizere.fraudshield.ingest.web.RateLimitFilter
                                      .DEGRADED_HEADER)
                              .isPresent()))
          .as("every degraded answer says it is degraded")
          .isTrue();
    } finally {
      ApiHarness.REDIS.unpause();
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
