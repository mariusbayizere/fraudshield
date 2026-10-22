package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.session.SessionStateCache;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Revocation, Redis, expiry and the CONNECT deadline (ADR 0115 points 2, 3 and 5). */
class SessionGuardTest {

  private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final SessionStateCache sessions = mock(SessionStateCache.class);
  private final SafeRedis redis = mock(SafeRedis.class);
  private final AtomicBoolean consumer = new AtomicBoolean(true);
  private final ScheduledExecutorService thread = Executors.newSingleThreadScheduledExecutor();
  private final RealtimeSettings settings = RealtimeSettings.defaults();
  private final InstanceBudget budget = new InstanceBudget(settings.instanceBudgetBytes());
  private final SessionRegistry registry = new SessionRegistry(settings, budget, nanos::get);
  private final SessionGuard guard =
      new SessionGuard(
          registry,
          sessions,
          redis,
          settings,
          consumer::get,
          thread,
          nanos::get,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @AfterEach
  void stop() {
    thread.shutdownNow();
  }

  private FakeSocket bound(StaffClaims claims, Instant expiresAt) {
    FakeSocket socket = new FakeSocket("10.0.0.2");
    RealtimeSession session = pending(socket);
    assertThat(registry.bind(session, new Binding(claims, expiresAt), new ArrayList<>())).isEmpty();
    return socket;
  }

  private RealtimeSession pending(FakeSocket socket) {
    RealtimeSession session =
        new RealtimeSession(
            socket,
            "10.0.0.2",
            nanos.get(),
            new Outbound(settings.queueFrames(), settings.queueBytes(), budget),
            frame -> {});
    registry.opened(session);
    session.start();
    return session;
  }

  private static StaffClaims claims(StaffRole role) {
    return new StaffClaims(
        UUID.randomUUID(), UUID.randomUUID(), role, null, null, 0, UUID.randomUUID());
  }

  @Test
  @Tag("FR-04-02")
  void revokedSessionIsClosedByTheBackstop() throws InterruptedException {
    StaffClaims revoked = claims(StaffRole.ANALYST);
    StaffClaims current = claims(StaffRole.ANALYST);
    when(sessions.isCurrent(eq(revoked), any())).thenReturn(SessionStateCache.Currency.NOT_CURRENT);
    when(sessions.isCurrent(eq(current), any())).thenReturn(SessionStateCache.Currency.CURRENT);
    FakeSocket gone = bound(revoked, NOW.plusSeconds(600));
    FakeSocket kept = bound(current, NOW.plusSeconds(600));
    guard.backstop();
    assertThat(gone.next()).startsWith("ERROR").contains("session ended");
    assertThat(kept.next()).isNull();
  }

  @Test
  @Tag("FR-04-02")
  void revocationAnnouncementRechecksTheMatchingSessions() throws InterruptedException {
    StaffClaims revoked = claims(StaffRole.ANALYST);
    when(sessions.isCurrent(eq(revoked), any())).thenReturn(SessionStateCache.Currency.NOT_CURRENT);
    FakeSocket gone = bound(revoked, NOW.plusSeconds(600));
    guard.versionChanged(revoked.userId());
    assertThat(gone.next()).startsWith("ERROR").contains("unauthorized");
  }

  @Test
  @Tag("FR-04-02")
  void pairUnansweredForTwoPeriodsFailsClosed() throws InterruptedException {
    StaffClaims unknown = claims(StaffRole.ANALYST);
    when(sessions.isCurrent(eq(unknown), any())).thenReturn(SessionStateCache.Currency.UNKNOWN);
    final FakeSocket socket = bound(unknown, NOW.plusSeconds(600));
    guard.backstop();
    nanos.addAndGet(settings.backstopPeriod().toNanos());
    guard.backstop();
    assertThat(socket.next()).as("one period is not enough").isNull();
    nanos.addAndGet(settings.backstopPeriod().toNanos());
    guard.backstop();
    assertThat(socket.next()).startsWith("ERROR").contains("service-unavailable");
  }

  @Test
  @Tag("FR-04-02")
  void redisDownClosesEverySessionAndTwoGoodProbesReopen() throws InterruptedException {
    final FakeSocket socket = bound(claims(StaffRole.ANALYST), NOW.plusSeconds(600));
    when(redis.ping()).thenReturn(false);
    when(redis.available()).thenReturn(false);
    guard.probeRedis();
    assertThat(socket.next()).startsWith("ERROR").contains("service-unavailable");
    assertThat(guard.open()).isFalse();
    when(redis.ping()).thenReturn(true);
    guard.probeRedis();
    assertThat(guard.open()).as("one good probe").isFalse();
    guard.probeRedis();
    assertThat(guard.open()).isTrue();
  }

  @Test
  @Tag("FR-04-02")
  void anExpiredTokenAndMissingConnectEndTheSession() throws InterruptedException {
    FakeSocket expired = bound(claims(StaffRole.ANALYST), NOW);
    FakeSocket silent = new FakeSocket("10.0.0.3");
    pending(silent);
    guard.sweep();
    assertThat(expired.next()).contains("access token expired");
    assertThat(silent.next()).as("before 5 s").isNull();
    nanos.addAndGet(settings.connectTimeout().toNanos());
    guard.sweep();
    assertThat(silent.next()).startsWith("ERROR").contains("unauthorized");
  }

  @Test
  @Tag("FR-04-02")
  void stalledConsumerClosesTheSessionsAndRefusesHandshakes() throws InterruptedException {
    final FakeSocket socket = bound(claims(StaffRole.ANALYST), NOW.plusSeconds(600));
    consumer.set(false);
    assertThat(guard.open()).isFalse();
    guard.sweep();
    assertThat(socket.next()).startsWith("ERROR").contains("feed interrupted");
  }
}
