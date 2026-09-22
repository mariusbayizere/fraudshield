package io.github.mariusbayizere.fraudshield.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Small, audited cryptographic helpers shared by the auth module. */
public final class Crypto {

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder BASE64URL_DECODER = Base64.getUrlDecoder();

  /** Minimum length of an HMAC key: the HMAC-SHA256 block of security (RFC 2104). */
  public static final int MIN_KEY_BYTES = 32;

  private Crypto() {}

  /**
   * Cryptographically random bytes.
   *
   * @param length number of bytes
   * @return the bytes
   */
  public static byte[] randomBytes(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    return bytes;
  }

  /**
   * A uniformly random integer in [0, bound).
   *
   * @param bound exclusive upper bound
   * @return the integer
   */
  public static int randomInt(int bound) {
    return RANDOM.nextInt(bound);
  }

  /**
   * A random token of {@code bytes} bytes, base64url without padding.
   *
   * @param bytes entropy in bytes (at least 16 for 128 bits)
   * @return the token
   */
  public static String randomToken(int bytes) {
    return BASE64URL.encodeToString(randomBytes(bytes));
  }

  /**
   * Base64url without padding.
   *
   * @param bytes input
   * @return encoded text
   */
  public static String base64url(byte[] bytes) {
    return BASE64URL.encodeToString(bytes);
  }

  /**
   * Decodes base64url.
   *
   * @param text encoded text
   * @return the bytes
   * @throws IllegalArgumentException if the text is not base64url
   */
  public static byte[] fromBase64url(String text) {
    return BASE64URL_DECODER.decode(text);
  }

  /**
   * SHA-256 of UTF-8 text.
   *
   * @param text input
   * @return 32-byte digest
   */
  public static byte[] sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * SHA-256.
   *
   * @param bytes input
   * @return 32-byte digest
   */
  public static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * HMAC-SHA256 over the concatenation of the parts.
   *
   * @param key key of at least 32 bytes
   * @param parts message parts
   * @return 32-byte MAC
   */
  public static byte[] hmacSha256(byte[] key, byte[]... parts) {
    if (key.length < MIN_KEY_BYTES) {
      throw new IllegalArgumentException("HMAC keys must be at least 32 bytes");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      for (byte[] part : parts) {
        mac.update(part);
      }
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }

  /**
   * Constant-time comparison of secrets or MACs.
   *
   * @param a first value
   * @param b second value
   * @return whether they are equal
   */
  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    return MessageDigest.isEqual(a, b);
  }
}
