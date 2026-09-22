package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import io.github.mariusbayizere.fraudshield.decision.application.port.CircuitBreakerPort;
import io.github.mariusbayizere.fraudshield.decision.domain.CircuitBreakerState;
import io.github.mariusbayizere.fraudshield.decision.domain.MccCircuitBreaker;
import io.lettuce.core.Range;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-MCC rolling counts and breaker state shared by every instance (FR-03-07, D-18).
 *
 * <p>Counts are one-minute buckets ({@code HINCRBY}), so the rolling window's edge has one-minute
 * granularity: the window over [now − 15 min, now] is the current partial minute plus the 14 full
 * minutes before it (ADR 0061). State changes are a Lua compare-and-set on a version counter, so
 * when several instances evaluate at once each change is recorded once. {@link #isOpen} answers
 * from a local copy refreshed on every evaluation, so the hot path pays nothing.
 */
public final class RedisCircuitBreakers implements CircuitBreakerPort {

  private static final Duration BUCKET_TTL = Duration.ofHours(25);

  private static final String SET_STATE =
      """
      local version = tonumber(redis.call('HGET', KEYS[1], 'version') or '0')
      if version ~= tonumber(ARGV[1]) then return 0 end
      redis.call('HSET', KEYS[1], 'version', version + 1, 'open', ARGV[2], 'opened_at', ARGV[3],
        'last_breach_at', ARGV[4])
      return 1
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final Duration timeout;
  private final Map<Key, Boolean> open = new ConcurrentHashMap<>();
  private final Map<Key, Long> versions = new ConcurrentHashMap<>();

  /**
   * Creates the adapter.
   *
   * @param connection shared Lettuce connection
   * @param timeout bound on each call
   */
  public RedisCircuitBreakers(
      StatefulRedisConnection<String, String> connection, Duration timeout) {
    this.redis = connection.async();
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public boolean isOpen(UUID institutionId, String merchantCategoryCode) {
    return open.getOrDefault(new Key(institutionId, merchantCategoryCode), false);
  }

  @Override
  public void count(UUID institutionId, String mcc, Instant at, boolean fraud) {
    String bucket = RedisKeys.mccBucket(institutionId, mcc, minute(at));
    List<CompletionStage<?>> writes = new ArrayList<>();
    writes.add(redis.hincrby(bucket, "n", 1));
    if (fraud) {
      writes.add(redis.hincrby(bucket, "f", 1));
    }
    writes.add(redis.pexpire(bucket, BUCKET_TTL.toMillis()));
    writes.add(redis.zadd(RedisKeys.MCC_ACTIVE, at.toEpochMilli(), institutionId + "|" + mcc));
    for (CompletionStage<?> write : writes) {
      RedisSupport.await(write, timeout);
    }
  }

  @Override
  public void countConfirmedFraud(UUID institutionId, String mcc, Instant at) {
    // Buckets by the transaction's minute, not the confirmation's, so the fraud lands in the
    // window it belongs to; counts() clamps f to n, so a confirmation whose bucket has expired
    // raises nothing (ADR 0061 point 6).
    String bucket = RedisKeys.mccBucket(institutionId, mcc, minute(at));
    RedisSupport.await(redis.hincrby(bucket, "f", 1), timeout);
    RedisSupport.await(redis.pexpire(bucket, BUCKET_TTL.toMillis()), timeout);
  }

  @Override
  public Collection<Key> active(Instant now) {
    Set<Key> keys = ConcurrentHashMap.newKeySet();
    RedisSupport.await(
        redis.zremrangebyscore(
            RedisKeys.MCC_ACTIVE,
            Range.create(Double.NEGATIVE_INFINITY, (double) now.minus(BUCKET_TTL).toEpochMilli())),
        timeout);
    for (String member : RedisSupport.await(redis.zrange(RedisKeys.MCC_ACTIVE, 0, -1), timeout)) {
      int separator = member.indexOf('|');
      keys.add(
          new Key(
              UUID.fromString(member.substring(0, separator)), member.substring(separator + 1)));
    }
    keys.addAll(open.keySet());
    return keys;
  }

  @Override
  public MccCircuitBreaker.WindowCounts counts(Key key, int window, Instant now) {
    long last = minute(now);
    List<CompletionStage<List<io.lettuce.core.KeyValue<String, String>>>> reads = new ArrayList<>();
    for (long m = last - window + 1; m <= last; m++) {
      reads.add(
          redis.hmget(
              RedisKeys.mccBucket(key.institutionId(), key.merchantCategoryCode(), m), "n", "f"));
    }
    long transactions = 0;
    long fraud = 0;
    for (CompletionStage<List<io.lettuce.core.KeyValue<String, String>>> read : reads) {
      List<io.lettuce.core.KeyValue<String, String>> values = RedisSupport.await(read, timeout);
      transactions +=
          values.get(0).getValueOrElse("0").isEmpty()
              ? 0
              : Long.parseLong(values.get(0).getValueOrElse("0"));
      fraud += Long.parseLong(values.get(1).getValueOrElse("0"));
    }
    return new MccCircuitBreaker.WindowCounts(transactions, Math.min(fraud, transactions));
  }

  @Override
  public CircuitBreakerState state(Key key) {
    Map<String, String> fields =
        RedisSupport.await(
            redis.hgetall(RedisKeys.mccState(key.institutionId(), key.merchantCategoryCode())),
            timeout);
    versions.put(key, Long.parseLong(fields.getOrDefault("version", "0")));
    CircuitBreakerState state =
        fields.isEmpty() || fields.get("opened_at").isEmpty()
            ? CircuitBreakerState.CLOSED
            : new CircuitBreakerState(
                "1".equals(fields.get("open")),
                instant(fields.get("opened_at")),
                instant(fields.get("last_breach_at")));
    remember(key, state);
    return state;
  }

  @Override
  public boolean compareAndSet(Key key, CircuitBreakerState expected, CircuitBreakerState next) {
    Long version = versions.getOrDefault(key, 0L);
    Long stored =
        RedisSupport.await(
            redis.eval(
                SET_STATE,
                ScriptOutputType.INTEGER,
                new String[] {RedisKeys.mccState(key.institutionId(), key.merchantCategoryCode())},
                Long.toString(version),
                next.open() ? "1" : "0",
                millis(next.openedAt()),
                millis(next.lastBreachAt())),
            timeout);
    if (stored == 1L) {
      versions.put(key, version + 1);
      remember(key, next);
      return true;
    }
    return false;
  }

  private void remember(Key key, CircuitBreakerState state) {
    if (state.open()) {
      open.put(key, true);
    } else {
      open.remove(key);
    }
  }

  private static long minute(Instant at) {
    return at.getEpochSecond() / 60;
  }

  private static String millis(Instant at) {
    return at == null ? "" : Long.toString(at.toEpochMilli());
  }

  private static Instant instant(String millis) {
    return millis == null || millis.isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(millis));
  }
}
