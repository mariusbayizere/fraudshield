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
 */
public final class ApiKeyAuthenticator {

  /** Pub/sub channel of API-key changes. */
  public static final String CHANNEL = "fs:auth:api-keys";

  private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);
  private static final byte[] DUMMY_HMAC = new byte[32];

  private final ApiKeyRepository repository;
  private final TenantTransactions tenants;
  private final SafeRedis redis;
  private final Map<Integer, byte[]> peppers;
  private final String environment;
  private final long cacheTtlNanos;
  private final Clock clock;
  private final LongSupplier nanoTime;
  private final Executor executor;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastTouched = new ConcurrentHashMap<>();

  private record Cached(Optional<ApiKeyRepository.Credential> credential, long expiresAtNanos) {}

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
    long now = nanoTime.getAsLong();
    if (cached != null && cached.expiresAtNanos() - now > 0) {
      return cached.credential();
    }
    Optional<ApiKeyRepository.Credential> loaded = repository.findCredential(keyId);
    cache.put(keyId, new Cached(loaded, now + cacheTtlNanos));
    return loaded;
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
    cache.remove(keyId);
    redis.publish(CHANNEL, keyId);
  }

  /**
   * Applies an announcement from any instance.
   *
   * @param keyId public key ID
   */
  public void onMessage(String keyId) {
    cache.remove(keyId);
  }
}
