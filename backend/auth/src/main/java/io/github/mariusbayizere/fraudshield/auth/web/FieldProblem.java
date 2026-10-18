package io.github.mariusbayizere.fraudshield.auth.web;

import java.util.Map;
import java.util.Objects;

/**
 * One field-level validation error (ADR 0011 section 6).
 *
 * @param field JSON field name, or a JSON pointer-like path
 * @param code contract ValidationErrorCode
 * @param message human-readable message
 */
public record FieldProblem(String field, String code, String message) {

  /**
   * The HTTP status each code implies ({@code x-status-by-code} in the contract): 400 when the body
   * is not a usable instance of the schema, 422 for a well-formed field with an unacceptable value.
   */
  public static final Map<String, Integer> STATUS_BY_CODE =
      Map.ofEntries(
          Map.entry("malformed_json", 400),
          Map.entry("required", 400),
          Map.entry("unknown_field", 400),
          Map.entry("not_a_token", 400),
          Map.entry("type_mismatch", 422),
          Map.entry("invalid_format", 422),
          Map.entry("unsupported_value", 422),
          Map.entry("out_of_range", 422),
          Map.entry("length_out_of_range", 422),
          Map.entry("item_count_out_of_range", 422),
          Map.entry("duplicate_items", 422),
          Map.entry("timestamp_in_future", 422),
          Map.entry("thresholds_not_ordered", 422),
          Map.entry("duplicate_channel", 422),
          Map.entry("invalid_cidr", 422),
          Map.entry("webhook_url_not_allowed", 422),
          Map.entry("password_policy", 422),
          Map.entry("person_name", 422),
          Map.entry("escalation_target_not_higher", 422),
          Map.entry("no_change", 422));

  /** Validates the code against the catalogue. */
  public FieldProblem {
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(message, "message");
    if (!STATUS_BY_CODE.containsKey(code)) {
      throw new IllegalArgumentException("not a contract validation code: " + code);
    }
  }

  /**
   * The status this error implies.
   *
   * @return 400 or 422
   */
  public int status() {
    return STATUS_BY_CODE.get(code);
  }
}
