package io.github.mariusbayizere.fraudshield.rules.json;

import io.github.mariusbayizere.fraudshield.rules.dsl.RuleCompiler;
import io.github.mariusbayizere.fraudshield.rules.dsl.RulePredicate;
import tools.jackson.databind.JsonNode;

/** Parses and compiles a stored or submitted rule expression in one step. */
public final class RuleDefinitions {

  private RuleDefinitions() {}

  /**
   * Parses then compiles; structural errors are reported before semantic ones.
   *
   * @param expression JSON value of {@code rule_expression}
   * @param path error path, for example {@code expression}
   * @return the predicate
   * @throws io.github.mariusbayizere.fraudshield.rules.dsl.InvalidRuleException on any error
   */
  public static RulePredicate compile(JsonNode expression, String path) {
    return RuleCompiler.compile(RuleExpressionParser.parse(expression, path), path);
  }
}
