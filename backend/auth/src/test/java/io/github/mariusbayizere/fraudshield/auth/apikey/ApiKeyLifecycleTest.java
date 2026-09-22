package io.github.mariusbayizere.fraudshield.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.audit.AuditActor;
import io.github.mariusbayizere.fraudshield.audit.RequestContext;
import io.github.mariusbayizere.fraudshield.audit.jdbc.JdbcAuditLog;
import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.crypto.SecretBox;
import io.github.mariusbayizere.fraudshield.auth.testing.Instances;
import io.github.mariusbayizere.fraudshield.auth.testing.JpaTestStack;
import io.github.mariusbayizere.fraudshield.auth.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** API keys end to end on a real database and Redis (FR-06-07, D-19, FR-07-05). */
@Tag("requires-docker")
@Tag("FR-06-07")
class ApiKeyLifecycleTest {

  private static final Map<Integer, byte[]> PEPPERS = Map.of(1, Crypto.randomBytes(32));
  private static final Executor DIRECT = Runnable::run;
  private static final SecretBox BOX = new SecretBox("box-1", Crypto.randomBytes(32));
  private static TestDatabase db;
  private static JpaTestStack app;
  private static UUID bank;
  private static AuditActor admin;
  private static StringRedisTemplate redisA;
  private static StringRedisTemplate redisB;

  private final MutableClock clock = new MutableClock(Instant.now());

  @BeforeAll
  static void start() throws Exception {
    db = TestDatabase.create();
    app = JpaTestStack.of(db, "fs_app");
    bank = db.createInstitution("key-bank");
    UUID adminId = UUID.randomUUID();
    try (Connection superuser = db.superuser();
        PreparedStatement insert =
            superuser.prepareStatement(
                """
                INSERT INTO fraudshield.users (id, institution_id, first_name, last_name, email,
                  password_hash, role, status, employee_id, department)
                VALUES (?, ?, 'Key', 'Admin', 'key.admin@bank.rw',
                  '$2a$12$abcdefghijklmnopqrstuuv0123456789abcdefghijklmnopqrst', 'ADMIN', 'ACTIVE', 'KEYADMIN1', 'IT')
                """)) {
      insert.setObject(1, adminId);
      insert.setObject(2, bank);
      insert.executeUpdate();
    }
    admin = new AuditActor(adminId, "Key", "Admin", StaffRole.ADMIN);
    redisA = Instances.redis();
    redisB = Instances.redis();
  }

  private ApiKeyAuthenticator authenticator(StringRedisTemplate redis, String environment) {
    return new ApiKeyAuthenticator(
        new ApiKeyRepository(app.jdbc(), app.apiKeys(), app.entities(), clock),
        app.tenants(),
        Instances.safe(redis),
        PEPPERS,
        environment,
        Duration.ofSeconds(2),
        clock,
        System::nanoTime,
        DIRECT);
  }

  private ApiKeyService service(ApiKeyAuthenticator authenticator) {
    return new ApiKeyService(
        app.tenants(),
        new ApiKeyRepository(app.jdbc(), app.apiKeys(), app.entities(), clock),
        authenticator,
        new WebhookUrlValidator(host -> new InetAddress[] {InetAddress.getByName("203.0.113.7")}),
        BOX,
        new JdbcAuditLog(app.jdbc(), (short) 11),
        clock,
        "test",
        PEPPERS,
        1,
        Duration.ofHours(24));
  }

  private static String name() {
    return "Core banking " + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
  }

