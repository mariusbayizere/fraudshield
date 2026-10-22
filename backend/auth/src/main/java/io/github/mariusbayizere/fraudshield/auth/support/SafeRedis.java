package io.github.mariusbayizere.fraudshield.auth.support;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis access that never fails the caller (C.4: Redis down degrades, never blocks). Every
 * operation returns empty or does nothing when Redis is unreachable, and the caller falls back to
 * the database or to in-process state. Failures are logged at most once a minute.
 */
public final class SafeRedis {

  private static final Logger LOG = LoggerFactory.getLogger(SafeRedis.class);
  private static final long LOG_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

  private final StringRedisTemplate redis;
  private final AtomicLong lastLogged = new AtomicLong(System.nanoTime() - LOG_INTERVAL_NANOS);

  /**
   * Failed operations in a row (ADR 0115 point 3): an exception caught, never a missing key. Reset
   * by any success.
   */
  private final AtomicInteger consecutiveFailures = new AtomicInteger();

  /** Failures in a row after which Redis counts as down: 3 (ASSUMED, ADR 0115 point 3). */
  public static final int DOWN_AFTER = 3;

  /**
   * Wraps a template.
   *
   * @param redis the template, or null when no Redis is configured
   */
  public SafeRedis(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Reads a key.
   *
   * @param key key
   * @return its value, or empty if missing or Redis is unavailable
   */
  public Optional<String> get(String key) {
    if (redis == null) {
      return Optional.empty();
    }
    try {
      Optional<String> value = Optional.ofNullable(redis.opsForValue().get(key));
      succeeded();
      return value;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return Optional.empty();
    }
  }

  /**
   * Writes a key with a time to live.
   *
   * @param key key
   * @param value value
   * @param ttl time to live
   * @return whether the write reached Redis
   */
  public boolean set(String key, String value, Duration ttl) {
    if (redis == null) {
      return false;
    }
    try {
      redis.opsForValue().set(key, value, ttl);
      succeeded();
      return true;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return false;
    }
  }

  /**
   * Writes a key only if it does not exist (Redis SET NX).
   *
   * @param key key
   * @param value value
   * @param ttl time to live
   * @return whether this call created the key, or empty if Redis is unavailable
   */
  public Optional<Boolean> setIfAbsent(String key, String value, Duration ttl) {
    if (redis == null) {
      return Optional.empty();
    }
    try {
      Optional<Boolean> created =
          Optional.ofNullable(redis.opsForValue().setIfAbsent(key, value, ttl));
      succeeded();
      return created;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return Optional.empty();
    }
  }

  /**
   * Adds a member to a set and refreshes the set's time to live.
   *
   * @param key set key
   * @param member member
   * @param ttl time to live
   */
  public void addToSet(String key, String member, Duration ttl) {
    if (redis == null) {
      return;
    }
    try {
      redis.opsForSet().add(key, member);
      redis.expire(key, ttl);
      succeeded();
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
    }
  }

  /**
   * Removes a set and returns its members.
   *
   * @param key set key
   * @return the members, empty if none or Redis is unavailable
   */
  public java.util.Set<String> takeSet(String key) {
    if (redis == null) {
      return java.util.Set.of();
    }
    try {
      java.util.Set<String> members = redis.opsForSet().members(key);
      redis.delete(key);
      succeeded();
      return members == null ? java.util.Set.of() : members;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return java.util.Set.of();
    }
  }

  /**
   * Deletes a key.
   *
   * @param key key
   */
  public void delete(String key) {
    if (redis == null) {
      return;
    }
    try {
      redis.delete(key);
      succeeded();
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
    }
  }

  /**
   * Publishes a message.
   *
   * @param channel channel
   * @param message message
   */
  public void publish(String channel, String message) {
    if (redis == null) {
      return;
    }
    try {
      redis.convertAndSend(channel, message);
      succeeded();
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
    }
  }

  /**
   * Runs a Lua script.
   *
   * @param script the script
   * @param keys its keys
   * @param args its arguments
   * @param <T> result type
   * @return its result, or empty if Redis is unavailable
   */
  public <T> Optional<T> execute(RedisScript<T> script, List<String> keys, Object... args) {
    if (redis == null) {
      return Optional.empty();
    }
    try {
      Optional<T> result = Optional.ofNullable(redis.execute(script, keys, args));
      succeeded();
      return result;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return Optional.empty();
    }
  }

  /**
   * Whether Redis is reachable (ADR 0115 point 3): false when none is configured, or once {@link
   * #DOWN_AFTER} operations in a row have failed, until one succeeds. A missing key is a success.
   *
   * @return whether Redis counts as available
   */
  public boolean available() {
    return redis != null && consecutiveFailures.get() < DOWN_AFTER;
  }

  /**
   * Sends PING, so the availability signal moves even when nothing else uses Redis.
   *
   * @return whether Redis answered
   */
  public boolean ping() {
    if (redis == null) {
      return false;
    }
    try {
      redis.execute((RedisCallback<String>) connection -> connection.ping());
      succeeded();
      return true;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return false;
    }
  }

  /**
   * Adds or re-scores a member of a sorted set and refreshes the set's time to live.
   *
   * @param key sorted-set key
   * @param member member
   * @param score score
   * @param ttl the set's time to live
   */
  public void addScored(String key, String member, double score, Duration ttl) {
    if (redis == null) {
      return;
    }
    try {
      redis.opsForZSet().add(key, member, score);
      redis.expire(key, ttl);
      succeeded();
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
    }
  }

  private void succeeded() {
    consecutiveFailures.set(0);
  }

  private void failed(RuntimeException e) {
    consecutiveFailures.incrementAndGet();
    long now = System.nanoTime();
    long last = lastLogged.get();
    if (now - last >= LOG_INTERVAL_NANOS && lastLogged.compareAndSet(last, now)) {
      LOG.warn("Redis is unavailable, using the fallback path: {}", e.getClass().getSimpleName());
    }
  }
}
