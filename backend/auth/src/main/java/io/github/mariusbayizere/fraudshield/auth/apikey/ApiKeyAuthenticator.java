package io.github.mariusbayizere.fraudshield.auth.apikey;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticates {@code X-API-Key} (D-19, FR-06-07, FR-07-05).
 *
 * <p>The key ID selects the stored record; the secret is HMAC-SHA256'd with the server pepper of
 * the record's version and compared in constant time. bcrypt is deliberately not used: at 10,000
 * requests per second its 100-300 ms per check is a denial of service (D-19).
 *
 * <p>The record cache implements docs/architecture/api-key-cache-contract.md, whose rule numbers
 * the comments cite. Records are cached for 2 seconds. A revocation or rotation is announced on a
 * Redis channel after it commits and evicts the record everywhere at once; if the announcement is
 * lost, the time to live still makes a revoked key fail within 2 seconds while this instance can
 * read the database. The cache holds the stored HMAC, never the secret.
 *
 * <p>While the database is unavailable (R8), a cached record keeps being used until {@code
 * staleIfError} (10 minutes in production) after the read that produced it, so ingestion keeps
 * deciding through a PostgreSQL outage (D-15; owner decision 2026-10-17, threat model R-13). A
 * received announcement is never undone. What this can miss is a revocation committed after the
 * record's read whose announcement this instance did not receive, for up to {@code staleIfError}. A
 * key with no cached record is refused with {@link KeyStoreUnavailableException}, which the filters
 * answer 503; so is a read of an uncached key when the bulkhead (R9) is full.
 */
public final class ApiKeyAuthenticator {

  /** Pub/sub channel of API-key changes. */
  public static final String CHANNEL = "fs:auth:api-keys";

  /** Default bulkhead size for reads of uncached keys (R9). */
  public static final int DEFAULT_UNCACHED_READ_PERMITS = 4;

  /** Default wait for a bulkhead permit (R9). */
  public static final Duration DEFAULT_UNCACHED_READ_WAIT = Duration.ofMillis(500);

