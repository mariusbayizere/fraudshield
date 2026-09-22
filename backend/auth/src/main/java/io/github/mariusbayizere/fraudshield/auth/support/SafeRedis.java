package io.github.mariusbayizere.fraudshield.auth.support;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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
      return Optional.ofNullable(redis.opsForValue().get(key));
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
      return true;
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return false;
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
      return Optional.ofNullable(redis.execute(script, keys, args));
    } catch (DataAccessException | IllegalStateException e) {
      failed(e);
      return Optional.empty();
    }
  }

  private void failed(RuntimeException e) {
    long now = System.nanoTime();
    long last = lastLogged.get();
    if (now - last >= LOG_INTERVAL_NANOS && lastLogged.compareAndSet(last, now)) {
      LOG.warn("Redis is unavailable, using the fallback path: {}", e.getClass().getSimpleName());
    }
  }
}
