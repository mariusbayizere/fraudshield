package io.github.mariusbayizere.fraudshield.analyst.decisions;

import io.github.mariusbayizere.fraudshield.analyst.alerts.AlertQueries;
import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.support.AfterCommit;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerIds;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerRequest;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * The analyst's four decision operations (decision-owner contract §9, D1, D2, D5, D6). Each is one
 * runner transaction that locks the entry, then its advisory lock, and makes its request to the
 * transaction's owner (§3's lock order). Nothing leaves PostgreSQL before the commit: recording an
 * owner outcome and rescheduling a Redis hold run after it.
 */
public final class AlertDecisions {

  /** W, the escalation review window: ASSUMED, 15 minutes by default (ADR 0111 point 3). */
  public static final Duration DEFAULT_ESCALATION_WINDOW = Duration.ofMinutes(15);

  private static final String CONTEST_TYPE = "decision-contested";

  private static final String LOCK_ENTRY =
      """
      SELECT id, transaction_id, tier, status, version, escalation_target_role, resolution_id
      FROM alert_queue_entries WHERE id = ? FOR UPDATE
      """;

  private static final String ENTRY_LOCK =
      "SELECT pg_advisory_xact_lock(hashtextextended('alert_entry:' || ?::text, 0))";

  private static final String BY_KEY =
      """
      SELECT id, alert_queue_entry_id, analyst_id, decision, request_fingerprint, undo_until,
      escalated_to_alert_id FROM alert_decisions WHERE idempotency_key = ?
      """;

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final DecisionOwner owner;
  private final RequestRecorder recorder;
  private final AuditLog audit;
  private final AlertQueries alerts;
  private final HoldSchedulePort holds;
  private final Duration escalationWindow;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param tenants the runner, which retries 40001 and 40P01
   * @param jdbc JDBC template of the same data source
   * @param dataSource the data source, for the owner's transaction-bound connection
   * @param owner the transaction's owner
   * @param recorder records an owner outcome after the commit
   * @param audit the audit log
   * @param alerts alert reads, for the contract's bodies
   * @param holds the hold schedule, rescheduled after an undo or an escalation; null when the
   *     application has none (the overdue sweep then acts 10 to 15 s after the deadline)
   * @param escalationWindow W
   * @param clock clock
   */
  public AlertDecisions(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      DataSource dataSource,
      DecisionOwner owner,
      RequestRecorder recorder,
      AuditLog audit,
      AlertQueries alerts,
      HoldSchedulePort holds,
      Duration escalationWindow,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.holds = holds;
    this.escalationWindow = Objects.requireNonNull(escalationWindow, "escalationWindow");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The caller, as the contract's operations see it.
   *
   * @param userId staff user
   * @param institutionId institution
   * @param role role
   * @param context the request's audit context
   */
  public record Caller(UUID userId, UUID institutionId, StaffRole role, RequestContext context) {}

  private record Entry(
      UUID id,
      UUID transactionId,
      String tier,
      String status,
      long version,
      String escalationTarget,
      UUID resolutionId) {

    boolean open() {
      return "PENDING".equals(status) || "IN_REVIEW".equals(status);
    }
  }

  private record Keyed(
      UUID id,
      UUID entryId,
      UUID analystId,
      String decision,
      byte[] fingerprint,
      Instant undoUntil,
      UUID escalatedTo) {}

  // ---------------------------------------------------------------------------------- D1: decide

  /**
   * {@code decideAlert} (D1). Answers 202 with the decision's id and its undo window.
   *
   * @param caller the caller
   * @param alertId the entry
   * @param key the Idempotency-Key
   * @param fraud true for CONFIRM_FRAUD
   * @param comment the analyst's comment
   * @param alertVersion the version the analyst saw
   * @return the contract's {@code AlertDecisionAccepted}
   */
  public Map<String, Object> decide(
      Caller caller, UUID alertId, UUID key, boolean fraud, String comment, long alertVersion) {
    String kind = fraud ? "CONFIRM_FRAUD" : "MARK_LEGITIMATE";
    byte[] fingerprint =
        fingerprint("decide", alertId.toString(), kind, comment, Long.toString(alertVersion));
    try {
      return decideOnce(caller, alertId, key, kind, comment, alertVersion, fingerprint);
    } catch (DuplicateKeyException sameKeyElsewhere) {
      // D1(b): the same key used concurrently; re-run once and answer by the stored row.
      return decideOnce(caller, alertId, key, kind, comment, alertVersion, fingerprint);
    }
  }

  private Map<String, Object> decideOnce(
      Caller caller,
      UUID alertId,
      UUID key,
      String kind,
      String comment,
      long alertVersion,
      byte[] fingerprint) {
    return inTenant(
        caller.institutionId(),
        () -> {
          bound();
          Entry entry = lockEntry(alertId);
          Optional<Keyed> keyed = byKey(key);
          if (keyed.isPresent()) {
            Keyed k = keyed.get();
            if (k.entryId().equals(alertId)
                && caller.userId().equals(k.analystId())
                && Arrays.equals(k.fingerprint(), fingerprint)) {
              return accepted(k.id(), k.undoUntil());
            }
            throw idempotencyConflict();
          }
          requireLevel(caller, entry);
          requireCurrent(entry, alertVersion);
          UUID decisionId = UUID.randomUUID();
          DecisionOwner.Result claim =
              submit(
                  caller.institutionId(),
                  entry.transactionId(),
                  new OwnerRequest.ClaimRequest(decisionId, "MEDIUM".equals(entry.tier())));
          if (claim.outcome() != Verdict.Outcome.CLAIMED) {
            throw refusal(claim, entry);
          }
          Instant undoUntil;
          try {
            undoUntil =
                Objects.requireNonNull(
                        jdbc.queryForObject(
                            """
                            INSERT INTO alert_decisions (id, institution_id, alert_queue_entry_id,
                            analyst_id, decision, analyst_comment, idempotency_key, prior_status,
                            alert_version, request_fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            RETURNING undo_until
                            """,
                            Timestamp.class,
                            decisionId,
                            caller.institutionId(),
                            alertId,
                            caller.userId(),
                            kind,
                            comment,
                            key,
                            entry.status(),
                            alertVersion,
                            fingerprint),
                        "undo_until")
                    .toInstant();
          } catch (DuplicateKeyException e) {
            if (e.getMessage() != null && e.getMessage().contains("live analyst decision")) {
              // The transaction is aborted: the problem is built after the rollback (code review
              // 1, minor 9).
              throw new StaleAfterRollback(entry.id());
            }
            throw e;
          }
          jdbc.update(
              "UPDATE alert_queue_entries SET status = ?, version = version + 1 WHERE id = ?",
              "CONFIRM_FRAUD".equals(kind) ? "CONFIRMED_FRAUD" : "MARKED_LEGITIMATE",
              alertId);
          audit.record(
              AuditEvent.of(caller.institutionId(), AuditEventType.ANALYST_DECISION, "DECIDED")
                  .entity("alert_decision", decisionId)
                  .actor(actor(caller))
                  .after(Map.of("alert_id", alertId.toString(), "decision", kind))
                  .context(caller.context())
                  .at(clock.instant()));
          return accepted(decisionId, undoUntil);
        });
  }

  private static Map<String, Object> accepted(UUID decisionId, Instant undoUntil) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision_id", decisionId.toString());
    body.put("state", "PENDING_COMMIT");
    body.put("undo_until", undoUntil.toString());
    return body;
  }

