package io.github.mariusbayizere.fraudshield.analyst.assignments;

import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.analyst.support.Views;
import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Alert assignment (ADR 0116): {@code assignAlert} and {@code listAssignableStaff}. Assignment is
 * advisory: it records who is expected to work an entry and never changes who may see or decide it.
 * It never touches the transaction's decision owner or an analyst's claim.
 *
 * <p>{@code assignAlert} is one runner transaction that takes, in order, levels 1, 2, 6 and 7 of
 * the decision-owner contract's lock order (§3): the entry {@code FOR UPDATE}, the entry's advisory
 * lock, the users it reads {@code FOR SHARE} (and the foreign-key share locks of its writes), and
 * the audit chain head. It never takes levels 3 to 5.
 */
public final class AlertAssignments {

  /** The bound of {@code AssignableStaffList.items}; more matching users answer 422. */
  static final int MAX_STAFF = 1000;

  private static final String LOCK_ENTRY =
      """
      SELECT status, version, escalation_target_role, assigned_analyst_id
      FROM alert_queue_entries WHERE id = ? FOR UPDATE
      """;

  private static final String ENTRY_LOCK =
      "SELECT pg_advisory_xact_lock(hashtextextended('alert_entry:' || ?::text, 0))";

  private static final String BOUNDS =
      "SELECT set_config('lock_timeout', '2s', true), set_config('statement_timeout', '5s', true)";

  private static final String BY_KEY =
      """
      SELECT alert_queue_entry_id, assigned_by, request_fingerprint FROM alert_assignments
      WHERE idempotency_key = ?
      """;

  private static final String USER_FOR_SHARE =
      "SELECT role, status FROM users WHERE id = ? FOR SHARE";

  private static final String INSERT_HISTORY =
      """
      INSERT INTO alert_assignments (institution_id, alert_queue_entry_id, previous_assignee_id,
      assignee_id, assigned_by, idempotency_key, request_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?)
      RETURNING assigned_at
      """;

  private static final String UPDATE_ENTRY =
      """
      UPDATE alert_queue_entries SET assigned_analyst_id = ?, assigned_at = ?, version = version + 1
      WHERE id = ?
      """;

  private static final String ACTOR = "SELECT first_name, last_name FROM users WHERE id = ?";

  private static final String ENTRY_TARGET =
      "SELECT escalation_target_role FROM alert_queue_entries WHERE id = ?";

  private static final String RANK =
      "(CASE role WHEN 'ANALYST' THEN 1 WHEN 'SENIOR_ANALYST' THEN 2 WHEN 'RISK_OFFICER' THEN 3"
          + " END)";

  private static final String ASSIGNABLE =
      "SELECT id, first_name, last_name, role FROM users WHERE status IN ('ACTIVE', 'LOCKED')"
          + " AND role IN ('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER') AND "
          + RANK
          + " BETWEEN ? AND ? ORDER BY last_name, first_name, id LIMIT ?";

