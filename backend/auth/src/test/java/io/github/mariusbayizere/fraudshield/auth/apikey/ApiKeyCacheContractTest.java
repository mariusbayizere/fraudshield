package io.github.mariusbayizere.fraudshield.auth.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.auth.testing.FakeTime;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/**
 * The record cache against docs/architecture/api-key-cache-contract.md, rule by rule, with a
 * scripted key store and a test-driven clock: each test names the property (P-number) it proves.
 * The database-backed scenarios are in {@link ApiKeyLifecycleTest}.
 */
@Tag("D-15")
class ApiKeyCacheContractTest {

  private static final byte[] PEPPER = Crypto.randomBytes(32);
  private static final String SECRET = "S".repeat(43);
  private static final UUID BANK = UUID.randomUUID();
  private static final long TTL_MS = 2_000;
  private static final long CAP_MS = Duration.ofMinutes(10).toMillis();

  private final FakeTime time = new FakeTime();
  private final AtomicReference<ApiKeyAuthenticator.CredentialReader> store =
      new AtomicReference<>();
  private final AtomicInteger reads = new AtomicInteger();
  private final ExecutorService threads = Executors.newCachedThreadPool();

  @AfterEach
  void stop() {
    threads.shutdownNow();
  }

  private ApiKeyAuthenticator instance() {
    return instance(4);
  }

  private ApiKeyAuthenticator instance(int permits) {
    return new ApiKeyAuthenticator(
        keyId -> {
          reads.incrementAndGet();
          return store.get().find(keyId);
        },
        (credential, at) -> {},
        new SafeRedis(null),
        Map.of(1, PEPPER),
        "test",
        Duration.ofMillis(TTL_MS),
        Duration.ofMillis(CAP_MS),
        permits,
        Duration.ofMillis(1_000),
        time,
        time::nanos,
        work -> {});
  }

  private static String raw(String keyId) {
    return "fsk_test_" + keyId + "_" + SECRET;
  }

  private static String id(int n) {
    return String.format("k%011d", n);
  }

  private static ApiKeyRepository.Credential record(
      String state, Instant expiresAt, ApiKeyScope scope) {
    return new ApiKeyRepository.Credential(
        UUID.nameUUIDFromBytes(state.getBytes(StandardCharsets.UTF_8)),
        BANK,
        Crypto.hmacSha256(PEPPER, SECRET.getBytes(StandardCharsets.US_ASCII)),
        1,
        List.of(scope),
        state,
        expiresAt);
  }

  private static ApiKeyRepository.Credential active() {
    return record("ACTIVE", null, ApiKeyScope.INGEST_WRITE);
  }

  private static ApiKeyRepository.Credential active(ApiKeyScope scope) {
    return record("ACTIVE", null, scope);
  }

  private static ApiKeyRepository.Credential revoked() {
    return record("REVOKED", null, ApiKeyScope.INGEST_WRITE);
  }

  private void up(ApiKeyRepository.Credential credential) {
    store.set(keyId -> Optional.of(credential));
  }

  private void down() {
    store.set(
        keyId -> {
          throw new CannotGetJdbcConnectionException(
              "Failed to obtain JDBC Connection", new SQLException("refused", "08001"));
        });
  }

  private void later(long millis) {
    time.set(time.now() + millis);
  }

