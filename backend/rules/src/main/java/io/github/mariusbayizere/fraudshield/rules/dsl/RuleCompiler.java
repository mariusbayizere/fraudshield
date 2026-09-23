package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a rule expression and compiles it to a {@link RulePredicate} (E.6, FR-05-05).
 *
 * <p>Validation is complete, not first-error: every problem is reported with its path. Rules are
 * data, never code — there is no regex and no reflection, and the size limits below bound the cost
 * of evaluating any accepted rule on the hot path.
 */
public final class RuleCompiler {

  /** Deepest nesting of all/any/not accepted. */
  public static final int MAX_DEPTH = 16;

  /** Most nodes (logical and comparison) in one expression. */
  public static final int MAX_NODES = 256;

  /** Most items in an in or not_in list. */
  public static final int MAX_LIST_ITEMS = 1000;

  private final List<RuleError> errors = new ArrayList<>();
  private int nodes;

  private RuleCompiler() {}

  /**
   * Compiles an expression.
   *
   * @param expression the parsed AST
   * @param basePath path prefix for errors, for example {@code expression}
   * @return the predicate
   * @throws InvalidRuleException listing every problem found
   */
  public static RulePredicate compile(RuleExpression expression, String basePath) {
    RuleCompiler compiler = new RuleCompiler();
    RulePredicate predicate = compiler.node(expression, basePath, 1);
    if (compiler.nodes > MAX_NODES) {
      compiler.errors.add(
          new RuleError(
              basePath,
              "item_count_out_of_range",
              "a rule may have at most " + MAX_NODES + " nodes"));
    }
    if (!compiler.errors.isEmpty()) {
      throw new InvalidRuleException(compiler.errors);
    }
    return predicate;
  }

  private RulePredicate node(RuleExpression expression, String path, int depth) {
    nodes++;
    if (depth > MAX_DEPTH) {
      errors.add(
          new RuleError(path, "out_of_range", "rules nest at most " + MAX_DEPTH + " levels"));
      return subject -> Truth.UNKNOWN;
    }
    return switch (expression) {
      case RuleExpression.All all -> logical(all.children(), path + ".all", depth, true);
      case RuleExpression.Any any -> logical(any.children(), path + ".any", depth, false);
      case RuleExpression.Not not -> {
        RulePredicate child = node(not.child(), path + ".not", depth + 1);
        yield subject -> child.evaluate(subject).not();
      }
      case RuleExpression.Comparison comparison -> comparison(comparison, path);
    };
  }

  private RulePredicate logical(
      List<RuleExpression> children, String path, int depth, boolean conjunction) {
    if (children.isEmpty()) {
      errors.add(new RuleError(path, "item_count_out_of_range", "needs at least one condition"));
    }
    List<RulePredicate> compiled = new ArrayList<>(children.size());
    for (int i = 0; i < children.size(); i++) {
      compiled.add(node(children.get(i), path + "[" + i + "]", depth + 1));
    }
    RulePredicate[] parts = compiled.toArray(RulePredicate[]::new);
    if (conjunction) {
      return subject -> {
        Truth result = Truth.TRUE;
        for (RulePredicate part : parts) {
          result = result.and(part.evaluate(subject));
          if (result == Truth.FALSE) {
            return result;
          }
        }
        return result;
      };
    }
    return subject -> {
      Truth result = Truth.FALSE;
      for (RulePredicate part : parts) {
        result = result.or(part.evaluate(subject));
        if (result == Truth.TRUE) {
          return result;
        }
      }
      return result;
    };
  }

  private RulePredicate comparison(RuleExpression.Comparison comparison, String path) {
    final int before = errors.size();
    RuleField field = FieldCatalogue.find(comparison.field()).orElse(null);
    if (field == null) {
      errors.add(
          new RuleError(
              path + ".field", "unsupported_value", "not a request field or a registered feature"));
    }
    Operator operator = Operator.parse(comparison.operator()).orElse(null);
    if (operator == null) {
      errors.add(new RuleError(path + ".op", "unsupported_value", "unknown operator"));
    }
    if (field != null && operator != null) {
      checkOperand(field, operator, comparison.value(), path + ".value");
    }
    if (errors.size() > before) {
      return subject -> Truth.UNKNOWN;
    }
    return build(field.name(), operator, comparison.value());
  }

