package io.github.mariusbayizere.fraudshield.admin.support;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import io.github.mariusbayizere.fraudshield.auth.web.ProblemException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Idempotency for administrator writes that take {@code Idempotency-Key} (D-29): the first request
 * runs; a replay with the same key and the same body returns the stored response; the same key with
 * a different body, or while the first is still running, is 409. Records live 24 hours in Redis, or
 * per instance when Redis is unreachable.
 */
public final class IdempotencyStore {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String PREFIX = "fs:idem:";
  private static final String IN_FLIGHT = "IN_FLIGHT";

  private final SafeRedis redis;
  private final Clock clock;
  private final Map<String, Local> local = new ConcurrentHashMap<>();

  private record Local(String value, Instant expiresAt) {}

  /**
   * Creates the store.
   *
   * @param redis Redis
   * @param clock clock
   */
  public IdempotencyStore(SafeRedis redis, Clock clock) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Runs an operation at most once per key.
   *
   * @param scope operation and caller, for example {@code createUser:<admin id>}
   * @param key the Idempotency-Key
   * @param requestFingerprint canonical request body
   * @param operation the operation, returning the serialised response
   * @return the response, from this run or the first run
   */
  public String once(
      String scope, UUID key, String requestFingerprint, Supplier<String> operation) {
    String storageKey = PREFIX + scope + ":" + key;
    String fingerprint = Crypto.base64url(Crypto.sha256(requestFingerprint));
    Optional<String> existing = read(storageKey);
    if (existing.isPresent()) {
      return replay(existing.get(), fingerprint);
    }
    write(storageKey, fingerprint + "|" + IN_FLIGHT);
    String response = null;
    try {
      response = operation.get();
      return response;
    } finally {
      if (response == null) {
        delete(storageKey); // the operation failed: the key may be used again
      } else {
        write(storageKey, fingerprint + "|" + response);
      }
    }
  }

  private static String replay(String stored, String fingerprint) {
    int bar = stored.indexOf('|');
    if (!stored.substring(0, bar).equals(fingerprint)) {
      throw ProblemException.of(
          "conflict", 409, "Conflict", "This Idempotency-Key was used with a different request");
    }
    String response = stored.substring(bar + 1);
    if (response.equals(IN_FLIGHT)) {
      throw ProblemException.of(
          "conflict", 409, "Conflict", "A request with this Idempotency-Key is still running");
    }
    return response;
  }

  private Optional<String> read(String key) {
    Optional<String> remote = redis.get(key);
    if (remote.isPresent()) {
      return remote;
    }
    Local value = local.get(key);
    if (value == null || !value.expiresAt().isAfter(clock.instant())) {
      local.remove(key);
      return Optional.empty();
    }
    return Optional.of(value.value());
  }

  private void write(String key, String value) {
    if (!redis.set(key, value, TTL)) {
      local.put(key, new Local(value, clock.instant().plus(TTL)));
    }
  }

  private void delete(String key) {
    redis.delete(key);
    local.remove(key);
  }
}
