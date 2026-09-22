package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreRequest;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.IngestDecision;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScorerUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
import io.github.mariusbayizere.fraudshield.decision.domain.AccountHistory;
import io.github.mariusbayizere.fraudshield.decision.domain.Decision;
import io.github.mariusbayizere.fraudshield.decision.domain.ReasonCodes;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.InMemoryPorts;
import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.ScorerDouble;
import io.github.mariusbayizere.fraudshield.rules.dsl.FieldValue;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("FR-02-01")
@Tag("NFR-REL-01")
class GrpcScorerTest {

  private final ScorerDouble remote = new ScorerDouble();
  private Server server;
  private GrpcScorer scorer;
  private String channelName;

  @BeforeEach
  void start() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).addService(remote).build().start();
    scorer =
        new GrpcScorer(
            InProcessChannelBuilder.forName(name).directExecutor().build(), Duration.ofSeconds(2));
    channelName = name;
    assertThat(scorer.warmUp(Duration.ofSeconds(5))).contains("fs-ensemble-test-double");
  }

  @AfterEach
  void stop() {
    scorer.close();
    server.shutdownNow();
  }

  private ScoringPort.Scored score(Transaction t) {
    return scorer.score(
        t, AccountHistory.empty(true), List.of(Money.of("50000", CurrencyCode.RWF)));
  }

  @Test
  @Tag("FR-01-04")
  @Tag("D-04")
  void theRequestFollowsTheContract() {
    Transaction ussd =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "15000.5", Channel.USSD);
    score(ussd);
    ScoreRequest request = remote.requests.getFirst();
    assertThat(request.getTransaction().getTransactionId())
        .isEqualTo(ussd.transactionId().toString());
    assertThat(request.getTransaction().getAmount().getAmount()).isEqualTo("15000.5");
    assertThat(request.getTransaction().getAmountRwf()).isEqualTo("15000.5");
    assertThat(request.getTransaction().getChannel().name()).isEqualTo("CHANNEL_USSD");
    assertThat(request.getTransaction().hasDeviceToken()).as("USSD has no fingerprint").isFalse();
    assertThat(request.getTransaction().hasAgentToken()).isFalse();
    assertThat(request.getContext().hasDevice()).isFalse();
    assertThat(request.getContext().getMeanHourlyCount30D()).as("PB-37 fail closed").isNaN();
    assertThat(request.getContext().hasKycTier()).isFalse();
    assertThat(request.getConfiguredLimitsList())
        .singleElement()
        .satisfies(m -> assertThat(m.getAmount()).isEqualTo("50000"));
    assertThat(request.getTransaction().getTransactionTimestamp().getSeconds())
        .isEqualTo(NOW.getEpochSecond());

    Transaction agent =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "100", Channel.AGENT_BANKING);
    scorer.score(
        agent,
        new AccountHistory(
            1,
            1,
            1,
            1,
            java.math.BigDecimal.ONE,
            java.math.BigDecimal.ONE,
            1,
            0.5,
            java.math.BigDecimal.ONE,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ONE,
            1,
            new io.github.mariusbayizere.fraudshield.decision.domain.GeoPoint(1, 2),
            NOW,
            new io.github.mariusbayizere.fraudshield.decision.domain.GeoPoint(1, 2),
            List.of("RW"),
            400,
            2,
            3,
            0,
            false,
            20,
            1,
            0,
            1,
            new AccountHistory.DeviceHistory(false, 1, 0, 30),
            new AccountHistory.AgentHistory(0.5, 3, 2, 1.5),
            0.01,
            0,
            1.2),
        List.of());
    ScoreRequest full = remote.requests.get(1);
    assertThat(full.getTransaction().getAgentToken()).isEqualTo(agent.agentToken());
    assertThat(full.getContext().getAgent().getCashoutCount1H()).isEqualTo(3);
    assertThat(full.getContext().getDevice().getDeviceAgeDays()).isEqualTo(30);
    assertThat(full.getContext().getAccountAgeDays()).isEqualTo(400);
    assertThat(full.getContext().getDaysSinceSimSwap()).isEqualTo(3);
    assertThat(full.getContext().getAmountMad90DRwf()).isEqualTo("0");
  }

  @Test
  @Tag("FR-02-10")
  void theResultMapsFeaturesMissingValuesAndShap() {
    remote.score = r -> 0.9;
    ScoringPort.Scored scored = score(Fixtures.transaction("100"));
    assertThat(scored.scoring().ensembleScore()).isEqualTo(0.9);
    assertThat(scored.scoring().modelVersion()).isEqualTo("fs-ensemble-test-double");
    assertThat(scored.scoring().features()).hasSize(44);
    assertThat(scored.scoring().features().get("device_age_days")).isEqualTo(FieldValue.MISSING);
    assertThat(scored.scoring().features().get("channel"))
        .isEqualTo(FieldValue.category("MOBILE_MONEY"));
    assertThat(scored.scoring().topContributions())
        .singleElement()
        .satisfies(c -> assertThat(c.increasesRisk()).isTrue());
    assertThat(scored.record().featureVector()).containsEntry("device_age_days", null).hasSize(44);
    assertThat(scored.record().shapAll()).hasSize(44);
    assertThat(scored.record().shapTop5().getFirst()).containsEntry("direction", "INCREASES_RISK");
    assertThat(score(Fixtures.transaction("100")).record().shapAll()).isNotNull();
    remote.score = r -> 0.3;
    assertThat(score(Fixtures.transaction("100")).record().shapAll()).isNull();
  }

  @Test
  @Tag("FR-02-10")
  void resultsThatBreakTheContractAreNotDecidedOn() {
    remote.tamper = b -> b.removeFeatureVector("kyc_tier");
    assertThatThrownBy(() -> score(Fixtures.transaction("1")))
        .isInstanceOf(ScorerUnavailableException.class);
    remote.tamper = b -> b.setEnsembleScore(1.5);
    assertThatThrownBy(() -> score(Fixtures.transaction("1")))
        .isInstanceOf(ScorerUnavailableException.class);
    remote.tamper = b -> b.setModelVersion("");
    assertThatThrownBy(() -> score(Fixtures.transaction("1")))
        .isInstanceOf(ScorerUnavailableException.class);
    remote.tamper = b -> b.setTransactionId(UUID.randomUUID().toString());
    assertThatThrownBy(() -> score(Fixtures.transaction("1")))
        .isInstanceOf(ScorerUnavailableException.class);
  }

  @Test
  void slowScorersMissTheirDeadline() {
    try (GrpcScorer impatient =
        new GrpcScorer(
            InProcessChannelBuilder.forName(channelName).directExecutor().build(),
            Duration.ofMillis(100))) {
      impatient.warmUp(Duration.ofSeconds(5));
      remote.delayMillis = 500;
      long start = System.nanoTime();
      assertThatThrownBy(
              () ->
                  impatient.score(Fixtures.transaction("1"), AccountHistory.empty(true), List.of()))
          .isInstanceOf(ScorerUnavailableException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(450));
    }
  }

  @Test
  void theCircuitOpensWithinFiveSecondsAndClosesAfterRecovery() {
    remote.failure = Status.UNAVAILABLE;
    long start = System.nanoTime();
    while (scorer.state() != CircuitBreaker.State.OPEN) {
      assertThatThrownBy(() -> score(Fixtures.transaction("1")))
          .isInstanceOf(ScorerUnavailableException.class);
    }
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
    int callsWhenOpened = remote.calls.get();
    assertThatThrownBy(() -> score(Fixtures.transaction("1")))
        .isInstanceOf(ScorerUnavailableException.class)
        .hasMessageContaining("circuit is open");
    assertThat(remote.calls.get())
        .as("an open circuit does not call the scorer")
        .isEqualTo(callsWhenOpened);

    remote.failure = null;
    await()
        .atMost(Duration.ofSeconds(8))
        .pollInterval(Duration.ofMillis(250))
        .until(
            () -> {
              try {
                score(Fixtures.transaction("1"));
              } catch (ScorerUnavailableException stillOpen) {
                return false;
              }
              return true;
            });
    for (int i = 0; i < 5; i++) {
      score(Fixtures.transaction("1"));
    }
    assertThat(scorer.state()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  @Tag("FR-03-01")
  void decisionsContinueOnTheFallbackWhileTheScorerIsDown() {
    InMemoryPorts ports = new InMemoryPorts();
    DecisionService service =
        new DecisionService(
            ports,
            ports,
            scorer,
            ports,
            ports,
            ports,
            ports,
            ports,
            ports,
            DecisionMetrics.NONE,
            DecisionSettings.DEFAULTS,
            new MutableClock(NOW));
    remote.score = r -> 0.95;
    IngestDecision modelled =
        service.decide(Fixtures.transaction("100"), new byte[32], System.nanoTime());
    assertThat(modelled.decision()).isEqualTo(Decision.DECLINE);
    assertThat(modelled.mlUnavailableFallback()).isFalse();
    remote.failure = Status.UNAVAILABLE;
    for (int i = 0; i < 30; i++) {
      IngestDecision fallback =
          service.decide(Fixtures.transaction("100"), new byte[32], System.nanoTime());
      assertThat(fallback.mlUnavailableFallback()).isTrue();
      assertThat(fallback.reasonCodes()).contains(ReasonCodes.ML_UNAVAILABLE);
      assertThat(fallback.modelVersion()).isEqualTo("fallback-rules-1");
    }
    assertThat(scorer.state()).isEqualTo(CircuitBreaker.State.OPEN);
  }
}
