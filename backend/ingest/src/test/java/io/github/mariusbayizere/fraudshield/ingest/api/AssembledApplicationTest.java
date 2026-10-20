package io.github.mariusbayizere.fraudshield.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.mariusbayizere.fraudshield.audit.cli.AuditVerifyCommand;
import io.github.mariusbayizere.fraudshield.auth.persistence.StaffUserEntity;
import io.github.mariusbayizere.fraudshield.decision.testing.Fixtures;
import io.github.mariusbayizere.fraudshield.decision.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.ingest.auth.ApiKeyAuthenticator;
import io.github.mariusbayizere.fraudshield.notify.webhook.WebhookEndpoints;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.CodeSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The API application as it ships: M6's decision path and M7's staff identity, API keys,
 * administration and audit in one process, with no test bean of any kind (M7 merge review, finding
 * 1). Only infrastructure is provided: PostgreSQL, the PII vault, Redis and Kafka containers. The
 * scorer is M5's separate process and is absent here, so the C.4 fallback decides; the SMS provider
 * is configured but never reached.
 *
 * <p>One journey runs through both milestones' code: an administrator signs in and issues an API
 * key; the key submits a transaction that the fallback holds for review; the hold's audit event,
 * published by the decision engine to Kafka, is appended to the hash chain by the audit writer's
 * consumer, found by audit search and verified by {@code fraudshield audit verify}; and the key,
 * once revoked, is refused within the 5 seconds FR-06-07 allows.
 */
