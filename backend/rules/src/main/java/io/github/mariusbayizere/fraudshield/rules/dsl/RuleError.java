package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.io.Serializable;
import java.util.Objects;

/**
 * One problem with a rule, reported with the API's validation error codes (ADR 0011 section 6).
 *
 * @param path location, for example {@code expression.all[1].op}
 * @param code a {@code ValidationErrorCode} such as {@code unsupported_value}
 * @param message human-readable explanation, never containing field values
 */
public record RuleError(String path, String code, String message) implements Serializable {

  /** Requires every component. */
  public RuleError {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }
}
