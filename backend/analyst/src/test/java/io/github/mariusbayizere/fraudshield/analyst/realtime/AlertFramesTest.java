package io.github.mariusbayizere.fraudshield.analyst.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Mapping and validating records (ADR 0115 points 4, 5, 6.1 and 6.2). */
class AlertFramesTest {

  private static final UUID BANK = UUID.randomUUID();
  private final AlertFrames frames = new AlertFrames();

  @Test
  @Tag("FR-04-02")
  void raisedAlertIsPendingVersionZeroCard() {
    AlertFrames.Routed r =
        frames.route("fs.alerts.high", RealtimeFixtures.raised(BANK, true), 7).orElseThrow();
    assertThat(r.institutionId()).isEqualTo(BANK);
    assertThat(r.visibility()).isEqualTo(Visibility.EVERYONE);
    assertThat(r.body()).containsEntry("change", "RAISED").containsEntry("provisional", false);
    @SuppressWarnings("unchecked")
    Map<String, Object> alert = (Map<String, Object>) r.body().get("alert");
    assertThat(alert)
        .containsEntry("version", 0)
        .containsEntry("status", "PENDING")
        .containsEntry("escalated_from_alert_id", null)
        .containsEntry("assigned_analyst", null)
        .containsEntry("masked_account", null)
        .containsEntry("merchant", null)
        .containsEntry("expected_loss_rwf", "13650");
    assertThat(r.createdMillis()).isEqualTo(7);
  }

  @Test
  @Tag("FR-04-02")
  void recordWithoutTheCardFieldsIsProvisional() {
    AlertFrames.Routed r =
        frames.route("fs.alerts.high", RealtimeFixtures.raised(BANK, false), 1).orElseThrow();
    assertThat(r.body()).containsEntry("provisional", true);
  }

  @Test
  @Tag("FR-04-02")
  void invalidRecordsAreDropped() {
    assertThat(frames.route("fs.alerts.high", "not json".getBytes(StandardCharsets.UTF_8), 1))
        .isEmpty();
    ObjectNode wrongType = RealtimeFixtures.example("fs.alerts.high");
    wrongType.put("event_type", "alert.changed");
    assertThat(frames.route("fs.alerts.high", bytes(wrongType), 1)).isEmpty();
    ObjectNode noInstitution = RealtimeFixtures.example("fs.alerts.changes");
    noInstitution.remove("institution_id");
    assertThat(frames.route(ChangePublisher.TOPIC, bytes(noInstitution), 1)).isEmpty();
    ObjectNode named = RealtimeFixtures.example("fs.alerts.changes");
    ((ObjectNode) named.get("payload").get("alert")).put("assigned_analyst_name", "A Person");
    assertThat(frames.route(ChangePublisher.TOPIC, bytes(named), 1))
        .as("no names on Kafka")
        .isEmpty();
  }

  @Test
  @Tag("FR-04-10")
  void anEscalatedChangeCarriesItsRouting() {
    UUID escalator = UUID.randomUUID();
    AlertFrames.Routed r =
        frames
            .route(ChangePublisher.TOPIC, RealtimeFixtures.escalationCreated(BANK, escalator), 1)
            .orElseThrow();
    assertThat(r.visibility().targetRole()).isEqualTo(StaffRole.SENIOR_ANALYST);
    assertThat(r.visibility().escalatedBy()).isEqualTo(escalator);
    assertThat(r.assignee()).isNull();
    assertThat(r.body()).containsEntry("actor_user_id", escalator.toString());
  }

  @Test
  @Tag("FR-04-02")
  void anAssigneeIsLeftForTheResolver() {
    UUID assignee = UUID.randomUUID();
    AlertFrames.Routed r =
        frames
            .route(ChangePublisher.TOPIC, RealtimeFixtures.assigned(BANK, assignee), 1)
            .orElseThrow();
    assertThat(r.assignee()).isEqualTo(assignee);
    @SuppressWarnings("unchecked")
    Map<String, Object> alert = (Map<String, Object>) r.body().get("alert");
    assertThat(alert).doesNotContainKey("assigned_analyst_id");
  }

  private static byte[] bytes(ObjectNode node) {
    return JsonMapper.builder().build().writeValueAsBytes(node);
  }
}
