package io.github.mariusbayizere.fraudshield.decision.adapter.grpc;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.Timestamp;
import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.AccountContext;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.GeoPoint;
import io.github.mariusbayizere.fraudshield.contracts.scoring.v1.ScoreRequest;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionService;
import io.github.mariusbayizere.fraudshield.decision.application.DecisionSettings;
import io.github.mariusbayizere.fraudshield.decision.application.IngestDecision;
import io.github.mariusbayizere.fraudshield.decision.application.port.DecisionMetrics;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScorerUnavailableException;
import io.github.mariusbayizere.fraudshield.decision.application.port.ScoringPort;
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
import java.util.Map;
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
    return scorer.score(t, List.of(Money.of("50000", CurrencyCode.RWF)));
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
    // ADR 0033: the scorer reads the account context from the feature store; field 2 is reserved.
    assertThat(ScoreRequest.getDescriptor().findFieldByNumber(2)).isNull();
    assertThat(request.getUnknownFields().asMap()).isEmpty();
    assertThat(request.getConfiguredLimitsList())
        .singleElement()
        .satisfies(m -> assertThat(m.getAmount()).isEqualTo("50000"));
    assertThat(request.getTransaction().getTransactionTimestamp().getSeconds())
        .isEqualTo(NOW.getEpochSecond());

    Transaction agent =
        Fixtures.transaction(UUID.randomUUID(), Fixtures.ACCOUNT, "100", Channel.AGENT_BANKING);
    scorer.score(agent, List.of());
    ScoreRequest full = remote.requests.get(1);
    assertThat(full.getTransaction().getAgentToken()).isEqualTo(agent.agentToken());
    assertThat(full.getTransaction().getAccountToken()).isEqualTo(Fixtures.ACCOUNT);
    assertThat(full.getUnknownFields().asMap()).isEmpty();
  }

  @Test
  @Tag("NFR-REL-02")
  void theScorersDegradedFeatureStoreIsCarriedIntoTheRecord() {
    assertThat(score(Fixtures.transaction("100")).record().featureStoreDegraded()).isFalse();
    remote.tamper = b -> b.setFeatureStoreDegraded(true);
    assertThat(score(Fixtures.transaction("100")).record().featureStoreDegraded()).isTrue();
  }

  @Test
  @Tag("FR-02-10")
  void theContextTheScorerReadIsKeptAsItWasReturned() {
    assertThat(score(Fixtures.transaction("100")).record().accountContext())
        .as("a scorer that returns no context")
        .isNull();
    remote.tamper =
        b ->
            b.setAccountContext(
                AccountContext.newBuilder()
                    .setTxCount1H(3)
                    .setTxCount24H(0xFFFF_FFFF)
                    .setAmountSum24HRwf("125000.50")
                    .setMeanHourlyCount30D(Double.NaN)
                    .setDaysSinceSimSwap(0)
                    .setLastTransactionAt(Timestamp.newBuilder().setSeconds(1_790_000_000L))
                    .setLastLocation(GeoPoint.newBuilder().setLatitude(-1.95).setLongitude(30.06))
                    .addCountriesSeen("RW")
                    .addCountriesSeen("UG"));
    Map<String, Object> context = score(Fixtures.transaction("100")).record().accountContext();
    assertThat(context)
        .containsEntry("tx_count_1h", 3L)
        .containsEntry("tx_count_24h", 4_294_967_295L)
        .containsEntry("amount_sum_24h_rwf", "125000.50")
        .containsEntry("mean_hourly_count_30d", null)
        .containsEntry("tx_count_7d", 0L)
        .containsEntry("days_since_sim_swap", 0L)
        .containsEntry("last_transaction_at", "2026-09-21T14:13:20Z")
        .containsEntry("last_location", Map.of("latitude", -1.95, "longitude", 30.06))
        .containsEntry("countries_seen", List.of("RW", "UG"))
        .as("a field with presence that was not set means no source, not zero")
        .doesNotContainKeys("kyc_tier", "account_age_days", "device", "agent");
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
      assertThatThrownBy(() -> impatient.score(Fixtures.transaction("1"), List.of()))
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
      assertThat(fallback.modelVersion()).isEqualTo("fallback-rules-2");
    }
    assertThat(scorer.state()).isEqualTo(CircuitBreaker.State.OPEN);
  }
}
