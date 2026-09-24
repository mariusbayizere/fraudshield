package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import io.github.mariusbayizere.fraudshield.decision.application.port.FreezePort;
import io.github.mariusbayizere.fraudshield.decision.domain.FreezePolicy;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The account-freeze counter (FR-03-06): a sorted set of HIGH decisions per account, trimmed to the
 * sliding hour, and a freeze flag set with {@code NX} so exactly one of several racing instances
 * freezes. One Lua script, so the add, trim, count and freeze are atomic.
 */
public final class RedisFreezes implements FreezePort {

  private static final String RECORD_HIGH =
      """
      redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
      redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', tonumber(ARGV[2]) - tonumber(ARGV[3]))
      redis.call('PEXPIRE', KEYS[1], ARGV[3])
      local count = redis.call('ZCOUNT', KEYS[1], tonumber(ARGV[2]) - tonumber(ARGV[3]) + 1,
        ARGV[2])
      local froze = 0
      if count >= tonumber(ARGV[4]) then
        if redis.call('SET', KEYS[2], ARGV[2], 'NX') then froze = 1 end
      end
      return {count, froze}
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final Duration timeout;

  /**
   * Creates the adapter.
   *
   * @param connection shared Lettuce connection
   * @param timeout bound on the call
   */
  public RedisFreezes(StatefulRedisConnection<String, String> connection, Duration timeout) {
    this.redis = connection.async();
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public FreezeCheck recordHigh(
      UUID institutionId, String accountToken, UUID transactionId, Instant decidedAt) {
    List<Object> result =
        RedisSupport.await(
            redis.eval(
                RECORD_HIGH,
                ScriptOutputType.MULTI,
                new String[] {
                  RedisKeys.account(institutionId, accountToken, "highs"),
                  RedisKeys.account(institutionId, accountToken, "frozen")
                },
                transactionId.toString(),
                Long.toString(decidedAt.toEpochMilli()),
                Long.toString(FreezePolicy.WINDOW.toMillis()),
                Integer.toString(FreezePolicy.HIGH_DECISIONS_TO_FREEZE)),
            timeout);
    return new FreezeCheck(((Long) result.get(0)).intValue(), (Long) result.get(1) == 1L);
  }
}
