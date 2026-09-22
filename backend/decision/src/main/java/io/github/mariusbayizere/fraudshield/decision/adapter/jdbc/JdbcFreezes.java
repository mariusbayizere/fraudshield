package io.github.mariusbayizere.fraudshield.decision.adapter.jdbc;

import io.github.mariusbayizere.fraudshield.decision.application.port.FreezePort;
import io.github.mariusbayizere.fraudshield.decision.domain.FreezePolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The freeze counter's fallback while Redis is down (C.4): HIGH decisions already persisted as
 * auto-block events in the sliding hour, plus the one being decided. Persistence lags the spool by
 * the drain delay, so in degraded mode a freeze can come one decision late; it never comes early.
 * Without Redis's {@code NX} two instances can both freeze; both events are recorded and the
 * account is frozen either way.
 */
public final class JdbcFreezes implements FreezePort {

  private static final String COUNT_BLOCKS =
      """
      SELECT count(*), coalesce((SELECT e.event = 'FROZEN' FROM account_freeze_events e
      WHERE e.account_token = ? ORDER BY e.occurred_at DESC LIMIT 1), false)
      FROM auto_block_events WHERE account_token = ? AND blocked_at > ? AND blocked_at <= ?
      AND transaction_id <> ?
      """;

  private final DataSource dataSource;

  /**
   * Creates the fallback.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JdbcFreezes(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public FreezeCheck recordHigh(
      UUID institutionId, String accountToken, UUID transactionId, Instant decidedAt) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      c.setReadOnly(true);
      try {
        Tenant.use(c, institutionId);
        try (PreparedStatement s = c.prepareStatement(COUNT_BLOCKS)) {
          s.setString(1, accountToken);
          s.setString(2, accountToken);
          s.setObject(3, Tenant.utc(decidedAt.minus(FreezePolicy.WINDOW)));
          s.setObject(4, Tenant.utc(decidedAt));
          s.setObject(5, transactionId);
          try (ResultSet row = s.executeQuery()) {
            row.next();
            int count = row.getInt(1) + 1;
            boolean alreadyFrozen = row.getBoolean(2);
            c.commit();
            return new FreezeCheck(
                count, !alreadyFrozen && count >= FreezePolicy.HIGH_DECISIONS_TO_FREEZE);
          }
        }
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new IllegalStateException("the freeze counter is unavailable", e);
    }
  }
}
