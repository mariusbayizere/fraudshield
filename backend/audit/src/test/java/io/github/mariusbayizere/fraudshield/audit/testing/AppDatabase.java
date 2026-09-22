package io.github.mariusbayizere.fraudshield.audit.testing;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring JDBC plumbing for one FraudShield role of a test database, without an application context.
 *
 * @param jdbc JDBC template
 * @param transactions transaction template
 * @param tenants tenant transaction runner
 */
public record AppDatabase(
    JdbcTemplate jdbc, TransactionTemplate transactions, TenantTransactions tenants) {

  /**
   * Plumbing for a role.
   *
   * @param db the test database
   * @param role the role
   * @return the plumbing
   */
  public static AppDatabase of(TestDatabase db, String role) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(db.url(), role, db.password(role));
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TransactionTemplate transactions =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    return new AppDatabase(jdbc, transactions, new TenantTransactions(transactions, jdbc, 5));
  }
}
