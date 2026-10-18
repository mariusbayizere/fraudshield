package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.math.BigDecimal;
import java.util.Objects;

/** A field's value at evaluation time: a number, a category or missing. */
public sealed interface FieldValue {

  /** The single missing value. */
  FieldValue MISSING = new Missing();

  /**
   * A numeric value; NaN and infinities are missing (D-04 structural NaN).
   *
   * @param value the number
   * @return the value
   */
  static FieldValue of(double value) {
    return Double.isFinite(value) ? new Number(BigDecimal.valueOf(value)) : MISSING;
  }

  /**
   * A numeric value.
   *
   * @param value the number, or null for missing
   * @return the value
   */
  static FieldValue of(BigDecimal value) {
    return value == null ? MISSING : new Number(value);
  }

  /**
   * A categorical value.
   *
   * @param value the category, or null for missing
   * @return the value
   */
  static FieldValue category(String value) {
    return value == null ? MISSING : new Category(value);
  }

  /**
   * A numeric value.
   *
   * @param value the number
   */
  record Number(BigDecimal value) implements FieldValue {
    /** Requires a value. */
    public Number {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A categorical value.
   *
   * @param value the category
   */
  record Category(String value) implements FieldValue {
    /** Requires a value. */
    public Category {
      Objects.requireNonNull(value, "value");
    }
  }

  /** A missing value. */
  record Missing() implements FieldValue {}
}
