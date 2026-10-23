package io.github.mariusbayizere.fraudshield.rules.dsl;

import java.util.ArrayList;
import java.util.List;

/** A rule that does not parse or compile; carries every error found, in document order. */
public final class InvalidRuleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The errors, as a serialisable list of serialisable records. */
  private final ArrayList<RuleError> errors;

  /**
   * Creates the exception.
   *
   * @param errors at least one error
   */
  public InvalidRuleException(List<RuleError> errors) {
    super(
        errors.size()
            + " rule error(s), first: "
            + errors.getFirst().path()
            + " "
            + errors.getFirst().code());
    this.errors = new ArrayList<>(errors);
  }

  /**
   * The errors.
   *
   * @return unmodifiable list
   */
  public List<RuleError> errors() {
    return List.copyOf(errors);
  }
}
