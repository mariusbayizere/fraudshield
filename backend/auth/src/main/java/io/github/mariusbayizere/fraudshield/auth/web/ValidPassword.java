package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.auth.domain.PasswordPolicy;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The FR-07-07 password policy as a Bean Validation constraint; the message names the failed rule
 * (FR-07-07: specific failure reasons). Null is left to {@code @NotNull}.
 */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPassword.Validator.class)
public @interface ValidPassword {

  /**
   * Default message.
   *
   * @return the message
   */
  String message() default "Password does not meet the policy";

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

  /** Applies {@link PasswordPolicy}. */
  final class Validator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      if (value == null) {
        return true;
      }
      return PasswordPolicy.check(value)
          .map(
              rejection -> {
                context.disableDefaultConstraintViolation();
                context
                    .buildConstraintViolationWithTemplate(reason(rejection))
                    .addConstraintViolation();
                return false;
              })
          .orElse(true);
    }

    static String reason(PasswordPolicy.Rejection rejection) {
      return switch (rejection) {
        case LENGTH -> "LENGTH: use 8 to 72 characters";
        case BYTES -> "BYTES: the password is longer than 72 bytes";
        case CHARACTERS -> "CHARACTERS: remove control, invisible or separator characters";
        case UPPER -> "UPPER: add an upper-case letter A-Z";
        case LOWER -> "LOWER: add a lower-case letter a-z";
        case DIGIT -> "DIGIT: add a digit 0-9";
        case SPECIAL -> "SPECIAL: add a special character";
      };
    }
  }
}
