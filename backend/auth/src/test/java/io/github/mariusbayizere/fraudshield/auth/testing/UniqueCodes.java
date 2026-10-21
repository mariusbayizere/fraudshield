package io.github.mariusbayizere.fraudshield.auth.testing;

import java.util.UUID;

/**
 * Unique values for test data that must not collide (institution codes, live API-key names). A
 * clock is not a source of uniqueness: {@code System.nanoTime() % 100000} repeats whenever two
 * reads fall on the same residue, and how often that happens depends on the machine's clock.
 */
public final class UniqueCodes {

  /** Hex digits taken from a random UUID: 48 random bits. */
  static final int HEX_DIGITS = 12;

  private UniqueCodes() {}

  /**
   * A prefix followed by twelve random lower-case hex digits.
   *
   * @param prefix the value's readable part, for example {@code "self-"}
   * @return the prefix and twelve hex digits
   */
  public static String of(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, HEX_DIGITS);
  }
}