  private void checkOperand(RuleField field, Operator operator, Operand value, String path) {
    if (operator.isOrdering() && field.type() != FieldType.NUMBER) {
      errors.add(
          new RuleError(path, "type_mismatch", operator.wireName() + " needs a numeric field"));
      return;
    }
    switch (operator) {
      case IS_NULL, IS_NOT_NULL -> {
        if (!(value instanceof Operand.None)) {
          errors.add(
              new RuleError(path, "unsupported_value", operator.wireName() + " takes no value"));
        }
      }
      case IN, NOT_IN -> {
        if (!(value instanceof Operand.Items items)) {
          errors.add(new RuleError(path, "type_mismatch", "needs a list"));
          return;
        }
        if (items.items().isEmpty() || items.items().size() > MAX_LIST_ITEMS) {
          errors.add(
              new RuleError(
                  path, "item_count_out_of_range", "needs 1 to " + MAX_LIST_ITEMS + " items"));
        }
        for (int i = 0; i < items.items().size(); i++) {
          checkScalar(field, items.items().get(i), path + "[" + i + "]");
        }
      }
      case BETWEEN -> {
        if (!(value instanceof Operand.Items items) || items.items().size() != 2) {
          errors.add(
              new RuleError(path, "item_count_out_of_range", "between needs exactly [low, high]"));
          return;
        }
        boolean numeric = true;
        for (int i = 0; i < 2; i++) {
          if (!(items.items().get(i) instanceof Operand.Number)) {
            errors.add(new RuleError(path + "[" + i + "]", "type_mismatch", "needs a number"));
            numeric = false;
          }
        }
        if (numeric
            && ((Operand.Number) items.items().get(0))
                    .value()
                    .compareTo(((Operand.Number) items.items().get(1)).value())
                > 0) {
          errors.add(new RuleError(path, "out_of_range", "low must not exceed high"));
        }
      }
      default -> checkScalar(field, value, path);
    }
  }

  private void checkScalar(RuleField field, Operand value, String path) {
    boolean ok =
        field.type() == FieldType.NUMBER
            ? value instanceof Operand.Number
            : value instanceof Operand.Text;
    if (!ok) {
      errors.add(
          new RuleError(
              path,
              "type_mismatch",
              field.type() == FieldType.NUMBER ? "needs a number" : "needs a string"));
    }
  }

  private static RulePredicate build(String field, Operator operator, Operand value) {
    return switch (operator) {
      case IS_NULL -> subject -> Truth.of(subject.value(field) instanceof FieldValue.Missing);
      case IS_NOT_NULL ->
          subject -> Truth.of(!(subject.value(field) instanceof FieldValue.Missing));
      case EQ -> scalar(field, value, cmp -> cmp == 0);
      case NE -> scalar(field, value, cmp -> cmp != 0);
      case GT -> scalar(field, value, cmp -> cmp > 0);
      case GTE -> scalar(field, value, cmp -> cmp >= 0);
      case LT -> scalar(field, value, cmp -> cmp < 0);
      case LTE -> scalar(field, value, cmp -> cmp <= 0);
      case IN -> membership(field, (Operand.Items) value, true);
      case NOT_IN -> membership(field, (Operand.Items) value, false);
      case BETWEEN -> between(field, (Operand.Items) value);
    };
  }

  private interface Comparing {
    boolean holds(int comparison);
  }

  private static RulePredicate scalar(String field, Operand operand, Comparing comparing) {
    if (operand instanceof Operand.Number number) {
      BigDecimal expected = number.value();
      return subject ->
          subject.value(field) instanceof FieldValue.Number actual
              ? Truth.of(comparing.holds(actual.value().compareTo(expected)))
              : Truth.UNKNOWN;
    }
    String expected = ((Operand.Text) operand).value();
    return subject ->
        subject.value(field) instanceof FieldValue.Category actual
            ? Truth.of(comparing.holds(actual.value().compareTo(expected)))
            : Truth.UNKNOWN;
  }

  private static RulePredicate membership(String field, Operand.Items items, boolean in) {
    Set<BigDecimal> numbers = new HashSet<>();
    Set<String> texts = new HashSet<>();
    for (Operand item : items.items()) {
      if (item instanceof Operand.Number number) {
        numbers.add(number.value().stripTrailingZeros());
      } else {
        texts.add(((Operand.Text) item).value());
      }
    }
    return subject -> {
      FieldValue actual = subject.value(field);
      boolean member;
      if (actual instanceof FieldValue.Number number) {
        member = numbers.contains(number.value().stripTrailingZeros());
      } else if (actual instanceof FieldValue.Category category) {
        member = texts.contains(category.value());
      } else {
        return Truth.UNKNOWN;
      }
      return Truth.of(member == in);
    };
  }

  private static RulePredicate between(String field, Operand.Items items) {
    BigDecimal low = ((Operand.Number) items.items().get(0)).value();
    BigDecimal high = ((Operand.Number) items.items().get(1)).value();
    return subject ->
        subject.value(field) instanceof FieldValue.Number actual
            ? Truth.of(actual.value().compareTo(low) >= 0 && actual.value().compareTo(high) <= 0)
            : Truth.UNKNOWN;
  }
}
