package io.github.mariusbayizere.fraudshield.persistence.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.mariusbayizere.fraudshield.persistence.DatabaseToolApplication;
import io.github.mariusbayizere.fraudshield.persistence.TestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A database seeded with demo data is refused by any later process that is not dev or demo,
 * whatever its beans or lazy-initialisation setting, and it keeps the banner on (review MAJOR-3,
 * PB-15, PB-16, ADR 0019, D-21).
 */
@Tag("requires-docker")
@Tag("D-21")
class SyntheticDataGuardTest {

  private static final String PASSWORD = "Status-Gen3rated!pw";

  private static TestDatabase clean;
  private static TestDatabase seeded;
  private static String unmigratedUrl;

  /** An application without auto-configuration: no DataSource, JdbcTemplate or Flyway beans. */
  @SpringBootConfiguration
  static class BareApplication {}

  @BeforeAll
  static void createDatabases() throws SQLException {
    clean = TestDatabase.create();
    seeded = TestDatabase.create();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(seeded.url(), "fs_migrator", seeded.password("fs_migrator"));
    try (io.github.mariusbayizere.fraudshield.persistence.schema.SchemaJpa jpa =
        new io.github.mariusbayizere.fraudshield.persistence.schema.SchemaJpa(dataSource)) {
      new DemoDataSeeder(
              new DemoSeedProperties(
                  true,
                  new DemoSeedProperties.Passwords(
                      PASSWORD, PASSWORD, PASSWORD, PASSWORD, PASSWORD),
                  "fsk_dev_a1b2c3d4e5f6_" + "Zx9".repeat(14) + "Q",
                  "1e".repeat(32)),
              new JdbcTemplate(dataSource),
              jpa.transactions(),
              Clock.systemUTC(),
              jpa.repository(
                  io.github.mariusbayizere.fraudshield.persistence.schema.InstitutionRepository
                      .class),
              jpa.repository(
                  io.github.mariusbayizere.fraudshield.persistence.schema.DemoUserRepository.class),
              jpa.repository(
                  io.github.mariusbayizere.fraudshield.persistence.schema.ThresholdVersionRepository
                      .class),
              jpa.repository(
                  io.github.mariusbayizere.fraudshield.persistence.schema.ThresholdSeedRepository
                      .class),
              jpa.repository(
                  io.github.mariusbayizere.fraudshield.persistence.schema
                      .BreakerSettingsSeedRepository.class),
              jpa.entityManager())
          .seed();
    }
    String name = "fs_unmigrated_" + HexFormat.of().toHexDigits(System.nanoTime());
    try (Connection admin = seeded.superuser();
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    }
    unmigratedUrl = seeded.url().substring(0, seeded.url().lastIndexOf('/') + 1) + name;
  }

  private static ConfigurableApplicationContext start(
      Class<?> application, TestDatabase database, String url, String... arguments) {
    List<String> all = new ArrayList<>();
    all.add("--spring.datasource.url=" + url);
    all.add("--spring.datasource.username=fs_migrator");
    all.add("--spring.datasource.password=" + database.password("fs_migrator"));
    all.addAll(List.of(arguments));
    return new SpringApplicationBuilder(application)
        .web(WebApplicationType.NONE)
        .run(all.toArray(String[]::new));
  }

  /** Startup fails, with the guard's exception somewhere in the cause chain. */
  private static void assertRefused(ThrowingCallable startup, String message) {
    Throwable thrown = catchThrowable(startup);
    assertThat(thrown).as("startup must fail").isNotNull();
    List<Throwable> chain = new ArrayList<>();
    for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
    }
    assertThat(chain)
        .anySatisfy(
            t ->
                assertThat(t)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(message));
  }

  private static ConfigurableApplicationContext tool(TestDatabase database, String... arguments) {
    return start(DatabaseToolApplication.class, database, database.url(), arguments);
  }

  @Test
  void productionRefusesToStartAgainstSeededDatabase() {
    assertRefused(() -> tool(seeded, "--spring.profiles.active=prod"), "synthetic demo data");
    assertRefused(() -> tool(seeded), "synthetic demo data");
  }

  @Test
  void lazyInitialisationDoesNotSkipTheCheck() {
    assertRefused(
        () ->
            tool(seeded, "--spring.profiles.active=prod", "--spring.main.lazy-initialization=true"),
        "synthetic demo data");
  }

  @Test
  void applicationWithoutJdbcTemplateIsStillChecked() {
    assertRefused(
        () -> start(BareApplication.class, seeded, seeded.url(), "--spring.profiles.active=prod"),
        "synthetic demo data");
  }

  @Test
  void unreachableDatabaseFailsClosed() {
    assertRefused(
        () ->
            start(
                BareApplication.class,
                clean,
                "jdbc:postgresql://127.0.0.1:1/fraudshield",
                "--spring.profiles.active=prod"),
        "cannot check the database");
  }

  @Test
  void demoProcessesStartAndReportSyntheticData() {
    try (ConfigurableApplicationContext context = tool(seeded, "--spring.profiles.active=demo")) {
      assertThat(context.getBean(SyntheticDataStatus.class).syntheticData()).isTrue();
    }
  }

  @Test
  void productionAgainstCleanOrUnmigratedDatabaseStartsWithoutBanner() {
    try (ConfigurableApplicationContext context = tool(clean, "--spring.profiles.active=prod")) {
      assertThat(context.getBean(SyntheticDataStatus.class).syntheticData()).isFalse();
    }
    try (ConfigurableApplicationContext context =
        start(BareApplication.class, clean, unmigratedUrl, "--spring.profiles.active=prod")) {
      assertThat(context.getBean(SyntheticDataStatus.class).syntheticData()).isFalse();
    }
  }

  @Test
  void theMarkerIsVisibleToEveryApplicationRoleWithoutTenant() {
    for (String role : Stream.of("fs_app", "fs_app_readonly", "fs_compliance_ro").toList()) {
      Boolean marker =
          new JdbcTemplate(new DriverManagerDataSource(seeded.url(), role, seeded.password(role)))
              .queryForObject("SELECT fraudshield.deployment_has_synthetic_data()", Boolean.class);
      assertThat(marker).as(role).isTrue();
    }
  }
}
