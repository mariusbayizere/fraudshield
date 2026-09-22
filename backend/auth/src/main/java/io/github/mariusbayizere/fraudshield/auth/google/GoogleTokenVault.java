package io.github.mariusbayizere.fraudshield.auth.google;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import io.github.mariusbayizere.fraudshield.auth.crypto.SecretBox;
import io.github.mariusbayizere.fraudshield.auth.support.SafeRedis;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds a Google access token, encrypted, for the life of the session that it began, so sign-out
 * can revoke it (FR-07-09, ADR 0027). Stored in Redis only, never in the database, and expiring
 * with the token itself (at most an hour); without Redis the token simply expires unrevoked.
 */
public final class GoogleTokenVault {

  private static final String KEY = "fs:auth:gtok:";
  private static final Duration MAX_TTL = Duration.ofHours(1);

  private final SafeRedis redis;
  private final SecretBox box;

  /**
   * Creates the vault.
   *
   * @param redis Redis
   * @param box encryption
   */
  public GoogleTokenVault(SafeRedis redis, SecretBox box) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.box = Objects.requireNonNull(box, "box");
  }

  /**
   * Stores a token for a session.
   *
   * @param sessionId session ID
   * @param accessToken Google access token
   * @param expiresInSeconds its lifetime
   */
  public void store(UUID sessionId, String accessToken, long expiresInSeconds) {
    Duration ttl = Duration.ofSeconds(Math.max(1, expiresInSeconds));
    if (ttl.compareTo(MAX_TTL) > 0) {
      ttl = MAX_TTL;
    }
    redis.set(KEY + sessionId, Crypto.base64url(box.seal(accessToken, aad(sessionId))), ttl);
  }

  /**
   * Removes and returns a session's token.
   *
   * @param sessionId session ID
   * @return the token, or empty if none is held
   */
  public Optional<String> take(UUID sessionId) {
    Optional<String> ciphertext = redis.get(KEY + sessionId);
    if (ciphertext.isEmpty()) {
      return Optional.empty();
    }
    redis.delete(KEY + sessionId);
    try {
      return Optional.of(box.open(Crypto.fromBase64url(ciphertext.get()), aad(sessionId)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static String aad(UUID sessionId) {
    return "google-access-token:" + sessionId;
  }
}
