package io.github.mariusbayizere.fraudshield.audit.jdbc;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import java.sql.Timestamp;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends audit events to {@code audit_events} in the caller's transaction (FR-06-06, D-32).
 *
 * <p>The database assigns {@code seq}, {@code prev_hash}, {@code row_hash} and {@code recorded_at}
 * in a trigger running with the owner's rights, so this class cannot forge a chain position. The
 * tenant guard rejects a row for any institution other than the transaction's.
 */
public final class JdbcAuditLog implements AuditLog {

  private static final String INSERT =
      """
      INSERT INTO audit_events (institution_id, writer_partition, event_type, action, entity_type,
        entity_id, user_id, user_first_name, user_last_name, user_role, before_value, after_value,
        ip_address, user_agent, correlation_id, event_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?, ?, ?)
      """;
  private static final short MAX_PARTITION = 63;

  private final JdbcTemplate jdbc;
  private final short writerPartition;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * Creates the writer.
   *
   * @param jdbc JDBC template of the application data source
   * @param writerPartition this instance's chain partition, 0-63; instances running at the same
   *     time should use different partitions to avoid contention on one chain head
   */
  public JdbcAuditLog(JdbcTemplate jdbc, short writerPartition) {
    if (writerPartition < 0 || writerPartition > MAX_PARTITION) {
      throw new IllegalArgumentException("writer partition must be 0-63");
    }
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.writerPartition = writerPartition;
  }

  @Override
  public void record(AuditEvent event) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "audit events are written in the transaction of the change they record");
    }
    AuditActor actor = event.actorIfAny().orElse(null);
    jdbc.update(
        INSERT,
        event.institutionId(),
        writerPartition,
        event.type().name(),
        event.action(),
        event.entityType(),
        event.entityId(),
        actor == null ? null : actor.userId(),
        actor == null ? null : actor.firstName(),
        actor == null ? null : actor.lastName(),
        actor == null ? null : actor.role().name(),
        toJson(event.before()),
        toJson(event.after()),
        event.ipAddress(),
        event.userAgent(),
        event.correlationId(),
        Timestamp.from(event.eventAt()));
  }

  private String toJson(Map<String, Object> values) {
    return values == null ? null : json.writeValueAsString(values);
  }
}
