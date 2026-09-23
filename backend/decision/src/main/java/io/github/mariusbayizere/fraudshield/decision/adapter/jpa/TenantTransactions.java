package io.github.mariusbayizere.fraudshield.decision.adapter.jpa;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction scoped to one institution (ADR 0068, following ADR 0071).
 *
 * <p>Row-level security is what keeps one institution's configuration invisible to another, and it
 * applies only when {@code fraudshield.institution_id} is set on the connection the statements run
 * on. With JPA the transaction manager is a {@code JpaTransactionManager}, which hands its JDBC
 * connection to {@link JdbcTemplate}, so the {@code set_config} below and every Hibernate statement
 * in the same transaction share one connection. The third argument to {@code set_config} is true:
 * the setting is transaction-local, so a pooled connection never carries a tenant to the next
 * caller.
 */
public final class TenantTransactions {

  private final TransactionTemplate transactions;
  private final JdbcTemplate jdbc;

  /**
   * Creates the helper.
   *
   * @param transactions a template over the JPA transaction manager
   * @param jdbc a template over the same data source
   */
  public TenantTransactions(TransactionTemplate transactions, JdbcTemplate jdbc) {
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  /**
   * Runs work as one institution.
   *
   * @param institutionId the institution
   * @param work what to do
   * @param <T> what it returns
   * @return the work's result
   */
  public <T> T as(UUID institutionId, Supplier<T> work) {
    return transactions.execute(
        status -> {
          jdbc.queryForObject(
              "SELECT set_config('fraudshield.institution_id', ?, true)",
              String.class,
              institutionId.toString());
          return work.get();
        });
  }
}
