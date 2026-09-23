package io.github.mariusbayizere.fraudshield.ingest.idempotency;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * atomically. A claim is an in-flight lease of a few seconds owned by a random token, so a process
 * that dies mid-decision cannot block a retry for 24 hours; a completed record lives 24 hours
 * (FR-01-03: TTL = 24 h).
 *
 * <p>Only the owner of a claim can release it or mark it uncertain, and completion always stores
 * the fingerprint, so a decision that finishes after its lease expired is still replayed to
 * identical requests rather than reported as a conflict. If a duplicate re-claimed and completed
 * first, its response stands and is returned to both (ADR 0067).
 *
 * <p>Each institution has a verification key holding the time until which new claims must be
 * checked against PostgreSQL. It is created on first use with a 24-hour window, so a Redis that
 * lost its data (a flush, a restart without persistence) never trusts its own emptiness for longer
 * than the records it may have lost would have lived; the API extends it after deciding without
 * Redis.
 */
public final class RedisIdempotency implements IdempotencyStore {

  /** How long a completed record lives (FR-01-03). */
  public static final Duration TTL = Duration.ofHours(24);

  private static final String CLAIM =
      """
      local now = tonumber(ARGV[4])
      local verify = 0
      local verifyUntil = redis.call('GET', KEYS[2])
      if not verifyUntil then
        redis.call('SET', KEYS[2], tostring(now + tonumber(ARGV[5])))
        verify = 1
      elseif tonumber(verifyUntil) > now then
        verify = 1
      end
      local state = redis.call('HGET', KEYS[1], 'state')
      if state and redis.call('HGET', KEYS[1], 'fp') ~= ARGV[1] then return {'CONFLICT'} end
      if state == 'DONE' then return {'REPLAY', redis.call('HGET', KEYS[1], 'response')} end
      if state == 'INFLIGHT' then return {'INFLIGHT'} end
      if state == 'UNCERTAIN' then verify = 1 end
      redis.call('HSET', KEYS[1], 'fp', ARGV[1], 'state', 'INFLIGHT', 'owner', ARGV[3])
      redis.call('PEXPIRE', KEYS[1], ARGV[2])
      return {'CLAIMED', tostring(verify)}
      """;

  private static final String COMPLETE =
      """
      if redis.call('HGET', KEYS[1], 'state') == 'DONE' then
        if redis.call('HGET', KEYS[1], 'fp') == ARGV[1] then
          return redis.call('HGET', KEYS[1], 'response')
        end
        return ARGV[2]
      end
      redis.call('DEL', KEYS[1])
      redis.call('HSET', KEYS[1], 'fp', ARGV[1], 'state', 'DONE', 'response', ARGV[2])
      redis.call('PEXPIRE', KEYS[1], ARGV[3])
      return ARGV[2]
      """;

  private static final String RELEASE =
      """
      if redis.call('HGET', KEYS[1], 'state') == 'INFLIGHT'
          and redis.call('HGET', KEYS[1], 'owner') == ARGV[1] then
        redis.call('DEL', KEYS[1])
      end
      return 1
      """;

  private static final String UNCERTAIN =
      """
      if redis.call('HGET', KEYS[1], 'state') == 'INFLIGHT'
          and redis.call('HGET', KEYS[1], 'owner') == ARGV[1] then
        redis.call('HSET', KEYS[1], 'state', 'UNCERTAIN')
        redis.call('HDEL', KEYS[1], 'owner')
        redis.call('PEXPIRE', KEYS[1], ARGV[2])
      end
      return 1
      """;

  private static final String REQUIRE_VERIFICATION =
      """
      local verifyUntil = tonumber(redis.call('GET', KEYS[1]) or '0')
      if tonumber(ARGV[1]) > verifyUntil then redis.call('SET', KEYS[1], ARGV[1]) end
      return 1
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final Duration lease;
  private final Duration timeout;
  private final Clock clock;

  /**
   * Creates the store.
   *
   * @param connection shared Lettuce connection
   * @param lease how long an unfinished claim blocks duplicates
   * @param timeout bound on each call
   * @param clock clock for the verification window
   */
  public RedisIdempotency(
      StatefulRedisConnection<String, String> connection,
      Duration lease,
      Duration timeout,
      Clock clock) {
    this.redis = connection.async();
    this.lease = Objects.requireNonNull(lease, "lease");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private static String key(UUID institution, UUID transaction) {
    return "fs:{" + institution + "}:idem:" + transaction;
  }

  private static String verificationKey(UUID institution) {
    return "fs:{" + institution + "}:idem-verify-until";
  }

  @Override
  public Claim claim(UUID institutionId, UUID transactionId, byte[] fingerprint) {
    final String token = UUID.randomUUID().toString();
    List<Object> result =
        await(
            redis.eval(
                CLAIM,
                ScriptOutputType.MULTI,
                new String[] {key(institutionId, transactionId), verificationKey(institutionId)},
                HexFormat.of().formatHex(fingerprint),
                Long.toString(lease.toMillis()),
                token,
                Long.toString(clock.millis()),
                Long.toString(TTL.toMillis())));
    return switch ((String) result.get(0)) {
      case "CLAIMED" -> new Claimed(token, "1".equals(result.get(1)));
      case "CONFLICT" -> new Conflict();
      case "REPLAY" -> new Replay(Base64.getDecoder().decode((String) result.get(1)));
      default -> new InFlight();
    };
  }

  @Override
  public byte[] complete(
      UUID institutionId, UUID transactionId, Claimed claim, byte[] fingerprint, byte[] response) {
    String standing =
        await(
            redis.eval(
                COMPLETE,
                ScriptOutputType.VALUE,
                new String[] {key(institutionId, transactionId)},
                HexFormat.of().formatHex(fingerprint),
                Base64.getEncoder().encodeToString(response),
                Long.toString(TTL.toMillis())));
    return Base64.getDecoder().decode(standing);
  }

  @Override
  public java.util.Optional<byte[]> decided(UUID institutionId, UUID transactionId) {
    java.util.Map<String, String> record = await(redis.hgetall(key(institutionId, transactionId)));
    return "DONE".equals(record.get("state"))
        ? java.util.Optional.of(Base64.getDecoder().decode(record.get("response")))
        : java.util.Optional.empty();
  }

  @Override
  public void release(UUID institutionId, UUID transactionId, Claimed claim) {
    await(
        redis.eval(
            RELEASE,
            ScriptOutputType.INTEGER,
            new String[] {key(institutionId, transactionId)},
            claim.token()));
  }

  @Override
  public void uncertain(UUID institutionId, UUID transactionId, Claimed claim) {
    await(
        redis.eval(
            UNCERTAIN,
            ScriptOutputType.INTEGER,
            new String[] {key(institutionId, transactionId)},
            claim.token(),
            Long.toString(TTL.toMillis())));
  }

  @Override
  public void requireVerification(UUID institutionId, Instant until) {
    await(
        redis.eval(
            REQUIRE_VERIFICATION,
            ScriptOutputType.INTEGER,
            new String[] {verificationKey(institutionId)},
            Long.toString(until.toEpochMilli())));
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