  private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuthenticator.class);
  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
  private static final byte[] DUMMY_HMAC = new byte[32];
  private static final int MAX_CACHED_KEYS = 10_000;

  private final CredentialReader reader;
  private final UseRecorder recorder;
  private final SafeRedis redis;
  private final Map<Integer, byte[]> peppers;
  private final String environment;
  private final long cacheTtlNanos;
  private final long staleIfErrorNanos;
  private final Clock clock;
  private final LongSupplier nanoTime;
  private final Executor executor;
  private final Semaphore uncachedReads;
  private final long uncachedReadWaitNanos;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  // R4(a): every announcement counts here before it removes, so a read that overlapped one is not
  // installed (R2(c)).
  private final AtomicLong announcements = new AtomicLong();
  private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

  /** The cache's read: the repository in production, a scripted reader in the contract tests. */
  @FunctionalInterface
  interface CredentialReader {

    /**
     * Reads a key's record.
     *
     * @param keyId public key ID
     * @return the record, or empty if no such key exists
     */
    Optional<ApiKeyRepository.Credential> find(String keyId);
  }

  /** Records that a key was used. */
  @FunctionalInterface
  interface UseRecorder {

    /**
     * Records a use.
     *
     * @param credential the key's record
     * @param at when
     */
    void used(ApiKeyRepository.Credential credential, Instant at);
  }

  /**
   * A cache entry. Replaced, never mutated: installs and renewals happen inside the key's {@code
   * compute}; removals (R2(e), R4, R6) are atomic map operations of their own.
   *
   * @param credential the record, always usable when installed (R2(e))
   * @param expiresAtNanos e: when it must be read again
   * @param readAtNanos r: the start of the read that produced it, which bounds stale use
   */
  private record Cached(
      ApiKeyRepository.Credential credential, long expiresAtNanos, long readAtNanos) {}

  /**
   * Creates the authenticator without stale use: a key whose record cannot be read is refused,
   * unless a read begun after the failed one has already installed a fresher record (R3(b)).
   *
   * @param repository key repository
   * @param tenants tenant transactions (for last-used updates)
   * @param redis Redis (for revocation announcements)
   * @param peppers peppers by version, each at least 32 bytes
   * @param environment the deployment's key environment segment
   * @param cacheTtl record cache time to live
   * @param clock clock
   * @param nanoTime monotonic clock
   * @param executor executor for last-used updates
   */
  public ApiKeyAuthenticator(
      ApiKeyRepository repository,
      TenantTransactions tenants,
      SafeRedis redis,
      Map<Integer, byte[]> peppers,
      String environment,
      Duration cacheTtl,
      Clock clock,
      LongSupplier nanoTime,
      Executor executor) {
    this(
        repository,
        tenants,
        redis,
        peppers,
        environment,
        cacheTtl,
        Duration.ZERO,
        clock,
        nanoTime,
        executor);
  }

  /**
   * Creates the authenticator, serving a cached record while the database is unavailable.
   *
   * @param repository key repository
   * @param tenants tenant transactions (for last-used updates)
   * @param redis Redis (for revocation announcements)
   * @param peppers peppers by version, each at least 32 bytes
   * @param environment the deployment's key environment segment
   * @param cacheTtl record cache time to live
   * @param staleIfError how long after the read that produced it a record may still be used while
   *     the database is unavailable; zero refuses at once
   * @param clock clock
   * @param nanoTime monotonic clock
   * @param executor executor for last-used updates
   */
  public ApiKeyAuthenticator(
      ApiKeyRepository repository,
      TenantTransactions tenants,
      SafeRedis redis,
      Map<Integer, byte[]> peppers,
      String environment,
      Duration cacheTtl,
      Duration staleIfError,
      Clock clock,
      LongSupplier nanoTime,
      Executor executor) {
    this(
        repository,
        tenants,
        redis,
        peppers,
        environment,
        cacheTtl,
        staleIfError,
        DEFAULT_UNCACHED_READ_PERMITS,
        DEFAULT_UNCACHED_READ_WAIT,
        clock,
        nanoTime,
        executor);
  }

  /**
   * Creates the authenticator with an explicit bulkhead for reads of uncached keys (R9).
   *
   * @param repository key repository
   * @param tenants tenant transactions (for last-used updates)
   * @param redis Redis (for revocation announcements)
   * @param peppers peppers by version, each at least 32 bytes
   * @param environment the deployment's key environment segment
   * @param cacheTtl record cache time to live
   * @param staleIfError how long after the read that produced it a record may still be used while
   *     the database is unavailable; zero refuses at once
   * @param uncachedReadPermits how many reads of uncached keys may run at once; must be below the
   *     connection pool's size
   * @param uncachedReadWait how long a read of an uncached key waits for a permit
   * @param clock clock
   * @param nanoTime monotonic clock
   * @param executor executor for last-used updates
   */
  public ApiKeyAuthenticator(
      ApiKeyRepository repository,
      TenantTransactions tenants,
      SafeRedis redis,
      Map<Integer, byte[]> peppers,
      String environment,
      Duration cacheTtl,
      Duration staleIfError,
      int uncachedReadPermits,
      Duration uncachedReadWait,
      Clock clock,
      LongSupplier nanoTime,
      Executor executor) {
    this(
        Objects.requireNonNull(repository, "repository")::findCredential,
        recordUseWith(repository, tenants),
        redis,
        peppers,
        environment,
        cacheTtl,
        staleIfError,
        uncachedReadPermits,
        uncachedReadWait,
        clock,
        nanoTime,
        executor);
  }

  ApiKeyAuthenticator(
      CredentialReader reader,
      UseRecorder recorder,
      SafeRedis redis,
      Map<Integer, byte[]> peppers,
      String environment,
      Duration cacheTtl,
      Duration staleIfError,
      int uncachedReadPermits,
      Duration uncachedReadWait,
      Clock clock,
      LongSupplier nanoTime,
      Executor executor) {
    if (uncachedReadPermits < 1) {
      throw new IllegalStateException("uncached key reads need at least one permit");
    }
    this.staleIfErrorNanos = staleIfError.toNanos();
    this.uncachedReads = new Semaphore(uncachedReadPermits);
    this.uncachedReadWaitNanos = uncachedReadWait.toNanos();
    this.reader = Objects.requireNonNull(reader, "reader");
    this.recorder = Objects.requireNonNull(recorder, "recorder");
    this.redis = Objects.requireNonNull(redis, "redis");
    this.peppers = Map.copyOf(peppers);
    if (this.peppers.isEmpty()) {
      throw new IllegalStateException(
          "fraudshield.auth.api-keys.peppers must hold at least one pepper");
    }
    this.peppers
        .values()
        .forEach(
            p -> {
              if (p.length < Crypto.MIN_KEY_BYTES) {
                throw new IllegalStateException("API-key peppers must be at least 32 bytes");
              }
            });
    this.environment = ApiKeyFormat.requireEnvironment(environment);
    this.cacheTtlNanos = cacheTtl.toNanos();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * Authenticates a presented key.
   *
   * @param presented the X-API-Key header value
   * @return the principal, or empty if the key is malformed, unknown, revoked, past its rotation
   *     overlap, from another environment or has a wrong secret
   */
  public Optional<ApiKeyPrincipal> authenticate(String presented) {
    Optional<ApiKeyFormat.ParsedKey> parsed = ApiKeyFormat.parse(presented);
    if (parsed.isEmpty() || !parsed.get().environment().equals(environment)) {
      return Optional.empty();
    }
    ApiKeyFormat.ParsedKey key = parsed.get();
    Optional<ApiKeyRepository.Credential> credential = credential(key.keyId());
    byte[] pepper = credential.map(c -> peppers.get(c.pepperVersion())).orElse(null);
    // An unknown key is hashed too, so the response time does not reveal which key IDs exist.
    byte[] presentedHmac =
        Crypto.hmacSha256(
            pepper == null ? peppers.values().iterator().next() : pepper,
            key.secret().getBytes(StandardCharsets.US_ASCII));
    byte[] storedHmac = credential.map(ApiKeyRepository.Credential::secretHmac).orElse(DUMMY_HMAC);
    boolean secretMatches = Crypto.constantTimeEquals(presentedHmac, storedHmac);
    if (credential.isEmpty() || pepper == null || !secretMatches || !usable(credential.get())) {
      return Optional.empty();
    }
    ApiKeyRepository.Credential valid = credential.get();
    touch(valid);
    EnumSet<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
    scopes.addAll(valid.scopes());
    return Optional.of(new ApiKeyPrincipal(valid.id(), key.keyId(), valid.institutionId(), scopes));
  }

  private boolean usable(ApiKeyRepository.Credential credential) {
    return switch (credential.state()) {
      case "ACTIVE" -> true;
      case "ROTATING" ->
          credential.expiresAt() != null && credential.expiresAt().isAfter(clock.instant());
      default -> false;
    };
  }

  private Optional<ApiKeyRepository.Credential> credential(String keyId) {
    Cached cached = cache.get(keyId);
    return cached == null ? uncached(keyId) : cachedOrRead(keyId, cached);
  }

  // R1. With staleIfError below the TTL, an entry is still served within its TTL.
  private Optional<ApiKeyRepository.Credential> cachedOrRead(String keyId, Cached cached) {
    long now = nanoTime.getAsLong();
    if (cached.expiresAtNanos() - now > 0
        && now - cached.readAtNanos() <= Math.max(cacheTtlNanos, staleIfErrorNanos)) {
      return Optional.of(cached.credential());
    }
    return read(keyId);
  }

  // R9: a key with no entry reads only with a permit, so a flood of unknown key IDs holds at most
  // the bulkhead's connections. After the wait the key is looked up again: another request may
  // have installed it meanwhile.
  private Optional<ApiKeyRepository.Credential> uncached(String keyId) {
    boolean permit;
    try {
      permit = uncachedReads.tryAcquire(uncachedReadWaitNanos, TimeUnit.NANOSECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new KeyStoreUnavailableException("interrupted waiting to read a key", interrupted);
    }
    Cached arrived = cache.get(keyId);
    if (arrived != null) {
      if (permit) {
        uncachedReads.release();
      }
      return cachedOrRead(keyId, arrived);
    }
    if (!permit) {
      throw new KeyStoreUnavailableException("too many reads of uncached keys in flight", null);
    }
    try {
      return read(keyId);
    } finally {
      uncachedReads.release();
    }
  }

  private Optional<ApiKeyRepository.Credential> read(String keyId) {
    long start = nanoTime.getAsLong();
    final long announced = announcements.get();
    Optional<ApiKeyRepository.Credential> loaded;
    try {
      loaded = reader.find(keyId);
    } catch (RuntimeException error) {
      if (!KeyStoreUnavailability.isUnavailable(error)) {
        throw error; // R7: a bug or a data problem, answered 500, cache untouched
      }
      return Optional.of(stale(keyId, start, error));
    }
    if (loaded.isEmpty()) {
      // R2(d): never cached; an older entry of a key that no longer exists is not left behind.
      cache.computeIfPresent(keyId, (key, entry) -> entry.readAtNanos() - start < 0 ? null : entry);
      return loaded;
    }
    ApiKeyRepository.Credential record = loaded.get();
    if (!usable(record)) {
      // R2(e): a key seen revoked or past its overlap ends this instance's use of it, whatever
      // the entry's age. Best effort: an older read in flight may still install afterwards.
      cache.remove(keyId);
      return loaded;
    }
    // R6, before and outside the key's compute: dropping touches other keys.
    if (cache.size() >= MAX_CACHED_KEYS) {
      long now = nanoTime.getAsLong();
      cache.values().removeIf(entry -> entry.expiresAtNanos() - now <= 0);
      if (cache.size() >= MAX_CACHED_KEYS) {
        cache.clear();
      }
    }
    // R2(b), R2(c): the announcement count is compared inside the compute, which holds the key
    // against R4(b)'s removal; an older read never replaces a newer entry.
    Cached fresh = new Cached(record, start + cacheTtlNanos, start);
    cache.compute(
        keyId,
        (key, previous) ->
            announcements.get() != announced
                    || (previous != null && previous.readAtNanos() - start > 0)
                ? previous
                : fresh);
    return loaded;
  }

  // R3, in one compute on the key with now read inside it.
  private ApiKeyRepository.Credential stale(String keyId, long start, RuntimeException error) {
    LOG.error("API-key store unavailable (SQLSTATE {})", rootState(error));
    ApiKeyRepository.Credential[] served = new ApiKeyRepository.Credential[1];
    cache.computeIfPresent(
        keyId,
        (key, entry) -> {
          if (start - entry.readAtNanos() < 0) {
            served[0] = entry.credential(); // R3(b): a read begun after this one succeeded
            return entry;
          }
          long now = nanoTime.getAsLong();
          if (now - entry.readAtNanos() <= staleIfErrorNanos) {
            served[0] = entry.credential(); // R3(c)
            return new Cached(entry.credential(), now + cacheTtlNanos, entry.readAtNanos());
          }
          return entry;
        });
    if (served[0] == null) {
      throw new KeyStoreUnavailableException(
          "the key store is unavailable and no usable record is cached", error);
    }
    return served[0];
  }

  private static String rootState(Throwable error) {
    String state = null;
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        state = sql.getSQLState();
      }
    }
    return state == null ? "none" : state;
  }

  /**
   * The longest this instance can keep admitting a key after its revocation committed, when the
   * announcement is lost, <em>while this instance can read the database</em> (ADR 0071 §6). A
   * record is cached until the time taken <em>before</em> its database read plus the cache TTL, and
   * a record read before the revocation committed was read after that time, so it expires no later
   * than the commit plus the TTL, however slow the read. While this instance cannot read the
   * database, an unannounced revocation can be missed for up to {@code staleIfError} after the read
   * that produced the record (D-15, threat model R-13).
   *
   * @return the cache time to live
   */
  public Duration worstCaseStaleness() {
    return Duration.ofNanos(cacheTtlNanos);
  }

  private void touch(ApiKeyRepository.Credential credential) {
    Instant now = clock.instant();
    Instant last = lastTouched.get(credential.id().toString());
    if (last != null && Duration.between(last, now).compareTo(TOUCH_INTERVAL) < 0) {
      return;
    }
    lastTouched.put(credential.id().toString(), now);
    executor.execute(() -> recorder.used(credential, now));
  }

  private static UseRecorder recordUseWith(
      ApiKeyRepository repository, TenantTransactions tenants) {
    Objects.requireNonNull(tenants, "tenants");
    return (credential, at) ->
        tenants.runInTenant(
            credential.institutionId(), () -> repository.touch(credential.id(), at));
  }

  /**
   * Announces a changed key to every instance; call after the change commits.
   *
   * @param keyId public key ID
   */
  public void keyChanged(String keyId) {
    announcements.incrementAndGet();
    cache.remove(keyId);
    redis.publish(CHANNEL, keyId);
  }

  /**
   * Applies an announcement from any instance.
   *
   * @param keyId public key ID
   */
  public void onMessage(String keyId) {
    announcements.incrementAndGet();
    cache.remove(keyId);
  }
}
