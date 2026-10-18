package io.github.mariusbayizere.fraudshield.auth.apikey;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import org.springframework.dao.DataAccessException;

/**
 * Authenticates {@code X-API-Key} (D-19, FR-06-07, FR-07-05).
 *
 * <p>The key ID selects the stored record; the secret is HMAC-SHA256'd with the server pepper of
 * the record's version and compared in constant time. bcrypt is deliberately not used: at 10,000
 * requests per second its 100-300 ms per check is a denial of service (D-19).
 *
 * <p>Records are cached in process for 2 seconds. A revocation or rotation is announced on a Redis
 * channel after it commits and evicts the record everywhere at once; if the announcement is lost,
 * the time to live still makes a revoked key fail within 2 seconds, inside the required 5. The
 * cache holds the stored HMAC, never the secret, so a cached record cannot admit a wrong secret.
 *
 * <p>While the database cannot answer, a record verified within the last {@code staleIfError} (10
 * minutes in production) keeps being used, so ingestion keeps deciding through a PostgreSQL outage
 * (D-15; owner decision 2026-10-17, threat model R-13). An announced revocation evicts the record,
 * even while a read is blocked, and a read in flight when it arrives is not cached. What this can
 * miss is a revocation this instance is not told about: any committed after its last successful
 * read of the key, which may be up to {@code staleIfError} before the outage began, or during the
 * outage if it is local to this instance (other instances can still commit), whose announcement it
 * did not receive. A key with no record in this instance, or one read longer ago than the cap, is
 * refused with the database's exception, which the filter answers 503.
 */
public final class ApiKeyAuthenticator {

  /** Pub/sub channel of API-key changes. */
  public static final String CHANNEL = "fs:auth:api-keys";

  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
  private static final byte[] DUMMY_HMAC = new byte[32];
  private static final int MAX_CACHED_KEYS = 10_000;

  private final ApiKeyRepository repository;
  private final TenantTransactions tenants;
  private final SafeRedis redis;
  private final Map<Integer, byte[]> peppers;
  private final String environment;
  private final long cacheTtlNanos;
  private final long staleIfErrorNanos;
  private final Clock clock;
  private final LongSupplier nanoTime;
  private final Executor executor;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  // Counts announcements. A read that overlapped one does not cache its result, which may predate
  // the change the announcement reported (review of the delta fixes, N1).
  private final java.util.concurrent.atomic.AtomicLong announcements =
      new java.util.concurrent.atomic.AtomicLong();
  private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

  /**
   * A cached record.
   *
   * @param credential the record
   * @param expiresAtNanos when it must be read again
   * @param readAtNanos when it was last read successfully, which bounds its use during an outage
   */
  private record Cached(
      Optional<ApiKeyRepository.Credential> credential, long expiresAtNanos, long readAtNanos) {}

  /**
   * Creates the authenticator.
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
   * Creates the authenticator, serving a recently verified record while the database cannot answer.
   *
   * @param repository key repository
   * @param tenants tenant transactions (for last-used updates)
   * @param redis Redis (for revocation announcements)
   * @param peppers peppers by version, each at least 32 bytes
   * @param environment the deployment's key environment segment
   * @param cacheTtl record cache time to live
   * @param staleIfError how long after its last successful read a record may still be used while
   *     the database cannot answer; zero refuses at once
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
    this.staleIfErrorNanos = staleIfError.toNanos();
    this.repository = Objects.requireNonNull(repository, "repository");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
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
    long now = nanoTime.getAsLong(); // before the read: anchors the analytic bound
    long announcedBefore = announcements.get();
    // A record renewed while the database was down is still held to the cap from its last read.
    if (cached != null
        && cached.expiresAtNanos() - now > 0
        && now - cached.readAtNanos() <= Math.max(cacheTtlNanos, staleIfErrorNanos)) {
      return cached.credential();
    }
    Optional<ApiKeyRepository.Credential> loaded;
    try {
      loaded = repository.findCredential(keyId);
    } catch (DataAccessException storeDown) {
      // Usable for staleIfError after its last successful read (D-15, R-13). The renewal runs from
      // the end of the failed read, so the next read is a TTL away however long this one blocked;
      // the cap stays anchored to readAtNanos. It replaces only the entry this read started from:
      // an announcement that evicted the record while the read was blocked must stay in force.
      if (cached != null && now - cached.readAtNanos() <= staleIfErrorNanos) {
        Cached renewed =
            new Cached(
                cached.credential(), nanoTime.getAsLong() + cacheTtlNanos, cached.readAtNanos());
        if (cache.replace(keyId, cached, renewed)) {
          return cached.credential();
        }
        // Another request got there first. If it renewed the same record (same read, same
        // instance), serve it; if it read the database successfully since, its record is fresher
        // and is served. An entry that is gone was evicted by an announcement, and is not.
        Cached current = cache.get(keyId);
        if (current != null
            && current.readAtNanos() == cached.readAtNanos()
            && current.credential() == cached.credential()) {
          return cached.credential();
        }
        if (current != null && current.readAtNanos() - cached.readAtNanos() > 0) {
          return current.credential();
        }
      }
      throw storeDown;
    }
    // Only existing keys are cached: caching misses would let random key IDs grow the map without
    // bound (review finding 2). The map is also capped.
    if (loaded.isPresent()) {
      if (cache.size() >= MAX_CACHED_KEYS) {
        cache.values().removeIf(entry -> entry.expiresAtNanos() - now <= 0);
        if (cache.size() >= MAX_CACHED_KEYS) {
          cache.clear();
        }
      }
      // A record read while an announcement arrived is used for this request only: cached, it
      // could outlive the announcement through an outage (stale-if-error). An announcement counts
      // before it removes, and compute holds the key against that removal, so none slips between
      // the check and the insert.
      Cached fresh = new Cached(loaded, now + cacheTtlNanos, now);
      cache.compute(
          keyId, (key, previous) -> announcements.get() == announcedBefore ? fresh : previous);
    }
    return loaded;
  }

  /**
   * The longest this instance can keep admitting a key after its revocation committed, when the
   * announcement is lost, <em>while this instance can read the database</em> (ADR 0071 §6). A
   * record is cached until the time taken <em>before</em> its database read plus the cache TTL, and
   * a record read before the revocation committed was read after that time, so it expires no later
   * than the commit plus the TTL, however slow the read. While this instance cannot read the
   * database, an unannounced revocation can be missed for up to {@code staleIfError} after the
   * record's last read (D-15, threat model R-13).
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
    executor.execute(
        () ->
            tenants.runInTenant(
                credential.institutionId(), () -> repository.touch(credential.id(), now)));
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
