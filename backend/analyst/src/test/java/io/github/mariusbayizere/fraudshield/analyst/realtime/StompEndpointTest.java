package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.github.mariusbayizere.fraudshield.analyst.performance.Presence;
import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

/**
 * The STOMP rules of ADR 0115 points 2, 3, 4 and 8, against the endpoint with a fake socket and a
 * fake token decoder. The same rules over real sockets, Kafka and Redis run in CI.
 */
class StompEndpointTest {

  private static final UUID BANK_A = UUID.randomUUID();
  private static final UUID BANK_B = UUID.randomUUID();

  private final Map<String, Jwt> issued = new HashMap<>();
  private final JwtDecoder decoder =
      token -> {
        Jwt jwt = issued.get(token);
        if (jwt == null) {
          throw new BadJwtException("unknown token");
        }
        return jwt;
      };
  private final SafeRedis redis = mock(SafeRedis.class);
  private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
  private final RealtimeSettings settings = RealtimeSettings.defaults();
  private final InstanceBudget budget = new InstanceBudget(settings.instanceBudgetBytes());
  private final SessionRegistry registry = new SessionRegistry(settings, budget, System::nanoTime);
  private final SessionGuard guard =
      new SessionGuard(
          registry,
          mock(SessionStateCache.class),
          redis,
          settings,
          () -> true,
          thread,
          System::nanoTime,
          Clock.systemUTC());
  private final StompEndpoint endpoint =
      new StompEndpoint(
          registry,
          guard,
          decoder,
          settings,
          budget,
          frame -> {},
          StompEndpoint.Subscribed.NONE,
          System::nanoTime);

  @AfterEach
  void stop() {
    thread.shutdownNow();
  }

  private String token(UUID user, UUID institution, StaffRole role, UUID sid, long version) {
    String token = UUID.randomUUID().toString();
    issued.put(
        token,
        Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .subject(user.toString())
            .claim("institution_id", institution.toString())
            .claim("role", role.name())
            .claim("token_version", version)
            .claim("sid", sid.toString())
            .expiresAt(Instant.now().plusSeconds(900))
            .build());
    return token;
  }

  private String token(UUID institution, StaffRole role) {
    return token(UUID.randomUUID(), institution, role, UUID.randomUUID(), 0);
  }

  private FakeSocket open() {
    FakeSocket socket = new FakeSocket("10.0.0.1");
    endpoint.afterConnectionEstablished(socket);
    return socket;
  }

  private void send(FakeSocket socket, String frame) {
    endpoint.handleTextMessage(socket, new TextMessage(frame));
  }

  private static String connect(String token) {
    return "CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n\0";
  }

  private static String subscribe(String destination, String id, String receipt) {
    return "SUBSCRIBE\ndestination:"
        + destination
        + "\nid:"
        + id
        + "\nreceipt:"
        + receipt
        + "\n\n\0";
  }

  private FakeSocket connected(String token) throws InterruptedException {
    FakeSocket socket = open();
    send(socket, connect(token));
    assertThat(socket.next()).startsWith("CONNECTED\nversion:1.2");
    return socket;
  }

  private static void assertError(FakeSocket socket, String problem) throws InterruptedException {
    String frame = socket.next();
    assertThat(frame).startsWith("ERROR").contains("urn:fraudshield:problem:" + problem);
    assertThat(socket.awaitClosed()).as("closed after the ERROR").isTrue();
  }

  @Test
  @Tag("FR-04-02")
  void frameBeforeConnectIsRefusedAndCloses() throws InterruptedException {
    FakeSocket socket = open();
    send(socket, subscribe(StompEndpoint.ALERTS, "s1", "r1"));
    assertError(socket, "unauthorized");
  }

  @Test
  @Tag("FR-04-02")
  void connectWithBearerTokenAndNoCsrfTokenIsAccepted() throws InterruptedException {
    FakeSocket socket = connected(token(BANK_A, StaffRole.ANALYST));
    send(socket, subscribe(StompEndpoint.ALERTS, "s1", "r1"));
    assertThat(socket.next()).isEqualTo("RECEIPT\nreceipt-id:r1\n\n\0");
    assertThat(registry.authenticated()).isEqualTo(1);
  }

