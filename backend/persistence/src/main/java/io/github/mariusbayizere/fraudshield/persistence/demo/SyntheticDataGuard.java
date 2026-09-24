package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Refuses to start any Spring Boot application outside the dev and demo profiles against a database
 * that holds synthetic demo data, and publishes {@link SyntheticDataStatus} for the banner (ADR
 * 0019, review MAJOR-3, PB-15, PB-16).
 *
 * <p>Registered in {@code META-INF/spring.factories}, so it does not depend on auto-configuration,
 * on which beans exist or on lazy initialisation. When the context is prepared, before any bean is
 * created, it opens its own JDBC connection from {@code spring.datasource.url}, {@code username}
 * and {@code password} and reads {@code fraudshield.deployment_has_synthetic_data()}. It fails
 * closed:
 *
 * <ul>
 *   <li>a connection or query error stops startup, except that a database without the FraudShield
 *       schema or function yet (not migrated) cannot hold demo data and counts as clean;
 *   <li>a {@link DataSource} bean that the property-based check did not cover (no {@code
 *       spring.datasource.url}, or more than one data source) stops startup during refresh, before
 *       any singleton is created.
 * </ul>
 */
public final class SyntheticDataGuard implements ApplicationListener<ApplicationPreparedEvent> {

  /** Bean name of the published {@link SyntheticDataStatus}. */
  public static final String STATUS_BEAN = "syntheticDataStatus";

  static final String URL_PROPERTY = "spring.datasource.url";

  /** PostgreSQL states for a database that FraudShield migrations have not created yet. */
  private static final Set<String> NOT_MIGRATED = Set.of("42883", "3F000");

  @Override
  public void onApplicationEvent(ApplicationPreparedEvent event) {
    ConfigurableApplicationContext context = event.getApplicationContext();
    Environment environment = context.getEnvironment();
    String url = environment.getProperty(URL_PROPERTY);
    boolean checked = url != null && !url.isBlank();
    boolean database = checked && databaseHasSyntheticData(environment, url);
    SyntheticDataFlag.checkProfiles(environment, database);
    context
        .getBeanFactory()
        .registerSingleton(
            STATUS_BEAN,
            new SyntheticDataStatus(SyntheticDataFlag.isSyntheticData(environment, database)));
    context.addBeanFactoryPostProcessor(beanFactory -> requireCoverage(beanFactory, checked));
  }

  static void requireCoverage(ConfigurableListableBeanFactory beanFactory, boolean checked) {
    String[] dataSources = beanFactory.getBeanNamesForType(DataSource.class, true, false);
    if (dataSources.length > 1 || (dataSources.length == 1 && !checked)) {
      throw new IllegalStateException(
          "Refusing to start: the synthetic-data check (ADR 0019) covers exactly one data source"
              + " configured through "
              + URL_PROPERTY
              + ", but this application defines "
              + dataSources.length
              + " data source bean(s) and the property is "
              + (checked ? "set" : "not set"));
    }
  }

  static boolean databaseHasSyntheticData(Environment environment, String url) {
    Properties credentials = new Properties();
    String username = environment.getProperty("spring.datasource.username");
    String password = environment.getProperty("spring.datasource.password");
    if (username != null && !username.isBlank()) {
      credentials.setProperty("user", username);
    }
    if (password != null) {
      credentials.setProperty("password", password);
    }
    try (Connection connection = DriverManager.getConnection(url, credentials);
        Statement statement = connection.createStatement();
        ResultSet row =
            statement.executeQuery("SELECT fraudshield.deployment_has_synthetic_data()")) {
      return row.next() && row.getBoolean(1);
    } catch (SQLException e) {
      if (NOT_MIGRATED.contains(e.getSQLState())) {
        return false;
      }
      throw new IllegalStateException(
          "Refusing to start: cannot check the database for synthetic demo data (ADR 0019): "
              + e.getMessage(),
          e);
    }
  }
}
