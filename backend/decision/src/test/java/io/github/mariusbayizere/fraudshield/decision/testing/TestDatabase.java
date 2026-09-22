package io.github.mariusbayizere.fraudshield.decision.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A freshly bootstrapped and migrated FraudShield database for decision-module tests: the same
 * image, bootstrap script and migrations as {@code backend/persistence} (read from its source
 * tree), with the four roles given random passwords. One container per JVM, one database per
 * instance.
 */
public final class TestDatabase {

  /** Image of the TimescaleDB service in docker-compose.yml. */
  public static final String IMAGE = "timescale/timescaledb:2.30.0-pg16";

  private static final Path PERSISTENCE =
      ContractSchemas.REPOSITORY.resolve("backend/persistence/src/main/resources/db");
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Map<String, String> PASSWORDS =
      Map.of(
          "fs_migrator",
          secret(),
          "fs_app",
          secret(),
          "fs_app_readonly",
          secret(),
          "fs_compliance_ro",
          secret());
  private static PostgreSQLContainer container;

  private final String name;

  private TestDatabase(String name) {
    this.name = name;
  }

  /**
   * Creates, bootstraps and migrates a new database.
   *
   * @return the database
   */
  public static synchronized TestDatabase create() {
    if (container == null) {
      container =
          new PostgreSQLContainer(
                  DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
              .withDatabaseName("postgres")
              .withUsername("postgres")
              .withPassword(secret());
      container.start();
    }
    String name = "fs_decision_" + secret().substring(0, 12);
    try (Connection admin =
            DriverManager.getConnection(
                container.getJdbcUrl(), "postgres", container.getPassword());
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    TestDatabase db = new TestDatabase(name);
    try (Connection superuser = db.superuser();
        Statement statement = superuser.createStatement()) {
      statement.execute(
          Files.readString(PERSISTENCE.resolve("bootstrap/bootstrap.sql"), StandardCharsets.UTF_8));
      for (Map.Entry<String, String> role : PASSWORDS.entrySet()) {
        statement.execute("ALTER ROLE " + role.getKey() + " PASSWORD '" + role.getValue() + "'");
      }
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("could not bootstrap the test database", e);
    }
    Flyway.configure()
        .dataSource(db.url(), "fs_migrator", PASSWORDS.get("fs_migrator"))
        .schemas("fraudshield")
        .defaultSchema("fraudshield")
        .createSchemas(false)
        .locations("filesystem:" + PERSISTENCE.resolve("migration"))
        .load()
        .migrate();
    return db;
  }

  /**
   * JDBC URL of this database.
   *
   * @return the URL
   */
  public String url() {
    String base = container.getJdbcUrl();
    int query = base.indexOf('?');
    String path = query < 0 ? base : base.substring(0, query);
    return path.substring(0, path.lastIndexOf('/') + 1) + name;
  }

  /**
   * A connection as the container's superuser.
   *
   * @return the connection
   * @throws SQLException on failure
   */
  public Connection superuser() throws SQLException {
    return DriverManager.getConnection(url(), "postgres", container.getPassword());
  }

  /**
   * A data source for a FraudShield role.
   *
   * @param role for example {@code fs_app}
   * @return the data source
   */
  public DataSource dataSource(String role) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(url());
    dataSource.setUser(role);
    dataSource.setPassword(PASSWORDS.get(role));
    return dataSource;
  }

  /**
   * The generated password of a role.
   *
   * @param role for example {@code fs_app}
   * @return its password
   */
  public String password(String role) {
    return PASSWORDS.get(role);
  }

  /**
   * The container, for chaos tests that pause it.
   *
   * @return the container
   */
  public static PostgreSQLContainer container() {
    return container;
  }

  /**
   * Creates a synthetic institution with version-1 thresholds and circuit-breaker settings, as demo
   * seeding does.
   *
   * @param id institution id
   * @throws SQLException on failure
   */
  public void institution(UUID id) throws SQLException {
    try (Connection c = superuser()) {
      c.setAutoCommit(false);
      exec(
          c,
          "INSERT INTO fraudshield.institutions (id, code, name, country, synthetic)"
              + " VALUES (?, ?, 'Synthetic test bank', 'RW', true)",
          id,
          "test-" + id.toString().substring(0, 8));
      exec(
          c,
          "INSERT INTO fraudshield.risk_threshold_versions (institution_id, version,"
              + " effective_at) VALUES (?, 1, now() - interval '1 day')",
          id);
      for (String channel :
          new String[] {
            "MOBILE_MONEY", "CARD", "AGENT_BANKING", "USSD", "ONLINE", "BANK_TRANSFER"
          }) {
        exec(
            c,
            "INSERT INTO fraudshield.risk_thresholds (institution_id, version, channel,"
                + " medium_threshold, high_threshold, medium_timeout_policy)"
                + " VALUES (?, 1, ?, 0.60, 0.85, 'RELEASE_WITH_TIMEOUT_LABEL')",
            id,
            channel);
      }
      exec(
          c,
          "INSERT INTO fraudshield.mcc_circuit_breaker_settings_versions (institution_id,"
              + " version, fraud_rate_threshold, window_minutes, minimum_transactions,"
              + " clean_reset_minutes, effective_at) VALUES (?, 1, 0.05, 15, 100, 60,"
              + " now() - interval '1 day')",
          id);
      c.commit();
    }
  }

  /**
   * Runs one statement as the superuser.
   *
   * @param c connection
   * @param sql statement with {@code ?} parameters
   * @param parameters parameter values
   * @throws SQLException on failure
   */
  public static void exec(Connection c, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = c.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) {
        statement.setObject(i + 1, parameters[i]);
      }
      statement.execute();
    }
  }

  private static String secret() {
    byte[] bytes = new byte[24];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }
}
