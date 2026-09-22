package io.github.mariusbayizere.fraudshield.decision.adapter.redis;

import io.github.mariusbayizere.fraudshield.common.transaction.Channel;
import io.github.mariusbayizere.fraudshield.decision.adapter.jdbc.JdbcAccountProfiles;
import io.github.mariusbayizere.fraudshield.decision.application.port.AccountStatePort;
import io.github.mariusbayizere.fraudshield.decision.domain.Transaction;
import io.github.mariusbayizere.fraudshield.decision.domain.history.Arrival;
import io.github.mariusbayizere.fraudshield.decision.domain.history.HistoryCalculator;
import io.github.mariusbayizere.fraudshield.decision.domain.history.HistoryInputs;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The online feature store in Redis (FR-02-09, C.2 step 3): every read for a transaction is sent at
 * once and awaited together, one network round trip. The durable first-seen and opening date
 * (PB-37) and the freeze flag are restored from PostgreSQL when Redis does not hold them, so a
 * flush neither collapses the velocity ratio nor unfreezes an account.
 */
public final class RedisAccountState implements AccountStatePort {

  private static final Duration SENDERS = Duration.ofHours(24);
  private static final Duration DEVICE = Duration.ofDays(7);
  private static final Duration AGENT = Duration.ofHours(1);
  private static final String SEPARATOR = "|";
  private static final ZAddArgs LATEST = ZAddArgs.Builder.gt();

  private static final String SET_LAST =
      """
      local current = tonumber(redis.call('HGET', KEYS[1], 'at') or '-1')
      if current < tonumber(ARGV[1]) then
        redis.call('HSET', KEYS[1], 'at', ARGV[1], 'arrival', ARGV[2])
      end
      return 1
      """;

  private final RedisAsyncCommands<String, String> redis;
  private final JdbcAccountProfiles profiles;
  private final Duration timeout;
  private final Duration durableBudget;
  private final java.util.concurrent.ExecutorService lookups =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates the adapter with a 50 ms budget for the durable profile lookup.
   *
   * @param connection shared Lettuce connection
   * @param profiles durable profiles, read on a cache miss
   * @param timeout how long one read or write may take before the caller falls back
   */
  public RedisAccountState(
      StatefulRedisConnection<String, String> connection,
      JdbcAccountProfiles profiles,
      Duration timeout) {
    this(connection, profiles, timeout, Duration.ofMillis(50));
  }

  /**
   * Creates the adapter.
   *
   * @param connection shared Lettuce connection
   * @param profiles durable profiles, read on a cache miss
   * @param timeout how long one read or write may take before the caller falls back
   * @param durableBudget how long the hot path waits for PostgreSQL on a profile cache miss
   */
  public RedisAccountState(
      StatefulRedisConnection<String, String> connection,
      JdbcAccountProfiles profiles,
      Duration timeout,
      Duration durableBudget) {
    this.redis = connection.async();
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
    this.durableBudget = Objects.requireNonNull(durableBudget, "durableBudget");
  }

