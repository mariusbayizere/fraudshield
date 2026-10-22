package io.github.mariusbayizere.fraudshield.analyst.realtime;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a Kafka record into the body of a {@code RealtimeAlertEvent} and its routing (ADR 0115
 * points 5, 6.1 and 6.2). It validates the envelope and payload against the frozen schemas, takes
 * the institution from the envelope only, and never touches a database: a staff name it needs is
 * left for the name resolver.
 */
final class AlertFrames {

  /** The topics the fan-out reads: three of raised alerts and one of later changes. */
  static final List<String> TOPICS =
      List.of("fs.alerts.high", "fs.alerts.medium", "fs.alerts.anomaly", ChangePublisher.TOPIC);

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final List<String> SCHEMAS =
      List.of("common", "envelope", "alert", "alert-change");

  private final Schema envelope;
  private final Schema alert;
  private final Schema change;

  /**
   * A routed record: what is still needed before the frame can be written.
   *
   * @param institutionId the envelope's institution
   * @param visibility who may receive it
   * @param body the frame body, with {@code alert.assigned_analyst} still to resolve when {@code
   *     assignee} is set
   * @param assignee the assigned analyst whose name the frame needs, or null
   * @param createdMillis the record's CreateTime
   */
  record Routed(
      UUID institutionId,
      Visibility visibility,
      Map<String, Object> body,
      UUID assignee,
      long createdMillis) {}

  /** Loads the schemas packaged from {@code contracts/kafka/schemas}. */
  AlertFrames() {
    Map<String, String> schemas = new HashMap<>();
    for (String name : SCHEMAS) {
      String path = "/fraudshield/kafka-schemas/" + name + ".schema.json";
      try (InputStream in = AlertFrames.class.getResourceAsStream(path)) {
        if (in == null) {
          throw new IllegalStateException("missing packaged schema " + path);
        }
        schemas.put(
            "urn:fraudshield:kafka:" + name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    SchemaRegistry registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder
                    .schemas(schemas)
                    .schemaRegistryConfig(
                        SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    this.envelope = registry.getSchema(SchemaLocation.of("urn:fraudshield:kafka:envelope"));
    this.alert = registry.getSchema(SchemaLocation.of("urn:fraudshield:kafka:alert"));
    this.change = registry.getSchema(SchemaLocation.of("urn:fraudshield:kafka:alert-change"));
  }

  /**
   * Validates and maps one record; empty when it is invalid (counted by the caller, never
   * dead-lettered: this reader owns nothing).
   *
   * @param topic the topic
   * @param value the envelope
   * @param createdMillis the record's CreateTime
   * @return the routed record
   */
  Optional<Routed> route(String topic, byte[] value, long createdMillis) {
    JsonNode node;
    try {
      node = JSON.readTree(value);
    } catch (RuntimeException unreadable) {
      return Optional.empty();
    }
    if (node == null || !envelope.validate(node).isEmpty()) {
      return Optional.empty();
    }
    boolean changed = ChangePublisher.TOPIC.equals(topic);
    String type = node.path("event_type").asString();
    if (!(changed ? "alert.changed" : "alert.created").equals(type)
        || node.path("schema_version").asInt() != 1) {
      return Optional.empty();
    }
    JsonNode payload = node.get("payload");
    if (!(changed ? change : alert).validate(payload).isEmpty()) {
      return Optional.empty();
    }
    UUID institution = UUID.fromString(node.get("institution_id").asString());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("event_id", node.get("event_id").asString());
    body.put("occurred_at", node.get("occurred_at").asString());
    if (changed) {
      return Optional.of(changed(institution, payload, body, createdMillis));
    }
    body.put("change", "RAISED");
    body.put("actor_user_id", null);
    body.put("provisional", !payload.has("merchant") || !payload.has("top_reasons"));
    body.put("alert", raised(payload));
    return Optional.of(new Routed(institution, Visibility.EVERYONE, body, null, createdMillis));
  }

  private static Routed changed(
      UUID institution, JsonNode payload, Map<String, Object> body, long createdMillis) {
    Map<String, Object> summary = JSON.convertValue(payload.get("alert"), MAP);
    final String assigned = (String) summary.remove("assigned_analyst_id");
    summary.put("assigned_analyst", null);
    body.put("change", payload.get("change").asString());
    body.put("actor_user_id", text(payload.get("actor_user_id")));
    body.put("provisional", false);
    body.put("alert", summary);
    String from = (String) summary.get("escalated_from_alert_id");
    String target = text(payload.get("escalation_target_role"));
    String by = text(payload.get("escalated_by_user_id"));
    Visibility visibility =
        from == null
            ? Visibility.EVERYONE
            : new Visibility(
                UUID.fromString(from),
                target == null ? null : StaffRole.valueOf(target),
                by == null ? null : UUID.fromString(by));
    return new Routed(
        institution,
        visibility,
        body,
        assigned == null ? null : UUID.fromString(assigned),
        createdMillis);
  }

  /**
   * A raised alert's card (point 6.1): version 0, PENDING, not escalated, unassigned, no masked
   * account; merchant and top reasons as carried, or null and empty when absent.
   */
  private static Map<String, Object> raised(JsonNode p) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("alert_id", p.get("alert_id").asString());
    view.put("version", 0);
    view.put("transaction_id", p.get("transaction_id").asString());
    view.put("tier", p.get("tier").asString());
    view.put("status", "PENDING");
    view.put("fraud_probability", p.get("fraud_probability").asDouble());
    if (p.hasNonNull("expected_loss_rwf")) {
      view.put("expected_loss_rwf", decimal(p.get("expected_loss_rwf")));
    }
    Map<String, Object> money = new LinkedHashMap<>();
    money.put("amount", decimal(p.get("amount").get("amount")));
    money.put("currency", p.get("amount").get("currency").asString());
    view.put("amount", money);
    view.put("channel", p.get("channel").asString());
    view.put("masked_account", null);
    view.put("merchant", text(p.get("merchant")));
    view.put("transaction_timestamp", timestamp(p.get("transaction_timestamp")));
    view.put(
        "top_reasons",
        p.has("top_reasons")
            ? JSON.convertValue(p.get("top_reasons"), new TypeReference<List<Object>>() {})
            : List.of());
    view.put("review_deadline_at", timestamp(p.get("review_deadline_at")));
    view.put("escalated_from_alert_id", null);
    // The read's rule: null under the rule-based fallback, whose AlertRaised omits it (6.1).
    view.put(
        "anomaly_score", p.hasNonNull("anomaly_score") ? p.get("anomaly_score").asDouble() : null);
    view.put("assigned_analyst", null);
    return view;
  }

  /**
   * Serialises a frame body once, for every recipient (point 5).
   *
   * @param body the body
   * @return its JSON
   */
  static String json(Map<String, Object> body) {
    return JSON.writeValueAsString(body);
  }

  private static String decimal(JsonNode value) {
    return Views.decimal(new BigDecimal(value.asString()));
  }

  private static String timestamp(JsonNode value) {
    return value == null || value.isNull()
        ? null
        : Views.timestamp(Instant.parse(value.asString()));
  }

  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.asString();
  }
}
