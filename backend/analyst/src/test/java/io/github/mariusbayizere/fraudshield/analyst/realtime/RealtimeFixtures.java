package io.github.mariusbayizere.fraudshield.analyst.realtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Kafka records for the fan-out's tests, built from the contract's own examples. */
final class RealtimeFixtures {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path EXAMPLES =
      Path.of("..", "..", "contracts", "kafka", "examples").toAbsolutePath().normalize();

  private RealtimeFixtures() {}

  static ObjectNode example(String topic) {
    try {
      return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(topic + ".json")));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A raised HIGH alert, with or without ADR 0115's card fields. */
  static byte[] raised(UUID institution, boolean cardFields) {
    ObjectNode envelope = example("fs.alerts.high");
    envelope.put("event_id", UUID.randomUUID().toString());
    envelope.put("institution_id", institution.toString());
    ObjectNode payload = (ObjectNode) envelope.get("payload");
    payload.put("alert_id", UUID.randomUUID().toString());
    if (cardFields) {
      payload.putNull("merchant");
      payload.putArray("top_reasons");
    }
    return JSON.writeValueAsBytes(envelope);
  }

  /** The new entry of an escalation to SENIOR_ANALYST, by {@code escalator}. */
  static byte[] escalationCreated(UUID institution, UUID escalator) {
    ObjectNode envelope = example("fs.alerts.changes");
    envelope.put("event_id", UUID.randomUUID().toString());
    envelope.put("institution_id", institution.toString());
    ObjectNode payload = (ObjectNode) envelope.get("payload");
    UUID entry = UUID.randomUUID();
    payload.put("alert_id", entry.toString());
    payload.put("version", 0);
    payload.put("change", "ESCALATION_CREATED");
    payload.put("actor_user_id", escalator.toString());
    payload.put("escalation_target_role", "SENIOR_ANALYST");
    payload.put("escalated_by_user_id", escalator.toString());
    ObjectNode alert = (ObjectNode) payload.get("alert");
    alert.put("alert_id", entry.toString());
    alert.put("version", 0);
    alert.put("status", "PENDING");
    alert.put("escalated_from_alert_id", UUID.randomUUID().toString());
    alert.putNull("assigned_analyst_id");
    return JSON.writeValueAsBytes(envelope);
  }

  /** A change whose entry is assigned to {@code assignee}. */
  static byte[] assigned(UUID institution, UUID assignee) {
    ObjectNode envelope = example("fs.alerts.changes");
    envelope.put("event_id", UUID.randomUUID().toString());
    envelope.put("institution_id", institution.toString());
    ObjectNode payload = (ObjectNode) envelope.get("payload");
    payload.put("change", "ASSIGNED");
    ((ObjectNode) payload.get("alert")).put("assigned_analyst_id", assignee.toString());
    return JSON.writeValueAsBytes(envelope);
  }
}
