package io.github.mariusbayizere.fraudshield.common.money;

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ISO 4217 currencies accepted by FraudShield, with their minor-unit exponents.
 *
 * <p>The exponents are held in code rather than read from the JDK so that amount formatting
 * cannot change with a JDK upgrade (D-43). A unit test cross-checks the table against
 * {@link java.util.Currency}. The set covers the five EAC markets in the dataset (RW, KE, TZ,
 * UG, CD), the remaining EAC member states, and the settlement currencies seen in
 * cross-border corridors.
 */
public enum CurrencyCode {
  RWF(0),
  KES(2),
  TZS(2),
  UGX(0),
  CDF(2),
  BIF(0),
  SSP(2),
  SOS(2),
  USD(2),
  EUR(2);

  private static final Pattern ISO_ALPHA_CODE = Pattern.compile("[A-Z]{3}");

  private final int minorUnitDigits;

  CurrencyCode(int minorUnitDigits) {
    this.minorUnitDigits = minorUnitDigits;
  }

  /** Number of decimal places in the currency's minor unit (ISO 4217 exponent). */
  public int minorUnitDigits() {
    return minorUnitDigits;
  }

  /**
   * Parses an upper-case ISO 4217 alphabetic code.
   *
   * @param code three upper-case letters, for example {@code "RWF"}
   * @return the currency, or empty when the code is not supported
   */
  public static Optional<CurrencyCode> parse(String code) {
    if (code == null || !ISO_ALPHA_CODE.matcher(code).matches()) {
      return Optional.empty();
    }
    return Arrays.stream(values()).filter(c -> c.name().equals(code)).findFirst();
  }
}
