package io.github.mariusbayizere.fraudshield.auth.apikey;

import io.github.mariusbayizere.fraudshield.auth.crypto.Crypto;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The API-key format {@code fsk_<env>_<keyId>_<secret>} (D-19): a 12-character public key ID for
 * lookup and a 256-bit secret, base64url (43 characters). Only an HMAC of the secret is stored.
 */
public final class ApiKeyFormat {

  private static final Pattern KEY =
      Pattern.compile("^fsk_(dev|test|stg|prod)_([a-z0-9]{12})_([A-Za-z0-9_-]{43})$");
  private static final Pattern ENVIRONMENT = Pattern.compile("^(dev|test|stg|prod)$");
  private static final Pattern ALPHANUMERIC_TAIL = Pattern.compile("[A-Za-z0-9]{4}$");
  private static final String KEY_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
  private static final String WEBHOOK_ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int KEY_ID_LENGTH = 12;
  private static final int SECRET_BYTES = 32;
  private static final int WEBHOOK_SECRET_LENGTH = 48;

  private ApiKeyFormat() {}

  /**
   * A parsed key.
   *
   * @param environment environment segment
   * @param keyId public key ID
   * @param secret secret (never stored or logged)
   */
  public record ParsedKey(String environment, String keyId, String secret) {

    /**
     * The raw key.
     *
     * @return {@code fsk_<env>_<keyId>_<secret>}
     */
    public String raw() {
      return "fsk_" + environment + "_" + keyId + "_" + secret;
    }

    /**
     * The last four characters, shown in the admin list (FR-06-07).
     *
     * @return last four characters of the secret
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
   * Parses a presented key.
   *
   * @param raw the X-API-Key header
   * @return the key, or empty if it is not in the FraudShield format
   */
  public static Optional<ParsedKey> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    Matcher matcher = KEY.matcher(raw);
    return matcher.matches()
        ? Optional.of(new ParsedKey(matcher.group(1), matcher.group(2), matcher.group(3)))
        : Optional.empty();
  }

  /**
   * Generates a key. The secret is drawn again until its last four characters are alphanumeric,
   * because the contract shows them as {@code [A-Za-z0-9]{4}}; this removes about 2 of 256 bits.
   *
   * @param environment environment segment
   * @return the new key
   */
  public static ParsedKey generate(String environment) {
    requireEnvironment(environment);
    String secret;
    do {
      secret = Crypto.randomToken(SECRET_BYTES);
    } while (!ALPHANUMERIC_TAIL.matcher(secret).find());
    return new ParsedKey(environment, random(KEY_ID_ALPHABET, KEY_ID_LENGTH), secret);
  }

  /**
   * Generates a webhook signing secret {@code whsec_<env>_<48 alphanumerics>} (about 285 bits).
   *
   * @param environment environment segment
   * @return the secret
   */
  public static String webhookSecret(String environment) {
    requireEnvironment(environment);
    return "whsec_" + environment + "_" + random(WEBHOOK_ALPHABET, WEBHOOK_SECRET_LENGTH);
  }

  /**
   * Validates an environment segment.
   *
   * @param environment the segment
   * @return the segment
   */
  public static String requireEnvironment(String environment) {
    if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
      throw new IllegalStateException(
          "fraudshield.auth.environment must be dev, test, stg or prod: " + environment);
    }
    return environment;
  }

  private static String random(String alphabet, int length) {
    Objects.requireNonNull(alphabet);
    StringBuilder out = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      out.append(alphabet.charAt(Crypto.randomInt(alphabet.length())));
    }
    return out.toString();
  }
}
