package io.github.mariusbayizere.fraudshield.analyst;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.analyst.realtime.ChangePublisher;
import io.github.mariusbayizere.fraudshield.analyst.realtime.RealtimeConfiguration;
import io.github.mariusbayizere.fraudshield.analyst.realtime.RealtimeProbe;
import io.github.mariusbayizere.fraudshield.analyst.realtime.RealtimeTestAutoConfiguration;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.testing.Http;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import io.github.mariusbayizere.fraudshield.decision.application.port.AlertChangePublisher;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;

/**
 * The real-time channel end to end in the application (ADR 0115 points 2 to 4, 9 and 11): the
 * handshake through the security chain, STOMP over a real socket, routing by institution,
 * re-authentication without loss, revocation by push, and point 4's proof that what is pushed
 * equals what {@code listAlerts} shows. Kafka is {@code MockConsumer} ({@link
 * RealtimeTestAutoConfiguration}), so the broker itself is not exercised here.
 */
class AnalystRealtimeTest extends AnalystIntegrationTest {

  private static final UUID BANK = DB.createInstitution("analyst-realtime");
  private static final UUID OTHER = DB.createInstitution("analyst-realtime-other");
  private static final List<String> STATUSES =
      List.of(
          "PENDING",
          "IN_REVIEW",
          "CONFIRMED_FRAUD",
          "MARKED_LEGITIMATE",
          "AUTO_RELEASED",
          "AUTO_DECLINED",
          "ESCALATED",
          "OVERRIDDEN");

  @LocalServerPort private int port;
  @Autowired private AccessTokens tokens;
  @Autowired private JwtDecoder decoder;
  @Autowired private TenantTransactions tenants;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private AlertQueries alerts;

  /** A STOMP client socket that keeps every frame it receives. */
  private final class Client extends TextWebSocketHandler {
    final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    WebSocketSession socket;
    volatile boolean closed;

    Client open() throws Exception {
      WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
      headers.setSecWebSocketProtocol(List.of("v12.stomp"));
      headers.setOrigin("http://localhost:" + port);
      socket =
          new StandardWebSocketClient()
              .execute(
                  this, headers, URI.create("ws://localhost:" + port + RealtimeConfiguration.PATH))
              .get(10, TimeUnit.SECONDS);
      return this;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
      if (!"\n".equals(message.getPayload())) {
        frames.add(message.getPayload());
      }
    }

    @Override
    public void afterConnectionClosed(
        WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
      closed = true;
    }

    void send(String frame) throws Exception {
      socket.sendMessage(new TextMessage(frame));
    }

    String next(long seconds) throws InterruptedException {
      return frames.poll(seconds, TimeUnit.SECONDS);
    }

    String connect(String accessToken) throws Exception {
      send(
          "CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer "
              + accessToken
              + "\n\n\0");
      return next(10);
    }

    String subscribe() throws Exception {
      send("SUBSCRIBE\ndestination:/user/queue/alerts\nid:alerts\nreceipt:r-alerts\n\n\0");
      return next(10);
    }
  }

  /** Waits until this instance accepts upgrades: its consumer positioned and Redis up (point 5). */
  private Client connected(Session session) throws Exception {
    long until = System.nanoTime() + 30_000_000_000L;
    while (true) {
      try {
        Client client = new Client().open();
        assertThat(client.connect(session.accessToken())).startsWith("CONNECTED");
        return client;
      } catch (java.util.concurrent.ExecutionException notReady) {
        if (System.nanoTime() > until) {
          throw notReady;
        }
        Thread.sleep(250);
      }
    }
  }

  @Test
  @Tag("FR-04-02")
  void handshakeAnswersEveryCallerAlikeThroughTheSecurityChain() {
    Session analyst = issueSession(createAccount(BANK, "ANALYST", "ACTIVE"));
    Session admin = issueSession(createAccount(BANK, "ADMIN", "ACTIVE"));
    Set<Integer> answers = new HashSet<>();
    for (Http.Response response :
        List.of(
            http.get(RealtimeConfiguration.PATH),
            http.get(RealtimeConfiguration.PATH, analyst.bearer()),
            http.get(RealtimeConfiguration.PATH, admin.bearer()))) {
      answers.add(response.status());
      assertThat(response.json().get("type").asString())
          .isEqualTo("urn:fraudshield:problem:upgrade-required");
    }
    assertThat(answers).as("never 401 or 403 on role").containsExactly(426);
    assertThat(http.get(RealtimeConfiguration.PATH + "/sub").status())
        .as("sub-paths stay closed by default")
        .isIn(401, 403, 404);
  }

