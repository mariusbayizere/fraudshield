package io.github.mariusbayizere.fraudshield.notify.webhook;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** D-14 and E.1: signed, ordered, retried and dead-lettered webhook delivery. */
@Tag("requires-docker")
@Tag("D-14")
@Tag("FR-03-02")
class WebhookDeliveryTest {

  /** The published test secret, read from the shared vectors (allowlisted there only). */
  private static final String SECRET = vectorSecret();

  private static String vectorSecret() {
    try {
      return new ObjectMapper()
          .readTree(
              java.nio.file.Files.readString(
                  java.nio.file.Path.of(
                      "..", "..", "contracts", "webhooks", "signature-test-vectors.json")))
          .get("vectors")
          .get(0)
          .get("secrets")
          .get(0)
          .asString();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  /** What the receiver saw. */
  private record Received(String signature, byte[] body) {}

  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(200);
  private final MutableClock clock = new MutableClock(NOW);
  private HttpServer receiver;
  private TestDatabase db;
  private WebhookDispatcher dispatcher;

  @BeforeEach
  void start() throws Exception {
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.createContext(
        "/hooks",
        exchange -> {
          received.add(
              new Received(
                  exchange.getRequestHeaders().getFirst("X-FraudShield-Signature"),
                  exchange.getRequestBody().readAllBytes()));
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    receiver.start();
    db = TestDatabase.create();
    db.institution(INSTITUTION);
    URI url = URI.create("http://127.0.0.1:" + receiver.getAddress().getPort() + "/hooks");
    dispatcher =
        new WebhookDispatcher(
            db.dataSource("fs_app"),
            institution ->
                institution.equals(INSTITUTION)
                    ? Optional.of(new WebhookEndpoints.Endpoint(url, List.of(SECRET)))
                    : Optional.empty(),
            new HttpWebhookTransport(u -> "127.0.0.1".equals(u.getHost())),
            new RetryPolicy(RandomGenerator.of("L64X128MixRandom")),
            clock);
  }

  @AfterEach
  void stop() {
    receiver.stop(0);
  }

  private static byte[] body(DecisionState state) {
    return new ObjectMapper().writeValueAsBytes(FactCodec.finalDecision(state));
  }

  private static DecisionState hold(UUID tx) {
    return DecisionState.initial(
        INSTITUTION, tx, Decision.HOLD, NOW, List.of(), NOW.plusSeconds(30));
  }

  private String state(UUID eventId) throws Exception {
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery(
                "SELECT state FROM fraudshield.webhook_deliveries"
                    + " WHERE event_id = '"
                    + eventId
                    + "'")) {
      return r.next() ? r.getString(1) : null;
    }
  }

  @Test
  void sequenceOneIsNeverSentAndLaterStatesArriveSignedAndExact() throws Exception {
    UUID tx = UUID.randomUUID();
    DecisionState hold = hold(tx);
    assertThat(dispatcher.enqueue(INSTITUTION, body(hold))).isFalse();
    DecisionState released =
        hold.next(
            DecisionValue.TIMEOUT_RELEASE,
            DecidedBy.TIMEOUT_POLICY,
            NOW.plusSeconds(30),
            List.of("REVIEW_TIMEOUT"));
    assertThat(dispatcher.enqueue(INSTITUTION, body(released))).isTrue();
    assertThat(dispatcher.enqueue(INSTITUTION, body(released))).as("idempotent").isFalse();
    assertThat(dispatcher.deliverDue(10)).isEqualTo(1);
    assertThat(received)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.body()).isEqualTo(body(released));
              assertThat(
                      WebhookSignatures.verify(
                          r.signature(), r.body(), List.of(SECRET), NOW.getEpochSecond()))
                  .isEqualTo(WebhookSignatures.Verdict.VALID);
            });
    assertThat(state(released.eventId())).isEqualTo("DELIVERED");
    assertThat(dispatcher.deliverDue(10)).isZero();
    assertThat(dispatcher.delivered()).isEqualTo(1);
  }

  @Test
  void failuresAreRetriedWithBackoffAndEachAttemptIsSignedAtItsOwnTime() throws Exception {
    DecisionState declined =
        hold(UUID.randomUUID()).next(DecisionValue.DECLINE, DecidedBy.ANALYST, NOW, List.of());
    dispatcher.enqueue(INSTITUTION, body(declined));
    status.set(503);
    assertThat(dispatcher.deliverDue(10)).isEqualTo(1);
    assertThat(state(declined.eventId())).isEqualTo("PENDING");
    assertThat(dispatcher.deliverDue(10))
        .as("not due yet, or due at once by jitter")
        .isBetween(0, 1);
    status.set(204);
    clock.advance(RetryPolicy.CAP);
    assertThat(dispatcher.deliverDue(10)).isEqualTo(1);
    assertThat(state(declined.eventId())).isEqualTo("DELIVERED");
    String first = received.getFirst().signature();
    String last = received.getLast().signature();
    assertThat(last.substring(0, last.indexOf(',')))
        .isNotEqualTo(first.substring(0, first.indexOf(',')));
    assertThat(received.getLast().body()).isEqualTo(received.getFirst().body());
  }

