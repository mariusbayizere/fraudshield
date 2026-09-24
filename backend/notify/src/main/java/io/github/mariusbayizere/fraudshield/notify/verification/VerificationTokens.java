package io.github.mariusbayizere.fraudshield.notify.verification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Single-use verification tokens (D-25 point 4): 128 random bits, sent only in the SMS link and
 * stored only as their SHA-256 hash.
 */
public final class VerificationTokens {

  /** Random bytes per token (128 bits). */
  public static final int BYTES = 16;

  private static final SecureRandom RANDOM = new SecureRandom();

  /**
   * A new token and its hash.
   *
   * @param token the URL-safe token (22 characters), never stored
   * @param hash its SHA-256, the only form stored
   */
  public record Minted(String token, byte[] hash) {
    /** Copies the hash. */
    public Minted {
      hash = hash.clone();
    }

    @Override
    public byte[] hash() {
      return hash.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Minted that && token.equals(that.token);
    }

    @Override
    public int hashCode() {
      return token.hashCode();
    }

    @Override
    public String toString() {
      return "Minted[token=<redacted>]";
    }
  }

  private VerificationTokens() {}

  /**
   * Mints a token.
   *
   * @return the token and its hash
   */
  public static Minted mint() {
    byte[] bytes = new byte[BYTES];
    RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new Minted(token, hash(token));
  }

  /**
   * The stored form of a token.
   *
   * @param token the token from a link
   * @return its SHA-256
   */
  public static byte[] hash(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the platform", e);
    }
  }
}