@Tag("requires-docker")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles(ApiHarness.PROFILE)
// Closed after this class: its real SMS gateway and webhook registry join the same consumer groups
// as the other API tests' doubles, and a cached context would keep taking their partitions.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssembledApplicationTest {

  private static final String PASSWORD = "Assembl3d!Passw0rd";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /**
   * A fixed management port. The management chain admits {@code /actuator/**} only on the
   * configured management port, so with ApiHarness's {@code management.server.port=0} every
   * actuator request is refused with 401, however it arrives.
   */
  private static final int MANAGEMENT_PORT = freePort();

  @Autowired Environment environment;

  @Autowired ConfigurableApplicationContext context;

  private String base;

  /** A response, with its body parsed on demand. */
  private record Response(int status, String body) {
    JsonNode json() {
      return JSON.readTree(body);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ApiHarness.properties(registry);
    // Nothing listens on port 1: the scorer is unavailable and the fallback decides (C.4).
    registry.add("fraudshield.scorer.target", () -> "127.0.0.1:1");
    registry.add("fraudshield.sms.base-url", () -> "http://127.0.0.1:1/");
    registry.add("fraudshield.sms.username", () -> "sandbox");
    registry.add("fraudshield.sms.api-key", () -> "not-a-real-provider-key");
    registry.add("management.server.port", () -> MANAGEMENT_PORT);
  }

  private static int freePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @BeforeEach
  void client() {
    base = "http://127.0.0.1:" + environment.getProperty("local.server.port");
  }

  private Response send(String method, String path, Object body, String... headers)
      throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    for (int i = 0; i < headers.length; i += 2) {
      request.header(headers[i], headers[i + 1]);
    }
    HttpResponse<String> response =
        CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Response(response.statusCode(), response.body());
  }

  private Response post(String path, Object body, String... headers) throws Exception {
    return send("POST", path, body, headers);
  }

  private Response get(String path, String... headers) throws Exception {
    return send("GET", path, null, headers);
  }

  @Test
  @Tag("FR-06-07")
  void theApplicationStartsWithMilestoneSevensAdaptersAndOneTransactionManager() {
    // No bean comes from test code. A test-jar on this module's classpath is enough to break that:
    // the application scans the whole root package, and once picked up M7's test application, whose
    // fixed clock then stopped M6's rate-limit refill and hold timer in every API test.
    List<String> fromTests = new ArrayList<>();
    for (String name : context.getBeanDefinitionNames()) {
      Class<?> type = context.getType(name);
      CodeSource source = type == null ? null : type.getProtectionDomain().getCodeSource();
      String where = source == null ? "" : String.valueOf(source.getLocation());
      if (where.contains("/test-classes/") || where.endsWith("-tests.jar")) {
        fromTests.add(name + ": " + type.getName() + " from " + where);
      }
    }
    assertThat(fromTests).as("beans defined by test code").isEmpty();
    // M7's chain authorises every request before M6's filters run: Spring Security's proxy is
    // registered ahead of M6's ApiKeyFilter and RateLimitFilter.
    Map<String, org.springframework.boot.web.servlet.AbstractFilterRegistrationBean<?>> filters =
        new java.util.HashMap<>();
    for (var registration :
        context
            .getBeansOfType(
                org.springframework.boot.web.servlet.AbstractFilterRegistrationBean.class)
            .entrySet()) {
      filters.put(registration.getKey(), registration.getValue());
    }
    int security =
        filters.values().stream()
            .filter(
                r ->
                    r
                        instanceof
                        org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean)
            .mapToInt(org.springframework.boot.web.servlet.AbstractFilterRegistrationBean::getOrder)
            .findFirst()
            .orElseThrow();
    assertThat(security).isLessThan(filters.get("apiKeyFilter").getOrder());
    assertThat(security).isLessThan(filters.get("rateLimitFilter").getOrder());
    // The ingestion API's two ports are bound by StaffIdentityWiring, not by any test bean.
    for (Class<?> port : List.of(ApiKeyAuthenticator.class, WebhookEndpoints.class)) {
      String[] names = context.getBeanNamesForType(port);
      assertThat(names).as(port.getSimpleName()).hasSize(1);
      assertThat(context.getBeanFactory().getBeanDefinition(names[0]).getFactoryBeanName())
          .as(port.getSimpleName())
          .isEqualTo("staffIdentityWiring");
    }
    // One transaction manager, JPA's, so M6's TenantTransactions and M7's share it and JDBC work
    // joins JPA's transaction (ADR 0068, ADR 0071): a second, DataSource-only manager would fail
    // here.
    Map<String, PlatformTransactionManager> managers =
        context.getBeansOfType(PlatformTransactionManager.class);
    assertThat(managers).hasSize(1);
    assertThat(managers.values().iterator().next()).isInstanceOf(JpaTransactionManager.class);
    // One entity maps users: M7's. The demo seeder's stand-in lives only in the database tool.
    List<Class<?>> users = new ArrayList<>();
    for (EntityType<?> entity :
        context.getBean(EntityManagerFactory.class).getMetamodel().getEntities()) {
      Table table = entity.getJavaType().getAnnotation(Table.class);
      if (table != null && table.name().equals("users")) {
        users.add(entity.getJavaType());
      }
    }
    assertThat(users).containsExactly(StaffUserEntity.class);
  }

  @Test
  @Tag("FR-06-06")
  @Tag("FR-06-07")
  @Tag("D-32")
  void heldDecisionIsAuditedSearchableAndVerifiedThenItsRevokedKeyStopsWithinFiveSeconds()
      throws Exception {
    String admin = signIn(adminAccount());

    Response created =
        post(
            "/api/v1/admin/api-keys",
            Map.of("name", "Assembled core banking", "scopes", List.of("ingest:write")),
            "Authorization",
            admin);
    assertThat(created.status()).as(created.body()).isEqualTo(201);
    String key = created.json().get("raw_key").asString();

    // At or above the fallback's review amount (ADR 0061), so the decision is a hold, which the
    // decision engine audits.
    UUID transaction = UUID.randomUUID();
    tools.jackson.databind.node.ObjectNode body = IngestApiTest.body(transaction, "MOBILE_MONEY");
    body.put("amount", "2500000");
    Response decided = post("/api/v1/transactions/ingest", body, "X-API-Key", key);
    assertThat(decided.status()).as(decided.body()).isEqualTo(200);
    assertThat(decided.json().get("decision").asString()).isEqualTo("HOLD");
    assertThat(decided.json().get("ml_unavailable_fallback").asBoolean()).isTrue();

    String search = "/api/v1/admin/audit-events?entity_type=transaction&entity_id=" + transaction;
    JsonNode held =
        await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .until(
                () -> get(search, "Authorization", admin).json().get("items"),
                items -> !items.isEmpty());
    assertThat(held.get(0).get("event_type").asString()).isEqualTo("TRANSACTION_DECISION");
    assertThat(held.get(0).get("action").asString()).isEqualTo("HELD");

    List<String> out = new ArrayList<>();
    List<String> err = new ArrayList<>();
    String today = LocalDate.now(ZoneOffset.UTC).toString();
    int verified =
        new AuditVerifyCommand(
                Map.of(
                    "FRAUDSHIELD_DB_URL", ApiHarness.DB.url(),
                    "FRAUDSHIELD_DB_COMPLIANCE_PASSWORD",
                        ApiHarness.DB.password("fs_compliance_ro")),
                out::add,
                err::add)
            .run("audit", "verify", "--from", today, "--to", today);
    assertThat(verified).as("%s %s", out, err).isZero();
    assertThat(out).last().isEqualTo("VERIFIED");

    String keyId = created.json().get("key_id").asString();
    assertThat(
            post("/api/v1/admin/api-keys/" + keyId + "/revoke", null, "Authorization", admin)
                .status())
        .isEqualTo(204);
    long revokedAt = System.nanoTime();
    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .until(
            () ->
                post(
                            "/api/v1/transactions/ingest",
                            IngestApiTest.body(UUID.randomUUID(), "CARD"),
                            "X-API-Key",
                            key)
                        .status()
                    == 401);
    assertThat(Duration.ofNanos(System.nanoTime() - revokedAt)).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @Tag("FR-01-07")
  void apiDocsChainOpensTheTwoDocumentPathsToGetAndNothingElse() throws Exception {
    assertThat(get("/api/docs").status()).isEqualTo(200);
    assertThat(get("/api/docs/openapi.yaml").status()).isEqualTo(200);
    assertThat(get("/api/docs/x").status()).as("another path under it").isIn(401, 403);
    assertThat(send("HEAD", "/api/docs", null).status()).as("another method").isIn(401, 403);
    assertThat(post("/api/docs", Map.of()).status()).as("a write").isIn(401, 403);
  }

  @Test
  @Tag("D-32")
  void recordThatIsNotAnAuditEventIsDeadLetteredOnItsFirstAttempt() throws Exception {
    // A record any later attempt would refuse too goes to the dead-letter topic at once; a failure
    // that may be the record's own would be retried for ten minutes first (RetryBound.DEFAULT).
    final String key = "not-an-audit-event-" + UUID.randomUUID();
    tools.jackson.databind.node.ObjectNode envelope = JSON.createObjectNode();
    envelope.put("event_id", UUID.randomUUID().toString());
    envelope.put("event_type", "audit.appended");
    envelope.put("schema_version", 1);
    envelope.put("occurred_at", java.time.Instant.now().toString());
    envelope.put("institution_id", Fixtures.INSTITUTION.toString());
    envelope.put("producer", "fraudshield-api");
    envelope.putObject("payload").put("event_type", "NOT_A_TYPE").put("writer_partition", 0);
    java.util.Map<String, Object> client =
        Map.of(
            "bootstrap.servers",
            ApiHarness.KAFKA.bootstrapServers(),
            "acks",
            "all",
            "group.id",
            "dlq-reader-" + UUID.randomUUID(),
            "auto.offset.reset",
            "earliest");
    try (var producer =
        new org.apache.kafka.clients.producer.KafkaProducer<String, byte[]>(
            client,
            new org.apache.kafka.common.serialization.StringSerializer(),
            new org.apache.kafka.common.serialization.ByteArraySerializer())) {
      var record =
          new org.apache.kafka.clients.producer.ProducerRecord<String, byte[]>(
              "fs.audit.events", key, JSON.writeValueAsBytes(envelope));
      record.headers().add("event_id", UUID.randomUUID().toString().getBytes());
      producer.send(record).get();
      // A well-formed audit payload without the event_id header cannot be deduplicated either.
      tools.jackson.databind.node.ObjectNode valid = envelope.deepCopy();
      valid
          .putObject("payload")
          .put("event_type", "TRANSACTION_DECISION")
          .put("action", "HELD")
          .put("entity_type", "transaction")
          .put("entity_id", UUID.randomUUID().toString())
          .put("event_at", java.time.Instant.now().toString())
          .put("writer_partition", 0);
      producer
          .send(
              new org.apache.kafka.clients.producer.ProducerRecord<String, byte[]>(
                  "fs.audit.events", key + "-no-id", JSON.writeValueAsBytes(valid)))
          .get();
    }
    String reason = null;
    String noIdReason = null;
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    try (var consumer =
        new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(
            client,
            new org.apache.kafka.common.serialization.StringDeserializer(),
            new org.apache.kafka.common.serialization.ByteArrayDeserializer())) {
      consumer.subscribe(List.of("fs.audit.events.dlq"));
      while ((reason == null || noIdReason == null) && System.nanoTime() < deadline) {
        for (var dead : consumer.poll(Duration.ofMillis(500))) {
          if (key.equals(dead.key())) {
            reason = new String(dead.headers().lastHeader("fs-dlq-reason").value());
          } else if ((key + "-no-id").equals(dead.key())) {
            noIdReason = new String(dead.headers().lastHeader("fs-dlq-reason").value());
          }
        }
      }
    }
    assertThat(reason)
        .as("dead-lettered within 30 s, not after ten minutes of retries")
        .isEqualTo("malformed_envelope");
    assertThat(noIdReason)
        .as("a record without its event_id header, likewise")
        .isEqualTo("malformed_envelope");
  }

  @Test
  @Tag("OPS-OBS-01")
  void theSpoolDepthGaugeIsExportedUnderTheNameM9sAlertAndDashboardRead() throws Exception {
    // M9's FraudShieldSpoolBacklog alert and the fs-kafka-spool dashboard read the name Prometheus
    // scrapes, not the name the code registers. The gauge is registered as fs_spool_depth with base
    // unit "bytes"; this scrape records which name that becomes (M9_updates.md 3c, 2026-10-20).
    String management = "http://127.0.0.1:" + MANAGEMENT_PORT;
    HttpResponse<String> scrape =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(management + "/actuator/prometheus")).build(),
            HttpResponse.BodyHandlers.ofString());
    List<String> spoolLines =
        scrape.body().lines().filter(line -> line.contains("fs_spool_depth")).toList();

    assertThat(scrape.statusCode()).isEqualTo(200);
    assertThat(spoolLines)
        .as("every exported line naming fs_spool_depth")
        .contains("# TYPE fs_spool_depth_bytes gauge")
        .noneMatch(
            line -> line.startsWith("fs_spool_depth{") || line.startsWith("fs_spool_depth "));
  }

  private static UUID adminAccount() throws Exception {
    UUID id = UUID.randomUUID();
    try (Connection c = ApiHarness.DB.superuser()) {
      TestDatabase.exec(
          c,
          "INSERT INTO fraudshield.users (id, institution_id, first_name, last_name, email,"
              + " password_hash, role, status, email_verified, employee_id, department) VALUES"
              + " (?, ?, 'Assembled', 'Admin', 'assembled.admin@example.test', ?, 'ADMIN',"
              + " 'ACTIVE', true, 'EMPASM01', 'IT')",
          id,
          Fixtures.INSTITUTION,
          new BCryptPasswordEncoder(12).encode(PASSWORD));
    }
    return id;
  }

  private String signIn(UUID account) throws Exception {
    Response response =
        post(
            "/api/v1/auth/login",
            Map.of("email", "assembled.admin@example.test", "password", PASSWORD));
    assertThat(response.status()).as("sign-in of %s: %s", account, response.body()).isEqualTo(200);
    return "Bearer " + response.json().get("access_token").asString();
  }
}
