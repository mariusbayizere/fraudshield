package io.github.mariusbayizere.fraudshield.ingest.api;

import com.sun.net.httpserver.HttpServer;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.KafkaTestCluster;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.github.mariusbayizere.fraudshield.decision.testing.ScorerDouble;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiPrincipal;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiScope;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Everything the API needs, real where it can be: TimescaleDB, Redis and Kafka containers, and the
 * scorer as a gRPC double on a real Netty port (M5 builds the real one), and the PII vault as its
 * own PostgreSQL instance with the application's own vault adapters over it (D-20). The API-key
 * module, the webhook endpoint registry and the SMS provider are M7's and third parties'; here they
 * are test beans, registered only by these tests.
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
          BUDGET_KEY_FIVE, UUID.fromString("6c1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a05"));

  /** The api_keys row id of {@link #KEY}. */
  public static final UUID KEY_ID = UUID.fromString("5b1e8f4a-2c3d-4e5f-8a9b-0c1d2e3f4a5b");

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
      apiKeyRow();
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

  private static void apiKeyRow() throws SQLException {
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
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.api_keys (id, institution_id, key_id, name,"
              + " secret_hmac, pepper_version, last_four, scopes, created_by) VALUES (?, ?,"
              + " 'aaaaaaaaaaaa', 'Core banking test', decode(repeat('00', 32), 'hex'), 1, 'kkkk',"
              + " ARRAY['ingest:write', 'decisions:read', 'jobs:read'], ?)",
          KEY_ID,
          Fixtures.INSTITUTION,
          admin);
      for (Map.Entry<String, UUID> budget : BUDGET_KEY_IDS.entrySet()) {
        TestDatabase.exec(
            c,
            "INSERT INTO fraudshield.api_keys (id, institution_id, key_id, name,"
                + " secret_hmac, pepper_version, last_four, scopes, created_by) VALUES (?, ?,"
                + " ?, 'Budget test', decode(repeat('00', 32), 'hex'), 1, 'bbbb',"
                + " ARRAY['ingest:write', 'decisions:read', 'jobs:read'], ?)",
            budget.getValue(),
            Fixtures.INSTITUTION,
            budget.getKey().substring("fsk_test_".length(), "fsk_test_".length() + 12),
            admin);
      }
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

  /** Test-only beans standing in for M7, the PII vault and the SMS provider. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Collaborators {

    private static ApiPrincipal budget(String key) {
      return new ApiPrincipal(
          BUDGET_KEY_IDS.get(key), Fixtures.INSTITUTION, Set.of(ApiScope.values()));
    }

    @Bean
    ApiKeyAuthenticator apiKeys() {
      Map<String, ApiPrincipal> keys =
          Map.of(
              KEY, new ApiPrincipal(KEY_ID, Fixtures.INSTITUTION, Set.of(ApiScope.values())),
              ROTATED_KEY,
                  new ApiPrincipal(KEY_ID, Fixtures.INSTITUTION, Set.of(ApiScope.values())),
              READ_ONLY_KEY,
                  new ApiPrincipal(
                      UUID.randomUUID(), Fixtures.INSTITUTION, Set.of(ApiScope.DECISIONS_READ)),
              BUDGET_KEY_ONE, budget(BUDGET_KEY_ONE),
              BUDGET_KEY_TWO, budget(BUDGET_KEY_TWO),
              BUDGET_KEY_THREE, budget(BUDGET_KEY_THREE),
              BUDGET_KEY_FOUR, budget(BUDGET_KEY_FOUR),
              BUDGET_KEY_FIVE, budget(BUDGET_KEY_FIVE));
      return raw -> Optional.ofNullable(raw == null ? null : keys.get(raw));
    }

    @Bean
    WebhookEndpoints webhookEndpoints() {
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