  /** A reader that blocks on its latch, then answers; signals when it has started. */
  private static final class Gate {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    void await() {
      started.countDown();
      try {
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    void awaitStarted() throws InterruptedException {
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Future<Optional<ApiKeyPrincipal>> async(ApiKeyAuthenticator keys, String rawKey) {
    return threads.submit(() -> keys.authenticate(rawKey));
  }

  @Test
  void p1EntryIsAdmittedThroughAnOutageUntilTheCapAndRefusedAfter() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    long read = time.now();
    assertThat(keys.authenticate(raw(id(1)))).isPresent();
    down();
    time.set(read + CAP_MS);
    assertThat(keys.authenticate(raw(id(1)))).as("at the cap").isPresent();
    time.set(read + CAP_MS + TTL_MS + 1);
    assertThatThrownBy(() -> keys.authenticate(raw(id(1))))
        .as("past the cap")
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p2TheCapIsCheckedWhenTheFailureIsObservedNotWhenTheReadBegan() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    long read = time.now();
    assertThat(keys.authenticate(raw(id(2)))).isPresent();
    store.set(
        keyId -> {
          later(2); // the read began 1 ms inside the cap and fails 1 ms past it
          throw new CannotGetJdbcConnectionException("x", new SQLException("refused", "08001"));
        });
    time.set(read + CAP_MS - 1);
    assertThatThrownBy(() -> keys.authenticate(raw(id(2))))
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p3FailedReadBegunBeforeFresherSuccessIsAnsweredFromItWithoutRenewingIt() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(3)))).isPresent();
    later(TTL_MS + 1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          throw new CannotGetJdbcConnectionException("x", new SQLException("refused", "08001"));
        });
    final Future<Optional<ApiKeyPrincipal>> stuck = async(keys, raw(id(3)));
    gate.awaitStarted();
    later(1);
    up(active());
    assertThat(keys.authenticate(raw(id(3)))).as("a later read succeeds").isPresent();
    final long installedAt = time.now();
    later(1_000); // a renewal now would push the entry's expiry past installedAt + TTL
    gate.release.countDown();
    assertThat(stuck.get(10, TimeUnit.SECONDS)).as("served the fresher record").isPresent();
    int before = reads.get();
    time.set(installedAt + TTL_MS + 1);
    assertThat(keys.authenticate(raw(id(3)))).isPresent();
    assertThat(reads.get())
        .as("the fresher entry was not renewed: its TTL ran out")
        .isGreaterThan(before);
  }

  @Test
  void p6RenewedEntryIsNotServedPastTheCapWithoutReading() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    long read = time.now();
    assertThat(keys.authenticate(raw(id(6)))).isPresent();
    down();
    time.set(read + CAP_MS - 1_000);
    assertThat(keys.authenticate(raw(id(6)))).as("stale, renewed for a TTL").isPresent();
    int before = reads.get();
    time.set(read + CAP_MS + 500);
    assertThatThrownBy(() -> keys.authenticate(raw(id(6))))
        .isInstanceOf(KeyStoreUnavailableException.class);
    assertThat(reads.get()).as("inside the renewal's TTL, but read again").isEqualTo(before + 1);
  }

  @Test
  void p11AnnouncementForAnotherKeyLeavesThisKeysEntryNeitherReplacedNorRemoved() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    final long first = time.now();
    assertThat(keys.authenticate(raw(id(11)))).isPresent();
    later(5_000);
    store.set(
        keyId -> {
          keys.onMessage(id(99)); // another key's announcement while this read is in flight
          return Optional.of(active());
        });
    assertThat(keys.authenticate(raw(id(11)))).as("its own request is answered").isPresent();
    down();
    time.set(first + CAP_MS - 1);
    assertThat(keys.authenticate(raw(id(11)))).as("the older entry was not removed").isPresent();
    time.set(first + CAP_MS + 1);
    assertThatThrownBy(() -> keys.authenticate(raw(id(11))))
        .as("and the overlapping read was not installed, or this would still be admitted")
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p12AnnouncementForAnotherKeyDoesNotTouchThisKey() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(12)))).isPresent();
    keys.onMessage(id(98));
    keys.keyChanged(id(97));
    down();
    later(TTL_MS + 1);
    assertThat(keys.authenticate(raw(id(12)))).isPresent();
  }

  @Test
  void p13NoSuchKeyIsUnauthorisedUncachedAndRemovesTheOlderEntry() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(13)))).isPresent();
    later(TTL_MS + 1);
    store.set(keyId -> Optional.empty());
    assertThat(keys.authenticate(raw(id(13)))).as("401").isEmpty();
    int before = reads.get();
    assertThat(keys.authenticate(raw(id(13)))).isEmpty();
    assertThat(reads.get()).as("not cached").isEqualTo(before + 1);
    down();
    assertThatThrownBy(() -> keys.authenticate(raw(id(13))))
        .as("the older entry is gone, not served stale")
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p14NoSuchKeyDoesNotRemoveAnEntryFromLaterRead() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(14)))).isPresent();
    later(TTL_MS + 1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          return Optional.empty();
        });
    final Future<Optional<ApiKeyPrincipal>> missing = async(keys, raw(id(14)));
    gate.awaitStarted();
    later(1);
    up(active());
    assertThat(keys.authenticate(raw(id(14)))).isPresent();
    gate.release.countDown();
    assertThat(missing.get(10, TimeUnit.SECONDS)).isEmpty();
    down();
    later(TTL_MS + 1);
    assertThat(keys.authenticate(raw(id(14)))).as("the later entry survived").isPresent();
  }

  @Test
  void p15OlderReadDoesNotReplaceNewerEntry() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(15)))).isPresent();
    later(TTL_MS + 1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          return Optional.of(active(ApiKeyScope.JOBS_READ));
        });
    final Future<Optional<ApiKeyPrincipal>> older = async(keys, raw(id(15)));
    gate.awaitStarted();
    later(1);
    up(active(ApiKeyScope.DECISIONS_READ));
    assertThat(keys.authenticate(raw(id(15)))).isPresent();
    gate.release.countDown();
    assertThat(older.get(10, TimeUnit.SECONDS).orElseThrow().scopes())
        .containsExactly(ApiKeyScope.JOBS_READ);
    int before = reads.get();
    assertThat(keys.authenticate(raw(id(15))).orElseThrow().scopes())
        .as("the newer entry is kept")
        .containsExactly(ApiKeyScope.DECISIONS_READ);
    assertThat(reads.get()).isEqualTo(before);
  }

  @Test
  void p16FullCacheDropsExpiredEntriesFirstAndDroppedEntryIsRefusedInAnOutage() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    for (int n = 0; n < 5_000; n++) {
      assertThat(keys.authenticate(raw(id(100_000 + n)))).isPresent();
    }
    later(TTL_MS + 1);
    for (int n = 0; n < 5_000; n++) {
      assertThat(keys.authenticate(raw(id(200_000 + n)))).isPresent();
    }
    assertThat(keys.authenticate(raw(id(300_000)))).as("installs into a full cache").isPresent();
    down();
    assertThat(keys.authenticate(raw(id(200_000)))).as("a live entry is kept").isPresent();
    assertThatThrownBy(() -> keys.authenticate(raw(id(100_000))))
        .as("an expired entry was dropped and is not served stale")
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p17BothKindsOfAnnouncementEvictAndStopAnInFlightInstall() {
    for (boolean local : new boolean[] {true, false}) {
      ApiKeyAuthenticator keys = instance();
      up(active());
      assertThat(keys.authenticate(raw(id(17)))).isPresent();
      if (local) {
        keys.keyChanged(id(17));
      } else {
        keys.onMessage(id(17));
      }
      down();
      assertThatThrownBy(() -> keys.authenticate(raw(id(17))))
          .as("evicted (local=%s)", local)
          .isInstanceOf(KeyStoreUnavailableException.class);
      store.set(
          keyId -> {
            if (local) {
              keys.keyChanged(id(17));
            } else {
              keys.onMessage(id(17));
            }
            return Optional.of(active());
          });
      assertThat(keys.authenticate(raw(id(17)))).isPresent();
      down();
      assertThatThrownBy(() -> keys.authenticate(raw(id(17))))
          .as("the overlapping read was not installed (local=%s)", local)
          .isInstanceOf(KeyStoreUnavailableException.class);
    }
  }

  @Test
  void p30RevokedReadOverlappingAnotherKeysAnnouncementRemovesTheActiveEntry() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(30)))).isPresent();
    later(TTL_MS + 1);
    store.set(
        keyId -> {
          keys.onMessage(id(96));
          return Optional.of(revoked());
        });
    assertThat(keys.authenticate(raw(id(30)))).as("401 for the request itself").isEmpty();
    down();
    assertThatThrownBy(() -> keys.authenticate(raw(id(30))))
        .as("the revocation this instance read is not forgotten in the outage")
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p31RevokedReadRemovesTheEntryEvenWhenTheEntryCameFromLaterRead() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(31)))).isPresent();
    later(TTL_MS + 1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          return Optional.of(revoked());
        });
    final Future<Optional<ApiKeyPrincipal>> sawRevoked = async(keys, raw(id(31)));
    gate.awaitStarted();
    later(1);
    up(active());
    assertThat(keys.authenticate(raw(id(31)))).isPresent();
    gate.release.countDown();
    assertThat(sawRevoked.get(10, TimeUnit.SECONDS)).isEmpty();
    down();
    assertThatThrownBy(() -> keys.authenticate(raw(id(31))))
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p32RotatedKeyReadAtTheEndOfItsOverlapIsNotInstalledAndRemovesTheEntry() {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(32)))).isPresent();
    later(TTL_MS + 1);
    up(record("ROTATING", time.instant(), ApiKeyScope.INGEST_WRITE));
    assertThat(keys.authenticate(raw(id(32)))).as("overlap ends now: refused").isEmpty();
    down();
    assertThatThrownBy(() -> keys.authenticate(raw(id(32))))
        .isInstanceOf(KeyStoreUnavailableException.class);
  }

  @Test
  void p33ReadWithTheSameStartReplacesTheEntry() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(33)))).isPresent();
    later(TTL_MS + 1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          return Optional.of(active(ApiKeyScope.JOBS_READ));
        });
    final Future<Optional<ApiKeyPrincipal>> first = async(keys, raw(id(33)));
    gate.awaitStarted();
    up(active(ApiKeyScope.DECISIONS_READ)); // same instant: an equal start
    assertThat(keys.authenticate(raw(id(33)))).isPresent();
    gate.release.countDown();
    assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(keys.authenticate(raw(id(33))).orElseThrow().scopes())
        .containsExactly(ApiKeyScope.JOBS_READ);
  }

  @Test
  void p26FullBulkheadRefusesAnUnknownKeyWithoutReadingWhileCachedKeyRefreshes() throws Exception {
    ApiKeyAuthenticator keys = instance();
    up(active());
    assertThat(keys.authenticate(raw(id(260)))).isPresent();
    later(TTL_MS + 1);
    CountDownLatch holding = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    Map<String, Boolean> blocking = new ConcurrentHashMap<>();
    store.set(
        keyId -> {
          if (blocking.containsKey(keyId)) {
            holding.countDown();
            try {
              assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return Optional.of(active());
        });
    for (int n = 0; n < 4; n++) {
      blocking.put(id(261 + n), true);
      async(keys, raw(id(261 + n)));
    }
    assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
    int before = reads.get();
    long started = System.nanoTime();
    assertThatThrownBy(() -> keys.authenticate(raw(id(270))))
        .isInstanceOf(KeyStoreUnavailableException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(reads.get()).as("the fifth unknown key read nothing").isEqualTo(before);
    assertThat(keys.authenticate(raw(id(260)))).as("a cached key still refreshes").isPresent();
    assertThat(reads.get()).isEqualTo(before + 1);
    release.countDown();
  }

  @Test
  void p27PermitIsReleasedWhateverTheReadsOutcome() {
    ApiKeyAuthenticator keys = instance(1);
    store.set(
        keyId -> {
          throw new CannotGetJdbcConnectionException("x", new SQLException("refused", "08001"));
        });
    assertThatThrownBy(() -> keys.authenticate(raw(id(271))))
        .isInstanceOf(KeyStoreUnavailableException.class);
    store.set(
        keyId -> {
          throw new BadSqlGrammarException("q", "select", new SQLException("syntax", "42601"));
        });
    assertThatThrownBy(() -> keys.authenticate(raw(id(272))))
        .as("R7 propagates unchanged")
        .isInstanceOf(BadSqlGrammarException.class);
    store.set(
        keyId -> {
          throw new IllegalStateException("unexpected");
        });
    assertThatThrownBy(() -> keys.authenticate(raw(id(273))))
        .isInstanceOf(IllegalStateException.class);
    store.set(keyId -> Optional.empty());
    assertThat(keys.authenticate(raw(id(274)))).isEmpty();
    up(active());
    assertThat(keys.authenticate(raw(id(275))))
        .as("the single permit came back after every outcome")
        .isPresent();
  }

  @Test
  void p34WaiterOnTheBulkheadIsServedFromTheEntryInstalledMeanwhile() throws Exception {
    ApiKeyAuthenticator keys = instance(1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          return Optional.of(active());
        });
    final Future<Optional<ApiKeyPrincipal>> first = async(keys, raw(id(340)));
    gate.awaitStarted();
    AtomicReference<Optional<ApiKeyPrincipal>> second = new AtomicReference<>();
    Thread waiter = new Thread(() -> second.set(keys.authenticate(raw(id(340)))));
    waiter.start();
    awaitWaiting(waiter);
    gate.release.countDown();
    waiter.join(10_000);
    assertThat(first.get(10, TimeUnit.SECONDS)).isPresent();
    assertThat(second.get()).isPresent();
    assertThat(reads).as("the waiter used the installed entry").hasValue(1);
  }

  @Test
  void p34WaiterThatFindsAnExpiredEntryReadsWithoutHoldingPermit() throws Exception {
    ApiKeyAuthenticator keys = instance(1);
    Gate first = new Gate();
    Gate refresh = new Gate();
    AtomicInteger call = new AtomicInteger();
    store.set(
        keyId -> {
          if (keyId.equals(id(341))) {
            if (call.incrementAndGet() == 1) {
              first.await();
            } else {
              refresh.await();
            }
          }
          return Optional.of(active());
        });
    async(keys, raw(id(341)));
    first.awaitStarted();
    Thread waiter = new Thread(() -> keys.authenticate(raw(id(341))));
    waiter.start();
    awaitWaiting(waiter);
    later(TTL_MS + 1); // the entry the first read installs is already expired
    first.release.countDown();
    refresh.awaitStarted();
    assertThat(keys.authenticate(raw(id(342))))
        .as("the waiter's refresh holds no permit, so another uncached key gets one")
        .isPresent();
    refresh.release.countDown();
    waiter.join(10_000);
  }

  @Test
  void p35InterruptedWaitIsRefusedAndKeepsTheInterrupt() throws Exception {
    ApiKeyAuthenticator keys = instance(1);
    Gate gate = new Gate();
    store.set(
        keyId -> {
          gate.await();
          return Optional.of(active());
        });
    async(keys, raw(id(350)));
    gate.awaitStarted();
    Future<Boolean> interrupted =
        threads.submit(
            () -> {
              Thread.currentThread().interrupt();
              try {
                keys.authenticate(raw(id(351)));
                return false;
              } catch (KeyStoreUnavailableException expected) {
                return Thread.currentThread().isInterrupted();
              }
            });
    assertThat(interrupted.get(10, TimeUnit.SECONDS)).isTrue();
    gate.release.countDown();
  }

  private static void awaitWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.TIMED_WAITING) {
      assertThat(System.nanoTime()).isLessThan(deadline);
      Thread.sleep(1);
    }
  }
}
