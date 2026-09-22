package io.github.mariusbayizere.fraudshield.auth.domain;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The staff password policy (FR-07-07, ADR 0014 decision 7), identical to the front end's Zod rule
 * and tested against {@code contracts/validation/password-vectors.json}.
 *
 * <p>8-72 code points and at most 72 UTF-8 bytes (bcrypt reads only the first 72 bytes); no
 * character of general category Cc, Cf, Cs or Co, nor of category Z other than U+0020 SPACE;
 * unassigned code points are allowed. At least one ASCII upper-case letter, one ASCII lower-case
 * letter, one ASCII digit and one special character (any other permitted character except the
 * space). Checks run in the order of {@link Rejection} and the first failure is the reason.
 */
public final class PasswordPolicy {

  /** Minimum length in code points. */
  public static final int MIN_CODE_POINTS = 8;

  /** Maximum length in code points. */
  public static final int MAX_CODE_POINTS = 72;

  /** Maximum length in UTF-8 bytes, the bcrypt input limit. */
  public static final int MAX_UTF8_BYTES = 72;

  /** Why a password is rejected, in check order. */
  public enum Rejection {
    /** Fewer than 8 or more than 72 code points. */
    LENGTH,
    /** More than 72 UTF-8 bytes. */
    BYTES,
    /** A forbidden control, format, surrogate, private-use or separator character. */
    CHARACTERS,
    /** No ASCII upper-case letter. */
    UPPER,
    /** No ASCII lower-case letter. */
    LOWER,
    /** No ASCII digit. */
    DIGIT,
    /** No special character. */
    SPECIAL
  }

  private PasswordPolicy() {}

  /**
   * Checks a candidate password.
   *
   * @param password the candidate
   * @return the first failed rule, or empty if the password is acceptable
   */
  public static Optional<Rejection> check(String password) {
    int codePoints = password.codePointCount(0, password.length());
    if (codePoints < MIN_CODE_POINTS || codePoints > MAX_CODE_POINTS) {
      return Optional.of(Rejection.LENGTH);
    }
    if (!fitsBcrypt(password)) {
      return Optional.of(Rejection.BYTES);
    }
    if (password.codePoints().anyMatch(PasswordPolicy::isForbidden)) {
      return Optional.of(Rejection.CHARACTERS);
    }
    if (password.chars().noneMatch(c -> c >= 'A' && c <= 'Z')) {
      return Optional.of(Rejection.UPPER);
    }
    if (password.chars().noneMatch(c -> c >= 'a' && c <= 'z')) {
      return Optional.of(Rejection.LOWER);
    }
    if (password.chars().noneMatch(c -> c >= '0' && c <= '9')) {
      return Optional.of(Rejection.DIGIT);
    }
    if (password.codePoints().noneMatch(PasswordPolicy::isSpecial)) {
      return Optional.of(Rejection.SPECIAL);
    }
    return Optional.empty();
  }

  /**
   * Whether a submitted password can be compared with bcrypt at all. Sign-in answers a longer one
   * as invalid credentials without hashing it (ADR 0014).
   *
   * @param password the submitted password
   * @return whether it has at most 72 UTF-8 bytes
   */
  public static boolean fitsBcrypt(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
  }

  private static boolean isForbidden(int codePoint) {
    if (codePoint == ' ') {
      return false;
    }
    int type = Character.getType(codePoint);
    return type == Character.CONTROL
        || type == Character.FORMAT
        || type == Character.SURROGATE
        || type == Character.PRIVATE_USE
        || type == Character.SPACE_SEPARATOR
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private static boolean isSpecial(int codePoint) {
    boolean asciiLetterOrDigit =
        (codePoint >= 'A' && codePoint <= 'Z')
            || (codePoint >= 'a' && codePoint <= 'z')
            || (codePoint >= '0' && codePoint <= '9');
    return !asciiLetterOrDigit && codePoint != ' ';
  }
}
