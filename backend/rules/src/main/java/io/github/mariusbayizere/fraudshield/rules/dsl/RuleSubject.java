package io.github.mariusbayizere.fraudshield.rules.dsl;

/** What a rule is evaluated against: the validated request and the scoring result's features. */
@FunctionalInterface
public interface RuleSubject {

  /**
   * The value of a field; fields the subject does not know are missing.
   *
   * @param field a name from {@link FieldCatalogue}
   * @return the value, never null
   */
  FieldValue value(String field);
}
