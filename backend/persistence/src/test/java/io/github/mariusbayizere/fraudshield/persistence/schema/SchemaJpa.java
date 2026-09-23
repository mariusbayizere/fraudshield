package io.github.mariusbayizere.fraudshield.persistence.schema;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The schema module's JPA layer, built without a Spring Boot application, so the seeder tests stay
 * plain JUnit against Testcontainers.
 *
 * <p>{@code ddl-auto=validate}, as the application runs it (ADR 0068): an entity that no longer
 * matches its migration fails the test rather than being quietly repaired.
 */
public final class SchemaJpa implements AutoCloseable {

  private final LocalContainerEntityManagerFactoryBean factory;
  private final EntityManager shared;
  private final JpaRepositoryFactory repositories;
  private final JpaTransactionManager transactionManager;

  /**
   * Builds the layer over a data source.
   *
   * @param dataSource connections as the role under test
   */
  public SchemaJpa(DataSource dataSource) {
    factory = new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(dataSource);
    factory.setPackagesToScan("io.github.mariusbayizere.fraudshield.persistence.schema");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(
        Map.of(
            "hibernate.hbm2ddl.auto", "validate",
            "hibernate.jdbc.time_zone", "UTC",
            "hibernate.default_schema", "fraudshield"));
    factory.afterPropertiesSet();
    EntityManagerFactory entityManagerFactory = factory.getObject();
    shared = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    repositories = new JpaRepositoryFactory(shared);
    transactionManager = new JpaTransactionManager(entityManagerFactory);
  }

  /**
   * A repository of this module.
   *
   * @param type the repository interface
   * @param <T> its type
   * @return the repository
   */
  public <T> T repository(Class<T> type) {
    return repositories.getRepository(type);
  }

  /**
   * The persistence context, which the seeder flushes before its explicit SQL reads.
   *
   * @return the entity manager
   */
  public EntityManager entityManager() {
    return shared;
  }

  /**
   * A transaction template over the JPA transaction manager, which lends its connection to {@code
   * JdbcTemplate} so {@code set_config} applies to Hibernate's statements too.
   *
   * @return the template
   */
  public TransactionTemplate transactions() {
    return new TransactionTemplate(transactionManager);
  }

  @Override
  public void close() {
    factory.destroy();
  }
}
