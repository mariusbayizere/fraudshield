package io.github.mariusbayizere.fraudshield.analyst.decisions;

import io.github.mariusbayizere.fraudshield.audit.AuditEvent;
import io.github.mariusbayizere.fraudshield.audit.AuditEventType;
import io.github.mariusbayizere.fraudshield.audit.AuditLog;
import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import io.github.mariusbayizere.fraudshield.decision.owner.OwnerRequest;
import io.github.mariusbayizere.fraudshield.decision.owner.RequestRecorder;
import io.github.mariusbayizere.fraudshield.decision.owner.Verdict;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * The decision-owner contract's analyst workers, each run every P on every instance: the committer
 * (D3), the effects worker (D4) and the resolver (§7.2). Each lists the institutions with work
 * through a SECURITY DEFINER lister (ids only), then works one decision or resolution per
 * transaction, so one failure never stops the others (review 4, finding 1). Each run handles at
 * most one batch.
 */
public final class DecisionWorkers {

  private static final int BATCH = 100;
  private static final Logger LOG = LoggerFactory.getLogger(DecisionWorkers.class);

  private final TenantTransactions tenants;
  private final JdbcTemplate jdbc;
  private final DataSource dataSource;
  private final DecisionOwner owner;
  private final RequestRecorder recorder;
  private final AuditLog audit;
  private final CircuitBreakerPort breakers;
  private final HoldSchedulePort holds;
  private final Clock clock;
  private final AtomicLong commitFailures = new AtomicLong();
  private final AtomicLong effectFailures = new AtomicLong();

