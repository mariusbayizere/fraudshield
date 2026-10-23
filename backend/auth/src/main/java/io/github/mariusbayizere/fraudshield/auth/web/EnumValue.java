package io.github.mariusbayizere.fraudshield.auth.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

/**
 * A string that must name a constant of an enum (contract {@code enum}, code {@code
 * unsupported_value}). Enum fields are bound as strings and checked here, rather than by Jackson,
 * so an invalid value is reported together with every other field error (ADR 0011: all errors are
 * reported) instead of aborting the parse. Null is left to {@code @NotNull}.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = EnumValue.Validator.class)
public @interface EnumValue {

  /**
   * The enum.
   *
   * @return its class
   */
  Class<? extends Enum<?>> of();

  /**
   * Default message.
   *
   * @return the message
   */
  String message() default "Unsupported value";

  /**
   * Groups.
   *
   * @return the groups
   */
  Class<?>[] groups() default {};

  /**
   * Payload.
   *
   * @return the payload
   */
  Class<? extends Payload>[] payload() default {};

  /** Checks the value against the enum's constant names. */
  final class Validator implements ConstraintValidator<EnumValue, String> {

    private String[] names = new String[0];

    @Override
    public void initialize(EnumValue annotation) {
      names =
          Arrays.stream(annotation.of().getEnumConstants()).map(Enum::name).toArray(String[]::new);
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || Arrays.asList(names).contains(value);
    }
  }
}
