package io.github.mariusbayizere.fraudshield.analyst.performance;

import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Who has the console open, for {@code analysts_online} (ADR 0117 point 4): one Redis sorted set
 * per institution, members the user ids of ANALYST, SENIOR_ANALYST and RISK_OFFICER sessions,
 * scores their expiry in epoch milliseconds. ADR 0115's real-time channel writes and refreshes it
 * ({@code SessionGuard}), against {@link #key(UUID)}; with no session open, the set does not exist
 * and the count is 0 whenever Redis answers.
 *
 * <p>Reading removes the expired members ({@code ZREMRANGEBYSCORE}), without which the set would
 * grow, then counts the rest ({@code ZCOUNT}), in one script so the two see the same set. Redis is
 * optional: with no Redis configured, or Redis unavailable, the count is unknown and the field is
 * null, never 0, and the analyst API keeps answering (C.4).
 */
public final class Presence {

  private static final RedisScript<Long> PRUNE_AND_COUNT =
      RedisScript.of(
          """
          redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
          return redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[1], '+inf')
          """,
          Long.class);

  private final SafeRedis redis;
  private final Clock clock;

  /**
   * Creates the reader.
   *
   * @param redis Redis that never fails its caller, or null when the application has none
   * @param clock the clock
   */
  public Presence(SafeRedis redis, Clock clock) {
    this.redis = redis;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The presence set of an institution: {@code fs:presence:<institution id>}. The one definition
   * the writer and this reader share.
   *
   * @param institutionId the institution
   * @return the key
   */
  public static String key(UUID institutionId) {
    return "fs:presence:" + Objects.requireNonNull(institutionId, "institutionId");
  }

  /**
   * Staff of the institution with the console open now.
   *
   * @param institutionId the institution
   * @return the count, or null when Redis is absent or unavailable
   */
  public Integer online(UUID institutionId) {
    if (redis == null) {
      return null;
    }
    Optional<Long> count =
        redis.execute(PRUNE_AND_COUNT, List.of(key(institutionId)), Long.toString(clock.millis()));
    return count.map(Math::toIntExact).orElse(null);
  }
}
