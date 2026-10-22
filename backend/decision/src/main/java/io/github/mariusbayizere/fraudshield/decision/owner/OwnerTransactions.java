package io.github.mariusbayizere.fraudshield.decision.owner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The runner for the decision and notify modules' owner requests (decision-owner contract §2). One
 * transaction at READ COMMITTED, scoped to one institution, with {@code lock_timeout} 2 s and
 * {@code statement_timeout} 5 s set with {@code SET LOCAL} inside it, never for the role: the sink
 * shares the role, and a role-level lock timeout would dead-letter its first decisions (review 4,
 * finding 7). A deadlock (40P01) or a serialization failure (40001) is retried from the start, a
 * bounded number of times; the work has no effect outside PostgreSQL before the commit.
 */
public final class OwnerTransactions {

  private static final String SET_UP =
      "SELECT set_config('fraudshield.institution_id', ?, true),"
          + " set_config('lock_timeout', '2s', true), set_config('statement_timeout', '5s', true)";

  private final DataSource dataSource;
  private final int maxAttempts;

  /** Work on one transaction's connection. */
  @FunctionalInterface
  public interface Work<T> {
    /**
     * Runs on the transaction's connection.
     *
     * @param c the connection; the runner commits or rolls back
     * @return the result
     * @throws SQLException on a database error
     */
    T run(Connection c) throws SQLException;
  }

  /**
   * Creates the runner.
   *
   * @param dataSource connections as {@code fs_app}
   * @param maxAttempts attempts before a 40001 or 40P01 is rethrown, at least 1
   */
  public OwnerTransactions(DataSource dataSource, int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.maxAttempts = maxAttempts;
  }

  /**
   * Runs work in a new transaction of the institution.
   *
   * @param institutionId the institution
   * @param work the work
   * @param <T> result type
   * @return the result
   * @throws SQLException when the work fails, after the retries
   */
  public <T> T inTenant(UUID institutionId, Work<T> work) throws SQLException {
    Objects.requireNonNull(institutionId, "institutionId");
    for (int attempt = 1; ; attempt++) {
      try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try {
          try (PreparedStatement s = c.prepareStatement(SET_UP)) {
            s.setString(1, institutionId.toString());
            s.execute();
          }
          T result = work.run(c);
          c.commit();
          return result;
        } catch (SQLException | RuntimeException e) {
          c.rollback();
          throw e;
        }
      } catch (SQLException e) {
        if (attempt >= maxAttempts || !retried(e.getSQLState())) {
          throw e;
        }
      }
    }
  }

  /**
   * Whether the runner retries this SQLSTATE: a serialization failure or a deadlock.
   *
   * @param sqlState the state
   * @return true for 40001 and 40P01
   */
  public static boolean retried(String sqlState) {
    return "40001".equals(sqlState) || "40P01".equals(sqlState);
  }
}
