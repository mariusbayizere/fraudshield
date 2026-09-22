package io.github.mariusbayizere.fraudshield.auth.crypto;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless single-use tokens for account unlock and password reset (ADR 0027).
 *
 * <p>A token is {@code base64url(version | purpose | user ID | expiry | binding | HMAC)}, 74 bytes
 * (99 characters, within the contract's 22-128). The binding is a digest of the account state the
 * token may act on (for unlock, the lock's end; for reset, the token version and password hash).
 * Using the token changes that state, so the same token never verifies twice, without a token
 * table. The HMAC key is separate from every other key.
 */
public final class SignedToken {

  private static final byte VERSION = 1;
  private static final int BINDING_BYTES = 16;
  private static final int MAC_BYTES = 32;
  private static final int BODY_BYTES = 1 + 1 + 16 + 8 + BINDING_BYTES;

  /** What a token may be used for. */
  public enum Purpose {
    /** Unlock a locked account (FR-07-06). */
    ACCOUNT_UNLOCK,
    /** Set a new password after a verified reset code (FR-07-08). */
    PASSWORD_RESET
  }

  /**
   * A token whose MAC, purpose and expiry verified; the caller still compares the binding.
   *
   * @param userId account the token acts on
   * @param expiresAt expiry
   * @param binding digest of the account state at issue
   */
  public record Claims(UUID userId, Instant expiresAt, byte[] binding) {

    /** Copies the binding. */
    public Claims {
      binding = binding.clone();
    }

    @Override
    public byte[] binding() {
      return binding.clone();
    }

    /**
     * Whether the token was issued for the given current state.
     *
     * @param state the account state now, as passed to {@link SignedToken#issue}
     * @return whether the state is unchanged
     */
    public boolean boundTo(String state) {
      return Crypto.constantTimeEquals(binding, bindingOf(state));
    }
  }

  private final byte[] key;

  /**
   * Creates the codec.
   *
   * @param key HMAC key of at least 32 bytes, used for nothing else
   */
  public SignedToken(byte[] key) {
    if (key.length < Crypto.MIN_KEY_BYTES) {
      throw new IllegalArgumentException("the token key must be at least 32 bytes");
    }
    this.key = key.clone();
  }

  /**
   * Issues a token.
   *
   * @param purpose purpose
   * @param userId account
   * @param expiresAt expiry
   * @param state account state the token may act on
   * @return the token
   */
  public String issue(Purpose purpose, UUID userId, Instant expiresAt, String state) {
    ByteBuffer body =
        ByteBuffer.allocate(BODY_BYTES)
            .put(VERSION)
            .put((byte) purpose.ordinal())
            .putLong(userId.getMostSignificantBits())
            .putLong(userId.getLeastSignificantBits())
            .putLong(expiresAt.getEpochSecond())
            .put(bindingOf(state));
    byte[] bodyBytes = body.array();
    byte[] mac = Crypto.hmacSha256(key, bodyBytes);
    return Crypto.base64url(
        ByteBuffer.allocate(BODY_BYTES + MAC_BYTES).put(bodyBytes).put(mac).array());
  }

  /**
   * Verifies a token's MAC, purpose and expiry.
   *
   * @param token the token
   * @param purpose the purpose it must have
   * @param now the current time
   * @return its claims, or empty if it is malformed, forged, for another purpose or expired
   */
  public Optional<Claims> verify(String token, Purpose purpose, Instant now) {
    Objects.requireNonNull(purpose, "purpose");
    byte[] bytes;
    try {
      bytes = Crypto.fromBase64url(token);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (bytes.length != BODY_BYTES + MAC_BYTES) {
      return Optional.empty();
    }
    byte[] body = Arrays.copyOfRange(bytes, 0, BODY_BYTES);
    byte[] mac = Arrays.copyOfRange(bytes, BODY_BYTES, bytes.length);
    if (!Crypto.constantTimeEquals(mac, Crypto.hmacSha256(key, body))) {
      return Optional.empty();
    }
    ByteBuffer buffer = ByteBuffer.wrap(body);
    if (buffer.get() != VERSION || buffer.get() != (byte) purpose.ordinal()) {
      return Optional.empty();
    }
    UUID userId = new UUID(buffer.getLong(), buffer.getLong());
    Instant expiresAt = Instant.ofEpochSecond(buffer.getLong());
    if (!now.isBefore(expiresAt)) {
      return Optional.empty();
    }
    byte[] binding = new byte[BINDING_BYTES];
    buffer.get(binding);
    return Optional.of(new Claims(userId, expiresAt, binding));
  }

  private static byte[] bindingOf(String state) {
    return Arrays.copyOf(Crypto.sha256(state), BINDING_BYTES);
  }
}
