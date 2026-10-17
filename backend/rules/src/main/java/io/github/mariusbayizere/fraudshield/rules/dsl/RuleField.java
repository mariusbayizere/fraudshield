package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.Objects;

/**
 * A field a rule may reference.
 *
 * @param name wire name
 * @param type how it compares
 * @param source request field or feature
 */
public record RuleField(String name, FieldType type, FieldSource source) {

  /** Requires every component. */
  public RuleField {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(source, "source");
  }
}
