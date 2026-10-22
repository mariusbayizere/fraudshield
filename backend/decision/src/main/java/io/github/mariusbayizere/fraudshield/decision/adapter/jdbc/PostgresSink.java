package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.decision.adapter.events.FactCodec;
import io.github.mariusbayizere.fraudshield.decision.adapter.events.KafkaMessages;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolDrainer;
import io.github.mariusbayizere.fraudshield.decision.adapter.spool.SpoolRecord;
import io.github.mariusbayizere.fraudshield.decision.application.event.DecisionEvent;
import io.github.mariusbayizere.fraudshield.decision.domain.AlertTier;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.owner.DecisionOwner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes spooled facts to PostgreSQL (V3–V6, V60) as {@code fs_app}.
 *
 * <p>Idempotent: every insert is keyed so that re-delivering a spool record writes nothing new. A
 * transaction decided twice (the process died after the spool append but before the response, and
 * the client retried) keeps its first decision: the second record's rows are dropped, because
 * {@code transaction_ids} admits one fingerprint per transaction and {@code decision_states} one
 * sequence 1. A record the database refuses for a non-transient reason is written to a dead-letter
 * file beside the spool and counted, rather than blocking every record behind it; transient
 * failures fail the batch, which the drainer retries.
 *
 * <p>Under the decision-owner contract (§6.1) it writes sequence 1 only. For every first decision
 * it takes the first-decision lock and asks the transaction's owner, if there is one, whether the
 * record is the owner's own, a duplicate, or a second first decision; later states are the owner's
 * request rows, and a spooled one is not written again.
 */
public final class PostgresSink implements SpoolDrainer.Sink {

  private static final Logger LOG = LoggerFactory.getLogger(PostgresSink.class);
  private static final String FREEZE_CAUSE =
      "Third HIGH-risk decision within 60 minutes (FR-03-06)";

  private static final String INSERT_TRANSACTION_IDS =
      """
      INSERT INTO transaction_ids (institution_id, transaction_id, request_fingerprint,
      transaction_timestamp, received_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
      """;

  private static final String INSERT_TRANSACTIONS =
      """
      INSERT INTO transactions (institution_id, transaction_id, account_token,
      counterparty_token, amount, currency, amount_rwf, channel, merchant_category_code,
      latitude, longitude, device_token, agent_token, counterparty_country,
      transaction_timestamp, received_at, processing_duration_ms) VALUES (?, ?, ?, ?, ?, ?, ?,
      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
      """;

  private static final String INSERT_FRAUD_SCORES =
      """
      INSERT INTO fraud_scores (id, institution_id, transaction_id, scored_at, ensemble_score,
      xgboost_score, lightgbm_score, anomaly_score, anomaly_raw, risk_tier, shap_top5, shap_all,
      feature_vector, model_version, feature_registry_version, scoring_duration_ms,
      requires_analyst_review, ml_unavailable_fallback, trace_id, account_context,
      feature_store_degraded, shap_base_value, final_margin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
      ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) ON CONFLICT DO NOTHING
      """;

  private static final String INSERT_ACCOUNT_PROFILES =
      """
      INSERT INTO account_profiles (institution_id, account_token, first_seen_at) VALUES (?, ?,
      ?) ON CONFLICT (institution_id, account_token) DO UPDATE SET first_seen_at =
      EXCLUDED.first_seen_at WHERE account_profiles.first_seen_at IS NULL
      OR EXCLUDED.first_seen_at < account_profiles.first_seen_at
      """;

  private static final String SELECT_DECISION_STATES =
      """
      SELECT event_id, decision FROM decision_states WHERE institution_id = ? AND transaction_id =
      ? AND decision_sequence = 1
      """;

  private static final String FIRST_DECISION_LOCK =
      "SELECT pg_advisory_xact_lock(hashtextextended('first_decision:' || ? || ':' || ?, 0))";

  private static final String SELECT_TIMEOUT_POLICY =
      """
      SELECT medium_timeout_policy FROM risk_thresholds WHERE institution_id = ? AND version = ?
      AND channel = ?
      """;

  private static final String SELECT_REQUEST_EVENT =
      "SELECT 1 FROM transaction_requests WHERE event_id = ?";

