package io.github.mariusbayizere.fraudshield.audit.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.testing.AppDatabase;
import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Audit events relayed from {@code fs.audit.events} (D-32, M7 merge review finding 2): appended
 * once however often Kafka delivers them, to the producer's writer partition, with the named staff
 * member's name and role.
 */
@Tag("requires-docker")
@Tag("D-32")
@Tag("FR-06-06")
class RelayedAuditAppenderTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static TestDatabase db;
  private static AppDatabase app;
  private static RelayedAuditAppender appender;
  private static UUID bank;
  private static UUID analyst;

  @BeforeAll
  static void createDatabase() throws SQLException {
    db = TestDatabase.create();
    app = AppDatabase.of(db, "fs_app");
    bank = db.createInstitution("relay-bank");
    analyst = UUID.randomUUID();
    try (Connection superuser = db.superuser();
        PreparedStatement insert =
            superuser.prepareStatement(
                """
                INSERT INTO fraudshield.users (id, institution_id, first_name, last_name, email,
                  password_hash, role, status, employee_id, department)
                VALUES (?, ?, 'Aline', 'Mukamana', 'aline.relay@bank.rw',
                  '$2a$12$abcdefghijklmnopqrstuuv0123456789abcdefghijklmnopqrst', 'ANALYST',
                  'ACTIVE', 'RELAY0001', 'FRAUD_OPERATIONS')
                """)) {
      insert.setObject(1, analyst);
      insert.setObject(2, bank);
      insert.executeUpdate();
    }
    appender = new RelayedAuditAppender(app.tenants(), app.jdbc());
  }

  /** The payload the decision engine's {@code KafkaMessages} renders for a hold. */
  private static ObjectNode held(UUID transaction, int partition) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("event_type", "TRANSACTION_DECISION");
    payload.put("action", "HELD");
    payload.put("entity_type", "transaction");
    payload.put("entity_id", transaction.toString());
    payload.put("event_at", "2026-10-17T09:00:00Z");
    payload.put("writer_partition", partition);
    payload.putNull("actor_user_id");
    payload.putNull("actor_role");
    payload.putNull("before_value");
    payload.putNull("after_value");
    return payload;
  }

  private static List<Map<String, Object>> rows(String entityId) {
    return app.tenants()
        .inTenant(
            bank,
            () ->
                app.jdbc()
                    .queryForList(
                        """
                        SELECT writer_partition, event_type, action, user_first_name, user_role,
                          after_value->>'decision' AS decision
                        FROM v_audit_events WHERE entity_id = ?
                        """,
                        entityId));
  }

  @Test
  void redeliveredEventIsAppendedOnceToItsProducersPartition() {
    UUID transaction = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    assertThat(appender.append(eventId, bank, held(transaction, 17))).isTrue();
    assertThat(appender.append(eventId, bank, held(transaction, 17)))
        .as("Kafka's second delivery of the same envelope")
        .isFalse();
    List<Map<String, Object>> rows = rows(transaction.toString());
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst())
        .containsEntry("writer_partition", 17)
        .containsEntry("event_type", "TRANSACTION_DECISION")
        .containsEntry("action", "HELD");
  }

  @Test
  void analystsDecisionCarriesTheirNameRoleAndValues() {
    UUID transaction = UUID.randomUUID();
    ObjectNode payload = held(transaction, 4);
    payload.put("event_type", "ANALYST_DECISION");
    payload.put("action", "HOLD_APPROVED");
    payload.put("actor_user_id", analyst.toString());
    payload.putObject("after_value").put("decision", "APPROVE");
    assertThat(appender.append(UUID.randomUUID(), bank, payload)).isTrue();
    assertThat(rows(transaction.toString()).getFirst())
        .containsEntry("user_first_name", "Aline")
        .containsEntry("user_role", "ANALYST")
        .containsEntry("decision", "APPROVE");
  }

  @Test
  void payloadThatIsNotAnAuditEventIsRefusedBeforeAnythingIsWritten() {
    UUID transaction = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    ObjectNode unknownType = held(transaction, 0);
    unknownType.put("event_type", "LOGIN");
    ObjectNode badPartition = held(transaction, 64);
    for (ObjectNode payload : List.of(unknownType, badPartition)) {
      assertThatThrownBy(() -> appender.append(eventId, bank, payload))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(appender.append(eventId, bank, held(transaction, 0)))
        .as("no receipt was left behind by the refused attempts")
        .isTrue();
  }
}
