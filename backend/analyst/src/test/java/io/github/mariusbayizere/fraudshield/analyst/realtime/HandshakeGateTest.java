package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The handshake's refusals (ADR 0115 points 2, 3, 5 and 8): one answer for every caller. */
class HandshakeGateTest {

  private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
  private final RealtimeSettings settings = RealtimeSettings.defaults();
  private final AtomicBoolean consumer = new AtomicBoolean(true);
  private final SessionRegistry registry =
      new SessionRegistry(
          settings, new InstanceBudget(settings.instanceBudgetBytes()), System::nanoTime);
  private final SessionGuard guard =
      new SessionGuard(
          registry,
          mock(SessionStateCache.class),
          mock(SafeRedis.class),
          settings,
          consumer::get,
          thread,
          thread,
          System::nanoTime,
          Clock.systemUTC());

  @AfterEach
  void stop() {
    thread.shutdownNow();
  }

  private static MockHttpServletRequest upgrade(String origin) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/realtime");
    request.setScheme("https");
    request.setServerName("console.example.rw");
    request.setServerPort(443);
    request.setRemoteAddr("10.3.3.3");
    request.addHeader("Upgrade", "websocket");
    request.addHeader("Connection", "Upgrade");
    request.addHeader("Sec-WebSocket-Protocol", "v10.stomp, v12.stomp");
    if (origin != null) {
      request.addHeader("Origin", origin);
    }
    return request;
  }

  private MockHttpServletResponse answer(HandshakeGate gate, MockHttpServletRequest request)
      throws IOException {
    MockHttpServletResponse response = new MockHttpServletResponse();
    Map<String, Object> attributes = new HashMap<>();
    boolean admitted =
        gate.beforeHandshake(
            new ServletServerHttpRequest(request),
            new ServletServerHttpResponse(response),
            null,
            attributes);
    if (admitted) {
      assertThat(attributes).containsEntry(StompEndpoint.addressAttribute(), "10.3.3.3");
      response.setStatus(101);
    }
    return response;
  }

  private static void assertProblem(MockHttpServletResponse response, int status, String type)
      throws IOException {
    assertThat(response.getStatus()).isEqualTo(status);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString()).contains("urn:fraudshield:problem:" + type);
  }

  @Test
  @Tag("FR-04-02")
  void anythingButStompWebSocketUpgradeIsUpgradeRequired() throws IOException {
    HandshakeGate gate = new HandshakeGate(registry, guard, List.of());
    assertProblem(
        answer(gate, new MockHttpServletRequest("GET", "/api/v1/realtime")),
        426,
        "upgrade-required");
    MockHttpServletRequest noStomp = new MockHttpServletRequest("GET", "/api/v1/realtime");
    noStomp.addHeader("Upgrade", "WebSocket");
    noStomp.addHeader("Sec-WebSocket-Protocol", "v10.stomp");
    assertProblem(answer(gate, noStomp), 426, "upgrade-required");
  }

  @Test
  @Tag("FR-04-02")
  void onlyTheApisOwnOriginIsAllowedByDefault() throws IOException {
    HandshakeGate gate = new HandshakeGate(registry, guard, List.of());
    assertThat(answer(gate, upgrade("https://console.example.rw")).getStatus()).isEqualTo(101);
    assertThat(answer(gate, upgrade("HTTPS://Console.Example.RW")).getStatus()).isEqualTo(101);
    assertProblem(answer(gate, upgrade("https://evil.example")), 403, "forbidden");
    assertProblem(answer(gate, upgrade(null)), 403, "forbidden");
    HandshakeGate configured =
        new HandshakeGate(registry, guard, List.of("https://app.example.rw"));
    assertThat(answer(configured, upgrade("https://app.example.rw")).getStatus()).isEqualTo(101);
    assertProblem(answer(configured, upgrade("https://console.example.rw")), 403, "forbidden");
  }

  @Test
  @Tag("FR-04-02")
  void channelThatIsNotOpenAnswersServiceUnavailableWithRetryAfter() throws IOException {
    consumer.set(false);
    MockHttpServletResponse response =
        answer(
            new HandshakeGate(registry, guard, List.of()), upgrade("https://console.example.rw"));
    assertProblem(response, 503, "service-unavailable");
    assertThat(response.getHeader("Retry-After")).isEqualTo("5");
  }

  @Test
  @Tag("FR-04-02")
  void anAddressOverItsPendingLimitIsTooManyRequests() throws IOException {
    RealtimeSettings tight =
        new RealtimeSettings(
            List.of(),
            settings.connectTimeout(),
            settings.sessionsPerUser(),
            settings.sessionsPerInstance(),
            settings.pairsPerInstance(),
            settings.pendingPerInstance(),
            2,
            settings.handshakesPerAddressPerMinute(),
            settings.connectsPerUserPerMinute(),
            settings.inboundFrameBytes(),
            settings.queueFrames(),
            settings.queueBytes(),
            settings.instanceBudgetBytes(),
            settings.heartBeat(),
            settings.maxClientHeartBeat(),
            settings.backstopPeriod(),
            settings.backstopJitter(),
            settings.loadsPerSecond(),
            settings.redisPing(),
            settings.presenceRefresh(),
            settings.presenceLifetime(),
            settings.handOffQueue(),
            settings.producerBufferBytes(),
            settings.producerMaxBlock(),
            settings.nameCacheTtl(),
            settings.resolverThreads(),
            settings.resolverQueue(),
            settings.resolverTimeout(),
            settings.consumerStall(),
            settings.partitionRefresh());
    SessionRegistry limited =
        new SessionRegistry(
            tight, new InstanceBudget(tight.instanceBudgetBytes()), System::nanoTime);
    HandshakeGate gate = new HandshakeGate(limited, guard, List.of());
    assertThat(answer(gate, upgrade("https://console.example.rw")).getStatus()).isEqualTo(101);
    assertThat(answer(gate, upgrade("https://console.example.rw")).getStatus()).isEqualTo(101);
    assertProblem(answer(gate, upgrade("https://console.example.rw")), 429, "rate-limited");
  }

  @Test
  @Tag("FR-04-02")
  void failedUpgradeReleasesItsReservedSlot() throws IOException {
    HandshakeGate gate = new HandshakeGate(registry, guard, List.of());
    for (int i = 0; i < settings.pendingPerInstance(); i++) {
      assertThat(answer(gate, upgrade("https://console.example.rw")).getStatus()).isEqualTo(101);
    }
    assertProblem(answer(gate, upgrade("https://console.example.rw")), 503, "service-unavailable");
    MockHttpServletResponse failed = new MockHttpServletResponse();
    failed.setStatus(400);
    gate.afterHandshake(
        new ServletServerHttpRequest(upgrade("https://console.example.rw")),
        new ServletServerHttpResponse(failed),
        null,
        null);
    assertThat(answer(gate, upgrade("https://console.example.rw")).getStatus()).isEqualTo(101);
  }

  @Test
  @Tag("FR-04-02")
  void successfulUpgradeKeepsItsSlotUntilTheSocketIsCounted() throws IOException {
    HandshakeGate gate = new HandshakeGate(registry, guard, List.of());
    for (int i = 0; i < settings.pendingPerInstance(); i++) {
      assertThat(answer(gate, upgrade("https://console.example.rw")).getStatus()).isEqualTo(101);
      MockHttpServletResponse upgraded = new MockHttpServletResponse();
      upgraded.setStatus(101);
      gate.afterHandshake(
          new ServletServerHttpRequest(upgrade("https://console.example.rw")),
          new ServletServerHttpResponse(upgraded),
          null,
          null);
    }
    assertProblem(answer(gate, upgrade("https://console.example.rw")), 503, "service-unavailable");
  }
}
