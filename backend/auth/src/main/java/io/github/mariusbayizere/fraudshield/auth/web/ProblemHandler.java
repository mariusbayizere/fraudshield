package io.github.mariusbayizere.fraudshield.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Maps every failure of the staff and admin APIs to an RFC 9457 problem with a contract problem
 * type, a field-level {@code errors[]} for validation, and the request's correlation ID. Unexpected
 * errors are logged with the correlation ID and answered without internal detail.
 */
@RestControllerAdvice
public class ProblemHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);
  private static final MediaType PROBLEM = MediaType.parseMediaType(ProblemWriter.MEDIA_TYPE);

  /**
   * Renders a problem.
   *
   * @param problem the problem
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(ProblemException.class)
  public ResponseEntity<Map<String, Object>> problem(
      ProblemException problem, HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(PROBLEM);
    headers.setCacheControl("no-store");
    if (problem.retryAfterSeconds() != null) {
      headers.set(HttpHeaders.RETRY_AFTER, problem.retryAfterSeconds().toString());
    }
    return new ResponseEntity<>(
        ProblemWriter.body(problem, request), headers, HttpStatus.valueOf(problem.status()));
  }

  /**
   * Body validation: Bean Validation constraints mapped to contract codes.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> invalidBody(
      MethodArgumentNotValidException e, HttpServletRequest request) {
    List<FieldProblem> errors = new ArrayList<>();
    for (FieldError error : e.getBindingResult().getFieldErrors()) {
      errors.add(
          new FieldProblem(
              JsonNames.snake(error.getField()),
              ConstraintCodes.code(error.getCode(), error.getRejectedValue()),
              String.valueOf(error.getDefaultMessage())));
    }
    e.getBindingResult()
        .getGlobalErrors()
        .forEach(
            error ->
                errors.add(
                    new FieldProblem(
                        "",
                        ConstraintCodes.code(error.getCode(), null),
                        String.valueOf(error.getDefaultMessage()))));
    return problem(ProblemException.validation(errors), request);
  }

  /**
   * Parameter validation outside a body.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, Object>> invalidParameter(
      ConstraintViolationException e, HttpServletRequest request) {
    List<FieldProblem> errors = new ArrayList<>();
    for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
      String path = violation.getPropertyPath().toString();
      errors.add(
          new FieldProblem(
              JsonNames.snake(path.substring(path.lastIndexOf('.') + 1)),
              ConstraintCodes.code(
                  violation
                      .getConstraintDescriptor()
                      .getAnnotation()
                      .annotationType()
                      .getSimpleName(),
                  violation.getInvalidValue()),
              violation.getMessage()));
    }
    return problem(ProblemException.validation(errors), request);
  }

  /**
   * Unreadable bodies: malformed JSON, unknown fields, wrong JSON types, unsupported enum values.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> unreadable(
      HttpMessageNotReadableException e, HttpServletRequest request) {
    Throwable cause = e.getMostSpecificCause();
    FieldProblem error;
    if (cause instanceof UnrecognizedPropertyException unknown) {
      error = new FieldProblem(unknown.getPropertyName(), "unknown_field", "Unknown field");
    } else if (cause instanceof InvalidFormatException format
        && format.getTargetType() != null
        && format.getTargetType().isEnum()) {
      error = new FieldProblem(path(format), "unsupported_value", "Unsupported value");
    } else if (cause instanceof MismatchedInputException mismatch
        && !mismatch.getPath().isEmpty()) {
      error = new FieldProblem(path(mismatch), "type_mismatch", "Wrong JSON type");
    } else if (cause instanceof StreamReadException || cause instanceof DatabindException) {
      error = new FieldProblem("", "malformed_json", "The body is not valid JSON");
    } else {
      error = new FieldProblem("", "malformed_json", "The body is missing or not valid JSON");
    }
    return problem(ProblemException.validation(List.of(error)), request);
  }

  private static String path(DatabindException e) {
    StringBuilder path = new StringBuilder();
    e.getPath()
        .forEach(
            reference -> {
              if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                  path.append('.');
                }
                path.append(reference.getPropertyName());
              } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
              }
            });
    return path.toString();
  }

  /**
   * A required query parameter is missing.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> missingParameter(
      MissingServletRequestParameterException e, HttpServletRequest request) {
    return problem(
        ProblemException.validation(e.getParameterName(), "required", "Parameter is required"),
        request);
  }

  /**
   * A required header is missing (Idempotency-Key, X-CSRF-Token).
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<Map<String, Object>> missingHeader(
      MissingRequestHeaderException e, HttpServletRequest request) {
    return problem(
        ProblemException.validation(e.getHeaderName(), "required", "Header is required"), request);
  }

  /**
   * A path or query parameter of the wrong type.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> typeMismatch(
      MethodArgumentTypeMismatchException e, HttpServletRequest request) {
    Class<?> required = e.getRequiredType();
    boolean isEnum = required != null && required.isEnum();
    return problem(
        ProblemException.validation(
            JsonNames.snake(e.getName()),
            isEnum ? "unsupported_value" : "type_mismatch",
            isEnum ? "Unsupported value" : "Wrong type"),
        request);
  }

  /**
   * Content-Type is not JSON.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> unsupportedMediaType(
      HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
    return problem(
        ProblemException.of(
            "unsupported-media-type",
            415,
            "Unsupported media type",
            "Content-Type must be application/json"),
        request);
  }

  /**
   * The Accept header excludes JSON.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
  public ResponseEntity<Map<String, Object>> notAcceptable(
      HttpMediaTypeNotAcceptableException e, HttpServletRequest request) {
    return problem(
        ProblemException.of("not-acceptable", 406, "Not acceptable", "Accept must allow JSON"),
        request);
  }

  /**
   * No such resource or method.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
  public ResponseEntity<Map<String, Object>> notFound(Exception e, HttpServletRequest request) {
    return problem(ProblemException.notFound("The resource"), request);
  }

  /**
   * Method security refused the call.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Map<String, Object>> accessDenied(
      AccessDeniedException e, HttpServletRequest request) {
    return problem(ProblemException.forbidden(), request);
  }

  /**
   * Authentication failed inside a controller.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<Map<String, Object>> unauthenticated(
      AuthenticationException e, HttpServletRequest request) {
    return problem(ProblemException.unauthorized(), request);
  }

  /**
   * Anything else: logged with the correlation ID, answered without detail.
   *
   * @param e the exception
   * @param request the request
   * @return the response
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
    LOG.error("unexpected error, correlation_id={}", CorrelationIdFilter.of(request), e);
    return problem(
        ProblemException.of("internal", 500, "Internal error", "An unexpected error occurred"),
        request);
  }
}
