package io.github.mariusbayizere.fraudshield.rules.dsl;

/** A compiled, validated rule expression. Stateless and safe to share between threads. */
@FunctionalInterface
public interface RulePredicate {

  /**
   * Evaluates the expression.
   *
   * @param subject the request and features
   * @return the three-valued result
   */
  Truth evaluate(RuleSubject subject);

  /**
   * Whether the rule fires: only a definite {@link Truth#TRUE} does.
   *
   * @param subject the request and features
   * @return true if the expression is TRUE
   */
  default boolean matches(RuleSubject subject) {
    return evaluate(subject) == Truth.TRUE;
  }
}
