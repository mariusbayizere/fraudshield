package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** When the figures are recomputed and pushed (ADR 0115 point 6.3). */
class FiguresPusherTest {

  private static final long SECOND = 1_000_000_000L;
  private static final UUID BANK = UUID.randomUUID();

  private final AtomicLong nanos = new AtomicLong(100 * SECOND);
  private final AtomicInteger computed = new AtomicInteger();
  private final RealtimeSettings settings = RealtimeSettings.defaults();
  private final InstanceBudget budget = new InstanceBudget(settings.instanceBudgetBytes());
  private final SessionRegistry registry = new SessionRegistry(settings, budget, nanos::get);
  private final FiguresPusher pusher =
      new FiguresPusher(
          registry,
          institution -> {
            computed.incrementAndGet();
            return Map.of("open_alerts", 3);
          },
          nanos::get,
          Clock.systemUTC());

  private FakeSocket subscribed() throws InterruptedException {
    FakeSocket socket = new FakeSocket("10.0.0.1");
    RealtimeSession session =
        new RealtimeSession(
            socket,
            "10.0.0.1",
            nanos.get(),
            new Outbound(settings.queueFrames(), settings.queueBytes(), budget),
            frame -> {});
    registry.opened(session);
    registry.bind(
        session,
        new Binding(
            new StaffClaims(
                UUID.randomUUID(), BANK, StaffRole.ANALYST, null, null, 0, UUID.randomUUID()),
            Instant.now().plusSeconds(600)),
        new ArrayList<>());
    session.subscribe(StompEndpoint.FIGURES, "f1");
    session.start();
    pusher.subscribed(session, StompEndpoint.FIGURES);
    return socket;
  }

  @Test
  @Tag("UX-DASH-01")
  void computedOncePerInstitutionAtMostEveryFiveSecondsAndWhenSomethingChanged()
      throws InterruptedException {
    FakeSocket socket = subscribed();
    pusher.tick();
    assertThat(socket.next()).startsWith("MESSAGE\ndestination:/user/queue/figures");
    assertThat(computed).hasValue(1);

    pusher.alertSeen(BANK);
    nanos.addAndGet(2 * SECOND);
    pusher.tick();
    assertThat(computed).as("not within 5 s").hasValue(1);
    nanos.addAndGet(3 * SECOND);
    pusher.tick();
    assertThat(computed).as("an alert passed").hasValue(2);

    nanos.addAndGet(10 * SECOND);
    pusher.tick();
    assertThat(computed).as("nothing changed").hasValue(2);
    nanos.addAndGet(20 * SECOND);
    pusher.tick();
    assertThat(computed).as("30 s since the last computation").hasValue(3);
  }

  @Test
  @Tag("UX-DASH-01")
  void nothingIsComputedForAnInstitutionWithNoLocalSession() {
    pusher.alertSeen(BANK);
    pusher.tick();
    assertThat(computed).hasValue(0);
  }

  @Test
  @Tag("UX-DASH-01")
  void figuresStopWhenTheInstitutionsLastSessionEnds() throws InterruptedException {
    FakeSocket socket = subscribed();
    pusher.tick();
    assertThat(socket.next()).startsWith("MESSAGE");
    RealtimeSession session = registry.sessionsOf(BANK).iterator().next();
    Binding binding = session.binding();
    registry.closed(session);
    pusher.ended(binding);
    nanos.addAndGet(60 * SECOND);
    pusher.tick();
    assertThat(computed).as("no local session: nothing computed").hasValue(1);
  }
}
