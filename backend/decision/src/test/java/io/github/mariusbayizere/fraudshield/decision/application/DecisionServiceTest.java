package io.github.mariusbayizere.fraudshield.decision.application;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.transaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.RecorderUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.domain.AlertTier;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionOutcome;
import io.github.mariusbayizere.fraudshield.decision.domain.ReasonCodes;
import io.github.mariusbayizere.fraudshield.decision.domain.RiskTier;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.rules.dsl.CompiledRule;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleSet;
import io.github.mariusbayizere.fraudshield.rules.dsl.TierOverride;
import io.github.mariusbayizere.fraudshield.rules.dsl.Truth;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class DecisionServiceTest {

  private static final byte[] FINGERPRINT = new byte[32];

  private InMemoryPorts ports;
  private MutableClock clock;
  private DecisionService service;

  @BeforeEach
  void setUp() {
    ports = new InMemoryPorts();
    clock = new MutableClock(NOW);
    service =
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
  }

  private IngestDecision decide(Transaction transaction) {
    return service.decide(transaction, FINGERPRINT, System.nanoTime());
  }

  @Test
  @Tag("FR-03-03")
  void lowIsApprovedAndRecordedWithoutSideEffects() {
    IngestDecision decision = decide(transaction("15000"));
    assertThat(decision.decision()).isEqualTo(Decision.APPROVE);
    assertThat(decision.reviewDeadlineAt()).isNull();
    assertThat(ports.eventsOf(DecisionEvent.TransactionDecided.class)).hasSize(1);
    assertThat(ports.events).hasSize(1);
    assertThat(ports.latest).hasSize(1);
    assertThat(ports.counted).hasSize(1);
  }

  @Test
  @Tag("FR-05-05")
  void rulesReadTheFeatureValuesTheScorerReturned() {
    ports.rules =
        new RuleSet(
            1,
            List.of(
                new CompiledRule(
                    UUID.randomUUID(),
                    1,
                    TierOverride.HIGH,
                    subject ->
                        subject.value("tx_count_60s") instanceof FieldValue.Number n
                            ? Truth.of(n.value().intValue() >= 5)
                            : Truth.UNKNOWN)));
    ports.features = Map.of("tx_count_60s", FieldValue.of(7.0));
    assertThat(decide(transaction("100")).decision()).isEqualTo(Decision.DECLINE);
    ports.features = Map.of("tx_count_60s", FieldValue.of(2.0));
    assertThat(decide(transaction("100")).decision()).isEqualTo(Decision.APPROVE);
    // Without the scorer there are no features: the rule is UNKNOWN and does not fire.
    ports.scores = t -> Optional.empty();
    ports.features = Map.of("tx_count_60s", FieldValue.of(7.0));
    assertThat(decide(transaction("100")).decision()).isEqualTo(Decision.APPROVE);
  }

  @Test
  @Tag("NFR-REL-02")
  void scorersWithoutTheirFeatureStoreAreCountedAndRecorded() {
    int[] degraded = new int[1];
    DecisionMetrics metrics =
        new DecisionMetrics() {
          @Override
          public void decided(Transaction t, DecisionOutcome outcome, long nanos) {}

          @Override
          public void timeoutRelease(String channel) {}

          @Override
          public void holdLateness(long lateMillis) {}

          @Override
          public void featureStoreDegraded() {
            degraded[0]++;
          }
        };
    DecisionService counting =
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
            metrics,
            DecisionSettings.DEFAULTS,
            clock);
    ports.featureStoreDegraded = true;
    counting.decide(transaction("100"), FINGERPRINT, System.nanoTime());
    assertThat(degraded[0]).isOne();
    assertThat(
            ports
                .eventsOf(DecisionEvent.TransactionDecided.class)
                .getFirst()
                .scoring()
                .featureStoreDegraded())
        .isTrue();
  }

  @Test
  @Tag("FR-03-01")
  void highIsDeclinedAutoBlockedAndTheCustomerIsNotified() {
    ports.scores = t -> Optional.of(0.95);
    IngestDecision decision = decide(transaction("15000"));
    assertThat(decision.decision()).isEqualTo(Decision.DECLINE);
    assertThat(decision.riskTier()).isEqualTo(RiskTier.HIGH);
    assertThat(ports.eventsOf(DecisionEvent.AlertRaised.class))
        .singleElement()
        .extracting(DecisionEvent.AlertRaised::tier)
        .isEqualTo(AlertTier.HIGH);
    DecisionEvent.AutoBlocked block = ports.eventsOf(DecisionEvent.AutoBlocked.class).getFirst();
    assertThat(ports.eventsOf(DecisionEvent.CustomerNotificationRequested.class))
        .singleElement()
        .extracting(DecisionEvent.CustomerNotificationRequested::autoBlockEventId)
        .isEqualTo(block.autoBlockEventId());
    assertThat(ports.counted.get(new CircuitBreakerPort.Key(Fixtures.INSTITUTION, "4829")))
        .containsExactly(1, 1);
  }

  @Test
  @Tag("FR-03-06")
  void theThirdHighInAnHourFreezesAndLaterTransactionsAreDeclinedAsFrozen() {
    ports.scores = t -> Optional.of(0.95);
    decide(transaction("100"));
    clock.advance(Duration.ofMinutes(10));
    decide(transaction("100"));
    assertThat(ports.eventsOf(DecisionEvent.AccountFrozen.class)).isEmpty();
    clock.advance(Duration.ofMinutes(10));
    decide(transaction("100"));
    assertThat(ports.eventsOf(DecisionEvent.AccountFrozen.class))
        .singleElement()
        .extracting(DecisionEvent.AccountFrozen::highDecisions)
        .isEqualTo(3);

    ports.scores = t -> Optional.of(0.01);
    IngestDecision frozen = decide(transaction("100"));
    assertThat(frozen.decision()).isEqualTo(Decision.DECLINE);
    assertThat(frozen.reasonCodes()).containsExactly(ReasonCodes.ACCOUNT_FROZEN);
  }

  @Test
  @Tag("FR-03-02")
  void mediumIsHeldAndItsDeadlineScheduled() {
    ports.scores = t -> Optional.of(0.7);
    Transaction transaction = transaction("15000");
    IngestDecision decision = decide(transaction);
    assertThat(decision.decision()).isEqualTo(Decision.HOLD);
    assertThat(decision.reviewDeadlineAt()).isEqualTo(NOW.plusSeconds(30));
    assertThat(ports.holds.get(transaction.transactionId()).deadline())
        .isEqualTo(NOW.plusSeconds(30));
    assertThat(ports.eventsOf(DecisionEvent.AlertRaised.class).getFirst().reviewDeadlineAt())
        .isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  @Tag("NFR-REL-01")
  void scorerFailureFallsBackToRulesAndSaysSo() {
    ports.scores = t -> Optional.empty();
    IngestDecision decision = decide(transaction("15000"));
    assertThat(decision.mlUnavailableFallback()).isTrue();
    assertThat(decision.modelVersion()).isEqualTo("fallback-rules-2");
    assertThat(decision.reasonCodes()).containsExactly(ReasonCodes.ML_UNAVAILABLE);
    DecisionEvent.ScoringRecord record =
        ports.eventsOf(DecisionEvent.TransactionDecided.class).getFirst().scoring();
    assertThat(record.fallback()).isTrue();
    assertThat(record.ensembleScore()).isZero();

    IngestDecision held = decide(transaction("2500000"));
    assertThat(held.decision()).isEqualTo(Decision.HOLD);
    assertThat(ports.eventsOf(DecisionEvent.AlertRaised.class).getFirst().fraudProbability())
        .isEqualTo(0.60);
    assertThat(ports.eventsOf(DecisionEvent.AlertRaised.class).getFirst().anomalyScore()).isNull();
  }

  @Test
  @Tag("FR-03-07")
  void anOpenBreakerHoldsOtherwiseApprovedTransactions() {
    ports.breakerStates.put(
        new CircuitBreakerPort.Key(Fixtures.INSTITUTION, "4829"),
        new CircuitBreakerState(true, NOW, NOW));
    IngestDecision decision = decide(transaction("100"));
    assertThat(decision.decision()).isEqualTo(Decision.HOLD);
    assertThat(decision.reasonCodes()).first().isEqualTo(ReasonCodes.MCC_CIRCUIT_BREAKER);
  }

  @Test
  @Tag("D-10")
  void anomalyOnlyTransactionsGoToTheAnomalyQueue() {
    ports.anomaly = 0.999;
    IngestDecision decision = decide(transaction("100"));
    assertThat(decision.decision()).isEqualTo(Decision.APPROVE);
    assertThat(ports.eventsOf(DecisionEvent.AlertRaised.class))
        .singleElement()
        .extracting(DecisionEvent.AlertRaised::tier)
        .isEqualTo(AlertTier.ANOMALY);
  }

  @Test
  @Tag("D-15")
  void nothingIsVisibleWhenTheDecisionCannotBeMadeDurable() {
    ports.recorderFull = true;
    ports.scores = t -> Optional.of(0.7);
    assertThatThrownBy(() -> decide(transaction("100")))
        .isInstanceOf(RecorderUnavailableException.class);
    assertThat(ports.latest).isEmpty();
    assertThat(ports.holds).isEmpty();
    assertThat(ports.counted).isEmpty();
  }

  @Test
  @Tag("FR-01-04")
  void everyChannelIsDecided() {
    for (Channel channel : Channel.values()) {
      IngestDecision decision =
          decide(Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "100", channel));
      assertThat(decision.decision()).as(channel.name()).isEqualTo(Decision.APPROVE);
    }
  }

  @Test
  void fallbackNominalScoresAreTheTierEdges() {
    assertThat(DecisionService.nominalScore(RiskTier.LOW, Fixtures.threshold())).isZero();
    assertThat(DecisionService.nominalScore(RiskTier.HIGH, Fixtures.threshold())).isEqualTo(0.85);
  }
}