  @Test
  void newerStatesSupersedePendingOnesAndOlderStatesAreNeverSent() throws Exception {
    UUID tx = UUID.randomUUID();
    DecisionState declined =
        hold(tx).next(DecisionValue.DECLINE, DecidedBy.ANALYST, NOW, List.of());
    DecisionState approved =
        declined.next(
            DecisionValue.APPROVE, DecidedBy.CUSTOMER_VERIFICATION, NOW.plusSeconds(60), List.of());
    final DecisionState overridden =
        approved.next(
            DecisionValue.DECLINE, DecidedBy.SENIOR_OVERRIDE, NOW.plusSeconds(120), List.of());
    status.set(500);
    dispatcher.enqueue(INSTITUTION, body(declined));
    dispatcher.deliverDue(10);
    dispatcher.enqueue(INSTITUTION, body(overridden));
    assertThat(state(declined.eventId())).isEqualTo("SUPERSEDED");
    assertThat(dispatcher.enqueue(INSTITUTION, body(approved))).as("older than queued").isFalse();
    assertThat(state(approved.eventId())).isEqualTo("SUPERSEDED");
    status.set(200);
    received.clear();
    assertThat(dispatcher.deliverDue(10)).isEqualTo(1);
    assertThat(received)
        .singleElement()
        .satisfies(
            r ->
                assertThat(new ObjectMapper().readTree(r.body()).get("decision_sequence").asInt())
                    .isEqualTo(4));
  }

  @Test
  void deliveriesAreDeadLetteredAfterTwentyFourHoursOrWithoutAnEndpoint() throws Exception {
    DecisionState declined =
        hold(UUID.randomUUID()).next(DecisionValue.DECLINE, DecidedBy.ANALYST, NOW, List.of());
    status.set(500);
    dispatcher.enqueue(INSTITUTION, body(declined));
    dispatcher.deliverDue(10);
    for (int hour = 0; hour < 25; hour++) {
      clock.advance(Duration.ofHours(1));
      dispatcher.deliverDue(10);
    }
    assertThat(state(declined.eventId())).isEqualTo("DEAD_LETTERED");
    assertThat(dispatcher.deadLettered()).isEqualTo(1);

    UUID other = UUID.randomUUID();
    try (Connection c = db.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.institutions (id, code, name, country)"
              + " VALUES (?, 'no-hooks', 'No webhooks bank', 'RW')",
          other);
    }
    DecisionState elsewhere =
        DecisionState.initial(
                other, UUID.randomUUID(), Decision.HOLD, NOW, List.of(), NOW.plusSeconds(30))
            .next(DecisionValue.TIMEOUT_RELEASE, DecidedBy.TIMEOUT_POLICY, NOW, List.of());
    dispatcher.enqueue(other, body(elsewhere));
    dispatcher.deliverDue(10);
    try (Connection c = db.superuser();
        Statement s = c.createStatement();
        ResultSet r =
            s.executeQuery(
                "SELECT state, last_error FROM fraudshield.webhook_deliveries"
                    + " WHERE institution_id = '"
                    + other
                    + "'")) {
      r.next();
      assertThat(r.getString(1)).isEqualTo("DEAD_LETTERED");
      assertThat(r.getString(2)).contains("no webhook endpoint");
    }
  }

  @Test
  @Tag("NFR-SEC-03")
  void onlyPublicHttpsDestinationsAreCalled() throws Exception {
    HttpWebhookTransport.Destinations policy = HttpWebhookTransport.Destinations.PUBLIC_HTTPS;
    assertThat(policy.allowed(URI.create("http://8.8.8.8/hook"))).isFalse();
    assertThat(policy.allowed(URI.create("https://127.0.0.1/hook"))).isFalse();
    assertThat(policy.allowed(URI.create("https://10.1.2.3/hook"))).isFalse();
    assertThat(policy.allowed(URI.create("https://192.168.0.10/hook"))).isFalse();
    assertThat(policy.allowed(URI.create("https://169.254.169.254/latest"))).isFalse();
    assertThat(policy.allowed(URI.create("https://[fd00::1]/hook"))).isFalse();
    assertThat(policy.allowed(URI.create("https://user@8.8.8.8/hook"))).isFalse();
    assertThat(policy.allowed(URI.create("https://8.8.8.8/hook"))).isTrue();
    HttpWebhookTransport strict = new HttpWebhookTransport(policy);
    assertThatThrownBy(
            () -> strict.post(URI.create("http://127.0.0.1:1/x"), java.util.Map.of(), new byte[0]))
        .isInstanceOf(java.io.IOException.class);
    assertThat(
            new WebhookEndpoints.Endpoint(URI.create("https://bank.example/hook"), List.of(SECRET))
                .toString())
        .doesNotContain(SECRET);
  }

  @Test
  void retryWaitsGrowWithFullJitterUnderTheCap() {
    RetryPolicy policy = new RetryPolicy(RandomGenerator.of("L64X128MixRandom"));
    for (int attempt = 1; attempt < 30; attempt++) {
      Duration ceiling = RetryPolicy.BASE.multipliedBy(1L << Math.min(attempt - 1, 20));
      assertThat(policy.delay(attempt))
          .isBetween(
              Duration.ZERO, ceiling.compareTo(RetryPolicy.CAP) < 0 ? ceiling : RetryPolicy.CAP);
    }
    assertThat(policy.exhausted(NOW, NOW.plus(Duration.ofHours(24)))).isTrue();
    assertThat(policy.exhausted(NOW, NOW.plus(Duration.ofHours(23)))).isFalse();
  }
}