  private static final String INSERT_DECISION_STATES =
      """
      INSERT INTO decision_states (event_id, institution_id, transaction_id, decision_sequence,
      decision, final, decided_at, decided_by, reason_codes, review_deadline_at,
      supersedes_decision, timeout_policy) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT
      DO NOTHING
      """;

  /** Stateless: every call runs on the sink's own connection and transaction. */
  private static final DecisionOwner OWNER = new DecisionOwner();

  /** SQLSTATE 53200: the shared lock table is full (§6.1, the lock budget). */
  private static final String OUT_OF_LOCKS = "53200";

  private static final String INSERT_ALERT_QUEUE_ENTRIES =
      """
      INSERT INTO alert_queue_entries (id, institution_id, transaction_id, fraud_score_id, tier,
      fraud_probability, expected_loss_rwf, review_deadline_at, created_at) VALUES (?, ?, ?, ?,
      ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
      """;

  private static final String INSERT_AUTO_BLOCK_EVENTS =
      """
      INSERT INTO auto_block_events (id, institution_id, transaction_id, fraud_score_id,
      account_token, blocked_at, block_reason) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO
      NOTHING
      """;

  private static final String INSERT_CUSTOMER_NOTIFICATIONS =
      """
      INSERT INTO customer_notifications (notification_id, institution_id, auto_block_event_id,
      account_token, channel, template_key, locale, verification_link_allowed, event,
      occurred_at) VALUES (?, ?, ?, ?, 'SMS', ?, ?, ?, 'REQUESTED', ?) ON CONFLICT DO NOTHING
      """;

  private static final String INSERT_ACCOUNT_FREEZE_EVENTS =
      """
      INSERT INTO account_freeze_events (id, institution_id, account_token, event, cause,
      auto_block_event_id, occurred_at) VALUES (?, ?, ?, 'FROZEN', ?, ?, ?) ON CONFLICT DO
      NOTHING
      """;

  private static final String INSERT_LABEL_EVENTS =
      """
      INSERT INTO label_events (id, institution_id, transaction_id, label, source,
      transaction_timestamp, label_available_at, supersedes_label_id) VALUES (?, ?, ?, ?, ?, ?, ?,
      ?) ON CONFLICT DO NOTHING
      """;

  private static final String INSERT_MCC_CIRCUIT_BREAKER_EVENTS =
      """
      INSERT INTO mcc_circuit_breaker_events (id, institution_id, merchant_category_code, event,
      window_fraud_rate, window_transactions, settings_version, occurred_at) VALUES (?, ?, ?, ?,
      ?, ?, ?, ?) ON CONFLICT DO NOTHING
      """;

  private final DataSource dataSource;
  private final Path deadLetters;
  private final AtomicLong deadLettered = new AtomicLong();
  private final AtomicLong duplicateDecisions = new AtomicLong();
  private final AtomicLong unreservedStates = new AtomicLong();

