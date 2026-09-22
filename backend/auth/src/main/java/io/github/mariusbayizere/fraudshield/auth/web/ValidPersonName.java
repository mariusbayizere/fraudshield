package io.github.mariusbayizere.fraudshield.auth.web;

import io.github.mariusbayizere.fraudshield.common.identity.PersonName;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The person-name rule (ADR 0013) as a Bean Validation constraint. Null is left to @NotNull. */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidPersonName.Validator.class)
public @interface ValidPersonName {

  /**
   * Default message.
   *
   * @return the message
   */
  String message() default "Not a valid person name";

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

  /** Applies {@link PersonName#check}. */
  final class Validator implements ConstraintValidator<ValidPersonName, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
      return value == null || PersonName.check(value).isEmpty();
    }
  }
}
