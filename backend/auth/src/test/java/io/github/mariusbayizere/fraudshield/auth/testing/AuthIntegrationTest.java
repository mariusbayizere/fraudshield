package io.github.mariusbayizere.fraudshield.auth.testing;

import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.auth.security.CsrfDoubleSubmitFilter;
import io.github.mariusbayizere.fraudshield.auth.web.SessionCookies;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the auth and admin integration tests: the real application over HTTP against a migrated
 * TimescaleDB, Redis and a fake Google, with a movable clock and a recording mailer. One database
 * and one application context serve every test class; tests use their own accounts, and Redis is
 * flushed before each test so rate limits never leak between tests.
 */
@Tag("requires-docker")
@SpringBootTest(
    classes = AuthTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AuthIntegrationTest {

  /** Password of every account the tests create. */
  public static final String PASSWORD = "Str0ng!Passw0rd";

  /** Domain allowed to self-register into bank A. */
  public static final String BANK_A_DOMAIN = "bank-a.example.rw";

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final AtomicInteger SEQUENCE = new AtomicInteger();
  private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

  /** The shared database. */
  protected static final TestDatabase DB = TestDatabase.create();

  /** Institution A (self-service domain bank-a.example.rw). */
  protected static final UUID BANK_A = DB.createInstitution("bank-a");

  /** Institution B. */
  protected static final UUID BANK_B = DB.createInstitution("bank-b");

  /** The fake Google endpoints. */
  protected static final FakeGoogle GOOGLE = startGoogle();

  @LocalServerPort private int port;

  @Autowired protected MutableClock clock;

  @Autowired protected RecordingStaffMailer mailer;

  @Autowired private StringRedisTemplate redis;

  @Autowired private io.github.mariusbayizere.fraudshield.auth.session.SessionService sessions;

  @Autowired private io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions tenants;

  @Autowired
  private io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository accounts;

  /** HTTP client for the running application. */
  protected Http http;

  private static FakeGoogle startGoogle() {
    try {
      return new FakeGoogle();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String randomHex(int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return HexFormat.of().formatHex(value);
  }

  private static Path jwtKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      Path file = Files.createTempFile("fraudshield-jwt", ".pem");
      file.toFile().deleteOnExit();
      Files.writeString(
          file,
          io.github.mariusbayizere.fraudshield.audit.testing.Pem.of(
              "PRIVATE" + " KEY", generator.generateKeyPair().getPrivate().getEncoded()));
      return file;
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    // The schema is migrated by TestDatabase as fs_migrator; the application role cannot and must
    // not.
    registry.add("spring.flyway.enabled", () -> "false");
    registry.add("spring.datasource.url", DB::url);
    registry.add("spring.datasource.username", () -> "fs_app");
    registry.add("spring.datasource.password", () -> DB.password("fs_app"));
    registry.add("spring.data.redis.url", TestRedis::url);
    registry.add("spring.data.redis.timeout", () -> "2s");
    registry.add("spring.data.redis.connect-timeout", () -> "2s");
    registry.add("fraudshield.auth.environment", () -> "test");
    registry.add(
        "fraudshield.auth.console-base-url", () -> "https://console.test.fraudshield.local");
    registry.add("fraudshield.auth.mail-from", () -> "no-reply@fraudshield.local");
    registry.add("fraudshield.auth.token-key-hex", () -> randomHex(32));
    registry.add("fraudshield.auth.secret-box-key-hex", () -> randomHex(32));
    registry.add("fraudshield.auth.secret-box-key-id", () -> "box-1");
    Path key = jwtKey();
    registry.add("fraudshield.auth.jwt.signing-keys[0].kid", () -> "staff-1");
    registry.add("fraudshield.auth.jwt.signing-keys[0].private-key", key::toString);
    registry.add("fraudshield.auth.api-keys.peppers.1", () -> randomHex(32));
    registry.add("fraudshield.auth.api-keys.current-pepper-version", () -> "1");
    registry.add("fraudshield.auth.self-service-domains[" + BANK_A_DOMAIN + "]", BANK_A::toString);
    registry.add("fraudshield.auth.rate-limits.minimum-public-response", () -> "20ms");
    // Tests insert allowlist rows directly; the admin API evicts the cache itself.
    registry.add("fraudshield.auth.rate-limits.office-ip-cache-ttl", () -> "1ms");
    registry.add("fraudshield.auth.google.client-id", () -> FakeGoogle.CLIENT_ID);
    registry.add("fraudshield.auth.google.client-secret", () -> "test-client-secret");
    registry.add("fraudshield.auth.google.token-uri", () -> GOOGLE.baseUrl() + "/token");
    registry.add("fraudshield.auth.google.jwks-uri", () -> GOOGLE.baseUrl() + "/jwks");
    registry.add("fraudshield.auth.google.revoke-uri", () -> GOOGLE.baseUrl() + "/revoke");
    registry.add("fraudshield.auth.google.allowed-redirect-uris[0]", () -> FakeGoogle.REDIRECT_URI);
  }

  @BeforeEach
  void resetSharedState() {
    http = new Http("http://127.0.0.1:" + port);
    clock.set(Instant.now());
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<Object>)
            connection -> {
              connection.serverCommands().flushAll();
              return null;
            });
  }

  /** A unique email in bank A's domain. */
  protected static String uniqueEmail(String prefix) {
    return prefix
        + "."
        + SEQUENCE.incrementAndGet()
        + "."
        + System.nanoTime() % 100000
        + "@"
        + BANK_A_DOMAIN;
  }

  /** A unique employee ID. */
  protected static String uniqueEmployeeId() {
    return "E"
        + Long.toString(Math.abs(RANDOM.nextLong()), 36)
            .substring(0, 10)
            .toUpperCase(java.util.Locale.ROOT);
  }

  /**
   * An account created directly in the database.
   *
   * @param id account ID
   * @param institution institution
   * @param email email
   * @param role role
   * @param status status
   */
  public record Account(UUID id, UUID institution, String email, String role, String status) {}

  /**
   * Creates an account with the shared password.
   *
   * @param institution institution
   * @param role role
   * @param status status
   * @return the account
   */
  protected static Account createAccount(UUID institution, String role, String status) {
    Account account =
        new Account(
            UUID.randomUUID(),
            institution,
            uniqueEmail(role.toLowerCase(java.util.Locale.ROOT)),
            role,
            status);
    try (Connection admin = DB.superuser();
        PreparedStatement insert =
            admin.prepareStatement(
                """
                INSERT INTO fraudshield.users (id, institution_id, first_name, last_name, email,
                  password_hash, role, status, email_verified, employee_id, department, locked_until)
                VALUES (?, ?, 'Amani', 'Uwase', ?, ?, ?, ?, true, ?, 'FRAUD_OPERATIONS',
                  CASE WHEN ? = 'LOCKED' THEN now() + interval '30 minutes' END)
                """)) {
      insert.setObject(1, account.id());
      insert.setObject(2, institution);
      insert.setString(3, account.email());
      insert.setString(4, PASSWORD_HASH);
      insert.setString(5, role);
      insert.setString(6, status);
      insert.setString(7, uniqueEmployeeId());
      insert.setString(8, status);
      insert.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return account;
  }

  /**
   * Runs SQL as the superuser.
   *
   * @param sql statement
   * @param args arguments
   */
  protected static void superuser(String sql, Object... args) {
    try (Connection admin = DB.superuser();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      statement.execute();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Reads one value as the superuser.
   *
   * @param sql query
   * @param args arguments
   * @return the first column of the first row
   */
  protected static Object query(String sql, Object... args) {
    try (Connection admin = DB.superuser();
        PreparedStatement statement = admin.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
      var rows = statement.executeQuery();
      return rows.next() ? rows.getObject(1) : null;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Audit actions recorded for an entity, oldest first.
   *
   * @param entityId entity ID
   * @return actions as TYPE/ACTION
   */
  protected static List<String> auditActions(Object entityId) {
    try (Connection admin = DB.superuser();
        PreparedStatement statement =
            admin.prepareStatement(
                "SELECT event_type || '/' || action FROM fraudshield.audit_events"
                    + " WHERE entity_id = ?"
                    + " ORDER BY recorded_at, seq")) {
      statement.setString(1, entityId.toString());
      var rows = statement.executeQuery();
      List<String> actions = new java.util.ArrayList<>();
      while (rows.next()) {
        actions.add(rows.getString(1));
      }
      return actions;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A signed-in session as the browser holds it.
   *
   * @param accessToken access token (memory)
   * @param refreshToken refresh cookie
   * @param csrfToken CSRF cookie
   */
  public record Session(String accessToken, String refreshToken, String csrfToken) {

    /**
     * The session ID ({@code sid} claim) of the access token.
     *
     * @return the session ID
     */
    public String sessionIdFromToken() {
      String payload =
          new String(
              java.util.Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
              java.nio.charset.StandardCharsets.UTF_8);
      int start = payload.indexOf("\"sid\":\"") + 7;
      return payload.substring(start, payload.indexOf('"', start));
    }

    /**
     * Authorization header pair.
     *
     * @return header name and value
     */
    public String[] bearer() {
      return new String[] {"Authorization", "Bearer " + accessToken};
    }

    /**
     * Authorization plus CSRF header and cookie.
     *
     * @return header pairs
     */
    public String[] bearerWithCsrf() {
      return new String[] {
        "Authorization",
        "Bearer " + accessToken,
        CsrfDoubleSubmitFilter.HEADER,
        csrfToken,
        "Cookie",
        CsrfDoubleSubmitFilter.COOKIE + "=" + csrfToken
      };
    }

    /**
     * Refresh cookie plus CSRF header and cookie, as the browser sends them to the refresh path.
     *
     * @return header pairs
     */
    public String[] refreshHeaders() {
      return new String[] {
        CsrfDoubleSubmitFilter.HEADER,
        csrfToken,
        "Cookie",
        SessionCookies.REFRESH
            + "="
            + refreshToken
            + "; "
            + CsrfDoubleSubmitFilter.COOKIE
            + "="
            + csrfToken
      };
    }
  }

  /**
   * Signs in.
   *
   * @param email email
   * @return the session
   */
  protected Session login(String email) {
    Http.Response response =
        http.post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
    if (response.status() != 200) {
      throw new AssertionError("sign-in failed: " + response.status() + " " + response.body());
    }
    return session(response);
  }

  /**
   * A session issued directly, without the sign-in endpoint (no bcrypt, no rate limit), for tests
   * that need many sessions.
   *
   * @param account the account
   * @return the session
   */
  protected Session issueSession(Account account) {
    var issued =
        tenants.inTenant(
            account.institution(),
            () ->
                sessions.start(
                    accounts.findById(account.id()).orElseThrow(),
                    io.github.mariusbayizere.fraudshield.audit.RequestContext.SYSTEM,
                    null));
    return new Session(issued.accessToken(), issued.refreshToken(), issued.csrfToken());
  }

  /**
   * The session a sign-in or refresh response carries.
   *
   * @param response the response
   * @return the session
   */
  protected static Session session(Http.Response response) {
    return new Session(
        response.json().get("access_token").asString(),
        response.cookie(SessionCookies.REFRESH).orElseThrow(),
        response.cookie(CsrfDoubleSubmitFilter.COOKIE).orElseThrow());
  }
}
