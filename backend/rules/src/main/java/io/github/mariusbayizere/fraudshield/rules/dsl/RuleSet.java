package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The enabled rules of one institution at one configuration version, replaced atomically on hot
 * reload (E.6).
 *
 * @param version monotonically increasing configuration version
 * @param rules enabled rules
 */
public record RuleSet(long version, List<CompiledRule> rules) {

  /** An empty rule set. */
  public static final RuleSet EMPTY = new RuleSet(0, List.of());

  /** Stores an unmodifiable copy. */
  public RuleSet {
    rules = List.copyOf(rules);
  }

  /**
   * The outcome of evaluating every rule.
   *
   * @param override the highest tier any matching rule raises to, if any rule matched
   * @param matched the rules that matched, for reason codes and trigger counts
   */
  public record Evaluation(Optional<TierOverride> override, List<CompiledRule> matched) {
    /** Stores an unmodifiable copy. */
    public Evaluation {
      matched = List.copyOf(matched);
    }
  }

  /**
   * Evaluates every rule. Compiled predicates only compare values whose types were checked at
   * compile time, so evaluation cannot throw.
   *
   * @param subject the request and features
   * @return the matches and the highest override
   */
  public Evaluation evaluate(RuleSubject subject) {
    List<CompiledRule> matched = new ArrayList<>();
    TierOverride highest = null;
    for (CompiledRule rule : rules) {
      if (rule.predicate().matches(subject)) {
        matched.add(rule);
        if (highest == null || rule.override().compareTo(highest) > 0) {
          highest = rule.override();
        }
      }
    }
    return new Evaluation(Optional.ofNullable(highest), matched);
  }
}
