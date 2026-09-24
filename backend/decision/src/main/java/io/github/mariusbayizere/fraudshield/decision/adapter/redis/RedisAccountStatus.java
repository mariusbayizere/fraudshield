package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcAccountStatus;
import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatusPort;
import io.lettuce.core.KeyValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The freeze flag in Redis, restored from PostgreSQL after a flush (FR-03-06, PB-37).
 *
 * <p>A freeze sets {@code frozen} ({@link RedisFreezes}). Its absence proves nothing after a flush,
 * so a second key, {@code known}, records that Redis's answer for the account is authoritative.
 * Both are read in one round trip. When neither exists, PostgreSQL is asked within a small budget
 * and the answer written back; if PostgreSQL does not answer in time the account is treated as not
 * frozen for this request and asked about again on the next one, so a stalled database never stalls
 * a decision.
 */
public final class RedisAccountStatus implements AccountStatusPort {

  /** How long Redis's answer for an account stays authoritative without a new freeze. */
  static final Duration KNOWN_FOR = Duration.ofDays(30);

  private final RedisAsyncCommands<String, String> redis;
  private final JdbcAccountStatus database;
  private final Duration timeout;
  private final Duration durableBudget;
  private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates the adapter.
   *
   * @param connection shared Lettuce connection
   * @param database the durable flag, read when Redis holds no answer
   * @param timeout how long one Redis call may take before the caller falls back
   * @param durableBudget how long the hot path waits for PostgreSQL when Redis holds no answer
   */
  public RedisAccountStatus(
      StatefulRedisConnection<String, String> connection,
      JdbcAccountStatus database,
      Duration timeout,
      Duration durableBudget) {
    this.redis = connection.async();
    this.database = Objects.requireNonNull(database, "database");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.durableBudget = Objects.requireNonNull(durableBudget, "durableBudget");
  }

  @Override
  public boolean frozen(UUID institutionId, String accountToken) {
    final String frozenKey = RedisKeys.account(institutionId, accountToken, "frozen");
    final String knownKey = RedisKeys.account(institutionId, accountToken, "known");
    List<KeyValue<String, String>> values =
        RedisSupport.await(redis.mget(frozenKey, knownKey), timeout);
    if (values.get(0).hasValue()) {
      return true;
    }
    if (values.get(1).hasValue()) {
      return false;
    }
    Optional<Boolean> stored = durable(institutionId, accountToken);
    if (stored.isEmpty()) {
      return false;
    }
    // Written back without waiting: the next request for the account finds the answer in Redis.
    if (stored.get()) {
      redis.set(frozenKey, "restored");
    }
    redis.psetex(knownKey, KNOWN_FOR.toMillis(), "1");
    return stored.get();
  }

  private Optional<Boolean> durable(UUID institutionId, String accountToken) {
    CompletableFuture<Boolean> lookup =
        CompletableFuture.supplyAsync(() -> database.frozen(institutionId, accountToken), lookups);
    try {
      return Optional.of(lookup.get(durableBudget.toNanos(), TimeUnit.NANOSECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (ExecutionException | TimeoutException unavailable) {
      return Optional.empty();
    }
  }
}
