package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The per-instance, per-address and per-user limits (ADR 0115 point 8). */
class SessionRegistryTest {

  private final AtomicLong nanos = new AtomicLong(1);
  private final RealtimeSettings settings = RealtimeSettings.defaults();
  private final InstanceBudget budget = new InstanceBudget(settings.instanceBudgetBytes());
  private final SessionRegistry registry = new SessionRegistry(settings, budget, nanos::get);

  private RealtimeSession socket(String address) {
    return socketIn(registry, address);
  }

  private RealtimeSession socketIn(SessionRegistry into, String address) {
    RealtimeSession s =
        new RealtimeSession(
            new FakeSocket(address),
            address,
            nanos.get(),
            new Outbound(settings.queueFrames(), settings.queueBytes(), budget),
            frame -> {});
    into.opened(s);
    return s;
  }

  private static Binding binding(UUID user, UUID sid) {
    return new Binding(
        new StaffClaims(user, UUID.randomUUID(), StaffRole.ANALYST, null, null, 0, sid),
        Instant.now().plusSeconds(600));
  }

  @Test
  @Tag("FR-04-02")
  void pendingSocketsAreCappedPerInstanceAndPerAddress() {
    for (int i = 0; i < settings.pendingPerInstance(); i++) {
      assertThat(registry.admit("10.0.0." + (i % 3))).isEmpty();
      socket("10.0.0." + (i % 3));
    }
    assertThat(registry.admit("10.9.9.9")).contains(SessionRegistry.Refusal.PENDING_FULL);
  }

