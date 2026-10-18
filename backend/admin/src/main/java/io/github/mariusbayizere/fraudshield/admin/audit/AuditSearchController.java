package io.github.mariusbayizere.fraudshield.admin.audit;

import io.github.mariusbayizere.fraudshield.admin.support.Cursor;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.jwt.AccessTokens;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.web.ContractOperation;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * Searches the append-only audit log of the caller's institution by user, event type, time range
 * and entity (FR-06-06, D-32), newest first, through the tenant view {@code v_audit_events}.
 */
@RestController
@Validated
@RequestMapping("/api/v1/admin/audit-events")
public class AuditSearchController {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;

  /**
   * Creates the controller.
   *
   * @param tenants tenant transactions
   * @param jdbc JDBC template of the application role
   */
  public AuditSearchController(TenantTransactions tenants, JdbcTemplate jdbc) {
    this.tenants = Objects.requireNonNull(tenants);
    this.jdbc = Objects.requireNonNull(jdbc);
  }

  /**
   * Searches audit events.
   *
   * @param jwt caller
   * @param userId acting user
   * @param eventType event type
   * @param from earliest event time (inclusive)
   * @param to latest event time (exclusive)
   * @param entityType entity type
   * @param entityId entity ID
   * @param cursor cursor
   * @param limit page size
   * @return a page of events
   */
  @ContractOperation("searchAuditEvents")
  @PreAuthorize("hasAnyRole('ADMIN')")
  @GetMapping
  public Map<String, Object> search(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(name = "user_id", required = false) UUID userId,
      @RequestParam(name = "event_type", required = false) AuditEventType eventType,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(name = "entity_type", required = false) @Size(max = 64) String entityType,
      @RequestParam(name = "entity_id", required = false) @Size(max = 128) String entityId,
      @RequestParam(required = false) @Size(max = 512) String cursor,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    StringBuilder sql =
        new StringBuilder(
            """
            SELECT id, event_type, action, entity_type, entity_id, event_at, user_id, user_first_name,
              user_last_name, user_role, before_value::text AS before_json,
              after_value::text AS after_json, host(ip_address) AS ip, row_hash
            FROM v_audit_events WHERE true
            """);
    List<Object> args = new ArrayList<>();
    if (userId != null) {
      sql.append(" AND user_id = ?");
      args.add(userId);
    }
    if (eventType != null) {
      sql.append(" AND event_type = ?");
      args.add(eventType.name());
    }
    if (from != null) {
      sql.append(" AND event_at >= ?");
      args.add(Timestamp.from(timestamp("from", from)));
    }
    if (to != null) {
      sql.append(" AND event_at < ?");
      args.add(Timestamp.from(timestamp("to", to)));
    }
    if (entityType != null) {
      sql.append(" AND entity_type = ?");
      args.add(entityType);
    }
    if (entityId != null) {
      sql.append(" AND entity_id = ?");
      args.add(entityId);
    }
    Cursor after = Cursor.decode(cursor);
    if (after != null) {
      sql.append(" AND (event_at, id) < (?, ?)");
      args.add(Timestamp.from(after.at()));
      args.add(after.id());
    }
    sql.append(" ORDER BY event_at DESC, id DESC LIMIT ?");
    args.add(limit + 1);
    StaffClaims claims = AccessTokens.claims(jwt);
    List<Row> rows =
        tenants.inTenant(
            claims.institutionId(),
            () -> jdbc.query(sql.toString(), AuditSearchController::row, args.toArray()));
    Map<String, Object> page = new LinkedHashMap<>();
    List<Row> items = rows.size() > limit ? rows.subList(0, limit) : rows;
    page.put("items", items.stream().map(Row::view).toList());
    page.put(
        "next_cursor",
        rows.size() > limit
            ? new Cursor(items.getLast().eventAt(), items.getLast().id()).encode()
            : null);
    return page;
  }

  private static Instant timestamp(String field, String value) {
    try {
      if (!value.endsWith("Z")) {
        throw new DateTimeParseException("not UTC", value, 0);
      }
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      throw ProblemException.validation(
          field, "invalid_format", "Use an RFC 3339 UTC timestamp ending in Z");
    }
  }

  private record Row(UUID id, Instant eventAt, Map<String, Object> view) {}

  private static Row row(ResultSet row, int index) throws SQLException {
    UUID id = row.getObject("id", UUID.class);
    Instant eventAt = row.getTimestamp("event_at").toInstant();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("event_id", id);
    view.put("event_type", row.getString("event_type"));
    view.put("action", row.getString("action"));
    view.put("entity_type", row.getString("entity_type"));
    view.put("entity_id", row.getString("entity_id"));
    view.put("event_at", eventAt.toString());
    UUID actor = row.getObject("user_id", UUID.class);
    if (actor == null) {
      view.put("actor", null);
    } else {
      Map<String, Object> reference = new LinkedHashMap<>();
      reference.put("user_id", actor);
      reference.put("first_name", row.getString("user_first_name"));
      reference.put("last_name", row.getString("user_last_name"));
      reference.put("role", row.getString("user_role"));
      view.put("actor", reference);
    }
    view.put("before_value", json(row.getString("before_json")));
    view.put("after_value", json(row.getString("after_json")));
    view.put("ip_address", row.getString("ip"));
    view.put("row_hash", HexFormat.of().formatHex(row.getBytes("row_hash")));
    return new Row(id, eventAt, view);
  }

  private static Object json(String text) {
    return text == null ? null : JSON.readValue(text, Map.class);
  }
}
