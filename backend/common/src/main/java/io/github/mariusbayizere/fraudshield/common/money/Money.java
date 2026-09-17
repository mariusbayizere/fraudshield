package io.github.mariusbayizere.fraudshield.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An amount of money in a specific currency.
 *
 * <p>Amounts are stored exactly as {@code DECIMAL(18,4)} (SRS section 6, D-43): at most 18
 * significant digits and 4 decimal places. The scale is normalised to 4 so that equal amounts are
 * equal values regardless of how they were written. Money never uses binary floating point, and
 * arithmetic across currencies is rejected rather than silently converted.
 *
 * <p>The sign is not restricted here: compensating and reversal entries need negative amounts.
 * Boundaries that require a positive amount, such as transaction ingestion (build prompt E.1:
 * {@code amount > 0}), validate it with {@link #isPositive()}.
 *
 * @param amount exact decimal amount, scale normalised to {@link #STORAGE_SCALE}
 * @param currency ISO 4217 currency
 */
public record Money(BigDecimal amount, CurrencyCode currency) {

  /** Decimal places stored for every amount, matching {@code DECIMAL(18,4)}. */
  public static final int STORAGE_SCALE = 4;

  /** Total significant digits stored, matching {@code DECIMAL(18,4)}. */
  public static final int STORAGE_PRECISION = 18;

  /** Smallest non-zero magnitude that {@code DECIMAL(18,4)} can store: 0.0001. */
  public static final BigDecimal SMALLEST_STORABLE_MAGNITUDE = new BigDecimal("0.0001");

  /** Largest magnitude that {@code DECIMAL(18,4)} can store: 99,999,999,999,999.9999. */
  public static final BigDecimal LARGEST_STORABLE_MAGNITUDE = new BigDecimal("99999999999999.9999");

  /**
   * Creates a money value, rejecting amounts that cannot be stored exactly.
   *
   * @throws IllegalArgumentException if the amount has more than 4 decimal places or more than 14
   *     integer digits
   */
  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    BigDecimal stripped = amount.stripTrailingZeros();
    if (stripped.scale() > STORAGE_SCALE) {
      throw new IllegalArgumentException("amount has more than 4 decimal places");
    }
    amount = amount.setScale(STORAGE_SCALE, RoundingMode.UNNECESSARY);
    if (amount.precision() > STORAGE_PRECISION) {
      throw new IllegalArgumentException("amount exceeds DECIMAL(18,4)");
    }
  }

  /** Parses a decimal string such as {@code "15000"} or {@code "1250.50"}. */
  public static Money of(String amount, CurrencyCode currency) {
    return new Money(new BigDecimal(amount), currency);
  }

  /**
   * The smallest amount expressible in the currency's minor unit, for example 1 RWF or 0.01 KES.
   */
  public static Money smallestMinorUnit(CurrencyCode currency) {
    return new Money(BigDecimal.ONE.movePointLeft(currency.minorUnitDigits()), currency);
  }

  /**
   * Returns this value if the amount is strictly greater than zero.
   *
   * <p>For boundaries that require {@code amount > 0}, such as transaction ingestion (build prompt
   * E.1).
   *
   * @throws IllegalArgumentException if the amount is zero or negative
   */
  public Money requirePositive() {
    if (!isPositive()) {
      throw new IllegalArgumentException("amount must be greater than zero");
    }
    return this;
  }

  /** Whether the amount is strictly greater than zero. */
  public boolean isPositive() {
    return amount.signum() > 0;
  }

  /**
   * Adds another amount in the same currency.
   *
   * @throws IllegalArgumentException if the currencies differ
   */
  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(amount.add(other.amount), currency);
  }

  /**
   * The amount rounded to the currency's minor unit for display, using banker's rounding. For
   * example 1250.5000 RWF displays as 1250 and 99.9950 KES as 100.00.
   */
  public BigDecimal displayAmount() {
    return amount.setScale(currency.minorUnitDigits(), RoundingMode.HALF_EVEN);
  }

  private void requireSameCurrency(Money other) {
    if (other.currency != currency) {
      throw new IllegalArgumentException(
          "currency mismatch: " + currency + " vs " + other.currency);
    }
  }
}
