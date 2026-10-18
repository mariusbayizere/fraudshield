package io.github.mariusbayizere.fraudshield.auth.testing;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.auth.persistence.ApiKeyJpaRepository;
import io.github.mariusbayizere.fraudshield.auth.persistence.OfficeIpRangeJpaRepository;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserEntity;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Map;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The staff-identity persistence stack without an application context: Hibernate with {@code
 * ddl-auto=validate} over one role of a test database, a JPA transaction manager shared by JDBC and
 * JPA, tenant transactions and the Spring Data repositories. Component tests that run two
 * independent "instances" build one stack each.
 *
 * @param jdbc JDBC template
 * @param tenants tenant transactions on the JPA transaction manager
 * @param entities shared entity manager
 * @param users user repository
 * @param apiKeys API-key repository
 * @param ranges office-range repository
 */
public record JpaTestStack(
    JdbcTemplate jdbc,
    TenantTransactions tenants,
    EntityManager entities,
    StaffUserJpaRepository users,
    ApiKeyJpaRepository apiKeys,
    OfficeIpRangeJpaRepository ranges) {

  /**
   * Builds a stack.
   *
   * @param db the test database
   * @param role the database role
   * @return the stack
   */
  public static JpaTestStack of(TestDatabase db, String role) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(db.url(), role, db.password(role));
    LocalContainerEntityManagerFactoryBean factoryBean =
        new LocalContainerEntityManagerFactoryBean();
    factoryBean.setDataSource(dataSource);
    factoryBean.setPackagesToScan(StaffUserEntity.class.getPackageName());
    factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factoryBean.setJpaPropertyMap(
        Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.default_schema", "fraudshield"));
    factoryBean.afterPropertiesSet();
    EntityManagerFactory factory = factoryBean.getObject();
    JpaTransactionManager transactionManager = new JpaTransactionManager(factory);
    transactionManager.setDataSource(dataSource);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    EntityManager shared = SharedEntityManagerCreator.createSharedEntityManager(factory);
    JpaRepositoryFactory repositories = new JpaRepositoryFactory(shared);
    return new JpaTestStack(
        jdbc,
        new TenantTransactions(new TransactionTemplate(transactionManager), jdbc, 5),
        shared,
        repositories.getRepository(StaffUserJpaRepository.class),
        repositories.getRepository(ApiKeyJpaRepository.class),
        repositories.getRepository(OfficeIpRangeJpaRepository.class));
  }
}
