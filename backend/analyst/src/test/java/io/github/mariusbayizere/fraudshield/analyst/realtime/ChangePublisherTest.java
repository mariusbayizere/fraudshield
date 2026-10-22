package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.decision.application.port.AlertChangePublisher;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The change publisher (ADR 0115 point 6.2), with the re-read and the sender faked. */
class ChangePublisherTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TenantTransactions tenants = mock(TenantTransactions.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final AlertQueries alerts = mock(AlertQueries.class);
  private final List<byte[]> sent = new CopyOnWriteArrayList<>();

  @SuppressWarnings("unchecked")
  private ChangePublisher publisher(ChangePublisher.Sender sender, int capacity) {
    when(tenants.inTenant(any(), any(Supplier.class)))
        .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
    return new ChangePublisher(
        tenants, jdbc, alerts, sender, "fraudshield-api", capacity, Clock.systemUTC());
  }

  private Map<String, Object> summary(UUID alertId, long version, Map<String, Object> assignee) {
    Map<String, Object> alert =
        JSON.convertValue(
            RealtimeFixtures.example("fs.alerts.changes").get("payload").get("alert"),
            new TypeReference<LinkedHashMap<String, Object>>() {});
    alert.remove("assigned_analyst_id");
    alert.put("alert_id", alertId);
    alert.put("version", version);
    alert.put("transaction_id", UUID.fromString((String) alert.get("transaction_id")));
    alert.put("assigned_analyst", assignee);
    return alert;
  }

  @Test
  @Tag("FR-04-02")
  void changeIsReadAgainAndSentAsValidIdsOnlyEvent() {
    UUID bank = UUID.randomUUID();
    UUID alertId = UUID.randomUUID();
    UUID officer = UUID.randomUUID();
    UUID analyst = UUID.randomUUID();
    when(alerts.summary(alertId))
        .thenReturn(
            Optional.of(
                summary(
                    alertId,
                    3,
                    Map.of(
                        "user_id",
                        analyst,
                        "first_name",
                        "A",
                        "last_name",
                        "B",
                        "role",
                        "ANALYST"))));
    when(jdbc.queryForList(anyString(), eq(alertId))).thenReturn(List.of());
    try (ChangePublisher publisher = publisher((key, value, failed) -> sent.add(value), 10)) {
      publisher.offer(bank, alertId, 3, AlertChangePublisher.Change.OVERRIDDEN, officer);
      await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 1);
    }
    AlertFrames.Routed routed =
        new AlertFrames().route(ChangePublisher.TOPIC, sent.getFirst(), 1).orElseThrow();
    assertThat(routed.institutionId()).isEqualTo(bank);
    assertThat(routed.assignee()).isEqualTo(analyst);
    assertThat(routed.body())
        .containsEntry("event_id", ChangePublisher.eventId(alertId, 3).toString())
        .containsEntry("change", "OVERRIDDEN")
        .containsEntry("actor_user_id", officer.toString());
    assertThat(new String(sent.getFirst(), java.nio.charset.StandardCharsets.UTF_8))
        .as("no staff name on Kafka")
        .doesNotContain("first_name");
  }

  @Test
  @Tag("FR-04-02")
  void theEventFollowsTheVersionReReadNotTheOneOffered() {
    UUID alertId = UUID.randomUUID();
    when(alerts.summary(alertId)).thenReturn(Optional.of(summary(alertId, 5, null)));
    when(jdbc.queryForList(anyString(), eq(alertId))).thenReturn(List.of());
    try (ChangePublisher publisher = publisher((key, value, failed) -> sent.add(value), 10)) {
      publisher.offer(UUID.randomUUID(), alertId, 4, AlertChangePublisher.Change.UNDONE, null);
      await().atMost(Duration.ofSeconds(5)).until(() -> sent.size() == 1);
    }
    assertThat(new String(sent.getFirst(), java.nio.charset.StandardCharsets.UTF_8))
        .contains(ChangePublisher.eventId(alertId, 5).toString())
        .contains("\"assigned_analyst_id\":null");
  }

  @Test
  @Tag("FR-04-02")
  void fullQueueDropsAndFailedReadOrSendIsCounted() {
    UUID alertId = UUID.randomUUID();
    when(alerts.summary(alertId)).thenThrow(new IllegalStateException("database down"));
    try (ChangePublisher publisher = publisher((key, value, failed) -> failed.run(), 1)) {
      for (int i = 0; i < 50; i++) {
        publisher.offer(UUID.randomUUID(), alertId, i, AlertChangePublisher.Change.DECIDED, null);
      }
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> publisher.dropped() + publisher.failed() == 50);
      assertThat(publisher.dropped()).as("the queue holds one").isPositive();
    }
  }

  @Test
  @Tag("FR-04-02")
  void theEventIdIsTheNameUuidOfTheEntryAndVersion() {
    UUID alertId = UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d");
    assertThat(ChangePublisher.eventId(alertId, 1))
        .isEqualTo(
            UUID.nameUUIDFromBytes(
                ("alert_changed|" + alertId + "|1")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isNotEqualTo(ChangePublisher.eventId(alertId, 2));
  }
}
