package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatusPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The durable freeze flag: the latest event in V5 {@code account_freeze_events} (FR-03-06).
 * Restores Redis after a flush and answers alone while Redis is down (C.4).
 */
public final class JdbcAccountStatus implements AccountStatusPort {

  private static final String SELECT_LATEST_FREEZE_EVENT =
      """
      SELECT event = 'FROZEN' FROM account_freeze_events WHERE account_token = ?
      ORDER BY occurred_at DESC LIMIT 1
      """;

  private final DataSource dataSource;

  /**
   * Creates the reader.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JdbcAccountStatus(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public boolean frozen(UUID institutionId, String accountToken) {
    try {
      return read(institutionId, accountToken);
    } catch (SQLException e) {
      throw new IllegalStateException("the freeze flag could not be read from PostgreSQL", e);
    }
  }

  private boolean read(UUID institutionId, String accountToken) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, institutionId);
        try (PreparedStatement s = c.prepareStatement(SELECT_LATEST_FREEZE_EVENT)) {
          s.setQueryTimeout(1);
          s.setString(1, accountToken);
          try (ResultSet row = s.executeQuery()) {
            boolean frozen = row.next() && row.getBoolean(1);
            c.commit();
            return frozen;
          }
        }
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