  /**
   * Creates the workers.
   *
   * @param tenants the runner
   * @param jdbc JDBC template of the same data source
   * @param dataSource the data source, for the owner's transaction-bound connection
   * @param owner the transaction's owner
   * @param recorder records an owner outcome after the commit
   * @param audit the audit log
   * @param breakers the MCC breaker, for E2; null where the application has none
   * @param holds the hold schedule, rescheduled after a released claim; null where none
   * @param clock clock
   */
  public DecisionWorkers(
      TenantTransactions tenants,
      JdbcTemplate jdbc,
      DataSource dataSource,
      DecisionOwner owner,
      RequestRecorder recorder,
      AuditLog audit,
      CircuitBreakerPort breakers,
      HoldSchedulePort holds,
      Clock clock) {
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.owner = Objects.requireNonNull(owner, "owner");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.breakers = breakers;
    this.holds = holds;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // ------------------------------------------------------------------------------- D3: commit

  /**
   * Commits every analyst decision whose undo window has closed (D3).
   *
   * @return decisions committed
   */
  public int commitDue() {
    int committed = 0;
    for (UUID institution : institutions("SELECT * FROM institutions_with_due_commits()")) {
      List<UUID> due =
          tenants.inTenant(
              institution,
              () ->
                  jdbc.queryForList(
                      """
                      SELECT d.id FROM alert_decisions d WHERE d.undo_until <= clock_timestamp()
                      AND NOT EXISTS (SELECT 1 FROM alert_decision_commits c WHERE c.decision_id = d.id)
                      AND NOT EXISTS (SELECT 1 FROM alert_decision_undos u WHERE u.decision_id = d.id)
                      ORDER BY d.undo_until LIMIT ?
                      """,
                      UUID.class,
                      BATCH));
      for (UUID decisionId : due) {
        try {
          if (commit(institution, decisionId)) {
            committed++;
          }
        } catch (RuntimeException failed) {
          commitFailures.incrementAndGet();
          LOG.warn("decision {} could not be committed; tried again next run", decisionId, failed);
        }
      }
    }
    return committed;
  }

  private boolean commit(UUID institution, UUID decisionId) {
    return tenants.inTenant(
        institution,
        () -> {
          Boolean locked =
              jdbc.queryForObject(
                  "SELECT pg_try_advisory_xact_lock(hashtextextended('alert_decision:' || ?::text,"
                      + " 0))",
                  Boolean.class,
                  decisionId);
          if (!Boolean.TRUE.equals(locked)) {
            return false;
          }
          List<UUID> inserted =
              jdbc.queryForList(
                  """
                  INSERT INTO alert_decision_commits (decision_id, institution_id) VALUES (?, ?)
                  ON CONFLICT DO NOTHING RETURNING decision_id
                  """,
                  UUID.class,
                  decisionId,
                  institution);
          if (inserted.isEmpty()) {
            return false;
          }
          audit.record(
              AuditEvent.of(institution, AuditEventType.ANALYST_DECISION, "COMMITTED")
                  .entity("alert_decision", decisionId)
                  .at(clock.instant()));
          return true;
        });
  }

  // ------------------------------------------------------------------------------ D4: effects

  /**
   * Runs the effects of committed analyst decisions, then the breaker counts (D4).
   *
   * @return effects written
   */
  public int runEffects() {
    int done = 0;
    for (UUID institution : institutions("SELECT * FROM institutions_with_pending_effects()")) {
      List<Map<String, Object>> pending =
          tenants.inTenant(
              institution,
              () ->
                  jdbc.queryForList(
                      """
                      SELECT d.id, d.decision, d.analyst_id, e.transaction_id FROM alert_decisions d
                      JOIN alert_decision_commits c ON c.decision_id = d.id
                      JOIN alert_queue_entries e ON e.id = d.alert_queue_entry_id
                      WHERE NOT EXISTS (SELECT 1 FROM alert_decision_effects f WHERE f.decision_id = d.id)
                      AND NOT EXISTS (SELECT 1 FROM decision_owners o
                        WHERE o.transaction_id = e.transaction_id AND o.contested_since IS NOT NULL)
                      ORDER BY c.committed_at LIMIT ?
                      """,
                      BATCH));
      for (Map<String, Object> row : pending) {
        if (effect(institution, row)) {
          done++;
        }
      }
      countBreakers(institution);
    }
    return done;
  }

  private boolean effect(UUID institution, Map<String, Object> row) {
    UUID decisionId = (UUID) row.get("id");
    UUID transactionId = (UUID) row.get("transaction_id");
    OwnerRequest.Effect request =
        new OwnerRequest.Effect(
            decisionId, "CONFIRM_FRAUD".equals(row.get("decision")), (UUID) row.get("analyst_id"));
    DecisionOwner.Result result;
    try {
      result =
          tenants.inTenant(
              institution,
              () -> {
                DecisionOwner.Result r = submit(institution, transactionId, request);
                String outcome =
                    switch (r.outcome()) {
                      case APPLIED -> "APPLIED";
                      case LABEL_ONLY -> "LABEL_ONLY";
                      case REFUSED -> "SKIPPED";
                      default -> null;
                    };
                if (outcome != null) {
                  jdbc.update(
                      """
                      INSERT INTO alert_decision_effects (decision_id, institution_id, outcome,
                      reason) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                      """,
                      decisionId,
                      institution,
                      outcome,
                      r.refusal() == null ? null : r.refusal().reason());
                }
                return r;
              });
    } catch (DataAccessException | IllegalStateException failed) {
      String state = sqlState(failed);
      if (state != null && (state.startsWith("22") || state.startsWith("23"))) {
        // D4(e): a permanent failure is recorded FAILED, counted, and the worker moves on.
        effectFailures.incrementAndGet();
        tenants.inTenant(
            institution,
            () ->
                jdbc.update(
                    """
                    INSERT INTO alert_decision_effects (decision_id, institution_id, outcome,
                    sqlstate, reason) VALUES (?, ?, 'FAILED', ?, ?) ON CONFLICT DO NOTHING
                    """,
                    decisionId,
                    institution,
                    state,
                    "effect"));
      }
      LOG.warn("the effect of decision {} failed", decisionId, failed);
      return false;
    }
    recorder.record(institution, result);
    return result.outcome() != Verdict.Outcome.DEFERRED;
  }

  /** E2: count each committed CONFIRM_FRAUD of a MEDIUM or ANOMALY entry once, never HIGH. */
  private void countBreakers(UUID institution) {
    if (breakers == null) {
      return;
    }
    List<Map<String, Object>> uncounted =
        tenants.inTenant(
            institution,
            () ->
                jdbc.queryForList(
                    """
                    SELECT d.id, e.transaction_id, t.merchant_category_code, t.transaction_timestamp
                    FROM alert_decisions d
                    JOIN alert_decision_effects f ON f.decision_id = d.id
                    JOIN alert_queue_entries e ON e.id = d.alert_queue_entry_id
                    JOIN v_transactions t ON t.transaction_id = e.transaction_id
                    WHERE f.outcome IN ('APPLIED', 'LABEL_ONLY') AND e.tier IN ('MEDIUM', 'ANOMALY')
                    AND (d.decision = 'CONFIRM_FRAUD' OR d.override_decision = 'CONFIRM_FRAUD')
                    AND NOT EXISTS (SELECT 1 FROM alert_decision_breaker_counts k
                      WHERE k.decision_id = d.id)
                    LIMIT ?
                    """,
                    BATCH));
    for (Map<String, Object> row : uncounted) {
      try {
        breakers.countConfirmedFraudOnce(
            institution,
            (UUID) row.get("transaction_id"),
            (String) row.get("merchant_category_code"),
            ((Timestamp) row.get("transaction_timestamp")).toInstant());
        tenants.inTenant(
            institution,
            () ->
                jdbc.update(
                    """
                    INSERT INTO alert_decision_breaker_counts (decision_id, institution_id)
                    VALUES (?, ?) ON CONFLICT DO NOTHING
                    """,
                    row.get("id"),
                    institution));
      } catch (RuntimeException redisDown) {
        // Retried on the next run; SET NX makes the retry count once (D4(d)).
        return;
      }
    }
  }

  // ------------------------------------------------------------------------- §7.2: resolver

  /**
   * Applies every operator resolution the SQL functions authorised (§7.2).
   *
   * @return resolutions applied
   */
  public int applyResolutions() {
    int applied = 0;
    for (UUID institution : institutions("SELECT * FROM institutions_with_unapplied_resolutions()")) {
      List<Map<String, Object>> unapplied =
          tenants.inTenant(
              institution,
              () ->
                  jdbc.queryForList(
                      """
                      SELECT z.id, z.transaction_id, z.kind, z.decision_id, z.chosen_decision,
                      z.contested_since, z.staff_user_id FROM decision_conflict_resolutions z
                      WHERE NOT EXISTS (SELECT 1 FROM transaction_requests q WHERE q.request_id =
                        fs_name_uuid(CASE z.kind WHEN 'RESOLVE' THEN 'resolve|'
                          ELSE 'release_failed|' END || z.id::text))
                      ORDER BY z.created_at LIMIT ?
                      """,
                      BATCH));
      for (Map<String, Object> row : unapplied) {
        try {
          resolve(institution, row);
          applied++;
        } catch (RuntimeException failed) {
          LOG.warn("resolution {} could not be applied; tried again next run", row.get("id"), failed);
        }
      }
    }
    return applied;
  }

  private void resolve(UUID institution, Map<String, Object> row) {
    UUID resolutionId = (UUID) row.get("id");
    UUID transactionId = (UUID) row.get("transaction_id");
    UUID officer = (UUID) row.get("staff_user_id");
    OwnerRequest request =
        "RESOLVE".equals(row.get("kind"))
            ? new OwnerRequest.Resolve(
                resolutionId,
                DecisionValue.valueOf((String) row.get("chosen_decision")),
                officer,
                ((Timestamp) row.get("contested_since")).toInstant())
            : new OwnerRequest.ReleaseFailed(resolutionId, (UUID) row.get("decision_id"));
    DecisionOwner.Result result =
        tenants.inTenant(
            institution,
            () -> {
              DecisionOwner.Result r = submit(institution, transactionId, request);
              audit.record(
                  AuditEvent.of(
                          institution,
                          request instanceof OwnerRequest.Resolve
                              ? AuditEventType.SENIOR_OVERRIDE
                              : AuditEventType.ANALYST_DECISION,
                          request instanceof OwnerRequest.Resolve ? "RESOLVED" : "CLAIM_RELEASED")
                      .entity("transaction", transactionId)
                      .after(
                          Map.of(
                              "resolution_id", resolutionId.toString(),
                              "outcome", r.outcome().name(),
                              "cleared_claim",
                              r.clearedClaim() == null ? "" : r.clearedClaim().toString()))
                      .at(clock.instant()));
              return r;
            });
    recorder.record(institution, result);
    if (result.outcome() == Verdict.Outcome.RELEASED && holds != null) {
      rescheduleHeld(institution, transactionId);
    }
  }

  private void rescheduleHeld(UUID institution, UUID transactionId) {
    List<HoldSchedulePort.DueHold> held =
        tenants.inTenant(
            institution,
            () ->
                jdbc.query(
                    """
                    SELECT o.deadline, o.timeout_policy, t.channel FROM decision_owners o
                    JOIN v_transactions t ON t.transaction_id = o.transaction_id
                    WHERE o.transaction_id = ? AND o.decision = 'HOLD'
                    """,
                    (r, i) ->
                        new HoldSchedulePort.DueHold(
                            institution,
                            transactionId,
                            r.getString(3),
                            MediumTimeoutPolicy.valueOf(
                                r.getString(2) == null ? "RELEASE_WITH_TIMEOUT_LABEL" : r.getString(2)),
                            r.getTimestamp(1).toInstant()),
                    transactionId));
    for (HoldSchedulePort.DueHold hold : held) {
      try {
        holds.schedule(hold);
      } catch (RuntimeException failed) {
        // The overdue sweep acts 10 to 15 s after the deadline.
      }
    }
  }

  // -------------------------------------------------------------------------------- helpers

  private List<UUID> institutions(String lister) {
    try {
      return new ArrayList<>(jdbc.queryForList(lister, UUID.class));
    } catch (DataAccessException e) {
      LOG.warn("a decision worker's lister failed", e);
      return List.of();
    }
  }

  private DecisionOwner.Result submit(
      UUID institutionId, UUID transactionId, OwnerRequest request) {
    jdbc.queryForObject(
        "SELECT set_config('lock_timeout', '2s', true), set_config('statement_timeout', '5s',"
            + " true)",
        Object.class);
    Connection c = DataSourceUtils.getConnection(dataSource);
    try {
      return owner.submit(c, institutionId, transactionId, request);
    } catch (SQLException e) {
      throw new IllegalStateException(e.getSQLState(), e);
    } finally {
      DataSourceUtils.releaseConnection(c, dataSource);
    }
  }

  private static String sqlState(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }

  /**
   * Commits that failed and were left for the next run.
   *
   * @return count
   */
  public long commitFailures() {
    return commitFailures.get();
  }

  /**
   * Effects recorded FAILED.
   *
   * @return count
   */
  public long effectFailures() {
    return effectFailures.get();
  }
}