  @Override
  public Snapshot read(Transaction t) {
    UUID inst = t.institutionId();
    long now = t.transactionTimestamp().toEpochMilli();
    var arrivals =
        redis.zrangebyscore(
            RedisKeys.account(inst, t.accountToken(), "tx"),
            Range.create(now - HistoryCalculator.HORIZON.toMillis() + 1, now - 1));
    var last = redis.hget(RedisKeys.account(inst, t.accountToken(), "last"), "arrival");
    var profile = redis.hgetall(RedisKeys.account(inst, t.accountToken(), "profile"));
    var frozen = redis.exists(RedisKeys.account(inst, t.accountToken(), "frozen"));
    String sendersKey = RedisKeys.counterparty(inst, t.counterpartyToken());
    Range<Long> day = Range.create(now - SENDERS.toMillis() + 1, now - 1);
    var senders = redis.zcount(sendersKey, day);
    var ownSend = redis.zscore(sendersKey, t.accountToken());
    String deviceKey =
        t.deviceToken() == null ? null : RedisKeys.device(inst, t.deviceToken(), "accounts");
    Range<Long> week = Range.create(now - DEVICE.toMillis() + 1, now - 1);
    CompletionStage<Long> deviceAccounts =
        deviceKey == null ? CompletableFuture.completedFuture(0L) : redis.zcount(deviceKey, week);
    CompletionStage<Double> ownDevice =
        deviceKey == null
            ? CompletableFuture.completedFuture(null)
            : redis.zscore(deviceKey, t.accountToken());
    CompletionStage<String> deviceFirst =
        t.deviceToken() == null
            ? CompletableFuture.completedFuture(null)
            : redis.get(RedisKeys.device(inst, t.deviceToken(), "first"));
    CompletionStage<List<ScoredValue<String>>> agent =
        t.channel() == Channel.AGENT_BANKING
            ? redis.zrangebyscoreWithScores(
                RedisKeys.agent(inst, t.agentToken()),
                Range.create(now - AGENT.toMillis() + 1, now - 1))
            : CompletableFuture.completedFuture(List.of());

    Map<String, String> profileFields = await(profile);
    boolean isFrozen = await(frozen) > 0;
    Instant firstSeen = instant(profileFields.get("first_seen"));
    Instant opened = instant(profileFields.get("opened"));
    boolean firstTransaction = false;
    if (firstSeen == null) {
      Optional<Optional<JdbcAccountProfiles.Profile>> lookup = durable(t);
      Optional<JdbcAccountProfiles.Profile> stored = lookup.orElse(Optional.empty());
      if (lookup.isEmpty()) {
        // PostgreSQL did not answer within the budget: first-seen stays unknown and the ratio
        // fails closed (PB-37). The account is not called new, because that is not known either.
        firstTransaction = false;
      } else if (stored.isPresent()) {
        firstSeen = stored.get().firstSeenAt();
        opened = stored.get().openedAt();
        isFrozen = isFrozen || stored.get().frozen();
        restore(t, stored.get());
      } else {
        firstTransaction = true;
      }
    }
    HistoryInputs inputs =
        new HistoryInputs(
            await(arrivals).stream().map(RedisAccountState::decode).toList(),
            Optional.ofNullable(await(last)).map(RedisAccountState::decode).orElse(null),
            firstSeen,
            opened,
            (int) (await(senders) - (within(await(ownSend), day) ? 1 : 0)),
            Math.toIntExact(await(deviceAccounts)),
            (int) (await(deviceAccounts) - (within(await(ownDevice), week) ? 1 : 0)),
            instant(await(deviceFirst)),
            pairs(await(agent)));
    return new Snapshot(HistoryCalculator.compute(t, inputs), isFrozen, firstTransaction);
  }

  @Override
  public void record(Transaction t) {
    UUID inst = t.institutionId();
    long at = t.transactionTimestamp().toEpochMilli();
    String member = encode(Arrival.of(t));
    String accountKey = RedisKeys.account(inst, t.accountToken(), "tx");
    long horizon = HistoryCalculator.HORIZON.toMillis();
    List<CompletionStage<?>> writes = new ArrayList<>();
    writes.add(redis.zadd(accountKey, at, member));
    writes.add(
        redis.zremrangebyscore(
            accountKey, Range.create(Double.NEGATIVE_INFINITY, (double) (at - horizon))));
    writes.add(redis.pexpire(accountKey, horizon + Duration.ofDays(1).toMillis()));
    writes.add(
        redis.eval(
            SET_LAST,
            ScriptOutputType.INTEGER,
            new String[] {RedisKeys.account(inst, t.accountToken(), "last")},
            Long.toString(at),
            member));
    writes.add(
        redis.hsetnx(
            RedisKeys.account(inst, t.accountToken(), "profile"), "first_seen", Long.toString(at)));
    String senders = RedisKeys.counterparty(inst, t.counterpartyToken());
    // One member per account holding its latest time, so the store counts distinct accounts.
    writes.add(redis.zadd(senders, LATEST, at, t.accountToken()));
    writes.add(
        redis.zremrangebyscore(
            senders, Range.create(Double.NEGATIVE_INFINITY, (double) (at - SENDERS.toMillis()))));
    writes.add(redis.pexpire(senders, SENDERS.toMillis() * 2));
    if (t.deviceToken() != null) {
      String accounts = RedisKeys.device(inst, t.deviceToken(), "accounts");
      writes.add(redis.zadd(accounts, LATEST, at, t.accountToken()));
      writes.add(
          redis.zremrangebyscore(
              accounts, Range.create(Double.NEGATIVE_INFINITY, (double) (at - DEVICE.toMillis()))));
      writes.add(redis.pexpire(accounts, DEVICE.toMillis() * 2));
      writes.add(redis.setnx(RedisKeys.device(inst, t.deviceToken(), "first"), Long.toString(at)));
    }
    if (t.channel() == Channel.AGENT_BANKING) {
      String agent = RedisKeys.agent(inst, t.agentToken());
      writes.add(redis.zadd(agent, at, t.accountToken() + SEPARATOR + t.transactionId()));
      writes.add(
          redis.zremrangebyscore(
              agent, Range.create(Double.NEGATIVE_INFINITY, (double) (at - AGENT.toMillis()))));
      writes.add(redis.pexpire(agent, AGENT.toMillis() * 2));
    }
    for (CompletionStage<?> write : writes) {
      await(write);
    }
  }