  // ------------------------------------------------------------------------------------ D2: undo

  /**
   * {@code undoAlertDecision} (D2). Answers 204.
   *
   * @param caller the caller
   * @param alertId the entry
   * @param decisionId the decision
   */
  public void undo(Caller caller, UUID alertId, UUID decisionId) {
    Optional<HoldSchedulePort.DueHold> reschedule =
        inTenant(
            caller.institutionId(),
            () -> {
              bound();
              Entry entry = lockEntry(alertId);
              List<Map<String, Object>> found =
                  jdbc.queryForList(
                      """
                      SELECT analyst_id, decision, prior_status FROM alert_decisions WHERE id = ?
                      AND alert_queue_entry_id = ?
                      AND decision IN ('CONFIRM_FRAUD', 'MARK_LEGITIMATE')
                      """,
                      decisionId,
                      alertId);
              if (found.isEmpty()) {
                throw ProblemException.notFound("decision");
              }
              if (!caller.userId().equals(found.getFirst().get("analyst_id"))) {
                throw ProblemException.of(
                    "not-decision-author", 403, "Not the author", "Only its author can undo it");
              }
              if (entry.resolutionId() != null) {
                throw stale(entry.id());
              }
              Integer undone =
                  jdbc.queryForObject(
                      "SELECT count(*) FROM alert_decision_undos WHERE decision_id = ?",
                      Integer.class,
                      decisionId);
              if (undone != null && undone > 0) {
                return heldAt(caller.institutionId(), entry.transactionId());
              }
              try {
                jdbc.update(
                    """
                    INSERT INTO alert_decision_undos (decision_id, institution_id, undone_by)
                    VALUES (?, ?, ?)
                    """,
                    decisionId,
                    caller.institutionId(),
                    caller.userId());
              } catch (DataIntegrityViolationException expired) {
                throw ProblemException.of(
                    "undo-window-expired",
                    409,
                    "Undo window expired",
                    "The decision can no longer be undone");
              }
              DecisionOwner.Result released =
                  submit(
                      caller.institutionId(),
                      entry.transactionId(),
                      new OwnerRequest.Release(decisionId));
              if (released.outcome() != Verdict.Outcome.CLAIMED) {
                // The claim is not this decision's any more: a resolution cleared it, and reopening
                // the entry would leave one nothing can decide (D2(a); code review 1, MAJOR 5).
                throw new StaleAfterRollback(entry.id());
              }
              jdbc.update(
                  "UPDATE alert_queue_entries SET status = ?, version = version + 1 WHERE id = ?",
                  found.getFirst().get("prior_status"),
                  alertId);
              audit.record(
                  AuditEvent.of(caller.institutionId(), AuditEventType.ANALYST_DECISION, "UNDO")
                      .entity("alert_decision", decisionId)
                      .actor(actor(caller))
                      .context(caller.context())
                      .at(clock.instant()));
              return heldAt(caller.institutionId(), entry.transactionId());
            });
    reschedule.ifPresent(this::reschedule);
  }

