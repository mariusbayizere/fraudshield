package io.github.mariusbayizere.fraudshield.rules.dsl;

/**
 * Kleene three-valued logic. A comparison against a missing value is {@link #UNKNOWN}, and a rule
 * fires only when its whole expression is {@link #TRUE}, so a rule never raises a tier because data
 * was absent unless it says {@code is_null} explicitly (E.6: "every operator has unit tests,
 * including null semantics").
 */
public enum Truth {
  /** Definitely true. */
  TRUE,
  /** Definitely false. */
  FALSE,
  /** Not decidable because an operand was missing. */
  UNKNOWN;

  /**
   * Converts a boolean.
   *
   * @param value the boolean
   * @return {@link #TRUE} or {@link #FALSE}
   */
  public static Truth of(boolean value) {
    return value ? TRUE : FALSE;
  }

  /**
   * Kleene conjunction.
   *
   * @param other the other operand
   * @return FALSE if either is FALSE, TRUE if both are TRUE, UNKNOWN otherwise
   */
  public Truth and(Truth other) {
    if (this == FALSE || other == FALSE) {
      return FALSE;
    }
    return this == TRUE && other == TRUE ? TRUE : UNKNOWN;
  }

  /**
   * Kleene disjunction.
   *
   * @param other the other operand
   * @return TRUE if either is TRUE, FALSE if both are FALSE, UNKNOWN otherwise
   */
  public Truth or(Truth other) {
    if (this == TRUE || other == TRUE) {
      return TRUE;
    }
    return this == FALSE && other == FALSE ? FALSE : UNKNOWN;
  }

  /**
   * Kleene negation.
   *
   * @return the negation; UNKNOWN stays UNKNOWN
   */
  public Truth not() {
    return switch (this) {
      case TRUE -> FALSE;
      case FALSE -> TRUE;
      case UNKNOWN -> UNKNOWN;
    };
  }
}
