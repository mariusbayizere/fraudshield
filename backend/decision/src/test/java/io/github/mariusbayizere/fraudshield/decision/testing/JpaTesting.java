package io.github.mariusbayizere.fraudshield.decision.testing;

import io.github.mariusbayizere.fraudshield.decision.adapter.jpa.BreakerSettingsRepository;
import io.github.mariusbayizere.fraudshield.decision.adapter.jpa.JpaConfiguration;
import io.github.mariusbayizere.fraudshield.decision.adapter.jpa.RuleRepository;
import io.github.mariusbayizere.fraudshield.decision.adapter.jpa.TenantTransactions;
import io.github.mariusbayizere.fraudshield.decision.adapter.jpa.ThresholdRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.Map;
import javax.sql.DataSource;
import org.hibernate.stat.Statistics;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The JPA layer of the decision module, built without a Spring Boot application, so the adapter
 * tests stay plain JUnit against Testcontainers.
 *
 * <p>It uses the settings the application runs with (ADR 0068): {@code ddl-auto=validate}, so a
 * mapping that no longer matches the migrations fails the test the way it would fail a start-up,
 * and Hibernate statistics on, so a test can count the statements a read makes.
 */
public final class JpaTesting implements AutoCloseable {

  private final LocalContainerEntityManagerFactoryBean factory;
  private final EntityManagerFactory entityManagerFactory;
  private final JpaRepositoryFactory repositories;
  private final TenantTransactions tenants;

  /**
   * Builds the layer over a data source.
   *
   * @param dataSource connections as {@code fs_app}
   */
  public JpaTesting(DataSource dataSource) {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(dataSource);
    factory.setPackagesToScan("io.github.mariusbayizere.fraudshield.decision.adapter.jpa");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(
        Map.of(
            "hibernate.hbm2ddl.auto", "validate",
            "hibernate.generate_statistics", "true",
            "hibernate.jdbc.time_zone", "UTC",
            "hibernate.default_schema", "fraudshield"));
    factory.afterPropertiesSet();
    entityManagerFactory = factory.getObject();
    EntityManager shared =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    repositories = new JpaRepositoryFactory(shared);
    tenants =
        new TenantTransactions(
            new TransactionTemplate(new JpaTransactionManager(entityManagerFactory)),
            new JdbcTemplate(dataSource));
  }

  /**
   * A repository of the configuration layer.
   *
   * @param type the repository interface
   * @param <T> its type
   * @return the repository
   */
  public <T> T repository(Class<T> type) {
    return repositories.getRepository(type);
  }

  /**
   * The configuration adapter as the application wires it.
   *
   * @param clock clock for effective times
   * @return the adapter
   */
  public JpaConfiguration configuration(Clock clock) {
    return new JpaConfiguration(
        tenants,
        repository(ThresholdRepository.class),
        repository(RuleRepository.class),
        repository(BreakerSettingsRepository.class),
        clock);
  }

  /**
   * Transactions scoped to one institution.
   *
   * @return the helper
   */
  public TenantTransactions tenants() {
    return tenants;
  }

  /**
   * Hibernate's statistics, for counting statements.
   *
   * @return the statistics
   */
  public Statistics statistics() {
    return entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
  }

  @Override
  public void close() {
    factory.destroy();
  }
}
