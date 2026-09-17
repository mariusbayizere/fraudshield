package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.persistence.DatabaseToolApplication;
import io.github.mariusbayizere.fraudshield.persistence.TestDatabase;
import java.time.Clock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A database seeded with demo data is refused by any later process that is not dev or demo, and it
 * keeps the banner on (review MAJOR-3, ADR 0019, D-21).
 */
@Tag("requires-docker")
@Tag("D-21")
class SyntheticDataStatusTest {

  private static final String PASSWORD = "Status-Gen3rated!pw";

  private static TestDatabase clean;
  private static TestDatabase seeded;

  @BeforeAll
  static void createDatabases() {
    clean = TestDatabase.create();
    seeded = TestDatabase.create();
    DriverManagerDataSource dataSource = dataSource(seeded);
    new DemoDataSeeder(
            new DemoSeedProperties(
                true,
                new DemoSeedProperties.Passwords(PASSWORD, PASSWORD, PASSWORD, PASSWORD, PASSWORD),
                "fsk_dev_a1b2c3d4e5f6_" + "Zx9".repeat(14) + "Q",
                "1e".repeat(32)),
            new JdbcTemplate(dataSource),
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
            Clock.systemUTC())
        .seed();
  }

  private static DriverManagerDataSource dataSource(TestDatabase database) {
    return new DriverManagerDataSource(
        database.url(), "fs_migrator", database.password("fs_migrator"));
  }

  /** Starts the real database tool against a test database as the application role would. */
  private static ConfigurableApplicationContext start(TestDatabase database, String... profiles) {
    return new SpringApplicationBuilder(DatabaseToolApplication.class)
        .web(WebApplicationType.NONE)
        .profiles(profiles)
        .run(
            "--spring.datasource.url=" + database.url(),
            "--spring.datasource.username=fs_migrator",
            "--spring.datasource.password=" + database.password("fs_migrator"));
  }

  @Test
  void markerIsVisibleToEveryApplicationRoleWithoutTenant() {
    for (String role : new String[] {"fs_app", "fs_app_readonly", "fs_compliance_ro"}) {
      JdbcTemplate jdbc =
          new JdbcTemplate(new DriverManagerDataSource(seeded.url(), role, seeded.password(role)));
      assertThat(SyntheticDataFlag.databaseHasSyntheticData(jdbc)).as(role).isTrue();
    }
    assertThat(SyntheticDataFlag.databaseHasSyntheticData(new JdbcTemplate(dataSource(clean))))
        .isFalse();
  }

  @Test
  void productionProcessRefusesToStartAgainstSeededDatabase() {
    assertThatThrownBy(() -> start(seeded, "prod"))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .rootCause()
        .hasMessageContaining("synthetic demo data");
    assertThatThrownBy(() -> start(seeded)).hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void demoProcessesStartAndReportSyntheticData() {
    try (ConfigurableApplicationContext context = start(seeded, "demo")) {
      assertThat(context.getBean(SyntheticDataStatus.class).syntheticData()).isTrue();
    }
  }

  @Test
  void productionAgainstCleanDatabaseStartsWithoutBanner() {
    try (ConfigurableApplicationContext context = start(clean, "prod")) {
      assertThat(context.getBean(SyntheticDataStatus.class).syntheticData()).isFalse();
    }
  }
}
