package io.github.mariusbayizere.fraudshield.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A freshly bootstrapped and migrated FraudShield database for tests (ADR 0017).
 *
 * <p>By default a TimescaleDB container with the same image as {@code docker-compose.yml} is
 * started once per JVM (tests using this class are tagged {@code requires-docker}, ADR 0010). When
 * {@code FRAUDSHIELD_TEST_POSTGRES_URL} names a PostgreSQL 16 server with TimescaleDB and a
 * superuser ({@code jdbc:postgresql://host:port/postgres?user=...&password=...}), that server is
 * used instead. Every instance creates its own database, runs the bootstrap script as the
 * superuser, sets random passwords on the four roles and applies the Flyway migrations as {@code
 * fs_migrator}.
 */
public final class TestDatabase {

  /** Image of the TimescaleDB service in docker-compose.yml. */
  public static final String IMAGE = "timescale/timescaledb:2.30.0-pg16";

  private static final String EXTERNAL_URL_VARIABLE = "FRAUDSHIELD_TEST_POSTGRES_URL";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Map<String, String> PASSWORDS =
      Map.of(
          "fs_migrator", randomSecret(),
          "fs_app", randomSecret(),
          "fs_app_readonly", randomSecret(),
          "fs_compliance_ro", randomSecret());
  private static PostgreSQLContainer container;

  private final String adminBaseUrl;
  private final String database;

  private TestDatabase(String adminBaseUrl, String database) {
    this.adminBaseUrl = adminBaseUrl;
    this.database = database;
  }

  /**
   * Creates, bootstraps and migrates a new database.
   *
   * @return the database
   */
  public static synchronized TestDatabase create() {
    String adminUrl = System.getenv(EXTERNAL_URL_VARIABLE);
    if (adminUrl == null || adminUrl.isBlank()) {
      adminUrl = containerAdminUrl();
    }
    String name = "fs_test_" + HexFormat.of().formatHex(randomBytes(6)).toLowerCase(Locale.ROOT);
    try (Connection admin = DriverManager.getConnection(adminUrl);
        Statement statement = admin.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the test database", e);
    }
    TestDatabase db = new TestDatabase(adminUrl, name);
    db.bootstrap();
    Flyway.configure()
        .dataSource(db.url(), "fs_migrator", PASSWORDS.get("fs_migrator"))
        .schemas("fraudshield")
        .defaultSchema("fraudshield")
        .createSchemas(false)
        .locations("classpath:db/migration")
        .load()
        .migrate();
    return db;
  }

  private static String containerAdminUrl() {
    if (container == null) {
      container =
          new PostgreSQLContainer(
                  DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
              .withDatabaseName("postgres")
              .withUsername("postgres")
              .withPassword(randomSecret());
      container.start();
    }
    return container.getJdbcUrl()
        + (container.getJdbcUrl().contains("?") ? "&" : "?")
        + "user=postgres&password="
        + container.getPassword();
  }

  private void bootstrap() {
    try (InputStream script = getClass().getResourceAsStream("/db/bootstrap/bootstrap.sql");
        Connection admin = superuser();
        Statement statement = admin.createStatement()) {
      String sql =
          new String(Objects.requireNonNull(script).readAllBytes(), StandardCharsets.UTF_8);
      statement.execute(sql);
      for (Map.Entry<String, String> role : PASSWORDS.entrySet()) {
        statement.execute("ALTER ROLE " + role.getKey() + " PASSWORD '" + role.getValue() + "'");
      }
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("could not bootstrap the test database", e);
    }
  }

  /**
   * JDBC URL of the database, without credentials.
   *
   * @return the URL
   */
  public String url() {
    int query = adminBaseUrl.indexOf('?');
    String base = query < 0 ? adminBaseUrl : adminBaseUrl.substring(0, query);
    return base.substring(0, base.lastIndexOf('/') + 1) + database;
  }

  /**
   * A connection as a superuser, which can bypass every control (used to set up and to tamper).
   *
   * @return the connection
   * @throws SQLException if the connection fails
   */
  public Connection superuser() throws SQLException {
    int query = adminBaseUrl.indexOf('?');
    return DriverManager.getConnection(url() + (query < 0 ? "" : adminBaseUrl.substring(query)));
  }

  /**
   * A connection as one of the FraudShield roles.
   *
   * @param role fs_migrator, fs_app, fs_app_readonly or fs_compliance_ro
   * @return the connection
   * @throws SQLException if the connection fails
   */
  public Connection as(String role) throws SQLException {
    return DriverManager.getConnection(
        url(), role, Objects.requireNonNull(PASSWORDS.get(role), role));
  }

  /**
   * The generated password of a role (for tests of components that open their own connections).
   *
   * @param role the role
   * @return its password
   */
  public String password(String role) {
    return PASSWORDS.get(role);
  }

  private static String randomSecret() {
    return HexFormat.of().formatHex(randomBytes(24));
  }

  private static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
