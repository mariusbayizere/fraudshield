package io.github.mariusbayizere.fraudshield.ingest.api;

import com.sun.net.httpserver.HttpServer;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.KafkaTestCluster;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.github.mariusbayizere.fraudshield.decision.testing.ScorerDouble;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.notify.sms.SmsGateway;
import io.github.mariusbayizere.fraudshield.notify.vault.TestVault;
import io.github.mariusbayizere.fraudshield.notify.webhook.HttpWebhookTransport;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookEndpoints;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookTransport;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Everything the API needs, real where it can be: TimescaleDB, Redis and Kafka containers, and the
 * scorer as a gRPC double on a real Netty port (M5 builds the real one), and the PII vault as its
 * own PostgreSQL instance with the application's own vault adapters over it (D-20). API keys are
 * M7's real ones: each test key is an {@code api_keys} row with the HMAC of its secret under the
 * test pepper. Three collaborators stay test beans, registered only by these tests: the SMS
 * provider, a loopback webhook transport, and a webhook registry pointing at the plain-HTTP
 * receiver below, which M7's real registry cannot hold because {@code api_keys.webhook_url} must be
 * HTTPS.
 */
public final class ApiHarness {

  /**
   * The profile every API test runs under: the vault's keys come from configuration, which the
   * application allows only under a dev, demo or test profile (ADR 0069 point 10).
   */
  public static final String PROFILE = "test";

  /** Full-scope key of the synthetic institution. */
  public static final String KEY = "fsk_test_aaaaaaaaaaaa_" + "k".repeat(43);

  /** A key with decisions:read only. */
  public static final String READ_ONLY_KEY = "fsk_test_bbbbbbbbbbbb_" + "r".repeat(43);

  /** A second active key of the same institution, as during a 24-hour rotation overlap. */
  public static final String ROTATED_KEY = "fsk_test_cccccccccccc_" + "n".repeat(43);

  /** Keys with budgets of their own, so a rate-limit test cannot spend another test's (E.1). */
  public static final String BUDGET_KEY_ONE = "fsk_test_dddddddddddd_" + "b".repeat(43);

  /** A second key with its own budget. */
  public static final String BUDGET_KEY_TWO = "fsk_test_eeeeeeeeeeee_" + "c".repeat(43);

  /** A third key with its own budget. */
  public static final String BUDGET_KEY_THREE = "fsk_test_ffffffffffff_" + "d".repeat(43);

  /** A fourth key with its own budget, for batches against the transaction budget (ADR 0058). */
  public static final String BUDGET_KEY_FOUR = "fsk_test_gggggggggggg_" + "e".repeat(43);

  /** A fifth key with its own budget, for batches while Redis is down. */
  public static final String BUDGET_KEY_FIVE = "fsk_test_hhhhhhhhhhhh_" + "f".repeat(43);

  /** A sixth key with its own budget, for an exhausted key's stalled batch. */
  public static final String BUDGET_KEY_SIX = "fsk_test_iiiiiiiiiiii_" + "g".repeat(43);

  /** A seventh key with its own budget, for what refused and invalid batches cost. */
  public static final String BUDGET_KEY_SEVEN = "fsk_test_jjjjjjjjjjjj_" + "h".repeat(43);

  /** An eighth key with its own budget, for a batch refused at its second charge. */
  public static final String BUDGET_KEY_EIGHT = "fsk_test_kkkkkkkkkkkk_" + "i".repeat(43);

  /**
   * A key no test uses before the PostgreSQL outage, so no instance holds its record when the
   * database goes away (D-15, threat model R-13).
   */
  public static final String UNSEEN_KEY = "fsk_test_llllllllllll_" + "u".repeat(43);

