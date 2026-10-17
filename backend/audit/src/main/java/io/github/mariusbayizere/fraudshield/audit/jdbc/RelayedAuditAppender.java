package io.github.mariusbayizere.fraudshield.audit.jdbc;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends audit events that another component published rather than wrote (D-32, ADR 0012). The
 * decision engine (M6) publishes its audit events to {@code fs.audit.events}, and the audit
 * consumer hands each one here, so decisions, holds and blocks join the same hash chain, audit
 * search and {@code fraudshield audit verify} as staff actions.
 *
 * <p>Kafka delivers at least once. The envelope's event id goes into {@code audit_event_receipts}
 * in the same transaction as the audit row, so a redelivered event is appended once, and a
 * rolled-back append leaves no receipt. The row goes to the writer partition its producer chose
 * (0-63, carried in the payload). An event that names a staff member carries their name and role as
 * they stand when it is appended, as every audit row stores them (D-32).
 */
public final class RelayedAuditAppender {

  private static final String RECEIPT =
      """
      INSERT INTO audit_event_receipts (event_id, institution_id) VALUES (?, ?)
      ON CONFLICT (event_id) DO NOTHING
      """;
  private static final String ACTOR = "SELECT first_name, last_name, role FROM users WHERE id = ?";
  private static final int PARTITIONS = 64;
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final JdbcAuditLog[] writers = new JdbcAuditLog[PARTITIONS];
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * Creates the appender.
   *
   * @param tenants tenant transactions
   * @param jdbc JDBC template of the application data source
   */
  public RelayedAuditAppender(TenantTransactions tenants, JdbcTemplate jdbc) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    for (short partition = 0; partition < PARTITIONS; partition++) {
      writers[partition] = new JdbcAuditLog(jdbc, partition);
    }
  }

  /**
   * Appends one relayed event, unless it was appended before.
   *
   * @param eventId the envelope's event id
   * @param institutionId the envelope's institution
   * @param payload the envelope's payload ({@code contracts/kafka/schemas/audit-event.schema.json})
   * @return true if the event was appended now, false if it had been already
   * @throws IllegalArgumentException if the payload is not an audit event, checked before any
   *     database access
   * @throws IllegalStateException if the staff member it names does not exist in the institution
   */
  public boolean append(UUID eventId, UUID institutionId, JsonNode payload) {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(institutionId, "institutionId");
    Relayed relayed = parse(institutionId, payload);
    return tenants.inTenant(
        institutionId,
        () -> {
          if (jdbc.update(RECEIPT, eventId, institutionId) == 0) {
            return false;
          }
          AuditEvent.Builder event =
              AuditEvent.of(institutionId, relayed.type(), relayed.action())
                  .entity(relayed.entityType(), relayed.entityId())
                  .before(relayed.before())
                  .after(relayed.after());
          relayed.actorId().ifPresent(id -> event.actor(actor(id)));
          writers[relayed.partition()].record(event.at(relayed.eventAt()));
          return true;
        });
  }

  private AuditActor actor(UUID userId) {
    return jdbc
        .query(
            ACTOR,
            (row, i) ->
                new AuditActor(
                    userId,
                    row.getString(1),
                    row.getString(2),
                    StaffRole.valueOf(row.getString(3))),
            userId)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("the event names an unknown staff member " + userId));
  }

  private record Relayed(
      AuditEventType type,
      String action,
      String entityType,
      String entityId,
      Optional<UUID> actorId,
      Map<String, Object> before,
      Map<String, Object> after,
      Instant eventAt,
      int partition) {}

  private Relayed parse(UUID institutionId, JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("the payload is not an object");
    }
    try {
      int partition = payload.path("writer_partition").asInt(-1);
      if (partition < 0 || partition >= PARTITIONS) {
        throw new IllegalArgumentException("writer_partition is not 0-63");
      }
      Relayed relayed =
          new Relayed(
              AuditEventType.valueOf(text(payload, "event_type")),
              text(payload, "action"),
              text(payload, "entity_type"),
              text(payload, "entity_id"),
              optionalText(payload, "actor_user_id").map(UUID::fromString),
              values(payload.get("before_value")),
              values(payload.get("after_value")),
              Instant.parse(text(payload, "event_at")),
              partition);
      // The record's own checks (action shape, field lengths), before any database access.
      AuditEvent.of(institutionId, relayed.type(), relayed.action())
          .entity(relayed.entityType(), relayed.entityId())
          .at(relayed.eventAt());
      return relayed;
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("event_at is not an instant", e);
    }
  }

  private static String text(JsonNode payload, String field) {
    return optionalText(payload, field)
        .orElseThrow(() -> new IllegalArgumentException(field + " is missing"));
  }

  private static Optional<String> optionalText(JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (!node.isString()) {
      throw new IllegalArgumentException(field + " is not a string");
    }
    return Optional.of(node.asString());
  }

  private Map<String, Object> values(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isObject()) {
      throw new IllegalArgumentException("before_value and after_value must be objects");
    }
    return json.convertValue(node, MAP);
  }
}
