package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.Objects;
import java.util.UUID;

/**
 * One enabled rule version, ready to evaluate.
 *
 * @param ruleId the rule
 * @param version the immutable version (a row of {@code alert_rule_versions})
 * @param override the tier it raises to
 * @param predicate the compiled expression
 */
public record CompiledRule(
    UUID ruleId, int version, TierOverride override, RulePredicate predicate) {

  /** Requires every component. */
  public CompiledRule {
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(override, "override");
    Objects.requireNonNull(predicate, "predicate");
    if (version < 1) {
      throw new IllegalArgumentException("rule versions start at 1");
    }
  }
}