  @Test
  @Tag("FR-04-02")
  void onlyTheThreeDecisionRolesAreAdmitted() throws InterruptedException {
    FakeSocket admin = open();
    send(admin, connect(token(BANK_A, StaffRole.ADMIN)));
    assertError(admin, "forbidden");
    FakeSocket none = open();
    send(none, "CONNECT\naccept-version:1.2\n\n\0");
    assertError(none, "unauthorized");
    FakeSocket apiKey = open();
    send(apiKey, "CONNECT\naccept-version:1.2\nAuthorization:Bearer fsk_live_not_a_jwt\n\n\0");
    assertError(apiKey, "unauthorized");
    for (StaffRole role :
        new StaffRole[] {StaffRole.ANALYST, StaffRole.SENIOR_ANALYST, StaffRole.RISK_OFFICER}) {
      connected(token(BANK_A, role));
    }
  }

  @Test
  @Tag("FR-04-02")
  void secondConnectIsRefusedWhateverItsToken() throws InterruptedException {
    FakeSocket socket = connected(token(BANK_A, StaffRole.ANALYST));
    send(socket, connect(token(BANK_B, StaffRole.RISK_OFFICER)));
    assertError(socket, "forbidden");
    assertThat(registry.sessionsOf(BANK_B)).isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void onlyTheTwoDestinationsMayBeSubscribed() throws InterruptedException {
    FakeSocket topic = connected(token(BANK_A, StaffRole.ANALYST));
    send(topic, subscribe("/topic/alerts", "s1", "r1"));
    assertError(topic, "forbidden");
    FakeSocket translated = connected(token(BANK_A, StaffRole.ANALYST));
    send(translated, subscribe("/user/queue/alerts-user" + translated.getId(), "s1", "r1"));
    assertError(translated, "forbidden");
    FakeSocket sendElsewhere = connected(token(BANK_A, StaffRole.ANALYST));
    send(sendElsewhere, "SEND\ndestination:/app/other\n\n\0");
    assertError(sendElsewhere, "forbidden");
  }

  @Test
  @Tag("FR-04-02")
  void reauthenticationReplacesTheTokenAndKeepsTheSubscriptions() throws InterruptedException {
    UUID user = UUID.randomUUID();
    UUID sid = UUID.randomUUID();
    FakeSocket socket = connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    send(socket, subscribe(StompEndpoint.ALERTS, "s1", "r1"));
    assertThat(socket.next()).startsWith("RECEIPT");
    String fresh = token(user, BANK_A, StaffRole.ANALYST, sid, 1);
    send(
        socket,
        "SEND\ndestination:/app/session.reauth\nAuthorization:Bearer "
            + fresh
            + "\nreceipt:r2\n\n\0");
    assertThat(socket.next()).isEqualTo("RECEIPT\nreceipt-id:r2\n\n\0");
    RealtimeSession session = registry.sessionsOf(BANK_A).iterator().next();
    assertThat(session.binding().claims().tokenVersion()).isEqualTo(1);
    assertThat(session.subscribed(StompEndpoint.ALERTS)).isTrue();
  }

  @Test
  @Tag("FR-04-02")
  void reauthenticationWithAnotherSessionOrIdentityIsRefused() throws InterruptedException {
    UUID user = UUID.randomUUID();
    UUID sid = UUID.randomUUID();
    FakeSocket otherSid = connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    send(
        otherSid,
        "SEND\ndestination:/app/session.reauth\nAuthorization:Bearer "
            + token(user, BANK_A, StaffRole.ANALYST, UUID.randomUUID(), 0)
            + "\n\n\0");
    assertThat(otherSid.next()).contains("session changed");
    FakeSocket otherRole = connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    send(
        otherRole,
        "SEND\ndestination:/app/session.reauth\nAuthorization:Bearer "
            + token(user, BANK_A, StaffRole.SENIOR_ANALYST, sid, 1)
            + "\n\n\0");
    assertError(otherRole, "forbidden");
    FakeSocket invalid = connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    send(invalid, "SEND\ndestination:/app/session.reauth\nAuthorization:Bearer nope\n\n\0");
    assertError(invalid, "unauthorized");
  }

  @Test
  @Tag("FR-04-02")
  void anOversizedFrameIsRefused() throws InterruptedException {
    FakeSocket socket = connected(token(BANK_A, StaffRole.ANALYST));
    send(socket, "SEND\ndestination:/app/session.reauth\nx:" + "a".repeat(9_000) + "\n\n\0");
    assertError(socket, "validation");
  }

  @Test
  @Tag("FR-04-02")
  void userBeyondFiveSessionsLosesTheOldest() throws InterruptedException {
    UUID user = UUID.randomUUID();
    UUID sid = UUID.randomUUID();
    FakeSocket oldest = connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    for (int i = 0; i < 4; i++) {
      connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    }
    connected(token(user, BANK_A, StaffRole.ANALYST, sid, 0));
    assertError(oldest, "rate-limited");
    assertThat(registry.authenticated()).isEqualTo(5);
  }

  @Test
  @Tag("FR-04-02")
  void presenceIsWrittenOnlyForTheThreeDecisionRoles() throws InterruptedException {
    Binding admin =
        new Binding(
            new io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims(
                UUID.randomUUID(), BANK_A, StaffRole.ADMIN, null, null, 0, UUID.randomUUID()),
            Instant.now().plusSeconds(60));
    guard.present(admin);
    verify(redis, after(200).never()).addScored(anyString(), anyString(), anyDouble(), any());
    connected(token(BANK_A, StaffRole.SENIOR_ANALYST));
    verify(redis, timeout(1_000))
        .addScored(
            org.mockito.ArgumentMatchers.eq(Presence.key(BANK_A)), anyString(), anyDouble(), any());
  }

  @Test
  @Tag("FR-04-10")
  void eventsReachOnlyTheirInstitutionAndTheRolesListAlertsWouldShow() throws InterruptedException {
    FanOut fanOut =
        new FanOut(
            null,
            new AlertFrames(),
            mock(StaffNames.class),
            registry,
            institution -> {},
            settings,
            System::nanoTime,
            partition -> {});
    UUID escalator = UUID.randomUUID();
    final FakeSocket analyst = subscribed(token(BANK_A, StaffRole.ANALYST));
    FakeSocket senior = subscribed(token(BANK_A, StaffRole.SENIOR_ANALYST));
    FakeSocket byEscalator =
        subscribed(token(escalator, BANK_A, StaffRole.ANALYST, UUID.randomUUID(), 0));
    final FakeSocket otherBank = subscribed(token(BANK_B, StaffRole.RISK_OFFICER));

    fanOut.route(ChangePublisher.TOPIC, RealtimeFixtures.escalationCreated(BANK_A, escalator), 1);
    assertThat(senior.next()).startsWith("MESSAGE").contains("\"change\":\"ESCALATION_CREATED\"");
    assertThat(byEscalator.next()).startsWith("MESSAGE");
    assertThat(analyst.next()).as("below the target role").isNull();
    assertThat(otherBank.next()).as("another institution").isNull();

    fanOut.route("fs.alerts.high", RealtimeFixtures.raised(BANK_A, true), 1);
    assertThat(analyst.next()).startsWith("MESSAGE").contains("\"change\":\"RAISED\"");
    assertThat(otherBank.next()).isNull();
  }

  @Test
  @Tag("FR-04-02")
  void readerTooFarBehindIsClosedWithOverflowWithoutDelayingOthers() throws InterruptedException {
    FanOut fanOut =
        new FanOut(
            null,
            new AlertFrames(),
            mock(StaffNames.class),
            registry,
            institution -> {},
            settings,
            System::nanoTime,
            partition -> {});
    FakeSocket slow = subscribed(token(BANK_A, StaffRole.ANALYST));
    FakeSocket fast = subscribed(token(BANK_A, StaffRole.ANALYST));
    slow.stall();
    for (int i = 0; i < settings.queueFrames() + 10; i++) {
      fanOut.route("fs.alerts.high", RealtimeFixtures.raised(BANK_A, true), 1);
    }
    assertThat(fast.next()).as("the fast reader is not delayed").startsWith("MESSAGE");
    slow.release();
    String frame = slow.next();
    while (frame != null && frame.startsWith("MESSAGE")) {
      frame = slow.next();
    }
    assertThat(frame).startsWith("ERROR").contains("realtime-overflow");
    assertThat(slow.awaitClosed()).isTrue();
    endpoint.afterConnectionClosed(slow, CloseStatus.NORMAL);
    assertThat(registry.authenticated()).isEqualTo(1);
  }

  private FakeSocket subscribed(String token) throws InterruptedException {
    FakeSocket socket = connected(token);
    send(socket, subscribe(StompEndpoint.ALERTS, "s1", "r1"));
    assertThat(socket.next()).startsWith("RECEIPT");
    return socket;
  }
}
