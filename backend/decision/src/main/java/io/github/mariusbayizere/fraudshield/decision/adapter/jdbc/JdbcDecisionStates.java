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
 * Reads the latest persisted decision state (V3 {@code decision_states}), the durable fallback for
 * {@code GET /decisions/{id}} when Redis is down or no longer holds the transaction (C.4).
 */
public final class JdbcDecisionStates {

  private static final String SELECT_DECISION_STATES =
      """
      SELECT event_id, decision_sequence, decision, decided_at, decided_by, reason_codes,
      review_deadline_at, supersedes_decision FROM decision_states WHERE transaction_id = ?
      ORDER BY decision_sequence DESC LIMIT 1
      """;

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
        try (PreparedStatement s = c.prepareStatement(SELECT_DECISION_STATES)) {
          s.setObject(1, transactionId);
          try (ResultSet row = s.executeQuery()) {
            Optional<DecisionState> state = Optional.empty();
            if (row.next()) {
              Array reasons = row.getArray(6);
              OffsetDateTime deadline = row.getObject(7, OffsetDateTime.class);
              String supersedes = row.getString(8);
              state =
                  Optional.of(
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
            c.commit();
            return state;
          }
        }
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
