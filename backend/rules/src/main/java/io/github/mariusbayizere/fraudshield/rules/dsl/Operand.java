package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** The {@code value} of a comparison as written in the rule. Booleans are numbers 1 and 0. */
public sealed interface Operand {

  /** No value (absent or JSON null); only valid for is_null and is_not_null. */
  Operand NONE = new None();

  /**
   * A number.
   *
   * @param value the number
   */
  record Number(BigDecimal value) implements Operand {
    /** Requires a value. */
    public Number {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A string.
   *
   * @param value the string
   */
  record Text(String value) implements Operand {
    /** Requires a value. */
    public Text {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * A list of scalars, for in, not_in and between.
   *
   * @param items the items
   */
  record Items(List<Operand> items) implements Operand {
    /** Stores an unmodifiable copy. */
    public Items {
      items = List.copyOf(items);
    }
  }

  /** No value. */
  record None() implements Operand {}
}
