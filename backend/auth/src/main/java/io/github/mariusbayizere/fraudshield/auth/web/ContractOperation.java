package io.github.mariusbayizere.fraudshield.auth.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the contract operation a controller method implements. {@code AuthorisationMatrixTest}
 * requires it on every handler and checks the method, path and {@code @PreAuthorize} roles against
 * the OpenAPI document and the golden matrix (H.2: a test fails if any endpoint lacks an explicit
 * rule).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ContractOperation {

  /**
   * The contract operation ID.
   *
   * @return the ID
   */
  String value();
}
