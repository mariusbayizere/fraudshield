package io.github.mariusbayizere.fraudshield.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.audit.testing.TestDatabase;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.auth.testing.FakeTime;
import io.github.mariusbayizere.fraudshield.auth.testing.Instances;
import io.github.mariusbayizere.fraudshield.auth.testing.JpaTestStack;
import io.github.mariusbayizere.fraudshield.common.config.StaffRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * M7 gate: token_version invalidation under 5 seconds (D-27, FR-06-02, FR-07-09), measured between
 * two independent instances that share only Redis and the database. Instance A has already cached
 * the session as current; instance B ends it; the time until A refuses the token is measured.
 */
@Tag("requires-docker")
@Tag("D-27")
@Tag("FR-06-02")
class SessionInvalidationTimingTest {

  private static final Duration GATE = Duration.ofSeconds(5);
  private static final int RUNS = 20;

  private static TestDatabase db;
  private static JpaTestStack app;
  private static UUID bank;
  private static StringRedisTemplate redisA;
  private static StringRedisTemplate redisB;
  private static final List<RedisMessageListenerContainer> CONTAINERS = new ArrayList<>();

  @BeforeAll
  static void start() {
    db = TestDatabase.create();
    app = JpaTestStack.of(db, "fs_app");
    bank = db.createInstitution("timing-bank");
    redisA = Instances.redis();
    redisB = Instances.redis();
    redisA.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  @AfterAll
  static void stop() {
    CONTAINERS.forEach(RedisMessageListenerContainer::stop);
  }

  private static SessionStateCache cache(SafeRedis redis, Duration redisTtl, Duration localTtl) {
    return new SessionStateCache(
        redis,
        app.tenants(),
        new StaffAccountRepository(app.jdbc(), app.users(), app.entities()),
        new RefreshTokenRepository(app.jdbc()),
        redisTtl,
        localTtl,
        System::nanoTime,
        java.time.Clock.systemUTC());
  }

  private static StaffClaims signedIn() throws Exception {
    UUID user = UUID.randomUUID();
    UUID family = UUID.randomUUID();
    try (Connection admin = db.superuser();
        PreparedStatement insert =
            admin.prepareStatement(
                """
                INSERT INTO fraudshield.users (id, institution_id, first_name, last_name, email,
                  password_hash, role, status, employee_id, department)
                VALUES (?, ?, 'Timing', 'Test', ?, '$2a$12$abcdefghijklmnopqrstuuv0123456789abcdefghijklmnopqrst',
                  'ANALYST', 'ACTIVE', ?, 'IT')
                """);
        PreparedStatement token =
            admin.prepareStatement(
                """
                INSERT INTO fraudshield.refresh_tokens (institution_id, user_id, family_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
      insert.setObject(1, user);
      insert.setObject(2, bank);
      insert.setString(3, "timing." + user + "@bank.rw");
      insert.setString(
          4, ("T" + user.toString().replace("-", "")).substring(0, 16).toUpperCase(Locale.ROOT));
      insert.executeUpdate();
      token.setObject(1, bank);
      token.setObject(2, user);
      token.setObject(3, family);
      token.setBytes(
          4,
          java.security.MessageDigest.getInstance("SHA-256").digest(family.toString().getBytes()));
      token.setTimestamp(5, Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
      token.executeUpdate();
    }
    return new StaffClaims(user, bank, StaffRole.ANALYST, "Timing", "t@bank.rw", 0, family);
  }

  /** B increments the token version in a transaction, then announces it (the production order). */
  private static void endAllSessions(SessionStateCache b, StaffClaims claims) {
    long version =
        app.tenants()
            .inTenant(
                bank,
                () ->
                    new StaffAccountRepository(app.jdbc(), app.users(), app.entities())
                        .bumpTokenVersion(claims.userId()));
    b.versionChanged(claims.userId(), version);
  }

  private static long millisUntilRefused(SessionStateCache a, StaffClaims claims) {
    long start = System.nanoTime();
    while (a.isCurrent(claims)) {
      if (System.nanoTime() - start > GATE.multipliedBy(2).toNanos()) {
        break;
      }
      Thread.onSpinWait();
    }
    return (System.nanoTime() - start) / 1_000_000;
  }

  private static long[] measure(SessionStateCache a, SessionStateCache b) throws Exception {
    long[] millis = new long[RUNS];
    for (int i = 0; i < RUNS; i++) {
      StaffClaims claims = signedIn();
      assertThat(a.isCurrent(claims)).isTrue();
      assertThat(a.isCurrent(claims)).as("now cached in A").isTrue();
      endAllSessions(b, claims);
      millis[i] = millisUntilRefused(a, claims);
    }
    java.util.Arrays.sort(millis);
    return millis;
  }

  /**
   * The worst case stated analytically (ADR 0071 §6): with the announcement and the Redis write
   * both lost, an instance answers from a value read before the change for at most {@code
   * max(redisTtl, localTtl)} after its read began, however long the read took. The read is made
   * slow for real (a table lock) while fake time moves, so the bound is asserted to the
   * millisecond, independent of machine load.
   */
  @Test
  void staleAnswersEndWithinTheAnalyticBoundHoweverSlowTheRead() throws Exception {
    for (Duration stall :
        List.of(
            Duration.ZERO,
            Duration.ofMillis(500),
            Duration.ofMillis(1500),
            Duration.ofMillis(2500))) {
      FakeTime time = new FakeTime();
      SessionStateCache a =
          new SessionStateCache(
              Instances.safe(redisA),
              app.tenants(),
              new StaffAccountRepository(app.jdbc(), app.users(), app.entities()),
              new RefreshTokenRepository(app.jdbc()),
              Duration.ofSeconds(2),
              Duration.ofSeconds(1),
              time::nanos,
              time);
      Duration bound = a.worstCaseStaleness();
      assertThat(bound).isEqualTo(Duration.ofSeconds(2));
      StaffClaims claims = signedIn();
      long anchor = time.now();
      try (Connection lock = db.superuser();
          Connection observer = db.superuser()) {
        assertThat(
                time.withSlowRead(
                    lock, observer, "fraudshield.users", stall, () -> a.isCurrent(claims)))
            .isTrue();
      }
      // Another instance ends every session; its announcement and its Redis write are both lost.
      app.tenants()
          .inTenant(
              bank,
              () ->
                  new StaffAccountRepository(app.jdbc(), app.users(), app.entities())
                      .bumpTokenVersion(claims.userId()));
      long end = anchor + bound.toMillis();
      if (stall.compareTo(bound) < 0) {
        time.set(end - 1);
        assertThat(a.isCurrent(claims))
            .as("stale answer just inside the bound (read took %s)", stall)
            .isTrue();
      }
      for (long at = end; at <= end + 2000; at += 250) {
        time.set(at);
        assertThat(a.isCurrent(claims))
            .as("refused %d ms after the read began (read took %s)", at - anchor, stall)
            .isFalse();
      }
    }
  }

  @Test
  void invalidationReachesAnotherInstanceWellInsideFiveSeconds() throws Exception {
    SessionStateCache a =
        cache(Instances.safe(redisA), Duration.ofSeconds(2), Duration.ofSeconds(1));
    SessionStateCache b =
        cache(Instances.safe(redisB), Duration.ofSeconds(2), Duration.ofSeconds(1));
    CONTAINERS.add(Instances.subscribe(redisA, SessionStateCache.CHANNEL, a::onMessage));
    Thread.sleep(300);
    long[] withPubSub = measure(a, b);

    // Worst case: A never hears the announcement and B's Redis write is lost (B has no Redis), so A
    // only notices when its local and Redis entries expire.
    SessionStateCache deaf =
        cache(Instances.safe(redisA), Duration.ofSeconds(2), Duration.ofSeconds(1));
    SessionStateCache noRedis =
        cache(Instances.safe(null), Duration.ofSeconds(2), Duration.ofSeconds(1));
    long[] worstCase = measure(deaf, noRedis);

    Instances.record(
        "M7-token-version-invalidation.json",
        String.format(
            Locale.ROOT,
            "{\"runs\": %d, \"gate_ms\": 5000, \"with_pubsub_ms\": {\"p50\": %d, \"max\": %d},"
                + " \"announcement_and_redis_write_lost_ms\": {\"p50\": %d, \"max\": %d},"
                + " \"redis_ttl_ms\": 2000, \"local_ttl_ms\": 1000}%n",
            RUNS,
            withPubSub[RUNS / 2],
            withPubSub[RUNS - 1],
            worstCase[RUNS / 2],
            worstCase[RUNS - 1]));
    assertThat(withPubSub[RUNS - 1]).as("max with pub/sub, ms").isLessThan(GATE.toMillis());
    assertThat(worstCase[RUNS - 1])
        .as("max with announcement lost, ms")
        .isLessThan(GATE.toMillis());
  }

  @Test
  void signedOutSessionIsRefusedAndRedisOutageFallsBackToTheDatabase() throws Exception {
    SessionStateCache noRedis =
        cache(Instances.safe(null), Duration.ofSeconds(2), Duration.ofMillis(1));
    StaffClaims claims = signedIn();
    assertThat(noRedis.isCurrent(claims)).isTrue();
    app.tenants()
        .runInTenant(
            bank,
            () ->
                new RefreshTokenRepository(app.jdbc())
                    .revokeFamily(claims.sessionId(), Instant.now()));
    noRedis.sessionEnded(claims.sessionId());
    assertThat(noRedis.isCurrent(claims)).isFalse();
    StaffClaims other = signedIn();
    StaffClaims wrongVersion =
        new StaffClaims(other.userId(), bank, StaffRole.ANALYST, "x", "y", 7, other.sessionId());
    assertThat(noRedis.isCurrent(wrongVersion)).isFalse();
    noRedis.onMessage("not-ours");
    noRedis.onMessage("tv:not-a-uuid");
  }
}
