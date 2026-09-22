package io.github.mariusbayizere.fraudshield.auth.session;

import io.github.mariusbayizere.fraudshield.audit.jdbc.TenantTransactions;
import io.github.mariusbayizere.fraudshield.auth.account.StaffAccountRepository;
import io.github.mariusbayizere.fraudshield.auth.jwt.StaffClaims;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Decides whether an access token's session is still current (D-27, FR-06-02, FR-07-09): the
 * token's {@code token_version} must equal the account's, and its {@code sid} must name a family
 * with a live refresh token.
 *
 * <p>Three tiers, each with a short time to live: an in-process map (default 1 s), Redis (default 2
 * s) and the database. A change is written through to Redis and announced on a pub/sub channel
 * after its transaction commits, so every instance forgets it at once. When Redis is down, the
 * database answers.
 *
 * <p>If the announcement and the Redis write are both lost, the delay is bounded analytically by
 * {@link #worstCaseStaleness()}: {@code max(redisTtl, localTtl)} (2 s by default) plus the clock
 * skew between instances, whatever the machine load (ADR 0071 §6). Both tiers are anchored to a
 * time taken <em>before</em> the database read, so a slow read cannot extend them:
 *
 * <ul>
 *   <li>a value loaded from the database lives in process until {@code start + localTtl};
 *   <li>the Redis value carries the wall-clock deadline {@code start + redisTtl}, after which no
 *       instance trusts it, and an in-process copy of it never outlives that deadline.
 * </ul>
 *
 * <p>A stale value was read before the change committed, so every copy of it has expired by the
 * commit time plus that bound, and the next check reads the database.
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
  private final Clock clock;
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
   * @param clock wall clock, for the Redis deadline shared between instances
   */
  public SessionStateCache(
      SafeRedis redis,
      TenantTransactions tenants,
      StaffAccountRepository accounts,
      RefreshTokenRepository tokens,
      Duration redisTtl,
      Duration localTtl,
      LongSupplier nanoTime,
      Clock clock) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.tenants = Objects.requireNonNull(tenants, "tenants");
    this.accounts = Objects.requireNonNull(accounts, "accounts");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.redisTtl = Objects.requireNonNull(redisTtl, "redisTtl");
    this.localTtlNanos = localTtl.toNanos();
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The longest an instance can keep answering from a value read before a change committed, when
   * the change's announcement and Redis write are both lost, excluding clock skew between instances
   * (ADR 0071 §6).
   *
   * @return {@code max(redisTtl, localTtl)}
   */
  public Duration worstCaseStaleness() {
    Duration local = Duration.ofNanos(localTtlNanos);
    return redisTtl.compareTo(local) >= 0 ? redisTtl : local;
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
    return cached(versions, userId, VERSION_KEY, Long::valueOf);
  }

  private Boolean cachedSession(UUID sessionId) {
    return cached(sessions, sessionId, SESSION_KEY, "1"::equals);
  }

  private <T> T cached(
      Map<UUID, Cached<T>> map, UUID key, String prefix, Function<String, T> parse) {
    long now = nanoTime.getAsLong();
    Cached<T> local = map.get(key);
    if (local != null && local.expiresAtNanos() - now > 0) {
      return local.value();
    }
    String raw = redis.get(prefix + key).orElse(null);
    int separator = raw == null ? -1 : raw.lastIndexOf('|');
    if (separator < 0) {
      return null; // absent, or written without a deadline: not trusted
    }
    long remainingMillis;
    T value;
    try {
      remainingMillis = Long.parseLong(raw.substring(separator + 1)) - clock.millis();
      value = parse.apply(raw.substring(0, separator));
    } catch (NumberFormatException e) {
      return null;
    }
    if (remainingMillis <= 0) {
      return null; // past its deadline: the database answers
    }
    long life = Math.min(localTtlNanos, Duration.ofMillis(remainingMillis).toNanos());
    putLocal(map, key, value, now + life);
    return value;
  }

  private State load(StaffClaims claims) {
    // Anchored before the read: a slow read or a stalled thread cannot extend either tier.
    long startNanos = nanoTime.getAsLong();
    long deadline = clock.millis() + redisTtl.toMillis();
    State state =
        tenants.inTenant(
            claims.institutionId(),
            () ->
                new State(
                    accounts.tokenVersion(claims.userId()).orElse(null),
                    tokens.familyActive(claims.sessionId())));
    boolean redisUseful = deadline - clock.millis() > 0;
    if (state.version() != null) {
      if (redisUseful) {
        redis.set(
            VERSION_KEY + claims.userId(), stamped(state.version().toString(), deadline), redisTtl);
      }
      putLocal(versions, claims.userId(), state.version(), startNanos + localTtlNanos);
    }
    if (redisUseful) {
      redis.set(
          SESSION_KEY + claims.sessionId(),
          stamped(state.active() ? "1" : "0", deadline),
          redisTtl);
    }
    putLocal(sessions, claims.sessionId(), state.active(), startNanos + localTtlNanos);
    return state;
  }

  private static String stamped(String value, long deadlineMillis) {
    return value + "|" + deadlineMillis;
  }

  private <T> void putLocal(Map<UUID, Cached<T>> map, UUID key, T value, long expiresAtNanos) {
    long now = nanoTime.getAsLong();
    if (map.size() >= MAX_LOCAL_ENTRIES) {
      map.values().removeIf(entry -> entry.expiresAtNanos() - now <= 0);
    }
    map.put(key, new Cached<>(value, expiresAtNanos));
  }

  /**
   * Announces a new token version; call after the change commits.
   *
   * @param userId account
   * @param version new token version
   */
  public void versionChanged(UUID userId, long version) {
    versions.remove(userId);
    redis.set(
        VERSION_KEY + userId,
        stamped(Long.toString(version), clock.millis() + redisTtl.toMillis()),
        redisTtl);
    redis.publish(CHANNEL, VERSION_MESSAGE + userId);
  }

  /**
   * Announces a signed-out session; call after the change commits.
   *
   * @param sessionId session (family) ID
   */
  public void sessionEnded(UUID sessionId) {
    sessions.remove(sessionId);
    redis.set(
        SESSION_KEY + sessionId, stamped("0", clock.millis() + redisTtl.toMillis()), redisTtl);
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
