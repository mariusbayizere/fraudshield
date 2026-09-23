package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import io.github.mariusbayizere.fraudshield.common.config.MediumTimeoutPolicy;
import io.github.mariusbayizere.fraudshield.decision.application.port.HoldSchedulePort;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MEDIUM review deadlines in a Redis sorted set scored by deadline (E.6), with an index so an
 * analyst can remove a hold by transaction. Claiming and cancelling are Lua scripts, so a hold is
 * removed by exactly one of the poller and the analyst. The poller lease is a key set with {@code
 * NX} and renewed by its holder only.
 */
public final class RedisHoldSchedule implements HoldSchedulePort {

  /** How long a leader's lease lasts without renewal; the poller renews every 100 ms. */
  public static final Duration LEASE = Duration.ofSeconds(2);

  private static final String SCHEDULE =
      """
      redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
      redis.call('HSET', KEYS[2], ARGV[3], ARGV[2])
      return 1
      """;

  private static final String CANCEL =
      """
      local member = redis.call('HGET', KEYS[2], ARGV[1])
      if not member then return 0 end
      redis.call('HDEL', KEYS[2], ARGV[1])
      return redis.call('ZREM', KEYS[1], member)
      """;

  private static final String CLAIM =
      """
      local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
      for _, member in ipairs(due) do
        redis.call('ZREM', KEYS[1], member)
        local sep = string.find(member, '|', 1, true)
        local second = string.find(member, '|', sep + 1, true)
        redis.call('HDEL', KEYS[2], string.sub(member, 1, second - 1))
      end
      return due
      """;

  private static final String LEAD =
      """
      local holder = redis.call('GET', KEYS[1])
      if holder == ARGV[1] then
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return 1
      end
      if not holder then
        redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
        return 1
      end
      return 0
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final Duration timeout;

  /**
   * Creates the adapter.
   *
   * @param connection shared Lettuce connection
   * @param timeout bound on each call
   */
  public RedisHoldSchedule(StatefulRedisConnection<String, String> connection, Duration timeout) {
    this.redis = connection.async();
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public void schedule(DueHold hold) {
    String id = hold.institutionId() + "|" + hold.transactionId();
    String member =
        id + "|" + hold.channel() + "|" + hold.policy() + "|" + hold.deadline().toEpochMilli();
    RedisSupport.await(
        redis.eval(
            SCHEDULE,
            ScriptOutputType.INTEGER,
            new String[] {RedisKeys.HOLDS, RedisKeys.HOLDS_INDEX},
            Long.toString(hold.deadline().toEpochMilli()),
            member,
            id),
        timeout);
  }

  @Override
  public boolean cancel(UUID institutionId, UUID transactionId) {
    Long removed =
        RedisSupport.await(
            redis.eval(
                CANCEL,
                ScriptOutputType.INTEGER,
                new String[] {RedisKeys.HOLDS, RedisKeys.HOLDS_INDEX},
                institutionId + "|" + transactionId),
            timeout);
    return removed == 1L;
  }

  @Override
  public List<DueHold> claimDue(Instant now, int max) {
    List<Object> members =
        RedisSupport.await(
            redis.eval(
                CLAIM,
                ScriptOutputType.MULTI,
                new String[] {RedisKeys.HOLDS, RedisKeys.HOLDS_INDEX},
                Long.toString(now.toEpochMilli()),
                Integer.toString(max)),
            timeout);
    List<DueHold> due = new ArrayList<>(members.size());
    for (Object member : members) {
      String[] f = ((String) member).split("\\|");
      due.add(
          new DueHold(
              UUID.fromString(f[0]),
              UUID.fromString(f[1]),
              f[2],
              MediumTimeoutPolicy.valueOf(f[3]),
              Instant.ofEpochMilli(Long.parseLong(f[4]))));
    }
    return due;
  }

  @Override
  public boolean acquireLeadership(String instanceId) {
    Long led =
        RedisSupport.await(
            redis.eval(
                LEAD,
                ScriptOutputType.INTEGER,
                new String[] {RedisKeys.HOLDS_LEADER},
                instanceId,
                Long.toString(LEASE.toMillis())),
            timeout);
    return led == 1L;
  }
}
