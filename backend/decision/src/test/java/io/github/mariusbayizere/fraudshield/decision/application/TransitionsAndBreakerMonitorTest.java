package io.github.mariusbayizere.fraudshield.decision.application;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class TransitionsAndBreakerMonitorTest {

  private final InMemoryPorts ports = new InMemoryPorts();
  private final MutableClock clock = new MutableClock(NOW);

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
