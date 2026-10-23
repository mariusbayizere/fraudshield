package io.github.mariusbayizere.fraudshield.persistence.demo;

import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Parses FraudShield API keys and computes the stored HMAC (D-19): {@code HMAC-SHA256(pepper,
 * secret)}. Only the HMAC and the last four characters are ever stored.
 */
public final class ApiKeyHasher {

  private static final Pattern KEY =
      Pattern.compile("^fsk_(dev|test|stg|prod)_([a-z0-9]{12})_([A-Za-z0-9_-]{43})$");
  private static final int MINIMUM_PEPPER_BYTES = 32;

  private ApiKeyHasher() {}

  /**
   * A parsed API key.
   *
   * @param environment environment segment
   * @param keyId public key identifier
   * @param secret secret part (never stored)
   */
  public record ParsedKey(String environment, String keyId, String secret) {

    /**
     * The last four characters of the secret, shown in the admin list.
     *
     * @return last four characters
     */
    public String lastFour() {
      return secret.substring(secret.length() - 4);
    }

    @Override
    public String toString() {
      return "ParsedKey[environment=" + environment + ", keyId=" + keyId + ", secret=<redacted>]";
    }
  }

  /**
   * Parses a raw key.
   *
   * @param rawKey the full key
   * @return the parsed key
   * @throws IllegalArgumentException if the key does not have the FraudShield format
   */
  public static ParsedKey parse(String rawKey) {
    Matcher matcher = KEY.matcher(Objects.requireNonNull(rawKey, "rawKey"));
    if (!matcher.matches()) {
      throw new IllegalArgumentException("not a FraudShield API key");
    }
    return new ParsedKey(matcher.group(1), matcher.group(2), matcher.group(3));
  }

  /**
   * HMAC-SHA256 of the secret with the pepper.
   *
   * @param pepper server-side pepper, at least 32 bytes
   * @param secret the key's secret part
   * @return 32-byte HMAC
   */
  public static byte[] hmac(byte[] pepper, String secret) {
    if (pepper.length < MINIMUM_PEPPER_BYTES) {
      throw new IllegalArgumentException("the API key pepper must be at least 32 bytes");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
      return mac.doFinal(secret.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is unavailable", e);
    }
  }
}
