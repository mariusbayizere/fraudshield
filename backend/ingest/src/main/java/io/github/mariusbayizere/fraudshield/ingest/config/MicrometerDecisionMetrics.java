package io.github.mariusbayizere.fraudshield.ingest.config;

import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The decision metrics of E.10: {@code fs_decisions_total{tier,decision,channel,fallback}}, {@code
 * fs_decision_latency_seconds} (5 ms to 200 ms buckets), {@code
 * fs_alert_timeout_release_total{channel}} (the D-10 alert watches its rate) and the hold-lateness
 * distribution for D-18's tolerance.
 */
public final class MicrometerDecisionMetrics implements DecisionMetrics {

  private final MeterRegistry registry;
  private final Timer latency;
  private final DistributionSummary lateness;

  /**
   * Creates the metrics.
   *
   * @param registry the registry
   */
  public MicrometerDecisionMetrics(MeterRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.latency =
        Timer.builder("fs_decision_latency")
            .description("Server-side decision latency (C.2)")
            .serviceLevelObjectives(
                Duration.ofMillis(5),
                Duration.ofMillis(10),
                Duration.ofMillis(20),
                Duration.ofMillis(30),
                Duration.ofMillis(40),
                Duration.ofMillis(50),
                Duration.ofMillis(75),
                Duration.ofMillis(100),
                Duration.ofMillis(200))
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    this.lateness =
        DistributionSummary.builder("fs_hold_timeout_lateness_ms")
            .description("How late holds were timed out beyond the 500 ms tolerance (D-18)")
            .register(registry);
  }

  @Override
  public void decided(Transaction transaction, DecisionOutcome outcome, long latencyNanos) {
    latency.record(latencyNanos, TimeUnit.NANOSECONDS);
    Counter.builder("fs_decisions_total")
        .tag("tier", outcome.tier().name())
        .tag("decision", outcome.decision().name())
        .tag("channel", transaction.channel().name())
        .tag("fallback", Boolean.toString(outcome.fallback()))
        .register(registry)
        .increment();
  }

  @Override
  public void timeoutRelease(String channel) {
    Counter.builder("fs_alert_timeout_release_total")
        .tag("channel", channel)
        .register(registry)
        .increment();
  }

  @Override
  public void holdLateness(long lateMillis) {
    lateness.record(lateMillis);
  }
}
