package io.github.mariusbayizere.fraudshield.auth.web;

/** Bean Validation constraint names to contract validation codes (ADR 0011 section 6). */
final class ConstraintCodes {

  private ConstraintCodes() {}

  static String code(String constraint, Object rejectedValue) {
    if (constraint == null) {
      return "invalid_format";
    }
    return switch (constraint) {
      case "NotNull" -> "required";
      case "NotBlank", "NotEmpty" -> rejectedValue == null ? "required" : "length_out_of_range";
      case "Size", "Length" -> "length_out_of_range";
      case "Min", "Max", "Positive", "PositiveOrZero", "DecimalMin", "DecimalMax" -> "out_of_range";
      case "ValidPassword" -> "password_policy";
      case "ValidPersonName" -> "person_name";
      case "EnumValue" -> "unsupported_value";
      default -> "invalid_format";
    };
  }
}