  @Test
  @Tag("D-19")
  void createdKeyAuthenticatesWithItsScopesAndStoresOnlyAnHmac() {
    ApiKeyAuthenticator authenticator = authenticator(redisA, "test");
    ApiKeyService.Issued issued =
        service(authenticator)
            .create(
                bank,
                name(),
                List.of(ApiKeyScope.INGEST_WRITE, ApiKeyScope.DECISIONS_READ),
                "https://hooks.bank.example/fs",
                admin,
                RequestContext.SYSTEM);
    assertThat(issued.rawKey()).startsWith("fsk_test_");
    assertThat(issued.webhookSigningSecret()).startsWith("whsec_test_");
    assertThat(issued.record().lastFour())
        .isEqualTo(issued.rawKey().substring(issued.rawKey().length() - 4));

    ApiKeyPrincipal principal = authenticator.authenticate(issued.rawKey()).orElseThrow();
    assertThat(principal.scopes())
        .containsExactlyInAnyOrder(ApiKeyScope.INGEST_WRITE, ApiKeyScope.DECISIONS_READ);
    assertThat(principal.institutionId()).isEqualTo(bank);

    String secret = issued.rawKey().substring(issued.rawKey().lastIndexOf('_') + 1);
    try (Connection superuser = db.superuser();
        var rows =
            superuser
                .createStatement()
                .executeQuery(
                    "SELECT encode(secret_hmac, 'escape')"
                        + " || coalesce(encode(webhook_secret_ciphertext, 'escape'), '')"
                        + " FROM fraudshield.api_keys WHERE key_id = '"
                        + issued.record().keyId()
                        + "'")) {
      rows.next();
      assertThat(rows.getString(1))
          .doesNotContain(secret)
          .doesNotContain(issued.webhookSigningSecret());
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
    assertThat(
            service(authenticator)
                .webhookSecret(bank, issued.record().id(), issued.record().keyId()))
        .isEqualTo(issued.webhookSigningSecret());
  }

  @Test
  void wrongSecretsOtherEnvironmentsAndMalformedKeysAreRefused() {
    ApiKeyAuthenticator authenticator = authenticator(redisA, "test");
    ApiKeyService.Issued issued =
        service(authenticator)
            .create(
                bank, name(), List.of(ApiKeyScope.JOBS_READ), null, admin, RequestContext.SYSTEM);
    String raw = issued.rawKey();
    String wrongSecret = raw.substring(0, raw.length() - 1) + (raw.endsWith("A") ? "B" : "A");
    assertThat(authenticator.authenticate(wrongSecret)).isEmpty();
    assertThat(authenticator(redisA, "prod").authenticate(raw))
        .as("key of another environment")
        .isEmpty();
    assertThat(authenticator.authenticate("fsk_test_unknownkey01_" + "a".repeat(43))).isEmpty();
    assertThat(authenticator.authenticate("Bearer something")).isEmpty();
    assertThat(issued.webhookSigningSecret()).isNull();
  }

  @Test
  @Tag("FR-06-07")
  void rotationKeepsTheOldKeyForTwentyFourHours() {
    ApiKeyAuthenticator authenticator = authenticator(redisA, "test");
    ApiKeyService service = service(authenticator);
    ApiKeyService.Issued original =
        service.create(
            bank, name(), List.of(ApiKeyScope.INGEST_WRITE), null, admin, RequestContext.SYSTEM);
    ApiKeyService.Issued replacement =
        service.rotate(bank, original.record().keyId(), admin, RequestContext.SYSTEM);
    assertThat(replacement.record().scopes()).containsExactly(ApiKeyScope.INGEST_WRITE);
    assertThat(authenticator.authenticate(original.rawKey()))
        .as("old key during the overlap")
        .isPresent();
    assertThat(authenticator.authenticate(replacement.rawKey())).isPresent();
    assertThatThrownBy(
            () -> service.rotate(bank, original.record().keyId(), admin, RequestContext.SYSTEM))
        .isInstanceOf(ProblemException.class)
        .satisfies(e -> assertThat(((ProblemException) e).status()).isEqualTo(409));

    // PostgreSQL rounds the stored expiry to the microsecond, so step just past it.
    clock.advance(Duration.ofHours(24).plusMillis(1));
    authenticator.onMessage(original.record().keyId());
    assertThat(authenticator.authenticate(original.rawKey())).as("overlap over").isEmpty();
    assertThat(authenticator.authenticate(replacement.rawKey())).isPresent();
  }

  @Test
  @Tag("FR-06-07")
  void revokedKeyFailsOnAnotherInstanceWithinFiveSeconds() throws Exception {
    ApiKeyAuthenticator instanceA = authenticator(redisA, "test");
    ApiKeyAuthenticator instanceB = authenticator(redisB, "test");
    var subscription =
        Instances.subscribe(redisA, ApiKeyAuthenticator.CHANNEL, instanceA::onMessage);
    Thread.sleep(300);
    try {
      long[] withPubSub = new long[5];
      long[] withoutPubSub = new long[5];
      ApiKeyAuthenticator deaf = authenticator(null, "test");
      for (int i = 0; i < withPubSub.length; i++) {
        ApiKeyService.Issued key =
            service(instanceB)
                .create(
                    bank,
                    name(),
                    List.of(ApiKeyScope.INGEST_WRITE),
                    null,
                    admin,
                    RequestContext.SYSTEM);
        assertThat(instanceA.authenticate(key.rawKey())).isPresent();
        assertThat(deaf.authenticate(key.rawKey())).isPresent();
        service(instanceB).revoke(bank, key.record().keyId(), admin, RequestContext.SYSTEM);
        withPubSub[i] = millisUntilRefused(instanceA, key.rawKey());
        withoutPubSub[i] = millisUntilRefused(deaf, key.rawKey());
      }
      java.util.Arrays.sort(withPubSub);
      java.util.Arrays.sort(withoutPubSub);
      Instances.record(
          "M7-api-key-revocation.json",
          String.format(
              Locale.ROOT,
              "{\"runs\": 5, \"gate_ms\": 5000, \"with_pubsub_max_ms\": %d,"
                  + " \"announcement_lost_max_ms\": %d,"
                  + " \"cache_ttl_ms\": 2000}%n",
              withPubSub[4],
              withoutPubSub[4]));
      assertThat(withPubSub[4]).isLessThan(5000);
      assertThat(withoutPubSub[4]).isLessThan(5000);
    } finally {
      subscription.stop();
    }
  }

  private static long millisUntilRefused(ApiKeyAuthenticator authenticator, String rawKey) {
    long start = System.nanoTime();
    while (authenticator.authenticate(rawKey).isPresent()) {
      if (System.nanoTime() - start > Duration.ofSeconds(10).toNanos()) {
        break;
      }
      Thread.onSpinWait();
    }
    return (System.nanoTime() - start) / 1_000_000;
  }

  @Test
  void duplicateLiveNamesAndUnsafeWebhooksAreRefused() {
    ApiKeyService service = service(authenticator(redisA, "test"));
    String name = name();
    service.create(bank, name, List.of(ApiKeyScope.JOBS_READ), null, admin, RequestContext.SYSTEM);
    assertThatThrownBy(
            () ->
                service.create(
                    bank, name, List.of(ApiKeyScope.JOBS_READ), null, admin, RequestContext.SYSTEM))
        .isInstanceOf(ProblemException.class)
        .satisfies(e -> assertThat(((ProblemException) e).status()).isEqualTo(409));
    assertThatThrownBy(
            () ->
                service.create(
                    bank,
                    name(),
                    List.of(ApiKeyScope.JOBS_READ),
                    "http://hooks.bank.example",
                    admin,
                    RequestContext.SYSTEM))
        .isInstanceOf(ProblemException.class)
        .satisfies(
            e ->
                assertThat(((ProblemException) e).errors().getFirst().code())
                    .isEqualTo("webhook_url_not_allowed"));
    assertThatThrownBy(() -> service.revoke(bank, "nosuchkey000", admin, RequestContext.SYSTEM))
        .isInstanceOf(ProblemException.class)
        .satisfies(e -> assertThat(((ProblemException) e).status()).isEqualTo(404));
  }
}
