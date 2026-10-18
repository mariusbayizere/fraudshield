package io.github.mariusbayizere.fraudshield.audit.jdbc;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a database transaction bound to one institution (ADR 0017).
 *
 * <p>Row-level security reads the institution from {@code fraudshield.institution_id}, set here
 * with {@code set_config(..., true)} so it lasts exactly one transaction and can never leak to the
 * next user of a pooled connection. Without it every tenant query returns nothing and every insert
 * fails, so a forgotten call fails closed.
 *
 * <p>The audit hash chain refuses, with SQLSTATE 40001, a transaction that started before the last
 * row of its chain was written (V8). Such a transaction is retried from the start, a bounded number
 * of times; the work must therefore have no side effects outside the transaction except through
 * after-commit callbacks.
 */
public final class TenantTransactions {

  private static final String SERIALIZATION_FAILURE = "40001";

  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;
  private final int maxAttempts;

  /**
   * Creates the runner.
   *
   * @param transactions transaction template of the application data source
   * @param jdbc JDBC template of the same data source
   * @param maxAttempts attempts before a serialization failure is rethrown, at least 1
   */
  public TenantTransactions(TransactionTemplate transactions, JdbcTemplate jdbc, int maxAttempts) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.maxAttempts = maxAttempts;
  }

  /**
   * Runs work in a new transaction of the institution and returns its result.
   *
   * @param institutionId the institution
   * @param work the work
   * @param <T> result type
   * @return the result
   */
  public <T> T inTenant(UUID institutionId, Supplier<T> work) {
    Objects.requireNonNull(institutionId, "institutionId");
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(
            status -> {
              jdbc.queryForObject(
                  "SELECT set_config('fraudshield.institution_id', ?, true)",
                  String.class,
                  institutionId.toString());
              return work.get();
            });
      } catch (DataAccessException e) {
        if (attempt >= maxAttempts || !isSerializationFailure(e)) {
          throw e;
        }
      }
    }
  }

  /**
   * Runs work without a result in a new transaction of the institution.
   *
   * @param institutionId the institution
   * @param work the work
   */
  public void runInTenant(UUID institutionId, Runnable work) {
    inTenant(
        institutionId,
        () -> {
          work.run();
          return null;
        });
  }

  static boolean isSerializationFailure(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && SERIALIZATION_FAILURE.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
