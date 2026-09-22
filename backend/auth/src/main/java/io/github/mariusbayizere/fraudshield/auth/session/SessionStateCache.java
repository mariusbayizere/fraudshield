package io.github.mariusbayizere.fraudshield.auth.session;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decides whether an access token's session is still current (D-27, FR-06-02, FR-07-09): the
 * token's {@code token_version} must equal the account's, and its {@code sid} must name a family
 * with a live refresh token.
 *
 * <p>Three tiers, each with a short time to live: an in-process map (default 1 s), Redis (default 2
 * s) and the database. A change is written through to Redis and announced on a pub/sub channel
 * after its transaction commits, so every instance forgets it at once. If the announcement or the
 * Redis write is lost, the two time-to-live values still bound the delay (3 s by default, inside
 * the 5 s required). When Redis is down, the database answers.
 */
public final class SessionStateCache {

  /** Pub/sub channel of session invalidations. */
  public static final String CHANNEL = "fs:auth:sessions";

  private static final String VERSION_KEY = "fs:auth:tv:";
  private static final String SESSION_KEY = "fs:auth:sid:";
  private static final String VERSION_MESSAGE = "tv:";
  private static final String SESSION_MESSAGE = "sid:";
  private static final int MAX_LOCAL_ENTRIES = 50_000;

  private final SafeRedis redis;
  private final TenantTransactions tenants;
  private final StaffAccountRepository accounts;
  private final RefreshTokenRepository tokens;
  private final Duration redisTtl;
  private final long localTtlNanos;
  private final LongSupplier nanoTime;
  private final Map<UUID, Cached<Long>> versions = new ConcurrentHashMap<>();
  private final Map<UUID, Cached<Boolean>> sessions = new ConcurrentHashMap<>();

  private record Cached<T>(T value, long expiresAtNanos) {}

  /**
   * Creates the cache.
   *
   * @param redis Redis
   * @param tenants tenant transactions
   * @param accounts account repository
   * @param tokens refresh-token repository
   * @param redisTtl Redis time to live
   * @param localTtl in-process time to live
   * @param nanoTime monotonic clock (System::nanoTime outside tests)
   */
  public SessionStateCache(
      SafeRedis redis,
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      RefreshTokenRepository tokens,
      Duration redisTtl,
      Duration localTtl,
      LongSupplier nanoTime) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.redisTtl = Objects.requireNonNull(redisTtl, "redisTtl");
    this.localTtlNanos = localTtl.toNanos();
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  /**
   * Whether the token's session is current.
   *
   * @param claims the token's claims
   * @return whether the token version matches and the session is signed in
   */
  public boolean isCurrent(StaffClaims claims) {
    Long version = cachedVersion(claims.userId());
    Boolean active = cachedSession(claims.sessionId());
    if (version == null || active == null) {
      State state = load(claims);
      version = state.version();
      active = state.active();
    }
    return version != null && version == claims.tokenVersion() && active;
  }

  private record State(Long version, boolean active) {}

  private Long cachedVersion(UUID userId) {
    Cached<Long> local = versions.get(userId);
    if (local != null && local.expiresAtNanos() - nanoTime.getAsLong() > 0) {
      return local.value();
    }
    Long remote = redis.get(VERSION_KEY + userId).map(Long::valueOf).orElse(null);
    if (remote != null) {
      putLocal(versions, userId, remote);
    }
    return remote;
  }

  private Boolean cachedSession(UUID sessionId) {
    Cached<Boolean> local = sessions.get(sessionId);
    if (local != null && local.expiresAtNanos() - nanoTime.getAsLong() > 0) {
      return local.value();
    }
    Boolean remote = redis.get(SESSION_KEY + sessionId).map("1"::equals).orElse(null);
    if (remote != null) {
      putLocal(sessions, sessionId, remote);
    }
    return remote;
  }

  private State load(StaffClaims claims) {
    State state =
        tenants.inTenant(
            claims.institutionId(),
            () ->
                new State(
                    accounts.tokenVersion(claims.userId()).orElse(null),
                    tokens.familyActive(claims.sessionId())));
    if (state.version() != null) {
      redis.set(VERSION_KEY + claims.userId(), state.version().toString(), redisTtl);
      putLocal(versions, claims.userId(), state.version());
    }
    redis.set(SESSION_KEY + claims.sessionId(), state.active() ? "1" : "0", redisTtl);
    putLocal(sessions, claims.sessionId(), state.active());
    return state;
  }

  private <T> void putLocal(Map<UUID, Cached<T>> map, UUID key, T value) {
    long now = nanoTime.getAsLong();
    if (map.size() >= MAX_LOCAL_ENTRIES) {
      map.values().removeIf(entry -> entry.expiresAtNanos() - now <= 0);
    }
    map.put(key, new Cached<>(value, now + localTtlNanos));
  }

  /**
   * Announces a new token version; call after the change commits.
   *
   * @param userId account
   * @param version new token version
   */
  public void versionChanged(UUID userId, long version) {
    versions.remove(userId);
    redis.set(VERSION_KEY + userId, Long.toString(version), redisTtl);
    redis.publish(CHANNEL, VERSION_MESSAGE + userId);
  }

  /**
   * Announces a signed-out session; call after the change commits.
   *
   * @param sessionId session (family) ID
   */
  public void sessionEnded(UUID sessionId) {
    sessions.remove(sessionId);
    redis.set(SESSION_KEY + sessionId, "0", redisTtl);
    redis.publish(CHANNEL, SESSION_MESSAGE + sessionId);
  }

  /**
   * Applies an invalidation announced by any instance.
   *
   * @param message the pub/sub message
   */
  public void onMessage(String message) {
    try {
      if (message.startsWith(VERSION_MESSAGE)) {
        versions.remove(UUID.fromString(message.substring(VERSION_MESSAGE.length())));
      } else if (message.startsWith(SESSION_MESSAGE)) {
        sessions.remove(UUID.fromString(message.substring(SESSION_MESSAGE.length())));
      }
    } catch (IllegalArgumentException ignored) {
      // Not a message of ours; the time to live still bounds staleness.
    }
  }
}