  /**
   * Creates the sink.
   *
   * @param dataSource connections as {@code fs_app}
   * @param deadLetters directory for records the database refuses
   */
  public PostgresSink(DataSource dataSource, Path deadLetters) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
  }

  /**
   * Records moved to the dead-letter directory.
   *
   * @return count
   */
  public long deadLettered() {
    return deadLettered.get();
  }

  /**
   * Later states that reached the sink with no owner request behind them (a pre-V81 dead letter,
   * contract §6.1), dropped and counted.
   *
   * @return count
   */
  public long unreservedStates() {
    return unreservedStates.get();
  }

  /**
   * Second decisions for an already-decided transaction that were dropped.
   *
   * @return count
   */
  public long duplicateDecisions() {
    return duplicateDecisions.get();
  }

  @Override
  public void accept(List<SpoolRecord> records) throws SpoolDrainer.SinkException {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        for (SpoolRecord record : records) {
          write(connection, FactCodec.decode(record.payload()));
        }
        connection.commit();
        return;
      } catch (SQLException e) {
        connection.rollback();
        if (OUT_OF_LOCKS.equals(e.getSQLState()) && records.size() > 1) {
          // One first-decision lock per record is held to the batch's commit: halve, never retry
          // an over-large batch for ever (§6.1).
          int half = records.size() / 2;
          accept(records.subList(0, half));
          accept(records.subList(half, records.size()));
          return;
        }
        if (Tenant.transientError(e)) {
          throw new SpoolDrainer.SinkException("PostgreSQL is unavailable", e);
        }
      }
      for (SpoolRecord record : records) {
        try {
          write(connection, FactCodec.decode(record.payload()));
          connection.commit();
        } catch (SQLException e) {
          connection.rollback();
          if (Tenant.transientError(e)) {
            throw new SpoolDrainer.SinkException("PostgreSQL is unavailable", e);
          }
          deadLetter(record, e);
        }
      }
    } catch (SQLException e) {
      throw new SpoolDrainer.SinkException("could not reach PostgreSQL", e);
    }
  }

  private void deadLetter(SpoolRecord record, SQLException cause) {
    try {
      Files.createDirectories(deadLetters);
      Files.write(
          deadLetters.resolve(String.format("%020d.json", record.offset())),
          record.payload(),
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.writeString(
          deadLetters.resolve(String.format("%020d.error", record.offset())),
          cause.getSQLState() + " " + cause.getMessage(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("could not dead-letter a spool record", e);
    }
    deadLettered.incrementAndGet();
    LOG.error("a spool record was refused by PostgreSQL and dead-lettered", cause);
  }

  private void write(Connection c, List<DecisionEvent> facts) throws SQLException {
    boolean skip = false;
    for (DecisionEvent fact : facts) {
      Tenant.use(c, fact.institutionId());
      switch (fact) {
        case DecisionEvent.TransactionDecided e -> skip = !transactionDecided(c, e);
        case DecisionEvent.AlertRaised e -> {
          if (!skip) {
            alert(c, e);
          }
        }
        case DecisionEvent.AutoBlocked e -> {
          if (!skip) {
            autoBlock(c, e);
          }
        }
        case DecisionEvent.CustomerNotificationRequested e -> {
          if (!skip) {
            notification(c, e);
          }
        }
        case DecisionEvent.AccountFrozen e -> {
          if (!skip) {
            freeze(c, e);
          }
        }
        case DecisionEvent.DecisionChanged e -> decisionChanged(c, e);
        case DecisionEvent.LabelRecorded e -> label(c, e);
        case DecisionEvent.CircuitBreakerChanged e -> breaker(c, e);
        case DecisionEvent.IdempotencyConflict e -> {
          // Audited through Kafka only; nothing to persist here.
        }
      }
    }
  }

  private boolean transactionDecided(Connection c, DecisionEvent.TransactionDecided e)
      throws SQLException {
    Transaction t = e.transaction();
    try (PreparedStatement s = c.prepareStatement(FIRST_DECISION_LOCK)) {
      s.setString(1, t.institutionId().toString());
      s.setString(2, t.transactionId().toString());
      s.execute();
    }
    if (!OWNER.firstDecisionArrives(c, e.state(), kept(c, e.state()))) {
      duplicateDecisions.incrementAndGet();
      return false;
    }
    try (PreparedStatement s = c.prepareStatement(INSERT_TRANSACTION_IDS)) {
      s.setObject(1, t.institutionId());
      s.setObject(2, t.transactionId());
      s.setBytes(3, e.requestFingerprint());
      s.setObject(4, Tenant.utc(t.transactionTimestamp()));
      s.setObject(5, Tenant.utc(t.receivedAt()));
      s.executeUpdate();
    }
    try (PreparedStatement s = c.prepareStatement(INSERT_TRANSACTIONS)) {
      s.setObject(1, t.institutionId());
      s.setObject(2, t.transactionId());
      s.setString(3, t.accountToken());
      s.setString(4, t.counterpartyToken());
      s.setBigDecimal(5, t.amount().amount());
      s.setString(6, t.amount().currency().name());
      s.setBigDecimal(7, t.amountRwf());
      s.setString(8, t.channel().name());
      s.setString(9, t.merchantCategoryCode());
      s.setBigDecimal(10, BigDecimal.valueOf(t.latitude()));
      s.setBigDecimal(11, BigDecimal.valueOf(t.longitude()));
      s.setString(12, t.deviceToken());
      s.setString(13, t.agentToken());
      s.setString(14, t.counterpartyCountry());
      s.setObject(15, Tenant.utc(t.transactionTimestamp()));
      s.setObject(16, Tenant.utc(t.receivedAt()));
      s.setInt(17, (int) Math.min(Integer.MAX_VALUE, e.decisionLatencyMs()));
      s.executeUpdate();
    }
    DecisionEvent.ScoringRecord r = e.scoring();
    try (PreparedStatement s = c.prepareStatement(INSERT_FRAUD_SCORES)) {
      s.setObject(1, r.scoringResultId());
      s.setObject(2, t.institutionId());
      s.setObject(3, t.transactionId());
      s.setObject(4, Tenant.utc(e.state().decidedAt()));
      s.setDouble(5, r.ensembleScore());
      s.setDouble(6, r.xgboostScore());
      s.setDouble(7, r.lightgbmScore());
      s.setDouble(8, r.anomalyScore());
      s.setDouble(9, r.anomalyRaw());
      s.setString(10, r.tier().name());
      s.setString(11, r.shapTop5() == null ? null : json(r.shapTop5()));
      s.setString(12, r.shapAll() == null ? null : json(r.shapAll()));
      s.setString(13, json(r.featureVector()));
      s.setString(14, r.modelVersion());
      s.setString(15, r.featureRegistryVersion());
      s.setInt(16, r.scoringDurationMs());
      s.setBoolean(17, r.requiresAnalystReview());
      s.setBoolean(18, r.fallback());
      s.setString(19, r.traceId());
      s.setString(20, r.accountContext() == null ? null : json(r.accountContext()));
      s.setBoolean(21, r.featureStoreDegraded());
      s.setObject(22, r.shapBaseValue(), Types.DOUBLE);
      s.setObject(23, r.finalMargin(), Types.DOUBLE);
      s.executeUpdate();
    }
    state(c, e.state(), timeoutPolicy(c, e));
    try (PreparedStatement s = c.prepareStatement(INSERT_ACCOUNT_PROFILES)) {
      s.setObject(1, t.institutionId());
      s.setString(2, t.accountToken());
      s.setObject(3, Tenant.utc(t.transactionTimestamp()));
      s.executeUpdate();
    }
    return true;
  }

  private static Optional<DecisionOwner.FirstDecision> kept(Connection c, DecisionState state)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_DECISION_STATES)) {
      s.setObject(1, state.institutionId());
      s.setObject(2, state.transactionId());
      try (ResultSet rows = s.executeQuery()) {
        return rows.next()
            ? Optional.of(
                new DecisionOwner.FirstDecision(
                    rows.getObject(1, UUID.class), DecisionValue.valueOf(rows.getString(2))))
            : Optional.empty();
      }
    }
  }

  /** The policy of the record's own thresholds version, for a HOLD (contract §4.2 point 4). */
  private static String timeoutPolicy(Connection c, DecisionEvent.TransactionDecided e)
      throws SQLException {
    if (e.state().decision() != DecisionValue.HOLD) {
      return null;
    }
    try (PreparedStatement s = c.prepareStatement(SELECT_TIMEOUT_POLICY)) {
      s.setObject(1, e.transaction().institutionId());
      s.setLong(2, e.thresholdsVersion());
      s.setString(3, e.transaction().channel().name());
      try (ResultSet row = s.executeQuery()) {
        return row.next() ? row.getString(1) : null;
      }
    }
  }

  private static void state(Connection c, DecisionState st, String timeoutPolicy)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_DECISION_STATES)) {
      s.setObject(1, st.eventId());
      s.setObject(2, st.institutionId());
      s.setObject(3, st.transactionId());
      s.setInt(4, st.sequence());
      s.setString(5, st.decision().name());
      s.setBoolean(6, st.isFinal());
      s.setObject(7, Tenant.utc(st.decidedAt()));
      s.setString(8, st.decidedBy().name());
      s.setArray(9, c.createArrayOf("text", st.reasonCodes().toArray()));
      s.setObject(10, Tenant.utc(st.reviewDeadlineAt()));
      s.setString(11, st.supersedes() == null ? null : st.supersedes().name());
      s.setString(12, timeoutPolicy);
      s.executeUpdate();
    }
  }

  private static void alert(Connection c, DecisionEvent.AlertRaised e) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_ALERT_QUEUE_ENTRIES)) {
      s.setObject(1, e.alertId());
      s.setObject(2, e.institutionId());
      s.setObject(3, e.transaction().transactionId());
      s.setObject(4, e.scoringResultId());
      s.setString(5, e.tier().name());
      s.setDouble(6, e.fraudProbability());
      s.setBigDecimal(7, KafkaMessages.expectedLoss(e));
      s.setObject(8, Tenant.utc(e.reviewDeadlineAt()));
      s.setObject(9, Tenant.utc(e.createdAt()));
      s.executeUpdate();
    }
    if (e.tier() == AlertTier.MEDIUM) {
      OWNER.lateEntry(c, e.institutionId(), e.transaction().transactionId(), e.alertId());
    }
  }

  private static void autoBlock(Connection c, DecisionEvent.AutoBlocked e) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_AUTO_BLOCK_EVENTS)) {
      s.setObject(1, e.autoBlockEventId());
      s.setObject(2, e.institutionId());
      s.setObject(3, e.transactionId());
      s.setObject(4, e.scoringResultId());
      s.setString(5, e.accountToken());
      s.setObject(6, Tenant.utc(e.blockedAt()));
      s.setString(7, e.reason().isEmpty() ? "HIGH_RISK" : e.reason());
      s.executeUpdate();
    }
  }

  private static void notification(Connection c, DecisionEvent.CustomerNotificationRequested e)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_CUSTOMER_NOTIFICATIONS)) {
      s.setObject(1, e.notificationId());
      s.setObject(2, e.institutionId());
      s.setObject(3, e.autoBlockEventId());
      s.setString(4, e.accountToken());
      s.setString(5, e.templateKey());
      s.setString(6, e.locale());
      s.setBoolean(7, e.verificationLinkAllowed());
      s.setObject(8, Tenant.utc(e.requestedAt()));
      s.executeUpdate();
    }
  }

  private static void freeze(Connection c, DecisionEvent.AccountFrozen e) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_ACCOUNT_FREEZE_EVENTS)) {
      s.setObject(
          1,
          UUID.nameUUIDFromBytes(
              (e.autoBlockEventId() + ":frozen").getBytes(StandardCharsets.UTF_8)));
      s.setObject(2, e.institutionId());
      s.setString(3, e.accountToken());
      s.setString(4, FREEZE_CAUSE);
      s.setObject(5, e.autoBlockEventId());
      s.setObject(6, Tenant.utc(e.frozenAt()));
      s.executeUpdate();
    }
  }

  /**
   * A later state needs no write: its owner request row is its record (contract §4.4). One with no
   * such row can only be a pre-V81 dead letter replayed; it is counted, logged and dropped, and the
   * overdue sweep times out a hold it left open.
   */
  private void decisionChanged(Connection c, DecisionEvent.DecisionChanged e) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_REQUEST_EVENT)) {
      s.setObject(1, e.state().eventId());
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          unreservedStates.incrementAndGet();
          LOG.warn(
              "a later state of transaction {} has no owner request and was dropped",
              e.state().transactionId());
        }
      }
    }
  }

  private static void label(Connection c, DecisionEvent.LabelRecorded e) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_LABEL_EVENTS)) {
      s.setObject(1, e.labelId());
      s.setObject(2, e.institutionId());
      s.setObject(3, e.transactionId());
      s.setString(4, e.fraud() ? "FRAUD" : "LEGITIMATE");
      s.setString(5, e.source());
      s.setObject(6, Tenant.utc(e.transactionTimestamp()));
      s.setObject(7, Tenant.utc(e.availableAt()));
      s.setObject(8, e.supersedesLabelId());
      s.executeUpdate();
    }
  }

  private static void breaker(Connection c, DecisionEvent.CircuitBreakerChanged e)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(INSERT_MCC_CIRCUIT_BREAKER_EVENTS)) {
      s.setObject(
          1,
          UUID.nameUUIDFromBytes(
              (e.institutionId() + ":" + e.merchantCategoryCode() + ":" + e.change() + ":" + e.at())
                  .getBytes(StandardCharsets.UTF_8)));
      s.setObject(2, e.institutionId());
      s.setString(3, e.merchantCategoryCode());
      s.setString(4, e.change().name());
      s.setBigDecimal(5, e.counts().rate());
      s.setInt(6, (int) Math.min(Integer.MAX_VALUE, e.counts().transactions()));
      s.setLong(7, e.settingsVersion());
      s.setObject(8, Tenant.utc(e.at()));
      s.executeUpdate();
    }
  }

  private static String json(Object value) {
    return new String(FactCodec.toJson(value), StandardCharsets.UTF_8);
  }
}