  private static final String FILTERABLE =
      """
      SELECT id, first_name, last_name, role, status FROM users
      WHERE role IN ('ANALYST', 'SENIOR_ANALYST', 'RISK_OFFICER')
      ORDER BY last_name, first_name, id LIMIT ?
      """;

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final AuditLog audit;
  private final AlertQueries alerts;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param tenants the runner, which retries 40001 and 40P01
   * @param jdbc JDBC template of the same data source
   * @param audit the audit log
   * @param alerts alert reads, for the contract's bodies
   * @param clock clock
   */
  public AlertAssignments(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      AuditLog audit,
      AlertQueries alerts,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The caller.
   *
   * @param userId staff user
   * @param institutionId institution
   * @param role role
   * @param context the request's audit context
   */
  public record Caller(UUID userId, UUID institutionId, StaffRole role, RequestContext context) {}

  /** {@code listAssignableStaff}'s two modes. */
  public enum Purpose {
    /** The users the caller may assign (rule A2). */
    ASSIGN,
    /** Every decision-role user, with status, for the analyst filter. */
    FILTER
  }

  private record Entry(String status, long version, StaffRole target, UUID assignee) {

    boolean open() {
      return "PENDING".equals(status) || "IN_REVIEW".equals(status);
    }
  }

  private record Keyed(UUID entryId, UUID assignedBy, byte[] fingerprint) {}

  private record Person(StaffRole role, String status) {}

  // ---------------------------------------------------------------------------------- assignAlert

  /**
   * {@code assignAlert}. Answers 200 with the entry's summary as it now stands.
   *
   * @param caller the caller, a SENIOR_ANALYST or RISK_OFFICER
   * @param alertId the entry
   * @param key the Idempotency-Key
   * @param assignee the user to assign, or null to return the entry to the pool
   * @param alertVersion the version the caller saw
   * @return the contract's {@code AlertSummary}
   */
  public Map<String, Object> assign(
      Caller caller, UUID alertId, UUID key, UUID assignee, long alertVersion) {
    byte[] fingerprint =
        fingerprint(
            "assign",
            alertId.toString(),
            assignee == null ? "" : assignee.toString(),
            Long.toString(alertVersion));
    try {
      return assignOnce(caller, alertId, key, assignee, alertVersion, fingerprint);
    } catch (DuplicateKeyException sameKeyElsewhere) {
      // ADR 0116 point 5, as D1(b): the same key used concurrently; re-run once and answer by the
      // stored row.
      return assignOnce(caller, alertId, key, assignee, alertVersion, fingerprint);
    }
  }

  private Map<String, Object> assignOnce(
      Caller caller, UUID alertId, UUID key, UUID assignee, long alertVersion, byte[] fingerprint) {
    return inTenant(
        caller.institutionId(),
        () -> {
          // 1. 404, with lock levels 1 and 2.
          final Entry entry = lockEntry(alertId);
          // 2. Idempotency, scoped to this operation.
          Optional<Keyed> keyed = byKey(key);
          if (keyed.isPresent()) {
            Keyed k = keyed.get();
            if (k.entryId().equals(alertId)
                && caller.userId().equals(k.assignedBy())
                && Arrays.equals(k.fingerprint(), fingerprint)) {
              return current(alertId);
            }
            throw ProblemException.of(
                "idempotency-conflict",
                409,
                "Idempotency conflict",
                "This Idempotency-Key was used with a different request");
          }
          // 3. A1.
          if (!AssignmentRules.reaches(caller.role(), entry.target())) {
            throw ProblemException.of(
                "escalation-level-required",
                403,
                "Escalation level required",
                "This escalated alert needs a higher role");
          }
          // 4. A3, on the current assignee's current role and status (lock level 6).
          if (entry.assignee() != null) {
            Optional<Person> holder = person(entry.assignee());
            if (holder.isPresent()
                && AssignmentRules.heldAgainst(
                    caller.role(), holder.get().role(), holder.get().status())) {
              throw ProblemException.of(
                  "assignment-held-by-higher-role",
                  403,
                  "Assignment held by a higher role",
                  "This alert is assigned to someone whose role ranks above yours");
            }
          }
          // 5. A4, then A5.
          if (!entry.open() || entry.version() != alertVersion) {
            throw stale(alertId);
          }
          // 6. A2 (lock level 6: an administrator's concurrent change commits first or waits).
          if (assignee != null) {
            Optional<Person> candidate = person(assignee);
            if (candidate.isEmpty()
                || !AssignmentRules.eligible(
                    caller.role(),
                    entry.target(),
                    candidate.get().role(),
                    candidate.get().status())) {
              throw ProblemException.validation(
                  "assignee_user_id",
                  "assignee_not_eligible",
                  "Not a user this alert can be assigned to");
            }
          }
          // 7. A6.
          if (Objects.equals(entry.assignee(), assignee)) {
            throw ProblemException.validation(
                "assignee_user_id", "no_change", "The alert is already assigned so");
          }
          Timestamp at =
              jdbc.queryForObject(
                  INSERT_HISTORY,
                  Timestamp.class,
                  caller.institutionId(),
                  alertId,
                  entry.assignee(),
                  assignee,
                  caller.userId(),
                  key,
                  fingerprint);
          jdbc.update(UPDATE_ENTRY, assignee, assignee == null ? null : at, alertId);
          Map<String, Object> before = new LinkedHashMap<>();
          before.put("assigned_analyst_id", text(entry.assignee()));
          Map<String, Object> after = new LinkedHashMap<>();
          after.put("assigned_analyst_id", text(assignee));
          audit.record(
              AuditEvent.of(
                      caller.institutionId(),
                      AuditEventType.ANALYST_DECISION,
                      assignee == null ? "UNASSIGNED" : "ASSIGNED")
                  .entity("alert_queue_entry", alertId)
                  .actor(actor(caller))
                  .before(before)
                  .after(after)
                  .context(caller.context())
                  .at(clock.instant()));
          return current(alertId);
        });
  }

  // -------------------------------------------------------------------------- listAssignableStaff

  /**
   * {@code listAssignableStaff}. Never returns e-mail; returns status only in the filter mode.
   *
   * @param caller the caller, a SENIOR_ANALYST or RISK_OFFICER
   * @param purpose the mode
   * @param alertId an entry, in the assign mode only, or null
   * @return the contract's {@code AssignableStaffList}
   */
  public Map<String, Object> staff(Caller caller, Purpose purpose, UUID alertId) {
    if (purpose == Purpose.FILTER && alertId != null) {
      throw ProblemException.validation(
          "alert_id", "unsupported_value", "alert_id is not accepted with purpose=filter");
    }
    List<Map<String, Object>> items =
        tenants.inTenant(
            caller.institutionId(),
            () -> {
              if (purpose == Purpose.FILTER) {
                return jdbc.query(FILTERABLE, (row, i) -> staff(row, true), MAX_STAFF + 1);
              }
              StaffRole target = null;
              if (alertId != null) {
                List<String> found =
                    jdbc.query(ENTRY_TARGET, (row, i) -> row.getString(1), alertId);
                if (found.isEmpty()) {
                  throw ProblemException.notFound("alert");
                }
                target = found.getFirst() == null ? null : StaffRole.valueOf(found.getFirst());
              }
              return jdbc.query(
                  ASSIGNABLE,
                  (row, i) -> staff(row, false),
                  AssignmentRules.lowestRank(target),
                  Views.rank(caller.role()),
                  MAX_STAFF + 1);
            });
    if (items.size() > MAX_STAFF) {
      throw ProblemException.of(
          "result-too-large",
          422,
          "Result too large",
          "More than " + MAX_STAFF + " users match; nothing is truncated");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    return body;
  }

  private static Map<String, Object> staff(ResultSet row, boolean withStatus) throws SQLException {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("user_id", row.getObject("id", UUID.class));
    view.put("first_name", row.getString("first_name"));
    view.put("last_name", row.getString("last_name"));
    view.put("role", row.getString("role"));
    if (withStatus) {
      view.put("status", row.getString("status"));
    }
    return view;
  }

  // ---------------------------------------------------------------------------------- helpers

  private Entry lockEntry(UUID alertId) {
    List<Entry> found =
        jdbc.query(
            LOCK_ENTRY,
            (row, i) -> {
              String target = row.getString(3);
              return new Entry(
                  row.getString(1),
                  row.getLong(2),
                  target == null ? null : StaffRole.valueOf(target),
                  row.getObject(4, UUID.class));
            },
            alertId);
    if (found.isEmpty()) {
      throw ProblemException.notFound("alert");
    }
    jdbc.queryForObject(ENTRY_LOCK, Object.class, alertId);
    return found.getFirst();
  }

  private Optional<Keyed> byKey(UUID key) {
    return jdbc
        .query(
            BY_KEY,
            (row, i) ->
                new Keyed(
                    row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getBytes(3)),
            key)
        .stream()
        .findFirst();
  }

  /** A user of the caller's institution, read FOR SHARE; another institution's is not found. */
  private Optional<Person> person(UUID userId) {
    return jdbc
        .query(
            USER_FOR_SHARE,
            (row, i) -> new Person(StaffRole.valueOf(row.getString(1)), row.getString(2)),
            userId)
        .stream()
        .findFirst();
  }

  private Map<String, Object> current(UUID alertId) {
    return alerts.summary(alertId).orElseThrow(() -> ProblemException.notFound("alert"));
  }

  private ProblemException stale(UUID alertId) {
    Optional<Map<String, Object>> current = alerts.summary(alertId);
    ProblemException stale =
        ProblemException.of(
            "stale-alert", 409, "Stale alert", "The alert changed; the current one is attached");
    return current.isPresent() ? stale.with("current", current.get()) : stale;
  }

  /**
   * One runner transaction with the decision-owner contract's §2 bounds. A lock or statement
   * timeout, or a lost connection, answers 503 with Retry-After.
   */
  private <T> T inTenant(UUID institutionId, Supplier<T> work) {
    try {
      return tenants.inTenant(
          institutionId,
          () -> {
            jdbc.queryForObject(BOUNDS, Object.class);
            return work.get();
          });
    } catch (PessimisticLockingFailureException
        | QueryTimeoutException
        | DataAccessResourceFailureException unavailable) {
      throw ProblemException.serviceUnavailable(1);
    }
  }

  private AuditActor actor(Caller caller) {
    Map<String, Object> user = jdbc.queryForMap(ACTOR, caller.userId());
    return new AuditActor(
        caller.userId(),
        (String) user.get("first_name"),
        (String) user.get("last_name"),
        caller.role());
  }

  private static String text(UUID id) {
    return id == null ? null : id.toString();
  }

  static byte[] fingerprint(String... parts) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        sha.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        sha.update((byte) ':');
        sha.update(bytes);
      }
      return sha.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