  /**
   * The durable profile, waited for at most the budget so a slow or unreachable PostgreSQL never
   * stalls a decision.
   *
   * @return empty when PostgreSQL did not answer in time; otherwise the lookup's result
   */
  private Optional<Optional<JdbcAccountProfiles.Profile>> durable(Transaction t) {
    CompletableFuture<Optional<JdbcAccountProfiles.Profile>> lookup =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return profiles.find(t.institutionId(), t.accountToken());
              } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
              }
            },
            lookups);
    try {
      return Optional.of(
          lookup.get(durableBudget.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException unavailable) {
      // Without the durable store first-seen stays unknown and the ratio fails closed; the freeze
      // flag in Redis is still read. Never guess.
      return Optional.empty();
    }
  }

  /** Writes the durable values back without waiting: the next read finds them in Redis. */
  private void restore(Transaction t, JdbcAccountProfiles.Profile profile) {
    String key = RedisKeys.account(t.institutionId(), t.accountToken(), "profile");
    redis.hsetnx(key, "first_seen", Long.toString(profile.firstSeenAt().toEpochMilli()));
    if (profile.openedAt() != null) {
      redis.hset(key, "opened", Long.toString(profile.openedAt().toEpochMilli()));
    }
    if (profile.frozen()) {
      redis.set(RedisKeys.account(t.institutionId(), t.accountToken(), "frozen"), "1");
    }
  }

  private <T> T await(CompletionStage<T> stage) {
    return RedisSupport.await(stage, timeout);
  }

  static String encode(Arrival a) {
    return String.join(
        SEPARATOR,
        a.transactionId().toString(),
        Long.toString(a.at().toEpochMilli()),
        a.amountRwf().toPlainString(),
        a.counterparty(),
        Double.toString(a.latitude()),
        Double.toString(a.longitude()),
        Objects.toString(a.counterpartyCountry(), ""),
        Objects.toString(a.device(), ""));
  }

  static Arrival decode(String member) {
    String[] f = member.split("\\|", -1);
    return new Arrival(
        UUID.fromString(f[0]),
        Instant.ofEpochMilli(Long.parseLong(f[1])),
        new BigDecimal(f[2]),
        f[3],
        Double.parseDouble(f[4]),
        Double.parseDouble(f[5]),
        f[6].isEmpty() ? null : f[6],
        f[7].isEmpty() ? null : f[7]);
  }

  private static List<Map.Entry<String, Instant>> pairs(List<ScoredValue<String>> members) {
    return members.stream()
        .map(
            m ->
                Map.entry(
                    m.getValue().substring(0, m.getValue().indexOf(SEPARATOR)),
                    Instant.ofEpochMilli((long) m.getScore())))
        .toList();
  }

  private static boolean within(Double score, Range<Long> range) {
    return score != null
        && score >= range.getLower().getValue()
        && score <= range.getUpper().getValue();
  }

  private static Instant instant(String epochMillis) {
    return epochMillis == null ? null : Instant.ofEpochMilli(Long.parseLong(epochMillis));
  }
}
