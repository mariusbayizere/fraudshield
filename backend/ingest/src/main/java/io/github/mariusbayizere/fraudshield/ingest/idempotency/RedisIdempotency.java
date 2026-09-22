package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Idempotency in Redis (C.2 step 2): one Lua script claims, replays or reports a conflict
 * atomically. A claim is an in-flight lease of a few seconds, so a process that dies mid-decision
 * cannot block a retry for 24 hours; a completed record lives 24 hours (FR-01-03: TTL = 24 h).
 */
public final class RedisIdempotency implements IdempotencyStore {

  /** How long a completed record lives (FR-01-03). */
  public static final Duration TTL = Duration.ofHours(24);

  private static final String CLAIM =
      """
      local state = redis.call('HGET', KEYS[1], 'state')
      if not state then
        redis.call('HSET', KEYS[1], 'fp', ARGV[1], 'state', 'INFLIGHT')
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
        return {'CLAIMED'}
      end
      if redis.call('HGET', KEYS[1], 'fp') ~= ARGV[1] then return {'CONFLICT'} end
      if state == 'DONE' then return {'REPLAY', redis.call('HGET', KEYS[1], 'response')} end
      return {'INFLIGHT'}
      """;

  private static final String COMPLETE =
      """
      redis.call('HSET', KEYS[1], 'state', 'DONE', 'response', ARGV[1])
      redis.call('PEXPIRE', KEYS[1], ARGV[2])
      return 1
      """;

  private static final String RELEASE =
      """
      if redis.call('HGET', KEYS[1], 'state') == 'INFLIGHT' then redis.call('DEL', KEYS[1]) end
      return 1
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final Duration lease;
  private final Duration timeout;

  /**
   * Creates the store.
   *
   * @param connection shared Lettuce connection
   * @param lease how long an unfinished claim blocks duplicates
   * @param timeout bound on each call
   */
  public RedisIdempotency(
      StatefulRedisConnection<String, String> connection, Duration lease, Duration timeout) {
    this.redis = connection.async();
    this.lease = Objects.requireNonNull(lease, "lease");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  private static String key(UUID institution, UUID transaction) {
    return "fs:{" + institution + "}:idem:" + transaction;
  }

  @Override
  public Claim claim(UUID institutionId, UUID transactionId, byte[] fingerprint) {
    List<Object> result =
        await(
            redis.eval(
                CLAIM,
                ScriptOutputType.MULTI,
                new String[] {key(institutionId, transactionId)},
                HexFormat.of().formatHex(fingerprint),
                Long.toString(lease.toMillis())));
    return switch ((String) result.get(0)) {
      case "CLAIMED" -> new Claimed();
      case "CONFLICT" -> new Conflict();
      case "REPLAY" -> new Replay(Base64.getDecoder().decode((String) result.get(1)));
      default -> new InFlight();
    };
  }

  @Override
  public void complete(UUID institutionId, UUID transactionId, byte[] response) {
    await(
        redis.eval(
            COMPLETE,
            ScriptOutputType.INTEGER,
            new String[] {key(institutionId, transactionId)},
            Base64.getEncoder().encodeToString(response),
            Long.toString(TTL.toMillis())));
  }

  @Override
  public java.util.Optional<byte[]> decided(UUID institutionId, UUID transactionId) {
    java.util.Map<String, String> record = await(redis.hgetall(key(institutionId, transactionId)));
    return "DONE".equals(record.get("state"))
        ? java.util.Optional.of(Base64.getDecoder().decode(record.get("response")))
        : java.util.Optional.empty();
  }

  @Override
  public void release(UUID institutionId, UUID transactionId) {
    await(
        redis.eval(
            RELEASE, ScriptOutputType.INTEGER, new String[] {key(institutionId, transactionId)}));
  }

  private <T> T await(java.util.concurrent.CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().get(timeout.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting for Redis", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Redis did not answer", e);
    }
  }
}
