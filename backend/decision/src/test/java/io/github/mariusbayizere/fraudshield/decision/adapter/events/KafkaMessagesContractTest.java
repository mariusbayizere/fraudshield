package io.github.mariusbayizere.fraudshield.decision.adapter.events;

import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.INSTITUTION;
import static io.github.mariusbayizere.fraudshield.decision.testing.Fixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.common.money.CurrencyCode;
import io.github.mariusbayizere.fraudshield.common.money.Money;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.testing.ContractSchemas;
import io.github.mariusbayizere.fraudshield.decision.testing.FactScenarios;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every fact the decision path records renders to envelopes that satisfy the frozen contracts in
 * {@code contracts/kafka}, and survives the spool codec unchanged.
 */
@Tag("FR-01-01")
@Tag("D-14")
@Tag("D-15")
class KafkaMessagesContractTest {

  private static final ContractSchemas CONTRACTS = new ContractSchemas();
  private final KafkaMessages renderer = new KafkaMessages("fraudshield-api", 3);

  private static List<DecisionEvent> everyKindOfFact() {
    return FactScenarios.everyKindOfFact();
  }

  @Test
  void everyRenderedEnvelopeSatisfiesItsTopicContract() {
    List<DecisionEvent> events = everyKindOfFact();
    Set<Class<?>> kinds = events.stream().map(Object::getClass).collect(Collectors.toSet());
    assertThat(kinds).hasSize(8);
    List<KafkaMessage> messages = renderer.render(events);
    Set<String> topics = messages.stream().map(KafkaMessage::topic).collect(Collectors.toSet());
    assertThat(topics)
        .containsExactlyInAnyOrder(
            "fs.transactions.raw",
            "fs.transactions.scored",
            "fs.decisions.final",
            "fs.audit.events",
            "fs.alerts.high",
            "fs.alerts.medium",
            "fs.alerts.anomaly",
            "fs.notifications.customer",
            "fs.notifications.staff",
            "fs.labels");
    for (KafkaMessage message : messages) {
      assertThat(CONTRACTS.errors(message.topic(), message.value()))
          .as(message.topic() + " " + new String(message.value(), StandardCharsets.UTF_8))
          .isEmpty();
    }
  }

  @Test
  void theContractCheckHasTeeth() {
    KafkaMessage decision =
        renderer.render(everyKindOfFact()).stream()
            .filter(m -> m.topic().equals("fs.decisions.final"))
            .findFirst()
            .orElseThrow();
    String broken =
        new String(decision.value(), StandardCharsets.UTF_8)
            .replace("\"decision_sequence\":1", "\"decision_sequence\":0");
    assertThat(CONTRACTS.errors("fs.decisions.final", broken.getBytes(StandardCharsets.UTF_8)))
        .isNotEmpty();
    assertThat(CONTRACTS.errors("fs.labels", decision.value())).isNotEmpty();
  }

  @Test
  void republishingYieldsTheSameEventIds() {
    List<DecisionEvent> events = everyKindOfFact();
    List<String> first = renderer.render(events).stream().map(KafkaMessage::eventId).toList();
    List<String> again =
        renderer.render(FactCodec.decode(FactCodec.encode(events))).stream()
            .map(KafkaMessage::eventId)
            .toList();
    assertThat(again).isEqualTo(first).doesNotHaveDuplicates();
  }

  @Test
  void theCodecRoundTripsEveryFact() {
    List<DecisionEvent> events = everyKindOfFact();
    List<DecisionEvent> decoded = FactCodec.decode(FactCodec.encode(events));
    assertThat(decoded).hasSameSizeAs(events);
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i) instanceof DecisionEvent.TransactionDecided original) {
        DecisionEvent.TransactionDecided copy = (DecisionEvent.TransactionDecided) decoded.get(i);
        assertThat(copy.transaction()).isEqualTo(original.transaction());
        assertThat(copy.outcome()).isEqualTo(original.outcome());
        assertThat(copy.state()).isEqualTo(original.state());
        assertThat(copy.scoring()).isEqualTo(original.scoring());
        assertThat(copy.requestFingerprint()).isEqualTo(original.requestFingerprint());
        assertThat(copy.firstSeenForAccount()).isEqualTo(original.firstSeenForAccount());
      } else {
        assertThat(decoded.get(i)).isEqualTo(events.get(i));
      }
    }
  }

  @Test
  void decimalsUseTheContractFormAndExpectedLossIsProbabilityTimesAmount() {
    assertThat(FactCodec.decimal(new BigDecimal("15000.0000"))).isEqualTo("15000");
    assertThat(FactCodec.decimal(new BigDecimal("0.5000"))).isEqualTo("0.5");
    assertThat(FactCodec.decimal(BigDecimal.ZERO)).isEqualTo("0");
    Transaction t = Fixtures.transaction("20000");
    DecisionEvent.AlertRaised alert =
        new DecisionEvent.AlertRaised(
            UUID.randomUUID(),
            INSTITUTION,
            t,
            io.github.mariusbayizere.fraudshield.decision.domain.AlertTier.HIGH,
            0.9,
            null,
            UUID.randomUUID(),
            null,
            List.of(),
            NOW);
    assertThat(KafkaMessages.expectedLoss(alert)).isEqualByComparingTo("18000");
    assertThat(Money.of("1", CurrencyCode.RWF)).isNotNull();
    assertThatThrownBy(() -> new KafkaMessages("Bad Name", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new KafkaMessages("fraudshield-api", 64))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> FactCodec.decode("[{\"type\":\"unknown\"}]".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Map.of()).isEmpty();
  }
}