  // ------------------------------------------------------------------------------ D6: escalate

  /**
   * {@code escalateAlert} (D6). Answers 201 with the new entry's summary.
   *
   * @param caller the caller
   * @param alertId the entry
   * @param key the Idempotency-Key
   * @param target SENIOR_ANALYST or RISK_OFFICER
   * @param reason the reason
   * @param alertVersion the version the caller saw
   * @return the contract's {@code AlertSummary} of the escalated entry
   */
  public Map<String, Object> escalate(
      Caller caller, UUID alertId, UUID key, StaffRole target, String reason, long alertVersion) {
    if (rank(target) <= rank(caller.role())) {
      throw ProblemException.validation(
          "target_role", "escalation_target_not_higher", "Escalate to a higher role");
    }
    byte[] fingerprint =
        fingerprint(
            "escalate", alertId.toString(), target.name(), reason, Long.toString(alertVersion));
    Escalated escalated =
        inTenant(
            caller.institutionId(),
            () -> {
              bound();
              Entry entry = lockEntry(alertId);
              Optional<Keyed> keyed = byKey(key);
              if (keyed.isPresent()) {
                Keyed k = keyed.get();
                if (k.entryId().equals(alertId)
                    && caller.userId().equals(k.analystId())
                    && Arrays.equals(k.fingerprint(), fingerprint)) {
                  return new Escalated(k.escalatedTo(), Optional.empty());
                }
                throw idempotencyConflict();
              }
              requireLevel(caller, entry);
              requireCurrent(entry, alertVersion);
              DecisionOwner.Result result =
                  submit(
                      caller.institutionId(),
                      entry.transactionId(),
                      new OwnerRequest.Escalate(escalationWindow));
              if (result.outcome() != Verdict.Outcome.CLAIMED) {
                throw refusal(result, entry);
              }
              Map<String, Object> owned =
                  jdbc.queryForMap(
                      "SELECT decision, deadline FROM decision_owners WHERE transaction_id = ?",
                      entry.transactionId());
              boolean held = "HOLD".equals(owned.get("decision"));
              UUID escalatedId = UUID.randomUUID();
              jdbc.update(
                  """
                  INSERT INTO alert_queue_entries (id, institution_id, transaction_id,
                  fraud_score_id, tier, fraud_probability, expected_loss_rwf, review_deadline_at,
                  escalated_from_alert_id, escalation_target_role, escalation_reason)
                  SELECT ?, institution_id, transaction_id, fraud_score_id, tier, fraud_probability,
                  expected_loss_rwf, ?, id, ?, ? FROM alert_queue_entries WHERE id = ?
                  """,
                  escalatedId,
                  held ? owned.get("deadline") : null,
                  target.name(),
                  reason,
                  alertId);
              UUID decisionId = UUID.randomUUID();
              jdbc.update(
                  """
                  INSERT INTO alert_decisions (id, institution_id, alert_queue_entry_id,
                  analyst_id, decision, analyst_comment, idempotency_key, prior_status,
                  alert_version, request_fingerprint, escalated_to_alert_id) VALUES (?, ?, ?, ?,
                  'ESCALATE', ?, ?, ?, ?, ?, ?)
                  """,
                  decisionId,
                  caller.institutionId(),
                  alertId,
                  caller.userId(),
                  reason,
                  key,
                  entry.status(),
                  alertVersion,
                  fingerprint,
                  escalatedId);
              jdbc.update(
                  "UPDATE alert_queue_entries SET status = 'ESCALATED', version = version + 1"
                      + " WHERE id = ?",
                  alertId);
              audit.record(
                  AuditEvent.of(
                          caller.institutionId(), AuditEventType.ANALYST_DECISION, "ESCALATED")
                      .entity("alert_decision", decisionId)
                      .actor(actor(caller))
                      .after(
                          Map.of(
                              "alert_id", alertId.toString(),
                              "escalated_alert_id", escalatedId.toString(),
                              "target_role", target.name()))
                      .context(caller.context())
                      .at(clock.instant()));
              return new Escalated(
                  escalatedId,
                  held ? heldAt(caller.institutionId(), entry.transactionId()) : Optional.empty());
            });
    escalated.reschedule().ifPresent(this::reschedule);
    return inTenant(
        caller.institutionId(),
        () ->
            alerts
                .summary(escalated.entryId())
                .orElseThrow(() -> ProblemException.notFound("alert")));
  }

