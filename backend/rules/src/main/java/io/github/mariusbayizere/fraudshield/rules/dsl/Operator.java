package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.Locale;
import java.util.Optional;

/** Comparison operators of the rule DSL (E.6), by wire name. */
public enum Operator {
  /** Equal. */
  EQ,
  /** Not equal (a missing value is UNKNOWN, not "not equal"). */
  NE,
  /** Greater than. */
  GT,
  /** Greater than or equal. */
  GTE,
  /** Less than. */
  LT,
  /** Less than or equal. */
  LTE,
  /** Member of a list. */
  IN,
  /** Not a member of a list. */
  NOT_IN,
  /** Inclusive range {@code [low, high]}. */
  BETWEEN,
  /** The value is missing (null in the request, NaN in the feature vector). */
  IS_NULL,
  /** The value is present. */
  IS_NOT_NULL;

  /**
   * The wire name.
   *
   * @return lower-case name, for example {@code not_in}
   */
  public String wireName() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parses a wire name.
   *
   * @param wireName for example {@code gte}
   * @return the operator, or empty if unknown
   */
  public static Optional<Operator> parse(String wireName) {
    for (Operator operator : values()) {
      if (operator.wireName().equals(wireName)) {
        return Optional.of(operator);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether the operator orders values and therefore needs a numeric field.
   *
   * @return true for gt, gte, lt, lte and between
   */
  public boolean isOrdering() {
    return this == GT || this == GTE || this == LT || this == LTE || this == BETWEEN;
  }

  /**
   * Whether the operator takes no value.
   *
   * @return true for is_null and is_not_null
   */
  public boolean isNullTest() {
    return this == IS_NULL || this == IS_NOT_NULL;
  }
}