  @Test
  @Tag("FR-04-02")
  void stompDeliversItsInstitutionsEventsSurvivesReauthenticationAndEndsOnRevocation()
      throws Exception {
    Session analyst = issueSession(createAccount(BANK, "ANALYST", "ACTIVE"));
    Client client = connected(analyst);
    assertThat(client.subscribe()).startsWith("RECEIPT\nreceipt-id:r-alerts");

    RealtimeTestAutoConfiguration.KAFKA.publish("fs.alerts.high", RealtimeProbe.raised(OTHER));
    byte[] mine = RealtimeProbe.raised(BANK);
    RealtimeTestAutoConfiguration.KAFKA.publish("fs.alerts.high", mine);
    String message = client.next(10);
    assertThat(message).startsWith("MESSAGE").contains("\"change\":\"RAISED\"");
    assertThat(client.next(1)).as("another institution's event never arrives").isNull();

    // Re-authentication with a new token of the same session: nothing is dropped (point 3).
    StaffClaims claims = AccessTokens.claims(decoder.decode(analyst.accessToken()));
    client.send(
        "SEND\ndestination:/app/session.reauth\nAuthorization:Bearer "
            + tokens.issue(claims)
            + "\nreceipt:r-reauth\n\n\0");
    assertThat(client.next(10)).startsWith("RECEIPT\nreceipt-id:r-reauth");
    RealtimeTestAutoConfiguration.KAFKA.publish("fs.alerts.medium", RealtimeProbe.raised(BANK));
    assertThat(client.next(10)).startsWith("MESSAGE");

    // Revocation by push: signing out ends the socket within D-27's 5 s.
    long start = System.nanoTime();
    assertThat(http.post("/api/v1/auth/logout", Map.of(), analyst.bearerWithCsrf()).status())
        .isLessThan(300);
    String error = client.next(5);
    assertThat(error).startsWith("ERROR").contains("urn:fraudshield:problem:unauthorized");
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
  }

  @Test
  @Tag("FR-04-10")
  void pushedEqualsListedForEveryEntryRoleAndUser() throws Exception {
    // Point 4: "would be pushed" equals "is listed by queue=all with every outcome", for every
    // seeded entry, every role and every user, with an escalation chain of depth 2.
    Account analyst = createAccount(BANK, "ANALYST", "ACTIVE");
    Account otherAnalyst = createAccount(BANK, "ANALYST", "ACTIVE");
    Account senior = createAccount(BANK, "SENIOR_ANALYST", "ACTIVE");
    Account otherSenior = createAccount(BANK, "SENIOR_ANALYST", "ACTIVE");
    Account officer = createAccount(BANK, "RISK_OFFICER", "ACTIVE");
    List<Account> users = List.of(analyst, otherAnalyst, senior, otherSenior, officer);
    Map<UUID, Session> sessions = new HashMap<>();
    for (Account user : users) {
      sessions.put(user.id(), issueSession(user));
    }

    List<UUID> seeded = new ArrayList<>();
    for (String status : STATUSES) {
      Tx tx = new Tx(BANK);
      tx.at = Instant.now();
      Alert alert = new Alert(tx.write());
      alert.status = status;
      seeded.add(alert.write().id);
    }
    UUID first = escalate(sessions.get(analyst.id()), heldNow(), "SENIOR_ANALYST", 0);
    UUID second = escalate(sessions.get(senior.id()), first, "RISK_OFFICER", 0);
    UUID direct = escalate(sessions.get(otherAnalyst.id()), heldNow(), "RISK_OFFICER", 0);
    seeded.addAll(List.of(first, second, direct));

    Map<UUID, byte[]> published = new ConcurrentHashMap<>();
    try (ChangePublisher publisher =
        new ChangePublisher(
            tenants,
            jdbc,
            alerts,
            (key, value, failed) ->
                published.put(UUID.fromString(RealtimeProbe.alertId(value)), value),
            "fraudshield-api",
            100,
            Clock.systemUTC())) {
      for (UUID entry : seeded) {
        publisher.offer(BANK, entry, 0, AlertChangePublisher.Change.DECIDED, null);
      }
      long until = System.nanoTime() + 20_000_000_000L;
      while (published.size() < seeded.size() && System.nanoTime() < until) {
        Thread.sleep(50);
      }
    }
    assertThat(published.keySet()).containsExactlyInAnyOrderElementsOf(seeded);

    List<String> differences = new ArrayList<>();
    for (Account user : users) {
      Set<UUID> listed = listed(sessions.get(user.id()));
      for (UUID entry : seeded) {
        boolean pushed =
            RealtimeProbe.wouldPush(
                published.get(entry), user.id(), BANK, StaffRole.valueOf(user.role()));
        if (pushed != listed.contains(entry)) {
          differences.add(user.role() + " " + user.id() + " entry " + entry + " pushed=" + pushed);
        }
      }
    }
    assertThat(differences).isEmpty();
    assertThat(listed(sessions.get(otherAnalyst.id())))
        .as("the filter is not vacuous: an analyst does not see the senior's escalation")
        .doesNotContain(first);
  }

  private static UUID heldNow() {
    Tx tx = new Tx(BANK);
    tx.at = Instant.now();
    return new Alert(tx.write()).write().id;
  }

  private UUID escalate(Session by, UUID entry, String target, long version) {
    Http.Response escalated =
        http.post(
            "/api/v1/alerts/" + entry + "/escalations",
            Map.of("target_role", target, "reason", "Unusual pattern", "alert_version", version),
            by.bearer()[0],
            by.bearer()[1],
            "Idempotency-Key",
            UUID.randomUUID().toString());
    assertThat(escalated.status()).as(escalated.body()).isEqualTo(201);
    return UUID.fromString(escalated.json().get("alert_id").asString());
  }

  private Set<UUID> listed(Session session) {
    StringBuilder query = new StringBuilder("/api/v1/alerts?queue=all&limit=200");
    for (String status : STATUSES) {
      query.append("&outcome=").append(status);
    }
    Http.Response page = http.get(query.toString(), session.bearer());
    assertThat(page.status()).as(page.body()).isEqualTo(200);
    Set<UUID> ids = new HashSet<>();
    for (JsonNode item : page.json().get("items")) {
      ids.add(UUID.fromString(item.get("alert_id").asString()));
    }
    assertThat(page.json().get("next_cursor").isNull()).as("one page holds the seed").isTrue();
    return ids;
  }
}