  private record Escalated(UUID entryId, Optional<HoldSchedulePort.DueHold> reschedule) {}

  // ------------------------------------------------------------------------------ D5: override

  /**
   * {@code overrideAlertDecision} (D5). Applied synchronously at the owner; answers 201.
   *
   * @param caller the caller, a risk officer
   * @param alertId the entry
   * @param key the Idempotency-Key
   * @param originalId the decision overridden
   * @param fraud true for CONFIRM_FRAUD
   * @param reason the reason
   * @param alertVersion the version the caller saw
   * @return the contract's {@code DecisionRecord}
   */
  public Map<String, Object> override(
      Caller caller,
      UUID alertId,
      UUID key,
      UUID originalId,
      boolean fraud,
      String reason,
      long alertVersion) {
    String newDecision = fraud ? "CONFIRM_FRAUD" : "MARK_LEGITIMATE";
    byte[] fingerprint =
        fingerprint(
            "override",
            alertId.toString(),
            originalId.toString(),
            newDecision,
            reason,
            Long.toString(alertVersion));
    Overridden overridden =
        inTenant(
            caller.institutionId(),
            () -> {
              bound();
              Entry entry = lockEntry(alertId);
              Optional<Keyed> keyed = byKey(key);
              if (keyed.isPresent()) {
                Keyed k = keyed.get();
                if (k.entryId().equals(alertId)
                    && caller.userId().equals(k.analystId())
                    && Arrays.equals(k.fingerprint(), fingerprint)) {
                  return new Overridden(record(k.id()), null);
                }
                throw idempotencyConflict();
              }
              if (entry.version() != alertVersion || entry.resolutionId() != null) {
                throw stale(entry.id());
              }
              Map<String, Object> original = latestOverridable(alertId, originalId, entry);
              if (!"COMMITTED".equals(original.get("state"))) {
                throw ProblemException.of(
                    "decision-not-committed",
                    409,
                    "Decision not committed",
                    "Only a committed decision can be overridden");
              }
              if (caller.userId().equals(original.get("analyst_id"))) {
                throw ProblemException.of(
                    "own-decision-override",
                    403,
                    "Own decision",
                    "A risk officer cannot override their own decision");
              }
              UUID decisionId = UUID.randomUUID();
              jdbc.update(
                  """
                  INSERT INTO alert_decisions (id, institution_id, alert_queue_entry_id,
                  analyst_id, decision, analyst_comment, override_of_decision_id, idempotency_key,
                  prior_status, alert_version, request_fingerprint, override_decision) VALUES (?, ?,
                  ?, ?, 'SENIOR_OVERRIDE', ?, ?, ?, ?, ?, ?, ?)
                  """,
                  decisionId,
                  caller.institutionId(),
                  alertId,
                  caller.userId(),
                  reason,
                  originalId,
                  key,
                  entry.status(),
                  alertVersion,
                  fingerprint,
                  newDecision);
              DecisionOwner.Result result =
                  submit(
                      caller.institutionId(),
                      entry.transactionId(),
                      new OwnerRequest.SeniorOverride(
                          decisionId, fraud, caller.userId(), recordedLabel(originalId)));
              String outcome =
                  switch (result.outcome()) {
                    case APPLIED -> "APPLIED";
                    case LABEL_ONLY -> "LABEL_ONLY";
                    default -> throw refusal(result, entry);
                  };
              jdbc.update(
                  """
                  INSERT INTO alert_decision_effects (decision_id, institution_id, outcome)
                  VALUES (?, ?, ?)
                  """,
                  decisionId,
                  caller.institutionId(),
                  outcome);
              jdbc.update(
                  "UPDATE alert_queue_entries SET status = 'OVERRIDDEN', version = version + 1"
                      + " WHERE id = ?",
                  alertId);
              audit.record(
                  AuditEvent.of(
                          caller.institutionId(), AuditEventType.SENIOR_OVERRIDE, "OVERRIDDEN")
                      .entity("alert_decision", decisionId)
                      .actor(actor(caller))
                      .after(
                          Map.of(
                              "override_of_decision_id",
                              originalId.toString(),
                              "override_decision",
                              newDecision))
                      .context(caller.context())
                      .at(clock.instant()));
              return new Overridden(record(decisionId), result);
            });
    if (overridden.result() != null) {
      recorder.record(caller.institutionId(), overridden.result());
    }
    return overridden.record();
  }

