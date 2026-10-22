package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.decision.domain.DecidedBy;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionState;
import io.github.mariusbayizere.fraudshield.decision.domain.DecisionValue;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Reads the latest persisted decision state ({@code v_decision_states}, V81), the durable read for
 * {@code GET /decisions/{id}} when Redis is down or no longer holds the transaction (C.4).
 */
public final class JdbcDecisionStates {

  private static final String SELECT_DECISION_STATES =
      """
      SELECT event_id, decision_sequence, decision, decided_at, decided_by, reason_codes,
      review_deadline_at, supersedes_decision FROM v_decision_states WHERE transaction_id = ?
      ORDER BY decision_sequence DESC LIMIT 1
      """;

  private static final String SELECT_CONTESTED =
      """
      SELECT contested_since IS NOT NULL FROM decision_owners WHERE transaction_id = ?
      """;

  /**
   * What the bank's poll reads from PostgreSQL (decision-owner contract §8).
   *
   * @param state the highest sequence in {@code v_decision_states}, if any
   * @param contested whether the transaction's owner is contested
   */
  public record Poll(Optional<DecisionState> state, boolean contested) {}

  /**
   * The poll's PostgreSQL read: the latest state, and whether the transaction is contested, from
   * one snapshot.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @return the read
   * @throws SQLException when PostgreSQL cannot answer
   */
  public Poll poll(UUID institutionId, UUID transactionId) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, institutionId);
        boolean contested;
        try (PreparedStatement s = c.prepareStatement(SELECT_CONTESTED)) {
          s.setObject(1, transactionId);
          try (ResultSet row = s.executeQuery()) {
            contested = row.next() && row.getBoolean(1);
          }
        }
        Optional<DecisionState> state = read(c, institutionId, transactionId);
        c.commit();
        return new Poll(state, contested);
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private final DataSource dataSource;

  /**
   * Creates the reader.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JdbcDecisionStates(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * The latest state.
   *
   * @param institutionId institution
   * @param transactionId transaction
   * @return the state with the highest sequence, if any
   * @throws SQLException when the database is unavailable
   */
  public Optional<DecisionState> latest(UUID institutionId, UUID transactionId)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, institutionId);
        Optional<DecisionState> state = read(c, institutionId, transactionId);
        c.commit();
        return state;
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static Optional<DecisionState> read(Connection c, UUID institutionId, UUID transactionId)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(SELECT_DECISION_STATES)) {
      s.setObject(1, transactionId);
      try (ResultSet row = s.executeQuery()) {
        if (!row.next()) {
          return Optional.empty();
        }
        Array reasons = row.getArray(6);
        OffsetDateTime deadline = row.getObject(7, OffsetDateTime.class);
        String supersedes = row.getString(8);
        return Optional.of(
            new DecisionState(
                row.getObject(1, UUID.class),
                institutionId,
                transactionId,
                row.getInt(2),
                DecisionValue.valueOf(row.getString(3)),
                row.getObject(4, OffsetDateTime.class).toInstant(),
                DecidedBy.valueOf(row.getString(5)),
                List.of((String[]) reasons.getArray()),
                deadline == null ? null : deadline.toInstant(),
                supersedes == null ? null : DecisionValue.valueOf(supersedes)));
      }
    }
  }
}
