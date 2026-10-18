package io.github.mariusbayizere.fraudshield.decision.application;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TransitionsAndBreakerMonitorTest {

  private final InMemoryPorts ports = new InMemoryPorts();
  private final MutableClock clock = new MutableClock(NOW);

  private UUID declined() {
    UUID tx = UUID.randomUUID();
    ports.latest.put(
        tx,
        DecisionState.initial(
            INSTITUTION, tx, Decision.DECLINE, NOW, List.of("VELOCITY_SPIKE"), null));
    return tx;
  }

  @Test
  @Tag("FR-03-05")
  void yesThisWasMeLiftsTheBlockAndLabelsItLegitimate() {
    DecisionTransitionService transitions =
        new DecisionTransitionService(ports, ports, ports, clock);
    UUID tx = declined();
    DecisionState next = transitions.customerAnswered(INSTITUTION, tx, true, NOW);
    assertThat(next.decision()).isEqualTo(DecisionValue.APPROVE);
    assertThat(next.decidedBy()).isEqualTo(DecidedBy.CUSTOMER_VERIFICATION);
    assertThat(ports.eventsOf(DecisionEvent.LabelRecorded.class))
        .singleElement()
        .satisfies(
            label -> {
              assertThat(label.fraud()).isFalse();
              assertThat(label.source()).isEqualTo("CUSTOMER");
            });
    assertThatThrownBy(() -> transitions.customerAnswered(INSTITUTION, tx, true, NOW))
        .isInstanceOf(DecisionTransitionService.TransitionRefusedException.class);
  }

  @Test
  @Tag("FR-03-05")
  void noThisWasNotMeLabelsFraudAndKeepsTheBlock() {
    DecisionTransitionService transitions =
        new DecisionTransitionService(ports, ports, ports, clock);
    UUID tx = declined();
    assertThat(transitions.customerAnswered(INSTITUTION, tx, false, NOW).decision())
        .isEqualTo(DecisionValue.DECLINE);
    assertThat(ports.eventsOf(DecisionEvent.LabelRecorded.class).getFirst().fraud()).isTrue();
    assertThatThrownBy(
            () -> transitions.customerAnswered(INSTITUTION, UUID.randomUUID(), true, NOW))
        .extracting(e -> ((DecisionTransitionService.TransitionRefusedException) e).refusal())
        .isEqualTo(DecisionTransitionService.Refusal.NOT_FOUND);
  }

  @Test
  @Tag("FR-03-07")
  void theMonitorOpensAndClosesBreakersAndRecordsEachChangeOnce() {
    CircuitBreakerMonitor monitor = new CircuitBreakerMonitor(ports, ports, ports, clock);
    CircuitBreakerPort.Key key = new CircuitBreakerPort.Key(INSTITUTION, "6051");
    ports.windowCounts.put(key, new MccCircuitBreaker.WindowCounts(100, 6));
    assertThat(monitor.evaluate()).isEqualTo(1);
    assertThat(ports.isOpen(INSTITUTION, "6051")).isTrue();
    assertThat(monitor.evaluate()).isZero();

    ports.windowCounts.put(key, new MccCircuitBreaker.WindowCounts(100, 0));
    clock.advance(Duration.ofMinutes(59));
    assertThat(monitor.evaluate()).isZero();
    clock.advance(Duration.ofMinutes(1));
    assertThat(monitor.evaluate()).isEqualTo(1);
    assertThat(ports.eventsOf(DecisionEvent.CircuitBreakerChanged.class))
        .extracting(DecisionEvent.CircuitBreakerChanged::change)
        .containsExactly(MccCircuitBreaker.Change.OPENED, MccCircuitBreaker.Change.CLOSED);
  }

  @Test
  @Tag("FR-03-07")
  void monitorsLosingTheCompareAndSetRecordNothing() {
    CircuitBreakerPort.Key key = new CircuitBreakerPort.Key(INSTITUTION, "6051");
    ports.windowCounts.put(key, new MccCircuitBreaker.WindowCounts(100, 6));
    InMemoryPorts racing =
        new InMemoryPorts() {
          @Override
          public boolean compareAndSet(
              CircuitBreakerPort.Key k, CircuitBreakerState expected, CircuitBreakerState next) {
            return false;
          }
        };
    racing.windowCounts.putAll(ports.windowCounts);
    assertThat(new CircuitBreakerMonitor(racing, racing, racing, clock).evaluate()).isZero();
    assertThat(racing.events).isEmpty();
  }
}