  private record Overridden(Map<String, Object> record, DecisionOwner.Result result) {}

  /** D5(b): the entry's latest decision that is neither undone nor overridden, and overridable. */
  private Map<String, Object> latestOverridable(UUID alertId, UUID originalId, Entry entry) {
    List<Map<String, Object>> latest =
        jdbc.queryForList(
            """
            SELECT s.id, s.analyst_id, s.decision, s.state FROM v_alert_decision_state s
            WHERE s.alert_queue_entry_id = ? AND s.state <> 'UNDONE'
            AND NOT EXISTS (SELECT 1 FROM alert_decisions o WHERE o.override_of_decision_id = s.id)
            ORDER BY s.created_at DESC LIMIT 1
            """,
            alertId);
    if (latest.isEmpty()
        || !originalId.equals(latest.getFirst().get("id"))
        || "ESCALATE".equals(latest.getFirst().get("decision"))) {
      throw stale(entry.id());
    }
    return latest.getFirst();
  }

  /**
   * The original's label id when its label was recorded: its effects row is APPLIED or LABEL_ONLY.
   */
  private UUID recordedLabel(UUID originalId) {
    Integer recorded =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM alert_decision_effects WHERE decision_id = ?
            AND outcome IN ('APPLIED', 'LABEL_ONLY')
            """,
            Integer.class,
            originalId);
    return recorded != null && recorded > 0 ? OwnerIds.decisionLabel(originalId) : null;
  }

  private Map<String, Object> record(UUID decisionId) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
            SELECT s.id, s.decision, s.analyst_comment, s.override_of_decision_id,
            s.override_decision, s.created_at, s.state, u.id AS user_id, u.first_name,
            u.last_name, u.role FROM v_alert_decision_state s JOIN users u ON u.id = s.analyst_id
            WHERE s.id = ?
            """,
            decisionId);
    Map<String, Object> analyst = new LinkedHashMap<>();
    analyst.put("user_id", row.get("user_id").toString());
    analyst.put("first_name", row.get("first_name"));
    analyst.put("last_name", row.get("last_name"));
    analyst.put("role", row.get("role"));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decision_id", row.get("id").toString());
    body.put("decision", row.get("decision"));
    body.put("analyst", analyst);
    body.put("decided_at", ((Timestamp) row.get("created_at")).toInstant().toString());
    body.put("state", row.get("state"));
    body.put("comment", row.get("analyst_comment"));
    Object of = row.get("override_of_decision_id");
    body.put("override_of_decision_id", of == null ? null : of.toString());
    body.put("override_decision", row.get("override_decision"));
    body.put("false_positive_confirmed", null);
    return body;
  }

  // -------------------------------------------------------------------------------- helpers

  /** §2: the bounds every transaction here runs with, set inside it. */
  private void bound() {
    jdbc.queryForObject(
        "SELECT set_config('lock_timeout', '2s', true), set_config('statement_timeout', '5s',"
            + " true)",
        Object.class);
  }

  private Entry lockEntry(UUID alertId) {
    List<Entry> found =
        jdbc.query(
            LOCK_ENTRY,
            (row, i) ->
                new Entry(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getString(3),
                    row.getString(4),
                    row.getLong(5),
                    row.getString(6),
                    row.getObject(7, UUID.class)),
            alertId);
    if (found.isEmpty()) {
      throw ProblemException.notFound("alert");
    }
    jdbc.queryForObject(ENTRY_LOCK, Object.class, alertId);
    return found.getFirst();
  }

  private Optional<Keyed> byKey(UUID key) {
    List<Keyed> found =
        jdbc.query(
            BY_KEY,
            (row, i) -> {
              Timestamp until = row.getTimestamp(6);
              return new Keyed(
                  row.getObject(1, UUID.class),
                  row.getObject(2, UUID.class),
                  row.getObject(3, UUID.class),
                  row.getString(4),
                  row.getBytes(5),
                  until == null ? null : until.toInstant(),
                  row.getObject(7, UUID.class));
            },
            key);
    return found.stream().findFirst();
  }

  private static void requireLevel(Caller caller, Entry entry) {
    if (entry.escalationTarget() != null
        && rank(caller.role()) < rank(StaffRole.valueOf(entry.escalationTarget()))) {
      throw ProblemException.of(
          "escalation-level-required",
          403,
          "Escalation level required",
          "This escalated alert needs a higher role");
    }
  }

  private void requireCurrent(Entry entry, long alertVersion) {
    if (entry.version() != alertVersion || !entry.open()) {
      throw stale(entry.id());
    }
  }

  private ProblemException stale(UUID alertId) {
    Optional<Map<String, Object>> current = alerts.summary(alertId);
    ProblemException stale =
        ProblemException.of(
            "stale-alert", 409, "Stale alert", "The alert changed; the current one is attached");
    return current.isPresent() ? stale.with("current", current.get()) : stale;
  }

  private static ProblemException idempotencyConflict() {
    return ProblemException.of(
        "idempotency-conflict",
        409,
        "Idempotency conflict",
        "This Idempotency-Key was used with a different request");
  }

  /** Maps an owner refusal to the contract's answer (§5). */
  private ProblemException refusal(DecisionOwner.Result result, Entry entry) {
    Verdict.Refusal why = result.refusal();
    if (why == null) {
      return ProblemException.serviceUnavailable(1);
    }
    return switch (why) {
      case DEADLINE_PASSED ->
          ProblemException.of(
              "review-deadline-passed",
              409,
              "Review deadline passed",
              "The hold's deadline has passed; its timeout policy decides it");
      case CONTESTED ->
          ProblemException.of(
              CONTEST_TYPE,
              409,
              "Decision contested",
              "Two first decisions exist; a risk officer is resolving them");
      case NOT_COMMITTED ->
          ProblemException.of(
              "decision-not-committed",
              409,
              "Decision not committed",
              "The original's effect is still pending");
      case EFFECT_FAILED ->
          ProblemException.of(
              "decision-effect-failed",
              409,
              "Decision effect failed",
              "The original's effect failed; an operator must release it first");
      case NO_FIRST_DECISION -> ProblemException.serviceUnavailable(1);
      default -> stale(entry.id());
    };
  }

  private DecisionOwner.Result submit(
      UUID institutionId, UUID transactionId, OwnerRequest request) {
    Connection c = DataSourceUtils.getConnection(dataSource);
    try {
      return owner.submit(c, institutionId, transactionId, request);
    } catch (SQLException e) {
      // Translated, so the runner sees a 40001 or 40P01 and retries it, and a timeout reaches
      // inTenant's mapping to 503 (code review 1, MAJOR 1).
      DataAccessException translated =
          jdbc.getExceptionTranslator().translate("the owner request", null, e);
      throw translated != null
          ? translated
          : new UncategorizedSQLException("the owner request", null, e);
    } finally {
      DataSourceUtils.releaseConnection(c, dataSource);
    }
  }

  /**
   * One runner transaction with §2's bounds. A lock or statement timeout, or a lost connection,
   * answers 503 with Retry-After, as §2 requires; a stale alert detected in an aborted transaction
   * is answered from a fresh one.
   */
  private <T> T inTenant(UUID institutionId, Supplier<T> work) {
    try {
      return tenants.inTenant(
          institutionId,
          () -> {
            bound();
            return work.get();
          });
    } catch (StaleAfterRollback stale) {
      throw tenants.inTenant(institutionId, () -> stale(stale.alertId()));
    } catch (PessimisticLockingFailureException
        | QueryTimeoutException
        | DataAccessResourceFailureException unavailable) {
      throw ProblemException.serviceUnavailable(1);
    }
  }

  /** A stale alert found where the transaction can no longer read it. */
  private static final class StaleAfterRollback extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final UUID alertId;

    StaleAfterRollback(UUID alertId) {
      super("stale alert " + alertId, null, false, false);
      this.alertId = alertId;
    }

    UUID alertId() {
      return alertId;
    }
  }

  /** The hold to reschedule after the commit, when the transaction is still held (D2(e), D6(e)). */
  private Optional<HoldSchedulePort.DueHold> heldAt(UUID institutionId, UUID transactionId) {
    List<HoldSchedulePort.DueHold> found =
        jdbc.query(
            """
            SELECT o.deadline, o.timeout_policy, t.channel FROM decision_owners o
            JOIN v_transactions t ON t.transaction_id = o.transaction_id
            WHERE o.transaction_id = ? AND o.decision = 'HOLD'
            """,
            (row, i) ->
                new HoldSchedulePort.DueHold(
                    institutionId,
                    transactionId,
                    row.getString(3),
                    io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy.valueOf(
                        row.getString(2) == null ? "RELEASE_WITH_TIMEOUT_LABEL" : row.getString(2)),
                    row.getTimestamp(1).toInstant()),
            transactionId);
    return found.stream().findFirst();
  }

  private void reschedule(HoldSchedulePort.DueHold hold) {
    if (holds == null) {
      return;
    }
    AfterCommit.run(
        () -> {
          try {
            holds.schedule(hold);
          } catch (RuntimeException failed) {
            // The overdue sweep times the hold out 10 to 15 s after its deadline (D2(e)).
          }
        });
  }

  private AuditActor actor(Caller caller) {
    Map<String, Object> user =
        jdbc.queryForMap("SELECT first_name, last_name FROM users WHERE id = ?", caller.userId());
    return new AuditActor(
        caller.userId(),
        (String) user.get("first_name"),
        (String) user.get("last_name"),
        caller.role());
  }

  static int rank(StaffRole role) {
    return switch (role) {
      case ANALYST -> 1;
      case SENIOR_ANALYST -> 2;
      case RISK_OFFICER -> 3;
      default -> 0;
    };
  }

  static byte[] fingerprint(String... parts) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
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
