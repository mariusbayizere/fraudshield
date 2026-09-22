package io.github.mariusbayizere.fraudshield.ingest.request;

import java.util.Objects;
import java.util.Set;

/**
 * One field error with its {@code ValidationErrorCode} (ADR 0011 section 6).
 *
 * @param field JSON field, empty for the body itself
 * @param code error code
 * @param message explanation that never echoes the value (it may be personal data)
 */
public record ValidationError(String field, String code, String message) {

  /** Codes that make the body unusable and the response 400 rather than 422. */
  public static final Set<String> BAD_REQUEST_CODES =
      Set.of("malformed_json", "required", "unknown_field", "not_a_token");

  /** Requires every component. */
  public ValidationError {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }

  /**
   * Whether this error alone makes the response 400.
   *
   * @return true for a 400-class code
   */
  public boolean badRequest() {
    return BAD_REQUEST_CODES.contains(code);
  }
}
