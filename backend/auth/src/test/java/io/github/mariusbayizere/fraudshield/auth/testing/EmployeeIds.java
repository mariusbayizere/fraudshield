package io.github.mariusbayizere.fraudshield.auth.testing;

import java.util.Locale;

/** Employee IDs for test accounts, apart from the integration base so a unit test can call it. */
final class EmployeeIds {

  /** Base-36 digits in every ID; with the "E", 11 characters, inside the API's 4 to 20. */
  static final int DIGITS = 10;

  private EmployeeIds() {}

  /**
   * The employee ID for one random value. The value is read as unsigned, so no ID carries a minus
   * sign, and left-padded with zeros, so a value with fewer than ten base-36 digits still gives
   * ten. Taking ten characters of such a value instead threw, and reddened main on 2026-10-20.
   *
   * @param value a random value
   * @return "E" and ten upper-case base-36 digits
   */
  static String of(long value) {
    String digits = Long.toUnsignedString(value, 36);
    String padded = "0".repeat(Math.max(0, DIGITS - digits.length())) + digits;
    return "E" + padded.substring(0, DIGITS).toUpperCase(Locale.ROOT);
  }
}
