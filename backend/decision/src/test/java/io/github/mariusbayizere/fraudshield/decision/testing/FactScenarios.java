package io.github.mariusbayizere.fraudshield.decision.testing;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionTransitionService;
import io.github.mariusbayizere.fraudshield.decision.application.HoldTimeoutService;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives the application services through every kind of fact they record: HIGH with an auto-block,
 * a freeze on the third HIGH, a MEDIUM hold that times out, a fallback decision, an anomaly-only
 * approval, a frozen-account decline, a customer "yes, this was me", an analyst decision on a hold
 * and a circuit-breaker change.
 */
public final class FactScenarios {

  private FactScenarios() {}

  /**
   * The facts, in the order the services recorded them.
   *
   * @return every kind of fact at least once
   */
  public static List<DecisionEvent> everyKindOfFact() {
    InMemoryPorts ports = new InMemoryPorts();
    MutableClock clock = new MutableClock(NOW);
    DecisionService service =
        new DecisionService(
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            DecisionMetrics.NONE,
            DecisionSettings.DEFAULTS,
            clock);
    ports.scores = t -> Optional.of(0.95);
    for (int i = 0; i < 3; i++) {
      service.decide(Fixtures.transaction("15000.5"), new byte[32], System.nanoTime());
      clock.advance(Duration.ofMinutes(1));
    }
    ports.scores = t -> Optional.of(0.7);
    Transaction held =
        Fixtures.transaction(
            UUID.randomUUID(), "tok_OtherAccountQqqqRrrrSsss01", "250", Channel.AGENT_BANKING);
    service.decide(held, new byte[32], System.nanoTime());
    ports.scores = t -> Optional.empty();
    service.decide(
        Fixtures.transaction(
            UUID.randomUUID(), "tok_ThirdAccountTtttUuuuVvvv01", "3000000", Channel.USSD),
        new byte[32],
        System.nanoTime());
    ports.scores = t -> Optional.of(0.1);
    ports.anomaly = 0.999;
    service.decide(
        Fixtures.transaction(
            UUID.randomUUID(), "tok_FourthAccountWwwwXxxxYyy1", "10", Channel.CARD),
        new byte[32],
        System.nanoTime());
    service.decide(Fixtures.transaction("20"), new byte[32], System.nanoTime());

    clock.advance(Duration.ofSeconds(30));
    new HoldTimeoutService(ports, ports, ports, DecisionMetrics.NONE, clock, "api-0").tick();
    UUID declined =
        ports.latest.values().stream()
            .filter(s -> s.decision().name().equals("DECLINE") && s.sequence() == 1)
            .findFirst()
            .orElseThrow()
            .transactionId();
    new DecisionTransitionService(ports, ports, ports, clock)
        .customerAnswered(INSTITUTION, declined, true, NOW);
    UUID heldAgain =
        ports.latest.values().stream()
            .filter(s -> s.decision().name().equals("HOLD"))
            .findFirst()
            .map(DecisionState::transactionId)
            .orElse(null);
    if (heldAgain == null) {
      Transaction second =
          Fixtures.transaction(
              UUID.randomUUID(), "tok_FifthAccountZzzzAaaaBbb1", "400", Channel.ONLINE);
      ports.scores = t -> Optional.of(0.7);
      service.decide(second, new byte[32], System.nanoTime());
      heldAgain = second.transactionId();
    }
    new DecisionTransitionService(ports, ports, ports, clock)
        .analystDecidesHold(INSTITUTION, heldAgain, true, UUID.randomUUID(), "ONLINE", NOW);

    List<DecisionEvent> events = new ArrayList<>(ports.events);
    events.add(
        new DecisionEvent.CircuitBreakerChanged(
            INSTITUTION,
            "6051",
            MccCircuitBreaker.Change.OPENED,
            new MccCircuitBreaker.WindowCounts(120, 7),
            1,
            NOW));
    return events;
  }

  /**
   * A hold scheduled by hand, for tests that need one without a decision.
   *
   * @param ports the ports
   * @param policy the timeout policy
   * @return the held transaction
   */
  public static UUID hold(InMemoryPorts ports, MediumTimeoutPolicy policy) {
    UUID tx = UUID.randomUUID();
    ports.latest.put(
        tx,
        DecisionState.initial(INSTITUTION, tx, Decision.HOLD, NOW, List.of(), NOW.plusSeconds(30)));
    ports.schedule(
        new HoldSchedulePort.DueHold(INSTITUTION, tx, "CARD", policy, NOW.plusSeconds(30)));
    return tx;
  }
}
