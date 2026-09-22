package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.List;
import java.util.Objects;

/** The rule DSL abstract syntax tree (E.6, OpenAPI {@code RuleExpression}). */
public sealed interface RuleExpression {

  /**
   * True when every child is true.
   *
   * @param children at least one child
   */
  record All(List<RuleExpression> children) implements RuleExpression {
    /** Stores an unmodifiable copy. */
    public All {
      children = List.copyOf(children);
    }
  }

  /**
   * True when any child is true.
   *
   * @param children at least one child
   */
  record Any(List<RuleExpression> children) implements RuleExpression {
    /** Stores an unmodifiable copy. */
    public Any {
      children = List.copyOf(children);
    }
  }

  /**
   * Negation.
   *
   * @param child the negated expression
   */
  record Not(RuleExpression child) implements RuleExpression {
    /** Requires a child. */
    public Not {
      Objects.requireNonNull(child, "child");
    }
  }

  /**
   * A comparison {@code {field, op, value}}.
   *
   * @param field wire field name, not yet validated
   * @param operator wire operator name, not yet validated
   * @param value the operand
   */
  record Comparison(String field, String operator, Operand value) implements RuleExpression {
    /** Requires every component. */
    public Comparison {
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(value, "value");
    }
  }
}
