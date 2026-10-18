package io.github.mariusbayizere.fraudshield.decision.application;

import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.ConfigurationPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.EventRecorder;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates every active MCC's breaker on a short period (every 5 s by default), so a breach opens
 * the breaker well within FR-03-07's 60 seconds and a clean window closes it. State changes are
 * compare-and-set, so concurrent monitors on several instances record each change once.
 */
public final class CircuitBreakerMonitor {

  private final CircuitBreakerPort breakers;
  private final ConfigurationPort configuration;
  private final EventRecorder recorder;
  private final Clock clock;

  /**
   * Creates the monitor.
   *
   * @param breakers breaker counts and states
   * @param configuration breaker settings
   * @param recorder durable events
   * @param clock clock
   */
  public CircuitBreakerMonitor(
      CircuitBreakerPort breakers,
      ConfigurationPort configuration,
      EventRecorder recorder,
      Clock clock) {
    this.breakers = Objects.requireNonNull(breakers, "breakers");
    this.configuration = Objects.requireNonNull(configuration, "configuration");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Evaluates every active breaker once.
   *
   * @return changes recorded by this call
   */
  public int evaluate() {
    Instant now = clock.instant();
    int changes = 0;
    for (CircuitBreakerPort.Key key : breakers.active(now)) {
      ConfigurationPort.BreakerSettings settings =
          configuration.breakerSettings(key.institutionId());
      MccCircuitBreaker.WindowCounts counts =
          breakers.counts(key, (int) settings.settings().window().toMinutes(), now);
      CircuitBreakerState current = breakers.state(key);
      MccCircuitBreaker.Evaluation evaluation =
          MccCircuitBreaker.evaluate(counts, settings.settings(), current, now);
      if (evaluation.state().equals(current)
          || !breakers.compareAndSet(key, current, evaluation.state())) {
        continue;
      }
      if (evaluation.change().isPresent()) {
        recorder.record(
            List.of(
                new DecisionEvent.CircuitBreakerChanged(
                    key.institutionId(),
                    key.merchantCategoryCode(),
                    evaluation.change().get(),
                    counts,
                    settings.version(),
                    now)));
        changes++;
      }
    }
    return changes;
  }
}