  @Test
  @Tag("FR-04-02")
  void handshakesPerAddressAreLimitedPerMinute() {
    for (int i = 0; i < settings.handshakesPerAddressPerMinute(); i++) {
      assertThat(registry.admit("10.1.1.1")).isEmpty();
      registry.closed(socket("10.1.1.1"));
    }
    assertThat(registry.admit("10.1.1.1")).contains(SessionRegistry.Refusal.ADDRESS_RATE);
    nanos.addAndGet(60_000_000_000L);
    assertThat(registry.admit("10.1.1.1")).isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void distinctPairsAreCappedAndConnectsPerUserLimited() {
    for (int i = 0; i < settings.pairsPerInstance(); i++) {
      assertThat(
              registry.bind(
                  socket("10.0.0.1"),
                  binding(UUID.randomUUID(), UUID.randomUUID()),
                  new ArrayList<>()))
          .isEmpty();
    }
    assertThat(registry.pairs()).isEqualTo(settings.pairsPerInstance());
    assertThat(
            registry.bind(
                socket("10.0.0.1"),
                binding(UUID.randomUUID(), UUID.randomUUID()),
                new ArrayList<>()))
        .contains(SessionRegistry.ConnectRefusal.PAIRS_FULL);

    SessionRegistry fresh = new SessionRegistry(settings, budget, nanos::get);
    UUID user = UUID.randomUUID();
    UUID sid = UUID.randomUUID();
    List<RealtimeSession> evicted = new ArrayList<>();
    for (int i = 0; i < settings.connectsPerUserPerMinute(); i++) {
      assertThat(fresh.bind(socketIn(fresh, "10.0.0.1"), binding(user, sid), evicted)).isEmpty();
    }
    assertThat(fresh.bind(socketIn(fresh, "10.0.0.1"), binding(user, sid), evicted))
        .contains(SessionRegistry.ConnectRefusal.RATE_LIMITED);
    assertThat(evicted).hasSize(settings.connectsPerUserPerMinute() - settings.sessionsPerUser());
  }

  @Test
  @Tag("FR-04-02")
  void theLargestQueuesAreShedFirstUntilBelowThreeQuarters() {
    InstanceBudget small = new InstanceBudget(1_000);
    SessionRegistry tight = new SessionRegistry(settings, small, nanos::get);
    RealtimeSession big = queued(tight, small, 600);
    RealtimeSession medium = queued(tight, small, 400);
    RealtimeSession tiny = queued(tight, small, 100);
    assertThat(tight.toShed()).containsExactly(big);
    assertThat(tight.toShed()).doesNotContain(medium, tiny);
  }

  private RealtimeSession queued(SessionRegistry into, InstanceBudget budget, int bytes) {
    RealtimeSession s =
        new RealtimeSession(
            new FakeSocket("10.0.0.1"),
            "10.0.0.1",
            nanos.get(),
            new Outbound(10, 10_000, budget),
            frame -> {});
    into.opened(s);
    s.deliver(new Outbound.Frame("/user/queue/alerts", "x", bytes, 1));
    return s;
  }

  @Test
  @Tag("FR-04-02")
  void socketClosedWhileItsConnectWasAuthenticatedIsNeverCounted() {
    // Code review 2, MAJOR 1: the close callback ran first, on another thread.
    RealtimeSession session = socket("10.0.0.1");
    registry.closed(session);
    assertThat(
            registry.bind(
                session, binding(UUID.randomUUID(), UUID.randomUUID()), new ArrayList<>()))
        .contains(SessionRegistry.ConnectRefusal.CLOSED);
    assertThat(registry.authenticated()).isZero();
    assertThat(registry.pairs()).isZero();
    assertThat(registry.institutions()).isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void refusedConnectsDoNotUseTheUsersRate() {
    UUID user = UUID.randomUUID();
    RealtimeSession closed = socket("10.0.0.1");
    registry.closed(closed);
    for (int i = 0; i < 2 * settings.connectsPerUserPerMinute(); i++) {
      registry.bind(closed, binding(user, UUID.randomUUID()), new ArrayList<>());
    }
    assertThat(
            registry.bind(socket("10.0.0.1"), binding(user, UUID.randomUUID()), new ArrayList<>()))
        .isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void institutionLeavesWithItsLastSession() {
    RealtimeSession session = socket("10.0.0.1");
    Binding b = binding(UUID.randomUUID(), UUID.randomUUID());
    registry.bind(session, b, new ArrayList<>());
    assertThat(registry.institutions()).containsExactly(b.institutionId());
    registry.closed(session);
    assertThat(registry.institutions()).isEmpty();
    assertThat(registry.sessionsOf(b.institutionId())).isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void admittedHandshakesReserveTheirSlotUntilCountedOrLapsed() {
    for (int i = 0; i < settings.pendingPerInstance(); i++) {
      assertThat(registry.admit("10.2.2." + i)).isEmpty();
    }
    assertThat(registry.admit("10.2.3.1"))
        .as("reserved, though no socket is counted yet")
        .contains(SessionRegistry.Refusal.PENDING_FULL);
    nanos.addAndGet(settings.connectTimeout().toNanos());
    assertThat(registry.admit("10.2.3.1")).as("unused reservations lapse").isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void connectsRefusedForFullInstanceDoNotUseTheUsersRate() {
    // Code review 3, MINOR 7: the INSTANCE_FULL and PAIRS_FULL refusals of finding 10.
    List<RealtimeSession> holders = new ArrayList<>();
    for (int i = 0; i < settings.pairsPerInstance(); i++) {
      RealtimeSession holder = socket("10.0.0.1");
      registry.bind(holder, binding(UUID.randomUUID(), UUID.randomUUID()), new ArrayList<>());
      holders.add(holder);
    }
    UUID user = UUID.randomUUID();
    for (int i = 0; i < 2 * settings.connectsPerUserPerMinute(); i++) {
      assertThat(
              registry.bind(
                  socket("10.0.0.2"), binding(user, UUID.randomUUID()), new ArrayList<>()))
          .contains(SessionRegistry.ConnectRefusal.PAIRS_FULL);
    }
    registry.closed(holders.getFirst());
    assertThat(
            registry.bind(socket("10.0.0.2"), binding(user, UUID.randomUUID()), new ArrayList<>()))
        .as("the refusals used none of the user's ten a minute")
        .isEmpty();
  }

  @Test
  @Tag("FR-04-02")
  void reservationIsReleasedWhenItsSocketIsCountedOrItsUpgradeFails() {
    List<RealtimeSession> admitted = new ArrayList<>();
    for (int i = 0; i < settings.pendingPerInstance(); i++) {
      String address = "10.4.4." + i;
      assertThat(registry.admit(address)).isEmpty();
      admitted.add(socket(address));
    }
    for (RealtimeSession s : admitted) {
      registry.bind(s, binding(UUID.randomUUID(), UUID.randomUUID()), new ArrayList<>());
    }
    assertThat(registry.admit("10.4.5.1"))
        .as("counted sockets released their reservations, and are no longer pending")
        .isEmpty();
    for (int i = 1; i < settings.pendingPerInstance(); i++) {
      assertThat(registry.admit("10.4.6." + i)).isEmpty();
    }
    assertThat(registry.admit("10.4.7.1")).contains(SessionRegistry.Refusal.PENDING_FULL);
    registry.released("10.4.6.1");
    assertThat(registry.admit("10.4.7.1")).as("a failed upgrade's slot is free again").isEmpty();
  }
}
