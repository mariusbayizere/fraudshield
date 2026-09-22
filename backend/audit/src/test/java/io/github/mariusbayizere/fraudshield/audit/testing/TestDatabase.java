package io.github.mariusbayizere.fraudshield.audit.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A freshly bootstrapped and migrated FraudShield database for the audit, auth and admin tests,
 * built exactly like the persistence module's (ADR 0017): the bootstrap script as a superuser, then
 * the Flyway migrations as {@code fs_migrator}, read from the persistence module's sources so every
 * module tests against the one schema.
 *
 * <p>By default a TimescaleDB container with the image of {@code docker-compose.yml} starts once
 * per JVM (tests using it are tagged {@code requires-docker}, ADR 0010). {@code
 * FRAUDSHIELD_TEST_POSTGRES_URL} names an existing PostgreSQL 16 server with TimescaleDB and a
 * superuser instead.
 */
public final class TestDatabase {

  /** Image of the TimescaleDB service in docker-compose.yml. */
  public static final String IMAGE = "timescale/timescaledb:2.30.0-pg16";

  private static final Path PERSISTENCE_RESOURCES =
      Path.of("..", "persistence", "src", "main", "resources", "db");
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
        .locations("filesystem:" + PERSISTENCE_RESOURCES.resolve("migration"))
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
    try (Connection admin = superuser();
        Statement statement = admin.createStatement()) {
      statement.execute(
          Files.readString(
              PERSISTENCE_RESOURCES.resolve("bootstrap").resolve("bootstrap.sql"),
              StandardCharsets.UTF_8));
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
   * The generated password of a role.
   *
   * @param role the role
   * @return its password
   */
  public String password(String role) {
    return Objects.requireNonNull(PASSWORDS.get(role), role);
  }

  /**
   * Inserts an institution as the superuser.
   *
   * @param code institution code
   * @return its ID
   */
  public UUID createInstitution(String code) {
    UUID id = UUID.randomUUID();
    try (Connection admin = superuser();
        var insert =
            admin.prepareStatement(
                "INSERT INTO fraudshield.institutions (id, code, name, country)"
                    + " VALUES (?, ?, ?, 'RW')")) {
      insert.setObject(1, id);
      insert.setString(2, code);
      insert.setString(3, "Synthetic " + code);
      insert.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("could not create institution " + code, e);
    }
    return id;
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
