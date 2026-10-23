package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.mariusbayizere.fraudshield.decision.testing.MutableClock;
import io.github.mariusbayizere.fraudshield.decision.testing.RedisTestServer;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Review finding 1: claims are owned, and completion outlives the lease (ADR 0067). */
@Tag("FR-01-03")
class RedisIdempotencyTest {

  private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
  private static final Duration LEASE = Duration.ofMillis(200);
  private static final byte[] FINGERPRINT = new byte[] {1, 2, 3};
  private static final byte[] OTHER_FINGERPRINT = new byte[] {9, 9, 9};

  private static RedisTestServer server;
  private static StatefulRedisConnection<String, String> connection;

  private MutableClock clock;
  private RedisIdempotency store;
  private UUID institution;

  @BeforeAll
  static void start() {
    server = new RedisTestServer();
    connection = server.connect();
  }

  @AfterAll
  static void stop() {
    connection.close();
    server.close();
  }

  @BeforeEach
  void fresh() {
    clock = new MutableClock(NOW);
    store = new RedisIdempotency(connection, LEASE, Duration.ofSeconds(2), clock);
    institution = UUID.randomUUID();
    // Past the verification window every new institution starts with (Redis may have lost data).
    store.claim(institution, UUID.randomUUID(), FINGERPRINT);
    clock.advance(Duration.ofHours(25));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private IdempotencyStore.Claimed claim(UUID transaction) {
    return (IdempotencyStore.Claimed) store.claim(institution, transaction, FINGERPRINT);
  }

  private static void outliveTheLease() throws InterruptedException {
    Thread.sleep(LEASE.toMillis() * 2);
  }

  @Test
  void decisionsCompletedAfterTheLeaseAreStillReplayedNotConflicts() throws Exception {
    UUID tx = UUID.randomUUID();
    IdempotencyStore.Claimed claim = claim(tx);
    assertThat(claim.verify()).isFalse();
    outliveTheLease();
    assertThat(store.complete(institution, tx, claim, FINGERPRINT, bytes("first")))
        .isEqualTo(bytes("first"));
    assertThat(store.claim(institution, tx, FINGERPRINT))
        .isEqualTo(new IdempotencyStore.Replay(bytes("first")));
    assertThat(store.claim(institution, tx, OTHER_FINGERPRINT))
        .isInstanceOf(IdempotencyStore.Conflict.class);
  }

  @Test
  void whenDuplicatesBothDecideTheFirstCompletedResponseStandsForBoth() throws Exception {
    UUID tx = UUID.randomUUID();
    IdempotencyStore.Claimed slow = claim(tx);
    outliveTheLease();
    IdempotencyStore.Claimed duplicate = claim(tx);
    assertThat(store.complete(institution, tx, duplicate, FINGERPRINT, bytes("duplicate")))
        .isEqualTo(bytes("duplicate"));
    assertThat(store.complete(institution, tx, slow, FINGERPRINT, bytes("slow")))
        .as("the response already given stands")
        .isEqualTo(bytes("duplicate"));
    assertThat(store.decided(institution, tx)).contains(bytes("duplicate"));
  }

  @Test
  void onlyTheOwnerCanReleaseOrMarkItsClaim() throws Exception {
    UUID tx = UUID.randomUUID();
    IdempotencyStore.Claimed expired = claim(tx);
    outliveTheLease();
    final IdempotencyStore.Claimed current = claim(tx);
    store.release(institution, tx, expired);
    store.uncertain(institution, tx, expired);
    assertThat(store.claim(institution, tx, FINGERPRINT))
        .as("still the current owner's")
        .isInstanceOf(IdempotencyStore.InFlight.class);
    store.release(institution, tx, current);
    assertThat(store.claim(institution, tx, FINGERPRINT))
        .isInstanceOf(IdempotencyStore.Claimed.class);
  }

  @Test
  void uncertainAttemptsAreVerifiedByTheNextClaim() {
    UUID tx = UUID.randomUUID();
    IdempotencyStore.Claimed claim = claim(tx);
    store.uncertain(institution, tx, claim);
    IdempotencyStore.Claimed next = claim(tx);
    assertThat(next.verify()).isTrue();
    assertThat(store.claim(institution, tx, OTHER_FINGERPRINT))
        .isInstanceOf(IdempotencyStore.Conflict.class);
  }

  @Test
  void newClaimsAreVerifiedForOneDayAfterDataLossOrWhenRequired() {
    UUID fresh = UUID.randomUUID();
    assertThat(
            ((IdempotencyStore.Claimed) store.claim(fresh, UUID.randomUUID(), FINGERPRINT))
                .verify())
        .as("an institution Redis knows nothing about may have lost records")
        .isTrue();
    clock.advance(Duration.ofHours(23));
    assertThat(
            ((IdempotencyStore.Claimed) store.claim(fresh, UUID.randomUUID(), FINGERPRINT))
                .verify())
        .isTrue();
    clock.advance(Duration.ofHours(2));
    assertThat(
            ((IdempotencyStore.Claimed) store.claim(fresh, UUID.randomUUID(), FINGERPRINT))
                .verify())
        .isFalse();

    assertThat(claim(UUID.randomUUID()).verify()).isFalse();
    store.requireVerification(institution, clock.instant().plus(Duration.ofHours(1)));
    store.requireVerification(institution, clock.instant().plus(Duration.ofMinutes(1)));
    clock.advance(Duration.ofMinutes(30));
    assertThat(claim(UUID.randomUUID()).verify()).as("the later window is kept").isTrue();
    clock.advance(Duration.ofMinutes(31));
    assertThat(claim(UUID.randomUUID()).verify()).isFalse();
  }
}
