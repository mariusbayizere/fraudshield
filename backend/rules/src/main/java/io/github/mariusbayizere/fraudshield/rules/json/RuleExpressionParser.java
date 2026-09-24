package io.github.mariusbayizere.fraudshield.rules.json;

import io.github.mariusbayizere.fraudshield.rules.dsl.InvalidRuleException;
import io.github.mariusbayizere.fraudshield.rules.dsl.Operand;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleError;
import io.github.mariusbayizere.fraudshield.rules.dsl.RuleExpression;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads the JSON rule AST (OpenAPI {@code RuleExpression}) into {@link RuleExpression}.
 *
 * <p>Structural errors use the API's codes: an unexpected property is {@code unknown_field}, a
 * missing one {@code required}, a value of the wrong JSON type {@code type_mismatch}. Semantic
 * checks (known field, operator, operand shape) are the compiler's.
 */
public final class RuleExpressionParser {

  private static final Set<String> COMPARISON_KEYS = Set.of("field", "op", "value");
  private static final Set<String> LOGICAL_KEYS = Set.of("all", "any", "not");

  private final List<RuleError> errors = new ArrayList<>();

  private RuleExpressionParser() {}

  /**
   * Parses an expression.
   *
   * @param node the JSON value of {@code expression}
   * @param path error path of the node, for example {@code expression}
   * @return the AST
   * @throws InvalidRuleException listing every structural problem
   */
  public static RuleExpression parse(JsonNode node, String path) {
    RuleExpressionParser parser = new RuleExpressionParser();
    RuleExpression expression = parser.expression(node, path);
    if (!parser.errors.isEmpty()) {
      throw new InvalidRuleException(parser.errors);
    }
    return expression;
  }

  private RuleExpression expression(JsonNode node, String path) {
    if (node == null || !node.isObject()) {
      errors.add(new RuleError(path, "type_mismatch", "a rule expression is an object"));
      return placeholder();
    }
    List<String> names = new ArrayList<>(node.propertyNames());
    List<String> logical = names.stream().filter(LOGICAL_KEYS::contains).toList();
    if (logical.size() == 1 && names.size() == 1) {
      String key = logical.getFirst();
      JsonNode value = node.get(key);
      if (key.equals("not")) {
        return new RuleExpression.Not(expression(value, path + ".not"));
      }
      if (!value.isArray()) {
        errors.add(new RuleError(path + "." + key, "type_mismatch", key + " takes a list"));
        return placeholder();
      }
      List<RuleExpression> children = new ArrayList<>();
      for (int i = 0; i < value.size(); i++) {
        children.add(expression(value.get(i), path + "." + key + "[" + i + "]"));
      }
      return key.equals("all")
          ? new RuleExpression.All(children)
          : new RuleExpression.Any(children);
    }
    if (!logical.isEmpty()) {
      for (String name : names) {
        if (!name.equals(logical.getFirst())) {
          errors.add(
              new RuleError(
                  path + "." + name, "unknown_field", "a logical node has exactly one property"));
        }
      }
      return placeholder();
    }
    return comparison(node, names, path);
  }

  private RuleExpression comparison(JsonNode node, List<String> names, String path) {
    for (String name : names) {
      if (!COMPARISON_KEYS.contains(name)) {
        errors.add(new RuleError(path + "." + name, "unknown_field", "not a rule property"));
      }
    }
    String field = text(node, "field", path);
    String operator = text(node, "op", path);
    Operand value = operand(node.get("value"), path + ".value", true);
    if (field == null || operator == null) {
      return placeholder();
    }
    return new RuleExpression.Comparison(field, operator, value);
  }

  private String text(JsonNode node, String key, String path) {
    JsonNode value = node.get(key);
    if (value == null) {
      errors.add(new RuleError(path + "." + key, "required", key + " is required"));
      return null;
    }
    if (!value.isString()) {
      errors.add(new RuleError(path + "." + key, "type_mismatch", key + " is a string"));
      return null;
    }
    return value.asString();
  }

  private Operand operand(JsonNode value, String path, boolean listAllowed) {
    if (value == null || value.isNull()) {
      return Operand.NONE;
    }
    if (value.isBoolean()) {
      return new Operand.Number(value.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO);
    }
    if (value.isNumber()) {
      return new Operand.Number(value.decimalValue());
    }
    if (value.isString()) {
      return new Operand.Text(value.asString());
    }
    if (value.isArray() && listAllowed) {
      List<Operand> items = new ArrayList<>();
      for (int i = 0; i < value.size(); i++) {
        items.add(operand(value.get(i), path + "[" + i + "]", false));
      }
      return new Operand.Items(items);
    }
    errors.add(new RuleError(path, "type_mismatch", "not a supported value"));
    return Operand.NONE;
  }

  private static RuleExpression placeholder() {
    return new RuleExpression.Comparison("", "", Operand.NONE);
  }
}
