package io.github.mariusbayizere.fraudshield.notify.vault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A bootstrapped and migrated PII vault database, from the same scripts the deployment uses and
 * nothing more (no grant is added here that the shipped bootstrap does not make) ({@code
 * backend/persistence/src/main/resources/db/vault}). A separate instance from the main database, as
 * docker-compose has it: the point of D-20 is that the two do not share a server, a role or a
 * backup.
 */
public final class TestVault implements AutoCloseable {

  /** Image of the pii-vault service in docker-compose.yml. */
  public static final String IMAGE = "postgres:16.15";

  private static final Path VAULT =
      Path.of("..", "persistence", "src", "main", "resources", "db", "vault");
  private static final SecureRandom RANDOM = new SecureRandom();

  private final PostgreSQLContainer container;
  private final String migratorPassword = secret();
  private final String vaultPassword = secret();

  /** Starts, bootstraps and migrates a vault. */
  public TestVault() {
    container =
        new PostgreSQLContainer(DockerImageName.parse(IMAGE))
            .withDatabaseName("fraudshield_pii")
            .withUsername("postgres")
            .withPassword(secret());
    container.start();
    try (Connection superuser = superuser();
        Statement statement = superuser.createStatement()) {
      statement.execute(Files.readString(VAULT.resolve("bootstrap.sql"), StandardCharsets.UTF_8));
      statement.execute("ALTER ROLE fs_vault_migrator PASSWORD '" + migratorPassword + "'");
      statement.execute("ALTER ROLE fs_vault PASSWORD '" + vaultPassword + "'");
    } catch (IOException | SQLException e) {
      throw new IllegalStateException("could not bootstrap the test vault", e);
    }
    Flyway.configure()
        .dataSource(container.getJdbcUrl(), "fs_vault_migrator", migratorPassword)
        .schemas("vault")
        .defaultSchema("vault")
        .createSchemas(true)
        .locations("filesystem:" + VAULT)
        .load()
        .migrate();
  }

  /**
   * A connection as the container's superuser, for tests that look at the stored bytes.
   *
   * @return the connection
   * @throws SQLException on failure
   */
  public Connection superuser() throws SQLException {
    return DriverManager.getConnection(container.getJdbcUrl(), "postgres", container.getPassword());
  }

  /**
   * A data source for a vault role.
   *
   * @param role {@code fs_vault} or {@code fs_vault_migrator}
   * @return the data source
   */
  public DataSource dataSource(String role) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(container.getJdbcUrl());
    dataSource.setUser(role);
    dataSource.setPassword("fs_vault".equals(role) ? vaultPassword : migratorPassword);
    return dataSource;
  }

  /**
   * The vault's JDBC URL, as configuration would carry it.
   *
   * @return the URL
   */
  public String url() {
    return container.getJdbcUrl();
  }

  /**
   * A vault role's password, as the environment would carry it.
   *
   * @param role {@code fs_vault} or {@code fs_vault_migrator}
   * @return the password
   */
  public String password(String role) {
    return "fs_vault".equals(role) ? vaultPassword : migratorPassword;
  }

  /**
   * A fresh master key, as configuration would carry it.
   *
   * @return 32 bytes as base64
   */
  public static String masterKey() {
    byte[] key = new byte[32];
    RANDOM.nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  private static String secret() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return "p" + HexFormat.of().formatHex(bytes);
  }

  @Override
  public void close() {
    container.stop();
  }
}