  /**
   * The {@code api_keys} row ids of the budget keys: fixed, and inserted, because a batch job
   * references its key's row.
   */
  public static final Map<String, UUID> BUDGET_KEY_IDS =
      Map.of(
          BUDGET_KEY_ONE, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a01"),
          BUDGET_KEY_TWO, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a02"),
          BUDGET_KEY_THREE, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a03"),
          BUDGET_KEY_FOUR, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a04"),
          BUDGET_KEY_FIVE, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a05"),
          BUDGET_KEY_SIX, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a06"),
          BUDGET_KEY_SEVEN, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a07"),
          BUDGET_KEY_EIGHT, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a08"));

  /** The api_keys row id of {@link #KEY}. */
  public static final UUID KEY_ID = UUID.fromString("5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b");

  /** The API-key pepper (version 1), random per run. */
  static final byte[] PEPPER = Crypto.randomBytes(32);

  /** Webhook signing secret for the test receiver. */
  public static final String WEBHOOK_SECRET = "hook-" + "s".repeat(40);

  /** The scorer double, shared by every test. */
  public static final ScorerDouble SCORER = new ScorerDouble();

  /** Webhook bodies and signatures the receiver got. */
  public static final List<String[]> WEBHOOKS = new CopyOnWriteArrayList<>();

  /** SMS texts the provider double accepted. */
  public static final List<String> SMS = new CopyOnWriteArrayList<>();

  /** The numbers the provider double was asked to send to, in the order of {@link #SMS}. */
  public static final List<String> SMS_PHONES = new CopyOnWriteArrayList<>();

  static final TestDatabase DB;
  static final TestVault VAULT;
  static final String VAULT_MASTER_KEY = TestVault.masterKey();
  static final String VAULT_INDEX_KEY = TestVault.masterKey();
  static final RedisTestServer REDIS;
  static final KafkaTestCluster KAFKA;
  static final Server SCORER_SERVER;
  static final HttpServer RECEIVER;
  static final Path SPOOL;

  static {
    try {
      DB = TestDatabase.create();
      DB.institution(Fixtures.INSTITUTION);
      notDemoData();
      apiKeyRows();
      VAULT = new TestVault();
      REDIS = new RedisTestServer();
      KAFKA = new KafkaTestCluster();
      SCORER_SERVER =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .addService(SCORER)
              .build()
              .start();
      RECEIVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      RECEIVER.createContext(
          "/hooks",
          exchange -> {
            WEBHOOKS.add(
                new String[] {
                  new String(
                      exchange.getRequestBody().readAllBytes(),
                      java.nio.charset.StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("X-FraudShield-Signature")
                });
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          });
      RECEIVER.start();
      SPOOL = Files.createTempDirectory("fs-spool");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private ApiHarness() {}

  /**
   * TestDatabase marks its institution as synthetic demo data, as demo seeding does. The API runs
   * the synthetic-data guard (ADR 0019 point 5) under the {@code test} profile, which refuses a
   * database holding demo data; this test bank is not demo data, so the marker is cleared.
   */
  private static void notDemoData() throws SQLException {
    try (Connection c = DB.superuser()) {
      TestDatabase.exec(
          c,
          "UPDATE fraudshield.institutions SET synthetic = false WHERE id = ?",
          Fixtures.INSTITUTION);
    }
  }

  private static void apiKeyRows() throws SQLException {
    UUID admin = UUID.randomUUID();
    try (Connection c = DB.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.users (id, institution_id, first_name,"
              + " last_name, email, role, employee_id, department, password_hash) VALUES (?, ?,"
              + " 'Test', 'Admin', 'admin@example.test', 'ADMIN', 'EMP0002', 'IT',"
              + " '$2b$12$notARealHashJustTheShapeOfOne.............')",
          admin,
          Fixtures.INSTITUTION);
      String all = "ARRAY['ingest:write', 'decisions:read', 'jobs:read']";
      apiKeyRow(c, KEY_ID, KEY, "Core banking test", all, admin);
      apiKeyRow(c, UUID.randomUUID(), ROTATED_KEY, "Core banking rotated", all, admin);
      apiKeyRow(c, UUID.randomUUID(), READ_ONLY_KEY, "Read only", "ARRAY['decisions:read']", admin);
      apiKeyRow(c, UUID.randomUUID(), UNSEEN_KEY, "Unseen before the outage", all, admin);
      for (Map.Entry<String, UUID> budget : BUDGET_KEY_IDS.entrySet()) {
        apiKeyRow(
            c,
            budget.getValue(),
            budget.getKey(),
            "Budget test " + budget.getValue().toString().substring(34),
            all,
            admin);
      }
    }
  }

  private static void apiKeyRow(
      Connection c, UUID id, String rawKey, String name, String scopes, UUID admin)
      throws SQLException {
    String keyId = rawKey.substring("fsk_test_".length(), "fsk_test_".length() + 12);
    String secret = rawKey.substring("fsk_test_".length() + 13);
    TestDatabase.exec(
        c,
        "INSERT INTO fraudshield.api_keys (id, institution_id, key_id, name, secret_hmac,"
            + " pepper_version, last_four, scopes, created_by) VALUES (?, ?, ?, ?, ?, 1, ?, "
            + scopes
            + ", ?)",
        id,
        Fixtures.INSTITUTION,
        keyId,
        name,
        Crypto.hmacSha256(PEPPER, secret.getBytes(StandardCharsets.US_ASCII)),
        secret.substring(secret.length() - 4),
        admin);
  }

  private static Path jwtSigningKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      Path file = Files.createTempFile("fs-api-jwt", ".pem");
      file.toFile().deleteOnExit();
      String label = "PRIVATE" + " KEY"; // split, so no scanner mistakes the code for a key
      Files.writeString(
          file,
          "-----BEGIN "
              + label
              + "-----\n"
              + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                  .encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
              + "\n-----END "
              + label
              + "-----\n");
      return file;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Properties pointing the application at the harness.
   *
   * @param registry Spring's registry
   */
  public static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DB::url);
    registry.add("spring.datasource.username", () -> "fs_app");
    registry.add("spring.datasource.password", () -> DB.password("fs_app"));
    registry.add("fraudshield.redis-uri", REDIS::uri);
    registry.add("fraudshield.kafka-bootstrap-servers", KAFKA::bootstrapServers);
    registry.add("fraudshield.spool-directory", SPOOL::toString);
    registry.add("fraudshield.scorer.target", () -> "127.0.0.1:" + SCORER_SERVER.getPort());
    registry.add("fraudshield.scorer.plaintext", () -> "true");
    registry.add("fraudshield.scorer.deadline", () -> "2s");
    registry.add("fraudshield.redis-timeout", () -> "500ms");
    registry.add("fraudshield.anomaly-review-threshold", () -> "0.7");
    registry.add("fraudshield.vault.url", VAULT::url);
    registry.add("fraudshield.vault.username", () -> "fs_vault");
    registry.add("fraudshield.vault.password", () -> VAULT.password("fs_vault"));
    registry.add("fraudshield.vault.key-provider", () -> "configured");
    registry.add("fraudshield.vault.current-key-id", () -> "harness");
    registry.add("fraudshield.vault.master-keys.harness", () -> VAULT_MASTER_KEY);
    registry.add("fraudshield.vault.index-key", () -> VAULT_INDEX_KEY);
    registry.add("spring.data.redis.url", REDIS::uri);
    registry.add("spring.data.redis.timeout", () -> "500ms");
    registry.add("fraudshield.auth.environment", () -> "test");
    registry.add(
        "fraudshield.auth.console-base-url", () -> "https://console.test.fraudshield.local");
    registry.add("fraudshield.auth.mail-from", () -> "no-reply@fraudshield.local");
    registry.add(
        "fraudshield.auth.token-key-hex", () -> HexFormat.of().formatHex(Crypto.randomBytes(32)));
    registry.add("fraudshield.auth.secret-box-key-id", () -> "harness");
    registry.add(
        "fraudshield.auth.secret-box-key-hex",
        () -> HexFormat.of().formatHex(Crypto.randomBytes(32)));
    Path jwt = jwtSigningKey();
    registry.add("fraudshield.auth.jwt.signing-keys[0].kid", () -> "harness");
    registry.add("fraudshield.auth.jwt.signing-keys[0].private-key", jwt::toString);
    registry.add("fraudshield.auth.api-keys.peppers.1", () -> HexFormat.of().formatHex(PEPPER));
    registry.add("server.port", () -> "0");
    registry.add("management.server.port", () -> "0");
    registry.add("fraudshield.institutions." + Fixtures.INSTITUTION + ".sender-id", () -> "FSBANK");
    registry.add(
        "fraudshield.institutions." + Fixtures.INSTITUTION + ".official-phone",
        () -> "+250788100100");
    registry.add(
        "fraudshield.institutions." + Fixtures.INSTITUTION + ".verification-base",
        () -> "https://verify.fsbank.rw/v/");
  }

  /** Test-only beans standing in for the SMS provider and an integrator's webhook receiver. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Collaborators {

    /**
     * Webhooks to the plain-HTTP receiver, in place of M7's registry ({@code StaffIdentityWiring}),
     * which holds HTTPS URLs only.
     *
     * @return the registry
     */
    @Bean
    @Primary
    WebhookEndpoints loopbackWebhookEndpoints() {
      URI url = URI.create("http://127.0.0.1:" + RECEIVER.getAddress().getPort() + "/hooks");
      return institution ->
          institution.equals(Fixtures.INSTITUTION)
              ? Optional.of(new WebhookEndpoints.Endpoint(url, List.of(WEBHOOK_SECRET)))
              : Optional.empty();
    }

    @Bean
    WebhookTransport loopbackTransport() {
      return new HttpWebhookTransport(u -> "127.0.0.1".equals(u.getHost()));
    }

    @Bean
    SmsGateway smsGateway() {
      return (sender, phone, text) -> {
        SMS_PHONES.add(phone);
        SMS.add(text);
        return "ATXid_" + SMS.size();
      };
    }
  }
}
